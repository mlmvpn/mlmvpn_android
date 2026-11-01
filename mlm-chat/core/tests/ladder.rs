//! Phase 3, SPEC 15.9: the connection ladder and a volunteer's reach check, on loopback.
//!
//! - The ladder climbs a volunteer's addresses one at a time: a dead one fails, the next one
//!   works, and the network's memory puts the working one first on the next climb.
//! - A volunteer announces an address only after a peer really connected to it; a peer asked
//!   over `/mlmchat/reach/1` dials it back on a fresh connection.

mod common;

use common::*;
use mlmchat_core::node::{Node, NodeOptions};
use mlmchat_core::transport::p2p::{Multiaddr, NetStatus, P2pConfig};
use std::net::SocketAddr;
use std::path::Path;
use std::time::{Duration, Instant};

fn ma(addr: SocketAddr) -> Multiaddr {
    format!("/ip4/{}/tcp/{}/tls", addr.ip(), addr.port()).parse().unwrap()
}

fn node(dir: &Path, name: &str) -> Node {
    Node::create(&dir.join("chat.db"), &KEY, name, NodeOptions::default()).unwrap()
}

fn wait_net(n: &Node, secs: u64, ok: impl Fn(&NetStatus) -> bool) -> Option<NetStatus> {
    let end = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < end {
        if let Some(p) = n.net_status().p2p {
            if ok(&p) {
                return Some(p);
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    None
}

#[test]
fn ladder_climbs_past_a_dead_rung_and_remembers_the_live_one() {
    init_log();
    let (dv, dc) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let v = node(dv.path(), "davtalab");
    let vport = free_tcp();
    let tv = v.start_p2p(P2pConfig { listen: vec![ma(vport)], volunteer: true, allow_loopback: true, ..P2pConfig::default() }).unwrap();
    let vid = tv.peer_id();
    // the first rung leads nowhere (nothing listens there), the second is the volunteer
    let dead: Multiaddr = format!("{}/p2p/{vid}", ma(free_tcp())).parse().unwrap();
    let live: Multiaddr = format!("{}/p2p/{vid}", ma(vport)).parse().unwrap();

    let c = node(dc.path(), "rayaneh");
    c.set_network("test:ladder");
    let cfg = P2pConfig {
        listen: vec!["/ip4/127.0.0.1/tcp/0".parse().unwrap()],
        boot: vec![dead.clone(), live.clone()],
        allow_loopback: true,
        rung_stagger: Duration::from_secs(1),
        ..P2pConfig::default()
    };
    c.start_p2p(cfg).unwrap();
    let st = wait_net(&c, 30, |p| p.ladder == "reachable" && p.reservations >= 1).unwrap_or_else(|| panic!("never reached the volunteer: {:?}\n{}", c.net_status(), dump_log()));
    assert_eq!(st.tried, 2, "the dead rung first, then the live one: {st:?}\n{}", dump_log());
    assert!(st.rung.starts_with("lan:"), "{st:?}");

    // a new climb on the same network: the live rung first now, and nothing else is needed
    c.network_changed();
    // the status of the old climb stays until the old connection is gone and the new one starts
    wait_net(&c, 15, |p| p.ladder == "probing").unwrap_or_else(|| panic!("no new climb: {:?}\n{}", c.net_status(), dump_log()));
    let st = wait_net(&c, 30, |p| p.ladder == "reachable" && p.reservations >= 1).unwrap_or_else(|| panic!("never reached it again: {:?}\n{}", c.net_status(), dump_log()));
    assert_eq!(st.tried, 1, "this network's memory puts the live rung first: {st:?}");
    let log = dump_log();
    assert!(log.contains("p2p: ladder: reached a volunteer on lan:"), "{log}");
    c.stop();
    v.stop();
}

#[test]
fn a_volunteer_announces_only_what_a_peer_really_reached() {
    init_log();
    let (dv, dc) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let v = node(dv.path(), "davtalab");
    // two doors: the peer comes in through the first; the second only a dial-back can prove
    let (p1, p2) = (free_tcp(), free_tcp());
    let tv = v
        .start_p2p(P2pConfig { listen: vec![ma(p1), ma(p2)], volunteer: true, allow_loopback: true, trust_lan_reach: true, ..P2pConfig::default() })
        .unwrap();
    let vid = tv.peer_id();
    let st = v.net_status().p2p.unwrap();
    assert!(st.verified.is_empty(), "nothing is verified before anyone came: {st:?}");

    let c = node(dc.path(), "rayaneh");
    let boot: Multiaddr = format!("{}/p2p/{vid}", ma(p1)).parse().unwrap();
    c.start_p2p(P2pConfig { listen: vec!["/ip4/127.0.0.1/tcp/0".parse().unwrap()], boot: vec![boot], allow_loopback: true, ..P2pConfig::default() }).unwrap();
    let both = |p: &NetStatus| p.verified.iter().any(|a| a.contains(&format!("/tcp/{}/", p1.port()))) && p.verified.iter().any(|a| a.contains(&format!("/tcp/{}/", p2.port())));
    let st = wait_net(&v, 60, both).unwrap_or_else(|| panic!("not both verified: {:?}\n{}", v.net_status(), dump_log()));
    assert_eq!(st.candidates, 2, "{st:?}");
    let log = dump_log();
    assert!(log.contains("p2p: reach check: helper connected"), "the second door was proven by a dial-back: {log}");
    assert!(log.contains("p2p: reachable from another network (ipv4, listen)"), "{log}");
    c.stop();
    v.stop();
}
