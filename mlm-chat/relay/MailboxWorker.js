// Gereh combined relay: the mailbox in the owner's own Cloudflare Worker (MLM CHAT, SPEC 19.13).
//
// One mailbox, two doors. This Worker is the mailbox; its state lives in one Durable Object (SQLite
// storage, on Cloudflare's free plan). A phone that reaches Cloudflare on its network talks to it
// straight; any other phone goes through the relay's own Google Apps Script (relay/Door.gs), which
// only hands each request on to this Worker. Both doors speak exactly the Google mailbox's protocol
// (relay/Mailbox.gs, SPEC 19.5): the same operations, fields and answers, so the phones need nothing
// new but the choice of door.
//
// Every item is a SealedFrame (SPEC 6, 14.6): end-to-end encrypted and sealed to its recipient, so
// this Worker and Cloudflare see neither the text nor who sent it. Tags are random-looking and change
// every day.
//
// The app makes this file with two fresh random keys in it and puts it on the owner's Cloudflare
// account itself (the account of the app's cloud section):
//   USE_KEY   goes in the owner's contact card and in share links: anyone holding it may leave and
//             take items.
//   OWNER_KEY stays on the owner's phone: statistics, pause, and the daily device limit.
//
// Differences from the Google script, all on the safe side: items live 3 days instead of 6 hours, and
// a waiting take is woken the moment an item for it arrives instead of looking every 800 ms.

const VERSION = 3;
const KIND = "cf";
const USE_KEY = "__MLMCHAT_USE_KEY__";
const OWNER_KEY = "__MLMCHAT_OWNER_KEY__";

const ITEM_TTL_MS = 3 * 86400000;
const MAX_ITEM_CHARS = 98000;
const MAX_ITEMS_PER_PUT = 64;
const MAX_PER_TAG = 400;
const MAX_TAKE_CHARS = 3000000;
const MAX_TAGS = 4;
const MAX_ACKS = 500;
const MAX_WAIT_MS = 20000;
const MAX_WAITERS = 64;
const DAYS_KEPT = 14;
const FLUSH_MS = 300000;
const CLEAN_MS = 3600000;
const MAX_CLIENTS_TRACKED = 2000;
const MAX_TAGS_TRACKED = 1000;
const DEFAULT_MAX_CLIENTS = 40;

const FEED_BUDGET_CHARS = 2000000;
const MAX_FEED_ITEM_CHARS = 24000;
const MAX_FEED_PUT = 16;
const MAX_FEED_INFO = 24;
const MAX_FEED_TAKE_CHARS = 1500000;

const HEX32 = /^[0-9a-f]{32}$/;
const HEX16 = /^[0-9a-f]{16}$/;
const B64 = /^[A-Za-z0-9+/=_-]+$/;

// What a wrong key, a GET or a malformed body gets: the same page as the Google script's decoy.
const DECOY = "<!DOCTYPE html><html><head><title>Web App</title></head>" +
  "<body><p>The script completed but did not return anything.</p></body></html>";

function decoy() {
  return new Response(DECOY, { headers: { "content-type": "text/html; charset=utf-8" } });
}

function json(o) {
  return new Response(JSON.stringify(o), { headers: { "content-type": "application/json" } });
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return decoy();
    if (USE_KEY.indexOf("__MLMCHAT") === 0) return decoy();
    const text = await request.text();
    // a stranger never reaches the Durable Object (its requests count against the free plan). Only
    // a look for the key here, no parsing: a Worker gets 10 ms of CPU on the free plan, and a put
    // of many items is megabytes of JSON; the Durable Object checks everything properly.
    if (text.indexOf('"k":"' + USE_KEY + '"') < 0 && text.indexOf('"o":"' + OWNER_KEY + '"') < 0) return decoy();
    const box = env.MBOX.get(env.MBOX.idFromName("mailbox"));
    return box.fetch("https://mailbox/", { method: "POST", body: text });
  },
};

