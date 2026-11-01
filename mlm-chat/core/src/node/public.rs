//! Public relays (SPEC 19.12; Ehsan, 25 Oct 2026: «رلهٔ عمومی برای کمک به دیگران رو بساز»): a
//! mailbox its owner opens to everyone, for phones with no relay of their own.
//!
//! - The owner switches it on (`gas_relay_set_public`). The script itself keeps the switch
//!   (version 2, `pub`, set only with the owner key), so nobody else can open someone's relay:
//!   a contact knows its key of use from the owner's card, but not the owner key. The owner's
//!   phone signs a record of it (`PublicRelay`) and says it again every day while it stays open.
//! - Records ride the official feeds' catch-up (`FeedMsg.public_relays`) to every phone; each
//!   keeps the newest `MAX_KEPT`. A phone reached for the first time gets them at once.
//! - A phone with no relay of its own (none made, none added by hand) borrows up to `BORROW` of
//!   them, each only after the relay itself says it is public. Its cards name them, so contacts
//!   leave its messages there. It asks again every `CHECK_MS`, and drops one that closed, is
//!   paused, filled its devices for the day, or keeps failing, for another. Once the user has a
//!   relay of their own, the borrowed ones go.
//! - The owner sees how many devices used it each day (the script's own count, «stats»).

use super::{Inner, Node, State};
use crate::envelope::{PublicRelay, SignedPublicRelay, MAX_PUBLIC_RELAYS};
use crate::identity::Identity;
use crate::store::{Db, GasRelayRow};
use crate::transport::gas::proto::{RelayRef, DAY_MS, MAX_NAME_BYTES};
use crate::util::{now_ms, random};
use crate::{Error, Result, UserId};
use prost::Message;
use std::collections::HashSet;
use std::time::Duration;

/// What an announcer signs: this, then the encoded record.
pub const DOMAIN: &[u8] = b"mlmchat/public-relay/1";
/// A record is good this long; the owner's phone renews it every `RENEW_MS`.
const LIFE_MS: u64 = 7 * DAY_MS;
const RENEW_MS: u64 = DAY_MS;
/// Records kept.
pub const MAX_KEPT: usize = 200;
/// Relays a phone with none of its own borrows.
pub const BORROW: usize = 2;
/// A borrowed relay is asked again this often whether it is still public.
const CHECK_MS: u64 = 6 * 3600 * 1000;
/// A relay that failed is not tried again before this; one that is closed, not before the next.
const AVOID_FAIL_MS: u64 = 3600 * 1000;
const AVOID_CLOSED_MS: u64 = DAY_MS;
/// Failures in a row after which a borrowed relay is given up.
const GIVE_UP_STREAK: u32 = 3;
/// Devices a day a relay serves once its owner opens it, if it had the new-relay limit; the owner
/// changes it after.
pub const PUBLIC_MAX_CLIENTS: u32 = 200;
const NEW_RELAY_MAX_CLIENTS: u64 = 40;
/// meta: `gas_pub:<rid>` = [1] while we keep that relay of ours open; `gas_borrow_off` = [1]
/// when the user switched borrowing off.
const META_PUB: &str = "gas_pub:";
const META_BORROW_OFF: &str = "gas_borrow_off";
/// How often the borrowing is looked at, and the first time after the start.
pub(super) const TICK: Duration = Duration::from_secs(300);
pub(super) const FIRST_TICK: Duration = Duration::from_secs(20);

/// A public relay as the screens show it.
#[derive(Debug, Clone, serde::Serialize)]
pub struct PublicRelayView {
    pub rid: String,
    pub name: String,
    /// The announcer's identity key (hex).
    pub owner: String,
    pub until_ms: u64,
    pub mine: bool,
    pub borrowed: bool,
    /// Not tried again before this (it failed, or said it is closed).
    pub avoid_until: u64,
}

/// A record whose signature and shape check, read.
pub(super) fn open(s: &SignedPublicRelay, now: u64) -> Option<(PublicRelay, UserId, RelayRef)> {
    let r = PublicRelay::decode(s.body.as_slice()).ok()?;
    let owner: UserId = r.owner.as_slice().try_into().ok()?;
    let sig: [u8; 64] = s.sig.as_slice().try_into().ok()?;
    let relay = RelayRef::from_bytes(&r.relay)?;
    if r.name.len() > MAX_NAME_BYTES || r.ts_ms > now + 5 * 60 * 1000 || r.until_ms > r.ts_ms + LIFE_MS + DAY_MS {
        return None;
    }
    let mut signed = DOMAIN.to_vec();
    signed.extend_from_slice(&s.body);
    if !crate::transport::session::verify(&owner, &signed, &sig) {
        return None;
    }
    Some((r, owner, relay))
}

