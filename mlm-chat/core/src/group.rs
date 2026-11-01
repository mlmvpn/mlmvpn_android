//! Groups and channels (SPEC 7, phase 5): the group's state log.
//!
//! A group is the set of its signed events (`GroupOp`), each naming the events it follows
//! (`parents`). Every member's phone holds the same events, so every phone builds the same state
//! from them: the events are put in one order that is the same everywhere (parents first; among
//! events ready together, the author's rank in the state built so far, then the event id), and
//! applied one by one. An event takes effect only if its author has that right in the state at
//! that point; an invalid one is kept but changes nothing. So when the owner removes an admin at
//! the same time as that admin does something, the owner's event comes first and the admin's no
//! longer counts (SPEC 7.2).
//!
//! On the network an event is `SignedOp { body, sig }`: `body` is the exact protobuf bytes the
//! author signed (Ed25519 by `author_sk`), so an event from a newer version with fields this one
//! does not know still verifies. Event id = SHA-256(body || sig).
//!
//! A channel (T37) is a group with `broadcast` set: members' default rights are zero, so only the
//! owner and admins post.

use crate::transport::session::verify;
use crate::{Error, Result, UserId};
use prost::Message;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet, HashMap};

pub type OpId = [u8; 32];
pub const GROUP_ID_LEN: usize = 16;
pub const MAX_PARENTS: usize = 16;
pub const MAX_NAME: usize = 128;
pub const MAX_ABOUT: usize = 512;
pub const MAX_TITLE: usize = 16;
pub const MAX_CARD: usize = 4096;
/// SPEC 7.5 (T13): about 200 members in version 1.
pub const MAX_MEMBERS: usize = 200;

/// Admin rights (SPEC 7.1).
pub mod admin {
    pub const CHANGE_INFO: u32 = 1;
    pub const DELETE: u32 = 2;
    pub const BAN: u32 = 4;
    pub const INVITE: u32 = 8;
    pub const PIN: u32 = 16;
    pub const ADD_ADMINS: u32 = 32;
    pub const ANONYMOUS: u32 = 64;
    pub const TOPICS: u32 = 128;
    pub const ALL: u32 = 255;
}

/// Member rights (SPEC 7.1).
pub mod member {
    pub const TEXT: u32 = 1;
    pub const PHOTO: u32 = 2;
    pub const VIDEO: u32 = 4;
    pub const FILE: u32 = 8;
    pub const VOICE: u32 = 16;
    pub const ROUND: u32 = 32;
    pub const STICKER: u32 = 64;
    pub const POLL: u32 = 128;
    pub const LINK: u32 = 256;
    pub const ADD_MEMBERS: u32 = 512;
    pub const PIN: u32 = 1024;
    pub const CHANGE_INFO: u32 = 2048;
    /// What a new group gives its members: everything but changing the info and pinning.
    pub const DEFAULT: u32 = TEXT | PHOTO | VIDEO | FILE | VOICE | ROUND | STICKER | POLL | LINK | ADD_MEMBERS;
}

#[derive(Clone, PartialEq, Message)]
pub struct GroupOp {
    #[prost(bytes = "vec", tag = "1")]
    pub group_id: Vec<u8>,
    #[prost(bytes = "vec", repeated, tag = "2")]
    pub parents: Vec<Vec<u8>>,
    #[prost(bytes = "vec", tag = "3")]
    pub author_sk: Vec<u8>,
    #[prost(uint64, tag = "4")]
    pub ts_ms: u64,
    #[prost(oneof = "Op", tags = "10, 11, 12, 13, 14, 15, 16, 17, 19, 20, 22, 23, 24, 28, 29")]
    pub op: Option<Op>,
}

#[derive(Clone, PartialEq, prost::Oneof)]
pub enum Op {
    #[prost(message, tag = "10")]
    Create(Create),
    #[prost(message, tag = "11")]
    SetInfo(SetInfo),
    #[prost(message, tag = "12")]
    Add(AddMember),
    #[prost(message, tag = "13")]
    Remove(RemoveMember),
    #[prost(message, tag = "14")]
    SetRole(SetRole),
    #[prost(message, tag = "15")]
    Defaults(SetDefaults),
    #[prost(message, tag = "16")]
    Restrict(Restrict),
    /// Slow mode in a group (q13, T73): a member posts again only this many seconds after their
    /// last post. A phone from before it keeps the event with no effect, and holds no one back.
    #[prost(message, tag = "17")]
    SlowMode(SetSlowMode),
    #[prost(message, tag = "19")]
    Invite(CreateInvite),
    #[prost(message, tag = "20")]
    Revoke(RevokeInvite),
    /// Pin or unpin a message (T65): admins with the pin right, members when the group gives it.
    /// A phone from before it keeps the event with no effect.
    #[prost(message, tag = "22")]
    Pin(PinMessage),
    #[prost(message, tag = "23")]
    Transfer(TransferOwner),
    #[prost(message, tag = "24")]
    Dissolve(Dissolve),
    #[prost(message, tag = "28")]
    SetCard(SetCard),
    /// Lift a ban without taking the member back (T59). A phone from before it keeps the event
    /// with no effect, and the ban stands there until it is updated.
    #[prost(message, tag = "29")]
    Unban(Unban),
}

