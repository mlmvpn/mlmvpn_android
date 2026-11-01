//! The fallback path: both phones connect out to the observer, which pipes the two connections
//! into each other. The phones run TLS 1.3 end to end through the pipe (role a is the client,
//! pinned to role b's card), so the observer carries only ciphertext.

use crate::card::{role, room_id, ObserverCard, PeerCard};
use crate::obsclient::ObsConn;
use crate::session::Session;
use crate::tlsutil;
use crate::util::{err_kind, ms};
use crate::{measure, plog};
use anyhow::{anyhow, Result};
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::time::timeout;

pub async fn run(sess: Arc<Session>, v: &Value) -> Result<Value> {
    let peer = PeerCard::decode(v["peer"].as_str().ok_or_else(|| anyhow!("peer card missing"))?)?;
    let obs = ObserverCard::decode(v["observer"].as_str().ok_or_else(|| anyhow!("observer card missing"))?)?;
    let bytes = v["bytes"].as_u64().unwrap_or(measure::DEFAULT_BYTES);
    let room = room_id(&sess.sid, &peer.sid);
    let my_role = role(&sess.sid, &peer.sid);
    let cid = hex::encode(crate::util::rand_bytes::<8>());

    let t0 = Instant::now();
    let mut ctl = ObsConn::connect(&obs, obs.main()?, None, &cid).await?;
    let to_observer_ms = ms(t0.elapsed());
    plog!("relay: waiting for the other phone (role {my_role})");
    let r = ctl.call_within(json!({ "t": "relay", "room": room, "role": my_role }), Duration::from_secs(130)).await?;
    if r["state"].as_str() != Some("ready") {
        return Err(anyhow!("observer did not open the relay"));
    }
    let paired_ms = ms(t0.elapsed());
    let raw = ctl.ns.into_inner();
    let mut out = json!({ "role": my_role, "observer": obs.label(), "to_observer_ms": to_observer_ms, "paired_ms": paired_ms });
    if my_role == "a" {
        let cfg = tlsutil::client_config(peer.pin()?, tlsutil::alpn_for(&peer.sid))?;
        let t1 = Instant::now();
        let tls = timeout(Duration::from_secs(15), tokio_rustls::TlsConnector::from(Arc::new(cfg)).connect(tlsutil::any_name(), raw)).await??;
        out["tls_ms"] = json!(ms(t1.elapsed()));
        let _g = sess.measure_lock.lock().await;
        match measure::client(tls, &sess.sid, "relay-tcp", bytes).await {
            Ok(m) => {
                out["ok"] = json!(true);
                out["measure"] = m;
            }
            Err(e) => {
                out["ok"] = json!(false);
                out["error"] = json!(err_kind(&e));
            }
        }
    } else {
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(tlsutil::server_config(&sess.ident, tlsutil::alpn_for(&sess.sid))?));
        let tls = timeout(Duration::from_secs(15), acceptor.accept(raw)).await??;
        match measure::server(tls, &sess.sid).await {
            Ok(info) => {
                out["ok"] = json!(true);
                out["served"] = info;
            }
            Err(e) => {
                out["ok"] = json!(false);
                out["error"] = json!(err_kind(&e));
            }
        }
    }
    Ok(out)
}
