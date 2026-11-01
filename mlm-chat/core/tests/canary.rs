//! The canary test (PLAN 9, INTEROP ه۲۳, T18), at the core level: two nodes chat through
//! recording proxies with a marked text; then every captured byte (both directions of every
//! connection, every LAN beacon), every database file on disk and every log line is searched for
//! the marked text, the names, and each user's keys in raw, hex and base64 form. Nothing may be
//! found, and the encrypted messages on the wire may only have the fixed sizes of SPEC 4.

mod common;

use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::Engine;
use common::*;
use mlmchat_core::card::Card;
use mlmchat_core::frame::BUCKETS;
use mlmchat_core::node::Event;
use mlmchat_core::store::MsgState;
use std::sync::{Arc, Mutex, OnceLock};

const CANARY: &str = "QNARY-7391 matn-e makhfi";
const NAME_A: &str = "Canary-Alice-7391";
const NAME_B: &str = "Canary-Bob-7391";

static LOG: OnceLock<Mutex<Vec<String>>> = OnceLock::new();

struct Capturing;

impl log::Log for Capturing {
    fn enabled(&self, _: &log::Metadata) -> bool {
        true
    }
    fn log(&self, record: &log::Record) {
        LOG.get_or_init(Default::default).lock().unwrap().push(format!("{} {} {}", record.level(), record.target(), record.args()));
    }
    fn flush(&self) {}
}

fn needles_for_key(label: &str, key: &[u8; 32], out: &mut Vec<(String, Vec<u8>)>) {
    out.push((format!("{label} raw"), key.to_vec()));
    out.push((format!("{label} hex"), hex::encode(key).into_bytes()));
    out.push((format!("{label} HEX"), hex::encode_upper(key).into_bytes()));
    out.push((format!("{label} base64"), STANDARD.encode(key).trim_end_matches('=').as_bytes().to_vec()));
    out.push((format!("{label} base64url"), URL_SAFE_NO_PAD.encode(key).into_bytes()));
}

fn find(hay: &[u8], needle: &[u8]) -> bool {
    needle.len() <= hay.len() && hay.windows(needle.len()).any(|w| w == needle)
}

/// Lengths of the Noise messages in one recorded direction (each has a 2-byte length).
fn noise_lengths(stream: &[u8]) -> Vec<usize> {
    let mut out = Vec::new();
    let mut i = 0;
    while i + 2 <= stream.len() {
        let n = u16::from_be_bytes([stream[i], stream[i + 1]]) as usize;
        out.push(n);
        i += 2 + n;
    }
    assert_eq!(i, stream.len(), "the capture ends inside a message");
    out
}

