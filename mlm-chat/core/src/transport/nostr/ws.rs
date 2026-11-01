//! A small WebSocket client (RFC 6455) over the core's own TLS (`gas::http::tls_config`), for the
//! Nostr relays (SPEC 20): text frames only, client frames masked, ping answered, close honoured.
//! Blocking, one connection per relay.
//!
//! `Split::Record` re-frames the TLS ClientHello as two TLS records with the server name cut after
//! its first byte (RFC 8446 §5.1 allows it; Shroudly's and ByeByeDPI's "record split"), and sends
//! them as two TCP writes: a filter that matches the name in one record, even after putting TCP
//! back together, does not see it whole.

use crate::transport::gas::http::tls_config;
use crate::util::random;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{aws_lc_rs, WebPkiSupportedAlgorithms};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, ClientConnection, DigitallySignedStruct, SignatureScheme, StreamOwned};
use sha2::{Digest, Sha256};
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// How the TLS ClientHello leaves the phone.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Split {
    /// As rustls writes it: one record, one write.
    None,
    /// Two records, the server name cut after its first byte.
    Record,
}

impl Split {
    pub fn label(self) -> &'static str {
        match self {
            Split::None => "plain",
            Split::Record => "record-split",
        }
    }
}

/// Where a connection stopped.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Stage {
    Dns,
    Tcp,
    Tls,
    Upgrade,
    Io,
}

impl Stage {
    pub fn code(self) -> &'static str {
        match self {
            Stage::Dns => "dns",
            Stage::Tcp => "tcp",
            Stage::Tls => "tls",
            Stage::Upgrade => "ws",
            Stage::Io => "io",
        }
    }
}

/// The TCP stream, with the first TLS record optionally cut in two.
pub struct SplitStream {
    tcp: TcpStream,
    split: Split,
    done: bool,
}

impl SplitStream {
    pub fn new(tcp: TcpStream, split: Split) -> SplitStream {
        SplitStream { tcp, split, done: false }
    }

    pub fn tcp(&self) -> &TcpStream {
        &self.tcp
    }
}

impl Read for SplitStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.tcp.read(buf)
    }
}

impl Write for SplitStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if self.done || self.split == Split::None {
            return self.tcp.write(buf);
        }
        self.done = true;
        match split_client_hello(buf) {
            Some((a, b)) => {
                self.tcp.write_all(&a)?;
                self.tcp.flush()?;
                // a moment apart, so the two records leave in two segments
                std::thread::sleep(Duration::from_millis(20));
                self.tcp.write_all(&b)?;
                Ok(buf.len())
            }
            None => self.tcp.write(buf),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        self.tcp.flush()
    }
}

/// The ClientHello record `buf` (and anything after it) as two records, cut one byte into the
/// server name; None when `buf` is not one.
pub fn split_client_hello(buf: &[u8]) -> Option<(Vec<u8>, Vec<u8>)> {
    if buf.len() < 5 || buf[0] != 0x16 {
        return None;
    }
    let len = u16::from_be_bytes([buf[3], buf[4]]) as usize;
    let rec = buf.get(5..5 + len)?;
    let rest = &buf[5 + len..];
    let cut = sni_offset(rec)? + 1;
    if cut == 0 || cut >= rec.len() {
        return None;
    }
    let mut a = vec![buf[0], buf[1], buf[2]];
    a.extend_from_slice(&(cut as u16).to_be_bytes());
    a.extend_from_slice(&rec[..cut]);
    let mut b = vec![buf[0], buf[1], buf[2]];
    b.extend_from_slice(&((rec.len() - cut) as u16).to_be_bytes());
    b.extend_from_slice(&rec[cut..]);
    b.extend_from_slice(rest);
    Some((a, b))
}

