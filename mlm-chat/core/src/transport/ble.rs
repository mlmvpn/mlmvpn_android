//! Bluetooth LE, step 1 (SPEC 16): a contact right beside us, with no network at all.
//!
//! The radio itself (advertising, scanning, GATT) belongs to the platform: on Android it is the
//! core package's Kotlin code. This module is the part every platform shares:
//!
//! - **Private discovery** (SPEC 16.1): the advertised tag is the first 8 bytes of
//!   HMAC-SHA256(loc, "mlmchat/ble/1" || epoch), epoch = unix seconds / 600. Only contacts, who
//!   hold our `loc`, recognise it; to anyone else it is random and changes every 10 minutes.
//! - **Links** (SPEC 16.2): the platform tells us about connections and the bytes on them, and
//!   takes from us what to connect to and what to write ([`BleCmd`]). On a link runs the very same
//!   SPEC 14.7 session as on TCP: Noise keyed on the responder's `loc`, signed hellos, frames each
//!   acknowledged. A Bluetooth eavesdropper sees only that session's ciphertext.
//!
//! Nothing here logs an address, a tag, a key or a name: kinds and counts only.

use super::session::{self, Pipe};
use super::{Hooks, PeerInfo, Sent, Transport, TransportError};
use crate::beacon;
use crate::pair::{self, PairSink};
use crate::util::now_s;
use crate::UserId;
use hmac::{Hmac, KeyInit, Mac};
use sha2::Sha256;
use std::collections::{HashMap, VecDeque};
use std::io::{ErrorKind, Read, Write};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

/// The GATT service and its two characteristics (SPEC 16.1).
pub const SERVICE_UUID: &str = "bbfbe4da-bad5-4b6d-986a-3d77a5519a62";
pub const RX_UUID: &str = "bbfbe4da-bad5-4b6d-986a-3d77a5519a63";
pub const TX_UUID: &str = "bbfbe4da-bad5-4b6d-986a-3d77a5519a64";
pub const INFO: &[u8] = b"mlmchat/ble/1";
pub const TAG_LEN: usize = 8;
/// A contact seen this recently is "beside us".
const FRESH: Duration = Duration::from_secs(60);
/// Tell the node again about a contact still beside us at most this often (retries its queue).
const SEEN_EVERY: Duration = Duration::from_secs(30);
const CONTACTS_REFRESH: Duration = Duration::from_secs(10);

/// The advertised tag for `loc` in `epoch` (SPEC 16.1).
pub fn tag(loc: &[u8; 32], epoch: u32) -> [u8; TAG_LEN] {
    let mut mac = <Hmac<Sha256> as KeyInit>::new_from_slice(loc).expect("HMAC takes any key length");
    mac.update(INFO);
    mac.update(&epoch.to_be_bytes());
    let full = mac.finalize().into_bytes();
    let mut out = [0u8; TAG_LEN];
    out.copy_from_slice(&full[..TAG_LEN]);
    out
}

/// What the platform's radio must do.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BleCmd {
    /// Connect to the device seen at `addr` (as a GATT client); then report `connected(link,
    /// false)` or `closed(link)`.
    Connect { link: u32, addr: String },
    /// Write these bytes on the link, in order (split to the MTU as needed).
    Send { link: u32, data: Vec<u8> },
    /// Close the link.
    Close { link: u32 },
    /// Advertise again, with what [`BleTransport::advert_data`] gives now («add nearby» opened or
    /// closed, SPEC 16.4).
    Advertise,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BleConfig {
    /// How long to wait for the platform to open a link.
    pub connect_timeout: Duration,
    /// How long a read on a link waits for bytes.
    pub io_timeout: Duration,
}

impl Default for BleConfig {
    fn default() -> Self {
        BleConfig { connect_timeout: Duration::from_secs(20), io_timeout: Duration::from_secs(20) }
    }
}

struct Sighting {
    addr: String,
    at: Instant,
    told: Instant,
}

