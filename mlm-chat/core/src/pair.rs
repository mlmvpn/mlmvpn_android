//! «Add nearby» (SPEC 16.4, T36): people side by side add each other with no network and no QR.
//!
//! While someone has the «add nearby» screen open (two minutes at most) their phone announces a
//! random 8-byte id: over Bluetooth as the Gereh service's data (`0x01 || id`), and over Wi-Fi as
//! a UDP datagram on port 47732 to every broadcast address of the device (the same Wi-Fi, or one
//! phone's hotspot). Nothing in it says who they are, and the id is new each time.
//!
//! Phones in this mode connect (the smaller id dials), run `Noise_NN_25519_ChaChaPoly_SHA256`
//! (snow) and send, encrypted, only the name each person typed for the moment. Asking someone
//! shows four emoji on both screens, from the Noise handshake hash (HKDF-SHA256, info
//! "mlmchat/pair/1/sas"): someone in the middle must run two handshakes and gets other emoji.
//! When both people confirm, each sends its face-to-face card (the QR's link) on the same channel
//! and the node adds it exactly as a scanned QR.
//!
//! Nothing here logs a name, an id, an address or a card: kinds and counts only.

use crate::transport::lan;
use crate::transport::session::Pipe;
use crate::util::random;
use hkdf::Hkdf;
use sha2::Sha256;
use snow::{HandshakeState, TransportState};
use std::collections::HashMap;
use std::io::{ErrorKind, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender, TryRecvError};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const PARAMS: &str = "Noise_NN_25519_ChaChaPoly_SHA256";
pub const PROLOGUE: &[u8] = b"mlmchat/pair/1";
pub const SAS_INFO: &[u8] = b"mlmchat/pair/1/sas";
/// The first bytes on a pairing link, so a Bluetooth link's other side tells it from a session
/// (whose first byte, the high byte of a short length, is 0).
pub const LINK_MAGIC: [u8; 4] = [0xFF, b'P', b'R', b'1'];
/// A Wi-Fi announcement: `0xFF 'P' 0x01 || id (8) || TCP port (u16 BE)`.
pub const ANNOUNCE_HEAD: [u8; 3] = [0xFF, b'P', 1];
pub const ANNOUNCE_LEN: usize = 13;
pub const DEFAULT_PORT: u16 = 47732;
/// Bluetooth service data in this mode: `0x01 || id` (the contact tag of SPEC 16.1 is 8 bytes).
pub const BLE_KIND: u8 = 0x01;
pub const BLE_DATA_LEN: usize = 9;
pub const WINDOW: Duration = Duration::from_secs(120);
pub const MAX_PEERS: usize = 8;
pub const NAME_MAX: usize = 64;

const REC_HELLO: u8 = 1;
const REC_ASK: u8 = 2;
const REC_YES: u8 = 3;
const REC_NO: u8 = 4;
/// Every record is padded to one of these sizes (a card fits the last).
const RECORD_BUCKETS: [usize; 3] = [256, 1024, 4096];
const ANNOUNCE_EVERY: Duration = Duration::from_secs(1);
const TARGETS_EVERY: Duration = Duration::from_secs(10);
const POLL: Duration = Duration::from_millis(100);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
/// The bigger id dials too when no link came from the smaller one by then (only one side saw the
/// other), and a failed dial is retried after `DIAL_RETRY`.
const DIAL_GRACE: Duration = Duration::from_secs(5);
const DIAL_RETRY: Duration = Duration::from_secs(8);

pub type PairId = [u8; 8];

/// The 64 symbols of the confirmation code, in this order (index = 6 bits of the code).
pub const EMOJI: [&str; 64] = [
    "🐶", "🐱", "🦁", "🐎", "🦄", "🦒", "🐘", "🐰", "🐼", "🐓", "🐧", "🐢", "🐟", "🐙", "🦋", "🌷", //
    "🌳", "🌵", "🍄", "🌏", "🌙", "☁️", "🔥", "🍌", "🍎", "🍓", "🌽", "🍕", "🎂", "❤️", "😀", "🤖", //
    "🎩", "👓", "🔧", "⭐", "👍", "☂️", "⌛", "⏰", "🎁", "💡", "📕", "✏️", "📎", "✂️", "🔒", "🔑", //
    "🔨", "☎️", "🏁", "🚂", "🚲", "✈️", "🚀", "🏆", "⚽", "🎸", "🎺", "🔔", "⚓", "🎧", "📁", "📌",
];

