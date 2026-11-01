// Gereh mailbox relay (MLM CHAT, SPEC 19) — Google Apps Script.
// Paste this whole file into an EMPTY Code.gs: delete everything that is there first.
//
// No block comments and no asterisk anywhere in this file: a phone's editor (or a chat app the
// code passed through) turned them into stray characters and Google refused the code
// ("SyntaxError: Unexpected token" on an asterisk, Ehsan's tester, 25 Oct 2026).
//
// A mailbox in the owner's own Google account. Phones that cannot reach each other (Irancell and
// MCI, for example) both reach Google: the sender leaves sealed frames here under the recipient's
// daily tag, the recipient takes them. Every item is a SealedFrame (SPEC 6, 14.6): end-to-end
// encrypted and sealed to the recipient, so this script and Google see neither the text nor who
// sent it. A tag is random-looking and changes every day.
//
// The app makes this file with two fresh random keys in it and the owner pastes it as is:
//   USE_KEY   goes in the owner's contact card and in share links: anyone holding it may leave
//             and take items.
//   OWNER_KEY stays on the owner's phone: statistics, pause, and the limit on how many devices
//             may use this relay each day.
//
// Deploy: script.google.com -> New project -> paste -> Save -> Deploy -> New deployment ->
//   Web app, Execute as: Me, Who has access: Anyone -> Deploy -> copy the Deployment ID into Gereh.
//
// No UrlFetchApp: this script never calls out, so the daily URL-fetch quota is not used at all.
// Storage is CacheService (an item lives at most 6 hours; the sender's phone puts it again until
// it is taken, up to 7 days) and PropertiesService for settings and daily totals.

var VERSION = 3;
var USE_KEY = "__MLMCHAT_USE_KEY__";
var OWNER_KEY = "__MLMCHAT_OWNER_KEY__";

var ITEM_TTL_S = 21600;          // 6 h, the most CacheService keeps a value
var MAX_ITEM_CHARS = 98000;      // one base64 item, under the 100 KB of one cache value
var MAX_ITEMS_PER_PUT = 64;
var MAX_PER_TAG = 400;           // items waiting for one recipient
var MAX_TAKE_CHARS = 3000000;
var MAX_TAGS = 4;
var MAX_ACKS = 500;
var MAX_WAIT_MS = 20000;         // a take may wait this long for an item to arrive
var WAIT_STEP_MS = 800;
var MAX_WAITERS = 8;             // waiting takes at once: each holds one of the owner's 30 executions
var LOCK_MS = 10000;
var DAYS_KEPT = 14;
var FLUSH_MS = 300000;           // daily totals go to the script properties at most this often (5 min)
var MAX_CLIENTS_TRACKED = 2000;  // the day's anonymous device ids kept for counting
var MAX_TAGS_TRACKED = 1000;     // the day's recipient tags kept for counting
var DEFAULT_MAX_CLIENTS = 40;    // devices a day a new relay serves; the owner changes it in the app (0 = no limit)

// The official feeds' copy (version 3, SPEC 18.2): signed items of the official channel and group,
// kept in the script properties (they outlive the cache's 6 hours) so a phone with no contact yet
// reads them, and the member count sketches merged. Every phone checks each item's signature; this
// script cannot make or change one, only keep, drop or withhold them.
var FEED_BUDGET_CHARS = 380000;  // items kept, of the 500 KB the script properties hold in all
var FEED_PART_CHARS = 8000;      // one property value holds at most 9 KB
var MAX_FEED_ITEM_CHARS = 24000;
var MAX_FEED_PUT = 16;
var MAX_FEED_INFO = 24;          // admin lists, names and photos kept per feed
var MAX_FEED_TAKE_CHARS = 1500000;
var HEX64 = /^[0-9a-f]{64}$/;

var HEX32 = /^[0-9a-f]{32}$/;
var HEX16 = /^[0-9a-f]{16}$/;
var B64 = /^[A-Za-z0-9+\/=_-]+$/;

