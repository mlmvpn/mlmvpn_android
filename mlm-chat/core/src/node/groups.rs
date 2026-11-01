//! Groups and channels on the node (SPEC 7, phase 5, T37).
//!
//! - A change to a group is a signed event (`group.rs`). The author applies it here, keeps it, and
//!   sends it to every member in a pairwise Olm envelope (`GroupOps`); a new member gets the whole
//!   log. Every phone builds the same state from the same events.
//! - Each member reaches every member: a member who is not a contact becomes a hidden contact row
//!   from the card in the group log (`group_peers`), never shown in the contact list.
//! - Messages: our own Megolm session per group, its key shared with each member in a `KeyShare`
//!   (Olm). A group message is one Megolm cipher, put in every member's queue as its own frame.
//!   The session is replaced when a member leaves or is removed, and after 100 messages or 7 days.

use super::{enqueue, Event, Inner, Node, PollResults, State};
use crate::card::Card;
use crate::envelope::{Body, Entity, Envelope, GroupOps, KeyShare, Poll, MAX_GROUP_OPS};
use crate::frame::{self, KIND_MEGOLM};
use crate::group::{self, AddMember, ChatPhoto, Create, GroupLog, GroupState, Member, Op, RemoveMember, SetCard, SetInfo, SetRole, SignedOp, GROUP_ID_LEN};
use crate::identity::Identity;
use crate::megolm;
use crate::store::{Contact, Db, GroupMessageRow, MsgState, OutRow};
use crate::transport::direct::frame_id;
use crate::transport::Verdict;
use crate::util::{array, now_ms, random};
use crate::{Error, Result, UserId};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::path::Path;

/// The member right a post needs (SPEC 7.2, member rights): a photo the photo right; a video, a
/// file, a voice message, a round video, a sticker or GIF, and a poll each their own; anything
/// else the text right. A member an admin restricted (`Restrict`) cannot post what was taken from
/// them, and the other phones do not take it from a phone that does (Ehsan, 28 Oct 2026: a
/// member's own permissions).
pub(crate) fn right_for(body: &Option<Body>) -> u32 {
    use crate::envelope::MediaKind;
    use crate::group::member;
    match body {
        Some(Body::Media(m)) => match MediaKind::try_from(m.kind) {
            Ok(MediaKind::Photo) => member::PHOTO,
            Ok(MediaKind::Video) => member::VIDEO,
            Ok(MediaKind::Voice) => member::VOICE,
            Ok(MediaKind::VideoNote) => member::ROUND,
            Ok(MediaKind::Gif) => member::STICKER,
            _ => member::FILE,
        },
        Some(Body::Sticker(_)) => member::STICKER,
        Some(Body::Poll(_)) => member::POLL,
        _ => member::TEXT,
    }
}

/// The album a post is one of (SPEC 5 `Media.album_id`); empty for none.
fn album_of(env: &Envelope) -> Vec<u8> {
    match &env.body {
        Some(Body::Media(m)) => m.album_id.clone(),
        _ => Vec::new(),
    }
}

/// Whether a post of `sk` at `env.sent_ms` comes too near another of theirs kept here, under the
/// slow mode `st` holds for it (q13, T73; `group::slow_clash`).
fn slow_too_soon(db: &Db, st: &GroupState, sk: &UserId, env: &Envelope) -> Result<bool> {
    let slow = st.slow_for(sk, env.sent_ms);
    if slow == 0 {
        return Ok(false);
    }
    let span = u64::from(slow) * 1000;
    let near: Vec<(u64, Vec<u8>)> = db
        .group_posts_near(&st.group_id, sk, env.sent_ms.saturating_sub(span), env.sent_ms.saturating_add(span))?
        .into_iter()
        .map(|(t, bytes)| (t, Envelope::from_bytes(&bytes).map(|e| album_of(&e)).unwrap_or_default()))
        .collect();
    Ok(group::slow_clash(slow, env.sent_ms, &album_of(env), &near))
}

pub(super) fn load_log(db: &Db, gid: &[u8]) -> Result<GroupLog> {
    let mut log = GroupLog::new();
    for (body, sig) in db.group_ops(gid)? {
        let _ = log.insert(SignedOp { body, sig });
    }
    Ok(log)
}

/// A member as a contact this phone can send to. An existing contact stays as it is; otherwise a
/// hidden row from the member's own card in the log (its fallback key; a card someone else put
/// there may hold a one-time key already used). `None` until the member published one.
pub(super) fn member_contact(db: &Db, sk: &UserId, member: Option<&Member>) -> Result<Option<Contact>> {
    if let Some(c) = db.contact(sk)? {
        return Ok(Some(c));
    }
    let Some(m) = member.filter(|m| m.own_card) else { return Ok(None) };
    let Ok(card) = Card::from_bytes(&m.card) else { return Ok(None) };
    let Some(loc) = card.loc() else { return Ok(None) };
    if card.sk != *sk {
        return Ok(None);
    }
    let c = Contact {
        sk: card.sk,
        ik: card.ik,
        hk: card.hk,
        otk: card.otk,
        loc: Some(loc),
        name: card.name.clone().unwrap_or_default(),
        card: card.to_bytes(),
        added_ms: now_ms(),
        by_them: false,
        blocked: false,
        verified: false,
    };
    db.upsert_contact(&c)?;
    db.mark_group_peer(sk)?;
    Ok(Some(c))
}

/// Members of our groups whose own card is in the group's log: we can tell their frames apart
/// (as `member_by_sk` does).
pub(super) fn members_with_cards(db: &Db) -> Result<HashSet<UserId>> {
    let now = now_ms();
    let mut out = HashSet::new();
    for gid in db.groups()? {
        if let Some(st) = load_log(db, &gid)?.state(now) {
            out.extend(st.members.iter().filter(|(_, m)| m.own_card).map(|(sk, _)| *sk));
        }
    }
    Ok(out)
}

/// Someone who is not a contact but a member of one of our groups: their hidden contact row from
/// the card in that group's log, so their frames are taken like a contact's.
pub(super) fn member_by_sk(db: &Db, sk: &UserId) -> Result<Option<Contact>> {
    let now = now_ms();
    for gid in db.groups()? {
        if let Some(st) = load_log(db, &gid)?.state(now) {
            if let Some(m) = st.members.get(sk) {
                return member_contact(db, sk, Some(m));
            }
        }
    }
    Ok(None)
}

/// Send events to some members (all but us when `to` is empty), in envelopes of at most
/// `MAX_GROUP_OPS`.
/// The poll a message of group `gid` is (by its id); None when it is not here or is no poll.
fn poll_of(db: &Db, gid: &[u8], id: &[u8; 16]) -> Result<Option<Poll>> {
    let Some(row) = db.group_message_by_id(gid, id)? else { return Ok(None) };
    Ok(match Envelope::from_bytes(&row.envelope).ok().and_then(|e| e.body) {
        Some(Body::Poll(p)) => Some(p),
        _ => None,
    })
}

