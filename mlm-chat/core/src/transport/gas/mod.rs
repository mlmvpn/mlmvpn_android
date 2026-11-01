//! The Google mailbox transport (SPEC 19). Two phones that cannot reach each other (two
//! operators, the home modem's firewall) both reach Google. The sender seals each frame to the
//! recipient (SealedFrame, SPEC 14.6) and leaves it in one of the recipient's mailboxes, a small
//! Apps Script in the recipient's own Google account (`relay/Mailbox.gs`), under the recipient's
//! daily tag. The recipient takes what waits for it from every mailbox it has switched on.
//!
//! - Sending: the card names up to three mailboxes. The tag picks which one goes first (load is
//!   spread over the day's tags and the contacts); a mailbox that fails is passed over.
//! - Taking: one worker per mailbox. While the app is in use a take waits up to 15 s at the
//!   mailbox, so a message arrives within about a second; idle phones ask every 30 s, and every
//!   90 s after a quiet while.
//! - Nothing here is trusted with a message: Google and the owner see sealed bytes, a tag that
//!   changes daily, an anonymous device id per relay per day, and sizes and times.
//! - A put that returned «ok» is checked again later (`chk`); if the cache let the item go, it
//!   is put again, until the mailbox says it was taken or 7 days passed.
//! - Frames up to 16 KiB (messages, receipts, cards) go as they are. A larger one (a file chunk,
//!   64 KiB) is cut into parts that each fill the 16 KiB bucket (`frame::KIND_PART`; Ehsan, 25 Oct
//!   2026: files through the relay too, «تکه تکه ... 16 کیلوبایتی»): every part looks the same to
//!   the mailbox, and the receiver keeps parts in its database until the frame is whole. The
//!   mailbox route is the last one tried, so files go this way only when nothing else reaches.
//!
//! The way to Google is found by the phone itself (`front`), per network, and remembered.
//!
//! A combined relay (SPEC 19.13) is one mailbox with two doors: the owner's Cloudflare Worker,
//! reached straight where Cloudflare answers on this network (`cf`), and a Google script that hands
//! each request on to it. Each call takes the Worker's door when there is one, else the Google
//! door; the protocol behind both is the same, so nothing else here tells the two kinds apart. A
//! relay is passed over (its health) only when both doors failed.

pub mod cf;
pub mod front;
mod h2;
pub mod http;
mod huffman;
pub mod proto;

use crate::frame;
use crate::transport::{Hooks, PeerInfo, Sent, Transport, TransportError, Verdict};
use crate::util::{b64url_decode, b64url_encode, now_ms, random};
use crate::UserId;
use http::{Client, Endpoint, HttpError};
use proto::RelayRef;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

/// Frames up to this go into a mailbox as they are; larger ones are cut into parts.
pub const MAX_WHOLE: usize = 16384;
/// Items in one put (the script refuses more).
const MAX_PUT_ITEMS: usize = 64;
/// Parts kept for a frame that is not whole yet: as long as a mailbox keeps an item.
pub const PART_KEEP_MS: u64 = 6 * 3600 * 1000;
/// Parts kept at most (about 64 MiB), for a sender that never sends the rest.
pub const MAX_PARTS: usize = 4096;
const MAX_PUT_CHARS: usize = 1_200_000;
const MAX_PENDING: usize = 500;
const PENDING_KEEP_MS: u64 = 7 * 24 * 3600 * 1000;
const TAKE_MAX_CHARS: u64 = 1_500_000;
const FLUSH_MS: u64 = 15_000;
/// A Worker's answer comes within this, beyond a take's own wait (SPEC 19.13).
const WORKER_TIMEOUT: Duration = Duration::from_secs(10);
const ACTIVE_MS: u64 = 3 * 60 * 1000;
const QUIET_MS: u64 = 10 * 60 * 1000;

/// One answer of the official feeds' copy (SPEC 18.2): items after the cursor, oldest first, as
/// (id, feed, seq, signed item bytes); the cursor for the next read; whether more wait; and the
/// copy's merged member count sketches as (period, packed).
#[derive(Clone, Debug, Default)]
pub struct FeedPage {
    pub items: Vec<([u8; 16], Vec<u8>, u64, Vec<u8>)>,
    pub next: u64,
    pub more: bool,
    pub census: Vec<(u32, Vec<u8>)>,
}

/// An answer of the copy is cut at about this many characters (a new phone reads a few pages).
const FEED_TAKE_CHARS: u64 = 400_000;
/// Items in one `fput` (the script refuses more).
pub const MAX_FEED_PUT: usize = 16;

/// base64 from the script: Google's own encoder pads it.
fn b64_any(s: &str) -> Option<Vec<u8>> {
    b64url_decode(s.trim_end_matches('='))
}

/// A relay of the user's list, complete (a draft with no deployment yet never reaches the
/// transport).
#[derive(Clone, Debug)]
pub struct GasRelay {
    pub rid: String,
    pub relay: RelayRef,
    pub owner_key: Option<[u8; 16]>,
    pub name: String,
    pub enabled: bool,
    pub announce: bool,
}

/// What one relay did since the last write, added to the day's row.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct StatDelta {
    pub req: u64,
    pub puts: u64,
    pub takes: u64,
    pub items_out: u64,
    pub items_in: u64,
    pub bytes_out: u64,
    pub bytes_in: u64,
    pub errors: u64,
    pub ms_sum: u64,
    pub ms_n: u64,
}

impl StatDelta {
    fn is_empty(&self) -> bool {
        *self == StatDelta::default()
    }
}

/// A frame put in a mailbox and not yet seen taken.
#[derive(Clone, Debug)]
pub struct Pending {
    pub id: [u8; 16],
    pub peer: UserId,
    pub loc: [u8; 32],
    pub hk: [u8; 32],
    pub relay: Vec<u8>,
    pub frame: Vec<u8>,
    pub first_ms: u64,
    pub put_ms: u64,
    pub next_ms: u64,
    pub tries: u32,
    pub day: u64,
}

/// What the transport needs from the node's database.
pub trait GasStore: Send + Sync + 'static {
    /// The relays of the list, complete ones only (the transport uses the enabled ones).
    fn relays(&self) -> Vec<GasRelay>;
    /// A secret of this device that never leaves it: the anonymous device ids derive from it.
    fn client_secret(&self) -> [u8; 32];
    fn add_stats(&self, rid: &str, day: u64, d: &StatDelta);
    fn pending_add(&self, p: &Pending);
    fn pending_due(&self, now_ms: u64, limit: usize) -> Vec<Pending>;
    fn pending_update(&self, id: &[u8; 16], next_ms: u64, tries: u32);
    fn pending_done(&self, id: &[u8; 16]);
    fn pending_count(&self) -> usize;
    /// Keep one part of a frame from `from`; all the frame's parts' bytes, in order, once the last
    /// one is here.
    fn part_add(&self, from: &UserId, part: &frame::Part, now_ms: u64) -> Option<Vec<Vec<u8>>>;
    /// The way to Google that last worked on a network (its label), kept over restarts: the chat
    /// process starts often on Android, and each start raced every front again.
    fn front_get(&self, _network: &str) -> Option<Endpoint> {
        None
    }
    fn front_put(&self, _network: &str, _front: &Endpoint) {}
    /// A small value kept over restarts (the Nostr transport's last end-of-stored-events per relay).
    fn kv_get(&self, _key: &str) -> Option<Vec<u8>> {
        None
    }
    fn kv_put(&self, _key: &str, _value: &[u8]) {}
}

#[derive(Clone, Debug)]
pub struct GasConfig {
    /// Tests and emulators: use this endpoint instead of racing Google fronts.
    pub endpoint: Option<Endpoint>,
    /// How long a take may wait at the mailbox while the app is in use.
    pub wait_ms: u64,
    /// Gap between takes when idle: soon after activity, and after a quiet while.
    pub idle_soon: Duration,
    pub idle_late: Duration,
    pub request_timeout: Duration,
    /// How often the check of put items runs, and when the first check of an item is.
    pub pending_tick: Duration,
    pub pending_first: Duration,
    /// Tests and emulators: a combined relay's Worker is here, instead of on Cloudflare. With an
    /// `endpoint` set and this not, combined relays use their Google door only.
    pub worker_endpoint: Option<Endpoint>,
    /// Cloudflare edges the app found clean on this network: tried first for a Worker.
    pub cf_ips: Vec<IpAddr>,
}

