//! Slow mode in a group (q13, T73; SPEC 7.2 `SetSlowMode`): only an admin with the right to ban
//! sets it; a member posts again only once its time has come, and an album counts as one post; the
//! owner and admins are never held back; 0 ends it.

mod common;

use common::*;
use mlmchat_core::envelope::{Body, Envelope};
use mlmchat_core::node::{MediaInfo, Node};
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
fn slow_mode_holds_a_member_back_an_album_is_one_post() {
    init_log();
    let (d1, d2) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let (so, sa) = (o.me().sk, a.me().sk);
    o.add_contact(&a.card_link().unwrap()).unwrap();
    assert!(eventually(20, || a.contact(&so).unwrap().is_some()));
    let gid = o.group_create("goruh", "", false).unwrap();
    o.group_add(&gid, &sa).unwrap();
    assert!(eventually(30, || a.group_state(&gid).unwrap().is_some_and(|s| s.is_member(&sa))), "Ali joins\n{}", dump_log());

    assert!(a.group_set_slow(&gid, 60).is_err(), "a member may not set it");
    assert!(o.group_set_slow(&gid, 3601).is_err(), "at most an hour");
    let st = o.group_set_slow(&gid, 60).unwrap();
    assert_eq!(st.slow_s, 60);
    assert!(o.group_events(&gid).unwrap().iter().any(|e| e.1 == "slow_mode" && e.4));
    assert!(eventually(30, || a.group_state(&gid).unwrap().is_some_and(|s| s.slow_s == 60)), "Ali's phone learns it\n{}", dump_log());
    assert_eq!(a.group_slow_next(&gid).unwrap(), 0, "nothing posted yet");

    // Ali's first post is an album of two photos: one post
    let src = d2.path().join("p.jpg");
    std::fs::write(&src, vec![7u8; 1000]).unwrap();
    let photo = MediaInfo { kind: 1, mime: "image/jpeg".into(), album_id: vec![5; 16], ..Default::default() };
    a.group_send_media(&gid, &src, &photo).unwrap();
    a.group_send_media(&gid, &src, &photo).unwrap();
    // then nothing until its time has come, and the screen is told when
    assert!(a.group_send_text(&gid, "zood").is_err(), "a member waits");
    let other = MediaInfo { album_id: vec![6; 16], ..photo.clone() };
    assert!(a.group_send_media(&gid, &src, &other).is_err(), "another album waits too");
    let next = a.group_slow_next(&gid).unwrap();
    let now = mlmchat_core::util::now_ms();
    assert!(next > now + 50_000 && next <= now + 60_000, "next {next}, now {now}");
    // the owner is never held back
    o.group_send_text(&gid, "yek").unwrap();
    o.group_send_text(&gid, "do").unwrap();
    assert_eq!(o.group_slow_next(&gid).unwrap(), 0);

    let photos = |n: &Node| {
        n.group_messages(&gid, 20)
            .unwrap()
            .iter()
            .filter(|m| m.sender == sa)
            .filter(|m| matches!(Envelope::from_bytes(&m.envelope).ok().and_then(|e| e.body), Some(Body::Media(_))))
            .count()
    };
    assert!(eventually(30, || photos(&o) == 2), "the album reaches the owner whole\n{}", dump_log());
    assert!(eventually(30, || a.group_messages(&gid, 20).unwrap().iter().filter(|m| m.sender == so).count() == 2), "the owner's two reach Ali");

    // ended: Ali posts again at once
    o.group_set_slow(&gid, 0).unwrap();
    assert!(eventually(30, || a.group_state(&gid).unwrap().is_some_and(|s| s.slow_s == 0)), "and its end\n{}", dump_log());
    assert_eq!(a.group_slow_next(&gid).unwrap(), 0);
    a.group_send_text(&gid, "hala").unwrap();
    assert!(eventually(30, || o.group_messages(&gid, 20).unwrap().iter().any(|m| m.sender == sa && matches!(Envelope::from_bytes(&m.envelope).ok().and_then(|e| e.body), Some(Body::Text(_))))));
    o.stop();
    a.stop();
}
