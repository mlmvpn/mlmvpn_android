//! «Add contact» on a group member's profile: the owner knows both others, they do not know each
//! other; one adds the other from the group, who then shows in their contact list and gets one-to-
//! one messages. Before, the bridge answered Telegram's request with an error that Telegram drops,
//! after its screen had already said «saved» (Dr.Net's owner, 26 Oct 2026, both apps).

mod common;

use common::*;
use mlmchat_core::node::{Event, Node};
use std::time::Duration;

fn up(dir: &std::path::Path, name: &str) -> Node {
    let n = new_node(dir, name);
    n.start_direct(loopback(Vec::new())).unwrap();
    n
}

#[test]
fn a_group_member_becomes_a_contact() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    let (a_sk, b_sk) = (a.me().sk, b.me().sk);
    // nobody's card yet: refused plainly, not «saved»
    assert!(a.add_member_contact(&b_sk).is_err(), "no group yet, no card");
    assert!(a.add_member_contact(&a_sk).is_err(), "not oneself");

    o.add_contact(&a.card_link().unwrap()).unwrap();
    o.add_contact(&b.card_link().unwrap()).unwrap();
    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &a_sk).unwrap();
    o.group_add(&gid, &b_sk).unwrap();
    // Sara speaks in the group, so Ali has her own card from the group's log
    let mut said = None;
    for _ in 0..40 {
        if b.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3) {
            b.group_send_text(&gid, "man sara hastam").unwrap();
            said = wait_for(&rx_a, &mut ea, 3, |e| match e {
                Event::GroupMessage { gid: g, text: Some(t), .. } if *g == gid && t == "man sara hastam" => Some(()),
                _ => None,
            });
            if said.is_some() {
                break;
            }
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    assert!(said.is_some(), "Ali hears Sara in the group: {ea:?}\n{}", dump_log());
    assert!(a.contacts().unwrap().iter().all(|c| c.sk != b_sk), "a member is not a contact by itself");

    let c = a.add_member_contact(&b_sk).unwrap();
    assert_eq!(c.sk, b_sk);
    assert_eq!(c.name, "sara");
    assert!(a.contacts().unwrap().iter().any(|c| c.sk == b_sk), "Sara is in Ali's contact list now");
    assert!(wait_for(&rx_a, &mut ea, 1, |e| matches!(e, Event::Contact { peer, by_them: false } if *peer == b_sk).then_some(())).is_some());
    // again: the same contact, no error
    assert_eq!(a.add_member_contact(&b_sk).unwrap().sk, b_sk);

    // and a one-to-one message reaches her
    a.send_text(&b_sk, "salam sara, az goruh").unwrap();
    let got = wait_for(&rx_b, &mut eb, 30, |e| match e {
        Event::Message { peer, text: Some(t), .. } if *peer == a_sk => Some(t.clone()),
        _ => None,
    });
    assert_eq!(got.as_deref(), Some("salam sara, az goruh"), "Sara: {eb:?}\n{}", dump_log());
    // adding stays one-sided, as in Telegram: Ali is still not in Sara's list
    assert!(b.contacts().unwrap().iter().all(|c| c.sk != a_sk));
    o.stop();
    a.stop();
    b.stop();
}
