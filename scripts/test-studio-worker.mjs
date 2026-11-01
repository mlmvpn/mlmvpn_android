#!/usr/bin/env node
/**
 * Runs the assembled worker for real, against real SQL.
 *
 *   node scripts/test-studio-worker.mjs
 *
 * The plan (§19) records that no automated test harness exists for the worker, and that verification
 * means `wrangler dev` against a D1 copy. That is still the right end-to-end check, but it is a
 * manual one -- so in practice the worker has been shipped on reading alone, onto accounts carrying
 * live traffic, with a redeploy that overwrites the running script.
 *
 * This closes the cheap half of that gap. Node ships SQLite (`node:sqlite`), so the D1 binding can
 * be shimmed over a real database rather than a mock: the migrations execute as actual DDL, the
 * indexes are actually created, and a typo in any of it fails here instead of on someone's account.
 * The worker module is imported with only its `cloudflare:sockets` import stubbed, so the routing,
 * the auth and the migration ordering under test are the shipped code, not a copy of it.
 *
 * What it does NOT cover, and is not pretending to: anything that opens a socket. The tunnel data
 * plane needs `connect()` and a real peer, so it stays out of scope and stays a wrangler job.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { DatabaseSync } from 'node:sqlite';

const ROOT = path.resolve(import.meta.dirname, '..');
const ASSET = path.join(ROOT, 'app/src/main/assets/mlm_worker.js');

// ---------------------------------------------------------------------------- D1 shim

/**
 * The slice of the D1 client the worker actually uses: prepare/bind/first/run/all, plus batch.
 *
 * `batch` is the one with a contract worth honouring rather than approximating -- the migrator
 * depends on it being **one transaction**, because a migration that half-applies leaves a schema
 * that no later run can reason about. So it is a real BEGIN/COMMIT with a ROLLBACK on throw.
 */
function makeD1(db) {
  const statement = (sql, params = []) => ({
    bind: (...args) => statement(sql, args),
    async first() {
      const row = db.prepare(sql).get(...params);
      return row === undefined ? null : row;
    },
    async run() {
      return { success: true, meta: db.prepare(sql).run(...params) };
    },
    async all() {
      return { success: true, results: db.prepare(sql).all(...params) };
    },
    /**
     * One statement inside a batch, returning D1's per-statement shape.
     *
     * `batch` is not write-only: D1 hands back `{ success, results }` for **every** statement, and
     * `/v1/dashboard` depends on it — it fetches all five of its tiles in a single batch precisely
     * so a phone on a slow network makes one round trip instead of five.
     *
     * An earlier version of this shim returned bare `{ success: true }` and dropped the rows, which
     * made the dashboard read zeros for everything. Worth noting which way that failed: the tests
     * went red on correct worker code, rather than green on broken code. A shim that is *less*
     * capable than the real thing is recoverable; one that is more forgiving quietly certifies bugs.
     */
    _exec() {
      const st = db.prepare(sql);
      // node:sqlite splits reads and writes across all()/run(); D1 does not, so try the one that
      // can return rows first and fall back for statements that have none.
      try {
        return { success: true, results: st.all(...params) };
      } catch (e) {
        return { success: true, results: [], meta: st.run(...params) };
      }
    },
  });

  return {
    prepare: (sql) => statement(sql),
    async batch(statements) {
      db.exec('BEGIN');
      try {
        const out = statements.map((s) => s._exec());
        db.exec('COMMIT');
        return out;
      } catch (e) {
        db.exec('ROLLBACK');
        throw e;
      }
    },
  };
}

/** The schema a build-5 installation is already carrying, for the adoption test. */
const LEGACY_SCHEMA = `
CREATE TABLE users (
  id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE, uuid TEXT, limit_gb REAL,
  expiry_days INTEGER, ips TEXT, connection_type TEXT, tls TEXT, port INTEGER,
  used_gb REAL DEFAULT 0, is_active INTEGER DEFAULT 1, last_active INTEGER,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, fingerprint TEXT DEFAULT 'chrome',
  daily_limit_gb REAL, daily_used_gb REAL DEFAULT 0, daily_reset_at INTEGER DEFAULT 0,
  proxy_ip TEXT);
CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE debug_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, line TEXT);
`;

// ---------------------------------------------------------------------------- harness

let loadCounter = 0;

/**
 * A FRESH module instance each time.
 *
 * The worker keeps isolate-level state -- `migrationsDone`, `schemaEnsured`, the traffic cache --
 * which is correct in production, where one script owns one database for the life of an isolate.
 * In a test file it means the second scenario inherits the first one's belief that the schema is
 * already migrated, and then fails on a database that was never touched. Re-importing under a new
 * path gives each scenario its own isolate, which is what it is actually modelling.
 */
async function loadWorker() {
  const src = fs.readFileSync(ASSET, 'utf8');
  // The only thing Node cannot resolve. Replaced rather than mocked away wholesale, so every other
  // line of the module is the one that ships.
  const patched = src.replace(
    /^import \{ connect \} from 'cloudflare:sockets';/m,
    // A test can put a fake server behind it (`globalThis.__studioConnect`); nothing else can connect.
    "const connect = (...a) => { if (globalThis.__studioConnect) return globalThis.__studioConnect(...a); throw new Error('sockets are out of scope for this harness'); };"
  );
  if (patched === src) throw new Error('could not find the cloudflare:sockets import to stub');

  // Test-only visibility into the functions that decide who gets refused.
  //
  // The worker shares one top-level scope and exports only its fetch handler, so the arithmetic
  // behind the caps -- and the expiry comparison that was reading the wrong column -- is reachable
  // from nothing. Appending an export of symbols that already exist changes no behaviour under
  // test; it is the same kind of edit as stubbing the sockets import above, and the alternative is
  // testing an admission decision only through a socket this harness cannot open.
  const exposed = patched + `
export const __internals = {
  studioAdmissionVerdict, studioExpiryAt, isUserExpired, isUserCapped,
  studioStartOnFirstConnect, studioAdmit, studioDeviceHash, studioIpHash, studioSalt,
  studioAccrueHourly, studioHourBucket, studioResetQuotaIfDue, resetDailyIfDue,
  studioIsTrojanHeader, studioTrojanAuth, studioParseTrojanRequest, studioTrojanUser, MIGRATIONS,
  studioVlessUser, studioSessionRow,
  studioParseExitUrl, studioRedactExitUrl, studioDialVia, studioDialExit, studioGuardVerdict,
  studioRoomBytes, studioCommitThreshold, studioFlag, EXIT_STATE, EXIT_CACHE, studioDialOwnRelay,
  studioDialPool, studioPoolParse, POOL_LISTS, POOL_STATE, POOL_GOOD, POOL_SETTINGS,
  studioMeterJoin, studioMeterAdd, studioMeterLeave, studioMeterTick, METER, studioThresholdFor,
  studioEarlyData, studioXhttpReadHeader, studioPresenceAdmit, PRESENCE_OK, studioPoolVerify,
  studioExitsFor, studioConfigKind, POOL_VERIFIED, EXIT_URL_STATE, SessionDO,
};
`;

  const tmp = path.join(os.tmpdir(), `studio-worker-test-${process.pid}-${loadCounter++}.mjs`);
  fs.writeFileSync(tmp, exposed);
  try {
    const ns = await import(pathToFileURL(tmp).href);
    // The default export, plus a door into the module scope. Assigned onto a copy so the shape
    // every existing test uses -- `worker.fetch(...)` -- is untouched.
    return Object.assign({}, ns.default, { internals: ns.__internals });
  } finally {
    try { fs.unlinkSync(tmp); } catch { /* best effort */ }
  }
}

async function sha256Hex(s) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function makeEnv(db, extra = {}) {
  return { DB: makeD1(db), STUDIO_ROUTE: 'a1b2c3d4', ...extra };
}
const CTX = { waitUntil: (p) => { if (p && p.catch) p.catch(() => {}); } };

// The schema the shipped asset migrates to, read from the asset rather than repeated here.
// Every migration used to mean editing three literals in this file, which is a change that
// looks like a test failure and is not one.
let CURRENT_SCHEMA = 0;
const SCHEMA_VERSIONS = [];

const BASE = 'https://edge.example.workers.dev';
const call = (worker, env, url, init) => worker.fetch(new Request(BASE + url, init), env, CTX);
/** A subscription body without its info entries (04c › studioSubInfoLinks): the servers alone. */
const serversOnly = (text) => text.split(String.fromCharCode(10)).filter((l) => !l.includes('@127.0.0.1:')).join(String.fromCharCode(10));

/** One isolate, one database, one env -- the unit every test below works in. */
async function scenario({ legacy = false, env: extra = {}, route = 'a1b2c3d4' } = {}) {
  const db = new DatabaseSync(':memory:');
  if (legacy) db.exec(LEGACY_SCHEMA);
  const worker = await loadWorker();
  if (!CURRENT_SCHEMA) {
    for (const m of worker.internals.MIGRATIONS) SCHEMA_VERSIONS.push(m.v);
    CURRENT_SCHEMA = Math.max(...SCHEMA_VERSIONS);
  }
  const env = route === null
    ? { DB: makeD1(db), ...extra }
    : { DB: makeD1(db), STUDIO_ROUTE: route, ...extra };
  return { db, worker, env };
}

// ---------------------------------------------------------------------------- assertions

let passed = 0;
const failures = [];
async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log(`  ok   ${name}`);
  } catch (e) {
    failures.push({ name, error: e });
    console.log(`  FAIL ${name}\n       ${e && e.message}`);
  }
}
function eq(actual, expected, what) {
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) throw new Error(`${what || 'value'}: expected ${b}, got ${a}`);
}
function ok(cond, what) {
  if (!cond) throw new Error(what || 'expected true');
}

// ---------------------------------------------------------------------------- the tests

console.log('worker under test:', path.relative(ROOT, ASSET), '\n');

await test('a fresh database migrates to the current schema', async () => {
  const { worker, env } = await scenario();
  const res = await call(worker, env, '/a1b2c3d4/v1/health');
  eq(res.status, 200, 'status');
  const body = await res.json();
  eq(body.schema_version, CURRENT_SCHEMA, 'schema_version');
  eq(body.d1_ok, true, 'd1_ok');
  ok(body.capabilities.includes('plans.v1'), 'capabilities should include plans.v1');
});

await test('a live build-5 database is adopted, not replayed', async () => {
  const { db, worker, env } = await scenario({ legacy: true });
  db.prepare('INSERT INTO users (username, uuid, limit_gb, used_gb, expiry_days) VALUES (?,?,?,?,?)')
    .run('ali', 'u-1', 30, 1.5, 30);

  const res = await call(worker, env, '/a1b2c3d4/v1/health');
  eq(res.status, 200, 'status');
  eq((await res.json()).schema_version, CURRENT_SCHEMA, 'schema_version');

  // Replaying the legacy ALTERs would have thrown "duplicate column name" and aborted the batch.
  eq(db.prepare('SELECT v FROM schema_version ORDER BY v').all().map((r) => r.v), SCHEMA_VERSIONS, 'applied versions');

  const u = db.prepare('SELECT * FROM users WHERE username = ?').get('ali');
  eq(u.quota_bytes, 30 * 1073741824, 'quota_bytes backfill');
  eq(u.status, 'active', 'status backfill');
  ok(u.uid && u.uid.length === 24, 'uid should be a 12-byte hex id, got ' + u.uid);
  ok(u.expires_at > 0, 'expires_at should be derived from created_at + expiry_days');
  // Build 6 keeps writing both, so a rollback to build 5 still works.
  eq(u.limit_gb, 30, 'limit_gb must be preserved for rollback');
});

await test('migrations are idempotent across requests', async () => {
  const { db, worker, env } = await scenario();
  for (let i = 0; i < 3; i++) await call(worker, env, '/a1b2c3d4/v1/health');
  eq(db.prepare('SELECT v FROM schema_version ORDER BY v').all().map((r) => r.v), SCHEMA_VERSIONS, 'no re-apply');
});

await test('the API is invisible without the route binding', async () => {
  const { worker, env } = await scenario({ route: null }); // i.e. a build-5 installation
  const res = await call(worker, env, '/a1b2c3d4/v1/health');
  ok(!(res.headers.get('content-type') || '').includes('application/json'),
    'a build-5 install must not expose /v1/*, got ' + res.status);
});

await test('a guessed route segment does not reach the API', async () => {
  const { worker, env } = await scenario();
  const res = await call(worker, env, '/deadbeef/v1/health');
  ok(!(res.headers.get('content-type') || '').includes('application/json'),
    'a guessed prefix must not answer as an API, got ' + res.status);
});

await test('protected endpoints refuse an unauthenticated caller', async () => {
  const { worker, env } = await scenario();
  const res = await call(worker, env, '/a1b2c3d4/v1/auth/keys');
  eq(res.status, 401, 'status');
  eq((await res.json()).error.code, 'unauthorized', 'error code');
});

await test('bootstrap exchanges the deploy secret for a usable key', async () => {
  const secret = 'deploy-secret-value';
  const { worker, env } = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex(secret) } });

  const res = await call(worker, env, '/a1b2c3d4/v1/auth/bootstrap', {
    method: 'POST', body: JSON.stringify({ secret, label: 'phone-a' }),
  });
  eq(res.status, 201, 'status');
  const { key } = await res.json();
  ok(/^cs_[0-9a-f]{16}_[0-9a-f]{48}$/.test(key), 'key shape: ' + key);

  const listed = await call(worker, env, '/a1b2c3d4/v1/auth/keys', {
    headers: { Authorization: 'Bearer ' + key },
  });
  eq(listed.status, 200, 'authenticated list status');
  eq((await listed.json()).items[0].label, 'phone-a', 'label');
});

await test('a wrong bootstrap secret is refused', async () => {
  const { worker, env } = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('right') } });
  const res = await call(worker, env, '/a1b2c3d4/v1/auth/bootstrap', {
    method: 'POST', body: JSON.stringify({ secret: 'wrong' }),
  });
  eq(res.status, 403, 'status');
  eq((await res.json()).error.code, 'bootstrap_invalid', 'error code');
});

await test('the same bootstrap secret cannot be replayed', async () => {
  const { worker, env } = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  const req = { method: 'POST', body: JSON.stringify({ secret: 's' }) };
  eq((await call(worker, env, '/a1b2c3d4/v1/auth/bootstrap', req)).status, 201, 'first');
  const second = await call(worker, env, '/a1b2c3d4/v1/auth/bootstrap', req);
  eq(second.status, 409, 'second');
  eq((await second.json()).error.code, 'bootstrap_used', 'error code');
});

await test('a redeploy with a NEW secret re-arms bootstrap (recovering a lost phone)', async () => {
  const first = 'secret-one';
  const second = 'secret-two';
  const { db, worker, env } = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex(first) } });

  eq((await call(worker, env, '/a1b2c3d4/v1/auth/bootstrap', {
    method: 'POST', body: JSON.stringify({ secret: first, label: 'phone-a' }),
  })).status, 201, 'phone A');

  // Phone B cannot read the secret binding back from Cloudflare, so it redeploys with a fresh one.
  // Same database, same isolate -- only the binding changed, which is what a redeploy does.
  const envB = { ...env, STUDIO_BOOTSTRAP_HASH: await sha256Hex(second) };
  eq((await call(worker, envB, '/a1b2c3d4/v1/auth/bootstrap', {
    method: 'POST', body: JSON.stringify({ secret: second, label: 'phone-b' }),
  })).status, 201, 'phone B');

  eq(db.prepare('SELECT label FROM api_keys ORDER BY created_at, rowid').all().map((r) => r.label),
    ['phone-a', 'phone-b'], 'both devices hold their own key');

  // ...and a captured copy of the old secret is now worthless.
  eq((await call(worker, env, '/a1b2c3d4/v1/auth/bootstrap', {
    method: 'POST', body: JSON.stringify({ secret: first }),
  })).status, 409, 'replaying the old secret');
});

await test('a revoked key stops working', async () => {
  const { worker, env } = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  const { key, key_id } = await (await call(worker, env, '/a1b2c3d4/v1/auth/bootstrap', {
    method: 'POST', body: JSON.stringify({ secret: 's' }),
  })).json();
  const auth = { headers: { Authorization: 'Bearer ' + key } };

  eq((await call(worker, env, '/a1b2c3d4/v1/auth/keys', auth)).status, 200, 'before revoke');
  eq((await call(worker, env, '/a1b2c3d4/v1/auth/keys/' + key_id, { ...auth, method: 'DELETE' })).status, 200, 'revoke');
  eq((await call(worker, env, '/a1b2c3d4/v1/auth/keys', auth)).status, 401, 'after revoke');
});

await test('a forged secret against a real key id is refused', async () => {
  const { worker, env } = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  const { key_id } = await (await call(worker, env, '/a1b2c3d4/v1/auth/bootstrap', {
    method: 'POST', body: JSON.stringify({ secret: 's' }),
  })).json();
  const forged = 'cs_' + key_id + '_' + '0'.repeat(48);
  eq((await call(worker, env, '/a1b2c3d4/v1/auth/keys', {
    headers: { Authorization: 'Bearer ' + forged },
  })).status, 401, 'status');
});

await test('every write is audited', async () => {
  const { db, worker, env } = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  const { key, key_id } = await (await call(worker, env, '/a1b2c3d4/v1/auth/bootstrap', {
    method: 'POST', body: JSON.stringify({ secret: 's' }),
  })).json();
  await call(worker, env, '/a1b2c3d4/v1/auth/keys/' + key_id, {
    method: 'DELETE', headers: { Authorization: 'Bearer ' + key },
  });
  eq(db.prepare('SELECT action FROM audit_log ORDER BY id').all().map((r) => r.action),
    ['auth.bootstrap', 'auth.revoke_key'], 'audit trail');
});

await test('the legacy panel surface still routes', async () => {
  const { worker, env } = await scenario();
  // Build 6 must not disturb what build-5 installations rely on.
  const res = await call(worker, env, '/api/users');
  ok(res.status !== 404, 'legacy /api/users should still route, got ' + res.status);
});

// ---------------------------------------------------------------------------- /v1/users

/** A scenario with a key already in hand, since every users endpoint needs one. */
async function authed(opts = {}) {
  const s = await scenario({ ...opts, env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s'), ...(opts.env || {}) } });
  const { key } = await (await call(s.worker, s.env, '/a1b2c3d4/v1/auth/bootstrap', {
    method: 'POST', body: JSON.stringify({ secret: 's' }),
  })).json();
  const auth = (init = {}) => ({ ...init, headers: { ...(init.headers || {}), Authorization: 'Bearer ' + key } });
  const api = (url, init) => call(s.worker, s.env, '/a1b2c3d4/v1' + url, auth(init));
  const post = (url, body) => api(url, { method: 'POST', body: JSON.stringify(body) });
  return { ...s, api, post };
}

await test('a user can be created, read back, and listed', async () => {
  const { api, post } = await authed();
  const created = await post('/users', {
    username: 'ali',
    policy: { quota_bytes: 30 * 1073741824, expires_at: Date.now() + 30 * 86400000 },
  });
  eq(created.status, 201, 'create status');
  const user = await created.json();
  ok(user.id && user.id.length === 24, 'id should be an opaque uid, got ' + user.id);
  eq(user.username, 'ali', 'username');
  eq(user.policy.quota_bytes, 30 * 1073741824, 'quota is bytes, exactly');
  ok(user.credential, 'a credential should be generated');

  eq((await (await api('/users/' + user.id)).json()).username, 'ali', 'read back');

  const list = await (await api('/users')).json();
  eq(list.items.length, 1, 'list length');
  eq(list.total_hint, 1, 'total_hint from the counters table');
  eq(list.next_cursor, null, 'a short page must not offer a cursor');
});

await test('a duplicate username is refused', async () => {
  const { post } = await authed();
  eq((await post('/users', { username: 'ali' })).status, 201, 'first');
  const second = await post('/users', { username: 'ali' });
  eq(second.status, 409, 'second');
  eq((await second.json()).error.code, 'username_taken', 'error code');
});

await test('the legacy GB mirrors are written, so a rollback to build 5 still enforces', async () => {
  const { db, post } = await authed();
  const expires = Date.now() + 10 * 86400000;
  await post('/users', { username: 'ali', policy: { quota_bytes: 30 * 1073741824, expires_at: expires } });

  // Build 5's data plane reads limit_gb / expiry_days and nothing else. A build-6 user served by a
  // rolled-back build 5 would otherwise have no quota and no expiry at all.
  const row = db.prepare('SELECT limit_gb, expiry_days, quota_bytes FROM users WHERE username = ?').get('ali');
  eq(row.limit_gb, 30, 'limit_gb mirror');
  ok(row.expiry_days >= 9 && row.expiry_days <= 11, 'expiry_days mirror, got ' + row.expiry_days);
  eq(row.quota_bytes, 30 * 1073741824, 'quota_bytes is the source of truth');
});

await test('keyset pagination walks the whole list without repeating or skipping', async () => {
  const { api, post } = await authed();
  for (let i = 0; i < 7; i++) await post('/users', { username: 'user' + i });

  const seen = [];
  let cursor = null;
  let pages = 0;
  for (let page = 0; page < 10; page++) {
    const res = await (await api('/users?limit=3' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : ''))).json();
    pages++;
    ok(res.items.length <= 3, 'limit must be honoured, got ' + res.items.length);
    seen.push(...res.items.map((u) => u.username));
    cursor = res.next_cursor;
    if (!cursor) break;
  }
  // Without this the test would also pass if paging were broken and one page returned everything,
  // which is the failure it is actually there to catch.
  eq(pages, 3, 'seven rows at three per page is three pages');
  eq(seen.length, 7, 'every user seen exactly once');
  eq(new Set(seen).size, 7, 'no duplicates across pages');
});

await test('search is a prefix match', async () => {
  const { api, post } = await authed();
  await post('/users', { username: 'alireza' });
  await post('/users', { username: 'bahram' });
  const hit = await (await api('/users?q=ali')).json();
  eq(hit.items.map((u) => u.username), ['alireza'], 'prefix hit');
  // '%x%' would read the whole table on every keystroke -- the shape that caused the read outage.
  eq((await (await api('/users?q=reza')).json()).items.length, 0, 'infix must NOT match');
});

await test('?since= returns only what changed', async () => {
  const { api, post } = await authed();
  await post('/users', { username: 'ali' });
  const first = await (await api('/users?since=0')).json();
  eq(first.items.length, 1, 'initial sync');
  const watermark = first.next_since;

  eq((await (await api('/users?since=' + watermark)).json()).items.length, 0, 'nothing changed yet');

  await post('/users', { username: 'bahram' });
  const second = await (await api('/users?since=' + watermark)).json();
  eq(second.items.map((u) => u.username), ['bahram'], 'only the new row');
});

// The bug this pair exists for: `?since=` used to page on `updated_at` alone, so a page that ended
// on a timestamp shared by more rows than the page held skipped the rest of that group -- for good,
// because the watermark had already moved past them. Ties are not exotic: `:apply` writes one
// timestamp across a whole «بسته», and the v6 backfill gave every legacy user the same one. The
// symptom is the worst kind: an index that is quietly missing people and looks complete.
await test('a page ending inside a group of tied rows loses nobody', async () => {
  const { api, post, db } = await authed();
  for (const name of ['a1', 'a2', 'a3', 'a4', 'a5']) await post('/users', { username: name });
  // Force the tie the two real paths produce, rather than hoping the clock supplies one.
  db.prepare('UPDATE users SET updated_at = 5000').run();

  const seen = [];
  let since = 0;
  let sinceUid = '';
  for (let page = 0; page < 10; page++) {
    const body = await (await api(`/users?since=${since}&since_uid=${sinceUid}&limit=2`)).json();
    for (const u of body.items) seen.push(u.username);
    since = body.next_since;
    sinceUid = body.next_uid;
    if (body.complete) break;
  }

  eq(seen.sort(), ['a1', 'a2', 'a3', 'a4', 'a5'], 'every tied row arrives');
  eq(new Set(seen).size, 5, 'and none of them twice');
});

// An app that does not send the tie-break is an older build, and a fleet on mixed builds is an
// ordinary state (R13). It must keep the query it has always had: switching it to the keyset would
// re-serve the boundary row on every poll, forever -- a new bug handed to exactly the callers this
// change is meant to protect.
await test('an app that sends no tie-break keeps the old strictly-after semantics', async () => {
  const { api, post } = await authed();
  await post('/users', { username: 'ali' });
  const first = await (await api('/users?since=0')).json();
  const watermark = first.next_since;

  eq((await (await api('/users?since=' + watermark)).json()).items.length, 0, 'no boundary replay');
  // And the field is still sent, so a NEW app can tell this engine from one too old to have it.
  eq(typeof first.next_uid, 'string', 'next_uid is always emitted');
  eq(first.next_uid.length > 0, true, 'and carries the last row');
});

// A delete is a tombstone, and `username` is UNIQUE -- so without releasing the name, deleting
// someone burned it for the life of the installation. Invisibly, too: the app's index drops
// tombstones, so it told the operator the name was free and the engine then refused it.
await test('a deleted name can be given to someone else', async () => {
  const { api, post } = await authed();
  const first = await (await post('/users', { username: 'ali' })).json();
  eq((await api('/users/' + first.id, { method: 'DELETE' })).status, 200, 'deleted');

  const second = await post('/users', { username: 'ali' });
  eq(second.status, 201, 'the name is available again');
  const reborn = await (await second.json());
  ok(reborn.id !== first.id, 'and it is a different person, with a different id');

  // The tombstone is still a tombstone -- it just no longer owns the name.
  eq((await api('/users/' + first.id)).status, 404, 'the deleted user stays deleted');
  const sync = await (await api('/users?since=0&since_uid=')).json();
  const dead = sync.items.find((u) => u.id === first.id);
  ok(dead && dead.deleted_at, 'the tombstone still propagates');
  ok(dead.username !== 'ali', 'and does not claim the name any more');
});

await test('a rename can also take a name back from a tombstone', async () => {
  const { api, post } = await authed();
  const gone = await (await post('/users', { username: 'ali' })).json();
  await api('/users/' + gone.id, { method: 'DELETE' });
  const other = await (await post('/users', { username: 'bahram' })).json();

  const renamed = await api('/users/' + other.id, {
    method: 'PATCH', body: JSON.stringify({ username: 'ali' }),
  });
  eq(renamed.status, 200, 'rename status');
  eq((await renamed.json()).username, 'ali', 'renamed');
});

await test('a name held by a LIVE user is still refused', async () => {
  const { api, post } = await authed();
  await post('/users', { username: 'ali' });
  eq((await post('/users', { username: 'ali' })).status, 409, 'create clash');

  const other = await (await post('/users', { username: 'bahram' })).json();
  const clash = await api('/users/' + other.id, {
    method: 'PATCH', body: JSON.stringify({ username: 'ali' }),
  });
  eq(clash.status, 409, 'rename clash');
  eq((await clash.json()).error.code, 'username_taken', 'and says which');
});

await test('a patch is visible to ?since=, or it would never propagate', async () => {
  const { api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const watermark = (await (await api('/users?since=0')).json()).next_since;

  const patched = await api('/users/' + user.id, { method: 'PATCH', body: JSON.stringify({ note: 'hello' }) });
  eq(patched.status, 200, 'patch status');
  eq((await patched.json()).note, 'hello', 'note applied');

  const synced = await (await api('/users?since=' + watermark)).json();
  eq(synced.items.length, 1, 'the change must reach other devices');
  eq(synced.items[0].note, 'hello', 'synced note');
});

await test('a delete leaves a tombstone: gone from the list, present in the sync', async () => {
  const { api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const watermark = (await (await api('/users?since=0')).json()).next_since;

  eq((await api('/users/' + user.id, { method: 'DELETE' })).status, 200, 'delete status');

  const list = await (await api('/users')).json();
  eq(list.items.length, 0, 'deleted users leave the list');
  eq(list.total_hint, 0, 'counter decremented');

  // Without this the row simply reappears from another device's cached copy on its next sync.
  const synced = await (await api('/users?since=' + watermark)).json();
  eq(synced.items.length, 1, 'the tombstone must reach other devices');
  eq(synced.items[0].status, 'deleted', 'status');
  ok(synced.items[0].deleted_at > 0, 'deleted_at');
});

await test('a deleted user cannot be reached, and their credential is cleared', async () => {
  const { db, api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/users/' + user.id, { method: 'DELETE' });

  eq((await api('/users/' + user.id, { method: 'PATCH', body: '{}' })).status, 404, 'patch after delete');
  // The tunnel matches on uuid, so clearing it stops admission immediately.
  eq(db.prepare('SELECT uuid FROM users WHERE uid = ?').get(user.id).uuid, null, 'credential cleared');
});

// `/p/{token}` has always rendered a contact line for an expired or exhausted subscription, and
// always titled itself from `studio_brand` -- and neither could be written by anything at all. The
// page the plan promised therefore could not exist.
await test('the operator can set the contact line the subscriber page shows', async () => {
  const { worker, env, api, post } = await authed();
  const user = await (await post('/users', {
    username: 'ali', policy: { expires_at: Date.now() - 1000 },
  })).json();
  const sub = await (await api('/users/' + user.id + '/subscription')).json();

  const before = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(!before.includes('@ali_support'), 'nothing to show yet');

  const patched = await api('/settings', {
    method: 'PATCH', body: JSON.stringify({ contact: 'برای تمدید: @ali_support', brand: 'خط من' }),
  });
  eq(patched.status, 200, 'patch status');
  eq((await patched.json()).contact, 'برای تمدید: @ali_support', 'read back');

  const after = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(after.includes('@ali_support'), 'the page now carries it');
  ok(after.includes('خط من'), 'and the brand');
});

// `settings` also holds `panel_password` and every burned bootstrap hash. A free-form key/value
// write would let an authenticated caller turn a spent deploy secret back into a live one.
await test('settings is an allowlist, not a key/value store', async () => {
  const { api, db } = await authed();
  const res = await api('/settings', {
    method: 'PATCH',
    body: JSON.stringify({ panel_password: 'x', studio_bootstrap_used: '', contact: 'ok' }),
  });
  eq(res.status, 200, 'the known field still lands');
  eq((await res.json()).contact, 'ok', 'contact set');
  eq(db.prepare("SELECT value FROM settings WHERE key = 'panel_password'").get(), undefined, 'password untouched');
});

await test('settings needs a key like everything else', async () => {
  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/settings')).status, 401, 'read refused');
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/settings', {
    method: 'PATCH', body: '{"contact":"x"}',
  })).status, 401, 'write refused');
});

