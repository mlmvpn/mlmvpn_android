//! The official feeds' copy in a Google relay (SPEC 18.2; Ehsan, 25 Oct 2026: two testers saw the
//! official chats empty with one member, and the owner never saw them counted). Phones that are
//! nobody's contact get the posts and the count from the copy, leave their own there, and keep
//! only what checks: a forged item in the copy goes nowhere.

mod common;

use common::gas_emu::Emu;
use common::*;
use mlmchat_core::envelope::{Body, Envelope};
use mlmchat_core::feed::{self, FeedDescriptor, FeedItem, Item, SignedFeedItem};
use mlmchat_core::identity::Identity;
use mlmchat_core::node::Node;
use mlmchat_core::transport::gas::http::Endpoint;
use mlmchat_core::transport::gas::proto::{share_link, RelayRef};
use mlmchat_core::transport::gas::GasConfig;
use std::time::Duration;

const ID: &str = "AKfycbEmuMirror0abcdefghijklmnopqrstuvwxyz0123456789";
const USE: [u8; 16] = [0x5a; 16];

fn cfg(emu: &Emu) -> GasConfig {
    GasConfig {
        endpoint: Some(Endpoint::Plain(emu.addr)),
        wait_ms: 1500,
        idle_soon: Duration::from_millis(300),
        idle_late: Duration::from_millis(300),
        request_timeout: Duration::from_secs(5),
        pending_tick: Duration::from_millis(200),
        pending_first: Duration::from_millis(400),
        ..Default::default()
    }
}

fn texts(n: &Node, feed: &[u8]) -> Vec<String> {
    n.feed_state(feed)
        .unwrap()
        .unwrap()
        .posts
        .iter()
        .filter_map(|p| match Envelope::from_bytes(&p.envelope).ok()?.body {
            Some(Body::Text(t)) => Some(t.body),
            _ => None,
        })
        .collect()
}

#[test]
fn a_phone_with_no_contact_gets_the_posts_and_is_counted() {
    init_log();
    let emu = Emu::start(Duration::from_secs(60));
    emu.add(ID, &hex::encode(USE), &hex::encode([0x6b; 16]));
    let link = share_link(&RelayRef::new(ID, USE).unwrap(), "official");

    let (owner_secret, owner) = feed::new_owner_key();
    let channel = FeedDescriptor { feed_id: vec![1; 16], broadcast: true, name: "gereh-news".into(), owner };
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let dirs: Vec<_> = (0..3).map(|_| tempfile::tempdir().unwrap()).collect();
    let admin = new_node(dirs[0].path(), "ehsan");
    let seed: Vec<_> = [&channel, &group].iter().map(|f| feed::sign_admins(&owner_secret, &f.feed_id, 1, &[admin.me().sk], 1).unwrap()).collect();
    let start = |n: &Node| {
        n.set_feeds(vec![channel.clone(), group.clone()], &seed).unwrap();
        n.set_feed_mirror(Some(&link)).unwrap();
        n.start_gas(cfg(&emu)).unwrap();
    };
    start(&admin);

    // the admin posts: it goes to the copy at once, though the admin has no contact
    admin.feed_send_text(&channel.feed_id, "khosh amadid be gereh").unwrap();
    let ok = (0..100).any(|_| {
        std::thread::sleep(Duration::from_millis(100));
        emu.feed_len(ID) >= 1
    });
    assert!(ok, "the post reached the copy\n{}", dump_log());

    // two new phones, nobody's contact: they read the post and count themselves in
    let tester1 = new_node(dirs[1].path(), "tester-1");
    let tester2 = new_node(dirs[2].path(), "tester-2");
    for t in [&tester1, &tester2] {
        start(t);
        t.mirror_sync_now();
        assert_eq!(texts(t, &channel.feed_id), vec!["khosh amadid be gereh".to_string()], "\n{}", dump_log());
    }
    // the admin sees them counted, and so does each tester
    admin.mirror_sync_now();
    tester1.mirror_sync_now();
    // (3; a counter shared by two of the three tokens, about one run in 700, shows 2)
    let counted = |n: u64| (2..=3).contains(&n);
    assert!(counted(admin.feed_member_count().unwrap()), "the admin sees the two testers: {}", admin.feed_member_count().unwrap());
    assert!(counted(tester1.feed_member_count().unwrap()));
    assert!(counted(emu.feed_count(ID).round() as u64));

    // a tester writes in the group; the admin reads it from the copy
    tester2.feed_send_text(&group.feed_id, "salam, man tester-2 hastam").unwrap();
    let ok = (0..100).any(|_| {
        std::thread::sleep(Duration::from_millis(100));
        emu.feed_len(ID) >= 2
    });
    assert!(ok, "the tester's post reached the copy");
    admin.mirror_sync_now();
    assert_eq!(texts(&admin, &group.feed_id), vec!["salam, man tester-2 hastam".to_string()]);

    // nothing new: a read brings nothing and leaves nothing
    let puts = emu.feed_ops(ID).1;
    tester1.mirror_sync_now();
    tester1.mirror_sync_now();
    assert_eq!(emu.feed_ops(ID).1, puts, "nothing to leave, no write");

    // a forged channel post (signed by someone who is no admin) in the copy goes nowhere
    let stranger = Identity::new("jaal").unwrap();
    let forged = FeedItem { feed_id: channel.feed_id.clone(), id: vec![9; 16], author: stranger.sk().to_vec(), ts_ms: mlmchat_core::util::now_ms(), name: "MLMVPN".into(), card: Vec::new(), device: Vec::new(), client: None, item: Some(Item::Post(Envelope::text("jaali").to_bytes().unwrap())) };
    let forged = SignedFeedItem::sign(&forged, |m| stranger.sign(m));
    let gas = tester1.gas().unwrap();
    gas.feed_put(&RelayRef::new(ID, USE).unwrap(), &channel.feed_id, &[([9; 16], forged.to_bytes(), 0)], &[]).unwrap();
    tester2.mirror_sync_now();
    assert_eq!(texts(&tester2, &channel.feed_id), vec!["khosh amadid be gereh".to_string()], "the forged post is not kept");

    for n in [&admin, &tester1, &tester2] {
        n.stop();
    }
}

