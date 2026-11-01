//! A Google Apps Script emulator for the mailbox tests (SPEC 19): an HTTP server on loopback that
//! answers like a deployed `relay/Mailbox.gs`, redirect to `script.googleusercontent.com`
//! included, with knobs to make it misbehave (a lost cache, 503s). The real script is run by
//! `tools/mailbox-sim/test.mjs`; this one keeps the two sides of the protocol honest.

#![allow(dead_code)]

use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

struct Mailbox {
    use_key: String,
    owner_key: String,
    items: HashMap<String, (String, Instant)>,
    queues: HashMap<String, Vec<String>>,
    done: HashMap<String, Instant>,
    paused: bool,
    /// The owner's «public» switch (script version 2).
    public: bool,
    max_clients: u64,
    clients: HashSet<String>,
    requests: u64,
    puts: u64,
    takes: u64,
    /// The official feeds' copy (script version 3): (seq, feed, id, base64) in order, the merged
    /// count sketches by period, and the feed reads and writes.
    feed: Vec<(u64, String, String, String)>,
    census: Vec<(u32, mlmchat_core::census::Sketch)>,
    fgets: u64,
    fputs: u64,
}

struct State {
    boxes: HashMap<String, Mailbox>,
    /// Combined relays (SPEC 19.13): a Worker's host and the mailbox it is (the same one its Google
    /// door, the deployment, hands requests to), and the requests that came to Workers straight.
    workers: HashMap<String, String>,
    worker_hits: u64,
    results: HashMap<String, Vec<u8>>,
    down: HashSet<String>,
    bodies: Vec<Vec<u8>>,
    counter: u64,
}

pub struct Emu {
    pub addr: SocketAddr,
    state: Arc<Mutex<State>>,
    ttl: Duration,
    fail_next: Arc<AtomicUsize>,
    fail_all: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
}

impl Drop for Emu {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        let _ = TcpStream::connect(self.addr);
    }
}