impl Default for GasConfig {
    fn default() -> Self {
        GasConfig {
            endpoint: None,
            wait_ms: 15_000,
            idle_soon: Duration::from_secs(30),
            idle_late: Duration::from_secs(90),
            request_timeout: Duration::from_secs(25),
            pending_tick: Duration::from_secs(5),
            pending_first: Duration::from_secs(45),
            worker_endpoint: None,
            cf_ips: Vec::new(),
        }
    }
}

/// Why a call to a mailbox did not work. `code()` is what the app turns into its own words.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CallError {
    /// No Google front answers on this network.
    Front,
    Network,
    Timeout,
    /// Google has no such deployment: a wrong id, or the project was deleted.
    NotFound,
    /// Google asks for a sign-in: the deployment is not open to «Anyone».
    Access,
    /// The script answered like a stranger's: the wrong key, or not this script.
    Auth,
    /// The script on Google stopped with an error of its own (Google's error page, «ReferenceError:
    /// … is not defined»): its code is not the one the app gave, often a copy that lost characters
    /// on the way. The code must be pasted again.
    Script,
    Paused,
    Limit,
    Quota,
    Busy,
    Remote,
    BadResponse,
    Bad,
    NotOwner,
    Full,
    Stopped,
}

impl CallError {
    pub fn code(self) -> &'static str {
        match self {
            CallError::Front => "front",
            CallError::Network => "network",
            CallError::Timeout => "timeout",
            CallError::NotFound => "not_found",
            CallError::Access => "access",
            CallError::Auth => "auth",
            CallError::Script => "script",
            CallError::Paused => "paused",
            CallError::Limit => "limit",
            CallError::Quota => "quota",
            CallError::Busy => "busy",
            CallError::Remote => "remote",
            CallError::BadResponse => "bad_response",
            CallError::Bad => "bad",
            CallError::NotOwner => "not_owner",
            CallError::Full => "full",
            CallError::Stopped => "stopped",
        }
    }

    fn transport(self) -> TransportError {
        match self {
            CallError::Front | CallError::Network => TransportError::Connect,
            CallError::Timeout => TransportError::Timeout,
            CallError::Remote | CallError::BadResponse | CallError::Bad => TransportError::Protocol("mailbox"),
            CallError::Stopped => TransportError::Stopped,
            _ => TransportError::Refused,
        }
    }

    /// How long a mailbox is left alone after this, given how many calls in a row failed.
    fn backoff(self, streak: u32) -> Duration {
        let s = match self {
            CallError::Quota => 900,
            CallError::Limit => 600,
            CallError::Paused => 300,
            // the user has to fix these: asked again every minute at first, then every 10
            CallError::Auth | CallError::Script | CallError::NotFound | CallError::Access | CallError::NotOwner => (60 * 2u64.pow(streak.clamp(1, 5) - 1)).min(600),
            _ => 2u64.pow(streak.clamp(1, 6)),
        };
        Duration::from_secs(s)
    }
}

/// How a mailbox is doing, as the screens show it.
#[derive(Clone, Debug)]
pub struct Health {
    /// `new` (never tried), `ok`, or a `CallError::code()`.
    pub state: &'static str,
    pub streak: u32,
    pub last_ok_ms: u64,
    /// The last round trip.
    pub ms: u64,
    pub retry_at_ms: u64,
    /// The script's version, from its last answer to a ping.
    pub version: u32,
    /// The door the last call that worked went through: `google`, or `cloudflare` (a combined
    /// relay's Worker, straight).
    pub door: &'static str,
}

impl Default for Health {
    fn default() -> Self {
        Health { state: "new", streak: 0, last_ok_ms: 0, ms: 0, retry_at_ms: 0, version: 0, door: "" }
    }
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct TestStep {
    pub step: &'static str,
    pub ok: bool,
    pub code: String,
}

/// The answer to «test this relay» (SPEC 19.7): the steps in order, stopping at the first that
/// failed.
#[derive(Debug, Clone, serde::Serialize)]
pub struct TestReport {
    pub steps: Vec<TestStep>,
    pub front: String,
    pub front_ms: u64,
    pub ms: u64,
    /// The owner key was accepted.
    pub owner: bool,
    pub version: u32,
    pub paused: bool,
    /// The owner opened it to everyone (a public relay).
    pub public: bool,
    /// A combined relay (SPEC 19.13): how its Worker was reached straight (`Cloudflare 1.2.3.4`),
    /// empty when not; `worker_ok` whether that door works on this network.
    pub worker: String,
    pub worker_ok: bool,
    pub ok: bool,
}

struct Shared {
    hooks: Arc<dyn Hooks>,
    store: Arc<dyn GasStore>,
    cfg: GasConfig,
    client: Client,
    stop: AtomicBool,
    foreground: AtomicBool,
    active_until: AtomicU64,
    last_move: AtomicU64,
    wake: (Mutex<u64>, Condvar),
    health: Mutex<HashMap<String, Health>>,
    fronts: Mutex<HashMap<String, (Endpoint, u64)>>,
    racing: Mutex<()>,
    front_retry_ms: AtomicU64,
    acc: Mutex<HashMap<String, StatDelta>>,
    flushed: AtomicU64,
    secret: [u8; 32],
    wanted: Mutex<HashSet<String>>,
    running: Mutex<HashSet<String>>,
    /// Turns sends to a contact's relays round all of them (`GasTransport::order`).
    spread: AtomicU64,
    /// The way to each Worker per network (`network|host`): an address that answered, or none
    /// (the Google door alone) until the time given.
    doors: Mutex<HashMap<String, (Option<Endpoint>, u64)>>,
    door_racing: Mutex<()>,
}

/// Which door a call may use (SPEC 19.13).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Door {
    /// The Worker's when it answers here, else Google's.
    Any,
    Cloudflare,
    Google,
}

pub struct GasTransport {
    sh: Arc<Shared>,
}

fn hex16(b: &[u8; 16]) -> String {
    hex::encode(b)
}

fn classify(resp: &http::Response) -> Result<Value, CallError> {
    match resp.status {
        200 => match serde_json::from_slice::<Value>(&resp.body) {
            Ok(v) if v.is_object() => match v.get("e").and_then(Value::as_str) {
                None => Ok(v),
                Some(e) => Err(match e {
                    "paused" => CallError::Paused,
                    "limit" => CallError::Limit,
                    "quota" => CallError::Quota,
                    "busy" => CallError::Busy,
                    "owner" => CallError::NotOwner,
                    "bad" => CallError::Bad,
                    _ => CallError::Remote,
                }),
            },
            _ => {
                let text = String::from_utf8_lossy(&resp.body);
                if text.contains("did not return anything") {
                    Err(CallError::Auth)
                } else if script_error(&text) {
                    Err(CallError::Script)
                } else if text.contains("accounts.google.com") || text.contains("Sign in") {
                    Err(CallError::Access)
                } else {
                    Err(CallError::BadResponse)
                }
            }
        },
        301 | 302 | 303 | 307 | 308 => match &resp.location {
            Some(l) if l.contains("accounts.google.com") => Err(CallError::Access),
            _ => Err(CallError::BadResponse),
        },
        401 | 403 => Err(CallError::Access),
        404 => Err(CallError::NotFound),
        429 => Err(CallError::Quota),
        500..=599 => Err(CallError::Remote),
        _ => Err(CallError::BadResponse),
    }
}

