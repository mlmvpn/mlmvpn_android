//! A volunteer opens its own door on the router, automatically and only for its own port
//! (SPEC 15.9): no user ever configures a router.
//!
//! - UPnP IGD v1/v2: a TCP port mapping for IPv4 (`WANIPConnection` / `WANPPPConnection`), and
//!   on IGD v2 an IPv6 pinhole (`WANIPv6FirewallControl`) to this device's own global address.
//! - NAT-PMP (RFC 6886) for IPv4, PCP MAP (RFC 6887) for IPv4 and IPv6.
//!
//! Every lease is at most [`LEASE_S`] (2 hours), renewed at half time while the volunteer runs,
//! and deleted when it stops. A permanent mapping is never asked for. What this opens is only a
//! candidate: the p2p transport announces an address once a peer on another network has really
//! connected to it (`reach`). Nothing here logs an address: methods and result kinds only.

use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV6, TcpStream, UdpSocket};
use std::time::{Duration, Instant};

/// Longest lease asked for, in seconds.
pub const LEASE_S: u32 = 7200;
const DESCRIPTION: &str = "mlmchat";
const HTTP_TIMEOUT: Duration = Duration::from_secs(3);
const SSDP_WAIT: Duration = Duration::from_millis(1500);
/// NAT-PMP and PCP: three tries, 250 ms, 500 ms, 1 s (RFC 6886 starts at 250 ms; we stop early).
const UDP_TRIES: [u64; 3] = [250, 500, 1000];
const PORT_PCP: u16 = 5351;

/// One network interface with a router behind it.
#[derive(Clone, Debug, Default)]
pub struct Gateway {
    /// Our IPv4 on this interface and the router's.
    pub local4: Option<Ipv4Addr>,
    pub gw4: Option<Ipv4Addr>,
    /// Our global IPv6 addresses here, and the router's (with the interface index for link-local).
    pub local6: Vec<Ipv6Addr>,
    pub gw6: Vec<(Ipv6Addr, u32)>,
}

/// The routers of this device: interfaces that are up, have a gateway and are not loopback.
pub fn gateways() -> Vec<Gateway> {
    let mut out = Vec::new();
    for i in netdev::get_interfaces() {
        if !i.is_up() || i.is_loopback() {
            continue;
        }
        let Some(g) = i.gateway.as_ref() else { continue };
        let local4 = i.ipv4.iter().map(|n| n.addr()).find(|a| a.is_private());
        let local6: Vec<Ipv6Addr> = i.ipv6.iter().map(|n| n.addr()).filter(|a| global6(a)).collect();
        let gw4 = g.ipv4.iter().copied().find(|a| a.is_private());
        let gw6: Vec<(Ipv6Addr, u32)> = g.ipv6.iter().map(|a| (*a, i.index)).collect();
        if (local4.is_some() && gw4.is_some()) || (!local6.is_empty() && !gw6.is_empty()) {
            out.push(Gateway { local4, gw4, local6, gw6 });
        }
    }
    out
}

pub fn global6(a: &Ipv6Addr) -> bool {
    a.segments()[0] & 0xe000 == 0x2000
}

/// What one method did, for the status line: `method` is `upnp4`, `pinhole6`, `natpmp`, `pcp4`
/// or `pcp6`; `result` is `opened`, `no_router`, `no_answer`, `unsupported`, `refused`,
/// `permanent_only`, `not_allowed` or `firewall_off`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct MethodResult {
    pub method: &'static str,
    pub result: &'static str,
}

/// A door that is open now.
#[derive(Clone, Debug)]
pub struct Opening {
    pub method: &'static str,
    /// Where others dial us: the router's external address and port (IPv4), or our own global
    /// address (an IPv6 pinhole).
    pub external: SocketAddr,
    pub internal_port: u16,
    renew_at: Instant,
    undo: Undo,
}

#[derive(Clone, Debug)]
enum Undo {
    Igd4 { control: String, service: String, local: Ipv4Addr, ext_port: u16 },
    Pinhole { control: String, service: String, id: String },
    NatPmp { gw: SocketAddr },
    Pcp { gw: SocketAddr, client: IpAddr, nonce: [u8; 12], ext: SocketAddr },
}

/// The doors one volunteer opened for one port.
pub struct Door {
    pub port: u16,
    pub openings: Vec<Opening>,
    pub results: Vec<MethodResult>,
}

