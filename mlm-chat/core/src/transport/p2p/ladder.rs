//! The connection ladder (SPEC 15.9): the ways of reaching a volunteer, tried in the order this
//! network has proven, each with its own short timeout (fixed principle 3: measure, don't
//! assume; Iranian operators and regions differ).
//!
//! A rung is one address of one volunteer. Two memories rank it, both kept per network on the
//! device (`ladder` table): the address itself (`a:<hash>`) and its class (`c:v4:443`,
//! `c:v6:40443`, `c:lan:40443`), so a volunteer never tried here still benefits from what its
//! class did on this network. Without any memory: the LAN first (except on mobile data), then
//! IPv4 on the common ports, then other IPv4, then IPv6 (IPv6 is the most restricted in Iran).

use crate::store::RouteStat;
use libp2p::multiaddr::Protocol;
use libp2p::Multiaddr;
use sha2::{Digest, Sha256};
use std::net::IpAddr;

/// A rung that failed this many times in a row on this network goes to the end...
pub const STREAK_LIMIT: u32 = 3;
/// ...while it was tried this recently.
pub const SKIP_MS: u64 = 10 * 60 * 1000;

/// One candidate: its two memory keys.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rung {
    pub addr_key: String,
    pub class: String,
}

impl Rung {
    pub fn of(a: &Multiaddr) -> Rung {
        Rung { addr_key: addr_key(a), class: class_of(a) }
    }
}

/// `a:` and the first 8 bytes of SHA-256 of the binary multiaddr: the address itself never
/// sits in the table, only a stable name for it.
pub fn addr_key(a: &Multiaddr) -> String {
    format!("a:{}", hex::encode(&Sha256::digest(a.to_vec())[..8]))
}

/// `c:v4:<port>`, `c:v6:<port>`, or `c:lan:<port>` for a private, link-local or loopback address.
pub fn class_of(a: &Multiaddr) -> String {
    let port = a.iter().find_map(|p| if let Protocol::Tcp(p) = p { Some(p) } else { None }).unwrap_or(0);
    let fam = match ip(a) {
        Some(IpAddr::V4(v)) if v.is_private() || v.is_loopback() || v.is_link_local() || (v.octets()[0] == 100 && (v.octets()[1] & 0xc0) == 64) => "lan",
        Some(IpAddr::V6(v)) if v.is_loopback() || (v.segments()[0] & 0xfe00) == 0xfc00 || (v.segments()[0] & 0xffc0) == 0xfe80 => "lan",
        Some(IpAddr::V4(_)) => "v4",
        Some(IpAddr::V6(_)) => "v6",
        None => "other",
    };
    format!("c:{fam}:{port}")
}

fn ip(a: &Multiaddr) -> Option<IpAddr> {
    a.iter().find_map(|p| match p {
        Protocol::Ip4(v) => Some(IpAddr::V4(v)),
        Protocol::Ip6(v) => Some(IpAddr::V6(v)),
        _ => None,
    })
}

/// The order with no memory at all.
fn default_rank(class: &str, on_cell: bool) -> u8 {
    let mut it = class.split(':').skip(1);
    let fam = it.next().unwrap_or("");
    let port: u16 = it.next().and_then(|p| p.parse().ok()).unwrap_or(0);
    match (fam, port) {
        ("lan", _) if !on_cell => 0,
        ("v4", 443) => 1,
        ("v4", 80) => 2,
        ("v4", 8080) => 3,
        ("v4", _) => 4,
        ("v6", 443) => 5,
        ("v6", _) => 6,
        ("lan", _) => 9,
        _ => 8,
    }
}

