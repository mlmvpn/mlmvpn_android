// Runs the Telegram reader script (chat/TMessagesProj/src/main/assets/gereh_tg.gs, T68) as Apps
// Script would, with UrlFetchApp, Utilities and ContentService emulated, on made-up channel pages.
// No network, no Google account.
//
//   node tools/tg-probe/reader-test.mjs            (from mlm-chat)
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { gzipSync, gunzipSync } from "node:zlib";
import vm from "node:vm";
import assert from "node:assert/strict";

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(here, "..", "..", "..", "chat", "TMessagesProj", "src", "main", "assets", "gereh_tg.gs"), "utf8");
const KEY = "c".repeat(32);

// a channel of posts 1..45: each page is the 20 posts before `before` (or after `after`)
function page(name, ids) {
  return "<html><body>" + ids.map((i) => `<div class="tgme_widget_message" data-post="${name}/${i}">post ${i}</div>`).join("") + "</body></html>";
}
const POSTS = Array.from({ length: 45 }, (_, i) => i + 1);

// a small JPEG that says whose it is
function jpeg(name) {
  return Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.from(name, "utf8")]);
}
// t.me/NAME: Telegram's preview page; extra is the line under the title (empty: no such name)
function preview(title, extra) {
  return `<html><head><meta property="og:title" content="${title}"><meta property="og:image" content="https://cdn4.telesco.pe/file/AbCdEfPhoto1234.jpg"></head><body>` + (extra ? `<div class="tgme_page_extra">
  ${extra}
</div>` : "") + "</body></html>";
}
// tg.me/search: rows as the site writes them, plus a link that is not a row
function row(name, title, metric) {
  return `<a href="/${name}" class="rl-row"><span class="rl-ava"><img src="/i/${name}.jpg" alt=""></span><span class="rl-main"><span class="rl-name"><span class="rl-name-txt" dir="auto">${title}</span><span class="rl-verified"></span></span><span class="rl-sub">@<!-- -->${name}</span></span><span class="rl-metric">${metric}</span></a>`;
}
const SEARCH = `<html><body><a href="/help" class="nav">Help</a>` + row("NewsFa", "خبر فوری", "316K") + row("configs_free", "کانفیگ <b>رایگان</b> &amp; سریع", "12.5K") + row("vpn_iran", "VPN Iran", "980") + Array.from({ length: 30 }, (_, i) => row("more_channel_" + i, "More " + i, "1")).join("") + "</body></html>";