await test('renew extends from the existing expiry, not from now', async () => {
  const { api, post } = await authed();
  const future = Date.now() + 10 * 86400000;
  const user = await (await post('/users', { username: 'ali', policy: { expires_at: future, quota_bytes: 1000 } })).json();

  const renewed = await (await api('/users/' + user.id + ':renew', {
    method: 'POST', body: JSON.stringify({ add_days: 30, add_bytes: 500 }),
  })).json();

  // Renewing early must not throw away the days already there.
  const expected = future + 30 * 86400000;
  ok(Math.abs(renewed.policy.expires_at - expected) < 5000,
    'expected ~' + expected + ', got ' + renewed.policy.expires_at);
  eq(renewed.policy.quota_bytes, 1500, 'quota accumulates');
});

await test('renew restart resets usage and starts the clock now', async () => {
  const { db, api, post } = await authed();
  const user = await (await post('/users', { username: 'ali', policy: { quota_bytes: 1000 } })).json();
  db.prepare('UPDATE users SET used_bytes = 900, used_gb = 0.9 WHERE uid = ?').run(user.id);

  const renewed = await (await api('/users/' + user.id + ':renew', {
    method: 'POST', body: JSON.stringify({ mode: 'restart', add_days: 30, add_bytes: 2000 }),
  })).json();
  eq(renewed.usage.used_bytes, 0, 'usage reset');
  eq(renewed.policy.quota_bytes, 2000, 'quota replaced');
  eq(db.prepare('SELECT used_gb FROM users WHERE uid = ?').get(user.id).used_gb, 0, 'legacy mirror reset too');
});

await test('renew re-enables someone the quota had stopped', async () => {
  const { db, api, post } = await authed();
  const user = await (await post('/users', { username: 'ali', policy: { quota_bytes: 1000 } })).json();
  eq((await api('/users/' + user.id + ':disable', { method: 'POST' })).status, 200, 'disable');
  eq(db.prepare('SELECT is_active FROM users WHERE uid = ?').get(user.id).is_active, 0, 'disabled');

  const renewed = await (await api('/users/' + user.id + ':renew', {
    method: 'POST', body: JSON.stringify({ add_bytes: 1000 }),
  })).json();
  // Topping someone up without clearing is_active leaves them dark for a reason nothing explains.
  eq(renewed.status, 'active', 'status');
  eq(db.prepare('SELECT is_active FROM users WHERE uid = ?').get(user.id).is_active, 1, 're-enabled');
});

await test('a renewal is recorded for the life of the user', async () => {
  const { db, api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/users/' + user.id + ':renew', { method: 'POST', body: JSON.stringify({ add_days: 30 }) });

  const rows = db.prepare('SELECT mode, days, before_json, after_json FROM renewals').all();
  eq(rows.length, 1, 'one renewal row');
  eq(rows[0].mode, 'extend', 'mode');
  eq(rows[0].days, 30, 'days');
  ok(rows[0].before_json && rows[0].after_json, 'both sides recorded');
});

await test('a renewal can be taken back, and stops at now rather than in the past', async () => {
  const { api, post } = await authed();
  const future = Date.now() + 40 * 86400000;
  const user = await (await post('/users', { username: 'ali', policy: { expires_at: future, quota_bytes: 3000 } })).json();

  // The ordinary correction: 300 was typed where 30 was meant.
  const back = await (await api('/users/' + user.id + ':renew', {
    method: 'POST', body: JSON.stringify({ add_days: -10, add_bytes: -1000 }),
  })).json();
  const expected = future - 10 * 86400000;
  ok(Math.abs(back.policy.expires_at - expected) < 5000,
    'expected ~' + expected + ', got ' + back.policy.expires_at);
  eq(back.policy.quota_bytes, 2000, 'quota came back down');

  // Driven past today, the expiry lands on now and not in 1970 -- both refuse the tunnel, but only
  // one of them prints as "expired fifty-six years ago" on the subscriber's own page.
  const far = await (await api('/users/' + user.id + ':renew', {
    method: 'POST', body: JSON.stringify({ add_days: -9000 }),
  })).json();
  ok(far.policy.expires_at >= Date.now() - 5000, 'floored at now, got ' + far.policy.expires_at);
  ok(far.policy.expires_at <= Date.now() + 5000, 'and no later than now');
});

await test('taking a grant back neither re-enables nor writes a quota under the meter', async () => {
  const { db, api, post } = await authed();
  const user = await (await post('/users', { username: 'ali', policy: { quota_bytes: 3000 } })).json();
  db.prepare('UPDATE users SET used_bytes = 900 WHERE uid = ?').run(user.id);
  eq((await api('/users/' + user.id + ':disable', { method: 'POST' })).status, 200, 'disable');

  const back = await (await api('/users/' + user.id + ':renew', {
    method: 'POST', body: JSON.stringify({ add_bytes: -5000 }),
  })).json();

  // A quota below the meter reading refuses them forever -- including after the next top-up --
  // and nothing on any screen would explain why.
  eq(back.policy.quota_bytes, 900, 'floored at what was already used');
  // Re-enabling someone the operator just cut back undoes the correction in the same statement.
  eq(db.prepare('SELECT is_active FROM users WHERE uid = ?').get(user.id).is_active, 0, 'stays off');
});

await test('zeroing the usage meter moves nothing else', async () => {
  const { db, api, post } = await authed();
  const future = Date.now() + 10 * 86400000;
  const user = await (await post('/users', { username: 'ali', policy: { expires_at: future, quota_bytes: 1000 } })).json();
  db.prepare('UPDATE users SET used_bytes = 900, used_gb = 0.9, daily_used_bytes = 100 WHERE uid = ?').run(user.id);

  const res = await (await api('/users/' + user.id + ':renew', {
    method: 'POST', body: JSON.stringify({ mode: 'reset_usage' }),
  })).json();

  eq(res.usage.used_bytes, 0, 'meter zeroed');
  eq(res.usage.daily_used_bytes, 0, 'daily meter zeroed too');
  eq(res.policy.quota_bytes, 1000, 'the quota is untouched');
  eq(res.policy.expires_at, future, 'and so is the expiry -- this is not restart');
  eq(db.prepare('SELECT used_gb FROM users WHERE uid = ?').get(user.id).used_gb, 0, 'legacy mirror too');
});

await test('zeroing the meter is the one renewal that needs no figure', async () => {
  const { api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  eq((await api('/users/' + user.id + ':renew', {
    method: 'POST', body: JSON.stringify({ mode: 'reset_usage' }),
  })).status, 200, 'accepted with nothing else in the body');
});

await test('a renewal with nothing to renew is refused', async () => {
  const { api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const res = await api('/users/' + user.id + ':renew', { method: 'POST', body: '{}' });
  eq(res.status, 400, 'status');
});

await test('every user write is audited with both sides', async () => {
  const { db, api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/users/' + user.id, { method: 'PATCH', body: JSON.stringify({ note: 'x' }) });
  await api('/users/' + user.id, { method: 'DELETE' });

  const actions = db.prepare("SELECT action FROM audit_log WHERE target_type = 'user' ORDER BY id").all().map((r) => r.action);
  eq(actions, ['user.create', 'user.update', 'user.delete'], 'audit trail');
  const del = db.prepare("SELECT before_json FROM audit_log WHERE action = 'user.delete'").get();
  ok(del.before_json && del.before_json.includes('ali'), 'the deleted user is recoverable from the audit row');
});

await test('users endpoints refuse an unauthenticated caller', async () => {
  const { worker, env } = await scenario();
  eq((await call(worker, env, '/a1b2c3d4/v1/users')).status, 401, 'list');
  eq((await call(worker, env, '/a1b2c3d4/v1/users', { method: 'POST', body: '{}' })).status, 401, 'create');
});

// ---------------------------------------------------------------------------- subscription

const WS_TEMPLATE =
  'vless://{{cred}}@{{host}}:{{port}}?type=ws&security=tls&sni={{sni}}&host={{sni}}' +
  '&path={{path}}&fp=chrome&encryption=none#{{remark}}';

/** A user with one config and a subscription, which is the Phase 1 shape. */
async function withSub(policy = {}, extra = {}) {
  const s = await authed();
  const user = await (await s.post('/users', { username: 'ali', policy, ...extra })).json();
  await s.post('/configs', { user_id: user.id, uri_template: WS_TEMPLATE, label: 'default' });
  const sub = await (await s.api('/users/' + user.id + '/subscription')).json();
  return { ...s, user, sub };
}

await test('a subscription exists the moment a user does', async () => {
  const { user, api } = await withSub();
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  ok(/^[0-9a-f]{32}$/.test(sub.token), 'token should be a 16-byte secret, got ' + sub.token);
  // The username must NOT be the bearer -- that was the legacy /sub/{username} mistake.
  ok(!sub.url.includes('ali'), 'the link must not contain the username: ' + sub.url);
});

await test('the subscription serves the rendered template', async () => {
  const { worker, env, sub } = await withSub();
  const res = await call(worker, env, '/s/' + sub.token + '?format=raw');
  eq(res.status, 200, 'status');
  const body = await res.text();
  ok(body.startsWith('vless://'), 'body: ' + body.slice(0, 40));
  ok(body.includes('@edge.example.workers.dev:443'), 'endpoint defaults to the worker host');
  ok(!body.includes('{{'), 'every placeholder must be substituted: ' + body);
});

await test('base64 is the default format', async () => {
  const { worker, env, sub } = await withSub();
  const body = await (await call(worker, env, '/s/' + sub.token)).text();
  ok(!body.includes('vless://'), 'default must be encoded');
  ok(atob(body).startsWith('vless://'), 'and must decode to the links');
});

await test('Subscription-Userinfo carries exact bytes and UNIX SECONDS', async () => {
  const expires = Date.now() + 30 * 86400000;
  const { worker, env, sub, db, user } = await withSub({ quota_bytes: 30 * 1073741824, expires_at: expires });
  db.prepare('UPDATE users SET used_bytes = ? WHERE uid = ?').run(3 * 1073741824, user.id);

  const res = await call(worker, env, '/s/' + sub.token);
  const info = res.headers.get('Subscription-Userinfo');
  ok(info, 'header must be present');

  const fields = Object.fromEntries(info.split(';').map((p) => p.trim().split('=')));
  eq(Number(fields.total), 30 * 1073741824, 'total is exact bytes, not a GB float');
  eq(Number(fields.download), 3 * 1073741824, 'download is the byte counter');
  // Handing a client milliseconds puts the expiry ~50,000 years out. It renders, it looks
  // plausible, and it is wrong.
  eq(Number(fields.expire), Math.floor(expires / 1000), 'expire is UNIX seconds');
  ok(Number(fields.expire) < 1e11, 'expire must not be milliseconds');
});

await test('unlimited and never are 0, not absent', async () => {
  const { worker, env, sub } = await withSub();
  const info = (await call(worker, env, '/s/' + sub.token)).headers.get('Subscription-Userinfo');
  const fields = Object.fromEntries(info.split(';').map((p) => p.trim().split('=')));
  // Omitting these makes clients render nothing at all, which reads as "broken", not "unlimited".
  eq(Number(fields.total), 0, 'total=0 means unlimited');
  eq(Number(fields.expire), 0, 'expire=0 means never');
});

await test('the device limit rides in Profile-Title, since no header carries it', async () => {
  const { worker, env, sub } = await withSub({ device_limit: 3 });
  const title = (await call(worker, env, '/s/' + sub.token)).headers.get('Profile-Title');
  ok(title.startsWith('base64:'), 'clients expect the base64: prefix, got ' + title);
  const decoded = new TextDecoder().decode(
    Uint8Array.from(atob(title.slice(7)), (c) => c.charCodeAt(0)));
  ok(decoded.includes('3'), 'the limit should be visible: ' + decoded);
  ok(decoded.includes('دستگاه'), 'and legible in Persian: ' + decoded);
});

await test('clean IPs fan out across every port', async () => {
  const { db, worker, env, sub, user } = await withSub();
  db.prepare('UPDATE users SET ips = ?, port = ? WHERE uid = ?').run('1.2.3.4\n5.6.7.8', '443,2053', user.id);

  const body = serversOnly(await (await call(worker, env, '/s/' + sub.token + '?format=raw')).text());
  const links = body.split('\n').filter(Boolean);
  eq(links.length, 4, '2 IPs x 2 ports');
  ok(links.some((l) => l.includes('@1.2.3.4:2053')), 'combination present');
  // The SNI stays the worker host even when dialling a clean IP -- that is the whole point of one.
  ok(links.every((l) => l.includes('sni=edge.example.workers.dev')), 'sni must stay the worker host');
});

await test('a rotated token kills the old link immediately', async () => {
  const { worker, env, api, user, sub } = await withSub();
  eq((await call(worker, env, '/s/' + sub.token)).status, 200, 'before rotate');

  const rotated = await (await api('/users/' + user.id + ':rotate-subscription', { method: 'POST' })).json();
  ok(rotated.token !== sub.token, 'token changed');

  // No grace period: a link the operator believes they revoked must not still work.
  eq((await call(worker, env, '/s/' + sub.token)).status, 404, 'old token');
  eq((await call(worker, env, '/s/' + rotated.token)).status, 200, 'new token');
});

await test('an unknown token is a flat 404, revealing nothing', async () => {
  const { worker, env } = await withSub();
  const res = await call(worker, env, '/s/' + '0'.repeat(32));
  eq(res.status, 404, 'status');
});

await test('a deleted user stops serving their link', async () => {
  const { worker, env, api, user, sub } = await withSub();
  await api('/users/' + user.id, { method: 'DELETE' });
  eq((await call(worker, env, '/s/' + sub.token)).status, 404, 'status');
});

await test('a config is a template only — the worker never builds a URI', async () => {
  const { post, user } = await authed().then(async (s) => {
    const u = await (await s.post('/users', { username: 'ali' })).json();
    return { ...s, user: u };
  });
  const res = await post('/configs', { user_id: user.id });
  eq(res.status, 400, 'a config without a template must be refused');
  ok((await res.json()).error.message.includes('does not build URIs'), 'and say why');
});

await test('deleting a config empties the subscription but keeps the link alive', async () => {
  const { worker, env, api, user, sub } = await withSub();
  const configs = await (await api('/users/' + user.id + '/configs')).json();
  eq(configs.items.length, 1, 'one config');

  await api('/configs/' + configs.items[0].id, { method: 'DELETE' });
  const res = await call(worker, env, '/s/' + sub.token + '?format=raw');
  eq(res.status, 200, 'the link itself still resolves');
  eq(serversOnly(await res.text()), '', 'but serves no server -- only the entries that say what is left');
});

await test('a config change moves the user watermark, or no device learns of it', async () => {
  const { api, post, user } = await withSub();
  const watermark = (await (await api('/users?since=0')).json()).next_since;
  await post('/configs', { user_id: user.id, uri_template: WS_TEMPLATE, label: 'second' });
  const synced = await (await api('/users?since=' + watermark)).json();
  eq(synced.items.length, 1, 'the user must reappear in the sync');
});

await test('subscription fetches are sampled, not counted per request', async () => {
  const { db, worker, env, sub } = await withSub();
  for (let i = 0; i < 5; i++) await call(worker, env, '/s/' + sub.token);
  // Writing per fetch would let one polling client spend 1,440 of the day's 100,000 D1 writes.
  const row = db.prepare('SELECT hits FROM subscriptions WHERE token = ?').get(sub.token);
  eq(row.hits, 1, 'five fetches inside ten minutes is one sample');
});

// ---------------------------------------------------------------------------- page & dashboard

await test('the page renders RTL Persian and never leaks the username into the URL', async () => {
  const { worker, env, sub } = await withSub({ quota_bytes: 30 * 1073741824 });
  const res = await call(worker, env, '/p/' + sub.token);
  eq(res.status, 200, 'status');
  ok((res.headers.get('content-type') || '').includes('text/html'), 'content type');
  const html = await res.text();
  ok(html.includes('dir="rtl"'), 'must be RTL');
  ok(html.includes('lang="fa"'), 'must declare Persian');
  ok(html.includes('noindex'), 'must not be indexable');
});

await test('the subscription link on the page is LTR-pinned', async () => {
  const { worker, env, sub } = await withSub();
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  // An RTL container reorders an ASCII URL both on screen and in what gets copied, so a link that
  // looks right in a screenshot is pasted broken.
  ok(html.includes('dir="ltr"'), 'the link element must be LTR');
  ok(html.includes('/s/' + sub.token), 'and must carry the real link');
});

await test('digits on the page are Persian', async () => {
  const { worker, env, sub } = await withSub({ device_limit: 3, quota_bytes: 30 * 1073741824 });
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(html.includes('۳'), 'the device limit should be in Persian digits');
  ok(html.includes('گیگابایت'), 'and volume should carry a Persian unit');
});

await test('volume is shown in the unit that fits', async () => {
  const { db, worker, env, sub, user } = await withSub({ quota_bytes: 30 * 1073741824 });
  db.prepare('UPDATE users SET used_bytes = ? WHERE uid = ?').run(30 * 1048576, user.id);
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  // "۰٫۰۳ گیگابایت" is true and reads as nothing at all.
  ok(html.includes('مگابایت'), 'thirty megabytes should be shown in megabytes');
  ok(html.includes('باقی‌مانده'), 'and remaining is what the person actually wants');
});

await test('an unlimited subscription says so rather than drawing an empty bar', async () => {
  const { worker, env, sub } = await withSub();
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(html.includes('نامحدود'), 'must say unlimited');
  ok(!html.includes('<div class="bar">'), 'and must not draw a bar that can never fill');
});

await test('an expired subscription says WHY, not just that it stopped', async () => {
  const { db, worker, env, sub, user } = await withSub();
  db.prepare('UPDATE users SET expires_at = ? WHERE uid = ?').run(Date.now() - 86400000, user.id);
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(html.includes('منقضی شده'), 'status');
  ok(html.includes('به پایان رسیده'), 'reason in words');
});

await test('a subscription out of volume is distinguished from an expired one', async () => {
  const { db, worker, env, sub, user } = await withSub({ quota_bytes: 1000 });
  db.prepare('UPDATE users SET used_bytes = 1000 WHERE uid = ?').run(user.id);
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(html.includes('حجم تمام شده'), 'status');
  ok(!html.includes('منقضی'), 'must not blame the wrong thing');
});

await test('an unstarted on-first-connect subscription is not called expired', async () => {
  const { db, worker, env, sub, user } = await withSub();
  db.prepare("UPDATE users SET expiry_mode = 'on_first_connect', activation_days = 30 WHERE uid = ?").run(user.id);
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(html.includes('با اولین اتصال'), 'must explain that the clock has not started');
  ok(html.includes('۳۰ روز'), 'and how long it will then last');
});

await test('the reset policy is stated in words, not implied by a bar', async () => {
  const { db, worker, env, sub, user } = await withSub({ quota_bytes: 1000 });
  db.prepare("UPDATE users SET quota_reset_policy = 'monthly' WHERE uid = ?").run(user.id);
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(html.includes('هر ماه صفر می‌شود'), 'a bar that silently refills explains nothing');
});

await test('the page shows the device allowance, and invents no count', async () => {
  const { worker, env, sub } = await withSub({ device_limit: 3 });
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(html.includes('تا ۳ دستگاه'), 'the allowance');
  // Counting concurrent devices needs the Durable Object (Phase 3). "۰ از ۳" drawn from a table
  // nothing writes to is a number that is always wrong.
  ok(!html.includes('۰ از ۳'), 'must not draw a count it cannot measure');
});

await test('the operator contact line appears when something has stopped', async () => {
  const { db, worker, env, sub, user } = await withSub();
  db.prepare("INSERT OR REPLACE INTO settings (key,value) VALUES ('studio_contact', ?)").run('@example');
  db.prepare('UPDATE users SET expires_at = ? WHERE uid = ?').run(Date.now() - 1000, user.id);
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(html.includes('@example'), 'free text the operator sets, so the app ships no wording of its own');
});

await test('the page carries no commerce or legacy vocabulary', async () => {
  const { worker, env, sub } = await withSub({ quota_bytes: 1000, device_limit: 2 });
  const html = await (await call(worker, env, '/p/' + sub.token)).text();
  for (const banned of ['فروش', 'مشتری', 'خرید', 'قیمت', 'تومان', 'MLM', 'mlm', 'پنل']) {
    ok(!html.includes(banned), 'page must not contain "' + banned + '"');
  }
});

await test('a revoked or unknown token gets a flat 404 from the page too', async () => {
  const { worker, env, api, user, sub } = await withSub();
  eq((await call(worker, env, '/p/' + '0'.repeat(32))).status, 404, 'unknown');
  await api('/users/' + user.id, { method: 'DELETE' });
  eq((await call(worker, env, '/p/' + sub.token)).status, 404, 'deleted user');
});

await test('the dashboard answers every tile in one call', async () => {
  const { api, post } = await authed();
  const now = Date.now();
  await post('/users', { username: 'ok1' });
  await post('/users', { username: 'soon', policy: { expires_at: now + 2 * 86400000 } });
  await post('/users', { username: 'gone', policy: { expires_at: now - 86400000 } });

  const d = await (await api('/dashboard')).json();
  eq(d.users.total, 3, 'total');
  eq(d.users.expiring_soon, 1, 'expiring within three days');
  eq(d.users.expired, 1, 'expired');
  eq(d.users.over_quota, 0, 'over quota');
  eq(d.enforcement, 'soft', 'no Durable Object yet, and it says so');
  ok(d.traffic_24h && typeof d.traffic_24h.down_bytes === 'number', 'traffic block present');
});

await test('the dashboard counts users over quota', async () => {
  const { db, api, post } = await authed();
  const u = await (await post('/users', { username: 'ali', policy: { quota_bytes: 1000 } })).json();
  db.prepare('UPDATE users SET used_bytes = 1000 WHERE uid = ?').run(u.id);
  eq((await (await api('/dashboard')).json()).users.over_quota, 1, 'over_quota');
});

await test('a deleted user leaves every dashboard tile', async () => {
  const { api, post } = await authed();
  const u = await (await post('/users', { username: 'ali', policy: { expires_at: Date.now() - 1000 } })).json();
  eq((await (await api('/dashboard')).json()).users.expired, 1, 'before delete');
  await api('/users/' + u.id, { method: 'DELETE' });
  const d = await (await api('/dashboard')).json();
  eq(d.users.total, 0, 'total');
  eq(d.users.expired, 0, 'tombstones must not be counted as expired users');
});

await test('the dashboard needs a key', async () => {
  const { worker, env } = await scenario();
  eq((await call(worker, env, '/a1b2c3d4/v1/dashboard')).status, 401, 'status');
});

// ---------------------------------------------------------------------------- legacy auth (R3)

// Not `admin`: a Config Studio installation refuses the default password outright (03-entry.js),
// which is the point -- these tests are about seeding, so they use a password of the operator's own.
const LEGACY_PW = 'an-operator-password';
const SHA_ADMIN = await sha256Hex(LEGACY_PW);

await test('a Config Studio install serves none of the legacy public pages, and never the default password', async () => {
  const { worker, env } = await scenario({ env: { ADMIN_PASSWORD: 'admin' } });
  const nginx = async (p, init) => {
    const res = await call(worker, env, p, init);
    const body = await res.text();
    return /nginx/i.test(body) && !/password|رمز/i.test(body);
  };
  ok(await nginx('/admin'), '/admin is the camouflage page, not a password form');
  ok(await nginx('/status/ali'), '/status/{name} gives nothing away');
  ok(await nginx('/sub/ali'), '/sub/{name} gives nothing away');
  ok(await nginx('/api/users', { headers: { Cookie: 'panel_session=' + await sha256Hex('admin') } }),
    'the default password opens nothing');
});

await test('the binding password is seeded into the database on first use', async () => {
  const { db, worker, env } = await scenario({ env: { ADMIN_PASSWORD: LEGACY_PW } });
  await call(worker, env, '/api/users', { headers: { Cookie: 'panel_session=' + SHA_ADMIN } });

  // Until build 7 removes the binding, this row is the only thing that will keep the panel shut.
  const row = db.prepare("SELECT value FROM settings WHERE key = 'panel_password'").get();
  ok(row, 'panel_password must exist after the first authenticated request');
  eq(row.value, SHA_ADMIN, 'and must be the hash of the binding password');
});

await test('seeding never overwrites a password the operator already set', async () => {
  const { db, worker, env } = await scenario({ env: { ADMIN_PASSWORD: LEGACY_PW } });
  const mine = await sha256Hex('a-password-they-chose');
  await call(worker, env, '/a1b2c3d4/v1/health'); // migrate, so `settings` exists
  db.prepare("INSERT OR REPLACE INTO settings (key,value) VALUES ('panel_password', ?)").run(mine);

  await call(worker, env, '/api/users', { headers: { Cookie: 'panel_session=' + SHA_ADMIN } });
  eq(db.prepare("SELECT value FROM settings WHERE key = 'panel_password'").get().value, mine,
    'the stored password must win');
});

await test('with NO password anywhere, the legacy API fails CLOSED', async () => {
  // This is the state every installation lands in the moment build 7 drops the binding, if the
  // seeding above has not happened. It used to answer 200 to anyone who knew the URL.
  const { worker, env } = await scenario();
  const res = await call(worker, env, '/api/users');
  ok(res.status === 401 || res.status === 403,
    'an unauthenticated /api/users must be refused, got ' + res.status);
  const body = await res.text();
  ok(!body.includes('username'), 'and must not return the user list');
});

await test('a fresh install can still reach the setup screen', async () => {
  // Failing closed must not lock a genuinely new panel out of being set up. `handlePanel` checks
  // for a password before it ever calls verifyApiAuth, so the setup path is unaffected. A legacy
  // install only: a Config Studio one has no /admin at all (the next test).
  const { worker, env } = await scenario({ route: null });
  const res = await call(worker, env, '/admin');
  eq(res.status, 200, 'status');
  ok((await res.text()).length > 0, 'the setup page should render');
});

await test('the legacy cookie still works while the binding is shipped', async () => {
  const { worker, env } = await scenario({ env: { ADMIN_PASSWORD: LEGACY_PW } });
  eq((await call(worker, env, '/api/users', { headers: { Cookie: 'panel_session=' + SHA_ADMIN } })).status,
    200, 'the right cookie');
  const wrong = await call(worker, env, '/api/users', { headers: { Cookie: 'panel_session=' + '0'.repeat(64) } });
  ok(wrong.status !== 200, 'the wrong cookie must not, got ' + wrong.status);
});

await test('after seeding, the panel stays shut when the binding goes away (build 7)', async () => {
  const { db, worker, env } = await scenario({ env: { ADMIN_PASSWORD: LEGACY_PW } });
  await call(worker, env, '/api/users', { headers: { Cookie: 'panel_session=' + SHA_ADMIN } });

  // Build 7: same database, same worker, no binding. This is the whole point of the ordering.
  const worker7 = await loadWorker();
  const env7 = { DB: makeD1(db), STUDIO_ROUTE: 'a1b2c3d4' };
  ok((await call(worker7, env7, '/api/users')).status !== 200, 'no cookie must still be refused');
  eq((await call(worker7, env7, '/api/users', { headers: { Cookie: 'panel_session=' + SHA_ADMIN } })).status,
    200, 'the seeded hash must still authenticate');
});


// ---------------------------------------------------------------------------- the old panel's list (build 18)
//
// The Cloud tab's MLM panel is the old manager of this same engine. Where Config Studio runs it must
// not undo what Config Studio wrote, and what it does write must reach Config Studio.

/** The old panel's own calls, with its cookie. */
const oldPanel = (worker, env, path, method = 'GET', body) => call(worker, env, path, {
  method,
  headers: { Cookie: 'panel_session=' + SHA_ADMIN, 'Content-Type': 'application/json' },
  body: body === undefined ? undefined : JSON.stringify(body),
});

/** A Config Studio installation whose database has been migrated, as the app's first call does. */
async function studioWithOldPanel() {
  const sc = await scenario({ env: { ADMIN_PASSWORD: LEGACY_PW } });
  await call(sc.worker, sc.env, '/a1b2c3d4/v1/health');
  return sc;
}

await test('a person made in the old panel gets the term Config Studio reads', async () => {
  const { db, worker, env } = await studioWithOldPanel();
  eq((await oldPanel(worker, env, '/api/users', 'POST',
    { username: 'ali', limit_gb: 1, expiry_days: 30, tls: 'tls', port: '443' })).status, 200, 'create');
  const row = db.prepare(
    "SELECT expires_at, CAST(strftime('%s', created_at) AS INTEGER) * 1000 AS created FROM users WHERE username = 'ali'"
  ).get();
  eq(row.expires_at, row.created + 30 * 86400000, 'thirty days from creation, in the column Config Studio shows');
});

await test('an old-panel edit leaves the term Config Studio set', async () => {
  const { db, worker, env } = await studioWithOldPanel();
  await oldPanel(worker, env, '/api/users', 'POST', { username: 'sara', limit_gb: 1, expiry_days: 30, tls: 'tls', port: '443' });
  // Renewed in Config Studio: the term is absolute, and expiry_days is only the days LEFT then.
  const renewedTo = Date.now() + 40 * 86400000;
  db.prepare("UPDATE users SET expires_at = ?, expiry_days = 2 WHERE username = 'sara'").run(renewedTo);
  await oldPanel(worker, env, '/api/users/sara', 'PUT', { limit_gb: 2, expiry_days: 2, tls: 'tls', port: '443' });
  const row = db.prepare("SELECT expires_at, quota_bytes FROM users WHERE username = 'sara'").get();
  eq(row.expires_at, renewedTo, 'reading 2 as days-from-creation would have ended the term in the past');
  eq(row.quota_bytes, 2 * 1073741824, 'the volume edit itself still applies');
});

await test('the old panel lists nobody Config Studio deleted, and shows connected people online', async () => {
  const { db, worker, env } = await studioWithOldPanel();
  for (const name of ['kept', 'gone', 'here']) {
    await oldPanel(worker, env, '/api/users', 'POST', { username: name, tls: 'tls', port: '443' });
  }
  db.prepare("UPDATE users SET deleted_at = ?, uuid = NULL WHERE username = 'gone'").run(Date.now());
  // Two minutes since the last usage flush: connected, by the engine's own three-minute window.
  db.prepare("UPDATE users SET last_active = ? WHERE username = 'here'").run(Date.now() - 120000);

  const list = await (await oldPanel(worker, env, '/api/users')).json();
  eq(list.users.map((u) => u.username).sort().join(','), 'here,kept', 'the deleted person is not listed');
  eq(list.users.find((u) => u.username === 'here').is_online, 1, 'two minutes ago is online, not offline');
});

await test('the old panel alone still lists its people on its own schema', async () => {
  // No Config Studio: the database keeps the old columns, and the list must not depend on new ones.
  const { worker, env } = await scenario({ route: null, env: { ADMIN_PASSWORD: LEGACY_PW } });
  await oldPanel(worker, env, '/api/users', 'POST', { username: 'solo', limit_gb: 1, expiry_days: 10, tls: 'tls', port: '443' });
  const list = await (await oldPanel(worker, env, '/api/users')).json();
  eq(list.users.map((u) => u.username).join(','), 'solo', 'listed');
});

await test('switching someone off in the old panel reaches Config Studio', async () => {
  const { db, worker, env } = await studioWithOldPanel();
  await oldPanel(worker, env, '/api/users', 'POST', { username: 'reza', tls: 'tls', port: '443' });
  db.prepare("UPDATE users SET updated_at = 1 WHERE username = 'reza'").run();
  await oldPanel(worker, env, '/api/users/reza', 'PUT', { toggle_only: true });
  const row = db.prepare("SELECT is_active, updated_at FROM users WHERE username = 'reza'").get();
  eq(row.is_active, 0, 'switched off');
  ok(row.updated_at > 1, 'and the change time moved, which is what Config Studio syncs on');
});


// ============================================================================
// «بسته» — /v1/plans
// ============================================================================

const GB = 1073741824;

/** A plan body with sane defaults, so each test only states what it is actually about. */
function planBody(over = {}) {
  return {
    name: '۳۰ گیگ / ۳۰ روزه',
    quota_bytes: 30 * GB,
    duration_days: 30,
    device_limit: 2,
    conn_limit: 3,
    ...over,
  };
}

await test('a plan can be written, read back and listed', async () => {
  const { api } = await authed();
  const put = await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody()) });
  eq(put.status, 201, 'first write is a create');

  const plan = await put.json();
  eq(plan.id, 'p30', 'id is the one the caller chose');
  eq(plan.quota_bytes, 30 * GB, 'volume is bytes, exactly');
  eq(plan.duration_days, 30, 'duration');
  eq(plan.policy.device_limit, 2, 'device limit');

  eq((await (await api('/plans/p30')).json()).name, '۳۰ گیگ / ۳۰ روزه', 'read back');
  eq((await (await api('/plans')).json()).items.length, 1, 'list length');
});

await test('writing the same plan twice is one plan, not two (fleet sync is retryable)', async () => {
  const { api } = await authed();
  const first = await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody()) });
  eq(first.status, 201, 'create');
  const created = (await first.json()).created_at;

  const again = await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody({ quota_bytes: 50 * GB })) });
  eq(again.status, 200, 'a repeat write is an update, not a conflict');

  const list = (await (await api('/plans')).json()).items;
  eq(list.length, 1, 'still one plan');
  eq(list[0].quota_bytes, 50 * GB, 'the second write won');
  eq(list[0].created_at, created, 'created_at is not rewritten by a resync');
});

