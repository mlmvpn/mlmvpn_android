//! Many routes at once (Ehsan, 26 Oct 2026: «برای تقسیم بار از همه راه ها هم زمان استفاده کنه
//! مخصوصا برای ارسال فایل ها … هرکدوم از راه ها هم بسته شد … همون لحظه راه جایگزین در جریانه …
//! اگر کاربر ۲ یا ۱۰ تا رله گوگل داشت … از همشون استفاده کنه»). Covers: a contact's Google relays
//! all carry a share of a file; a file goes over the direct route and the mailbox at once; when
//! the direct route closes, the next message goes by Nostr at once.

mod common;

use common::gas_emu::Emu;
use common::nostr_emu::Relay;
use common::*;
use mlmchat_core::node::{Event, MediaInfo, Node};
use mlmchat_core::store::MsgState;
use mlmchat_core::transport::gas::http::Endpoint;
use mlmchat_core::transport::gas::GasConfig;
use mlmchat_core::transport::nostr::transport::NostrConfig;
use mlmchat_core::transport::scope::NetScope;
use std::time::{Duration, Instant};

fn gas_cfg(emu: &Emu) -> GasConfig {
    GasConfig {
        endpoint: Some(Endpoint::Plain(emu.addr)),
        wait_ms: 1500,
        idle_soon: Duration::from_millis(300),
        idle_late: Duration::from_millis(300),
        request_timeout: Duration::from_secs(5),
        pending_tick: Duration::from_millis(200),
        pending_first: Duration::from_millis(400),
        ..Default::default()
    }
}

fn id_of(n: u32) -> String {
    format!("AKfycbEmu{n:03}abcdefghijklmnopqrstuvwxyz0123456789")
}

/// A relay on `emu` for `node`, numbered `n`.
fn make_relay(node: &Node, emu: &Emu, n: u32) {
    let d = node.gas_relay_new(&format!("relay {n}")).unwrap();
    let pick = |name: &str| {
        let k = format!("var {name} = \"");
        let i = d.script.find(&k).expect("key in script") + k.len();
        d.script[i..i + 32].to_string()
    };
    emu.add(&id_of(n), &pick("USE_KEY"), &pick("OWNER_KEY"));
    node.gas_relay_finish(&d.rid, &format!("https://script.google.com/macros/s/{}/exec", id_of(n))).unwrap();
}

fn eventually(secs: u64, mut f: impl FnMut() -> bool) -> bool {
    let end = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < end {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    false
}

/// A file of `n` bytes in `dir`, and what it is.
fn file(dir: &std::path::Path, n: u32) -> std::path::PathBuf {
    let bytes: Vec<u8> = (0..n).map(|i| (i.wrapping_mul(17) % 251) as u8).collect();
    let p = dir.join("file.bin");
    std::fs::write(&p, bytes).unwrap();
    p
}

/// The blob id of the last file `from` sent `to`, as `to` has it.
fn blob_from(to: &Node, from: &[u8; 32]) -> Option<[u8; 32]> {
    use mlmchat_core::envelope::{Body, Envelope};
    to.history(from, 20).ok()?.iter().rev().find_map(|r| match Envelope::from_bytes(&r.envelope).ok()?.body {
        Some(Body::Media(m)) => m.blob_id.as_slice().try_into().ok(),
        _ => None,
    })
}

fn whole(n: &Node, id: &[u8; 32]) -> bool {
    n.media_status(id).ok().flatten().is_some_and(|(have, total, done)| have == total && done)
}

#[test]
fn every_google_relay_of_a_contact_carries_a_share() {
    init_log();
    let emu = Emu::start(Duration::from_secs(60));
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "arash");
    let b = new_node(db.path(), "bahar");
    make_relay(&a, &emu, 1);
    for n in 2..=4 {
        make_relay(&b, &emu, n);
    }
    a.start_gas(gas_cfg(&emu)).unwrap();
    b.start_gas(gas_cfg(&emu)).unwrap();
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let sa = a.me().sk;
    assert!(eventually(30, || b.contact(&sa).unwrap().is_some() && a.contact(&b.me().sk).unwrap().is_some_and(|c| c.loc.is_some())), "contacts through the mailboxes\n{}", dump_log());
    let before: Vec<u64> = (2..=4).map(|n| emu.puts(&id_of(n))).collect();
    // ten chunks of a 600 KB file, and a few messages
    a.send_media(&b.me().sk, &file(da.path(), 600_000), &MediaInfo { kind: 0, name: "f.bin".into(), ..Default::default() }).unwrap();
    for k in 0..4 {
        a.send_text(&b.me().sk, &format!("payam {k}")).unwrap();
    }
    assert!(eventually(30, || blob_from(&b, &sa).is_some()), "the file's message came");
    let id = blob_from(&b, &sa).unwrap();
    assert!(eventually(90, || whole(&b, &id)), "the file came whole\n{}", dump_log());
    let got: Vec<u64> = (2..=4).map(|n| emu.puts(&id_of(n)) - before[n as usize - 2]).collect();
    eprintln!("puts per relay of Bahar: {got:?}");
    assert!(got.iter().all(|p| *p > 0), "each of Bahar's three relays took a share: {got:?}");
    a.stop();
    b.stop();
}

