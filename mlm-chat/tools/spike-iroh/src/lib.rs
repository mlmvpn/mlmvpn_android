//! The smallest iroh 1.x endpoint, for the size and behaviour comparison with spike-libp2p
//! (PLAN section 17.2, decision T11). The `Minimal` preset sets only the TLS crypto provider:
//! no n0 relay servers, no DNS discovery, nothing central.
//!
//! - `loopback`: two endpoints on 127.0.0.1 dial each other by key + direct address.
//! - `chat` (phone, netprobe): both phones use the home PC's own iroh relay (TLS pinned by the
//!   certificate hash in the observer card), meet through a small "hub" endpoint on the same PC,
//!   connect, and time a small message and 1 MiB before and after iroh's hole punching.
//! - `server::serve_relay_and_hub` (feature `relay-server`, inside mlm-observer): that relay and hub.
//!
//! Nothing here logs or reports an IP address: only its family and kind.

use anyhow::{anyhow, bail, Result};
use iroh::endpoint::{presets, Connection};
use iroh::tls::CaTlsConfig;
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayConfig, RelayMap, RelayMode, RelayUrl, TransportAddr};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use serde_json::{json, Value};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const ALPN: &[u8] = b"/mlmchat/spike/1";
pub const HUB_ALPN: &[u8] = b"/mlmchat/spike-hub/1";
pub const DEFAULT_BASE_PORT: u16 = 40300;
pub const DEFAULT_ROOM: &str = "mlmchat-spike";

const K_PING: u8 = 1;
const K_BULK: u8 = 2;
const K_DONE: u8 = 3;
const BULK: usize = 1 << 20;
const MAX_FRAME: usize = 2 << 20;

// ---- log and cancel (read by the JNI side) ----

static LOG: Mutex<Vec<String>> = Mutex::new(Vec::new());
static CANCEL: AtomicBool = AtomicBool::new(false);
static ECHO: AtomicBool = AtomicBool::new(false);

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
        if colons >= 2 || (dots == 3 && run.chars().all(|c| c.is_ascii_digit() || c == '.')) {
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

fn describe(t: &TransportAddr) -> Value {
    match t {
        TransportAddr::Ip(a) => json!({ "path": "direct", "family": if a.is_ipv4() { "v4" } else { "v6" }, "kind": ip_kind(a.ip()) }),
        TransportAddr::Relay(_) => json!({ "path": "relay" }),
        _ => json!({ "path": "custom" }),
    }
}

/// The path the connection uses right now: `relay`, `direct` or `none`, with its description.
fn selected(conn: &Connection) -> (&'static str, Value) {
    for p in conn.paths().iter() {
        if p.is_selected() {
            let d = describe(p.remote_addr());
            return (if p.is_relay() { "relay" } else { "direct" }, d);
        }
    }
    ("none", Value::Null)
}

// ---- TLS to the home relay: pinned by the certificate's SHA-256, no CA ----

pub fn cert_pin(der: &[u8]) -> [u8; 32] {
    let d = ring::digest::digest(&ring::digest::SHA256, der);
    let mut out = [0u8; 32];
    out.copy_from_slice(d.as_ref());
    out
}

#[derive(Debug)]
struct PinVerifier {
    pin: [u8; 32],
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if cert_pin(end_entity.as_ref()) == self.pin {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(rustls::CertificateError::ApplicationVerificationFailure))
        }
    }

    fn verify_tls12_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

fn pinned_tls(pin: [u8; 32]) -> CaTlsConfig {
    CaTlsConfig::custom_server_cert_verifier(Arc::new(move |provider| Ok(Arc::new(PinVerifier { pin, provider }) as Arc<dyn ServerCertVerifier>)))
}

fn relay_url(ip: IpAddr, port: u16) -> Result<RelayUrl> {
    let host = match ip {
        IpAddr::V4(v) => v.to_string(),
        IpAddr::V6(v) => format!("[{v}]"),
    };
    format!("https://{host}:{port}/").parse().map_err(|e| anyhow!("relay url: {e}"))
}

