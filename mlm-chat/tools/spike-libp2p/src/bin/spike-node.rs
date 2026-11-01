//! spike-node: the desktop side of the libp2p spike.
//!
//!   spike-node loopback        relay + two peers on 127.0.0.1; prints one JSON line
//!   spike-node chat-selftest   relay on 127.0.0.1 + two `chat` runs (the phone code); one JSON line
//!   spike-node chat CARD_FILE  what the phone does, with the observer card in CARD_FILE
//!                              (observer-card.txt); start it on two consoles at once
//!   spike-node peer-id         makes an identity and prints its peer id (smoke test)

use serde_json::{json, Value};

async fn chat_selftest() -> anyhow::Result<Value> {
    let key = spike_libp2p::Keypair::generate_ed25519();
    let peer = key.public().to_peer_id().to_string();
    let port = 40290;
    tokio::spawn(spike_libp2p::serve_relay(key, port, vec!["127.0.0.1".parse()?]));
    tokio::time::sleep(std::time::Duration::from_millis(500)).await;
    let args = json!({
        "cmd": "chat",
        "room": format!("selftest-{}", spike_libp2p::now_ms()),
        "duration_s": 60,
        "obs": { "addrs": ["127.0.0.1"], "p2p": { "peer": peer, "port": port } },
    });
    let (a, b) = tokio::join!(spike_libp2p::chat_run(&args), spike_libp2p::chat_run(&args));
    let (a, b) = (a?, b?);
    let ok = a["ok"] == json!(true) && b["ok"] == json!(true);
    Ok(json!({ "ok": ok, "a": a, "b": b }))
}

/// `mlmobs1:` + base64url(JSON) -> the JSON the phone passes as "obs".
fn decode_card(path: &str) -> anyhow::Result<Value> {
    let text = std::fs::read_to_string(path)?;
    let body = text.trim().strip_prefix("mlmobs1:").ok_or_else(|| anyhow::anyhow!("not an observer card"))?;
    let mut bytes = Vec::new();
    let (mut acc, mut bits) = (0u32, 0);
    for c in body.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'-' => 62,
            b'_' => 63,
            _ => anyhow::bail!("bad card text"),
        } as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            bytes.push((acc >> bits) as u8);
        }
    }
    Ok(serde_json::from_slice(&bytes)?)
}

fn main() {
    let cmd = std::env::args().nth(1).unwrap_or_else(|| "loopback".into());
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("runtime");
    let fail = |e: anyhow::Error| json!({ "ok": false, "error": format!("{e:#}") });
    let out = match cmd.as_str() {
        "loopback" => rt.block_on(spike_libp2p::loopback_run(30)).unwrap_or_else(fail),
        "chat-selftest" => {
            if std::env::var("SPIKE_DEBUG").is_ok() {
                spike_libp2p::set_echo(true);
            }
            rt.block_on(chat_selftest()).unwrap_or_else(fail)
        }
        "chat" => {
            spike_libp2p::set_echo(true);
            let card = std::env::args().nth(2).unwrap_or_else(|| "observer-card.txt".into());
            match decode_card(&card) {
                Ok(obs) => rt.block_on(spike_libp2p::chat_run(&json!({ "cmd": "chat", "obs": obs, "duration_s": 90 }))).unwrap_or_else(fail),
                Err(e) => fail(e),
            }
        }
        "peer-id" => json!({ "ok": true, "peer_id": spike_libp2p::fresh_peer_id().to_string() }),
        other => {
            eprintln!("unknown command {other}: use loopback, chat-selftest or peer-id");
            std::process::exit(2);
        }
    };
    println!("{out}");
    std::process::exit(if out["ok"] == json!(true) { 0 } else { 1 });
}
