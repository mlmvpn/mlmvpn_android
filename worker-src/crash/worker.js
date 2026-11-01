/**
 * MLM VPN crash collector: the one place crash reports go, and nothing else.
 *
 * Crash reports used to ride on the Quick Connect pool's Worker (`mlmvpn_pool_worker.js`, still the
 * app's fallback). The pool authenticates every request against D1, and at its size it spends the
 * account's free D1 reads -- and its Worker requests -- before the day is out. On those days every
 * crash report was refused, which is exactly when they mattered. So this Worker:
 *
 *   - is meant to live on an account the pool does not use (scripts/deploy-crash-worker.mjs), so
 *     nothing the pool does can spend its limits;
 *   - uses no database at all. The issues in the private GitHub repository are the store and the
 *     place reports are read; KV, when bound, only remembers which issue a crash signature has,
 *     and in-memory maps carry that when KV is out of writes;
 *   - files before it answers, so a 200 means "a person can read this now" and anything else means
 *     "keep it and send it again". The app keeps every report until it gets a 200.
 *
 * One issue per crash signature, the same signature the pool computes (sha256 of the summary line
 * the app sends: the exception and the first frame of ours), so a crash filed through either one
 * lands on the same issue. A repeat adds a comment at most once an hour, carrying that report in
 * full; a closed issue that a NEWER build still hits is reopened, because that is a fix that did
 * not hold.
 *
 * Bindings: STATE (KV, optional). Secrets: GH_TOKEN (fine-grained, "Issues: read and write" on
 * GH_REPO and nothing else), GH_REPO (owner/name, private), ADMIN_TOKEN (for /health).
 *
 * Abuse: the app's key below is in every APK, so it only keeps out what scans URLs. The real limits
 * are per install and per address per day, a daily ceiling on NEW issues, and the signature
 * dedup -- one bug is one issue however often it is sent.
 */

/** Also in the app (CrashClient.APP_KEY). */
const APP_KEY = 'redacted-crash-key';

const MAX_BODY = 24 * 1024;
/** Reports wait on phones for days; a phone's clock can be wrong by hours. Replays only repeat. */
const TS_SLACK_MS = 3 * 24 * 60 * 60 * 1000;
const PER_INSTALL_PER_DAY = 40;
/** Carriers put thousands of subscribers behind one address (CGNAT): this only stops a flood. */
const PER_ADDRESS_PER_DAY = 400;
const NEW_ISSUES_PER_DAY = 150;
const COMMENT_EVERY_MS = 60 * 60 * 1000;
const INSTALLS_TRACKED = 50;
const MEMORY_MAX = 5000;
const KINDS = new Set(['jvm', 'native', 'anr', 'other']);
const GH_UA = 'mlmvpn-crash-collector';

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });

const enc = new TextEncoder();
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');

async function sha256Hex(text) {
  return hex(await crypto.subtle.digest('SHA-256', enc.encode(text)));
}

