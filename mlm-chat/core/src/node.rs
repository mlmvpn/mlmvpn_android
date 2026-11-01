//! A running MLM CHAT node: identity, contacts, Olm sessions, the encrypted store and a
//! transport, tied together. This is the core's API; the Android bridge and `peer-cli` use only
//! this. Every operation reloads sessions from the store and saves them in the same transaction
//! as its frame, so a crash can lose a message but never reuse a ratchet key.

use crate::card::{Card, CardKind};
use crate::envelope::{Body, ChatActionKind, Entity, Envelope, ReceiptKind, MAX_IDS};
use crate::frame::{self, KIND_MEGOLM, KIND_OLM_PREKEY};
use crate::identity::{self, Identity};
use crate::olm;
use crate::pair::{PairConfig, PairHost, PairSession, PairSink};
use crate::queue::{self, Waker};
use crate::store::{Contact, Db, MessageRow, MsgState, OutRow, RouteStat, Store};
use crate::transport::ble::{BleConfig, BleTransport};
use crate::transport::direct::{frame_id, DirectConfig, DirectTransport};
use crate::transport::gas::GasTransport;
use crate::transport::nostr::transport::NostrTransport;
use crate::transport::p2p::ledger::RelayStatus;
use crate::transport::p2p::{NetStatus, P2pConfig, P2pTransport};
use crate::transport::scope::NetScope;
use crate::transport::session::{rank, Pipe};
use crate::transport::{Hooks, PeerInfo, Transport, TransportError, Verdict, Via};
use crate::util::{array, now_ms, now_s};
use crate::{addr, Error, Result, UserId};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex, RwLock, Weak};
use std::thread::JoinHandle;
use std::time::Duration;

mod actions;
mod directory;
mod feedfiles;
mod feedrelay;
mod feeds;
mod gas;
mod groups;
mod invites;
mod media;
mod mirror;
mod nostr;
mod profile;
mod public;
mod reads;

pub use actions::{in_app, ACTION_EVERY_MS, ACTION_SHOWN_MS, PRESENCE_EVERY_MS, PRESENCE_TTL_MS};
pub use reads::{reads_told, READS_MAX_AGE_MS, READS_MAX_MEMBERS};
pub use media::{short_id as media_short_id, MediaInfo, ALBUM_ID_LEN};
pub use feeds::SYNC_EVERY_MS as FEED_SYNC_EVERY_MS;
pub use gas::{GasComboDraft, GasDay, GasDraft, GasOverview, GasRelayView, MAX_RELAYS_LISTED};
pub use directory::{DirResult, PUBLISH_EVERY_MS as DIR_PUBLISH_EVERY_MS};
pub use invites::{InviteInfo, InviteView, JoinRequestView};
pub use profile::{Photo, MAX_PHOTO};
pub use public::PublicRelayView;

/// A face-to-face card is valid this long.
pub const CARD_TTL_S: u64 = 30 * 24 * 3600;
const BATCH: usize = 32;
/// The p2p network key changes this often (SPEC 2). A volunteer's key stays: cards name it.
pub const NETKEY_TTL_MS: u64 = 7 * 24 * 3600 * 1000;
/// A contact reached this recently counts as online (honest status, SPEC 15.6).
pub const ONLINE_MS: u64 = 3 * 60 * 1000;
/// Route memory (SPEC 15.5): a route that failed this many times in a row on this network is
/// tried after the others, and skipped for `SKIP_MS` when another route works.
const STREAK_LIMIT: u32 = 3;
const SKIP_MS: u64 = 10 * 60 * 1000;
/// `Reachable` events at most this often per contact.
const REACH_EVENT_MS: u64 = 15_000;
const META_NETKEY: &str = "p2p_key";
const META_NETKEY_MS: &str = "p2p_key_ms";
const META_VOLKEY: &str = "volunteer_key";
/// A volunteer's kept totals (SPEC 15.12): different peers served, bytes moved, since first on.
const META_RELAY_SERVED: &str = "relay_served";
const META_RELAY_BYTES: &str = "relay_bytes";
/// Remembered volunteer addresses are kept this long after they last worked, at most this many.
const VOLUNTEER_KEEP_MS: u64 = 60 * 24 * 3600 * 1000;
const VOLUNTEER_MAX: usize = 32;
/// When the volunteers we use change, contacts get our fresh card at most this often (SPEC 15.9).
const CARD_REFRESH_MS: u64 = 6 * 3600 * 1000;
const BOOT_CHECK_MS: u64 = 60 * 1000;
const META_BOOT_SENT: &str = "boot_sent";
const META_BOOT_SENT_MS: &str = "boot_sent_ms";

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    /// A message arrived. `text` is set for text messages.
    Message { peer: UserId, id: [u8; 16], kind: &'static str, text: Option<String> },
    /// One of our messages moved forward (sent to the device, delivered, read) or failed.
    State { peer: UserId, id: [u8; 16], state: MsgState },
    /// Frames for this contact are still queued, and why (a `TransportError::code`).
    Queued { peer: UserId, reason: &'static str },
    /// A new contact: added by us, or they added us first (a request).
    Contact { peer: UserId, by_them: bool },
    /// A contact sent a newer card (KeyUpdate); its name may have changed.
    ContactChanged { peer: UserId },
    /// A contact showed up on the LAN (`lan`) or beside us over Bluetooth (`ble`).
    Seen { peer: UserId, via: Via },
    /// A contact is reachable right now: a delivery or their session just got through, `via`
    /// `lan`, `direct`, `p2p` or `relay` (a volunteer in the middle).
    Reachable { peer: UserId, via: &'static str },
    /// A group or channel changed: created, joined, someone added or removed, renamed, or we
    /// were removed (SPEC 7).
    Group { gid: Vec<u8> },
    /// A message in a group or channel. `text` is set for text messages.
    GroupMessage { gid: Vec<u8>, sender: UserId, id: [u8; 16], kind: &'static str, text: Option<String> },
    /// Chunks of a file arrived: `have` of `total`; whole and checked when they are equal.
    Blob { id: [u8; 32], have: u32, total: u32 },
    /// A contact or member sent a newer profile: their photo changed or went (SPEC 10).
    Profile { peer: UserId },
    /// Someone asked to join a group by a link that needs approval (SPEC 7.4); on the phones
    /// of admins who may invite.
    JoinRequest { gid: Vec<u8>, peer: UserId },
    /// An official feed got new items (T38), or its member count may have changed.
    Feed { feed_id: Vec<u8> },
    /// Someone's reaction to message `id` changed: in the one-to-one chat with `chat` (a contact's
    /// key), or in group `chat` when `group`. `peer` is who reacted (us, too).
    Reaction { chat: Vec<u8>, group: bool, id: [u8; 16], peer: UserId },
    /// Someone's vote on poll `id` of group `chat` changed (T69); `peer` is who voted (us, too).
    PollVote { chat: Vec<u8>, group: bool, id: [u8; 16], peer: UserId },
    /// Messages were taken down for everyone (SPEC 5.2) by the other side: in the one-to-one chat
    /// with `chat`, or in group `chat` when `group`. `seqs` are this phone's numbers for them, as
    /// `history` and `group_messages` gave them (the rows are gone now).
    Deleted { chat: Vec<u8>, group: bool, seqs: Vec<i64> },
    /// A message's text (or a media message's caption) was edited (SPEC 5 `Edit`), by us or by its
    /// sender: in the one-to-one chat with `chat`, or in group `chat` when `group`. `seq` is this
    /// phone's number for it; `history` and `group_messages` give it as it is now.
    Edited { chat: Vec<u8>, group: bool, seq: i64 },
    /// The pinned messages of the one-to-one chat with `peer` changed (SPEC 5 `Pin`, T65): read
    /// them with `pinned`. A group's or an official feed's come with `Group` and `Feed`.
    Pinned { peer: UserId },
    /// A contact is doing this in our one-to-one chat right now (SPEC 5.4, T56): `typing`,
    /// `upload_photo`, `upload_file`, `record_voice`, or `cancel` (no longer). Shown until a newer
    /// one, or for `ACTION_SHOWN_MS`.
    ChatAction { peer: UserId, kind: &'static str },
    /// A contact's app came to the front (`online`) or left it (SPEC 5.5, T57): only while our own
    /// last seen is shown. [`Node::contact_status`] has when, and whether «online» still holds.
    Presence { peer: UserId, online: bool },
    /// `peer` read our message `seq` in group `gid` (SPEC 7.6, T58): [`Node::group_reads`] has who
    /// and when.
    GroupRead { gid: Vec<u8>, seq: i64, peer: UserId },
    /// What is known of the app `peer` writes from changed (SPEC 5 `Client`, T75): their first
    /// envelope since this phone has kept it, or one from another app or version. Read it with
    /// [`Node::clients_since`].
    Client { peer: UserId },
}

/// Where a poll stands ([`Node::poll_results`], T69).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PollResults {
    /// Votes for each option, in the poll's order.
    pub counts: Vec<u32>,
    /// How many voted.
    pub voters: u32,
    /// Our own choices; empty when we have not voted.
    pub mine: Vec<u32>,
    /// Who chose what; only for a poll that is not anonymous.
    pub who: Vec<(UserId, Vec<u32>)>,
}

/// What the app shows under a contact's name (SPEC 15.6): only what really happened.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct ContactStatus {
    /// When a session with them last got through, either way (0 = never).
    pub last_reached_ms: u64,
    /// How (`lan`, `direct`, `p2p`, `relay`), empty if never.
    pub via: String,
    /// Reached within `ONLINE_MS`, and no delivery to them has failed since.
    pub online: bool,
    /// Our messages to them still waiting, and the last reason.
    pub queued: usize,
    pub reason: Option<String>,
    /// When a frame last came from them through one of our Google mailboxes, and last went into
    /// theirs (0 = never). This is a route, not «online»: a mailbox holds what was left for a while.
    pub mailbox_in_ms: u64,
    pub mailbox_out_ms: u64,
    /// Google mailbox relays their newest card names (SPEC 19.3). With none and no other route,
    /// nothing of ours can reach them until a newer card of theirs arrives (a link sent again).
    pub their_relays: usize,
    /// When they were last seen in the app (SPEC 5.5): their newest «online» or «offline», by this
    /// phone's clock; 0 when not known, and while our own last seen is hidden.
    pub last_seen_ms: u64,
    /// In the app right now: «online» within `PRESENCE_TTL_MS` and no «offline» since.
    pub in_app: bool,
}

/// The network as the app's status line tells it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct NodeNet {
    /// The network label routes are remembered under.
    pub network: String,
    pub direct: bool,
    pub p2p: Option<NetStatus>,
    /// Address ranges the node is limited to (SPEC 15.10); 0 = none set, everything allowed.
    pub scope: usize,
}

/// Whether this device could be a volunteer where it is now (SPEC 15.11). No address in it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct VolunteerCheck {
    /// `suitable` (a public IPv4 and a door: everyone can reach it); `same_operator` (the router
    /// sits behind the operator's NAT, like LTE home modems in Iran, but opened a door there:
    /// subscribers of the same operator may reach it); `ipv6_maybe` (no usable IPv4, but this
    /// device has a global IPv6: whether peers get in depends on the router's IPv6 firewall);
    /// `no_router` (no router answered UPnP or NAT-PMP, no IPv6: mobile data, or a router that
    /// opens no doors); `no_address`; `private` (the router sits behind another router);
    /// `outside_scope` (the router's address is outside the node's scope, SPEC 15.10). Anything
    /// but `suitable` is proven or not only once the role is on (SPEC 15.9).
    pub verdict: &'static str,
    /// The router's door method: `upnp4`, `natpmp`, or empty.
    pub method: &'static str,
    /// The router's IPv4: `public`, `carrier_nat`, `private`, `outside_scope`, or `none`.
    pub v4: &'static str,
    /// This device has a global IPv6 address (inside the scope).
    pub v6: bool,
    /// The router could open an IPv6 pinhole itself (UPnP IGD v2).
    pub pinhole6: bool,
}

#[derive(Debug, Clone)]
pub struct NodeOptions {
    /// Send "read" receipts (Telegram's privacy setting).
    pub read_receipts: bool,
    /// Accept messages from someone who is not a contact yet but holds our locator (they scanned
    /// our QR); they appear as a request.
    pub accept_requests: bool,
}

impl Default for NodeOptions {
    fn default() -> Self {
        NodeOptions { read_receipts: true, accept_requests: true }
    }
}

#[derive(Debug, Clone)]
pub struct Me {
    pub sk: UserId,
    pub name: String,
    pub display_name: String,
    /// Our 30-digit half of every security number.
    pub code: String,
}

struct State {
    store: Store,
    identity: Identity,
}

