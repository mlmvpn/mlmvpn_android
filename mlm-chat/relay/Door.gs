// Gereh combined relay: the Google door (MLM CHAT, SPEC 19.13) — Google Apps Script.
// Paste this whole file into an EMPTY Code.gs of a NEW project: not the project of a simple relay.
//
// No block comments and no asterisk anywhere in this file (a phone's editor turned them into stray
// characters, and Google refused the code).
//
// The mailbox of a combined relay is the owner's own Cloudflare Worker (relay/MailboxWorker.js). A
// phone that reaches Cloudflare talks to it straight. This script is the other door, for phones that
// reach only Google: it hands each request to the Worker as it came and gives the Worker's answer
// back. It keeps nothing and knows no key; the Worker checks the keys. Every item in a request is a
// sealed frame (SPEC 6, 14.6), so neither this script nor Google can read one.
//
// The app makes this file with the Worker's address in it.
//
// Deploy: script.google.com -> New project -> paste -> Save -> Deploy -> New deployment ->
//   Web app, Execute as: Me, Who has access: Anyone -> Deploy -> authorize (it asks to connect to an
//   external service: that is the Worker) -> copy the Deployment ID into Gereh.

var DOOR_VERSION = 1;
var WORKER_URL = "__MLMCHAT_WORKER_URL__";
var FETCH_FAILS = /too many times|quota|limit exceeded|Service invoked/i;

var DECOY = "<!DOCTYPE html><html><head><title>Web App</title></head>" +
  "<body><p>The script completed but did not return anything.</p></body></html>";

function doGet(e) {
  return decoy_();
}

function doPost(e) {
  if (WORKER_URL.indexOf("__MLMCHAT") === 0) return decoy_(); // the template itself
  var body = e && e.postData ? e.postData.contents : "";
  if (!body) return decoy_();
  var r;
  try {
    r = UrlFetchApp.fetch(WORKER_URL, {
      method: "post",
      contentType: "application/json",
      payload: body,
      muteHttpExceptions: true,
      followRedirects: false
    });
  } catch (err) {
    var m = String(err && err.message ? err.message : err);
    return json_(FETCH_FAILS.test(m) ? "{\"e\":\"quota\"}" : "{\"e\":\"remote\"}");
  }
  var code = r.getResponseCode();
  var text = r.getContentText();
  if (code === 429) return json_("{\"e\":\"quota\"}");
  if (code !== 200) return json_("{\"e\":\"remote\"}");
  // the Worker's own decoy (a wrong key) goes back as the same decoy
  if (text.charAt(0) !== "{") return decoy_();
  return json_(text);
}

function json_(text) {
  return ContentService.createTextOutput(text).setMimeType(ContentService.MimeType.JSON);
}

function decoy_() {
  return ContentService.createTextOutput(DECOY).setMimeType(ContentService.MimeType.TEXT);
}
