//! The official chats' files on our own relays (SPEC 18.3, T63; Ehsan, 29 Oct 2026, with hundreds
//! in the official group: no photo, voice or file there ever downloaded). A post read from the
//! official copy named no one to fetch its file from, and a post passed on by a contact named that
//! contact, who seldom had the file whole. Now the phone that posts leaves the sealed file on our
//! relay inside Iran (T54) as events of their own kind, kept two weeks, and any phone that wants
//! it reads them there.
//!
//! - An event is half a sealed chunk (`PART`), so it stays under the 64 KiB relays take; its tags
//!   name the file (`x`, its id), the chunk (`i`), the half (`h`), the chunks in all (`n`) and the
//!   official chat (`f`), with an `expiration` (NIP-40). It is signed by a key made for it alone.
//! - The relay sees sealed bytes, sizes, times and addresses. The file's key is in the post, which
//!   every Gereh user reads; each chunk is checked with that key and the whole with the file's id,
//!   so a relay, or anyone who writes there, can withhold or spoil a file, never change it.

use super::event::{self, Event};
use super::ws::{Split, Ws};
use crate::blob::{self, BlobId};
use crate::util::{b64url_decode, b64url_encode, random};
use serde_json::{json, Value};
use std::collections::HashSet;
use std::time::{Duration, Instant};

/// The events' kind: a regular one (relays keep it), Gereh's own.
pub const KIND: u64 = 3059;
/// How long our relay keeps them.
pub const EXPIRE_S: u64 = 14 * 86_400;
/// A chat's own photo (an official chat's `Info`): its own kind, kept on our relays for good, not
/// two weeks; the relay's trim never takes it (T66; Ehsan, 30 Oct 2026: once the photo expired
/// there, users saw the official chats without one).
pub const PHOTO_KIND: u64 = 3060;
/// For good: ten years.
pub const PHOTO_EXPIRE_S: u64 = 3650 * 86_400;
/// A group's or channel's own photo goes there up to this size (sealed; q16, T76): an avatar is far
/// smaller, and our relay takes no larger one from a chat that is not official.
pub const CHAT_PHOTO_MAX: u64 = 1024 * 1024;
/// Sealed bytes per event: half a sealed chunk.
pub const PART: usize = blob::SEALED_CHUNK.div_ceil(2);
/// Files up to this size (sealed) go there; larger ones stay phone to phone.
pub const MAX_SIZE: u64 = 20 * 1024 * 1024;
/// Chunks asked for in one subscription: two events each, well under the 500 a relay answers.
pub const CHUNKS_PER_REQ: usize = 100;
/// Events written before their answers are awaited.
const WINDOW: usize = 16;
const MAX_MESSAGE: usize = 256 * 1024;

/// Our own relays, the ones these files go to (`PINNED`, T54).
pub fn relays() -> Vec<String> {
    super::PINNED.iter().map(|(h, _)| format!("wss://{h}")).collect()
}

/// The sealed length of chunk `index` of a blob of `size` sealed bytes.
pub fn chunk_len(index: u32, size: u64) -> usize {
    size.saturating_sub(blob::offset(index)).min(blob::SEALED_CHUNK as u64) as usize
}

/// The events of one sealed chunk: one per half, one alone when it fits.
pub fn events_for(id: &BlobId, feed: &[u8], index: u32, total: u32, sealed: &[u8], now_s: u64) -> Vec<Event> {
    sealed
        .chunks(PART)
        .enumerate()
        .map(|(half, bytes)| {
            let tags = json!([
                ["x", hex::encode(id)],
                ["i", index.to_string()],
                ["h", half.to_string()],
                ["n", total.to_string()],
                ["f", hex::encode(feed)],
                ["expiration", (now_s + EXPIRE_S).to_string()]
            ]);
            event::signed(KIND, tags, b64url_encode(bytes), now_s)
        })
        .collect()
}

/// The events of one sealed chunk of a chat's photo: as [`events_for`], of [`PHOTO_KIND`] and kept for good.
pub fn photo_events_for(id: &BlobId, feed: &[u8], index: u32, total: u32, sealed: &[u8], now_s: u64) -> Vec<Event> {
    sealed
        .chunks(PART)
        .enumerate()
        .map(|(half, bytes)| {
            let tags = json!([
                ["x", hex::encode(id)],
                ["i", index.to_string()],
                ["h", half.to_string()],
                ["n", total.to_string()],
                ["f", hex::encode(feed)],
                ["expiration", (now_s + PHOTO_EXPIRE_S).to_string()]
            ]);
            event::signed(PHOTO_KIND, tags, b64url_encode(bytes), now_s)
        })
        .collect()
}

/// Half a sealed chunk, from a relay.
#[derive(Debug, Clone)]
pub struct Part {
    pub index: u32,
    pub half: u8,
    pub bytes: Vec<u8>,
    pub created: u64,
}

