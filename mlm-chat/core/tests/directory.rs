//! The public list of channels and groups (q6, T70; Ehsan, 30 Oct 2026): an owner's phone puts its
//! chats' cards on the relay, any phone reads them and finds them by their words, the best first,
//! and an owner can take a chat out again.

mod common;

use common::nostr_emu::Relay;
use common::*;
use mlmchat_core::transport::nostr::dir;

#[test]
fn a_chat_is_found_by_its_words_and_can_be_taken_out() {
    init_log();
    let relay = Relay::start();
    let dirs: Vec<_> = (0..2).map(|_| tempfile::tempdir().unwrap()).collect();
    let owner = new_node(dirs[0].path(), "ehsan");
    let reader = new_node(dirs[1].path(), "reza");
    for n in [&owner, &reader] {
        n.set_file_relays(vec![relay.url()]);
    }
    let channel = owner.group_create("کانفیگ رایگان روزانه", "هر روز کانفیگ تازه", true).unwrap();
    let group = owner.group_create("گروه کانفیگ", "پرسش و پاسخ", false).unwrap();
    assert!(owner.dir_listed(&channel).unwrap(), "every chat is listed until its owner says no");
    // the channel's photo, small, goes with its card (Ehsan, 31 Oct 2026: «مثل خود تلگرام عکس»)
    let jpeg = [vec![0xff, 0xd8, 0xff, 0xe0], vec![7u8; 9000]].concat();
    assert!(owner.dir_set_photo(&channel, Some(&jpeg)).unwrap());
    assert!(!owner.dir_set_photo(&channel, Some(&jpeg)).unwrap(), "the same photo again changes nothing");
    assert!(owner.dir_set_photo(&channel, Some(&vec![0u8; dir::MAX_PHOTO + 1])).is_err());
    assert!(owner.dir_publish_due());
    assert_eq!(owner.dir_publish().unwrap(), 1, "the relay took the cards");
    assert!(!owner.dir_publish_due(), "renewed only every few hours");

    assert!(reader.dir_stale());
    assert_eq!(reader.dir_refresh().unwrap(), 2);
    assert!(!reader.dir_stale());
    let found = reader.dir_search("کانفيگ", 10).unwrap();
    assert_eq!(found.len(), 2, "both, in Arabic letters too");
    assert_eq!(found[0].group_id, channel, "the name that starts with the words first");
    assert!(found[0].broadcast && !found[1].broadcast);
    assert!(!found[0].member);
    // the photo is read only for chats a search shows, and only the one the card names
    assert_eq!(found[0].photo, dir::photo_hash(&jpeg));
    assert!(found[1].photo.is_empty());
    assert!(reader.dir_photo(&channel).unwrap().is_none());
    assert_eq!(reader.dir_fetch_photos(&[channel.clone(), group.clone()]).unwrap(), 1);
    assert_eq!(reader.dir_photo(&channel).unwrap(), Some(jpeg.clone()));
    assert_eq!(reader.dir_fetch_photos(&[channel.clone()]).unwrap(), 0, "kept: not read again");
    // a channel's link takes anyone, a group's asks its admins
    assert!(!reader.invite_info(&found[0].link).unwrap().need_approval);
    assert!(reader.invite_info(&found[1].link).unwrap().need_approval);
    assert!(reader.dir_search("آشپزی", 10).unwrap().is_empty());
    // the owner's own phone finds its chats as its own
    owner.dir_refresh().unwrap();
    assert!(owner.dir_search("گروه کانفیگ", 10).unwrap()[0].member);

    // the owner takes the group out: gone from the list at the next look
    assert!(reader.dir_set_listed(&group, false).is_err(), "only the owner");
    std::thread::sleep(std::time::Duration::from_millis(1_100));
    owner.dir_set_listed(&group, false).unwrap();
    assert!(owner.dir_publish_due(), "a change goes at once");
    owner.dir_publish().unwrap();
    reader.dir_refresh().unwrap();
    let found = reader.dir_search("کانفیگ", 10).unwrap();
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].group_id, channel);
}