// What a wrong key, a GET or a malformed body gets: the page of a forgotten Apps Script project,
// nothing a scanner could tell from any other script.
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
  if (USE_KEY.indexOf("__MLMCHAT") === 0) return decoy_(); // the template itself, never deployed keys
  var owner = typeof req.o === "string" && req.o === OWNER_KEY;
  if (!owner && req.k !== USE_KEY) return decoy_();
  var c = typeof req.c === "string" && HEX16.test(req.c) ? req.c : "";
  try {
    var cfg = cfg_();
    if (!owner && cfg.paused && req.op !== "ping") return json_({ e: "paused" });
    switch (req.op) {
      case "ping":
        return json_(ping_(c, owner, cfg));
      case "put":
        return json_(put_(req, c, owner, cfg));
      case "take":
        return json_(take_(req, c, owner, cfg));
      case "chk":
        return json_(chk_(req, c, owner, cfg));
      case "fget":
        return json_(fget_(req, c, owner, cfg));
      case "fput":
        return json_(fput_(req, c, owner, cfg));
      case "stats":
        return json_(owner ? stats_(cfg) : { e: "owner" });
      case "admin":
        return json_(owner ? admin_(req) : { e: "owner" });
      default:
        return json_({ e: "bad" });
    }
  } catch (err) {
    var m = String(err && err.message ? err.message : err);
    if (/too many times|quota|limit exceeded|Service invoked/i.test(m)) return json_({ e: "quota" });
    if (/lock|timed out|timeout/i.test(m)) return json_({ e: "busy" });
    return json_({ e: "error" });
  }
}

// ---- operations ---------------------------------------------------------------------------

function ping_(c, owner, cfg) {
  var lock = LockService.getScriptLock();
  if (lock.tryLock(2000)) {
    try {
      var cache = CacheService.getScriptCache();
      var st = statsLoad_(cache);
      admit_(st, c, owner, cfg, true);
      st.r++;
      statsSave_(cache, st);
    } finally {
      lock.releaseLock();
    }
  }
  // pub: the owner opened this relay to everyone (version 2); only the owner key sets it, so a
  // phone that borrows a public relay asks the relay itself, not whoever told it about it
  return { ok: 1, ver: VERSION, owner: owner ? 1 : 0, paused: cfg.paused ? 1 : 0, pub: cfg.pub ? 1 : 0 };
}

// Leave items for one tag: `t` the tag, `i` a list of [id, base64]. One result per item.
function put_(req, c, owner, cfg) {
  var tag = req.t;
  if (typeof tag !== "string" || !HEX32.test(tag) || !Array.isArray(req.i) || req.i.length === 0 || req.i.length > MAX_ITEMS_PER_PUT) {
    return { e: "bad" };
  }
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_MS)) return { e: "busy" };
  try {
    var cache = CacheService.getScriptCache();
    var st = statsLoad_(cache);
    if (!admit_(st, c, owner, cfg, false)) return { e: "limit" };
    var qKey = "q:" + tag;
    var q = parse_(cache.get(qKey), []);
    var doneKeys = [];
    req.i.forEach(function (it) {
      if (Array.isArray(it) && typeof it[0] === "string" && HEX32.test(it[0])) doneKeys.push("d:" + it[0]);
    });
    var done = doneKeys.length ? cache.getAll(doneKeys) : {};
    var values = {};
    var res = [];
    var stored = 0;
    var chars = 0;
    req.i.forEach(function (it) {
      if (!Array.isArray(it) || it.length < 2 || typeof it[0] !== "string" || !HEX32.test(it[0]) || typeof it[1] !== "string" || it[1].length === 0) {
        res.push("bad");
        return;
      }
      var id = it[0];
      if (done["d:" + id]) {
        res.push("done"); // taken already: the sender stops putting it again
        return;
      }
      if (it[1].length > MAX_ITEM_CHARS) {
        res.push("size");
        return;
      }
      if (!B64.test(it[1])) {
        res.push("bad");
        return;
      }
      if (q.indexOf(id) < 0) {
        if (q.length >= MAX_PER_TAG) {
          res.push("full");
          return;
        }
        q.push(id);
      }
      values["i:" + id] = it[1];
      stored++;
      chars += it[1].length;
      res.push("ok");
    });
    if (stored > 0) {
      cache.putAll(values, ITEM_TTL_S);
      cache.put(qKey, JSON.stringify(q), ITEM_TTL_S);
      if (Object.keys(st.g).length < MAX_TAGS_TRACKED) st.g[tag] = 1;
    }
    st.r++;
    st.p++;
    st.ii += stored;
    st.ci += chars;
    statsSave_(cache, st);
    return { ok: 1, r: res };
  } finally {
    lock.releaseLock();
  }
}