struct Inner {
    /// This node itself, for work handed to a thread of its own (`actions.rs`).
    me: Weak<Inner>,
    state: crate::util::TimedMutex<State>,
    keys: RwLock<(UserId, [u8; 32])>,
    /// Tried in the order this network has proven (SPEC 15.5); the direct transport first.
    transports: Mutex<Vec<Arc<dyn Transport>>>,
    p2p: Mutex<Option<Arc<P2pTransport>>>,
    /// The Google mailbox transport (SPEC 19), when started.
    gas: Mutex<Option<Arc<GasTransport>>>,
    /// The Nostr transport (SPEC 20), when started.
    nostr: Mutex<Option<Arc<NostrTransport>>>,
    /// Contacts a delivery is going to right now (`deliver_loop`).
    delivering: Mutex<HashMap<UserId, u8>>,
    /// The mailbox relays our cards name changed: contacts get the new card at once.
    gas_dirty: AtomicBool,
    events: Mutex<Option<Sender<Event>>>,
    waker: Waker,
    stop: AtomicBool,
    opts: NodeOptions,
    /// The label route memory is kept under; empty = worked out from our addresses.
    network: RwLock<String>,
    /// Last `Reachable` event per contact, to keep them rare.
    reach_told: Mutex<HashMap<UserId, u64>>,
    /// Where we may connect (SPEC 15.10); None = everywhere.
    scope: RwLock<Option<Arc<NetScope>>>,
    /// Bluetooth LE (SPEC 16), when the app turned «nearby» on.
    ble: Mutex<Option<Arc<BleTransport>>>,
    /// The open «add nearby» screen (SPEC 16.4).
    pair: Mutex<Option<Arc<PairSession>>>,
    /// Where files are kept, sealed (SPEC 17): next to the database.
    blob_dir: std::path::PathBuf,
    /// The official feeds this app ships (T38).
    feeds: Mutex<Vec<crate::feed::FeedDescriptor>>,
    /// When each peer last got our feeds' catch-up, and our count sketches and public relays.
    feed_sent: Mutex<feeds::FeedSent>,
    /// Each feed's state as last built: (`store::feed_gen` then, when, the state). Kept until the
    /// items change or `feeds::STATE_KEEP_MS` passes (`Node::feed_state`).
    feed_cache: Mutex<HashMap<Vec<u8>, (u64, u64, crate::feed::FeedState)>>,
    /// One look at the public relays at a time (`public.rs`).
    public_busy: Mutex<()>,
    /// Held frames are being tried again (`replay_held`): one pass at a time.
    replaying: AtomicBool,
    /// Asked for while a pass ran: one more pass.
    replay_again: AtomicBool,
    /// The official feeds' copy (`mirror.rs`), when the app gave one; when it is due next (0: by
    /// the clock) and when it was last read.
    mirror: RwLock<Option<crate::transport::gas::proto::RelayRef>>,
    mirror_due: AtomicU64,
    mirror_last: AtomicU64,
    /// Something of ours waits to go to the copy (set by our own items; true at start), and
    /// whether we were an admin there at the last look (an admin leaves everyone's items).
    mirror_up: AtomicBool,
    mirror_admin: AtomicBool,
    /// Our own relays that official files go to, files read there lately, and one go of leaving
    /// files there at a time (`feedfiles.rs`, SPEC 18.3).
    file_relays: RwLock<Vec<String>>,
    file_fetches: Mutex<HashMap<crate::blob::BlobId, u64>>,
    files_putting: AtomicBool,
    /// One go of leaving official items on those relays at a time, and when an admin last looked
    /// for items they lack (`feedrelay.rs`, SPEC 18.7).
    feed_relay_busy: AtomicBool,
    feed_relay_scanned: AtomicU64,
    /// The last chat action sent to each contact (`actions.rs`).
    actions: Mutex<HashMap<UserId, actions::ActionSent>>,
    /// The app is in front (`Node::set_online`), and the last presence told to each contact.
    in_front: AtomicBool,
    presence_sent: Mutex<HashMap<UserId, actions::PresenceSent>>,
    /// What the app read of the phone itself (`Node::set_device`, T74); empty until then. Only its
    /// hash with each feed ever leaves the phone (`feed::device_tag`).
    device: RwLock<Vec<u8>>,
}

