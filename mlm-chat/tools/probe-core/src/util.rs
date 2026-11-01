use anyhow::{Context, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::RngCore;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use socket2::{Domain, Protocol, Socket, Type};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub fn b64e(b: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(b)
}

pub fn b64d(s: &str) -> Result<Vec<u8>> {
    Ok(URL_SAFE_NO_PAD.decode(s.trim().trim_end_matches('='))?)
}

pub fn rand_bytes<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    rand::thread_rng().fill_bytes(&mut b);
    b
}

pub fn rand_fill(buf: &mut [u8]) {
    rand::thread_rng().fill_bytes(buf);
}

pub fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut h = Sha256::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Milliseconds with one decimal, for reports.
pub fn ms(d: Duration) -> f64 {
    (d.as_secs_f64() * 10_000.0).round() / 10.0
}

/// Megabits per second for `bytes` moved in `d`.
pub fn mbps(bytes: u64, d: Duration) -> f64 {
    let s = d.as_secs_f64().max(1e-6);
    ((bytes as f64 * 8.0 / s / 1e6) * 100.0).round() / 100.0
}

pub fn canon(a: SocketAddr) -> SocketAddr {
    SocketAddr::new(a.ip().to_canonical(), a.port())
}

/// An IPv4 target sent from a dual-stack IPv6 socket has to be written as ::ffff:a.b.c.d.
pub fn for_socket(target: SocketAddr, socket_is_v6: bool) -> SocketAddr {
    match (target, socket_is_v6) {
        (SocketAddr::V4(a), true) => SocketAddr::new(IpAddr::V6(a.ip().to_ipv6_mapped()), a.port()),
        _ => target,
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum IpKind {
    Loopback,
    LinkLocal,
    /// 10/8, 172.16/12, 192.168/16: a home or office LAN (or a carrier that uses them inside).
    Private4,
    /// 100.64/10: the carrier-grade NAT range.
    Cgnat4,
    Public4,
    /// fc00::/7
    Ula6,
    /// 2000::/3
    Global6,
    Other,
}

pub fn ip_kind(ip: IpAddr) -> IpKind {
    match ip.to_canonical() {
        IpAddr::V4(a) => {
            let o = a.octets();
            if a.is_loopback() {
                IpKind::Loopback
            } else if a.is_link_local() {
                IpKind::LinkLocal
            } else if a.is_private() {
                IpKind::Private4
            } else if o[0] == 100 && (o[1] & 0xc0) == 64 {
                IpKind::Cgnat4
            } else if a.is_unspecified() || a.is_broadcast() || a.is_multicast() || o[0] == 0 || o[0] >= 224 {
                IpKind::Other
            } else {
                IpKind::Public4
            }
        }
        IpAddr::V6(a) => {
            let s = a.segments();
            if a.is_loopback() {
                IpKind::Loopback
            } else if (s[0] & 0xffc0) == 0xfe80 {
                IpKind::LinkLocal
            } else if (s[0] & 0xfe00) == 0xfc00 {
                IpKind::Ula6
            } else if (s[0] & 0xe000) == 0x2000 {
                IpKind::Global6
            } else {
                IpKind::Other
            }
        }
    }
}

pub fn kind_label(k: IpKind) -> &'static str {
    match k {
        IpKind::Loopback => "loopback",
        IpKind::LinkLocal => "link-local",
        IpKind::Private4 => "v4-private",
        IpKind::Cgnat4 => "v4-cgnat",
        IpKind::Public4 => "v4-public",
        IpKind::Ula6 => "v6-ula",
        IpKind::Global6 => "v6-global",
        IpKind::Other => "other",
    }
}

/// Reports never carry an IP address (the repository must never hold one). Each distinct
/// address gets a stable label for the lifetime of the process instead, so a report can still
/// say "both observers saw the same public address" without saying which.
static ALIASES: std::sync::LazyLock<Mutex<HashMap<IpAddr, String>>> =
    std::sync::LazyLock::new(|| Mutex::new(HashMap::new()));

pub fn alias(ip: IpAddr) -> String {
    let ip = ip.to_canonical();
    let mut m = ALIASES.lock().unwrap();
    let n = m.len() + 1;
    m.entry(ip)
        .or_insert_with(|| format!("{}#{}", kind_label(ip_kind(ip)), n))
        .clone()
}

pub fn alias_sa(a: SocketAddr) -> String {
    let a = canon(a);
    format!("{}:{}", alias(a.ip()), a.port())
}

pub fn stats(v: &[f64]) -> Value {
    if v.is_empty() {
        return Value::Null;
    }
    let mut s = v.to_vec();
    s.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let med = if s.len() % 2 == 1 {
        s[s.len() / 2]
    } else {
        (s[s.len() / 2 - 1] + s[s.len() / 2]) / 2.0
    };
    json!({ "n": s.len(), "min": s[0], "med": (med * 10.0).round() / 10.0, "max": s[s.len() - 1] })
}

#[derive(Clone, Debug)]
pub struct Iface {
    pub name: String,
    pub ip: IpAddr,
}

/// The device's addresses, without loopback. Uses getifaddrs (bionic has it from API 24).
pub fn interfaces() -> Vec<Iface> {
    let mut out = vec![];
    if let Ok(list) = if_addrs::get_if_addrs() {
        for i in list {
            let ip = i.ip();
            if ip.is_loopback() {
                continue;
            }
            out.push(Iface { name: i.name.clone(), ip });
        }
    }
    out
}

pub fn interfaces_json() -> Value {
    Value::Array(
        interfaces()
            .iter()
            .map(|i| json!({ "if": i.name, "kind": kind_label(ip_kind(i.ip)), "ip": alias(i.ip) }))
            .collect(),
    )
}

/// Desktop self-tests set this to 127.0.0.1 so nothing they open is reachable from the network
/// (and no firewall prompt appears). On a phone it stays unset: bind everywhere.
static TEST_BIND: std::sync::OnceLock<Ipv4Addr> = std::sync::OnceLock::new();

pub fn set_test_bind(ip: Ipv4Addr) {
    let _ = TEST_BIND.set(ip);
}

pub fn test_bind() -> Option<Ipv4Addr> {
    TEST_BIND.get().copied()
}

/// The IPv4 address to bind outgoing and listening sockets to.
pub fn any_v4() -> IpAddr {
    IpAddr::V4(test_bind().unwrap_or(Ipv4Addr::UNSPECIFIED))
}

pub fn any_v4_port(port: u16) -> SocketAddr {
    SocketAddr::new(any_v4(), port)
}

/// A TCP listener on [::] that also takes IPv4 (falls back to 0.0.0.0 without IPv6).
/// `reuse_port` lets outgoing connections share the port, which is how a listener's public
/// mapping is learned and how TCP hole punching works on Linux and Android.
pub fn tcp_listener(port: u16, reuse_port: bool) -> Result<(std::net::TcpListener, bool)> {
    let try_v6 = || -> Result<Socket> {
        if test_bind().is_some() {
            anyhow::bail!("test bind: IPv4 only");
        }
        let s = Socket::new(Domain::IPV6, Type::STREAM, Some(Protocol::TCP))?;
        s.set_only_v6(false)?;
        s.set_reuse_address(true)?;
        set_reuse_port(&s, reuse_port)?;
        s.bind(&SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), port).into())?;
        Ok(s)
    };
    let (s, v6) = match try_v6() {
        Ok(s) => (s, true),
        Err(_) => {
            let s = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
            s.set_reuse_address(true)?;
            set_reuse_port(&s, reuse_port)?;
            s.bind(&any_v4_port(port).into())?;
            (s, false)
        }
    };
    s.listen(128)?;
    s.set_nonblocking(true)?;
    Ok((s.into(), v6))
}