impl Door {
    /// Tries every method on every router, each with short timeouts (a few seconds in all).
    pub fn open(port: u16, gws: &[Gateway]) -> Door {
        let mut door = Door { port, openings: Vec::new(), results: Vec::new() };
        let tell = |door: &mut Door, method: &'static str, r: Result<Vec<Opening>, &'static str>| match r {
            Ok(o) if !o.is_empty() => {
                door.openings.extend(o);
                door.results.push(MethodResult { method, result: "opened" });
            }
            Ok(_) => door.results.push(MethodResult { method, result: "unsupported" }),
            Err(e) => door.results.push(MethodResult { method, result: e }),
        };
        // UPnP first: the most common on home routers, and the IGD v2 pinhole lives there
        let mut igd = Vec::new();
        for g in gws {
            if let Some(l) = g.local4 {
                igd.extend(ssdp_search(l).into_iter().map(|loc| (loc, g.clone())));
            }
        }
        if igd.is_empty() {
            door.results.push(MethodResult { method: "upnp4", result: "no_router" });
        }
        let mut pinhole_tried = false;
        for (loc, g) in &igd {
            let services = describe(loc).unwrap_or_default();
            let wan = services.iter().find(|(s, _)| s.contains(":WANIPConnection:") || s.contains(":WANPPPConnection:"));
            if let (Some((service, control)), Some(local)) = (wan, g.local4) {
                tell(&mut door, "upnp4", igd_map4(control, service, local, port).map(|o| vec![o]));
            }
            if let Some((service, control)) = services.iter().find(|(s, _)| s.contains(":WANIPv6FirewallControl:")) {
                pinhole_tried = true;
                let r = g.local6.iter().find_map(|a| igd_pinhole(control, service, *a, port).ok());
                match r {
                    Some(o) => tell(&mut door, "pinhole6", Ok(vec![o])),
                    None => tell(&mut door, "pinhole6", igd_pinhole_status(control, service)),
                }
            }
        }
        if !pinhole_tried && gws.iter().any(|g| !g.local6.is_empty()) {
            door.results.push(MethodResult { method: "pinhole6", result: "unsupported" });
        }
        // PCP, and NAT-PMP when PCP does not answer (a NAT-PMP-only router says "unsupported
        // version"); only where UPnP opened nothing for the same family
        for g in gws {
            if let (Some(local), Some(gw4)) = (g.local4, g.gw4) {
                if !door.openings.iter().any(|o| o.external.is_ipv4()) {
                    let gw = SocketAddr::new(IpAddr::V4(gw4), PORT_PCP);
                    match pcp_map(gw, IpAddr::V4(local), port, LEASE_S) {
                        Ok(o) => tell(&mut door, "pcp4", Ok(vec![o])),
                        Err(e) => {
                            tell(&mut door, "pcp4", Err(if e == "unsupported_version" { "unsupported" } else { e }));
                            tell(&mut door, "natpmp", natpmp_map(gw, port, LEASE_S).map(|o| vec![o]));
                        }
                    }
                }
            }
            if door.openings.iter().any(|o| o.external.is_ipv6()) {
                continue;
            }
            for local in g.local6.iter().take(1) {
                let r = g.gw6.iter().find_map(|(gw, scope)| {
                    let scope = if gw.segments()[0] & 0xffc0 == 0xfe80 { *scope } else { 0 };
                    match pcp_map(SocketAddr::V6(SocketAddrV6::new(*gw, PORT_PCP, 0, scope)), IpAddr::V6(*local), port, LEASE_S) {
                        Ok(o) => Some(Ok(o)),
                        Err("no_answer") => None,
                        Err("unsupported_version") => Some(Err("unsupported")),
                        Err(e) => Some(Err(e)),
                    }
                });
                tell(&mut door, "pcp6", r.unwrap_or(Err("no_answer")).map(|o| vec![o]));
            }
        }
        // one result per method: the best one wins
        door.results = summarize(&door.results);
        for r in &door.results {
            log::info!("p2p: door {} {}", r.method, r.result);
        }
        door
    }

    /// Renews every lease that is past half its time. Returns the doors that could not be
    /// renewed (they are dropped).
    pub fn renew(&mut self) -> Vec<Opening> {
        let now = Instant::now();
        let mut lost = Vec::new();
        let mut kept = Vec::new();
        for o in self.openings.drain(..) {
            if o.renew_at > now {
                kept.push(o);
                continue;
            }
            let again = match &o.undo {
                Undo::Igd4 { control, service, local, ext_port } => add_port_mapping(control, service, *local, o.internal_port, *ext_port, LEASE_S).map(|_| ()),
                Undo::Pinhole { control, service, id } => {
                    soap(control, service, "UpdatePinhole", &format!("<UniqueID>{id}</UniqueID><NewLeaseTime>{LEASE_S}</NewLeaseTime>")).map(|_| ())
                }
                Undo::NatPmp { gw } => natpmp_request(*gw, o.internal_port, o.external.port(), LEASE_S).map(|_| ()),
                Undo::Pcp { gw, client, nonce, ext } => pcp_request(*gw, *client, *nonce, o.internal_port, Some(*ext), LEASE_S).map(|_| ()),
            };
            match again {
                Ok(()) => kept.push(Opening { renew_at: now + Duration::from_secs(LEASE_S as u64 / 2), ..o }),
                Err(_) => {
                    log::info!("p2p: door {} not renewed", o.method);
                    lost.push(o);
                }
            }
        }
        self.openings = kept;
        lost
    }