// Take the items waiting for up to four tags (`t`), after removing those acknowledged (`a`, ids
// from an earlier take). With `w`, waits up to that many ms for something to arrive. `m` caps the
// size of the answer. Items come back as [id, tag, base64]; `more` says some did not fit.
function take_(req, c, owner, cfg) {
  var tags = Array.isArray(req.t) ? req.t.filter(function (t) { return typeof t === "string" && HEX32.test(t); }).slice(0, MAX_TAGS) : [];
  if (tags.length === 0) return { e: "bad" };
  var acks = Array.isArray(req.a) ? req.a.filter(function (a) { return typeof a === "string" && HEX32.test(a); }).slice(0, MAX_ACKS) : [];
  var wait = Math.min(Math.max(Number(req.w) || 0, 0), MAX_WAIT_MS);
  var max = Math.min(Math.max(Number(req.m) || MAX_TAKE_CHARS, 100000), MAX_TAKE_CHARS);
  var cache = CacheService.getScriptCache();
  var out = takeOnce_(cache, tags, acks, max, c, owner, cfg, false);
  if (out.e || out.i.length > 0 || wait === 0) return out;
  if (!waiterEnter_(cache)) return out;
  try {
    var until = Date.now() + wait;
    while (Date.now() + WAIT_STEP_MS < until) {
      Utilities.sleep(WAIT_STEP_MS);
      var qs = cache.getAll(tags.map(function (t) { return "q:" + t; }));
      var any = tags.some(function (t) { return parse_(qs["q:" + t], []).length > 0; });
      if (any) return takeOnce_(cache, tags, [], max, c, owner, cfg, true);
    }
  } finally {
    waiterLeave_(cache);
  }
  return out;
}

function takeOnce_(cache, tags, acks, max, c, owner, cfg, again) {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_MS)) return { e: "busy" };
  try {
    var st = statsLoad_(cache);
    if (!again && !admit_(st, c, owner, cfg, false)) return { e: "limit" };
    var qs = cache.getAll(tags.map(function (t) { return "q:" + t; }));
    var acked = {};
    acks.forEach(function (a) { acked[a] = 1; });
    var removed = [];
    var doneMarks = {};
    var items = [];
    var chars = 0;
    var more = false;
    tags.forEach(function (t) {
      var q = parse_(qs["q:" + t], []);
      var keep = [];
      q.forEach(function (id) {
        if (acked[id]) {
          removed.push("i:" + id);
          doneMarks["d:" + id] = "1";
        } else {
          keep.push(id);
        }
      });
      var vals = keep.length ? cache.getAll(keep.map(function (id) { return "i:" + id; })) : {};
      var alive = [];
      keep.forEach(function (id) {
        var d = vals["i:" + id];
        if (d === null || d === undefined) return; // expired, or the cache let it go
        alive.push(id);
        if (items.length > 0 && chars + d.length > max) {
          more = true;
          return;
        }
        items.push([id, t, d]);
        chars += d.length;
      });
      if (alive.length > 0) {
        cache.put("q:" + t, JSON.stringify(alive), ITEM_TTL_S);
      } else if (q.length > 0) {
        cache.remove("q:" + t);
      }
    });
    if (removed.length > 0) {
      cache.removeAll(removed);
      cache.putAll(doneMarks, ITEM_TTL_S);
    }
    if (!again) st.r++;
    st.t++;
    st.io += items.length;
    st.co += chars;
    statsSave_(cache, st);
    return { ok: 1, i: items, more: more ? 1 : 0 };
  } finally {
    lock.releaseLock();
  }
}

