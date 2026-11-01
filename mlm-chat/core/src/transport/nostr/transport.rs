//! The Nostr transport (SPEC 20): frames go as events to public relays, under the recipient's
//! day tag, and arrive the moment they are written, over WebSocket connections kept open.
//!
//! - Reading: the first `keep` relays of the list that open on this network are kept open
//!   (plainly, else with the ClientHello in two TLS records); one that fails rests and the next
//!   one of the list takes its place, and gives it back when its rest is over. Phones on the same
//!   networks keep the same relays, in the list's order. Our own relays (`own`, T54) never rest:
//!   one that stops is tried again within seconds. The node puts the ones open in our card (key
//!   13), and our own ones through a short outage too.
//! - Taking: one subscription per connection to our tags of yesterday, today and tomorrow, since
//!   three hours before that relay's last end-of-stored-events (phones' clocks are often wrong);
//!   what waits there comes first, then everything new at once. A ping every 30 s keeps it open.
//!   A relay that closes our subscription gets a new connection that subscribes again.
//! - Sending: only to the relays the contact's card names (key 13) that our own list has too (we
//!   never connect to an address a card made up). An open connection is used; otherwise one only
//!   for writing, closed after `IDLE_WRITE` without a send. An event per frame (a larger frame in
//!   16 KiB parts, as in the Google mailbox); the frame counts as sent when every part has an OK
//!   from at least one relay, without waiting out a relay that never answers. A frame of several
//!   parts (a file's chunk) goes to one relay at a time, the next only if that one fails. A relay
//!   that refuses writes for good (payment, a sign-in) is not written to again; one that refuses
//!   for now (too many writes, a full disk) only rests its writes a little.
//! - Nothing here is trusted with a message: relays see a throwaway key, a tag that changes every
//!   day, sizes, times and the phone's address.

use super::event::{self, p_tag};
use super::feedrelay;
use super::relay_name;
use crate::feed::SignedFeedItem;
use super::ws::{Split, Stage, Ws};
use crate::frame;
use crate::transport::gas::{proto, GasStore, MAX_WHOLE};
use crate::transport::{Hooks, PeerInfo, Sent, Transport, TransportError, Verdict};
use crate::util::{now_ms, random};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet, VecDeque};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

#[derive(Clone, Debug)]
pub struct NostrConfig {
    /// The relays to use, best first (`wss://`, or `ws://` for tests).
    pub relays: Vec<String>,
    /// Connections kept open for reading.
    pub keep: usize,
    pub connect_timeout: Duration,
    /// How long a send waits for a relay's OK.
    pub ok_timeout: Duration,
    /// A relay that failed rests this long before it is tried again.
    pub rest: Duration,
    /// Our own relays (T54) of the list: never rested, never given up on for writes.
    pub own: Vec<String>,
}

impl Default for NostrConfig {
    fn default() -> Self {
        NostrConfig {
            relays: super::RELAYS.iter().map(|s| s.to_string()).collect(),
            keep: 4,
            connect_timeout: Duration::from_secs(10),
            ok_timeout: Duration::from_secs(12),
            rest: Duration::from_secs(600),
            own: super::files::relays(),
        }
    }
}

/// One relay of the list, as the relay picker shows it (SPEC 20.6): how it stands, what the
/// user's last measuring found, and whether it is the one they prefer.
#[derive(Clone, Debug, serde::Serialize)]
pub struct RelayChoice {
    pub relay: String,
    /// As cards carry it: the URL without `wss://`.
    pub name: String,
    /// One of our own relays (T54).
    pub own: bool,
    pub preferred: bool,
    /// `open`, `connecting`, `resting`, `idle` (not in use now), or a `Stage` code of the last failure.
    pub state: String,
    pub reads: bool,
    pub writes: bool,
    /// The last measuring: milliseconds to a subscription's end-of-stored answer, or where it failed.
    pub ms: Option<u64>,
    pub failed: Option<String>,
    pub way: &'static str,
    /// When it was measured (Unix ms), 0 for never on this network.
    pub measured_ms: u64,
}

/// What measuring one relay found.
#[derive(Clone, Debug)]
struct Measured {
    ms: Option<u64>,
    failed: Option<&'static str>,
    way: &'static str,
    at_ms: u64,
}

/// One relay, as the screens show it.
#[derive(Clone, Debug, serde::Serialize)]
pub struct RelayView {
    pub relay: String,
    /// `open`, `connecting`, `resting`, or a `Stage` code of the last failure.
    pub state: String,
    pub way: &'static str,
    /// Kept open and subscribed to our tags (else only written to).
    pub reads: bool,
    pub writes: bool,
    pub events_in: u64,
    pub events_out: u64,
}

/// One relay connection, driven by its own thread.
struct Link {
    url: String,
    /// One of the `keep` connections, subscribed to our tags; else only written to.
    reads: bool,
    /// One of our own relays (`NostrConfig::own`).
    own: bool,
    /// Ours, or the one the user prefers: never rested, tried again within seconds.
    steady: AtomicBool,
    out: Mutex<VecDeque<String>>,
    acks: Mutex<HashMap<String, Sender<bool>>>,
    open: AtomicBool,
    /// False once the relay refused a write for good.
    writes: AtomicBool,
    /// Not written to before this: it refused for now, or answered nothing (`rest_writes`).
    writes_after: Mutex<Option<Instant>>,
    /// When it was last open: our own relay stays in the card through a short outage.
    opened: Mutex<Option<Instant>>,
    /// Connections that failed in a row: a send does not wait for a link that is failing.
    fails: AtomicU32,
    state: Mutex<String>,
    split: Mutex<Split>,
    counts: Mutex<(u64, u64)>,
    stop: AtomicBool,
    /// The relay closed our subscription: this session ends and the next one subscribes again.
    resub: AtomicBool,
    /// When a send last used it (a writing-only connection closes after `IDLE_WRITE`).
    used: Mutex<Instant>,
}

