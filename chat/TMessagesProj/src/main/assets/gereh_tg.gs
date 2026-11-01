// Gereh: your own Telegram channel reader (MLM CHAT, T68). Google Apps Script.
// Paste this whole file into an EMPTY Code.gs of a NEW project: delete everything that is there first.
//
// No block comments and no asterisk anywhere in this file: a phone's editor (or a chat app the
// code passed through) once turned them into stray characters and Google refused the code.
//
// When your own Gereh asks, this script reads a public Telegram channel's web page
// (https://t.me/s/NAME) and the photos, videos and voice notes that page shows (cdnN.telesco.pe),
// and hands them to your phone. Your phone never connects to Telegram: Telegram sees only Google.
// It is apart from your Gereh mailbox relay, keeps nothing, and only your phone has its key.
//
// Deploy: script.google.com -> New project -> paste -> Save -> Deploy -> New deployment ->
//   Web app, Execute as: Me, Who has access: Anyone -> Deploy -> allow the permission Google asks
//   for ("connect to an external service") -> copy the Deployment ID into Gereh.

var VERSION = 4;
var KEY = "__GEREH_TG_KEY__";
var NAME_RE = /^[A-Za-z][A-Za-z0-9_]{3,31}$/;
var FILE_RE = /^https:\/\/cdn[0-9]{1,3}\.telesco\.pe\/file\/[A-Za-z0-9_.~%=&?\/-]{8,2000}$/;
var MAX_PAGES = 5;
var MAX_FILES = 8;
var MAX_BYTES = 2097152;
var QUOTA_RE = /too many times|quota|limit exceeded|Service invoked/i;

var DECOY = "<!DOCTYPE html><html><head><title>Web App</title></head>" +
  "<body><p>The script completed but did not return anything.</p></body></html>";

function doGet(e) {
  return decoy_();
}

function doPost(e) {
  var req;
  try {
    req = JSON.parse(e.postData.contents);
  } catch (err) {
    return decoy_();
  }
  if (!req || typeof req !== "object") return decoy_();
  if (KEY.indexOf("__GEREH") === 0) return decoy_(); // the template itself, never a deployed key
  if (req.k !== KEY) return decoy_();
  try {
    switch (req.op) {
      case "ping":
        return json_({ ok: 1, ver: VERSION, kind: "tg" });
      case "tg":
        return json_(pages_(req));
      case "tgf":
        return json_(files_(req));
      case "tgs":
        return json_(search_(req));
      default:
        return json_({ e: "bad" });
    }
  } catch (err) {
    if (QUOTA_RE.test(String(err && err.message ? err.message : err))) return json_({ e: "quota" });
    return json_({ e: "error" });
  }
}

// tg: pages of https://t.me/s/NAME, newest first; b = older than that post, a = newer than that
// post, p = how many pages (each about 20 posts). Each page goes back gzipped, in base64.
// s is Telegram's answer to the first page: 302 = no such public channel (or its page is off).
function pages_(req) {
  var name = String(req.n || "");
  if (!NAME_RE.test(name)) return { e: "bad" };
  var b = num_(req.b);
  var a = num_(req.a);
  var p = num_(req.p);
  if (b < 0 || a < 0 || p < 0 || (b > 0 && a > 0)) return { e: "bad" };
  p = Math.max(1, Math.min(MAX_PAGES, p || 1));
  var out = [];
  var status = 0;
  var loc = "";
  var more = 0;
  for (var i = 0; i < p; i++) {
    var url = "https://t.me/s/" + name + (b > 0 ? "?before=" + b : a > 0 ? "?after=" + a : "");
    var res = UrlFetchApp.fetch(url, { muteHttpExceptions: true, followRedirects: false, headers: { "Accept-Language": "en" } });
    var code = res.getResponseCode();
    if (i === 0) {
      status = code;
      if (code >= 300 && code < 400) loc = String(header_(res, "Location")).slice(0, 200);
    }
    if (code !== 200) break;
    var html = res.getContentText();
    var ids = ids_(html, name);
    more = 0;
    if (!ids.length) {
      if (i === 0) out.push(gz_(html)); // a channel with no post yet: its name, photo and about
      break;
    }
    // a page with nothing past where the walk is goes nowhere: after the newest post Telegram
    // gives the newest page again
    var top = Math.max.apply(null, ids);
    var low = Math.min.apply(null, ids);
    if ((a > 0 && top <= a) || (b > 0 && low >= b)) break;
    out.push(gz_(html));
    if (a > 0) {
      a = top;
    } else {
      if (low <= 1) break;
      b = low;
    }
    more = 1;
  }
  return { ok: 1, s: status, z: out, more: more, loc: loc };
}

