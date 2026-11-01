//! Files, photos, voice and video on the node (SPEC 12, 17; phase 4).
//!
//! - Sending: the file is sealed into this phone's blob folder (`blob.rs`), and a `Media` message
//!   with the file's key, id and preview goes like any message.
//! - Receiving: the message is stored at once (its preview shows); the file itself is asked for
//!   with a `BlobRequest` (small files at once, others when the app asks), and comes back as
//!   frames of kind 3, one sealed chunk each, written in place and checked; when every chunk is
//!   here the whole file is checked against its id.
//! - Chunks wait in their own queue and go only when that contact has no message waiting: text
//!   is never stuck behind a video.

use super::{enqueue, Event, Inner, Node, State};
use crate::blob::{self, BlobId};
use crate::envelope::{clean_entities, Body, BlobRequest, Entity, Envelope, Media};
use crate::frame::{self, KIND_BLOB};
use crate::store::{BlobOut, BlobRow, Db};
use crate::transport::direct::frame_id;
use crate::transport::{Transport, Verdict};
use crate::util::{array, now_ms};
use crate::{Error, Result, UserId};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// Files up to this size are fetched as soon as their message arrives.
pub const AUTO_FETCH: u64 = 1024 * 1024;
/// Chunks per delivery attempt.
pub const CHUNKS_PER_SEND: usize = 4;
/// Routes one contact's file chunks are spread over at once (`Inner::blobs_to`).
pub const LANES: usize = 4;
const HEADER: usize = 32 + 4 + 4;

/// What the app tells about a file it sends (the core seals the bytes itself).
#[derive(Debug, Clone, Default)]
pub struct MediaInfo {
    /// `Media.Kind`: 0 file, 1 photo, 2 video, 3 voice, 4 round video, 5 GIF.
    pub kind: i32,
    pub mime: String,
    pub name: String,
    pub caption: String,
    /// The caption's formatting and mentions (SPEC 5 `Entity`); bad ones are dropped.
    pub caption_entities: Vec<Entity>,
    /// A tiny preview (at most 4 KiB), shown before the file is here.
    pub thumb: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub duration_ms: u32,
    pub waveform: Vec<u8>,
    /// The album it is one of (SPEC 5 `Media.album_id`): the files sent together share one id,
    /// so the other phone shows them together; empty for a file on its own.
    pub album_id: Vec<u8>,
}

/// An album id's length (SPEC 5): longer ones are cut, so a bad one stays small.
pub const ALBUM_ID_LEN: usize = 16;

pub(super) fn blob_path(dir: &Path, id: &BlobId) -> PathBuf {
    dir.join(format!("{}.blob", hex::encode(id)))
}

/// A frame of kind 3: `blob_id || index || total || sealed chunk`.
pub(super) fn chunk_frame(id: &BlobId, index: u32, total: u32, sealed: &[u8]) -> Result<Vec<u8>> {
    let mut cipher = Vec::with_capacity(HEADER + sealed.len());
    cipher.extend_from_slice(id);
    cipher.extend_from_slice(&index.to_be_bytes());
    cipher.extend_from_slice(&total.to_be_bytes());
    cipher.extend_from_slice(sealed);
    frame::encode_padded(&[], KIND_BLOB, &cipher)
}

fn parse_chunk(cipher: &[u8]) -> Option<(BlobId, u32, u32, &[u8])> {
    if cipher.len() < HEADER + blob::TAG {
        return None;
    }
    let id: BlobId = cipher[..32].try_into().ok()?;
    let index = u32::from_be_bytes(cipher[32..36].try_into().ok()?);
    let total = u32::from_be_bytes(cipher[36..40].try_into().ok()?);
    Some((id, index, total, &cipher[40..]))
}

/// Ask `peer` for the chunks of `id` we do not have yet (inside a transaction).
pub(super) fn request(db: &Db, identity: &crate::identity::Identity, peer: &UserId, row: &BlobRow) -> Result<bool> {
    let Some(c) = db.contact(peer)? else { return Ok(false) };
    let from = row.first_missing().unwrap_or(row.chunks);
    if from >= row.chunks {
        return Ok(false);
    }
    let env = Envelope::new(Body::BlobRequest(BlobRequest { blob_id: row.id.to_vec(), from }));
    enqueue(db, identity, &c, &env, false)?;
    Ok(true)
}