fn publish(db: &Db, identity: &Identity, st: &GroupState, ops: &[SignedOp], to: &[UserId]) -> Result<bool> {
    let me = identity.sk();
    let targets: Vec<UserId> = if to.is_empty() { st.members.keys().copied().collect() } else { to.to_vec() };
    let mut sent = false;
    for sk in targets.iter().filter(|s| **s != me) {
        let Some(c) = member_contact(db, sk, st.members.get(sk))? else { continue };
        if c.blocked {
            continue;
        }
        for chunk in ops.chunks(MAX_GROUP_OPS) {
            let env = Envelope::new(Body::GroupOps(GroupOps { group_id: st.group_id.clone(), ops: chunk.to_vec() }));
            enqueue(db, identity, &c, &env, false)?;
            sent = true;
        }
    }
    Ok(sent)
}

/// Our Megolm key for the group, to the given members (Olm).
fn share_key(db: &Db, identity: &Identity, st: &GroupState, out: &vodozemac::megolm::GroupSession, to: &[UserId]) -> Result<()> {
    let me = identity.sk();
    let key = KeyShare { group_id: st.group_id.clone(), session_id: out.session_id().into_bytes(), session_key: megolm::session_key(out), first_index: out.message_index() };
    for sk in to.iter().filter(|s| **s != me) {
        if let Some(c) = member_contact(db, sk, st.members.get(sk))? {
            enqueue(db, identity, &c, &Envelope::new(Body::KeyShare(key.clone())), false)?;
        }
    }
    Ok(())
}

impl Inner {
    /// Make, sign and apply one event of ours; it must take effect. Then keep it and send it to
    /// the members (and to whoever it removed, so they learn it).
    pub(super) fn group_change(&self, gid: &[u8], op: Op) -> Result<GroupState> {
        let now = now_ms();
        let mut events = Vec::new();
        let st = {
            let mut guard = self.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let me = identity.sk();
            let mut log = load_log(&store.db(), gid)?;
            let before = log.state(now).ok_or(Error::NotFound("group"))?;
            let removed = match &op {
                Op::Remove(r) => array::<32>(&r.sk),
                _ => None,
            };
            let added = match &op {
                Op::Add(a) => array::<32>(&a.sk),
                _ => None,
            };
            let signed = SignedOp::sign(&group::new_op(&log, gid, &me, op, now), |m| identity.sign(m));
            let id = signed.id();
            log.insert(signed.clone())?;
            let st = log.state(now).ok_or(Error::NotFound("group"))?;
            if !st.applied.iter().any(|(i, ok)| *i == id && *ok) {
                return Err(Error::Session("not allowed in this group"));
            }
            let identity: &Identity = identity;
            store.transaction(|db| {
                db.group_op_save(gid, &id, &signed.body, &signed.sig, now)?;
                publish(db, identity, &st, std::slice::from_ref(&signed), &[])?;
                if let Some(r) = removed {
                    // the one removed learns it; our key changes so they cannot read what follows
                    publish(db, identity, &before, std::slice::from_ref(&signed), &[r])?;
                    db.megolm_out_drop(gid)?;
                    if r == me {
                        db.group_leave(gid, now)?;
                    }
                }
                if let Some(a) = added {
                    // the new member gets the whole log, and our key if we have one
                    let all: Vec<SignedOp> = db.group_ops(gid)?.into_iter().map(|(body, sig)| SignedOp { body, sig }).collect();
                    publish(db, identity, &st, &all, &[a])?;
                    if let Some((pickle, _, _)) = db.megolm_out(gid)? {
                        share_key(db, identity, &st, &megolm::load_out(&pickle)?, &[a])?;
                    }
                }
                Ok(())
            })?;
            events.push(Event::Group { gid: gid.to_vec() });
            st
        };
        for e in events {
            self.emit(e);
        }
        self.waker.wake();
        Ok(st)
    }

