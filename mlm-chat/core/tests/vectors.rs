//! Reference vectors (`mlm-chat/vectors/`, CLAUDE.md «قرارداد»): fixed fake keys in, exact bytes
//! out. Any implementation of the contract must reproduce them. They are written once with
//! `MLMCHAT_WRITE_VECTORS=1 cargo test --test vectors -- --ignored`, then never changed to make a
//! test pass: a wrong vector is fixed only through a `spec-change`.
//!
//! Olm itself has no vector here: its ratchet is random by design and vodozemac carries its own.

use ed25519_dalek::{Signer, SigningKey};
use hpke::kem::X25519HkdfSha256;
use hpke::{Kem, Serializable};
use mlmchat_core::beacon::{self, Beacon};
use mlmchat_core::card::{Card, CardKind};
use mlmchat_core::envelope::{Body, Envelope, ReceiptKind, Text};
use mlmchat_core::frame::{self, KIND_OLM, KIND_OLM_PREKEY};
use mlmchat_core::identity::{display_name, personal_code, security_number};
use mlmchat_core::transport::direct::{frame_id, hello_input, psk, ROLE_INITIATOR};
use mlmchat_core::addr;
use serde_json::{json, Value};
use std::path::PathBuf;

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..").join("vectors")
}

struct User {
    signing: SigningKey,
    sk: [u8; 32],
    ik: [u8; 32],
    otk: [u8; 32],
    hk: [u8; 32],
    loc: [u8; 32],
}

/// X25519 public keys from fixed input key material (RFC 9180 DeriveKeyPair).
fn x25519(ikm: u8) -> [u8; 32] {
    X25519HkdfSha256::derive_keypair(&[ikm; 32]).1.to_bytes().into()
}

fn user(seed: u8) -> User {
    let signing = SigningKey::from_bytes(&[seed; 32]);
    User { sk: signing.verifying_key().to_bytes(), signing, ik: x25519(seed + 1), otk: x25519(seed + 2), hk: x25519(seed + 3), loc: [seed + 4; 32] }
}

fn alice() -> User {
    user(0xA0)
}

fn bob() -> User {
    user(0xB0)
}

const EXP: u64 = 1_900_000_000;
const SENT_MS: u64 = 1_800_000_000_000;
const MSG_ID: [u8; 16] = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10];

fn face_card(u: &User) -> Card {
    let mut c = Card::new(u.sk, u.ik, u.otk, u.hk, CardKind::FaceToFace { loc: u.loc }, EXP);
    c.name = Some("alice".into());
    c.addrs = vec![addr::encode(&"192.168.1.5:47731".parse().unwrap()), addr::encode(&"[2001:db8::5]:47731".parse().unwrap())];
    c.sign_with(|m| u.signing.sign(m).to_bytes()).unwrap();
    c
}

fn remote_card(u: &User) -> Card {
    let mut c = Card::new(u.sk, u.ik, u.otk, u.hk, CardKind::Remote { ticket: [0x7a; 16], pake: true }, EXP);
    c.sign_with(|m| u.signing.sign(m).to_bytes()).unwrap();
    c
}

fn text_envelope() -> Envelope {
    Envelope { v: 1, id: MSG_ID.to_vec(), sent_ms: SENT_MS, body: Some(Body::Text(Text { body: "salam MLM CHAT · سلام".into(), ..Default::default() })), ..Default::default() }
}

fn frame_small() -> Vec<u8> {
    frame::encode_padded(&[], KIND_OLM, &[0x5a; 100]).unwrap()
}

fn frame_tagged() -> Vec<u8> {
    frame::encode_padded(&[0x7b; 16], KIND_OLM_PREKEY, &[0x5b; 300]).unwrap()
}

