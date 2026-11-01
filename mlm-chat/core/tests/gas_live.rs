//! The way to the real Google (SPEC 19.5), by hand: `cargo test --test gas_live -- --ignored --nocapture`.
//! Needs the internet, so it is not part of the normal run. It finds a front the way the phone
//! does and asks a deployment that does not exist: Google must answer 404 through the verified
//! TLS session, and the client must read it as «not found».
//!
//! With a real relay (made by the app, deployed at script.google.com), set
//! `MLMCHAT_GAS_ID` (deployment id) and `MLMCHAT_GAS_KEY` (the 32 hex digits after
//! `var USE_KEY = "` in the script): the test then also pings it and puts and takes one item.

use mlmchat_core::transport::gas::front;
use mlmchat_core::transport::gas::http::Client;
use serde_json::{json, Value};
use std::time::Duration;

#[test]
#[ignore]
fn google_answers_through_a_front_and_an_unknown_deployment_is_not_found() {
    let candidates = front::candidates(None, true);
    println!("{} candidates", candidates.len());
    let (ep, ms) = front::race(candidates, Duration::from_secs(8)).expect("a way to Google on this network");
    println!("front {} in {ms} ms", front::label(&ep));
    let c = Client::default();
    let r = c
        .exec(&ep, "AKfycbxNoSuchDeployment000000000000000000000000000000000000000000000000000000000", b"{}", Duration::from_secs(15))
        .expect("an HTTP answer");
    println!("status {} ({} bytes)", r.status, r.body.len());
    assert_eq!(r.status, 404, "Google has no such deployment");
}

#[test]
#[ignore]
fn a_real_relay_pings_and_keeps_an_item() {
    let (Ok(id), Ok(key)) = (std::env::var("MLMCHAT_GAS_ID"), std::env::var("MLMCHAT_GAS_KEY")) else {
        println!("MLMCHAT_GAS_ID and MLMCHAT_GAS_KEY are not set: skipped");
        return;
    };
    let (ep, ms) = front::race(front::candidates(None, true), Duration::from_secs(8)).expect("a way to Google");
    println!("front {} in {ms} ms", front::label(&ep));
    let c = Client::default();
    let call = |body: Value| -> Value {
        let t0 = std::time::Instant::now();
        let r = c.exec(&ep, &id, body.to_string().as_bytes(), Duration::from_secs(30)).expect("an HTTP answer");
        println!("status {} in {:?}", r.status, t0.elapsed());
        assert_eq!(r.status, 200, "{}", String::from_utf8_lossy(&r.body));
        serde_json::from_slice(&r.body).unwrap_or_else(|_| panic!("not JSON: {}", String::from_utf8_lossy(&r.body)))
    };
    let ping = call(json!({ "op": "ping", "k": key, "c": "00000000000000aa" }));
    println!("ping {ping}");
    assert_eq!(ping["ok"], 1);
    let tag = "0123456789abcdef0123456789abcdef";
    let item = "fedcba9876543210fedcba9876543210";
    let put = call(json!({ "op": "put", "k": key, "c": "00000000000000aa", "t": tag, "i": [[item, "aGVsbG8"]] }));
    println!("put {put}");
    assert_eq!(put["r"][0], "ok");
    let t0 = std::time::Instant::now();
    let take = call(json!({ "op": "take", "k": key, "c": "00000000000000aa", "t": [tag], "a": [], "w": 0 }));
    println!("take {take} ({:?})", t0.elapsed());
    assert_eq!(take["i"][0][0], item);
    let ack = call(json!({ "op": "take", "k": key, "c": "00000000000000aa", "t": [tag], "a": [item], "w": 0 }));
    assert_eq!(ack["i"].as_array().map(|a| a.len()), Some(0));
    let chk = call(json!({ "op": "chk", "k": key, "c": "00000000000000aa", "t": tag, "i": [item] }));
    assert_eq!(chk["r"][0], "done");
}

/// Without the key a deployment must answer like a forgotten script (the decoy page): this proves
/// the deployment exists, is open to «Anyone», is this script, and that Google's redirect to
/// `script.googleusercontent.com` is followed through the front.
#[test]
#[ignore]
fn a_real_deployment_answers_a_stranger_with_the_decoy() {
    let Ok(id) = std::env::var("MLMCHAT_GAS_ID") else {
        println!("MLMCHAT_GAS_ID is not set: skipped");
        return;
    };
    let (ep, ms) = front::race(front::candidates(None, true), Duration::from_secs(8)).expect("a way to Google");
    println!("front {} in {ms} ms", front::label(&ep));
    let c = Client::default();
    let t0 = std::time::Instant::now();
    let body = json!({ "op": "ping", "k": "00000000000000000000000000000000", "c": "0000000000000001" }).to_string();
    let r = c.exec(&ep, &id, body.as_bytes(), Duration::from_secs(30)).expect("an HTTP answer");
    println!("status {} in {:?}, {} bytes, redirect-less final answer", r.status, t0.elapsed(), r.body.len());
    let text = String::from_utf8_lossy(&r.body).to_string();
    println!("{}", text.chars().take(200).collect::<String>());
    assert_eq!(r.status, 200);
    assert!(text.contains("did not return anything"), "the decoy page");
}
