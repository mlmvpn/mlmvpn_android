//! The Google mailbox relay's formats (SPEC 19): a relay reference as cards and share links carry
//! it, the recipient's daily tag, the anonymous device id the relay counts, an item's id, and the
//! script itself (`relay/Mailbox.gs`) with fresh keys in it.

use crate::util::{b64url_decode, b64url_encode, random};
use crate::{Error, Result};
use hmac::{Hmac, KeyInit, Mac};
use sha2::{Digest, Sha256};

/// The script the app hands out, with `__MLMCHAT_USE_KEY__` and `__MLMCHAT_OWNER_KEY__` in it.
pub const SCRIPT_TEMPLATE: &str = include_str!("../../../../relay/Mailbox.gs");
/// A combined relay's mailbox (SPEC 19.13): the Worker the app puts on the owner's Cloudflare
/// account, with the same two keys in it.
pub const WORKER_TEMPLATE: &str = include_str!("../../../../relay/MailboxWorker.js");
/// A combined relay's Google door: hands each request to the Worker at `__MLMCHAT_WORKER_URL__`.
pub const DOOR_TEMPLATE: &str = include_str!("../../../../relay/Door.gs");
/// The `VERSION` of that script.
pub const SCRIPT_VERSION: u32 = 3;
/// The oldest script that still works as a mailbox: an older one is shown as «update».
pub const MIN_SCRIPT_VERSION: u32 = 1;
/// The oldest script that can be a public relay (it keeps the owner's `pub` switch).
pub const PUBLIC_SCRIPT_VERSION: u32 = 2;
/// The oldest script that can keep the official feeds' copy (`fget`, `fput`; SPEC 18.2).
pub const FEED_SCRIPT_VERSION: u32 = 3;
pub const SHARE_PREFIX: &str = "mlmchat://r/";
pub const TAG_INFO: &[u8] = b"mlmchat/mbox/1";
pub const CLIENT_INFO: &[u8] = b"mlmchat/mbox/client/1";
pub const RID_INFO: &[u8] = b"mlmchat/mbox/rid/1";
pub const DAY_MS: u64 = 86_400_000;
const REF_VERSION: u8 = 1;
/// A combined relay's form (SPEC 19.13): it names a Worker beside the Google door.
const REF_VERSION_COMBINED: u8 = 2;
/// The longest a combined relay's card form may be (card key 14).
pub const MAX_COMBINED_BYTES: usize = 220;
pub const MAX_NAME_BYTES: usize = 48;

/// One relay: its Apps Script deployment id and the key anyone using it holds. A combined relay
/// (SPEC 19.13) also names its Worker: the mailbox itself, on Cloudflare; the deployment is then
/// its Google door.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct RelayRef {
    pub id: String,
    pub key: [u8; 16],
    /// The Worker's host name (`name.account.workers.dev`), for a combined relay.
    pub worker: Option<String>,
}

