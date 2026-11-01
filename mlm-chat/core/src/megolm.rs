//! Megolm group sessions (SPEC 7.3, T25): vodozemac with `SessionConfig::version_1`.
//!
//! Each member has one outbound session per group; its session key goes to every other member in
//! a `KeyShare` inside Olm, so only members can read. A Megolm message carries no session id, so
//! a group frame's cipher is `session id length (u8) || session id || Megolm message`. The
//! session is replaced after a member leaves or is removed, and after `ROTATE_AFTER` messages or
//! `ROTATE_MS` (SPEC 7.3).

use crate::{Error, Result};
use vodozemac::megolm::{GroupSession, GroupSessionPickle, InboundGroupSession, InboundGroupSessionPickle, MegolmMessage, SessionConfig, SessionKey};

pub const CONFIG: SessionConfig = SessionConfig::version_1();
pub const ROTATE_AFTER: u32 = 100;
pub const ROTATE_MS: u64 = 7 * 24 * 3600 * 1000;

pub fn new_outbound() -> GroupSession {
    GroupSession::new(CONFIG)
}

pub fn pickle_out(s: &GroupSession) -> Vec<u8> {
    serde_json::to_vec(&s.pickle()).expect("group session pickle serializes")
}

pub fn load_out(bytes: &[u8]) -> Result<GroupSession> {
    let p: GroupSessionPickle = serde_json::from_slice(bytes).map_err(|_| Error::Session("saved group session unreadable"))?;
    Ok(GroupSession::from_pickle(p))
}

pub fn pickle_in(s: &InboundGroupSession) -> Vec<u8> {
    serde_json::to_vec(&s.pickle()).expect("inbound group session pickle serializes")
}

pub fn load_in(bytes: &[u8]) -> Result<InboundGroupSession> {
    let p: InboundGroupSessionPickle = serde_json::from_slice(bytes).map_err(|_| Error::Session("saved group session unreadable"))?;
    Ok(InboundGroupSession::from_pickle(p))
}

/// The key to share with the members (inside Olm only).
pub fn session_key(s: &GroupSession) -> Vec<u8> {
    s.session_key().to_bytes()
}

/// The inbound side of a shared key: (session id, session).
pub fn inbound(key: &[u8]) -> Result<(String, InboundGroupSession)> {
    let k = SessionKey::from_bytes(key).map_err(|_| Error::Session("bad group session key"))?;
    let s = InboundGroupSession::new(&k, CONFIG);
    Ok((s.session_id(), s))
}

/// Encrypt for the group: the frame cipher (session id with the message).
pub fn encrypt(s: &mut GroupSession, plaintext: &[u8]) -> Vec<u8> {
    let sid = s.session_id();
    let msg = s.encrypt(plaintext).to_bytes();
    let mut out = Vec::with_capacity(1 + sid.len() + msg.len());
    out.push(sid.len() as u8);
    out.extend_from_slice(sid.as_bytes());
    out.extend_from_slice(&msg);
    out
}

/// The session id a group frame cipher names, and its Megolm message.
pub fn split(cipher: &[u8]) -> Result<(String, MegolmMessage)> {
    let n = usize::from(*cipher.first().ok_or(Error::Frame("empty group cipher"))?);
    let sid = cipher.get(1..1 + n).and_then(|b| std::str::from_utf8(b).ok()).ok_or(Error::Frame("bad group session id"))?;
    let msg = MegolmMessage::from_bytes(&cipher[1 + n..]).map_err(|_| Error::Frame("bad group message"))?;
    Ok((sid.to_string(), msg))
}

/// Decrypt with the inbound session: the plaintext and the message index (to refuse replays).
pub fn decrypt(s: &mut InboundGroupSession, msg: &MegolmMessage) -> Result<(Vec<u8>, u32)> {
    let d = s.decrypt(msg).map_err(|_| Error::Session("group message does not decrypt"))?;
    Ok((d.plaintext, d.message_index))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn share_encrypt_decrypt_and_pickle() {
        let mut out = new_outbound();
        let (sid, mut inb) = inbound(&session_key(&out)).unwrap();
        let c = encrypt(&mut out, b"salam be hame");
        let (sid2, msg) = split(&c).unwrap();
        assert_eq!(sid, sid2);
        let (plain, idx) = decrypt(&mut inb, &msg).unwrap();
        assert_eq!((plain.as_slice(), idx), (&b"salam be hame"[..], 0));
        // pickles round-trip and keep the ratchet where it was
        let mut out = load_out(&pickle_out(&out)).unwrap();
        let mut inb = load_in(&pickle_in(&inb)).unwrap();
        let (_, msg) = split(&encrypt(&mut out, b"dovom")).unwrap();
        assert_eq!(decrypt(&mut inb, &msg).unwrap().1, 1);
        // another session's key does not open it
        let (_, mut stranger) = inbound(&session_key(&new_outbound())).unwrap();
        assert!(decrypt(&mut stranger, &msg).is_err());
    }
}
