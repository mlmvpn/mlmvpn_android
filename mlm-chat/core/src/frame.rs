//! What goes on the wire (SPEC 6): `Frame` padded to a fixed size (SPEC 4, 14.5), and
//! `SealedFrame`, HPKE base mode (RFC 9180, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256,
//! ChaCha20Poly1305) to the recipient's sealing key, for any frame another device holds.

use crate::{Error, Result, UserId};
use hpke::aead::ChaCha20Poly1305;
use hpke::kdf::HkdfSha256;
use hpke::kem::X25519HkdfSha256;
use hpke::{Deserializable, Kem, OpModeR, OpModeS, Serializable};
use prost::Message;

pub const FRAME_VERSION: u32 = 1;
/// Fixed frame sizes: 256 B, 1, 4, 16, 64 KiB.
pub const BUCKETS: [usize; 5] = [256, 1024, 4096, 16384, 65536];
pub const TAG_LEN: usize = 16;

pub const KIND_OLM_PREKEY: u32 = 0;
pub const KIND_OLM: u32 = 1;
pub const KIND_MEGOLM: u32 = 2;
/// One sealed chunk of a file (SPEC 17): `blob_id || index || total || chunk`. The chunk is
/// already end-to-end encrypted with the file's key; the session encrypts the frame again.
pub const KIND_BLOB: u32 = 3;
/// One part of a larger frame, only inside a mailbox (SPEC 19.11): a frame over 16 KiB (a file
/// chunk) is cut into parts that each fill the 16 KiB bucket, so every part looks the same and
/// fits a mailbox item. `cipher` = `id (8) || index (1) || count (1) || len (u16 BE) || bytes`,
/// zero-filled to `PART_HEADER + PART_DATA`. The receiver puts the frame together again before
/// anything else sees it; no other transport carries parts.
pub const KIND_PART: u32 = 4;
pub const PART_HEADER: usize = 12;
/// Bytes of the whole frame in one part: the part frame stays in the 16 KiB bucket.
pub const PART_DATA: usize = 16_000;

/// HPKE `info` for SealedFrame. The `aad` is the SealedFrame tag.
pub const SEAL_INFO: &[u8] = b"mlmchat/sealed/1";

#[derive(Clone, PartialEq, Message)]
pub struct Frame {
    #[prost(uint32, tag = "1")]
    pub v: u32,
    #[prost(bytes = "vec", tag = "2")]
    pub tag: Vec<u8>,
    #[prost(uint32, tag = "3")]
    pub kind: u32,
    #[prost(bytes = "vec", tag = "4")]
    pub cipher: Vec<u8>,
    #[prost(bytes = "vec", tag = "5")]
    pub pad: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct SealedFrame {
    #[prost(uint32, tag = "1")]
    pub v: u32,
    #[prost(bytes = "vec", tag = "2")]
    pub tag: Vec<u8>,
    #[prost(bytes = "vec", tag = "3")]
    pub enc: Vec<u8>,
    #[prost(bytes = "vec", tag = "4")]
    pub ct: Vec<u8>,
}

/// The HPKE plaintext of a SealedFrame (SPEC 14.6): the padded Frame and the sender's sk.
#[derive(Clone, PartialEq, Message)]
pub struct SealedInner {
    #[prost(bytes = "vec", tag = "1")]
    pub frame: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub from_sk: Vec<u8>,
}

fn varint_len(mut v: usize) -> usize {
    let mut n = 1;
    while v >= 0x80 {
        v >>= 7;
        n += 1;
    }
    n
}

fn pad_field(len: usize) -> Vec<u8> {
    let mut out = vec![0x2A]; // field 5, length-delimited
    prost::encoding::encode_varint(len as u64, &mut out);
    out.resize(out.len() + len, 0);
    out
}

/// Bytes that add exactly `total` bytes of `pad`: one field, or (at a varint boundary where one
/// field cannot hit the size) an empty pad field followed by a second one. Protobuf keeps the
/// last value of a repeated singular field, so both forms decode the same.
fn pad_bytes(total: usize) -> Option<Vec<u8>> {
    for vl in 1..=3 {
        if total > vl {
            let p = total - 1 - vl;
            if varint_len(p) == vl {
                return Some(pad_field(p));
            }
        }
    }
    if total >= 4 {
        let mut out = vec![0x2A, 0x00];
        out.extend(pad_bytes(total - 2)?);
        return Some(out);
    }
    None
}

/// Encode a frame and pad it to the smallest bucket that fits.
pub fn encode_padded(tag: &[u8], kind: u32, cipher: &[u8]) -> Result<Vec<u8>> {
    if !(tag.is_empty() || tag.len() == TAG_LEN) || kind > KIND_PART {
        return Err(Error::Frame("bad tag or kind"));
    }
    let mut out = Frame { v: FRAME_VERSION, tag: tag.to_vec(), kind, cipher: cipher.to_vec(), pad: Vec::new() }.encode_to_vec();
    let n = out.len();
    for b in BUCKETS {
        if b >= n + 2 {
            if let Some(p) = pad_bytes(b - n) {
                out.extend(p);
                debug_assert_eq!(out.len(), b);
                return Ok(out);
            }
        }
    }
    Err(Error::Frame("frame larger than the largest bucket"))
}

/// Decode a frame from the network. Its size must be one of the buckets.
pub fn decode(bytes: &[u8]) -> Result<Frame> {
    if !BUCKETS.contains(&bytes.len()) {
        return Err(Error::Frame("size is not a padding bucket"));
    }
    let f = Frame::decode(bytes)?;
    if f.v != FRAME_VERSION {
        return Err(Error::Frame("unsupported frame version"));
    }
    if !(f.tag.is_empty() || f.tag.len() == TAG_LEN) || f.kind > KIND_PART || f.cipher.is_empty() {
        return Err(Error::Frame("bad tag, kind or cipher"));
    }
    Ok(f)
}

/// One part of a frame (`KIND_PART`), read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Part {
    pub id: [u8; 8],
    pub index: u8,
    pub count: u8,
    pub data: Vec<u8>,
}