/// Google's page for a script that threw (in the owner's language, so only the error itself is
/// looked for): `ReferenceError: decoy is not defined (line 63, file Code)` and the like.
fn script_error(text: &str) -> bool {
    ["ReferenceError: ", "TypeError: ", "SyntaxError: ", "RangeError: ", "Exception: "].iter().any(|e| text.contains(e))
}

/// When a put item is looked at again: after `first`, then 3 min, 15 min, 1 h, 4 h, then every
/// 5 h (under the 6 h an item lives, so a «taken» mark is always seen).
fn next_check_ms(tries: u32, now: u64, first: Duration) -> u64 {
    let after = match tries {
        0 => first.as_millis() as u64,
        1 => 180_000,
        2 => 900_000,
        3 => 3_600_000,
        4 => 14_400_000,
        _ => 18_000_000,
    };
    now + after
}

impl Shared {
    fn stopped(&self) -> bool {
        self.stop.load(Ordering::SeqCst)
    }

    /// Sleep up to `d`; a `wake` or a stop ends it early.
    fn sleep(&self, d: Duration) {
        let (m, cv) = &self.wake;
        let mut g = m.lock().expect("wake");
        let seen = *g;
        let end = Instant::now() + d;
        while *g == seen && !self.stopped() {
            let left = end.saturating_duration_since(Instant::now());
            if left.is_zero() {
                break;
            }
            g = cv.wait_timeout(g, left).expect("wake").0;
        }
    }

    fn wake_all(&self) {
        let (m, cv) = &self.wake;
        *m.lock().expect("wake") += 1;
        cv.notify_all();
    }

    fn touch(&self) {
        let now = now_ms();
        self.active_until.store(now + ACTIVE_MS, Ordering::SeqCst);
        self.last_move.store(now, Ordering::SeqCst);
    }

    fn active(&self) -> bool {
        self.foreground.load(Ordering::SeqCst) || now_ms() < self.active_until.load(Ordering::SeqCst)
    }

    fn idle_gap(&self) -> Duration {
        if now_ms().saturating_sub(self.last_move.load(Ordering::SeqCst)) < QUIET_MS {
            self.cfg.idle_soon
        } else {
            self.cfg.idle_late
        }
    }

    // ---- accounting ---------------------------------------------------------------------

    fn note(&self, rid: &str, f: impl FnOnce(&mut StatDelta)) {
        f(self.acc.lock().expect("acc").entry(rid.to_string()).or_default());
        self.flush(false);
    }

    fn flush(&self, force: bool) {
        let now = now_ms();
        let last = self.flushed.load(Ordering::SeqCst);
        if !force && (now.saturating_sub(last) < FLUSH_MS || self.flushed.compare_exchange(last, now, Ordering::SeqCst, Ordering::SeqCst).is_err()) {
            return;
        }
        let drained: Vec<(String, StatDelta)> = self.acc.lock().expect("acc").drain().collect();
        let day = proto::day(now);
        for (rid, d) in drained.into_iter().filter(|(_, d)| !d.is_empty()) {
            self.store.add_stats(&rid, day, &d);
        }
    }

    /// `ms` is None for a call whose time is not a round trip (a waiting take): the last one stays.
    fn health_ok(&self, id: &str, ms: Option<u64>, door: &'static str) {
        let mut h = self.health.lock().expect("health");
        let e = h.entry(id.to_string()).or_default();
        e.door = door;
        e.state = "ok";
        e.streak = 0;
        e.last_ok_ms = now_ms();
        if let Some(ms) = ms {
            e.ms = ms;
        }
        e.retry_at_ms = 0;
    }

    fn health_err(&self, id: &str, err: CallError) {
        let mut h = self.health.lock().expect("health");
        let e = h.entry(id.to_string()).or_default();
        e.state = err.code();
        e.streak += 1;
        e.retry_at_ms = now_ms() + err.backoff(e.streak).as_millis() as u64;
    }

    fn retry_at(&self, id: &str) -> u64 {
        self.health.lock().expect("health").get(id).map(|h| h.retry_at_ms).unwrap_or(0)
    }

    // ---- the way to Google --------------------------------------------------------------

    fn endpoint(&self) -> Option<Endpoint> {
        if let Some(e) = &self.cfg.endpoint {
            return Some(e.clone());
        }
        let net = self.hooks.network_label();
        if let Some((e, _)) = self.fronts.lock().expect("fronts").get(&net) {
            return Some(e.clone());
        }
        let _one = self.racing.lock().expect("racing");
        if let Some((e, _)) = self.fronts.lock().expect("fronts").get(&net) {
            return Some(e.clone());
        }
        if now_ms() < self.front_retry_ms.load(Ordering::SeqCst) {
            return None;
        }
        // in steps, each begun only if the last found nothing (drnet, 26 Oct 2026: the race used to
        // start up to 32 TLS handshakes at once, about 150-200 KB, on every start of the chat
        // process): the front that last worked on this network, kept over restarts, alone and
        // quickly; then a small wave; then the rest
        let found = (|| {
            if let Some(r) = self.store.front_get(&net) {
                if let Some(ms) = http::probe(&r, front::REMEMBERED_TIMEOUT) {
                    return Some((r, ms));
                }
            }
            let mut all = front::candidates(None, true);
            let rest = all.split_off(all.len().min(front::FIRST_WAVE));
            front::race(all, front::PROBE_TIMEOUT).or_else(|| front::race(rest, front::PROBE_TIMEOUT))
        })();
        match found {
            Some((e, ms)) => {
                log::info!("gas: a way to Google found in {ms} ms");
                self.fronts.lock().expect("fronts").insert(net.clone(), (e.clone(), ms));
                self.store.front_put(&net, &e);
                Some(e)
            }
            None => {
                log::info!("gas: no way to Google found on this network");
                self.front_retry_ms.store(now_ms() + 8_000, Ordering::SeqCst);
                None
            }
        }
    }

    fn forget_front(&self, ep: &Endpoint) {
        self.fronts.lock().expect("fronts").retain(|_, (e, _)| e != ep);
        self.client.reset();
    }

    /// The way to a combined relay's Worker on this network, found and remembered (SPEC 19.13);
    /// None while only its Google door works here.
    fn worker_endpoint(&self, host: &str) -> Option<Endpoint> {
        if let Some(e) = &self.cfg.worker_endpoint {
            return Some(e.clone());
        }
        if self.cfg.endpoint.is_some() {
            return None; // an emulator for Google, none for Cloudflare: the Google door only
        }
        let net = self.hooks.network_label();
        let key = format!("{net}|{host}");
        let known = |doors: &HashMap<String, (Option<Endpoint>, u64)>| match doors.get(&key) {
            Some((Some(e), _)) => Some(Some(e.clone())),
            Some((None, until)) if now_ms() < *until => Some(None),
            _ => None,
        };
        if let Some(k) = known(&self.doors.lock().expect("doors")) {
            return k;
        }
        let _one = self.door_racing.lock().expect("door racing");
        if let Some(k) = known(&self.doors.lock().expect("doors")) {
            return k;
        }
        let memo_key = format!("cfdoor/{key}");
        let remembered = self.store.kv_get(&memo_key).and_then(|v| String::from_utf8(v).ok()).and_then(|m| cf::from_memo(host, &m));
        let found = cf::find(host, &self.cfg.cf_ips, remembered, true).map(|(e, _)| e);
        match &found {
            Some(e) => {
                if let Some(m) = cf::to_memo(e) {
                    self.store.kv_put(&memo_key, m.as_bytes());
                }
                self.doors.lock().expect("doors").insert(key, (Some(e.clone()), 0));
            }
            None => {
                log::info!("gas: no way to a Worker on this network, its Google door then");
                self.doors.lock().expect("doors").insert(key, (None, now_ms() + cf::RETRY_MS));
            }
        }
        found
    }

    /// The Worker's way stopped working here: its Google door, and Cloudflare looked at again later.
    fn forget_worker(&self, host: &str) {
        let key = format!("{}|{host}", self.hooks.network_label());
        self.doors.lock().expect("doors").insert(key, (None, now_ms() + cf::RETRY_MS));
        self.client.reset();
    }

