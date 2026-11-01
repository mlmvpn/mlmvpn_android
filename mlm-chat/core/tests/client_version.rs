//! The app a message came from (SPEC 5 `Client`, T75; Ehsan, 31 Oct 2026: «هرکس نسخه قدیمی داره
//! توی گروه به بقیه و خودش نشون داده بشه»): every envelope from draft 28 on says which app and
//! version sent it, and each phone keeps every sender's newest, telling the app when it changes. A
//! message with no app is from a phone before draft 28. A phone's app is one for the whole process,
//! so this file holds a single test.

mod common;

use common::*;
use mlmchat_core::envelope::set_client;
use mlmchat_core::node::{Event, Node};
use std::time::Duration;

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

#[test]
fn every_message_says_its_app_and_the_newest_is_kept() {
    init_log();
    // both phones from before draft 28: no app on what they send
    set_client(None);
    let (d1, d2) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(d1.path(), "ali");
    let b = up(d2.path(), "sara");
    let (sa, sb) = (a.me().sk, b.me().sk);
    let rx_b = b.events();
    let mut seen = Vec::new();
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some()), "Sara has Ali\n{}", dump_log());
    let first = a.send_text(&sb, "salam").unwrap();
    assert!(eventually(20, || b.history(&sa, 5).unwrap().iter().any(|m| m.id == first)), "first message");
    let ali = b.clients_since(0).unwrap().into_iter().find(|c| c.sk == sa).expect("Ali's app is known");
    assert!(ali.app.is_empty() && ali.name.is_empty(), "a message with no app: a phone from before");
    // the first word of anyone is news too
    assert!(wait_for(&rx_b, &mut seen, 20, |e| matches!(e, Event::Client { peer } if *peer == sa).then_some(())).is_some(), "told of Ali's first app");

    // Ali's app is updated: its next message says so, and Sara's phone tells its app
    seen.clear();
    a.set_client("com.mlmvpn.scanner", 81, "1.2.45");
    let second = a.send_text(&sb, "be-ruz shodam").unwrap();
    assert!(wait_for(&rx_b, &mut seen, 20, |e| matches!(e, Event::Client { peer } if *peer == sa).then_some(())).is_some(), "told of Ali's new app\n{}", dump_log());
    assert!(eventually(20, || b.history(&sa, 5).unwrap().iter().any(|m| m.id == second)), "second message");
    let ali = b.clients_since(0).unwrap().into_iter().find(|c| c.sk == sa).unwrap();
    assert_eq!((ali.app.as_str(), ali.code, ali.name.as_str()), ("com.mlmvpn.scanner", 81, "1.2.45"));
    let changed_at = ali.at_ms;
    assert!(b.clients_since(changed_at + 1).unwrap().iter().all(|c| c.sk != sa), "nothing of Ali changed after");

    // the same app again: kept, nothing to tell
    seen.clear();
    let third = a.send_text(&sb, "dobare").unwrap();
    assert!(eventually(20, || b.history(&sa, 5).unwrap().iter().any(|m| m.id == third)), "third message");
    std::thread::sleep(Duration::from_millis(500));
    while let Ok(e) = rx_b.try_recv() {
        seen.push(e);
    }
    assert!(!seen.iter().any(|e| matches!(e, Event::Client { peer } if *peer == sa)), "no change, no word");
    assert_eq!(b.clients_since(0).unwrap().into_iter().find(|c| c.sk == sa).unwrap().at_ms, changed_at);

    // Ali's phone knows Sara's app the same way (her receipts carry it too)
    assert!(eventually(20, || a.clients_since(0).unwrap().iter().any(|c| c.sk == sb && c.name == "1.2.45")), "Ali has Sara's app\n{}", dump_log());
    set_client(None);
    a.stop();
    b.stop();
}
