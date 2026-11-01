// Gereh T71: the file fetcher, a Google Apps Script web app in its owner's own Google account.
// A phone asks for the small file of a public Telegram channel's post (channel name and post
// number); this script gets it from Telegram with the fetcher's account (tgcore.js above, over
// Telegram's web transport) and hands it back. The phone never talks to Telegram, and Telegram
// sees only Google. The account's session is in this project's Script Properties (TG_SESSION),
// never in the code. It joins no channel and does nothing else with the account.
//
// Limits (Ehsan, 31 Oct 2026): files up to 2 MB, no video or audio; PHONE_DAY files a day for one
// phone; SCRIPT_DAY files read from Telegram a day for this script (Google allows 20,000 fetches
// a day; one file is a few); each file is kept for 6 hours and served from here meanwhile; when
// Telegram says to wait (FLOOD_WAIT), nobody asks it until then.

var FILES_VERSION = 1;
var FILES_KEY = "redacted-files-key";
var FILES_NAME_RE = /^[A-Za-z][A-Za-z0-9_]{3,31}$/;
var FILES_MAX = 2 * 1024 * 1024;
var PHONE_DAY = 20;
var SCRIPT_DAY = 2500;
var KEEP_S = 21600;
var CHUNK = 90000;
var FILES_QUOTA_RE = /too many times|quota|limit exceeded|Service invoked/i;

function doGet(e) {
  return filesDecoy_();
}

function doPost(e) {
  var req;
  try {
    req = JSON.parse(e.postData.contents);
  } catch (err) {
    return filesDecoy_();
  }
  if (!req || typeof req !== "object" || req.k !== FILES_KEY) return filesDecoy_();
  try {
    if (req.op === "ping") {
      return filesJson_({ ok: 1, ver: FILES_VERSION, kind: "tgfiles", ready: !!PropertiesService.getScriptProperties().getProperty("TG_SESSION") });
    }
    if (req.op === "f") return filesJson_(fileOf_(req));
    return filesJson_({ e: "bad" });
  } catch (err) {
    if (FILES_QUOTA_RE.test(String(err && err.message ? err.message : err))) return filesJson_({ e: "quota" });
    return filesJson_({ e: "error" });
  }
}

