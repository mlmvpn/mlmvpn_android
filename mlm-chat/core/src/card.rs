//! Contact card and `mlmchat://c/` link (SPEC 3, 14.3).
//!
//! The card is a CBOR map with integer keys in deterministic form (RFC 8949 4.2.1: definite
//! lengths, shortest integers, keys ascending). `sig` (key 15) is Ed25519 by `sk` over the same
//! map without key 15. Unknown keys are kept as raw bytes, covered by the signature, ignored.

use crate::util::{array, b64url_decode, b64url_encode};
use crate::{Error, Result, UserId};
use minicbor::{Decoder, Encoder};
use vodozemac::{Ed25519PublicKey, Ed25519Signature};

pub const CARD_VERSION: u64 = 1;
pub const LINK_PREFIX: &str = "mlmchat://c/";
pub const MAX_CARD_BYTES: usize = 1024;
pub const MAX_NAME_BYTES: usize = 64;
pub const MAX_ADDRS: usize = 8;
pub const MAX_ADDR_BYTES: usize = 64;
pub const MAX_BOOT: usize = 3;
pub const MAX_BOOT_BYTES: usize = 128;
/// Google mailbox relays where this device takes its frames (SPEC 19.3), best first.
pub const MAX_RELAYS: usize = 3;
pub const MAX_RELAY_BYTES: usize = 128;
/// Nostr relays this device reads from (SPEC 20), as names without `wss://`.
pub const MAX_NOSTR: usize = 4;
pub const MAX_NOSTR_BYTES: usize = 64;
/// Combined relays (SPEC 19.13): a Google door and a Cloudflare Worker, `RelayRef` form 2.
pub const MAX_RELAYS2: usize = 2;
pub const MAX_RELAY2_BYTES: usize = crate::transport::gas::proto::MAX_COMBINED_BYTES;

const K_V: u64 = 0;
const K_SK: u64 = 1;
const K_IK: u64 = 2;
const K_OTK: u64 = 3;
const K_LOC: u64 = 4;
const K_NAME: u64 = 5;
const K_ADDRS: u64 = 6;
const K_BOOT: u64 = 7;
const K_EXP: u64 = 8;
const K_TICKET: u64 = 9;
const K_PAKE: u64 = 10;
const K_HK: u64 = 11;
const K_RELAYS: u64 = 12;
const K_NOSTR: u64 = 13;
const K_RELAYS2: u64 = 14;
const K_SIG: u64 = 15;

/// Face to face (QR on a screen) carries `loc`; a remote link carries a ticket instead.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CardKind {
    FaceToFace { loc: [u8; 32] },
    Remote { ticket: [u8; 16], pake: bool },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Card {
    pub v: u64,
    pub sk: UserId,
    pub ik: [u8; 32],
    pub otk: [u8; 32],
    pub hk: [u8; 32],
    pub kind: CardKind,
    pub name: Option<String>,
    pub addrs: Vec<Vec<u8>>,
    pub boot: Vec<Vec<u8>>,
    /// Mailbox relays (SPEC 19.3): `transport::gas::RelayRef` bytes, at most `MAX_RELAYS`.
    pub relays: Vec<Vec<u8>>,
    /// Nostr relays (SPEC 20): names without `wss://`, at most `MAX_NOSTR`. A sender writes only
    /// to those of them its own list has.
    pub nostr: Vec<Vec<u8>>,
    /// Combined relays (SPEC 19.13, key 14): `RelayRef` bytes of form 2, at most `MAX_RELAYS2`. A
    /// key of its own: key 12's 128 bytes are too few for two addresses, and a longer item there
    /// would make older cores refuse the whole card.
    pub relays2: Vec<Vec<u8>>,
    pub exp: u64,
    pub sig: [u8; 64],
    /// Entries this version does not know: (key, raw CBOR value).
    pub unknown: Vec<(u64, Vec<u8>)>,
}

fn enc() -> Encoder<Vec<u8>> {
    Encoder::new(Vec::new())
}

fn cbor_bytes(b: &[u8]) -> Vec<u8> {
    let mut e = enc();
    e.bytes(b).expect("vec write");
    e.into_writer()
}

fn cbor_list(items: &[Vec<u8>]) -> Vec<u8> {
    let mut e = enc();
    e.array(items.len() as u64).expect("vec write");
    for i in items {
        e.bytes(i).expect("vec write");
    }
    e.into_writer()
}

fn cbor_u64(v: u64) -> Vec<u8> {
    let mut e = enc();
    e.u64(v).expect("vec write");
    e.into_writer()
}