/// Every deterministic vector, by file name.
fn deterministic() -> Vec<(&'static str, Value)> {
    let (a, b) = (alice(), bob());
    let (ca, cb) = (personal_code(&a.sk, &a.ik), personal_code(&b.sk, &b.ik));
    let identity = json!({
        "about": "SPEC 2 and 14.1: personal code (30 digits) = libsignal NumericFingerprint v0, 5200 SHA-512 iterations over version||sk||ik then ||sk; security number = both codes, smaller first; display name = name·first 4 digits",
        "alice": { "ed25519_seed": hex::encode([0xA0u8; 32]), "sk": hex::encode(a.sk), "ik": hex::encode(a.ik), "code": ca },
        "bob": { "ed25519_seed": hex::encode([0xB0u8; 32]), "sk": hex::encode(b.sk), "ik": hex::encode(b.ik), "code": cb },
        "security_number": security_number(&ca, &cb),
        "display_name_alice": display_name("alice", &ca),
    });

    let face = face_card(&a);
    let remote = remote_card(&a);
    let card = json!({
        "about": "SPEC 3 and 14.3: deterministic CBOR map with integer keys, sig (15) = Ed25519 by sk over the map without key 15; hk = key 11; addrs = binary multiaddr /ip4|ip6/<a>/tcp/<port>",
        "inputs": {
            "ed25519_seed": hex::encode([0xA0u8; 32]), "ik": hex::encode(a.ik), "otk": hex::encode(a.otk), "hk": hex::encode(a.hk),
            "loc": hex::encode(a.loc), "name": "alice", "addrs": ["192.168.1.5:47731", "[2001:db8::5]:47731"], "exp": EXP,
            "remote_ticket": hex::encode([0x7au8; 16]), "remote_pake": true
        },
        "multiaddr_ip4": hex::encode(addr::encode(&"192.168.1.5:47731".parse().unwrap())),
        "multiaddr_ip6": hex::encode(addr::encode(&"[2001:db8::5]:47731".parse().unwrap())),
        "face_to_face_cbor": hex::encode(face.to_bytes()),
        "face_to_face_link": face.to_link(),
        "remote_cbor": hex::encode(remote.to_bytes()),
        "remote_link": remote.to_link(),
    });

    let text = text_envelope();
    let receipt = Envelope { v: 1, id: [0x22; 16].to_vec(), sent_ms: SENT_MS + 1, body: Some(Body::Receipt(mlmchat_core::envelope::Receipt { kind: ReceiptKind::Read as i32, ids: vec![MSG_ID.to_vec()] })), ..Default::default() };
    let key_update = Envelope { v: 1, id: [0x33; 16].to_vec(), sent_ms: SENT_MS + 2, body: Some(Body::KeyUpdate(mlmchat_core::envelope::KeyUpdate { loc: a.loc.to_vec(), card: face.to_bytes(), join: None })), ..Default::default() };
    let envelope = json!({
        "about": "SPEC 5: protobuf mlmchat.v1.Envelope, standard proto3 encoding (fields in number order, defaults left out)",
        "text": { "id": hex::encode(MSG_ID), "sent_ms": SENT_MS, "body": "salam MLM CHAT · سلام", "bytes": hex::encode(text.to_bytes().unwrap()) },
        "receipt_read": { "bytes": hex::encode(receipt.to_bytes().unwrap()) },
        "key_update": { "card": "face_to_face_cbor of card.json", "bytes": hex::encode(key_update.to_bytes().unwrap()) },
    });

    let fs = frame_small();
    let ft = frame_tagged();
    let frames = json!({
        "about": "SPEC 6 and 14.5: Frame padded to 256 B / 1 / 4 / 16 / 64 KiB with field 5; at a varint boundary an empty field 5 comes first; the acknowledgement id = first 16 bytes of SHA-256(frame)",
        "olm_100": { "tag": "", "kind": KIND_OLM, "cipher": hex::encode([0x5au8; 100]), "frame": hex::encode(&fs), "len": fs.len(), "ack_id": hex::encode(frame_id(&fs)) },
        "prekey_300_tagged": { "tag": hex::encode([0x7bu8; 16]), "kind": KIND_OLM_PREKEY, "cipher": hex::encode([0x5bu8; 300]), "frame": hex::encode(&ft), "len": ft.len() },
    });

    let bc = Beacon::new(&a.loc, 3_000_000 * beacon::EPOCH_S, 47731);
    let beacon_v = json!({
        "about": "SPEC 8 and 14.8: tag = HMAC-SHA256(loc, \"mlmchat/lan/1\" || epoch u32 BE)[..16]; beacon = epoch u32 BE || port u16 BE || tag",
        "loc": hex::encode(a.loc), "epoch": 3_000_000u32, "port": 47731,
        "tag": hex::encode(beacon::tag(&a.loc, 3_000_000)), "beacon": hex::encode(bc.to_bytes()),
    });

    let hh = [0x33u8; 32];
    let hello = hello_input(ROLE_INITIATOR, &hh);
    let direct = json!({
        "about": "SPEC 14.7: Noise_NNpsk0_25519_ChaChaPoly_SHA256, prologue \"mlmchat/direct/1\", psk = HKDF-SHA256(salt none, ikm loc, info \"mlmchat/direct/1/psk\"); hello signs \"mlmchat/direct/1/hello\" || role ('I' or 'R') || handshake hash",
        "loc": hex::encode(a.loc), "psk": hex::encode(psk(&a.loc)),
        "handshake_hash": hex::encode(hh), "hello_input_initiator": hex::encode(&hello),
        "hello_signature_alice": hex::encode(a.signing.sign(&hello).to_bytes()),
    });
    vec![("identity.json", identity), ("card.json", card), ("envelope.json", envelope), ("frame.json", frames), ("beacon.json", beacon_v), ("direct.json", direct)]
}

