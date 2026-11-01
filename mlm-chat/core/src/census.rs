//! The member count of the official feeds (T38; Ehsan, 25 Oct 2026: count them phone to phone,
//! with the Google relay's help). Everyone who installs Gereh is in both official chats and they
//! keep no member list, so the count is an estimate every phone works out the same way:
//!
//! - Each phone has a random install secret that never leaves it. For each period of
//!   `EPOCH_MS` it makes a token from it (unlinkable from one period to the next, and to nothing
//!   about its identity) and puts the token in a HyperLogLog sketch: 2048 small counters, the
//!   highest "rarity" seen in each. A sketch says nothing about who is in it.
//! - Phones pass their sketches along with the official feeds' periodic catch-up (`FeedMsg`, so
//!   also through the Google relay when that is the only way to a contact) and merge what they
//!   get: each counter keeps the higher value. Merging is order-free and repeat-free, so every
//!   phone ends with the same sketch.
//! - The count shown is the estimate of this period's and the last period's sketches merged:
//!   the phones active in the last two to four weeks, within about 2.3% (1.04/sqrt(2048)).
//!
//! A modified app can raise counters and make the number bigger; nothing without a server can
//! stop that, and the counters are capped so a sketch stays a sketch (5 bits each).

use crate::{Error, Result};
use sha2::{Digest, Sha256};

/// Counters: 2^P.
pub const P: u32 = 11;
pub const M: usize = 1 << P;
/// One counting period. The count covers this one and the last.
pub const EPOCH_MS: u64 = 14 * 24 * 3600 * 1000;
/// A sketch on the wire: 5 bits per counter.
pub const PACKED_LEN: usize = M * 5 / 8;
const MAX_REG: u8 = 31;

/// The counting period of a time.
pub fn epoch_of(now_ms: u64) -> u32 {
    (now_ms / EPOCH_MS) as u32
}

