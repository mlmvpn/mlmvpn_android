//! Replies (SPEC 5 `reply_to`; 26 Oct 2026, a group of a Dr.Net phone and an MLMVPN phone: an
//! answer showed as one only on the phone that wrote it, a plain message on the other): the
//! answering phone names the message it answers by its own seq, the envelope carries that
//! message's id, and the other phone turns the id back into its own seq - in a one-to-one chat
//! and in a group alike. A seq of another chat goes as a plain message. In the official channel
//! and group (SPEC 18) a post's envelope names the post it answers by its id, with the text's
//! formatting (Ehsan, 26 Oct 2026: replies there did not reach the other phones).

mod common;

use common::*;
use mlmchat_core::envelope::{Body, Entity, EntityType, Envelope};
use mlmchat_core::feed::{self, FeedDescriptor};
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

fn reply_to(envelope: &[u8]) -> Vec<u8> {
    Envelope::from_bytes(envelope).unwrap().reply_to
}

#[test]
fn replies_name_the_message_on_both_phones() {
    init_log();
    let (d1, d2) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let (so, sa) = (o.me().sk, a.me().sk);
    o.add_contact(&a.card_link().unwrap()).unwrap();
    assert!(eventually(20, || a.contact(&so).unwrap().is_some()));

    // one to one: the owner says salam, Ali answers it
    let salam = o.send_text(&sa, "salam").unwrap();
    let row = |n: &Node, peer, id| n.history(peer, 20).unwrap().into_iter().find(|m| m.id == id);
    assert!(eventually(20, || row(&a, &so, salam).is_some()), "salam arrived\n{}", dump_log());
    let salam_at_ali = row(&a, &so, salam).unwrap().seq;
    let answer = a.send_text_reply(&so, "khoobi", salam_at_ali).unwrap();
    let mine = row(&a, &so, answer).unwrap();
    assert_eq!(reply_to(&mine.envelope), salam.to_vec(), "Ali's copy names salam");
    assert_eq!(a.reply_seq(&so, &reply_to(&mine.envelope)).unwrap(), salam_at_ali);
    assert!(eventually(20, || row(&o, &sa, answer).is_some()), "the answer arrived\n{}", dump_log());
    let theirs = row(&o, &sa, answer).unwrap();
    assert_eq!(reply_to(&theirs.envelope), salam.to_vec(), "the owner's copy names salam too");
    let salam_at_owner = row(&o, &sa, salam).unwrap().seq;
    assert_eq!(o.reply_seq(&sa, &reply_to(&theirs.envelope)).unwrap(), salam_at_owner, "as the owner's own seq");

    // no reply, and a seq that is not a message of this chat, go as plain messages
    let plain = a.send_text(&so, "bedoone javab").unwrap();
    assert!(reply_to(&row(&a, &so, plain).unwrap().envelope).is_empty());
    let stray = a.send_text_reply(&so, "gharibe", salam_at_ali + 1_000_000).unwrap();
    assert!(reply_to(&row(&a, &so, stray).unwrap().envelope).is_empty());
    assert_eq!(a.reply_seq(&so, &[]).unwrap(), 0);

    // a group: the same, through Megolm
    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &sa).unwrap();
    let both = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 2 && s.members.values().all(|m| m.own_card));
    assert!(eventually(30, || both(&o) && both(&a)), "both members settled\n{}", dump_log());
    let grow = |n: &Node, id| n.group_messages(&gid, 20).unwrap().into_iter().find(|m| m.id == id);
    let hi = o.group_send_text(&gid, "salam bacheha").unwrap();
    assert!(eventually(30, || grow(&a, hi).is_some()), "the post arrived\n{}", dump_log());
    let hi_at_ali = grow(&a, hi).unwrap().seq;
    let back = a.group_send_text_reply(&gid, "salam", hi_at_ali).unwrap();
    assert_eq!(reply_to(&grow(&a, back).unwrap().envelope), hi.to_vec());
    assert!(eventually(30, || grow(&o, back).is_some()), "the answer arrived in the group\n{}", dump_log());
    let got = grow(&o, back).unwrap();
    assert_eq!(reply_to(&got.envelope), hi.to_vec());
    assert_eq!(o.group_reply_seq(&gid, &reply_to(&got.envelope)).unwrap(), grow(&o, hi).unwrap().seq);
    // a seq that is no message of the group goes as a plain post
    let other = a.group_send_text_reply(&gid, "gharibe", hi_at_ali + 1_000_000).unwrap();
    assert!(reply_to(&grow(&a, other).unwrap().envelope).is_empty());

    for n in [&o, &a] {
        n.stop();
    }
}

#[test]
fn replies_in_the_official_channel_and_group() {
    init_log();
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(da.path(), "ehsan");
    let b = up(db.path(), "ali");
    let (owner_secret, owner) = feed::new_owner_key();
    let channel = FeedDescriptor { feed_id: vec![1; 16], broadcast: true, name: "gereh-news".into(), owner };
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let seed: Vec<_> = [&channel, &group].iter().map(|f| feed::sign_admins(&owner_secret, &f.feed_id, 1, &[a.me().sk], 1).unwrap()).collect();
    for n in [&a, &b] {
        n.set_feeds(vec![channel.clone(), group.clone()], &seed).unwrap();
    }
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || b.contact(&a.me().sk).unwrap().is_some()));
    let post = |n: &Node, f: &[u8], id: &[u8]| n.feed_state(f).unwrap().and_then(|s| s.posts.into_iter().find(|p| p.id == id)).and_then(|p| Envelope::from_bytes(&p.envelope).ok());
    let sync = |n: &Node, f: &[u8], id: &[u8]| {
        eventually(30, || {
            n.feed_sync_now();
            post(n, f, id).is_some()
        })
    };

    // the channel: the admin answers their own post, with a bold word
    let news = a.feed_send_text(&channel.feed_id, "khabar-e aval").unwrap();
    let bold = vec![Entity { r#type: EntityType::Bold as i32, offset: 0, length: 5, ..Default::default() }];
    let more = a.feed_send_text_rich(&channel.feed_id, "edame-ye khabar", bold, &news).unwrap();
    assert!(sync(&b, &channel.feed_id, &more), "the answer reached Ali
{}", dump_log());
    let got = post(&b, &channel.feed_id, &more).unwrap();
    assert_eq!(got.reply_to, news);
    match got.body {
        Some(Body::Text(t)) => assert_eq!(t.entities.len(), 1, "the formatting came too"),
        _ => panic!("a text post"),
    }

    // the group: Ali answers the admin, and the admin answers Ali
    let hi = a.feed_send_text(&group.feed_id, "salam be hame").unwrap();
    assert!(sync(&b, &group.feed_id, &hi));
    let back = b.feed_send_text_rich(&group.feed_id, "salam", Vec::new(), &hi).unwrap();
    assert!(sync(&a, &group.feed_id, &back), "Ali's answer reached the admin
{}", dump_log());
    assert_eq!(post(&a, &group.feed_id, &back).unwrap().reply_to, hi);
    // a post of another feed, or none at all, goes as a plain post
    let stray = b.feed_send_text_rich(&group.feed_id, "gharibe", Vec::new(), &news).unwrap();
    assert!(post(&b, &group.feed_id, &stray).unwrap().reply_to.is_empty());
    a.stop();
    b.stop();
}