    /// A Megolm frame from a member (SPEC 7.3).
    pub(super) fn on_group_frame(&self, from: UserId, bytes: &[u8], cipher: &[u8]) -> Verdict {
        let fid = frame_id(bytes);
        let now = now_ms();
        let mut guard = self.state.lock().expect("state");
        let State { store, identity } = &mut *guard;
        let me = identity.sk();
        match store.db().got_frame(&fid) {
            Ok(true) => return Verdict::Stored,
            Ok(false) => {}
            Err(_) => return Verdict::Retry,
        }
        let Ok((sid, msg)) = megolm::split(cipher) else { return Verdict::Dropped };
        let (gid, sender, pickle) = match store.db().megolm_in(&sid) {
            Ok(Some(x)) => x,
            // the key is on its way (it was queued before this message): held here and opened when
            // it comes. Before, the frame was left unacknowledged, and the mailbox handed the same
            // item back on every take, about once a second, until the key came or the item expired.
            Ok(None) => {
                return if super::hold_frame(&store.db(), &fid, &from, bytes, now) {
                    log::debug!("node: held a group frame until its key comes");
                    Verdict::Stored
                } else {
                    Verdict::Retry
                };
            }
            Err(_) => return Verdict::Retry,
        };
        let Ok(mut inbound) = megolm::load_in(&pickle) else { return Verdict::Dropped };
        let Ok((plain, _)) = megolm::decrypt(&mut inbound, &msg) else { return Verdict::Dropped };
        let Ok(env) = Envelope::from_bytes(&plain) else { return Verdict::Dropped };
        let Some(env_id) = array::<16>(&env.id) else { return Verdict::Dropped };
        if env.group_id != gid || sender == me {
            return Verdict::Dropped;
        }
        let Ok(log) = load_log(&store.db(), &gid) else { return Verdict::Retry };
        let state = log.state(now);
        // a sender we have the key of but not yet the event that made them a member (a contact who
        // joined by a link, a frame that overtook the group's news): held until the event comes,
        // instead of their message being lost
        if state.as_ref().is_some_and(|st| st.is_member(&me) && !st.is_member(&sender)) && super::hold_frame(&store.db(), &fid, &from, bytes, now) {
            log::debug!("node: held a group frame until its sender's membership comes");
            return Verdict::Stored;
        }
        let allowed = state.as_ref().is_some_and(|st| st.can_post(&sender, now) && st.is_member(&me) && st.member_rights(&sender, now) & right_for(&env.body) != 0);
        // any member reacts, a channel's readers too (as in Telegram)
        let may_react = state.as_ref().is_some_and(|st| st.is_member(&sender) && st.is_member(&me));
        // an admin with the right takes anyone's message down, a member only their own (SPEC 5.2)
        let deletes_any = state.as_ref().is_some_and(|st| st.admin_rights(&sender) & crate::group::admin::DELETE != 0);
        let mut events = Vec::new();
        let mut wake = false;
        let identity: &Identity = identity;
        let result = store.transaction(|db| {
            db.mark_got(&fid, now)?;
            db.held_drop(&fid)?;
            db.megolm_in_save(&sid, &gid, &sender, &megolm::pickle_in(&inbound))?;
            // the app it came from (SPEC 5 `Client`, T75), for the «old version» tag beside them
            if db.client_seen(&sender, env.client.as_ref(), env.sent_ms.min(now), now)? {
                events.push(Event::Client { peer: sender });
            }
            if let Some(Body::Delete(d)) = &env.body {
                if !may_react || !db.mark_seen(&env.id, now)? {
                    return Ok(());
                }
                let mut seqs = Vec::new();
                for id in d.ids.iter().filter(|i| i.len() == 16) {
                    match db.group_message_find(&gid, id)? {
                        Some((seq, author)) if author == sender || deletes_any => {
                            db.group_message_blank(&gid, id)?;
                            seqs.push(seq);
                        }
                        Some(_) => {}
                        // not here yet: an admin's word keeps a late copy out (a member's own
                        // messages come before their delete, from the same queue)
                        None if deletes_any => {
                            db.mark_seen(id, now)?;
                        }
                        None => {}
                    }
                }
                if !seqs.is_empty() {
                    events.push(Event::Deleted { chat: gid.clone(), group: true, seqs });
                }
                return Ok(());
            }
            if let Some(Body::Edit(e)) = &env.body {
                // a member's edit of their own message (SPEC 5 `Edit`): changed in place, never a
                // post. Anyone else's, one not here, or one older than the last taken is dropped.
                if !may_react || !db.mark_seen(&env.id, now)? {
                    return Ok(());
                }
                if let Some(target) = array::<16>(&e.target) {
                    if let Some(row) = db.group_message_by_id(&gid, &target)?.filter(|r| r.sender == sender && !r.outgoing) {
                        if env.sent_ms > db.edited_ms(&target)? {
                            if let Some(new) = Envelope::from_bytes(&row.envelope).ok().and_then(|s| crate::envelope::apply_edit(&s, e)) {
                                if db.group_message_edit(&gid, &target, &new.to_bytes()?, env.sent_ms)? {
                                    events.push(Event::Edited { chat: gid.clone(), group: true, seq: row.seq });
                                }
                            }
                        }
                    }
                }
                return Ok(());
            }
            if let Some(Body::Reaction(r)) = &env.body {
                let emoji = if r.remove { "" } else { r.emoji.as_str() };
                if let (true, Some(target)) = (may_react, array::<16>(&r.target)) {
                    if db.mark_seen(&env.id, now)? && db.reaction_set(&gid, &target, &sender, emoji, env.sent_ms)? {
                        events.push(Event::Reaction { chat: gid.clone(), group: true, id: target, peer: sender });
                    }
                }
                return Ok(());
            }
            if let Some(Body::PollVote(v)) = &env.body {
                // a vote on a poll of this group (T69): any member, a channel's readers too, like a
                // reaction; kept beside the poll and counted here, never a message of its own. A
                // vote that came before its poll is kept, and its poll's options decide later.
                if let (true, Some(poll)) = (may_react, array::<16>(&v.poll_id)) {
                    let found = poll_of(db, &gid, &poll)?;
                    let fits = found.as_ref().is_none_or(|p| p.accepts(&v.choices) && p.open_at(env.sent_ms));
                    let quiz = found.as_ref().is_some_and(|p| p.quiz);
                    if fits && db.mark_seen(&env.id, now)? && db.poll_vote_set(&gid, &poll, &sender, &v.choices, env.sent_ms, quiz)? {
                        events.push(Event::PollVote { chat: gid.clone(), group: true, id: poll, peer: sender });
                    }
                }
                return Ok(());
            }
            if !allowed || !env.is_visible() || !db.mark_seen(&env.id, now)? {
                return Ok(());
            }
            // slow mode (q13, T73): a member's post too near another of theirs is taken nowhere
            if let Some(st) = state.as_ref() {
                if slow_too_soon(db, st, &sender, &env)? {
                    log::info!("node: a group post too soon under slow mode, dropped");
                    return Ok(());
                }
            }
            if let Some(Body::Media(m)) = &env.body {
                // the file comes from its sender, a contact or a member we reach from the group's log
                if db.contact(&sender)?.is_none() {
                    member_by_sk(db, &sender)?;
                }
                wake |= super::media::on_media(db, identity, &sender, m)?;
            }
            let row = GroupMessageRow { id: env_id, gid: gid.clone(), sender, outgoing: false, sent_ms: env.sent_ms, at_ms: now, kind: env.kind_name().to_string(), envelope: plain.clone(), state: MsgState::Received, seq: 0 };
            if db.group_message_save(&row)?.is_some() {
                let text = match &env.body {
                    Some(Body::Text(t)) => Some(t.body.clone()),
                    _ => None,
                };
                events.push(Event::GroupMessage { gid: gid.clone(), sender, id: env_id, kind: env.kind_name(), text });
            }
            Ok(())
        });
        if result.is_err() {
            return Verdict::Retry;
        }
        drop(guard);
        log::info!("node: group message stored");
        for e in events {
            self.emit(e);
        }
        if wake {
            self.waker.wake();
        }
        Verdict::Stored
    }
}

/// What `on_ops` leaves for after the transaction.
#[derive(Default)]
pub(super) struct OpsOutcome {
    /// Frames were queued.
    pub wake: bool,
    /// We were just added to this group: our own card goes to its members next.
    pub joined: Option<Vec<u8>>,
}

/// Members (not the author) that pass a new group event on to the others, per event.
pub const RELAYERS: usize = 2;

/// Who passes event `id` on (SPEC 7.3, T53): of the members its author surely reached (`reached`,
/// `GroupLog::reached`) and still in the group, the `RELAYERS` whose hash with the event's id is
/// smallest, never the author. They have the event from its author, and every phone works out the
/// same ones from the same log. None (rare: no one surely took it) means everyone who has it.
fn relayers(id: &group::OpId, author: &UserId, reached: &BTreeSet<UserId>, st: &GroupState) -> Vec<UserId> {
    use sha2::{Digest, Sha256};
    let mut ranked: Vec<([u8; 32], UserId)> = reached
        .iter()
        .filter(|m| *m != author && st.is_member(m))
        .map(|m| {
            let mut d = Sha256::new();
            d.update(b"mlmchat/group-relay/1");
            d.update(id);
            d.update(m);
            (d.finalize().into(), *m)
        })
        .collect();
    ranked.sort();
    ranked.into_iter().take(RELAYERS).map(|(_, m)| m).collect()
}

/// Can we send to member `m` as `st` stands: a contact, or their own card is in the log (as
/// `member_contact` decides).
fn can_reach(db: &Db, st: &GroupState, m: &UserId) -> Result<bool> {
    let Some(x) = st.members.get(m) else { return Ok(false) };
    Ok(x.own_card || db.contact(m)?.is_some())
}

/// Do we pass event `id` (by `author`) on: we wrote it, we are one of its relayers, or it has none.
fn we_pass(id: &group::OpId, author: &UserId, reached: &BTreeSet<UserId>, st: &GroupState, me: &UserId) -> bool {
    if author == me {
        return true;
    }
    let r = relayers(id, author, reached, st);
    r.is_empty() || r.contains(me)
}

/// Event `id` adds member `m`, or is `m`'s own card.
fn about(log: &GroupLog, id: &group::OpId, m: &UserId) -> bool {
    match log.op(id).and_then(|o| o.op.as_ref().map(|x| (o, x))) {
        Some((_, Op::Add(a))) => a.sk == m,
        Some((o, Op::SetCard(_))) => o.author_sk == m,
        _ => false,
    }
}

