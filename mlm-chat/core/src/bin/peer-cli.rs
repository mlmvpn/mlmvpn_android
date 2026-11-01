//! `peer-cli`: the headless reference node. It runs the core exactly as the app will, over the
//! direct transport (LAN, direct IPv6) and, with `--p2p`, the p2p transport (volunteers, the
//! private DHT, relay circuits; SPEC 15), with commands typed on stdin.
//!
//!   peer-cli <dir> init <name>
//!   peer-cli <dir> card --advertise ADDR[,ADDR]
//!   peer-cli <dir> run [--listen ADDR] [--advertise ADDR,..] [--discovery ADDR|off]
//!                      [--beacon-to ADDR,..] [--log]
//!                      [--p2p] [--p2p-port N] [--p2p-ports N,..] [--p2p-listen MA,..] [--boot MA,..]
//!                      [--volunteer] [--external MA,..] [--upnp] [--relay-only] [--scope FILE]
//!                      [--gas] [--gas-only] [--h2] [--nostr] [--nostr-only] [--nostr-relay URL]..
//!
//! `--scope FILE` limits every connection, both ways, to the ranges in FILE, one per line; the
//! local network is always allowed (SPEC 15.10). With Iran's IPv4 ranges it is the app's «inside
//! Iran only», the way to test today what still works when the country is cut off.
//! `--volunteer` makes this node a volunteer (PLAN 11): relay server and DHT server for everyone.
//! Its card then names it in `boot`, so whoever adds this contact learns the volunteer.
//! `--upnp` (also `--door`) lets a volunteer open its own door on the router for `--p2p-port`
//! only: UPnP IGD v1/v2 (with the IPv6 pinhole), NAT-PMP, PCP; leases of 2 hours at most,
//! renewed while it runs, deleted on `quit` (SPEC 15.9). An address is announced to others only
//! once a peer on another network has really connected to it; `net` shows which. `--show-card`
//! prints the card link and QR, and what the door did, a few seconds after the start.
//! `--relay-only` publishes only relay circuits in the presence record, and `--no-holepunch`
//! turns DCUtR off, so a contact can only be reached through the volunteer (to prove that path).
//! `--gas` starts the Google mailbox transport (SPEC 19) next to the others; `--gas-only` starts
//! nothing else (no direct listener, no p2p), so a contact can only be reached through the relays
//! of the list (`gas`, `gasadd`, `gasref`, `gastest`). The way to Google is found by itself.
//! `--h2` talks HTTP/2 to the fronts that offer it (HTTP/1.1 to the others).
//! `--nostr` starts the Nostr transport (SPEC 20) next to the others; `--nostr-only` starts nothing
//! else (it can go with `--gas`), so a contact can only be reached through public Nostr relays
//! (`nostr` shows them). `--nostr-relay URL` (more than once for more) uses only these relays, and
//! the card names only them: the way to test one relay by itself, such as our own in Iran (T54).
//! A test tool: the database key sits next to the database in `<dir>/db.key`. The app keeps its
//! key in Android Keystore instead.

use mlmchat_core::card::qr_for_terminal;
use mlmchat_core::identity::grouped;
use mlmchat_core::node::{contact_display_name, Event, Node, NodeOptions};
use mlmchat_core::store::Contact;
use mlmchat_core::transport::direct::DirectConfig;
use mlmchat_core::transport::gas::http::wire_totals;
use mlmchat_core::transport::gas::GasConfig;
use mlmchat_core::transport::nostr::transport::NostrConfig;
use mlmchat_core::transport::p2p::P2pConfig;
use mlmchat_core::transport::scope::NetScope;
use mlmchat_core::util::{random, short_id};
use mlmchat_core::UserId;
use std::io::BufRead;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// `echo on`: a message `PING …` is answered `PONG …` at once (the relay field test, docs/RELAY-FIELD-TEST.fa.md).
static ECHO: AtomicBool = AtomicBool::new(false);
/// `pingtest`: per ping number, when it went (this computer's clock) and its round trip.
static PINGS: Mutex<Vec<(u32, u64, Option<u64>)>> = Mutex::new(Vec::new());

const USAGE: &str = "usage:
  peer-cli <dir> init <name>
  peer-cli <dir> card --advertise ADDR[,ADDR]
  peer-cli <dir> sk                  our identity key (hex)
  peer-cli <keyfile> feed-owner      make (or read) the official feeds' owner key; prints its public half
  peer-cli <keyfile> feed-admins <feed id> <version> <sk,sk>   a signed admin list (hex)
  peer-cli <keyfile> feed-owner-qr   the owner key as a QR, for the owner's main phone to scan once
  peer-cli <dir> run [--listen ADDR] [--advertise ADDR,..] [--discovery ADDR|off] [--beacon-to ADDR,..] [--log]
                     [--p2p] [--p2p-port N] [--p2p-listen MA,..] [--boot MA,..]
                     [--volunteer] [--external MA,..] [--upnp|--door] [--show-card] [--relay-only] [--no-holepunch] [--network LABEL]
                     [--scope FILE] [--gas] [--gas-only] [--h2] [--nostr] [--nostr-only] [--feeds FILE] [--mirror LINK]

commands while running:
  card                 my card (link and QR)
  add <link>           add a contact from a link
  contacts             list contacts
  send <n> <text>      send text to contact n
  read <n>             mark contact n's messages as read
  history <n> [count]  last messages with contact n
  sn <n>               security number with contact n
  verify <n>           mark contact n as verified
  queue                frames still waiting
  status <n>           what really happened with contact n (reached when, how; queued)
  net                  network state (ladder, volunteers, reservations, presence; a volunteer's door
                       and the addresses peers on other networks really reached)
  netchange            the network changed: reconnect, publish again
  volcheck             could this machine be a volunteer here? (asks the router, opens nothing)
  relay                a volunteer's ledger: peers using it now, their traffic, kept totals
  kick <name>          disconnect one peer of the ledger (its six-digit name), out for an hour
  gcreate <name>       a new group (ccreate: a channel, only admins post)
  groups               list groups and channels
  gadd <g> <n>         add contact n to group g
  gsend <g> <text>     post in group g
  edit <n> <text>      new text for my last message to contact n (gedit <g> <text>: in group g)
  typing <n> [kind]    tell contact n what I am doing now: typing, upload_photo, upload_file,
                       record_voice or cancel (only over a route open to their phone, else dropped)
  presence <on|off>    my app is in front / not (contacts with an open route hear «online»)
  lastseen <show|hide> show my last seen (the default) or hide it, and with it everyone else's
  ghistory <g>         last messages of group g
  gread <g>            I read group g up to now (its authors hear it, SPEC 7.6); greads <g> who read my last post
  gmembers <g>         members of group g, and those banned from it
  gban <g> <n>         remove contact n from group g and ban them for good
  gunban <g> <n>       lift contact n's ban from group g (they stay out until added)
  gleave <g>           leave group g
  sendfile <n> <path>  send a file to contact n (.jpg/.png as a photo, .mp4 as a video)
  sendround <n> <path> send an .mp4 to contact n as a round video message
  gsendfile <g> <path> post a file in group g
  savefile <n> <path>  write the newest file from contact n (asks for the rest if not whole)
  setphoto <path|->    our profile photo (- removes it)
  gsetphoto <g> <path|-> group g's photo (- removes it)
  photo <n> <path>     write contact n's profile photo (gphoto <g> <path>: group g's)
  ginvite <g> [approve] a new invite link for group g (approve: requests wait for an admin)
  ginvites <g>         group g's invites
  join <link>          ask to join a group by its link
  grequests <g>        requests waiting in group g (gapprove/gdecline <g> <i>)
  gas                  the Google relays: list, state, numbers, the way to Google
  gasadd <link>        add a friend's relay from its mlmchat://r/ link
  gasref <n>           add the relays contact n's card names to my list
  gastest <rid>        test a relay (the way to Google, the script, a message through it)
  gasset <rid> on|off|announce|hide   switch a relay, or name it in my card or not
  gasremove <rid>      remove a relay from the list
  gasbytes             what the relays carried today (requests, payload bytes)
  nostr                the Nostr relays: state, way, events in and out (--nostr)
  feeds / fposts <f> / fsend <f> <text> / fsync   the official feeds (--feeds FILE)
  msync                read the official feeds' copy now (--mirror LINK); fcount: the member count
  echo on|off          answer every PING with a PONG (the relay field test)
  pingtest <n> [count] [gap]   PINGs to contact n, then round trips and relay bytes per ping
  wait <seconds>       pause (for scripts)
  quit";

