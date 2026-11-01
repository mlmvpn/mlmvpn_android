//! Group and channel invite links (SPEC 7.4; step 6.3 of the work queue).
//!
//! - A link (`mlmchat://g/…`) carries the group, an invite id, a secret, the group's name and
//!   the inviter's card. The group's log holds the invite with only the secret's hash.
//! - Joining: the joiner reaches the inviter from the card in the link (a hidden contact row,
//!   never in the contact list) and sends a `KeyUpdate` with its own card and a `JoinRequest`
//!   (the secret), inside Olm like any first message.
//! - The inviter's phone checks the secret against the log. A link without approval: it adds
//!   the joiner at once (any member may, with a valid invite). With approval: the request waits
//!   on the phones of admins who may invite, until one approves or declines.
//! - The joiner becomes a member when the `AddMember` reaches it, through the usual group log.

use super::groups::load_log;
use super::{enqueue, Event, Inner, Node, State};
use crate::card::Card;
use crate::envelope::{Body, Envelope, JoinRequest, KeyUpdate};
use crate::group::{self, admin, AddMember, CreateInvite, InviteLink, Op, RevokeInvite};
use crate::identity::Identity;
use crate::store::{Contact, Db};
use crate::util::{now_ms, random};
use crate::{Error, Result, UserId};

/// What a link says before joining.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InviteInfo {
    pub group_id: Vec<u8>,
    pub invite_id: Vec<u8>,
    pub name: String,
    pub broadcast: bool,
    pub need_approval: bool,
    pub inviter: UserId,
    pub exp_ms: u64,
    /// We are already a member.
    pub member: bool,
    /// We asked to join and wait.
    pub asked: bool,
}

/// An invite as the app lists it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InviteView {
    pub invite_id: Vec<u8>,
    /// Only for invites this phone made (only it has the secret).
    pub link: Option<String>,
    pub by: UserId,
    pub exp_ms: u64,
    pub max_uses: u32,
    pub uses: u32,
    pub need_approval: bool,
    pub created_ms: u64,
}

/// Someone waiting for approval.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JoinRequestView {
    pub sk: UserId,
    /// The name their card suggests.
    pub name: String,
    pub at_ms: u64,
}

/// A join the inviter's phone may do at once (after the Olm transaction).
pub(super) struct AutoAdd {
    pub gid: Vec<u8>,
    pub op: Op,
}

/// Inside the Olm transaction: `from` asks to join with an invite's secret; `card` is theirs.
/// Returns the add to make at once, or None (kept for approval, or refused).
pub(super) fn on_join_request(db: &Db, identity: &Identity, from: &UserId, card: &[u8], j: &JoinRequest, events: &mut Vec<Event>) -> Result<Option<AutoAdd>> {
    let now = now_ms();
    let me = identity.sk();
    let Some(st) = load_log(db, &j.group_id)?.state(now) else { return Ok(None) };
    if !st.is_member(&me) || st.is_member(from) || st.dissolved {
        return Ok(None);
    }
    let Some(inv) = st.invites.get(&j.invite_id) else {
        log::debug!("node: a join request for an unknown invite");
        return Ok(None);
    };
    let expired = inv.exp_ms != 0 && now >= inv.exp_ms;
    let used_up = inv.max_uses > 0 && inv.uses >= inv.max_uses;
    if group::secret_hash(&j.secret) != inv.secret_hash || expired || used_up || st.banned.get(from).is_some_and(|until| now < *until) {
        log::debug!("node: a join request refused");
        return Ok(None);
    }
    if inv.need_approval {
        if st.admin_rights(&me) & admin::INVITE != 0 {
            db.join_request_save(&j.group_id, from, &j.invite_id, card, now)?;
            events.push(Event::JoinRequest { gid: j.group_id.clone(), peer: *from });
        }
        return Ok(None);
    }
    Ok(Some(AutoAdd { gid: j.group_id.clone(), op: Op::Add(AddMember { sk: from.to_vec(), card: card.to_vec(), invite_id: j.invite_id.clone() }) }))
}

