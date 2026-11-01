//! «Add nearby» over Wi-Fi (SPEC 16.4, T36): two people on the same network, no QR. Each sees the
//! other's name for the moment, asking shows the same four emoji on both phones, and only when both
//! confirm are the cards exchanged and added. A recording proxy in the middle sees nothing readable.

mod common;

use common::*;
use mlmchat_core::node::Node;
use mlmchat_core::pair::{announce_bytes, PairConfig, PairPeer, PairSession, PeerState};
use std::net::{SocketAddr, UdpSocket};
use std::sync::Arc;
use std::time::{Duration, Instant};

fn cfg(name: &str, udp: SocketAddr, targets: Vec<SocketAddr>, id: [u8; 8]) -> PairConfig {
    PairConfig { name: name.into(), udp: Some(udp), targets, window: Duration::from_secs(60), id: Some(id) }
}

/// The first peer `pick` accepts within `secs`.
fn wait_peer(s: &PairSession, secs: u64, pick: impl Fn(&PairPeer) -> bool) -> Option<PairPeer> {
    let end = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < end {
        if let Some(p) = s.peers().into_iter().find(|p| pick(p)) {
            return Some(p);
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    None
}

#[test]
fn two_people_add_each_other_over_the_same_wifi() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a: Node = new_node(da.path(), "ali");
    let b: Node = new_node(db.path(), "sara");
    let (ua, ub) = (free_udp(), free_udp());
    let sa = a.pair_start(cfg("Ali", ua, vec![ub], [1; 8])).unwrap();
    let sb = b.pair_start(cfg("Sara", ub, vec![ua], [9; 8])).unwrap();

    // each sees the other's name; no code yet, nobody asked
    let sara = wait_peer(&sa, 10, |p| p.name == "Sara").expect("A sees Sara");
    let ali = wait_peer(&sb, 10, |p| p.name == "Ali").expect("B sees Ali");
    assert_eq!((sara.state, sara.via, sara.sas), (PeerState::Found, "wifi", None));
    assert_eq!(ali.state, PeerState::Found);

    // Ali asks: both screens show the same code
    assert!(sa.ask(&sara.id));
    let ali = wait_peer(&sb, 10, |p| p.state == PeerState::Incoming).expect("B is asked");
    let sara = wait_peer(&sa, 10, |p| p.state == PeerState::Outgoing).unwrap();
    assert!(ali.sas.is_some());
    assert_eq!(ali.sas, sara.sas, "the same four emoji on both phones");
    assert!(a.contact(&b.me().sk).unwrap().is_none(), "nothing is added before both confirm");

    // Sara confirms first, then Ali: both cards are added
    assert!(sb.confirm(&ali.id));
    assert!(wait_peer(&sb, 5, |p| p.state == PeerState::Waiting).is_some());
    assert!(b.contact(&a.me().sk).unwrap().is_none(), "not before Ali confirms too");
    assert!(sa.confirm(&sara.id));
    assert!(wait_peer(&sa, 10, |p| p.state == PeerState::Done).is_some(), "{:?}", sa.peers());
    assert!(wait_peer(&sb, 10, |p| p.state == PeerState::Done).is_some(), "{:?}", sb.peers());
    assert_eq!(a.contact(&b.me().sk).unwrap().map(|c| c.name), Some("sara".into()));
    assert_eq!(b.contact(&a.me().sk).unwrap().map(|c| c.name), Some("ali".into()));

    a.pair_stop();
    b.pair_stop();
    assert!(a.pair().is_none());
    a.stop();
    b.stop();
}

#[test]
fn nothing_readable_crosses_the_network() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a: Node = new_node(da.path(), "ali");
    let b: Node = new_node(db.path(), "sara");
    let (ua, ub) = (free_udp(), free_udp());
    let (announced_to, announced) = udp_recorder();
    // B announces only to a recorder; A learns B from an announcement that points at the proxy
    let sb = b.pair_start(cfg("Sara", ub, vec![announced_to], [9; 8])).unwrap();
    let sa = a.pair_start(cfg("Ali", ua, vec![ub], [1; 8])).unwrap();
    let capture = Arc::new(Capture::default());
    let proxy = recording_proxy(SocketAddr::from(([127, 0, 0, 1], sb.tcp_port())), capture.clone());
    let fake = announce_bytes(&[9; 8], proxy.port());
    let u = UdpSocket::bind("127.0.0.1:0").unwrap();
    for _ in 0..3 {
        u.send_to(&fake, ua).unwrap();
    }

    let sara = wait_peer(&sa, 10, |p| p.name == "Sara").expect("A sees Sara through the proxy");
    assert!(sa.ask(&sara.id));
    let ali = wait_peer(&sb, 10, |p| p.state == PeerState::Incoming).unwrap();
    assert!(sb.confirm(&ali.id));
    assert!(sa.confirm(&sara.id));
    assert!(wait_peer(&sa, 10, |p| p.state == PeerState::Done).is_some());
    assert!(wait_peer(&sb, 10, |p| p.state == PeerState::Done).is_some());

    // the proxy carried the whole exchange, and none of it is readable
    let wire: Vec<u8> = capture.all().concat();
    assert!(wire.len() > 2 * 1024, "the cards went through the proxy ({} bytes)", wire.len());
    for plain in [&b"Ali"[..], b"Sara", b"ali", b"sara", b"mlmchat://", b"mlmchat:"] {
        assert!(!wire.windows(plain.len()).any(|w| w == plain), "{} in the clear", String::from_utf8_lossy(plain));
    }
    // announcements are 13 bytes: no name, no key
    let ann = announced.lock().unwrap().clone();
    assert!(!ann.is_empty());
    assert!(ann.iter().all(|d| d.len() == 13));
    a.stop();
    b.stop();
}

#[test]
fn a_no_adds_nobody_and_strangers_stay_out() {
    let (da, db, dc) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a: Node = new_node(da.path(), "ali");
    let b: Node = new_node(db.path(), "sara");
    // C is on the same network but never opened the screen
    let c: Node = new_node(dc.path(), "gharibe");
    let (ua, ub) = (free_udp(), free_udp());
    let sa = a.pair_start(cfg("Ali", ua, vec![ub], [1; 8])).unwrap();
    let sb = b.pair_start(cfg("Sara", ub, vec![ua], [9; 8])).unwrap();
    let sara = wait_peer(&sa, 10, |p| p.name == "Sara").unwrap();
    assert!(sa.ask(&sara.id));
    let ali = wait_peer(&sb, 10, |p| p.state == PeerState::Incoming).unwrap();
    assert!(sb.decline(&ali.id));
    assert!(wait_peer(&sa, 10, |p| p.state == PeerState::Declined).is_some(), "{:?}", sa.peers());
    assert!(!sa.confirm(&sara.id), "no confirming after a no");
    std::thread::sleep(Duration::from_millis(500));
    assert!(a.contact(&b.me().sk).unwrap().is_none());
    assert!(b.contact(&a.me().sk).unwrap().is_none());
    assert_eq!(sa.peers().len(), 1, "only Sara is in the list");
    assert!(c.contacts().unwrap().is_empty());
    a.stop();
    b.stop();
    c.stop();
}
