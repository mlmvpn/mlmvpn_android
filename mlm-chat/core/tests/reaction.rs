//! Reactions (SPEC 5 `Reaction`; Ehsan, 25 Oct 2026: none anywhere before): in a one-to-one chat
//! each side sees the other's, a newer one replaces the older, an empty one takes it back; in a
//! group every member sees them; in a channel a reader, who may not post, still reacts. A
//! reaction is never a message of its own.

mod common;

use common::*;
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
fn reactions_in_a_chat_a_group_and_a_channel() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    let (so, sa) = (o.me().sk, a.me().sk);
    o.add_contact(&a.card_link().unwrap()).unwrap();
    o.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || a.contact(&so).unwrap().is_some() && b.contact(&so).unwrap().is_some()));

    // one to one: Ali reacts to the owner's message
    let rx_o = o.events();
    let mut eo = Vec::new();
    let id = o.send_text(&sa, "salam").unwrap();
    assert!(eventually(20, || a.history(&so, 5).unwrap().iter().any(|m| m.id == id)), "the message arrived\n{}", dump_log());
    a.react(&so, &id, "👍").unwrap();
    let got = wait_for(&rx_o, &mut eo, 20, |e| matches!(e, Event::Reaction { group: false, id: i, peer, .. } if *i == id && *peer == sa).then_some(()));
    assert!(got.is_some(), "the owner hears it: {eo:?}\n{}", dump_log());
    assert_eq!(o.reactions(&sa).unwrap(), vec![(id, sa, "👍".to_string())]);
    assert_eq!(a.reactions(&so).unwrap(), vec![(id, sa, "👍".to_string())], "kept on Ali's phone too");
    // not a message of its own, on either side
    assert_eq!(o.history(&sa, 10).unwrap().len(), 1);
    assert_eq!(a.history(&so, 10).unwrap().len(), 1);
    // a newer one replaces it, an empty one takes it back
    std::thread::sleep(Duration::from_millis(5));
    a.react(&so, &id, "❤").unwrap();
    assert!(eventually(20, || o.reactions(&sa).unwrap() == vec![(id, sa, "❤".to_string())]));
    std::thread::sleep(Duration::from_millis(5));
    a.react(&so, &id, "").unwrap();
    assert!(eventually(20, || o.reactions(&sa).unwrap().is_empty()), "taken back");

    // a group: Sara sees Ali's reaction to the owner's post (Ali and Sara are not contacts)
    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &sa).unwrap();
    o.group_add(&gid, &b.me().sk).unwrap();
    let all_three = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3);
    assert!(eventually(30, || all_three(&a) && all_three(&b)), "every member sees three\n{}", dump_log());
    let post = o.group_send_text(&gid, "salam be hame").unwrap();
    assert!(eventually(30, || a.group_messages(&gid, 5).unwrap().iter().any(|m| m.id == post)));
    a.group_react(&gid, &post, "🔥").unwrap();
    assert!(eventually(30, || b.group_reactions(&gid).unwrap() == vec![(post, sa, "🔥".to_string())]), "Sara sees it\n{}", dump_log());
    assert!(eventually(30, || o.group_reactions(&gid).unwrap() == vec![(post, sa, "🔥".to_string())]));
    assert_eq!(b.group_messages(&gid, 10).unwrap().len(), 1, "no message of its own");

    // a channel: a reader may not post, and still reacts
    let cid = o.group_create("akhbar", "", true).unwrap();
    o.group_add(&cid, &sa).unwrap();
    assert!(eventually(30, || a.group_state(&cid).unwrap().is_some()), "Ali joins the channel");
    let news = o.group_send_text(&cid, "khabar").unwrap();
    assert!(eventually(30, || a.group_messages(&cid, 5).unwrap().iter().any(|m| m.id == news)));
    assert!(a.group_send_text(&cid, "man ham").is_err(), "a reader does not post");
    a.group_react(&cid, &news, "👏").unwrap();
    assert!(eventually(30, || o.group_reactions(&cid).unwrap() == vec![(news, sa, "👏".to_string())]), "the owner sees the reader's\n{}", dump_log());

    for n in [&o, &a, &b] {
        n.stop();
    }
}