#[derive(Clone, PartialEq, Message)]
pub struct Create {
    #[prost(string, tag = "1")]
    pub name: String,
    #[prost(string, tag = "2")]
    pub about: String,
    /// Members' default rights.
    #[prost(uint32, tag = "4")]
    pub defaults: u32,
    /// A channel (T37): only the owner and admins post.
    #[prost(bool, tag = "5")]
    pub broadcast: bool,
    /// The group secret (32 bytes): group tags and rendezvous keys come from it (SPEC 7.3).
    #[prost(bytes = "vec", tag = "6")]
    pub secret: Vec<u8>,
    /// The owner's face-to-face card, so every member can reach the owner.
    #[prost(bytes = "vec", tag = "7")]
    pub owner_card: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct SetInfo {
    #[prost(string, tag = "1")]
    pub name: String,
    #[prost(string, tag = "2")]
    pub about: String,
    /// The photo below replaces the group's (none: no photo); unset, the photo stays as it is.
    #[prost(bool, tag = "3")]
    pub photo_set: bool,
    #[prost(message, optional, tag = "4")]
    pub photo: Option<ChatPhoto>,
    /// The owner's choice for Gereh's public list (q6, T70): 0 leaves it as it is, 1 in, 2 out. Only
    /// the owner's counts; admins' phones follow it when they put the chat's card up. A phone from
    /// before it keeps the event with no change.
    #[prost(uint32, tag = "5")]
    pub listed: u32,
}

/// A group's or channel's photo: a sealed file (`blob.rs`) members fetch from whoever set it.
#[derive(Clone, PartialEq, Eq, Message)]
pub struct ChatPhoto {
    #[prost(bytes = "vec", tag = "1")]
    pub blob_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub key: Vec<u8>,
    /// Sealed size.
    #[prost(uint64, tag = "3")]
    pub size: u64,
    /// A tiny preview, at most 4 KiB.
    #[prost(bytes = "vec", tag = "4")]
    pub thumb: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct AddMember {
    #[prost(bytes = "vec", tag = "1")]
    pub sk: Vec<u8>,
    /// The member's face-to-face card: every member can reach every member (SPEC 7.3).
    #[prost(bytes = "vec", tag = "2")]
    pub card: Vec<u8>,
    #[prost(bytes = "vec", tag = "3")]
    pub invite_id: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct RemoveMember {
    #[prost(bytes = "vec", tag = "1")]
    pub sk: Vec<u8>,
    /// Banned until then (unix ms); 0 = not banned; `u64::MAX` = for good.
    #[prost(uint64, tag = "2")]
    pub ban_until_ms: u64,
}

#[derive(Clone, PartialEq, Message)]
pub struct SetRole {
    #[prost(bytes = "vec", tag = "1")]
    pub sk: Vec<u8>,
    #[prost(bool, tag = "2")]
    pub admin: bool,
    #[prost(uint32, tag = "3")]
    pub rights: u32,
    #[prost(string, tag = "4")]
    pub title: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct SetDefaults {
    #[prost(uint32, tag = "1")]
    pub rights: u32,
}

#[derive(Clone, PartialEq, Message)]
pub struct Restrict {
    #[prost(bytes = "vec", tag = "1")]
    pub sk: Vec<u8>,
    #[prost(uint32, tag = "2")]
    pub rights: u32,
    #[prost(uint64, tag = "3")]
    pub until_ms: u64,
}

/// A group's slow mode (q13, T73), in seconds, at most [`crate::feed::MAX_SLOW_S`]; 0 ends it.
/// Only an admin with the right to ban sets it, and only in a group: a channel's members do not post.
#[derive(Clone, PartialEq, Message)]
pub struct SetSlowMode {
    #[prost(uint32, tag = "1")]
    pub seconds: u32,
}

/// Whether a member's post at `sent_ms` (of album `album`, empty for none) comes too near another
/// post of theirs kept here, under slow mode `slow_s` (q13, T73): less than `slow_s` before or
/// after it, unless both are of one album (an album counts as one post, as in Telegram). `near`
/// is their other posts: (time, album). Either order: a late copy of an earlier post is judged
/// the same, so a member who kept to it never loses one.
pub fn slow_clash(slow_s: u32, sent_ms: u64, album: &[u8], near: &[(u64, Vec<u8>)]) -> bool {
    let span = u64::from(slow_s) * 1000;
    slow_s > 0 && near.iter().any(|(t, a)| sent_ms.abs_diff(*t) < span && (album.is_empty() || a.as_slice() != album))
}

#[derive(Clone, PartialEq, Message)]
pub struct CreateInvite {
    #[prost(bytes = "vec", tag = "1")]
    pub invite_id: Vec<u8>,
    /// SHA-256 of the invite secret that is in the link only.
    #[prost(bytes = "vec", tag = "2")]
    pub secret_hash: Vec<u8>,
    #[prost(uint64, tag = "3")]
    pub exp_ms: u64,
    #[prost(uint32, tag = "4")]
    pub max_uses: u32,
    #[prost(bool, tag = "5")]
    pub need_approval: bool,
}

#[derive(Clone, PartialEq, Message)]
pub struct RevokeInvite {
    #[prost(bytes = "vec", tag = "1")]
    pub invite_id: Vec<u8>,
}

/// An invite link (SPEC 7.4): `mlmchat://g/` and the base64url of this. Whoever holds it can
/// ask the inviter (whose card it carries) to be added; the secret proves holding it.
#[derive(Clone, PartialEq, Message)]
pub struct InviteLink {
    #[prost(uint32, tag = "1")]
    pub v: u32,
    #[prost(bytes = "vec", tag = "2")]
    pub group_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "3")]
    pub invite_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "4")]
    pub secret: Vec<u8>,
    /// The group's name when the link was made, shown before joining.
    #[prost(string, tag = "5")]
    pub name: String,
    #[prost(bool, tag = "6")]
    pub broadcast: bool,
    #[prost(bool, tag = "7")]
    pub need_approval: bool,
    /// The inviter's card (with its fallback key, addresses and volunteers).
    #[prost(bytes = "vec", tag = "8")]
    pub card: Vec<u8>,
    #[prost(uint64, tag = "9")]
    pub exp_ms: u64,
}

pub const INVITE_PREFIX: &str = "mlmchat://g/";

impl InviteLink {
    pub fn to_link(&self) -> String {
        format!("{INVITE_PREFIX}{}", crate::util::b64url_encode(&self.encode_to_vec()))
    }

    /// From an `mlmchat://g/` link, or from its base64url part alone (as Telegram's
    /// `tg://join?invite=` carries it).
    pub fn from_link(link: &str) -> Result<InviteLink> {
        let link = link.trim();
        let body = if link.len() >= INVITE_PREFIX.len() && link[..INVITE_PREFIX.len()].eq_ignore_ascii_case(INVITE_PREFIX) { &link[INVITE_PREFIX.len()..] } else { link };
        if body.len() > 4 * MAX_CARD {
            return Err(Error::Link("invite link too long"));
        }
        let bytes = crate::util::b64url_decode(body).ok_or(Error::Link("bad base64url"))?;
        let l = InviteLink::decode(bytes.as_slice()).map_err(|_| Error::Link("not an invite link"))?;
        if l.v != 1 || l.group_id.len() != GROUP_ID_LEN || l.invite_id.len() != 16 || l.secret.len() != 32 || l.name.len() > MAX_NAME || l.card.len() > MAX_CARD {
            return Err(Error::Link("not an invite link"));
        }
        Ok(l)
    }

    /// The base64url part only (for `tg://join?invite=`).
    pub fn payload(&self) -> String {
        crate::util::b64url_encode(&self.encode_to_vec())
    }
}

/// SHA-256 of an invite secret, as `CreateInvite` keeps it.
pub fn secret_hash(secret: &[u8]) -> Vec<u8> {
    Sha256::digest(secret).to_vec()
}

