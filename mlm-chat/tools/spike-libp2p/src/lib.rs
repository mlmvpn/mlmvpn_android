//! The smallest rust-libp2p node with the stack PLAN section 17.2 / SPEC section 9 proposes:
//! TCP and QUIC, Noise, Yamux, a private Kademlia (`/mlmchat/kad/1`), Circuit Relay v2 (client,
//! and server on a desktop), DCUtR, identify, ping, and one request/response protocol
//! (`/mlmchat/msg/1`) that carries the test messages.
//!
//! Three uses:
//! - `libspike_libp2p.so` on a phone (netprobe): `chat` finds the other phone through the home
//!   PC's relay (a provider record in the private Kademlia), connects through the relay, lets
//!   DCUtR try a direct connection, and times a small message and 1 MiB on each path.
//! - `serve_relay` inside mlm-observer on the home PC: relay v2 server + Kademlia server.
//! - `spike-node loopback` on the desktop: relay and two peers on 127.0.0.1.
//!
//! Nothing here logs or reports an IP address: only its family and kind (lan, cgnat, public...).

use anyhow::{anyhow, Context, Result};
use async_trait::async_trait;
use futures::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, StreamExt};
use libp2p::multiaddr::Protocol;
use libp2p::request_response::{self, OutboundRequestId, ProtocolSupport};
use libp2p::swarm::behaviour::toggle::Toggle;
use libp2p::swarm::{ConnectionId, DialError, NetworkBehaviour, SwarmEvent};
use libp2p::{dcutr, identify, identity, kad, noise, ping, relay, tcp, yamux, Multiaddr, PeerId, StreamProtocol, Swarm};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub use libp2p::identity::Keypair;

pub const KAD_PROTOCOL: &str = "/mlmchat/kad/1";
pub const MSG_PROTOCOL: &str = "/mlmchat/msg/1";
pub const DEFAULT_RELAY_PORT: u16 = 40200;
pub const DEFAULT_ROOM: &str = "mlmchat-spike";

/// Test messages: one kind byte, then the payload. The answer is the received length (u64 BE).
const K_PING: u8 = 1;
const K_BULK: u8 = 2;
const K_DONE: u8 = 3;
const BULK: usize = 1 << 20;
const MAX_FRAME: usize = 2 << 20;

// ---- log and cancel (read by the JNI side) ----

static LOG: Mutex<Vec<String>> = Mutex::new(Vec::new());
static CANCEL: AtomicBool = AtomicBool::new(false);
static ECHO: AtomicBool = AtomicBool::new(false);

/// Also print every log line (the desktop relay).
pub fn set_echo(on: bool) {
    ECHO.store(on, Ordering::Relaxed);
}

pub fn log(s: impl Into<String>) {
    let s = s.into();
    if ECHO.load(Ordering::Relaxed) {
        println!("{s}");
    }
    if let Ok(mut l) = LOG.lock() {
        if l.len() < 400 {
            l.push(s);
        }
    }
}

pub fn drain_log() -> String {
    LOG.lock().map(|mut l| std::mem::take(&mut *l).join("\n")).unwrap_or_default()
}

pub fn cancel() {
    CANCEL.store(true, Ordering::Relaxed);
}

/// Error texts can carry addresses: anything that looks like an IPv4 or IPv6 address becomes `<ip>`.
pub fn scrub(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut run = String::new();
    let flush = |run: &mut String, out: &mut String| {
        let colons = run.matches(':').count();
        let dots = run.matches('.').count();
        let digits = run.chars().any(|c| c.is_ascii_digit());
        if colons >= 2 || (dots == 3 && digits && run.chars().all(|c| c.is_ascii_digit() || c == '.')) {
            out.push_str("<ip>");
        } else {
            out.push_str(run);
        }
        run.clear();
    };
    for c in s.chars() {
        if c.is_ascii_hexdigit() || c == ':' || c == '.' {
            run.push(c);
        } else {
            flush(&mut run, &mut out);
            out.push(c);
        }
    }
    flush(&mut run, &mut out);
    out
}

// ---- wire codec ----

/// Length-prefixed raw bytes both ways, so 1 MiB of payload costs 1 MiB on the wire.
#[derive(Clone, Default)]
pub struct RawCodec;

async fn read_frame<T: AsyncRead + Unpin + Send>(io: &mut T) -> std::io::Result<Vec<u8>> {
    let mut len = [0u8; 4];
    io.read_exact(&mut len).await?;
    let n = u32::from_be_bytes(len) as usize;
    if n > MAX_FRAME {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "frame too large"));
    }
    let mut buf = vec![0u8; n];
    io.read_exact(&mut buf).await?;
    Ok(buf)
}

async fn write_frame<T: AsyncWrite + Unpin + Send>(io: &mut T, b: &[u8]) -> std::io::Result<()> {
    io.write_all(&(b.len() as u32).to_be_bytes()).await?;
    io.write_all(b).await?;
    io.flush().await
}

#[async_trait]
impl request_response::Codec for RawCodec {
    type Protocol = StreamProtocol;
    type Request = Vec<u8>;
    type Response = Vec<u8>;

    async fn read_request<T>(&mut self, _: &StreamProtocol, io: &mut T) -> std::io::Result<Vec<u8>>
    where
        T: AsyncRead + Unpin + Send,
    {
        read_frame(io).await
    }

