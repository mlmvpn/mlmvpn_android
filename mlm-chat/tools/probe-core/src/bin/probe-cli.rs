//! Desktop runner for the probe.
//!
//!   probe-cli selftest [--long]   two observers and two "phones" in this process, all on
//!                                 127.0.0.1/127.0.0.2: every command end to end, nothing leaves
//!                                 the machine.
//!   probe-cli run '<json>'        one command, exactly as the phone sends it (or run @cmd.json).

use probe_core::card::ObserverCard;
use probe_core::observer::{spawn_local, ObserverCfg};
use probe_core::session::Session;
use probe_core::util::b64e;
use probe_core::{tests_nat, tests_peer, tests_punch, tests_relay, RT};
use serde_json::{json, Value};
use std::net::{IpAddr, Ipv4Addr};

fn main() {
    probe_core::log::set_echo(true);
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(|s| s.as_str()) {
        Some("run") => {
            // `run @file.json` reads the command from a file (shell quoting of JSON is fragile).
            let arg = args.get(1).cloned().unwrap_or_default();
            let input = match arg.strip_prefix('@') {
                Some(path) => std::fs::read_to_string(path).unwrap_or_default(),
                None => arg,
            };
            println!("{}", probe_core::run_command(&input));
        }
        Some("selftest") => {
            let long = args.iter().any(|a| a == "--long");
            let ok = RT.block_on(selftest(long));
            std::process::exit(if ok { 0 } else { 1 });
        }
        _ => {
            eprintln!("usage: probe-cli selftest [--long] | probe-cli run '<json>'");
            std::process::exit(2);
        }
    }
}

async fn observer(ip: Ipv4Addr, port: u16, extra: Vec<u16>, name: &str) -> anyhow::Result<String> {
    let (sk, pk) = probe_core::noise::keypair()?;
    let mut cfg = ObserverCfg::new(sk);
    cfg.bind = IpAddr::V4(ip);
    cfg.port = port;
    cfg.alt = port + 1;
    cfg.extra_tcp = extra.clone();
    spawn_local(cfg).await?;
    let card = ObserverCard { v: 1, name: name.into(), pk: b64e(&pk), addrs: vec![ip.to_string()], port, alt: port + 1, extra_tcp: extra, p2p: None, iroh: None };
    Ok(card.encode())
}

fn check(name: &str, ok: bool, detail: &Value, fails: &mut Vec<String>) {
    println!("{} {name}", if ok { "PASS" } else { "FAIL" });
    if !ok {
        println!("     {}", detail);
        fails.push(name.to_string());
    }
}

