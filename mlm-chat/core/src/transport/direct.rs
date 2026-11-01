//! Direct transport (phase 1): TCP on the LAN or direct IPv6 (SPEC 14.7), plus the private LAN
//! beacons (SPEC 14.8). Each connection carries one SPEC 14.7 session (`session.rs`).

use super::session::{self, Conn};
use super::{Hooks, PeerInfo, Sent, Transport, TransportError};
use crate::beacon::Beacon;
use crate::util::now_s;
use crate::UserId;
use socket2::{Domain, Protocol, Socket, Type};
use std::collections::HashMap;
use std::io::ErrorKind;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub use super::session::{frame_id, hello_input, psk, HELLO_CONTEXT, NOISE_PARAMS, PROLOGUE, PSK_INFO, ROLE_INITIATOR, ROLE_RESPONDER};

pub const DEFAULT_DISCOVERY_PORT: u16 = 47731;
pub const MULTICAST_V4: Ipv4Addr = Ipv4Addr::new(239, 255, 77, 77);
const MAX_INBOUND: usize = 32;
/// A LAN sighting is dialled first for this long.
const SIGHTING_TTL: Duration = Duration::from_secs(120);
/// `on_seen` at most this often per contact.
const SEEN_EVERY: Duration = Duration::from_secs(20);
const CONTACTS_REFRESH: Duration = Duration::from_secs(10);
const POLL: Duration = Duration::from_millis(100);
const TARGETS_EVERY: Duration = Duration::from_secs(30);

#[derive(Clone, Debug)]
pub struct DirectConfig {
    /// Where to listen. `[::]:0` = both IPv6 and IPv4, any free port.
    pub listen: SocketAddr,
    /// Addresses put in our card. Empty = found automatically (main LAN IPv4, global IPv6).
    pub advertise: Vec<SocketAddr>,
    /// Where beacons are received; `None` = no LAN discovery.
    pub discovery: Option<SocketAddr>,
    /// Where our beacons go. Empty = IPv4 broadcast and the multicast group, on the discovery port.
    pub beacon_targets: Vec<SocketAddr>,
    pub beacon_every: Duration,
    pub connect_timeout: Duration,
    pub io_timeout: Duration,
}

impl Default for DirectConfig {
    fn default() -> Self {
        DirectConfig {
            listen: SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)),
            advertise: Vec::new(),
            discovery: Some(SocketAddr::from((Ipv4Addr::UNSPECIFIED, DEFAULT_DISCOVERY_PORT))),
            beacon_targets: Vec::new(),
            beacon_every: Duration::from_secs(5),
            connect_timeout: Duration::from_secs(5),
            io_timeout: Duration::from_secs(20),
        }
    }
}

struct Sighting {
    addr: SocketAddr,
    at: Instant,
    told: Instant,
}

pub struct DirectTransport {
    hooks: Arc<dyn Hooks>,
    cfg: DirectConfig,
    local: SocketAddr,
    stop: AtomicBool,
    sightings: Mutex<HashMap<UserId, Sighting>>,
    inbound: AtomicUsize,
    /// The listener and beacon threads; `stop` waits for them, so the port is closed after it.
    threads: Mutex<Vec<std::thread::JoinHandle<()>>>,
}

