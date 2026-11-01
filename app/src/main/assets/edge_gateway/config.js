// Gateway configuration, read from the Worker's own bindings — nothing sensitive is compiled
// into the code, so the same file is deployed to every account.
//
//   UUID                 secret_text  REQUIRED. One or more VLESS user ids, comma or space separated.
//                                     Without a valid one the Worker is not a relay at all.
//   WS_PATH              plain_text   The only path that upgrades to a tunnel. Every other path
//                                     answers the plain JSON document. Default: any path.
//   FALLBACK             plain_text   Backup destinations, comma separated: "host", "host:port" or
//                                     "[v6]:port". Tried in order when the requested destination
//                                     does not answer. A bare host keeps the requested port.
//   BLOCKED_PORTS        plain_text   Ports that are never dialled (default "25").
//   DOH_URL              plain_text   DNS-over-HTTPS endpoint that carries UDP/53 (default Cloudflare's).
//   CONNECT_TIMEOUT_MS   plain_text   Per destination attempt (default 8000).
//   HANDSHAKE_TIMEOUT_MS plain_text   Upgrade → first valid request (default 10000).
//   IDLE_TIMEOUT_MS      plain_text   No bytes either way (default 300000; 0 = never).
//   SERVICE_NAME         plain_text   The "service" field of the JSON document.

import { parseUuid } from './bytes.js';

export const DEFAULTS = Object.freeze({
  connectTimeoutMs: 8000,
  handshakeTimeoutMs: 10000,
  idleTimeoutMs: 300000,
  blockedPorts: Object.freeze([25]),
  dohUrl: 'https://cloudflare-dns.com/dns-query',
  serviceName: 'edge-gateway',
  // Client → destination bytes allowed to wait while the destination is slow to accept them.
  maxQueuedBytes: 4 * 1024 * 1024,
  // What is remembered, until the destination first answers, to replay onto a fallback.
  replayLimitBytes: 256 * 1024,
  // One WebSocket message is never larger than this when sending downstream.
  maxFrameBytes: 256 * 1024,
  maxFallbacks: 3,
  maxUuids: 128,
});

const MAX_HOST = 253;

function intInRange(value, fallback, min, max) {
  if (value === undefined || value === null || String(value).trim() === '') return fallback;
  const n = Number(String(value).trim());
  if (!Number.isInteger(n) || n < min || n > max) return fallback;
  return n;
}

/** "host", "host:port", "[v6]" or "[v6]:port" → { host, port|null }, or null when it is not one. */
export function parseEndpoint(raw) {
  const text = String(raw || '').trim();
  if (!text) return null;
  let host;
  let port = null;
  const bracketed = /^\[([0-9a-fA-F:.]+)\](?::(\d{1,5}))?$/.exec(text);
  if (bracketed) {
    host = bracketed[1];
    if (bracketed[2]) port = Number(bracketed[2]);
  } else if ((text.match(/:/g) || []).length > 1) {
    // More than one colon and no brackets: a bare IPv6 literal.
    if (!/^[0-9a-fA-F:.]+$/.test(text)) return null;
    host = text;
  } else {
    const m = /^([^:]+)(?::(\d{1,5}))?$/.exec(text);
    if (!m) return null;
    host = m[1];
    if (m[2]) port = Number(m[2]);
  }
  if (port !== null && (port < 1 || port > 65535)) return null;
  if (host.length > MAX_HOST || !/^[A-Za-z0-9._:-]+$/.test(host)) return null;
  return { host, port };
}

function parseEndpoints(text, max) {
  const out = [];
  for (const part of String(text || '').split(/[,\s]+/)) {
    if (!part) continue;
    const ep = parseEndpoint(part);
    if (ep) out.push(ep);
    if (out.length >= max) break;
  }
  return out;
}

function parsePorts(text, fallback) {
  if (text === undefined || text === null) return [...fallback];
  const out = [];
  for (const part of String(text).split(/[,\s]+/)) {
    if (!part) continue;
    const n = Number(part);
    if (Number.isInteger(n) && n >= 1 && n <= 65535 && !out.includes(n)) out.push(n);
  }
  return out;
}

function normalizePath(text) {
  const t = String(text || '').trim();
  if (!t) return null;
  const noQuery = t.split(/[?#]/)[0];
  if (!noQuery || noQuery === '/') return null;
  return noQuery.startsWith('/') ? noQuery : '/' + noQuery;
}

function parseHttpsUrl(text, fallback) {
  const t = String(text || '').trim();
  if (!t) return fallback;
  try {
    const u = new URL(t);
    return u.protocol === 'https:' ? u.toString() : fallback;
  } catch {
    return fallback;
  }
}

const cache = new WeakMap();

/**
 * The settings in `env`, validated. { ok: false, problem } when the Worker must not relay (no usable
 * UUID); otherwise every field is present and in range — a bad optional value falls back to its
 * default rather than taking the gateway down.
 */
export function loadConfig(env) {
  const key = env && typeof env === 'object' ? env : null;
  if (key && cache.has(key)) return cache.get(key);
  const result = build(env || {});
  if (key) cache.set(key, result);
  return result;
}

function build(env) {
  const uuids = [];
  for (const part of String(env.UUID || '').split(/[,\s]+/)) {
    if (!part) continue;
    const bytes = parseUuid(part);
    if (bytes) uuids.push(bytes);
    if (uuids.length >= DEFAULTS.maxUuids) break;
  }
  if (!uuids.length) return Object.freeze({ ok: false, problem: 'uuid_missing' });
  return Object.freeze({
    ok: true,
    uuids,
    wsPath: normalizePath(env.WS_PATH),
    fallbacks: parseEndpoints(env.FALLBACK, DEFAULTS.maxFallbacks),
    blockedPorts: new Set(parsePorts(env.BLOCKED_PORTS, DEFAULTS.blockedPorts)),
    dohUrl: parseHttpsUrl(env.DOH_URL, DEFAULTS.dohUrl),
    connectTimeoutMs: intInRange(env.CONNECT_TIMEOUT_MS, DEFAULTS.connectTimeoutMs, 500, 60000),
    handshakeTimeoutMs: intInRange(env.HANDSHAKE_TIMEOUT_MS, DEFAULTS.handshakeTimeoutMs, 500, 60000),
    idleTimeoutMs: intInRange(env.IDLE_TIMEOUT_MS, DEFAULTS.idleTimeoutMs, 0, 24 * 3600 * 1000),
    serviceName: /^[A-Za-z0-9._-]{1,40}$/.test(String(env.SERVICE_NAME || '').trim())
      ? String(env.SERVICE_NAME).trim() : DEFAULTS.serviceName,
    maxQueuedBytes: DEFAULTS.maxQueuedBytes,
    replayLimitBytes: DEFAULTS.replayLimitBytes,
    maxFrameBytes: DEFAULTS.maxFrameBytes,
  });
}
