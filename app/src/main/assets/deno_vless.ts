// ============================================================================
// mlmvpn — VLESS over WebSocket backend for Deno Deploy
// ----------------------------------------------------------------------------
// Standalone anti-filtering proxy. Runs on Deno Deploy (free, no credit card).
// Parses inbound WebSocket connections, decodes the VLESS header, and pipes
// traffic to the destination via Deno.connect().
//
// Deploy:  https://dash.deno.com  ->  new Playground / project  ->  paste this
// Entry:   main.ts   (Deno.serve is the entrypoint)
//
// Supports VLESS and Trojan, over both WebSocket and xHTTP transports.
// ============================================================================

import { createHash } from "node:crypto";

// ---- CONFIG ----------------------------------------------------------------
// Generate your own with:  crypto.randomUUID()  (or `deno eval "console.log(crypto.randomUUID())"`)
// Read UUID from the deployment env var (injected by the Android app at deploy
// time). Falls back to a hardcoded value for manual/standalone deploys.
const UUID = (typeof Deno !== "undefined" && Deno.env.get("VLESS_UUID")) ||
  "4392e015-b6f3-4963-887e-43d3582aa04f";

// Optional: obscure path can also be injected per-deployment via env var.
const WS_PATH_ENV = (typeof Deno !== "undefined" && Deno.env.get("WS_PATH")) || "";

// Obscure WebSocket path. VLESS is ONLY triggered on an exact match; every
// other request is served the decoy site. This defends against censor
// active-probing (the endpoint looks like a normal, boring website).
const WS_PATH = WS_PATH_ENV || "/api/v1/stream";

// xHTTP (SplitHTTP) base path — a SEPARATE transport on the same port that
// does NOT hold a permanent socket (uploads are discrete POSTs), which keeps
// Deno's "memory time" usage far lower than a long-lived WebSocket.
// Requests look like  GET <XHTTP_PATH>/<sessionId>  and
// POST <XHTTP_PATH>/<sessionId>/<seq>.
const XHTTP_PATH = (typeof Deno !== "undefined" && Deno.env.get("XHTTP_PATH")) ||
  "/api/v2/media";

// Set to false in production to keep the Deno dashboard logs clean/quiet.
const VERBOSE = false;

// Idle handling: a proxy keeps the Deno isolate resident (and burns "memory
// time") for as long as a connection is open — even when nothing flows. Since
// VPN tunnels are idle most of the time, we close a session after a stretch of
// no traffic so the isolate can be released. The same timer flushes usage
// periodically so long transfers show up before they finish.
const FLUSH_MS = 9000;          // usage flush / idle check interval
const IDLE_CLOSE_TICKS = 5;     // ~45s of no REAL traffic -> close the session
// Only "substantial" traffic in a window counts as activity. Keepalive pings
// are tiny, so a tunnel carrying only keepalives is treated as idle and closed
// (freeing the isolate) — this is the main lever against Deno's memory-time.
const IDLE_MIN_BYTES = 3072;

// Pre-parse the UUID into 16 raw bytes for fast comparison.
const UUID_BYTES = uuidToBytes(UUID);

// Trojan password (defaults to the UUID). Trojan clients send
// hex(SHA-224(password)) as a 56-char ASCII header, which we precompute here.
const TROJAN_PASS = (typeof Deno !== "undefined" && Deno.env.get("TROJAN_PASS")) || UUID;
const TROJAN_HEX = createHash("sha224").update(TROJAN_PASS).digest("hex"); // 56 hex chars
const TROJAN_HEX_BYTES = new TextEncoder().encode(TROJAN_HEX);             // 56 ASCII bytes

// ---- USAGE COUNTERS ---------------------------------------------------------
// Prefer Deno KV: a single set of counters SHARED across all isolates, so the
// total is accurate no matter which isolate handled the traffic or which one
// answers the /stats poll. If KV is unavailable we fall back to per-isolate
// in-memory counters (the app then accumulates via BOOT_ID).
let kv: Deno.Kv | null = null;
try {
  // deno-lint-ignore no-explicit-any
  if (typeof (Deno as any).openKv === "function") kv = await Deno.openKv();
} catch {
  kv = null;
}