async function hmacHex(key, message) {
  const k = await crypto.subtle.importKey('raw', enc.encode(key), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return hex(await crypto.subtle.sign('HMAC', k, enc.encode(message)));
}

function sameHex(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

const today = () => new Date(Date.now()).toISOString().slice(0, 10);

// ------------------------------------------------------------------------------------- memory
//
// Per isolate. Approximate by nature -- several isolates, several data centres -- which is all a
// flood limit and a dedup cache in front of KV need to be.

const counters = new Map();

function take(kind, key, limit) {
  const k = kind + ':' + key;
  const day = today();
  const seen = counters.get(k);
  const n = seen && seen.day === day ? seen.n : 0;
  if (n >= limit) return false;
  counters.delete(k);
  counters.set(k, { day, n: n + 1 });
  if (counters.size > MEMORY_MAX * 4) counters.delete(counters.keys().next().value);
  return true;
}

const recs = new Map();

function remember(sig, rec) {
  recs.delete(sig);
  recs.set(sig, rec);
  if (recs.size > MEMORY_MAX) recs.delete(recs.keys().next().value);
}

async function kvGet(env, key) {
  if (!env.STATE) return null;
  try { return await env.STATE.get(key, 'json'); } catch (e) { return null; }
}

async function kvPut(env, key, value) {
  if (!env.STATE) return;
  try { await env.STATE.put(key, JSON.stringify(value)); } catch (e) { }
}

// ------------------------------------------------------------------------------------- GitHub

async function gh(env, path, init = {}) {
  try {
    return await fetch('https://api.github.com' + path, {
      ...init,
      headers: {
        'Authorization': 'Bearer ' + env.GH_TOKEN,
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': GH_UA,
        'Content-Type': 'application/json',
      },
    });
  } catch (e) {
    return null;
  }
}

/**
 * How the last GitHub calls went, for /health. Fine-grained tokens expire by design; when one
 * does, this is where it shows. Written to KV only when the state flips or an hour has passed.
 */
let health = null;

async function loadHealth(env) {
  if (!health) health = (await kvGet(env, 'gh')) || {};
  return health;
}

async function note(env, res, during) {
  const h = await loadHealth(env);
  const now = Date.now();
  const wasFailing = !!(h.last_error && (!h.last_ok_at || h.last_error.at > h.last_ok_at));
  let failing = false;
  if (res && res.ok) {
    h.last_ok_at = now;
  } else {
    let message = res ? await res.clone().text().catch(() => '') : 'no response (network error)';
    try { message = JSON.parse(message).message || message; } catch (e) { }
    h.last_error = { at: now, status: res ? res.status : 0, message: String(message).slice(0, 200), during };
    failing = true;
  }
  if (failing !== wasFailing || now - (h.saved_at || 0) > COMMENT_EVERY_MS) {
    h.saved_at = now;
    await kvPut(env, 'gh', h);
  }
}

/** The build number in an app string like "1.2.37 (2000069)", or 0. */
function buildOf(app) {
  const m = String(app || '').match(/\((\d+)\)/);
  return m ? Number(m[1]) : 0;
}

/** A code block nothing in the report can close early. */
function fenced(text) {
  const longest = Math.max(2, ...(text.match(/`+/g) || []).map((s) => s.length));
  const f = '`'.repeat(longest + 1);
  return f + '\n' + text + '\n' + f;
}

function factsOf(info) {
  return '| | |\n|---|---|\n' +
    `| kind | ${info.kind} |\n` +
    `| app | \`${info.app || '?'}\` |\n` +
    `| device | ${info.device || '?'} |\n` +
    `| Android | ${info.android || '?'} |\n` +
    `| signature | \`${info.sig}\` |\n`;
}

const failed = (res) => ({ ok: false, status: res ? res.status : 0 });

/** Files [info] on GitHub. `ok` only when a person can now read it there. */
async function file(env, info) {
  const now = Date.now();
  const repo = env.GH_REPO;
  const label = 'sig:' + info.sig;

  let rec = recs.get(info.sig) || (await kvGet(env, 'sig:' + info.sig));
  // A record that does not know its builds is treated as this one, never as older.
  if (rec && rec.build === undefined) rec.build = info.build;
  if (rec && !Array.isArray(rec.installs)) rec.installs = [];

  if (!rec) {
    // No record here does not mean no issue there: KV may be new, or the pool may have filed it.
    const found = await gh(env, `/repos/${repo}/issues?state=all&per_page=1&labels=${encodeURIComponent(label)}`);
    await note(env, found, 'looking up the issue');
    if (!found || !found.ok) return failed(found);
    const list = await found.json().catch(() => []);
    if (Array.isArray(list) && list.length > 0) {
      rec = { issue: list[0].number, count: 0, installs: [], first: now, last: now, commented: 0, build: info.build };
    }
  }

  let action;
  if (!rec) {
    const capKey = 'new:' + today();
    const made = Number(await kvGet(env, capKey)) || 0;
    if (made >= NEW_ISSUES_PER_DAY || !take('new', 'issues', NEW_ISSUES_PER_DAY)) {
      return { ok: false, status: 429 };
    }
    const title = (info.summary || info.body.split('\n')[0] || 'crash').slice(0, 200);
    const body = factsOf(info) + '\nFirst seen ' + new Date(now).toISOString() + '.\n\n' +
      '<details><summary>report</summary>\n\n' + fenced(info.body) + '\n\n</details>\n';
    const labels = ['crash', label].concat(info.kind === 'jvm' ? [] : [info.kind]);
    let res = await gh(env, `/repos/${repo}/issues`, { method: 'POST', body: JSON.stringify({ title, body, labels }) });
    // 422: labels cannot be created there. The issue matters more than its labels.
    if (res && res.status === 422) {
      res = await gh(env, `/repos/${repo}/issues`, { method: 'POST', body: JSON.stringify({ title, body }) });
    }
    await note(env, res, 'filing the issue');
    if (!res || !res.ok) return failed(res);
    const issue = await res.json().catch(() => null);
    if (!issue || !issue.number) return { ok: false, status: 502 };
    await kvPut(env, capKey, made + 1);
    rec = { issue: issue.number, count: 1, installs: [info.install], first: now, last: now, commented: now, build: info.build };
    action = 'filed';
  } else {
    rec.count = (rec.count || 0) + 1;
    rec.last = now;
    if (!rec.installs.includes(info.install) && rec.installs.length < INSTALLS_TRACKED) rec.installs.push(info.install);
    action = 'counted';
    if (now - (rec.commented || 0) > COMMENT_EVERY_MS) {
      const cur = await gh(env, `/repos/${repo}/issues/${rec.issue}`);
      await note(env, cur, 'checking the issue');
      if (!cur || !cur.ok) return failed(cur);
      const state = ((await cur.json().catch(() => null)) || {}).state;
      if (state === 'closed' && info.build <= (rec.build || 0)) {
        // An older build: a user who has not updated yet. The issue's story is unchanged.
        rec.commented = now;
        action = 'closed';
      } else {
        if (state === 'closed') {
          const re = await gh(env, `/repos/${repo}/issues/${rec.issue}`, { method: 'PATCH', body: JSON.stringify({ state: 'open' }) });
          await note(env, re, 'reopening the issue');
          if (!re || !re.ok) return failed(re);
          action = 'reopened';
        } else {
          action = 'commented';
        }
        const users = rec.installs.length + (rec.installs.length >= INSTALLS_TRACKED ? '+' : '');
        const head = action === 'reopened'
          ? `Reopened: seen on \`${info.app}\`, newer than every build it was reported from before it was closed.`
          : 'Seen again.';
        const text = `${head} ${rec.count} reports from ${users} installs so far.\n\n` + factsOf(info) +
          '\n<details><summary>this report</summary>\n\n' + fenced(info.body) + '\n\n</details>\n';
        const c = await gh(env, `/repos/${repo}/issues/${rec.issue}/comments`, { method: 'POST', body: JSON.stringify({ body: text }) });
        await note(env, c, 'commenting');
        if (!c || !c.ok) return failed(c);
        rec.commented = now;
      }
    }
  }
  if (info.build > (rec.build || 0)) rec.build = info.build;
  remember(info.sig, rec);
  await kvPut(env, 'sig:' + info.sig, rec);
  return { ok: true, issue: rec.issue, action };
}

// ------------------------------------------------------------------------------------- routes

async function report(request, env) {
  // Without these nothing can be filed; a 503 sends the app to its fallback and keeps the report.
  if (!env.GH_TOKEN || !env.GH_REPO) return json({ error: 'not configured' }, 503);

  const text = await request.text();
  if (text.length > MAX_BODY * 2) return json({ error: 'too large' }, 413);
  const ts = Number(request.headers.get('X-Ts'));
  if (!Number.isFinite(ts) || Math.abs(Date.now() - ts) > TS_SLACK_MS) return json({ error: 'bad time' }, 401);
  const expected = await hmacHex(APP_KEY, `${ts}.${text}`);
  if (!sameHex(expected, String(request.headers.get('X-Sig') || ''))) return json({ error: 'bad signature' }, 401);

  let p;
  try { p = JSON.parse(text); } catch (e) { return json({ error: 'bad json' }, 400); }
  const body = String(p.body || '').slice(0, MAX_BODY);
  if (!body.trim()) return json({ error: 'empty' }, 400);
  const summary = String(p.summary || '').slice(0, 300);
  const install = /^[0-9a-f]{8,64}$/.test(String(p.id || '')) ? String(p.id) : 'anonymous';
  const address = request.headers.get('CF-Connecting-IP') || 'unknown';
  if (!take('install', install, PER_INSTALL_PER_DAY) || !take('address', address, PER_ADDRESS_PER_DAY)) {
    return json({ error: 'rate limited' }, 429);
  }

  const app = String(p.app || '').slice(0, 40);
  const info = {
    // The same signature the pool computes, so both routes file one bug on one issue.
    sig: (await sha256Hex(summary || body.slice(0, 400))).slice(0, 16),
    summary,
    body,
    kind: KINDS.has(p.kind) ? p.kind : 'jvm',
    app,
    build: buildOf(app),
    device: String(p.device || '').slice(0, 80),
    android: String(p.android || '').slice(0, 20),
    install: (await sha256Hex('install.' + install)).slice(0, 12),
  };
  const r = await file(env, info);
  if (r.ok) return json({ ok: true, sig: info.sig, issue: r.issue, action: r.action });
  return json({ error: 'not filed', github_status: r.status, sig: info.sig }, r.status === 429 ? 429 : 502);
}

async function healthPage(env, url) {
  if (!env.ADMIN_TOKEN || url.searchParams.get('k') !== env.ADMIN_TOKEN) return new Response('unauthorized', { status: 401 });
  const h = await loadHealth(env);
  return json({
    github: {
      configured: !!(env.GH_TOKEN && env.GH_REPO),
      repo: env.GH_REPO || null,
      last_ok_at: h.last_ok_at || null,
      last_error: h.last_error || null,
    },
    kv: !!env.STATE,
    new_issues_today: Number(await kvGet(env, 'new:' + today())) || 0,
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (request.method === 'POST' && url.pathname === '/v1/crash') return await report(request, env);
      if (url.pathname === '/health') return await healthPage(env, url);
      // The app's route check (WorkerRoute) sends HEAD / and only needs a status line.
      if (url.pathname === '/') return new Response('ok', { headers: { 'cache-control': 'no-store' } });
      return json({ error: 'not found' }, 404);
    } catch (e) {
      return json({ error: String(e && e.message ? e.message : e) }, 500);
    }
  },
};