impl Link {
    fn new(url: &str, reads: bool, own: bool, steady: bool) -> Arc<Link> {
        Arc::new(Link {
            url: url.to_string(),
            reads,
            own,
            steady: AtomicBool::new(own || steady),
            out: Mutex::new(VecDeque::new()),
            acks: Mutex::new(HashMap::new()),
            open: AtomicBool::new(false),
            writes: AtomicBool::new(true),
            writes_after: Mutex::new(None),
            opened: Mutex::new(None),
            fails: AtomicU32::new(0),
            state: Mutex::new("connecting".into()),
            split: Mutex::new(Split::None),
            counts: Mutex::new((0, 0)),
            stop: AtomicBool::new(false),
            resub: AtomicBool::new(false),
            used: Mutex::new(Instant::now()),
        })
    }

    fn stopped(&self) -> bool {
        self.stop.load(Ordering::SeqCst)
    }

    /// It takes writes now: it never refused for good, and no rest of its writes is running.
    fn writable(&self) -> bool {
        self.writes.load(Ordering::SeqCst) && self.writes_after.lock().expect("writes after").is_none_or(|t| Instant::now() >= t)
    }

    /// No writes to it for a while: `OWN_WRITE_REST` for our own relay, else `WRITE_REST`.
    fn rest_writes(&self) {
        let d = if self.own { OWN_WRITE_REST } else { WRITE_REST };
        *self.writes_after.lock().expect("writes after") = Some(Instant::now() + d);
    }

    /// Named in our card: open now, or a steady one open within `OWN_GRACE` (it comes back within
    /// seconds, and what was written there meanwhile waits for us).
    fn named(&self) -> bool {
        self.open.load(Ordering::SeqCst) || self.steady.load(Ordering::SeqCst) && self.opened.lock().expect("opened").is_some_and(|t| t.elapsed() < OWN_GRACE)
    }
}

/// Whether a relay's refusal of a write lasts, so it is not written to again: it takes nothing
/// from strangers (payment, a sign-in, proof of work, a web of trust) or not our kind of event.
/// Too many writes at once, a full disk or the relay's own error pass, so it only rests a little.
/// On 31 Oct 2026 our relay in Iran said «rate-limited» to phones behind a busy operator address,
/// then «Write error» when its disk filled, and each phone that heard it never wrote there again
/// until the app restarted: chats stopped though the relay was fine again in minutes. Our own
/// relays refuse nothing for good (`setup-strfry.sh`: one event, or too many at once).
fn refusal_lasts(own: bool, why: &str) -> bool {
    if own {
        return false;
    }
    let w = why.to_ascii_lowercase();
    match w.split(':').next().unwrap_or("").trim() {
        "rate-limited" | "error" => false,
        "blocked" | "restricted" | "auth-required" | "pow" | "invalid" | "mute" => true,
        _ => !["rate", "limit", "error", "busy", "slow", "later", "timeout", "full"].iter().any(|k| w.contains(k)),
    }
}

struct Shared {
    hooks: Arc<dyn Hooks>,
    store: Arc<dyn GasStore>,
    cfg: NostrConfig,
    stop: AtomicBool,
    links: Mutex<Vec<Arc<Link>>>,
    resting: Mutex<HashMap<String, Instant>>,
    seen: Mutex<(HashSet<[u8; 32]>, VecDeque<[u8; 32]>)>,
    /// The relay the user prefers (SPEC 20.6): always read, first of all; None for automatic.
    preferred: Mutex<Option<String>>,
    /// The user's last measuring on this network (cleared when the network changes).
    measured: Mutex<HashMap<String, Measured>>,
}

pub struct NostrTransport {
    sh: Arc<Shared>,
}

const SEEN_MAX: usize = 8192;
const PING_EVERY: Duration = Duration::from_secs(30);
/// A line that sent nothing back this long (two pings unanswered) is opened again.
const DEAD_AFTER: Duration = Duration::from_secs(75);
const MAX_MESSAGE: usize = 512 * 1024;
/// A connection opened only to write closes after this long without a send.
pub const IDLE_WRITE: Duration = Duration::from_secs(300);
/// A subscription asks for events from this long before the relay's last end-of-stored-events.
const SINCE_SLACK_S: u64 = 3 * 3600;
/// A relay that refused a write for now, or answered nothing for a whole `ok_timeout`, is not
/// written to for this long; our own relay only briefly (its limits are per minute).
const WRITE_REST: Duration = Duration::from_secs(120);
const OWN_WRITE_REST: Duration = Duration::from_secs(15);
/// Our own relay that stopped is tried again after these pauses (the last one repeats), never
/// set aside: a restart of it takes seconds (13 s, 31 Oct 2026), and the 10-minute rest made
/// every phone stop reading and writing there for 10 minutes after each.
const OWN_RETRY: [Duration; 5] = [Duration::from_secs(2), Duration::from_secs(4), Duration::from_secs(8), Duration::from_secs(15), Duration::from_secs(30)];
/// Our own relay stays in our card this long after it was last open.
const OWN_GRACE: Duration = Duration::from_secs(30 * 60);

fn day_now() -> u64 {
    proto::day(now_ms())
}

