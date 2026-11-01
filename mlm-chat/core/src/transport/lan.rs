//! The device's IPv4 broadcast addresses (SPEC 14.8, 16.5): the same Wi-Fi, a phone's own hotspot,
//! a LAN cable.
//!
//! `255.255.255.255` follows the default route, and a phone that shares its hotspot with no other
//! network has none: its hotspot interface is no Android "network". So every up interface's own
//! subnet broadcast (192.168.43.255 on a hotspot, say) is added; Android routes those through its
//! local-network table for every app.

use std::net::{Ipv4Addr, SocketAddr};

pub const MULTICAST_V4: Ipv4Addr = Ipv4Addr::new(239, 255, 77, 77);

/// The limited broadcast, the multicast group and each interface's subnet broadcast, on `port`.
pub fn broadcast_targets(port: u16) -> Vec<SocketAddr> {
    let mut out = vec![SocketAddr::from((Ipv4Addr::BROADCAST, port)), SocketAddr::from((MULTICAST_V4, port))];
    for (addr, prefix) in interface_v4() {
        if let Some(b) = subnet_broadcast(addr, prefix) {
            let a = SocketAddr::from((b, port));
            if !out.contains(&a) {
                out.push(a);
            }
        }
    }
    out
}

/// The IPv4 address and prefix of every interface that is up and not loopback. On Android 11+
/// an app may not list links (netlink RTM_GETLINK and /sys/class/net are SELinux-denied), but
/// getifaddrs() still gives addresses, so unix systems use it.
#[cfg(unix)]
fn interface_v4() -> Vec<(Ipv4Addr, u8)> {
    let mut out = Vec::new();
    let mut ifap: *mut libc::ifaddrs = std::ptr::null_mut();
    // SAFETY: getifaddrs fills a list freed below with freeifaddrs; every pointer is checked
    // before use and the list is only read.
    unsafe {
        if libc::getifaddrs(&mut ifap) != 0 {
            return out;
        }
        let mut p = ifap;
        while !p.is_null() {
            let ifa = &*p;
            p = ifa.ifa_next;
            let flags = ifa.ifa_flags as libc::c_int;
            if ifa.ifa_addr.is_null() || ifa.ifa_netmask.is_null() || flags & libc::IFF_UP == 0 || flags & libc::IFF_LOOPBACK != 0 {
                continue;
            }
            if libc::c_int::from((*ifa.ifa_addr).sa_family) != libc::AF_INET {
                continue;
            }
            let a = &*(ifa.ifa_addr as *const libc::sockaddr_in);
            let m = &*(ifa.ifa_netmask as *const libc::sockaddr_in);
            let addr = Ipv4Addr::from(u32::from_be(a.sin_addr.s_addr));
            let prefix = u32::from_be(m.sin_addr.s_addr).count_ones() as u8;
            out.push((addr, prefix));
        }
        libc::freeifaddrs(ifap);
    }
    out
}

#[cfg(not(unix))]
fn interface_v4() -> Vec<(Ipv4Addr, u8)> {
    let mut out = Vec::new();
    for i in netdev::get_interfaces() {
        if !i.is_up() || i.is_loopback() {
            continue;
        }
        for n in &i.ipv4 {
            out.push((n.addr(), n.prefix_len()));
        }
    }
    out
}

/// The subnet broadcast of `addr/prefix`; none for loopback, link-local, /0 and /31–/32.
pub fn subnet_broadcast(addr: Ipv4Addr, prefix: u8) -> Option<Ipv4Addr> {
    if prefix == 0 || prefix >= 31 || addr.is_loopback() || addr.is_link_local() || addr.is_unspecified() {
        return None;
    }
    let mask = u32::MAX << (32 - u32::from(prefix));
    Some(Ipv4Addr::from(u32::from(addr) | !mask))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn subnet_broadcasts() {
        assert_eq!(subnet_broadcast(Ipv4Addr::new(192, 168, 43, 17), 24), Some(Ipv4Addr::new(192, 168, 43, 255)));
        assert_eq!(subnet_broadcast(Ipv4Addr::new(10, 146, 9, 1), 20), Some(Ipv4Addr::new(10, 146, 15, 255)));
        assert_eq!(subnet_broadcast(Ipv4Addr::new(127, 0, 0, 1), 8), None);
        assert_eq!(subnet_broadcast(Ipv4Addr::new(169, 254, 3, 4), 16), None);
        assert_eq!(subnet_broadcast(Ipv4Addr::new(192, 168, 1, 2), 32), None);
        let t = broadcast_targets(47731);
        assert_eq!(t[0], SocketAddr::from((Ipv4Addr::BROADCAST, 47731)));
        assert!(t.iter().all(|a| a.port() == 47731));
    }
}
