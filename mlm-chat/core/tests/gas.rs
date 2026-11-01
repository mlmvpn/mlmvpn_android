//! The Google mailbox relay (SPEC 19). Two nodes with no other route reach each other only
//! through mailboxes: each puts sealed frames in the other's mailbox (an Apps Script emulator on
//! loopback, redirect and all) and takes what waits for it. Covers: delivery both ways, receipts,
//! a file cut into 16 KiB parts, a mailbox that lost an item (the sender puts it again), a
//! mailbox that is down (the next one is used), the owner pausing a relay, and what the mailbox
//! can see (the canary).

mod common;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use common::gas_emu::Emu;
use common::*;
use mlmchat_core::node::{Event, Node};
use mlmchat_core::store::MsgState;
use mlmchat_core::transport::gas::http::Endpoint;
use mlmchat_core::transport::gas::GasConfig;
use serde_json::Value;
use std::sync::mpsc::Receiver;
use std::time::{Duration, Instant};

const CANARY: &str = "QNARY-7731 payam az rah-e sandoogh";
const NAME_A: &str = "Canary-Arash-7731";
const NAME_B: &str = "Canary-Bahar-7731";

fn cfg(emu: &Emu) -> GasConfig {
    GasConfig {
        endpoint: Some(Endpoint::Plain(emu.addr)),
        wait_ms: 1500,
        idle_soon: Duration::from_millis(300),
        idle_late: Duration::from_millis(300),
        request_timeout: Duration::from_secs(5),
        pending_tick: Duration::from_millis(200),
        // the first check of a put frame: 400 ms often came before the other node had taken and
        // acknowledged it (a busy computer), the answer was `wait`, the next check minutes away,
        // and a frame put while the pair was made stayed watched past the end of a test
        pending_first: Duration::from_secs(3),
        ..Default::default()
    }
}

fn keys_of(script: &str) -> (String, String) {
    let pick = |name: &str| {
        let k = format!("var {name} = \"");
        let i = script.find(&k).expect("key in script") + k.len();
        script[i..i + 32].to_string()
    };
    (pick("USE_KEY"), pick("OWNER_KEY"))
}

/// A relay on `emu` for `node`: made, "deployed" in the emulator, finished. Returns its rid.
fn make_relay(node: &Node, emu: &Emu, name: &str, n: u32) -> String {
    let d = node.gas_relay_new(name).unwrap();
    let (u, o) = keys_of(&d.script);
    let id = format!("AKfycbEmu{n:03}abcdefghijklmnopqrstuvwxyz0123456789");
    emu.add(&id, &u, &o);
    node.gas_relay_finish(&d.rid, &format!("https://script.google.com/macros/s/{id}/exec")).unwrap()
}

fn id_of(n: u32) -> String {
    format!("AKfycbEmu{n:03}abcdefghijklmnopqrstuvwxyz0123456789")
}

struct Pair {
    emu: Emu,
    a: Node,
    b: Node,
    rx_a: Receiver<Event>,
    rx_b: Receiver<Event>,
    seen_a: Vec<Event>,
    seen_b: Vec<Event>,
    ra: String,
    _dirs: (tempfile::TempDir, tempfile::TempDir),
}

/// Arash and Bahar, one relay each on one emulator, gas started, Arash added Bahar.
fn pair() -> Pair {
    let emu = Emu::start(Duration::from_secs(60));
    let dirs = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(dirs.0.path(), NAME_A);
    let b = new_node(dirs.1.path(), NAME_B);
    let rx_a = a.events();
    let rx_b = b.events();
    let ra = make_relay(&a, &emu, "relay of A", 1);
    make_relay(&b, &emu, "relay of B", 2);
    a.start_gas(cfg(&emu)).unwrap();
    b.start_gas(cfg(&emu)).unwrap();
    let mut p = Pair { emu, a, b, rx_a, rx_b, seen_a: Vec::new(), seen_b: Vec::new(), ra, _dirs: dirs };
    let link_b = p.b.card_link().unwrap();
    p.a.add_contact(&link_b).unwrap();
    // Bahar sees the request, and her answer card reaches Arash: both have each other
    let a_sk = p.a.me().sk;
    wait_for(&p.rx_b, &mut p.seen_b, 20, |e| matches!(e, Event::Contact { peer, by_them: true } if *peer == a_sk).then_some(())).expect("Bahar got Arash's request through the mailbox");
    p
}

