//! The SPEC 14.7 session, whatever carries its bytes.
//!
//! Every session is `Noise_NNpsk0_25519_ChaChaPoly_SHA256` (snow). The pre-shared key is
//! HKDF-SHA256 of the responder's locator, so someone who does not hold it (a stranger, a
//! replayed beacon, a volunteer relay in the middle) cannot even finish the handshake. Then each
//! side proves its identity with an Ed25519 signature over the Noise handshake hash; the
//! responder answers only an initiator it accepts, so it reveals nothing to anyone else. Frames
//! travel one per record, each acknowledged (`/mlmchat/msg/1`: one frame, the answer = delivery
//! acknowledgement).
//!
//! The direct transport runs it on a TCP connection; the p2p transport runs the very same bytes
//! on a libp2p stream, directly or through a volunteer's relay circuit (SPEC 15). A relay only
//! ever sees this session's ciphertext.

use super::{Hooks, PeerInfo, TransportError, Verdict};
use crate::frame::BUCKETS;
use crate::UserId;
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use snow::{HandshakeState, TransportState};
use std::io::{ErrorKind, Read, Write};
use vodozemac::{Ed25519PublicKey, Ed25519Signature};

pub const NOISE_PARAMS: &str = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";
pub const PROLOGUE: &[u8] = b"mlmchat/direct/1";
pub const PSK_INFO: &[u8] = b"mlmchat/direct/1/psk";
pub const HELLO_CONTEXT: &[u8] = b"mlmchat/direct/1/hello";

pub const MSG_HELLO: u8 = 1;
pub const MSG_FRAME: u8 = 2;
pub const MSG_ACK: u8 = 3;
pub const MSG_BYE: u8 = 4;
pub const ROLE_INITIATOR: u8 = b'I';
pub const ROLE_RESPONDER: u8 = b'R';
const ACK_STORED: u8 = 0;
const ACK_DROPPED: u8 = 1;
const HELLO_LEN: usize = 1 + 32 + 64;
const ACK_LEN: usize = 1 + 16 + 1;
/// Plaintext per Noise message: 65535 minus the 16-byte tag, minus our 1-byte "more" flag.
const CHUNK: usize = 65535 - 16 - 1;
/// Largest record: a 64 KiB frame plus its type byte.
const MAX_RECORD: usize = 65536 + 1;

/// Anything a session can run on: a TCP connection, a libp2p stream.
pub trait Pipe: Read + Write + Send {}
impl<T: Read + Write + Send> Pipe for T {}

/// The connection's pre-shared key, from the responder's locator.
pub fn psk(loc: &[u8; 32]) -> [u8; 32] {
    let mut out = [0u8; 32];
    Hkdf::<Sha256>::new(None, loc).expand(PSK_INFO, &mut out).expect("32 bytes is a valid HKDF length");
    out
}

/// What each side signs: context || role || handshake hash. The role byte stops a signature
/// being reflected back as the other side's.
pub fn hello_input(role: u8, handshake_hash: &[u8]) -> Vec<u8> {
    let mut m = Vec::with_capacity(HELLO_CONTEXT.len() + 1 + handshake_hash.len());
    m.extend_from_slice(HELLO_CONTEXT);
    m.push(role);
    m.extend_from_slice(handshake_hash);
    m
}

/// The id a frame is acknowledged by: the first 16 bytes of its SHA-256.
pub fn frame_id(frame: &[u8]) -> [u8; 16] {
    let d = Sha256::digest(frame);
    let mut out = [0u8; 16];
    out.copy_from_slice(&d[..16]);
    out
}

/// Initiator side: handshake and hellos. The answer must come from `peer.sk`, or `WrongPeer`.
pub fn initiate(pipe: Box<dyn Pipe>, hooks: &dyn Hooks, peer: &PeerInfo) -> Result<Conn, TransportError> {
    let key = psk(&peer.loc);
    let hs = noise_builder(&key)?.build_initiator().map_err(|_| TransportError::Handshake)?;
    let mut conn = Conn::new(pipe);
    let hh = conn.initiator_handshake(hs)?;

    let (me, _) = hooks.me();
    let sig = hooks.sign(&hello_input(ROLE_INITIATOR, &hh));
    conn.send_record(&hello_record(&me, &sig))?;
    let rec = conn.recv_record().map_err(|e| if e == TransportError::Connect { TransportError::Refused } else { e })?;
    let (sk, sig) = parse_hello(&rec).ok_or(TransportError::Protocol("bad hello"))?;
    if sk != peer.sk || !verify(&sk, &hello_input(ROLE_RESPONDER, &hh), &sig) {
        return Err(TransportError::WrongPeer);
    }
    Ok(conn)
}