const BOOT_ID = crypto.randomUUID();
const STATS = { requests: 0, up: 0, down: 0, since: Date.now() };

// up   = bytes received FROM the client  = user UPLOAD
// down = bytes sent TO the client        = user DOWNLOAD
function bumpStats(requests: number, up: number, down: number): void {
  STATS.requests += requests;
  STATS.up += up;
  STATS.down += down;
  if (kv && (requests || up || down)) {
    kv.atomic()
      .sum(["stats", "requests"], BigInt(requests))
      .sum(["stats", "up"], BigInt(up))
      .sum(["stats", "down"], BigInt(down))
      .commit()
      .catch(() => {}); // never let counter errors break the proxy
  }
}

async function statsResponse(): Promise<Response> {
  if (kv) {
    try {
      const [r, u, d] = await kv.getMany<[Deno.KvU64, Deno.KvU64, Deno.KvU64]>([
        ["stats", "requests"], ["stats", "up"], ["stats", "down"],
      ]);
      return Response.json({
        mode: "kv",
        now: Date.now(),
        requests: Number(r.value?.value ?? 0n),
        up: Number(u.value?.value ?? 0n),
        down: Number(d.value?.value ?? 0n),
      }, { headers: { "cache-control": "no-store" } });
    } catch { /* fall through to in-memory */ }
  }
  return Response.json({
    mode: "mem",
    boot: BOOT_ID,
    since: STATS.since,
    now: Date.now(),
    requests: STATS.requests,
    up: STATS.up,
    down: STATS.down,
  }, { headers: { "cache-control": "no-store" } });
}

// ---- xHTTP (SplitHTTP packet-up) TRANSPORT ---------------------------------
// Unlike WebSocket, xHTTP does not hold a permanent bidirectional socket:
// uploads arrive as discrete POST requests and the download is a streaming
// GET. This keeps Deno's "memory time" much lower for the same traffic.
interface XSession {
  buf: Map<number, Uint8Array>; // out-of-order upload packets, keyed by seq
  want: number; // next seq to consume
  waiters: Array<() => void>; // wake the reassembler when a packet arrives
  closed: boolean;
  reap: number; // reap timer id
}
const XSESSIONS = new Map<string, XSession>();

function xGet(id: string): XSession {
  let s = XSESSIONS.get(id);
  if (!s) {
    s = { buf: new Map(), want: 0, waiters: [], closed: false, reap: 0 };
    // Reap a session that never opens its download GET within 30s.
    s.reap = setTimeout(() => xClose(id), 30000);
    XSESSIONS.set(id, s);
  }
  return s;
}

function xClose(id: string): void {
  const s = XSESSIONS.get(id);
  if (!s) return;
  s.closed = true;
  clearTimeout(s.reap);
  s.waiters.splice(0).forEach((f) => f());
  XSESSIONS.delete(id);
}

function xPush(s: XSession, seq: number, payload: Uint8Array): void {
  if (s.closed) return;
  s.buf.set(seq, payload);
  s.waiters.splice(0).forEach((f) => f());
}

// Async iterator over the reassembled, in-order upload byte stream.
async function* xUpload(s: XSession): AsyncGenerator<Uint8Array> {
  while (true) {
    const chunk = s.buf.get(s.want);
    if (chunk) {
      s.buf.delete(s.want);
      s.want++;
      if (chunk.length) yield chunk;
      continue;
    }
    if (s.closed) return;
    await new Promise<void>((res) => s.waiters.push(res));
  }
}

// Random padding header (mirrors Xray's X-Padding) for basic obfuscation.
function xHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const n = 100 + Math.floor(Math.random() * 900);
  return { "X-Padding": "0".repeat(n), ...extra };
}

