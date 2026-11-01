//! Phone side of the observer protocol.

use crate::card::ObserverCard;
use crate::noise::{self, NoiseStream};
use crate::util::{canon, for_socket, ms};
use anyhow::{anyhow, bail, Result};
use serde_json::{json, Value};
use std::net::SocketAddr;
use std::time::{Duration, Instant};
use tokio::net::{TcpStream, UdpSocket};
use tokio::time::timeout;

pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(6);

/// An encrypted control connection to one observer port.
pub struct ObsConn {
    pub ns: NoiseStream<TcpStream>,
    /// Our address as the observer saw it (the TCP mapping of `local`).
    pub observed: SocketAddr,
    pub local: SocketAddr,
    pub connect_ms: f64,
    pub handshake_ms: f64,
}

impl ObsConn {
    /// `from`: bind this local address first (SO_REUSEADDR/SO_REUSEPORT) so several connections
    /// share one port; None = any port.
    pub async fn connect(card: &ObserverCard, to: SocketAddr, from: Option<SocketAddr>, cid: &str) -> Result<Self> {
        let pk = card.pk()?;
        let t0 = Instant::now();
        let stream = match from {
            Some(local) => timeout(CONNECT_TIMEOUT, crate::util::reuse_tcp(local)?.connect(to)).await??,
            None => timeout(CONNECT_TIMEOUT, TcpStream::connect(to)).await??,
        };
        stream.set_nodelay(true)?;
        let connect_ms = ms(t0.elapsed());
        let local = stream.local_addr()?;
        let t1 = Instant::now();
        let hello = json!({ "t": "hello", "v": crate::PROTO, "cid": cid });
        let (ns, greet) = timeout(CONNECT_TIMEOUT, noise::client(stream, &pk, &hello)).await??;
        let handshake_ms = ms(t1.elapsed());
        let observed: SocketAddr = greet["observed"]
            .as_str()
            .and_then(|s| s.parse().ok())
            .ok_or_else(|| anyhow!("observer greeting without address"))?;
        Ok(ObsConn { ns, observed: canon(observed), local, connect_ms, handshake_ms })
    }

    pub async fn call(&mut self, req: Value) -> Result<Value> {
        self.call_within(req, Duration::from_secs(15)).await
    }

    pub async fn call_within(&mut self, req: Value, limit: Duration) -> Result<Value> {
        self.ns.send_json(&req).await?;
        let r = timeout(limit, self.ns.recv_json()).await??;
        if let Some(e) = r["error"].as_str() {
            bail!("observer refused: {e}");
        }
        Ok(r)
    }
}

/// One NK request datagram and its answer. Retries with a fresh handshake each time; only one
/// request is outstanding at a time, so a late answer can never be mistaken for a new one.
pub struct UdpAnswer {
    pub answer: Value,
    pub from: SocketAddr,
    pub rtt_ms: f64,
    pub tries: u32,
}

pub async fn udp_call(
    sock: &UdpSocket,
    sock_is_v6: bool,
    obs_pk: &[u8],
    to: SocketAddr,
    req: &Value,
    tries: u32,
    each: Duration,
) -> Result<UdpAnswer> {
    let mut buf = vec![0u8; 2048];
    for attempt in 1..=tries {
        let (mut hs, msg) = noise::udp_request(obs_pk, req)?;
        let t0 = Instant::now();
        sock.send_to(&msg, for_socket(to, sock_is_v6)).await?;
        let deadline = t0 + each;
        loop {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            let (n, from) = match timeout(left, sock.recv_from(&mut buf)).await {
                Ok(r) => r?,
                Err(_) => break,
            };
            // An answer is at least ephemeral key (32) + tag (16); tokens are 16 bytes.
            if n < 48 {
                continue;
            }
            match noise::udp_answer(&mut hs, &buf[..n]) {
                Ok(answer) => {
                    return Ok(UdpAnswer { answer, from: canon(from), rtt_ms: ms(t0.elapsed()), tries: attempt });
                }
                // Not ours (or damaged): this handshake may now be spoiled, so start a new one.
                Err(_) => break,
            }
        }
    }
    bail!("no answer after {tries} tries")
}

/// Waits for 16-byte tokens on `sock` for `wait`, returning the ones that arrived.
pub async fn collect_tokens(sock: &UdpSocket, want: &[[u8; 16]], wait: Duration) -> Vec<[u8; 16]> {
    let mut got = vec![];
    let mut buf = vec![0u8; 2048];
    let end = Instant::now() + wait;
    while got.len() < want.len() {
        let left = end.saturating_duration_since(Instant::now());
        if left.is_zero() {
            break;
        }
        match timeout(left, sock.recv_from(&mut buf)).await {
            Ok(Ok((16, _))) => {
                let t: [u8; 16] = buf[..16].try_into().unwrap();
                if want.contains(&t) && !got.contains(&t) {
                    got.push(t);
                }
            }
            Ok(Ok(_)) => continue,
            _ => break,
        }
    }
    got
}