/// Ehsan, 29 Oct 2026, with hundreds in the official group: new posts came tens of minutes late
/// for everyone. A phone read the copy every 20 minutes and once when the chat opened; now it reads
/// it every few seconds while an official chat is on the screen (the app says so with
/// `feed_watch`), and not more often than that however often the app says it.
#[test]
fn an_official_chat_on_the_screen_gets_new_posts_in_seconds() {
    init_log();
    let emu = Emu::start(Duration::from_secs(60));
    emu.add(ID, &hex::encode(USE), &hex::encode([0x6b; 16]));
    let relay = RelayRef::new(ID, USE).unwrap();
    let link = share_link(&relay, "official");
    let (owner_secret, owner) = feed::new_owner_key();
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let dir = tempfile::tempdir().unwrap();
    let reader = new_node(dir.path(), "reader");
    let admin = Identity::new("ehsan").unwrap();
    let seed = vec![feed::sign_admins(&owner_secret, &group.feed_id, 1, &[admin.sk()], 1).unwrap()];
    reader.set_feeds(vec![group.clone()], &seed).unwrap();
    reader.set_feed_mirror(Some(&link)).unwrap();
    reader.start_gas(cfg(&emu)).unwrap();
    let reads = || emu.feed_ops(ID).0;
    // the first read comes by itself, soon after the start
    assert!((0..100).any(|_| {
        std::thread::sleep(Duration::from_millis(100));
        reads() >= 1
    }));
    let first = std::time::Instant::now();

    // someone posts in the group (straight into the copy, as a phone of theirs would)
    let post = FeedItem { feed_id: group.feed_id.clone(), id: vec![7; 16], author: admin.sk().to_vec(), ts_ms: mlmchat_core::util::now_ms(), name: "ehsan".into(), card: Vec::new(), device: Vec::new(), client: None, item: Some(Item::Post(Envelope::text("payam-e taze").to_bytes().unwrap())) };
    let post = SignedFeedItem::sign(&post, |m| admin.sign(m));
    reader.gas().unwrap().feed_put(&relay, &group.feed_id, &[([7; 16], post.to_bytes(), 0)], &[]).unwrap();

    // with no official chat on the screen (and the app behind), nothing is read for a while
    std::thread::sleep(Duration::from_secs(3));
    assert_eq!(reads(), 1, "no read by itself in the background");
    assert!(texts(&reader, &group.feed_id).is_empty());

    // the chat on the screen: the app says so often (Telegram polls an open channel); the post
    // comes within a watch period of the last read (MIRROR_WATCH_MS, 45 s since 1.2.45: the copy
    // was read too often by thousands), and the copy is not read more often than that
    let got = (0..500).any(|_| {
        reader.feed_watch();
        std::thread::sleep(Duration::from_millis(100));
        !texts(&reader, &group.feed_id).is_empty()
    });
    assert!(got, "the post came while watching\n{}", dump_log());
    let took = first.elapsed();
    assert!(took < Duration::from_secs(48), "about one watch period after the last read: {took:?}");
    assert!(reads() <= 2, "one more read, not one per look: {}", reads());
    reader.stop();
}