function makeEnv(code) {
  const fetched = [];
  const answer = (url, opts) => {
    fetched.push(url);
    const u = new URL(url);
    if (u.hostname === "tg.me" && u.pathname.startsWith("/api/avatar/")) {
      const name = u.pathname.slice("/api/avatar/".length);
      if (name.startsWith("more_channel_1") || name === "noavatar") return resp(404, "", {});
      return resp(200, jpeg(name), { "Content-Type": "image/jpeg" });
    }
    // Telegram's own small photo: none for some (a 404 GIF, as t.me answers), an SVG for one
    if (u.hostname === "t.me" && u.pathname.startsWith("/i/userpic/160/")) {
      const name = u.pathname.slice("/i/userpic/160/".length).replace(/\.jpg$/, "");
      if (["NewsFa", "configs_free", "noavatar"].includes(name) || name.startsWith("more_channel_1")) return resp(404, "GIF89a", { "Content-Type": "image/gif" });
      if (name === "more_channel_2") return resp(200, "<svg/>", { "Content-Type": "image/svg+xml" });
      return resp(200, jpeg("userpic:" + name), { "Content-Type": "image/jpeg" });
    }
    if (u.hostname === "tg.me") return resp(200, u.searchParams.get("q").includes("someperson") ? "<html><body>Nothing found</body></html>" : SEARCH, { "Content-Type": "text/html" });
    if (u.hostname === "t.me" && !u.pathname.startsWith("/s/")) {
      const name = u.pathname.slice(1);
      if (name === "NewsFa") return resp(200, preview("خبر فوری &amp; تحلیل", "316 248 subscribers"), {});
      if (name === "GroupFa") return resp(200, preview("گروه پرسش", "12 345 members, 1 234 online"), {});
      if (name === "someperson") return resp(200, preview("Some Person", "@someperson"), {});
      return resp(200, preview("Telegram: Contact @" + name, ""), {});
    }
    if (u.hostname === "t.me") {
      const name = u.pathname.split("/")[2];
      if (name === "missing") return resp(302, "", { Location: "https://t.me/missing" });
      const before = Number(u.searchParams.get("before") || 0);
      const after = Number(u.searchParams.get("after") || 0);
      let ids = before ? POSTS.filter((i) => i < before).slice(-20) : after ? POSTS.filter((i) => i > after).slice(0, 20) : POSTS.slice(-20);
      if (after && !ids.length) ids = POSTS.slice(-20); // Telegram gives the newest page again
      return resp(200, page(name, ids), { "Content-Type": "text/html; charset=utf-8" });
    }
    if (!opts.headers || !opts.headers.Range) return resp(200, jpeg("page-photo"), { "Content-Type": "image/jpeg" });
    const m = /bytes=(\d+)-(\d+)/.exec(opts.headers.Range);
    const total = 5000;
    const from = Number(m[1]);
    const to = Math.min(Number(m[2]), total - 1);
    return resp(206, Buffer.alloc(to - from + 1, 7), { "Content-Type": "video/mp4", "Content-Range": `bytes ${from}-${to}/${total}` });
  };
  function resp(status, body, headers) {
    const buf = Buffer.isBuffer(body) ? body : Buffer.from(body, "utf8");
    return {
      getResponseCode: () => status,
      getContentText: () => buf.toString("utf8"),
      getContent: () => [...buf],
      getAllHeaders: () => headers,
    };
  }
  const ctx = {
    JSON, Math, Number, String, Object, Array, RegExp, isFinite,
    UrlFetchApp: {
      fetch: (url, opts) => answer(url, opts),
      fetchAll: (reqs) => reqs.map((r) => answer(r.url, r)),
    },
    Utilities: {
      newBlob: (s) => ({ bytes: Buffer.from(s, "utf8") }),
      gzip: (blob) => ({ getBytes: () => [...gzipSync(blob.bytes)] }),
      base64Encode: (bytes) => Buffer.from(bytes).toString("base64"),
    },
    ContentService: {
      MimeType: { JSON: "json", TEXT: "text" },
      createTextOutput: (s) => ({ text: s, setMimeType() { return this; } }),
    },
  };
  vm.createContext(ctx);
  vm.runInContext(code, ctx);
  const call = (body) => {
    const out = ctx.doPost({ postData: { contents: typeof body === "string" ? body : JSON.stringify(body) } }).text;
    try {
      return JSON.parse(out);
    } catch {
      return out;
    }
  };
  return { call, fetched, ctx };
}

const ready = source.replace("__GEREH_TG_KEY__", KEY);
let n = 0;
function test(name, f) {
  f();
  n++;
  console.log("ok - " + name);
}

test("no asterisk and no block comment anywhere (Google refused copies that had them)", () => {
  assert.ok(!source.includes("*"));
});

test("the template, a wrong key, bad JSON and GET look like a forgotten script", () => {
  const t = makeEnv(source);
  assert.match(t.call({ op: "ping", k: "__GEREH_TG_KEY__" }), /did not return anything/);
  const e = makeEnv(ready);
  assert.match(e.call({ op: "ping", k: "d".repeat(32) }), /did not return anything/);
  assert.match(e.call("{"), /did not return anything/);
  assert.match(e.ctx.doGet({}).text, /did not return anything/);
  assert.equal(e.fetched.length, 0);
});

test("ping says the version and what it is", () => {
  const e = makeEnv(ready);
  assert.deepEqual(e.call({ op: "ping", k: KEY }), { ok: 1, ver: 4, kind: "tg" });
});

test("pages go back gzipped, newest first, and stop at the page count", () => {
  const e = makeEnv(ready);
  const r = e.call({ op: "tg", k: KEY, n: "SomeChannel", p: 2 });
  assert.equal(r.ok, 1);
  assert.equal(r.s, 200);
  assert.equal(r.z.length, 2);
  const first = gunzipSync(Buffer.from(r.z[0], "base64")).toString("utf8");
  const second = gunzipSync(Buffer.from(r.z[1], "base64")).toString("utf8");
  assert.ok(first.includes('data-post="SomeChannel/45"') && first.includes('data-post="SomeChannel/26"'));
  assert.ok(second.includes('data-post="SomeChannel/25"') && second.includes('data-post="SomeChannel/6"'));
  assert.equal(r.more, 1);
  assert.deepEqual(e.fetched, ["https://t.me/s/SomeChannel", "https://t.me/s/SomeChannel?before=26"]);
});

