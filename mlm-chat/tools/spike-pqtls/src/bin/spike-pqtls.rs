//! spike-pqtls [N]: N TLS 1.3 handshakes per key-exchange group, in memory; prints one JSON line.

fn main() {
    let n = std::env::args().nth(1).and_then(|s| s.parse().ok()).unwrap_or(50);
    let out = spike_pqtls::run(n);
    println!("{out}");
    std::process::exit(if out["ok"] == serde_json::json!(true) { 0 } else { 1 });
}