/// Cut a padded frame into part frames (`id`: random, the same in every part of it).
pub fn split(frame: &[u8], id: [u8; 8]) -> Result<Vec<Vec<u8>>> {
    let count = frame.len().div_ceil(PART_DATA);
    if count < 2 || count > 255 {
        return Err(Error::Frame("not a frame to cut"));
    }
    frame
        .chunks(PART_DATA)
        .enumerate()
        .map(|(i, c)| {
            let mut cipher = Vec::with_capacity(PART_HEADER + PART_DATA);
            cipher.extend_from_slice(&id);
            cipher.push(i as u8);
            cipher.push(count as u8);
            cipher.extend_from_slice(&(c.len() as u16).to_be_bytes());
            cipher.extend_from_slice(c);
            cipher.resize(PART_HEADER + PART_DATA, 0);
            encode_padded(&[], KIND_PART, &cipher)
        })
        .collect()
}

/// The part a decoded frame carries, if it is a well-formed one.
pub fn part_of(f: &Frame) -> Option<Part> {
    if f.kind != KIND_PART || f.cipher.len() != PART_HEADER + PART_DATA {
        return None;
    }
    let c = &f.cipher;
    let (index, count) = (c[8], c[9]);
    let len = u16::from_be_bytes([c[10], c[11]]) as usize;
    if count < 2 || index >= count || len == 0 || len > PART_DATA {
        return None;
    }
    Some(Part { id: crate::util::array::<8>(&c[..8])?, index, count, data: c[PART_HEADER..PART_HEADER + len].to_vec() })
}

/// The frame again, from all its parts' bytes in order; checked to be a frame.
pub fn join(parts: &[Vec<u8>]) -> Result<Vec<u8>> {
    let whole = parts.concat();
    decode(&whole)?;
    Ok(whole)
}

type SealKem = X25519HkdfSha256;

/// Seal a padded frame to a recipient's sealing key (`hk` from their card).
pub fn seal(frame: &[u8], from_sk: &UserId, to_hk: &[u8; 32], tag: &[u8; TAG_LEN]) -> Result<Vec<u8>> {
    decode(frame)?;
    let pk = <SealKem as Kem>::PublicKey::from_bytes(to_hk).map_err(|_| Error::Sealed("bad recipient key"))?;
    let inner = SealedInner { frame: frame.to_vec(), from_sk: from_sk.to_vec() }.encode_to_vec();
    let (enc, ct) = hpke::single_shot_seal::<ChaCha20Poly1305, HkdfSha256, SealKem>(&OpModeS::Base, &pk, SEAL_INFO, &inner, tag)
        .map_err(|_| Error::Sealed("seal failed"))?;
    Ok(SealedFrame { v: FRAME_VERSION, tag: tag.to_vec(), enc: enc.to_bytes().to_vec(), ct }.encode_to_vec())
}

pub struct Opened {
    pub frame: Vec<u8>,
    pub from_sk: UserId,
    pub tag: [u8; TAG_LEN],
}

