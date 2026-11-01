//! Which Google front to use on this network (SPEC 19.5), found by the phone itself: Google
//! addresses and cover names are raced with the real first steps of a request (TCP, TLS with a
//! verified certificate, an HTTP answer for script.google.com) and the fastest is remembered for
//! the network. The user never chooses; the screens only show what was found.

use super::http::{probe, Endpoint};
use std::collections::HashSet;
use std::net::{IpAddr, ToSocketAddrs};
use std::sync::mpsc::channel;
use std::thread;
use std::time::{Duration, Instant};

/// Google frontends that serve script.google.com (the list GST in MLMVPN measured).
pub const IPS: &[&str] = &[
    "216.239.38.120",
    "216.239.32.120",
    "216.239.34.120",
    "216.239.36.120",
    "142.250.186.110",
    "142.251.37.110",
    "172.217.18.238",
    "142.250.181.142",
    "142.251.32.14",
];

/// Cover names with a certificate on those frontends (googlevideo.com and ytimg.com have none).
pub const SNIS: &[&str] = &[
    "www.google.com",
    "mail.google.com",
    "play.google.com",
    "drive.google.com",
    "translate.google.com",
    "maps.google.com",
    "mtalk.google.com",
    "googleapis.com",
    "www.youtube.com",
];

pub const PROBE_TIMEOUT: Duration = Duration::from_secs(4);
/// The front that last worked on this network is tried alone first, this long.
pub const REMEMBERED_TIMEOUT: Duration = Duration::from_millis(2500);
/// Then the first this many candidates (the system resolver's addresses and the listed ones, all
/// with www.google.com), and only if none answers, the rest.
pub const FIRST_WAVE: usize = 8;
const DNS_WAIT: Duration = Duration::from_millis(1500);
const MAX_PARALLEL: usize = 32;

/// Addresses the system resolver gives for Google names on this network, IPv4 only (the list
/// above stays the fallback when DNS answers nothing or something that does not work).
fn resolved() -> Vec<IpAddr> {
    let (tx, rx) = channel();
    for host in ["www.google.com:443", "script.google.com:443"] {
        let tx = tx.clone();
        let _ = thread::Builder::new().name("gas-dns".into()).spawn(move || {
            let found: Vec<IpAddr> = host.to_socket_addrs().map(|a| a.map(|s| s.ip()).collect()).unwrap_or_default();
            let _ = tx.send(found);
        });
    }
    drop(tx);
    // a blocked or slow resolver costs at most this long; the built-in list is the fallback
    let until = Instant::now() + DNS_WAIT;
    let mut out: Vec<IpAddr> = Vec::new();
    while let Ok(found) = rx.recv_timeout(until.saturating_duration_since(Instant::now())) {
        for ip in found {
            // a poisoned answer is often a private address (a block page)
            let usable = match ip {
                IpAddr::V4(v) => !v.is_loopback() && !v.is_unspecified() && !v.is_private() && !v.is_link_local() && !v.is_multicast() && !v.is_broadcast(),
                IpAddr::V6(_) => false,
            };
            if usable && !out.contains(&ip) {
                out.push(ip);
            }
        }
    }
    out.truncate(4);
    out
}

/// The pairs to try: every address with www.google.com, then the first addresses with the other
/// cover names. The remembered one goes first.
pub fn candidates(remembered: Option<&Endpoint>, dns: bool) -> Vec<Endpoint> {
    let mut ips: Vec<IpAddr> = if dns { resolved() } else { Vec::new() };
    for s in IPS {
        let ip: IpAddr = s.parse().expect("listed address");
        if !ips.contains(&ip) {
            ips.push(ip);
        }
    }
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    let mut add = |e: Endpoint, out: &mut Vec<Endpoint>| {
        if seen.insert(format!("{e:?}")) {
            out.push(e);
        }
    };
    if let Some(r) = remembered {
        add(r.clone(), &mut out);
    }
    for ip in &ips {
        add(Endpoint::Front { ip: *ip, sni: SNIS[0].to_string() }, &mut out);
    }
    for ip in ips.iter().take(2) {
        for sni in &SNIS[1..] {
            add(Endpoint::Front { ip: *ip, sni: sni.to_string() }, &mut out);
        }
    }
    out
}

/// Race the candidates; the first to answer wins, with its time. None when none answered.
pub fn race(candidates: Vec<Endpoint>, timeout: Duration) -> Option<(Endpoint, u64)> {
    if candidates.is_empty() {
        return None;
    }
    let (tx, rx) = channel();
    let n = candidates.len().min(MAX_PARALLEL);
    for e in candidates.into_iter().take(n) {
        let tx = tx.clone();
        thread::Builder::new()
            .name("gas-front".into())
            .spawn(move || {
                let r = probe(&e, timeout).map(|ms| (e, ms));
                let _ = tx.send(r);
            })
            .ok()?;
    }
    drop(tx);
    let until = Instant::now() + timeout + Duration::from_secs(1);
    for _ in 0..n {
        let left = until.saturating_duration_since(Instant::now());
        match rx.recv_timeout(left) {
            Ok(Some(found)) => return Some(found),
            Ok(None) => continue,
            Err(_) => break,
        }
    }
    None
}

/// A short label for the screens: `216.239.38.120 · www.google.com`.
pub fn label(e: &Endpoint) -> String {
    match e {
        Endpoint::Front { ip, sni } => format!("{ip} · {sni}"),
        Endpoint::Worker { ip, split, .. } => format!("Cloudflare {ip}{}", if *split { " · split" } else { "" }),
        Endpoint::Plain(a) => format!("local {a}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn candidates_start_with_the_remembered_and_have_no_duplicates() {
        let r = Endpoint::Front { ip: "142.251.32.14".parse().unwrap(), sni: "mail.google.com".into() };
        let c = candidates(Some(&r), false);
        assert_eq!(c[0], r);
        let set: HashSet<String> = c.iter().map(|e| format!("{e:?}")).collect();
        assert_eq!(set.len(), c.len());
        assert!(c.len() >= IPS.len() + 2 * (SNIS.len() - 1));
        assert!(c.len() <= 40);
    }

    #[test]
    fn nothing_answers_on_a_closed_port() {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        drop(l);
        assert!(race(vec![Endpoint::Plain(addr)], Duration::from_millis(500)).is_none());
    }
}