// {c: channel name, p: post number, u: this phone's id for today (hex)} -> {ok, m: mime, s: size,
// b: base64} or {e: bad | limit | busy (w: seconds) | nofile | big | notconfig | nochannel | setup}
function fileOf_(req) {
  var c = String(req.c || ""), p = Number(req.p), u = String(req.u || "");
  if (!FILES_NAME_RE.test(c) || !(p > 0 && p < 2147483647 && Math.floor(p) === p) || !/^[0-9a-f]{16,64}$/.test(u)) return { e: "bad" };
  var cache = CacheService.getScriptCache();
  var day = Math.floor(Date.now() / 86400000);
  var uk = "u:" + day + ":" + u;
  var used = Number(cache.get(uk) || 0);
  if (used >= PHONE_DAY) return { e: "limit" };
  var key = "f:" + c.toLowerCase() + "/" + p;
  var kept = filesKept_(cache, key);
  if (kept) {
    cache.put(uk, String(used + 1), 86400);
    return kept;
  }
  if (cache.get(key + ":none")) return { e: "nofile" };
  var until = Number(cache.get("wait") || 0);
  if (until > Date.now()) return { e: "busy", w: Math.ceil((until - Date.now()) / 1000) };
  var props = PropertiesService.getScriptProperties();
  var dk = "day:" + day;
  var done = Number(props.getProperty(dk) || 0);
  if (done >= SCRIPT_DAY) return { e: "busy", w: 3600 };
  var session = JSON.parse(props.getProperty("TG_SESSION") || "null");
  if (!session) return { e: "setup" };
  var client = new TgClient(filesEnv_(), { apiId: session.apiId, apiHash: session.apiHash, home: session.home, keys: session.keys });
  for (var dc in client.keys) {
    var salt = cache.get("salt:" + dc);
    if (salt) client.keys[dc].salt = salt;
  }
  var d, bytes;
  try {
    var ch = filesChannel_(props, client, c);
    d = client.document(ch, p);
    if (!d) {
      cache.put(key + ":none", "1", 3600);
      return { e: "nofile" };
    }
    if (d.size > FILES_MAX) return { e: "big" };
    if (/^(video|audio|image)\//.test(d.mime)) return { e: "notconfig" };
    bytes = client.download(d, FILES_MAX);
  } catch (err) {
    var m = String(err && err.message ? err.message : err);
    var fw = /FLOOD_WAIT_(\d+)/.exec(m);
    if (fw) {
      var w = Number(fw[1]);
      cache.put("wait", String(Date.now() + w * 1000), Math.min(21600, w + 5));
      return { e: "busy", w: w };
    }
    if (/USERNAME_NOT_OCCUPIED|USERNAME_INVALID|CHANNEL_INVALID|CHANNEL_PRIVATE|not a channel|without access hash/.test(m)) return { e: "nochannel" };
    if (/AUTH_KEY_UNREGISTERED|SESSION_REVOKED|SESSION_EXPIRED|USER_DEACTIVATED|AUTH_KEY_DUPLICATED/.test(m)) return { e: "setup" };
    throw err;
  } finally {
    for (var k in client.keys) cache.put("salt:" + k, String(client.keys[k].salt), 1800);
  }
  props.setProperty(dk, String(done + 1));
  var out = { ok: 1, m: d.mime, s: bytes.length, b: Utilities.base64Encode(filesSigned_(bytes)) };
  filesKeep_(cache, key, out);
  cache.put(uk, String(used + 1), 86400);
  return out;
}

// a public channel's id and access hash, kept for good: Telegram allows few name lookups a day
function filesChannel_(props, client, name) {
  var pk = "ch:" + name.toLowerCase();
  var v = props.getProperty(pk);
  if (v) {
    var parts = v.split(":");
    return { id: BigInt(parts[0]), hash: BigInt(parts[1]) };
  }
  var ch = client.resolve(name);
  props.setProperty(pk, ch.id.toString() + ":" + ch.hash.toString());
  return ch;
}

// a file in the cache, in pieces of at most CHUNK characters (a cache value holds 100 KB)
function filesKeep_(cache, key, out) {
  var n = Math.ceil(out.b.length / CHUNK) || 1;
  var all = {};
  for (var i = 0; i < n; i++) all[key + ":" + i] = out.b.slice(i * CHUNK, (i + 1) * CHUNK);
  all[key + ":m"] = JSON.stringify({ m: out.m, s: out.s, n: n });
  cache.putAll(all, KEEP_S);
}

function filesKept_(cache, key) {
  var meta = cache.get(key + ":m");
  if (!meta) return null;
  meta = JSON.parse(meta);
  var names = [];
  for (var i = 0; i < meta.n; i++) names.push(key + ":" + i);
  var got = cache.getAll(names);
  var b = "";
  for (var j = 0; j < names.length; j++) {
    if (typeof got[names[j]] !== "string") return null;
    b += got[names[j]];
  }
  return { ok: 1, m: meta.m, s: meta.s, b: b };
}

function filesEnv_() {
  return {
    sha1: function (b) { return filesBytes_(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_1, filesSigned_(b))); },
    sha256: function (b) { return filesBytes_(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, filesSigned_(b))); },
    aes: TG_AES,
    random: function (n) {
      // Google's UUIDs come from a secure generator: 30 of their hex digits are random
      var out = new Uint8Array(n), i = 0;
      while (i < n) {
        var h = Utilities.getUuid().replace(/-/g, "");
        h = h.slice(0, 12) + h.slice(13, 16) + h.slice(17);
        for (var j = 0; j + 1 < h.length && i < n; j += 2) out[i++] = parseInt(h.substr(j, 2), 16);
      }
      return out;
    },
    now: function () { return Date.now(); },
    gunzip: function (b) { return filesBytes_(Utilities.ungzip(Utilities.newBlob(filesSigned_(b), "application/x-gzip")).getBytes()); },
    post: function (url, body) {
      var res = UrlFetchApp.fetch(url, { method: "post", contentType: "application/octet-stream", payload: filesSigned_(body), muteHttpExceptions: true, followRedirects: false });
      return filesBytes_(res.getContent());
    },
  };
}

function filesSigned_(u8) {
  var a = new Array(u8.length);
  for (var i = 0; i < u8.length; i++) a[i] = u8[i] > 127 ? u8[i] - 256 : u8[i];
  return a;
}

function filesBytes_(a) {
  var u = new Uint8Array(a.length);
  for (var i = 0; i < a.length; i++) u[i] = a[i] & 255;
  return u;
}

function filesJson_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function filesDecoy_() {
  return ContentService.createTextOutput("<!DOCTYPE html><html><head><title>Web App</title></head><body><p>The script completed but did not return anything.</p></body></html>")
    .setMimeType(ContentService.MimeType.TEXT);
}
