//! spike-iroh: the desktop side of the iroh spike.
//!
//!   spike-iroh [loopback]     two iroh endpoints on 127.0.0.1, one message; prints one JSON line
//!   spike-iroh chat-selftest  (feature relay-server) relay + hub on 127.0.0.1 and two `chat`
//!                             runs (the phone code); prints one JSON line
//!   spike-iroh chat CARD_FILE what the phone does, with the observer card in CARD_FILE

use serde_json::json;

#[cfg(feature = "relay-server")]
async fn chat_selftest() -> anyhow::Result<serde_json::Value> {
    use spike_iroh::server;
    let (cert_der, key_der) = server::make_cert()?;
    let seed = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos() as u8).unwrap_or(7);
    let secret: [u8; 32] = std::array::from_fn(|i| (i as u8).wrapping_mul(37).wrapping_add(seed));
    let base = 40390;
    let pin = spike_iroh::cert_pin(&cert_der);
    let hub = server::hub_id(&secret).to_string();
    tokio::spawn(server::serve_relay_and_hub(server::Cfg { secret, cert_der, key_der, base_port: base }));
    tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
    let args = json!({
        "cmd": "chat",
        "room": "selftest",
        "duration_s": 90,
        "obs": { "addrs": ["127.0.0.1"], "iroh": { "hub": hub, "port": base, "pin": b64url(&pin) } },
    });
    let (a, b) = tokio::join!(spike_iroh::chat_run(&args), spike_iroh::chat_run(&args));
    let (a, b) = (a?, b?);
    Ok(json!({ "ok": a["ok"] == json!(true) && b["ok"] == json!(true), "a": a, "b": b }))
}

#[cfg(feature = "relay-server")]
fn b64url(b: &[u8]) -> String {
    const A: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::new();
    for chunk in b.chunks(3) {
        let n = chunk.iter().enumerate().fold(0u32, |acc, (i, &x)| acc | (x as u32) << (16 - 8 * i));
        for i in 0..=chunk.len() {
            out.push(A[((n >> (18 - 6 * i)) & 63) as usize] as char);
        }
    }
    out
}

fn main() {
    let cmd = std::env::args().nth(1).unwrap_or_else(|| "loopback".into());
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("runtime");
    let fail = |e: anyhow::Error| json!({ "ok": false, "error": format!("{e:#}") });
    let out = match cmd.as_str() {
        "loopback" => rt.block_on(spike_iroh::loopback_run()).unwrap_or_else(fail),
        #[cfg(feature = "relay-server")]
        "chat-selftest" => {
            if std::env::var("SPIKE_DEBUG").is_ok() {
                spike_iroh::set_echo(true);
            }
            rt.block_on(chat_selftest()).unwrap_or_else(fail)
        }
        "chat" => {
            spike_iroh::set_echo(true);
            let card = std::env::args().nth(2).unwrap_or_else(|| "observer-card.txt".into());
            match spike_iroh::decode_observer_card(&std::fs::read_to_string(&card).unwrap_or_default()) {
                Ok(obs) => rt.block_on(spike_iroh::chat_run(&json!({ "cmd": "chat", "obs": obs, "duration_s": 90 }))).unwrap_or_else(fail),
                Err(e) => fail(e),
            }
        }
        other => {
            eprintln!("unknown command {other}");
            std::process::exit(2);
        }
    };
    println!("{out}");
    std::process::exit(if out["ok"] == json!(true) { 0 } else { 1 });
}