/// The largest file Gereh sends or stores, 20 MiB (Ehsan, 30 Oct 2026; the relay refuses more too).
pub const MAX_FILE: u64 = 20 * 1024 * 1024;

impl Inner {
    /// A frame of kind 3 from `from`: one chunk of a file we asked for.
    pub(super) fn on_blob_frame(&self, from: UserId, bytes: &[u8], cipher: &[u8]) -> Verdict {
        let Some((id, index, total, sealed)) = parse_chunk(cipher) else { return Verdict::Dropped };
        let fid = frame_id(bytes);
        let now = now_ms();
        let mut events = Vec::new();
        {
            let mut guard = self.state.lock().expect("state");
            let State { store, .. } = &mut *guard;
            let row = match store.db().blob(&id) {
                Ok(Some(r)) => r,
                Ok(None) => return Verdict::Dropped, // nothing we asked for
                Err(_) => return Verdict::Retry,
            };
            if row.complete || total != row.chunks || index >= total || row.has(index) {
                return Verdict::Stored;
            }
            if row.source.is_some_and(|s| s != from) {
                return Verdict::Dropped;
            }
            let path = blob_path(&self.blob_dir, &id);
            if std::fs::create_dir_all(&self.blob_dir).is_err() || blob::write_chunk(&path, index, sealed).is_err() {
                log::debug!("node: a file chunk could not be written");
                return Verdict::Retry;
            }
            let mut row = row;
            row.set(index);
            let complete = row.missing() == 0;
            if complete && blob::verify_id(&path, &id, row.size).is_err() {
                // every chunk came but the whole does not match: start over
                let _ = std::fs::remove_file(&path);
                row.clear();
                let _ = store.db().blob_save(&row);
                log::debug!("node: a file did not match its id");
                return Verdict::Dropped;
            }
            row.complete = complete;
            let saved = store.transaction(|db| {
                db.mark_got(&fid, now)?;
                db.blob_save(&row)?;
                // an official file our relays lacked when we looked: it goes there now (SPEC 18.3)
                if complete {
                    if let Some(f) = db.feed_file(&id)?.filter(|f| f.lacked && f.put == 0 && row.size <= crate::transport::nostr::files::MAX_SIZE) {
                        db.feed_file_put(&f.id, 1, 0, 0)?;
                    }
                }
                Ok(())
            });
            if saved.is_err() {
                return Verdict::Retry;
            }
            let have = row.chunks - row.missing();
            if complete || have % 8 == 0 {
                events.push(Event::Blob { id, have, total });
            }
            if complete {
                log::info!("node: a file arrived whole");
            }
        }
        for e in events {
            self.emit(e);
        }
        Verdict::Stored
    }

    /// Send chunks that are due, for contacts with no message waiting. True if any were sent.
    pub(super) fn deliver_blobs(self: &Arc<Self>, transports: &[Arc<dyn Transport>], network: &str) -> bool {
        let now = now_ms();
        let due: Vec<BlobOut> = {
            let st = self.state.lock().expect("state");
            st.store.db().blob_out_due(now, 64 * LANES).unwrap_or_default()
        };
        if due.is_empty() {
            return false;
        }
        // file chunks go to a contact with nothing else on its way; a message may then pass them
        let busy: HashMap<UserId, u8> = self.delivering.lock().expect("delivering").clone();
        let mut by_peer: Vec<(UserId, Vec<BlobOut>)> = Vec::new();
        for o in due.into_iter().filter(|o| !busy.contains_key(&o.peer)) {
            match by_peer.iter_mut().find(|(p, _)| *p == o.peer) {
                Some((_, v)) if v.len() < CHUNKS_PER_SEND * LANES => v.push(o),
                Some(_) => {}
                None => by_peer.push((o.peer, vec![o])),
            }
        }
        let mut any = false;
        for (peer, rows) in by_peer {
            if !self.begin_delivery(peer, crate::node::LANE_OTHER) {
                break;
            }
            any = true;
            let (i, t, net) = (self.clone(), transports.to_vec(), network.to_string());
            let spawned = std::thread::Builder::new().name("mlmchat-deliver-file".into()).spawn(move || {
                i.blobs_to(&t, &net, peer, rows);
                i.end_delivery(peer, crate::node::LANE_OTHER);
            });
            if spawned.is_err() {
                self.end_delivery(peer, crate::node::LANE_OTHER);
            }
        }
        any
    }

