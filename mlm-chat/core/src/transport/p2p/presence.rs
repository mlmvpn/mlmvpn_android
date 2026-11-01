//! The presence record (SPEC 8, exact form in SPEC 15.2): where a user can be reached today, in
//! the private DHT, readable only by those who hold their locator.
//!
//! - key: `SHA-256("mlmchat/rdv/1" || loc || day)`, day = UTC days since 1970 as u32 BE.
//! - value: `nonce(24) || XChaCha20-Poly1305(HKDF-SHA256(ikm: loc, info: "mlmchat/rdv/1" || day),
//!   nonce, aad: key, PresenceSealed padded to 960 bytes)`, so every record is 1000 bytes.
//! - `PresenceSealed.sig` is Ed25519 by the user's `sk` over
//!   `"mlmchat/rdv/1/sig" || key || body`; `body` is the encoded `PresenceBody`, kept as bytes.
//!
//! A DHT node sees a random-looking key and 1000 random-looking bytes that change every day.
//! Everyone holding `loc` can decrypt, so the signature is what stops a contact (or a former one)
//! from forging someone else's record.

use crate::{UserId, Result, Error};
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use prost::Message;
use sha2::{Digest, Sha256};

pub const RDV: &[u8] = b"mlmchat/rdv/1";
pub const SIG_CONTEXT: &[u8] = b"mlmchat/rdv/1/sig";
pub const PRESENCE_VERSION: u32 = 1;
/// Plaintext size every record is padded to.
pub const SEALED_LEN: usize = 960;
/// nonce + padded plaintext + Poly1305 tag.
pub const VALUE_LEN: usize = 24 + SEALED_LEN + 16;
/// A record is kept this long (SPEC 8: 26 hours).
pub const RECORD_TTL_S: u64 = 26 * 3600;
/// Tomorrow's record is published from this hour (UTC), so the change of day has no gap.
pub const NEXT_DAY_FROM_S: u64 = 22 * 3600;
/// A record claiming a time further ahead than this is refused.
const FUTURE_SKEW_MS: u64 = 10 * 60 * 1000;

#[derive(Clone, PartialEq, Message)]
pub struct PresenceBody {
    #[prost(uint32, tag = "1")]
    pub v: u32,
    /// The libp2p peer id (multihash bytes) the user's device has right now.
    #[prost(bytes = "vec", tag = "2")]
    pub peer_id: Vec<u8>,
    /// Binary multiaddrs to dial: direct ones and volunteer circuits (`.../p2p-circuit`).
    #[prost(bytes = "vec", repeated, tag = "3")]
    pub addrs: Vec<Vec<u8>>,
    #[prost(uint64, tag = "4")]
    pub ts_ms: u64,
}

#[derive(Clone, PartialEq, Message)]
pub struct PresenceSealed {
    #[prost(bytes = "vec", tag = "1")]
    pub body: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub sig: Vec<u8>,
    #[prost(bytes = "vec", tag = "15")]
    pub pad: Vec<u8>,
}

pub fn day(now_s: u64) -> u32 {
    (now_s / 86400) as u32
}

/// The DHT key of `loc`'s record for `day`.
pub fn dht_key(loc: &[u8; 32], day: u32) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(RDV);
    h.update(loc);
    h.update(day.to_be_bytes());
    h.finalize().into()
}

fn aead_key(loc: &[u8; 32], day: u32) -> [u8; 32] {
    let mut info = RDV.to_vec();
    info.extend_from_slice(&day.to_be_bytes());
    let mut out = [0u8; 32];
    Hkdf::<Sha256>::new(None, loc).expand(&info, &mut out).expect("32 bytes is a valid HKDF length");
    out
}

fn sig_input(key: &[u8; 32], body: &[u8]) -> Vec<u8> {
    let mut m = Vec::with_capacity(SIG_CONTEXT.len() + 32 + body.len());
    m.extend_from_slice(SIG_CONTEXT);
    m.extend_from_slice(key);
    m.extend_from_slice(body);
    m
}

fn varint_len(mut v: usize) -> usize {
    let mut n = 1;
    while v >= 0x80 {
        v >>= 7;
        n += 1;
    }
    n
}

/// `PresenceSealed` encoded to exactly [`SEALED_LEN`] bytes (field 15 takes the rest).
fn pad_sealed(mut sealed: PresenceSealed) -> Result<Vec<u8>> {
    sealed.pad.clear();
    let bare = sealed.encoded_len();
    // tag of field 15 is one byte; find the pad length whose varint fits exactly
    let mut n = SEALED_LEN.checked_sub(bare + 2).ok_or(Error::Envelope("presence record too large"))?;
    loop {
        let total = bare + 1 + varint_len(n) + n;
        if total == SEALED_LEN {
            break;
        }
        if total < SEALED_LEN || n == 0 {
            return Err(Error::Envelope("presence record cannot be padded"));
        }
        n -= 1;
    }
    sealed.pad = vec![0u8; n];
    let out = sealed.encode_to_vec();
    debug_assert_eq!(out.len(), SEALED_LEN);
    Ok(out)
}