struct StderrLog;

impl log::Log for StderrLog {
    fn enabled(&self, _: &log::Metadata) -> bool {
        true
    }
    fn log(&self, r: &log::Record) {
        eprintln!("[log] {} {}", r.level(), r.args());
    }
    fn flush(&self) {}
}

fn fail(msg: &str) -> ! {
    eprintln!("{msg}");
    std::process::exit(2);
}

fn key_for(dir: &Path, create: bool) -> [u8; 32] {
    let path = dir.join("db.key");
    match std::fs::read(&path) {
        Ok(b) if b.len() == 32 => b.try_into().expect("32 bytes"),
        Ok(_) => fail("db.key is damaged"),
        Err(_) if create => {
            let k: [u8; 32] = random();
            std::fs::write(&path, k).unwrap_or_else(|_| fail("cannot write db.key"));
            k
        }
        Err(_) => fail("no identity here; run `init` first"),
    }
}

fn addrs(list: &str) -> Vec<SocketAddr> {
    list.split(',').filter(|s| !s.is_empty()).map(|s| s.trim().parse().unwrap_or_else(|_| fail(&format!("not an address: {s}")))).collect()
}

fn flag<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    args.iter().position(|a| a == name).map(|i| args.get(i + 1).map(String::as_str).unwrap_or_else(|| fail(&format!("{name} needs a value"))))
}

fn open(dir: &Path) -> Node {
    let key = key_for(dir, false);
    Node::open(&dir.join("chat.db"), &key, NodeOptions::default()).unwrap_or_else(|e| fail(&format!("cannot open: {e}")))
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 2 {
        fail(USAGE);
    }
    let dir = PathBuf::from(&args[0]);
    match args[1].as_str() {
        "init" => {
            let name = args.get(2).unwrap_or_else(|| fail(USAGE));
            std::fs::create_dir_all(&dir).unwrap_or_else(|_| fail("cannot create the folder"));
            let key = key_for(&dir, true);
            let node = Node::create(&dir.join("chat.db"), &key, name, NodeOptions::default()).unwrap_or_else(|e| fail(&format!("init failed: {e}")));
            let me = node.me();
            println!("identity made: {}", me.display_name);
        }
        "card" => {
            let adv = addrs(flag(&args, "--advertise").unwrap_or_else(|| fail("card needs --advertise ADDR")));
            let node = open(&dir);
            let link = node.card_link_for(&adv).unwrap_or_else(|e| fail(&format!("{e}")));
            println!("{link}");
        }
        "run" => run(&dir, &args[2..]),
        "sk" => println!("{}", hex::encode(open(&dir).me().sk)),
        // which Nostr relays this network lets through (SPEC 20), plainly and with the ClientHello
        // in two TLS records; <dir> is not used. Run it on the phone itself, never through a VPN.
        "nostrprobe" => {
            let secs: u64 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(10);
            println!("relay                               way           result   dns   tcp   tls    ws   sub");
            let all = mlmchat_core::transport::nostr::probe_all(Duration::from_secs(secs));
            for p in &all {
                let res = match p.failed {
                    None => "ok".to_string(),
                    Some(s) => format!("x {}", s.code()),
                };
                println!("{:<36}{:<14}{:<8}{:>5} {:>5} {:>5} {:>5} {:>5}", p.relay.trim_start_matches("wss://"), p.split.label(), res, p.ms[0], p.ms[1], p.ms[2], p.ms[3], p.ms[4]);
            }
            let ok = |s| all.iter().filter(|p| p.split == s && p.failed.is_none()).count();
            println!(
                "open: {} of {} plainly, {} of {} with the record split",
                ok(mlmchat_core::transport::nostr::ws::Split::None),
                mlmchat_core::transport::nostr::RELAYS.len(),
                ok(mlmchat_core::transport::nostr::ws::Split::Record),
                mlmchat_core::transport::nostr::RELAYS.len()
            );
        }
        // what a card link holds, as the app would show it; adds nothing to any database
        "cardinfo" => {
            let link = args.get(2).unwrap_or_else(|| fail("cardinfo <link>"));
            let card = mlmchat_core::card::Card::from_link(link).unwrap_or_else(|e| fail(&format!("not a card: {e}")));
            let name = card.name.clone().unwrap_or_default();
            let code = mlmchat_core::identity::personal_code(&card.sk, &card.ik);
            println!("name: {name}");
            println!("shown as: {}", mlmchat_core::identity::display_name(if name.is_empty() { "?" } else { &name }, &code));
            println!("sk: {}", hex::encode(card.sk));
            println!("relays named: {}", card.all_relays().len());
        }
        // the owner's offline tools for the official feeds (T38): <dir> is the owner key file
        "feed-owner" => {
            let secret = match std::fs::read_to_string(&dir) {
                Ok(h) => hex::decode(h.trim()).ok().and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok()).unwrap_or_else(|| fail("bad owner key file")),
                Err(_) => {
                    let (secret, _) = mlmchat_core::feed::new_owner_key();
                    std::fs::write(&dir, hex::encode(secret)).unwrap_or_else(|_| fail("cannot write the owner key"));
                    secret
                }
            };
            let public = vodozemac::Ed25519SecretKey::from_slice(&secret).public_key();
            println!("{}", hex::encode(public.as_bytes()));
        }
        // the owner key as a link and QR, for the owner's main phone to scan once (Gereh: add
        // contact): its admins are then named in the app. Shown, never written to a file.
        "feed-owner-qr" => {
            let secret = std::fs::read_to_string(&dir).ok().map(|h| h.trim().to_string()).filter(|h| h.len() == 64).unwrap_or_else(|| fail("bad owner key file"));
            let link = format!("mlmchat://o/{secret}");
            match qr_for_terminal(&link) {
                Ok(qr) => println!("{qr}"),
                Err(e) => fail(&format!("{e}")),
            }
        }
        "feed-admins" => {
            let secret: [u8; 32] = std::fs::read_to_string(&dir)
                .ok()
                .and_then(|h| hex::decode(h.trim()).ok())
                .and_then(|b| b.try_into().ok())
                .unwrap_or_else(|| fail("bad owner key file"));
            let feed = hex::decode(args.get(2).unwrap_or_else(|| fail("feed-admins <feed id> <version> <sk,sk>"))).unwrap_or_else(|_| fail("bad feed id"));
            let version: u64 = args.get(3).and_then(|v| v.parse().ok()).unwrap_or_else(|| fail("bad version"));
            let admins: Vec<[u8; 32]> = args
                .get(4)
                .map(|l| l.split(',').filter(|x| !x.is_empty()).map(|x| hex::decode(x).ok().and_then(|b| b.try_into().ok()).unwrap_or_else(|| fail("bad admin key"))).collect())
                .unwrap_or_default();
            let item = mlmchat_core::feed::sign_admins(&secret, &feed, version, &admins, mlmchat_core::util::now_ms()).unwrap_or_else(|e| fail(&format!("{e}")));
            println!("{}", hex::encode(item.to_bytes()));
        }
        _ => fail(USAGE),
    }
}

