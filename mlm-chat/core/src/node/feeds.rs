//! Official feeds on the node (T38, `feed.rs`): keep what checks, pass new items on, catch up.
//!
//! - A new item (ours, or one that came and counts here) goes to every contact and group member
//!   reached in the last day (by any route, or a frame of theirs through our mailboxes), except
//!   the one it came from: each phone passes each item on once, so it spreads along the whole web
//!   of contacts and groups.
//! - Every `SYNC_EVERY_MS`, and as soon as a peer is reached for the first time since the start,
//!   each phone tells the peers it reached lately the newest ids it has of each feed (`FeedHave`,
//!   posts and reactions apart); a peer answers with what counts there and is missing here (newer
//!   than the oldest of a full list). A full answer brings the next round at once. So a phone that
//!   was off, or new, catches up from whoever it meets first, in seconds.
//! - A photo in a post is fetched from the phone that passed the post on (small ones at once).
//! - The member count (`census.rs`) and the public relays (`public.rs`) ride the catch-up: they go
//!   to a peer when they changed, when the peer is reached for the first time since the start, or
//!   every `CENSUS_RESEND_MS`; a peer whose count sketches lack something gets ours back.

use super::{enqueue, Event, Inner, Node, State};
use crate::census::{self, epoch_of, Sketch, PACKED_LEN};
use crate::envelope::{Body, CensusSketch, Envelope, FeedHave, FeedMsg, MAX_FEED_IDS, MAX_FEED_ITEMS};
use crate::feed::{FeedBan, FeedDelete, FeedDescriptor, FeedInfoItem, FeedItem, FeedMeta, FeedReact, FeedState, Item, SignedFeedItem, ITEM_ID_LEN, MAX_ABOUT, MAX_EMOJI, MAX_ITEMS, MAX_REACTS, MAX_TITLE};
use crate::identity::Identity;
use crate::store::Db;
use crate::util::{array, now_ms, random};
use crate::{Error, Result, UserId};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;

/// Peers reached within this long get new items; the rest read them from the official copy
/// (mirror.rs). A day made every phone encrypt each item for each contact of the day, which
/// flooded the outboxes and our relay once thousands wrote in the official group (30 Oct 2026).
const PASS_TO_MS: u64 = 10 * 60 * 1000;
/// Our card goes with one post a day in each feed (SPEC 18.4); meta key prefix of when it last did.
const CARD_EVERY_MS: u64 = 24 * 3600 * 1000;
const CARD_SENT: &str = "feed_card_sent_";

/// Inside a transaction: the card an item came with, kept when it is its author's own (the item's
/// signature vouches for it), the newest of each author.
fn remember_card(db: &Db, item: &FeedItem) -> Result<()> {
    if item.card.is_empty() || item.card.len() > crate::feed::MAX_ITEM_CARD {
        return Ok(());
    }
    let Ok(card) = crate::card::Card::from_bytes(&item.card) else { return Ok(()) };
    if card.sk[..] != item.author[..] || card.loc().is_none() {
        return Ok(());
    }
    db.feed_card_save(&card.sk, &item.card, item.ts_ms)
}

/// Inside a transaction: someone who posted in an official feed with their card, as a contact this
/// phone can send to (a hidden row, as for a group member, until the user adds them).
pub(super) fn author_contact(db: &Db, sk: &UserId) -> Result<Option<crate::store::Contact>> {
    let Some(bytes) = db.feed_card(sk)? else { return Ok(None) };
    let Ok(card) = crate::card::Card::from_bytes(&bytes) else { return Ok(None) };
    let Some(loc) = card.loc() else { return Ok(None) };
    if card.sk != *sk {
        return Ok(None);
    }
    let c = crate::store::Contact {
        sk: card.sk,
        ik: card.ik,
        hk: card.hk,
        otk: card.otk,
        loc: Some(loc),
        name: card.name.clone().unwrap_or_default(),
        card: card.to_bytes(),
        added_ms: now_ms(),
        by_them: false,
        blocked: false,
        verified: false,
    };
    db.upsert_contact(&c)?;
    db.mark_group_peer(sk)?;
    Ok(Some(c))
}
/// How often phones compare what they have.
pub const SYNC_EVERY_MS: u64 = 10 * 60 * 1000;
/// How often the delivery loop looks for peers due a catch-up.
pub const SYNC_TICK_MS: u64 = 30 * 1000;
/// How long `Node::feed_state` may hand out a state built before while the items stay the same:
/// the time counts only for a post dated ahead of the clock (`MAX_SKEW_MS`), shown once it is due.
pub(super) const STATE_KEEP_MS: u64 = 30 * 1000;

/// When each peer last got our "have" lists and our count sketches, and when changed sketches
/// last went to everyone.
#[derive(Default)]
pub(super) struct FeedSent {
    pub have: HashMap<UserId, u64>,
    pub census: HashMap<UserId, u64>,
    pub wide_ms: u64,
}
/// Our count sketches go with the catch-up when they changed, and at least this often.
const CENSUS_RESEND_MS: u64 = 6 * 3600 * 1000;
/// meta keys: the install secret the count tokens come from (never leaves the phone), and 16
/// bytes of digest of the count sketches and public relays that last went out.
const CENSUS_SECRET: &str = "census_secret";
const CENSUS_SENT: &str = "census_sent";
/// meta key: the official feeds' owner key (its secret half), on the one phone it was given to.
/// It signs the admin lists and nothing else, and never leaves the encrypted database.
const OWNER_SECRET: &str = "feed_owner_secret";