/// Frames in order, each acknowledged; then goodbye. `Err` only when none got through.
pub fn send_frames(conn: &mut Conn, frames: &[Vec<u8>]) -> Result<Vec<Verdict>, TransportError> {
    let mut verdicts = Vec::with_capacity(frames.len());
    for f in frames {
        let id = frame_id(f);
        let one = (|| {
            let mut rec = Vec::with_capacity(1 + f.len());
            rec.push(MSG_FRAME);
            rec.extend_from_slice(f);
            conn.send_record(&rec)?;
            let ack = conn.recv_record()?;
            if ack.len() != ACK_LEN || ack[0] != MSG_ACK || ack[1..17] != id {
                return Err(TransportError::Protocol("bad acknowledgement"));
            }
            Ok(if ack[17] == ACK_STORED { Verdict::Stored } else { Verdict::Dropped })
        })();
        match one {
            Ok(v) => verdicts.push(v),
            Err(e) if verdicts.is_empty() => return Err(e),
            Err(_) => break,
        }
    }
    let _ = conn.send_record(&[MSG_BYE]);
    Ok(verdicts)
}

/// Responder side: handshake with our locator, hellos, then frames until goodbye. `on_hello`
/// learns who proved themselves, before any frame (a contact that reaches us is reachable).
pub fn respond(pipe: Box<dyn Pipe>, hooks: &dyn Hooks, stopped: &dyn Fn() -> bool, on_hello: &mut dyn FnMut(UserId)) -> Result<(), TransportError> {
    let (_, loc) = hooks.me();
    let key = psk(&loc);
    let hs = noise_builder(&key)?.build_responder().map_err(|_| TransportError::Handshake)?;
    let mut conn = Conn::new(pipe);
    let hh = conn.responder_handshake(hs)?;

    let rec = conn.recv_record()?;
    let (sk, sig) = parse_hello(&rec).ok_or(TransportError::Protocol("bad hello"))?;
    if !verify(&sk, &hello_input(ROLE_INITIATOR, &hh), &sig) {
        return Err(TransportError::WrongPeer);
    }
    if !hooks.accept_peer(&sk) {
        return Err(TransportError::Refused);
    }
    let (me, _) = hooks.me();
    let my_sig = hooks.sign(&hello_input(ROLE_RESPONDER, &hh));
    conn.send_record(&hello_record(&me, &my_sig))?;
    on_hello(sk);

    loop {
        if stopped() {
            return Err(TransportError::Stopped);
        }
        let rec = match conn.recv_record() {
            Ok(r) => r,
            Err(TransportError::Connect) => return Ok(()), // the other side closed
            Err(e) => return Err(e),
        };
        match rec.first() {
            Some(&MSG_FRAME) if BUCKETS.contains(&(rec.len() - 1)) => {
                let frame = rec[1..].to_vec();
                let id = frame_id(&frame);
                let status = match hooks.on_frame(sk, frame) {
                    Verdict::Stored => ACK_STORED,
                    Verdict::Dropped => ACK_DROPPED,
                    Verdict::Retry => return Ok(()),
                };
                let mut ack = Vec::with_capacity(ACK_LEN);
                ack.push(MSG_ACK);
                ack.extend_from_slice(&id);
                ack.push(status);
                conn.send_record(&ack)?;
            }
            Some(&MSG_BYE) => return Ok(()),
            _ => return Err(TransportError::Protocol("unexpected record")),
        }
    }
}

/// How to rank two failure reasons: keep the most telling one (a wrong peer or a failed
/// handshake says more than a timeout).
pub fn rank(e: &TransportError) -> u8 {
    match e {
        TransportError::NoRoute => 0,
        TransportError::Connect => 1,
        TransportError::Timeout => 2,
        TransportError::Refused => 3,
        TransportError::Protocol(_) => 4,
        TransportError::Handshake => 5,
        TransportError::WrongPeer => 6,
        TransportError::Stopped => 7,
    }
}

fn noise_builder(key: &[u8; 32]) -> Result<snow::Builder<'_>, TransportError> {
    let params = NOISE_PARAMS.parse().map_err(|_| TransportError::Handshake)?;
    snow::Builder::new(params).prologue(PROLOGUE).and_then(|b| b.psk(0, key)).map_err(|_| TransportError::Handshake)
}

fn hello_record(sk: &UserId, sig: &[u8; 64]) -> Vec<u8> {
    let mut r = Vec::with_capacity(HELLO_LEN);
    r.push(MSG_HELLO);
    r.extend_from_slice(sk);
    r.extend_from_slice(sig);
    r
}

fn parse_hello(rec: &[u8]) -> Option<(UserId, [u8; 64])> {
    if rec.len() != HELLO_LEN || rec[0] != MSG_HELLO {
        return None;
    }
    Some((rec[1..33].try_into().ok()?, rec[33..97].try_into().ok()?))
}

/// An Ed25519 signature by `sk`.
pub fn verify(sk: &UserId, message: &[u8], sig: &[u8; 64]) -> bool {
    match (Ed25519PublicKey::from_slice(sk), Ed25519Signature::from_slice(sig)) {
        (Ok(pk), Ok(s)) => pk.verify(message, &s).is_ok(),
        _ => false,
    }
}