fn run(dir: &Path, args: &[String]) {
    if args.iter().any(|a| a == "--log") {
        log::set_logger(&StderrLog).ok();
        log::set_max_level(log::LevelFilter::Debug);
    }
    let mut cfg = DirectConfig::default();
    if let Some(l) = flag(args, "--listen") {
        cfg.listen = l.parse().unwrap_or_else(|_| fail("bad --listen"));
    }
    if let Some(a) = flag(args, "--advertise") {
        cfg.advertise = addrs(a);
    }
    match flag(args, "--discovery") {
        Some("off") => cfg.discovery = None,
        Some(d) => cfg.discovery = Some(d.parse().unwrap_or_else(|_| fail("bad --discovery"))),
        None => {}
    }
    if let Some(b) = flag(args, "--beacon-to") {
        cfg.beacon_targets = addrs(b);
    }

    let node = Arc::new(open(dir));
    let events = node.events();
    if let Some(label) = flag(args, "--network") {
        node.set_network(label);
    }
    if let Some(f) = flag(args, "--scope") {
        // SPEC 15.10: one range per line (the app's «inside Iran only» passes Iran's IPv4 ranges)
        let text = std::fs::read_to_string(f).unwrap_or_else(|_| fail("cannot read --scope"));
        let scope = NetScope::parse(text.lines());
        println!("scope: {} ranges", scope.len());
        node.set_scope(Some(scope));
    }
    if let Some(f) = flag(args, "--feeds") {
        // the official feeds as the app ships them (T38): {"feeds":[…],"seed":[hex]}
        let v: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(f).unwrap_or_else(|_| fail("cannot read --feeds"))).unwrap_or_else(|_| fail("bad --feeds"));
        let feeds = v["feeds"].as_array().cloned().unwrap_or_default().iter().map(|f| mlmchat_core::feed::FeedDescriptor {
            feed_id: hex::decode(f["id"].as_str().unwrap_or_default()).unwrap_or_default(),
            broadcast: f["broadcast"].as_bool().unwrap_or(false),
            name: f["name"].as_str().unwrap_or_default().to_string(),
            owner: hex::decode(f["owner"].as_str().unwrap_or_default()).ok().and_then(|b| b.try_into().ok()).unwrap_or_else(|| fail("bad feed owner")),
        }).collect();
        let seed: Vec<mlmchat_core::feed::SignedFeedItem> = v["seed"].as_array().cloned().unwrap_or_default().iter().filter_map(|h| hex::decode(h.as_str()?).ok()).filter_map(|b| mlmchat_core::feed::SignedFeedItem::from_bytes(&b).ok()).collect();
        node.set_feeds(feeds, &seed).unwrap_or_else(|e| fail(&format!("feeds: {e}")));
        if let Some(m) = v["mirror"].as_str().filter(|m| !m.is_empty()) {
            node.set_feed_mirror(Some(m)).unwrap_or_else(|e| fail(&format!("mirror: {e}")));
        }
    }
    if let Some(m) = flag(args, "--mirror") {
        // the official feeds' copy in a Google relay (SPEC 18.2), as the app ships it
        node.set_feed_mirror(Some(m)).unwrap_or_else(|e| fail(&format!("mirror: {e}")));
        println!("official copy: set");
    }
    let nostr_only = args.iter().any(|a| a == "--nostr-only");
    let gas_only = nostr_only || args.iter().any(|a| a == "--gas-only");
    if gas_only {
        println!("{}: direct and p2p are off, only the relays carry frames", node.me().display_name);
    } else {
        let transport = node.start_direct(cfg).unwrap_or_else(|e| fail(&format!("cannot listen: {e}")));
        println!("{} listening on {}", node.me().display_name, transport.local_addr());
    }
    if nostr_only || args.iter().any(|a| a == "--nostr") {
        let only: Vec<String> = args.windows(2).filter(|w| w[0] == "--nostr-relay").map(|w| w[1].clone()).collect();
        let ncfg = if only.is_empty() { NostrConfig::default() } else { NostrConfig { relays: only, ..NostrConfig::default() } };
        node.start_nostr(ncfg).unwrap_or_else(|e| fail(&format!("nostr: {e}")));
        println!("nostr relays started");
    }
    if (gas_only && !nostr_only) || args.iter().any(|a| a == "--gas") {
        node.start_gas(GasConfig::default()).unwrap_or_else(|e| fail(&format!("gas: {e}")));
        // like an app in use: takes wait at the mailbox, so a message comes within about a second
        node.gas_foreground(true);
        if args.iter().any(|a| a == "--h2") {
            node.gas_http2(true);
            println!("HTTP/2 to the fronts: on");
        }
        println!("google relays started");
    }
    let volunteer = !gas_only && args.iter().any(|a| a == "--volunteer");
    if !gas_only && (volunteer || args.iter().any(|a| a == "--p2p")) {
        let mas = |name: &str| -> Vec<mlmchat_core::transport::p2p::Multiaddr> {
            flag(args, name).map(|l| l.split(',').filter(|s| !s.is_empty()).map(|s| s.trim().parse().unwrap_or_else(|_| fail(&format!("not a multiaddr: {s}")))).collect()).unwrap_or_default()
        };
        let pcfg = P2pConfig {
            listen: mas("--p2p-listen"),
            port: flag(args, "--p2p-port").map(|p| p.parse().unwrap_or_else(|_| fail("bad --p2p-port"))).unwrap_or(0),
            more_ports: flag(args, "--p2p-ports").map(|l| l.split(',').filter(|s| !s.is_empty()).map(|p| p.trim().parse().unwrap_or_else(|_| fail("bad --p2p-ports"))).collect()).unwrap_or_default(),
            volunteer,
            external: mas("--external"),
            upnp: args.iter().any(|a| a == "--upnp" || a == "--door"),
            boot: mas("--boot"),
            publish_direct: !args.iter().any(|a| a == "--relay-only"),
            allow_loopback: args.iter().any(|a| a == "--allow-loopback"),
            hole_punch: !args.iter().any(|a| a == "--no-holepunch"),
            ..P2pConfig::default()
        };
        let p = node.start_p2p(pcfg).unwrap_or_else(|e| fail(&format!("p2p: {e}")));
        if volunteer {
            // the operator of a volunteer needs its address to hand out; it is their own
            println!("volunteer peer id {}", p.peer_id());
        }
        println!("p2p started");
        if args.iter().any(|a| a == "--show-card") {
            // once the door had its few seconds: the card to hand out, and what the door did
            let n = node.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_secs(12));
                if let Some(st) = n.net_status().p2p {
                    let door: Vec<String> = st.door.iter().map(|d| format!("{}={}", d.method, d.result)).collect();
                    println!("door: {}", if door.is_empty() { "off".to_string() } else { door.join(" ") });
                    println!("reachable from other networks (verified): {}", if st.verified.is_empty() { "not yet".to_string() } else { st.verified.join(" ") });
                }
                if let Ok(link) = n.card_link() {
                    println!("card: {link}");
                    if let Ok(qr) = qr_for_terminal(&link) {
                        println!("{qr}");
                    }
                }
            });
        }
    }

    let n2 = node.clone();
    std::thread::spawn(move || {
        while let Ok(e) = events.recv() {
            print_event(&n2, e);
        }
    });

    let stdin = std::io::stdin();
    for line in stdin.lock().lines() {
        let Ok(line) = line else { break };
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if !command(&node, line) {
            break;
        }
    }
    node.stop();
}