    /// Deletes every mapping and pinhole (best effort: the leases end by themselves anyway).
    pub fn close(&mut self) {
        for o in self.openings.drain(..) {
            let r = match &o.undo {
                Undo::Igd4 { control, service, ext_port, .. } => soap(
                    control,
                    service,
                    "DeletePortMapping",
                    &format!("<NewRemoteHost></NewRemoteHost><NewExternalPort>{ext_port}</NewExternalPort><NewProtocol>TCP</NewProtocol>"),
                )
                .map(|_| ()),
                Undo::Pinhole { control, service, id } => soap(control, service, "DeletePinhole", &format!("<UniqueID>{id}</UniqueID>")).map(|_| ()),
                Undo::NatPmp { gw } => natpmp_request(*gw, o.internal_port, 0, 0).map(|_| ()),
                Undo::Pcp { gw, client, nonce, ext } => pcp_request(*gw, *client, *nonce, o.internal_port, Some(*ext), 0).map(|_| ()),
            };
            log::info!("p2p: door {} {}", o.method, if r.is_ok() { "closed" } else { "left to expire" });
        }
    }
}

impl Drop for Door {
    fn drop(&mut self) {
        self.close();
    }
}

/// What the routers say, without opening anything (the «suitable for a volunteer?» check, SPEC
/// 15.11): which method could open a door, and the router's external IPv4.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize)]
pub struct Probe {
    /// `upnp4`, `natpmp` or empty: the method that would open the door.
    pub method: &'static str,
    pub external4: Option<Ipv4Addr>,
    /// An IGD v2 router that could open an IPv6 pinhole.
    pub pinhole6: bool,
}

/// Asks only read-only questions: UPnP `GetExternalIPAddress` and NAT-PMP opcode 0. A couple of
/// seconds at most.
pub fn probe(gws: &[Gateway]) -> Probe {
    let mut p = Probe::default();
    for g in gws {
        let Some(local) = g.local4 else { continue };
        for loc in ssdp_search(local) {
            let services = describe(&loc).unwrap_or_default();
            if services.iter().any(|(s, _)| s.contains(":WANIPv6FirewallControl:")) {
                p.pinhole6 = true;
            }
            let wan = services.iter().find(|(s, _)| s.contains(":WANIPConnection:") || s.contains(":WANPPPConnection:"));
            if let Some((service, control)) = wan {
                if p.method.is_empty() {
                    p.method = "upnp4";
                }
                if p.external4.is_none() {
                    p.external4 = soap(control, service, "GetExternalIPAddress", "").ok().and_then(|b| tag(&b, "NewExternalIPAddress")).and_then(|s| s.parse().ok());
                }
            }
        }
        if p.method.is_empty() {
            if let Some(gw4) = g.gw4 {
                let gw = SocketAddr::new(IpAddr::V4(gw4), PORT_PCP);
                let ext = udp_ask(gw, &[0, 0], |r| r.len() >= 12 && r[0] == 0 && r[1] == 128).ok().filter(|r| u16::from_be_bytes([r[2], r[3]]) == 0);
                if let Some(r) = ext {
                    p.method = "natpmp";
                    p.external4 = Some(Ipv4Addr::new(r[8], r[9], r[10], r[11]));
                }
            }
        }
    }
    p
}

/// The operators' own NAT in front of a router: RFC 6598 shared space, and `22.0.0.0/8`, where
/// both Hamrah-e Aval and Irancell number their carrier NAT (measured 2026-10-22, PHASE3 §2). A
/// router whose external address is in here cannot be reached from another network.
pub fn carrier_nat(v: Ipv4Addr) -> bool {
    let o = v.octets();
    (o[0] == 100 && (o[1] & 0xc0) == 64) || o[0] == 22
}

fn summarize(all: &[MethodResult]) -> Vec<MethodResult> {
    let rank = |r: &str| match r {
        "opened" => 9,
        "firewall_off" => 8,
        "refused" | "permanent_only" | "not_allowed" => 6,
        "unsupported" => 4,
        "no_answer" => 3,
        _ => 1,
    };
    let mut out: Vec<MethodResult> = Vec::new();
    for r in all {
        match out.iter_mut().find(|o| o.method == r.method) {
            Some(o) if rank(r.result) > rank(o.result) => o.result = r.result,
            Some(_) => {}
            None => out.push(r.clone()),
        }
    }
    out
}

// ---- UPnP IGD ----

