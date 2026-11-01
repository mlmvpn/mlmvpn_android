//! The official chats' files on our own relays (SPEC 18.3, T63; `transport::nostr::files`).
//!
//! - Every new official item with a file (a post's photo, voice, video or file, or the chat's
//!   photo) is known by its file from the moment it is kept: its key under its short id, so the app
//!   can ask for it however the item came (from a contact or from the official copy).
//! - The phone that posts leaves the sealed file there at once, and again later while it fails, up
//!   to three days. A phone that looked there in vain and then got the file whole from a contact
//!   leaves it there too, so a file of an older app's post gets there as well.
//! - A phone that wants an official file reads it there first; what is missing then comes from the
//!   contact that passed the post on, if any.

use super::{Event, Inner, Node};
use crate::blob::{self, BlobId};
use crate::envelope::{Body, Envelope};
use crate::feed::{FeedInfoItem, FeedItem, Item};
use crate::store::{BlobRow, Db, FeedFileRow};
use crate::transport::nostr::files;
use crate::util::{array, now_ms};
use crate::Result;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

/// A file is not read from our relays again for this long after a look (an app asks many times).
pub const FETCH_AGAIN_MS: u64 = 60_000;
/// Waits after a failed try to leave a file there: doubling from this, up to an hour.
pub const PUT_RETRY_MS: u64 = 60_000;
/// A file not left there by then is given up (our relays would keep it two weeks from its post).
pub const PUT_GIVE_UP_MS: u64 = 3 * 24 * 3600 * 1000;
/// How long the connection to a relay waits for an answer.
pub const RELAY_TIMEOUT: Duration = Duration::from_secs(20);
/// Chunks written in one go.
const PUT_CHUNKS: u32 = 8;

/// The file an official item carries: (id, key, sealed size).
pub(super) fn file_of(item: &FeedItem) -> Option<(BlobId, [u8; 32], u64)> {
    match &item.item {
        Some(Item::Post(p)) => match Envelope::from_bytes(p).ok()?.body {
            Some(Body::Media(m)) => Some((array::<32>(&m.blob_id)?, array::<32>(&m.key)?, m.size)),
            _ => None,
        },
        Some(Item::Info(FeedInfoItem { photo: Some(p) })) => Some((array::<32>(&p.blob_id)?, array::<32>(&p.key)?, p.size)),
        _ => None,
    }
}

/// Inside a transaction (`feeds::keep`): a new official item. If it has a file, its key is kept
/// under its short id and the file is known as official.
pub(super) fn register(db: &Db, item: &FeedItem) -> Result<()> {
    let Some((id, key, size)) = file_of(item) else { return Ok(()) };
    if matches!(item.item, Some(Item::Info(_))) {
        db.feed_photo_add(&id)?;
    }
    db.media_key_save(super::media::short_id(&id), &id, &key, size)?;
    db.feed_file_add(&id, &item.feed_id, item.ts_ms)
}

/// Inside a transaction: our own official item with a file goes to our relays now.
pub(super) fn put_soon(db: &Db, item: &FeedItem) -> Result<()> {
    let Some((id, _, size)) = file_of(item) else { return Ok(()) };
    register(db, item)?;
    if size <= files::MAX_SIZE {
        db.feed_file_put(&id, 1, 0, 0)?;
    }
    Ok(())
}

/// meta key: the items kept before this build had their files made known once.
const BACKFILLED: &str = "feed_files_v1";

