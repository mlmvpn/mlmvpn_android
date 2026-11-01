//! The Cloudflare door of a combined relay (SPEC 19.13): which Cloudflare address, with the
//! ClientHello whole or in two TLS records, reaches the relay's Worker on this network. Raced with
//! the real first steps of a request, the way the Google fronts are (`front.rs`), and remembered per
//! network and Worker. Where none answers, the relay's Google door carries everything and Cloudflare
//! is looked at again after `RETRY_MS`.

use super::front;
use super::http::Endpoint;
use std::net::{IpAddr, ToSocketAddrs};
use std::sync::mpsc::channel;
use std::thread;
use std::time::{Duration, Instant};

/// Cloudflare addresses to try after the resolver's: any address of Cloudflare's own ranges serves
/// every name Cloudflare hosts, a Worker's too. The app may put the edges it found clean on this
/// network in front of them (`GasConfig::cf_ips`).
pub const IPS: &[&str] = &[
    "104.16.132.229",
    "104.16.133.229",
    "188.114.96.3",
    "188.114.97.3",
    "104.17.10.10",
    "104.18.10.10",
    "172.67.10.10",
    "104.21.10.10",
];

/// The door that last worked here is tried alone first, this long.
pub const REMEMBERED_TIMEOUT: Duration = Duration::from_millis(2500);
pub const PROBE_TIMEOUT: Duration = Duration::from_secs(4);
/// After no Cloudflare address answered (or the way that did broke), the Google door alone for
/// this long.
pub const RETRY_MS: u64 = 3 * 60 * 1000;
/// The first wave: this many addresses, each whole and split.
const FIRST_WAVE: usize = 4;
const DNS_WAIT: Duration = Duration::from_millis(1500);

/// The Worker's own addresses from the system resolver, IPv4, leaving out what a poisoned answer
/// gives (private addresses, a block page).
fn resolved(host: &str) -> Vec<IpAddr> {
    let (tx, rx) = channel();
    let name = format!("{host}:443");
    let _ = thread::Builder::new().name("gas-cf-dns".into()).spawn(move || {
        let found: Vec<IpAddr> = name.to_socket_addrs().map(|a| a.map(|s| s.ip()).collect()).unwrap_or_default();
        let _ = tx.send(found);
    });
    let mut out: Vec<IpAddr> = rx
        .recv_timeout(DNS_WAIT)
        .unwrap_or_default()
        .into_iter()
        .filter(|ip| match ip {
            IpAddr::V4(v) => !v.is_loopback() && !v.is_unspecified() && !v.is_private() && !v.is_link_local() && !v.is_multicast() && !v.is_broadcast(),
            IpAddr::V6(_) => false,
        })
        .collect();
    out.dedup();
    out.truncate(2);
    out
}

/// The addresses to try, best first: the app's clean edges, the resolver's, the listed ones.
pub fn addresses(host: &str, extra: &[IpAddr], dns: bool) -> Vec<IpAddr> {
    let mut ips: Vec<IpAddr> = extra.to_vec();
    if dns {
        ips.extend(resolved(host));
    }
    for s in IPS {
        ips.push(s.parse().expect("listed address"));
    }
    let mut seen = Vec::new();
    ips.retain(|ip| {
        let fresh = !seen.contains(ip);
        seen.push(*ip);
        fresh
    });
    ips
}

/// Find a way to the Worker at `host`: the remembered one alone, then a small wave, then the rest;
/// each address whole, and in two records. None when nothing answered.
pub fn find(host: &str, extra: &[IpAddr], remembered: Option<Endpoint>, dns: bool) -> Option<(Endpoint, u64)> {
    let t0 = Instant::now();
    if let Some(r) = remembered {
        if let Some(ms) = super::http::probe(&r, REMEMBERED_TIMEOUT) {
            return Some((r, ms));
        }
    }
    let ips = addresses(host, extra, dns);
    let both = |ips: &[IpAddr]| -> Vec<Endpoint> {
        ips.iter().flat_map(|ip| [false, true].map(|split| Endpoint::Worker { ip: *ip, host: host.to_string(), split })).collect()
    };
    let (first, rest) = ips.split_at(ips.len().min(FIRST_WAVE));
    let found = front::race(both(first), PROBE_TIMEOUT).or_else(|| front::race(both(rest), PROBE_TIMEOUT));
    if let Some((e, _)) = &found {
        log::info!("gas: a way to a Worker found in {} ms ({})", t0.elapsed().as_millis(), front::label(e));
    }
    found
}

/// How a door is remembered over restarts: `ip|split`.
pub fn to_memo(e: &Endpoint) -> Option<String> {
    match e {
        Endpoint::Worker { ip, split, .. } => Some(format!("{ip}|{}", u8::from(*split))),
        _ => None,
    }
}

pub fn from_memo(host: &str, memo: &str) -> Option<Endpoint> {
    let (ip, split) = memo.split_once('|')?;
    Some(Endpoint::Worker { ip: ip.parse().ok()?, host: host.to_string(), split: split == "1" })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_put_the_apps_edges_first_and_repeat_none() {
        let extra: Vec<IpAddr> = vec!["188.114.97.3".parse().unwrap(), "1.2.3.4".parse().unwrap()];
        let ips = addresses("box.a.workers.dev", &extra, false);
        assert_eq!(ips[0], extra[0]);
        assert_eq!(ips[1], extra[1]);
        assert_eq!(ips.iter().filter(|ip| **ip == extra[0]).count(), 1);
        assert_eq!(ips.len(), IPS.len() + 1);
    }

    #[test]
    fn a_door_is_remembered_as_address_and_split() {
        let e = Endpoint::Worker { ip: "104.16.132.229".parse().unwrap(), host: "box.a.workers.dev".into(), split: true };
        let m = to_memo(&e).unwrap();
        assert_eq!(from_memo("box.a.workers.dev", &m), Some(e));
        assert_eq!(from_memo("x", "nonsense"), None);
    }
}