struct Shared {
    hooks: Arc<dyn Hooks>,
    cfg: BleConfig,
    stopped: AtomicBool,
    next_link: AtomicU32,
    cmds: Mutex<VecDeque<BleCmd>>,
    cmds_ready: Condvar,
    /// Each open link's incoming bytes go to its pipe.
    links: Mutex<HashMap<u32, Sender<Vec<u8>>>>,
    /// Links we asked to open: the answer goes here.
    opening: Mutex<HashMap<u32, Sender<bool>>>,
    sightings: Mutex<HashMap<UserId, Sighting>>,
    locs: Mutex<(Vec<(UserId, [u8; 32])>, Option<Instant>)>,
    /// The open «add nearby» screen, if any (SPEC 16.4).
    pair: Mutex<Option<Arc<dyn PairSink>>>,
}

impl Shared {
    fn push(&self, cmd: BleCmd) {
        self.cmds.lock().expect("cmds").push_back(cmd);
        self.cmds_ready.notify_all();
    }

    fn stopped(&self) -> bool {
        self.stopped.load(Ordering::Relaxed)
    }
}

pub struct BleTransport {
    shared: Arc<Shared>,
}

impl BleTransport {
    pub fn start(cfg: BleConfig, hooks: Arc<dyn Hooks>) -> Arc<BleTransport> {
        log::info!("ble: started");
        Arc::new(BleTransport {
            shared: Arc::new(Shared {
                hooks,
                cfg,
                stopped: AtomicBool::new(false),
                next_link: AtomicU32::new(1),
                cmds: Mutex::new(VecDeque::new()),
                cmds_ready: Condvar::new(),
                links: Mutex::new(HashMap::new()),
                opening: Mutex::new(HashMap::new()),
                sightings: Mutex::new(HashMap::new()),
                locs: Mutex::new((Vec::new(), None)),
                pair: Mutex::new(None),
            }),
        })
    }

    /// The tag to advertise now (SPEC 16.1); the platform refreshes it every few minutes.
    pub fn advert_tag(&self) -> [u8; TAG_LEN] {
        let (_, loc) = self.shared.hooks.me();
        tag(&loc, beacon::epoch(now_s()))
    }

    /// The service data to advertise now: `0x01 || id` while «add nearby» is open (SPEC 16.4),
    /// else the contact tag.
    pub fn advert_data(&self) -> Vec<u8> {
        if let Some(p) = self.shared.pair.lock().expect("pair").as_ref() {
            let mut d = Vec::with_capacity(pair::BLE_DATA_LEN);
            d.push(pair::BLE_KIND);
            d.extend_from_slice(&p.id());
            return d;
        }
        self.advert_tag().to_vec()
    }

    /// «Add nearby» opened (`Some`) or closed: its adverts and links go there, and the radio
    /// advertises again.
    pub fn set_pair(&self, sink: Option<Arc<dyn PairSink>>) {
        *self.shared.pair.lock().expect("pair") = sink;
        self.shared.push(BleCmd::Advertise);
    }

    /// A link to the device seen at `addr` for «add nearby», reads waiting at most `read_timeout`.
    pub fn link(&self, addr: &str, read_timeout: Duration) -> Option<Box<dyn Pipe>> {
        let mut pipe = self.open(addr.to_string()).ok()?;
        pipe.timeout = read_timeout;
        Some(Box::new(pipe))
    }

    /// The next thing the radio must do, waiting up to `timeout`.
    pub fn next_command(&self, timeout: Duration) -> Option<BleCmd> {
        let s = &self.shared;
        let mut q = s.cmds.lock().expect("cmds");
        if q.is_empty() && !s.stopped() {
            q = s.cmds_ready.wait_timeout(q, timeout).expect("cmds").0;
        }
        q.pop_front()
    }

