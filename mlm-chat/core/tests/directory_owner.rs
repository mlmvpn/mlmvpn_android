//! Only a chat's owner puts it in the public list (q6, T70; Ehsan, 31 Oct 2026: the owner always
//! has the last word, and with no word from the owner nothing goes): an admin's or a member's phone
//! puts nothing up, the owner's choice reaches every member in the chat's info, and the owner's
//! «out» takes the chat out of every search.

mod common;

use common::nostr_emu::Relay;
use common::*;
use mlmchat_core::group::admin;
use mlmchat_core::node::Node;
use std::time::Duration;

fn up(dir: &std::path::Path, name: &str, relay: &Relay) -> Node {
    let n = new_node(dir, name);
    n.start_direct(loopback(Vec::new())).unwrap();
    n.set_file_relays(vec![relay.url()]);
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
fn only_the_owner_lists_and_its_out_reaches_everyone() {
    init_log();
    let relay = Relay::start();
    let dirs: Vec<_> = (0..4).map(|_| tempfile::tempdir().unwrap()).collect();
    let o = up(dirs[0].path(), "owner", &relay);
    let a = up(dirs[1].path(), "ali", &relay);
    let m = up(dirs[2].path(), "sara", &relay);
    let r = up(dirs[3].path(), "reza", &relay);
    let (so, sa, sm) = (o.me().sk, a.me().sk, m.me().sk);
    for n in [&a, &m] {
        o.add_contact(&n.card_link().unwrap()).unwrap();
    }
    assert!(eventually(20, || [&a, &m].iter().all(|n| n.contact(&so).unwrap().is_some())));
    let gid = o.group_create("goruh-e malek", "", false).unwrap();
    for sk in [&sa, &sm] {
        o.group_add(&gid, sk).unwrap();
    }
    let three = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3);
    assert!(eventually(40, || three(&a) && three(&m)), "every member sees three\n{}", dump_log());
    o.group_set_admin(&gid, &sa, true, admin::INVITE, "").unwrap();
    assert!(eventually(30, || a.group_state(&gid).unwrap().is_some_and(|s| s.admin_rights(&sa) == admin::INVITE)), "{}", dump_log());

    // an admin's and a member's phone put nothing up: there is no card until the owner's
    assert_eq!(a.dir_publish().unwrap(), 0);
    assert_eq!(m.dir_publish().unwrap(), 0);
    r.dir_refresh().unwrap_or(0);
    assert!(r.dir_search("goruh", 10).unwrap().is_empty());
    assert!(a.group_state(&gid).unwrap().unwrap().listed, "listed until the owner says no");
    assert!(a.dir_set_listed(&gid, false).is_err(), "only the owner chooses");
    assert_eq!(o.dir_publish().unwrap(), 1);
    r.dir_refresh().unwrap();
    assert_eq!(r.dir_search("goruh", 10).unwrap().len(), 1);

    // the owner takes it out: every member learns it, and the only card goes
    std::thread::sleep(Duration::from_millis(1_100));
    o.dir_set_listed(&gid, false).unwrap();
    assert!(eventually(30, || [&a, &m].iter().all(|n| n.group_state(&gid).unwrap().is_some_and(|s| !s.listed))), "{}", dump_log());
    o.dir_publish().unwrap();
    assert_eq!(a.dir_publish().unwrap(), 0);
    r.dir_refresh().unwrap();
    assert!(r.dir_search("goruh", 10).unwrap().is_empty(), "out of every search");
}
