//! Hole punching through both NATs, with an observer as the introducer.
//!
//! 1. Each phone registers a fresh UDP socket with the observer (`reg`) and opens a control
//!    connection from a fresh TCP port P (`room_join`). The observer now knows both mappings and
//!    tells each phone the other's, with a start time.
//! 2. UDP: both phones send small random packets to each other's mapping for 1.5 s (that opens
//!    their own NAT for the other), then role a dials QUIC on the same socket and role b accepts.
//! 3. TCP: role a dials the other mapping from P again and again; role b listens on P and dials
//!    too, so either a crossing SYN (simultaneous open) or a plain accept connects them. The one
//!    connection that results is always the one role a started, so both agree on it.
//! 4. Whatever connected is measured inside TLS / QUIC.

use crate::card::{role, room_id, ObserverCard, PeerCard};
use crate::obsclient::{udp_call, ObsConn};
use crate::session::Session;
use crate::tlsutil;
use crate::util::{canon, err_kind, ms, rand_fill};
use crate::{measure, plog};
use anyhow::{anyhow, bail, Result};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::net::{TcpStream, UdpSocket};
use tokio::time::timeout;

const PUNCH_FOR: Duration = Duration::from_millis(1500);

fn parse_sa(v: &Value) -> Option<SocketAddr> {
    v.as_str().and_then(|s| s.parse().ok()).map(canon)
}

pub async fn run(sess: Arc<Session>, v: &Value) -> Result<Value> {
    let peer = PeerCard::decode(v["peer"].as_str().ok_or_else(|| anyhow!("peer card missing"))?)?;
    let obs = ObserverCard::decode(v["observer"].as_str().ok_or_else(|| anyhow!("observer card missing"))?)?;
    let bytes = v["bytes"].as_u64().unwrap_or(measure::DEFAULT_BYTES);
    let window = Duration::from_secs(v["window_s"].as_u64().unwrap_or(12).clamp(3, 60));
    let room = room_id(&sess.sid, &peer.sid);
    let my_role = role(&sess.sid, &peer.sid);
    let cid = hex::encode(crate::util::rand_bytes::<8>());
    let pk = obs.pk()?;
    let mut out = json!({ "role": my_role, "observer": obs.label() });

    // 1. Register both sockets with the introducer.
    let udp = UdpSocket::bind(crate::util::any_v4_port(0)).await?;
    match udp_call(&udp, false, &pk, obs.main()?, &json!({ "t": "reg", "room": room, "role": my_role, "cid": cid }), 3, Duration::from_millis(1500)).await {
        Ok(a) => out["udp_reg"] = json!({ "ok": true, "rtt_ms": a.rtt_ms }),
        Err(e) => out["udp_reg"] = json!({ "ok": false, "error": err_kind(&e) }),
    }
    let mut ctl = ObsConn::connect(&obs, obs.main()?, Some(crate::util::any_v4_port(0)), &cid).await?;
    let local_port = ctl.local.port();
    plog!("punch: waiting for the other phone in the room (role {my_role})");
    let info = ctl.call_within(json!({ "t": "room_join", "room": room, "role": my_role }), Duration::from_secs(130)).await?;
    let go_in = Duration::from_millis(info["go_in_ms"].as_u64().unwrap_or(900));
    let peer_udp = parse_sa(&info["udp"]);
    let peer_tcp = parse_sa(&info["tcp"]);
    out["peer_seen"] = json!({ "udp": peer_udp.is_some(), "tcp": peer_tcp.is_some() });
    tokio::time::sleep(go_in).await;
    plog!("punch: go");

    let udp_task = async {
        match peer_udp {
            Some(to) => udp_punch(&sess, &peer, my_role, udp, to, window, bytes).await.unwrap_or_else(|e| json!({ "ok": false, "error": err_kind(&e) })),
            None => json!({ "ok": false, "error": "the other phone's UDP mapping is unknown" }),
        }
    };
    let tcp_task = async {
        match peer_tcp {
            Some(to) => tcp_punch(&sess, &peer, my_role, local_port, to, window, bytes).await.unwrap_or_else(|e| json!({ "ok": false, "error": err_kind(&e) })),
            None => json!({ "ok": false, "error": "the other phone's TCP mapping is unknown" }),
        }
    };
    let (u, t) = tokio::join!(udp_task, tcp_task);
    let _ = ctl.ns.send_json(&json!({ "t": "bye" })).await;
    out["udp"] = u;
    out["tcp"] = t;
    Ok(out)
}