impl Card {
    pub fn new(sk: UserId, ik: [u8; 32], otk: [u8; 32], hk: [u8; 32], kind: CardKind, exp: u64) -> Self {
        Card { v: CARD_VERSION, sk, ik, otk, hk, kind, name: None, addrs: Vec::new(), boot: Vec::new(), relays: Vec::new(), nostr: Vec::new(), relays2: Vec::new(), exp, sig: [0; 64], unknown: Vec::new() }
    }

    /// Every mailbox relay the card names, combined ones (SPEC 19.13) first: `RelayRef` bytes.
    pub fn all_relays(&self) -> Vec<Vec<u8>> {
        self.relays2.iter().chain(self.relays.iter()).cloned().collect()
    }

    pub fn loc(&self) -> Option<[u8; 32]> {
        match self.kind {
            CardKind::FaceToFace { loc } => Some(loc),
            CardKind::Remote { .. } => None,
        }
    }

    /// Every entry except `sig`, ascending by key.
    fn entries(&self) -> Vec<(u64, Vec<u8>)> {
        let mut out = vec![(K_V, cbor_u64(self.v)), (K_SK, cbor_bytes(&self.sk)), (K_IK, cbor_bytes(&self.ik)), (K_OTK, cbor_bytes(&self.otk))];
        if let CardKind::FaceToFace { loc } = &self.kind {
            out.push((K_LOC, cbor_bytes(loc)));
        }
        if let Some(name) = &self.name {
            let mut e = enc();
            e.str(name).expect("vec write");
            out.push((K_NAME, e.into_writer()));
        }
        if !self.addrs.is_empty() {
            out.push((K_ADDRS, cbor_list(&self.addrs)));
        }
        if !self.boot.is_empty() {
            out.push((K_BOOT, cbor_list(&self.boot)));
        }
        out.push((K_EXP, cbor_u64(self.exp)));
        if let CardKind::Remote { ticket, pake } = &self.kind {
            out.push((K_TICKET, cbor_bytes(ticket)));
            if *pake {
                let mut e = enc();
                e.bool(true).expect("vec write");
                out.push((K_PAKE, e.into_writer()));
            }
        }
        out.push((K_HK, cbor_bytes(&self.hk)));
        if !self.relays.is_empty() {
            out.push((K_RELAYS, cbor_list(&self.relays)));
        }
        if !self.nostr.is_empty() {
            out.push((K_NOSTR, cbor_list(&self.nostr)));
        }
        if !self.relays2.is_empty() {
            out.push((K_RELAYS2, cbor_list(&self.relays2)));
        }
        out.extend(self.unknown.iter().cloned());
        out.sort_by_key(|(k, _)| *k);
        out
    }

    fn encode_map(entries: &[(u64, Vec<u8>)]) -> Vec<u8> {
        let mut e = enc();
        e.map(entries.len() as u64).expect("vec write");
        let mut out = e.into_writer();
        for (k, raw) in entries {
            out.extend(cbor_u64(*k));
            out.extend_from_slice(raw);
        }
        out
    }

    /// The bytes the signature covers.
    pub fn signed_bytes(&self) -> Vec<u8> {
        Self::encode_map(&self.entries())
    }

    pub fn sign_with(&mut self, sign: impl FnOnce(&[u8]) -> [u8; 64]) -> Result<()> {
        self.check_shape()?;
        self.sig = sign(&self.signed_bytes());
        Ok(())
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut entries = self.entries();
        entries.push((K_SIG, cbor_bytes(&self.sig)));
        entries.sort_by_key(|(k, _)| *k);
        Self::encode_map(&entries)
    }

    pub fn to_link(&self) -> String {
        format!("{LINK_PREFIX}{}", b64url_encode(&self.to_bytes()))
    }

    fn check_shape(&self) -> Result<()> {
        if self.v != CARD_VERSION {
            return Err(Error::Card("unsupported card version"));
        }
        if let Some(n) = &self.name {
            if n.len() > MAX_NAME_BYTES {
                return Err(Error::Card("name too long"));
            }
        }
        if self.addrs.len() > MAX_ADDRS || self.addrs.iter().any(|a| a.len() > MAX_ADDR_BYTES) {
            return Err(Error::Card("too many or too long addresses"));
        }
        if self.boot.len() > MAX_BOOT || self.boot.iter().any(|a| a.len() > MAX_BOOT_BYTES) {
            return Err(Error::Card("too many or too long boot entries"));
        }
        if self.relays.len() > MAX_RELAYS || self.relays.iter().any(|r| r.is_empty() || r.len() > MAX_RELAY_BYTES) {
            return Err(Error::Card("too many or too long relays"));
        }
        if self.nostr.len() > MAX_NOSTR || self.nostr.iter().any(|r| r.is_empty() || r.len() > MAX_NOSTR_BYTES) {
            return Err(Error::Card("too many or too long nostr relays"));
        }
        if self.relays2.len() > MAX_RELAYS2 || self.relays2.iter().any(|r| r.is_empty() || r.len() > MAX_RELAY2_BYTES) {
            return Err(Error::Card("too many or too long combined relays"));
        }
        Ok(())
    }

