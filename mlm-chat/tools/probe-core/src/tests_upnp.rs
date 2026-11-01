//! Does the home modem open a port when asked over UPnP (IGD)?
//!
//! The one exception to "everything encrypted": UPnP has no encryption at all, so the SSDP
//! search and the SOAP calls go to the modem in clear, inside the home network. What they carry
//! is only the phone's LAN address, two port numbers and the label "netprobe"; no message,
//! identity or contact data. The test runs only when the user starts it, the mapping is removed
//! at once, and the screen says so before it runs.

use crate::card::ObserverCard;
use crate::obsclient::{collect_tokens, ObsConn};
use crate::util::{alias, b64e, err_kind, ip_kind, kind_label, IpKind};
use crate::plog;
use anyhow::{anyhow, Result};
use igd_next::{aio::tokio::search_gateway, PortMappingProtocol, SearchOptions};
use serde_json::{json, Value};
use std::net::SocketAddr;
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::time::timeout;

pub async fn run(v: &Value) -> Result<Value> {
    let obs: Option<ObserverCard> = v["observers"]
        .as_array()
        .and_then(|a| a.iter().filter_map(|c| c.as_str()).find_map(|c| ObserverCard::decode(c).ok()));
    plog!("upnp: searching for the modem (4 s)");
    let gw = match search_gateway(SearchOptions { timeout: Some(Duration::from_secs(4)), ..Default::default() }).await {
        Ok(g) => g,
        Err(e) => return Ok(json!({ "gateway": false, "error": crate::util::scrub(&e.to_string()) })),
    };
    let mut out = json!({ "gateway": true });
    let ext = match gw.get_external_ip().await {
        Ok(ip) => {
            out["external_kind"] = json!(kind_label(ip_kind(ip)));
            out["external"] = json!(alias(ip));
            // A private or 100.64/10 "external" address means the home line itself sits behind
            // the ISP's NAT: a UPnP mapping then cannot help anyone outside.
            out["home_line_behind_isp_nat"] = json!(matches!(ip_kind(ip), IpKind::Private4 | IpKind::Cgnat4));
            Some(ip)
        }
        Err(e) => {
            out["external_error"] = json!(crate::util::scrub(&e.to_string()));
            None
        }
    };
    let local_ip = crate::util::local_ip_towards(gw.addr.ip()).ok_or_else(|| anyhow!("no local address toward the modem"))?;

    // TCP: a listener, a mapping to it, and (with an observer) a dial from outside.
    let listener = tokio::net::TcpListener::bind(SocketAddr::new(local_ip, 0)).await?;
    let tport = listener.local_addr()?.port();
    let ext_tcp = 20000 + (rand::random::<u16>() % 30000);
    let add_tcp = gw.add_port(PortMappingProtocol::TCP, ext_tcp, SocketAddr::new(local_ip, tport), 120, "netprobe").await;
    out["add_tcp"] = json!(add_tcp.is_ok());
    if let Err(e) = &add_tcp {
        out["add_tcp_error"] = json!(crate::util::scrub(&e.to_string()));
    }

    // UDP the same way.
    let usock = tokio::net::UdpSocket::bind(SocketAddr::new(local_ip, 0)).await?;
    let uport = usock.local_addr()?.port();
    let ext_udp = 20000 + (rand::random::<u16>() % 30000);
    let add_udp = gw.add_port(PortMappingProtocol::UDP, ext_udp, SocketAddr::new(local_ip, uport), 120, "netprobe").await;
    out["add_udp"] = json!(add_udp.is_ok());
    if let Err(e) = &add_udp {
        out["add_udp_error"] = json!(crate::util::scrub(&e.to_string()));
    }

    if let (Some(o), Some(ext)) = (&obs, ext) {
        let cid = hex::encode(crate::util::rand_bytes::<8>());
        let r = async {
            let mut c = ObsConn::connect(o, o.main()?, None, &cid).await?;
            // The observer only writes to addresses this phone came from: the modem's external
            // address is exactly that when the phone reaches the observer through this modem.
            let came_from_ext = c.observed.ip() == ext;
            let mut res = json!({ "observer": o.label(), "observer_saw_external": came_from_ext });
            if add_tcp.is_ok() {
                let token = crate::util::rand_bytes::<16>();
                let target = SocketAddr::new(ext, ext_tcp);
                let accept = tokio::spawn(async move {
                    timeout(Duration::from_secs(6), async {
                        let (mut s, _) = listener.accept().await?;
                        let mut b = [0u8; 16];
                        s.read_exact(&mut b).await?;
                        Ok::<_, anyhow::Error>(b == token)
                    })
                    .await
                    .unwrap_or(Ok(false))
                    .unwrap_or(false)
                });
                let d = c.call(json!({ "t": "tcp_connect", "target": target.to_string(), "token": b64e(&token) })).await;
                res["tcp_from_outside"] = json!(accept.await.unwrap_or(false));
                res["tcp_dial"] = d.map(|d| d["connected"].clone()).unwrap_or_else(|e| json!(err_kind(&e)));
            }
            if add_udp.is_ok() {
                let token = crate::util::rand_bytes::<16>();
                let target = SocketAddr::new(ext, ext_udp);
                let _ = c.call(json!({ "t": "udp_send", "target": target.to_string(), "from": "main", "token": b64e(&token) })).await;
                res["udp_from_outside"] = json!(!collect_tokens(&usock, &[token], Duration::from_secs(3)).await.is_empty());
            }
            let _ = c.ns.send_json(&json!({ "t": "bye" })).await;
            Ok::<_, anyhow::Error>(res)
        }
        .await;
        out["verify"] = r.unwrap_or_else(|e| json!({ "error": err_kind(&e) }));
    } else {
        out["verify"] = json!({ "skipped": "no observer card, or no external address" });
    }

    if add_tcp.is_ok() {
        out["removed_tcp"] = json!(gw.remove_port(PortMappingProtocol::TCP, ext_tcp).await.is_ok());
    }
    if add_udp.is_ok() {
        out["removed_udp"] = json!(gw.remove_port(PortMappingProtocol::UDP, ext_udp).await.is_ok());
    }
    Ok(out)
}