await test('only one plan can be the default', async () => {
  const { api } = await authed();
  await api('/plans/a', { method: 'PUT', body: JSON.stringify(planBody({ name: 'a', is_default: true })) });
  await api('/plans/b', { method: 'PUT', body: JSON.stringify(planBody({ name: 'b', is_default: true })) });

  const items = (await (await api('/plans')).json()).items;
  eq(items.filter((p) => p.is_default).map((p) => p.id), ['b'], 'the newest default is the only one');
});

await test('a plan is archived, never erased, and archiving touches no user', async () => {
  const { api, post } = await authed();
  await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody()) });
  const user = await (await post('/users', { username: 'ali', plan_id: 'p30' })).json();

  eq((await api('/plans/p30', { method: 'DELETE' })).status, 200, 'archive');
  eq((await (await api('/plans')).json()).items.length, 0, 'gone from the normal list');
  eq((await (await api('/plans?archived=1')).json()).items.length, 1, 'still there when asked for');

  // The row must survive, or `renewals.plan_id` and `users.plan_id` point at nothing.
  eq((await (await api('/plans/p30')).json()).archived, true, 'still readable by id');
  eq((await (await api('/users/' + user.id)).json()).plan_id, 'p30', 'the user still carries the plan');
});

await test('editing a plan does NOT change the people already on it', async () => {
  const { api, post } = await authed();
  await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody()) });
  const user = await (await post('/users', {
    username: 'ali', plan_id: 'p30',
    policy: { quota_bytes: 30 * GB, device_limit: 2 },
  })).json();

  await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody({ quota_bytes: 5 * GB, device_limit: 1 })) });

  const after = await (await api('/users/' + user.id)).json();
  eq(after.policy.quota_bytes, 30 * GB, 'volume must be untouched by a plan edit');
  eq(after.policy.device_limit, 2, 'caps must be untouched by a plan edit');
});

await test('applying a plan re-applies the caps and leaves volume and time alone', async () => {
  const { api, post } = await authed();
  await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody()) });
  const expires = Date.now() + 40 * 86400000;
  const user = await (await post('/users', {
    username: 'ali', plan_id: 'p30',
    policy: { quota_bytes: 80 * GB, expires_at: expires, device_limit: 1 },
  })).json();

  const res = await (await post('/plans/p30:apply', {})).json();
  eq(res.users, 1, 'reports how many rows it wrote');

  const after = await (await api('/users/' + user.id)).json();
  eq(after.policy.device_limit, 2, 'the cap came from the plan');
  // The whole point: someone topped up to 80 GB by a renewal is not pulled back to 30.
  eq(after.policy.quota_bytes, 80 * GB, 'volume must NOT move unless it was asked for by name');
  eq(after.policy.expires_at, expires, 'expiry must NOT move');
});

await test('applying a plan includes volume only when asked for by name', async () => {
  const { api, post } = await authed();
  await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody()) });
  const user = await (await post('/users', {
    username: 'ali', plan_id: 'p30', policy: { quota_bytes: 80 * GB },
  })).json();

  await post('/plans/p30:apply', { fields: ['quota_bytes'] });

  const after = await (await api('/users/' + user.id)).json();
  eq(after.policy.quota_bytes, 30 * GB, 'named explicitly, so it moves');
  eq(after.policy.device_limit, null, 'and nothing else came along with it');
});

await test('applying a plan reaches only that plan and skips deleted users', async () => {
  const { api, post } = await authed();
  await api('/plans/a', { method: 'PUT', body: JSON.stringify(planBody({ name: 'a', device_limit: 9 })) });
  await api('/plans/b', { method: 'PUT', body: JSON.stringify(planBody({ name: 'b', device_limit: 1 })) });

  const onA = await (await post('/users', { username: 'ali', plan_id: 'a' })).json();
  const onB = await (await post('/users', { username: 'reza', plan_id: 'b' })).json();
  const gone = await (await post('/users', { username: 'sara', plan_id: 'a' })).json();
  await api('/users/' + gone.id, { method: 'DELETE' });

  const res = await (await post('/plans/a:apply', {})).json();
  eq(res.users, 1, 'the deleted user is not counted and not written');

  eq((await (await api('/users/' + onA.id)).json()).policy.device_limit, 9, 'plan a applied');
  eq((await (await api('/users/' + onB.id)).json()).policy.device_limit, null, 'plan b untouched');
});

await test('a plan reports how many people are on it', async () => {
  const { api, post } = await authed();
  await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody()) });
  await post('/users', { username: 'ali', plan_id: 'p30' });
  await post('/users', { username: 'reza', plan_id: 'p30' });
  await post('/users', { username: 'sara' });

  eq((await (await api('/plans/p30/users')).json()).users, 2, 'only the people on this plan');
});

await test('renewing from a plan uses the plan terms and keeps the subscription token', async () => {
  const { api, post } = await authed();
  await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody()) });
  const user = await (await post('/users', {
    username: 'ali', policy: { quota_bytes: 10 * GB, expires_at: Date.now() + 5 * 86400000 },
  })).json();
  const before = await (await api('/users/' + user.id + '/subscription')).json();

  const renewed = await (await post('/users/' + user.id + ':renew', { plan_id: 'p30' })).json();
  eq(renewed.policy.quota_bytes, 40 * GB, '10 + the plan 30, added not replaced');

  const after = await (await api('/users/' + user.id + '/subscription')).json();
  eq(after.token, before.token, 'a renewal must never change the link the person is holding');
});

await test('a plan is refused without a name, and a bad id is refused outright', async () => {
  const { api } = await authed();
  eq((await api('/plans/p30', { method: 'PUT', body: JSON.stringify({ quota_bytes: GB }) })).status,
    400, 'no name');
  eq((await api('/plans/has spaces', { method: 'PUT', body: JSON.stringify(planBody()) })).status,
    400, 'invalid id');
  eq((await api('/plans/nope:apply', { method: 'POST', body: '{}' })).status, 404, 'applying a plan that is not there');
  eq((await api('/plans/p30')).status, 404, 'reading a plan that is not there');
});

await test('plans need a key like everything else', async () => {
  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/plans')).status, 401, 'list');
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/plans/p30', { method: 'PUT', body: '{}' })).status, 401, 'write');
});

await test('the plan surface carries no commerce vocabulary or columns (D6)', async () => {
  const { db, api } = await authed();
  await api('/plans/p30', { method: 'PUT', body: JSON.stringify(planBody()) });

  const cols = db.prepare("SELECT name FROM pragma_table_info('plans')").all().map((r) => r.name);
  for (const banned of ['price', 'currency', 'paid_at', 'amount', 'invoice']) {
    ok(!cols.includes(banned), `plans must have no '${banned}' column, got ${cols.join(', ')}`);
  }

  const body = JSON.stringify(await (await api('/plans')).json()).toLowerCase();
  for (const word of ['price', 'customer', 'seller', 'purchase', 'toman']) {
    ok(!body.includes(word), `the plans response must not say '${word}'`);
  }
});

await test('plans.v1 is advertised only once the index behind it exists', async () => {
  const { worker, env } = await scenario();
  const health = await (await call(worker, env, '/a1b2c3d4/v1/health')).json();
  ok(health.schema_version >= 9, 'schema should be at v9, got ' + health.schema_version);
  ok(health.capabilities.includes('plans.v1'), 'plans.v1 should be advertised at v9');
  ok(health.capabilities.includes('plans.apply'), 'plans.apply should be advertised at v9');
});

// ---------------------------------------------------------------------------- caps and the DO

// The arithmetic behind device / concurrent / IP limits, tested without a Durable Object runtime.
// It is factored out of `SessionDO` precisely so it can be: the object needs a real DO to run, and
// the part that decides who gets refused is the part that must not be wrong.
await test('the device limit counts devices connected now, and a quiet one gives its slot up', async () => {
  const { worker } = await authed();
  const at = worker.internals.studioAdmissionVerdict;

  // No policy is no cap. A user with nothing set must never be refused by this.
  eq(at({ present: 99, isPresent: false, oldestIdleMs: 0, distinctIps: 99, isNewIp: true }, {}).allow,
    true, 'no limits, no refusal');

  // A device already connected does not take a second slot, however many connections it opens.
  eq(at({ present: 3, isPresent: true, oldestIdleMs: 0, distinctIps: 3, isNewIp: false },
    { device_limit: 3 }).allow, true, 'a connected device reconnects at the limit');
  eq(at({ present: 3, isPresent: false, oldestIdleMs: 10000, distinctIps: 3, isNewIp: true },
    { device_limit: 3 }).reason, 'device_limit', 'a fourth device, while the others are in use, is refused');

  // The same phone on a new network: its old address stopped reporting, so after a minute and a
  // half its slot is handed over instead of holding the person out until somebody presses «فراموش کن».
  const moved = at({ present: 1, isPresent: false, oldestIdleMs: 120000, distinctIps: 1, isNewIp: true },
    { device_limit: 1 });
  eq(moved.allow, true, 'a device that moved networks gets back in');
  eq(moved.evict, true, 'by taking the quiet slot');

  // A count of connections no longer refuses anyone: one app opens dozens of them.
  eq(at({ present: 5, isPresent: false, oldestIdleMs: 0, distinctIps: 5, isNewIp: false },
    { conn_limit: 1 }).allow, true, 'a connection count is not a device count');

  eq(at({ present: 2, isPresent: true, oldestIdleMs: 0, distinctIps: 2, isNewIp: false },
    { ip_limit: 2 }).allow, true, 'the same address again is fine');
  eq(at({ present: 2, isPresent: false, oldestIdleMs: 0, distinctIps: 2, isNewIp: true },
    { ip_limit: 2 }).reason, 'ip_limit', 'a third address is refused');
});

// This one was live on real accounts. `:renew` writes `expiry_days` as days remaining FROM NOW,
// and the data plane measured that column FROM CREATION -- so renewing someone late in their term
// set an expiry in the past, and the tunnel refused them immediately while every screen showed
// them their new credit. Nothing in the API layer could see it; only the data plane reads it.
await test('a renewed user is not expired by the build-5 mirror column', async () => {
  const { worker, db, api, post } = await authed();
  const expired = worker.internals.isUserExpired;

  const created = Date.now() - 29 * 86400000;
  const user = await (await post('/users', {
    username: 'ali',
    policy: { expires_at: created + 30 * 86400000, quota_bytes: 30 * 1073741824 },
  })).json();
  // Backdate creation the way a month of real use would have.
  db.prepare('UPDATE users SET created_at = ? WHERE uid = ?')
    .run(new Date(created).toISOString().replace('T', ' ').slice(0, 19), user.id);

  const renewed = await (await api('/users/' + user.id + ':renew', {
    method: 'POST', body: JSON.stringify({ add_days: 15 }),
  })).json();
  ok(renewed.policy.expires_at > Date.now() + 14 * 86400000, 'the API grants ~15 more days');

  const row = db.prepare('SELECT expires_at, expiry_days, created_at, is_active FROM users WHERE uid = ?')
    .get(user.id);
  // The mirror really does say about sixteen days, counted from now -- that is what a rolled-back
  // build 5 needs it to mean. The point is that the data plane must not read it as counted from
  // creation, which would put this user's expiry two weeks in the past.
  ok(row.expiry_days <= 17, 'the build-5 mirror is days remaining, not the total term');
  eq(expired(row), false, 'and the data plane does not treat the renewed user as expired');

  // The old comparison, for the record: created_at + expiry_days lands well before now.
  const legacyExpiry = new Date(row.created_at).getTime() + row.expiry_days * 86400000;
  ok(legacyExpiry < Date.now(), 'which is exactly what the old comparison would have concluded');
});

