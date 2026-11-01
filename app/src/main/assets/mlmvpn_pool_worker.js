/**
 * The MLMVPN shared pool: a live subscription built from what our own users measure.
 *
 * The free-server feeds this app reads carry ~11,500 configs of which roughly 1% work at any
 * moment. Measured on a 300-config sample from an Iranian mobile line: 36% open a TCP port, 20%
 * complete a TLS handshake for the name the config asks for, and about 1% pass a real proxied
 * request. The 36% is the misleading one -- most of it is shared Cloudflare addresses that accept
 * TCP from anyone while the domain behind them is long gone.
 *
 * Five thousand people run those sweeps every day and throw the answers away. This keeps them.
 *
 * The unit of storage is deliberately NOT "a config". A config that works on Irancell can be dead
 * on MCI at the same second, so everything here is keyed by (config, ISP) -- and the ISP comes
 * from Cloudflare's own `request.cf.asn`, which costs the client no permission and lets us bucket
 * fixed-line users (Shatel, Pars Online, TCI) correctly too. No IP address is ever stored.
 */

const HALF_LIFE_MS = 2 * 60 * 60 * 1000;   // a success ages out of relevance in hours, not days
const LIST_SIZE = 20;                       // what one client gets, to race
const TRIAL_SLOTS = 2;                      // of those, reserved for unproven configs
const POOL_SIZE = 200;                      // the top band we sample from, to spread load
const FRESH_OK_MS = 60 * 60 * 1000;         // a config is servable if it worked within the hour
const QUARANTINE_RETRY_MS = 6 * 60 * 60 * 1000;
const PROMOTE_USERS = 2;                    // distinct successes to leave probation
const CONDEMN_USERS = 5;                    // distinct failures to quarantine
const FAIL_USER_CAP = 8;                    // how many reporter ids we remember per row
const CACHE_TTL_MS = 10 * 60 * 1000;
const MAX_REPORT_ROWS = 12;                 // hard cap per request, to protect the D1 write budget
const RATE_LIMIT_PER_DAY = 60;

// -------------------------------------------------------------------------------------------------
// Read budget.
//
// D1's free tier bills ROWS READ, and it counts rows a query had to look at, not rows it returned.
// On 2026-09-06 the whole account hit the daily ceiling and every endpoint here started answering
// with a D1 error -- which takes the config pool down for every user at once, because a worker that
// cannot read its database has nothing to serve.
//
// The cause was one clause. The trial query ended in `ORDER BY RANDOM()`, and RANDOM() cannot use an
// index: SQLite must materialise EVERY matching row before it can sort them, so `LIMIT 6` saved
// nothing. Its filter was `state = 'probation'` with no time bound, and probation is the default
// state of every config anyone has ever reported -- so each call read every unproven row ever
// recorded for that ISP, and the cost grew with the table forever.
//
// Every read on the serving path is bounded now: each query names ONE state so the (isp, state, ...)
// index can be walked in order and abandoned at the LIMIT. The numbers below are what one /list call
// may look at, and they are ceilings rather than estimates.
const BAND_ACTIVE_ROWS = POOL_SIZE;   // proven configs -- the bulk of what gets served
const BAND_PROBATION_ROWS = 50;       // unproven ones can enter the band, but not flood it
const TRIAL_SCAN_ROWS = TRIAL_SLOTS * 3;
const RETRY_SCAN_ROWS = TRIAL_SLOTS * 2;

// How long a row can sit untouched before the sweep drops it. Nothing here ever deleted anything,
// which is the other half of why the reads grew without limit.
const PRUNE_QUARANTINE_MS = 7 * 24 * 60 * 60 * 1000;
const PRUNE_PROBATION_MS = 3 * 24 * 60 * 60 * 1000;
const PRUNE_CRASH_MS = 30 * 24 * 60 * 60 * 1000;
const PRUNE_BATCH = 400;              // per sweep, so one sweep is never expensive
const PRUNE_ODDS = 40;                // roughly one report in this many carries a sweep
const STATS_CACHE_S = 300;            // the dashboard is one person refreshing; do not rescan

/**
 * Password for the dashboard at /stats.
 *
 * A single shared secret rather than a login: this is a read-only view of counts seen by one
 * person, and anything more would be ceremony. Deliberately NOT the credential the app uses --
 * an install token cannot open the dashboard, and this password cannot fetch configs.
 */
const STATS_PASSWORD = 'redacted-stats-password';

// -------------------------------------------------------------------------------------------------
// schema
//
// Created on first request rather than by the installer, the same way mlm_worker.js and
// nahan_worker.js do it. The app never runs SQL of its own.
// -------------------------------------------------------------------------------------------------
let schemaReady = false;

async function ensureSchema(db) {
  if (schemaReady) return;
  await db.batch([
    db.prepare(`CREATE TABLE IF NOT EXISTS configs (
      h TEXT PRIMARY KEY,
      uri TEXT NOT NULL,
      proto TEXT, host TEXT, port INTEGER,
      country TEXT, provider TEXT,
      first_seen INTEGER, last_seen INTEGER
    )`),
    db.prepare(`CREATE TABLE IF NOT EXISTS stats (
      h TEXT NOT NULL,
      isp TEXT NOT NULL,
      ok REAL DEFAULT 0,
      bad REAL DEFAULT 0,
      last_ok INTEGER DEFAULT 0,
      last_bad INTEGER DEFAULT 0,
      touched INTEGER DEFAULT 0,
      ms INTEGER DEFAULT 0,
      ok_users TEXT DEFAULT '[]',
      fail_users TEXT DEFAULT '[]',
      state TEXT DEFAULT 'probation',
      PRIMARY KEY (h, isp)
    )`),
    db.prepare(`CREATE INDEX IF NOT EXISTS stats_serve
      ON stats (isp, state, last_ok)`),
    // The trial scan walks this one from a random point on the hash ring. `h` is a sha256, so it
    // is already uniformly distributed -- which is what lets a random sample cost exactly as many
    // rows as it returns, with no extra column and no backfill.
    db.prepare(`CREATE INDEX IF NOT EXISTS stats_trial
      ON stats (isp, state, h)`),
    // The quarantine retry queue: oldest failure first, and a row that fails again goes to the
    // back of it by itself, because retrying updates last_bad.
    db.prepare(`CREATE INDEX IF NOT EXISTS stats_retry
      ON stats (isp, state, last_bad)`),
    // For the sweep, which asks the one question none of the serving indexes can answer cheaply:
    // which rows of this state has nobody touched in days.
    db.prepare(`CREATE INDEX IF NOT EXISTS stats_prune
      ON stats (state, touched)`),
    db.prepare(`CREATE TABLE IF NOT EXISTS installs (
      id TEXT PRIMARY KEY,
      secret TEXT NOT NULL,
      created INTEGER,
      day TEXT,
      calls INTEGER DEFAULT 0
    )`),
    // Crash reports. Deliberately a separate table with its own retention: it is the only thing
    // here that is written by a broken app rather than a working one, and mixing it into the
    // pool's tables would let a crash loop from one install compete for space with the config
    // data every user depends on.
    //
    // `sig` is a hash of the crash's identity (exception + top frames), not of the whole text.
    // That is what makes "one bug, four hundred users" readable: the reports group by sig, so a
    // single query answers "which crash is hitting the most people" instead of four hundred
    // stacks that have to be eyeballed for sameness.
    db.prepare(`CREATE TABLE IF NOT EXISTS crashes (
      id TEXT PRIMARY KEY,
      sig TEXT NOT NULL,
      install TEXT NOT NULL,
      at INTEGER NOT NULL,
      app TEXT, device TEXT, android TEXT,
      summary TEXT,
      body TEXT
    )`),
    db.prepare(`CREATE INDEX IF NOT EXISTS crashes_sig ON crashes (sig, at)`),
    db.prepare(`CREATE INDEX IF NOT EXISTS crashes_at ON crashes (at)`),
  ]);
  // CREATE TABLE IF NOT EXISTS does not add columns to a table that already exists.
  try { await db.prepare('ALTER TABLE installs ADD COLUMN last_isp TEXT').run(); } catch (e) { }
  // Crash reports' own daily allowance; see CRASH_PER_DAY.
  try { await db.prepare('ALTER TABLE installs ADD COLUMN crash_day TEXT').run(); } catch (e) { }
  try { await db.prepare('ALTER TABLE installs ADD COLUMN crash_calls INTEGER DEFAULT 0').run(); } catch (e) { }
  schemaReady = true;
}

