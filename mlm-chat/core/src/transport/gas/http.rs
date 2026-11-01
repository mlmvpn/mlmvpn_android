//! HTTPS/1.1 to Apps Script through a Google front (SPEC 19.5): TCP to a Google address, TLS with
//! a cover name (SNI) whose certificate is verified against the standard web roots, and the
//! request's `Host: script.google.com`. Apps Script answers a web-app POST with a redirect to
//! `script.googleusercontent.com`; that GET goes the same way, on the same kept-alive connection
//! when it can.
//!
//! A combined relay's Worker (SPEC 19.13) is reached the same way, with its own name: TCP to a
//! Cloudflare address, TLS with the Worker's host (its ClientHello cut in two records where the
//! name is filtered), `Host:` the Worker.
//!
//! Blocking, one request at a time per connection; a few idle connections are kept for reuse.
//! A plain-HTTP endpoint exists for tests and local emulators only. With HTTP/2 on, a Google front
//! that offers it gets the same calls over HTTP/2 (`h2.rs`); everything else stays on HTTP/1.1.

use super::h2::H2;
use rustls::crypto::{aws_lc_rs, CryptoProvider};
use rustls::pki_types::ServerName;
use crate::transport::nostr::ws::{Split, SplitStream};
use rustls::{ClientConfig, ClientConnection, StreamOwned};
use std::collections::HashSet;
use std::io::{self, Read, Write};
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

pub const SCRIPT_HOST: &str = "script.google.com";
const IDLE_MAX: usize = 4;
const IDLE_KEEP: Duration = Duration::from_secs(110);
const MAX_BODY: usize = 8 * 1024 * 1024;
const MAX_HEAD: usize = 32 * 1024;

/// Where requests go: a Google address with a cover name, a combined relay's Worker on a
/// Cloudflare address (`split`: the ClientHello in two TLS records), or (tests) a local plain-HTTP
/// server.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Endpoint {
    Front { ip: IpAddr, sni: String },
    Worker { ip: IpAddr, host: String, split: bool },
    Plain(SocketAddr),
}

impl Endpoint {
    fn key(&self) -> String {
        match self {
            Endpoint::Front { ip, sni } => format!("{ip}|{sni}"),
            Endpoint::Worker { ip, host, split } => format!("w|{ip}|{host}|{split}"),
            Endpoint::Plain(a) => a.to_string(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HttpError {
    /// TCP or TLS to the front failed: the front is blocked or down on this network.
    Front,
    Timeout,
    /// The connection broke in the middle of a request.
    Io,
    Protocol,
}

impl HttpError {
    pub fn code(self) -> &'static str {
        match self {
            HttpError::Front => "front",
            HttpError::Timeout => "timeout",
            HttpError::Io => "network",
            HttpError::Protocol => "bad_response",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Response {
    pub status: u16,
    pub location: Option<String>,
    pub body: Vec<u8>,
}

/// What this process sent to and got from the fronts and Workers: TCP payload, so TLS records and
/// handshakes, HTTP heads, redirects, front probes and failed requests too (not IP and TCP
/// headers). This is what a data plan pays for; the relays' own counts are request and answer
/// bodies only.
static WIRE_OUT: AtomicU64 = AtomicU64::new(0);
static WIRE_IN: AtomicU64 = AtomicU64::new(0);

/// Bytes (out, in) on the wire since the process started.
pub fn wire_totals() -> (u64, u64) {
    (WIRE_OUT.load(Ordering::Relaxed), WIRE_IN.load(Ordering::Relaxed))
}

/// A stream that counts what crosses it: right above the TCP socket (and the ClientHello split,
/// which only cuts the same bytes in two writes), so every TLS byte.
struct Counted<S>(S);

impl<S: Read> Read for Counted<S> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.0.read(buf)?;
        WIRE_IN.fetch_add(n as u64, Ordering::Relaxed);
        Ok(n)
    }
}

impl<S: Write> Write for Counted<S> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.0.write(buf)?;
        WIRE_OUT.fetch_add(n as u64, Ordering::Relaxed);
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.0.flush()
    }
}

enum Stream {
    Tls(Box<StreamOwned<ClientConnection, Counted<SplitStream>>>),
    Plain(Counted<TcpStream>),
}

impl Stream {
    fn tcp(&self) -> &TcpStream {
        match self {
            Stream::Tls(s) => s.sock.0.tcp(),
            Stream::Plain(s) => &s.0,
        }
    }

    fn set_timeout(&self, t: Duration) {
        let _ = self.tcp().set_read_timeout(Some(t));
        let _ = self.tcp().set_write_timeout(Some(t));
    }
}

impl Read for Stream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Stream::Tls(s) => s.read(buf),
            Stream::Plain(s) => s.read(buf),
        }
    }
}

