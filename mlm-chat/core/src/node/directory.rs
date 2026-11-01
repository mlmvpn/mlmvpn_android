//! The public list of channels and groups (q6, T70; Ehsan, 30 Oct 2026: «همه»; the most relevant
//! to the words first, then the bigger, then the older; no badge saying which). Each chat's owner
//! phone puts the chat's card on our relays (`transport::nostr::dir`) and renews it every
//! [`PUBLISH_EVERY_MS`]; the owner can take a chat out. A phone reads every card once and then only
//! those signed since, keeps them, and searches them itself.

use super::Node;
use crate::group::{Op, SetInfo};
use crate::transport::nostr::dir::{self, Card};
use crate::util::{now_ms, random};
use crate::{Error, Result};
use std::collections::HashMap;
use std::time::Duration;

/// How often an owner's phone renews its chats' cards (they live `dir::EXPIRE_S`).
pub const PUBLISH_EVERY_MS: u64 = 6 * 3600 * 1000;
/// A search reads the relays again when the cards are older than this.
pub const REFRESH_MS: u64 = 3600 * 1000;
/// Cards signed this long before the last look are asked for again (a clock off a little).
const SINCE_MARGIN_S: u64 = 3600;
const SECRET: &str = "dir_secret";
const FETCHED: &str = "dir_fetched_s";
const PUBLISHED: &str = "dir_published_ms";
const TIMEOUT: Duration = Duration::from_secs(15);

/// One chat a search found.
#[derive(Clone, Debug, PartialEq)]
pub struct DirResult {
    pub group_id: Vec<u8>,
    pub name: String,
    pub about: String,
    pub broadcast: bool,
    pub members: u32,
    pub link: String,
    pub created_ms: u64,
    /// We are in it already.
    pub member: bool,
    pub score: f64,
    /// The hash of its list photo (`dir::photo_hash`); empty for none. [`Node::dir_photo`] gives it
    /// once [`Node::dir_fetch_photos`] read it.
    pub photo: String,
}

/// Letters as a search compares them: Arabic forms as Persian, no joiners or marks, lower case.
fn fold(s: &str) -> String {
    s.chars()
        .filter_map(|c| match c {
            'ي' | 'ى' => Some('ی'),
            'ك' => Some('ک'),
            'ة' => Some('ه'),
            'أ' | 'إ' | 'آ' => Some('ا'),
            'ؤ' => Some('و'),
            '\u{200c}' | '\u{200d}' | '\u{200e}' | '\u{200f}' | '\u{064b}'..='\u{065f}' | '\u{0670}' => None,
            '_' | '-' | '.' | '@' => Some(' '),
            c => Some(c),
        })
        .flat_map(char::to_lowercase)
        .collect()
}

/// How well a card answers `query` (0: not at all), then how big and how old it is. The words
/// decide first; a channel's size and age only order the ones that match about as well.
pub(crate) fn score(card: &Card, query: &str, now_ms: u64) -> f64 {
    let q = fold(query.trim());
    if q.is_empty() {
        return 0.0;
    }
    let name = fold(&card.n);
    let about = fold(&card.a);
    let mut text = 0.0;
    if name == q {
        text += 100.0;
    } else if name.starts_with(&q) {
        text += 70.0;
    } else if name.contains(&q) {
        text += 50.0;
    }
    let words: Vec<&str> = q.split_whitespace().collect();
    let mut every = !words.is_empty();
    for w in &words {
        if name.split_whitespace().any(|n| n.starts_with(w)) {
            text += 15.0;
        } else if name.contains(w) {
            text += 8.0;
        } else if about.contains(w) {
            text += 4.0;
        } else {
            every = false;
        }
    }
    if text == 0.0 || (words.len() > 1 && !every && !name.contains(&q)) {
        return 0.0;
    }
    let size = 12.0 * ((card.m as f64) + 1.0).log10();
    let days = now_ms.saturating_sub(card.c) as f64 / 86_400_000.0;
    let age = if card.c > 0 { 3.0 * (days + 1.0).log2().min(10.0) } else { 0.0 };
    text + size + age
}

