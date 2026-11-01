//! Editing a sent message (SPEC 5 `Edit`; Dr.Net's owner, 27 Oct 2026, from MLMVPN's list «ویرایش
//! پیام در چت تکی و گروه و کانال»): in a one-to-one chat the sender's new text replaces the old on
//! both phones and the other side is told; only one's own message is edited; in a group a member's
//! edit reaches every member, and a member cannot edit someone else's post.

mod common;

use common::*;
use mlmchat_core::envelope::{Body, Envelope};
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

fn text_of(envelope: &[u8]) -> Option<String> {
    match Envelope::from_bytes(envelope).ok()?.body {
        Some(Body::Text(t)) => Some(t.body),
        _ => None,
    }
}

#[test]
fn messages_are_edited_for_everyone() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    let (so, sa, sb) = (o.me().sk, a.me().sk, b.me().sk);
    o.add_contact(&a.card_link().unwrap()).unwrap();
    o.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || a.contact(&so).unwrap().is_some() && b.contact(&so).unwrap().is_some()));

    // one to one: the owner edits what he sent; Ali sees the new text and is told
    let rx_a = a.events();
    let mut ea = Vec::new();
    let id = o.send_text(&sa, "salam donya").unwrap();
    let text_at = |n: &Node, peer, id| n.history(peer, 10).unwrap().into_iter().find(|m| m.id == id).and_then(|m| text_of(&m.envelope));
    assert!(eventually(20, || text_at(&a, &so, id).is_some()), "arrived\n{}", dump_log());
    let seq = o.history(&sa, 10).unwrap().into_iter().find(|m| m.id == id).unwrap().seq;
    o.edit_text(seq, "salam donya, virayesh shod", Vec::new()).unwrap();
    assert_eq!(text_at(&o, &sa, id).as_deref(), Some("salam donya, virayesh shod"), "changed here at once");
    assert!(o.edited_ms(&id).unwrap() > 0, "marked edited here");
    let got = wait_for(&rx_a, &mut ea, 20, |e| matches!(e, Event::Edited { group: false, .. }).then_some(()));
    assert!(got.is_some(), "Ali is told: {ea:?}\n{}", dump_log());
    assert_eq!(text_at(&a, &so, id).as_deref(), Some("salam donya, virayesh shod"), "changed on the other phone");
    assert!(a.edited_ms(&id).unwrap() > 0, "marked edited there");
    assert_eq!(a.history(&so, 10).unwrap().len(), 1, "an edit is no message of its own");
    // Ali cannot edit the owner's message
    let seq_at_ali = a.history(&so, 10).unwrap()[0].seq;
    assert!(a.edit_text(seq_at_ali, "dast bordam", Vec::new()).is_err(), "only one's own");

    // a group: the owner (admin) and two members
    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &sa).unwrap();
    o.group_add(&gid, &sb).unwrap();
    let all_three = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3 && s.members.values().all(|m| m.own_card));
    assert!(eventually(30, || all_three(&o) && all_three(&a) && all_three(&b)), "every member sees three\n{}", dump_log());
    let post_text = |n: &Node, id| n.group_messages(&gid, 20).unwrap().into_iter().find(|m| m.id == id).and_then(|m| text_of(&m.envelope));
    let seq_of = |n: &Node, id| n.group_messages(&gid, 20).unwrap().into_iter().find(|m| m.id == id).unwrap().seq;
    let from_ali = a.group_send_text(&gid, "az ali").unwrap();
    assert!(eventually(30, || post_text(&o, from_ali).is_some() && post_text(&b, from_ali).is_some()), "posted everywhere\n{}", dump_log());

    // Ali edits his own post: every member sees it
    a.group_edit_text(seq_of(&a, from_ali), "az ali (virayesh)", Vec::new()).unwrap();
    let edited = |n: &Node| post_text(n, from_ali).as_deref() == Some("az ali (virayesh)");
    assert!(edited(&a), "changed here at once");
    assert!(eventually(30, || edited(&o) && edited(&b)), "changed for every member\n{}", dump_log());
    assert_eq!(o.group_messages(&gid, 20).unwrap().len(), 1, "an edit is no post of its own");

    // Sara cannot edit Ali's post, even with a hand-made edit: the others keep his words
    assert!(b.group_edit_text(seq_of(&b, from_ali), "sara neveshte", Vec::new()).is_err(), "only one's own");
    let mut forged = Envelope::new(Body::Edit(mlmchat_core::envelope::Edit {
        target: from_ali.to_vec(),
        text: Some(mlmchat_core::envelope::Text { body: "jaali".into(), ..Default::default() }),
        caption: String::new(),
    }));
    forged.group_id = gid.clone();
    b.group_send(&forged).unwrap();
    std::thread::sleep(Duration::from_secs(3));
    assert!(edited(&o) && edited(&a), "a member's edit of someone else's post is dropped\n{}", dump_log());
    let _ = sb;
}