fn sign(identity: &Identity, relay: &RelayRef, name: &str, now: u64, withdrawn: bool) -> SignedPublicRelay {
    let r = PublicRelay {
        relay: relay.to_bytes(),
        name: short_name(name),
        owner: identity.sk().to_vec(),
        ts_ms: now,
        until_ms: now + LIFE_MS,
        withdrawn,
    };
    let body = r.encode_to_vec();
    let mut signed = DOMAIN.to_vec();
    signed.extend_from_slice(&body);
    SignedPublicRelay { body, sig: identity.sign(&signed).to_vec() }
}

/// A name that fits a record: no control characters, at most `MAX_NAME_BYTES`, whole characters.
fn short_name(name: &str) -> String {
    let mut out = String::new();
    for c in name.trim().chars().filter(|c| !c.is_control()) {
        if out.len() + c.len_utf8() > MAX_NAME_BYTES {
            break;
        }
        out.push(c);
    }
    out
}

fn keep(db: &Db, s: &SignedPublicRelay, now: u64) -> Result<bool> {
    let Some((r, owner, _)) = open(s, now) else { return Ok(false) };
    if r.until_ms <= now {
        return Ok(false);
    }
    db.gas_public_save(&r.relay, &owner, &s.body, &s.sig, r.ts_ms, r.until_ms, r.withdrawn)
}

/// Inside a transaction: records from a peer, kept if they check and are newer. True if any
/// changed what is kept.
pub(super) fn take_in(db: &Db, items: &[SignedPublicRelay], now: u64) -> Result<bool> {
    let mut changed = false;
    for s in items {
        changed |= keep(db, s, now)?;
    }
    if changed {
        db.gas_public_prune(now, MAX_KEPT)?;
    }
    Ok(changed)
}

/// The records that go out with the catch-up: the newest still good (withdrawals too, so they
/// spread like the rest).
pub(super) fn out(db: &Db, now: u64) -> Result<Vec<SignedPublicRelay>> {
    Ok(db.gas_public_all()?.into_iter().filter(|p| p.until_ms > now).take(MAX_PUBLIC_RELAYS).map(|p| SignedPublicRelay { body: p.body, sig: p.sig }).collect())
}

pub(super) fn is_open(db: &Db, rid: &str) -> bool {
    db.meta(&format!("{META_PUB}{rid}")).ok().flatten().is_some_and(|v| v == [1])
}

pub(super) fn borrow_on(db: &Db) -> bool {
    !db.meta(META_BORROW_OFF).ok().flatten().is_some_and(|v| v == [1])
}

impl Inner {
    /// Our open relays' records: made, or said again once a day.
    pub(super) fn public_renew(&self) {
        let mut guard = self.state.lock().expect("state");
        let State { store, identity } = &mut *guard;
        let db = store.db();
        let now = now_ms();
        let (Ok(rows), Ok(kept)) = (db.gas_relays(), db.gas_public_all()) else { return };
        let me = identity.sk();
        for r in rows.iter().filter(|r| r.mine && !r.id.is_empty() && is_open(&db, &r.rid)) {
            let relay = r.relay_ref();
            let bytes = relay.to_bytes();
            let fresh = kept.iter().any(|k| k.relay == bytes && k.owner == me && !k.withdrawn && now < k.ts_ms + RENEW_MS);
            if !fresh {
                let _ = keep(&db, &sign(identity, &relay, &r.name, now, false), now);
            }
        }
    }