fn name_of(node: &Node, sk: &UserId) -> String {
    match node.contact(sk) {
        Ok(Some(c)) => contact_display_name(&c),
        _ => "?".into(),
    }
}

fn print_event(node: &Node, e: Event) {
    match e {
        Event::Message { peer, kind, text, .. } => match text {
            Some(t) => {
                println!("[msg] {} {}: {t}", clock(), name_of(node, &peer));
                if let Some(rest) = t.strip_prefix("PING ") {
                    if ECHO.load(Ordering::SeqCst) {
                        let _ = node.send_text(&peer, &format!("PONG {rest}"));
                    }
                } else if let Some(rest) = t.strip_prefix("PONG ") {
                    // "PONG <n> <sent ms>": the round trip on this computer's own clock
                    let mut it = rest.split(' ');
                    if let (Some(Ok(n)), Some(Ok(t0))) = (it.next().map(str::parse::<u32>), it.next().map(str::parse::<u64>)) {
                        let rtt = mlmchat_core::util::now_ms().saturating_sub(t0);
                        if let Some(p) = PINGS.lock().unwrap().iter_mut().find(|p| p.0 == n && p.1 == t0) {
                            p.2.get_or_insert(rtt);
                        }
                        println!("[pong] #{n} round trip {rtt} ms");
                    }
                }
            }
            None => println!("[msg] {} {}: ({kind})", clock(), name_of(node, &peer)),
        },
        Event::State { peer, id, state } => println!("[state] {} #{} -> {}", name_of(node, &peer), short_id(&id), state.name()),
        Event::Queued { peer, reason } => println!("[queued] {}: {reason}", name_of(node, &peer)),
        Event::Contact { peer, by_them: true } => println!("[contact] request from {}", name_of(node, &peer)),
        Event::Contact { peer, by_them: false } => println!("[contact] added {}", name_of(node, &peer)),
        Event::Seen { peer, via } => println!("[seen] {} ({via})", name_of(node, &peer)),
        Event::ContactChanged { peer } => println!("[contact] {} sent a newer card", name_of(node, &peer)),
        Event::Reachable { peer, via } => println!("[reachable] {} via {via}", name_of(node, &peer)),
        Event::Group { gid } => println!("[group] {} changed", hex::encode(&gid)),
        Event::Blob { id, have, total } => println!("[file] {} {have}/{total}", hex::encode(&id[..6])),
        Event::GroupMessage { gid, sender, text, kind, .. } => match text {
            Some(t) => println!("[group {}] {}: {t}", hex::encode(&gid), name_of(node, &sender)),
            None => println!("[group {}] {}: ({kind})", hex::encode(&gid), name_of(node, &sender)),
        },
        Event::Feed { feed_id } => println!("[feed] {} changed", hex::encode(&feed_id)),
        Event::Reaction { chat, group, id, peer } => println!("[reaction] {} #{} by {} in {}", if group { "group" } else { "chat" }, short_id(&id), name_of(node, &peer), hex::encode(&chat)),
        Event::PollVote { chat, id, peer, .. } => println!("[vote] poll #{} by {} in {}", short_id(&id), name_of(node, &peer), hex::encode(&chat)),
        Event::Deleted { chat, group, seqs } => println!("[deleted] {} message(s) in {} {}", seqs.len(), if group { "group" } else { "chat" }, hex::encode(&chat)),
        Event::Edited { chat, group, seq } => println!("[edited] message {seq} in {} {}", if group { "group" } else { "chat" }, hex::encode(&chat)),
        Event::Pinned { peer } => println!("[pinned] chat with {}", hex::encode(peer)),
        Event::JoinRequest { gid, peer } => println!("[join request] {} wants to join {}", name_of(node, &peer), hex::encode(&gid)),
        Event::ChatAction { peer, kind } => println!("[action] {} {kind}", name_of(node, &peer)),
        Event::Presence { peer, online } => println!("[presence] {} {}", name_of(node, &peer), if online { "online" } else { "offline" }),
        Event::GroupRead { gid, seq, peer } => println!("[seen] {} read message {seq} in group {}", name_of(node, &peer), hex::encode(&gid)),
        Event::Client { peer } => println!("[app] {} writes from another app or version now", name_of(node, &peer)),
        Event::Profile { peer } => match node.photo_of(&peer) {
            Ok(Some(p)) => println!("[profile] {} photo {}", name_of(node, &peer), hex::encode(&p.blob_id[..6])),
            _ => println!("[profile] {} no photo", name_of(node, &peer)),
        },
    }
}

/// Hours, minutes, seconds and milliseconds (UTC) for log lines of the field test.
fn clock() -> String {
    let ms = mlmchat_core::util::now_ms();
    let s = ms / 1000;
    format!("{:02}:{:02}:{:02}.{:03}Z", (s / 3600) % 24, (s / 60) % 60, s % 60, ms % 1000)
}

/// The day's totals of every relay in the list: requests, payload bytes out, payload bytes in.
fn gas_totals(node: &Node) -> (u64, u64, u64) {
    node.gas_overview().map(|o| o.relays.iter().fold((0, 0, 0), |a, r| (a.0 + r.today.req, a.1 + r.today.bytes_out, a.2 + r.today.bytes_in))).unwrap_or((0, 0, 0))
}

