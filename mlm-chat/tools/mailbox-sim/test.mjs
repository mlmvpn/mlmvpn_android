// Runs relay/Mailbox.gs as Apps Script would, with CacheService, LockService, PropertiesService,
// ContentService and Utilities emulated (sizes and lifetimes as Google documents them), and checks
// every operation the core uses. No network, no Google account.
//
//   node tools/mailbox-sim/test.mjs            (from mlm-chat)
//
// The core's own tests use a Rust mailbox emulator (core/tests/gas.rs); this one checks the real
// script, so the two cannot drift apart unnoticed.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";
import assert from "node:assert/strict";

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(here, "..", "..", "relay", "Mailbox.gs"), "utf8");
const USE = "a".repeat(32);
const OWNER = "b".repeat(32);

function makeEnv(code) {
  const clock = { now: 1_800_000_000_000 };
  const cache = new Map(); // key -> {v, exp}
  const props = new Map();
  const propOps = { reads: 0, writes: 0 };
  let onSleep = null;
  const live = (k) => {
    const e = cache.get(k);
    if (!e) return null;
    if (e.exp <= clock.now) {
      cache.delete(k);
      return null;
    }
    return e.v;
  };
  const checkPut = (k, v, ttl) => {
    if (typeof v !== "string") throw new Error("cache value must be a string");
    if (k.length > 250) throw new Error("cache key too long");
    if (v.length > 100 * 1024) throw new Error("Argument too large: value");
    if (ttl > 21600) throw new Error("ttl over 6 h");
  };
  const scriptCache = {
    get: (k) => live(k),
    getAll: (keys) => Object.fromEntries(keys.map((k) => [k, live(k)]).filter(([, v]) => v !== null)),
    put: (k, v, ttl = 600) => {
      checkPut(k, v, ttl);
      cache.set(k, { v, exp: clock.now + ttl * 1000 });
    },
    putAll: (o, ttl = 600) => {
      for (const [k, v] of Object.entries(o)) scriptCache.put(k, v, ttl);
    },
    remove: (k) => cache.delete(k),
    removeAll: (keys) => keys.forEach((k) => cache.delete(k)),
  };
  class FakeDate extends Date {
    static now() {
      return clock.now;
    }
  }
  const ctx = {
    Date: FakeDate,
    JSON,
    Math,
    Number,
    String,
    Object,
    Array,
    CacheService: { getScriptCache: () => scriptCache },
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock: () => {} }) },
    PropertiesService: {
      getScriptProperties: () => {
        // Google: 9 KB a value, 500 KB in all (keys count), 50,000 reads and writes a day
        const total = () => [...props].reduce((n, [k, v]) => n + k.length + v.length, 0);
        const set = (k, v) => {
          if (typeof v !== "string") throw new Error("property value must be a string");
          if (v.length > 9 * 1024) throw new Error("property value over 9 KB");
          props.set(k, v);
        };
        const after = () => {
          if (total() > 500 * 1024) throw new Error("You have exceeded the property storage quota.");
        };
        return {
          getProperty: (k) => (propOps.reads++, props.has(k) ? props.get(k) : null),
          setProperty: (k, v) => (propOps.writes++, set(k, v), after()),
          setProperties: (o) => {
            propOps.writes++;
            for (const [k, v] of Object.entries(o)) set(k, v);
            after();
          },
          getProperties: () => (propOps.reads++, Object.fromEntries(props)),
          getKeys: () => (propOps.reads++, [...props.keys()]),
          deleteProperty: (k) => (propOps.writes++, props.delete(k)),
        };
      },
    },
    ContentService: {
      MimeType: { JSON: "json", TEXT: "text" },
      createTextOutput: (s) => ({ s, m: null, setMimeType(m) { this.m = m; return this; } }),
    },
    Utilities: {
      sleep: (ms) => {
        clock.now += ms;
        if (onSleep) {
          const f = onSleep;
          onSleep = null;
          f();
        }
      },
      // Google's bytes are Java bytes: -128..127; it wants padded base64 and gives it padded
      base64DecodeWebSafe: (s) => {
        if (s.length % 4 !== 0) throw new Error("Could not decode string.");
        return [...Buffer.from(s, "base64url")].map((x) => (x > 127 ? x - 256 : x));
      },
      base64EncodeWebSafe: (bytes) => {
        if (bytes.some((x) => x < -128 || x > 127)) throw new Error("not a byte");
        const b64 = Buffer.from(bytes.map((x) => x & 255)).toString("base64url");
        return b64 + "=".repeat((4 - (b64.length % 4)) % 4);
      },
    },
  };
  vm.createContext(ctx);
  vm.runInContext(code, ctx);
  const post = (body) => {
    const out = ctx.doPost({ postData: { contents: typeof body === "string" ? body : JSON.stringify(body) } });
    return out.m === "json" ? JSON.parse(out.s) : { decoy: out.s.includes("did not return anything"), mime: out.m };
  };
  return { ctx, clock, cache, props, propOps, post, setOnSleep: (f) => (onSleep = f) };
}