impl Write for Stream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            Stream::Tls(s) => s.write(buf),
            Stream::Plain(s) => s.write(buf),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self {
            Stream::Tls(s) => s.flush(),
            Stream::Plain(s) => s.flush(),
        }
    }
}

struct Idle {
    key: String,
    stream: Stream,
    since: Instant,
}

/// TLS for the fronts: the usual web roots, and only the classic key exchanges, so the
/// ClientHello stays the size of any browser's (a post-quantum one is larger and less common).
pub fn tls_config() -> Arc<ClientConfig> {
    static CONFIG: OnceLock<Arc<ClientConfig>> = OnceLock::new();
    CONFIG.get_or_init(|| make_tls(&[b"http/1.1"])).clone()
}

/// The same, offering HTTP/2 first as browsers do.
fn tls_config_h2() -> Arc<ClientConfig> {
    static CONFIG: OnceLock<Arc<ClientConfig>> = OnceLock::new();
    CONFIG.get_or_init(|| make_tls(&[b"h2", b"http/1.1"])).clone()
}

fn make_tls(alpn: &[&[u8]]) -> Arc<ClientConfig> {
    let mut p: CryptoProvider = aws_lc_rs::default_provider();
    p.kx_groups = vec![aws_lc_rs::kx_group::X25519, aws_lc_rs::kx_group::SECP256R1];
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut c = ClientConfig::builder_with_provider(Arc::new(p))
        .with_safe_default_protocol_versions()
        .expect("TLS 1.2 and 1.3")
        .with_root_certificates(roots)
        .with_no_client_auth();
    c.alpn_protocols = alpn.iter().map(|a| a.to_vec()).collect();
    Arc::new(c)
}

/// TCP and the TLS handshake to a front get at most this long, whatever the request may take after.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);

/// Any failure before the TLS handshake is done is the front's: a timeout too. Iran's filtering
/// mostly drops a blocked front's packets instead of refusing them, so a timeout here was the usual
/// way a front went bad; counted as `Timeout`, it kept the front, and every call spent its whole
/// request timeout on it until the network changed (drnet's reading of this code, 26 Oct 2026).
fn connect(ep: &Endpoint, timeout: Duration) -> Result<Stream, HttpError> {
    connect_with(ep, timeout, false).map(|(s, _)| s)
}

/// A connection, offering HTTP/2 when `h2`; the flag says whether the server chose it.
fn connect_with(ep: &Endpoint, timeout: Duration, h2: bool) -> Result<(Stream, bool), HttpError> {
    let addr = match ep {
        Endpoint::Front { ip, .. } | Endpoint::Worker { ip, .. } => SocketAddr::new(*ip, 443),
        Endpoint::Plain(a) => *a,
    };
    let ct = timeout.min(CONNECT_TIMEOUT);
    let tcp = TcpStream::connect_timeout(&addr, ct).map_err(|_| HttpError::Front)?;
    let _ = tcp.set_nodelay(true);
    let _ = tcp.set_read_timeout(Some(ct));
    let _ = tcp.set_write_timeout(Some(ct));
    let (sni, split) = match ep {
        Endpoint::Plain(_) => return Ok((Stream::Plain(Counted(tcp)), false)),
        Endpoint::Front { sni, .. } => (sni, false),
        Endpoint::Worker { host, split, .. } => (host, *split),
    };
    let name = ServerName::try_from(sni.clone()).map_err(|_| HttpError::Front)?;
    let config = if h2 { tls_config_h2() } else { tls_config() };
    let mut conn = ClientConnection::new(config, name).map_err(|_| HttpError::Front)?;
    let mut sock = Counted(SplitStream::new(tcp, if split { Split::Record } else { Split::None }));
    // finish the handshake here, so a front that is blocked shows as one, not as a broken request
    while conn.is_handshaking() {
        if conn.complete_io(&mut sock).is_err() {
            return Err(HttpError::Front);
        }
    }
    let chose_h2 = conn.alpn_protocol() == Some(&b"h2"[..]);
    Ok((Stream::Tls(Box::new(StreamOwned::new(conn, sock))), chose_h2))
}