fn eose_key(url: &str) -> String {
    format!("nostr.eose/{}", relay_name(url))
}

fn feed_eose_key(url: &str) -> String {
    format!("nostr.feed-eose/{}", relay_name(url))
}

/// Official items kept aside go to the node together: after the stored ones end, once none has
/// come for this long, or at this many.
const FEED_GATHER: Duration = Duration::from_millis(300);
const FEED_BATCH: usize = 100;

/// A message of the official items' subscription (SPEC 18.7).
enum FeedMsg {
    Item(SignedFeedItem),
    Stored,
    Other,
}

/// The relay has answered none of the events it was `given` (an answered one leaves its `acks`).
fn quiet(link: &Link, given: &[String]) -> bool {
    let acks = link.acks.lock().expect("acks");
    !given.is_empty() && given.iter().all(|id| acks.contains_key(id))
}

impl Shared {
    fn stopped(&self) -> bool {
        self.stop.load(Ordering::SeqCst)
    }

    /// Our tags (yesterday, today, tomorrow): the Nostr ones we subscribe to, and the sealed
    /// frame's own ones it must carry.
    fn our_tags(&self, day: u64) -> (Vec<String>, Vec<[u8; 16]>) {
        let (_, loc) = self.hooks.me();
        let days = [day.saturating_sub(1), day, day + 1];
        (days.iter().map(|d| p_tag(&loc, *d)).collect(), days.iter().map(|d| proto::tag(&loc, *d)).collect())
    }

    /// Where a subscription to `url` starts: three hours before its last end-of-stored-events,
    /// never before the oldest event a relay still keeps.
    fn since(&self, url: &str) -> u64 {
        let now_s = now_ms() / 1000;
        let oldest = now_s.saturating_sub(event::EXPIRE_S + 3600);
        let last = self.store.kv_get(&eose_key(url)).and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(u64::from_le_bytes);
        last.map_or(oldest, |e| e.saturating_sub(SINCE_SLACK_S).max(oldest))
    }

    fn own(&self, url: &str) -> bool {
        self.cfg.own.iter().any(|u| u == url)
    }

    fn is_preferred(&self, url: &str) -> bool {
        self.preferred.lock().expect("preferred").as_deref() == Some(url)
    }

    /// The list in the order relays are read from and written to: the one the user prefers, then
    /// our own, then the others in the list's order; once the user measured them on this network,
    /// the fastest that answered first and those that failed last (automatic picks the best).
    fn order(&self) -> Vec<String> {
        let preferred = self.preferred.lock().expect("preferred").clone();
        let measured = self.measured.lock().expect("measured");
        let mut rest: Vec<(usize, &String)> = self.cfg.relays.iter().enumerate().filter(|(_, u)| Some(*u) != preferred.as_ref()).collect();
        rest.sort_by_key(|(i, u)| {
            let speed = match measured.get(*u) {
                Some(Measured { ms: Some(ms), .. }) => (0, *ms),
                None => (1, 0),
                Some(_) => (2, 0),
            };
            (!self.own(u), speed, *i)
        });
        preferred.into_iter().chain(rest.into_iter().map(|(_, u)| u.clone())).collect()
    }

    /// The supervisor: the first `keep` relays of the list not at rest are read from; drop links
    /// that ended. One whose rest is over takes its place again, and the last of the list gives
    /// way once `keep` better ones are open: before, a relay back from its rest waited for one of
    /// the others to fail, so our relay in Iran, after one restart, could stay unread on a phone
    /// whose foreign relays all worked, and phones on other networks lost their one way to it.
    fn supervise(self: Arc<Self>) {
        while !self.stopped() {
            {
                let mut links = self.links.lock().expect("links");
                links.retain(|l| !l.stopped());
                let resting = self.resting.lock().expect("resting");
                let now = Instant::now();
                let order = self.order();
                let wanted: Vec<&String> = order.iter().filter(|u| !resting.get(*u).is_some_and(|until| *until > now)).take(self.cfg.keep).collect();
                let reading: HashSet<String> = links.iter().filter(|l| l.reads).map(|l| l.url.clone()).collect();
                for url in wanted.iter().filter(|u| !reading.contains(**u)) {
                    // a writing-only link to the same relay makes room for the reading one
                    for l in links.iter().filter(|l| &&l.url == url) {
                        l.stop.store(true, Ordering::SeqCst);
                    }
                    let link = Link::new(url, true, self.own(url), self.is_preferred(url));
                    links.push(link.clone());
                    let sh = self.clone();
                    let _ = std::thread::Builder::new().name("mlmchat-nostr".into()).spawn(move || sh.drive(link));
                }
                let rank = |url: &str| order.iter().position(|u| u == url).unwrap_or(usize::MAX);
                let open: Vec<usize> = links.iter().filter(|l| l.reads && !l.stopped() && l.open.load(Ordering::SeqCst)).map(|l| rank(&l.url)).collect();
                for l in links.iter().filter(|l| l.reads && !wanted.contains(&&l.url)) {
                    let r = rank(&l.url);
                    if open.iter().filter(|o| **o < r).count() >= self.cfg.keep {
                        l.stop.store(true, Ordering::SeqCst);
                    }
                }
            }
            std::thread::sleep(Duration::from_secs(2));
        }
    }

