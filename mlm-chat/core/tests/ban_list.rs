//! The list of the banned (Dr.Net's owner, 27 Oct 2026: «لیست بن شدگان تو گروه نشون بده»): every
//! phone builds the same list from the group's log, each ban with the card the member had when
//! banned, so the app can name them; a removal without a ban is not on it, and a ban keeps them out.

mod common;

use common::*;
use mlmchat_core::card::Card;
use mlmchat_core::node::Node;
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
fn every_phone_lists_the_banned_by_their_card() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    let (so, sa, sb) = (o.me().sk, a.me().sk, b.me().sk);
    o.add_contact(&a.card_link().unwrap()).unwrap();
    o.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || a.contact(&so).unwrap().is_some() && b.contact(&so).unwrap().is_some()));
    let cid = o.group_create("akhbar", "", true).unwrap();
    o.group_add(&cid, &sa).unwrap();
    o.group_add(&cid, &sb).unwrap();
    let three = |n: &Node| n.group_state(&cid).unwrap().is_some_and(|s| s.members.len() == 3);
    assert!(eventually(30, || three(&a) && three(&b)), "every member sees three\n{}", dump_log());

    // Ali is banned: listed on the owner's phone, named by his own card
    let st = o.group_remove(&cid, &sa, true).unwrap();
    assert_eq!(st.banned.get(&sa), Some(&u64::MAX));
    let card = st.banned_cards.get(&sa).and_then(|c| Card::from_bytes(c).ok()).expect("the card he had is kept");
    assert_eq!(card.sk, sa);
    // ... and on Sara's, built from the same log
    let listed = |n: &Node| n.group_state(&cid).unwrap().is_some_and(|s| s.banned.get(&sa) == Some(&u64::MAX) && s.banned_cards.contains_key(&sa));
    assert!(eventually(30, || listed(&b)), "Sara's phone lists him too\n{}", dump_log());

    // a removal without a ban is not on the list, and she may come back
    let st = o.group_remove(&cid, &sb, false).unwrap();
    assert!(!st.is_member(&sb) && !st.banned.contains_key(&sb) && !st.banned_cards.contains_key(&sb));
    assert!(o.group_add(&cid, &sb).is_ok(), "added back");
    // the ban keeps Ali out while it stands
    assert!(o.group_add(&cid, &sa).is_err(), "a banned member is not added back");
    assert!(o.group_state(&cid).unwrap().unwrap().banned.contains_key(&sa));

    for n in [&o, &a, &b] {
        n.stop();
    }
}
