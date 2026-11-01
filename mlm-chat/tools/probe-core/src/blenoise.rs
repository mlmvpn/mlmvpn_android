//! Noise for the Bluetooth test: `Noise_NKpsk0_25519_ChaChaPoly_BLAKE2s`.
//!
//! The card owner (GATT server) is the responder; the phone that scanned its card knows the
//! responder's static key and the pre-shared key. After two handshake messages every packet is a
//! transport message with its 8-byte nonce in front (Noise spec section 11.4, "out-of-order
//! transport messages"): a packet that repeats or goes back in nonce is refused.
//!
//! The Java side holds a numeric handle; the state stays here.

use crate::noise::{params, MAX_MSG, NK_PSK0};
use anyhow::{anyhow, bail, Result};
use snow::{Builder, HandshakeState, StatelessTransportState};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{LazyLock, Mutex};

enum State {
    Hs(Box<HandshakeState>),
    Ts { ts: Box<StatelessTransportState>, last_rx: Option<u64> },
    Gone,
}

static TABLE: LazyLock<Mutex<HashMap<u64, State>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT: AtomicU64 = AtomicU64::new(1);

fn put(s: State) -> u64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    TABLE.lock().unwrap().insert(id, s);
    id
}

pub fn initiator(remote_pk: &[u8], psk: &[u8]) -> Result<u64> {
    if remote_pk.len() != 32 || psk.len() != 32 {
        bail!("key sizes");
    }
    let hs = Builder::new(params(NK_PSK0)).remote_public_key(remote_pk).psk(0, psk).build_initiator()?;
    Ok(put(State::Hs(Box::new(hs))))
}

pub fn responder(local_sk: &[u8], psk: &[u8]) -> Result<u64> {
    let hs = Builder::new(params(NK_PSK0)).local_private_key(local_sk).psk(0, psk).build_responder()?;
    Ok(put(State::Hs(Box::new(hs))))
}

/// The responder side for the current session's card.
pub fn responder_for_session() -> Result<u64> {
    let s = crate::current_session().ok_or_else(|| anyhow!("no card yet"))?;
    responder(&s.ble_sk, &s.ble_psk)
}

/// The initiator side toward a scanned card.
pub fn initiator_for_card(card: &str) -> Result<u64> {
    let c = crate::card::PeerCard::decode(card)?;
    initiator(&crate::util::b64d(&c.bk)?, &crate::util::b64d(&c.bpsk)?)
}

fn finish_if_done(st: &mut State) -> Result<()> {
    if let State::Hs(hs) = st {
        if hs.is_handshake_finished() {
            let old = std::mem::replace(st, State::Gone);
            if let State::Hs(hs) = old {
                *st = State::Ts { ts: Box::new(hs.into_stateless_transport_mode()?), last_rx: None };
            }
        }
    }
    Ok(())
}

pub fn write_handshake(id: u64, payload: &[u8]) -> Result<Vec<u8>> {
    let mut t = TABLE.lock().unwrap();
    let st = t.get_mut(&id).ok_or_else(|| anyhow!("no such session"))?;
    let State::Hs(hs) = st else { bail!("handshake already done") };
    let mut buf = vec![0u8; MAX_MSG];
    let n = hs.write_message(payload, &mut buf)?;
    buf.truncate(n);
    finish_if_done(st)?;
    Ok(buf)
}

pub fn read_handshake(id: u64, msg: &[u8]) -> Result<Vec<u8>> {
    let mut t = TABLE.lock().unwrap();
    let st = t.get_mut(&id).ok_or_else(|| anyhow!("no such session"))?;
    let State::Hs(hs) = st else { bail!("handshake already done") };
    let mut buf = vec![0u8; MAX_MSG];
    let n = hs.read_message(msg, &mut buf)?;
    buf.truncate(n);
    finish_if_done(st)?;
    Ok(buf)
}

pub fn is_ready(id: u64) -> bool {
    matches!(TABLE.lock().unwrap().get(&id), Some(State::Ts { .. }))
}

/// nonce (8 bytes, big endian) || ciphertext
pub fn seal(id: u64, nonce: u64, pt: &[u8]) -> Result<Vec<u8>> {
    let t = TABLE.lock().unwrap();
    let Some(State::Ts { ts, .. }) = t.get(&id) else { bail!("not ready") };
    let mut buf = vec![0u8; 8 + pt.len() + 16];
    buf[..8].copy_from_slice(&nonce.to_be_bytes());
    let n = ts.write_message(nonce, pt, &mut buf[8..])?;
    buf.truncate(8 + n);
    Ok(buf)
}

pub fn open(id: u64, pkt: &[u8]) -> Result<Vec<u8>> {
    if pkt.len() < 8 + 16 {
        bail!("short packet");
    }
    let mut t = TABLE.lock().unwrap();
    let Some(State::Ts { ts, last_rx }) = t.get_mut(&id) else { bail!("not ready") };
    let nonce = u64::from_be_bytes(pkt[..8].try_into().unwrap());
    if let Some(l) = *last_rx {
        if nonce <= l {
            bail!("replayed or old packet");
        }
    }
    let mut out = vec![0u8; pkt.len()];
    let n = ts.read_message(nonce, &pkt[8..], &mut out)?;
    out.truncate(n);
    *last_rx = Some(nonce);
    Ok(out)
}

pub fn free(id: u64) {
    TABLE.lock().unwrap().remove(&id);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handshake_and_packets() {
        let (sk, pk) = crate::noise::keypair().unwrap();
        let psk = [7u8; 32];
        let i = initiator(&pk, &psk).unwrap();
        let r = responder(&sk, &psk).unwrap();
        let m1 = write_handshake(i, b"hi").unwrap();
        assert_eq!(read_handshake(r, &m1).unwrap(), b"hi");
        let m2 = write_handshake(r, b"").unwrap();
        read_handshake(i, &m2).unwrap();
        assert!(is_ready(i) && is_ready(r));
        let p = seal(i, 1, b"data").unwrap();
        assert_eq!(open(r, &p).unwrap(), b"data");
        assert!(open(r, &p).is_err(), "replay must fail");
        let wrong = responder(&sk, &[8u8; 32]).unwrap();
        let i2 = initiator(&pk, &psk).unwrap();
        let m1 = write_handshake(i2, b"").unwrap();
        assert!(read_handshake(wrong, &m1).is_err(), "wrong psk must fail");
    }
}