fn read(name: &str) -> Value {
    let text = std::fs::read_to_string(dir().join(name)).unwrap_or_else(|_| panic!("vectors/{name} is missing; generate once (see top of this file)"));
    serde_json::from_str(&text).unwrap()
}

fn unhex(v: &Value) -> Vec<u8> {
    hex::decode(v.as_str().unwrap()).unwrap()
}

#[test]
#[ignore]
fn generate() {
    if std::env::var("MLMCHAT_WRITE_VECTORS").as_deref() != Ok("1") {
        panic!("set MLMCHAT_WRITE_VECTORS=1 to write vectors; they are written once");
    }
    std::fs::create_dir_all(dir()).unwrap();
    for (name, v) in deterministic() {
        std::fs::write(dir().join(name), serde_json::to_string_pretty(&v).unwrap() + "\n").unwrap();
    }
    // HPKE uses a fresh ephemeral key, so this one is made once and only ever opened afterwards
    let secret_ikm = [0xC1u8; 32];
    let (_, pk) = X25519HkdfSha256::derive_keypair(&secret_ikm);
    let hk: [u8; 32] = pk.to_bytes().into();
    let sealed = frame::seal(&frame_small(), &alice().sk, &hk, &[0x5e; 16]).unwrap();
    let v = json!({
        "about": "SPEC 4, 6 and 14.6: SealedFrame = HPKE base mode RFC 9180, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20Poly1305, info \"mlmchat/sealed/1\", aad = tag; plaintext = SealedInner{1: frame, 2: from_sk}. Open it with the recipient key derived from recipient_ikm.",
        "recipient_ikm": hex::encode(secret_ikm), "recipient_hk": hex::encode(hk), "tag": hex::encode([0x5eu8; 16]),
        "sealed": hex::encode(&sealed), "frame": hex::encode(frame_small()), "from_sk": hex::encode(alice().sk),
    });
    std::fs::write(dir().join("sealed.json"), serde_json::to_string_pretty(&v).unwrap() + "\n").unwrap();
}

#[test]
fn deterministic_vectors_match() {
    for (name, expected) in deterministic() {
        assert_eq!(read(name), expected, "vectors/{name} differs from what the core makes");
    }
}

#[test]
fn vectors_parse_back() {
    let card = read("card.json");
    let face = Card::from_link(card["face_to_face_link"].as_str().unwrap()).unwrap();
    assert_eq!(face.loc(), Some(alice().loc));
    assert_eq!(face.to_bytes(), unhex(&card["face_to_face_cbor"]));
    let remote = Card::from_bytes(&unhex(&card["remote_cbor"])).unwrap();
    assert!(matches!(remote.kind, CardKind::Remote { pake: true, .. }));

    let env = read("envelope.json");
    let text = Envelope::from_bytes(&unhex(&env["text"]["bytes"])).unwrap();
    assert_eq!(text, text_envelope());

    let f = read("frame.json");
    let fr = frame::decode(&unhex(&f["olm_100"]["frame"])).unwrap();
    assert_eq!(fr.cipher, vec![0x5a; 100]);
    assert_eq!(unhex(&f["prekey_300_tagged"]["frame"]).len(), 1024);

    let b = read("beacon.json");
    let parsed = Beacon::from_bytes(&unhex(&b["beacon"])).unwrap();
    assert_eq!(parsed.recognise(&[([1; 32], alice().loc)], 3_000_000 * beacon::EPOCH_S), Some([1; 32]));

    let d = read("direct.json");
    let pk = vodozemac::Ed25519PublicKey::from_slice(&alice().sk).unwrap();
    let sig = vodozemac::Ed25519Signature::from_slice(&unhex(&d["hello_signature_alice"])).unwrap();
    assert!(pk.verify(&unhex(&d["hello_input_initiator"]), &sig).is_ok());

    let s = read("sealed.json");
    let ikm: [u8; 32] = unhex(&s["recipient_ikm"]).try_into().unwrap();
    let (secret, _) = X25519HkdfSha256::derive_keypair(&ikm);
    let opened = frame::open(&unhex(&s["sealed"]), &secret).unwrap();
    assert_eq!(opened.frame, unhex(&s["frame"]));
    assert_eq!(opened.from_sk.to_vec(), unhex(&s["from_sk"]));
    assert_eq!(opened.tag.to_vec(), unhex(&s["tag"]));
}
