//! p2p transport (phase 3, T11 = libp2p, SPEC 15): how two phones on different networks reach
//! each other with no server.
//!
//! - **Volunteers** (PLAN 11) are ordinary nodes that others can dial: a Circuit Relay v2 server
//!   and a Kademlia server on the private protocol `/mlmchat/kad/1`. They come from the contacts'
//!   cards (`boot`, SPEC 3), from the relays named in contacts' presence records, from the app,
//!   or from the config.
//! - A volunteer opens its own door on the router (`door`: UPnP IGD, NAT-PMP, PCP, one TCP port,
//!   leases of 2 hours at most) and announces an address only once a peer on another network
//!   has really connected to it there (`reach`, SPEC 15.9).
//! - Every other node keeps a connection to up to [`MAX_RESERVATIONS`] volunteers, found by the
//!   **ladder** (`ladder`): one address at a time, in the order this network has proven, each
//!   with a short timeout before the next starts beside it, and one quick retry on a fresh
//!   connection for a rung that timed out (Iranian networks throttle per connection). It holds
//!   a relay reservation on each and puts its **presence record** (SPEC 8, `presence.rs`) in the
//!   DHT: its current peer id, its relay circuits and the direct addresses worth trying.
//! - To deliver, the sender looks the contact's record up by their locator, dials the addresses
//!   in it (libp2p tries them together, DCUtR may then upgrade a relayed connection to a direct
//!   one), opens a `/mlmchat/msg/1` stream and runs the same SPEC 14.7 session as the direct
//!   transport on it. A volunteer in the middle sees two Noise-encrypted libp2p connections, and
//!   inside them only the session's own Noise ciphertext.
//!
//! Nothing here logs an address, a peer id, a key or a name: only kinds and counts.

pub mod door;
pub mod ladder;
pub mod ledger;
mod pipe;
pub mod presence;
pub mod reach;
pub mod tls;

use super::session::{self, Conn};
use super::{Hooks, PeerInfo, Sent, Transport, TransportError};
use crate::store::RouteStat;
use crate::util::{now_ms, now_s, random};
use crate::UserId;
use futures::{AsyncReadExt, AsyncWriteExt, StreamExt};
use ladder::Rung;
use libp2p::core::transport::ListenerId;
use libp2p::core::ConnectedPoint;
use libp2p::kad::store::RecordStore;
use libp2p::multiaddr::Protocol;
use libp2p::swarm::behaviour::toggle::Toggle;
use libp2p::swarm::dial_opts::{DialOpts, PeerCondition};
use libp2p::swarm::{ConnectionId, DialError, NetworkBehaviour, SwarmEvent};
use libp2p::{dcutr, identify, identity, kad, noise, ping, relay, tcp, yamux, PeerId, StreamProtocol, Swarm, Transport as _};
pub use libp2p::Multiaddr as Multiaddr;
use pipe::BlockingStream;
use presence::PresenceBody;
use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::runtime::{Handle, Runtime};
use tokio::sync::{mpsc, oneshot};

pub const MSG_PROTOCOL: StreamProtocol = StreamProtocol::new("/mlmchat/msg/1");
pub const KAD_PROTOCOL: StreamProtocol = StreamProtocol::new("/mlmchat/kad/1");
pub const REACH_PROTOCOL: StreamProtocol = StreamProtocol::new("/mlmchat/reach/1");
pub const ID_PROTOCOL: &str = "/mlmchat/id/1";
/// What identify says about us: the protocol family only, no app name or version.
pub const AGENT: &str = "mlmchat";
const HOP_PROTOCOL: &str = "/libp2p/circuit/relay/0.2.0/hop";
/// Relay reservations a normal node keeps at once.
pub const MAX_RESERVATIONS: usize = 2;
/// Volunteers a card carries (SPEC 3: at most 3).
const MAX_BOOT: usize = 3;
const MAX_INBOUND: usize = 32;
const TICK: Duration = Duration::from_secs(2);
const VOLUNTEERS_REFRESH: Duration = Duration::from_secs(10);
const PRESENCE_CACHE: Duration = Duration::from_secs(90);
/// A volunteer's circuits (SPEC 12: enough for text; files get their own quota later).
const CIRCUIT_SECS: u64 = 120;
const CIRCUIT_BYTES: u64 = 1 << 20;
/// Announcing "I am online" goes to at most this many recent contacts.
const ANNOUNCE_MAX: usize = 20;
/// TCP connect, TLS and Noise together must finish within this (the transport's own timeout).
const CONNECT_TIMEOUT: Duration = Duration::from_secs(12);
/// Pause between ladder rounds that reached no volunteer.
const BACKOFF_S: [u64; 6] = [5, 15, 30, 60, 120, 300];
/// Volunteers a volunteer stays connected to (the DHT between volunteers).
const VOLUNTEER_PEERS: usize = 8;
/// Reach checks: an unverified candidate is asked about this often, a verified one rechecked
/// this often; a verified address with no confirmation for `VERIFIED_FOR_MS` is no longer
/// announced.
const ASK_GAP: Duration = Duration::from_secs(30);
const RECHECK: Duration = Duration::from_secs(20 * 60);
const HELPER_GAP: Duration = Duration::from_secs(60);
const VERIFIED_FOR_MS: u64 = 24 * 3600 * 1000;
/// A helper dials back at most once per requester per this, and this many times a minute.
const REACH_PEER_GAP: Duration = Duration::from_secs(30);
const REACH_PER_MIN: usize = 6;
/// A dial-back connection is closed this long after it opened.
const DIALBACK_CLOSE: Duration = Duration::from_secs(2);
/// Candidate sources that are a door on the router.
const DOOR_SOURCES: [&str; 5] = ["upnp4", "pinhole6", "natpmp", "pcp4", "pcp6"];
/// A peer its volunteer's operator disconnected stays out this long.
const KICK_FOR: Duration = Duration::from_secs(3600);
/// A volunteer's kept totals go to the device this often (SPEC 15.12).
const LEDGER_FLUSH: Duration = Duration::from_secs(30);

#[derive(Clone, Debug)]
pub struct P2pConfig {
    /// TCP listen addresses. Empty = `/ip4/0.0.0.0/tcp/<port>` and `/ip6/::/tcp/<port>`.
    pub listen: Vec<Multiaddr>,
    /// Port for the default listen addresses (0 = any free port).
    pub port: u16,
    /// More ports to listen on as well (a volunteer: 443, 80, 8080...), so a network that filters
    /// one port still gets through another. A port that cannot be bound is skipped.
    pub more_ports: Vec<u16>,
    /// Volunteer: relay server and DHT server for everyone (PLAN 11). Off unless the user opts in.
    pub volunteer: bool,
    /// Addresses others dial us on, as the operator says (a volunteer behind a forwarded port).
    /// Trusted as they are: announced without a reach check.
    pub external: Vec<Multiaddr>,
    /// A volunteer opens its own door on the router for `port` (UPnP IGD v1/v2 with the IPv6
    /// pinhole, NAT-PMP, PCP; SPEC 15.9).
    pub upnp: bool,
    /// Volunteers to use from the start (`.../p2p/<peer id>`), beside those in the contacts' cards.
    pub boot: Vec<Multiaddr>,
    /// The libp2p key (protobuf encoding). Empty = a fresh one for this run.
    pub key: Vec<u8>,
    /// Publish direct listen addresses in the presence record too (off = circuits only, which is
    /// what the tests use to prove the relay path).
    pub publish_direct: bool,
    /// Treat loopback addresses as usable (tests on one machine).
    pub allow_loopback: bool,
    /// Tests only: a peer on the same machine or LAN counts as "another network" for the reach
    /// check.
    pub trust_lan_reach: bool,
    /// Try DCUtR hole punching after a relayed connection. Off only to prove the relay path.
    pub hole_punch: bool,
    /// The ladder: how long one rung has before the next one starts beside it.
    pub rung_stagger: Duration,
    pub dial_timeout: Duration,
    pub io_timeout: Duration,
    /// Presence is put again this often even when nothing changed.
    pub republish: Duration,
}

impl Default for P2pConfig {
    fn default() -> Self {
        P2pConfig {
            listen: Vec::new(),
            port: 0,
            more_ports: Vec::new(),
            volunteer: false,
            external: Vec::new(),
            upnp: false,
            boot: Vec::new(),
            key: Vec::new(),
            publish_direct: true,
            allow_loopback: false,
            trust_lan_reach: false,
            hole_punch: true,
            rung_stagger: Duration::from_secs(4),
            dial_timeout: Duration::from_secs(14),
            io_timeout: Duration::from_secs(20),
            republish: Duration::from_secs(20 * 60),
        }
    }
}

/// What the app shows as the network state (honest status line). Counts and kinds only, except
/// a volunteer's own verified addresses (its operator hands them out).
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Serialize)]
pub struct NetStatus {
    pub running: bool,
    pub volunteer: bool,
    /// Volunteers this node knows of (config, cards, presence records, memory).
    pub volunteers_known: usize,
    pub volunteers_connected: usize,
    /// Relay reservations held right now: others can reach us through that many volunteers.
    pub reservations: usize,
    /// Addresses others can dial us on directly.
    pub external_addrs: usize,
    /// When our presence record last reached the DHT (0 = never).
    pub published_ms: u64,
    /// The ladder (SPEC 15.9): `idle` (no volunteer known), `probing`, `reachable` (connected to
    /// a volunteer), `unreachable` (the last round reached none; the next starts in `next_try_s`).
    pub ladder: &'static str,
    /// The class of the rung in use: `v4:443`, `v6:40443`, `lan:40443`; empty if none.
    pub rung: String,
    /// Rungs tried and rungs in the current round (else the last one).
    pub tried: usize,
    pub total: usize,
    /// What most rungs of the last round failed with: `timeout`, `refused`, `unreachable`, ...
    pub last_error: &'static str,
    pub next_try_s: u64,
    /// Volunteer addresses the last round left out because the node's scope does not allow
    /// them (SPEC 15.10: «inside Iran only» leaves out every address abroad and every IPv6).
    pub out_of_scope: usize,
    /// A volunteer's door on its router: what each method did.
    pub door: Vec<door::MethodResult>,
    /// A volunteer's candidate addresses, and those a peer on another network really reached
    /// (only these are announced).
    pub candidates: usize,
    pub verified: Vec<String>,
    /// A volunteer's own full addresses (`.../p2p/<id>`), for its operator to hand out: those its
    /// card carries, then its LAN ones (only for peers on the same network). Empty otherwise.
    pub volunteer_addrs: Vec<String>,
}

#[derive(NetworkBehaviour)]
struct Behaviour {
    relay_client: relay::client::Behaviour,
    relay_server: Toggle<relay::Behaviour>,
    dcutr: Toggle<dcutr::Behaviour>,
    identify: identify::Behaviour,
    ping: ping::Behaviour,
    kad: kad::Behaviour<kad::store::MemoryStore>,
    stream: libp2p_stream::Behaviour,
}