/// `pingtest`: `count` pings `gap` seconds apart to one contact, then up to a minute for the pongs;
/// prints the round trips and what the relays carried for them.
fn ping_test(node: Arc<Node>, to: Contact, count: u32, gap: f64) {
    node.gas_flush_stats();
    let (r0, o0, i0) = gas_totals(&node);
    let (wo0, wi0) = wire_totals();
    PINGS.lock().unwrap().clear();
    for n in 1..=count {
        let t0 = mlmchat_core::util::now_ms();
        PINGS.lock().unwrap().push((n, t0, None));
        if let Err(e) = node.send_text(&to.sk, &format!("PING {n} {t0}")) {
            println!("pingtest: #{n} not sent ({e})");
        }
        std::thread::sleep(Duration::from_secs_f64(gap));
    }
    let end = std::time::Instant::now() + Duration::from_secs(60);
    while std::time::Instant::now() < end && PINGS.lock().unwrap().iter().any(|p| p.2.is_none()) {
        std::thread::sleep(Duration::from_millis(250));
    }
    std::thread::sleep(Duration::from_secs(16));
    node.gas_flush_stats();
    let (r1, o1, i1) = gas_totals(&node);
    let (wo1, wi1) = wire_totals();
    let mut rtts: Vec<u64> = PINGS.lock().unwrap().iter().filter_map(|p| p.2).collect();
    rtts.sort_unstable();
    let pick = |q: f64| rtts.get(((rtts.len() as f64 - 1.0) * q).round() as usize).copied().unwrap_or(0);
    println!(
        "pingtest: {} of {count} came back; round trip ms min {} median {} p90 {} max {}; relays: {} requests, {} bytes out, {} bytes in ({} + {} per ping)",
        rtts.len(),
        pick(0.0),
        pick(0.5),
        pick(0.9),
        pick(1.0),
        r1.saturating_sub(r0),
        o1.saturating_sub(o0),
        i1.saturating_sub(i0),
        o1.saturating_sub(o0) / u64::from(count.max(1)),
        i1.saturating_sub(i0) / u64::from(count.max(1)),
    );
    println!(
        "pingtest: on the wire {} bytes out, {} bytes in ({} + {} per ping; TLS, HTTP heads and redirects included)",
        wo1.saturating_sub(wo0),
        wi1.saturating_sub(wi0),
        wo1.saturating_sub(wo0) / u64::from(count.max(1)),
        wi1.saturating_sub(wi0) / u64::from(count.max(1)),
    );
}

fn nth(node: &Node, n: &str) -> Option<Contact> {
    let i: usize = n.parse().ok()?;
    node.contacts().ok()?.into_iter().nth(i.checked_sub(1)?)
}

fn nth_group(node: &Node, n: &str) -> Option<Vec<u8>> {
    let i: usize = n.parse().ok()?;
    node.groups().ok()?.into_iter().nth(i.checked_sub(1)?).map(|g| g.group_id)
}

