//! The observer: a small helper that runs on a home PC (never a central server). It
//!
//! - answers UDP probes on two ports with the address it saw (NAT mapping and filtering);
//! - on request, sends a 16-byte random token to an address the requesting phone proved it owns
//!   (filtering, mapping lifetime and inbound tests);
//! - introduces two phones of one room to each other (hole punching), and
//! - pipes the bytes of two phones of one room into each other when nothing else works (relay;
//!   the phones run TLS end to end through it, so it sees only ciphertext).
//!
//! Only someone holding the observer card (its Noise public key) can talk to it at all, and it
//! sends tokens only to addresses the requester itself came from.

use crate::noise::{self, NoiseStream};
use crate::util::{alias_sa, canon, for_socket, ms};
use anyhow::{anyhow, bail, Result};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::oneshot;
use tokio::time::timeout;

pub const DEFAULT_PORT: u16 = 40100;
pub const DEFAULT_ALT: u16 = 40101;
const GO_IN_MS: u64 = 900;
const MAX_CONNS: usize = 256;
const MAX_TOKENS_PER_CONN: u32 = 64;
const MAX_DIALS_PER_CONN: u32 = 16;
const MAX_SPEED_BYTES: u64 = 64 << 20;
const ROOM_TTL: Duration = Duration::from_secs(180);
const SEEN_TTL: Duration = Duration::from_secs(900);

#[derive(Clone, Debug)]
pub struct ObserverCfg {
    pub sk: Vec<u8>,
    /// Address to bind. `[::]` = every address, IPv4 and IPv6.
    pub bind: IpAddr,
    pub port: u16,
    pub alt: u16,
    pub extra_tcp: Vec<u16>,
    /// Log every request (addresses are aliased, never printed).
    pub verbose: bool,
}

impl ObserverCfg {
    pub fn new(sk: Vec<u8>) -> Self {
        ObserverCfg {
            sk,
            bind: IpAddr::V6(Ipv6Addr::UNSPECIFIED),
            port: DEFAULT_PORT,
            alt: DEFAULT_ALT,
            extra_tcp: vec![],
            verbose: false,
        }
    }
}

#[derive(Default)]
struct Member {
    udp: Option<SocketAddr>,
    tcp: Option<SocketAddr>,
    waiter: Option<oneshot::Sender<Value>>,
    relay: Option<oneshot::Sender<TcpStream>>,
}

struct Room {
    m: [Member; 2],
    born: Instant,
}

struct State {
    cfg: ObserverCfg,
    udp: [Arc<UdpSocket>; 2],
    udp_v6: bool,
    rooms: Mutex<HashMap<String, Room>>,
    /// client id -> addresses that sent a valid probe with it, and when.
    seen: Mutex<HashMap<String, Vec<(IpAddr, Instant)>>>,
    conns: AtomicUsize,
}

fn role_index(r: &str) -> Result<usize> {
    match r {
        "a" => Ok(0),
        "b" => Ok(1),
        _ => bail!("role must be a or b"),
    }
}

fn bind_udp(ip: IpAddr, port: u16) -> Result<(std::net::UdpSocket, bool)> {
    use socket2::{Domain, Protocol, Socket, Type};
    let v6 = ip.is_ipv6();
    let s = Socket::new(if v6 { Domain::IPV6 } else { Domain::IPV4 }, Type::DGRAM, Some(Protocol::UDP))?;
    if v6 && ip.is_unspecified() {
        s.set_only_v6(false)?;
    }
    s.bind(&SocketAddr::new(ip, port).into())?;
    s.set_nonblocking(true)?;
    Ok((s.into(), v6))
}

fn bind_tcp(ip: IpAddr, port: u16) -> Result<std::net::TcpListener> {
    use socket2::{Domain, Protocol, Socket, Type};
    let v6 = ip.is_ipv6();
    let s = Socket::new(if v6 { Domain::IPV6 } else { Domain::IPV4 }, Type::STREAM, Some(Protocol::TCP))?;
    if v6 && ip.is_unspecified() {
        s.set_only_v6(false)?;
    }
    #[cfg(unix)]
    s.set_reuse_address(true)?;
    s.bind(&SocketAddr::new(ip, port).into())?;
    s.listen(256)?;
    s.set_nonblocking(true)?;
    Ok(s.into())
}

