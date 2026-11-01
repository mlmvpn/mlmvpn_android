//! mlm-observer: run on a home PC during MLM CHAT phase 0. See README.fa.md.
//!
//!   mlm-observer [--addr IP]... [--port 40100] [--alt 40101] [--extra-tcp 443,8443]
//!                [--bind IP] [--name NAME] [--key FILE] [--upnp] [--verbose] [--card-only]
//!                [--p2p-port 40200] [--iroh-port 40300] [--no-spike]
//!
//! Besides the observer itself it runs the two phase-0 spikes of PLAN section 17.2: the libp2p
//! relay (TCP+UDP p2p-port) and the iroh relay + hub (iroh-port .. iroh-port+3).

use anyhow::{anyhow, bail, Context, Result};
use igd_next::{aio::tokio::search_gateway, PortMappingProtocol, SearchOptions};
use probe_core::card::ObserverCard;
use probe_core::observer::{default_bind, serve, ObserverCfg, DEFAULT_PORT};
use probe_core::util::{b64d, b64e, ip_kind, IpKind};
use serde_json::json;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::time::Duration;

struct Args {
    addrs: Vec<IpAddr>,
    port: u16,
    alt: Option<u16>,
    extra: Vec<u16>,
    bind: Option<IpAddr>,
    name: String,
    key: PathBuf,
    upnp: bool,
    verbose: bool,
    card_only: bool,
    p2p_port: u16,
    iroh_port: u16,
    spike: bool,
}

fn parse_args() -> Result<Args> {
    let mut a = Args {
        addrs: vec![],
        port: DEFAULT_PORT,
        alt: None,
        extra: vec![],
        bind: None,
        name: String::new(),
        key: PathBuf::from("observer-key.json"),
        upnp: false,
        verbose: false,
        card_only: false,
        p2p_port: spike_libp2p::DEFAULT_RELAY_PORT,
        iroh_port: spike_iroh::DEFAULT_BASE_PORT,
        spike: true,
    };
    let mut it = std::env::args().skip(1);
    while let Some(k) = it.next() {
        let mut val = || it.next().ok_or_else(|| anyhow!("{k} needs a value"));
        match k.as_str() {
            "--addr" => a.addrs.push(val()?.parse().context("--addr")?),
            "--port" => a.port = val()?.parse().context("--port")?,
            "--alt" => a.alt = Some(val()?.parse().context("--alt")?),
            "--extra-tcp" => {
                for p in val()?.split(',').filter(|s| !s.trim().is_empty()) {
                    a.extra.push(p.trim().parse().context("--extra-tcp")?);
                }
            }
            "--bind" => a.bind = Some(val()?.parse().context("--bind")?),
            "--name" => a.name = val()?,
            "--key" => a.key = PathBuf::from(val()?),
            "--upnp" => a.upnp = true,
            "--verbose" => a.verbose = true,
            "--card-only" => a.card_only = true,
            "--p2p-port" => a.p2p_port = val()?.parse().context("--p2p-port")?,
            "--iroh-port" => a.iroh_port = val()?.parse().context("--iroh-port")?,
            "--no-spike" => a.spike = false,
            "-h" | "--help" => {
                println!("{}", include_str!("usage.txt"));
                std::process::exit(0);
            }
            other => bail!("unknown option {other} (see --help)"),
        }
    }
    Ok(a)
}

/// The observer's Noise key lives in one small file next to where it runs. Keep it: phones that
/// already scanned the card need the same key. Never commit it.
fn load_or_create_key(path: &PathBuf) -> Result<(Vec<u8>, Vec<u8>)> {
    if let Ok(text) = std::fs::read_to_string(path) {
        let v: serde_json::Value = serde_json::from_str(&text).context("key file is not json")?;
        let sk = b64d(v["sk"].as_str().unwrap_or(""))?;
        let pk = b64d(v["pk"].as_str().unwrap_or(""))?;
        if sk.len() != 32 || pk.len() != 32 {
            bail!("key file is damaged; delete it to make a new key (phones must then scan the new card)");
        }
        return Ok((sk, pk));
    }
    let (sk, pk) = probe_core::noise::keypair()?;
    std::fs::write(path, serde_json::to_string_pretty(&json!({ "sk": b64e(&sk), "pk": b64e(&pk) }))?)
        .with_context(|| format!("cannot write {}", path.display()))?;
    println!("کلید تازه ساخته شد: {}", path.display());
    Ok((sk, pk))
}

