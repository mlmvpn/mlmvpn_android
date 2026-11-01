//! The official feeds' copy in a Google relay (SPEC 18.2, T50; Ehsan, 25 Oct 2026: «رلهٔ رسمی
//! گوگل»). Two testers installed Gereh and saw the official chats empty with one member, and the
//! owner never saw them counted: posts and count sketches went only from contact to contact, and a
//! new phone has none. Now every phone also uses a relay whose key ships with the app:
//!
//! - It reads the copy `MIRROR_FIRST_MS` after the start, every `MIRROR_EVERY_MS` (every
//!   `MIRROR_FRONT_MS` while the app is in front), every `MIRROR_WATCH_MS` while an official chat
//!   is on the screen, and when an official chat is opened (at most every `MIRROR_SOON_MS`): what
//!   came after its cursor, a page at a time. After a failed round the next waits, longer each
//!   time. Items are kept exactly like items from a contact (shape, signature, the author's right
//!   in the feed): the copy can withhold an item, never forge or change one.
//! - It merges the copy's member count sketches into its own, and leaves its own there when they
//!   hold something the copy lacks: a new phone is counted by everyone within minutes.
//! - It leaves there its own new items at once, and an admin every item of the last
//!   `PUSH_WITHIN_MS` the copy lacks (posts that reached the admin from phone to phone).
//!
//! Items read from the copy are not passed on to contacts: each phone reads the copy itself, and
//! the usual catch-up between contacts still covers phones that cannot reach Google.

use super::{feeds, Event, Inner, Node};
use crate::envelope::CensusSketch;
use crate::feed::{Item, SignedFeedItem};
use crate::transport::gas::proto::{self, RelayRef};
use crate::transport::gas::MAX_FEED_PUT;
use crate::util::now_ms;
use crate::{Error, Result};
use std::sync::atomic::Ordering;

/// The first read after the start, and then the regular ones.
pub const MIRROR_FIRST_MS: u64 = 5_000;
pub const MIRROR_EVERY_MS: u64 = 20 * 60 * 1000;
/// The regular reads while the app is in front (`Node::set_online`).
pub const MIRROR_FRONT_MS: u64 = 10 * 60 * 1000;
/// An official chat on the screen (`Node::feed_watch`): read again this often while it stays there.
/// Ehsan, 29 Oct 2026, with hundreds in the official group: new posts came tens of minutes late
/// for everyone, since each phone read the copy every 20 minutes and once when the chat opened.
pub const MIRROR_WATCH_MS: u64 = 45_000;
/// An official chat opened, or an item of ours: read again, at most this often for the former.
pub const MIRROR_SOON_MS: u64 = 60_000;
/// After a failed round the next waits this long, doubling each time up to `MIRROR_EVERY_MS`, so
/// a relay out of quota or busy is not asked every few seconds by every phone watching.
pub const MIRROR_RETRY_MS: u64 = 30_000;

/// How long to wait after `fails` failed rounds in a row.
pub(super) fn retry_after(fails: u32) -> u64 {
    MIRROR_RETRY_MS.saturating_mul(1 << fails.saturating_sub(1).min(10)).min(MIRROR_EVERY_MS)
}
/// An admin leaves items this new that the copy lacks.
pub const PUSH_WITHIN_MS: u64 = 3 * 24 * 3600 * 1000;
/// Pages read in one go (a new phone: a few; the cursor keeps the place for the next go).
const MAX_PAGES: usize = 20;
/// meta key: where reading the copy goes on from.
const META_CURSOR: &str = "mirror_cursor";

impl Inner {
    /// Read the copy soon: now when `now` (an item of ours), else unless it was read in the last
    /// `MIRROR_SOON_MS` (an official chat opened).
    pub(super) fn mirror_soon(&self, now: bool) {
        let t = now_ms();
        if now || t.saturating_sub(self.mirror_last.load(Ordering::SeqCst)) >= MIRROR_SOON_MS {
            self.mirror_due.store(t, Ordering::SeqCst);
        }
    }

    /// An official chat is on the screen: read the copy now unless it was read in the last
    /// `MIRROR_WATCH_MS`.
    pub(super) fn mirror_watch(&self) {
        let t = now_ms();
        if t.saturating_sub(self.mirror_last.load(Ordering::SeqCst)) >= MIRROR_WATCH_MS {
            self.mirror_due.store(t, Ordering::SeqCst);
        }
    }

