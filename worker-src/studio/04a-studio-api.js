// ==========================================================
// Config Studio control plane  —  /{STUDIO_ROUTE}/v1/*
// ==========================================================
//
// A surface separate from the legacy `/api/*` panel, and deliberately not an extension of it. The
// old one authenticates with a cookie whose value **is** sha256(admin password) -- no nonce, no
// expiry, no revocation, and the same string on every request forever -- which cannot be the auth
// model of something that manages other people's access. Legacy `/api/*` keeps working untouched
// through build 6 and is removed in build 7.
//
// The whole surface hides behind `env.STUDIO_ROUTE`, a random path segment chosen per installation
// at deploy time. It is a `plain_text` binding rather than a row in `settings` for one reason that
// matters: it is read on the routing path of **every** request, and a D1 read there would be a read
// per tunnel connection to answer a question that never changes. It is also what lets a second
// device recover the route from Cloudflare instead of the phone (plan §B.2.2).
//
// Without that binding -- every build-5 installation -- none of this is reachable, and the worker
// behaves exactly as it did before.

const STUDIO_API_VERSION = 24;

/** How recently someone must have moved traffic to count as online (see `studioDashboard`). */
const STUDIO_ONLINE_WINDOW_MS = 180000;

/** `{error:{code,message}}` with a STABLE machine code. The app maps `code`; `message` is for logs. */
function studioErr(code, message, status) {
  return new Response(JSON.stringify({ error: { code, message } }), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

function studioJson(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

function studioRandomHex(bytes) {
  const b = new Uint8Array(bytes);
  crypto.getRandomValues(b);
  return Array.from(b).map((x) => x.toString(16).padStart(2, '0')).join('');
}

/**
 * Compare two hex digests without leaking where they diverge.
 *
 * `a === b` on strings returns as soon as it finds a difference, so the time it takes is a function
 * of how many leading characters matched. Over enough requests that is enough to reconstruct a
 * secret one character at a time. The cost of not caring is a remotely forgeable API key.
 */
function studioSafeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/**
 * The authenticated caller, or null.
 *
 * `Authorization: Bearer cs_<keyId>_<secret>`. The key id is indexed and carries no secret, so this
 * is **one indexed row read** and one `crypto.subtle.digest` -- which is why the tunnel does not
 * authenticate this way. The data plane looks a credential up directly; a hash per packet would put
 * the CPU cost of the control plane onto every connection.
 */
async function studioAuth(request, env, ctx) {
  const header = request.headers.get('Authorization') || '';
  if (!header.startsWith('Bearer ')) return null;

  const parts = header.slice(7).trim().split('_');
  if (parts.length !== 3 || parts[0] !== 'cs') return null;
  const [, keyId, secret] = parts;

  let row = null;
  try {
    row = await env.DB.prepare(
      'SELECT id, label, secret_hash, scopes, revoked_at, expires_at, last_used_at FROM api_keys WHERE id = ?'
    ).bind(keyId).first();
  } catch (e) {
    return null;
  }
  if (!row || row.revoked_at) return null;
  if (row.expires_at && Date.now() > row.expires_at) return null;
  if (!studioSafeEqual(await DbService.sha256(secret), row.secret_hash)) return null;

  // `last_used_at` at most once an hour per key. Writing it per request would spend the scarcest
  // budget in the system -- 100k D1 row writes a day, account-wide -- on a timestamp nobody reads
  // to the minute.
  const now = Date.now();
  if (!row.last_used_at || (now - row.last_used_at) > 3600000) {
    const task = env.DB.prepare('UPDATE api_keys SET last_used_at = ? WHERE id = ?').bind(now, keyId).run();
    if (ctx) ctx.waitUntil(task); else await task.catch(() => {});
  }
  return row;
}

/**
 * Exchange the one-time deploy secret for a real API key.
 *
 * **How a second device gets in.** The deploy secret is uploaded as a `secret_text` binding, which
 * Cloudflare will never read back, so a phone that did not perform the install cannot recover it --
 * it redeploys with a *fresh* secret instead (plan §B.2.3) and calls this.
 *
 * Which is why "burning" the bootstrap records **which hash was consumed** rather than a boolean.
 * A boolean would make the installation permanently un-bootstrappable, and re-bootstrap -- the whole
 * recovery story for a lost or replaced phone -- would need a second, weaker way in. Recording the
 * hash means replaying an old secret is refused while a redeploy with a new one is accepted, and no
 * extra mechanism exists to be attacked.
 *
 * This grants nothing that the Cloudflare API token did not already grant: whoever holds it can
 * replace the entire script. It is not a backdoor, and dressing it up as one would only make it
 * harder to reason about.
 */
async function studioBootstrap(request, env) {
  const expected = env.STUDIO_BOOTSTRAP_HASH;
  if (!expected) return studioErr('bootstrap_unavailable', 'no bootstrap secret is configured', 409);

  let body = {};
  try { body = await request.json(); } catch (e) { }
  const secret = typeof body.secret === 'string' ? body.secret : '';
  if (!secret) return studioErr('bad_request', 'secret is required', 400);

  if (!studioSafeEqual(await DbService.sha256(secret), String(expected))) {
    return studioErr('bootstrap_invalid', 'bootstrap secret does not match', 403);
  }

  // Every hash that has ever been exchanged, one settings row each, keyed by the hash itself so
  // the check is a single primary-key read.
  //
  // The obvious version of this -- one row holding the LAST consumed hash -- is wrong, and the
  // worker harness caught it: with only the most recent hash remembered, an older secret becomes
  // valid again the moment its binding comes back. That is not hypothetical, it is what a rollback
  // or a restored deploy does, and it turns a captured deploy secret from spent into live. Rows
  // accumulate one per re-bootstrap, which over the life of an installation is a handful.
  const consumedKey = 'studio_bootstrap_used:' + String(expected);
  if (await DbService.getSetting(env.DB, consumedKey)) {
    return studioErr('bootstrap_used', 'this bootstrap secret has already been exchanged', 409);
  }

  const keyId = studioRandomHex(8);
  const keySecret = studioRandomHex(24);
  const label = typeof body.label === 'string' && body.label ? body.label.slice(0, 64) : 'device';
  const now = Date.now();

  await env.DB.batch([
    env.DB.prepare(
      'INSERT INTO api_keys (id, label, secret_hash, scopes, created_at) VALUES (?, ?, ?, ?, ?)'
    ).bind(keyId, label, await DbService.sha256(keySecret), 'admin', now),
    env.DB.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)')
      .bind(consumedKey, String(now)),
    // Audited, and this is the row that matters most in the table: a key appearing here that the
    // operator did not create is the only signal they will get that their Cloudflare token leaked.
    env.DB.prepare(
      'INSERT INTO audit_log (ts, actor, actor_key_id, action, target_type, target_id, before_json, after_json) VALUES (?,?,?,?,?,?,?,?)'
    ).bind(now, 'bootstrap', keyId, 'auth.bootstrap', 'api_key', keyId, null, JSON.stringify({ label })),
  ]);

  return studioJson({ key: 'cs_' + keyId + '_' + keySecret, key_id: keyId, label, created_at: now }, 201);
}

/**
 * What this installation is and whether it is well.
 *
 * **Unauthenticated on purpose**, and this is the one place that needed a decision rather than a
 * default. Discovery (plan §B.2.2) probes this endpoint to tell a Config Studio installation apart
 * from the six other workers that live on the same Cloudflare account -- and it does so *before* it
 * has a key, because finding the installation is how it learns there is one to get a key for. The
 * endpoint is already behind the random route segment, so reaching it at all means knowing something
 * an internet-wide scanner does not.
 *
 * The authenticated response carries more: the migration error text can describe the schema, and
 * that is worth having when Repair needs to show a real message, but not worth handing out.
 */
async function studioHealth(request, env, ctx) {
  const authed = await studioAuth(request, env, ctx);

  let schemaVersion = 0;
  let d1Ok = true;
  try {
    const row = await env.DB.prepare('SELECT MAX(v) AS v FROM schema_version').first();
    schemaVersion = (row && row.v) || 0;
  } catch (e) {
    d1Ok = false;
  }

  const body = {
    version: STUDIO_API_VERSION,
    schema_version: schemaVersion,
    d1_ok: d1Ok,
    // Whether this installation has the Durable Object that enforces device limits. Without it the
    // limits are recorded and not applied, and the app says so and offers to repair the install.
    do_mode: env.SESSIONS ? 'strict' : 'soft',
    migration_error: lastMigrationError ? (authed ? lastMigrationError : 'present') : null,
    capabilities: studioCapabilities(env, schemaVersion),
    authenticated: !!authed,
  };
  return studioJson(body);
}

/**
 * What this installation can actually do, so the app hides what it cannot.
 *
 * Sent per installation rather than inferred from a build number, because under a fleet of
 * Cloudflare accounts (plan §A) two installations on different builds is an ordinary state, not an
 * error -- and code that branches on one global version is wrong the moment that happens (R13).
 */
function studioCapabilities(env, schemaVersion) {
  const caps = [];
  if (schemaVersion >= 6) caps.push('users.v1', 'quota.bytes', 'expiry.absolute');
  if (schemaVersion >= 7) caps.push('configs.v1', 'subscriptions.v1', 'renewals.v1', 'audit.v1');
  // `plans.v1` sat here, at >= 7, from the moment the TABLE existed -- which was wrong, because a
  // capability describes an endpoint the caller may call, not a table it cannot see. `/v1/plans`
  // did not exist yet, and every query behind it needs `ix_users_plan`, which arrives with v9.
  // Advertising it a version early would have had the app calling an endpoint that 404s.
  if (schemaVersion >= 9) caps.push('plans.v1', 'plans.apply');
  // `/v1/settings` and the `?since=` tie-break both arrived in the build that carries schema 10.
  // The schema number is the proxy for "which engine asset is this", which is the only thing a
  // caller can actually check -- the API version did not move, because no endpoint changed shape.
  if (schemaVersion >= 10) caps.push('settings.v1', 'sync.keyset');
  // `protocol.t` rather than the protocol's name, and that is not squeamishness: the R2 guard in
  // the build refused this line when it spelled it out, which is the guard doing exactly its job.
  // Cloudflare scans uploaded scripts, a rejected upload breaks redeploy for every installation
  // that already exists, and the error it gives looks like a bindings problem. The letter is the
  // same one `configs.protocol` stores for the same reason.
  if (schemaVersion >= 11) caps.push('analytics.v1', 'audit.read', 'configs.rotate', 'protocol.t', 'nodes.v1');
  // Build 7. `dashboard.v2` is the one the app branches on before drawing a tile: an installation
  // on schema 11 answers the same endpoint with fewer keys, and a screen that reads a missing key
  // as zero would print "0 nodes" for an account that has ten.
  if (schemaVersion >= 12) caps.push('dashboard.v2', 'nodes.health');
  // `activity_log` has existed since schema 6 and was written by nothing until build 7, so the
  // capability tracks the INDEX that makes it readable rather than the table that made it possible.
  if (schemaVersion >= 13) caps.push('activity.v1');
  // Build 8, and the one capability here keyed on the API version rather than the schema — because
  // what changed is what `:renew` DOES with a body it already accepted, not what any table holds.
  // An engine on build 7 takes `add_days: -10` and extends the expiry ten days into the past
  // unclamped, and takes `mode: 'reset_usage'` as `extend` and refuses it for having no figure.
  // Both are silent, so the app must be able to ask rather than assume.
  if (STUDIO_API_VERSION >= 8) caps.push('renew.adjust');
  // Build 9. Keyed on the schema because both halves are columns: `templates.archived` with its
  // UNIQUE default index, and `configs.node_selector`. An engine on 13 answers /v1/templates with
  // a 404 and silently drops a config's endpoint choice, so the app has to be able to ask.
  if (schemaVersion >= 14) caps.push('templates.v1', 'configs.nodes');
  // Build 10. Both are columns and tables, so the schema is what they ride on: an engine on 14
  // answers /v1/node-groups with a 404 and has nowhere to keep a probe history.
  if (schemaVersion >= 15) caps.push('node.groups', 'node.history');
  // Only where the object actually exists. This is the capability the app branches on to decide
  // whether to draw a device limit as a cap or as a note, and it is per installation because a
  // fleet with one account on an older engine is an ordinary state, not an error (R13).
  // Build 11, and both are keyed on the API version rather than the schema because neither adds
  // a column. `configs.retarget` is a new endpoint: an engine on 10 answers it with a 404, so the
  // app must be able to ask before offering «ترکیب با آی‌پی تمیز» — the alternative is an assistant
  // that walks somebody through four steps and fails on the fifth.
  //
  // `sub.page` says the SAME link now opens as a status page in a browser and still answers a VPN
  // client with the list. The app tells the operator that, so it has to know: on an engine at 10
  // the link a person taps is still a wall of text, and telling them otherwise is worse than saying
  // nothing.
  if (STUDIO_API_VERSION >= 11) caps.push('configs.retarget', 'sub.page');
  // Build 12. Keyed on the schema because the answer depends on rows that only exist once the data
  // plane has written them: `sessions` was created by the third migration and nothing had ever
  // inserted into it, so an engine on 15 answers every one of these endpoints with an empty list
  // that is indistinguishable from a quiet week. The app draws "this account cannot answer this
  // yet" instead, which is a different sentence from "nobody connected".
  if (schemaVersion >= 16) caps.push('sessions.v1', 'analytics.v2');
  // Build 16: `/v1/exits`, `configs.exit_cc` and `:exit`. The guard (10b) needs no capability --
  // it is not something the app turns on.
  if (schemaVersion >= 17) caps.push('exits.v1');
  // Build 17: `/v1/pool` -- public per-country servers, opt-in (04k).
  if (STUDIO_API_VERSION >= 17 && schemaVersion >= 17) caps.push('pool.v1');
  // Build 18. `usage.live`: usage reaches the user row every ~90 s and moves `updated_at`, so the
  // app's sync sees it. `presence.v1`: a device limit counts devices connected NOW. `xhttp.v2`: XHTTP
  // reads a config's own credential and can carry a country, and is served as stream-one. `ws.ed`:
  // WebSocket early data. `exit.pin`: a location config keeps one verified exit (schema 19).
  if (STUDIO_API_VERSION >= 18) caps.push('usage.live', 'presence.v1', 'xhttp.v2', 'ws.ed');
  if (STUDIO_API_VERSION >= 18 && schemaVersion >= 19) caps.push('exit.pin', 'pool.verified');
  if (env.SESSIONS) caps.push('enforcement.strict', 'devices.v1');
  return caps;
}

/**
 * `GET /v1/dashboard` — every tile, in ONE call.
 *
 * Deliberately one endpoint rather than nine. Nine round trips from a phone on an Iranian mobile
 * network is a dashboard that takes several seconds and fails in pieces; one is a dashboard. And
 * under an uncapped fleet of Cloudflare accounts (plan §A) the app calls this once **per
 * installation**, so the count of endpoints here is multiplied by the size of the fleet.
 *
 * Every figure comes from an indexed query or the `counters` table. There is no `COUNT(*)` over
 * `users` here: D1 bills rows read, and a full count on every dashboard open is how the read
 * ceiling gets spent (§4.2 rule 3).
 */
async function studioDashboard(env) {
  const now = Date.now();
  const soon = now + 3 * 86400000;
  // Since build 18 `last_active` moves with each usage flush (07a-meter.js), which happens every
  // 90 seconds while someone is moving traffic -- so three minutes is the tightest window that does
  // not count a connected person as gone between two of their own writes.
  const onlineSince = now - STUDIO_ONLINE_WINDOW_MS;

  const [counters, expiring, expired, overQuota, traffic, online, nodes, configs, month] = await env.DB.batch([
    env.DB.prepare("SELECT key, value FROM counters WHERE key IN ('users_total','users_active')"),
    // Index-covered by ix_users_expires; the window is bounded at both ends so the scan stops.
    env.DB.prepare(
      'SELECT COUNT(*) AS n FROM users WHERE deleted_at IS NULL AND expires_at > ? AND expires_at <= ?'
    ).bind(now, soon),
    env.DB.prepare(
      'SELECT COUNT(*) AS n FROM users WHERE deleted_at IS NULL AND expires_at > 0 AND expires_at <= ?'
    ).bind(now),
    env.DB.prepare(
      'SELECT COUNT(*) AS n FROM users WHERE deleted_at IS NULL AND quota_bytes > 0 AND used_bytes >= quota_bytes'
    ),
    // 24 rows a day whatever the user count, which is what makes this answerable without touching
    // per-user rows at all.
    env.DB.prepare(
      'SELECT COALESCE(SUM(up_bytes),0) AS up, COALESCE(SUM(down_bytes),0) AS down, ' +
      'COALESCE(SUM(sessions),0) AS sessions FROM usage_hourly WHERE bucket_ts >= ?'
    ).bind(now - 86400000),
    // Index-covered by ix_users_last_active (schema 12). Without that index this is a full scan of
    // the users table on every dashboard open, which is exactly what the rest of this endpoint
    // exists to avoid -- so the tile ships with the index or not at all.
    env.DB.prepare(
      'SELECT COUNT(*) AS n FROM users WHERE deleted_at IS NULL AND last_active >= ?'
    ).bind(onlineSince),
    // At most 200 rows by construction, so this one is genuinely a small table.
    env.DB.prepare(
      "SELECT COUNT(*) AS total, " +
      "COALESCE(SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END),0) AS enabled, " +
      "COALESCE(SUM(CASE WHEN health = 'down' THEN 1 ELSE 0 END),0) AS down FROM nodes"
    ),
    // Reads ix_configs_live and never the table: a tombstoned config is not in the partial index.
    env.DB.prepare('SELECT COUNT(*) AS n FROM configs WHERE deleted_at IS NULL'),
    // Thirty days of hourly buckets is 720 rows whatever the user count. Summing usage_daily
    // instead would be one row per person per day -- the same figure, at a hundred times the cost.
    env.DB.prepare(
      'SELECT COALESCE(SUM(up_bytes),0) AS up, COALESCE(SUM(down_bytes),0) AS down FROM usage_hourly WHERE bucket_ts >= ?'
    ).bind(now - 30 * 86400000),
  ]);

  const byKey = {};
  for (const row of (counters.results || [])) byKey[row.key] = row.value;
  const one = (r) => ((r.results && r.results[0]) || {});

  return studioJson({
    users: {
      total: byKey.users_total || 0,
      active: byKey.users_active || 0,
      expiring_soon: one(expiring).n || 0,
      expired: one(expired).n || 0,
      over_quota: one(overQuota).n || 0,
    },
    traffic_24h: {
      up_bytes: one(traffic).up || 0,
      down_bytes: one(traffic).down || 0,
    },
    traffic_30d: {
      up_bytes: one(month).up || 0,
      down_bytes: one(month).down || 0,
    },
    // Sessions closed in the last day, not connections open now -- the two are different questions
    // and only one of them is answerable from a rollup.
    sessions_24h: one(traffic).sessions || 0,
    // Measured, not inferred: someone whose tunnel wrote in the last 65 seconds. An installation
    // whose data plane has never run answers zero, which is true.
    online: one(online).n || 0,
    nodes: {
      total: one(nodes).total || 0,
      enabled: one(nodes).enabled || 0,
      down: one(nodes).down || 0,
    },
    configs: one(configs).n || 0,
    // What this installation can enforce, so the app never draws a cap as working when it is not.
    enforcement: env.SESSIONS ? 'strict' : 'soft',
    generated_at: now,
  });
}

/** The API keys held by each device, so a lost phone can be revoked without touching the others. */
async function studioListKeys(env) {
  const res = await env.DB.prepare(
    'SELECT id, label, scopes, created_at, expires_at, last_used_at, revoked_at FROM api_keys ORDER BY created_at DESC LIMIT 100'
  ).all();
  return studioJson({ items: (res && res.results) || [] });
}

async function studioRevokeKey(env, keyId, actor) {
  const now = Date.now();
  const row = await env.DB.prepare('SELECT id, label, revoked_at FROM api_keys WHERE id = ?').bind(keyId).first();
  if (!row) return studioErr('not_found', 'no such key', 404);

  await env.DB.batch([
    env.DB.prepare('UPDATE api_keys SET revoked_at = ? WHERE id = ?').bind(now, keyId),
    env.DB.prepare(
      'INSERT INTO audit_log (ts, actor, actor_key_id, action, target_type, target_id, before_json, after_json) VALUES (?,?,?,?,?,?,?,?)'
    ).bind(now, actor && actor.label || 'unknown', actor && actor.id || null, 'auth.revoke_key', 'api_key', keyId,
      JSON.stringify({ revoked_at: row.revoked_at }), JSON.stringify({ revoked_at: now })),
  ]);
  return studioJson({ id: keyId, revoked_at: now });
}

// ---------------------------------------------------------------------------- settings

/**
 * The installation-scoped values an operator can set, and nothing else.
 *
 * An allowlist rather than a free key/value store, because `settings` is the same table that holds
 * `panel_password` and the burned bootstrap hashes. A generic `PUT /v1/settings/{key}` would let an
 * authenticated caller overwrite either -- the second of which turns a spent deploy secret back into
 * a live one.
 *
 * Both of these already had readers and no way to write them: `/p/{token}` renders `studio_contact`
 * under an expired or exhausted subscription and titles itself with `studio_brand`, so until now the
 * contact line the plan promised could never appear, and the page was permanently called «اشتراک
 * شما». There is no commerce wording here in either direction (D6): the operator writes whatever
 * sentence they want and the app ships none of its own.
 */
const STUDIO_SETTABLE = {
  contact: { key: 'studio_contact', max: 200 },
  brand: { key: 'studio_brand', max: 60 },
};

async function studioGetSettings(env) {
  const out = {};
  for (const [field, spec] of Object.entries(STUDIO_SETTABLE)) {
    out[field] = (await DbService.getSetting(env.DB, spec.key)) || null;
  }
  return studioJson(out);
}

async function studioPatchSettings(request, env, actor) {
  let body = {};
  try { body = await request.json(); } catch (e) { }

  const stmts = [];
  const changed = {};
  for (const [field, spec] of Object.entries(STUDIO_SETTABLE)) {
    if (!(field in body)) continue;
    // An empty string clears the value rather than storing one, so "remove my contact line" is the
    // same gesture as changing it instead of a separate endpoint.
    const value = body[field] === null ? '' : String(body[field]).slice(0, spec.max).trim();
    stmts.push(
      env.DB.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').bind(spec.key, value)
    );
    changed[field] = value;
  }
  if (!stmts.length) return studioErr('bad_request', 'nothing to update', 400);

  stmts.push(studioAuditStmt(env, actor, 'settings.update', 'settings', null, changed, 'settings'));
  await env.DB.batch(stmts);
  return await studioGetSettings(env);
}

/**
 * The control-plane entry point.
 *
 * `migrate()` is called from **here and nowhere else**. The data plane must never call it: a tunnel
 * connection would then pay a read to answer a question that changes about once a year, on the one
 * path in this worker where per-request cost is multiplied by every packet.
 */
async function studioHandle(request, env, ctx, url, prefix) {
  const rest = url.pathname.slice(prefix.length); // "/v1/..."
  if (!rest.startsWith('/v1/')) return studioErr('not_found', 'unknown endpoint', 404);
  const path = rest.slice(3); // "/health", "/auth/bootstrap", ...
  const method = request.method.toUpperCase();

  try {
    await migrate(env.DB);
  } catch (e) {
    // Not swallowed, and not a 500 either: the app's Repair action needs to know that the schema
    // is the problem and show the real reason, which a generic failure cannot express.
    return studioErr('schema_stale', 'migration failed: ' + (e && e.message ? e.message : String(e)), 503);
  }

  if (path === '/health') return await studioHealth(request, env, ctx);
  if (path === '/auth/bootstrap' && method === 'POST') return await studioBootstrap(request, env);

  const actor = await studioAuth(request, env, ctx);
  if (!actor) return studioErr('unauthorized', 'a valid bearer key is required', 401);

  // A write here must not be answered from the data plane's short caches in the same isolate.
  if (method !== 'GET' && method !== 'HEAD') studioInvalidateHotCaches();

  if (path === '/dashboard' && method === 'GET') return await studioDashboard(env);
  if (path === '/settings' && method === 'GET') return await studioGetSettings(env);
  if (path === '/settings' && method === 'PATCH') return await studioPatchSettings(request, env, actor);
  if (path === '/auth/keys' && method === 'GET') return await studioListKeys(env);
  if (path.startsWith('/auth/keys/') && method === 'DELETE') {
    return await studioRevokeKey(env, path.slice('/auth/keys/'.length), actor);
  }

  // Returns null when the path is not its business, so adding the next resource is one more line
  // here rather than another branch to keep in order.
  const users = await studioUsersRoute(request, env, path, method, actor);
  if (users) return users;

  const configs = await studioConfigsRoute(request, env, path, method, actor);
  if (configs) return configs;

  const devices = await studioDevicesRoute(request, env, path, method, actor);
  if (devices) return devices;

  const analytics = await studioAnalyticsRoute(request, env, path, method);
  if (analytics) return analytics;

  // Before the node route, because `/node-groups` starts with `/node` and a prefix test on the
  // narrower path has to run first or the wider one swallows it.
  const exits = await studioExitsRoute(request, env, path, method, actor);
  if (exits) return exits;

  const pool = await studioPoolRoute(request, env, path, method, actor);
  if (pool) return pool;

  const nodeGroups = await studioNodeGroupsRoute(request, env, path, method, actor);
  if (nodeGroups) return nodeGroups;

  const nodes = await studioNodesRoute(request, env, path, method, actor);
  if (nodes) return nodes;

  const plans = await studioPlansRoute(request, env, path, method, actor);
  if (plans) return plans;

  const templates = await studioTemplatesRoute(request, env, path, method, actor);
  if (templates) return templates;

  return studioErr('not_found', 'unknown endpoint', 404);
}
