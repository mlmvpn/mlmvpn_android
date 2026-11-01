//! A Nostr relay emulator for the tests (SPEC 20): plain WebSocket on loopback, NIP-01 `REQ`,
//! `EVENT`, `CLOSE`, `OK`, `EOSE`, with `#p` and `kinds` filters, stored events and live ones, and
//! knobs to make it misbehave (refuse writes, never answer, drop every connection).

#![allow(dead_code)]

use serde_json::{json, Value};
use std::collections::VecDeque;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

struct Sub {
    conn: u64,
    id: String,
    filter: Value,
    out: Arc<Mutex<VecDeque<String>>>,
}

#[derive(Default)]
struct State {
    events: Vec<Value>,
    subs: Vec<Sub>,
}

pub struct Relay {
    pub addr: SocketAddr,
    state: Arc<Mutex<State>>,
    refuse: Arc<AtomicBool>,
    mute: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
    epoch: Arc<AtomicU64>,
    writes: Arc<AtomicU64>,
    more: Arc<More>,
}

/// More ways to misbehave: the words of a refusal, a relay that is down (every connection closed
/// before the upgrade), one slow to open.
#[derive(Default)]
struct More {
    why: Mutex<String>,
    down: AtomicBool,
    slow_ms: AtomicU64,
}

impl Drop for Relay {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        let _ = TcpStream::connect(self.addr);
    }
}

fn tag_values(ev: &Value, name: &str) -> Vec<String> {
    ev.get("tags").and_then(Value::as_array).into_iter().flatten().filter(|t| t.get(0).and_then(Value::as_str) == Some(name)).filter_map(|t| t.get(1).and_then(Value::as_str).map(String::from)).collect()
}

/// NIP-01: `kinds` and every `#<letter>` of the filter must match (a filter with no tag matches
/// nothing here: the tests never ask for everything).
fn matches(f: &Value, ev: &Value) -> bool {
    let Some(map) = f.as_object() else { return false };
    if let Some(kinds) = map.get("kinds").and_then(Value::as_array) {
        if !kinds.iter().any(|k| k.as_u64().is_some() && k.as_u64() == ev.get("kind").and_then(Value::as_u64)) {
            return false;
        }
    }
    let mut any_tag = false;
    for (k, v) in map {
        let Some(name) = k.strip_prefix('#') else { continue };
        any_tag = true;
        let want: Vec<&str> = v.as_array().into_iter().flatten().filter_map(Value::as_str).collect();
        if !tag_values(ev, name).iter().any(|t| want.contains(&t.as_str())) {
            return false;
        }
    }
    any_tag
}

impl Relay {
    pub fn start() -> Relay {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        let r = Relay { addr, state: Arc::default(), refuse: Arc::default(), mute: Arc::default(), stop: Arc::default(), epoch: Arc::default(), writes: Arc::default(), more: Arc::default() };
        let (state, refuse, mute, stop, epoch, writes, more) = (r.state.clone(), r.refuse.clone(), r.mute.clone(), r.stop.clone(), r.epoch.clone(), r.writes.clone(), r.more.clone());
        std::thread::spawn(move || {
            let mut next = 0u64;
            for conn in l.incoming() {
                if stop.load(Ordering::SeqCst) {
                    break;
                }
                let Ok(conn) = conn else { continue };
                if more.down.load(Ordering::SeqCst) {
                    // down: the connection closes before any answer
                    drop(conn);
                    continue;
                }
                next += 1;
                let (state, refuse, mute, stop, epoch, writes, more) = (state.clone(), refuse.clone(), mute.clone(), stop.clone(), epoch.clone(), writes.clone(), more.clone());
                let id = next;
                std::thread::spawn(move || {
                    let slow = more.slow_ms.load(Ordering::SeqCst);
                    if slow > 0 {
                        std::thread::sleep(Duration::from_millis(slow));
                    }
                    serve(conn, id, state, refuse, mute, stop, epoch, writes, more)
                });
            }
        });
        r
    }

