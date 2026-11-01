//! One phone, one or two observers: how does this network treat us?
//!
//! - reach: which observer ports answer at all (TCP control + UDP probe);
//! - UDP and TCP mapping (RFC 4787 terms): does the public port stay the same toward different
//!   destinations? Port preserved? Same public address toward both observers?
//! - UDP filtering: does a packet from an address we never wrote to get in?
//! - TCP inbound: can an observer open a connection to our mapping?
//! - IPv6: global address, outbound, and inbound from the observer's network;
//! - mapping lifetime (separate command, minutes long) and speed to the observer.

use crate::card::ObserverCard;
use crate::obsclient::{collect_tokens, udp_call, ObsConn};
use crate::util::{alias, alias_sa, b64e, err_kind, ip_kind, kind_label, mbps, ms, stats, IpKind};
use crate::plog;
use anyhow::{anyhow, Result};
use serde_json::{json, Value};
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;
use tokio::time::timeout;

fn observers(v: &Value) -> Result<Vec<ObserverCard>> {
    let list: Vec<ObserverCard> = v["observers"]
        .as_array()
        .ok_or_else(|| anyhow!("no observers"))?
        .iter()
        .filter_map(|c| c.as_str())
        .map(ObserverCard::decode)
        .collect::<Result<_>>()?;
    if list.is_empty() {
        return Err(anyhow!("no observers"));
    }
    Ok(list)
}

fn seen(a: &Value) -> Option<SocketAddr> {
    a["observed"].as_str().and_then(|s| s.parse().ok()).map(crate::util::canon)
}

/// Mapping behaviour from the public ports seen by O1:main, O1:alt and O2:main.
fn classify_mapping(local_port: u16, m11: Option<SocketAddr>, m12: Option<SocketAddr>, m21: Option<SocketAddr>, local_ips: &[IpAddr]) -> Value {
    let Some(a) = m11 else { return json!({ "verdict": "unknown", "why": "no answer from the first observer" }) };
    let no_nat = local_ips.contains(&a.ip());
    let mapping = match (m12, m21) {
        (Some(b), Some(c)) if a.port() == b.port() && a.port() == c.port() => "endpoint-independent",
        (Some(b), Some(_)) if a.port() == b.port() => "address-dependent",
        (Some(b), None) if a.port() == b.port() => "endpoint-independent-same-ip-only",
        (Some(_), _) => "address-and-port-dependent",
        (None, Some(c)) if a.port() == c.port() => "endpoint-independent-other-ip-only",
        (None, Some(_)) => "dependent",
        (None, None) => "unknown",
    };
    let pooling = match m21 {
        Some(c) if c.ip() == a.ip() => "paired",
        Some(_) => "arbitrary",
        None => "unknown",
    };
    let deltas: Vec<i64> = [m12, m21].iter().flatten().map(|m| m.port() as i64 - a.port() as i64).collect();
    json!({
        "verdict": if no_nat { "no-nat" } else { mapping },
        "port_preserved": a.port() == local_port,
        "ip_pooling": pooling,
        "public_kind": kind_label(ip_kind(a.ip())),
        "public": alias(a.ip()),
        "port_deltas": deltas,
    })
}

async fn reach(obs: &[ObserverCard], cid: &str) -> Value {
    let mut out = vec![];
    for o in obs {
        let Ok(ip) = o.primary() else { continue };
        let mut ports = vec![o.port, o.alt];
        ports.extend(o.extra_tcp.iter().copied());
        for p in ports {
            let r = ObsConn::connect(o, SocketAddr::new(ip, p), None, cid).await;
            out.push(match r {
                Ok(mut c) => {
                    let _ = c.ns.send_json(&json!({ "t": "bye" })).await;
                    json!({ "observer": o.label(), "proto": "tcp", "port": p, "ok": true, "connect_ms": c.connect_ms, "handshake_ms": c.handshake_ms })
                }
                Err(e) => json!({ "observer": o.label(), "proto": "tcp", "port": p, "ok": false, "error": err_kind(&e) }),
            });
        }
        if let Ok(pk) = o.pk() {
            if let Ok(sock) = UdpSocket::bind(crate::util::any_v4_port(0)).await {
                for p in [o.port, o.alt] {
                    let r = udp_call(&sock, false, &pk, SocketAddr::new(ip, p), &json!({ "t": "map", "cid": cid }), 3, Duration::from_millis(1500)).await;
                    out.push(match r {
                        Ok(a) => json!({ "observer": o.label(), "proto": "udp", "port": p, "ok": true, "rtt_ms": a.rtt_ms, "tries": a.tries }),
                        Err(e) => json!({ "observer": o.label(), "proto": "udp", "port": p, "ok": false, "error": err_kind(&e) }),
                    });
                }
            }
        }
    }
    Value::Array(out)
}