/// SSDP search from one local IPv4 address: the description URLs of Internet gateways.
pub fn ssdp_search(local: Ipv4Addr) -> Vec<String> {
    let Ok(s) = UdpSocket::bind(SocketAddr::new(IpAddr::V4(local), 0)) else { return Vec::new() };
    let _ = s.set_multicast_ttl_v4(2);
    let _ = s.set_read_timeout(Some(Duration::from_millis(300)));
    let mut out: Vec<String> = Vec::new();
    for st in ["urn:schemas-upnp-org:device:InternetGatewayDevice:2", "urn:schemas-upnp-org:device:InternetGatewayDevice:1"] {
        let msg = format!("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: {st}\r\n\r\n");
        let _ = s.send_to(msg.as_bytes(), "239.255.255.250:1900");
    }
    let end = Instant::now() + SSDP_WAIT;
    let mut buf = [0u8; 2048];
    while Instant::now() < end {
        let Ok((n, _)) = s.recv_from(&mut buf) else { continue };
        let text = String::from_utf8_lossy(&buf[..n]);
        if let Some(loc) = header(&text, "location") {
            if loc.starts_with("http://") && !out.contains(&loc) {
                out.push(loc);
            }
        }
    }
    out
}

fn header(text: &str, name: &str) -> Option<String> {
    text.lines().find_map(|l| {
        let (k, v) = l.split_once(':')?;
        k.trim().eq_ignore_ascii_case(name).then(|| v.trim().to_string())
    })
}

/// The services of a gateway's description: (service type, absolute control URL).
pub fn describe(location: &str) -> Result<Vec<(String, String)>, &'static str> {
    let (status, body) = http("GET", location, &[], b"")?;
    if status != 200 {
        return Err("no_answer");
    }
    let xml = String::from_utf8_lossy(&body).to_string();
    let base = tag(&xml, "URLBase").unwrap_or_else(|| origin(location).to_string());
    let mut out = Vec::new();
    let mut rest = xml.as_str();
    while let Some(i) = rest.find("<service>") {
        let after = &rest[i + "<service>".len()..];
        let Some(end) = after.find("</service>") else { break };
        let block = &after[..end];
        if let (Some(st), Some(cu)) = (tag(block, "serviceType"), tag(block, "controlURL")) {
            let url = if cu.starts_with("http://") { cu } else { format!("{}/{}", base.trim_end_matches('/'), cu.trim_start_matches('/')) };
            out.push((st, url));
        }
        rest = &after[end + "</service>".len()..];
    }
    Ok(out)
}

fn origin(url: &str) -> &str {
    let after = url.find("://").map(|i| i + 3).unwrap_or(0);
    match url[after..].find('/') {
        Some(j) => &url[..after + j],
        None => url,
    }
}

fn tag(xml: &str, name: &str) -> Option<String> {
    let open = format!("<{name}>");
    let i = xml.find(&open)? + open.len();
    let j = xml[i..].find(&format!("</{name}>"))? + i;
    Some(xml[i..j].trim().to_string())
}

/// A TCP port mapping for IPv4, at most `LEASE_S`. A taken port moves to the next few.
pub fn igd_map4(control: &str, service: &str, local: Ipv4Addr, port: u16) -> Result<Opening, &'static str> {
    let ext_ip = soap(control, service, "GetExternalIPAddress", "").ok().and_then(|b| tag(&b, "NewExternalIPAddress")).and_then(|s| s.parse::<Ipv4Addr>().ok());
    let mut last = "refused";
    for ext_port in [port, port.wrapping_add(1), port.wrapping_add(2)] {
        match add_port_mapping(control, service, local, port, ext_port, LEASE_S) {
            Ok(()) => {
                let Some(ip) = ext_ip else { return Err("no_answer") };
                return Ok(Opening {
                    method: "upnp4",
                    external: SocketAddr::new(IpAddr::V4(ip), ext_port),
                    internal_port: port,
                    renew_at: Instant::now() + Duration::from_secs(LEASE_S as u64 / 2),
                    undo: Undo::Igd4 { control: control.into(), service: service.into(), local, ext_port },
                });
            }
            Err(e) if e == "conflict" => continue,
            Err(e) => {
                last = e;
                break;
            }
        }
    }
    Err(last)
}

fn add_port_mapping(control: &str, service: &str, local: Ipv4Addr, port: u16, ext_port: u16, lease: u32) -> Result<(), &'static str> {
    let args = format!(
        "<NewRemoteHost></NewRemoteHost><NewExternalPort>{ext_port}</NewExternalPort><NewProtocol>TCP</NewProtocol>\
         <NewInternalPort>{port}</NewInternalPort><NewInternalClient>{local}</NewInternalClient><NewEnabled>1</NewEnabled>\
         <NewPortMappingDescription>{DESCRIPTION}</NewPortMappingDescription><NewLeaseDuration>{lease}</NewLeaseDuration>"
    );
    soap(control, service, "AddPortMapping", &args).map(|_| ())
}

/// An IPv6 pinhole (IGD v2) for TCP to our own global address and port, at most `LEASE_S`.
pub fn igd_pinhole(control: &str, service: &str, local: Ipv6Addr, port: u16) -> Result<Opening, &'static str> {
    let args = format!(
        "<RemoteHost></RemoteHost><RemotePort>0</RemotePort><InternalClient>{local}</InternalClient>\
         <InternalPort>{port}</InternalPort><Protocol>6</Protocol><LeaseTime>{LEASE_S}</LeaseTime>"
    );
    let body = soap(control, service, "AddPinhole", &args)?;
    let id = tag(&body, "UniqueID").ok_or("refused")?;
    Ok(Opening {
        method: "pinhole6",
        external: SocketAddr::new(IpAddr::V6(local), port),
        internal_port: port,
        renew_at: Instant::now() + Duration::from_secs(LEASE_S as u64 / 2),
        undo: Undo::Pinhole { control: control.into(), service: service.into(), id },
    })
}