// Where items are, for the sender: `t` the tag, `i` ids. Per id: "wait" (still here), "done"
// (taken and acknowledged: stop putting it), or "gone" (neither: it expired or the cache let it
// go, so the sender puts it again; if it had been taken long ago the receiver sees a repeat and
// drops it).
function chk_(req, c, owner, cfg) {
  var tag = req.t;
  if (typeof tag !== "string" || !HEX32.test(tag) || !Array.isArray(req.i) || req.i.length === 0 || req.i.length > MAX_ITEMS_PER_PUT) {
    return { e: "bad" };
  }
  var ids = req.i.filter(function (x) { return typeof x === "string" && HEX32.test(x); });
  if (ids.length !== req.i.length) return { e: "bad" };
  var cache = CacheService.getScriptCache();
  var q = parse_(cache.get("q:" + tag), []);
  var keys = [];
  ids.forEach(function (id) { keys.push("d:" + id); keys.push("i:" + id); });
  var got = cache.getAll(keys);
  var res = ids.map(function (id) {
    if (got["d:" + id]) return "done";
    if (q.indexOf(id) >= 0 && got["i:" + id]) return "wait";
    return "gone";
  });
  var lock = LockService.getScriptLock();
  if (lock.tryLock(2000)) {
    try {
      var st = statsLoad_(cache);
      if (!admit_(st, c, owner, cfg, false)) return { e: "limit" };
      st.r++;
      statsSave_(cache, st);
    } finally {
      lock.releaseLock();
    }
  }
  return { ok: 1, r: res };
}

// The owner's view: today and the last days. Device and recipient counts are approximate.
function stats_(cfg) {
  var cache = CacheService.getScriptCache();
  var st = statsLoad_(cache);
  var days = flush_(st);
  return { ok: 1, ver: VERSION, now: Date.now(), day: st.d, today: summary_(st), days: days, cfg: cfg, waiters: Number(cache.get("w")) || 0 };
}

// Pause the relay for everyone but the owner, limit the devices a day (0 = no limit), or open it
// to everyone as a public relay (`pub`, version 2).
function admin_(req) {
  var cfg = cfg_();
  if (typeof req.paused === "boolean") cfg.paused = req.paused;
  if (typeof req.pub === "boolean") cfg.pub = req.pub;
  if (typeof req.max_clients === "number" && req.max_clients >= 0 && req.max_clients <= 100000) cfg.max_clients = Math.floor(req.max_clients);
  PropertiesService.getScriptProperties().setProperty("cfg", JSON.stringify(cfg));
  CacheService.getScriptCache().put("cfg", JSON.stringify(cfg), 600);
  return { ok: 1, cfg: cfg };
}

// ---- the official feeds (version 3, SPEC 18.2) ----------------------------------------------
//
// An item is kept as one key that says everything about it, "f|seq|feed|id|class|chars|parts"
// (value "1"), and its base64 in parts "p|id|n" (a property holds 9 KB at most). The list of keys
// and each item stay in the cache too: phones read from the cache, the properties only when it
// lost them (Google allows 50,000 property reads and writes a day).
// Class: 0 a post (or anything else), 1 an admin list, name or photo, 2 a reaction.

