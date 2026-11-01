//! Chat actions (SPEC 5.4, T56) and presence (SPEC 5.5, T57), in one-to-one chats.
//!
//! - Chat actions: «typing», «sending a photo / a file», «recording a voice message» and the end of
//!   it. At most one per contact every `ACTION_EVERY_MS`; the end of one (`Cancel`) goes at once,
//!   once. The receiving app clears an action after `ACTION_SHOWN_MS` unless a newer one comes.
//! - Presence: while the app is in front ([`Node::set_online`], said again about every 55 s), each
//!   contact with a route open to their phone hears «online», again after `PRESENCE_EVERY_MS`, and
//!   «offline» once the app leaves the front. A contact who says «online» hears ours back at once
//!   unless told lately, so neither waits for the other's next round. The receiver keeps when each
//!   was last seen, by its own clock, and shows «online» for `PRESENCE_TTL_MS` after the last one
//!   unless «offline» came. Only while our own last seen is shown (the default): hiding it sends
//!   nothing, keeps nothing of others' and forgets what was kept, as in Telegram. On this phone only.
//! - Both are transient: never stored as messages, receipted or queued, and not even their ids are
//!   kept. One goes at once over a route open to the contact's own phone right now (a LAN sighting,
//!   a p2p link, or the direct or p2p route that reached them within `ONLINE_MS` with no failure
//!   since), or not at all. Never through a Google or Nostr mailbox: it would only arrive stale.
//! - Only in an Olm session that already exists: none is started for them.

use super::{is_online, Inner, Node, State};
use crate::envelope::{ChatActionKind, Envelope};
use crate::frame;
use crate::olm;
use crate::store::Db;
use crate::transport::{PeerInfo, Transport};
use crate::util::now_ms;
use crate::{Result, UserId};
use std::sync::atomic::Ordering;
use std::sync::Arc;

/// At most one chat action per contact this often.
pub const ACTION_EVERY_MS: u64 = 5_000;
/// The receiving app shows an action this long unless a newer one comes.
pub const ACTION_SHOWN_MS: u64 = 6_000;
/// While the app stays in front, a contact hears «online» again after this long.
pub const PRESENCE_EVERY_MS: u64 = 50_000;
/// «Online» holds this long after the last one came, unless «offline» came since.
pub const PRESENCE_TTL_MS: u64 = 150_000;
/// meta key: our last seen is hidden (absent: shown, the default).
const META_LAST_SEEN_HIDDEN: &str = "last_seen_hidden";

/// The last action sent to one contact.
#[derive(Debug, Clone, Copy)]
pub(super) struct ActionSent {
    at: u64,
    ended: bool,
}

/// The last presence told to one contact.
#[derive(Debug, Clone, Copy)]
pub(super) struct PresenceSent {
    online: bool,
    at: u64,
}

/// Routes to the contact's own phone, with nothing that keeps a frame in between.
fn to_phone(transport: &str) -> bool {
    matches!(transport, "direct" | "p2p")
}

/// Whether a route as `reached` names it (`lan`, `direct`, `p2p`, `relay`) is this transport's.
fn via_of(transport: &str, via: &str) -> bool {
    match transport {
        "direct" => matches!(via, "lan" | "direct"),
        "p2p" => matches!(via, "p2p" | "relay"),
        _ => false,
    }
}

/// In the app at `now`: «online» came at `online_ms`, within `PRESENCE_TTL_MS`, and no «offline»
/// (at `offline_ms`) since.
pub fn in_app(online_ms: u64, offline_ms: u64, now: u64) -> bool {
    online_ms > 0 && online_ms > offline_ms && now.saturating_sub(online_ms) < PRESENCE_TTL_MS
}

/// Our last seen is shown (the default) or hidden.
pub(super) fn last_seen_shown(db: &Db) -> bool {
    db.meta(META_LAST_SEEN_HIDDEN).ok().flatten().is_none_or(|v| v.first() != Some(&1))
}

