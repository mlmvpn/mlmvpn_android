//! One test session of one phone: what its card describes, and the listeners behind it.
//!
//! `make_card` creates a fresh TLS identity and BLE keys, opens a TCP listener and a QUIC
//! endpoint (one UDP socket), asks the observers (if any) how those two ports look from outside,
//! and collects the phone's addresses. The listeners answer the other phone's measurements until
//! the session ends, and every inbound connection is written down: that is the "inbound" half of
//! the two-phone test.

use crate::card::{Cand, ObserverCard, PeerCard, PubMap};
use crate::obsclient::{udp_call, ObsConn};
use crate::tlsutil::{self, Ident};
use crate::util::{alias_sa, b64e, canon, ip_kind, kind_label, ms, IpKind};
use crate::{measure, plog};
use anyhow::Result;
use serde_json::{json, Value};
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;
use tokio::time::timeout;

pub struct Session {
    pub sid: String,
    pub ident: Ident,
    pub ble_sk: Vec<u8>,
    pub ble_pk: Vec<u8>,
    pub ble_psk: [u8; 32],
    pub tcp_port: u16,
    pub udp_port: u16,
    pub endpoint: quinn::Endpoint,
    pub endpoint_v6: bool,
    pub card: PeerCard,
    pub card_text: String,
    /// One measurement at a time per phone, so two paths do not share the radio.
    pub measure_lock: tokio::sync::Mutex<()>,
    born: Instant,
    inbound: Arc<Mutex<Vec<Value>>>,
    tasks: Mutex<Vec<tokio::task::JoinHandle<()>>>,
    notes: Vec<String>,
    pubs_view: Vec<Value>,
}

fn record(inbound: &Arc<Mutex<Vec<Value>>>, born: Instant, mut ev: Value) {
    ev["t_ms"] = json!(born.elapsed().as_millis() as u64);
    let mut v = inbound.lock().unwrap();
    if v.len() < 500 {
        v.push(ev);
    }
}

fn from_kind(a: SocketAddr) -> &'static str {
    kind_label(ip_kind(a.ip()))
}

/// Which local addresses go into the card.
fn candidates() -> Vec<Cand> {
    let mut out: Vec<Cand> = vec![];
    for i in crate::util::interfaces() {
        let k = ip_kind(i.ip);
        let keep = matches!(k, IpKind::Global6 | IpKind::Private4 | IpKind::Cgnat4 | IpKind::Ula6 | IpKind::Public4);
        if keep && !out.iter().any(|c| c.a == i.ip.to_string()) {
            out.push(Cand { a: i.ip.to_string(), k: kind_label(k).into(), i: i.name.chars().take(16).collect() });
        }
    }
    // Global IPv6 first (the direct path of normal days), then LAN, then the rest.
    let rank = |c: &Cand| match c.k.as_str() {
        "v6-global" => 0,
        "v4-private" => 1,
        "v6-ula" => 2,
        "v4-public" => 3,
        _ => 4,
    };
    out.sort_by_key(rank);
    out.truncate(8);
    out
}

/// `only_ips` (desktop self-test) replaces the device's addresses, e.g. with 127.0.0.1.
fn candidates_for(p: &Value) -> Vec<Cand> {
    match p["only_ips"].as_array() {
        Some(list) => list
            .iter()
            .filter_map(|v| v.as_str())
            .filter_map(|s| s.parse::<IpAddr>().ok())
            .map(|ip| Cand { a: ip.to_string(), k: kind_label(ip_kind(ip)).into(), i: "test".into() })
            .collect(),
        None => candidates(),
    }
}

fn observers_of(p: &Value) -> Vec<ObserverCard> {
    p["observers"]
        .as_array()
        .map(|a| a.iter().filter_map(|c| c.as_str()).filter_map(|c| ObserverCard::decode(c).ok()).collect())
        .unwrap_or_default()
}


