//! Profile, group and channel photos (SPEC 10, 7.2; step 6.2): three nodes on loopback.
//! A profile photo reaches a contact, a contact added later, and a group member who is not a
//! contact; removing it reaches them too. A group photo reaches every member, from whoever set
//! it; a member without the right cannot change it; a rename keeps it.

mod common;

use common::*;
use mlmchat_core::node::{Event, Node};
use std::sync::mpsc::Receiver;
use std::time::Duration;

fn up(dir: &std::path::Path, name: &str) -> Node {
    let n = new_node(dir, name);
    n.start_direct(loopback(Vec::new())).unwrap();
    n
}

/// Polls until `f` holds (up to `secs`).
fn eventually(secs: u64, mut f: impl FnMut() -> bool) -> bool {
    for _ in 0..secs * 10 {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    false
}

/// The plain bytes of a whole file this node knows by its id, or None while it is on its way.
fn opened(n: &Node, id: &[u8; 32], short: i64, out: &std::path::Path) -> Option<Vec<u8>> {
    let (_, _, whole) = n.media_status(id).ok()??;
    if !whole {
        return None;
    }
    let (_, key, _) = n.media_by_short(short).ok()??;
    n.open_media(id, &key, out).ok()?;
    std::fs::read(out).ok()
}

fn profile_from(rx: &Receiver<Event>, seen: &mut Vec<Event>, who: &[u8; 32], secs: u64) -> bool {
    wait_for(rx, seen, secs, |e| matches!(e, Event::Profile { peer } if peer == who).then_some(())).is_some()
}

fn image(seed: u32, len: u32) -> Vec<u8> {
    (0..len).map(|i| (i.wrapping_mul(seed) % 251) as u8).collect()
}

#[test]
fn a_profile_photo_reaches_contacts_old_and_new() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(d1.path(), "ali");
    let b = up(d2.path(), "sara");
    let c = up(d3.path(), "reza");
    let (rx_b, rx_c) = (b.events(), c.events());
    let (mut eb, mut ec) = (Vec::new(), Vec::new());
    let sa = a.me().sk;
    a.add_contact(&b.card_link().unwrap()).unwrap();
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some()), "B knows A");

    // A sets a photo: B gets the profile, then the file
    let photo = image(7, 120_000);
    let src = d1.path().join("me.jpg");
    std::fs::write(&src, &photo).unwrap();
    let mine = a.set_photo(Some(&src), &[1, 2, 3]).unwrap().expect("a photo");
    assert_eq!(a.photo_of(&sa).unwrap().as_ref(), Some(&mine), "our own photo is kept under our key");
    assert!(profile_from(&rx_b, &mut eb, &sa, 20), "B gets A's profile: {eb:?}\n{}", dump_log());
    let theirs = b.photo_of(&sa).unwrap().expect("B has A's photo");
    assert_eq!((theirs.blob_id, theirs.short, theirs.thumb.as_slice()), (mine.blob_id, mine.short, &[1u8, 2, 3][..]));
    let out = d2.path().join("a.jpg");
    assert!(eventually(30, || opened(&b, &theirs.blob_id, theirs.short, &out).as_deref() == Some(&photo[..])), "B opens A's photo");

    // C adds A later: A answers with the photo too
    c.add_contact(&a.card_link().unwrap()).unwrap();
    assert!(profile_from(&rx_c, &mut ec, &sa, 30), "a new contact gets the photo: {ec:?}");
    let p = c.photo_of(&sa).unwrap().unwrap();
    let out = d3.path().join("a.jpg");
    assert!(eventually(30, || opened(&c, &p.blob_id, p.short, &out).as_deref() == Some(&photo[..])));

    // A removes it: both learn
    eb.clear();
    assert!(a.set_photo(None, &[]).unwrap().is_none());
    assert!(profile_from(&rx_b, &mut eb, &sa, 20));
    assert!(b.photo_of(&sa).unwrap().is_none(), "the photo is gone on B");
    assert!(eventually(20, || c.photo_of(&sa).unwrap().is_none()), "and on C");
    a.stop();
    b.stop();
    c.stop();
}

#[test]
fn a_group_photo_reaches_every_member() {
    init_log();
    let (d1, d2, d3) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let b = up(d3.path(), "sara");
    o.add_contact(&a.card_link().unwrap()).unwrap();
    o.add_contact(&b.card_link().unwrap()).unwrap();
    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &a.me().sk).unwrap();
    o.group_add(&gid, &b.me().sk).unwrap();
    let three = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 3);
    assert!(eventually(30, || three(&a) && three(&b)), "every member sees three members");

    // the owner sets the photo: both members get it from the owner
    let photo = image(13, 90_000);
    let src = d1.path().join("g.jpg");
    std::fs::write(&src, &photo).unwrap();
    let st = o.group_set_photo(&gid, Some(&src), &[4, 5]).unwrap();
    let (p, by) = st.photo.clone().expect("the group has a photo");
    assert_eq!(by, o.me().sk);
    let id: [u8; 32] = p.blob_id.as_slice().try_into().unwrap();
    let short = mlmchat_core::node::media_short_id(&id);
    for (n, d) in [(&a, d2.path()), (&b, d3.path())] {
        assert!(eventually(30, || n.group_state(&gid).unwrap().is_some_and(|s| s.photo.as_ref().map(|x| &x.0) == Some(&p))), "a member sees the photo\n{}", dump_log());
        let out = d.join("g.jpg");
        assert!(eventually(30, || opened(n, &id, short, &out).as_deref() == Some(&photo[..])), "a member opens the photo");
    }

    // a member without the right cannot change it; a rename keeps it
    let other = d2.path().join("x.jpg");
    std::fs::write(&other, image(3, 1000)).unwrap();
    assert!(a.group_set_photo(&gid, Some(&other), &[]).is_err(), "only who may change the info");
    let st = o.group_set_info(&gid, "doostan-e khoob", "").unwrap();
    assert_eq!(st.photo.map(|x| x.0), Some(p.clone()), "a rename keeps the photo");
    assert!(eventually(20, || b.group_state(&gid).unwrap().is_some_and(|s| s.name == "doostan-e khoob" && s.photo.is_some())));

    // A's profile photo reaches B, who is only a fellow member
    let me_a = d2.path().join("ali.jpg");
    std::fs::write(&me_a, image(5, 40_000)).unwrap();
    let pa = a.set_photo(Some(&me_a), &[]).unwrap().unwrap();
    assert!(eventually(30, || b.photo_of(&a.me().sk).unwrap().is_some_and(|x| x.blob_id == pa.blob_id)), "a member sees a member's photo\n{}", dump_log());

    // the owner removes the group photo
    o.group_set_photo(&gid, None, &[]).unwrap();
    assert!(eventually(20, || b.group_state(&gid).unwrap().is_some_and(|s| s.photo.is_none())));
    o.stop();
    a.stop();
    b.stop();
}