/// Where the server name starts in a ClientHello handshake message (the record's payload).
fn sni_offset(h: &[u8]) -> Option<usize> {
    if *h.first()? != 1 {
        return None;
    }
    let mut p = 4 + 2 + 32; // handshake header, version, random
    p += 1 + *h.get(p)? as usize; // session id
    p += 2 + u16::from_be_bytes([*h.get(p)?, *h.get(p + 1)?]) as usize; // cipher suites
    p += 1 + *h.get(p)? as usize; // compression
    let end = p + 2 + u16::from_be_bytes([*h.get(p)?, *h.get(p + 1)?]) as usize;
    p += 2;
    while p + 4 <= end.min(h.len()) {
        let kind = u16::from_be_bytes([h[p], h[p + 1]]);
        let n = u16::from_be_bytes([h[p + 2], h[p + 3]]) as usize;
        if kind == 0 {
            // list length (2), name type (1), name length (2), name
            let at = p + 4 + 5;
            return (at < h.len()).then_some(at);
        }
        p += 4 + n;
    }
    None
}

/// TLS to one of our own relays (`super::PINNED`): its certificate must be the pinned one; the
/// web's roots and the name are not consulted (a self-signed certificate on an IP address, T54).
/// The same key exchanges as `tls_config`, so its ClientHello looks the same.
fn pinned_config(sha256: [u8; 32]) -> Arc<ClientConfig> {
    let mut p = aws_lc_rs::default_provider();
    p.kx_groups = vec![aws_lc_rs::kx_group::X25519, aws_lc_rs::kx_group::SECP256R1];
    let algs = p.signature_verification_algorithms;
    let mut c = ClientConfig::builder_with_provider(Arc::new(p))
        .with_safe_default_protocol_versions()
        .expect("TLS 1.2 and 1.3")
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Pinned { sha256, algs }))
        .with_no_client_auth();
    c.alpn_protocols = vec![b"http/1.1".to_vec()];
    Arc::new(c)
}

/// The certificate is ours when its SHA-256 is the pinned one; the handshake's signatures are
/// still checked against it.
#[derive(Debug)]
struct Pinned {
    sha256: [u8; 32],
    algs: WebPkiSupportedAlgorithms,
}

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(&self, end_entity: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: &ServerName<'_>, _: &[u8], _: UnixTime) -> Result<ServerCertVerified, rustls::Error> {
        if Sha256::digest(end_entity.as_ref())[..] == self.sha256 {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(rustls::CertificateError::ApplicationVerificationFailure))
        }
    }

    fn verify_tls12_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.algs)
    }

    fn verify_tls13_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algs)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algs.supported_schemes()
    }
}

/// A byte stream a WebSocket runs over: TLS, or plain TCP (`ws://`, the tests' emulator).
trait Io: Read + Write + Send {
    /// The socket that reads. A read timeout goes on it, not on a clone: on Windows one set on a
    /// clone does not reach it, so every read waited the whole connect timeout and a write queued
    /// behind it waited too (each Nostr send took 5 s in the tests, 27 Oct 2026).
    fn socket(&self) -> &TcpStream;
}

impl Io for TcpStream {
    fn socket(&self) -> &TcpStream {
        self
    }
}

impl Io for StreamOwned<ClientConnection, SplitStream> {
    fn socket(&self) -> &TcpStream {
        self.sock.tcp()
    }
}

pub struct Ws {
    s: Box<dyn Io>,
    buf: Vec<u8>,
    /// When the relay last sent anything, a pong included: a ping it never answers is a dead line.
    pub last_rx: Instant,
}

/// `wss://host[:port][/path]` (or `ws://`, plain) -> (tls, host, port, path).
pub fn parse_url(url: &str) -> Option<(bool, String, u16, String)> {
    let (tls, rest) = match url.strip_prefix("wss://") {
        Some(r) => (true, r),
        None => (false, url.strip_prefix("ws://")?),
    };
    let (hostport, path) = match rest.find('/') {
        Some(i) => (&rest[..i], rest[i..].to_string()),
        None => (rest, "/".to_string()),
    };
    let (host, port) = match hostport.rsplit_once(':') {
        Some((h, p)) => (h.to_string(), p.parse().ok()?),
        None => (hostport.to_string(), if tls { 443 } else { 80 }),
    };
    (!host.is_empty()).then_some((tls, host, port, path))
}