enum Cmd {
    /// Raw values found under these DHT keys.
    Lookup { keys: Vec<[u8; 32]>, reply: oneshot::Sender<Vec<Vec<u8>>> },
    /// Be connected to `peer` (dialing `addrs` if needed); `true` = only through relays.
    Connect { peer: PeerId, addrs: Vec<Multiaddr>, reply: oneshot::Sender<Result<bool, TransportError>> },
    /// Signed presence records to put (made off the runtime, the signature needs the identity).
    Put(Vec<([u8; 32], Vec<u8>)>),
    Volunteers(Vec<Multiaddr>),
    NetworkChanged,
    /// The network's label changed (the app named it): the ladder's memory of that one.
    Relabel,
    /// The ladder's memory for the network we are on now.
    LadderStats(String, Vec<RouteStat>),
    /// A peer asked us to dial it at `addr` on a fresh connection (reach check).
    DialBack { peer: PeerId, addr: Multiaddr, reply: oneshot::Sender<bool> },
    /// A helper's answer about one of our candidates (`None`: no answer).
    ReachDone { peer: PeerId, status: Option<u8> },
    /// The door thread opened these (method, external address, internal port).
    DoorOpened { opened: Vec<(&'static str, SocketAddr, u16)>, results: Vec<door::MethodResult> },
    DoorLost(Vec<SocketAddr>),
    /// A volunteer's operator disconnects one peer of its ledger, by its short name, and keeps
    /// it out for [`KICK_FOR`].
    Kick { name: String, reply: oneshot::Sender<bool> },
    /// The device added `.0` (new peers, bytes) to the kept totals, which are now `.1` (SPEC 15.12).
    RelayTotals((u64, u64), (u64, u64)),
    Stop,
}

/// What both the runtime and the std side read.
struct Shared {
    stopped: Arc<AtomicBool>,
    status: Mutex<NetStatus>,
    /// Open connections per peer: (direct, relayed).
    links: Mutex<HashMap<PeerId, (usize, usize)>>,
    /// Peers a hole punch (DCUtR) reached directly.
    punched: Mutex<HashSet<PeerId>>,
    /// The volunteers we use, as card `boot` entries.
    boot: Mutex<Vec<Vec<u8>>>,
    /// Who proved which sk on an inbound session: a reply can skip the DHT while connected.
    known: Mutex<HashMap<UserId, PeerId>>,
    inbound: AtomicUsize,
    /// The door thread: told to open again after a network change, joined on stop (it deletes
    /// its mappings).
    door_reopen: AtomicBool,
    door_thread: Mutex<Option<std::thread::JoinHandle<()>>>,
    /// Bytes on the wire, and a volunteer's ledger of who uses it (SPEC 15.12).
    traffic: Arc<ledger::Traffic>,
    ledger: Mutex<ledger::Ledger>,
}

impl Shared {
    fn relayed_only(&self, peer: &PeerId) -> bool {
        self.links.lock().expect("links").get(peer).is_some_and(|(d, r)| *d == 0 && *r > 0)
    }

    fn via(&self, peer: &PeerId) -> &'static str {
        if self.relayed_only(peer) {
            "relay"
        } else if self.punched.lock().expect("punched").contains(peer) {
            "holepunch"
        } else {
            "p2p"
        }
    }
}

pub struct P2pTransport {
    client: Arc<Client>,
    key: Vec<u8>,
    rt: Mutex<Option<Runtime>>,
}

/// The sending side, shared by the transport and the announce thread.
struct Client {
    hooks: Arc<dyn Hooks>,
    cfg: P2pConfig,
    me: PeerId,
    handle: Handle,
    cmd: mpsc::UnboundedSender<Cmd>,
    control: libp2p_stream::Control,
    shared: Arc<Shared>,
    cache: Mutex<HashMap<UserId, (Instant, PresenceBody)>>,
}

impl P2pTransport {
    pub fn start(cfg: P2pConfig, hooks: Arc<dyn Hooks>) -> std::io::Result<Arc<P2pTransport>> {
        let key = if cfg.key.is_empty() {
            identity::Keypair::generate_ed25519()
        } else {
            identity::Keypair::from_protobuf_encoding(&cfg.key).map_err(|_| io_err("bad libp2p key"))?
        };
        let key_bytes = key.to_protobuf_encoding().map_err(|_| io_err("libp2p key"))?;
        let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).thread_name("mlmchat-p2p").enable_all().build()?;
        let handle = rt.handle().clone();
        let traffic = Arc::new(ledger::Traffic::default());
        let mut swarm = handle.block_on(async { build_swarm(key, &cfg, hooks.clone(), traffic.clone()) }).map_err(|_| io_err("libp2p setup failed"))?;
        let peer_id = *swarm.local_peer_id();
        let mut control = swarm.behaviour().stream.new_control();
        let incoming = control.accept(MSG_PROTOCOL).map_err(|_| io_err("stream protocol taken"))?;
        let reach_in = control.accept(REACH_PROTOCOL).map_err(|_| io_err("stream protocol taken"))?;

        let mut cfg = cfg;
        for list in [&mut cfg.listen, &mut cfg.external, &mut cfg.boot] {
            *list = list.drain(..).map(with_tls).collect();
        }
        let mut listen: Vec<Multiaddr> = if cfg.listen.is_empty() {
            vec![format!("/ip4/0.0.0.0/tcp/{}/tls", cfg.port).parse().expect("multiaddr"), format!("/ip6/::/tcp/{}/tls", cfg.port).parse().expect("multiaddr")]
        } else {
            cfg.listen.clone()
        };
        for p in cfg.more_ports.iter().filter(|p| **p != 0 && **p != cfg.port) {
            listen.push(format!("/ip4/0.0.0.0/tcp/{p}/tls").parse().expect("multiaddr"));
            listen.push(format!("/ip6/::/tcp/{p}/tls").parse().expect("multiaddr"));
        }
        let mut listening = 0;
        handle.block_on(async {
            for a in &listen {
                if swarm.listen_on(a.clone()).is_ok() {
                    listening += 1;
                }
            }
        });
        if listening == 0 {
            return Err(io_err("libp2p: nothing to listen on"));
        }
        swarm.behaviour_mut().kad.set_mode(Some(if cfg.volunteer { kad::Mode::Server } else { kad::Mode::Client }));

        let shared = Arc::new(Shared {
            stopped: Arc::new(AtomicBool::new(false)),
            status: Mutex::new(NetStatus { running: true, volunteer: cfg.volunteer, ladder: "idle", ..Default::default() }),
            links: Mutex::new(HashMap::new()),
            punched: Mutex::new(HashSet::new()),
            boot: Mutex::new(Vec::new()),
            known: Mutex::new(HashMap::new()),
            inbound: AtomicUsize::new(0),
            door_reopen: AtomicBool::new(false),
            door_thread: Mutex::new(None),
            traffic,
            ledger: Mutex::new(ledger::Ledger::default()),
        });
        let (tx, rx) = mpsc::unbounded_channel();
        let client = Arc::new(Client { hooks: hooks.clone(), cfg: cfg.clone(), me: peer_id, handle: handle.clone(), cmd: tx.clone(), control, shared: shared.clone(), cache: Mutex::new(HashMap::new()) });
        // the ladder starts with what this network already proved
        let (net, lstats) = hooks.ladder_stats();
        let now = Instant::now();
        let mut task = Task {
            swarm,
            client: client.clone(),
            hooks: hooks.clone(),
            cfg: cfg.clone(),
            shared: shared.clone(),
            tx: tx.clone(),
            lookups: HashMap::new(),
            lookup_queries: HashMap::new(),
            next_lookup: 0,
            dials: HashMap::new(),
            contact_dials: HashMap::new(),
            vols: HashMap::new(),
            circuits: HashMap::new(),
            listen_addrs: HashSet::new(),
            publish_at: Some(now + Duration::from_secs(1)),
            published_at: None,
            volunteers_at: None,
            announced_at: None,
            put_queries: HashSet::new(),
            net,
            lstats,
            stats_pending: None,
            round: None,
            attempts: HashMap::new(),
            round_at: now,
            backoff: 0,
            last_round: (0, 0, ""),
            out_of_scope: 0,
            cands: Vec::new(),
            my4: HashSet::new(),
            reach_peers: HashSet::new(),
            conn_ips: HashMap::new(),
            asking: None,
            helper_at: HashMap::new(),
            dialbacks: HashMap::new(),
            closing: Vec::new(),
            door_results: Vec::new(),
            door_started: false,
            kicked: HashMap::new(),
            ledger_at: None,
            fresh_ports_until: now,
        };
        for a in cfg.external.clone() {
            task.add_cand(a, "config", 0);
        }
        handle.spawn(task.run(rx));
        handle.spawn(serve_inbound(incoming, hooks.clone(), shared.clone(), handle.clone(), cfg.io_timeout));
        handle.spawn(serve_reach(reach_in, tx.clone(), shared.clone(), cfg.allow_loopback));
        log::info!("p2p: started (volunteer={})", cfg.volunteer);
        Ok(Arc::new(P2pTransport { client, key: key_bytes, rt: Mutex::new(Some(rt)) }))
    }

    pub fn peer_id(&self) -> PeerId {
        self.client.me
    }

    /// The libp2p key, to keep it (a volunteer's must stay the same: cards carry its peer id).
    pub fn key(&self) -> Vec<u8> {
        self.key.clone()
    }

    pub fn status(&self) -> NetStatus {
        self.client.shared.status.lock().expect("status").clone()
    }

    /// The network under us changed (Wi-Fi to mobile data...): drop every connection, climb the
    /// ladder again with this network's memory, put a fresh presence record.
    pub fn network_changed(&self) {
        self.client.cache.lock().expect("cache").clear();
        let _ = self.client.cmd.send(Cmd::NetworkChanged);
    }

    /// The app named the network we are on (the same one): results go under that name now.
    pub fn network_relabeled(&self) {
        let _ = self.client.cmd.send(Cmd::Relabel);
    }

    /// A volunteer's ledger (SPEC 15.12): who uses it now, how much, and the kept totals.
    pub fn relay_status(&self) -> ledger::RelayStatus {
        let s = &self.client.shared;
        let (rx, tx) = (s.traffic.rx.load(Ordering::Relaxed), s.traffic.tx.load(Ordering::Relaxed));
        s.ledger.lock().expect("ledger").status(self.client.cfg.volunteer, rx, tx)
    }

    /// Disconnect the peer shown under `name` in the ledger, and keep it out for an hour. False if
    /// no such peer is connected.
    pub fn relay_kick(&self, name: &str) -> bool {
        let (tx, rx) = oneshot::channel();
        if self.client.cmd.send(Cmd::Kick { name: name.to_string(), reply: tx }).is_err() {
            return false;
        }
        self.client.wait(rx, Duration::from_secs(5)).unwrap_or(false)
    }
}

impl Client {
    fn stopped(&self) -> bool {
        self.shared.stopped.load(Ordering::Relaxed)
    }

    /// The contact's newest valid presence record (cached for a short while). The volunteers
    /// its circuits go through are worth knowing too: they reach the ladder.
    fn presence(&self, peer: &PeerInfo) -> Result<PresenceBody, TransportError> {
        if let Some((at, body)) = self.cache.lock().expect("cache").get(&peer.sk) {
            if at.elapsed() < PRESENCE_CACHE {
                return Ok(body.clone());
            }
        }
        let now = now_s();
        let days = presence::lookup_days(now);
        let keys: Vec<[u8; 32]> = days.iter().map(|d| presence::dht_key(&peer.loc, *d)).collect();
        let (tx, rx) = oneshot::channel();
        self.cmd.send(Cmd::Lookup { keys, reply: tx }).map_err(|_| TransportError::Stopped)?;
        let values = self.wait(rx, Duration::from_secs(20))?;
        let now_ms = now_ms();
        let best = values.iter().filter_map(|v| days.iter().find_map(|d| presence::open(&peer.loc, *d, &peer.sk, v, now_ms))).max_by_key(|b| b.ts_ms).ok_or(TransportError::NoRoute)?;
        let relays: Vec<Multiaddr> = best.addrs.iter().filter_map(|a| Multiaddr::try_from(a.clone()).ok()).filter_map(|a| relay_part(&a)).collect();
        if !relays.is_empty() {
            let _ = self.cmd.send(Cmd::Volunteers(relays));
        }
        self.cache.lock().expect("cache").insert(peer.sk, (Instant::now(), best.clone()));
        Ok(best)
    }

    fn wait<T>(&self, rx: oneshot::Receiver<T>, timeout: Duration) -> Result<T, TransportError> {
        self.handle.block_on(async { tokio::time::timeout(timeout, rx).await }).map_err(|_| TransportError::Timeout)?.map_err(|_| TransportError::Stopped)
    }

