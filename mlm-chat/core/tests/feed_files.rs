//! The official chats' files on our own relay (SPEC 18.3, T63; Ehsan, 29 Oct 2026: with hundreds
//! in the official group, no photo, voice or file there ever downloaded). The phone that posts
//! leaves the sealed file on our relay at once; a phone that is nobody's contact, and read the post
//! from the official copy, reads the file there and has the very same bytes. What the relay holds
//! is sealed.

mod common;

use common::gas_emu::Emu;
use common::nostr_emu::Relay;
use common::*;
use mlmchat_core::envelope::{Body, Envelope};
use mlmchat_core::feed::{self, FeedDescriptor};
use mlmchat_core::node::{media_short_id, MediaInfo, Node};
use mlmchat_core::transport::gas::http::Endpoint;
use mlmchat_core::transport::gas::proto::{share_link, RelayRef};
use mlmchat_core::transport::gas::GasConfig;
use mlmchat_core::transport::nostr::files;
use std::time::Duration;

const ID: &str = "AKfycbEmuFiles0abcdefghijklmnopqrstuvwxyz0123456789";
const USE: [u8; 16] = [0x5b; 16];
const CANARY: &[u8] = b"QNARY-7731 aks-e gorooh-e rasmi";

fn cfg(emu: &Emu) -> GasConfig {
    GasConfig {
        endpoint: Some(Endpoint::Plain(emu.addr)),
        wait_ms: 1500,
        idle_soon: Duration::from_millis(300),
        idle_late: Duration::from_millis(300),
        request_timeout: Duration::from_secs(5),
        pending_tick: Duration::from_millis(200),
        pending_first: Duration::from_millis(400),
        ..Default::default()
    }
}

fn wait(what: &str, mut ok: impl FnMut() -> bool) {
    let done = (0..300).any(|_| {
        if ok() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
        false
    });
    assert!(done, "{what}\n{}", dump_log());
}

fn file_events(relay: &Relay) -> Vec<serde_json::Value> {
    relay.events().into_iter().filter(|e| e["kind"] == files::KIND).collect()
}

#[test]
fn a_photo_in_the_official_group_comes_from_our_relay_to_a_phone_with_no_contact() {
    init_log();
    let emu = Emu::start(Duration::from_secs(60));
    emu.add(ID, &hex::encode(USE), &hex::encode([0x6c; 16]));
    let link = share_link(&RelayRef::new(ID, USE).unwrap(), "official");
    let relay = Relay::start();
    let (owner_secret, owner) = feed::new_owner_key();
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let dirs: Vec<_> = (0..2).map(|_| tempfile::tempdir().unwrap()).collect();
    let poster = new_node(dirs[0].path(), "poster");
    let reader = new_node(dirs[1].path(), "reader");
    let seed = vec![feed::sign_admins(&owner_secret, &group.feed_id, 1, &[poster.me().sk], 1).unwrap()];
    for n in [&poster, &reader] {
        n.set_feeds(vec![group.clone()], &seed).unwrap();
        n.set_feed_mirror(Some(&link)).unwrap();
        n.set_file_relays(vec![relay.url()]);
        n.start_gas(cfg(&emu)).unwrap();
    }

    // a photo of about 150 KB (three chunks) posted in the group
    let mut plain: Vec<u8> = (0..150_000u32).map(|i| (i * 31 % 251) as u8).collect();
    plain[1000..1000 + CANARY.len()].copy_from_slice(CANARY);
    let photo = dirs[0].path().join("photo.jpg");
    std::fs::write(&photo, &plain).unwrap();
    let info = MediaInfo { kind: 1, mime: "image/jpeg".into(), width: 800, height: 600, ..Default::default() };
    poster.feed_send_media(&group.feed_id, &photo, &info).unwrap();

    // its sealed parts reach our relay at once: two halves of each full chunk, the last alone
    wait("the photo reached our relay", || file_events(&relay).len() == 5);
    for e in file_events(&relay) {
        let sealed = mlmchat_core::util::b64url_decode(e["content"].as_str().unwrap()).unwrap();
        assert!(!sealed.windows(CANARY.len()).any(|w| w == CANARY), "the relay holds sealed bytes only");
        assert_eq!(e["tags"][4][1], hex::encode(&group.feed_id), "the official chat it belongs to");
    }

    // the reader, nobody's contact, reads the post from the official copy
    wait("the post reached the copy", || emu.feed_len(ID) >= 1);
    reader.mirror_sync_now();
    let post = reader.feed_state(&group.feed_id).unwrap().unwrap().posts.pop().expect("the post came");
    let Some(Body::Media(m)) = Envelope::from_bytes(&post.envelope).unwrap().body else { panic!("a photo post") };
    let id: [u8; 32] = m.blob_id.as_slice().try_into().unwrap();
    // its file can be asked for, though the post came from the copy and names no one to ask
    let (_, key, _) = reader.media_by_short(media_short_id(&id)).unwrap().expect("the key is known by its short id");
    assert!(reader.fetch_media(&id).unwrap());
    wait("the photo came whole from our relay", || reader.media_status(&id).unwrap() == Some((3, 3, true)));
    let out = dirs[1].path().join("out.jpg");
    reader.open_media(&id, &key, &out).unwrap();
    assert_eq!(std::fs::read(&out).unwrap(), plain, "the very same photo");

    for n in [&poster, &reader] {
        n.stop();
    }
}