#[derive(Clone, PartialEq, Message)]
pub struct TransferOwner {
    #[prost(bytes = "vec", tag = "1")]
    pub sk: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct Dissolve {}

/// A member's own card (with its fallback key), so members who are not its contacts can reach
/// it. Only the member sets it. The card in `AddMember` is whatever the adder holds, often a QR
/// card whose one-time key the adder already used.
#[derive(Clone, PartialEq, Message)]
pub struct SetCard {
    #[prost(bytes = "vec", tag = "1")]
    pub card: Vec<u8>,
}

/// A ban lifted (T59): only by an admin with the right to ban. The member stays out until added.
#[derive(Clone, PartialEq, Message)]
pub struct Unban {
    #[prost(bytes = "vec", tag = "1")]
    pub sk: Vec<u8>,
}

/// A message pinned or unpinned (SPEC 7.2, T65): its id (the envelope's, 16 bytes).
#[derive(Clone, PartialEq, Message)]
pub struct PinMessage {
    #[prost(bytes = "vec", tag = "1")]
    pub target: Vec<u8>,
    #[prost(bool, tag = "2")]
    pub unpin: bool,
}

/// Messages a group keeps pinned at once: the oldest goes when one more is pinned.
pub const MAX_PINNED: usize = 100;

/// An event as it travels: the signed bytes and the signature.
#[derive(Clone, PartialEq, Message)]
pub struct SignedOp {
    #[prost(bytes = "vec", tag = "1")]
    pub body: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub sig: Vec<u8>,
}

impl SignedOp {
    /// Sign `op` with the author's identity key.
    pub fn sign(op: &GroupOp, sign: impl FnOnce(&[u8]) -> [u8; 64]) -> SignedOp {
        let body = op.encode_to_vec();
        let sig = sign(&body).to_vec();
        SignedOp { body, sig }
    }

    pub fn id(&self) -> OpId {
        let mut h = Sha256::new();
        h.update(&self.body);
        h.update(&self.sig);
        h.finalize().into()
    }

