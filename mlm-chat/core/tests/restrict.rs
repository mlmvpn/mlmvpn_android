//! One member's own permissions (SPEC 7.2 `Restrict`; Ehsan, 28 Oct 2026: a member's permissions
//! could not be changed): an admin takes the photo right from a member until a time; that member's
//! phone does not post a photo while it stands but still posts text, the other phones do not take
//! one from them, and lifting it gives the right back. Only an admin with the right to ban does
//! it, and never to an admin.

mod common;

use common::*;
use mlmchat_core::envelope::{Body, Envelope};
use mlmchat_core::group::{member, Op, Restrict};
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
fn an_admin_takes_one_members_photo_right_and_gives_it_back() {
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

    // no right without the right to ban, and never on the owner or an admin
    assert!(a.group_op(&gid, Op::Restrict(Restrict { sk: so.to_vec(), rights: 0, until_ms: u64::MAX })).is_err());
    let far = u64::MAX;
    let st = o.group_op(&gid, Op::Restrict(Restrict { sk: sa.to_vec(), rights: member::DEFAULT & !member::PHOTO, until_ms: far })).unwrap();
    let now = mlmchat_core::util::now_ms();
    assert_eq!(st.member_rights(&sa, now) & member::PHOTO, 0);
    assert_ne!(st.member_rights(&sa, now) & member::TEXT, 0);
    let restricted = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.member_rights(&sa, mlmchat_core::util::now_ms()) & member::PHOTO == 0);
    assert!(eventually(30, || restricted(&a)), "Ali's phone learns it\n{}", dump_log());

    // Ali's phone posts no photo now, but text
    let src = d2.path().join("p.jpg");
    std::fs::write(&src, vec![7u8; 1000]).unwrap();
    let photo = MediaInfo { kind: 1, mime: "image/jpeg".into(), ..Default::default() };
    assert!(a.group_send_media(&gid, &src, &photo).is_err(), "no photo while it stands");
    let file = MediaInfo { kind: 0, name: "f.bin".into(), ..Default::default() };
    a.group_send_media(&gid, &src, &file).unwrap();
    a.group_send_text(&gid, "salam").unwrap();
    let kinds = |n: &Node| -> Vec<i32> {
        n.group_messages(&gid, 20)
            .unwrap()
            .iter()
            .filter(|m| m.sender == sa)
            .filter_map(|m| match Envelope::from_bytes(&m.envelope).ok()?.body {
                Some(Body::Media(x)) => Some(x.kind),
                Some(Body::Text(_)) => Some(-1),
                _ => None,
            })
            .collect()
    };
    assert!(eventually(30, || kinds(&o).len() == 2), "the file and the text reach the owner\n{}", dump_log());
    assert_eq!(kinds(&o), vec![0, -1]);

    // lifted: the photo goes again
    o.group_op(&gid, Op::Restrict(Restrict { sk: sa.to_vec(), rights: u32::MAX, until_ms: 0 })).unwrap();
    assert!(eventually(30, || !restricted(&a)), "Ali's phone learns it is lifted\n{}", dump_log());
    a.group_send_media(&gid, &src, &photo).unwrap();
    assert!(eventually(30, || kinds(&o).contains(&1)), "the photo reaches the owner\n{}", dump_log());
    o.stop();
    a.stop();
}