async function handleXhttp(req: Request, url: URL): Promise<Response> {
  // Path layout: <XHTTP_PATH>/<sessionId>[/<seq>]
  const rest = url.pathname.slice(XHTTP_PATH.length + 1);
  const segs = rest.split("/");
  const sessionId = segs[0] || "";
  const seqStr = segs[1] ?? "";

  if (req.method === "OPTIONS") {
    return new Response(null, { status: 200, headers: xHeaders() });
  }
  if (!sessionId) {
    return decoyResponse(url.pathname); // stream-one not supported -> decoy
  }

  // --- Upload packet: POST <path>/<sid>/<seq> ---
  if (req.method === "POST") {
    const s = xGet(sessionId);
    const seq = parseInt(seqStr, 10);
    if (Number.isNaN(seq)) return new Response(null, { status: 400 });
    const body = new Uint8Array(await req.arrayBuffer());
    xPush(s, seq, body);
    return new Response(null, {
      status: 200,
      headers: xHeaders({ "X-Accel-Buffering": "no", "Cache-Control": "no-store" }),
    });
  }

  // --- Download stream: GET <path>/<sid> ---
  if (req.method === "GET") {
    const s = xGet(sessionId);
    clearTimeout(s.reap); // download opened -> lives with the stream
    const body = new ReadableStream<Uint8Array>({
      start(ctl) {
        pumpXhttp(sessionId, s, ctl).catch(() => {
          try { ctl.close(); } catch { /* ignore */ }
        });
      },
      cancel() { xClose(sessionId); },
    });
    return new Response(body, {
      status: 200,
      headers: xHeaders({
        "Content-Type": "text/event-stream",
        "X-Accel-Buffering": "no",
        "Cache-Control": "no-store",
      }),
    });
  }

  return new Response(null, { status: 405 });
}

// Drives one xHTTP session: decode the VLESS header from the reassembled
// upload, connect to the destination, then relay both directions.
async function pumpXhttp(
  id: string,
  s: XSession,
  ctl: ReadableStreamDefaultController<Uint8Array>,
): Promise<void> {
  const gen = xUpload(s);
  let pending = new Uint8Array(0);
  let parsed: { header: ParsedHeader; rest: Uint8Array } | null = null;

  while (!parsed) {
    const { value, done } = await gen.next();
    if (done) { try { ctl.close(); } catch { /* */ } xClose(id); return; }
    pending = concat(pending, value);
    const p = tryParseHeader(pending);
    if (p === "need-more") continue;
    if (p === "bad") { try { ctl.close(); } catch { /* */ } xClose(id); return; }
    parsed = p;
  }

  let remote: Deno.TcpConn;
  try {
    remote = await Deno.connect({ hostname: parsed.header.address, port: parsed.header.port });
  } catch {
    try { ctl.close(); } catch { /* */ }
    xClose(id);
    return;
  }
  const rw = remote.writable.getWriter();
  let up = 0;
  let down = 0;
  let flushedUp = 0;
  let flushedDown = 0;
  let idleTicks = 0;
  let finished = false;

  bumpStats(1, 0, 0); // count the request now; bytes are flushed as they flow

  const finish = () => {
    if (finished) return;
    finished = true;
    clearInterval(monitor);
    const dUp = up - flushedUp, dDown = down - flushedDown;
    if (dUp || dDown) bumpStats(0, dUp, dDown);
    try { rw.close(); } catch { /* */ }
    try { remote.close(); } catch { /* */ }
    try { ctl.close(); } catch { /* */ }
  };

  // Periodic flush + idle close (frees the isolate when the tunnel is idle).
  const monitor = setInterval(() => {
    const dUp = up - flushedUp, dDown = down - flushedDown;
    if (dUp || dDown) { bumpStats(0, dUp, dDown); flushedUp = up; flushedDown = down; }
    if (dUp + dDown > IDLE_MIN_BYTES) idleTicks = 0;          // real activity
    else if (++idleTicks >= IDLE_CLOSE_TICKS) finish();       // keepalive-only -> close
  }, FLUSH_MS);

  // VLESS sends a 2-byte response header; Trojan has none.
  if (parsed.header.protocol === "vless") {
    ctl.enqueue(new Uint8Array([parsed.header.version, 0]));
  }
  if (parsed.rest.length) { up += parsed.rest.length; await rw.write(parsed.rest); }

  // remote -> download (GET response)
  const downPump = (async () => {
    try {
      for await (const d of remote.readable) { down += d.length; ctl.enqueue(d); }
    } catch { /* remote closed */ }
  })();

  // upload (POSTs) -> remote
  try {
    for await (const chunk of gen) { up += chunk.length; await rw.write(chunk); }
  } catch { /* */ }

  await downPump.catch(() => {});
  finish();
  xClose(id);
}

