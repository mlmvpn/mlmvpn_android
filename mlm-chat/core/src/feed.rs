//! Official feeds (T38; step 6.4 of the work queue): an official channel and an official group
//! that everyone who installs the app is in, with no member list and no server.
//!
//! - A feed is named by an id and an **owner key** that ship with the app (`FeedDescriptor`).
//!   The owner key is not anyone's Gereh identity: its secret half stays offline with the owner,
//!   and it only signs the list of admins (`FeedAdmins`, newest version wins).
//! - Everything in a feed is a `FeedItem` signed by its author's identity key: a post (an
//!   `Envelope` with text or media), a delete, a ban, the chat's photo or its name and description
//!   (admins), a reaction, or an edit of one's own post. A channel takes posts from admins only;
//!   the group from anyone not banned, at most `GROUP_RATE` an hour each. Anyone not banned
//!   reacts, in both.
//! - Items pass from phone to phone inside the pairwise Olm sessions (`node/feeds.rs`), so on the
//!   wire they are as encrypted as any message. They are public to every Gereh user, though:
//!   anyone can be in the feed, so it is no place for private talk (T38).
//! - Every phone keeps every item whose signature checks, and decides what counts when reading:
//!   an item that does not count yet (its author's admin record has not arrived) may count later.

use crate::envelope::Envelope;
use crate::transport::session::verify;
use crate::{Error, Result, UserId};
use prost::Message;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

pub const FEED_ID_LEN: usize = 16;
pub const ITEM_ID_LEN: usize = 16;
/// Items kept per feed on each phone (the newest).
pub const MAX_ITEMS: usize = 500;
/// Posts per author an hour in a group feed.
pub const GROUP_RATE: usize = 10;
/// An item stamped further ahead than this is not taken.
pub const MAX_SKEW_MS: u64 = 5 * 60 * 1000;
pub const MAX_NAME: usize = 64;
pub const MAX_ADMINS: usize = 64;
/// A post's envelope is at most this big (a message, not a file: files go as blobs).
pub const MAX_POST: usize = 16 * 1024;
/// Reactions kept per feed on each phone (the newest), apart from the posts.
pub const MAX_REACTS: usize = 4000;
/// Reactions per person an hour in a feed (changing one counts too).
pub const REACT_RATE: usize = 60;
/// Edits per person an hour in a feed.
pub const EDIT_RATE: usize = 30;
/// A feed's name and description (bytes), as Telegram allows them.
pub const MAX_TITLE: usize = 128;
pub const MAX_ABOUT: usize = 1024;
pub const MAX_EMOJI: usize = 32;

/// A feed as the app ships it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeedDescriptor {
    pub feed_id: Vec<u8>,
    /// A channel (only admins post) or a group (everyone posts).
    pub broadcast: bool,
    pub name: String,
    /// The owner key: it signs the admin list, nothing else.
    pub owner: UserId,
}

#[derive(Clone, PartialEq, Message)]
pub struct FeedItem {
    #[prost(bytes = "vec", tag = "1")]
    pub feed_id: Vec<u8>,
    /// Random, 16 bytes: the item's id everywhere.
    #[prost(bytes = "vec", tag = "2")]
    pub id: Vec<u8>,
    /// The signer: an identity key, or the owner key for `Admins`.
    #[prost(bytes = "vec", tag = "3")]
    pub author: Vec<u8>,
    #[prost(uint64, tag = "4")]
    pub ts_ms: u64,
    /// The author's display name (`name·1234`) as they give it.
    #[prost(string, tag = "5")]
    pub name: String,
    /// The author's own contact card (SPEC 3, with their fallback key), now and then in a post, so
    /// any member can start a one-to-one chat with them as in a Telegram group (SPEC 18.4, T64;
    /// Ehsan, 29 Oct 2026). Empty in most items; an older phone skips it.
    #[prost(bytes = "vec", tag = "6")]
    pub card: Vec<u8>,
    /// The posting phone's tag in this feed ([`device_tag`], T74): the same for every account made
    /// on that phone, so an admin's ban holds the phone, not only the account (Ehsan, 31 Oct 2026:
    /// a banned advertiser cleared the app's data and came back under a new name). Empty from a
    /// phone before it.
    #[prost(bytes = "vec", tag = "7")]
    pub device: Vec<u8>,
    /// The app and version the item was posted from (SPEC 5 `Client`, T75), as on an envelope;
    /// none from a phone before it.
    #[prost(message, optional, tag = "8")]
    pub client: Option<crate::envelope::Client>,
    #[prost(oneof = "Item", tags = "10, 11, 12, 13, 14, 15, 16, 17, 18, 19")]
    pub item: Option<Item>,
}