/// Open a SealedFrame with our sealing secret. The sender sk inside is a claim; it is proven
/// only when the Olm frame inside decrypts with that contact's session.
pub fn open(bytes: &[u8], secret: &<SealKem as Kem>::PrivateKey) -> Result<Opened> {
    if bytes.len() > BUCKETS[BUCKETS.len() - 1] + 256 {
        return Err(Error::Sealed("too large"));
    }
    let s = SealedFrame::decode(bytes)?;
    if s.v != FRAME_VERSION || s.tag.len() != TAG_LEN {
        return Err(Error::Sealed("bad version or tag"));
    }
    let enc = <SealKem as Kem>::EncappedKey::from_bytes(&s.enc).map_err(|_| Error::Sealed("bad encapsulated key"))?;
    let inner = hpke::single_shot_open::<ChaCha20Poly1305, HkdfSha256, SealKem>(&OpModeR::Base, secret, &enc, SEAL_INFO, &s.ct, &s.tag)
        .map_err(|_| Error::Sealed("open failed"))?;
    let inner = SealedInner::decode(&inner[..])?;
    decode(&inner.frame)?;
    let from_sk = crate::util::array::<32>(&inner.from_sk).ok_or(Error::Sealed("bad sender"))?;
    Ok(Opened { frame: inner.frame, from_sk, tag: crate::util::array::<16>(&s.tag).expect("checked") })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_big_frame_cuts_into_alike_parts_and_joins_back() {
        let big = encode_padded(&[], KIND_BLOB, &vec![7u8; 60_000]).unwrap();
        assert_eq!(big.len(), 65536);
        let parts = split(&big, [9; 8]).unwrap();
        assert_eq!(parts.len(), 5);
        assert!(parts.iter().all(|p| p.len() == 16384), "every part fills the 16 KiB bucket");
        let mut read: Vec<Part> = parts.iter().map(|p| part_of(&decode(p).unwrap()).unwrap()).collect();
        assert!(read.iter().all(|p| p.id == [9; 8] && p.count == 5));
        read.reverse();
        read.sort_by_key(|p| p.index);
        let back = join(&read.into_iter().map(|p| p.data).collect::<Vec<_>>()).unwrap();
        assert_eq!(back, big);
        // a small frame is not cut, and a part with a missing piece is not a frame
        assert!(split(&encode_padded(&[], KIND_OLM, b"hi").unwrap(), [1; 8]).is_err());
        let parts = split(&big, [9; 8]).unwrap();
        let some: Vec<Vec<u8>> = parts[..4].iter().map(|p| part_of(&decode(p).unwrap()).unwrap().data).collect();
        assert!(join(&some).is_err());
    }

    #[test]
    fn every_cipher_length_lands_on_a_bucket() {
        for len in 1..20_000 {
            let f = encode_padded(&[], KIND_OLM, &vec![7u8; len]).unwrap();
            assert!(BUCKETS.contains(&f.len()), "len {len} -> {}", f.len());
            assert_eq!(decode(&f).unwrap().cipher.len(), len);
        }
        // with a tag the header is 24 bytes and the pad field at least 2: 65510 is the most
        // (an envelope at MAX_ENVELOPE becomes about 65200 bytes of Olm)
        for len in [65_000, 65_400, 65_500, 65_510] {
            let f = encode_padded(&[9; 16], KIND_OLM_PREKEY, &vec![7u8; len]).unwrap();
            assert_eq!(f.len(), 65536);
        }
        assert!(encode_padded(&[9; 16], KIND_OLM_PREKEY, &vec![7u8; 65_511]).is_err());
        assert!(encode_padded(&[], KIND_OLM, &vec![0; 65536]).is_err());
    }

    #[test]
    fn odd_sizes_are_refused() {
        let mut f = encode_padded(&[], KIND_OLM, b"abc").unwrap();
        f.push(0);
        assert!(decode(&f).is_err());
    }

    #[test]
    fn seal_and_open() {
        let (sk, pk) = SealKem::derive_keypair(&[5; 32]);
        let hk: [u8; 32] = pk.to_bytes().into();
        let frame = encode_padded(&[], KIND_OLM, b"cipher").unwrap();
        let sealed = seal(&frame, &[1; 32], &hk, &[2; 16]).unwrap();
        let o = open(&sealed, &sk).unwrap();
        assert_eq!(o.frame, frame);
        assert_eq!(o.from_sk, [1; 32]);
        let (other, _) = SealKem::derive_keypair(&[6; 32]);
        assert!(open(&sealed, &other).is_err());
        let mut tampered = SealedFrame::decode(&sealed[..]).unwrap();
        tampered.tag = vec![3; 16];
        assert!(open(&tampered.encode_to_vec(), &sk).is_err());
    }
}
