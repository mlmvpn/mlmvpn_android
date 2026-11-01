//! The official chats' items on our own relays, once for everyone (SPEC 18.7, T77; Ehsan, 31 Oct
//! 2026: «طرف باید حتما یه نقطه ای چیزی بزاره تا پیام های بقیه براش بیاد»): a post reaches a phone
//! that has no contact and has sent nothing, over the connection it keeps to our relay, within
//! seconds; a phone that starts later finds it there; and the relay keeps one copy of each item,
//! whoever leaves it.

mod common;

use common::nostr_emu::Relay;
use common::*;
use mlmchat_core::feed::{self, FeedDescriptor};
use mlmchat_core::node::Node;
use mlmchat_core::transport::nostr::feedrelay;
use mlmchat_core::transport::nostr::transport::NostrConfig;
use std::time::Duration;

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
        .map(|s| {
            s.posts
                .iter()
                .filter_map(|p| match mlmchat_core::envelope::Envelope::from_bytes(&p.envelope).ok()?.body {
                    Some(mlmchat_core::envelope::Body::Text(t)) => Some(t.body),
                    _ => None,
                })
                .collect()
        })
        .unwrap_or_default()
}

fn up(dir: &std::path::Path, name: &str, relay: &Relay, group: &FeedDescriptor, seed: &[feed::SignedFeedItem]) -> Node {
    let n = new_node(dir, name);
    n.set_feeds(vec![group.clone()], seed).unwrap();
    n.set_file_relays(vec![relay.url()]);
    n.start_nostr(NostrConfig { relays: vec![relay.url()], keep: 1, connect_timeout: Duration::from_secs(5), ok_timeout: Duration::from_secs(8), rest: Duration::from_secs(5), own: vec![relay.url()] })
        .unwrap();
    n
}

#[test]
fn a_post_reaches_every_phone_through_our_relay_once() {
    init_log();
    let relay = Relay::start();
    let (owner_secret, owner) = feed::new_owner_key();
    let group = FeedDescriptor { feed_id: vec![3; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let dirs: Vec<_> = (0..3).map(|_| tempfile::tempdir().unwrap()).collect();
    let admin_dir = &dirs[0];
    let admin = new_node(admin_dir.path(), "ehsan");
    let seed = vec![feed::sign_admins(&owner_secret, &group.feed_id, 1, &[admin.me().sk], 1).unwrap()];
    drop(admin);
    // two phones that are no one's contact: one posts, one only reads
    let poster = up(dirs[1].path(), "sara", &relay, &group, &seed);
    let reader = up(dirs[2].path(), "reza", &relay, &group, &seed);
    // the reader's subscription is open before the post
    assert!(eventually(20, || !reader.nostr_relays().iter().all(|r| r.state != "open")), "the reader reads our relay\n{}", dump_log());
    std::thread::sleep(Duration::from_millis(500));

    let env = mlmchat_core::envelope::Envelope::text("salam az gereh");
    poster.feed_post(&group.feed_id, &env).unwrap();
    assert!(eventually(20, || texts(&reader, &group.feed_id).iter().any(|t| t == "salam az gereh")), "the post came over the relay\n{}", dump_log());
    let items = || relay.events().into_iter().filter(|e| e["kind"] == feedrelay::KIND).count();
    assert_eq!(items(), 1, "one event for the post");

    // a phone that starts afterwards finds it there
    let late_dir = tempfile::tempdir().unwrap();
    let late = up(late_dir.path(), "mina", &relay, &group, &seed);
    assert!(eventually(20, || texts(&late, &group.feed_id).iter().any(|t| t == "salam az gereh")), "the late phone found the post\n{}", dump_log());

    // whoever leaves the same item (an admin's phone that lacks it there, say) makes the same
    // event: the relay takes it as there already and keeps one
    let stored = relay.events().into_iter().find(|e| e["kind"] == feedrelay::KIND).unwrap();
    let s = feedrelay::open(&stored).unwrap();
    let fi = s.open().unwrap();
    let again = feedrelay::event_for(&s, &fi.feed_id, &fi.id, fi.ts_ms).unwrap();
    assert_eq!(hex::encode(again.id), stored["id"].as_str().unwrap(), "the same event");
    let mut w = mlmchat_core::transport::nostr::files::Writer::open(&relay.url(), Duration::from_secs(5)).unwrap();
    assert_eq!(w.write(std::slice::from_ref(&again)), 1, "taken: it is there");
    w.close();
    assert_eq!(items(), 1, "still one");
    for n in [&poster, &reader, &late] {
        n.stop();
    }
}
