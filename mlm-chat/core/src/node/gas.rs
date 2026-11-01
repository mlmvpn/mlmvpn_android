//! The node's side of the Google mailbox relays (SPEC 19): the user's list (make one, add a
//! friend's, switch on or off, remove), what each did, the cards that name them, and the glue to
//! the transport (`transport::gas`).

use super::{Inner, Node};
use crate::store::{Db, GasRelayRow};
use crate::transport::gas::proto::{self, RelayRef};
use crate::transport::gas::{CallError, GasConfig, GasRelay, GasStore, GasTransport, Health, Pending, StatDelta, TestReport};
use crate::transport::Transport;
use crate::util::{now_ms, random};
use crate::{Error, Result};
use serde_json::Value;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Weak};

/// The most relays the list holds.
pub const MAX_RELAYS_LISTED: usize = 12;
const STATS_KEEP_DAYS: u64 = 60;
const META_CLIENT: &str = "gas_client";

/// Counters of one relay over some days, as the screens show them.
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Serialize)]
pub struct GasDay {
    pub req: u64,
    pub puts: u64,
    pub takes: u64,
    pub items_out: u64,
    pub items_in: u64,
    pub bytes_out: u64,
    pub bytes_in: u64,
    pub errors: u64,
    /// Average round trip, ms.
    pub ms: u64,
    #[serde(skip)]
    n: u64,
}

