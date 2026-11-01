//! An admin's phone after 1.2.44's flood (Ehsan, 30 Oct 2026: no admin of the official group could
//! open Gereh; on his S26 each contact's status line took 2.2 s, the outbox read whole each time).
//! With 200,000 frames waiting for 300 contacts, the deliver loop's look for due frames and the
//! contacts' status lines stay quick, and the oldest due frames still go first: whether every
//! frame is due, or only the newest ones while the rest wait for a later try. So do a contact seen
//! and the file chunks (the official chats' owner's S26 on 1.2.45, 1 Nov 2026).

use mlmchat_core::store::{OutRow, Store};
use std::time::Instant;

const FRAMES: u32 = 200_000;

fn flooded(due: impl Fn(u32) -> bool) -> (Store, Vec<[u8; 32]>) {
    let mut store = Store::open_in_memory(&[7; 32]).unwrap();
    let peers: Vec<[u8; 32]> = (0..300u32)
        .map(|i| {
            let mut p = [0u8; 32];
            p[..4].copy_from_slice(&i.to_be_bytes());
            p
        })
        .collect();
    store
        .transaction(|db| {
            for i in 0..FRAMES {
                let mut frame_id = [0u8; 16];
                frame_id[..4].copy_from_slice(&i.to_be_bytes());
                let next_ms = if due(i) { 1_000 } else { 9_000_000_000_000 };
                db.enqueue(&OutRow { frame_id, peer: peers[(i % 300) as usize], msg_id: None, frame: vec![0x5a; 1200], attempts: 3, next_ms, last_error: None })?;
            }
            Ok(())
        })
        .unwrap();
    // as the node does after its start, on the phone whose outbox this is (`Db::build_late_indexes`)
    let t = Instant::now();
    store.db().build_late_indexes().unwrap();
    let ms = t.elapsed().as_millis();
    eprintln!("queue indexes built in {ms} ms");
    assert!(ms < 3_000, "the queue indexes took {ms} ms");
    (store, peers)
}

fn first_due(store: &Store, expect_first: u32, expect_last: u32) -> u128 {
    let db = store.db();
    let t = Instant::now();
    let due = db.due(2_000, 400).unwrap();
    let ms = t.elapsed().as_millis();
    assert_eq!(due.len(), 400);
    assert_eq!(&due[0].frame_id[..4], &expect_first.to_be_bytes(), "the oldest due first");
    assert_eq!(&due[399].frame_id[..4], &expect_last.to_be_bytes());
    ms
}

#[test]
fn a_flooded_outbox_stays_quick() {
    // as on the phone after a while: the frames were tried and wait for their next try, and only
    // the newest are due (walking the table in order read every older frame's bytes first)
    let (store, peers) = flooded(|i| i >= FRAMES - 1_000);
    let newest_ms = first_due(&store, FRAMES - 1_000, FRAMES - 601);
    let db = store.db();
    let t = Instant::now();
    for p in peers.iter().take(100) {
        db.queued_for(p).unwrap();
    }
    let status_ms = t.elapsed().as_millis();
    // and at the start, when every frame is due
    let (all, _) = flooded(|_| true);
    let all_ms = first_due(&all, 0, 399);

    eprintln!("due frames: only the newest {newest_ms} ms, all {all_ms} ms; 100 status lines {status_ms} ms");
    assert!(newest_ms < 100, "due frames (only the newest) took {newest_ms} ms");
    assert!(all_ms < 400, "due frames (all) took {all_ms} ms");
    assert!(status_ms < 1000, "100 status lines took {status_ms} ms");
}