impl DirectTransport {
    pub fn start(cfg: DirectConfig, hooks: Arc<dyn Hooks>) -> std::io::Result<Arc<DirectTransport>> {
        let listener = match bind_listener(cfg.listen) {
            Ok(l) => l,
            // no IPv6 on this device: IPv4 only, same port
            Err(_) if cfg.listen.ip() == IpAddr::V6(Ipv6Addr::UNSPECIFIED) => bind_listener(SocketAddr::from((Ipv4Addr::UNSPECIFIED, cfg.listen.port())))?,
            Err(e) => return Err(e),
        };
        listener.set_nonblocking(true)?;
        let local = listener.local_addr()?;
        let discovery = match cfg.discovery {
            Some(addr) => match bind_discovery(addr) {
                Ok(s) => Some(s),
                Err(e) => {
                    log::warn!("direct: LAN discovery off ({:?})", e.kind());
                    None
                }
            },
            None => None,
        };
        let t = Arc::new(DirectTransport {
            hooks,
            cfg,
            local,
            stop: AtomicBool::new(false),
            sightings: Mutex::new(HashMap::new()),
            inbound: AtomicUsize::new(0),
            threads: Mutex::new(Vec::new()),
        });

        let mut threads = Vec::new();
        let me = t.clone();
        threads.push(std::thread::Builder::new().name("mlmchat-accept".into()).spawn(move || me.accept_loop(listener))?);
        if let Some(sock) = discovery {
            let me = t.clone();
            threads.push(std::thread::Builder::new().name("mlmchat-beacon-rx".into()).spawn(move || me.beacon_rx(sock))?);
        }
        if t.cfg.discovery.is_some() || !t.cfg.beacon_targets.is_empty() {
            let me = t.clone();
            threads.push(std::thread::Builder::new().name("mlmchat-beacon-tx".into()).spawn(move || me.beacon_tx())?);
        }
        *t.threads.lock().expect("threads") = threads;
        log::info!("direct: listening");
        Ok(t)
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.local
    }

    fn stopped(&self) -> bool {
        self.stop.load(Ordering::Relaxed)
    }

    // ---- outgoing ----

    /// A fresh LAN sighting first, then the card's addresses.
    fn routes(&self, peer: &PeerInfo) -> Vec<(SocketAddr, super::Via)> {
        let mut out: Vec<(SocketAddr, super::Via)> = Vec::new();
        if let Some(s) = self.sightings.lock().expect("sightings").get(&peer.sk) {
            if s.at.elapsed() < SIGHTING_TTL {
                out.push((s.addr, "lan"));
            }
        }
        for a in &peer.addrs {
            if !out.iter().any(|(o, _)| o == a) {
                out.push((*a, "direct"));
            }
        }
        // the node's scope (SPEC 15.10): a card's public IPv6 or an address abroad is no route
        out.retain(|(a, _)| self.hooks.allows(a.ip()));
        out
    }


    fn dial(&self, addr: SocketAddr, peer: &PeerInfo) -> Result<Conn, TransportError> {
        let stream = TcpStream::connect_timeout(&addr, self.cfg.connect_timeout).map_err(|e| {
            log::debug!("direct: connect failed ({:?})", e.kind());
            match e.kind() {
                ErrorKind::TimedOut | ErrorKind::WouldBlock => TransportError::Timeout,
                _ => TransportError::Connect,
            }
        })?;
        prepare(&stream, self.cfg.io_timeout).map_err(|_| TransportError::Connect)?;
        session::initiate(Box::new(stream), &*self.hooks, peer)
    }

    // ---- incoming ----

    fn accept_loop(self: Arc<Self>, listener: TcpListener) {
        while !self.stopped() {
            match listener.accept() {
                Ok((_, from)) if !self.hooks.allows(from.ip()) => {}
                Ok((stream, _)) => {
                    if self.inbound.fetch_add(1, Ordering::SeqCst) >= MAX_INBOUND {
                        self.inbound.fetch_sub(1, Ordering::SeqCst);
                        continue;
                    }
                    let me = self.clone();
                    let spawned = std::thread::Builder::new().name("mlmchat-in".into()).spawn(move || {
                        let r = me.handle_inbound(stream);
                        me.inbound.fetch_sub(1, Ordering::SeqCst);
                        if let Err(e) = r {
                            log::debug!("direct: inbound closed ({})", e.code());
                        }
                    });
                    if spawned.is_err() {
                        self.inbound.fetch_sub(1, Ordering::SeqCst);
                    }
                }
                Err(e) if e.kind() == ErrorKind::WouldBlock => std::thread::sleep(POLL),
                Err(_) => std::thread::sleep(POLL),
            }
        }
    }

