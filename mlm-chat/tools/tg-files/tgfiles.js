// Gereh T71 on this computer: the Telegram account of the file fetcher, and a test fetch.
//
//   node tgfiles.js probe                 a key with Telegram and one encrypted call; no account
//   node tgfiles.js login                 sign the fetcher's account in (its owner types everything)
//   node tgfiles.js fetch <channel>/<post> a post's file into ./got/
//   node tgfiles.js latest <channel>       the newest post with a file, from t.me/s/<channel>
//   node tgfiles.js copy                   the session onto the clipboard, for the Google script
//
// The session (keys that act as the account) is kept in %USERPROFILE%\.gereh-tgfiles\session.json,
// never in the repository and never printed. Telegram is reached over its web transport
// (https://<dc>.web.telegram.org/apiw1), the same way the Google script does it.
"use strict";
const crypto = require("crypto");
const zlib = require("zlib");
const fs = require("fs");
const os = require("os");
const path = require("path");
const cp = require("child_process");
const readline = require("readline");
const { TgClient, TgW, TgR } = require("./tgcore.js");

const DIR = path.join(os.homedir(), ".gereh-tgfiles");
const SESSION = path.join(DIR, "session.json");
const MAX = 2 * 1024 * 1024;

const env = {
  sha1: (b) => new Uint8Array(crypto.createHash("sha1").update(b).digest()),
  sha256: (b) => new Uint8Array(crypto.createHash("sha256").update(b).digest()),
  aes: (key) => {
    const e = crypto.createCipheriv("aes-256-ecb", key, null);
    const d = crypto.createDecipheriv("aes-256-ecb", key, null);
    e.setAutoPadding(false);
    d.setAutoPadding(false);
    return { enc: (b) => new Uint8Array(e.update(b)), dec: (b) => new Uint8Array(d.update(b)) };
  },
  random: (n) => new Uint8Array(crypto.randomBytes(n)),
  now: () => Date.now(),
  gunzip: (b) => new Uint8Array(zlib.gunzipSync(b)),
  pbkdf2: (pw, salt, it) => new Uint8Array(crypto.pbkdf2Sync(pw, salt, it, 64, "sha512")),
  post: (url, body) =>
    new Uint8Array(cp.execFileSync("curl", ["-s", "--max-time", "40", "-X", "POST", "-H", "Content-Type: application/octet-stream", "--data-binary", "@-", url], {
      input: Buffer.from(body),
      maxBuffer: 64 << 20,
    })),
};

function ask(question, hidden) {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    if (hidden) {
      rl._writeToOutput = (s) => {
        if (s.startsWith(question)) rl.output.write(s);
        else if (s !== "\r\n" && s !== "\n") rl.output.write("*");
        else rl.output.write(s);
      };
    }
    rl.question(question, (a) => {
      rl.close();
      if (hidden) process.stdout.write("\n");
      resolve(a.trim());
    });
  });
}

function load() {
  if (!fs.existsSync(SESSION)) throw new Error("no session: run «node tgfiles.js login» first");
  return JSON.parse(fs.readFileSync(SESSION, "utf8"));
}

function save(s) {
  fs.mkdirSync(DIR, { recursive: true });
  fs.writeFileSync(SESSION, JSON.stringify(s, null, 1), { mode: 0o600 });
}

async function probe() {
  const c = new TgClient(env, { apiId: 0, apiHash: "", home: 2 });
  const t0 = Date.now();
  c.createKey(2);
  console.log("key with dc2 in", Date.now() - t0, "ms");
  // help.getNearestDc: answered without an account
  const r = new TgR(c.call(2, new TgW().int(0x1fb33026).done()));
  const ctor = r.int();
  if (ctor !== 0x8e1a1775) throw new Error("unexpected answer 0x" + ctor.toString(16));
  console.log("encrypted call ok: nearest dc", (r.string(), r.int(), r.int()));
}