fn message_text(rx: &Receiver<Event>, seen: &mut Vec<Event>, secs: u64, text: &str) -> bool {
    wait_for(rx, seen, secs, |e| matches!(e, Event::Message { text: Some(t), .. } if t == text).then_some(())).is_some()
}

#[test]
fn two_nodes_with_no_other_route_talk_through_their_mailboxes() {
    let mut p = pair();
    let (a_sk, b_sk) = (p.a.me().sk, p.b.me().sk);
    let t0 = Instant::now();
    let first = p.a.send_text(&b_sk, CANARY).unwrap();
    assert!(message_text(&p.rx_b, &mut p.seen_b, 20, CANARY), "Bahar got the message");
    eprintln!("a -> b through the mailbox in {:?}", t0.elapsed());
    p.b.send_text(&a_sk, "javab").unwrap();
    assert!(message_text(&p.rx_a, &mut p.seen_a, 20, "javab"), "Arash got the answer");
    // the receipt travelled the same way: Bahar's «delivered» put Arash's message at that state
    let got = wait_for(&p.rx_a, &mut p.seen_a, 20, |e| matches!(e, Event::State { id, state: MsgState::Delivered | MsgState::Read, .. } if *id == first).then_some(()));
    assert!(got.is_some(), "the delivery receipt came back");

    // honesty: a frame left in a mailbox does not make the contact «online»
    let st = p.a.contact_status(&b_sk).unwrap();
    assert!(!st.online || st.via != "gas");

    // the mailbox saw only sealed bytes (canary)
    let mut sealed_all = Vec::new();
    let mut raw_all = Vec::new();
    for body in p.emu.bodies() {
        raw_all.extend_from_slice(&body);
        if let Ok(v) = serde_json::from_slice::<Value>(&body) {
            for it in v.get("i").and_then(Value::as_array).into_iter().flatten() {
                if let Some(d) = it.get(1).and_then(Value::as_str) {
                    sealed_all.extend(URL_SAFE_NO_PAD.decode(d).unwrap_or_default());
                }
            }
        }
    }
    assert!(!sealed_all.is_empty(), "items went through");
    let contains = |hay: &[u8], needle: &[u8]| hay.windows(needle.len()).any(|w| w == needle);
    for (what, needle) in [("text", CANARY.as_bytes()), ("name a", NAME_A.as_bytes()), ("name b", NAME_B.as_bytes()), ("javab", b"javab".as_slice())] {
        assert!(!contains(&raw_all, needle), "{what} is not in the requests");
        assert!(!contains(&sealed_all, needle), "{what} is not in the sealed items");
    }
    for (what, key) in [("sk a", a_sk), ("sk b", b_sk)] {
        assert!(!contains(&sealed_all, &key), "{what} is not in the sealed items");
        assert!(!contains(&raw_all, hex::encode(key).as_bytes()), "{what} is not in the requests");
    }
    // the screens have numbers for it
    let ov = p.a.gas_overview().unwrap();
    assert_eq!(ov.relays.len(), 1);
    let v = &ov.relays[0];
    assert!(v.today.req > 0 && v.today.takes > 0 && v.today.items_in >= 2, "{:?}", v.today);
    assert_eq!(v.state, "ok");
    assert!(v.announce && !v.draft && v.mine);
    assert_eq!(ov.announced.len(), 1);
}

