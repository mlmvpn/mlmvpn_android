// Asks a deployed file fetcher (TgFiles.gs) from this computer: ping, then one file.
//   node tools/tg-files/gs-live-test.js <deployment id> [<channel>/<post>]     (from mlm-chat)
"use strict";
const cp = require("child_process");
const crypto = require("crypto");

const [dep, ref = "mitivpn/18932"] = process.argv.slice(2);
if (!/^[A-Za-z0-9_-]{20,110}$/.test(dep || "")) {
  console.error("usage: gs-live-test.js <deployment id> [<channel>/<post>]");
  process.exit(1);
}
const url = "https://script.google.com/macros/s/" + dep + "/exec";
const ask = (body) => {
  const t0 = Date.now();
  // Apps Script answers a POST with a redirect to its output, read with a GET
  const out = cp.execFileSync("curl", ["-s", "-L", "--max-time", "90", "-H", "Content-Type: application/json", "--data-binary", "@-", url], { input: JSON.stringify(body) }).toString("utf8");
  try {
    return { r: JSON.parse(out), ms: Date.now() - t0 };
  } catch {
    return { r: out.slice(0, 200), ms: Date.now() - t0 };
  }
};
const K = "redacted-files-key";
const u = crypto.randomBytes(16).toString("hex");
const p = ask({ k: K, op: "ping" });
console.log("ping:", JSON.stringify(p.r), p.ms + " ms");
const [c, post] = ref.split("/");
for (const round of ["first", "again"]) {
  const f = ask({ k: K, op: "f", c, p: Number(post), u });
  if (f.r && f.r.ok) {
    const b = Buffer.from(f.r.b, "base64");
    console.log(`${round}: ${f.r.m}, ${b.length} bytes, sha256 ${crypto.createHash("sha256").update(b).digest("hex").slice(0, 16)}…, ${f.ms} ms`);
  } else {
    console.log(round + ":", JSON.stringify(f.r), f.ms + " ms");
  }
}