pub struct Node {
    inner: Arc<Inner>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

/// Where a database's files are kept: `blobs` next to it.
fn blob_dir(db_path: &Path) -> std::path::PathBuf {
    db_path.parent().map(|p| p.join("blobs")).unwrap_or_else(|| std::path::PathBuf::from("blobs"))
}

/// The name a contact is shown with: their chosen name and four digits of their code (T14).
pub fn contact_display_name(c: &Contact) -> String {
    let name = if c.name.is_empty() { "?" } else { &c.name };
    identity::display_name(name, &identity::personal_code(&c.sk, &c.ik))
}

fn card_addrs(c: &Contact) -> Vec<SocketAddr> {
    Card::from_bytes(&c.card).map(|card| card.addrs.iter().filter_map(|a| addr::decode(a)).collect()).unwrap_or_default()
}

/// A label for the network we are on when the app gives none: the IPv4 /16 and IPv6 /32 of our
/// addresses, hashed (it stays on this device, in the encrypted database).
pub fn auto_network(addrs: &[SocketAddr]) -> String {
    let mut parts: Vec<String> = addrs
        .iter()
        .map(|a| match a.ip() {
            IpAddr::V4(v) => format!("4:{}.{}", v.octets()[0], v.octets()[1]),
            IpAddr::V6(v) => format!("6:{:x}:{:x}", v.segments()[0], v.segments()[1]),
        })
        .collect();
    parts.sort();
    parts.dedup();
    let h = Sha256::digest(parts.join(",").as_bytes());
    format!("auto:{}", hex::encode(&h[..4]))
}

/// The order to try the transports in for one contact on this network (SPEC 15.5): a route
/// known fresh right now first (a LAN sighting, an open connection); then those that did not
/// fail `STREAK_LIMIT` times in a row, the most recent success first; then the others. A route
/// on such a streak, tried within `SKIP_MS`, is left out while another route has worked since.
pub fn route_order(names: &[(&'static str, bool)], stats: &[RouteStat], now: u64) -> Vec<usize> {
    let stat = |name: &str| stats.iter().find(|s| s.via == name);
    let best_ok = stats.iter().map(|s| s.last_ok_ms).max().unwrap_or(0);
    let mut idx: Vec<usize> = (0..names.len()).collect();
    idx.retain(|&i| {
        let (name, fresh) = names[i];
        match stat(name) {
            // a mailbox is never set aside: to a phone that is off it is the only way
            Some(s) if mailbox_rank(name) == 0 && !fresh && s.streak >= STREAK_LIMIT && now.saturating_sub(s.last_try_ms) < SKIP_MS && best_ok > s.last_ok_ms => false,
            _ => true,
        }
    });
    if idx.is_empty() {
        idx = (0..names.len()).collect();
    }
    idx.sort_by_key(|&i| {
        let (name, fresh) = names[i];
        let s = stat(name);
        let streak = s.is_some_and(|s| s.streak >= STREAK_LIMIT);
        let mailbox = mailbox_rank(name);
        (!fresh, mailbox != 0, streak, mailbox, std::cmp::Reverse(s.map(|s| s.last_ok_ms).unwrap_or(0)), i)
    });
    idx
}

/// 0 for a route to the contact's phone itself. A mailbox (it does not mean the contact is there)
/// comes after every other route; of the two, Nostr (at once, no polling) before the Google mailbox.
fn mailbox_rank(name: &str) -> u8 {
    match name {
        "nostr" => 1,
        "gas" => 2,
        _ => 0,
    }
}

/// Reached within `ONLINE_MS` (at `at`), and no delivery to them has failed since (at `failed`).
fn is_online(at: u64, failed: u64, now: u64) -> bool {
    at > 0 && at >= failed && now.saturating_sub(at) < ONLINE_MS
}

impl Node {
    /// A new identity in a new database. Refuses a database that already has one.
    pub fn create(db_path: &Path, db_key: &[u8; 32], name: &str, opts: NodeOptions) -> Result<Node> {
        Self::create_with(db_path, db_key, Identity::new(name)?, opts)
    }

    /// A new database for an identity made beforehand (see [`Identity::set_name`]). Refuses a
    /// database that already has one.
    pub fn create_with(db_path: &Path, db_key: &[u8; 32], identity: Identity, opts: NodeOptions) -> Result<Node> {
        let store = Store::open(db_path, db_key)?;
        if store.db().identity()?.is_some() {
            return Err(Error::Identity("this database already has an identity"));
        }
        store.db().save_identity(&identity.to_saved())?;
        Ok(Self::from_parts(store, identity, opts, blob_dir(db_path)))
    }

    pub fn open(db_path: &Path, db_key: &[u8; 32], opts: NodeOptions) -> Result<Node> {
        let store = Store::open(db_path, db_key)?;
        let saved = store.db().identity()?.ok_or(Error::NotFound("identity"))?;
        let identity = Identity::from_saved(&saved)?;
        Ok(Self::from_parts(store, identity, opts, blob_dir(db_path)))
    }

    fn from_parts(store: Store, mut identity: Identity, opts: NodeOptions, blob_dir: std::path::PathBuf) -> Node {
        let keys = (identity.sk(), identity.loc());
        // our cards name the mailbox relays of the list from the first card on
        identity.set_relays(gas::card_relays(&store.db().gas_relays().unwrap_or_default(), None));
        let inner = Arc::new_cyclic(|me: &Weak<Inner>| Inner {
            me: me.clone(),
            state: crate::util::TimedMutex::new(State { store, identity }),
            keys: RwLock::new(keys),
            transports: Mutex::new(Vec::new()),
            p2p: Mutex::new(None),
            gas: Mutex::new(None),
            nostr: Mutex::new(None),
            delivering: Mutex::new(HashMap::new()),
            gas_dirty: AtomicBool::new(false),
            events: Mutex::new(None),
            waker: Waker::default(),
            stop: AtomicBool::new(false),
            opts,
            network: RwLock::new(String::new()),
            reach_told: Mutex::new(HashMap::new()),
            scope: RwLock::new(None),
            ble: Mutex::new(None),
            pair: Mutex::new(None),
            blob_dir,
            feeds: Mutex::new(Vec::new()),
            feed_sent: Mutex::new(feeds::FeedSent::default()),
            feed_cache: Mutex::new(HashMap::new()),
            public_busy: Mutex::new(()),
            replaying: AtomicBool::new(false),
            replay_again: AtomicBool::new(false),
            mirror: RwLock::new(None),
            mirror_due: AtomicU64::new(0),
            mirror_last: AtomicU64::new(0),
            mirror_up: AtomicBool::new(true),
            mirror_admin: AtomicBool::new(false),
            file_relays: RwLock::new(crate::transport::nostr::files::relays()),
            file_fetches: Mutex::new(HashMap::new()),
            files_putting: AtomicBool::new(false),
            feed_relay_busy: AtomicBool::new(false),
            feed_relay_scanned: AtomicU64::new(0),
            actions: Mutex::new(HashMap::new()),
            in_front: AtomicBool::new(false),
            presence_sent: Mutex::new(HashMap::new()),
            device: RwLock::new(Vec::new()),
        });
        Node { inner, worker: Mutex::new(None) }
    }

    /// What the app read of the phone itself, the same after its data is cleared or it is put on
    /// again (on Android its `ANDROID_ID`; T74): every official post carries its hash with the feed,
    /// so an admin's ban holds the phone and a new account made on it posts no more. Set it at
    /// each start, before posting.
    pub fn set_device(&self, device: &[u8]) {
        *self.inner.device.write().expect("device") = device.to_vec();
    }

    /// Start the phase-1 direct transport (LAN, direct IPv6) and the delivery loop.
    pub fn start_direct(&self, cfg: DirectConfig) -> Result<Arc<DirectTransport>> {
        let t = DirectTransport::start(cfg, self.hooks())?;
        self.start_with(t.clone());
        Ok(t)
    }

    /// The object a transport calls back into.
    pub fn hooks(&self) -> Arc<dyn Hooks> {
        Arc::new(NodeHooks(Arc::downgrade(&self.inner)))
    }

    /// Start the p2p transport (phase 3): volunteers, the private DHT, relay circuits (SPEC 15).
    /// With no key in `cfg`, the node uses its own: a volunteer's stays the same (cards name its
    /// peer id), anyone else's changes every 7 days (SPEC 2).
    pub fn start_p2p(&self, mut cfg: P2pConfig) -> Result<Arc<P2pTransport>> {
        if cfg.key.is_empty() {
            cfg.key = self.p2p_key(cfg.volunteer)?;
        }
        let t = P2pTransport::start(cfg, self.hooks())?;
        *self.inner.p2p.lock().expect("p2p") = Some(t.clone());
        self.start_with(t.clone());
        Ok(t)
    }

    fn p2p_key(&self, volunteer: bool) -> Result<Vec<u8>> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        if volunteer {
            if let Some(k) = db.meta(META_VOLKEY)? {
                return Ok(k);
            }
        } else if let (Some(k), Some(ms)) = (db.meta(META_NETKEY)?, db.meta(META_NETKEY_MS)?) {
            let made = <[u8; 8]>::try_from(ms.as_slice()).map(u64::from_le_bytes).unwrap_or(0);
            if now_ms().saturating_sub(made) < NETKEY_TTL_MS {
                return Ok(k);
            }
        }
        let fresh = libp2p::identity::Keypair::generate_ed25519().to_protobuf_encoding().map_err(|_| Error::Identity("libp2p key"))?;
        if volunteer {
            db.set_meta(META_VOLKEY, &fresh)?;
        } else {
            db.set_meta(META_NETKEY, &fresh)?;
            db.set_meta(META_NETKEY_MS, &now_ms().to_le_bytes())?;
        }
        Ok(fresh)
    }

    /// Start the delivery loop on one more transport (tried after those already there, until the
    /// route memory knows better).
    pub fn start_with(&self, transport: Arc<dyn Transport>) {
        self.inner.transports.lock().expect("transport").push(transport);
        let mut worker = self.worker.lock().expect("worker");
        if worker.is_none() {
            let inner = self.inner.clone();
            *worker = Some(std::thread::Builder::new().name("mlmchat-deliver".into()).spawn(move || deliver_loop(inner)).expect("spawn delivery thread"));
            // frames that are no message of the user's and waited over three days go, a few at a
            // time with the lock free between: official items passed on and the like only grew
            // (1.2.44, 30 Oct 2026). First the queues' indexes, seconds once on a big outbox, off
            // the thread that opened the database (1.2.45, 1 Nov 2026)
            let weak = Arc::downgrade(&self.inner);
            let _ = std::thread::Builder::new().name("mlmchat-trim".into()).spawn(move || {
                if let Some(i) = weak.upgrade() {
                    let t = std::time::Instant::now();
                    if let Err(e) = i.state.lock().expect("state").store.db().build_late_indexes() {
                        log::info!("store: queue indexes not built ({e})");
                    } else if t.elapsed().as_millis() >= 1000 {
                        log::info!("store: queue indexes built in {} ms", t.elapsed().as_millis());
                    }
                }
                let before = now_ms().saturating_sub(crate::store::OUTBOX_KEEP_MS);
                let mut after = 0;
                loop {
                    let Some(i) = weak.upgrade() else { return };
                    let step = i.state.lock().expect("state").store.transaction(|db| db.prune_outbox_step(after, before, 500));
                    drop(i);
                    match step {
                        Ok((last, false)) => after = last,
                        _ => return,
                    }
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
            });
        }
        self.inner.waker.wake();
    }

    pub fn stop(&self) {
        self.inner.stop.store(true, Ordering::SeqCst);
        let transports: Vec<Arc<dyn Transport>> = self.inner.transports.lock().expect("transport").clone();
        for t in transports {
            t.stop();
        }
        self.inner.waker.wake();
        if let Some(h) = self.worker.lock().expect("worker").take() {
            let _ = h.join();
        }
    }

    /// Events for the app. A second call replaces the first receiver.
    pub fn events(&self) -> Receiver<Event> {
        let (tx, rx) = channel();
        *self.inner.events.lock().expect("events") = Some(tx);
        rx
    }

    pub fn me(&self) -> Me {
        let st = self.inner.state.lock().expect("state");
        Me { sk: st.identity.sk(), name: st.identity.name().to_string(), display_name: st.identity.display_name(), code: st.identity.code() }
    }

    /// Renames this identity (the four digits stay, T14) and sends the new card to every contact
    /// not blocked, in the KeyUpdate a new contact also gets (SPEC 6); their phones show the new
    /// name once it arrives. Returns the new `Me`.
    pub fn set_name(&self, name: &str) -> Result<Me> {
        {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let old = identity.name().to_string();
            identity.set_name(name)?;
            let sent = (|| {
                let update = Envelope::key_update(identity.loc(), self.inner.session_card_bytes(identity)?);
                let saved = identity.to_saved();
                let contacts = store.db().contacts()?;
                store.transaction(|db| {
                    db.save_identity(&saved)?;
                    for c in contacts.iter().filter(|c| !c.blocked) {
                        enqueue(db, identity, c, &update, false)?;
                    }
                    Ok(())
                })
            })();
            if let Err(e) = sent {
                identity.set_name(&old)?;
                return Err(e);
            }
        }
        log::info!("node: renamed");
        self.inner.waker.wake();
        Ok(self.me())
    }

    /// A face-to-face card with the transports' addresses and volunteers, as an `mlmchat://c/` link.
    pub fn card_link(&self) -> Result<String> {
        self.inner.card_link()
    }

    /// A face-to-face card with the given addresses (and the volunteers we use). Each card has a
    /// fresh one-time key.
    pub fn card_link_for(&self, addrs: &[SocketAddr]) -> Result<String> {
        self.inner.card_link_for(addrs)
    }

    /// Add a contact from their link or QR, and send them our card inside the new session so
    /// they can answer and find us.
    pub fn add_contact(&self, link: &str) -> Result<Contact> {
        self.inner.add_contact(link)
    }

    /// A member of one of our groups added as a contact (Telegram's «add contact» on their
    /// profile): the row the group made from their own card is shown in the contact list, and
    /// they get our card inside the session, as after [`Node::add_contact`]. `NotFound` while
    /// no group has a card the member published themself.
    pub fn add_member_contact(&self, sk: &UserId) -> Result<Contact> {
        self.inner.add_member_contact(sk)
    }

    /// The contact list: not the members of shared groups the user never added themself.
    pub fn contacts(&self) -> Result<Vec<Contact>> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let mut out = Vec::new();
        for c in db.contacts()? {
            if !db.is_group_peer(&c.sk)? {
                out.push(c);
            }
        }
        Ok(out)
    }

    pub fn contact(&self, peer: &UserId) -> Result<Option<Contact>> {
        self.inner.state.lock().expect("state").store.db().contact(peer)
    }

    pub fn send_text(&self, peer: &UserId, text: &str) -> Result<[u8; 16]> {
        self.send_text_reply(peer, text, 0)
    }

    /// A text in answer to message `reply_to` of the chat with `peer` (its seq here; 0 for none).
    /// The envelope's `reply_to` carries that message's id (SPEC 5), which each phone turns back
    /// into its own seq ([`Node::reply_seq`]). A seq that is not a message of this chat goes as a
    /// plain text.
    pub fn send_text_reply(&self, peer: &UserId, text: &str, reply_to: i64) -> Result<[u8; 16]> {
        self.send_text_rich(peer, text, Vec::new(), reply_to)
    }

    /// A text with its formatting and mentions (SPEC 5 `Entity`, UTF-16 offsets), optionally in
    /// answer to message `reply_to` (see [`Node::send_text_reply`]). Entities the envelope checks
    /// would refuse are dropped, not the message.
    pub fn send_text_rich(&self, peer: &UserId, text: &str, entities: Vec<Entity>, reply_to: i64) -> Result<[u8; 16]> {
        let mut env = Envelope::rich_text(text, entities);
        env.reply_to = self.reply_id(peer, reply_to)?;
        self.send_envelope(peer, &env)
    }

    /// The id behind seq `seq` of the chat with `peer`, for an envelope's `reply_to`: empty for 0
    /// and for a seq of another chat.
    pub(crate) fn reply_id(&self, peer: &UserId, seq: i64) -> Result<Vec<u8>> {
        if seq <= 0 {
            return Ok(Vec::new());
        }
        let st = self.inner.state.lock().expect("state");
        let rows = st.store.db().messages_by_seq(&[seq])?;
        Ok(rows.into_iter().find(|(_, p, _)| p == peer).map(|(_, _, id)| id.to_vec()).unwrap_or_default())
    }

    /// This phone's seq of the message an envelope of the chat with `peer` answers (its
    /// `reply_to`); 0 for none, and for a message that is not here.
    pub fn reply_seq(&self, peer: &UserId, reply_to: &[u8]) -> Result<i64> {
        if reply_to.is_empty() {
            return Ok(0);
        }
        Ok(self.inner.state.lock().expect("state").store.db().message_seq(peer, reply_to)?.unwrap_or(0))
    }

    /// Our reaction to message `target` of the chat with `peer` (theirs or ours); an empty emoji
    /// takes it back. Kept here and sent to them (SPEC 5 `Reaction`).
    pub fn react(&self, peer: &UserId, target: &[u8; 16], emoji: &str) -> Result<()> {
        if emoji.len() > 32 {
            return Err(Error::Envelope("bad reaction"));
        }
        let env = Envelope::new(Body::Reaction(crate::envelope::Reaction { target: target.to_vec(), emoji: emoji.to_string(), remove: emoji.is_empty() }));
        let me = {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let me = identity.sk();
            let contact = store.db().contact(peer)?.ok_or(Error::NotFound("contact"))?;
            if contact.blocked {
                return Err(Error::Session("this contact is blocked"));
            }
            store.transaction(|db| {
                db.reaction_set(peer, target, &me, emoji, env.sent_ms)?;
                enqueue(db, identity, &contact, &env, false).map(|_| ())
            })?;
            me
        };
        self.inner.emit(Event::Reaction { chat: peer.to_vec(), group: false, id: *target, peer: me });
        self.inner.waker.wake();
        Ok(())
    }

    /// Delete one-to-one messages by seq for both sides (SPEC 5.2): each one's contact gets a
    /// `Delete` and takes its copy down too; then they go here. Returns how many went here.
    pub fn delete_for_everyone(&self, seqs: &[i64]) -> Result<usize> {
        let n = {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let identity: &Identity = identity;
            store.transaction(|db| {
                let rows = db.messages_by_seq(seqs)?;
                let mut by_peer: HashMap<UserId, Vec<Vec<u8>>> = HashMap::new();
                for (_, peer, id) in &rows {
                    by_peer.entry(*peer).or_default().push(id.to_vec());
                }
                for (peer, ids) in &by_peer {
                    if let Some(c) = db.contact(peer)?.filter(|c| !c.blocked) {
                        for chunk in ids.chunks(crate::envelope::MAX_IDS) {
                            let env = Envelope::new(Body::Delete(crate::envelope::Delete { ids: chunk.to_vec(), for_everyone: true }));
                            enqueue(db, identity, &c, &env, false)?;
                        }
                    }
                    for id in ids {
                        db.reactions_drop(peer, id)?;
                    }
                }
                db.delete_messages(seqs)
            })?
        };
        self.inner.waker.wake();
        Ok(n)
    }

    /// Give a one-to-one message we sent (by seq) a new text, or a media message a new caption, on
    /// both sides (SPEC 5 `Edit`): changed here, and its contact gets the `Edit`.
    pub fn edit_text(&self, seq: i64, text: &str, entities: Vec<Entity>) -> Result<()> {
        let now = now_ms();
        let peer = {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let identity: &Identity = identity;
            store.transaction(|db| {
                let (_, peer, id) = db.messages_by_seq(&[seq])?.into_iter().next().ok_or(Error::NotFound("message"))?;
                let row = db.message(&id)?.ok_or(Error::NotFound("message"))?;
                if !row.outgoing {
                    return Err(Error::Session("only one's own messages are edited"));
                }
                let stored = Envelope::from_bytes(&row.envelope)?;
                let edit = crate::envelope::edit_for(&stored, &id, text, entities).ok_or(Error::Session("this message has no text to edit"))?;
                let new = crate::envelope::apply_edit(&stored, &edit).ok_or(Error::Session("this message has no text to edit"))?;
                db.message_edit(&id, &new.to_bytes()?, now)?;
                if let Some(c) = db.contact(&peer)?.filter(|c| !c.blocked) {
                    enqueue(db, identity, &c, &Envelope::new(Body::Edit(edit)), false)?;
                }
                Ok(peer)
            })?
        };
        self.inner.emit(Event::Edited { chat: peer.to_vec(), group: false, seq });
        self.inner.waker.wake();
        Ok(())
    }

    /// When a message was last edited (by its sender's clock), 0 if never: one-to-one or group.
    pub fn edited_ms(&self, id: &[u8]) -> Result<u64> {
        self.inner.state.lock().expect("state").store.db().edited_ms(id)
    }

    /// The reactions standing in the chat with `peer`: (message id, who, emoji), oldest first.
    pub fn reactions(&self, peer: &UserId) -> Result<Vec<([u8; 16], UserId, String)>> {
        self.inner.state.lock().expect("state").store.db().reactions(peer)
    }

    /// Queue any visible envelope for a contact; returns its id.
    pub fn send_envelope(&self, peer: &UserId, env: &Envelope) -> Result<[u8; 16]> {
        let id = array::<16>(&env.id).ok_or(Error::Envelope("id must be 16 bytes"))?;
        {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            if *peer == identity.sk() {
                // Saved Messages (Ehsan, p3, 30 Oct 2026: a message to oneself got «!»): it stays
                // on this phone, read at once, and nothing goes out; what is no message (a pin, a
                // reaction) has nothing to carry
                if env.is_visible() {
                    store.db().insert_message(&MessageRow {
                        id,
                        peer: *peer,
                        outgoing: true,
                        sent_ms: env.sent_ms,
                        at_ms: now_ms(),
                        kind: env.kind_name().to_string(),
                        envelope: env.to_bytes()?,
                        state: MsgState::Read,
                        seq: 0,
                    })?;
                }
                return Ok(id);
            }
            let contact = store.db().contact(peer)?.ok_or(Error::NotFound("contact"))?;
            if contact.blocked {
                return Err(Error::Session("this contact is blocked"));
            }
            store.transaction(|db| enqueue(db, identity, &contact, env, env.is_visible()))?;
        }
        log::info!("node: queued kind={}", env.kind_name());
        self.inner.waker.wake();
        Ok(id)
    }

    /// Pin message `seq` of the chat with `peer` (its seq here) for both of us, or take its pin
    /// off (SPEC 5 `Pin`, T65; Ehsan, 29 Oct 2026).
    pub fn pin(&self, peer: &UserId, seq: i64, unpin: bool) -> Result<()> {
        let id = self.inner.state.lock().expect("state").store.db().message_at(peer, seq)?.ok_or(Error::NotFound("message"))?.id;
        let env = Envelope::new(Body::Pin(crate::envelope::Pin { target: id.to_vec(), unpin, silent: false }));
        self.send_envelope(peer, &env)?;
        self.inner.state.lock().expect("state").store.db().pin_set(peer, &id, !unpin, now_ms())?;
        self.inner.emit(Event::Pinned { peer: *peer });
        Ok(())
    }

    /// The chat's pinned messages, by their seq here, the newest last.
    pub fn pinned(&self, peer: &UserId) -> Result<Vec<i64>> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let mut out = Vec::new();
        for id in db.pins(peer)? {
            if let Some(seq) = db.message_seq(peer, &id)? {
                out.push(seq);
            }
        }
        Ok(out)
    }

    /// Mark everything from `peer` as read; sends a "read" receipt if that setting is on.
    pub fn mark_read(&self, peer: &UserId) -> Result<usize> {
        let n = {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let ids = store.db().unread(peer)?;
            if ids.is_empty() {
                return Ok(0);
            }
            let contact = store.db().contact(peer)?.ok_or(Error::NotFound("contact"))?;
            let read_receipts = self.inner.opts.read_receipts && !contact.blocked;
            store.transaction(|db| {
                for id in &ids {
                    db.set_state(id, MsgState::Seen)?;
                }
                if read_receipts {
                    for chunk in ids.chunks(MAX_IDS) {
                        let receipt = Envelope::receipt(ReceiptKind::Read, chunk.iter().map(|i| i.to_vec()).collect());
                        enqueue(db, identity, &contact, &receipt, false)?;
                    }
                }
                Ok(())
            })?;
            ids.len()
        };
        self.inner.waker.wake();
        Ok(n)
    }

    pub fn history(&self, peer: &UserId, limit: usize) -> Result<Vec<MessageRow>> {
        self.inner.state.lock().expect("state").store.db().messages(peer, limit)
    }

    /// A page of the chat with `peer`, oldest first: with `after` below 0, the newest `limit`
    /// before seq `before` (0: the newest of all); else the oldest `limit` after seq `after` (0:
    /// from the first). A screen asks for the page it shows, never the whole chat: a long chat
    /// opens as fast as a short one, and no message is out of reach (Ehsan, 28 Oct 2026: «کندی
    /// چت شخصی نسبت به گروه»).
    pub fn history_page(&self, peer: &UserId, before: i64, after: i64, limit: usize) -> Result<Vec<MessageRow>> {
        self.inner.state.lock().expect("state").store.db().messages_page(peer, before, after, limit)
    }

    /// One message of the chat with `peer`: by its id, or by its seq when `id` is `None`.
    pub fn message(&self, peer: &UserId, id: Option<&[u8; 16]>, seq: i64) -> Result<Option<MessageRow>> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        match id {
            Some(id) => Ok(db.message(id)?.filter(|m| m.peer == *peer)),
            None => db.message_at(peer, seq),
        }
    }