// Read what is new since `s` (the `s` of the last answer, 0 at first) in the feeds `f`, at most
// about `m` characters. Items come back as [id, feed, seq, base64], oldest first, with the merged
// member count sketches `cs` as [[period, base64]].
function fget_(req, c, owner, cfg) {
  var feeds = Array.isArray(req.f) ? req.f.filter(function (f) { return typeof f === "string" && HEX32.test(f); }).slice(0, 8) : [];
  if (feeds.length === 0) return { e: "bad" };
  var since = Math.max(Math.floor(Number(req.s) || 0), 0);
  var max = Math.min(Math.max(Number(req.m) || MAX_FEED_TAKE_CHARS, 20000), MAX_FEED_TAKE_CHARS);
  var cache = CacheService.getScriptCache();
  feedCount_(cache, owner, "fr");
  var index = feedIndex_(cache);
  var wanted = index.filter(function (m) { return m.seq > since && feeds.indexOf(m.feed) >= 0; });
  var chosen = [];
  var chars = 0;
  var more = false;
  for (var n = 0; n < wanted.length; n++) {
    if (chosen.length > 0 && chars + wanted[n].len > max) {
      more = true;
      break;
    }
    chosen.push(wanted[n]);
    chars += wanted[n].len;
  }
  var values = feedValues_(cache, chosen);
  var items = [];
  chosen.forEach(function (m) {
    if (values[m.id]) items.push([m.id, m.feed, m.seq, values[m.id]]);
  });
  var top = index.length ? index[index.length - 1].seq : 0;
  var next = more ? chosen[chosen.length - 1].seq : Math.max(top, since);
  return { ok: 1, s: next, i: items, more: more ? 1 : 0, cs: censusGet_(cache) };
}

// Keep items of one feed `f`: `i` a list of [id, base64, class], each signed by its author (the
// phones check that). One result per item: "ok", "dup" (kept already) or "bad". With `cs`, a
// phone's member count sketches, merged into the kept ones.
function fput_(req, c, owner, cfg) {
  var feed = req.f;
  var list = Array.isArray(req.i) ? req.i : [];
  var cs = Array.isArray(req.cs) ? req.cs : [];
  if (typeof feed !== "string" || !HEX32.test(feed) || list.length > MAX_FEED_PUT || (list.length === 0 && cs.length === 0)) {
    return { e: "bad" };
  }
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_MS)) return { e: "busy" };
  try {
    var cache = CacheService.getScriptCache();
    var props = PropertiesService.getScriptProperties();
    feedCount_(cache, owner, "fw", true);
    var index = feedIndex_(cache);
    var have = {};
    index.forEach(function (m) { have[m.id] = 1; });
    var seq = 0;
    var values = {};
    var res = [];
    list.forEach(function (it) {
      if (!Array.isArray(it) || typeof it[0] !== "string" || !HEX32.test(it[0]) || typeof it[1] !== "string" ||
          it[1].length === 0 || it[1].length > MAX_FEED_ITEM_CHARS || !B64.test(it[1])) {
        res.push("bad");
        return;
      }
      if (have[it[0]]) {
        res.push("dup");
        return;
      }
      if (seq === 0) seq = Math.max(Number(props.getProperty("fseq")) || 0, index.length ? index[index.length - 1].seq : 0);
      seq++;
      var cls = it[2] === 1 || it[2] === 2 ? it[2] : 0;
      var parts = 0;
      for (var at = 0; at < it[1].length; at += FEED_PART_CHARS) {
        values["p|" + it[0] + "|" + parts] = it[1].substring(at, at + FEED_PART_CHARS);
        parts++;
      }
      var key = ["f", ("000000000" + seq).slice(-9), feed, it[0], cls, it[1].length, parts].join("|");
      values[key] = "1";
      index.push(feedMeta_(key));
      have[it[0]] = 1;
      try {
        cache.put("fv|" + it[0], it[1], ITEM_TTL_S);
      } catch (err) {
        // too large for one cache value: read from the properties
      }
      res.push("ok");
    });
    if (seq > 0) {
      values.fseq = String(seq);
      props.setProperties(values);
      feedPrune_(props, index);
      feedIndexSave_(cache, index);
    }
    if (cs.length > 0) censusMerge_(cache, props, cs);
    return { ok: 1, r: res };
  } finally {
    lock.releaseLock();
  }
}

