//! PLAN section 17.3: what the hybrid post-quantum key exchange costs on a phone.
//!
//! Full TLS 1.3 handshakes between a client and a server in the same process, in memory (no
//! network): once with X25519 alone and once with X25519MLKEM768 (the hybrid the plan proposes
//! for the HTTPS-like path). Per group: bytes each side sends (the ClientHello on its own, since
//! it is the first and most visible packet), and CPU time per handshake.

use rustls::crypto::{aws_lc_rs, CryptoProvider, SupportedKxGroup};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName};
use rustls::{ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection};
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Instant;

const HOST: &str = "mlmchat.test";

fn provider(kx: &'static dyn SupportedKxGroup) -> Arc<CryptoProvider> {
    let mut p = aws_lc_rs::default_provider();
    p.kx_groups = vec![kx];
    Arc::new(p)
}

fn configs(kx: &'static dyn SupportedKxGroup) -> Result<(Arc<ClientConfig>, Arc<ServerConfig>), String> {
    let ck = rcgen::generate_simple_self_signed(vec![HOST.to_string()]).map_err(|e| e.to_string())?;
    let cert = CertificateDer::from(ck.cert.der().to_vec());
    let key = PrivateKeyDer::try_from(ck.key_pair.serialize_der()).map_err(|e| e.to_string())?;
    let mut roots = RootCertStore::empty();
    roots.add(cert.clone()).map_err(|e| e.to_string())?;
    let p = provider(kx);
    let client = ClientConfig::builder_with_provider(p.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| e.to_string())?
        .with_root_certificates(roots)
        .with_no_client_auth();
    let server = ServerConfig::builder_with_provider(p)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| e.to_string())?
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)
        .map_err(|e| e.to_string())?;
    Ok((Arc::new(client), Arc::new(server)))
}

struct Flight {
    c2s: usize,
    s2c: usize,
    client_hello: usize,
}

fn one_handshake(c: &Arc<ClientConfig>, s: &Arc<ServerConfig>) -> Result<Flight, String> {
    let mut client = ClientConnection::new(c.clone(), ServerName::try_from(HOST).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    let mut server = ServerConnection::new(s.clone()).map_err(|e| e.to_string())?;
    let (mut c2s, mut s2c, mut client_hello) = (0usize, 0usize, 0usize);
    let mut buf = Vec::with_capacity(16 * 1024);
    for round in 0..10 {
        buf.clear();
        while client.wants_write() {
            client.write_tls(&mut buf).map_err(|e| e.to_string())?;
        }
        if round == 0 {
            client_hello = buf.len();
        }
        c2s += buf.len();
        let mut rd = &buf[..];
        while !rd.is_empty() {
            server.read_tls(&mut rd).map_err(|e| e.to_string())?;
        }
        server.process_new_packets().map_err(|e| e.to_string())?;
        buf.clear();
        while server.wants_write() {
            server.write_tls(&mut buf).map_err(|e| e.to_string())?;
        }
        s2c += buf.len();
        let mut rd = &buf[..];
        while !rd.is_empty() {
            client.read_tls(&mut rd).map_err(|e| e.to_string())?;
        }
        client.process_new_packets().map_err(|e| e.to_string())?;
        if !client.is_handshaking() && !server.is_handshaking() && !client.wants_write() {
            return Ok(Flight { c2s, s2c, client_hello });
        }
    }
    Err("handshake did not finish".into())
}

fn measure(name: &str, kx: &'static dyn SupportedKxGroup, n: usize) -> Value {
    let (c, s) = match configs(kx) {
        Ok(x) => x,
        Err(e) => return json!({ "group": name, "ok": false, "error": e }),
    };
    // One warm-up handshake (lazy tables, first allocations), then n timed ones.
    let first = match one_handshake(&c, &s) {
        Ok(f) => f,
        Err(e) => return json!({ "group": name, "ok": false, "error": e }),
    };
    let mut times: Vec<f64> = Vec::with_capacity(n);
    for _ in 0..n {
        let t = Instant::now();
        if let Err(e) = one_handshake(&c, &s) {
            return json!({ "group": name, "ok": false, "error": e });
        }
        times.push(t.elapsed().as_secs_f64() * 1000.0);
    }
    times.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let round = |v: f64| (v * 100.0).round() / 100.0;
    json!({
        "group": name,
        "ok": true,
        "client_hello_bytes": first.client_hello,
        "client_to_server_bytes": first.c2s,
        "server_to_client_bytes": first.s2c,
        "handshakes": n,
        "ms_median": round(times[n / 2]),
        "ms_p90": round(times[n * 9 / 10]),
    })
}

pub fn run(n: usize) -> Value {
    let n = n.clamp(5, 500);
    let t0 = Instant::now();
    let classic = measure("X25519", aws_lc_rs::kx_group::X25519, n);
    let hybrid = measure("X25519MLKEM768", aws_lc_rs::kx_group::X25519MLKEM768, n);
    let mut out = json!({
        "ok": classic["ok"] == json!(true) && hybrid["ok"] == json!(true),
        "provider": "rustls 0.23 + aws-lc-rs",
        "note": "full TLS 1.3 handshakes in memory, client and server in this process (both sides' CPU); ECDSA P-256 certificate",
        "classic": classic.clone(),
        "hybrid": hybrid.clone(),
    });
    if out["ok"] == json!(true) {
        let extra = |k: &str| hybrid[k].as_i64().unwrap_or(0) - classic[k].as_i64().unwrap_or(0);
        let (ch, srv) = (extra("client_hello_bytes"), extra("server_to_client_bytes"));
        let (h, c) = (hybrid["ms_median"].as_f64().unwrap_or(0.0), classic["ms_median"].as_f64().unwrap_or(0.0));
        out["hybrid_extra_client_hello_bytes"] = json!(ch);
        out["hybrid_extra_server_bytes"] = json!(srv);
        out["hybrid_extra_ms_median"] = json!(((h - c) * 100.0).round() / 100.0);
    }
    out["took_ms"] = json!(t0.elapsed().as_millis() as u64);
    out
}

/// JNI entry (class com.mlmchat.spike.PqTls, `static native String run(int handshakes)`).
#[no_mangle]
pub extern "system" fn Java_com_mlmchat_spike_PqTls_run<'l>(env: jni::JNIEnv<'l>, _c: jni::objects::JClass<'l>, n: jni::sys::jint) -> jni::sys::jstring {
    let out = std::panic::catch_unwind(|| run(n.max(0) as usize).to_string()).unwrap_or_else(|_| r#"{"ok":false,"error":"panic"}"#.into());
    env.new_string(out).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