    /// The one-to-one messages of every chat stored after seq `after`, oldest first, at most
    /// `limit`: what came while an app was not looking, without reading any chat whole.
    pub fn messages_since(&self, after: i64, limit: usize) -> Result<Vec<MessageRow>> {
        self.inner.state.lock().expect("state").store.db().messages_since(after, limit)
    }

    /// The chat with `peer` in a chat list: unread count and the newest seq read on each side.
    pub fn chat_marks(&self, peer: &UserId) -> Result<crate::store::ChatMarks> {
        self.inner.state.lock().expect("state").store.db().chat_marks(peer)
    }

    /// Delete messages by seq on this phone only; the other phone keeps its copy (there is no
    /// "delete for both" in the protocol). A message deleted while still queued is not sent.
    pub fn delete_messages(&self, seqs: &[i64]) -> Result<usize> {
        self.inner.state.lock().expect("state").store.transaction(|db| db.delete_messages(seqs))
    }

    /// Delete the conversation with `peer` up to `max_seq` (all of it for 0), on this phone only.
    pub fn clear_history(&self, peer: &UserId, max_seq: i64) -> Result<usize> {
        self.inner.state.lock().expect("state").store.transaction(|db| db.clear_messages(peer, max_seq))
    }

    /// Every message on this phone goes, with the files only they carried; the account, contacts,
    /// groups, channels and the official feeds stay (q3: «پاک کردن دادهٔ گره», Ehsan 31 Oct 2026).
    /// Returns how many messages went.
    pub fn clear_all_messages(&self) -> Result<usize> {
        let (n, files) = self.inner.state.lock().expect("state").store.transaction(|db| {
            let (n, blobs) = db.clear_all_messages()?;
            Ok((n, db.drop_blobs(&blobs)?))
        })?;
        for id in &files {
            let _ = std::fs::remove_file(media::blob_path(&self.inner.blob_dir, id));
        }
        Ok(n)
    }

    /// Why a message is still queued: (tries so far, last reason code).
    pub fn queue_reason(&self, msg_id: &[u8; 16]) -> Result<Option<(u32, Option<String>)>> {
        self.inner.state.lock().expect("state").store.db().queue_reason(msg_id)
    }

    pub fn outbox_len(&self) -> Result<usize> {
        self.inner.state.lock().expect("state").store.db().outbox_len()
    }

    /// The app this phone runs in, put on everything it sends from now on (SPEC 5 `Client`, T75):
    /// `app` its package name, `code` its version code, `name` its version as its releases are tagged.
    pub fn set_client(&self, app: &str, code: u32, name: &str) {
        crate::envelope::set_client(Some(crate::envelope::Client { app: app.to_string(), code, name: name.to_string() }));
    }

    /// The apps people write from, changed at or after `since_ms` by this phone's clock (T75).
    pub fn clients_since(&self, since_ms: u64) -> Result<Vec<crate::store::ClientRow>> {
        self.inner.state.lock().expect("state").store.db().clients_since(since_ms)
    }

    /// The 60-digit number both people compare (SPEC 2).
    pub fn security_number(&self, peer: &UserId) -> Result<String> {
        let st = self.inner.state.lock().expect("state");
        let c = st.store.db().contact(peer)?.ok_or(Error::NotFound("contact"))?;
        Ok(identity::security_number(&st.identity.code(), &identity::personal_code(&c.sk, &c.ik)))
    }

    pub fn set_verified(&self, peer: &UserId, verified: bool) -> Result<()> {
        self.update_contact(peer, |c| c.verified = verified)
    }

    /// Blocking stays on this phone (SPEC 10): their frames are dropped, nothing is sent to them.
    pub fn set_blocked(&self, peer: &UserId, blocked: bool) -> Result<()> {
        self.update_contact(peer, |c| c.blocked = blocked)
    }

    /// The network label routes are remembered under (SPEC 15.5), e.g. the operator and the
    /// network type the app knows. Empty = worked out from our addresses.
    pub fn set_network(&self, label: &str) {
        let label: String = label.chars().take(64).collect();
        let changed = {
            let mut cur = self.inner.network.write().expect("network");
            let changed = *cur != label;
            *cur = label;
            changed
        };
        if changed {
            if let Some(p) = self.inner.p2p.lock().expect("p2p").as_ref() {
                p.network_relabeled();
            }
        }
    }

    /// The device moved to another network: fresh connections, a fresh presence record, and every
    /// queued frame tried at once.
    pub fn network_changed(&self) {
        if let Some(p) = self.inner.p2p.lock().expect("p2p").as_ref() {
            p.network_changed();
        }
        if let Some(g) = self.inner.gas.lock().expect("gas").as_ref() {
            g.network_changed();
        }
        if let Some(t) = self.inner.nostr.lock().expect("nostr").as_ref() {
            t.network_changed();
        }
        let _ = self.inner.state.lock().expect("state").store.db().wake_all();
        self.inner.waker.wake();
    }

    pub fn net_status(&self) -> NodeNet {
        let direct = self.inner.transports.lock().expect("transport").iter().any(|t| t.name() == "direct");
        let scope = self.inner.scope.read().expect("scope").as_ref().map_or(0, |s| s.len());
        NodeNet { network: self.inner.network_label(), direct, p2p: self.inner.p2p.lock().expect("p2p").as_ref().map(|p| p.status()), scope }
    }

    /// Limit every transport to these address ranges (SPEC 15.10), or `None` for everywhere. Takes
    /// effect at once: open connections are dropped and the ladder climbs again within the new
    /// limit; queued frames are tried again.
    pub fn set_scope(&self, scope: Option<NetScope>) {
        *self.inner.scope.write().expect("scope") = scope.map(Arc::new);
        self.network_changed();
    }

    /// Could this device be a volunteer on the network it is on now (SPEC 15.11)? Asks the
    /// routers only read-only questions (a couple of seconds); opens nothing. The real proof comes
    /// once the user turns the role on: a peer on another network connects (SPEC 15.9).
    pub fn volunteer_check(&self) -> VolunteerCheck {
        use crate::transport::p2p::door;
        let gws = door::gateways();
        let p = door::probe(&gws);
        let allows = |ip: IpAddr| NodeHooks(Arc::downgrade(&self.inner)).allows(ip);
        // IPv4: what the router's own address is
        let v4 = if p.method.is_empty() {
            "none"
        } else {
            match p.external4 {
                None => "none",
                Some(v) if v.is_private() || v.is_loopback() || v.is_link_local() || v.is_unspecified() => "private",
                Some(v) if door::carrier_nat(v) => "carrier_nat",
                Some(v) if !allows(IpAddr::V4(v)) => "outside_scope",
                Some(_) => "public",
            }
        };
        // IPv6: a global address of this device's own (a door to it is a pinhole on the router,
        // which only a peer on another network can prove, SPEC 15.9)
        let v6 = gws.iter().flat_map(|g| g.local6.iter()).any(|a| allows(IpAddr::V6(*a)));
        let verdict = match v4 {
            "public" => "suitable",
            // the operator's NAT space: subscribers of the same operator may reach the mapping
            "carrier_nat" => "same_operator",
            _ if v6 => "ipv6_maybe",
            "private" => "private",
            "outside_scope" => "outside_scope",
            _ if p.method.is_empty() => "no_router",
            _ => "no_address",
        };
        log::info!("volunteer check: {verdict} (router {}, ipv6 {})", if p.method.is_empty() { "none" } else { p.method }, if v6 { "yes" } else { "no" });
        VolunteerCheck { verdict, method: p.method, v4, v6, pinhole6: p.pinhole6 }
    }

    /// A volunteer's ledger (SPEC 15.12); all zero when the p2p transport is not running.
    pub fn relay_status(&self) -> RelayStatus {
        self.inner.p2p.lock().expect("p2p").as_ref().map(|p| p.relay_status()).unwrap_or_default()
    }

    /// Disconnect one peer of the ledger by its short name, and keep it out for an hour.
    pub fn relay_kick(&self, name: &str) -> bool {
        let p = self.inner.p2p.lock().expect("p2p").clone();
        p.is_some_and(|p| p.relay_kick(name))
    }

    /// Start Bluetooth LE (SPEC 16): the platform's radio code then feeds it sightings and links
    /// through the returned transport. Safe to call again: the running one is returned.
    pub fn start_ble(&self, cfg: BleConfig) -> Arc<BleTransport> {
        let mut slot = self.inner.ble.lock().expect("ble");
        if let Some(t) = slot.as_ref() {
            return t.clone();
        }
        let t = BleTransport::start(cfg, self.hooks());
        *slot = Some(t.clone());
        drop(slot);
        if let Some(p) = self.pair() {
            t.set_pair(Some(p as Arc<dyn PairSink>));
        }
        self.start_with(t.clone());
        t
    }

    /// Open «add nearby» (SPEC 16.4, T36) for `cfg.window`: Wi-Fi announcements as `cfg` says,
    /// and Bluetooth while it runs. A second call closes the first.
    pub fn pair_start(&self, cfg: PairConfig) -> Result<Arc<PairSession>> {
        self.pair_stop();
        let host: Arc<dyn PairHost> = Arc::new(NodePair(Arc::downgrade(&self.inner)));
        let s = PairSession::start(cfg, host)?;
        *self.inner.pair.lock().expect("pair") = Some(s.clone());
        if let Some(b) = self.ble() {
            b.set_pair(Some(s.clone() as Arc<dyn PairSink>));
        }
        Ok(s)
    }

    /// The «add nearby» screen, while it is open.
    pub fn pair(&self) -> Option<Arc<PairSession>> {
        self.inner.pair.lock().expect("pair").clone().filter(|p| p.is_open())
    }

    /// Close «add nearby»: Bluetooth goes back to advertising the contact tag.
    pub fn pair_stop(&self) {
        let Some(p) = self.inner.pair.lock().expect("pair").take() else { return };
        p.stop();
        if let Some(b) = self.ble() {
            b.set_pair(None);
        }
    }

    /// The Bluetooth transport, if «nearby» is on.
    pub fn ble(&self) -> Option<Arc<BleTransport>> {
        self.inner.ble.lock().expect("ble").clone()
    }

    /// Stop Bluetooth LE («nearby» turned off). The other transports go on.
    pub fn stop_ble(&self) {
        let Some(b) = self.inner.ble.lock().expect("ble").take() else { return };
        self.inner.transports.lock().expect("transport").retain(|t| t.name() != "ble");
        Transport::stop(&*b);
    }

    /// Stop the p2p transport, to start it again with another config (the user turned the
    /// volunteer role on or off). The direct transport and the queue go on.
    pub fn stop_p2p(&self) {
        let Some(p) = self.inner.p2p.lock().expect("p2p").take() else { return };
        self.inner.transports.lock().expect("transport").retain(|t| t.name() != "p2p");
        Transport::stop(&*p);
    }

    /// What really happened with this contact, for the line under their name.
    pub fn contact_status(&self, peer: &UserId) -> Result<ContactStatus> {
        let st = self.inner.state.lock().expect("state");
        let db = st.store.db();
        let (at, via) = db.reach(peer)?.unwrap_or((0, String::new()));
        let failed = db.unreach(peer)?;
        let (queued, reason) = db.queued_for(peer)?;
        let online = is_online(at, failed, now_ms());
        let (mailbox_in_ms, mailbox_out_ms) = db.gas_peer(peer)?;
        let their_relays = db.contact(peer)?.and_then(|c| Card::from_bytes(&c.card).ok()).map_or(0, |card| card.all_relays().len());
        // whoever hides their own last seen sees nobody's (SPEC 5.5)
        let (on, off) = if actions::last_seen_shown(&db) { db.presence(peer)? } else { (0, 0) };
        Ok(ContactStatus {
            last_reached_ms: at,
            online,
            via,
            queued,
            reason,
            mailbox_in_ms,
            mailbox_out_ms,
            their_relays,
            last_seen_ms: on.max(off),
            in_app: actions::in_app(on, off, now_ms()),
        })
    }

    fn update_contact(&self, peer: &UserId, f: impl FnOnce(&mut Contact)) -> Result<()> {
        let st = self.inner.state.lock().expect("state");
        let mut c = st.store.db().contact(peer)?.ok_or(Error::NotFound("contact"))?;
        f(&mut c);
        st.store.db().upsert_contact(&c)
    }
}