async fn udp_mapping(obs: &[ObserverCard], cid: &str, local_ips: &[IpAddr]) -> Result<Value> {
    let sock = UdpSocket::bind(crate::util::any_v4_port(0)).await?;
    let local_port = sock.local_addr()?.port();
    let o1 = &obs[0];
    let pk1 = o1.pk()?;
    let req = json!({ "t": "map", "cid": cid });
    let each = Duration::from_millis(1500);
    let a11 = udp_call(&sock, false, &pk1, o1.main()?, &req, 3, each).await.ok();
    let a12 = udp_call(&sock, false, &pk1, o1.alt_addr()?, &req, 3, each).await.ok();
    let a21 = match obs.get(1) {
        Some(o2) => udp_call(&sock, false, &o2.pk()?, o2.main()?, &req, 3, each).await.ok(),
        None => None,
    };
    let m = |a: &Option<crate::obsclient::UdpAnswer>| a.as_ref().and_then(|x| seen(&x.answer));
    let (m11, m12, m21) = (m(&a11), m(&a12), m(&a21));
    let mut out = classify_mapping(local_port, m11, m12, m21, local_ips);
    out["ports"] = json!({ "local": local_port, "o1_main": m11.map(|a| a.port()), "o1_alt": m12.map(|a| a.port()), "o2_main": m21.map(|a| a.port()) });
    out["rtt_ms"] = json!(a11.as_ref().map(|a| a.rtt_ms));
    Ok(out)
}

async fn udp_filtering(obs: &[ObserverCard], cid: &str) -> Result<Value> {
    let sock = UdpSocket::bind(crate::util::any_v4_port(0)).await?;
    let o1 = &obs[0];
    let a = udp_call(&sock, false, &o1.pk()?, o1.main()?, &json!({ "t": "map", "cid": cid }), 3, Duration::from_millis(1500)).await?;
    let mapped = seen(&a.answer).ok_or_else(|| anyhow!("no mapping"))?;
    let t_same = crate::util::rand_bytes::<16>();
    let t_alt = crate::util::rand_bytes::<16>();
    let t_o2 = crate::util::rand_bytes::<16>();
    let mut c1 = ObsConn::connect(o1, o1.main()?, None, cid).await?;
    let send = |t: &[u8; 16], from: &str| json!({ "t": "udp_send", "target": mapped.to_string(), "from": from, "token": b64e(t) });
    let r_same = c1.call(send(&t_same, "main")).await;
    let r_alt = c1.call(send(&t_alt, "alt")).await;
    let mut want = vec![t_same, t_alt];
    let mut asked_o2 = false;
    if let Some(o2) = obs.get(1) {
        if let Ok(mut c2) = ObsConn::connect(o2, o2.main()?, None, cid).await {
            asked_o2 = c2.call(send(&t_o2, "main")).await.is_ok();
            want.push(t_o2);
        }
    }
    let got = collect_tokens(&sock, &want, Duration::from_secs(3)).await;
    let (same, alt, other) = (got.contains(&t_same), got.contains(&t_alt), got.contains(&t_o2));
    let verdict = if !same {
        "unknown"
    } else if other {
        "endpoint-independent"
    } else if alt && asked_o2 {
        "address-dependent"
    } else if alt {
        "address-dependent-or-independent"
    } else {
        "address-and-port-dependent"
    };
    Ok(json!({
        "verdict": verdict,
        "same_addr_back": same, "other_port_back": alt, "other_ip_back": if asked_o2 { json!(other) } else { Value::Null },
        "asked": { "same": r_same.is_ok(), "alt": r_alt.is_ok(), "o2": asked_o2 },
    }))
}

