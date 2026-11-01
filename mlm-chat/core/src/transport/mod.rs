//! The transport layer, behind one trait. The node gives a transport padded frames for one
//! contact and gets frames back through `Hooks`.
//!
//! - `session`: the SPEC 14.7 session (Noise NNpsk0 keyed on the locator, signed hellos, frames
//!   each acknowledged), on any pipe.
//! - `direct`: TCP on the LAN or direct IPv6, private LAN beacons (phase 1).
//! - `p2p`: libp2p (T11): the private DHT with presence records, volunteer relays (circuit relay
//!   v2), DCUtR; the same session on a `/mlmchat/msg/1` stream (phase 3, SPEC 15).
//!
//! - `gas`: a Google Apps Script mailbox in a user's own account (SPEC 19): for two phones that
//!   cannot reach each other (two operators), both reach Google; end-to-end sealed frames wait in
//!   the recipient's mailbox. Slower than the others, so it is tried last.
//! - `scope`: the address ranges the node may connect to, when the app sets them (SPEC 15.10).
//! - `ble`: Bluetooth LE, a contact right beside us with no network at all; the same session on a
//!   GATT link the platform's radio code opens (SPEC 16).
//!
//! The node keeps the route memory and tries the transports in the order the current network
//! has proven (SPEC 15.5).
//!
//! No unencrypted transport exists (SPEC 9): every pipe carries only the Noise session.

pub mod ble;
pub mod direct;
pub mod gas;
pub mod lan;
pub mod nostr;
pub mod p2p;
pub mod scope;
pub mod session;

use crate::UserId;
use std::net::{IpAddr, SocketAddr};

/// Why a delivery did not happen. Kinds only: no address, key or name (SPEC 0).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum TransportError {
    #[error("no known route to the contact")]
    NoRoute,
    #[error("could not connect")]
    Connect,
    #[error("timed out")]
    Timeout,
    #[error("encrypted handshake failed")]
    Handshake,
    #[error("the other side is not the expected contact")]
    WrongPeer,
    #[error("the other side refused the connection")]
    Refused,
    #[error("protocol violation: {0}")]
    Protocol(&'static str),
    #[error("transport stopped")]
    Stopped,
}

impl TransportError {
    /// A stable code the app turns into its own words («در صف» and why, SPEC 0 honesty rule).
    pub fn code(&self) -> &'static str {
        match self {
            TransportError::NoRoute => "no_route",
            TransportError::Connect => "connect",
            TransportError::Timeout => "timeout",
            TransportError::Handshake => "handshake",
            TransportError::WrongPeer => "wrong_peer",
            TransportError::Refused => "refused",
            TransportError::Protocol(_) => "protocol",
            TransportError::Stopped => "stopped",
        }
    }
}

/// What a transport needs to reach one contact.
#[derive(Clone)]
pub struct PeerInfo {
    pub sk: UserId,
    /// The contact's locator. The direct transport keys the connection's pre-shared secret on it
    /// (SPEC 14.7), so only someone who holds it can even finish the handshake.
    pub loc: [u8; 32],
    /// Addresses from the contact's card (SPEC 3 `addrs`), tried after a fresh LAN sighting.
    pub addrs: Vec<SocketAddr>,
    /// The contact's sealing key (card key 11): the mailbox transport seals frames to it.
    pub hk: [u8; 32],
    /// The contact's mailbox relays (card key 12, SPEC 19.3), `gas::RelayRef` bytes.
    pub relays: Vec<Vec<u8>>,
    /// The Nostr relays the contact reads from (card key 13, SPEC 20): names without `wss://`.
    pub nostr: Vec<String>,
}

impl PeerInfo {
    /// Only what the direct and p2p transports need.
    pub fn bare(sk: UserId, loc: [u8; 32], addrs: Vec<SocketAddr>) -> PeerInfo {
        PeerInfo { sk, loc, addrs, hk: [0; 32], relays: Vec::new(), nostr: Vec::new() }
    }
}

/// What the receiving node decided about one frame.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// Stored (or a known duplicate): acknowledge it.
    Stored,
    /// Will never be usable (bad frame, unknown session): acknowledge, so the sender stops.
    Dropped,
    /// Could not be handled now (database busy): no acknowledgement, the sender retries.
    Retry,
}

/// How frames got through: `lan` (a LAN beacon sighting), `direct` (an address of the card),
/// `p2p` (libp2p, straight to the contact), `relay` (libp2p through a volunteer's circuit).
pub type Via = &'static str;

/// A delivery: the contact's device acknowledged the first `verdicts.len()` frames, each with its
/// verdict (fewer than all when the connection dropped midway), on the route `via`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Sent {
    pub verdicts: Vec<Verdict>,
    pub via: Via,
}