impl Drop for Node {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Encrypt one envelope for a contact and put its frame in the outbox (inside a transaction).
/// Hold a frame that cannot be used yet, to try it again when group events or keys come in
/// (`Inner::replay_held`): only while we are in a group or joining one, the only way such a frame
/// becomes usable later. False when it is not held (no group, or no room).
pub(crate) fn hold_frame(db: &Db, fid: &[u8; 16], from: &UserId, bytes: &[u8], now: u64) -> bool {
    let in_group = db.groups().is_ok_and(|g| !g.is_empty()) || db.joining().is_ok_and(|j| !j.is_empty());
    in_group && db.held_add(fid, from, bytes, now).unwrap_or(false)
}

fn enqueue(db: &Db, identity: &Identity, contact: &Contact, env: &Envelope, store_message: bool) -> Result<[u8; 16]> {
    let now = now_ms();
    let bytes = env.to_bytes()?;
    let id = array::<16>(&env.id).ok_or(Error::Envelope("id must be 16 bytes"))?;
    let mut sessions = olm::load(db.sessions(&contact.sk)?)?;
    let enc = olm::encrypt(identity, &mut sessions, &contact.sk, &contact.ik, &contact.otk, &bytes, now)?;
    let frame = frame::encode_padded(&[], enc.kind, &enc.cipher)?;
    for s in sessions.iter().filter(|s| s.dirty) {
        db.save_session(&s.to_row())?;
    }
    if store_message {
        db.insert_message(&MessageRow { id, peer: contact.sk, outgoing: true, sent_ms: env.sent_ms, at_ms: now, kind: env.kind_name().to_string(), envelope: bytes, state: MsgState::Queued, seq: 0 })?;
    }
    db.enqueue(&OutRow { frame_id: frame_id(&frame), peer: contact.sk, msg_id: store_message.then_some(id), frame, attempts: 0, next_ms: 0, last_error: None })?;
    Ok(id)
}

/// Like [`enqueue`] (no message row), for a frame that waits until `next_ms` behind the others and
/// is never tried again: a delivery that fails drops it (a group read receipt, SPEC 7.6).
pub(crate) fn enqueue_once(db: &Db, identity: &Identity, contact: &Contact, env: &Envelope, next_ms: u64) -> Result<()> {
    let bytes = env.to_bytes()?;
    let mut sessions = olm::load(db.sessions(&contact.sk)?)?;
    let enc = olm::encrypt(identity, &mut sessions, &contact.sk, &contact.ik, &contact.otk, &bytes, now_ms())?;
    let frame = frame::encode_padded(&[], enc.kind, &enc.cipher)?;
    for s in sessions.iter().filter(|s| s.dirty) {
        db.save_session(&s.to_row())?;
    }
    let fid = frame_id(&frame);
    db.enqueue(&OutRow { frame_id: fid, peer: contact.sk, msg_id: None, frame, attempts: 0, next_ms, last_error: None })?;
    db.out_once_mark(&fid)
}

/// The first envelope from someone who is not a contact must be their card (KeyUpdate), signed by
/// the sk the connection proved, with the identity key the PreKey message used.
fn bind_new_contact(env: &Envelope, from: &UserId, sender_ik: Option<[u8; 32]>) -> Option<Contact> {
    let Some(Body::KeyUpdate(k)) = &env.body else { return None };
    let card = Card::from_bytes(&k.card).ok()?;
    let loc = card.loc()?;
    if card.sk != *from || Some(card.ik) != sender_ik || k.loc[..] != loc[..] {
        return None;
    }
    Some(Contact {
        sk: card.sk,
        ik: card.ik,
        hk: card.hk,
        otk: card.otk,
        loc: Some(loc),
        name: card.name.clone().unwrap_or_default(),
        card: k.card.clone(),
        added_ms: now_ms(),
        by_them: true,
        blocked: false,
        verified: false,
    })
}

/// A newer card from a known contact: same sk and identity key, or it is ignored.
fn apply_key_update(contact: &mut Contact, k: &crate::envelope::KeyUpdate) -> bool {
    let Ok(card) = Card::from_bytes(&k.card) else { return false };
    let Some(loc) = card.loc() else { return false };
    if card.sk != contact.sk || card.ik != contact.ik || k.loc[..] != loc[..] {
        return false;
    }
    contact.hk = card.hk;
    contact.otk = card.otk;
    contact.loc = Some(loc);
    contact.card = k.card.clone();
    if let Some(n) = card.name {
        contact.name = n;
    }
    true
}

impl Inner {
    fn card_link(&self) -> Result<String> {
        let addrs = self.advertised();
        self.card_link_for(&addrs)
    }

    /// A face-to-face card with the given addresses (and the volunteers we use). Each card has a
    /// fresh one-time key.
    fn card_link_for(&self, addrs: &[SocketAddr]) -> Result<String> {
        self.sync_card_nostr();
        let boot = self.boot();
        let mut guard = self.state.lock().expect("state");
        let State { store, identity } = &mut *guard;
        let card = identity.face_to_face_card(addrs.iter().map(addr::encode).collect(), boot, CARD_TTL_S)?;
        store.db().save_identity(&identity.to_saved())?;
        Ok(card.to_link())
    }

    /// Add a contact from their link or QR, and send them our card inside the new session so
    /// they can answer and find us.
    fn add_contact(&self, link: &str) -> Result<Contact> {
        let card = Card::from_link(link)?;
        if card.is_expired(now_s()) {
            return Err(Error::Card("this card has expired; ask for a new QR"));
        }
        let loc = match card.kind {
            CardKind::FaceToFace { loc } => loc,
            CardKind::Remote { .. } => return Err(Error::Card("remote links need the DHT (phase 3); scan the QR face to face")),
        };
        // the card they get names the relays we read from this moment (not as of the last check)
        self.sync_card_nostr();
        let mut guard = self.state.lock().expect("state");
        let State { store, identity } = &mut *guard;
        if card.sk == identity.sk() {
            return Err(Error::Card("this is your own card"));
        }
        let old = store.db().contact(&card.sk)?;
        if old.as_ref().is_some_and(|o| o.ik != card.ik) {
            return Err(Error::Card("this card has a different identity key than the saved contact"));
        }
        let contact = Contact {
            sk: card.sk,
            ik: card.ik,
            hk: card.hk,
            otk: card.otk,
            loc: Some(loc),
            name: card.name.clone().unwrap_or_default(),
            card: card.to_bytes(),
            added_ms: old.as_ref().map(|o| o.added_ms).unwrap_or_else(now_ms),
            by_them: false,
            blocked: old.as_ref().is_some_and(|o| o.blocked),
            verified: old.as_ref().is_some_and(|o| o.verified),
        };
        self.list_contact(guard, contact)
    }

    /// A group member as a contact: their row from the card they published in a group (a contact
    /// already stays as it is). A card in a group log is not checked for expiry: it is the
    /// member's own, signed into the log, and the hidden row was already used to reach them.
    fn add_member_contact(&self, sk: &UserId) -> Result<Contact> {
        let guard = self.state.lock().expect("state");
        if *sk == guard.identity.sk() {
            return Err(Error::Card("this is your own card"));
        }
        let db = guard.store.db();
        // a member of one of our groups, else someone who posted in an official feed with their
        // card (SPEC 18.4: a member opens a chat with another from the official group)
        let found = match groups::member_by_sk(&db, sk)? {
            Some(c) => Some(c),
            None => feeds::author_contact(&db, sk)?,
        };
        let Some(mut contact) = found else {
            return Err(Error::NotFound("no card from this member yet"));
        };
        if db.is_group_peer(sk)? {
            // listed from now on, like any contact added now
            contact.added_ms = now_ms();
            contact.by_them = false;
        }
        drop(db);
        self.list_contact(guard, contact)
    }

    /// The end of adding a contact: saved and shown in the list, and our card (and profile) sent
    /// to them inside the session so they can answer and find us.
    fn list_contact(&self, mut guard: crate::util::TimedGuard<'_, State>, contact: Contact) -> Result<Contact> {
        let State { store, identity } = &mut *guard;
        let my_card = self.session_card_bytes(identity)?;
        let update = Envelope::key_update(identity.loc(), my_card);
        let saved_identity = identity.to_saved();
        store.transaction(|db| {
            db.save_identity(&saved_identity)?;
            db.upsert_contact(&contact)?;
            db.unmark_group_peer(&contact.sk)?;
            enqueue(db, identity, &contact, &update, false)?;
            if let Some(p) = profile::own_profile(db, identity)? {
                enqueue(db, identity, &contact, &p, false)?;
            }
            Ok(())
        })?;
        drop(guard);
        log::info!("node: contact added");
        self.emit(Event::Contact { peer: contact.sk, by_them: false });
        self.waker.wake();
        Ok(contact)
    }

    fn session_card_bytes(&self, identity: &mut Identity) -> Result<Vec<u8>> {
        let addrs = self.advertised();
        Ok(identity.session_card(addrs.iter().map(addr::encode).collect(), self.boot(), CARD_TTL_S)?.to_bytes())
    }

    fn emit(&self, e: Event) {
        if let Some(tx) = self.events.lock().expect("events").as_ref() {
            let _ = tx.send(e);
        }
    }

    /// Addresses for our cards: every transport's.
    fn advertised(&self) -> Vec<SocketAddr> {
        let mut out = Vec::new();
        for t in self.transports.lock().expect("transport").iter() {
            for a in t.advertised_addrs() {
                if !out.contains(&a) {
                    out.push(a);
                }
            }
        }
        out
    }

    /// Volunteers for our cards' `boot`.
    fn boot(&self) -> Vec<Vec<u8>> {
        let mut out: Vec<Vec<u8>> = Vec::new();
        for t in self.transports.lock().expect("transport").iter() {
            for b in t.boot() {
                if !out.contains(&b) && out.len() < crate::card::MAX_BOOT {
                    out.push(b);
                }
            }
        }
        out
    }

    fn network_label(&self) -> String {
        let label = self.network.read().expect("network").clone();
        if label.is_empty() {
            auto_network(&self.advertised())
        } else {
            label
        }
    }

    /// A contact is reachable right now: remember it (the "last seen" the app shows is this, a
    /// session that really got through) and tell the app, not too often.
    fn reached(&self, peer: UserId, via: Via) {
        let now = now_ms();
        let _ = self.state.lock().expect("state").store.db().set_reach(&peer, now, via);
        let tell = {
            let mut told = self.reach_told.lock().expect("reach");
            let fresh = told.get(&peer).is_none_or(|t| now.saturating_sub(*t) > REACH_EVENT_MS);
            if fresh {
                told.insert(peer, now);
            }
            fresh
        };
        if tell {
            self.emit(Event::Reachable { peer, via });
        }
    }

