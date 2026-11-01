//! Pinned messages (T65; Ehsan, 29 Oct 2026: pinning in groups and channels, official or not, by
//! the owner and the admins, and in one-to-one chats). In a group a pin is an event of its log: a
//! member without the right cannot pin, and a member who joins later sees the pins. In a
//! one-to-one chat it is a `Pin` envelope and both phones see it. In an official feed only an
//! admin pins.

mod common;

use common::*;
use mlmchat_core::feed::{self, FeedDescriptor};
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
fn pins_in_a_group_and_in_a_one_to_one_chat() {
    init_log();
    let dirs: Vec<_> = (0..3).map(|_| tempfile::tempdir().unwrap()).collect();
    let o = up(dirs[0].path(), "owner");
    let a = up(dirs[1].path(), "ali");
    let b = up(dirs[2].path(), "bita");
    let (so, sa, sb) = (o.me().sk, a.me().sk, b.me().sk);
    for n in [&a, &b] {
        o.add_contact(&n.card_link().unwrap()).unwrap();
    }
    assert!(eventually(20, || a.contact(&so).unwrap().is_some() && b.contact(&so).unwrap().is_some()));

    // one-to-one: the owner pins Ali's message; both phones have it pinned, then neither
    a.send_text(&so, "in payam ra sanjagh kon").unwrap();
    assert!(eventually(20, || o.history(&sa, 5).unwrap().iter().any(|m| !m.outgoing)), "the message came\n{}", dump_log());
    let seq_o = o.history(&sa, 5).unwrap().iter().find(|m| !m.outgoing).unwrap().seq;
    let rx_a = a.events();
    o.pin(&sa, seq_o, false).unwrap();
    assert_eq!(o.pinned(&sa).unwrap(), vec![seq_o]);
    let seq_a = a.history(&so, 5).unwrap().iter().find(|m| m.outgoing).unwrap().seq;
    assert!(eventually(20, || a.pinned(&so).unwrap() == vec![seq_a]), "Ali sees it pinned\n{}", dump_log());
    assert!(rx_a.try_iter().any(|e| matches!(e, Event::Pinned { peer } if peer == so)), "an event says so");
    a.pin(&so, seq_a, true).unwrap();
    assert!(eventually(20, || o.pinned(&sa).unwrap().is_empty()), "the pin came off on both");
    assert!(o.history(&sa, 5).unwrap().len() == 1, "a pin is no message of its own");

    // a group: the owner pins; Ali, a member with no pin right, cannot
    let gid = o.group_create("goruh", "", false).unwrap();
    o.group_add(&gid, &sa).unwrap();
    assert!(eventually(30, || a.group_state(&gid).unwrap().is_some_and(|s| s.is_member(&sa))), "Ali joins\n{}", dump_log());
    o.group_send_text(&gid, "ghavanin-e goruh").unwrap();
    assert!(eventually(20, || !a.group_messages(&gid, 5).unwrap().is_empty()));
    let gseq = o.group_messages(&gid, 5).unwrap()[0].seq;
    o.group_pin(&gid, gseq, false).unwrap();
    assert_eq!(o.group_pinned(&gid).unwrap(), vec![gseq]);
    let aseq = a.group_messages(&gid, 5).unwrap()[0].seq;
    assert!(eventually(20, || a.group_pinned(&gid).unwrap() == vec![aseq]), "Ali sees the pin\n{}", dump_log());
    assert!(a.group_pin(&gid, aseq, true).is_err(), "a member without the right takes no pin off");

    // Bita joins later: the pin comes with the group's log
    o.group_add(&gid, &sb).unwrap();
    assert!(eventually(30, || b.group_state(&gid).unwrap().is_some_and(|s| s.pinned.len() == 1)), "Bita has the pin from the log\n{}", dump_log());
    for n in [&o, &a, &b] {
        n.stop();
    }
}

#[test]
fn only_an_admin_pins_in_an_official_feed() {
    init_log();
    let dirs: Vec<_> = (0..2).map(|_| tempfile::tempdir().unwrap()).collect();
    let admin = up(dirs[0].path(), "ehsan");
    let user = up(dirs[1].path(), "reza");
    let (owner_secret, owner) = feed::new_owner_key();
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let seed = vec![feed::sign_admins(&owner_secret, &group.feed_id, 1, &[admin.me().sk], 1).unwrap()];
    for n in [&admin, &user] {
        n.set_feeds(vec![group.clone()], &seed).unwrap();
    }
    let post = user.feed_send_text(&group.feed_id, "soal: rele chist?").unwrap();
    assert!(user.feed_pin(&group.feed_id, &post, false).is_err(), "a member pins nothing");
    // the admin has the post (here straight from the user's phone, as a contact would pass it)
    let items: Vec<_> = user.feed_state(&group.feed_id).unwrap().unwrap().posts.iter().map(|p| p.id.clone()).collect();
    assert_eq!(items, vec![post.clone()]);
    admin.add_contact(&user.card_link().unwrap()).unwrap();
    // with the next catch-up (SYNC_TICK_MS, 30 s), which may come only just under 30 s after the
    // contact: two ticks of room (as in slow.rs)
    assert!(eventually(70, || admin.feed_state(&group.feed_id).unwrap().unwrap().posts.len() == 1), "the post reached the admin\n{}", dump_log());
    admin.feed_pin(&group.feed_id, &post, false).unwrap();
    assert_eq!(admin.feed_state(&group.feed_id).unwrap().unwrap().pinned, vec![post.clone()]);
    assert!(eventually(30, || user.feed_state(&group.feed_id).unwrap().unwrap().pinned == vec![post.clone()]), "everyone sees the pin\n{}", dump_log());
    admin.feed_pin(&group.feed_id, &post, true).unwrap();
    assert!(eventually(30, || user.feed_state(&group.feed_id).unwrap().unwrap().pinned.is_empty()), "and its end");
    for n in [&admin, &user] {
        n.stop();
    }
}
