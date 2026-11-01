// Gereh: can a Google Apps Script read a public Telegram channel's web page?
// A one-off measurement for the "popular Telegram channel" proposal (T68). The result of the
// run on 30 Oct 2026 is in docs/TG-CHANNEL.fa.md.
// Paste into a NEW project at script.google.com, press Run on probe, allow the
// "connect to an external service" permission, then copy the Execution log.
// It reads public pages only (t.me/s and the photos/videos they show), keeps
// nothing, and sends nothing anywhere else.

var CHANNELS = ["telegram", "durov"];

function probe() {
  var out = [];
  for (var i = 0; i < CHANNELS.length; i++) out.push(probeChannel_(CHANNELS[i]));
  var miss = get_("https://t.me/s/gereh_no_such_channel_9x", null, false);
  out.push({ channel: "missing", page: miss.sum, location: String(miss.headers["Location"] || miss.headers["location"] || "").slice(0, 60) });
  Logger.log(JSON.stringify({ when: new Date().toISOString(), results: out }));
}

function probeChannel_(name) {
  var r = { channel: name };
  var page = get_("https://t.me/s/" + name, null, true);
  r.page = page.sum;
  if (page.code !== 200) return r;
  var html = page.text;
  var ids = postIds_(html, name);
  r.page.posts = ids.length;
  r.page.first = ids.length ? ids[0] : 0;
  r.page.last = ids.length ? ids[ids.length - 1] : 0;
  r.page.gzip = Utilities.gzip(Utilities.newBlob(html)).getBytes().length;
  r.page.title = /tgme_channel_info_header_title/.test(html);
  r.page.about = /tgme_channel_info_description/.test(html);
  r.page.photo = /tgme_page_photo_image/.test(html);
  if (r.page.first > 1) {
    var older = get_("https://t.me/s/" + name + "?before=" + r.page.first, null, true);
    r.before = older.sum;
    if (older.code === 200) {
      var oids = postIds_(older.text, name);
      r.before.posts = oids.length;
      r.before.first = oids.length ? oids[0] : 0;
      r.before.last = oids.length ? oids[oids.length - 1] : 0;
      var newer = get_("https://t.me/s/" + name + "?after=" + r.before.first, null, true);
      r.after = newer.sum;
      if (newer.code === 200) {
        var nids = postIds_(newer.text, name);
        r.after.posts = nids.length;
        r.after.first = nids.length ? nids[0] : 0;
      }
    }
  }
  var photo = html.match(/photo_wrap[^>]*background-image:url\('([^']+)'\)/);
  if (photo) r.photo = get_(photo[1], null, true).sum;
  var video = html.match(/<video src="([^"]+)"/);
  if (video) {
    var v = get_(video[1], { Range: "bytes=0-1023" }, true);
    r.videoRange = v.sum;
    r.videoRange.contentRange = v.headers["Content-Range"] || v.headers["content-range"] || "";
  }
  return r;
}

function get_(url, headers, follow) {
  var t0 = Date.now();
  var res;
  try {
    res = UrlFetchApp.fetch(url, { muteHttpExceptions: true, followRedirects: follow, headers: headers || {} });
  } catch (e) {
    return { code: -1, text: "", headers: {}, sum: { host: host_(url), error: String(e).slice(0, 200), ms: Date.now() - t0 } };
  }
  var hdrs = res.getAllHeaders();
  var ct = String(hdrs["Content-Type"] || hdrs["content-type"] || "");
  var sum = { host: host_(url), code: res.getResponseCode(), bytes: res.getContent().length, ms: Date.now() - t0, type: ct.slice(0, 40) };
  var text = /text|html/.test(ct) ? res.getContentText() : "";
  return { code: res.getResponseCode(), text: text, headers: hdrs, sum: sum };
}

function postIds_(html, name) {
  var re = new RegExp('data-post="' + name + '/(\\d+)"', "gi");
  var ids = [];
  var m;
  while ((m = re.exec(html)) !== null) ids.push(Number(m[1]));
  return ids;
}

function host_(url) {
  var m = String(url).match(/^https?:\/\/([^\/?#]+)/);
  return m ? m[1] : "";
}
