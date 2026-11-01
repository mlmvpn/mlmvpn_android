//! MLM CHAT core (phase 1): everything that does not depend on the network stack choice (T11).
//!
//! The wire contract is `docs/SPEC.md`; reference vectors are in `vectors/`. No hand-made
//! cryptography: Olm comes from vodozemac, sealing from the `hpke` crate (RFC 9180), the
//! connection layer from `snow` (Noise), the database from SQLCipher.
//!
//! Logging rule (SPEC 0, T18): nothing this crate logs contains message text, keys, names,
//! numbers or addresses of contacts. Log lines carry only event kinds and counts.

pub mod addr;
pub mod beacon;
pub mod blob;
pub mod card;
pub mod census;
pub mod envelope;
pub mod feed;
pub mod error;
pub mod frame;
pub mod group;
pub mod identity;
pub mod megolm;
pub mod node;
pub mod olm;
pub mod pair;
pub mod queue;
pub mod store;
pub mod transport;
pub mod util;

pub use error::{Error, Result};

/// The 32-byte Ed25519 key that is a user's identity (SPEC 2: user id = `sk`).
pub type UserId = [u8; 32];

/// Smoke check that the bundled SQLCipher is the one linked.
pub fn sqlcipher_version() -> String {
    rusqlite::Connection::open_in_memory()
        .and_then(|c| c.query_row("PRAGMA cipher_version", [], |r| r.get::<_, String>(0)))
        .unwrap_or_default()
}