fn find<'a>(feeds: &'a [FeedDescriptor], feed_id: &[u8]) -> Option<&'a FeedDescriptor> {
    feeds.iter().find(|d| d.feed_id == feed_id)
}

/// Every item kept for a feed that still opens, and the state they make.
pub(super) fn load(db: &Db, d: &FeedDescriptor, now: u64) -> Result<(FeedState, Vec<(FeedItem, SignedFeedItem)>)> {
    let items: Vec<(FeedItem, SignedFeedItem)> = db.feed_items(&d.feed_id)?.into_iter().filter_map(|s| s.open_kept().ok().map(|i| (i, s))).collect();
    let plain: Vec<FeedItem> = items.iter().map(|(i, _)| i.clone()).collect();
    Ok((FeedState::build(d, &plain, now), items))
}

/// Inside a transaction: keep the items that check (shape, signature, a feed we know; an admin
/// list only from the feed's owner). Returns the new ones that count now, with their feed, and
/// the feeds that changed.
pub(super) fn keep(db: &Db, feeds: &[FeedDescriptor], items: &[SignedFeedItem]) -> Result<(Vec<(FeedItem, SignedFeedItem)>, Vec<Vec<u8>>)> {
    let now = now_ms();
    let mut fresh: Vec<(FeedItem, SignedFeedItem)> = Vec::new();
    let mut changed: Vec<Vec<u8>> = Vec::new();
    for s in items {
        let Ok(item) = s.open() else { continue };
        let Some(d) = find(feeds, &item.feed_id) else { continue };
        let admins = matches!(item.item, Some(Item::Admins(_)));
        if admins != (item.author[..] == d.owner[..]) {
            continue; // the owner key signs the admin list and nothing else
        }
        // admin lists, the photo and the name are never pruned; reactions and edits apart from the
        // posts, so many of them never push a post out
        let class = match item.item {
            Some(Item::Admins(_)) | Some(Item::Info(_)) | Some(Item::Meta(_)) | Some(Item::Pin(_)) | Some(Item::Slow(_)) => 1,
            Some(Item::React(_)) | Some(Item::Edit(_)) => 2,
            _ => 0,
        };
        if db.feed_item_save(&item.feed_id, &item.id, item.ts_ms, class, s)? {
            if !changed.contains(&item.feed_id) {
                changed.push(item.feed_id.clone());
            }
            // its file, if any, can be asked for from now on however the item came (SPEC 18.3),
            // and its author's card, if it came with one, opens a chat with them (SPEC 18.4)
            super::feedfiles::register(db, &item)?;
            remember_card(db, &item)?;
            // the app it was posted from (T75): the official chats' authors are seldom contacts
            if let Ok(author) = <UserId>::try_from(item.author.as_slice()) {
                db.client_seen(&author, item.client.as_ref(), item.ts_ms.min(now), now)?;
            }
            fresh.push((item, s.clone()));
        }
    }
    let mut counting = Vec::new();
    for f in &changed {
        db.feed_prune(f, MAX_ITEMS)?;
        db.feed_prune_reacts(f, MAX_REACTS)?;
        let Some(d) = find(feeds, f) else { continue };
        let (st, _) = load(db, d, now)?;
        counting.extend(fresh.iter().filter(|(i, _)| i.feed_id == *f && st.valid.contains(&i.id)).cloned());
    }
    Ok((counting, changed))
}

/// Who feed items and catch-ups go to: peers reached since `since` by any route, and those a frame
/// came from through our mailboxes since then (Google may be the only way between two phones; a
/// frame we left in theirs says nothing, so only what came from them counts).
fn feed_peers(db: &Db, since: u64) -> Result<Vec<UserId>> {
    let mut peers = db.reached_since(since)?;
    for p in db.gas_peers_since(since)? {
        if !peers.contains(&p) {
            peers.push(p);
        }
    }
    Ok(peers)
}

/// Inside a transaction: items to every peer reached lately, except `except`. True if queued.
fn pass_on(db: &Db, identity: &Identity, items: &[SignedFeedItem], except: Option<&UserId>) -> Result<bool> {
    if items.is_empty() {
        return Ok(false);
    }
    let me = identity.sk();
    let mut any = false;
    for peer in feed_peers(db, now_ms().saturating_sub(PASS_TO_MS))? {
        if peer == me || Some(&peer) == except {
            continue;
        }
        let Some(c) = db.contact(&peer)?.filter(|c| !c.blocked) else { continue };
        for chunk in items.chunks(MAX_FEED_ITEMS) {
            enqueue(db, identity, &c, &Envelope::new(Body::Feed(FeedMsg { items: chunk.to_vec(), have: Vec::new(), census: Vec::new(), public_relays: Vec::new() })), false)?;
            any = true;
        }
    }
    Ok(any)
}

/// Reactions and edits: their own have list and their own floor, apart from the posts.
fn is_react(i: &FeedItem) -> bool {
    matches!(i.item, Some(Item::React(_)) | Some(Item::Edit(_)))
}