    /// A device of ours was seen advertising `tag` at `addr`: if it is a contact, it is beside us.
    pub fn seen(&self, addr: &str, t: &[u8]) {
        if t.len() == pair::BLE_DATA_LEN && t[0] == pair::BLE_KIND {
            let sink = self.shared.pair.lock().expect("pair").clone();
            if let (Some(sink), Ok(id)) = (sink, <pair::PairId>::try_from(&t[1..])) {
                sink.seen_ble(addr, id);
            }
            return;
        }
        let Ok(t) = <[u8; TAG_LEN]>::try_from(t) else { return };
        let s = &self.shared;
        let locs = {
            let mut cache = s.locs.lock().expect("locs");
            if cache.1.is_none_or(|at| at.elapsed() > CONTACTS_REFRESH) {
                *cache = (s.hooks.contact_locs(), Some(Instant::now()));
            }
            cache.0.clone()
        };
        let e = beacon::epoch(now_s());
        let Some(sk) = locs.iter().find(|(_, loc)| tag(loc, e) == t || tag(loc, e.wrapping_sub(1)) == t).map(|(sk, _)| *sk) else { return };
        let tell = {
            let mut map = s.sightings.lock().expect("sightings");
            let now = Instant::now();
            match map.get_mut(&sk) {
                Some(sg) => {
                    let moved = sg.addr != addr;
                    sg.addr = addr.to_string();
                    sg.at = now;
                    if moved || sg.told.elapsed() > SEEN_EVERY {
                        sg.told = now;
                        true
                    } else {
                        false
                    }
                }
                None => {
                    map.insert(sk, Sighting { addr: addr.to_string(), at: now, told: now });
                    true
                }
            }
        };
        if tell {
            log::info!("ble: a contact is beside us");
            s.hooks.on_seen(sk, "ble");
        }
    }

    /// A link is open: one we asked for (`inbound` false), or a device connected to us.
    pub fn connected(&self, link: u32, inbound: bool) {
        let s = self.shared.clone();
        if !inbound {
            if let Some(tx) = s.opening.lock().expect("opening").remove(&link) {
                let _ = tx.send(true);
            }
            return;
        }
        let (tx, rx) = channel();
        s.links.lock().expect("links").insert(link, tx);
        let mut pipe = BlePipe::new(link, rx, s.clone());
        let spawned = std::thread::Builder::new().name("mlmchat-ble-in".into()).spawn(move || {
            // «add nearby» links begin with their own magic (SPEC 16.4); a session's first byte is 0
            let mut head = [0u8; 4];
            if pipe.read_exact(&mut head).is_err() {
                return;
            }
            if head == pair::LINK_MAGIC {
                let sink = s.pair.lock().expect("pair").clone();
                if let Some(sink) = sink {
                    pipe.timeout = Duration::from_millis(100);
                    sink.incoming(Box::new(pipe), "ble");
                }
                return;
            }
            let hooks = s.hooks.clone();
            let pipe = Prefixed { head: head.to_vec(), pos: 0, inner: pipe };
            let r = session::respond(Box::new(pipe), &*hooks, &|| s.stopped(), &mut |sk| hooks.on_reached(sk, "ble"));
            if let Err(e) = r {
                log::debug!("ble: inbound session ended ({})", e.code());
            }
        });
        if spawned.is_err() {
            self.shared.push(BleCmd::Close { link });
        }
    }

    /// Bytes arrived on a link, in order.
    pub fn data(&self, link: u32, bytes: Vec<u8>) {
        if let Some(tx) = self.shared.links.lock().expect("links").get(&link) {
            let _ = tx.send(bytes);
        }
    }

    /// A link closed, or could not be opened.
    pub fn closed(&self, link: u32) {
        self.shared.links.lock().expect("links").remove(&link);
        if let Some(tx) = self.shared.opening.lock().expect("opening").remove(&link) {
            let _ = tx.send(false);
        }
    }

    /// Contacts beside us right now (a count, for the status line).
    pub fn nearby(&self) -> usize {
        self.shared.sightings.lock().expect("sightings").values().filter(|s| s.at.elapsed() < FRESH).count()
    }

    fn sighting(&self, sk: &UserId) -> Option<String> {
        self.shared.sightings.lock().expect("sightings").get(sk).filter(|s| s.at.elapsed() < FRESH).map(|s| s.addr.clone())
    }

    /// Ask the radio for a link to `addr` and wait for it.
    fn open(&self, addr: String) -> Result<BlePipe, TransportError> {
        let s = &self.shared;
        let link = s.next_link.fetch_add(1, Ordering::Relaxed);
        let (data_tx, data_rx) = channel();
        let (open_tx, open_rx) = channel();
        s.links.lock().expect("links").insert(link, data_tx);
        s.opening.lock().expect("opening").insert(link, open_tx);
        s.push(BleCmd::Connect { link, addr });
        match open_rx.recv_timeout(s.cfg.connect_timeout) {
            Ok(true) => Ok(BlePipe::new(link, data_rx, s.clone())),
            other => {
                s.opening.lock().expect("opening").remove(&link);
                s.links.lock().expect("links").remove(&link);
                s.push(BleCmd::Close { link });
                Err(if matches!(other, Err(RecvTimeoutError::Timeout)) { TransportError::Timeout } else { TransportError::Connect })
            }
        }
    }
}

