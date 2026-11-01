//! Identity made on the device (SPEC 2): a vodozemac Olm account (Ed25519 `sk`, Curve25519 `ik`),
//! a separate X25519 key for sealing (`hk`, SPEC 14.2), and the 32-byte locator `loc`.
//! No phone number, no e-mail: the key is the identity.

use crate::card::{Card, CardKind};
use crate::util::{now_s, random};
use crate::{Error, Result, UserId};
use hpke::kem::X25519HkdfSha256;
use hpke::{Kem, Serializable};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha512};
use vodozemac::olm::{Account, AccountPickle};
use zeroize::Zeroizing;

/// Iterations of the published Signal "safety number" construction (libsignal NumericFingerprint).
pub const SAFETY_ITERATIONS: usize = 5200;
/// Version bytes that start the fingerprint input, as in libsignal (version 0).
const SAFETY_VERSION: [u8; 2] = [0, 0];

/// The 30-digit half of a security number that belongs to one user (SPEC 14.1).
/// hash = version || sk || ik; then 5200 times hash = SHA-512(hash || sk); the first 30 bytes
/// become six 5-digit groups (each 5 bytes, big-endian, mod 100000).
pub fn personal_code(sk: &[u8; 32], ik: &[u8; 32]) -> String {
    let mut key = [0u8; 64];
    key[..32].copy_from_slice(sk);
    key[32..].copy_from_slice(ik);
    let codes = CODES.get_or_init(|| std::sync::Mutex::new(std::collections::HashMap::new()));
    if let Some(code) = codes.lock().expect("codes").get(&key) {
        return code.clone();
    }
    // worked out outside the lock; two threads asking at once for a new one both do the same work
    let code = work_out_code(sk, ik);
    let mut known = codes.lock().expect("codes");
    if known.len() >= CODES_MAX {
        known.clear();
    }
    known.insert(key, code.clone());
    code
}

/// Codes already worked out, by key pair. A code costs `SAFETY_ITERATIONS` rounds of SHA-512,
/// about 20 ms on a mid-range phone (A50), and the same ones are asked for again and again: our
/// own in every `Node::me`, each contact's in every list of contacts, each member's in every
/// group's state. Before this, the app's calls spent most of their time here, many of them
/// holding the node's lock (28 Oct 2026: a new message took 60 to 100 ms to show, a group's
/// member list 100 to 300 ms). A code is not secret: it is the digits shown beside a name.
static CODES: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<[u8; 64], String>>> = std::sync::OnceLock::new();
/// More different people than one phone ever meets; past it the memory starts over.
const CODES_MAX: usize = 4096;

fn work_out_code(sk: &[u8; 32], ik: &[u8; 32]) -> String {
    let mut hash: Vec<u8> = Vec::with_capacity(66);
    hash.extend_from_slice(&SAFETY_VERSION);
    hash.extend_from_slice(sk);
    hash.extend_from_slice(ik);
    for _ in 0..SAFETY_ITERATIONS {
        let mut d = Sha512::new();
        d.update(&hash);
        d.update(sk);
        hash = d.finalize().to_vec();
    }
    let mut out = String::with_capacity(30);
    for chunk in hash[..30].chunks(5) {
        let mut v: u64 = 0;
        for b in chunk {
            v = (v << 8) | u64::from(*b);
        }
        out.push_str(&format!("{:05}", v % 100_000));
    }
    out
}

/// The 60-digit security number two users compare (SPEC 2): both halves, the smaller first, so
/// both phones show the same digits.
pub fn security_number(a: &str, b: &str) -> String {
    if a <= b {
        format!("{a}{b}")
    } else {
        format!("{b}{a}")
    }
}

/// Groups of five digits, as shown on screen.
pub fn grouped(digits: &str) -> String {
    digits.as_bytes().chunks(5).map(|c| std::str::from_utf8(c).unwrap_or("")).collect::<Vec<_>>().join(" ")
}

/// Short display name (T14): chosen name, "·", and the first four digits of the user's own half.
pub fn display_name(name: &str, code: &str) -> String {
    format!("{}·{}", name, &code[..4])
}

fn check_name(name: &str) -> Result<()> {
    if name.is_empty() || name.len() > 64 {
        return Err(Error::Identity("name must be 1..64 bytes"));
    }
    Ok(())
}