/// The part an event carries, if it is one of file `id`: our kind, a valid id and signature.
pub fn open_part(v: &Value, id: &BlobId) -> Option<Part> {
    let (_, kind, tags, content) = event::checked(v)?;
    if kind != KIND && kind != PHOTO_KIND {
        return None;
    }
    let tag = |name: &str| -> Option<&str> { tags.as_array()?.iter().find(|t| t.get(0).and_then(Value::as_str) == Some(name))?.get(1)?.as_str() };
    if tag("x")? != hex::encode(id) {
        return None;
    }
    let half: u8 = tag("h")?.parse().ok()?;
    if half > 1 {
        return None;
    }
    Some(Part { index: tag("i")?.parse().ok()?, half, bytes: b64url_decode(content)?, created: v.get("created_at")?.as_u64()? })
}

/// The sealed chunks the parts make, each checked with the file's key: the first pair of halves
/// that opens, oldest first (whoever posted wrote first; a later copy under the same name is
/// someone else's).
pub fn chunks_of(parts: &[Part], key: &[u8; 32], total: u32, size: u64) -> Vec<(u32, Vec<u8>)> {
    let mut indices: Vec<u32> = parts.iter().map(|p| p.index).filter(|i| *i < total).collect();
    indices.sort_unstable();
    indices.dedup();
    let mut out = Vec::new();
    for index in indices {
        let len = chunk_len(index, size);
        let halves = |h: u8| {
            let mut v: Vec<&Part> = parts.iter().filter(|p| p.index == index && p.half == h).collect();
            v.sort_by_key(|p| p.created);
            v.truncate(4);
            v
        };
        let (first, second) = (halves(0), halves(1));
        let found = first.iter().find_map(|a| {
            if len <= PART {
                return (a.bytes.len() == len && blob::open_chunk(key, index, total, &a.bytes).is_ok()).then(|| a.bytes.clone());
            }
            second.iter().find_map(|b| {
                let mut sealed = a.bytes.clone();
                sealed.extend_from_slice(&b.bytes);
                (sealed.len() == len && blob::open_chunk(key, index, total, &sealed).is_ok()).then_some(sealed)
            })
        });
        if let Some(sealed) = found {
            out.push((index, sealed));
        }
    }
    out
}

pub(super) fn connect(url: &str, timeout: Duration) -> Option<Ws> {
    for split in [Split::None, Split::Record] {
        if let Ok((ws, _)) = Ws::connect(url, split, timeout) {
            ws.set_read_timeout(timeout);
            return Some(ws);
        }
    }
    None
}

/// A connection to one relay that events are written to.
pub struct Writer {
    ws: Ws,
    timeout: Duration,
}

impl Writer {
    pub fn open(url: &str, timeout: Duration) -> Option<Writer> {
        Some(Writer { ws: connect(url, timeout)?, timeout })
    }

    /// Write `events`; how many the relay took (one it had already counts: it is there). Stops at
    /// the first refusal or silence.
    pub fn write(&mut self, events: &[Event]) -> usize {
        let mut took = 0;
        for window in events.chunks(WINDOW) {
            let mut waiting: HashSet<String> = HashSet::new();
            for e in window {
                if self.ws.send_text(&json!(["EVENT", e.json]).to_string()).is_err() {
                    return took;
                }
                waiting.insert(hex::encode(e.id));
            }
            let end = Instant::now() + self.timeout;
            while !waiting.is_empty() && Instant::now() < end {
                let Ok(m) = self.ws.recv_text(MAX_MESSAGE) else { break };
                let Ok(Value::Array(a)) = serde_json::from_str::<Value>(&m) else { continue };
                if a.first().and_then(Value::as_str) != Some("OK") {
                    continue;
                }
                let id = a.get(1).and_then(Value::as_str).unwrap_or("");
                if !waiting.remove(id) {
                    continue;
                }
                if a.get(2).and_then(Value::as_bool) == Some(true) {
                    took += 1;
                } else {
                    let why = a.get(3).and_then(Value::as_str).unwrap_or("");
                    log::info!("files: our relay refused a part ({})", why.split(':').next().unwrap_or(""));
                    return took;
                }
            }
            if !waiting.is_empty() {
                return took;
            }
        }
        took
    }

    pub fn close(self) {
        self.ws.close();
    }
}

