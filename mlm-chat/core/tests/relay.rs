//! Phase 3 (SPEC 15): two nodes with no direct path reach each other through a volunteer.
//! Everything on loopback. Neither node runs the direct transport and their presence records
//! carry only relay circuits, so a delivery can only be the relay path: the private DHT on the
//! volunteer (presence by locator), a reservation, a circuit, and the SPEC 14.7 session on a
//! `/mlmchat/msg/1` stream inside it.
//!
//! The canary part records every byte between one node and the volunteer and searches it, the
//! databases and the log lines for the marked text, the names and every key.

mod common;

use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::Engine;
use common::*;
use mlmchat_core::card::Card;
use mlmchat_core::node::{Event, Node, NodeOptions};
use mlmchat_core::store::MsgState;
use mlmchat_core::transport::p2p::{Multiaddr, P2pConfig};
use std::net::SocketAddr;
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

const CANARY: &str = "QNARY-5512 az rah-e davtalab";
const NAME_A: &str = "Canary-Arash-5512";
const NAME_B: &str = "Canary-Bahar-5512";

static LOG: OnceLock<Mutex<Vec<String>>> = OnceLock::new();

struct Capturing;

impl log::Log for Capturing {
    fn enabled(&self, m: &log::Metadata) -> bool {
        m.target().starts_with("mlmchat")
    }
    fn log(&self, record: &log::Record) {
        if self.enabled(record.metadata()) {
            let line = format!("{} {} {}", record.level(), record.target(), record.args());
            if std::env::var("MLMCHAT_TEST_LOG").is_ok() {
                eprintln!("[{:?}] {line}", std::thread::current().name().unwrap_or("?"));
            }
            LOG.get_or_init(Default::default).lock().unwrap().push(line);
        }
    }
    fn flush(&self) {}
}

fn ma(addr: SocketAddr) -> Multiaddr {
    format!("/ip4/{}/tcp/{}/tls", addr.ip(), addr.port()).parse().unwrap()
}

fn volunteer_cfg(port: SocketAddr) -> P2pConfig {
    // no external address: the volunteer must offer the one it listens on (a reservation
    // without the relay's addresses is refused)
    P2pConfig { listen: vec![ma(port)], volunteer: true, allow_loopback: true, ..P2pConfig::default() }
}

fn client_cfg(boot: Multiaddr) -> P2pConfig {
    P2pConfig {
        listen: vec!["/ip4/127.0.0.1/tcp/0".parse().unwrap()],
        boot: vec![boot],
        publish_direct: false,
        allow_loopback: true,
        dial_timeout: Duration::from_secs(10),
        io_timeout: Duration::from_secs(10),
        ..P2pConfig::default()
    }
}

fn node(dir: &Path, name: &str) -> Node {
    Node::create(&dir.join("chat.db"), &KEY, name, NodeOptions::default()).unwrap()
}

