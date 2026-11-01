//! Groups and channels end to end (SPEC 7, phase 5): three nodes on loopback. The owner knows
//! both others; they do not know each other, and still talk inside the group (each member reaches
//! each member from the card in the group's log). A channel lets only its admins post. Someone
//! removed does not read what follows.

mod common;

use common::*;
use mlmchat_core::node::{Event, Node};
use std::sync::mpsc::Receiver;

fn up(dir: &std::path::Path, name: &str) -> Node {
    let n = new_node(dir, name);
    n.start_direct(loopback(Vec::new())).unwrap();
    n
}

fn group_text(rx: &Receiver<Event>, seen: &mut Vec<Event>, gid: &[u8], secs: u64) -> Option<String> {
    wait_for(rx, seen, secs, |e| match e {
        Event::GroupMessage { gid: g, text, .. } if g == gid => text.clone(),
        _ => None,
    })
}

fn joined(rx: &Receiver<Event>, seen: &mut Vec<Event>, gid: &[u8], secs: u64) -> bool {
    wait_for(rx, seen, secs, |e| matches!(e, Event::Group { gid: g } if g == gid).then_some(())).is_some()
}

#[test]
fn three_people_talk_in_a_group() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    let (rx_a, rx_b, rx_o) = (a.events(), b.events(), o.events());
    let (mut ea, mut eb, mut eo) = (Vec::new(), Vec::new(), Vec::new());
    o.add_contact(&a.card_link().unwrap()).unwrap();
    o.add_contact(&b.card_link().unwrap()).unwrap();

    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &a.me().sk).unwrap();
    o.group_add(&gid, &b.me().sk).unwrap();
    assert!(joined(&rx_a, &mut ea, &gid, 20), "A joins: {ea:?}");
    assert!(joined(&rx_b, &mut eb, &gid, 20), "B joins: {eb:?}");
    // both see the whole group
    let mut ok = false;
    for _ in 0..100 {
        if a.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3) && b.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3) {
            ok = true;
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    assert!(ok, "every member sees three members");
    assert_eq!(b.group_state(&gid).unwrap().unwrap().name, "doostan");

    o.group_send_text(&gid, "salam be hame").unwrap();
    assert_eq!(group_text(&rx_a, &mut ea, &gid, 20).as_deref(), Some("salam be hame"));
    assert_eq!(group_text(&rx_b, &mut eb, &gid, 20).as_deref(), Some("salam be hame"));

    // A and B are not contacts, and still reach each other through the group
    ea.clear();
    eb.clear();
    a.group_send_text(&gid, "man ali hastam").unwrap();
    assert_eq!(group_text(&rx_b, &mut eb, &gid, 30).as_deref(), Some("man ali hastam"), "B: {eb:?}
{}", dump_log());
    assert_eq!(group_text(&rx_o, &mut eo, &gid, 30).as_deref(), Some("man ali hastam"));
    assert!(a.contacts().unwrap().iter().all(|c| c.sk != b.me().sk), "group members stay out of the contact list");
    assert_eq!(b.group_messages(&gid, 10).unwrap().len(), 2);
    o.stop();
    a.stop();
    b.stop();
}

#[test]
fn a_channel_and_someone_removed() {
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    let (rx_a, rx_b) = (a.events(), b.events());
    let (mut ea, mut eb) = (Vec::new(), Vec::new());
    o.add_contact(&a.card_link().unwrap()).unwrap();
    o.add_contact(&b.card_link().unwrap()).unwrap();

    let cid = o.group_create("akhbar", "", true).unwrap();
    o.group_add(&cid, &a.me().sk).unwrap();
    o.group_add(&cid, &b.me().sk).unwrap();
    assert!(joined(&rx_a, &mut ea, &cid, 20));
    assert!(joined(&rx_b, &mut eb, &cid, 20));
    for _ in 0..100 {
        if a.group_state(&cid).unwrap().is_some_and(|s| s.members.len() == 3) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    assert!(a.group_send_text(&cid, "man ham").is_err(), "a reader does not post in a channel");
    o.group_send_text(&cid, "khabar-e aval").unwrap();
    assert_eq!(group_text(&rx_a, &mut ea, &cid, 20).as_deref(), Some("khabar-e aval"));
    assert_eq!(group_text(&rx_b, &mut eb, &cid, 20).as_deref(), Some("khabar-e aval"));

    // B is removed: B learns it, and what comes next is not for B
    o.group_remove(&cid, &b.me().sk, false).unwrap();
    let mut gone = false;
    for _ in 0..100 {
        if b.group_state(&cid).unwrap().is_some_and(|s| !s.is_member(&b.me().sk)) {
            gone = true;
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    assert!(gone, "B learns they were removed");
    assert!(b.groups().unwrap().is_empty());
    ea.clear();
    eb.clear();
    o.group_send_text(&cid, "khabar-e dovom").unwrap();
    assert_eq!(group_text(&rx_a, &mut ea, &cid, 20).as_deref(), Some("khabar-e dovom"));
    assert_eq!(group_text(&rx_b, &mut eb, &cid, 5), None, "B no longer receives");
    o.stop();
    a.stop();
    b.stop();
}