// -------------------------------------------------------------------------------------------------
// helpers
// -------------------------------------------------------------------------------------------------

const enc = new TextEncoder();

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', enc.encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Short, stable id for a config. The full URI is stored once, in `configs`. */
async function hashOf(uri) {
  return (await sha256Hex(uri.trim())).slice(0, 24);
}

async function hmacHex(secret, message) {
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(message));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Time-decayed weight.
 *
 * A success five minutes ago says far more about right now than one from six hours ago, and on a
 * network where blocking comes and goes within a day a flat counter would keep recommending
 * configs that stopped working before lunch.
 */
function decay(value, since, now) {
  if (!value || !since) return 0;
  const age = Math.max(0, now - since);
  return value * Math.pow(0.5, age / HALF_LIFE_MS);
}

function jsonResponse(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

/** Which network the caller is on, from Cloudflare's own view of the connection. */
function ispOf(request) {
  const asn = request.cf && request.cf.asn;
  return asn ? `as${asn}` : 'unknown';
}

/**
 * Rough provider bucket, used only to keep one served list from being all-Cloudflare.
 *
 * When an operator blocks a range, everything behind it dies together -- so twenty configs that
 * all sit on the same edge are, for reliability purposes, one config.
 */
function providerOf(host, port) {
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
    const a = Number(host.split('.')[0]);
    const b = Number(host.split('.')[1]);
    if (a === 104 || a === 172 && b >= 64 || a === 162 || a === 188 && b === 114) return 'cf';
  }
  if (/\.workers\.dev$/i.test(host) || /\.pages\.dev$/i.test(host)) return 'cf';
  return `p${port}`;
}

// -------------------------------------------------------------------------------------------------
// auth
//
// The APK can be taken apart, so none of this is a wall -- it is a series of speed bumps whose
// point is that a leaked token is worth very little. Every response carries at most LIST_SIZE
// configs chosen at random from the top band, so even unlimited calls cannot enumerate the pool
// quickly, and the rate limit means they are not unlimited.
// -------------------------------------------------------------------------------------------------

/**
 * Secrets this isolate has already checked against D1, so a crash report can still be verified on
 * a day D1 refuses every query -- see `authenticate`. Memory only: never written anywhere, gone
 * with the isolate, and capped so a busy isolate cannot grow it without bound.
 */
const verifiedSecrets = new Map();
const VERIFIED_SECRETS_MAX = 20000;

function rememberSecret(id, secret) {
  if (verifiedSecrets.get(id) === secret) return;
  verifiedSecrets.delete(id);
  verifiedSecrets.set(id, secret);
  if (verifiedSecrets.size > VERIFIED_SECRETS_MAX) {
    verifiedSecrets.delete(verifiedSecrets.keys().next().value);
  }
}

/** Crash reports accepted per install while D1 is down, in this isolate, today. */
const offlineCrashes = new Map();

function takeOfflineCrashSlot(id) {
  const today = new Date(Date.now()).toISOString().slice(0, 10);
  const seen = offlineCrashes.get(id);
  const n = seen && seen.day === today ? seen.n : 0;
  if (n >= CRASH_PER_DAY) return false;
  offlineCrashes.set(id, { day: today, n: n + 1 });
  return true;
}

/**
 * `kind` is 'crash' for `/crash` and 'pool' for everything else. They are counted apart: a crash
 * report used to share the pool's sixty calls a day, so whoever had leaned on Quick Connect that
 * day -- the people most likely to hit a bug in it -- had their crash report refused with a 429.
 */
async function authenticate(request, db, body, kind = 'pool') {
  const id = request.headers.get('X-Install');
  const ts = request.headers.get('X-Ts');
  const sig = request.headers.get('X-Sig');
  if (!id || !ts || !sig) return { ok: false, status: 401, error: 'missing credentials' };

  // A five minute window: enough for a slow mobile round trip, short enough that a captured
  // request cannot be replayed later.
  const skew = Math.abs(Date.now() - Number(ts));
  if (!Number.isFinite(skew) || skew > 5 * 60 * 1000) {
    return { ok: false, status: 401, error: 'stale request' };
  }

  let row;
  try {
    row = await db.prepare(
      'SELECT secret, day, calls, last_isp, crash_day, crash_calls FROM installs WHERE id = ?'
    ).bind(id).first();
  } catch (e) {
    // D1 is refusing queries -- on the free plan, typically the account's daily read ceiling,
    // which this pool reaches at its size. Everything the pool serves needs the database; a crash
    // report does not, and losing them on exactly the days the service is struggling is what
    // this guards against. An install this isolate verified earlier is checked against the
    // secret it proved then: the same signature check, without the read.
    const known = kind === 'crash' ? verifiedSecrets.get(id) : null;
    if (!known) return { ok: false, status: 503, error: 'pool database unavailable' };
    const expected = await hmacHex(known, `${id}.${ts}.${body || ''}`);
    if (expected !== sig) return { ok: false, status: 401, error: 'bad signature' };
    if (!takeOfflineCrashSlot(id)) return { ok: false, status: 429, error: 'rate limited' };
    return { ok: true, id, tick: null, lastIsp: null, offline: true };
  }
  if (!row) return { ok: false, status: 401, error: 'not enrolled' };

  const expected = await hmacHex(row.secret, `${id}.${ts}.${body || ''}`);
  if (expected !== sig) return { ok: false, status: 401, error: 'bad signature' };
  rememberSecret(id, row.secret);

  const today = new Date(Date.now()).toISOString().slice(0, 10);
  if (kind === 'crash') {
    const crashes = row.crash_day === today ? (row.crash_calls || 0) : 0;
    if (crashes >= CRASH_PER_DAY) return { ok: false, status: 429, error: 'rate limited' };
    const tick = db.prepare('UPDATE installs SET crash_day = ?, crash_calls = ? WHERE id = ?')
      .bind(today, crashes + 1, id);
    return { ok: true, id, tick, lastIsp: row.last_isp || null };
  }
  const calls = row.day === today ? (row.calls || 0) : 0;
  if (calls >= RATE_LIMIT_PER_DAY) {
    return { ok: false, status: 429, error: 'rate limited' };
  }

  // Not executed here: handed back so the caller can send it in the same batch as its own
  // queries. One fewer round trip to a database that is an ocean away.
  const tick = db.prepare('UPDATE installs SET day = ?, calls = ? WHERE id = ?')
    .bind(today, calls + 1, id);

  return { ok: true, id, tick, lastIsp: row.last_isp || null };
}