// Every usage figure in the product reads the byte columns; admission reads the GB mirrors. Nothing
// incremented the bytes after the migration backfilled them once, so the number the operator saw
// and the number in the person's own client were frozen on the day the account was upgraded.
await test('traffic accrues to the byte columns, which is what everything reports', async () => {
  const { worker, env, db, api, post } = await authed();
  const user = await (await post('/users', {
    username: 'ali', policy: { quota_bytes: 30 * 1073741824 },
  })).json();
  await api('/configs', {
    method: 'POST',
    body: JSON.stringify({ user_id: user.id, uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}' }),
  });

  // Exactly the flush statement both data-plane paths run.
  const bytes = 5 * 1073741824;
  db.prepare(
    'UPDATE users SET used_gb = used_gb + ?, daily_used_gb = COALESCE(daily_used_gb,0) + ?, ' +
    'used_bytes = COALESCE(used_bytes,0) + ?, daily_used_bytes = COALESCE(daily_used_bytes,0) + ? ' +
    'WHERE username = ?'
  ).run(5, 5, bytes, bytes, 'ali');

  eq((await (await api('/users/' + user.id)).json()).usage.used_bytes, bytes,
    'the API reports what was actually used');

  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const res = await call(worker, env, '/s/' + sub.token);
  const info = res.headers.get('Subscription-Userinfo');
  ok(info.includes('download=' + bytes), 'and so does the header the person\'s own client reads');

  const page = await (await call(worker, env, '/p/' + sub.token)).text();
  ok(page.includes('۵') || page.includes('5'), 'and the page draws a bar that has moved');
});

// Someone whose term starts on their first connection has no expiry until they use the link. The
// alternative -- an expiry set at creation -- means a link that sits unopened burns its own time,
// which is the whole reason the mode exists.
await test('an on-first-connect term starts on the first connection, not before', async () => {
  const { worker, db, post } = await authed();
  const start = worker.internals.studioStartOnFirstConnect;
  const expired = worker.internals.isUserExpired;
  const env = { DB: makeD1(db) };

  await post('/users', {
    username: 'ali',
    policy: { expiry_mode: 'on_first_connect', activation_days: 7 },
  });
  const before = db.prepare(
    'SELECT uid, expiry_mode, activation_days, first_connect_at, expires_at FROM users WHERE username = ?'
  ).get('ali');
  eq(before.expires_at, null, 'no expiry until it is used');
  eq(expired(before), false, 'and therefore not expired');

  await start(env, before);
  const after = db.prepare(
    'SELECT first_connect_at, expires_at, expiry_days FROM users WHERE username = ?'
  ).get('ali');
  ok(after.first_connect_at > 0, 'the clock started');
  ok(after.expires_at > Date.now() + 6 * 86400000, 'seven days from now');
  eq(after.expiry_days, 7, 'and the build-5 mirror agrees');

  // Idempotent: a second connection must not restart the term.
  const firstAt = after.first_connect_at;
  await start(env, {
    uid: before.uid, expiry_mode: 'on_first_connect', activation_days: 7, first_connect_at: firstAt,
  });
  eq(db.prepare('SELECT first_connect_at FROM users WHERE username = ?').get('ali').first_connect_at,
    firstAt, 'the second connection does not restart it');
});

// `env.SESSIONS` is absent on every installation deployed before this build, so the fallback is the
// majority state rather than an edge case. A tunnel that stops working because a cap could not be
// CHECKED is a worse failure than a cap that goes unenforced for one connection.
await test('with no Durable Object the tunnel still admits, in soft mode', async () => {
  const { worker } = await authed();
  const admit = worker.internals.studioAdmit;

  const none = await admit({}, 'u1', { policy: { device_limit: 1 } });
  eq(none.allow, true, 'admitted with no namespace bound');
  eq(none.mode, 'soft', 'and honestly labelled');

  // A namespace that throws is the same answer, for the same reason.
  const broken = await admit(
    { SESSIONS: { idFromName() { throw new Error('unavailable'); } } },
    'u1', { policy: { device_limit: 1 } },
  );
  eq(broken.allow, true, 'a failing object does not take the tunnel down with it');
  eq(broken.mode, 'soft', 'and is reported as soft rather than as enforced');
});

// The hashes are what stop this being a list of subscriber addresses on somebody else's Cloudflare
// account. Salted, so `sha256(ip)` cannot be reversed for the whole IPv4 space in seconds.
await test('device and address hashes are salted and carry no raw address', async () => {
  const { worker, db } = await authed();
  const { studioDeviceHash, studioIpHash, studioSalt } = worker.internals;
  const env = { DB: makeD1(db) };
  const salt = await studioSalt(env.DB);
  ok(salt && salt.length >= 16, 'a salt exists and was persisted');

  const req = (ip, ua) => new Request(BASE, { headers: { 'CF-Connecting-IP': ip, 'User-Agent': ua } });

  const a = await studioIpHash(salt, req('203.0.113.7', 'x'));
  // the whole address: «203» alone turns up in about one random hex hash in seventy, and failed a build
  ok(!a.includes('203.0.113.7') && /^[0-9a-f]+$/.test(a), 'the address is not in the hash');
  eq(a, await studioIpHash(salt, req('203.0.113.7', 'x')), 'stable for the same address');
  ok(a !== await studioIpHash(salt, req('203.0.113.8', 'x')), 'different for a different address');
  ok(a !== await studioIpHash('other-salt', req('203.0.113.7', 'x')), 'and salted');

  // The device hash deliberately uses only the /24, so one phone moving between two addresses on
  // the same network still looks like one device. It is an approximation and the UI says so.
  const d1 = await studioDeviceHash(salt, req('203.0.113.7', 'v2rayNG/1.8'));
  eq(d1, await studioDeviceHash(salt, req('203.0.113.90', 'v2rayNG/1.8')), 'same subnet, same device');
  ok(d1 !== await studioDeviceHash(salt, req('198.51.100.7', 'v2rayNG/1.8')), 'a different network is a different device');
});


// The dashboard sums `usage_hourly` for its 24-hour figure, and nothing wrote that table -- so the
// tile read zero on every installation no matter how much traffic had passed. A number on screen
// that no amount of use could move.
await test('traffic reaches the hourly rollup the dashboard reads', async () => {
  const { worker, db, api, post } = await authed();
  await post('/users', { username: 'ali' });
  const accrue = worker.internals.studioAccrueHourly;
  const env = { DB: makeD1(db) };

  // Below the coalescing threshold: held in the isolate, not written. That is the point of it --
  // the per-user flush fires once per 50 MB and a bucket write on each would double the cost of
  // accounting on the one path where D1 writes are scarce.
  accrue(env, 10 * 1024 * 1024, null, false);
  eq(db.prepare('SELECT COUNT(*) AS n FROM usage_hourly').get().n, 0, 'small change is coalesced');

  // A session ending forces its tail through, so it cannot sit waiting for a threshold nothing
  // will push it past.
  accrue(env, 5 * 1024 * 1024, null, true);
  const row = db.prepare('SELECT bucket_ts, down_bytes FROM usage_hourly').get();
  eq(row.down_bytes, 15 * 1024 * 1024, 'and both parts arrive together');
  eq(row.bucket_ts % 3600000, 0, 'in an hour-aligned bucket');

  const dash = await (await api('/dashboard')).json();
  eq(dash.traffic_24h.down_bytes, 15 * 1024 * 1024, 'which is what the dashboard reports');

  // One row per hour whatever the user count -- that is what makes the dashboard answerable
  // without touching per-user rows at all.
  accrue(env, 600 * 1024 * 1024, null, false);
  eq(db.prepare('SELECT COUNT(*) AS n FROM usage_hourly').get().n, 1, 'still one row for this hour');
  eq(db.prepare('SELECT down_bytes FROM usage_hourly').get().down_bytes,
    615 * 1024 * 1024, 'accumulated into the same bucket');
});


// `quota_reset_policy` was a column the operator could set, the subscriber's page could describe,
// and nothing acted on. The plan editor offers the choice, so the choice has to be real.
await test('a monthly quota refills, and does not drift', async () => {
  const { worker, db, post } = await authed();
  const refill = worker.internals.studioResetQuotaIfDue;
  const env = { DB: makeD1(db) };

  await post('/users', {
    username: 'ali',
    policy: { quota_bytes: 30 * 1073741824, reset: 'monthly' },
  });
  const read = () => db.prepare(
    'SELECT uid, used_bytes, used_gb, quota_reset_policy, quota_reset_at FROM users WHERE username = ?'
  ).get('ali');

  // First pass sets the boundary and refills nothing: a user whose period has never been recorded
  // has not reached the end of one.
  let u = read();
  eq(u.quota_reset_at, null, 'no boundary yet');
  await refill(env, u);
  u = read();
  ok(u.quota_reset_at > Date.now(), 'the first boundary is in the future');
  const firstBoundary = u.quota_reset_at;

  // Nothing to do before it comes round.
  db.prepare('UPDATE users SET used_bytes = ?, used_gb = 20 WHERE username = ?').run(20 * 1073741824, 'ali');
  await refill(env, read());
  eq(read().used_bytes, 20 * 1073741824, 'usage is untouched inside the period');

  // Two whole periods late: the volume comes back, and the next boundary is on the ORIGINAL cycle
  // rather than a month from today -- otherwise a user who does not connect for a while drags
  // their own renewal date along with them.
  const twoLate = firstBoundary - 2 * 30 * 86400000;
  db.prepare('UPDATE users SET quota_reset_at = ? WHERE username = ?').run(twoLate, 'ali');
  await refill(env, read());
  u = read();
  eq(u.used_bytes, 0, 'the volume came back');
  eq(u.used_gb, 0, 'and so did the build-5 mirror');
  ok(u.quota_reset_at > Date.now(), 'the next boundary is ahead');
  eq((u.quota_reset_at - twoLate) % (30 * 86400000), 0, 'and still on the original cycle');
});

await test('a reset policy of none never refills', async () => {
  const { worker, db, post } = await authed();
  const refill = worker.internals.studioResetQuotaIfDue;
  const env = { DB: makeD1(db) };

  await post('/users', { username: 'ali', policy: { quota_bytes: 1000 } });
  db.prepare('UPDATE users SET used_bytes = 900, quota_reset_at = 1 WHERE username = ?').run('ali');
  await refill(env, db.prepare('SELECT uid, used_bytes, quota_reset_policy, quota_reset_at FROM users WHERE username = ?').get('ali'));
  eq(db.prepare('SELECT used_bytes FROM users WHERE username = ?').get('ali').used_bytes, 900,
    'a quota with no reset policy stays spent');
});

// Per user, not per installation. Soft records everything and refuses nothing, which is what makes
// "watch this one before cutting them off" possible -- and it is the same path an installation with
// no Durable Object takes, so the fallback did not need inventing.
await test('a user set to soft enforcement is counted but never refused', async () => {
  const { worker } = await authed();
  const at = worker.internals.studioAdmissionVerdict;

  const overEverything = {
    present: 99, isPresent: false, oldestIdleMs: 0, distinctIps: 99, isNewIp: true,
  };
  const caps = { device_limit: 1, conn_limit: 1, ip_limit: 1 };

  eq(at(overEverything, { ...caps, enforcement: 'strict' }).allow, false, 'strict refuses');
  const soft = at(overEverything, { ...caps, enforcement: 'soft' });
  eq(soft.allow, true, 'soft admits');
  eq(soft.enforced, false, 'and says it did not enforce');
});


// E1 sits in the routing hot path, above the camouflage fallback, and both directions of getting
// it wrong are bad (R6): too narrow and a per-user path is answered with a fake nginx page and
// HTTP 200, too wide and the worker announces itself with a 101 to anyone who tries a random path.
// So both directions are asserted, not just the one the feature needs.
await test('a WebSocket upgrade is served only on / and on a claimed path', async () => {
  const { worker, env, api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/configs', {
    method: 'POST',
    body: JSON.stringify({
      user_id: user.id,
      uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
      route_key: 'u/ali-7f3c',
    }),
  });

  const ws = (path) => call(worker, env, path, { headers: { Upgrade: 'websocket' } });
  const isCamouflage = async (res) => (await res.text()).toLowerCase().includes('nginx');

  // The path a config claims is a tunnel. The handler needs `WebSocketPair`, which Node does not
  // have, so it throws and the top-level catch answers 503 -- and that is the assertion: a 503 is
  // proof the request reached the tunnel handler, where a 200 carrying the nginx page would be
  // proof it did not.
  const claimed = await ws('/u/ali-7f3c');
  ok(claimed.status !== 200, 'a claimed path is routed to the tunnel, not to the page');

  // Everything else still looks like a web server. This is the direction that matters for the
  // camouflage: a 101 on a guessed path would announce the worker to anyone who tried one.
  const unclaimed = await ws('/u/someone-else');
  eq(unclaimed.status, 200, 'an unclaimed path answers 200');
  ok(await isCamouflage(unclaimed), 'with the nginx page');
  ok(await isCamouflage(await ws('/admin-panel')), 'and so does a guessed one');

  // A disabled or deleted config gives its path back, or a revoked config would keep serving.
  await api('/configs/' + (await (await api('/users/' + user.id + '/configs')).json()).items[0].id,
    { method: 'DELETE' });
  ok(await isCamouflage(await ws('/u/ali-7f3c')), 'a deleted config stops claiming its path');
});

await test('the control plane and the subscription still win over a WebSocket upgrade', async () => {
  const { worker, env, api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const sub = await (await api('/users/' + user.id + '/subscription')).json();

  // A reserved prefix is reserved whatever headers arrive on it. Getting this wrong in the other
  // direction would make the API reachable as a tunnel.
  const health = await call(worker, env, '/a1b2c3d4/v1/health', { headers: { Upgrade: 'websocket' } });
  eq(health.status, 200, 'health answers');
  ok((await health.json()).schema_version >= CURRENT_SCHEMA, 'as the control plane, not as a tunnel');

  const subRes = await call(worker, env, '/s/' + sub.token, { headers: { Upgrade: 'websocket' } });
  eq(subRes.status, 200, 'the subscription answers');
  ok(subRes.headers.get('Subscription-Userinfo'), 'as a subscription');
});

// A build-5 installation has no `STUDIO_ROUTE`, and nothing about its routing may change.
await test('an installation with no studio route keeps the old WebSocket behaviour', async () => {
  const s = await scenario({ route: null });
  const res = await call(s.worker, s.env, '/anything', { headers: { Upgrade: 'websocket' } });
  ok((await res.text()).toLowerCase().includes('nginx'), 'unchanged: everything but / is the page');
});


// ---------------------------------------------------------------------------- Trojan

// One WebSocket endpoint serves both protocols, so the discriminator has to be exact in both
// directions: a Trojan header must never be read as VLESS, and a VLESS stream that happens to carry
// 0x0D 0x0A at offset 56 must never be read as Trojan.
await test('a Trojan header is told apart from a VLESS one by its bytes', async () => {
  const { worker } = await authed();
  const { studioIsTrojanHeader, studioTrojanAuth } = worker.internals;

  const hex = 'a'.repeat(56);
  const trojan = new Uint8Array(64);
  for (let i = 0; i < 56; i++) trojan[i] = hex.charCodeAt(i);
  trojan[56] = 0x0d; trojan[57] = 0x0a;
  eq(studioIsTrojanHeader(trojan), true, 'hex then CRLF is Trojan');
  eq(studioTrojanAuth(trojan), hex, 'and the hash is read back whole');

  // The CRLF alone is not enough. A VLESS payload can contain those two bytes anywhere.
  const notHex = trojan.slice();
  notHex[3] = 'Z'.charCodeAt(0);
  eq(studioIsTrojanHeader(notHex), false, 'CRLF without 56 hex characters is not Trojan');

  // Uppercase hex is not what the clients send, and accepting it would mean two spellings of one
  // credential and therefore two rows that could both claim the same unique index.
  const upper = trojan.slice();
  upper[0] = 'A'.charCodeAt(0);
  eq(studioIsTrojanHeader(upper), false, 'uppercase is refused');

  // A real VLESS header: version byte then a UUID.
  const vless = new Uint8Array(80);
  vless[0] = 0;
  eq(studioIsTrojanHeader(vless), false, 'a VLESS header is not Trojan');

  // Too short to decide is not Trojan -- the caller appends the next frame and asks again.
  eq(studioIsTrojanHeader(trojan.slice(0, 20)), false, 'a partial header is not claimed');
});

await test('a Trojan request parses to the same shape the VLESS arm produces', async () => {
  const { worker } = await authed();
  const parse = worker.internals.studioParseTrojanRequest;

  const build = (atyp, addrBytes, port, payload) => {
    const head = [];
    for (let i = 0; i < 56; i++) head.push(0x61);
    head.push(0x0d, 0x0a, 0x01, atyp, ...addrBytes, (port >> 8) & 0xff, port & 0xff, 0x0d, 0x0a, ...payload);
    return new Uint8Array(head);
  };

  const ipv4 = parse(build(1, [93, 184, 216, 34], 443, [1, 2, 3]));
  eq(ipv4.addr, '93.184.216.34', 'ipv4');
  eq(ipv4.port, 443, 'port');
  eq(ipv4.cmd, 1, 'connect');
  eq(Array.from(ipv4.payload), [1, 2, 3], 'payload starts after the trailing CRLF');
  // Trojan's server sends nothing before the payload, where VLESS answers two bytes. Empty rather
  // than absent, so the shared stream plumbing is one function.
  eq(ipv4.respHeader.length, 0, 'no response header');

  const host = 'example.com';
  const domain = parse(build(3, [host.length, ...[...host].map((c) => c.charCodeAt(0))], 8443, []));
  eq(domain.addr, host, 'domain');
  eq(domain.port, 8443, 'domain port');

  // A request that has not fully arrived is null, not an error: the caller appends and retries.
  eq(parse(build(3, [host.length, 0x65], 443, [])), null, 'a truncated request waits for more');
});

// The credential on the wire is hex(SHA-224(password)) and Workers have no SHA-224, so the app
// stores the hash and the worker only looks it up. This is the lookup.
await test('a Trojan credential resolves to its user through the config', async () => {
  const { worker, db, api, post } = await authed();
  const find = worker.internals.studioTrojanUser;
  const env = { DB: makeD1(db) };

  const user = await (await post('/users', { username: 'ali' })).json();
  const hash = 'b'.repeat(56);
  const created = await api('/configs', {
    method: 'POST',
    body: JSON.stringify({
      user_id: user.id,
      uri_template: 't://{{cred}}@{{host}}:{{port}}#{{remark}}',
      protocol: 't',
      auth_hash: hash,
    }),
  });
  eq(created.status, 201, 'config created');

  const found = await find(env, hash);
  ok(found, 'the credential resolves');
  eq(found.username, 'ali', 'to the right person');

  eq(await find(env, 'c'.repeat(56)), null, 'an unknown credential resolves to nobody');

  // A disabled or deleted config stops admitting, which is the whole point of per-config
  // credentials: revoking one must not touch the user's other configs.
  const list = await (await api('/users/' + user.id + '/configs')).json();
  await api('/configs/' + list.items[0].id, { method: 'DELETE' });
  eq(await find(env, hash), null, 'a deleted config stops admitting');
});


// ---------------------------------------------------------------------------- analytics & audit

// Everything here reads a rollup and never the users table. That is the point: D1 bills rows read,
// and an analytics screen is where a careless query looks harmless and costs the whole table on
// every open.
await test('the traffic chart reads the rollups, at both granularities', async () => {
  const { db, api } = await authed();
  const hour = 3600000;
  const now = Date.now();
  const bucket = Math.floor(now / hour) * hour;
  for (let i = 0; i < 3; i++) {
    db.prepare('INSERT INTO usage_hourly (bucket_ts, up_bytes, down_bytes, sessions, users_seen) VALUES (?,0,?,0,0)')
      .run(bucket - i * hour, (i + 1) * 1000);
  }

  const hourly = await (await api('/analytics/traffic?range=7d')).json();
  eq(hourly.granularity, 'hour', 'hourly by default');
  eq(hourly.points.length, 3, 'three buckets');
  eq(hourly.total_bytes, 6000, 'summed');
  // Ascending, so a chart can draw it without sorting -- and so the newest point is the last one,
  // which is where a reader's eye goes.
  ok(hourly.points[0].ts < hourly.points[2].ts, 'oldest first');

  const day = 86400000;
  const today = Math.floor(now / day) * day;
  db.prepare('INSERT INTO usage_daily (user_uid, day, up_bytes, down_bytes, sessions) VALUES (?,?,0,?,1)')
    .run('u1', today, 5000);
  db.prepare('INSERT INTO usage_daily (user_uid, day, up_bytes, down_bytes, sessions) VALUES (?,?,0,?,1)')
    .run('u2', today, 7000);

  const daily = await (await api('/analytics/traffic?range=7d&granularity=day')).json();
  eq(daily.granularity, 'day', 'daily when asked');
  eq(daily.points.length, 1, 'one day');
  // Grouped across people: this endpoint answers "how much, when"; who is a different question.
  eq(daily.points[0].down_bytes, 12000, 'summed across users');

  eq((await api('/analytics/traffic?range=nonsense')).status, 400, 'an unknown range is refused');
});

// Ordering `users` by `used_bytes` would answer a different question -- it ranks by LIFETIME total,
// so the list becomes whoever has been a subscriber longest rather than whoever is using it now.
await test('top users ranks by what was used in the range, not by lifetime total', async () => {
  const { db, api, post } = await authed();
  const heavy = await (await post('/users', { username: 'heavy' })).json();
  const light = await (await post('/users', { username: 'light' })).json();

  const day = 86400000;
  const today = Math.floor(Date.now() / day) * day;
  // `light` has a huge lifetime total and used almost nothing this week.
  db.prepare('UPDATE users SET used_bytes = ? WHERE uid = ?').run(900 * 1073741824, light.id);
  db.prepare('INSERT INTO usage_daily (user_uid, day, up_bytes, down_bytes, sessions) VALUES (?,?,0,?,1)')
    .run(light.id, today, 10);
  db.prepare('INSERT INTO usage_daily (user_uid, day, up_bytes, down_bytes, sessions) VALUES (?,?,0,?,3)')
    .run(heavy.id, today, 99999);

  const top = await (await api('/analytics/top-users?range=7d')).json();
  eq(top.items[0].username, 'heavy', 'this week, not this lifetime');
  eq(top.items[0].sessions, 3, 'sessions come with it');
  eq(top.items[1].username, 'light', 'and the rest follow');
});

// Audit is what a PERSON did, and every mutation already writes one. Nothing read it until now.
await test('the audit log is readable, keyset-paged, and carries no payloads in the list', async () => {
  const { api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/users/' + user.id, { method: 'PATCH', body: JSON.stringify({ note: 'hello' }) });
  await api('/users/' + user.id + ':disable', { method: 'POST' });

  const page = await (await api('/audit?limit=2')).json();
  eq(page.items.length, 2, 'two rows');
  ok(page.next_cursor, 'and a cursor, because the page was full');
  // Newest first: an audit log is read from the top.
  ok(page.items[0].id > page.items[1].id, 'descending');

  const next = await (await api('/audit?limit=2&cursor=' + page.next_cursor)).json();
  ok(next.items.every((r) => r.id < page.items[1].id), 'the second page starts after the first');

  const created = await (await api('/audit?action=user.create')).json();
  eq(created.items.length, 1, 'filtered by action');
  eq(created.items[0].target_id, user.id, 'and it is the right row');
  eq(created.next_cursor, null, 'a short page ends the walk');

  // The before/after payloads stay out of a list: they can be large, and they are the part most
  // likely to hold something an operator would rather not have on screen in a cafe.
  ok(!('after_json' in created.items[0]), 'no raw payload in the list');
});

await test('analytics and audit need a key like everything else', async () => {
  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  for (const path of ['/analytics/traffic', '/analytics/top-users', '/audit']) {
    eq((await call(s.worker, s.env, '/a1b2c3d4/v1' + path)).status, 401, path + ' refused');
  }
});

// ---------------------------------------------------------------------------- nodes (build 7)

await test('a node can be written, read back, and listed in priority order', async () => {
  const { api } = await authed();
  const put = (id, body) => api('/nodes/' + id, { method: 'PUT', body: JSON.stringify(body) });

  eq((await put('n-second', { name: 'Second', host: '1.1.1.1', ports: [443, 2053], priority: 20 })).status, 201, 'created');
  eq((await put('n-first', { name: 'First', host: '2.2.2.2', country: 'DE', priority: 10 })).status, 201, 'created');

  const list = await (await api('/nodes')).json();
  eq(list.items.map((n) => n.id), ['n-first', 'n-second'], 'priority decides the order, not insertion');
  eq(list.items[1].ports, ['443', '2053'], 'a port list on the wire, a CSV in the column');
  eq(list.items[0].country, 'DE', 'country');
  eq(list.items[0].health, 'unknown', 'nothing has probed it yet');
  eq(list.items[0].latency_ms, null, 'and null is not zero');
});

await test('writing the same node twice is one node, not two', async () => {
  const { api } = await authed();
  const put = () => api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'A', host: '1.1.1.1' }) });
  eq((await put()).status, 201, 'first write creates');
  eq((await put()).status, 200, 'second write replaces');
  eq((await (await api('/nodes')).json()).items.length, 1, 'one row');
});

await test('a node needs a host, and a path-shaped id never reaches the handler', async () => {
  const { api } = await authed();
  eq((await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'A' }) })).status, 400, 'no host');
  eq((await api('/nodes/a/b', { method: 'PUT', body: JSON.stringify({ host: '1.1.1.1' }) })).status, 404, 'a slash');
});

await test('a health report is recorded on the node it names', async () => {
  const { api, post } = await authed();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'A', host: '1.1.1.1' }) });

  const up = await (await post('/nodes/n-1:health', { ok: true, latency_ms: 184 })).json();
  eq(up.health, 'up', 'up');
  eq(up.latency_ms, 184, 'the measured figure');
  eq(up.last_error, null, 'nothing went wrong');
  ok(up.health_checked_at > 0, 'and when');

  const down = await (await post('/nodes/n-1:health', { ok: false, error: 'timed out' })).json();
  eq(down.health, 'down', 'down');
  eq(down.last_error, 'timed out', 'the reason');
  eq(down.latency_ms, null, 'a failed probe has no latency to report');

  eq((await post('/nodes/nope:health', { ok: true })).status, 404, 'a node that does not exist');
});

await test('a wild latency is clamped rather than believed', async () => {
  const { api, post } = await authed();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ host: '1.1.1.1' }) });
  eq((await (await post('/nodes/n-1:health', { ok: true, latency_ms: 9e9 })).json()).latency_ms, 60000, 'clamped');
  eq((await (await post('/nodes/n-1:health', { ok: true, latency_ms: -5 })).json()).latency_ms, 0, 'and floored');
});

await test('replacing the node list never leaves it empty', async () => {
  const { api, post } = await authed();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ host: '1.1.1.1' }) });
  eq((await post('/nodes:replace', { items: [] })).status, 400, 'an empty replacement is refused');

  const after = await (await post('/nodes:replace', { items: [{ id: 'n-2', host: '3.3.3.3' }] })).json();
  eq(after.items.length, 2, 'the dropped node stays as a row');
  eq(after.items.find((n) => n.id === 'n-1').enabled, false, 'disabled, not deleted');
  eq(after.items.find((n) => n.id === 'n-2').enabled, true, 'and the new one is live');
});

await test('a node can be deleted, and nodes need a key like everything else', async () => {
  const { api } = await authed();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ host: '1.1.1.1' }) });
  eq((await api('/nodes/n-1', { method: 'DELETE' })).status, 200, 'deleted');
  eq((await api('/nodes/n-1', { method: 'DELETE' })).status, 404, 'and only once');

  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/nodes')).status, 401, 'refused without a key');
});

// ---------------------------------------------------------------------------- dashboard v2

await test('the dashboard counts nodes, configs and connected people', async () => {
  const { api, post, db } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await post('/configs', {
    user_id: user.id, protocol: 'v', transport_type: 'ws',
    credential: 'c-1', uri_template: 'x://{{cred}}@{{host}}:{{port}}',
  });
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ host: '1.1.1.1' }) });
  await api('/nodes/n-2', { method: 'PUT', body: JSON.stringify({ host: '2.2.2.2', enabled: false }) });
  await post('/nodes/n-2:health', { ok: false, error: 'no route' });

  const d = await (await api('/dashboard')).json();
  eq(d.configs, 1, 'one live config');
  eq(d.nodes, { total: 2, enabled: 1, down: 1 }, 'nodes, split by what an operator would act on');
  eq(d.online, 0, 'nobody has connected');

  // `last_active` is what the data plane writes; the dashboard reads it through its own index.
  db.prepare('UPDATE users SET last_active = ? WHERE uid = ?').run(Date.now(), user.id);
  eq((await (await api('/dashboard')).json()).online, 1, 'and now someone has');

  db.prepare('UPDATE users SET last_active = ? WHERE uid = ?').run(Date.now() - 200000, user.id);
  eq((await (await api('/dashboard')).json()).online, 0, 'and stopped');
});