test("the oldest page ends the walk; after= walks forward and stops when nothing is newer", () => {
  const e = makeEnv(ready);
  const old = e.call({ op: "tg", k: KEY, n: "SomeChannel", b: 6, p: 5 });
  assert.equal(old.z.length, 1);
  assert.equal(old.more, 0);
  const f = makeEnv(ready);
  const fwd = f.call({ op: "tg", k: KEY, n: "SomeChannel", a: 10, p: 5 });
  assert.equal(fwd.z.length, 2); // 11..30, 31..45; the newest page again (nothing newer) is not sent
  assert.equal(fwd.more, 0);
  const g = makeEnv(ready);
  const none = g.call({ op: "tg", k: KEY, n: "SomeChannel", a: 45 });
  assert.equal(none.s, 200);
  assert.equal(none.z.length, 0);
});

test("a name that is not a channel name, both directions, or a bad count is refused unread", () => {
  const e = makeEnv(ready);
  for (const bad of [{ n: "a/../b" }, { n: "x" }, { n: "1abcde" }, { n: "okname", a: 3, b: 9 }, { n: "okname", p: -1 }, { n: "okname", b: 2.5 }]) {
    assert.deepEqual(e.call({ op: "tg", k: KEY, ...bad }), { e: "bad" });
  }
  assert.equal(e.fetched.length, 0);
});

test("a channel with no public page says Telegram's redirect", () => {
  const e = makeEnv(ready);
  const r = e.call({ op: "tg", k: KEY, n: "missing" });
  assert.equal(r.s, 302);
  assert.equal(r.z.length, 0);
  assert.equal(r.loc, "https://t.me/missing");
});

test("files: parts by range with the whole size; only telesco.pe, at most 8 and 2 MB", () => {
  const e = makeEnv(ready);
  const url = "https://cdn4.telesco.pe/file/AbCdEf123456.mp4?token=xyz";
  const r = e.call({ op: "tgf", k: KEY, i: [[url, 0, 0], [url, 100, 1099]] });
  assert.equal(r.r.length, 2);
  assert.deepEqual(r.r[0].slice(0, 3), [206, 5000, "video/mp4"]);
  assert.equal(Buffer.from(r.r[1][3], "base64").length, 1000);
  for (const bad of [
    [["https://evil.example/file/x.mp4", 0, 0]],
    [["http://cdn4.telesco.pe/file/AbCdEf123456.mp4", 0, 0]],
    [[url, 0, 2097152]],
    [[url, 0, 1048575], [url, 1048576, 2097152]],
    Array.from({ length: 9 }, () => [url, 0, 0]),
    [[url, 5, 4]],
  ]) {
    assert.deepEqual(e.call({ op: "tgf", k: KEY, i: bad }), { e: "bad" });
  }
});

test("Google's quota error is said as quota", () => {
  const e = makeEnv(ready);
  e.ctx.UrlFetchApp.fetch = () => {
    throw new Error("Service invoked too many times for one day: urlfetch.");
  };
  assert.deepEqual(e.call({ op: "tg", k: KEY, n: "SomeChannel" }), { e: "quota" });
});

test("search: a name is looked at on Telegram itself first, then the directory, no repeats, 20 at most", () => {
  const e = makeEnv(ready);
  const r = e.call({ op: "tgs", k: KEY, q: " https://t.me/NewsFa " });
  assert.equal(r.ok, 1);
  assert.deepEqual(r.r[0].slice(0, 4), ["NewsFa", "خبر فوری & تحلیل", "316 248", "t"]);
  assert.deepEqual(r.r[1].slice(0, 4), ["configs_free", "کانفیگ رایگان & سریع", "12.5K", "d"]);
  assert.deepEqual(r.r[2].slice(0, 4), ["vpn_iran", "VPN Iran", "980", "d"]);
  assert.equal(r.r.length, 20);
  assert.equal(r.r.filter((x) => x[0] === "NewsFa").length, 1);
  assert.deepEqual(e.fetched.slice(0, 2), ["https://t.me/NewsFa", "https://tg.me/search?q=https%3A%2F%2Ft.me%2FNewsFa"]);
  const g = makeEnv(ready);
  assert.deepEqual(g.call({ op: "tgs", k: KEY, q: "@GroupFa" }).r[0].slice(0, 4), ["GroupFa", "گروه پرسش", "12 345", "t"]);
});