async fn selftest(long: bool) -> bool {
    probe_core::util::set_test_bind(Ipv4Addr::LOCALHOST);
    let mut fails = vec![];
    let o1 = observer(Ipv4Addr::new(127, 0, 0, 1), 41100, vec![41102], "obs-one").await.expect("observer 1");
    let o2 = observer(Ipv4Addr::new(127, 0, 0, 2), 41110, vec![], "obs-two").await.expect("observer 2");

    // NAT behaviour against two observers.
    let nat = tests_nat::run(&json!({ "observers": [o1, o2] })).await.unwrap_or_else(|e| json!({ "error": e.to_string() }));
    let reach_ok = nat["reach"].as_array().map(|a| a.iter().all(|r| r["ok"] == json!(true))).unwrap_or(false);
    check("nat: every observer port answers (tcp + udp)", reach_ok, &nat["reach"], &mut fails);
    check("nat: udp mapping classified", nat["udp_mapping"]["verdict"].is_string() && nat["udp_mapping"]["error"].is_null(), &nat["udp_mapping"], &mut fails);
    check("nat: udp filtering: token from the other observer arrives (loopback has no NAT)", nat["udp_filtering"]["verdict"] == json!("endpoint-independent"), &nat["udp_filtering"], &mut fails);
    check("nat: tcp mapping from one port to three observer ports", nat["tcp_mapping"]["ports"]["o2_main"].is_number(), &nat["tcp_mapping"], &mut fails);

    let speed = tests_nat::observer_speed(&json!({ "observers": [o1], "bytes": 1 << 20 })).await.unwrap_or_else(|e| json!({ "error": e.to_string() }));
    check("observer speed: 1 MiB up and down inside Noise", speed["up"]["mbps"].is_number() && speed["down"]["mbps"].is_number(), &speed, &mut fails);

    // Two phones.
    let params = json!({ "observers": [o1], "only_ips": ["127.0.0.1"] });
    let a = Session::start(&params).await.expect("session a");
    let b = Session::start(&params).await.expect("session b");
    let (ca, cb) = (a.card_text.clone(), b.card_text.clone());

    let (qa, qb) = (json!({ "peer": cb, "duration_s": 12, "bytes": 262144 }), json!({ "peer": ca, "duration_s": 12, "bytes": 262144 }));
    let (ra, rb) = tokio::join!(tests_peer::run(a.clone(), &qa), tests_peer::run(b.clone(), &qb));
    let ra = ra.unwrap_or_else(|e| json!({ "error": e.to_string() }));
    let rb = rb.unwrap_or_else(|e| json!({ "error": e.to_string() }));
    let all_ok = |r: &Value| r["outbound"].as_array().map(|o| !o.is_empty() && o.iter().all(|p| p["ok"] == json!(true))).unwrap_or(false);
    check("direct: a -> b over TLS/TCP and QUIC", all_ok(&ra), &ra, &mut fails);
    check("direct: b -> a over TLS/TCP and QUIC", all_ok(&rb), &rb, &mut fails);
    let inbound_n = ra["inbound"].as_array().map(|v| v.len()).unwrap_or(0);
    check("direct: a recorded b's inbound connections", inbound_n >= 2, &ra["inbound"], &mut fails);

    let (qa, qb) = (
        json!({ "peer": cb, "observer": o1, "bytes": 262144, "window_s": 8 }),
        json!({ "peer": ca, "observer": o1, "bytes": 262144, "window_s": 8 }),
    );
    let (pa, pb) = tokio::join!(tests_punch::run(a.clone(), &qa), tests_punch::run(b.clone(), &qb));
    let pa = pa.unwrap_or_else(|e| json!({ "error": e.to_string() }));
    let pb = pb.unwrap_or_else(|e| json!({ "error": e.to_string() }));
    check("punch: udp (quic) on both sides", pa["udp"]["ok"] == json!(true) && pb["udp"]["ok"] == json!(true), &json!([pa["udp"], pb["udp"]]), &mut fails);
    check("punch: tcp on both sides", pa["tcp"]["ok"] == json!(true) && pb["tcp"]["ok"] == json!(true), &json!([pa["tcp"], pb["tcp"]]), &mut fails);

    let (qa, qb) = (json!({ "peer": cb, "observer": o1, "bytes": 262144 }), json!({ "peer": ca, "observer": o1, "bytes": 262144 }));
    let (la, lb) = tokio::join!(tests_relay::run(a.clone(), &qa), tests_relay::run(b.clone(), &qb));
    let la = la.unwrap_or_else(|e| json!({ "error": e.to_string() }));
    let lb = lb.unwrap_or_else(|e| json!({ "error": e.to_string() }));
    check("relay: TLS end to end through the observer", la["ok"] == json!(true) && lb["ok"] == json!(true), &json!([la, lb]), &mut fails);

    if long {
        let lt = tests_nat::lifetime(&json!({ "observers": [o1], "max_s": 30, "tcp": false })).await.unwrap_or_else(|e| json!({ "error": e.to_string() }));
        check("lifetime: udp mapping still alive after 30 s on loopback", lt["udp_alive_up_to_s"] == json!(30), &lt, &mut fails);
    }

    a.shutdown();
    b.shutdown();
    println!("\nsample results:\n{}", serde_json::to_string_pretty(&json!({ "nat": nat, "observer_speed": speed, "direct_a": ra["outbound"][0], "punch_a": pa, "relay_a": la })).unwrap());
    if fails.is_empty() {
        println!("\nALL PASS");
        true
    } else {
        println!("\n{} FAILED: {:?}", fails.len(), fails);
        false
    }
}
