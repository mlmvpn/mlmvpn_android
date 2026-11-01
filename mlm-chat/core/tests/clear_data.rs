//! «پاک کردن دادهٔ گره» (q3, Ehsan, 31 Oct 2026): every message goes, one-to-one and in groups,
//! with the files only they carried, while the account, its groups and its profile photo stay.

mod common;

use common::*;
use mlmchat_core::envelope::{Body, Envelope};
use mlmchat_core::node::MediaInfo;

#[test]
fn messages_and_their_files_go_the_account_and_its_groups_stay() {
    init_log();
    let dir = tempfile::tempdir().unwrap();
    let n = new_node(dir.path(), "ehsan");
    let me = n.me().sk;
    let src = dir.path().join("photo.jpg");
    std::fs::write(&src, vec![7u8; 50_000]).unwrap();
    let info = MediaInfo { kind: 1, mime: "image/jpeg".into(), thumb: vec![1, 2, 3], width: 10, height: 10, ..Default::default() };

    // Saved Messages: a text and a photo
    n.send_text(&me, "yaddasht").unwrap();
    n.send_media(&me, &src, &info).unwrap();
    let blob_of = |env: &[u8]| match Envelope::from_bytes(env).unwrap().body {
        Some(Body::Media(m)) => <[u8; 32]>::try_from(m.blob_id.as_slice()).ok(),
        _ => None,
    };
    let saved = n.history(&me, 10).unwrap();
    assert_eq!(saved.len(), 2);
    let saved_blob = saved.iter().find_map(|r| blob_of(&r.envelope)).expect("the photo's file");

    // a group of one: a text and a photo
    let gid = n.group_create("khane", "", false).unwrap();
    n.group_send_text(&gid, "salam").unwrap();
    n.group_send_media(&gid, &src, &info).unwrap();
    let group_blob = n.group_messages(&gid, 10).unwrap().iter().find_map(|r| blob_of(&r.envelope)).expect("the group photo message's file");

    // a profile photo, which is no message's
    let photo = n.set_photo(Some(&src), &[1, 2, 3]).unwrap().expect("a photo");

    assert!(n.media_status(&saved_blob).unwrap().is_some());
    assert!(n.media_status(&group_blob).unwrap().is_some());
    assert_eq!(n.clear_all_messages().unwrap(), 4);

    assert!(n.history(&me, 10).unwrap().is_empty(), "Saved Messages empty");
    assert!(n.group_messages(&gid, 10).unwrap().iter().all(|r| r.kind == "deleted"), "the group's messages taken down");
    assert!(n.media_status(&saved_blob).unwrap().is_none(), "the photo's file is gone");
    assert!(n.media_status(&group_blob).unwrap().is_none(), "and the group photo message's");
    assert!(n.media_status(&photo.blob_id).unwrap().is_some(), "the profile photo stays");
    let blob_files = std::fs::read_dir(dir.path().join("blobs")).map(|d| d.count()).unwrap_or(0);
    assert_eq!(blob_files, 1, "only the profile photo's file is left on disk");
    assert_eq!(n.groups().unwrap().len(), 1, "the group stays");
    assert_eq!(n.me().sk, me, "and the account");

    // a new message after it gets a new seq, never one Telegram knew
    n.send_text(&me, "taze").unwrap();
    let after = n.history(&me, 10).unwrap();
    assert_eq!(after.len(), 1);
    assert!(after[0].seq > saved.iter().map(|r| r.seq).max().unwrap(), "seq not given again");
}