/// A card in an item is kept only up to this size.
pub const MAX_ITEM_CARD: usize = 1024;

/// A phone's tag in a feed, in bytes (T74).
pub const DEVICE_LEN: usize = 16;

/// The most phone tags one ban names (the accounts one person posted from).
pub const MAX_BAN_DEVICES: usize = 16;

/// From this time on (2026-11-01 00:00 UTC) a member's post in a group counts only with its
/// phone's tag (T74; Ehsan: a phone from before it, 1.2.44 and older, posts no more): else a banned
/// phone would come back on an old version. Posts before it stay as they are.
pub const DEVICE_FROM_MS: u64 = 1_793_491_200_000;

/// A phone's tag in feed `feed_id` (T74): from what the app reads of the phone itself, the same
/// after the app's data is cleared or the app put on again (Android's `ANDROID_ID`, new only after
/// a factory reset). Hashed with the feed, so it says nothing outside the feed and nothing of the
/// phone itself; the app's own value never leaves the phone.
pub fn device_tag(feed_id: &[u8], device: &[u8]) -> Vec<u8> {
    let mut h = Sha256::new();
    h.update(b"mlmchat/device/1");
    h.update((feed_id.len() as u32).to_be_bytes());
    h.update(feed_id);
    h.update(device);
    h.finalize()[..DEVICE_LEN].to_vec()
}

#[derive(Clone, PartialEq, prost::Oneof)]
pub enum Item {
    /// An `Envelope` (text or media) as bytes.
    #[prost(bytes, tag = "10")]
    Post(Vec<u8>),
    #[prost(message, tag = "11")]
    Delete(FeedDelete),
    #[prost(message, tag = "12")]
    Ban(FeedBan),
    #[prost(message, tag = "13")]
    Admins(FeedAdmins),
    /// The chat's photo, from an admin; the newest stands (Ehsan, 25 Oct 2026: the official
    /// channel's and group's photo would not stay).
    #[prost(message, tag = "14")]
    Info(FeedInfoItem),
    /// The chat's name and description, from an admin; the newest stands (Ehsan, 25 Oct 2026:
    /// renaming the official channel and group did nothing).
    #[prost(message, tag = "15")]
    Meta(FeedMeta),
    /// Someone's reaction to a post; their newest on that post stands, an empty emoji takes it
    /// back (Ehsan, 25 Oct 2026: no reactions anywhere).
    #[prost(message, tag = "16")]
    React(FeedReact),
    /// New text for a post (a text post's text, a photo's or file's caption) from the post's own
    /// author, as SPEC 5 `Edit` in a chat; their newest stands (T60; Ehsan, 27 Oct 2026). A phone
    /// that does not know this kind refuses its shape and keeps showing the post as it was.
    #[prost(message, tag = "17")]
    Edit(crate::envelope::Edit),
    /// An admin pins a post or takes the pin off (T65); a phone that does not know this kind
    /// refuses its shape and shows no pin.
    #[prost(message, tag = "18")]
    Pin(FeedPin),
    /// An admin's slow mode in the group (T67): a member posts again only this many seconds after
    /// their last post that counts; 0 ends it. A phone that does not know this kind refuses its
    /// shape and keeps no slow mode.
    #[prost(message, tag = "19")]
    Slow(FeedSlow),
}