    async fn read_response<T>(&mut self, _: &StreamProtocol, io: &mut T) -> std::io::Result<Vec<u8>>
    where
        T: AsyncRead + Unpin + Send,
    {
        read_frame(io).await
    }

    async fn write_request<T>(&mut self, _: &StreamProtocol, io: &mut T, req: Vec<u8>) -> std::io::Result<()>
    where
        T: AsyncWrite + Unpin + Send,
    {
        write_frame(io, &req).await
    }

    async fn write_response<T>(&mut self, _: &StreamProtocol, io: &mut T, res: Vec<u8>) -> std::io::Result<()>
    where
        T: AsyncWrite + Unpin + Send,
    {
        write_frame(io, &res).await
    }
}

fn ack(n: usize) -> Vec<u8> {
    (n as u64).to_be_bytes().to_vec()
}

fn acked(r: &[u8]) -> Option<u64> {
    r.get(..8).map(|b| u64::from_be_bytes(b.try_into().unwrap_or([0; 8])))
}

fn payload(kind: u8, len: usize) -> Vec<u8> {
    let mut v = vec![0u8; len.max(1)];
    v[0] = kind;
    // Not all zeros, so a compressing middlebox cannot flatter the numbers.
    for (i, b) in v.iter_mut().enumerate().skip(1) {
        *b = (i as u32).wrapping_mul(2_654_435_761).to_be_bytes()[0];
    }
    v
}

// ---- the node ----

#[derive(NetworkBehaviour)]
pub struct Behaviour {
    pub relay_client: relay::client::Behaviour,
    pub relay_server: Toggle<relay::Behaviour>,
    pub dcutr: dcutr::Behaviour,
    pub identify: identify::Behaviour,
    pub ping: ping::Behaviour,
    pub kad: kad::Behaviour<kad::store::MemoryStore>,
    pub msg: request_response::Behaviour<RawCodec>,
}

pub fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// `relay_server`: also act as a Circuit Relay v2 server (a volunteer node, PLAN section 11).
pub fn build(key: identity::Keypair, relay_server: bool) -> Result<Swarm<Behaviour>> {
    let swarm = libp2p::SwarmBuilder::with_existing_identity(key)
        .with_tokio()
        .with_tcp(tcp::Config::default().nodelay(true), noise::Config::new, yamux::Config::default)?
        .with_quic()
        .with_relay_client(noise::Config::new, yamux::Config::default)?
        .with_behaviour(|key, relay_client| {
            let id = key.public().to_peer_id();
            let mut kcfg = kad::Config::new(StreamProtocol::new(KAD_PROTOCOL));
            kcfg.set_query_timeout(Duration::from_secs(30));
            // Test rooms are short-lived: a phone from an earlier run must not linger for a day.
            kcfg.set_provider_record_ttl(Some(Duration::from_secs(600)));
            kcfg.set_provider_publication_interval(None);
            // The default circuit (2 minutes, 128 KiB) is too small for a 1 MiB test.
            let rcfg = relay::Config {
                max_circuit_duration: Duration::from_secs(15 * 60),
                max_circuit_bytes: 64 << 20,
                ..Default::default()
            };
            Ok(Behaviour {
                relay_client,
                relay_server: Toggle::from(relay_server.then(|| relay::Behaviour::new(id, rcfg))),
                dcutr: dcutr::Behaviour::new(id),
                identify: identify::Behaviour::new(identify::Config::new("/mlmchat/id/1".into(), key.public())),
                ping: ping::Behaviour::new(ping::Config::new()),
                kad: kad::Behaviour::with_config(id, kad::store::MemoryStore::new(id), kcfg),
                msg: request_response::Behaviour::with_codec(
                    RawCodec,
                    [(StreamProtocol::new(MSG_PROTOCOL), ProtocolSupport::Full)],
                    request_response::Config::default().with_request_timeout(Duration::from_secs(90)),
                ),
            })
        })?
        .with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(120)))
        .build();
    Ok(swarm)
}

async fn wait_listen(s: &mut Swarm<Behaviour>, n: usize) -> Vec<Multiaddr> {
    let mut out = vec![];
    while out.len() < n {
        if let SwarmEvent::NewListenAddr { address, .. } = s.select_next_some().await {
            out.push(address);
        }
    }
    out
}

fn transport_of(a: &Multiaddr) -> &'static str {
    let mut t = "?";
    for p in a.iter() {
        match p {
            Protocol::Tcp(_) => t = "tcp",
            Protocol::QuicV1 => t = "quic",
            Protocol::P2pCircuit => t = "relay",
            _ => {}
        }
    }
    t
}

fn ip_kind(ip: IpAddr) -> &'static str {
    match ip {
        IpAddr::V4(v) => {
            let o = v.octets();
            if v.is_loopback() {
                "loopback"
            } else if v.is_private() {
                "lan"
            } else if o[0] == 100 && (o[1] & 0xC0) == 64 {
                "cgnat"
            } else if v.is_link_local() {
                "link-local"
            } else {
                "public"
            }
        }
        IpAddr::V6(v) => {
            let s0 = v.segments()[0];
            if v.is_loopback() {
                "loopback"
            } else if (s0 & 0xfe00) == 0xfc00 {
                "ula"
            } else if (s0 & 0xffc0) == 0xfe80 {
                "link-local"
            } else {
                "global"
            }
        }
    }
}

