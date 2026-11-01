//! Two nodes on one machine over the direct transport (loopback): the phase-1 chat path end to
//! end, LAN beacons, a restart while messages wait, and strangers being turned away.

mod common;

use common::*;
use mlmchat_core::card::Card;
use mlmchat_core::node::{Event, Node, NodeOptions};
use mlmchat_core::store::{MsgState, Store};
use mlmchat_core::transport::direct::DirectTransport;
use mlmchat_core::transport::{PeerInfo, Transport, TransportError, Verdict};

#[test]
fn add_send_deliver_reply_read() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "ali");
    let b = new_node(db.path(), "sara");
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    a.start_direct(loopback(Vec::new())).unwrap();
    b.start_direct(loopback(Vec::new())).unwrap();
    let (sa, sb) = (a.me().sk, b.me().sk);

    a.add_contact(&b.card_link().unwrap()).unwrap();
    let id = a.send_text(&sb, "salam").unwrap();

    let text = wait_for(&rx_b, &mut eb, 15, |e| match e {
        Event::Message { peer, text, .. } if *peer == sa => text.clone(),
        _ => None,
    });
    assert_eq!(text.as_deref(), Some("salam"));
    // B learnt A from A's card inside the session: a request
    assert!(eb.iter().any(|e| matches!(e, Event::Contact { peer, by_them: true } if *peer == sa)));
    let a_in_b = b.contact(&sa).unwrap().unwrap();
    assert!(a_in_b.by_them);

    // sent to the device, then delivered (B's app decrypted it)
    assert!(wait_for(&rx_a, &mut ea, 15, |e| matches!(e, Event::State { id: i, state: MsgState::Delivered, .. } if *i == id).then_some(())).is_some());

    // B answers; A gets it
    b.send_text(&sa, "aleyk").unwrap();
    let back = wait_for(&rx_a, &mut ea, 15, |e| match e {
        Event::Message { peer, text, .. } if *peer == sb => text.clone(),
        _ => None,
    });
    assert_eq!(back.as_deref(), Some("aleyk"));

    // B reads: A sees "read"
    assert_eq!(b.mark_read(&sa).unwrap(), 1);
    assert!(wait_for(&rx_a, &mut ea, 15, |e| matches!(e, Event::State { id: i, state: MsgState::Read, .. } if *i == id).then_some(())).is_some());

    // both phones show the same security number
    assert_eq!(a.security_number(&sb).unwrap(), b.security_number(&sa).unwrap());
    assert_eq!(a.security_number(&sb).unwrap().len(), 60);

    let hist = a.history(&sb, 10).unwrap();
    assert_eq!(hist.len(), 2);
    assert!(hist[0].outgoing && hist[0].state == MsgState::Read);
    assert!(!hist[1].outgoing);
    // everything of A's gets there: the «delivered» A owes B for «aleyk» goes on its own, and may
    // still be on its way when B's «read» for «salam» is in (it was checked at once, and failed
    // now and then on both teams' computers, with older code too)
    let drained = (0..100).any(|_| {
        let empty = a.outbox_len().unwrap() == 0;
        if !empty {
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        empty
    });
    assert!(drained, "A's outbox empties");

    // A renames: B gets the new card and shows the new name; the four digits stay (T14)
    let digits = a.me().display_name.rsplit('·').next().unwrap().to_string();
    let me = a.set_name("ali2").unwrap();
    assert_eq!(me.display_name, format!("ali2·{digits}"));
    assert!(wait_for(&rx_b, &mut eb, 15, |e| matches!(e, Event::ContactChanged { peer } if *peer == sa).then_some(())).is_some());
    assert_eq!(b.contact(&sa).unwrap().unwrap().name, "ali2");
}

#[test]
fn lan_beacon_finds_a_contact_whose_card_address_is_dead() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "ali");
    let b = new_node(db.path(), "sara");
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    let (disc_a, disc_b) = (free_udp(), free_udp());

    let mut ca = loopback(Vec::new());
    ca.discovery = Some(disc_a);
    ca.beacon_targets = vec![disc_b];
    // B's card points at a port nobody listens on: only its beacon can lead A to it
    let mut cb = loopback(vec!["127.0.0.1:9".parse().unwrap()]);
    cb.discovery = Some(disc_b);
    cb.beacon_targets = vec![disc_a];
    a.start_direct(ca).unwrap();
    b.start_direct(cb).unwrap();
    let (sa, sb) = (a.me().sk, b.me().sk);

    a.add_contact(&b.card_link().unwrap()).unwrap();
    a.send_text(&sb, "az rah-e beacon").unwrap();
    assert!(wait_for(&rx_a, &mut ea, 15, |e| matches!(e, Event::Seen { peer, via: "lan" } if *peer == sb).then_some(())).is_some());
    let got = wait_for(&rx_b, &mut eb, 20, |e| match e {
        Event::Message { peer, text, .. } if *peer == sa => text.clone(),
        _ => None,
    });
    assert_eq!(got.as_deref(), Some("az rah-e beacon"));
}

