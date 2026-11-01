//! Bluetooth LE step 1 (SPEC 16) end to end, with a fake radio in place of Android's: two nodes
//! with no network at all, only the BLE transport. The radio shows each node's advertised tag to
//! the other and carries the links' bytes, the way the platform code does.

mod common;

use common::*;
use mlmchat_core::node::{Event, Node};
use mlmchat_core::pair::{PairConfig, PairPeer, PairSession, PeerState};
use mlmchat_core::store::MsgState;
use mlmchat_core::transport::ble::{BleCmd, BleConfig, BleTransport};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

/// Advertising, scanning and GATT links between named radios, in memory.
fn radio(radios: Vec<(&'static str, Arc<BleTransport>)>, stop: Arc<AtomicBool>, bytes_seen: Arc<std::sync::Mutex<Vec<u8>>>) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        // (radio, its link) -> (other radio, its link)
        let mut pairs: HashMap<(usize, u32), (usize, u32)> = HashMap::new();
        let mut next_in = 10_000u32;
        let mut tick = 0u32;
        while !stop.load(Ordering::Relaxed) {
            if tick.is_multiple_of(10) {
                for (i, (name, t)) in radios.iter().enumerate() {
                    let tag = t.advert_data();
                    for (j, (_, other)) in radios.iter().enumerate() {
                        if i != j {
                            other.seen(name, &tag);
                        }
                    }
                }
            }
            tick += 1;
            for i in 0..radios.len() {
                while let Some(cmd) = radios[i].1.next_command(Duration::from_millis(5)) {
                    match cmd {
                        BleCmd::Connect { link, addr } => match radios.iter().position(|(n, _)| *n == addr) {
                            Some(j) => {
                                next_in += 1;
                                pairs.insert((i, link), (j, next_in));
                                pairs.insert((j, next_in), (i, link));
                                radios[j].1.connected(next_in, true);
                                radios[i].1.connected(link, false);
                            }
                            None => radios[i].1.closed(link),
                        },
                        BleCmd::Send { link, data } => {
                            bytes_seen.lock().unwrap().extend_from_slice(&data);
                            if let Some(&(j, l)) = pairs.get(&(i, link)) {
                                radios[j].1.data(l, data);
                            }
                        }
                        BleCmd::Close { link } => {
                            if let Some((j, l)) = pairs.remove(&(i, link)) {
                                pairs.remove(&(j, l));
                                radios[j].1.closed(l);
                            }
                        }
                        // the next round of adverts reads advert_data again
                        BleCmd::Advertise => {}
                    }
                }
            }
        }
    })
}

fn ble_cfg() -> BleConfig {
    BleConfig { connect_timeout: Duration::from_secs(5), io_timeout: Duration::from_secs(5) }
}

#[test]
fn two_phones_side_by_side_with_no_network() {
    let (da, db, dc) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a: Node = new_node(da.path(), "ali");
    let b: Node = new_node(db.path(), "sara");
    // a stranger beside them: sees their tags, is never recognised, recognises nobody
    let c: Node = new_node(dc.path(), "gharibe");
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    let (ta, tb, tc) = (a.start_ble(ble_cfg()), b.start_ble(ble_cfg()), c.start_ble(ble_cfg()));
    let stop = Arc::new(AtomicBool::new(false));
    let wire = Arc::new(std::sync::Mutex::new(Vec::new()));
    let r = radio(vec![("A", ta.clone()), ("B", tb.clone()), ("C", tc.clone())], stop.clone(), wire.clone());
    let (sa, sb) = (a.me().sk, b.me().sk);

    // cards exchanged face to face (QR); then only Bluetooth carries anything
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let id = a.send_text(&sb, "salam az bluetooth").unwrap();
    let text = wait_for(&rx_b, &mut eb, 20, |e| match e {
        Event::Message { peer, text, .. } if *peer == sa => text.clone(),
        _ => None,
    });
    assert_eq!(text.as_deref(), Some("salam az bluetooth"), "B events: {eb:?}");
    assert!(wait_for(&rx_a, &mut ea, 20, |e| matches!(e, Event::State { id: i, state: MsgState::Delivered, .. } if *i == id).then_some(())).is_some(), "A events: {ea:?}");
    assert!(ea.iter().any(|e| matches!(e, Event::Reachable { peer, via: "ble" } if *peer == sb)) || a.contact_status(&sb).unwrap().via == "ble");

    // B answers over Bluetooth too
    b.send_text(&sa, "aleyk").unwrap();
    let back = wait_for(&rx_a, &mut ea, 20, |e| match e {
        Event::Message { peer, text, .. } if *peer == sb => text.clone(),
        _ => None,
    });
    assert_eq!(back.as_deref(), Some("aleyk"));
    // A's own receipts for "aleyk" leave over Bluetooth too: the queue empties
    let mut left = a.outbox_len().unwrap();
    for _ in 0..100 {
        if left == 0 {
            break;
        }
        std::thread::sleep(Duration::from_millis(100));
        left = a.outbox_len().unwrap();
    }
    assert_eq!(left, 0);

    // each sees the other beside them; the stranger sees nobody it knows
    assert_eq!(ta.nearby(), 1);
    assert_eq!(tb.nearby(), 1);
    assert_eq!(tc.nearby(), 0);

    // nothing readable crossed the radio
    let bytes = wire.lock().unwrap().clone();
    assert!(!bytes.is_empty());
    for plain in [&b"salam az bluetooth"[..], b"aleyk", b"ali", b"sara"] {
        assert!(!bytes.windows(plain.len()).any(|w| w == plain), "{} in the clear", String::from_utf8_lossy(plain));
    }

    stop.store(true, Ordering::Relaxed);
    r.join().unwrap();
    a.stop();
    b.stop();
    c.stop();
}