/// Runs until the process ends. Binding errors are returned at once.
pub async fn serve(cfg: ObserverCfg) -> Result<()> {
    let state = start(cfg).await?;
    // Housekeeping: forget stale rooms and stale "seen" entries.
    loop {
        tokio::time::sleep(Duration::from_secs(30)).await;
        state.rooms.lock().unwrap().retain(|_, r| r.born.elapsed() < ROOM_TTL);
        state.seen.lock().unwrap().retain(|_, v| {
            v.retain(|(_, t)| t.elapsed() < SEEN_TTL);
            !v.is_empty()
        });
    }
}

/// Binds everything and spawns the loops; returns once the observer is listening.
async fn start(cfg: ObserverCfg) -> Result<Arc<State>> {
    let (u0, v6) = bind_udp(cfg.bind, cfg.port).map_err(|e| anyhow!("UDP {}: {e}", cfg.port))?;
    let (u1, _) = bind_udp(cfg.bind, cfg.alt).map_err(|e| anyhow!("UDP {}: {e}", cfg.alt))?;
    let mut tcp_ports = vec![cfg.port, cfg.alt];
    tcp_ports.extend(cfg.extra_tcp.iter().copied());
    let mut listeners = vec![];
    for p in &tcp_ports {
        let l = bind_tcp(cfg.bind, *p).map_err(|e| anyhow!("TCP {p}: {e}"))?;
        listeners.push(TcpListener::from_std(l)?);
    }
    let state = Arc::new(State {
        cfg,
        udp: [Arc::new(UdpSocket::from_std(u0)?), Arc::new(UdpSocket::from_std(u1)?)],
        udp_v6: v6,
        rooms: Mutex::new(HashMap::new()),
        seen: Mutex::new(HashMap::new()),
        conns: AtomicUsize::new(0),
    });
    for which in 0..2 {
        tokio::spawn(udp_loop(state.clone(), which));
    }
    for l in listeners {
        tokio::spawn(tcp_loop(state.clone(), l));
    }
    crate::plog!("observer listening: tcp {:?}, udp {} and {}", tcp_ports, state.cfg.port, state.cfg.alt);
    Ok(state)
}

/// For tests and the desktop self-test: start an observer inside this process.
pub async fn spawn_local(cfg: ObserverCfg) -> Result<()> {
    let state = start(cfg).await?;
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(30)).await;
            state.rooms.lock().unwrap().retain(|_, r| r.born.elapsed() < ROOM_TTL);
        }
    });
    Ok(())
}

impl State {
    fn note_seen(&self, cid: &str, ip: IpAddr) {
        if cid.is_empty() || cid.len() > 64 {
            return;
        }
        let mut m = self.seen.lock().unwrap();
        let v = m.entry(cid.to_string()).or_default();
        let ip = ip.to_canonical();
        v.retain(|(a, _)| *a != ip);
        v.push((ip, Instant::now()));
        if v.len() > 16 {
            v.remove(0);
        }
    }

    /// Tokens and dials go only to addresses the requester itself used.
    fn allowed(&self, conn_ip: IpAddr, cid: &str, target: IpAddr) -> bool {
        let t = target.to_canonical();
        let c = conn_ip.to_canonical();
        if t == c {
            return true;
        }
        if let (IpAddr::V6(a), IpAddr::V6(b)) = (t, c) {
            if a.segments()[..4] == b.segments()[..4] {
                return true;
            }
        }
        self.seen
            .lock()
            .unwrap()
            .get(cid)
            .map(|v| v.iter().any(|(a, when)| *a == t && when.elapsed() < SEEN_TTL))
            .unwrap_or(false)
    }
}