/// Learns how `udp_port` and `tcp_port` look from outside, from every observer once.
async fn learn_mappings(
    observers: &[ObserverCard],
    udp_std: &std::net::UdpSocket,
    udp_v6: bool,
    tcp_port: u16,
    tcp_v6: bool,
    cid: &str,
    notes: &mut Vec<String>,
) -> Result<(Vec<PubMap>, Vec<Value>)> {
    let mut pubs: Vec<PubMap> = vec![];
    let mut view = vec![];
    if observers.is_empty() {
        return Ok((pubs, view));
    }
    let probe = UdpSocket::from_std(udp_std.try_clone()?)?;
    for o in observers {
        let (pk, to) = match (o.pk(), o.main()) {
            (Ok(pk), Ok(to)) => (pk, to),
            _ => {
                notes.push(format!("observer {} card unusable", o.label()));
                continue;
            }
        };
        let req = json!({ "t": "map", "cid": cid });
        match udp_call(&probe, udp_v6, &pk, to, &req, 3, Duration::from_millis(1500)).await {
            Ok(a) => {
                if let Some(obs) = a.answer["observed"].as_str().and_then(|s| s.parse::<SocketAddr>().ok()) {
                    let obs = canon(obs);
                    if !pubs.iter().any(|m| m.a == obs.to_string() && m.k == "udp") {
                        pubs.push(PubMap { a: obs.to_string(), k: "udp".into() });
                    }
                    view.push(json!({ "k": "udp", "observer": o.label(), "seen": alias_sa(obs), "local_port": probe.local_addr()?.port() }));
                }
            }
            Err(e) => notes.push(format!("udp mapping via {}: {}", o.label(), crate::util::err_kind(&e))),
        }
        // An outgoing connection from the listening port (SO_REUSEPORT) shows its TCP mapping.
        if cfg!(unix) {
            let local = if tcp_v6 {
                SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), tcp_port)
            } else {
                crate::util::any_v4_port(tcp_port)
            };
            match ObsConn::connect(o, crate::util::for_socket(to, tcp_v6), Some(local), cid).await {
                Ok(mut c) => {
                    if !pubs.iter().any(|m| m.a == c.observed.to_string() && m.k == "tcp") {
                        pubs.push(PubMap { a: c.observed.to_string(), k: "tcp".into() });
                    }
                    view.push(json!({ "k": "tcp", "observer": o.label(), "seen": alias_sa(c.observed), "local_port": tcp_port }));
                    let _ = c.ns.send_json(&json!({ "t": "bye" })).await;
                }
                Err(e) => notes.push(format!("tcp mapping via {}: {}", o.label(), crate::util::err_kind(&e))),
            }
        }
    }
    Ok((pubs, view))
}

fn spawn_tcp_listener(
    listener: tokio::net::TcpListener,
    tls: Arc<rustls::ServerConfig>,
    sid: String,
    inbound: Arc<Mutex<Vec<Value>>>,
    born: Instant,
) -> tokio::task::JoinHandle<()> {
    let acceptor = tokio_rustls::TlsAcceptor::from(tls);
    tokio::spawn(async move {
        loop {
            let (s, peer) = match listener.accept().await {
                Ok(x) => x,
                Err(_) => {
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    continue;
                }
            };
            let peer = canon(peer);
            let (acceptor, sid, inbound) = (acceptor.clone(), sid.clone(), inbound.clone());
            tokio::spawn(async move {
                let _ = s.set_nodelay(true);
                let t0 = Instant::now();
                let tls = match timeout(Duration::from_secs(10), acceptor.accept(s)).await {
                    Ok(Ok(t)) => t,
                    _ => {
                        record(&inbound, born, json!({ "transport": "tcp", "from": from_kind(peer), "ok": false, "error": "tls handshake" }));
                        return;
                    }
                };
                let hs_ms = ms(t0.elapsed());
                plog!("inbound tcp from {} (tls {} ms)", from_kind(peer), hs_ms);
                let r = measure::server(tls, &sid).await;
                let mut ev = json!({ "transport": "tcp", "from": from_kind(peer), "tls_ms": hs_ms, "ok": r.is_ok() });
                match r {
                    Ok(info) => ev["info"] = info,
                    Err(e) => ev["error"] = json!(crate::util::err_kind(&e)),
                }
                record(&inbound, born, ev);
            });
        }
    })
}

fn spawn_quic_listener(
    endpoint: quinn::Endpoint,
    sid: String,
    inbound: Arc<Mutex<Vec<Value>>>,
    born: Instant,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        while let Some(incoming) = endpoint.accept().await {
            let (sid, inbound) = (sid.clone(), inbound.clone());
            tokio::spawn(async move {
                let peer = canon(incoming.remote_address());
                let t0 = Instant::now();
                let conn = match timeout(Duration::from_secs(10), incoming).await {
                    Ok(Ok(c)) => c,
                    _ => {
                        record(&inbound, born, json!({ "transport": "quic", "from": from_kind(peer), "ok": false, "error": "quic handshake" }));
                        return;
                    }
                };
                let hs_ms = ms(t0.elapsed());
                plog!("inbound quic from {} (handshake {} ms)", from_kind(peer), hs_ms);
                // The connection stays open until the client closes it: dropping it early
                // would throw away stream data that is still in flight.
                while let Ok((send, recv)) = conn.accept_bi().await {
                    let r = measure::server(tokio::io::join(recv, send), &sid).await;
                    let mut ev = json!({ "transport": "quic", "from": from_kind(peer), "quic_ms": hs_ms, "ok": r.is_ok() });
                    match r {
                        Ok(info) => ev["info"] = info,
                        Err(e) => ev["error"] = json!(crate::util::err_kind(&e)),
                    }
                    record(&inbound, born, ev);
                }
            });
        }
    })
}