const keyed = source.replace("__MLMCHAT_USE_KEY__", USE).replace("__MLMCHAT_OWNER_KEY__", OWNER);
const hex = (n, i) => i.toString(16).padStart(n, "0");
const tagA = hex(32, 0xa1);
const tagB = hex(32, 0xb2);
const item = (i, len = 40) => [hex(32, i), "Q".repeat(len)];
let passed = 0;
const test = (name, f) => {
  f();
  passed++;
  console.log("ok   " + name);
};

test("the template itself answers only the decoy", () => {
  const { post } = makeEnv(source);
  assert.equal(post({ op: "ping", k: "__MLMCHAT_USE_KEY__" }).decoy, true);
});

test("wrong key, bad JSON and GET look like a forgotten script", () => {
  const { post, ctx } = makeEnv(keyed);
  assert.equal(post({ op: "ping", k: "c".repeat(32) }).decoy, true);
  assert.equal(post("{not json").decoy, true);
  assert.equal(post({ op: "ping" }).decoy, true);
  assert.ok(ctx.doGet({}).s.includes("did not return anything"));
});

test("ping says the version and whether the owner asked", () => {
  const { post } = makeEnv(keyed);
  assert.deepEqual(post({ op: "ping", k: USE, c: hex(16, 1) }), { ok: 1, ver: 3, owner: 0, paused: 0, pub: 0 });
  assert.equal(post({ op: "ping", o: OWNER, c: hex(16, 2) }).owner, 1);
});

test("put, take, acknowledge, and a second put of a taken item says done", () => {
  const { post } = makeEnv(keyed);
  assert.deepEqual(post({ op: "put", k: USE, c: hex(16, 1), t: tagA, i: [item(1), item(2)] }), { ok: 1, r: ["ok", "ok"] });
  // the same item again only refreshes it
  assert.deepEqual(post({ op: "put", k: USE, c: hex(16, 1), t: tagA, i: [item(1)] }).r, ["ok"]);
  const got = post({ op: "take", k: USE, c: hex(16, 3), t: [tagA, tagB] });
  assert.equal(got.ok, 1);
  assert.deepEqual(got.i.map((x) => x[0]), [hex(32, 1), hex(32, 2)]);
  assert.equal(got.i[0][1], tagA);
  // not acknowledged yet: comes again
  assert.equal(post({ op: "take", k: USE, c: hex(16, 3), t: [tagA] }).i.length, 2);
  const after = post({ op: "take", k: USE, c: hex(16, 3), t: [tagA], a: [hex(32, 1)] });
  assert.deepEqual(after.i.map((x) => x[0]), [hex(32, 2)]);
  assert.deepEqual(post({ op: "put", k: USE, c: hex(16, 1), t: tagA, i: [item(1)] }).r, ["done"]);
  assert.equal(post({ op: "take", k: USE, t: [tagA], a: [hex(32, 2)] }).i.length, 0);
});

