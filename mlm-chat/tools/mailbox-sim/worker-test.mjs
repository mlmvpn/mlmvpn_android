// Runs relay/MailboxWorker.js (the combined relay's mailbox, SPEC 19.13) as Cloudflare would: the
// Worker in front, one Durable Object behind it with its storage emulated (get, put, delete, list,
// alarms), and a clock the test moves. Checks it answers exactly as relay/Mailbox.gs does, and that
// relay/Door.gs hands requests on unchanged.
//
//   node tools/mailbox-sim/worker-test.mjs            (from mlm-chat)

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";
import assert from "node:assert/strict";

const here = dirname(fileURLToPath(import.meta.url));
const relayDir = join(here, "..", "..", "relay");
const USE = "a".repeat(32);
const OWNER = "b".repeat(32);

const clock = { now: 1_800_000_000_000 };
const RealDate = Date;
globalThis.Date = class extends RealDate {
  constructor(...a) {
    super(...(a.length ? a : [clock.now]));
  }
  static now() {
    return clock.now;
  }
};
const timers = [];
globalThis.setTimeout = (fn, ms) => {
  const t = { at: clock.now + ms, fn, done: false };
  timers.push(t);
  return t;
};
globalThis.clearTimeout = (t) => {
  if (t) t.done = true;
};
// time passes: due timers fire
async function advance(ms) {
  clock.now += ms;
  for (const t of timers) {
    if (!t.done && t.at <= clock.now) {
      t.done = true;
      t.fn();
    }
  }
  await new Promise((r) => setImmediate(r));
}

function storage() {
  const m = new Map();
  const ops = { reads: 0, writes: 0 };
  let alarm = null;
  const clone = (v) => (v === undefined ? undefined : structuredClone(v));
  return {
    m,
    ops,
    async get(k) {
      if (Array.isArray(k)) {
        assert.ok(k.length <= 128, "a get of at most 128 keys");
        ops.reads += k.length;
        const out = new Map();
        k.forEach((x) => m.has(x) && out.set(x, clone(m.get(x))));
        return out;
      }
      ops.reads++;
      return clone(m.get(k));
    },
    async put(k, v) {
      if (typeof k === "string") {
        ops.writes++;
        m.set(k, clone(v));
        return;
      }
      const entries = Object.entries(k);
      assert.ok(entries.length <= 128, "a put of at most 128 keys");
      entries.forEach(([x, y]) => m.set(x, clone(y)));
      ops.writes += entries.length;
    },
    async delete(k) {
      const keys = Array.isArray(k) ? k : [k];
      assert.ok(keys.length <= 128, "a delete of at most 128 keys");
      ops.writes += keys.length;
      keys.forEach((x) => m.delete(x));
    },
    async list({ prefix = "", start } = {}) {
      const keys = [...m.keys()].filter((x) => x.startsWith(prefix) && (start === undefined || x >= start)).sort();
      ops.reads += keys.length;
      return new Map(keys.map((x) => [x, clone(m.get(x))]));
    },
    async getAlarm() {
      return alarm;
    },
    async setAlarm(t) {
      alarm = t;
    },
  };
}