/// The newest ids that count here, two `FeedHave`s per feed: one of posts and the rest, one of
/// reactions, so many reactions never push the posts out of the list.
fn have_of(db: &Db, d: &FeedDescriptor, now: u64) -> Result<Vec<FeedHave>> {
    let (st, items) = load(db, d, now)?;
    let mut out = Vec::new();
    for reacts in [false, true] {
        let ids: Vec<Vec<u8>> = items.iter().rev().filter(|(i, _)| st.valid.contains(&i.id) && is_react(i) == reacts).take(MAX_FEED_IDS).map(|(i, _)| i.id.clone()).collect();
        if !ids.is_empty() || !reacts {
            out.push(FeedHave { feed_id: d.feed_id.clone(), ids });
        }
    }
    Ok(out)
}

/// What a peer lacks of one feed, given all its `FeedHave`s of it, newest first, at most `room`.
/// A full list is the peer's newest window: what is older than its oldest is kept there already
/// (or pruned), so it is not sent again every sync.
fn missing(db: &Db, d: &FeedDescriptor, haves: &[&FeedHave], now: u64, room: usize) -> Result<Vec<SignedFeedItem>> {
    let (st, items) = load(db, d, now)?;
    let theirs: HashSet<&Vec<u8>> = haves.iter().flat_map(|h| h.ids.iter()).collect();
    // per class (posts, reactions): the oldest time in a full list of theirs
    let kept_always = |i: &FeedItem| matches!(i.item, Some(Item::Admins(_)) | Some(Item::Info(_)) | Some(Item::Meta(_)) | Some(Item::Pin(_)));
    let mut floor: [u64; 2] = [0, 0];
    for h in haves.iter().filter(|h| h.ids.len() >= MAX_FEED_IDS) {
        let ids: HashSet<&Vec<u8>> = h.ids.iter().collect();
        let mine: Vec<&FeedItem> = items.iter().map(|(i, _)| i).filter(|i| ids.contains(&i.id) && !kept_always(i)).collect();
        if let Some(first) = mine.first() {
            let class = usize::from(is_react(first));
            let oldest = mine.iter().map(|i| i.ts_ms).min().unwrap_or(0);
            floor[class] = floor[class].max(oldest);
        }
    }
    let mut out = Vec::new();
    for (i, s) in items.iter().rev() {
        if out.len() >= room {
            break;
        }
        // the admin list, photo and name stand however old they are
        if st.valid.contains(&i.id) && !theirs.contains(&i.id) && (kept_always(i) || i.ts_ms >= floor[usize::from(is_react(i))]) {
            out.push(s.clone());
        }
    }
    Ok(out)
}

// ---- the member count (census.rs) ----

/// One meta slot per period parity: the period, then the packed sketch.
fn census_slot(epoch: u32) -> String {
    format!("census_{}", epoch % 2)
}

fn census_get(db: &Db, epoch: u32) -> Result<Sketch> {
    if let Some(v) = db.meta(&census_slot(epoch))? {
        if v.len() == 4 + PACKED_LEN && v[..4] == epoch.to_be_bytes() {
            return Sketch::unpack(epoch, &v[4..]);
        }
    }
    Ok(Sketch::new(epoch))
}

fn census_put(db: &Db, s: &Sketch) -> Result<()> {
    let mut v = s.epoch.to_be_bytes().to_vec();
    v.extend(s.pack());
    db.set_meta(&census_slot(s.epoch), &v)
}

/// This period's and the last one's sketches, with this phone counted in this one.
pub(super) fn census_now(db: &Db, now: u64) -> Result<[Sketch; 2]> {
    let e = epoch_of(now);
    let secret = match db.meta(CENSUS_SECRET)?.and_then(|v| array::<32>(&v)) {
        Some(s) => s,
        None => {
            let s = random::<32>();
            db.set_meta(CENSUS_SECRET, &s)?;
            s
        }
    };
    let mut cur = census_get(db, e)?;
    if cur.add(&census::token(&secret, e)) {
        census_put(db, &cur)?;
    }
    let prev = census_get(db, e.saturating_sub(1))?;
    Ok([cur, prev])
}

pub(super) fn census_wire(sketches: &[Sketch; 2]) -> Vec<CensusSketch> {
    sketches.iter().filter(|s| !s.is_empty()).map(|s| CensusSketch { epoch: s.epoch, regs: s.pack() }).collect()
}

/// A peer's sketches into ours: whether theirs lack something ours have (they get ours back), and
/// whether ours took something from theirs (the count may have changed).
pub(super) fn census_take(db: &Db, theirs: &[CensusSketch], now: u64) -> Result<(bool, bool)> {
    let mut ahead = false;
    let mut took = false;
    for mut mine in census_now(db, now)? {
        let t = match theirs.iter().find(|c| c.epoch == mine.epoch) {
            Some(c) => Sketch::unpack(c.epoch, &c.regs)?,
            None => Sketch::new(mine.epoch),
        };
        if mine.merge(&t) {
            census_put(db, &mine)?;
            took = true;
        }
        ahead |= mine.ahead_of(&t);
    }
    Ok((ahead, took))
}