    /// Parse and verify. Rejects anything that is not the deterministic encoding, so one card
    /// has exactly one byte form.
    pub fn from_bytes(bytes: &[u8]) -> Result<Card> {
        if bytes.len() > MAX_CARD_BYTES {
            return Err(Error::Card("card too large"));
        }
        let mut d = Decoder::new(bytes);
        let n = d.map().map_err(|_| Error::Card("not a CBOR map"))?.ok_or(Error::Card("indefinite map"))?;
        if n > 32 {
            return Err(Error::Card("too many entries"));
        }
        let mut raw: Vec<(u64, Vec<u8>)> = Vec::new();
        for _ in 0..n {
            let k = d.u64().map_err(|_| Error::Card("key is not an integer"))?;
            if raw.last().is_some_and(|(p, _)| *p >= k) {
                return Err(Error::Card("keys not ascending"));
            }
            let start = d.position();
            d.skip().map_err(|_| Error::Card("bad value"))?;
            raw.push((k, bytes[start..d.position()].to_vec()));
        }
        if d.position() != bytes.len() {
            return Err(Error::Card("trailing bytes"));
        }
        let get = |k: u64| raw.iter().find(|(key, _)| *key == k).map(|(_, v)| v.as_slice());
        let b32 = |k: u64, what: &'static str| -> Result<[u8; 32]> {
            let v = get(k).ok_or(Error::Card(what))?;
            Decoder::new(v).bytes().ok().and_then(array::<32>).ok_or(Error::Card(what))
        };
        let uint = |k: u64, what: &'static str| -> Result<u64> {
            Decoder::new(get(k).ok_or(Error::Card(what))?).u64().map_err(|_| Error::Card(what))
        };
        let list = |k: u64| -> Result<Vec<Vec<u8>>> {
            let Some(v) = get(k) else { return Ok(Vec::new()) };
            let mut d = Decoder::new(v);
            let n = d.array().map_err(|_| Error::Card("bad list"))?.ok_or(Error::Card("indefinite list"))?;
            if n > 16 {
                return Err(Error::Card("list too long"));
            }
            (0..n).map(|_| d.bytes().map(|b| b.to_vec()).map_err(|_| Error::Card("bad list item"))).collect()
        };

        let v = uint(K_V, "missing version")?;
        if v != CARD_VERSION {
            return Err(Error::Card("unsupported card version"));
        }
        let sk = b32(K_SK, "missing or bad sk")?;
        let ik = b32(K_IK, "missing or bad ik")?;
        let otk = b32(K_OTK, "missing or bad otk")?;
        let hk = b32(K_HK, "missing or bad hk")?;
        let exp = uint(K_EXP, "missing exp")?;
        let name = match get(K_NAME) {
            Some(v) => Some(Decoder::new(v).str().map_err(|_| Error::Card("bad name"))?.to_string()),
            None => None,
        };
        let loc = get(K_LOC).map(|_| b32(K_LOC, "bad loc")).transpose()?;
        let ticket = match get(K_TICKET) {
            Some(v) => Some(Decoder::new(v).bytes().ok().and_then(array::<16>).ok_or(Error::Card("bad ticket"))?),
            None => None,
        };
        let pake = match get(K_PAKE) {
            Some(v) => Decoder::new(v).bool().map_err(|_| Error::Card("bad pake"))?,
            None => false,
        };
        let kind = match (loc, ticket) {
            (Some(loc), None) if !pake => CardKind::FaceToFace { loc },
            (None, Some(ticket)) => CardKind::Remote { ticket, pake },
            _ => return Err(Error::Card("a card has either loc or ticket")),
        };
        let sig_raw = get(K_SIG).ok_or(Error::Card("missing sig"))?;
        let sig: [u8; 64] = Decoder::new(sig_raw).bytes().ok().and_then(array::<64>).ok_or(Error::Card("bad sig"))?;
        let known = [K_V, K_SK, K_IK, K_OTK, K_LOC, K_NAME, K_ADDRS, K_BOOT, K_EXP, K_TICKET, K_PAKE, K_HK, K_RELAYS, K_NOSTR, K_RELAYS2, K_SIG];
        let unknown = raw.iter().filter(|(k, _)| !known.contains(k)).cloned().collect();

        let card = Card { v, sk, ik, otk, hk, kind, name, addrs: list(K_ADDRS)?, boot: list(K_BOOT)?, relays: list(K_RELAYS)?, nostr: list(K_NOSTR)?, relays2: list(K_RELAYS2)?, exp, sig, unknown };
        card.check_shape()?;
        if card.to_bytes() != bytes {
            return Err(Error::Card("not in deterministic encoding"));
        }
        let pk = Ed25519PublicKey::from_slice(&card.sk).map_err(|_| Error::Card("bad sk"))?;
        let signature = Ed25519Signature::from_slice(&card.sig).map_err(|_| Error::Card("bad sig"))?;
        pk.verify(&card.signed_bytes(), &signature).map_err(|_| Error::Card("signature does not verify"))?;
        Ok(card)
    }

    pub fn from_link(link: &str) -> Result<Card> {
        let link = link.trim();
        if link.len() > 2 * MAX_CARD_BYTES || link.len() < LINK_PREFIX.len() || !link[..LINK_PREFIX.len()].eq_ignore_ascii_case(LINK_PREFIX) {
            return Err(Error::Link("not an mlmchat://c/ link"));
        }
        let bytes = b64url_decode(&link[LINK_PREFIX.len()..]).ok_or(Error::Link("bad base64url"))?;
        Card::from_bytes(&bytes)
    }

    pub fn is_expired(&self, now_s: u64) -> bool {
        now_s > self.exp
    }
}