    /// One contact's file chunks, in groups of `CHUNKS_PER_SEND` that go at once, each starting
    /// on another of the routes that work for them (a group that starts on the Google relays
    /// goes to another relay than the one before): the load is shared, and a route that fails
    /// hands its group to the next at once (`try_routes`). Before, a file went four chunks at a
    /// time down one route (Ehsan, 26 Oct 2026: «برای تقسیم بار از همه راه ها هم زمان استفاده
    /// کنه مخصوصا برای ارسال فایل ها»).
    fn blobs_to(self: &Arc<Self>, transports: &[Arc<dyn Transport>], network: &str, peer: UserId, rows: Vec<BlobOut>) {
        let now = now_ms();
        let later = |rows: &[BlobOut]| {
            let st = self.state.lock().expect("state");
            for r in rows {
                let _ = st.store.db().blob_out_later(r, crate::queue::next_try_ms(r.attempts, now));
            }
        };
        let Some(info) = self.peer_info(&peer) else {
            later(&rows);
            return;
        };
        let mut frames = Vec::new();
        let mut sendable = Vec::new();
        for r in &rows {
            let built = (|| {
                let b = self.state.lock().expect("state").store.db().blob(&r.id)?.ok_or(Error::NotFound("blob"))?;
                let sealed = blob::read_chunk(&blob_path(&self.blob_dir, &r.id), r.index, b.chunks, b.size)?;
                chunk_frame(&r.id, r.index, b.chunks, &sealed)
            })();
            match built {
                Ok(f) => {
                    frames.push(f);
                    sendable.push(r.clone());
                }
                Err(_) => {
                    let _ = self.state.lock().expect("state").store.db().blob_out_done(r);
                }
            }
        }
        let lanes: Vec<usize> = super::route_list(self, transports, network, &peer).into_iter().take(LANES).collect();
        let groups: Vec<(Vec<BlobOut>, Vec<Vec<u8>>)> = sendable.chunks(CHUNKS_PER_SEND).zip(frames.chunks(CHUNKS_PER_SEND)).map(|(r, f)| (r.to_vec(), f.to_vec())).collect();
        let handles: Vec<_> = groups
            .into_iter()
            .enumerate()
            .map(|(k, (rows, frames))| {
                let first = (!lanes.is_empty()).then(|| lanes[k % lanes.len()]);
                let (i, t, net, info) = (self.clone(), transports.to_vec(), network.to_string(), info.clone());
                std::thread::spawn(move || {
                    let result = super::try_routes(&i, &t, &net, &info, &frames, first);
                    let st = i.state.lock().expect("state");
                    let db = st.store.db();
                    let now = now_ms();
                    let sent = result.as_ref().map_or(0, |s| s.verdicts.len());
                    for r in &rows[..sent.min(rows.len())] {
                        let _ = db.blob_out_done(r);
                    }
                    for r in &rows[sent.min(rows.len())..] {
                        let _ = db.blob_out_later(r, crate::queue::next_try_ms(r.attempts, now));
                    }
                })
            })
            .collect();
        for h in handles {
            let _ = h.join();
        }
    }
}

/// The short id an app (Telegram) shows a file under: the first 8 bytes of its id, positive.
pub fn short_id(id: &BlobId) -> i64 {
    (i64::from_be_bytes(id[..8].try_into().expect("8 bytes")) & i64::MAX).max(1)
}

