//! MLM CHAT phase-0 network probe (PLAN.fa.md section 17).
//!
//! The phone app (tools/netprobe) calls [`run_command`] through JNI with one JSON command and
//! gets one JSON result back. The observer (tools/observer) runs [`observer::serve`]. The desktop
//! `probe-cli` runs the same commands, which is how the protocol is exercised on loopback before
//! it ever reaches a phone.
//!
//! Rule T18: no byte leaves the device unencrypted. Every exchange in this crate is either
//! Noise (snow), TLS 1.3 (rustls) or QUIC (quinn). The only exceptions are listed in the README:
//! random hole-punch/probe tokens that carry no data, and the UPnP test, which is opt-in because
//! the UPnP protocol itself has no encryption.

pub mod blenoise;
pub mod card;
pub mod log;
pub mod measure;
pub mod noise;
pub mod obsclient;
pub mod observer;
pub mod session;
pub mod tests_nat;
pub mod tests_peer;
pub mod tests_punch;
pub mod tests_relay;
pub mod tests_upnp;
pub mod tlsutil;
pub mod util;

use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock, Mutex};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
/// Bumped whenever the wire format between phone, phone and observer changes.
pub const PROTO: u32 = 1;

pub static RT: LazyLock<tokio::runtime::Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .thread_name("probe")
        .build()
        .expect("tokio runtime")
});

static CANCEL: AtomicBool = AtomicBool::new(false);
static SESSION: LazyLock<Mutex<Option<Arc<session::Session>>>> = LazyLock::new(|| Mutex::new(None));

pub fn cancel() {
    CANCEL.store(true, Ordering::Relaxed);
}

pub fn cancelled() -> bool {
    CANCEL.load(Ordering::Relaxed)
}

pub fn current_session() -> Option<Arc<session::Session>> {
    SESSION.lock().unwrap().clone()
}

/// One JSON command in, one JSON result out. Blocking: call it from a worker thread.
pub fn run_command(input: &str) -> String {
    CANCEL.store(false, Ordering::Relaxed);
    let v: Value = match serde_json::from_str(input) {
        Ok(v) => v,
        Err(e) => return json!({"ok": false, "error": format!("bad json: {e}")}).to_string(),
    };
    let cmd = v["cmd"].as_str().unwrap_or("").to_string();
    let started = util::now_ms();
    let res = RT.block_on(dispatch(&cmd, &v));
    let mut out = match res {
        Ok(Value::Object(m)) => Value::Object(m),
        Ok(other) => json!({ "result": other }),
        Err(e) => {
            log::log(format!("{cmd}: error: {e:#}"));
            // Error text can quote an address; the report must never hold one.
            json!({ "error": util::scrub(&format!("{e:#}")) })
        }
    };
    out["ok"] = json!(out.get("error").is_none());
    out["cmd"] = json!(cmd);
    out["started_ms"] = json!(started);
    out["took_ms"] = json!(util::now_ms().saturating_sub(started));
    out["probe"] = json!(VERSION);
    out.to_string()
}

async fn dispatch(cmd: &str, v: &Value) -> anyhow::Result<Value> {
    match cmd {
        "version" => Ok(json!({ "version": VERSION, "proto": PROTO })),
        "interfaces" => Ok(json!({ "interfaces": util::interfaces_json() })),
        "make_card" => {
            if let Some(old) = SESSION.lock().unwrap().take() {
                old.shutdown();
            }
            let s = session::Session::start(v).await?;
            let out = s.describe();
            *SESSION.lock().unwrap() = Some(s);
            Ok(out)
        }
        "end_session" => {
            if let Some(old) = SESSION.lock().unwrap().take() {
                old.shutdown();
            }
            Ok(json!({}))
        }
        "inbound" => {
            let s = need_session()?;
            Ok(json!({ "inbound": s.inbound_since(0) }))
        }
        "peer" => tests_peer::run(need_session()?, v).await,
        "punch" => tests_punch::run(need_session()?, v).await,
        "relay" => tests_relay::run(need_session()?, v).await,
        "nat" => tests_nat::run(v).await,
        "nat_lifetime" => tests_nat::lifetime(v).await,
        "obs_speed" => tests_nat::observer_speed(v).await,
        "upnp" => tests_upnp::run(v).await,
        "" => anyhow::bail!("missing cmd"),
        other => anyhow::bail!("unknown cmd {other}"),
    }
}

fn need_session() -> anyhow::Result<Arc<session::Session>> {
    current_session().ok_or_else(|| anyhow::anyhow!("no card yet: run make_card first"))
}