/// The confirmation code of a handshake: four indexes into [`EMOJI`] (24 bits).
pub fn sas(handshake_hash: &[u8]) -> [u8; 4] {
    let mut okm = [0u8; 3];
    Hkdf::<Sha256>::new(None, handshake_hash).expand(SAS_INFO, &mut okm).expect("3 bytes is a valid HKDF length");
    let v = u32::from_be_bytes([0, okm[0], okm[1], okm[2]]);
    [((v >> 18) & 63) as u8, ((v >> 12) & 63) as u8, ((v >> 6) & 63) as u8, (v & 63) as u8]
}

pub fn sas_emoji(code: &[u8; 4]) -> String {
    code.iter().map(|i| EMOJI[usize::from(*i & 63)]).collect::<Vec<_>>().join(" ")
}

/// A name as shown: no control characters, trimmed, at most 64 bytes on a character boundary.
pub fn clean_name(name: &str) -> String {
    let s: String = name.chars().filter(|c| !c.is_control()).collect();
    let s = s.trim();
    let mut end = s.len().min(NAME_MAX);
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    s[..end].to_string()
}

pub fn announce_bytes(id: &PairId, port: u16) -> [u8; ANNOUNCE_LEN] {
    let mut out = [0u8; ANNOUNCE_LEN];
    out[..3].copy_from_slice(&ANNOUNCE_HEAD);
    out[3..11].copy_from_slice(id);
    out[11..].copy_from_slice(&port.to_be_bytes());
    out
}

pub fn parse_announce(bytes: &[u8]) -> Option<(PairId, u16)> {
    if bytes.len() != ANNOUNCE_LEN || bytes[..3] != ANNOUNCE_HEAD {
        return None;
    }
    Some((bytes[3..11].try_into().ok()?, u16::from_be_bytes([bytes[11], bytes[12]])))
}

/// What the node gives a pairing session.
pub trait PairHost: Send + Sync + 'static {
    /// A fresh face-to-face card link (the QR's), to give someone the user confirmed.
    fn my_card(&self) -> Option<String>;
    /// Add the card someone gave us, as a scanned QR. False when it is not a valid card.
    fn add_card(&self, link: &str) -> bool;
    /// A Bluetooth link to the device seen at `addr`, reads waiting at most `read_timeout`.
    fn ble_link(&self, addr: &str, read_timeout: Duration) -> Option<Box<dyn Pipe>>;
}

/// What the Bluetooth transport tells a pairing session.
pub trait PairSink: Send + Sync + 'static {
    fn id(&self) -> PairId;
    /// A device in this mode was seen advertising `id`.
    fn seen_ble(&self, addr: &str, id: PairId);
    /// A device opened a pairing link to us; [`LINK_MAGIC`] is already read. Reads on `pipe` must
    /// wait at most a fraction of a second.
    fn incoming(&self, pipe: Box<dyn Pipe>, via: &'static str);
}

#[derive(Clone, Debug)]
pub struct PairConfig {
    /// The name shown to the others for this moment.
    pub name: String,
    /// Where Wi-Fi announcements are received; `None` = Bluetooth only.
    pub udp: Option<SocketAddr>,
    /// Where ours go; empty = every broadcast address of this device, on `udp`'s port.
    pub targets: Vec<SocketAddr>,
    pub window: Duration,
    /// A fixed id (tests); `None` = random.
    pub id: Option<PairId>,
}

