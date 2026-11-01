// ==========================================================
// SessionDO — how many devices a person has connected at once
// ==========================================================
//
// A cap is only a cap if the check and the admission happen in one indivisible step. Everything
// simpler than a Durable Object fails exactly under the load the cap exists to limit:
//
//   * **KV** is eventually consistent, up to a minute. Two devices in two colos both read `count = 0`
//     and both are admitted. A limit that fails open under contention is decoration.
//   * **A D1 table** works and the write budget kills it, and D1 has no cross-statement transaction
//     from a Worker, so the read-then-insert is racy anyway.
//   * **A Durable Object** is single-threaded per object, so the critical section is free, and its
//     SQLite storage does not touch the D1 budget at all.
//
// One object **per user**: `idFromName("u:" + uid)`.
//
// **Build 18: devices connected NOW, not devices ever seen.** The limit the operator types is "this
// many phones at the same time" (agreed with the operator, 2026-09-26). The object used to keep every
// device it had ever admitted, forever, so one phone that moved from Wi-Fi to mobile data became two
// devices and a 1-device user was locked out until somebody pressed «فراموش کن». It now keeps a
// device *present* while an isolate reports it, and lets a device that has gone quiet hand its slot
// to a new one -- the same phone on its new network.
//
// And it is asked once per device per isolate per minute, not per connection: one app opens dozens of
// connections, and a Durable Object request for each of them was both the cost and the slowness.
//
// Two rules this file keeps:
//
//   * **It never reads D1.** The caller already loaded the user row to authenticate them, so the
//     policy arrives as an argument.
//   * **No raw client IP is ever stored.** Only `sha256(salt ‖ ip)`, truncated — enough to count
//     distinct addresses, not enough to be a list of who connected from where on somebody else's
//     Cloudflare account.

/** A device counts as connected while an isolate has reported it this recently. */
const STUDIO_PRESENCE_TTL_MS = 150000;

/**
 * A connected device silent this long gives its slot to a new one.
 *
 * Shorter than the TTL, and that gap is the whole behaviour: a phone that is still connected is
 * reported every minute (STUDIO_PRESENCE_TOUCH_MS) and never gets this quiet, while one that changed
 * networks stops being reported the moment its old connections die. So the same phone on its new
 * address is let back in within a minute and a half, and a second phone that is really in use is not.
 */
const STUDIO_PRESENCE_EVICT_MS = 90000;

/** How often an isolate re-reports a device it is carrying traffic for. */
const STUDIO_PRESENCE_TOUCH_MS = 60000;

/** How long an isolate trusts an admission before asking the object again. */
const STUDIO_PRESENCE_CACHE_MS = 60000;

/** Leases from build 17 and earlier, swept on the same schedule so an old table drains. */
const STUDIO_LEASE_TTL_MS = 120000;

/** How often the object wakes to sweep. Wider than the TTL costs nothing. */
const STUDIO_DO_ALARM_MS = 300000;

/**
 * The verdict, as arithmetic — separate from the object so it can be tested without a DO runtime.
 *
 * [counts] is taken inside the critical section:
 *   present       devices connected right now
 *   isPresent     whether THIS device is one of them (it reconnects freely)
 *   oldestIdleMs  how long the quietest OTHER connected device has been silent
 *   distinctIps   distinct addresses among the connected devices; isNewIp whether this one is new
 *
 * Returning a **reason** rather than a boolean is what lets the page tell someone why they were
 * refused. `evict` means "admit, and take the quiet device's slot".
 *
 * `conn_limit` is not consulted any more. A client opens one connection per site it loads, so a
 * count of connections cut people off for browsing; the device count is what the operator meant.
 */
function studioAdmissionVerdict(counts, policy) {
  const p = policy || {};

  // The per-user monitor-only switch. It records the device and refuses nothing, for the person the
  // operator wants to watch before cutting off.
  if (p.enforcement === 'soft') return { allow: true, reason: null, enforced: false };

  const limit = Number(p.device_limit) || 0;
  if (limit > 0 && !counts.isPresent && counts.present >= limit) {
    if (counts.oldestIdleMs >= STUDIO_PRESENCE_EVICT_MS && counts.present - 1 < limit) {
      return { allow: true, reason: null, evict: true };
    }
    return { allow: false, reason: 'device_limit' };
  }
  const ipLimit = Number(p.ip_limit) || 0;
  if (ipLimit > 0 && counts.isNewIp && counts.distinctIps >= ipLimit) {
    return { allow: false, reason: 'ip_limit' };
  }
  return { allow: true, reason: null };
}