async fn endpoint(url: &RelayUrl, qad_port: u16, pin: [u8; 32], alpn: &[u8], bind_port: u16, secret: Option<iroh::SecretKey>) -> Result<Endpoint> {
    let map = RelayMap::from_iter([RelayConfig::new(url.clone(), Some(iroh_relay::RelayQuicConfig::new(qad_port)))]);
    let mut b = Endpoint::builder(presets::Minimal)
        .relay_mode(RelayMode::Custom(map))
        .ca_tls_config(pinned_tls(pin))
        .alpns(vec![alpn.to_vec()]);
    if let Some(s) = secret {
        b = b.secret_key(s);
    }
    if bind_port != 0 {
        b = b.bind_addr(SocketAddr::from(([0, 0, 0, 0], bind_port))).map_err(|e| anyhow!("bind addr: {e}"))?;
    }
    b.bind().await.map_err(|e| anyhow!("bind: {e}"))
}

// ---- test messages: one stream each, kind byte + payload, answered with the length ----

async fn exchange(conn: &Connection, body: &[u8]) -> Result<u64> {
    let (mut send, mut recv) = conn.open_bi().await.map_err(|e| anyhow!("open stream: {e}"))?;
    send.write_all(body).await.map_err(|e| anyhow!("write: {e}"))?;
    send.finish().map_err(|e| anyhow!("finish: {e}"))?;
    let got = recv.read_to_end(64).await.map_err(|e| anyhow!("read: {e}"))?;
    got.get(..8).map(|b| u64::from_be_bytes(b.try_into().unwrap_or([0; 8]))).ok_or_else(|| anyhow!("short answer"))
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

/// Answers the other phone's streams until the connection closes; flips `done` on its K_DONE.
async fn serve_streams(conn: Connection, done: Arc<AtomicBool>) {
    while let Ok((mut send, mut recv)) = conn.accept_bi().await {
        let done = done.clone();
        tokio::spawn(async move {
            if let Ok(b) = recv.read_to_end(MAX_FRAME).await {
                if b.first() == Some(&K_DONE) {
                    done.store(true, Ordering::Relaxed);
                }
                let _ = send.write_all(&(b.len() as u64).to_be_bytes()).await;
                let _ = send.finish();
            }
        });
    }
}

/// A small message then 1 MiB; filed under the path in use when each one finished.
async fn measure(conn: &Connection, out: &mut Value) {
    let t = Instant::now();
    match exchange(conn, &payload(K_PING, 6)).await {
        Ok(_) => {
            let (p, _) = selected(conn);
            if !out[p].is_object() {
                out[p] = json!({});
            }
            out[p]["message_rtt_ms"] = json!(t.elapsed().as_millis() as u64);
        }
        Err(e) => {
            out["error"] = json!(scrub(&e.to_string()));
            return;
        }
    }
    let t = Instant::now();
    match exchange(conn, &payload(K_BULK, BULK)).await {
        Ok(n) => {
            let took = t.elapsed().as_millis() as u64;
            let (p, _) = selected(conn);
            if !out[p].is_object() {
                out[p] = json!({});
            }
            out[p]["upload_1mib_ms"] = json!(took);
            out[p]["upload_mbps"] = json!(((BULK as f64 * 8.0 / 1e6) / (took.max(1) as f64 / 1000.0) * 100.0).round() / 100.0);
            out[p]["upload_ok"] = json!(n == BULK as u64);
            log(format!("{p}: {}", out[p]));
        }
        Err(e) => out["error"] = json!(scrub(&e.to_string())),
    }
}

// ---- two phones through the home relay ----

/// `args`: `{"obs": <decoded observer card>, "room": "...", "duration_s": 120}`.
pub async fn chat_run(args: &Value) -> Result<Value> {
    CANCEL.store(false, Ordering::Relaxed);
    let duration = Duration::from_secs(args["duration_s"].as_u64().unwrap_or(120).clamp(20, 600));
    let cancelled = async {
        while !CANCEL.load(Ordering::Relaxed) {
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
    };
    tokio::select! {
        r = tokio::time::timeout(duration, chat_inner(args)) => match r {
            Ok(r) => r,
            Err(_) => Ok(json!({ "ok": false, "error": format!("time limit {} s", duration.as_secs()) })),
        },
        _ = cancelled => Ok(json!({ "ok": false, "error": "cancelled" })),
    }
}

async fn chat_inner(args: &Value) -> Result<Value> {
    let t0 = Instant::now();
    let ms = || t0.elapsed().as_millis() as u64;
    let obs = &args["obs"];
    let ir = obs.get("iroh").filter(|v| v.is_object()).ok_or_else(|| anyhow!("the observer card has no iroh relay: run the new mlm-observer on the PC and scan its card again"))?;
    let hub: EndpointId = ir["hub"].as_str().unwrap_or("").parse().map_err(|e| anyhow!("hub id in the card: {e}"))?;
    let base = ir["port"].as_u64().unwrap_or(DEFAULT_BASE_PORT as u64) as u16;
    let pin_b = base64url(ir["pin"].as_str().unwrap_or(""))?;
    let pin: [u8; 32] = pin_b.try_into().map_err(|_| anyhow!("relay pin must be 32 bytes"))?;
    let ips: Vec<IpAddr> = obs["addrs"].as_array().map(|a| a.iter().filter_map(|v| v.as_str()?.trim().parse().ok()).collect()).unwrap_or_default();
    let ip = ips.iter().find(|i| i.is_ipv4()).or(ips.first()).copied().ok_or_else(|| anyhow!("the observer card has no address"))?;
    let url = relay_url(ip, base + 1)?;
    let room = args["room"].as_str().filter(|s| !s.is_empty()).unwrap_or(DEFAULT_ROOM).to_string();

    let mut r = json!({ "stack": "iroh 1.4 (Minimal preset, own relay)", "relay_family": if ip.is_ipv4() { "v4" } else { "v6" } });
    let ep = endpoint(&url, base + 2, pin, ALPN, 0, None).await?;
    r["bind_ms"] = json!(ms());
    log(format!("iroh endpoint ready in {} ms; connecting to the home relay", ms()));
    if tokio::time::timeout(Duration::from_secs(20), ep.online()).await.is_err() {
        r["error"] = json!("could not reach the home relay (TLS on its HTTPS port) in 20 s");
        r["ok"] = json!(false);
        ep.close().await;
        return Ok(r);
    }
    r["relay_ms"] = json!(ms());
    log(format!("home relay connected in {} ms", ms()));

    // Meet the other phone at the hub: send the room, get its endpoint id.
    let hub_addr = EndpointAddr::new(hub).with_relay_url(url.clone()).with_ip_addr(SocketAddr::new(ip, base + 3));
    let partner: EndpointId = {
        let hc = ep.connect(hub_addr, HUB_ALPN).await.map_err(|e| anyhow!("hub: {}", scrub(&e.to_string())))?;
        r["hub_connected_ms"] = json!(ms());
        r["hub_path"] = selected(&hc).1;
        let (mut send, mut recv) = hc.open_bi().await.map_err(|e| anyhow!("hub stream: {e}"))?;
        send.write_all(room.as_bytes()).await.map_err(|e| anyhow!("hub write: {e}"))?;
        send.finish().map_err(|e| anyhow!("hub finish: {e}"))?;
        log("waiting at the hub for the other phone");
        let ans = recv.read_to_end(256).await.map_err(|e| anyhow!("hub read: {e}"))?;
        hc.close(0u32.into(), b"bye");
        let s = String::from_utf8_lossy(&ans).to_string();
        s.trim().parse().map_err(|_| anyhow!("hub: {}", s.trim()))?
    };
    r["other_phone_found_ms"] = json!(ms());
    log(format!("other phone found in {} ms", ms()));

    // One side dials, so the phones do not race two connections.
    let dialer = ep.id().as_bytes() < partner.as_bytes();
    let conn = if dialer {
        ep.connect(EndpointAddr::new(partner).with_relay_url(url.clone()), ALPN).await.map_err(|e| anyhow!("connect: {}", scrub(&e.to_string())))?
    } else {
        loop {
            let inc = ep.accept().await.ok_or_else(|| anyhow!("endpoint closed"))?;
            let c = inc.await.map_err(|e| anyhow!("accept: {}", scrub(&e.to_string())))?;
            if c.remote_id() == partner {
                break c;
            }
        }
    };
    r["connected_ms"] = json!(ms());
    r["we_dialed"] = json!(dialer);
    r["first_path"] = selected(&conn).1;
    log(format!("connected to the other phone in {} ms over {}", ms(), selected(&conn).0));
    let partner_done = Arc::new(AtomicBool::new(false));
    tokio::spawn(serve_streams(conn.clone(), partner_done.clone()));

    let mut paths = json!({});
    measure(&conn, &mut paths).await;
    // Hole punching: wait up to 20 s for iroh to move the connection to a direct path.
    let t_wait = Instant::now();
    while selected(&conn).0 != "direct" && t_wait.elapsed() < Duration::from_secs(20) && conn.close_reason().is_none() {
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    let (p, d) = selected(&conn);
    r["direct"] = if p == "direct" { d } else { Value::Null };
    r["direct_ms"] = if p == "direct" { json!(ms()) } else { Value::Null };
    log(format!("path after hole punching: {p}"));
    if p == "direct" && paths.get("direct").and_then(|v| v.get("upload_1mib_ms")).is_none() {
        measure(&conn, &mut paths).await;
    }
    r["paths"] = paths.clone();

    let _ = exchange(&conn, &[K_DONE]).await;
    let t_done = Instant::now();
    // The other phone may still be timing its own 1 MiB: keep answering it for a while.
    while !partner_done.load(Ordering::Relaxed) && t_done.elapsed() < Duration::from_secs(30) && conn.close_reason().is_none() {
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    conn.close(0u32.into(), b"done");
    ep.close().await;
    r["took_ms"] = json!(ms());
    r["ok"] = json!(paths.as_object().is_some_and(|m| m.keys().any(|k| k == "relay" || k == "direct")));
    Ok(r)
}

/// `mlmobs1:` + base64url(JSON) -> the decoded card (the phone app does this in Java).
pub fn decode_observer_card(text: &str) -> Result<Value> {
    let body = text.trim().strip_prefix("mlmobs1:").ok_or_else(|| anyhow!("not an observer card"))?;
    Ok(serde_json::from_slice(&base64url(body)?)?)
}

/// Small decoder, so the phone library needs no extra crate for one 43-character field.
fn base64url(s: &str) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut acc: u32 = 0;
    let mut bits = 0;
    for c in s.trim().trim_end_matches('=').bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'-' | b'+' => 62,
            b'_' | b'/' => 63,
            _ => bail!("bad base64"),
        } as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Ok(out)
}

// ---- desktop relay and hub (inside mlm-observer) ----

#[cfg(feature = "relay-server")]
pub mod server {
    use super::*;
    use std::collections::HashMap;
    use tokio::sync::oneshot;

    /// A fresh self-signed certificate for the relay's TLS: (certificate DER, private key DER).
    /// Phones pin its SHA-256 from the observer card, so no CA or host name is involved.
    pub fn make_cert() -> Result<(Vec<u8>, Vec<u8>)> {
        let ck = rcgen::generate_simple_self_signed(vec!["mlmchat-observer".to_string()])?;
        Ok((ck.cert.der().to_vec(), ck.signing_key.serialize_der()))
    }

    pub struct Cfg {
        pub secret: [u8; 32],
        pub cert_der: Vec<u8>,
        pub key_der: Vec<u8>,
        /// HTTP base+0 (not needed from outside), HTTPS base+1 (TCP), QUIC address discovery
        /// base+2 (UDP), hub base+3 (UDP).
        pub base_port: u16,
    }

    pub fn hub_id(secret: &[u8; 32]) -> EndpointId {
        iroh::SecretKey::from_bytes(secret).public()
    }

    type Waiting = Arc<Mutex<HashMap<String, (EndpointId, oneshot::Sender<EndpointId>)>>>;

    /// The iroh relay server and the hub endpoint; runs until the future is dropped.
    pub async fn serve_relay_and_hub(cfg: Cfg) -> Result<()> {
        use iroh_relay::server as rs;
        let base = cfg.base_port;
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let tls = rustls::ServerConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()?
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(cfg.cert_der.clone())],
                rustls::pki_types::PrivateKeyDer::try_from(cfg.key_der.clone()).map_err(|e| anyhow!("relay key: {e}"))?,
            )?;
        let any = |p: u16| SocketAddr::from(([0, 0, 0, 0], p));
        let mut relay_cfg = rs::RelayConfig::new(any(base));
        relay_cfg.tls = Some(rs::TlsConfig::new(any(base + 1), rs::CertConfig::Manual { server_config: tls }));
        let mut sc = rs::ServerConfig::default();
        sc.relay = Some(relay_cfg);
        sc.quic = Some(rs::QuicConfig::new(any(base + 2)));
        let mut relay = rs::Server::spawn(sc).await.map_err(|e| anyhow!("iroh relay: {e}"))?;
        log(format!("iroh relay: HTTPS on TCP {} and QUIC address discovery on UDP {}", base + 1, base + 2));

        // The hub reaches its own relay over loopback; phones use the card's address.
        let url = relay_url(IpAddr::from([127, 0, 0, 1]), base + 1)?;
        let ep = endpoint(&url, base + 2, cert_pin(&cfg.cert_der), HUB_ALPN, base + 3, Some(iroh::SecretKey::from_bytes(&cfg.secret))).await?;
        log(format!("iroh hub: {} on UDP {}", ep.id(), base + 3));
        let waiting: Waiting = Arc::new(Mutex::new(HashMap::new()));
        loop {
            tokio::select! {
                _ = relay.join() => bail!("iroh relay stopped"),
                inc = ep.accept() => {
                    let Some(inc) = inc else { bail!("hub endpoint closed") };
                    tokio::spawn(hub_one(inc, waiting.clone()));
                }
            }
        }
    }

    async fn hub_one(inc: iroh::endpoint::Incoming, waiting: Waiting) {
        let Ok(conn) = inc.await else { return };
        let who = conn.remote_id();
        let Ok((mut send, mut recv)) = conn.accept_bi().await else { return };
        let Ok(room) = recv.read_to_end(128).await else { return };
        let room = String::from_utf8_lossy(&room).to_string();
        let pair = {
            let mut w = waiting.lock().unwrap();
            match w.remove(&room) {
                Some((other, tx)) if other != who => {
                    let _ = tx.send(who);
                    Ok(other)
                }
                _ => {
                    let (tx, rx) = oneshot::channel();
                    w.insert(room.clone(), (who, tx));
                    Err(rx)
                }
            }
        };
        let answer = match pair {
            Ok(other) => {
                log("iroh hub: second phone arrived; paired");
                other.to_string()
            }
            Err(rx) => {
                log("iroh hub: a phone is waiting for its partner");
                match tokio::time::timeout(Duration::from_secs(120), rx).await {
                    Ok(Ok(other)) => other.to_string(),
                    _ => {
                        let mut w = waiting.lock().unwrap();
                        if w.get(&room).is_some_and(|(id, _)| *id == who) {
                            w.remove(&room);
                        }
                        "no other phone came within 120 s".to_string()
                    }
                }
            }
        };
        let _ = send.write_all(answer.as_bytes()).await;
        let _ = send.finish();
        conn.closed().await;
    }
}