/// Inside a transaction, once (`Node::set_feeds` at start): the items kept before files were made
/// known have them made known now, so the posts already in the official chats can be asked for;
/// and our own files of the last two weeks go to our relays.
pub(super) fn backfill(db: &Db, feeds: &[crate::feed::FeedDescriptor], me: &crate::UserId) -> Result<()> {
    if db.meta(BACKFILLED)?.is_some() {
        return Ok(());
    }
    let since = now_ms().saturating_sub(files::EXPIRE_S * 1000);
    for d in feeds {
        for s in db.feed_items(&d.feed_id)? {
            let Ok(item) = s.open() else { continue };
            if item.author[..] == me[..] && item.ts_ms >= since && db.blob(&file_of(&item).map(|f| f.0).unwrap_or([0; 32]))?.is_some_and(|b| b.complete) {
                put_soon(db, &item)?;
            } else {
                register(db, &item)?;
            }
        }
    }
    db.set_meta(BACKFILLED, &[1])
}

/// meta key: the official chats' photos went to our relays for good once (T66).
const PHOTOS_FOR_GOOD: &str = "feed_photos_v1";

/// Inside a transaction, once (`Node::set_feeds` at start): every official chat's photo this phone
/// holds whole goes to our relays as a photo, kept for good, whoever set it and however long ago:
/// a photo set before was there two weeks at most, or never (Ehsan, 30 Oct 2026). The relay keeps
/// the first copy of each part; a later one is refused as a duplicate.
pub(super) fn backfill_photos(db: &Db, feeds: &[crate::feed::FeedDescriptor]) -> Result<()> {
    if db.meta(PHOTOS_FOR_GOOD)?.is_some() {
        return Ok(());
    }
    for d in feeds {
        for s in db.feed_items(&d.feed_id)? {
            let Ok(item) = s.open() else { continue };
            if !matches!(item.item, Some(Item::Info(_))) {
                continue;
            }
            register(db, &item)?;
            let Some((id, _, size)) = file_of(&item) else { continue };
            if size <= files::MAX_SIZE && db.blob(&id)?.is_some_and(|b| b.complete) {
                db.feed_file_put(&id, 1, 0, 0)?;
            }
        }
    }
    db.set_meta(PHOTOS_FOR_GOOD, &[1])
}

/// Inside a transaction: a group's or channel's photo is a file of our relays too, kept there for
/// good as an official chat's is (q16, T76; Ehsan: «خیلی مهم است»): read there first, and left there
/// by whoever has it whole. Before, it went only phone to phone, from whoever set it.
pub(super) fn register_chat_photo(db: &Db, id: &BlobId, chat: &[u8], size: u64) -> Result<()> {
    if size > files::CHAT_PHOTO_MAX {
        return Ok(());
    }
    db.feed_photo_add(id)?;
    db.feed_file_add(id, chat, now_ms())?;
    if db.blob(id)?.is_some_and(|b| b.complete) && db.feed_file(id)?.is_some_and(|f| f.put == 0) {
        db.feed_file_put(id, 1, 0, 0)?;
    }
    Ok(())
}

/// meta key: the groups' and channels' photos were made files of our relays once (q16).
const CHAT_PHOTOS: &str = "chat_photos_v1";

/// Inside a transaction, once: every group's and channel's photo, set before this build, becomes
/// a file of our relays; the ones held whole here go there.
pub(super) fn backfill_chat_photos(db: &Db) -> Result<()> {
    if db.meta(CHAT_PHOTOS)?.is_some() {
        return Ok(());
    }
    for gid in db.groups()? {
        let Some(st) = super::groups::load_log(db, &gid)?.state(now_ms()) else { continue };
        if let Some((p, _)) = &st.photo {
            if let Some(id) = array::<32>(&p.blob_id) {
                register_chat_photo(db, &id, &gid, p.size)?;
            }
        }
    }
    db.set_meta(CHAT_PHOTOS, &[1])
}

fn backoff(tries: u32) -> u64 {
    PUT_RETRY_MS.saturating_mul(1 << tries.min(6)).min(3600 * 1000)
}

impl Inner {
    pub(super) fn file_relays(&self) -> Vec<String> {
        self.file_relays.read().expect("file relays").clone()
    }

