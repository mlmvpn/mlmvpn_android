//! Formatting and mentions (SPEC 5 `Entity`; 26 Oct 2026: a mention or a link in a message showed
//! only while it was being sent - on the other phone, and on the sender's after the chat was
//! opened again, it was plain text, since no layer carried the entities). They now travel with
//! the text and a file's caption, in UTF-16 offsets as Telegram counts them, and an entity the
//! envelope checks would refuse is dropped from the send rather than failing the message.

mod common;

use common::*;
use mlmchat_core::envelope::{clean_entities, Body, Entity, EntityType, Envelope};
use mlmchat_core::node::{MediaInfo, Node};
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

fn entity(kind: EntityType, offset: u32, length: u32) -> Entity {
    Entity { r#type: kind as i32, offset, length, ..Default::default() }
}

fn text_entities(envelope: &[u8]) -> Vec<Entity> {
    match Envelope::from_bytes(envelope).unwrap().body {
        Some(Body::Text(t)) => t.entities,
        Some(Body::Media(m)) => m.caption_entities,
        _ => Vec::new(),
    }
}

#[test]
fn only_entities_the_envelope_takes_are_kept() {
    // «سلام 👋 علی»: the emoji is two UTF-16 units, so the text is 11 units long
    let text = "سلام 👋 علی";
    assert_eq!(text.encode_utf16().count(), 11);
    let good = entity(EntityType::Bold, 8, 3);
    let mention = Entity { mention_sk: vec![7; 32], ..entity(EntityType::Mention, 8, 3) };
    let kept = clean_entities(
        text,
        vec![
            good.clone(),
            entity(EntityType::Italic, 9, 3),                                // runs past the end
            Entity { r#type: 99, ..entity(EntityType::Bold, 0, 1) },        // no such type
            Entity { mention_sk: vec![1; 5], ..entity(EntityType::Mention, 0, 4) }, // a short key
            entity(EntityType::Code, 2, 0),                                  // covers nothing
            mention.clone(),
        ],
    );
    assert_eq!(kept, vec![good, mention]);
    let env = Envelope::rich_text(text, vec![entity(EntityType::Strike, 0, 12)]);
    assert!(text_entities(&env.to_bytes().unwrap()).is_empty(),"an entity past the text is dropped, the text still goes");
}

#[test]
fn formatting_and_mentions_reach_the_other_phone() {
    init_log();
    let (d1, d2) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let o = up(d1.path(), "owner");
    let a = up(d2.path(), "ali");
    let (so, sa) = (o.me().sk, a.me().sk);
    o.add_contact(&a.card_link().unwrap()).unwrap();
    assert!(eventually(20, || a.contact(&so).unwrap().is_some()));

    // one to one: a bold word, a link and a mention of Ali, in answer to nothing
    let text = "salam @ali, see this";
    let sent = vec![
        entity(EntityType::Bold, 0, 5),
        Entity { mention_sk: sa.to_vec(), ..entity(EntityType::Mention, 6, 4) },
        Entity { url: "https://example.org/x".into(), ..entity(EntityType::Link, 16, 4) },
    ];
    let id = o.send_text_rich(&sa, text, sent.clone(), 0).unwrap();
    let row = |n: &Node, peer, id| n.history(peer, 20).unwrap().into_iter().find(|m| m.id == id);
    assert_eq!(text_entities(&row(&o, &sa, id).unwrap().envelope), sent, "the sender's own copy keeps them");
    assert!(eventually(20, || row(&a, &so, id).is_some()), "the message arrived\n{}", dump_log());
    assert_eq!(text_entities(&row(&a, &so, id).unwrap().envelope), sent, "and so does Ali's");

    // with a reply too
    let ali_seq = row(&a, &so, id).unwrap().seq;
    let back = a.send_text_rich(&so, "merci", vec![entity(EntityType::Italic, 0, 5)], ali_seq).unwrap();
    assert!(eventually(20, || row(&o, &sa, back).is_some()), "the answer arrived\n{}", dump_log());
    let got = row(&o, &sa, back).unwrap();
    assert_eq!(text_entities(&got.envelope), vec![entity(EntityType::Italic, 0, 5)]);
    assert_eq!(Envelope::from_bytes(&got.envelope).unwrap().reply_to, id.to_vec());

    // a file's caption
    let src = d1.path().join("photo.jpg");
    std::fs::write(&src, vec![9u8; 2000]).unwrap();
    let info = MediaInfo {
        kind: 1,
        mime: "image/jpeg".into(),
        caption: "dar shiraz".into(),
        caption_entities: vec![entity(EntityType::Underline, 4, 6), entity(EntityType::Bold, 4, 60)],
        ..Default::default()
    };
    let photo = o.send_media(&sa, &src, &info).unwrap();
    assert!(eventually(20, || row(&a, &so, photo).is_some()), "the photo arrived\n{}", dump_log());
    assert_eq!(text_entities(&row(&a, &so, photo).unwrap().envelope), vec![entity(EntityType::Underline, 4, 6)]);

    // a group: a mention of the owner
    let gid = o.group_create("doostan", "", false).unwrap();
    o.group_add(&gid, &sa).unwrap();
    let both = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == 2 && s.members.values().all(|m| m.own_card));
    assert!(eventually(30, || both(&o) && both(&a)), "both members settled\n{}", dump_log());
    let grow = |n: &Node, id| n.group_messages(&gid, 20).unwrap().into_iter().find(|m| m.id == id);
    let tagged = vec![Entity { mention_sk: so.to_vec(), ..entity(EntityType::Mention, 0, 6) }];
    let post = a.group_send_text_rich(&gid, "@owner biya", tagged.clone(), 0).unwrap();
    assert!(eventually(30, || grow(&o, post).is_some()), "the post arrived\n{}", dump_log());
    assert_eq!(text_entities(&grow(&o, post).unwrap().envelope), tagged);

    for n in [&o, &a] {
        n.stop();
    }
}