fn hex_ok(s: &str, n: usize) -> bool {
    s.len() == n && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

impl Emu {
    pub fn start(ttl: Duration) -> Emu {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let state = Arc::new(Mutex::new(State { boxes: HashMap::new(), workers: HashMap::new(), worker_hits: 0, results: HashMap::new(), down: HashSet::new(), bodies: Vec::new(), counter: 0 }));
        let fail_next = Arc::new(AtomicUsize::new(0));
        let fail_all = Arc::new(AtomicBool::new(false));
        let stop = Arc::new(AtomicBool::new(false));
        {
            let (state, fail_next, fail_all, stop) = (state.clone(), fail_next.clone(), fail_all.clone(), stop.clone());
            std::thread::spawn(move || {
                for conn in listener.incoming() {
                    if stop.load(Ordering::SeqCst) {
                        break;
                    }
                    let Ok(conn) = conn else { continue };
                    let (state, fail_next, fail_all) = (state.clone(), fail_next.clone(), fail_all.clone());
                    std::thread::spawn(move || serve(conn, state, ttl, fail_next, fail_all));
                }
            });
        }
        Emu { addr, state, ttl, fail_next, fail_all, stop }
    }

    /// A deployed script with these keys (`id` as it would be in the web-app address).
    pub fn add(&self, id: &str, use_key: &str, owner_key: &str) {
        self.state.lock().unwrap().boxes.insert(
            id.to_string(),
            Mailbox {
                use_key: use_key.into(),
                owner_key: owner_key.into(),
                items: HashMap::new(),
                queues: HashMap::new(),
                done: HashMap::new(),
                paused: false,
                public: false,
                max_clients: 0,
                clients: HashSet::new(),
                requests: 0,
                puts: 0,
                takes: 0,
                feed: Vec::new(),
                census: Vec::new(),
                fgets: 0,
                fputs: 0,
            },
        );
    }

    /// A combined relay's Worker at `host`: the mailbox of deployment `id` (its Google door), so
    /// both doors reach one mailbox, as the real Door.gs hands everything to the Worker.
    pub fn add_worker(&self, host: &str, id: &str) {
        self.state.lock().unwrap().workers.insert(host.to_string(), id.to_string());
    }

    /// Requests that reached a Worker straight (not through a Google door).
    pub fn worker_hits(&self) -> u64 {
        self.state.lock().unwrap().worker_hits
    }

    /// The cache lets every waiting item go (the «taken» marks stay).
    pub fn drop_items(&self) {
        for b in self.state.lock().unwrap().boxes.values_mut() {
            b.items.clear();
        }
    }

    /// One deployment answers 503 (or answers again).
    pub fn down(&self, id: &str, on: bool) {
        let mut s = self.state.lock().unwrap();
        if on {
            s.down.insert(id.to_string());
        } else {
            s.down.remove(id);
        }
    }

    pub fn fail_next(&self, n: usize) {
        self.fail_next.store(n, Ordering::SeqCst);
    }

    pub fn fail_all(&self, on: bool) {
        self.fail_all.store(on, Ordering::SeqCst);
    }

    pub fn requests(&self, id: &str) -> u64 {
        self.state.lock().unwrap().boxes.get(id).map_or(0, |b| b.requests)
    }

    pub fn puts(&self, id: &str) -> u64 {
        self.state.lock().unwrap().boxes.get(id).map_or(0, |b| b.puts)
    }

    /// Reads and writes of the official feeds' copy.
    pub fn feed_ops(&self, id: &str) -> (u64, u64) {
        self.state.lock().unwrap().boxes.get(id).map_or((0, 0), |b| (b.fgets, b.fputs))
    }

    /// Items in the official feeds' copy.
    pub fn feed_len(&self, id: &str) -> usize {
        self.state.lock().unwrap().boxes.get(id).map_or(0, |b| b.feed.len())
    }

    /// The count the copy's merged sketches give.
    pub fn feed_count(&self, id: &str) -> f64 {
        let s = self.state.lock().unwrap();
        let Some(b) = s.boxes.get(id) else { return 0.0 };
        let mut all = b.census.iter().map(|(_, k)| k.clone());
        let Some(first) = all.next() else { return 0.0 };
        match all.next() {
            Some(second) => first.estimate_with(&second),
            None => first.estimate(),
        }
    }

    /// Items waiting now, in all queues.
    pub fn waiting(&self, id: &str) -> usize {
        let s = self.state.lock().unwrap();
        s.boxes.get(id).map_or(0, |b| b.items.len())
    }

    /// Every request body the emulator received, as the network carried it.
    pub fn bodies(&self) -> Vec<Vec<u8>> {
        self.state.lock().unwrap().bodies.clone()
    }

    pub fn ttl(&self) -> Duration {
        self.ttl
    }
}

fn read_request(s: &mut TcpStream, buf: &mut Vec<u8>) -> Option<(String, String, String, Vec<u8>)> {
    loop {
        let mut headers = [httparse::EMPTY_HEADER; 32];
        let mut req = httparse::Request::new(&mut headers);
        if let Ok(httparse::Status::Complete(n)) = req.parse(buf) {
            let method = req.method?.to_string();
            let path = req.path?.to_string();
            let host = req.headers.iter().find(|h| h.name.eq_ignore_ascii_case("host")).and_then(|h| std::str::from_utf8(h.value).ok()).unwrap_or("").to_string();
            let len = req.headers.iter().find(|h| h.name.eq_ignore_ascii_case("content-length")).and_then(|h| std::str::from_utf8(h.value).ok()?.trim().parse::<usize>().ok()).unwrap_or(0);
            while buf.len() < n + len {
                let mut chunk = [0u8; 16384];
                let r = s.read(&mut chunk).ok()?;
                if r == 0 {
                    return None;
                }
                buf.extend_from_slice(&chunk[..r]);
            }
            let body = buf[n..n + len].to_vec();
            buf.drain(..n + len);
            return Some((method, path, host, body));
        }
        let mut chunk = [0u8; 16384];
        let r = s.read(&mut chunk).ok()?;
        if r == 0 {
            return None;
        }
        buf.extend_from_slice(&chunk[..r]);
    }
}

fn reply(s: &mut TcpStream, status: &str, extra: &str, body: &[u8]) -> bool {
    let head = format!("HTTP/1.1 {status}\r\n{extra}Content-Length: {}\r\n\r\n", body.len());
    s.write_all(head.as_bytes()).and_then(|_| s.write_all(body)).is_ok()
}

fn serve(mut s: TcpStream, state: Arc<Mutex<State>>, ttl: Duration, fail_next: Arc<AtomicUsize>, fail_all: Arc<AtomicBool>) {
    let mut buf = Vec::new();
    while let Some((method, path, host, body)) = read_request(&mut s, &mut buf) {
        let worker = (method == "POST" && path == "/").then(|| state.lock().unwrap().workers.get(&host).cloned()).flatten();
        let ok = if let Some(id) = worker {
            // a Worker answers straight, no redirect; a wrong key gets its decoy page
            {
                let mut st = state.lock().unwrap();
                st.worker_hits += 1;
                st.bodies.push(body.clone());
            }
            let (_, out) = answer(&state, ttl, &id, &body);
            let html = !out.starts_with(b"{");
            reply(&mut s, "200 OK", if html { "Content-Type: text/html\r\n" } else { "Content-Type: application/json\r\n" }, &out)
        } else if method == "POST" && path.starts_with("/macros/s/") && path.ends_with("/exec") {
            let id = path["/macros/s/".len()..path.len() - "/exec".len()].to_string();
            let is_down = state.lock().unwrap().down.contains(&id);
            if is_down || fail_all.load(Ordering::SeqCst) || fail_next.fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| n.checked_sub(1)).is_ok() {
                reply(&mut s, "503 Service Unavailable", "", b"<html>busy</html>")
            } else {
                state.lock().unwrap().bodies.push(body.clone());
                let (status, out) = answer(&state, ttl, &id, &body);
                if status == 404 {
                    reply(&mut s, "404 Not Found", "Content-Type: text/html\r\n", b"<html>Sorry, unable to open the file at present.</html>")
                } else {
                    let key = {
                        let mut st = state.lock().unwrap();
                        st.counter += 1;
                        let k = format!("k{}", st.counter);
                        st.results.insert(k.clone(), out);
                        k
                    };
                    reply(&mut s, "302 Found", &format!("Location: https://script.googleusercontent.com/macros/echo?user_content_key={key}&lib=x\r\n"), b"")
                }
            }
        } else if method == "GET" && path.starts_with("/macros/echo?user_content_key=") {
            let key: String = path["/macros/echo?user_content_key=".len()..].chars().take_while(|c| c.is_ascii_alphanumeric()).collect();
            let out = state.lock().unwrap().results.remove(&key);
            match out {
                Some(o) => reply(&mut s, "200 OK", "Content-Type: application/json; charset=utf-8\r\n", &o),
                None => reply(&mut s, "404 Not Found", "", b""),
            }
        } else {
            reply(&mut s, "404 Not Found", "", b"")
        };
        if !ok {
            break;
        }
    }
}