    /// Borrowing (see the module comment): give up relays that closed or keep failing, borrow
    /// new ones while there is no relay of our own. Blocking (it asks relays over the network);
    /// runs on its own thread.
    pub(super) fn public_tick(&self) {
        let _one = self.public_busy.lock().expect("public");
        self.public_renew();
        let Some(g) = self.gas.lock().expect("gas").clone() else { return };
        let now = now_ms();
        let (rows, borrowed, records, auto, me) = {
            let st = self.state.lock().expect("state");
            let db = st.store.db();
            (db.gas_relays().unwrap_or_default(), db.gas_borrowed_all().unwrap_or_default(), db.gas_public_all().unwrap_or_default(), borrow_on(&db), st.identity.sk())
        };
        let borrowed_rids: HashSet<&str> = borrowed.iter().map(|b| b.0.as_str()).collect();
        let own = rows.iter().any(|r| !r.id.is_empty() && r.enabled && !borrowed_rids.contains(r.rid.as_str()));
        let health = g.health();
        let mut changed = false;
        let mut kept = 0;
        // those borrowed: still wanted, still public, still working?
        for (rid, relay, _since, checked) in &borrowed {
            let rref = RelayRef::from_bytes(relay);
            let listed = rows.iter().any(|r| r.rid == *rid);
            let mut drop = own || !auto || rref.is_none() || !listed;
            let mut avoid = 0;
            // its owner closed it, and the news came with the feeds: no need to ask the relay
            if !drop && records.iter().any(|p| p.relay == *relay && p.withdrawn) {
                drop = true;
                avoid = AVOID_CLOSED_MS;
            }
            if !drop {
                let h = rref.as_ref().and_then(|r| health.get(&r.id));
                if h.is_some_and(|h| matches!(h.state, "not_found" | "access" | "auth" | "paused" | "limit" | "quota") && h.streak >= GIVE_UP_STREAK) {
                    drop = true;
                    avoid = AVOID_FAIL_MS;
                }
            }
            if !drop && now >= checked + CHECK_MS {
                match g.public_probe(rref.as_ref().expect("checked")) {
                    Ok(true) => {
                        let _ = self.state.lock().expect("state").store.db().gas_borrowed_checked(rid, now);
                    }
                    Ok(false) => {
                        drop = true;
                        avoid = AVOID_CLOSED_MS;
                    }
                    Err(_) => {} // failures count in its health, above
                }
            }
            if drop {
                let st = self.state.lock().expect("state");
                let db = st.store.db();
                let _ = db.gas_relay_delete(rid);
                let _ = db.gas_borrowed_remove(rid);
                if avoid > 0 {
                    let _ = db.gas_public_avoid(relay, now + avoid);
                }
                changed = true;
                log::info!("gas: a borrowed public relay given back");
            } else {
                kept += 1;
            }
        }
        // borrow while we have no relay of our own
        if !own && auto && kept < BORROW {
            let listed: HashSet<&str> = rows.iter().map(|r| r.id.as_str()).collect();
            let mut cands: Vec<(u64, RelayRef, String)> = records
                .iter()
                .filter(|p| !p.withdrawn && p.until_ms > now && p.avoid_until <= now && p.owner != me)
                .filter_map(|p| {
                    let r = PublicRelay::decode(p.body.as_slice()).ok()?;
                    let rref = RelayRef::from_bytes(&p.relay)?;
                    (!listed.contains(rref.id.as_str())).then(|| (u64::from_le_bytes(random::<8>()), rref, r.name))
                })
                .collect();
            cands.sort_by_key(|c| c.0);
            for (_, rref, name) in cands {
                if kept >= BORROW {
                    break;
                }
                match g.public_probe(&rref) {
                    Ok(true) => {
                        let st = self.state.lock().expect("state");
                        let db = st.store.db();
                        let pos = db.gas_relays().unwrap_or_default().iter().map(|r| r.pos).max().unwrap_or(0) + 1;
                        let row = GasRelayRow {
                            rid: rref.rid(),
                            id: rref.id.clone(),
                            use_key: rref.key,
                            owner_key: None,
                            name: if name.trim().is_empty() { "public".to_string() } else { name },
                            added_ms: now,
                            enabled: true,
                            announce: true,
                            pos,
                            mine: false,
                            worker: rref.worker.clone(),
                        };
                        if db.gas_relay_save(&row).is_ok() && db.gas_borrowed_add(&row.rid, &rref.to_bytes(), now).is_ok() {
                            kept += 1;
                            changed = true;
                            log::info!("gas: a public relay borrowed");
                        }
                    }
                    Ok(false) => {
                        let _ = self.state.lock().expect("state").store.db().gas_public_avoid(&rref.to_bytes(), now + AVOID_CLOSED_MS);
                    }
                    Err(_) => {
                        let _ = self.state.lock().expect("state").store.db().gas_public_avoid(&rref.to_bytes(), now + AVOID_FAIL_MS);
                    }
                }
            }
        }
        if changed {
            self.gas_changed();
        }
    }
}