impl Default for PairConfig {
    fn default() -> Self {
        PairConfig {
            name: String::new(),
            udp: Some(SocketAddr::from((Ipv4Addr::UNSPECIFIED, DEFAULT_PORT))),
            targets: Vec::new(),
            window: WINDOW,
            id: None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PeerState {
    /// In the list; nobody asked yet.
    Found,
    /// We asked; the code is on both screens; our confirmation is awaited.
    Outgoing,
    /// They asked; the code is on both screens; our confirmation is awaited.
    Incoming,
    /// We confirmed; their confirmation is awaited.
    Waiting,
    /// Both confirmed and their card was added.
    Done,
    /// One side said no.
    Declined,
    /// The link was lost, or their card was not valid.
    Failed,
}

impl PeerState {
    pub fn code(&self) -> &'static str {
        match self {
            PeerState::Found => "found",
            PeerState::Outgoing => "outgoing",
            PeerState::Incoming => "incoming",
            PeerState::Waiting => "waiting",
            PeerState::Done => "done",
            PeerState::Declined => "declined",
            PeerState::Failed => "failed",
        }
    }
}

/// One person in the list.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairPeer {
    /// Their id for this moment, hex (what the app passes back to `ask`, `confirm`, `decline`).
    pub id: String,
    pub name: String,
    pub state: PeerState,
    /// The confirmation code, once someone asked.
    pub sas: Option<[u8; 4]>,
    /// `wifi` or `ble`.
    pub via: &'static str,
}

#[derive(Default)]
struct Peer {
    name: Option<String>,
    hh_sas: Option<[u8; 4]>,
    asked_by_me: bool,
    asked_by_them: bool,
    yes_by_me: bool,
    their_card: Option<String>,
    added: bool,
    declined: bool,
    failed: bool,
    /// The open link: records to send on it, and which link it is.
    out: Option<Sender<Vec<u8>>>,
    link_no: u64,
    link_outbound: bool,
    linking: bool,
    via: &'static str,
    wifi: Option<SocketAddr>,
    ble: Option<String>,
    first_seen: Option<Instant>,
    last_dial: Option<Instant>,
}

impl Peer {
    fn state(&self) -> PeerState {
        if self.added {
            PeerState::Done
        } else if self.declined {
            PeerState::Declined
        } else if self.failed {
            PeerState::Failed
        } else if self.yes_by_me {
            PeerState::Waiting
        } else if self.asked_by_me {
            PeerState::Outgoing
        } else if self.asked_by_them {
            PeerState::Incoming
        } else {
            PeerState::Found
        }
    }