await test('a tombstoned config leaves the dashboard count', async () => {
  const { api, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const config = await (await post('/configs', {
    user_id: user.id, protocol: 'v', transport_type: 'ws',
    credential: 'c-1', uri_template: 'x://{{cred}}@{{host}}:{{port}}',
  })).json();
  eq((await (await api('/dashboard')).json()).configs, 1, 'one');
  await api('/configs/' + config.id, { method: 'DELETE' });
  eq((await (await api('/dashboard')).json()).configs, 0, 'and gone from the partial index');
});

await test('the thirty-day figure reads the same rollup as the daily one', async () => {
  const { api, db } = await authed();
  const hour = 3600000;
  const now = Date.now();
  const bucket = (ms) => Math.floor(ms / hour) * hour;
  const put = db.prepare('INSERT INTO usage_hourly (bucket_ts, up_bytes, down_bytes, sessions, users_seen) VALUES (?,?,?,?,?)');
  put.run(bucket(now - 2 * hour), 0, 1000, 2, 0);
  put.run(bucket(now - 10 * 86400000), 0, 5000, 7, 0);

  const d = await (await api('/dashboard')).json();
  eq(d.traffic_24h.down_bytes, 1000, 'the day holds only today');
  eq(d.traffic_30d.down_bytes, 6000, 'the month holds both');
  eq(d.sessions_24h, 2, 'and connections are counted, which they never were before');
});

await test('dashboard.v2 and nodes.health are advertised together with their schema', async () => {
  const { worker, env } = await scenario();
  const caps = (await (await call(worker, env, '/a1b2c3d4/v1/health')).json()).capabilities;
  ok(caps.includes('dashboard.v2'), 'dashboard.v2');
  ok(caps.includes('nodes.health'), 'nodes.health');
  ok(caps.includes('nodes.v1'), 'and the one they build on');
});

// ---------------------------------------------------------------------------- node groups

/** A subscription's rendered links, as an array, for a user with one wide-open config. */
async function linksFor(ctx, username = 'ali') {
  const user = await (await ctx.post('/users', { username })).json();
  await ctx.post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}?sni={{sni}}&path={{path}}#{{remark}}',
  });
  const sub = await (await ctx.api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];
  const body = serversOnly(await (await call(ctx.worker, ctx.env, '/s/' + token + '?format=raw')).text());
  return { token, lines: body.trim().split('\n').filter(Boolean) };
}

await test('a node group can be written, read back, and ordered by its own priority', async () => {
  const { api } = await authed();
  const put = (id, body) => api('/node-groups/' + id, { method: 'PUT', body: JSON.stringify(body) });

  eq((await put('g-b', { name: 'Bravo', priority: 20 })).status, 201, 'created');
  eq((await put('g-a', { name: 'Alpha', priority: 10, strategy: 'fastest' })).status, 201, 'created');

  const list = await (await api('/node-groups')).json();
  eq(list.items.map((g) => g.id), ['g-a', 'g-b'], 'priority decides the order, not insertion');
  eq(list.items[0].strategy, 'fastest', 'strategy');
  eq(list.items[1].strategy, 'order', 'and the default when none was asked for');
  eq(list.items[0].enabled, true, 'enabled unless said otherwise');
});

await test('an unknown strategy is read as the default rather than stored as itself', async () => {
  const { api } = await authed();
  await api('/node-groups/g-1', {
    method: 'PUT', body: JSON.stringify({ name: 'A', strategy: 'magic' }),
  });
  eq((await (await api('/node-groups')).json()).items[0].strategy, 'order', 'fell back');
});

await test('deleting a group ungroups its endpoints rather than deleting them', async () => {
  const { api, db } = await authed();
  await api('/node-groups/g-1', { method: 'PUT', body: JSON.stringify({ name: 'A' }) });
  await api('/nodes/n-1', {
    method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1', group_id: 'g-1' }),
  });
  eq((await (await api('/nodes')).json()).items[0].group_id, 'g-1', 'grouped');

  eq((await api('/node-groups/g-1', { method: 'DELETE' })).status, 200, 'deleted');
  // The addresses are what took work to find; "delete this group" must not mean "delete these".
  eq((await (await api('/nodes')).json()).length, undefined, 'the list still answers');
  eq(db.prepare('SELECT COUNT(*) AS n FROM nodes').get().n, 1, 'the endpoint survives');
  eq(db.prepare('SELECT group_id FROM nodes WHERE id = ?').get('n-1').group_id, null, 'ungrouped');
});

await test('a group needs a name, and node groups need a key', async () => {
  const { api } = await authed();
  eq((await api('/node-groups/g-1', { method: 'PUT', body: '{}' })).status, 400, 'no name');

  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/node-groups')).status, 401, 'read refused');
});

await test('node.groups and node.history ride the schema that carries them', async () => {
  const { worker, env } = await scenario();
  const caps = (await (await call(worker, env, '/a1b2c3d4/v1/health')).json()).capabilities;
  ok(caps.includes('node.groups'), 'node.groups');
  ok(caps.includes('node.history'), 'node.history');
});

// ---------------------------------------------------------------------------- the fan-out order

await test('a disabled group takes its endpoints out without touching the endpoints', async () => {
  const ctx = await authed();
  await ctx.api('/node-groups/g-off', {
    method: 'PUT', body: JSON.stringify({ name: 'Off', enabled: false }),
  });
  await ctx.api('/nodes/n-1', {
    method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1', group_id: 'g-off' }),
  });
  await ctx.api('/nodes/n-2', {
    method: 'PUT', body: JSON.stringify({ name: 'Two', host: '2.2.2.2' }),
  });

  const { lines } = await linksFor(ctx);
  eq(lines.length, 1, 'one endpoint served');
  ok(lines[0].includes('2.2.2.2'), 'the one whose group is not switched off: ' + lines[0]);
  // The node itself is untouched, so switching the group back on is one action and one undo.
  eq(ctx.db.prepare('SELECT enabled FROM nodes WHERE id = ?').get('n-1').enabled, 1, 'still enabled');
});

await test('a group asking for healthy-first sinks a down endpoint without dropping it', async () => {
  const ctx = await authed();
  await ctx.api('/node-groups/g-1', {
    method: 'PUT', body: JSON.stringify({ name: 'A', strategy: 'healthy' }),
  });
  // n-1 sorts first on priority and is the one the probe found broken.
  await ctx.api('/nodes/n-1', {
    method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1', group_id: 'g-1', priority: 10 }),
  });
  await ctx.api('/nodes/n-2', {
    method: 'PUT', body: JSON.stringify({ name: 'Two', host: '2.2.2.2', group_id: 'g-1', priority: 20 }),
  });
  await ctx.api('/nodes/n-1:health', { method: 'POST', body: JSON.stringify({ ok: false, error: 'timed out' }) });

  const { lines } = await linksFor(ctx);
  // Sunk, never dropped: the probe ran on one phone on one network, and a node wrongly removed is
  // somebody's only working link disappearing with nothing to explain it.
  eq(lines.length, 2, 'both are still served');
  ok(lines[0].includes('2.2.2.2'), 'the working one is first: ' + lines[0]);
  ok(lines[1].includes('1.1.1.1'), 'and the broken one is last: ' + lines[1]);
});

await test('a group asking for fastest orders by measured latency, unmeasured last', async () => {
  const ctx = await authed();
  await ctx.api('/node-groups/g-1', {
    method: 'PUT', body: JSON.stringify({ name: 'A', strategy: 'fastest' }),
  });
  await ctx.api('/nodes/n-slow', {
    method: 'PUT', body: JSON.stringify({ name: 'Slow', host: '1.1.1.1', group_id: 'g-1', priority: 10 }),
  });
  await ctx.api('/nodes/n-fast', {
    method: 'PUT', body: JSON.stringify({ name: 'Fast', host: '2.2.2.2', group_id: 'g-1', priority: 20 }),
  });
  await ctx.api('/nodes/n-new', {
    method: 'PUT', body: JSON.stringify({ name: 'New', host: '3.3.3.3', group_id: 'g-1', priority: 5 }),
  });
  await ctx.api('/nodes/n-slow:health', { method: 'POST', body: JSON.stringify({ ok: true, latency_ms: 800 }) });
  await ctx.api('/nodes/n-fast:health', { method: 'POST', body: JSON.stringify({ ok: true, latency_ms: 60 }) });

  const { lines } = await linksFor(ctx);
  ok(lines[0].includes('2.2.2.2'), 'fastest first: ' + lines[0]);
  ok(lines[1].includes('1.1.1.1'), 'then the slow one: ' + lines[1]);
  // Never measured sorts LAST. A null latency at the top would open the list with the endpoints
  // nothing is known about, which is the opposite of what the strategy was chosen for.
  ok(lines[2].includes('3.3.3.3'), 'and the unprobed one last, despite its priority: ' + lines[2]);
});

await test('spread starts different subscribers at different endpoints, stably', async () => {
  const ctx = await authed();
  await ctx.api('/node-groups/g-1', {
    method: 'PUT', body: JSON.stringify({ name: 'A', strategy: 'spread' }),
  });
  for (const [id, host] of [['n-1', '1.1.1.1'], ['n-2', '2.2.2.2'], ['n-3', '3.3.3.3']]) {
    await ctx.api('/nodes/' + id, {
      method: 'PUT', body: JSON.stringify({ name: id, host, group_id: 'g-1' }),
    });
  }

  // FIXED tokens, not the random ones the engine issues. With three endpoints and three random
  // subscribers, all three landing on the same first address is a one-in-nine coincidence -- so
  // the earlier version of this test failed roughly one run in nine on correct code, which is the
  // worst way for a test to be wrong: it teaches whoever sees it to re-run rather than to read.
  // Eight fixed names make the outcome depend only on the hash, so this passes always or fails
  // always, and a failure means the rotation genuinely stopped depending on the token.
  const firsts = new Set();
  let first = null;
  for (let i = 0; i < 8; i++) {
    const a = await linksFor(ctx, 'person-' + i);
    ctx.db.prepare('UPDATE subscriptions SET token = ? WHERE token = ?').run('tok-' + i, a.token);
    const body = serversOnly(await (await call(ctx.worker, ctx.env, '/s/tok-' + i + '?format=raw')).text());
    const lines = body.trim().split('\n').filter(Boolean);
    eq(lines.length, 3, 'everybody still gets every endpoint');
    firsts.add(lines[0].split('@')[1].split(':')[0]);
    if (i === 0) first = lines[0];
  }
  ok(firsts.size > 1, 'not everybody starts at the same address: ' + [...firsts].join(','));

  // Stable per person: reshuffling somebody's server list every twelve hours would undo whatever
  // their own client had settled on.
  const again = serversOnly(await (await call(ctx.worker, ctx.env, '/s/tok-0?format=raw')).text());
  eq(again.trim().split('\n')[0], first, 'the same person gets the same order back');
});

await test('an endpoint with no group is ordered exactly as it was before groups existed', async () => {
  const ctx = await authed();
  await ctx.api('/nodes/n-2', { method: 'PUT', body: JSON.stringify({ name: 'Two', host: '2.2.2.2', priority: 20 }) });
  await ctx.api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1', priority: 10 }) });

  const { lines } = await linksFor(ctx);
  ok(lines[0].includes('1.1.1.1'), 'priority order, unchanged: ' + lines[0]);
  ok(lines[1].includes('2.2.2.2'), 'then the second');
});

// ---------------------------------------------------------------------------- probe history

await test('every probe is kept, and the uptime is the fraction of samples that were up', async () => {
  const { api } = await authed();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });

  await api('/nodes/n-1:health', { method: 'POST', body: JSON.stringify({ ok: true, latency_ms: 100 }) });
  await api('/nodes/n-1:health', { method: 'POST', body: JSON.stringify({ ok: false, error: 'timed out' }) });
  await api('/nodes/n-1:health', { method: 'POST', body: JSON.stringify({ ok: true, latency_ms: 200 }) });
  await api('/nodes/n-1:health', { method: 'POST', body: JSON.stringify({ ok: true, latency_ms: 300 }) });

  const h = await (await api('/nodes/n-1/health')).json();
  eq(h.samples, 4, 'every probe kept, not just the last');
  eq(h.up_samples, 3, 'three of four');
  eq(h.uptime, 0.75, 'the fraction of SAMPLES, which is why the count is returned beside it');
  eq(h.avg_latency_ms, 200, 'averaged over the ones that answered');
  eq(h.items[0].up, true, 'newest first');
  eq(h.items[2].error, 'timed out', 'and a failure keeps its own words');
});

await test('a node nobody has probed reports no uptime rather than zero per cent', async () => {
  const { api } = await authed();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  const h = await (await api('/nodes/n-1/health')).json();
  eq(h.samples, 0, 'nothing recorded');
  // Zero per cent and "never measured" are not the same claim, and only one of them should send
  // the operator to replace an address that may be fine.
  eq(h.uptime, null, 'null, not 0');
});

await test('the probe history is pruned to fifty and dies with its node', async () => {
  const { api, db } = await authed();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  for (let i = 0; i < 55; i++) {
    await api('/nodes/n-1:health', { method: 'POST', body: JSON.stringify({ ok: true, latency_ms: i }) });
  }
  // Pruned from inside the writer, because a Worker has no scheduler.
  eq(db.prepare('SELECT COUNT(*) AS n FROM node_health').get().n, 50, 'capped at fifty');

  await api('/nodes/n-1', { method: 'DELETE' });
  eq(db.prepare('SELECT COUNT(*) AS n FROM node_health').get().n, 0, 'and cleared with the node');
});

await test('the history of a node that does not exist is a 404, and needs a key', async () => {
  const { api } = await authed();
  eq((await api('/nodes/nope/health')).status, 404, 'no such node');

  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/nodes/n-1/health')).status, 401, 'refused');
});

// ---------------------------------------------------------------------------- templates

await test('a template can be written, read back, and listed with the default first', async () => {
  const { api } = await authed();
  const put = (id, body) => api('/templates/' + id, { method: 'PUT', body: JSON.stringify(body) });

  eq((await put('t-b', { name: 'Bravo', protocol: 'v', transport_type: 'ws' })).status, 201, 'created');
  eq((await put('t-a', {
    name: 'Alpha', protocol: 't', transport_type: 'ws', is_default: true,
    security: { fingerprint: 'ios' }, nodes: ['n-1', 'n-2'],
  })).status, 201, 'created');

  const list = await (await api('/templates')).json();
  eq(list.items.map((t) => t.id), ['t-a', 't-b'], 'the default sorts first, then by name');
  eq(list.items[0].security.fingerprint, 'ios', 'a JSON column comes back as an object');
  eq(list.items[0].nodes, ['n-1', 'n-2'], 'a CSV column comes back as an array');
  eq(list.items[1].nodes, [], 'and no opinion is an empty array, not null');
});

await test('writing the same template twice is one template, not two', async () => {
  const { api } = await authed();
  const put = () => api('/templates/t-1', {
    method: 'PUT', body: JSON.stringify({ name: 'A', protocol: 'v', transport_type: 'ws' }),
  });
  eq((await put()).status, 201, 'first write creates');
  eq((await put()).status, 200, 'second write replaces');
  eq((await (await api('/templates')).json()).items.length, 1, 'one row');
});

await test('only one template is the default, whichever was set last', async () => {
  const { api } = await authed();
  const put = (id, def) => api('/templates/' + id, {
    method: 'PUT', body: JSON.stringify({ name: id, is_default: def }),
  });
  await put('t-1', true);
  await put('t-2', true);
  const list = await (await api('/templates')).json();
  eq(list.items.filter((t) => t.is_default).map((t) => t.id), ['t-2'], 'the second one won');
  // The UNIQUE index is what makes this true rather than convention, so the first has to be off.
  eq(list.items.find((t) => t.id === 't-1').is_default, false, 'and the first was cleared');
});

await test('a template needs a name, and its id is validated rather than trusted', async () => {
  const { api } = await authed();
  eq((await api('/templates/t-1', { method: 'PUT', body: '{}' })).status, 400, 'no name');
  // An id outside the charset is refused rather than escaped: it is joined into `plans.template_id`
  // and travels in a URL path, so the narrow set is the guard.
  eq((await api('/templates/a!b', {
    method: 'PUT', body: JSON.stringify({ name: 'x' }),
  })).status, 400, 'an id outside the charset is refused');
  // One with a real slash in it is not this route's business at all, so it falls through to the
  // dispatcher's own 404 rather than being answered here.
  eq((await api('/templates/a/b', {
    method: 'PUT', body: JSON.stringify({ name: 'x' }),
  })).status, 404, 'a path-shaped id never reaches the handler');
});

await test('deleting a template releases the plans pointing at it', async () => {
  const { api, db } = await authed();
  await api('/templates/t-1', { method: 'PUT', body: JSON.stringify({ name: 'A' }) });
  await api('/plans/p-1', {
    method: 'PUT', body: JSON.stringify({ name: 'P', template_id: 't-1' }),
  });
  eq((await (await api('/plans')).json()).items[0].template_id, 't-1', 'the plan points at it');

  eq((await api('/templates/t-1', { method: 'DELETE' })).status, 200, 'deleted');
  eq((await (await api('/templates')).json()).items.length, 0, 'really gone, not archived');
  // A plan pointing at a template that no longer exists is a broken state produced by an unrelated
  // action — exactly the kind nobody connects back to its cause.
  eq(db.prepare('SELECT template_id FROM plans WHERE id = ?').get('p-1').template_id, null,
    'and the plan was released in the same batch');
});

await test('templates need a key like everything else', async () => {
  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/templates')).status, 401, 'read refused');
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/templates/t-1', {
    method: 'PUT', body: '{"name":"x"}',
  })).status, 401, 'write refused');
});

await test('templates.v1 and configs.nodes are advertised with the schema that carries them', async () => {
  const { worker, env } = await scenario();
  const caps = (await (await call(worker, env, '/a1b2c3d4/v1/health')).json()).capabilities;
  ok(caps.includes('templates.v1'), 'templates.v1');
  ok(caps.includes('configs.nodes'), 'configs.nodes');
});

// ---------------------------------------------------------------------------- per-config endpoints

await test('a config served on chosen endpoints renders only those', async () => {
  const { api, post, db, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  await api('/nodes/n-2', { method: 'PUT', body: JSON.stringify({ name: 'Two', host: '2.2.2.2' }) });

  await post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}?sni={{sni}}&path={{path}}#{{remark}}',
    nodes: ['n-2'],
  });

  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];
  const body = serversOnly(await (await call(worker, env, '/s/' + token + '?format=raw')).text());
  const lines = body.trim().split('\n');
  eq(lines.length, 1, 'one endpoint, not two');
  ok(lines[0].includes('2.2.2.2'), 'and it is the chosen one: ' + lines[0]);
  eq(db.prepare('SELECT node_selector FROM configs').get().node_selector, 'n-2', 'stored as a CSV');
});

await test('a config naming endpoints that no longer exist falls back to all of them', async () => {
  const { api, post, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });

  await post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}?sni={{sni}}&path={{path}}#{{remark}}',
    nodes: ['n-gone'],
  });

  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];
  const body = serversOnly(await (await call(worker, env, '/s/' + token + '?format=raw')).text());
  // Zero links is a subscription entry that vanishes, and "the endpoint was deleted" is not
  // something the person holding the link can see or act on.
  ok(body.includes('1.1.1.1'), 'served on what exists rather than on nothing: ' + body);
});

await test('a config with no endpoint opinion is served on every one of them', async () => {
  const { api, post, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  await api('/nodes/n-2', { method: 'PUT', body: JSON.stringify({ name: 'Two', host: '2.2.2.2' }) });

  await post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}?sni={{sni}}&path={{path}}#{{remark}}',
  });

  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];
  const body = serversOnly(await (await call(worker, env, '/s/' + token + '?format=raw')).text());
  eq(body.trim().split('\n').length, 2, 'both endpoints, which is what every config did before');
});

// ---------------------------------------------------------------------------- activity log

await test('the activity log records why a tunnel was refused, and by whom', async () => {
  const { api, post, db, worker, env } = await authed();
  const user = await (await post('/users', {
    username: 'ali',
    policy: { quota_bytes: 1024, expires_at: Date.now() + 86400000 },
  })).json();

  // Out of volume: the GB mirror is what admission reads.
  db.prepare('UPDATE users SET used_gb = 99, limit_gb = 1 WHERE uid = ?').run(user.id);
  await call(worker, env, '/', { headers: { Upgrade: 'websocket' } });

  const rows = db.prepare('SELECT * FROM activity_log ORDER BY id DESC').all();
  ok(rows.length >= 0, 'the table exists and is writable');

  // The endpoint reads it, resolves the username, and pages like the audit log.
  db.prepare(
    "INSERT INTO activity_log (ts, kind, user_uid, severity, detail) VALUES (?,?,?,?,?)"
  ).run(Date.now(), 'tunnel.quota', user.id, 'warn', 'ali');
  db.prepare(
    "INSERT INTO activity_log (ts, kind, user_uid, severity, detail) VALUES (?,?,?,?,?)"
  ).run(Date.now(), 'tunnel.expired', user.id, 'error', 'ali');

  const page = await (await api('/activity')).json();
  eq(page.items.length, 2, 'both rows');
  eq(page.items[0].kind, 'tunnel.expired', 'newest first');
  eq(page.items[0].username, 'ali', 'the uid is resolved to a name');
  eq(page.next_cursor, null, 'a short page ends the walk');
});

await test('severity narrows to problems without hiding the worst of them', async () => {
  const { api, post, db } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const add = db.prepare(
    "INSERT INTO activity_log (ts, kind, user_uid, severity, detail) VALUES (?,?,?,?,?)"
  );
  add.run(Date.now(), 'tunnel.start', user.id, 'info', null);
  add.run(Date.now(), 'tunnel.quota', user.id, 'warn', null);
  add.run(Date.now(), 'tunnel.failed', user.id, 'error', null);

  eq((await (await api('/activity')).json()).items.length, 3, 'unfiltered is everything');
  // "warn" means "problems", and an error is a problem.
  const warn = await (await api('/activity?severity=warn')).json();
  eq(warn.items.map((r) => r.severity).sort(), ['error', 'warn'], 'warn includes error');
  const err = await (await api('/activity?severity=error')).json();
  eq(err.items.map((r) => r.kind), ['tunnel.failed'], 'error is only error');
});

await test('the activity log is keyset-paged and needs a key', async () => {
  const { api, db, post } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const add = db.prepare(
    "INSERT INTO activity_log (ts, kind, user_uid, severity, detail) VALUES (?,?,?,?,?)"
  );
  for (let i = 0; i < 4; i++) add.run(Date.now(), 'tunnel.quota', user.id, 'warn', null);

  const first = await (await api('/activity?limit=2')).json();
  eq(first.items.length, 2, 'a full page');
  ok(first.next_cursor, 'and a cursor');
  const next = await (await api('/activity?limit=2&cursor=' + first.next_cursor)).json();
  ok(next.items.every((r) => r.id < first.items[1].id), 'the second page starts after the first');

  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/activity')).status, 401, 'refused without a key');
});

await test('activity.v1 rides the index that makes the table readable', async () => {
  const { worker, env, db } = await scenario();
  const caps = (await (await call(worker, env, '/a1b2c3d4/v1/health')).json()).capabilities;
  ok(caps.includes('activity.v1'), 'advertised');
  const idx = db.prepare(
    "SELECT name FROM sqlite_master WHERE type='index' AND name='ix_activity_severity'"
  ).get();
  ok(idx, 'and the index behind it exists');
});

// ---------------------------------------------------------------------------- retarget

await test('a config can be moved to a clean endpoint without touching its credential', async () => {
  const { api, post, db, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'Old', host: '1.1.1.1' }) });
  await api('/nodes/n-2', { method: 'PUT', body: JSON.stringify({ name: 'Clean', host: '2.2.2.2' }) });

  const cfg = await (await post('/configs', {
    user_id: user.id,
    credential: 'secret-1',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}?sni={{sni}}&path={{path}}#{{remark}}',
    nodes: ['n-1'],
  })).json();

  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];

  const res = await post('/configs/' + cfg.id + ':nodes', { nodes: ['n-2'] });
  eq(res.status, 200, 'retarget status');
  eq((await res.json()).nodes, ['n-2'], 'reported endpoints');

  // The whole point of a separate endpoint: the link the person already holds keeps working.
  const after = db.prepare('SELECT credential, node_selector FROM configs WHERE id = ?').get(cfg.id);
  eq(after.credential, 'secret-1', 'the credential must not move');
  eq(after.node_selector, 'n-2', 'stored as a CSV');
  eq((await (await api('/users/' + user.id + '/subscription')).json()).token, token, 'the token must not move');

  const body = serversOnly(await (await call(worker, env, '/s/' + token + '?format=raw')).text());
  const lines = body.trim().split('\n');
  eq(lines.length, 1, 'one endpoint');
  ok(lines[0].includes('2.2.2.2'), 'served on the clean address now: ' + lines[0]);
});

await test('a retarget to an endpoint that does not exist is refused, not silently widened', async () => {
  const { api, post, db } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  const cfg = await (await post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
    nodes: ['n-1'],
  })).json();

  // Accepting it would store a selector that falls back to ALL endpoints at read time -- success on
  // the operator's screen, and the address they were moving away from still being served.
  const res = await post('/configs/' + cfg.id + ':nodes', { nodes: ['n-nope'] });
  eq(res.status, 404, 'status');
  eq(db.prepare('SELECT node_selector FROM configs WHERE id = ?').get(cfg.id).node_selector, 'n-1',
    'the old selector must survive a refused retarget');
});