#[test]
fn a_file_goes_through_the_mailboxes_in_alike_16_kib_parts() {
    let mut p = pair();
    let a_sk = p.a.me().sk;
    let dir = tempfile::tempdir().unwrap();
    // a 300 KB photo: five 64 KiB chunks, each cut into 16 KiB parts on the way
    let photo: Vec<u8> = (0..300_000u32).map(|i| (i.wrapping_mul(7) % 251) as u8).collect();
    let src = dir.path().join("photo.jpg");
    std::fs::write(&src, &photo).unwrap();
    let info = mlmchat_core::node::MediaInfo { kind: 1, mime: "image/jpeg".into(), thumb: vec![1, 2, 3], width: 800, height: 600, ..Default::default() };
    p.a.send_media(&p.b.me().sk, &src, &info).unwrap();
    let got = wait_for(&p.rx_b, &mut p.seen_b, 30, |e| matches!(e, Event::Message { peer, kind: "media", .. } if *peer == a_sk).then_some(()));
    assert!(got.is_some(), "the photo's message came");
    let media = p.b.history(&a_sk, 20).unwrap().iter().rev().find_map(|r| match mlmchat_core::envelope::Envelope::from_bytes(&r.envelope).ok()?.body {
        Some(mlmchat_core::envelope::Body::Media(m)) => Some(m),
        _ => None,
    });
    let m = media.expect("a media message");
    let id: [u8; 32] = m.blob_id.as_slice().try_into().unwrap();
    let key: [u8; 32] = m.key.as_slice().try_into().unwrap();
    let whole = wait_for(&p.rx_b, &mut p.seen_b, 60, |e| matches!(e, Event::Blob { id: i, have, total } if *i == id && have == total).then_some(()));
    assert!(whole.is_some(), "the photo came whole through the mailbox
{}", dump_log());
    let out = dir.path().join("got.jpg");
    assert_eq!(p.b.open_media(&id, &key, &out).unwrap(), photo.len() as u64);
    assert_eq!(std::fs::read(&out).unwrap(), photo);
    // no item was bigger than a sealed 16 KiB part
    let mut biggest = 0;
    for body in p.emu.bodies() {
        if let Ok(v) = serde_json::from_slice::<Value>(&body) {
            for it in v.get("i").and_then(Value::as_array).into_iter().flatten() {
                biggest = biggest.max(it.get(1).and_then(Value::as_str).map_or(0, str::len));
            }
        }
    }
    assert!(biggest > 20_000 && biggest < 23_000, "items stay one 16 KiB part each: {biggest} chars");
}

#[test]
fn a_phone_with_no_relay_borrows_a_public_one_and_gives_it_back_when_it_closes() {
    // Arash (A) opens his relay to everyone; Bahar (B) is his contact through the mailboxes only;
    // Cyrus (C) has no relay and knows only Bahar, on the same Wi-Fi (the direct route). The news
    // rides the official feeds' catch-up: A → B (Google) → C (Wi-Fi).
    init_log();
    let emu = Emu::start(Duration::from_secs(60));
    let dirs = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(dirs.0.path(), "arash");
    let b = new_node(dirs.1.path(), "bahar");
    let c = new_node(dirs.2.path(), "cyrus");
    let (_, owner) = mlmchat_core::feed::new_owner_key();
    let feed = mlmchat_core::feed::FeedDescriptor { feed_id: vec![1; 16], broadcast: true, name: "news".into(), owner };
    for n in [&a, &b, &c] {
        n.set_feeds(vec![feed.clone()], &[]).unwrap();
    }
    let ra = make_relay(&a, &emu, "relay of A", 1);
    make_relay(&b, &emu, "relay of B", 2);
    for n in [&a, &b, &c] {
        n.start_gas(cfg(&emu)).unwrap();
    }
    let rx_b = b.events();
    let mut seen_b = Vec::new();
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let a_sk = a.me().sk;
    wait_for(&rx_b, &mut seen_b, 20, |e| matches!(e, Event::Contact { peer, by_them: true } if *peer == a_sk).then_some(())).expect("Bahar got Arash through the mailbox");
    // Bahar and Cyrus on one Wi-Fi
    b.start_direct(loopback(Vec::new())).unwrap();
    c.start_direct(loopback(Vec::new())).unwrap();
    c.add_contact(&b.card_link().unwrap()).unwrap();
    let (b_sk, c_sk) = (b.me().sk, c.me().sk);
    let both = || b.contact(&c_sk).unwrap().is_some() && c.contact(&b_sk).unwrap().is_some();
    let eventually = |secs: u64, f: &dyn Fn() -> bool| (0..secs * 10).any(|_| f() || { std::thread::sleep(Duration::from_millis(100)); false });
    assert!(eventually(20, &both), "Bahar and Cyrus are contacts\n{}", dump_log());
    c.send_text(&b_sk, "salam").unwrap();
    assert!(eventually(20, &|| b.history(&c_sk, 5).unwrap().len() >= 1), "Bahar heard Cyrus");

    // nobody can borrow a relay its owner has not opened
    assert!(c.gas_public_list().unwrap().is_empty());
    // Arash opens it: the script keeps the switch, and Bahar hears of it with the feeds
    a.gas_relay_set_public(&ra, true).unwrap();
    assert!(a.gas_relay_is_public(&ra));
    assert!(eventually(30, &|| !b.gas_public_list().unwrap().is_empty()), "Bahar heard of the public relay\n{}", dump_log());
    b.feed_sync_now();
    assert!(eventually(30, &|| !c.gas_public_list().unwrap().is_empty()), "Cyrus heard of it from Bahar\n{}", dump_log());
    let listed = c.gas_public_list().unwrap();
    assert_eq!((listed[0].rid.as_str(), listed[0].owner.as_str(), listed[0].mine), (ra.as_str(), hex::encode(a_sk).as_str(), false));

    // Cyrus has no relay of his own: he borrows it, and his cards name it
    c.gas_public_tick_now();
    let ov = c.gas_overview().unwrap();
    assert_eq!(ov.relays.len(), 1, "one relay, the borrowed one");
    assert!(c.gas_relay_is_borrowed(&ra) && ov.announced == vec![ra.clone()]);
    // Bahar, with a relay of her own, borrows nothing
    b.gas_public_tick_now();
    assert!(!b.gas_relay_is_borrowed(&ra) && b.gas_overview().unwrap().relays.len() == 1);

    // Arash closes it again: the news comes the same way, and Cyrus gives it back
    a.gas_relay_set_public(&ra, false).unwrap();
    assert!(eventually(30, &|| b.gas_public_list().unwrap().is_empty()), "Bahar heard it closed\n{}", dump_log());
    b.feed_sync_now();
    assert!(eventually(30, &|| c.gas_public_list().unwrap().is_empty()), "Cyrus heard it closed");
    c.gas_public_tick_now();
    assert!(!c.gas_relay_is_borrowed(&ra) && c.gas_overview().unwrap().relays.is_empty(), "given back");

    // paused and closed, opening it again works and ends the pause: the pause used to read as an
    // old script (Ehsan, 25 Oct 2026: «میگه اسکریپت این رله قدیمی هست»)
    a.gas_relay_admin(&ra, Some(true), None).unwrap();
    a.gas_relay_set_public(&ra, true).unwrap();
    let cfg_now = a.gas_relay_admin(&ra, None, None).unwrap();
    assert_eq!((cfg_now["cfg"]["paused"].clone(), cfg_now["cfg"]["pub"].clone()), (Value::Bool(false), Value::Bool(true)));
    for n in [&a, &b, &c] {
        n.stop();
    }
}

#[test]
fn a_frame_the_cache_lost_is_put_again_until_it_is_taken() {
    let mut p = pair();
    let b_sk = p.b.me().sk;
    let a_sk = p.a.me().sk;
    // Arash stops taking; Bahar's message waits in his mailbox, and then the cache loses it
    p.a.stop_gas();
    p.b.send_text(&a_sk, "lost-then-found").unwrap();
    let id = id_of(1);
    let end = Instant::now() + Duration::from_secs(10);
    while p.emu.waiting(&id) == 0 && Instant::now() < end {
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(p.emu.waiting(&id) > 0, "the frame was put in Arash's mailbox");
    p.emu.drop_items();
    assert_eq!(p.emu.waiting(&id), 0);
    // Arash is back; Bahar's check finds the frame gone and puts it again
    p.a.start_gas(cfg(&p.emu)).unwrap();
    assert!(message_text(&p.rx_a, &mut p.seen_a, 25, "lost-then-found"), "the frame came after all");
    // and once it is taken, Bahar stops watching it
    let end = Instant::now() + Duration::from_secs(15);
    while p.b.gas_pending_count() > 0 && Instant::now() < end {
        std::thread::sleep(Duration::from_millis(100));
    }
    assert_eq!(p.b.gas_pending_count(), 0, "nothing left to watch");
    let _ = b_sk;
}

#[test]
fn a_mailbox_that_is_down_is_passed_over_for_the_next() {
    let mut p = pair();
    let (a_sk, b_sk) = (p.a.me().sk, p.b.me().sk);
    // Arash gets a second relay; his next card (two relays) reaches Bahar with this message
    make_relay(&p.a, &p.emu, "second", 3);
    p.a.send_text(&b_sk, "two relays now").unwrap();
    assert!(message_text(&p.rx_b, &mut p.seen_b, 20, "two relays now"));
    // wait until Bahar has the new card (a ping-pong makes sure the update was processed)
    p.b.send_text(&a_sk, "ack").unwrap();
    assert!(message_text(&p.rx_a, &mut p.seen_a, 20, "ack"));
    std::thread::sleep(Duration::from_millis(1500));
    // whichever of the two comes first for Bahar's tag is down in one of the two rounds
    for (round, down) in [(1, 1u32), (2, 3u32)] {
        p.emu.down(&id_of(down), true);
        let text = format!("round {round}");
        p.b.send_text(&a_sk, &text).unwrap();
        assert!(message_text(&p.rx_a, &mut p.seen_a, 45, &text), "delivered with relay {down} down");
        // long enough for Arash's own takes to meet the failure too
        std::thread::sleep(Duration::from_millis(3000));
        p.emu.down(&id_of(down), false);
        std::thread::sleep(Duration::from_millis(500));
    }
    let ov = p.a.gas_overview().unwrap();
    assert_eq!(ov.relays.len(), 2);
    assert_eq!(ov.announced.len(), 2);
    assert!(ov.relays.iter().any(|r| r.total.errors > 0), "the failures are counted");
}

#[test]
fn the_owner_can_pause_a_relay_and_see_who_used_it() {
    let mut p = pair();
    let (a_sk, b_sk) = (p.a.me().sk, p.b.me().sk);
    p.a.send_text(&b_sk, "warm").unwrap();
    assert!(message_text(&p.rx_b, &mut p.seen_b, 20, "warm"));
    // the owner's view of her own script
    let srv = p.a.gas_relay_server_stats(&p.ra).unwrap();
    assert!(srv["data"]["today"]["r"].as_u64().unwrap() > 0);
    // paused: nobody else can leave or take anything; Bahar's message waits, with the reason
    let r = p.a.gas_relay_admin(&p.ra, Some(true), None).unwrap();
    assert_eq!(r["cfg"]["paused"], Value::Bool(true));
    p.b.send_text(&a_sk, "while paused").unwrap();
    let queued = wait_for(&p.rx_b, &mut p.seen_b, 20, |e| matches!(e, Event::Queued { reason, .. } if *reason == "refused").then_some(()));
    assert!(queued.is_some(), "Bahar is told the mailbox refuses");
    // Arash's own phone shows it
    let ov = p.a.gas_overview().unwrap();
    assert_eq!(ov.relays[0].state, "ok", "the owner is never locked out of her own relay");
    // resumed: the message goes
    p.a.gas_relay_admin(&p.ra, Some(false), Some(5)).unwrap();
    p.b.network_changed(); // try the waiting message at once
    assert!(message_text(&p.rx_a, &mut p.seen_a, 40, "while paused"));
}

#[test]
fn a_share_link_adds_the_relay_once_and_never_carries_the_owners_key() {
    let p = pair();
    let link = p.a.gas_relay_share(&p.ra).unwrap();
    assert!(link.starts_with("mlmchat://r/"));
    let owner = {
        let script = p.a.gas_relay_script(&p.ra).unwrap();
        keys_of(&script).1
    };
    let payload = &link["mlmchat://r/".len()..];
    let bytes = URL_SAFE_NO_PAD.decode(payload).unwrap();
    assert!(!bytes.windows(16).any(|w| hex::encode(w) == owner), "the owner key is not in the link");
    let rid = p.b.gas_relay_add_share(&link).unwrap();
    assert_eq!(p.b.gas_relay_add_share(&link).unwrap(), rid, "the same relay is not added twice");
    let ov = p.b.gas_overview().unwrap();
    assert_eq!(ov.relays.len(), 2);
    let added = ov.relays.iter().find(|r| r.rid == rid).unwrap();
    assert!(!added.mine);
    // a friend's relay can be tried, not administered
    assert!(p.b.gas_relay_admin(&rid, Some(true), None).is_err());
    let rep = p.b.gas_relay_test(&rid).unwrap();
    assert!(rep.ok, "{rep:?}");
    assert_eq!(rep.steps.len(), 3);
    // removing it
    p.b.gas_relay_remove(&rid).unwrap();
    assert_eq!(p.b.gas_overview().unwrap().relays.len(), 1);
}

#[test]
fn a_draft_waits_for_its_deployment_and_a_wrong_key_is_named() {
    let dir = tempfile::tempdir().unwrap();
    let n = new_node(dir.path(), "Draft-Dara");
    let emu = Emu::start(Duration::from_secs(60));
    n.start_gas(cfg(&emu)).unwrap();
    let d = n.gas_relay_new("mine").unwrap();
    assert!(d.script.contains("var USE_KEY = \""), "the script has its keys");
    assert!(!d.script.contains("__MLMCHAT_USE_KEY__") && !d.script.contains("__MLMCHAT_OWNER_KEY__"));
    let ov = n.gas_overview().unwrap();
    assert!(ov.relays[0].draft && ov.relays[0].state == "draft");
    assert!(n.gas_relay_test(&d.rid).is_err(), "a draft cannot be tested");
    assert!(n.gas_relay_finish(&d.rid, "too short").is_err());
    // deployed with other keys than the app's (a wrong paste): the script answers like a stranger
    emu.add(&id_of(9), &"0".repeat(32), &"1".repeat(32));
    let rid = n.gas_relay_finish(&d.rid, &id_of(9)).unwrap();
    let rep = n.gas_relay_test(&rid).unwrap();
    assert!(!rep.ok);
    assert_eq!(rep.steps.last().unwrap().step, "script");
    assert_eq!(rep.steps.last().unwrap().code, "auth");
    // a deployment Google does not know
    let d2 = n.gas_relay_new("other").unwrap();
    let rid2 = n.gas_relay_finish(&d2.rid, &id_of(8)).unwrap();
    let rep2 = n.gas_relay_test(&rid2).unwrap();
    assert_eq!(rep2.steps.last().unwrap().code, "not_found");
    // the right deployment pasted over the wrong one: same keys, new id, and it works
    let (u, o) = keys_of(&n.gas_relay_script(&rid2).unwrap());
    emu.add(&id_of(7), &u, &o);
    let rid3 = n.gas_relay_finish(&rid2, &id_of(7)).unwrap();
    assert_ne!(rid3, rid2);
    let rep3 = n.gas_relay_test(&rid3).unwrap();
    assert!(rep3.ok, "{rep3:?}");
    assert_eq!(n.gas_overview().unwrap().relays.len(), 2);
    // a dead relay is left out of the card, a working one is named in it
    assert_eq!(n.gas_overview().unwrap().announced.len(), 1);
    assert!(n.card_link().unwrap().len() > 50);
}