    /// One connection's life: connect, subscribe, pass messages both ways; on failure, a few
    /// tries, then the relay rests and this link ends (the supervisor starts the next relay).
    fn drive(self: Arc<Self>, link: Arc<Link>) {
        let mut failures = 0u32;
        while !self.stopped() && !link.stopped() {
            match self.connect(&link) {
                Ok(ws) => {
                    failures = 0;
                    link.fails.store(0, Ordering::SeqCst);
                    self.session(&link, ws);
                    link.open.store(false, Ordering::SeqCst);
                    *link.opened.lock().expect("opened") = Some(Instant::now());
                    // no answer comes for what that connection carried: whoever waits hears no
                    // now and goes on by another relay or route, not after `ok_timeout` (a relay
                    // that falls over, as ours did on 31 Oct 2026, held every send that long)
                    link.out.lock().expect("out").clear();
                    for (_, tx) in link.acks.lock().expect("acks").drain() {
                        let _ = tx.send(false);
                    }
                    if self.stopped() || link.stopped() {
                        break;
                    }
                    *link.state.lock().expect("state") = "connecting".into();
                    self.nap(&link, Duration::from_secs(2));
                }
                Err(stage) => {
                    failures += 1;
                    link.fails.store(failures, Ordering::SeqCst);
                    *link.state.lock().expect("state") = stage.code().into();
                    if link.steady.load(Ordering::SeqCst) && link.reads {
                        // ours (or the user's choice) is never set aside: tried again within
                        // seconds, for as long as it takes
                        if failures == 2 {
                            log::info!("nostr: a steady relay did not open ({}), trying again", stage.code());
                        }
                        self.nap(&link, OWN_RETRY[(failures as usize - 1).min(OWN_RETRY.len() - 1)]);
                        continue;
                    }
                    if failures >= 2 {
                        // (a writing-only link to a steady one just ends: its reading one goes on trying)
                        if !link.steady.load(Ordering::SeqCst) {
                            log::info!("nostr: a relay did not open here ({}), resting", stage.code());
                            self.resting.lock().expect("resting").insert(link.url.clone(), Instant::now() + self.cfg.rest);
                        }
                        link.stop.store(true, Ordering::SeqCst);
                        break;
                    }
                    self.nap(&link, Duration::from_secs(3));
                }
            }
        }
        link.stop.store(true, Ordering::SeqCst);
        // whoever waits for an OK from this relay hears no
        for (_, tx) in link.acks.lock().expect("acks").drain() {
            let _ = tx.send(false);
        }
    }

    /// Sleep `d`, or less when the link or the transport stops.
    fn nap(&self, link: &Link, d: Duration) {
        let end = Instant::now() + d;
        while Instant::now() < end && !self.stopped() && !link.stopped() {
            std::thread::sleep(end.saturating_duration_since(Instant::now()).min(Duration::from_millis(250)));
        }
    }

    /// Plainly first; if the TLS handshake fails, with the ClientHello in two records.
    fn connect(&self, link: &Link) -> Result<Ws, Stage> {
        let first = *link.split.lock().expect("split");
        match Ws::connect(&link.url, first, self.cfg.connect_timeout) {
            Ok((ws, _)) => Ok(ws),
            Err((Stage::Tls, _)) if first == Split::None && link.url.starts_with("wss://") => {
                let (ws, _) = Ws::connect(&link.url, Split::Record, self.cfg.connect_timeout).map_err(|(s, _)| s)?;
                *link.split.lock().expect("split") = Split::Record;
                Ok(ws)
            }
            Err((s, _)) => Err(s),
        }
    }

    fn session(&self, link: &Link, mut ws: Ws) {
        let mut day = day_now();
        let sub = hex::encode(random::<8>());
        let subscribe = |ws: &mut Ws, day: u64| {
            let (tags, _) = self.our_tags(day);
            ws.send_text(&json!(["REQ", sub, { "kinds": [event::KIND], "#p": tags, "since": self.since(&link.url) }]).to_string())
        };
        link.resub.store(false, Ordering::SeqCst);
        if link.reads && subscribe(&mut ws, day).is_err() {
            return;
        }
        // on our own relays, the official chats' items too, as they come (SPEC 18.7, T77)
        let feed_sub = if link.reads { self.feed_sub(&link.url) } else { None };
        if let Some((fsub, filter)) = &feed_sub {
            if ws.send_text(&json!(["REQ", fsub, filter]).to_string()).is_err() {
                return;
            }
        }
        let mut feed_in: Vec<SignedFeedItem> = Vec::new();
        let mut feed_stored = false;
        let mut feed_at = Instant::now();
        link.open.store(true, Ordering::SeqCst);
        *link.opened.lock().expect("opened") = Some(Instant::now());
        *link.state.lock().expect("state") = "open".into();
        ws.set_read_timeout(Duration::from_millis(200));
        let mut pinged = Instant::now();
        while !self.stopped() && !link.stopped() && !link.resub.load(Ordering::SeqCst) {
            if !feed_in.is_empty() && (feed_stored && feed_at.elapsed() >= FEED_GATHER || feed_in.len() >= FEED_BATCH) {
                self.hooks.on_feed_items(std::mem::take(&mut feed_in));
            }
            // what waits to go out
            let out: Vec<String> = link.out.lock().expect("out").drain(..).collect();
            for m in out {
                if ws.send_text(&m).is_err() {
                    return;
                }
            }
            if link.reads && day_now() != day {
                day = day_now();
                if subscribe(&mut ws, day).is_err() {
                    return;
                }
            }
            if !link.reads && link.used.lock().expect("used").elapsed() >= IDLE_WRITE && link.acks.lock().expect("acks").is_empty() {
                link.stop.store(true, Ordering::SeqCst);
                break;
            }
            if pinged.elapsed() >= PING_EVERY {
                pinged = Instant::now();
                if ws.ping().is_err() {
                    return;
                }
            }
            // nothing back, not even a pong, for two pings and more: the line is dead though writes
            // still «go» (a NAT or a filter dropped it). Open it again now, from where it stopped:
            // phones heard nothing until their own send noticed (users, 30 Oct 2026)
            if ws.last_rx.elapsed() >= DEAD_AFTER {
                log::info!("nostr: no answer for {} s, opening the line again", ws.last_rx.elapsed().as_secs());
                return;
            }
            match ws.recv_text(MAX_MESSAGE) {
                Ok(m) => match feed_sub.as_ref().map(|(fsub, _)| self.feed_message(link, fsub, &m)) {
                    Some(FeedMsg::Item(s)) => {
                        feed_in.push(s);
                        feed_at = Instant::now();
                    }
                    Some(FeedMsg::Stored) => {
                        // every stored item is here: they go to the node at once
                        feed_stored = true;
                        if !feed_in.is_empty() {
                            self.hooks.on_feed_items(std::mem::take(&mut feed_in));
                        }
                    }
                    _ => self.on_message(link, &m),
                },
                Err(e) if matches!(e.kind(), std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock) => {}
                Err(_) => return,
            }
        }
        if !feed_in.is_empty() {
            self.hooks.on_feed_items(feed_in);
        }
        ws.close();
    }