async fn udp_loop(state: Arc<State>, which: usize) {
    let sock = state.udp[which].clone();
    let mut buf = vec![0u8; 2048];
    loop {
        let (n, from) = match sock.recv_from(&mut buf).await {
            Ok(x) => x,
            Err(_) => {
                tokio::time::sleep(Duration::from_millis(50)).await;
                continue;
            }
        };
        // Message 1 of NK is at least an ephemeral key (32) and a tag (16).
        if n < 48 {
            continue;
        }
        let (hs, req) = match noise::udp_open(&state.cfg.sk, &buf[..n]) {
            Ok(x) => x,
            Err(_) => continue,
        };
        let from = canon(from);
        let cid = req["cid"].as_str().unwrap_or("").to_string();
        state.note_seen(&cid, from.ip());
        let via = if which == 0 { state.cfg.port } else { state.cfg.alt };
        let mut answer = json!({ "observed": from.to_string(), "via": via, "time_ms": crate::util::now_ms() });
        let mut out_sock = which;
        match req["t"].as_str().unwrap_or("") {
            "map" => {
                if req["reply"].as_str() == Some("alt") {
                    out_sock = 1 - which;
                    answer["from_port"] = json!(if out_sock == 0 { state.cfg.port } else { state.cfg.alt });
                }
            }
            "reg" => {
                let room = req["room"].as_str().unwrap_or("").to_string();
                match role_index(req["role"].as_str().unwrap_or("")) {
                    Ok(r) if !room.is_empty() && room.len() <= 64 => {
                        let mut rooms = state.rooms.lock().unwrap();
                        let entry = rooms.entry(room).or_insert_with(|| Room { m: Default::default(), born: Instant::now() });
                        entry.m[r].udp = Some(from);
                    }
                    _ => answer["error"] = json!("bad room or role"),
                }
            }
            _ => answer["error"] = json!("unknown request"),
        }
        if state.cfg.verbose {
            crate::plog!("udp {} from {}", req["t"].as_str().unwrap_or("?"), alias_sa(from));
        }
        if let Ok(msg) = noise::udp_seal(hs, &answer) {
            let _ = state.udp[out_sock].send_to(&msg, for_socket(from, state.udp_v6)).await;
        }
    }
}

async fn tcp_loop(state: Arc<State>, l: TcpListener) {
    loop {
        let (stream, peer) = match l.accept().await {
            Ok(x) => x,
            Err(_) => {
                tokio::time::sleep(Duration::from_millis(50)).await;
                continue;
            }
        };
        if state.conns.load(Ordering::Relaxed) >= MAX_CONNS {
            continue;
        }
        let st = state.clone();
        tokio::spawn(async move {
            st.conns.fetch_add(1, Ordering::Relaxed);
            let local_port = stream.local_addr().map(|a| a.port()).unwrap_or(0);
            if let Err(e) = handle_conn(st.clone(), stream, canon(peer), local_port).await {
                if st.cfg.verbose {
                    crate::plog!("conn from {} ended: {}", alias_sa(peer), crate::util::err_kind(&e));
                }
            }
            st.conns.fetch_sub(1, Ordering::Relaxed);
        });
    }
}