    /// Read an official file from our relays, in the background, unless that was done within
    /// `FETCH_AGAIN_MS`. What they lack comes from `source` (the contact that passed the post on),
    /// if any.
    pub(super) fn fetch_official(self: &Arc<Self>, id: BlobId) {
        let now = now_ms();
        {
            let mut busy = self.file_fetches.lock().expect("file fetches");
            busy.retain(|_, at| now.saturating_sub(*at) < FETCH_AGAIN_MS);
            if busy.contains_key(&id) {
                return;
            }
            busy.insert(id, now);
        }
        let me = self.clone();
        let spawned = std::thread::Builder::new().name("mlmchat-file-get".into()).spawn(move || me.fetch_official_now(id));
        if spawned.is_err() {
            self.file_fetches.lock().expect("file fetches").remove(&id);
        }
    }

    fn fetch_official_now(&self, id: BlobId) {
        let (row, key) = {
            let st = self.state.lock().expect("state");
            let db = st.store.db();
            match (db.blob(&id), db.media_key(super::media::short_id(&id))) {
                (Ok(Some(r)), Ok(Some((_, key, _)))) => (r, key),
                _ => return,
            }
        };
        if row.complete {
            return;
        }
        let missing: Vec<u32> = (0..row.chunks).filter(|i| !row.has(*i)).collect();
        let mut parts = None;
        for url in self.file_relays() {
            if let Some(p) = files::fetch(&url, &id, &missing, RELAY_TIMEOUT) {
                parts = Some(p);
                break;
            }
        }
        let got = match &parts {
            Some(p) => self.take_chunks(&id, files::chunks_of(p, &key, row.chunks, row.size)),
            None => {
                log::info!("files: our relay could not be reached");
                0
            }
        };
        if got > 0 {
            log::info!("files: {got} chunks of an official file read from our relay");
        }
        // what is still missing: from the contact that passed the post on, if any
        let woke = {
            let mut guard = self.state.lock().expect("state");
            let super::State { store, identity } = &mut *guard;
            let identity: &crate::identity::Identity = identity;
            store
                .transaction(|db| {
                    let Some(row) = db.blob(&id)? else { return Ok(false) };
                    if row.complete {
                        return Ok(false);
                    }
                    if parts.is_some() {
                        db.feed_file_lacked(&id, true)?;
                    }
                    match row.source {
                        Some(src) => super::media::request(db, identity, &src, &row),
                        None => Ok(false),
                    }
                })
                .unwrap_or(false)
        };
        if woke {
            self.waker.wake();
        }
    }

    /// Sealed chunks of `id` that checked: written in place; the whole is checked against its id
    /// once every chunk is here. How many were new.
    fn take_chunks(&self, id: &BlobId, chunks: Vec<(u32, Vec<u8>)>) -> u32 {
        let mut events = Vec::new();
        let mut new = 0;
        {
            let st = self.state.lock().expect("state");
            let db = st.store.db();
            let Ok(Some(mut row)) = db.blob(id) else { return 0 };
            if row.complete {
                return 0;
            }
            let path = super::media::blob_path(&self.blob_dir, id);
            if std::fs::create_dir_all(&self.blob_dir).is_err() {
                return 0;
            }
            for (index, sealed) in chunks {
                if index >= row.chunks || row.has(index) || sealed.len() != files::chunk_len(index, row.size) {
                    continue;
                }
                if blob::write_chunk(&path, index, &sealed).is_err() {
                    break;
                }
                row.set(index);
                new += 1;
            }
            if new == 0 {
                return 0;
            }
            row.complete = row.missing() == 0;
            if row.complete && blob::verify_id(&path, id, row.size).is_err() {
                // every chunk opened with the key, but the whole is not this file: someone wrote
                // there under its name; start over from the contact
                let _ = std::fs::remove_file(&path);
                row.clear();
                row.complete = false;
                let _ = db.blob_save(&row);
                log::info!("files: a file from our relay did not match its id");
                return 0;
            }
            if db.blob_save(&row).is_err() {
                return 0;
            }
            let have = row.chunks - row.missing();
            events.push(Event::Blob { id: *id, have, total: row.chunks });
            if row.complete {
                log::info!("node: a file arrived whole");
            }
        }
        for e in events {
            self.emit(e);
        }
        new
    }