    fn finished(&self) -> bool {
        self.added || self.declined
    }
}

struct Shared {
    id: PairId,
    name: String,
    host: Arc<dyn PairHost>,
    deadline: Instant,
    stopped: AtomicBool,
    peers: Mutex<HashMap<PairId, Peer>>,
    next_link: AtomicU64,
    tcp_port: u16,
}

impl Shared {
    fn over(&self) -> bool {
        self.stopped.load(Ordering::Relaxed) || Instant::now() >= self.deadline
    }
}

/// One «add nearby» screen, open for at most [`WINDOW`].
pub struct PairSession {
    shared: Arc<Shared>,
}

impl PairSession {
    pub fn start(cfg: PairConfig, host: Arc<dyn PairHost>) -> std::io::Result<Arc<PairSession>> {
        let id = cfg.id.unwrap_or_else(random::<8>);
        let (listener, udp) = match cfg.udp {
            Some(addr) => {
                let l = TcpListener::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))?;
                l.set_nonblocking(true)?;
                let u = bind_udp(addr)?;
                (Some(l), Some(u))
            }
            None => (None, None),
        };
        let tcp_port = listener.as_ref().and_then(|l| l.local_addr().ok()).map(|a| a.port()).unwrap_or(0);
        let shared = Arc::new(Shared {
            id,
            name: clean_name(&cfg.name),
            host,
            deadline: Instant::now() + cfg.window,
            stopped: AtomicBool::new(false),
            peers: Mutex::new(HashMap::new()),
            next_link: AtomicU64::new(1),
            tcp_port,
        });
        let s = Arc::new(PairSession { shared: shared.clone() });
        let targets = cfg.targets.clone();
        let port = cfg.udp.map(|a| a.port()).unwrap_or(0);
        std::thread::Builder::new().name("mlmchat-pair".into()).spawn(move || main_loop(shared, listener, udp, targets, port))?;
        log::info!("pair: open");
        Ok(s)
    }

    /// Everyone in the list (those whose name arrived), in a stable order.
    pub fn peers(&self) -> Vec<PairPeer> {
        let map = self.shared.peers.lock().expect("peers");
        let mut out: Vec<PairPeer> = map
            .iter()
            .filter_map(|(id, p)| {
                let name = p.name.clone()?;
                let st = p.state();
                let sas = if p.asked_by_me || p.asked_by_them { p.hh_sas } else { None };
                Some(PairPeer { id: hex::encode(id), name, state: st, sas, via: p.via })
            })
            .collect();
        out.sort_by(|a, b| a.name.cmp(&b.name).then(a.id.cmp(&b.id)));
        out
    }

    /// Seconds left before the screen closes itself.
    pub fn left_s(&self) -> u64 {
        self.shared.deadline.saturating_duration_since(Instant::now()).as_secs()
    }

    pub fn is_open(&self) -> bool {
        !self.shared.over()
    }

    /// Where links over Wi-Fi come in (0 = Bluetooth only).
    pub fn tcp_port(&self) -> u16 {
        self.shared.tcp_port
    }

    /// Ask someone in the list: both screens show the code.
    pub fn ask(&self, id: &str) -> bool {
        self.with_peer(id, |p| {
            if p.state() != PeerState::Found {
                return None;
            }
            p.asked_by_me = true;
            Some(record(REC_ASK, &[]))
        })
    }

    /// The person confirmed the code matches: give them our card (and add theirs once it is here).
    pub fn confirm(&self, id: &str) -> bool {
        let Some(card) = self.shared.host.my_card() else { return false };
        let sent = self.with_peer(id, |p| {
            if !matches!(p.state(), PeerState::Outgoing | PeerState::Incoming) {
                return None;
            }
            p.yes_by_me = true;
            Some(record(REC_YES, card.as_bytes()))
        });
        if sent {
            self.finish_if_both(id);
        }
        sent
    }

    pub fn decline(&self, id: &str) -> bool {
        self.with_peer(id, |p| {
            if p.finished() {
                return None;
            }
            p.declined = true;
            Some(record(REC_NO, &[]))
        })
    }

    pub fn stop(&self) {
        if !self.shared.stopped.swap(true, Ordering::SeqCst) {
            log::info!("pair: closed");
        }
    }

    fn with_peer(&self, id: &str, f: impl FnOnce(&mut Peer) -> Option<Vec<u8>>) -> bool {
        let Some(rid) = hex::decode(id).ok().and_then(|b| PairId::try_from(b.as_slice()).ok()) else { return false };
        let mut map = self.shared.peers.lock().expect("peers");
        let Some(p) = map.get_mut(&rid) else { return false };
        let Some(out) = p.out.clone() else { return false };
        match f(p) {
            Some(rec) => out.send(rec).is_ok(),
            None => false,
        }
    }

    fn finish_if_both(&self, id: &str) {
        let Some(rid) = hex::decode(id).ok().and_then(|b| PairId::try_from(b.as_slice()).ok()) else { return };
        finish_if_both(&self.shared, &rid);
    }
}

impl Drop for PairSession {
    fn drop(&mut self) {
        self.stop();
    }
}

impl PairSink for PairSession {
    fn id(&self) -> PairId {
        self.shared.id
    }

    fn seen_ble(&self, addr: &str, id: PairId) {
        if id == self.shared.id || self.shared.over() {
            return;
        }
        let mut map = self.shared.peers.lock().expect("peers");
        if !map.contains_key(&id) && map.len() >= MAX_PEERS {
            return;
        }
        let p = map.entry(id).or_default();
        p.ble = Some(addr.to_string());
        p.first_seen.get_or_insert_with(Instant::now);
    }

    fn incoming(&self, pipe: Box<dyn Pipe>, via: &'static str) {
        let shared = self.shared.clone();
        let _ = std::thread::Builder::new().name("mlmchat-pair-in".into()).spawn(move || run_link(shared, pipe, None, via));
    }
}

/// Add their card once both people confirmed.
fn finish_if_both(shared: &Arc<Shared>, rid: &PairId) {
    let card = {
        let mut map = shared.peers.lock().expect("peers");
        let Some(p) = map.get_mut(rid) else { return };
        if !p.yes_by_me || p.added || p.declined {
            return;
        }
        match p.their_card.take() {
            Some(c) => c,
            None => return,
        }
    };
    let ok = shared.host.add_card(&card);
    let mut map = shared.peers.lock().expect("peers");
    if let Some(p) = map.get_mut(rid) {
        if ok {
            p.added = true;
        } else {
            p.failed = true;
        }
    }
    log::info!("pair: {}", if ok { "contact added" } else { "their card was refused" });
}

