//! Files between two phones (SPEC 12, 17; phase 4): a small photo comes on its own; a larger file
//! comes when the receiver asks. Both arrive whole, checked against their id, and open to the
//! very bytes that were sent. Nothing readable crosses the wire.

mod common;

use common::*;
use mlmchat_core::envelope::{Body, Envelope};
use mlmchat_core::node::{Event, MediaInfo, Node};
use std::sync::Arc;
use std::time::Duration;

fn up(dir: &std::path::Path, name: &str) -> Node {
    let n = new_node(dir, name);
    n.start_direct(loopback(Vec::new())).unwrap();
    n
}

/// The key and id of the newest media message from `peer`.
fn media_of(n: &Node, peer: &[u8; 32]) -> Option<([u8; 32], [u8; 32], u64)> {
    let rows = n.history(peer, 20).ok()?;
    rows.iter().rev().find_map(|r| match Envelope::from_bytes(&r.envelope).ok()?.body {
        Some(Body::Media(m)) => Some((m.key.as_slice().try_into().ok()?, m.blob_id.as_slice().try_into().ok()?, m.size)),
        _ => None,
    })
}

fn whole(rx: &std::sync::mpsc::Receiver<Event>, seen: &mut Vec<Event>, id: &[u8; 32], secs: u64) -> bool {
    wait_for(rx, seen, secs, |e| matches!(e, Event::Blob { id: i, have, total } if i == id && have == total).then_some(())).is_some()
}

#[test]
fn a_photo_comes_on_its_own_and_a_big_file_when_asked() {
    init_log();
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(da.path(), "ali");
    let b = up(db.path(), "sara");
    let rx_b = b.events();
    let mut eb = Vec::new();
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let sa = a.me().sk;

    // a 300 KB "photo": comes by itself
    let photo: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
    let src = da.path().join("photo.jpg");
    std::fs::write(&src, &photo).unwrap();
    let info = MediaInfo { kind: 1, mime: "image/jpeg".into(), thumb: vec![1, 2, 3], width: 800, height: 600, ..Default::default() };
    a.send_media(&b.me().sk, &src, &info).unwrap();
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Message { peer, kind: "media", .. } if *peer == sa).then_some(())).is_some(), "{eb:?}");
    let (key, id, _) = media_of(&b, &sa).expect("the message carries the key");
    assert!(whole(&rx_b, &mut eb, &id, 30), "the photo arrives whole: {eb:?}
{}", dump_log());
    let out = db.path().join("got.jpg");
    assert_eq!(b.open_media(&id, &key, &out).unwrap(), photo.len() as u64);
    assert_eq!(std::fs::read(&out).unwrap(), photo);

    // a 3 MB file: only its message comes, until Sara asks
    let big: Vec<u8> = (0..3_000_000u32).map(|i| (i.wrapping_mul(31) % 253) as u8).collect();
    let src = da.path().join("doc.pdf");
    std::fs::write(&src, &big).unwrap();
    let info = MediaInfo { kind: 0, mime: "application/pdf".into(), name: "doc.pdf".into(), ..Default::default() };
    let mid = a.send_media(&b.me().sk, &src, &info).unwrap();
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Message { id, .. } if *id == mid).then_some(())).is_some());
    let (key, id, _) = media_of(&b, &sa).unwrap();
    std::thread::sleep(Duration::from_secs(2));
    let (have, total, done) = b.media_status(&id).unwrap().unwrap();
    assert_eq!((have, done), (0, false), "not fetched before asked");
    assert_eq!(total, 47);
    assert!(b.fetch_media(&id).unwrap());
    assert!(whole(&rx_b, &mut eb, &id, 60), "the file arrives whole once asked");
    let out = db.path().join("got.pdf");
    assert_eq!(b.open_media(&id, &key, &out).unwrap(), big.len() as u64);
    assert_eq!(std::fs::read(&out).unwrap(), big);
    // a wrong key opens nothing
    assert!(b.open_media(&id, &[9; 32], &db.path().join("x")).is_err());
    a.stop();
    b.stop();
}

#[test]
fn nothing_readable_on_the_wire() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = new_node(da.path(), "ali");
    let b = new_node(db.path(), "sara");
    // B listens on a known port and every card of B names only the recording proxy
    let port = free_tcp();
    let capture = Arc::new(Capture::default());
    let proxy = recording_proxy(port, capture.clone());
    let mut cfg = loopback(vec![proxy]);
    cfg.listen = port;
    b.start_direct(cfg).unwrap();
    a.start_direct(loopback(Vec::new())).unwrap();
    let rx_b = b.events();
    let mut eb = Vec::new();
    // A reaches B only through the recording proxy
    let link = b.card_link_for(&[proxy]).unwrap();
    a.add_contact(&link).unwrap();
    let marker = b"MLMCHAT-PLAIN-FILE-MARKER-0123456789";
    let mut data = marker.repeat(3000);
    data.truncate(100_000);
    let src = da.path().join("secret.txt");
    std::fs::write(&src, &data).unwrap();
    a.send_media(&b.me().sk, &src, &MediaInfo { kind: 0, name: "secret.txt".into(), ..Default::default() }).unwrap();
    let sa = a.me().sk;
    assert!(wait_for(&rx_b, &mut eb, 20, |e| matches!(e, Event::Message { peer, .. } if *peer == sa).then_some(())).is_some());
    let (_, id, _) = media_of(&b, &sa).unwrap();
    assert!(whole(&rx_b, &mut eb, &id, 30), "{eb:?}");
    let wire: Vec<u8> = capture.all().concat();
    assert!(wire.len() > 100_000, "the file went through the proxy");
    for plain in [&marker[..16], b"secret.txt"] {
        assert!(!wire.windows(plain.len()).any(|w| w == plain), "{} in the clear", String::from_utf8_lossy(plain));
    }
    a.stop();
    b.stop();
}

/// Photos sent together (Ehsan, 28 Oct 2026: several files at once failed) carry one album id
/// (SPEC 5 `Media.album_id`), so the other phone shows them together; a photo sent on its own
/// carries none, and an id longer than an album's is cut.
#[test]
fn files_sent_together_carry_one_album_id() {
    init_log();
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(da.path(), "ali");
    let b = up(db.path(), "sara");
    let rx_b = b.events();
    let mut eb = Vec::new();
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let sa = a.me().sk;
    let album = vec![7u8; 16];
    let mut ids = Vec::new();
    for (n, album_id) in [(1u8, album.clone()), (2, album.clone()), (3, Vec::new()), (4, vec![5u8; 40])] {
        let src = da.path().join(format!("p{n}.jpg"));
        std::fs::write(&src, vec![n; 1000]).unwrap();
        let info = MediaInfo { kind: 1, mime: "image/jpeg".into(), album_id, ..Default::default() };
        ids.push(a.send_media(&b.me().sk, &src, &info).unwrap());
    }
    let albums = |n: &Node| -> Vec<Vec<u8>> {
        n.history(&sa, 10)
            .unwrap()
            .iter()
            .filter_map(|r| match Envelope::from_bytes(&r.envelope).ok()?.body {
                Some(Body::Media(m)) => Some(m.album_id),
                _ => None,
            })
            .collect()
    };
    assert!(wait_for(&rx_b, &mut eb, 20, |_| (albums(&b).len() == 4).then_some(())).is_some() || albums(&b).len() == 4, "all four arrived\n{}", dump_log());
    assert_eq!(albums(&b), vec![album.clone(), album, Vec::new(), vec![5u8; 16]]);
    a.stop();
    b.stop();
}