/// What the store keeps for the identity. Lives only inside the encrypted database.
#[derive(Serialize, Deserialize)]
struct SavedIdentity {
    account: AccountPickle,
    seal_ikm: String,
    loc: String,
    name: String,
    created_s: u64,
    /// The public fallback key cards carry (hex). vodozemac only reports a fallback key until it
    /// is marked published, so it is kept here; older saves have none and make a new one.
    #[serde(default)]
    fallback: String,
}

pub struct Identity {
    account: Account,
    seal_ikm: Zeroizing<[u8; 32]>,
    loc: [u8; 32],
    name: String,
    created_s: u64,
    fallback: Option<[u8; 32]>,
    /// Mailbox relays every card made from now on names (SPEC 19.3); set by the node, not saved.
    relays: Vec<Vec<u8>>,
    /// Nostr relays every card made from now on names (SPEC 20); set by the node, not saved.
    nostr: Vec<Vec<u8>>,
}

impl Identity {
    pub fn new(name: &str) -> Result<Self> {
        check_name(name)?;
        Ok(Identity {
            account: Account::new(),
            seal_ikm: Zeroizing::new(random()),
            loc: random(),
            name: name.to_string(),
            created_s: now_s(),
            fallback: None,
            relays: Vec::new(),
            nostr: Vec::new(),
        })
    }

    pub fn sk(&self) -> UserId {
        *self.account.ed25519_key().as_bytes()
    }

    pub fn ik(&self) -> [u8; 32] {
        self.account.curve25519_key().to_bytes()
    }

    /// Public half of the sealing key (card key 11).
    pub fn hk(&self) -> [u8; 32] {
        let (_, pk) = X25519HkdfSha256::derive_keypair(&self.seal_ikm[..]);
        pk.to_bytes().into()
    }

    /// Private half of the sealing key, for opening SealedFrames addressed to us.
    pub fn seal_secret(&self) -> <X25519HkdfSha256 as Kem>::PrivateKey {
        X25519HkdfSha256::derive_keypair(&self.seal_ikm[..]).0
    }