#[test]
fn nothing_readable_leaves_the_device() {
    log::set_logger(&Capturing).unwrap();
    log::set_max_level(log::LevelFilter::Trace);

    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = mlmchat_core::node::Node::create(&da.path().join("chat.db"), &KEY, NAME_A, Default::default()).unwrap();
    let b = mlmchat_core::node::Node::create(&db.path().join("chat.db"), &KEY, NAME_B, Default::default()).unwrap();
    let capture = Arc::new(Capture::default());
    let (beacon_sink, beacons) = udp_recorder();

    // each side is reached only through a recording proxy: its card advertises the proxy
    let port_a = free_tcp();
    let proxy_a = recording_proxy(port_a, capture.clone());
    let mut ca = loopback(vec![proxy_a]);
    ca.listen = port_a;
    a.start_direct(ca).unwrap();

    let mut cb = loopback(Vec::new());
    cb.beacon_targets = vec![beacon_sink]; // B's beacons go to the recorder
    let tb = b.start_direct(cb).unwrap();
    let proxy_b = recording_proxy(tb.local_addr(), capture.clone());
    let link_b = b.card_link_for(&[proxy_b]).unwrap();

    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    let (sa, sb) = (a.me().sk, b.me().sk);

    a.add_contact(&link_b).unwrap();
    let id = a.send_text(&sb, CANARY).unwrap();
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Message { text: Some(t), .. } if t == CANARY).then_some(())).is_some());
    let back = b.send_text(&sa, &format!("{CANARY} (javab)")).unwrap();
    assert!(wait_for(&rx_a, &mut ea, 20, |e| matches!(e, Event::Message { peer, .. } if *peer == sb).then_some(())).is_some());
    b.mark_read(&sa).unwrap();
    a.mark_read(&sb).unwrap();
    assert!(wait_for(&rx_a, &mut ea, 20, |e| matches!(e, Event::State { id: i, state: MsgState::Read, .. } if *i == id).then_some(())).is_some());
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::State { id: i, state: MsgState::Read, .. } if *i == back).then_some(())).is_some());
    // a chat action (SPEC 5.4) crosses the same proxy, with the same sizes and nothing readable
    assert!(a.chat_action(&sb, mlmchat_core::envelope::ChatActionKind::Typing).unwrap());
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::ChatAction { peer, .. } if *peer == sa).then_some(())).is_some());
    // and «online» (SPEC 5.5)
    assert_eq!(a.set_online(true).unwrap(), 1);
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Presence { peer, online: true } if *peer == sa).then_some(())).is_some());
    std::thread::sleep(std::time::Duration::from_millis(600)); // a few beacons

    // every secret and every name, in every likely encoding
    let card_a = Card::from_link(&a.card_link().unwrap()).unwrap();
    let card_b = Card::from_link(&link_b).unwrap();
    let mut needles: Vec<(String, Vec<u8>)> = vec![
        ("canary".into(), CANARY.as_bytes().to_vec()),
        ("canary prefix".into(), b"QNARY".to_vec()),
        ("canary utf16".into(), CANARY.encode_utf16().flat_map(|u| u.to_le_bytes()).collect()),
        ("name A".into(), NAME_A.as_bytes().to_vec()),
        ("name B".into(), NAME_B.as_bytes().to_vec()),
        ("display name A".into(), a.me().display_name.into_bytes()),
        ("code A".into(), a.me().code.into_bytes()),
        ("code B".into(), b.me().code.into_bytes()),
        ("link B".into(), link_b.as_bytes()[12..60].to_vec()),
    ];
    for (who, card) in [("A", &card_a), ("B", &card_b)] {
        needles_for_key(&format!("{who} sk"), &card.sk, &mut needles);
        needles_for_key(&format!("{who} ik"), &card.ik, &mut needles);
        needles_for_key(&format!("{who} hk"), &card.hk, &mut needles);
        needles_for_key(&format!("{who} loc"), &card.loc().unwrap(), &mut needles);
    }
    // positive control: the same search does find them where they are in the clear
    let history: Vec<u8> = a.history(&sb, 10).unwrap().into_iter().flat_map(|m| m.envelope).collect();
    assert!(find(&history, CANARY.as_bytes()), "the search cannot even find the canary in plain text");
    assert!(find(&card_b.to_bytes(), &card_b.sk) && find(&card_b.to_bytes(), NAME_B.as_bytes()));
    a.stop();
    b.stop();

    let mut hay: Vec<(String, Vec<u8>)> = Vec::new();
    for (i, s) in capture.all().into_iter().enumerate() {
        hay.push((format!("tcp stream {i}"), s));
    }
    for (i, d) in beacons.lock().unwrap().iter().enumerate() {
        hay.push((format!("beacon {i}"), d.clone()));
    }
    for dir in [da.path(), db.path()] {
        for entry in std::fs::read_dir(dir).unwrap().flatten() {
            hay.push((format!("file {}", entry.file_name().to_string_lossy()), std::fs::read(entry.path()).unwrap()));
        }
    }
    let logs = LOG.get_or_init(Default::default).lock().unwrap().join("\n");
    hay.push(("logs".into(), logs.clone().into_bytes()));

    let streams = capture.all();
    assert!(streams.len() >= 8, "too little was captured to mean anything");
    assert!(!beacons.lock().unwrap().is_empty(), "no beacon captured");
    assert!(hay.iter().any(|(n, h)| n.starts_with("file") && h.len() > 4096), "no database file found");
    assert!(!logs.is_empty(), "no log lines captured");

    let mut found = Vec::new();
    for (hname, h) in &hay {
        for (nname, n) in &needles {
            if find(h, n) {
                found.push(format!("{nname} in {hname}"));
            }
        }
    }
    assert!(found.is_empty(), "readable data leaked: {found:#?}");

    // sizes on the wire: the two handshake messages, the fixed hello/ack/bye records, and frames
    // only in the SPEC 4 buckets (+ type byte, more-flag, Noise tag; 64 KiB splits in two)
    let mut allowed = vec![48usize, 1 + 97 + 16, 1 + 18 + 16, 1 + 1 + 16, 65535, (65537 - 65518) + 1 + 16];
    allowed.extend(BUCKETS.iter().filter(|b| **b + 1 < 65518).map(|b| b + 1 + 1 + 16));
    let mut frames = 0;
    for s in &streams {
        for n in noise_lengths(s) {
            assert!(allowed.contains(&n), "a message of {n} bytes on the wire");
            if BUCKETS.iter().any(|b| b + 18 == n) {
                frames += 1;
            }
        }
    }
    assert!(frames >= 6, "only {frames} frames seen");
}