    fn handle_inbound(&self, stream: TcpStream) -> Result<(), TransportError> {
        stream.set_nonblocking(false).map_err(|_| TransportError::Connect)?;
        prepare(&stream, self.cfg.io_timeout).map_err(|_| TransportError::Connect)?;
        let hooks = self.hooks.clone();
        session::respond(Box::new(stream), &*self.hooks, &|| self.stopped(), &mut |sk| hooks.on_reached(sk, "direct"))
    }

    // ---- LAN beacons ----

    /// The configured targets, or the broadcast, the multicast group and every interface's subnet
    /// broadcast (a hotspot too, SPEC 16.5).
    fn beacon_targets(&self) -> Vec<SocketAddr> {
        if !self.cfg.beacon_targets.is_empty() {
            return self.cfg.beacon_targets.clone();
        }
        match self.cfg.discovery {
            Some(d) => super::lan::broadcast_targets(d.port()),
            None => Vec::new(),
        }
    }

    fn beacon_tx(self: Arc<Self>) {
        let mut targets = self.beacon_targets();
        let mut targets_at = Instant::now();
        let sock = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok();
        if let Some(s) = &sock {
            let _ = s.set_broadcast(true);
        }
        let mut next = Instant::now();
        while !self.stopped() {
            // interfaces come and go: a hotspot turned on, Wi-Fi joined
            if targets_at.elapsed() > TARGETS_EVERY {
                targets = self.beacon_targets();
                targets_at = Instant::now();
            }
            if Instant::now() >= next {
                let (_, loc) = self.hooks.me();
                let bytes = Beacon::new(&loc, now_s(), self.local.port()).to_bytes();
                if let Some(s) = &sock {
                    for t in &targets {
                        let _ = s.send_to(&bytes, t);
                    }
                }
                next = Instant::now() + self.cfg.beacon_every;
            }
            std::thread::sleep(POLL);
        }
    }

    fn beacon_rx(self: Arc<Self>, sock: UdpSocket) {
        let mut buf = [0u8; 512];
        let mut locs: Vec<(UserId, [u8; 32])> = Vec::new();
        let mut locs_at: Option<Instant> = None;
        while !self.stopped() {
            let (n, src) = match sock.recv_from(&mut buf) {
                Ok(x) => x,
                // read timeout: check the stop flag
                Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => continue,
                Err(_) => {
                    std::thread::sleep(POLL);
                    continue;
                }
            };
            let Some(beacon) = Beacon::from_bytes(&buf[..n]) else { continue };
            if locs_at.is_none_or(|t| t.elapsed() > CONTACTS_REFRESH) {
                locs = self.hooks.contact_locs();
                locs_at = Some(Instant::now());
            }
            let Some(sk) = beacon.recognise(&locs, now_s()) else { continue };
            let addr = SocketAddr::new(src.ip(), beacon.port);
            let tell = {
                let mut map = self.sightings.lock().expect("sightings");
                let now = Instant::now();
                match map.get_mut(&sk) {
                    Some(s) => {
                        let moved = s.addr != addr;
                        s.addr = addr;
                        s.at = now;
                        if moved || s.told.elapsed() > SEEN_EVERY {
                            s.told = now;
                            true
                        } else {
                            false
                        }
                    }
                    None => {
                        map.insert(sk, Sighting { addr, at: now, told: now });
                        true
                    }
                }
            };
            if tell {
                log::debug!("direct: contact seen on the LAN");
                self.hooks.on_seen(sk, "lan");
            }
        }
    }
}