fn bind_udp(addr: SocketAddr) -> std::io::Result<UdpSocket> {
    use socket2::{Domain, Protocol, Socket, Type};
    let s = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    s.set_reuse_address(true)?;
    #[cfg(all(unix, not(any(target_os = "solaris", target_os = "illumos"))))]
    let _ = s.set_reuse_port(true);
    s.set_broadcast(true)?;
    s.bind(&addr.into())?;
    if addr.ip().is_unspecified() {
        let _ = s.join_multicast_v4(&lan::MULTICAST_V4, &Ipv4Addr::UNSPECIFIED);
    }
    s.set_nonblocking(true)?;
    Ok(s.into())
}

fn main_loop(shared: Arc<Shared>, listener: Option<TcpListener>, udp: Option<UdpSocket>, fixed: Vec<SocketAddr>, port: u16) {
    let announce = announce_bytes(&shared.id, shared.tcp_port);
    let mut targets: Vec<SocketAddr> = Vec::new();
    let mut targets_at: Option<Instant> = None;
    let mut next = Instant::now();
    let mut buf = [0u8; 64];
    while !shared.over() {
        if let Some(u) = &udp {
            if Instant::now() >= next {
                if targets_at.is_none_or(|t| t.elapsed() > TARGETS_EVERY) {
                    targets = if fixed.is_empty() { lan::broadcast_targets(port) } else { fixed.clone() };
                    targets_at = Some(Instant::now());
                }
                for t in &targets {
                    let _ = u.send_to(&announce, t);
                }
                next = Instant::now() + ANNOUNCE_EVERY;
            }
            while let Ok((n, src)) = u.recv_from(&mut buf) {
                let Some((rid, tcp)) = parse_announce(&buf[..n]) else { continue };
                if rid == shared.id || tcp == 0 {
                    continue;
                }
                let mut map = shared.peers.lock().expect("peers");
                if !map.contains_key(&rid) && map.len() >= MAX_PEERS {
                    continue;
                }
                let p = map.entry(rid).or_default();
                p.wifi = Some(SocketAddr::new(src.ip(), tcp));
                p.first_seen.get_or_insert_with(Instant::now);
            }
        }
        if let Some(l) = &listener {
            while let Ok((stream, _)) = l.accept() {
                let shared = shared.clone();
                let _ = std::thread::Builder::new().name("mlmchat-pair-in".into()).spawn(move || {
                    if let Ok(pipe) = tcp_pipe(stream) {
                        let mut pipe: Box<dyn Pipe> = Box::new(pipe);
                        if read_magic(&mut *pipe) {
                            run_link(shared, pipe, None, "wifi");
                        }
                    }
                });
            }
        }
        dial_due(&shared);
        std::thread::sleep(POLL);
    }
    log::info!("pair: window over");
}

/// Start a link to everyone seen and not linked: the smaller id dials at once, the bigger one only
/// after a grace period (in case only it saw the other).
fn dial_due(shared: &Arc<Shared>) {
    let due: Vec<(PairId, Option<SocketAddr>, Option<String>)> = {
        let mut map = shared.peers.lock().expect("peers");
        map.iter_mut()
            .filter(|(rid, p)| {
                p.out.is_none()
                    && !p.linking
                    && !p.finished()
                    && (shared.id < **rid || p.first_seen.is_some_and(|t| t.elapsed() > DIAL_GRACE))
                    && p.last_dial.is_none_or(|t| t.elapsed() > DIAL_RETRY)
            })
            .map(|(rid, p)| {
                p.linking = true;
                p.last_dial = Some(Instant::now());
                (*rid, p.wifi, p.ble.clone())
            })
            .collect()
    };
    for (rid, wifi, ble) in due {
        let shared = shared.clone();
        let _ = std::thread::Builder::new().name("mlmchat-pair-out".into()).spawn(move || {
            let mut opened: Option<(Box<dyn Pipe>, &'static str)> = None;
            if let Some(addr) = wifi {
                if let Ok(s) = TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT) {
                    if let Ok(p) = tcp_pipe(s) {
                        opened = Some((Box::new(p), "wifi"));
                    }
                }
            }
            if opened.is_none() {
                if let Some(addr) = ble {
                    if let Some(p) = shared.host.ble_link(&addr, POLL) {
                        opened = Some((p, "ble"));
                    }
                }
            }
            match opened {
                Some((mut pipe, via)) => {
                    if pipe.write_all(&LINK_MAGIC).and_then(|_| pipe.flush()).is_ok() {
                        run_link(shared.clone(), pipe, Some(rid), via);
                    }
                }
                None => log::debug!("pair: could not reach a device"),
            }
            if let Some(p) = shared.peers.lock().expect("peers").get_mut(&rid) {
                p.linking = false;
            }
        });
    }
}