/// Family, kind and transport of an address; never the address itself.
pub fn describe(a: &Multiaddr) -> Value {
    let mut fam = "?";
    let mut kind = "?";
    for p in a.iter() {
        match p {
            Protocol::Ip4(ip) => {
                fam = "v4";
                kind = ip_kind(IpAddr::V4(ip));
            }
            Protocol::Ip6(ip) => {
                fam = "v6";
                kind = ip_kind(IpAddr::V6(ip));
            }
            _ => {}
        }
    }
    json!({ "family": fam, "kind": kind, "transport": transport_of(a) })
}

// ---- desktop relay (inside mlm-observer) ----

pub fn keypair_from_bytes(b: &[u8]) -> Result<identity::Keypair> {
    identity::Keypair::from_protobuf_encoding(b).map_err(|e| anyhow!("libp2p key: {e}"))
}

pub fn keypair_to_bytes(k: &identity::Keypair) -> Vec<u8> {
    k.to_protobuf_encoding().unwrap_or_default()
}

/// Relay v2 server + Kademlia server on `port` (TCP and QUIC, IPv4 and IPv6) until the task is
/// dropped. `external`: the addresses phones dial (the modem's public address first).
pub async fn serve_relay(key: identity::Keypair, port: u16, external: Vec<IpAddr>) -> Result<()> {
    let mut s = build(key, true)?;
    s.listen_on(format!("/ip4/0.0.0.0/tcp/{port}").parse()?)?;
    s.listen_on(format!("/ip4/0.0.0.0/udp/{port}/quic-v1").parse()?)?;
    let _ = s.listen_on(format!("/ip6/::/tcp/{port}").parse()?);
    let _ = s.listen_on(format!("/ip6/::/udp/{port}/quic-v1").parse()?);
    for ip in &external {
        let base = match ip {
            IpAddr::V4(v) => Multiaddr::empty().with(Protocol::Ip4(*v)),
            IpAddr::V6(v) => Multiaddr::empty().with(Protocol::Ip6(*v)),
        };
        s.add_external_address(base.clone().with(Protocol::Tcp(port)));
        s.add_external_address(base.with(Protocol::Udp(port)).with(Protocol::QuicV1));
    }
    s.behaviour_mut().kad.set_mode(Some(kad::Mode::Server));
    log(format!("libp2p relay: peer {} on TCP and UDP {port}", s.local_peer_id()));
    let mut peers: HashSet<PeerId> = HashSet::new();
    loop {
        match s.select_next_some().await {
            SwarmEvent::ConnectionEstablished { peer_id, endpoint, .. } => {
                if peers.insert(peer_id) {
                    log(format!("libp2p relay: phone connected ({}) · {} connected", describe(endpoint.get_remote_address())["transport"], peers.len()));
                }
            }
            SwarmEvent::ConnectionClosed { peer_id, num_established, .. } if num_established == 0 => {
                peers.remove(&peer_id);
                log(format!("libp2p relay: phone left · {} connected", peers.len()));
            }
            SwarmEvent::Behaviour(BehaviourEvent::RelayServer(e)) => match e {
                relay::Event::ReservationReqAccepted { renewed, .. } => log(format!("libp2p relay: reservation {}", if renewed { "renewed" } else { "accepted" })),
                relay::Event::CircuitReqAccepted { .. } => log("libp2p relay: circuit opened between two phones"),
                relay::Event::CircuitClosed { error, .. } => log(format!("libp2p relay: circuit closed{}", error.map(|e| format!(" ({})", scrub(&e.to_string()))).unwrap_or_default())),
                relay::Event::ReservationReqDenied { .. } | relay::Event::CircuitReqDenied { .. } => log(format!("libp2p relay: denied: {e:?}")),
                _ => {}
            },
            _ => {}
        }
    }
}

// ---- loopback (desktop and phone, nothing leaves the machine) ----