/// Group events from a contact or member, inside the Olm transaction (`on_frame`). Unknown
/// groups are taken only when the events make us a member. Events we relay go on to the members
/// their author may not have reached, so a member's card reaches members who cannot reach it yet.
pub(super) fn on_ops(db: &Db, identity: &Identity, from: &UserId, g: &GroupOps, events: &mut Vec<Event>) -> Result<OpsOutcome> {
    let now = now_ms();
    let me = identity.sk();
    let gid = &g.group_id;
    let mut log = load_log(db, gid)?;
    let before = log.state(now);
    let mut new: Vec<(group::OpId, SignedOp)> = Vec::new();
    for op in &g.ops {
        match log.insert(op.clone()) {
            Ok(true) => new.push((op.id(), op.clone())),
            Ok(false) => {}
            Err(e) => log::debug!("node: a group event refused ({e})"),
        }
    }
    let mut out = OpsOutcome::default();
    if new.is_empty() {
        return Ok(out);
    }
    let Some(st) = log.state(now) else {
        // events before their group's creation came: keep them only for a group we are in
        if db.groups()?.iter().any(|x| x == gid) {
            for (id, op) in &new {
                db.group_op_save(gid, id, &op.body, &op.sig, now)?;
            }
        }
        return Ok(out);
    };
    let known = db.groups()?.iter().any(|x| x == gid);
    let sender_ok = st.is_member(from) || before.as_ref().is_some_and(|b| b.is_member(from));
    if !sender_ok || (!known && !st.is_member(&me)) {
        log::debug!("node: group events not taken (sender member {sender_ok}, known {known})");
        return Ok(out);
    }
    for (id, op) in &new {
        db.group_op_save(gid, id, &op.body, &op.sig, now)?;
    }
    if !known && st.is_member(&me) {
        db.group_join(gid, now)?;
        db.joining_done(gid)?;
        out.joined = Some(gid.clone());
        log::info!("node: joined a group");
    }
    // pass events on (SPEC 7.3, T53). The log tells who surely took an event from its author
    // (`GroupLog::reached`); of those, only the event's two relayers pass it to the other members.
    // An event is passed once everything it follows is here, so every phone works out the same
    // relayers. Before, every member passed every new event to every other: n² frames an event,
    // and a group of twenty took nearly five minutes to settle; relayers picked among all members
    // stalled it, as they often never had the event, and so did counting as reached the members
    // who hold a newcomer's first card until they know the newcomer (stress.rs, 26 Oct 2026).
    let pos: HashMap<group::OpId, usize> = st.applied.iter().enumerate().map(|(i, (id, _))| (*id, i)).collect();
    let mut pass: BTreeMap<UserId, BTreeSet<usize>> = BTreeMap::new();
    let wrote_any = st.applied.iter().any(|(id, _)| log.author(id) == Some(me));
    if let (Some(b), true) = (&before, wrote_any) {
        // members we can reach now and could not before (their card or their membership came)
        let mut newly = Vec::new();
        for m in st.members.keys().filter(|m| **m != me) {
            if can_reach(db, &st, m)? && !can_reach(db, b, m)? {
                newly.push(*m);
            }
        }
        let placed_before: HashSet<group::OpId> = b.applied.iter().map(|(id, _)| *id).collect();
        for (id, _) in st.applied.iter().filter(|(id, _)| !placed_before.contains(id)) {
            let Some(author) = log.author(id).filter(|a| *a != me) else { continue };
            let reached = log.reached(id);
            if !we_pass(id, &author, &reached, &st, &me) {
                continue;
            }
            for m in st.members.keys().filter(|m| **m != author && *m != from && **m != me && !reached.contains(*m)) {
                pass.entry(*m).or_default().insert(pos[id]);
            }
        }
        // the catch-up: what we wrote or passed on that a newly reachable member may lack, for
        // we could not reach them when it came. They hold what the event that made them
        // reachable follows (their card's ancestors, or the log their adder sent them).
        for m in &newly {
            let Some(last) = st.applied.iter().rev().map(|(id, _)| *id).find(|id| about(&log, id, m)) else { continue };
            let mut held = log.ancestors(&last);
            held.insert(last);
            for (id, _) in st.applied.iter().filter(|(id, _)| !held.contains(id)) {
                let Some(author) = log.author(id).filter(|a| a != m) else { continue };
                let reached = log.reached(id);
                if !reached.contains(m) && we_pass(id, &author, &reached, &st, &me) {
                    pass.entry(*m).or_default().insert(pos[id]);
                }
            }
        }
    }
    for (m, at) in &pass {
        let ops: Vec<SignedOp> = at.iter().filter_map(|i| log.get(&st.applied[*i].0).cloned()).collect();
        out.wake |= publish(db, identity, &st, &ops, std::slice::from_ref(m))?;
    }
    if known && !st.is_member(&me) {
        db.group_leave(gid, now)?;
        db.megolm_out_drop(gid)?;
    }
    if let Some(b) = &before {
        // someone left or was removed: our next message uses a new key
        if b.members.keys().any(|m| !st.members.contains_key(m)) {
            db.megolm_out_drop(gid)?;
        }
        // someone joined, or a member we could not reach published their card: our key, so
        // they read what we send from now on
        let reach: Vec<UserId> = st
            .members
            .iter()
            .filter(|(sk, m)| **sk != me && (!b.members.contains_key(*sk) || (m.own_card && !b.members[*sk].own_card)))
            .map(|(sk, _)| *sk)
            .collect();
        if !reach.is_empty() {
            let current = db.megolm_out(gid)?.map(|(pickle, _, _)| megolm::load_out(&pickle)).transpose()?;
            for sk in &reach {
                // keys owed from messages written while we could not reach them (`group_send`):
                // those open what waits for them in the queue; then our key now, if not among them
                let owed = db.group_owed_take(gid, sk)?;
                let has_current = current.as_ref().is_some_and(|c| owed.iter().any(|(sid, _, _)| *sid == c.session_id()));
                if let Some(c) = member_contact(db, sk, st.members.get(sk))?.filter(|c| !c.blocked) {
                    for (sid, key, idx) in &owed {
                        let k = KeyShare { group_id: gid.clone(), session_id: sid.clone().into_bytes(), session_key: key.clone(), first_index: *idx };
                        enqueue(db, identity, &c, &Envelope::new(Body::KeyShare(k)), false)?;
                    }
                    if !owed.is_empty() {
                        db.wake_peer(sk)?;
                    }
                }
                if let (Some(c), false) = (&current, has_current) {
                    share_key(db, identity, &st, c, std::slice::from_ref(sk))?;
                }
                out.wake = true;
            }
            out.wake |= send_profile(db, identity, &st, &reach)?;
        }
    }
    // a new group photo: its file comes from whoever set it, or, if we cannot reach them yet,
    // from the member who passed the event on
    if let Some((p, by)) = &st.photo {
        let had = before.as_ref().and_then(|b| b.photo.as_ref()).is_some_and(|(q, _)| q == p);
        if let (false, true, Some(id), Some(key)) = (had, *by != me, array::<32>(&p.blob_id), array::<32>(&p.key)) {
            let src = if member_contact(db, by, st.members.get(by))?.is_some() { *by } else { *from };
            out.wake |= super::media::expect_file(db, identity, &src, &id, &key, p.size)?;
        }
        // and from our relays, where it is kept for good (q16, T76); ours goes there now
        if let (false, Some(id)) = (had, array::<32>(&p.blob_id)) {
            super::feedfiles::register_chat_photo(db, &id, gid, p.size)?;
        }
    }
    events.push(Event::Group { gid: gid.clone() });
    Ok(out)
}