    /// One request to a Worker straight. A Worker answers at once, or (a waiting take) when its wait
    /// is over, so anything much longer is a way the network broke: the request gets that long, then
    /// once more on a new connection, in case a kept one was dropped without a word. Before, a call
    /// waited its whole timeout (up to 40 s) every few minutes on MCI Wi-Fi (A50, 26 Oct 2026).
    fn worker_request(&self, ep: &Endpoint, host: &str, bytes: &[u8], timeout: Duration) -> Result<http::Response, HttpError> {
        let t = timeout.saturating_sub(self.cfg.request_timeout) + WORKER_TIMEOUT;
        match self.client.request(ep, "POST", host, "/", Some(bytes), t) {
            Err(HttpError::Timeout | HttpError::Io) if !self.stopped() => {
                self.client.drop_idle(ep);
                self.client.request(ep, "POST", host, "/", Some(bytes), t)
            }
            r => r,
        }
    }

    /// One call to a relay. Counts it, and keeps the relay's health.
    fn call(&self, relay: &RelayRef, body: Value, timeout: Duration) -> Result<Value, CallError> {
        self.call_via(relay, body, timeout, Door::Any)
    }

    /// One call through the door asked for: a combined relay's Worker straight when it answers
    /// here (a way that broke goes on through the Google door in the same call), else the script on
    /// Google. Every operation can be repeated safely (items are named by their content), so a call
    /// cut off at the Worker is made again at Google.
    fn call_via(&self, relay: &RelayRef, body: Value, timeout: Duration, door: Door) -> Result<Value, CallError> {
        let rid = relay.rid();
        let bytes = body.to_string().into_bytes();
        let t0 = Instant::now();
        let mut used = "google";
        let straight = match (&relay.worker, door) {
            (Some(host), Door::Any | Door::Cloudflare) => Some((host.clone(), self.worker_endpoint(host))),
            _ => None,
        };
        let mut through_worker: Option<Result<Value, CallError>> = None;
        match straight {
            Some((host, Some(ep))) => match self.worker_request(&ep, &host, &bytes, timeout) {
                // the Worker answers 200 only; anything else is an edge's or a middlebox's page
                // (an Irancell phone took a 403 for the relay's «access» fault, 26 Oct 2026)
                Ok(resp) if resp.status != 200 => {
                    log::info!("gas: a Worker's way answered {} here, its Google door then", resp.status);
                    self.forget_worker(&host);
                    if door == Door::Cloudflare {
                        through_worker = Some(Err(CallError::Front));
                    }
                }
                Ok(resp) => {
                    self.note(&rid, |d| d.bytes_in += resp.body.len() as u64);
                    used = "cloudflare";
                    through_worker = Some(classify(&resp));
                }
                Err(e) => {
                    log::info!("gas: a Worker's way failed here ({}), its Google door then", e.code());
                    if e == HttpError::Front || self.cfg.worker_endpoint.is_none() {
                        self.forget_worker(&host);
                    }
                    if door == Door::Cloudflare {
                        through_worker = Some(Err(match e {
                            HttpError::Front => CallError::Front,
                            HttpError::Timeout => CallError::Timeout,
                            HttpError::Io => CallError::Network,
                            HttpError::Protocol => CallError::BadResponse,
                        }));
                    }
                }
            },
            Some((_, None)) if door == Door::Cloudflare => through_worker = Some(Err(CallError::Front)),
            _ => {}
        }
        let mut retried = false;
        let result = if let Some(r) = through_worker {
            r
        } else {
            loop {
                if self.stopped() {
                    return Err(CallError::Stopped);
                }
                let Some(ep) = self.endpoint() else { break Err(CallError::Front) };
                match self.client.exec(&ep, &relay.id, &bytes, timeout) {
                    Ok(resp) => {
                        self.note(&rid, |d| d.bytes_in += resp.body.len() as u64);
                        break classify(&resp);
                    }
                    Err(HttpError::Front) if !retried && self.cfg.endpoint.is_none() => {
                        // the remembered front stopped working here: look again, once
                        retried = true;
                        self.forget_front(&ep);
                    }
                    Err(HttpError::Front) => break Err(CallError::Front),
                    Err(HttpError::Timeout) => break Err(CallError::Timeout),
                    Err(HttpError::Io) => break Err(CallError::Network),
                    Err(HttpError::Protocol) => break Err(CallError::BadResponse),
                }
            }
        };
        let ms = t0.elapsed().as_millis() as u64;
        // a take that waits at the mailbox (`w`) is slow on purpose: its time says nothing of speed
        let timed = !(body["op"] == "take" && body["w"].as_u64().unwrap_or(0) > 0);
        self.note(&rid, |d| {
            d.req += 1;
            d.bytes_out += bytes.len() as u64;
            if timed {
                d.ms_sum += ms;
                d.ms_n += 1;
            }
            if result.is_err() {
                d.errors += 1;
            }
        });
        match &result {
            Ok(_) => self.health_ok(&relay.id, timed.then_some(ms), used),
            // the owner's key being wrong, or a full queue, says nothing against the relay
            Err(CallError::NotOwner | CallError::Full | CallError::Bad) => {}
            Err(e) => self.health_err(&relay.id, *e),
        }
        result
    }

    /// One call to a script of the user's own that is not a mailbox (the Telegram reader, T68,
    /// SPEC 21): the same way to Google as a relay's, without a relay's counts or health. The
    /// answer comes with the bytes sent and received, which the app tells the user.
    fn script_call(&self, deployment: &str, body: &Value, timeout: Duration) -> Result<(Value, u64, u64), CallError> {
        let bytes = body.to_string().into_bytes();
        let mut retried = false;
        loop {
            if self.stopped() {
                return Err(CallError::Stopped);
            }
            let Some(ep) = self.endpoint() else { return Err(CallError::Front) };
            match self.client.exec(&ep, deployment, &bytes, timeout) {
                Ok(resp) => return classify(&resp).map(|v| (v, bytes.len() as u64, resp.body.len() as u64)),
                Err(HttpError::Front) if !retried && self.cfg.endpoint.is_none() => {
                    retried = true;
                    self.forget_front(&ep);
                }
                Err(HttpError::Front) => return Err(CallError::Front),
                Err(HttpError::Timeout) => return Err(CallError::Timeout),
                Err(HttpError::Io) => return Err(CallError::Network),
                Err(HttpError::Protocol) => return Err(CallError::BadResponse),
            }
        }
    }

    fn base(&self, relay: &RelayRef, op: &str) -> Value {
        let day = proto::day(now_ms());
        json!({ "op": op, "k": relay.key_hex(), "c": proto::client_id(&self.secret, &relay.rid(), day) })
    }

    fn ping(&self, relay: &RelayRef, owner: Option<&[u8; 16]>) -> Result<Value, CallError> {
        self.ping_via(relay, owner, Door::Any)
    }

    fn ping_via(&self, relay: &RelayRef, owner: Option<&[u8; 16]>, door: Door) -> Result<Value, CallError> {
        let mut b = self.base(relay, "ping");
        if let Some(o) = owner {
            b["o"] = json!(hex::encode(o));
        }
        let v = self.call_via(relay, b, self.cfg.request_timeout, door)?;
        if let Some(ver) = v.get("ver").and_then(Value::as_u64) {
            self.health.lock().expect("health").entry(relay.id.clone()).or_default().version = ver as u32;
        }
        Ok(v)
    }

    /// A request to one of our own relays carries its owner key: the owner's phone is never
    /// counted among its devices and never shut out when the owner pauses it for others. Before,
    /// only takes did, so a paused relay also stopped the owner's own messages to contacts who
    /// leave theirs there (Ehsan, 25 Oct 2026: «برای خودمم زده صاحبش آن را متوقف کرده»).
    fn own(&self, relay: &RelayRef, b: &mut Value) {
        if let Some(o) = self.store.relays().into_iter().find(|r| r.relay.id == relay.id).and_then(|r| r.owner_key) {
            b["o"] = json!(hex::encode(o));
        }
    }