/// Relay + two peers on 127.0.0.1 inside one process.
pub async fn loopback_run(timeout_s: u64) -> Result<Value> {
    let t0 = Instant::now();
    let mut relay = build(identity::Keypair::generate_ed25519(), true)?;
    relay.listen_on("/ip4/127.0.0.1/tcp/0".parse()?)?;
    relay.listen_on("/ip4/127.0.0.1/udp/0/quic-v1".parse()?)?;
    let raddrs = wait_listen(&mut relay, 2).await;
    for a in &raddrs {
        relay.add_external_address(a.clone());
    }
    relay.behaviour_mut().kad.set_mode(Some(kad::Mode::Server));
    let relay_id = *relay.local_peer_id();
    let relay_tcp = raddrs.iter().find(|a| a.iter().any(|p| matches!(p, Protocol::Tcp(_)))).cloned().ok_or_else(|| anyhow!("no tcp addr"))?;
    let relay_quic: Multiaddr = raddrs.iter().find(|a| a.iter().any(|p| matches!(p, Protocol::QuicV1))).cloned().ok_or_else(|| anyhow!("no quic addr"))?;
    let relay_task = tokio::spawn(async move {
        loop {
            let _ = relay.select_next_some().await;
        }
    });

    let mut a = build(identity::Keypair::generate_ed25519(), false)?;
    let mut b = build(identity::Keypair::generate_ed25519(), false)?;
    for s in [&mut a, &mut b] {
        s.listen_on("/ip4/127.0.0.1/tcp/0".parse()?)?;
        s.listen_on("/ip4/127.0.0.1/udp/0/quic-v1".parse()?)?;
    }
    for s in [&mut a, &mut b] {
        for addr in wait_listen(s, 2).await {
            s.add_external_address(addr);
        }
        s.behaviour_mut().kad.add_address(&relay_id, relay_tcp.clone());
    }
    let a_id = *a.local_peer_id();
    // a: dial the relay (TCP), then ask it for a reservation.
    a.dial(relay_tcp.clone().with(Protocol::P2p(relay_id)))?;
    let circuit = relay_tcp.clone().with(Protocol::P2p(relay_id)).with(Protocol::P2pCircuit);
    // The circuit listener is opened only once the relay is connected and identified (below):
    // opened earlier, it closes at once with no address.
    let mut circuit_open = false;
    // b: reaches the relay over QUIC too, so both transports are exercised; the circuit itself
    // goes over the TCP connection.
    b.dial(relay_tcp.clone().with(Protocol::P2p(relay_id)))?;
    b.dial(relay_quic.clone().with(Protocol::P2p(relay_id)))?;
    let mut quic_ms = None;

    let mut out = json!({ "relay": "local", "kad_protocol": KAD_PROTOCOL });
    let mut reserved_ms = None;
    let mut relayed_ms = None;
    let mut direct = Value::Null;
    let mut sent = false;
    let mut answer_ms = None;
    let debug = std::env::var("SPIKE_DEBUG").is_ok();
    let deadline = tokio::time::sleep(Duration::from_secs(timeout_s));
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            _ = &mut deadline => break,
            ev = a.select_next_some() => match { if debug { eprintln!("a: {ev:?}"); } ev } {
                SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received { peer_id, .. })) if peer_id == relay_id && !circuit_open => {
                    circuit_open = true;
                    a.listen_on(circuit.clone())?;
                }
                SwarmEvent::Behaviour(BehaviourEvent::RelayClient(relay::client::Event::ReservationReqAccepted { .. })) => {
                    reserved_ms = Some(t0.elapsed().as_millis() as u64);
                    b.dial(circuit.clone().with(Protocol::P2p(a_id)))?;
                }
                SwarmEvent::Behaviour(BehaviourEvent::Msg(request_response::Event::Message { message: request_response::Message::Request { request, channel, .. }, .. })) => {
                    let _ = a.behaviour_mut().msg.send_response(channel, ack(request.len()));
                }
                _ => {}
            },
            ev = b.select_next_some() => match { if debug { eprintln!("b: {ev:?}"); } ev } {
                SwarmEvent::ConnectionEstablished { peer_id, endpoint, .. }
                    if peer_id == relay_id && endpoint.get_remote_address().iter().any(|p| matches!(p, Protocol::QuicV1)) => {
                    quic_ms = Some(t0.elapsed().as_millis() as u64);
                }
                SwarmEvent::ConnectionEstablished { peer_id, endpoint, .. } if peer_id == a_id => {
                    if endpoint.is_relayed() && relayed_ms.is_none() {
                        relayed_ms = Some(t0.elapsed().as_millis() as u64);
                    }
                    if !sent {
                        sent = true;
                        let mut m = vec![K_PING];
                        m.extend_from_slice(b"salam");
                        b.behaviour_mut().msg.send_request(&a_id, m);
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Dcutr(e)) => {
                    direct = match e.result {
                        Ok(_) => json!({ "ok": true, "ms": t0.elapsed().as_millis() as u64 }),
                        Err(err) => json!({ "ok": false, "error": err.to_string() }),
                    };
                }
                SwarmEvent::Behaviour(BehaviourEvent::Msg(request_response::Event::Message { message: request_response::Message::Response { response, .. }, .. })) => {
                    if acked(&response) == Some(6) {
                        answer_ms = Some(t0.elapsed().as_millis() as u64);
                    }
                }
                _ => {}
            },
        }
        if answer_ms.is_some() && !direct.is_null() {
            break;
        }
    }
    relay_task.abort();
    out["quic_to_relay_ms"] = json!(quic_ms);
    out["reservation_ms"] = json!(reserved_ms);
    out["relayed_connection_ms"] = json!(relayed_ms);
    out["dcutr_direct"] = direct;
    out["message_answered_ms"] = json!(answer_ms);
    out["ok"] = json!(reserved_ms.is_some() && relayed_ms.is_some() && answer_ms.is_some());
    Ok(out)
}

// ---- two phones through the home relay ----

/// One measurement cycle on whatever connection libp2p picks: a small message, then 1 MiB.
/// The result is filed under the path of the connection that actually carried the answer.
struct Cycle {
    id: OutboundRequestId,
    bulk: bool,
    t: Instant,
}

