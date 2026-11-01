//! The combined Google + Cloudflare relay (SPEC 19.13): one mailbox (the owner's Worker) with two
//! doors (the Worker straight, and a Google script that hands everything to it). On the emulator,
//! the Worker and its Google door are the same mailbox, as in life. Covers: making one (Worker,
//! then door), what the card says (key 14, not key 12), messages both ways with the Worker reached
//! straight, a network where Cloudflare does not answer (the Google door carries it all), the
//! relay test's steps, and passing it on by a link.

mod common;

use common::gas_emu::Emu;
use common::*;
use mlmchat_core::card::Card;
use mlmchat_core::node::{Event, Node};
use mlmchat_core::transport::gas::http::Endpoint;
use mlmchat_core::transport::gas::GasConfig;
use std::sync::mpsc::Receiver;
use std::time::Duration;

const HOST: &str = "gereh-box-7a1c.example.workers.dev";

fn cfg(emu: &Emu, worker: Option<Endpoint>) -> GasConfig {
    GasConfig {
        endpoint: Some(Endpoint::Plain(emu.addr)),
        wait_ms: 1500,
        idle_soon: Duration::from_millis(300),
        idle_late: Duration::from_millis(300),
        request_timeout: Duration::from_secs(5),
        pending_tick: Duration::from_millis(200),
        pending_first: Duration::from_millis(400),
        worker_endpoint: worker,
        ..Default::default()
    }
}

fn keys_in(script: &str, form: &str) -> (String, String) {
    let pick = |name: &str| {
        let k = format!("{form} {name} = \"");
        let i = script.find(&k).expect("key in script") + k.len();
        script[i..i + 32].to_string()
    };
    (pick("USE_KEY"), pick("OWNER_KEY"))
}

fn door_id(n: u32) -> String {
    format!("AKfycbDoor{n:03}abcdefghijklmnopqrstuvwxyz0123456789")
}

/// A combined relay for `node`, the way the screens make it: the Worker (its keys go to the
/// emulator), its address back, the Google door's script, the door's deployment back.
fn make_combined(node: &Node, emu: &Emu, n: u32) -> String {
    let d = node.gas_combo_new("ترکیبی").unwrap();
    let (u, o) = keys_in(&d.worker_script, "const");
    assert!(node.gas_relay_script(&d.rid).is_err(), "no door before the Worker");
    let door = node.gas_combo_worker(&d.rid, &format!("https://{HOST}/")).unwrap();
    assert!(door.contains(&format!("https://{HOST}/")), "the door names the Worker");
    assert_eq!(node.gas_relay_script(&d.rid).unwrap(), door);
    emu.add(&door_id(n), &u, &o);
    emu.add_worker(HOST, &door_id(n));
    node.gas_relay_finish(&d.rid, &format!("https://script.google.com/macros/s/{}/exec", door_id(n))).unwrap()
}

fn make_simple(node: &Node, emu: &Emu, n: u32) -> String {
    let d = node.gas_relay_new("ساده").unwrap();
    let (u, o) = keys_in(&d.script, "var");
    let id = format!("AKfycbEmu{n:03}abcdefghijklmnopqrstuvwxyz0123456789");
    emu.add(&id, &u, &o);
    node.gas_relay_finish(&d.rid, &id).unwrap()
}

fn text_came(rx: &Receiver<Event>, seen: &mut Vec<Event>, secs: u64, text: &str) -> bool {
    wait_for(rx, seen, secs, |e| matches!(e, Event::Message { text: Some(t), .. } if t == text).then_some(())).is_some()
}