impl Node {
    /// Open one of our relays to everyone (`on`), or close it again. The script keeps the switch
    /// (version 2; an older one: `Error::Relay("script_old")`, its code must be put in again);
    /// then every phone hears of it with the official feeds. Opening raises the devices a day of a
    /// relay that still has the new-relay limit to `PUBLIC_MAX_CLIENTS`. Blocking.
    pub fn gas_relay_set_public(&self, rid: &str, on: bool) -> Result<()> {
        let r = self.inner.state.lock().expect("state").store.db().gas_relay(rid)?.ok_or(Error::Relay("not_found"))?;
        if r.id.is_empty() {
            return Err(Error::Relay("draft"));
        }
        let owner = r.owner_key.ok_or(Error::Relay("not_mine"))?;
        let g = self.gas().ok_or(Error::Relay("not_started"))?;
        let relay = r.relay_ref();
        // opening it to everyone also ends a pause: a paused relay serves no one but its owner, and
        // the probe that followed read the pause as an old script (Ehsan, 25 Oct 2026: «میگه
        // اسکریپت این رله قدیمی هست»). Closing it leaves the pause as it is.
        let v = g.admin(&relay, &owner, on.then_some(false), None, Some(on)).map_err(|e| Error::Relay(e.code()))?;
        if on {
            // the script answers with the settings it kept: one older than version 2 has no `pub`
            let kept = v.get("cfg").and_then(|c| c.get("pub")).and_then(serde_json::Value::as_bool) == Some(true);
            if !kept {
                let _ = g.admin(&relay, &owner, None, None, Some(false));
                return Err(Error::Relay("script_old"));
            }
            if v.get("cfg").and_then(|c| c.get("max_clients")).and_then(serde_json::Value::as_u64) == Some(NEW_RELAY_MAX_CLIENTS) {
                let _ = g.admin(&relay, &owner, None, Some(PUBLIC_MAX_CLIENTS), None);
            }
        }
        {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let db = store.db();
            db.set_meta(&format!("{META_PUB}{rid}"), if on { &[1] } else { &[] })?;
            let now = now_ms();
            keep(&db, &sign(identity, &relay, &r.name, now, !on), now)?;
        }
        self.inner.feeds_sync(true);
        Ok(())
    }

    /// Whether a phone with no relay of its own borrows public ones (on unless switched off).
    pub fn gas_borrow_auto(&self) -> bool {
        borrow_on(&self.inner.state.lock().expect("state").store.db())
    }

    /// Switch borrowing on or off; off gives the borrowed relays back at the next look.
    pub fn gas_set_borrow_auto(&self, on: bool) -> Result<()> {
        self.inner.state.lock().expect("state").store.db().set_meta(META_BORROW_OFF, if on { &[] } else { &[1] })?;
        Ok(())
    }

    /// Look at the borrowing now (blocking; the app's thread does it every few minutes).
    pub fn gas_public_tick_now(&self) {
        self.inner.public_tick();
    }

    /// The public relays this phone knows of, newest first.
    pub fn gas_public_list(&self) -> Result<Vec<PublicRelayView>> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let me = st.identity.sk();
        let now = now_ms();
        let borrowed: HashSet<Vec<u8>> = db.gas_borrowed_all()?.into_iter().map(|b| b.1).collect();
        Ok(db
            .gas_public_all()?
            .into_iter()
            .filter(|p| !p.withdrawn && p.until_ms > now)
            .filter_map(|p| {
                let r = PublicRelay::decode(p.body.as_slice()).ok()?;
                let rref = RelayRef::from_bytes(&p.relay)?;
                Some(PublicRelayView { rid: rref.rid(), name: r.name, owner: hex::encode(p.owner), until_ms: p.until_ms, mine: p.owner == me, borrowed: borrowed.contains(&p.relay), avoid_until: p.avoid_until })
            })
            .collect())
    }

    /// Whether one of our relays is open to everyone (for the screens).
    pub fn gas_relay_is_public(&self, rid: &str) -> bool {
        is_open(&self.inner.state.lock().expect("state").store.db(), rid)
    }

    /// Whether a relay of the list is one borrowed (for the screens).
    pub fn gas_relay_is_borrowed(&self, rid: &str) -> bool {
        self.inner.state.lock().expect("state").store.db().gas_borrowed_all().unwrap_or_default().iter().any(|b| b.0 == rid)
    }
}
