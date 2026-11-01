//! Saved Messages (Ehsan, p3, 30 Oct 2026: a message there got «!»): a message to oneself stays
//! on this phone, read at once, edited and deleted like any other, and nothing ever goes out.

mod common;

use common::*;
use mlmchat_core::store::MsgState;

#[test]
fn a_message_to_oneself_stays_on_this_phone() {
    init_log();
    let dir = tempfile::tempdir().unwrap();
    let n = new_node(dir.path(), "ehsan");
    let me = n.me().sk;
    n.send_text(&me, "yaddasht").unwrap();
    let rows = n.history(&me, 10).unwrap();
    assert_eq!(rows.len(), 1, "kept");
    assert!(rows[0].outgoing);
    assert_eq!(rows[0].state, MsgState::Read, "nothing to wait for");
    assert_eq!(n.outbox_len().unwrap(), 0, "nothing goes out");
    n.edit_text(rows[0].seq, "yaddasht-e taze", Vec::new()).unwrap();
    assert_eq!(n.outbox_len().unwrap(), 0, "an edit neither");
    assert_eq!(n.delete_for_everyone(&[rows[0].seq]).unwrap(), 1, "and it goes here");
    assert!(n.history(&me, 10).unwrap().is_empty());
    assert_eq!(n.outbox_len().unwrap(), 0);
}