function feedMeta_(k) {
  var p = k.split("|");
  if (p.length !== 7 || p[0] !== "f" || !HEX32.test(p[2]) || !HEX32.test(p[3])) return null;
  return { k: k, seq: Number(p[1]), feed: p[2], id: p[3], cls: Number(p[4]), len: Number(p[5]), parts: Number(p[6]) };
}

// The items kept, oldest first.
function feedIndex_(cache) {
  var keys = parse_(cache.get("fk"), null);
  if (!keys) {
    keys = PropertiesService.getScriptProperties().getKeys().filter(function (k) { return k.indexOf("f|") === 0; });
    try {
      cache.put("fk", JSON.stringify(keys), ITEM_TTL_S);
    } catch (err) {
      // the list outgrew one cache value: read from the properties every time
    }
  }
  var list = [];
  keys.forEach(function (k) {
    var m = feedMeta_(k);
    if (m) list.push(m);
  });
  list.sort(function (a, b) { return a.seq - b.seq; });
  return list;
}

function feedIndexSave_(cache, index) {
  try {
    cache.put("fk", JSON.stringify(index.map(function (m) { return m.k; })), ITEM_TTL_S);
  } catch (err) {
    cache.remove("fk");
  }
}

// The items' base64 by id: from the cache, else (several missing) all properties in one read.
function feedValues_(cache, metas) {
  var out = {};
  if (metas.length === 0) return out;
  var got = cache.getAll(metas.map(function (m) { return "fv|" + m.id; }));
  var missing = metas.filter(function (m) { return !got["fv|" + m.id]; });
  metas.forEach(function (m) {
    if (got["fv|" + m.id]) out[m.id] = got["fv|" + m.id];
  });
  if (missing.length === 0) return out;
  var props = PropertiesService.getScriptProperties();
  var all = missing.length > 3 ? props.getProperties() : null;
  var back = {};
  missing.forEach(function (m) {
    var v = "";
    for (var k = 0; k < m.parts; k++) {
      var key = "p|" + m.id + "|" + k;
      var part = all ? all[key] : props.getProperty(key);
      if (part === null || part === undefined) return; // a part went missing: the item is skipped
      v += part;
    }
    out[m.id] = v;
    if (v.length < 90000) back["fv|" + m.id] = v;
  });
  try {
    cache.putAll(back, ITEM_TTL_S);
  } catch (err) {
    // fine: read from the properties again next time
  }
  return out;
}

// Over the budget: the oldest reactions go first, then the oldest posts. Per feed, admin lists,
// names and photos beyond the newest MAX_FEED_INFO go. `index` is changed in place.
function feedPrune_(props, index) {
  var total = 0;
  index.forEach(function (m) { total += m.len; });
  var drop = {};
  var info = {};
  for (var n = index.length - 1; n >= 0; n--) {
    var m = index[n];
    if (m.cls !== 1) continue;
    info[m.feed] = (info[m.feed] || 0) + 1;
    if (info[m.feed] > MAX_FEED_INFO) {
      drop[m.id] = m;
      total -= m.len;
    }
  }
  [2, 0].forEach(function (cls) {
    for (var n = 0; n < index.length && total > FEED_BUDGET_CHARS; n++) {
      var m = index[n];
      if (m.cls === cls && !drop[m.id]) {
        drop[m.id] = m;
        total -= m.len;
      }
    }
  });
  var ids = Object.keys(drop);
  if (ids.length === 0) return;
  ids.forEach(function (id) {
    var m = drop[id];
    props.deleteProperty(m.k);
    for (var k = 0; k < m.parts; k++) props.deleteProperty("p|" + m.id + "|" + k);
  });
  var kept = index.filter(function (m) { return !drop[m.id]; });
  index.length = 0;
  kept.forEach(function (m) { index.push(m); });
}