impl Ws {
    /// TCP, TLS (with `split`) and the upgrade. On failure, the stage it stopped at.
    pub fn connect(url: &str, split: Split, timeout: Duration) -> Result<(Ws, [u64; 4]), (Stage, [u64; 4])> {
        let mut ms = [0u64; 4];
        let t0 = Instant::now();
        let (tls, host, port, path) = parse_url(url).ok_or((Stage::Dns, ms))?;
        let addrs: Vec<SocketAddr> = (host.as_str(), port).to_socket_addrs().map_err(|_| (Stage::Dns, ms))?.filter(|a| a.is_ipv4()).collect();
        let addr = *addrs.first().ok_or((Stage::Dns, ms))?;
        ms[0] = t0.elapsed().as_millis() as u64;
        let tcp = TcpStream::connect_timeout(&addr, timeout).map_err(|_| (Stage::Tcp, ms))?;
        let _ = tcp.set_nodelay(true);
        let _ = tcp.set_read_timeout(Some(timeout));
        let _ = tcp.set_write_timeout(Some(timeout));
        ms[1] = t0.elapsed().as_millis() as u64;
        let mut s: Box<dyn Io> = if tls {
            let name = ServerName::try_from(host.clone()).map_err(|_| (Stage::Dns, ms))?;
            let config = super::pinned_sha256(&host).map_or_else(tls_config, pinned_config);
            let mut conn = ClientConnection::new(config, name).map_err(|_| (Stage::Tls, ms))?;
            let mut sock = SplitStream { tcp, split, done: false };
            while conn.is_handshaking() {
                if conn.complete_io(&mut sock).is_err() {
                    return Err((Stage::Tls, ms));
                }
            }
            Box::new(StreamOwned::new(conn, sock))
        } else {
            Box::new(tcp)
        };
        ms[2] = t0.elapsed().as_millis() as u64;
        let key = crate::util::b64url_encode(&random::<16>()).replace('-', "+").replace('_', "/") + "==";
        let req = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: Mozilla/5.0\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n");
        s.write_all(req.as_bytes()).map_err(|_| (Stage::Upgrade, ms))?;
        let mut head = Vec::new();
        let mut byte = [0u8; 1];
        while !head.ends_with(b"\r\n\r\n") {
            if head.len() > 16 * 1024 || s.read(&mut byte).map_err(|_| (Stage::Upgrade, ms))? == 0 {
                return Err((Stage::Upgrade, ms));
            }
            head.push(byte[0]);
        }
        if !head.starts_with(b"HTTP/1.1 101") {
            return Err((Stage::Upgrade, ms));
        }
        ms[3] = t0.elapsed().as_millis() as u64;
        Ok((Ws { s, buf: Vec::new(), last_rx: Instant::now() }, ms))
    }

    /// One masked text frame.
    pub fn send_text(&mut self, text: &str) -> io::Result<()> {
        self.send_frame(0x1, text.as_bytes())
    }

    fn send_frame(&mut self, opcode: u8, payload: &[u8]) -> io::Result<()> {
        let mut f = vec![0x80 | opcode];
        let n = payload.len();
        if n < 126 {
            f.push(0x80 | n as u8);
        } else if n < 65536 {
            f.push(0x80 | 126);
            f.extend_from_slice(&(n as u16).to_be_bytes());
        } else {
            f.push(0x80 | 127);
            f.extend_from_slice(&(n as u64).to_be_bytes());
        }
        let mask: [u8; 4] = random();
        f.extend_from_slice(&mask);
        f.extend(payload.iter().enumerate().map(|(i, b)| b ^ mask[i % 4]));
        self.s.write_all(&f)?;
        self.s.flush()
    }

    fn fill(&mut self, n: usize) -> io::Result<()> {
        let mut chunk = [0u8; 16 * 1024];
        while self.buf.len() < n {
            let got = self.s.read(&mut chunk)?;
            if got == 0 {
                return Err(io::ErrorKind::UnexpectedEof.into());
            }
            self.buf.extend_from_slice(&chunk[..got]);
        }
        Ok(())
    }