    /// The subscription to the official items on `url`, if it is one of our own relays: an id and
    /// its filter, from shortly before the end of what it last had stored (SPEC 18.7).
    fn feed_sub(&self, url: &str) -> Option<(String, Value)> {
        let (feeds, ours) = self.hooks.feed_subscription();
        if feeds.is_empty() || !ours.iter().any(|u| u == url) {
            return None;
        }
        let now_s = now_ms() / 1000;
        let first = now_s.saturating_sub(feedrelay::FIRST_S);
        let last = self.store.kv_get(&feed_eose_key(url)).and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(u64::from_le_bytes);
        let since = last.map_or(first, |e| e.saturating_sub(SINCE_SLACK_S).max(first));
        Some((hex::encode(random::<8>()), feedrelay::filter(&feeds, since)))
    }

    /// What a message of the official items' subscription `fsub` is; `Other` for any other message.
    fn feed_message(&self, link: &Link, fsub: &str, text: &str) -> FeedMsg {
        // only a message that names the subscription is read here
        if !text.contains(fsub) {
            return FeedMsg::Other;
        }
        let Ok(Value::Array(a)) = serde_json::from_str::<Value>(text) else { return FeedMsg::Other };
        if a.get(1).and_then(Value::as_str) != Some(fsub) {
            return FeedMsg::Other;
        }
        match a.first().and_then(Value::as_str) {
            Some("EVENT") => {
                link.counts.lock().expect("counts").0 += 1;
                match a.get(2).and_then(feedrelay::open) {
                    Some(s) => FeedMsg::Item(s),
                    None => FeedMsg::Other,
                }
            }
            Some("EOSE") => {
                self.store.kv_put(&feed_eose_key(&link.url), &(now_ms() / 1000).to_le_bytes());
                FeedMsg::Stored
            }
            Some("CLOSED") => {
                log::info!("nostr: a relay closed the official items' subscription");
                link.resub.store(true, Ordering::SeqCst);
                FeedMsg::Other
            }
            _ => FeedMsg::Other,
        }
    }

    fn on_message(&self, link: &Link, text: &str) {
        let Ok(Value::Array(a)) = serde_json::from_str::<Value>(text) else { return };
        match a.first().and_then(Value::as_str) {
            Some("EVENT") if link.reads => {
                if let Some(ev) = a.get(2) {
                    link.counts.lock().expect("counts").0 += 1;
                    self.deliver(ev);
                }
            }
            Some("EOSE") if link.reads => {
                // all that was stored is here: the next subscription to this relay starts near now
                self.store.kv_put(&eose_key(&link.url), &(now_ms() / 1000).to_le_bytes());
            }
            Some("OK") => {
                let (Some(id), ok) = (a.get(1).and_then(Value::as_str), a.get(2).and_then(Value::as_bool).unwrap_or(false)) else { return };
                let why = a.get(3).and_then(Value::as_str).unwrap_or("");
                if !ok && !why.starts_with("duplicate") {
                    let reason = why.split(':').next().unwrap_or("");
                    if refusal_lasts(link.own, why) {
                        // a relay that takes no writes from strangers (pay, sign-in, proof of work)
                        log::info!("nostr: a relay refused a write ({reason}), not written to again");
                        link.writes.store(false, Ordering::SeqCst);
                    } else {
                        log::info!("nostr: a relay refused a write for now ({reason}), resting its writes");
                        link.rest_writes();
                    }
                }
                if let Some(tx) = link.acks.lock().expect("acks").remove(id) {
                    let _ = tx.send(ok || why.starts_with("duplicate"));
                }
            }
            Some("CLOSED") if link.reads => {
                // the relay ended our subscription: a new connection subscribes again
                log::info!("nostr: a relay closed our subscription");
                link.resub.store(true, Ordering::SeqCst);
            }
            _ => {}
        }
    }