    pub fn loc(&self) -> [u8; 32] {
        self.loc
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    /// Changes the chosen name (T14). The four digits after it stay: they come from the keys.
    /// An app makes the identity first and names it last, so the user sees their four digits
    /// while still choosing the name.
    pub fn set_name(&mut self, name: &str) -> Result<()> {
        check_name(name)?;
        self.name = name.to_string();
        Ok(())
    }

    pub fn code(&self) -> String {
        personal_code(&self.sk(), &self.ik())
    }

    pub fn display_name(&self) -> String {
        display_name(&self.name, &self.code())
    }

    pub fn sign(&self, message: &[u8]) -> [u8; 64] {
        self.account.sign(message).to_bytes()
    }

    pub fn account(&self) -> &Account {
        &self.account
    }

    pub fn account_mut(&mut self) -> &mut Account {
        &mut self.account
    }

    /// "Start over with everyone" (SPEC 2): a new locator. The caller sends it to kept contacts.
    pub fn rotate_loc(&mut self) {
        self.loc = random();
    }

    /// A fresh one-time key for a face-to-face card.
    fn fresh_one_time_key(&mut self) -> [u8; 32] {
        let result = self.account.generate_one_time_keys(1);
        self.account.mark_keys_as_published();
        result.created[0].to_bytes()
    }

    /// The fallback key, for links given to several people, cards inside KeyUpdate and the cards in
    /// a group's log. Made once and remembered: vodozemac reports a fallback key only until it is
    /// marked published (before 24 Oct 2026 this returned zeros, so those cards' keys were unusable).
    fn fallback_key(&mut self) -> [u8; 32] {
        if let Some(k) = self.fallback {
            return k;
        }
        self.account.generate_fallback_key();
        let k = self.account.fallback_key().values().next().map(|k| k.to_bytes()).expect("a fallback key was just generated");
        self.account.mark_keys_as_published();
        self.fallback = Some(k);
        k
    }

    /// Card shown as a QR code face to face (SPEC 3): carries `loc`, a one-time key.
    pub fn face_to_face_card(&mut self, addrs: Vec<Vec<u8>>, boot: Vec<Vec<u8>>, ttl_s: u64) -> Result<Card> {
        let otk = self.fresh_one_time_key();
        self.card(CardKind::FaceToFace { loc: self.loc }, otk, addrs, boot, ttl_s)
    }

    /// Card sent inside an Olm session (KeyUpdate): carries `loc`, the fallback key.
    pub fn session_card(&mut self, addrs: Vec<Vec<u8>>, boot: Vec<Vec<u8>>, ttl_s: u64) -> Result<Card> {
        let fk = self.fallback_key();
        self.card(CardKind::FaceToFace { loc: self.loc }, fk, addrs, boot, ttl_s)
    }

    /// Remote link for an unsafe channel (SPEC 3): no `loc`, a one-time ticket, fallback key.
    pub fn remote_link_card(&mut self, pake: bool, ttl_s: u64) -> Result<Card> {
        let fk = self.fallback_key();
        self.card(CardKind::Remote { ticket: random(), pake }, fk, Vec::new(), Vec::new(), ttl_s)
    }

    /// The mailbox relays every card made from now on names (SPEC 19.3), best first: simple ones
    /// (`RelayRef` form 1, card key 12) and combined ones (form 2, key 14, SPEC 19.13), each kind
    /// up to its own limit.
    pub fn set_relays(&mut self, relays: Vec<Vec<u8>>) {
        let (mut simple, mut combined) = (0, 0);
        self.relays = relays
            .into_iter()
            .filter(|r| match r.first() {
                Some(1) if r.len() <= crate::card::MAX_RELAY_BYTES && simple < crate::card::MAX_RELAYS => {
                    simple += 1;
                    true
                }
                Some(2) if r.len() <= crate::card::MAX_RELAY2_BYTES && combined < crate::card::MAX_RELAYS2 => {
                    combined += 1;
                    true
                }
                _ => false,
            })
            .collect();
    }

    pub fn relays(&self) -> &[Vec<u8>] {
        &self.relays
    }

    /// The Nostr relays (names without `wss://`) every card made from now on names (SPEC 20).
    pub fn set_nostr(&mut self, mut names: Vec<Vec<u8>>) {
        names.retain(|r| !r.is_empty() && r.len() <= crate::card::MAX_NOSTR_BYTES);
        names.truncate(crate::card::MAX_NOSTR);
        self.nostr = names;
    }

    pub fn nostr(&self) -> &[Vec<u8>] {
        &self.nostr
    }

    /// `boot`: volunteers this device uses (SPEC 3), at most `card::MAX_BOOT`. The mailbox relays go
    /// in too, and the Nostr relays. When the card would pass `MAX_CARD_BYTES`, the volunteers give
    /// way first (down to one), then the addresses (down to two), the Nostr relays (down to two),
    /// the mailbox relays last of all (simple ones before combined ones): they are the only way
    /// between two operators, and a card that dropped them first left a phone with many addresses
    /// unreachable through its own relay (Ehsan and a tester, 25 Oct 2026).
    fn card(&self, kind: CardKind, otk: [u8; 32], addrs: Vec<Vec<u8>>, mut boot: Vec<Vec<u8>>, ttl_s: u64) -> Result<Card> {
        let mut card = Card::new(self.sk(), self.ik(), otk, self.hk(), kind, now_s() + ttl_s);
        card.name = Some(self.name.clone());
        card.addrs = addrs;
        boot.truncate(crate::card::MAX_BOOT);
        card.boot = boot;
        if !matches!(card.kind, CardKind::Remote { .. }) {
            card.relays = self.relays.iter().filter(|r| r.first() == Some(&1)).cloned().collect();
            card.relays2 = self.relays.iter().filter(|r| r.first() == Some(&2)).cloned().collect();
            card.nostr = self.nostr.clone();
        }
        let fits = |c: &Card| c.to_bytes().len() <= crate::card::MAX_CARD_BYTES;
        while !fits(&card) && card.boot.len() > 1 {
            card.boot.pop();
        }
        while !fits(&card) && card.addrs.len() > 2 {
            card.addrs.pop();
        }
        while !fits(&card) && card.nostr.len() > 2 {
            card.nostr.pop();
        }
        while !fits(&card) && !card.relays.is_empty() {
            card.relays.pop();
        }
        while !fits(&card) && !card.relays2.is_empty() {
            card.relays2.pop();
        }
        while !fits(&card) && !card.nostr.is_empty() {
            card.nostr.pop();
        }
        while !fits(&card) && !card.boot.is_empty() {
            card.boot.pop();
        }
        while !fits(&card) && !card.addrs.is_empty() {
            card.addrs.pop();
        }
        card.sign_with(|m| self.sign(m))?;
        Ok(card)
    }

    pub fn to_saved(&self) -> Vec<u8> {
        let saved = SavedIdentity {
            account: self.account.pickle(),
            seal_ikm: hex::encode(&self.seal_ikm[..]),
            loc: hex::encode(self.loc),
            name: self.name.clone(),
            created_s: self.created_s,
            fallback: self.fallback.map(hex::encode).unwrap_or_default(),
        };
        serde_json::to_vec(&saved).expect("identity serializes")
    }

    pub fn from_saved(bytes: &[u8]) -> Result<Self> {
        let saved: SavedIdentity = serde_json::from_slice(bytes).map_err(|_| Error::Identity("saved identity unreadable"))?;
        let seal_ikm = hex::decode(&saved.seal_ikm).ok().and_then(|b| crate::util::array::<32>(&b));
        let loc = hex::decode(&saved.loc).ok().and_then(|b| crate::util::array::<32>(&b));
        match (seal_ikm, loc) {
            (Some(s), Some(l)) => Ok(Identity {
                account: Account::from_pickle(saved.account),
                seal_ikm: Zeroizing::new(s),
                loc: l,
                name: saved.name,
                created_s: saved.created_s,
                fallback: hex::decode(&saved.fallback).ok().and_then(|b| crate::util::array::<32>(&b)),
                relays: Vec::new(),
                nostr: Vec::new(),
            }),
            _ => Err(Error::Identity("saved identity unreadable")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_fallback_key_is_real_and_kept() {
        let mut id = Identity::new("ali").unwrap();
        let k = id.fallback_key();
        assert_ne!(k, [0u8; 32], "a usable key, not zeros");
        assert_eq!(id.fallback_key(), k, "the same key every time");
        let back = Identity::from_saved(&id.to_saved()).unwrap();
        assert_eq!(back.fallback, Some(k), "kept across a save");
        // someone else can start a session with it
        let other = Identity::new("sara").unwrap();
        assert!(other
            .account()
            .create_outbound_session(crate::olm::CONFIG, vodozemac::Curve25519PublicKey::from_bytes(id.ik()), vodozemac::Curve25519PublicKey::from_bytes(k))
            .is_ok());
    }

    #[test]
    fn code_is_30_digits_and_stable() {
        let c = personal_code(&[1; 32], &[2; 32]);
        assert_eq!(c.len(), 30);
        assert!(c.bytes().all(|b| b.is_ascii_digit()));
        assert_eq!(c, personal_code(&[1; 32], &[2; 32]));
        assert_ne!(c, personal_code(&[1; 32], &[3; 32]));
    }

    #[test]
    fn security_number_is_symmetric() {
        let a = personal_code(&[1; 32], &[2; 32]);
        let b = personal_code(&[3; 32], &[4; 32]);
        assert_eq!(security_number(&a, &b), security_number(&b, &a));
        assert_eq!(security_number(&a, &b).len(), 60);
    }

    #[test]
    fn identity_round_trips_through_saved_form() {
        let mut id = Identity::new("ehsan").unwrap();
        let card = id.face_to_face_card(Vec::new(), Vec::new(), 60).unwrap();
        let back = Identity::from_saved(&id.to_saved()).unwrap();
        assert_eq!(back.sk(), id.sk());
        assert_eq!(back.ik(), id.ik());
        assert_eq!(back.hk(), id.hk());
        assert_eq!(back.loc(), id.loc());
        assert_eq!(card.sk, id.sk());
        assert!(back.display_name().starts_with("ehsan·"));
    }

    #[test]
    fn renaming_keeps_the_four_digits() {
        let mut id = Identity::new("-").unwrap();
        let code = id.code();
        id.set_name("احسان").unwrap();
        assert_eq!(id.code(), code);
        assert_eq!(id.display_name(), format!("احسان·{}", &code[..4]));
        assert!(id.set_name("").is_err());
        assert!(id.set_name(&"x".repeat(65)).is_err());
        assert_eq!(id.name(), "احسان");
    }
}