/// Three control connections from one local port P; then, where SO_REUSEPORT exists, a listener
/// on P and a dial from an observer to P's mapping.
async fn tcp_mapping(obs: &[ObserverCard], cid: &str, local_ips: &[IpAddr]) -> Result<Value> {
    let o1 = &obs[0];
    let mut c11 = ObsConn::connect(o1, o1.main()?, Some(crate::util::any_v4_port(0)), cid).await?;
    let port = c11.local.port();
    let local: SocketAddr = crate::util::any_v4_port(port);
    let c12 = ObsConn::connect(o1, o1.alt_addr()?, Some(local), cid).await.ok();
    let c21 = match obs.get(1) {
        Some(o2) => ObsConn::connect(o2, o2.main()?, Some(local), cid).await.ok(),
        None => None,
    };
    let m11 = c11.observed;
    let mut out = classify_mapping(port, Some(m11), c12.as_ref().map(|c| c.observed), c21.as_ref().map(|c| c.observed), local_ips);
    out["ports"] = json!({ "local": port, "o1_main": m11.port(), "o1_alt": c12.as_ref().map(|c| c.observed.port()), "o2_main": c21.as_ref().map(|c| c.observed.port()) });
    out["connect_ms"] = json!(c11.connect_ms);

    // Inbound to the mapping. Needs a listener sharing P with the open connections.
    if cfg!(unix) {
        let listener = crate::util::reuse_tcp(local)?.listen(8)?;
        let token = crate::util::rand_bytes::<16>();
        let req = json!({ "t": "tcp_connect", "target": m11.to_string(), "token": b64e(&token) });
        // From the other observer when there is one (a new address), else from O1 on a new port.
        let (by, dial) = match (obs.get(1), c21) {
            (Some(o2), Some(_)) => {
                let mut c = ObsConn::connect(o2, o2.main()?, None, cid).await?;
                ("o2", tokio::spawn(async move { c.call(req).await }))
            }
            _ => {
                let mut c = ObsConn::connect(o1, o1.main()?, None, cid).await?;
                ("o1-new-port", tokio::spawn(async move { c.call(req).await }))
            }
        };
        let accepted = timeout(Duration::from_secs(6), async {
            loop {
                let (mut s, _) = listener.accept().await?;
                let mut b = [0u8; 16];
                use tokio::io::AsyncReadExt;
                if timeout(Duration::from_secs(2), s.read_exact(&mut b)).await.is_ok() && b == token {
                    return Ok::<_, anyhow::Error>(true);
                }
            }
        })
        .await
        .unwrap_or(Ok(false))
        .unwrap_or(false);
        let dialed = dial.await.ok().and_then(|r| r.ok());
        out["inbound"] = json!({ "by": by, "accepted": accepted, "observer_connected": dialed.as_ref().map(|d| d["connected"].clone()) });
    } else {
        out["inbound"] = json!({ "skipped": "needs SO_REUSEPORT (Android/Linux)" });
    }
    let _ = c11.ns.send_json(&json!({ "t": "bye" })).await;
    Ok(out)
}

/// IPv6: a global address, outbound to an observer over IPv6, and inbound from it.
async fn ipv6(obs: &[ObserverCard], cid: &str) -> Value {
    let globals: Vec<IpAddr> = crate::util::interfaces().iter().map(|i| i.ip).filter(|ip| ip_kind(*ip) == IpKind::Global6).collect();
    let mut out = json!({ "global_addresses": globals.len() });
    let Some(o) = obs.iter().find(|o| o.v6().is_some()) else {
        out["observer"] = json!("no observer with an IPv6 address: only the address check ran");
        return out;
    };
    let ip = o.v6().unwrap();
    let r = async {
        let mut c = ObsConn::connect(o, SocketAddr::new(ip, o.port), None, cid).await?;
        let mine = c.observed;
        let mut res = json!({ "observer": o.label(), "outbound_tcp": true, "connect_ms": c.connect_ms,
                              "seen_is_local": globals.contains(&mine.ip()), "seen_kind": kind_label(ip_kind(mine.ip())) });
        // UDP outbound over IPv6.
        let u = UdpSocket::bind("[::]:0").await?;
        let ua = udp_call(&u, true, &o.pk()?, SocketAddr::new(ip, o.port), &json!({ "t": "map", "cid": cid }), 3, Duration::from_millis(1500)).await;
        res["outbound_udp"] = json!(ua.is_ok());
        // Inbound TCP and UDP to the address the observer saw.
        let l = tokio::net::TcpListener::bind("[::]:0").await?;
        let lport = l.local_addr()?.port();
        let tt = crate::util::rand_bytes::<16>();
        let target = SocketAddr::new(mine.ip(), lport);
        let req = json!({ "t": "tcp_connect", "target": target.to_string(), "token": b64e(&tt) });
        let accept = tokio::spawn(async move {
            timeout(Duration::from_secs(6), async {
                let (mut s, _) = l.accept().await?;
                let mut b = [0u8; 16];
                use tokio::io::AsyncReadExt;
                s.read_exact(&mut b).await?;
                Ok::<_, anyhow::Error>(b == tt)
            })
            .await
            .unwrap_or(Ok(false))
            .unwrap_or(false)
        });
        let d = c.call(req).await;
        res["inbound_tcp"] = json!(accept.await.unwrap_or(false));
        res["observer_dial"] = d.map(|d| d["connected"].clone()).unwrap_or(Value::Null);
        let ut = crate::util::rand_bytes::<16>();
        let utarget = SocketAddr::new(mine.ip(), u.local_addr()?.port());
        let _ = c.call(json!({ "t": "udp_send", "target": utarget.to_string(), "from": "main", "token": b64e(&ut) })).await;
        res["inbound_udp"] = json!(!collect_tokens(&u, &[ut], Duration::from_secs(3)).await.is_empty());
        let _ = c.ns.send_json(&json!({ "t": "bye" })).await;
        Ok::<_, anyhow::Error>(res)
    }
    .await;
    match r {
        Ok(v) => {
            for (k, val) in v.as_object().unwrap() {
                out[k] = val.clone();
            }
        }
        Err(e) => {
            out["observer"] = json!(o.label());
            out["outbound_tcp"] = json!(false);
            out["error"] = json!(err_kind(&e));
        }
    }
    out
}