// -------------------------------------------------------------------------------------------------
// the served list
// -------------------------------------------------------------------------------------------------

/**
 * Rank the rows the ledger returned.
 *
 * Deliberately no cache anywhere in this path. A KV cache was the obvious move and it was wrong
 * here: with a ten minute TTL a config that five users had just condemned kept being handed out,
 * and one that had just been promoted stayed invisible -- on a list whose entire value is
 * freshness.
 *
 * That is affordable only because the reads are bounded. The estimate this comment used to carry
 * -- "~200 rows per request" -- was true of the band and wildly untrue of the trial query beside
 * it, which read every unproven row for the ISP on every call and took the whole account over D1's
 * daily ceiling on 2026-09-06. Measured against a 60k-row table, 20k of them on the ISP being
 * served: the old trial query cost 378,760 VDBE steps and the one that replaced it costs 111, and
 * unlike the old one that number does not grow with the table.
 */
function score(rows, now) {
  return rows.map((r) => {
    const ok = decay(r.ok, r.last_ok, now);
    const bad = decay(r.bad, r.last_ok, now);
    // Latency matters, but only as a tiebreak: a 90%-reliable 400ms server beats a
    // 40%-reliable 80ms one every time.
    const reliability = ok / (ok + bad + 1);
    const speed = r.ms > 0 ? Math.min(1, 500 / r.ms) : 0.3;
    return { ...r, score: reliability * 0.85 + speed * 0.15 };
  }).sort((a, b) => b.score - a.score);
}

/** Pick the list one client gets: diverse, randomised, with room for unproven configs. */
function chooseServed(pool, trials) {
  const byProvider = new Map();
  const shuffled = [...pool].sort(() => Math.random() - 0.5);
  const picked = [];

  // At most a third of the list from any one provider, so a single edge going down cannot take
  // the whole list with it.
  const cap = Math.max(2, Math.ceil((LIST_SIZE - TRIAL_SLOTS) / 3));
  for (const row of shuffled) {
    if (picked.length >= LIST_SIZE - TRIAL_SLOTS) break;
    const key = row.provider || 'other';
    const used = byProvider.get(key) || 0;
    if (used >= cap) continue;
    byProvider.set(key, used + 1);
    picked.push(row);
  }
  // Top up if diversity starved the list.
  for (const row of shuffled) {
    if (picked.length >= LIST_SIZE - TRIAL_SLOTS) break;
    if (!picked.includes(row)) picked.push(row);
  }
  return picked.concat(trials.slice(0, TRIAL_SLOTS));
}

/** A random point on the hash ring, in the same shape `stats.h` is stored in. */
function randomCursor() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * The band, one state at a time.
 *
 * `state IN ('active','probation')` looks like one query and costs like a table scan: with two
 * values on the second index column, SQLite cannot also read the third in order, so it collects
 * every matching row and sorts. Asked one state at a time, the (isp, state, last_ok) index IS the
 * requested order, so the walk stops at the LIMIT and the read is exactly what it returns.
 */
function bandQuery(db, isp, state, cutoff, limit) {
  return db.prepare(
    `SELECT s.h, s.ok, s.bad, s.last_ok, s.ms, c.uri, c.country, c.provider
       FROM stats s JOIN configs c ON c.h = s.h
      WHERE s.isp = ? AND s.state = ? AND s.last_ok > ?
      ORDER BY s.last_ok DESC
      LIMIT ?`,
  ).bind(isp, state, cutoff, limit);
}

/**
 * Unproven configs, sampled from a random point on the hash ring.
 *
 * This is what `ORDER BY RANDOM()` was for, and it is the one that took the account down: RANDOM()
 * cannot be indexed, so every call read every probation row for that ISP before throwing all but
 * six away. `h` is a sha256 of the config URI, which is already uniform, so walking (isp, state, h)
 * forward from a random cursor is the same sample for the price of the rows returned. The caller
 * pairs this with a second one from the start of the ring, because a cursor near the end finds few
 * rows after it.
 */
function trialQuery(db, isp, cursor, limit) {
  return db.prepare(
    `SELECT s.h, c.uri, c.country, c.provider, s.ms
       FROM stats s JOIN configs c ON c.h = s.h
      WHERE s.isp = ? AND s.state = 'probation' AND s.h > ?
      ORDER BY s.h
      LIMIT ?`,
  ).bind(isp, cursor, limit);
}

async function handleList(request, env, ctx, db, tick, installId) {
  const isp = ispOf(request);
  const now = Date.now();
  const cutoff = now - FRESH_OK_MS;
  const cursor = randomCursor();

  // One round trip for all of it. Every read below is bounded by its own LIMIT and served in
  // index order, so the cost of a call no longer grows with the size of the table.
  const [, , activeRes, probationRes, trialRes, trialWrapRes, retryRes] = await db.batch([
    tick,
    db.prepare('UPDATE installs SET last_isp = ? WHERE id = ?').bind(isp, installId),
    bandQuery(db, isp, 'active', cutoff, BAND_ACTIVE_ROWS),
    bandQuery(db, isp, 'probation', cutoff, BAND_PROBATION_ROWS),
    trialQuery(db, isp, cursor, TRIAL_SCAN_ROWS),
    // The wrap. A cursor that lands near the end of the ring has little after it, and without
    // this the tail of the hash space would simply be sampled less often than the head.
    trialQuery(db, isp, '', TRIAL_SCAN_ROWS),
    // Quarantined configs due for a retry, oldest failure first. A row that fails again writes
    // last_bad = now and goes to the back of this queue by itself, so it rotates without RANDOM().
    db.prepare(
      `SELECT s.h, c.uri, c.country, c.provider, s.ms
         FROM stats s JOIN configs c ON c.h = s.h
        WHERE s.isp = ? AND s.state = 'quarantine' AND s.last_bad < ?
        ORDER BY s.last_bad
        LIMIT ?`,
    ).bind(isp, now - QUARANTINE_RETRY_MS, RETRY_SCAN_ROWS),
  ]);

  // The band is scored across both states together, exactly as before -- splitting the query
  // changed what it costs to read, not what gets served.
  const band = (activeRes.results || []).concat(probationRes.results || []);
  const pool = score(band, now).slice(0, POOL_SIZE);

  // Discovery and forgiveness share the trial slots, as they did when one query returned both.
  const trialSeen = new Set();
  const trials = [];
  for (const row of (trialRes.results || [])
    .concat(trialWrapRes.results || [])
    .concat(retryRes.results || [])) {
    if (trialSeen.has(row.h)) continue;
    trialSeen.add(row.h);
    trials.push(row);
  }
  trials.sort(() => Math.random() - 0.5);

  const served = chooseServed(pool, trials);

  return jsonResponse({
    isp,
    at: now,
    pool: pool.length,
    servers: served.map((r) => ({
      h: r.h,
      uri: r.uri,
      country: r.country || null,
      ms: r.ms || 0,
    })),
  });
}