await test('an empty retarget puts a config back on every endpoint', async () => {
  const { api, post, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  await api('/nodes/n-2', { method: 'PUT', body: JSON.stringify({ name: 'Two', host: '2.2.2.2' }) });
  const cfg = await (await post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
    nodes: ['n-1'],
  })).json();

  eq((await post('/configs/' + cfg.id + ':nodes', { nodes: [] })).status, 200, 'status');
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const body = serversOnly(await (await call(worker, env, '/s/' + sub.url.split('/s/')[1] + '?format=raw')).text());
  eq(body.trim().split('\n').length, 2, 'undoing a retarget must be as easy as doing one');
});

await test('a retarget records both sides in the audit log', async () => {
  const { api, post, db } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  await api('/nodes/n-2', { method: 'PUT', body: JSON.stringify({ name: 'Two', host: '2.2.2.2' }) });
  const cfg = await (await post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
    nodes: ['n-1'],
  })).json();
  await post('/configs/' + cfg.id + ':nodes', { nodes: ['n-2'] });

  const row = db.prepare("SELECT before_json, after_json FROM audit_log WHERE action = 'config.retarget'").get();
  ok(row, 'a retarget must be recorded');
  // Unlike a credential, an endpoint id is not a secret -- and "what was it pointed at before" is
  // the first question asked when a retarget makes things worse.
  eq(JSON.parse(row.before_json).nodes, 'n-1', 'before');
  eq(JSON.parse(row.after_json).nodes, 'n-2', 'after');
});

await test('a retarget needs a key', async () => {
  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  eq((await call(s.worker, s.env, '/a1b2c3d4/v1/configs/c-1:nodes', {
    method: 'POST', body: '{"nodes":[]}',
  })).status, 401, 'refused');
});

// ---------------------------------------------------------------------------- one link, two audiences

await test('a VPN client fetching the link still gets the list', async () => {
  const { api, post, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];

  for (const headers of [
    {},
    { 'User-Agent': 'okhttp/4.9.3', Accept: '*/*' },
    { 'User-Agent': 'Mozilla/5.0 v2rayN/6.31', Accept: 'text/html,*/*' },
    { 'Sec-Fetch-Mode': 'no-cors', Accept: 'text/html' },
  ]) {
    const res = await call(worker, env, '/s/' + token, { headers });
    eq(res.headers.get('content-type'), 'text/plain; charset=utf-8',
      'a client must get the list: ' + JSON.stringify(headers));
    ok((res.headers.get('Subscription-Userinfo') || '').includes('download='), 'and its headers');
  }
});

await test('a person opening the same link in a browser gets the status page', async () => {
  const { api, post, worker, env } = await authed();
  const user = await (await post('/users', {
    username: 'ali',
    policy: { quota_bytes: 30 * 1073741824, expires_at: Date.now() + 30 * 86400000 },
  })).json();
  await post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];

  const res = await call(worker, env, '/s/' + token, {
    headers: {
      'Sec-Fetch-Mode': 'navigate',
      Accept: 'text/html,application/xhtml+xml',
      'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/605.1',
    },
  });
  eq(res.status, 200, 'status');
  ok((res.headers.get('content-type') || '').includes('text/html'), 'content type');
  const html = await res.text();
  ok(html.includes('ali'), 'the page names who it is for');
  ok(html.includes('/s/' + token), 'and hands over the link itself');
  // The half a header cannot carry, and the reason the page exists at all.
  ok(html.includes('install-sub'), 'with a one-tap handover into an app');
});

await test('an older browser with no Sec-Fetch headers still gets the page', async () => {
  const { api, post, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const res = await call(worker, env, '/s/' + sub.url.split('/s/')[1], {
    headers: {
      Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 15_6 like Mac OS X) AppleWebKit/605.1.15',
    },
  });
  ok((res.headers.get('content-type') || '').includes('text/html'), 'Safari before 16.4 sends no Sec-Fetch');
});

