//! The multiaddr subset the direct transport uses in card `addrs` (SPEC 3): binary multiaddr
//! `/ip4/<a>/tcp/<port>` and `/ip6/<a>/tcp/<port>`. Codes from the multiaddr table: ip4 = 4,
//! ip6 = 41, tcp = 6 (each a one-byte varint). Other multiaddrs are kept but not dialed here.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

const IP4: u8 = 0x04;
const IP6: u8 = 0x29;
const TCP: u8 = 0x06;

pub fn encode(addr: &SocketAddr) -> Vec<u8> {
    let mut out = Vec::with_capacity(21);
    match addr.ip() {
        IpAddr::V4(ip) => {
            out.push(IP4);
            out.extend_from_slice(&ip.octets());
        }
        IpAddr::V6(ip) => {
            out.push(IP6);
            out.extend_from_slice(&ip.octets());
        }
    }
    out.push(TCP);
    out.extend_from_slice(&addr.port().to_be_bytes());
    out
}

pub fn decode(bytes: &[u8]) -> Option<SocketAddr> {
    let (ip, rest): (IpAddr, &[u8]) = match *bytes.first()? {
        IP4 if bytes.len() >= 5 => (IpAddr::V4(Ipv4Addr::from(<[u8; 4]>::try_from(&bytes[1..5]).ok()?)), &bytes[5..]),
        IP6 if bytes.len() >= 17 => (IpAddr::V6(Ipv6Addr::from(<[u8; 16]>::try_from(&bytes[1..17]).ok()?)), &bytes[17..]),
        _ => return None,
    };
    if rest.len() != 3 || rest[0] != TCP {
        return None;
    }
    Some(SocketAddr::new(ip, u16::from_be_bytes([rest[1], rest[2]])))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        for a in ["192.168.1.5:4100", "[2001:db8::1]:443"] {
            let s: SocketAddr = a.parse().unwrap();
            assert_eq!(decode(&encode(&s)), Some(s));
        }
        assert_eq!(hex::encode(encode(&"127.0.0.1:4100".parse().unwrap())), "047f000001061004");
        assert_eq!(decode(&[0x04, 1, 2]), None);
    }
}