/**
 * One XHTTP packet-up request, inside the session's Durable Object (build 23).
 *
 * The object is named after the session (`x:{session}`), so the download GET, the header POST and
 * every upload POST of one connection arrive HERE, in one isolate -- and the per-isolate session
 * table (01-globals.js), which was correct all along except that the requests did not meet, now
 * holds the whole session. The request goes through the ordinary routing; the hop mark keeps it
 * from being forwarded again. The download response streams back through the Worker untouched.
 */
async function studioXhttpInObject(object, request) {
  const ctx = {
    waitUntil(p) { try { object.ctx.waitUntil(p); } catch (e) { } },
    passThroughOnException() { },
  };
  try {
    return await handleRequest(request, object.env, ctx);
  } catch (e) {
    return denyTransport('xhttp-do: ' + (e && e.message || e));
  }
}

export class SessionDO {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.sql = ctx.storage.sql;
    this.ready = false;
  }

  ensure() {
    if (this.ready) return;
    // Kept so an object created by build 17 still opens; nothing writes to it any more.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS leases (
      lease_id TEXT PRIMARY KEY, config_id TEXT, device_hash TEXT, ip_hash TEXT,
      opened_at INTEGER, last_seen INTEGER, up INTEGER DEFAULT 0, down INTEGER DEFAULT 0)`);
    // Every device ever admitted: the history the devices screen shows, and where «مسدود» lives.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS known_devices (
      device_hash TEXT PRIMARY KEY, first_seen INTEGER, last_seen INTEGER,
      last_ip_hash TEXT, client_hint TEXT, blocked INTEGER DEFAULT 0)`);
    // The devices connected right now. This is what the limit counts.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS presence (
      device_hash TEXT PRIMARY KEY, ip_hash TEXT, first_seen INTEGER, last_seen INTEGER)`);
    this.ready = true;
  }

  sweep(now) {
    this.sql.exec('DELETE FROM presence WHERE last_seen < ?', now - STUDIO_PRESENCE_TTL_MS);
    this.sql.exec('DELETE FROM leases WHERE last_seen < ?', now - STUDIO_LEASE_TTL_MS);
  }

  one(query, ...binds) {
    const rows = this.sql.exec(query, ...binds).toArray();
    return rows.length ? rows[0] : {};
  }

  async fetch(request) {
    // An XHTTP packet-up request handed over by the Worker (03-entry.js): this object is the
    // session's meeting point, so the request runs here, through the ordinary routing, against the
    // session state this isolate already holds. Before anything reads the body -- it is the tunnel.
    if (request.headers.get(STUDIO_XHTTP_HOP) === '1') return studioXhttpInObject(this, request);

    this.ensure();
    const url = new URL(request.url);
    let body = {};
    try { body = await request.json(); } catch (e) { }

    switch (url.pathname) {
      case '/open': return this.open(body);
      case '/close': return this.close(body);
      case '/touch': return this.touch(body);
      case '/state': return this.state();
      case '/device': return this.device(body);
      default: return studioJson({ error: 'unknown' }, 404);
    }
  }

  /**
   * Block, unblock or forget one device.
   *
   * Blocking also drops its presence, so the slot is free at once and its next connection is
   * refused. A blocked device's open connections end within the minute an isolate trusts an
   * admission for.
   */
  device(body) {
    const hash = String(body.device_hash || '');
    if (!hash) return studioJson({ error: 'bad_request' }, 400);
    const known = this.one('SELECT device_hash FROM known_devices WHERE device_hash = ?', hash);
    if (!known.device_hash) return studioJson({ error: 'not_found' }, 404);

    switch (body.action) {
      case 'block':
        this.sql.exec('UPDATE known_devices SET blocked = 1 WHERE device_hash = ?', hash);
        this.sql.exec('DELETE FROM presence WHERE device_hash = ?', hash);
        break;
      case 'unblock':
        this.sql.exec('UPDATE known_devices SET blocked = 0 WHERE device_hash = ?', hash);
        break;
      case 'forget':
        this.sql.exec('DELETE FROM known_devices WHERE device_hash = ?', hash);
        this.sql.exec('DELETE FROM presence WHERE device_hash = ?', hash);
        break;
      default:
        return studioJson({ error: 'bad_request' }, 400);
    }
    return studioJson({ ok: true, device_hash: hash, action: body.action });
  }

  /**
   * Admit a device, or refuse it with a reason. One serialized turn: sweep, count, decide, record.
   * Deciding before the sweep would count devices whose connections ended when a colo went away.
   */
  open(body) {
    const now = Date.now();
    this.sweep(now);

    const deviceHash = String(body.device_hash || '');
    const ipHash = String(body.ip_hash || '');
    const policy = body.policy || {};

    if (deviceHash) {
      const dev = this.one('SELECT blocked FROM known_devices WHERE device_hash = ?', deviceHash);
      if (dev.blocked) return studioJson({ allow: false, reason: 'device_blocked' });
    }

    const present = this.one('SELECT COUNT(*) AS n FROM presence').n || 0;
    const isPresent = deviceHash
      ? !!this.one('SELECT 1 AS n FROM presence WHERE device_hash = ?', deviceHash).n
      : false;
    const oldest = this.one(
      'SELECT device_hash, last_seen FROM presence WHERE device_hash <> ? ORDER BY last_seen ASC LIMIT 1',
      deviceHash,
    );
    const distinctIps = this.one('SELECT COUNT(DISTINCT ip_hash) AS n FROM presence').n || 0;
    const isNewIp = ipHash
      ? !this.one('SELECT 1 AS n FROM presence WHERE ip_hash = ?', ipHash).n
      : false;

    const verdict = studioAdmissionVerdict({
      present, isPresent, distinctIps, isNewIp,
      oldestIdleMs: oldest.last_seen ? now - oldest.last_seen : 0,
    }, policy);
    if (!verdict.allow) return studioJson(verdict);

    if (verdict.evict && oldest.device_hash) {
      this.sql.exec('DELETE FROM presence WHERE device_hash = ?', oldest.device_hash);
    }
    if (deviceHash) {
      this.sql.exec(
        `INSERT INTO presence (device_hash, ip_hash, first_seen, last_seen) VALUES (?,?,?,?)
         ON CONFLICT(device_hash) DO UPDATE SET ip_hash = excluded.ip_hash, last_seen = excluded.last_seen`,
        deviceHash, ipHash || null, now, now,
      );
      this.sql.exec(
        `INSERT INTO known_devices (device_hash, first_seen, last_seen, last_ip_hash, client_hint, blocked)
         VALUES (?,?,?,?,?,0)
         ON CONFLICT(device_hash) DO UPDATE SET last_seen = excluded.last_seen,
           last_ip_hash = excluded.last_ip_hash, client_hint = excluded.client_hint`,
        deviceHash, now, now, ipHash || null, String(body.client_hint || '').slice(0, 80),
      );
    }

    // Woken to sweep even if nothing reports again, so a dead device cannot hold a slot forever.
    this.ctx.storage.setAlarm(now + STUDIO_DO_ALARM_MS);

    return studioJson({
      allow: true,
      reason: null,
      evicted: !!verdict.evict,
      live: this.one('SELECT COUNT(*) AS n FROM presence').n || 0,
      devices: this.one('SELECT COUNT(*) AS n FROM known_devices WHERE blocked = 0').n || 0,
    });
  }

  /**
   * A connected device, still connected. Never INSERTs: a device that was evicted or blocked must
   * come back through `open`, where the limit is counted -- `present: false` tells the isolate so.
   */
  touch(body) {
    const hash = String(body.device_hash || '');
    if (!hash) return studioJson({ ok: true, present: false });
    const now = Date.now();
    this.sql.exec('UPDATE presence SET last_seen = ? WHERE device_hash = ?', now, hash);
    const present = !!this.one('SELECT 1 AS n FROM presence WHERE device_hash = ?', hash).n;
    if (present) this.sql.exec('UPDATE known_devices SET last_seen = ? WHERE device_hash = ?', now, hash);
    return studioJson({ ok: true, present });
  }

  /** Build 17's per-connection close. Accepted so an isolate mid-rollout does not error. */
  close(body) {
    if (body.lease_id) this.sql.exec('DELETE FROM leases WHERE lease_id = ?', String(body.lease_id));
    return studioJson({ ok: true });
  }

  /** What the app shows on a user's page: real counts, from the thing that enforces them. */
  state() {
    const now = Date.now();
    this.sweep(now);
    const online = new Set(this.sql.exec('SELECT device_hash FROM presence').toArray().map((r) => r.device_hash));
    const devices = this.sql.exec(
      'SELECT device_hash, first_seen, last_seen, last_ip_hash, client_hint, blocked FROM known_devices ORDER BY last_seen DESC LIMIT 50'
    ).toArray().map((d) => ({ ...d, online: online.has(d.device_hash) }));
    return studioJson({
      live: online.size,
      devices,
      device_count: devices.filter((d) => !d.blocked).length,
    });
  }

  async alarm() {
    this.ensure();
    this.sweep(Date.now());
    // Re-armed only while something is still connected, so an object nobody uses stops waking up.
    if ((this.one('SELECT COUNT(*) AS n FROM presence').n || 0) > 0) {
      this.ctx.storage.setAlarm(Date.now() + STUDIO_DO_ALARM_MS);
    }
  }
}

// ---------------------------------------------------------------------------- the client side

/**
 * Ask the Durable Object whether this device may connect.
 *
 * **Degrades to soft, never to refused.** `env.SESSIONS` is absent on an installation the deployer
 * could not give the object to, and a DO that throws is treated the same way: a tunnel that stops
 * working because a cap could not be *checked* is a worse failure than a cap unenforced for a minute.
 */
async function studioAdmit(env, uid, payload) {
  if (!env.SESSIONS) return { allow: true, mode: 'soft', lease_id: null };
  try {
    const stub = env.SESSIONS.get(env.SESSIONS.idFromName('u:' + uid));
    const res = await stub.fetch('https://do/open', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    const out = await res.json();
    return { ...out, mode: 'strict' };
  } catch (e) {
    return { allow: true, mode: 'soft', lease_id: null, error: String(e && e.message || e) };
  }
}

/** `true`/`false` for whether the device is still counted, or null when the object did not answer. */
async function studioPresenceTouch(env, uid, device) {
  if (!env.SESSIONS || !device) return null;
  try {
    const stub = env.SESSIONS.get(env.SESSIONS.idFromName('u:' + uid));
    const res = await stub.fetch('https://do/touch', { method: 'POST', body: JSON.stringify({ device_hash: device }) });
    const out = await res.json();
    return !!out.present;
  } catch (e) {
    return null;
  }
}

/** uid|device -> admitted until. So a device's forty connections cost one question, not forty. */
const PRESENCE_OK = new Map();

/**
 * Whether this connection's device may connect.
 *
 * Asked only for a person who has a limit -- everyone else costs the object nothing at all -- and
 * at most once a minute per device per isolate. `device`/`ip` come back so the connection can
 * register them with its meter, which keeps them reported while traffic flows.
 */
async function studioPresenceAdmit(env, ctx, user, request) {
  const policy = {
    device_limit: Number(user.device_limit) || 0,
    ip_limit: Number(user.ip_limit) || 0,
    enforcement: user.enforcement,
  };
  if (!(policy.device_limit > 0) && !(policy.ip_limit > 0)) return { allow: true, skip: true };
  if (!request || !env.SESSIONS) return { allow: true, mode: 'soft' };

  const uid = user.uid || user.username;
  const salt = await studioSalt(env.DB);
  const device = await studioDeviceHash(salt, request);
  const ip = await studioIpHash(salt, request);
  const key = uid + '|' + device;
  const now = Date.now();
  const hit = PRESENCE_OK.get(key);
  if (hit && hit.until > now) return { allow: true, device, ip, cached: true, mode: 'strict' };

  const verdict = await studioAdmit(env, uid, {
    device_hash: device,
    ip_hash: ip,
    client_hint: (request.headers.get('User-Agent') || '').slice(0, 80),
    policy,
  });
  if (verdict.allow && verdict.mode === 'strict') {
    if (PRESENCE_OK.size > 5000) {
      for (const [k, v] of PRESENCE_OK) if (v.until <= now) PRESENCE_OK.delete(k);
    }
    PRESENCE_OK.set(key, { until: now + STUDIO_PRESENCE_CACHE_MS });
  }
  return { ...verdict, device, ip };
}

/** A connection of [device] joined this person's meter. */
function studioMeterAddDevice(m, device, ip) {
  if (!m || !device) return;
  const d = m.devices.get(device) || { ip, conns: 0, lastTouch: Date.now() };
  d.conns++;
  d.ip = ip || d.ip;
  m.devices.set(device, d);
}

function studioMeterDropDevice(m, device) {
  if (!m || !device) return;
  const d = m.devices.get(device);
  if (!d) return;
  if (--d.conns <= 0) m.devices.delete(device);
}

/**
 * Keep this person's connected devices counted as connected -- once a minute per device, from the
 * meter's own tick, so it costs nothing per connection. A device the object no longer counts
 * (evicted or blocked) loses its cached admission, and its next connection is asked again.
 */
function studioPresenceTouchAll(env, ctx, m) {
  if (!env.SESSIONS || !m || !m.devices.size) return;
  const now = Date.now();
  const uid = m.uid || m.username;
  for (const [device, d] of m.devices) {
    if (now - d.lastTouch < STUDIO_PRESENCE_TOUCH_MS) continue;
    d.lastTouch = now;
    const task = studioPresenceTouch(env, uid, device).then((present) => {
      if (present === false) PRESENCE_OK.delete(uid + '|' + device);
    });
    if (ctx && ctx.waitUntil) ctx.waitUntil(task);
  }
}


/**
 * The per-installation salt for device and IP hashes.
 *
 * Read once per isolate, not once per connection: it changes never, and a D1 read on the tunnel's
 * admission path is a read multiplied by every connection. Generated on first use so an installation
 * that predates this has one without a migration.
 *
 * The salt is what stops the hashes being a lookup table. `sha256(ip)` with no salt is reversible
 * for the whole IPv4 space in seconds, which would make this a list of subscriber addresses sitting
 * on somebody else's Cloudflare account.
 */
let cachedStudioSalt = null;
async function studioSalt(db) {
  if (cachedStudioSalt) return cachedStudioSalt;
  try {
    let salt = await DbService.getSetting(db, 'studio_hash_salt');
    if (!salt) {
      salt = studioRandomHex(16);
      await DbService.setSetting(db, 'studio_hash_salt', salt);
    }
    cachedStudioSalt = salt;
  } catch (e) {
    // A salt that only lives in this isolate still hashes; it just does not agree with the next
    // isolate's. Counting devices is then wrong until D1 comes back, which beats refusing traffic.
    cachedStudioSalt = studioRandomHex(16);
  }
  return cachedStudioSalt;
}

/**
 * A device fingerprint, and the reason the UI has to call it a guess.
 *
 * `sha256(salt ‖ client hint ‖ /24 of the address)`. Two phones behind one NAT running the same
 * client version collide, and one phone moving between Wi-Fi and mobile data looks like two. That is
 * not fixable without something the client sends and no VPN client sends — so it is surfaced as an
 * approximation everywhere it appears, rather than presented as an identity it is not.
 */
async function studioDeviceHash(salt, request) {
  // The client's own name, and nothing that depends on the transport: `Sec-WebSocket-Version` was
  // part of this until build 18, and it exists on a WebSocket upgrade and not on an XHTTP request --
  // so one phone using both was two devices, and a limit of one refused its second protocol.
  const hint = (request.headers.get('User-Agent') || '') + '|';
  const ip = request.headers.get('CF-Connecting-IP') || '';
  const prefix = ip.includes(':') ? ip.split(':').slice(0, 4).join(':') : ip.split('.').slice(0, 3).join('.');
  return (await DbService.sha256(salt + '|d|' + hint + '|' + prefix)).slice(0, 32);
}

async function studioIpHash(salt, request) {
  const ip = request.headers.get('CF-Connecting-IP') || '';
  if (!ip) return '';
  return (await DbService.sha256(salt + '|i|' + ip)).slice(0, 32);
}

// ---------------------------------------------------------------------------- the API surface

/**
 * `GET /v1/users/{id}/devices` — the devices this person has actually connected from.
 *
 * Read from the Durable Object, not from D1, and that is the point: the object is the thing that
 * counts them when it decides whether to admit a connection, so anything else would be a second
 * number that can disagree with the one being enforced.
 *
 * **When there is no object, this says so rather than returning an empty list.** Zero devices and
 * "this installation cannot count devices" look identical in an empty array, and only one of them
 * means the person has never connected. The plan's rule about never drawing a number this
 * installation cannot measure applies to the operator's screen exactly as it does to the
 * subscriber's page.
 */
async function studioUserDevices(env, uid) {
  if (!env.SESSIONS) {
    return studioJson({ available: false, reason: 'do_unavailable', devices: [], live: 0 });
  }
  try {
    const stub = env.SESSIONS.get(env.SESSIONS.idFromName('u:' + uid));
    const res = await stub.fetch('https://do/state', { method: 'POST', body: '{}' });
    const body = await res.json();
    return studioJson({ available: true, ...body });
  } catch (e) {
    return studioJson({ available: false, reason: 'do_error', devices: [], live: 0 });
  }
}

/**
 * Block, unblock or forget one device.
 *
 * Forgetting is not the same as blocking and both are offered: a device the person has stopped
 * using should free its slot, while one that should never have had access has to stay refused even
 * though it will keep trying. A blocked device is refused before any cap is counted, because that
 * is a decision about the device rather than a question of how many are in use.
 */
async function studioDeviceAction(env, uid, hash, action, actor) {
  if (!env.SESSIONS) return studioErr('do_unavailable', 'this installation cannot manage devices', 409);
  try {
    const stub = env.SESSIONS.get(env.SESSIONS.idFromName('u:' + uid));
    const res = await stub.fetch('https://do/device', {
      method: 'POST',
      body: JSON.stringify({ device_hash: hash, action }),
    });
    if (!res.ok) return studioErr('not_found', 'no such device', 404);
    await env.DB.batch([
      studioAuditStmt(env, actor, 'device.' + action, uid, null, { device_hash: hash }, 'device'),
    ]);
    return studioJson(await res.json());
  } catch (e) {
    return studioErr('do_error', 'the session object did not answer', 503);
  }
}

/** Returns null when the path is not its business, so `studioHandle` stays a list. */
async function studioDevicesRoute(request, env, path, method, actor) {
  const list = path.match(/^\/users\/([^/]+)\/devices$/);
  if (list && method === 'GET') return await studioUserDevices(env, list[1]);

  const act = path.match(/^\/users\/([^/]+)\/devices\/([^/:]+):(block|unblock|forget)$/);
  if (act) {
    if (method !== 'POST') return studioErr('method_not_allowed', 'actions are POST', 405);
    return await studioDeviceAction(env, act[1], act[2], act[3], actor);
  }
  return null;
}

/**
 * Whether some enabled config claims this path (E1).
 *
 * One lookup on `ux_configs_route`, the unique index that exists for exactly this, so the cost is a
 * single indexed row rather than a scan — the difference between a per-connection read and a
 * per-connection read of the whole configs table.
 *
 * **Cached briefly, since build 18** -- a minute for a path that is served, ten seconds for one that
 * is not. This read sat in front of every WebSocket upgrade on a config's own path, before the 101,
 * so a slow D1 minute was a slow connect for everybody on such a config. The bounds keep the old
 * worry small: a deleted config's path answers 101 for at most a minute (and its credential is
 * refused on the first frame anyway), and a config created in another isolate is reachable within
 * ten seconds. The control plane clears the cache in its own isolate on every write.
 *
 * Fails CLOSED: a database that cannot be read means the path is not served, and the request falls
 * through to the camouflage page. The alternative — treating an unreadable database as "yes" —
 * would answer 101 on every path the moment D1 had a bad minute.
 */
const ROUTE_KEY_CACHE = new Map();
async function studioRouteKeyServed(env, pathname) {
  const key = pathname.replace(/^\/+/, '');
  if (!key || key.length > 128) return false;
  const now = Date.now();
  const hit = ROUTE_KEY_CACHE.get(key);
  if (hit && hit.until > now) return hit.served;
  try {
    const row = await env.DB.prepare(
      'SELECT id FROM configs WHERE route_key = ? AND enabled = 1 AND deleted_at IS NULL'
    ).bind(key).first();
    const served = !!row;
    if (ROUTE_KEY_CACHE.size > 2000) ROUTE_KEY_CACHE.clear();
    ROUTE_KEY_CACHE.set(key, { served, until: now + (served ? 60000 : 10000) });
    return served;
  } catch (e) {
    return false;
  }
}