    /// The event, if its signature is the author's and its fields are within the caps.
    pub fn open(&self) -> Result<GroupOp> {
        let op = GroupOp::decode(self.body.as_slice())?;
        let sk: UserId = op.author_sk.as_slice().try_into().map_err(|_| Error::Envelope("group op author"))?;
        let sig: [u8; 64] = self.sig.as_slice().try_into().map_err(|_| Error::Envelope("group op signature"))?;
        if !verify(&sk, &self.body, &sig) {
            return Err(Error::Envelope("group op signature"));
        }
        if op.group_id.len() != GROUP_ID_LEN || op.parents.len() > MAX_PARENTS || op.parents.iter().any(|p| p.len() != 32) {
            return Err(Error::Envelope("group op header"));
        }
        if !caps_ok(&op) {
            return Err(Error::Envelope("group op too large"));
        }
        Ok(op)
    }
}

fn caps_ok(op: &GroupOp) -> bool {
    let sk_ok = |b: &Vec<u8>| b.len() == 32;
    match &op.op {
        None => true,
        Some(Op::Create(c)) => c.name.len() <= MAX_NAME && c.about.len() <= MAX_ABOUT && c.secret.len() == 32 && c.owner_card.len() <= MAX_CARD,
        Some(Op::SetInfo(i)) => {
            i.name.len() <= MAX_NAME
                && i.about.len() <= MAX_ABOUT
                && i.photo.as_ref().is_none_or(|p| p.blob_id.len() == 32 && p.key.len() == 32 && p.thumb.len() <= crate::envelope::MAX_THUMB)
        }
        Some(Op::Add(a)) => sk_ok(&a.sk) && a.card.len() <= MAX_CARD && a.invite_id.len() <= 16,
        Some(Op::Remove(r)) => sk_ok(&r.sk),
        Some(Op::SetRole(r)) => sk_ok(&r.sk) && r.title.chars().count() <= MAX_TITLE,
        Some(Op::Defaults(_)) => true,
        Some(Op::Restrict(r)) => sk_ok(&r.sk),
        Some(Op::SlowMode(s)) => s.seconds <= crate::feed::MAX_SLOW_S,
        Some(Op::Invite(i)) => i.invite_id.len() == 16 && i.secret_hash.len() == 32,
        Some(Op::Revoke(r)) => r.invite_id.len() == 16,
        Some(Op::Transfer(t)) => sk_ok(&t.sk),
        Some(Op::Dissolve(_)) => true,
        Some(Op::SetCard(c)) => c.card.len() <= MAX_CARD,
        Some(Op::Unban(u)) => sk_ok(&u.sk),
        Some(Op::Pin(p)) => p.target.len() == 16,
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Admin {
    pub rights: u32,
    pub title: String,
    /// Order of becoming admin (seniority): lower is older.
    pub since: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Member {
    pub card: Vec<u8>,
    /// `card` is the member's own (from `Create` or `SetCard`): its key may be used by anyone.
    pub own_card: bool,
    pub admin: Option<Admin>,
    /// Rights taken from this member until a time: (rights left, until unix ms).
    pub restricted: Option<(u32, u64)>,
    /// Order of joining.
    pub joined: u32,
    /// When: the time of the event that brought the member in (its author's clock, unix ms), so
    /// the same on every phone; what a channel's subscriber list shows as «joined».
    pub joined_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Invite {
    pub secret_hash: Vec<u8>,
    pub exp_ms: u64,
    pub max_uses: u32,
    pub uses: u32,
    pub need_approval: bool,
    pub by: UserId,
}

/// A group as its events say it is now.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupState {
    pub group_id: Vec<u8>,
    pub name: String,
    pub about: String,
    /// The photo and who set it (they have the file whole).
    pub photo: Option<(ChatPhoto, UserId)>,
    pub broadcast: bool,
    pub owner: UserId,
    pub defaults: u32,
    pub secret: Vec<u8>,
    pub dissolved: bool,
    /// In Gereh's public list, as its owner last said (every chat is, until the owner says no).
    pub listed: bool,
    pub members: BTreeMap<UserId, Member>,
    pub banned: BTreeMap<UserId, u64>,
    /// The card each banned member had when banned: the name the list of the banned shows.
    pub banned_cards: BTreeMap<UserId, Vec<u8>>,
    pub invites: BTreeMap<Vec<u8>, Invite>,
    /// Pinned messages' ids, the newest last.
    pub pinned: Vec<Vec<u8>>,
    /// Slow mode now, in seconds (q13, T73); 0 for none.
    pub slow_s: u32,
    /// When the slow mode in force was set (its event's time): posts from before it stay.
    pub slow_since_ms: u64,
    /// Events in the order applied, and whether each took effect.
    pub applied: Vec<(OpId, bool)>,
    counter: u32,
}

impl GroupState {
    pub fn is_member(&self, sk: &UserId) -> bool {
        self.members.contains_key(sk)
    }

    pub fn is_admin(&self, sk: &UserId) -> bool {
        *sk == self.owner || self.members.get(sk).is_some_and(|m| m.admin.is_some())
    }

    /// Admin rights of `sk` (all for the owner, none for a member).
    pub fn admin_rights(&self, sk: &UserId) -> u32 {
        if *sk == self.owner {
            return admin::ALL;
        }
        self.members.get(sk).and_then(|m| m.admin.as_ref()).map_or(0, |a| a.rights)
    }

    /// Member rights of `sk` at `now_ms` (all for the owner and admins).
    pub fn member_rights(&self, sk: &UserId, now_ms: u64) -> u32 {
        if self.dissolved {
            return 0;
        }
        let Some(m) = self.members.get(sk) else { return 0 };
        if self.is_admin(sk) {
            return u32::MAX;
        }
        match m.restricted {
            Some((r, until)) if now_ms < until => r & self.defaults,
            _ => self.defaults,
        }
    }

    /// May `sk` post a text message now (a channel: only the owner and admins).
    pub fn can_post(&self, sk: &UserId, now_ms: u64) -> bool {
        self.member_rights(sk, now_ms) & member::TEXT != 0
    }

    /// The slow mode that holds back a post of `sk` made at `sent_ms` (q13, T73): none in a
    /// channel, for the owner and admins, or for a post from before it was set.
    pub fn slow_for(&self, sk: &UserId, sent_ms: u64) -> u32 {
        if self.broadcast || self.is_admin(sk) || sent_ms < self.slow_since_ms {
            return 0;
        }
        self.slow_s
    }

    /// Rank for ordering concurrent events: owner, admins by seniority, members, outsiders.
    fn rank(&self, sk: &UserId) -> (u8, u32) {
        if *sk == self.owner {
            return (0, 0);
        }
        match self.members.get(sk) {
            Some(Member { admin: Some(a), .. }) => (1, a.since),
            Some(m) => (2, m.joined),
            None => (3, 0),
        }
    }

    fn next(&mut self) -> u32 {
        self.counter += 1;
        self.counter
    }

    /// Apply one event that is in order; false if it has no effect (not allowed).
    fn apply(&mut self, author: &UserId, op: &GroupOp, now_ms: u64) -> bool {
        if self.dissolved {
            return false;
        }
        let Some(body) = &op.op else { return false };
        let rights = self.admin_rights(author);
        let member_rights = self.member_rights(author, now_ms);
        let sk_of = |b: &Vec<u8>| -> Option<UserId> { b.as_slice().try_into().ok() };
        match body {
            Op::Create(_) => false, // only the genesis event creates
            Op::SetInfo(i) => {
                if rights & admin::CHANGE_INFO == 0 && member_rights & member::CHANGE_INFO == 0 {
                    return false;
                }
                self.name = i.name.clone();
                self.about = i.about.clone();
                if i.photo_set {
                    self.photo = i.photo.clone().map(|p| (p, *author));
                }
                if i.listed != 0 && *author == self.owner {
                    self.listed = i.listed == 1;
                }
                true
            }
            Op::Add(a) => {
                let Some(sk) = sk_of(&a.sk) else { return false };
                if self.members.contains_key(&sk) || self.members.len() >= MAX_MEMBERS {
                    return false;
                }
                if self.banned.get(&sk).is_some_and(|until| now_ms < *until) {
                    return false;
                }
                // by a valid invite any member adds, but an invite that needs approval only an admin
                // who may invite (SPEC 7.4)
                let by_invite = !a.invite_id.is_empty()
                    && self.invites.get(&a.invite_id).is_some_and(|i| (i.exp_ms == 0 || now_ms < i.exp_ms) && (!i.need_approval || rights & admin::INVITE != 0));
                let allowed = rights & admin::INVITE != 0 || member_rights & member::ADD_MEMBERS != 0 || (by_invite && self.is_member(author));
                if !allowed {
                    return false;
                }
                if let Some(i) = self.invites.get_mut(&a.invite_id) {
                    if i.max_uses > 0 && i.uses >= i.max_uses {
                        return false;
                    }
                    i.uses += 1;
                }
                let joined = self.next();
                self.banned.remove(&sk);
                self.banned_cards.remove(&sk);
                self.members.insert(sk, Member { card: a.card.clone(), own_card: false, admin: None, restricted: None, joined, joined_ms: op.ts_ms });
                true
            }
            Op::Remove(r) => {
                let Some(sk) = sk_of(&r.sk) else { return false };
                if !self.members.contains_key(&sk) || sk == self.owner {
                    return false;
                }
                let leaving = sk == *author;
                // kicking: a ban right, over a member; only the owner removes admins
                let kicking = rights & admin::BAN != 0 && (!self.is_admin(&sk) || *author == self.owner);
                if !(leaving && r.ban_until_ms == 0) && !kicking {
                    return false;
                }
                let gone = self.members.remove(&sk);
                if r.ban_until_ms > 0 {
                    self.banned.insert(sk, r.ban_until_ms);
                    if let Some(m) = gone {
                        self.banned_cards.insert(sk, m.card);
                    }
                }
                true
            }
            Op::SetRole(r) => {
                let Some(sk) = sk_of(&r.sk) else { return false };
                if sk == self.owner || !self.members.contains_key(&sk) {
                    return false;
                }
                let target_admin = self.is_admin(&sk);
                // the owner sets anything; an admin who may add admins promotes members, never
                // giving more than they have, and never touches other admins
                let ok = *author == self.owner || (rights & admin::ADD_ADMINS != 0 && !target_admin && r.admin && r.rights & !rights == 0);
                if !ok {
                    return false;
                }
                let since = if r.admin { self.members[&sk].admin.as_ref().map(|a| a.since).unwrap_or_else(|| self.next()) } else { 0 };
                let m = self.members.get_mut(&sk).expect("member");
                m.admin = r.admin.then(|| Admin { rights: r.rights & admin::ALL, title: r.title.clone(), since });
                true
            }
            Op::Pin(p) => {
                if rights & admin::PIN == 0 && member_rights & member::PIN == 0 {
                    return false;
                }
                self.pinned.retain(|t| *t != p.target);
                if !p.unpin {
                    self.pinned.push(p.target.clone());
                    if self.pinned.len() > MAX_PINNED {
                        self.pinned.remove(0);
                    }
                }
                true
            }
            Op::Defaults(d) => {
                if rights & admin::BAN == 0 {
                    return false;
                }
                self.defaults = d.rights;
                true
            }
            Op::SlowMode(s) => {
                // as the group's permissions: an admin who may ban (Telegram's own rule)
                if self.broadcast || rights & admin::BAN == 0 {
                    return false;
                }
                self.slow_s = s.seconds.min(crate::feed::MAX_SLOW_S);
                self.slow_since_ms = op.ts_ms;
                true
            }
            Op::Restrict(r) => {
                let Some(sk) = sk_of(&r.sk) else { return false };
                if rights & admin::BAN == 0 || !self.members.contains_key(&sk) || self.is_admin(&sk) {
                    return false;
                }
                self.members.get_mut(&sk).expect("member").restricted = (r.until_ms > now_ms).then_some((r.rights, r.until_ms));
                true
            }
            Op::Invite(i) => {
                if rights & admin::INVITE == 0 && member_rights & member::ADD_MEMBERS == 0 {
                    return false;
                }
                self.invites.insert(
                    i.invite_id.clone(),
                    Invite { secret_hash: i.secret_hash.clone(), exp_ms: i.exp_ms, max_uses: i.max_uses, uses: 0, need_approval: i.need_approval, by: *author },
                );
                true
            }
            Op::Revoke(r) => {
                let Some(inv) = self.invites.get(&r.invite_id) else { return false };
                if inv.by != *author && rights & admin::INVITE == 0 {
                    return false;
                }
                self.invites.remove(&r.invite_id);
                true
            }
            Op::Transfer(t) => {
                let Some(sk) = sk_of(&t.sk) else { return false };
                if *author != self.owner || !self.members.contains_key(&sk) {
                    return false;
                }
                let old = self.owner;
                self.owner = sk;
                if let Some(m) = self.members.get_mut(&sk) {
                    m.admin = None;
                }
                let since = self.next();
                if let Some(m) = self.members.get_mut(&old) {
                    m.admin = Some(Admin { rights: admin::ALL, title: String::new(), since });
                }
                true
            }
            Op::Dissolve(_) => {
                if *author != self.owner {
                    return false;
                }
                self.dissolved = true;
                true
            }
            Op::Unban(u) => {
                let Some(sk) = sk_of(&u.sk) else { return false };
                if rights & admin::BAN == 0 || !self.banned.contains_key(&sk) {
                    return false;
                }
                self.banned.remove(&sk);
                self.banned_cards.remove(&sk);
                true
            }
            Op::SetCard(c) => {
                let ok = crate::card::Card::from_bytes(&c.card).is_ok_and(|card| card.sk == *author && card.loc().is_some());
                let Some(m) = self.members.get_mut(author).filter(|_| ok) else { return false };
                m.card = c.card.clone();
                m.own_card = true;
                true
            }
        }
    }
}

/// All the events of one group this phone holds.
#[derive(Default, Clone)]
pub struct GroupLog {
    ops: HashMap<OpId, (SignedOp, GroupOp)>,
}

impl GroupLog {
    pub fn new() -> GroupLog {
        GroupLog::default()
    }

    /// Keep an event if it is signed by its author and belongs to this log's group. Returns
    /// whether it was new.
    pub fn insert(&mut self, signed: SignedOp) -> Result<bool> {
        let id = signed.id();
        if self.ops.contains_key(&id) {
            return Ok(false);
        }
        let op = signed.open()?;
        if let Some((_, any)) = self.ops.values().next() {
            if any.group_id != op.group_id {
                return Err(Error::Envelope("group op of another group"));
            }
        }
        self.ops.insert(id, (signed, op));
        Ok(true)
    }

    pub fn len(&self) -> usize {
        self.ops.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ops.is_empty()
    }

    pub fn ids(&self) -> BTreeSet<OpId> {
        self.ops.keys().copied().collect()
    }

    pub fn get(&self, id: &OpId) -> Option<&SignedOp> {
        self.ops.get(id).map(|(s, _)| s)
    }

    /// Events no other event follows: the parents of the next event made here.
    pub fn heads(&self) -> Vec<OpId> {
        let mut referenced: BTreeSet<OpId> = BTreeSet::new();
        for (_, op) in self.ops.values() {
            for p in &op.parents {
                if let Ok(p) = OpId::try_from(p.as_slice()) {
                    referenced.insert(p);
                }
            }
        }
        let mut heads: Vec<OpId> = self.ops.keys().filter(|id| !referenced.contains(*id)).copied().collect();
        heads.sort();
        heads.truncate(MAX_PARENTS);
        heads
    }

    /// Every event here that `id` follows, directly or through others (not `id` itself).
    pub fn ancestors(&self, id: &OpId) -> BTreeSet<OpId> {
        let mut seen = BTreeSet::new();
        let mut todo = vec![*id];
        while let Some(x) = todo.pop() {
            let Some((_, op)) = self.ops.get(&x) else { continue };
            for p in &op.parents {
                if let Ok(p) = OpId::try_from(p.as_slice()) {
                    if seen.insert(p) {
                        todo.push(p);
                    }
                }
            }
        }
        seen
    }

    /// An event here, opened.
    pub fn op(&self, id: &OpId) -> Option<&GroupOp> {
        self.ops.get(id).map(|(_, op)| op)
    }

    /// The author of an event here.
    pub fn author(&self, id: &OpId) -> Option<UserId> {
        self.ops.get(id).and_then(|(_, op)| op.author_sk.as_slice().try_into().ok())
    }

    /// Members who surely took event `id` from its author (SPEC 7.3, T53). The author sent it to
    /// every member whose own card it held: the group's creator and the author of every `SetCard`
    /// the event follows. Those take the author's frame once they know the author's own card, so
    /// when the event follows that card (or the author is the creator) they all count. Before
    /// that (the author's first card itself) only whoever added the author surely knows them, so
    /// only they count, if their own card is among the above. The same on every phone that holds
    /// all of the event's ancestors.
    pub fn reached(&self, id: &OpId) -> BTreeSet<UserId> {
        let Some(author) = self.author(id) else { return BTreeSet::new() };
        let mut known = BTreeSet::new();
        let mut adders = BTreeSet::new();
        for (_, op) in self.ancestors(id).iter().filter_map(|a| self.ops.get(a)) {
            let Ok(by) = UserId::try_from(op.author_sk.as_slice()) else { continue };
            match &op.op {
                Some(Op::SetCard(_)) => {
                    known.insert(by);
                }
                Some(Op::Create(_)) if op.parents.is_empty() => {
                    known.insert(by);
                }
                Some(Op::Add(a)) if a.sk == author => {
                    adders.insert(by);
                }
                _ => {}
            }
        }
        if known.contains(&author) {
            known
        } else {
            known.intersection(&adders).copied().collect()
        }
    }

    /// The state the events build, the same on every phone; `None` before the group's creation
    /// event is here. Events whose parents are not all here yet wait.
    pub fn state(&self, now_ms: u64) -> Option<GroupState> {
        // the genesis: a Create with no parents, signed by the owner; the smallest id if several
        let (gid, genesis) = self
            .ops
            .iter()
            .filter(|(_, (_, op))| op.parents.is_empty() && matches!(op.op, Some(Op::Create(_))))
            .min_by_key(|(id, _)| **id)?;
        let Some(Op::Create(c)) = &genesis.1.op else { return None };
        let owner: UserId = genesis.1.author_sk.as_slice().try_into().ok()?;
        let mut st = GroupState {
            group_id: genesis.1.group_id.clone(),
            name: c.name.clone(),
            about: c.about.clone(),
            photo: None,
            broadcast: c.broadcast,
            owner,
            defaults: if c.broadcast { 0 } else { c.defaults },
            secret: c.secret.clone(),
            dissolved: false,
            listed: true,
            members: BTreeMap::new(),
            banned: BTreeMap::new(),
            banned_cards: BTreeMap::new(),
            invites: BTreeMap::new(),
            pinned: Vec::new(),
            slow_s: 0,
            slow_since_ms: 0,
            applied: vec![(*gid, true)],
            counter: 0,
        };
        let joined = st.next();
        st.members.insert(owner, Member { card: c.owner_card.clone(), own_card: true, admin: None, restricted: None, joined, joined_ms: genesis.1.ts_ms });

        // Kahn's order: an event is ready when all its parents were placed
        let mut placed: BTreeSet<OpId> = BTreeSet::new();
        placed.insert(*gid);
        let mut waiting: Vec<OpId> = self.ops.keys().filter(|id| *id != gid).copied().collect();
        loop {
            let mut ready: Vec<(OpId, UserId)> = waiting
                .iter()
                .filter_map(|id| {
                    let (_, op) = &self.ops[id];
                    let parents_ok = !op.parents.is_empty() && op.parents.iter().all(|p| OpId::try_from(p.as_slice()).is_ok_and(|p| placed.contains(&p)));
                    let author: UserId = op.author_sk.as_slice().try_into().ok()?;
                    parents_ok.then_some((*id, author))
                })
                .collect();
            if ready.is_empty() {
                break;
            }
            ready.sort_by_key(|(id, author)| (st.rank(author), *id));
            let (id, author) = ready[0];
            let ok = st.apply(&author, &self.ops[&id].1, now_ms);
            st.applied.push((id, ok));
            placed.insert(id);
            waiting.retain(|w| *w != id);
        }
        Some(st)
    }
}

/// A new event of `author` for a group, following everything this phone holds.
pub fn new_op(log: &GroupLog, group_id: &[u8], author: &UserId, op: Op, now_ms: u64) -> GroupOp {
    GroupOp {
        group_id: group_id.to_vec(),
        parents: log.heads().iter().map(|h| h.to_vec()).collect(),
        author_sk: author.to_vec(),
        ts_ms: now_ms,
        op: Some(op),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::Identity;

    struct P {
        id: Identity,
    }

    impl P {
        fn new(name: &str) -> P {
            P { id: Identity::new(name).unwrap() }
        }
        fn sk(&self) -> UserId {
            self.id.sk()
        }
        fn op(&self, log: &GroupLog, gid: &[u8], op: Op) -> SignedOp {
            let o = new_op(log, gid, &self.sk(), op, 1000);
            SignedOp::sign(&o, |m| self.id.sign(m))
        }
    }

    fn create(owner: &P, broadcast: bool) -> (GroupLog, Vec<u8>) {
        let gid = vec![7u8; GROUP_ID_LEN];
        let mut log = GroupLog::new();
        let c = Create { name: "doostan".into(), about: String::new(), defaults: member::DEFAULT, broadcast, secret: vec![1; 32], owner_card: vec![9; 10] };
        let s = owner.op(&log, &gid, Op::Create(c));
        assert!(log.insert(s).unwrap());
        (log, gid)
    }

    fn add(by: &P, log: &mut GroupLog, gid: &[u8], who: &P) -> SignedOp {
        let s = by.op(log, gid, Op::Add(AddMember { sk: who.sk().to_vec(), card: vec![3; 10], invite_id: Vec::new() }));
        log.insert(s.clone()).unwrap();
        s
    }

    #[test]
    fn reached_is_the_creator_and_the_cards_an_event_follows() {
        let (o, a, b, c) = (P::new("owner"), P::new("ali"), P::new("sara"), P::new("reza"));
        let (mut log, gid) = create(&o, false);
        add(&o, &mut log, &gid, &a);
        add(&o, &mut log, &gid, &b);
        // a and b publish their cards at the same time: neither follows the other
        let ca = a.op(&log, &gid, Op::SetCard(SetCard { card: vec![5; 10] }));
        let cb = b.op(&log, &gid, Op::SetCard(SetCard { card: vec![6; 10] }));
        log.insert(ca.clone()).unwrap();
        log.insert(cb.clone()).unwrap();
        assert_eq!(log.reached(&ca.id()), BTreeSet::from([o.sk()]));
        assert_eq!(log.reached(&cb.id()), BTreeSet::from([o.sk()]));
        let x = add(&o, &mut log, &gid, &c);
        assert!(log.ancestors(&x.id()).is_superset(&BTreeSet::from([ca.id(), cb.id()])));
        assert_eq!(log.reached(&x.id()), BTreeSet::from([o.sk(), a.sk(), b.sk()]));
        assert_eq!(log.author(&x.id()), Some(o.sk()));
        // a member added by ali: their first card is surely taken only by ali, who knows them;
        // after it, by everyone whose card it follows
        let d = P::new("dara");
        add(&a, &mut log, &gid, &d);
        let cd = d.op(&log, &gid, Op::SetCard(SetCard { card: vec![7; 10] }));
        log.insert(cd.clone()).unwrap();
        assert_eq!(log.reached(&cd.id()), BTreeSet::from([a.sk()]));
        let y = d.op(&log, &gid, Op::Remove(RemoveMember { sk: d.sk().to_vec(), ban_until_ms: 0 }));
        log.insert(y.clone()).unwrap();
        assert_eq!(log.reached(&y.id()), BTreeSet::from([o.sk(), a.sk(), b.sk(), d.sk()]));
    }

    #[test]
    fn create_add_and_rights() {
        let (o, a, b) = (P::new("owner"), P::new("ali"), P::new("sara"));
        let (mut log, gid) = create(&o, false);
        add(&o, &mut log, &gid, &a);
        // members may add members by default
        add(&a, &mut log, &gid, &b);
        let st = log.state(2000).unwrap();
        assert_eq!(st.members.len(), 3);
        assert_eq!(st.owner, o.sk());
        assert!(st.can_post(&b.sk(), 2000));
        assert!(st.applied.iter().all(|(_, ok)| *ok));
        // a member cannot kick
        let s = a.op(&log, &gid, Op::Remove(RemoveMember { sk: b.sk().to_vec(), ban_until_ms: 0 }));
        log.insert(s).unwrap();
        let st = log.state(2000).unwrap();
        assert!(st.is_member(&b.sk()));
        assert!(!st.applied.last().unwrap().1);
        // leaving is always allowed
        let s = b.op(&log, &gid, Op::Remove(RemoveMember { sk: b.sk().to_vec(), ban_until_ms: 0 }));
        log.insert(s).unwrap();
        assert!(!log.state(2000).unwrap().is_member(&b.sk()));
    }

    #[test]
    fn a_channel_lets_only_admins_post() {
        let (o, a, b) = (P::new("owner"), P::new("ali"), P::new("sara"));
        let (mut log, gid) = create(&o, true);
        add(&o, &mut log, &gid, &a);
        add(&o, &mut log, &gid, &b);
        let s = o.op(&log, &gid, Op::SetRole(SetRole { sk: a.sk().to_vec(), admin: true, rights: admin::CHANGE_INFO | admin::DELETE, title: "dabir".into() }));
        log.insert(s).unwrap();
        let st = log.state(2000).unwrap();
        assert!(st.broadcast);
        assert!(st.can_post(&o.sk(), 2000));
        assert!(st.can_post(&a.sk(), 2000), "an admin posts");
        assert!(!st.can_post(&b.sk(), 2000), "a reader does not");
        // a reader cannot add people to a channel
        let c = P::new("x");
        add(&b, &mut log, &gid, &c);
        assert!(!log.state(2000).unwrap().is_member(&c.sk()));
    }

    #[test]
    fn the_owner_wins_over_a_concurrent_admin() {
        let (o, a, b) = (P::new("owner"), P::new("ali"), P::new("sara"));
        let (mut log, gid) = create(&o, false);
        add(&o, &mut log, &gid, &a);
        add(&o, &mut log, &gid, &b);
        let s = o.op(&log, &gid, Op::SetRole(SetRole { sk: a.sk().to_vec(), admin: true, rights: admin::BAN, title: String::new() }));
        log.insert(s).unwrap();
        // at the same time (same parents): the owner demotes Ali, and Ali kicks Sara
        let mut fork = log.clone();
        let demote = o.op(&log, &gid, Op::SetRole(SetRole { sk: a.sk().to_vec(), admin: false, rights: 0, title: String::new() }));
        let kick = a.op(&fork, &gid, Op::Remove(RemoveMember { sk: b.sk().to_vec(), ban_until_ms: u64::MAX }));
        fork.insert(kick.clone()).unwrap();
        log.insert(demote.clone()).unwrap();
        log.insert(kick).unwrap();
        fork.insert(demote).unwrap();
        let (s1, s2) = (log.state(2000).unwrap(), fork.state(2000).unwrap());
        assert_eq!(s1, s2, "the same state whatever order the events arrived in");
        assert!(s1.is_member(&b.sk()), "Ali was no longer an admin when his kick came");
        assert!(!s1.is_admin(&a.sk()));
    }

    #[test]
    fn forged_and_foreign_events_are_refused_and_orphans_wait() {
        let (o, a) = (P::new("owner"), P::new("ali"));
        let (mut log, gid) = create(&o, false);
        let mut s = o.op(&log, &gid, Op::SetInfo(SetInfo { name: "x".into(), ..Default::default() }));
        s.sig[0] ^= 1;
        assert!(log.insert(s).is_err());
        // an event whose parent is not here yet waits, then counts once it comes
        let mut ahead = log.clone();
        let first = add(&o, &mut ahead, &gid, &a);
        let second = a.op(&ahead, &gid, Op::SetInfo(SetInfo { name: "ali changed it".into(), ..Default::default() }));
        log.insert(second).unwrap();
        let st = log.state(2000).unwrap();
        assert_eq!(st.name, "doostan");
        assert_eq!(st.applied.len(), 1);
        log.insert(first).unwrap();
        let st = log.state(2000).unwrap();
        // members cannot change the info by default
        assert_eq!(st.name, "doostan");
        assert_eq!(st.applied.len(), 3);
        // another group's event does not mix in
        let other = o.op(&GroupLog::new(), &[8u8; GROUP_ID_LEN], Op::Dissolve(Dissolve {}));
        assert!(log.insert(other).is_err());
    }

    #[test]
    fn transfer_and_dissolve() {
        let (o, a) = (P::new("owner"), P::new("ali"));
        let (mut log, gid) = create(&o, false);
        add(&o, &mut log, &gid, &a);
        let s = o.op(&log, &gid, Op::Transfer(TransferOwner { sk: a.sk().to_vec() }));
        log.insert(s).unwrap();
        let st = log.state(2000).unwrap();
        assert_eq!(st.owner, a.sk());
        assert_eq!(st.admin_rights(&o.sk()), admin::ALL, "the old owner stays an admin");
        let s = o.op(&log, &gid, Op::Dissolve(Dissolve {}));
        log.insert(s).unwrap();
        assert!(!log.state(2000).unwrap().dissolved, "only the owner dissolves");
        let s = a.op(&log, &gid, Op::Dissolve(Dissolve {}));
        log.insert(s).unwrap();
        let st = log.state(2000).unwrap();
        assert!(st.dissolved);
        assert!(!st.can_post(&a.sk(), 2000));
    }

    #[test]
    fn unban_needs_the_right_and_a_ban() {
        let (o, a, b) = (P::new("owner"), P::new("ali"), P::new("sara"));
        let (mut log, gid) = create(&o, false);
        add(&o, &mut log, &gid, &a);
        add(&o, &mut log, &gid, &b);
        let s = o.op(&log, &gid, Op::Remove(RemoveMember { sk: a.sk().to_vec(), ban_until_ms: u64::MAX }));
        log.insert(s).unwrap();
        assert!(log.state(2000).unwrap().banned.contains_key(&a.sk()));
        // a member may not lift it
        let s = b.op(&log, &gid, Op::Unban(Unban { sk: a.sk().to_vec() }));
        log.insert(s.clone()).unwrap();
        let st = log.state(2000).unwrap();
        assert!(st.applied.contains(&(s.id(), false)) && st.banned.contains_key(&a.sk()));
        // the owner may; the member stays out
        let s = o.op(&log, &gid, Op::Unban(Unban { sk: a.sk().to_vec() }));
        log.insert(s.clone()).unwrap();
        let st = log.state(2000).unwrap();
        assert!(st.applied.contains(&(s.id(), true)));
        assert!(!st.banned.contains_key(&a.sk()) && !st.banned_cards.contains_key(&a.sk()) && !st.is_member(&a.sk()));
        // nothing to lift: no effect
        let s = o.op(&log, &gid, Op::Unban(Unban { sk: b.sk().to_vec() }));
        log.insert(s.clone()).unwrap();
        assert!(log.state(2000).unwrap().applied.contains(&(s.id(), false)));
    }

    #[test]
    fn an_event_this_phone_cannot_read_changes_nothing_and_blocks_nothing() {
        // an event of a newer phone (field 30, unknown to every phone): kept with no effect, and the
        // events after it apply as before. A phone from before T59 sees an `Unban` like this.
        let (o, a) = (P::new("owner"), P::new("ali"));
        let (mut log, gid) = create(&o, false);
        add(&o, &mut log, &gid, &a);
        let mut unknown = new_op(&log, &gid, &o.sk(), Op::Dissolve(Dissolve {}), 1000);
        unknown.op = None;
        let mut body = unknown.encode_to_vec();
        body.extend_from_slice(&[0xF2, 0x01, 0x02, 0x0A, 0x00]); // field 30: a message holding one empty field
        let sig = o.id.sign(&body);
        let s = SignedOp { body, sig: sig.to_vec() };
        assert!(log.insert(s.clone()).unwrap(), "kept");
        let after = o.op(&log, &gid, Op::SetInfo(SetInfo { name: "jadid".into(), ..Default::default() }));
        log.insert(after.clone()).unwrap();
        let st = log.state(2000).unwrap();
        assert!(st.applied.contains(&(s.id(), false)), "no effect");
        assert!(st.applied.contains(&(after.id(), true)), "the event after it applies");
        assert_eq!(st.name, "jadid");
        assert!(!st.dissolved);
    }

    #[test]
    fn a_member_keeps_the_time_of_joining() {
        let (o, a) = (P::new("owner"), P::new("ali"));
        let (mut log, gid) = create(&o, true);
        let add = new_op(&log, &gid, &o.sk(), Op::Add(AddMember { sk: a.sk().to_vec(), card: vec![3; 10], invite_id: Vec::new() }), 1_761_600_000_000);
        log.insert(SignedOp::sign(&add, |m| o.id.sign(m))).unwrap();
        // the joining event's own time, whenever (and on whichever phone) the state is built
        for now in [2_000, 1_900_000_000_000] {
            let st = log.state(now).unwrap();
            assert_eq!(st.members[&o.sk()].joined_ms, 1000, "the owner: the creation's time");
            assert_eq!(st.members[&a.sk()].joined_ms, 1_761_600_000_000);
        }
    }

    #[test]
    fn slow_mode_is_an_admin_with_the_ban_right_in_a_group() {
        let (o, a, b) = (P::new("owner"), P::new("ali"), P::new("sara"));
        let (mut log, gid) = create(&o, false);
        add(&o, &mut log, &gid, &a);
        add(&o, &mut log, &gid, &b);
        // a member may not set it
        let s = a.op(&log, &gid, Op::SlowMode(SetSlowMode { seconds: 30 }));
        log.insert(s.clone()).unwrap();
        let st = log.state(2000).unwrap();
        assert!(st.applied.contains(&(s.id(), false)) && st.slow_s == 0);
        // nor an admin without the right to ban
        let s = o.op(&log, &gid, Op::SetRole(SetRole { sk: a.sk().to_vec(), admin: true, rights: admin::PIN, title: String::new() }));
        log.insert(s).unwrap();
        let s = a.op(&log, &gid, Op::SlowMode(SetSlowMode { seconds: 30 }));
        log.insert(s.clone()).unwrap();
        assert!(log.state(2000).unwrap().applied.contains(&(s.id(), false)));
        // the owner may; it holds a member back, never an admin, and not a post from before it
        let s = o.op(&log, &gid, Op::SlowMode(SetSlowMode { seconds: 30 }));
        log.insert(s.clone()).unwrap();
        let st = log.state(2000).unwrap();
        assert!(st.applied.contains(&(s.id(), true)));
        assert_eq!((st.slow_s, st.slow_since_ms), (30, 1000));
        assert_eq!(st.slow_for(&b.sk(), 1500), 30);
        assert_eq!(st.slow_for(&b.sk(), 999), 0, "a post from before it stays");
        assert_eq!(st.slow_for(&a.sk(), 1500), 0, "an admin");
        assert_eq!(st.slow_for(&o.sk(), 1500), 0, "the owner");
        // longer than an hour does not open
        let long = new_op(&log, &gid, &o.sk(), Op::SlowMode(SetSlowMode { seconds: crate::feed::MAX_SLOW_S + 1 }), 1000);
        assert!(SignedOp::sign(&long, |m| o.id.sign(m)).open().is_err());
        // 0 ends it
        let s = o.op(&log, &gid, Op::SlowMode(SetSlowMode { seconds: 0 }));
        log.insert(s).unwrap();
        assert_eq!(log.state(2000).unwrap().slow_for(&b.sk(), 1500), 0);

        // a channel has none: its members do not post
        let (mut log, gid) = create(&o, true);
        let s = o.op(&log, &gid, Op::SlowMode(SetSlowMode { seconds: 30 }));
        log.insert(s.clone()).unwrap();
        let st = log.state(2000).unwrap();
        assert!(st.applied.contains(&(s.id(), false)) && st.slow_s == 0);
    }

    #[test]
    fn slow_clash_judges_both_ways_and_an_album_is_one_post() {
        let album = vec![5u8; 16];
        let near = vec![(100_000, Vec::new()), (200_000, album.clone())];
        assert!(!slow_clash(0, 100_500, &[], &near), "no slow mode");
        assert!(slow_clash(30, 120_000, &[], &near), "20 s after a post");
        assert!(slow_clash(30, 85_000, &[], &near), "a late copy of a post from 15 s before it");
        assert!(!slow_clash(30, 130_000, &[], &near), "30 s on is in time");
        assert!(!slow_clash(30, 201_000, &album, &near), "the next file of the same album");
        assert!(slow_clash(30, 201_000, &[9u8; 16], &near), "another album");
        assert!(slow_clash(30, 201_000, &[], &near), "a text beside an album");
    }
}