#[test]
fn queued_while_offline_then_delivered_after_restart() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "ali");
    let rx_a = a.events();
    let mut ea = Vec::new();
    // A hears LAN beacons: B's return is a sighting that retries A's queue at once (SPEC 14.8),
    // instead of A's next backoff step, which a busy machine can push past a minute
    let a_beacons = free_udp();
    let mut ca = loopback(Vec::new());
    ca.discovery = Some(a_beacons);
    a.start_direct(ca).unwrap();
    let sa = a.me().sk;

    let (link, sb): (String, _) = {
        let b = new_node(db.path(), "sara");
        b.start_direct(loopback(Vec::new())).unwrap();
        (b.card_link().unwrap(), b.me().sk)
        // B stops here (dropped)
    };
    a.add_contact(&link).unwrap();
    let id = a.send_text(&sb, "vaghti nabudi").unwrap();
    let reason = wait_for(&rx_a, &mut ea, 10, |e| match e {
        Event::Queued { peer, reason } if *peer == sb => Some(*reason),
        _ => None,
    });
    // Windows retries a refused loopback connect, so it can end as a timeout
    assert!(matches!(reason, Some("connect" | "timeout")), "events: {ea:?}");
    assert!(a.queue_reason(&id).unwrap().is_some());
    assert_eq!(a.history(&sb, 5).unwrap()[0].state, MsgState::Queued);

    // B comes back from its encrypted database
    let b = Node::open(&db.path().join("chat.db"), &KEY, NodeOptions::default()).unwrap();
    assert_eq!(b.me().sk, sb);
    let rx_b = b.events();
    let mut eb = Vec::new();
    // on a new port: reusing a loopback port just closed is unreliable on Windows when other
    // tests open connections meanwhile; the beacon tells A where B is now (SPEC 14.8)
    let mut cb = loopback(Vec::new());
    cb.beacon_targets = vec![a_beacons];
    b.start_direct(cb).unwrap();
    let got = wait_for(&rx_b, &mut eb, 30, |e| match e {
        Event::Message { peer, text, .. } if *peer == sa => text.clone(),
        _ => None,
    });
    assert_eq!(got.as_deref(), Some("vaghti nabudi"), "log:
{}", dump_log());
    assert!(wait_for(&rx_a, &mut ea, 30, |e| matches!(e, Event::State { id: i, state: MsgState::Delivered, .. } if *i == id).then_some(())).is_some());
}

#[test]
fn strangers_cannot_connect_or_pose_as_the_contact() {
    let (db, dc) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let b = new_node(db.path(), "sara");
    let tb = b.start_direct(loopback(Vec::new())).unwrap();
    let card = Card::from_link(&b.card_link().unwrap()).unwrap();
    let addrs = vec![tb.local_addr()];

    let c = new_node(dc.path(), "gholam");
    let tc = DirectTransport::start(loopback(Vec::new()), c.hooks()).unwrap();
    let frame = mlmchat_core::frame::encode_padded(&[], 1, b"x").unwrap();

    // without B's locator the encrypted handshake cannot finish
    let no_loc = PeerInfo::bare(card.sk, [7; 32], addrs.clone());
    assert_eq!(tc.send(&no_loc, std::slice::from_ref(&frame)).err(), Some(TransportError::Handshake));

    // with the locator but expecting someone else: B's signed hello does not match
    let wrong = PeerInfo::bare([9; 32], card.loc().unwrap(), addrs);
    assert_eq!(tc.send(&wrong, &[frame]).err(), Some(TransportError::WrongPeer));
    tc.stop();
}

/// A frame offered again after its acknowledgement was lost is acknowledged as stored, not
/// decrypted twice (Olm refuses a used message key; the sender would show "failed").
#[test]
fn a_frame_offered_twice_is_acknowledged_not_refused() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "ali");
    let b = new_node(db.path(), "sara");
    let rx_b = b.events();
    let mut eb = Vec::new();
    let tb = b.start_direct(loopback(Vec::new())).unwrap();
    let link_b = b.card_link().unwrap();
    let sb = b.me().sk;
    // A runs no delivery loop: its frames stay in the outbox, where the test reads them
    a.add_contact(&link_b).unwrap();
    a.send_text(&sb, "do bar").unwrap();
    let frames: Vec<Vec<u8>> = Store::open(&da.path().join("chat.db"), &KEY).unwrap().db().due(mlmchat_core::util::now_ms() + 86_400_000, 10).unwrap().into_iter().map(|r| r.frame).collect();
    assert_eq!(frames.len(), 2, "KeyUpdate and the text");
    let t = DirectTransport::start(loopback(Vec::new()), a.hooks()).unwrap();
    let peer = PeerInfo::bare(sb, Card::from_link(&link_b).unwrap().loc().unwrap(), vec![tb.local_addr()]);
    assert_eq!(t.send(&peer, &frames).unwrap().verdicts, vec![Verdict::Stored, Verdict::Stored]);
    assert!(wait_for(&rx_b, &mut eb, 10, |e| matches!(e, Event::Message { text: Some(t), .. } if t == "do bar").then_some(())).is_some());
    // the same frames again, as after a lost acknowledgement
    assert_eq!(t.send(&peer, &frames).unwrap().verdicts, vec![Verdict::Stored, Verdict::Stored]);
    assert_eq!(b.history(&a.me().sk, 10).unwrap().len(), 1, "stored once");
    t.stop();
}