/// `args`: `{"obs": <decoded observer card>, "room": "...", "duration_s": 120}`.
pub async fn chat_run(args: &Value) -> Result<Value> {
    CANCEL.store(false, Ordering::Relaxed);
    let t0 = Instant::now();
    let ms = || t0.elapsed().as_millis() as u64;
    let obs = &args["obs"];
    let p2p = obs.get("p2p").filter(|v| v.is_object()).ok_or_else(|| anyhow!("the observer card has no libp2p relay: run the new mlm-observer on the PC and scan its card again"))?;
    let relay_id: PeerId = p2p["peer"].as_str().unwrap_or("").parse().context("relay peer id in the card")?;
    let port = p2p["port"].as_u64().unwrap_or(DEFAULT_RELAY_PORT as u64) as u16;
    let ips: Vec<IpAddr> = obs["addrs"].as_array().map(|a| a.iter().filter_map(|v| v.as_str()?.trim().parse().ok()).collect()).unwrap_or_default();
    let ip = ips.iter().find(|i| i.is_ipv4()).or(ips.first()).copied().ok_or_else(|| anyhow!("the observer card has no address"))?;
    let base = match ip {
        IpAddr::V4(v) => Multiaddr::empty().with(Protocol::Ip4(v)),
        IpAddr::V6(v) => Multiaddr::empty().with(Protocol::Ip6(v)),
    };
    let relay_tcp = base.clone().with(Protocol::Tcp(port));
    let relay_quic = base.with(Protocol::Udp(port)).with(Protocol::QuicV1);
    let circuit = relay_tcp.clone().with(Protocol::P2p(relay_id)).with(Protocol::P2pCircuit);
    let room = args["room"].as_str().filter(|s| !s.is_empty()).unwrap_or(DEFAULT_ROOM).to_string();
    let room_key = kad::RecordKey::new(&format!("/mlmchat/spike-room/{room}"));
    let duration = Duration::from_secs(args["duration_s"].as_u64().unwrap_or(120).clamp(20, 600));

    let mut s = build(identity::Keypair::generate_ed25519(), false)?;
    let me = *s.local_peer_id();
    for a in ["/ip4/0.0.0.0/tcp/0", "/ip4/0.0.0.0/udp/0/quic-v1", "/ip6/::/tcp/0", "/ip6/::/udp/0/quic-v1"] {
        if let Err(e) = s.listen_on(a.parse()?) {
            log(format!("listen {a}: {}", scrub(&e.to_string())));
        }
    }
    s.behaviour_mut().kad.add_address(&relay_id, relay_tcp.clone());
    // A phone never serves the DHT here, even once it has a (relayed) external address.
    s.behaviour_mut().kad.set_mode(Some(kad::Mode::Client));
    log(format!("relay {} · dialing over TCP and QUIC", describe(&relay_tcp)["kind"]));
    s.dial(relay_tcp.clone().with(Protocol::P2p(relay_id)))?;
    s.dial(relay_quic.clone().with(Protocol::P2p(relay_id)))?;

    let mut r = json!({ "stack": "rust-libp2p 0.56", "relay_family": describe(&relay_tcp)["family"] });
    let mut to_relay = json!({});
    let mut observed: Vec<Value> = vec![];
    let mut identified = false;
    let mut reservation_ms: Option<u64> = None;
    let mut partner: Option<PeerId> = None;
    let mut found_ms: Option<u64> = None;
    let mut dialed: HashSet<PeerId> = HashSet::new();
    let mut path_of: HashMap<ConnectionId, &'static str> = HashMap::new();
    let mut open: HashMap<ConnectionId, &'static str> = HashMap::new();
    let mut relayed_ms: Option<u64> = None;
    let mut direct_ms: Option<u64> = None;
    let mut direct_info = Value::Null;
    let mut dcutr = Value::Null;
    let mut paths = json!({});
    let mut cycle: Option<Cycle> = None;
    let mut cycles = 0;
    let mut finishing: Option<Instant> = None;
    let mut partner_done = false;
    let mut errors: Vec<String> = vec![];

    let debug = std::env::var("SPIKE_DEBUG").is_ok();
    let mut tick = tokio::time::interval(Duration::from_secs(3));
    let deadline = tokio::time::sleep(duration);
    tokio::pin!(deadline);
    let timed_out = loop {
        // What to do next, decided before waiting for the next event.
        if let (Some(p), None, None) = (partner, &cycle, finishing) {
            let has_direct = open.values().any(|k| *k == "direct");
            let has_any = !open.is_empty();
            let complete = |k: &str| paths.get(k).is_some_and(|v| v.get("message_rtt_ms").is_some() && v.get("upload_1mib_ms").is_some());
            let (relay_done, direct_done) = (complete("relay"), complete("direct"));
            if has_any && cycles < 4 && ((!relay_done && !direct_done && !has_direct) || (has_direct && !direct_done)) {
                if has_direct {
                    // Close the relayed connections so the next cycle must use the direct one.
                    for (id, k) in open.clone() {
                        if k == "relay" {
                            s.close_connection(id);
                        }
                    }
                }
                cycles += 1;
                let id = s.behaviour_mut().msg.send_request(&p, payload(K_PING, 6));
                cycle = Some(Cycle { id, bulk: false, t: Instant::now() });
            } else if direct_done
                || cycles >= 4
                || (relay_done && (dcutr.get("ok") == Some(&json!(false)) || relayed_ms.is_some_and(|t| ms() > t + 30_000)))
            {
                log("done; telling the other phone");
                s.behaviour_mut().msg.send_request(&p, vec![K_DONE]);
                finishing = Some(Instant::now());
            }
        }
        if let Some(f) = finishing {
            // The other phone may still be timing its own 1 MiB: keep answering it for a while.
            if partner_done || f.elapsed() > Duration::from_secs(30) {
                break false;
            }
        }
        tokio::select! {
            _ = &mut deadline => break true,
            _ = tick.tick() => {
                if CANCEL.load(Ordering::Relaxed) {
                    errors.push("cancelled".into());
                    break false;
                }
                if identified && partner.is_none() {
                    s.behaviour_mut().kad.get_providers(room_key.clone());
                }
            }
            ev = s.select_next_some() => match { if debug { eprintln!("{}: {}", &me.to_string()[46..], scrub(&format!("{ev:?}"))); } ev } {
                SwarmEvent::ConnectionEstablished { peer_id, connection_id, endpoint, .. } if peer_id == relay_id => {
                    let t = transport_of(endpoint.get_remote_address());
                    if to_relay.get(t).is_none() {
                        to_relay[t] = json!({ "ok": true, "ms": ms() });
                        log(format!("relay connected over {t} in {} ms", ms()));
                    }
                    let _ = connection_id;
                }
                SwarmEvent::OutgoingConnectionError { peer_id, error, .. } => {
                    if let DialError::Transport(v) = &error {
                        for (a, e) in v {
                            let t = transport_of(a);
                            let msg = scrub(&e.to_string());
                            if peer_id == Some(relay_id) && to_relay.get(t).is_none() {
                                to_relay[t] = json!({ "ok": false, "error": msg });
                            }
                            log(format!("dial {t} failed: {msg}"));
                        }
                    } else {
                        log(format!("dial failed: {}", scrub(&error.to_string())));
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received { peer_id, info, .. })) if peer_id == relay_id => {
                    observed.push(describe(&info.observed_addr));
                    if !identified {
                        identified = true;
                        s.listen_on(circuit.clone())?;
                        s.behaviour_mut().kad.get_providers(room_key.clone());
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::RelayClient(relay::client::Event::ReservationReqAccepted { renewal, .. })) => {
                    if !renewal && reservation_ms.is_none() {
                        reservation_ms = Some(ms());
                        log(format!("relay reservation in {} ms; looking for the other phone", ms()));
                        // A provider record without an address is dropped by the relay's
                        // Kademlia, so publish only now, with the relayed address as ours.
                        s.add_external_address(circuit.clone().with(Protocol::P2p(me)));
                        if let Err(e) = s.behaviour_mut().kad.start_providing(room_key.clone()) {
                            errors.push(format!("start_providing: {e}"));
                        }
                    }
                }
                SwarmEvent::ListenerClosed { addresses, reason, .. } if addresses.iter().any(|a| transport_of(a) == "relay") || reason.is_err() => {
                    if let Err(e) = reason {
                        errors.push(format!("relay listener closed: {}", scrub(&e.to_string())));
                    }
                }
                SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::OutboundQueryProgressed { result, .. })) => match result {
                    kad::QueryResult::GetProviders(Ok(kad::GetProvidersOk::FoundProviders { providers, .. })) => {
                        for p in providers {
                            if p == me || p == relay_id || partner.is_some() {
                                continue;
                            }
                            found_ms.get_or_insert(ms());
                            // One side dials, so the two phones do not race two circuits.
                            if me.to_bytes() < p.to_bytes() && dialed.insert(p) {
                                log("found the other phone; dialing it through the relay");
                                let _ = s.dial(circuit.clone().with(Protocol::P2p(p)));
                            } else if dialed.insert(p) {
                                log("found the other phone; waiting for it to dial");
                            }
                        }
                    }
                    kad::QueryResult::StartProviding(Err(e)) => errors.push(format!("start_providing: {e:?}")),
                    _ => {}
                },
                SwarmEvent::ConnectionEstablished { peer_id, connection_id, endpoint, .. } => {
                    if partner.is_some_and(|p| p != peer_id) {
                        continue;
                    }
                    partner = Some(peer_id);
                    found_ms.get_or_insert(ms());
                    let kind = if endpoint.is_relayed() { "relay" } else { "direct" };
                    path_of.insert(connection_id, kind);
                    open.insert(connection_id, kind);
                    if endpoint.is_relayed() {
                        relayed_ms.get_or_insert(ms());
                        log(format!("connected to the other phone through the relay in {} ms", ms()));
                    } else {
                        direct_ms.get_or_insert(ms());
                        let mut d = describe(endpoint.get_remote_address());
                        d["we_dialed"] = json!(endpoint.is_dialer());
                        log(format!("direct connection: {d}"));
                        direct_info = d;
                    }
                }
                SwarmEvent::ConnectionClosed { peer_id, connection_id, .. } if Some(peer_id) == partner => {
                    open.remove(&connection_id);
                }
                SwarmEvent::Behaviour(BehaviourEvent::Dcutr(e)) => {
                    dcutr = match e.result {
                        Ok(_) => json!({ "ok": true, "ms": ms() }),
                        Err(err) => json!({ "ok": false, "ms": ms(), "error": scrub(&err.to_string()) }),
                    };
                    log(format!("DCUtR: {dcutr}"));
                }
                SwarmEvent::Behaviour(BehaviourEvent::Msg(e)) => match e {
                    request_response::Event::Message { message: request_response::Message::Request { request, channel, .. }, .. } => {
                        if request.first() == Some(&K_DONE) {
                            partner_done = true;
                        }
                        let _ = s.behaviour_mut().msg.send_response(channel, ack(request.len()));
                    }
                    request_response::Event::Message { connection_id, message: request_response::Message::Response { request_id, response }, .. } => {
                        if let Some(c) = cycle.as_ref().filter(|c| c.id == request_id) {
                            let path = path_of.get(&connection_id).copied().unwrap_or("?");
                            let took = c.t.elapsed().as_millis() as u64;
                            let p = partner.expect("partner");
                            if !c.bulk {
                                paths[path] = json!({ "message_rtt_ms": took });
                                let id = s.behaviour_mut().msg.send_request(&p, payload(K_BULK, BULK));
                                cycle = Some(Cycle { id, bulk: true, t: Instant::now() });
                            } else {
                                if !paths[path].is_object() {
                                    paths[path] = json!({});
                                }
                                let ok = acked(&response) == Some(BULK as u64);
                                paths[path]["upload_1mib_ms"] = json!(took);
                                paths[path]["upload_mbps"] = json!(((BULK as f64 * 8.0 / 1e6) / (took.max(1) as f64 / 1000.0) * 100.0).round() / 100.0);
                                paths[path]["upload_ok"] = json!(ok);
                                log(format!("{path}: {}", paths[path]));
                                cycle = None;
                            }
                        }
                    }
                    request_response::Event::OutboundFailure { request_id, error, .. } => {
                        if cycle.as_ref().is_some_and(|c| c.id == request_id) {
                            errors.push(format!("message: {}", scrub(&error.to_string())));
                            cycle = None;
                        }
                    }
                    _ => {}
                },
                _ => {}
            },
        }
    };
    if timed_out {
        errors.push(format!("time limit {} s", duration.as_secs()));
    }
    r["to_relay"] = to_relay;
    r["observed_by_relay"] = json!(observed);
    r["reservation_ms"] = json!(reservation_ms);
    r["other_phone_found_ms"] = json!(found_ms);
    r["relayed_connection_ms"] = json!(relayed_ms);
    r["dcutr"] = dcutr;
    r["direct_connection_ms"] = json!(direct_ms);
    r["direct"] = direct_info;
    r["paths"] = paths.clone();
    r["errors"] = json!(errors);
    r["took_ms"] = json!(ms());
    r["ok"] = json!(paths.as_object().is_some_and(|m| !m.is_empty()));
    Ok(r)
}

