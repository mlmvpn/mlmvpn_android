//! The official chats' items on our own relays, once for everyone (SPEC 18.7, T77; Ehsan, 31 Oct
//! 2026: «هنوز پیام ها دیر میاد طرف باید حتما یه نقطه ای چیزی بزاره تا پیام های بقیه براش بیاد»).
//! An item went from phone to phone, sealed again for each contact reached lately: a phone that had
//! sent nothing lately got nothing until it posted, and the copies flooded our relay. Now each item
//! is one event there, the same whoever leaves it (so the relay keeps one), and every phone reads
//! them as they come, over the connection it keeps open to that relay anyway.
//!
//! - The event: kind `KIND`, `created_at` the item's own time, tags `f` (the feed), `i` (the item)
//!   and an `expiration` a week later, signed by a key made from the feed and the item. Its content
//!   is the signed item sealed with a key made from the same (anyone with the app reads it, as
//!   anyone reads the official chats), so it is the same bytes from every phone.
//! - A phone checks each item exactly as one from a contact (shape, signature, the author's right
//!   in the feed): the relay can withhold an item, never forge or change one.

use super::event::{self, Event};
use crate::feed::SignedFeedItem;
use crate::util::{b64url_decode, b64url_encode};
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use k256::schnorr::SigningKey;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

/// The events' kind: a regular one (relays keep it), Gereh's own.
pub const KIND: u64 = 3061;
/// How long our relay keeps an item: a week.
pub const EXPIRE_S: u64 = 7 * 86_400;
/// A phone new to a relay reads the items of this long before, newest first up to `LIMIT`.
pub const FIRST_S: u64 = 3 * 86_400;
pub const LIMIT: usize = 500;

fn derive(label: &[u8], feed: &[u8], item: &[u8]) -> [u8; 32] {
    let mut d = Sha256::new();
    d.update(label);
    d.update((feed.len() as u32).to_be_bytes());
    d.update(feed);
    d.update(item);
    d.finalize().into()
}

fn signer(feed: &[u8], item: &[u8]) -> Option<SigningKey> {
    // a hash out of the curve's range is all but impossible; one more try covers it
    (0u8..4).find_map(|n| {
        let mut d = derive(b"mlmchat/feed-relay/sign/1", feed, item);
        d[31] ^= n;
        SigningKey::from_bytes(&d).ok()
    })
}

/// A key for this item alone: its one nonce never repeats under it.
fn cipher(feed: &[u8], item: &[u8]) -> ChaCha20Poly1305 {
    ChaCha20Poly1305::new(Key::from_slice(&derive(b"mlmchat/feed-relay/seal/1", feed, item)))
}

/// The one event of a signed item; `feed`, `item` and `ts_ms` are what it holds.
pub fn event_for(signed: &SignedFeedItem, feed: &[u8], item: &[u8], ts_ms: u64) -> Option<Event> {
    let key = signer(feed, item)?;
    let sealed = cipher(feed, item).encrypt(Nonce::from_slice(&[0u8; 12]), signed.to_bytes().as_slice()).ok()?;
    let at = ts_ms / 1000;
    let tags = json!([["f", hex::encode(feed)], ["i", hex::encode(item)], ["expiration", (at + EXPIRE_S).to_string()]]);
    Some(event::signed_with(&key, KIND, tags, b64url_encode(&sealed), at))
}

/// The signed item one of these events holds; None for anything else. Whether it counts is the
/// feed's to say, as for an item from a contact.
pub fn open(ev: &Value) -> Option<SignedFeedItem> {
    if ev.get("kind")?.as_u64()? != KIND {
        return None;
    }
    let tags = ev.get("tags")?.as_array()?;
    let tag = |name: &str| {
        tags.iter().find_map(|t| {
            let t = t.as_array()?;
            if t.first()?.as_str()? == name {
                t.get(1)?.as_str()
            } else {
                None
            }
        })
    };
    let feed = hex::decode(tag("f")?).ok()?;
    let item = hex::decode(tag("i")?).ok()?;
    let sealed = b64url_decode(ev.get("content")?.as_str()?)?;
    let plain = cipher(&feed, &item).decrypt(Nonce::from_slice(&[0u8; 12]), sealed.as_slice()).ok()?;
    SignedFeedItem::from_bytes(&plain).ok()
}

/// The subscription to the items of `feeds` written since `since_s`.
pub fn filter(feeds: &[Vec<u8>], since_s: u64) -> Value {
    json!({ "kinds": [KIND], "#f": feeds.iter().map(hex::encode).collect::<Vec<_>>(), "since": since_s, "limit": LIMIT })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::feed::{FeedItem, Item};

    fn item(id: u8) -> (SignedFeedItem, FeedItem) {
        let key = vodozemac::Ed25519SecretKey::from_slice(&[7; 32]);
        let fi = FeedItem {
            feed_id: vec![2; 16],
            id: vec![id; 16],
            author: key.public_key().as_bytes().to_vec(),
            ts_ms: 1_793_000_000_000,
            name: "ali".into(),
            item: Some(Item::Post(crate::envelope::Envelope::text("salam").to_bytes().unwrap())),
            ..Default::default()
        };
        (SignedFeedItem::sign(&fi, |m| key.sign(m).to_bytes()), fi)
    }

    #[test]
    fn one_event_whoever_leaves_it_and_it_opens() {
        let (s, fi) = item(5);
        let a = event_for(&s, &fi.feed_id, &fi.id, fi.ts_ms).unwrap();
        let b = event_for(&s, &fi.feed_id, &fi.id, fi.ts_ms).unwrap();
        assert_eq!(a.id, b.id, "the same item makes the same event");
        assert_eq!(a.json["kind"], KIND);
        assert_eq!(a.json["created_at"], fi.ts_ms / 1000);
        assert_eq!(open(&a.json).unwrap().to_bytes(), s.to_bytes());
        // another item, another event and another key
        let (s2, fi2) = item(6);
        let c = event_for(&s2, &fi2.feed_id, &fi2.id, fi2.ts_ms).unwrap();
        assert_ne!(a.id, c.id);
        assert_ne!(a.json["pubkey"], c.json["pubkey"]);
        // a tag that does not name what is inside: nothing opens
        let mut wrong = a.json.clone();
        wrong["tags"][1][1] = json!(hex::encode([6u8; 16]));
        assert!(open(&wrong).is_none());
        // not ours
        let mut other = a.json.clone();
        other["kind"] = json!(1059);
        assert!(open(&other).is_none());
    }
}