    /// Connect and open a session with the contact; also says how (`relay`, `holepunch`, `p2p`).
    fn open(&self, peer: &PeerInfo) -> Result<(Conn, &'static str), TransportError> {
        let known = self.shared.known.lock().expect("known").get(&peer.sk).copied().filter(|p| self.shared.links.lock().expect("links").contains_key(p));
        let (pid, addrs) = match known {
            Some(p) => (p, Vec::new()),
            None => {
                let body = self.presence(peer)?;
                let pid = PeerId::from_bytes(&body.peer_id).map_err(|_| TransportError::Protocol("bad presence record"))?;
                let addrs: Vec<Multiaddr> = body
                    .addrs
                    .iter()
                    .filter_map(|a| Multiaddr::try_from(a.clone()).ok())
                    .filter(|a| (self.cfg.allow_loopback || !is_loopback(a)) && ip_of(a).is_none_or(|ip| self.hooks.allows(ip)))
                    .collect();
                (pid, addrs)
            }
        };
        if pid == self.me {
            return Err(TransportError::NoRoute);
        }
        let (tx, rx) = oneshot::channel();
        self.cmd.send(Cmd::Connect { peer: pid, addrs, reply: tx }).map_err(|_| TransportError::Stopped)?;
        self.wait(rx, self.cfg.dial_timeout + Duration::from_secs(2))?.inspect_err(|e| log::debug!("p2p: connect failed ({})", e.code()))?;
        // how we reached them is how the connection the session runs on was made; read it now: a
        // hole punch (DCUtR) may finish while the session's handshake is still going on
        let via = self.shared.via(&pid);
        let mut control = self.control.clone();
        let stream = self
            .handle
            .block_on(async { tokio::time::timeout(self.cfg.io_timeout, control.open_stream(pid, MSG_PROTOCOL)).await })
            .map_err(|_| TransportError::Timeout)?
            .map_err(|e| {
                log::debug!("p2p: no stream ({})", match e {
                    libp2p_stream::OpenStreamError::UnsupportedProtocol(_) => "unsupported",
                    libp2p_stream::OpenStreamError::Io(_) => "io",
                    _ => "other",
                });
                TransportError::Connect
            })?;
        let pipe = BlockingStream::new(stream, self.handle.clone(), self.cfg.io_timeout, self.shared.stopped.clone());
        let conn = session::initiate(Box::new(pipe), &*self.hooks, peer)?;
        Ok((conn, via))
    }

    fn send(&self, peer: &PeerInfo, frames: &[Vec<u8>]) -> Result<Sent, TransportError> {
        if self.stopped() {
            return Err(TransportError::Stopped);
        }
        match self.open(peer) {
            Ok((mut conn, via)) => session::send_frames(&mut conn, frames).map(|verdicts| Sent { verdicts, via }),
            Err(e) => {
                // the record may be stale: look it up again next time
                self.cache.lock().expect("cache").remove(&peer.sk);
                Err(e)
            }
        }
    }
}

impl Transport for P2pTransport {
    fn name(&self) -> &'static str {
        "p2p"
    }

    fn send(&self, peer: &PeerInfo, frames: &[Vec<u8>]) -> Result<Sent, TransportError> {
        self.client.send(peer, frames)
    }

    fn has_fresh_route(&self, sk: &UserId) -> bool {
        let known = self.client.shared.known.lock().expect("known").get(sk).copied();
        known.is_some_and(|p| self.client.shared.links.lock().expect("links").contains_key(&p))
    }

    fn advertised_addrs(&self) -> Vec<std::net::SocketAddr> {
        Vec::new()
    }

    fn boot(&self) -> Vec<Vec<u8>> {
        self.client.shared.boot.lock().expect("boot").clone()
    }

    fn stop(&self) {
        if self.client.shared.stopped.swap(true, Ordering::SeqCst) {
            return;
        }
        let _ = self.client.cmd.send(Cmd::Stop);
        // the door thread deletes its mappings and pinholes before it ends
        if let Some(h) = self.client.shared.door_thread.lock().expect("door").take() {
            let _ = h.join();
        }
        if let Some(rt) = self.rt.lock().expect("rt").take() {
            // not on a runtime thread: stop() is called by the node or the app
            if Handle::try_current().is_err() {
                rt.shutdown_timeout(Duration::from_secs(2));
            } else {
                rt.shutdown_background();
            }
        }
        self.client.shared.status.lock().expect("status").running = false;
        log::info!("p2p: stopped");
    }
}

impl Drop for P2pTransport {
    fn drop(&mut self) {
        self.stop();
    }
}

// ---- the swarm ----

fn build_swarm(key: identity::Keypair, cfg: &P2pConfig, hooks: Arc<dyn Hooks>, traffic: Arc<ledger::Traffic>) -> Result<Swarm<Behaviour>, Box<dyn std::error::Error + Send + Sync>> {
    let volunteer = cfg.volunteer;
    let hole_punch = cfg.hole_punch;
    let (client_tls, server_tls) = tls::configs()?;
    let allow: tls::Allow = Arc::new(move |ip| hooks.allows(ip));
    let swarm = libp2p::SwarmBuilder::with_existing_identity(key)
        .with_tokio()
        .with_other_transport(move |key| -> Result<_, Box<dyn std::error::Error + Send + Sync>> {
            // TCP, then TLS 1.3, then libp2p's own Noise and Yamux inside it (SPEC 15.3); the
            // node's scope is checked there too, both ways (SPEC 15.10)
            let tcp = tcp::tokio::Transport::new(tcp::Config::default().nodelay(true));
            Ok(tls::TlsTransport::new(tcp, client_tls, server_tls, allow, traffic)
                .upgrade(libp2p::core::upgrade::Version::V1Lazy)
                .authenticate(noise::Config::new(key)?)
                .multiplex(yamux::Config::default())
                .timeout(CONNECT_TIMEOUT))
        })?
        .with_relay_client(noise::Config::new, yamux::Config::default)?
        .with_behaviour(move |key, relay_client| {
            let id = key.public().to_peer_id();
            let mut kcfg = kad::Config::new(KAD_PROTOCOL);
            kcfg.set_query_timeout(Duration::from_secs(15));
            kcfg.set_record_ttl(Some(Duration::from_secs(presence::RECORD_TTL_S)));
            // we put our own record again on our schedule; nobody re-publishes for us
            kcfg.set_publication_interval(None);
            kcfg.set_replication_interval(None);
            // volunteers are reached through the ladder only (SPEC 15.9): Kademlia's own bootstrap
            // would dial every address of a volunteer at once, ahead of the ladder and outside its
            // order, timeouts and memory of this network (also why a volunteer goes into the
            // routing table only once the ladder has connected it, `set_volunteers`)
            kcfg.set_periodic_bootstrap_interval(None);
            kcfg.set_max_packet_size(16 * 1024);
            // a volunteer keeps only well-formed presence records (exactly 1000 bytes): nobody
            // can use it as free storage (SPEC 15.2)
            kcfg.set_record_filtering(kad::StoreInserts::FilterBoth);
            let rcfg = relay::Config { max_circuit_duration: Duration::from_secs(CIRCUIT_SECS), max_circuit_bytes: CIRCUIT_BYTES, ..Default::default() };
            // identify carries only external addresses: a volunteer's are those a peer on
            // another network really reached (SPEC 15.9), pushed as soon as they change
            let icfg = identify::Config::new(ID_PROTOCOL.into(), key.public()).with_agent_version(AGENT.into()).with_hide_listen_addrs(true).with_push_listen_addr_updates(true);
            Ok(Behaviour {
                relay_client,
                relay_server: Toggle::from(volunteer.then(|| relay::Behaviour::new(id, rcfg))),
                dcutr: Toggle::from(hole_punch.then(|| dcutr::Behaviour::new(id))),
                identify: identify::Behaviour::new(icfg),
                ping: ping::Behaviour::new(ping::Config::new().with_interval(Duration::from_secs(25))),
                kad: kad::Behaviour::with_config(id, kad::store::MemoryStore::with_config(id, store_cfg()), kcfg),
                stream: libp2p_stream::Behaviour::new(),
            })
        })?
        .with_swarm_config(|c| c.with_idle_connection_timeout(Duration::from_secs(90)))
        .build();
    Ok(swarm)
}

/// Where others reach a volunteer, for cards: what it says itself (its verified addresses,
/// public-looking only), IPv4 first, at most 2.
fn vol_public(v: &Vol) -> Vec<Multiaddr> {
    let mut p = v.public.clone();
    p.sort_by_key(|a| !matches!(ip_of(a), Some(IpAddr::V4(_))));
    p.truncate(2);
    p
}

/// Where contacts reach us through a volunteer (the circuits of the presence record): its
/// public addresses, else the one we reached it on (a volunteer on the same LAN).
fn vol_circuit_addrs(v: &Vol) -> Vec<Multiaddr> {
    let p = vol_public(v);
    if !p.is_empty() {
        return p;
    }
    v.reached_on.clone().or_else(|| v.addrs.first().cloned()).into_iter().collect()
}

/// The DHT store: a volunteer serves many users' records, about 1.1 KB each.
fn store_cfg() -> kad::store::MemoryStoreConfig {
    kad::store::MemoryStoreConfig { max_records: 20_000, max_value_bytes: presence::VALUE_LEN + 1, max_providers_per_key: 0, max_provided_keys: 0 }
}

struct Vol {
    addrs: Vec<Multiaddr>,
    /// The addresses the volunteer itself says others reach it on (identify), public-looking
    /// only: these, not the LAN address we may have dialed, go into cards and circuits.
    public: Vec<Multiaddr>,
    connected: bool,
    identified: bool,
    circuit: Option<ListenerId>,
    reserved: bool,
    failures: u32,
    /// When a reservation may be asked for again after one failed or ended.
    next_try: Instant,
    /// The address the current connection was made on, and its ladder class.
    reached_on: Option<Multiaddr>,
    class: Option<String>,
}

struct Lookup {
    remaining: usize,
    values: Vec<Vec<u8>>,
    reply: Option<oneshot::Sender<Vec<Vec<u8>>>>,
}

/// One rung being climbed.
struct Attempt {
    peer: PeerId,
    addr: Multiaddr,
    rung: Rung,
    started: Instant,
    retry: bool,
}

/// One round up the ladder.
struct Round {
    queue: VecDeque<(PeerId, Multiaddr, bool)>,
    next_at: Instant,
    tried: usize,
    total: usize,
    errors: Vec<&'static str>,
    reached: bool,
}

/// One of a volunteer's own addresses that others might reach it on.
struct Cand {
    addr: Multiaddr,
    /// `config`, `listen`, `observed`, or the door method that opened it.
    source: &'static str,
    /// The port it leads to here (a mapping), 0 = the same as in `addr`.
    internal: u16,
    /// When a peer on another network last really connected to it (0 = never).
    verified_ms: u64,
    asked: Option<Instant>,
    /// Helpers on other networks that could not connect, in a row.
    fails: u32,
}

struct Task {
    swarm: Swarm<Behaviour>,
    client: Arc<Client>,
    hooks: Arc<dyn Hooks>,
    cfg: P2pConfig,
    shared: Arc<Shared>,
    tx: mpsc::UnboundedSender<Cmd>,
    lookups: HashMap<u64, Lookup>,
    lookup_queries: HashMap<kad::QueryId, u64>,
    next_lookup: u64,
    dials: HashMap<PeerId, Vec<oneshot::Sender<Result<bool, TransportError>>>>,
    contact_dials: HashMap<ConnectionId, PeerId>,
    vols: HashMap<PeerId, Vol>,
    circuits: HashMap<ListenerId, Multiaddr>,
    listen_addrs: HashSet<Multiaddr>,
    publish_at: Option<Instant>,
    published_at: Option<Instant>,
    volunteers_at: Option<Instant>,
    announced_at: Option<Instant>,
    put_queries: HashSet<kad::QueryId>,
    // the ladder (SPEC 15.9)
    net: String,
    lstats: Vec<RouteStat>,
    stats_pending: Option<Instant>,
    round: Option<Round>,
    attempts: HashMap<ConnectionId, Attempt>,
    round_at: Instant,
    backoff: usize,
    last_round: (usize, usize, &'static str),
    /// Volunteer addresses the last round left out: outside the node's scope (SPEC 15.10).
    out_of_scope: usize,
    // a volunteer's own reachability
    cands: Vec<Cand>,
    my4: HashSet<Ipv4Addr>,
    reach_peers: HashSet<PeerId>,
    /// Remote IP of every open connection (None = relayed), to tell peers on other networks.
    conn_ips: HashMap<ConnectionId, (PeerId, Option<IpAddr>)>,
    asking: Option<Instant>,
    helper_at: HashMap<PeerId, Instant>,
    dialbacks: HashMap<ConnectionId, oneshot::Sender<bool>>,
    closing: Vec<(ConnectionId, Instant)>,
    door_results: Vec<door::MethodResult>,
    door_started: bool,
    // a volunteer's ledger (SPEC 15.12)
    kicked: HashMap<PeerId, Instant>,
    /// When the kept totals were last added to (None: never in this run).
    ledger_at: Option<Instant>,
    /// Until then the ladder dials from fresh local ports (just after a network change).
    fresh_ports_until: Instant,
}

impl Task {
    async fn run(mut self, mut rx: mpsc::UnboundedReceiver<Cmd>) {
        let mut tick = tokio::time::interval(TICK);
        loop {
            tokio::select! {
                cmd = rx.recv() => match cmd {
                    None | Some(Cmd::Stop) => break,
                    Some(c) => self.on_cmd(c),
                },
                ev = self.swarm.select_next_some() => self.on_event(ev),
                _ = tick.tick() => self.on_tick(),
            }
            if self.shared.stopped.load(Ordering::Relaxed) {
                break;
            }
        }
        for (_, waiters) in self.dials.drain() {
            for w in waiters {
                let _ = w.send(Err(TransportError::Stopped));
            }
        }
    }