    /// One round with the copy: read what is new, merge the count, leave what it lacks. Blocking;
    /// on the mirror thread. False when the relay failed us (the next round waits longer).
    pub(super) fn mirror_sync(&self) -> bool {
        let Some(relay) = self.mirror.read().expect("mirror").clone() else { return true };
        let Some(gas) = self.gas.lock().expect("gas").clone() else { return true };
        let feeds = self.feed_list();
        if feeds.is_empty() {
            return true;
        }
        self.mirror_last.store(now_ms(), Ordering::SeqCst);
        let ids: Vec<Vec<u8>> = feeds.iter().map(|d| d.feed_id.clone()).collect();
        let mut cursor = {
            let st = self.state.lock().expect("state");
            st.store.db().meta(META_CURSOR).ok().flatten().and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(u64::from_be_bytes).unwrap_or(0)
        };
        let mut census: Vec<CensusSketch> = Vec::new();
        let mut read = 0;
        for _ in 0..MAX_PAGES {
            let page = match gas.feed_get(&relay, &ids, cursor) {
                Ok(p) => p,
                Err(e) => {
                    log::info!("mirror: read failed ({})", e.code());
                    return false;
                }
            };
            let signed: Vec<SignedFeedItem> = page.items.iter().filter_map(|(_, _, _, b)| SignedFeedItem::from_bytes(b).ok()).collect();
            let seen: Vec<&[u8]> = page.items.iter().map(|(id, _, _, _)| &id[..]).collect();
            let now = now_ms();
            let changed = {
                let mut st = self.state.lock().expect("state");
                st.store.transaction(|db| {
                    let (_, changed) = feeds::keep(db, &feeds, &signed)?;
                    db.mirror_mark(&seen, now)?;
                    db.set_meta(META_CURSOR, &page.next.to_be_bytes())?;
                    Ok(changed)
                })
            };
            match changed {
                Ok(changed) => {
                    for f in changed {
                        self.emit(Event::Feed { feed_id: f });
                    }
                }
                Err(e) => {
                    log::info!("mirror: items not kept ({e})");
                    return false;
                }
            }
            read += page.items.len();
            census = page.census.into_iter().map(|(epoch, regs)| CensusSketch { epoch, regs }).collect();
            cursor = page.next;
            if !page.more {
                break;
            }
        }
        if read > 0 {
            log::info!("mirror: {read} items read");
        }
        // the count: the copy's sketches into ours, and ours there when they hold more
        let now = now_ms();
        let ours = {
            let mut st = self.state.lock().expect("state");
            st.store.transaction(|db| {
                let (ahead, took) = feeds::census_take(db, &census, now)?;
                let wire = if ahead { feeds::census_wire(&feeds::census_now(db, now)?) } else { Vec::new() };
                Ok((wire, took))
            })
        };
        let (sketches, took) = match ours {
            Ok(x) => x,
            Err(e) => {
                log::info!("mirror: count not merged ({e})");
                (Vec::new(), false)
            }
        };
        if took {
            for d in &feeds {
                self.emit(Event::Feed { feed_id: d.feed_id.clone() });
            }
        }
        // what goes there: ours, and an admin's every item of the last days, that the copy lacks.
        // Only when something of ours waits or we are an admin: loading both feeds whole under the
        // lock every round froze slow phones (1.2.44, 30 Oct 2026); else only the count, if ahead
        let up_due = self.mirror_up.swap(false, Ordering::SeqCst);
        if !up_due && !self.mirror_admin.load(Ordering::SeqCst) {
            if !sketches.is_empty() {
                let cs: Vec<(u32, Vec<u8>)> = sketches.into_iter().map(|c| (c.epoch, c.regs)).collect();
                if let Err(e) = gas.feed_put(&relay, &feeds[0].feed_id, &[], &cs) {
                    log::info!("mirror: count not left ({})", e.code());
                    return false;
                }
            }
            return true;
        }
        let mut admin_any = false;
        let up: Vec<(Vec<u8>, Vec<([u8; 16], Vec<u8>, u8)>)> = {
            let st = self.state.lock().expect("state");
            let db = st.store.db();
            let me = st.identity.sk();
            feeds
                .iter()
                .map(|d| {
                    let Ok((state, items)) = feeds::load(&db, d, now) else { return (d.feed_id.clone(), Vec::new()) };
                    let admin = state.is_admin(&me);
                    admin_any |= admin;
                    let list = items
                        .into_iter()
                        .filter(|(i, _)| state.valid.contains(&i.id) && now.saturating_sub(i.ts_ms) < PUSH_WITHIN_MS)
                        .filter(|(i, _)| admin || i.author[..] == me[..])
                        .filter(|(i, _)| !db.mirror_has(&i.id).unwrap_or(true))
                        .filter_map(|(i, s)| {
                            let id = <[u8; 16]>::try_from(i.id.as_slice()).ok()?;
                            let class = match i.item {
                                Some(Item::Admins(_)) | Some(Item::Info(_)) | Some(Item::Meta(_)) | Some(Item::Pin(_)) | Some(Item::Slow(_)) => 1,
                                Some(Item::React(_)) | Some(Item::Edit(_)) => 2,
                                _ => 0,
                            };
                            Some((id, s.to_bytes(), class))
                        })
                        .collect();
                    (d.feed_id.clone(), list)
                })
                .collect()
        };
        self.mirror_admin.store(admin_any, Ordering::SeqCst);
        let sketches: Vec<(u32, Vec<u8>)> = sketches.into_iter().map(|c| (c.epoch, c.regs)).collect();
        let mut census_left = !sketches.is_empty();
        let mut sent = 0;
        for (feed, items) in up {
            let mut chunks: Vec<&[([u8; 16], Vec<u8>, u8)]> = items.chunks(MAX_FEED_PUT).collect();
            if chunks.is_empty() && census_left {
                chunks.push(&[]);
            }
            for chunk in chunks {
                let cs: &[(u32, Vec<u8>)] = if census_left { &sketches } else { &[] };
                match gas.feed_put(&relay, &feed, chunk, cs) {
                    Ok(results) => {
                        census_left = false;
                        let there: Vec<&[u8]> = chunk.iter().zip(&results).filter(|(_, r)| **r != "bad").map(|((id, _, _), _)| &id[..]).collect();
                        sent += results.iter().filter(|r| **r == "ok").count();
                        let st = self.state.lock().expect("state");
                        let _ = st.store.db().mirror_mark(&there, now_ms());
                    }
                    Err(e) => {
                        log::info!("mirror: leave failed ({})", e.code());
                        self.mirror_up.store(true, Ordering::SeqCst);
                        return false;
                    }
                }
            }
        }
        if sent > 0 {
            log::info!("mirror: {sent} items left");
        }
        true
    }
}

