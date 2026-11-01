//! The public list on our real relays (q6, T70): a card goes, is read back and found, and is
//! withdrawn again at once. Needs the network, so it runs only when asked:
//!
//!     cargo test --release --test directory_live -- --ignored --nocapture

mod common;

use common::*;

/// A channel and a group of a node kept in GEREH_DIR_DEMO (a folder), on our real relays, for a
/// phone's search to find; GEREH_DIR_DEMO_OUT=1 takes them out again.
#[test]
#[ignore]
fn demo_cards_for_a_phone() {
    init_log();
    let Ok(dir) = std::env::var("GEREH_DIR_DEMO") else { return };
    std::fs::create_dir_all(&dir).unwrap();
    let db = std::path::Path::new(&dir).join("chat.db");
    let node = if db.exists() {
        mlmchat_core::node::Node::open(&db, &KEY, mlmchat_core::node::NodeOptions::default()).unwrap()
    } else {
        new_node(std::path::Path::new(&dir), "gereh-search-demo")
    };
    node.set_file_relays(mlmchat_core::transport::nostr::files::relays());
    let mine: Vec<_> = node.groups().unwrap().into_iter().filter(|g| !g.dissolved).collect();
    if mine.is_empty() {
        node.group_create("MLM Search Test Channel", "a channel for testing Gereh's search", true).unwrap();
        node.group_create("MLM Search Test Group", "a group for testing Gereh's search", false).unwrap();
    }
    let out = std::env::var("GEREH_DIR_DEMO_OUT").is_ok();
    // GEREH_DIR_DEMO_PHOTO: a small JPEG every demo chat shows in the list
    let photo = std::env::var("GEREH_DIR_DEMO_PHOTO").ok().and_then(|p| std::fs::read(p).ok());
    for g in node.groups().unwrap() {
        node.dir_set_listed(&g.group_id, !out).unwrap();
        if let Some(j) = &photo {
            node.dir_set_photo(&g.group_id, Some(j)).unwrap();
        }
        println!("{} {}", if out { "out:" } else { "listed:" }, g.name);
    }
    println!("published to {} relay(s)", node.dir_publish().unwrap());
}

#[test]
#[ignore]
fn our_relay_opens() {
    use mlmchat_core::transport::nostr::{files, ws};
    let url = &files::relays()[0];
    for split in [ws::Split::None, ws::Split::Record] {
        match ws::Ws::connect(url, split, std::time::Duration::from_secs(15)) {
            Ok((_, ms)) => println!("{url} {split:?}: open, {ms:?} ms"),
            Err((stage, ms)) => println!("{url} {split:?}: failed at {stage:?}, {ms:?} ms"),
        }
    }
}

#[test]
#[ignore]
fn our_relay_answers_a_card() {
    use mlmchat_core::transport::nostr::{dir, files, ws};
    let url = &files::relays()[0];
    let (mut w, _) = ws::Ws::connect(url, ws::Split::Record, std::time::Duration::from_secs(15)).map_err(|e| format!("{:?}", e.0)).unwrap();
    w.set_read_timeout(std::time::Duration::from_secs(10));
    let gid = mlmchat_core::util::random::<16>();
    let key = dir::key_for(&mlmchat_core::util::random::<32>(), &gid);
    let now = mlmchat_core::util::now_ms() / 1000;
    // a withdrawal: an empty card, shown to no one
    let e = dir::withdrawn(&hex::encode(gid), &key, now);
    let msg = serde_json::json!(["EVENT", e.json]).to_string();
    println!("sent {} bytes: {}", msg.len(), &msg[..msg.len().min(400)]);
    w.send_text(&msg).unwrap();
    for _ in 0..3 {
        match w.recv_text(1 << 20) {
            Ok(m) => println!("got: {m}"),
            Err(e) => {
                println!("recv error: {e}");
                break;
            }
        }
    }
}

#[test]
#[ignore]
fn a_card_goes_to_our_relays_and_back() {
    init_log();
    let dirs: Vec<_> = (0..2).map(|_| tempfile::tempdir().unwrap()).collect();
    let owner = new_node(dirs[0].path(), "probe");
    let reader = new_node(dirs[1].path(), "reader");
    // new_node keeps tests off the network; this one is for our real relays
    for n in [&owner, &reader] {
        n.set_file_relays(mlmchat_core::transport::nostr::files::relays());
    }
    let name = format!("gereh dir probe {}", std::process::id());
    let channel = owner.group_create(&name, "a test card, withdrawn at once", true).unwrap();
    let took = owner.dir_publish().unwrap();
    println!("published to {took} relay(s)");
    if took == 0 {
        for l in dump_log().lines().filter(|l| l.contains("files") || l.contains("directory") || l.contains("nostr") || l.contains("tls")) {
            println!("  {l}");
        }
    }
    assert!(took >= 1, "no relay took the card");
    let kept = reader.dir_refresh().unwrap();
    println!("the reader keeps {kept} card(s)");
    let found = reader.dir_search(&name, 5).unwrap();
    assert_eq!(found.first().map(|r| r.group_id.clone()), Some(channel.clone()), "the card was not read back");
    // out again, for every phone
    std::thread::sleep(std::time::Duration::from_millis(1_100));
    owner.dir_set_listed(&channel, false).unwrap();
    assert!(owner.dir_publish().unwrap() >= 1, "the withdrawal did not go");
    reader.dir_refresh().unwrap();
    assert!(reader.dir_search(&name, 5).unwrap().is_empty(), "the withdrawal was not read");
    println!("withdrawn");
}