/// The owner's S26 on 1.2.45 (Ehsan, 1 Nov 2026: in the official chats' owner's phone everything
/// loaded very late): a contact seen or reached (`on_seen`, `on_reached`) woke its frames by
/// reading the outbox whole and writing every frame of theirs again, and the deliver loop's look
/// for file chunks read every due frame to leave out their contacts, each 1.4 to 3 s under the
/// core's lock, 285 times in 17 minutes. Now a contact seen costs what their own waiting frames do
/// (before, on this computer: 100 contacts seen 27.7 s, seen again 6.9 s, a message's reason
/// 48 ms), and the chunks, their next try and a message's reason go through indexes.
#[test]
fn a_contact_seen_and_file_chunks_stay_quick() {
    // the newest frames are due, but only to the first 100 contacts; the rest wait for a later try
    let (mut store, peers) = flooded(|i| i >= FRAMES - 3_000 && i % 300 < 100);
    let few: Vec<[u8; 32]> = (300..400u32)
        .map(|i| {
            let mut p = [0u8; 32];
            p[..4].copy_from_slice(&i.to_be_bytes());
            p
        })
        .collect();
    let msg = [9u8; 16];
    store
        .transaction(|db| {
            // a message of ours still queued, the newest frame of all
            db.enqueue(&OutRow { frame_id: [0xee; 16], peer: peers[200], msg_id: Some(msg), frame: vec![1; 300], attempts: 2, next_ms: 9_000_000_000_000, last_error: Some("timeout".into()) })?;
            // 100 more contacts with a few frames each, waiting for a later try
            for (n, p) in few.iter().enumerate() {
                for k in 0..5u8 {
                    let frame_id = [0xff, n as u8, k, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
                    db.enqueue(&OutRow { frame_id, peer: *p, msg_id: None, frame: vec![0x5a; 1200], attempts: 1, next_ms: 9_000_000_000_000, last_error: None })?;
                }
            }
            // a file's chunks for every contact
            for j in 0..30_000u32 {
                db.blob_out_add(&peers[(j % 300) as usize], &[7; 32], j)?;
            }
            Ok(())
        })
        .unwrap();
    let db = store.db();
    let nth = |p: &[u8; 32]| u32::from_be_bytes([p[0], p[1], p[2], p[3]]);

    let t = Instant::now();
    let chunks = db.blob_out_due(2_000, 256).unwrap();
    let chunks_ms = t.elapsed().as_millis();
    assert_eq!(chunks.len(), 256);
    assert!(chunks.iter().all(|c| nth(&c.peer) >= 100), "never to a contact with a frame due");
    let t = Instant::now();
    assert_eq!(db.blob_out_next_ms().unwrap(), Some(0));
    let next_ms = t.elapsed().as_millis();
    let t = Instant::now();
    assert_eq!(db.queue_reason(&msg).unwrap(), Some((2, Some("timeout".to_string()))));
    let reason_ms = t.elapsed().as_millis();

    // the 100 contacts with a few frames seen: theirs only are due at once
    let t = Instant::now();
    let woke_few: usize = few.iter().map(|p| db.wake_peer(p).unwrap()).sum();
    let few_ms = t.elapsed().as_millis();
    // 100 contacts with hundreds of frames each seen: those frames are written again, once
    let t = Instant::now();
    let woke: usize = peers[150..250].iter().map(|p| db.wake_peer(p).unwrap()).sum();
    let first_ms = t.elapsed().as_millis();
    // and seen again, as they are every few seconds: nothing more to do
    let t = Instant::now();
    let again: usize = peers[150..250].iter().map(|p| db.wake_peer(p).unwrap()).sum();
    let again_ms = t.elapsed().as_millis();
    eprintln!(
        "file chunks {chunks_ms} ms, their next try {next_ms} ms, a message's reason {reason_ms} ms; 100 contacts seen: with 5 frames {few_ms} ms, with {} {first_ms} ms, again {again_ms} ms",
        FRAMES / 300
    );
    assert_eq!(woke_few, 500);
    let theirs = (0..FRAMES).filter(|i| (150..250).contains(&(i % 300))).count();
    assert_eq!(woke, theirs + 1, "every frame of theirs, our message too");
    assert_eq!(again, 0);
    assert!(db.blob_out_due(2_000, 256).unwrap().iter().all(|c| !(150..250).contains(&nth(&c.peer))), "now they have frames due");

    assert!(chunks_ms < 50, "file chunks took {chunks_ms} ms");
    assert!(next_ms < 10, "the chunks' next try took {next_ms} ms");
    assert!(reason_ms < 10, "a message's reason took {reason_ms} ms");
    assert!(few_ms < 100, "100 contacts with a few frames seen took {few_ms} ms");
    assert!(again_ms < 50, "100 contacts seen again took {again_ms} ms");
}
