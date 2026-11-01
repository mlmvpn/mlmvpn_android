//! The two cards of the probe. Both are shown as a QR code (or copied as text) face to face;
//! neither is ever sent over a network by the probe.
//!
//! - Observer card `mlmobs1:...`: the observer's Noise public key, where to reach it, its ports.
//! - Phone card `mlmnp1:...`: one test session of one phone: the TLS certificate pin, the ports,
//!   the phone's candidate addresses, and the BLE Noise key + pre-shared key.
//!
//! These are probe-only formats. They are not the MLM CHAT contact card of SPEC section 3.

use crate::util::{b64d, b64e};
use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::net::{IpAddr, SocketAddr};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ObserverCard {
    pub v: u32,
    #[serde(default)]
    pub name: String,
    /// Noise static public key, base64url.
    pub pk: String,
    /// Addresses phones should use, most useful first (public IPv4, then IPv6, then LAN).
    pub addrs: Vec<String>,
    pub port: u16,
    pub alt: u16,
    /// More TCP ports that answer the same control protocol (reachability per port).
    #[serde(default)]
    pub extra_tcp: Vec<u16>,
    /// The libp2p relay on the same PC (PLAN section 17.2): `{"peer", "port"}`. Read by the
    /// phone's libspike_libp2p.so, not by probe-core.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub p2p: Option<serde_json::Value>,
    /// The iroh relay + hub on the same PC: `{"hub", "port", "pin"}` (pin = SHA-256 of the
    /// relay's TLS certificate). Read by libspike_iroh.so.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub iroh: Option<serde_json::Value>,
}

impl ObserverCard {
    pub const PREFIX: &'static str = "mlmobs1:";

    pub fn encode(&self) -> String {
        format!("{}{}", Self::PREFIX, b64e(&serde_json::to_vec(self).expect("card json")))
    }

    pub fn decode(s: &str) -> Result<Self> {
        let s = s.trim();
        let body = s.strip_prefix(Self::PREFIX).ok_or_else(|| anyhow!("not an observer card"))?;
        let c: ObserverCard = serde_json::from_slice(&b64d(body)?).context("observer card json")?;
        if c.v != 1 {
            bail!("observer card version {} is not supported", c.v);
        }
        c.pk()?;
        if c.addrs.is_empty() {
            bail!("observer card has no address");
        }
        Ok(c)
    }

    pub fn pk(&self) -> Result<Vec<u8>> {
        let k = b64d(&self.pk)?;
        if k.len() != 32 {
            bail!("observer key must be 32 bytes");
        }
        Ok(k)
    }

    fn ips(&self) -> Vec<IpAddr> {
        self.addrs.iter().filter_map(|a| a.trim().parse::<IpAddr>().ok()).collect()
    }

    pub fn v4(&self) -> Option<IpAddr> {
        self.ips().into_iter().find(|i| i.is_ipv4())
    }

    pub fn v6(&self) -> Option<IpAddr> {
        self.ips().into_iter().find(|i| i.is_ipv6())
    }

    /// The address used for IPv4 tests: the first IPv4 one, else whatever is first.
    pub fn primary(&self) -> Result<IpAddr> {
        self.v4().or_else(|| self.ips().into_iter().next()).ok_or_else(|| anyhow!("observer card has no usable address"))
    }

    pub fn main(&self) -> Result<SocketAddr> {
        Ok(SocketAddr::new(self.primary()?, self.port))
    }

    pub fn alt_addr(&self) -> Result<SocketAddr> {
        Ok(SocketAddr::new(self.primary()?, self.alt))
    }

    pub fn label(&self) -> String {
        if self.name.is_empty() {
            "observer".into()
        } else {
            self.name.chars().filter(|c| c.is_alphanumeric() || *c == '-' || *c == '_').take(24).collect()
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Cand {
    /// IP address.
    pub a: String,
    /// Kind label from util::kind_label.
    pub k: String,
    /// Interface name (wlan0, rmnet_data0, tun0, ...).
    #[serde(default)]
    pub i: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PubMap {
    /// Public ip:port an observer saw for the listening port.
    pub a: String,
    /// "tcp" or "udp".
    pub k: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PeerCard {
    pub v: u32,
    /// Session id, 16 hex characters. Fresh for every card.
    pub sid: String,
    /// SHA-256 of this session's TLS certificate, base64url.
    pub fp: String,
    pub tcp: u16,
    pub udp: u16,
    pub ips: Vec<Cand>,
    #[serde(default)]
    pub pubs: Vec<PubMap>,
    /// BLE: Noise static public key and pre-shared key, base64url.
    pub bk: String,
    pub bpsk: String,
}

impl PeerCard {
    pub const PREFIX: &'static str = "mlmnp1:";

    pub fn encode(&self) -> String {
        format!("{}{}", Self::PREFIX, b64e(&serde_json::to_vec(self).expect("card json")))
    }

    pub fn decode(s: &str) -> Result<Self> {
        let s = s.trim();
        let body = s.strip_prefix(Self::PREFIX).ok_or_else(|| anyhow!("not a phone card"))?;
        let c: PeerCard = serde_json::from_slice(&b64d(body)?).context("phone card json")?;
        if c.v != 1 {
            bail!("phone card version {} is not supported", c.v);
        }
        if c.sid.len() != 16 || hex::decode(&c.sid).is_err() {
            bail!("bad session id");
        }
        c.pin()?;
        Ok(c)
    }

    pub fn pin(&self) -> Result<[u8; 32]> {
        b64d(&self.fp)?.try_into().map_err(|_| anyhow!("certificate pin must be 32 bytes"))
    }
}

/// Room id for two sessions: the same on both phones, unknowable without both cards.
pub fn room_id(a: &str, b: &str) -> String {
    let (x, y) = if a < b { (a, b) } else { (b, a) };
    hex::encode(&crate::util::sha256(&[b"mlmnp/room/1", x.as_bytes(), y.as_bytes()])[..12])
}

/// "a" for the session with the smaller id. Role a dials and is the TLS client.
pub fn role(mine: &str, peer: &str) -> &'static str {
    if mine < peer {
        "a"
    } else {
        "b"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cards_round_trip() {
        let o = ObserverCard { v: 1, name: "home".into(), pk: crate::util::b64e(&[1u8; 32]), addrs: vec!["5.6.7.8".into()], port: 40100, alt: 40101, extra_tcp: vec![443], p2p: None, iroh: None };
        let back = ObserverCard::decode(&o.encode()).unwrap();
        assert_eq!(back.main().unwrap().port(), 40100);
        assert!(ObserverCard::decode("mlmnp1:xyz").is_err());
        let p = PeerCard { v: 1, sid: "0123456789abcdef".into(), fp: crate::util::b64e(&[2u8; 32]), tcp: 1, udp: 2, ips: vec![], pubs: vec![], bk: String::new(), bpsk: String::new() };
        assert_eq!(PeerCard::decode(&p.encode()).unwrap().pin().unwrap(), [2u8; 32]);
    }

    #[test]
    fn room_and_role_agree_on_both_phones() {
        assert_eq!(room_id("aa", "bb"), room_id("bb", "aa"));
        assert_ne!(role("aa", "bb"), role("bb", "aa"));
    }
}