impl Inner {
    /// We were just added: our own card (with our fallback key) goes into the group, so members
    /// who are not our contacts can reach us. Called with the state lock held.
    pub(super) fn announce_card(&self, store: &mut crate::store::Store, identity: &mut Identity, gid: &[u8]) -> Result<()> {
        let now = now_ms();
        let me = identity.sk();
        let card = self.session_card_bytes(identity)?;
        let mut log = load_log(&store.db(), gid)?;
        let signed = SignedOp::sign(&group::new_op(&log, gid, &me, Op::SetCard(SetCard { card }), now), |m| identity.sign(m));
        let id = signed.id();
        log.insert(signed.clone())?;
        let Some(st) = log.state(now) else { return Ok(()) };
        let saved = identity.to_saved();
        let identity: &Identity = identity;
        store.transaction(|db| {
            db.save_identity(&saved)?;
            db.group_op_save(gid, &id, &signed.body, &signed.sig, now)?;
            publish(db, identity, &st, std::slice::from_ref(&signed), &[])?;
            let all: Vec<UserId> = st.members.keys().copied().collect();
            send_profile(db, identity, &st, &all)?;
            Ok(())
        })?;
        Ok(())
    }
}

/// Our profile (photo) to these members, if we have one (SPEC 10): members who are not our
/// contacts see it too. True if any was queued.
fn send_profile(db: &Db, identity: &Identity, st: &GroupState, to: &[UserId]) -> Result<bool> {
    let Some(p) = super::profile::own_profile(db, identity)? else { return Ok(false) };
    let me = identity.sk();
    let mut any = false;
    for sk in to.iter().filter(|s| **s != me) {
        if let Some(c) = member_contact(db, sk, st.members.get(sk))?.filter(|c| !c.blocked) {
            enqueue(db, identity, &c, &p, false)?;
            any = true;
        }
    }
    Ok(any)
}

/// A member's Megolm key for a group (inside Olm, from `from`).
pub(super) fn on_key_share(db: &Db, from: &UserId, k: &KeyShare) -> Result<()> {
    let now = now_ms();
    let Some(st) = load_log(db, &k.group_id)?.state(now) else { return Ok(()) };
    if !st.is_member(from) {
        return Ok(());
    }
    let (sid, inbound) = megolm::inbound(&k.session_key)?;
    if sid.as_bytes() != k.session_id.as_slice() {
        return Ok(());
    }
    // a key we have already, unless this one starts at an earlier message (one owed us: it opens
    // the messages that waited for us, and every later one too)
    if let Some((_, _, pickle)) = db.megolm_in(&sid)? {
        if megolm::load_in(&pickle)?.first_known_index() <= inbound.first_known_index() {
            return Ok(());
        }
    }
    db.megolm_in_save(&sid, &k.group_id, from, &megolm::pickle_in(&inbound))
}

impl Node {
    /// Create a group, or a channel (`broadcast`: only the owner and admins post). Returns its id.
    pub fn group_create(&self, name: &str, about: &str, broadcast: bool) -> Result<Vec<u8>> {
        let now = now_ms();
        let gid = random::<GROUP_ID_LEN>().to_vec();
        {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let card = self.inner.session_card_bytes(identity)?;
            let me = identity.sk();
            let c = Create { name: name.into(), about: about.into(), defaults: group::member::DEFAULT, broadcast, secret: random::<32>().to_vec(), owner_card: card };
            let op = group::GroupOp { group_id: gid.clone(), parents: Vec::new(), author_sk: me.to_vec(), ts_ms: now, op: Some(Op::Create(c)) };
            let signed = SignedOp::sign(&op, |m| identity.sign(m));
            signed.open()?;
            let saved = identity.to_saved();
            store.transaction(|db| {
                db.save_identity(&saved)?;
                db.group_join(&gid, now)?;
                db.group_op_save(&gid, &signed.id(), &signed.body, &signed.sig, now)?;
                Ok(())
            })?;
        }
        log::info!("node: group created");
        self.inner.emit(Event::Group { gid: gid.clone() });
        Ok(gid)
    }

    /// Add a contact to the group (they get the whole log, and the members' keys follow).
    pub fn group_add(&self, gid: &[u8], peer: &UserId) -> Result<GroupState> {
        let card = {
            let st = self.inner.state.lock().expect("state");
            let c = st.store.db().contact(peer)?.ok_or(Error::NotFound("contact"))?;
            c.card
        };
        self.inner.group_change(gid, Op::Add(AddMember { sk: peer.to_vec(), card, invite_id: Vec::new() }))
    }

    /// Remove a member (`ban`: they cannot come back), or leave when `peer` is us.
    pub fn group_remove(&self, gid: &[u8], peer: &UserId, ban: bool) -> Result<GroupState> {
        self.inner.group_change(gid, Op::Remove(RemoveMember { sk: peer.to_vec(), ban_until_ms: if ban { u64::MAX } else { 0 } }))
    }

    /// Lift a ban without taking the member back (SPEC 7.2 `Unban`, T59): an admin with the right
    /// to ban. They may be added again after it.
    pub fn group_unban(&self, gid: &[u8], peer: &UserId) -> Result<GroupState> {
        self.inner.group_change(gid, Op::Unban(group::Unban { sk: peer.to_vec() }))
    }

    pub fn group_leave(&self, gid: &[u8]) -> Result<GroupState> {
        let me = self.me().sk;
        self.group_remove(gid, &me, false)
    }

    /// Pin message `seq` of the group (its seq here) for every member, or take its pin off (SPEC
    /// 7.2 `PinMessage`, T65; Ehsan, 29 Oct 2026): admins with the pin right, members when the
    /// group gives it. Every phone keeps the same pins from the group's log.
    pub fn group_pin(&self, gid: &[u8], seq: i64, unpin: bool) -> Result<GroupState> {
        let id = self.inner.state.lock().expect("state").store.db().group_message_at(gid, seq)?.ok_or(Error::NotFound("message"))?.id;
        self.inner.group_change(gid, Op::Pin(group::PinMessage { target: id.to_vec(), unpin }))
    }