/// A file someone else wrote on our relay under the photo's name opens with nothing: each chunk
/// is checked with the file's key and the whole with its id. The reader then has no file rather
/// than a wrong one.
#[test]
fn a_spoiled_copy_on_the_relay_is_never_taken() {
    init_log();
    let relay = Relay::start();
    let dir = tempfile::tempdir().unwrap();
    let (owner_secret, owner) = feed::new_owner_key();
    let group = FeedDescriptor { feed_id: vec![2; 16], broadcast: false, name: "gereh-chat".into(), owner };
    let poster = new_node(dir.path(), "poster");
    let seed = vec![feed::sign_admins(&owner_secret, &group.feed_id, 1, &[poster.me().sk], 1).unwrap()];
    poster.set_feeds(vec![group.clone()], &seed).unwrap();
    poster.set_file_relays(vec![relay.url()]);
    // another phone's photo: what the poster leaves is its own, and it never reads what another wrote
    let photo = dir.path().join("p.jpg");
    std::fs::write(&photo, vec![7u8; 70_000]).unwrap();
    poster.feed_send_media(&group.feed_id, &photo, &MediaInfo { kind: 1, mime: "image/jpeg".into(), ..Default::default() }).unwrap();
    wait("the photo reached our relay", || file_events(&relay).len() == 3);
    let mut evs = file_events(&relay);
    let id: [u8; 32] = hex::decode(evs[0]["tags"][0][1].as_str().unwrap()).unwrap().try_into().unwrap();
    // flip a byte in a part: it no longer opens with the key
    let ev = evs.remove(0);
    let mut sealed = mlmchat_core::util::b64url_decode(ev["content"].as_str().unwrap()).unwrap();
    sealed[10] ^= 1;
    let part = files::Part { index: 0, half: 0, bytes: sealed, created: 1 };
    let (_, key, size) = poster.media_by_short(media_short_id(&id)).unwrap().unwrap();
    let mut parts: Vec<files::Part> = evs.iter().filter_map(|e| files::open_part(e, &id)).collect();
    parts.push(part);
    let chunks = files::chunks_of(&parts, &key, 2, size);
    assert_eq!(chunks.iter().map(|(i, _)| *i).collect::<Vec<_>>(), vec![1], "only the untouched chunk is taken");
    poster.stop();
}

/// Our real relay inside Iran (T54) takes an official file's parts and gives them back: run by hand
/// after the relay's write policy changes (`cargo test --release --test feed_files -- --ignored`).
/// It leaves a few kilobytes of random sealed bytes there, kept two weeks.
#[test]
#[ignore]
fn our_real_relay_takes_and_gives_an_official_file() {
    let dir = tempfile::tempdir().unwrap();
    let (src, dst) = (dir.path().join("a"), dir.path().join("a.blob"));
    std::fs::write(&src, mlmchat_core::util::random::<32>().repeat(64)).unwrap();
    let s = mlmchat_core::blob::seal_file(&src, &dst).unwrap();
    let group = hex::decode("d4249e84b11c00236edf28621d63575e").unwrap();
    let sealed = mlmchat_core::blob::read_chunk(&dst, 0, s.chunks, s.size).unwrap();
    let events = files::events_for(&s.blob_id, &group, 0, s.chunks, &sealed, mlmchat_core::util::now_ms() / 1000);
    let url = &files::relays()[0];
    let mut w = files::Writer::open(url, Duration::from_secs(20)).expect("our relay opens");
    assert_eq!(w.write(&events), events.len(), "our relay takes every part");
    w.close();
    let parts = files::fetch(url, &s.blob_id, &[0], Duration::from_secs(20)).expect("our relay answers");
    let chunks = files::chunks_of(&parts, &s.key, s.chunks, s.size);
    assert_eq!(chunks, vec![(0, sealed)], "and gives the same sealed chunk back");
}
