//! Private LAN discovery (SPEC 8, 14.8), in place of plain mDNS that broadcasts a node id.
//!
//! A beacon is `epoch (u32 BE) || port (u16 BE) || tag (16 bytes) [|| more tags]`, sent over UDP
//! every few seconds. tag = first 16 bytes of HMAC-SHA256(secret, "mlmchat/lan/1" || epoch BE),
//! secret = the sender's `loc` (later also one per group). The epoch changes every 10 minutes, so
//! only those who hold the secret recognise the beacon; to everyone else it is random bytes.

use crate::UserId;
use hmac::{Hmac, KeyInit, Mac};
use sha2::Sha256;

pub const EPOCH_S: u64 = 600;
pub const INFO: &[u8] = b"mlmchat/lan/1";
pub const TAG_LEN: usize = 16;
pub const MAX_TAGS: usize = 8;
const HEAD: usize = 6;

pub fn epoch(now_s: u64) -> u32 {
    (now_s / EPOCH_S) as u32
}

pub fn tag(secret: &[u8; 32], epoch: u32) -> [u8; TAG_LEN] {
    let mut mac = <Hmac<Sha256> as KeyInit>::new_from_slice(secret).expect("HMAC takes any key length");
    mac.update(INFO);
    mac.update(&epoch.to_be_bytes());
    let full = mac.finalize().into_bytes();
    let mut out = [0u8; TAG_LEN];
    out.copy_from_slice(&full[..TAG_LEN]);
    out
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Beacon {
    pub epoch: u32,
    pub port: u16,
    pub tags: Vec<[u8; TAG_LEN]>,
}

impl Beacon {
    pub fn new(secret: &[u8; 32], now_s: u64, port: u16) -> Beacon {
        let e = epoch(now_s);
        Beacon { epoch: e, port, tags: vec![tag(secret, e)] }
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEAD + TAG_LEN * self.tags.len());
        out.extend_from_slice(&self.epoch.to_be_bytes());
        out.extend_from_slice(&self.port.to_be_bytes());
        for t in &self.tags {
            out.extend_from_slice(t);
        }
        out
    }

    /// Strict parse: exact length, 1..=8 tags, a usable port.
    pub fn from_bytes(bytes: &[u8]) -> Option<Beacon> {
        if bytes.len() < HEAD + TAG_LEN || !(bytes.len() - HEAD).is_multiple_of(TAG_LEN) || (bytes.len() - HEAD) / TAG_LEN > MAX_TAGS {
            return None;
        }
        let epoch = u32::from_be_bytes(bytes[0..4].try_into().ok()?);
        let port = u16::from_be_bytes(bytes[4..6].try_into().ok()?);
        if port == 0 {
            return None;
        }
        let tags = bytes[HEAD..].chunks(TAG_LEN).map(|c| c.try_into().expect("exact chunk")).collect();
        Some(Beacon { epoch, port, tags })
    }

    /// The contact whose beacon this is, if any. Only the sender's epoch and its two neighbours
    /// around our own clock are accepted (10 minutes of clock difference either way).
    pub fn recognise(&self, secrets: &[(UserId, [u8; 32])], now_s: u64) -> Option<UserId> {
        let ours = epoch(now_s);
        if self.epoch.abs_diff(ours) > 1 {
            return None;
        }
        secrets.iter().find(|(_, s)| {
            let expected = tag(s, self.epoch);
            self.tags.iter().any(|t| constant_time_eq(t, &expected))
        })
        .map(|(sk, _)| *sk)
    }
}

fn constant_time_eq(a: &[u8; TAG_LEN], b: &[u8; TAG_LEN]) -> bool {
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_and_recognise() {
        let loc = [9u8; 32];
        let b = Beacon::new(&loc, 1_800_000_000, 4100);
        let back = Beacon::from_bytes(&b.to_bytes()).unwrap();
        assert_eq!(back, b);
        assert_eq!(back.to_bytes().len(), 22);
        assert_eq!(back.recognise(&[([1; 32], [8; 32]), ([2; 32], loc)], 1_800_000_000), Some([2; 32]));
        assert_eq!(back.recognise(&[([1; 32], [8; 32])], 1_800_000_000), None);
        // stale: two epochs away
        assert_eq!(back.recognise(&[([2; 32], loc)], 1_800_000_000 + 2 * EPOCH_S), None);
    }

    #[test]
    fn tag_changes_every_epoch_and_bad_shapes_are_refused() {
        assert_ne!(tag(&[9; 32], 1), tag(&[9; 32], 2));
        assert!(Beacon::from_bytes(&[0; 21]).is_none());
        assert!(Beacon::from_bytes(&[0; 22]).is_none()); // port 0
        assert!(Beacon::from_bytes(&[0; 6 + 16 * 9]).is_none());
    }
}