const DECOY: &str = "<!DOCTYPE html><html><head><title>Web App</title></head><body><p>The script completed but did not return anything.</p></body></html>";

fn answer(state: &Arc<Mutex<State>>, ttl: Duration, id: &str, body: &[u8]) -> (u16, Vec<u8>) {
    let json_out = |v: Value| (200, v.to_string().into_bytes());
    let decoy = || (200, DECOY.as_bytes().to_vec());
    let Ok(req) = serde_json::from_slice::<Value>(body) else { return decoy() };
    // a take may wait
    let wait = req.get("w").and_then(Value::as_u64).unwrap_or(0).min(20_000);
    let tags: Vec<String> = req.get("t").and_then(Value::as_array).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
    let started = Instant::now();
    loop {
        let (res, empty_take) = {
            let mut st = state.lock().unwrap();
            let Some(b) = st.boxes.get_mut(id) else { return (404, Vec::new()) };
            let owner = req.get("o").and_then(Value::as_str) == Some(&b.owner_key);
            if !owner && req.get("k").and_then(Value::as_str) != Some(&b.use_key) {
                return decoy();
            }
            expire(b);
            let op = req.get("op").and_then(Value::as_str).unwrap_or("");
            let client = req.get("c").and_then(Value::as_str).unwrap_or("").to_string();
            if !owner && b.paused && op != "ping" {
                return json_out(json!({ "e": "paused" }));
            }
            if !owner && !client.is_empty() && !b.clients.contains(&client) && op != "ping" {
                if b.max_clients > 0 && b.clients.len() as u64 >= b.max_clients {
                    return json_out(json!({ "e": "limit" }));
                }
            }
            if !owner && !client.is_empty() {
                b.clients.insert(client);
            }
            b.requests += 1;
            match op {
                "ping" => (json_out(json!({ "ok": 1, "ver": 2, "owner": owner as u8, "paused": b.paused as u8, "pub": b.public as u8 })), false),
                "put" => {
                    b.puts += 1;
                    (put(b, &req, ttl), false)
                }
                "take" => {
                    b.takes += 1;
                    let acks: Vec<String> = req.get("a").and_then(Value::as_array).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
                    let r = take(b, &tags, &acks, ttl);
                    let empty = r.get("i").and_then(Value::as_array).is_some_and(|a| a.is_empty());
                    (json_out(r), empty)
                }
                "chk" => (chk(b, &req), false),
                "fget" => {
                    b.fgets += 1;
                    (json_out(fget(b, &req)), false)
                }
                "fput" => {
                    b.fputs += 1;
                    (json_out(fput(b, &req)), false)
                }
                "stats" if owner => (json_out(json!({ "ok": 1, "ver": 1, "today": { "r": b.requests, "p": b.puts, "t": b.takes, "c": b.clients.len() }, "days": [], "cfg": { "paused": b.paused, "max_clients": b.max_clients } })), false),
                "admin" if owner => {
                    if let Some(p) = req.get("paused").and_then(Value::as_bool) {
                        b.paused = p;
                    }
                    if let Some(m) = req.get("max_clients").and_then(Value::as_u64) {
                        b.max_clients = m;
                    }
                    if let Some(p) = req.get("pub").and_then(Value::as_bool) {
                        b.public = p;
                    }
                    (json_out(json!({ "ok": 1, "cfg": { "paused": b.paused, "max_clients": b.max_clients, "pub": b.public } })), false)
                }
                _ => (json_out(json!({ "e": "bad" })), false),
            }
        };
        if empty_take && (started.elapsed().as_millis() as u64) + 60 < wait {
            std::thread::sleep(Duration::from_millis(40));
            continue;
        }
        return res;
    }
}