    /// Answer every write with OK false and these words (`refuse_writes(false)` ends it).
    pub fn refuse_writes_with(&self, why: &str) {
        *self.more.why.lock().unwrap() = why.to_string();
        self.refuse.store(true, Ordering::SeqCst);
    }

    /// Down: every open connection drops, and every new one closes before it is answered.
    pub fn down(&self, on: bool) {
        self.more.down.store(on, Ordering::SeqCst);
        if on {
            self.drop_all();
        }
    }

    /// Each new connection waits this long before it is answered.
    pub fn slow(&self, ms: u64) {
        self.more.slow_ms.store(ms, Ordering::SeqCst);
    }

    pub fn url(&self) -> String {
        format!("ws://{}", self.addr)
    }

    /// Answer every write with OK false (a relay that wants payment or a sign-in).
    pub fn refuse_writes(&self, on: bool) {
        self.more.why.lock().unwrap().clear();
        self.refuse.store(on, Ordering::SeqCst);
    }

    /// Take the connection and every write, and never answer anything: no OK, no EOSE, no pong
    /// (a filter that lets the connection open and drops what comes back).
    pub fn mute(&self, on: bool) {
        self.mute.store(on, Ordering::SeqCst);
    }

    /// Drop every open connection now.
    pub fn drop_all(&self) {
        self.epoch.fetch_add(1, Ordering::SeqCst);
    }

    pub fn stored(&self) -> usize {
        self.state.lock().unwrap().events.len()
    }

    pub fn writes(&self) -> u64 {
        self.writes.load(Ordering::SeqCst)
    }

    /// Every event kept, as written.
    pub fn events(&self) -> Vec<Value> {
        self.state.lock().unwrap().events.clone()
    }
}

fn read_frame(s: &mut TcpStream, buf: &mut Vec<u8>) -> Option<Option<(u8, Vec<u8>)>> {
    let mut chunk = [0u8; 16384];
    loop {
        let header = if buf.len() < 2 {
            None
        } else {
            match buf[1] & 0x7f {
                126 if buf.len() >= 4 => Some((u16::from_be_bytes([buf[2], buf[3]]) as usize, 4)),
                127 if buf.len() >= 10 => Some((u64::from_be_bytes(buf[2..10].try_into().unwrap()) as usize, 10)),
                126 | 127 => None,
                x => Some((x as usize, 2)),
            }
        };
        if let Some((n, p)) = header {
            let masked = buf[1] & 0x80 != 0;
            let need = p + if masked { 4 } else { 0 } + n;
            if buf.len() >= need {
                let op = buf[0] & 0x0f;
                let mut payload = buf[need - n..need].to_vec();
                if masked {
                    let m = [buf[p], buf[p + 1], buf[p + 2], buf[p + 3]];
                    payload.iter_mut().enumerate().for_each(|(i, b)| *b ^= m[i % 4]);
                }
                buf.drain(..need);
                return Some(Some((op, payload)));
            }
        }
        match s.read(&mut chunk) {
            Ok(0) => return None,
            Ok(k) => buf.extend_from_slice(&chunk[..k]),
            Err(e) if matches!(e.kind(), std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock) => return Some(None),
            Err(_) => return None,
        }
    }
}

fn write_text(s: &mut TcpStream, text: &str) -> bool {
    let b = text.as_bytes();
    let mut f = vec![0x81];
    if b.len() < 126 {
        f.push(b.len() as u8);
    } else if b.len() < 65536 {
        f.push(126);
        f.extend_from_slice(&(b.len() as u16).to_be_bytes());
    } else {
        f.push(127);
        f.extend_from_slice(&(b.len() as u64).to_be_bytes());
    }
    f.extend_from_slice(b);
    s.write_all(&f).is_ok()
}

