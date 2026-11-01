//! Olm sessions (SPEC 4, T25): vodozemac 3DH + Double Ratchet with `SessionConfig::version_1`,
//! vodozemac's audited default (version 2 sits behind its "experimental" feature).
//!
//! Two people can start a session with each other at the same moment. Every session keeps
//! decrypting, but for sending both phones pick the same one (SPEC 14.4): a session created by
//! the user with the smaller `sk` wins; among those, the newest. A session the peer no longer
//! holds is left out first ([`LOST_AFTER_MS`]).

use crate::frame::{KIND_OLM, KIND_OLM_PREKEY};
use crate::identity::Identity;
use crate::store::SessionRow;
use crate::{Error, Result, UserId};
use vodozemac::olm::{OlmMessage, Session, SessionConfig, SessionPickle};
use vodozemac::Curve25519PublicKey;

pub const CONFIG: SessionConfig = SessionConfig::version_1();

/// A session in memory, with the row it was loaded from.
pub struct Live {
    pub row: SessionRow,
    pub session: Session,
    /// Changed since loading: must be saved.
    pub dirty: bool,
}

impl Live {
    /// The row to save, with the current ratchet state.
    pub fn to_row(&self) -> SessionRow {
        let mut row = self.row.clone();
        row.pickle = serde_json::to_vec(&self.session.pickle()).expect("session pickle serializes");
        row
    }
}

pub fn load(rows: Vec<SessionRow>) -> Result<Vec<Live>> {
    rows.into_iter()
        .map(|row| {
            let pickle: SessionPickle = serde_json::from_slice(&row.pickle).map_err(|_| Error::Session("saved session unreadable"))?;
            Ok(Live { session: Session::from_pickle(pickle), row, dirty: false })
        })
        .collect()
}

/// A phone starts a session only while it holds none with the peer. So a session the peer started
/// this long after the last sign that it held another (a message of its opened there, else that
/// session's start) means the other is gone from its phone (a new install, a restore): a message
/// sent there would never open. The margin keeps two people who start at the same moment on the
/// smaller `sk`'s session, as before (SPEC 14.4).
pub const LOST_AFTER_MS: u64 = 10 * 60 * 1000;

/// The session both sides send on (SPEC 14.4), among those the peer still holds.
pub fn winner(sessions: &[Live], me: &UserId, peer: &UserId) -> Option<usize> {
    let smaller_is_me = me < peer;
    let by_smaller = |l: &Live| l.row.by_me == smaller_is_me;
    let newest = |a: &(usize, &Live), b: &(usize, &Live)| (a.1.row.created_ms, &a.1.row.id).cmp(&(b.1.row.created_ms, &b.1.row.id));
    // every clock here is this phone's; the session the peer showed last is always kept
    let held = |l: &Live| l.row.rx_ms.max(l.row.created_ms);
    let theirs = sessions.iter().filter(|l| !l.row.by_me).map(held).max().unwrap_or(0);
    let kept: Vec<(usize, &Live)> = sessions.iter().enumerate().filter(|(_, l)| held(l) + LOST_AFTER_MS >= theirs).collect();
    kept
        .iter()
        .copied()
        .filter(|(_, l)| by_smaller(l))
        .max_by(newest)
        .or_else(|| kept.iter().copied().max_by(newest))
        .map(|(i, _)| i)
}

pub struct Encrypted {
    pub kind: u32,
    pub cipher: Vec<u8>,
}