test("search: words go only to the directory; a person or a missing name is no channel", () => {
  const e = makeEnv(ready);
  const r = e.call({ op: "tgs", k: KEY, q: "کانفیگ رایگان" });
  assert.equal(r.r[0][3], "d");
  assert.equal(e.fetched[0], "https://tg.me/search?q=" + encodeURIComponent("کانفیگ رایگان"));
  assert.ok(!e.fetched.some((u) => u.startsWith("https://t.me/") && !u.startsWith("https://t.me/i/userpic/")), "words are not a name: Telegram's page is not asked");
  const p = makeEnv(ready);
  assert.deepEqual(p.call({ op: "tgs", k: KEY, q: "@someperson" }), { ok: 1, ver: 4, r: [] });
  const m = makeEnv(ready);
  assert.ok(m.call({ op: "tgs", k: KEY, q: "nosuchname" }).r.every((x) => x[3] === "d"));
});

test("search: nothing to look for is refused unread; the quota is said as quota", () => {
  const e = makeEnv(ready);
  assert.deepEqual(e.call({ op: "tgs", k: KEY, q: "   " }), { e: "bad" });
  assert.deepEqual(e.call({ op: "tgs", k: KEY }), { e: "bad" });
  assert.equal(e.fetched.length, 0);
  e.ctx.UrlFetchApp.fetch = () => {
    throw new Error("Service invoked too many times for one day: urlfetch.");
  };
  assert.deepEqual(e.call({ op: "tgs", k: KEY, q: "news" }), { e: "quota" });
});

test("search: each answer's small photo comes with it: Telegram's own small copy, else tg.me's or the page's", () => {
  const e = makeEnv(ready);
  const r = e.call({ op: "tgs", k: KEY, q: "NewsFa" });
  assert.equal(r.ver, 4, "the phone knows this script brings photos");
  const photo = (row) => Buffer.from(row[4], "base64").toString("utf8", 4);
  const of = (name) => r.r.find((row) => row[0] === name);
  assert.equal(photo(of("vpn_iran")), "userpic:vpn_iran", "Telegram's own 160-pixel copy first");
  assert.equal(photo(of("NewsFa")), "page-photo", "none there: the page's own photo of a name read on Telegram");
  assert.equal(photo(of("configs_free")), "configs_free", "none there: tg.me's copy");
  assert.ok(r.r.every((row) => row.length === 5));
  // no photo anywhere, or not a photo (an SVG): none
  assert.equal(of("more_channel_1")[4], "");
  assert.equal(photo(of("more_channel_2")), "more_channel_2", "an SVG is no photo: tg.me's copy instead");
  assert.ok(e.fetched.includes("https://t.me/i/userpic/160/vpn_iran.jpg"));
  assert.ok(!e.fetched.includes("https://tg.me/api/avatar/vpn_iran?v=3"), "a photo found once is not fetched twice");
  assert.ok(e.fetched.includes("https://cdn4.telesco.pe/file/AbCdEfPhoto1234.jpg"));
});

test("search: one address that cannot be fetched at all does not cost the other photos", () => {
  const e = makeEnv(ready);
  e.ctx.UrlFetchApp.fetchAll = () => {
    throw new Error("Address unavailable: https://t.me/i/userpic/160/more_channel_5.jpg");
  };
  const r = e.call({ op: "tgs", k: KEY, q: "vpn" });
  assert.equal(Buffer.from(r.r.find((row) => row[0] === "vpn_iran")[4], "base64").toString("utf8", 4), "userpic:vpn_iran");
  assert.equal(Buffer.from(r.r.find((row) => row[0] === "configs_free")[4], "base64").toString("utf8", 4), "configs_free");
});

test("search: photos the phone has are not fetched again", () => {
  const e = makeEnv(ready);
  const r = e.call({ op: "tgs", k: KEY, q: "vpn", h: ["CONFIGS_FREE", "vpn_iran"] });
  assert.equal(r.r.find((row) => row[0] === "configs_free")[4], "");
  assert.equal(r.r.find((row) => row[0] === "vpn_iran")[4], "");
  assert.ok(!e.fetched.some((u) => /configs_free|vpn_iran/.test(u) && (u.includes("/api/avatar/") || u.includes("/i/userpic/"))));
  assert.notEqual(r.r.find((row) => row[0] === "NewsFa")[4], "");
});

console.log(`${n} tests passed`);