/// Until the node holds a reservation and its presence record reached the volunteer.
fn wait_ready(n: &Node, secs: u64) -> bool {
    let end = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < end {
        if let Some(p) = n.net_status().p2p {
            if p.reservations >= 1 && p.published_ms > 0 {
                return true;
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    false
}

fn find(hay: &[u8], needle: &[u8]) -> bool {
    needle.len() <= hay.len() && hay.windows(needle.len()).any(|w| w == needle)
}

fn needles_for_key(label: &str, key: &[u8; 32], out: &mut Vec<(String, Vec<u8>)>) {
    out.push((format!("{label} raw"), key.to_vec()));
    out.push((format!("{label} hex"), hex::encode(key).into_bytes()));
    out.push((format!("{label} base64"), STANDARD.encode(key).trim_end_matches('=').as_bytes().to_vec()));
    out.push((format!("{label} base64url"), URL_SAFE_NO_PAD.encode(key).into_bytes()));
}

#[test]
fn through_a_volunteer_both_ways_queue_and_canary() {
    log::set_logger(&Capturing).unwrap();
    log::set_max_level(log::LevelFilter::Debug);

    let (dv, da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    // the volunteer
    let v = node(dv.path(), "davtalab");
    let vport = free_tcp();
    let tv = v.start_p2p(volunteer_cfg(vport)).unwrap();
    let vid = tv.peer_id();
    let v_addr: Multiaddr = format!("{}/p2p/{vid}", ma(vport)).parse().unwrap();

    // A reaches the volunteer only through a recording proxy
    let capture = Arc::new(Capture::default());
    let proxy = recording_proxy(vport, capture.clone());
    let a_boot: Multiaddr = format!("{}/p2p/{vid}", ma(proxy)).parse().unwrap();

    let a = node(da.path(), NAME_A);
    let b = node(db.path(), NAME_B);
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    a.start_p2p(client_cfg(a_boot)).unwrap();
    b.start_p2p(client_cfg(v_addr.clone())).unwrap();
    assert!(wait_ready(&a, 30), "A never got a reservation: {:?}", a.net_status());
    assert!(wait_ready(&b, 30), "B never got a reservation: {:?}", b.net_status());
    let (sa, sb) = (a.me().sk, b.me().sk);

    // B's card names the volunteer (boot), and has no direct address at all
    let link_b = b.card_link().unwrap();
    let card_b = Card::from_link(&link_b).unwrap();
    assert!(card_b.addrs.is_empty());
    assert_eq!(card_b.boot.len(), 1);
    a.add_contact(&link_b).unwrap();

    let id = a.send_text(&sb, CANARY).unwrap();
    let got = wait_for(&rx_b, &mut eb, 40, |e| match e {
        Event::Message { peer, text, .. } if *peer == sa => text.clone(),
        _ => None,
    });
    assert_eq!(got.as_deref(), Some(CANARY), "B events: {eb:?}");
    assert!(wait_for(&rx_a, &mut ea, 30, |e| matches!(e, Event::State { id: i, state: MsgState::Delivered, .. } if *i == id).then_some(())).is_some(), "A events: {ea:?}");
    // through the volunteer: its relay circuit, or the direct link DCUtR made through it (since
    // deliveries go in parallel, the upgrade sometimes comes before the first one)
    assert!(ea.iter().any(|e| matches!(e, Event::Reachable { peer, via: "relay" | "holepunch" } if *peer == sb)), "not through the relay: {ea:?}");
    // after the first relayed session DCUtR may upgrade the link (it can, on loopback): a later
    // session then says "holepunch"
    let st = a.contact_status(&sb).unwrap();
    assert!(st.online && ["relay", "holepunch", "p2p"].contains(&st.via.as_str()) && st.queued == 0, "{st:?}");

    // and back
    let back = b.send_text(&sa, &format!("{CANARY} (javab)")).unwrap();
    assert!(wait_for(&rx_a, &mut ea, 30, |e| matches!(e, Event::Message { peer, .. } if *peer == sb).then_some(())).is_some(), "A events: {ea:?}");
    a.mark_read(&sb).unwrap();
    assert!(wait_for(&rx_b, &mut eb, 30, |e| matches!(e, Event::State { id: i, state: MsgState::Read, .. } if *i == back).then_some(())).is_some(), "B events: {eb:?}");

    // B goes away: A's message stays queued, with a reason, and no tick
    b.stop();
    drop(b);
    std::thread::sleep(Duration::from_millis(500));
    let later = a.send_text(&sb, "vaghti nabudi").unwrap();
    // three failed tries: A's next one is 15 s away (queue::BACKOFF_S)
    let mut fails = 0;
    while fails < 3 {
        assert!(wait_for(&rx_a, &mut ea, 40, |e| matches!(e, Event::Queued { peer, .. } if *peer == sb).then_some(())).is_some(), "A events: {ea:?}");
        ea.retain(|e| !matches!(e, Event::Queued { .. }));
        fails += 1;
    }
    assert_eq!(a.history(&sb, 1).unwrap()[0].state, MsgState::Queued);
    let st = a.contact_status(&sb).unwrap();
    assert!(st.queued >= 1 && st.reason.is_some(), "{st:?}");
    // reached a minute ago, but a delivery failed since: not «reachable» (SPEC 15.7)
    assert!(!st.online, "{st:?}");

    // B comes back from its database: it reserves again and tells A it is back, so A's queue
    // goes out at once instead of at the next backoff step (SPEC 15.4)
    let b = Node::open(&db.path().join("chat.db"), &KEY, NodeOptions::default()).unwrap();
    let rx_b = b.events();
    let mut eb = Vec::new();
    let t0 = Instant::now();
    b.start_p2p(client_cfg(v_addr.clone())).unwrap();
    let got = wait_for(&rx_b, &mut eb, 60, |e| match e {
        Event::Message { peer, text, .. } if *peer == sa => text.clone(),
        _ => None,
    });
    assert_eq!(got.as_deref(), Some("vaghti nabudi"), "B events: {eb:?}");
    assert!(wait_for(&rx_a, &mut ea, 30, |e| matches!(e, Event::State { id: i, state: MsgState::Delivered, .. } if *i == later).then_some(())).is_some());
    let took = t0.elapsed();
    eprintln!("queued message arrived {} ms after B came back", took.as_millis());
    assert!(took < Duration::from_secs(10), "the queue waited for the backoff, not for B's announce: {took:?}");

    // ---- canary ----
    let card_a = Card::from_link(&a.card_link().unwrap()).unwrap();
    let mut needles: Vec<(String, Vec<u8>)> = vec![
        ("canary".into(), CANARY.as_bytes().to_vec()),
        ("canary prefix".into(), b"QNARY".to_vec()),
        ("name A".into(), NAME_A.as_bytes().to_vec()),
        ("name B".into(), NAME_B.as_bytes().to_vec()),
        ("code A".into(), a.me().code.into_bytes()),
        ("code B".into(), b.me().code.into_bytes()),
    ];
    for (who, card) in [("A", &card_a), ("B", &card_b)] {
        needles_for_key(&format!("{who} sk"), &card.sk, &mut needles);
        needles_for_key(&format!("{who} ik"), &card.ik, &mut needles);
        needles_for_key(&format!("{who} hk"), &card.hk, &mut needles);
        needles_for_key(&format!("{who} loc"), &card.loc().unwrap(), &mut needles);
    }
    a.stop();
    b.stop();
    v.stop();
    let mut hay: Vec<(String, Vec<u8>)> = capture.all().into_iter().enumerate().map(|(i, s)| (format!("A-volunteer stream {i}"), s)).collect();
    for dir in [dv.path(), da.path(), db.path()] {
        for entry in std::fs::read_dir(dir).unwrap().flatten() {
            hay.push((format!("file {}", entry.file_name().to_string_lossy()), std::fs::read(entry.path()).unwrap()));
        }
    }
    let logs = LOG.get_or_init(Default::default).lock().unwrap().join("\n");
    hay.push(("logs".into(), logs.clone().into_bytes()));
    assert!(capture.all().iter().map(|s| s.len()).sum::<usize>() > 2000, "too little was captured to mean anything");
    assert!(logs.contains("relay reservation accepted"), "no log lines captured");
    let mut found = Vec::new();
    for (hname, h) in &hay {
        for (nname, n) in &needles {
            if find(h, n) {
                found.push(format!("{nname} in {hname}"));
            }
        }
    }
    assert!(found.is_empty(), "readable data leaked: {found:#?}");

    // the volunteer link is TLS 1.3 from the first byte: no protocol name in the clear (libp2p
    // negotiates "/multistream/1.0.0" and "/noise" in plain text otherwise), and the ClientHello
    // offers the hybrid post-quantum group X25519MLKEM768 (0x11ec) and ALPN h2, http/1.1 like a
    // browser (SPEC 15.3)
    let streams = capture.all();
    for (i, st) in streams.iter().enumerate() {
        for plain in [&b"/multistream"[..], b"/noise", b"/yamux", b"/mlmchat", b"/libp2p", b"mlmchat"] {
            assert!(!find(st, plain), "{} in the clear in stream {i}", String::from_utf8_lossy(plain));
        }
    }
    let ups: Vec<&Vec<u8>> = streams.iter().step_by(2).filter(|s| !s.is_empty()).collect();
    assert!(!ups.is_empty());
    for up in ups {
        assert_eq!(&up[..2], &[0x16, 0x03], "not a TLS handshake record");
        let hello_len = u16::from_be_bytes([up[3], up[4]]) as usize;
        assert!(find(&up[5..5 + hello_len], &[0x11, 0xec]), "the ClientHello does not offer X25519MLKEM768");
        assert!(find(&up[5..5 + hello_len], b"\x02h2\x08http/1.1"), "the ClientHello does not offer ALPN h2, http/1.1");
    }
}