    /// An event for us: checked, unsealed, given to the node (parts joined first). One the node
    /// could not take in now is forgotten here, so its copy from another relay (or the next
    /// subscription) brings it again.
    fn deliver(&self, ev: &Value) {
        let Some((id, sealed)) = event::open(ev) else { return };
        {
            let mut seen = self.seen.lock().expect("seen");
            if !seen.0.insert(id) {
                return;
            }
            seen.1.push_back(id);
            if seen.1.len() > SEEN_MAX {
                if let Some(old) = seen.1.pop_front() {
                    seen.0.remove(&old);
                }
            }
        }
        let verdict = self.take(&sealed);
        if verdict == Verdict::Retry {
            self.seen.lock().expect("seen").0.remove(&id);
        }
    }

    fn take(&self, sealed: &[u8]) -> Verdict {
        let Some(op) = self.hooks.open_sealed(sealed) else { return Verdict::Dropped };
        let (_, tags) = self.our_tags(day_now());
        if !tags.contains(&op.tag) || !self.hooks.accept_peer(&op.from_sk) {
            return Verdict::Dropped;
        }
        let from = op.from_sk;
        let Ok(f) = frame::decode(&op.frame) else { return Verdict::Dropped };
        let verdict = if f.kind == frame::KIND_PART {
            let Some(part) = frame::part_of(&f) else { return Verdict::Dropped };
            let Some(all) = self.store.part_add(&from, &part, now_ms()) else { return Verdict::Stored };
            let Ok(whole) = frame::join(&all) else { return Verdict::Dropped };
            let verdict = self.hooks.on_frame(from, whole);
            if verdict == Verdict::Retry {
                // not taken in now: the other parts are kept again, and this one comes back
                for (i, data) in all.into_iter().enumerate().filter(|(i, _)| *i != part.index as usize) {
                    self.store.part_add(&from, &frame::Part { id: part.id, index: i as u8, count: part.count, data }, now_ms());
                }
            }
            verdict
        } else {
            self.hooks.on_frame(from, op.frame)
        };
        if verdict == Verdict::Stored {
            self.hooks.on_mailbox(from, true);
        }
        verdict
    }
}

impl NostrTransport {
    pub fn start(cfg: NostrConfig, hooks: Arc<dyn Hooks>, store: Arc<dyn GasStore>) -> Arc<NostrTransport> {
        let sh = Arc::new(Shared {
            hooks,
            store,
            cfg,
            stop: AtomicBool::new(false),
            links: Mutex::new(Vec::new()),
            resting: Mutex::new(HashMap::new()),
            seen: Mutex::new((HashSet::new(), VecDeque::new())),
            preferred: Mutex::new(None),
            measured: Mutex::new(HashMap::new()),
        });
        let s = sh.clone();
        let _ = std::thread::Builder::new().name("mlmchat-nostr-super".into()).spawn(move || s.supervise());
        Arc::new(NostrTransport { sh })
    }

    pub fn relays(&self) -> Vec<RelayView> {
        let links = self.sh.links.lock().expect("links");
        let resting = self.sh.resting.lock().expect("resting");
        let mut out: Vec<RelayView> = links
            .iter()
            .filter(|l| !l.stopped())
            .map(|l| {
                let (i, o) = *l.counts.lock().expect("counts");
                RelayView { relay: l.url.clone(), state: l.state.lock().expect("state").clone(), way: l.split.lock().expect("split").label(), reads: l.reads, writes: l.writable(), events_in: i, events_out: o }
            })
            .collect();
        for url in resting.iter().filter(|(_, u)| **u > Instant::now()).map(|(url, _)| url) {
            if !out.iter().any(|v| &v.relay == url) {
                out.push(RelayView { relay: url.clone(), state: "resting".into(), way: "plain", reads: false, writes: false, events_in: 0, events_out: 0 });
            }
        }
        out
    }

    /// The relays we read from right now (open and subscribed; our own also while it comes back
    /// from a short outage), names without `wss://`, in the list's order: what our card says
    /// (key 13).
    pub fn reading(&self) -> Vec<String> {
        let order = self.sh.order();
        let links = self.sh.links.lock().expect("links");
        order
            .iter()
            .filter(|u| links.iter().any(|l| &l.url == *u && l.reads && !l.stopped() && l.named()))
            .map(|u| relay_name(u).to_string())
            .collect()
    }

    /// Every relay of the list for the picker, in the order they are used (SPEC 20.6).
    pub fn choices(&self) -> Vec<RelayChoice> {
        let order = self.sh.order();
        let preferred = self.sh.preferred.lock().expect("preferred").clone();
        let measured = self.sh.measured.lock().expect("measured").clone();
        let links = self.sh.links.lock().expect("links");
        let resting = self.sh.resting.lock().expect("resting");
        order
            .iter()
            .map(|u| {
                let live: Vec<&Arc<Link>> = links.iter().filter(|l| &l.url == u && !l.stopped()).collect();
                let l = live.iter().find(|l| l.reads).or(live.first());
                let state = match l {
                    Some(l) => l.state.lock().expect("state").clone(),
                    None if resting.get(u).is_some_and(|until| *until > Instant::now()) => "resting".into(),
                    None => "idle".into(),
                };
                let m = measured.get(u);
                RelayChoice {
                    relay: u.clone(),
                    name: relay_name(u).to_string(),
                    own: self.sh.own(u),
                    preferred: preferred.as_ref() == Some(u),
                    state,
                    reads: l.is_some_and(|l| l.reads),
                    writes: l.is_none_or(|l| l.writable()),
                    ms: m.and_then(|m| m.ms),
                    failed: m.and_then(|m| m.failed).map(String::from),
                    way: m.map_or("", |m| m.way),
                    measured_ms: m.map_or(0, |m| m.at_ms),
                }
            })
            .collect()
    }