#[allow(clippy::too_many_arguments)]
#[allow(clippy::too_many_arguments)]
fn serve(mut s: TcpStream, conn: u64, state: Arc<Mutex<State>>, refuse: Arc<AtomicBool>, mute: Arc<AtomicBool>, stop: Arc<AtomicBool>, epoch: Arc<AtomicU64>, writes: Arc<AtomicU64>, more: Arc<More>) {
    // the upgrade (the Sec-WebSocket-Accept is not checked by the core's client)
    let mut head = Vec::new();
    let mut b = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        if s.read(&mut b).ok() != Some(1) || head.len() > 16384 {
            return;
        }
        head.push(b[0]);
    }
    if s.write_all(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: x\r\n\r\n").is_err() {
        return;
    }
    let _ = s.set_read_timeout(Some(Duration::from_millis(30)));
    let out: Arc<Mutex<VecDeque<String>>> = Arc::default();
    let born = epoch.load(Ordering::SeqCst);
    let mut buf = Vec::new();
    let end = |state: &Arc<Mutex<State>>| state.lock().unwrap().subs.retain(|x| x.conn != conn);
    loop {
        if stop.load(Ordering::SeqCst) || epoch.load(Ordering::SeqCst) != born {
            end(&state);
            return;
        }
        let quiet = mute.load(Ordering::SeqCst);
        let pending: Vec<String> = out.lock().unwrap().drain(..).collect();
        for m in pending.iter().filter(|_| !quiet) {
            if !write_text(&mut s, m) {
                end(&state);
                return;
            }
        }
        let Some(frame) = read_frame(&mut s, &mut buf) else {
            end(&state);
            return;
        };
        let Some((op, payload)) = frame else { continue };
        if quiet {
            // what comes in is counted and dropped; nothing goes back
            if op == 0x1 && serde_json::from_slice::<Value>(&payload).is_ok_and(|v| v.get(0).and_then(Value::as_str) == Some("EVENT")) {
                writes.fetch_add(1, Ordering::SeqCst);
            }
            continue;
        }
        match op {
            0x9 => {
                let _ = s.write_all(&[0x8a, 0]);
                continue;
            }
            0x8 => {
                end(&state);
                return;
            }
            0x1 => {}
            _ => continue,
        }
        let Ok(Value::Array(a)) = serde_json::from_slice::<Value>(&payload) else { continue };
        match a.first().and_then(Value::as_str) {
            Some("REQ") => {
                let id = a.get(1).and_then(Value::as_str).unwrap_or("").to_string();
                let f = a.get(2).cloned().unwrap_or(json!({}));
                let mut st = state.lock().unwrap();
                st.subs.retain(|x| !(x.conn == conn && x.id == id));
                let old: Vec<Value> = st.events.iter().filter(|e| matches(&f, e)).cloned().collect();
                st.subs.push(Sub { conn, id: id.clone(), filter: f, out: out.clone() });
                drop(st);
                let mut o = out.lock().unwrap();
                for e in old {
                    o.push_back(json!(["EVENT", id, e]).to_string());
                }
                o.push_back(json!(["EOSE", id]).to_string());
            }
            Some("EVENT") => {
                let Some(ev) = a.get(1).cloned() else { continue };
                let id = ev.get("id").and_then(Value::as_str).unwrap_or("").to_string();
                writes.fetch_add(1, Ordering::SeqCst);
                if refuse.load(Ordering::SeqCst) {
                    let why = more.why.lock().unwrap().clone();
                    let why = if why.is_empty() { "restricted: sign in first".to_string() } else { why };
                    out.lock().unwrap().push_back(json!(["OK", id, false, why]).to_string());
                    continue;
                }
                let mut st = state.lock().unwrap();
                let dup = st.events.iter().any(|e| e.get("id") == ev.get("id"));
                if !dup {
                    st.events.push(ev.clone());
                    for sub in st.subs.iter().filter(|x| matches(&x.filter, &ev)) {
                        sub.out.lock().unwrap().push_back(json!(["EVENT", sub.id, ev]).to_string());
                    }
                }
                drop(st);
                out.lock().unwrap().push_back(json!(["OK", id, true, if dup { "duplicate: already have this event" } else { "" }]).to_string());
            }
            Some("CLOSE") => {
                let id = a.get(1).and_then(Value::as_str).unwrap_or("").to_string();
                state.lock().unwrap().subs.retain(|x| !(x.conn == conn && x.id == id));
            }
            _ => {}
        }
    }
}
