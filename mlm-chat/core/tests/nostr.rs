//! The Nostr transport (SPEC 20): two phones whose only way is a Nostr relay (an emulator on
//! loopback). Covers: becoming contacts, messages and receipts both ways, a frame larger than one
//! part, a relay that refuses writes (the other one carries), a relay that drops every
//! connection (they come back), a relay that never answers (nothing waits for it), and what the
//! relay sees (the canary).

mod common;

use common::nostr_emu::Relay;
use common::*;
use mlmchat_core::envelope::{Body, Envelope};
use mlmchat_core::node::{Event, Node};
use mlmchat_core::store::MsgState;
use mlmchat_core::transport::nostr::transport::NostrConfig;
use std::time::Duration;

const CANARY: &str = "QNARY-5512 payam az rah-e nostr";

fn cfg(relays: &[&Relay]) -> NostrConfig {
    NostrConfig {
        relays: relays.iter().map(|r| r.url()).collect(),
        keep: 4,
        connect_timeout: Duration::from_secs(5),
        ok_timeout: Duration::from_secs(8),
        rest: Duration::from_secs(5),
        own: Vec::new(),
    }
}

/// Two phones that are contacts, each with `ncfg`, once their relays are open.
fn pair(da: &std::path::Path, db: &std::path::Path, ncfg: &NostrConfig, open: usize) -> (Node, Node) {
    let (a, b) = (new_node(da, "a"), new_node(db, "b"));
    for n in [&a, &b] {
        n.start_nostr(ncfg.clone()).unwrap();
    }
    assert!(eventually(20, || [&a, &b].iter().all(|n| n.nostr_relays().iter().filter(|x| x.state == "open").count() >= open)), "relays open\n{}", dump_log());
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let (sa, sb) = (a.me().sk, b.me().sk);
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some() && a.contact(&sb).unwrap().is_some_and(|c| c.loc.is_some())), "contacts\n{}", dump_log());
    (a, b)
}

fn got(n: &Node, from: &[u8; 32], text: &str) -> bool {
    n.history(from, 50).unwrap().iter().any(|m| {
        !m.outgoing
            && match Envelope::from_bytes(&m.envelope).ok().and_then(|e| e.body) {
                Some(Body::Text(t)) => t.body == text,
                _ => false,
            }
    })
}

/// Our relay in Iran, 31 Oct 2026: it said «rate-limited» to phones behind a busy operator
/// address, then «Write error» when its disk filled, and every phone that heard it never wrote
/// there again until the app restarted. A refusal for now only rests its writes a little.
#[test]
fn our_relay_that_refused_for_now_is_written_to_again() {
    init_log();
    let r = Relay::start();
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let ncfg = NostrConfig { own: vec![r.url()], ..cfg(&[&r]) };
    let (a, b) = pair(da.path(), db.path(), &ncfg, 1);
    let (sa, sb) = (a.me().sk, b.me().sk);
    for why in ["rate-limited: slow down", "Write error"] {
        r.refuse_writes_with(why);
        a.send_text(&sb, why).unwrap();
        std::thread::sleep(Duration::from_secs(3));
        assert!(!got(&b, &sa, why), "nothing came while it refused");
        r.refuse_writes(false);
        assert!(eventually(45, || got(&b, &sa, why)), "after «{why}» it is written to again\n{}", dump_log());
    }
    // and a public relay's refusal for good still lasts (the other test), ours never does
    a.stop();
    b.stop();
}

/// A restart of our relay takes seconds (13 s, 31 Oct 2026). Before, two failed connections set
/// it aside for 10 minutes on every phone, so nothing went there or came from there for 10
/// minutes after each restart. Ours is tried again within seconds.
#[test]
fn our_relay_back_after_a_restart_is_used_within_seconds() {
    init_log();
    let r = Relay::start();
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let ncfg = NostrConfig { own: vec![r.url()], rest: Duration::from_secs(600), ..cfg(&[&r]) };
    let (a, b) = pair(da.path(), db.path(), &ncfg, 1);
    let (sa, sb) = (a.me().sk, b.me().sk);
    r.down(true);
    std::thread::sleep(Duration::from_secs(8));
    // still in the card meanwhile: what is written there waits for us
    assert!(b.card_link().is_ok());
    r.down(false);
    a.send_text(&sb, "bad az restart").unwrap();
    assert!(eventually(40, || got(&b, &sa, "bad az restart")), "within seconds, not after a 10-minute rest\n{}", dump_log());
    a.stop();
    b.stop();
}