// -------------------------------------------------------------------------------------------------
// reports
// -------------------------------------------------------------------------------------------------

/**
 * Fold one batch of client results into the ledger.
 *
 * The client sends deltas only -- a handful of rows per session, not every test it ran. That is a
 * budget decision as much as a design one: D1's free tier allows 100,000 row writes a day and the
 * limit is per account, so writing every result would exhaust it at a few thousand users.
 */
/**
 * Drop what nobody is coming back for.
 *
 * Nothing here ever deleted a row. `stats` is one row per (config, ISP) and users report from
 * feeds of eleven thousand configs of which about one percent work, so the table grew by every
 * dead config every user ever tried, on every network they tried it from -- and every one of those
 * rows sat in `probation` forever, which is exactly the state the trial query had to read through.
 * Bounding that query stops the bleeding; this stops the table that caused it from growing without
 * end.
 *
 * What goes: quarantined rows nobody has touched in a week (five people failed it and no one has
 * looked since), unproven rows nobody has touched in three days (reported once, never confirmed,
 * and the feeds have moved on), and crash reports older than a month. What never goes: anything
 * `active`, and anything touched recently, whatever its state.
 *
 * A config deleted here is not lost -- `configs` keeps the URI, and the next user who measures it
 * re-creates the stats row. This is a cache of evidence, not the evidence itself.
 *
 * Bounded on purpose: `PRUNE_BATCH` rows per statement, on roughly one report in `PRUNE_ODDS`, so
 * the sweep is never the expensive thing in a request. It runs after the response has been sent.
 */
async function sweep(db, now) {
  const doomed = (table, where, cutoff) => db.prepare(
    `DELETE FROM ${table} WHERE rowid IN (
       SELECT rowid FROM ${table} WHERE ${where} LIMIT ${PRUNE_BATCH})`,
  ).bind(cutoff);
  try {
    await db.batch([
      doomed('stats', "state = 'quarantine' AND touched < ?", now - PRUNE_QUARANTINE_MS),
      doomed('stats', "state = 'probation' AND touched < ?", now - PRUNE_PROBATION_MS),
      doomed('crashes', 'at < ?', now - PRUNE_CRASH_MS),
    ]);
  } catch (e) {
    // A sweep that fails is a sweep that runs again in fifty reports. It must never be able to
    // fail the report it is riding on, which has already been answered by the time this runs.
  }
}

async function handleReport(request, env, ctx, db, installId, body, lastIsp) {
  let payload;
  try {
    payload = JSON.parse(body || '{}');
  } catch (e) {
    return jsonResponse({ error: 'bad json' }, 400);
  }

  // The ASN this install had when it last fetched, before any tunnel was up. Falls back to the
  // current connection for an install that has never fetched.
  const isp = lastIsp || ispOf(request);
  const now = Date.now();
  const items = Array.isArray(payload.results) ? payload.results.slice(0, MAX_REPORT_ROWS) : [];
  if (items.length === 0) return jsonResponse({ ok: true, accepted: 0 });

  const statements = [];
  let accepted = 0;

  for (const item of items) {
    const uri = typeof item.uri === 'string' ? item.uri.trim() : '';
    if (!uri || uri.length > 2000) continue;
    // Only a real proxied request counts as a success. A TCP connect proves nothing here: the
    // shared Cloudflare addresses these configs sit on accept TCP from anyone.
    const good = item.ok === true && Number(item.ms) > 0;
    const h = await hashOf(uri);

    let host = '';
    let port = 443;
    let proto = '';
    try {
      const u = new URL(uri);
      proto = (u.protocol || '').replace(':', '');
      host = u.hostname;
      port = Number(u.port) || 443;
    } catch (e) { /* vmess:// and ss:// are not URLs; the hash still works */ }

    statements.push(
      db.prepare(
        `INSERT INTO configs (h, uri, proto, host, port, country, provider, first_seen, last_seen)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(h) DO UPDATE SET last_seen = excluded.last_seen`,
      ).bind(h, uri, proto, host, port, item.country || null, providerOf(host, port), now, now),
    );

    const prev = await db.prepare(
      'SELECT ok, bad, last_ok, last_bad, ok_users, fail_users, state, ms FROM stats WHERE h = ? AND isp = ?',
    ).bind(h, isp).first();

    let ok = prev ? decay(prev.ok, prev.last_ok, now) : 0;
    let bad = prev ? decay(prev.bad, prev.last_bad, now) : 0;
    let okUsers = prev ? JSON.parse(prev.ok_users || '[]') : [];
    let failUsers = prev ? JSON.parse(prev.fail_users || '[]') : [];
    let lastOk = prev ? prev.last_ok : 0;
    let lastBad = prev ? prev.last_bad : 0;
    let ms = prev ? prev.ms : 0;
    let state = prev ? prev.state : 'probation';

    if (good) {
      ok += 1;
      lastOk = now;
      if (!okUsers.includes(installId)) okUsers = [installId, ...okUsers].slice(0, FAIL_USER_CAP);
      // A success is also a pardon: whatever went wrong before has clearly stopped.
      failUsers = [];
      bad = 0;
      ms = ms > 0 ? Math.round(ms * 0.7 + Number(item.ms) * 0.3) : Number(item.ms);
      state = okUsers.length >= PROMOTE_USERS ? 'active' : 'probation';
    } else {
      bad += 1;
      lastBad = now;
      if (!failUsers.includes(installId)) failUsers = [installId, ...failUsers].slice(0, FAIL_USER_CAP);
      // Distinct people, on this network, with nothing newer saying otherwise. One user with a
      // broken connection must not be able to delete a working server for everybody else.
      if (failUsers.length >= CONDEMN_USERS && lastOk < lastBad) state = 'quarantine';
    }

    statements.push(
      db.prepare(
        `INSERT INTO stats (h, isp, ok, bad, last_ok, last_bad, touched, ms, ok_users, fail_users, state)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(h, isp) DO UPDATE SET
           ok = excluded.ok, bad = excluded.bad,
           last_ok = excluded.last_ok, last_bad = excluded.last_bad,
           touched = excluded.touched, ms = excluded.ms,
           ok_users = excluded.ok_users, fail_users = excluded.fail_users,
           state = excluded.state`,
      ).bind(
        h, isp, ok, bad, lastOk, lastBad, now, ms,
        JSON.stringify(okUsers), JSON.stringify(failUsers), state,
      ),
    );
    accepted += 1;
  }

  if (statements.length > 0) await db.batch(statements);

  // After the answer, not before it: the user is waiting for an acknowledgement, not for
  // housekeeping. One report in PRUNE_ODDS carries it, which is often enough to keep up with
  // the inflow and rare enough to cost nothing on any single call.
  if (ctx && Math.random() * PRUNE_ODDS < 1) ctx.waitUntil(sweep(db, now));

  return jsonResponse({ ok: true, accepted });
}


