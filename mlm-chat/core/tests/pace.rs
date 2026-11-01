//! A one-to-one chat keeps the pace of a group (Ehsan, 28 Oct 2026: «کندی چت شخصی نسبت به
//! گروه»). Two nodes on loopback behind a route that takes a second for each delivery, as a
//! mailbox or a far phone does. A one-to-one chat sends receipts («delivered», «read») that a
//! group does not; an answer must not wait behind them.

mod common;

use common::*;
use mlmchat_core::node::{Event, Node};
use mlmchat_core::transport::direct::DirectTransport;
use mlmchat_core::transport::{PeerInfo, Sent, Transport, TransportError};
use std::net::SocketAddr;
use std::sync::mpsc::Receiver;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// What one delivery takes on the slow route.
const DELAY: Duration = Duration::from_millis(1000);
const ROUNDS: u32 = 3;

/// The direct route, `DELAY` slower for each delivery.
struct Slow(Arc<DirectTransport>);

impl Transport for Slow {
    fn name(&self) -> &'static str {
        "slow"
    }

    fn send(&self, peer: &PeerInfo, frames: &[Vec<u8>]) -> Result<Sent, TransportError> {
        std::thread::sleep(DELAY);
        self.0.send(peer, frames)
    }

    fn advertised_addrs(&self) -> Vec<SocketAddr> {
        self.0.advertised_addrs()
    }

    fn stop(&self) {
        self.0.stop()
    }
}

fn up(dir: &std::path::Path, name: &str) -> Node {
    let n = new_node(dir, name);
    let t = DirectTransport::start(loopback(Vec::new()), n.hooks()).unwrap();
    n.start_with(Arc::new(Slow(t)));
    n
}

fn text_from(rx: &Receiver<Event>, seen: &mut Vec<Event>, from: &[u8; 32], want: &str) -> bool {
    wait_for(rx, seen, 30, |e| match e {
        Event::Message { peer, text: Some(t), .. } if peer == from && t == want => Some(()),
        _ => None,
    })
    .is_some()
}

fn group_text_from(rx: &Receiver<Event>, seen: &mut Vec<Event>, gid: &[u8], want: &str) -> bool {
    wait_for(rx, seen, 30, |e| match e {
        Event::GroupMessage { gid: g, text: Some(t), .. } if g == gid && t == want => Some(()),
        _ => None,
    })
    .is_some()
}

/// Until both outboxes are empty: the next round starts with nothing on its way.
fn settle(a: &Node, b: &Node) {
    let end = Instant::now() + Duration::from_secs(30);
    while Instant::now() < end && (a.outbox_len().unwrap() > 0 || b.outbox_len().unwrap() > 0) {
        std::thread::sleep(Duration::from_millis(50));
    }
}

#[test]
fn a_one_to_one_answer_does_not_wait_behind_receipts() {
    init_log();
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(da.path(), "ali");
    let b = up(db.path(), "sara");
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    let (sa, sb) = (a.me().sk, b.me().sk);

    // sessions both ways, and a group of the two
    a.add_contact(&b.card_link().unwrap()).unwrap();
    a.send_text(&sb, "salam").unwrap();
    assert!(text_from(&rx_b, &mut eb, &sa, "salam"), "{}", dump_log());
    b.send_text(&sa, "aleyk").unwrap();
    assert!(text_from(&rx_a, &mut ea, &sb, "aleyk"), "{}", dump_log());
    let gid = a.group_create("do nafar", "", false).unwrap();
    a.group_add(&gid, &sb).unwrap();
    assert!(wait_for(&rx_b, &mut eb, 30, |e| matches!(e, Event::Group { gid: g } if *g == gid).then_some(())).is_some());
    a.group_send_text(&gid, "salam").unwrap();
    assert!(group_text_from(&rx_b, &mut eb, &gid, "salam"));
    b.group_send_text(&gid, "aleyk").unwrap();
    assert!(group_text_from(&rx_a, &mut ea, &gid, "aleyk"));
    settle(&a, &b);

    // a question and its answer, with the chat open on both phones (Telegram reads at once)
    let mut one = Duration::ZERO;
    for i in 0..ROUNDS {
        let t = Instant::now();
        a.send_text(&sb, &format!("q{i}")).unwrap();
        assert!(text_from(&rx_b, &mut eb, &sa, &format!("q{i}")), "{}", dump_log());
        b.mark_read(&sa).unwrap();
        b.send_text(&sa, &format!("r{i}")).unwrap();
        assert!(text_from(&rx_a, &mut ea, &sb, &format!("r{i}")), "{}", dump_log());
        a.mark_read(&sb).unwrap();
        one += t.elapsed();
    }
    settle(&a, &b);
    let mut group = Duration::ZERO;
    for i in 0..ROUNDS {
        let t = Instant::now();
        a.group_send_text(&gid, &format!("q{i}")).unwrap();
        assert!(group_text_from(&rx_b, &mut eb, &gid, &format!("q{i}")), "{}", dump_log());
        b.group_send_text(&gid, &format!("r{i}")).unwrap();
        assert!(group_text_from(&rx_a, &mut ea, &gid, &format!("r{i}")), "{}", dump_log());
        group += t.elapsed();
    }
    let (one, group) = (one / ROUNDS, group / ROUNDS);
    eprintln!("question and answer: one-to-one {} ms, group {} ms", one.as_millis(), group.as_millis());
    // the same two deliveries each way; a little room for the delivery loop's own pace
    assert!(one <= group + DELAY / 2, "one-to-one {} ms, group {} ms", one.as_millis(), group.as_millis());
    a.stop();
    b.stop();
}