async fn handle_conn(state: Arc<State>, stream: TcpStream, peer: SocketAddr, local_port: u16) -> Result<()> {
    stream.set_nodelay(true)?;
    let observed = peer.to_string();
    let (mut ns, hello) = timeout(
        Duration::from_secs(10),
        noise::server(stream, &state.cfg.sk, |_| {
            json!({ "t": "hi", "observed": observed, "port": local_port, "time_ms": crate::util::now_ms(), "proto": crate::PROTO })
        }),
    )
    .await??;
    let cid = hello["cid"].as_str().unwrap_or("").to_string();
    state.note_seen(&cid, peer.ip());
    let mut tokens = 0u32;
    let mut dials = 0u32;
    let mut speed_total = 0u64;
    loop {
        let req = match timeout(Duration::from_secs(600), ns.recv_json()).await {
            Ok(Ok(r)) => r,
            _ => return Ok(()),
        };
        let t = req["t"].as_str().unwrap_or("").to_string();
        if state.cfg.verbose {
            crate::plog!("tcp {} from {}", t, alias_sa(peer));
        }
        match t.as_str() {
            "ping" => ns.send_json(&json!({ "t": "pong", "n": req["n"] })).await?,
            "udp_send" => {
                tokens += 1;
                let r = if tokens > MAX_TOKENS_PER_CONN {
                    json!({ "error": "too many tokens" })
                } else {
                    udp_send(&state, &req, peer.ip(), &cid).await
                };
                ns.send_json(&r).await?;
            }
            "tcp_connect" => {
                dials += 1;
                let r = if dials > MAX_DIALS_PER_CONN {
                    json!({ "error": "too many dials" })
                } else {
                    tcp_dial(&state, &req, peer.ip(), &cid).await
                };
                ns.send_json(&r).await?;
            }
            "room_join" => {
                let r = room_join(&state, &req, peer).await;
                ns.send_json(&r).await?;
            }
            "relay" => return relay(&state, ns, &req).await,
            "speed_up" => {
                let want = req["bytes"].as_u64().unwrap_or(0).min(MAX_SPEED_BYTES);
                speed_total += want;
                if speed_total > MAX_SPEED_BYTES {
                    ns.send_json(&json!({ "error": "speed budget used up" })).await?;
                    continue;
                }
                ns.send_json(&json!({ "t": "go" })).await?;
                let t0 = Instant::now();
                let mut got = 0u64;
                while got < want {
                    got += timeout(Duration::from_secs(120), ns.recv()).await??.len() as u64;
                }
                ns.send_json(&json!({ "t": "got", "bytes": got, "ms": ms(t0.elapsed()) })).await?;
            }
            "speed_down" => {
                let want = req["bytes"].as_u64().unwrap_or(0).min(MAX_SPEED_BYTES);
                speed_total += want;
                if speed_total > MAX_SPEED_BYTES {
                    ns.send_json(&json!({ "error": "speed budget used up" })).await?;
                    continue;
                }
                ns.send_json(&json!({ "t": "data", "bytes": want })).await?;
                let mut chunk = vec![0u8; 60_000];
                crate::util::rand_fill(&mut chunk);
                let mut left = want;
                while left > 0 {
                    let k = left.min(chunk.len() as u64) as usize;
                    timeout(Duration::from_secs(120), ns.send(&chunk[..k])).await??;
                    left -= k as u64;
                }
            }
            "bye" => return Ok(()),
            _ => ns.send_json(&json!({ "error": "unknown request" })).await?,
        }
    }
}

fn parse_target(req: &Value) -> Result<SocketAddr> {
    let t: SocketAddr = req["target"].as_str().ok_or_else(|| anyhow!("no target"))?.parse()?;
    Ok(canon(t))
}

fn parse_token(req: &Value) -> Result<[u8; 16]> {
    let t = crate::util::b64d(req["token"].as_str().unwrap_or(""))?;
    t.try_into().map_err(|_| anyhow!("token must be 16 bytes"))
}

async fn udp_send(state: &State, req: &Value, conn_ip: IpAddr, cid: &str) -> Value {
    let go = async {
        let target = parse_target(req)?;
        let token = parse_token(req)?;
        if !state.allowed(conn_ip, cid, target.ip()) {
            bail!("target is not an address of this client");
        }
        if target.is_ipv6() && !state.udp_v6 {
            bail!("observer has no IPv6 socket");
        }
        let which = if req["from"].as_str() == Some("alt") { 1 } else { 0 };
        let delay = req["delay_ms"].as_u64().unwrap_or(0).min(600_000);
        if delay > 0 {
            tokio::time::sleep(Duration::from_millis(delay)).await;
        }
        state.udp[which].send_to(&token, for_socket(target, state.udp_v6)).await?;
        Ok::<_, anyhow::Error>(json!({ "t": "sent" }))
    };
    go.await.unwrap_or_else(|e| json!({ "error": crate::util::err_kind(&e) }))
}

async fn tcp_dial(state: &State, req: &Value, conn_ip: IpAddr, cid: &str) -> Value {
    let go = async {
        let target = parse_target(req)?;
        let token = parse_token(req)?;
        if !state.allowed(conn_ip, cid, target.ip()) {
            bail!("target is not an address of this client");
        }
        let t0 = Instant::now();
        let r = timeout(Duration::from_secs(5), TcpStream::connect(target)).await;
        match r {
            Ok(Ok(mut s)) => {
                use tokio::io::AsyncWriteExt;
                let connect_ms = ms(t0.elapsed());
                let _ = s.write_all(&token).await;
                let _ = s.flush().await;
                tokio::time::sleep(Duration::from_millis(300)).await;
                Ok(json!({ "t": "dialed", "connected": true, "ms": connect_ms }))
            }
            Ok(Err(e)) => Ok(json!({ "t": "dialed", "connected": false, "err": format!("{:?}", e.kind()) })),
            Err(_) => Ok(json!({ "t": "dialed", "connected": false, "err": "TimedOut" })),
        }
    };
    go.await.unwrap_or_else(|e: anyhow::Error| json!({ "error": crate::util::err_kind(&e) }))
}