/// A post pinned, or its pin taken off.
#[derive(Clone, PartialEq, Message)]
pub struct FeedPin {
    #[prost(bytes = "vec", tag = "1")]
    pub target: Vec<u8>,
    #[prost(bool, tag = "2")]
    pub unpin: bool,
}

/// A group's slow mode (T67), in seconds; 0 ends it.
#[derive(Clone, PartialEq, Message)]
pub struct FeedSlow {
    #[prost(uint32, tag = "1")]
    pub seconds: u32,
}

/// The longest slow mode: an hour, as in Telegram.
pub const MAX_SLOW_S: u32 = 3600;

/// An admin's name and description for the feed; empty ones leave the app's own.
#[derive(Clone, PartialEq, Message)]
pub struct FeedMeta {
    #[prost(string, tag = "1")]
    pub title: String,
    #[prost(string, tag = "2")]
    pub about: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct FeedReact {
    #[prost(bytes = "vec", tag = "1")]
    pub target: Vec<u8>,
    /// One emoji; empty: no reaction any more.
    #[prost(string, tag = "2")]
    pub emoji: String,
}

/// What an admin sets about the feed: its photo (unset: no photo), a sealed file like a group's
/// (`group::ChatPhoto`), fetched from the phone that passed the item on.
#[derive(Clone, PartialEq, Message)]
pub struct FeedInfoItem {
    #[prost(message, optional, tag = "1")]
    pub photo: Option<crate::group::ChatPhoto>,
}

#[derive(Clone, PartialEq, Message)]
pub struct FeedDelete {
    #[prost(bytes = "vec", tag = "1")]
    pub target: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct FeedBan {
    #[prost(bytes = "vec", tag = "1")]
    pub sk: Vec<u8>,
    /// Banned until (unix ms); 0 lifts a ban.
    #[prost(uint64, tag = "2")]
    pub until_ms: u64,
    /// The tags of the phones `sk` posted from (T74), banned (or let go) with it: a new account on
    /// one of them posts no more either. The admin's phone fills them from the posts it holds.
    #[prost(bytes = "vec", repeated, tag = "3")]
    pub devices: Vec<Vec<u8>>,
}

#[derive(Clone, PartialEq, Message)]
pub struct FeedAdmins {
    #[prost(uint64, tag = "1")]
    pub version: u64,
    #[prost(bytes = "vec", repeated, tag = "2")]
    pub admins: Vec<Vec<u8>>,
}

/// On the wire and on disk: the exact bytes signed, and the signature.
#[derive(Clone, PartialEq, Message)]
pub struct SignedFeedItem {
    #[prost(bytes = "vec", tag = "1")]
    pub body: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub sig: Vec<u8>,
}

impl SignedFeedItem {
    pub fn sign(item: &FeedItem, sign: impl FnOnce(&[u8]) -> [u8; 64]) -> SignedFeedItem {
        let body = item.encode_to_vec();
        let sig = sign(&body).to_vec();
        SignedFeedItem { body, sig }
    }