/// The order to try `rungs` in on this network. Nothing is left out: a rung that failed
/// `STREAK_LIMIT` times in a row within `SKIP_MS` goes last, after those never tried and those
/// that only failed; the rest come by their newest success here.
pub fn order(rungs: &[Rung], stats: &[RouteStat], on_cell: bool, now: u64) -> Vec<usize> {
    let stat = |k: &str| stats.iter().find(|s| s.via == k);
    let mut idx: Vec<usize> = (0..rungs.len()).collect();
    idx.sort_by_key(|&i| {
        let r = &rungs[i];
        let (a, c) = (stat(&r.addr_key), stat(&r.class));
        let last_ok = a.map(|s| s.last_ok_ms).unwrap_or(0).max(c.map(|s| s.last_ok_ms).unwrap_or(0));
        let on_streak = |s: Option<&RouteStat>| s.is_some_and(|s| s.streak >= STREAK_LIMIT && now.saturating_sub(s.last_try_ms) < SKIP_MS);
        // the address's own memory wins over its class's
        let bad = on_streak(a) || (a.is_none_or(|s| s.ok == 0) && on_streak(c));
        let failed_only = last_ok == 0 && (a.is_some_and(|s| s.fail > 0) || c.is_some_and(|s| s.fail > 0));
        (bad, std::cmp::Reverse(last_ok), failed_only, default_rank(&r.class, on_cell), i)
    });
    idx
}

#[cfg(test)]
mod tests {
    use super::*;

    fn st(via: &str, ok: u32, fail: u32, streak: u32, last_ok_ms: u64, last_try_ms: u64) -> RouteStat {
        RouteStat { via: via.into(), ok, fail, streak, last_ok_ms, last_try_ms, ms: 0 }
    }

    fn rung(a: &str) -> Rung {
        Rung::of(&a.parse().unwrap())
    }

    #[test]
    fn classes_and_keys() {
        assert_eq!(class_of(&"/ip4/203.0.113.7/tcp/443/tls".parse().unwrap()), "c:v4:443");
        assert_eq!(class_of(&"/ip6/2001:db8::1/tcp/40443/tls".parse().unwrap()), "c:v6:40443");
        assert_eq!(class_of(&"/ip4/192.168.8.6/tcp/40443/tls".parse().unwrap()), "c:lan:40443");
        assert_eq!(class_of(&"/ip6/fe80::1/tcp/1/tls".parse().unwrap()), "c:lan:1");
        let k = addr_key(&"/ip4/203.0.113.7/tcp/443/tls".parse().unwrap());
        assert!(k.starts_with("a:") && k.len() == 18 && !k.contains("203"));
    }

    #[test]
    fn default_order_is_lan_then_ipv4_common_ports_then_ipv6() {
        let r = [rung("/ip6/2001:db8::1/tcp/40443/tls"), rung("/ip4/203.0.113.7/tcp/40443/tls"), rung("/ip4/203.0.113.7/tcp/443/tls"), rung("/ip4/192.168.8.6/tcp/40443/tls")];
        assert_eq!(order(&r, &[], false, 1_000), vec![3, 2, 1, 0]);
        // on mobile data a LAN address goes last
        assert_eq!(order(&r, &[], true, 1_000), vec![2, 1, 0, 3]);
    }

    #[test]
    fn this_networks_memory_reorders() {
        let r = [rung("/ip4/203.0.113.7/tcp/443/tls"), rung("/ip6/2001:db8::1/tcp/40443/tls")];
        // here IPv6 worked and IPv4 only failed: IPv6 first
        let s = [st(&r[0].addr_key, 0, 2, 2, 0, 900), st(&r[1].addr_key, 1, 0, 0, 950, 950)];
        assert_eq!(order(&r, &s, true, 1_000), vec![1, 0]);
        // a class that worked here helps a volunteer never tried here
        let other = rung("/ip6/2001:db8::99/tcp/40443/tls");
        let s = [st(&other.class, 4, 0, 0, 990, 990)];
        assert_eq!(order(&[r[0].clone(), other.clone()], &s, true, 1_000), vec![1, 0]);
        // three failures in a row lately: last, after an untried rung
        let r3 = [rung("/ip4/203.0.113.7/tcp/443/tls"), rung("/ip4/198.51.100.2/tcp/80/tls")];
        let s = [st(&r3[0].addr_key, 0, 3, 3, 0, 990)];
        assert_eq!(order(&r3, &s, true, 1_000), vec![1, 0]);
        // ...and back in its place once SKIP_MS has passed (an untried rung still goes first)
        assert_eq!(order(&r3, &s, true, 990 + SKIP_MS + 1), vec![1, 0]);
        let s = [st(&r3[0].addr_key, 2, 3, 3, 500, 990)];
        assert_eq!(order(&r3, &s, true, 990 + SKIP_MS + 1), vec![0, 1]);
    }
}