async fn room_join(state: &State, req: &Value, peer: SocketAddr) -> Value {
    let room = req["room"].as_str().unwrap_or("").to_string();
    let me = match role_index(req["role"].as_str().unwrap_or("")) {
        Ok(r) if !room.is_empty() && room.len() <= 64 => r,
        _ => return json!({ "error": "bad room or role" }),
    };
    let other = 1 - me;
    let wait = {
        let mut rooms = state.rooms.lock().unwrap();
        let r = rooms.entry(room.clone()).or_insert_with(|| Room { m: Default::default(), born: Instant::now() });
        r.m[me].tcp = Some(peer);
        if r.m[other].waiter.is_some() {
            let to_other = json!({ "t": "peer", "udp": r.m[me].udp.map(|a| a.to_string()), "tcp": peer.to_string(), "go_in_ms": GO_IN_MS });
            let to_me = json!({ "t": "peer", "udp": r.m[other].udp.map(|a| a.to_string()), "tcp": r.m[other].tcp.map(|a| a.to_string()), "go_in_ms": GO_IN_MS });
            let _ = r.m[other].waiter.take().unwrap().send(to_other);
            return to_me;
        }
        let (tx, rx) = oneshot::channel();
        r.m[me].waiter = Some(tx);
        rx
    };
    match timeout(Duration::from_secs(120), wait).await {
        Ok(Ok(v)) => v,
        _ => {
            if let Some(r) = state.rooms.lock().unwrap().get_mut(&room) {
                r.m[me].waiter = None;
            }
            json!({ "error": "the other phone did not join within 2 minutes" })
        }
    }
}

/// After both phones of a room asked for it, the two connections become one pipe.
async fn relay(state: &State, mut ns: NoiseStream<TcpStream>, req: &Value) -> Result<()> {
    let room = req["room"].as_str().unwrap_or("").to_string();
    let me = role_index(req["role"].as_str().unwrap_or(""))?;
    let other = 1 - me;
    enum Side {
        Second(oneshot::Sender<TcpStream>),
        First(oneshot::Receiver<TcpStream>),
    }
    let side = {
        let mut rooms = state.rooms.lock().unwrap();
        let r = rooms.entry(room.clone()).or_insert_with(|| Room { m: Default::default(), born: Instant::now() });
        match r.m[other].relay.take() {
            Some(tx) => Side::Second(tx),
            None => {
                let (tx, rx) = oneshot::channel();
                r.m[me].relay = Some(tx);
                Side::First(rx)
            }
        }
    };
    match side {
        Side::Second(tx) => {
            ns.send_json(&json!({ "t": "relay", "state": "ready" })).await?;
            let mine = ns.into_inner();
            tx.send(mine).map_err(|_| anyhow!("the other phone left"))?;
            Ok(())
        }
        Side::First(rx) => {
            let mut theirs = match timeout(Duration::from_secs(120), rx).await {
                Ok(Ok(s)) => s,
                _ => {
                    ns.send_json(&json!({ "error": "the other phone did not ask for the relay within 2 minutes" })).await?;
                    return Ok(());
                }
            };
            ns.send_json(&json!({ "t": "relay", "state": "ready" })).await?;
            let mut mine = ns.into_inner();
            let r = timeout(Duration::from_secs(900), tokio::io::copy_bidirectional(&mut mine, &mut theirs)).await;
            if state.cfg.verbose {
                if let Ok(Ok((a, b))) = r {
                    crate::plog!("relay done: {a} + {b} bytes");
                }
            }
            Ok(())
        }
    }
}

/// The unspecified address of the family that should be bound by default.
pub fn default_bind() -> IpAddr {
    match std::net::UdpSocket::bind("[::]:0") {
        Ok(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        Err(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
    }
}