// ---- HTTP ENTRYPOINT -------------------------------------------------------
Deno.serve((req: Request) => {
  const url = new URL(req.url);
  const upgrade = req.headers.get("upgrade")?.toLowerCase();
  const isWs = upgrade === "websocket";

  // Hidden stats endpoint: GET <WS_PATH>?stats=<UUID> -> exact usage JSON.
  // Requires the secret UUID, so probes without it just see the decoy.
  if (!isWs && url.pathname === WS_PATH && url.searchParams.get("stats") === UUID) {
    return statsResponse();
  }

  // xHTTP transport (SplitHTTP packet-up). Runs alongside WebSocket on the
  // same port. Kept entirely separate so the WS path is never affected.
  if (!isWs && url.pathname.startsWith(XHTTP_PATH + "/")) {
    return handleXhttp(req, url);
  }

  // Only an exact match on the obscure path AND a real WS upgrade proceeds.
  // Anything else — browsers, scanners, provider health-checks, probes —
  // gets the innocent decoy site. We never leak "Upgrade Required" or any
  // proxy-shaped error to a normal client.
  if (!isWs || url.pathname !== WS_PATH) {
    return decoyResponse(url.pathname);
  }

  const { socket, response } = Deno.upgradeWebSocket(req);
  handleSession(socket).catch(() => {
    try { socket.close(); } catch { /* ignore */ }
  });
  return response;
});

// ---- DECOY SITE ------------------------------------------------------------
// A plausible, harmless "under construction" page. Returns 200 on "/" so the
// domain looks like a live site, and a normal 404 for unknown paths.
function decoyResponse(pathname: string): Response {
  const status = pathname === "/" ? 200 : 404;
  const body = status === 200 ? DECOY_HOME : DECOY_404;
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "public, max-age=3600",
    },
  });
}