/// Inside the Olm transaction (`on_frame`): a `Media` message arrived from `from`. Its file is
/// expected from them; a small one is asked for at once.
pub(super) fn on_media(db: &Db, identity: &crate::identity::Identity, from: &UserId, m: &Media) -> Result<bool> {
    let Some(id) = array::<32>(&m.blob_id) else { return Ok(false) };
    if let Some(key) = array::<32>(&m.key) {
        db.media_key_save(short_id(&id), &id, &key, m.size)?;
    }
    if db.blob(&id)?.is_some() {
        return Ok(false);
    }
    let chunks = blob::chunk_count_sealed(m.size);
    let row = BlobRow::new(id, m.size, chunks, Some(*from));
    db.blob_save(&row)?;
    if m.size <= AUTO_FETCH {
        return request(db, identity, from, &row);
    }
    Ok(false)
}

/// Inside the Olm transaction: `from` asks for chunks of a file we have whole.
pub(super) fn on_request(db: &Db, from: &UserId, r: &BlobRequest) -> Result<bool> {
    let Some(id) = array::<32>(&r.blob_id) else { return Ok(false) };
    let Some(row) = db.blob(&id)? else { return Ok(false) };
    if !row.complete {
        return Ok(false);
    }
    let mut any = false;
    for index in r.from..row.chunks {
        db.blob_out_add(from, &id, index)?;
        any = true;
    }
    Ok(any)
}

/// Inside a transaction: a file (a profile or group photo) is expected from `from`, who has it
/// whole. Its key is kept under its short id; a small one is asked for at once. True if a
/// request was queued.
pub(super) fn expect_file(db: &Db, identity: &crate::identity::Identity, from: &UserId, id: &BlobId, key: &[u8; 32], size: u64) -> Result<bool> {
    db.media_key_save(short_id(id), id, key, size)?;
    if db.blob(id)?.is_some() {
        return Ok(false);
    }
    let row = BlobRow::new(*id, size, blob::chunk_count_sealed(size), Some(*from));
    db.blob_save(&row)?;
    if size <= AUTO_FETCH {
        return request(db, identity, from, &row);
    }
    Ok(false)
}

impl Inner {
    /// Seal a file into this phone's blob folder, whole and ready to send to whoever asks; its
    /// key is kept under its short id.
    pub(super) fn seal_file(&self, path: &Path) -> Result<blob::Sealed> {
        // no file over 20 MB anywhere in Gereh: chats, groups, channels, official posts (Ehsan, 30 Oct 2026)
        if std::fs::metadata(path)?.len() > MAX_FILE {
            return Err(Error::Session("a file is at most 20 MB"));
        }
        let tmp = self.blob_dir.join(format!("out-{}.tmp", hex::encode(crate::util::random::<8>())));
        std::fs::create_dir_all(&self.blob_dir)?;
        let sealed = blob::seal_file(path, &tmp)?;
        std::fs::rename(&tmp, blob_path(&self.blob_dir, &sealed.blob_id))?;
        let mut row = BlobRow::new(sealed.blob_id, sealed.size, sealed.chunks, None);
        row.fill();
        row.complete = true;
        let st = self.state.lock().expect("state");
        st.store.db().blob_save(&row)?;
        st.store.db().media_key_save(short_id(&sealed.blob_id), &sealed.blob_id, &sealed.key, sealed.size)?;
        Ok(sealed)
    }
}

impl Node {
    /// Send a file (photo, video, voice, document) to a contact. The core seals it; the message
    /// carries the key, the id and the preview. Returns the message id.
    pub fn send_media(&self, peer: &UserId, path: &Path, info: &MediaInfo) -> Result<[u8; 16]> {
        self.send_media_reply(peer, path, info, 0)
    }

    /// A file in answer to message `reply_to` of the chat (its seq here; 0 for none) - see
    /// [`Node::send_text_reply`].
    pub fn send_media_reply(&self, peer: &UserId, path: &Path, info: &MediaInfo, reply_to: i64) -> Result<[u8; 16]> {
        let reply = self.reply_id(peer, reply_to)?;
        let media = self.seal_media(path, info)?;
        let mut env = Envelope::new(Body::Media(media));
        env.reply_to = reply;
        self.send_envelope(peer, &env)
    }

