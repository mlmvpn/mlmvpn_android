//! Lifting a ban without taking the member back (SPEC 7.2 `Unban`, T59; before it, the only way was
//! adding them again): an admin with the right to ban lifts it, on every phone; a member or an admin
//! without that right cannot; lifting a ban that is not there changes nothing; once lifted, the
//! member is not in the group but may be added again.

mod common;

use common::*;
use mlmchat_core::group::admin;
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
fn an_admin_who_may_ban_lifts_a_ban() {
    init_log();
    let (d1, d2, d3, d4) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    let c = up(d4.path(), "reza");
    let (so, sa, sb, sc) = (o.me().sk, a.me().sk, b.me().sk, c.me().sk);
    for n in [&a, &b, &c] {
        o.add_contact(&n.card_link().unwrap()).unwrap();
    }
    assert!(eventually(20, || [&a, &b, &c].iter().all(|n| n.contact(&so).unwrap().is_some())));
    let gid = o.group_create("doostan", "", false).unwrap();
    for sk in [&sa, &sb, &sc] {
        o.group_add(&gid, sk).unwrap();
    }
    let four = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 4);
    assert!(eventually(40, || four(&b) && four(&c)), "every member sees four\n{}", dump_log());
    // Sara is an admin who may ban, Reza one who may not
    o.group_set_admin(&gid, &sb, true, admin::BAN, "").unwrap();
    o.group_set_admin(&gid, &sc, true, admin::CHANGE_INFO, "").unwrap();
    let ranks = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.admin_rights(&sb) == admin::BAN && s.admin_rights(&sc) == admin::CHANGE_INFO);
    assert!(eventually(30, || ranks(&b) && ranks(&c)), "{}", dump_log());

    // the owner bans Ali
    o.group_remove(&gid, &sa, true).unwrap();
    let banned = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.banned.contains_key(&sa) && !s.is_member(&sa));
    assert!(eventually(30, || banned(&b) && banned(&c)), "{}", dump_log());

    // Reza may not lift it
    assert!(c.group_unban(&gid, &sa).is_err(), "an admin without the right to ban");
    // Sara may: off the list on every phone, and Ali is still no member
    let st = b.group_unban(&gid, &sa).unwrap();
    assert!(!st.banned.contains_key(&sa) && !st.banned_cards.contains_key(&sa) && !st.is_member(&sa));
    let lifted = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| !s.banned.contains_key(&sa) && !s.is_member(&sa));
    assert!(eventually(30, || lifted(&o) && lifted(&c)), "every phone lifts it\n{}", dump_log());
    let events = o.group_events(&gid).unwrap();
    assert!(events.iter().any(|(_, kind, by, _, ok)| *kind == "unban" && *by == sb && *ok), "«recent actions» name it");
    // a ban that is not there is not lifted
    assert!(b.group_unban(&gid, &sa).is_err());
    // once lifted, Ali may be added again
    o.group_add(&gid, &sa).unwrap();
    assert!(eventually(30, || a.group_state(&gid).unwrap().is_some_and(|s| s.is_member(&sa))), "Ali is back\n{}", dump_log());

    for n in [&o, &a, &b, &c] {
        n.stop();
    }
}