async function login() {
  console.log("The fetcher's Telegram account. Type each answer yourself; nothing is shown or stored but the session.");
  const apiId = Number(await ask("api_id (my.telegram.org): "));
  const apiHash = await ask("api_hash: ", true);
  const phone = (await ask("phone (+98...): ")).replace(/[^+0-9]/g, "");
  const c = new TgClient(env, { apiId, apiHash, home: 2 });
  c.createKey(2);
  const hash = c.sendCode(phone);
  const code = await ask("the code Telegram sent: ", true);
  if (!c.signIn(phone, hash, code)) {
    const pw = await ask("two-step password: ", true);
    c.checkPassword(pw);
  }
  console.log("signed in on dc" + c.opts.home);
  for (let dc = 1; dc <= 5; dc++) {
    if (dc === c.opts.home) continue;
    try {
      c.authorizeOn(dc);
      console.log("dc" + dc + " ready");
    } catch (e) {
      console.log("dc" + dc + ": " + e.message + " (later, when a file needs it)");
    }
  }
  save({ apiId, apiHash, home: c.opts.home, keys: c.keys });
  console.log("session kept in " + SESSION + " (it is the account: never share it)");
}

function client() {
  const s = load();
  const c = new TgClient(env, { apiId: s.apiId, apiHash: s.apiHash, home: s.home, keys: s.keys });
  return { c, s };
}

async function fetchPost(ref) {
  const m = /^@?([A-Za-z][A-Za-z0-9_]{3,31})\/(\d+)$/.exec(ref || "");
  if (!m) throw new Error("usage: fetch <channel>/<post>");
  const { c, s } = client();
  const t0 = Date.now();
  const ch = c.resolve(m[1]);
  const d = c.document(ch, Number(m[2]));
  if (!d) {
    console.log("post " + ref + " has no file");
    return;
  }
  const bytes = c.download(d, MAX);
  save({ ...s, home: c.opts.home, keys: c.keys });
  const out = path.join(__dirname, "got");
  fs.mkdirSync(out, { recursive: true });
  const file = path.join(out, m[1] + "-" + m[2] + ".bin");
  fs.writeFileSync(file, bytes);
  const sha = crypto.createHash("sha256").update(bytes).digest("hex").slice(0, 16);
  console.log(`${ref}: ${d.mime}, ${bytes.length} bytes from dc${d.dc}, sha256 ${sha}…, ${Date.now() - t0} ms -> ${file}`);
}

// The session onto the clipboard, for the Google script's TG_SESSION property; never shown.
async function copy() {
  const s = load();
  cp.execFileSync("clip", { input: JSON.stringify(s) });
  console.log("the session is on the clipboard: paste it as the value of TG_SESSION, then copy something else over it");
}

async function latest(name) {
  const html = cp.execFileSync("curl", ["-s", "--max-time", "30", "-H", "Accept-Language: en", "https://t.me/s/" + name]).toString("utf8");
  const posts = [...html.matchAll(/data-post="([A-Za-z0-9_]+\/\d+)"[\s\S]*?(?=data-post="|$)/g)].filter((x) => x[0].includes("tgme_widget_message_document"));
  if (!posts.length) throw new Error("no post with a file on the page");
  const last = posts[posts.length - 1][1];
  console.log("newest post with a file: " + last);
  await fetchPost(last);
}

(async () => {
  const [cmd, arg] = process.argv.slice(2);
  try {
    if (cmd === "probe") await probe();
    else if (cmd === "login") await login();
    else if (cmd === "fetch") await fetchPost(arg);
    else if (cmd === "latest") await latest(arg);
    else if (cmd === "copy") await copy();
    else console.log("usage: node tgfiles.js probe | login | fetch <channel>/<post> | latest <channel> | copy");
  } catch (e) {
    console.error("error: " + (e.code ? e.code + " " : "") + e.message);
    process.exit(1);
  }
})();