    /// Put items under a tag. One result per item: `ok`, `done`, `size`, `bad`, `full`.
    fn put(&self, relay: &RelayRef, tag: &[u8; 16], items: &[([u8; 16], Vec<u8>)]) -> Result<Vec<&'static str>, CallError> {
        let list: Vec<Value> = items.iter().map(|(id, sealed)| json!([hex16(id), b64url_encode(sealed)])).collect();
        let mut b = self.base(relay, "put");
        self.own(relay, &mut b);
        b["t"] = json!(hex16(tag));
        b["i"] = Value::Array(list);
        let v = self.call(relay, b, self.cfg.request_timeout)?;
        let r = v.get("r").and_then(Value::as_array).ok_or(CallError::BadResponse)?;
        let out: Vec<&'static str> = r
            .iter()
            .map(|x| match x.as_str() {
                Some("ok") => "ok",
                Some("done") => "done",
                Some("size") => "size",
                Some("full") => "full",
                _ => "bad",
            })
            .collect();
        let stored = out.iter().filter(|x| **x == "ok").count() as u64;
        self.note(&relay.rid(), |d| {
            d.puts += 1;
            d.items_out += stored;
        });
        if out.len() != items.len() {
            return Err(CallError::BadResponse);
        }
        Ok(out)
    }

    /// Take what waits under `tags`, acknowledging `acks` (ids from the last take). Items come
    /// back as (id, bytes); `more` says the answer was cut at its size cap.
    fn take(&self, relay: &RelayRef, owner: Option<&[u8; 16]>, tags: &[[u8; 16]], acks: &[String], wait_ms: u64) -> Result<(Vec<(String, Vec<u8>)>, bool), CallError> {
        let mut b = self.base(relay, "take");
        if let Some(o) = owner {
            // the owner's own phone: not counted among the devices the relay helped, and not
            // locked out when the owner pauses the relay for others
            b["o"] = json!(hex::encode(o));
        }
        b["t"] = json!(tags.iter().map(hex16).collect::<Vec<_>>());
        b["a"] = json!(acks);
        b["w"] = json!(wait_ms);
        b["m"] = json!(TAKE_MAX_CHARS);
        let v = self.call(relay, b, self.cfg.request_timeout + Duration::from_millis(wait_ms))?;
        let mut out = Vec::new();
        if let Some(items) = v.get("i").and_then(Value::as_array) {
            for it in items {
                let (Some(id), Some(data)) = (it.get(0).and_then(Value::as_str), it.get(2).and_then(Value::as_str)) else { continue };
                match b64url_decode(data) {
                    Some(bytes) => out.push((id.to_string(), bytes)),
                    None => out.push((id.to_string(), Vec::new())),
                }
            }
        }
        let more = v.get("more").and_then(Value::as_u64) == Some(1);
        let n = out.len() as u64;
        self.note(&relay.rid(), |d| {
            d.takes += 1;
            d.items_in += n;
        });
        Ok((out, more))
    }

    fn chk(&self, relay: &RelayRef, tag: &[u8; 16], ids: &[[u8; 16]]) -> Result<Vec<&'static str>, CallError> {
        let mut b = self.base(relay, "chk");
        self.own(relay, &mut b);
        b["t"] = json!(hex16(tag));
        b["i"] = json!(ids.iter().map(hex16).collect::<Vec<_>>());
        let v = self.call(relay, b, self.cfg.request_timeout)?;
        let r = v.get("r").and_then(Value::as_array).ok_or(CallError::BadResponse)?;
        Ok(r.iter().map(|x| match x.as_str() { Some("wait") => "wait", Some("done") => "done", _ => "gone" }).collect())
    }

    /// One taken item: open it, check it is for us, give it to the node.
    fn deliver(&self, tags: &[[u8; 16]], sealed: &[u8]) -> Verdict {
        if sealed.is_empty() {
            return Verdict::Dropped;
        }
        let Some(op) = self.hooks.open_sealed(sealed) else { return Verdict::Dropped };
        if !tags.contains(&op.tag) || !self.hooks.accept_peer(&op.from_sk) {
            return Verdict::Dropped;
        }
        let from = op.from_sk;
        // a part of a larger frame: kept until the frame is whole, then that goes on
        let Ok(f) = frame::decode(&op.frame) else { return Verdict::Dropped };
        if f.kind == frame::KIND_PART {
            let Some(part) = frame::part_of(&f) else { return Verdict::Dropped };
            let Some(all) = self.store.part_add(&from, &part, now_ms()) else { return Verdict::Stored };
            let Ok(whole) = frame::join(&all) else { return Verdict::Dropped };
            let verdict = self.hooks.on_frame(from, whole);
            if verdict == Verdict::Retry {
                // not taken in now (the database was busy): the other parts are kept again, and
                // this one stays in the mailbox to finish the frame when it comes back
                for (i, data) in all.into_iter().enumerate().filter(|(i, _)| *i != part.index as usize) {
                    self.store.part_add(&from, &frame::Part { id: part.id, index: i as u8, count: part.count, data }, now_ms());
                }
                return Verdict::Retry;
            }
            if verdict == Verdict::Stored {
                self.hooks.on_mailbox(from, true);
            }
            return Verdict::Stored;
        }
        let verdict = self.hooks.on_frame(from, op.frame);
        if verdict == Verdict::Stored {
            self.hooks.on_mailbox(from, true);
        }
        verdict
    }

    // ---- threads ------------------------------------------------------------------------

    fn supervisor(self: Arc<Self>) {
        while !self.stopped() {
            let relays: Vec<GasRelay> = self.store.relays().into_iter().filter(|r| r.enabled).collect();
            {
                let mut wanted = self.wanted.lock().expect("wanted");
                wanted.clear();
                wanted.extend(relays.iter().map(|r| r.relay.id.clone()));
            }
            for r in relays {
                let id = r.relay.id.clone();
                let fresh = self.running.lock().expect("running").insert(id.clone());
                if fresh {
                    let sh = self.clone();
                    let done = id.clone();
                    let spawned = std::thread::Builder::new().name("mlmchat-gas-take".into()).spawn(move || {
                        sh.clone().worker(r);
                        sh.running.lock().expect("running").remove(&done);
                    });
                    if spawned.is_err() {
                        self.running.lock().expect("running").remove(&id);
                    }
                }
            }
            self.sleep(Duration::from_secs(2));
        }
    }

    fn worker(self: Arc<Self>, relay: GasRelay) {
        let mut acks: Vec<String> = Vec::new();
        let id = relay.relay.id.clone();
        while !self.stopped() && self.wanted.lock().expect("wanted").contains(&id) {
            let now = now_ms();
            let until = self.retry_at(&id);
            if until > now {
                self.sleep(Duration::from_millis((until - now).min(5_000)));
                continue;
            }
            let (_, loc) = self.hooks.me();
            let day = proto::day(now);
            let tags: Vec<[u8; 16]> = [day.saturating_sub(1), day, day + 1].iter().map(|d| proto::tag(&loc, *d)).collect();
            let active = self.active();
            let t0 = Instant::now();
            let wait = if active { self.cfg.wait_ms } else { 0 };
            match self.take(&relay.relay, relay.owner_key.as_ref(), &tags, &acks, wait) {
                Ok((items, more)) => {
                    acks.clear();
                    let n = items.len();
                    let mut retry = false;
                    for (item_id, bytes) in items {
                        match self.deliver(&tags, &bytes) {
                            Verdict::Retry => retry = true,
                            _ => acks.push(item_id),
                        }
                    }
                    if n > 0 {
                        self.touch();
                        log::info!("gas: {n} sealed frames taken");
                    }
                    if retry {
                        self.sleep(Duration::from_secs(1));
                    }
                    if more || n > 0 {
                        continue;
                    }
                    let gap = if active {
                        // the mailbox answered long before the wait we asked for (too many waiting there)
                        if wait > 0 && t0.elapsed() < Duration::from_millis(wait / 2) { Duration::from_secs(3) } else { Duration::ZERO }
                    } else {
                        self.idle_gap()
                    };
                    if !gap.is_zero() {
                        self.sleep(gap);
                    }
                }
                Err(CallError::Stopped) => break,
                Err(e) => log::info!("gas: a take failed ({})", e.code()),
            }
        }
        self.flush(true);
    }

    fn pending_loop(self: Arc<Self>) {
        while !self.stopped() {
            self.sleep(self.cfg.pending_tick);
            if self.stopped() {
                break;
            }
            self.flush(false);
            let now = now_ms();
            let due = self.store.pending_due(now, 64);
            if due.is_empty() {
                continue;
            }
            // one question per (relay, tag)
            let mut groups: Vec<((Vec<u8>, [u8; 16]), Vec<Pending>)> = Vec::new();
            for p in due {
                let tag = proto::tag(&p.loc, p.day);
                match groups.iter_mut().find(|(k, _)| k.0 == p.relay && k.1 == tag) {
                    Some((_, v)) => v.push(p),
                    None => groups.push(((p.relay.clone(), tag), vec![p])),
                }
            }
            for ((relay_bytes, tag), list) in groups {
                let Some(relay) = RelayRef::from_bytes(&relay_bytes) else {
                    list.iter().for_each(|p| self.store.pending_done(&p.id));
                    continue;
                };
                if self.retry_at(&relay.id) > now_ms() {
                    list.iter().for_each(|p| self.store.pending_update(&p.id, now_ms() + 60_000, p.tries));
                    continue;
                }
                let ids: Vec<[u8; 16]> = list.iter().map(|p| p.id).collect();
                match self.chk(&relay, &tag, &ids) {
                    Ok(states) => {
                        for (p, st) in list.iter().zip(states) {
                            self.pending_step(&relay, p, st);
                        }
                    }
                    Err(_) => list.iter().for_each(|p| self.store.pending_update(&p.id, now_ms() + 60_000, p.tries)),
                }
            }
        }
    }

    fn pending_step(&self, relay: &RelayRef, p: &Pending, state: &str) {
        let now = now_ms();
        match state {
            "done" => self.store.pending_done(&p.id),
            "wait" => self.store.pending_update(&p.id, next_check_ms(p.tries + 1, now, self.cfg.pending_first), p.tries + 1),
            _ => {
                if now.saturating_sub(p.first_ms) > PENDING_KEEP_MS {
                    self.store.pending_done(&p.id);
                    return;
                }
                // gone: the cache let it go (or it was taken long ago). Seal it again under today's
                // tag and put it again; the receiver drops a repeat it already has.
                let day = proto::day(now);
                let tag = proto::tag(&p.loc, day);
                let (me, _) = self.hooks.me();
                let redo = frame::seal(&p.frame, &me, &p.hk, &tag).ok().map(|sealed| (proto::item_id(&sealed), sealed));
                let Some((new_id, sealed)) = redo else {
                    self.store.pending_done(&p.id);
                    return;
                };
                match self.put(relay, &tag, &[(new_id, sealed)]) {
                    Ok(r) if matches!(r.first().copied(), Some("ok")) => {
                        self.store.pending_done(&p.id);
                        self.store.pending_add(&Pending { id: new_id, day, put_ms: now, next_ms: next_check_ms(0, now, self.cfg.pending_first), tries: p.tries + 1, ..p.clone() });
                        log::info!("gas: a frame the mailbox had lost was put again");
                    }
                    Ok(r) if matches!(r.first().copied(), Some("done")) => self.store.pending_done(&p.id),
                    _ => self.store.pending_update(&p.id, now + 60_000, p.tries),
                }
            }
        }
    }
}