fn section(r: Result<Value>) -> Value {
    r.unwrap_or_else(|e| json!({ "error": err_kind(&e) }))
}

pub async fn run(v: &Value) -> Result<Value> {
    let obs = observers(v)?;
    let cid = hex::encode(crate::util::rand_bytes::<8>());
    let local_ips: Vec<IpAddr> = crate::util::interfaces().iter().map(|i| i.ip).collect();
    plog!("nat: {} observer(s)", obs.len());
    let reach = reach(&obs, &cid).await;
    plog!("nat: udp mapping");
    let udp_map = section(udp_mapping(&obs, &cid, &local_ips).await);
    plog!("nat: udp filtering");
    let udp_filter = section(udp_filtering(&obs, &cid).await);
    plog!("nat: tcp mapping");
    let tcp_map = section(tcp_mapping(&obs, &cid, &local_ips).await);
    plog!("nat: ipv6");
    let v6 = ipv6(&obs, &cid).await;
    Ok(json!({
        "observers": obs.iter().map(|o| o.label()).collect::<Vec<_>>(),
        "reach": reach,
        "udp_mapping": udp_map,
        "udp_filtering": udp_filter,
        "tcp_mapping": tcp_map,
        "ipv6": v6,
    }))
}

/// How long an idle mapping lives. UDP: one socket per step; after T seconds of silence a fresh
/// control connection asks the observer to write to the old mapping. TCP (optional): one idle
/// control connection per step; after T seconds it must still answer a ping. This decides how
/// often a phone must send keepalives, which is battery.
pub async fn lifetime(v: &Value) -> Result<Value> {
    let obs = observers(v)?;
    let o = obs[0].clone();
    let cid = hex::encode(crate::util::rand_bytes::<8>());
    let max = v["max_s"].as_u64().unwrap_or(180).clamp(15, 1800);
    let udp_steps: Vec<u64> = [15, 30, 45, 60, 90, 120, 180, 300].into_iter().filter(|s| *s <= max).collect();
    let tcp_steps: Vec<u64> = if v["tcp"].as_bool().unwrap_or(false) {
        [60, 120, 300, 600, 900, 1200].into_iter().filter(|s| *s <= max).collect()
    } else {
        vec![]
    };
    plog!("lifetime: udp {:?} s, tcp {:?} s", udp_steps, tcp_steps);
    let pk = o.pk()?;
    let mut set = tokio::task::JoinSet::new();
    for t in udp_steps {
        let (o, pk, cid) = (o.clone(), pk.clone(), cid.clone());
        set.spawn(async move {
            let r = async {
                let sock = UdpSocket::bind(crate::util::any_v4_port(0)).await?;
                let a = udp_call(&sock, false, &pk, o.main()?, &json!({ "t": "map", "cid": cid }), 3, Duration::from_millis(1500)).await?;
                let mapped = seen(&a.answer).ok_or_else(|| anyhow!("no mapping"))?;
                tokio::time::sleep(Duration::from_secs(t)).await;
                let token = crate::util::rand_bytes::<16>();
                let mut c = ObsConn::connect(&o, o.main()?, None, &cid).await?;
                c.call(json!({ "t": "udp_send", "target": mapped.to_string(), "from": "main", "token": b64e(&token) })).await?;
                let alive = !collect_tokens(&sock, &[token], Duration::from_secs(3)).await.is_empty();
                Ok::<_, anyhow::Error>(alive)
            }
            .await;
            ("udp", t, r.map_err(|e| err_kind(&e)))
        });
    }
    for t in tcp_steps {
        let (o, cid) = (o.clone(), cid.clone());
        set.spawn(async move {
            let r = async {
                let mut c = ObsConn::connect(&o, o.main()?, None, &cid).await?;
                tokio::time::sleep(Duration::from_secs(t)).await;
                let p = c.call_within(json!({ "t": "ping", "n": t }), Duration::from_secs(10)).await;
                Ok::<_, anyhow::Error>(p.is_ok())
            }
            .await;
            ("tcp", t, r.map_err(|e| err_kind(&e)))
        });
    }
    let mut udp = vec![];
    let mut tcp = vec![];
    while let Some(r) = set.join_next().await {
        let Ok((k, t, r)) = r else { continue };
        let row = match r {
            Ok(alive) => json!({ "idle_s": t, "alive": alive }),
            Err(e) => json!({ "idle_s": t, "alive": Value::Null, "error": e }),
        };
        if k == "udp" { udp.push(row) } else { tcp.push(row) }
    }
    let key = |a: &Value| a["idle_s"].as_u64().unwrap_or(0);
    udp.sort_by_key(key);
    tcp.sort_by_key(key);
    let longest = |rows: &[Value]| rows.iter().filter(|r| r["alive"] == json!(true)).map(key).max();
    let first_dead = |rows: &[Value]| rows.iter().filter(|r| r["alive"] == json!(false)).map(key).min();
    Ok(json!({
        "observer": o.label(),
        "udp": udp, "udp_alive_up_to_s": longest(&udp), "udp_dead_from_s": first_dead(&udp),
        "tcp": tcp, "tcp_alive_up_to_s": longest(&tcp), "tcp_dead_from_s": first_dead(&tcp),
    }))
}