// tgf: up to 8 files, or parts of files, from the page (cdnN.telesco.pe), at most 2 MB together.
// Each item is [address, first byte, last byte]; [address, 0, 0] tells only the file's size.
// Answer per item: [status, whole size, type, base64 bytes].
function files_(req) {
  var list = req.i;
  if (!Array.isArray(list) || !list.length || list.length > MAX_FILES) return { e: "bad" };
  var asks = [];
  var spans = [];
  var total = 0;
  for (var i = 0; i < list.length; i++) {
    var it = list[i];
    if (!Array.isArray(it) || it.length !== 3) return { e: "bad" };
    var url = String(it[0]);
    var from = num_(it[1]);
    var to = num_(it[2]);
    if (!FILE_RE.test(url) || from < 0 || to < from || to - from + 1 > MAX_BYTES) return { e: "bad" };
    total += to - from + 1;
    if (total > MAX_BYTES) return { e: "bad" };
    asks.push({ url: url, muteHttpExceptions: true, followRedirects: true, headers: { Range: "bytes=" + from + "-" + to } });
    spans.push(to - from + 1);
  }
  var res;
  try {
    res = UrlFetchApp.fetchAll(asks);
  } catch (err) {
    if (QUOTA_RE.test(String(err && err.message ? err.message : err))) throw err;
    // one address the network failed takes the whole batch down: each alone then
    res = asks.map(function (one) {
      try {
        return UrlFetchApp.fetch(one.url, one);
      } catch (e2) {
        if (QUOTA_RE.test(String(e2 && e2.message ? e2.message : e2))) throw e2;
        return null;
      }
    });
  }
  var r = [];
  for (var j = 0; j < res.length; j++) {
    var got = res[j];
    if (!got) {
      r.push([0, 0, "", ""]);
      continue;
    }
    var code = got.getResponseCode();
    var bytes = code === 200 || code === 206 ? got.getContent() : [];
    var size = 0;
    var range = String(header_(got, "Content-Range")).match(/\/([0-9]+)$/);
    if (range) size = Number(range[1]);
    else if (code === 200) size = bytes.length;
    if (bytes.length > spans[j]) bytes = bytes.slice(0, spans[j]);
    r.push([code, size, String(header_(got, "Content-Type")).slice(0, 40), bytes.length ? Utilities.base64Encode(bytes) : ""]);
  }
  return { ok: 1, r: r };
}

// tgs: a search of Telegram's public channels and groups for q: words, a name or a t.me link.
// Telegram has no search without an account, so the words go to tg.me's search (a public
// directory of Telegram channels, written in its page); a name is looked at on Telegram itself
// too. Up to 20 answers, the best first: [name, title, members as written there (e.g. 316K),
// where found: "t" Telegram, "d" the directory, its small photo in base64 or ""]. h: the names
// (small letters) whose photo the phone has already, not fetched again. ver tells the phone
// this script brings photos (a VERSION 2 script searched without them).
var SEARCH_MAX = 20;
var PHOTO_MAX = 49152;
var PHOTO_RE = /^image\/(jpeg|png|webp)/i;
var ROW_RE = /<a href="\/([A-Za-z][A-Za-z0-9_]{3,31})" class="rl-row">[\s\S]{0,3000}?<span class="rl-name-txt"[^>]{0,80}>([\s\S]{0,600}?)<\/span>[\s\S]{0,1500}?<span class="rl-metric">([^<]{0,40})<\/span>/g;

function search_(req) {
  var q = String(req.q || "").replace(/^\s+|\s+$/g, "").slice(0, 64);
  if (!q) return { e: "bad" };
  var have = {};
  if (Array.isArray(req.h)) {
    for (var k = 0; k < req.h.length && k < 64; k++) have[String(req.h[k]).toLowerCase()] = 1;
  }
  var out = [];
  var seen = {};
  var m = q.match(/^(?:@|(?:https?:\/\/)?(?:t|telegram)\.(?:me|dog)\/(?:s\/)?)?([A-Za-z][A-Za-z0-9_]{3,31})\/?$/);
  if (m) {
    var one = page_(m[1]);
    if (one) {
      out.push(one);
      seen[one[0].toLowerCase()] = 1;
    }
  }
  var res = UrlFetchApp.fetch("https://tg.me/search?q=" + encodeURIComponent(q), { muteHttpExceptions: true, followRedirects: true, headers: { "Accept-Language": "en" } });
  if (res.getResponseCode() === 200) {
    var html = res.getContentText();
    ROW_RE.lastIndex = 0;
    var r;
    while ((r = ROW_RE.exec(html)) !== null && out.length < SEARCH_MAX) {
      var key = r[1].toLowerCase();
      if (seen[key]) continue;
      seen[key] = 1;
      out.push([r[1], text_(r[2]).slice(0, 128), text_(r[3]).slice(0, 16), "d", ""]);
    }
  }
  photos_(out, have);
  return { ok: 1, ver: VERSION, r: out };
}