    /// Measure every relay of the list now, all at once (the user's tap; never by itself): the
    /// whole path a mailbox needs, plainly and, if the TLS handshake fails, with the ClientHello
    /// in two records. Automatic then reads from the fastest. Blocks up to about twice `timeout`.
    pub fn measure(&self, timeout: Duration) -> Vec<RelayChoice> {
        let handles: Vec<_> = self
            .sh
            .cfg
            .relays
            .iter()
            .cloned()
            .map(|url| {
                std::thread::spawn(move || {
                    let mut p = super::probe(&url, Split::None, timeout);
                    if p.failed == Some(Stage::Tls) && url.starts_with("wss://") {
                        p = super::probe(&url, Split::Record, timeout);
                    }
                    p
                })
            })
            .collect();
        let now = now_ms();
        let found: Vec<super::Probe> = handles.into_iter().filter_map(|h| h.join().ok()).collect();
        {
            let mut measured = self.sh.measured.lock().expect("measured");
            for p in found {
                let m = Measured { ms: p.failed.is_none().then_some(p.ms[4]), failed: p.failed.map(|s| s.code()), way: p.split.label(), at_ms: now };
                measured.insert(p.relay, m);
            }
        }
        log::info!("nostr: relays measured");
        self.choices()
    }

    /// The relay the user prefers (SPEC 20.6; Ehsan, 31 Oct 2026): it is always read and comes
    /// first of all, in our card and for what is written; the others stay as its backup, so a
    /// chat never stops on it. None (or a relay not of the list) for automatic.
    pub fn set_preferred(&self, url: Option<String>) {
        let url = url.filter(|u| self.sh.cfg.relays.contains(u));
        let old = std::mem::replace(&mut *self.sh.preferred.lock().expect("preferred"), url.clone());
        if old == url {
            return;
        }
        if let Some(u) = &url {
            // no rest for it: the supervisor opens it at once
            self.sh.resting.lock().expect("resting").remove(u);
        }
        for l in self.sh.links.lock().expect("links").iter() {
            if Some(&l.url) == url.as_ref() {
                l.steady.store(true, Ordering::SeqCst);
            } else if Some(&l.url) == old.as_ref() {
                l.steady.store(l.own, Ordering::SeqCst);
            }
        }
    }

    /// The network changed: every connection opens again, and rested relays get another chance.
    pub fn network_changed(&self) {
        self.sh.resting.lock().expect("resting").clear();
        // what was measured holds for the network it was measured on
        self.sh.measured.lock().expect("measured").clear();
        for l in self.sh.links.lock().expect("links").iter() {
            l.stop.store(true, Ordering::SeqCst);
        }
    }

    /// One frame's parts to the relays `group` of `links` (`to`: our sk, the contact, its day tag
    /// and its Nostr tag, the time), each relay answering a part on that part's one channel. True
    /// when each part has an OK.
    fn write_frame(&self, links: &[Arc<Link>], group: &[usize], pieces: &[Vec<u8>], to: (&crate::UserId, &PeerInfo, &[u8; 16], &str, u64), given: &mut [Vec<String>]) -> Result<bool, TransportError> {
        let (me, peer, tag, p, now) = to;
        let mut waits: Vec<Receiver<bool>> = Vec::new();
        for piece in pieces {
            let sealed = frame::seal(piece, me, &peer.hk, tag).map_err(|_| TransportError::NoRoute)?;
            let ev = event::make(&sealed, p, now / 1000);
            let id = hex::encode(ev.id);
            let msg = json!(["EVENT", ev.json]).to_string();
            let (tx, rx) = channel();
            for &i in group {
                let l = &links[i];
                l.acks.lock().expect("acks").insert(id.clone(), tx.clone());
                l.out.lock().expect("out").push_back(msg.clone());
                l.counts.lock().expect("counts").1 += 1;
                *l.used.lock().expect("used") = Instant::now();
                given[i].push(id.clone());
            }
            waits.push(rx);
        }
        // The first OK of each piece is enough. Waiting for every relay's answer, a relay that
        // takes the connection and never answers (a filter that drops what comes back) held
        // every frame `ok_timeout`, and a phone's receipts queued behind each other for
        // minutes (28 Oct 2026: Ehsan's messages stayed at one tick on his phone).
        let end = Instant::now() + self.sh.cfg.ok_timeout;
        let all_pieces = waits.iter().all(|rx| {
            // each relay answers a piece once: OK, a refusal, or no when its connection ends
            for _ in 0..group.len() {
                match rx.recv_timeout(end.saturating_duration_since(Instant::now())) {
                    Ok(true) => return true,
                    Ok(false) => {}
                    Err(_) => return false,
                }
            }
            false
        });
        if !all_pieces {
            // one that answered nothing in all that time rests its writes: the next send does not
            // wait for it again
            for &i in group.iter().filter(|&&i| quiet(&links[i], &given[i])) {
                links[i].rest_writes();
            }
        }
        Ok(all_pieces)
    }

