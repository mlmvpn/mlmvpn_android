//! Noise (snow) for everything between a phone and an observer.
//!
//! - TCP control channel: `Noise_NK_25519_ChaChaPoly_BLAKE2s`. The phone knows the observer's
//!   static key from the observer card, so it knows it reached the right observer; the observer
//!   learns nothing about who the phone is. Every message after the two handshake messages is a
//!   Noise transport message, framed by a two-byte length.
//! - UDP probes: one NK handshake per request. Message 1 carries the request, message 2 the
//!   answer, both encrypted, so a request/answer pair costs exactly one datagram each way and the
//!   observer keeps no state.

use anyhow::{bail, Context, Result};
use serde_json::Value;
use snow::{params::NoiseParams, Builder, HandshakeState, TransportState};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const NK: &str = "Noise_NK_25519_ChaChaPoly_BLAKE2s";
pub const NK_PSK0: &str = "Noise_NKpsk0_25519_ChaChaPoly_BLAKE2s";
/// Largest Noise message (the spec's limit) and the largest payload one transport message holds.
pub const MAX_MSG: usize = 65535;
pub const MAX_PAYLOAD: usize = MAX_MSG - 16;

pub fn params(name: &str) -> NoiseParams {
    name.parse().expect("valid noise params")
}

/// (private, public)
pub fn keypair() -> Result<(Vec<u8>, Vec<u8>)> {
    let kp = Builder::new(params(NK)).generate_keypair()?;
    Ok((kp.private, kp.public))
}

pub fn nk_initiator(remote_pk: &[u8]) -> Result<HandshakeState> {
    if remote_pk.len() != 32 {
        bail!("observer key must be 32 bytes");
    }
    Ok(Builder::new(params(NK)).remote_public_key(remote_pk).build_initiator()?)
}

pub fn nk_responder(sk: &[u8]) -> Result<HandshakeState> {
    Ok(Builder::new(params(NK)).local_private_key(sk).build_responder()?)
}

/// Client side of a one-datagram request: returns the handshake (kept to read the answer) and
/// the datagram to send.
pub fn udp_request(remote_pk: &[u8], req: &Value) -> Result<(HandshakeState, Vec<u8>)> {
    let mut hs = nk_initiator(remote_pk)?;
    let payload = serde_json::to_vec(req)?;
    let mut buf = vec![0u8; MAX_MSG];
    let n = hs.write_message(&payload, &mut buf)?;
    buf.truncate(n);
    Ok((hs, buf))
}

/// Client side: opens the answer. Fails on anything that is not the answer to this request.
pub fn udp_answer(hs: &mut HandshakeState, msg: &[u8]) -> Result<Value> {
    let mut out = vec![0u8; MAX_MSG];
    let n = hs.read_message(msg, &mut out)?;
    Ok(serde_json::from_slice(&out[..n])?)
}

/// Observer side: opens a request datagram. The handshake is kept to seal the answer.
pub fn udp_open(sk: &[u8], msg: &[u8]) -> Result<(HandshakeState, Value)> {
    let mut hs = nk_responder(sk)?;
    let mut out = vec![0u8; MAX_MSG];
    let n = hs.read_message(msg, &mut out)?;
    Ok((hs, serde_json::from_slice(&out[..n])?))
}

/// Observer side: seals the answer datagram.
pub fn udp_seal(mut hs: HandshakeState, answer: &Value) -> Result<Vec<u8>> {
    let payload = serde_json::to_vec(answer)?;
    let mut buf = vec![0u8; MAX_MSG];
    let n = hs.write_message(&payload, &mut buf)?;
    buf.truncate(n);
    Ok(buf)
}

/// A Noise transport over any byte stream. It never reads past the frame it needs, so after a
/// "switch to raw" message the underlying stream can be handed on (the relay does that).
pub struct NoiseStream<S> {
    io: S,
    ts: TransportState,
    buf: Vec<u8>,
}