    fn on_cmd(&mut self, cmd: Cmd) {
        match cmd {
            Cmd::Lookup { keys, reply } => {
                let id = self.next_lookup;
                self.next_lookup += 1;
                let mut values = Vec::new();
                let mut remaining = 0;
                for k in keys {
                    let key = kad::RecordKey::new(&k);
                    // our own store first (a volunteer holds the records it serves)
                    if let Some(r) = self.swarm.behaviour_mut().kad.store_mut().get(&key) {
                        values.push(r.value.clone());
                    }
                    let q = self.swarm.behaviour_mut().kad.get_record(key);
                    self.lookup_queries.insert(q, id);
                    remaining += 1;
                }
                self.lookups.insert(id, Lookup { remaining, values, reply: Some(reply) });
            }
            Cmd::Connect { peer, addrs, reply } => {
                if self.swarm.is_connected(&peer) {
                    let _ = reply.send(Ok(self.shared.relayed_only(&peer)));
                    return;
                }
                let first = !self.dials.contains_key(&peer);
                self.dials.entry(peer).or_default().push(reply);
                if first {
                    let opts = DialOpts::peer_id(peer).addresses(addrs).condition(PeerCondition::DisconnectedAndNotDialing).build();
                    let id = opts.connection_id();
                    match self.swarm.dial(opts) {
                        Ok(()) => {
                            self.contact_dials.insert(id, peer);
                        }
                        Err(DialError::DialPeerConditionFalse(_)) => {}
                        Err(e) => self.dial_failed(peer, &e),
                    }
                }
            }
            Cmd::Put(records) => {
                for (k, v) in records {
                    let mut r = kad::Record::new(kad::RecordKey::new(&k), v);
                    r.expires = Some(Instant::now() + Duration::from_secs(presence::RECORD_TTL_S));
                    if let Ok(q) = self.swarm.behaviour_mut().kad.put_record(r, kad::Quorum::One) {
                        self.put_queries.insert(q);
                    }
                }
            }
            Cmd::Volunteers(list) => self.set_volunteers(list),
            Cmd::NetworkChanged => {
                let peers: Vec<PeerId> = self.swarm.connected_peers().copied().collect();
                for p in peers {
                    let _ = self.swarm.disconnect_peer_id(p);
                }
                let now = Instant::now();
                for v in self.vols.values_mut() {
                    v.failures = 0;
                    v.next_try = now;
                }
                // a fresh climb with the new network's memory, as soon as it is loaded
                self.round = None;
                self.attempts.clear();
                self.backoff = 0;
                self.round_at = now;
                self.fresh_ports_until = now + Duration::from_secs(60);
                self.load_stats();
                self.publish_at = Some(now + Duration::from_secs(3));
                self.announced_at = None;
                if self.cfg.volunteer {
                    self.shared.door_reopen.store(true, Ordering::SeqCst);
                }
                log::info!("p2p: network changed");
            }
            Cmd::Relabel => self.load_stats(),
            Cmd::LadderStats(net, stats) => {
                self.net = net;
                self.lstats = stats;
                self.stats_pending = None;
            }
            Cmd::DialBack { peer, addr, reply } => {
                // a fresh connection to exactly that address, even though we are connected
                let opts = DialOpts::peer_id(peer).addresses(vec![addr]).condition(PeerCondition::Always).build();
                let id = opts.connection_id();
                match self.swarm.dial(opts) {
                    Ok(()) => {
                        self.dialbacks.insert(id, reply);
                    }
                    Err(_) => {
                        let _ = reply.send(false);
                    }
                }
            }
            Cmd::ReachDone { peer, status } => {
                self.asking = None;
                let foreign = self.peer_foreign(&peer);
                let what = match status {
                    Some(reach::CONNECTED) => "connected",
                    Some(reach::FAILED) => "could not connect",
                    Some(reach::BUSY) => "busy",
                    Some(_) => "refused",
                    None => "no answer",
                };
                log::info!("p2p: reach check: helper {} ({})", what, if foreign { "another network" } else { "this network or unknown" });
                if status == Some(reach::FAILED) && foreign {
                    let mut changed = false;
                    if let Some(c) = self.cands.iter_mut().filter(|c| c.asked.is_some()).max_by_key(|c| c.asked) {
                        c.fails += 1;
                        if c.verified_ms > 0 && c.fails >= 2 && c.source != "config" {
                            c.verified_ms = 0;
                            changed = true;
                            log::info!("p2p: no longer reachable from another network ({})", c.source);
                        }
                    }
                    if changed {
                        self.refresh_externals();
                    }
                }
            }
            Cmd::DoorOpened { opened, results } => {
                self.door_results = results;
                let new: Vec<Multiaddr> = opened.iter().map(|(_, ext, _)| sock_ma(ext)).collect();
                self.cands.retain(|c| !DOOR_SOURCES.contains(&c.source) || new.contains(&c.addr));
                for (method, ext, internal) in opened {
                    if let IpAddr::V4(v) = ext.ip() {
                        self.my4.insert(v);
                    }
                    self.add_cand(sock_ma(&ext), method, internal);
                }
                self.refresh_externals();
            }
            Cmd::DoorLost(lost) => {
                let lost: Vec<Multiaddr> = lost.iter().map(sock_ma).collect();
                self.cands.retain(|c| !(DOOR_SOURCES.contains(&c.source) && lost.contains(&c.addr)));
                self.refresh_externals();
            }
            Cmd::Kick { name, reply } => {
                let peer = self.shared.ledger.lock().expect("ledger").find(&name);
                if let Some(p) = peer {
                    self.kicked.insert(p, Instant::now() + KICK_FOR);
                    let _ = self.swarm.disconnect_peer_id(p);
                    log::info!("p2p: volunteer: the operator disconnected a peer");
                }
                let _ = reply.send(peer.is_some());
            }
            Cmd::RelayTotals(added, totals) => self.shared.ledger.lock().expect("ledger").kept(added, totals),
            Cmd::Stop => {}
        }
    }

    fn load_stats(&mut self) {
        self.stats_pending = Some(Instant::now());
        let hooks = self.hooks.clone();
        let tx = self.tx.clone();
        tokio::task::spawn_blocking(move || {
            let (net, stats) = hooks.ladder_stats();
            let _ = tx.send(Cmd::LadderStats(net, stats));
        });
    }

    fn set_volunteers(&mut self, list: Vec<Multiaddr>) {
        let me = *self.swarm.local_peer_id();
        for a in list {
            let a = with_tls(a);
            let Some(pid) = peer_of(&a) else { continue };
            if pid == me || (!self.cfg.allow_loopback && is_loopback(&a)) {
                continue;
            }
            let base = without_peer(&a);
            let v = self.vols.entry(pid).or_insert_with(|| Vol {
                addrs: Vec::new(),
                public: Vec::new(),
                connected: false,
                identified: false,
                circuit: None,
                reserved: false,
                failures: 0,
                next_try: Instant::now(),
                reached_on: None,
                class: None,
            });
            // not into Kademlia's routing table yet: a new entry there makes it bootstrap, which
            // dials every address at once; the ladder adds it once connected
            if !v.addrs.contains(&base) {
                v.addrs.push(base);
            }
        }
    }

    fn on_tick(&mut self) {
        let now = Instant::now();
        if self.volunteers_at.is_none_or(|t| t.elapsed() > VOLUNTEERS_REFRESH) {
            self.volunteers_at = Some(now);
            let hooks = self.hooks.clone();
            let tx = self.tx.clone();
            let boot = self.cfg.boot.clone();
            tokio::task::spawn_blocking(move || {
                let mut list = boot;
                list.extend(hooks.volunteers().into_iter().filter_map(|b| Multiaddr::try_from(b).ok()));
                let _ = tx.send(Cmd::Volunteers(list));
            });
        }
        // dial-back connections we opened for a peer's reach check: closed shortly after
        let due: Vec<ConnectionId> = self.closing.iter().filter(|(_, t)| *t <= now).map(|(id, _)| *id).collect();
        self.closing.retain(|(_, t)| *t > now);
        for id in due {
            self.swarm.close_connection(id);
        }
        // volunteers: climb the ladder, hold reservations
        self.ladder_step(now);
        let reserved = self.vols.values().filter(|v| v.reserved).count();
        let mut to_reserve = Vec::new();
        for (pid, v) in self.vols.iter() {
            if !self.cfg.volunteer && v.connected && v.identified && v.circuit.is_none() && v.next_try <= now && reserved + to_reserve.len() < MAX_RESERVATIONS {
                to_reserve.push(*pid);
            }
        }
        for pid in to_reserve {
            let addr = self.vols.get(&pid).and_then(|v| vol_circuit_addrs(v).into_iter().next());
            let Some(addr) = addr else { continue };
            let circuit = addr.with(Protocol::P2p(pid)).with(Protocol::P2pCircuit);
            match self.swarm.listen_on(circuit.clone()) {
                Ok(id) => {
                    self.circuits.insert(id, circuit);
                    if let Some(v) = self.vols.get_mut(&pid) {
                        v.circuit = Some(id);
                    }
                }
                Err(_) => self.volunteer_failed(pid),
            }
        }
        // a volunteer: ask a peer to check one of our addresses; drop stale verifications
        if self.cfg.volunteer {
            self.ask_reach(now);
            self.refresh_externals();
            // the ledger's kept totals (SPEC 15.12): read at once, added to every half minute
            if self.ledger_at.is_none_or(|t| t.elapsed() >= LEDGER_FLUSH) {
                self.ledger_at = Some(now);
                self.kicked.retain(|_, t| *t > now);
                let run = self.shared.traffic.rx.load(Ordering::Relaxed) + self.shared.traffic.tx.load(Ordering::Relaxed);
                let added = self.shared.ledger.lock().expect("ledger").unkept(run);
                let hooks = self.hooks.clone();
                let tx = self.tx.clone();
                tokio::task::spawn_blocking(move || {
                    let totals = hooks.relay_add(added.0, added.1);
                    let _ = tx.send(Cmd::RelayTotals(added, totals));
                });
            }
        }
        // presence
        let due = self.publish_at.is_some_and(|t| t <= now) || self.published_at.is_some_and(|t| t.elapsed() > self.cfg.republish);
        if due && (self.cfg.volunteer || self.vols.values().any(|v| v.connected)) {
            self.publish_at = None;
            self.published_at = Some(now);
            self.publish();
        }
        // "I am online": once we are reachable again
        let reachable = self.cfg.volunteer || self.vols.values().any(|v| v.reserved);
        if reachable && self.announced_at.is_none() {
            self.announced_at = Some(now);
            self.announce();
        }
        self.update_status();
    }

    // ---- the ladder (SPEC 15.9) ----

    fn want(&self) -> usize {
        self.vols.len().min(if self.cfg.volunteer { VOLUNTEER_PEERS } else { MAX_RESERVATIONS })
    }

    fn connected_vols(&self) -> usize {
        self.vols.values().filter(|v| v.connected).count()
    }