#[test]
fn nothing_beside_us_is_no_route_and_the_message_waits() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a: Node = new_node(da.path(), "ali");
    let b: Node = new_node(db.path(), "sara");
    let rx_a = a.events();
    let mut ea = Vec::new();
    a.start_ble(ble_cfg());
    a.add_contact(&b.card_link().unwrap()).unwrap();
    a.send_text(&b.me().sk, "kasi nist").unwrap();
    // no radio: B is never seen, so the frame stays queued with a reason
    assert!(wait_for(&rx_a, &mut ea, 10, |e| matches!(e, Event::Queued { .. }).then_some(())).is_some(), "{ea:?}");
    // our card for B and the message, both still waiting
    assert_eq!(a.outbox_len().unwrap(), 2);
    a.stop();
}

fn wait_peer(s: &PairSession, secs: u64, pick: impl Fn(&PairPeer) -> bool) -> Option<PairPeer> {
    let end = std::time::Instant::now() + Duration::from_secs(secs);
    while std::time::Instant::now() < end {
        if let Some(p) = s.peers().into_iter().find(|p| pick(p)) {
            return Some(p);
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    None
}

fn ble_pair(name: &str, id: [u8; 8]) -> PairConfig {
    PairConfig { name: name.into(), udp: None, targets: Vec::new(), window: Duration::from_secs(60), id: Some(id) }
}

/// «Add nearby» (SPEC 16.4) with Bluetooth only: strangers add each other, then chat over it.
#[test]
fn add_nearby_over_bluetooth_with_no_network() {
    let (da, db, dc) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a: Node = new_node(da.path(), "ali");
    let b: Node = new_node(db.path(), "sara");
    // C has Bluetooth on but never opens «add nearby»: it is never in anyone's list
    let c: Node = new_node(dc.path(), "gharibe");
    let (ta, tb, tc) = (a.start_ble(ble_cfg()), b.start_ble(ble_cfg()), c.start_ble(ble_cfg()));
    let stop = Arc::new(AtomicBool::new(false));
    let wire = Arc::new(std::sync::Mutex::new(Vec::new()));
    let r = radio(vec![("A", ta.clone()), ("B", tb.clone()), ("C", tc.clone())], stop.clone(), wire.clone());

    let sa = a.pair_start(ble_pair("Ali", [1; 8])).unwrap();
    let sb = b.pair_start(ble_pair("Sara", [9; 8])).unwrap();
    assert_eq!(ta.advert_data()[0], 0x01, "the pairing advert while the screen is open");
    let sara = wait_peer(&sa, 20, |p| p.name == "Sara").expect("A sees Sara over Bluetooth");
    assert_eq!(sara.via, "ble");
    assert!(sa.ask(&sara.id));
    let ali = wait_peer(&sb, 20, |p| p.state == PeerState::Incoming).expect("B is asked");
    let sara = wait_peer(&sa, 5, |p| p.state == PeerState::Outgoing).unwrap();
    assert_eq!(ali.sas, sara.sas);
    assert!(sb.confirm(&ali.id));
    assert!(sa.confirm(&sara.id));
    assert!(wait_peer(&sa, 20, |p| p.state == PeerState::Done).is_some(), "{:?}", sa.peers());
    assert!(wait_peer(&sb, 20, |p| p.state == PeerState::Done).is_some(), "{:?}", sb.peers());
    assert_eq!(sa.peers().len(), 1, "the stranger is not in the list");

    // the screens close; the contact tags come back and Bluetooth carries the chat
    a.pair_stop();
    b.pair_stop();
    assert_eq!(ta.advert_data().len(), 8);
    let (sk_a, sk_b) = (a.me().sk, b.me().sk);
    let rx_b = b.events();
    let mut eb = Vec::new();
    a.send_text(&sk_b, "salam doost-e jadid").unwrap();
    let text = wait_for(&rx_b, &mut eb, 30, |e| match e {
        Event::Message { peer, text, .. } if *peer == sk_a => text.clone(),
        _ => None,
    });
    assert_eq!(text.as_deref(), Some("salam doost-e jadid"), "B events: {eb:?}");

    let bytes = wire.lock().unwrap().clone();
    for plain in [&b"Ali"[..], b"Sara", b"salam doost-e jadid", b"mlmchat:"] {
        assert!(!bytes.windows(plain.len()).any(|w| w == plain), "{} in the clear", String::from_utf8_lossy(plain));
    }
    stop.store(true, Ordering::Relaxed);
    r.join().unwrap();
    a.stop();
    b.stop();
    c.stop();
}