impl GasTransport {
    pub fn start(cfg: GasConfig, hooks: Arc<dyn Hooks>, store: Arc<dyn GasStore>) -> Arc<GasTransport> {
        let sh = Arc::new(Shared {
            secret: store.client_secret(),
            hooks,
            store,
            cfg,
            client: Client::default(),
            stop: AtomicBool::new(false),
            foreground: AtomicBool::new(false),
            active_until: AtomicU64::new(0),
            last_move: AtomicU64::new(now_ms()),
            wake: (Mutex::new(0), Condvar::new()),
            health: Mutex::new(HashMap::new()),
            fronts: Mutex::new(HashMap::new()),
            racing: Mutex::new(()),
            front_retry_ms: AtomicU64::new(0),
            acc: Mutex::new(HashMap::new()),
            flushed: AtomicU64::new(now_ms()),
            wanted: Mutex::new(HashSet::new()),
            running: Mutex::new(HashSet::new()),
            spread: AtomicU64::new(0),
            doors: Mutex::new(HashMap::new()),
            door_racing: Mutex::new(()),
        });
        for (name, f) in [("mlmchat-gas-super", Shared::supervisor as fn(Arc<Shared>)), ("mlmchat-gas-pending", Shared::pending_loop as fn(Arc<Shared>))] {
            let s = sh.clone();
            let _ = std::thread::Builder::new().name(name.into()).spawn(move || f(s));
        }
        Arc::new(GasTransport { sh })
    }

    /// The list of relays changed: look at it now.
    pub fn refresh(&self) {
        self.sh.wake_all();
    }

    /// The phone moved to another network: forget the way to Google and look again when needed.
    pub fn network_changed(&self) {
        self.sh.client.reset();
        self.sh.front_retry_ms.store(0, Ordering::SeqCst);
        self.sh.doors.lock().expect("doors").retain(|_, (e, _)| e.is_some());
        for h in self.sh.health.lock().expect("health").values_mut() {
            h.retry_at_ms = 0;
        }
        self.sh.wake_all();
    }

    /// HTTP/2 to the fronts that offer it (HTTP/1.1 elsewhere): the same calls, about 40% fewer
    /// bytes in. Off until it is proven on phones.
    pub fn set_http2(&self, on: bool) {
        self.sh.client.set_http2(on);
    }

    /// The app is in use (true) or in the background (false).
    pub fn set_foreground(&self, on: bool) {
        self.sh.foreground.store(on, Ordering::SeqCst);
        if on {
            self.sh.touch();
        }
        self.sh.wake_all();
    }

    pub fn health(&self) -> HashMap<String, Health> {
        self.sh.health.lock().expect("health").clone()
    }

    /// The way to Google in use on this network and its time, if one has been found.
    pub fn front_now(&self) -> Option<(String, u64)> {
        if let Some(e) = &self.sh.cfg.endpoint {
            return Some((front::label(e), 0));
        }
        let net = self.sh.hooks.network_label();
        self.sh.fronts.lock().expect("fronts").get(&net).map(|(e, ms)| (front::label(e), *ms))
    }

    pub fn flush_stats(&self) {
        self.sh.flush(true);
    }

