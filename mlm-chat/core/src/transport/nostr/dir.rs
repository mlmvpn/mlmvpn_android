//! The public list of channels and groups (q6, T70; Ehsan, 30 Oct 2026: users find channels and
//! groups by searching). Gereh has no server to keep such a list, so each listed channel's or
//! group's owner phone puts its card on our relays (`PINNED`) as an event of Gereh's own
//! parameterized replaceable kind (NIP-33): one card a chat, `d` = the chat's id, signed by a key
//! that phone keeps for that chat, so a newer card takes the older one's place. A card is kept for
//! good (Ehsan, 31 Oct 2026: «برای همیشه»), renewed every six hours while its owner's phone runs,
//! and goes only when the owner takes the chat out. Any phone reads them all once and then only
//! those changed since, and searches them itself (`node::directory`).
//!
//! What a card says is public on purpose: name, description, whether a channel, how many members,
//! when made, and an invite link (no approval for a channel, approval for a group). The chat's
//! photo, small, is an event of its own (`PHOTO_KIND`, same key and `d`), named in the card by its
//! hash, and read only for the chats a search shows (Ehsan: «مثل خود تلگرام عکس»).
use super::event::{self, Event};
use super::files;
use crate::util::random;
use base64::Engine;
use k256::schnorr::SigningKey;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::time::{Duration, Instant};

/// Gereh's kind for a card (parameterized replaceable, 30000-39999).
pub const KIND: u64 = 30090;
/// The tag every card carries, so a phone asks for exactly these.
pub const TAG: &str = "gereh-dir/1";
/// Gereh's kind for a chat's small photo (parameterized replaceable, `d` = the chat's id).
pub const PHOTO_KIND: u64 = 30091;
/// A card or photo is kept this long (for good: ten years, as the official chats' photos, T66).
pub const EXPIRE_S: u64 = 3650 * 86_400;
/// The largest card a phone takes.
pub const MAX_CARD: usize = 4096;
/// The largest list photo, a JPEG (a 160-pixel one is about 10 KiB).
pub const MAX_PHOTO: usize = 32 * 1024;
/// Cards a relay answers in one go (strfry's `maxFilterLimit`): more come page by page.
const PAGE: usize = 500;
/// Pages read at most in one look: 20,000 cards.
const MAX_PAGES: usize = 40;
const MAX_MESSAGE: usize = 64 * 1024;

/// One channel's or group's card.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Card {
    /// The format, 1.
    pub v: u32,
    /// The chat's id, hex.
    pub g: String,
    /// Name.
    pub n: String,
    /// Description.
    #[serde(default)]
    pub a: String,
    /// A channel (only admins post) rather than a group.
    #[serde(default)]
    pub b: bool,
    /// Members (subscribers), as its owner's phone counts them.
    #[serde(default)]
    pub m: u32,
    /// An invite link (`mlmchat://g/…`) for this very chat.
    pub l: String,
    /// When the chat was made (unix ms).
    #[serde(default)]
    pub c: u64,
    /// The hash of its photo (`photo_hash`), the event `PHOTO_KIND` carries; empty for none.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub p: String,
}

impl Card {
    fn fits(&self) -> bool {
        self.v == 1
            && self.g.len() == 32
            && hex::decode(&self.g).is_ok()
            && !self.n.trim().is_empty()
            && self.n.len() <= 256
            && self.a.len() <= 1024
            && self.l.starts_with(crate::group::INVITE_PREFIX)
            && self.l.len() <= 3000
            && (self.p.is_empty() || (self.p.len() == 16 && hex::decode(&self.p).is_ok()))
    }
}

/// A list photo's name in its card: the first 8 bytes of its SHA-256, hex.
pub fn photo_hash(jpeg: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    hex::encode(&Sha256::digest(jpeg)[..8])
}

/// A card as read from a relay: the chat, who signed it and when, and the card (None: its owner
/// took the chat out of the list).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Read {
    pub gid: String,
    pub pubkey: String,
    pub created_s: u64,
    pub card: Option<Card>,
}

