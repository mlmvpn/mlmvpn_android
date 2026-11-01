//! Invite links (SPEC 7.4; step 6.3): a stranger joins a group by its link, without being anyone's
//! contact; a link that needs approval waits for an admin, who approves or declines; a revoked
//! link lets no one in; a channel's link makes a reader.

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

fn member_of(n: &Node, gid: &[u8]) -> bool {
    let me = n.me().sk;
    n.group_state(gid).unwrap().is_some_and(|s| s.is_member(&me))
}

#[test]
fn a_stranger_joins_by_link_and_talks() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    o.add_contact(&a.card_link().unwrap()).unwrap();
    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &a.me().sk).unwrap();
    assert!(eventually(20, || member_of(&a, &gid)));

    // the owner makes a link; Sara, nobody's contact, reads it and joins
    let link = o.group_invite_create(&gid, false, 0, 0).unwrap();
    assert!(link.starts_with("mlmchat://g/"));
    let info = b.invite_info(&link).unwrap();
    assert_eq!((info.name.as_str(), info.broadcast, info.need_approval, info.member), ("doostan", false, false, false));
    assert_eq!(info.inviter, o.me().sk);
    b.group_join(&link).unwrap();
    assert!(b.invite_info(&link).unwrap().asked, "the join waits");
    assert!(eventually(30, || member_of(&b, &gid)), "Sara is in\n{}", dump_log());
    assert!(b.joining().unwrap().is_empty(), "nothing waits once in");
    let three = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3);
    assert!(eventually(30, || three(&o) && three(&a)), "everyone sees three");
    assert!(o.contacts().unwrap().iter().all(|c| c.sk != b.me().sk), "a joiner stays out of the contact list");
    assert!(b.contacts().unwrap().is_empty(), "and the inviter out of the joiner's");
    assert_eq!(o.group_invites(&gid).unwrap()[0].uses, 1);

    // Sara talks to Ali, who is not her contact either
    let rx_a = a.events();
    let mut ea = Vec::new();
    b.group_send_text(&gid, "salam, man sara hastam").unwrap();
    let got = wait_for(&rx_a, &mut ea, 30, |e| match e {
        Event::GroupMessage { gid: g, text, .. } if *g == gid => text.clone(),
        _ => None,
    });
    assert_eq!(got.as_deref(), Some("salam, man sara hastam"), "{ea:?}\n{}", dump_log());
    o.stop();
    a.stop();
    b.stop();
}

#[test]
fn approval_revoke_and_a_channel() {
    init_log();
    let dirs: Vec<_> = (0..4).map(|_| tempfile::tempdir().unwrap()).collect();
    let o = up(dirs[0].path(), "owner");
    let c = up(dirs[1].path(), "reza");
    let d = up(dirs[2].path(), "mina");
    let e = up(dirs[3].path(), "omid");
    let rx_o = o.events();
    let mut eo = Vec::new();
    let gid = o.group_create("khanevadeh", "", false).unwrap();

    // a link that needs approval: Reza's request waits, then the owner approves it
    let link = o.group_invite_create(&gid, true, 0, 0).unwrap();
    assert!(c.invite_info(&link).unwrap().need_approval);
    c.group_join(&link).unwrap();
    let asked = wait_for(&rx_o, &mut eo, 30, |ev| matches!(ev, Event::JoinRequest { gid: g, .. } if *g == gid).then_some(()));
    assert!(asked.is_some(), "the owner sees the request: {eo:?}\n{}", dump_log());
    std::thread::sleep(Duration::from_secs(2));
    assert!(!member_of(&c, &gid), "not in before approval");
    let reqs = o.group_join_requests(&gid).unwrap();
    assert_eq!(reqs.len(), 1);
    assert_eq!(reqs[0].sk, c.me().sk);
    o.group_join_decide(&gid, &c.me().sk, true).unwrap();
    assert!(eventually(30, || member_of(&c, &gid)), "approved: Reza is in");
    assert!(o.group_join_requests(&gid).unwrap().is_empty());

    // Mina asks too, and is declined
    d.group_join(&link).unwrap();
    assert!(eventually(30, || o.group_join_requests(&gid).unwrap().len() == 1));
    o.group_join_decide(&gid, &d.me().sk, false).unwrap();
    std::thread::sleep(Duration::from_secs(3));
    assert!(!member_of(&d, &gid), "declined: Mina is out");

    // a revoked link lets no one in
    let open = o.group_invite_create(&gid, false, 0, 0).unwrap();
    let open_id = mlmchat_core::group::InviteLink::from_link(&open).unwrap().invite_id;
    assert!(o.group_invites(&gid).unwrap().iter().any(|v| v.invite_id == open_id && v.link.is_some()));
    o.group_invite_revoke(&gid, &open_id).unwrap();
    assert!(o.group_invites(&gid).unwrap().iter().all(|v| v.invite_id != open_id));
    e.group_join(&open).unwrap();
    std::thread::sleep(Duration::from_secs(4));
    assert!(!member_of(&e, &gid), "a revoked link does nothing");

    // a channel: the link makes a reader, who cannot post
    let cid = o.group_create("akhbar", "", true).unwrap();
    let clink = o.group_invite_create(&cid, false, 0, 0).unwrap();
    assert!(e.invite_info(&clink).unwrap().broadcast);
    e.group_join(&clink).unwrap();
    assert!(eventually(30, || member_of(&e, &cid)), "Omid reads the channel\n{}", dump_log());
    assert!(e.group_send_text(&cid, "man ham").is_err(), "a reader does not post");
    for n in [&o, &c, &d, &e] {
        n.stop();
    }
}