#[test]
fn a_file_goes_over_the_direct_route_and_the_mailbox_at_once() {
    init_log();
    let emu = Emu::start(Duration::from_secs(60));
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "arash");
    let b = new_node(db.path(), "bahar");
    make_relay(&a, &emu, 1);
    make_relay(&b, &emu, 2);
    for n in [&a, &b] {
        n.start_direct(loopback(Vec::new())).unwrap();
        n.start_gas(gas_cfg(&emu)).unwrap();
    }
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let sa = a.me().sk;
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some() && a.contact(&b.me().sk).unwrap().is_some_and(|c| c.loc.is_some())));
    let before = emu.puts(&id_of(2));
    a.send_media(&b.me().sk, &file(da.path(), 900_000), &MediaInfo { kind: 0, name: "f.bin".into(), ..Default::default() }).unwrap();
    assert!(eventually(20, || blob_from(&b, &sa).is_some()));
    let id = blob_from(&b, &sa).unwrap();
    assert!(eventually(60, || whole(&b, &id)), "the file came whole\n{}", dump_log());
    let by_mailbox = emu.puts(&id_of(2)) - before;
    eprintln!("puts to Bahar's mailbox during the file: {by_mailbox}");
    assert!(by_mailbox > 0, "the mailbox carried part of the file beside the direct route");
    a.stop();
    b.stop();
}

#[test]
fn when_the_direct_route_closes_nostr_carries_at_once() {
    init_log();
    let r = Relay::start();
    let ncfg = NostrConfig { relays: vec![r.url()], keep: 2, connect_timeout: Duration::from_secs(5), ok_timeout: Duration::from_secs(8), rest: Duration::from_secs(5), own: Vec::new() };
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "arash");
    let b = new_node(db.path(), "bahar");
    for n in [&a, &b] {
        n.start_direct(loopback(Vec::new())).unwrap();
        n.start_nostr(ncfg.clone()).unwrap();
    }
    // the cards name the relay once it is open
    assert!(eventually(20, || [&a, &b].iter().all(|n| n.nostr_relays().iter().any(|x| x.state == "open"))));
    let rx_b = b.events();
    let mut eb = Vec::new();
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let (sa, sb) = (a.me().sk, b.me().sk);
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some() && a.contact(&sb).unwrap().is_some_and(|c| c.loc.is_some())));
    a.send_text(&sb, "aval").unwrap();
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Message { text: Some(t), .. } if t == "aval").then_some(())).is_some());
    // the direct route closes (Arash may reach nothing on loopback any more)
    a.set_scope(Some(NetScope::parse(["10.0.0.0/8"])));
    let t0 = Instant::now();
    a.send_text(&sb, "dovom").unwrap();
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Message { text: Some(t), .. } if t == "dovom").then_some(())).is_some(), "the message went another way\n{}", dump_log());
    eprintln!("after the direct route closed: {:?}", t0.elapsed());
    assert!(t0.elapsed() < Duration::from_secs(15), "at once, not after the queue's waits");
    a.stop();
    b.stop();
}

/// A listener that takes every connection and never answers: a route that stalls, the way an
/// address whose packets the network drops looks.
fn tarpit() -> std::net::SocketAddr {
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = l.local_addr().unwrap();
    std::thread::spawn(move || {
        let mut held = Vec::new();
        for s in l.incoming().flatten() {
            held.push(s);
        }
    });
    addr
}

#[test]
fn to_a_contact_whose_phone_is_off_the_mailbox_takes_it_at_once() {
    // Ehsan, 27 Oct 2026: «وقتی کاربر مقابل آفلاینه پیام ارسال میکنم ساعت میاد … بهتره یک تیک
    // بخوره … مگر اینکه واقعا ارتباط من مشکل داشته باشه». Each card names an address that takes
    // the connection and never answers, so neither phone ever reaches the other itself, and Nostr
    // carries everything. The stall outlasts the test, so it never counts as a failure that sets
    // the route aside: the route to the phone stays first.
    init_log();
    let r = Relay::start();
    let ncfg = NostrConfig { relays: vec![r.url()], keep: 2, connect_timeout: Duration::from_secs(5), ok_timeout: Duration::from_secs(8), rest: Duration::from_secs(5), own: Vec::new() };
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "arash");
    let b = new_node(db.path(), "bahar");
    for n in [&a, &b] {
        n.start_direct(mlmchat_core::transport::direct::DirectConfig { io_timeout: Duration::from_secs(120), ..loopback(vec![tarpit()]) }).unwrap();
        n.start_nostr(ncfg.clone()).unwrap();
    }
    assert!(eventually(20, || [&a, &b].iter().all(|n| n.nostr_relays().iter().any(|x| x.state == "open"))));
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let (sa, sb) = (a.me().sk, b.me().sk);
    assert!(eventually(30, || b.contact(&sa).unwrap().is_some() && a.contact(&sb).unwrap().is_some_and(|c| c.loc.is_some())), "contacts through Nostr\n{}", dump_log());
    // Bahar's phone goes off
    b.stop();
    let rx_a = a.events();
    let mut ea = Vec::new();
    let t0 = Instant::now();
    let id = a.send_text(&sb, "salam").unwrap();
    let sent = wait_for(&rx_a, &mut ea, 20, |e| matches!(e, Event::State { id: i, state: MsgState::Sent, .. } if *i == id).then(|| t0.elapsed()));
    let sent = sent.unwrap_or_else(|| panic!("a mailbox took it\n{}", dump_log()));
    eprintln!("to a phone that is off: in a mailbox after {sent:?}");
    // before, the stalled route to her phone held it 2.5 s before the mailbox even began
    assert!(sent < Duration::from_millis(2000), "the mailbox at once, beside the route that stalls ({sent:?})\n{}", dump_log());
    // when no mailbox takes it, it stays queued: the clock is honest
    r.refuse_writes(true);
    let id2 = a.send_text(&sb, "dovom").unwrap();
    assert!(wait_for(&rx_a, &mut ea, 6, |e| matches!(e, Event::State { id: i, .. } if *i == id2).then_some(())).is_none(), "no check while nothing took it");
    assert!(a.history(&sb, 10).unwrap().iter().any(|m| m.id == id2 && m.state == MsgState::Queued), "still queued\n{}", dump_log());
    a.stop();
}
