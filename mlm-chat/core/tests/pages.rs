//! A chat is read a page at a time (Ehsan, 28 Oct 2026: «کندی چت شخصی نسبت به گروه»): a page
//! before or after a seq, one message by its id or its seq, what came since a seq across every
//! chat, and what a chat list shows besides the last message, all without reading a chat whole.
//! The same for a group.

mod common;

use common::*;
use mlmchat_core::node::{Event, Node};
use mlmchat_core::store::MsgState;
use std::time::{Duration, Instant};

fn eventually(secs: u64, mut f: impl FnMut() -> bool) -> bool {
    let end = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < end {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    false
}

fn up(dir: &std::path::Path, name: &str) -> Node {
    let n = new_node(dir, name);
    n.start_direct(loopback(Vec::new())).unwrap();
    n
}

fn text(m: &[u8]) -> String {
    use mlmchat_core::envelope::{Body, Envelope};
    match Envelope::from_bytes(m).ok().and_then(|e| e.body) {
        Some(Body::Text(t)) => t.body,
        _ => String::new(),
    }
}

#[test]
fn a_chat_is_read_a_page_at_a_time() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(da.path(), "ali");
    let b = up(db.path(), "sara");
    let (sa, sb) = (a.me().sk, b.me().sk);
    a.add_contact(&b.card_link().unwrap()).unwrap();
    const N: usize = 60;
    for i in 0..N {
        a.send_text(&sb, &format!("m{i}")).unwrap();
    }
    assert!(eventually(60, || b.history(&sa, 1000).unwrap().len() == N), "B has every message");

    // the newest page is the newest messages, oldest first, as `history` gives them
    let newest = b.history_page(&sa, 0, -1, 10).unwrap();
    let texts: Vec<String> = newest.iter().map(|m| text(&m.envelope)).collect();
    assert_eq!(texts, (50..60).map(|i| format!("m{i}")).collect::<Vec<_>>());
    let whole = b.history(&sa, 10).unwrap();
    assert_eq!(newest.iter().map(|m| m.id).collect::<Vec<_>>(), whole.iter().map(|m| m.id).collect::<Vec<_>>());
    assert!(newest.windows(2).all(|w| w[0].seq < w[1].seq));

    // older: the page before the first one shown
    let older = b.history_page(&sa, newest[0].seq, -1, 10).unwrap();
    assert_eq!(older.iter().map(|m| text(&m.envelope)).collect::<Vec<_>>(), (40..50).map(|i| format!("m{i}")).collect::<Vec<_>>());
    // newer: the page after a seq, oldest first
    let newer = b.history_page(&sa, 0, older[4].seq, 3).unwrap();
    assert_eq!(newer.iter().map(|m| text(&m.envelope)).collect::<Vec<_>>(), ["m45", "m46", "m47"]);
    // the oldest page ends at the first message, with no message out of reach
    let first = b.history_page(&sa, older[0].seq, -1, 100).unwrap();
    assert_eq!(first.len(), 40);
    assert_eq!(text(&first[0].envelope), "m0");
    // and «after 0» starts from that first message
    let start = b.history_page(&sa, 0, 0, 2).unwrap();
    assert_eq!(start.iter().map(|m| text(&m.envelope)).collect::<Vec<_>>(), ["m0", "m1"]);

    // one message, by its id or by its seq; never one of another chat
    let m = &newest[3];
    let by_id = b.message(&sa, Some(&m.id), 0).unwrap().expect("by id");
    assert_eq!((by_id.seq, text(&by_id.envelope)), (m.seq, "m53".to_string()));
    assert_eq!(b.message(&sa, None, m.seq).unwrap().map(|r| r.id), Some(m.id));
    assert!(b.message(&[9; 32], Some(&m.id), 0).unwrap().is_none());
    assert!(b.message(&[9; 32], None, m.seq).unwrap().is_none());

    // what came since a seq, across every chat, oldest first
    let since = b.messages_since(newest[4].seq, 100).unwrap();
    assert_eq!(since.iter().map(|m| text(&m.envelope)).collect::<Vec<_>>(), ["m55", "m56", "m57", "m58", "m59"]);
    assert!(since.iter().all(|m| m.peer == sa && !m.outgoing));

    // the chat list's marks, counted and not read
    let marks = b.chat_marks(&sa).unwrap();
    assert_eq!((marks.unread, marks.read_in, marks.read_out), (N as u32, 0, 0));
    assert_eq!(b.mark_read(&sa).unwrap(), N);
    let marks = b.chat_marks(&sa).unwrap();
    assert_eq!((marks.unread, marks.read_in), (0, newest[9].seq));
    // A sees «read» on its newest message once B's receipts come
    let last_a = a.history_page(&sb, 0, -1, 1).unwrap()[0].seq;
    assert!(eventually(30, || a.chat_marks(&sb).unwrap().read_out == last_a), "read receipts: {:?}", a.chat_marks(&sb));
    assert_eq!(a.history_page(&sb, 0, -1, 1).unwrap()[0].state, MsgState::Read);
    a.stop();
    b.stop();
}

#[test]
fn a_group_is_read_a_page_at_a_time() {
    let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    let a = up(da.path(), "ali");
    let b = up(db.path(), "sara");
    let rx_b = b.events();
    let mut eb = Vec::new();
    a.add_contact(&b.card_link().unwrap()).unwrap();
    let gid = a.group_create("safhe", "", false).unwrap();
    a.group_add(&gid, &b.me().sk).unwrap();
    assert!(wait_for(&rx_b, &mut eb, 30, |e| matches!(e, Event::Group { gid: g } if *g == gid).then_some(())).is_some());
    const N: usize = 30;
    for i in 0..N {
        a.group_send_text(&gid, &format!("g{i}")).unwrap();
    }
    assert!(eventually(60, || b.group_messages(&gid, 1000).unwrap().len() == N), "B has every post");

    let newest = b.group_messages_page(&gid, 0, -1, 5).unwrap();
    assert_eq!(newest.iter().map(|m| text(&m.envelope)).collect::<Vec<_>>(), (25..30).map(|i| format!("g{i}")).collect::<Vec<_>>());
    let older = b.group_messages_page(&gid, newest[0].seq, -1, 5).unwrap();
    assert_eq!(text(&older[0].envelope), "g20");
    let newer = b.group_messages_page(&gid, 0, older[2].seq, 2).unwrap();
    assert_eq!(newer.iter().map(|m| text(&m.envelope)).collect::<Vec<_>>(), ["g23", "g24"]);

    let m = &newest[1];
    assert_eq!(b.group_message(&gid, Some(&m.id), 0).unwrap().map(|r| r.seq), Some(m.seq));
    assert_eq!(b.group_message(&gid, None, m.seq).unwrap().map(|r| r.id), Some(m.id));
    assert!(b.group_message(b"another group", Some(&m.id), 0).unwrap().is_none());

    let since = b.group_messages_since(newest[2].seq, 100).unwrap();
    assert_eq!(since.iter().map(|m| text(&m.envelope)).collect::<Vec<_>>(), ["g28", "g29"]);
    assert!(since.iter().all(|m| m.gid == gid));
    assert_eq!(b.group_marks(&gid, 0).unwrap().unread, N as u32);
    assert_eq!(b.group_marks(&gid, newest[2].seq).unwrap().unread, 2);
    assert_eq!(b.group_marks(&gid, 0).unwrap().total, N as u32);
    // our own posts never count as unread
    assert_eq!(a.group_marks(&gid, 0).unwrap().unread, 0);
    a.stop();
    b.stop();
}