test("chk tells a sender whether an item waits, was taken, or is gone", () => {
  const { post, clock } = makeEnv(keyed);
  post({ op: "put", k: USE, t: tagA, i: [item(1), item(2), item(3)] });
  assert.deepEqual(post({ op: "chk", k: USE, t: tagA, i: [hex(32, 1), hex(32, 2), hex(32, 9)] }), { ok: 1, r: ["wait", "wait", "gone"] });
  post({ op: "take", k: USE, t: [tagA], a: [hex(32, 2)] });
  assert.deepEqual(post({ op: "chk", k: USE, t: tagA, i: [hex(32, 1), hex(32, 2)] }).r, ["wait", "done"]);
  clock.now += 21600 * 1000 + 1;
  assert.deepEqual(post({ op: "chk", k: USE, t: tagA, i: [hex(32, 1), hex(32, 2)] }).r, ["gone", "gone"]);
  assert.deepEqual(post({ op: "chk", k: USE, t: tagA, i: [] }), { e: "bad" });
  assert.deepEqual(post({ op: "chk", k: USE, t: tagA, i: ["zz"] }), { e: "bad" });
});

test("an item lives 6 hours, and the queue forgets it", () => {
  const { post, clock, cache } = makeEnv(keyed);
  post({ op: "put", k: USE, t: tagA, i: [item(5)] });
  clock.now += 21600 * 1000 + 1;
  assert.equal(post({ op: "take", k: USE, t: [tagA] }).i.length, 0);
  assert.equal(cache.has("q:" + tagA), false);
});

test("too large, malformed and over the per-recipient limit are refused per item", () => {
  const { post } = makeEnv(keyed);
  const big = [hex(32, 9), "Q".repeat(98001)];
  const bad = [hex(32, 10), "not base64!"];
  assert.deepEqual(post({ op: "put", k: USE, t: tagA, i: [big, bad, item(11), ["xyz", "QQ"]] }).r, ["size", "bad", "ok", "bad"]);
  for (let n = 0; n < 7; n++) {
    const batch = [];
    for (let j = 0; j < 64; j++) batch.push(item(1000 + n * 64 + j, 8));
    post({ op: "put", k: USE, t: tagA, i: batch });
  }
  const r = post({ op: "put", k: USE, t: tagA, i: [item(5000, 8)] }).r;
  assert.deepEqual(r, ["full"]);
  assert.deepEqual(post({ op: "put", k: USE, t: tagA, i: [] }), { e: "bad" });
  assert.deepEqual(post({ op: "put", k: USE, t: "XYZ", i: [item(1)] }), { e: "bad" });
});

test("a take answer is capped and says there is more", () => {
  const { post } = makeEnv(keyed);
  const batch = [];
  for (let j = 0; j < 30; j++) batch.push(item(j + 1, 90000));
  post({ op: "put", k: USE, t: tagA, i: batch });
  const got = post({ op: "take", k: USE, t: [tagA], m: 1000000 });
  assert.equal(got.more, 1);
  assert.equal(got.i.length, 11);
  const all = post({ op: "take", k: USE, t: [tagA] });
  assert.equal(all.i.length, 30);
  assert.equal(all.more, 0);
});

test("pause: everyone but the owner is refused; ping still answers", () => {
  const { post } = makeEnv(keyed);
  assert.deepEqual(post({ op: "admin", k: USE, paused: true }), { e: "owner" });
  assert.equal(post({ op: "admin", o: OWNER, paused: true }).cfg.paused, true);
  assert.deepEqual(post({ op: "put", k: USE, t: tagA, i: [item(1)] }), { e: "paused" });
  assert.equal(post({ op: "ping", k: USE }).paused, 1);
  assert.deepEqual(post({ op: "put", o: OWNER, t: tagA, i: [item(1)] }).r, ["ok"]);
  post({ op: "admin", o: OWNER, paused: false });
  assert.deepEqual(post({ op: "put", k: USE, t: tagA, i: [item(2)] }).r, ["ok"]);
});