    fn on_frame(&self, from: UserId, bytes: Vec<u8>) -> Verdict {
        let Ok(f) = frame::decode(&bytes) else { return Verdict::Dropped };
        if f.kind == KIND_MEGOLM {
            return self.on_group_frame(from, &bytes, &f.cipher);
        }
        if f.kind == crate::frame::KIND_BLOB {
            return self.on_blob_frame(from, &bytes, &f.cipher);
        }
        if f.kind == crate::frame::KIND_PART {
            return Verdict::Dropped; // parts are joined inside the mailbox transport only
        }
        let fid = frame_id(&bytes);
        let mut guard = self.state.lock().expect("state");
        let State { store, identity } = &mut *guard;
        let now = now_ms();
        match store.db().got_frame(&fid) {
            Ok(true) => return Verdict::Stored, // offered again after a lost acknowledgement
            Ok(false) => {}
            Err(_) => return Verdict::Retry,
        }
        let Ok(mut known) = store.db().contact(&from) else { return Verdict::Retry };
        if known.is_none() {
            // a member of one of our groups who never wrote to us before (SPEC 7.3)
            known = groups::member_by_sk(&store.db(), &from).ok().flatten();
        }
        if known.as_ref().is_some_and(|c| c.blocked) {
            return Verdict::Dropped;
        }
        let mut sessions = match store.db().sessions(&from).and_then(olm::load) {
            Ok(s) => s,
            Err(Error::Store(_)) => return Verdict::Retry,
            Err(_) => return Verdict::Dropped,
        };
        // a PreKey message may use up a one-time key: keep the account to undo that on refusal
        let before = (f.kind == KIND_OLM_PREKEY).then(|| identity.to_saved());
        let undo = |identity: &mut Identity| {
            if let Some(b) = &before {
                if let Ok(back) = Identity::from_saved(b) {
                    *identity = back;
                }
            }
        };
        let dec = match olm::decrypt(identity, &mut sessions, &from, known.as_ref().map(|c| &c.ik), f.kind, &f.cipher, now) {
            Ok(d) => d,
            Err(_) => {
                log::debug!("node: a frame did not decrypt");
                return Verdict::Dropped;
            }
        };
        let env = match Envelope::from_bytes(&dec.plaintext) {
            Ok(e) => e,
            Err(_) => {
                undo(identity);
                return Verdict::Dropped;
            }
        };
        // a chat action (SPEC 5.4) is transient: nothing of it is kept, not even its ids, and one
        // from someone not known yet is never held for later (it would only be stale)
        let transient = matches!(&env.body, Some(Body::ChatAction(_)));
        let (mut contact, is_new) = match known {
            Some(c) => (c, false),
            None => match bind_new_contact(&env, &from, dec.sender_ik).filter(|_| self.opts.accept_requests) {
                Some(c) => (c, true),
                None => {
                    undo(identity);
                    // a member of one of our groups whose news (the event that made them one) is
                    // still on its way: before, this frame (their group key) was refused for good
                    // and their messages never opened here (a link joiner, 25 Oct 2026)
                    if !transient && hold_frame(&store.db(), &fid, &from, &bytes, now) {
                        log::debug!("node: held a first frame from someone not known yet");
                        return Verdict::Stored;
                    }
                    log::debug!("node: refused a first frame from a stranger");
                    return Verdict::Dropped;
                }
            },
        };
        let Some(env_id) = array::<16>(&env.id) else { return Verdict::Dropped };

        let mut events = Vec::new();
        let mut wake = false;
        let mut joined: Option<Vec<u8>> = None;
        // what a held frame may have waited for: a member's group event, a group key, a contact
        let unlock = is_new || matches!(&env.body, Some(Body::GroupOps(_) | Body::KeyShare(_) | Body::KeyUpdate(_)));
        let reply_card = if is_new { self.reply_card(identity) } else { None };
        // asking to join a group by a link: a stranger stays out of the contact list
        let joining = matches!(&env.body, Some(Body::KeyUpdate(k)) if k.join.is_some());
        let mut auto_add: Option<invites::AutoAdd> = None;
        let mut feed_more = false;
        let mut answer_presence = false;
        let feed_list = self.feed_list();
        let my_loc = identity.loc();
        let my_sk = identity.sk();
        // a new inbound session used a one-time key; a reply card may have made the fallback key
        let saved_identity = (dec.created || reply_card.is_some()).then(|| identity.to_saved());
        let result = store.transaction(|db| {
            for s in sessions.iter().filter(|s| s.dirty) {
                db.save_session(&s.to_row())?;
            }
            if let Some(saved) = &saved_identity {
                db.save_identity(saved)?;
            }
            if !transient {
                db.mark_got(&fid, now)?;
            }
            db.held_drop(&fid)?;
            if is_new {
                db.upsert_contact(&contact)?;
                if joining {
                    db.mark_group_peer(&from)?;
                } else {
                    events.push(Event::Contact { peer: from, by_them: true });
                }
            }
            if !transient && !db.mark_seen(&env.id, now)? {
                return Ok(()); // duplicate: already handled
            }
            // the app it came from (SPEC 5 `Client`, T75), for the «old version» tag beside them
            if db.client_seen(&from, env.client.as_ref(), env.sent_ms.min(now), now)? {
                events.push(Event::Client { peer: from });
            }
            let mut replies: Vec<Envelope> = Vec::new();
            match &env.body {
                Some(Body::ChatAction(a)) => match ChatActionKind::try_from(a.kind) {
                    // their app came to the front or left it (SPEC 5.5): kept and told only while
                    // our own last seen is shown; a contact's «online» gets ours back
                    Ok(k @ (ChatActionKind::Online | ChatActionKind::Offline)) => {
                        if actions::last_seen_shown(db) {
                            let online = k == ChatActionKind::Online;
                            db.presence_set(&from, online, now)?;
                            events.push(Event::Presence { peer: from, online });
                            answer_presence = online && !db.is_group_peer(&from)?;
                        }
                    }
                    // what they are doing in our chat right now: told, never kept
                    Ok(kind) => events.push(Event::ChatAction { peer: from, kind: kind.name() }),
                    // a kind of a newer phone
                    Err(_) => {}
                },
                Some(Body::Receipt(r)) => {
                    let state = if r.kind == ReceiptKind::Read as i32 { MsgState::Read } else { MsgState::Delivered };
                    let ids: Vec<[u8; 16]> = r.ids.iter().filter_map(|i| array::<16>(i)).collect();
                    let changed = db.advance_outgoing(&from, &ids, state)?;
                    for id in &changed {
                        events.push(Event::State { peer: from, id: *id, state });
                    }
                    if state == MsgState::Read {
                        // a member read our group messages (SPEC 7.6): the ids of no one-to-one message
                        reads::on_receipt(db, &my_sk, &from, &ids, &changed, now, &mut events)?;
                    }
                }
                Some(Body::KeyUpdate(k)) => {
                    if !is_new && apply_key_update(&mut contact, k) {
                        db.upsert_contact(&contact)?;
                        events.push(Event::ContactChanged { peer: from });
                    }
                    if let Some(card) = &reply_card {
                        replies.push(Envelope::key_update(my_loc, card.clone()));
                    }
                    if let Some(j) = &k.join {
                        auto_add = invites::on_join_request(db, identity, &from, &k.card, j, &mut events)?;
                    }
                }
                Some(Body::GroupOps(g)) => {
                    let o = groups::on_ops(db, identity, &from, g, &mut events)?;
                    wake |= o.wake;
                    if o.joined.is_some() {
                        joined = o.joined;
                    }
                }
                Some(Body::KeyShare(k)) => groups::on_key_share(db, &from, k)?,
                Some(Body::BlobRequest(r)) => {
                    wake |= media::on_request(db, &from, r)?;
                }
                Some(Body::Profile(p)) => {
                    wake |= profile::on_profile(db, identity, &from, p, &mut events)?;
                }
                Some(Body::Feed(f)) => {
                    let o = feeds::on_feed(db, identity, &feed_list, &from, f, &mut events)?;
                    wake |= o.wake;
                    feed_more |= o.more;
                }
                Some(Body::Delete(d)) if d.for_everyone => {
                    // they took messages of this chat down for both of us (SPEC 5.2): theirs or ours.
                    // One not here yet is marked seen, so it is not taken in when it comes late.
                    let mut seqs = Vec::new();
                    for id in d.ids.iter().filter(|i| i.len() == 16) {
                        match db.message_seq(&from, id)? {
                            Some(seq) => {
                                db.delete_messages(&[seq])?;
                                db.reactions_drop(&from, id)?;
                                seqs.push(seq);
                            }
                            None => {
                                db.mark_seen(id, now)?;
                            }
                        }
                    }
                    if !seqs.is_empty() {
                        events.push(Event::Deleted { chat: from.to_vec(), group: false, seqs });
                    }
                }
                Some(Body::Edit(e)) => {
                    // their edit of a message they sent us (SPEC 5 `Edit`): changed in place, never
                    // a message itself. One of ours, one not here, or an edit older than the last one
                    // taken (two edits crossing on the way) is dropped.
                    if let Some(target) = array::<16>(&e.target) {
                        if let Some(row) = db.message(&target)?.filter(|r| !r.outgoing && r.peer == from) {
                            if env.sent_ms > db.edited_ms(&target)? {
                                if let Some(new) = Envelope::from_bytes(&row.envelope).ok().and_then(|s| crate::envelope::apply_edit(&s, e)) {
                                    if db.message_edit(&target, &new.to_bytes()?, env.sent_ms)? {
                                        if let Some(seq) = db.message_seq(&from, &target)? {
                                            events.push(Event::Edited { chat: from.to_vec(), group: false, seq });
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Some(Body::Pin(p)) => {
                    // they pinned a message of this chat for both of us, or took a pin off (T65):
                    // only a message of this very chat, theirs or ours
                    if let Some(target) = array::<16>(&p.target) {
                        if db.message_seq(&from, &target)?.is_some() {
                            db.pin_set(&from, &target, !p.unpin, env.sent_ms.min(now))?;
                            events.push(Event::Pinned { peer: from });
                        }
                    }
                }
                Some(Body::Reaction(r)) => {
                    // their reaction to a message of this chat: kept beside it, never a message itself
                    let emoji = if r.remove { "" } else { r.emoji.as_str() };
                    if let Some(target) = array::<16>(&r.target) {
                        if db.reaction_set(&from, &target, &from, emoji, env.sent_ms)? {
                            events.push(Event::Reaction { chat: from.to_vec(), group: false, id: target, peer: from });
                        }
                    }
                }
                Some(_) if env.is_visible() => {
                    let row = MessageRow { id: env_id, peer: from, outgoing: false, sent_ms: env.sent_ms, at_ms: now, kind: env.kind_name().to_string(), envelope: dec.plaintext.clone(), state: MsgState::Received, seq: 0 };
                    if let Some(Body::Media(m)) = &env.body {
                        wake |= media::on_media(db, identity, &from, m)?;
                    }
                    if db.insert_message(&row)? {
                        let text = match &env.body {
                            Some(Body::Text(t)) => Some(t.body.clone()),
                            _ => None,
                        };
                        events.push(Event::Message { peer: from, id: env_id, kind: env.kind_name(), text });
                    }
                    replies.push(Envelope::receipt(ReceiptKind::Delivered, vec![env_id.to_vec()]));
                }
                _ => {}
            }
            if is_new {
                // a new contact (they added us first) sees our photo too
                if let Some(p) = profile::own_profile(db, identity)? {
                    replies.push(p);
                }
            }
            for r in &replies {
                enqueue(db, identity, &contact, r, false)?;
                wake = true;
            }
            Ok(())
        });
        if let Err(e) = &result {
            log::debug!("node: a frame was not stored ({e})");
            undo(identity);
            return Verdict::Retry;
        }
        if let Some(gid) = &joined {
            // just added to a group: our own card goes to its members (SPEC 7.3)
            if let Err(e) = self.announce_card(store, identity, gid) {
                log::debug!("node: card for a new group not sent ({e})");
            }
            wake = true;
        }
        drop(guard);
        if transient {
            log::debug!("node: frame taken kind={}", env.kind_name());
        } else {
            log::info!("node: frame stored kind={}", env.kind_name());
        }
        for e in events {
            self.emit(e);
        }
        if feed_more {
            self.feed_more(&from);
        }
        if answer_presence {
            self.answer_presence(&from);
        }
        if let Some(a) = auto_add {
            self.auto_add(a);
        }
        if wake {
            self.waker.wake();
        }
        if unlock {
            self.replay_held();
        }
        Verdict::Stored
    }

    /// Try the held frames again (`hold_frame`): what they waited for may have come. One that
    /// still cannot be used stays held until it expires; a refused one goes.
    fn replay_held(&self) {
        loop {
            if self.replaying.swap(true, Ordering::SeqCst) {
                // another thread is at it: it goes round once more for what this one brought (a
                // group key that came in meanwhile was otherwise never tried on the held frames)
                self.replay_again.store(true, Ordering::SeqCst);
                return;
            }
            // a few rounds: a group key let out of the queue may open messages held before it
            for _ in 0..8 {
                self.replay_again.store(false, Ordering::SeqCst);
                let used = self.replay_round();
                if used > 0 {
                    log::info!("node: {used} held frames taken in");
                } else if !self.replay_again.load(Ordering::SeqCst) {
                    break;
                }
            }
            self.replaying.store(false, Ordering::SeqCst);
            // asked again between the last round and here: that round is ours
            if !self.replay_again.swap(false, Ordering::SeqCst) {
                return;
            }
        }
    }

    /// One pass over the held frames; how many were taken in.
    fn replay_round(&self) -> usize {
        let (held, carded) = {
            let st = self.state.lock().expect("state");
            let db = st.store.db();
            (db.held(crate::store::MAX_HELD as usize).unwrap_or_default(), groups::members_with_cards(&db).unwrap_or_default())
        };
        let mut used = 0;
        let mut unknown: std::collections::HashSet<UserId> = std::collections::HashSet::new();
        for (fid, from, bytes) in held {
            if self.state.lock().expect("state").store.db().got_frame(&fid).unwrap_or(false) {
                let _ = self.state.lock().expect("state").store.db().held_drop(&fid);
                continue;
            }
            // a sender we still cannot tell apart (no contact, no own card in a group's log): its
            // frame would only be held again. Before, every held frame went through decryption
            // and the group's whole state on every group event, and a group of twenty spent most
            // of its time there (stress.rs, 26 Oct 2026).
            if unknown.contains(&from) {
                continue;
            }
            if !carded.contains(&from) && self.state.lock().expect("state").store.db().contact(&from).ok().flatten().is_none() {
                unknown.insert(from);
                continue;
            }
            let v = self.on_frame(from, bytes);
            let db = || self.state.lock().expect("state");
            match v {
                Verdict::Dropped => {
                    let _ = db().store.db().held_drop(&fid);
                }
                Verdict::Stored if !db().store.db().held_has(&fid).unwrap_or(true) => used += 1,
                _ => {}
            }
        }
        used
    }

    /// Our card for a new contact's answer: it carries the fallback key, our addresses and the
    /// volunteers we use.
    fn reply_card(&self, identity: &mut Identity) -> Option<Vec<u8>> {
        let addrs = self.advertised();
        identity.session_card(addrs.iter().map(addr::encode).collect(), self.boot(), CARD_TTL_S).ok().map(|c| c.to_bytes())
    }

    fn peer_info(&self, peer: &UserId) -> Option<PeerInfo> {
        let st = self.state.lock().expect("state");
        let c = st.store.db().contact(peer).ok()??;
        if c.blocked {
            return None;
        }
        let card = Card::from_bytes(&c.card).ok();
        let relays = card.as_ref().map(|card| card.all_relays()).unwrap_or_default();
        let nostr = card.map(|card| card.nostr.iter().filter_map(|n| String::from_utf8(n.clone()).ok()).collect()).unwrap_or_default();
        Some(PeerInfo { sk: c.sk, loc: c.loc?, addrs: card_addrs(&c), hk: c.hk, relays, nostr })
    }
}

/// The volunteers we use changed: every contact gets our fresh card (the KeyUpdate a new contact
/// gets, SPEC 6), so a volunteer that people on another network can reach spreads along the
/// contacts, never through a public list a censor could read (SPEC 15.9). At most every
/// `CARD_REFRESH_MS`, and never for an empty list.
fn refresh_card_if_boot_changed(inner: &Inner, force: bool) {
    let boot = inner.boot();
    let relays = {
        let rows = inner.state.lock().expect("state").store.db().gas_relays().unwrap_or_default();
        let health = inner.gas.lock().expect("gas").as_ref().map(|g| g.health());
        gas::card_relays(&rows, health.as_ref())
    };
    let nostr: Vec<Vec<u8>> = inner.state.lock().expect("state").identity.nostr().to_vec();
    if boot.is_empty() && relays.is_empty() && nostr.is_empty() {
        return;
    }
    let addrs = inner.advertised();
    let mut sorted = boot.clone();
    sorted.sort();
    let mut digest_in = sorted.concat();
    digest_in.extend_from_slice(b"|relays|");
    digest_in.extend(relays.concat());
    if !nostr.is_empty() {
        digest_in.extend_from_slice(b"|nostr|");
        digest_in.extend(nostr.join(&b","[..]));
    }
    let digest = Sha256::digest(digest_in).to_vec();
    let now = now_ms();
    let mut guard = inner.state.lock().expect("state");
    let State { store, identity } = &mut *guard;
    let last = store.db().meta(META_BOOT_SENT).ok().flatten();
    if last.as_deref() == Some(&digest[..]) {
        return;
    }
    let last_ms = store.db().meta(META_BOOT_SENT_MS).ok().flatten().and_then(|b| <[u8; 8]>::try_from(b.as_slice()).ok()).map(u64::from_le_bytes).unwrap_or(0);
    if !force && now.saturating_sub(last_ms) < CARD_REFRESH_MS {
        return;
    }
    identity.set_relays(relays);
    let sent = (|| {
        let card = identity.session_card(addrs.iter().map(addr::encode).collect(), boot, CARD_TTL_S)?.to_bytes();
        let update = Envelope::key_update(identity.loc(), card);
        let saved = identity.to_saved();
        let contacts = store.db().contacts()?;
        store.transaction(|db| {
            db.save_identity(&saved)?;
            for c in contacts.iter().filter(|c| !c.blocked) {
                enqueue(db, identity, c, &update, false)?;
            }
            db.set_meta(META_BOOT_SENT, &digest)?;
            db.set_meta(META_BOOT_SENT_MS, &now.to_le_bytes())?;
            Ok(contacts.len())
        })
    })();
    drop(guard);
    if let Ok(n) = sent {
        log::info!("node: volunteers changed, fresh card queued for {n} contacts");
        inner.waker.wake();
    }
}

fn deliver_loop(inner: Arc<Inner>) {
    let mut boot_checked = 0u64;
    let mut feeds_synced = now_ms();
    while !inner.stop.load(Ordering::SeqCst) {
        if inner.gas_dirty.load(Ordering::SeqCst) || now_ms().saturating_sub(boot_checked) > BOOT_CHECK_MS {
            boot_checked = now_ms();
            refresh_card_if_boot_changed(&inner, inner.gas_dirty.swap(false, Ordering::SeqCst));
        }
        // each peer still gets the catch-up every SYNC_EVERY_MS; the short tick only lets a new
        // contact (or one that just came back) get it at once
        if now_ms().saturating_sub(feeds_synced) > feeds::SYNC_TICK_MS {
            feeds_synced = now_ms();
            inner.feeds_sync(false);
            inner.files_tick();
            inner.feed_relay_tick();
        }
        let transports: Vec<Arc<dyn Transport>> = inner.transports.lock().expect("transport").clone();
        if transports.is_empty() {
            inner.waker.wait(Duration::from_secs(1));
            continue;
        }
        let now = now_ms();
        let (due, next) = {
            let st = inner.state.lock().expect("state");
            let db = st.store.db();
            (db.due(now, BATCH * 4).unwrap_or_default(), db.next_due_ms().ok().flatten())
        };
        // frames to a contact already on their way wait for that delivery to end, but a message
        // passes anything that is not a message, once the contact surely holds our card (`may_go`)
        let busy: HashMap<UserId, u8> = inner.delivering.lock().expect("delivering").clone();
        let due: Vec<OutRow> = {
            let st = inner.state.lock().expect("state");
            let db = st.store.db();
            due.into_iter()
                .filter(|r| {
                    let held = busy.get(&r.peer).copied().unwrap_or(0);
                    let passing = held & LANE_OTHER != 0 && r.msg_id.is_some();
                    may_go(r, held, passing && db.knows_us(&r.peer).unwrap_or(false))
                })
                .collect()
        };
        if due.is_empty() {
            // no message waiting: file chunks may go (SPEC 12, text first)
            if inner.deliver_blobs(&transports, &inner.network_label()) {
                continue;
            }
            let blob_next = inner.state.lock().expect("state").store.db().blob_out_next_ms().ok().flatten();
            let next = match (next, blob_next) {
                (Some(a), Some(b)) => Some(a.min(b)),
                (a, b) => a.or(b),
            };
            let wait = next.map(|n| n.saturating_sub(now)).unwrap_or(1000).clamp(20, 1000);
            inner.waker.wait(Duration::from_millis(wait));
            continue;
        }
        let network = inner.network_label();
        let mut started = false;
        for (peer, rows) in queue::group_by_peer(due) {
            if inner.stop.load(Ordering::SeqCst) {
                return;
            }
            let lanes = lanes_of(&rows);
            if !inner.begin_delivery(peer, lanes) {
                break;
            }
            started = true;
            let (i, t, net) = (inner.clone(), transports.clone(), network.clone());
            let spawned = std::thread::Builder::new().name("mlmchat-deliver-peer".into()).spawn(move || {
                deliver_to(&i, &t, &net, peer, &rows);
                i.end_delivery(peer, lanes);
            });
            if spawned.is_err() {
                inner.end_delivery(peer, lanes);
            }
        }
        if !started {
            // every contact with frames due is being delivered to, or as many as at once: until one ends
            inner.waker.wait(Duration::from_millis(250));
        }
    }
}

/// Contacts delivered to at the same time: a slow or stalled route to one never holds up the
/// others (before, contacts went one after another, and one waiting mailbox held everyone).
const PARALLEL_PEERS: usize = 8;

/// A delivery's lanes (`Inner::delivering`). A one-to-one message on its way holds every frame to
/// its contact behind it: an edit or a delete of it must not arrive first. Anything else on its
/// way (a receipt, a card, a group's frame, file chunks) holds only frames that are not messages,
/// so a reply never waits behind the «delivered» or «read» of the message it answers, which a
/// group does not send (Ehsan, 28 Oct 2026: «کندی چت شخصی نسبت به گروه»).
pub(crate) const LANE_MESSAGE: u8 = 1;
pub(crate) const LANE_OTHER: u8 = 2;

/// The lanes a delivery of these frames takes.
fn lanes_of(rows: &[OutRow]) -> u8 {
    rows.iter().fold(0, |l, r| l | if r.msg_id.is_some() { LANE_MESSAGE } else { LANE_OTHER })
}

/// A frame due now may start beside what is on its way to its contact (`busy`, its lanes). A
/// message passes what is not a message only when the contact surely holds our card (`known`,
/// `Db::knows_us`): to a new contact, what is on its way may be that very card, and a message
/// that came first would be dropped there as a stranger's (28 Oct 2026, `tests/feed.rs`).
fn may_go(row: &OutRow, busy: u8, known: bool) -> bool {
    busy & LANE_MESSAGE == 0 && (busy & LANE_OTHER == 0 || row.msg_id.is_some() && known)
}

impl Inner {
    /// Mark a delivery to `peer` on `lanes` as begun. False when one to this contact on one of
    /// them already is, or `PARALLEL_PEERS` contacts are being delivered to (a contact's second
    /// lane does not count again).
    fn begin_delivery(&self, peer: UserId, lanes: u8) -> bool {
        let mut d = self.delivering.lock().expect("delivering");
        let held = d.get(&peer).copied().unwrap_or(0);
        if held & lanes != 0 || held == 0 && d.len() >= PARALLEL_PEERS {
            return false;
        }
        d.insert(peer, held | lanes);
        true
    }

    fn end_delivery(&self, peer: UserId, lanes: u8) {
        {
            let mut d = self.delivering.lock().expect("delivering");
            if let Some(held) = d.get_mut(&peer) {
                *held &= !lanes;
                if *held == 0 {
                    d.remove(&peer);
                }
            }
        }
        self.waker.wake();
    }

    /// The contact is «reachable» now ([`Node::contact_status`]).
    fn online(&self, peer: &UserId) -> bool {
        let st = self.state.lock().expect("state");
        let db = st.store.db();
        let at = db.reach(peer).ok().flatten().map_or(0, |(at, _)| at);
        is_online(at, db.unreach(peer).unwrap_or(0), now_ms())
    }
}

/// One contact's due frames: through the routes (`try_routes`), then what each frame became.
fn deliver_to(inner: &Arc<Inner>, transports: &[Arc<dyn Transport>], network: &str, peer: UserId, rows: &[OutRow]) {
    let result = match inner.peer_info(&peer) {
        Some(info) => {
            let frames: Vec<Vec<u8>> = rows.iter().map(|r| r.frame.clone()).collect();
            try_routes(inner, transports, network, &info, &frames, None)
        }
        None => Err(TransportError::NoRoute),
    };
    // a frame left in a mailbox says nothing about the contact being there now (SPEC 0, honesty)
    if let Some(sent) = result.as_ref().ok().filter(|s| mailbox_rank(s.via) == 0) {
        inner.reached(peer, sent.via);
    }
    let mut events = Vec::new();
    let saved = {
        let mut st = inner.state.lock().expect("state");
        let now = now_ms();
        st.store.transaction(|db| {
            match &result {
                Ok(sent) => {
                    for (row, v) in rows.iter().zip(&sent.verdicts) {
                        db.remove_out(&row.frame_id)?;
                        if let Some(m) = row.msg_id {
                            match v {
                                Verdict::Dropped => {
                                    db.set_state(&m, MsgState::Failed)?;
                                    events.push(Event::State { peer, id: m, state: MsgState::Failed });
                                }
                                _ => {
                                    for id in db.advance_outgoing(&peer, &[m], MsgState::Sent)? {
                                        events.push(Event::State { peer, id, state: MsgState::Sent });
                                    }
                                }
                            }
                        }
                    }
                    for row in rows.iter().skip(sent.verdicts.len()) {
                        if db.out_once(&row.frame_id)? {
                            db.remove_out(&row.frame_id)?;
                        } else {
                            db.retry_later(&row.frame_id, queue::next_try_ms(row.attempts, now), "connect")?;
                        }
                    }
                }
                Err(e) => {
                    // a frame sent once only (a group read receipt) goes; the rest wait
                    let mut kept = 0;
                    for row in rows {
                        if db.out_once(&row.frame_id)? {
                            db.remove_out(&row.frame_id)?;
                        } else {
                            db.retry_later(&row.frame_id, queue::next_try_ms(row.attempts, now), e.code())?;
                            kept += 1;
                        }
                    }
                    db.set_unreach(&peer, now)?;
                    if kept > 0 {
                        events.push(Event::Queued { peer, reason: e.code() });
                    }
                }
            }
            Ok(())
        })
    };
    if saved.is_err() {
        // the database is not writable right now: do not spin on the same rows
        log::warn!("node: could not record a delivery result");
        std::thread::sleep(Duration::from_secs(1));
        return;
    }
    for e in events {
        inner.emit(e);
    }
}

/// A route that has not answered in this long gets the next route started beside it.
const RACE_AFTER: Duration = Duration::from_millis(2500);
/// No delivery waits longer than this for its routes (each has its own timeouts well within).
const ROUTES_MAX: Duration = Duration::from_secs(300);

/// The routes to try for a contact, best first: the order this network has proven (SPEC 15.5).
fn route_list(inner: &Inner, transports: &[Arc<dyn Transport>], network: &str, peer: &UserId) -> Vec<usize> {
    let stats = inner.state.lock().expect("state").store.db().route_stats(network, peer).unwrap_or_default();
    let names: Vec<(&'static str, bool)> = transports.iter().map(|t| (t.name(), t.has_fresh_route(peer))).collect();
    route_order(&names, &stats, now_ms())
}

/// What one route did: its name, the result, how long it took.
type RouteDone = (&'static str, std::result::Result<crate::transport::Sent, TransportError>, u64);

/// Try the transports in the order this network has proven for this contact (`first`, if given,
/// ahead of it), and remember how each did (SPEC 15.5). The first route starts at once; one that
/// fails gets the next started at once, and one that has not answered within `RACE_AFTER` gets
/// the next started beside it. The first to deliver wins, so a route that closes or stalls
/// never holds the frames up (Ehsan, 26 Oct 2026: «هرکدوم از راه ها هم بسته شد … همون لحظه
/// راه جایگزین در جریانه»); a frame that goes twice is kept once by the receiver. `NoRoute`
/// (nothing to try: no address, no presence record) is not held against a route.
fn try_routes(inner: &Arc<Inner>, transports: &[Arc<dyn Transport>], network: &str, info: &PeerInfo, frames: &[Vec<u8>], first: Option<usize>) -> std::result::Result<crate::transport::Sent, TransportError> {
    let mut order = route_list(inner, transports, network, &info.sk);
    if let Some(p) = first.and_then(|f| order.iter().position(|i| *i == f)) {
        let f = order.remove(p);
        order.insert(0, f);
    }
    // A contact not reachable now, with no fresh route, most likely has their phone off, and then
    // a mailbox is the only way: the mailboxes come right after the first route and the first of
    // them starts beside it (one with nothing to try, NoRoute, hands over to the next at once),
    // not after every route to the phone has stalled, so the message is out of this phone (one
    // check, SPEC 15.6) in seconds. Ehsan, 27 Oct 2026: «وقتی کاربر مقابل آفلاینه … بهتره یک تیک بخوره».
    let mut at_once = 1;
    let to_phone = |i: usize| mailbox_rank(transports[i].name()) == 0;
    if first.is_none() && order.first().is_some_and(|&i| to_phone(i) && !transports[i].has_fresh_route(&info.sk)) && !inner.online(&info.sk) {
        let (boxes, phones): (Vec<usize>, Vec<usize>) = order.iter().copied().partition(|&i| !to_phone(i));
        if !boxes.is_empty() {
            order = phones[..1].iter().chain(&boxes).chain(&phones[1..]).copied().collect();
            at_once = 2;
        }
    }
    let frames = Arc::new(frames.to_vec());
    let (tx, rx) = channel::<RouteDone>();
    let launch = |next: &mut usize| {
        let Some(&i) = order.get(*next) else { return false };
        *next += 1;
        let (t, info, frames, tx, inner, network) = (transports[i].clone(), info.clone(), frames.clone(), tx.clone(), inner.clone(), network.to_string());
        std::thread::Builder::new()
            .name("mlmchat-route".into())
            .spawn(move || {
                let t0 = std::time::Instant::now();
                let r = t.send(&info, &frames);
                let ms = t0.elapsed().as_millis() as u64;
                if !matches!(r, Err(TransportError::NoRoute)) {
                    let _ = inner.state.lock().expect("state").store.db().route_result(&network, &info.sk, t.name(), r.is_ok(), ms, now_ms());
                }
                let _ = tx.send((t.name(), r, ms));
            })
            .is_ok()
    };
    let mut next = 0;
    let mut running = 0;
    let mut best = TransportError::NoRoute;
    let end = std::time::Instant::now() + ROUTES_MAX;
    let mut last_start = std::time::Instant::now();
    for _ in 0..at_once {
        if launch(&mut next) {
            running += 1;
        }
    }
    while running > 0 && std::time::Instant::now() < end {
        if inner.stop.load(Ordering::SeqCst) {
            return Err(TransportError::Stopped);
        }
        match rx.recv_timeout(Duration::from_millis(250)) {
            Ok((name, r, ms)) => {
                running -= 1;
                match r {
                    Ok(sent) => {
                        log::info!("node: delivered via={} in {} ms", sent.via, ms);
                        return Ok(sent);
                    }
                    Err(e) => {
                        log::debug!("node: route {} failed ({}) in {} ms", name, e.code(), ms);
                        if rank(&e) > rank(&best) {
                            best = e;
                        }
                        // the next route at once, beside any still trying
                        if launch(&mut next) {
                            running += 1;
                            last_start = std::time::Instant::now();
                        }
                    }
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                if last_start.elapsed() >= RACE_AFTER && launch(&mut next) {
                    running += 1;
                    last_start = std::time::Instant::now();
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }
    if running > 0 && matches!(best, TransportError::NoRoute) {
        best = TransportError::Timeout;
    }
    Err(best)
}

/// What the transport sees of the node. Weak, so a stopped node is freed.
/// What «add nearby» uses of the node: our card, adding theirs, Bluetooth links.
struct NodePair(Weak<Inner>);

impl PairHost for NodePair {
    fn my_card(&self) -> Option<String> {
        self.0.upgrade()?.card_link().ok()
    }

    fn add_card(&self, link: &str) -> bool {
        self.0.upgrade().is_some_and(|i| i.add_contact(link).is_ok())
    }

    fn ble_link(&self, addr: &str, read_timeout: Duration) -> Option<Box<dyn Pipe>> {
        let b = self.0.upgrade()?.ble.lock().expect("ble").clone()?;
        b.link(addr, read_timeout)
    }
}

struct NodeHooks(Weak<Inner>);

impl Hooks for NodeHooks {
    fn me(&self) -> (UserId, [u8; 32]) {
        self.0.upgrade().map(|i| *i.keys.read().expect("keys")).unwrap_or(([0; 32], [0; 32]))
    }

    fn allows(&self, ip: IpAddr) -> bool {
        self.0.upgrade().is_none_or(|i| i.scope.read().expect("scope").as_ref().is_none_or(|s| s.allows(ip)))
    }

    fn network_label(&self) -> String {
        self.0.upgrade().map(|i| i.network_label()).unwrap_or_default()
    }

    fn on_mailbox(&self, peer: UserId, inbound: bool) {
        if let Some(i) = self.0.upgrade() {
            let _ = i.state.lock().expect("state").store.db().gas_peer_seen(&peer, inbound, now_ms());
        }
    }

    fn open_sealed(&self, bytes: &[u8]) -> Option<frame::Opened> {
        let i = self.0.upgrade()?;
        let st = i.state.lock().expect("state");
        frame::open(bytes, &st.identity.seal_secret()).ok()
    }

    fn feed_subscription(&self) -> (Vec<Vec<u8>>, Vec<String>) {
        let Some(i) = self.0.upgrade() else { return (Vec::new(), Vec::new()) };
        (i.feed_list().iter().map(|d| d.feed_id.clone()).collect(), i.file_relays())
    }

    fn on_feed_items(&self, items: Vec<crate::feed::SignedFeedItem>) {
        if let Some(i) = self.0.upgrade() {
            i.take_relay_items(&items);
        }
    }

    fn relay_add(&self, served: u64, bytes: u64) -> (u64, u64) {
        let Some(i) = self.0.upgrade() else { return (served, bytes) };
        let st = i.state.lock().expect("state");
        let db = st.store.db();
        let get = |k: &str| db.meta(k).ok().flatten().and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(u64::from_le_bytes).unwrap_or(0);
        let (s, b) = (get(META_RELAY_SERVED) + served, get(META_RELAY_BYTES) + bytes);
        if served > 0 || bytes > 0 {
            let _ = db.set_meta(META_RELAY_SERVED, &s.to_le_bytes());
            let _ = db.set_meta(META_RELAY_BYTES, &b.to_le_bytes());
        }
        (s, b)
    }

    fn sign(&self, message: &[u8]) -> [u8; 64] {
        self.0.upgrade().map(|i| i.state.lock().expect("state").identity.sign(message)).unwrap_or([0; 64])
    }

    fn accept_peer(&self, sk: &UserId) -> bool {
        let Some(i) = self.0.upgrade() else { return false };
        let st = i.state.lock().expect("state");
        match st.store.db().contact(sk) {
            Ok(Some(c)) => !c.blocked,
            Ok(None) => i.opts.accept_requests,
            Err(_) => false,
        }
    }

    fn contact_locs(&self) -> Vec<(UserId, [u8; 32])> {
        let Some(i) = self.0.upgrade() else { return Vec::new() };
        let st = i.state.lock().expect("state");
        st.store.db().contacts().unwrap_or_default().into_iter().filter(|c| !c.blocked).filter_map(|c| c.loc.map(|l| (c.sk, l))).collect()
    }

    fn on_frame(&self, from: UserId, frame: Vec<u8>) -> Verdict {
        match self.0.upgrade() {
            Some(i) => i.on_frame(from, frame),
            None => Verdict::Retry,
        }
    }

    fn on_seen(&self, sk: UserId, via: Via) {
        let Some(i) = self.0.upgrade() else { return };
        let woke = i.state.lock().expect("state").store.db().wake_peer(&sk).unwrap_or(0);
        i.emit(Event::Seen { peer: sk, via });
        if woke > 0 {
            i.waker.wake();
        }
    }

    fn on_reached(&self, sk: UserId, via: Via) {
        let Some(i) = self.0.upgrade() else { return };
        i.reached(sk, via);
        let woke = i.state.lock().expect("state").store.db().wake_peer(&sk).unwrap_or(0);
        if woke > 0 {
            i.waker.wake();
        }
    }

    fn volunteers(&self) -> Vec<Vec<u8>> {
        let Some(i) = self.0.upgrade() else { return Vec::new() };
        let (contacts, remembered) = {
            let st = i.state.lock().expect("state");
            let db = st.store.db();
            (db.contacts().unwrap_or_default(), db.volunteers(now_ms(), VOLUNTEER_KEEP_MS, VOLUNTEER_MAX).unwrap_or_default())
        };
        let mut out: Vec<Vec<u8>> = remembered;
        for c in contacts.iter().filter(|c| !c.blocked) {
            if let Ok(card) = Card::from_bytes(&c.card) {
                for b in card.boot {
                    if !out.contains(&b) {
                        out.push(b);
                    }
                }
            }
        }
        out
    }

    fn remember_volunteers(&self, addrs: Vec<Vec<u8>>) {
        let Some(i) = self.0.upgrade() else { return };
        let st = i.state.lock().expect("state");
        let now = now_ms();
        for a in addrs.iter().take(VOLUNTEER_MAX) {
            let _ = st.store.db().remember_volunteer(a, now);
        }
    }

    fn ladder_stats(&self) -> (String, Vec<RouteStat>) {
        let Some(i) = self.0.upgrade() else { return (String::new(), Vec::new()) };
        let net = i.network_label();
        let stats = i.state.lock().expect("state").store.db().ladder_stats(&net).unwrap_or_default();
        (net, stats)
    }

    fn ladder_result(&self, net: &str, keys: &[String], ok: bool, ms: u64) {
        let Some(i) = self.0.upgrade() else { return };
        let st = i.state.lock().expect("state");
        let now = now_ms();
        for k in keys {
            let _ = st.store.db().ladder_result(net, k, ok, ms, now);
        }
    }

    fn announce_targets(&self, max: usize) -> Vec<(UserId, [u8; 32])> {
        let Some(i) = self.0.upgrade() else { return Vec::new() };
        let st = i.state.lock().expect("state");
        let db = st.store.db();
        let contacts = db.contacts().unwrap_or_default();
        let recent = db.recent_peers(max).unwrap_or_default();
        recent.iter().filter_map(|sk| contacts.iter().find(|c| c.sk == *sk && !c.blocked)).filter_map(|c| c.loc.map(|l| (c.sk, l))).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn st(via: &str, streak: u32, last_ok_ms: u64, last_try_ms: u64) -> RouteStat {
        RouteStat { via: via.into(), ok: 0, fail: streak, streak, last_ok_ms, last_try_ms, ms: 0 }
    }

    #[test]
    fn route_order_follows_what_this_network_proved() {
        let names = [("direct", false), ("p2p", false)];
        // nothing known: the configured order
        assert_eq!(route_order(&names, &[], 1_000), vec![0, 1]);
        // p2p worked last here, direct did not: p2p first
        assert_eq!(route_order(&names, &[st("direct", 1, 0, 900), st("p2p", 0, 950, 950)], 1_000), vec![1, 0]);
        // direct failed 3 times in a row, recently, and p2p works: direct is left out
        let s = [st("direct", 3, 0, 900), st("p2p", 0, 950, 950)];
        assert_eq!(route_order(&names, &s, 1_000), vec![1]);
        // ... but tried again once SKIP_MS has passed
        assert_eq!(route_order(&names, &s, 900 + SKIP_MS + 1), vec![1, 0]);
        // a fresh LAN sighting beats everything
        assert_eq!(route_order(&[("direct", true), ("p2p", false)], &s, 1_000), vec![0, 1]);
        // when everything is on a streak, nothing is dropped
        assert_eq!(route_order(&names, &[st("direct", 5, 0, 990), st("p2p", 4, 0, 990)], 1_000), vec![0, 1]);
        // mailboxes after the others, Nostr before the Google mailbox even when that worked later
        let names = [("gas", false), ("nostr", false), ("direct", false)];
        assert_eq!(route_order(&names, &[], 1_000), vec![2, 1, 0]);
        assert_eq!(route_order(&names, &[st("gas", 0, 990, 990), st("nostr", 0, 950, 950)], 1_000), vec![2, 1, 0]);
        // a mailbox on a streak is never set aside: to a phone that is off it is the only way
        assert_eq!(route_order(&names, &[st("direct", 0, 990, 990), st("nostr", 3, 0, 980), st("gas", 4, 0, 985)], 1_000), vec![2, 1, 0]);
    }

    #[test]
    fn a_message_passes_a_receipt_on_its_way_and_nothing_passes_a_message() {
        let row = |message: bool| OutRow { frame_id: [0; 16], peer: [1; 32], msg_id: message.then_some([2; 16]), frame: Vec::new(), attempts: 0, next_ms: 0, last_error: None };
        // nothing on its way: anything goes
        assert!(may_go(&row(true), 0, false) && may_go(&row(false), 0, false));
        // a receipt (or a card, a group frame, file chunks) on its way: a message passes it, the rest wait
        assert!(may_go(&row(true), LANE_OTHER, true) && !may_go(&row(false), LANE_OTHER, true));
        // but not to a contact who may not hold our card yet: what is on its way may be the card
        assert!(!may_go(&row(true), LANE_OTHER, false));
        // a message on its way: everything waits, so an edit or a delete never comes before it
        assert!(!may_go(&row(true), LANE_MESSAGE, true) && !may_go(&row(false), LANE_MESSAGE, true));
        assert_eq!(lanes_of(&[row(true), row(false)]), LANE_MESSAGE | LANE_OTHER);
        assert_eq!(lanes_of(&[row(false)]), LANE_OTHER);
    }

    #[test]
    fn online_is_a_recent_session_with_no_failure_since() {
        assert!(!is_online(0, 0, 1_000));
        assert!(is_online(1_000, 0, 1_000 + ONLINE_MS - 1));
        assert!(!is_online(1_000, 0, 1_000 + ONLINE_MS));
        assert!(!is_online(1_000, 1_500, 2_000));
        assert!(is_online(1_500, 1_500, 2_000));
    }

    #[test]
    fn auto_network_is_coarse_and_private() {
        let a = auto_network(&["192.168.8.20:1".parse().unwrap(), "[2a02:4540:7008::1]:1".parse().unwrap()]);
        let b = auto_network(&["192.168.8.77:5".parse().unwrap(), "[2a02:4540:9999::2]:9".parse().unwrap()]);
        assert_eq!(a, b);
        assert!(a.starts_with("auto:") && !a.contains("192"));
        assert_ne!(a, auto_network(&["22.135.1.2:1".parse().unwrap()]));
    }
}