/// Why no pinhole: the router's own answer, read-only.
fn igd_pinhole_status(control: &str, service: &str) -> Result<Vec<Opening>, &'static str> {
    let body = soap(control, service, "GetFirewallStatus", "")?;
    if tag(&body, "FirewallEnabled").as_deref() == Some("0") {
        return Err("firewall_off");
    }
    if tag(&body, "InboundPinholeAllowed").as_deref() == Some("0") {
        return Err("not_allowed");
    }
    Err("refused")
}

/// One SOAP call; the body on success. UPnP error codes become result kinds.
pub fn soap(control: &str, service: &str, action: &str, args: &str) -> Result<String, &'static str> {
    let body = format!(
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" \
         s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:{action} xmlns:u=\"{service}\">{args}</u:{action}></s:Body></s:Envelope>"
    );
    let action_h = format!("\"{service}#{action}\"");
    let (status, resp) = http("POST", control, &[("Content-Type", "text/xml; charset=\"utf-8\""), ("SOAPAction", &action_h)], body.as_bytes())?;
    let text = String::from_utf8_lossy(&resp).to_string();
    if status == 200 {
        return Ok(text);
    }
    Err(match tag(&text, "errorCode").and_then(|c| c.parse::<u32>().ok()) {
        Some(718) => "conflict",
        Some(725) => "permanent_only",
        Some(401) | Some(602) | Some(705) => "unsupported",
        Some(702) => "firewall_off",
        Some(606) | Some(703) | Some(729) => "not_allowed",
        Some(_) => "refused",
        None => "no_answer",
    })
}

/// A tiny HTTP/1.1 client for the router on the LAN: no proxy, short timeouts, `Connection:
/// close`, chunked bodies understood.
fn http(method: &str, url: &str, headers: &[(&str, &str)], body: &[u8]) -> Result<(u16, Vec<u8>), &'static str> {
    let rest = url.strip_prefix("http://").ok_or("unsupported")?;
    let (hostport, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let addr: SocketAddr = if hostport.contains(':') { hostport.parse().map_err(|_| "unsupported")? } else { format!("{hostport}:80").parse().map_err(|_| "unsupported")? };
    let mut s = TcpStream::connect_timeout(&addr, HTTP_TIMEOUT).map_err(|_| "no_answer")?;
    let _ = s.set_read_timeout(Some(HTTP_TIMEOUT));
    let _ = s.set_write_timeout(Some(HTTP_TIMEOUT));
    let mut req = format!("{method} {path} HTTP/1.1\r\nHost: {hostport}\r\nConnection: close\r\nContent-Length: {}\r\n", body.len());
    for (k, v) in headers {
        req.push_str(&format!("{k}: {v}\r\n"));
    }
    req.push_str("\r\n");
    s.write_all(req.as_bytes()).map_err(|_| "no_answer")?;
    s.write_all(body).map_err(|_| "no_answer")?;
    let mut raw = Vec::new();
    let mut buf = [0u8; 4096];
    loop {
        match s.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                raw.extend_from_slice(&buf[..n]);
                if raw.len() > 256 * 1024 {
                    return Err("refused");
                }
            }
            Err(_) if !raw.is_empty() => break,
            Err(_) => return Err("no_answer"),
        }
    }
    let split = raw.windows(4).position(|w| w == b"\r\n\r\n").ok_or("no_answer")?;
    let head = String::from_utf8_lossy(&raw[..split]).to_string();
    let status: u16 = head.split_whitespace().nth(1).and_then(|c| c.parse().ok()).ok_or("no_answer")?;
    let mut body = raw[split + 4..].to_vec();
    if header(&head, "transfer-encoding").is_some_and(|v| v.eq_ignore_ascii_case("chunked")) {
        body = dechunk(&body);
    }
    Ok((status, body))
}

fn dechunk(b: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        let Some(nl) = b[i..].windows(2).position(|w| w == b"\r\n") else { break };
        let size = usize::from_str_radix(String::from_utf8_lossy(&b[i..i + nl]).split(';').next().unwrap_or("").trim(), 16).unwrap_or(0);
        i += nl + 2;
        if size == 0 || i + size > b.len() {
            break;
        }
        out.extend_from_slice(&b[i..i + size]);
        i += size + 2;
    }
    out
}

// ---- NAT-PMP (RFC 6886) ----