/// Battery test (PLAN 17.1): stay connected to the home relay with a reservation, as a chat
/// app in the background would, for `duration_s`. Redials after a drop. `args` as for `chat`.
pub async fn hold_run(args: &Value) -> Result<Value> {
    CANCEL.store(false, Ordering::Relaxed);
    let t0 = Instant::now();
    let obs = &args["obs"];
    let p2p = obs.get("p2p").filter(|v| v.is_object()).ok_or_else(|| anyhow!("the observer card has no libp2p relay"))?;
    let relay_id: PeerId = p2p["peer"].as_str().unwrap_or("").parse().context("relay peer id in the card")?;
    let port = p2p["port"].as_u64().unwrap_or(DEFAULT_RELAY_PORT as u64) as u16;
    let ips: Vec<IpAddr> = obs["addrs"].as_array().map(|a| a.iter().filter_map(|v| v.as_str()?.trim().parse().ok()).collect()).unwrap_or_default();
    let ip = ips.iter().find(|i| i.is_ipv4()).or(ips.first()).copied().ok_or_else(|| anyhow!("the observer card has no address"))?;
    let base = match ip {
        IpAddr::V4(v) => Multiaddr::empty().with(Protocol::Ip4(v)),
        IpAddr::V6(v) => Multiaddr::empty().with(Protocol::Ip6(v)),
    };
    let relay_tcp = base.with(Protocol::Tcp(port)).with(Protocol::P2p(relay_id));
    let duration = Duration::from_secs(args["duration_s"].as_u64().unwrap_or(3600).clamp(60, 4 * 3600));

    let mut s = build(identity::Keypair::generate_ed25519(), false)?;
    s.behaviour_mut().kad.set_mode(Some(kad::Mode::Client));
    s.dial(relay_tcp.clone())?;
    let mut connected_since: Option<Instant> = None;
    let mut connected_total = Duration::ZERO;
    let (mut drops, mut dials, mut reservations, mut pings_ok, mut pings_failed) = (0u32, 1u32, 0u32, 0u32, 0u32);
    let mut rtts: Vec<u64> = vec![];
    let mut circuit_open = false;
    let mut redial_at: Option<Instant> = None;
    let mut tick = tokio::time::interval(Duration::from_secs(5));
    let mut last_report = Instant::now();
    loop {
        tokio::select! {
            _ = tick.tick() => {
                if CANCEL.load(Ordering::Relaxed) || t0.elapsed() >= duration {
                    break;
                }
                if redial_at.is_some_and(|t| Instant::now() >= t) {
                    redial_at = None;
                    dials += 1;
                    let _ = s.dial(relay_tcp.clone());
                }
                if last_report.elapsed() >= Duration::from_secs(300) {
                    last_report = Instant::now();
                    log(format!("{} min: drops {drops}, pings {pings_ok} ok / {pings_failed} failed", t0.elapsed().as_secs() / 60));
                }
            }
            ev = s.select_next_some() => match ev {
                SwarmEvent::ConnectionEstablished { peer_id, .. } if peer_id == relay_id => {
                    connected_since.get_or_insert(Instant::now());
                }
                SwarmEvent::ConnectionClosed { peer_id, num_established, .. } if peer_id == relay_id && num_established == 0 => {
                    drops += 1;
                    if let Some(t) = connected_since.take() {
                        connected_total += t.elapsed();
                    }
                    circuit_open = false;
                    redial_at = Some(Instant::now() + Duration::from_secs(5));
                    log(format!("relay connection dropped ({drops})"));
                }
                SwarmEvent::OutgoingConnectionError { peer_id: Some(p), .. } if p == relay_id => {
                    redial_at = Some(Instant::now() + Duration::from_secs(15));
                }
                SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received { peer_id, .. })) if peer_id == relay_id && !circuit_open => {
                    circuit_open = true;
                    let _ = s.listen_on(relay_tcp.clone().with(Protocol::P2pCircuit));
                }
                SwarmEvent::Behaviour(BehaviourEvent::RelayClient(relay::client::Event::ReservationReqAccepted { .. })) => reservations += 1,
                SwarmEvent::Behaviour(BehaviourEvent::Ping(ping::Event { peer, result, .. })) if peer == relay_id => match result {
                    Ok(rtt) => {
                        pings_ok += 1;
                        rtts.push(rtt.as_millis() as u64);
                    }
                    Err(_) => pings_failed += 1,
                },
                _ => {}
            },
        }
    }
    if let Some(t) = connected_since {
        connected_total += t.elapsed();
    }
    rtts.sort_unstable();
    Ok(json!({
        "ok": connected_total.as_secs() > 0,
        "stack": "rust-libp2p 0.56",
        "duration_s": t0.elapsed().as_secs(),
        "connected_s": connected_total.as_secs(),
        "drops": drops,
        "dials": dials,
        "reservations_incl_renewals": reservations,
        "pings_ok": pings_ok,
        "pings_failed": pings_failed,
        "ping_rtt_median_ms": rtts.get(rtts.len() / 2),
        "took_ms": t0.elapsed().as_millis() as u64,
    }))
}