export class Mailbox {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.db = ctx.storage;
    this.waiters = new Set();
    this.st = null;
    this.cfg = null;
    this.cleaned = 0;
  }

  async fetch(request) {
    let req;
    try {
      req = JSON.parse(await request.text());
    } catch (err) {
      return decoy();
    }
    const owner = typeof req.o === "string" && req.o === OWNER_KEY;
    if (!owner && req.k !== USE_KEY) return decoy();
    const c = typeof req.c === "string" && HEX16.test(req.c) ? req.c : "";
    try {
      const cfg = await this.config();
      if (!owner && cfg.paused && req.op !== "ping") return json({ e: "paused" });
      await this.maybeClean();
      switch (req.op) {
        case "ping":
          return json(await this.ping(c, owner, cfg));
        case "put":
          return json(await this.put(req, c, owner, cfg));
        case "take":
          return json(await this.take(req, c, owner, cfg));
        case "chk":
          return json(await this.chk(req, c, owner, cfg));
        case "fget":
          return json(await this.fget(req, owner));
        case "fput":
          return json(await this.fput(req, owner));
        case "stats":
          return json(owner ? await this.stats(cfg) : { e: "owner" });
        case "admin":
          return json(owner ? await this.admin(req) : { e: "owner" });
        default:
          return json({ e: "bad" });
      }
    } catch (err) {
      return json({ e: "error" });
    }
  }

  async alarm() {
    await this.clean();
    await this.flush(true);
  }

  // ---- operations -------------------------------------------------------------------------

  async ping(c, owner, cfg) {
    const st = await this.stats_();
    this.admit(st, c, owner, cfg, true);
    st.r++;
    await this.flush(false);
    return { ok: 1, ver: VERSION, kind: KIND, owner: owner ? 1 : 0, paused: cfg.paused ? 1 : 0, pub: cfg.pub ? 1 : 0 };
  }

  // Leave items for one tag: `t` the tag, `i` a list of [id, base64]. One result per item.
  async put(req, c, owner, cfg) {
    const tag = req.t;
    if (typeof tag !== "string" || !HEX32.test(tag) || !Array.isArray(req.i) || req.i.length === 0 || req.i.length > MAX_ITEMS_PER_PUT) {
      return { e: "bad" };
    }
    const st = await this.stats_();
    if (!this.admit(st, c, owner, cfg, false)) return { e: "limit" };
    const now = Date.now();
    const qKey = "q:" + tag;
    const q = (await this.db.get(qKey)) || [];
    const ids = req.i.filter((it) => Array.isArray(it) && typeof it[0] === "string" && HEX32.test(it[0])).map((it) => it[0]);
    const done = ids.length ? await this.db.get(ids.map((id) => "d:" + id)) : new Map();
    const values = {};
    const res = [];
    let stored = 0;
    let chars = 0;
    for (const it of req.i) {
      if (!Array.isArray(it) || it.length < 2 || typeof it[0] !== "string" || !HEX32.test(it[0]) || typeof it[1] !== "string" || it[1].length === 0) {
        res.push("bad");
        continue;
      }
      const id = it[0];
      if (done.get("d:" + id)) {
        res.push("done");
        continue;
      }
      if (it[1].length > MAX_ITEM_CHARS) {
        res.push("size");
        continue;
      }
      if (!B64.test(it[1])) {
        res.push("bad");
        continue;
      }
      const at = q.findIndex((x) => x[0] === id);
      if (at < 0) {
        if (q.length >= MAX_PER_TAG) {
          res.push("full");
          continue;
        }
        q.push([id, now]);
      } else {
        q[at][1] = now;
      }
      values["i:" + id] = it[1];
      stored++;
      chars += it[1].length;
      res.push("ok");
    }
    if (stored > 0) {
      values[qKey] = q;
      await this.putMany(values);
      if (Object.keys(st.g).length < MAX_TAGS_TRACKED) st.g[tag] = 1;
      this.wake(tag);
    }
    st.r++;
    st.p++;
    st.ii += stored;
    st.ci += chars;
    await this.flush(false);
    return { ok: 1, r: res };
  }

  // Take the items waiting for up to four tags (`t`), after removing those acknowledged (`a`). With
  // `w`, waits up to that many ms for something to arrive; the put that brings it wakes the take.
  async take(req, c, owner, cfg) {
    const tags = Array.isArray(req.t) ? req.t.filter((t) => typeof t === "string" && HEX32.test(t)).slice(0, MAX_TAGS) : [];
    if (tags.length === 0) return { e: "bad" };
    const acks = Array.isArray(req.a) ? req.a.filter((a) => typeof a === "string" && HEX32.test(a)).slice(0, MAX_ACKS) : [];
    const wait = Math.min(Math.max(Number(req.w) || 0, 0), MAX_WAIT_MS);
    const max = Math.min(Math.max(Number(req.m) || MAX_TAKE_CHARS, 100000), MAX_TAKE_CHARS);
    const out = await this.takeOnce(tags, acks, max, c, owner, cfg, false);
    if (out.e || out.i.length > 0 || wait === 0 || this.waiters.size >= MAX_WAITERS) return out;
    const woke = await new Promise((resolve) => {
      const w = { tags, resolve: null };
      const timer = setTimeout(() => {
        this.waiters.delete(w);
        resolve(false);
      }, wait);
      w.resolve = () => {
        clearTimeout(timer);
        this.waiters.delete(w);
        resolve(true);
      };
      this.waiters.add(w);
    });
    if (!woke) return out;
    return this.takeOnce(tags, [], max, c, owner, cfg, true);
  }

  wake(tag) {
    for (const w of [...this.waiters]) {
      if (w.tags.indexOf(tag) >= 0) w.resolve();
    }
  }

  async takeOnce(tags, acks, max, c, owner, cfg, again) {
    const st = await this.stats_();
    if (!again && !this.admit(st, c, owner, cfg, false)) return { e: "limit" };
    const now = Date.now();
    const qs = await this.db.get(tags.map((t) => "q:" + t));
    const acked = new Set(acks);
    const removed = [];
    const marks = {};
    const items = [];
    const writes = {};
    const drops = [];
    let chars = 0;
    let more = false;
    for (const t of tags) {
      const q = qs.get("q:" + t) || [];
      const keep = [];
      for (const x of q) {
        if (acked.has(x[0])) {
          removed.push("i:" + x[0]);
          marks["d:" + x[0]] = now;
        } else if (now - x[1] < ITEM_TTL_MS) {
          keep.push(x);
        } else {
          removed.push("i:" + x[0]);
        }
      }
      // read 128 at a time (the most one storage get takes), and no more once the answer is full:
      // what is left unread stays queued for the next take
      const alive = [];
      for (let n = 0; n < keep.length; n += 128) {
        const part = keep.slice(n, n + 128);
        if (more) {
          alive.push(...part);
          continue;
        }
        const vals = await this.db.get(part.map((x) => "i:" + x[0]));
        for (const x of part) {
          const d = vals.get("i:" + x[0]);
          if (d === undefined || d === null) continue;
          alive.push(x);
          if (items.length > 0 && chars + d.length > max) {
            more = true;
            continue;
          }
          items.push([x[0], t, d]);
          chars += d.length;
        }
      }
      if (alive.length !== q.length) {
        if (alive.length > 0) writes["q:" + t] = alive;
        else drops.push("q:" + t);
      }
    }
    if (removed.length > 0) {
      for (let n = 0; n < removed.length; n += 128) await this.db.delete(removed.slice(n, n + 128));
      Object.assign(writes, marks);
    }
    if (drops.length > 0) await this.db.delete(drops);
    await this.putMany(writes);
    if (!again) st.r++;
    st.t++;
    st.io += items.length;
    st.co += chars;
    await this.flush(false);
    return { ok: 1, i: items, more: more ? 1 : 0 };
  }

  // Where items are, for the sender: per id "wait", "done" or "gone" (as the Google script).
  async chk(req, c, owner, cfg) {
    const tag = req.t;
    if (typeof tag !== "string" || !HEX32.test(tag) || !Array.isArray(req.i) || req.i.length === 0 || req.i.length > MAX_ITEMS_PER_PUT) {
      return { e: "bad" };
    }
    const ids = req.i.filter((x) => typeof x === "string" && HEX32.test(x));
    if (ids.length !== req.i.length) return { e: "bad" };
    const st = await this.stats_();
    if (!this.admit(st, c, owner, cfg, false)) return { e: "limit" };
    const now = Date.now();
    const q = (await this.db.get("q:" + tag)) || [];
    const keys = [];
    ids.forEach((id) => keys.push("d:" + id, "i:" + id));
    const got = await this.db.get(keys);
    const res = ids.map((id) => {
      if (got.get("d:" + id)) return "done";
      const x = q.find((y) => y[0] === id);
      if (x && now - x[1] < ITEM_TTL_MS && got.get("i:" + id)) return "wait";
      return "gone";
    });
    st.r++;
    await this.flush(false);
    return { ok: 1, r: res };
  }

  async stats(cfg) {
    const st = await this.stats_();
    const days = await this.flush(true);
    return { ok: 1, ver: VERSION, kind: KIND, now: Date.now(), day: st.d, today: summary(st), days, cfg, waiters: this.waiters.size };
  }

  async admin(req) {
    const cfg = await this.config();
    if (typeof req.paused === "boolean") cfg.paused = req.paused;
    if (typeof req.pub === "boolean") cfg.pub = req.pub;
    if (typeof req.max_clients === "number" && req.max_clients >= 0 && req.max_clients <= 100000) cfg.max_clients = Math.floor(req.max_clients);
    await this.db.put("cfg", cfg);
    this.cfg = cfg;
    return { ok: 1, cfg };
  }

  // ---- the official feeds (as the Google script, version 3; SPEC 18.2) ----------------------
  //
  // Items as "f:<seq, 9 digits>" -> [id, feed, class, base64], and "fi:<id>" -> seq to see repeats.

  async fget(req, owner) {
    const feeds = Array.isArray(req.f) ? req.f.filter((f) => typeof f === "string" && HEX32.test(f)).slice(0, 8) : [];
    if (feeds.length === 0) return { e: "bad" };
    const since = Math.max(Math.floor(Number(req.s) || 0), 0);
    const max = Math.min(Math.max(Number(req.m) || MAX_FEED_TAKE_CHARS, 20000), MAX_FEED_TAKE_CHARS);
    const st = await this.stats_();
    if (owner) st.oc++;
    st.r++;
    st.fr = (st.fr || 0) + 1;
    await this.flush(false);
    const all = await this.db.list({ prefix: "f:", start: "f:" + pad9(since + 1) });
    const items = [];
    let chars = 0;
    let more = false;
    let last = since;
    for (const [k, v] of all) {
      const seq = Number(k.slice(2));
      last = Math.max(last, seq);
      if (feeds.indexOf(v[1]) < 0) continue;
      if (items.length > 0 && chars + v[3].length > max) {
        more = true;
        last = items[items.length - 1][2];
        break;
      }
      items.push([v[0], v[1], seq, v[3]]);
      chars += v[3].length;
    }
    const top = (await this.db.get("fseq")) || 0;
    const next = more ? last : Math.max(top, since);
    return { ok: 1, s: next, i: items, more: more ? 1 : 0, cs: (await this.db.get("cs")) || [] };
  }

  async fput(req, owner) {
    const feed = req.f;
    const list = Array.isArray(req.i) ? req.i : [];
    const cs = Array.isArray(req.cs) ? req.cs : [];
    if (typeof feed !== "string" || !HEX32.test(feed) || list.length > MAX_FEED_PUT || (list.length === 0 && cs.length === 0)) {
      return { e: "bad" };
    }
    const st = await this.stats_();
    if (owner) st.oc++;
    st.r++;
    st.fw = (st.fw || 0) + 1;
    await this.flush(false);
    const ids = list.filter((it) => Array.isArray(it) && typeof it[0] === "string" && HEX32.test(it[0])).map((it) => "fi:" + it[0]);
    const have = ids.length ? await this.db.get(ids) : new Map();
    let seq = (await this.db.get("fseq")) || 0;
    const values = {};
    const res = [];
    for (const it of list) {
      if (!Array.isArray(it) || typeof it[0] !== "string" || !HEX32.test(it[0]) || typeof it[1] !== "string" ||
          it[1].length === 0 || it[1].length > MAX_FEED_ITEM_CHARS || !B64.test(it[1])) {
        res.push("bad");
        continue;
      }
      if (have.get("fi:" + it[0]) || values["fi:" + it[0]]) {
        res.push("dup");
        continue;
      }
      seq++;
      const cls = it[2] === 1 || it[2] === 2 ? it[2] : 0;
      values["f:" + pad9(seq)] = [it[0], feed, cls, it[1]];
      values["fi:" + it[0]] = seq;
      res.push("ok");
    }
    if (Object.keys(values).length > 0) {
      values.fseq = seq;
      await this.putMany(values);
      await this.feedPrune();
    }
    if (cs.length > 0) await this.censusMerge(cs);
    return { ok: 1, r: res };
  }

  // Over the budget: the oldest reactions go first, then the oldest posts; per feed, admin lists,
  // names and photos beyond the newest MAX_FEED_INFO go.
  async feedPrune() {
    const all = [...(await this.db.list({ prefix: "f:" }))];
    let total = 0;
    all.forEach(([, v]) => (total += v[3].length));
    const drop = new Set();
    const info = {};
    for (let n = all.length - 1; n >= 0; n--) {
      const [k, v] = all[n];
      if (v[2] !== 1) continue;
      info[v[1]] = (info[v[1]] || 0) + 1;
      if (info[v[1]] > MAX_FEED_INFO) {
        drop.add(k);
        total -= v[3].length;
      }
    }
    for (const cls of [2, 0]) {
      for (let n = 0; n < all.length && total > FEED_BUDGET_CHARS; n++) {
        const [k, v] = all[n];
        if (v[2] === cls && !drop.has(k)) {
          drop.add(k);
          total -= v[3].length;
        }
      }
    }
    if (drop.size === 0) return;
    const keys = [];
    all.forEach(([k, v]) => {
      if (drop.has(k)) keys.push(k, "fi:" + v[0]);
    });
    for (let n = 0; n < keys.length; n += 128) await this.db.delete(keys.slice(n, n + 128));
  }

  // A phone's member count sketches into the kept ones: each counter keeps the higher value; only
  // this period and the last.
  async censusMerge(incoming) {
    const period = Math.floor(Date.now() / 1209600000);
    let mine = (await this.db.get("cs")) || [];
    let changed = false;
    for (const s of incoming) {
      if (!Array.isArray(s) || typeof s[0] !== "number" || typeof s[1] !== "string" || !B64.test(s[1])) continue;
      const epoch = Math.floor(s[0]);
      if (epoch !== period && epoch !== period - 1) continue;
      const theirs = unpack5(s[1]);
      if (!theirs) continue;
      const at = mine.findIndex((x) => x[0] === epoch);
      const regs = (at >= 0 ? unpack5(mine[at][1]) : null) || new Array(2048).fill(0);
      let rose = false;
      for (let i = 0; i < 2048; i++) {
        if (theirs[i] > regs[i]) {
          regs[i] = theirs[i];
          rose = true;
        }
      }
      if (!rose) continue;
      const packed = pack5(regs);
      if (at >= 0) mine[at][1] = packed;
      else mine.push([epoch, packed]);
      changed = true;
    }
    mine = mine.filter((x) => x[0] === period || x[0] === period - 1);
    if (changed) await this.db.put("cs", mine);
  }

  // ---- counting and housekeeping ------------------------------------------------------------

  // A storage put takes at most 128 keys: more go in turns.
  async putMany(values) {
    const all = Object.entries(values);
    for (let n = 0; n < all.length; n += 128) await this.db.put(Object.fromEntries(all.slice(n, n + 128)));
  }

  async config() {
    if (!this.cfg) this.cfg = (await this.db.get("cfg")) || { paused: false, max_clients: DEFAULT_MAX_CLIENTS };
    return this.cfg;
  }

  // The day's totals stay in memory; storage gets them at most every FLUSH_MS (and from the alarm).
  async stats_() {
    const d = Math.floor(Date.now() / 86400000);
    if (this.st && this.st.d === d) return this.st;
    if (this.st) await this.flush(true);
    let st = (await this.db.get("s:" + d)) || null;
    if (!st) st = { d, r: 0, p: 0, t: 0, ii: 0, io: 0, ci: 0, co: 0, oc: 0, fr: 0, fw: 0, cb: 0, gb: 0, c: {}, g: {} };
    st.fl = st.fl || 0;
    this.st = st;
    return st;
  }

  async flush(force) {
    const st = this.st;
    if (!st) return (await this.db.get("days")) || [];
    const now = Date.now();
    if (!force && now - st.fl < FLUSH_MS) return null;
    st.fl = now;
    const days = ((await this.db.get("days")) || []).filter((x) => x.d !== st.d && x.d > st.d - DAYS_KEPT);
    days.push(summary(st));
    days.sort((a, b) => a.d - b.d);
    await this.db.put({ ["s:" + st.d]: st, days });
    const old = (await this.db.list({ prefix: "s:" })).keys();
    const stale = [...old].filter((k) => Number(k.slice(2)) < st.d);
    if (stale.length > 0) await this.db.delete(stale);
    if ((await this.db.getAlarm()) === null) await this.db.setAlarm(now + CLEAN_MS);
    return days;
  }

  admit(st, c, owner, cfg, ping) {
    if (owner) {
      st.oc++;
      return true;
    }
    if (c && !st.c[c]) {
      const n = st.cb + Object.keys(st.c).length;
      if (!ping && cfg.max_clients > 0 && n >= cfg.max_clients) return false;
      if (Object.keys(st.c).length < MAX_CLIENTS_TRACKED) st.c[c] = 1;
    }
    return true;
  }

  async maybeClean() {
    if (Date.now() - this.cleaned < CLEAN_MS) return;
    await this.clean();
  }

  // Items past their 3 days and old "taken" marks go; an empty queue goes.
  async clean() {
    const now = Date.now();
    this.cleaned = now;
    const dead = [];
    const writes = {};
    for (const [k, q] of await this.db.list({ prefix: "q:" })) {
      const alive = q.filter((x) => now - x[1] < ITEM_TTL_MS);
      q.filter((x) => now - x[1] >= ITEM_TTL_MS).forEach((x) => dead.push("i:" + x[0]));
      if (alive.length === 0) dead.push(k);
      else if (alive.length !== q.length) writes[k] = alive;
    }
    for (const [k, at] of await this.db.list({ prefix: "d:" })) {
      if (now - at >= ITEM_TTL_MS) dead.push(k);
    }
    for (let n = 0; n < dead.length; n += 128) await this.db.delete(dead.slice(n, n + 128));
    await this.putMany(writes);
    await this.db.setAlarm(now + CLEAN_MS);
  }
}