// each answer's small photo, all at once: Telegram's own 160-pixel copy (t.me/i/userpic, about
// 10 KB), and for those it did not give, tg.me's copy or, for a name read on Telegram itself, the
// page's own photo; left out when the phone has it, too big, or not a photo
function photos_(rows, have) {
  var want = [];
  for (var i = 0; i < rows.length; i++) {
    var row = rows[i];
    var other = row[4] || "https://tg.me/api/avatar/" + row[0] + "?v=3";
    row[4] = "";
    if (have[row[0].toLowerCase()]) continue;
    want.push({ i: i, urls: ["https://t.me/i/userpic/160/" + row[0] + ".jpg", other] });
  }
  for (var pass = 0; pass < 2 && want.length; pass++) {
    var got = fetchEach_(want.map(function (w) { return w.urls[pass]; }));
    var left = [];
    for (var j = 0; j < want.length; j++) {
      var b64 = photo_(got[j]);
      if (b64) rows[want[j].i][4] = b64;
      else left.push(want[j]);
    }
    want = left;
  }
}

// a photo's bytes in base64 from an answer, or "" (none there, not a photo, or too big)
function photo_(res) {
  if (!res || res.getResponseCode() !== 200) return "";
  if (!PHOTO_RE.test(String(header_(res, "Content-Type")))) return "";
  var bytes = res.getContent();
  if (!bytes.length || bytes.length > PHOTO_MAX) return "";
  return Utilities.base64Encode(bytes);
}

// the answers for these addresses, all at once; one that cannot be fetched at all fails the
// whole fetchAll, and then each is asked on its own for a few seconds (null for one not asked
// or failed). Google's quota still ends the search.
function fetchEach_(urls) {
  var asks = urls.map(function (u) {
    return { url: u, muteHttpExceptions: true, followRedirects: true, headers: u.indexOf("https://tg.me/") === 0 ? { Referer: "https://tg.me/" } : {} };
  });
  try {
    return UrlFetchApp.fetchAll(asks);
  } catch (err) {
    if (QUOTA_RE.test(String(err && err.message ? err.message : err))) throw err;
  }
  var out = [];
  var until = Date.now() + 12000;
  for (var i = 0; i < asks.length; i++) {
    var one = null;
    if (Date.now() < until) {
      try {
        one = UrlFetchApp.fetch(asks[i].url, { muteHttpExceptions: true, followRedirects: true, headers: asks[i].headers });
      } catch (err2) {
        if (QUOTA_RE.test(String(err2 && err2.message ? err2.message : err2))) throw err2;
      }
    }
    out.push(one);
  }
  return out;
}

// t.me/NAME, Telegram's public preview: [name, title, members, "t"] for a channel or group,
// null for no such name, a person or a bot
function page_(name) {
  var res = UrlFetchApp.fetch("https://t.me/" + name, { muteHttpExceptions: true, followRedirects: false, headers: { "Accept-Language": "en" } });
  if (res.getResponseCode() !== 200) return null;
  var html = res.getContentText();
  var title = (html.match(/<meta property="og:title" content="([^"]{1,300})"/) || [])[1];
  var extra = (html.match(/<div class="tgme_page_extra">([\s\S]{0,400}?)<\/div>/) || [])[1];
  if (!title || !extra || !/subscriber|member/i.test(extra)) return null;
  var n = (extra.match(/([0-9][0-9 ,.]{0,12}[0-9KMkm]?)\s{0,3}(subscriber|member)/i) || [])[1] || "";
  var img = (html.match(/<meta property="og:image" content="(https:\/\/cdn[0-9]{1,3}\.telesco\.pe\/file\/[^"]{8,1500})"/) || [])[1] || "";
  return [name, text_(title).slice(0, 128), n.replace(/\s+/g, " ").replace(/^\s+|\s+$/g, "").slice(0, 16), "t", img];
}

function text_(h) {
  return String(h)
    .replace(/<!--[\s\S]{0,200}?-->/g, "")
    .replace(/<[^>]{0,400}>/g, "")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/\s+/g, " ")
    .replace(/^\s+|\s+$/g, "");
}

function gz_(html) {
  return Utilities.base64Encode(Utilities.gzip(Utilities.newBlob(html, "text/html")).getBytes());
}

function ids_(html, name) {
  var re = new RegExp('data-post="' + name + '/([0-9]+)"', "gi");
  var ids = [];
  var m;
  while ((m = re.exec(html)) !== null) ids.push(Number(m[1]));
  return ids;
}

function header_(res, name) {
  var h = res.getAllHeaders();
  return h[name] || h[name.toLowerCase()] || "";
}

// a whole number 0 or more; missing is 0, anything else -1
function num_(v) {
  if (v === undefined || v === null || v === "") return 0;
  var n = Number(v);
  return isFinite(n) && n >= 0 && Math.floor(n) === n ? n : -1;
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function decoy_() {
  return ContentService.createTextOutput(DECOY).setMimeType(ContentService.MimeType.TEXT);
}