    /// The group's pinned messages that are on this phone, by their seq here, the newest last.
    pub fn group_pinned(&self, gid: &[u8]) -> Result<Vec<i64>> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let Some(state) = load_log(&db, gid)?.state(now_ms()) else { return Ok(Vec::new()) };
        let mut out = Vec::new();
        for id in &state.pinned {
            if let Some(row) = db.group_message_by_id(gid, id)? {
                out.push(row.seq);
            }
        }
        Ok(out)
    }

    /// Make a member an admin with `rights` (SPEC 7.1), or a member again.
    pub fn group_set_admin(&self, gid: &[u8], peer: &UserId, admin: bool, rights: u32, title: &str) -> Result<GroupState> {
        self.inner.group_change(gid, Op::SetRole(SetRole { sk: peer.to_vec(), admin, rights, title: title.into() }))
    }

    /// Rename, or change the description; the photo stays.
    pub fn group_set_info(&self, gid: &[u8], name: &str, about: &str) -> Result<GroupState> {
        self.inner.group_change(gid, Op::SetInfo(SetInfo { name: name.into(), about: about.into(), ..Default::default() }))
    }

    /// The group's or channel's photo from an image file, with its tiny preview; `None` removes
    /// it. Needs the right to change its info. Members fetch the file from this phone.
    pub fn group_set_photo(&self, gid: &[u8], path: Option<&Path>, thumb: &[u8]) -> Result<GroupState> {
        let st = self.group_state(gid)?.ok_or(Error::NotFound("group"))?;
        let sealed = match path {
            Some(p) => Some(self.inner.seal_file(p)?),
            None => None,
        };
        let photo = sealed.as_ref().map(|s| ChatPhoto { blob_id: s.blob_id.to_vec(), key: s.key.to_vec(), size: s.size, thumb: super::profile::thumb_ok(thumb) });
        let state = self.inner.group_change(gid, Op::SetInfo(SetInfo { name: st.name, about: st.about, photo_set: true, photo, listed: 0 }))?;
        // ours goes to our relays now, kept there for good, so members get it with this phone away (q16, T76)
        if let Some(s) = &sealed {
            self.inner.state.lock().expect("state").store.transaction(|db| super::feedfiles::register_chat_photo(db, &s.blob_id, gid, s.size))?;
            self.inner.files_tick();
        }
        Ok(state)
    }

    /// Any other change (defaults, restrictions, invites, transfer, dissolve).
    pub fn group_op(&self, gid: &[u8], op: Op) -> Result<GroupState> {
        self.inner.group_change(gid, op)
    }

    /// Slow mode in a group, in seconds, 0 to end it (q13, T73; SPEC 7.2 `SetSlowMode`): a member
    /// posts again only this long after their last post. An admin with the right to ban.
    pub fn group_set_slow(&self, gid: &[u8], seconds: u32) -> Result<GroupState> {
        if seconds > crate::feed::MAX_SLOW_S {
            return Err(Error::Envelope("slow mode too long"));
        }
        self.inner.group_change(gid, Op::SlowMode(group::SetSlowMode { seconds }))
    }

    /// When this phone may post in the group again under its slow mode (unix ms); 0 for now.
    pub fn group_slow_next(&self, gid: &[u8]) -> Result<u64> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let me = st.identity.sk();
        let now = now_ms();
        let Some(state) = load_log(&db, gid)?.state(now) else { return Ok(0) };
        let slow = state.slow_for(&me, now);
        if slow == 0 {
            return Ok(0);
        }
        let next = db.group_last_post_ms(gid, &me)?.saturating_add(u64::from(slow) * 1000);
        Ok(if next > now { next } else { 0 })
    }

    /// The group's events, for «recent actions» (SPEC 7.2): id, kind, author, time; in the order
    /// the state applies them, with whether each took effect. Events still waiting for their
    /// parents are at the end, not applied.
    pub fn group_events(&self, gid: &[u8]) -> Result<Vec<(group::OpId, &'static str, UserId, u64, bool)>> {
        let st = self.inner.state.lock().expect("state");
        let log = load_log(&st.store.db(), gid)?;
        let applied = log.state(now_ms()).map(|s| s.applied).unwrap_or_default();
        let mut out = Vec::new();
        let mut seen = std::collections::BTreeSet::new();
        let ids: Vec<(group::OpId, bool)> = applied.into_iter().chain(log.ids().into_iter().map(|i| (i, false))).collect();
        for (id, ok) in ids {
            if !seen.insert(id) {
                continue;
            }
            let Some(op) = log.get(&id).and_then(|s| s.open().ok()) else { continue };
            let kind = match &op.op {
                Some(Op::Create(_)) => "create",
                Some(Op::SetInfo(_)) => "set_info",
                Some(Op::Add(_)) => "add",
                Some(Op::Remove(_)) => "remove",
                Some(Op::SetRole(_)) => "set_role",
                Some(Op::Defaults(_)) => "defaults",
                Some(Op::Restrict(_)) => "restrict",
                Some(Op::SlowMode(_)) => "slow_mode",
                Some(Op::Invite(_)) => "invite",
                Some(Op::Revoke(_)) => "revoke",
                Some(Op::Transfer(_)) => "transfer",
                Some(Op::Dissolve(_)) => "dissolve",
                Some(Op::SetCard(_)) => "set_card",
                Some(Op::Unban(_)) => "unban",
                Some(Op::Pin(_)) => "pin",
                None => "unknown",
            };
            let author = array::<32>(&op.author_sk).unwrap_or([0; 32]);
            out.push((id, kind, author, op.ts_ms, ok));
        }
        Ok(out)
    }

    pub fn group_state(&self, gid: &[u8]) -> Result<Option<GroupState>> {
        let st = self.inner.state.lock().expect("state");
        Ok(load_log(&st.store.db(), gid)?.state(now_ms()))
    }

    /// The groups and channels this phone is in.
    pub fn groups(&self) -> Result<Vec<GroupState>> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let now = now_ms();
        let mut out = Vec::new();
        for gid in db.groups()? {
            if let Some(s) = load_log(&db, &gid)?.state(now) {
                out.push(s);
            }
        }
        Ok(out)
    }

    pub fn group_messages(&self, gid: &[u8], limit: usize) -> Result<Vec<GroupMessageRow>> {
        self.inner.state.lock().expect("state").store.db().group_messages(gid, limit)
    }

    /// A page of a group or channel, as [`Node::history_page`].
    pub fn group_messages_page(&self, gid: &[u8], before: i64, after: i64, limit: usize) -> Result<Vec<GroupMessageRow>> {
        self.inner.state.lock().expect("state").store.db().group_messages_page(gid, before, after, limit)
    }

    /// One message of a group or channel: by its id, or by its seq when `id` is `None`.
    pub fn group_message(&self, gid: &[u8], id: Option<&[u8; 16]>, seq: i64) -> Result<Option<GroupMessageRow>> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        match id {
            Some(id) => db.group_message_by_id(gid, id),
            None => db.group_message_at(gid, seq),
        }
    }

    /// The messages of every group and channel stored after seq `after`, oldest first, at most
    /// `limit` (see [`Node::messages_since`]).
    pub fn group_messages_since(&self, after: i64, limit: usize) -> Result<Vec<GroupMessageRow>> {
        self.inner.state.lock().expect("state").store.db().group_messages_since(after, limit)
    }

    /// A group or channel in a chat list: incoming messages after seq `after` (the last one read
    /// here), our newest message a member has read, and how many messages it has.
    pub fn group_marks(&self, gid: &[u8], after: i64) -> Result<crate::store::GroupMarks> {
        self.inner.state.lock().expect("state").store.db().group_marks(gid, after)
    }

    /// Post a text to the group: one Megolm cipher, a frame in each member's queue.
    pub fn group_send_text(&self, gid: &[u8], text: &str) -> Result<[u8; 16]> {
        self.group_send_text_reply(gid, text, 0)
    }

    /// A text in answer to message `reply_to` of the group (its seq here; 0 for none) - see
    /// [`Node::send_text_reply`].
    pub fn group_send_text_reply(&self, gid: &[u8], text: &str, reply_to: i64) -> Result<[u8; 16]> {
        self.group_send_text_rich(gid, text, Vec::new(), reply_to)
    }

    /// A text with its formatting and mentions in the group - see [`Node::send_text_rich`].
    pub fn group_send_text_rich(&self, gid: &[u8], text: &str, entities: Vec<Entity>, reply_to: i64) -> Result<[u8; 16]> {
        let mut env = Envelope::rich_text(text, entities);
        env.group_id = gid.to_vec();
        env.reply_to = self.group_reply_id(gid, reply_to)?;
        self.group_send(&env)
    }

    /// The id behind seq `seq` of the group `gid`, for an envelope's `reply_to`: empty for 0 and
    /// for a seq of another group.
    pub(crate) fn group_reply_id(&self, gid: &[u8], seq: i64) -> Result<Vec<u8>> {
        if seq <= 0 {
            return Ok(Vec::new());
        }
        let st = self.inner.state.lock().expect("state");
        let rows = st.store.db().group_messages_by_seq(&[seq])?;
        Ok(rows.into_iter().find(|(_, g, _, _)| g.as_slice() == gid).map(|(_, _, id, _)| id.to_vec()).unwrap_or_default())
    }

    /// This phone's seq of the group message an envelope of the group answers (its `reply_to`);
    /// 0 for none, and for a message that is not here.
    pub fn group_reply_seq(&self, gid: &[u8], reply_to: &[u8]) -> Result<i64> {
        if reply_to.is_empty() {
            return Ok(0);
        }
        Ok(self.inner.state.lock().expect("state").store.db().group_message_seq(gid, reply_to)?.unwrap_or(0))
    }

    /// Our reaction to message `target` of a group or channel (an empty emoji takes it back): any
    /// member, a channel's readers too. Kept here and sent to the members like a message.
    pub fn group_react(&self, gid: &[u8], target: &[u8; 16], emoji: &str) -> Result<()> {
        if emoji.len() > 32 {
            return Err(Error::Envelope("bad reaction"));
        }
        let mut env = Envelope::new(Body::Reaction(crate::envelope::Reaction { target: target.to_vec(), emoji: emoji.to_string(), remove: emoji.is_empty() }));
        env.group_id = gid.to_vec();
        self.group_send(&env)?;
        Ok(())
    }

    /// The reactions standing in a group: (message id, who, emoji), oldest first.
    pub fn group_reactions(&self, gid: &[u8]) -> Result<Vec<([u8; 16], UserId, String)>> {
        self.inner.state.lock().expect("state").store.db().reactions(gid)
    }

    /// A poll in the group or channel (SPEC 5 `Poll`, T69): 2 to 10 options, by whoever may post
    /// polls there; in answer to message `reply_to` (its seq here; 0 for none).
    pub fn group_send_poll(&self, gid: &[u8], poll: Poll, reply_to: i64) -> Result<[u8; 16]> {
        let mut env = Envelope::new(Body::Poll(poll));
        env.group_id = gid.to_vec();
        env.reply_to = self.group_reply_id(gid, reply_to)?;
        crate::envelope::validate(&env)?;
        self.group_send(&env)
    }

    /// Our vote on poll `poll` (its message id) of a group or channel: the options chosen, none
    /// to take it back. Any member votes, a channel's readers too; the vote goes to every member
    /// and each phone counts (T69). A quiz takes one answer, once; a closed poll none.
    pub fn group_vote(&self, gid: &[u8], poll: &[u8; 16], choices: &[u32]) -> Result<()> {
        {
            let st = self.inner.state.lock().expect("state");
            let db = st.store.db();
            let p = poll_of(&db, gid, poll)?.ok_or(Error::NotFound("poll"))?;
            if !p.accepts(choices) || !p.open_at(now_ms()) {
                return Err(Error::Envelope("bad vote"));
            }
            if p.quiz && db.poll_votes(gid, poll)?.iter().any(|(v, _)| *v == st.identity.sk()) {
                return Err(Error::Envelope("already answered"));
            }
        }
        let mut env = Envelope::new(Body::PollVote(crate::envelope::PollVote { poll_id: poll.to_vec(), choices: choices.to_vec() }));
        env.group_id = gid.to_vec();
        self.group_send(&env)?;
        Ok(())
    }

    /// Where a poll of a group stands: votes for each option, how many voted, our own choices,
    /// and who chose what (empty for an anonymous poll: no app shows the voters, T69). Votes
    /// that do not fit the poll (one that came before it, from a changed app) are not counted.
    pub fn poll_results(&self, gid: &[u8], poll: &[u8; 16]) -> Result<PollResults> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let p = poll_of(&db, gid, poll)?.ok_or(Error::NotFound("poll"))?;
        let me = st.identity.sk();
        let mut out = PollResults { counts: vec![0; p.options.len()], ..PollResults::default() };
        for (voter, choices) in db.poll_votes(gid, poll)? {
            if !p.accepts(&choices) {
                continue;
            }
            out.voters += 1;
            for &c in &choices {
                out.counts[c as usize] += 1;
            }
            if voter == me {
                out.mine = choices.clone();
            }
            if !p.anonymous {
                out.who.push((voter, choices));
            }
        }
        Ok(out)
    }

    /// Take group or channel messages down for every member (SPEC 5.2; Ehsan, 25 Oct 2026: «باید
    /// برای همه حذف بشه»): ours, or anyone's when we are an admin with the right. The others go
    /// nowhere. Returns how many went (here and, as a `Delete`, to the members).
    pub fn group_delete(&self, gid: &[u8], ids: &[[u8; 16]]) -> Result<usize> {
        let now = now_ms();
        let allowed: Vec<[u8; 16]> = {
            let st = self.inner.state.lock().expect("state");
            let db = st.store.db();
            let me = st.identity.sk();
            let state = load_log(&db, gid)?.state(now).ok_or(Error::NotFound("group"))?;
            let any = state.admin_rights(&me) & crate::group::admin::DELETE != 0;
            let mut out = Vec::new();
            for id in ids {
                if db.group_message_find(gid, id)?.is_some_and(|(_, author)| author == me || any) {
                    out.push(*id);
                }
            }
            out
        };
        if allowed.is_empty() {
            return Err(Error::Session("not allowed to delete these messages"));
        }
        for chunk in allowed.chunks(crate::envelope::MAX_IDS) {
            let mut env = Envelope::new(Body::Delete(crate::envelope::Delete { ids: chunk.iter().map(|i| i.to_vec()).collect(), for_everyone: true }));
            env.group_id = gid.to_vec();
            self.group_send(&env)?;
        }
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        for id in &allowed {
            db.group_message_blank(gid, id)?;
        }
        Ok(allowed.len())
    }

    /// Group messages by this phone's seqs (as `group_messages` gives them): for every member when
    /// `everyone` (as `group_delete`: ours, or anyone's for an admin), else on this phone only.
    pub fn group_delete_seqs(&self, seqs: &[i64], everyone: bool) -> Result<usize> {
        let rows = self.inner.state.lock().expect("state").store.db().group_messages_by_seq(seqs)?;
        let mut by_group: HashMap<Vec<u8>, Vec<[u8; 16]>> = HashMap::new();
        for (_, gid, id, _) in rows {
            by_group.entry(gid).or_default().push(id);
        }
        let mut n = 0;
        for (gid, ids) in by_group {
            if everyone {
                // what we may not take down for everyone still goes from this phone
                n += self.group_delete(&gid, &ids).unwrap_or(0);
            }
            let st = self.inner.state.lock().expect("state");
            let db = st.store.db();
            for id in &ids {
                n += usize::from(db.group_message_blank(&gid, id)?);
            }
        }
        Ok(n)
    }

    /// Give a group or channel message we sent (by this phone's seq) a new text, or a media
    /// message a new caption, for every member (SPEC 5 `Edit`). Only one's own: an admin takes
    /// others' messages down but does not put words in them.
    pub fn group_edit_text(&self, seq: i64, text: &str, entities: Vec<Entity>) -> Result<()> {
        let now = now_ms();
        let (gid, edit) = {
            let st = self.inner.state.lock().expect("state");
            let db = st.store.db();
            let me = st.identity.sk();
            let (_, gid, id, sender) = db.group_messages_by_seq(&[seq])?.into_iter().next().ok_or(Error::NotFound("message"))?;
            let row = db.group_message_by_id(&gid, &id)?.ok_or(Error::NotFound("message"))?;
            if sender != me || !row.outgoing {
                return Err(Error::Session("only one's own messages are edited"));
            }
            let stored = Envelope::from_bytes(&row.envelope)?;
            let edit = crate::envelope::edit_for(&stored, &id, text, entities).ok_or(Error::Session("this message has no text to edit"))?;
            let new = crate::envelope::apply_edit(&stored, &edit).ok_or(Error::Session("this message has no text to edit"))?;
            db.group_message_edit(&gid, &id, &new.to_bytes()?, now)?;
            (gid, edit)
        };
        let mut env = Envelope::new(Body::Edit(edit));
        env.group_id = gid.clone();
        self.group_send(&env)?;
        self.inner.emit(Event::Edited { chat: gid, group: true, seq });
        Ok(())
    }

    pub fn group_send(&self, env: &Envelope) -> Result<[u8; 16]> {
        let now = now_ms();
        let gid = env.group_id.clone();
        let bytes = env.to_bytes()?;
        let id = array::<16>(&env.id).ok_or(Error::Envelope("id must be 16 bytes"))?;
        let reaction = match &env.body {
            Some(Body::Reaction(r)) => Some((array::<16>(&r.target).ok_or(Error::Envelope("bad reaction"))?, if r.remove { String::new() } else { r.emoji.clone() })),
            _ => None,
        };
        let vote = match &env.body {
            Some(Body::PollVote(v)) => Some((array::<16>(&v.poll_id).ok_or(Error::Envelope("bad vote"))?, v.choices.clone())),
            _ => None,
        };
        // a reaction, a vote, a delete or an edit is no post: any member sends it (an edit, only
        // of their own message), and it is no message of its own
        let control = reaction.is_some() || vote.is_some() || matches!(env.body, Some(Body::Delete(_)) | Some(Body::Edit(_)));
        let mut reacted: Option<UserId> = None;
        {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let me = identity.sk();
            let st = load_log(&store.db(), &gid)?.state(now).ok_or(Error::NotFound("group"))?;
            if !st.is_member(&me) || (!control && (!st.can_post(&me, now) || st.member_rights(&me, now) & right_for(&env.body) == 0)) {
                return Err(Error::Session("not allowed to post in this group"));
            }
            // slow mode (q13, T73): our next post only once its time has come, as every phone judges it
            if !control && slow_too_soon(&store.db(), &st, &me, env)? {
                return Err(Error::Session("slow mode: not yet"));
            }
            let identity: &Identity = identity;
            store.transaction(|db| {
                // our session: new on first use, after a member left, every 100 messages or 7 days
                let mut fresh = false;
                let mut out = match db.megolm_out(&gid)? {
                    Some((p, created, sent)) if sent < megolm::ROTATE_AFTER && now.saturating_sub(created) < megolm::ROTATE_MS => megolm::load_out(&p)?,
                    _ => {
                        fresh = true;
                        megolm::new_outbound()
                    }
                };
                let others: Vec<UserId> = st.members.keys().filter(|m| **m != me).copied().collect();
                if fresh {
                    share_key(db, identity, &st, &out, &others)?;
                }
                // the key from this message on, for members we cannot reach yet (below)
                let (sid, key_here, idx_here) = (out.session_id(), megolm::session_key(&out), out.message_index());
                let cipher = megolm::encrypt(&mut out, &bytes);
                let (created, sent) = match db.megolm_out(&gid)? {
                    Some((_, c, s)) if !fresh => (c, s + 1),
                    _ => (now, 1),
                };
                db.megolm_out_save(&gid, &megolm::pickle_out(&out), created, sent)?;
                for sk in &others {
                    let frame = frame::encode_padded(&gid, KIND_MEGOLM, &cipher)?;
                    let key = frame_id(&[&sk[..], &frame[..]].concat());
                    let Some(c) = member_contact(db, sk, st.members.get(sk))? else {
                        // a member whose own card has not come yet: the frame waits in the queue
                        // for them, and they are owed our key from this message on, given when
                        // their card comes (`on_ops`). Before, they never got this message
                        // (tests/group.rs failed now and then; more often with deliveries in parallel).
                        db.group_owed_add(&gid, sk, &sid, &key_here, idx_here, now)?;
                        db.enqueue(&OutRow { frame_id: key, peer: *sk, msg_id: None, frame, attempts: 0, next_ms: now + 2_000, last_error: None })?;
                        continue;
                    };
                    if c.blocked {
                        continue;
                    }
                    // one row per member: the same frame bytes go to each
                    db.enqueue(&OutRow { frame_id: key, peer: *sk, msg_id: None, frame, attempts: 0, next_ms: 0, last_error: None })?;
                }
                if let Some((target, emoji)) = &reaction {
                    db.reaction_set(&gid, target, &me, emoji, env.sent_ms)?;
                } else if let Some((poll, choices)) = &vote {
                    let quiz = poll_of(db, &gid, poll)?.is_some_and(|p| p.quiz);
                    db.poll_vote_set(&gid, poll, &me, choices, env.sent_ms, quiz)?;
                } else if !control {
                    db.group_message_save(&GroupMessageRow { id, gid: gid.clone(), sender: me, outgoing: true, sent_ms: env.sent_ms, at_ms: now, kind: env.kind_name().to_string(), envelope: bytes.clone(), state: MsgState::Sent, seq: 0 })?;
                }
                Ok(())
            })?;
            if reaction.is_some() || vote.is_some() {
                reacted = Some(me);
            }
        }
        if let (Some(me), Some((target, _))) = (reacted, &reaction) {
            self.inner.emit(Event::Reaction { chat: gid.clone(), group: true, id: *target, peer: me });
        }
        if let (Some(me), Some((poll, _))) = (reacted, &vote) {
            self.inner.emit(Event::PollVote { chat: gid.clone(), group: true, id: *poll, peer: me });
        }
        self.inner.waker.wake();
        Ok(id)
    }
}
