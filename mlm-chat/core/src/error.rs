//! Error type. Messages are fixed strings plus non-sensitive detail only (no keys, text, names).

use crate::transport::TransportError;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("card: {0}")]
    Card(&'static str),
    #[error("link: {0}")]
    Link(&'static str),
    #[error("envelope: {0}")]
    Envelope(&'static str),
    #[error("frame: {0}")]
    Frame(&'static str),
    #[error("sealed frame: {0}")]
    Sealed(&'static str),
    #[error("session: {0}")]
    Session(&'static str),
    #[error("identity: {0}")]
    Identity(&'static str),
    #[error("not found: {0}")]
    NotFound(&'static str),
    /// A Google mailbox relay (SPEC 19): a stable code the app turns into its own words
    /// (`key`, `not_found`, `auth`, `quota`, `busy`, `paused`, `limit`, `network`, `front`, ...).
    #[error("relay: {0}")]
    Relay(&'static str),
    #[error("store: {0}")]
    Store(#[from] rusqlite::Error),
    #[error("transport: {0}")]
    Transport(#[from] TransportError),
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("protobuf: {0}")]
    Decode(#[from] prost::DecodeError),
}
