//! Two phones, no helper: every address in the other phone's card, over TCP (TLS 1.3) and
//! over UDP (QUIC). Both phones run this at the same time, so each one's outbound attempts are
//! the other's inbound test. Paths that connect are measured: 10 round trips, then 1 MiB each way.

use crate::card::PeerCard;
use crate::session::Session;
use crate::tlsutil;
use crate::util::{canon, err_kind, for_socket, ip_kind, kind_label, ms};
use crate::{measure, plog};
use anyhow::{anyhow, Result};
use serde_json::{json, Value};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::net::TcpStream;
use tokio::time::timeout;

#[derive(Clone)]
struct Path {
    label: String,
    kind: String,
    transport: &'static str,
    addr: SocketAddr,
}

fn paths(peer: &PeerCard) -> Vec<Path> {
    let mut out = vec![];
    for c in &peer.ips {
        let Ok(ip) = c.a.parse() else { continue };
        let ip: std::net::IpAddr = ip;
        for (t, port) in [("tcp", peer.tcp), ("quic", peer.udp)] {
            out.push(Path { label: format!("{}-{}", c.k, t), kind: c.k.clone(), transport: t, addr: SocketAddr::new(ip, port) });
        }
    }
    for m in &peer.pubs {
        let Ok(a) = m.a.parse::<SocketAddr>() else { continue };
        let t = if m.k == "tcp" { "tcp" } else { "quic" };
        let kind = format!("{}-mapped", kind_label(ip_kind(a.ip())));
        if !out.iter().any(|p| p.addr == a && p.transport == t) {
            out.push(Path { label: format!("{kind}-{t}"), kind, transport: t, addr: a });
        }
    }
    out
}

/// One TLS-over-TCP connection to `p`, measured.
pub async fn tcp_once(sess: &Session, peer: &PeerCard, addr: SocketAddr, label: &str, bytes: u64) -> Result<Value> {
    let t0 = Instant::now();
    let s = timeout(Duration::from_secs(5), TcpStream::connect(addr)).await??;
    s.set_nodelay(true)?;
    let connect_ms = ms(t0.elapsed());
    let cfg = tlsutil::client_config(peer.pin()?, tlsutil::alpn_for(&peer.sid))?;
    let t1 = Instant::now();
    let tls = timeout(Duration::from_secs(10), tokio_rustls::TlsConnector::from(Arc::new(cfg)).connect(tlsutil::any_name(), s)).await??;
    let tls_ms = ms(t1.elapsed());
    let _g = sess.measure_lock.lock().await;
    let mut m = measure::client(tls, &sess.sid, label, bytes).await?;
    m["connect_ms"] = json!(connect_ms);
    m["tls_ms"] = json!(tls_ms);
    Ok(m)
}

/// One QUIC connection to `p` from the session's endpoint, measured.
pub async fn quic_once(sess: &Session, peer: &PeerCard, addr: SocketAddr, label: &str, bytes: u64) -> Result<Value> {
    let cfg = tlsutil::quic_client(peer.pin()?, tlsutil::alpn_for(&peer.sid))?;
    let to = for_socket(addr, sess.endpoint_v6);
    let t0 = Instant::now();
    let conn = timeout(Duration::from_secs(6), sess.endpoint.connect_with(cfg, to, "localhost")?).await??;
    let quic_ms = ms(t0.elapsed());
    let _g = sess.measure_lock.lock().await;
    let (send, recv) = conn.open_bi().await?;
    let r = measure::client(tokio::io::join(recv, send), &sess.sid, label, bytes).await;
    conn.close(0u32.into(), b"done");
    let mut m = r?;
    m["quic_ms"] = json!(quic_ms);
    Ok(m)
}

async fn try_path(sess: Arc<Session>, peer: PeerCard, p: Path, deadline: Instant, bytes: u64) -> Value {
    let started = Instant::now();
    let mut attempts = 0u32;
    let mut last_err = String::new();
    while Instant::now() < deadline && !crate::cancelled() {
        attempts += 1;
        let r = match p.transport {
            "tcp" => tcp_once(&sess, &peer, p.addr, &p.label, bytes).await,
            _ => quic_once(&sess, &peer, p.addr, &p.label, bytes).await,
        };
        match r {
            Ok(m) => {
                plog!("outbound {} ok after {} tries", p.label, attempts);
                return json!({ "path": p.label, "kind": p.kind, "transport": p.transport, "ok": true,
                               "attempts": attempts, "first_ok_ms": started.elapsed().as_millis() as u64, "measure": m });
            }
            Err(e) => {
                last_err = err_kind(&e);
                // The other phone may start a few seconds later: keep trying until the deadline.
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
        }
    }
    plog!("outbound {} failed: {}", p.label, last_err);
    json!({ "path": p.label, "kind": p.kind, "transport": p.transport, "ok": false, "attempts": attempts, "error": last_err })
}

pub async fn run(sess: Arc<Session>, v: &Value) -> Result<Value> {
    let peer = PeerCard::decode(v["peer"].as_str().ok_or_else(|| anyhow!("peer card missing"))?)?;
    if peer.sid == sess.sid {
        return Err(anyhow!("that is this phone's own card"));
    }
    let dur = Duration::from_secs(v["duration_s"].as_u64().unwrap_or(40).clamp(10, 600));
    let bytes = v["bytes"].as_u64().unwrap_or(measure::DEFAULT_BYTES);
    let inbound_from = sess.inbound_len();
    let deadline = Instant::now() + dur;
    let list = paths(&peer);
    plog!("direct test: {} paths for {} s", list.len(), dur.as_secs());
    let mut set = tokio::task::JoinSet::new();
    for p in list {
        set.spawn(try_path(sess.clone(), peer.clone(), p, deadline, bytes));
    }
    let mut outbound = vec![];
    while let Some(r) = set.join_next().await {
        if let Ok(v) = r {
            outbound.push(v);
        }
    }
    // Let the other phone's late attempts arrive, then report what came in.
    let settle = deadline.saturating_duration_since(Instant::now()).min(Duration::from_secs(15));
    tokio::time::sleep(settle).await;
    outbound.sort_by(|a, b| a["path"].as_str().cmp(&b["path"].as_str()));
    let inbound: Vec<Value> = sess
        .inbound_since(inbound_from)
        .into_iter()
        .filter(|e| e["info"]["peer_sid"].as_str().map(|s| s == peer.sid).unwrap_or(true))
        .collect();
    let _ = canon;
    Ok(json!({ "peer_sid": peer.sid, "outbound": outbound, "inbound": inbound }))
}