/// Keys of the two spike relays, next to the observer key: the libp2p identity, the iroh hub's
/// secret, and the iroh relay's self-signed TLS certificate (phones pin its hash). Kept across
/// runs so a scanned card stays valid. Never commit it.
struct SpikeKeys {
    p2p: Vec<u8>,
    hub: [u8; 32],
    cert: Vec<u8>,
    cert_key: Vec<u8>,
}

fn load_or_create_spike_keys(path: &PathBuf) -> Result<SpikeKeys> {
    if let Ok(text) = std::fs::read_to_string(path) {
        let v: serde_json::Value = serde_json::from_str(&text).context("spike key file is not json")?;
        let hub: [u8; 32] = b64d(v["hub"].as_str().unwrap_or(""))?.try_into().map_err(|_| anyhow!("spike key file is damaged; delete it"))?;
        let k = SpikeKeys { p2p: b64d(v["p2p"].as_str().unwrap_or(""))?, hub, cert: b64d(v["cert"].as_str().unwrap_or(""))?, cert_key: b64d(v["cert_key"].as_str().unwrap_or(""))? };
        spike_libp2p::keypair_from_bytes(&k.p2p).context("spike key file is damaged; delete it")?;
        return Ok(k);
    }
    let (cert, cert_key) = spike_iroh::server::make_cert()?;
    let k = SpikeKeys {
        p2p: spike_libp2p::keypair_to_bytes(&spike_libp2p::Keypair::generate_ed25519()),
        hub: probe_core::util::rand_bytes::<32>(),
        cert,
        cert_key,
    };
    let j = json!({ "p2p": b64e(&k.p2p), "hub": b64e(&k.hub), "cert": b64e(&k.cert), "cert_key": b64e(&k.cert_key) });
    std::fs::write(path, serde_json::to_string_pretty(&j)?).with_context(|| format!("cannot write {}", path.display()))?;
    println!("کلیدهای تازهٔ آزمایش libp2p و iroh ساخته شد: {}", path.display());
    Ok(k)
}

fn lan_addresses() -> Vec<IpAddr> {
    let mut out: Vec<IpAddr> = probe_core::util::interfaces()
        .into_iter()
        .map(|i| i.ip)
        .filter(|ip| matches!(ip_kind(*ip), IpKind::Public4 | IpKind::Private4 | IpKind::Global6))
        .collect();
    out.sort_by_key(|ip| match ip_kind(*ip) {
        IpKind::Public4 => 0,
        IpKind::Global6 => 1,
        _ => 2,
    });
    out.dedup();
    out
}

async fn upnp_open(ports: &[u16]) -> Result<(IpAddr, Vec<(PortMappingProtocol, u16)>)> {
    let gw = search_gateway(SearchOptions { timeout: Some(Duration::from_secs(5)), ..Default::default() }).await?;
    let ext = gw.get_external_ip().await?;
    let local = probe_core::util::local_ip_towards(gw.addr.ip()).ok_or_else(|| anyhow!("no local address toward the modem"))?;
    let mut done = vec![];
    for p in ports {
        for proto in [PortMappingProtocol::TCP, PortMappingProtocol::UDP] {
            match gw.add_port(proto, *p, SocketAddr::new(local, *p), 0, "mlm-observer").await {
                Ok(()) => done.push((proto, *p)),
                Err(e) => println!("UPnP: باز کردن درگاه {p} {proto:?} نشد: {e}"),
            }
        }
    }
    Ok((ext, done))
}

async fn upnp_close(open: &[(PortMappingProtocol, u16)]) {
    if open.is_empty() {
        return;
    }
    if let Ok(gw) = search_gateway(SearchOptions { timeout: Some(Duration::from_secs(5)), ..Default::default() }).await {
        for (proto, p) in open {
            let _ = gw.remove_port(*proto, *p).await;
        }
        println!("UPnP: درگاه‌ها بسته شد.");
    }
}