    /// One step: start a round when volunteers are missing, and start the next rung when the
    /// ones climbing have had their time (they keep going beside it) or failed.
    fn ladder_step(&mut self, now: Instant) {
        if self.round.is_none() {
            let stats_ready = self.stats_pending.is_none_or(|t| t.elapsed() > Duration::from_secs(3));
            if self.connected_vols() >= self.want() || now < self.round_at || !stats_ready {
                return;
            }
            self.start_round(now);
        }
        let Some(mut round) = self.round.take() else { return };
        if now >= round.next_at || self.attempts.is_empty() {
            while let Some((pid, addr, retry)) = round.queue.pop_front() {
                if self.connected_vols() >= self.want() {
                    round.queue.clear();
                    break;
                }
                if self.vols.get(&pid).is_none_or(|v| v.connected) {
                    continue;
                }
                round.tried += 1;
                round.next_at = now + self.cfg.rung_stagger;
                let mut b = DialOpts::peer_id(pid).addresses(vec![addr.clone()]).condition(PeerCondition::Always);
                if retry || now < self.fresh_ports_until {
                    // a fresh local port: the last connection from ours to this address may still be
                    // closing (TIME_WAIT), right after a network change or a failed try
                    b = b.allocate_new_port();
                }
                let opts = b.build();
                let id = opts.connection_id();
                match self.swarm.dial(opts) {
                    Ok(()) => {
                        self.attempts.insert(id, Attempt { peer: pid, rung: Rung::of(&addr), addr, started: now, retry });
                    }
                    Err(_) => {
                        round.errors.push("dial");
                        self.record_rung(&Rung::of(&addr), false, 0);
                        continue;
                    }
                }
                break;
            }
        }
        if round.queue.is_empty() && self.attempts.is_empty() {
            self.end_round(round, now);
        } else {
            self.round = Some(round);
        }
    }

    fn start_round(&mut self, now: Instant) {
        let mut cands: Vec<(PeerId, Multiaddr)> = Vec::new();
        let mut out_of_scope = 0;
        for (pid, v) in &self.vols {
            if v.connected {
                continue;
            }
            for a in v.addrs.iter().chain(v.public.iter()) {
                // a circuit is not a way to the volunteer itself
                if a.iter().any(|p| matches!(p, Protocol::P2pCircuit)) {
                    continue;
                }
                if !cands.iter().any(|(p, b)| p == pid && b == a) && (self.cfg.allow_loopback || !is_loopback(a)) {
                    // outside the node's scope (SPEC 15.10): not a rung at all
                    if ip_of(a).is_some_and(|ip| !self.hooks.allows(ip)) {
                        out_of_scope += 1;
                        continue;
                    }
                    cands.push((*pid, a.clone()));
                }
            }
        }
        self.out_of_scope = out_of_scope;
        if cands.is_empty() {
            self.round_at = now + Duration::from_secs(BACKOFF_S[0]);
            return;
        }
        let rungs: Vec<Rung> = cands.iter().map(|(_, a)| Rung::of(a)).collect();
        let order = ladder::order(&rungs, &self.lstats, self.net.starts_with("cell:"), now_ms());
        let queue: VecDeque<(PeerId, Multiaddr, bool)> = order.into_iter().map(|i| (cands[i].0, cands[i].1.clone(), false)).collect();
        log::info!("p2p: ladder: {} rungs to {} volunteers", queue.len(), self.vols.values().filter(|v| !v.connected).count());
        let total = queue.len();
        self.round = Some(Round { queue, next_at: now, tried: 0, total, errors: Vec::new(), reached: false });
    }

    fn end_round(&mut self, round: Round, now: Instant) {
        let common = most_common(&round.errors);
        self.last_round = (round.tried, round.total, common);
        if round.reached || self.connected_vols() > 0 {
            self.backoff = 0;
            self.round_at = now + Duration::from_secs(BACKOFF_S[0]);
        } else {
            let wait = BACKOFF_S[self.backoff.min(BACKOFF_S.len() - 1)];
            self.backoff += 1;
            self.round_at = now + Duration::from_secs(wait);
            log::info!("p2p: ladder: no volunteer reachable from this network ({} rungs, mostly {}); again in {} s", round.tried, common, wait);
        }
    }

    /// A rung's result, in memory now and on the device for this network.
    fn record_rung(&mut self, rung: &Rung, ok: bool, ms: u64) {
        let now = now_ms();
        for k in [&rung.addr_key, &rung.class] {
            match self.lstats.iter_mut().find(|s| &s.via == k) {
                Some(s) if ok => {
                    s.ok += 1;
                    s.streak = 0;
                    s.last_ok_ms = now;
                    s.last_try_ms = now;
                    s.ms = ms;
                }
                Some(s) => {
                    s.fail += 1;
                    s.streak += 1;
                    s.last_try_ms = now;
                }
                None => self.lstats.push(RouteStat { via: k.clone(), ok: ok as u32, fail: (!ok) as u32, streak: (!ok) as u32, last_ok_ms: if ok { now } else { 0 }, last_try_ms: now, ms }),
            }
        }
        let hooks = self.hooks.clone();
        let net = self.net.clone();
        let keys = vec![rung.addr_key.clone(), rung.class.clone()];
        tokio::task::spawn_blocking(move || hooks.ladder_result(&net, &keys, ok, ms));
    }

    fn volunteer_failed(&mut self, pid: PeerId) {
        if let Some(v) = self.vols.get_mut(&pid) {
            v.failures = v.failures.saturating_add(1);
            let secs = [2u64, 5, 15, 30, 60, 120][(v.failures as usize).min(6) - 1];
            v.next_try = Instant::now() + Duration::from_secs(secs);
        }
    }

    // ---- a volunteer's own door and reachability (SPEC 15.9) ----

    fn add_cand(&mut self, addr: Multiaddr, source: &'static str, internal: u16) {
        let addr = with_tls(addr);
        let lan_ok = self.cfg.allow_loopback && self.cfg.trust_lan_reach;
        if source != "config" && !(usable_direct(&addr, false) || (lan_ok && is_loopback(&addr))) {
            return;
        }
        if let Some(c) = self.cands.iter_mut().find(|c| c.addr == addr) {
            if DOOR_SOURCES.contains(&source) {
                c.source = source;
                c.internal = internal;
            }
            return;
        }
        let verified_ms = if source == "config" { now_ms() } else { 0 };
        self.cands.push(Cand { addr, source, internal, verified_ms, asked: None, fails: 0 });
        self.refresh_externals();
    }

    /// Is this IP on another network than ours? A LAN, loopback or carrier-NAT address is not,
    /// nor one of our own public addresses (a hairpin), nor an IPv6 in our own /64.
    fn foreign(&self, ip: IpAddr) -> bool {
        if self.cfg.trust_lan_reach {
            return true;
        }
        match ip {
            IpAddr::V4(v) => !(v.is_private() || v.is_loopback() || v.is_link_local() || v.is_unspecified() || (v.octets()[0] == 100 && (v.octets()[1] & 0xc0) == 64) || self.my4.contains(&v)),
            IpAddr::V6(v) => {
                let p64 = |a: &std::net::Ipv6Addr| a.segments()[..4].to_vec();
                door::global6(&v) && !self.listen_addrs.iter().any(|a| matches!(ip_of(a), Some(IpAddr::V6(m)) if p64(&m) == p64(&v)))
            }
        }
    }

    fn peer_foreign(&self, peer: &PeerId) -> bool {
        self.conn_ips.values().any(|(p, ip)| p == peer && ip.is_some_and(|ip| self.foreign(ip)))
    }

    /// An inbound connection from another network arrived on `local`: the candidate it went
    /// through is really reachable.
    fn confirm(&mut self, local: &Multiaddr) {
        let (Some(lip), Some(lport)) = (ip_of(local), port_of(local)) else { return };
        let mapped_here = matches!(lip, IpAddr::V4(v) if v.is_private());
        let now = now_ms();
        let mut changed = false;
        for c in self.cands.iter_mut() {
            let (Some(cip), Some(cport)) = (ip_of(&c.addr), port_of(&c.addr)) else { continue };
            let same = cip == lip && cport == lport;
            // through a mapping on the router: our LAN address and the mapped internal port
            let through = mapped_here && cip.is_ipv4() && c.source != "listen" && (c.internal == lport || (c.internal == 0 && cport == lport));
            if same || through {
                if c.verified_ms == 0 {
                    changed = true;
                    log::info!("p2p: reachable from another network ({}, {})", if cip.is_ipv4() { "ipv4" } else { "ipv6" }, c.source);
                }
                c.verified_ms = now;
                c.fails = 0;
            }
        }
        if changed {
            self.refresh_externals();
            self.publish_at.get_or_insert(Instant::now() + Duration::from_secs(1));
        }
    }

    /// The addresses libp2p hands out (identify, relay reservations): the operator's and the
    /// verified ones. With none of those, the LAN ones, because a relay reservation without any
    /// address of the relay is refused; cards never carry LAN addresses.
    fn refresh_externals(&mut self) {
        if !self.cfg.volunteer {
            return;
        }
        let now = now_ms();
        let mut want: Vec<Multiaddr> = self.cands.iter().filter(|c| c.source == "config" || (c.verified_ms > 0 && now.saturating_sub(c.verified_ms) < VERIFIED_FOR_MS)).map(|c| c.addr.clone()).collect();
        if want.is_empty() {
            want = self.listen_addrs.iter().filter(|a| volunteer_offers(a, self.cfg.allow_loopback) && !usable_direct(a, false)).cloned().collect();
        }
        let have: Vec<Multiaddr> = self.swarm.external_addresses().cloned().collect();
        for a in &have {
            if !want.contains(a) {
                self.swarm.remove_external_address(a);
            }
        }
        for a in want {
            if !have.contains(&a) {
                self.swarm.add_external_address(a);
            }
        }
    }

    /// Ask one connected peer to dial one of our candidates on a fresh connection. Its answer is
    /// a hint; the inbound connection itself is the proof (`confirm`).
    fn ask_reach(&mut self, now: Instant) {
        if self.asking.is_some_and(|t| now.duration_since(t) < Duration::from_secs(30)) {
            return;
        }
        let pick = self
            .cands
            .iter()
            .enumerate()
            .filter(|(_, c)| c.source != "config" && c.asked.is_none_or(|t| now.duration_since(t) > if c.verified_ms > 0 { RECHECK } else { ASK_GAP }))
            .min_by_key(|(_, c)| (c.verified_ms > 0, c.asked))
            .map(|(i, _)| i);
        let Some(i) = pick else { return };
        // a helper: one on another network first, then one behind a relay (somewhere else)
        let mut best: Option<(u8, PeerId)> = None;
        for p in self.reach_peers.iter().filter(|p| self.swarm.is_connected(p)) {
            if self.helper_at.get(p).is_some_and(|t| now.duration_since(*t) < HELPER_GAP) {
                continue;
            }
            let ips: Vec<Option<IpAddr>> = self.conn_ips.values().filter(|(q, _)| q == p).map(|(_, ip)| *ip).collect();
            let score = if ips.iter().any(|ip| ip.is_some_and(|ip| self.foreign(ip))) {
                0
            } else if ips.iter().any(|ip| ip.is_none()) {
                1
            } else {
                continue;
            };
            if best.is_none_or(|(s, _)| score < s) {
                best = Some((score, *p));
            }
        }
        let Some((_, peer)) = best else { return };
        self.asking = Some(now);
        self.helper_at.insert(peer, now);
        self.cands[i].asked = Some(now);
        let addr = self.cands[i].addr.clone();
        let mut control = self.client.control.clone();
        let tx = self.tx.clone();
        tokio::spawn(async move {
            let status = tokio::time::timeout(Duration::from_secs(25), async {
                let mut s = control.open_stream(peer, REACH_PROTOCOL).await.ok()?;
                s.write_all(&reach::request(&addr)).await.ok()?;
                s.flush().await.ok()?;
                let mut b = [0u8; 1];
                s.read_exact(&mut b).await.ok()?;
                Some(b[0])
            })
            .await
            .ok()
            .flatten();
            let _ = tx.send(Cmd::ReachDone { peer, status });
        });
    }

