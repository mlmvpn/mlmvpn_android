//! Who saw a group message (SPEC 7.6, T58), as in Telegram: only in a group of at most
//! `READS_MAX_MEMBERS` members (a channel has none), only for messages under `READS_MAX_AGE`.
//!
//! - A member who reads group messages tells each one's author, and only the author: one
//!   `Receipt(Read)` per author with all of theirs read at once (at most `MAX_IDS` per frame), in
//!   Olm through the member's card (a contact's session, or the hidden row from their own card in
//!   the group's log). Only while read receipts are on.
//! - Low priority and never tried again: it waits `READS_DELAY_MS` behind what is queued with it,
//!   and a delivery that fails drops it. A Google mailbox may carry it like anything else.
//! - The author keeps who read which of its messages and when, by its own clock (`group_reads`),
//!   for the app's «seen by», and marks the message read. A receipt for a message that is not the
//!   author's own, or from someone who is not a member of its group now, changes nothing.

use super::{enqueue_once, groups, Event, Node, State};
use crate::envelope::{Envelope, ReceiptKind, MAX_IDS};
use crate::identity::Identity;
use crate::store::{Db, MsgState};
use crate::util::now_ms;
use crate::{Result, UserId};
use std::collections::BTreeMap;

/// Telegram's «seen by» limits: a group this big at most, and messages younger than this.
pub const READS_MAX_MEMBERS: usize = 100;
pub const READS_MAX_AGE_MS: u64 = 7 * 86_400_000;
/// A read receipt waits this long behind the frames queued with it.
const READS_DELAY_MS: u64 = 1_500;

/// Whether reading a message of `age_ms` in a group of `members` tells its author.
pub fn reads_told(members: usize, broadcast: bool, age_ms: u64) -> bool {
    !broadcast && members <= READS_MAX_MEMBERS && age_ms < READS_MAX_AGE_MS
}

impl Node {
    /// The messages of group `gid` up to `max_seq` (this phone's seq, as `group_messages` gives it)
    /// were read here: marked seen, and with read receipts on, each author is told (see the module).
    /// Returns how many were marked.
    pub fn group_mark_read(&self, gid: &[u8], max_seq: i64) -> Result<usize> {
        let now = now_ms();
        let n = {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let me = identity.sk();
            let rows = store.db().group_unread(gid, max_seq)?;
            if rows.is_empty() {
                return Ok(0);
            }
            let st = groups::load_log(&store.db(), gid)?.state(now).filter(|s| s.is_member(&me));
            let read_receipts = self.inner.opts.read_receipts;
            let identity: &Identity = identity;
            store.transaction(|db| {
                let mut by_author: BTreeMap<UserId, Vec<Vec<u8>>> = BTreeMap::new();
                for (id, sender, at) in &rows {
                    db.group_message_state(id, MsgState::Seen)?;
                    if let Some(st) = st.as_ref().filter(|s| read_receipts && reads_told(s.members.len(), s.broadcast, now.saturating_sub(*at))) {
                        if *sender != me && st.is_member(sender) {
                            by_author.entry(*sender).or_default().push(id.to_vec());
                        }
                    }
                }
                if let Some(st) = &st {
                    for (author, ids) in by_author {
                        let Some(c) = groups::member_contact(db, &author, st.members.get(&author))? else { continue };
                        if c.blocked {
                            continue;
                        }
                        for chunk in ids.chunks(MAX_IDS) {
                            enqueue_once(db, identity, &c, &Envelope::receipt(ReceiptKind::Read, chunk.to_vec()), now + READS_DELAY_MS)?;
                        }
                    }
                }
                Ok(rows.len())
            })?
        };
        self.inner.waker.wake();
        Ok(n)
    }

    /// Who read our group message `seq` and when (by this phone's clock), the first first.
    pub fn group_reads(&self, seq: i64) -> Result<Vec<(UserId, u64)>> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let Some((_, _, id, _)) = db.group_messages_by_seq(&[seq])?.into_iter().next() else { return Ok(Vec::new()) };
        db.group_reads(&id)
    }
}

/// A member's read receipt for ids that are none of our one-to-one messages (`done`): those that
/// are our own messages in a group `from` is a member of now are kept as read by them, and the
/// message marked read. Returns (group, seq) of each newly read one.
pub(super) fn on_receipt(db: &Db, me: &UserId, from: &UserId, ids: &[[u8; 16]], done: &[[u8; 16]], now: u64, events: &mut Vec<Event>) -> Result<()> {
    let mut states: BTreeMap<Vec<u8>, bool> = BTreeMap::new();
    for id in ids.iter().filter(|i| !done.contains(i)) {
        let Some((gid, seq)) = db.group_message_mine(id)? else { continue };
        let member = match states.get(&gid) {
            Some(m) => *m,
            None => {
                let m = groups::load_log(db, &gid)?.state(now).is_some_and(|s| s.is_member(from) && s.is_member(me));
                states.insert(gid.clone(), m);
                m
            }
        };
        if member && db.group_read_add(&gid, id, from, now)? {
            db.group_message_state(id, MsgState::Read)?;
            events.push(Event::GroupRead { gid, seq, peer: *from });
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_small_groups_and_young_messages() {
        assert!(reads_told(3, false, 1_000));
        assert!(reads_told(READS_MAX_MEMBERS, false, READS_MAX_AGE_MS - 1));
        assert!(!reads_told(READS_MAX_MEMBERS + 1, false, 1_000), "a big group");
        assert!(!reads_told(3, false, READS_MAX_AGE_MS), "an old message");
        assert!(!reads_told(3, true, 1_000), "a channel");
    }
}