fn id_ok(id: &str) -> bool {
    (20..=110).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

/// A Worker's host name as cards carry it: lower-case letters, digits, dots and hyphens, at least
/// one dot, nothing that is not a name (no port, path or address).
pub fn worker_ok(host: &str) -> bool {
    (4..=100).contains(&host.len())
        && host.contains('.')
        && !host.starts_with('.')
        && !host.ends_with('.')
        && !host.contains("..")
        && host.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-')
        && host.parse::<std::net::IpAddr>().is_err()
}

/// The Worker's host out of whatever was given: the host, or its whole address.
pub fn normalize_worker(raw: &str) -> Option<String> {
    let t = raw.trim().to_ascii_lowercase();
    let t = t.strip_prefix("https://").or_else(|| t.strip_prefix("http://")).unwrap_or(&t);
    let host = t.split(['/', '?', '#']).next().unwrap_or("").to_string();
    worker_ok(&host).then_some(host)
}

/// The bare deployment id out of whatever was pasted: the id, the whole web-app URL
/// (`…/macros/s/AKfy…/exec`), with stray spaces or line breaks from the clipboard.
pub fn normalize_deployment_id(raw: &str) -> Option<String> {
    let text = raw.trim();
    let after = |marker: &str| text.find(marker).map(|i| text[i + marker.len()..].chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-').collect::<String>());
    let id: String = match after("/macros/s/").or_else(|| if text.contains("/exec") { after("/s/") } else { None }) {
        // the whole web-app address, also the Workspace form `…/a/macros/<domain>/s/<id>/exec`
        Some(id) => id,
        None => text.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-').collect(),
    };
    id_ok(&id).then_some(id)
}

impl RelayRef {
    pub fn new(id: &str, key: [u8; 16]) -> Result<RelayRef> {
        let id = normalize_deployment_id(id).ok_or(Error::Relay("bad_id"))?;
        Ok(RelayRef { id, key, worker: None })
    }

    /// `1 ‖ key (16) ‖ deployment id (ASCII)`: the form in cards (SPEC 19.3, card key 12). A
    /// combined relay: `2 ‖ key (16) ‖ id length (1) ‖ deployment id ‖ Worker host` (SPEC 19.13,
    /// card key 14).
    pub fn to_bytes(&self) -> Vec<u8> {
        match &self.worker {
            None => {
                let mut out = vec![REF_VERSION];
                out.extend_from_slice(&self.key);
                out.extend_from_slice(self.id.as_bytes());
                out
            }
            Some(w) => {
                let mut out = vec![REF_VERSION_COMBINED];
                out.extend_from_slice(&self.key);
                out.push(self.id.len() as u8);
                out.extend_from_slice(self.id.as_bytes());
                out.extend_from_slice(w.as_bytes());
                out
            }
        }
    }

    pub fn from_bytes(b: &[u8]) -> Option<RelayRef> {
        if b.len() < 17 {
            return None;
        }
        let key: [u8; 16] = b[1..17].try_into().expect("16");
        match b[0] {
            REF_VERSION => {
                let id = std::str::from_utf8(&b[17..]).ok()?;
                id_ok(id).then(|| RelayRef { id: id.to_string(), key, worker: None })
            }
            REF_VERSION_COMBINED if b.len() <= MAX_COMBINED_BYTES => {
                let n = *b.get(17)? as usize;
                let id = std::str::from_utf8(b.get(18..18 + n)?).ok()?;
                let worker = std::str::from_utf8(b.get(18 + n..)?).ok()?;
                (id_ok(id) && worker_ok(worker)).then(|| RelayRef { id: id.to_string(), key, worker: Some(worker.to_string()) })
            }
            _ => None,
        }
    }

    /// A combined relay (SPEC 19.13): it names a Worker beside its Google door.
    pub fn is_combined(&self) -> bool {
        self.worker.is_some()
    }

    /// A short stable name for statistics and the screens: says nothing of the key.
    pub fn rid(&self) -> String {
        rid_of(&self.id)
    }

    pub fn key_hex(&self) -> String {
        hex::encode(self.key)
    }
}

pub fn rid_of(deployment_id: &str) -> String {
    let mut h = Sha256::new();
    h.update(RID_INFO);
    h.update(deployment_id.as_bytes());
    hex::encode(&h.finalize()[..6])
}

fn hmac(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as KeyInit>::new_from_slice(key).expect("HMAC takes any key length");
    for p in parts {
        mac.update(p);
    }
    mac.finalize().into_bytes().into()
}

pub fn day(now_ms: u64) -> u64 {
    now_ms / DAY_MS
}

/// The recipient's tag on `day` (SPEC 19.2): the first 16 bytes of
/// `HMAC-SHA256(loc, "mlmchat/mbox/1" ‖ day as u32 big-endian)`. Every contact holding the
/// card can make it; the relay and Google see only a random-looking value that changes daily.
pub fn tag(loc: &[u8; 32], day: u64) -> [u8; 16] {
    let d = (day as u32).to_be_bytes();
    hmac(loc, &[TAG_INFO, &d])[..16].try_into().expect("16")
}

/// The anonymous device id one relay sees on one day: 8 bytes from a secret that never leaves
/// the device. The relay can count devices a day; it cannot follow one across days or relays.
pub fn client_id(secret: &[u8; 32], rid: &str, day: u64) -> String {
    let d = (day as u32).to_be_bytes();
    hex::encode(&hmac(secret, &[CLIENT_INFO, rid.as_bytes(), &d])[..8])
}

/// An item's id on the relay: the first 16 bytes of SHA-256 of the sealed frame. Putting the
/// same frame again refreshes it instead of making a copy.
pub fn item_id(sealed: &[u8]) -> [u8; 16] {
    Sha256::digest(sealed)[..16].try_into().expect("16")
}

/// The primary relay of a list for a tag (SPEC 19.4): senders try it first, the recipient takes
/// from it most often. Tags change daily, so a recipient's load moves from relay to relay.
pub fn primary(tag: &[u8; 16], n: usize) -> usize {
    if n == 0 {
        0
    } else {
        u32::from_be_bytes(tag[..4].try_into().expect("4")) as usize % n
    }
}

/// Two fresh keys for a new relay: the one cards carry, and the owner's.
pub fn new_keys() -> ([u8; 16], [u8; 16]) {
    (random(), random())
}

/// The script with these keys in it, ready to paste into script.google.com.
pub fn script(use_key: &[u8; 16], owner_key: &[u8; 16]) -> String {
    SCRIPT_TEMPLATE.replace("__MLMCHAT_USE_KEY__", &hex::encode(use_key)).replace("__MLMCHAT_OWNER_KEY__", &hex::encode(owner_key))
}

/// A combined relay's Worker with these keys in it, for the app to put on the owner's Cloudflare
/// account (an ES module with a Durable Object class `Mailbox`, bound as `MBOX`).
pub fn worker_script(use_key: &[u8; 16], owner_key: &[u8; 16]) -> String {
    WORKER_TEMPLATE.replace("__MLMCHAT_USE_KEY__", &hex::encode(use_key)).replace("__MLMCHAT_OWNER_KEY__", &hex::encode(owner_key))
}

/// A combined relay's Google door for the Worker at `host`, ready to paste into script.google.com.
pub fn door_script(host: &str) -> String {
    DOOR_TEMPLATE.replace("__MLMCHAT_WORKER_URL__", &format!("https://{host}/"))
}

/// A relay someone shared, with the name its owner gave it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Shared {
    pub relay: RelayRef,
    pub name: String,
}

/// `mlmchat://r/` + base64url(`1 ‖ key (16) ‖ name length (1) ‖ name ‖ deployment id`). Only
/// the key cards carry is in it, never the owner's. A combined relay: `2 ‖ key (16) ‖ name length
/// (1) ‖ name ‖ id length (1) ‖ deployment id ‖ Worker host` (SPEC 19.13).
pub fn share_link(relay: &RelayRef, name: &str) -> String {
    let mut name = name.trim().to_string();
    while name.len() > MAX_NAME_BYTES {
        name.pop();
    }
    let mut b = vec![if relay.worker.is_some() { REF_VERSION_COMBINED } else { REF_VERSION }];
    b.extend_from_slice(&relay.key);
    b.push(name.len() as u8);
    b.extend_from_slice(name.as_bytes());
    if let Some(w) = &relay.worker {
        b.push(relay.id.len() as u8);
        b.extend_from_slice(relay.id.as_bytes());
        b.extend_from_slice(w.as_bytes());
    } else {
        b.extend_from_slice(relay.id.as_bytes());
    }
    format!("{SHARE_PREFIX}{}", b64url_encode(&b))
}

/// A share link in any form it travels in: `mlmchat://r/…`, `tg://gereh_relay?l=…`, or bare.
pub fn parse_share(link: &str) -> Result<Shared> {
    let text = link.trim();
    let payload = if let Some(i) = text.find("l=") {
        &text[i + 2..]
    } else if text.len() >= SHARE_PREFIX.len() && text[..SHARE_PREFIX.len()].eq_ignore_ascii_case(SHARE_PREFIX) {
        &text[SHARE_PREFIX.len()..]
    } else {
        text
    };
    let payload: String = payload.chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-').collect();
    let bad = || Error::Link("bad relay link");
    let b = b64url_decode(&payload).ok_or_else(bad)?;
    if b.len() < 18 || (b[0] != REF_VERSION && b[0] != REF_VERSION_COMBINED) {
        return Err(bad());
    }
    let n = b[17] as usize;
    if n > MAX_NAME_BYTES || b.len() < 18 + n {
        return Err(bad());
    }
    let name = std::str::from_utf8(&b[18..18 + n]).map_err(|_| bad())?.to_string();
    let key: [u8; 16] = b[1..17].try_into().expect("16");
    let rest = &b[18 + n..];
    let relay = if b[0] == REF_VERSION {
        let id = std::str::from_utf8(rest).map_err(|_| bad())?;
        if !id_ok(id) {
            return Err(bad());
        }
        RelayRef { id: id.to_string(), key, worker: None }
    } else {
        let mut form = vec![REF_VERSION_COMBINED];
        form.extend_from_slice(&key);
        form.extend_from_slice(rest);
        RelayRef::from_bytes(&form).ok_or_else(bad)?
    };
    Ok(Shared { relay, name })
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: &str = "AKfycbx1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-01";

    #[test]
    fn deployment_ids_are_cleaned() {
        assert_eq!(normalize_deployment_id(&format!(" https://script.google.com/macros/s/{ID}/exec \n")).as_deref(), Some(ID));
        assert_eq!(normalize_deployment_id(&format!("{ID}\n")).as_deref(), Some(ID));
        assert_eq!(normalize_deployment_id(&format!("https://script.google.com/a/macros/example.org/s/{ID}/exec")).as_deref(), Some(ID));
        // a pasted script is not a deployment
        assert_eq!(normalize_deployment_id(&"function doPost(e) { return 1; }\n".repeat(20)), None);
        assert_eq!(normalize_deployment_id("short"), None);
    }

    #[test]
    fn relay_refs_round_trip() {
        let r = RelayRef::new(ID, [7; 16]).unwrap();
        assert_eq!(RelayRef::from_bytes(&r.to_bytes()), Some(r.clone()));
        assert!(r.to_bytes().len() <= crate::card::MAX_RELAY_BYTES);
        assert_eq!(r.rid().len(), 12);
        assert!(RelayRef::from_bytes(&[2; 40]).is_none());
        assert!(RelayRef::from_bytes(&[3; 40]).is_none());
    }

    #[test]
    fn combined_relays_round_trip_and_bad_workers_are_refused() {
        let mut r = RelayRef::new(ID, [5; 16]).unwrap();
        r.worker = Some("gereh-box-1a2b.example.workers.dev".into());
        let b = r.to_bytes();
        assert_eq!(b[0], 2);
        assert!(b.len() <= MAX_COMBINED_BYTES);
        assert_eq!(RelayRef::from_bytes(&b), Some(r.clone()));
        assert_eq!(r.rid(), RelayRef::new(ID, [5; 16]).unwrap().rid(), "named by its Google door");
        let link = share_link(&r, "ترکیبی");
        let s = parse_share(&link).unwrap();
        assert_eq!(s.relay, r);
        assert_eq!(s.name, "ترکیبی");
        for bad in ["127.0.0.1", "x", "evil.com:8080", "a..b", "UPPER.workers.dev", "a/b.dev", ".a.dev"] {
            assert!(!worker_ok(bad), "{bad}");
        }
        assert_eq!(normalize_worker(" https://Gereh-Box.example.workers.dev/ \n").as_deref(), Some("gereh-box.example.workers.dev"));
        let door = door_script("gereh-box.example.workers.dev");
        assert!(door.contains("var WORKER_URL = \"https://gereh-box.example.workers.dev/\";"));
        assert!(!door.contains('*'), "no asterisk in a Google script");
        let w = worker_script(&[0xab; 16], &[0xcd; 16]);
        assert!(w.contains(&format!("const USE_KEY = \"{}\";", "ab".repeat(16))));
        assert!(w.contains(&format!("const OWNER_KEY = \"{}\";", "cd".repeat(16))));
        assert!(WORKER_TEMPLATE.contains(&format!("const VERSION = {SCRIPT_VERSION};")));
    }

    #[test]
    fn share_links_round_trip_in_every_form() {
        let r = RelayRef::new(ID, [9; 16]).unwrap();
        let link = share_link(&r, "رلهٔ احسان");
        let tg = link.replace(SHARE_PREFIX, "tg://gereh_relay?l=");
        for l in [link.clone(), tg, link[SHARE_PREFIX.len()..].to_string()] {
            let s = parse_share(&l).unwrap();
            assert_eq!(s.relay, r);
            assert_eq!(s.name, "رلهٔ احسان");
        }
        assert!(parse_share("mlmchat://r/AAAA").is_err());
    }

    #[test]
    fn tags_change_daily_and_differ_per_locator() {
        let a = tag(&[1; 32], 20_000);
        assert_eq!(a, tag(&[1; 32], 20_000));
        assert_ne!(a, tag(&[1; 32], 20_001));
        assert_ne!(a, tag(&[2; 32], 20_000));
        assert_ne!(client_id(&[3; 32], "aa", 1), client_id(&[3; 32], "aa", 2));
        assert_ne!(client_id(&[3; 32], "aa", 1), client_id(&[3; 32], "bb", 1));
    }

    #[test]
    fn the_script_gets_the_keys() {
        let s = script(&[0xab; 16], &[0xcd; 16]);
        assert!(s.contains(&format!("var USE_KEY = \"{}\";", "ab".repeat(16))));
        assert!(s.contains(&format!("var OWNER_KEY = \"{}\";", "cd".repeat(16))));
        assert!(!s.contains("__MLMCHAT_USE_KEY__\""));
        assert!(SCRIPT_TEMPLATE.contains(&format!("var VERSION = {SCRIPT_VERSION};")));
    }
}