/// A relay back from its rest takes its place in the list again: before, it waited for one of the
/// others to fail, so a phone could keep reading only foreign relays while phones on another
/// network had our relay in Iran as their one way.
#[test]
fn a_relay_back_from_its_rest_takes_its_place_again() {
    init_log();
    let (r1, r2) = (Relay::start(), Relay::start());
    r1.down(true);
    let d = tempfile::tempdir().unwrap();
    let a = new_node(d.path(), "a");
    a.start_nostr(NostrConfig { keep: 1, rest: Duration::from_secs(3), ..cfg(&[&r1, &r2]) }).unwrap();
    let reads = |n: &Node, r: &Relay| n.nostr_relays().iter().any(|x| x.relay == r.url() && x.reads && x.state == "open");
    assert!(eventually(30, || reads(&a, &r2)), "the second reads while the first is down\n{}", dump_log());
    r1.down(false);
    assert!(eventually(30, || reads(&a, &r1) && !a.nostr_relays().iter().any(|x| x.relay == r2.url() && x.reads)), "the first takes its place back\n{}", dump_log());
    a.stop();
}

/// At the start the first relay to open was all contacts were told of: our card named only it,
/// so when it stopped nothing reached us by the others we read from. The card goes once the
/// relays open have settled.
#[test]
fn contacts_hear_of_every_relay_we_read_from() {
    init_log();
    let (r1, r2) = (Relay::start(), Relay::start());
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let ncfg = cfg(&[&r1, &r2]);
    let (a, b) = pair(da.path(), db.path(), &ncfg, 2);
    let (sa, sb) = (a.me().sk, b.me().sk);
    // Bahar's phone starts again; the second relay is slow to open, so a second after the start
    // she reads from the first only
    b.stop();
    drop(b);
    r2.slow(3000);
    let b = Node::open(&db.path().join("chat.db"), &KEY, mlmchat_core::node::NodeOptions::default()).unwrap();
    b.set_file_relays(Vec::new());
    b.set_device(db.path().to_string_lossy().as_bytes());
    b.start_nostr(ncfg.clone()).unwrap();
    assert!(eventually(20, || b.nostr_relays().iter().filter(|x| x.state == "open").count() == 2), "both open again\n{}", dump_log());
    // a while later the first relay stops for good: Arash still reaches her by the second
    std::thread::sleep(Duration::from_secs(25));
    r1.down(true);
    a.send_text(&sb, "az rele-ye dovom").unwrap();
    assert!(eventually(40, || got(&b, &sa, "az rele-ye dovom")), "by the other relay her card names\n{}", dump_log());
    a.stop();
    b.stop();
}

fn up(dir: &std::path::Path, name: &str, relays: &[&Relay]) -> Node {
    let n = new_node(dir, name);
    n.start_nostr(cfg(relays)).unwrap();
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

#[test]
fn two_phones_talk_through_nostr_relays_only() {
    init_log();
    let (r1, r2) = (Relay::start(), Relay::start());
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(da.path(), "Arash-5512", &[&r1, &r2]);
    let b = up(db.path(), "Bahar-5512", &[&r1, &r2]);
    let (sa, sb) = (a.me().sk, b.me().sk);
    let rx_a = a.events();
    let rx_b = b.events();
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    // both relays open before anything is sent
    assert!(eventually(20, || a.nostr_relays().iter().filter(|r| r.state == "open").count() == 2 && b.nostr_relays().iter().filter(|r| r.state == "open").count() == 2), "relays open\n{}", dump_log());

    // the card says «listens on Nostr»: Arash adds Bahar, and her answer card comes back the same way
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Contact { peer, by_them: true } if *peer == sa).then_some(())).is_some(), "Bahar got Arash's request\n{}", dump_log());
    assert!(eventually(20, || a.contact(&sb).unwrap().is_some_and(|c| c.loc.is_some())));

    let id = a.send_text(&sb, CANARY).unwrap();
    let got = wait_for(&rx_b, &mut eb, 20, |e| match e {
        Event::Message { text: Some(t), .. } if t == CANARY => Some(()),
        _ => None,
    });
    assert!(got.is_some(), "Bahar got the message\n{}", dump_log());
    assert!(wait_for(&rx_a, &mut ea, 20, |e| matches!(e, Event::State { id: i, state: MsgState::Delivered | MsgState::Read, .. } if *i == id).then_some(())).is_some(), "the receipt came back
{}", dump_log());
    b.send_text(&sa, "javab").unwrap();
    assert!(wait_for(&rx_a, &mut ea, 20, |e| matches!(e, Event::Message { text: Some(t), .. } if t == "javab").then_some(())).is_some(), "Arash got the answer");

    // a relay that refuses writes: the other one carries, and it is not written to again
    r2.refuse_writes(true);
    let before = r2.writes();
    for k in 0..3 {
        a.send_text(&sb, &format!("ba yek rele {k}")).unwrap();
    }
    assert!(eventually(20, || b.history(&sa, 50).unwrap().iter().filter(|m| !m.outgoing).count() >= 4), "all came through the one that takes writes");
    // both phones write (Arash the messages, Bahar the receipts); each learns from its own refusal.
    // A phone has up to two sends to a contact on their way at once (a message, and a receipt that no
    // longer waits behind it), so two writes each may reach the relay before the first refusal is back;
    // a phone that did not learn would write all six.
    assert!(r2.writes() - before <= 4, "after one refusal each, no more writes to it ({})", r2.writes() - before);

    // every connection dropped: they open again and messages go on
    r1.drop_all();
    std::thread::sleep(Duration::from_secs(1));
    b.send_text(&sa, "bad az ghat").unwrap();
    assert!(wait_for(&rx_a, &mut ea, 30, |e| matches!(e, Event::Message { text: Some(t), .. } if t == "bad az ghat").then_some(())).is_some(), "after the drop\n{}", dump_log());

    // the relays saw only sealed events under throwaway keys and day tags
    let all = format!("{:?}{:?}", r1.events(), r2.events());
    for needle in [CANARY, "Arash-5512", "Bahar-5512", &hex::encode(sa), &hex::encode(sb)] {
        assert!(!all.contains(needle), "{needle} is not on the relay");
    }
    let keys: std::collections::HashSet<String> = r1.events().iter().filter_map(|e| e.get("pubkey").and_then(|p| p.as_str()).map(String::from)).collect();
    assert_eq!(keys.len(), r1.events().len(), "a new key for every event");
    a.stop();
    b.stop();
}