    fn start_door(&mut self, port: u16) {
        if self.door_started || !self.cfg.volunteer || !self.cfg.upnp || port == 0 {
            return;
        }
        self.door_started = true;
        let tx = self.tx.clone();
        let shared = self.shared.clone();
        let h = std::thread::Builder::new().name("mlmchat-door".into()).spawn(move || door_thread(port, tx, shared));
        if let Ok(h) = h {
            *self.shared.door_thread.lock().expect("door") = Some(h);
        }
    }

    // ---- presence ----

    /// Addresses for the presence record: circuits first (they work from anywhere), then what can
    /// be dialed directly.
    fn presence_addrs(&self) -> Vec<Vec<u8>> {
        let me = *self.swarm.local_peer_id();
        let mut out: Vec<Multiaddr> = Vec::new();
        // a circuit through each reserved volunteer, on each address it is reached on
        for (pid, v) in &self.vols {
            if v.reserved && v.circuit.is_some_and(|id| self.circuits.contains_key(&id)) {
                for a in vol_circuit_addrs(v) {
                    out.push(a.with(Protocol::P2p(*pid)).with(Protocol::P2pCircuit));
                }
            }
        }
        for a in self.swarm.external_addresses() {
            if usable_direct(a, self.cfg.allow_loopback) {
                out.push(a.clone());
            }
        }
        if self.cfg.publish_direct {
            for a in &self.listen_addrs {
                if usable_direct(a, self.cfg.allow_loopback) {
                    out.push(a.clone());
                }
            }
        }
        let mut seen = HashSet::new();
        out.into_iter().map(|a| strip_peer(a, &me)).filter(|a| seen.insert(a.clone())).map(|a| a.to_vec()).collect()
    }

    fn publish(&mut self) {
        let addrs = self.presence_addrs();
        if addrs.is_empty() {
            return;
        }
        let peer_id = self.swarm.local_peer_id().to_bytes();
        let hooks = self.hooks.clone();
        let tx = self.tx.clone();
        tokio::task::spawn_blocking(move || {
            let (_, loc) = hooks.me();
            let now = now_ms();
            let sign = |m: &[u8]| hooks.sign(m);
            let records: Vec<([u8; 32], Vec<u8>)> = presence::publish_days(now / 1000).into_iter().filter_map(|d| presence::seal(&loc, d, peer_id.clone(), addrs.clone(), now, &sign, random()).ok()).collect();
            let _ = tx.send(Cmd::Put(records));
        });
    }

    /// Open an empty session with recent contacts: each one learns we are back and retries what
    /// it has queued for us at once (SPEC 15.4).
    fn announce(&mut self) {
        let client = self.client.clone();
        std::thread::Builder::new()
            .name("mlmchat-announce".into())
            .spawn(move || {
                // the contact answers on the connection this opens (it knows our peer id from
                // the session), so our presence record need not have landed yet
                let mut reached = 0;
                for (sk, loc) in client.hooks.announce_targets(ANNOUNCE_MAX) {
                    if client.stopped() {
                        return;
                    }
                    match client.send(&PeerInfo::bare(sk, loc, Vec::new()), &[]) {
                        Ok(_) => reached += 1,
                        Err(e) => log::debug!("p2p: announce not delivered ({})", e.code()),
                    }
                }
                log::info!("p2p: announced to {reached} contacts");
            })
            .ok();
    }

    fn update_status(&mut self) {
        let now = Instant::now();
        let connected = self.connected_vols();
        let rung = self.vols.values().filter(|v| v.connected).find_map(|v| v.class.clone()).map(|c| c.trim_start_matches("c:").to_string()).unwrap_or_default();
        let ladder = if self.vols.is_empty() {
            "idle"
        } else if connected > 0 {
            "reachable"
        } else if self.round.is_some() || self.stats_pending.is_some() {
            "probing"
        } else {
            "unreachable"
        };
        let (tried, total) = match &self.round {
            Some(r) => (r.tried, r.total),
            None => (self.last_round.0, self.last_round.1),
        };
        let vnow = now_ms();
        let verified: Vec<String> = self.cands.iter().filter(|c| c.source == "config" || (c.verified_ms > 0 && vnow.saturating_sub(c.verified_ms) < VERIFIED_FOR_MS)).map(|c| c.addr.to_string()).collect();
        {
            let mut st = self.shared.status.lock().expect("status");
            st.volunteers_known = self.vols.len();
            st.volunteers_connected = connected;
            st.reservations = self.vols.values().filter(|v| v.reserved).count();
            st.external_addrs = self.swarm.external_addresses().filter(|a| usable_direct(a, self.cfg.allow_loopback)).count();
            st.ladder = ladder;
            st.rung = rung;
            st.tried = tried;
            st.total = total;
            st.last_error = self.last_round.2;
            st.out_of_scope = self.out_of_scope;
            st.next_try_s = if ladder == "unreachable" { self.round_at.saturating_duration_since(now).as_secs() } else { 0 };
            st.door = self.door_results.clone();
            st.candidates = self.cands.len();
            st.verified = if self.cfg.volunteer { verified.clone() } else { Vec::new() };
        }
        let me = *self.swarm.local_peer_id();
        let mut boot: Vec<Vec<u8>> = Vec::new();
        if self.cfg.volunteer {
            // our own card: the verified addresses; with none yet, the best guesses, so the first
            // contact on another network can confirm one; others' cards carry only verified ones
            let mut mine: Vec<Multiaddr> = verified.iter().filter_map(|a| a.parse().ok()).filter(|a| usable_direct(a, self.cfg.allow_loopback)).collect();
            if mine.is_empty() {
                let mut guesses: Vec<&Cand> = self.cands.iter().collect();
                guesses.sort_by_key(|c| (!DOOR_SOURCES.contains(&c.source), !matches!(ip_of(&c.addr), Some(IpAddr::V4(_)))));
                mine = guesses.into_iter().map(|c| c.addr.clone()).collect();
            }
            if mine.is_empty() {
                mine = self.swarm.external_addresses().cloned().collect();
            }
            mine.sort_by_key(|a| !matches!(ip_of(a), Some(IpAddr::V4(_))));
            let mut shown: Vec<String> = Vec::new();
            for a in mine.into_iter().take(2) {
                let full = strip_peer(a, &me).with(Protocol::P2p(me));
                shown.push(full.to_string());
                boot.push(full.to_vec());
            }
            for a in self.listen_addrs.iter().filter(|a| matches!(ip_of(a), Some(IpAddr::V4(v)) if v.is_private())) {
                shown.push(strip_peer(a.clone(), &me).with(Protocol::P2p(me)).to_string());
            }
            self.shared.status.lock().expect("status").volunteer_addrs = shown;
        }
        for (pid, v) in &self.vols {
            if v.connected {
                for a in vol_public(v) {
                    boot.push(a.with(Protocol::P2p(*pid)).to_vec());
                }
            }
        }
        boot.truncate(MAX_BOOT);
        *self.shared.boot.lock().expect("boot") = boot;
    }

    fn dial_failed(&mut self, peer: PeerId, e: &DialError) {
        let err = match e {
            DialError::NoAddresses => TransportError::NoRoute,
            DialError::WrongPeerId { .. } => TransportError::WrongPeer,
            DialError::Denied { .. } | DialError::LocalPeerId { .. } => TransportError::Refused,
            DialError::Transport(errs) if errs.iter().any(|(_, t)| matches!(t, libp2p::TransportError::Other(e) if e.kind() == std::io::ErrorKind::TimedOut)) => TransportError::Timeout,
            _ => TransportError::Connect,
        };
        // kinds only: a DialError's text carries addresses
        log::debug!("p2p: dial failed ({})", match e {
            DialError::NoAddresses => "no addresses",
            DialError::WrongPeerId { .. } => "wrong peer",
            DialError::Denied { .. } => "denied",
            DialError::Aborted => "aborted",
            DialError::Transport(_) => "transport",
            _ => "other",
        });
        if let DialError::Transport(errs) = e {
            for (a, t) in errs {
                let kind = if a.iter().any(|p| matches!(p, Protocol::P2pCircuit)) { "relay" } else { "direct" };
                log::debug!("p2p:   {kind}: {}", scrub(&format!("{t:?}")));
            }
        }
        if let Some(waiters) = self.dials.remove(&peer) {
            for w in waiters {
                let _ = w.send(Err(err.clone()));
            }
        }
    }