impl Session {
    pub async fn start(p: &Value) -> Result<Arc<Session>> {
        let ident = Ident::new()?;
        let sid = hex::encode(crate::util::rand_bytes::<8>());
        let (ble_sk, ble_pk) = crate::noise::keypair()?;
        let ble_psk = crate::util::rand_bytes::<32>();
        let want_tcp = p["tcp_port"].as_u64().unwrap_or(0) as u16;
        let want_udp = p["udp_port"].as_u64().unwrap_or(0) as u16;
        let cid = hex::encode(crate::util::rand_bytes::<8>());
        let mut notes = vec![];

        let (tcp_std, tcp_v6) = crate::util::tcp_listener(want_tcp, cfg!(unix))?;
        let tcp_port = tcp_std.local_addr()?.port();
        let listener = tokio::net::TcpListener::from_std(tcp_std)?;
        let (udp_std, udp_v6) = crate::util::udp_dual(want_udp)?;
        let udp_port = udp_std.local_addr()?.port();

        let observers = observers_of(p);
        let (pubs, pubs_view) = learn_mappings(&observers, &udp_std, udp_v6, tcp_port, tcp_v6, &cid, &mut notes).await?;

        let alpn = tlsutil::alpn_for(&sid);
        let endpoint = quinn::Endpoint::new(
            quinn::EndpointConfig::default(),
            Some(tlsutil::quic_server(&ident, alpn.clone())?),
            udp_std,
            Arc::new(quinn::TokioRuntime),
        )?;
        let tls_server = Arc::new(tlsutil::server_config(&ident, alpn)?);

        let card = PeerCard {
            v: 1,
            sid: sid.clone(),
            fp: ident.fp_b64(),
            tcp: tcp_port,
            udp: udp_port,
            ips: candidates_for(p),
            pubs,
            bk: b64e(&ble_pk),
            bpsk: b64e(&ble_psk),
        };
        let card_text = card.encode();
        let born = Instant::now();
        let inbound: Arc<Mutex<Vec<Value>>> = Arc::new(Mutex::new(vec![]));
        let tasks = vec![
            spawn_tcp_listener(listener, tls_server, sid.clone(), inbound.clone(), born),
            spawn_quic_listener(endpoint.clone(), sid.clone(), inbound.clone(), born),
        ];
        plog!("card ready: session {}, tcp {}, udp {}, {} addresses", &sid[..4], tcp_port, udp_port, card.ips.len());
        Ok(Arc::new(Session {
            sid,
            ident,
            ble_sk,
            ble_pk,
            ble_psk,
            tcp_port,
            udp_port,
            endpoint,
            endpoint_v6: udp_v6,
            card,
            card_text,
            measure_lock: tokio::sync::Mutex::new(()),
            born,
            inbound,
            tasks: Mutex::new(tasks),
            notes,
            pubs_view,
        }))
    }

    /// What the screen shows. The card text itself is for the QR code only.
    pub fn describe(&self) -> Value {
        json!({
            "card": self.card_text,
            "sid": self.sid,
            "tcp_port": self.tcp_port,
            "udp_port": self.udp_port,
            "candidates": self.card.ips.iter().map(|c| json!({ "kind": c.k, "if": c.i })).collect::<Vec<_>>(),
            "public": self.pubs_view,
            "notes": self.notes,
        })
    }

    pub fn inbound_since(&self, from: usize) -> Vec<Value> {
        let v = self.inbound.lock().unwrap();
        v.iter().skip(from).cloned().collect()
    }

    pub fn inbound_len(&self) -> usize {
        self.inbound.lock().unwrap().len()
    }

    pub fn age_ms(&self) -> u64 {
        self.born.elapsed().as_millis() as u64
    }

    pub fn shutdown(&self) {
        for t in self.tasks.lock().unwrap().drain(..) {
            t.abort();
        }
        self.endpoint.close(0u32.into(), b"done");
    }
}