impl<S: AsyncRead + AsyncWrite + Unpin> NoiseStream<S> {
    /// Sends `data` as one transport message per 65519 bytes (an empty `data` is one empty message).
    pub async fn send(&mut self, data: &[u8]) -> Result<()> {
        let mut chunks: Vec<&[u8]> = data.chunks(MAX_PAYLOAD).collect();
        if chunks.is_empty() {
            chunks.push(&[]);
        }
        for chunk in chunks {
            let n = self.ts.write_message(chunk, &mut self.buf[2..])?;
            self.buf[..2].copy_from_slice(&(n as u16).to_be_bytes());
            self.io.write_all(&self.buf[..2 + n]).await?;
        }
        Ok(())
    }

    pub async fn recv(&mut self) -> Result<Vec<u8>> {
        let msg = read_frame(&mut self.io).await?;
        let mut out = vec![0u8; msg.len()];
        let n = self.ts.read_message(&msg, &mut out)?;
        out.truncate(n);
        Ok(out)
    }

    pub async fn send_json(&mut self, v: &Value) -> Result<()> {
        let b = serde_json::to_vec(v)?;
        if b.len() > MAX_PAYLOAD {
            bail!("control message too large");
        }
        self.send(&b).await?;
        self.io.flush().await?;
        Ok(())
    }

    pub async fn recv_json(&mut self) -> Result<Value> {
        let b = self.recv().await?;
        Ok(serde_json::from_slice(&b).context("control message is not json")?)
    }

    pub fn get_ref(&self) -> &S {
        &self.io
    }

    pub fn into_inner(self) -> S {
        self.io
    }
}

async fn read_frame<S: AsyncRead + Unpin>(io: &mut S) -> Result<Vec<u8>> {
    let mut l = [0u8; 2];
    io.read_exact(&mut l).await?;
    let n = u16::from_be_bytes(l) as usize;
    let mut m = vec![0u8; n];
    io.read_exact(&mut m).await?;
    Ok(m)
}

async fn write_frame<S: AsyncWrite + Unpin>(io: &mut S, m: &[u8]) -> Result<()> {
    let mut b = Vec::with_capacity(2 + m.len());
    b.extend_from_slice(&(m.len() as u16).to_be_bytes());
    b.extend_from_slice(m);
    io.write_all(&b).await?;
    io.flush().await?;
    Ok(())
}

/// Phone side of the TCP control channel. `hello` travels inside handshake message 1 (already
/// encrypted to the observer's key); the observer's greeting comes back inside message 2.
pub async fn client<S: AsyncRead + AsyncWrite + Unpin>(
    mut io: S,
    remote_pk: &[u8],
    hello: &Value,
) -> Result<(NoiseStream<S>, Value)> {
    let mut hs = nk_initiator(remote_pk)?;
    let mut buf = vec![0u8; MAX_MSG];
    let n = hs.write_message(&serde_json::to_vec(hello)?, &mut buf)?;
    write_frame(&mut io, &buf[..n]).await?;
    let m2 = read_frame(&mut io).await?;
    let k = hs.read_message(&m2, &mut buf)?;
    let greeting: Value = serde_json::from_slice(&buf[..k])?;
    let ts = hs.into_transport_mode()?;
    Ok((NoiseStream { io, ts, buf: vec![0u8; MAX_MSG + 2] }, greeting))
}

/// Observer side of the TCP control channel.
pub async fn server<S, F>(mut io: S, sk: &[u8], greet: F) -> Result<(NoiseStream<S>, Value)>
where
    S: AsyncRead + AsyncWrite + Unpin,
    F: FnOnce(&Value) -> Value,
{
    let mut hs = nk_responder(sk)?;
    let mut buf = vec![0u8; MAX_MSG];
    let m1 = read_frame(&mut io).await?;
    let k = hs.read_message(&m1, &mut buf)?;
    let hello: Value = serde_json::from_slice(&buf[..k])?;
    let reply = greet(&hello);
    let n = hs.write_message(&serde_json::to_vec(&reply)?, &mut buf)?;
    write_frame(&mut io, &buf[..n]).await?;
    let ts = hs.into_transport_mode()?;
    Ok((NoiseStream { io, ts, buf: vec![0u8; MAX_MSG + 2] }, hello))
}
