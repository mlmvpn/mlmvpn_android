// Runs relay/tgfiles/TgFiles.gs as Google would, with Utilities, UrlFetchApp, CacheService,
// PropertiesService and ContentService imitated here; UrlFetchApp really goes out (curl), so this
// fetches a real file from Telegram with the saved session (node tgfiles.js login first).
//   node tools/tg-files/gas-test.js <channel>/<post>     (from mlm-chat)
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const vm = require("vm");
const zlib = require("zlib");
const crypto = require("crypto");
const cp = require("child_process");
const assert = require("assert/strict");

const ref = process.argv[2] || "mitivpn/18932";
const [chan, post] = ref.split("/");
const gs = fs.readFileSync(path.join(__dirname, "..", "..", "relay", "tgfiles", "TgFiles.gs"), "utf8");
const session = fs.readFileSync(path.join(os.homedir(), ".gereh-tgfiles", "session.json"), "utf8");

const unsigned = (a) => Buffer.from(a.map((x) => x & 255));
const signed = (b) => Array.from(b, (x) => (x > 127 ? x - 256 : x));
let fetches = 0;
const cache = new Map();
const props = new Map([["TG_SESSION", JSON.stringify(JSON.parse(session))]]);
const ctx = {
  Utilities: {
    DigestAlgorithm: { SHA_1: "sha1", SHA_256: "sha256" },
    computeDigest: (alg, data) => signed(crypto.createHash(alg).update(unsigned(data)).digest()),
    base64Encode: (data) => unsigned(data).toString("base64"),
    getUuid: () => crypto.randomUUID(),
    newBlob: (data) => ({ data }),
    ungzip: (blob) => ({ getBytes: () => signed(zlib.gunzipSync(unsigned(blob.data))) }),
  },
  UrlFetchApp: {
    fetch: (url, o) => {
      fetches++;
      const out = cp.execFileSync("curl", ["-s", "--max-time", "40", "-X", "POST", "-H", "Content-Type: " + o.contentType, "--data-binary", "@-", url], { input: unsigned(o.payload), maxBuffer: 64 << 20 });
      return { getContent: () => signed(out), getResponseCode: () => 200 };
    },
  },
  CacheService: {
    getScriptCache: () => ({
      get: (k) => (cache.has(k) ? cache.get(k) : null),
      put: (k, v) => { assert.ok(String(v).length <= 100000, "a cache value over 100 KB"); cache.set(k, String(v)); },
      getAll: (ks) => Object.fromEntries(ks.filter((k) => cache.has(k)).map((k) => [k, cache.get(k)])),
      putAll: (o) => { for (const k in o) { assert.ok(o[k].length <= 100000); cache.set(k, o[k]); } },
    }),
  },
  PropertiesService: {
    getScriptProperties: () => ({ getProperty: (k) => (props.has(k) ? props.get(k) : null), setProperty: (k, v) => props.set(k, String(v)) }),
  },
  ContentService: {
    MimeType: { JSON: "json", TEXT: "text" },
    createTextOutput: (s) => ({ text: s, setMimeType() { return this; } }),
  },
};
vm.createContext(ctx);
vm.runInContext(gs, ctx);
const call = (body) => {
  const t = ctx.doPost({ postData: { contents: JSON.stringify(body) } }).text;
  try { return JSON.parse(t); } catch { return t; }
};
const K = "redacted-files-key";
const u = crypto.randomBytes(16).toString("hex");

assert.match(call({ k: "wrong", op: "ping" }), /did not return anything/, "a wrong key looks like a forgotten script");
assert.deepEqual(call({ k: K, op: "ping" }), { ok: 1, ver: 1, kind: "tgfiles", ready: true });
assert.deepEqual(call({ k: K, op: "f", c: "x", p: 1, u }), { e: "bad" });
let t0 = Date.now();
const r = call({ k: K, op: "f", c: chan, p: Number(post), u });
assert.equal(r.ok, 1, "the file came: " + JSON.stringify(r).slice(0, 200));
const bytes = Buffer.from(r.b, "base64");
assert.equal(bytes.length, r.s);
console.log(`${ref}: ${r.m}, ${r.s} bytes, ${fetches} fetches to Telegram, ${Date.now() - t0} ms, sha256 ${crypto.createHash("sha256").update(bytes).digest("hex").slice(0, 16)}…`);
const before = fetches;
t0 = Date.now();
const again = call({ k: K, op: "f", c: chan, p: Number(post), u });
assert.equal(again.b, r.b);
assert.equal(fetches, before, "the second time from the cache, Telegram not asked");
console.log(`again: from the script's cache, no fetch, ${Date.now() - t0} ms`);
for (let i = 2; i < 20; i++) assert.equal(call({ k: K, op: "f", c: chan, p: Number(post), u }).ok, 1);
assert.deepEqual(call({ k: K, op: "f", c: chan, p: Number(post), u }), { e: "limit" }, "20 a day for one phone");
assert.equal(call({ k: K, op: "f", c: chan, p: Number(post), u: crypto.randomBytes(16).toString("hex") }).ok, 1, "another phone still gets it");
console.log("limits ok: 20 a day for one phone, others unaffected; " + fetches + " fetches in all");