/// The parts of file `id` that `url` keeps, of the chunks `indices`. None when the relay could not
/// be reached or stopped answering before it said what it has.
pub fn fetch(url: &str, id: &BlobId, indices: &[u32], timeout: Duration) -> Option<Vec<Part>> {
    let mut ws = connect(url, timeout)?;
    let mut out = Vec::new();
    for batch in indices.chunks(CHUNKS_PER_REQ) {
        let sub = hex::encode(random::<8>());
        let names: Vec<String> = batch.iter().map(|i| i.to_string()).collect();
        let filter = json!({ "kinds": [KIND, PHOTO_KIND], "#x": [hex::encode(id)], "#i": names, "limit": batch.len() * 4 });
        if ws.send_text(&json!(["REQ", sub, filter]).to_string()).is_err() {
            return None;
        }
        let end = Instant::now() + timeout;
        loop {
            if Instant::now() >= end {
                return None;
            }
            let Ok(m) = ws.recv_text(MAX_MESSAGE) else { return None };
            let Ok(Value::Array(a)) = serde_json::from_str::<Value>(&m) else { continue };
            if a.get(1).and_then(Value::as_str) != Some(sub.as_str()) {
                continue;
            }
            match a.first().and_then(Value::as_str) {
                Some("EVENT") => {
                    if let Some(p) = a.get(2).and_then(|v| open_part(v, id)) {
                        out.push(p);
                    }
                }
                Some("EOSE") => break,
                Some("CLOSED") => return None,
                _ => {}
            }
        }
        let _ = ws.send_text(&json!(["CLOSE", sub]).to_string());
    }
    ws.close();
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sealed_file(len: usize) -> (tempfile::TempDir, std::path::PathBuf, blob::Sealed) {
        let dir = tempfile::tempdir().unwrap();
        let (src, dst) = (dir.path().join("a"), dir.path().join("a.blob"));
        std::fs::write(&src, (0..len).map(|i| (i * 13 % 251) as u8).collect::<Vec<u8>>()).unwrap();
        let s = blob::seal_file(&src, &dst).unwrap();
        (dir, dst, s)
    }

    fn parts_of(path: &std::path::Path, s: &blob::Sealed, feed: &[u8]) -> Vec<Part> {
        (0..s.chunks)
            .flat_map(|i| events_for(&s.blob_id, feed, i, s.chunks, &blob::read_chunk(path, i, s.chunks, s.size).unwrap(), 1_761_700_000))
            .map(|e| open_part(&e.json, &s.blob_id).expect("our own event opens"))
            .collect()
    }

    #[test]
    fn a_file_goes_in_halves_and_comes_back_whole() {
        let (_d, path, s) = sealed_file(blob::CHUNK * 2 + 1000);
        let parts = parts_of(&path, &s, &[1; 16]);
        assert_eq!(parts.len(), 5, "two halves of two full chunks, and a last chunk alone");
        let chunks = chunks_of(&parts, &s.key, s.chunks, s.size);
        assert_eq!(chunks.len(), 3);
        for (i, sealed) in chunks {
            assert_eq!(sealed, blob::read_chunk(&path, i, s.chunks, s.size).unwrap());
        }
        // an event stays well under the 64 KiB relays take
        let e = &events_for(&s.blob_id, &[1; 16], 0, s.chunks, &blob::read_chunk(&path, 0, s.chunks, s.size).unwrap(), 1)[0];
        assert!(e.json.to_string().len() < 48 * 1024, "{}", e.json.to_string().len());
        assert_eq!(e.json["kind"], KIND);
    }

    #[test]
    fn a_chat_photo_goes_as_its_own_kind_for_good() {
        let (_d, path, s) = sealed_file(5000);
        let e = &photo_events_for(&s.blob_id, &[1; 16], 0, s.chunks, &blob::read_chunk(&path, 0, s.chunks, s.size).unwrap(), 1_000)[0];
        assert_eq!(e.json["kind"], PHOTO_KIND);
        let exp = e.json["tags"].as_array().unwrap().iter().find(|t| t[0] == "expiration").unwrap()[1].as_str().unwrap().parse::<u64>().unwrap();
        assert_eq!(exp, 1_000 + PHOTO_EXPIRE_S, "kept for good");
        assert!(open_part(&e.json, &s.blob_id).is_some(), "read back like any part");
    }

    #[test]
    fn a_spoiled_or_foreign_part_is_left_out() {
        let (_d, path, s) = sealed_file(blob::CHUNK + 10);
        let mut parts = parts_of(&path, &s, &[1; 16]);
        // a part someone wrote later under the same name, with other bytes: the real one is used
        let mut fake = parts[0].clone();
        fake.bytes[3] ^= 1;
        fake.created += 5;
        parts.insert(0, fake);
        assert_eq!(chunks_of(&parts, &s.key, s.chunks, s.size).len(), 2);
        // only the spoiled one: the chunk is not taken
        parts.retain(|p| !(p.index == 0 && p.half == 0 && p.created == 1_761_700_000));
        let got = chunks_of(&parts, &s.key, s.chunks, s.size);
        assert_eq!(got.iter().map(|(i, _)| *i).collect::<Vec<_>>(), vec![1]);
        // another file's events do not open as this one's
        let (_d2, path2, s2) = sealed_file(100);
        let e = &events_for(&s2.blob_id, &[1; 16], 0, 1, &blob::read_chunk(&path2, 0, 1, s2.size).unwrap(), 1)[0];
        assert!(open_part(&e.json, &s.blob_id).is_none());
    }
}