    /// Decode, check the shape and the signature. Not whether it counts (`FeedState`).
    pub fn open(&self) -> Result<FeedItem> {
        let item = FeedItem::decode(self.body.as_slice()).map_err(|_| Error::Envelope("feed item"))?;
        let author: UserId = item.author.as_slice().try_into().map_err(|_| Error::Envelope("feed author"))?;
        let sig: [u8; 64] = self.sig.as_slice().try_into().map_err(|_| Error::Envelope("feed signature"))?;
        let shape = item.feed_id.len() == FEED_ID_LEN
            && item.id.len() == ITEM_ID_LEN
            && item.name.len() <= MAX_NAME
            && (item.device.is_empty() || item.device.len() == DEVICE_LEN)
            && item.client.as_ref().is_none_or(|c| c.app.len() <= crate::envelope::MAX_CLIENT_APP && c.name.len() <= crate::envelope::MAX_CLIENT_NAME)
            && match &item.item {
                Some(Item::Post(p)) => !p.is_empty() && p.len() <= MAX_POST && Envelope::from_bytes(p).is_ok(),
                Some(Item::Delete(d)) => d.target.len() == ITEM_ID_LEN,
                Some(Item::Ban(b)) => b.sk.len() == 32 && b.devices.len() <= MAX_BAN_DEVICES && b.devices.iter().all(|t| t.len() == DEVICE_LEN),
                Some(Item::Admins(a)) => a.admins.len() <= MAX_ADMINS && a.admins.iter().all(|s| s.len() == 32),
                Some(Item::Info(f)) => f.photo.as_ref().is_none_or(|p| {
                    p.blob_id.len() == 32 && p.key.len() == 32 && p.thumb.len() <= crate::envelope::MAX_THUMB && p.size > 0 && p.size <= crate::node::MAX_PHOTO + 65536
                }),
                Some(Item::Meta(m)) => m.title.len() <= MAX_TITLE && m.about.len() <= MAX_ABOUT,
                Some(Item::React(r)) => r.target.len() == ITEM_ID_LEN && r.emoji.len() <= MAX_EMOJI,
                Some(Item::Edit(e)) => e.target.len() == ITEM_ID_LEN && e.text.as_ref().map_or(0, |t| t.body.len()) + e.caption.len() <= MAX_POST,
                Some(Item::Pin(p)) => p.target.len() == ITEM_ID_LEN,
                Some(Item::Slow(s)) => s.seconds <= MAX_SLOW_S,
                None => false,
            };
        if !shape {
            return Err(Error::Envelope("feed item shape"));
        }
        if !verify(&author, &self.body, &sig) {
            return Err(Error::Envelope("feed signature"));
        }
        Ok(item)
    }

    /// An item read back from our own store. `keep` (node/feeds.rs), the only writer, saves an item
    /// only after `open` checked its shape and signature, so here it is only decoded. Checking
    /// every signature again on each load (500 posts and up to 4000 reactions) held the node's
    /// lock about 0.6 s a load on a Galaxy A50 and 1.2 s in its 32-bit mode (30 Oct 2026), and
    /// the official chats load often: the screens froze waiting for it.
    pub fn open_kept(&self) -> Result<FeedItem> {
        FeedItem::decode(self.body.as_slice()).map_err(|_| Error::Envelope("feed item"))
    }

    /// As kept or shipped: the protobuf bytes of this message.
    pub fn from_bytes(bytes: &[u8]) -> Result<SignedFeedItem> {
        SignedFeedItem::decode(bytes).map_err(|_| Error::Envelope("signed feed item"))
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        self.encode_to_vec()
    }