impl GasDay {
    fn add(&mut self, d: &StatDelta) {
        self.req += d.req;
        self.puts += d.puts;
        self.takes += d.takes;
        self.items_out += d.items_out;
        self.items_in += d.items_in;
        self.bytes_out += d.bytes_out;
        self.bytes_in += d.bytes_in;
        self.errors += d.errors;
        self.ms += d.ms_sum;
        self.n += d.ms_n;
    }
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct GasRelayView {
    pub rid: String,
    pub name: String,
    pub mine: bool,
    /// Keys made and the script shown, but no deployment pasted back yet.
    pub draft: bool,
    pub enabled: bool,
    pub announce: bool,
    /// `draft`, `off`, `new` (not tried yet), `ok`, or the last failure's `CallError::code()`.
    pub state: String,
    pub last_ok_ms: u64,
    /// The last round trip.
    pub ms: u64,
    pub version: u32,
    pub today: GasDay,
    pub week: GasDay,
    pub total: GasDay,
    /// Frames of ours waiting in this mailbox for their recipients.
    pub waiting: usize,
    /// The last statistics read from the script itself (the owner's view), if any: `{at, data}`.
    pub server: Option<Value>,
    /// The last characters of the deployment id, to tell relays apart.
    pub tail: String,
    /// One of ours that we opened to everyone (SPEC 19.12).
    pub public: bool,
    /// A public relay of someone else's that we borrowed, having none of our own.
    pub borrowed: bool,
    /// A combined relay (SPEC 19.13): a Cloudflare Worker with a Google door.
    pub combined: bool,
    /// Its Worker's host, empty until the Worker is made.
    pub worker: String,
    /// The door the last call went through on this network: `cloudflare`, `google`, or empty.
    pub door: String,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct GasOverview {
    pub relays: Vec<GasRelayView>,
    /// The way to Google found on this network (`ip · name`), empty if none yet.
    pub front: String,
    pub front_ms: u64,
    /// Relays named in our cards.
    pub announced: Vec<String>,
    pub running: bool,
    pub waiting: usize,
    /// Public relays of others this phone knows of.
    pub public_known: usize,
    /// Whether we borrow public relays while we have none of our own.
    pub borrow_auto: bool,
}

/// A relay just made: the script to paste into script.google.com.
#[derive(Debug, Clone, serde::Serialize)]
pub struct GasDraft {
    pub rid: String,
    pub script: String,
}

/// A combined relay just made (SPEC 19.13): its Worker, for the app to put on the owner's
/// Cloudflare account. The Google door's script comes once the Worker's address is known.
#[derive(Debug, Clone, serde::Serialize)]
pub struct GasComboDraft {
    pub rid: String,
    pub worker_script: String,
}

/// The database side of the transport.
pub(super) struct NodeGasStore(pub(super) Weak<Inner>);

impl NodeGasStore {
    fn with<T>(&self, default: T, f: impl FnOnce(&Db) -> T) -> T {
        match self.0.upgrade() {
            Some(i) => f(&i.state.lock().expect("state").store.db()),
            None => default,
        }
    }
}

pub(super) fn to_relay(r: &GasRelayRow) -> Option<GasRelay> {
    // a draft, or a combined relay whose Worker is not made yet
    if r.id.is_empty() || r.worker.as_deref() == Some("") {
        return None;
    }
    Some(GasRelay { rid: r.rid.clone(), relay: r.relay_ref(), owner_key: r.owner_key, name: r.name.clone(), enabled: r.enabled, announce: r.announce })
}

impl GasStore for NodeGasStore {
    fn part_add(&self, from: &crate::UserId, part: &crate::frame::Part, now_ms: u64) -> Option<Vec<Vec<u8>>> {
        self.with(None, |db| db.gas_part_add(from, part, now_ms, crate::transport::gas::PART_KEEP_MS, crate::transport::gas::MAX_PARTS).ok().flatten())
    }

    fn relays(&self) -> Vec<GasRelay> {
        self.with(Vec::new(), |db| db.gas_relays().unwrap_or_default().iter().filter_map(to_relay).collect())
    }

    fn client_secret(&self) -> [u8; 32] {
        self.with([0; 32], |db| match db.meta(META_CLIENT).ok().flatten().and_then(|v| crate::util::array::<32>(&v)) {
            Some(s) => s,
            None => {
                let s: [u8; 32] = random();
                let _ = db.set_meta(META_CLIENT, &s);
                s
            }
        })
    }

    fn add_stats(&self, rid: &str, day: u64, d: &StatDelta) {
        self.with((), |db| {
            let _ = db.gas_stats_add(rid, day, d);
        })
    }

    fn pending_add(&self, p: &Pending) {
        self.with((), |db| {
            let _ = db.gas_pending_add(p);
        })
    }

    fn pending_due(&self, now_ms: u64, limit: usize) -> Vec<Pending> {
        self.with(Vec::new(), |db| db.gas_pending_due(now_ms, limit).unwrap_or_default())
    }

    fn pending_update(&self, id: &[u8; 16], next_ms: u64, tries: u32) {
        self.with((), |db| {
            let _ = db.gas_pending_update(id, next_ms, tries);
        })
    }

    fn pending_done(&self, id: &[u8; 16]) {
        self.with((), |db| {
            let _ = db.gas_pending_done(id);
        })
    }

    fn pending_count(&self) -> usize {
        self.with(0, |db| db.gas_pending_count().unwrap_or(0))
    }

    fn front_get(&self, network: &str) -> Option<crate::transport::gas::http::Endpoint> {
        let v = self.with(None, |db| db.meta(&front_key(network)).ok().flatten())?;
        let text = String::from_utf8(v).ok()?;
        let (ip, sni) = text.split_once('|')?;
        Some(crate::transport::gas::http::Endpoint::Front { ip: ip.parse().ok()?, sni: sni.to_string() })
    }

    fn front_put(&self, network: &str, front: &crate::transport::gas::http::Endpoint) {
        if let crate::transport::gas::http::Endpoint::Front { ip, sni } = front {
            self.with((), |db| {
                let _ = db.set_meta(&front_key(network), format!("{ip}|{sni}").as_bytes());
            });
        }
    }

    fn kv_get(&self, key: &str) -> Option<Vec<u8>> {
        self.with(None, |db| db.meta(&format!("kv/{key}")).ok().flatten())
    }

    fn kv_put(&self, key: &str, value: &[u8]) {
        self.with((), |db| {
            let _ = db.set_meta(&format!("kv/{key}"), value);
        });
    }
}

/// meta key of the way to Google that last worked on a network.
fn front_key(network: &str) -> String {
    format!("gas_front:{network}")
}

/// The relays a card names: complete, switched on, announced; the first `MAX_RELAYS` simple ones
/// and the first `MAX_RELAYS2` combined ones (SPEC 19.13), leaving out those that are known to be
/// dead (the id is wrong, the key is wrong, or it is closed).
pub(super) fn card_relays(rows: &[GasRelayRow], health: Option<&std::collections::HashMap<String, Health>>) -> Vec<Vec<u8>> {
    let alive: Vec<&GasRelayRow> = rows
        .iter()
        .filter(|r| to_relay(r).is_some() && r.enabled && r.announce)
        .filter(|r| health.and_then(|h| h.get(&r.id)).is_none_or(|h| !matches!(h.state, "not_found" | "access" | "auth")))
        .collect();
    let combined = alive.iter().filter(|r| r.worker.is_some()).take(crate::card::MAX_RELAYS2);
    let simple = alive.iter().filter(|r| r.worker.is_none()).take(crate::card::MAX_RELAYS);
    combined.chain(simple).map(|r| r.relay_ref().to_bytes()).collect()
}

impl Inner {
    /// Our cards name these relays from now on.
    pub(super) fn sync_card_relays(&self) {
        let rows = self.state.lock().expect("state").store.db().gas_relays().unwrap_or_default();
        let health = self.gas.lock().expect("gas").as_ref().map(|g| g.health());
        let refs = card_relays(&rows, health.as_ref());
        self.state.lock().expect("state").identity.set_relays(refs);
    }

    pub(super) fn gas_changed(&self) {
        self.sync_card_relays();
        self.gas_dirty.store(true, Ordering::SeqCst);
        if let Some(g) = self.gas.lock().expect("gas").as_ref() {
            g.refresh();
        }
        self.waker.wake();
    }
}

impl Node {
    /// Start the mailbox transport: the relays of the list are taken from, and contacts' relays
    /// are put into when nothing faster reaches them. A no-op when already running.
    pub fn start_gas(&self, cfg: GasConfig) -> Result<Arc<GasTransport>> {
        if let Some(g) = self.inner.gas.lock().expect("gas").as_ref() {
            return Ok(g.clone());
        }
        let store = Arc::new(NodeGasStore(Arc::downgrade(&self.inner)));
        let t = GasTransport::start(cfg, self.hooks(), store);
        *self.inner.gas.lock().expect("gas") = Some(t.clone());
        {
            let st = self.inner.state.lock().expect("state");
            let _ = st.store.db().gas_prune(proto::day(now_ms()).saturating_sub(STATS_KEEP_DAYS));
        }
        self.start_with(t.clone());
        self.inner.gas_changed();
        // public relays (`public.rs`): renewing ours, borrowing while we have none
        let weak = Arc::downgrade(&self.inner);
        let _ = std::thread::Builder::new().name("mlmchat-public".into()).spawn(move || {
            let mut wait = super::public::FIRST_TICK;
            loop {
                let started = std::time::Instant::now();
                while started.elapsed() < wait {
                    std::thread::sleep(std::time::Duration::from_secs(1));
                    match weak.upgrade() {
                        Some(i) if !i.stop.load(Ordering::SeqCst) && i.gas.lock().expect("gas").is_some() => {}
                        _ => return,
                    }
                }
                match weak.upgrade() {
                    Some(i) => i.public_tick(),
                    None => return,
                }
                wait = super::public::TICK;
            }
        });
        // the official feeds' copy (`mirror.rs`): soon after the start, then by the clock (more
        // often while the app is in front), every few seconds while an official chat is on the
        // screen, and when an official chat opens or an item of ours is made; after a failed round,
        // not before a wait that grows with each failure
        let weak = Arc::downgrade(&self.inner);
        let _ = std::thread::Builder::new().name("mlmchat-mirror".into()).spawn(move || {
            use super::mirror::{retry_after, MIRROR_EVERY_MS, MIRROR_FIRST_MS, MIRROR_FRONT_MS};
            let mut not_before = now_ms() + MIRROR_FIRST_MS;
            let mut fails: u32 = 0;
            loop {
                std::thread::sleep(std::time::Duration::from_millis(500));
                let Some(i) = weak.upgrade() else { return };
                if i.stop.load(Ordering::SeqCst) || i.gas.lock().expect("gas").is_none() {
                    return;
                }
                let now = now_ms();
                if now < not_before {
                    continue;
                }
                let every = if i.in_front.load(Ordering::SeqCst) { MIRROR_FRONT_MS } else { MIRROR_EVERY_MS };
                let last = i.mirror_last.load(Ordering::SeqCst);
                let by_clock = last == 0 || now.saturating_sub(last) >= every;
                if !by_clock && i.mirror_due.load(Ordering::SeqCst) == 0 {
                    continue;
                }
                i.mirror_due.store(0, Ordering::SeqCst);
                if i.mirror_sync() {
                    fails = 0;
                } else {
                    fails = fails.saturating_add(1);
                    not_before = now_ms() + retry_after(fails);
                }
            }
        });
        Ok(t)
    }

    pub fn stop_gas(&self) {
        let Some(g) = self.inner.gas.lock().expect("gas").take() else { return };
        self.inner.transports.lock().expect("transport").retain(|t| t.name() != "gas");
        Transport::stop(&*g);
    }

    pub fn gas(&self) -> Option<Arc<GasTransport>> {
        self.inner.gas.lock().expect("gas").clone()
    }

    /// Write the relays' counts gathered so far (they go by themselves every 15 s).
    pub fn gas_flush_stats(&self) {
        if let Some(g) = self.gas() {
            g.flush_stats();
        }
    }

    /// The app is in use (true) or in the background: takes wait at the mailbox while it is.
    pub fn gas_foreground(&self, on: bool) {
        if let Some(g) = self.gas() {
            g.set_foreground(on);
        }
    }

    /// HTTP/2 to the Google fronts (HTTP/1.1 where a front does not offer it).
    pub fn gas_http2(&self, on: bool) {
        if let Some(g) = self.gas() {
            g.set_http2(on);
        }
    }

    fn rows(&self) -> Result<Vec<GasRelayRow>> {
        self.inner.state.lock().expect("state").store.db().gas_relays()
    }

    fn row(&self, rid: &str) -> Result<GasRelayRow> {
        self.inner.state.lock().expect("state").store.db().gas_relay(rid)?.ok_or(Error::Relay("not_found"))
    }

    fn save_row(&self, r: &GasRelayRow) -> Result<()> {
        self.inner.state.lock().expect("state").store.db().gas_relay_save(r)
    }

    /// Make a relay: two fresh keys and the script with them in it. The user pastes the script
    /// at script.google.com, deploys it and gives the deployment back (`gas_relay_finish`). The
    /// draft is kept, so leaving the screen loses nothing.
    pub fn gas_relay_new(&self, name: &str) -> Result<GasDraft> {
        let rows = self.rows()?;
        if rows.len() >= MAX_RELAYS_LISTED {
            return Err(Error::Relay("too_many"));
        }
        let (use_key, owner_key) = proto::new_keys();
        let rid = format!("d-{}", hex::encode(random::<4>()));
        let name = clean_name(name);
        self.save_row(&GasRelayRow {
            rid: rid.clone(),
            id: String::new(),
            use_key,
            owner_key: Some(owner_key),
            name,
            added_ms: now_ms(),
            enabled: true,
            announce: true,
            pos: rows.iter().map(|r| r.pos).max().unwrap_or(0) + 1,
            mine: true,
            worker: None,
        })?;
        Ok(GasDraft { rid, script: proto::script(&use_key, &owner_key) })
    }

    /// The script of a relay made here (for a draft the user came back to, or to deploy again).
    /// For a combined relay, its Google door (once its Worker is made).
    pub fn gas_relay_script(&self, rid: &str) -> Result<String> {
        let r = self.row(rid)?;
        let owner = r.owner_key.ok_or(Error::Relay("not_mine"))?;
        match r.worker.as_deref() {
            None => Ok(proto::script(&r.use_key, &owner)),
            Some("") => Err(Error::Relay("no_worker")),
            Some(host) => Ok(proto::door_script(host)),
        }
    }

    /// Make a combined relay (SPEC 19.13): two fresh keys and the Worker with them in it. The app
    /// puts the Worker on the owner's Cloudflare account and gives its address back
    /// (`gas_combo_worker`); then the Google door is deployed like a simple relay's script and its
    /// deployment given back (`gas_relay_finish`). The draft is kept at every step.
    pub fn gas_combo_new(&self, name: &str) -> Result<GasComboDraft> {
        let rows = self.rows()?;
        if rows.len() >= MAX_RELAYS_LISTED {
            return Err(Error::Relay("too_many"));
        }
        let (use_key, owner_key) = proto::new_keys();
        let rid = format!("d-{}", hex::encode(random::<4>()));
        self.save_row(&GasRelayRow {
            rid: rid.clone(),
            id: String::new(),
            use_key,
            owner_key: Some(owner_key),
            name: clean_name(name),
            added_ms: now_ms(),
            enabled: true,
            announce: true,
            pos: rows.iter().map(|r| r.pos).max().unwrap_or(0) + 1,
            mine: true,
            worker: Some(String::new()),
        })?;
        Ok(GasComboDraft { rid, worker_script: proto::worker_script(&use_key, &owner_key) })
    }

    /// The Worker of a combined relay made here, to put on the account again (an update, or another
    /// account).
    pub fn gas_combo_script(&self, rid: &str) -> Result<String> {
        let r = self.row(rid)?;
        let owner = r.owner_key.ok_or(Error::Relay("not_mine"))?;
        if r.worker.is_none() {
            return Err(Error::Relay("not_combined"));
        }
        Ok(proto::worker_script(&r.use_key, &owner))
    }

    /// The Worker of a combined relay is on Cloudflare at `host` (or its whole address): kept, and
    /// the Google door's script, with that address in it, comes back. Again later, it moves the
    /// relay to another Worker (contacts hear of it in our next card).
    pub fn gas_combo_worker(&self, rid: &str, host: &str) -> Result<String> {
        let host = proto::normalize_worker(host).ok_or(Error::Relay("bad_worker"))?;
        let mut r = self.row(rid)?;
        if r.worker.is_none() || r.owner_key.is_none() {
            return Err(Error::Relay("not_combined"));
        }
        r.worker = Some(host.clone());
        self.save_row(&r)?;
        self.inner.gas_changed();
        Ok(proto::door_script(&host))
    }

    /// The user pasted the deployment (its id, or the whole web-app address) of a draft. The
    /// relay is switched on; contacts hear of it in our next card. Returns its new `rid`. For a
    /// relay made here that is already finished, it replaces the deployment (a wrong paste fixed,
    /// or the script deployed again) and keeps the keys.
    pub fn gas_relay_finish(&self, rid: &str, deployment: &str) -> Result<String> {
        let id = proto::normalize_deployment_id(deployment).ok_or(Error::Relay("bad_id"))?;
        let r = self.row(rid)?;
        if !r.id.is_empty() && !r.mine {
            return Err(Error::Relay("not_mine"));
        }
        // a combined relay's Google door names its Worker: the Worker comes first
        if r.worker.as_deref() == Some("") {
            return Err(Error::Relay("no_worker"));
        }
        let new_rid = proto::rid_of(&id);
        if new_rid == rid {
            return Ok(new_rid);
        }
        {
            let st = self.inner.state.lock().expect("state");
            let db = st.store.db();
            if db.gas_relay_by_id(&id)?.is_some() {
                return Err(Error::Relay("duplicate"));
            }
            db.gas_relay_rename(rid, &new_rid, &id)?;
        }
        self.inner.gas_changed();
        Ok(new_rid)
    }

    /// A friend's relay, from their share link: our messages and our contacts' may go through
    /// it too. An id already in the list is not added twice (its `rid` is returned).
    pub fn gas_relay_add_share(&self, link: &str) -> Result<String> {
        let shared = proto::parse_share(link)?;
        let rows = self.rows()?;
        if let Some(r) = rows.iter().find(|r| r.id == shared.relay.id) {
            // the same door: a combined relay's Worker is taken from the link when ours has none
            if shared.relay.worker.is_some() && r.worker.is_none() && !r.mine {
                let mut r = r.clone();
                r.worker = shared.relay.worker.clone();
                self.save_row(&r)?;
                self.inner.gas_changed();
            }
            return Ok(r.rid.clone());
        }
        if rows.len() >= MAX_RELAYS_LISTED {
            return Err(Error::Relay("too_many"));
        }
        let rid = shared.relay.rid();
        self.save_row(&GasRelayRow {
            rid: rid.clone(),
            id: shared.relay.id.clone(),
            use_key: shared.relay.key,
            owner_key: None,
            name: clean_name(&shared.name),
            added_ms: now_ms(),
            enabled: true,
            announce: true,
            pos: rows.iter().map(|r| r.pos).max().unwrap_or(0) + 1,
            mine: false,
            worker: shared.relay.worker.clone(),
        })?;
        self.inner.gas_changed();
        Ok(rid)
    }

    /// A relay added by hand from its deployment id and its key (what a card names), without a
    /// share link.
    pub fn gas_relay_add_ref(&self, relay: &RelayRef, name: &str) -> Result<String> {
        let link = proto::share_link(relay, name);
        self.gas_relay_add_share(&link)
    }

    /// The relays a contact's card names, added to our list (their key of use is in the card, so
    /// this is how a contact's mailbox becomes ours too). Returns the rids now in the list.
    pub fn gas_relay_add_from_contact(&self, peer: &crate::UserId) -> Result<Vec<String>> {
        let c = self.contact(peer)?.ok_or(Error::NotFound("contact"))?;
        let card = crate::card::Card::from_bytes(&c.card)?;
        let name = super::contact_display_name(&c);
        let mut out = Vec::new();
        for raw in &card.all_relays() {
            if let Some(r) = RelayRef::from_bytes(raw) {
                out.push(self.gas_relay_add_ref(&r, &name)?);
            }
        }
        Ok(out)
    }

    /// The link to give a friend: it carries the key of use, never the owner's.
    pub fn gas_relay_share(&self, rid: &str) -> Result<String> {
        let r = self.row(rid)?;
        if r.id.is_empty() {
            return Err(Error::Relay("draft"));
        }
        Ok(proto::share_link(&r.relay_ref(), &r.name))
    }

    pub fn gas_relay_set(&self, rid: &str, enabled: Option<bool>, announce: Option<bool>, name: Option<&str>) -> Result<()> {
        let mut r = self.row(rid)?;
        if let Some(e) = enabled {
            r.enabled = e;
        }
        if let Some(a) = announce {
            r.announce = a;
        }
        if let Some(n) = name {
            r.name = clean_name(n);
        }
        self.save_row(&r)?;
        self.inner.gas_changed();
        Ok(())
    }

    pub fn gas_relay_remove(&self, rid: &str) -> Result<()> {
        self.inner.state.lock().expect("state").store.db().gas_relay_delete(rid)?;
        self.inner.gas_changed();
        Ok(())
    }

    /// Test a relay (the way to Google, the script, the key, a round trip). Blocking.
    pub fn gas_relay_test(&self, rid: &str) -> Result<TestReport> {
        let r = self.row(rid)?;
        if r.id.is_empty() {
            return Err(Error::Relay("draft"));
        }
        let g = self.gas().ok_or(Error::Relay("not_started"))?;
        Ok(g.test(&r.relay_ref(), r.owner_key.as_ref()))
    }

    /// One call to the user's own Telegram reader script (T68, SPEC 21), a script apart from the
    /// relays: `body` is its JSON request. JSON `{"r": answer, "up": bytes sent, "down": bytes
    /// received}`. Blocking.
    pub fn gas_script_call(&self, deployment: &str, body: &str, timeout_s: u64) -> Result<Value> {
        let id = deployment.trim();
        if id.len() < 20 || id.len() > 110 || !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
            return Err(Error::Relay("bad"));
        }
        let req: Value = serde_json::from_str(body).map_err(|_| Error::Relay("bad"))?;
        if !req.is_object() {
            return Err(Error::Relay("bad"));
        }
        let g = self.gas().ok_or(Error::Relay("not_started"))?;
        let t = std::time::Duration::from_secs(timeout_s.clamp(5, 120));
        let (v, up, down) = g.script_call(id, &req, t).map_err(call_error)?;
        Ok(serde_json::json!({ "r": v, "up": up, "down": down }))
    }

    /// A value the app keeps in this phone's encrypted database (`app:` keys), or None. The
    /// Telegram channels of T68 live here: which channels a user follows is theirs alone.
    pub fn app_get(&self, key: &str) -> Result<Option<Vec<u8>>> {
        let st = self.inner.state.lock().expect("state");
        Ok(st.store.db().meta(&format!("app:{key}"))?.filter(|v| !v.is_empty()))
    }

    /// Keeps a value for the app (empty = gone).
    pub fn app_put(&self, key: &str, value: &[u8]) -> Result<()> {
        let st = self.inner.state.lock().expect("state");
        st.store.db().set_meta(&format!("app:{key}"), value)
    }

    /// Read the script's own statistics (the owner's view: all requests, devices and recipients
    /// it served) and keep them to show. Blocking.
    pub fn gas_relay_server_stats(&self, rid: &str) -> Result<Value> {
        let r = self.row(rid)?;
        let owner = r.owner_key.ok_or(Error::Relay("not_mine"))?;
        let g = self.gas().ok_or(Error::Relay("not_started"))?;
        let v = g.server_stats(&r.relay_ref(), &owner).map_err(call_error)?;
        let kept = serde_json::json!({ "at": now_ms(), "data": v });
        let _ = self.inner.state.lock().expect("state").store.db().set_meta(&format!("gas_srv:{rid}"), kept.to_string().as_bytes());
        Ok(kept)
    }

    /// Pause the relay for everyone but us, or limit the devices a day (0 = no limit). Blocking.
    pub fn gas_relay_admin(&self, rid: &str, paused: Option<bool>, max_clients: Option<u32>) -> Result<Value> {
        let r = self.row(rid)?;
        let owner = r.owner_key.ok_or(Error::Relay("not_mine"))?;
        let g = self.gas().ok_or(Error::Relay("not_started"))?;
        let v = g.admin(&r.relay_ref(), &owner, paused, max_clients, None).map_err(call_error)?;
        Ok(v)
    }

    /// Frames of ours that were put in a mailbox and are not seen taken yet (all contacts).
    pub fn gas_pending_count(&self) -> usize {
        self.inner.state.lock().expect("state").store.db().gas_pending_count().unwrap_or(0)
    }

    pub fn gas_overview(&self) -> Result<GasOverview> {
        // the counters not yet written count too
        if let Some(g) = self.gas() {
            g.flush_stats();
        }
        let (rows, pending, server, opened, borrowed, public_known, borrow_auto) = {
            let st = self.inner.state.lock().expect("state");
            let db = st.store.db();
            let rows = db.gas_relays()?;
            let pending = db.gas_pending_by_relay()?;
            let server: Vec<Option<Value>> = rows
                .iter()
                .map(|r| db.meta(&format!("gas_srv:{}", r.rid)).ok().flatten().and_then(|b| serde_json::from_slice::<Value>(&b).ok()))
                .collect();
            let opened: Vec<bool> = rows.iter().map(|r| r.mine && super::public::is_open(&db, &r.rid)).collect();
            let borrowed: std::collections::HashSet<String> = db.gas_borrowed_all()?.into_iter().map(|b| b.0).collect();
            let me = st.identity.sk();
            let now = now_ms();
            let public_known = db.gas_public_all()?.iter().filter(|p| !p.withdrawn && p.until_ms > now && p.owner != me).count();
            (rows, pending, server, opened, borrowed, public_known, super::public::borrow_on(&db))
        };
        let g = self.gas();
        let health = g.as_ref().map(|g| g.health()).unwrap_or_default();
        let today = proto::day(now_ms());
        let mut views = Vec::new();
        let mut waiting_all = 0;
        for ((r, server), public) in rows.iter().zip(server).zip(opened) {
            let stats = self.inner.state.lock().expect("state").store.db().gas_stats(&r.rid, today.saturating_sub(STATS_KEEP_DAYS))?;
            let (mut d0, mut d7, mut dall) = (GasDay::default(), GasDay::default(), GasDay::default());
            for (day, d) in &stats {
                dall.add(d);
                if *day + 7 > today {
                    d7.add(d);
                }
                if *day == today {
                    d0.add(d);
                }
            }
            let key = r.relay_ref().to_bytes();
            let waiting = if r.id.is_empty() { 0 } else { pending.iter().find(|(k, _)| *k == key).map(|(_, n)| *n).unwrap_or(0) };
            waiting_all += waiting;
            let h = health.get(&r.id).cloned().unwrap_or_default();
            let state = if r.id.is_empty() || r.worker.as_deref() == Some("") {
                "draft".to_string()
            } else if !r.enabled {
                "off".to_string()
            } else {
                h.state.to_string()
            };
            views.push(GasRelayView {
                rid: r.rid.clone(),
                name: r.name.clone(),
                mine: r.mine,
                draft: r.id.is_empty() || r.worker.as_deref() == Some(""),
                enabled: r.enabled,
                announce: r.announce,
                state,
                last_ok_ms: h.last_ok_ms,
                ms: h.ms,
                version: h.version,
                today: finish(d0),
                week: finish(d7),
                total: finish(dall),
                waiting,
                server,
                tail: r.id.chars().rev().take(6).collect::<Vec<_>>().into_iter().rev().collect(),
                public,
                borrowed: borrowed.contains(&r.rid),
                combined: r.worker.is_some(),
                worker: r.worker.clone().unwrap_or_default(),
                door: h.door.to_string(),
            });
        }
        let (front, front_ms) = g.as_ref().and_then(|g| g.front_now()).unwrap_or_default();
        let health_ref = if g.is_some() { Some(&health) } else { None };
        let announced = card_relays(&rows, health_ref).iter().filter_map(|b| RelayRef::from_bytes(b)).map(|r| r.rid()).collect();
        Ok(GasOverview { relays: views, front, front_ms, announced, running: g.is_some(), waiting: waiting_all, public_known, borrow_auto })
    }
}

fn finish(mut d: GasDay) -> GasDay {
    d.ms = if d.n > 0 { d.ms / d.n } else { 0 };
    d
}

fn call_error(e: CallError) -> Error {
    Error::Relay(e.code())
}

fn clean_name(name: &str) -> String {
    let n: String = name.trim().chars().filter(|c| !c.is_control()).take(40).collect();
    if n.is_empty() {
        "relay".to_string()
    } else {
        n
    }
}