/// How long one front takes to answer an HTTP request for script.google.com: TCP, TLS with the
/// cover name and a verified certificate, and a status line back. Any status counts: it proves
/// the whole fronted path works on this network.
pub fn probe(ep: &Endpoint, timeout: Duration) -> Option<u64> {
    let t0 = Instant::now();
    let mut s = connect(ep, timeout).ok()?;
    let req = match ep {
        // a Worker answers a GET with its decoy page
        Endpoint::Worker { host, .. } => format!("GET / HTTP/1.1\r\nHost: {host}\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"),
        _ => format!("GET /macros/s/probe/exec HTTP/1.1\r\nHost: {SCRIPT_HOST}\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"),
    };
    s.write_all(req.as_bytes()).ok()?;
    let mut buf = [0u8; 16];
    let mut got = 0;
    while got < 12 {
        match s.read(&mut buf[got..]) {
            Ok(0) | Err(_) => return None,
            Ok(n) => got += n,
        }
    }
    // a Worker's way counts only when the Worker itself answered (its decoy, 200): an edge or a
    // middlebox that answers with an error page is no way to it
    let ok = match ep {
        Endpoint::Worker { .. } => buf.starts_with(b"HTTP/1.1 200") || buf.starts_with(b"HTTP/1.0 200"),
        _ => buf.starts_with(b"HTTP/1."),
    };
    ok.then(|| t0.elapsed().as_millis() as u64)
}

/// Requests through one endpoint at a time; idle connections kept for reuse.
pub struct Client {
    idle: Mutex<Vec<Idle>>,
    idle_h2: Mutex<Vec<(String, H2<Stream>, Instant)>>,
    http2: AtomicBool,
    /// Fronts that did not offer HTTP/2, or where it went wrong: HTTP/1.1 there until a reset.
    no_h2: Mutex<HashSet<String>>,
}

impl Default for Client {
    fn default() -> Self {
        Client { idle: Mutex::new(Vec::new()), idle_h2: Mutex::new(Vec::new()), http2: AtomicBool::new(false), no_h2: Mutex::new(HashSet::new()) }
    }
}

impl Client {
    /// Forget every kept connection (the network or the front changed).
    pub fn reset(&self) {
        self.idle.lock().expect("idle").clear();
        self.idle_h2.lock().expect("idle_h2").clear();
        self.no_h2.lock().expect("no_h2").clear();
    }

    /// HTTP/2 to the Google fronts that offer it.
    pub fn set_http2(&self, on: bool) {
        self.http2.store(on, Ordering::SeqCst);
        if !on {
            self.idle_h2.lock().expect("idle_h2").clear();
        }
    }

    fn take_idle_h2(&self, key: &str) -> Option<H2<Stream>> {
        let mut idle = self.idle_h2.lock().expect("idle_h2");
        idle.retain(|i| i.2.elapsed() < IDLE_KEEP);
        let i = idle.iter().position(|i| i.0 == key)?;
        Some(idle.swap_remove(i).1)
    }

    fn put_idle_h2(&self, key: &str, c: H2<Stream>) {
        if !c.usable() {
            return;
        }
        let mut idle = self.idle_h2.lock().expect("idle_h2");
        if idle.len() >= IDLE_MAX {
            idle.remove(0);
        }
        idle.push((key.to_string(), c, Instant::now()));
    }

    /// One request over HTTP/2; `None` when this front turned out not to be for HTTP/2 and nothing
    /// came back, so the caller goes on with HTTP/1.1.
    #[allow(clippy::too_many_arguments)]
    fn request_h2(&self, ep: &Endpoint, key: &str, method: &str, host: &str, path: &str, body: Option<&[u8]>, timeout: Duration) -> Option<Result<Response, HttpError>> {
        if let Some(mut c) = self.take_idle_h2(key) {
            c.io().set_timeout(timeout);
            match c.request(method, host, path, body) {
                Ok(r) => {
                    self.put_idle_h2(key, c);
                    return Some(Ok(r));
                }
                Err((true, e)) => return Some(Err(e)),
                Err((false, _)) => {} // a stale connection: a fresh one
            }
        }
        let (s, chose_h2) = match connect_with(ep, timeout, true) {
            Ok(x) => x,
            Err(e) => return Some(Err(e)),
        };
        if !chose_h2 {
            // the front answered for HTTP/1.1: this connection, and the next ones, go that way
            self.no_h2.lock().expect("no_h2").insert(key.to_string());
            return Some(self.on(key, s, method, host, path, body, timeout).map_err(|(_, e)| e));
        }
        s.set_timeout(timeout);
        let mut c = H2::new(s);
        match c.request(method, host, path, body) {
            Ok(r) => {
                self.put_idle_h2(key, c);
                Some(Ok(r))
            }
            Err((got_any, HttpError::Protocol)) => {
                log::info!("gas: HTTP/2 went wrong on a front; HTTP/1.1 there from now on");
                self.no_h2.lock().expect("no_h2").insert(key.to_string());
                if got_any {
                    Some(Err(HttpError::Protocol))
                } else {
                    None
                }
            }
            Err((_, e)) => Some(Err(e)),
        }
    }

