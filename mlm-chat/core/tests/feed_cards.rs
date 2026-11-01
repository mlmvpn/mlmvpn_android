//! A member opens a one-to-one chat with another from the official group (SPEC 18.4, T64; Ehsan,
//! 29 Oct 2026: users in the group could not go to each other's private chat). A post carries its
//! author's own card now and then; a phone that read the post from the official copy, and is not
//! the author's contact, adds the author from it and their messages go both ways.

mod common;

use common::gas_emu::Emu;
use common::*;
use mlmchat_core::feed::{self, FeedDescriptor};
use mlmchat_core::node::Node;
use mlmchat_core::transport::gas::http::Endpoint;
use mlmchat_core::transport::gas::proto::{share_link, RelayRef};
use mlmchat_core::transport::gas::GasConfig;
use std::time::Duration;

const ID: &str = "AKfycbEmuCards0abcdefghijklmnopqrstuvwxyz0123456789";
const USE: [u8; 16] = [0x5c; 16];

fn eventually(secs: u64, mut f: impl FnMut() -> bool) -> bool {
    (0..secs * 10).any(|_| {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
        false
    })
}

#[test]
fn a_member_opens_a_chat_with_an_author_from_the_official_group() {
    init_log();
    let emu = Emu::start(Duration::from_secs(60));
    emu.add(ID, &hex::encode(USE), &hex::encode([0x6d; 16]));
    let link = share_link(&RelayRef::new(ID, USE).unwrap(), "official");
    let (owner_secret, owner) = feed::new_owner_key();
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let dirs: Vec<_> = (0..2).map(|_| tempfile::tempdir().unwrap()).collect();
    let up = |dir: &std::path::Path, name: &str| -> Node {
        let n = new_node(dir, name);
        n.start_direct(loopback(Vec::new())).unwrap();
        n
    };
    let author = up(dirs[0].path(), "sara");
    let reader = up(dirs[1].path(), "reza");
    let seed = vec![feed::sign_admins(&owner_secret, &group.feed_id, 1, &[[9; 32]], 1).unwrap()];
    for n in [&author, &reader] {
        n.set_feeds(vec![group.clone()], &seed).unwrap();
        n.set_feed_mirror(Some(&link)).unwrap();
        n.start_gas(GasConfig { endpoint: Some(Endpoint::Plain(emu.addr)), request_timeout: Duration::from_secs(5), ..Default::default() }).unwrap();
    }
    let sk = author.me().sk;
    // before any post of hers, the reader has no way to her
    assert!(reader.add_member_contact(&sk).is_err());

    // she posts twice: her card goes with the first post of the day only
    author.feed_send_text(&group.feed_id, "salam be hame").unwrap();
    author.feed_send_text(&group.feed_id, "dovomi").unwrap();
    assert!(eventually(20, || emu.feed_len(ID) >= 2), "the posts reached the copy\n{}", dump_log());
    reader.mirror_sync_now();
    let posts = reader.feed_state(&group.feed_id).unwrap().unwrap().posts;
    assert_eq!(posts.len(), 2);

    // the reader, nobody's contact, opens a chat with her from her post's card
    let c = reader.add_member_contact(&sk).expect("her card came with her post");
    assert_eq!(c.sk, sk);
    assert!(reader.contacts().unwrap().iter().any(|x| x.sk == sk), "listed like any contact added by hand");
    reader.send_text(&sk, "salam sara, man reza hastam").unwrap();
    let rs = reader.me().sk;
    assert!(eventually(30, || author.history(&rs, 5).unwrap().iter().any(|m| !m.outgoing)), "she got the message\n{}", dump_log());
    // and she can answer: the reader's card came inside the session
    author.send_text(&rs, "salam reza").unwrap();
    assert!(eventually(30, || reader.history(&sk, 5).unwrap().iter().any(|m| !m.outgoing)), "the answer came\n{}", dump_log());
    for n in [&author, &reader] {
        n.stop();
    }
}