    /// Leave the files that are due on our relays, in the background, one go at a time (the
    /// delivery loop asks every half minute; a post of ours at once).
    pub(super) fn files_tick(self: &Arc<Self>) {
        let due = {
            let st = self.state.lock().expect("state");
            let db = st.store.db();
            let _ = db.feed_files_prune(now_ms().saturating_sub(2 * files::EXPIRE_S * 1000));
            db.feed_files_put_due(now_ms(), 4).unwrap_or_default()
        };
        if due.is_empty() || self.files_putting.swap(true, Ordering::SeqCst) {
            return;
        }
        let me = self.clone();
        let spawned = std::thread::Builder::new().name("mlmchat-file-put".into()).spawn(move || {
            loop {
                let due = {
                    let st = me.state.lock().expect("state");
                    st.store.db().feed_files_put_due(now_ms(), 4).unwrap_or_default()
                };
                if due.is_empty() || me.stop.load(Ordering::SeqCst) {
                    break;
                }
                for f in due {
                    me.put_file(&f);
                }
            }
            me.files_putting.store(false, Ordering::SeqCst);
        });
        if spawned.is_err() {
            self.files_putting.store(false, Ordering::SeqCst);
        }
    }

    /// One file to our relays: done when one of them took every part.
    fn put_file(&self, f: &FeedFileRow) {
        let now = now_ms();
        let row: Option<BlobRow> = self.state.lock().expect("state").store.db().blob(&f.id).ok().flatten();
        let set = |put: u8, next: u64, tries: u32| {
            let _ = self.state.lock().expect("state").store.db().feed_file_put(&f.id, put, next, tries);
        };
        let Some(row) = row.filter(|r| r.complete && r.size <= files::MAX_SIZE) else {
            set(0, 0, f.put_tries);
            return;
        };
        let path = super::media::blob_path(&self.blob_dir, &f.id);
        // a chat's own photo goes as one, kept for good (T66)
        let photo = self.state.lock().expect("state").store.db().is_feed_photo(&f.id).unwrap_or(false);
        let put_all = |url: &str| -> bool {
            let Some(mut w) = files::Writer::open(url, RELAY_TIMEOUT) else { return false };
            let now_s = now_ms() / 1000;
            let mut index = 0;
            while index < row.chunks {
                let mut events = Vec::new();
                for i in index..(index + PUT_CHUNKS).min(row.chunks) {
                    match blob::read_chunk(&path, i, row.chunks, row.size) {
                        Ok(sealed) if photo => events.extend(files::photo_events_for(&f.id, &f.feed, i, row.chunks, &sealed, now_s)),
                        Ok(sealed) => events.extend(files::events_for(&f.id, &f.feed, i, row.chunks, &sealed, now_s)),
                        Err(_) => return false,
                    }
                }
                if w.write(&events) != events.len() {
                    return false;
                }
                index += PUT_CHUNKS;
            }
            w.close();
            true
        };
        if self.file_relays().iter().any(|u| put_all(u)) {
            log::info!("files: an official file left on our relay");
            set(2, 0, f.put_tries);
        } else if !photo && now.saturating_sub(f.at_ms.min(now)) > PUT_GIVE_UP_MS {
            set(0, 0, f.put_tries);
        } else {
            log::info!("files: an official file not left on our relay yet");
            set(1, now + backoff(f.put_tries), f.put_tries + 1);
        }
    }
}

impl Node {
    /// Our own relays that official files go to (`transport::nostr::files::relays`); for tests.
    pub fn set_file_relays(&self, urls: Vec<String>) {
        *self.inner.file_relays.write().expect("file relays") = urls;
    }
}