/// One command; `false` = quit.
fn command(node: &Arc<Node>, line: &str) -> bool {
    let mut parts = line.splitn(3, ' ');
    let cmd = parts.next().unwrap_or("");
    let a = parts.next().unwrap_or("");
    let rest = parts.next().unwrap_or("");
    let res: Result<(), String> = (|| {
        match cmd {
            "card" => {
                let link = node.card_link().map_err(|e| e.to_string())?;
                println!("{link}");
                if let Ok(qr) = qr_for_terminal(&link) {
                    println!("{qr}");
                }
            }
            "add" => {
                let c = node.add_contact(a).map_err(|e| e.to_string())?;
                println!("added {}", contact_display_name(&c));
            }
            "contacts" => {
                for (i, c) in node.contacts().map_err(|e| e.to_string())?.iter().enumerate() {
                    let tags = [(c.by_them, " [request]"), (c.verified, " [verified]"), (c.blocked, " [blocked]")];
                    let tags: String = tags.iter().filter(|(on, _)| *on).map(|(_, t)| *t).collect();
                    println!("{}  {}{}", i + 1, contact_display_name(c), tags);
                }
            }
            // with their identity keys (an admin list for the official feeds needs them)
            "contactkeys" => {
                for c in node.contacts().map_err(|e| e.to_string())? {
                    println!("  {}  {}", contact_display_name(&c), hex::encode(c.sk));
                }
            }
            "send" => {
                let c = nth(node, a).ok_or("no such contact")?;
                let id = node.send_text(&c.sk, rest).map_err(|e| e.to_string())?;
                println!("[queued] #{}", short_id(&id));
            }
            "edit" => {
                let c = nth(node, a).ok_or("no such contact")?;
                let last = node.history(&c.sk, 50).map_err(|e| e.to_string())?.into_iter().rev().find(|m| m.outgoing).ok_or("no message of mine there")?;
                node.edit_text(last.seq, rest, Vec::new()).map_err(|e| e.to_string())?;
                println!("[edited] #{}", short_id(&last.id));
            }
            "typing" => {
                let c = nth(node, a).ok_or("no such contact")?;
                let kind = if rest.is_empty() { "typing" } else { rest.trim() };
                let kind = mlmchat_core::envelope::ChatActionKind::from_name(kind).ok_or("no such action")?;
                let went = node.chat_action(&c.sk, kind).map_err(|e| e.to_string())?;
                println!("[action] {}", if went { "sent" } else { "not sent (too soon, or no route open to their phone)" });
            }
            "presence" => {
                let n = node.set_online(a != "off").map_err(|e| e.to_string())?;
                println!("[presence] {} told to {n} contact(s)", if a == "off" { "offline" } else { "online" });
            }
            "lastseen" => {
                node.set_last_seen_visible(a != "hide").map_err(|e| e.to_string())?;
                println!("[presence] last seen {}", if node.last_seen_visible() { "shown" } else { "hidden" });
            }
            "read" => {
                let c = nth(node, a).ok_or("no such contact")?;
                println!("{} marked read", node.mark_read(&c.sk).map_err(|e| e.to_string())?);
            }
            "history" => {
                let c = nth(node, a).ok_or("no such contact")?;
                let count = rest.parse().unwrap_or(20);
                for m in node.history(&c.sk, count).map_err(|e| e.to_string())? {
                    let text = mlmchat_core::envelope::Envelope::from_bytes(&m.envelope)
                        .ok()
                        .and_then(|e| match e.body {
                            Some(mlmchat_core::envelope::Body::Text(t)) => Some(t.body),
                            _ => None,
                        })
                        .unwrap_or_else(|| format!("({})", m.kind));
                    let who = if m.outgoing { "me" } else { "them" };
                    println!("  {who:>4} #{} [{}] {text}", short_id(&m.id), m.state.name());
                }
            }
            "sn" => {
                let c = nth(node, a).ok_or("no such contact")?;
                println!("{}", grouped(&node.security_number(&c.sk).map_err(|e| e.to_string())?));
            }
            "verify" => {
                let c = nth(node, a).ok_or("no such contact")?;
                node.set_verified(&c.sk, true).map_err(|e| e.to_string())?;
                println!("verified");
            }
            "queue" => println!("{} frames waiting", node.outbox_len().map_err(|e| e.to_string())?),
            "status" => {
                let c = nth(node, a).ok_or("no such contact")?;
                let st = node.contact_status(&c.sk).map_err(|e| e.to_string())?;
                let ago = if st.last_reached_ms == 0 { "never".to_string() } else { format!("{} s ago", mlmchat_core::util::now_ms().saturating_sub(st.last_reached_ms) / 1000) };
                let since = |ms: u64| if ms == 0 { "never".to_string() } else { format!("{} s ago", mlmchat_core::util::now_ms().saturating_sub(ms) / 1000) };
                println!(
                    "online={} reached={ago} via={} queued={} reason={} mailbox: in {} / out {}",
                    st.online, st.via, st.queued, st.reason.unwrap_or_default(), since(st.mailbox_in_ms), since(st.mailbox_out_ms)
                );
            }
            "net" => println!("{}", serde_json::to_string(&node.net_status()).unwrap_or_default()),
            "netchange" => {
                node.network_changed();
                println!("reconnecting");
            }
            "volcheck" => println!("{}", serde_json::to_string(&node.volunteer_check()).unwrap_or_default()),
            "relay" => println!("{}", serde_json::to_string(&node.relay_status()).unwrap_or_default()),
            // the Nostr relays (SPEC 20)
            "nostr" => {
                let rs = node.nostr_relays();
                println!("nostr relays: {} open for reading", rs.iter().filter(|r| r.reads && r.state == "open").count());
                for r in &rs {
                    println!("  {} state={} way={} {}{} in={} out={}", r.relay, r.state, r.way, if r.reads { "reads " } else { "" }, if r.writes { "writes" } else { "no-writes" }, r.events_in, r.events_out);
                }
            }
            // the Google mailbox relays (SPEC 19)
            "gas" => {
                let o = node.gas_overview().map_err(|e| e.to_string())?;
                println!("way to Google: {} ({} ms); named in my card: {}; my frames waiting: {}", if o.front.is_empty() { "not found yet" } else { &o.front }, o.front_ms, o.announced.len(), o.waiting);
                for r in &o.relays {
                    println!(
                        "  {} [{}] {}{}{} state={} today: {} requests, {} out, {} in, {} errors, {} ms",
                        r.rid, r.name, if r.mine { "mine " } else { "friend's " }, if r.enabled { "on " } else { "off " }, if r.announce { "announced" } else { "hidden" },
                        r.state, r.today.req, r.today.items_out, r.today.items_in, r.today.errors, r.today.ms
                    );
                }
            }
            "gasadd" => println!("added {}", node.gas_relay_add_share(a.trim()).map_err(|e| e.to_string())?),
            "gasref" => {
                let c = nth(node, a).ok_or("no such contact")?;
                let rids = node.gas_relay_add_from_contact(&c.sk).map_err(|e| e.to_string())?;
                println!("{} relays of {} in my list: {}", rids.len(), contact_display_name(&c), rids.join(" "));
            }
            "gastest" => {
                let rep = node.gas_relay_test(a.trim()).map_err(|e| e.to_string())?;
                println!("{}", serde_json::to_string(&rep).unwrap_or_default());
            }
            "gasset" => {
                let (rid, what) = (a.trim(), rest.trim());
                match what {
                    "on" => node.gas_relay_set(rid, Some(true), None, None),
                    "off" => node.gas_relay_set(rid, Some(false), None, None),
                    "announce" => node.gas_relay_set(rid, None, Some(true), None),
                    "hide" => node.gas_relay_set(rid, None, Some(false), None),
                    _ => return Err("gasset <rid> on|off|announce|hide".into()),
                }
                .map_err(|e| e.to_string())?;
                println!("ok");
            }
            "gasremove" => {
                node.gas_relay_remove(a.trim()).map_err(|e| e.to_string())?;
                println!("removed");
            }
            "kick" => println!("{}", if node.relay_kick(a.trim()) { "disconnected (kept out for an hour)" } else { "no such peer" }),
            // groups and channels (SPEC 7): N is the group's number in `groups`
            "gcreate" | "ccreate" => {
                let gid = node.group_create(&format!("{a} {rest}").trim().to_string(), "", cmd == "ccreate").map_err(|e| e.to_string())?;
                println!("[{}] {}", if cmd == "ccreate" { "channel" } else { "group" }, hex::encode(gid));
            }
            "groups" => {
                for (i, g) in node.groups().map_err(|e| e.to_string())?.iter().enumerate() {
                    let kind = if g.broadcast { "channel" } else { "group" };
                    println!("{}  {} ({kind}, {} members) {}", i + 1, g.name, g.members.len(), hex::encode(&g.group_id));
                }
            }
            "gadd" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let c = nth(node, rest.trim()).ok_or("no such contact")?;
                node.group_add(&g, &c.sk).map_err(|e| e.to_string())?;
                println!("added");
            }
            "gsend" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let id = node.group_send_text(&g, rest).map_err(|e| e.to_string())?;
                println!("[queued] #{}", short_id(&id));
            }
            "gedit" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let last = node.group_messages(&g, 50).map_err(|e| e.to_string())?.into_iter().rev().find(|m| m.outgoing).ok_or("no post of mine there")?;
                node.group_edit_text(last.seq, rest, Vec::new()).map_err(|e| e.to_string())?;
                println!("[edited] #{}", short_id(&last.id));
            }
            "react" | "greact" => {
                // react <contact n> <emoji> / greact <group n> <emoji>: on the newest message that is
                // not ours; no emoji takes ours back
                let (target, how) = if cmd == "react" {
                    let c = nth(node, a).ok_or("no such contact")?;
                    let m = node.history(&c.sk, 50).map_err(|e| e.to_string())?.into_iter().rev().find(|m| !m.outgoing).ok_or("no message from them")?;
                    node.react(&c.sk, &m.id, rest.trim()).map_err(|e| e.to_string())?;
                    (m.id, "chat")
                } else {
                    let g = nth_group(node, a).ok_or("no such group")?;
                    let m = node.group_messages(&g, 50).map_err(|e| e.to_string())?.into_iter().rev().find(|m| !m.outgoing).ok_or("no post of others there")?;
                    node.group_react(&g, &m.id, rest.trim()).map_err(|e| e.to_string())?;
                    (m.id, "group")
                };
                println!("[reacted] {how} #{} {}", short_id(&target), rest.trim());
            }
            "gread" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let max = node.group_messages(&g, 1).map_err(|e| e.to_string())?.last().map_or(0, |m| m.seq);
                println!("[seen] {} message(s) read", node.group_mark_read(&g, max).map_err(|e| e.to_string())?);
            }
            "greads" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let mine = node.group_messages(&g, 50).map_err(|e| e.to_string())?.into_iter().rev().find(|m| m.outgoing).ok_or("no post of mine there")?;
                for (sk, at) in node.group_reads(mine.seq).map_err(|e| e.to_string())? {
                    println!("  {} at {at}", name_of(node, &sk));
                }
            }
            "ghistory" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                for m in node.group_messages(&g, 30).map_err(|e| e.to_string())? {
                    let text = mlmchat_core::envelope::Envelope::from_bytes(&m.envelope)
                        .ok()
                        .and_then(|e| match e.body {
                            Some(mlmchat_core::envelope::Body::Text(t)) => Some(t.body),
                            _ => None,
                        })
                        .unwrap_or_else(|| format!("({})", m.kind));
                    let who = if m.outgoing { "me".to_string() } else { name_of(node, &m.sender) };
                    println!("  {who}: {text}");
                }
            }
            "gmembers" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let st = node.group_state(&g).map_err(|e| e.to_string())?.ok_or("no such group")?;
                for sk in st.members.keys() {
                    let role = if *sk == st.owner { " [owner]" } else if st.is_admin(sk) { " [admin]" } else { "" };
                    println!("  {}{role}", name_of(node, sk));
                }
                let now = mlmchat_core::util::now_ms();
                for (sk, until) in st.banned.iter().filter(|(_, u)| **u > now) {
                    println!("  {} [banned{}]", name_of(node, sk), if *until == u64::MAX { String::new() } else { format!(" until {until}") });
                }
            }
            "gban" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let c = nth(node, rest).ok_or("no such contact")?;
                node.group_remove(&g, &c.sk, true).map_err(|e| e.to_string())?;
                println!("banned");
            }
            "gunban" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let c = nth(node, rest).ok_or("no such contact")?;
                node.group_unban(&g, &c.sk).map_err(|e| e.to_string())?;
                println!("ban lifted");
            }
            "gleave" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                node.group_leave(&g).map_err(|e| e.to_string())?;
                println!("left");
            }
            // files (SPEC 12, 17): a photo when the name ends in .jpg/.jpeg/.png, a video for .mp4
            // (a round video with sendround), else a file
            "sendfile" | "gsendfile" | "sendround" => {
                let path = std::path::Path::new(rest.trim());
                let lower = rest.trim().to_lowercase();
                let photo = lower.ends_with(".jpg") || lower.ends_with(".jpeg") || lower.ends_with(".png");
                let round = cmd == "sendround";
                let video = round || lower.ends_with(".mp4");
                let info = mlmchat_core::node::MediaInfo {
                    kind: if round { 4 } else if video { 2 } else if photo { 1 } else { 0 },
                    mime: if video { "video/mp4".into() } else if photo { "image/jpeg".into() } else { "application/octet-stream".into() },
                    name: path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
                    // a round video is square; the size and length only shape the bubble until it plays
                    width: if round { 384 } else { 0 },
                    height: if round { 384 } else { 0 },
                    duration_ms: if round { 2000 } else { 0 },
                    ..Default::default()
                };
                let id = if cmd != "gsendfile" {
                    let c = nth(node, a).ok_or("no such contact")?;
                    node.send_media(&c.sk, path, &info).map_err(|e| e.to_string())?
                } else {
                    let g = nth_group(node, a).ok_or("no such group")?;
                    node.group_send_media(&g, path, &info).map_err(|e| e.to_string())?
                };
                println!("[queued] #{}", short_id(&id));
            }
            // photos to contact n as one album (SPEC 5 `album_id`): paths split by '|'
            "sendalbum" => {
                let c = nth(node, a).ok_or("no such contact")?;
                let album = random::<16>().to_vec();
                for p in rest.split('|').map(str::trim).filter(|p| !p.is_empty()) {
                    let path = std::path::Path::new(p);
                    let info = mlmchat_core::node::MediaInfo {
                        kind: 1,
                        mime: "image/jpeg".into(),
                        name: path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
                        album_id: album.clone(),
                        ..Default::default()
                    };
                    let id = node.send_media(&c.sk, path, &info).map_err(|e| e.to_string())?;
                    println!("[queued] #{} album {}", short_id(&id), hex::encode(&album[..4]));
                }
            }
            // the newest file from contact n, written to a path once it is whole
            "savefile" => {
                let c = nth(node, a).ok_or("no such contact")?;
                let rows = node.history(&c.sk, 50).map_err(|e| e.to_string())?;
                let m = rows
                    .iter()
                    .rev()
                    .find_map(|r| match mlmchat_core::envelope::Envelope::from_bytes(&r.envelope).ok()?.body {
                        Some(mlmchat_core::envelope::Body::Media(m)) => Some(m),
                        _ => None,
                    })
                    .ok_or("no file from them")?;
                let id: [u8; 32] = m.blob_id.as_slice().try_into().map_err(|_| "bad id")?;
                let key: [u8; 32] = m.key.as_slice().try_into().map_err(|_| "bad key")?;
                match node.media_status(&id).map_err(|e| e.to_string())? {
                    Some((have, total, true)) => {
                        let n = node.open_media(&id, &key, std::path::Path::new(rest.trim())).map_err(|e| e.to_string())?;
                        println!("saved {n} bytes ({have}/{total} chunks)");
                    }
                    Some((have, total, false)) => {
                        node.fetch_media(&id).map_err(|e| e.to_string())?;
                        println!("not whole yet: {have}/{total}; asked for the rest");
                    }
                    None => println!("unknown file"),
                }
            }
            // profile and group photos (SPEC 10, 7.2): a path sets one, "-" removes it
            "setphoto" => {
                let path = rest.trim();
                let p = node.set_photo((path != "-").then(|| std::path::Path::new(path)), &[]).map_err(|e| e.to_string())?;
                println!("{}", p.map(|p| format!("photo {}", hex::encode(&p.blob_id[..6]))).unwrap_or_else(|| "no photo".into()));
            }
            "gsetphoto" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let path = rest.trim();
                let st = node.group_set_photo(&g, (path != "-").then(|| std::path::Path::new(path)), &[]).map_err(|e| e.to_string())?;
                println!("{}", st.photo.map(|(p, _)| format!("group photo {}", hex::encode(&p.blob_id[..6]))).unwrap_or_else(|| "no group photo".into()));
            }
            // the photo of contact n (or of group g with "gphoto"), written to a path once whole
            "photo" | "gphoto" => {
                let (id, key) = if cmd == "photo" {
                    let c = nth(node, a).ok_or("no such contact")?;
                    let p = node.photo_of(&c.sk).map_err(|e| e.to_string())?.ok_or("no photo")?;
                    let (id, key, _) = node.media_by_short(p.short).map_err(|e| e.to_string())?.ok_or("unknown file")?;
                    (id, key)
                } else {
                    let g = nth_group(node, a).ok_or("no such group")?;
                    let st = node.group_state(&g).map_err(|e| e.to_string())?.ok_or("no such group")?;
                    let (p, _) = st.photo.ok_or("no group photo")?;
                    let id: [u8; 32] = p.blob_id.as_slice().try_into().map_err(|_| "bad id")?;
                    let key: [u8; 32] = p.key.as_slice().try_into().map_err(|_| "bad key")?;
                    (id, key)
                };
                match node.media_status(&id).map_err(|e| e.to_string())? {
                    Some((_, _, true)) => {
                        let n = node.open_media(&id, &key, std::path::Path::new(rest.trim())).map_err(|e| e.to_string())?;
                        println!("saved {n} bytes");
                    }
                    Some((have, total, false)) => {
                        node.fetch_media(&id).map_err(|e| e.to_string())?;
                        println!("not whole yet: {have}/{total}; asked for the rest");
                    }
                    None => println!("unknown file"),
                }
            }
            // invite links (SPEC 7.4): "ginvite <g> [approve]" makes one; "join <link>" asks
            "ginvite" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let link = node.group_invite_create(&g, rest.trim() == "approve", 0, 0).map_err(|e| e.to_string())?;
                println!("{link}");
            }
            "ginvites" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                for v in node.group_invites(&g).map_err(|e| e.to_string())? {
                    let mine = if v.link.is_some() { " (mine)" } else { "" };
                    println!("  {} uses {}{}{mine}", hex::encode(&v.invite_id[..4]), v.uses, if v.need_approval { " approval" } else { "" });
                }
            }
            "join" => {
                let g = node.group_join(a).map_err(|e| e.to_string())?;
                println!("asked to join {}", hex::encode(g));
            }
            "grequests" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                for (i, r) in node.group_join_requests(&g).map_err(|e| e.to_string())?.iter().enumerate() {
                    println!("  {}  {}", i + 1, r.name);
                }
            }
            "gapprove" | "gdecline" => {
                let g = nth_group(node, a).ok_or("no such group")?;
                let reqs = node.group_join_requests(&g).map_err(|e| e.to_string())?;
                let r = rest.trim().parse::<usize>().ok().and_then(|i| reqs.get(i.wrapping_sub(1))).ok_or("no such request")?;
                node.group_join_decide(&g, &r.sk, cmd == "gapprove").map_err(|e| e.to_string())?;
                println!("{}", if cmd == "gapprove" { "approved" } else { "declined" });
            }
            // official feeds (T38): run with --feeds <json file> (the app's config)
            "feeds" => {
                for (i, d) in node.feeds().iter().enumerate() {
                    let st = node.feed_state(&d.feed_id).map_err(|e| e.to_string())?.unwrap_or_default();
                    println!("  {}  {} ({}, {} posts, {} admins)", i + 1, d.name, if d.broadcast { "channel" } else { "group" }, st.posts.len(), st.admins.len());
                    for a in &st.admins {
                        println!("       admin {}", &hex::encode(a)[..12]);
                    }
                    let now = mlmchat_core::util::now_ms();
                    for (sk, until) in &st.banned {
                        let left = until.saturating_sub(now);
                        println!("       banned {} {}", &hex::encode(sk)[..12], if left == 0 { "(over)".to_string() } else if left > 365 * 86_400_000 { "for good".to_string() } else { format!("{} min more", left / 60_000) });
                    }
                }
            }
            "fposts" => {
                let d = node.feeds().get(a.parse::<usize>().unwrap_or(0).wrapping_sub(1)).cloned().ok_or("no such feed")?;
                for p in node.feed_state(&d.feed_id).map_err(|e| e.to_string())?.map(|s| s.posts).unwrap_or_default() {
                    let env = mlmchat_core::envelope::Envelope::from_bytes(&p.envelope).ok();
                    // the post's own id and the one it answers (8 hex each), to follow replies
                    let reply = env.as_ref().filter(|e| !e.reply_to.is_empty()).map(|e| format!(" -> {}", &hex::encode(&e.reply_to)[..8])).unwrap_or_default();
                    let text = match env.and_then(|e| e.body) {
                        Some(mlmchat_core::envelope::Body::Text(t)) => t.body,
                        _ => "(media)".into(),
                    };
                    println!("  [{}{reply}] {} ({}): {text}", &hex::encode(&p.id)[..8], p.name, hex::encode(p.author));
                }
            }
            "fsend" => {
                let d = node.feeds().get(a.parse::<usize>().unwrap_or(0).wrapping_sub(1)).cloned().ok_or("no such feed")?;
                node.feed_send_text(&d.feed_id, rest).map_err(|e| e.to_string())?;
                println!("posted");
            }
            "fseed" => {
                let bytes = hex::decode(a).map_err(|_| "bad hex")?;
                let item = mlmchat_core::feed::SignedFeedItem::from_bytes(&bytes).map_err(|e| e.to_string())?;
                node.set_feeds(node.feeds(), &[item]).map_err(|e| e.to_string())?;
                node.feed_sync_now();
                println!("taken");
            }
            "fsync" => {
                node.feed_sync_now();
                println!("asked");
            }
            // the official feeds' copy (SPEC 18.2): read it now, then what the feeds and the count say
            "msync" => {
                node.mirror_sync_now();
                for d in node.feeds() {
                    let st = node.feed_state(&d.feed_id).map_err(|e| e.to_string())?.unwrap_or_default();
                    println!("  {} ({} posts)", d.name, st.posts.len());
                }
                println!("members: {}", node.feed_member_count().map_err(|e| e.to_string())?);
            }
            "fcount" => println!("members: {}", node.feed_member_count().map_err(|e| e.to_string())?),
            // the relay field test (docs/RELAY-FIELD-TEST.fa.md)
            "echo" => {
                ECHO.store(a.trim() != "off", Ordering::SeqCst);
                println!("echo {}", if ECHO.load(Ordering::SeqCst) { "on: PING is answered PONG" } else { "off" });
            }
            "pingtest" => {
                let c = nth(node, a).ok_or("no such contact")?;
                let mut it = rest.split_whitespace();
                let count: u32 = it.next().and_then(|x| x.parse().ok()).unwrap_or(10).clamp(1, 500);
                let gap: f64 = it.next().and_then(|x| x.parse().ok()).unwrap_or(5.0_f64).clamp(0.2, 600.0);
                println!("pingtest: {count} pings to {}, {gap} s apart", contact_display_name(&c));
                let n = node.clone();
                std::thread::spawn(move || ping_test(n, c, count, gap));
            }
            "gasbytes" => {
                node.gas_flush_stats();
                let (r, o, i) = gas_totals(node);
                println!("today, all relays: {r} requests, {o} bytes out, {i} bytes in (payload, without HTTP and TLS)");
                let (wo, wi) = wire_totals();
                println!("on the wire since start: {wo} bytes out, {wi} bytes in (TLS, HTTP heads, redirects and front probes included)");
            }
            "wait" => std::thread::sleep(Duration::from_secs_f64(a.parse().unwrap_or(1.0))),
            "help" => println!("{USAGE}"),
            "quit" | "exit" => return Err("quit".into()),
            _ => return Err("unknown command; try help".to_string()),
        }
        Ok(())
    })();
    match res {
        Err(e) if e == "quit" => false,
        Err(e) => {
            println!("error: {e}");
            true
        }
        Ok(()) => true,
    }
}