impl Node {
    /// This phone's own secret for its cards' keys, made once.
    fn dir_secret(&self) -> Result<[u8; 32]> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        if let Some(v) = db.meta(SECRET)? {
            if let Ok(s) = <[u8; 32]>::try_from(v.as_slice()) {
                return Ok(s);
            }
        }
        let s = random::<32>();
        db.set_meta(SECRET, &s)?;
        Ok(s)
    }

    /// Whether a chat of ours is in the public list: every one is, until its owner takes it out.
    pub fn dir_listed(&self, gid: &[u8]) -> Result<bool> {
        Ok(self.inner.state.lock().expect("state").store.db().dir_listed(gid)?.unwrap_or(true))
    }

    /// The owner puts a chat in the public list or takes it out; its card goes (or is withdrawn)
    /// at the next renewal, which this brings forward. The choice goes to every member too, in the
    /// chat's info (SetInfo `listed`), so its admins' phones follow it.
    pub fn dir_set_listed(&self, gid: &[u8], listed: bool) -> Result<()> {
        let me = self.me().sk;
        let st = self.group_state(gid)?.ok_or(Error::NotFound("group"))?;
        if st.owner != me {
            return Err(Error::Session("only the owner lists a chat"));
        }
        {
            let s = self.inner.state.lock().expect("state");
            let db = s.store.db();
            db.dir_set_listed(gid, listed)?;
            db.set_meta(PUBLISHED, &0u64.to_be_bytes())?;
        }
        if st.listed != listed {
            self.group_op(gid, Op::SetInfo(SetInfo { name: st.name, about: st.about, listed: if listed { 1 } else { 2 }, ..Default::default() }))?;
        }
        Ok(())
    }

    /// Whether this phone's chats' cards are due again (the deliver loop asks; [`Node::dir_publish`] does it).
    pub fn dir_publish_due(&self) -> bool {
        let st = self.inner.state.lock().expect("state");
        let last = st.store.db().meta(PUBLISHED).ok().flatten().and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(u64::from_be_bytes).unwrap_or(0);
        now_ms().saturating_sub(last) >= PUBLISH_EVERY_MS
    }

    /// The cards of every chat this phone owns, renewed (a chat taken out: withdrawn), to our
    /// relays. Only the owner's phone puts a chat up (Ehsan, 31 Oct 2026: the owner always has the
    /// last word, and with no word from the owner nothing goes): no admin's or member's phone does,
    /// so the owner's «out» takes the only card down. Blocks on the network: off any thread that
    /// matters. How many relays took them.
    pub fn dir_publish(&self) -> Result<usize> {
        let me = self.me().sk;
        let secret = self.dir_secret()?;
        let now = now_ms();
        let now_s = now / 1000;
        let mut events = Vec::new();
        for st in self.groups()? {
            if st.owner != me || st.dissolved {
                continue;
            }
            let gid = st.group_id.clone();
            let key = dir::key_for(&secret, &gid);
            if !self.dir_listed(&gid)? {
                // out of the list: its card and its photo, both
                events.push(dir::withdrawn(&hex::encode(&gid), &key, now_s));
                events.push(dir::photo_event(&hex::encode(&gid), &[], &key, now_s));
                continue;
            }
            let photo = self.inner.state.lock().expect("state").store.db().dir_own_photo(&gid)?;
            // not in the match itself: its lock would live through the arms, and making the link locks
            let kept = self.inner.state.lock().expect("state").store.db().dir_link(&gid)?;
            let link = match kept {
                Some(l) => l,
                None => {
                    // a link of its own, for good: a channel takes anyone, a group asks its admins
                    let l = self.group_invite_create(&gid, !st.broadcast, 0, 0)?;
                    self.inner.state.lock().expect("state").store.db().dir_set_link(&gid, &l)?;
                    l
                }
            };
            let created = self.group_events(&gid)?.iter().filter(|e| e.1 == "create").map(|e| e.3).min().unwrap_or(0);
            let p = photo.as_ref().map(|(h, _)| h.clone()).unwrap_or_default();
            let card = Card { v: 1, g: hex::encode(&gid), n: st.name.clone(), a: st.about.clone(), b: st.broadcast, m: st.members.len() as u32, l: link, c: created, p };
            events.push(dir::event_for(&card, &key, now_s));
            if let Some((_, jpeg)) = &photo {
                events.push(dir::photo_event(&card.g, jpeg, &key, now_s));
            }
        }
        let mut took = 0;
        if !events.is_empty() {
            for url in self.inner.file_relays() {
                if dir::publish(&url, &events, TIMEOUT) == events.len() {
                    took += 1;
                }
            }
        }
        if took > 0 || events.is_empty() {
            self.inner.state.lock().expect("state").store.db().set_meta(PUBLISHED, &now.to_be_bytes())?;
        }
        log::info!("directory: {} card(s) to {} relay(s)", events.len(), took);
        Ok(took)
    }

    /// The small photo an owned chat shows in the list (a JPEG of at most `dir::MAX_PHOTO`; None: no
    /// photo). The app makes it from the chat's photo; it goes with the next cards, which this
    /// brings forward when it changed. Whether it changed.
    pub fn dir_set_photo(&self, gid: &[u8], jpeg: Option<&[u8]>) -> Result<bool> {
        if jpeg.is_some_and(|j| j.is_empty() || j.len() > dir::MAX_PHOTO) {
            return Err(Error::Session("a list photo is a small JPEG"));
        }
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let had = db.dir_own_photo(gid)?.map(|(h, _)| h);
        let now = jpeg.map(dir::photo_hash);
        if had == now {
            return Ok(false);
        }
        match (jpeg, &now) {
            (Some(j), Some(h)) => db.dir_set_own_photo(gid, Some((h.as_str(), j)))?,
            _ => db.dir_set_own_photo(gid, None)?,
        }
        db.set_meta(PUBLISHED, &0u64.to_be_bytes())?;
        Ok(true)
    }

    /// The photo of a listed chat its newest card names, if read already.
    pub fn dir_photo(&self, gid: &[u8]) -> Result<Option<Vec<u8>>> {
        let Some((pubkey, card)) = self.dir_best(gid)? else { return Ok(None) };
        if card.p.is_empty() {
            return Ok(None);
        }
        let kept = self.inner.state.lock().expect("state").store.db().dir_photo(gid, &pubkey)?;
        Ok(kept.and_then(|(h, j)| (h == card.p).then_some(j)))
    }

    /// Reads from our relays the photos of these chats (a search's results) that their newest
    /// card names and that are not here yet. Blocks on the network. How many came.
    pub fn dir_fetch_photos(&self, gids: &[Vec<u8>]) -> Result<usize> {
        let mut wanted = Vec::new();
        let mut hashes = HashMap::new();
        for g in gids {
            let Some((pubkey, card)) = self.dir_best(g)? else { continue };
            if card.p.is_empty() {
                continue;
            }
            let kept = self.inner.state.lock().expect("state").store.db().dir_photo(g, &pubkey)?;
            if kept.is_some_and(|(h, _)| h == card.p) {
                continue;
            }
            hashes.insert((card.g.clone(), pubkey.clone()), card.p.clone());
            wanted.push((card.g, pubkey));
        }
        if wanted.is_empty() {
            return Ok(0);
        }
        let mut got = 0;
        for url in self.inner.file_relays() {
            let Some(photos) = dir::fetch_photos(&url, &wanted, TIMEOUT) else { continue };
            let st = self.inner.state.lock().expect("state");
            for (g, pubkey, jpeg) in photos {
                // only the very photo the card names
                if hashes.get(&(g.clone(), pubkey.clone())) == Some(&dir::photo_hash(&jpeg)) {
                    st.store.db().dir_photo_put(&hex::decode(&g).unwrap_or_default(), &pubkey, &dir::photo_hash(&jpeg), &jpeg)?;
                    got += 1;
                }
            }
            break;
        }
        Ok(got)
    }

    /// A chat's newest card from any signer: (signer, card).
    fn dir_best(&self, gid: &[u8]) -> Result<Option<(String, Card)>> {
        let rows = self.inner.state.lock().expect("state").store.db().dir_cards()?;
        let mut best: Option<(u64, String, Card)> = None;
        for (g, pubkey, json, created_s) in rows {
            if g != gid {
                continue;
            }
            let Ok(card) = serde_json::from_str::<Card>(&json) else { continue };
            if best.as_ref().is_none_or(|(c, _, _)| *c < created_s) {
                best = Some((created_s, pubkey, card));
            }
        }
        Ok(best.map(|(_, p, c)| (p, c)))
    }

    /// Whether the cards kept here are older than [`REFRESH_MS`].
    pub fn dir_stale(&self) -> bool {
        let st = self.inner.state.lock().expect("state");
        let fetched = st.store.db().meta(FETCHED).ok().flatten().and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(u64::from_be_bytes).unwrap_or(0);
        (now_ms() / 1000).saturating_sub(fetched) * 1000 >= REFRESH_MS
    }

    /// Reads the cards from our relays: all the first time, then those signed since the last look.
    /// Blocks on the network. How many cards are kept afterwards.
    pub fn dir_refresh(&self) -> Result<usize> {
        let since = {
            let st = self.inner.state.lock().expect("state");
            let f = st.store.db().meta(FETCHED)?.and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(u64::from_be_bytes).unwrap_or(0);
            if f == 0 { 0 } else { f.saturating_sub(SINCE_MARGIN_S) }
        };
        let now_s = now_ms() / 1000;
        let mut any = false;
        let mut reads = Vec::new();
        for url in self.inner.file_relays() {
            if let Some(r) = dir::fetch(&url, since, TIMEOUT) {
                any = true;
                reads.extend(r);
            }
        }
        if !any {
            return Err(Error::Relay("no relay of the list answered"));
        }
        let mut st = self.inner.state.lock().expect("state");
        st.store.transaction(|db| {
            for r in &reads {
                let g = hex::decode(&r.gid).unwrap_or_default();
                match &r.card {
                    Some(c) => db.dir_card_put(&g, &r.pubkey, &serde_json::to_string(c).unwrap_or_default(), r.created_s)?,
                    None => db.dir_card_drop(&g, &r.pubkey)?,
                }
            }
            db.dir_cards_drop_older(now_s.saturating_sub(dir::EXPIRE_S))?;
            db.set_meta(FETCHED, &now_s.to_be_bytes())
        })?;
        Ok(st.store.db().dir_cards()?.len())
    }

    /// The chats whose cards answer `query`, best first: the words, then size, then age.
    pub fn dir_search(&self, query: &str, limit: usize) -> Result<Vec<DirResult>> {
        let now = now_ms();
        let rows = self.inner.state.lock().expect("state").store.db().dir_cards()?;
        // one card a chat: the newest any signer made
        let mut best: HashMap<Vec<u8>, (u64, Card)> = HashMap::new();
        for (g, _, json, created_s) in rows {
            let Ok(card) = serde_json::from_str::<Card>(&json) else { continue };
            if best.get(&g).is_none_or(|(c, _)| *c < created_s) {
                best.insert(g, (created_s, card));
            }
        }
        let mine: std::collections::HashSet<Vec<u8>> = self.groups()?.into_iter().map(|s| s.group_id).collect();
        let mut out: Vec<DirResult> = best
            .into_iter()
            .filter_map(|(g, (_, card))| {
                let s = score(&card, query, now);
                (s > 0.0).then(|| DirResult {
                    member: mine.contains(&g),
                    group_id: g,
                    name: card.n,
                    about: card.a,
                    broadcast: card.b,
                    members: card.m,
                    link: card.l,
                    created_ms: card.c,
                    score: s,
                    photo: card.p,
                })
            })
            .collect();
        out.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        out.truncate(limit);
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn card(n: &str, a: &str, m: u32, days_old: u64) -> Card {
        let now = 1_761_800_000_000u64;
        Card { v: 1, g: "ab".repeat(16), n: n.into(), a: a.into(), b: true, m, l: "mlmchat://g/A".into(), c: now - days_old * 86_400_000, p: String::new() }
    }

    #[test]
    fn the_words_come_first_then_size_then_age() {
        let now = 1_761_800_000_000u64;
        // a name that is the words beats a big one that only mentions them
        assert!(score(&card("کانفیگ رایگان", "", 10, 1), "کانفیگ رایگان", now) > score(&card("اخبار", "کانفیگ رایگان هر روز", 50_000, 400), "کانفیگ رایگان", now));
        // the same words: the bigger first
        assert!(score(&card("کانفیگ vless", "", 20_000, 30), "کانفیگ", now) > score(&card("کانفیگ v2ray", "", 300, 30), "کانفیگ", now));
        // the same words and size: the older first
        assert!(score(&card("کانفیگ ها", "", 1_000, 300), "کانفیگ", now) > score(&card("کانفیگ ما", "", 1_000, 2), "کانفیگ", now));
        // Arabic letters, joiners and case find the Persian name
        assert!(score(&card("کانفیگ‌های ایرانسل", "", 1, 1), "كانفيگهاي", now) > 0.0);
        assert!(score(&card("MLM VPN News", "", 1, 1), "mlm vpn", now) > 0.0);
        // words that are not there find nothing
        assert_eq!(score(&card("اخبار", "روزانه", 9_999, 9), "کانفیگ", now), 0.0);
        assert_eq!(score(&card("اخبار ورزش", "", 9_999, 9), "اخبار سیاسی", now), 0.0);
    }
}
