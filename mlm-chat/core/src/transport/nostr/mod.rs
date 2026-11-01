//! Nostr relays as mailboxes (SPEC 20, T52; Ehsan, 26 Oct 2026: «اینم به عنوان راه جدید پیاده
//! کن»): a way between two phones on two operators that needs neither the Google relay nor a
//! user's modem. Hundreds of public relays, run by many people in many countries, keep and pass on
//! signed events over WebSocket, with no account and at once (no polling). A frame goes as an
//! event under the recipient's daily tag, sealed to the recipient exactly as in a Google mailbox;
//! the relays see sizes, times and the phone's address, never who talks to whom or what is said.
//!
//! `probe` measures which relays a network lets through, plainly and with the TLS ClientHello cut
//! in two records (Shroudly's "record split"); `transport` carries frames; `event` makes and checks
//! the events.

pub mod dir;
pub mod event;
pub mod feedrelay;
pub mod files;
pub mod transport;
pub mod ws;

use std::time::{Duration, Instant};
use ws::{Split, Stage, Ws};

/// Public relays that take writes without payment or sign-up (26 Oct 2026). A phone keeps the
/// ones that work on its network; the list only seeds that. First our own relay inside Iran (T54,
/// 27 Oct 2026: strfry, sealed frames only, no logs; `relay/nostr/setup-strfry.sh`), which stays
/// up when the international internet is cut; then the eight that opened both on home Wi-Fi and
/// on Irancell (A50, 26 Oct 2026; fastest subscription first), so phones on the two networks keep
/// the same ones; then those open on one of them; the rest last.
pub const RELAYS: &[&str] = &[
    "wss://203.0.113.10",
    "wss://nos.lol",
    "wss://nostr.mom",
    "wss://relay.snort.social",
    "wss://offchain.pub",
    "wss://nostr21.com",
    "wss://nostr-pub.wellorder.net",
    "wss://nostr.bitcoiner.social",
    "wss://nostr.oxtr.dev",
    "wss://relay.damus.io",
    "wss://nostr.wine",
    "wss://relay.primal.net",
    "wss://relay.mostr.pub",
    "wss://relay.nostr.net",
    "wss://nostr.fmt.wiz.biz",
    "wss://relay.nostr.band",
    "wss://relay.nostr.bg",
    "wss://relay.0xchat.com",
    "wss://relay.current.fyi",
];

/// Our own relays (T54): a self-signed certificate on an IP address, no domain and no outside CA.
/// The host and the SHA-256 of its certificate, which is checked instead of the web's roots.
pub const PINNED: &[(&str, &str)] = &[("203.0.113.10", "0000000000000000000000000000000000000000000000000000000000000000")];

/// The pinned certificate hash for `host`, if it is one of our own relays.
pub fn pinned_sha256(host: &str) -> Option<[u8; 32]> {
    let (_, hex) = PINNED.iter().find(|(h, _)| *h == host)?;
    hex::decode(hex).ok()?.try_into().ok()
}

/// A relay's name as cards carry it (card key 13): the URL without `wss://`.
pub fn relay_name(url: &str) -> &str {
    url.strip_prefix("wss://").or_else(|| url.strip_prefix("ws://")).unwrap_or(url)
}

/// What one relay did on this network.
#[derive(Debug, Clone)]
pub struct Probe {
    pub relay: String,
    pub split: Split,
    /// None: it answered a subscription; Some(stage): where it stopped.
    pub failed: Option<Stage>,
    /// Milliseconds at DNS, TCP, TLS, upgrade, and the subscription's end-of-stored answer.
    pub ms: [u64; 5],
}

/// Connect to `relay`, subscribe to nothing that exists (a random tag), and wait for its
/// end-of-stored-events: the whole path a mailbox needs, with no event written.
pub fn probe(relay: &str, split: Split, timeout: Duration) -> Probe {
    let t0 = Instant::now();
    let mut out = Probe { relay: relay.to_string(), split, failed: None, ms: [0; 5] };
    let (mut ws, ms) = match Ws::connect(relay, split, timeout) {
        Ok(x) => x,
        Err((stage, ms)) => {
            out.ms[..4].copy_from_slice(&ms);
            out.failed = Some(stage);
            return out;
        }
    };
    out.ms[..4].copy_from_slice(&ms);
    let sub = hex::encode(crate::util::random::<8>());
    let tag = hex::encode(crate::util::random::<32>());
    let req = format!(r##"["REQ","{sub}",{{"kinds":[1059],"#p":["{tag}"],"limit":1}}]"##);
    if ws.send_text(&req).is_err() {
        out.failed = Some(Stage::Io);
        return out;
    }
    let end = Instant::now() + timeout;
    while Instant::now() < end {
        match ws.recv_text(256 * 1024) {
            Ok(m) if m.starts_with("[\"EOSE\"") || m.starts_with("[\"CLOSED\"") => {
                out.ms[4] = t0.elapsed().as_millis() as u64;
                let _ = ws.send_text(&format!(r#"["CLOSE","{sub}"]"#));
                ws.close();
                return out;
            }
            Ok(_) => {}
            Err(_) => break,
        }
    }
    out.failed = Some(Stage::Io);
    out
}

/// Every relay of the list, plainly and with the record split, a few at a time.
pub fn probe_all(timeout: Duration) -> Vec<Probe> {
    let (tx, rx) = std::sync::mpsc::channel();
    let mut n = 0;
    for chunk in RELAYS.chunks(6) {
        let mut handles = Vec::new();
        for r in chunk {
            for split in [Split::None, Split::Record] {
                let tx = tx.clone();
                let r = r.to_string();
                n += 1;
                handles.push(std::thread::spawn(move || {
                    let _ = tx.send(probe(&r, split, timeout));
                }));
            }
        }
        for h in handles {
            let _ = h.join();
        }
    }
    drop(tx);
    let mut out: Vec<Probe> = rx.iter().take(n).collect();
    out.sort_by(|a, b| a.relay.cmp(&b.relay).then((a.split as u8).cmp(&(b.split as u8))));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn our_relay_comes_first_with_its_certificate_pinned() {
        let host = relay_name(RELAYS[0]);
        let pin = pinned_sha256(host).expect("the first relay is ours, with a pin");
        assert_eq!(hex::encode(pin), PINNED[0].1);
        assert!(pinned_sha256("nos.lol").is_none(), "public relays keep the web's roots");
    }
}