/// Build the sealed record for `day`. `sign` signs with the user's identity key. Addresses that
/// do not fit are left out (the first ones are the most useful).
pub fn seal(loc: &[u8; 32], day: u32, peer_id: Vec<u8>, mut addrs: Vec<Vec<u8>>, ts_ms: u64, sign: &dyn Fn(&[u8]) -> [u8; 64], nonce: [u8; 24]) -> Result<([u8; 32], Vec<u8>)> {
    let key = dht_key(loc, day);
    let plain = loop {
        let body = PresenceBody { v: PRESENCE_VERSION, peer_id: peer_id.clone(), addrs: addrs.clone(), ts_ms }.encode_to_vec();
        let sig = sign(&sig_input(&key, &body)).to_vec();
        match pad_sealed(PresenceSealed { body, sig, pad: Vec::new() }) {
            Ok(p) => break p,
            Err(_) if !addrs.is_empty() => {
                addrs.pop();
            }
            Err(e) => return Err(e),
        }
    };
    let cipher = XChaCha20Poly1305::new(&aead_key(loc, day).into());
    let ct = cipher.encrypt(XNonce::from_slice(&nonce), Payload { msg: &plain, aad: &key }).map_err(|_| Error::Envelope("presence encryption"))?;
    let mut value = Vec::with_capacity(VALUE_LEN);
    value.extend_from_slice(&nonce);
    value.extend_from_slice(&ct);
    Ok((key, value))
}

/// Open a record found under `dht_key(loc, day)` and check it is `sk`'s. `None` for anything
/// that is not exactly a valid, signed, current record.
pub fn open(loc: &[u8; 32], day: u32, sk: &UserId, value: &[u8], now_ms: u64) -> Option<PresenceBody> {
    if value.len() != VALUE_LEN {
        return None;
    }
    let key = dht_key(loc, day);
    let cipher = XChaCha20Poly1305::new(&aead_key(loc, day).into());
    let plain = cipher.decrypt(XNonce::from_slice(&value[..24]), Payload { msg: &value[24..], aad: &key }).ok()?;
    let sealed = PresenceSealed::decode(plain.as_slice()).ok()?;
    let sig: [u8; 64] = sealed.sig.as_slice().try_into().ok()?;
    if !crate::transport::session::verify(sk, &sig_input(&key, &sealed.body), &sig) {
        return None;
    }
    let body = PresenceBody::decode(sealed.body.as_slice()).ok()?;
    if body.v != PRESENCE_VERSION || body.ts_ms > now_ms + FUTURE_SKEW_MS || body.ts_ms + RECORD_TTL_S * 1000 < now_ms {
        return None;
    }
    Some(body)
}

/// The days a record should exist for now: today, and tomorrow from 22:00 UTC.
pub fn publish_days(now_s: u64) -> Vec<u32> {
    let d = day(now_s);
    if now_s % 86400 >= NEXT_DAY_FROM_S {
        vec![d, d + 1]
    } else {
        vec![d]
    }
}

/// The days a lookup tries, newest first: today, then yesterday (a device that has been offline
/// since before midnight still has yesterday's record).
pub fn lookup_days(now_s: u64) -> [u32; 2] {
    let d = day(now_s);
    [d, d.saturating_sub(1)]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::Identity;

    #[test]
    fn seal_open_round_trip_and_refusals() {
        let id = Identity::new("x").unwrap();
        let other = Identity::new("y").unwrap();
        let loc = id.loc();
        let sign = |m: &[u8]| id.sign(m);
        let now = 1_800_000_000_000u64;
        let d = day(now / 1000);
        let addrs = vec![vec![4, 127, 0, 0, 1, 6, 0x10, 0x04]; 3];
        let (key, value) = seal(&loc, d, vec![1, 2, 3], addrs.clone(), now, &sign, [9; 24]).unwrap();
        assert_eq!(key, dht_key(&loc, d));
        assert_eq!(value.len(), VALUE_LEN);
        let body = open(&loc, d, &id.sk(), &value, now).unwrap();
        assert_eq!(body.peer_id, vec![1, 2, 3]);
        assert_eq!(body.addrs, addrs);
        // another user's sk, another day, a flipped byte, a stale record: refused
        assert!(open(&loc, d, &other.sk(), &value, now).is_none());
        assert!(open(&loc, d + 1, &id.sk(), &value, now).is_none());
        let mut bad = value.clone();
        bad[100] ^= 1;
        assert!(open(&loc, d, &id.sk(), &bad, now).is_none());
        assert!(open(&loc, d, &id.sk(), &value, now + (RECORD_TTL_S + 1) * 1000).is_none());
        // a record signed by someone else under the same locator is refused
        let forge = |m: &[u8]| other.sign(m);
        let (_, forged) = seal(&loc, d, vec![7], Vec::new(), now, &forge, [1; 24]).unwrap();
        assert!(open(&loc, d, &id.sk(), &forged, now).is_none());
        // the value says nothing in clear
        assert!(!value.windows(3).any(|w| w == [1, 2, 3]));
    }

    #[test]
    fn too_many_addresses_are_cut_not_refused() {
        let id = Identity::new("x").unwrap();
        let sign = |m: &[u8]| id.sign(m);
        let addrs = vec![vec![0xa5; 100]; 20];
        let (_, value) = seal(&id.loc(), 1, vec![1; 38], addrs, 5, &sign, [0; 24]).unwrap();
        let body = open(&id.loc(), 1, &id.sk(), &value, 5).unwrap();
        assert!(!body.addrs.is_empty() && body.addrs.len() < 20);
    }

    #[test]
    fn days() {
        assert_eq!(publish_days(86400 * 10 + 3600), vec![10]);
        assert_eq!(publish_days(86400 * 10 + 22 * 3600), vec![10, 11]);
        assert_eq!(lookup_days(86400 * 10), [10, 9]);
    }
}