/// This phone's token for a period.
pub fn token(secret: &[u8; 32], epoch: u32) -> [u8; 32] {
    let mut d = Sha256::new();
    d.update(b"mlmchat/census-token/1");
    d.update(secret);
    d.update(epoch.to_be_bytes());
    d.finalize().into()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Sketch {
    pub epoch: u32,
    regs: Vec<u8>,
}

impl Sketch {
    pub fn new(epoch: u32) -> Sketch {
        Sketch { epoch, regs: vec![0; M] }
    }

    pub fn is_empty(&self) -> bool {
        self.regs.iter().all(|r| *r == 0)
    }

    /// Count a token in. True if a counter rose.
    pub fn add(&mut self, token: &[u8]) -> bool {
        let h = Sha256::digest(token);
        let mut x = [0u8; 8];
        x.copy_from_slice(&h[..8]);
        let x = u64::from_be_bytes(x);
        let idx = (x >> (64 - P)) as usize;
        let rest = x << P;
        let rank = (rest.leading_zeros() + 1).min(64 - P + 1).min(MAX_REG as u32) as u8;
        if rank > self.regs[idx] {
            self.regs[idx] = rank;
            true
        } else {
            false
        }
    }

    /// Take in another phone's sketch of the same period. True if a counter rose.
    pub fn merge(&mut self, other: &Sketch) -> bool {
        if other.epoch != self.epoch {
            return false;
        }
        let mut rose = false;
        for (a, b) in self.regs.iter_mut().zip(&other.regs) {
            if *b > *a {
                *a = *b;
                rose = true;
            }
        }
        rose
    }

    /// True if `other` lacks something this sketch has.
    pub fn ahead_of(&self, other: &Sketch) -> bool {
        self.epoch == other.epoch && self.regs.iter().zip(&other.regs).any(|(a, b)| a > b)
    }

    /// How many different tokens went in, estimated.
    pub fn estimate(&self) -> f64 {
        estimate(&self.regs)
    }

    /// The estimate of this sketch and another merged (counters taken whatever their period).
    pub fn estimate_with(&self, other: &Sketch) -> f64 {
        let regs: Vec<u8> = self.regs.iter().zip(&other.regs).map(|(a, b)| *a.max(b)).collect();
        estimate(&regs)
    }

    pub fn pack(&self) -> Vec<u8> {
        let mut out = vec![0u8; PACKED_LEN];
        for (i, r) in self.regs.iter().enumerate() {
            let bit = i * 5;
            let v = (*r as u16) << (11 - bit % 8);
            out[bit / 8] |= (v >> 8) as u8;
            if bit / 8 + 1 < PACKED_LEN {
                out[bit / 8 + 1] |= v as u8;
            }
        }
        out
    }

    pub fn unpack(epoch: u32, bytes: &[u8]) -> Result<Sketch> {
        if bytes.len() != PACKED_LEN {
            return Err(Error::Envelope("census sketch of the wrong size"));
        }
        let mut regs = vec![0u8; M];
        for (i, r) in regs.iter_mut().enumerate() {
            let bit = i * 5;
            let hi = bytes[bit / 8] as u16;
            let lo = if bit / 8 + 1 < PACKED_LEN { bytes[bit / 8 + 1] as u16 } else { 0 };
            *r = ((((hi << 8) | lo) >> (11 - bit % 8)) & 0x1f) as u8;
        }
        Ok(Sketch { epoch, regs })
    }
}

/// HyperLogLog's estimate, with linear counting while few counters are set.
fn estimate(regs: &[u8]) -> f64 {
    let m = regs.len() as f64;
    let alpha = 0.7213 / (1.0 + 1.079 / m);
    let sum: f64 = regs.iter().map(|r| 2f64.powi(-(*r as i32))).sum();
    let raw = alpha * m * m / sum;
    let zeros = regs.iter().filter(|r| **r == 0).count();
    if raw <= 2.5 * m && zeros > 0 {
        m * (m / zeros as f64).ln()
    } else {
        raw
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tokens(n: u32, salt: u8) -> impl Iterator<Item = [u8; 32]> {
        (0..n).map(move |i| {
            let mut s = [salt; 32];
            s[..4].copy_from_slice(&i.to_be_bytes());
            token(&s, 7)
        })
    }

    #[test]
    fn empty_counts_nothing_and_one_counts_one() {
        let mut s = Sketch::new(7);
        assert_eq!(s.estimate().round(), 0.0);
        assert!(s.add(&token(&[1; 32], 7)));
        assert!(!s.add(&token(&[1; 32], 7)), "the same token twice is one member");
        assert_eq!(s.estimate().round(), 1.0);
    }

    #[test]
    fn estimates_stay_within_a_few_percent() {
        for n in [100u32, 5_000, 50_000] {
            let mut s = Sketch::new(7);
            for t in tokens(n, 3) {
                s.add(&t);
            }
            let e = s.estimate();
            let err = (e - n as f64).abs() / n as f64;
            assert!(err < 0.07, "{n}: estimated {e}");
        }
    }

    #[test]
    fn merging_is_the_union_and_order_free() {
        let (mut a, mut b) = (Sketch::new(7), Sketch::new(7));
        for t in tokens(3_000, 1) {
            a.add(&t);
        }
        for t in tokens(3_000, 2) {
            b.add(&t);
        }
        let mut ab = a.clone();
        ab.merge(&b);
        let mut ba = b.clone();
        ba.merge(&a);
        assert_eq!(ab, ba);
        assert!(!ab.clone().merge(&a), "merging again changes nothing");
        let e = ab.estimate();
        assert!((e - 6_000.0).abs() / 6_000.0 < 0.07, "union estimated {e}");
        assert!(a.ahead_of(&b) && ab.ahead_of(&a) && !a.ahead_of(&ab));
        assert!(!ab.merge(&Sketch::new(8)), "another period is not merged");
    }

    #[test]
    fn packing_round_trips() {
        let mut s = Sketch::new(9);
        for t in tokens(20_000, 5) {
            s.add(&t);
        }
        s.regs[0] = 31;
        s.regs[M - 1] = 17;
        let p = s.pack();
        assert_eq!(p.len(), PACKED_LEN);
        assert_eq!(Sketch::unpack(9, &p).unwrap(), s);
        assert!(Sketch::unpack(9, &p[1..]).is_err());
    }

    #[test]
    fn tokens_change_with_the_period() {
        let s = [4; 32];
        assert_ne!(token(&s, 1), token(&s, 2));
        assert_eq!(epoch_of(EPOCH_MS * 3 + 5), 3);
    }
}