/// The key this phone signs a chat's card with: one per chat, from this phone's own `secret`.
pub fn key_for(secret: &[u8; 32], gid: &[u8]) -> SigningKey {
    use sha2::{Digest, Sha256};
    let mut n = 0u32;
    loop {
        let mut d = Sha256::new();
        d.update(b"mlmchat/dir-key/1");
        d.update(secret);
        d.update(gid);
        d.update(n.to_be_bytes());
        if let Ok(k) = SigningKey::from_bytes(&d.finalize()) {
            return k;
        }
        n += 1;
    }
}

/// The signer a key puts on its events (hex, x-only).
pub fn key_pubkey(key: &SigningKey) -> String {
    hex::encode(key.verifying_key().to_bytes())
}

/// The event of one card.
pub fn event_for(card: &Card, key: &SigningKey, now_s: u64) -> Event {
    let tags = json!([["d", card.g], ["t", TAG], ["expiration", (now_s + EXPIRE_S).to_string()]]);
    event::signed_with(key, KIND, tags, serde_json::to_string(card).expect("a card serializes"), now_s)
}

/// The event that takes a chat out of the list: empty, in its card's place.
pub fn withdrawn(gid_hex: &str, key: &SigningKey, now_s: u64) -> Event {
    let tags = json!([["d", gid_hex], ["t", TAG], ["expiration", (now_s + EXPIRE_S).to_string()]]);
    event::signed_with(key, KIND, tags, String::new(), now_s)
}

/// The event of a chat's list photo; `jpeg` empty takes it away.
pub fn photo_event(gid_hex: &str, jpeg: &[u8], key: &SigningKey, now_s: u64) -> Event {
    let tags = json!([["d", gid_hex], ["t", TAG], ["expiration", (now_s + EXPIRE_S).to_string()]]);
    event::signed_with(key, PHOTO_KIND, tags, base64::engine::general_purpose::STANDARD.encode(jpeg), now_s)
}

/// A list photo from a relay: (chat id hex, signer, JPEG), if the event is one that checks.
pub fn open_photo(v: &Value) -> Option<(String, String, Vec<u8>)> {
    if v.get("kind")?.as_u64()? != PHOTO_KIND {
        return None;
    }
    let (_, _, tags, content) = event::checked(v)?;
    let tag = |name: &str| tags.as_array()?.iter().find_map(|t| (t.get(0)?.as_str()? == name).then(|| t.get(1)?.as_str().map(str::to_string)).flatten());
    if tag("t").as_deref() != Some(TAG) {
        return None;
    }
    let gid = tag("d")?;
    if gid.len() != 32 || hex::decode(&gid).is_err() {
        return None;
    }
    let jpeg = base64::engine::general_purpose::STANDARD.decode(content.as_bytes()).ok()?;
    if jpeg.len() > MAX_PHOTO {
        return None;
    }
    Some((gid, v.get("pubkey")?.as_str()?.to_string(), jpeg))
}

/// The photos of these chats, each from the signer of its card, from `url`: (chat, signer, JPEG).
/// None when the relay could not be reached.
pub fn fetch_photos(url: &str, wanted: &[(String, String)], timeout: Duration) -> Option<Vec<(String, String, Vec<u8>)>> {
    if wanted.is_empty() {
        return Some(Vec::new());
    }
    let mut ws = files::connect(url, timeout)?;
    let sub = hex::encode(random::<8>());
    let gids: Vec<&String> = wanted.iter().map(|w| &w.0).collect();
    let authors: Vec<&String> = wanted.iter().map(|w| &w.1).collect();
    let filter = json!({ "kinds": [PHOTO_KIND], "#d": gids, "authors": authors, "limit": wanted.len() * 2 });
    if ws.send_text(&json!(["REQ", sub, filter]).to_string()).is_err() {
        return None;
    }
    let end = Instant::now() + timeout;
    let mut out = Vec::new();
    while Instant::now() < end {
        let Ok(m) = ws.recv_text(MAX_PHOTO * 2 + 4096) else { return None };
        let Ok(Value::Array(a)) = serde_json::from_str::<Value>(&m) else { continue };
        match a.first().and_then(Value::as_str) {
            Some("EVENT") if a.get(1).and_then(Value::as_str) == Some(sub.as_str()) => {
                if let Some(ph) = a.get(2).and_then(open_photo) {
                    if wanted.iter().any(|w| w.0 == ph.0 && w.1 == ph.1) {
                        out.push(ph);
                    }
                }
            }
            Some("EOSE") if a.get(1).and_then(Value::as_str) == Some(sub.as_str()) => break,
            Some("CLOSED") => return None,
            _ => {}
        }
    }
    let _ = ws.send_text(&json!(["CLOSE", sub]).to_string());
    ws.close();
    Some(out)
}