impl Node {
    /// Tell a contact what we are doing in their chat. Returns whether it went: false when it is too
    /// soon after the last one (or a `Cancel` with nothing to end), when no route to their phone is
    /// open now, for a blocked contact, and when no session with them exists yet. What does not go
    /// is dropped, never queued. Presence is [`Node::set_online`]'s, not this.
    pub fn chat_action(&self, peer: &UserId, kind: ChatActionKind) -> Result<bool> {
        if matches!(kind, ChatActionKind::Online | ChatActionKind::Offline) {
            return Ok(false);
        }
        let now = now_ms();
        let cancel = kind == ChatActionKind::Cancel;
        {
            let sent = self.inner.actions.lock().expect("actions");
            let last = sent.get(peer).copied();
            let due = match last {
                None => !cancel,
                Some(l) if cancel => !l.ended && now.saturating_sub(l.at) < ACTION_SHOWN_MS,
                Some(l) => now.saturating_sub(l.at) >= ACTION_EVERY_MS,
            };
            if !due {
                return Ok(false);
            }
        }
        let routes = self.inner.phone_routes(peer, now);
        if routes.is_empty() {
            return Ok(false);
        }
        let Some(info) = self.inner.peer_info(peer) else { return Ok(false) };
        let Some(frame) = self.inner.seal_transient(peer, &Envelope::chat_action(kind), now)? else { return Ok(false) };
        {
            let mut sent = self.inner.actions.lock().expect("actions");
            match sent.get_mut(peer) {
                Some(l) if cancel => l.ended = true,
                _ => {
                    sent.insert(*peer, ActionSent { at: now, ended: false });
                }
            }
        }
        Ok(self.inner.spawn_transient(routes, info, frame))
    }

    /// The app came to the front (`true`; the app says so again about every 55 s while it stays
    /// there) or left it. In front, every contact with a route open to their phone hears «online»
    /// (not again within `PRESENCE_EVERY_MS`); on leaving, those told «online» hear «offline».
    /// Nothing while our last seen is hidden. Returns how many contacts were told.
    pub fn set_online(&self, online: bool) -> Result<usize> {
        self.inner.in_front.store(online, Ordering::SeqCst);
        let now = now_ms();
        let targets: Vec<UserId> = {
            let st = self.inner.state.lock().expect("state");
            let db = st.store.db();
            if !last_seen_shown(&db) {
                return Ok(0);
            }
            if online {
                let mut out = Vec::new();
                for c in db.contacts()?.into_iter().filter(|c| !c.blocked) {
                    if !db.is_group_peer(&c.sk)? {
                        out.push(c.sk);
                    }
                }
                out
            } else {
                self.inner.told_online(now)
            }
        };
        let mut told = 0;
        for sk in targets {
            if self.inner.tell_presence(&sk, online, now)? {
                told += 1;
            }
        }
        Ok(told)
    }

    /// Show our last seen (the default) or hide it. Hiding tells «offline» at once to whoever was
    /// told «online», sends no presence after, and keeps nothing of others' (what was kept goes): as
    /// in Telegram, whoever hides theirs sees nobody's. Showing it again while in front says
    /// «online» at once. On this phone only.
    pub fn set_last_seen_visible(&self, visible: bool) -> Result<()> {
        if self.last_seen_visible() == visible {
            return Ok(());
        }
        let now = now_ms();
        if !visible {
            for sk in self.inner.told_online(now) {
                let _ = self.inner.tell_presence(&sk, false, now);
            }
        }
        self.inner.state.lock().expect("state").store.transaction(|db| {
            db.set_meta(META_LAST_SEEN_HIDDEN, &[u8::from(!visible)])?;
            if !visible {
                db.presence_clear()?;
            }
            Ok(())
        })?;
        if visible && self.inner.in_front.load(Ordering::SeqCst) {
            self.set_online(true)?;
        }
        Ok(())
    }

    /// Whether our last seen is shown (the default).
    pub fn last_seen_visible(&self) -> bool {
        last_seen_shown(&self.inner.state.lock().expect("state").store.db())
    }
}

impl Inner {
    /// Contacts told «online» within `PRESENCE_TTL_MS`.
    fn told_online(&self, now: u64) -> Vec<UserId> {
        let told = self.presence_sent.lock().expect("presence");
        told.iter().filter(|(_, p)| p.online && now.saturating_sub(p.at) < PRESENCE_TTL_MS).map(|(sk, _)| *sk).collect()
    }

    /// Tell one contact «online» or «offline» now, over a route open to their phone: «online» not
    /// again within `PRESENCE_EVERY_MS`, «offline» only after an «online». True if it went.
    pub(super) fn tell_presence(&self, peer: &UserId, online: bool, now: u64) -> Result<bool> {
        let due = match self.presence_sent.lock().expect("presence").get(peer).copied() {
            None => online,
            Some(p) if online => !p.online || now.saturating_sub(p.at) >= PRESENCE_EVERY_MS,
            Some(p) => p.online,
        };
        if !due {
            return Ok(false);
        }
        let routes = self.phone_routes(peer, now);
        if routes.is_empty() {
            return Ok(false);
        }
        let Some(info) = self.peer_info(peer) else { return Ok(false) };
        let kind = if online { ChatActionKind::Online } else { ChatActionKind::Offline };
        let Some(frame) = self.seal_transient(peer, &Envelope::chat_action(kind), now)? else { return Ok(false) };
        self.presence_sent.lock().expect("presence").insert(*peer, PresenceSent { online, at: now });
        Ok(self.spawn_transient(routes, info, frame))
    }