fn tcp_pipe(s: TcpStream) -> std::io::Result<TcpStream> {
    s.set_nonblocking(false)?;
    s.set_read_timeout(Some(POLL))?;
    s.set_write_timeout(Some(Duration::from_secs(5)))?;
    s.set_nodelay(true)?;
    Ok(s)
}

fn read_magic(pipe: &mut dyn Pipe) -> bool {
    let mut got = [0u8; 4];
    let mut n = 0;
    let end = Instant::now() + Duration::from_secs(5);
    while n < 4 && Instant::now() < end {
        match pipe.read(&mut got[n..]) {
            Ok(0) => return false,
            Ok(k) => n += k,
            Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
            Err(_) => return false,
        }
    }
    n == 4 && got == LINK_MAGIC
}

/// A record: type, length, body, zeros up to a bucket.
fn record(kind: u8, body: &[u8]) -> Vec<u8> {
    let need = 3 + body.len();
    let size = RECORD_BUCKETS.iter().copied().find(|b| *b >= need).unwrap_or(need);
    let mut r = Vec::with_capacity(size);
    r.push(kind);
    r.extend_from_slice(&(body.len() as u16).to_be_bytes());
    r.extend_from_slice(body);
    r.resize(size, 0);
    r
}

fn parse_record(r: &[u8]) -> Option<(u8, &[u8])> {
    if r.len() < 3 || !RECORD_BUCKETS.contains(&r.len()) {
        return None;
    }
    let len = usize::from(u16::from_be_bytes([r[1], r[2]]));
    r.get(3..3 + len).map(|b| (r[0], b))
}

/// Noise messages with a 2-byte big-endian length, read without losing a partial message when a
/// read times out.
struct Link {
    pipe: Box<dyn Pipe>,
    inbuf: Vec<u8>,
    buf: Vec<u8>,
}

impl Link {
    fn send(&mut self, msg: &[u8]) -> std::io::Result<()> {
        let mut out = Vec::with_capacity(2 + msg.len());
        out.extend_from_slice(&(msg.len() as u16).to_be_bytes());
        out.extend_from_slice(msg);
        self.pipe.write_all(&out)?;
        self.pipe.flush()
    }

    /// A whole message if one is here (or arrives within one read's timeout); `Ok(None)` if not
    /// yet; `Err` when the link closed.
    fn poll(&mut self) -> std::io::Result<Option<Vec<u8>>> {
        if let Some(m) = self.take() {
            return Ok(Some(m));
        }
        let mut chunk = [0u8; 2048];
        match self.pipe.read(&mut chunk) {
            Ok(0) => Err(std::io::Error::new(ErrorKind::UnexpectedEof, "closed")),
            Ok(n) => {
                self.inbuf.extend_from_slice(&chunk[..n]);
                Ok(self.take())
            }
            Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => Ok(None),
            Err(e) => Err(e),
        }
    }

    fn take(&mut self) -> Option<Vec<u8>> {
        if self.inbuf.len() < 2 {
            return None;
        }
        let len = usize::from(u16::from_be_bytes([self.inbuf[0], self.inbuf[1]]));
        if self.inbuf.len() < 2 + len {
            return None;
        }
        let m = self.inbuf[2..2 + len].to_vec();
        self.inbuf.drain(..2 + len);
        Some(m)
    }

    fn wait(&mut self, until: Instant) -> std::io::Result<Vec<u8>> {
        loop {
            if let Some(m) = self.poll()? {
                return Ok(m);
            }
            if Instant::now() >= until {
                return Err(std::io::Error::new(ErrorKind::TimedOut, "handshake timed out"));
            }
        }
    }