/// Encrypt for `peer`; with no session yet, a new outbound one from their card's keys.
pub fn encrypt(identity: &Identity, sessions: &mut Vec<Live>, peer: &UserId, peer_ik: &[u8; 32], peer_otk: &[u8; 32], plaintext: &[u8], now_ms: u64) -> Result<Encrypted> {
    let index = match winner(sessions, &identity.sk(), peer) {
        Some(i) => i,
        None => {
            let session = identity
                .account()
                .create_outbound_session(CONFIG, Curve25519PublicKey::from_bytes(*peer_ik), Curve25519PublicKey::from_bytes(*peer_otk))
                .map_err(|_| Error::Session("could not start a session with the card's keys"))?;
            let row = SessionRow { id: session.session_id(), peer: *peer, pickle: Vec::new(), by_me: true, created_ms: now_ms, used_ms: now_ms, rx_ms: 0 };
            sessions.push(Live { row, session, dirty: true });
            sessions.len() - 1
        }
    };
    let live = &mut sessions[index];
    let message = live.session.encrypt(plaintext).map_err(|_| Error::Session("encrypt failed"))?;
    live.row.used_ms = now_ms;
    live.dirty = true;
    let (t, cipher) = message.to_parts();
    let kind = if t == 0 { KIND_OLM_PREKEY } else { KIND_OLM };
    Ok(Encrypted { kind, cipher })
}

pub struct Decrypted {
    pub plaintext: Vec<u8>,
    /// A new inbound session was made, so the account changed (a one-time key was used up).
    pub created: bool,
    /// The sender's identity key, from a PreKey message.
    pub sender_ik: Option<[u8; 32]>,
}