    fn on_event(&mut self, ev: SwarmEvent<BehaviourEvent>) {
        match ev {
            SwarmEvent::NewListenAddr { listener_id, address } => {
                if self.circuits.contains_key(&listener_id) {
                    self.publish_at.get_or_insert(Instant::now() + Duration::from_secs(1));
                } else {
                    if self.cfg.volunteer {
                        let port = port_of(&address).unwrap_or(0);
                        if usable_direct(&address, false) || (self.cfg.trust_lan_reach && self.cfg.allow_loopback && is_loopback(&address)) {
                            self.add_cand(address.clone(), "listen", 0);
                        }
                        self.start_door(if self.cfg.port != 0 { self.cfg.port } else { port });
                    }
                    self.listen_addrs.insert(address);
                    self.refresh_externals();
                }
            }
            SwarmEvent::ExpiredListenAddr { address, .. } => {
                self.listen_addrs.remove(&address);
                self.cands.retain(|c| !(c.source == "listen" && c.addr == address));
                self.refresh_externals();
            }
            SwarmEvent::NewExternalAddrCandidate { address } => {
                // how a peer sees us (identify): a public IPv4 is worth a reach check
                if self.cfg.volunteer && usable_direct(&address, false) {
                    if let Some(IpAddr::V4(v)) = ip_of(&address) {
                        self.my4.insert(v);
                        let port = if self.cfg.port != 0 { self.cfg.port } else { port_of(&address).unwrap_or(0) };
                        self.add_cand(sock_ma(&SocketAddr::new(IpAddr::V4(v), port)), "observed", port);
                    }
                }
            }
            SwarmEvent::ListenerClosed { listener_id, .. } => {
                if self.circuits.remove(&listener_id).is_some() {
                    let pid = self.vols.iter().find(|(_, v)| v.circuit == Some(listener_id)).map(|(p, _)| *p);
                    if let Some(pid) = pid {
                        if let Some(v) = self.vols.get_mut(&pid) {
                            v.circuit = None;
                            v.reserved = false;
                        }
                        self.volunteer_failed(pid);
                        log::info!("p2p: a reservation ended");
                    }
                    self.publish_at.get_or_insert(Instant::now() + Duration::from_secs(1));
                }
            }
            SwarmEvent::ConnectionEstablished { peer_id, connection_id, endpoint, .. } => {
                {
                    let mut links = self.shared.links.lock().expect("links");
                    let e = links.entry(peer_id).or_default();
                    if endpoint.is_relayed() {
                        e.1 += 1;
                    } else {
                        e.0 += 1;
                    }
                }
                let remote = match &endpoint {
                    _ if endpoint.is_relayed() => None,
                    ConnectedPoint::Dialer { address, .. } => ip_of(address),
                    ConnectedPoint::Listener { send_back_addr, .. } => ip_of(send_back_addr),
                };
                self.conn_ips.insert(connection_id, (peer_id, remote));
                // a volunteer's ledger (SPEC 15.12): who came in, and their connection's bytes
                if let ConnectedPoint::Listener { send_back_addr, .. } = &endpoint {
                    let bytes = self.shared.traffic.take(&send_back_addr.to_string());
                    if self.cfg.volunteer && !endpoint.is_relayed() {
                        if self.kicked.get(&peer_id).is_some_and(|t| *t > Instant::now()) {
                            self.swarm.close_connection(connection_id);
                        } else {
                            self.shared.ledger.lock().expect("ledger").connected(peer_id, connection_id, bytes, now_ms());
                        }
                    }
                }
                // a volunteer's reach proof: someone on another network came in on that address
                if self.cfg.volunteer {
                    if let (ConnectedPoint::Listener { local_addr, .. }, Some(rip)) = (&endpoint, remote) {
                        if self.foreign(rip) {
                            let local = local_addr.clone();
                            self.confirm(&local);
                        }
                    }
                }
                if let Some(reply) = self.dialbacks.remove(&connection_id) {
                    let _ = reply.send(true);
                    self.closing.push((connection_id, Instant::now() + DIALBACK_CLOSE));
                }
                self.contact_dials.remove(&connection_id);
                let attempt = self.attempts.remove(&connection_id);
                if let Some(a) = &attempt {
                    let ms = a.started.elapsed().as_millis() as u64;
                    let rung = a.rung.clone();
                    self.record_rung(&rung, true, ms);
                    log::info!("p2p: ladder: reached a volunteer on {} in {} ms{}", rung.class.trim_start_matches("c:"), ms, if a.retry { " (retry)" } else { "" });
                    if let Some(r) = self.round.as_mut() {
                        r.reached = true;
                    }
                }
                let mut kad_addr = None;
                if let Some(v) = self.vols.get_mut(&peer_id) {
                    if !v.connected {
                        log::info!("p2p: volunteer connected");
                        let addr = match (&attempt, &endpoint) {
                            (Some(a), _) => Some(a.addr.clone()),
                            (None, ConnectedPoint::Dialer { address, .. }) if !endpoint.is_relayed() => Some(without_peer(address)),
                            _ => None,
                        };
                        v.class = addr.as_ref().map(ladder::class_of);
                        kad_addr = addr.clone();
                        v.reached_on = addr;
                    }
                    v.connected = true;
                    v.failures = 0;
                }
                // a volunteer joins the routing table once really connected (`set_volunteers`)
                if let Some(a) = kad_addr {
                    self.swarm.behaviour_mut().kad.add_address(&peer_id, a);
                }
                if let Some(waiters) = self.dials.remove(&peer_id) {
                    let relayed = self.shared.relayed_only(&peer_id);
                    for w in waiters {
                        let _ = w.send(Ok(relayed));
                    }
                }
            }
            SwarmEvent::ConnectionClosed { peer_id, connection_id, endpoint, num_established, .. } => {
                {
                    let mut links = self.shared.links.lock().expect("links");
                    if let Some(e) = links.get_mut(&peer_id) {
                        if endpoint.is_relayed() {
                            e.1 = e.1.saturating_sub(1);
                        } else {
                            e.0 = e.0.saturating_sub(1);
                        }
                    }
                    if num_established == 0 {
                        links.remove(&peer_id);
                    }
                }
                self.conn_ips.remove(&connection_id);
                self.shared.ledger.lock().expect("ledger").closed(&peer_id, connection_id, num_established == 0);
                if num_established == 0 {
                    self.shared.punched.lock().expect("punched").remove(&peer_id);
                    if let Some(v) = self.vols.get_mut(&peer_id) {
                        v.connected = false;
                        v.identified = false;
                        v.reached_on = None;
                        v.class = None;
                        log::info!("p2p: volunteer disconnected");
                    }
                    if self.vols.contains_key(&peer_id) {
                        self.volunteer_failed(peer_id);
                    }
                }
            }
            SwarmEvent::OutgoingConnectionError { peer_id, connection_id, error } => {
                if let Some(reply) = self.dialbacks.remove(&connection_id) {
                    let _ = reply.send(false);
                }
                if let Some(a) = self.attempts.remove(&connection_id) {
                    let kind = error_kind(&error);
                    if kind != "aborted" {
                        self.record_rung(&a.rung, false, 0);
                    }
                    log::info!("p2p: ladder: {} failed ({kind}) after {} ms", a.rung.class.trim_start_matches("c:"), a.started.elapsed().as_millis());
                    if let Some(r) = self.round.as_mut() {
                        r.errors.push(kind);
                        // throttling is per connection here: a timed-out rung gets one quick
                        // retry on a fresh connection, and so does one whose local port was
                        // still in use (a reconnect right after a network change)
                        if (kind == "timeout" || kind == "in_use") && !a.retry {
                            r.queue.push_back((a.peer, a.addr, true));
                            r.total += 1;
                        }
                    }
                }
                if let Some(peer) = self.contact_dials.remove(&connection_id).or(peer_id.filter(|p| self.dials.contains_key(p))) {
                    if !self.swarm.is_connected(&peer) {
                        self.dial_failed(peer, &error);
                    }
                }
            }
            SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received { peer_id, info, .. })) => {
                if info.protocols.iter().any(|p| p.as_ref() == REACH_PROTOCOL.as_ref()) {
                    self.reach_peers.insert(peer_id);
                }
                let hop = info.protocols.iter().any(|p| p.as_ref() == HOP_PROTOCOL);
                let allow_loopback = self.cfg.allow_loopback;
                if let Some(v) = self.vols.get_mut(&peer_id) {
                    v.identified = hop;
                    let public: Vec<Multiaddr> = info.listen_addrs.iter().map(without_peer).filter(|a| a.iter().any(|p| matches!(p, Protocol::Tls)) && usable_direct(a, allow_loopback)).collect();
                    if public != v.public {
                        for a in &public {
                            self.swarm.behaviour_mut().kad.add_address(&peer_id, a.clone());
                        }
                        v.public = public;
                    }
                    if hop {
                        // kept on the device: the next start dials every address it ever announced
                        let known: Vec<Vec<u8>> = v.addrs.iter().chain(v.public.iter()).map(|a| a.clone().with(Protocol::P2p(peer_id)).to_vec()).collect();
                        let hooks = self.hooks.clone();
                        tokio::task::spawn_blocking(move || hooks.remember_volunteers(known));
                    }
                }
            }
            SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Pushed { peer_id, info, .. })) => {
                let allow_loopback = self.cfg.allow_loopback;
                if let Some(v) = self.vols.get_mut(&peer_id) {
                    v.public = info.listen_addrs.iter().map(without_peer).filter(|a| a.iter().any(|p| matches!(p, Protocol::Tls)) && usable_direct(a, allow_loopback)).collect();
                }
            }
            SwarmEvent::Behaviour(BehaviourEvent::Dcutr(dcutr::Event { remote_peer_id, result })) => {
                if result.is_ok() {
                    self.shared.punched.lock().expect("punched").insert(remote_peer_id);
                    log::info!("p2p: hole punch succeeded");
                } else {
                    log::debug!("p2p: hole punch failed");
                }
            }
            SwarmEvent::Behaviour(BehaviourEvent::RelayClient(relay::client::Event::ReservationReqAccepted { relay_peer_id, renewal, .. })) => {
                if let Some(v) = self.vols.get_mut(&relay_peer_id) {
                    v.reserved = true;
                }
                if !renewal {
                    log::info!("p2p: relay reservation accepted");
                    self.publish_at = Some(Instant::now() + Duration::from_millis(300));
                }
            }
            SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::InboundRequest { request: kad::InboundRequest::PutRecord { record: Some(r), .. } })) => {
                // only a record that can be a presence record; its content is unreadable here
                if r.value.len() == presence::VALUE_LEN && r.key.as_ref().len() == 32 && r.publisher.is_some() {
                    let mut r = r;
                    let ttl = Instant::now() + Duration::from_secs(presence::RECORD_TTL_S);
                    r.expires = Some(r.expires.map_or(ttl, |e| e.min(ttl)));
                    let _ = self.swarm.behaviour_mut().kad.store_mut().put(r);
                }
            }
            SwarmEvent::Behaviour(BehaviourEvent::Kad(kad::Event::OutboundQueryProgressed { id, result, step, .. })) => match result {
                kad::QueryResult::GetRecord(r) => {
                    let Some(&lid) = self.lookup_queries.get(&id) else { return };
                    let mut done = step.last;
                    match r {
                        Ok(kad::GetRecordOk::FoundRecord(rec)) => {
                            if let Some(l) = self.lookups.get_mut(&lid) {
                                l.values.push(rec.record.value);
                            }
                        }
                        Ok(kad::GetRecordOk::FinishedWithNoAdditionalRecord { .. }) | Err(_) => done = true,
                    }
                    if done {
                        self.lookup_queries.remove(&id);
                        let finished = self.lookups.get_mut(&lid).map(|l| {
                            l.remaining = l.remaining.saturating_sub(1);
                            l.remaining == 0
                        });
                        if finished == Some(true) {
                            if let Some(mut l) = self.lookups.remove(&lid) {
                                if let Some(reply) = l.reply.take() {
                                    let _ = reply.send(l.values);
                                }
                            }
                        }
                    }
                }
                kad::QueryResult::PutRecord(r) => {
                    if self.put_queries.remove(&id) {
                        match r {
                            Ok(_) => {
                                self.shared.status.lock().expect("status").published_ms = now_ms();
                                log::info!("p2p: presence published");
                            }
                            Err(_) => {
                                log::info!("p2p: presence not stored by any volunteer yet");
                                self.publish_at.get_or_insert(Instant::now() + Duration::from_secs(15));
                            }
                        }
                    }
                }
                _ => {}
            },
            // the volunteer's own side: counts and kinds only
            SwarmEvent::Behaviour(BehaviourEvent::RelayServer(e)) => {
                {
                    let mut l = self.shared.ledger.lock().expect("ledger");
                    match &e {
                        relay::Event::ReservationReqAccepted { src_peer_id, .. } => l.reserved(src_peer_id, true),
                        relay::Event::ReservationTimedOut { src_peer_id } => l.reserved(src_peer_id, false),
                        relay::Event::CircuitReqAccepted { src_peer_id, dst_peer_id } => l.circuit(src_peer_id, dst_peer_id, true),
                        relay::Event::CircuitClosed { src_peer_id, dst_peer_id, .. } => l.circuit(src_peer_id, dst_peer_id, false),
                        _ => {}
                    }
                }
                let what = match e {
                    relay::Event::ReservationReqAccepted { renewed: false, .. } => "reservation accepted",
                    relay::Event::ReservationReqAccepted { renewed: true, .. } => "reservation renewed",
                    relay::Event::ReservationReqDenied { .. } => "reservation denied",
                    relay::Event::ReservationTimedOut { .. } => "reservation timed out",
                    relay::Event::CircuitReqDenied { .. } => "circuit denied",
                    relay::Event::CircuitReqAccepted { .. } => "circuit opened",
                    relay::Event::CircuitClosed { .. } => "circuit closed",
                    _ => "other",
                };
                log::debug!("p2p: volunteer: {what}");
            }
            _ => {}
        }
    }
}

/// The door thread of a volunteer: open, renew at half lease, open again after a network change,
/// and delete everything on stop (P2pTransport::stop joins it).
fn door_thread(port: u16, tx: mpsc::UnboundedSender<Cmd>, shared: Arc<Shared>) {
    let opened = |d: &door::Door| Cmd::DoorOpened { opened: d.openings.iter().map(|o| (o.method, o.external, o.internal_port)).collect(), results: d.results.clone() };
    let mut d = door::Door::open(port, &door::gateways());
    let _ = tx.send(opened(&d));
    let mut renew_at = Instant::now() + Duration::from_secs(60);
    while !shared.stopped.load(Ordering::Relaxed) {
        std::thread::sleep(Duration::from_millis(500));
        if shared.door_reopen.swap(false, Ordering::SeqCst) {
            d.close();
            d = door::Door::open(port, &door::gateways());
            let _ = tx.send(opened(&d));
        }
        if Instant::now() >= renew_at {
            renew_at = Instant::now() + Duration::from_secs(60);
            let lost = d.renew();
            if !lost.is_empty() {
                let _ = tx.send(Cmd::DoorLost(lost.iter().map(|o| o.external).collect()));
            }
        }
    }
    d.close();
}