test("public: only the owner opens it, and ping tells anyone (version 2)", () => {
  const { post } = makeEnv(keyed);
  assert.equal(post({ op: "ping", k: USE }).pub, 0);
  assert.deepEqual(post({ op: "admin", k: USE, pub: true }), { e: "owner" });
  assert.equal(post({ op: "ping", k: USE }).pub, 0, "a contact with the key of use cannot open it");
  assert.equal(post({ op: "admin", o: OWNER, pub: true }).cfg.pub, true);
  assert.equal(post({ op: "ping", k: USE }).pub, 1);
  assert.equal(post({ op: "admin", o: OWNER, pub: false }).cfg.pub, false);
  assert.equal(post({ op: "ping", k: USE }).pub, 0);
});

test("a daily device limit lets known devices and the owner in", () => {
  const { post } = makeEnv(keyed);
  post({ op: "admin", o: OWNER, max_clients: 2 });
  assert.equal(post({ op: "take", k: USE, c: hex(16, 1), t: [tagA] }).ok, 1);
  assert.equal(post({ op: "take", k: USE, c: hex(16, 2), t: [tagA] }).ok, 1);
  assert.deepEqual(post({ op: "take", k: USE, c: hex(16, 3), t: [tagA] }), { e: "limit" });
  assert.equal(post({ op: "take", k: USE, c: hex(16, 1), t: [tagA] }).ok, 1);
  assert.equal(post({ op: "take", o: OWNER, c: hex(16, 9), t: [tagA] }).ok, 1);
});

test("stats count requests, devices helped (not the owner) and recipients", () => {
  const { post } = makeEnv(keyed);
  post({ op: "put", k: USE, c: hex(16, 1), t: tagA, i: [item(1)] });
  post({ op: "put", k: USE, c: hex(16, 2), t: tagB, i: [item(2), item(3)] });
  post({ op: "take", o: OWNER, c: hex(16, 7), t: [tagA, tagB] });
  assert.deepEqual(post({ op: "stats", k: USE }), { e: "owner" });
  const s = post({ op: "stats", o: OWNER });
  assert.equal(s.today.r, 3);
  assert.equal(s.today.p, 2);
  assert.equal(s.today.t, 1);
  assert.equal(s.today.ii, 3);
  assert.equal(s.today.io, 3);
  assert.equal(s.today.c, 2);
  assert.equal(s.today.g, 2);
  assert.equal(s.today.oc, 1);
  assert.equal(s.days.length, 1);
});

test("counts survive the cache losing them, from the saved daily totals", () => {
  const { post, cache, clock } = makeEnv(keyed);
  for (let i = 0; i < 20; i++) {
    // totals go to the properties at most every 5 minutes: the last ping is past that
    if (i === 19) clock.now += 6 * 60 * 1000;
    post({ op: "ping", k: USE, c: hex(16, i + 1) });
  }
  for (const k of [...cache.keys()]) if (k.startsWith("s:")) cache.delete(k);
  post({ op: "ping", k: USE, c: hex(16, 99) });
  const s = post({ op: "stats", o: OWNER });
  assert.equal(s.today.r, 21);
  assert.ok(s.today.c >= 20);
});

test("a new relay serves 40 devices a day, and the owner can lift that", () => {
  const { post } = makeEnv(keyed);
  for (let i = 0; i < 40; i++) assert.equal(post({ op: "ping", k: USE, c: hex(16, i + 1) }).ok, 1);
  assert.deepEqual(post({ op: "take", k: USE, c: hex(16, 41), t: [tagA] }), { e: "limit" });
  assert.equal(post({ op: "take", k: USE, c: hex(16, 1), t: [tagA] }).ok, 1);
  assert.equal(post({ op: "stats", o: OWNER }).cfg.max_clients, 40);
  post({ op: "admin", o: OWNER, max_clients: 0 });
  assert.equal(post({ op: "take", k: USE, c: hex(16, 41), t: [tagA] }).ok, 1);
});