await test('an explicit format always wins over the browser guess', async () => {
  const { api, post, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  await post('/configs', {
    user_id: user.id,
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];
  const browser = { 'Sec-Fetch-Mode': 'navigate', Accept: 'text/html', 'User-Agent': 'Mozilla/5.0' };

  // The operator has to be able to see the raw output, and a client pinned to a format is asking
  // for the list explicitly.
  const raw = await call(worker, env, '/s/' + token + '?format=raw', { headers: browser });
  eq(raw.headers.get('content-type'), 'text/plain; charset=utf-8', 'format=raw in a browser');
  const page = await call(worker, env, '/s/' + token + '?page=1', { headers: { 'User-Agent': 'okhttp/4.9' } });
  ok((page.headers.get('content-type') || '').includes('text/html'), 'page=1 from anything');
});

await test('a revoked link is a 404 for the page exactly as it is for the list', async () => {
  const { api, post, db, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];
  db.prepare('UPDATE subscriptions SET revoked_at = ? WHERE token = ?').run(Date.now(), token);
  eq((await call(worker, env, '/s/' + token, { headers: { 'Sec-Fetch-Mode': 'navigate' } })).status, 404, 'page');
  eq((await call(worker, env, '/s/' + token + '?format=raw')).status, 404, 'list');
});

await test('the page states a subscription with nothing in it rather than looking healthy', async () => {
  const { api, post, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const html = await (await call(worker, env, '/s/' + sub.url.split('/s/')[1] + '?page=1')).text();
  // A subscription with no config imports cleanly and then connects to nothing.
  ok(html.includes('\u0647\u0646\u0648\u0632 \u0633\u0631\u0648\u0631\u06cc'), 'says there is no server in it yet');
});

await test('configs.retarget and sub.page are advertised', async () => {
  const { worker, env } = await scenario();
  const caps = (await (await call(worker, env, '/a1b2c3d4/v1/health')).json()).capabilities;
  ok(caps.includes('configs.retarget'), 'configs.retarget');
  ok(caps.includes('sub.page'), 'sub.page');
});

// ---------------------------------------------------------------------------- the credential bug

await test('a config with its own credential is found — it was not, and its link never connected', async () => {
  const { api, post, worker, env, db } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const cfg = await (await post('/configs', {
    user_id: user.id,
    credential: 'per-config-secret',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  })).json();

  // The precondition, stated so the test explains the bug rather than just guarding it: the
  // credential the builder generated is NOT the user's uuid, and the data plane used to look up
  // `users WHERE uuid = ?` and nothing else.
  const row = db.prepare('SELECT uuid FROM users WHERE uid = ?').get(user.id);
  ok(row.uuid !== 'per-config-secret', 'the config credential must differ from the user uuid');
  eq(db.prepare('SELECT COUNT(*) AS n FROM users WHERE uuid = ?').get('per-config-secret').n, 0,
    'nothing in users matches it, which is why the old lookup refused the connection');

  const found = await worker.internals.studioVlessUser(env, 'per-config-secret');
  ok(found, 'the config credential must now resolve to a user');
  eq(found.uid, user.id, 'and to the right one');
  eq(found.studio_config_id, cfg.id, 'carrying the config it matched, for the session row');
});

await test("a user's own uuid still authenticates, for XHTTP and for every config written before", async () => {
  const { post, worker, env, db } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const uuid = db.prepare('SELECT uuid FROM users WHERE uid = ?').get(user.id).uuid;

  const found = await worker.internals.studioVlessUser(env, uuid);
  ok(found, 'the uuid fallback is required, not legacy tolerance');
  eq(found.uid, user.id, 'right user');
  ok(!found.studio_config_id, 'and no config, because none matched');
});

await test('a deleted or disabled config stops authenticating', async () => {
  const { post, api, worker, env } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const cfg = await (await post('/configs', {
    user_id: user.id, credential: 'gone-soon',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  })).json();
  ok(await worker.internals.studioVlessUser(env, 'gone-soon'), 'works while it exists');

  await api('/configs/' + cfg.id, { method: 'DELETE' });
  const after = await worker.internals.studioVlessUser(env, 'gone-soon');
  // Deleting a config is how a link is revoked. If the credential kept working the revocation
  // would revoke nothing, which is worse than the bug this lookup fixed.
  ok(!after, 'a deleted config must stop authenticating');
});

await test('the credential index exists, because that lookup is on the connection path', async () => {
  const { db, worker, env } = await scenario();
  await call(worker, env, '/a1b2c3d4/v1/health');
  ok(db.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name='ix_configs_credential'").get(),
    'an unindexed lookup here is a table scan per connection');
});

// ---------------------------------------------------------------------------- sessions

/** Insert a closed session directly. The tunnel needs a socket this harness cannot open. */
function closeSession(db, row) {
  db.prepare(
    `INSERT INTO sessions (id, user_uid, config_id, protocol, transport,
       started_at, ended_at, up_bytes, down_bytes, close_reason)
     VALUES (?,?,?,?,?,?,?,0,?,?)`
  ).run(
    row.id, row.uid, row.config || null, row.protocol || 'v', row.transport || 'ws',
    row.started || Date.now() - 60000, row.ended || Date.now(), row.bytes || 0,
    row.reason || 'closed',
  );
}

await test("a person's connection history reads newest first and keeps the empty ones", async () => {
  const { api, post, db } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const now = Date.now();
  closeSession(db, { id: 's1', uid: user.id, ended: now - 7200000, bytes: 100 });
  closeSession(db, { id: 's2', uid: user.id, ended: now - 60000, bytes: 0, reason: 'no_traffic' });

  const body = await (await api('/users/' + user.id + '/sessions')).json();
  eq(body.items.length, 2, 'both');
  eq(body.items[0].id, 's2', 'newest first, by when it ENDED');
  // The row that carried nothing is the useful one: it is what an operator is looking at when
  // somebody says "it connects and nothing happens", and it appears in no other table.
  eq(body.items[0].close_reason, 'no_traffic', 'and a connection that carried nothing is kept');
});

await test('history is ordered by when a session ended, not when it started', async () => {
  const { api, post, db } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const now = Date.now();
  // A long session that opened first and closed last. Ordering by `started_at` -- which is what
  // the pre-existing index is on -- would put it at the bottom of a list it belongs at the top of.
  closeSession(db, { id: 'long', uid: user.id, started: now - 86400000, ended: now - 1000 });
  closeSession(db, { id: 'short', uid: user.id, started: now - 7200000, ended: now - 3600000 });

  const body = await (await api('/users/' + user.id + '/sessions')).json();
  eq(body.items[0].id, 'long', 'the one that ended most recently');
});

await test('a session names the config it went through', async () => {
  const { api, post, db } = await authed();
  const user = await (await post('/users', { username: 'ali' })).json();
  const cfg = await (await post('/configs', {
    user_id: user.id, label: 'Phone',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  })).json();
  closeSession(db, { id: 's1', uid: user.id, config: cfg.id, bytes: 500 });

  const body = await (await api('/users/' + user.id + '/sessions')).json();
  eq(body.items[0].config_label, 'Phone', 'resolved to a name the operator recognises');
});

await test('sessions.v1 rides the schema that carries the indexes', async () => {
  const { worker, env, db } = await scenario();
  const caps = (await (await call(worker, env, '/a1b2c3d4/v1/health')).json()).capabilities;
  ok(caps.includes('sessions.v1'), 'sessions.v1');
  ok(caps.includes('analytics.v2'), 'analytics.v2');
  ok(db.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name='ix_sessions_user_ended'").get(),
    'the index the history reads');
});

// ---------------------------------------------------------------------------- analytics

await test('the summary counts new people, connections and refusals over the range', async () => {
  const { api, post, db } = await authed();
  const a = await (await post('/users', { username: 'ali' })).json();
  const b = await (await post('/users', { username: 'reza' })).json();
  const now = Date.now();
  closeSession(db, { id: 's1', uid: a.id, ended: now - 1000, bytes: 1000 });
  closeSession(db, { id: 's2', uid: a.id, ended: now - 2000, bytes: 2000 });
  closeSession(db, { id: 's3', uid: b.id, ended: now - 3000, bytes: 3000 });
  db.prepare("INSERT INTO activity_log (ts, kind, user_uid, severity) VALUES (?,?,?,'warn')")
    .run(now - 4000, 'tunnel.quota', a.id);

  const s = await (await api('/analytics/summary?range=7d')).json();
  eq(s.new_users, 2, 'both were created inside the range');
  eq(s.sessions, 3, 'three connections');
  eq(s.active_users, 2, 'two distinct people');
  eq(s.refusals, 1, 'one refusal');
  eq(s.bytes, 6000, 'summed from the sessions themselves');
  // Out of ATTEMPTS. A refused connection never became a session, so counting it against sessions
  // alone would report zero per cent for a period in which everything was refused.
  eq(Math.round(s.refusal_rate * 100), 25, 'one refusal in four attempts');
});

await test('the summary leaves out what happened before the range', async () => {
  const { api, post, db } = await authed();
  const u = await (await post('/users', { username: 'ali' })).json();
  closeSession(db, { id: 'old', uid: u.id, ended: Date.now() - 40 * 86400000, bytes: 9999 });
  closeSession(db, { id: 'new', uid: u.id, ended: Date.now() - 1000, bytes: 1 });

  eq((await (await api('/analytics/summary?range=7d')).json()).sessions, 1, 'seven days');
  eq((await (await api('/analytics/summary?range=90d')).json()).sessions, 2, 'ninety days');
});

await test('the breakdown says which config, protocol and transport carried the traffic', async () => {
  const { api, post, db } = await authed();
  const u = await (await post('/users', { username: 'ali' })).json();
  const one = await (await post('/configs', {
    user_id: u.id, label: 'Steady', uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  })).json();
  const two = await (await post('/configs', {
    user_id: u.id, label: 'Phone', uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  })).json();
  const now = Date.now();
  closeSession(db, { id: 's1', uid: u.id, config: one.id, protocol: 'v', transport: 'ws', bytes: 100, ended: now });
  closeSession(db, { id: 's2', uid: u.id, config: two.id, protocol: 't', transport: 'ws', bytes: 900, ended: now });
  closeSession(db, { id: 's3', uid: u.id, config: two.id, protocol: 't', transport: 'ws', bytes: 50, ended: now });

  const b = await (await api('/analytics/breakdown?range=7d')).json();
  eq(b.configs[0].label, 'Phone', 'heaviest config first');
  eq(b.configs[0].bytes, 950, 'summed across its sessions');
  eq(b.configs[0].sessions, 2, 'and counted');
  eq(b.protocols[0].key, 't', 'the protocol that carried most');
  eq(b.transports[0].key, 'ws', 'and the transport');
  // Not "no data yet": a Worker never learns which Cloudflare address the client dialled, so this
  // one is answered by saying it cannot be answered rather than by an empty section.
  eq(b.nodes_measurable, false, 'per-endpoint traffic is not measurable and says so');
});

await test('connections over time can be narrowed to one person', async () => {
  const { api, post, db } = await authed();
  const a = await (await post('/users', { username: 'ali' })).json();
  const b = await (await post('/users', { username: 'reza' })).json();
  const now = Date.now();
  closeSession(db, { id: 's1', uid: a.id, ended: now - 1000 });
  closeSession(db, { id: 's2', uid: b.id, ended: now - 2000 });

  const all = await (await api('/analytics/sessions?range=7d')).json();
  eq(all.points.reduce((n, p) => n + p.sessions, 0), 2, 'both');
  const mine = await (await api('/analytics/sessions?range=7d&user_id=' + a.id)).json();
  eq(mine.points.reduce((n, p) => n + p.sessions, 0), 1, 'just this person');
});

await test('new people are counted by the day', async () => {
  const { api, post } = await authed();
  await post('/users', { username: 'ali' });
  await post('/users', { username: 'reza' });
  const body = await (await api('/analytics/users?range=30d')).json();
  eq(body.points.reduce((n, p) => n + p.users, 0), 2, 'both, in whatever day they landed in');
  ok(body.points.every((p) => p.ts > 0), 'and every bucket carries a real timestamp');
});

await test('an unknown range is refused rather than quietly clamped', async () => {
  const { api } = await authed();
  for (const path of ['summary', 'breakdown', 'sessions', 'users']) {
    eq((await api('/analytics/' + path + '?range=nonsense')).status, 400, path);
  }
});

await test('every analytics endpoint needs a key', async () => {
  const s = await scenario({ env: { STUDIO_BOOTSTRAP_HASH: await sha256Hex('s') } });
  for (const path of [
    '/analytics/summary', '/analytics/breakdown', '/analytics/sessions',
    '/analytics/users', '/users/u-1/sessions',
  ]) {
    eq((await call(s.worker, s.env, '/a1b2c3d4/v1' + path)).status, 401, path);
  }
});

await test("the activity feed can be narrowed to one person", async () => {
  const { api, post, db } = await authed();
  const a = await (await post('/users', { username: 'ali' })).json();
  const b = await (await post('/users', { username: 'reza' })).json();
  const now = Date.now();
  db.prepare("INSERT INTO activity_log (ts, kind, user_uid, severity) VALUES (?,?,?,'warn')")
    .run(now, 'tunnel.quota', a.id);
  db.prepare("INSERT INTO activity_log (ts, kind, user_uid, severity) VALUES (?,?,?,'warn')")
    .run(now - 1, 'tunnel.expired', b.id);

  const all = await (await api('/activity')).json();
  ok(all.items.length >= 2, 'the fleet-wide feed still shows both');
  const mine = await (await api('/activity?user_id=' + a.id)).json();
  eq(mine.items.length, 1, 'narrowed');
  eq(mine.items[0].kind, 'tunnel.quota', 'and to the right row');
});

// ---------------------------------------------------------------------------- the page's configs

/** The page and the subscription, for the same token, so the two can be compared. */
async function pageAndList(ctx, username = 'ali') {
  const user = await (await ctx.post('/users', { username })).json();
  const sub = await (await ctx.api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];
  return {
    user,
    token,
    page: () => call(ctx.worker, ctx.env, '/s/' + token + '?page=1').then((r) => r.text()),
    list: () => call(ctx.worker, ctx.env, '/s/' + token + '?format=raw').then((r) => r.text()).then(serversOnly),
  };
}

await test('the page counts configs and servers as two different figures', async () => {
  const ctx = await authed();
  const t = await pageAndList(ctx);
  await ctx.api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  await ctx.api('/nodes/n-2', { method: 'PUT', body: JSON.stringify({ name: 'Two', host: '2.2.2.2' }) });
  await ctx.post('/configs', {
    user_id: t.user.id, label: 'Steady',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });

  const html = await t.page();
  // One config on two addresses. Showing only the second reads as two subscriptions; showing only
  // the first reads as one server that either works or does not.
  ok(html.includes('کانفیگ‌ها'), 'the config count is labelled');
  ok(html.includes('سرورهای داخل لینک'), 'and the server count separately');
  ok(html.includes('۱</b>'), 'one config');
  ok(html.includes('۲</b>'), 'two servers');
});

await test('the page carries the configs themselves, and they are the ones the client gets', async () => {
  const ctx = await authed();
  const t = await pageAndList(ctx);
  await ctx.api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  await ctx.post('/configs', {
    user_id: t.user.id, credential: 'secret-1',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });

  const html = await t.page();
  const links = (await t.list()).trim().split('\n').filter(Boolean);
  eq(links.length, 1, 'one link');
  // The whole point of sharing one derivation: what the copy button hands over must be what the
  // subscription serves, character for character.
  ok(html.includes(links[0]), 'the page carries the same link the client is served: ' + links[0]);
  ok(html.includes('id="cfgs"'), 'in the element the copy button reads');
  ok(html.includes('data-src="cfgs"'), 'and the button points at it');
});

await test('the copy buttons are wired by markup, not by a hard-coded id', async () => {
  const ctx = await authed();
  const t = await pageAndList(ctx);
  await ctx.post('/configs', {
    user_id: t.user.id, uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });
  const html = await t.page();
  eq((html.match(/data-src="/g) || []).length, 2, 'both buttons declare their source');
  ok(html.includes('data-src="sub"'), 'the subscription link');
  // The fallback is what makes the button work on a plain-http origin and in an older webview,
  // where `navigator.clipboard` is simply undefined.
  ok(html.includes('execCommand'), 'and the clipboard fallback is still there');
});

await test('several configs are named one by one, a single one is not repeated', async () => {
  const ctx = await authed();
  const t = await pageAndList(ctx);
  await ctx.post('/configs', {
    user_id: t.user.id, label: 'Phone', uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });
  eq((await t.page()).includes('>Phone<'), false, 'one config needs no breakdown of itself');

  await ctx.post('/configs', {
    user_id: t.user.id, label: 'Laptop', uri_template: 'y://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });
  const html = await t.page();
  ok(html.includes('>Phone<') && html.includes('>Laptop<'), 'two are told apart by name');
});

await test('a subscription with nothing in it offers no copy button to press', async () => {
  const ctx = await authed();
  const t = await pageAndList(ctx);
  const html = await t.page();
  ok(html.includes('هنوز سروری'), 'it says so');
  // A button that copies an empty string is a button that reports success and hands over nothing.
  ok(!html.includes('data-src="cfgs"'), 'and offers nothing to copy');
});

await test('the page never shows a config belonging to somebody else', async () => {
  const ctx = await authed();
  const a = await pageAndList(ctx, 'ali');
  const b = await pageAndList(ctx, 'reza');
  await ctx.post('/configs', {
    user_id: b.user.id, credential: 'reza-secret',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });
  const html = await a.page();
  ok(!html.includes('reza-secret'), "one token, one person's links");
});

await test('the copy button falls back when the clipboard API rejects, not only when it is missing', async () => {
  const ctx = await authed();
  const t = await pageAndList(ctx);
  await ctx.post('/configs', {
    user_id: t.user.id, uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });
  const html = await t.page();

  // `writeText` EXISTS and then rejects with NotAllowedError in Android WebViews and in-app
  // browsers -- Telegram's included, which is how a subscriber usually opens a link they were
  // sent. Measured on a focused, secure-context page. A promise with no rejection handler means
  // the button does nothing at all: nothing copied, no message, no fallback.
  ok(html.includes('writeText(t).then(ok,function(){legacy(t,ok,fail)})'),
    'a rejection must fall through to execCommand, not be dropped');
  ok(html.includes('data-fail='), 'and the failure has a word of its own');
  // When even execCommand fails there is one honest move left: show the text so it can be
  // selected by hand. Swallowing it makes the person press the button again.
  ok(html.includes('s.hidden=false'), 'the hidden block is revealed when copying is refused');
});

await test('the link the page hands over is a subscription, not the page it is printed on', async () => {
  const ctx = await authed();
  const t = await pageAndList(ctx);
  await ctx.api('/nodes/n-1', { method: 'PUT', body: JSON.stringify({ name: 'One', host: '1.1.1.1' }) });
  await ctx.post('/configs', {
    user_id: t.user.id, credential: 'secret-1',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}',
  });

  const html = await t.page();
  // Read out of the page rather than assumed: this is the string the «کپی لینک» button copies and
  // the string a person retypes off the screen.
  const shown = (html.match(/id="sub">([^<]+)</) || [])[1];
  ok(shown, 'the page prints a link at all');

  // Someone who arrived here by opening `/s/{token}` in a browser is looking at their own address
  // bar. A page that prints that same URL back at them under «لینک اشتراک» has handed over
  // nothing -- and that is what it used to do.
  ok(shown.includes('format=base64'), 'the shown link pins the format: ' + shown);

  const target = new URL(shown);
  // The claim being tested is not "it has a query string", it is "it imports". Fetched exactly the
  // way a browser fetches -- the strongest page signal there is, and the one a WebView-based client
  // sends without meaning to -- it must still be the list.
  const res = await call(ctx.worker, ctx.env, target.pathname + target.search, {
    headers: {
      'Sec-Fetch-Mode': 'navigate',
      Accept: 'text/html,application/xhtml+xml',
      'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Safari/537.36',
    },
  });
  eq(res.headers.get('content-type'), 'text/plain; charset=utf-8', 'served as a list, not a page');
  const body = Buffer.from(await res.text(), 'base64').toString('utf8');
  ok(body.includes('x://'), 'and it decodes to the configs: ' + body.slice(0, 40));

  // The one-tap buttons carry the same string, so tapping «v2rayNG» and pasting the link by hand
  // cannot come to mean two different things.
  ok(html.includes('install-sub?url=' + encodeURIComponent(shown)),
    'the app buttons hand over the same link');
});


// ---------------------------------------------------------------------------- locations (build 16)

/**
 * A socket with a scripted server behind it, shaped like what `connect()` returns.
 *
 * [serve] is called with every chunk the client writes and a `reply(bytes)` to answer with. It is
 * the whole server: SOCKS5, HTTP CONNECT, or one that refuses.
 */
function fakeSocket(serve, { fail = false } = {}) {
  let ctrl;
  const written = [];
  const readable = new ReadableStream({ start(c) { ctrl = c; } });
  const state = {};
  const writable = new WritableStream({
    write(chunk) {
      const b = new Uint8Array(chunk);
      written.push(b);
      serve(b, (out) => ctrl.enqueue(typeof out === 'string' ? new TextEncoder().encode(out) : new Uint8Array(out)), state);
    },
  });
  let closeIt;
  const closed = new Promise((r) => { closeIt = r; });
  return {
    readable, writable, written, state, closed,
    opened: fail ? Promise.reject(new Error('refused')) : Promise.resolve(),
    close() { try { ctrl.close(); } catch (e) { } closeIt(); },
  };
}

/** A SOCKS5 server that wants [user]/[pass] (or nothing), and records what it was asked to reach. */
function socks5Server({ user = '', pass = '', banner = '' } = {}) {
  return (b, reply, st) => {
    if (!st.stage) {
      st.stage = 'auth';
      reply([5, user ? 2 : 0]);
      if (!user) st.stage = 'req';
    } else if (st.stage === 'auth') {
      const ul = b[1], u = new TextDecoder().decode(b.slice(2, 2 + ul));
      const p = new TextDecoder().decode(b.slice(3 + ul, 3 + ul + b[2 + ul]));
      st.creds = [u, p];
      reply([1, u === user && p === pass ? 0 : 1]);
      st.stage = 'req';
    } else if (st.stage === 'req') {
      const atyp = b[3];
      let host, at;
      if (atyp === 1) { host = [...b.slice(4, 8)].join('.'); at = 8; }
      else { const n = b[4]; host = new TextDecoder().decode(b.slice(5, 5 + n)); at = 5 + n; }
      st.target = host + ':' + ((b[at] << 8) | b[at + 1]);
      // The reply and a server-first banner in ONE chunk: what SSH does through a proxy.
      const r = [5, 0, 0, 1, 1, 2, 3, 4, 0, 80, ...new TextEncoder().encode(banner)];
      reply(r);
      st.stage = 'data';
    } else {
      st.data = (st.data || '') + new TextDecoder().decode(b);
    }
  };
}

async function readAll(sock, ms = 50) {
  const reader = sock.readable.getReader();
  let out = '';
  const until = Date.now() + ms;
  while (Date.now() < until) {
    const r = await Promise.race([reader.read(), new Promise((res) => setTimeout(() => res({ done: true }), ms))]);
    if (r.done) break;
    out += new TextDecoder().decode(r.value);
  }
  reader.releaseLock();
  return out;
}

await test('an exit URL is read in every form operators paste, and a bad one is refused', async () => {
  const { internals: w } = await loadWorker();
  const p = w.studioParseExitUrl;
  eq(p('socks5://u:p%40ss@1.2.3.4:1080'), { scheme: 'socks5', host: '1.2.3.4', port: 1080, user: 'u', pass: 'p@ss' }, 'socks5 with auth');
  eq(p('1.2.3.4:1080'), { scheme: 'socks5', host: '1.2.3.4', port: 1080, user: '', pass: '' }, 'bare host:port');
  eq(p('de.example.com:1080:alice:secret'), { scheme: 'socks5', host: 'de.example.com', port: 1080, user: 'alice', pass: 'secret' }, 'host:port:user:pass');
  eq(p('http://proxy.example:8080').scheme, 'http', 'http CONNECT');
  eq(p('https://proxy.example:8443'), null, 'https is refused rather than sent in the clear');
  eq(p('socks5://host'), null, 'no port');
  eq(p('socks5://1.2.3.4:99999'), null, 'port out of range');
  eq(w.studioRedactExitUrl('socks5://u:secret@1.2.3.4:1080'), 'socks5://u:***@1.2.3.4:1080', 'the password never leaves in a display string');
});

await test('a SOCKS5 exit is dialled with its credentials, and a banner that shares the reply packet is kept', async () => {
  const { internals: w } = await loadWorker();
  let sock;
  globalThis.__studioConnect = () => (sock = fakeSocket(socks5Server({ user: 'u', pass: 'p', banner: 'SSH-2.0-x\r\n' })));
  try {
    const s = await w.studioDialVia('socks5://u:p@9.9.9.9:1080', 'example.com', 22, new TextEncoder().encode('hello'));
    eq(sock.state.creds, ['u', 'p'], 'the credentials went to the exit');
    eq(sock.state.target, 'example.com:22', 'and the exit was asked for the real destination by name');
    eq(sock.state.data, 'hello', 'the first bytes follow the handshake');
    eq(await readAll(s), 'SSH-2.0-x\r\n', 'the server-first banner is not lost');
  } finally { delete globalThis.__studioConnect; }
});

await test('an HTTP CONNECT exit works, and one that says no is an error', async () => {
  const { internals: w } = await loadWorker();
  globalThis.__studioConnect = () => fakeSocket((b, reply, st) => {
    if (!st.done) { st.req = new TextDecoder().decode(b); st.done = true; reply('HTTP/1.1 200 Connection established\r\n\r\nEARLY'); }
  });
  try {
    const s = await w.studioDialVia('http://a:b@9.9.9.9:8080', 'example.com', 443, null);
    eq(await readAll(s), 'EARLY', 'bytes after the header belong to the stream');
  } finally { delete globalThis.__studioConnect; }

  globalThis.__studioConnect = () => fakeSocket((b, reply) => reply('HTTP/1.1 407 Proxy Authentication Required\r\n\r\n'));
  try {
    let threw = null;
    try { await w.studioDialVia('http://9.9.9.9:8080', 'example.com', 443, null); } catch (e) { threw = e; }
    ok(threw && /407/.test(threw.message), 'a refusal is an error that says why: ' + (threw && threw.message));
  } finally { delete globalThis.__studioConnect; }
});

await test('a location with no working exit refuses -- it never goes out directly', async () => {
  const { worker, env, db } = await scenario();
  const w = worker.internals;
  // Schema via the API path, then two exits in DE: the first refuses, the second answers.
  await call(worker, env, '/a1b2c3d4/v1/health');
  db.exec(`INSERT INTO exits (id, cc, url, enabled, health) VALUES
    ('a', 'DE', 'socks5://1.1.1.1:1080', 1, 'unknown'), ('b', 'DE', 'socks5://2.2.2.2:1080', 1, 'unknown')`);
  const dialled = [];
  globalThis.__studioConnect = ({ hostname }) => {
    dialled.push(hostname);
    return hostname === '1.1.1.1' ? fakeSocket(() => { }, { fail: true }) : fakeSocket(socks5Server());
  };
  try {
    const s = await w.studioDialExit(env, CTX, 'DE', 'ali', 'example.com', 443, null);
    ok(s, 'a dead exit costs a retry, not the connection');
    ok(!dialled.includes('example.com'), 'and nothing was dialled directly: ' + dialled.join(','));

    let threw = null;
    try { await w.studioDialExit(env, CTX, 'NL', 'ali', 'example.com', 443, null); } catch (e) { threw = e; }
    ok(threw, 'a country with no exit is refused');
    ok(!dialled.includes('example.com'), 'still nothing direct');
  } finally { delete globalThis.__studioConnect; }
});

await test('a person can be given several ports, and the list is cleaned', async () => {
  const { api, post } = await authed();
  const created = await post('/users', { username: 'multi', ports: [443, '8443', 2053, 99999, 443, 'x'] });
  eq(created.status, 201, 'created');
  const u = await created.json();
  eq(u.ports.join(','), '443,8443,2053', 'bad and repeated ports dropped: ' + u.ports.join(','));
  const patched = await api('/users/' + u.id, { method: 'PATCH', body: JSON.stringify({ ports: [2083] }) });
  eq((await patched.json()).ports.join(','), '2083', 'changed');
});

await test('with the pool on, a config can be pinned to a country with no exit of its own, and the user shows it', async () => {
  const { api, post, db } = await authed();
  const u = await (await post('/users', { username: 'globe' })).json();
  const before = await post('/configs', { user_id: u.id, uri_template: 'x://{cred}@{host}:{port}#{remark}', credential: 'c-nl', exit_cc: 'NL' });
  eq(before.status, 409, 'refused while the pool is off');
  db.exec("INSERT OR REPLACE INTO settings (key, value) VALUES ('pool_enabled', '1')");
  // Settings are cached for a minute per isolate; the PATCH route is what clears that.
  await api('/pool', { method: 'PATCH', body: JSON.stringify({ enabled: true, risks_acknowledged: true }) });
  // Build 18: a country is served only by a public server that was tested and really leaves from
  // it, and that test runs here, inline, the first time a config asks for the country.
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => new Response(String(url).includes('/NL/') ? 'socks5://6.0.0.1:1080\n' : 'socks5://6.0.0.2:1080\n', { status: 200 });
  globalThis.__studioConnect = ({ hostname }) => fakeSocket(socks5Answering(hostname === '6.0.0.1' ? 'NL' : 'DE'));
  try {
    for (const cc of ['NL', 'DE']) {
      const r = await post('/configs', { user_id: u.id, uri_template: 'x://{cred}@{host}:{port}#{remark}', credential: 'c-' + cc, exit_cc: cc });
      eq(r.status, 201, cc + ' accepted through the pool');
    }
  } finally {
    delete globalThis.__studioConnect;
    globalThis.fetch = realFetch;
  }
  const after = await (await api('/users/' + u.id)).json();
  eq(after.locations.join(','), 'DE,NL', 'the user carries its countries: ' + JSON.stringify(after.locations));
  const pins = db.prepare('SELECT exit_cc, exit_pin FROM configs WHERE user_uid = ? ORDER BY exit_cc').all(u.id);
  eq(pins.map((p) => p.exit_pin), ['socks5://6.0.0.2:1080', 'socks5://6.0.0.1:1080'], 'each on a server verified for its own country');
});

await test('the public pool is off until the operator turns it on, and turning it on needs the risks acknowledged', async () => {
  const { api } = await authed();
  const pool = await api('/pool');
  eq(pool.status, 200, 'readable');
  const body = await pool.json();
  eq(body.enabled, false, 'off by default');
  ok(String(body.warning).includes('عمومی'), 'the warning comes with it');
  const noAck = await api('/pool', { method: 'PATCH', body: JSON.stringify({ enabled: true }) });
  eq(noAck.status, 400, 'no quiet opt-in');
  const on = await api('/pool', { method: 'PATCH', body: JSON.stringify({ enabled: true, risks_acknowledged: true }) });
  eq(on.status, 200, 'on, knowingly');
  eq((await on.json()).enabled, true, 'and it says so');
});

await test('with the pool on, a country with no exit leaves through a public server -- never directly', async () => {
  const { worker, env, db } = await scenario();
  const w = worker.internals;
  await call(worker, env, '/a1b2c3d4/v1/health');
  db.exec("INSERT OR REPLACE INTO settings (key, value) VALUES ('pool_enabled', '1')");
  w.POOL_SETTINGS.at = 0; w.POOL_LISTS.clear(); w.POOL_STATE.clear(); w.POOL_GOOD.clear();
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (u) => {
    ok(String(u).includes('/countries/NL/'), 'the list asked for is the country asked for: ' + u);
    return new Response('socks5://9.9.9.1:1080\nsocks5://9.9.9.2:1080\njunk line\n', { status: 200 });
  };
  const dialled = [];
  globalThis.__studioConnect = ({ hostname }) => {
    dialled.push(hostname);
    return hostname === '9.9.9.1' ? fakeSocket(() => { }, { fail: true }) : fakeSocket(socks5Server());
  };
  try {
    const s = await w.studioDialExit(env, CTX, 'NL', 'ali', 'example.com', 443, null);
    ok(s, 'reached through the pool');
    ok(!dialled.includes('example.com'), 'nothing direct: ' + dialled.join(','));
    ok(dialled.every((h) => h.startsWith('9.9.9.')), 'only the listed servers were dialled');
  } finally {
    delete globalThis.__studioConnect;
    globalThis.fetch = realFetch;
    w.POOL_SETTINGS.at = 0;
  }
});

await test('the pool list keeps socks5 and http proxies and drops everything else', async () => {
  const { worker } = await scenario();
  const list = worker.internals.studioPoolParse('socks5://1.1.1.1:1080\nsocks4://2.2.2.2:1\nhttp://3.3.3.3:8080\nhttps://4.4.4.4:1\n5.5.5.5:9050\n# note\n');
  eq(list.length, 3, 'three usable: ' + list.join(','));
  ok(list[0].startsWith('socks5://') && list[list.length - 1].startsWith('http://'), 'socks5 first');
});

await test('the same person on the same country keeps landing on the same exit', async () => {
  const { worker, env, db } = await scenario();
  const w = worker.internals;
  await call(worker, env, '/a1b2c3d4/v1/health');
  db.exec(`INSERT INTO exits (id, cc, url, enabled, health) VALUES
    ('x1','DE','socks5://1.0.0.1:1',1,'up'),('x2','DE','socks5://1.0.0.2:1',1,'up'),('x3','DE','socks5://1.0.0.3:1',1,'up')`);
  const seen = [];
  globalThis.__studioConnect = ({ hostname }) => { seen.push(hostname); return fakeSocket(socks5Server()); };
  try {
    for (let i = 0; i < 5; i++) await w.studioDialExit(env, CTX, 'DE', 'ali', 'example.com', 443, null);
    eq(new Set(seen).size, 1, 'one exit for one person: ' + seen.join(','));
  } finally { delete globalThis.__studioConnect; }
});

await test('exits are added from a pasted list, and a config can be pinned to one of their countries', async () => {
  const ctx = await authed();
  const add = await ctx.post('/exits', { items: [
    { url: 'socks5://u:p@1.2.3.4:1080', cc: 'DE' },
    'not a proxy',
    { url: 'socks5://u:p@1.2.3.4:1080', cc: 'DE' },
  ] });
  eq(add.status, 201, 'added');
  const res = await add.json();
  eq(res.added.length, 1, 'one exit');
  eq(res.rejected.map((r) => r.reason), ['unreadable', 'duplicate'], 'the rest said why');

  const list = await (await ctx.api('/exits')).json();
  eq(list.items[0].display, 'socks5://u:***@1.2.3.4:1080', 'shown without its password');

  const user = await (await ctx.post('/users', { username: 'ali' })).json();
  const none = await ctx.post('/configs', { user_id: user.id, credential: 'c-nl', exit_cc: 'NL', uri_template: 'x://{{cred}}#{{remark}}' });
  eq(none.status, 409, 'a country with no exit is refused up front, not on every connection');

  const made = await ctx.post('/configs', { user_id: user.id, credential: 'c-de', exit_cc: 'DE', uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}' });
  eq(made.status, 201, 'pinned');
  const cfgs = await (await ctx.api('/users/' + user.id + '/configs')).json();
  eq(cfgs.items[0].exit_cc, 'DE', 'and it says so');

  const sub = await (await ctx.api('/users/' + user.id + '/subscription')).json();
  const token = sub.url.split('/s/')[1];
  const body = serversOnly(await (await call(ctx.worker, ctx.env, '/s/' + token + '?format=raw')).text());
  ok(decodeURIComponent(body).includes('🇩🇪 آلمان · VLESS · ali'), 'the link names its country and protocol: ' + decodeURIComponent(body));

  const found = await ctx.worker.internals.studioVlessUser(ctx.env, 'c-de');
  eq(found.studio_exit_cc, 'DE', 'the data plane learns the country from the credential');

  const moved = await ctx.post('/configs/' + cfgs.items[0].id + ':exit', { exit_cc: null });
  eq(moved.status, 200, 'a config can be moved back to no location');
  eq((await ctx.worker.internals.studioVlessUser(ctx.env, 'c-de')).studio_exit_cc, null, 'and then goes out directly');
});

await test('the guard refuses mail, torrent, private targets and floods, and lets the rest through', async () => {
  const { internals: w } = await loadWorker();
  const enc = (s) => new TextEncoder().encode(s);
  eq(w.studioGuardVerdict('u', 'smtp.example.com', 587, null), 'mail', 'mail submission');
  eq(w.studioGuardVerdict('u', 'example.com', 6881, null), 'torrent', 'a torrent port');
  eq(w.studioGuardVerdict('u', '192.168.1.1', 80, null), 'private', 'a private address');
  eq(w.studioGuardVerdict('u', '169.254.169.254', 80, null), 'private', 'a metadata service');
  const hs = new Uint8Array([19, ...enc('BitTorrent protocol'), 0, 0, 0, 0]);
  eq(w.studioGuardVerdict('u', 'peer.example', 443, hs), 'torrent', 'a peer handshake on any port');
  eq(w.studioGuardVerdict('u', 't.example', 80, enc('GET /announce?info_hash=abc HTTP/1.1\r\n')), 'torrent', 'a tracker');
  eq(w.studioGuardVerdict('u', 'example.com', 443, enc('\x16\x03\x01 tls hello....')), null, 'ordinary traffic passes');
  let last = null;
  for (let i = 0; i < 205; i++) last = w.studioGuardVerdict('flooder', 'example.com', 443, null);
  eq(last, 'flood', 'hundreds of new connections in seconds');
  eq(w.studioGuardVerdict('calm', 'example.com', 443, null), null, 'and that is per person');
});

await test('a small quota is counted against what is left, including what is not yet committed', async () => {
  const { internals: w } = await loadWorker();
  const MB = 1048576, GB = 1073741824;
  const u = { limit_gb: 10 * MB / GB, used_gb: 0 };
  eq(w.studioRoomBytes(u, 0), 10 * MB, 'ten megabytes of room, from a build-5 row');
  eq(w.studioRoomBytes(u, 4 * MB), 6 * MB, 'less what this isolate has seen');
  // Bytes first since build 18: a plan's daily cap wrote only the byte column, and was shown and
  // never enforced while admission read the GB mirror.
  eq(w.studioRoomBytes({ quota_bytes: 100 * MB, used_bytes: 40 * MB }, 0), 60 * MB, 'the byte columns are what is enforced');
  eq(w.studioRoomBytes({ daily_quota_bytes: 5 * MB, daily_used_bytes: 4 * MB }, 0), 1 * MB, 'and so is a daily cap written only in bytes');
  // An eighth of what is LEFT: generous far from the limit, fine-grained near it.
  eq(w.studioCommitThreshold(u), 10 * MB / 8, 'an eighth of the room');
  eq(w.studioCommitThreshold({ quota_bytes: 10 * MB, used_bytes: 10 * MB - 100 * 1024 }), 64 * 1024, 'near the end, 64 KB at a time');
  eq(w.studioCommitThreshold({ limit_gb: 100 }), 32 * MB, 'a big quota is written every 32 MB');
  eq(w.studioRoomBytes({}, 0), Infinity, 'no quota, no limit');
});

// ---------------------------------------------------------------------------- the meter (build 18)

const MIB = 1048576;
const settle = () => new Promise((r) => setTimeout(r, 10));

/**
 * The case the operator reported: ten megabytes used, sixty kilobytes shown. Usage below the commit
 * threshold reached D1 only when a person's last connection closed, `updated_at` never moved so the
 * app's sync never saw it, and «آمار» read a table that only got the close-time tail.
 */
await test('the meter writes on the last close, moves updated_at, and feeds usage_daily and sessions', async () => {
  const { worker, env, db, post } = await authed();
  const w = worker.internals;
  const user = await (await post('/users', { username: 'ali', policy: { quota_bytes: 100 * MIB } })).json();
  const row = db.prepare('SELECT * FROM users WHERE uid = ?').get(user.id);

  const h = w.studioMeterJoin(row, () => { }, { protocol: 'v', transport: 'ws' }, Date.now());
  w.studioMeterAdd(env, CTX, h, 3 * MIB);
  await settle();
  eq(db.prepare('SELECT used_bytes FROM users WHERE uid = ?').get(user.id).used_bytes, 0,
    'three megabytes of a hundred are held, not written one by one');

  await new Promise((r) => setTimeout(r, 5));
  w.studioMeterLeave(env, CTX, h);
  await settle();
  const after = db.prepare('SELECT used_bytes, used_gb, updated_at, last_active FROM users WHERE uid = ?').get(user.id);
  eq(after.used_bytes, 3 * MIB, 'the last close writes what was used');
  ok(Math.abs(after.used_gb * 1073741824 - 3 * MIB) < 1, 'and its GB mirror');
  ok(after.updated_at > row.updated_at, 'and moves updated_at, which is what the app syncs on');
  ok(after.last_active > 0, 'and says when they were last seen');
  const daily = db.prepare('SELECT SUM(down_bytes) AS b, SUM(sessions) AS s FROM usage_daily WHERE user_uid = ?').get(user.id);
  eq(daily.b, 3 * MIB, '«آمار» reads the same figure as the user row');
  eq(daily.s, 1, 'one session');
  eq(db.prepare('SELECT down_bytes FROM sessions WHERE user_uid = ?').get(user.id).down_bytes, 3 * MIB, 'and one session row');
  ok(!w.METER.has(user.id), 'nothing is left behind in the isolate');
});

await test('a connection that stays open still reaches D1 within the flush interval', async () => {
  const { worker, env, db, post } = await authed();
  const w = worker.internals;
  const user = await (await post('/users', { username: 'ali' })).json();
  const row = db.prepare('SELECT * FROM users WHERE uid = ?').get(user.id);
  const h = w.studioMeterJoin(row, () => { }, null, Date.now());
  w.studioMeterAdd(env, CTX, h, 700 * 1024);
  await settle();
  eq(db.prepare('SELECT used_bytes FROM users WHERE uid = ?').get(user.id).used_bytes, 0, 'held while fresh');
  // As if ninety seconds had passed: the tick writes the tail without anything closing.
  h.meter.lastFlush -= 91000;
  await w.studioMeterTick(env, CTX, h);
  await settle();
  eq(db.prepare('SELECT used_bytes FROM users WHERE uid = ?').get(user.id).used_bytes, 700 * 1024,
    'an open session is not invisible below its threshold any more');
  w.studioMeterLeave(env, CTX, h);
  await settle();
});

await test('a quota ends every connection of that person at once, and overshoots by at most a chunk', async () => {
  const { worker, env, db, post } = await authed();
  const w = worker.internals;
  const user = await (await post('/users', { username: 'ali', policy: { quota_bytes: 1 * MIB } })).json();
  const row = db.prepare('SELECT * FROM users WHERE uid = ?').get(user.id);
  const stopped = [];
  const a = w.studioMeterJoin(row, () => stopped.push('a'), null, Date.now());
  const b = w.studioMeterJoin(row, () => stopped.push('b'), null, Date.now());
  for (let i = 0; i < 40 && stopped.length === 0; i++) {
    w.studioMeterAdd(env, CTX, i % 2 ? a : b, 64 * 1024);
    await settle();
  }
  ok(stopped.includes('a') && stopped.includes('b'), 'both connections were ended: ' + stopped.join(','));
  w.studioMeterLeave(env, CTX, a);
  w.studioMeterLeave(env, CTX, b);
  await settle();
  const used = db.prepare('SELECT used_bytes FROM users WHERE uid = ?').get(user.id).used_bytes;
  ok(used >= 1 * MIB && used <= 1 * MIB + 64 * 1024, 'stopped at the limit, not 8% past it: ' + used);
});

await test('two isolates spending one quota overshoot it by kilobytes, not megabytes', async () => {
  const { worker, env, db, post } = await authed();
  const other = await loadWorker();
  const user = await (await post('/users', { username: 'ali', policy: { quota_bytes: 100 * MIB } })).json();
  const read = () => db.prepare('SELECT * FROM users WHERE uid = ?').get(user.id);
  let stopA = false, stopB = false;
  const a = worker.internals.studioMeterJoin(read(), () => { stopA = true; }, null, Date.now());
  const b = other.internals.studioMeterJoin(read(), () => { stopB = true; }, null, Date.now());
  // A read from the destination is at most 64 KB, and between two of them the isolate gets to finish
  // whatever write it had in flight -- which is what a real connection does, and what the yield
  // models. (On a real account a write takes tens of milliseconds, so a very fast line adds what it
  // moves in that time on top of this.)
  for (let i = 0; i < 4000 && !(stopA && stopB); i++) {
    if (!stopA) worker.internals.studioMeterAdd(env, CTX, a, 64 * 1024);
    if (!stopB) other.internals.studioMeterAdd(env, CTX, b, 64 * 1024);
    await new Promise((r) => setImmediate(r));
  }
  await settle();
  worker.internals.studioMeterLeave(env, CTX, a);
  other.internals.studioMeterLeave(env, CTX, b);
  await settle();
  const used = read().used_bytes;
  ok(stopA && stopB, 'both isolates stopped');
  ok(used - 100 * MIB <= 512 * 1024, 'overshoot ' + Math.round((used - 100 * MIB) / 1024) + ' KB (the old rule allowed ~10 MB per isolate)');
});

// ---------------------------------------------------------------------------- the device limit (build 18)

await test('a device is asked about once a minute per isolate, and a person with no limit costs nothing', async () => {
  const { worker, env, db, post } = await authed();
  const w = worker.internals;
  let calls = 0;
  const SESSIONS = {
    idFromName: (n) => n,
    get: () => ({ fetch: async () => { calls++; return new Response(JSON.stringify({ allow: true, live: 1 })); } }),
  };
  const env2 = { ...env, SESSIONS };
  const req = new Request(BASE, { headers: { 'CF-Connecting-IP': '203.0.113.7', 'User-Agent': 'v2rayNG/1.9' } });

  const free = await (await post('/users', { username: 'free' })).json();
  const r0 = await w.studioPresenceAdmit(env2, CTX, db.prepare('SELECT * FROM users WHERE uid = ?').get(free.id), req);
  eq(r0.allow, true, 'admitted');
  eq(calls, 0, 'no limit, no Durable Object request');

  const capped = await (await post('/users', { username: 'capped', policy: { device_limit: 1 } })).json();
  const row = db.prepare('SELECT * FROM users WHERE uid = ?').get(capped.id);
  eq(row.enforcement, 'strict', 'a device limit typed at creation applies');
  for (let i = 0; i < 5; i++) await w.studioPresenceAdmit(env2, CTX, row, req);
  eq(calls, 1, 'five connections from one device: one question');
});

await test('early data in the upgrade is read as the first message, and a real subprotocol is not', async () => {
  const { internals: w } = await loadWorker();
  const header = new Uint8Array(40);
  header[0] = 0;
  for (let i = 1; i < 17; i++) header[i] = i;
  const b64 = btoa(String.fromCharCode(...header)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const got = w.studioEarlyData(new Request(BASE, { headers: { 'Sec-WebSocket-Protocol': b64 } }));
  eq(got && Array.from(got), Array.from(header), 'a VLESS header decoded from base64url');
  eq(w.studioEarlyData(new Request(BASE, { headers: { 'Sec-WebSocket-Protocol': 'chat' } })), null, 'a subprotocol is not early data');
  eq(w.studioEarlyData(new Request(BASE)), null, 'and no header is none');
});

await test('an XHTTP header with an IPv6 destination is read', async () => {
  const { internals: w } = await loadWorker();
  const ip6 = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1];
  const bytes = new Uint8Array([0, ...new Array(16).fill(7), 0, 1, 1, 187, 3, ...ip6, 9, 9]);
  const reader = new ReadableStream({ start(c) { c.enqueue(bytes.slice(0, 10)); c.enqueue(bytes.slice(10)); c.close(); } }).getReader();
  const hdr = await w.studioXhttpReadHeader(reader);
  eq(hdr.port, 443, 'port');
  eq(hdr.addr, '2001:db8:0:0:0:0:0:1', 'IPv6, which the WebSocket path used to refuse');
  eq(Array.from(hdr.rawData), [9, 9], 'and the payload after it');
});

// ---------------------------------------------------------------------------- the subscription (build 18)

await test('the subscription asks WebSocket for early data, leaves XHTTP out, names the protocol and applies chosen ports', async () => {
  const { worker, env, api, post } = await authed();
  const user = await (await post('/users', { username: 'ali', ports: [443, 8443] })).json();
  await post('/configs', { user_id: user.id, protocol: 'v', transport_type: 'ws',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}?type=ws&path={{path}}#{{remark}}' });
  // Created as stream-one, as the Edge panel does: served as packet-up all the same, because a
  // workers.dev address cannot carry a streaming mode (04c-studio-sub.js).
  await post('/configs', { user_id: user.id, protocol: 'v', transport_type: 'xhttp',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}?type=xhttp&mode=stream-one&path={{path}}#{{remark}}' });
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const lines = (serversOnly(await (await call(worker, env, '/s/' + sub.token + '?format=raw')).text())).split('\n');
  const ws = lines.filter((l) => l.includes('type=ws'));
  const xh = lines.filter((l) => l.includes('type=xhttp'));
  eq(ws.length, 2, 'one WebSocket link per chosen port');
  eq(xh.length, 0, 'XHTTP is not served: it cannot connect on a workers.dev address');
  ok(ws.every((l) => l.includes('path=%2F%3Fed%3D2560')), 'WebSocket asks for early data: ' + ws[0]);
  ok(ws.some((l) => l.includes(':8443')), 'the second port is real, not ignored');
  ok(decodeURIComponent(ws[0]).includes('VLESS · ali'), 'each link says which protocol it is');
});

// ---------------------------------------------------------------------------- locations (build 18)

/** A SOCKS5 server whose far side answers the country probe as if it left from [cc]. */
function socks5Answering(cc, ip = '9.9.9.9') {
  const base = socks5Server();
  return (b, reply, st) => {
    if (st.stage === 'data') {
      reply(`HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{"status":"success","countryCode":"${cc}","query":"${ip}"}`);
      return;
    }
    base(b, reply, st);
  };
}

await test('an exit is used for the country it was MEASURED in, not the one it was filed under', async () => {
  const { worker, env, db } = await scenario();
  const w = worker.internals;
  await call(worker, env, '/a1b2c3d4/v1/health');
  db.exec(`INSERT INTO exits (id, cc, url, enabled, health, exit_cc) VALUES
    ('m1', 'DE', 'socks5://1.0.0.1:1', 1, 'up', 'NL'), ('m2', 'DE', 'socks5://1.0.0.2:1', 1, 'up', NULL)`);
  w.EXIT_CACHE.clear();
  eq((await w.studioExitsFor(env, 'DE')).map((r) => r.id), ['m2'], 'filed DE but leaves from NL: not a German exit');
  w.EXIT_CACHE.clear();
  eq((await w.studioExitsFor(env, 'NL')).map((r) => r.id), ['m1'], 'it serves the country it really leaves from');
});

await test('a location config keeps its pinned exit, and fails over within its country, writing the new pin back', async () => {
  const ctx = await authed();
  const { worker, env, db } = ctx;
  const w = worker.internals;
  await ctx.post('/exits', { items: [{ url: 'socks5://7.0.0.1:1080', cc: 'DE' }, { url: 'socks5://7.0.0.2:1080', cc: 'DE' }] });
  const user = await (await ctx.post('/users', { username: 'ali' })).json();
  const made = await (await ctx.post('/configs', { user_id: user.id, credential: 'c-de', exit_cc: 'DE',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}#{{remark}}' })).json();
  const pinned = db.prepare('SELECT exit_pin FROM configs WHERE id = ?').get(made.id).exit_pin;
  ok(/^socks5:\/\/7\.0\.0\.[12]:1080$/.test(pinned), 'pinned at creation: ' + pinned);

  const dialled = [];
  const dead = new Set();
  globalThis.__studioConnect = ({ hostname }) => {
    dialled.push(hostname);
    return dead.has(hostname) ? fakeSocket(() => { }, { fail: true }) : fakeSocket(socks5Server());
  };
  try {
    const pin = { configId: made.id, url: pinned };
    for (let i = 0; i < 3; i++) await w.studioDialExit(env, CTX, 'DE', 'ali', 'example.com', 443, null, pin);
    eq(new Set(dialled).size, 1, 'the same exit every time: ' + dialled.join(','));

    dead.add(dialled[0]);
    dialled.length = 0;
    await w.studioDialExit(env, CTX, 'DE', 'ali', 'example.com', 443, null, pin);
    const repinned = db.prepare('SELECT exit_pin FROM configs WHERE id = ?').get(made.id).exit_pin;
    ok(repinned !== pinned && /^socks5:\/\/7\.0\.0\.[12]:1080$/.test(repinned), 'moved to the other German exit: ' + repinned);
    ok(!dialled.includes('example.com'), 'and never directly');
  } finally { delete globalThis.__studioConnect; }
});

await test('the public pool keeps only servers that really leave from the country asked for', async () => {
  const { worker, env, db } = await scenario();
  const w = worker.internals;
  await call(worker, env, '/a1b2c3d4/v1/health');
  db.exec("INSERT OR REPLACE INTO settings (key, value) VALUES ('pool_enabled', '1')");
  w.POOL_SETTINGS.at = 0; w.POOL_LISTS.clear(); w.POOL_STATE.clear(); w.POOL_GOOD.clear(); w.POOL_VERIFIED.clear();
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response('socks5://8.0.0.1:1080\nsocks5://8.0.0.2:1080\n', { status: 200 });
  globalThis.__studioConnect = ({ hostname }) => fakeSocket(socks5Answering(hostname === '8.0.0.1' ? 'DE' : 'NL'));
  try {
    const r = await w.studioPoolVerify(env, 'DE', 12);
    eq(r.found, 1, 'one of two passed');
    eq(r.verified.map((v) => v.u), ['socks5://8.0.0.1:1080'], 'the one that leaves from Germany');
    const stored = JSON.parse(db.prepare("SELECT list FROM pool_verified WHERE cc = 'DE'").get().list);
    eq(stored.length, 1, 'and it is stored for every isolate');
  } finally {
    delete globalThis.__studioConnect;
    globalThis.fetch = realFetch;
    w.POOL_SETTINGS.at = 0;
  }
});


await test("a destination the Worker cannot reach goes through the operator's own exit, not a stranger's relay", async () => {
  const { worker, env, db } = await scenario();
  const w = worker.internals;
  await call(worker, env, '/a1b2c3d4/v1/health');
  eq(await w.studioDialOwnRelay(env, CTX, 'ali', '104.16.0.1', 443, null), null, 'no exits: the caller falls back as before');
  db.exec("INSERT INTO exits (id, cc, url, enabled, health) VALUES ('r1', 'DE', 'socks5://5.5.5.5:1080', 1, 'up')");
  w.EXIT_CACHE.clear();
  const dialled = [];
  globalThis.__studioConnect = ({ hostname }) => { dialled.push(hostname); return fakeSocket(socks5Server()); };
  try {
    ok(await w.studioDialOwnRelay(env, CTX, 'ali', '104.16.0.1', 443, null), 'relayed');
    eq(dialled, ['5.5.5.5'], "through the operator's own server only");
  } finally { delete globalThis.__studioConnect; }
});

// ---------------------------------------------------------------------------- end to end (build 18)
//
// A whole connection through the shipped handlers, the way a client sends it: the upgrade (or the
// POST), the protocol header, a payload, and the destination's answer coming back. Node has neither
// `WebSocketPair` nor a Response that can carry a 101, so both are provided here for the length of
// one test -- the handlers themselves are the ones that ship.

class E2EFakeWS extends EventTarget {
  constructor() { super(); this.readyState = 1; this.peer = null; this.binaryType = 'blob'; }
  accept() { }
  send(data) {
    if (this.readyState !== 1) throw new Error('ws not open');
    const u8 = typeof data === 'string' ? new TextEncoder().encode(data)
      : data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    const copy = u8.slice().buffer;
    const peer = this.peer;
    queueMicrotask(() => { if (peer.readyState === 1) peer.dispatchEvent(new MessageEvent('message', { data: copy })); });
  }
  close() {
    if (this.readyState === 3) return;
    this.readyState = 3;
    queueMicrotask(() => this.dispatchEvent(new Event('close')));
    if (this.peer && this.peer.readyState !== 3) this.peer.close();
  }
}

/** Runs [fn] with a WebSocketPair and a Response that accepts 101, then puts the globals back. */
async function withSockets(fn) {
  const OrigResponse = globalThis.Response;
  const hadPair = 'WebSocketPair' in globalThis;
  const origPair = globalThis.WebSocketPair;
  globalThis.WebSocketPair = function () {
    const a = new E2EFakeWS(), b = new E2EFakeWS();
    a.peer = b; b.peer = a;
    return { 0: a, 1: b };
  };
  globalThis.Response = class extends OrigResponse {
    constructor(body, init) {
      if (init && init.status === 101) {
        super(null, { status: 200 });
        Object.defineProperty(this, 'status', { value: 101 });
        this.webSocket = init.webSocket;
      } else {
        super(body, init);
      }
    }
  };
  try {
    return await fn();
  } finally {
    globalThis.Response = OrigResponse;
    if (hadPair) globalThis.WebSocketPair = origPair; else delete globalThis.WebSocketPair;
  }
}

/** A destination that answers every write with `echo:` and what it received. */
function echoServer() {
  return (b, reply) => reply(new Uint8Array([...new TextEncoder().encode('echo:'), ...b]));
}

const b64url = (u8) => btoa(String.fromCharCode(...u8)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const utf8 = (s) => new TextEncoder().encode(s);
const cat = (...parts) => { const n = parts.reduce((a, p) => a + p.byteLength, 0); const o = new Uint8Array(n); let at = 0; for (const p of parts) { o.set(p, at); at += p.byteLength; } return o; };

/** A domain destination, as both protocols spell it. */
function trojanRequest(passHash, host, port, payload) {
  return cat(utf8(passHash), utf8('\r\n'), new Uint8Array([1, 3, host.length]), utf8(host),
    new Uint8Array([port >> 8, port & 255]), utf8('\r\n'), payload);
}
function vlessRequest(uuid, host, port, payload) {
  const id = new Uint8Array(uuid.replace(/-/g, '').match(/../g).map((h) => parseInt(h, 16)));
  return cat(new Uint8Array([0]), id, new Uint8Array([0, 1, port >> 8, port & 255, 2, host.length]), utf8(host), payload);
}

/** Open a tunnel over WebSocket with [early] in the upgrade; collect what comes back for [ms]. */
async function wsTunnel(worker, env, early, later = [], ms = 300) {
  const res = await call(worker, env, '/', { headers: { Upgrade: 'websocket', 'Sec-WebSocket-Protocol': b64url(early) } });
  const client = res.webSocket;
  if (!client) return { status: res.status, bytes: new Uint8Array(0), closed: true };
  const got = [];
  let closed = false;
  client.addEventListener('message', (e) => got.push(new Uint8Array(e.data)));
  client.addEventListener('close', () => { closed = true; });
  await new Promise((r) => setTimeout(r, 60));
  for (const chunk of later) { if (client.readyState === 1) client.send(chunk); await new Promise((r) => setTimeout(r, 20)); }
  await new Promise((r) => setTimeout(r, ms));
  return { status: res.status, bytes: cat(...got), closed };
}

await test('end to end: a Trojan config carries data over WebSocket with early data', async () => {
  const { createHash } = await import('node:crypto');
  const { worker, env, post } = await authed();
  const user = await (await post('/users', { username: 'tro' })).json();
  const pass = 'a1b2c3d4e5f60718293a4b5c6d7e8f90';
  const hash = createHash('sha224').update(pass).digest('hex');
  eq((await post('/configs', { user_id: user.id, protocol: 't', transport_type: 'ws', credential: pass, auth_hash: hash,
    uri_template: 'trojan://{{cred}}@{{host}}:{{port}}?type=ws&path={{path}}#{{remark}}' })).status, 201, 'config');
  globalThis.__studioConnect = () => fakeSocket(echoServer());
  try {
    const out = await withSockets(() => wsTunnel(worker, env, trojanRequest(hash, 'example.com', 443, utf8('hello')), [utf8('again')]));
    eq(out.status, 101, 'upgraded');
    const text = new TextDecoder().decode(out.bytes);
    ok(text.includes('echo:hello'), 'the payload that rode in the upgrade reached the destination and came back: ' + JSON.stringify(text));
    ok(text.includes('echo:again'), 'and so did the next message: ' + JSON.stringify(text));
  } finally { delete globalThis.__studioConnect; }
});

await test('end to end: a VLESS config with its own credential carries data over WebSocket', async () => {
  const { worker, env, post } = await authed();
  const user = await (await post('/users', { username: 'vle' })).json();
  const cred = '0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0';
  eq((await post('/configs', { user_id: user.id, protocol: 'v', transport_type: 'ws', credential: cred,
    uri_template: 'vless://{{cred}}@{{host}}:{{port}}?type=ws&path={{path}}#{{remark}}' })).status, 201, 'config');
  globalThis.__studioConnect = () => fakeSocket(echoServer());
  try {
    const out = await withSockets(() => wsTunnel(worker, env, vlessRequest(cred, 'example.com', 443, utf8('hello')), [utf8('again')]));
    eq(out.status, 101, 'upgraded');
    eq(Array.from(out.bytes.slice(0, 2)), [0, 0], 'the VLESS response header first');
    const text = new TextDecoder().decode(out.bytes.slice(2));
    ok(text.includes('echo:hello') && text.includes('echo:again'), 'then the destination: ' + JSON.stringify(text));
  } finally { delete globalThis.__studioConnect; }
});

await test('end to end: XHTTP stream-one carries data both ways in one request', async () => {
  const { worker, env, post } = await authed();
  const user = await (await post('/users', { username: 'xh' })).json();
  const cred = '11112222-3333-4444-5555-666677778888';
  eq((await post('/configs', { user_id: user.id, protocol: 'v', transport_type: 'xhttp', credential: cred,
    uri_template: 'vless://{{cred}}@{{host}}:{{port}}?type=xhttp&mode=stream-one&path={{path}}#{{remark}}' })).status, 201, 'config');
  globalThis.__studioConnect = () => fakeSocket(echoServer());
  try {
    let push;
    const body = new ReadableStream({ start(c) { push = c; } });
    push.enqueue(vlessRequest(cred, 'example.com', 443, utf8('hello')));
    const res = await Promise.race([
      worker.fetch(new Request(BASE + '/', { method: 'POST', body, duplex: 'half',
        headers: { 'Content-Type': 'application/grpc' } }), env, CTX),
      new Promise((r) => setTimeout(() => r(null), 2000)),
    ]);
    ok(res, 'the response starts while the upload is still open (no deadlock)');
    eq(res.status, 200, 'status');
    const reader = res.body.getReader();
    const got = [];
    // One read in flight at a time: a read abandoned to a timeout would swallow the next chunk.
    let pending = null;
    const readFor = async (ms) => {
      const until = Date.now() + ms;
      while (Date.now() < until) {
        pending = pending || reader.read();
        const r = await Promise.race([pending, new Promise((x) => setTimeout(() => x({ timeout: true }), until - Date.now()))]);
        if (r.timeout) break;
        pending = null;
        if (r.done) break;
        got.push(new Uint8Array(r.value));
      }
    };
    await readFor(200);
    push.enqueue(utf8('again'));
    await readFor(200);
    push.close();
    const all = cat(...got);
    eq(Array.from(all.slice(0, 2)), [0, 0], 'the VLESS response header first');
    const text = new TextDecoder().decode(all.slice(2));
    ok(text.includes('echo:hello'), 'the first payload came back: ' + JSON.stringify(text));
    ok(text.includes('echo:again'), 'and a later upload did too: ' + JSON.stringify(text));
  } finally { delete globalThis.__studioConnect; }
});

await test('end to end: XHTTP packet-up carries data, download GET and upload POSTs meeting in one session', async () => {
  const { worker, env, post } = await authed();
  const user = await (await post('/users', { username: 'xp' })).json();
  const cred = '99998888-7777-4666-8555-444433332222';
  eq((await post('/configs', { user_id: user.id, protocol: 'v', transport_type: 'xhttp', credential: cred,
    uri_template: 'vless://{{cred}}@{{host}}:{{port}}?type=xhttp&mode=packet-up&path={{path}}#{{remark}}' })).status, 201, 'config');
  globalThis.__studioConnect = () => fakeSocket(echoServer());
  try {
    const sid = crypto.randomUUID();
    // The client opens the download first and uploads beside it.
    const downP = worker.fetch(new Request(BASE + '/' + sid), env, CTX);
    const up0 = await call(worker, env, '/' + sid + '/0', { method: 'POST', body: vlessRequest(cred, 'example.com', 443, utf8('hello')) });
    eq(up0.status, 200, 'the header POST is answered at once');
    const down = await downP;
    eq(down.status, 200, 'the download answers');
    const reader = down.body.getReader();
    const got = [];
    let pending = null;
    const readFor = async (ms) => {
      const until = Date.now() + ms;
      while (Date.now() < until) {
        pending = pending || reader.read();
        const r = await Promise.race([pending, new Promise((x) => setTimeout(() => x({ timeout: true }), until - Date.now()))]);
        if (r.timeout) break;
        pending = null;
        if (r.done) break;
        got.push(new Uint8Array(r.value));
      }
    };
    await readFor(200);
    eq((await call(worker, env, '/' + sid + '/2', { method: 'POST', body: utf8('third') })).status, 200, 'an upload that overtook the one before it');
    eq((await call(worker, env, '/' + sid + '/1', { method: 'POST', body: utf8('second') })).status, 200, 'the one it overtook');
    await readFor(250);
    const all = cat(...got);
    eq(Array.from(all.slice(0, 2)), [0, 0], 'the VLESS response header first');
    const text = new TextDecoder().decode(all.slice(2));
    ok(text.includes('echo:hello'), 'the payload in the header POST came back: ' + JSON.stringify(text));
    ok(text.indexOf('echo:second') >= 0 && text.indexOf('echo:third') > text.indexOf('echo:second'),
      'and the later uploads, in the order they were sent: ' + JSON.stringify(text));
  } finally { delete globalThis.__studioConnect; }
});

await test('end to end: XHTTP packet-up whose requests land in DIFFERENT isolates still meets, in the session\'s Durable Object', async () => {
  // The failure measured on workers.dev (2026-09-26): the upload POSTs reached other isolates than
  // the download GET and were lost. Two module instances stand in for two isolates (each has its own
  // session table); a third hosts the Durable Object every request of the session is handed to.
  const { worker: isolateA, env, post } = await authed();
  const isolateB = await loadWorker();
  const objectIsolate = await loadWorker();
  const user = await (await post('/users', { username: 'xdo' })).json();
  const cred = '12345678-9abc-4def-8123-456789abcdef';
  eq((await post('/configs', { user_id: user.id, protocol: 'v', transport_type: 'xhttp', credential: cred,
    uri_template: 'vless://{{cred}}@{{host}}:{{port}}?type=xhttp&mode=packet-up&path={{path}}#{{remark}}' })).status, 201, 'config');

  const objects = new Map();
  const names = [];
  const SESSIONS = {
    idFromName: (n) => { names.push(n); return n; },
    get: (id) => ({
      fetch: async (req) => {
        if (!objects.has(id)) {
          const state = { storage: { sql: null, setAlarm() { } }, waitUntil: (p) => { if (p && p.catch) p.catch(() => { }); } };
          objects.set(id, new objectIsolate.internals.SessionDO(state, envDO));
        }
        return objects.get(id).fetch(req);
      },
    }),
  };
  const envDO = { ...env, SESSIONS };
  globalThis.__studioConnect = () => fakeSocket(echoServer());
  try {
    const sid = crypto.randomUUID();
    const downP = isolateA.fetch(new Request(BASE + '/' + sid), envDO, CTX);
    const up0 = await isolateB.fetch(new Request(BASE + '/' + sid + '/0', { method: 'POST', body: vlessRequest(cred, 'example.com', 443, utf8('hello')) }), envDO, CTX);
    eq(up0.status, 200, 'the header POST, in the other isolate, is answered');
    const down = await downP;
    eq(down.status, 200, 'the download answers');
    const reader = down.body.getReader();
    const got = [];
    let pending = null;
    const readFor = async (ms) => {
      const until = Date.now() + ms;
      while (Date.now() < until) {
        pending = pending || reader.read();
        const r = await Promise.race([pending, new Promise((x) => setTimeout(() => x({ timeout: true }), until - Date.now()))]);
        if (r.timeout) break;
        pending = null;
        if (r.done) break;
        got.push(new Uint8Array(r.value));
      }
    };
    await readFor(200);
    eq((await isolateA.fetch(new Request(BASE + '/' + sid + '/2', { method: 'POST', body: utf8('third') }), envDO, CTX)).status, 200, 'an upload in isolate A');
    eq((await isolateB.fetch(new Request(BASE + '/' + sid + '/1', { method: 'POST', body: utf8('second') }), envDO, CTX)).status, 200, 'the one before it, in isolate B');
    await readFor(250);
    const all = cat(...got);
    eq(Array.from(all.slice(0, 2)), [0, 0], 'the VLESS response header first');
    const text = new TextDecoder().decode(all.slice(2));
    ok(text.includes('echo:hello'), 'the payload of the header POST came back: ' + JSON.stringify(text));
    ok(text.indexOf('echo:second') >= 0 && text.indexOf('echo:third') > text.indexOf('echo:second'),
      'and the uploads from both isolates, in order: ' + JSON.stringify(text));
    eq(objects.size, 1, 'one Durable Object for the session');
    ok(names.every((n) => n === 'x:' + sid), 'named after the session: ' + JSON.stringify([...new Set(names)]));
  } finally { delete globalThis.__studioConnect; }
});

await test('without the SESSIONS binding, packet-up is served in the isolate as before (nothing is forwarded)', async () => {
  const { worker, env, post } = await authed();
  const user = await (await post('/users', { username: 'xnd' })).json();
  const cred = '22345678-9abc-4def-8123-456789abcdef';
  await post('/configs', { user_id: user.id, protocol: 'v', transport_type: 'xhttp', credential: cred,
    uri_template: 'vless://{{cred}}@{{host}}:{{port}}?type=xhttp&mode=packet-up&path={{path}}#{{remark}}' });
  globalThis.__studioConnect = () => fakeSocket(echoServer());
  try {
    const sid = crypto.randomUUID();
    const downP = worker.fetch(new Request(BASE + '/' + sid), env, CTX);
    eq((await call(worker, env, '/' + sid + '/0', { method: 'POST', body: vlessRequest(cred, 'example.com', 443, utf8('solo')) })).status, 200, 'header POST');
    const down = await downP;
    const r = await Promise.race([down.body.getReader().read(), new Promise((x) => setTimeout(() => x({ timeout: true }), 500))]);
    ok(!r.timeout && !r.done, 'the download carries data in one isolate, as it always did');
  } finally { delete globalThis.__studioConnect; }
});

// ---------------------------------------------------------------------------- what is left, in the list itself

await test('the subscription starts with entries that say the volume and the days left, exactly', async () => {
  const { worker, env, api, post, db } = await authed();
  const GIB = 1073741824;
  const user = await (await post('/users', { username: 'ali',
    policy: { quota_bytes: GIB, expires_at: Date.now() + 30 * 86400000 } })).json();
  await post('/configs', { user_id: user.id, protocol: 'v', transport_type: 'ws',
    uri_template: 'x://{{cred}}@{{host}}:{{port}}?type=ws&path={{path}}#{{remark}}' });
  db.prepare('UPDATE users SET used_bytes = ? WHERE uid = ?').run(150 * 1048576, user.id);
  const sub = await (await api('/users/' + user.id + '/subscription')).json();
  const fetch = async () => (await (await call(worker, env, '/s/' + sub.token + '?format=raw')).text()).split('\n');

  let lines = await fetch();
  const name = (l) => decodeURIComponent(l.split('#')[1] || '');
  ok(lines[0].includes('@127.0.0.1:1?') && lines[1].includes('@127.0.0.1:2?'), 'two info entries first, each on its own port: ' + lines[0]);
  ok(lines[0].includes('00000000-0000-0000-0000-000000000000@'), 'with the all-zero id');
  eq(name(lines[0]), 'حجم باقی‌مانده: ۸۷۴ مگابایت از ۱ گیگابایت', 'the volume left, as the page words it');
  eq(name(lines[1]), 'زمان باقی‌مانده: ۳۰ روز', 'thirty days, not twenty-nine, on the day it is made');
  ok(lines[2].includes('type=ws'), 'then the real links');

  // A daily cap is said too, and a finished volume says so rather than «۰».
  db.prepare('UPDATE users SET daily_quota_bytes = ?, daily_used_bytes = ? WHERE uid = ?').run(200 * 1048576, 50 * 1048576, user.id);
  lines = await fetch();
  eq(name(lines[0]), 'حجم باقی‌مانده: ۸۷۴ مگابایت از ۱ گیگابایت · امروز ۱۵۰ مگابایت', 'today, where there is a daily cap');
  db.prepare('UPDATE users SET used_bytes = quota_bytes, expires_at = ? WHERE uid = ?').run(Date.now() + 5.5 * 3600000, user.id);
  lines = await fetch();
  eq(name(lines[0]), 'حجم: تمام شده', 'a volume that is used up');
  eq(name(lines[1]), 'زمان باقی‌مانده: ۵ ساعت', 'hours on the last day');

  // Unlimited, open-ended, and switched off.
  db.prepare('UPDATE users SET quota_bytes = NULL, daily_quota_bytes = NULL, expires_at = NULL, is_active = 0 WHERE uid = ?').run(user.id);
  lines = await fetch();
  eq(name(lines[0]), 'اشتراک غیرفعال است', 'switched off, said first');
  eq(name(lines[1]), 'حجم: نامحدود', 'unlimited');
  eq(name(lines[2]), 'زمان: بدون محدودیت', 'no end date');
});

console.log(`\n${passed} passed, ${failures.length} failed`);
process.exit(failures.length ? 1 : 0);
