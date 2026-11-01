//! The official chats' items on our own relays, once for everyone (SPEC 18.7, T77;
//! `transport::nostr::feedrelay`). What the Nostr transport reads there comes here and is kept
//! exactly like an item from a contact; this phone's own items go there at once, and an admin's
//! phone leaves there every item of the last days the relay lacks (posts that reached it from phone
//! to phone, from a poster whose network does not reach the relay). Each item is one event, the
//! same from whoever leaves it, so the relay keeps a single copy.

use super::{feeds, Event, Inner};
use crate::feed::SignedFeedItem;
use crate::transport::nostr::{feedrelay, files};
use crate::util::now_ms;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

/// Items this old no longer go there (the relay keeps a week; phones read three days back).
pub const PUSH_WITHIN_MS: u64 = 3 * 24 * 3600 * 1000;
/// An admin looks for items the relay lacks this often.
pub const ADMIN_EVERY_MS: u64 = 10 * 60 * 1000;
/// After a failed try the next waits this long, doubling up to an hour.
const RETRY_MS: u64 = 60_000;
const RELAY_TIMEOUT: Duration = Duration::from_secs(15);
/// Items written in one go.
const PUT_BATCH: usize = 32;

fn backoff(tries: u32) -> u64 {
    RETRY_MS.saturating_mul(1 << tries.min(6)).min(3600 * 1000)
}

impl Inner {
    /// Items read on our relays: kept as from a contact (shape, signature, the author's right), and
    /// known to be there. Never passed on to contacts: each phone reads them there itself.
    pub(super) fn take_relay_items(&self, items: &[SignedFeedItem]) {
        let feeds = self.feed_list();
        if feeds.is_empty() || items.is_empty() {
            return;
        }
        let ids: Vec<Vec<u8>> = items.iter().filter_map(|s| s.open_kept().ok().map(|i| i.id)).collect();
        let changed = {
            let mut st = self.state.lock().expect("state");
            st.store.transaction(|db| {
                let (_, changed) = feeds::keep(db, &feeds, items)?;
                let refs: Vec<&[u8]> = ids.iter().map(Vec::as_slice).collect();
                db.feed_relay_mark(&refs, now_ms())?;
                Ok(changed)
            })
        };
        match changed {
            Ok(changed) => {
                for f in changed {
                    self.emit(Event::Feed { feed_id: f });
                }
            }
            Err(e) => log::info!("feed relay: items not kept ({e})"),
        }
    }

    /// Leave what is due on our relays, in the background, one go at a time: our own items at once
    /// (`feeds::feed_put` asks), and every `ADMIN_EVERY_MS` an admin's items the relay lacks.
    pub(super) fn feed_relay_tick(self: &Arc<Self>) {
        if self.file_relays().is_empty() || self.feed_relay_busy.swap(true, Ordering::SeqCst) {
            return;
        }
        let me = self.clone();
        let spawned = std::thread::Builder::new().name("mlmchat-feed-put".into()).spawn(move || {
            me.feed_relay_admin_scan();
            me.feed_relay_put_due();
            me.feed_relay_busy.store(false, Ordering::SeqCst);
        });
        if spawned.is_err() {
            self.feed_relay_busy.store(false, Ordering::SeqCst);
        }
    }

    /// An admin's every item of the last days that the relay lacks goes into the queue. Whether this
    /// phone is an admin comes from the cache (`Node::feed_state`): a phone that is none loads nothing.
    fn feed_relay_admin_scan(&self) {
        let now = now_ms();
        if now.saturating_sub(self.feed_relay_scanned.load(Ordering::SeqCst)) < ADMIN_EVERY_MS {
            return;
        }
        self.feed_relay_scanned.store(now, Ordering::SeqCst);
        let me = *self.keys.read().expect("keys");
        for d in self.feed_list() {
            // an admin's phone shows the official chats, so their state is in the cache
            let admin = self.feed_cache.lock().expect("feed_cache").get(&d.feed_id).is_some_and(|(_, _, s)| s.is_admin(&me.0));
            if !admin {
                continue;
            }
            let st = self.state.lock().expect("state");
            let db = st.store.db();
            let Ok((state, items)) = feeds::load(&db, &d, now) else { continue };
            if !state.is_admin(&me.0) {
                continue;
            }
            for (i, s) in items {
                if state.valid.contains(&i.id) && now.saturating_sub(i.ts_ms) < PUSH_WITHIN_MS && !db.feed_relay_has(&i.id).unwrap_or(true) {
                    let _ = db.feed_relay_queue(&i.id, &i.feed_id, &s.to_bytes(), i.ts_ms);
                }
            }
        }
    }

    /// The queue to our relays, a batch at a time while one of them takes it all.
    fn feed_relay_put_due(&self) {
        let relays = self.file_relays();
        loop {
            let now = now_ms();
            let due = {
                let st = self.state.lock().expect("state");
                let db = st.store.db();
                let _ = db.feed_relay_forget(now.saturating_sub(PUSH_WITHIN_MS));
                db.feed_relay_due(now, PUT_BATCH).unwrap_or_default()
            };
            if due.is_empty() || self.stop.load(Ordering::SeqCst) {
                return;
            }
            let events: Vec<_> = due
                .iter()
                .filter_map(|(id, feed, body, ts, _)| SignedFeedItem::from_bytes(body).ok().and_then(|s| feedrelay::event_for(&s, feed, id, *ts)))
                .collect();
            let took = relays.iter().any(|url| match files::Writer::open(url, RELAY_TIMEOUT) {
                Some(mut w) => {
                    let n = w.write(&events);
                    w.close();
                    n == events.len()
                }
                None => false,
            });
            let st = self.state.lock().expect("state");
            let db = st.store.db();
            if took {
                let ids: Vec<&[u8]> = due.iter().map(|(id, ..)| id.as_slice()).collect();
                let _ = db.feed_relay_mark(&ids, now);
                log::info!("feed relay: {} items left on our relay", due.len());
            } else {
                for (id, _, _, _, tries) in &due {
                    let _ = db.feed_relay_retry(id, now + backoff(*tries), tries + 1);
                }
                log::info!("feed relay: our relay did not take the items yet");
                return;
            }
        }
    }
}