// Feed reads and writes in the day's totals. Reads take the lock only if it is free at once.
function feedCount_(cache, owner, field, locked) {
  var lock = null;
  if (!locked) {
    lock = LockService.getScriptLock();
    if (!lock.tryLock(1000)) return;
  }
  try {
    var st = statsLoad_(cache);
    if (owner) st.oc++;
    st.r++;
    st[field] = (st[field] || 0) + 1;
    statsSave_(cache, st);
  } finally {
    if (lock) lock.releaseLock();
  }
}

// The member count sketches kept: the two newest periods, [[period, base64]].
function censusGet_(cache) {
  var v = parse_(cache.get("fcs"), null);
  if (v) return v;
  v = parse_(PropertiesService.getScriptProperties().getProperty("cs"), []);
  cache.put("fcs", JSON.stringify(v), ITEM_TTL_S);
  return v;
}

// A phone's sketches into the kept ones: each counter keeps the higher value. Only this period and
// the last count (a far-off period number would push the real ones out).
function censusMerge_(cache, props, incoming) {
  var period = Math.floor(Date.now() / 1209600000);
  var mine = parse_(props.getProperty("cs"), []);
  var changed = false;
  incoming.forEach(function (s) {
    if (!Array.isArray(s) || typeof s[0] !== "number" || typeof s[1] !== "string" || !B64.test(s[1])) return;
    var epoch = Math.floor(s[0]);
    if (epoch !== period && epoch !== period - 1) return;
    var theirs = unpack5_(s[1]);
    if (!theirs) return;
    var at = -1;
    mine.forEach(function (x, n) { if (x[0] === epoch) at = n; });
    var regs = at >= 0 ? unpack5_(mine[at][1]) : null;
    if (!regs) {
      regs = [];
      for (var z = 0; z < 2048; z++) regs.push(0);
    }
    var rose = false;
    for (var i = 0; i < 2048; i++) {
      if (theirs[i] > regs[i]) {
        regs[i] = theirs[i];
        rose = true;
      }
    }
    if (!rose) return;
    var packed = pack5_(regs);
    if (at >= 0) mine[at][1] = packed;
    else mine.push([epoch, packed]);
    changed = true;
  });
  mine = mine.filter(function (x) { return x[0] === period || x[0] === period - 1; });
  if (changed) {
    props.setProperty("cs", JSON.stringify(mine));
    cache.put("fcs", JSON.stringify(mine), ITEM_TTL_S);
  }
}

// 2048 counters of 5 bits, packed high bit first (the app's census.rs): 1280 bytes.
function unpack5_(b64) {
  var s = b64.replace(/=+$/, "");
  while (s.length % 4 !== 0) s += "=";
  var b;
  try {
    b = Utilities.base64DecodeWebSafe(s);
  } catch (err) {
    return null;
  }
  if (b.length !== 1280) return null;
  var regs = [];
  for (var i = 0; i < 2048; i++) {
    var bit = (i << 2) + i;
    var at = bit >> 3;
    var hi = b[at] & 255;
    var lo = at + 1 < 1280 ? b[at + 1] & 255 : 0;
    regs.push((((hi << 8) | lo) >> (11 - (bit & 7))) & 31);
  }
  return regs;
}

function pack5_(regs) {
  var out = [];
  for (var k = 0; k < 1280; k++) out.push(0);
  for (var i = 0; i < 2048; i++) {
    var bit = (i << 2) + i;
    var at = bit >> 3;
    var v = (regs[i] & 31) << (11 - (bit & 7));
    out[at] |= (v >> 8) & 255;
    if (at + 1 < 1280) out[at + 1] |= v & 255;
  }
  var signed = out.map(function (x) { return x > 127 ? x - 256 : x; });
  return Utilities.base64EncodeWebSafe(signed).replace(/=+$/, "");
}