    /// «Test this relay» (SPEC 19.7): the way to Google, the script and the key, and a round trip
    /// of a small item through the mailbox. A combined relay (SPEC 19.13) first tries its Worker
    /// straight (step `worker`, Cloudflare looked at afresh), then its Google door; one door that
    /// works is enough to go on. Blocking, up to about 40 seconds.
    pub fn test(&self, relay: &RelayRef, owner: Option<&[u8; 16]>) -> TestReport {
        let t0 = Instant::now();
        let mut rep = TestReport { steps: Vec::new(), front: String::new(), front_ms: 0, ms: 0, owner: false, version: 0, paused: false, public: false, worker: String::new(), worker_ok: false, ok: false };
        let sh = &self.sh;
        sh.front_retry_ms.store(0, Ordering::SeqCst);
        let took = |rep: &mut TestReport, v: &Value| {
            rep.version = v.get("ver").and_then(Value::as_u64).unwrap_or(0) as u32;
            rep.owner = v.get("owner").and_then(Value::as_u64) == Some(1);
            rep.paused = v.get("paused").and_then(Value::as_u64) == Some(1);
            rep.public = v.get("pub").and_then(Value::as_u64) == Some(1);
        };
        if let Some(host) = &relay.worker {
            sh.doors.lock().expect("doors").remove(&format!("{}|{host}", sh.hooks.network_label()));
            let r = sh.ping_via(relay, owner, Door::Cloudflare);
            if let Ok(v) = &r {
                took(&mut rep, v);
                rep.worker_ok = true;
                rep.worker = sh.worker_endpoint(host).map(|e| front::label(&e)).unwrap_or_default();
            }
            rep.steps.push(TestStep { step: "worker", ok: r.is_ok(), code: r.err().map(|e| e.code().to_string()).unwrap_or_default() });
        }
        let end = |rep: &mut TestReport| {
            rep.ms = t0.elapsed().as_millis() as u64;
        };
        match sh.endpoint() {
            Some(e) => {
                rep.front = front::label(&e);
                rep.front_ms = self.front_now().map(|(_, ms)| ms).unwrap_or(0);
                rep.steps.push(TestStep { step: "front", ok: true, code: String::new() });
                match sh.ping_via(relay, owner, Door::Google) {
                    Ok(v) => {
                        if !rep.worker_ok {
                            took(&mut rep, &v);
                        }
                        let old = v.get("ver").and_then(Value::as_u64).unwrap_or(0) < proto::MIN_SCRIPT_VERSION as u64;
                        rep.steps.push(TestStep { step: "script", ok: !old, code: if old { "update".into() } else { String::new() } });
                        if old && !rep.worker_ok {
                            end(&mut rep);
                            return rep;
                        }
                    }
                    Err(e) => {
                        rep.steps.push(TestStep { step: "script", ok: false, code: e.code().into() });
                        if !rep.worker_ok {
                            end(&mut rep);
                            return rep;
                        }
                    }
                }
            }
            None => {
                rep.steps.push(TestStep { step: "front", ok: false, code: CallError::Front.code().into() });
                if !rep.worker_ok {
                    end(&mut rep);
                    return rep;
                }
            }
        }
        // a small item out and back, under a tag nobody else uses
        let tag: [u8; 16] = random();
        let id: [u8; 16] = random();
        let round = (|| -> Result<(), CallError> {
            let put = sh.put(relay, &tag, &[(id, b"dGVzdA".to_vec())])?;
            if put.first().copied() != Some("ok") {
                return Err(CallError::Bad);
            }
            let (got, _) = sh.take(relay, None, &[tag], &[], 0)?;
            if !got.iter().any(|(i, _)| *i == hex16(&id)) {
                return Err(CallError::Remote);
            }
            sh.take(relay, None, &[tag], &[hex16(&id)], 0)?;
            Ok(())
        })();
        rep.steps.push(TestStep { step: "mailbox", ok: round.is_ok(), code: round.err().map(|e| e.code().to_string()).unwrap_or_default() });
        rep.ok = round.is_ok();
        if let Some(h) = sh.health.lock().expect("health").get_mut(&relay.id) {
            h.retry_at_ms = 0;
        }
        rep.ms = t0.elapsed().as_millis() as u64;
        rep
    }

    /// A call to one of the user's own scripts that is not a mailbox (the Telegram reader, T68):
    /// the answer, and the bytes sent and received. Blocking.
    pub fn script_call(&self, deployment: &str, body: &Value, timeout: Duration) -> Result<(Value, u64, u64), CallError> {
        self.sh.script_call(deployment, body, timeout)
    }

    /// The owner's view from the script itself: requests, devices and recipients, today and the
    /// last days (the script's `stats`).
    pub fn server_stats(&self, relay: &RelayRef, owner: &[u8; 16]) -> Result<Value, CallError> {
        let mut b = self.sh.base(relay, "stats");
        b["o"] = json!(hex::encode(owner));
        self.sh.call(relay, b, self.sh.cfg.request_timeout)
    }

    /// Pause the relay for everyone but its owner, set how many devices may use it in a day
    /// (0 = no limit), or open it to everyone (`public`). Any may be left out.
    pub fn admin(&self, relay: &RelayRef, owner: &[u8; 16], paused: Option<bool>, max_clients: Option<u32>, public: Option<bool>) -> Result<Value, CallError> {
        let mut b = self.sh.base(relay, "admin");
        b["o"] = json!(hex::encode(owner));
        if let Some(p) = paused {
            b["paused"] = json!(p);
        }
        if let Some(m) = max_clients {
            b["max_clients"] = json!(m);
        }
        if let Some(p) = public {
            b["pub"] = json!(p);
        }
        self.sh.call(relay, b, self.sh.cfg.request_timeout)
    }

    /// Whether a relay is open to everyone, by the relay's own word (its owner's switch): what a
    /// phone asks before it borrows one, and again now and then while it does. Blocking.
    pub fn public_probe(&self, relay: &RelayRef) -> Result<bool, CallError> {
        let v = self.sh.ping(relay, None)?;
        let ver = v.get("ver").and_then(Value::as_u64).unwrap_or(0) as u32;
        Ok(ver >= proto::PUBLIC_SCRIPT_VERSION && v.get("pub").and_then(Value::as_u64) == Some(1) && v.get("paused").and_then(Value::as_u64) != Some(1))
    }

    /// Read the official feeds' copy (SPEC 18.2): what is new after `since` in these feeds.
    /// Blocking, one call.
    pub fn feed_get(&self, relay: &RelayRef, feeds: &[Vec<u8>], since: u64) -> Result<FeedPage, CallError> {
        let mut b = self.sh.base(relay, "fget");
        b["f"] = json!(feeds.iter().map(hex::encode).collect::<Vec<_>>());
        b["s"] = json!(since);
        b["m"] = json!(FEED_TAKE_CHARS);
        let v = self.sh.call(relay, b, self.sh.cfg.request_timeout)?;
        let mut page = FeedPage { next: v.get("s").and_then(Value::as_u64).ok_or(CallError::BadResponse)?, more: v.get("more").and_then(Value::as_u64) == Some(1), ..FeedPage::default() };
        for it in v.get("i").and_then(Value::as_array).into_iter().flatten() {
            let id = it.get(0).and_then(Value::as_str).and_then(|s| hex::decode(s).ok()).and_then(|b| <[u8; 16]>::try_from(b.as_slice()).ok());
            let feed = it.get(1).and_then(Value::as_str).and_then(|s| hex::decode(s).ok());
            let seq = it.get(2).and_then(Value::as_u64);
            let data = it.get(3).and_then(Value::as_str).and_then(b64_any);
            if let (Some(id), Some(feed), Some(seq), Some(data)) = (id, feed, seq, data) {
                page.items.push((id, feed, seq, data));
            }
        }
        for c in v.get("cs").and_then(Value::as_array).into_iter().flatten() {
            if let (Some(epoch), Some(data)) = (c.get(0).and_then(Value::as_u64), c.get(1).and_then(Value::as_str).and_then(b64_any)) {
                page.census.push((epoch as u32, data));
            }
        }
        let n = page.items.len() as u64;
        self.sh.note(&relay.rid(), |d| {
            d.takes += 1;
            d.items_in += n;
        });
        Ok(page)
    }