impl Inner {
    /// After the transaction: add a joiner whose link needed no approval.
    pub(super) fn auto_add(&self, add: AutoAdd) {
        match self.group_change(&add.gid, add.op) {
            Ok(_) => log::info!("node: a member joined by a link"),
            Err(e) => log::debug!("node: a join by link not made ({e})"),
        }
    }

    fn invite_link(&self, identity: &mut Identity, st: &group::GroupState, invite_id: &[u8], secret: &[u8]) -> Result<String> {
        let inv = st.invites.get(invite_id).ok_or(Error::NotFound("invite"))?;
        let card = self.session_card_bytes(identity)?;
        Ok(InviteLink {
            v: 1,
            group_id: st.group_id.clone(),
            invite_id: invite_id.to_vec(),
            secret: secret.to_vec(),
            name: st.name.clone(),
            broadcast: st.broadcast,
            need_approval: inv.need_approval,
            card,
            exp_ms: inv.exp_ms,
        }
        .to_link())
    }
}

impl Node {
    /// A new invite link for a group or channel (needs the right to invite). `exp_ms` 0: never
    /// expires; `max_uses` 0: no limit. Returns the link.
    pub fn group_invite_create(&self, gid: &[u8], need_approval: bool, exp_ms: u64, max_uses: u32) -> Result<String> {
        let invite_id = random::<16>().to_vec();
        let secret = random::<32>().to_vec();
        let op = Op::Invite(CreateInvite { invite_id: invite_id.clone(), secret_hash: group::secret_hash(&secret), exp_ms, max_uses, need_approval });
        let st = self.inner.group_change(gid, op)?;
        let mut guard = self.inner.state.lock().expect("state");
        let State { store, identity } = &mut *guard;
        let link = self.inner.invite_link(identity, &st, &invite_id, &secret)?;
        store.db().save_identity(&identity.to_saved())?;
        store.db().invite_secret_save(&invite_id, gid, &secret, &link)?;
        Ok(link)
    }

    /// The group's invites still valid, newest last; links only for those this phone made.
    pub fn group_invites(&self, gid: &[u8]) -> Result<Vec<InviteView>> {
        let st = self.group_state(gid)?.ok_or(Error::NotFound("group"))?;
        let mine = self.inner.state.lock().expect("state").store.db().invite_secrets(gid)?;
        let mut out = Vec::new();
        for (id, inv) in &st.invites {
            let own = mine.iter().find(|(i, _, _)| i == id);
            out.push(InviteView {
                invite_id: id.clone(),
                link: own.map(|(_, l, _)| l.clone()),
                by: inv.by,
                exp_ms: inv.exp_ms,
                max_uses: inv.max_uses,
                uses: inv.uses,
                need_approval: inv.need_approval,
                created_ms: own.map(|(_, _, c)| *c).unwrap_or(0),
            });
        }
        out.sort_by_key(|v| v.created_ms);
        Ok(out)
    }

    /// Revoke an invite: its link stops working for everyone.
    pub fn group_invite_revoke(&self, gid: &[u8], invite_id: &[u8]) -> Result<()> {
        self.inner.group_change(gid, Op::Revoke(RevokeInvite { invite_id: invite_id.to_vec() }))?;
        self.inner.state.lock().expect("state").store.db().invite_secret_drop(invite_id)?;
        Ok(())
    }

    /// What a link says, without joining.
    pub fn invite_info(&self, link: &str) -> Result<InviteInfo> {
        let l = InviteLink::from_link(link)?;
        let card = Card::from_bytes(&l.card)?;
        let me = self.me().sk;
        let member = self.group_state(&l.group_id)?.is_some_and(|s| s.is_member(&me));
        let asked = self.inner.state.lock().expect("state").store.db().joining()?.iter().any(|j| j.0 == l.group_id);
        Ok(InviteInfo {
            group_id: l.group_id,
            invite_id: l.invite_id,
            name: l.name,
            broadcast: l.broadcast,
            need_approval: l.need_approval,
            inviter: card.sk,
            exp_ms: l.exp_ms,
            member,
            asked,
        })
    }