    /// Forget the kept connections to one endpoint.
    pub fn drop_idle(&self, ep: &Endpoint) {
        let key = ep.key();
        self.idle.lock().expect("idle").retain(|i| i.key != key);
    }

    fn take_idle(&self, key: &str) -> Option<Stream> {
        let mut idle = self.idle.lock().expect("idle");
        idle.retain(|i| i.since.elapsed() < IDLE_KEEP);
        let i = idle.iter().position(|i| i.key == key)?;
        Some(idle.swap_remove(i).stream)
    }

    fn put_idle(&self, key: String, stream: Stream) {
        let mut idle = self.idle.lock().expect("idle");
        if idle.len() >= IDLE_MAX {
            idle.remove(0);
        }
        idle.push(Idle { key, stream, since: Instant::now() });
    }

    /// One request. A kept connection that turns out closed is replaced by a fresh one, once.
    pub fn request(&self, ep: &Endpoint, method: &str, host: &str, path: &str, body: Option<&[u8]>, timeout: Duration) -> Result<Response, HttpError> {
        let key = ep.key();
        // HTTP/2 only to the Google fronts: a Worker's door stays on HTTP/1.1 (and its record split)
        if self.http2.load(Ordering::SeqCst) && matches!(ep, Endpoint::Front { .. }) && !self.no_h2.lock().expect("no_h2").contains(&key) {
            if let Some(r) = self.request_h2(ep, &key, method, host, path, body, timeout) {
                return r;
            }
        }
        if let Some(s) = self.take_idle(&key) {
            match self.on(&key, s, method, host, path, body, timeout) {
                Ok(r) => return Ok(r),
                Err((false, _)) => {} // nothing came back: a stale connection, try a fresh one
                Err((true, e)) => return Err(e),
            }
        }
        let s = connect(ep, timeout)?;
        self.on(&key, s, method, host, path, body, timeout).map_err(|(_, e)| e)
    }

    /// The error's flag says whether any byte of an answer came (then a retry could repeat it).
    #[allow(clippy::too_many_arguments)]
    fn on(&self, key: &str, mut s: Stream, method: &str, host: &str, path: &str, body: Option<&[u8]>, timeout: Duration) -> Result<Response, (bool, HttpError)> {
        s.set_timeout(timeout);
        let mut head = format!("{method} {path} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\nConnection: keep-alive\r\n");
        if let Some(b) = body {
            head.push_str(&format!("Content-Type: application/json\r\nContent-Length: {}\r\n", b.len()));
        }
        head.push_str("\r\n");
        let mut out = head.into_bytes();
        if let Some(b) = body {
            out.extend_from_slice(b);
        }
        if s.write_all(&out).and_then(|_| s.flush()).is_err() {
            return Err((false, HttpError::Io));
        }
        let mut r = Reader { s: &mut s, buf: Vec::new(), pos: 0 };
        let (resp, keep) = r.response(method == "HEAD")?;
        let leftover = r.pos < r.buf.len();
        if keep && !leftover {
            self.put_idle(key.to_string(), s);
        }
        Ok(resp)
    }