    /// Leave items of one feed in the copy, as (id, signed item bytes, class: 0 a post, 1 an admin
    /// list, name or photo, 2 a reaction), with our member count sketches (period, packed) when
    /// they hold something the copy lacks. One result per item: `ok`, `dup` or `bad`.
    pub fn feed_put(&self, relay: &RelayRef, feed: &[u8], items: &[([u8; 16], Vec<u8>, u8)], census: &[(u32, Vec<u8>)]) -> Result<Vec<&'static str>, CallError> {
        let mut b = self.sh.base(relay, "fput");
        b["f"] = json!(hex::encode(feed));
        b["i"] = Value::Array(items.iter().map(|(id, data, class)| json!([hex::encode(id), b64url_encode(data), class])).collect());
        if !census.is_empty() {
            b["cs"] = Value::Array(census.iter().map(|(e, regs)| json!([e, b64url_encode(regs)])).collect());
        }
        let v = self.sh.call(relay, b, self.sh.cfg.request_timeout)?;
        let r = v.get("r").and_then(Value::as_array).ok_or(CallError::BadResponse)?;
        let out: Vec<&'static str> = r
            .iter()
            .map(|x| match x.as_str() {
                Some("ok") => "ok",
                Some("dup") => "dup",
                _ => "bad",
            })
            .collect();
        if out.len() != items.len() {
            return Err(CallError::BadResponse);
        }
        let stored = out.iter().filter(|x| **x == "ok").count() as u64;
        self.sh.note(&relay.rid(), |d| {
            d.puts += 1;
            d.items_out += stored;
        });
        Ok(out)
    }

    /// The order to try a contact's relays in: each send starts at the next one, so the load
    /// spreads over all of them (two sends at once go to two relays); relays in a cool-down after
    /// failures go last. Before, every send went to the day's primary relay and the others only
    /// took over when it failed (Ehsan, 26 Oct 2026: «اگر کاربر ۲ یا ۱۰ تا رله گوگل داشت …
    /// باید از همشون استفاده کنه»).
    fn order(&self, relays: &[RelayRef], tag: &[u8; 16]) -> Vec<RelayRef> {
        let n = relays.len();
        let turn = self.sh.spread.fetch_add(1, Ordering::SeqCst) as usize;
        let first = (proto::primary(tag, n) + turn) % n.max(1);
        let now = now_ms();
        let mut ordered: Vec<RelayRef> = (0..n).map(|k| relays[(first + k) % n].clone()).collect();
        ordered.sort_by_key(|r| self.sh.retry_at(&r.id) > now);
        ordered
    }
}

impl Transport for GasTransport {
    fn name(&self) -> &'static str {
        "gas"
    }

    fn send(&self, peer: &PeerInfo, frames: &[Vec<u8>]) -> Result<Sent, TransportError> {
        let sh = &self.sh;
        let relays: Vec<RelayRef> = peer.relays.iter().filter_map(|r| RelayRef::from_bytes(r)).collect();
        if frames.is_empty() || relays.is_empty() || peer.hk == [0; 32] {
            return Err(TransportError::NoRoute);
        }
        let (me, _) = sh.hooks.me();
        let now = now_ms();
        let day = proto::day(now);
        let tag = proto::tag(&peer.loc, day);
        // each frame as one item, or as its parts; `units[k]` = the items of frame k
        let mut items: Vec<([u8; 16], Vec<u8>)> = Vec::new();
        let mut plain: Vec<Vec<u8>> = Vec::new();
        let mut units: Vec<std::ops::Range<usize>> = Vec::new();
        let mut chars = 0;
        for f in frames {
            let pieces = if f.len() <= MAX_WHOLE { vec![f.clone()] } else { frame::split(f, random::<8>()).map_err(|_| TransportError::NoRoute)? };
            let mut sealed = Vec::with_capacity(pieces.len());
            let mut more = 0;
            for p in &pieces {
                let s = frame::seal(p, &me, &peer.hk, &tag).map_err(|_| TransportError::NoRoute)?;
                more += s.len() * 4 / 3 + 8;
                sealed.push(s);
            }
            if !items.is_empty() && (chars + more > MAX_PUT_CHARS || items.len() + sealed.len() > MAX_PUT_ITEMS) {
                break;
            }
            chars += more;
            let start = items.len();
            for (s, p) in sealed.into_iter().zip(pieces) {
                items.push((proto::item_id(&s), s));
                plain.push(p);
            }
            units.push(start..items.len());
        }
        sh.touch();
        // a conversation has begun: the takes that sleep (idle) look at once, then at the quick pace
        sh.wake_all();
        let mut last = CallError::Front;
        for relay in self.order(&relays, &tag) {
            if sh.stopped() {
                return Err(TransportError::Stopped);
            }
            match sh.put(&relay, &tag, &items) {
                Ok(results) => {
                    // up to the first frame the mailbox could not hold whole
                    let mut verdicts = Vec::new();
                    for unit in &units {
                        let rs: Vec<&str> = unit.clone().map(|i| results.get(i).copied().unwrap_or("full")).collect();
                        if rs.iter().any(|r| *r == "size" || *r == "bad") {
                            verdicts.push(Verdict::Dropped);
                            continue;
                        }
                        if !rs.iter().all(|r| *r == "ok" || *r == "done") {
                            break;
                        }
                        verdicts.push(Verdict::Stored);
                        for (i, r) in unit.clone().zip(rs) {
                            if r == "ok" && sh.store.pending_count() < MAX_PENDING {
                                // a lost item is put again from its own bytes (a part, or the frame)
                                sh.store.pending_add(&Pending {
                                    id: items[i].0,
                                    peer: peer.sk,
                                    loc: peer.loc,
                                    hk: peer.hk,
                                    relay: relay.to_bytes(),
                                    frame: plain[i].clone(),
                                    first_ms: now,
                                    put_ms: now,
                                    next_ms: next_check_ms(0, now, sh.cfg.pending_first),
                                    tries: 0,
                                    day,
                                });
                            }
                        }
                    }
                    if verdicts.is_empty() {
                        last = CallError::Full;
                        continue;
                    }
                    log::info!("gas: {} frames put in a mailbox", verdicts.len());
                    sh.hooks.on_mailbox(peer.sk, false);
                    return Ok(Sent { verdicts, via: "gas" });
                }
                Err(e) => last = e,
            }
        }
        Err(last.transport())
    }

    fn advertised_addrs(&self) -> Vec<SocketAddr> {
        Vec::new()
    }

    fn stop(&self) {
        self.sh.stop.store(true, Ordering::SeqCst);
        self.sh.wake_all();
        self.sh.flush(true);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checks_come_sooner_at_first_and_never_slower_than_the_item_lives() {
        let f = Duration::from_secs(45);
        assert_eq!(next_check_ms(0, 1000, f), 46_000);
        assert!(next_check_ms(9, 0, f) < 6 * 3600 * 1000);
        assert!(next_check_ms(2, 0, f) > next_check_ms(1, 0, f));
    }

    #[test]
    fn answers_become_errors() {
        let r = |status: u16, body: &str, loc: Option<&str>| http::Response { status, location: loc.map(String::from), body: body.as_bytes().to_vec() };
        assert!(classify(&r(200, "{\"ok\":1}", None)).is_ok());
        assert_eq!(classify(&r(200, "{\"e\":\"limit\"}", None)).unwrap_err(), CallError::Limit);
        assert_eq!(classify(&r(200, "{\"e\":\"paused\"}", None)).unwrap_err(), CallError::Paused);
        assert_eq!(classify(&r(200, "<p>The script completed but did not return anything.</p>", None)).unwrap_err(), CallError::Auth);
        // a copy that lost characters on the way (Ehsan's relay, 25 Oct 2026; Finnish page)
        let page = "<title>Virhe</title><div>ReferenceError: decoy is not defined (rivi 63, tiedosto Code)</div>";
        assert_eq!(classify(&r(200, page, None)).unwrap_err(), CallError::Script);
        assert_eq!(classify(&r(302, "", Some("https://accounts.google.com/ServiceLogin"))).unwrap_err(), CallError::Access);
        assert_eq!(classify(&r(404, "x", None)).unwrap_err(), CallError::NotFound);
        assert_eq!(classify(&r(429, "x", None)).unwrap_err(), CallError::Quota);
        assert_eq!(classify(&r(503, "x", None)).unwrap_err(), CallError::Remote);
    }

    #[test]
    fn a_slow_failure_backs_off_longer_and_a_stranger_answer_waits_ten_minutes() {
        assert!(CallError::Network.backoff(1) < CallError::Network.backoff(4));
        assert_eq!(CallError::Auth.backoff(1), Duration::from_secs(60));
        assert_eq!(CallError::Auth.backoff(9), Duration::from_secs(600));
        assert!(CallError::Network.backoff(50) <= Duration::from_secs(64));
    }
}