function summary(st) {
  return { d: st.d, r: st.r, p: st.p, t: st.t, ii: st.ii, io: st.io, ci: st.ci, co: st.co, oc: st.oc, fr: st.fr || 0, fw: st.fw || 0, c: st.cb + Object.keys(st.c).length, g: st.gb + Object.keys(st.g).length };
}

function pad9(n) {
  return ("000000000" + n).slice(-9);
}

// 2048 counters of 5 bits, packed high bit first (the app's census.rs): 1280 bytes, base64url.
function unpack5(b64) {
  let b;
  try {
    const s = b64.replace(/-/g, "+").replace(/_/g, "/").replace(/=+$/, "");
    const bin = atob(s + "===".slice((s.length + 3) % 4));
    b = Uint8Array.from(bin, (ch) => ch.charCodeAt(0));
  } catch (err) {
    return null;
  }
  if (b.length !== 1280) return null;
  const regs = [];
  for (let i = 0; i < 2048; i++) {
    const bit = i * 5;
    const at = bit >> 3;
    const hi = b[at];
    const lo = at + 1 < 1280 ? b[at + 1] : 0;
    regs.push((((hi << 8) | lo) >> (11 - (bit & 7))) & 31);
  }
  return regs;
}

function pack5(regs) {
  const out = new Uint8Array(1280);
  for (let i = 0; i < 2048; i++) {
    const bit = i * 5;
    const at = bit >> 3;
    const v = (regs[i] & 31) << (11 - (bit & 7));
    out[at] |= (v >> 8) & 255;
    if (at + 1 < 1280) out[at + 1] |= v & 255;
  }
  let bin = "";
  out.forEach((x) => (bin += String.fromCharCode(x)));
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