impl Transport for DirectTransport {
    fn name(&self) -> &'static str {
        "direct"
    }

    fn send(&self, peer: &PeerInfo, frames: &[Vec<u8>]) -> Result<Sent, TransportError> {
        if self.stopped() {
            return Err(TransportError::Stopped);
        }
        let mut best = TransportError::NoRoute;
        for (addr, via) in self.routes(peer) {
            match self.dial(addr, peer) {
                Ok(mut conn) => return session::send_frames(&mut conn, frames).map(|verdicts| Sent { verdicts, via }),
                // keep the most telling reason: a wrong peer or failed handshake over a timeout
                Err(e) => {
                    if session::rank(&e) > session::rank(&best) {
                        best = e;
                    }
                }
            }
        }
        Err(best)
    }

    fn has_fresh_route(&self, sk: &UserId) -> bool {
        self.sightings.lock().expect("sightings").get(sk).is_some_and(|s| s.at.elapsed() < SIGHTING_TTL)
    }

    fn advertised_addrs(&self) -> Vec<SocketAddr> {
        if !self.cfg.advertise.is_empty() {
            return self.cfg.advertise.clone();
        }
        detect_addrs(self.local)
    }

    fn stop(&self) {
        self.stop.store(true, Ordering::Relaxed);
        let threads = std::mem::take(&mut *self.threads.lock().expect("threads"));
        let current = std::thread::current().id();
        for h in threads {
            if h.thread().id() != current {
                let _ = h.join();
            }
        }
    }
}

fn prepare(stream: &TcpStream, io_timeout: Duration) -> std::io::Result<()> {
    stream.set_read_timeout(Some(io_timeout))?;
    stream.set_write_timeout(Some(io_timeout))?;
    stream.set_nodelay(true)
}

fn bind_listener(addr: SocketAddr) -> std::io::Result<TcpListener> {
    let domain = if addr.is_ipv6() { Domain::IPV6 } else { Domain::IPV4 };
    let s = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    if addr.is_ipv6() {
        let _ = s.set_only_v6(false);
    }
    // On Windows SO_REUSEADDR lets another program take the port, so only on Unix.
    #[cfg(unix)]
    s.set_reuse_address(true)?;
    s.bind(&addr.into())?;
    s.listen(64)?;
    Ok(s.into())
}

fn bind_discovery(addr: SocketAddr) -> std::io::Result<UdpSocket> {
    let domain = if addr.is_ipv6() { Domain::IPV6 } else { Domain::IPV4 };
    let s = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    // several apps on one device may listen for beacons
    s.set_reuse_address(true)?;
    #[cfg(all(unix, not(any(target_os = "solaris", target_os = "illumos"))))]
    let _ = s.set_reuse_port(true);
    s.set_broadcast(true)?;
    s.bind(&addr.into())?;
    if let IpAddr::V4(ip) = addr.ip() {
        if ip.is_unspecified() {
            let _ = s.join_multicast_v4(&MULTICAST_V4, &Ipv4Addr::UNSPECIFIED);
        }
    }
    s.set_read_timeout(Some(Duration::from_millis(500)))?;
    Ok(s.into())
}

/// The address the routing table would use towards `target`. A UDP "connect" sends nothing.
fn route_ip(bind: SocketAddr, target: SocketAddr) -> Option<IpAddr> {
    let s = UdpSocket::bind(bind).ok()?;
    s.connect(target).ok()?;
    Some(s.local_addr().ok()?.ip())
}

/// Our main LAN IPv4 and global IPv6, with the listening port.
fn detect_addrs(local: SocketAddr) -> Vec<SocketAddr> {
    let ip = local.ip();
    if !ip.is_unspecified() {
        return vec![local];
    }
    let mut out = Vec::new();
    // documentation addresses (RFC 5737, RFC 3849): only the route lookup happens
    if let Some(v4) = route_ip(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)), SocketAddr::from(([192, 0, 2, 1], 9))) {
        if !v4.is_loopback() && !v4.is_unspecified() {
            out.push(SocketAddr::new(v4, local.port()));
        }
    }
    if local.is_ipv6() {
        let target = SocketAddr::from((Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1), 9));
        if let Some(IpAddr::V6(v6)) = route_ip(SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)), target) {
            // global unicast only (2000::/3)
            if v6.segments()[0] & 0xe000 == 0x2000 {
                out.push(SocketAddr::new(IpAddr::V6(v6), local.port()));
            }
        }
    }
    out
}
