// One tunnel: a WebSocket from the app ↔ one TCP destination (or a DNS-over-HTTPS relay for UDP/53).
//
// The rules this file keeps, because the first prototype broke each of them:
//
//  - ONE path for incoming bytes. Messages are queued and handled strictly in arrival order by a
//    single loop; a message that arrives while the destination is still being dialled waits its
//    turn instead of being parsed as a second header.
//  - Everything that touches the destination's writer (a forwarded chunk, a replay onto a
//    fallback) runs under one lock, so a failover can never interleave with a write.
//  - ONE teardown. `#end` is the only place sockets, timers and the WebSocket are released, it is
//    idempotent, and every way a session can finish (peer close, destination close, timeout,
//    error, overload) goes through it — no connection or timer outlives the session.
//  - A destination that does not answer is not the end: if it never connected, or connected and
//    delivered nothing, the next candidate (the configured fallbacks) is dialled and the bytes the
//    client has sent so far are replayed onto it. Once any byte has gone back to the client the
//    stream is committed and a failure simply ends it.
//  - Nothing the server learned about a failure is sent to the client: it is told "rejected",
//    "unreachable" or "timeout" by close code only. The detail stays in `result`.

import { toBytes, concatBytes } from './bytes.js';
import { parseVlessRequest, COMMAND } from './vless.js';
import { DnsRelay } from './dns.js';

// Application-defined codes (4000–4999): a decision of this Worker is never confused with a
// standard code (1001, 1006, …) that the edge or a proxy in between produces. The runtime accepts
// 1000–1011 and 3000–4999 (measured on workerd 2026-09-08; 5000 throws).
export const CLOSE = Object.freeze({
  NORMAL: 1000,
  REJECTED: 4000,     // malformed, not authorised, or not something this gateway carries
  UNREACHABLE: 4001,  // no destination answered
  TIMEOUT: 4002,      // the handshake did not complete, or the stream went quiet
  OVERLOAD: 4003,     // the client sent faster than the destination accepts
  UPSTREAM: 4004,     // the destination broke the stream
});

const WS_OPEN = 1;
const WS_CLOSING = 2;

const realTimers = {
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: (id) => clearTimeout(id),
  now: () => Date.now(),
};

export class RelaySession {
  /**
   * @param {object} ws                a WebSocket that has been accepted
   * @param {object} deps
   * @param {object} deps.config       loadConfig() result with ok === true
   * @param {Function} deps.connect    cloudflare:sockets connect()
   * @param {Function} deps.fetchImpl  fetch(), for the DoH relay
   * @param {object} [deps.timers]     { setTimeout, clearTimeout, now }
   * @param {Function} [deps.onEnd]    called once with { outcome, code }
   */
  constructor(ws, deps) {
    this.ws = ws;
    this.cfg = deps.config;
    this.connect = deps.connect;
    this.fetchImpl = deps.fetchImpl;
    this.timers = deps.timers || realTimers;
    this.onEnd = deps.onEnd;

    this.state = 'handshake';   // handshake → connecting → relaying | dns → closed
    this.closed = false;
    this.result = null;

    this.inbox = [];
    this.inboxBytes = 0;
    this.draining = false;
    this.head = null;

    this.candidates = [];
    this.nextCandidate = 0;
    this.up = null;
    this.upSeq = 0;
    this.dialing = null;
    this.replay = null;
    this.replayBytes = 0;
    this.version = 0;
    this.headerSent = false;
    this.dns = null;
    this.lock = Promise.resolve();

    this.handshakeTimer = null;
    this.idleTimer = null;
    this.lastActive = 0;
  }

