//! Polls (m4, T69; SPEC 5 `Poll` and `PollVote`): a poll is a message of the group; each vote
//! goes to every member like a reaction, and each phone counts. The newest vote of each member
//! stands, an empty one takes it back; a quiz keeps the first answer; a closed poll takes none.
//! An anonymous poll shows nobody who voted; a vote is never a message of its own; a channel's
//! reader, who may not post, still votes.

mod common;

use common::*;
use mlmchat_core::envelope::Poll;
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

fn poll(question: &str, options: &[&str]) -> Poll {
    Poll { question: question.into(), options: options.iter().map(|o| o.to_string()).collect(), anonymous: true, ..Poll::default() }
}

#[test]
fn polls_in_a_group_and_a_channel() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    let (so, sa, sb) = (o.me().sk, a.me().sk, b.me().sk);
    o.add_contact(&a.card_link().unwrap()).unwrap();
    o.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || a.contact(&so).unwrap().is_some() && b.contact(&so).unwrap().is_some()));

    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &sa).unwrap();
    o.group_add(&gid, &sb).unwrap();
    let all_three = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3);
    assert!(eventually(30, || all_three(&a) && all_three(&b)), "every member sees three\n{}", dump_log());

    // the poll is a message of its own, with its options
    assert!(o.group_send_poll(&gid, poll("koja?", &["shiraz"]), 0).is_err(), "one option is no poll");
    let p = o.group_send_poll(&gid, poll("koja beravim?", &["shiraz", "tabriz", "yazd"]), 0).unwrap();
    assert!(eventually(30, || a.group_messages(&gid, 5).unwrap().iter().any(|m| m.id == p && m.kind == "poll")), "Ali has it\n{}", dump_log());
    assert!(eventually(30, || b.group_messages(&gid, 5).unwrap().iter().any(|m| m.id == p)));

    // votes go to everyone and each phone counts; a vote is no message
    let rx_b = b.events();
    let mut eb = Vec::new();
    a.group_vote(&gid, &p, &[1]).unwrap();
    let heard = wait_for(&rx_b, &mut eb, 30, |e| matches!(e, Event::PollVote { group: true, id, peer, .. } if *id == p && *peer == sa).then_some(()));
    assert!(heard.is_some(), "Sara hears Ali's vote: {eb:?}\n{}", dump_log());
    b.group_vote(&gid, &p, &[1]).unwrap();
    o.group_vote(&gid, &p, &[2]).unwrap();
    let want = |n: &Node| n.poll_results(&gid, &p).unwrap().counts == vec![0, 2, 1];
    assert!(eventually(30, || want(&o) && want(&a) && want(&b)), "everyone counts the same\n{}", dump_log());
    let r = a.poll_results(&gid, &p).unwrap();
    assert_eq!((r.voters, r.mine.clone()), (3, vec![1]));
    assert!(r.who.is_empty(), "anonymous: nobody is shown");
    assert_eq!(b.group_messages(&gid, 10).unwrap().len(), 1, "votes are no messages");

    // a newer vote stands, an empty one takes it back; a vote that does not fit is refused
    std::thread::sleep(Duration::from_millis(5));
    a.group_vote(&gid, &p, &[0]).unwrap();
    assert!(eventually(30, || o.poll_results(&gid, &p).unwrap().counts == vec![1, 1, 1]), "changed\n{}", dump_log());
    std::thread::sleep(Duration::from_millis(5));
    a.group_vote(&gid, &p, &[]).unwrap();
    assert!(eventually(30, || o.poll_results(&gid, &p).unwrap().voters == 2), "taken back\n{}", dump_log());
    assert!(a.group_vote(&gid, &p, &[0, 1]).is_err(), "one answer only");
    assert!(a.group_vote(&gid, &p, &[3]).is_err(), "no such option");

    // a quiz keeps the first answer; a poll that is not anonymous shows who chose what
    let q = o.group_send_poll(&gid, Poll { quiz: true, correct: 2, ..poll("paytakht?", &["shiraz", "tabriz", "tehran"]) }, 0).unwrap();
    assert!(eventually(30, || a.group_messages(&gid, 5).unwrap().iter().any(|m| m.id == q)));
    a.group_vote(&gid, &q, &[0]).unwrap();
    assert!(a.group_vote(&gid, &q, &[2]).is_err(), "a quiz is answered once");
    assert!(a.group_vote(&gid, &q, &[]).is_err(), "and not taken back");
    let open = o.group_send_poll(&gid, Poll { anonymous: false, multiple: true, ..poll("chi bekhorim?", &["kabab", "ash"]) }, 0).unwrap();
    assert!(eventually(30, || b.group_messages(&gid, 5).unwrap().iter().any(|m| m.id == open)));
    b.group_vote(&gid, &open, &[0, 1]).unwrap();
    assert!(eventually(30, || o.poll_results(&gid, &open).unwrap().who == vec![(sb, vec![0, 1])]), "who chose what\n{}", dump_log());

    // a channel: a reader may not post, and still votes
    let cid = o.group_create("akhbar", "", true).unwrap();
    o.group_add(&cid, &sa).unwrap();
    assert!(eventually(30, || a.group_state(&cid).unwrap().is_some()), "Ali joins the channel");
    let cp = o.group_send_poll(&cid, poll("khabar?", &["are", "na"]), 0).unwrap();
    assert!(eventually(30, || a.group_messages(&cid, 5).unwrap().iter().any(|m| m.id == cp)));
    assert!(a.group_send_poll(&cid, poll("man?", &["1", "2"]), 0).is_err(), "a reader does not post a poll");
    a.group_vote(&cid, &cp, &[0]).unwrap();
    assert!(eventually(30, || o.poll_results(&cid, &cp).unwrap().counts == vec![1, 0]), "the owner counts the reader's\n{}", dump_log());

    for n in [&o, &a, &b] {
        n.stop();
    }
}