    /// Ask to join by a link: the request goes to the inviter's phone (queued until it is
    /// reachable). We are a member once the inviter, or an admin, adds us. Returns the group id.
    pub fn group_join(&self, link: &str) -> Result<Vec<u8>> {
        let l = InviteLink::from_link(link)?;
        let card = Card::from_bytes(&l.card)?;
        let loc = card.loc().ok_or(Error::Card("the inviter's card cannot be reached"))?;
        if l.exp_ms != 0 && now_ms() >= l.exp_ms {
            return Err(Error::Link("this link has expired"));
        }
        {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let me = identity.sk();
            if card.sk == me {
                return Err(Error::Link("this is your own link"));
            }
            if load_log(&store.db(), &l.group_id)?.state(now_ms()).is_some_and(|s| s.is_member(&me)) {
                return Ok(l.group_id);
            }
            let known = store.db().contact(&card.sk)?;
            let inviter = known.clone().unwrap_or_else(|| Contact {
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
            });
            if inviter.blocked {
                return Err(Error::Session("the inviter is blocked"));
            }
            let my_card = self.inner.session_card_bytes(identity)?;
            let env = Envelope::new(Body::KeyUpdate(KeyUpdate {
                loc: identity.loc().to_vec(),
                card: my_card,
                join: Some(JoinRequest { group_id: l.group_id.clone(), invite_id: l.invite_id.clone(), secret: l.secret.clone() }),
            }));
            let saved = identity.to_saved();
            let identity: &Identity = identity;
            store.transaction(|db| {
                db.save_identity(&saved)?;
                if known.is_none() {
                    // reached for this group only: never in the contact list
                    db.upsert_contact(&inviter)?;
                    db.mark_group_peer(&inviter.sk)?;
                }
                db.joining_save(&l.group_id, &inviter.sk, &l.name, l.broadcast)?;
                enqueue(db, identity, &inviter, &env, false)?;
                Ok(())
            })?;
        }
        log::info!("node: asked to join a group");
        self.inner.waker.wake();
        Ok(l.group_id)
    }

    /// The requests waiting for approval in a group (on this phone).
    pub fn group_join_requests(&self, gid: &[u8]) -> Result<Vec<JoinRequestView>> {
        let st = self.inner.state.lock().expect("state");
        Ok(st
            .store
            .db()
            .join_requests(gid)?
            .into_iter()
            .map(|(sk, _, card, at_ms)| JoinRequestView { sk, name: Card::from_bytes(&card).ok().and_then(|c| c.name).unwrap_or_default(), at_ms })
            .collect())
    }

    /// Approve (add them) or decline a request. Declining tells them nothing.
    pub fn group_join_decide(&self, gid: &[u8], peer: &UserId, approve: bool) -> Result<()> {
        let req = {
            let st = self.inner.state.lock().expect("state");
            st.store.db().join_requests(gid)?.into_iter().find(|r| r.0 == *peer)
        };
        let (sk, invite_id, card, _) = req.ok_or(Error::NotFound("join request"))?;
        if approve {
            self.inner.group_change(gid, Op::Add(AddMember { sk: sk.to_vec(), card, invite_id }))?;
        }
        self.inner.state.lock().expect("state").store.db().join_request_drop(gid, peer)?;
        self.inner.emit(Event::Group { gid: gid.to_vec() });
        Ok(())
    }

    /// The groups we asked to join and still wait for: (group, name, broadcast, when).
    pub fn joining(&self) -> Result<Vec<(Vec<u8>, String, bool, u64)>> {
        let st = self.inner.state.lock().expect("state");
        Ok(st.store.db().joining()?.into_iter().map(|(g, _, n, b, at)| (g, n, b, at)).collect())
    }
}