impl Node {
    /// The official feeds' copy (SPEC 18.2): a relay's share link (`mlmchat://r/…`) that ships with
    /// the app, or `None` for none. Read soon after the mailbox transport starts.
    pub fn set_feed_mirror(&self, link: Option<&str>) -> Result<()> {
        let relay: Option<RelayRef> = match link {
            Some(l) => Some(proto::parse_share(l).map_err(|_| Error::Link("bad relay link"))?.relay),
            None => None,
        };
        *self.inner.mirror.write().expect("mirror") = relay;
        Ok(())
    }

    /// Read the official feeds' copy now and wait for it (tests, `peer-cli`).
    pub fn mirror_sync_now(&self) {
        self.inner.mirror_sync();
    }

    /// An official chat is on the screen: the app says so every few seconds while it stays there
    /// (Telegram's own short poll of an open channel), and the copy is read every
    /// `MIRROR_WATCH_MS` instead of every 20 minutes.
    pub fn feed_watch(&self) {
        self.inner.mirror_watch();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_failing_relay_is_asked_later_and_later() {
        assert_eq!(retry_after(1), MIRROR_RETRY_MS);
        assert_eq!(retry_after(2), 2 * MIRROR_RETRY_MS);
        assert_eq!(retry_after(3), 4 * MIRROR_RETRY_MS);
        assert_eq!(retry_after(20), MIRROR_EVERY_MS, "never longer than the regular wait");
        assert!(MIRROR_WATCH_MS < MIRROR_SOON_MS && MIRROR_FRONT_MS < MIRROR_EVERY_MS);
    }
}
