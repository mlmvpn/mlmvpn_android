//! Last seen (SPEC 5.5, T57; Dr.Net's owner, 27 Oct 2026: «اخرین بازدیدشو خودش مشخص کنه»): while
//! the app is in front, contacts with a route open to its phone hear «online», and «offline» once it
//! leaves; the receiver keeps when each was last seen and shows «online» only while it holds. A
//! contact who says «online» hears ours back at once. Hiding one's own last seen sends nothing,
//! keeps nothing of others' and forgets what was kept. Never into a mailbox, never a message.

mod common;

use common::*;
use mlmchat_core::node::{Event, Node};
use mlmchat_core::transport::{PeerInfo, Sent, Transport, TransportError, Verdict};
use mlmchat_core::UserId;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

/// A mailbox that takes anything at once and claims a fresh route to everyone.
struct Mailbox {
    name: &'static str,
    frames: AtomicUsize,
}

impl Transport for Mailbox {
    fn name(&self) -> &'static str {
        self.name
    }

    fn send(&self, _peer: &PeerInfo, frames: &[Vec<u8>]) -> Result<Sent, TransportError> {
        self.frames.fetch_add(frames.len(), Ordering::SeqCst);
        Ok(Sent { verdicts: vec![Verdict::Stored; frames.len()], via: self.name })
    }

    fn advertised_addrs(&self) -> Vec<std::net::SocketAddr> {
        Vec::new()
    }

    fn has_fresh_route(&self, _sk: &UserId) -> bool {
        true
    }

    fn stop(&self) {}
}

fn up(dir: &std::path::Path, name: &str) -> Node {
    let n = new_node(dir, name);
    n.start_direct(loopback(Vec::new())).unwrap();
    n
}

fn eventually(secs: u64, mut f: impl FnMut() -> bool) -> bool {
    for _ in 0..secs * 10 {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    false
}

fn presence_of(peer: UserId, online: bool) -> impl FnMut(&Event) -> Option<()> {
    move |e| matches!(e, Event::Presence { peer: p, online: o } if *p == peer && *o == online).then_some(())
}

#[test]
fn last_seen_goes_to_open_routes_and_hiding_it_hides_everyone() {
    init_log();
    let (d1, d2) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(d1.path(), "ali");
    let b = up(d2.path(), "sara");
    let (sa, sb) = (a.me().sk, b.me().sk);
    // Sara's app is in front from the start; she has nobody to tell yet
    assert_eq!(b.set_online(true).unwrap(), 0);
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some()), "Sara has Ali\n{}", dump_log());
    let first = a.send_text(&sb, "salam").unwrap();
    assert!(eventually(20, || b.history(&sa, 5).unwrap().iter().any(|m| m.id == first)));
    let back = b.send_text(&sa, "salam ham").unwrap();
    assert!(eventually(20, || a.history(&sb, 5).unwrap().iter().any(|m| m.id == back)));
    assert!(eventually(20, || a.outbox_len().unwrap() == 0 && b.outbox_len().unwrap() == 0), "everything delivered");
    assert!(a.last_seen_visible() && b.last_seen_visible(), "shown by default");

    // from now on Ali also has two mailboxes that would take anything at once
    let gas = Arc::new(Mailbox { name: "gas", frames: AtomicUsize::new(0) });
    let nostr = Arc::new(Mailbox { name: "nostr", frames: AtomicUsize::new(0) });
    a.start_with(gas.clone());
    a.start_with(nostr.clone());
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());

    // Ali opens the app: Sara hears «online», and, being in front herself, answers at once
    assert_eq!(a.set_online(true).unwrap(), 1);
    assert!(wait_for(&rx_b, &mut eb, 20, presence_of(sa, true)).is_some(), "Sara sees Ali online: {eb:?}\n{}", dump_log());
    let st = b.contact_status(&sa).unwrap();
    assert!(st.in_app && st.last_seen_ms > 0, "{st:?}");
    assert!(wait_for(&rx_a, &mut ea, 20, presence_of(sb, true)).is_some(), "Ali sees Sara online at once: {ea:?}");
    assert!(a.contact_status(&sb).unwrap().in_app);
    // said again within the round: nobody is told twice
    assert_eq!(a.set_online(true).unwrap(), 0);

    // Ali leaves the app: Sara sees him offline, with when
    assert_eq!(a.set_online(false).unwrap(), 1);
    assert!(wait_for(&rx_b, &mut eb, 20, presence_of(sa, false)).is_some(), "{eb:?}");
    let st = b.contact_status(&sa).unwrap();
    assert!(!st.in_app && st.last_seen_ms > 0, "{st:?}");

    // nothing of it is a message, queued, or in a mailbox
    std::thread::sleep(Duration::from_millis(500));
    assert_eq!(a.outbox_len().unwrap() + b.outbox_len().unwrap(), 0);
    assert_eq!(a.history(&sb, 10).unwrap().len(), 2);
    assert_eq!(b.history(&sa, 10).unwrap().len(), 2);
    assert_eq!(gas.frames.load(Ordering::SeqCst) + nostr.frames.load(Ordering::SeqCst), 0, "a mailbox got presence");

    // Sara hides her last seen: Ali sees her go offline at once, she forgets his, and from then on
    // she neither sends nor keeps any
    b.set_last_seen_visible(false).unwrap();
    assert!(!b.last_seen_visible());
    assert!(wait_for(&rx_a, &mut ea, 20, presence_of(sb, false)).is_some(), "Ali sees Sara leave: {ea:?}");
    assert_eq!(b.contact_status(&sa).unwrap().last_seen_ms, 0, "Sara sees nobody's now");
    assert_eq!(b.set_online(true).unwrap(), 0, "Sara tells nobody");
    eb.clear();
    assert_eq!(a.set_online(true).unwrap(), 1, "Ali still tells her");
    assert!(wait_for(&rx_b, &mut eb, 3, presence_of(sa, true)).is_none(), "Sara keeps nothing of it: {eb:?}");
    let st = b.contact_status(&sa).unwrap();
    assert!(!st.in_app && st.last_seen_ms == 0, "{st:?}");
    assert_eq!(gas.frames.load(Ordering::SeqCst) + nostr.frames.load(Ordering::SeqCst), 0);

    // shown again while in front: «online» goes at once
    ea.clear();
    b.set_last_seen_visible(true).unwrap();
    assert!(wait_for(&rx_a, &mut ea, 20, presence_of(sb, true)).is_some(), "{ea:?}");

    for n in [&a, &b] {
        n.stop();
    }
}
