//! Files, photos, voice and video (SPEC 12, 17; phase 4): the encrypted blob.
//!
//! Each file gets a fresh random 32-byte key, sent only inside the end-to-end message (`Media`).
//! The file is cut into chunks of [`CHUNK`] bytes and each chunk is sealed with STREAM over
//! XChaCha20-Poly1305 (`aead::stream::StreamBE32`, the construction age uses): the chunk's
//! position and a "last chunk" flag are part of its nonce, so chunks cannot be reordered,
//! dropped from the end or swapped between files. The nonce prefix comes from the key (HKDF),
//! since every key seals one file only. `blob_id` = BLAKE3 over all sealed chunks in order: what
//! peers ask for, and the last check once every chunk is here.
//!
//! A sealed chunk is `CHUNK + 16` bytes (the last one shorter), so with its frame header it fits
//! the largest frame size (SPEC 14.5).

use crate::{Error, Result};
use chacha20poly1305::aead::stream::{NewStream, StreamBE32, StreamPrimitive};
use chacha20poly1305::{KeyInit, XChaCha20Poly1305};
use hkdf::Hkdf;
use sha2::Sha256;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

/// Plain bytes per chunk: 64 KiB less room for the frame header and the AEAD tag.
pub const CHUNK: usize = 65536 - 512;
pub const TAG: usize = 16;
pub const SEALED_CHUNK: usize = CHUNK + TAG;
pub const NONCE_INFO: &[u8] = b"mlmchat/blob/1/nonce";
/// SPEC 12: files up to 2 GiB in version 1.
pub const MAX_SIZE: u64 = 2 * 1024 * 1024 * 1024;

pub type BlobId = [u8; 32];

/// What the message carries about a sealed file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Sealed {
    pub key: [u8; 32],
    pub blob_id: BlobId,
    /// Bytes of the plain file.
    pub plain_size: u64,
    /// Bytes of the sealed blob (what travels).
    pub size: u64,
    pub chunks: u32,
}

pub fn new_key() -> [u8; 32] {
    crate::util::random()
}

/// Chunks a file of `plain_size` bytes has (an empty file has one, empty).
pub fn chunk_count(plain_size: u64) -> u32 {
    (plain_size.div_ceil(CHUNK as u64)).max(1) as u32
}

/// Sealed size of a file of `plain_size` bytes.
pub fn sealed_size(plain_size: u64) -> u64 {
    plain_size + u64::from(chunk_count(plain_size)) * TAG as u64
}

/// Where sealed chunk `index` starts in the sealed blob.
pub fn offset(index: u32) -> u64 {
    u64::from(index) * SEALED_CHUNK as u64
}

fn stream(key: &[u8; 32]) -> StreamBE32<XChaCha20Poly1305> {
    let mut prefix = [0u8; 19];
    Hkdf::<Sha256>::new(None, key).expand(NONCE_INFO, &mut prefix).expect("19 bytes is a valid HKDF length");
    StreamBE32::from_aead(XChaCha20Poly1305::new(key.into()), (&prefix).into())
}

pub fn seal_chunk(key: &[u8; 32], index: u32, total: u32, plain: &[u8]) -> Result<Vec<u8>> {
    if index >= total || plain.len() > CHUNK || (index + 1 < total && plain.len() != CHUNK) {
        return Err(Error::Frame("bad chunk"));
    }
    stream(key).encrypt(index, index + 1 == total, plain).map_err(|_| Error::Frame("chunk does not seal"))
}

/// One chunk's plain bytes: refused if it is not chunk `index` of `total` of this key's file.
pub fn open_chunk(key: &[u8; 32], index: u32, total: u32, sealed: &[u8]) -> Result<Vec<u8>> {
    if index >= total || sealed.len() > SEALED_CHUNK || sealed.len() < TAG {
        return Err(Error::Frame("bad chunk"));
    }
    stream(key).decrypt(index, index + 1 == total, sealed).map_err(|_| Error::Frame("chunk does not open"))
}

/// Seal the file at `src` into `dst` (the sealed blob, chunks one after the other).
pub fn seal_file(src: &Path, dst: &Path) -> Result<Sealed> {
    let key = new_key();
    let plain_size = std::fs::metadata(src)?.len();
    if plain_size > MAX_SIZE {
        return Err(Error::Frame("file larger than 2 GiB"));
    }
    let total = chunk_count(plain_size);
    let mut input = File::open(src)?;
    let mut output = File::create(dst)?;
    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; CHUNK];
    for index in 0..total {
        let want = if index + 1 == total { (plain_size - u64::from(index) * CHUNK as u64) as usize } else { CHUNK };
        input.read_exact(&mut buf[..want])?;
        let sealed = seal_chunk(&key, index, total, &buf[..want])?;
        hasher.update(&sealed);
        output.write_all(&sealed)?;
    }
    output.flush()?;
    Ok(Sealed { key, blob_id: *hasher.finalize().as_bytes(), plain_size, size: sealed_size(plain_size), chunks: total })
}

/// Sealed chunk `index` of the blob at `path`.
pub fn read_chunk(path: &Path, index: u32, total: u32, size: u64) -> Result<Vec<u8>> {
    if index >= total {
        return Err(Error::Frame("no such chunk"));
    }
    let start = offset(index);
    let len = (size.saturating_sub(start)).min(SEALED_CHUNK as u64) as usize;
    let mut f = File::open(path)?;
    f.seek(SeekFrom::Start(start))?;
    let mut out = vec![0u8; len];
    f.read_exact(&mut out)?;
    Ok(out)
}