    /// Post a file in a group or channel (the same sealing; the message goes with Megolm).
    pub fn group_send_media(&self, gid: &[u8], path: &Path, info: &MediaInfo) -> Result<[u8; 16]> {
        self.group_send_media_reply(gid, path, info, 0)
    }

    /// A file in answer to message `reply_to` of the group (its seq here; 0 for none).
    pub fn group_send_media_reply(&self, gid: &[u8], path: &Path, info: &MediaInfo, reply_to: i64) -> Result<[u8; 16]> {
        let reply = self.group_reply_id(gid, reply_to)?;
        let media = self.seal_media(path, info)?;
        let mut env = Envelope::new(Body::Media(media));
        env.group_id = gid.to_vec();
        env.reply_to = reply;
        self.group_send(&env)
    }

    pub(super) fn seal_media(&self, path: &Path, info: &MediaInfo) -> Result<Media> {
        let sealed = self.inner.seal_file(path)?;
        Ok(Media {
            kind: info.kind,
            mime: info.mime.clone(),
            size: sealed.size,
            blob_id: sealed.blob_id.to_vec(),
            key: sealed.key.to_vec(),
            thumb: info.thumb.clone(),
            width: info.width,
            height: info.height,
            duration_ms: info.duration_ms,
            name: info.name.clone(),
            waveform: info.waveform.clone(),
            caption: info.caption.clone(),
            caption_entities: clean_entities(&info.caption, info.caption_entities.clone()),
            album_id: info.album_id.iter().take(ALBUM_ID_LEN).copied().collect(),
        })
    }

    /// The file an app's short id names: (its id, key, sealed size), if this phone knows it.
    pub fn media_by_short(&self, short: i64) -> Result<Option<([u8; 32], [u8; 32], u64)>> {
        self.inner.state.lock().expect("state").store.db().media_key(short)
    }

    /// Ask for the file of a received message (one larger than `AUTO_FETCH`, or to resume). A file
    /// of an official post is read from our own relays first (SPEC 18.3); what they lack comes
    /// from the contact that passed the post on.
    pub fn fetch_media(&self, blob_id: &BlobId) -> Result<bool> {
        let (woke, official) = {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let db = store.db();
            let official = db.feed_file(blob_id)?.is_some();
            let row = match db.blob(blob_id)? {
                Some(r) => r,
                // an official post read from the official copy: its key is known, its file not yet
                None if official => {
                    let (_, _, size) = db.media_key(short_id(blob_id))?.ok_or(Error::NotFound("file"))?;
                    let r = BlobRow::new(*blob_id, size, blob::chunk_count_sealed(size), None);
                    db.blob_save(&r)?;
                    r
                }
                None => return Err(Error::NotFound("file")),
            };
            if row.complete {
                return Ok(false);
            }
            if official && row.size <= crate::transport::nostr::files::MAX_SIZE {
                (false, true)
            } else {
                let Some(src) = row.source else { return Ok(false) };
                let identity: &crate::identity::Identity = identity;
                (store.transaction(|db| request(db, identity, &src, &row))?, false)
            }
        };
        if official {
            self.inner.fetch_official(*blob_id);
            return Ok(true);
        }
        if woke {
            self.inner.waker.wake();
        }
        Ok(woke)
    }

    /// How much of a file is here: (chunks here, chunks in all, whole and checked).
    pub fn media_status(&self, blob_id: &BlobId) -> Result<Option<(u32, u32, bool)>> {
        let st = self.inner.state.lock().expect("state");
        Ok(st.store.db().blob(blob_id)?.map(|r| (r.chunks - r.missing(), r.chunks, r.complete)))
    }

    /// Write the plain file of a whole blob to `out` with its key (from the message).
    pub fn open_media(&self, blob_id: &BlobId, key: &[u8; 32], out: &Path) -> Result<u64> {
        let row = self.inner.state.lock().expect("state").store.db().blob(blob_id)?.ok_or(Error::NotFound("file"))?;
        if !row.complete {
            return Err(Error::Frame("the file is not here yet"));
        }
        blob::open_file(&blob_path(&self.inner.blob_dir, blob_id), key, blob_id, row.size, out)
    }
}