/// A TCP mapping through NAT-PMP, at most `LEASE_S`.
pub fn natpmp_map(gw: SocketAddr, port: u16, lease: u32) -> Result<Opening, &'static str> {
    // the external address first (opcode 0)
    let ext = udp_ask(gw, &[0, 0], |r| r.len() >= 12 && r[0] == 0 && r[1] == 128).and_then(|r| {
        if u16::from_be_bytes([r[2], r[3]]) != 0 {
            return Err("refused");
        }
        Ok(Ipv4Addr::new(r[8], r[9], r[10], r[11]))
    })?;
    let ext_port = natpmp_request(gw, port, port, lease)?;
    Ok(Opening {
        method: "natpmp",
        external: SocketAddr::new(IpAddr::V4(ext), ext_port),
        internal_port: port,
        renew_at: Instant::now() + Duration::from_secs(lease as u64 / 2),
        undo: Undo::NatPmp { gw },
    })
}

/// Opcode 2 (TCP): map `internal` asking for `suggested`; lifetime 0 deletes. Returns the port.
fn natpmp_request(gw: SocketAddr, internal: u16, suggested: u16, lease: u32) -> Result<u16, &'static str> {
    let mut req = vec![0u8, 2, 0, 0];
    req.extend_from_slice(&internal.to_be_bytes());
    req.extend_from_slice(&suggested.to_be_bytes());
    req.extend_from_slice(&lease.to_be_bytes());
    let r = udp_ask(gw, &req, |r| r.len() >= 16 && r[0] == 0 && r[1] == 130)?;
    match u16::from_be_bytes([r[2], r[3]]) {
        0 => Ok(u16::from_be_bytes([r[10], r[11]])),
        1 => Err("unsupported"),
        2 => Err("not_allowed"),
        _ => Err("refused"),
    }
}

// ---- PCP (RFC 6887) ----

/// A MAP for TCP: on IPv4 a port mapping on the router's address, on IPv6 a pinhole to our own
/// address. At most `lease` seconds.
pub fn pcp_map(gw: SocketAddr, client: IpAddr, port: u16, lease: u32) -> Result<Opening, &'static str> {
    let nonce: [u8; 12] = crate::util::random();
    let ext = pcp_request(gw, client, nonce, port, None, lease)?;
    Ok(Opening {
        method: if client.is_ipv4() { "pcp4" } else { "pcp6" },
        external: ext,
        internal_port: port,
        renew_at: Instant::now() + Duration::from_secs(lease as u64 / 2),
        undo: Undo::Pcp { gw, client, nonce, ext },
    })
}

fn ip16(a: IpAddr) -> [u8; 16] {
    match a {
        IpAddr::V4(v) => v.to_ipv6_mapped().octets(),
        IpAddr::V6(v) => v.octets(),
    }
}

fn pcp_request(gw: SocketAddr, client: IpAddr, nonce: [u8; 12], port: u16, ext: Option<SocketAddr>, lease: u32) -> Result<SocketAddr, &'static str> {
    let mut req = vec![2u8, 1, 0, 0];
    req.extend_from_slice(&lease.to_be_bytes());
    req.extend_from_slice(&ip16(client));
    req.extend_from_slice(&nonce);
    req.extend_from_slice(&[6, 0, 0, 0]);
    req.extend_from_slice(&port.to_be_bytes());
    let (sug_port, sug_ip) = match ext {
        Some(e) => (e.port(), ip16(e.ip())),
        None => (port, if client.is_ipv4() { ip16(IpAddr::V4(Ipv4Addr::UNSPECIFIED)) } else { [0; 16] }),
    };
    req.extend_from_slice(&sug_port.to_be_bytes());
    req.extend_from_slice(&sug_ip);
    let r = udp_ask(gw, &req, |r| r.len() >= 4 && (r[0] == 0 || (r[0] == 2 && r[1] == 0x81)))?;
    if r[0] == 0 {
        return Err("unsupported_version"); // a NAT-PMP-only router
    }
    if r.len() < 60 || r[24..36] != nonce {
        return Err("refused");
    }
    match r[3] {
        0 => {}
        1 => return Err("unsupported_version"),
        2 => return Err("not_allowed"),
        5 | 6 => return Err("unsupported"),
        _ => return Err("refused"),
    }
    let ext_port = u16::from_be_bytes([r[42], r[43]]);
    let ip = Ipv6Addr::from(<[u8; 16]>::try_from(&r[44..60]).map_err(|_| "refused")?);
    let ip = match ip.to_ipv4_mapped() {
        Some(v4) => IpAddr::V4(v4),
        None => IpAddr::V6(ip),
    };
    Ok(SocketAddr::new(ip, ext_port))
}