    /// A contact said «online»: while we are in front (and shown, the caller checked), they hear
    /// ours back at once unless told lately.
    pub(super) fn answer_presence(&self, peer: &UserId) {
        if self.in_front.load(Ordering::SeqCst) {
            if let Err(e) = self.tell_presence(peer, true, now_ms()) {
                log::debug!("node: presence not answered ({e})");
            }
        }
    }

    /// Send one transient frame on the first of `routes` that takes it, on a thread of its own;
    /// none is tried again. A session that got through says the contact is reachable (SPEC 15.6),
    /// which keeps that route open for the next one. False when no thread could start.
    pub(super) fn spawn_transient(&self, routes: Vec<Arc<dyn Transport>>, info: PeerInfo, frame: Vec<u8>) -> bool {
        let me = self.me.clone();
        std::thread::Builder::new()
            .name("mlmchat-action".into())
            .spawn(move || {
                for t in routes {
                    if me.upgrade().is_none_or(|i| i.stop.load(Ordering::SeqCst)) {
                        return;
                    }
                    if let Ok(sent) = t.send(&info, std::slice::from_ref(&frame)) {
                        if let Some(i) = me.upgrade() {
                            i.reached(info.sk, sent.via);
                        }
                        return;
                    }
                }
                log::debug!("node: a transient frame found no open route");
            })
            .is_ok()
    }

    /// The transports with a route open to the contact's phone right now: a fresh sighting or
    /// link of their own, or the one that reached them within `ONLINE_MS` with no failure since.
    pub(super) fn phone_routes(&self, peer: &UserId, now: u64) -> Vec<Arc<dyn Transport>> {
        let (reach, failed) = {
            let st = self.state.lock().expect("state");
            let db = st.store.db();
            (db.reach(peer).ok().flatten(), db.unreach(peer).unwrap_or(0))
        };
        let recent = reach.filter(|(at, _)| is_online(*at, failed, now)).map(|(_, via)| via);
        let transports: Vec<Arc<dyn Transport>> = self.transports.lock().expect("transport").clone();
        transports
            .into_iter()
            .filter(|t| to_phone(t.name()))
            .filter(|t| t.has_fresh_route(peer) || recent.as_deref().is_some_and(|via| via_of(t.name(), via)))
            .collect()
    }

    /// The frame of a transient envelope for a contact, in the Olm session we already have with
    /// them (the ratchet step saved at once). None for a blocked contact or one with no session.
    pub(super) fn seal_transient(&self, peer: &UserId, env: &Envelope, now: u64) -> Result<Option<Vec<u8>>> {
        let bytes = env.to_bytes()?;
        let mut guard = self.state.lock().expect("state");
        let State { store, identity } = &mut *guard;
        let Some(contact) = store.db().contact(peer)?.filter(|c| !c.blocked) else { return Ok(None) };
        let mut sessions = olm::load(store.db().sessions(peer)?)?;
        if olm::winner(&sessions, &identity.sk(), peer).is_none() {
            return Ok(None);
        }
        let enc = olm::encrypt(identity, &mut sessions, peer, &contact.ik, &contact.otk, &bytes, now)?;
        let frame = frame::encode_padded(&[], enc.kind, &enc.cipher)?;
        store.transaction(|db| {
            for s in sessions.iter().filter(|s| s.dirty) {
                db.save_session(&s.to_row())?;
            }
            Ok(())
        })?;
        Ok(Some(frame))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_routes_to_the_phone_itself() {
        assert!(to_phone("direct") && to_phone("p2p"));
        assert!(!to_phone("gas") && !to_phone("nostr") && !to_phone("ble"));
        assert!(via_of("direct", "lan") && via_of("direct", "direct") && via_of("p2p", "relay"));
        assert!(!via_of("direct", "p2p") && !via_of("p2p", "lan") && !via_of("gas", "gas"));
    }

    #[test]
    fn online_holds_until_offline_or_the_ttl() {
        assert!(!in_app(0, 0, 10));
        assert!(in_app(1_000, 0, 1_000 + PRESENCE_TTL_MS - 1));
        assert!(!in_app(1_000, 0, 1_000 + PRESENCE_TTL_MS));
        assert!(!in_app(1_000, 2_000, 3_000), "«offline» came after");
        assert!(in_app(3_000, 2_000, 4_000), "«online» again");
    }
}