/// The card an event from a relay carries, if it is one: our kind and tag, an id and signature
/// that check, a card that fits and names the chat its `d` tag names; or a withdrawal.
pub fn open(v: &Value) -> Option<Read> {
    if v.get("kind")?.as_u64()? != KIND {
        return None;
    }
    let (_, _, tags, content) = event::checked(v)?;
    if content.len() > MAX_CARD {
        return None;
    }
    let tag = |name: &str| tags.as_array()?.iter().find_map(|t| (t.get(0)?.as_str()? == name).then(|| t.get(1)?.as_str().map(str::to_string)).flatten());
    if tag("t").as_deref() != Some(TAG) {
        return None;
    }
    let gid = tag("d")?;
    if gid.len() != 32 || hex::decode(&gid).is_err() {
        return None;
    }
    let pubkey = v.get("pubkey")?.as_str()?.to_string();
    let created_s = v.get("created_at")?.as_u64()?;
    if content.is_empty() {
        return Some(Read { gid, pubkey, created_s, card: None });
    }
    let card: Card = serde_json::from_str(content).ok()?;
    if !card.fits() || card.g != gid {
        return None;
    }
    Some(Read { gid, pubkey, created_s, card: Some(card) })
}

/// Write cards to `url`; how many it took.
pub fn publish(url: &str, events: &[Event], timeout: Duration) -> usize {
    let Some(mut w) = files::Writer::open(url, timeout) else { return 0 };
    let n = w.write(events);
    w.close();
    n
}

