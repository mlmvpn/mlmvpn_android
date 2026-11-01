//! Nostr events (NIP-01) that carry sealed frames (SPEC 20). Each event is kind 1059 (the gift
//! wrap of NIP-59: an opaque sealed thing under a throwaway key), with a `p` tag the recipient
//! subscribes to and an `expiration` (NIP-40) after which relays drop it. Its key is made for this
//! one event, so two events of the same phone look unrelated; its BIP-340 signature (k256) is only
//! what relays need to take it. What it says is the sealed frame: secrecy and the sender come
//! from that seal (SPEC 6, 14.6), never from this layer.

use crate::util::{b64url_decode, b64url_encode, random};
use k256::schnorr::{SigningKey, VerifyingKey};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

pub const KIND: u64 = 1059;
/// Relays keep an event this long (NIP-40), the same as the Google mailbox's 6 hours is not
/// enough for a phone off for a weekend.
pub const EXPIRE_S: u64 = 2 * 86_400;

/// The recipient's tag for a day: what senders put in `p` and the recipient subscribes to. It
/// comes from the recipient's locator, which only its contacts and group members hold, and it
/// changes every day; it is unrelated to the Google mailbox's tag of the same day.
pub fn p_tag(loc: &[u8; 32], day: u64) -> String {
    let mut d = Sha256::new();
    d.update(b"mlmchat/nostr-tag/1");
    d.update(loc);
    d.update(day.to_be_bytes());
    hex::encode(d.finalize())
}

/// One event, ready to send as `["EVENT", <this>]`.
#[derive(Clone, Debug)]
pub struct Event {
    pub id: [u8; 32],
    pub json: Value,
}

fn id_of(pubkey: &str, created: u64, kind: u64, tags: &Value, content: &str) -> [u8; 32] {
    let ser = json!([0, pubkey, created, kind, tags, content]).to_string();
    Sha256::digest(ser.as_bytes()).into()
}

/// An event of `kind`, signed by a key made for it alone.
pub(super) fn signed(kind: u64, tags: Value, content: String, now_s: u64) -> Event {
    let key = loop {
        if let Ok(k) = SigningKey::from_bytes(&random::<32>()) {
            break k;
        }
    };
    signed_with(&key, kind, tags, content, now_s)
}

/// An event of `kind` signed by `key`: one that stays the same from event to event, as a
/// replaceable event's must (a channel's card in the public list, `dir`).
pub(super) fn signed_with(key: &SigningKey, kind: u64, tags: Value, content: String, now_s: u64) -> Event {
    let pubkey = hex::encode(key.verifying_key().to_bytes());
    let id = id_of(&pubkey, now_s, kind, &tags, &content);
    let sig = key.sign_prehash_with_aux_rand(&id, &random::<32>()).expect("a 32-byte message always signs");
    Event {
        id,
        json: json!({ "id": hex::encode(id), "pubkey": pubkey, "created_at": now_s, "kind": kind, "tags": tags, "content": content, "sig": hex::encode(sig.to_bytes()) }),
    }
}

/// An event from a relay whose id matches its fields and whose signature checks (a relay could
/// hand over anything): its id, kind, tags and content.
pub(super) fn checked(v: &Value) -> Option<([u8; 32], u64, &Value, &str)> {
    let kind = v.get("kind")?.as_u64()?;
    let pubkey = v.get("pubkey")?.as_str()?;
    let created = v.get("created_at")?.as_u64()?;
    let tags = v.get("tags")?;
    let content = v.get("content")?.as_str()?;
    let id = id_of(pubkey, created, kind, tags, content);
    if hex::encode(id) != v.get("id")?.as_str()? {
        return None;
    }
    let key = VerifyingKey::from_bytes(&hex::decode(pubkey).ok()?).ok()?;
    let sig = k256::schnorr::Signature::try_from(hex::decode(v.get("sig")?.as_str()?).ok()?.as_slice()).ok()?;
    key.verify_raw(&id, &sig).ok()?;
    Some((id, kind, tags, content))
}

/// An event for `sealed`, to the recipient whose day tag is `p`, signed by a fresh key.
pub fn make(sealed: &[u8], p: &str, now_s: u64) -> Event {
    let tags = json!([["p", p], ["expiration", (now_s + EXPIRE_S).to_string()]]);
    signed(KIND, tags, b64url_encode(sealed), now_s)
}

/// The sealed frame an event from a relay carries, if it is one of ours: kind 1059, an id that
/// matches its fields, a valid signature.
pub fn open(v: &Value) -> Option<([u8; 32], Vec<u8>)> {
    if v.get("kind")?.as_u64()? != KIND {
        return None;
    }
    let (id, _, _, content) = checked(v)?;
    Some((id, b64url_decode(content)?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_event_checks_and_gives_back_its_frame() {
        let p = p_tag(&[7; 32], 20_000);
        let e = make(b"sealed bytes", &p, 1_761_480_000);
        let (id, frame) = open(&e.json).expect("a valid event");
        assert_eq!(id, e.id);
        assert_eq!(frame, b"sealed bytes");
        assert_eq!(e.json["kind"], 1059);
        assert_eq!(e.json["tags"][0][1], p.as_str());
        assert_eq!(e.json["tags"][1][1], (1_761_480_000 + EXPIRE_S).to_string());
        // a changed byte anywhere breaks it
        let mut bad = e.json.clone();
        bad["content"] = json!("AAAA");
        assert!(open(&bad).is_none());
        let mut bad = e.json.clone();
        bad["created_at"] = json!(1);
        assert!(open(&bad).is_none());
    }

    #[test]
    fn two_events_have_unrelated_keys_and_tags_change_daily() {
        let p = p_tag(&[7; 32], 20_000);
        assert_ne!(make(b"x", &p, 1).json["pubkey"], make(b"x", &p, 1).json["pubkey"]);
        assert_ne!(p, p_tag(&[7; 32], 20_001));
        assert_ne!(p, p_tag(&[8; 32], 20_000));
        assert_eq!(p.len(), 64);
    }

    #[test]
    fn a_signature_from_the_spec_examples_checks() {
        // BIP-340 test vector 1 (secret key 3, aux 0, message 0): the same Schnorr Nostr uses
        let key = SigningKey::from_bytes(&hex::decode("0000000000000000000000000000000000000000000000000000000000000003").unwrap()).unwrap();
        let sig = key.sign_prehash_with_aux_rand(&[0u8; 32], &[0u8; 32]).unwrap();
        assert_eq!(
            hex::encode(sig.to_bytes()).to_uppercase(),
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"
        );
    }
}