fn print_card(card: &ObserverCard) -> Result<()> {
    let text = card.encode();
    let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::L)?;
    let art = code
        .render::<qrcode::render::unicode::Dense1x2>()
        .dark_color(qrcode::render::unicode::Dense1x2::Light)
        .light_color(qrcode::render::unicode::Dense1x2::Dark)
        .quiet_zone(true)
        .build();
    let svg = code.render::<qrcode::render::svg::Color>().min_dimensions(480, 480).build();
    std::fs::write("observer-card.svg", svg)?;
    std::fs::write("observer-card.txt", &text)?;
    println!("\n{art}\n");
    println!("کارت ناظر (همین را در netprobe بخوانید یا بچسبانید):\n{text}\n");
    println!("همین کارت در observer-card.txt و observer-card.svg هم ذخیره شد.");
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    let a = parse_args()?;
    let alt = a.alt.unwrap_or(a.port + 1);
    let (sk, pk) = load_or_create_key(&a.key)?;

    let mut ports = vec![a.port, alt];
    ports.extend(a.extra.iter().copied());
    let spike_keys = if a.spike {
        ports.push(a.p2p_port);
        ports.extend(a.iroh_port + 1..=a.iroh_port + 3);
        let kp = a.key.with_file_name("spike-keys.json");
        Some(load_or_create_spike_keys(&kp)?)
    } else {
        None
    };
    let mut addrs = a.addrs.clone();
    let mut upnp_done = vec![];
    if a.upnp {
        match upnp_open(&ports).await {
            Ok((ext, done)) => {
                println!("UPnP: مودم {} درگاه را باز کرد.", done.len());
                if matches!(ip_kind(ext), IpKind::Private4 | IpKind::Cgnat4) {
                    println!("هشدار: نشانی بیرونی مودم خصوصی است؛ خط خانه پشت NAT سرویس‌دهنده است و گوشی‌های بیرون به این ناظر نمی‌رسند.");
                } else if !addrs.contains(&ext) {
                    addrs.insert(0, ext);
                }
                upnp_done = done;
            }
            Err(e) => println!("UPnP: مودم پیدا نشد یا پاسخ نداد ({e}). درگاه‌ها را دستی در مودم باز کنید."),
        }
    }
    if addrs.is_empty() {
        addrs = lan_addresses();
        println!("هشدار: نشانی عمومی داده نشد (--addr). کارت فقط نشانی‌های شبکهٔ محلی را دارد؛ برای آزمون روی دادهٔ همراه، نشانی عمومی مودم را با --addr بدهید یا --upnp را امتحان کنید.");
    }
    if addrs.is_empty() {
        bail!("no address for the card: pass --addr");
    }
    let card = ObserverCard {
        v: 1,
        name: a.name.clone(),
        pk: b64e(&pk),
        addrs: addrs.iter().map(|i| i.to_string()).collect(),
        port: a.port,
        alt,
        extra_tcp: a.extra.clone(),
        p2p: spike_keys.as_ref().map(|k| {
            let peer = spike_libp2p::keypair_from_bytes(&k.p2p).map(|kp| kp.public().to_peer_id().to_string()).unwrap_or_default();
            json!({ "peer": peer, "port": a.p2p_port })
        }),
        iroh: spike_keys.as_ref().map(|k| {
            json!({ "hub": spike_iroh::server::hub_id(&k.hub).to_string(), "port": a.iroh_port, "pin": b64e(&spike_iroh::cert_pin(&k.cert)) })
        }),
    };
    print_card(&card)?;
    if a.card_only {
        upnp_close(&upnp_done).await;
        return Ok(());
    }

    probe_core::log::set_echo(true);
    if let Some(k) = spike_keys {
        spike_libp2p::set_echo(true);
        spike_iroh::set_echo(true);
        let key = spike_libp2p::keypair_from_bytes(&k.p2p)?;
        let (p2p_port, iroh_port) = (a.p2p_port, a.iroh_port);
        let ext = addrs.clone();
        // A spike that cannot start (say, its port is taken) is reported; the observer goes on.
        tokio::spawn(async move {
            if let Err(e) = spike_libp2p::serve_relay(key, p2p_port, ext).await {
                println!("رلهٔ libp2p کار نکرد: {e:#}");
            }
        });
        tokio::spawn(async move {
            let cfg = spike_iroh::server::Cfg { secret: k.hub, cert_der: k.cert, key_der: k.cert_key, base_port: iroh_port };
            if let Err(e) = spike_iroh::server::serve_relay_and_hub(cfg).await {
                println!("رلهٔ iroh کار نکرد: {e:#}");
            }
        });
    }
    let mut cfg = ObserverCfg::new(sk);
    cfg.bind = a.bind.unwrap_or_else(default_bind);
    cfg.port = a.port;
    cfg.alt = alt;
    cfg.extra_tcp = a.extra.clone();
    cfg.verbose = a.verbose;
    println!("ناظر روشن است. درگاه‌ها: TCP {:?} و UDP {} و {}. برای خاموش کردن Ctrl+C.", ports, a.port, alt);
    if a.spike {
        println!("آزمایش libp2p: TCP و UDP {}. آزمایش iroh: TCP {} و UDP {} و {}.", a.p2p_port, a.iroh_port + 1, a.iroh_port + 2, a.iroh_port + 3);
    }
    tokio::select! {
        r = serve(cfg) => { upnp_close(&upnp_done).await; r?; }
        _ = tokio::signal::ctrl_c() => { upnp_close(&upnp_done).await; }
    }
    Ok(())
}