/// A UDP socket on [::] that also takes IPv4 (falls back to 0.0.0.0 without IPv6).
pub fn udp_dual(port: u16) -> Result<(std::net::UdpSocket, bool)> {
    let try_v6 = || -> Result<Socket> {
        if test_bind().is_some() {
            anyhow::bail!("test bind: IPv4 only");
        }
        let s = Socket::new(Domain::IPV6, Type::DGRAM, Some(Protocol::UDP))?;
        s.set_only_v6(false)?;
        s.bind(&SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), port).into())?;
        Ok(s)
    };
    let (s, v6) = match try_v6() {
        Ok(s) => (s, true),
        Err(_) => {
            let s = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
            s.bind(&any_v4_port(port).into())?;
            (s, false)
        }
    };
    s.set_nonblocking(true)?;
    Ok((s.into(), v6))
}

/// A tokio TcpSocket bound to `local` with SO_REUSEADDR (and SO_REUSEPORT where it exists),
/// ready to connect. Several of these can share one local port at the same time.
pub fn reuse_tcp(local: SocketAddr) -> Result<tokio::net::TcpSocket> {
    let s = if local.is_ipv4() {
        tokio::net::TcpSocket::new_v4()?
    } else {
        tokio::net::TcpSocket::new_v6()?
    };
    s.set_reuseaddr(true)?;
    #[cfg(all(unix, not(target_os = "solaris"), not(target_os = "illumos")))]
    s.set_reuseport(true)?;
    s.bind(local).with_context(|| format!("bind {}", local.port()))?;
    Ok(s)
}