/// The count: phones active in this period or the last, estimated.
fn census_count(db: &Db, now: u64) -> Result<u64> {
    let [cur, prev] = census_now(db, now)?;
    Ok(cur.estimate_with(&prev).round() as u64)
}

/// What `on_feed` leaves for after the transaction.
#[derive(Default)]
pub(super) struct FeedOutcome {
    /// Frames were queued.
    pub wake: bool,
    /// A full page of items came that we lacked: there may be more, so our "have" lists go back
    /// to the sender at the next tick instead of in ten minutes (a new phone catches up quickly).
    pub more: bool,
}

/// Inside the Olm transaction: feed items and "have" lists from `from`.
pub(super) fn on_feed(db: &Db, identity: &Identity, feeds: &[FeedDescriptor], from: &UserId, m: &FeedMsg, events: &mut Vec<Event>) -> Result<FeedOutcome> {
    let now = now_ms();
    let (fresh, changed) = keep(db, feeds, &m.items)?;
    let more = m.items.len() >= MAX_FEED_ITEMS && !fresh.is_empty();
    // their count sketches: merged into ours; ours back if theirs lack something. A count that
    // changed shows at once (the app reads it again on a feed event)
    let (census_back, census_took) = if m.census.is_empty() { (false, false) } else { census_take(db, &m.census, now)? };
    let mut changed = changed;
    if census_took {
        for d in feeds {
            if !changed.contains(&d.feed_id) {
                changed.push(d.feed_id.clone());
            }
        }
    }
    for f in changed {
        events.push(Event::Feed { feed_id: f });
    }
    let mut wake = false;
    // a photo in a new post, or the chat's new photo: from the phone that passed it on
    for (item, _) in &fresh {
        if let Some(Item::Post(p)) = &item.item {
            if let Ok(Envelope { body: Some(Body::Media(media)), .. }) = Envelope::from_bytes(p) {
                if let (Some(id), Some(key)) = (array::<32>(&media.blob_id), array::<32>(&media.key)) {
                    wake |= super::media::expect_file(db, identity, from, &id, &key, media.size)?;
                }
            }
        }
        if let Some(Item::Info(FeedInfoItem { photo: Some(p) })) = &item.item {
            if let (Some(id), Some(key)) = (array::<32>(&p.blob_id), array::<32>(&p.key)) {
                wake |= super::media::expect_file(db, identity, from, &id, &key, p.size)?;
            }
        }
    }
    let signed: Vec<SignedFeedItem> = fresh.into_iter().map(|(_, s)| s).collect();
    wake |= pass_on(db, identity, &signed, Some(from))?;
    // public relays they know of
    super::public::take_in(db, &m.public_relays, now)?;
    // what they lack: what counts here and is in none of their lists of that feed, newest first
    let mut reply: Vec<SignedFeedItem> = Vec::new();
    for d in feeds {
        let haves: Vec<&FeedHave> = m.have.iter().filter(|h| h.feed_id == d.feed_id).collect();
        if haves.is_empty() || reply.len() >= MAX_FEED_ITEMS {
            continue;
        }
        reply.extend(missing(db, d, &haves, now, MAX_FEED_ITEMS - reply.len())?);
    }
    if !reply.is_empty() || census_back {
        if let Some(c) = db.contact(from)?.filter(|c| !c.blocked) {
            reply.reverse();
            let (census, public_relays) = if census_back { (census_wire(&census_now(db, now)?), super::public::out(db, now)?) } else { (Vec::new(), Vec::new()) };
            enqueue(db, identity, &c, &Envelope::new(Body::Feed(FeedMsg { items: reply, have: Vec::new(), census, public_relays })), false)?;
            wake = true;
        }
    }
    Ok(FeedOutcome { wake, more })
}

impl Inner {
    pub(super) fn feed_list(&self) -> Vec<FeedDescriptor> {
        self.feeds.lock().expect("feeds").clone()
    }

    /// This phone's tag in a feed (T74); empty while the app gave no device (`Node::set_device`).
    pub(super) fn device_tag(&self, feed_id: &[u8]) -> Vec<u8> {
        let device = self.device.read().expect("device");
        if device.is_empty() {
            return Vec::new();
        }
        crate::feed::device_tag(feed_id, &device)
    }

