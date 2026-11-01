//! Nostr relays on the node (SPEC 20, T52): start and stop the transport, and keep our card's
//! list of the relays we read from (card key 13) the same as the ones really open.

use super::{gas::NodeGasStore, Inner, Node};
use crate::transport::nostr::transport::{NostrConfig, NostrTransport, RelayChoice, RelayView};
use crate::transport::Transport;
use crate::Result;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// How often the relays open are compared with what our card says.
const CARD_CHECK: Duration = Duration::from_secs(1);
/// The relays open stay the same this long before contacts are told: at the start the first to
/// open (ours in Iran, a second after) is not all we read from. Contacts were told only that one,
/// at every start, and wrote to us nowhere else: when it stopped, so did the chats (31 Oct 2026).
const CARD_SETTLE: Duration = Duration::from_secs(20);
/// Contacts hear of a relay we read from that they were not told of at most this often.
const CARD_GAIN_EVERY: Duration = Duration::from_secs(30 * 60);
/// meta: the relay the user prefers (SPEC 20.6), its URL; none or empty for automatic.
const META_PREFERRED: &str = "nostr_preferred";
/// meta: the relays contacts were last told of, names one per line.
const META_TOLD: &str = "nostr_told";
/// Each relay's measuring waits at most this long (twice for one measured again record-split).
const MEASURE_TIMEOUT: Duration = Duration::from_secs(8);

impl Inner {
    /// Our card names the relays we read from right now. True if that changed.
    pub(super) fn sync_card_nostr(&self) -> bool {
        let reading: Vec<Vec<u8>> = match self.nostr.lock().expect("nostr").as_ref() {
            Some(t) => t.reading().into_iter().map(String::into_bytes).collect(),
            None => Vec::new(),
        };
        let mut st = self.state.lock().expect("state");
        if st.identity.nostr() == reading.as_slice() {
            return false;
        }
        st.identity.set_nostr(reading);
        true
    }
}

impl Node {
    /// Start the Nostr transport: the first relays of the list that open here are read from, and
    /// contacts whose card names a relay of our list are written to. A no-op when running.
    pub fn start_nostr(&self, cfg: NostrConfig) -> Result<Arc<NostrTransport>> {
        if let Some(t) = self.inner.nostr.lock().expect("nostr").as_ref() {
            return Ok(t.clone());
        }
        let store = Arc::new(NodeGasStore(Arc::downgrade(&self.inner)));
        let t = NostrTransport::start(cfg, self.hooks(), store);
        t.set_preferred(self.nostr_preferred());
        *self.inner.nostr.lock().expect("nostr") = Some(t.clone());
        self.start_with(t.clone());
        // the relays open change our card: contacts get the new one when we read from a relay
        // they were not told of, once the relays open have settled (`CARD_SETTLE`) and at most
        // every `CARD_GAIN_EVERY`; at once when they were told of none (a new phone, or one from
        // before this was kept). One we stopped reading is not worth a card to everyone (a frame
        // written there waits for us, or goes by another relay of the card).
        let weak = Arc::downgrade(&self.inner);
        let _ = std::thread::Builder::new().name("mlmchat-nostr-card".into()).spawn(move || {
            let Some(mut told) = weak.upgrade().map(|i| i.nostr_told()) else { return };
            let mut told_at: Option<Instant> = None;
            let mut changed = Instant::now();
            loop {
                std::thread::sleep(CARD_CHECK);
                let Some(i) = weak.upgrade() else { return };
                if i.stop.load(Ordering::SeqCst) || i.nostr.lock().expect("nostr").is_none() {
                    return;
                }
                if i.sync_card_nostr() {
                    changed = Instant::now();
                }
                let names = i.identity_nostr();
                let urgent = told.is_empty();
                if names.is_empty() || !urgent && changed.elapsed() < CARD_SETTLE {
                    continue;
                }
                let gained = names.iter().any(|n| !told.contains(n));
                if gained && (urgent || told_at.is_none_or(|at| at.elapsed() >= CARD_GAIN_EVERY)) {
                    i.set_nostr_told(&names);
                    // after a first word to contacts who had none, the settled list follows at once
                    if !urgent {
                        told_at = Some(Instant::now());
                    }
                    told = names;
                    i.gas_dirty.store(true, Ordering::SeqCst);
                    i.waker.wake();
                }
            }
        });
        Ok(t)
    }

    pub fn stop_nostr(&self) {
        let Some(t) = self.inner.nostr.lock().expect("nostr").take() else { return };
        self.inner.transports.lock().expect("transport").retain(|t| t.name() != "nostr");
        Transport::stop(&*t);
        self.inner.sync_card_nostr();
    }

    /// The relays as the screens show them (empty when Nostr is off).
    pub fn nostr_relays(&self) -> Vec<RelayView> {
        self.inner.nostr.lock().expect("nostr").as_ref().map(|t| t.relays()).unwrap_or_default()
    }

    /// Every relay of the list for the relay picker (SPEC 20.6; empty when Nostr is off).
    pub fn nostr_choices(&self) -> Vec<RelayChoice> {
        self.inner.nostr.lock().expect("nostr").as_ref().map(|t| t.choices()).unwrap_or_default()
    }

    /// Measure every relay now (the user's tap) and give the list again. Blocks for seconds.
    pub fn nostr_measure(&self) -> Vec<RelayChoice> {
        let t = self.inner.nostr.lock().expect("nostr").clone();
        t.map(|t| t.measure(MEASURE_TIMEOUT)).unwrap_or_default()
    }

    /// The relay the user prefers, kept for the next start; None for automatic.
    pub fn set_nostr_preferred(&self, url: Option<&str>) -> Result<()> {
        let url = url.filter(|u| !u.is_empty());
        self.inner.state.lock().expect("state").store.db().set_meta(META_PREFERRED, url.unwrap_or("").as_bytes())?;
        if let Some(t) = self.inner.nostr.lock().expect("nostr").as_ref() {
            t.set_preferred(url.map(String::from));
        }
        Ok(())
    }

    /// The relay the user prefers, or None for automatic.
    pub fn nostr_preferred(&self) -> Option<String> {
        let v = self.inner.state.lock().expect("state").store.db().meta(META_PREFERRED).ok().flatten()?;
        String::from_utf8(v).ok().filter(|u| !u.is_empty())
    }
}

impl Inner {
    fn identity_nostr(&self) -> Vec<Vec<u8>> {
        self.state.lock().expect("state").identity.nostr().to_vec()
    }

    /// The relays contacts were last told we read from (kept, so a restart knows).
    fn nostr_told(&self) -> Vec<Vec<u8>> {
        let v = self.state.lock().expect("state").store.db().meta(META_TOLD).ok().flatten().unwrap_or_default();
        v.split(|b| *b == b'\n').filter(|n| !n.is_empty()).map(<[u8]>::to_vec).collect()
    }

    fn set_nostr_told(&self, names: &[Vec<u8>]) {
        let _ = self.state.lock().expect("state").store.db().set_meta(META_TOLD, &names.join(&b'\n'));
    }
}