async function load() {
  const src = readFileSync(join(relayDir, "MailboxWorker.js"), "utf8").replace("__MLMCHAT_USE_KEY__", USE).replace("__MLMCHAT_OWNER_KEY__", OWNER);
  const mod = await import("data:text/javascript," + encodeURIComponent(src));
  const st = storage();
  const box = new mod.Mailbox({ storage: st }, {});
  let doRequests = 0;
  const env = {
    MBOX: {
      idFromName: (n) => n,
      get: () => ({
        fetch: (url, init) => {
          doRequests++;
          return box.fetch(new Request(url, init));
        },
      }),
    },
  };
  const call = async (body, method = "POST") => {
    const r = await mod.default.fetch(new Request("https://w.example.workers.dev/", method === "POST" ? { method, body: typeof body === "string" ? body : JSON.stringify(body) } : { method }), env);
    const text = await r.text();
    try {
      return JSON.parse(text);
    } catch (e) {
      return { decoy: text };
    }
  };
  return { call, st, box, doRequests: () => doRequests };
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const T1 = "1".repeat(32);
const T2 = "2".repeat(32);
const id = (n) => n.toString(16).padStart(32, "0");
const k = (o) => Object.assign({ k: USE, c: "0123456789abcdef" }, o);

test("strangers get the decoy and never reach the Durable Object", async () => {
  const w = await load();
  assert.ok((await w.call({ op: "ping", k: "c".repeat(32) })).decoy);
  assert.ok((await w.call("not json")).decoy);
  assert.ok((await w.call(null, "GET")).decoy);
  assert.equal(w.doRequests(), 0);
  const p = await w.call(k({ op: "ping" }));
  assert.equal(p.ok, 1);
  assert.equal(p.ver, 3);
  assert.equal(p.kind, "cf");
  assert.equal(p.owner, 0);
  assert.equal((await w.call({ op: "ping", o: OWNER })).owner, 1);
});

test("put, take, acknowledge, and the sender sees it done", async () => {
  const w = await load();
  const put = await w.call(k({ op: "put", t: T1, i: [[id(1), "aGVsbG8"], [id(2), "d29ybGQ"], [id(3), "*bad*"]] }));
  assert.deepEqual(put.r, ["ok", "ok", "bad"]);
  const take = await w.call(k({ op: "take", t: [T1, T2] }));
  assert.deepEqual(take.i, [[id(1), T1, "aGVsbG8"], [id(2), T1, "d29ybGQ"]]);
  assert.deepEqual((await w.call(k({ op: "chk", t: T1, i: [id(1), id(9)] }))).r, ["wait", "gone"]);
  const again = await w.call(k({ op: "take", t: [T1], a: [id(1)] }));
  assert.deepEqual(again.i, [[id(2), T1, "d29ybGQ"]]);
  assert.deepEqual((await w.call(k({ op: "chk", t: T1, i: [id(1), id(2)] }))).r, ["done", "wait"]);
  // putting a taken item again is refused as done: the sender stops
  assert.deepEqual((await w.call(k({ op: "put", t: T1, i: [[id(1), "aGVsbG8"]] }))).r, ["done"]);
  assert.deepEqual((await w.call(k({ op: "take", t: [T1], a: [id(2)] }))).i, []);
});

test("a waiting take wakes the moment its item comes", async () => {
  const w = await load();
  const pending = w.call(k({ op: "take", t: [T1], w: 15000 }));
  await new Promise((r) => setImmediate(r));
  await advance(1000);
  await w.call(k({ op: "put", t: T2, i: [[id(5), "eA"]] })); // another tag: still waiting
  await w.call(k({ op: "put", t: T1, i: [[id(6), "eQ"]] }));
  const got = await pending;
  assert.deepEqual(got.i, [[id(6), T1, "eQ"]]);
});

test("a waiting take ends empty after its wait", async () => {
  const w = await load();
  const pending = w.call(k({ op: "take", t: [T1], w: 5000 }));
  await new Promise((r) => setImmediate(r));
  await advance(6000);
  assert.deepEqual((await pending).i, []);
});

test("items live three days, then are gone (and the sender puts them again)", async () => {
  const w = await load();
  await w.call(k({ op: "put", t: T1, i: [[id(7), "eg"]] }));
  await advance(2 * 86400000);
  assert.deepEqual((await w.call(k({ op: "chk", t: T1, i: [id(7)] }))).r, ["wait"]);
  await advance(86400000 + 1000);
  assert.deepEqual((await w.call(k({ op: "chk", t: T1, i: [id(7)] }))).r, ["gone"]);
  assert.deepEqual((await w.call(k({ op: "take", t: [T1] }))).i, []);
  await w.box.alarm();
  assert.equal([...w.st.m.keys()].filter((x) => x.startsWith("i:") || x.startsWith("q:")).length, 0, "the alarm cleans up");
});

test("a full queue, an oversized item", async () => {
  const w = await load();
  const many = [];
  for (let n = 0; n < 64; n++) many.push([id(1000 + n), "eA"]);
  for (let b = 0; b < 6; b++) await w.call(k({ op: "put", t: T1, i: many.map(([x, v], n) => [id(2000 + b * 64 + n), v]) }));
  const r = await w.call(k({ op: "put", t: T1, i: [[id(9999), "eA"]] }));
  assert.deepEqual(r.r, ["ok"]);
  const big = await w.call(k({ op: "put", t: T2, i: [[id(1), "e".repeat(98001)]] }));
  assert.deepEqual(big.r, ["size"]);
  const full = await w.call(k({ op: "put", t: T1, i: Array.from({ length: 30 }, (_, n) => [id(50000 + n), "eA"]) }));
  assert.ok(full.r.includes("full"), "past 400 items for one recipient");
});

test("a long queue comes in pages, read 128 at a time, nothing lost", async () => {
  const w = await load();
  const v = "QUFB".repeat(250); // 1000 characters
  for (let b = 0; b < 5; b++) {
    await w.call(k({ op: "put", t: T1, i: Array.from({ length: 60 }, (_, n) => [id(7000 + b * 60 + n), v]) }));
  }
  const got = new Set();
  let acks = [];
  for (let round = 0; round < 10; round++) {
    const r = await w.call(k({ op: "take", t: [T1], a: acks, m: 100000 }));
    r.i.forEach((x) => got.add(x[0]));
    acks = r.i.map((x) => x[0]);
    if (!r.more && r.i.length === 0) break;
    assert.ok(r.i.length <= 101, "a page stays near its size");
  }
  assert.equal(got.size, 300);
});

test("the owner pauses, limits devices, opens it to everyone, reads statistics", async () => {
  const w = await load();
  assert.equal((await w.call(k({ op: "stats" }))).e, "owner");
  await w.call({ op: "admin", o: OWNER, paused: true });
  assert.equal((await w.call(k({ op: "take", t: [T1] }))).e, "paused");
  assert.equal((await w.call(k({ op: "ping" }))).paused, 1);
  assert.equal((await w.call({ op: "take", o: OWNER, t: [T1] })).ok, 1, "never the owner");
  await w.call({ op: "admin", o: OWNER, paused: false, max_clients: 3, pub: true });
  assert.equal((await w.call(k({ op: "ping" }))).pub, 1);
  for (const c of ["1111111111111111", "2222222222222222"]) assert.equal((await w.call({ op: "take", k: USE, c, t: [T1] })).ok, 1);
  assert.equal((await w.call({ op: "take", k: USE, c: "3333333333333333", t: [T1] })).e, "limit");
  const s = await w.call({ op: "stats", o: OWNER });
  assert.equal(s.ok, 1);
  assert.equal(s.kind, "cf");
  assert.equal(s.today.c, 3, "the first device and two more");
  assert.ok(s.days.length >= 1);
});

test("the official feeds' copy: put, read in pages, no repeats, sketches merged", async () => {
  const w = await load();
  const F = "f".repeat(32);
  const items = Array.from({ length: 5 }, (_, n) => [id(100 + n), "QUFB".repeat(100), n === 4 ? 2 : 0]);
  assert.deepEqual((await w.call(k({ op: "fput", f: F, i: items }))).r, ["ok", "ok", "ok", "ok", "ok"]);
  assert.deepEqual((await w.call(k({ op: "fput", f: F, i: [items[0]] }))).r, ["dup"]);
  const page = await w.call(k({ op: "fget", f: [F], s: 0, m: 20000 }));
  assert.equal(page.i.length, 5);
  assert.deepEqual(page.i[0].slice(0, 3), [id(100), F, 1]);
  const next = await w.call(k({ op: "fget", f: [F], s: page.s }));
  assert.equal(next.i.length, 0);
  // a sketch with one counter at 7
  const regs = new Uint8Array(1280);
  regs[0] = 7 << 3;
  const b64 = Buffer.from(regs).toString("base64url");
  const period = Math.floor(clock.now / 1209600000);
  await w.call(k({ op: "fput", f: F, cs: [[period, b64]] }));
  const cs = (await w.call(k({ op: "fget", f: [F], s: 0 }))).cs;
  assert.equal(cs.length, 1);
  assert.equal(Buffer.from(cs[0][1], "base64url")[0] >> 3, 7);
});

test("storage writes stay modest: two hundred phones' traffic in a day", async () => {
  const w = await load();
  const before = w.st.ops.writes;
  for (let n = 0; n < 200; n++) {
    await w.call(k({ op: "put", t: T1, i: [[id(n + 1), "eA"]] }));
    await w.call(k({ op: "take", t: [T1], a: [id(n + 1)] }));
    await advance(60000);
  }
  const used = w.st.ops.writes - before;
  // the free plan writes 100,000 rows a day
  assert.ok(used < 2000, `writes ${used}`);
});

test("the Google door hands the request on and the answer back as they are", () => {
  const src = readFileSync(join(relayDir, "Door.gs"), "utf8");
  assert.ok(!src.includes("*"), "no asterisk anywhere (a phone's editor breaks them)");
  const seen = [];
  const ctx = {
    UrlFetchApp: {
      fetch: (url, o) => {
        seen.push([url, o.payload, o.method]);
        if (o.payload === "quota") throw new Error("Service invoked too many times for one day: urlfetch.");
        return { getResponseCode: () => (o.payload === "down" ? 502 : 200), getContentText: () => (o.payload === "stranger" ? "<!DOCTYPE html>" : "{\"ok\":1}") };
      },
    },
    ContentService: { createTextOutput: (t) => ({ t, setMimeType: function () { return this; } }), MimeType: { JSON: "json", TEXT: "text" } },
  };
  vm.createContext(ctx);
  vm.runInContext(src.replace("__MLMCHAT_WORKER_URL__", "https://w.example.workers.dev/"), ctx);
  const post = (b) => ctx.doPost({ postData: { contents: b } }).t;
  assert.equal(post("{\"op\":\"ping\"}"), "{\"ok\":1}");
  assert.deepEqual(seen[0], ["https://w.example.workers.dev/", "{\"op\":\"ping\"}", "post"]);
  assert.ok(post("stranger").includes("did not return anything"));
  assert.equal(post("down"), "{\"e\":\"remote\"}");
  assert.equal(post("quota"), "{\"e\":\"quota\"}");
  assert.ok(ctx.doGet({}).t.includes("did not return anything"));
  // the template itself, never filled in, forwards nothing
  const blank = { ...ctx };
  vm.createContext(blank);
  vm.runInContext(src, blank);
  assert.ok(blank.doPost({ postData: { contents: "{}" } }).t.includes("did not return anything"));
});

let failed = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log("ok   " + name);
  } catch (e) {
    failed++;
    console.log("FAIL " + name + "\n     " + (e && e.stack ? e.stack.split("\n").filter((l) => !l.startsWith("    at") || l.includes("worker-test")).slice(0, 6).join("\n     ") : e));
  }
}
console.log(`${tests.length - failed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