// -------------------------------------------------------------------------------------------------
// the dashboard
//
// Read-only, and deliberately outside the install-token path: watching the pool grow should not
// require the app, and the app's credentials should not open the books.
// -------------------------------------------------------------------------------------------------

async function handleStats(request, env, db, url) {
  if (url.searchParams.get('k') !== STATS_PASSWORD) {
    return new Response('unauthorized', { status: 401 });
  }
  const now = Date.now();

  // Served from KV for five minutes.
  //
  // Every figure on this page is an aggregate over the whole of `stats` -- two GROUP BYs and a
  // join -- so each load reads the entire table three times over. That is affordable once and
  // ruinous on a tab someone left refreshing, which is the shape of a dashboard nobody thought
  // of as a cost. Five minutes is short enough that the numbers still move while you watch and
  // long enough that watching cannot be what breaks the pool. `?fresh=1` forces a rescan.
  const cache = env && env.POOL;
  const fresh = url.searchParams.get('fresh') === '1';
  if (cache && !fresh) {
    const hit = await cache.get('stats:html').catch(() => null);
    if (hit) {
      return new Response(hit, {
        headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' },
      });
    }
  }

  const [totals, byIsp, byState, recent, installs] = await db.batch([
    db.prepare('SELECT COUNT(*) AS n FROM configs'),
    db.prepare(
      "SELECT isp, COUNT(*) AS n," +
      " SUM(CASE WHEN state = 'active' THEN 1 ELSE 0 END) AS active," +
      " SUM(CASE WHEN state = 'probation' THEN 1 ELSE 0 END) AS probation," +
      " SUM(CASE WHEN state = 'quarantine' THEN 1 ELSE 0 END) AS quarantine," +
      " SUM(CASE WHEN last_ok > ? THEN 1 ELSE 0 END) AS fresh," +
      " CAST(AVG(CASE WHEN ms > 0 THEN ms END) AS INTEGER) AS avg_ms" +
      " FROM stats GROUP BY isp ORDER BY n DESC LIMIT 40"
    ).bind(now - FRESH_OK_MS),
    db.prepare('SELECT state, COUNT(*) AS n FROM stats GROUP BY state'),
    db.prepare(
      "SELECT c.country, COUNT(*) AS n FROM stats s JOIN configs c ON c.h = s.h" +
      " WHERE s.state = 'active' AND s.last_ok > ?" +
      " GROUP BY c.country ORDER BY n DESC LIMIT 15"
    ).bind(now - FRESH_OK_MS),
    db.prepare('SELECT COUNT(*) AS n FROM installs'),
  ]);

  const stateMap = {};
  for (const r of byState.results || []) stateMap[r.state] = r.n;

  const rows = (byIsp.results || []).map(function (r) {
    return '<tr><td class="mono">' + r.isp + '</td><td>' + r.n + '</td>' +
      '<td class="ok">' + (r.active || 0) + '</td>' +
      '<td class="warn">' + (r.probation || 0) + '</td>' +
      '<td class="bad">' + (r.quarantine || 0) + '</td>' +
      '<td><b>' + (r.fresh || 0) + '</b></td>' +
      '<td>' + (r.avg_ms || '-') + '</td></tr>';
  }).join('');

  const countries = (recent.results || []).map(function (r) {
    return '<span class="chip">' + (r.country || '??') + ' <b>' + r.n + '</b></span>';
  }).join(' ');

  const css =
    'body{font:14px/1.5 ui-sans-serif,system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:20px}' +
    'h1{font-size:18px;margin:0 0 4px}.sub{color:#888;font-size:12px;margin-bottom:20px}' +
    '.cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:22px}' +
    '.card{background:#1c1c1e;border-radius:12px;padding:12px 16px;min-width:110px}' +
    '.card .v{font-size:22px;font-weight:600}.card .l{color:#888;font-size:11px}' +
    'table{border-collapse:collapse;width:100%;font-size:13px}' +
    'th,td{text-align:left;padding:7px 10px;border-bottom:1px solid #262628}' +
    'th{color:#888;font-weight:500;font-size:11px;text-transform:uppercase}' +
    '.mono{font-family:ui-monospace,monospace;color:#9cf}' +
    '.ok{color:#4ade80}.warn{color:#fbbf24}.bad{color:#f87171}' +
    '.chip{display:inline-block;background:#1c1c1e;border-radius:8px;padding:3px 9px;margin:2px 0;font-size:12px}' +
    '.note{color:#666;font-size:11px;margin-top:22px;line-height:1.7}';

  const html =
    '<!doctype html><html><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>MLMVPN pool</title><style>' + css + '</style></head><body>' +
    '<h1>MLMVPN shared pool</h1>' +
    '<div class="sub">' + new Date(now).toISOString().replace('T', ' ').slice(0, 19) +
      ' UTC &middot; refresh to update</div>' +
    '<div class="cards">' +
      '<div class="card"><div class="v">' + ((totals.results[0] || {}).n || 0) + '</div><div class="l">configs known</div></div>' +
      '<div class="card"><div class="v ok">' + (stateMap.active || 0) + '</div><div class="l">active</div></div>' +
      '<div class="card"><div class="v warn">' + (stateMap.probation || 0) + '</div><div class="l">probation</div></div>' +
      '<div class="card"><div class="v bad">' + (stateMap.quarantine || 0) + '</div><div class="l">quarantine</div></div>' +
      '<div class="card"><div class="v">' + ((installs.results[0] || {}).n || 0) + '</div><div class="l">installs</div></div>' +
    '</div>' +
    '<h1>By network</h1>' +
    '<div class="sub">one row per ISP. "fresh" is what would actually be served right now.</div>' +
    '<table><tr><th>ASN</th><th>tracked</th><th>active</th><th>probation</th>' +
      '<th>quarantine</th><th>fresh</th><th>avg ms</th></tr>' +
      (rows || '<tr><td colspan="7" style="color:#666">nothing reported yet</td></tr>') +
    '</table>' +
    '<h1 style="margin-top:26px">Live countries</h1>' +
    '<div class="sub">active and confirmed within the last hour</div>' +
    '<div>' + (countries || '<span style="color:#666">none yet</span>') + '</div>' +
    '<div class="note">A config is <span class="ok">active</span> once two distinct installs on ' +
      'that network have proven it with a real proxied request, <span class="warn">probation</span> ' +
      'after the first, and <span class="bad">quarantine</span> once five distinct installs have ' +
      'failed it with no newer success. A single success pardons it. Scores decay with a two hour ' +
      'half-life, so "fresh" is the number that matters &mdash; the rest is history.</div>' +
    '</body></html>';

  if (cache) {
    await cache.put('stats:html', html, { expirationTtl: STATS_CACHE_S }).catch(() => {});
  }

  return new Response(html, {
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

// -------------------------------------------------------------------------------------------------

// -------------------------------------------------------------------------------------------------
// crash reports
//
// The app has always written a full stack to files/crashlogs/ and there was never a way for it to
// reach anyone. That gap is why "the app crashes when I open V2Ray" could be reported by several
// users and stay unreproducible for weeks: the artefact that answers it in a minute was sitting on
// their phones. This is the other end of that pipe.
//
// Since build 2 this is the FALLBACK. The app sends to the crash collector first (worker-src/crash,
// on another account, no database) and comes here only when that does not file the report -- this
// pool's own D1 and request limits are what used to lose crash reports on its busiest days.
//
// It is authenticated like every other endpoint, so a report is tied to an enrolled install and
// the same rate limit applies -- a crash loop cannot turn one phone into a firehose.
//
// WHERE THE REPORTS END UP, because it is not this file and not this account:
//
//   * GitHub, https://github.com/mlmvpn/crashes -- PRIVATE, and one issue per crash signature.
//     This is the copy a person reads, and the only one that notifies anybody. Requires the
//     GH_TOKEN and GH_REPO secrets below; without them `fileCrashOnGithub` returns on its first
//     line and says nothing, which from the app's side is indistinguishable from working.
//   * D1, the `crashes` table here -- one row per (install, signature), read through
//     `/crashes?k=<STATS_PASSWORD>`. This is the copy that can COUNT: it orders by distinct
//     installs, which GitHub cannot answer.
//
// The full path -- which file writes the stack, which screen offers to send it, how the signature
// is computed, how to test it end to end, and what to do when D1 is read-limited -- is written
// down once, in `docs/CRASH-REPORTS.md` in the mlmvpn_android repository. Start there.
// -------------------------------------------------------------------------------------------------

/** Newest report per install per signature, so a crash loop stores one row, not two hundred. */
const CRASH_MAX_BODY = 20000;

/**
 * How long a signature can go without a comment before the next occurrence adds one.
 *
 * A crash loop is one bug however many times it fires. Without this, forty reports of the same
 * signature in a morning would be forty comments on one issue, which buries the thing the issue
 * exists to say.
 */
const CRASH_COMMENT_MS = 60 * 60 * 1000;

/** The GitHub API wants a User-Agent, and refuses the request without one. */
const GH_UA = 'mlmvpn-pool-worker';

/**
 * Crash reports one install may send in a day -- counted apart from the pool's own calls (see
 * `authenticate`). Generous for a person and their crashes; a ceiling for a crash loop.
 */
const CRASH_PER_DAY = 30;

/**
 * The dedup records of this isolate, in front of KV. KV's free plan allows a thousand writes a
 * day, and once they were gone every report of a known bug found no record, looked its issue up
 * again and commented on it again -- one comment per report instead of one an hour.
 */
const crashRecs = new Map();
const CRASH_RECS_MAX = 5000;

/**
 * How the last GitHub calls went, for `/crashes`. Filing happens after the phone has its answer,
 * so a revoked or expired token (fine-grained tokens expire by design) used to fail where nobody
 * could see it: the app said "sent", D1 had the row, and no issue ever appeared. Kept in memory
 * and written to KV only when the state changes or an hour has passed, to spare KV's writes.
 */
let githubHealth = null;
const GH_HEALTH_KEY = 'crash:github';

async function loadGithubHealth(env) {
  if (githubHealth) return githubHealth;
  const kv = env && env.POOL;
  githubHealth = (kv ? await kv.get(GH_HEALTH_KEY, 'json').catch(() => null) : null) || {};
  return githubHealth;
}

async function noteGithub(env, res, during) {
  const h = await loadGithubHealth(env);
  const now = Date.now();
  const wasFailing = !!(h.last_error && (!h.last_ok_at || h.last_error.at > h.last_ok_at));
  let failing;
  if (res && res.ok) {
    h.last_ok_at = now;
    failing = false;
  } else {
    let message = res ? await res.clone().text().catch(() => '') : 'no response (network error)';
    try { message = JSON.parse(message).message || message; } catch (e) { }
    h.last_error = { at: now, status: res ? res.status : 0, message: String(message).slice(0, 200), during };
    failing = true;
  }
  const kv = env && env.POOL;
  if (kv && (failing !== wasFailing || now - (h.saved_at || 0) > CRASH_COMMENT_MS)) {
    h.saved_at = now;
    await kv.put(GH_HEALTH_KEY, JSON.stringify(h)).catch(() => {});
  }
}

/** The build number in an app string like "1.2.37 (2000069)", or 0. */
function buildOf(app) {
  const m = String(app || '').match(/\((\d+)\)/);
  return m ? Number(m[1]) : 0;
}

async function ghFetch(env, path, init) {
  return fetch('https://api.github.com' + path, {
    ...init,
    headers: {
      'Authorization': 'Bearer ' + env.GH_TOKEN,
      'Accept': 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': GH_UA,
      'Content-Type': 'application/json',
      ...(init && init.headers),
    },
  });
}

/**
 * File a crash as a GitHub issue, one issue per signature.
 *
 * WHY this exists rather than only the D1 table: on 2026-09-06 the account hit D1's daily row-read
 * ceiling and `/crashes` answered with a database error for the rest of the day -- so the reports
 * were unreadable on precisely the day a release went out and they mattered most. A bug tracker
 * that goes down with the thing it is tracking is not a bug tracker. GitHub also gives the parts a
 * dashboard never had: a notification when a NEW bug appears rather than a page someone has to
 * remember to open, a place to write down what was found, and a close button that means something.
 *
 * The D1 row is still written. It costs one indexed insert, it keeps `/crashes` working, and it
 * means a GitHub outage or a bad token loses nothing -- the reads were the expensive half, and
 * this replaces the need to read.
 *
 * The token lives as a Worker secret and never goes anywhere near the app: an APK is readable by
 * anyone who downloads it, so a token shipped inside one is a token given away. The repository it
 * points at MUST be private -- a stack trace names internal classes and the screens the user
 * walked through to get there, and the crash table has always been behind a password for that
 * reason.
 *
 * Deduplication is by signature, held in KV. If KV has no record -- a new namespace, an evicted
 * key -- the label is asked for directly, which is one request and cannot create a second issue
 * for a bug that already has one.
 */
async function fileCrashOnGithub(env, info) {
  if (!env || !env.GH_TOKEN || !env.GH_REPO) return;
  const kv = env.POOL;
  const key = 'crash:' + info.sig;
  const now = Date.now();
  const label = 'sig:' + info.sig;

  const build = buildOf(info.app);

  let rec = crashRecs.get(key) || null;
  if (!rec && kv) rec = await kv.get(key, 'json').catch(() => null);
  // A record from before builds were tracked: unknown is treated as this one, never as older.
  if (rec && rec.build === undefined) rec.build = build;

  if (!rec) {
    // No record here does not mean no issue there.
    const found = await ghFetch(
      env,
      `/repos/${env.GH_REPO}/issues?state=all&per_page=1&labels=${encodeURIComponent(label)}`,
      { method: 'GET' },
    ).catch(() => null);
    await noteGithub(env, found, 'looking up the issue');
    if (found && found.ok) {
      const list = await found.json().catch(() => []);
      if (Array.isArray(list) && list.length > 0) {
        // Which builds it was seen on before is lost with the record; taking this report's as the
        // newest keeps a closed issue closed rather than reopening it on a guess.
        rec = { issue: list[0].number, count: 0, first: now, last: now, commented: 0, build };
      }
    }
  }

  const facts =
    '| | |\n|---|---|\n' +
    `| app | \`${info.app || '?'}\` |\n` +
    `| device | ${info.device || '?'} |\n` +
    `| Android | ${info.android || '?'} |\n` +
    `| signature | \`${info.sig}\` |\n`;

  if (!rec) {
    const title = (info.summary || info.text.split('\n')[0] || 'crash').slice(0, 200);
    const body =
      facts + '\n' +
      'First seen ' + new Date(now).toISOString() + '.\n\n' +
      '<details><summary>stack</summary>\n\n```\n' + info.text + '\n```\n\n</details>\n';
    let res = await ghFetch(env, `/repos/${env.GH_REPO}/issues`, {
      method: 'POST',
      body: JSON.stringify({ title, body, labels: ['crash', label] }),
    }).catch(() => null);
    // A repository with label creation restricted answers 422. The issue matters more than the
    // label; without it, dedup falls back to KV alone, which is where it looks first anyway.
    if (res && res.status === 422) {
      res = await ghFetch(env, `/repos/${env.GH_REPO}/issues`, {
        method: 'POST',
        body: JSON.stringify({ title, body }),
      }).catch(() => null);
    }
    await noteGithub(env, res, 'filing the issue');
    if (!res || !res.ok) return;
    const issue = await res.json().catch(() => null);
    if (!issue || !issue.number) return;
    rec = { issue: issue.number, count: 1, first: now, last: now, commented: now, build };
  } else {
    rec.count = (rec.count || 0) + 1;
    rec.last = now;
    if (now - (rec.commented || 0) > CRASH_COMMENT_MS) {
      // At most once an hour: is the issue still open? A closed one that a NEWER build than any it
      // was reported from still hits is a fix that did not hold, and a comment on a closed issue
      // is one nobody reads -- so it is reopened. An older build hitting it is a user who has not
      // updated yet, and says nothing new.
      const cur = await ghFetch(env, `/repos/${env.GH_REPO}/issues/${rec.issue}`, { method: 'GET' })
        .catch(() => null);
      await noteGithub(env, cur, 'checking the issue');
      const state = cur && cur.ok ? ((await cur.json().catch(() => null)) || {}).state : null;
      if (state === 'closed' && build > (rec.build || 0)) {
        const reopened = await ghFetch(env, `/repos/${env.GH_REPO}/issues/${rec.issue}`, {
          method: 'PATCH',
          body: JSON.stringify({ state: 'open' }),
        }).catch(() => null);
        await noteGithub(env, reopened, 'reopening the issue');
        const ok = await ghFetch(env, `/repos/${env.GH_REPO}/issues/${rec.issue}/comments`, {
          method: 'POST',
          body: JSON.stringify({
            body: `Reopened: seen on \`${info.app}\`, newer than every build it was reported ` +
              `from before it was closed \u2014 ${rec.count} reports so far.\n\n` + facts,
          }),
        }).catch(() => null);
        await noteGithub(env, ok, 'commenting');
        if (ok && ok.ok) rec.commented = now;
      } else if (state === 'closed') {
        rec.commented = now;
      } else {
        const ok = await ghFetch(env, `/repos/${env.GH_REPO}/issues/${rec.issue}/comments`, {
          method: 'POST',
          body: JSON.stringify({
            body: `Seen again \u2014 ${rec.count} reports so far.\n\n` + facts,
          }),
        }).catch(() => null);
        await noteGithub(env, ok, 'commenting');
        if (ok && ok.ok) rec.commented = now;
      }
    }
  }
  if (build > (rec.build || 0)) rec.build = build;

  crashRecs.delete(key);
  crashRecs.set(key, rec);
  if (crashRecs.size > CRASH_RECS_MAX) crashRecs.delete(crashRecs.keys().next().value);
  if (kv) await kv.put(key, JSON.stringify(rec)).catch(() => {});
}

async function handleCrash(db, auth, body, env, ctx) {
  const installId = auth.id;
  let payload;
  try { payload = JSON.parse(body || '{}'); } catch (e) { return jsonResponse({ error: 'bad json' }, 400); }

  const text = String(payload.body || '').slice(0, CRASH_MAX_BODY);
  if (!text) return jsonResponse({ error: 'empty' }, 400);

  const summary = String(payload.summary || '').slice(0, 300);
  // The client sends the signature it computed, but it is recomputed here from the summary so a
  // malformed or hostile client cannot split one bug across many groups and hide it.
  const sig = (await sha256Hex(summary || text.slice(0, 400))).slice(0, 16);

  // One row per (install, signature). A user who hits the same crash forty times in a morning is
  // one data point about one bug, and storing forty copies of it would only make the newest one
  // harder to find.
  const id = (await sha256Hex(installId + '.' + sig)).slice(0, 32);

  // The D1 row, and it is ALLOWED TO FAIL.
  //
  // This used to be a bare `await`, so a database error threw out of the handler, the outer catch
  // answered 500, and `fileCrashOnGithub` below was never reached -- the report was lost. Which
  // defeats the entire reason the GitHub half was written: D1 on the free plan has an account-wide
  // daily row-read ceiling, and on the day it is exhausted every read fails, `/crashes` answers
  // with a D1_ERROR, and reports arrive in exactly the state where the dashboard cannot be read.
  // A bug tracker that goes down with the thing it is tracking is not a bug tracker.
  //
  // So: try to store it, and carry on either way. GitHub is the half that has to survive, because
  // it is the half that notifies.
  //
  // (This does NOT rescue the case where D1 is read-limited: `authenticate` above reads the
  // enrolment row and fails first, before this function is ever entered. Fixing that means either
  // paying for D1 or spending fewer row reads -- it cannot be fixed by dropping the auth, which
  // is what stops anyone with a URL from filing issues in a private repository.)
  // The crash allowance is spent whether or not the row goes in; neither may cost the report.
  if (auth.tick) { try { await auth.tick.run(); } catch (e) { } }

  // Verified without D1 (see `authenticate`): the database is refusing queries, so this is not
  // the moment to try one.
  let stored = !auth.offline;
  if (stored) try {
    await db.prepare(
      `INSERT INTO crashes (id, sig, install, at, app, device, android, summary, body)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET at = excluded.at, app = excluded.app, body = excluded.body`
    ).bind(
      id, sig, installId, Date.now(),
      String(payload.app || '').slice(0, 40),
      String(payload.device || '').slice(0, 80),
      String(payload.android || '').slice(0, 20),
      summary, text,
    ).run();
  } catch (e) {
    stored = false;
  }

  // After the answer. A phone that has just crashed is reporting on its way out, and it must not
  // be made to wait on a round trip to a third party for the acknowledgement.
  const info = {
    sig,
    summary,
    text,
    app: String(payload.app || '').slice(0, 40),
    device: String(payload.device || '').slice(0, 80),
    android: String(payload.android || '').slice(0, 20),
  };
  if (ctx) ctx.waitUntil(fileCrashOnGithub(env, info).catch(() => {}));

  // `stored` is reported so a caller -- and anyone reading a response by hand while testing this
  // end to end -- can tell "the row went in" from "GitHub has it and D1 did not". The app ignores
  // it and treats any 2xx as sent, deliberately: the person who just crashed does not need to be
  // told which of our two stores accepted their report.
  return jsonResponse({ ok: true, sig, stored });
}

/**
 * The crash dashboard: `/crashes` for the groups, `/crashes?sig=...` for one group's newest stack.
 *
 * Grouped by signature and ordered by how many DISTINCT installs hit it, because that is the
 * question worth answering first -- not which crash happened most often, which any single user in
 * a reconnect loop can win.
 */
async function handleCrashes(db, url, env) {
  // Behind the same password as /stats. Stack traces name internal classes and the screens a
  // user walked through before the crash; that is not a public dashboard, and leaving it open
  // would also let anyone measure which of our bugs is biting hardest.
  if (url.searchParams.get('k') !== STATS_PASSWORD) {
    return new Response('unauthorized', { status: 401 });
  }
  // The other half of the pipeline, first, because it is the half that fails silently: whether
  // the GitHub secrets are set, and how the last calls with them went.
  const health = await loadGithubHealth(env);
  const github = {
    configured: !!(env && env.GH_TOKEN && env.GH_REPO),
    repo: (env && env.GH_REPO) || null,
    last_ok_at: health.last_ok_at || null,
    last_error: health.last_error || null,
  };
  const since = Number(url.searchParams.get('days') || 14);
  try {
    const sig = url.searchParams.get('sig');
    if (sig) {
      const row = await db.prepare(
        `SELECT sig, at, app, device, android, summary, body FROM crashes
         WHERE sig = ? ORDER BY at DESC LIMIT 1`
      ).bind(sig).first();
      if (!row) return jsonResponse({ error: 'not found', github }, 404);
      return jsonResponse(row);
    }
    const cutoff = Date.now() - since * 86400000;
    const rows = await db.prepare(
      `SELECT sig,
              COUNT(DISTINCT install) AS users,
              COUNT(*)                AS reports,
              MAX(at)                 AS last_seen,
              MAX(app)                AS app,
              MAX(summary)            AS summary
       FROM crashes WHERE at > ?
       GROUP BY sig ORDER BY users DESC, last_seen DESC LIMIT 100`
    ).bind(cutoff).all();
    return jsonResponse({ days: since, groups: rows.results || [], github });
  } catch (e) {
    // Most often D1's daily read ceiling. Said plainly, with what is known without D1.
    return jsonResponse({ days: since, groups: [], github, d1_error: String(e && e.message ? e.message : e) }, 503);
  }
}

export default {
  async fetch(request, env, ctx) {
    const db = env.DB;
    if (!db) return jsonResponse({ error: 'no database bound' }, 500);

    const url = new URL(request.url);
    try {
      await ensureSchema(db);
    } catch (e) {
      // Outside every route's own handling, this used to be an uncaught exception -- Cloudflare's
      // error page for every request, `/crash` and `/crashes` included -- whenever D1 refused
      // queries. The tables exist on any pool that has ever served; each route now meets D1's
      // state itself, and the next request tries the schema again.
    }

    try {
      if (url.pathname === '/enroll' && request.method === 'POST') {
        // Anyone can enrol; the value of a token is capped by the rate limit and by never
        // serving more than a slice of the pool, not by who is allowed to ask.
        const id = crypto.randomUUID().replace(/-/g, '');
        const secret = crypto.randomUUID().replace(/-/g, '') + crypto.randomUUID().replace(/-/g, '');
        await db.prepare('INSERT INTO installs (id, secret, created, day, calls) VALUES (?, ?, ?, ?, 0)')
          .bind(id, secret, Date.now(), new Date().toISOString().slice(0, 10)).run();
        return jsonResponse({ id, secret });
      }

      if (url.pathname === '/stats') return await handleStats(request, env, db, url);
      // Password-gated like /stats, not install-authenticated: it is opened in a browser by
      // whoever maintains the app, and an install token must not be able to read other people's
      // crashes any more than this password can fetch configs.
      if (url.pathname === '/crashes') return await handleCrashes(db, url, env);

      const body = request.method === 'POST' ? await request.text() : '';
      const auth = await authenticate(request, db, body, url.pathname === '/crash' ? 'crash' : 'pool');
      if (!auth.ok) return jsonResponse({ error: auth.error }, auth.status);

      if (url.pathname === '/list') return await handleList(request, env, ctx, db, auth.tick, auth.id);
      if (url.pathname === '/report' && request.method === 'POST') {
        return await handleReport(request, env, ctx, db, auth.id, body, auth.lastIsp);
      }
      if (url.pathname === '/crash' && request.method === 'POST') {
        return await handleCrash(db, auth, body, env, ctx);
      }
      return jsonResponse({ error: 'not found' }, 404);
    } catch (e) {
      return jsonResponse({ error: String(e && e.message ? e.message : e) }, 500);
    }
  },
};