#[test]
fn a_combined_relay_carries_messages_through_its_worker_straight() {
    init_log();
    let emu = Emu::start(Duration::from_secs(60));
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "Arash-c");
    let b = new_node(db.path(), "Bahar-c");
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    let ra = make_combined(&a, &emu, 1);
    make_simple(&b, &emu, 2);
    let worker = Some(Endpoint::Plain(emu.addr));
    a.start_gas(cfg(&emu, worker.clone())).unwrap();
    b.start_gas(cfg(&emu, worker)).unwrap();

    // Arash's card names the combined relay under key 14, nothing under key 12
    let card = Card::from_link(&a.card_link().unwrap()).unwrap();
    assert_eq!(card.relays2.len(), 1, "key 14");
    assert!(card.relays.is_empty(), "key 12 stays as older cores know it");

    // the relay test: the Worker straight, the Google door, a round trip
    let rep = a.gas_relay_test(&ra).unwrap();
    let steps: Vec<(&str, bool)> = rep.steps.iter().map(|s| (s.step, s.ok)).collect();
    assert_eq!(steps, vec![("worker", true), ("front", true), ("script", true), ("mailbox", true)], "{rep:?}");
    assert!(rep.ok && rep.worker_ok && rep.owner);

    let (sa, sb) = (a.me().sk, b.me().sk);
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Contact { peer, by_them: true } if *peer == sa).then_some(())).is_some(), "Bahar got the request");
    let hits = emu.worker_hits();
    b.send_text(&sa, "az rah-e worker").unwrap();
    assert!(text_came(&rx_a, &mut ea, 20, "az rah-e worker"), "Arash got it\n{}", dump_log());
    assert!(emu.worker_hits() > hits, "it went through the Worker straight");
    a.send_text(&sb, "javab").unwrap();
    assert!(text_came(&rx_b, &mut eb, 20, "javab"));

    let ov = a.gas_overview().unwrap();
    let v = ov.relays.iter().find(|r| r.rid == ra).unwrap();
    assert!(v.combined && v.worker == HOST && !v.draft, "{v:?}");
    assert_eq!(v.door, "cloudflare");

    // passed on by a link: the Worker goes with it
    let link = a.gas_relay_share(&ra).unwrap();
    let dc = tempfile::tempdir().unwrap();
    let c = new_node(dc.path(), "Cyrus-c");
    let rc = c.gas_relay_add_share(&link).unwrap();
    let row = c.gas_overview().unwrap().relays.into_iter().find(|r| r.rid == rc).unwrap();
    assert!(row.combined && row.worker == HOST && !row.mine);
    a.stop();
    b.stop();
}

#[test]
fn where_cloudflare_does_not_answer_the_google_door_carries_everything() {
    init_log();
    let emu = Emu::start(Duration::from_secs(60));
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "Arash-g");
    let b = new_node(db.path(), "Bahar-g");
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    let ra = make_combined(&a, &emu, 3);
    make_simple(&b, &emu, 4);
    // a closed port stands for a network where Cloudflare is cut off
    let closed = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap();
    a.start_gas(cfg(&emu, Some(Endpoint::Plain(closed)))).unwrap();
    b.start_gas(cfg(&emu, Some(Endpoint::Plain(closed)))).unwrap();

    let rep = a.gas_relay_test(&ra).unwrap();
    let steps: Vec<(&str, bool)> = rep.steps.iter().map(|s| (s.step, s.ok)).collect();
    assert_eq!(steps, vec![("worker", false), ("front", true), ("script", true), ("mailbox", true)], "{rep:?}");
    assert!(rep.ok && !rep.worker_ok, "one door is enough");

    let sa = a.me().sk;
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Contact { peer, by_them: true } if *peer == sa).then_some(())).is_some());
    b.send_text(&sa, "az dar-e google").unwrap();
    assert!(text_came(&rx_a, &mut ea, 20, "az dar-e google"), "through the Google door\n{}", dump_log());
    assert_eq!(emu.worker_hits(), 0, "nothing reached the Worker straight");
    let v = a.gas_overview().unwrap().relays.into_iter().find(|r| r.rid == ra).unwrap();
    assert_eq!(v.state, "ok", "a relay whose Google door works is not passed over");
    assert_eq!(v.door, "google");
    a.stop();
    b.stop();
}

#[test]
fn a_combined_draft_is_not_named_anywhere_until_both_doors_are_made() {
    let dir = tempfile::tempdir().unwrap();
    let a = new_node(dir.path(), "Arash-d");
    let d = a.gas_combo_new("x").unwrap();
    assert!(Card::from_link(&a.card_link().unwrap()).unwrap().relays2.is_empty());
    assert!(a.gas_relay_finish(&d.rid, &door_id(9)).is_err(), "no door before its Worker");
    assert!(a.gas_combo_worker(&d.rid, "not a host").is_err());
    a.gas_combo_worker(&d.rid, HOST).unwrap();
    assert!(Card::from_link(&a.card_link().unwrap()).unwrap().relays2.is_empty(), "the door is not deployed yet");
    let v = a.gas_overview().unwrap().relays.into_iter().find(|r| r.rid == d.rid).unwrap();
    assert!(v.draft && v.combined);
    // a simple relay's draft has no Worker to set
    let s = a.gas_relay_new("s").unwrap();
    assert!(a.gas_combo_worker(&s.rid, HOST).is_err());
}