fn fget(b: &Mailbox, req: &Value) -> Value {
    let feeds: Vec<&str> = req.get("f").and_then(Value::as_array).map(|a| a.iter().filter_map(Value::as_str).collect()).unwrap_or_default();
    if feeds.is_empty() {
        return json!({ "e": "bad" });
    }
    let since = req.get("s").and_then(Value::as_u64).unwrap_or(0);
    let max = req.get("m").and_then(Value::as_u64).unwrap_or(1_500_000).max(20_000) as usize;
    let mut items = Vec::new();
    let mut chars = 0;
    let mut more = false;
    let mut last = since;
    for (seq, feed, id, data) in b.feed.iter().filter(|(seq, feed, _, _)| *seq > since && feeds.contains(&feed.as_str())) {
        if !items.is_empty() && chars + data.len() > max {
            more = true;
            break;
        }
        items.push(json!([id, feed, seq, data]));
        chars += data.len();
        last = *seq;
    }
    let top = b.feed.last().map_or(0, |x| x.0);
    let next = if more { last } else { top.max(since) };
    let cs: Vec<Value> = b.census.iter().map(|(e, s)| json!([e, mlmchat_core::util::b64url_encode(&s.pack())])).collect();
    json!({ "ok": 1, "s": next, "i": items, "more": more as u8, "cs": cs })
}

