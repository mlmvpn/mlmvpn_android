//! The measurement exchange between two phones, run inside TLS (TCP) or QUIC, never in clear:
//!
//! ```text
//! client: HELLO <sid> <path>        server: OK <sid>
//! client: PING <i>  (x10)           server: PONG <i>
//! client: UP <n> + n bytes          server: GOT <n> <ms the server spent receiving>
//! client: DOWN <n>                  server: DATA <n> + n bytes
//! client: BYE
//! ```

use crate::util::{mbps, ms, rand_fill, stats};
use anyhow::{anyhow, bail, Result};
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::time::timeout;

pub const DEFAULT_BYTES: u64 = 1 << 20;
const MAX_BYTES: u64 = 16 << 20;
const PINGS: u32 = 10;
const LINE_TIMEOUT: Duration = Duration::from_secs(15);
const SERVER_IDLE: Duration = Duration::from_secs(300);

fn transfer_timeout(bytes: u64) -> Duration {
    // 1 MiB on a 64 kbit/s line takes ~130 s; allow that, and more for bigger transfers.
    Duration::from_secs(60 + bytes / 4096)
}

async fn line<S: AsyncRead + Unpin>(io: &mut BufReader<S>) -> Result<String> {
    line_within(io, LINE_TIMEOUT).await
}

async fn line_within<S: AsyncRead + Unpin>(io: &mut BufReader<S>, limit: Duration) -> Result<String> {
    let mut s = String::new();
    let n = timeout(limit, io.read_line(&mut s)).await??;
    if n == 0 {
        bail!("connection closed");
    }
    if s.len() > 200 {
        bail!("line too long");
    }
    Ok(s.trim_end().to_string())
}

async fn put<S: AsyncWrite + Unpin>(io: &mut S, s: &str) -> Result<()> {
    io.write_all(s.as_bytes()).await?;
    io.write_all(b"\n").await?;
    io.flush().await?;
    Ok(())
}

async fn send_bytes<S: AsyncWrite + Unpin>(io: &mut S, n: u64) -> Result<()> {
    let mut buf = vec![0u8; 32 * 1024];
    rand_fill(&mut buf);
    let mut left = n;
    while left > 0 {
        let k = left.min(buf.len() as u64) as usize;
        io.write_all(&buf[..k]).await?;
        left -= k as u64;
    }
    io.flush().await?;
    Ok(())
}

async fn recv_bytes<S: AsyncRead + Unpin>(io: &mut S, n: u64) -> Result<()> {
    let mut buf = vec![0u8; 32 * 1024];
    let mut left = n;
    while left > 0 {
        let k = left.min(buf.len() as u64) as usize;
        io.read_exact(&mut buf[..k]).await?;
        left -= k as u64;
    }
    Ok(())
}

/// Client side. `bytes` = 0 skips both transfers (latency only).
pub async fn client<S: AsyncRead + AsyncWrite + Unpin>(io: S, my_sid: &str, path: &str, bytes: u64) -> Result<Value> {
    let mut io = BufReader::new(io);
    let t0 = Instant::now();
    put(&mut io, &format!("HELLO {my_sid} {path}")).await?;
    let ok = line(&mut io).await?;
    let peer_sid = ok.strip_prefix("OK ").ok_or_else(|| anyhow!("unexpected greeting"))?.to_string();
    let hello_ms = ms(t0.elapsed());

    let mut rtts = vec![];
    for i in 0..PINGS {
        let t = Instant::now();
        put(&mut io, &format!("PING {i}")).await?;
        let l = line(&mut io).await?;
        if l != format!("PONG {i}") {
            bail!("ping {i}: unexpected answer");
        }
        rtts.push(ms(t.elapsed()));
    }

    let mut out = json!({ "peer_sid": peer_sid, "hello_ms": hello_ms, "rtt_ms": stats(&rtts) });
    if bytes > 0 {
        let n = bytes.min(MAX_BYTES);
        let t = Instant::now();
        put(&mut io, &format!("UP {n}")).await?;
        timeout(transfer_timeout(n), send_bytes(&mut io, n)).await??;
        // send_bytes returns once the data is in the socket buffer; on a slow line the answer
        // comes only after the last byte has crossed, so wait as long as a transfer may take.
        let got = line_within(&mut io, transfer_timeout(n)).await?;
        let up_ms = t.elapsed();
        let server_ms: f64 = got.split_whitespace().nth(2).and_then(|x| x.parse().ok()).unwrap_or(-1.0);
        out["up"] = json!({ "bytes": n, "ms": ms(up_ms), "mbps": mbps(n, up_ms), "server_ms": server_ms });

        let t = Instant::now();
        put(&mut io, &format!("DOWN {n}")).await?;
        let hdr = line(&mut io).await?;
        if hdr != format!("DATA {n}") {
            bail!("download: unexpected header");
        }
        timeout(transfer_timeout(n), recv_bytes(&mut io, n)).await??;
        let down_ms = t.elapsed();
        out["down"] = json!({ "bytes": n, "ms": ms(down_ms), "mbps": mbps(n, down_ms) });
    }
    let _ = put(&mut io, "BYE").await;
    let _ = io.get_mut().shutdown().await;
    Ok(out)
}

/// Server side: answers one client until BYE. Returns what the client said about itself.
pub async fn server<S: AsyncRead + AsyncWrite + Unpin>(io: S, my_sid: &str) -> Result<Value> {
    let mut io = BufReader::new(io);
    let hello = line(&mut io).await?;
    let mut parts = hello.split_whitespace();
    if parts.next() != Some("HELLO") {
        bail!("expected HELLO");
    }
    let peer_sid: String = parts.next().unwrap_or("").chars().take(16).collect();
    let path: String = parts.next().unwrap_or("").chars().take(32).collect();
    put(&mut io, &format!("OK {my_sid}")).await?;
    let mut info = json!({ "peer_sid": peer_sid, "path": path, "pings": 0, "up": false, "down": false });
    loop {
        // Long wait: after a download the client is still receiving while the server waits.
        let l = match line_within(&mut io, SERVER_IDLE).await {
            Ok(l) => l,
            Err(_) => break,
        };
        let mut w = l.split_whitespace();
        match (w.next(), w.next()) {
            (Some("PING"), Some(i)) => {
                put(&mut io, &format!("PONG {i}")).await?;
                info["pings"] = json!(info["pings"].as_u64().unwrap_or(0) + 1);
            }
            (Some("UP"), Some(n)) => {
                let n: u64 = n.parse()?;
                if n > MAX_BYTES {
                    bail!("upload too large");
                }
                let t = Instant::now();
                timeout(transfer_timeout(n), recv_bytes(&mut io, n)).await??;
                put(&mut io, &format!("GOT {n} {}", ms(t.elapsed()))).await?;
                info["up"] = json!(true);
            }
            (Some("DOWN"), Some(n)) => {
                let n: u64 = n.parse()?;
                if n > MAX_BYTES {
                    bail!("download too large");
                }
                put(&mut io, &format!("DATA {n}")).await?;
                timeout(transfer_timeout(n), send_bytes(&mut io, n)).await??;
                info["down"] = json!(true);
            }
            (Some("BYE"), _) => break,
            _ => bail!("unknown request"),
        }
    }
    let _ = io.get_mut().shutdown().await;
    Ok(info)
}
