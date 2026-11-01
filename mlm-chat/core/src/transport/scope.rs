//! Where this node may connect (SPEC 15.10): an optional list of address ranges, set by the app
//! (MLMVPN's «inside Iran only»: Iran's IPv4 ranges, so no connection ever leaves the country,
//! and with no IPv6 range IPv6 is off, as in a shutdown). A public address outside the list is
//! never dialed and a connection from one is refused, on every transport. The local network is
//! always allowed (private, carrier-grade NAT `100.64/10`, link-local, unique-local, loopback):
//! it is not "the internet", and it is what still works when everything else is cut.
//!
//! With no list set, everything is allowed (the default).

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct NetScope {
    /// Inclusive ranges, sorted and merged.
    v4: Vec<(u32, u32)>,
    v6: Vec<(u128, u128)>,
}

impl NetScope {
    /// Ranges as `a.b.c.d/n` or `x:y::/n`, one per item; blank lines, `#` comments and anything
    /// that does not parse are skipped.
    pub fn parse<'a>(lines: impl IntoIterator<Item = &'a str>) -> NetScope {
        let mut v4 = Vec::new();
        let mut v6 = Vec::new();
        for line in lines {
            let l = line.trim();
            if l.is_empty() || l.starts_with('#') {
                continue;
            }
            let (ip, len) = match l.split_once('/') {
                Some((ip, len)) => (ip, len.parse::<u8>().ok()),
                None => (l, None),
            };
            match ip.parse::<IpAddr>() {
                Ok(IpAddr::V4(a)) => {
                    let len = len.unwrap_or(32);
                    if len <= 32 {
                        let mask = if len == 0 { 0 } else { u32::MAX << (32 - len) };
                        let start = u32::from(a) & mask;
                        v4.push((start, start | !mask));
                    }
                }
                Ok(IpAddr::V6(a)) => {
                    let len = len.unwrap_or(128);
                    if len <= 128 {
                        let mask = if len == 0 { 0 } else { u128::MAX << (128 - len) };
                        let start = u128::from(a) & mask;
                        v6.push((start, start | !mask));
                    }
                }
                Err(_) => {}
            }
        }
        NetScope { v4: merge(v4), v6: merge(v6) }
    }

    /// Ranges in the list (after merging).
    pub fn len(&self) -> usize {
        self.v4.len() + self.v6.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// May we connect to `ip`, or accept a connection from it?
    pub fn allows(&self, ip: IpAddr) -> bool {
        if is_local(ip) {
            return true;
        }
        match ip {
            IpAddr::V4(a) => within(&self.v4, u32::from(a)),
            IpAddr::V6(a) => match a.to_ipv4_mapped() {
                Some(v4) => self.allows(IpAddr::V4(v4)),
                None => within(&self.v6, u128::from(a)),
            },
        }
    }
}

/// The local network and this device: never "the internet".
pub fn is_local(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v) => local4(v),
        IpAddr::V6(v) => match v.to_ipv4_mapped() {
            Some(m) => local4(m),
            None => local6(v),
        },
    }
}

fn local4(v: Ipv4Addr) -> bool {
    v.is_private() || v.is_loopback() || v.is_link_local() || v.is_unspecified() || (v.octets()[0] == 100 && (v.octets()[1] & 0xc0) == 64)
}

fn local6(v: Ipv6Addr) -> bool {
    v.is_loopback() || v.is_unspecified() || (v.segments()[0] & 0xfe00) == 0xfc00 || (v.segments()[0] & 0xffc0) == 0xfe80
}

fn merge<T: Copy + Ord + num_like::Step>(mut r: Vec<(T, T)>) -> Vec<(T, T)> {
    r.sort();
    let mut out: Vec<(T, T)> = Vec::with_capacity(r.len());
    for (s, e) in r {
        match out.last_mut() {
            Some(last) if s <= last.1.next_or_max() => last.1 = last.1.max(e),
            _ => out.push((s, e)),
        }
    }
    out
}

fn within<T: Copy + Ord>(r: &[(T, T)], x: T) -> bool {
    // the last range that starts at or before x
    let i = r.partition_point(|(s, _)| *s <= x);
    i > 0 && x <= r[i - 1].1
}

mod num_like {
    pub trait Step {
        fn next_or_max(self) -> Self;
    }
    impl Step for u32 {
        fn next_or_max(self) -> Self {
            self.saturating_add(1)
        }
    }
    impl Step for u128 {
        fn next_or_max(self) -> Self {
            self.saturating_add(1)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    #[test]
    fn ranges_and_the_local_network() {
        let s = NetScope::parse(["2.144.0.0/14", "# a comment", "", "5.10.248.0/23", "not a range", "2.146.0.0/16", "22.0.0.0/8"]);
        // 2.146/16 sits inside 2.144/14: merged
        assert_eq!(s.len(), 3);
        assert!(s.allows(ip("2.144.0.1")));
        assert!(s.allows(ip("2.147.255.255")));
        assert!(!s.allows(ip("2.148.0.0")));
        assert!(s.allows(ip("5.10.249.7")));
        assert!(s.allows(ip("22.135.1.2")));
        assert!(!s.allows(ip("203.0.113.7")));
        // the local network is always allowed
        for l in ["192.168.8.6", "10.1.2.3", "172.16.0.9", "100.64.1.1", "127.0.0.1", "169.254.1.1", "fe80::1", "fd00::5", "::1"] {
            assert!(s.allows(ip(l)), "{l}");
        }
        // no IPv6 range: global IPv6 is off
        assert!(!s.allows(ip("2a01:5ec0::1")));
        // an IPv4-mapped IPv6 address counts as its IPv4
        assert!(s.allows(ip("::ffff:2.144.0.9")));
        assert!(!s.allows(ip("::ffff:203.0.113.7")));
    }

    #[test]
    fn ipv6_ranges_and_edges() {
        let s = NetScope::parse(["2a01:5ec0::/29", "0.0.0.0/0"]);
        assert!(s.allows(ip("2a01:5ec0:1::9")));
        assert!(!s.allows(ip("2a02:4540::1")));
        // /0 is everything
        assert!(s.allows(ip("203.0.113.7")));
        let one = NetScope::parse(["198.51.100.4"]);
        assert!(one.allows(ip("198.51.100.4")));
        assert!(!one.allows(ip("198.51.100.5")));
        // touching ranges merge
        assert_eq!(NetScope::parse(["10.0.0.0/8", "11.0.0.0/8"]).len(), 1);
        assert!(NetScope::parse(Vec::<&str>::new()).is_empty());
    }
}