/// Round trips and 1 MiB each way to an observer, inside the Noise control channel.
pub async fn observer_speed(v: &Value) -> Result<Value> {
    let obs = observers(v)?;
    let o = &obs[0];
    let bytes = v["bytes"].as_u64().unwrap_or(crate::measure::DEFAULT_BYTES).min(16 << 20);
    let cid = hex::encode(crate::util::rand_bytes::<8>());
    let mut c = ObsConn::connect(o, o.main()?, None, &cid).await?;
    let mut rtts = vec![];
    for n in 0..10 {
        let t = Instant::now();
        c.call(json!({ "t": "ping", "n": n })).await?;
        rtts.push(ms(t.elapsed()));
    }
    let mut out = json!({ "observer": o.label(), "connect_ms": c.connect_ms, "handshake_ms": c.handshake_ms, "rtt_ms": stats(&rtts) });
    let limit = Duration::from_secs(60 + bytes / 4096);

    let t = Instant::now();
    c.call(json!({ "t": "speed_up", "bytes": bytes })).await?;
    let mut chunk = vec![0u8; 60_000];
    crate::util::rand_fill(&mut chunk);
    let mut left = bytes;
    while left > 0 {
        let k = left.min(chunk.len() as u64) as usize;
        timeout(limit, c.ns.send(&chunk[..k])).await??;
        left -= k as u64;
    }
    let got = timeout(limit, c.ns.recv_json()).await??;
    let up = t.elapsed();
    out["up"] = json!({ "bytes": bytes, "ms": ms(up), "mbps": mbps(bytes, up), "observer_ms": got["ms"] });

    let t = Instant::now();
    c.ns.send_json(&json!({ "t": "speed_down", "bytes": bytes })).await?;
    let hdr = timeout(Duration::from_secs(15), c.ns.recv_json()).await??;
    if hdr["t"].as_str() != Some("data") {
        return Err(anyhow!("observer refused the download"));
    }
    let mut got = 0u64;
    while got < bytes {
        got += timeout(limit, c.ns.recv()).await??.len() as u64;
    }
    let down = t.elapsed();
    out["down"] = json!({ "bytes": bytes, "ms": ms(down), "mbps": mbps(bytes, down) });
    let _ = c.ns.send_json(&json!({ "t": "bye" })).await;
    let _ = alias_sa;
    Ok(out)
}
