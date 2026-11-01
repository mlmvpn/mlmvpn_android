//! Stress: twenty users at once (Ehsan, 26 Oct 2026: «یه تست استرس با ۲۰ تا کاربر هم بگیر، مخصوصاً
//! برای گروه و کانال… تا کاربرا زیاد شدن به مشکل نخوریم»). A group of twenty where only the owner
//! knows everyone, a channel with nineteen readers, ten pairs talking at once, and a group of
//! twenty whose only way is the Google mailboxes. Slow on purpose, so ignored by default:
//!
//!   cargo test --release --test stress -- --ignored --nocapture --test-threads=1

mod common;

use common::gas_emu::Emu;
use common::*;
use mlmchat_core::node::Node;
use mlmchat_core::store::MsgState;
use mlmchat_core::transport::gas::http::Endpoint;
use mlmchat_core::transport::gas::GasConfig;
use std::collections::HashSet;
use std::time::{Duration, Instant};

/// Twenty users (`STRESS_N` for another number, to find where something breaks).
static N_USERS: std::sync::LazyLock<usize> = std::sync::LazyLock::new(|| std::env::var("STRESS_N").ok().and_then(|v| v.parse().ok()).unwrap_or(20));

fn eventually(secs: u64, mut f: impl FnMut() -> bool) -> bool {
    let end = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < end {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    false
}

fn direct_nodes(dirs: &[tempfile::TempDir]) -> Vec<Node> {
    dirs.iter()
        .enumerate()
        .map(|(i, d)| {
            let n = new_node(d.path(), &format!("user{i:02}"));
            n.start_direct(loopback(Vec::new())).unwrap();
            n
        })
        .collect()
}

/// The owner adds everyone; the others know only the owner.
fn owner_knows_all(nodes: &[Node]) {
    let o = &nodes[0];
    for n in &nodes[1..] {
        o.add_contact(&n.card_link().unwrap()).unwrap();
    }
    let so = o.me().sk;
    assert!(eventually(120, || nodes[1..].iter().all(|n| n.contact(&so).unwrap().is_some())), "everyone has the owner\n{}", dump_log());
}

/// A group (or channel) of all of them, settled: everyone holds everyone's own card.
fn group_of_all(nodes: &[Node], name: &str, channel: bool) -> (Vec<u8>, Duration) {
    let t0 = Instant::now();
    let o = &nodes[0];
    let gid = o.group_create(name, "", channel).unwrap();
    for n in &nodes[1..] {
        o.group_add(&gid, &n.me().sk).unwrap();
    }
    let settled = |n: &Node| n.group_state(&gid).unwrap().is_some_and(|s| s.members.len() == nodes.len() && s.members.values().all(|m| m.own_card));
    // progress every 10 s: the fewest members and own cards any phone sees, and frames waiting
    let mut last = Instant::now();
    let ok = eventually(300, || {
        if last.elapsed() >= Duration::from_secs(10) {
            last = Instant::now();
            let seen: Vec<(usize, usize, usize)> = nodes
                .iter()
                .map(|n| {
                    let (m, c) = n.group_state(&gid).unwrap().map(|s| (s.members.len(), s.members.values().filter(|m| m.own_card).count())).unwrap_or((0, 0));
                    (m, c, n.outbox_len().unwrap_or(0))
                })
                .collect();
            eprintln!(
                "  {:>4} s: members min {} max {}, own cards min {} max {}, frames waiting {}",
                t0.elapsed().as_secs(),
                seen.iter().map(|x| x.0).min().unwrap_or(0),
                seen.iter().map(|x| x.0).max().unwrap_or(0),
                seen.iter().map(|x| x.1).min().unwrap_or(0),
                seen.iter().map(|x| x.1).max().unwrap_or(0),
                seen.iter().map(|x| x.2).sum::<usize>()
            );
        }
        nodes.iter().all(settled)
    });
    if !ok {
        for (i, n) in nodes.iter().enumerate() {
            let st = n.group_state(&gid).unwrap();
            let lacks: Vec<usize> = st
                .as_ref()
                .map(|s| nodes.iter().enumerate().filter(|(_, m)| !s.members.get(&m.me().sk).is_some_and(|x| x.own_card)).map(|(j, _)| j).collect())
                .unwrap_or_default();
            eprintln!("  user{i:02}: members {}, no own card of {:?}, frames waiting {}", st.map(|s| s.members.len()).unwrap_or(0), lacks, n.outbox_len().unwrap_or(0));
        }
    }
    // the whole log to a file, to see where the time went (`STRESS_LOG=<path>`)
    if let Ok(path) = std::env::var("STRESS_LOG") {
        let _ = std::fs::write(path, dump_log());
    }
    assert!(ok, "every member sees all {} with their own cards", nodes.len());
    (gid, t0.elapsed())
}

fn ids_in(n: &Node, gid: &[u8]) -> HashSet<[u8; 16]> {
    n.group_messages(gid, 1000).unwrap().into_iter().map(|m| m.id).collect()
}

#[test]
#[ignore]
fn twenty_in_a_group_where_only_the_owner_knows_everyone() {
    init_log();
    let dirs: Vec<_> = (0..*N_USERS).map(|_| tempfile::tempdir().unwrap()).collect();
    let nodes = direct_nodes(&dirs);
    owner_knows_all(&nodes);
    let (gid, settle) = group_of_all(&nodes, "bist-nafar", false);
    eprintln!("group of {}: settled in {settle:?}", *N_USERS);

    // everyone writes three messages at once
    let t0 = Instant::now();
    let sent: Vec<[u8; 16]> = std::thread::scope(|s| {
        let hs: Vec<_> = nodes.iter().enumerate().map(|(i, n)| {
            let gid = gid.clone();
            s.spawn(move || (0..3).map(|k| n.group_send_text(&gid, &format!("payam {k} az user{i:02}")).unwrap()).collect::<Vec<_>>())
        }).collect();
        hs.into_iter().flat_map(|h| h.join().unwrap()).collect()
    });
    assert_eq!(sent.len(), 3 * *N_USERS);
    let all: HashSet<[u8; 16]> = sent.iter().copied().collect();
    let ok = eventually(300, || nodes.iter().all(|n| all.is_subset(&ids_in(n, &gid))));
    let missing: usize = nodes.iter().map(|n| all.difference(&ids_in(n, &gid)).count()).sum();
    eprintln!("group of {}: {} messages to everyone in {:?} ({missing} missing)", *N_USERS, all.len(), t0.elapsed());
    assert!(ok, "every member has all {} messages ({missing} missing)\n{}", all.len(), dump_log());
    // no duplicates
    for n in &nodes {
        assert_eq!(n.group_messages(&gid, 1000).unwrap().len(), all.len(), "no message twice");
    }
    for n in &nodes {
        n.stop();
    }
}

#[test]
#[ignore]
fn a_channel_with_nineteen_readers_who_all_react() {
    init_log();
    let dirs: Vec<_> = (0..*N_USERS).map(|_| tempfile::tempdir().unwrap()).collect();
    let nodes = direct_nodes(&dirs);
    owner_knows_all(&nodes);
    let (cid, settle) = group_of_all(&nodes, "akhbar", true);
    eprintln!("channel of {}: settled in {settle:?}", *N_USERS);
    let o = &nodes[0];
    let t0 = Instant::now();
    let posts: Vec<[u8; 16]> = (0..5).map(|k| o.group_send_text(&cid, &format!("khabar {k}")).unwrap()).collect();
    let all: HashSet<[u8; 16]> = posts.iter().copied().collect();
    assert!(eventually(300, || nodes.iter().all(|n| all.is_subset(&ids_in(n, &cid)))), "every reader has the five posts\n{}", dump_log());
    eprintln!("channel of {}: 5 posts to every reader in {:?}", *N_USERS, t0.elapsed());
    assert!(nodes[1].group_send_text(&cid, "man ham").is_err(), "a reader does not post");

    let t1 = Instant::now();
    std::thread::scope(|s| {
        for n in &nodes[1..] {
            let (cid, first) = (cid.clone(), posts[0]);
            s.spawn(move || n.group_react(&cid, &first, "👍").unwrap());
        }
    });
    let ok = eventually(300, || o.group_reactions(&cid).unwrap().iter().filter(|r| r.0 == posts[0]).count() == *N_USERS - 1);
    eprintln!("channel of {}: {} reactions at the owner in {:?}", *N_USERS, o.group_reactions(&cid).unwrap().len(), t1.elapsed());
    assert!(ok, "the owner sees nineteen reactions\n{}", dump_log());
    assert!(eventually(300, || nodes[1..].iter().all(|n| n.group_reactions(&cid).unwrap().len() == *N_USERS - 1)), "and so does every reader");
    for n in &nodes {
        n.stop();
    }
}

#[test]
#[ignore]
fn ten_pairs_talk_at_once() {
    init_log();
    let dirs: Vec<_> = (0..*N_USERS).map(|_| tempfile::tempdir().unwrap()).collect();
    let nodes = direct_nodes(&dirs);
    let pairs: Vec<(&Node, &Node)> = nodes.chunks(2).map(|c| (&c[0], &c[1])).collect();
    for (a, b) in &pairs {
        a.add_contact(&b.card_link().unwrap()).unwrap();
    }
    assert!(eventually(120, || pairs.iter().all(|(a, b)| b.contact(&a.me().sk).unwrap().is_some())), "every pair is connected");
    let t0 = Instant::now();
    std::thread::scope(|s| {
        for (a, b) in &pairs {
            for (from, to) in [(*a, *b), (*b, *a)] {
                let to_sk = to.me().sk;
                s.spawn(move || {
                    for k in 0..20 {
                        from.send_text(&to_sk, &format!("payam {k}")).unwrap();
                    }
                });
            }
        }
    });
    let done = |from: &Node, to: &Node| {
        let h = from.history(&to.me().sk, 100).unwrap();
        h.iter().filter(|m| m.outgoing && matches!(m.state, MsgState::Delivered | MsgState::Read)).count() == 20 && h.iter().filter(|m| !m.outgoing).count() == 20
    };
    let ok = eventually(300, || pairs.iter().all(|(a, b)| done(a, b) && done(b, a)));
    eprintln!("ten pairs: 400 messages, all delivered with receipts, in {:?}", t0.elapsed());
    assert!(ok, "every message arrived and came back delivered\n{}", dump_log());
    for n in &nodes {
        n.stop();
    }
}

fn gas_cfg(emu: &Emu) -> GasConfig {
    GasConfig {
        endpoint: Some(Endpoint::Plain(emu.addr)),
        wait_ms: 1500,
        idle_soon: Duration::from_millis(500),
        idle_late: Duration::from_millis(500),
        request_timeout: Duration::from_secs(10),
        pending_tick: Duration::from_millis(500),
        pending_first: Duration::from_millis(2000),
        ..Default::default()
    }
}

fn keys_of(script: &str) -> (String, String) {
    let pick = |name: &str| {
        let k = format!("var {name} = \"");
        let i = script.find(&k).expect("key in script") + k.len();
        script[i..i + 32].to_string()
    };
    (pick("USE_KEY"), pick("OWNER_KEY"))
}

#[test]
#[ignore]
fn twenty_in_a_group_through_google_mailboxes_only() {
    init_log();
    let emu = Emu::start(Duration::from_secs(600));
    let dirs: Vec<_> = (0..*N_USERS).map(|_| tempfile::tempdir().unwrap()).collect();
    // each user's own mailbox, as in the app; no other transport at all
    let nodes: Vec<Node> = dirs
        .iter()
        .enumerate()
        .map(|(i, d)| {
            let n = new_node(d.path(), &format!("user{i:02}"));
            let draft = n.gas_relay_new(&format!("relay {i}")).unwrap();
            let (u, o) = keys_of(&draft.script);
            let id = format!("AKfycbStress{i:03}abcdefghijklmnopqrstuvwxyz0123456789");
            emu.add(&id, &u, &o);
            n.gas_relay_finish(&draft.rid, &format!("https://script.google.com/macros/s/{id}/exec")).unwrap();
            n.start_gas(gas_cfg(&emu)).unwrap();
            n
        })
        .collect();
    let t0 = Instant::now();
    owner_knows_all(&nodes);
    eprintln!("mailboxes: the owner and nineteen contacts in {:?}", t0.elapsed());
    let (gid, settle) = group_of_all(&nodes, "bist-nafar-sandoogh", false);
    eprintln!("mailboxes: group of {} settled in {settle:?}", *N_USERS);
    let before: u64 = (0..*N_USERS).map(|i| emu.requests(&format!("AKfycbStress{i:03}abcdefghijklmnopqrstuvwxyz0123456789"))).sum();
    let t1 = Instant::now();
    let sent: Vec<[u8; 16]> = std::thread::scope(|s| {
        let hs: Vec<_> = nodes.iter().enumerate().map(|(i, n)| {
            let gid = gid.clone();
            s.spawn(move || n.group_send_text(&gid, &format!("salam az user{i:02}")).unwrap())
        }).collect();
        hs.into_iter().map(|h| h.join().unwrap()).collect()
    });
    let all: HashSet<[u8; 16]> = sent.iter().copied().collect();
    let ok = eventually(600, || nodes.iter().all(|n| all.is_subset(&ids_in(n, &gid))));
    let took = t1.elapsed();
    let after: u64 = (0..*N_USERS).map(|i| emu.requests(&format!("AKfycbStress{i:03}abcdefghijklmnopqrstuvwxyz0123456789"))).sum();
    let missing: usize = nodes.iter().map(|n| all.difference(&ids_in(n, &gid)).count()).sum();
    eprintln!(
        "mailboxes: {} messages to all {} in {took:?} ({missing} missing); {} mailbox requests meanwhile, {:.1} per user per minute",
        all.len(),
        *N_USERS,
        after - before,
        (after - before) as f64 / *N_USERS as f64 / took.as_secs_f64().max(1.0) * 60.0
    );
    assert!(ok, "every member has all {} messages through the mailboxes ({missing} missing)", all.len());
    for n in &nodes {
        n.stop();
    }
}
