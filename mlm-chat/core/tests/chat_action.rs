//! Chat actions (SPEC 5.4, T56; Dr.Net's owner, 27 Oct 2026: «موقع تایپ بزنه در حال تایپ کردن، یا
//! چیزی میفرسته بزنه در حال ارسال»): «typing» reaches the contact's phone over a route open now and
//! is never kept, receipted or queued; at most one every five seconds, its end at once and once;
//! nothing goes with no open route, and never into a mailbox, however ready one is.

mod common;

use common::*;
use mlmchat_core::envelope::ChatActionKind;
use mlmchat_core::node::{Event, Node, ACTION_EVERY_MS};
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

fn action_from(peer: UserId, kind: &'static str) -> impl FnMut(&Event) -> Option<()> {
    move |e| matches!(e, Event::ChatAction { peer: p, kind: k } if *p == peer && *k == kind).then_some(())
}

#[test]
fn typing_goes_to_the_phone_now_or_not_at_all() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(d1.path(), "ali");
    let b = up(d2.path(), "sara");
    let (sa, sb) = (a.me().sk, b.me().sk);
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some()), "Sara has Ali\n{}", dump_log());
    // a message each way: each phone has reached the other, and a session exists
    let first = a.send_text(&sb, "salam").unwrap();
    assert!(eventually(20, || b.history(&sa, 5).unwrap().iter().any(|m| m.id == first)));
    let back = b.send_text(&sa, "salam ham").unwrap();
    assert!(eventually(20, || a.history(&sb, 5).unwrap().iter().any(|m| m.id == back)));
    assert!(eventually(20, || a.outbox_len().unwrap() == 0 && b.outbox_len().unwrap() == 0), "everything delivered");

    // from now on Ali also has two mailboxes that would take anything at once
    let gas = Arc::new(Mailbox { name: "gas", frames: AtomicUsize::new(0) });
    let nostr = Arc::new(Mailbox { name: "nostr", frames: AtomicUsize::new(0) });
    a.start_with(gas.clone());
    a.start_with(nostr.clone());

    let rx_b = b.events();
    let mut eb = Vec::new();
    assert!(a.chat_action(&sb, ChatActionKind::Typing).unwrap(), "it goes");
    assert!(wait_for(&rx_b, &mut eb, 20, action_from(sa, "typing")).is_some(), "Sara sees «typing»: {eb:?}\n{}", dump_log());
    // the pace: no second one within five seconds, of any kind
    assert!(!a.chat_action(&sb, ChatActionKind::Typing).unwrap());
    assert!(!a.chat_action(&sb, ChatActionKind::UploadPhoto).unwrap());
    // its end goes at once, once
    assert!(a.chat_action(&sb, ChatActionKind::Cancel).unwrap());
    assert!(wait_for(&rx_b, &mut eb, 20, action_from(sa, "cancel")).is_some(), "Sara sees it end: {eb:?}");
    assert!(!a.chat_action(&sb, ChatActionKind::Cancel).unwrap(), "nothing left to end");

    // never kept, receipted or queued, on either side, and never in a mailbox
    std::thread::sleep(Duration::from_millis(500));
    assert_eq!(a.outbox_len().unwrap(), 0);
    assert_eq!(b.outbox_len().unwrap(), 0, "no receipt for it");
    assert_eq!(a.history(&sb, 10).unwrap().len(), 2);
    assert_eq!(b.history(&sa, 10).unwrap().len(), 2);
    assert_eq!(gas.frames.load(Ordering::SeqCst) + nostr.frames.load(Ordering::SeqCst), 0, "a mailbox got a chat action");

    // five seconds on, the next one goes
    std::thread::sleep(Duration::from_millis(ACTION_EVERY_MS));
    assert!(a.chat_action(&sb, ChatActionKind::RecordVoice).unwrap());
    assert!(wait_for(&rx_b, &mut eb, 20, action_from(sa, "record_voice")).is_some(), "{eb:?}");
    assert_eq!(gas.frames.load(Ordering::SeqCst) + nostr.frames.load(Ordering::SeqCst), 0);

    // a contact this phone has not reached: dropped at once, nothing queued for it
    let c = up(d3.path(), "reza");
    let link_c = c.card_link().unwrap();
    c.stop();
    drop(c);
    let sc = a.add_contact(&link_c).unwrap().sk;
    // our card for him goes into a mailbox (his phone is off): nothing waits in the outbox
    assert!(eventually(20, || a.outbox_len().unwrap() == 0), "{}", dump_log());
    assert!(!a.chat_action(&sc, ChatActionKind::Typing).unwrap(), "no route open to Reza's phone");
    assert_eq!(a.outbox_len().unwrap(), 0, "nothing queued");

    for n in [&a, &b] {
        n.stop();
    }
}