impl Transport for BleTransport {
    fn name(&self) -> &'static str {
        "ble"
    }

    fn send(&self, peer: &PeerInfo, frames: &[Vec<u8>]) -> Result<Sent, TransportError> {
        if self.shared.stopped() {
            return Err(TransportError::Stopped);
        }
        let addr = self.sighting(&peer.sk).ok_or(TransportError::NoRoute)?;
        let pipe = self.open(addr)?;
        let mut conn = session::initiate(Box::new(pipe), &*self.shared.hooks, peer)?;
        let verdicts = session::send_frames(&mut conn, frames)?;
        Ok(Sent { verdicts, via: "ble" })
    }

    fn advertised_addrs(&self) -> Vec<SocketAddr> {
        Vec::new()
    }

    fn has_fresh_route(&self, sk: &UserId) -> bool {
        self.sighting(sk).is_some()
    }

    fn stop(&self) {
        if self.shared.stopped.swap(true, Ordering::SeqCst) {
            return;
        }
        let links: Vec<u32> = self.shared.links.lock().expect("links").drain().map(|(l, _)| l).collect();
        for link in links {
            self.shared.push(BleCmd::Close { link });
        }
        self.shared.cmds_ready.notify_all();
        log::info!("ble: stopped");
    }
}

/// A link as a byte pipe for the session: reads come from the platform's `data`, writes become
/// `Send` commands. Dropping it closes the link.
struct BlePipe {
    link: u32,
    rx: Receiver<Vec<u8>>,
    buf: Vec<u8>,
    pos: usize,
    shared: Arc<Shared>,
    /// How long a read waits for bytes.
    timeout: Duration,
}

impl BlePipe {
    fn new(link: u32, rx: Receiver<Vec<u8>>, shared: Arc<Shared>) -> BlePipe {
        let timeout = shared.cfg.io_timeout;
        BlePipe { link, rx, buf: Vec::new(), pos: 0, shared, timeout }
    }
}

/// A pipe whose first bytes were already read: they come back first.
struct Prefixed<P> {
    head: Vec<u8>,
    pos: usize,
    inner: P,
}

impl<P: Read> Read for Prefixed<P> {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        if self.pos < self.head.len() {
            let n = out.len().min(self.head.len() - self.pos);
            out[..n].copy_from_slice(&self.head[self.pos..self.pos + n]);
            self.pos += n;
            return Ok(n);
        }
        self.inner.read(out)
    }
}

impl<P: Write> Write for Prefixed<P> {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        self.inner.write(data)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

impl Read for BlePipe {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        if self.pos >= self.buf.len() {
            match self.rx.recv_timeout(self.timeout) {
                Ok(b) => {
                    self.buf = b;
                    self.pos = 0;
                }
                Err(RecvTimeoutError::Timeout) => return Err(std::io::Error::new(ErrorKind::TimedOut, "ble read timed out")),
                // the link closed: end of stream
                Err(RecvTimeoutError::Disconnected) => return Ok(0),
            }
        }
        let n = out.len().min(self.buf.len() - self.pos);
        out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}

impl Write for BlePipe {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        if self.shared.stopped() || !self.shared.links.lock().expect("links").contains_key(&self.link) {
            return Err(std::io::Error::new(ErrorKind::BrokenPipe, "ble link closed"));
        }
        self.shared.push(BleCmd::Send { link: self.link, data: data.to_vec() });
        Ok(data.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl Drop for BlePipe {
    fn drop(&mut self) {
        self.shared.links.lock().expect("links").remove(&self.link);
        self.shared.push(BleCmd::Close { link: self.link });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tags_are_private_and_rotate() {
        let loc = [7u8; 32];
        let t = tag(&loc, 100);
        assert_eq!(t.len(), TAG_LEN);
        assert_eq!(t, tag(&loc, 100));
        assert_ne!(t, tag(&loc, 101));
        assert_ne!(t, tag(&[8u8; 32], 100));
        // not the LAN beacon's tag for the same secret and epoch
        assert_ne!(&t[..], &beacon::tag(&loc, 100)[..TAG_LEN]);
    }
}