    pub fn id(&self) -> Option<Vec<u8>> {
        FeedItem::decode(self.body.as_slice()).ok().map(|i| i.id)
    }
}

/// The owner's tool (run offline, e.g. `peer-cli`): a signed admin list for a feed.
pub fn sign_admins(owner_secret: &[u8; 32], feed_id: &[u8], version: u64, admins: &[UserId], ts_ms: u64) -> Result<SignedFeedItem> {
    let key = vodozemac::Ed25519SecretKey::from_slice(owner_secret);
    let item = FeedItem {
        feed_id: feed_id.to_vec(),
        id: crate::util::random::<ITEM_ID_LEN>().to_vec(),
        author: key.public_key().as_bytes().to_vec(),
        ts_ms,
        name: String::new(),
        card: Vec::new(),
        device: Vec::new(),
        client: None,
        item: Some(Item::Admins(FeedAdmins { version, admins: admins.iter().map(|a| a.to_vec()).collect() })),
    };
    Ok(SignedFeedItem::sign(&item, |m| key.sign(m).to_bytes()))
}

/// A new owner key (its secret, its public half).
pub fn new_owner_key() -> ([u8; 32], UserId) {
    let key = vodozemac::Ed25519SecretKey::new();
    let public: UserId = *key.public_key().as_bytes();
    (*key.to_bytes(), public)
}

/// A post as the app shows it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeedPost {
    pub id: Vec<u8>,
    pub author: UserId,
    pub name: String,
    pub ts_ms: u64,
    pub envelope: Vec<u8>,
    /// Who reacted with what (one each, oldest first).
    pub reactions: Vec<(UserId, String)>,
    /// When its author last edited it (unix ms; 0: never). `envelope` is already the edited one.
    pub edited_ms: u64,
}

/// What a feed's items say now.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct FeedState {
    pub admins: Vec<UserId>,
    pub admins_version: u64,
    /// Banned until (unix ms).
    pub banned: BTreeMap<UserId, u64>,
    /// Phones banned until (unix ms), by their tag in this feed (T74).
    pub banned_devices: BTreeMap<Vec<u8>, u64>,
    /// The phone tags each author posted with here (T74), for an admin's ban to name.
    pub devices_of: BTreeMap<UserId, BTreeSet<Vec<u8>>>,
    /// The posts that count, oldest first.
    pub posts: Vec<FeedPost>,
    /// Ids of every item that counts (posts, deletes, bans, the admin list in force, the photo).
    pub valid: HashSet<Vec<u8>>,
    /// The photo in force, and the admin who set it (they have the file whole).
    pub photo: Option<(crate::group::ChatPhoto, UserId)>,
    /// An admin's name and description, when one set them (else the app's own).
    pub title: Option<String>,
    pub about: Option<String>,
    /// Pinned posts' ids, the newest last (T65).
    pub pinned: Vec<Vec<u8>>,
    /// The group's slow mode now, in seconds (T67); 0 for none.
    pub slow_s: u32,
}

impl FeedState {
    pub fn is_admin(&self, sk: &UserId) -> bool {
        self.admins.contains(sk)
    }

    pub fn can_post(&self, d: &FeedDescriptor, sk: &UserId, now_ms: u64) -> bool {
        if self.banned.get(sk).is_some_and(|u| now_ms < *u) {
            return false;
        }
        !d.broadcast || self.is_admin(sk)
    }

