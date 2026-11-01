//! `/mlmchat/reach/1` (SPEC 15.9): a volunteer asks a peer to dial one of its candidate
//! addresses on a fresh connection, so it learns whether others really reach it there.
//!
//! The answer is only a hint. The proof is on the volunteer's side: an inbound connection that
//! arrives on that address from a network other than its own. So a peer that lies proves
//! nothing, and a peer on the same LAN confirms nothing.
//!
//! Wire: request `[1][len][multiaddr]` (len ≤ 64), answer one byte: 0 connected, 1 could not
//! connect, 2 busy, 3 refused (not an address it will dial). The stream runs inside the TLS and
//! Noise of the libp2p connection like everything else.

use super::Multiaddr;
use libp2p::multiaddr::Protocol;
use std::net::IpAddr;

pub const VERSION: u8 = 1;
pub const MAX_ADDR: usize = 64;
pub const CONNECTED: u8 = 0;
pub const FAILED: u8 = 1;
pub const BUSY: u8 = 2;
pub const REFUSED: u8 = 3;

pub fn request(addr: &Multiaddr) -> Vec<u8> {
    let a = addr.to_vec();
    let mut out = vec![VERSION, a.len() as u8];
    out.extend_from_slice(&a);
    out
}

/// What a helper agrees to dial: TCP with TLS, no relay circuit, and an address that can be
/// reached across the internet (public IPv4 or global IPv6; loopback only in tests). It never
/// dials into a LAN for someone else.
pub fn dialable(a: &Multiaddr, allow_loopback: bool) -> bool {
    let tcp = a.iter().any(|p| matches!(p, Protocol::Tcp(port) if port != 0));
    let tls = a.iter().any(|p| matches!(p, Protocol::Tls));
    let circuit = a.iter().any(|p| matches!(p, Protocol::P2pCircuit));
    let ip = a.iter().find_map(|p| match p {
        Protocol::Ip4(v) => Some(IpAddr::V4(v)),
        Protocol::Ip6(v) => Some(IpAddr::V6(v)),
        _ => None,
    });
    let ok_ip = match ip {
        Some(IpAddr::V4(v)) if v.is_loopback() => allow_loopback,
        Some(IpAddr::V4(v)) => !(v.is_private() || v.is_link_local() || v.is_unspecified() || v.is_multicast() || v.is_broadcast() || (v.octets()[0] == 100 && (v.octets()[1] & 0xc0) == 64)),
        Some(IpAddr::V6(v)) if v.is_loopback() => allow_loopback,
        Some(IpAddr::V6(v)) => v.segments()[0] & 0xe000 == 0x2000,
        None => false,
    };
    tcp && tls && !circuit && ok_ip
}

/// Reads a request's address from its bytes (header already split off by the caller).
pub fn parse(len: u8, body: &[u8]) -> Option<Multiaddr> {
    if len as usize > MAX_ADDR || body.len() != len as usize {
        return None;
    }
    Multiaddr::try_from(body.to_vec()).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_internet_addresses_with_tls() {
        let ok: Multiaddr = "/ip4/203.0.113.7/tcp/40443/tls".parse().unwrap();
        assert!(dialable(&ok, false));
        assert!(dialable(&"/ip6/2001:db8::1/tcp/40443/tls".parse().unwrap(), false));
        assert!(!dialable(&"/ip4/192.168.1.2/tcp/40443/tls".parse().unwrap(), false), "never into someone's LAN");
        assert!(!dialable(&"/ip4/100.64.1.2/tcp/40443/tls".parse().unwrap(), false));
        assert!(!dialable(&"/ip4/203.0.113.7/tcp/40443".parse().unwrap(), false), "TLS only");
        assert!(!dialable(&"/ip6/fe80::1/tcp/40443/tls".parse().unwrap(), false));
        assert!(!dialable(&"/ip4/127.0.0.1/tcp/40443/tls".parse().unwrap(), false));
        assert!(dialable(&"/ip4/127.0.0.1/tcp/40443/tls".parse().unwrap(), true));
        let r = request(&ok);
        assert_eq!(r[0], VERSION);
        assert_eq!(parse(r[1], &r[2..]), Some(ok));
        assert_eq!(parse(70, &[0; 70]), None);
    }
}