    /// The next whole text message (fragments joined); pings are answered on the way. `Err` with
    /// `TimedOut`/`WouldBlock` when nothing came within the read timeout.
    pub fn recv_text(&mut self, max: usize) -> io::Result<String> {
        let mut msg: Vec<u8> = Vec::new();
        loop {
            self.fill(2)?;
            let (b0, b1) = (self.buf[0], self.buf[1]);
            let mut p = 2;
            let mut n = (b1 & 0x7f) as usize;
            if n == 126 {
                self.fill(4)?;
                n = u16::from_be_bytes([self.buf[2], self.buf[3]]) as usize;
                p = 4;
            } else if n == 127 {
                self.fill(10)?;
                n = u64::from_be_bytes(self.buf[2..10].try_into().expect("8")) as usize;
                p = 10;
            }
            let masked = b1 & 0x80 != 0;
            let mask_at = p;
            if masked {
                p += 4;
            }
            if n > max {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "frame too large"));
            }
            self.fill(p + n)?;
            let mut payload = self.buf[p..p + n].to_vec();
            if masked {
                let m = [self.buf[mask_at], self.buf[mask_at + 1], self.buf[mask_at + 2], self.buf[mask_at + 3]];
                payload.iter_mut().enumerate().for_each(|(i, b)| *b ^= m[i % 4]);
            }
            self.buf.drain(..p + n);
            self.last_rx = Instant::now();
            match b0 & 0x0f {
                0x9 => self.send_frame(0xA, &payload)?, // ping: pong
                0xA => {}
                0x8 => return Err(io::ErrorKind::ConnectionAborted.into()),
                0x1 | 0x0 => {
                    msg.extend_from_slice(&payload);
                    if msg.len() > max {
                        return Err(io::Error::new(io::ErrorKind::InvalidData, "message too large"));
                    }
                    if b0 & 0x80 != 0 {
                        return String::from_utf8(msg).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "not text"));
                    }
                }
                _ => {}
            }
        }
    }

    /// A ping of our own, so a quiet connection stays open through NATs and proxies.
    pub fn ping(&mut self) -> io::Result<()> {
        self.send_frame(0x9, b"")
    }

    pub fn set_read_timeout(&self, t: Duration) {
        let _ = self.s.socket().set_read_timeout(Some(t));
    }

    pub fn close(mut self) {
        let _ = self.send_frame(0x8, &[]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls_split_into_host_port_path() {
        assert_eq!(parse_url("wss://relay.damus.io"), Some((true, "relay.damus.io".into(), 443, "/".into())));
        assert_eq!(parse_url("wss://example.org:8443/nostr"), Some((true, "example.org".into(), 8443, "/nostr".into())));
        assert_eq!(parse_url("ws://127.0.0.1:7000"), Some((false, "127.0.0.1".into(), 7000, "/".into())));
        assert_eq!(parse_url("https://x"), None);
    }

    #[test]
    fn a_client_hello_is_cut_in_the_name_and_still_the_same_bytes() {
        // a real ClientHello from rustls, for a name we know
        let mut conn = ClientConnection::new(tls_config(), ServerName::try_from("relay.example.org").unwrap()).unwrap();
        let mut hello = Vec::new();
        conn.write_tls(&mut hello).unwrap();
        let (a, b) = split_client_hello(&hello).expect("a ClientHello");
        assert_eq!(&a[..3], &hello[..3]);
        assert_eq!(&b[..3], &hello[..3]);
        // the name is in neither record whole
        let name = b"relay.example.org";
        assert!(!a.windows(name.len()).any(|w| w == name));
        assert!(!b.windows(name.len()).any(|w| w == name));
        // the two payloads together are the original payload
        let mut joined = a[5..].to_vec();
        joined.extend_from_slice(&b[5..]);
        assert_eq!(joined, hello[5..]);
        assert_eq!(u16::from_be_bytes([a[3], a[4]]) as usize, a.len() - 5);
        assert_eq!(u16::from_be_bytes([b[3], b[4]]) as usize, b.len() - 5);
        // the first record ends one byte into the name
        assert_eq!(*a.last().unwrap(), b'r');
    }
}