/// Peer id of a fresh identity: the cheapest call that still pulls the whole stack in.
pub fn fresh_peer_id() -> PeerId {
    identity::Keypair::generate_ed25519().public().to_peer_id()
}

fn run_json(cmd: &str) -> String {
    let rt = match tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build() {
        Ok(r) => r,
        Err(e) => return json!({ "ok": false, "error": e.to_string() }).to_string(),
    };
    let r = if cmd.trim_start().starts_with('{') {
        match serde_json::from_str::<Value>(cmd) {
            Ok(v) if v["cmd"] == "chat" => rt.block_on(chat_run(&v)),
            Ok(v) if v["cmd"] == "hold" => rt.block_on(hold_run(&v)),
            Ok(v) => Err(anyhow!("unknown command {}", v["cmd"])),
            Err(e) => Err(anyhow!("bad json: {e}")),
        }
    } else {
        match cmd {
            "loopback" => rt.block_on(loopback_run(20)),
            _ => Ok(json!({ "ok": true, "peer_id": fresh_peer_id().to_string() })),
        }
    };
    let out = r.unwrap_or_else(|e| json!({ "ok": false, "error": scrub(&format!("{e:#}")) })).to_string();
    // Dropping the runtime here would wait for nothing: every task belongs to the swarm above.
    rt.shutdown_timeout(Duration::from_secs(2));
    out
}