    /// The catch-up (deliver_loop, every SYNC_TICK_MS): our "have" lists to each peer reached
    /// lately that has not had them for SYNC_EVERY_MS, so a new contact gets them at once instead
    /// of up to ten minutes later (Ehsan, 25 Oct 2026: a new user saw nothing in the official chats
    /// and the count did not move). `force`: to every peer reached lately, now.
    pub(super) fn feeds_sync(&self, force: bool) {
        let feeds = self.feed_list();
        if feeds.is_empty() {
            return;
        }
        let now = now_ms();
        let queued = {
            let st = self.state.lock().expect("state");
            let identity = &st.identity;
            let me = identity.sk();
            let db = st.store.db();
            (|| -> Result<bool> {
                let mut sent = self.feed_sent.lock().expect("feed_sent");
                let peers: Vec<UserId> = feed_peers(&db, now.saturating_sub(2 * SYNC_EVERY_MS))?.into_iter().filter(|p| *p != me).collect();
                // the count sketches and the public relays: to everyone when they changed since they
                // last went out (at most every SYNC_EVERY_MS), else to each peer not given them since
                // the start or for a while
                let sketches = census_now(&db, now)?;
                let publics = super::public::out(&db, now)?;
                let mut digest = Sha256::new();
                for s in &sketches {
                    digest.update(s.epoch.to_be_bytes());
                    digest.update(s.pack());
                }
                for p in &publics {
                    digest.update(&p.sig);
                }
                let digest = digest.finalize();
                let changed = db.meta(CENSUS_SENT)?.unwrap_or_default().get(..16) != Some(&digest[..16]);
                let wide = changed && (force || now.saturating_sub(sent.wide_ms) >= SYNC_EVERY_MS);
                let due: Vec<(UserId, bool, bool)> = peers
                    .iter()
                    .map(|p| {
                        let have = force || sent.have.get(p).is_none_or(|t| now.saturating_sub(*t) >= SYNC_EVERY_MS);
                        let census = wide || sent.census.get(p).is_none_or(|t| now.saturating_sub(*t) >= CENSUS_RESEND_MS);
                        (*p, have, census)
                    })
                    .filter(|(_, h, c)| *h || *c)
                    .collect();
                if due.is_empty() {
                    return Ok(false);
                }
                let have: Vec<FeedHave> = feeds.iter().map(|d| have_of(&db, d, now)).collect::<Result<Vec<_>>>()?.into_iter().flatten().collect();
                let census = census_wire(&sketches);
                let mut any = false;
                for (peer, due_have, due_census) in due {
                    let Some(c) = db.contact(&peer)?.filter(|c| !c.blocked) else { continue };
                    let (census, public_relays) = if due_census { (census.clone(), publics.clone()) } else { (Vec::new(), Vec::new()) };
                    let have = if due_have { have.clone() } else { Vec::new() };
                    enqueue(&db, identity, &c, &Envelope::new(Body::Feed(FeedMsg { items: Vec::new(), have, census, public_relays })), false)?;
                    if due_have {
                        sent.have.insert(peer, now);
                    }
                    if due_census {
                        sent.census.insert(peer, now);
                    }
                    any = true;
                }
                if any && wide {
                    sent.wide_ms = now;
                    db.set_meta(CENSUS_SENT, &digest[..16])?;
                }
                Ok(any)
            })()
        };
        match queued {
            Ok(true) => self.waker.wake(),
            Ok(false) => {}
            Err(e) => log::debug!("node: feed sync not queued ({e})"),
        }
    }

    /// A peer sent a full page of what we lacked: our lists go back to them at the next tick.
    pub(super) fn feed_more(&self, peer: &UserId) {
        self.feed_sent.lock().expect("feed_sent").have.remove(peer);
    }

    /// Sign, keep and pass on one item of ours.
    fn feed_put(self: &Arc<Self>, feed_id: &[u8], item: Item, need: impl FnOnce(&FeedDescriptor, &FeedState, &UserId) -> bool) -> Result<Vec<u8>> {
        let feeds = self.feed_list();
        let d = find(&feeds, feed_id).ok_or(Error::NotFound("feed"))?.clone();
        let now = now_ms();
        let id = random::<ITEM_ID_LEN>().to_vec();
        {
            let mut guard = self.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let me = identity.sk();
            let (st, _) = load(&store.db(), &d, now)?;
            if !need(&d, &st, &me) {
                return Err(Error::Session("not allowed in this feed"));
            }
            // a member's link would be kept out on every phone (feed.rs has_web_link): refuse it here
            if !d.broadcast && !st.is_admin(&me) && crate::feed::item_has_web_link(&item) {
                return Err(Error::Session("no links in the official group"));
            }
            // slow mode (T67): a member's next post only once its time has come
            if matches!(item, Item::Post(_)) && !d.broadcast && !st.is_admin(&me) && st.slow_s > 0 {
                let last = st.posts.iter().filter(|p| p.author == me).map(|p| p.ts_ms).max().unwrap_or(0);
                if now.saturating_sub(last) < u64::from(st.slow_s) * 1000 {
                    return Err(Error::Session("slow mode: not yet"));
                }
            }
            // this phone (T74): banned here, or no tag to post with, its post would count nowhere
            let device = self.device_tag(feed_id);
            if matches!(item, Item::Post(_)) && !d.broadcast && !st.is_admin(&me) {
                if device.is_empty() && now >= crate::feed::DEVICE_FROM_MS {
                    return Err(Error::Session("this phone gave no device tag"));
                }
                if st.banned_devices.get(&device).is_some_and(|u| now < *u) {
                    return Err(Error::Session("this phone is banned in this feed"));
                }
            }
            // our card goes with a post once a day in each feed, so any member can open a
            // one-to-one chat with us (SPEC 18.4)
            let card_key = format!("{CARD_SENT}{}", hex::encode(feed_id));
            let last = store.db().meta(&card_key)?.and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(u64::from_be_bytes);
            let card = if matches!(item, Item::Post(_)) && last.is_none_or(|t| now.saturating_sub(t) >= CARD_EVERY_MS) { self.session_card_bytes(identity)? } else { Vec::new() };
            let fi = FeedItem { feed_id: feed_id.to_vec(), id: id.clone(), author: me.to_vec(), ts_ms: now, name: identity.display_name(), card, device, client: crate::envelope::own_client(), item: Some(item) };
            let signed = SignedFeedItem::sign(&fi, |m| identity.sign(m));
            let identity: &Identity = identity;
            store.transaction(|db| {
                keep(db, &feeds, std::slice::from_ref(&signed))?;
                pass_on(db, identity, std::slice::from_ref(&signed), None)?;
                // the item itself goes to our own relays at once, once for everyone (SPEC 18.7)
                db.feed_relay_queue(&fi.id, feed_id, &signed.to_bytes(), now)?;
                // a file of ours goes to our own relays at once, for everyone (SPEC 18.3)
                super::feedfiles::put_soon(db, &fi)?;
                if !fi.card.is_empty() {
                    db.set_meta(&card_key, &now.to_be_bytes())?;
                }
                Ok(())
            })?;
        }
        self.emit(Event::Feed { feed_id: feed_id.to_vec() });
        self.waker.wake();
        self.feed_relay_tick();
        // and to the official feeds' copy, where a phone with no contact reads it (mirror.rs)
        self.mirror_up.store(true, std::sync::atomic::Ordering::SeqCst);
        self.mirror_soon(true);
        Ok(id)
    }
}