test("a busy day stays within cache and property sizes", () => {
  const { post, props } = makeEnv(keyed);
  post({ op: "admin", o: OWNER, max_clients: 0 });
  for (let i = 0; i < 2500; i++) post({ op: "put", k: USE, c: hex(16, i + 1), t: hex(32, 0x10000 + i), i: [item(i + 1, 8)] });
  const s = post({ op: "stats", o: OWNER });
  assert.equal(s.today.c, 2000);
  assert.equal(s.today.g, 1000);
  for (let d = 0; d < 20; d++) {
    // fourteen days at most
    post({ op: "ping", k: USE });
  }
  assert.ok(props.get("days").length < 9 * 1024);
});

test("a waiting take returns as soon as an item arrives, or empty when the wait ends", () => {
  const env = makeEnv(keyed);
  const start = env.clock.now;
  assert.equal(env.post({ op: "take", k: USE, t: [tagA], w: 5000 }).i.length, 0);
  assert.ok(env.clock.now - start >= 4000);
  env.setOnSleep(() => env.post({ op: "put", k: USE, t: tagA, i: [item(42)] }));
  const got = env.post({ op: "take", k: USE, t: [tagA], w: 15000 });
  assert.deepEqual(got.i.map((x) => x[0]), [hex(32, 42)]);
  assert.equal(env.cache.has("w"), false);
});

test("a quota error from Google becomes e:quota", () => {
  const env = makeEnv(keyed);
  env.ctx.CacheService.getScriptCache().get = () => {
    throw new Error("Service invoked too many times for one day: cache.");
  };
  assert.deepEqual(env.post({ op: "take", k: USE, t: [tagA] }), { e: "quota" });
});

// ---- the official feeds (version 3, SPEC 18.2) ----

const FEED_C = hex(32, 0xc1);
const FEED_G = hex(32, 0x91);
// an item as a phone sends it: [id, base64, class]
const fitem = (i, len = 300, cls = 0) => [hex(32, 0x5000 + i), Buffer.alloc(Math.ceil((len * 3) / 4), i % 251).toString("base64url").slice(0, len), cls];

// census.rs: 2048 counters of 5 bits, high bit first
const pack5 = (regs) => {
  const out = new Uint8Array(1280);
  regs.forEach((r, i) => {
    const bit = i * 5;
    const v = r << (11 - (bit % 8));
    out[bit >> 3] |= (v >> 8) & 255;
    if ((bit >> 3) + 1 < 1280) out[(bit >> 3) + 1] |= v & 255;
  });
  return Buffer.from(out).toString("base64url");
};
const unpack5 = (b64) => {
  const b = Buffer.from(b64, "base64url");
  assert.equal(b.length, 1280);
  return Array.from({ length: 2048 }, (_, i) => {
    const bit = i * 5;
    const lo = (bit >> 3) + 1 < 1280 ? b[(bit >> 3) + 1] : 0;
    return (((b[bit >> 3] << 8) | lo) >> (11 - (bit % 8))) & 31;
  });
};
const periodOf = (ms) => Math.floor(ms / (14 * 24 * 3600 * 1000));

test("official items: kept, read from the start by a new phone, then only what is new", () => {
  const { post } = makeEnv(keyed);
  assert.deepEqual(post({ op: "fput", k: USE, f: FEED_C, i: [fitem(1), fitem(2, 300, 1)] }), { ok: 1, r: ["ok", "ok"] });
  assert.deepEqual(post({ op: "fput", k: USE, f: FEED_G, i: [fitem(3), fitem(1)] }).r, ["ok", "dup"], "an id kept already, in any feed");
  const first = post({ op: "fget", k: USE, f: [FEED_C, FEED_G], s: 0 });
  assert.deepEqual(first.i.map((x) => [x[0], x[1], x[2]]), [[fitem(1)[0], FEED_C, 1], [fitem(2)[0], FEED_C, 2], [fitem(3)[0], FEED_G, 3]]);
  assert.equal(first.i[0][3], fitem(1)[1]);
  assert.equal(first.s, 3);
  assert.equal(first.more, 0);
  // only the channel
  assert.deepEqual(post({ op: "fget", k: USE, f: [FEED_C], s: 0 }).i.map((x) => x[2]), [1, 2]);
  // nothing new
  const again = post({ op: "fget", k: USE, f: [FEED_C, FEED_G], s: first.s });
  assert.deepEqual(again.i, []);
  assert.equal(again.s, 3);
  post({ op: "fput", k: USE, f: FEED_G, i: [fitem(4)] });
  assert.deepEqual(post({ op: "fget", k: USE, f: [FEED_C, FEED_G], s: 3 }).i.map((x) => x[2]), [4]);
});