/// Put a received sealed chunk at its place in the blob being assembled at `path`.
pub fn write_chunk(path: &Path, index: u32, sealed: &[u8]) -> Result<()> {
    let mut f = std::fs::OpenOptions::new().create(true).write(true).truncate(false).open(path)?;
    f.seek(SeekFrom::Start(offset(index)))?;
    f.write_all(sealed)?;
    Ok(())
}

/// The whole blob is here: check its id, then write the plain file to `out`.
pub fn open_file(blob: &Path, key: &[u8; 32], blob_id: &BlobId, size: u64, out: &Path) -> Result<u64> {
    let total = chunk_count_sealed(size);
    let mut hasher = blake3::Hasher::new();
    let mut f = File::open(blob)?;
    let mut buf = vec![0u8; SEALED_CHUNK];
    let mut parts = Vec::with_capacity(total as usize);
    let mut left = size;
    for _ in 0..total {
        let len = left.min(SEALED_CHUNK as u64) as usize;
        f.read_exact(&mut buf[..len])?;
        hasher.update(&buf[..len]);
        parts.push(len);
        left -= len as u64;
    }
    if left != 0 || hasher.finalize().as_bytes() != blob_id {
        return Err(Error::Frame("file does not match its id"));
    }
    let mut f = File::open(blob)?;
    let mut o = File::create(out)?;
    let mut written = 0u64;
    for (index, len) in parts.into_iter().enumerate() {
        f.read_exact(&mut buf[..len])?;
        let plain = open_chunk(key, index as u32, total, &buf[..len])?;
        o.write_all(&plain)?;
        written += plain.len() as u64;
    }
    o.flush()?;
    Ok(written)
}

/// Check a whole blob against its id (BLAKE3 over every sealed chunk).
pub fn verify_id(blob: &Path, blob_id: &BlobId, size: u64) -> Result<()> {
    let mut f = File::open(blob)?;
    if f.metadata()?.len() < size {
        return Err(Error::Frame("file not whole"));
    }
    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; SEALED_CHUNK];
    let mut left = size;
    while left > 0 {
        let len = left.min(SEALED_CHUNK as u64) as usize;
        f.read_exact(&mut buf[..len])?;
        hasher.update(&buf[..len]);
        left -= len as u64;
    }
    if hasher.finalize().as_bytes() != blob_id {
        return Err(Error::Frame("file does not match its id"));
    }
    Ok(())
}

/// Chunks of a sealed blob of `size` bytes.
pub fn chunk_count_sealed(size: u64) -> u32 {
    (size.div_ceil(SEALED_CHUNK as u64)).max(1) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(len: usize) {
        let dir = tempfile::tempdir().unwrap();
        let (src, enc, out) = (dir.path().join("a"), dir.path().join("a.blob"), dir.path().join("a.out"));
        let data: Vec<u8> = (0..len).map(|i| (i * 7 % 251) as u8).collect();
        std::fs::write(&src, &data).unwrap();
        let s = seal_file(&src, &enc).unwrap();
        assert_eq!(s.plain_size, len as u64);
        assert_eq!(std::fs::metadata(&enc).unwrap().len(), s.size);
        assert_eq!(s.chunks, chunk_count(len as u64));
        assert_eq!(chunk_count_sealed(s.size), s.chunks);
        assert_eq!(open_file(&enc, &s.key, &s.blob_id, s.size, &out).unwrap(), len as u64);
        assert_eq!(std::fs::read(&out).unwrap(), data);
    }

    #[test]
    fn files_of_every_size_go_round() {
        for len in [0, 1, 1000, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 17] {
            roundtrip(len);
        }
    }

    #[test]
    fn chunks_are_bound_to_their_place_and_file() {
        let key = [3u8; 32];
        let a = seal_chunk(&key, 0, 2, &[1u8; CHUNK]).unwrap();
        let b = seal_chunk(&key, 1, 2, b"end").unwrap();
        assert_eq!(open_chunk(&key, 1, 2, &b).unwrap(), b"end");
        // swapped places, a dropped last chunk (0 of 1), another key: all refused
        assert!(open_chunk(&key, 1, 2, &a).is_err());
        assert!(open_chunk(&key, 0, 1, &a).is_err());
        assert!(open_chunk(&[4u8; 32], 0, 2, &a).is_err());
        let mut bad = b.clone();
        bad[0] ^= 1;
        assert!(open_chunk(&key, 1, 2, &bad).is_err());
    }

    #[test]
    fn assembling_out_of_order_and_checking_the_id() {
        let dir = tempfile::tempdir().unwrap();
        let (src, enc, got, out) = (dir.path().join("a"), dir.path().join("a.blob"), dir.path().join("b.blob"), dir.path().join("b.out"));
        let data: Vec<u8> = (0..(2 * CHUNK + 5)).map(|i| (i % 253) as u8).collect();
        std::fs::write(&src, &data).unwrap();
        let s = seal_file(&src, &enc).unwrap();
        for index in [2, 0, 1] {
            let c = read_chunk(&enc, index, s.chunks, s.size).unwrap();
            write_chunk(&got, index, &c).unwrap();
        }
        assert_eq!(open_file(&got, &s.key, &s.blob_id, s.size, &out).unwrap(), data.len() as u64);
        assert_eq!(std::fs::read(&out).unwrap(), data);
        // one byte changed anywhere: the id no longer matches
        let mut raw = std::fs::read(&got).unwrap();
        raw[10] ^= 1;
        std::fs::write(&got, &raw).unwrap();
        assert!(open_file(&got, &s.key, &s.blob_id, s.size, &out).is_err());
    }
}
