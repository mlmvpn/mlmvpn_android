//! Delete for everyone (SPEC 5.2; Ehsan, 25 Oct 2026: «پیام داخل کانال یا گروه رو حذف میکنم
//! دوباره برمیگرده، باید برای همه حذف بشه»): in a one-to-one chat either side takes any message
//! down on both phones; in a group a member takes their own down and an admin anyone's, while a
//! member's try at someone else's leaves the others' copies alone; in a channel the admin's
//! post goes from the readers too.

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
fn messages_go_for_everyone() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    let (so, sa, sb) = (o.me().sk, a.me().sk, b.me().sk);
    o.add_contact(&a.card_link().unwrap()).unwrap();
    o.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || a.contact(&so).unwrap().is_some() && b.contact(&so).unwrap().is_some()));

    // one to one: Ali takes the owner's message down for both of them
    let rx_o = o.events();
    let mut eo = Vec::new();
    let keep = o.send_text(&sa, "bemanad").unwrap();
    let gone = o.send_text(&sa, "pak shavad").unwrap();
    let has = |n: &Node, peer, id| n.history(peer, 10).unwrap().iter().any(|m| m.id == id);
    assert!(eventually(20, || has(&a, &so, gone) && has(&a, &so, keep)), "both arrived\n{}", dump_log());
    let seq = a.history(&so, 10).unwrap().into_iter().find(|m| m.id == gone).unwrap().seq;
    assert_eq!(a.delete_for_everyone(&[seq]).unwrap(), 1);
    assert!(!has(&a, &so, gone), "gone here at once");
    let got = wait_for(&rx_o, &mut eo, 20, |e| matches!(e, Event::Deleted { group: false, seqs, .. } if !seqs.is_empty()).then_some(()));
    assert!(got.is_some(), "the owner is told: {eo:?}\n{}", dump_log());
    assert!(!has(&o, &sa, gone) && has(&o, &sa, keep), "gone on the other phone too, the rest stays");

    // a group: the owner (admin) and two members
    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &sa).unwrap();
    o.group_add(&gid, &sb).unwrap();
    // settled: every member holds every member's own card (Ali and Sara are no contacts of each
    // other and reach each other from those cards; a frame before them is a stranger's)
    let all_three = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3 && s.members.values().all(|m| m.own_card));
    assert!(eventually(30, || all_three(&o) && all_three(&a) && all_three(&b)), "every member sees three\n{}", dump_log());
    let in_group = |n: &Node, id| n.group_messages(&gid, 20).unwrap().iter().any(|m| m.id == id);
    let seq_of = |n: &Node, id| n.group_messages(&gid, 20).unwrap().into_iter().find(|m| m.id == id).unwrap().seq;
    let from_ali = a.group_send_text(&gid, "az ali").unwrap();
    let from_sara = b.group_send_text(&gid, "az sara").unwrap();
    let from_owner = o.group_send_text(&gid, "az owner").unwrap();
    let everywhere = |id| in_group(&o, id) && in_group(&a, id) && in_group(&b, id);
    assert!(eventually(30, || everywhere(from_ali) && everywhere(from_sara) && everywhere(from_owner)), "all three posts everywhere\n{}", dump_log());

    // a member takes their own down for everyone
    a.group_delete_seqs(&[seq_of(&a, from_ali)], true).unwrap();
    assert!(eventually(30, || !in_group(&o, from_ali) && !in_group(&b, from_ali)), "Ali's own post went everywhere\n{}", dump_log());
    // a member cannot take someone else's down for everyone: it goes from their phone only
    a.group_delete_seqs(&[seq_of(&a, from_sara)], true).unwrap();
    assert!(!in_group(&a, from_sara), "gone from Ali's phone");
    std::thread::sleep(Duration::from_secs(3));
    assert!(in_group(&o, from_sara) && in_group(&b, from_sara), "still on the others");
    // the admin takes anyone's down
    o.group_delete_seqs(&[seq_of(&o, from_sara)], true).unwrap();
    assert!(eventually(30, || !in_group(&b, from_sara)), "the admin took Sara's post down\n{}", dump_log());
    // a taken-down post's number is never given again: a new post gets a new one
    let next = o.group_send_text(&gid, "badi").unwrap();
    assert!(eventually(30, || in_group(&o, next)));
    let gone_seq = seq_of(&o, from_owner);
    assert!(seq_of(&o, next) > gone_seq);

    // a channel: the admin's post goes from the reader
    let cid = o.group_create("akhbar", "", true).unwrap();
    o.group_add(&cid, &sa).unwrap();
    assert!(eventually(30, || a.group_state(&cid).unwrap().is_some()), "Ali joins the channel");
    let post = o.group_send_text(&cid, "khabar").unwrap();
    let in_channel = |n: &Node| n.group_messages(&cid, 10).unwrap().iter().any(|m| m.id == post);
    assert!(eventually(30, || in_channel(&a)));
    let seq = o.group_messages(&cid, 10).unwrap().into_iter().find(|m| m.id == post).unwrap().seq;
    o.group_delete_seqs(&[seq], true).unwrap();
    assert!(eventually(30, || !in_channel(&a)), "gone from the reader\n{}", dump_log());

    for n in [&o, &a, &b] {
        n.stop();
    }
}