fn io_error(e: std::io::Error) -> TransportError {
    match e.kind() {
        ErrorKind::TimedOut | ErrorKind::WouldBlock => TransportError::Timeout,
        _ => TransportError::Connect,
    }
}

/// One encrypted session: Noise messages with a 2-byte big-endian length on the pipe.
pub struct Conn {
    pipe: Box<dyn Pipe>,
    noise: Option<TransportState>,
    buf: Vec<u8>,
}

impl Conn {
    fn new(pipe: Box<dyn Pipe>) -> Conn {
        Conn { pipe, noise: None, buf: vec![0u8; 65535] }
    }

    fn write_lp(&mut self, n: usize) -> Result<(), TransportError> {
        let mut out = Vec::with_capacity(2 + n);
        out.extend_from_slice(&(n as u16).to_be_bytes());
        out.extend_from_slice(&self.buf[..n]);
        self.pipe.write_all(&out).map_err(io_error)?;
        self.pipe.flush().map_err(io_error)
    }

    fn read_lp(&mut self) -> Result<Vec<u8>, TransportError> {
        let mut len = [0u8; 2];
        self.pipe.read_exact(&mut len).map_err(io_error)?;
        let mut msg = vec![0u8; u16::from_be_bytes(len) as usize];
        self.pipe.read_exact(&mut msg).map_err(io_error)?;
        Ok(msg)
    }

    /// -> e ; <- e, ee (the PSK is mixed in before the first message).
    fn initiator_handshake(&mut self, mut hs: HandshakeState) -> Result<Vec<u8>, TransportError> {
        let n = hs.write_message(&[], &mut self.buf).map_err(|_| TransportError::Handshake)?;
        self.write_lp(n)?;
        let msg = self.read_lp().map_err(|e| if e == TransportError::Connect { TransportError::Handshake } else { e })?;
        let mut scratch = [0u8; 64];
        hs.read_message(&msg, &mut scratch).map_err(|_| TransportError::Handshake)?;
        self.finish(hs)
    }

    fn responder_handshake(&mut self, mut hs: HandshakeState) -> Result<Vec<u8>, TransportError> {
        let msg = self.read_lp()?;
        let mut scratch = [0u8; 64];
        hs.read_message(&msg, &mut scratch).map_err(|_| TransportError::Handshake)?;
        let n = hs.write_message(&[], &mut self.buf).map_err(|_| TransportError::Handshake)?;
        self.write_lp(n)?;
        self.finish(hs)
    }

    fn finish(&mut self, hs: HandshakeState) -> Result<Vec<u8>, TransportError> {
        if !hs.is_handshake_finished() {
            return Err(TransportError::Handshake);
        }
        let hh = hs.get_handshake_hash().to_vec();
        self.noise = Some(hs.into_transport_mode().map_err(|_| TransportError::Handshake)?);
        Ok(hh)
    }

    /// A record: one or more Noise messages, each `more-flag || chunk`.
    fn send_record(&mut self, data: &[u8]) -> Result<(), TransportError> {
        let chunks: Vec<&[u8]> = if data.is_empty() { vec![&[][..]] } else { data.chunks(CHUNK).collect() };
        let last = chunks.len() - 1;
        for (i, chunk) in chunks.into_iter().enumerate() {
            let mut plain = Vec::with_capacity(1 + chunk.len());
            plain.push(u8::from(i != last));
            plain.extend_from_slice(chunk);
            let noise = self.noise.as_mut().ok_or(TransportError::Handshake)?;
            let n = noise.write_message(&plain, &mut self.buf).map_err(|_| TransportError::Protocol("encrypt"))?;
            self.write_lp(n)?;
        }
        Ok(())
    }

    fn recv_record(&mut self) -> Result<Vec<u8>, TransportError> {
        let mut out = Vec::new();
        loop {
            let msg = self.read_lp()?;
            let noise = self.noise.as_mut().ok_or(TransportError::Handshake)?;
            let n = noise.read_message(&msg, &mut self.buf).map_err(|_| TransportError::Protocol("decrypt"))?;
            if n == 0 {
                return Err(TransportError::Protocol("empty record"));
            }
            out.extend_from_slice(&self.buf[1..n]);
            if out.len() > MAX_RECORD {
                return Err(TransportError::Protocol("record too large"));
            }
            if self.buf[0] == 0 {
                return Ok(out);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn psk_and_hello_input_are_stable() {
        assert_eq!(psk(&[1; 32]), psk(&[1; 32]));
        assert_ne!(psk(&[1; 32]), psk(&[2; 32]));
        let m = hello_input(ROLE_INITIATOR, &[7; 32]);
        assert!(m.starts_with(HELLO_CONTEXT));
        assert_eq!(m.len(), HELLO_CONTEXT.len() + 33);
    }
}