fn fput(b: &mut Mailbox, req: &Value) -> Value {
    let Some(feed) = req.get("f").and_then(Value::as_str).filter(|f| hex_ok(f, 32)).map(String::from) else { return json!({ "e": "bad" }) };
    let items = req.get("i").and_then(Value::as_array).cloned().unwrap_or_default();
    let cs = req.get("cs").and_then(Value::as_array).cloned().unwrap_or_default();
    if items.len() > 16 || (items.is_empty() && cs.is_empty()) {
        return json!({ "e": "bad" });
    }
    let mut res = Vec::new();
    for it in &items {
        let (Some(id), Some(data)) = (it.get(0).and_then(Value::as_str), it.get(1).and_then(Value::as_str)) else {
            res.push("bad");
            continue;
        };
        if !hex_ok(id, 32) || data.is_empty() || data.len() > 24_000 {
            res.push("bad");
            continue;
        }
        if b.feed.iter().any(|x| x.2 == id) {
            res.push("dup");
            continue;
        }
        let seq = b.feed.last().map_or(0, |x| x.0) + 1;
        b.feed.push((seq, feed.clone(), id.to_string(), data.to_string()));
        res.push("ok");
    }
    for c in &cs {
        let (Some(epoch), Some(data)) = (c.get(0).and_then(Value::as_u64), c.get(1).and_then(Value::as_str)) else { continue };
        let Some(bytes) = mlmchat_core::util::b64url_decode(data.trim_end_matches('=')) else { continue };
        let Ok(theirs) = mlmchat_core::census::Sketch::unpack(epoch as u32, &bytes) else { continue };
        match b.census.iter_mut().find(|(e, _)| *e == epoch as u32) {
            Some((_, mine)) => {
                mine.merge(&theirs);
            }
            None => b.census.push((epoch as u32, theirs)),
        }
    }
    json!({ "ok": 1, "r": res })
}

fn expire(b: &mut Mailbox) {
    let now = Instant::now();
    b.items.retain(|_, (_, exp)| *exp > now);
    b.done.retain(|_, exp| *exp > now);
}

fn put(b: &mut Mailbox, req: &Value, ttl: Duration) -> (u16, Vec<u8>) {
    let tag = req.get("t").and_then(Value::as_str).unwrap_or("");
    let items = req.get("i").and_then(Value::as_array);
    let (Some(items), true) = (items, hex_ok(tag, 32)) else { return (200, json!({ "e": "bad" }).to_string().into_bytes()) };
    if items.is_empty() || items.len() > 64 {
        return (200, json!({ "e": "bad" }).to_string().into_bytes()); // as the script: at most 64 items a put
    }
    let mut res = Vec::new();
    for it in items {
        let (Some(id), Some(data)) = (it.get(0).and_then(Value::as_str), it.get(1).and_then(Value::as_str)) else {
            res.push("bad");
            continue;
        };
        if !hex_ok(id, 32) || data.is_empty() {
            res.push("bad");
        } else if b.done.contains_key(id) {
            res.push("done");
        } else if data.len() > 98_000 {
            res.push("size");
        } else {
            let q = b.queues.entry(tag.to_string()).or_default();
            if !q.iter().any(|x| x == id) {
                if q.len() >= 400 {
                    res.push("full");
                    continue;
                }
                q.push(id.to_string());
            }
            b.items.insert(id.to_string(), (data.to_string(), Instant::now() + ttl));
            res.push("ok");
        }
    }
    (200, json!({ "ok": 1, "r": res }).to_string().into_bytes())
}

fn take(b: &mut Mailbox, tags: &[String], acks: &[String], ttl: Duration) -> Value {
    let mut out = Vec::new();
    for t in tags {
        let q = b.queues.get(t).cloned().unwrap_or_default();
        let mut keep = Vec::new();
        for id in q {
            if acks.contains(&id) {
                b.items.remove(&id);
                b.done.insert(id, Instant::now() + ttl);
            } else if let Some((data, _)) = b.items.get(&id) {
                keep.push(id.clone());
                out.push(json!([id, t, data]));
            }
        }
        if keep.is_empty() {
            b.queues.remove(t);
        } else {
            b.queues.insert(t.clone(), keep);
        }
    }
    json!({ "ok": 1, "i": out, "more": 0 })
}

fn chk(b: &mut Mailbox, req: &Value) -> (u16, Vec<u8>) {
    let tag = req.get("t").and_then(Value::as_str).unwrap_or("");
    let q = b.queues.get(tag).cloned().unwrap_or_default();
    let r: Vec<&str> = req
        .get("i")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_str).map(|id| if b.done.contains_key(id) { "done" } else if q.iter().any(|x| x == id) && b.items.contains_key(id) { "wait" } else { "gone" }).collect())
        .unwrap_or_default();
    (200, json!({ "ok": 1, "r": r }).to_string().into_bytes())
}
