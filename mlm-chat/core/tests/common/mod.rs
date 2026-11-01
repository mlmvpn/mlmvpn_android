//! Helpers shared by the integration tests: nodes on loopback, waiting for events, and a TCP
//! proxy that records every byte (the "capture" of the canary test).

#![allow(dead_code)]

pub mod gas_emu;
pub mod nostr_emu;

use mlmchat_core::node::{Event, Node, NodeOptions};
use mlmchat_core::transport::direct::DirectConfig;
use std::io::{Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::path::Path;
use std::sync::mpsc::Receiver;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const KEY: [u8; 32] = [0x42; 32];

pub fn loopback(advertise: Vec<SocketAddr>) -> DirectConfig {
    DirectConfig {
        listen: "127.0.0.1:0".parse().unwrap(),
        advertise,
        discovery: None,
        beacon_targets: Vec::new(),
        beacon_every: Duration::from_millis(200),
        connect_timeout: Duration::from_secs(2),
        io_timeout: Duration::from_secs(5),
    }
}

pub fn new_node(dir: &Path, name: &str) -> Node {
    let n = Node::create(&dir.join("chat.db"), &KEY, name, NodeOptions::default()).unwrap();
    // official files go to no real relay from a test (a test that wants one names its emulator)
    n.set_file_relays(Vec::new());
    // each test node a phone of its own (T74): a member's official post needs its phone's tag
    n.set_device(dir.to_string_lossy().as_bytes());
    n
}

/// Wait for the first event that `pick` accepts; other events are kept in `seen`.
pub fn wait_for<T>(rx: &Receiver<Event>, seen: &mut Vec<Event>, secs: u64, mut pick: impl FnMut(&Event) -> Option<T>) -> Option<T> {
    if let Some(v) = seen.iter().find_map(&mut pick) {
        return Some(v);
    }
    let end = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < end {
        if let Ok(e) = rx.recv_timeout(Duration::from_millis(100)) {
            let hit = pick(&e);
            seen.push(e);
            if hit.is_some() {
                return hit;
            }
        }
    }
    None
}

/// Everything that crossed a recording proxy: per connection, the bytes of each direction.
#[derive(Default)]
pub struct Capture {
    pub streams: Mutex<Vec<Arc<Mutex<Vec<u8>>>>>,
}

impl Capture {
    pub fn all(&self) -> Vec<Vec<u8>> {
        self.streams.lock().unwrap().iter().map(|s| s.lock().unwrap().clone()).collect()
    }
}

fn pump(mut from: TcpStream, mut to: TcpStream, log: Arc<Mutex<Vec<u8>>>) {
    let mut buf = [0u8; 16384];
    loop {
        match from.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                log.lock().unwrap().extend_from_slice(&buf[..n]);
                if to.write_all(&buf[..n]).is_err() {
                    break;
                }
            }
        }
    }
    let _ = to.shutdown(Shutdown::Write);
}

/// A TCP proxy on loopback that forwards to `target` and records both directions.
pub fn recording_proxy(target: SocketAddr, capture: Arc<Capture>) -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for client in listener.incoming().flatten() {
            let Ok(server) = TcpStream::connect(target) else { continue };
            let up = Arc::new(Mutex::new(Vec::new()));
            let down = Arc::new(Mutex::new(Vec::new()));
            capture.streams.lock().unwrap().push(up.clone());
            capture.streams.lock().unwrap().push(down.clone());
            let (c2, s2) = (client.try_clone().unwrap(), server.try_clone().unwrap());
            std::thread::spawn(move || pump(client, server, up));
            std::thread::spawn(move || pump(s2, c2, down));
        }
    });
    addr
}

/// A UDP socket that records every datagram it gets (beacons).
pub fn udp_recorder() -> (SocketAddr, Arc<Mutex<Vec<Vec<u8>>>>) {
    let sock = UdpSocket::bind("127.0.0.1:0").unwrap();
    let addr = sock.local_addr().unwrap();
    let got = Arc::new(Mutex::new(Vec::new()));
    let g = got.clone();
    std::thread::spawn(move || {
        let mut buf = [0u8; 2048];
        while let Ok((n, _)) = sock.recv_from(&mut buf) {
            g.lock().unwrap().push(buf[..n].to_vec());
        }
    });
    (addr, got)
}

/// A free UDP port on loopback (bound and released).
pub fn free_udp() -> SocketAddr {
    UdpSocket::bind("127.0.0.1:0").unwrap().local_addr().unwrap()
}

/// A free TCP port on loopback (bound and released).
pub fn free_tcp() -> SocketAddr {
    TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap()
}

static MEMLOG: Mutex<Vec<String>> = Mutex::new(Vec::new());

struct MemLog;

impl log::Log for MemLog {
    fn enabled(&self, _: &log::Metadata) -> bool {
        true
    }
    fn log(&self, r: &log::Record) {
        let line = format!("[{:?}] {} {}", std::thread::current().name().unwrap_or("?"), r.level(), r.args());
        if let Ok(mut l) = MEMLOG.lock() {
            l.push(line);
        }
    }
    fn flush(&self) {}
}

/// Core log lines kept in memory (cheap enough not to change the timing); `dump_log` returns
/// them for a failure message. Kinds and counts only (SPEC 0).
pub fn init_log() {
    if log::set_logger(&MemLog).is_ok() {
        log::set_max_level(log::LevelFilter::Debug);
    }
}

pub fn dump_log() -> String {
    MEMLOG.lock().map(|l| l.join("
")).unwrap_or_default()
}