test("official items: bad ones refused one by one, wrong key and bad requests as before", () => {
  const { post } = makeEnv(keyed);
  const r = post({ op: "fput", k: USE, f: FEED_C, i: [fitem(1), ["xyz", "QQ"], [hex(32, 9), "not base64!"], [hex(32, 8), "Q".repeat(24001)]] });
  assert.deepEqual(r.r, ["ok", "bad", "bad", "bad"]);
  assert.deepEqual(post({ op: "fput", k: USE, f: "zz", i: [fitem(2)] }), { e: "bad" });
  assert.deepEqual(post({ op: "fput", k: USE, f: FEED_C, i: Array.from({ length: 17 }, (_, i) => fitem(i + 10)) }), { e: "bad" });
  assert.deepEqual(post({ op: "fget", k: USE, f: [] }), { e: "bad" });
  assert.equal(post({ op: "fget", k: "c".repeat(32), f: [FEED_C] }).decoy, true);
});

test("official items outlive the cache, and steady reads touch no property", () => {
  const env = makeEnv(keyed);
  const big = fitem(7, 20000); // in three properties
  env.post({ op: "fput", k: USE, f: FEED_C, i: [fitem(1), big] });
  env.cache.clear();
  env.clock.now += 7 * 3600 * 1000;
  const got = env.post({ op: "fget", k: USE, f: [FEED_C], s: 0 });
  assert.deepEqual(got.i.map((x) => x[3]), [fitem(1)[1], big[1]]);
  const reads = env.propOps.reads;
  for (let n = 0; n < 50; n++) env.post({ op: "fget", k: USE, f: [FEED_C], s: n % 2 ? 0 : got.s });
  assert.ok(env.propOps.reads - reads <= 2, `steady reads from the cache, not the properties (${env.propOps.reads - reads})`);
});

test("official items stay within the property store: old reactions go first, then old posts", () => {
  const env = makeEnv(keyed);
  for (let i = 0; i < 6; i++) env.post({ op: "fput", k: USE, f: FEED_C, i: [fitem(1000 + i, 200, 2)] });
  for (let i = 0; i < 40; i++) env.post({ op: "fput", k: USE, f: FEED_G, i: Array.from({ length: 4 }, (_, j) => fitem(i * 4 + j, 3000)) });
  for (let i = 0; i < 30; i++) env.post({ op: "fput", k: USE, f: FEED_C, i: [fitem(2000 + i, 500, 1)] });
  const total = [...env.props].reduce((n, [k, v]) => n + k.length + v.length, 0);
  assert.ok(total < 500 * 1024, `within 500 KB (${total})`);
  const got = env.post({ op: "fget", k: USE, f: [FEED_C, FEED_G], s: 0 });
  const ids = new Set(got.i.map((x) => x[0]));
  assert.ok(!ids.has(fitem(1000)[0]), "the oldest reactions went");
  assert.ok(!ids.has(fitem(0)[0]), "then the oldest posts");
  assert.ok(ids.has(fitem(159)[0]), "the newest post stays");
  const info = got.i.filter((x) => x[1] === FEED_C && parseInt(x[0], 16) >= 0x5000 + 2000);
  assert.equal(info.length, 24, "the newest 24 admin lists, names and photos of a feed");
  assert.ok(ids.has(fitem(2029)[0]));
  // a new phone reads it all in capped answers
  let s = 0;
  let seen = 0;
  for (let round = 0; round < 100; round++) {
    const page = env.post({ op: "fget", k: USE, f: [FEED_C, FEED_G], s, m: 20000 });
    seen += page.i.length;
    s = page.s;
    if (!page.more) break;
  }
  assert.equal(seen, got.i.length);
});