async fn udp_punch(sess: &Session, peer: &PeerCard, my_role: &str, udp: UdpSocket, to: SocketAddr, window: Duration, bytes: u64) -> Result<Value> {
    let t0 = Instant::now();
    let mut pkt = [0u8; 12];
    let mut sent = 0u32;
    while t0.elapsed() < PUNCH_FOR {
        rand_fill(&mut pkt);
        let _ = udp.send_to(&pkt, to).await;
        sent += 1;
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    let std_sock = udp.into_std()?;
    let server = if my_role == "b" { Some(tlsutil::quic_server(&sess.ident, tlsutil::alpn_for(&sess.sid))?) } else { None };
    let ep = quinn::Endpoint::new(quinn::EndpointConfig::default(), server, std_sock, Arc::new(quinn::TokioRuntime))?;
    let r = if my_role == "a" {
        let cfg = tlsutil::quic_client(peer.pin()?, tlsutil::alpn_for(&peer.sid))?;
        let conn = timeout(window, ep.connect_with(cfg, to, "localhost")?).await??;
        let connected_ms = ms(t0.elapsed());
        plog!("punch udp: quic connected");
        let _g = sess.measure_lock.lock().await;
        let (send, recv) = conn.open_bi().await?;
        let m = measure::client(tokio::io::join(recv, send), &sess.sid, "punch-quic", bytes).await;
        conn.close(0u32.into(), b"done");
        json!({ "ok": true, "punch_packets": sent, "connected_ms": connected_ms, "measure": m.map_err(|e| anyhow!(err_kind(&e)))? })
    } else {
        let incoming = timeout(window + Duration::from_secs(2), ep.accept()).await?.ok_or_else(|| anyhow!("endpoint closed"))?;
        let conn = timeout(Duration::from_secs(10), incoming).await??;
        let connected_ms = ms(t0.elapsed());
        plog!("punch udp: quic accepted");
        let (send, recv) = timeout(Duration::from_secs(15), conn.accept_bi()).await??;
        let info = measure::server(tokio::io::join(recv, send), &sess.sid).await;
        // Wait for role a to close, so nothing it still needs is thrown away.
        let _ = timeout(Duration::from_secs(10), conn.closed()).await;
        json!({ "ok": true, "punch_packets": sent, "connected_ms": connected_ms, "served": info.map_err(|e| anyhow!(err_kind(&e)))? })
    };
    ep.close(0u32.into(), b"done");
    Ok(r)
}

async fn tcp_punch(sess: &Session, peer: &PeerCard, my_role: &str, port: u16, to: SocketAddr, window: Duration, bytes: u64) -> Result<Value> {
    let local: SocketAddr = crate::util::any_v4_port(port);
    let t0 = Instant::now();
    let deadline = t0 + window;
    // Role b also listens on P: role a's SYN may simply be accepted.
    let listener = if my_role == "b" {
        let s = crate::util::reuse_tcp(local)?;
        Some(s.listen(16)?)
    } else {
        None
    };
    let mut errors: BTreeMap<String, u32> = BTreeMap::new();
    let mut attempts = 0u32;
    let (stream, how): (TcpStream, &str) = loop {
        if Instant::now() >= deadline || crate::cancelled() {
            return Ok(json!({ "ok": false, "attempts": attempts, "errors": errors }));
        }
        attempts += 1;
        let dial = async { crate::util::reuse_tcp(local)?.connect(to).await.map_err(anyhow::Error::from) };
        let accept = async {
            match &listener {
                Some(l) => l.accept().await.map(|(s, _)| s).map_err(anyhow::Error::from),
                None => std::future::pending().await,
            }
        };
        let r = timeout(Duration::from_millis(1500), async {
            tokio::select! {
                r = dial => r.map(|s| (s, "dial")),
                r = accept => r.map(|s| (s, "accept")),
            }
        })
        .await;
        match r {
            Ok(Ok((s, h))) => break (s, h),
            Ok(Err(e)) => {
                *errors.entry(err_kind(&e)).or_default() += 1;
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
            Err(_) => *errors.entry("TimedOut".into()).or_default() += 1,
        }
    };
    let connected_ms = ms(t0.elapsed());
    plog!("punch tcp: connected by {how} after {attempts} tries");
    stream.set_nodelay(true)?;
    let mut out = json!({ "ok": true, "attempts": attempts, "errors": errors, "connected_ms": connected_ms, "how": how });
    if my_role == "a" {
        let cfg = tlsutil::client_config(peer.pin()?, tlsutil::alpn_for(&peer.sid))?;
        let tls = timeout(Duration::from_secs(10), tokio_rustls::TlsConnector::from(Arc::new(cfg)).connect(tlsutil::any_name(), stream)).await??;
        let _g = sess.measure_lock.lock().await;
        out["measure"] = measure::client(tls, &sess.sid, "punch-tcp", bytes).await.map_err(|e| anyhow!(err_kind(&e)))?;
    } else {
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(tlsutil::server_config(&sess.ident, tlsutil::alpn_for(&sess.sid))?));
        let tls = timeout(Duration::from_secs(10), acceptor.accept(stream)).await??;
        out["served"] = measure::server(tls, &sess.sid).await.map_err(|e| anyhow!(err_kind(&e)))?;
    }
    if !out["ok"].as_bool().unwrap_or(false) {
        bail!("unreachable");
    }
    Ok(out)
}
