//! Slow mode in the official group (T67; Ehsan, 30 Oct 2026): an admin sets it; a member posts
//! again only once its time has come; an admin is never held back; posts from before it stay.

mod common;

use common::*;
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

#[test]
fn slow_mode_holds_a_member_back_not_an_admin() {
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
    user.feed_send_text(&group.feed_id, "yek").unwrap();
    user.feed_send_text(&group.feed_id, "do").unwrap();
    assert!(user.feed_set_slow(&group.feed_id, 60).is_err(), "only an admin sets it");
    admin.feed_set_slow(&group.feed_id, 60).unwrap();
    admin.add_contact(&user.card_link().unwrap()).unwrap();
    // a contact made after the posts gets them with the next catch-up (SYNC_TICK_MS, 30 s), and
    // that tick may come only just under 30 s after the contact: two ticks of room
    assert!(eventually(70, || user.feed_state(&group.feed_id).unwrap().unwrap().slow_s == 60), "the member learns it
{}", dump_log());
    assert_eq!(user.feed_state(&group.feed_id).unwrap().unwrap().posts.len(), 2, "posts from before it stay");
    assert!(user.feed_send_text(&group.feed_id, "se").is_err(), "a member waits");
    admin.feed_send_text(&group.feed_id, "modir 1").unwrap();
    admin.feed_send_text(&group.feed_id, "modir 2").unwrap();
    admin.feed_set_slow(&group.feed_id, 0).unwrap();
    assert!(eventually(30, || user.feed_state(&group.feed_id).unwrap().unwrap().slow_s == 0), "and its end");
    user.feed_send_text(&group.feed_id, "se").unwrap();
    for n in [&admin, &user] {
        n.stop();
    }
}
