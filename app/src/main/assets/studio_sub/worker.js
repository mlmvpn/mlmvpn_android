// MLMVPN — «کانفیگ استدیو», the subscription Worker. Written for this app; shares no code with any panel.
//
// The account's central Worker (assets/edge_gateway) is a relay that knows a list of ids and nothing
// else, and it does not change. This Worker sits beside it and does only what a subscription needs:
//
//   GET /<token>   the person's configs, in the format their app reads, carrying THEIR device's id
//   anything else  a small JSON document
//
// Everything it serves was written by the operator's phone into KV (binding S):
//
//   u:<token>  { v:1, on, until, name, limit, nohwid, ids:[...], ban:[...], t:{ x, c, l }, contact }
//              x = a JSON array of full Xray configs, c = a Clash (mihomo) profile, l = share links;
//              each holds the placeholder {{ID}} where the device's id goes; ban = devices the
//              operator took off, refused from then on.
//   d:<token>  { v:1, dev:[{ k, s, os, model, app, at, seen }] }  ← the only key this Worker writes
//
// A device limit is a number of places, each with its own id on the central Worker (the phone puts
// them there). A device is told apart by the id its app sends (x-hwid, as Happ and v2RayTun send it);
// an app that sends none either shares one place with every other such app, or is refused — the
// operator's choice per person (nohwid: "allow" | "block"). When the places are full, a new device
// gets a single config whose name says so, instead of an error the app would hide.

const DAY = 86_400_000;
const TOKEN_RE = /^[A-Za-z0-9_-]{20,64}$/;
const PLACEHOLDER = /\{\{ID\}\}/g;

const MESSAGES = {
  off: 'اشتراک خاموش است',
  expired: 'اعتبار تمام شده است',
  full: 'سقف دستگاه پر است',
  nohwid: 'این برنامه شناسهٔ دستگاه نمی‌فرستد؛ Happ یا v2RayTun را نصب کنید',
  removed: 'این دستگاه از اشتراک برداشته شده است',
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
}

function b64(text) {
  const bytes = new TextEncoder().encode(text);
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

async function hash(text) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(d)).slice(0, 12).map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Which body the app reads: asked for (?f=), or told by its name; a JSON array of Xray configs otherwise. */
export function pickFormat(url, ua) {
  const f = url.searchParams.get('f');
  if (f === 'x' || f === 'c' || f === 'l') return f;
  const a = String(ua || '').toLowerCase();
  if (/clash|mihomo|stash|hiddify|flclash|koala|karing|clashmi/.test(a)) return 'c';
  return 'x';
}

/** One config whose only job is its name: what the app shows when there is nothing else to give. */
export function messageBody(fmt, text) {
  if (fmt === 'c') {
    const name = JSON.stringify(text);
    return `proxies:\n  - {name: ${name}, type: socks5, server: 127.0.0.1, port: 9}\n` +
      `proxy-groups:\n  - {name: "PROXY", type: select, proxies: [${name}]}\nrules:\n  - MATCH,PROXY\n`;
  }
  if (fmt === 'l') {
    return b64(`vless://00000000-0000-4000-8000-000000000000@127.0.0.1:9?encryption=none&type=tcp#${encodeURIComponent(text)}\n`);
  }
  return JSON.stringify([{
    remarks: text,
    log: { loglevel: 'none' },
    inbounds: [{ tag: 'socks', listen: '127.0.0.1', port: 10808, protocol: 'socks', settings: { udp: true } }],
    outbounds: [{ tag: 'block', protocol: 'blackhole' }],
    routing: { rules: [] },
  }]);
}

function headersFor(fmt, rec) {
  const h = {
    'content-type': fmt === 'c' ? 'text/yaml; charset=utf-8' : 'text/plain; charset=utf-8',
    'cache-control': 'no-store',
    'profile-update-interval': '6',
    'profile-title': 'base64:' + b64(String(rec.name || 'MLM').slice(0, 64)),
    'subscription-userinfo': `upload=0; download=0; total=0; expire=${rec.until > 0 ? Math.floor(rec.until / 1000) : 0}`,
  };
  return h;
}