// ---- loopback ----

pub async fn loopback_run() -> Result<Value> {
    let t0 = Instant::now();
    let server = Endpoint::builder(presets::Minimal)
        .alpns(vec![ALPN.to_vec()])
        .bind_addr("127.0.0.1:0")
        .map_err(|e| anyhow!("bind addr: {e}"))?
        .bind()
        .await
        .map_err(|e| anyhow!("bind: {e}"))?;
    let bound_ms = t0.elapsed().as_millis() as u64;
    let accept = {
        let ep = server.clone();
        tokio::spawn(async move {
            let incoming = ep.accept().await.ok_or_else(|| anyhow!("endpoint closed"))?;
            let conn = incoming.await.map_err(|e| anyhow!("accept: {e}"))?;
            let (mut send, mut recv) = conn.accept_bi().await.map_err(|e| anyhow!("accept_bi: {e}"))?;
            let got = recv.read_to_end(64 * 1024).await.map_err(|e| anyhow!("read: {e}"))?;
            send.write_all(&got).await.map_err(|e| anyhow!("write: {e}"))?;
            send.finish().map_err(|e| anyhow!("finish: {e}"))?;
            conn.closed().await;
            Ok::<_, anyhow::Error>(())
        })
    };

    let client = Endpoint::builder(presets::Minimal)
        .bind_addr("127.0.0.1:0")
        .map_err(|e| anyhow!("bind addr: {e}"))?
        .bind()
        .await
        .map_err(|e| anyhow!("bind: {e}"))?;
    let addr = server.addr();
    let t1 = Instant::now();
    let conn = client.connect(addr, ALPN).await.map_err(|e| anyhow!("connect: {e}"))?;
    let connect_ms = t1.elapsed().as_millis() as u64;
    let (mut send, mut recv) = conn.open_bi().await.map_err(|e| anyhow!("open_bi: {e}"))?;
    send.write_all(b"salam").await.map_err(|e| anyhow!("write: {e}"))?;
    send.finish().map_err(|e| anyhow!("finish: {e}"))?;
    let echo = recv.read_to_end(64 * 1024).await.map_err(|e| anyhow!("read: {e}"))?;
    let echo_ms = t1.elapsed().as_millis() as u64;
    conn.close(0u32.into(), b"bye");
    let _ = tokio::time::timeout(std::time::Duration::from_secs(3), accept).await;
    client.close().await;
    server.close().await;
    Ok(json!({
        "ok": echo == b"salam",
        "preset": "Minimal (no relay, no discovery)",
        "bind_ms": bound_ms,
        "connect_ms": connect_ms,
        "echo_ms": echo_ms,
    }))
}