    /// Build it from every item kept (any order), as every phone does the same way.
    pub fn build(d: &FeedDescriptor, items: &[FeedItem], now_ms: u64) -> FeedState {
        let mut st = FeedState::default();
        let ok_time = |i: &FeedItem| i.ts_ms <= now_ms + MAX_SKEW_MS;
        // the admin list: the owner's newest version
        let mut admins: Option<&FeedItem> = None;
        for i in items.iter().filter(|i| i.feed_id == d.feed_id && i.author == d.owner && ok_time(i)) {
            if let Some(Item::Admins(a)) = &i.item {
                let newer = match admins.and_then(|x| match &x.item {
                    Some(Item::Admins(b)) => Some(b.version),
                    _ => None,
                }) {
                    Some(v) => a.version > v,
                    None => true,
                };
                if newer {
                    admins = Some(i);
                }
            }
        }
        if let Some(i) = admins {
            if let Some(Item::Admins(a)) = &i.item {
                st.admins = a.admins.iter().filter_map(|s| s.as_slice().try_into().ok()).collect();
                st.admins_version = a.version;
                st.valid.insert(i.id.clone());
            }
        }
        let author_of = |i: &FeedItem| -> Option<UserId> { i.author.as_slice().try_into().ok() };
        let mut ordered: Vec<&FeedItem> = items.iter().filter(|i| i.feed_id == d.feed_id && ok_time(i)).collect();
        ordered.sort_by(|a, b| (a.ts_ms, &a.id).cmp(&(b.ts_ms, &b.id)));
        // bans from admins, in time order; the newest for each person stands, and for each phone
        for i in &ordered {
            if let (Some(Item::Ban(b)), Some(a)) = (&i.item, author_of(i)) {
                if st.is_admin(&a) {
                    if let Ok(sk) = <UserId>::try_from(b.sk.as_slice()) {
                        st.banned.insert(sk, b.until_ms);
                        for t in &b.devices {
                            st.banned_devices.insert(t.clone(), b.until_ms);
                        }
                        st.valid.insert(i.id.clone());
                    }
                }
            }
        }
        // posts: from admins in a channel, from anyone not banned at that time in a group, at
        // most GROUP_RATE an hour each
        let mut recent: HashMap<UserId, Vec<u64>> = HashMap::new();
        let mut posts: Vec<FeedPost> = Vec::new();
        let mut last_post: HashMap<UserId, u64> = HashMap::new();
        for i in &ordered {
            // slow mode, an admin's, in force from its own time on: posts before it stay (T67)
            if let (Some(Item::Slow(s)), Some(a)) = (&i.item, author_of(i)) {
                if !d.broadcast && st.is_admin(&a) {
                    st.slow_s = s.seconds.min(MAX_SLOW_S);
                    st.valid.insert(i.id.clone());
                }
                continue;
            }
            let (Some(Item::Post(env)), Some(a)) = (&i.item, author_of(i)) else { continue };
            let banned = st.banned.get(&a).is_some_and(|u| i.ts_ms < *u);
            if banned || (d.broadcast && !st.is_admin(&a)) {
                continue;
            }
            if !d.broadcast && !st.is_admin(&a) {
                // the phone (T74): a member's post from a banned phone counts nowhere, nor one
                // without its phone's tag from DEVICE_FROM_MS on
                if i.device.len() == DEVICE_LEN {
                    st.devices_of.entry(a).or_default().insert(i.device.clone());
                    if st.banned_devices.get(&i.device).is_some_and(|u| i.ts_ms < *u) {
                        continue;
                    }
                } else if i.ts_ms >= DEVICE_FROM_MS {
                    continue;
                }
                if has_web_link(&post_text(env)) {
                    continue;
                }
                if st.slow_s > 0 && last_post.get(&a).is_some_and(|t| i.ts_ms.saturating_sub(*t) < u64::from(st.slow_s) * 1000) {
                    continue;
                }
                let times = recent.entry(a).or_default();
                times.retain(|t| i.ts_ms.saturating_sub(*t) < 3_600_000);
                if times.len() >= GROUP_RATE {
                    continue;
                }
                times.push(i.ts_ms);
            }
            st.valid.insert(i.id.clone());
            last_post.insert(a, i.ts_ms);
            posts.push(FeedPost { id: i.id.clone(), author: a, name: i.name.clone(), ts_ms: i.ts_ms, envelope: env.clone(), reactions: Vec::new(), edited_ms: 0 });
        }
        // deletes: an admin's, or the post's own author's
        let mut gone: HashSet<Vec<u8>> = HashSet::new();
        for i in &ordered {
            let (Some(Item::Delete(del)), Some(a)) = (&i.item, author_of(i)) else { continue };
            let own = posts.iter().any(|p| p.id == del.target && p.author == a);
            if st.is_admin(&a) || own {
                gone.insert(del.target.clone());
                st.valid.insert(i.id.clone());
            }
        }
        posts.retain(|p| !gone.contains(&p.id));
        // edits: the post's own author's, made after it, not banned at that time, at most
        // EDIT_RATE an hour each; the newest stands, made in the post as SPEC 5 `Edit` makes it
        let mut edit_times: HashMap<UserId, Vec<u64>> = HashMap::new();
        let mut last_edit: HashMap<&Vec<u8>, &FeedItem> = HashMap::new();
        for i in &ordered {
            let (Some(Item::Edit(e)), Some(a)) = (&i.item, author_of(i)) else { continue };
            let Some(p) = posts.iter().find(|p| p.id == e.target) else { continue };
            if p.author != a || i.ts_ms < p.ts_ms || st.banned.get(&a).is_some_and(|u| i.ts_ms < *u) {
                continue;
            }
            // no link through an edit either (a member's post in the group)
            if !d.broadcast && !st.is_admin(&a) && (has_web_link(&e.caption) || e.text.as_ref().is_some_and(|x| has_web_link(&x.body))) {
                continue;
            }
            let times = edit_times.entry(a).or_default();
            times.retain(|t| i.ts_ms.saturating_sub(*t) < 3_600_000);
            if times.len() >= EDIT_RATE {
                continue;
            }
            times.push(i.ts_ms);
            last_edit.insert(&e.target, i);
        }
        for p in &mut posts {
            let Some(i) = last_edit.get(&p.id) else { continue };
            let Some(Item::Edit(e)) = &i.item else { continue };
            let edited = Envelope::from_bytes(&p.envelope).ok().and_then(|stored| crate::envelope::apply_edit(&stored, e)).and_then(|env| env.to_bytes().ok());
            if let Some(bytes) = edited {
                p.envelope = bytes;
                p.edited_ms = i.ts_ms;
                st.valid.insert(i.id.clone());
            }
        }
        // reactions: from anyone not banned at that time, on a post that counts, at most
        // REACT_RATE an hour each; each person's newest on a post stands (an empty one takes it back)
        let live: HashSet<&Vec<u8>> = posts.iter().map(|p| &p.id).collect();
        let mut react_times: HashMap<UserId, Vec<u64>> = HashMap::new();
        let mut newest: HashMap<(Vec<u8>, UserId), (&FeedItem, &str)> = HashMap::new();
        for i in &ordered {
            let (Some(Item::React(r)), Some(a)) = (&i.item, author_of(i)) else { continue };
            if !live.contains(&r.target) || st.banned.get(&a).is_some_and(|u| i.ts_ms < *u) {
                continue;
            }
            let times = react_times.entry(a).or_default();
            times.retain(|t| i.ts_ms.saturating_sub(*t) < 3_600_000);
            if times.len() >= REACT_RATE {
                continue;
            }
            times.push(i.ts_ms);
            newest.insert((r.target.clone(), a), (i, r.emoji.as_str()));
        }
        let mut standing: Vec<(&Vec<u8>, UserId, &FeedItem, &str)> = newest.iter().map(|((t, a), (i, e))| (t, *a, *i, *e)).collect();
        standing.sort_by(|x, y| (x.2.ts_ms, &x.2.id).cmp(&(y.2.ts_ms, &y.2.id)));
        let mut by_post: HashMap<&Vec<u8>, Vec<(UserId, String)>> = HashMap::new();
        for (target, a, i, emoji) in standing {
            st.valid.insert(i.id.clone());
            if !emoji.is_empty() {
                by_post.entry(target).or_default().push((a, emoji.to_string()));
            }
        }
        for p in &mut posts {
            if let Some(r) = by_post.remove(&p.id) {
                p.reactions = r;
            }
        }
        st.posts = posts;
        // the photo, the name and the description: an admin's newest
        for i in &ordered {
            let Some(a) = author_of(i).filter(|a| st.is_admin(a)) else { continue };
            match &i.item {
                Some(Item::Info(f)) => {
                    st.photo = f.photo.clone().map(|p| (p, a));
                    st.valid.insert(i.id.clone());
                }
                Some(Item::Meta(m)) => {
                    st.title = Some(m.title.clone()).filter(|t| !t.trim().is_empty());
                    st.about = Some(m.about.clone()).filter(|t| !t.trim().is_empty());
                    st.valid.insert(i.id.clone());
                }
                // a pin on a post that counts; a newer pin of the same post moves it to the end
                Some(Item::Pin(p)) if st.posts.iter().any(|x| x.id == p.target) => {
                    st.pinned.retain(|t| *t != p.target);
                    if !p.unpin {
                        st.pinned.push(p.target.clone());
                    }
                    st.valid.insert(i.id.clone());
                }
                _ => {}
            }
        }
        st
    }
}