function respond(request, fmt, rec, body) {
  return new Response(request.method === 'HEAD' ? null : body, { status: 200, headers: headersFor(fmt, rec) });
}

/**
 * The place a device takes under [rec.limit], registering it on its first visit. Returns
 * { slot } or { refuse: 'full' | 'nohwid' }.
 */
export async function placeFor(env, token, rec, request, now) {
  const hwid = String(request.headers.get('x-hwid') || '').trim().slice(0, 200);
  if (!hwid && rec.nohwid === 'block') return { refuse: 'nohwid' };
  const key = hwid ? 'h:' + (await hash(hwid)) : '~';
  // A device the operator took off stays off; it does not walk back into the place it left.
  if (Array.isArray(rec.ban) && rec.ban.includes(key)) return { refuse: 'removed' };
  const stored = await env.S.get('d:' + token, 'json');
  const devs = stored && Array.isArray(stored.dev) ? stored : { v: 1, dev: [] };
  // Places the phone took away (a lower limit) are not places any more.
  const live = devs.dev.filter((x) => Number.isInteger(x.s) && x.s >= 0 && x.s < rec.limit);
  let d = live.find((x) => x.k === key);
  const register = request.method !== 'HEAD';
  if (!d) {
    if (live.length >= rec.limit) return { refuse: 'full' };
    if (!register) return { slot: -1 };
    const used = new Set(live.map((x) => x.s));
    let s = 0;
    while (used.has(s)) s++;
    const ua = String(request.headers.get('user-agent') || '');
    d = {
      k: key, s,
      os: String(request.headers.get('x-device-os') || '').slice(0, 40),
      model: String(request.headers.get('x-device-model') || '').slice(0, 60),
      app: ua.slice(0, 80),
      at: now, seen: now,
    };
    await env.S.put('d:' + token, JSON.stringify({ v: 1, dev: live.concat([d]) }));
  } else if (register && now - (d.seen || 0) > DAY) {
    // Once a day at most: KV takes a thousand writes a day on the free plan, for the whole account.
    d.seen = now;
    await env.S.put('d:' + token, JSON.stringify({ v: 1, dev: live }));
  }
  return { slot: d.s };
}

export async function handle(request, env, now = Date.now()) {
  if (request.method !== 'GET' && request.method !== 'HEAD') return json({ error: 'method' }, 405);
  const url = new URL(request.url);
  const token = url.pathname.replace(/^\/+/, '').replace(/\/+$/, '');
  if (!TOKEN_RE.test(token)) return json({ status: 'ok' });
  const rec = await env.S.get('u:' + token, 'json');
  if (!rec || rec.v !== 1 || !rec.t) return json({ error: 'not_found' }, 404);
  let fmt = pickFormat(url, request.headers.get('user-agent'));
  if (!rec.t[fmt]) fmt = rec.t.x ? 'x' : 'l';
  if (!rec.on) return respond(request, fmt, rec, messageBody(fmt, MESSAGES.off));
  if (rec.until > 0 && rec.until <= now) return respond(request, fmt, rec, messageBody(fmt, MESSAGES.expired));
  const ids = Array.isArray(rec.ids) ? rec.ids : [];
  let slot = 0;
  if (rec.limit > 0) {
    const p = await placeFor(env, token, rec, request, now);
    if (p.refuse) return respond(request, fmt, rec, messageBody(fmt, MESSAGES[p.refuse]));
    if (p.slot < 0) return respond(request, fmt, rec, '');
    slot = p.slot;
  }
  const id = ids[slot];
  if (!id) return respond(request, fmt, rec, messageBody(fmt, MESSAGES.full));
  const body = rec.t[fmt].replace(PLACEHOLDER, id);
  // Links travel as base64, the way every app reads a list of them; the id goes in before that.
  return respond(request, fmt, rec, fmt === 'l' ? b64(body) : body);
}

export default {
  fetch(request, env) {
    return handle(request, env).catch(() => json({ error: 'internal' }, 500));
  },
};