#[cfg(unix)]
fn set_reuse_port(s: &Socket, on: bool) -> Result<()> {
    if on {
        s.set_reuse_port(true)?;
    }
    Ok(())
}

#[cfg(not(unix))]
fn set_reuse_port(_s: &Socket, _on: bool) -> Result<()> {
    Ok(())
}

/// The local address the OS would use to reach `target` (a UDP "connect" sends nothing).
pub fn local_ip_towards(target: IpAddr) -> Option<IpAddr> {
    let bind: SocketAddr = if target.is_ipv4() {
        "0.0.0.0:0".parse().unwrap()
    } else {
        "[::]:0".parse().unwrap()
    };
    let s = std::net::UdpSocket::bind(bind).ok()?;
    s.connect(SocketAddr::new(target, 9)).ok()?;
    s.local_addr().ok().map(|a| a.ip())
}

/// Replaces every word that is an IP address or socket address with `<addr>`.
pub fn scrub(s: &str) -> String {
    s.split_whitespace()
        .map(|w| {
            let t = w.trim_matches(|c: char| matches!(c, '(' | ')' | ',' | '"' | '\'' | ';'));
            let inner = t.trim_matches(|c: char| c == '[' || c == ']');
            if t.parse::<SocketAddr>().is_ok() || inner.parse::<IpAddr>().is_ok() {
                "<addr>".to_string()
            } else {
                w.to_string()
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// A short error label for reports: the io error kind when there is one, never an address.
pub fn err_kind(e: &anyhow::Error) -> String {
    for cause in e.chain() {
        if let Some(io) = cause.downcast_ref::<std::io::Error>() {
            return format!("{:?}", io.kind());
        }
        if cause.downcast_ref::<tokio::time::error::Elapsed>().is_some() {
            return "TimedOut".into();
        }
    }
    scrub(&format!("{e:#}")).chars().take(120).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scrub_hides_addresses() {
        let s = scrub("dial 1.2.3.4:443 failed, [2001:db8::1]:80 and (10.0.0.1) too");
        assert!(!s.contains("1.2.3.4") && !s.contains("2001:db8") && !s.contains("10.0.0.1"), "{s}");
        assert!(s.contains("dial") && s.contains("too"));
    }

    #[test]
    fn kinds() {
        let k = |s: &str| ip_kind(s.parse().unwrap());
        assert_eq!(k("100.64.1.1"), IpKind::Cgnat4);
        assert_eq!(k("100.128.0.1"), IpKind::Public4);
        assert_eq!(k("192.168.1.5"), IpKind::Private4);
        assert_eq!(k("2a01:5ec0::1"), IpKind::Global6);
        assert_eq!(k("fd00::1"), IpKind::Ula6);
        assert_eq!(k("fe80::1"), IpKind::LinkLocal);
        assert_eq!(k("::ffff:5.6.7.8"), IpKind::Public4);
    }

    #[test]
    fn alias_is_stable_and_hides_the_address() {
        let a = alias("5.6.7.8".parse().unwrap());
        assert_eq!(a, alias("5.6.7.8".parse().unwrap()));
        assert!(!a.contains("5.6.7.8"));
    }
}