    /// Links to the relays `urls`: one already there, else a new one only for writing (not for a
    /// relay at rest). Waits up to the connect timeout for them to open; gives those that did.
    fn links_to(&self, urls: &[String]) -> Vec<Arc<Link>> {
        let sh = &self.sh;
        let wanted: Vec<Arc<Link>> = {
            let mut links = sh.links.lock().expect("links");
            let resting = sh.resting.lock().expect("resting");
            let mut out = Vec::new();
            for url in urls {
                if let Some(l) = links.iter().find(|l| &l.url == url && !l.stopped()) {
                    out.push(l.clone());
                } else if !resting.get(url).is_some_and(|until| *until > Instant::now()) {
                    let link = Link::new(url, false, sh.own(url), sh.is_preferred(url));
                    links.push(link.clone());
                    let s = sh.clone();
                    let l = link.clone();
                    let _ = std::thread::Builder::new().name("mlmchat-nostr-w".into()).spawn(move || s.drive(l));
                    out.push(link);
                }
            }
            out
        };
        for l in &wanted {
            *l.used.lock().expect("used") = Instant::now();
        }
        // (not for one that is failing: our own relay keeps trying, and a send that waited for it
        // held its contact's frames up the whole connect timeout each time)
        let end = Instant::now() + sh.cfg.connect_timeout;
        while Instant::now() < end && wanted.iter().any(|l| !l.stopped() && !l.open.load(Ordering::SeqCst) && l.fails.load(Ordering::SeqCst) == 0) {
            std::thread::sleep(Duration::from_millis(50));
        }
        wanted.into_iter().filter(|l| !l.stopped() && l.open.load(Ordering::SeqCst)).collect()
    }
}

impl Transport for NostrTransport {
    fn name(&self) -> &'static str {
        "nostr"
    }

    fn send(&self, peer: &PeerInfo, frames: &[Vec<u8>]) -> Result<Sent, TransportError> {
        let sh = &self.sh;
        if frames.is_empty() || peer.nostr.is_empty() || peer.hk == [0; 32] {
            return Err(TransportError::NoRoute);
        }
        // only relays of our own list: a card names relays, it never makes us connect anywhere
        // (in our order: the preferred one first, so a file's chunk goes there)
        let urls: Vec<String> = sh.order().into_iter().filter(|u| peer.nostr.iter().any(|n| n == relay_name(u))).collect();
        if urls.is_empty() {
            return Err(TransportError::NoRoute);
        }
        let links = self.links_to(&urls);
        if !links.iter().any(|l| l.writable()) {
            return Err(TransportError::Connect);
        }
        let (me, _) = sh.hooks.me();
        let now = now_ms();
        let day = proto::day(now);
        let tag = proto::tag(&peer.loc, day);
        let p = p_tag(&peer.loc, day);
        let mut verdicts = Vec::new();
        // the events each relay was given in this send
        let mut given: Vec<Vec<String>> = vec![Vec::new(); links.len()];
        let takes = |i: usize, given: &[Vec<String>]| links[i].writable() && !links[i].stopped() && !quiet(&links[i], &given[i]);
        for f in frames {
            // a relay that refused a write is left out from the next frame on, and so is one that
            // has answered nothing it was given in this send (it never answers, or too slowly to
            // matter now): nothing waits for it
            let writing: Vec<usize> = (0..links.len()).filter(|&i| takes(i, &given)).collect();
            if writing.is_empty() {
                break;
            }
            let pieces = if f.len() <= MAX_WHOLE { vec![f.clone()] } else { frame::split(f, random::<8>()).map_err(|_| TransportError::NoRoute)? };
            // A frame of several parts (a file's chunk) goes to one relay, the first of the list,
            // and to the next only if that one fails: every relay more is the whole file again on
            // the phone's data, and a card names up to 4. Any other frame goes to all at once.
            let groups: Vec<Vec<usize>> = if pieces.len() > 1 { writing.iter().map(|&i| vec![i]).collect() } else { vec![writing] };
            let mut went = false;
            for group in groups {
                let group: Vec<usize> = group.into_iter().filter(|&i| takes(i, &given)).collect();
                if !group.is_empty() && self.write_frame(&links, &group, &pieces, (&me, peer, &tag, &p, now), &mut given)? {
                    went = true;
                    break;
                }
            }
            if !went {
                break;
            }
            verdicts.push(Verdict::Stored);
        }
        // nothing waits for the answers still missing (one that comes later finds no one)
        for (l, ids) in links.iter().zip(&given) {
            let mut acks = l.acks.lock().expect("acks");
            for id in ids {
                acks.remove(id);
            }
        }
        if verdicts.is_empty() {
            return Err(TransportError::Timeout);
        }
        log::info!("nostr: {} frames written to relays", verdicts.len());
        sh.hooks.on_mailbox(peer.sk, false);
        Ok(Sent { verdicts, via: "nostr" })
    }

    fn advertised_addrs(&self) -> Vec<SocketAddr> {
        Vec::new()
    }

    fn stop(&self) {
        self.sh.stop.store(true, Ordering::SeqCst);
        for l in self.sh.links.lock().expect("links").iter() {
            l.stop.store(true, Ordering::SeqCst);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::refusal_lasts;

    #[test]
    fn only_a_refusal_for_good_sets_a_relay_aside() {
        // for good: payment, a sign-in, proof of work, a web of trust, not our kind
        for why in ["restricted: sign in first", "blocked: kind 1059 not allowed", "auth-required: log in", "pow: difficulty 20", "invalid: bad tags", "Policy violated and pubkey is not in our web of trust.", "blocked kind 1059 reason"] {
            assert!(refusal_lasts(false, why), "{why}");
        }
        // for now: too many at once, a full disk, the relay's own error
        for why in ["rate-limited: slow down", "rate-limited: too many files today", "error: database busy", "Write error", "too many requests, try later", "storage full"] {
            assert!(!refusal_lasts(false, why), "{why}");
        }
        // ours never refuses for good
        for why in ["blocked: bad file part", "restricted: sign in first", "rate-limited: slow down"] {
            assert!(!refusal_lasts(true, why), "{why}");
        }
    }
}
