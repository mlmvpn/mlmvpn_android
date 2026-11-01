//! The encrypted on-device database (SPEC 4, T18): SQLCipher, keyed with a raw 32-byte key that
//! the app keeps in Android Keystore (or the OS key store elsewhere). It holds the identity,
//! contacts, Olm sessions, messages, the outbox of already-encrypted frames, and seen envelope
//! ids. Temporary tables stay in memory (build.rs), deleted rows are overwritten.

use crate::{Result, UserId};
use rusqlite::{params, Connection, OptionalExtension, Row};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use zeroize::Zeroizing;

/// Goes up on every write to `feed_items`, so a feed's state built from them can be kept until
/// they change (`Node::feed_state`). One counter for the process: a write to any node's store
/// only makes the others build theirs once more.
static FEED_GEN: AtomicU64 = AtomicU64::new(1);

pub fn feed_gen() -> u64 {
    FEED_GEN.load(Ordering::SeqCst)
}

pub const SCHEMA_VERSION: i64 = 1;
/// Envelope ids are remembered this long to drop duplicates.
pub const SEEN_KEEP_MS: u64 = 30 * 24 * 3600 * 1000;
/// Frames that are no message of the user's wait this long for a contact (`prune_outbox_step`).
pub const OUTBOX_KEEP_MS: u64 = 3 * 24 * 3600 * 1000;
/// meta key: the highest message seq ever given (see Db::next_seq).
const SEQ_FLOOR: &str = "seq_floor";

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS contacts (
  sk BLOB PRIMARY KEY, ik BLOB NOT NULL, hk BLOB NOT NULL, otk BLOB NOT NULL, loc BLOB,
  name TEXT NOT NULL, card BLOB NOT NULL, added_ms INTEGER NOT NULL,
  by_them INTEGER NOT NULL DEFAULT 0, blocked INTEGER NOT NULL DEFAULT 0, verified INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY, peer BLOB NOT NULL, pickle BLOB NOT NULL, by_me INTEGER NOT NULL,
  created_ms INTEGER NOT NULL, used_ms INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS sessions_peer ON sessions(peer);
CREATE TABLE IF NOT EXISTS messages (
  id BLOB PRIMARY KEY, peer BLOB NOT NULL, outgoing INTEGER NOT NULL, sent_ms INTEGER NOT NULL,
  at_ms INTEGER NOT NULL, kind TEXT NOT NULL, envelope BLOB NOT NULL, state INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS messages_peer ON messages(peer, at_ms);
CREATE INDEX IF NOT EXISTS messages_peer_seq ON messages(peer);
CREATE TABLE IF NOT EXISTS outbox (
  frame_id BLOB PRIMARY KEY, peer BLOB NOT NULL, msg_id BLOB, frame BLOB NOT NULL,
  created_ms INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_ms INTEGER NOT NULL DEFAULT 0,
  last_error TEXT);
CREATE INDEX IF NOT EXISTS outbox_due ON outbox(next_ms);
CREATE TABLE IF NOT EXISTS seen (id BLOB PRIMARY KEY, at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS edits (id BLOB PRIMARY KEY, at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS routes (
  net TEXT NOT NULL, peer BLOB NOT NULL, via TEXT NOT NULL, ok INTEGER NOT NULL DEFAULT 0,
  fail INTEGER NOT NULL DEFAULT 0, streak INTEGER NOT NULL DEFAULT 0, last_ok_ms INTEGER NOT NULL DEFAULT 0,
  last_try_ms INTEGER NOT NULL DEFAULT 0, ms INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (net, peer, via));
CREATE TABLE IF NOT EXISTS reach (peer BLOB PRIMARY KEY, at_ms INTEGER NOT NULL, via TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS got (id BLOB PRIMARY KEY, at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS unreach (peer BLOB PRIMARY KEY, at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS volunteers (addr BLOB PRIMARY KEY, seen_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS ladder (
  net TEXT NOT NULL, k TEXT NOT NULL, ok INTEGER NOT NULL DEFAULT 0, fail INTEGER NOT NULL DEFAULT 0,
  streak INTEGER NOT NULL DEFAULT 0, last_ok_ms INTEGER NOT NULL DEFAULT 0, last_try_ms INTEGER NOT NULL DEFAULT 0,
  ms INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (net, k));
CREATE TABLE IF NOT EXISTS groups (gid BLOB PRIMARY KEY, joined_ms INTEGER NOT NULL, left_ms INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS group_ops (
  id BLOB PRIMARY KEY, gid BLOB NOT NULL, body BLOB NOT NULL, sig BLOB NOT NULL, at_ms INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS group_ops_gid ON group_ops(gid);
CREATE TABLE IF NOT EXISTS group_messages (
  id BLOB PRIMARY KEY, gid BLOB NOT NULL, sender BLOB NOT NULL, outgoing INTEGER NOT NULL, sent_ms INTEGER NOT NULL,
  at_ms INTEGER NOT NULL, kind TEXT NOT NULL, envelope BLOB NOT NULL, state INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS group_messages_gid ON group_messages(gid, at_ms);
CREATE INDEX IF NOT EXISTS group_messages_gid_seq ON group_messages(gid);
CREATE INDEX IF NOT EXISTS group_messages_sender ON group_messages(gid, sender, sent_ms);
CREATE TABLE IF NOT EXISTS megolm_out (gid BLOB PRIMARY KEY, pickle BLOB NOT NULL, created_ms INTEGER NOT NULL, sent INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS megolm_in (sid TEXT PRIMARY KEY, gid BLOB NOT NULL, sender BLOB NOT NULL, pickle BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS group_peers (sk BLOB PRIMARY KEY);
CREATE TABLE IF NOT EXISTS blobs (
  id BLOB PRIMARY KEY, size INTEGER NOT NULL, chunks INTEGER NOT NULL, have BLOB NOT NULL,
  complete INTEGER NOT NULL DEFAULT 0, source BLOB, created_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS media_keys (short INTEGER PRIMARY KEY, id BLOB NOT NULL, key BLOB NOT NULL, size INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS blob_out (
  peer BLOB NOT NULL, id BLOB NOT NULL, idx INTEGER NOT NULL, next_ms INTEGER NOT NULL DEFAULT 0,
  attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (peer, id, idx));
CREATE TABLE IF NOT EXISTS profiles (
  sk BLOB PRIMARY KEY, version INTEGER NOT NULL, blob BLOB, key BLOB, size INTEGER NOT NULL DEFAULT 0,
  thumb BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS invite_secrets (invite_id BLOB PRIMARY KEY, gid BLOB NOT NULL, secret BLOB NOT NULL, link TEXT NOT NULL, created_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS join_requests (
  gid BLOB NOT NULL, sk BLOB NOT NULL, invite_id BLOB NOT NULL, card BLOB NOT NULL, at_ms INTEGER NOT NULL, PRIMARY KEY (gid, sk));
CREATE TABLE IF NOT EXISTS joining (gid BLOB PRIMARY KEY, inviter BLOB NOT NULL, name TEXT NOT NULL, broadcast INTEGER NOT NULL, at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS feed_items (
  id BLOB PRIMARY KEY, feed_id BLOB NOT NULL, ts_ms INTEGER NOT NULL, admins INTEGER NOT NULL, body BLOB NOT NULL, sig BLOB NOT NULL, at_ms INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS feed_items_feed ON feed_items(feed_id, ts_ms);
CREATE TABLE IF NOT EXISTS reactions (
  scope BLOB NOT NULL, target BLOB NOT NULL, reactor BLOB NOT NULL, emoji TEXT NOT NULL, ts_ms INTEGER NOT NULL,
  PRIMARY KEY (scope, target, reactor));
CREATE TABLE IF NOT EXISTS poll_votes (
  scope BLOB NOT NULL, poll BLOB NOT NULL, voter BLOB NOT NULL, choices BLOB NOT NULL, ts_ms INTEGER NOT NULL,
  PRIMARY KEY (scope, poll, voter));
CREATE TABLE IF NOT EXISTS gas_relays (
  rid TEXT PRIMARY KEY, id TEXT NOT NULL, use_key BLOB NOT NULL, owner_key BLOB, name TEXT NOT NULL,
  added_ms INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, announce INTEGER NOT NULL DEFAULT 1,
  pos INTEGER NOT NULL DEFAULT 0, mine INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS gas_workers (use_key BLOB PRIMARY KEY, worker TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS gas_stats (
  rid TEXT NOT NULL, day INTEGER NOT NULL, req INTEGER NOT NULL DEFAULT 0, puts INTEGER NOT NULL DEFAULT 0,
  takes INTEGER NOT NULL DEFAULT 0, items_out INTEGER NOT NULL DEFAULT 0, items_in INTEGER NOT NULL DEFAULT 0,
  bytes_out INTEGER NOT NULL DEFAULT 0, bytes_in INTEGER NOT NULL DEFAULT 0, errors INTEGER NOT NULL DEFAULT 0,
  ms_sum INTEGER NOT NULL DEFAULT 0, ms_n INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (rid, day));
CREATE TABLE IF NOT EXISTS gas_peers (peer BLOB PRIMARY KEY, in_ms INTEGER NOT NULL DEFAULT 0, out_ms INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS gas_public (
  relay BLOB PRIMARY KEY, owner BLOB NOT NULL, body BLOB NOT NULL, sig BLOB NOT NULL, ts_ms INTEGER NOT NULL,
  until_ms INTEGER NOT NULL, withdrawn INTEGER NOT NULL, avoid_until INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS gas_borrowed (rid TEXT PRIMARY KEY, relay BLOB NOT NULL, since_ms INTEGER NOT NULL, checked_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS gas_parts (
  sender BLOB NOT NULL, fid BLOB NOT NULL, idx INTEGER NOT NULL, count INTEGER NOT NULL, data BLOB NOT NULL,
  at_ms INTEGER NOT NULL, PRIMARY KEY (sender, fid, idx));
CREATE TABLE IF NOT EXISTS gas_pending (
  id BLOB PRIMARY KEY, peer BLOB NOT NULL, loc BLOB NOT NULL, hk BLOB NOT NULL, relay BLOB NOT NULL, frame BLOB NOT NULL,
  first_ms INTEGER NOT NULL, put_ms INTEGER NOT NULL, next_ms INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0,
  day INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS held (
  fid BLOB PRIMARY KEY, sender BLOB NOT NULL, frame BLOB NOT NULL, at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS mirror_ids (id BLOB PRIMARY KEY, at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS group_owed (
  gid BLOB NOT NULL, peer BLOB NOT NULL, sid TEXT NOT NULL, key BLOB NOT NULL, idx INTEGER NOT NULL,
  at_ms INTEGER NOT NULL, PRIMARY KEY (gid, peer, sid));
CREATE TABLE IF NOT EXISTS presence (
  peer BLOB PRIMARY KEY, online_ms INTEGER NOT NULL DEFAULT 0, offline_ms INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS group_reads (
  id BLOB NOT NULL, reader BLOB NOT NULL, gid BLOB NOT NULL, at_ms INTEGER NOT NULL, PRIMARY KEY (id, reader));
CREATE TABLE IF NOT EXISTS outbox_once (frame_id BLOB PRIMARY KEY);
CREATE TABLE IF NOT EXISTS session_rx (id TEXT PRIMARY KEY, rx_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS feed_cards (sk BLOB PRIMARY KEY, card BLOB NOT NULL, ts_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS pins (peer BLOB NOT NULL, target BLOB NOT NULL, at_ms INTEGER NOT NULL, PRIMARY KEY (peer, target));
CREATE TABLE IF NOT EXISTS feed_photos (id BLOB PRIMARY KEY);
CREATE TABLE IF NOT EXISTS feed_files (
  id BLOB PRIMARY KEY, feed BLOB NOT NULL, at_ms INTEGER NOT NULL, put INTEGER NOT NULL DEFAULT 0,
  put_next_ms INTEGER NOT NULL DEFAULT 0, put_tries INTEGER NOT NULL DEFAULT 0, lacked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS dir_cards (g BLOB NOT NULL, pubkey TEXT NOT NULL, card TEXT NOT NULL, created_s INTEGER NOT NULL, PRIMARY KEY (g, pubkey));
CREATE TABLE IF NOT EXISTS dir_listed (g BLOB PRIMARY KEY, listed INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS dir_links (g BLOB PRIMARY KEY, link TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS dir_own_photos (g BLOB PRIMARY KEY, hash TEXT NOT NULL, jpeg BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS dir_photos (g BLOB NOT NULL, pubkey TEXT NOT NULL, hash TEXT NOT NULL, jpeg BLOB NOT NULL, PRIMARY KEY (g, pubkey));
CREATE TABLE IF NOT EXISTS peer_clients (sk BLOB PRIMARY KEY, app TEXT NOT NULL, code INTEGER NOT NULL, name TEXT NOT NULL, sent_ms INTEGER NOT NULL, at_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS feed_relay_out (id BLOB PRIMARY KEY, feed BLOB NOT NULL, body BLOB NOT NULL, ts_ms INTEGER NOT NULL, next_ms INTEGER NOT NULL DEFAULT 0, tries INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS feed_relay_ids (id BLOB PRIMARY KEY, at_ms INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS peer_clients_at ON peer_clients(at_ms);
";

/// The queues' indexes by contact, message and due time, built after the start and not at open
/// (`Db::build_late_indexes`, the node's trim thread). Without them a contact seen, the file chunks
/// due and the mailboxes' queue each read their table whole under the core's lock: 1.4 to 3 s a
/// time on the official chats' owner's S26, whose outbox is the biggest. Building them there takes
/// seconds once, and the app opens the database on its main thread (1.2.45, 1 Nov 2026).
const LATE_INDEXES: &str = "
CREATE INDEX IF NOT EXISTS outbox_peer ON outbox(peer, next_ms);
CREATE INDEX IF NOT EXISTS outbox_msg ON outbox(msg_id);
CREATE INDEX IF NOT EXISTS blob_out_due ON blob_out(next_ms);
CREATE INDEX IF NOT EXISTS gas_pending_due ON gas_pending(next_ms);
CREATE INDEX IF NOT EXISTS gas_parts_at ON gas_parts(at_ms);
";

/// The app someone writes from, as their newest envelope or feed item said it (SPEC 5 `Client`, T75).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientRow {
    pub sk: UserId,
    /// Empty: their newest came from a phone before draft 28.
    pub app: String,
    pub code: u32,
    pub name: String,
    /// Their clock on it, at most ours when it came.
    pub sent_ms: u64,
    /// Our clock when what is known of them last changed.
    pub at_ms: u64,
}

/// Feed items known to be in the official feeds' copy (read there, or left there) are remembered
/// this long, so none is left there twice.
pub const MIRROR_IDS_KEEP_MS: u64 = 60 * 24 * 3600 * 1000;

/// Frames held because they could not be used yet (SPEC 7.3: a member's first frame before the
/// event that makes them a member, a group message before its key): this long, and this many.
pub const HELD_KEEP_MS: u64 = 6 * 3600 * 1000;
pub const MAX_HELD: i64 = 512;
pub const MAX_HELD_PER_SENDER: i64 = 64;

mod gas;
pub use gas::{GasRelayRow, PublicRow};

/// How one route (a transport) did for one contact on one network (SPEC 15.5).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RouteStat {
    pub via: String,
    pub ok: u32,
    pub fail: u32,
    /// Failures in a row since the last success.
    pub streak: u32,
    pub last_ok_ms: u64,
    pub last_try_ms: u64,
    /// How long the last success took.
    pub ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contact {
    pub sk: UserId,
    pub ik: [u8; 32],
    pub hk: [u8; 32],
    /// The one-time or fallback key of the newest card, for a new outbound session.
    pub otk: [u8; 32],
    /// Their locator; `None` until it arrives (remote link, phase 3).
    pub loc: Option<[u8; 32]>,
    /// The name their card suggests.
    pub name: String,
    /// Their newest card, as signed bytes.
    pub card: Vec<u8>,
    pub added_ms: u64,
    /// They added us first (a "request" in the app).
    pub by_them: bool,
    pub blocked: bool,
    /// Security number compared.
    pub verified: bool,
}

/// A profile as this phone has it, ours or a contact's (SPEC 10). Only a newer `version`
/// replaces it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProfileRow {
    pub version: u64,
    /// The photo file: (id, key, sealed size); `None` when there is no photo.
    pub photo: Option<([u8; 32], [u8; 32], u64)>,
    /// The photo's tiny preview (at most 4 KiB), shown before the file is here.
    pub thumb: Vec<u8>,
}

/// Message state. Outgoing only moves forward: queued, sent to the device, delivered (their
/// app decrypted it), read. Nothing is shown as sent or delivered before it really is (SPEC 0).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum MsgState {
    Queued = 0,
    Sent = 1,
    Delivered = 2,
    Read = 3,
    /// The other device refused the frame for good.
    Failed = 9,
    Received = 10,
    /// Incoming, and we read it.
    Seen = 11,
}

impl MsgState {
    pub fn from_i64(v: i64) -> MsgState {
        match v {
            0 => MsgState::Queued,
            1 => MsgState::Sent,
            2 => MsgState::Delivered,
            3 => MsgState::Read,
            10 => MsgState::Received,
            11 => MsgState::Seen,
            _ => MsgState::Failed,
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            MsgState::Queued => "queued",
            MsgState::Sent => "sent",
            MsgState::Delivered => "delivered",
            MsgState::Read => "read",
            MsgState::Failed => "failed",
            MsgState::Received => "received",
            MsgState::Seen => "seen",
        }
    }
}

/// What a chat list row and a screen show of a group or channel besides its last message
/// ([`Db::group_marks`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct GroupMarks {
    /// Incoming messages after the last one read here.
    pub unread: u32,
    /// Our newest message a member has read (SPEC 7.6; its seq, 0: none).
    pub read_out: i64,
    /// Messages in it.
    pub total: u32,
}

/// What a chat list row shows of a one-to-one chat besides its last message ([`Db::chat_marks`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ChatMarks {
    /// Incoming messages not read yet.
    pub unread: u32,
    /// The newest incoming message read here (its seq; 0: none).
    pub read_in: i64,
    /// The newest outgoing message the contact has read (its seq; 0: none).
    pub read_out: i64,
}

#[derive(Debug, Clone)]
pub struct MessageRow {
    pub id: [u8; 16],
    pub peer: UserId,
    pub outgoing: bool,
    pub sent_ms: u64,
    /// When this device stored it.
    pub at_ms: u64,
    pub kind: String,
    /// The envelope, as protobuf bytes (inside the encrypted database only).
    pub envelope: Vec<u8>,
    pub state: MsgState,
    /// This device's own number for the message, in the order it was stored: a positive integer
    /// that never changes (the table's rowid; the core never runs VACUUM). Apps whose UI wants
    /// integer message ids (the Telegram fork) use it. 0 on a row not read from the database.
    pub seq: i64,
}

/// A file of an official post or of an official chat's photo (SPEC 18.3), and how it stands on
/// our own relays.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeedFileRow {
    pub id: [u8; 32],
    pub feed: Vec<u8>,
    /// The item's time.
    pub at_ms: u64,
    /// 0 nothing to leave there, 1 to leave there (from `put_next_ms` on), 2 left there.
    pub put: u8,
    pub put_next_ms: u64,
    pub put_tries: u32,
    /// Our relays lacked some of it when this phone looked: once whole here, it leaves it there.
    pub lacked: bool,
}

/// A file this phone has or is receiving (SPEC 17): which chunks are here.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlobRow {
    pub id: [u8; 32],
    /// Sealed size.
    pub size: u64,
    pub chunks: u32,
    /// One bit per chunk.
    pub have: Vec<u8>,
    /// Every chunk is here and the whole matches the id.
    pub complete: bool,
    /// Who it is expected from (none for our own files).
    pub source: Option<UserId>,
}

impl BlobRow {
    pub fn new(id: [u8; 32], size: u64, chunks: u32, source: Option<UserId>) -> BlobRow {
        BlobRow { id, size, chunks, have: vec![0; (chunks as usize).div_ceil(8)], complete: false, source }
    }

    pub fn has(&self, i: u32) -> bool {
        self.have.get(i as usize / 8).is_some_and(|b| b & (1 << (i % 8)) != 0)
    }

    pub fn set(&mut self, i: u32) {
        if let Some(b) = self.have.get_mut(i as usize / 8) {
            *b |= 1 << (i % 8);
        }
    }

    pub fn fill(&mut self) {
        for i in 0..self.chunks {
            self.set(i);
        }
    }

    pub fn clear(&mut self) {
        self.have.iter_mut().for_each(|b| *b = 0);
        self.complete = false;
    }

    pub fn missing(&self) -> u32 {
        (0..self.chunks).filter(|i| !self.has(*i)).count() as u32
    }

    pub fn first_missing(&self) -> Option<u32> {
        (0..self.chunks).find(|i| !self.has(*i))
    }
}

/// A chunk waiting to go to a contact.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlobOut {
    pub peer: UserId,
    pub id: [u8; 32],
    pub index: u32,
    pub attempts: u32,
}

/// A message in a group or channel (SPEC 7): like [`MessageRow`], with the group and the sender.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupMessageRow {
    pub id: [u8; 16],
    pub gid: Vec<u8>,
    pub sender: UserId,
    pub outgoing: bool,
    pub sent_ms: u64,
    pub at_ms: u64,
    pub kind: String,
    pub envelope: Vec<u8>,
    pub state: MsgState,
    /// This device's own number for it, increasing (the table's rowid). 0 before saving.
    pub seq: i64,
}

#[derive(Debug, Clone)]
pub struct SessionRow {
    pub id: String,
    pub peer: UserId,
    /// vodozemac session pickle (JSON); secret, so only ever inside the database.
    pub pickle: Vec<u8>,
    pub by_me: bool,
    pub created_ms: u64,
    pub used_ms: u64,
    /// When it last opened a message from the peer (0 = never): proof the peer held it then.
    pub rx_ms: u64,
}

#[derive(Debug, Clone)]
pub struct OutRow {
    pub frame_id: [u8; 16],
    pub peer: UserId,
    pub msg_id: Option<[u8; 16]>,
    pub frame: Vec<u8>,
    pub attempts: u32,
    pub next_ms: u64,
    pub last_error: Option<String>,
}

pub struct Store {
    conn: Connection,
}

/// Queries, on the connection or inside a transaction.
pub struct Db<'a>(&'a Connection);

fn arr<const N: usize>(row: &Row, i: usize) -> rusqlite::Result<[u8; N]> {
    let v: Vec<u8> = row.get(i)?;
    v.try_into().map_err(|_| rusqlite::Error::InvalidColumnType(i, "blob length".into(), rusqlite::types::Type::Blob))
}

fn opt_arr<const N: usize>(row: &Row, i: usize) -> rusqlite::Result<Option<[u8; N]>> {
    let v: Option<Vec<u8>> = row.get(i)?;
    match v {
        None => Ok(None),
        Some(v) => v.try_into().map(Some).map_err(|_| rusqlite::Error::InvalidColumnType(i, "blob length".into(), rusqlite::types::Type::Blob)),
    }
}

impl Store {
    /// Open (or create) the database with its 32-byte key. A wrong key is an error, never an
    /// empty database.
    pub fn open(path: &Path, key: &[u8; 32]) -> Result<Store> {
        let conn = Connection::open(path)?;
        Self::init(conn, key)
    }

    pub fn open_in_memory(key: &[u8; 32]) -> Result<Store> {
        Self::init(Connection::open_in_memory()?, key)
    }

    fn init(conn: Connection, key: &[u8; 32]) -> Result<Store> {
        // SQLCipher's own log would print decrypt failures to stderr / logcat; we report them
        let _: Option<String> = conn.query_row("PRAGMA cipher_log_level = NONE", [], |r| r.get(0)).ok();
        let pragma = Zeroizing::new(format!("PRAGMA key = \"x'{}'\";", hex::encode(key)));
        conn.execute_batch(&pragma)?;
        // fails with "file is not a database" on a wrong key
        conn.query_row("SELECT count(*) FROM sqlite_master", [], |r| r.get::<_, i64>(0))?;
        conn.execute_batch("PRAGMA secure_delete = ON;")?;
        let _: String = conn.query_row("PRAGMA journal_mode = WAL", [], |r| r.get(0))?;
        conn.execute_batch(SCHEMA)?;
        let store = Store { conn };
        let db = store.db();
        if db.meta("schema")?.is_none() {
            db.set_meta("schema", &SCHEMA_VERSION.to_be_bytes())?;
        }
        db.prune_seen(crate::util::now_ms().saturating_sub(SEEN_KEEP_MS))?;
        Ok(store)
    }

    pub fn db(&self) -> Db<'_> {
        Db(&self.conn)
    }

    /// Run `f` in one transaction: everything or nothing (the ratchet state, the account and the
    /// outbox row of one message are saved together, so a crash never reuses a key).
    pub fn transaction<T>(&mut self, f: impl FnOnce(&Db) -> Result<T>) -> Result<T> {
        let tx = self.conn.transaction()?;
        let out = f(&Db(&tx))?;
        tx.commit()?;
        Ok(out)
    }
}

impl Db<'_> {
    pub fn meta(&self, k: &str) -> Result<Option<Vec<u8>>> {
        Ok(self.0.query_row("SELECT v FROM meta WHERE k = ?1", [k], |r| r.get(0)).optional()?)
    }

    pub fn set_meta(&self, k: &str, v: &[u8]) -> Result<()> {
        self.0.execute("INSERT INTO meta (k, v) VALUES (?1, ?2) ON CONFLICT(k) DO UPDATE SET v = excluded.v", params![k, v])?;
        Ok(())
    }

    pub fn identity(&self) -> Result<Option<Vec<u8>>> {
        self.meta("identity")
    }

    pub fn save_identity(&self, saved: &[u8]) -> Result<()> {
        self.set_meta("identity", saved)
    }

    // ---- contacts ----

    pub fn upsert_contact(&self, c: &Contact) -> Result<()> {
        self.0.execute(
            "INSERT INTO contacts (sk, ik, hk, otk, loc, name, card, added_ms, by_them, blocked, verified)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
             ON CONFLICT(sk) DO UPDATE SET ik = excluded.ik, hk = excluded.hk, otk = excluded.otk,
               loc = excluded.loc, name = excluded.name, card = excluded.card, by_them = excluded.by_them,
               blocked = excluded.blocked, verified = excluded.verified",
            params![
                &c.sk[..],
                &c.ik[..],
                &c.hk[..],
                &c.otk[..],
                c.loc.as_ref().map(|l| l.to_vec()),
                c.name,
                c.card,
                c.added_ms as i64,
                c.by_them,
                c.blocked,
                c.verified
            ],
        )?;
        Ok(())
    }

    fn contact_row(r: &Row) -> rusqlite::Result<Contact> {
        Ok(Contact {
            sk: arr(r, 0)?,
            ik: arr(r, 1)?,
            hk: arr(r, 2)?,
            otk: arr(r, 3)?,
            loc: opt_arr(r, 4)?,
            name: r.get(5)?,
            card: r.get(6)?,
            added_ms: r.get::<_, i64>(7)? as u64,
            by_them: r.get(8)?,
            blocked: r.get(9)?,
            verified: r.get(10)?,
        })
    }

    pub fn contact(&self, sk: &UserId) -> Result<Option<Contact>> {
        Ok(self
            .0
            .query_row("SELECT sk, ik, hk, otk, loc, name, card, added_ms, by_them, blocked, verified FROM contacts WHERE sk = ?1", [&sk[..]], Self::contact_row)
            .optional()?)
    }

    pub fn contacts(&self) -> Result<Vec<Contact>> {
        let mut st = self.0.prepare("SELECT sk, ik, hk, otk, loc, name, card, added_ms, by_them, blocked, verified FROM contacts ORDER BY added_ms, rowid")?;
        let rows = st.query_map([], Self::contact_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    // ---- sessions ----

    pub fn sessions(&self, peer: &UserId) -> Result<Vec<SessionRow>> {
        let mut st = self.0.prepare(
            "SELECT s.id, s.peer, s.pickle, s.by_me, s.created_ms, s.used_ms, coalesce(r.rx_ms, 0)
             FROM sessions s LEFT JOIN session_rx r ON r.id = s.id WHERE s.peer = ?1 ORDER BY s.used_ms DESC",
        )?;
        let rows = st.query_map([&peer[..]], |r| {
            Ok(SessionRow {
                id: r.get(0)?,
                peer: arr(r, 1)?,
                pickle: r.get(2)?,
                by_me: r.get(3)?,
                created_ms: r.get::<_, i64>(4)? as u64,
                used_ms: r.get::<_, i64>(5)? as u64,
                rx_ms: r.get::<_, i64>(6)? as u64,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn save_session(&self, s: &SessionRow) -> Result<()> {
        self.0.execute(
            "INSERT INTO sessions (id, peer, pickle, by_me, created_ms, used_ms) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(id) DO UPDATE SET pickle = excluded.pickle, used_ms = excluded.used_ms",
            params![s.id, &s.peer[..], s.pickle, s.by_me, s.created_ms as i64, s.used_ms as i64],
        )?;
        // kept beside the row (a table of its own, as the store never alters an old one)
        if s.rx_ms > 0 {
            self.0.execute(
                "INSERT INTO session_rx (id, rx_ms) VALUES (?1, ?2) ON CONFLICT(id) DO UPDATE SET rx_ms = max(rx_ms, excluded.rx_ms)",
                params![s.id, s.rx_ms as i64],
            )?;
        }
        Ok(())
    }

    // ---- files (SPEC 17) ----

    pub fn blob(&self, id: &[u8; 32]) -> Result<Option<BlobRow>> {
        Ok(self
            .0
            .query_row("SELECT id, size, chunks, have, complete, source FROM blobs WHERE id = ?1", [&id[..]], |r| {
                let source: Option<Vec<u8>> = r.get(5)?;
                Ok(BlobRow {
                    id: arr(r, 0)?,
                    size: r.get::<_, i64>(1)? as u64,
                    chunks: r.get::<_, i64>(2)? as u32,
                    have: r.get(3)?,
                    complete: r.get(4)?,
                    source: source.and_then(|s| <[u8; 32]>::try_from(s.as_slice()).ok()),
                })
            })
            .optional()?)
    }

    pub fn blob_save(&self, b: &BlobRow) -> Result<()> {
        self.0.execute(
            "INSERT INTO blobs (id, size, chunks, have, complete, source, created_ms) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
             ON CONFLICT(id) DO UPDATE SET have = ?4, complete = ?5",
            params![&b.id[..], b.size as i64, b.chunks as i64, b.have, b.complete, b.source.map(|s| s.to_vec()), crate::util::now_ms() as i64],
        )?;
        Ok(())
    }

    /// Remember a file's key under the short id an app shows it with (inside the encrypted store).
    pub fn media_key_save(&self, short: i64, id: &[u8; 32], key: &[u8; 32], size: u64) -> Result<()> {
        self.0.execute(
            "INSERT OR REPLACE INTO media_keys (short, id, key, size) VALUES (?1, ?2, ?3, ?4)",
            params![short, &id[..], &key[..], size as i64],
        )?;
        Ok(())
    }

    pub fn media_key(&self, short: i64) -> Result<Option<([u8; 32], [u8; 32], u64)>> {
        Ok(self
            .0
            .query_row("SELECT id, key, size FROM media_keys WHERE short = ?1", [short], |r| Ok((arr(r, 0)?, arr(r, 1)?, r.get::<_, i64>(2)? as u64)))
            .optional()?)
    }

    // ---- pinned messages of one-to-one chats (SPEC 5 `Pin`, T65) ----

    /// Pin message `target` of the chat with `peer` (again: it moves to the end), or take it off.
    pub fn pin_set(&self, peer: &UserId, target: &[u8; 16], pinned: bool, at_ms: u64) -> Result<()> {
        if pinned {
            self.0.execute("INSERT OR REPLACE INTO pins (peer, target, at_ms) VALUES (?1, ?2, ?3)", params![&peer[..], &target[..], at_ms as i64])?;
        } else {
            self.0.execute("DELETE FROM pins WHERE peer = ?1 AND target = ?2", params![&peer[..], &target[..]])?;
        }
        Ok(())
    }

    /// The chat's pinned messages, the newest last.
    pub fn pins(&self, peer: &UserId) -> Result<Vec<[u8; 16]>> {
        let mut st = self.0.prepare("SELECT target FROM pins WHERE peer = ?1 ORDER BY at_ms")?;
        let rows = st.query_map([&peer[..]], |r| arr::<16>(r, 0))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    // ---- cards of those who post in the official feeds (SPEC 18.4) ----

    /// Keep an author's card unless a newer one of theirs is here.
    pub fn feed_card_save(&self, sk: &UserId, card: &[u8], ts_ms: u64) -> Result<()> {
        self.0.execute(
            "INSERT INTO feed_cards (sk, card, ts_ms) VALUES (?1, ?2, ?3)
             ON CONFLICT(sk) DO UPDATE SET card = ?2, ts_ms = ?3 WHERE ?3 > feed_cards.ts_ms",
            params![&sk[..], card, ts_ms as i64],
        )?;
        Ok(())
    }

    pub fn feed_card(&self, sk: &UserId) -> Result<Option<Vec<u8>>> {
        Ok(self.0.query_row("SELECT card FROM feed_cards WHERE sk = ?1", [&sk[..]], |r| r.get(0)).optional()?)
    }

    // ---- official files on our relays (SPEC 18.3) ----

    /// A file of an official item: known from now on (kept if known already).
    /// A file that is an official chat's own photo (`feedfiles.rs`: kept on our relays for good).
    pub fn feed_photo_add(&self, id: &[u8; 32]) -> Result<()> {
        self.0.execute("INSERT OR IGNORE INTO feed_photos (id) VALUES (?1)", [&id[..]])?;
        Ok(())
    }

    pub fn is_feed_photo(&self, id: &[u8; 32]) -> Result<bool> {
        Ok(self.0.query_row("SELECT 1 FROM feed_photos WHERE id = ?1", [&id[..]], |_| Ok(())).optional()?.is_some())
    }

    pub fn feed_file_add(&self, id: &[u8; 32], feed: &[u8], at_ms: u64) -> Result<()> {
        self.0.execute("INSERT OR IGNORE INTO feed_files (id, feed, at_ms) VALUES (?1, ?2, ?3)", params![&id[..], feed, at_ms as i64])?;
        Ok(())
    }

    fn feed_file_row(r: &Row) -> rusqlite::Result<FeedFileRow> {
        Ok(FeedFileRow {
            id: arr(r, 0)?,
            feed: r.get(1)?,
            at_ms: r.get::<_, i64>(2)? as u64,
            put: r.get::<_, i64>(3)? as u8,
            put_next_ms: r.get::<_, i64>(4)? as u64,
            put_tries: r.get::<_, i64>(5)? as u32,
            lacked: r.get::<_, i64>(6)? != 0,
        })
    }

    pub fn feed_file(&self, id: &[u8; 32]) -> Result<Option<FeedFileRow>> {
        Ok(self
            .0
            .query_row("SELECT id, feed, at_ms, put, put_next_ms, put_tries, lacked FROM feed_files WHERE id = ?1", [&id[..]], Self::feed_file_row)
            .optional()?)
    }

    /// Whether and when to leave it on our relays (see `FeedFileRow::put`).
    pub fn feed_file_put(&self, id: &[u8; 32], put: u8, next_ms: u64, tries: u32) -> Result<()> {
        self.0.execute("UPDATE feed_files SET put = ?2, put_next_ms = ?3, put_tries = ?4 WHERE id = ?1", params![&id[..], put as i64, next_ms as i64, tries as i64])?;
        Ok(())
    }

    pub fn feed_file_lacked(&self, id: &[u8; 32], lacked: bool) -> Result<()> {
        self.0.execute("UPDATE feed_files SET lacked = ?2 WHERE id = ?1", params![&id[..], lacked as i64])?;
        Ok(())
    }

    /// Files due to be left on our relays now, oldest first.
    pub fn feed_files_put_due(&self, now: u64, limit: usize) -> Result<Vec<FeedFileRow>> {
        let mut st = self.0.prepare("SELECT id, feed, at_ms, put, put_next_ms, put_tries, lacked FROM feed_files WHERE put = 1 AND put_next_ms <= ?1 ORDER BY at_ms LIMIT ?2")?;
        let rows = st.query_map(params![now as i64, limit as i64], Self::feed_file_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Forget files of items older than `before_ms` (our relays keep them two weeks).
    /// Files older than `before_ms` are no longer looked for on our relays, but never a chat's photo:
    /// it is kept there for good (T66, q16), and pruning it by the age of the item that set it took
    /// an official photo set weeks before off a new phone's list half a minute after it came.
    pub fn feed_files_prune(&self, before_ms: u64) -> Result<()> {
        self.0.execute("DELETE FROM feed_files WHERE at_ms < ?1 AND id NOT IN (SELECT id FROM feed_photos)", [before_ms as i64])?;
        Ok(())
    }

    // ---- official feeds (T38) ----

    /// Keep a feed item whose signature checked; false if it was here already. `class` (column
    /// `admins`): 0 pruned with the posts, 1 never pruned (admin lists, photo, name), 2 a reaction,
    /// pruned apart (`feed_prune_reacts`).
    pub fn feed_item_save(&self, feed_id: &[u8], id: &[u8], ts_ms: u64, class: u8, item: &crate::feed::SignedFeedItem) -> Result<bool> {
        let n = self.0.execute(
            "INSERT OR IGNORE INTO feed_items (id, feed_id, ts_ms, admins, body, sig, at_ms) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![id, feed_id, ts_ms as i64, class as i64, item.body, item.sig, crate::util::now_ms() as i64],
        )?;
        if n > 0 {
            FEED_GEN.fetch_add(1, Ordering::SeqCst);
        }
        Ok(n > 0)
    }

    /// Keep only the newest `keep` reactions of a feed.
    pub fn feed_prune_reacts(&self, feed_id: &[u8], keep: usize) -> Result<()> {
        let n = self.0.execute(
            "DELETE FROM feed_items WHERE feed_id = ?1 AND admins = 2 AND id NOT IN
               (SELECT id FROM feed_items WHERE feed_id = ?1 AND admins = 2 ORDER BY ts_ms DESC LIMIT ?2)",
            params![feed_id, keep as i64],
        )?;
        if n > 0 {
            FEED_GEN.fetch_add(1, Ordering::SeqCst);
        }
        Ok(())
    }

    // ---- reactions in chats and groups (SPEC 5 `Reaction`) ----

    /// Someone's reaction to a message (`scope`: the contact for a one-to-one chat, the group id
    /// for a group); an empty emoji takes it back. Only a newer one replaces theirs. True if it changed.
    pub fn reaction_set(&self, scope: &[u8], target: &[u8], reactor: &UserId, emoji: &str, ts_ms: u64) -> Result<bool> {
        let n = self.0.execute(
            "INSERT INTO reactions (scope, target, reactor, emoji, ts_ms) VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT (scope, target, reactor) DO UPDATE SET emoji = excluded.emoji, ts_ms = excluded.ts_ms
             WHERE excluded.ts_ms >= reactions.ts_ms AND excluded.emoji != reactions.emoji",
            params![scope, target, &reactor[..], emoji, ts_ms as i64],
        )?;
        Ok(n > 0)
    }

    /// Someone's vote on poll `poll` of a group (T69): the newest by its sender's clock stands, and
    /// no choices takes it back; with `first_stays` (a quiz) a vote already here is kept. True
    /// when it changed what is here.
    pub fn poll_vote_set(&self, scope: &[u8], poll: &[u8], voter: &UserId, choices: &[u32], ts_ms: u64, first_stays: bool) -> Result<bool> {
        let bytes: Vec<u8> = choices.iter().map(|&c| c as u8).collect();
        let sql = if first_stays {
            "INSERT INTO poll_votes (scope, poll, voter, choices, ts_ms) VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT (scope, poll, voter) DO NOTHING"
        } else {
            "INSERT INTO poll_votes (scope, poll, voter, choices, ts_ms) VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT (scope, poll, voter) DO UPDATE SET choices = excluded.choices, ts_ms = excluded.ts_ms
             WHERE excluded.ts_ms >= poll_votes.ts_ms AND excluded.choices != poll_votes.choices"
        };
        let n = self.0.execute(sql, params![scope, poll, &voter[..], bytes, ts_ms as i64])?;
        Ok(n > 0)
    }

    /// The votes standing on a poll: (who, their choices), oldest first; taken-back ones left out.
    pub fn poll_votes(&self, scope: &[u8], poll: &[u8]) -> Result<Vec<(UserId, Vec<u32>)>> {
        let mut st = self.0.prepare("SELECT voter, choices FROM poll_votes WHERE scope = ?1 AND poll = ?2 AND length(choices) > 0 ORDER BY ts_ms")?;
        let rows = st.query_map(params![scope, poll], |r| Ok((arr(r, 0)?, r.get::<_, Vec<u8>>(1)?.into_iter().map(u32::from).collect())))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// The reactions standing in a chat or group: (message id, who, emoji), oldest first.
    pub fn reactions(&self, scope: &[u8]) -> Result<Vec<([u8; 16], UserId, String)>> {
        let mut st = self.0.prepare("SELECT target, reactor, emoji FROM reactions WHERE scope = ?1 AND emoji != '' ORDER BY ts_ms")?;
        let rows = st.query_map([scope], |r| Ok((arr(r, 0)?, arr(r, 1)?, r.get::<_, String>(2)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Every item kept for a feed, oldest first.
    pub fn feed_items(&self, feed_id: &[u8]) -> Result<Vec<crate::feed::SignedFeedItem>> {
        let mut st = self.0.prepare("SELECT body, sig FROM feed_items WHERE feed_id = ?1 ORDER BY ts_ms, id")?;
        let rows = st.query_map([feed_id], |r| Ok(crate::feed::SignedFeedItem { body: r.get(0)?, sig: r.get(1)? }))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Keep only the newest `keep` items of a feed (admin lists always stay).
    pub fn feed_prune(&self, feed_id: &[u8], keep: usize) -> Result<()> {
        let n = self.0.execute(
            "DELETE FROM feed_items WHERE feed_id = ?1 AND admins = 0 AND id NOT IN
               (SELECT id FROM feed_items WHERE feed_id = ?1 AND admins = 0 ORDER BY ts_ms DESC LIMIT ?2)",
            params![feed_id, keep as i64],
        )?;
        if n > 0 {
            FEED_GEN.fetch_add(1, Ordering::SeqCst);
        }
        Ok(())
    }

    // ---- the public list of channels and groups (q6, T70; node::directory) ----

    /// A card read from a relay: kept when newer than the one that signer had for that chat.
    pub fn dir_card_put(&self, g: &[u8], pubkey: &str, card: &str, created_s: u64) -> Result<()> {
        self.0.execute(
            "INSERT INTO dir_cards (g, pubkey, card, created_s) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT (g, pubkey) DO UPDATE SET card = excluded.card, created_s = excluded.created_s
             WHERE excluded.created_s > dir_cards.created_s",
            params![g, pubkey, card, created_s as i64],
        )?;
        Ok(())
    }

    /// Every card kept: (chat, signer, card JSON, when signed).
    pub fn dir_cards(&self) -> Result<Vec<(Vec<u8>, String, String, u64)>> {
        let mut st = self.0.prepare("SELECT g, pubkey, card, created_s FROM dir_cards")?;
        let rows = st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get::<_, i64>(3)? as u64)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// A signer took its chat out of the list.
    pub fn dir_card_drop(&self, g: &[u8], pubkey: &str) -> Result<()> {
        self.0.execute("DELETE FROM dir_cards WHERE g = ?1 AND pubkey = ?2", params![g, pubkey])?;
        Ok(())
    }

    /// Cards signed before `before_s` go: their chat's owner did not renew them.
    pub fn dir_cards_drop_older(&self, before_s: u64) -> Result<usize> {
        Ok(self.0.execute("DELETE FROM dir_cards WHERE created_s < ?1", [before_s as i64])?)
    }

    /// Whether a chat of ours is in the public list; None when its owner never chose.
    pub fn dir_listed(&self, g: &[u8]) -> Result<Option<bool>> {
        Ok(self.0.query_row("SELECT listed FROM dir_listed WHERE g = ?1", [g], |r| r.get::<_, i64>(0)).optional()?.map(|v| v != 0))
    }

    pub fn dir_set_listed(&self, g: &[u8], listed: bool) -> Result<()> {
        self.0.execute("INSERT OR REPLACE INTO dir_listed (g, listed) VALUES (?1, ?2)", params![g, listed as i64])?;
        Ok(())
    }

    /// The invite link a chat's card carries, made once for the list.
    pub fn dir_link(&self, g: &[u8]) -> Result<Option<String>> {
        Ok(self.0.query_row("SELECT link FROM dir_links WHERE g = ?1", [g], |r| r.get(0)).optional()?)
    }

    pub fn dir_set_link(&self, g: &[u8], link: &str) -> Result<()> {
        self.0.execute("INSERT OR REPLACE INTO dir_links (g, link) VALUES (?1, ?2)", params![g, link])?;
        Ok(())
    }

    /// The small photo a chat of ours shows in the list: (its hash, its JPEG).
    pub fn dir_own_photo(&self, g: &[u8]) -> Result<Option<(String, Vec<u8>)>> {
        Ok(self.0.query_row("SELECT hash, jpeg FROM dir_own_photos WHERE g = ?1", [g], |r| Ok((r.get(0)?, r.get(1)?))).optional()?)
    }

    /// A chat of ours gets its list photo (None: it has none now).
    pub fn dir_set_own_photo(&self, g: &[u8], photo: Option<(&str, &[u8])>) -> Result<()> {
        match photo {
            Some((hash, jpeg)) => {
                self.0.execute("INSERT OR REPLACE INTO dir_own_photos (g, hash, jpeg) VALUES (?1, ?2, ?3)", params![g, hash, jpeg])?;
            }
            None => {
                self.0.execute("DELETE FROM dir_own_photos WHERE g = ?1", [g])?;
            }
        }
        Ok(())
    }

    /// A listed chat's photo read from a relay, as its signer put it.
    pub fn dir_photo_put(&self, g: &[u8], pubkey: &str, hash: &str, jpeg: &[u8]) -> Result<()> {
        self.0.execute("INSERT OR REPLACE INTO dir_photos (g, pubkey, hash, jpeg) VALUES (?1, ?2, ?3, ?4)", params![g, pubkey, hash, jpeg])?;
        Ok(())
    }

    /// The photo kept of a listed chat from that signer: (its hash, its JPEG).
    pub fn dir_photo(&self, g: &[u8], pubkey: &str) -> Result<Option<(String, Vec<u8>)>> {
        Ok(self.0.query_row("SELECT hash, jpeg FROM dir_photos WHERE g = ?1 AND pubkey = ?2", params![g, pubkey], |r| Ok((r.get(0)?, r.get(1)?))).optional()?)
    }

    /// Contacts (including group members) reached since `since_ms`: who feed items go to.
    pub fn reached_since(&self, since_ms: u64) -> Result<Vec<UserId>> {
        let mut st = self.0.prepare("SELECT peer FROM reach WHERE at_ms >= ?1")?;
        let rows = st.query_map([since_ms as i64], |r| arr(r, 0))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    // ---- invite links (SPEC 7.4) ----

    /// An invite we made, with its secret and link: only our phone can show the link again, and
    /// it stays the same (our card in it keeps our permanent fallback key).
    pub fn invite_secret_save(&self, invite_id: &[u8], gid: &[u8], secret: &[u8], link: &str) -> Result<()> {
        self.0.execute(
            "INSERT OR REPLACE INTO invite_secrets (invite_id, gid, secret, link, created_ms) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![invite_id, gid, secret, link, crate::util::now_ms() as i64],
        )?;
        Ok(())
    }

    /// (invite id, link, created) of the invites we made for a group.
    pub fn invite_secrets(&self, gid: &[u8]) -> Result<Vec<(Vec<u8>, String, u64)>> {
        let mut st = self.0.prepare("SELECT invite_id, link, created_ms FROM invite_secrets WHERE gid = ?1 ORDER BY created_ms")?;
        let rows = st.query_map([gid], |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? as u64)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn invite_secret_drop(&self, invite_id: &[u8]) -> Result<()> {
        self.0.execute("DELETE FROM invite_secrets WHERE invite_id = ?1", [invite_id])?;
        Ok(())
    }

    /// Someone asked to join by a link that needs approval (kept on admins' phones).
    pub fn join_request_save(&self, gid: &[u8], sk: &UserId, invite_id: &[u8], card: &[u8], at_ms: u64) -> Result<()> {
        self.0.execute(
            "INSERT OR REPLACE INTO join_requests (gid, sk, invite_id, card, at_ms) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![gid, &sk[..], invite_id, card, at_ms as i64],
        )?;
        Ok(())
    }

    /// (who, invite id, card, when) of the requests waiting for a group.
    pub fn join_requests(&self, gid: &[u8]) -> Result<Vec<(UserId, Vec<u8>, Vec<u8>, u64)>> {
        let mut st = self.0.prepare("SELECT sk, invite_id, card, at_ms FROM join_requests WHERE gid = ?1 ORDER BY at_ms")?;
        let rows = st.query_map([gid], |r| Ok((arr(r, 0)?, r.get(1)?, r.get(2)?, r.get::<_, i64>(3)? as u64)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn join_request_drop(&self, gid: &[u8], sk: &UserId) -> Result<()> {
        self.0.execute("DELETE FROM join_requests WHERE gid = ?1 AND sk = ?2", params![gid, &sk[..]])?;
        Ok(())
    }

    /// A group we asked to join and are waiting for (the joiner's side).
    pub fn joining_save(&self, gid: &[u8], inviter: &UserId, name: &str, broadcast: bool) -> Result<()> {
        self.0.execute(
            "INSERT OR REPLACE INTO joining (gid, inviter, name, broadcast, at_ms) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![gid, &inviter[..], name, broadcast, crate::util::now_ms() as i64],
        )?;
        Ok(())
    }

    /// (group, inviter, name, broadcast, when) of the joins still waiting.
    pub fn joining(&self) -> Result<Vec<(Vec<u8>, UserId, String, bool, u64)>> {
        let mut st = self.0.prepare("SELECT gid, inviter, name, broadcast, at_ms FROM joining ORDER BY at_ms")?;
        let rows = st.query_map([], |r| Ok((r.get(0)?, arr(r, 1)?, r.get(2)?, r.get(3)?, r.get::<_, i64>(4)? as u64)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn joining_done(&self, gid: &[u8]) -> Result<()> {
        self.0.execute("DELETE FROM joining WHERE gid = ?1", [gid])?;
        Ok(())
    }

    /// Our own profile (under our key) or a contact's, as the newest version that came.
    pub fn profile_save(&self, sk: &UserId, p: &ProfileRow) -> Result<()> {
        let (blob, key, size) = match &p.photo {
            Some((id, key, size)) => (Some(id.to_vec()), Some(key.to_vec()), *size as i64),
            None => (None, None, 0),
        };
        self.0.execute(
            "INSERT OR REPLACE INTO profiles (sk, version, blob, key, size, thumb) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![&sk[..], p.version as i64, blob, key, size, p.thumb],
        )?;
        Ok(())
    }

    pub fn profile(&self, sk: &UserId) -> Result<Option<ProfileRow>> {
        Ok(self
            .0
            .query_row("SELECT version, blob, key, size, thumb FROM profiles WHERE sk = ?1", [&sk[..]], |r| {
                let blob: Option<Vec<u8>> = r.get(1)?;
                let key: Option<Vec<u8>> = r.get(2)?;
                let photo = match (blob.and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok()), key.and_then(|k| <[u8; 32]>::try_from(k.as_slice()).ok())) {
                    (Some(b), Some(k)) => Some((b, k, r.get::<_, i64>(3)? as u64)),
                    _ => None,
                };
                Ok(ProfileRow { version: r.get::<_, i64>(0)? as u64, photo, thumb: r.get(4)? })
            })
            .optional()?)
    }

    pub fn blob_out_add(&self, peer: &UserId, id: &[u8; 32], index: u32) -> Result<()> {
        self.0.execute("INSERT OR IGNORE INTO blob_out (peer, id, idx) VALUES (?1, ?2, ?3)", params![&peer[..], &id[..], index as i64])?;
        Ok(())
    }

    /// Chunks due now, but only for contacts with no message waiting (text first, SPEC 12): the
    /// soonest due first, each looked up by contact. Leaving out every contact with a frame due
    /// read every due frame, 2 s at a time under the core's lock on the official chats' owner's
    /// phone, whose outbox is the biggest (1.2.45, 1 Nov 2026).
    pub fn blob_out_due(&self, now_ms: u64, limit: usize) -> Result<Vec<BlobOut>> {
        let mut st = self.0.prepare(
            "SELECT peer, id, idx, attempts FROM blob_out b WHERE next_ms <= ?1
             AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.peer = b.peer AND o.next_ms <= ?1)
             ORDER BY next_ms, rowid LIMIT ?2",
        )?;
        let rows = st.query_map(params![now_ms as i64, limit as i64], |r| {
            Ok(BlobOut { peer: arr(r, 0)?, id: arr(r, 1)?, index: r.get::<_, i64>(2)? as u32, attempts: r.get::<_, i64>(3)? as u32 })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn blob_out_done(&self, o: &BlobOut) -> Result<()> {
        self.0.execute("DELETE FROM blob_out WHERE peer = ?1 AND id = ?2 AND idx = ?3", params![&o.peer[..], &o.id[..], o.index as i64])?;
        Ok(())
    }

    pub fn blob_out_later(&self, o: &BlobOut, next_ms: u64) -> Result<()> {
        self.0.execute(
            "UPDATE blob_out SET attempts = attempts + 1, next_ms = ?4 WHERE peer = ?1 AND id = ?2 AND idx = ?3",
            params![&o.peer[..], &o.id[..], o.index as i64, next_ms as i64],
        )?;
        Ok(())
    }

    /// When the next chunk is due (for the delivery loop's sleep).
    pub fn blob_out_next_ms(&self) -> Result<Option<u64>> {
        Ok(self.0.query_row("SELECT MIN(next_ms) FROM blob_out", [], |r| r.get::<_, Option<i64>>(0))?.map(|v| v as u64))
    }

    // ---- groups (SPEC 7) ----

    pub fn group_join(&self, gid: &[u8], now_ms: u64) -> Result<()> {
        self.0.execute("INSERT INTO groups (gid, joined_ms) VALUES (?1, ?2) ON CONFLICT(gid) DO UPDATE SET left_ms = 0", params![gid, now_ms as i64])?;
        Ok(())
    }

    /// A contact row that exists only because of a shared group: hidden from the contact list.
    pub fn mark_group_peer(&self, sk: &UserId) -> Result<()> {
        self.0.execute("INSERT OR IGNORE INTO group_peers (sk) VALUES (?1)", [&sk[..]])?;
        Ok(())
    }

    /// The user added them themselves: a real contact now.
    pub fn unmark_group_peer(&self, sk: &UserId) -> Result<()> {
        self.0.execute("DELETE FROM group_peers WHERE sk = ?1", [&sk[..]])?;
        Ok(())
    }

    pub fn is_group_peer(&self, sk: &UserId) -> Result<bool> {
        Ok(self.0.query_row("SELECT count(*) FROM group_peers WHERE sk = ?1", [&sk[..]], |r| r.get::<_, i64>(0))? > 0)
    }

    pub fn group_leave(&self, gid: &[u8], now_ms: u64) -> Result<()> {
        self.0.execute("UPDATE groups SET left_ms = ?2 WHERE gid = ?1", params![gid, now_ms as i64])?;
        Ok(())
    }

    /// The groups this phone is in (not left).
    pub fn groups(&self) -> Result<Vec<Vec<u8>>> {
        let mut st = self.0.prepare("SELECT gid FROM groups WHERE left_ms = 0 ORDER BY joined_ms")?;
        let rows = st.query_map([], |r| r.get::<_, Vec<u8>>(0))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Keep a group event; `false` if it was already here.
    pub fn group_op_save(&self, gid: &[u8], id: &[u8; 32], body: &[u8], sig: &[u8], now_ms: u64) -> Result<bool> {
        let n = self.0.execute(
            "INSERT OR IGNORE INTO group_ops (id, gid, body, sig, at_ms) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![&id[..], gid, body, sig, now_ms as i64],
        )?;
        Ok(n == 1)
    }

    /// All events of a group: (body, sig), in the order they came.
    pub fn group_ops(&self, gid: &[u8]) -> Result<Vec<(Vec<u8>, Vec<u8>)>> {
        let mut st = self.0.prepare("SELECT body, sig FROM group_ops WHERE gid = ?1 ORDER BY at_ms, rowid")?;
        let rows = st.query_map([gid], |r| Ok((r.get(0)?, r.get(1)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Insert a group message; `false` if one with this id is already there. Returns its seq.
    pub fn group_message_save(&self, m: &GroupMessageRow) -> Result<Option<i64>> {
        let n = self.0.execute(
            "INSERT OR IGNORE INTO group_messages (id, gid, sender, outgoing, sent_ms, at_ms, kind, envelope, state) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![&m.id[..], m.gid, &m.sender[..], m.outgoing, m.sent_ms as i64, m.at_ms as i64, m.kind, m.envelope, m.state as i64],
        )?;
        Ok((n == 1).then(|| self.0.last_insert_rowid()))
    }

    fn group_message_row(r: &Row) -> rusqlite::Result<GroupMessageRow> {
        Ok(GroupMessageRow {
            id: arr(r, 0)?,
            gid: r.get(1)?,
            sender: arr(r, 2)?,
            outgoing: r.get(3)?,
            sent_ms: r.get::<_, i64>(4)? as u64,
            at_ms: r.get::<_, i64>(5)? as u64,
            kind: r.get(6)?,
            envelope: r.get(7)?,
            state: MsgState::from_i64(r.get(8)?),
            seq: r.get(9)?,
        })
    }

    /// The newest `limit` messages of a group, oldest first (not those taken down).
    pub fn group_messages(&self, gid: &[u8], limit: usize) -> Result<Vec<GroupMessageRow>> {
        let mut st = self.0.prepare(
            "SELECT * FROM (SELECT id, gid, sender, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM group_messages
             WHERE gid = ?1 AND kind != 'deleted' ORDER BY at_ms DESC, rowid DESC LIMIT ?2) ORDER BY at_ms, sent_ms",
        )?;
        let rows = st.query_map(params![gid, limit as i64], Self::group_message_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// A page of a group in seq order (not those taken down), as [`Db::messages_page`].
    pub fn group_messages_page(&self, gid: &[u8], before: i64, after: i64, limit: usize) -> Result<Vec<GroupMessageRow>> {
        let sql = if after >= 0 {
            "SELECT id, gid, sender, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM group_messages
             WHERE gid = ?1 AND rowid > ?2 AND kind != 'deleted' ORDER BY rowid LIMIT ?3"
        } else {
            "SELECT * FROM (SELECT id, gid, sender, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM group_messages
             WHERE gid = ?1 AND rowid < ?2 AND kind != 'deleted' ORDER BY rowid DESC LIMIT ?3) ORDER BY rowid"
        };
        let bound = if after >= 0 { after } else if before > 0 { before } else { i64::MAX };
        let mut st = self.0.prepare(sql)?;
        let rows = st.query_map(params![gid, bound, limit as i64], Self::group_message_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// One group message by its seq (not one taken down).
    pub fn group_message_at(&self, gid: &[u8], seq: i64) -> Result<Option<GroupMessageRow>> {
        Ok(self
            .0
            .query_row(
                "SELECT id, gid, sender, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM group_messages
                 WHERE rowid = ?1 AND gid = ?2 AND kind != 'deleted'",
                params![seq, gid],
                Self::group_message_row,
            )
            .optional()?)
    }

    /// The messages of every group and channel after seq `after` (not those taken down), oldest
    /// first, at most `limit`.
    pub fn group_messages_since(&self, after: i64, limit: usize) -> Result<Vec<GroupMessageRow>> {
        let mut st = self.0.prepare(
            "SELECT id, gid, sender, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM group_messages
             WHERE rowid > ?1 AND kind != 'deleted' ORDER BY rowid LIMIT ?2",
        )?;
        let rows = st.query_map(params![after, limit as i64], Self::group_message_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// What a chat list and a screen show of a group or channel besides its last message, counted
    /// and not read (not those taken down): incoming messages after seq `after` (the last one read
    /// here), our newest message a member has read (SPEC 7.6), and how many there are.
    pub fn group_marks(&self, gid: &[u8], after: i64) -> Result<GroupMarks> {
        Ok(self.0.query_row(
            "SELECT
               (SELECT count(*) FROM group_messages WHERE gid = ?1 AND rowid > ?2 AND outgoing = 0 AND kind != 'deleted'),
               (SELECT coalesce(max(rowid), 0) FROM group_messages WHERE gid = ?1 AND outgoing = 1 AND state = ?3 AND kind != 'deleted'),
               (SELECT count(*) FROM group_messages WHERE gid = ?1 AND kind != 'deleted')",
            params![gid, after, MsgState::Read as i64],
            |r| Ok(GroupMarks { unread: r.get::<_, i64>(0)? as u32, read_out: r.get(1)?, total: r.get::<_, i64>(2)? as u32 }),
        )?)
    }

    /// A member's posts in a group sent after `from_ms` and before `to_ms`, those taken down too
    /// (an empty envelope): (time, envelope). What slow mode weighs a post against (q13, T73).
    pub fn group_posts_near(&self, gid: &[u8], sender: &UserId, from_ms: u64, to_ms: u64) -> Result<Vec<(u64, Vec<u8>)>> {
        let mut st = self.0.prepare("SELECT sent_ms, envelope FROM group_messages WHERE gid = ?1 AND sender = ?2 AND sent_ms > ?3 AND sent_ms < ?4 LIMIT 50")?;
        let rows = st.query_map(params![gid, &sender[..], from_ms.min(i64::MAX as u64) as i64, to_ms.min(i64::MAX as u64) as i64], |r| Ok((r.get::<_, i64>(0)? as u64, r.get(1)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// When a member last posted in a group, a post taken down too; 0 for never (q13, T73).
    pub fn group_last_post_ms(&self, gid: &[u8], sender: &UserId) -> Result<u64> {
        let t: Option<i64> = self.0.query_row("SELECT max(sent_ms) FROM group_messages WHERE gid = ?1 AND sender = ?2", params![gid, &sender[..]], |r| r.get(0))?;
        Ok(t.unwrap_or(0).max(0) as u64)
    }

    /// A group message that is here (not taken down): (seq, sender).
    pub fn group_message_find(&self, gid: &[u8], id: &[u8]) -> Result<Option<(i64, UserId)>> {
        Ok(self
            .0
            .query_row("SELECT rowid, sender FROM group_messages WHERE gid = ?1 AND id = ?2 AND kind != 'deleted'", params![gid, id], |r| Ok((r.get(0)?, arr(r, 1)?)))
            .optional()?)
    }

    /// The group messages behind these seqs: (seq, group, id, sender).
    pub fn group_messages_by_seq(&self, seqs: &[i64]) -> Result<Vec<(i64, Vec<u8>, [u8; 16], UserId)>> {
        let mut out = Vec::new();
        for seq in seqs {
            if let Some(row) = self
                .0
                .query_row("SELECT rowid, gid, id, sender FROM group_messages WHERE rowid = ?1 AND kind != 'deleted'", [seq], |r| Ok((r.get(0)?, r.get(1)?, arr(r, 2)?, arr(r, 3)?)))
                .optional()?
            {
                out.push(row);
            }
        }
        Ok(out)
    }

    /// Take a group message down (SPEC 5.2): the row stays, empty, so its seq is never given again
    /// (Telegram knows the message by it) and a late copy of it is not taken in. Its reactions go.
    /// True if it was here.
    pub fn group_message_blank(&self, gid: &[u8], id: &[u8]) -> Result<bool> {
        let n = self.0.execute("UPDATE group_messages SET kind = 'deleted', envelope = x'' WHERE gid = ?1 AND id = ?2 AND kind != 'deleted'", params![gid, id])?;
        self.0.execute("DELETE FROM reactions WHERE scope = ?1 AND target = ?2", params![gid, id])?;
        Ok(n == 1)
    }

    /// A group message that is here (not taken down), whole.
    pub fn group_message_by_id(&self, gid: &[u8], id: &[u8]) -> Result<Option<GroupMessageRow>> {
        Ok(self
            .0
            .query_row(
                "SELECT id, gid, sender, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM group_messages
                 WHERE gid = ?1 AND id = ?2 AND kind != 'deleted'",
                params![gid, id],
                Self::group_message_row,
            )
            .optional()?)
    }

    /// A one-to-one message's envelope after an edit (SPEC 5 `Edit`), and when that edit was made.
    /// True if the message was here.
    pub fn message_edit(&self, id: &[u8; 16], envelope: &[u8], edited_ms: u64) -> Result<bool> {
        let n = self.0.execute("UPDATE messages SET envelope = ?2 WHERE id = ?1", params![&id[..], envelope])?;
        if n == 1 {
            self.edit_mark(id, edited_ms)?;
        }
        Ok(n == 1)
    }

    /// The same for a group message (not one taken down).
    pub fn group_message_edit(&self, gid: &[u8], id: &[u8; 16], envelope: &[u8], edited_ms: u64) -> Result<bool> {
        let n = self
            .0
            .execute("UPDATE group_messages SET envelope = ?3 WHERE gid = ?1 AND id = ?2 AND kind != 'deleted'", params![gid, &id[..], envelope])?;
        if n == 1 {
            self.edit_mark(id, edited_ms)?;
        }
        Ok(n == 1)
    }

    fn edit_mark(&self, id: &[u8; 16], at_ms: u64) -> Result<()> {
        self.0.execute("INSERT OR REPLACE INTO edits (id, at_ms) VALUES (?1, ?2)", params![&id[..], at_ms as i64])?;
        Ok(())
    }

    /// When a message was last edited (the edit's own time, as its sender stamped it); 0 if never.
    pub fn edited_ms(&self, id: &[u8]) -> Result<u64> {
        Ok(self
            .0
            .query_row("SELECT at_ms FROM edits WHERE id = ?1", [id], |r| r.get::<_, i64>(0))
            .optional()?
            .unwrap_or(0) as u64)
    }

    /// The one-to-one messages behind these seqs: (seq, contact, id).
    pub fn messages_by_seq(&self, seqs: &[i64]) -> Result<Vec<(i64, UserId, [u8; 16])>> {
        let mut out = Vec::new();
        for seq in seqs {
            if let Some(row) = self.0.query_row("SELECT rowid, peer, id FROM messages WHERE rowid = ?1", [seq], |r| Ok((r.get(0)?, arr(r, 1)?, arr(r, 2)?))).optional()? {
                out.push(row);
            }
        }
        Ok(out)
    }

    /// The seq of a message of the chat with `peer`, if it is here.
    pub fn message_seq(&self, peer: &UserId, id: &[u8]) -> Result<Option<i64>> {
        Ok(self.0.query_row("SELECT rowid FROM messages WHERE peer = ?1 AND id = ?2", params![&peer[..], id], |r| r.get(0)).optional()?)
    }

    /// The seq of a message of the group `gid`, if it is here (and not deleted).
    pub fn group_message_seq(&self, gid: &[u8], id: &[u8]) -> Result<Option<i64>> {
        Ok(self
            .0
            .query_row("SELECT rowid FROM group_messages WHERE gid = ?1 AND id = ?2 AND kind != 'deleted'", params![gid, id], |r| r.get(0))
            .optional()?)
    }

    /// A message's reactions go with it.
    pub fn reactions_drop(&self, scope: &[u8], target: &[u8]) -> Result<()> {
        self.0.execute("DELETE FROM reactions WHERE scope = ?1 AND target = ?2", params![scope, target])?;
        Ok(())
    }

    pub fn group_message_state(&self, id: &[u8; 16], state: MsgState) -> Result<bool> {
        Ok(self.0.execute("UPDATE group_messages SET state = ?2 WHERE id = ?1 AND state < ?2", params![&id[..], state as i64])? == 1)
    }

    /// Others' messages of a group up to seq `max_seq` not yet read here: (id, sender, stored at).
    pub fn group_unread(&self, gid: &[u8], max_seq: i64) -> Result<Vec<([u8; 16], UserId, u64)>> {
        let mut st = self.0.prepare(
            "SELECT id, sender, at_ms FROM group_messages WHERE gid = ?1 AND outgoing = 0 AND state = ?2 AND rowid <= ?3 AND kind != 'deleted' ORDER BY rowid",
        )?;
        let rows = st.query_map(params![gid, MsgState::Received as i64, max_seq], |r| Ok((arr(r, 0)?, arr(r, 1)?, r.get::<_, i64>(2)? as u64)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// One of our own group messages (not taken down): its group and seq.
    pub fn group_message_mine(&self, id: &[u8; 16]) -> Result<Option<(Vec<u8>, i64)>> {
        Ok(self
            .0
            .query_row("SELECT gid, rowid FROM group_messages WHERE id = ?1 AND outgoing = 1 AND kind != 'deleted'", [&id[..]], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()?)
    }

    /// `reader` read our group message `id` (SPEC 7.6), at `at_ms` by this phone's clock; `false`
    /// if that was known. Kept 8 days, past Telegram's 7 for «seen by».
    pub fn group_read_add(&self, gid: &[u8], id: &[u8; 16], reader: &UserId, at_ms: u64) -> Result<bool> {
        self.0.execute("DELETE FROM group_reads WHERE at_ms < ?1", [at_ms.saturating_sub(8 * 86_400_000) as i64])?;
        let n = self.0.execute(
            "INSERT OR IGNORE INTO group_reads (id, reader, gid, at_ms) VALUES (?1, ?2, ?3, ?4)",
            params![&id[..], &reader[..], gid, at_ms as i64],
        )?;
        Ok(n == 1)
    }

    /// Who read our group message `id`, and when: the first first.
    pub fn group_reads(&self, id: &[u8; 16]) -> Result<Vec<(UserId, u64)>> {
        let mut st = self.0.prepare("SELECT reader, at_ms FROM group_reads WHERE id = ?1 ORDER BY at_ms, rowid")?;
        let rows = st.query_map([&id[..]], |r| Ok((arr(r, 0)?, r.get::<_, i64>(1)? as u64)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Our outbound Megolm session for a group: (pickle, created, messages sent).
    pub fn megolm_out(&self, gid: &[u8]) -> Result<Option<(Vec<u8>, u64, u32)>> {
        Ok(self
            .0
            .query_row("SELECT pickle, created_ms, sent FROM megolm_out WHERE gid = ?1", [gid], |r| {
                Ok((r.get(0)?, r.get::<_, i64>(1)? as u64, r.get::<_, i64>(2)? as u32))
            })
            .optional()?)
    }

    pub fn megolm_out_save(&self, gid: &[u8], pickle: &[u8], created_ms: u64, sent: u32) -> Result<()> {
        self.0.execute(
            "INSERT INTO megolm_out (gid, pickle, created_ms, sent) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(gid) DO UPDATE SET pickle = ?2, created_ms = ?3, sent = ?4",
            params![gid, pickle, created_ms as i64, sent as i64],
        )?;
        Ok(())
    }

    pub fn megolm_out_drop(&self, gid: &[u8]) -> Result<()> {
        self.0.execute("DELETE FROM megolm_out WHERE gid = ?1", [gid])?;
        Ok(())
    }

    /// An inbound Megolm session: (group, sender, pickle).
    pub fn megolm_in(&self, sid: &str) -> Result<Option<(Vec<u8>, UserId, Vec<u8>)>> {
        Ok(self.0.query_row("SELECT gid, sender, pickle FROM megolm_in WHERE sid = ?1", [sid], |r| Ok((r.get(0)?, arr(r, 1)?, r.get(2)?))).optional()?)
    }

    pub fn megolm_in_save(&self, sid: &str, gid: &[u8], sender: &UserId, pickle: &[u8]) -> Result<()> {
        self.0.execute(
            "INSERT INTO megolm_in (sid, gid, sender, pickle) VALUES (?1, ?2, ?3, ?4) ON CONFLICT(sid) DO UPDATE SET pickle = ?4",
            params![sid, gid, &sender[..], pickle],
        )?;
        Ok(())
    }

    // ---- messages ----

    /// Insert a message; `false` if one with this id is already there.
    pub fn insert_message(&self, m: &MessageRow) -> Result<bool> {
        let n = self.0.execute(
            "INSERT OR IGNORE INTO messages (rowid, id, peer, outgoing, sent_ms, at_ms, kind, envelope, state) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![self.next_seq()?, &m.id[..], &m.peer[..], m.outgoing, m.sent_ms as i64, m.at_ms as i64, m.kind, m.envelope, m.state as i64],
        )?;
        Ok(n == 1)
    }

    /// The next message's seq. SQLite would give a deleted newest message's number to the next
    /// one; a seq is never given twice, so the highest one ever given is kept before deleting.
    fn next_seq(&self) -> Result<i64> {
        Ok(self.max_seq()?.max(self.seq_floor()?) + 1)
    }

    fn max_seq(&self) -> Result<i64> {
        Ok(self.0.query_row("SELECT coalesce(max(rowid), 0) FROM messages", [], |r| r.get(0))?)
    }

    fn seq_floor(&self) -> Result<i64> {
        Ok(self.meta(SEQ_FLOOR)?.and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(i64::from_le_bytes).unwrap_or(0))
    }

    fn keep_seq_floor(&self) -> Result<()> {
        let max = self.max_seq()?;
        if max > self.seq_floor()? {
            self.set_meta(SEQ_FLOOR, &max.to_le_bytes())?;
        }
        Ok(())
    }

    /// Delete messages by seq, on this phone only. A queued copy still in the outbox goes with
    /// its message: a message deleted before it was sent never leaves the phone. Returns how
    /// many were deleted.
    pub fn delete_messages(&self, seqs: &[i64]) -> Result<usize> {
        self.keep_seq_floor()?;
        let mut n = 0;
        for seq in seqs {
            self.0.execute("DELETE FROM outbox WHERE msg_id IN (SELECT id FROM messages WHERE rowid = ?1)", [seq])?;
            n += self.0.execute("DELETE FROM messages WHERE rowid = ?1", [seq])?;
        }
        Ok(n)
    }

    /// Every message on this phone, one-to-one and in groups and channels, each the way it goes on
    /// its own (delete_messages; group_message_blank, so no group seq is given again), with its
    /// queued copy, reactions, votes, pins and group reads; the official feeds' posts stay (q3,
    /// «پاک کردن دادهٔ گره»). Returns how many went, and the files their media carried.
    pub fn clear_all_messages(&self) -> Result<(usize, Vec<[u8; 32]>)> {
        self.keep_seq_floor()?;
        let mut blobs = Vec::new();
        for sql in ["SELECT envelope FROM messages", "SELECT envelope FROM group_messages WHERE kind != 'deleted'"] {
            let mut st = self.0.prepare(sql)?;
            let envelopes = st.query_map([], |r| r.get::<_, Vec<u8>>(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
            for e in envelopes {
                if let Some(crate::envelope::Body::Media(m)) = crate::envelope::Envelope::from_bytes(&e).ok().and_then(|e| e.body) {
                    if let Ok(id) = <[u8; 32]>::try_from(m.blob_id.as_slice()) {
                        blobs.push(id);
                    }
                }
            }
        }
        let ours = "(SELECT id FROM messages UNION SELECT id FROM group_messages)";
        self.0.execute(&format!("DELETE FROM outbox WHERE msg_id IN {ours}"), [])?;
        self.0.execute(&format!("DELETE FROM reactions WHERE target IN {ours}"), [])?;
        self.0.execute(&format!("DELETE FROM poll_votes WHERE poll IN {ours}"), [])?;
        self.0.execute(&format!("DELETE FROM pins WHERE target IN {ours}"), [])?;
        self.0.execute("DELETE FROM group_reads WHERE id IN (SELECT id FROM group_messages)", [])?;
        let mut n = self.0.execute("DELETE FROM messages", [])?;
        n += self.0.execute("UPDATE group_messages SET kind = 'deleted', envelope = x'' WHERE kind != 'deleted'", [])?;
        Ok((n, blobs))
    }

    /// Forgets these files (their rows, keys and pending sends), save those a profile or an
    /// official feed still shows. Returns the ones forgotten; their files on disk are the caller's.
    pub fn drop_blobs(&self, ids: &[[u8; 32]]) -> Result<Vec<[u8; 32]>> {
        let mut dropped = Vec::new();
        for id in ids {
            let shown: i64 = self.0.query_row(
                "SELECT (SELECT count(*) FROM profiles WHERE blob = ?1) + (SELECT count(*) FROM feed_files WHERE id = ?1)
                    + (SELECT count(*) FROM feed_photos WHERE id = ?1)",
                [&id[..]],
                |r| r.get(0),
            )?;
            if shown > 0 {
                continue;
            }
            self.0.execute("DELETE FROM blobs WHERE id = ?1", [&id[..]])?;
            self.0.execute("DELETE FROM media_keys WHERE id = ?1", [&id[..]])?;
            self.0.execute("DELETE FROM blob_out WHERE id = ?1", [&id[..]])?;
            dropped.push(*id);
        }
        Ok(dropped)
    }

    /// Delete every message with `peer` up to `max_seq` (all of them for 0), as delete_messages.
    pub fn clear_messages(&self, peer: &UserId, max_seq: i64) -> Result<usize> {
        self.keep_seq_floor()?;
        let max = if max_seq <= 0 { i64::MAX } else { max_seq };
        self.0.execute("DELETE FROM outbox WHERE msg_id IN (SELECT id FROM messages WHERE peer = ?1 AND rowid <= ?2)", params![&peer[..], max])?;
        Ok(self.0.execute("DELETE FROM messages WHERE peer = ?1 AND rowid <= ?2", params![&peer[..], max])?)
    }

    fn message_row(r: &Row) -> rusqlite::Result<MessageRow> {
        Ok(MessageRow {
            id: arr(r, 0)?,
            peer: arr(r, 1)?,
            outgoing: r.get(2)?,
            sent_ms: r.get::<_, i64>(3)? as u64,
            at_ms: r.get::<_, i64>(4)? as u64,
            kind: r.get(5)?,
            envelope: r.get(6)?,
            state: MsgState::from_i64(r.get(7)?),
            seq: r.get(8)?,
        })
    }

    pub fn message(&self, id: &[u8; 16]) -> Result<Option<MessageRow>> {
        Ok(self
            .0
            .query_row("SELECT id, peer, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM messages WHERE id = ?1", [&id[..]], Self::message_row)
            .optional()?)
    }

    /// The newest `limit` messages with one contact, oldest first.
    pub fn messages(&self, peer: &UserId, limit: usize) -> Result<Vec<MessageRow>> {
        let mut st = self.0.prepare(
            "SELECT * FROM (SELECT id, peer, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM messages
             WHERE peer = ?1 ORDER BY at_ms DESC, rowid DESC LIMIT ?2) ORDER BY at_ms, sent_ms",
        )?;
        let rows = st.query_map(params![&peer[..], limit as i64], Self::message_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// A page of the chat with `peer` in seq order, oldest first: with `after` below 0, the newest
    /// `limit` before seq `before` (0: the newest of all); else the oldest `limit` after seq
    /// `after` (0: from the first). Read through `messages_peer_seq`, so a long chat costs no
    /// more than a short one.
    pub fn messages_page(&self, peer: &UserId, before: i64, after: i64, limit: usize) -> Result<Vec<MessageRow>> {
        let sql = if after >= 0 {
            "SELECT id, peer, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM messages
             WHERE peer = ?1 AND rowid > ?2 ORDER BY rowid LIMIT ?3"
        } else {
            "SELECT * FROM (SELECT id, peer, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM messages
             WHERE peer = ?1 AND rowid < ?2 ORDER BY rowid DESC LIMIT ?3) ORDER BY rowid"
        };
        let bound = if after >= 0 { after } else if before > 0 { before } else { i64::MAX };
        let mut st = self.0.prepare(sql)?;
        let rows = st.query_map(params![&peer[..], bound, limit as i64], Self::message_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// One message of the chat with `peer`, by its seq.
    pub fn message_at(&self, peer: &UserId, seq: i64) -> Result<Option<MessageRow>> {
        Ok(self
            .0
            .query_row(
                "SELECT id, peer, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM messages WHERE rowid = ?1 AND peer = ?2",
                params![seq, &peer[..]],
                Self::message_row,
            )
            .optional()?)
    }

    /// The one-to-one messages of every chat after seq `after`, oldest first, at most `limit`.
    pub fn messages_since(&self, after: i64, limit: usize) -> Result<Vec<MessageRow>> {
        let mut st = self.0.prepare(
            "SELECT id, peer, outgoing, sent_ms, at_ms, kind, envelope, state, rowid FROM messages WHERE rowid > ?1 ORDER BY rowid LIMIT ?2",
        )?;
        let rows = st.query_map(params![after, limit as i64], Self::message_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// What a chat list shows of the chat with `peer` besides its last message, counted here and
    /// not by reading the chat: incoming messages not read yet, and the newest seq read on each
    /// side (0: none).
    pub fn chat_marks(&self, peer: &UserId) -> Result<ChatMarks> {
        Ok(self.0.query_row(
            "SELECT
               (SELECT count(*) FROM messages WHERE peer = ?1 AND outgoing = 0 AND state = ?2),
               (SELECT coalesce(max(rowid), 0) FROM messages WHERE peer = ?1 AND outgoing = 0 AND state = ?3),
               (SELECT coalesce(max(rowid), 0) FROM messages WHERE peer = ?1 AND outgoing = 1 AND state = ?4)",
            params![&peer[..], MsgState::Received as i64, MsgState::Seen as i64, MsgState::Read as i64],
            |r| Ok(ChatMarks { unread: r.get::<_, i64>(0)? as u32, read_in: r.get(1)?, read_out: r.get(2)? }),
        )?)
    }

    /// Whether `peer` surely holds our card: they added us themselves, a message of theirs opened
    /// here, or one of ours reached them (they said so). Until then a message to them must not pass
    /// a frame on its way there, which may be our card (`node::may_go`).
    pub fn knows_us(&self, peer: &UserId) -> Result<bool> {
        Ok(self.0.query_row(
            "SELECT EXISTS (SELECT 1 FROM contacts WHERE sk = ?1 AND by_them = 1)
                 OR EXISTS (SELECT 1 FROM messages WHERE peer = ?1 AND (outgoing = 0 OR state IN (?2, ?3)))",
            params![&peer[..], MsgState::Delivered as i64, MsgState::Read as i64],
            |r| r.get::<_, bool>(0),
        )?)
    }

    /// Move outgoing messages of `peer` forward to `state`; returns the ids that changed.
    pub fn advance_outgoing(&self, peer: &UserId, ids: &[[u8; 16]], state: MsgState) -> Result<Vec<[u8; 16]>> {
        let mut changed = Vec::new();
        for id in ids {
            let n = self.0.execute(
                "UPDATE messages SET state = ?1 WHERE id = ?2 AND peer = ?3 AND outgoing = 1 AND state < ?1 AND state != ?4",
                params![state as i64, &id[..], &peer[..], MsgState::Failed as i64],
            )?;
            if n == 1 {
                changed.push(*id);
            }
        }
        Ok(changed)
    }

    pub fn set_state(&self, id: &[u8; 16], state: MsgState) -> Result<()> {
        self.0.execute("UPDATE messages SET state = ?1 WHERE id = ?2", params![state as i64, &id[..]])?;
        Ok(())
    }

    /// Incoming messages of `peer` not yet read by us.
    pub fn unread(&self, peer: &UserId) -> Result<Vec<[u8; 16]>> {
        let mut st = self.0.prepare("SELECT id FROM messages WHERE peer = ?1 AND outgoing = 0 AND state = ?2 ORDER BY at_ms")?;
        let rows = st.query_map(params![&peer[..], MsgState::Received as i64], |r| arr::<16>(r, 0))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    // ---- outbox ----

    pub fn enqueue(&self, o: &OutRow) -> Result<()> {
        self.0.execute(
            "INSERT OR IGNORE INTO outbox (frame_id, peer, msg_id, frame, created_ms, attempts, next_ms, last_error) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![&o.frame_id[..], &o.peer[..], o.msg_id.map(|m| m.to_vec()), o.frame, crate::util::now_ms() as i64, o.attempts, o.next_ms as i64, o.last_error],
        )?;
        Ok(())
    }

    /// Frames due now, in the order they were made.
    pub fn due(&self, now_ms: u64, limit: usize) -> Result<Vec<OutRow>> {
        // the due frames' rowids from the index alone (outbox_due holds next_ms and the rowid),
        // then only those rows: walking the table in rowid order instead read every frame waiting
        // for a later try, bytes and all, at each pass of the deliver loop, and an admin's phone
        // after 1.2.44's flood holds hundreds of thousands (tests/outbox_scale.rs)
        let mut st = self.0.prepare(
            "SELECT frame_id, peer, msg_id, frame, attempts, next_ms, last_error FROM outbox WHERE rowid IN
               (SELECT rowid FROM outbox INDEXED BY outbox_due WHERE next_ms <= ?1 ORDER BY rowid LIMIT ?2) ORDER BY rowid",
        )?;
        let rows = st.query_map(params![now_ms as i64, limit as i64], |r| {
            Ok(OutRow {
                frame_id: arr(r, 0)?,
                peer: arr(r, 1)?,
                msg_id: opt_arr(r, 2)?,
                frame: r.get(3)?,
                attempts: r.get(4)?,
                next_ms: r.get::<_, i64>(5)? as u64,
                last_error: r.get(6)?,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn next_due_ms(&self) -> Result<Option<u64>> {
        Ok(self.0.query_row("SELECT MIN(next_ms) FROM outbox", [], |r| r.get::<_, Option<i64>>(0))?.map(|v| v as u64))
    }

    pub fn remove_out(&self, frame_id: &[u8; 16]) -> Result<()> {
        self.0.execute("DELETE FROM outbox WHERE frame_id = ?1", [&frame_id[..]])?;
        self.0.execute("DELETE FROM outbox_once WHERE frame_id = ?1", [&frame_id[..]])?;
        Ok(())
    }

    /// This queued frame is never tried again: a delivery that fails drops it (a group read
    /// receipt, SPEC 7.6).
    pub fn out_once_mark(&self, frame_id: &[u8; 16]) -> Result<()> {
        self.0.execute("INSERT OR IGNORE INTO outbox_once (frame_id) VALUES (?1)", [&frame_id[..]])?;
        Ok(())
    }

    pub fn out_once(&self, frame_id: &[u8; 16]) -> Result<bool> {
        Ok(self.0.query_row("SELECT 1 FROM outbox_once WHERE frame_id = ?1", [&frame_id[..]], |_| Ok(())).optional()?.is_some())
    }

    pub fn retry_later(&self, frame_id: &[u8; 16], next_ms: u64, reason: &str) -> Result<()> {
        self.0.execute(
            "UPDATE outbox SET attempts = attempts + 1, next_ms = ?1, last_error = ?2 WHERE frame_id = ?3",
            params![next_ms as i64, reason, &frame_id[..]],
        )?;
        Ok(())
    }

    /// The contact appeared: try its frames now.
    /// A member we could not reach when we wrote to the group is owed our key of session `sid`
    /// from message `idx` on (the first such message of the session counts). Kept 8 days, past
    /// the session's life.
    pub fn group_owed_add(&self, gid: &[u8], peer: &UserId, sid: &str, key: &[u8], idx: u32, now_ms: u64) -> Result<()> {
        self.0.execute("DELETE FROM group_owed WHERE at_ms < ?1", [now_ms.saturating_sub(8 * 86_400_000) as i64])?;
        self.0.execute(
            "INSERT OR IGNORE INTO group_owed (gid, peer, sid, key, idx, at_ms) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![gid, &peer[..], sid, key, idx as i64, now_ms as i64],
        )?;
        Ok(())
    }

    /// The keys owed to a member of a group, (session id, key, first index), taken out.
    pub fn group_owed_take(&self, gid: &[u8], peer: &UserId) -> Result<Vec<(String, Vec<u8>, u32)>> {
        let mut st = self.0.prepare("SELECT sid, key, idx FROM group_owed WHERE gid = ?1 AND peer = ?2")?;
        let rows = st.query_map(params![gid, &peer[..]], |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? as u32)))?.collect::<std::result::Result<Vec<_>, _>>()?;
        self.0.execute("DELETE FROM group_owed WHERE gid = ?1 AND peer = ?2", params![gid, &peer[..]])?;
        Ok(rows)
    }

    /// A contact seen or reached: its frames waiting for a later try are due now; how many. Through
    /// the contact's index, and frames already due stay as they are: reading the outbox whole and
    /// writing every frame again each time a contact was seen took 1.4 to 3 s under the core's
    /// lock on the official chats' owner's phone (1.2.45, 1 Nov 2026).
    pub fn wake_peer(&self, peer: &UserId) -> Result<usize> {
        Ok(self.0.execute("UPDATE outbox SET next_ms = 0 WHERE peer = ?1 AND next_ms > 0", [&peer[..]])?)
    }

    /// Why a message is still queued: (attempts, last reason).
    pub fn queue_reason(&self, msg_id: &[u8; 16]) -> Result<Option<(u32, Option<String>)>> {
        Ok(self
            .0
            .query_row("SELECT attempts, last_error FROM outbox WHERE msg_id = ?1 ORDER BY rowid LIMIT 1", [&msg_id[..]], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()?)
    }

    /// Every queued frame: try it now (the network changed). Frames already due stay as they are.
    pub fn wake_all(&self) -> Result<usize> {
        Ok(self.0.execute("UPDATE outbox SET next_ms = 0 WHERE next_ms > 0", [])?)
    }

    /// Our visible messages still queued for a contact, and the newest reason.
    pub fn queued_for(&self, peer: &UserId) -> Result<(usize, Option<String>)> {
        // from the messages, by their index: the outbox can be large, and every contact's status
        // line read it whole, twice (1.2.44: 28 s on an S26, 30 Oct 2026)
        let n = self.0.query_row("SELECT count(*) FROM messages WHERE peer = ?1 AND outgoing = 1 AND state = 0", [&peer[..]], |r| r.get::<_, i64>(0))? as usize;
        if n == 0 {
            return Ok((0, None));
        }
        // the reason, among the newest frames only (not through the contact's index, which holds
        // all their frames, unsorted by age)
        let reason = self
            .0
            .query_row(
                "SELECT last_error FROM outbox NOT INDEXED WHERE rowid > (SELECT coalesce(max(rowid), 0) - 5000 FROM outbox)
                 AND peer = ?1 AND msg_id IS NOT NULL AND last_error IS NOT NULL ORDER BY rowid DESC LIMIT 1",
                [&peer[..]],
                |r| r.get(0),
            )
            .optional()?;
        Ok((n, reason))
    }

    /// The queues' indexes ([`LATE_INDEXES`]); at once when they are there.
    pub fn build_late_indexes(&self) -> Result<()> {
        self.0.execute_batch(LATE_INDEXES)?;
        Ok(())
    }

    /// One step of trimming the outbox: the next `limit` frames after rowid `after`; those that
    /// are no message of the user's (`msg_id` empty) and were made before `before_ms` go. The last
    /// rowid looked at, and whether the trim is done (frames are in the order they were made).
    pub fn prune_outbox_step(&self, after: i64, before_ms: u64, limit: usize) -> Result<(i64, bool)> {
        let rows: Vec<(i64, i64, bool)> = {
            let mut st = self.0.prepare("SELECT rowid, created_ms, msg_id IS NULL FROM outbox WHERE rowid > ?1 ORDER BY rowid LIMIT ?2")?;
            let r = st.query_map(params![after, limit as i64], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
            r.collect::<rusqlite::Result<Vec<_>>>()?
        };
        let mut last = after;
        let mut done = rows.len() < limit;
        for (rowid, created, plain) in rows {
            if created as u64 >= before_ms {
                done = true;
                break;
            }
            last = rowid;
            if plain {
                self.0.execute("DELETE FROM outbox WHERE rowid = ?1", [rowid])?;
            }
        }
        Ok((last, done))
    }

    // ---- route memory and reachability (SPEC 15.5, 15.6) ----

    pub fn route_stats(&self, net: &str, peer: &UserId) -> Result<Vec<RouteStat>> {
        let mut st = self.0.prepare("SELECT via, ok, fail, streak, last_ok_ms, last_try_ms, ms FROM routes WHERE net = ?1 AND peer = ?2")?;
        let rows = st.query_map(params![net, &peer[..]], |r| {
            Ok(RouteStat { via: r.get(0)?, ok: r.get(1)?, fail: r.get(2)?, streak: r.get(3)?, last_ok_ms: r.get::<_, i64>(4)? as u64, last_try_ms: r.get::<_, i64>(5)? as u64, ms: r.get::<_, i64>(6)? as u64 })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn route_result(&self, net: &str, peer: &UserId, via: &str, ok: bool, ms: u64, now_ms: u64) -> Result<()> {
        self.0.execute("INSERT OR IGNORE INTO routes (net, peer, via) VALUES (?1, ?2, ?3)", params![net, &peer[..], via])?;
        if ok {
            self.0.execute(
                "UPDATE routes SET ok = ok + 1, streak = 0, last_ok_ms = ?4, last_try_ms = ?4, ms = ?5 WHERE net = ?1 AND peer = ?2 AND via = ?3",
                params![net, &peer[..], via, now_ms as i64, ms as i64],
            )?;
        } else {
            self.0.execute("UPDATE routes SET fail = fail + 1, streak = streak + 1, last_try_ms = ?4 WHERE net = ?1 AND peer = ?2 AND via = ?3", params![net, &peer[..], via, now_ms as i64])?;
        }
        Ok(())
    }

    /// The connection ladder's memory on one network (SPEC 15.9): one row per rung key (an
    /// address's hash `a:..` or a class `c:..`), never an address itself.
    pub fn ladder_stats(&self, net: &str) -> Result<Vec<RouteStat>> {
        let mut st = self.0.prepare("SELECT k, ok, fail, streak, last_ok_ms, last_try_ms, ms FROM ladder WHERE net = ?1")?;
        let rows = st.query_map(params![net], |r| {
            Ok(RouteStat { via: r.get(0)?, ok: r.get(1)?, fail: r.get(2)?, streak: r.get(3)?, last_ok_ms: r.get::<_, i64>(4)? as u64, last_try_ms: r.get::<_, i64>(5)? as u64, ms: r.get::<_, i64>(6)? as u64 })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn ladder_result(&self, net: &str, key: &str, ok: bool, ms: u64, now_ms: u64) -> Result<()> {
        self.0.execute("INSERT OR IGNORE INTO ladder (net, k) VALUES (?1, ?2)", params![net, key])?;
        if ok {
            self.0.execute("UPDATE ladder SET ok = ok + 1, streak = 0, last_ok_ms = ?3, last_try_ms = ?3, ms = ?4 WHERE net = ?1 AND k = ?2", params![net, key, now_ms as i64, ms as i64])?;
        } else {
            self.0.execute("UPDATE ladder SET fail = fail + 1, streak = streak + 1, last_try_ms = ?3 WHERE net = ?1 AND k = ?2", params![net, key, now_ms as i64])?;
        }
        Ok(())
    }

    pub fn set_reach(&self, peer: &UserId, at_ms: u64, via: &str) -> Result<()> {
        self.0.execute(
            "INSERT INTO reach (peer, at_ms, via) VALUES (?1, ?2, ?3) ON CONFLICT(peer) DO UPDATE SET at_ms = excluded.at_ms, via = excluded.via",
            params![&peer[..], at_ms as i64, via],
        )?;
        Ok(())
    }

    /// A volunteer address (binary multiaddr with `/p2p/<id>`) seen working on this device.
    pub fn remember_volunteer(&self, addr: &[u8], seen_ms: u64) -> Result<()> {
        self.0.execute(
            "INSERT INTO volunteers (addr, seen_ms) VALUES (?1, ?2) ON CONFLICT(addr) DO UPDATE SET seen_ms = excluded.seen_ms",
            params![addr, seen_ms as i64],
        )?;
        Ok(())
    }

    /// Remembered volunteer addresses, the most recently seen first; those not seen for `keep_ms`
    /// are dropped.
    pub fn volunteers(&self, now_ms: u64, keep_ms: u64, limit: usize) -> Result<Vec<Vec<u8>>> {
        self.0.execute("DELETE FROM volunteers WHERE seen_ms < ?1", [now_ms.saturating_sub(keep_ms) as i64])?;
        let mut st = self.0.prepare("SELECT addr FROM volunteers ORDER BY seen_ms DESC LIMIT ?1")?;
        let rows = st.query_map([limit as i64], |r| r.get::<_, Vec<u8>>(0))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// A delivery to this contact failed now: it is not reachable, whatever the last success.
    pub fn set_unreach(&self, peer: &UserId, at_ms: u64) -> Result<()> {
        self.0.execute("INSERT INTO unreach (peer, at_ms) VALUES (?1, ?2) ON CONFLICT(peer) DO UPDATE SET at_ms = excluded.at_ms", params![&peer[..], at_ms as i64])?;
        Ok(())
    }

    pub fn unreach(&self, peer: &UserId) -> Result<u64> {
        Ok(self.0.query_row("SELECT at_ms FROM unreach WHERE peer = ?1", [&peer[..]], |r| r.get::<_, i64>(0)).optional()?.unwrap_or(0) as u64)
    }

    /// A contact said «online» or «offline» (SPEC 5.5), at `at_ms` by this phone's clock.
    pub fn presence_set(&self, peer: &UserId, online: bool, at_ms: u64) -> Result<()> {
        let sql = if online {
            "INSERT INTO presence (peer, online_ms) VALUES (?1, ?2) ON CONFLICT(peer) DO UPDATE SET online_ms = excluded.online_ms"
        } else {
            "INSERT INTO presence (peer, offline_ms) VALUES (?1, ?2) ON CONFLICT(peer) DO UPDATE SET offline_ms = excluded.offline_ms"
        };
        self.0.execute(sql, params![&peer[..], at_ms as i64])?;
        Ok(())
    }

    /// When a contact last said «online» and «offline» (0 = never).
    pub fn presence(&self, peer: &UserId) -> Result<(u64, u64)> {
        Ok(self
            .0
            .query_row("SELECT online_ms, offline_ms FROM presence WHERE peer = ?1", [&peer[..]], |r| Ok((r.get::<_, i64>(0)? as u64, r.get::<_, i64>(1)? as u64)))
            .optional()?
            .unwrap_or((0, 0)))
    }

    /// `sk`'s envelope or feed item of `sent_ms` came from `client` (T75; none: a phone before draft
    /// 28), kept when it is the newest known of them. True when what is known of them changed.
    pub fn client_seen(&self, sk: &UserId, client: Option<&crate::envelope::Client>, sent_ms: u64, now: u64) -> Result<bool> {
        let (app, code, name) = client.map_or(("", 0, ""), |c| (c.app.as_str(), c.code, c.name.as_str()));
        let known: Option<(String, u32, String, i64)> = self
            .0
            .query_row("SELECT app, code, name, sent_ms FROM peer_clients WHERE sk = ?1", [&sk[..]], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))
            .optional()?;
        match known {
            Some((_, _, _, t)) if t as u64 >= sent_ms => Ok(false),
            Some((a, c, n, _)) if a == app && c == code && n == name => {
                self.0.execute("UPDATE peer_clients SET sent_ms = ?2 WHERE sk = ?1", params![&sk[..], sent_ms as i64])?;
                Ok(false)
            }
            _ => {
                self.0.execute(
                    "INSERT OR REPLACE INTO peer_clients (sk, app, code, name, sent_ms, at_ms) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    params![&sk[..], app, code, name, sent_ms as i64, now as i64],
                )?;
                Ok(true)
            }
        }
    }

    /// Everyone whose app changed at or after `since_ms` by our clock (T75), oldest change first.
    pub fn clients_since(&self, since_ms: u64) -> Result<Vec<ClientRow>> {
        let mut st = self.0.prepare("SELECT sk, app, code, name, sent_ms, at_ms FROM peer_clients WHERE at_ms >= ?1 ORDER BY at_ms")?;
        let rows = st.query_map([since_ms as i64], |r| {
            Ok(ClientRow { sk: arr(r, 0)?, app: r.get(1)?, code: r.get(2)?, name: r.get(3)?, sent_ms: r.get::<_, i64>(4)? as u64, at_ms: r.get::<_, i64>(5)? as u64 })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Forget every contact's presence (our own last seen was hidden).
    pub fn presence_clear(&self) -> Result<()> {
        self.0.execute("DELETE FROM presence", [])?;
        Ok(())
    }

    /// When a session with this contact last got through, and how.
    pub fn reach(&self, peer: &UserId) -> Result<Option<(u64, String)>> {
        Ok(self.0.query_row("SELECT at_ms, via FROM reach WHERE peer = ?1", [&peer[..]], |r| Ok((r.get::<_, i64>(0)? as u64, r.get(1)?))).optional()?)
    }

    /// Contacts we exchanged messages with most recently, newest first.
    pub fn recent_peers(&self, limit: usize) -> Result<Vec<UserId>> {
        let mut st = self.0.prepare("SELECT peer FROM messages GROUP BY peer ORDER BY MAX(at_ms) DESC LIMIT ?1")?;
        let rows = st.query_map([limit as i64], |r| r.get::<_, Vec<u8>>(0))?;
        Ok(rows.filter_map(|r| r.ok()).filter_map(|v| crate::util::array::<32>(&v)).collect())
    }

    pub fn outbox_len(&self) -> Result<usize> {
        Ok(self.0.query_row("SELECT count(*) FROM outbox", [], |r| r.get::<_, i64>(0))? as usize)
    }

    // ---- duplicates ----

    /// Remember an envelope id; `false` if it was already seen.
    pub fn mark_seen(&self, id: &[u8], at_ms: u64) -> Result<bool> {
        Ok(self.0.execute("INSERT OR IGNORE INTO seen (id, at_ms) VALUES (?1, ?2)", params![id, at_ms as i64])? == 1)
    }

    pub fn was_seen(&self, id: &[u8]) -> Result<bool> {
        Ok(self.0.query_row("SELECT 1 FROM seen WHERE id = ?1", [id], |_| Ok(())).optional()?.is_some())
    }

    /// A frame (by its SPEC 14.7 id) already stored: when the acknowledgement was lost and the
    /// sender offers it again, it is acknowledged, not decrypted a second time (Olm would refuse
    /// a used message key and the sender would mark the message failed).
    pub fn got_frame(&self, id: &[u8; 16]) -> Result<bool> {
        Ok(self.0.query_row("SELECT 1 FROM got WHERE id = ?1", [&id[..]], |_| Ok(())).optional()?.is_some())
    }

    pub fn mark_got(&self, id: &[u8; 16], at_ms: u64) -> Result<()> {
        self.0.execute("INSERT OR IGNORE INTO got (id, at_ms) VALUES (?1, ?2)", params![&id[..], at_ms as i64])?;
        Ok(())
    }

    fn prune_seen(&self, before_ms: u64) -> Result<()> {
        self.0.execute("DELETE FROM seen WHERE at_ms < ?1", [before_ms as i64])?;
        self.0.execute("DELETE FROM got WHERE at_ms < ?1", [before_ms as i64])?;
        Ok(())
    }

    /// Hold a frame that cannot be used yet, to be tried again when what it waits for arrives.
    /// `false` when there is no room (the frame is then refused as before).
    pub fn held_add(&self, fid: &[u8; 16], sender: &UserId, frame: &[u8], now_ms: u64) -> Result<bool> {
        self.0.execute("DELETE FROM held WHERE at_ms < ?1", [now_ms.saturating_sub(HELD_KEEP_MS) as i64])?;
        if self.0.query_row("SELECT 1 FROM held WHERE fid = ?1", [&fid[..]], |_| Ok(())).optional()?.is_some() {
            return Ok(true);
        }
        let all: i64 = self.0.query_row("SELECT COUNT(*) FROM held", [], |r| r.get(0))?;
        let theirs: i64 = self.0.query_row("SELECT COUNT(*) FROM held WHERE sender = ?1", [&sender[..]], |r| r.get(0))?;
        if all >= MAX_HELD || theirs >= MAX_HELD_PER_SENDER {
            return Ok(false);
        }
        self.0.execute("INSERT INTO held (fid, sender, frame, at_ms) VALUES (?1, ?2, ?3, ?4)", params![&fid[..], &sender[..], frame, now_ms as i64])?;
        Ok(true)
    }

    /// The held frames, oldest first: (fid, sender, frame).
    pub fn held(&self, limit: usize) -> Result<Vec<([u8; 16], UserId, Vec<u8>)>> {
        let mut st = self.0.prepare("SELECT fid, sender, frame FROM held ORDER BY at_ms, rowid LIMIT ?1")?;
        let rows = st.query_map([limit as i64], |r| Ok((arr(r, 0)?, arr(r, 1)?, r.get(2)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn held_drop(&self, fid: &[u8; 16]) -> Result<()> {
        self.0.execute("DELETE FROM held WHERE fid = ?1", [&fid[..]])?;
        Ok(())
    }

    pub fn held_has(&self, fid: &[u8; 16]) -> Result<bool> {
        Ok(self.0.query_row("SELECT 1 FROM held WHERE fid = ?1", [&fid[..]], |_| Ok(())).optional()?.is_some())
    }

    /// These feed items are in the official feeds' copy.
    pub fn mirror_mark(&self, ids: &[&[u8]], now_ms: u64) -> Result<()> {
        self.0.execute("DELETE FROM mirror_ids WHERE at_ms < ?1", [now_ms.saturating_sub(MIRROR_IDS_KEEP_MS) as i64])?;
        for id in ids {
            self.0.execute("INSERT OR REPLACE INTO mirror_ids (id, at_ms) VALUES (?1, ?2)", params![id, now_ms as i64])?;
        }
        Ok(())
    }

    pub fn mirror_has(&self, id: &[u8]) -> Result<bool> {
        Ok(self.0.query_row("SELECT 1 FROM mirror_ids WHERE id = ?1", [id], |_| Ok(())).optional()?.is_some())
    }

    // ---- official items on our relays (SPEC 18.7) ----

    /// These feed items are on our relays (read there, or left there): none waits to go there.
    pub fn feed_relay_mark(&self, ids: &[&[u8]], now_ms: u64) -> Result<()> {
        for id in ids {
            self.0.execute("INSERT OR REPLACE INTO feed_relay_ids (id, at_ms) VALUES (?1, ?2)", params![id, now_ms as i64])?;
            self.0.execute("DELETE FROM feed_relay_out WHERE id = ?1", [id])?;
        }
        Ok(())
    }

    pub fn feed_relay_has(&self, id: &[u8]) -> Result<bool> {
        Ok(self.0.query_row("SELECT 1 FROM feed_relay_ids WHERE id = ?1", [id], |_| Ok(())).optional()?.is_some())
    }

    /// A signed item of `feed` goes to our relays, unless it is there or waits already.
    pub fn feed_relay_queue(&self, id: &[u8], feed: &[u8], body: &[u8], ts_ms: u64) -> Result<()> {
        if self.feed_relay_has(id)? {
            return Ok(());
        }
        self.0.execute("INSERT OR IGNORE INTO feed_relay_out (id, feed, body, ts_ms) VALUES (?1, ?2, ?3, ?4)", params![id, feed, body, ts_ms as i64])?;
        Ok(())
    }

    /// Items due to go there by `now_ms`, oldest first: id, feed, signed item, its time, tries.
    pub fn feed_relay_due(&self, now_ms: u64, max: usize) -> Result<Vec<(Vec<u8>, Vec<u8>, Vec<u8>, u64, u32)>> {
        let mut st = self.0.prepare("SELECT id, feed, body, ts_ms, tries FROM feed_relay_out WHERE next_ms <= ?1 ORDER BY ts_ms LIMIT ?2")?;
        let rows = st.query_map(params![now_ms as i64, max as i64], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get::<_, i64>(3)? as u64, r.get(4)?)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn feed_relay_retry(&self, id: &[u8], next_ms: u64, tries: u32) -> Result<()> {
        self.0.execute("UPDATE feed_relay_out SET next_ms = ?2, tries = ?3 WHERE id = ?1", params![id, next_ms as i64, tries])?;
        Ok(())
    }

    /// Items older than `before_ms` no longer go there, and what is known to be there from before
    /// then is forgotten (the relay keeps a week).
    pub fn feed_relay_forget(&self, before_ms: u64) -> Result<()> {
        self.0.execute("DELETE FROM feed_relay_out WHERE ts_ms < ?1", [before_ms as i64])?;
        self.0.execute("DELETE FROM feed_relay_ids WHERE at_ms < ?1", [before_ms as i64])?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn contact(sk: u8) -> Contact {
        Contact { sk: [sk; 32], ik: [2; 32], hk: [3; 32], otk: [4; 32], loc: Some([5; 32]), name: "n".into(), card: vec![1, 2], added_ms: 1, by_them: false, blocked: false, verified: false }
    }

    #[test]
    fn wrong_key_is_refused_and_data_survives_reopen() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("t.db");
        {
            let s = Store::open(&path, &[7; 32]).unwrap();
            s.db().upsert_contact(&contact(1)).unwrap();
        }
        assert!(Store::open(&path, &[8; 32]).is_err());
        let s = Store::open(&path, &[7; 32]).unwrap();
        assert_eq!(s.db().contact(&[1; 32]).unwrap(), Some(contact(1)));
    }

    /// When a session last opened a message is kept beside it and only moves forward; a session
    /// saved before that was kept reads 0 (SPEC 14.4).
    #[test]
    fn a_session_keeps_when_it_last_opened_a_message() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        let row = |rx_ms: u64| SessionRow { id: "s".into(), peer: [1; 32], pickle: vec![1], by_me: true, created_ms: 1, used_ms: 2, rx_ms };
        let rx = || db.sessions(&[1; 32]).unwrap()[0].rx_ms;
        db.save_session(&row(0)).unwrap();
        assert_eq!(rx(), 0);
        db.save_session(&row(50)).unwrap();
        assert_eq!(rx(), 50);
        db.save_session(&row(30)).unwrap();
        assert_eq!(rx(), 50, "never back");
        db.save_session(&row(0)).unwrap();
        assert_eq!(rx(), 50);
        assert!(db.sessions(&[2; 32]).unwrap().is_empty());
    }

    /// A contact holds our card once they added us, wrote to us, or said a message of ours arrived;
    /// a message of ours only queued or sent proves nothing (`node::may_go`).
    #[test]
    fn a_contact_knows_us_once_they_showed_it() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        let msg = |id: u8, peer: u8, outgoing: bool, state: MsgState| MessageRow {
            id: [id; 16], peer: [peer; 32], outgoing, sent_ms: 1, at_ms: 1, kind: "text".into(), envelope: vec![], state, seq: 0,
        };
        db.upsert_contact(&contact(1)).unwrap();
        assert!(!db.knows_us(&[1; 32]).unwrap(), "just added by us");
        db.insert_message(&msg(1, 1, true, MsgState::Sent)).unwrap();
        assert!(!db.knows_us(&[1; 32]).unwrap(), "ours only left this phone");
        db.set_state(&[1; 16], MsgState::Delivered).unwrap();
        assert!(db.knows_us(&[1; 32]).unwrap(), "they said it arrived");
        db.insert_message(&msg(2, 2, false, MsgState::Received)).unwrap();
        assert!(db.knows_us(&[2; 32]).unwrap(), "they wrote to us");
        db.upsert_contact(&Contact { by_them: true, ..contact(3) }).unwrap();
        assert!(db.knows_us(&[3; 32]).unwrap(), "they added us");
    }

    /// A page reads only its own rows: walked through the seq index of its chat, newest first, and
    /// never the chat sorted whole (a long chat must cost what a short one does).
    #[test]
    fn a_page_is_read_through_the_seq_index() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        let plan = |sql: &str| -> String {
            let mut st = db.0.prepare(&format!("EXPLAIN QUERY PLAN {sql}")).unwrap();
            let rows = st.query_map(params![&[1u8; 32][..], 5i64, 10i64], |r| r.get::<_, String>(3)).unwrap();
            rows.map(|r| r.unwrap()).collect::<Vec<_>>().join("; ")
        };
        for sql in [
            "SELECT id FROM messages WHERE peer = ?1 AND rowid < ?2 ORDER BY rowid DESC LIMIT ?3",
            "SELECT id FROM messages WHERE peer = ?1 AND rowid > ?2 ORDER BY rowid LIMIT ?3",
            "SELECT id FROM group_messages WHERE gid = ?1 AND rowid < ?2 AND kind != 'deleted' ORDER BY rowid DESC LIMIT ?3",
            "SELECT id FROM group_messages WHERE gid = ?1 AND rowid > ?2 AND kind != 'deleted' ORDER BY rowid LIMIT ?3",
        ] {
            let p = plan(sql);
            eprintln!("{sql}\n  -> {p}");
            assert!(p.contains("_seq") && !p.contains("TEMP B-TREE"), "{sql}: {p}");
        }
    }

    /// The queue is looked up by contact, by message and by when it is due through its own
    /// indexes, never read whole: the official chats' owner's outbox is the biggest, and a contact
    /// seen there read it all under the core's lock (1.2.45, 1 Nov 2026).
    #[test]
    fn the_queue_is_looked_up_through_its_indexes() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        db.build_late_indexes().unwrap();
        let plan = |sql: &str| -> String {
            let mut st = db.0.prepare(&format!("EXPLAIN QUERY PLAN {sql}")).unwrap();
            let args = vec![5i64; st.parameter_count()];
            let rows = st.query_map(rusqlite::params_from_iter(args), |r| r.get::<_, String>(3)).unwrap();
            rows.map(|r| r.unwrap()).collect::<Vec<_>>().join("; ")
        };
        for (sql, index) in [
            ("UPDATE outbox SET next_ms = 0 WHERE peer = ?1 AND next_ms > 0", "outbox_peer"),
            ("SELECT attempts, last_error FROM outbox WHERE msg_id = ?1 ORDER BY rowid LIMIT 1", "outbox_msg"),
            ("DELETE FROM outbox WHERE msg_id IN (SELECT id FROM messages WHERE peer = ?1 AND rowid <= ?2)", "outbox_msg"),
            (
                "SELECT peer, id, idx, attempts FROM blob_out b WHERE next_ms <= ?1
                 AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.peer = b.peer AND o.next_ms <= ?1)
                 ORDER BY next_ms, rowid LIMIT ?2",
                "outbox_peer",
            ),
            ("SELECT MIN(next_ms) FROM blob_out", "blob_out_due"),
            // the mailboxes' own queue and parts, on every phone (their store held the lock 77 ms a
            // time on an A50)
            ("SELECT id, frame FROM gas_pending WHERE next_ms <= ?1 ORDER BY next_ms LIMIT ?2", "gas_pending_due"),
            ("DELETE FROM gas_parts WHERE at_ms < ?1", "gas_parts_at"),
            (
                "SELECT last_error FROM outbox NOT INDEXED WHERE rowid > (SELECT coalesce(max(rowid), 0) - 5000 FROM outbox)
                 AND peer = ?1 AND msg_id IS NOT NULL AND last_error IS NOT NULL ORDER BY rowid DESC LIMIT 1",
                "INTEGER PRIMARY KEY",
            ),
        ] {
            let p = plan(sql);
            eprintln!("{sql}\n  -> {p}");
            let whole = p.split("; ").any(|step| step.starts_with("SCAN"));
            assert!(p.contains(index) && !whole && !p.contains("TEMP B-TREE"), "{sql}: {p}");
        }
    }

    #[test]
    fn held_frames_are_bounded_and_expire() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        let fid = |n: u32| -> [u8; 16] {
            let mut f = [0u8; 16];
            f[..4].copy_from_slice(&n.to_le_bytes());
            f
        };
        // one sender fills its share; a second copy of a held frame is no new row
        for n in 0..MAX_HELD_PER_SENDER as u32 {
            assert!(db.held_add(&fid(n), &[1; 32], b"x", 1_000).unwrap());
        }
        assert!(db.held_add(&fid(0), &[1; 32], b"x", 1_000).unwrap());
        assert!(!db.held_add(&fid(9_999), &[1; 32], b"x", 1_000).unwrap(), "no room for this sender");
        assert!(db.held_add(&fid(10_000), &[2; 32], b"y", 1_000).unwrap(), "another sender has room");
        assert_eq!(db.held(1_000).unwrap().len(), MAX_HELD_PER_SENDER as usize + 1);
        assert_eq!(db.held(1).unwrap()[0].0, fid(0), "oldest first");
        db.held_drop(&fid(0)).unwrap();
        assert!(!db.held_has(&fid(0)).unwrap());
        // all of them expire with the mailbox's own item life
        assert!(db.held_add(&fid(20_000), &[3; 32], b"z", 1_000 + HELD_KEEP_MS + 1).unwrap());
        assert_eq!(db.held(1_000).unwrap().len(), 1);
    }

    #[test]
    fn outgoing_state_only_moves_forward() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        let m = MessageRow { id: [9; 16], peer: [1; 32], outgoing: true, sent_ms: 1, at_ms: 1, kind: "text".into(), envelope: vec![], state: MsgState::Queued, seq: 0 };
        assert!(db.insert_message(&m).unwrap());
        assert!(!db.insert_message(&m).unwrap());
        assert_eq!(db.advance_outgoing(&[1; 32], &[[9; 16]], MsgState::Delivered).unwrap().len(), 1);
        assert!(db.advance_outgoing(&[1; 32], &[[9; 16]], MsgState::Sent).unwrap().is_empty());
        // a receipt from someone else changes nothing
        assert!(db.advance_outgoing(&[2; 32], &[[9; 16]], MsgState::Read).unwrap().is_empty());
        assert_eq!(db.message(&[9; 16]).unwrap().unwrap().state, MsgState::Delivered);
    }

    #[test]
    fn seq_is_the_storing_order_and_stays() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        for (i, at) in [(1u8, 30u64), (2, 10), (3, 20)] {
            let m = MessageRow { id: [i; 16], peer: [1; 32], outgoing: i == 2, sent_ms: at, at_ms: at, kind: "text".into(), envelope: vec![], state: MsgState::Received, seq: 0 };
            db.insert_message(&m).unwrap();
        }
        let seqs: Vec<(u8, i64)> = db.messages(&[1; 32], 10).unwrap().iter().map(|m| (m.id[0], m.seq)).collect();
        // listed by time, numbered by the order they were stored
        assert_eq!(seqs, vec![(2, 2), (3, 3), (1, 1)]);
        db.set_state(&[1; 16], MsgState::Seen).unwrap();
        assert_eq!(db.message(&[1; 16]).unwrap().unwrap().seq, 1);
    }

    #[test]
    fn deleting_takes_the_queued_copy_and_never_reuses_a_seq() {
        let mut s = Store::open_in_memory(&[1; 32]).unwrap();
        let row = |i: u8, peer: u8| MessageRow { id: [i; 16], peer: [peer; 32], outgoing: true, sent_ms: i as u64, at_ms: i as u64, kind: "text".into(), envelope: vec![], state: MsgState::Queued, seq: 0 };
        s.transaction(|db| {
            for (i, peer) in [(1u8, 1u8), (2, 1), (3, 2), (4, 1)] {
                db.insert_message(&row(i, peer))?;
                db.enqueue(&OutRow { frame_id: [i; 16], peer: [peer; 32], msg_id: Some([i; 16]), frame: vec![i], attempts: 0, next_ms: 0, last_error: None })?;
            }
            // a receipt is not a message and stays
            db.enqueue(&OutRow { frame_id: [9; 16], peer: [1; 32], msg_id: None, frame: vec![9], attempts: 0, next_ms: 0, last_error: None })
        })
        .unwrap();
        let db = s.db();
        // the newest one (seq 4) and one that is not there
        assert_eq!(db.delete_messages(&[4, 99]).unwrap(), 1);
        assert_eq!(db.outbox_len().unwrap(), 4);
        db.insert_message(&row(5, 1)).unwrap();
        db.enqueue(&OutRow { frame_id: [5; 16], peer: [1; 32], msg_id: Some([5; 16]), frame: vec![5], attempts: 0, next_ms: 0, last_error: None }).unwrap();
        assert_eq!(db.message(&[5; 16]).unwrap().unwrap().seq, 5, "seq 4 is not given again");
        // peer 1 up to seq 2: messages 1 and 2 go, 5 and the other peer's 3 stay
        assert_eq!(db.clear_messages(&[1; 32], 2).unwrap(), 2);
        let left: Vec<u8> = db.messages(&[1; 32], 10).unwrap().iter().map(|m| m.id[0]).collect();
        assert_eq!(left, vec![5]);
        assert_eq!(db.messages(&[2; 32], 10).unwrap().len(), 1);
        let queued: Vec<u8> = db.due(10, 10).unwrap().iter().map(|o| o.frame_id[0]).collect();
        assert_eq!(queued, vec![3, 9, 5]);
        assert_eq!(db.clear_messages(&[1; 32], 0).unwrap(), 1);
        db.insert_message(&row(6, 1)).unwrap();
        assert_eq!(db.message(&[6; 16]).unwrap().unwrap().seq, 6);
    }

    #[test]
    fn outbox_order_and_retry() {
        let mut s = Store::open_in_memory(&[1; 32]).unwrap();
        s.transaction(|db| {
            for i in 0..3u8 {
                db.enqueue(&OutRow { frame_id: [i; 16], peer: [1; 32], msg_id: None, frame: vec![i], attempts: 0, next_ms: 0, last_error: None })?;
            }
            Ok(())
        })
        .unwrap();
        let db = s.db();
        assert_eq!(db.due(10, 10).unwrap().iter().map(|o| o.frame_id[0]).collect::<Vec<_>>(), vec![0, 1, 2]);
        db.retry_later(&[1; 16], 500, "timeout").unwrap();
        assert_eq!(db.due(10, 10).unwrap().len(), 2);
        assert_eq!(db.next_due_ms().unwrap(), Some(0));
        db.remove_out(&[0; 16]).unwrap();
        db.remove_out(&[2; 16]).unwrap();
        assert_eq!(db.next_due_ms().unwrap(), Some(500));
        assert_eq!(db.wake_peer(&[1; 32]).unwrap(), 1);
        assert_eq!(db.due(10, 10).unwrap()[0].last_error.as_deref(), Some("timeout"));
    }
}