    /// `-> e ; <- e, ee`: the handshake hash, and the transport state.
    fn handshake(&mut self, initiator: bool) -> Option<(Vec<u8>, TransportState)> {
        let params = PARAMS.parse().ok()?;
        let b = snow::Builder::new(params).prologue(PROLOGUE).ok()?;
        let mut hs: HandshakeState = if initiator { b.build_initiator().ok()? } else { b.build_responder().ok()? };
        let until = Instant::now() + HANDSHAKE_TIMEOUT;
        let mut scratch = [0u8; 64];
        if initiator {
            let n = hs.write_message(&[], &mut self.buf).ok()?;
            let m = self.buf[..n].to_vec();
            self.send(&m).ok()?;
            let reply = self.wait(until).ok()?;
            hs.read_message(&reply, &mut scratch).ok()?;
        } else {
            let first = self.wait(until).ok()?;
            hs.read_message(&first, &mut scratch).ok()?;
            let n = hs.write_message(&[], &mut self.buf).ok()?;
            let m = self.buf[..n].to_vec();
            self.send(&m).ok()?;
        }
        if !hs.is_handshake_finished() {
            return None;
        }
        let hh = hs.get_handshake_hash().to_vec();
        Some((hh, hs.into_transport_mode().ok()?))
    }

    fn send_record(&mut self, noise: &mut TransportState, rec: &[u8]) -> std::io::Result<()> {
        let n = noise.write_message(rec, &mut self.buf).map_err(|_| std::io::Error::other("encrypt"))?;
        let m = self.buf[..n].to_vec();
        self.send(&m)
    }

    fn open_record(&mut self, noise: &mut TransportState, msg: &[u8]) -> Option<Vec<u8>> {
        let n = noise.read_message(msg, &mut self.buf).ok()?;
        Some(self.buf[..n].to_vec())
    }
}

/// One link, from handshake to the end: hellos, then the records the user's choices produce.
/// `expect` is the id we dialled (outbound), none for a link someone opened to us.
fn run_link(shared: Arc<Shared>, pipe: Box<dyn Pipe>, expect: Option<PairId>, via: &'static str) {
    let outbound = expect.is_some();
    let mut link = Link { pipe, inbuf: Vec::new(), buf: vec![0u8; 65535] };
    let Some((hh, mut noise)) = link.handshake(outbound) else {
        log::debug!("pair: handshake failed");
        return;
    };
    let mut hello = Vec::with_capacity(9 + shared.name.len());
    hello.extend_from_slice(&shared.id);
    hello.extend_from_slice(shared.name.as_bytes());
    if link.send_record(&mut noise, &record(REC_HELLO, &hello)).is_err() {
        return;
    }
    let until = Instant::now() + HANDSHAKE_TIMEOUT;
    let Some((rid, name)) = link
        .wait(until)
        .ok()
        .and_then(|m| link.open_record(&mut noise, &m))
        .and_then(|r| parse_record(&r).filter(|(k, b)| *k == REC_HELLO && b.len() >= 8).map(|(_, b)| (b[..8].try_into().ok(), clean_name(&String::from_utf8_lossy(&b[8..])))))
        .and_then(|(rid, name)| rid.map(|r: PairId| (r, name)))
    else {
        log::debug!("pair: no hello");
        return;
    };
    if rid == shared.id || expect.is_some_and(|e| e != rid) {
        return;
    }
    // One link per person. When both dialled, the one the smaller id opened stays on both sides.
    let (tx, rx) = channel::<Vec<u8>>();
    let link_no = shared.next_link.fetch_add(1, Ordering::Relaxed);
    {
        let mut map = shared.peers.lock().expect("peers");
        if !map.contains_key(&rid) && map.len() >= MAX_PEERS {
            return;
        }
        let p = map.entry(rid).or_default();
        let keep_new = match p.out {
            None => true,
            Some(_) => outbound == (shared.id < rid),
        };
        if !keep_new {
            return;
        }
        p.out = Some(tx);
        p.link_no = link_no;
        p.link_outbound = outbound;
        p.name = Some(name);
        p.hh_sas = Some(sas(&hh));
        p.via = via;
        p.failed = false;
        p.first_seen.get_or_insert_with(Instant::now);
    }
    log::info!("pair: someone is in the list (via {via})");
    let end = serve(&shared, &mut link, &mut noise, &rx, &rid);
    let mut map = shared.peers.lock().expect("peers");
    if let Some(p) = map.get_mut(&rid) {
        if p.link_no == link_no {
            p.out = None;
            if !p.finished() && end.is_err() && !shared.over() {
                p.failed = true;
            }
        }
    }
}