// ---- counting -----------------------------------------------------------------------------

function day_() {
  return Math.floor(Date.now() / 86400000);
}

function cfg_() {
  var cache = CacheService.getScriptCache();
  var cfg = parse_(cache.get("cfg"), null);
  if (!cfg) {
    cfg = parse_(PropertiesService.getScriptProperties().getProperty("cfg"), null) || { paused: false, max_clients: DEFAULT_MAX_CLIENTS };
    cache.put("cfg", JSON.stringify(cfg), 600);
  }
  return cfg;
}

function daysProp_() {
  return parse_(PropertiesService.getScriptProperties().getProperty("days"), []);
}

function statsLoad_(cache) {
  var d = day_();
  var st = parse_(cache.get("s:" + d), null);
  if (st) return st;
  st = { d: d, r: 0, p: 0, t: 0, ii: 0, io: 0, ci: 0, co: 0, oc: 0, n: 0, cb: 0, gb: 0, c: {}, g: {} };
  // the cache lost today's counts (6 hours quiet, or memory): go on from the last saved totals
  var saved = daysProp_().filter(function (x) { return x.d === d; })[0];
  if (saved) {
    ["r", "p", "t", "ii", "io", "ci", "co", "oc", "fr", "fw"].forEach(function (k) { st[k] = saved[k] || 0; });
    st.cb = saved.c || 0;
    st.gb = saved.g || 0;
  }
  return st;
}

// The day's totals stay in the cache; the script properties get them on the first request and then
// at most every FLUSH_MS, however many phones ask. Every 20 requests, as before, would have spent
// 36,000 of Google's 50,000 property operations a day on an official relay with 5,000 users.
function statsSave_(cache, st) {
  st.n++;
  var now = Date.now();
  var due = st.n === 1 || now - (st.fl || 0) >= FLUSH_MS;
  if (due) st.fl = now;
  cache.put("s:" + st.d, JSON.stringify(st), ITEM_TTL_S);
  if (due) flush_(st);
}

// Whether this device may go on today; counts it (never the owner) among the day's devices.
function admit_(st, c, owner, cfg, ping) {
  if (owner) {
    st.oc++;
    return true;
  }
  if (c && !st.c[c]) {
    var n = st.cb + Object.keys(st.c).length;
    if (!ping && cfg.max_clients > 0 && n >= cfg.max_clients) return false;
    if (Object.keys(st.c).length < MAX_CLIENTS_TRACKED) st.c[c] = 1;
  }
  return true;
}

function summary_(st) {
  return { d: st.d, r: st.r, p: st.p, t: st.t, ii: st.ii, io: st.io, ci: st.ci, co: st.co, oc: st.oc, fr: st.fr || 0, fw: st.fw || 0, c: st.cb + Object.keys(st.c).length, g: st.gb + Object.keys(st.g).length };
}

function flush_(st) {
  var days = daysProp_().filter(function (x) { return x.d !== st.d && x.d > st.d - DAYS_KEPT; });
  days.push(summary_(st));
  days.sort(function (a, b) { return a.d - b.d; });
  PropertiesService.getScriptProperties().setProperty("days", JSON.stringify(days));
  return days;
}

function waiterEnter_(cache) {
  var n = Number(cache.get("w")) || 0;
  if (n >= MAX_WAITERS) return false;
  cache.put("w", String(n + 1), 60);
  return true;
}

function waiterLeave_(cache) {
  var n = Number(cache.get("w")) || 0;
  if (n > 1) cache.put("w", String(n - 1), 60);
  else cache.remove("w");
}

// ---- helpers ------------------------------------------------------------------------------

function parse_(s, fallback) {
  if (s === null || s === undefined || s === "") return fallback;
  try {
    return JSON.parse(s);
  } catch (err) {
    return fallback;
  }
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function decoy_() {
  return ContentService.createTextOutput(DECOY).setMimeType(ContentService.MimeType.TEXT);
}