test("the member count sketches are merged, counter by counter, for this period and the last", () => {
  const env = makeEnv(keyed);
  const now = periodOf(env.clock.now);
  const a = Array(2048).fill(0);
  const b = Array(2048).fill(0);
  a[0] = 3; a[100] = 7; a[2047] = 31;
  b[0] = 5; b[101] = 2;
  assert.deepEqual(env.post({ op: "fput", k: USE, f: FEED_C, cs: [[now, pack5(a)]] }).r, []);
  env.post({ op: "fput", k: USE, f: FEED_C, cs: [[now, pack5(b)], [now - 1, pack5(b)], [now + 7, pack5(a)]] });
  const cs = env.post({ op: "fget", k: USE, f: [FEED_C], s: 0 }).cs;
  assert.deepEqual(cs.map((x) => x[0]).sort(), [now - 1, now].sort(), "a far-off period is not kept");
  const merged = unpack5(cs.find((x) => x[0] === now)[1]);
  assert.deepEqual([merged[0], merged[100], merged[101], merged[2047], merged[5]], [5, 7, 2, 31, 0]);
  // nothing new: no write
  const writes = env.propOps.writes;
  env.post({ op: "fput", k: USE, f: FEED_C, cs: [[now, pack5(b)]] });
  assert.equal(env.propOps.writes, writes, "a sketch that adds nothing writes nothing");
});

test("official reads and writes are not held to the daily device limit, but a pause stops them", () => {
  const { post } = makeEnv(keyed);
  for (let i = 0; i < 60; i++) assert.equal(post({ op: "fget", k: USE, c: hex(16, i + 1), f: [FEED_C], s: 0 }).ok, 1);
  assert.equal(post({ op: "fput", k: USE, c: hex(16, 61), f: FEED_C, i: [fitem(1)] }).ok, 1);
  assert.equal(post({ op: "stats", o: OWNER }).today.fr, 60);
  post({ op: "admin", o: OWNER, paused: true });
  assert.deepEqual(post({ op: "fget", k: USE, f: [FEED_C], s: 0 }), { e: "paused" });
  assert.equal(post({ op: "fget", o: OWNER, f: [FEED_C], s: 0 }).i.length, 1);
});

test("stress: 200 phones read the official copy all day, 20 post at once, within Google's quotas", () => {
  const env = makeEnv(keyed);
  // twenty phones post at once, one item each, in both feeds
  for (let i = 0; i < 20; i++) {
    assert.equal(env.post({ op: "fput", k: USE, c: hex(16, 1000 + i), f: i % 2 ? FEED_G : FEED_C, i: [fitem(i, 400)] }).r[0], "ok");
  }
  // two hundred phones, each reading every 20 minutes for a day (72 reads), from the start
  const cursors = new Array(200).fill(0);
  const props0 = env.propOps.reads + env.propOps.writes;
  let reads = 0;
  for (let round = 0; round < 72; round++) {
    for (let p = 0; p < 200; p++) {
      const got = env.post({ op: "fget", k: USE, c: hex(16, p + 1), f: [FEED_C, FEED_G], s: cursors[p] });
      assert.equal(got.ok, 1);
      if (round === 0) assert.equal(got.i.length, 20, "a new phone gets every item");
      cursors[p] = got.s;
      reads++;
    }
    env.clock.now += 20 * 60 * 1000;
    // the cache keeps a value 6 hours at most: what was read stays warm by being read
  }
  const propOps = env.propOps.reads + env.propOps.writes - props0;
  assert.ok(propOps < 2000, `${reads} reads used ${propOps} property operations (quota 50,000 a day)`);
  console.log(`      ${reads} reads in a day: ${propOps} property operations`);
});

console.log(`\n${passed} passed`);
