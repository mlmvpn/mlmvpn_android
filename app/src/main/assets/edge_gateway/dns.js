// UDP/53 for VLESS clients. A Worker has no raw UDP socket, so every DNS datagram becomes one
// DNS-over-HTTPS request and its answer goes back as a datagram.
//
// Over a VLESS stream, UDP is framed as  length(2, big-endian) · datagram  both ways; the answer
// stream starts with the two-byte VLESS response header, once. Queries are answered as they
// complete (DNS matches answers by id, not by order), at most `maxInflight` at a time — the rest
// are dropped, which a resolver treats as a lost packet and retries.

const MAX_PENDING_BYTES = 128 * 1024;

export class DnsRelay {
  /**
   * @param {object}   o
   * @param {string}   o.dohUrl
   * @param {Function} o.fetchImpl
   * @param {Function} o.send            (Uint8Array) → void, a WebSocket message
   * @param {Uint8Array} o.responseHeader
   * @param {Function} [o.onFatal]       called once when the stream cannot be parsed any more
   * @param {number}   [o.maxInflight]
   * @param {number}   [o.timeoutMs]
   * @param {object}   [o.timers]        { setTimeout, clearTimeout }
   */
  constructor({ dohUrl, fetchImpl, send, responseHeader, onFatal, maxInflight = 8, timeoutMs = 5000, timers }) {
    this.dohUrl = dohUrl;
    this.fetchImpl = fetchImpl;
    this.send = send;
    this.header = responseHeader;
    this.onFatal = onFatal || (() => {});
    this.maxInflight = maxInflight;
    this.timeoutMs = timeoutMs;
    this.timers = timers || { setTimeout: (...a) => setTimeout(...a), clearTimeout: (...a) => clearTimeout(...a) };
    this.pending = new Uint8Array(0);
    this.inflight = new Set();
    this.closed = false;
    this.headerSent = false;
  }

  /** Feed stream bytes; every complete datagram starts its query at once. */
  push(bytes) {
    if (this.closed || !bytes.byteLength) return;
    if (this.pending.byteLength) {
      const joined = new Uint8Array(this.pending.byteLength + bytes.byteLength);
      joined.set(this.pending, 0);
      joined.set(bytes, this.pending.byteLength);
      this.pending = joined;
    } else {
      this.pending = bytes;
    }
    let p = 0;
    while (this.pending.byteLength - p >= 2) {
      const len = (this.pending[p] << 8) | this.pending[p + 1];
      if (this.pending.byteLength - p - 2 < len) break;
      if (len > 0) this.#query(this.pending.slice(p + 2, p + 2 + len));
      p += 2 + len;
    }
    this.pending = p ? this.pending.slice(p) : this.pending;
    if (this.pending.byteLength > MAX_PENDING_BYTES) {
      this.close();
      this.onFatal('dns_frame_overflow');
    }
  }

  #query(datagram) {
    if (this.inflight.size >= this.maxInflight) return;
    const controller = new AbortController();
    const timer = this.timers.setTimeout(() => controller.abort(), this.timeoutMs);
    const job = { controller, timer };
    this.inflight.add(job);
    const finish = () => {
      this.timers.clearTimeout(timer);
      this.inflight.delete(job);
    };
    Promise.resolve()
      .then(() => this.fetchImpl(this.dohUrl, {
        method: 'POST',
        headers: { 'content-type': 'application/dns-message', accept: 'application/dns-message' },
        body: datagram,
        signal: controller.signal,
      }))
      .then((res) => (res && res.ok ? res.arrayBuffer() : null))
      .then((buf) => {
        finish();
        if (this.closed || !buf || buf.byteLength === 0 || buf.byteLength > 0xffff) return;
        this.#answer(new Uint8Array(buf));
      })
      .catch(() => finish());
  }

  #answer(answer) {
    const prefix = this.headerSent ? 0 : this.header.byteLength;
    const out = new Uint8Array(prefix + 2 + answer.byteLength);
    if (prefix) out.set(this.header, 0);
    out[prefix] = answer.byteLength >> 8;
    out[prefix + 1] = answer.byteLength & 0xff;
    out.set(answer, prefix + 2);
    this.headerSent = true;
    this.send(out);
  }

  /** Abort what is in flight; nothing is sent afterwards. */
  close() {
    if (this.closed) return;
    this.closed = true;
    for (const job of this.inflight) {
      this.timers.clearTimeout(job.timer);
      try { job.controller.abort(); } catch { /* already aborted */ }
    }
    this.inflight.clear();
    this.pending = new Uint8Array(0);
  }
}