/// The record loop: what the user chose goes out, what the other side chose comes in. Ends when
/// both cards are through, someone said no, the window closed or the link dropped.
fn serve(shared: &Arc<Shared>, link: &mut Link, noise: &mut TransportState, rx: &Receiver<Vec<u8>>, rid: &PairId) -> Result<(), ()> {
    let mut sent_yes = false;
    let mut got_yes = false;
    loop {
        if shared.over() {
            return Ok(());
        }
        loop {
            match rx.try_recv() {
                Ok(rec) => {
                    let kind = rec[0];
                    link.send_record(noise, &rec).map_err(|_| ())?;
                    if kind == REC_YES {
                        sent_yes = true;
                    }
                    if kind == REC_NO {
                        return Ok(());
                    }
                }
                Err(TryRecvError::Empty) => break,
                // another link took this person's place
                Err(TryRecvError::Disconnected) => return Ok(()),
            }
        }
        if sent_yes && got_yes {
            // let the last record leave before closing
            std::thread::sleep(Duration::from_millis(300));
            return Ok(());
        }
        let Some(msg) = link.poll().map_err(|_| ())? else { continue };
        let rec = link.open_record(noise, &msg).ok_or(())?;
        let (kind, body) = parse_record(&rec).ok_or(())?;
        let mut map = shared.peers.lock().expect("peers");
        let Some(p) = map.get_mut(rid) else { return Ok(()) };
        match kind {
            REC_ASK => p.asked_by_them = true,
            REC_YES => {
                p.asked_by_them = true;
                p.their_card = std::str::from_utf8(body).ok().map(str::to_string);
                got_yes = true;
                drop(map);
                finish_if_both(shared, rid);
            }
            REC_NO => {
                p.declined = true;
                return Ok(());
            }
            _ => return Err(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn records_pad_and_parse() {
        let r = record(REC_HELLO, b"abc");
        assert_eq!(r.len(), 256);
        assert_eq!(parse_record(&r), Some((REC_HELLO, &b"abc"[..])));
        let big = vec![7u8; 1500];
        let r = record(REC_YES, &big);
        assert_eq!(r.len(), 4096);
        assert_eq!(parse_record(&r).map(|(k, b)| (k, b.len())), Some((REC_YES, 1500)));
        assert_eq!(parse_record(&r[..300]), None);
    }

    #[test]
    fn announcements_carry_no_identity() {
        let id = [1, 2, 3, 4, 5, 6, 7, 8];
        let a = announce_bytes(&id, 40001);
        assert_eq!(a.len(), ANNOUNCE_LEN);
        assert_eq!(parse_announce(&a), Some((id, 40001)));
        assert_eq!(parse_announce(&a[..12]), None);
        // never mistaken for a LAN beacon (at least 6 + 16 bytes)
        assert!(crate::beacon::Beacon::from_bytes(&a).is_none());
    }

    #[test]
    fn names_are_cleaned() {
        assert_eq!(clean_name("  Ali\n\u{7}  "), "Ali");
        let long = "ک".repeat(40); // 80 bytes
        let c = clean_name(&long);
        assert!(c.len() <= NAME_MAX && c.chars().all(|ch| ch == 'ک'));
    }

    #[test]
    fn the_code_is_24_bits_of_the_handshake() {
        let a = sas(&[1u8; 32]);
        assert_eq!(a, sas(&[1u8; 32]));
        assert_ne!(a, sas(&[2u8; 32]));
        assert!(a.iter().all(|i| *i < 64));
        assert_eq!(sas_emoji(&a).split(' ').count(), 4);
        let mut uniq = EMOJI.to_vec();
        uniq.sort();
        uniq.dedup();
        assert_eq!(uniq.len(), 64);
    }
}
