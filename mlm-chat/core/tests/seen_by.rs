//! «Seen by» in groups (SPEC 7.6, T58; Dr.Net's owner, 27 Oct 2026: «بزنه چند نفر سین زدن حداکثر تا
//! ۱۰۰ نفر»): a member who reads group messages tells each one's author, and only the author, even
//! when the two are no contacts; the author keeps who read what. A channel's readers tell nobody,
//! and a read receipt that cannot go is dropped, never tried again.

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

fn seq_of(n: &Node, gid: &[u8], id: &[u8; 16]) -> i64 {
    n.group_messages(gid, 50).unwrap().into_iter().find(|m| m.id == *id).map(|m| m.seq).unwrap_or(0)
}

#[test]
fn readers_tell_the_author_only() {
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
    let all_three = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3 && s.members.values().all(|m| m.own_card));
    assert!(eventually(40, || all_three(&o) && all_three(&a) && all_three(&b)), "every member has every card\n{}", dump_log());

    // the owner's post and Sara's (Ali and Sara are no contacts)
    let rx_o = o.events();
    let rx_b = b.events();
    let (mut eo, mut eb) = (Vec::new(), Vec::new());
    let post = o.group_send_text(&gid, "salam be hame").unwrap();
    let hers = b.group_send_text(&gid, "salam az sara").unwrap();
    assert!(eventually(30, || seq_of(&a, &gid, &post) > 0 && seq_of(&a, &gid, &hers) > 0), "Ali has both\n{}", dump_log());

    // Ali reads both at once: each author hears of their own only
    let upto = seq_of(&a, &gid, &post).max(seq_of(&a, &gid, &hers));
    assert_eq!(a.group_mark_read(&gid, upto).unwrap(), 2);
    let own = seq_of(&o, &gid, &post);
    assert!(wait_for(&rx_o, &mut eo, 30, |e| matches!(e, Event::GroupRead { seq, peer, .. } if *seq == own && *peer == sa).then_some(())).is_some(), "the owner hears Ali read it: {eo:?}\n{}", dump_log());
    let reads = o.group_reads(own).unwrap();
    assert_eq!(reads.iter().map(|r| r.0).collect::<Vec<_>>(), vec![sa]);
    assert!(o.group_messages(&gid, 10).unwrap().iter().any(|m| m.id == post && m.state.name() == "read"));
    let hers_seq = seq_of(&b, &gid, &hers);
    assert!(wait_for(&rx_b, &mut eb, 30, |e| matches!(e, Event::GroupRead { seq, peer, .. } if *seq == hers_seq && *peer == sa).then_some(())).is_some(), "Sara hears it from a member who is no contact: {eb:?}");
    assert_eq!(b.group_reads(hers_seq).unwrap().iter().map(|r| r.0).collect::<Vec<_>>(), vec![sa]);
    assert!(b.group_reads(seq_of(&b, &gid, &post)).unwrap().is_empty(), "nobody tells Sara about the owner's post");

    // read again: nothing new, nobody told twice
    assert_eq!(a.group_mark_read(&gid, upto).unwrap(), 0);
    // Sara reads the owner's post too: two readers, the first first
    assert_eq!(b.group_mark_read(&gid, seq_of(&b, &gid, &post)).unwrap(), 1);
    assert!(eventually(30, || o.group_reads(own).unwrap().len() == 2), "{}", dump_log());
    assert_eq!(o.group_reads(own).unwrap()[0].0, sa);
    assert!(eventually(20, || a.outbox_len().unwrap() == 0 && b.outbox_len().unwrap() == 0 && o.outbox_len().unwrap() == 0));

    // a channel's readers tell nobody
    let cid = o.group_create("akhbar", "", true).unwrap();
    o.group_add(&cid, &sa).unwrap();
    assert!(eventually(30, || a.group_state(&cid).unwrap().is_some()));
    let news = o.group_send_text(&cid, "khabar").unwrap();
    assert!(eventually(30, || seq_of(&a, &cid, &news) > 0));
    assert_eq!(a.group_mark_read(&cid, seq_of(&a, &cid, &news)).unwrap(), 1);
    std::thread::sleep(Duration::from_secs(3));
    assert!(o.group_reads(seq_of(&o, &cid, &news)).unwrap().is_empty(), "a channel has no «seen by»");

    // the author's phone is off: the receipt is dropped, never queued for later
    assert!(eventually(20, || a.outbox_len().unwrap() == 0));
    let late = b.group_send_text(&gid, "akharin").unwrap();
    assert!(eventually(30, || seq_of(&a, &gid, &late) > 0));
    b.stop();
    assert_eq!(a.group_mark_read(&gid, seq_of(&a, &gid, &late)).unwrap(), 1);
    assert!(a.outbox_len().unwrap() >= 1, "it waits a moment");
    assert!(eventually(20, || a.outbox_len().unwrap() == 0), "and goes once the delivery fails\n{}", dump_log());

    for n in [&o, &a] {
        n.stop();
    }
}