  /** Attach to the WebSocket. `earlyData` are bytes that arrived in the upgrade request itself. */
  start(earlyData) {
    this.ws.addEventListener('message', (ev) => this.#onMessage(ev));
    this.ws.addEventListener('close', () => this.#end(CLOSE.NORMAL, 'closed', 'peer_closed'));
    this.ws.addEventListener('error', () => this.#end(CLOSE.NORMAL, 'closed', 'peer_error'));
    this.handshakeTimer = this.timers.setTimeout(
      () => this.#end(CLOSE.TIMEOUT, 'timeout', 'handshake_timeout'), this.cfg.handshakeTimeoutMs);
    this.#armIdle();
    if (earlyData && earlyData.byteLength) this.#enqueue(earlyData);
  }

  // ── incoming bytes ──────────────────────────────────────────────────────────────

  #onMessage(ev) {
    if (this.closed) return;
    const bytes = toBytes(ev.data);
    if (!bytes) return this.#end(CLOSE.REJECTED, 'rejected', 'text_frame');
    this.#enqueue(bytes);
  }

  #enqueue(bytes) {
    if (this.closed || !bytes.byteLength) return;
    this.inbox.push(bytes);
    this.inboxBytes += bytes.byteLength;
    if (this.inboxBytes > this.cfg.maxQueuedBytes) return this.#end(CLOSE.OVERLOAD, 'overload', 'client_too_fast');
    this.#armIdle();
    void this.#drain();
  }

  async #drain() {
    if (this.draining) return;
    this.draining = true;
    try {
      while (!this.closed && this.inbox.length) {
        const chunk = this.inbox.shift();
        this.inboxBytes -= chunk.byteLength;
        await this.#process(chunk);
      }
    } catch (err) {
      this.#end(CLOSE.UPSTREAM, 'error', 'internal_error', err);
    } finally {
      this.draining = false;
    }
  }

  async #process(chunk) {
    if (this.state === 'handshake') return this.#onHandshake(chunk);
    if (this.state === 'relaying') return this.#forward(chunk);
    if (this.state === 'dns' && this.dns) this.dns.push(chunk);
  }