/// Every card `url` keeps made since `since_s` (0: all), page by page, the newest of each signer
/// and chat. None when the relay could not be reached or stopped answering.
pub fn fetch(url: &str, since_s: u64, timeout: Duration) -> Option<Vec<Read>> {
    let mut ws = files::connect(url, timeout)?;
    let mut newest: HashMap<(String, String), Read> = HashMap::new();
    let mut until: Option<u64> = None;
    for _ in 0..MAX_PAGES {
        let sub = hex::encode(random::<8>());
        let mut filter = json!({ "kinds": [KIND], "#t": [TAG], "limit": PAGE });
        if since_s > 0 {
            filter["since"] = json!(since_s);
        }
        if let Some(u) = until {
            filter["until"] = json!(u);
        }
        if ws.send_text(&json!(["REQ", sub, filter]).to_string()).is_err() {
            return None;
        }
        let end = Instant::now() + timeout;
        let mut got = 0usize;
        let mut oldest = u64::MAX;
        loop {
            if Instant::now() >= end {
                return None;
            }
            let Ok(m) = ws.recv_text(MAX_MESSAGE) else { return None };
            let Ok(Value::Array(a)) = serde_json::from_str::<Value>(&m) else { continue };
            match a.first().and_then(Value::as_str) {
                Some("EVENT") if a.get(1).and_then(Value::as_str) == Some(sub.as_str()) => {
                    got += 1;
                    if let Some(r) = a.get(2).and_then(open) {
                        oldest = oldest.min(r.created_s);
                        let k = (r.pubkey.clone(), r.gid.clone());
                        if newest.get(&k).is_none_or(|o| o.created_s < r.created_s) {
                            newest.insert(k, r);
                        }
                    }
                }
                Some("EOSE") if a.get(1).and_then(Value::as_str) == Some(sub.as_str()) => break,
                Some("CLOSED") => return None,
                _ => {}
            }
        }
        let _ = ws.send_text(&json!(["CLOSE", sub]).to_string());
        if got < PAGE || oldest == u64::MAX || oldest == 0 {
            break;
        }
        until = Some(oldest - 1);
    }
    ws.close();
    Some(newest.into_values().collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn card() -> Card {
        Card { v: 1, g: "ab".repeat(16), n: "kanal-e azmoon".into(), a: "dar bare-ye".into(), b: true, m: 12, l: format!("{}AAAA", crate::group::INVITE_PREFIX), c: 1_700_000_000_000, p: String::new() }
    }

    #[test]
    fn a_photo_goes_with_its_card() {
        let key = key_for(&[3; 32], &[0xab; 16]);
        let jpeg = vec![0xffu8, 0xd8, 0xff, 0xe0, 1, 2, 3];
        let c = Card { p: photo_hash(&jpeg), ..card() };
        assert_eq!(open(&event_for(&c, &key, 1).json).unwrap().card.unwrap().p, photo_hash(&jpeg));
        let e = photo_event(&"ab".repeat(16), &jpeg, &key, 1);
        assert_eq!(e.json["kind"], PHOTO_KIND);
        let (g, pk, back) = open_photo(&e.json).expect("a photo");
        assert_eq!((g, pk == key_pub(&key), back), ("ab".repeat(16), true, jpeg.clone()));
        // too big a photo, or a card with a hash that is not one, is not taken
        assert!(open_photo(&photo_event(&"ab".repeat(16), &vec![0; MAX_PHOTO + 1], &key, 1).json).is_none());
        assert!(open(&event_for(&Card { p: "xyz".into(), ..card() }, &key, 1).json).is_none());
    }

    fn key_pub(key: &SigningKey) -> String {
        hex::encode(key.verifying_key().to_bytes())
    }

    #[test]
    fn a_card_goes_out_and_comes_back() {
        let key = key_for(&[3; 32], &[0xab; 16]);
        let e = event_for(&card(), &key, 1_761_800_000);
        let r = open(&e.json).expect("a card");
        assert_eq!(r.card, Some(card()));
        // its owner takes it out: an empty event in its place
        let w = open(&withdrawn(&"ab".repeat(16), &key, 1_761_800_100).json).expect("a withdrawal");
        assert_eq!((w.gid.as_str(), w.card.is_none(), w.pubkey == r.pubkey), ("abababababababababababababababab", true, true));
        assert_eq!(e.json["kind"], KIND);
        assert_eq!(e.json["tags"][0][1], "ab".repeat(16));
        assert_eq!(e.json["tags"][2][1], (1_761_800_000 + EXPIRE_S).to_string());
        // the same phone signs the same chat's cards with the same key, another chat's with another
        assert_eq!(key_for(&[3; 32], &[0xab; 16]).verifying_key(), key.verifying_key());
        assert_ne!(key_for(&[3; 32], &[0xac; 16]).verifying_key(), key.verifying_key());
        assert_ne!(key_for(&[4; 32], &[0xab; 16]).verifying_key(), key.verifying_key());
    }

    #[test]
    fn a_card_that_lies_or_breaks_is_not_taken() {
        let key = key_for(&[3; 32], &[0xab; 16]);
        // its d tag names another chat than the card
        let mut other = card();
        other.g = "cd".repeat(16);
        let e = event::signed_with(&key, KIND, json!([["d", "ab".repeat(16)], ["t", TAG]]), serde_json::to_string(&other).unwrap(), 1);
        assert!(open(&e.json).is_none());
        // no link of ours
        let mut bad = card();
        bad.l = "https://t.me/x".into();
        assert!(open(&event_for(&bad, &key, 1).json).is_none());
        // a changed byte
        let mut j = event_for(&card(), &key, 1).json;
        j["content"] = json!(serde_json::to_string(&Card { m: 99_999, ..card() }).unwrap());
        assert!(open(&j).is_none());
    }
}