/// Decrypt one Olm frame from `peer`. A PreKey message goes to the session it belongs to, or
/// starts a new inbound session. `expected_ik` = the contact's identity key, if known.
pub fn decrypt(identity: &mut Identity, sessions: &mut Vec<Live>, peer: &UserId, expected_ik: Option<&[u8; 32]>, kind: u32, cipher: &[u8], now_ms: u64) -> Result<Decrypted> {
    let t = match kind {
        KIND_OLM_PREKEY => 0,
        KIND_OLM => 1,
        _ => return Err(Error::Session("not an Olm frame")),
    };
    let message = OlmMessage::from_parts(t, cipher).map_err(|_| Error::Session("bad Olm message"))?;
    match &message {
        OlmMessage::PreKey(pre) => {
            let sender_ik = pre.identity_key().to_bytes();
            if expected_ik.is_some_and(|ik| *ik != sender_ik) {
                return Err(Error::Session("identity key does not match the contact"));
            }
            let id = pre.session_id();
            if let Some(live) = sessions.iter_mut().find(|l| l.row.id == id) {
                let plaintext = live.session.decrypt(&message).map_err(|_| Error::Session("decrypt failed"))?;
                live.row.used_ms = now_ms;
                live.row.rx_ms = now_ms;
                live.dirty = true;
                return Ok(Decrypted { plaintext, created: false, sender_ik: Some(sender_ik) });
            }
            let created = identity
                .account_mut()
                .create_inbound_session(CONFIG, pre.identity_key(), pre)
                .map_err(|_| Error::Session("could not open the new session"))?;
            let row = SessionRow { id: created.session.session_id(), peer: *peer, pickle: Vec::new(), by_me: false, created_ms: now_ms, used_ms: now_ms, rx_ms: now_ms };
            sessions.push(Live { row, session: created.session, dirty: true });
            Ok(Decrypted { plaintext: created.plaintext, created: true, sender_ik: Some(sender_ik) })
        }
        OlmMessage::Normal(_) => {
            // newest-used first; a failed attempt changes nothing in vodozemac
            for live in sessions.iter_mut() {
                if let Ok(plaintext) = live.session.decrypt(&message) {
                    live.row.used_ms = now_ms;
                    live.row.rx_ms = now_ms;
                    live.dirty = true;
                    return Ok(Decrypted { plaintext, created: false, sender_ik: None });
                }
            }
            Err(Error::Session("no session decrypts this message"))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair() -> (Identity, Identity, [u8; 32]) {
        let a = Identity::new("a").unwrap();
        let mut b = Identity::new("b").unwrap();
        let card = b.face_to_face_card(Vec::new(), Vec::new(), 60).unwrap();
        (a, b, card.otk)
    }

    #[test]
    fn first_message_then_reply() {
        let (mut a, mut b, b_otk) = pair();
        let (mut sa, mut sb) = (Vec::new(), Vec::new());
        let e = encrypt(&a, &mut sa, &b.sk(), &b.ik(), &b_otk, b"salam", 1).unwrap();
        assert_eq!(e.kind, KIND_OLM_PREKEY);
        let d = decrypt(&mut b, &mut sb, &a.sk(), None, e.kind, &e.cipher, 2).unwrap();
        assert_eq!(d.plaintext, b"salam");
        assert!(d.created);
        assert_eq!(d.sender_ik, Some(a.ik()));
        // B answers on the same session; A gets a normal message
        let r = encrypt(&b, &mut sb, &a.sk(), &a.ik(), &[0; 32], b"aleyk", 3).unwrap();
        assert_eq!(r.kind, KIND_OLM);
        let d = decrypt(&mut a, &mut sa, &b.sk(), Some(&b.ik()), r.kind, &r.cipher, 4).unwrap();
        assert_eq!(d.plaintext, b"aleyk");
        // the saved form decrypts the next message too
        let mut reloaded = load(sa.iter().map(|l| l.to_row()).collect()).unwrap();
        let e2 = encrypt(&b, &mut sb, &a.sk(), &a.ik(), &[0; 32], b"again", 5).unwrap();
        assert_eq!(decrypt(&mut a, &mut reloaded, &b.sk(), None, e2.kind, &e2.cipher, 6).unwrap().plaintext, b"again");
    }

    #[test]
    fn simultaneous_sessions_converge_on_the_smaller_sk() {
        let mut a = Identity::new("a").unwrap();
        let mut b = Identity::new("b").unwrap();
        let a_otk = a.face_to_face_card(Vec::new(), Vec::new(), 60).unwrap().otk;
        let b_otk = b.face_to_face_card(Vec::new(), Vec::new(), 60).unwrap().otk;
        let (mut sa, mut sb) = (Vec::new(), Vec::new());
        let ea = encrypt(&a, &mut sa, &b.sk(), &b.ik(), &b_otk, b"from a", 10).unwrap();
        let eb = encrypt(&b, &mut sb, &a.sk(), &a.ik(), &a_otk, b"from b", 10).unwrap();
        decrypt(&mut b, &mut sb, &a.sk(), Some(&a.ik()), ea.kind, &ea.cipher, 11).unwrap();
        decrypt(&mut a, &mut sa, &b.sk(), Some(&b.ik()), eb.kind, &eb.cipher, 11).unwrap();
        let wa = winner(&sa, &a.sk(), &b.sk()).unwrap();
        let wb = winner(&sb, &b.sk(), &a.sk()).unwrap();
        assert_eq!(sa[wa].row.id, sb[wb].row.id);
        let smaller_made_it = if a.sk() < b.sk() { sa[wa].row.by_me } else { sb[wb].row.by_me };
        assert!(smaller_made_it);
    }

    /// a (the smaller `sk`) wrote first and b answered, on a's session, at 1-2 s; then b's phone
    /// lost every session with a (a new install, a restore). a's sessions and one-time key.
    fn talked_then_b_lost_it() -> (Identity, Identity, [u8; 32], Vec<Live>) {
        let mut a = Identity::new("a").unwrap();
        let mut b = Identity::new("b").unwrap();
        if b.sk() < a.sk() {
            std::mem::swap(&mut a, &mut b);
        }
        let a_otk = a.face_to_face_card(Vec::new(), Vec::new(), 60).unwrap().otk;
        let b_otk = b.face_to_face_card(Vec::new(), Vec::new(), 60).unwrap().otk;
        let (mut sa, mut sb) = (Vec::new(), Vec::new());
        let e = encrypt(&a, &mut sa, &b.sk(), &b.ik(), &b_otk, b"salam", 1_000).unwrap();
        decrypt(&mut b, &mut sb, &a.sk(), Some(&a.ik()), e.kind, &e.cipher, 1_001).unwrap();
        let r = encrypt(&b, &mut sb, &a.sk(), &a.ik(), &[0; 32], b"aleyk", 2_000).unwrap();
        decrypt(&mut a, &mut sa, &b.sk(), Some(&b.ik()), r.kind, &r.cipher, 2_001).unwrap();
        (a, b, a_otk, sa)
    }

    /// b starts again two days later. a used to keep sending on its own old session, which b can
    /// never open again (one tick for good); now a follows b's new one (SPEC 14.4).
    #[test]
    fn a_session_the_peer_lost_is_left() {
        let (mut a, mut b, a_otk, mut sa) = talked_then_b_lost_it();
        let old = sa[0].row.id.clone();
        let later = 2 * 24 * 3600 * 1000;
        let mut sb = Vec::new();
        let n = encrypt(&b, &mut sb, &a.sk(), &a.ik(), &a_otk, b"new phone", later).unwrap();
        assert!(decrypt(&mut a, &mut sa, &b.sk(), Some(&b.ik()), n.kind, &n.cipher, later + 1).unwrap().created);
        let w = winner(&sa, &a.sk(), &b.sk()).unwrap();
        assert_ne!(sa[w].row.id, old);
        assert_eq!(sa[w].row.id, sb[0].row.id);
        // the same from rows saved before `session_rx` was kept
        let legacy = load(sa.iter().map(|l| SessionRow { rx_ms: 0, ..l.to_row() }).collect()).unwrap();
        assert_eq!(legacy[winner(&legacy, &a.sk(), &b.sk()).unwrap()].row.id, sb[0].row.id);
        // a's answer opens on b's phone, and both stay on b's session
        let ans = encrypt(&a, &mut sa, &b.sk(), &b.ik(), &[0; 32], b"got it", later + 2).unwrap();
        assert_eq!(decrypt(&mut b, &mut sb, &a.sk(), Some(&a.ik()), ans.kind, &ans.cipher, later + 3).unwrap().plaintext, b"got it");
        assert_eq!(sb.len(), 1);
        let back = encrypt(&b, &mut sb, &a.sk(), &a.ik(), &a_otk, b"ok", later + 4).unwrap();
        assert_eq!(back.kind, KIND_OLM);
        decrypt(&mut a, &mut sa, &b.sk(), Some(&b.ik()), back.kind, &back.cipher, later + 5).unwrap();
        assert_eq!(sa[winner(&sa, &a.sk(), &b.sk()).unwrap()].row.id, sb[0].row.id);
    }

    /// b starts again 5 minutes after its answer: inside the margin that looks like two people
    /// starting at once, so a stays on its own session; b's next message, past the margin, shows
    /// the loss.
    #[test]
    fn a_loss_inside_the_margin_shows_at_the_next_message() {
        let (mut a, b, a_otk, mut sa) = talked_then_b_lost_it();
        let old = sa[0].row.id.clone();
        let minute = 60 * 1000;
        let mut sb = Vec::new();
        let n = encrypt(&b, &mut sb, &a.sk(), &a.ik(), &a_otk, b"new phone", 2_000 + 5 * minute).unwrap();
        decrypt(&mut a, &mut sa, &b.sk(), Some(&b.ik()), n.kind, &n.cipher, 2_001 + 5 * minute).unwrap();
        assert_eq!(sa[winner(&sa, &a.sk(), &b.sk()).unwrap()].row.id, old);
        let n = encrypt(&b, &mut sb, &a.sk(), &a.ik(), &a_otk, b"hello?", 2_000 + 11 * minute).unwrap();
        assert!(!decrypt(&mut a, &mut sa, &b.sk(), Some(&b.ik()), n.kind, &n.cipher, 2_001 + 11 * minute).unwrap().created);
        assert_eq!(sa[winner(&sa, &a.sk(), &b.sk()).unwrap()].row.id, sb[0].row.id);
    }

    #[test]
    fn wrong_identity_key_is_refused() {
        let (a, mut b, b_otk) = pair();
        let mut sa = Vec::new();
        let e = encrypt(&a, &mut sa, &b.sk(), &b.ik(), &b_otk, b"x", 1).unwrap();
        assert!(decrypt(&mut b, &mut Vec::new(), &a.sk(), Some(&[9; 32]), e.kind, &e.cipher, 2).is_err());
    }
}