/// A member's item that would carry a web link into the group: a post's text or caption, or an
/// edit's (the sending phone refuses it, as every phone would keep it out).
pub(crate) fn item_has_web_link(item: &Item) -> bool {
    match item {
        Item::Post(p) => has_web_link(&post_text(p)),
        Item::Edit(e) => has_web_link(&e.caption) || e.text.as_ref().is_some_and(|x| has_web_link(&x.body)),
        _ => false,
    }
}

/// A post's text, or its caption.
fn post_text(env: &[u8]) -> String {
    match Envelope::from_bytes(env).ok().and_then(|e| e.body) {
        Some(crate::envelope::Body::Text(x)) => x.body,
        Some(crate::envelope::Body::Media(m)) => m.caption,
        _ => String::new(),
    }
}

/// Config links stay allowed in the official group: sharing them is what it is for.
const CONFIG_SCHEMES: &[&str] = &[
    "vless", "vmess", "ss", "ssr", "trojan", "hysteria", "hysteria2", "hy2", "tuic", "wireguard", "wg", "socks", "socks5", "juicity", "anytls", "mieru", "naive+https",
];

/// A web link or a channel's @name in a member's text (Ehsan, 30 Oct 2026: the official group
/// filled with channel ads): any `scheme://` but a config's, `www.`, `t.me/` and the like, a bare
/// domain (`name.tld`), and an @name of 5 or more Latin letters, digits or `_`. Every phone keeps
/// such a post out of the group alike, so it is shown and passed on nowhere.
pub fn has_web_link(text: &str) -> bool {
    for raw in text.split(char::is_whitespace) {
        let t = raw.trim_matches(|c: char| !(c.is_alphanumeric() || "@/:._-+".contains(c))).to_ascii_lowercase();
        if t.is_empty() {
            continue;
        }
        if let Some(i) = t.find("://") {
            if CONFIG_SCHEMES.contains(&&t[..i]) {
                continue;
            }
            return true;
        }
        if t.starts_with("www.") || t.starts_with("tg:") || ["t.me/", "telegram.me/", "telegram.dog/"].iter().any(|h| t.contains(h)) {
            return true;
        }
        if let Some(name) = t.strip_prefix('@') {
            let name = name.trim_end_matches(|c: char| !c.is_ascii_alphanumeric());
            if name.len() >= 5 && name.starts_with(|c: char| c.is_ascii_lowercase()) && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
                return true;
            }
            continue;
        }
        let host = t.split('/').next().unwrap_or("");
        if let Some((name, tld)) = host.trim_end_matches('.').rsplit_once('.') {
            let tld_ok = (2..=10).contains(&tld.len()) && tld.chars().all(|c| c.is_ascii_lowercase());
            let name_ok = !name.is_empty() && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '.') && name.chars().any(|c| c.is_ascii_alphabetic());
            if tld_ok && name_ok {
                return true;
            }
        }
    }
    false
}

#[cfg(test)]
mod link_tests {
    use super::has_web_link;

    #[test]
    fn web_links_and_channels_but_not_configs() {
        for s in ["https://example.com/x", "join t.me/somechannel", "www.site.ir", "kanal @mychannel_ir", "bia example.com", "http://1.2.3.4:80"] {
            assert!(has_web_link(s), "{s}");
        }
        for s in ["salam doostan", "noskhe 1.2.45 amad", "vless://uuid@1.2.3.4:443?security=reality#name", "ss://YWVz@host.com:8388#x", "trojan://p@a.b.c:443", "@ali", "e.g. inja", "سلام بچه‌ها"] {
            assert!(!has_web_link(s), "{s}");
        }
    }
}