impl Node {
    /// The feeds this app ships (T38), with any items that ship too (the owner's admin list,
    /// a welcome post). Call once at start, before the transports.
    pub fn set_feeds(&self, feeds: Vec<FeedDescriptor>, seed: &[SignedFeedItem]) -> Result<()> {
        *self.inner.feeds.lock().expect("feeds") = feeds.clone();
        // a state is built with its feed's descriptor (the owner signs the admin list)
        self.inner.feed_cache.lock().expect("feed_cache").clear();
        let mut st = self.inner.state.lock().expect("state");
        let me = st.identity.sk();
        st.store.transaction(|db| {
            keep(db, &feeds, seed)?;
            super::feedfiles::backfill(db, &feeds, &me)?;
            super::feedfiles::backfill_photos(db, &feeds)?;
            super::feedfiles::backfill_chat_photos(db)
        })
    }

    pub fn feeds(&self) -> Vec<FeedDescriptor> {
        self.inner.feed_list()
    }

    /// What a feed's items say now: admins, bans, and the posts that count, oldest first. Built
    /// again only when the items changed (`store::feed_gen`) or `STATE_KEEP_MS` passed: the app
    /// asks very often (every name on screen asks whether its author is an admin), and building
    /// reads and decodes every item, the node's lock held all the while.
    pub fn feed_state(&self, feed_id: &[u8]) -> Result<Option<FeedState>> {
        let feeds = self.inner.feed_list();
        let Some(d) = find(&feeds, feed_id) else { return Ok(None) };
        let st = self.inner.state.lock().expect("state");
        let now = now_ms();
        let gen = crate::store::feed_gen();
        if let Some((g, at, s)) = self.inner.feed_cache.lock().expect("feed_cache").get(feed_id) {
            if *g == gen && now.saturating_sub(*at) < STATE_KEEP_MS {
                return Ok(Some(s.clone()));
            }
        }
        let built = load(&st.store.db(), d, now)?.0;
        self.inner.feed_cache.lock().expect("feed_cache").insert(feed_id.to_vec(), (gen, now, built.clone()));
        Ok(Some(built))
    }

    /// Post in a feed: in the channel only admins, in the group anyone not banned.
    pub fn feed_post(&self, feed_id: &[u8], env: &Envelope) -> Result<Vec<u8>> {
        let bytes = env.to_bytes()?;
        let now = now_ms();
        self.inner.feed_put(feed_id, Item::Post(bytes), |d, st, me| st.can_post(d, me, now))
    }

    pub fn feed_send_text(&self, feed_id: &[u8], text: &str) -> Result<Vec<u8>> {
        self.feed_send_text_rich(feed_id, text, Vec::new(), &[])
    }

    /// A text post with its formatting, mentions and links (SPEC 5 `Entity`), answering post
    /// `reply_to` of the same feed (SPEC 18: the post's envelope names it in `reply_to`; empty for
    /// none, and a post not in this feed goes as a plain post). Ehsan, 26 Oct 2026: replies in
    /// the official channel and group did not reach the other phones.
    pub fn feed_send_text_rich(&self, feed_id: &[u8], text: &str, entities: Vec<crate::envelope::Entity>, reply_to: &[u8]) -> Result<Vec<u8>> {
        let mut env = Envelope::rich_text(text, entities);
        env.reply_to = self.feed_reply_id(feed_id, reply_to)?;
        self.feed_post(feed_id, &env)
    }

    /// A photo or file in a feed: sealed here, fetched by each phone from the one that passed it.
    pub fn feed_send_media(&self, feed_id: &[u8], path: &std::path::Path, info: &super::MediaInfo) -> Result<Vec<u8>> {
        self.feed_send_media_reply(feed_id, path, info, &[])
    }

    /// A photo or file in a feed, answering post `reply_to` of it (as `feed_send_text_rich`).
    pub fn feed_send_media_reply(&self, feed_id: &[u8], path: &std::path::Path, info: &super::MediaInfo, reply_to: &[u8]) -> Result<Vec<u8>> {
        let media = self.seal_media(path, info)?;
        let mut env = Envelope::new(Body::Media(media));
        env.reply_to = self.feed_reply_id(feed_id, reply_to)?;
        let id = self.feed_post(feed_id, &env)?;
        self.inner.files_tick();
        Ok(id)
    }