    /// POST to a web app, following Apps Script's redirect to googleusercontent. A redirect to
    /// anywhere else (a sign-in page: the deployment is not open to «Anyone») comes back as is.
    pub fn exec(&self, ep: &Endpoint, deployment: &str, body: &[u8], timeout: Duration) -> Result<Response, HttpError> {
        let path = format!("/macros/s/{deployment}/exec");
        let first = self.request(ep, "POST", SCRIPT_HOST, &path, Some(body), timeout)?;
        if !matches!(first.status, 301 | 302 | 303 | 307 | 308) {
            return Ok(first);
        }
        let Some(loc) = first.location.clone() else { return Ok(first) };
        let Some((host, path)) = split_url(&loc) else { return Ok(first) };
        if !(host.ends_with(".googleusercontent.com") || host == SCRIPT_HOST) {
            return Ok(first);
        }
        self.request(ep, "GET", &host, &path, None, timeout)
    }
}

/// `https://host/path?query` -> (host, path?query).
fn split_url(url: &str) -> Option<(String, String)> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let (host, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    (!host.is_empty()).then(|| (host.to_ascii_lowercase(), path.to_string()))
}

struct Reader<'a> {
    s: &'a mut Stream,
    buf: Vec<u8>,
    pos: usize,
}

impl Reader<'_> {
    /// More bytes into the buffer; Err((got_any, error)).
    fn fill(&mut self) -> Result<(), (bool, HttpError)> {
        let mut chunk = [0u8; 16 * 1024];
        match self.s.read(&mut chunk) {
            Ok(0) => Err((!self.buf.is_empty(), HttpError::Io)),
            Ok(n) => {
                self.buf.extend_from_slice(&chunk[..n]);
                Ok(())
            }
            Err(e) if matches!(e.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock) => Err((true, HttpError::Timeout)),
            Err(_) => Err((!self.buf.is_empty(), HttpError::Io)),
        }
    }

    fn take(&mut self, n: usize) -> Result<Vec<u8>, (bool, HttpError)> {
        while self.buf.len() - self.pos < n {
            self.fill()?;
        }
        let out = self.buf[self.pos..self.pos + n].to_vec();
        self.pos += n;
        Ok(out)
    }

    fn line(&mut self) -> Result<String, (bool, HttpError)> {
        loop {
            if let Some(i) = self.buf[self.pos..].windows(2).position(|w| w == b"\r\n") {
                let l = String::from_utf8_lossy(&self.buf[self.pos..self.pos + i]).to_string();
                self.pos += i + 2;
                return Ok(l);
            }
            if self.buf.len() - self.pos > MAX_HEAD {
                return Err((true, HttpError::Protocol));
            }
            self.fill()?;
        }
    }

    /// The response and whether the connection may be kept.
    fn response(&mut self, head_only: bool) -> Result<(Response, bool), (bool, HttpError)> {
        let (status, location, chunked, length, close, used) = loop {
            let mut headers = [httparse::EMPTY_HEADER; 64];
            let mut resp = httparse::Response::new(&mut headers);
            match resp.parse(&self.buf) {
                Ok(httparse::Status::Complete(n)) => {
                    let status = resp.code.unwrap_or(0);
                    let mut location = None;
                    let mut chunked = false;
                    let mut length: Option<usize> = None;
                    let mut close = resp.version == Some(0);
                    for h in resp.headers.iter() {
                        let v = String::from_utf8_lossy(h.value).trim().to_string();
                        match h.name.to_ascii_lowercase().as_str() {
                            "location" => location = Some(v),
                            "transfer-encoding" => chunked = v.to_ascii_lowercase().contains("chunked"),
                            "content-length" => length = v.parse().ok(),
                            "connection" => close = v.eq_ignore_ascii_case("close"),
                            _ => {}
                        }
                    }
                    break (status, location, chunked, length, close, n);
                }
                Ok(httparse::Status::Partial) => {
                    if self.buf.len() > MAX_HEAD {
                        return Err((true, HttpError::Protocol));
                    }
                    self.fill()?;
                }
                Err(_) => return Err((true, HttpError::Protocol)),
            }
        };
        self.pos = used;
        let no_body = head_only || status == 204 || status == 304 || (100..200).contains(&status);
        let mut body = Vec::new();
        let mut keep = !close;
        if no_body {
        } else if chunked {
            loop {
                let size_line = self.line()?;
                let size = usize::from_str_radix(size_line.split(';').next().unwrap_or("").trim(), 16).map_err(|_| (true, HttpError::Protocol))?;
                if size == 0 {
                    while !self.line()?.is_empty() {}
                    break;
                }
                if body.len() + size > MAX_BODY {
                    return Err((true, HttpError::Protocol));
                }
                body.extend(self.take(size)?);
                if !self.line()?.is_empty() {
                    return Err((true, HttpError::Protocol));
                }
            }
        } else if let Some(n) = length {
            if n > MAX_BODY {
                return Err((true, HttpError::Protocol));
            }
            body = self.take(n)?;
        } else {
            // no length: the body runs to the end of the connection
            keep = false;
            loop {
                match self.fill() {
                    Ok(()) => {
                        if self.buf.len() - self.pos > MAX_BODY {
                            return Err((true, HttpError::Protocol));
                        }
                    }
                    Err((_, HttpError::Io)) => break,
                    Err(e) => return Err(e),
                }
            }
            body = self.buf[self.pos..].to_vec();
            self.pos = self.buf.len();
        }
        Ok((Response { status, location, body }, keep))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::thread;

    fn serve(answers: Vec<&'static [u8]>) -> SocketAddr {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        thread::spawn(move || {
            let (mut s, _) = l.accept().unwrap();
            for a in answers {
                let mut buf = vec![0u8; 65536];
                let mut got = Vec::new();
                loop {
                    let n = s.read(&mut buf).unwrap();
                    got.extend_from_slice(&buf[..n]);
                    if let Some(i) = got.windows(4).position(|w| w == b"\r\n\r\n") {
                        let head = String::from_utf8_lossy(&got[..i]).to_lowercase();
                        let len = head.lines().find_map(|l| l.strip_prefix("content-length: ").map(|v| v.trim().parse::<usize>().unwrap())).unwrap_or(0);
                        if got.len() >= i + 4 + len {
                            break;
                        }
                    }
                }
                s.write_all(a).unwrap();
            }
        });
        addr
    }

    #[test]
    fn redirect_chunked_and_kept_alive() {
        let ep = Endpoint::Plain(serve(vec![
            b"HTTP/1.1 302 Moved Temporarily\r\nLocation: https://script.googleusercontent.com/macros/echo?user_content_key=abc&lib=x\r\nContent-Length: 0\r\n\r\n",
            b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n{\"ok\"\r\n3\r\n:1}\r\n0\r\n\r\n",
        ]));
        let c = Client::default();
        let r = c.exec(&ep, "AKfycbxxxxxxxxxxxxxxxxxxxx", b"{}", Duration::from_secs(3)).unwrap();
        assert_eq!(r.status, 200);
        assert_eq!(r.body, b"{\"ok\":1}");
    }

    #[test]
    fn a_sign_in_redirect_comes_back_as_is() {
        let ep = Endpoint::Plain(serve(vec![b"HTTP/1.1 302 Found\r\nLocation: https://accounts.google.com/ServiceLogin?x=1\r\nContent-Length: 0\r\n\r\n"]));
        let r = Client::default().exec(&ep, "AKfycbxxxxxxxxxxxxxxxxxxxx", b"{}", Duration::from_secs(3)).unwrap();
        assert_eq!(r.status, 302);
        assert!(r.location.unwrap().contains("accounts.google.com"));
    }

    #[test]
    fn a_body_without_length_runs_to_close() {
        let ep = Endpoint::Plain(serve(vec![b"HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n<html>gone</html>"]));
        let r = Client::default().request(&ep, "GET", SCRIPT_HOST, "/", None, Duration::from_secs(3)).unwrap();
        assert_eq!(r.status, 404);
        assert_eq!(r.body, b"<html>gone</html>");
    }

    #[test]
    fn every_byte_on_the_wire_is_counted() {
        let answer: &'static [u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";
        let ep = Endpoint::Plain(serve(vec![answer]));
        let (o0, i0) = wire_totals();
        let r = Client::default().request(&ep, "POST", SCRIPT_HOST, "/x", Some(b"{}"), Duration::from_secs(3)).unwrap();
        assert_eq!(r.body, b"ok");
        let (o1, i1) = wire_totals();
        // other tests run alongside and count too, so at least, not exactly
        assert!(i1 - i0 >= answer.len() as u64);
        assert!(o1 - o0 >= (b"POST /x HTTP/1.1\r\nHost: script.google.com\r\n".len() + 2) as u64);
    }

    #[test]
    fn urls_split() {
        assert_eq!(split_url("https://Script.GoogleUserContent.com/macros/echo?a=1"), Some(("script.googleusercontent.com".into(), "/macros/echo?a=1".into())));
        assert_eq!(split_url("ftp://x"), None);
    }
}