/// Ask a router over UDP, with a few quick tries; `ok` says which reply is the answer.
fn udp_ask(gw: SocketAddr, req: &[u8], ok: impl Fn(&[u8]) -> bool) -> Result<Vec<u8>, &'static str> {
    let bind: SocketAddr = if gw.is_ipv4() { "0.0.0.0:0".parse().expect("addr") } else { "[::]:0".parse().expect("addr") };
    let s = UdpSocket::bind(bind).map_err(|_| "no_answer")?;
    let mut buf = [0u8; 1100];
    for wait in UDP_TRIES {
        if s.send_to(req, gw).is_err() {
            return Err("no_answer");
        }
        let _ = s.set_read_timeout(Some(Duration::from_millis(wait)));
        let end = Instant::now() + Duration::from_millis(wait);
        while Instant::now() < end {
            match s.recv_from(&mut buf) {
                Ok((n, from)) if from.ip() == gw.ip() && ok(&buf[..n]) => return Ok(buf[..n].to_vec()),
                Ok(_) => continue,
                // ICMP port unreachable: nothing listens there
                Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset || e.kind() == std::io::ErrorKind::ConnectionRefused => return Err("no_answer"),
                Err(_) => break,
            }
        }
    }
    Err("no_answer")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::{Arc, Mutex};

    /// A fake router speaking NAT-PMP or PCP on loopback; records what it was asked.
    fn fake_udp(pcp: bool) -> (SocketAddr, Arc<Mutex<Vec<Vec<u8>>>>) {
        let s = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = s.local_addr().unwrap();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let seen2 = seen.clone();
        std::thread::spawn(move || {
            let mut buf = [0u8; 1100];
            while let Ok((n, from)) = s.recv_from(&mut buf) {
                let q = buf[..n].to_vec();
                seen2.lock().unwrap().push(q.clone());
                let reply = if q[0] == 2 && pcp {
                    let mut r = vec![2u8, 0x81, 0, 0];
                    r.extend_from_slice(&q[4..8]); // lifetime
                    r.extend_from_slice(&[0, 0, 0, 7]); // epoch
                    r.extend_from_slice(&[0; 12]);
                    r.extend_from_slice(&q[24..42]); // nonce, protocol, reserved, internal port
                    r.extend_from_slice(&[0x9c, 0x4b]); // assigned port 40011
                    r.extend_from_slice(&Ipv4Addr::new(203, 0, 113, 9).to_ipv6_mapped().octets());
                    r
                } else if q[0] == 2 {
                    vec![0, 128 + 1, 0, 1, 0, 0, 0, 0] // NAT-PMP-only: unsupported version
                } else if q[1] == 0 {
                    vec![0, 128, 0, 0, 0, 0, 0, 7, 203, 0, 113, 9]
                } else {
                    let mut r = vec![0, 130, 0, 0, 0, 0, 0, 7];
                    r.extend_from_slice(&q[4..6]);
                    r.extend_from_slice(&q[6..8]);
                    r.extend_from_slice(&q[8..12]);
                    r
                };
                let _ = s.send_to(&reply, from);
            }
        });
        (addr, seen)
    }

    #[test]
    fn natpmp_maps_and_deletes() {
        let (gw, seen) = fake_udp(false);
        let o = natpmp_map(gw, 40443, LEASE_S).unwrap();
        assert_eq!(o.external, "203.0.113.9:40443".parse().unwrap());
        // the lease asked for is 2 hours at most
        let asked = seen.lock().unwrap().last().unwrap().clone();
        assert_eq!(u32::from_be_bytes(asked[8..12].try_into().unwrap()), LEASE_S);
        let mut door = Door { port: 40443, openings: vec![o], results: Vec::new() };
        door.close();
        // the fake router's thread records the delete on its own time: under a full parallel
        // test run that can come a moment after close() returns
        let lifetime = |q: &Vec<u8>| u32::from_be_bytes(q[8..12].try_into().unwrap());
        let end = Instant::now() + Duration::from_secs(2);
        let del = loop {
            let last = seen.lock().unwrap().last().unwrap().clone();
            if lifetime(&last) == 0 || Instant::now() > end {
                break last;
            }
            std::thread::sleep(Duration::from_millis(20));
        };
        assert_eq!(&del[..2], &[0, 2]);
        assert_eq!(lifetime(&del), 0, "lifetime 0 deletes");
    }

    #[test]
    fn pcp_maps_and_a_natpmp_router_says_so() {
        let (gw, seen) = fake_udp(true);
        let o = pcp_map(gw, "192.168.1.5".parse().unwrap(), 40443, LEASE_S).unwrap();
        assert_eq!(o.external, "203.0.113.9:40011".parse().unwrap());
        assert_eq!(o.method, "pcp4");
        let q = seen.lock().unwrap()[0].clone();
        assert_eq!(q.len(), 60);
        assert_eq!(q[36], 6, "TCP");
        assert_eq!(u32::from_be_bytes(q[4..8].try_into().unwrap()), LEASE_S);
        let mut door = Door { port: 40443, openings: vec![o], results: Vec::new() };
        door.close();
        let del = seen.lock().unwrap().last().unwrap().clone();
        assert_eq!(u32::from_be_bytes(del[4..8].try_into().unwrap()), 0);
        assert_eq!(del[24..36], q[24..36], "the same nonce deletes");
        let (gw, _) = fake_udp(false);
        assert_eq!(pcp_map(gw, "192.168.1.5".parse().unwrap(), 40443, LEASE_S).err(), Some("unsupported_version"));
    }

    #[test]
    fn nothing_listening_is_no_answer() {
        let s = UdpSocket::bind("127.0.0.1:0").unwrap();
        let gw = s.local_addr().unwrap();
        drop(s);
        assert_eq!(natpmp_map(gw, 1, LEASE_S).err(), Some("no_answer"));
    }

    /// A fake IGD v2 over HTTP on loopback: description, port mapping and pinhole.
    fn fake_igd() -> (String, Arc<Mutex<Vec<String>>>) {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", l.local_addr().unwrap());
        let seen = Arc::new(Mutex::new(Vec::new()));
        let seen2 = seen.clone();
        std::thread::spawn(move || {
            for c in l.incoming() {
                let Ok(mut c) = c else { continue };
                let mut req = Vec::new();
                let mut buf = [0u8; 4096];
                c.set_read_timeout(Some(Duration::from_millis(300))).unwrap();
                while let Ok(n) = c.read(&mut buf) {
                    if n == 0 {
                        break;
                    }
                    req.extend_from_slice(&buf[..n]);
                    let t = String::from_utf8_lossy(&req);
                    if t.starts_with("GET") && t.contains("\r\n\r\n") || t.contains("</s:Envelope>") {
                        break;
                    }
                }
                let t = String::from_utf8_lossy(&req).to_string();
                seen2.lock().unwrap().push(t.clone());
                let body = if t.starts_with("GET") {
                    "<root><device><serviceList><service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:2</serviceType><controlURL>/ctl/IP</controlURL></service>\
                     <service><serviceType>urn:schemas-upnp-org:service:WANIPv6FirewallControl:1</serviceType><controlURL>/ctl/6</controlURL></service></serviceList></device></root>"
                        .to_string()
                } else if t.contains("GetExternalIPAddress") {
                    "<s:Envelope><s:Body><u:R><NewExternalIPAddress>203.0.113.9</NewExternalIPAddress></u:R></s:Body></s:Envelope>".to_string()
                } else if t.contains("AddPinhole") {
                    "<s:Envelope><s:Body><u:R><UniqueID>17</UniqueID></u:R></s:Body></s:Envelope>".to_string()
                } else if t.contains("<NewExternalPort>40443</NewExternalPort>") && t.contains("AddPortMapping") {
                    let fault = "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>718</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
                    let _ = c.write_all(format!("HTTP/1.1 500 Internal Server Error\r\nContent-Length: {}\r\n\r\n{fault}", fault.len()).as_bytes());
                    continue;
                } else {
                    "<s:Envelope><s:Body><u:R/></s:Body></s:Envelope>".to_string()
                };
                // chunked, as some routers answer
                let _ = c.write_all(format!("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n{:x}\r\n{body}\r\n0\r\n\r\n", body.len()).as_bytes());
            }
        });
        (format!("{base}/desc.xml"), seen)
    }

    #[test]
    fn igd_maps_a_taken_port_elsewhere_and_opens_a_pinhole() {
        let (loc, seen) = fake_igd();
        let services = describe(&loc).unwrap();
        assert_eq!(services.len(), 2);
        let (st, ctl) = &services[0];
        assert!(ctl.ends_with("/ctl/IP"));
        let o = igd_map4(ctl, st, "192.168.1.5".parse().unwrap(), 40443).unwrap();
        // 40443 was taken on the router: the next port
        assert_eq!(o.external, "203.0.113.9:40444".parse().unwrap());
        let (st6, ctl6) = &services[1];
        let p = igd_pinhole(ctl6, st6, "2001:db8::5".parse().unwrap(), 40443).unwrap();
        assert_eq!(p.external, "[2001:db8::5]:40443".parse().unwrap());
        let mut door = Door { port: 40443, openings: vec![o, p], results: Vec::new() };
        door.close();
        let all = seen.lock().unwrap().join("\n");
        assert!(all.contains(&format!("<NewLeaseDuration>{LEASE_S}</NewLeaseDuration>")));
        assert!(all.contains(&format!("<LeaseTime>{LEASE_S}</LeaseTime>")));
        assert!(all.contains("<InternalPort>40443</InternalPort><Protocol>6</Protocol>"), "one TCP port only");
        assert!(all.contains("DeletePortMapping") && all.contains("<NewExternalPort>40444</NewExternalPort><NewProtocol>TCP</NewProtocol>"));
        assert!(all.contains("DeletePinhole") && all.contains("<UniqueID>17</UniqueID>"));
    }

    #[test]
    fn results_keep_the_best_per_method() {
        let r = summarize(&[
            MethodResult { method: "pcp6", result: "no_answer" },
            MethodResult { method: "pcp6", result: "opened" },
            MethodResult { method: "natpmp", result: "no_answer" },
        ]);
        assert_eq!(r, vec![MethodResult { method: "pcp6", result: "opened" }, MethodResult { method: "natpmp", result: "no_answer" }]);
    }
}