/// JNI entry (class com.mlmchat.spike.Spike, `static native String run(String cmd)`):
/// `loopback`, `peer-id`, or a JSON command (`{"cmd":"chat",...}`).
#[no_mangle]
pub extern "system" fn Java_com_mlmchat_spike_Spike_run<'l>(
    mut env: jni::JNIEnv<'l>,
    _c: jni::objects::JClass<'l>,
    cmd: jni::objects::JString<'l>,
) -> jni::sys::jstring {
    let c: String = env.get_string(&cmd).map(|s| s.into()).unwrap_or_default();
    let out = std::panic::catch_unwind(|| run_json(&c)).unwrap_or_else(|_| r#"{"ok":false,"error":"panic"}"#.into());
    env.new_string(out).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_spike_Spike_cancel<'l>(_env: jni::JNIEnv<'l>, _c: jni::objects::JClass<'l>) {
    cancel();
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_spike_Spike_pollLog<'l>(env: jni::JNIEnv<'l>, _c: jni::objects::JClass<'l>) -> jni::sys::jstring {
    let s = std::panic::catch_unwind(drain_log).unwrap_or_default();
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scrub_hides_addresses() {
        let s = scrub("dial /ip4/203.0.113.7/tcp/40200 and /ip6/2001:db8::1/udp/40200 failed: Connection refused");
        assert!(!s.contains("203.0.113.7"), "{s}");
        assert!(!s.contains("2001:db8::1"), "{s}");
        assert!(s.contains("40200") && s.contains("Connection refused"), "{s}");
    }

    #[test]
    fn describe_never_has_the_ip() {
        let a: Multiaddr = "/ip4/192.168.1.5/udp/4001/quic-v1".parse().unwrap();
        let d = describe(&a).to_string();
        assert!(!d.contains("192.168"), "{d}");
        assert!(d.contains("\"lan\"") && d.contains("\"quic\""), "{d}");
    }
}