  async #onHandshake(chunk) {
    const buf = this.head ? concatBytes(this.head, chunk) : chunk;
    const r = parseVlessRequest(buf, this.cfg.uuids);
    if (r.status === 'incomplete') {
      // The parser bounds this: a header is never longer than ~540 bytes, so a buffer longer than
      // that is complete or an error, never "incomplete" — what is kept here cannot grow.
      this.head = buf;
      return;
    }
    if (r.status === 'error') return this.#end(CLOSE.REJECTED, 'rejected', r.code);
    this.head = null;
    this.#clearHandshakeTimer();
    this.version = r.version;
    const payload = buf.subarray(r.offset);
    if (r.command === COMMAND.UDP) return this.#startDns(r, payload);
    return this.#startTcp(r, payload);
  }

  // ── TCP ─────────────────────────────────────────────────────────────────────────

  async #startTcp(req, payload) {
    if (this.cfg.blockedPorts.has(req.port)) return this.#end(CLOSE.REJECTED, 'rejected', 'blocked_port');
    this.state = 'connecting';
    this.candidates = [{ host: req.host, port: req.port }];
    for (const f of this.cfg.fallbacks) this.candidates.push({ host: f.host, port: f.port || req.port });
    this.nextCandidate = 0;
    this.replay = [];
    this.replayBytes = 0;
    if (payload.byteLength) {
      this.replay.push(payload);
      this.replayBytes = payload.byteLength;
    }
    if (!(await this.#dialNext())) return this.#end(CLOSE.UNREACHABLE, 'unreachable', 'unreachable');
    if (this.closed) return;
    this.state = 'relaying';
    if (!payload.byteLength) return;
    // If the destination already failed over while this was waiting, the replay has the payload.
    const up = this.up;
    await this.#locked(async () => {
      if (this.closed || !up || this.up !== up) return;
      try {
        await up.writer.write(payload);
      } catch {
        await this.#failover(up);
      }
    });
  }

  /** Dial candidates in order until one opens. true → `this.up` is live and its pump is running. */
  async #dialNext() {
    while (!this.closed && this.nextCandidate < this.candidates.length) {
      const target = this.candidates[this.nextCandidate++];
      const up = await this.#dial(target);
      if (up) {
        this.up = up;
        this.#pump(up).catch((err) => this.#end(CLOSE.UPSTREAM, 'error', 'internal_error', err));
        return true;
      }
    }
    return false;
  }

  async #dial(target) {
    let socket;
    try {
      // The runtime turns {hostname, port} into "hostname:port"; an IPv6 literal must be bracketed
      // or it cannot be told from the port (measured: "No such host is known: 0:0:0:0:0:0:0:1:PORT").
      const hostname = target.host.includes(':') ? `[${target.host}]` : target.host;
      socket = this.connect({ hostname, port: target.port }, { secureTransport: 'off', allowHalfOpen: false });
    } catch {
      return null;   // an address the runtime refuses outright
    }
    // `closed` rejects when the connection errors; nobody else would ever observe it.
    if (socket.closed && socket.closed.catch) socket.closed.catch(() => {});
    this.dialing = socket;
    let timer = null;
    try {
      const deadline = new Promise((_, reject) => {
        timer = this.timers.setTimeout(() => reject(new Error('connect timeout')), this.cfg.connectTimeoutMs);
      });
      await Promise.race([socket.opened, deadline]);
    } catch {
      this.dialing = null;
      this.#discard(socket);
      return null;
    } finally {
      if (timer !== null) this.timers.clearTimeout(timer);
    }
    this.dialing = null;
    if (this.closed) {
      this.#discard(socket);
      return null;
    }
    try {
      return {
        id: ++this.upSeq,
        socket,
        writer: socket.writable.getWriter(),
        reader: socket.readable.getReader(),
        gotData: false,
      };
    } catch {
      // Opened, but its streams cannot be taken: not a usable destination — release it, try the next.
      this.#discard(socket);
      return null;
    }
  }

  /** Destination → client. Runs for the life of one upstream. */
  async #pump(up) {
    let failure = false;
    try {
      for (;;) {
        const { done, value } = await up.reader.read();
        if (done) break;
        if (this.closed || this.up !== up) return;
        if (!value || !value.byteLength) continue;
        up.gotData = true;
        this.replay = null;   // the stream is committed: nothing can be replayed any more
        this.replayBytes = 0;
        this.#armIdle();
        if (!this.#sendDownstream(value)) return;
      }
    } catch {
      failure = true;
    }
    if (this.closed || this.up !== up) return;
    if (!up.gotData && this.replay !== null && this.nextCandidate < this.candidates.length) {
      await this.#locked(() => this.#failover(up));
      return;
    }
    // Connected, then closed: the client sees a valid, possibly empty, stream that ends.
    if (!this.headerSent && !up.gotData) this.#sendRaw(new Uint8Array([this.version, 0]));
    this.#end(failure ? CLOSE.UPSTREAM : CLOSE.NORMAL, failure ? 'error' : 'closed', failure ? 'upstream_error' : 'upstream_closed');
  }

  /** Client → destination, in order, under the lock. */
  #forward(chunk) {
    return this.#locked(async () => {
      if (this.closed || !this.up) return;
      const up = this.up;
      this.#remember(chunk);
      try {
        await up.writer.write(chunk);
      } catch {
        await this.#failover(up);
      }
    });
  }

  #remember(chunk) {
    if (this.replay === null) return;
    this.replayBytes += chunk.byteLength;
    if (this.replayBytes > this.cfg.replayLimitBytes) {
      this.replay = null;
      this.replayBytes = 0;
      return;
    }
    this.replay.push(chunk);
  }

  /**
   * `broken` stopped working before it delivered anything. Called with the lock held. Moves to the
   * next candidate and replays what the client has sent; ends the session when there is none, or
   * when the stream is already committed.
   */
  async #failover(broken) {
    let current = broken;
    while (!this.closed && this.up === current) {
      const retryable = !current.gotData && this.replay !== null && this.nextCandidate < this.candidates.length;
      const hadData = current.gotData;
      this.#dropUpstream(current);
      this.up = null;
      if (!retryable) {
        this.#end(CLOSE.UPSTREAM, 'error', hadData ? 'upstream_error' : 'upstream_failed');
        return;
      }
      if (!(await this.#dialNext())) {
        this.#end(CLOSE.UNREACHABLE, 'unreachable', 'unreachable');
        return;
      }
      current = this.up;
      const chunks = this.replay ? this.replay.slice() : [];
      try {
        for (const c of chunks) await current.writer.write(c);
        return;
      } catch {
        // this candidate took the connection and then failed on the replay — the next one
      }
    }
  }

  // ── UDP (DNS only) ──────────────────────────────────────────────────────────────

  #startDns(req, payload) {
    if (req.port !== 53 || !this.cfg.dohUrl) return this.#end(CLOSE.REJECTED, 'rejected', 'udp_unsupported');
    this.state = 'dns';
    this.dns = new DnsRelay({
      dohUrl: this.cfg.dohUrl,
      fetchImpl: this.fetchImpl,
      send: (bytes) => this.#sendRaw(bytes),
      responseHeader: new Uint8Array([this.version, 0]),
      onFatal: () => this.#end(CLOSE.REJECTED, 'rejected', 'dns_stream'),
      timers: this.timers,
    });
    // The relay frames its own answers, header included.
    this.headerSent = true;
    if (payload.byteLength) this.dns.push(payload);
  }

  // ── outgoing bytes ──────────────────────────────────────────────────────────────

  #sendRaw(bytes) {
    if (this.closed) return false;
    if (this.ws.readyState !== WS_OPEN) {
      this.#end(CLOSE.NORMAL, 'closed', 'peer_gone');
      return false;
    }
    try {
      this.ws.send(bytes);
      this.#armIdle();
      return true;
    } catch {
      this.#end(CLOSE.UPSTREAM, 'error', 'send_failed');
      return false;
    }
  }

  /** Destination bytes → WebSocket messages; the VLESS response header rides on the first. */
  #sendDownstream(value) {
    const max = this.cfg.maxFrameBytes;
    let offset = 0;
    do {
      const end = Math.min(value.byteLength, offset + max);
      let frame = value.subarray(offset, end);
      if (!this.headerSent) {
        const out = new Uint8Array(2 + frame.byteLength);
        out[0] = this.version;
        out[1] = 0;
        out.set(frame, 2);
        frame = out;
        this.headerSent = true;
      }
      if (!this.#sendRaw(frame)) return false;
      offset = end;
    } while (offset < value.byteLength);
    return true;
  }

  // ── timers ──────────────────────────────────────────────────────────────────────

  #clearHandshakeTimer() {
    if (this.handshakeTimer !== null) {
      this.timers.clearTimeout(this.handshakeTimer);
      this.handshakeTimer = null;
    }
  }

  /** Activity: one timer is kept, and it re-checks the clock when it fires instead of being reset. */
  #armIdle() {
    const ms = this.cfg.idleTimeoutMs;
    if (!ms || this.closed) return;
    this.lastActive = this.timers.now();
    if (this.idleTimer === null) this.idleTimer = this.timers.setTimeout(() => this.#idleCheck(), ms);
  }

  #idleCheck() {
    this.idleTimer = null;
    if (this.closed) return;
    const ms = this.cfg.idleTimeoutMs;
    const quiet = this.timers.now() - this.lastActive;
    if (quiet >= ms) return this.#end(CLOSE.TIMEOUT, 'timeout', 'idle_timeout');
    this.idleTimer = this.timers.setTimeout(() => this.#idleCheck(), ms - quiet);
  }

  // ── teardown ────────────────────────────────────────────────────────────────────

  #locked(fn) {
    const run = this.lock.then(fn);
    this.lock = run.then(() => {}, () => {});
    return run;
  }

  #discard(socket) {
    try {
      const p = socket.close();
      if (p && typeof p.catch === 'function') p.catch(() => {});
    } catch { /* already closed */ }
  }

  #dropUpstream(up) {
    try { up.reader.cancel().catch(() => {}); } catch { /* already released */ }
    try { up.writer.abort().catch(() => {}); } catch { /* already released */ }
    this.#discard(up.socket);
  }

  /**
   * The only place a session is released. Safe to call any number of times. `cause` is an
   * unexpected exception: its message (never a host or a byte of traffic) goes into `result.error`
   * for whoever observes `onEnd`; the client only ever sees the close code.
   */
  #end(code, reason, outcome, cause) {
    if (this.closed) return;
    this.closed = true;
    this.state = 'closed';
    this.result = { outcome, code };
    if (cause) this.result.error = String((cause && cause.message) || cause).slice(0, 200);
    this.#clearHandshakeTimer();
    if (this.idleTimer !== null) {
      this.timers.clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }
    this.inbox.length = 0;
    this.inboxBytes = 0;
    this.replay = null;
    this.head = null;
    if (this.dns) {
      this.dns.close();
      this.dns = null;
    }
    if (this.dialing) {
      this.#discard(this.dialing);
      this.dialing = null;
    }
    if (this.up) {
      this.#dropUpstream(this.up);
      this.up = null;
    }
    try {
      if (this.ws.readyState === WS_OPEN || this.ws.readyState === WS_CLOSING) this.ws.close(code, reason);
    } catch { /* the peer is already gone */ }
    try {
      if (this.onEnd) this.onEnd(this.result);
    } catch { /* an observer must not break teardown */ }
  }
}