fn run_json(cmd: &str) -> String {
    let rt = match tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build() {
        Ok(r) => r,
        Err(e) => return json!({ "ok": false, "error": e.to_string() }).to_string(),
    };
    let r = if cmd.trim_start().starts_with('{') {
        match serde_json::from_str::<Value>(cmd) {
            Ok(v) if v["cmd"] == "chat" => rt.block_on(chat_run(&v)),
            Ok(v) => Err(anyhow!("unknown command {}", v["cmd"])),
            Err(e) => Err(anyhow!("bad json: {e}")),
        }
    } else {
        rt.block_on(loopback_run())
    };
    let out = r.unwrap_or_else(|e| json!({ "ok": false, "error": scrub(&format!("{e:#}")) })).to_string();
    rt.shutdown_timeout(Duration::from_secs(2));
    out
}

/// JNI entry (class com.mlmchat.spike.SpikeIroh, `static native String run(String cmd)`):
/// `loopback` or `{"cmd":"chat",...}`.
#[no_mangle]
pub extern "system" fn Java_com_mlmchat_spike_SpikeIroh_run<'l>(
    mut env: jni::JNIEnv<'l>,
    _c: jni::objects::JClass<'l>,
    cmd: jni::objects::JString<'l>,
) -> jni::sys::jstring {
    let c: String = env.get_string(&cmd).map(|s| s.into()).unwrap_or_default();
    let out = std::panic::catch_unwind(|| run_json(&c)).unwrap_or_else(|_| r#"{"ok":false,"error":"panic"}"#.into());
    env.new_string(out).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_spike_SpikeIroh_cancel<'l>(_env: jni::JNIEnv<'l>, _c: jni::objects::JClass<'l>) {
    cancel();
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_spike_SpikeIroh_pollLog<'l>(env: jni::JNIEnv<'l>, _c: jni::objects::JClass<'l>) -> jni::sys::jstring {
    let s = std::panic::catch_unwind(drain_log).unwrap_or_default();
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64url_of_a_pin() {
        // 32 bytes 0..31, base64url without padding.
        let b = base64url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8").unwrap();
        assert_eq!(b, (0u8..32).collect::<Vec<_>>());
    }

    #[test]
    fn scrub_hides_addresses() {
        let s = scrub("connect to 203.0.113.7:40301 or [2001:db8::1]:40303 failed");
        assert!(!s.contains("203.0.113.7") && !s.contains("2001:db8::1"), "{s}");
    }
}