    /// `reply_to` if it is a post of this feed that counts here; else empty.
    fn feed_reply_id(&self, feed_id: &[u8], reply_to: &[u8]) -> Result<Vec<u8>> {
        if reply_to.len() != ITEM_ID_LEN {
            return Ok(Vec::new());
        }
        let known = self.feed_state(feed_id)?.is_some_and(|s| s.posts.iter().any(|p| p.id == reply_to));
        Ok(if known { reply_to.to_vec() } else { Vec::new() })
    }

    /// The feed's photo from an image file, with its tiny preview; `None` removes it. Admins only.
    /// Each phone fetches the file from the one that passed the item on.
    pub fn feed_set_photo(&self, feed_id: &[u8], path: Option<&std::path::Path>, thumb: &[u8]) -> Result<()> {
        let photo = match path {
            Some(p) => {
                let s = self.inner.seal_file(p)?;
                Some(crate::group::ChatPhoto { blob_id: s.blob_id.to_vec(), key: s.key.to_vec(), size: s.size, thumb: super::profile::thumb_ok(thumb) })
            }
            None => None,
        };
        self.inner.feed_put(feed_id, Item::Info(FeedInfoItem { photo }), |_, st, me| st.is_admin(me))?;
        self.inner.files_tick();
        Ok(())
    }

    /// The feed's name and description (empty: the app's own). Admins only.
    pub fn feed_set_meta(&self, feed_id: &[u8], title: &str, about: &str) -> Result<()> {
        let title = title.trim();
        let about = about.trim();
        if title.len() > MAX_TITLE || about.len() > MAX_ABOUT {
            return Err(Error::Envelope("feed name or description too long"));
        }
        self.inner.feed_put(feed_id, Item::Meta(FeedMeta { title: title.to_string(), about: about.to_string() }), |_, st, me| st.is_admin(me))?;
        Ok(())
    }

    /// Pin a post for everyone, or take its pin off (T65; Ehsan, 29 Oct 2026): admins only, on a
    /// post that counts. `FeedState::pinned` has them, the newest last.
    /// An admin sets the official group's slow mode (T67): a member posts again only `seconds`
    /// after their last post that counts; 0 ends it. Not in a channel, where only admins post.
    pub fn feed_set_slow(&self, feed_id: &[u8], seconds: u32) -> Result<()> {
        if seconds > crate::feed::MAX_SLOW_S {
            return Err(Error::Envelope("slow mode too long"));
        }
        self.inner.feed_put(feed_id, Item::Slow(crate::feed::FeedSlow { seconds }), |d, st, me| !d.broadcast && st.is_admin(me))?;
        Ok(())
    }

    pub fn feed_pin(&self, feed_id: &[u8], target: &[u8], unpin: bool) -> Result<()> {
        if target.len() != ITEM_ID_LEN {
            return Err(Error::Envelope("bad pin"));
        }
        let t = target.to_vec();
        self.inner.feed_put(feed_id, Item::Pin(crate::feed::FeedPin { target: t.clone(), unpin }), |_, st, me| st.is_admin(me) && st.posts.iter().any(|p| p.id == t))?;
        Ok(())
    }

    /// React to a post (an empty emoji takes ours back): anyone not banned, in both feeds.
    pub fn feed_react(&self, feed_id: &[u8], target: &[u8], emoji: &str) -> Result<()> {
        if target.len() != ITEM_ID_LEN || emoji.len() > MAX_EMOJI {
            return Err(Error::Envelope("bad reaction"));
        }
        let t = target.to_vec();
        let now = now_ms();
        self.inner.feed_put(feed_id, Item::React(FeedReact { target: t.clone(), emoji: emoji.to_string() }), |_, st, me| {
            !st.banned.get(me).is_some_and(|u| now < *u) && st.posts.iter().any(|p| p.id == t)
        })?;
        Ok(())
    }

    /// New text for one's own post (a text post's text, a photo's or file's caption, with its
    /// formatting), as SPEC 5 `Edit` in a chat; every phone shows the author's newest edit (T60).
    pub fn feed_edit(&self, feed_id: &[u8], target: &[u8], text: &str, entities: Vec<crate::envelope::Entity>) -> Result<()> {
        if target.len() != ITEM_ID_LEN {
            return Err(Error::Envelope("bad edit"));
        }
        let st = self.feed_state(feed_id)?.ok_or(Error::NotFound("feed"))?;
        let post = st.posts.iter().find(|p| p.id == target).ok_or(Error::NotFound("post"))?;
        let stored = Envelope::from_bytes(&post.envelope)?;
        let edit = crate::envelope::edit_for(&stored, target, text, entities).ok_or(Error::Envelope("nothing to edit in this post"))?;
        let t = target.to_vec();
        self.inner.feed_put(feed_id, Item::Edit(edit), |_, st, me| st.posts.iter().any(|p| p.id == t && p.author == *me))?;
        Ok(())
    }