/// The QR code of a link, drawn with half-block characters for a terminal.
pub fn qr_for_terminal(link: &str) -> Result<String> {
    let code = qrcode::QrCode::with_error_correction_level(link.as_bytes(), qrcode::EcLevel::M).map_err(|_| Error::Link("link too long for a QR code"))?;
    Ok(code.render::<qrcode::render::unicode::Dense1x2>().quiet_zone(true).build())
}

/// The QR modules (dark = true), row by row, for an app to draw itself.
pub fn qr_modules(link: &str) -> Result<(usize, Vec<bool>)> {
    let code = qrcode::QrCode::with_error_correction_level(link.as_bytes(), qrcode::EcLevel::M).map_err(|_| Error::Link("link too long for a QR code"))?;
    let width = code.width();
    Ok((width, code.to_colors().into_iter().map(|c| c == qrcode::Color::Dark).collect()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::Identity;

    #[test]
    fn face_to_face_card_round_trips_and_verifies() {
        let mut id = Identity::new("ali").unwrap();
        let card = id.face_to_face_card(vec![crate::addr::encode(&"192.168.1.5:4100".parse().unwrap())], Vec::new(), 3600).unwrap();
        let link = card.to_link();
        let back = Card::from_link(&link).unwrap();
        assert_eq!(back, card);
        assert_eq!(back.loc(), Some(id.loc()));
        assert!(qr_for_terminal(&link).unwrap().len() > 100);
    }

    #[test]
    fn remote_link_has_ticket_and_no_loc() {
        let mut id = Identity::new("ali").unwrap();
        let card = id.remote_link_card(true, 3600).unwrap();
        let back = Card::from_link(&card.to_link()).unwrap();
        assert_eq!(back.loc(), None);
        assert!(matches!(back.kind, CardKind::Remote { pake: true, .. }));
    }

    #[test]
    fn tampered_card_is_rejected() {
        let mut id = Identity::new("ali").unwrap();
        let mut bytes = id.face_to_face_card(Vec::new(), Vec::new(), 3600).unwrap().to_bytes();
        let i = bytes.len() / 2;
        bytes[i] ^= 1;
        assert!(Card::from_bytes(&bytes).is_err());
    }

    #[test]
    fn unknown_keys_survive_and_stay_signed() {
        let mut id = Identity::new("ali").unwrap();
        let mut card = Card::new(id.sk(), id.ik(), [7; 32], id.hk(), CardKind::FaceToFace { loc: id.loc() }, 99);
        card.unknown.push((20, cbor_u64(5)));
        card.sign_with(|m| id.sign(m)).unwrap();
        let back = Card::from_bytes(&card.to_bytes()).unwrap();
        assert_eq!(back.unknown, vec![(20, cbor_u64(5))]);
        let _ = &mut id;
    }
}
