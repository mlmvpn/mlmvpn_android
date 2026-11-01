// The request side of the gateway: what a plain HTTP request gets, what upgrades to a tunnel, and
// the glue that hands an accepted WebSocket to a RelaySession.
//
// Everything the runtime provides is passed in (`connect`, `fetch`, the WebSocketPair and the 101
// response), so the same code runs in the Workers runtime and under Node for tests.

import { loadConfig } from './config.js';
import { decodeBase64Url } from './bytes.js';
import { RelaySession } from './session.js';

const JSON_HEADERS = Object.freeze({
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
});

function json(body, status = 200, extra) {
  return new Response(JSON.stringify(body), { status, headers: { ...JSON_HEADERS, ...(extra || {}) } });
}

/**
 * @param {object}   deps
 * @param {Function} deps.connect            cloudflare:sockets connect()
 * @param {Function} [deps.fetchImpl]        defaults to the global fetch (DoH for UDP/53)
 * @param {Function} [deps.createPair]       defaults to () => new WebSocketPair()
 * @param {Function} [deps.upgradeResponse]  (clientSocket, headers) → the 101 Response
 * @param {object}   [deps.timers]           passed to every session
 * @param {Function} [deps.onSession]        called with each RelaySession (tests, metrics)
 */
export function createGateway(deps) {
  const connect = deps.connect;
  const fetchImpl = deps.fetchImpl || ((...args) => fetch(...args));
  const createPair = deps.createPair || (() => new WebSocketPair());
  const upgradeResponse = deps.upgradeResponse
    || ((client, headers) => new Response(null, { status: 101, webSocket: client, headers }));

  function health(request, cfg) {
    const method = request.method;
    if (method === 'GET' || method === 'HEAD') {
      const body = { status: 'ok', service: cfg.serviceName, ts: Date.now() };
      if (method === 'HEAD') return new Response(null, { status: 200, headers: JSON_HEADERS });
      return json(body);
    }
    if (method === 'OPTIONS') return new Response(null, { status: 204, headers: { allow: 'GET, HEAD, OPTIONS' } });
    return json({ status: 'error', error: 'method_not_allowed' }, 405, { allow: 'GET, HEAD, OPTIONS' });
  }

  function upgrade(request, cfg) {
    // Xray's 0-RTT: the first bytes of the stream ride in Sec-WebSocket-Protocol, base64url.
    const offered = (request.headers.get('sec-websocket-protocol') || '').trim();
    const early = offered ? decodeBase64Url(offered) : null;
    const pair = createPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();
    const session = new RelaySession(server, {
      config: cfg,
      connect,
      fetchImpl,
      timers: deps.timers,
      // Only a bug in this code is worth a line: no host, no port, no id — what failed, once.
      onEnd: (r) => { if (r.outcome === 'internal_error') (deps.warn || console.warn)(`edge-gateway: internal error: ${r.error || 'unknown'}`); },
    });
    if (deps.onSession) deps.onSession(session);
    session.start(early);
    // A client that offered a subprotocol expects it back.
    return upgradeResponse(client, early ? { 'sec-websocket-protocol': offered } : undefined);
  }

  return {
    async fetch(request, env) {
      const cfg = loadConfig(env);
      if (!cfg.ok) return json({ status: 'error', error: 'not_configured' }, 503);
      const wantsTunnel = (request.headers.get('upgrade') || '').toLowerCase() === 'websocket';
      if (wantsTunnel && (!cfg.wsPath || new URL(request.url).pathname === cfg.wsPath)) {
        return upgrade(request, cfg);
      }
      return health(request, cfg);
    },
  };
}