    /// Take a post down: an admin's or the post's author's.
    pub fn feed_delete(&self, feed_id: &[u8], target: &[u8]) -> Result<()> {
        let t = target.to_vec();
        self.inner.feed_put(feed_id, Item::Delete(FeedDelete { target: target.to_vec() }), |_, st, me| st.is_admin(me) || st.posts.iter().any(|p| p.id == t && p.author == *me))?;
        Ok(())
    }

    /// Ban someone from a feed until a time (0 lifts it). Admins only. The phones they posted
    /// from go with it (T74): a new account on one of them posts no more either.
    pub fn feed_ban(&self, feed_id: &[u8], sk: &UserId, until_ms: u64) -> Result<()> {
        let devices: Vec<Vec<u8>> = self
            .feed_state(feed_id)?
            .and_then(|st| st.devices_of.get(sk).cloned())
            .unwrap_or_default()
            .into_iter()
            .take(crate::feed::MAX_BAN_DEVICES)
            .collect();
        self.inner.feed_put(feed_id, Item::Ban(FeedBan { sk: sk.to_vec(), until_ms, devices }), |_, st, me| st.is_admin(me))?;
        Ok(())
    }

    /// Until when this phone may not post in a feed (T74): its own ban, or its phone's; 0 for none.
    pub fn feed_banned_until(&self, feed_id: &[u8]) -> Result<u64> {
        let Some(st) = self.feed_state(feed_id)? else { return Ok(0) };
        let me = self.me().sk;
        let now = now_ms();
        let own = st.banned.get(&me).copied().unwrap_or(0);
        let tag = self.inner.device_tag(feed_id);
        let phone = if tag.is_empty() { 0 } else { st.banned_devices.get(&tag).copied().unwrap_or(0) };
        let until = own.max(phone);
        Ok(if until > now { until } else { 0 })
    }

    /// Keeps the owner key of the official feeds on this phone, from its secret half: the owner's
    /// main phone then names the admins in the app (Ehsan, 27 Oct 2026), not an offline tool and a
    /// new release. Refused unless its public half owns a feed this app ships.
    pub fn feed_owner_import(&self, secret: &[u8]) -> Result<()> {
        let secret: [u8; 32] = secret.try_into().map_err(|_| Error::Envelope("owner key"))?;
        let owner: UserId = *vodozemac::Ed25519SecretKey::from_slice(&secret).public_key().as_bytes();
        if !self.inner.feed_list().iter().any(|d| d.owner == owner) {
            return Err(Error::Session("not the owner key of an official feed"));
        }
        let st = self.inner.state.lock().expect("state");
        st.store.db().set_meta(OWNER_SECRET, &secret)
    }

    /// Whether this phone holds the owner key of `feed_id`.
    pub fn feed_owner_held(&self, feed_id: &[u8]) -> bool {
        self.owner_secret(feed_id).is_some()
    }

    /// Names the admins of an official feed, the first shown as its creator: a new list, signed
    /// here with the owner key, one version above the one in force, so it replaces that one on
    /// every phone it reaches. Only on the phone that holds the owner key.
    pub fn feed_set_admins(&self, feed_id: &[u8], admins: &[UserId]) -> Result<()> {
        let secret = self.owner_secret(feed_id).ok_or(Error::Session("no owner key on this phone"))?;
        let mut list: Vec<UserId> = Vec::new();
        for a in admins {
            if !list.contains(a) {
                list.push(*a);
            }
        }
        if list.len() > crate::feed::MAX_ADMINS {
            return Err(Error::Envelope("too many admins"));
        }
        let feeds = self.inner.feed_list();
        let d = find(&feeds, feed_id).ok_or(Error::NotFound("feed"))?.clone();
        {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let now = now_ms();
            let (st, _) = load(&store.db(), &d, now)?;
            let signed = crate::feed::sign_admins(&secret, feed_id, st.admins_version + 1, &list, now)?;
            let identity: &Identity = identity;
            store.transaction(|db| {
                keep(db, &feeds, std::slice::from_ref(&signed))?;
                pass_on(db, identity, std::slice::from_ref(&signed), None)?;
                Ok(())
            })?;
        }
        self.inner.emit(Event::Feed { feed_id: feed_id.to_vec() });
        self.inner.waker.wake();
        self.inner.mirror_soon(true);
        Ok(())
    }

    /// The owner key kept here, if it owns `feed_id`.
    fn owner_secret(&self, feed_id: &[u8]) -> Option<[u8; 32]> {
        let owner = find(&self.inner.feed_list(), feed_id)?.owner;
        let st = self.inner.state.lock().expect("state");
        let secret: [u8; 32] = st.store.db().meta(OWNER_SECRET).ok()??.try_into().ok()?;
        (*vodozemac::Ed25519SecretKey::from_slice(&secret).public_key().as_bytes() == owner).then_some(secret)
    }

    /// Compare with the peers reached lately now, without waiting for the timer.
    pub fn feed_sync_now(&self) {
        self.inner.feeds_sync(true);
        self.inner.mirror_soon(false);
    }

    /// How many are in the official feeds: everyone who used Gereh in the last two to four weeks,
    /// estimated phone to phone (`census.rs`); within a few percent once the sketches spread.
    pub fn feed_member_count(&self) -> Result<u64> {
        let st = self.inner.state.lock().expect("state");
        census_count(&st.store.db(), now_ms())
    }
}