const DECOY_HOME = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Aria Studio — Coming Soon</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body { margin:0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
         min-height:100vh; display:flex; align-items:center; justify-content:center;
         background:#0f1117; color:#e6e6e6; }
  .card { text-align:center; padding:2.5rem 2rem; max-width:520px; }
  h1 { font-size:2rem; margin:0 0 .5rem; font-weight:600; }
  p { color:#9aa0aa; line-height:1.6; margin:.25rem 0; }
  .dot { display:inline-block; width:8px; height:8px; border-radius:50%;
         background:#4ade80; margin-right:8px; vertical-align:middle; }
  a { color:#6ea8fe; text-decoration:none; }
  footer { margin-top:2rem; font-size:.8rem; color:#5a606b; }
</style>
</head>
<body>
  <div class="card">
    <h1>Aria Studio</h1>
    <p><span class="dot"></span>Our new website is under construction.</p>
    <p>We're building something clean and simple. Check back soon.</p>
    <p>Questions? <a href="mailto:hello@example.com">Get in touch</a>.</p>
    <footer>&copy; 2026 Aria Studio. All rights reserved.</footer>
  </div>
</body>
</html>`;

const DECOY_404 = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>404 Not Found</title>
<style>body{font-family:system-ui,sans-serif;text-align:center;padding:4rem;color:#333}
h1{font-size:3rem;margin:0}p{color:#777}</style></head>
<body><h1>404</h1><p>The page you requested could not be found.</p></body></html>`;

// ---- SESSION HANDLER -------------------------------------------------------
async function handleSession(ws: WebSocket): Promise<void> {
  ws.binaryType = "arraybuffer";

  // State machine: we buffer the first bytes until we can decode the header.
  let header: ParsedHeader | null = null;
  let remote: Deno.TcpConn | null = null;
  let remoteWriter: WritableStreamDefaultWriter<Uint8Array> | null = null;
  let pending = new Uint8Array(0); // buffered bytes before header is complete
  let closed = false;

  // Per-session usage counters (flushed to shared stats as bytes flow).
  let up = 0;        // bytes from client (upload)
  let down = 0;      // bytes to client (download)
  let flushedUp = 0;
  let flushedDown = 0;
  let idleTicks = 0;
  let established = false;
  let monitor: number | undefined;

  const closeAll = () => {
    if (closed) return;
    closed = true;
    if (monitor !== undefined) clearInterval(monitor);
    // Flush any usage not yet counted.
    const dUp = up - flushedUp, dDown = down - flushedDown;
    if (dUp || dDown) bumpStats(0, dUp, dDown);
    try { ws.close(); } catch { /* ignore */ }
    try { remoteWriter?.close(); } catch { /* ignore */ }
    try { remote?.close(); } catch { /* ignore */ }
  };

  ws.onmessage = async (ev: MessageEvent) => {
    try {
      const chunk = toUint8(ev.data);
      if (chunk.length === 0) return;

      // --- Fast path: header already parsed, just forward payload ---
      if (header && remoteWriter) {
        up += chunk.length;
        await remoteWriter.write(chunk);
        return;
      }

      // --- Slow path: accumulate until the VLESS header is complete ---
      pending = concat(pending, chunk);
      const parsed = tryParseHeader(pending);
      if (parsed === "need-more") return;
      if (parsed === "bad") { closeAll(); return; }

      header = parsed.header;

      // Open the upstream TCP connection to the requested destination.
      remote = await Deno.connect({
        hostname: header.address,
        port: header.port,
      });
      remoteWriter = remote.writable.getWriter();
      established = true;
      bumpStats(1, 0, 0); // count the request; bytes flushed as they flow

      // Periodic flush + idle close (frees the isolate when the tunnel is idle).
      monitor = setInterval(() => {
        const dUp = up - flushedUp, dDown = down - flushedDown;
        if (dUp || dDown) { bumpStats(0, dUp, dDown); flushedUp = up; flushedDown = down; }
        if (dUp + dDown > IDLE_MIN_BYTES) idleTicks = 0;        // real activity
        else if (++idleTicks >= IDLE_CLOSE_TICKS) closeAll();   // keepalive-only -> close
      }, FLUSH_MS);

      // VLESS sends a 2-byte response header once; Trojan has none.
      if (header.protocol === "vless") {
        ws.send(new Uint8Array([header.version, 0]));
      }

      // Write any payload bytes that arrived in the same first message.
      if (parsed.rest.length > 0) {
        up += parsed.rest.length;
        await remoteWriter.write(parsed.rest);
      }
      pending = new Uint8Array(0);

      // Pump upstream -> client until the remote closes.
      pumpRemoteToWs(remote, ws, closeAll, (n) => { down += n; });
    } catch (e) {
      if (VERBOSE) console.error("onmessage:", (e as Error)?.message ?? e);
      closeAll();
    }
  };

  ws.onclose = closeAll;
  ws.onerror = closeAll;
}

// Stream remote TCP responses back to the WebSocket client.
async function pumpRemoteToWs(
  remote: Deno.TcpConn,
  ws: WebSocket,
  closeAll: () => void,
  onDown: (n: number) => void,
): Promise<void> {
  try {
    for await (const data of remote.readable) {
      if (ws.readyState !== WebSocket.OPEN) break;
      onDown(data.length);
      ws.send(data);
    }
  } catch {
    /* remote closed / reset */
  } finally {
    closeAll();
  }
}

// ---- INBOUND HEADER PARSING (VLESS or Trojan) ------------------------------
interface ParsedHeader {
  protocol: "vless" | "trojan";
  version: number; // VLESS version byte (unused for Trojan)
  address: string;
  port: number;
}

type ParseResult =
  | "need-more"
  | "bad"
  | { header: ParsedHeader; rest: Uint8Array };

// Detect the protocol from the first byte: VLESS begins with a version byte
// (0x00); Trojan begins with 56 ASCII hex chars, so its first byte is never 0.
function tryParseHeader(buf: Uint8Array): ParseResult {
  if (buf.length < 1) return "need-more";
  return buf[0] === 0 ? parseVless(buf) : parseTrojan(buf);
}

// VLESS: [ver(1)][uuid(16)][addonLen(1)][addons][cmd(1)][port(2)][atype(1)][addr][payload]
function parseVless(buf: Uint8Array): ParseResult {
  if (buf.length < 24) return "need-more";
  let i = 0;
  const version = buf[i++];
  for (let j = 0; j < 16; j++) if (buf[i + j] !== UUID_BYTES[j]) return "bad";
  i += 16;
  const addonLen = buf[i++];
  i += addonLen;
  if (i + 1 > buf.length) return "need-more";
  const command = buf[i++];
  if (command !== 1) return "bad"; // TCP only
  if (i + 2 > buf.length) return "need-more";
  const port = (buf[i] << 8) | buf[i + 1];
  i += 2;
  if (i + 1 > buf.length) return "need-more";
  const atype = buf[i++]; // VLESS: 1=IPv4, 2=domain, 3=IPv6
  const a = readAddress(buf, i, atype, 2);
  if (a === "need-more") return "need-more";
  if (a === "bad") return "bad";
  return { header: { protocol: "vless", version, address: a.address, port }, rest: buf.subarray(a.next) };
}

// Trojan: [hex-sha224(56)][CRLF][cmd(1)][atype(1)][addr][port(2)][CRLF][payload]
function parseTrojan(buf: Uint8Array): ParseResult {
  if (buf.length < 56) return "need-more";
  for (let j = 0; j < 56; j++) if (buf[j] !== TROJAN_HEX_BYTES[j]) return "bad";
  let i = 56;
  if (i + 2 > buf.length) return "need-more";
  if (buf[i] !== 0x0d || buf[i + 1] !== 0x0a) return "bad";
  i += 2;
  if (i + 2 > buf.length) return "need-more";
  const command = buf[i++];
  if (command !== 1) return "bad"; // TCP CONNECT only
  const atype = buf[i++]; // Trojan (SOCKS5): 1=IPv4, 3=domain, 4=IPv6
  const a = readAddress(buf, i, atype, 3);
  if (a === "need-more") return "need-more";
  if (a === "bad") return "bad";
  i = a.next;
  if (i + 2 > buf.length) return "need-more";
  const port = (buf[i] << 8) | buf[i + 1];
  i += 2;
  if (i + 2 > buf.length) return "need-more";
  if (buf[i] !== 0x0d || buf[i + 1] !== 0x0a) return "bad"; // trailing CRLF
  i += 2;
  return { header: { protocol: "trojan", version: 0, address: a.address, port }, rest: buf.subarray(i) };
}

// Reads a SOCKS-style address. domainAtype = the atype value that means
// "domain" for this protocol (2 for VLESS, 3 for Trojan); IPv6 is domainAtype+1.
function readAddress(
  buf: Uint8Array,
  start: number,
  atype: number,
  domainAtype: number,
): "need-more" | "bad" | { address: string; next: number } {
  let i = start;
  if (atype === 1) { // IPv4
    if (i + 4 > buf.length) return "need-more";
    const address = `${buf[i]}.${buf[i + 1]}.${buf[i + 2]}.${buf[i + 3]}`;
    return { address, next: i + 4 };
  } else if (atype === domainAtype) { // domain
    if (i + 1 > buf.length) return "need-more";
    const len = buf[i++];
    if (i + len > buf.length) return "need-more";
    return { address: new TextDecoder().decode(buf.subarray(i, i + len)), next: i + len };
  } else if (atype === domainAtype + 1) { // IPv6
    if (i + 16 > buf.length) return "need-more";
    const parts: string[] = [];
    for (let k = 0; k < 16; k += 2) parts.push(((buf[i + k] << 8) | buf[i + k + 1]).toString(16));
    return { address: parts.join(":"), next: i + 16 };
  }
  return "bad";
}

// ---- HELPERS ---------------------------------------------------------------
function uuidToBytes(uuid: string): Uint8Array {
  const hex = uuid.replace(/-/g, "");
  if (hex.length !== 32) throw new Error("invalid UUID");
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) {
    out[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return out;
}

function toUint8(data: unknown): Uint8Array {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (data instanceof Uint8Array) return data;
  // Shouldn't happen with binaryType="arraybuffer", but be safe.
  if (typeof data === "string") return new TextEncoder().encode(data);
  return new Uint8Array(0);
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  if (a.length === 0) return b;
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}