/// The node's side of the contract: keys, contacts, and where incoming frames go.
pub trait Hooks: Send + Sync + 'static {
    /// Our own sk and loc.
    fn me(&self) -> (UserId, [u8; 32]);
    /// Ed25519 signature by our identity key (binds a connection to the identity, SPEC 14.7).
    fn sign(&self, message: &[u8]) -> [u8; 64];
    /// Whether frames from this user are accepted (a contact, or a stranger who proved they
    /// hold our locator, unless blocked).
    fn accept_peer(&self, sk: &UserId) -> bool;
    /// Locators of contacts, to recognise their LAN beacons.
    fn contact_locs(&self) -> Vec<(UserId, [u8; 32])>;
    /// One frame from `from` (proven by the connection). Must return quickly.
    fn on_frame(&self, from: UserId, frame: Vec<u8>) -> Verdict;
    /// A contact showed up somewhere reachable (`via`: `lan` for a LAN beacon, `ble` for a
    /// Bluetooth advert): retry its queue now.
    fn on_seen(&self, sk: UserId, via: Via);
    /// A contact opened a session with us and proved itself (`via` as in [`Sent`]): it is online
    /// right now, so its queue is retried at once.
    fn on_reached(&self, sk: UserId, via: Via);
    /// Volunteers to use: remembered on this device and named in the contacts' cards (`boot`),
    /// binary multiaddrs with `/p2p/<id>`, the most recently confirmed first.
    fn volunteers(&self) -> Vec<Vec<u8>>;
    /// A volunteer proved itself (a relay that identified): keep these addresses of it.
    fn remember_volunteers(&self, addrs: Vec<Vec<u8>>);
    /// Contacts to tell "I am back online" (SPEC 15.4): the most recently active first.
    fn announce_targets(&self, max: usize) -> Vec<(UserId, [u8; 32])>;
    /// The connection ladder's memory on the network we are on now (SPEC 15.9): the network's
    /// label and one row per rung key.
    fn ladder_stats(&self) -> (String, Vec<crate::store::RouteStat>) {
        (String::new(), Vec::new())
    }
    /// How one rung did on network `net`, under each of its keys (address hash, class).
    fn ladder_result(&self, _net: &str, _keys: &[String], _ok: bool, _ms: u64) {}
    /// May we connect to `ip`, or accept a connection from it (SPEC 15.10)? Every transport asks
    /// before each dial and on each inbound connection.
    fn allows(&self, _ip: IpAddr) -> bool {
        true
    }
    /// A volunteer's kept totals (SPEC 15.12): add new peers and bytes, get the totals back.
    fn relay_add(&self, served: u64, bytes: u64) -> (u64, u64) {
        (served, bytes)
    }
    /// A frame passed through a mailbox with this contact: it came out of one of ours (`inbound`)
    /// or went into theirs. Says nothing about the contact being reachable now.
    fn on_mailbox(&self, _peer: UserId, _inbound: bool) {}
    /// The label of the network we are on now (route memory is kept per network).
    fn network_label(&self) -> String {
        String::new()
    }
    /// Open a SealedFrame addressed to us (SPEC 14.6): the padded frame inside, the sender's sk
    /// (a claim until Olm proves it) and the recipient tag. None when it is not for us.
    fn open_sealed(&self, _bytes: &[u8]) -> Option<crate::frame::Opened> {
        None
    }
    /// The official feeds whose items are read on our own relays (SPEC 18.7, T77), and those
    /// relays; nothing is read when either is empty.
    fn feed_subscription(&self) -> (Vec<Vec<u8>>, Vec<String>) {
        (Vec::new(), Vec::new())
    }
    /// Official items read on our relays: kept as items from a contact are, never passed on.
    fn on_feed_items(&self, _items: Vec<crate::feed::SignedFeedItem>) {}
}

/// One way of carrying frames between devices.
pub trait Transport: Send + Sync + 'static {
    fn name(&self) -> &'static str;
    /// Deliver frames to one contact, in order. `Err` only when none got through.
    fn send(&self, peer: &PeerInfo, frames: &[Vec<u8>]) -> Result<Sent, TransportError>;
    /// Addresses others can dial us on, for the card's `addrs`.
    fn advertised_addrs(&self) -> Vec<SocketAddr>;
    /// Volunteers for the card's `boot` (SPEC 3).
    fn boot(&self) -> Vec<Vec<u8>> {
        Vec::new()
    }
    /// A route to this contact is known to work right now (a LAN sighting, an open connection):
    /// the node tries this transport first.
    fn has_fresh_route(&self, _sk: &UserId) -> bool {
        false
    }
    fn stop(&self);
}