/// A relay that takes the connection and every write and never answers (a filter that lets it open
/// and drops what comes back) holds nothing: one relay's OK is enough. Waiting for every relay's
/// answer, each frame waited `ok_timeout` for the quiet one, and a phone's receipts queued behind
/// each other for minutes (28 Oct 2026: Ehsan's messages stayed at one tick on his phone).
#[test]
fn a_relay_that_never_answers_holds_nothing() {
    init_log();
    let (quiet, r) = (Relay::start(), Relay::start());
    quiet.mute(true);
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    // waiting it out even once fails the test
    let ncfg = NostrConfig { ok_timeout: Duration::from_secs(30), ..cfg(&[&quiet, &r]) };
    let (a, b) = (new_node(da.path(), "a"), new_node(db.path(), "b"));
    for n in [&a, &b] {
        n.start_nostr(ncfg.clone()).unwrap();
    }
    let (sa, sb) = (a.me().sk, b.me().sk);
    // the quiet one looks open as well, so both cards name it
    assert!(eventually(20, || [&a, &b].iter().all(|n| n.nostr_relays().iter().filter(|x| x.state == "open").count() == 2)), "relays open\n{}", dump_log());
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some() && a.contact(&sb).unwrap().is_some_and(|c| c.loc.is_some())), "contacts\n{}", dump_log());
    let before = quiet.writes();
    for k in 0..3 {
        a.send_text(&sb, &format!("payam {k}")).unwrap();
    }
    let texts = |n: &Node, peer: &[u8; 32], outgoing: bool| n.history(peer, 20).unwrap().into_iter().filter(|m| m.kind == "text" && m.outgoing == outgoing).collect::<Vec<_>>();
    let sent = || texts(&a, &sb, true).iter().filter(|m| m.state != MsgState::Queued).count();
    assert!(eventually(10, || sent() == 3), "all three sent long before ok_timeout ({} of 3)\n{}", sent(), dump_log());
    assert!(eventually(10, || texts(&b, &sa, false).len() == 3), "Bahar got them\n{}", dump_log());
    // and her receipts come back: they did not wait either
    assert!(eventually(10, || texts(&a, &sb, true).iter().all(|m| m.state == MsgState::Delivered || m.state == MsgState::Read)), "the receipts came back\n{}", dump_log());
    assert!(quiet.writes() > before, "the quiet relay was written to as well");
    a.stop();
    b.stop();
}

#[test]
fn a_frame_larger_than_a_part_arrives_whole() {
    init_log();
    let r = Relay::start();
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(da.path(), "a", &[&r]);
    let b = up(db.path(), "b", &[&r]);
    let (sa, sb) = (a.me().sk, b.me().sk);
    assert!(eventually(20, || a.nostr_relays().iter().any(|x| x.state == "open") && b.nostr_relays().iter().any(|x| x.state == "open")));
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some() && a.contact(&sb).unwrap().is_some_and(|c| c.loc.is_some())));
    // 40 KB of text: more than one 16 KiB part
    let long: String = "salam ".repeat(7000);
    a.send_text(&sb, &long).unwrap();
    assert!(eventually(30, || b.history(&sa, 10).unwrap().iter().any(|m| !m.outgoing)), "the long message came\n{}", dump_log());
    a.stop();
    b.stop();
}
