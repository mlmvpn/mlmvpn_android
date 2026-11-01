//! One account per phone in the official group (T74; Ehsan, 31 Oct 2026: a banned advertiser
//! cleared the app's data and came back under a new name). Every post carries its phone's tag; an
//! admin's ban takes the phones the account posted from, so a new account made on one of them posts
//! no more; and from `DEVICE_FROM_MS` on a member's post without a tag counts nowhere.

mod common;

use common::*;
use mlmchat_core::envelope::Envelope;
use mlmchat_core::feed::{self, FeedBan, FeedDescriptor, FeedItem, FeedState, Item};
use mlmchat_core::node::Node;
use std::time::Duration;

fn up(dir: &std::path::Path, name: &str, device: &[u8]) -> Node {
    let n = new_node(dir, name);
    n.set_device(device);
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

fn texts(n: &Node, feed_id: &[u8]) -> Vec<String> {
    n.feed_state(feed_id)
        .unwrap()
        .map(|st| {
            st.posts
                .iter()
                .filter_map(|p| match Envelope::from_bytes(&p.envelope).ok()?.body {
                    Some(mlmchat_core::envelope::Body::Text(t)) => Some(t.body),
                    _ => None,
                })
                .collect()
        })
        .unwrap_or_default()
}

#[test]
fn a_ban_holds_the_phone_and_a_new_account_on_it() {
    init_log();
    let dirs: Vec<_> = (0..4).map(|_| tempfile::tempdir().unwrap()).collect();
    let admin = up(dirs[0].path(), "ehsan", b"phone-of-ehsan");
    let reza = up(dirs[1].path(), "reza", b"phone-of-reza");
    let sara = up(dirs[2].path(), "sara", b"phone-of-sara");
    let (owner_secret, owner) = feed::new_owner_key();
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let seed = vec![feed::sign_admins(&owner_secret, &group.feed_id, 1, &[admin.me().sk], 1).unwrap()];
    for n in [&admin, &reza, &sara] {
        n.set_feeds(vec![group.clone()], &seed).unwrap();
    }
    reza.feed_send_text(&group.feed_id, "tabligh 1").unwrap();
    sara.feed_send_text(&group.feed_id, "salam").unwrap();
    for n in [&reza, &sara] {
        admin.add_contact(&n.card_link().unwrap()).unwrap();
    }
    assert!(eventually(70, || texts(&admin, &group.feed_id).len() == 2), "both posts reach the admin\n{}", dump_log());
    // the ban names Reza's phone, as the admin's phone saw it on his post
    let reza_sk = reza.me().sk;
    admin.feed_ban(&group.feed_id, &reza_sk, u64::MAX).unwrap();
    let st = admin.feed_state(&group.feed_id).unwrap().unwrap();
    let tag = feed::device_tag(&group.feed_id, b"phone-of-reza");
    assert_eq!(st.banned_devices.get(&tag), Some(&u64::MAX));
    assert!(!texts(&admin, &group.feed_id).contains(&"tabligh 1".to_string()));

    // Reza clears the app's data: a new account on the same phone. Before it hears of the ban it
    // posts; the admin's phone takes it nowhere, and once it hears, it refuses to post at all
    reza.stop();
    let again = up(dirs[3].path(), "reza-jadid", b"phone-of-reza");
    again.set_feeds(vec![group.clone()], &seed).unwrap();
    again.feed_send_text(&group.feed_id, "tabligh 2").unwrap();
    admin.add_contact(&again.card_link().unwrap()).unwrap();
    assert!(eventually(70, || again.feed_banned_until(&group.feed_id).unwrap() > 0), "the new account learns its phone is banned\n{}", dump_log());
    assert!(again.feed_send_text(&group.feed_id, "tabligh 3").is_err(), "a banned phone posts no more");
    std::thread::sleep(Duration::from_secs(2));
    let seen = texts(&admin, &group.feed_id);
    assert!(!seen.iter().any(|t| t.starts_with("tabligh")), "{seen:?}");
    assert!(seen.contains(&"salam".to_string()), "another phone posts as before");
    assert_eq!(sara.feed_banned_until(&group.feed_id).unwrap(), 0);
    for n in [&admin, &sara, &again] {
        n.stop();
    }
}

fn item(feed_id: &[u8], author: &[u8], n: u8, ts_ms: u64, device: &[u8], item: Item) -> FeedItem {
    FeedItem { feed_id: feed_id.to_vec(), id: vec![n; 16], author: author.to_vec(), ts_ms, name: String::new(), card: Vec::new(), device: device.to_vec(), client: None, item: Some(item) }
}

fn post(text: &str) -> Item {
    Item::Post(Envelope::text(text).to_bytes().unwrap())
}

#[test]
fn the_group_counts_a_member_post_by_its_phone() {
    let (owner_secret, owner) = feed::new_owner_key();
    let d = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let admin = [1u8; 32];
    let (x, y, z, w) = ([3u8; 32], [4u8; 32], [5u8; 32], [6u8; 32]);
    let phone = feed::device_tag(&d.feed_id, b"one phone");
    let other = feed::device_tag(&d.feed_id, b"another phone");
    let cut = feed::DEVICE_FROM_MS;
    let admins = feed::sign_admins(&owner_secret, &d.feed_id, 1, &[admin], 1).unwrap().open().unwrap();
    let mut items = vec![
        admins,
        item(&d.feed_id, &x, 10, cut - 50_000, &phone, post("x avval")),
        item(&d.feed_id, &admin, 11, cut - 40_000, &[], Item::Ban(FeedBan { sk: x.to_vec(), until_ms: u64::MAX, devices: vec![phone.clone()] })),
        // a new account on the banned phone
        item(&d.feed_id, &y, 12, cut - 30_000, &phone, post("y")),
        item(&d.feed_id, &z, 13, cut - 30_000, &other, post("z")),
        // no tag: before the cut it counts, after it not
        item(&d.feed_id, &w, 14, cut - 20_000, &[], post("w ghabl")),
        item(&d.feed_id, &w, 15, cut + 20_000, &[], post("w baad")),
        // an admin needs none
        item(&d.feed_id, &admin, 16, cut + 30_000, &[], post("modir")),
    ];
    let shown = |items: &[FeedItem]| -> Vec<String> {
        FeedState::build(&d, items, cut + 60_000)
            .posts
            .iter()
            .filter_map(|p| match Envelope::from_bytes(&p.envelope).ok()?.body {
                Some(mlmchat_core::envelope::Body::Text(t)) => Some(t.body),
                _ => None,
            })
            .collect()
    };
    assert_eq!(shown(&items), vec!["z", "w ghabl", "modir"]);
    let st = FeedState::build(&d, &items, cut + 60_000);
    assert_eq!(st.devices_of.get(&y).map(|s| s.contains(&phone)), Some(true), "the new account's phone is known, for its own ban");
    // the phone let go with the account: the new account's post counts again
    items.push(item(&d.feed_id, &admin, 17, cut + 40_000, &[], Item::Ban(FeedBan { sk: x.to_vec(), until_ms: 0, devices: vec![phone.clone()] })));
    assert_eq!(shown(&items), vec!["x avval", "y", "z", "w ghabl", "modir"]);
    // a ban from a member is no ban
    items.push(item(&d.feed_id, &z, 18, cut + 50_000, &other, Item::Ban(FeedBan { sk: w.to_vec(), until_ms: u64::MAX, devices: vec![phone.clone()] })));
    assert_eq!(shown(&items).len(), 5);
}

#[test]
fn a_tag_is_the_phones_and_the_feeds() {
    let a = feed::device_tag(&[2; 16], b"one phone");
    assert_eq!(a.len(), feed::DEVICE_LEN);
    assert_eq!(a, feed::device_tag(&[2; 16], b"one phone"));
    assert_ne!(a, feed::device_tag(&[3; 16], b"one phone"), "another feed: another tag");
    assert_ne!(a, feed::device_tag(&[2; 16], b"another phone"));
}
