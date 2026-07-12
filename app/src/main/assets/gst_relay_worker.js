/**
 * GST Relay Worker — Cloudflare edge equivalent of the Google Apps Script relay
 * (assets/gst/Code.gs). It speaks the EXACT same wire protocol so the mhrv-rs core can
 * use it as an optional `relay_url` to accelerate / stabilize the Google tunnel:
 *
 *   Single:  POST { k, m, u, h, b, ct, r }        -> { s, h, b }   (or { e })
 *   Batch:   POST { k, q: [ {m,u,h,b,ct,r}, ... ]}-> { q: [ {s,h,b} | {e}, ... ] }
 *
 * `k` must equal the AUTH_KEY secret binding (set at deploy time). Fields:
 *   m  method (default GET)   u  absolute http(s) URL   h  request headers object
 *   b  base64 request body    ct content-type for b     r  follow redirects (default true)
 * Response:
 *   s  upstream status   h  upstream headers   b  base64 upstream body   e  error string
 *
 * GET requests return a harmless placeholder so scanners can't fingerprint it.
 */

const DECOY_HTML =
  '<!DOCTYPE html><html><head><title>Web App</title></head>' +
  '<body><p>The script completed but did not return anything.</p></body></html>';

// Connection/IP-leak headers stripped before forwarding (mirrors Code.gs SKIP_HEADERS).
const SKIP_HEADERS = {
  host: 1, connection: 1, 'content-length': 1, 'transfer-encoding': 1,
  'proxy-connection': 1, 'proxy-authorization': 1, priority: 1, te: 1,
  'x-forwarded-for': 1, 'x-forwarded-host': 1, 'x-forwarded-proto': 1,
  'x-forwarded-port': 1, 'x-real-ip': 1, forwarded: 1, via: 1,
};

function json(obj) {
  return new Response(JSON.stringify(obj), {
    headers: { 'content-type': 'application/json;charset=UTF-8' },
  });
}

function decoy() {
  return new Response(DECOY_HTML, { headers: { 'content-type': 'text/html' } });
}

function bytesToB64(bytes) {
  let bin = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  }
  return btoa(bin);
}

function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function buildInit(item) {
  const method = (item.m || 'GET').toUpperCase();
  const headers = {};
  if (item.h && typeof item.h === 'object') {
    for (const k in item.h) {
      if (Object.prototype.hasOwnProperty.call(item.h, k) && !SKIP_HEADERS[k.toLowerCase()]) {
        headers[k] = item.h[k];
      }
    }
  }
  const init = {
    method,
    headers,
    redirect: item.r === false ? 'manual' : 'follow',
  };
  if (item.b) {
    init.body = b64ToBytes(item.b);
    if (item.ct) headers['content-type'] = item.ct;
  }
  return init;
}

async function relayOne(item) {
  if (!item || typeof item !== 'object') return { e: 'bad item' };
  if (!item.u || typeof item.u !== 'string' || !/^https?:\/\//i.test(item.u)) {
    return { e: 'bad url' };
  }
  try {
    const resp = await fetch(item.u, buildInit(item));
    const headers = {};
    resp.headers.forEach((v, k) => { headers[k] = v; });
    const buf = new Uint8Array(await resp.arrayBuffer());
    return { s: resp.status, h: headers, b: bytesToB64(buf) };
  } catch (err) {
    return { e: 'fetch failed: ' + String(err) };
  }
}

export default {
  async fetch(request, env) {
    if (request.method !== 'POST') return decoy();

    let req;
    try {
      req = await request.json();
    } catch (err) {
      return decoy();
    }

    if (!env.AUTH_KEY || req.k !== env.AUTH_KEY) return decoy();

    // Batch mode.
    if (Array.isArray(req.q)) {
      const results = await Promise.all(req.q.map((it) => relayOne(it)));
      return json({ q: results });
    }

    // Single mode.
    const out = await relayOne(req);
    return json(out);
  },
};