fn most_common(errors: &[&'static str]) -> &'static str {
    let mut best = ("", 0);
    for e in errors {
        let n = errors.iter().filter(|x| *x == e).count();
        if n > best.1 {
            best = (e, n);
        }
    }
    best.0
}

/// A dial error as one word for the ladder: `timeout`, `refused`, `reset`, `unreachable`,
/// `handshake`, `wrong_peer`, `aborted` or `other`.
fn error_kind(e: &DialError) -> &'static str {
    match e {
        DialError::Aborted => "aborted",
        DialError::WrongPeerId { .. } => "wrong_peer",
        DialError::Transport(errs) => {
            let mut kind = "other";
            for (_, t) in errs {
                let k = match t {
                    libp2p::TransportError::Other(io) => match io.kind() {
                        std::io::ErrorKind::TimedOut => "timeout",
                        std::io::ErrorKind::ConnectionRefused => "refused",
                        std::io::ErrorKind::AddrInUse => "in_use",
                        std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::ConnectionAborted | std::io::ErrorKind::UnexpectedEof => "reset",
                        std::io::ErrorKind::HostUnreachable | std::io::ErrorKind::NetworkUnreachable | std::io::ErrorKind::AddrNotAvailable => "unreachable",
                        _ => {
                            let text = format!("{io:?}");
                            if text.contains("Timeout") || text.contains("timed out") || text.contains("TimedOut") {
                                "timeout"
                            } else if text.contains("refused") || text.contains("ConnectionRefused") {
                                "refused"
                            } else if text.contains("nreachable") {
                                "unreachable"
                            } else if text.contains("AddrInUse") {
                                "in_use"
                            } else if text.contains("Tls") || text.contains("Noise") || text.contains("andshake") {
                                "handshake"
                            } else {
                                "other"
                            }
                        }
                    },
                    _ => "other",
                };
                if kind == "other" {
                    kind = k;
                }
            }
            kind
        }
        _ => "other",
    }
}

/// Every inbound `/mlmchat/msg/1` stream gets its own thread running the responder session.
async fn serve_inbound(mut incoming: libp2p_stream::IncomingStreams, hooks: Arc<dyn Hooks>, shared: Arc<Shared>, handle: Handle, io_timeout: Duration) {
    while let Some((peer, stream)) = incoming.next().await {
        if shared.stopped.load(Ordering::Relaxed) {
            break;
        }
        if shared.inbound.fetch_add(1, Ordering::SeqCst) >= MAX_INBOUND {
            shared.inbound.fetch_sub(1, Ordering::SeqCst);
            continue;
        }
        let hooks = hooks.clone();
        let shared2 = shared.clone();
        let handle = handle.clone();
        let spawned = std::thread::Builder::new().name("mlmchat-p2p-in".into()).spawn(move || {
            let via = shared2.via(&peer);
            let pipe = BlockingStream::new(stream, handle, io_timeout, shared2.stopped.clone());
            let stopped = shared2.stopped.clone();
            let r = session::respond(Box::new(pipe), &*hooks, &|| stopped.load(Ordering::Relaxed), &mut |sk| {
                shared2.known.lock().expect("known").insert(sk, peer);
                hooks.on_reached(sk, via);
            });
            shared2.inbound.fetch_sub(1, Ordering::SeqCst);
            if let Err(e) = r {
                log::debug!("p2p: inbound session closed ({})", e.code());
            }
        });
        if spawned.is_err() {
            shared.inbound.fetch_sub(1, Ordering::SeqCst);
        }
    }
}

/// Every node helps volunteers check their reachability (`reach.rs`): it dials the address a
/// peer names on a fresh connection, a few times a minute at most, never into a LAN.
async fn serve_reach(mut incoming: libp2p_stream::IncomingStreams, tx: mpsc::UnboundedSender<Cmd>, shared: Arc<Shared>, allow_loopback: bool) {
    let mut last: HashMap<PeerId, Instant> = HashMap::new();
    let mut window: VecDeque<Instant> = VecDeque::new();
    while let Some((peer, mut stream)) = incoming.next().await {
        if shared.stopped.load(Ordering::Relaxed) {
            break;
        }
        let now = Instant::now();
        while window.front().is_some_and(|t| now.duration_since(*t) > Duration::from_secs(60)) {
            window.pop_front();
        }
        last.retain(|_, t| now.duration_since(*t) < Duration::from_secs(600));
        let busy = last.get(&peer).is_some_and(|t| now.duration_since(*t) < REACH_PEER_GAP) || window.len() >= REACH_PER_MIN;
        if !busy {
            last.insert(peer, now);
            window.push_back(now);
        }
        let tx = tx.clone();
        tokio::spawn(async move {
            let r = tokio::time::timeout(Duration::from_secs(20), async {
                let mut head = [0u8; 2];
                stream.read_exact(&mut head).await.ok()?;
                if head[0] != reach::VERSION || head[1] as usize > reach::MAX_ADDR {
                    let _ = stream.write_all(&[reach::REFUSED]).await;
                    return Some(reach::REFUSED);
                }
                let mut body = vec![0u8; head[1] as usize];
                stream.read_exact(&mut body).await.ok()?;
                let status = match reach::parse(head[1], &body) {
                    _ if busy => reach::BUSY,
                    Some(a) if reach::dialable(&a, allow_loopback) => {
                        let (rtx, rrx) = oneshot::channel();
                        if tx.send(Cmd::DialBack { peer, addr: a, reply: rtx }).is_err() {
                            return None;
                        }
                        match tokio::time::timeout(Duration::from_secs(15), rrx).await {
                            Ok(Ok(true)) => reach::CONNECTED,
                            _ => reach::FAILED,
                        }
                    }
                    _ => reach::REFUSED,
                };
                stream.write_all(&[status]).await.ok()?;
                let _ = stream.close().await;
                Some(status)
            })
            .await;
            log::debug!("p2p: reach: helped a peer ({})", match r {
                Ok(Some(reach::CONNECTED)) => "connected",
                Ok(Some(reach::FAILED)) => "could not connect",
                Ok(Some(reach::BUSY)) => "busy",
                Ok(Some(_)) => "refused",
                _ => "no request",
            });
        });
    }
}

// ---- addresses ----

/// Error texts can carry addresses: anything that looks like an IPv4 or IPv6 address, or a peer
/// id, becomes `<x>` before it reaches a log line.
pub fn scrub(s: &str) -> String {
    s.split(|c: char| c.is_whitespace())
        .map(|w| {
            let digits = w.chars().filter(|c| c.is_ascii_digit()).count();
            let colons = w.matches(':').count();
            let dots = w.matches('.').count();
            if colons >= 2 || (dots >= 3 && digits >= 4) || w.starts_with("12D3") || w.starts_with("Qm") || w.contains("/ip4/") || w.contains("/ip6/") || w.contains("/p2p/") {
                "<x>".to_string()
            } else {
                w.to_string()
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn io_err(msg: &str) -> std::io::Error {
    std::io::Error::other(msg.to_string())
}

/// Every TCP address here is TLS (SPEC 15.3): add `/tls` where a caller left it out.
fn with_tls(a: Multiaddr) -> Multiaddr {
    let tcp = a.iter().any(|p| matches!(p, Protocol::Tcp(_)));
    let has = a.iter().any(|p| matches!(p, Protocol::Tls));
    let circuit = a.iter().any(|p| matches!(p, Protocol::P2pCircuit));
    if tcp && !has && !circuit {
        tls::add_tls(a)
    } else {
        a
    }
}

/// `/ip4|ip6/<a>/tcp/<p>/tls` for a socket address.
fn sock_ma(s: &SocketAddr) -> Multiaddr {
    let ip = match s.ip() {
        IpAddr::V4(v) => Protocol::Ip4(v),
        IpAddr::V6(v) => Protocol::Ip6(v),
    };
    Multiaddr::empty().with(ip).with(Protocol::Tcp(s.port())).with(Protocol::Tls)
}

/// The relay part of a circuit address (`.../p2p/<relay>`), or None for a direct address.
fn relay_part(a: &Multiaddr) -> Option<Multiaddr> {
    let parts: Vec<Protocol> = a.iter().collect();
    let at = parts.iter().position(|p| matches!(p, Protocol::P2pCircuit))?;
    let relay: Multiaddr = parts[..at].iter().cloned().collect();
    peer_of(&relay).map(|_| relay)
}

fn peer_of(a: &Multiaddr) -> Option<PeerId> {
    a.iter().filter_map(|p| if let Protocol::P2p(id) = p { Some(id) } else { None }).last()
}

/// The address without its trailing `/p2p/<id>`.
fn without_peer(a: &Multiaddr) -> Multiaddr {
    let mut out = a.clone();
    if matches!(out.iter().last(), Some(Protocol::P2p(_))) {
        out.pop();
    }
    out
}

/// Drop a trailing `/p2p/<me>` (the dialer adds it).
fn strip_peer(a: Multiaddr, me: &PeerId) -> Multiaddr {
    match a.iter().last() {
        Some(Protocol::P2p(id)) if id == *me => without_peer(&a),
        _ => a,
    }
}

fn ip_of(a: &Multiaddr) -> Option<IpAddr> {
    a.iter().find_map(|p| match p {
        Protocol::Ip4(ip) => Some(IpAddr::V4(ip)),
        Protocol::Ip6(ip) => Some(IpAddr::V6(ip)),
        _ => None,
    })
}

fn port_of(a: &Multiaddr) -> Option<u16> {
    a.iter().find_map(|p| if let Protocol::Tcp(p) = p { Some(p) } else { None })
}

fn is_loopback(a: &Multiaddr) -> bool {
    a.iter().all(|p| !matches!(p, Protocol::P2pCircuit)) && ip_of(a).is_some_and(|ip| ip.is_loopback())
}

/// A listen address a volunteer may offer libp2p as its own when nothing verified exists.
fn volunteer_offers(a: &Multiaddr, allow_loopback: bool) -> bool {
    match ip_of(a) {
        Some(IpAddr::V4(v)) => !v.is_unspecified() && !v.is_link_local() && (allow_loopback || !v.is_loopback()),
        Some(IpAddr::V6(v)) => !v.is_unspecified() && (v.segments()[0] & 0xffc0) != 0xfe80 && (allow_loopback || !v.is_loopback()),
        None => false,
    }
}

/// A listen address worth publishing for direct dials: global IPv6 or a public-looking IPv4.
/// LAN addresses are left to the LAN beacons of the direct transport.
fn usable_direct(a: &Multiaddr, allow_loopback: bool) -> bool {
    match ip_of(a) {
        Some(IpAddr::V4(v)) if v.is_loopback() => allow_loopback,
        Some(IpAddr::V4(v)) => !(v.is_private() || v.is_link_local() || v.is_unspecified() || (v.octets()[0] == 100 && (v.octets()[1] & 0xc0) == 64)),
        Some(IpAddr::V6(v)) if v.is_loopback() => allow_loopback,
        Some(IpAddr::V6(v)) => v.segments()[0] & 0xe000 == 0x2000,
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn address_helpers() {
        let me = PeerId::random();
        let r = PeerId::random();
        let circuit: Multiaddr = format!("/ip4/203.0.113.7/tcp/4100/p2p/{r}/p2p-circuit/p2p/{me}").parse().unwrap();
        assert_eq!(strip_peer(circuit.clone(), &me).to_string(), format!("/ip4/203.0.113.7/tcp/4100/p2p/{r}/p2p-circuit"));
        assert_eq!(peer_of(&circuit), Some(me));
        assert_eq!(relay_part(&circuit).unwrap().to_string(), format!("/ip4/203.0.113.7/tcp/4100/p2p/{r}"));
        assert_eq!(relay_part(&"/ip4/203.0.113.7/tcp/4100/tls".parse().unwrap()), None);
        let vol: Multiaddr = format!("/ip4/203.0.113.7/tcp/4100/p2p/{r}").parse().unwrap();
        assert_eq!(without_peer(&vol).to_string(), "/ip4/203.0.113.7/tcp/4100");
        assert!(usable_direct(&"/ip6/2001:db8::1/tcp/1".parse().unwrap(), false));
        assert!(!usable_direct(&"/ip4/192.168.1.2/tcp/1".parse().unwrap(), false));
        assert!(!usable_direct(&"/ip4/127.0.0.1/tcp/1".parse().unwrap(), false));
        assert!(usable_direct(&"/ip4/127.0.0.1/tcp/1".parse().unwrap(), true));
        assert!(!is_loopback(&circuit));
        assert_eq!(sock_ma(&"[2001:db8::5]:40443".parse().unwrap()).to_string(), "/ip6/2001:db8::5/tcp/40443/tls");
        // a volunteer entry in a card stays within SPEC 14.3's 128 bytes
        assert!(vol.to_vec().len() <= 64);
        let v6: Multiaddr = format!("/ip6/2001:db8::1/tcp/4100/p2p/{r}").parse().unwrap();
        assert!(v6.to_vec().len() <= 64);
        assert_eq!(most_common(&["timeout", "refused", "timeout"]), "timeout");
        assert_eq!(most_common(&[]), "");
    }
}
