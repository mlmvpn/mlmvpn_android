//! Official feeds (T38; step 6.4): an official channel and an official group that ship with the
//! app. Four nodes in a line (A–B–C, D joins later): an admin's post reaches the far end through
//! the middle; only admins post in the channel; anyone posts in the group; an admin takes a post
//! down and bans; a phone that comes later catches up; an admin list not signed by the owner
//! changes nothing; the member count spreads to phones that never met.

mod common;

use common::*;
use mlmchat_core::feed::{self, FeedDescriptor};
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

#[test]
fn official_channel_and_group_spread_and_obey() {
    init_log();
    let dirs: Vec<_> = (0..4).map(|_| tempfile::tempdir().unwrap()).collect();
    let a = up(dirs[0].path(), "ehsan");
    let b = up(dirs[1].path(), "ali");
    let c = up(dirs[2].path(), "sara");
    let d = up(dirs[3].path(), "reza");

    // the owner key stays offline; it signs the admin list (Ehsan) that ships with the app
    let (owner_secret, owner) = feed::new_owner_key();
    let channel = FeedDescriptor { feed_id: vec![1; 16], broadcast: true, name: "gereh-news".into(), owner };
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let mut seed = Vec::new();
    for f in [&channel, &group] {
        seed.push(feed::sign_admins(&owner_secret, &f.feed_id, 1, &[a.me().sk], 1).unwrap());
    }
    for n in [&a, &b, &c, &d] {
        n.set_feeds(vec![channel.clone(), group.clone()], &seed).unwrap();
        assert!(n.feed_state(&channel.feed_id).unwrap().unwrap().is_admin(&a.me().sk));
    }
    assert_eq!(a.feed_member_count().unwrap(), 1, "alone at first");

    // a line: A–B and B–C are contacts; A and C are not
    a.add_contact(&b.card_link().unwrap()).unwrap();
    c.add_contact(&b.card_link().unwrap()).unwrap();
    let sb = b.me().sk;
    assert!(eventually(20, || a.contact(&sb).unwrap().is_some() && c.contact(&sb).unwrap().is_some()));
    // sessions both ways, so B counts both as reached lately: B's receipts got back to both
    a.send_text(&sb, "salam").unwrap();
    c.send_text(&sb, "salam").unwrap();
    let delivered = |n: &Node| n.history(&sb, 5).unwrap().iter().any(|m| m.outgoing && matches!(m.state, mlmchat_core::store::MsgState::Delivered | mlmchat_core::store::MsgState::Read));
    assert!(eventually(30, || delivered(&a) && delivered(&c)), "B answered both\n{}", dump_log());

    // the admin posts in the channel: it reaches C through B
    let rx_c = c.events();
    let mut ec = Vec::new();
    a.feed_send_text(&channel.feed_id, "khabar-e aval").unwrap();
    let got = wait_for(&rx_c, &mut ec, 30, |e| matches!(e, Event::Feed { feed_id } if *feed_id == channel.feed_id).then_some(()));
    assert!(got.is_some(), "C hears the channel: {ec:?}\n{}", dump_log());
    assert!(eventually(20, || texts(&c, &channel.feed_id) == ["khabar-e aval"]));

    // only admins post in the channel
    assert!(b.feed_send_text(&channel.feed_id, "man ham").is_err());

    // anyone posts in the group
    let pid = c.feed_send_text(&group.feed_id, "salam az sara").unwrap();
    assert!(eventually(30, || texts(&a, &group.feed_id) == ["salam az sara"]), "A sees Sara's post\n{}", dump_log());

    // the admin takes it down and bans Sara
    a.feed_delete(&group.feed_id, &pid).unwrap();
    assert!(eventually(30, || texts(&c, &group.feed_id).is_empty()), "the post is gone on C");
    a.feed_ban(&group.feed_id, &c.me().sk, u64::MAX).unwrap();
    assert!(eventually(30, || c.feed_state(&group.feed_id).unwrap().unwrap().banned.contains_key(&c.me().sk)));
    assert!(c.feed_send_text(&group.feed_id, "dobare").is_err(), "a banned member does not post");

    // an admin list signed by someone else changes nothing
    let (fake_secret, _) = feed::new_owner_key();
    let fake = feed::sign_admins(&fake_secret, &channel.feed_id, 9, &[b.me().sk], 2).unwrap();
    b.set_feeds(vec![channel.clone(), group.clone()], &[fake]).unwrap();
    assert!(!b.feed_state(&channel.feed_id).unwrap().unwrap().is_admin(&sb));

    // Reza comes later, knows only Sara, and catches up
    d.add_contact(&c.card_link().unwrap()).unwrap();
    let sd = d.me().sk;
    assert!(eventually(20, || c.contact(&sd).unwrap().is_some()));
    d.send_text(&c.me().sk, "salam").unwrap();
    assert!(eventually(20, || c.history(&sd, 5).unwrap().len() >= 1));
    d.feed_sync_now();
    assert!(eventually(30, || texts(&d, &channel.feed_id) == ["khabar-e aval"]), "D caught up\n{}", dump_log());

    // the official group's photo: an admin sets it, every phone has it and fetches the file
    let photo: Vec<u8> = (0..40_000u32).map(|i| (i % 249) as u8).collect();
    let src = dirs[0].path().join("logo.jpg");
    std::fs::write(&src, &photo).unwrap();
    assert!(c.feed_set_photo(&group.feed_id, Some(&src), &[1, 2]).is_err(), "only admins set it");
    a.feed_set_photo(&group.feed_id, Some(&src), &[1, 2, 3]).unwrap();
    let photo_of = |n: &Node| n.feed_state(&group.feed_id).unwrap().unwrap().photo;
    assert!(eventually(30, || photo_of(&d).is_some_and(|(_, by)| by == a.me().sk)), "the photo reached the far end\n{}", dump_log());
    let (p, _) = photo_of(&d).unwrap();
    assert_eq!(p.thumb, vec![1, 2, 3]);
    let id: [u8; 32] = p.blob_id.as_slice().try_into().unwrap();
    assert!(eventually(30, || d.media_status(&id).unwrap().is_some_and(|(have, total, done)| have == total && done)), "D fetched the photo whole
{}", dump_log());

    // the name and description (Ehsan, 25 Oct 2026: renaming did nothing): an admin's, on every phone
    assert!(c.feed_set_meta(&group.feed_id, "goruh-e man", "").is_err(), "only admins rename");
    a.feed_set_meta(&group.feed_id, "Gereh rasmi", "goruh-e hame").unwrap();
    let meta = |n: &Node| n.feed_state(&group.feed_id).unwrap().map(|s| (s.title, s.about)).unwrap_or_default();
    assert!(eventually(30, || meta(&d) == (Some("Gereh rasmi".into()), Some("goruh-e hame".into()))), "the name reached the far end
{}", dump_log());

    // reactions to a channel post: anyone's, one each, the newest standing, an empty one taking it back
    let post_of = |n: &Node| n.feed_state(&channel.feed_id).unwrap().unwrap().posts[0].clone();
    let pid = post_of(&d).id;
    let sd = d.me().sk;
    d.feed_react(&channel.feed_id, &pid, "👍").unwrap();
    b.feed_react(&channel.feed_id, &pid, "❤").unwrap();
    let sorted = |mut r: Vec<(mlmchat_core::UserId, String)>| {
        r.sort();
        r
    };
    let want = sorted(vec![(sd, "👍".to_string()), (sb, "❤".to_string())]);
    assert!(eventually(30, || sorted(post_of(&a).reactions) == want), "A sees both: {:?}
{}", post_of(&a).reactions, dump_log());
    std::thread::sleep(Duration::from_millis(5));
    d.feed_react(&channel.feed_id, &pid, "🔥").unwrap();
    let want = sorted(vec![(sd, "🔥".to_string()), (sb, "❤".to_string())]);
    assert!(eventually(30, || sorted(post_of(&a).reactions) == want), "the newer one stands");
    std::thread::sleep(Duration::from_millis(5));
    d.feed_react(&channel.feed_id, &pid, "").unwrap();
    assert!(eventually(30, || post_of(&a).reactions == vec![(sb, "❤".to_string())]), "taken back");
    assert!(d.feed_react(&channel.feed_id, &[9; 16], "👍").is_err(), "no reaction to a post that is not there");

    // Ehsan, 25 Oct 2026 («کاربر جدید عضو گره میشه واردشون نمیشه»): a new phone catches up by
    // itself, without anyone asking, as soon as it reaches its first contact
    let de = tempfile::tempdir().unwrap();
    let e = up(de.path(), "naser");
    e.set_feeds(vec![channel.clone(), group.clone()], &seed).unwrap();
    e.add_contact(&a.card_link().unwrap()).unwrap();
    let se = e.me().sk;
    assert!(eventually(20, || a.contact(&se).unwrap().is_some()));
    e.send_text(&a.me().sk, "salam").unwrap();
    assert!(eventually(70, || texts(&e, &channel.feed_id) == ["khabar-e aval"]), "the new phone caught up
{}", dump_log());
    assert!(eventually(30, || meta(&e).0.as_deref() == Some("Gereh rasmi")), "with the name");
    e.stop();

    // the member count (census.rs): each phone counts itself, and the sketches spread along the
    // line with the catch-up until every phone counts all four (A and D never met). Four tokens
    // may share a counter (about 0.3%), so three is the floor, the same on every phone.
    let all = [&a, &b, &c, &d];
    let counts = || all.iter().map(|n| n.feed_member_count().unwrap()).collect::<Vec<u64>>();
    let mut spread = false;
    for _ in 0..40 {
        for n in all {
            n.feed_sync_now();
        }
        std::thread::sleep(Duration::from_millis(750));
        let k = counts();
        if k.iter().all(|x| *x == k[0]) && k[0] >= 3 {
            spread = true;
            break;
        }
    }
    assert!(spread, "counts {:?}\n{}", counts(), dump_log());
    for n in [&a, &b, &c, &d] {
        n.stop();
    }
}

/// The owner key kept on one phone (Ehsan's main phone, 27 Oct 2026) names admins from the app:
/// the new list is signed one version up, replaces the old one on a contact's phone, and takes an
/// admin off again the same way. A key that owns no feed is refused, and without the key nothing
/// can be named.
#[test]
fn the_owner_key_on_a_phone_names_admins() {
    init_log();
    let dirs: Vec<_> = (0..2).map(|_| tempfile::tempdir().unwrap()).collect();
    let a = up(dirs[0].path(), "ehsan");
    let b = up(dirs[1].path(), "ali");
    let (owner_secret, owner) = feed::new_owner_key();
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let seed = vec![feed::sign_admins(&owner_secret, &group.feed_id, 1, &[a.me().sk], 1).unwrap()];
    for n in [&a, &b] {
        n.set_feeds(vec![group.clone()], &seed).unwrap();
    }
    // a session both ways, so each counts the other as reached lately and passes items on
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let (sa, sb) = (a.me().sk, b.me().sk);
    assert!(eventually(20, || a.contact(&sb).unwrap().is_some()));
    a.send_text(&sb, "salam").unwrap();
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some()));
    b.send_text(&sa, "salam").unwrap();
    let delivered = |n: &Node, to: &[u8; 32]| n.history(to, 5).unwrap().iter().any(|m| m.outgoing && matches!(m.state, mlmchat_core::store::MsgState::Delivered | mlmchat_core::store::MsgState::Read));
    assert!(eventually(30, || delivered(&a, &sb) && delivered(&b, &sa)), "both ways\n{}", dump_log());

    assert!(!a.feed_owner_held(&group.feed_id));
    assert!(a.feed_set_admins(&group.feed_id, &[sa, sb]).is_err(), "no owner key here yet");
    let (other, _) = feed::new_owner_key();
    assert!(a.feed_owner_import(&other).is_err(), "a key that owns no feed");
    a.feed_owner_import(&owner_secret).unwrap();
    assert!(a.feed_owner_held(&group.feed_id));
    assert!(!b.feed_owner_held(&group.feed_id));

    a.feed_set_admins(&group.feed_id, &[sa, sb, sb]).unwrap();
    let st = a.feed_state(&group.feed_id).unwrap().unwrap();
    assert_eq!(st.admins_version, 2);
    assert_eq!(st.admins, vec![sa, sb], "in order, once each");
    assert!(eventually(30, || b.feed_state(&group.feed_id).unwrap().unwrap().is_admin(&sb)), "the new list reached b\n{}", dump_log());

    a.feed_set_admins(&group.feed_id, &[sa]).unwrap();
    assert_eq!(a.feed_state(&group.feed_id).unwrap().unwrap().admins_version, 3);
    assert!(eventually(30, || !b.feed_state(&group.feed_id).unwrap().unwrap().is_admin(&sb)), "b is off again\n{}", dump_log());
    for n in [&a, &b] {
        n.stop();
    }
}

/// Editing a post in an official feed (T60; Ehsan, 27 Oct 2026): the author's newest edit stands
/// on every phone it reaches; someone else cannot edit it, and an edit item they sign anyway
/// (here handed straight to a phone) changes nothing.
#[test]
fn the_author_edits_an_official_post_and_only_the_author() {
    init_log();
    let dirs: Vec<_> = (0..2).map(|_| tempfile::tempdir().unwrap()).collect();
    let a = up(dirs[0].path(), "ehsan");
    let b = up(dirs[1].path(), "ali");
    let (owner_secret, owner) = feed::new_owner_key();
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let seed = vec![feed::sign_admins(&owner_secret, &group.feed_id, 1, &[a.me().sk], 1).unwrap()];
    for n in [&a, &b] {
        n.set_feeds(vec![group.clone()], &seed).unwrap();
    }
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let (sa, sb) = (a.me().sk, b.me().sk);
    assert!(eventually(20, || a.contact(&sb).unwrap().is_some()));
    a.send_text(&sb, "salam").unwrap();
    assert!(eventually(20, || b.contact(&sa).unwrap().is_some()));
    b.send_text(&sa, "salam").unwrap();
    let delivered = |n: &Node, to: &[u8; 32]| n.history(to, 5).unwrap().iter().any(|m| m.outgoing && matches!(m.state, mlmchat_core::store::MsgState::Delivered | mlmchat_core::store::MsgState::Read));
    assert!(eventually(30, || delivered(&a, &sb) && delivered(&b, &sa)), "both ways\n{}", dump_log());

    let post = a.feed_send_text(&group.feed_id, "salam be hame").unwrap();
    assert!(eventually(30, || texts(&b, &group.feed_id) == vec!["salam be hame".to_string()]), "the post reached b\n{}", dump_log());

    a.feed_edit(&group.feed_id, &post, "salam be hame, dorost shod", Vec::new()).unwrap();
    let edited = |n: &Node| n.feed_state(&group.feed_id).unwrap().unwrap().posts.iter().find(|p| p.id == post).map(|p| p.edited_ms > 0).unwrap_or(false);
    assert_eq!(texts(&a, &group.feed_id), vec!["salam be hame, dorost shod".to_string()]);
    assert!(edited(&a));
    assert!(eventually(30, || texts(&b, &group.feed_id) == vec!["salam be hame, dorost shod".to_string()] && edited(&b)), "the edit reached b\n{}", dump_log());

    // not b's post: refused on b, and an edit someone else signs anyway counts nowhere
    assert!(b.feed_edit(&group.feed_id, &post, "hacked", Vec::new()).is_err());
    let mallory = mlmchat_core::identity::Identity::new("mallory").unwrap();
    let forged = mlmchat_core::feed::FeedItem {
        feed_id: group.feed_id.clone(),
        id: vec![9; 16],
        author: mallory.sk().to_vec(),
        ts_ms: mlmchat_core::util::now_ms(),
        name: "mallory".into(),
        card: Vec::new(),
        device: Vec::new(),
        client: None,
        item: Some(mlmchat_core::feed::Item::Edit(mlmchat_core::envelope::Edit {
            target: post.clone(),
            text: Some(mlmchat_core::envelope::Text { body: "hacked".into(), ..Default::default() }),
            caption: String::new(),
        })),
    };
    let signed = mlmchat_core::feed::SignedFeedItem::sign(&forged, |m| mallory.sign(m));
    a.set_feeds(vec![group.clone()], &[signed]).unwrap();
    assert_eq!(texts(&a, &group.feed_id), vec!["salam be hame, dorost shod".to_string()], "a forged edit changes nothing");
    for n in [&a, &b] {
        n.stop();
    }
}
