// Gereh: the Telegram file fetcher (T71). Google Apps Script, in its owner's own Google account.
// Paste this whole file into Code.gs of a new project, then:
//  1. Project Settings > Script Properties > Add script property: TG_SESSION, and as its value the
//     session (on the computer: node tgfiles.js copy, then paste). It acts as the fetcher's
//     Telegram account: show it to nobody.
//  2. Deploy > New deployment > Web app; Execute as: Me; Who has access: Anyone > Deploy; allow
//     "connect to an external service". The Deployment ID goes into the app.
// Built by mlm-chat/tools/tg-files/build-gs.js from tgcore.js, aes.js and gas-main.js: edit those.

// Gereh T71: a minimal MTProto 2.0 client over Telegram's web (HTTPS) transport, synchronous, for
// one job: the small file of a public channel's post. The same code runs in Node (the login tool,
// which sends with curl) and in Google Apps Script (UrlFetchApp). Hashes, AES blocks, randomness,
// gunzip and the HTTP POST come from `env`; nothing here talks to anything by itself.
//
// Layer 231, as the Telegram fork in this repository. The server answers in the layer a client
// declares, so this keeps working as Telegram moves on.

var TG_LAYER = 231;
var TG_HOSTS = { 1: "pluto", 2: "venus", 3: "aurora", 4: "vesta", 5: "flora" };
// Telegram's production RSA key (fingerprint 0xd09d1d85de64fd85), as embedded in its own clients
// (read from the fork's libtmessages.49.so)
var TG_RSA_FP = BigInt("-3414540481677951611");
var TG_RSA_N = BigInt("0x" +
  "e8bb3305c0b52c6cf2afdf7637313489e63e05268e5badb601af417786472e5f" +
  "93b85438968e20e6729a301c0afc121bf7151f834436f7fda680847a66bf64ac" +
  "cec78ee21c0b316f0edafe2f41908da7bd1f4a5107638eeb67040ace472a14f9" +
  "0d9f7c2b7def99688ba3073adb5750bb02964902a359fe745d8170e36876d4fd" +
  "8a5d41b2a76cbff9a13267eb9580b2d06d10357448d20d9da2191cb5d8c93982" +
  "961cdfdeda629e37f1fb09a0722027696032fe61ed663db7a37f6f263d370f69" +
  "db53a0dc0a1748bdaaff6209d5645485e6e001d1953255757e4b8e42813347b1" +
  "1da6ab500fd0ace7e6dfa3736199ccaf9397ed0745a427dcfa6cd67bcb1acff3");
var TG_RSA_E = BigInt(65537);

// ---- bytes -------------------------------------------------------------------------------

function tgConcat() {
  var n = 0;
  for (var i = 0; i < arguments.length; i++) n += arguments[i].length;
  var out = new Uint8Array(n);
  var at = 0;
  for (var j = 0; j < arguments.length; j++) {
    out.set(arguments[j], at);
    at += arguments[j].length;
  }
  return out;
}

function tgXor(a, b) {
  var out = new Uint8Array(a.length);
  for (var i = 0; i < a.length; i++) out[i] = a[i] ^ b[i];
  return out;
}

function tgEqual(a, b) {
  if (a.length !== b.length) return false;
  for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function tgBigFromBE(b) {
  var h = "0x";
  for (var i = 0; i < b.length; i++) h += (b[i] < 16 ? "0" : "") + b[i].toString(16);
  return b.length ? BigInt(h) : BigInt(0);
}

function tgBigToBE(v, len) {
  var h = v.toString(16);
  if (h.length % 2) h = "0" + h;
  var raw = new Uint8Array(h.length / 2);
  for (var i = 0; i < raw.length; i++) raw[i] = parseInt(h.substr(i * 2, 2), 16);
  if (len === undefined || raw.length === len) return raw;
  if (raw.length > len) return raw.subarray(raw.length - len);
  return tgConcat(new Uint8Array(len - raw.length), raw);
}

function tgModPow(b, e, m) {
  var r = BigInt(1);
  b = b % m;
  while (e > BigInt(0)) {
    if (e & BigInt(1)) r = (r * b) % m;
    e >>= BigInt(1);
    b = (b * b) % m;
  }
  return r;
}

function tgUtf8(s) {
  var out = [];
  for (var i = 0; i < s.length; i++) {
    var c = s.charCodeAt(i);
    if (c >= 0xd800 && c < 0xdc00 && i + 1 < s.length) {
      c = 0x10000 + ((c - 0xd800) << 10) + (s.charCodeAt(++i) - 0xdc00);
    }
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return new Uint8Array(out);
}

function tgFromUtf8(b) {
  var s = "";
  for (var i = 0; i < b.length; ) {
    var c = b[i++];
    if (c >= 0xf0) c = ((c & 7) << 18) | ((b[i++] & 63) << 12) | ((b[i++] & 63) << 6) | (b[i++] & 63);
    else if (c >= 0xe0) c = ((c & 15) << 12) | ((b[i++] & 63) << 6) | (b[i++] & 63);
    else if (c >= 0xc0) c = ((c & 31) << 6) | (b[i++] & 63);
    s += String.fromCodePoint(c);
  }
  return s;
}

// ---- TL ----------------------------------------------------------------------------------

function TgW() {
  this.parts = [];
}
TgW.prototype.raw = function (b) { this.parts.push(b); return this; };
TgW.prototype.int = function (v) {
  var b = new Uint8Array(4);
  b[0] = v & 255; b[1] = (v >>> 8) & 255; b[2] = (v >>> 16) & 255; b[3] = (v >>> 24) & 255;
  return this.raw(b);
};
TgW.prototype.long = function (v) {
  var x = BigInt.asUintN(64, BigInt(v));
  var b = new Uint8Array(8);
  for (var i = 0; i < 8; i++) { b[i] = Number(x & BigInt(255)); x >>= BigInt(8); }
  return this.raw(b);
};
TgW.prototype.bytes = function (d) {
  var head = d.length < 254 ? new Uint8Array([d.length]) : new Uint8Array([254, d.length & 255, (d.length >> 8) & 255, (d.length >> 16) & 255]);
  var pad = (4 - ((head.length + d.length) % 4)) % 4;
  return this.raw(head).raw(d).raw(new Uint8Array(pad));
};
TgW.prototype.string = function (s) { return this.bytes(tgUtf8(s)); };
TgW.prototype.done = function () { return tgConcat.apply(null, this.parts); };

function TgR(b) {
  this.b = b;
  this.p = 0;
}
TgR.prototype.int = function () {
  var b = this.b, p = this.p;
  if (p + 4 > b.length) throw new Error("tl: short read");
  this.p += 4;
  return (b[p] | (b[p + 1] << 8) | (b[p + 2] << 16) | (b[p + 3] << 24)) >>> 0;
};
TgR.prototype.long = function () {
  var lo = BigInt(this.int()), hi = BigInt(this.int());
  return BigInt.asIntN(64, (hi << BigInt(32)) | lo);
};
TgR.prototype.raw = function (n) {
  if (this.p + n > this.b.length) throw new Error("tl: short read");
  var out = this.b.subarray(this.p, this.p + n);
  this.p += n;
  return out;
};
TgR.prototype.bytes = function () {
  var n = this.b[this.p], head = 1;
  if (n === 254) { n = this.b[this.p + 1] | (this.b[this.p + 2] << 8) | (this.b[this.p + 3] << 16); head = 4; }
  this.p += head;
  var out = this.raw(n);
  this.p += (4 - ((head + n) % 4)) % 4;
  return out;
};
TgR.prototype.string = function () { return tgFromUtf8(this.bytes()); };

// ---- AES-IGE and MTProto 2.0 keys ----------------------------------------------------------

function tgIge(env, key, iv, data, encrypt) {
  var aes = env.aes(key);
  var out = new Uint8Array(data.length);
  var prevC = iv.subarray(0, 16), prevP = iv.subarray(16, 32);
  for (var i = 0; i < data.length; i += 16) {
    var block = data.subarray(i, i + 16);
    var res;
    if (encrypt) {
      res = tgXor(aes.enc(tgXor(block, prevC)), prevP);
      prevP = block;
      prevC = res;
    } else {
      res = tgXor(aes.dec(tgXor(block, prevP)), prevC);
      prevC = block;
      prevP = res;
    }
    out.set(res, i);
  }
  return out;
}

function tgMsgKeys(env, authKey, msgKey, x) {
  var a = env.sha256(tgConcat(msgKey, authKey.subarray(x, x + 36)));
  var b = env.sha256(tgConcat(authKey.subarray(40 + x, 76 + x), msgKey));
  return {
    key: tgConcat(a.subarray(0, 8), b.subarray(8, 24), a.subarray(24, 32)),
    iv: tgConcat(b.subarray(0, 8), a.subarray(8, 24), b.subarray(24, 32)),
  };
}

// ---- the client ----------------------------------------------------------------------------

// opts: { apiId, apiHash, keys: {dc: {key: hex, salt: decimal string}}, home: dc, device }
function TgClient(env, opts) {
  this.env = env;
  this.opts = opts;
  this.keys = opts.keys || {};
  this.timeOffset = 0;
  this.lastMsgId = BigInt(0);
  this.sessions = {};
}

TgClient.prototype.url = function (dc) {
  return "https://" + TG_HOSTS[dc] + ".web.telegram.org/apiw1";
};

TgClient.prototype.msgId = function () {
  var ms = this.env.now() + this.timeOffset * 1000;
  var id = (BigInt(Math.floor(ms / 1000)) << BigInt(32)) | (BigInt(ms % 1000) << BigInt(22)) | (BigInt(this.env.random(1)[0]) << BigInt(2));
  if (id <= this.lastMsgId) id = this.lastMsgId + BigInt(4);
  this.lastMsgId = id;
  return id;
};

TgClient.prototype.plainCall = function (dc, body) {
  var msg = new TgW().long(0).long(this.msgId()).int(body.length).raw(body).done();
  var res = this.env.post(this.url(dc), msg);
  var r = new TgR(res);
  if (res.length < 20) throw new Error("mtproto: transport error " + (res.length >= 4 ? new TgR(res).int() | 0 : "empty"));
  r.long();
  r.long();
  var n = r.int();
  return new TgR(r.raw(n));
};

// A new authorization key with data centre `dc` (MTProto 2.0 key exchange).
TgClient.prototype.createKey = function (dc) {
  var env = this.env;
  var nonce = env.random(16);
  var r = this.plainCall(dc, new TgW().int(0xbe7e8ef1).raw(nonce).done());
  if (r.int() !== 0x05162463) throw new Error("mtproto: no resPQ");
  if (!tgEqual(r.raw(16), nonce)) throw new Error("mtproto: nonce");
  var serverNonce = r.raw(16).slice();
  var pqBytes = r.bytes().slice();
  r.int();
  var nfp = r.int(), found = false;
  for (var i = 0; i < nfp; i++) if (r.long() === TG_RSA_FP) found = true;
  if (!found) throw new Error("mtproto: server key not known");
  var pq = tgBigFromBE(pqBytes);
  var p = tgFactor(pq), q = pq / p;
  if (p > q) { var t = p; p = q; q = t; }
  var pB = tgBigToBE(p), qB = tgBigToBE(q);
  var newNonce = env.random(32);
  var inner = new TgW().int(0xa9f55f95).bytes(pqBytes).bytes(pB).bytes(qB).raw(nonce).raw(serverNonce).raw(newNonce).int(dc).done();
  var encrypted = this.rsaPad(inner);
  r = this.plainCall(dc, new TgW().int(0xd712e4be).raw(nonce).raw(serverNonce).bytes(pB).bytes(qB).long(TG_RSA_FP).bytes(encrypted).done());
  if (r.int() !== 0xd0e8075c) throw new Error("mtproto: DH params refused");
  r.raw(32);
  var answer = r.bytes();
  var h1 = env.sha1(tgConcat(newNonce, serverNonce)), h2 = env.sha1(tgConcat(serverNonce, newNonce)), h3 = env.sha1(tgConcat(newNonce, newNonce));
  var tmpKey = tgConcat(h1, h2.subarray(0, 12));
  var tmpIv = tgConcat(h2.subarray(12, 20), h3, newNonce.subarray(0, 4));
  var plain = tgIge(env, tmpKey, tmpIv, answer, false);
  var ir = new TgR(plain.subarray(20));
  if (ir.int() !== 0xb5890dba) throw new Error("mtproto: no server_DH_inner_data");
  ir.raw(32);
  var g = BigInt(ir.int());
  var dhPrime = tgBigFromBE(ir.bytes());
  var gA = tgBigFromBE(ir.bytes());
  var serverTime = ir.int();
  this.timeOffset = serverTime - Math.floor(env.now() / 1000);
  var b = tgBigFromBE(env.random(256));
  var gB = tgModPow(g, b, dhPrime);
  var cInner = new TgW().int(0x6643b654).raw(nonce).raw(serverNonce).long(0).bytes(tgBigToBE(gB)).done();
  var withHash = tgConcat(env.sha1(cInner), cInner);
  withHash = tgConcat(withHash, env.random((16 - (withHash.length % 16)) % 16));
  r = this.plainCall(dc, new TgW().int(0xf5045f1f).raw(nonce).raw(serverNonce).bytes(tgIge(env, tmpKey, tmpIv, withHash, true)).done());
  if (r.int() !== 0x3bcbf734) throw new Error("mtproto: DH not accepted");
  var authKey = tgBigToBE(tgModPow(gA, b, dhPrime), 256);
  var salt = tgXor(newNonce.subarray(0, 8), serverNonce.subarray(0, 8));
  this.keys[dc] = { key: tgHex(authKey), salt: new TgR(salt).long().toString() };
  delete this.sessions[dc];
  return this.keys[dc];
};

TgClient.prototype.rsaPad = function (data) {
  var env = this.env;
  var padded = tgConcat(data, env.random(192 - data.length));
  var reversed = padded.slice().reverse();
  for (;;) {
    var tempKey = env.random(32);
    var withHash = tgConcat(reversed, env.sha256(tgConcat(tempKey, padded)));
    var aesEnc = tgIge(env, tempKey, new Uint8Array(32), withHash, true);
    var keyAes = tgConcat(tgXor(tempKey, env.sha256(aesEnc)), aesEnc);
    var v = tgBigFromBE(keyAes);
    if (v < TG_RSA_N) return tgBigToBE(tgModPow(v, TG_RSA_E, TG_RSA_N), 256);
  }
};

function tgFactor(n) {
  // Pollard's rho (Brent); pq is a product of two 31-bit primes
  if (n % BigInt(2) === BigInt(0)) return BigInt(2);
  for (var c = BigInt(1); ; c++) {
    var y = BigInt(2), x = y, g = BigInt(1), r = 1;
    while (g === BigInt(1)) {
      x = y;
      for (var i = 0; i < r; i++) y = (y * y + c) % n;
      for (var k = 0; k < r && g === BigInt(1); k++) {
        y = (y * y + c) % n;
        g = tgGcd(x > y ? x - y : y - x, n);
      }
      r *= 2;
    }
    if (g !== n) return g;
  }
}

function tgGcd(a, b) {
  while (b) { var t = a % b; a = b; b = t; }
  return a;
}

function tgHex(b) {
  var s = "";
  for (var i = 0; i < b.length; i++) s += (b[i] < 16 ? "0" : "") + b[i].toString(16);
  return s;
}

function tgUnhex(s) {
  var out = new Uint8Array(s.length / 2);
  for (var i = 0; i < out.length; i++) out[i] = parseInt(s.substr(i * 2, 2), 16);
  return out;
}

TgClient.prototype.session = function (dc) {
  var s = this.sessions[dc];
  if (!s) {
    var k = this.keys[dc];
    if (!k) throw new Error("mtproto: no key for dc " + dc);
    var key = tgUnhex(k.key);
    s = this.sessions[dc] = {
      key: key,
      keyId: this.env.sha1(key).subarray(12, 20),
      salt: BigInt(k.salt),
      id: this.env.random(8),
      seq: 0,
      acks: [],
      inited: false,
    };
  }
  return s;
};

TgClient.prototype.wrap = function (s, msgs) {
  // msgs: [{id, seq, body}] -> the message (a container when more than one)
  if (msgs.length === 1) return msgs[0];
  var w = new TgW().int(0x73f1f8dc).int(msgs.length);
  for (var i = 0; i < msgs.length; i++) w.long(msgs[i].id).int(msgs[i].seq).int(msgs[i].body.length).raw(msgs[i].body);
  return { id: this.msgId(), seq: s.seq * 2, body: w.done() };
};

TgClient.prototype.seal = function (s, m) {
  var env = this.env;
  var inner = new TgW().long(s.salt).raw(s.id).long(m.id).int(m.seq).int(m.body.length).raw(m.body).done();
  var pad = 12 + ((16 - ((inner.length + 12) % 16)) % 16);
  var plain = tgConcat(inner, env.random(pad));
  var msgKey = env.sha256(tgConcat(s.key.subarray(88, 120), plain)).subarray(8, 24);
  var k = tgMsgKeys(env, s.key, msgKey, 0);
  return tgConcat(s.keyId, msgKey, tgIge(env, k.key, k.iv, plain, true));
};

TgClient.prototype.open = function (s, res) {
  if (res.length < 24) throw new Error("mtproto: transport error " + (res.length >= 4 ? (new TgR(res).int() | 0) : "empty"));
  var msgKey = res.subarray(8, 24);
  var k = tgMsgKeys(this.env, s.key, msgKey, 8);
  var plain = tgIge(this.env, k.key, k.iv, res.subarray(24), false);
  if (!tgEqual(this.env.sha256(tgConcat(s.key.subarray(96, 128), plain)).subarray(8, 24), msgKey)) throw new Error("mtproto: bad msg_key");
  var r = new TgR(plain);
  r.long();
  r.raw(8);
  var msgId = r.long();
  var seq = r.int();
  var len = r.int();
  return { id: msgId, seq: seq, body: r.raw(len) };
};

// One call on data centre `dc`: the method's bytes in, the result's bytes out. Throws
// {code, message} for an RPC error (FLOOD_WAIT_X, FILE_MIGRATE_X, ...).
TgClient.prototype.call = function (dc, method) {
  var s = this.session(dc);
  var body = method;
  if (!s.inited) {
    // the first call of a session says who we are, in our layer
    var o = this.opts;
    var init = new TgW().int(0xc1cd5ea9).int(0).int(o.apiId).string(o.device || "Gereh files").string("1.0").string("1.0")
      .string("en").string("").string("en").raw(method).done();
    body = new TgW().int(0xda9b0d0d).int(TG_LAYER).raw(init).done();
  }
  for (var attempt = 0; attempt < 6; attempt++) {
    var callId = this.msgId();
    var msgs = [{ id: callId, seq: s.seq * 2 + 1, body: body }];
    s.seq++;
    if (s.acks.length) {
      var aw = new TgW().int(0x62d6b459).int(0x1cb5c415).int(s.acks.length);
      for (var i = 0; i < s.acks.length; i++) aw.long(s.acks[i]);
      msgs.push({ id: this.msgId(), seq: s.seq * 2, body: aw.done() });
      s.acks = [];
    }
    // http_wait: hold the answer until the result is ready (up to 25 s)
    msgs.push({ id: this.msgId(), seq: s.seq * 2, body: new TgW().int(0x9299359f).int(0).int(0).int(25000).done() });
    var out = this.wrap(s, msgs);
    var resend = false;
    for (var round = 0; round < 4 && !resend; round++) {
      var res = this.env.post(this.url(dc), this.seal(s, out));
      var got = this.open(s, res);
      var verdict = this.handle(s, got, callId);
      if (verdict.result) {
        s.inited = true;
        return verdict.result;
      }
      if (verdict.error) {
        s.inited = true;
        throw verdict.error;
      }
      resend = verdict.resend;
      if (!resend) {
        // nothing for us yet: ask again with a wait only
        out = { id: this.msgId(), seq: s.seq * 2, body: new TgW().int(0x9299359f).int(0).int(0).int(25000).done() };
      }
    }
  }
  throw new Error("mtproto: no answer");
};

TgClient.prototype.handle = function (s, m, callId) {
  var r = new TgR(m.body);
  var ctor = r.int();
  if (m.seq & 1) s.acks.push(m.id);
  var verdict = { resend: false };
  if (ctor === 0x73f1f8dc) {
    var n = r.int();
    for (var i = 0; i < n; i++) {
      var id = r.long(), seq = r.int(), len = r.int();
      var v = this.handle(s, { id: id, seq: seq, body: r.raw(len) }, callId);
      if (v.result || v.error) return v;
      if (v.resend) verdict.resend = true;
    }
    return verdict;
  }
  if (ctor === 0xf35c6d01) {
    // rpc_result
    if (r.long() !== callId) return verdict;
    var inner = r.raw(m.body.length - 12);
    return this.unpack(inner);
  }
  if (ctor === 0xedab447b) {
    // bad_server_salt: the new salt, and send again
    r.long(); r.int(); r.int();
    s.salt = r.long();
    this.keys[this.dcOf(s)].salt = s.salt.toString();
    verdict.resend = true;
    return verdict;
  }
  if (ctor === 0xa7eff811) {
    // bad_msg_notification: our clock (16, 17) or sequence; send again
    r.long(); r.int();
    var code = r.int();
    if (code === 16 || code === 17) this.timeOffset = Number(m.id >> BigInt(32)) - Math.floor(this.env.now() / 1000);
    if (code === 32 || code === 33) s.seq += 16;
    verdict.resend = true;
    return verdict;
  }
  if (ctor === 0x9ec20908) {
    // new_session_created
    r.long(); r.long();
    s.salt = r.long();
    return verdict;
  }
  return verdict; // acks, pongs, updates, salts: nothing to do
};

TgClient.prototype.dcOf = function (s) {
  for (var dc in this.sessions) if (this.sessions[dc] === s) return dc;
  return this.opts.home;
};

TgClient.prototype.unpack = function (inner) {
  var r = new TgR(inner);
  var ctor = r.int();
  if (ctor === 0x3072cfa1) {
    // gzip_packed
    return this.unpack(this.env.gunzip(r.bytes()));
  }
  if (ctor === 0x2144ca19) {
    var code = r.int() | 0;
    var e = new Error(r.string());
    e.code = code;
    return { error: e };
  }
  return { result: inner };
};

// ---- the API this needs ----------------------------------------------------------------------

// A call on the home data centre that follows PHONE/USER/NETWORK_MIGRATE_X once.
TgClient.prototype.home = function (method) {
  try {
    return this.call(this.opts.home, method);
  } catch (e) {
    var m = /^(PHONE|USER|NETWORK)_MIGRATE_(\d+)$/.exec(e.message || "");
    if (!m) throw e;
    this.opts.home = Number(m[2]);
    if (!this.keys[this.opts.home]) this.createKey(this.opts.home);
    return this.call(this.opts.home, method);
  }
};

TgClient.prototype.sendCode = function (phone) {
  var r = new TgR(this.home(new TgW().int(0xa677244f).string(phone).int(this.opts.apiId).string(this.opts.apiHash).int(0xad253d78).int(0).done()));
  var ctor = r.int();
  if (ctor === 0x2390fe44) throw new Error("already signed in");
  if (ctor !== 0x5e002502) throw new Error("auth.sentCode expected");
  r.int();
  var type = r.int();
  if (type === 0x3dbb5986 || type === 0xc000bba2 || type === 0x5353e5a7) r.int();
  else if (type === 0xab03c6d9) r.string();
  else if (type === 0x82006484 || type === 0xd9565c39) { r.string(); r.int(); }
  else if (type === 0xa416ac81 || type === 0xb37794af) { if (r.int() & 1) r.string(); }
  else throw new Error("code type not handled: 0x" + type.toString(16));
  return r.string();
};

// Signs in with the code; returns false when the account wants its password too.
TgClient.prototype.signIn = function (phone, hash, code) {
  try {
    this.home(new TgW().int(0x8d52a951).int(1).string(phone).string(hash).string(code).done());
    return true;
  } catch (e) {
    if (e.message === "SESSION_PASSWORD_NEEDED") return false;
    throw e;
  }
};

// The account's two-step password (SRP, as Telegram's clients do it); pbkdf2 from env.
TgClient.prototype.checkPassword = function (password) {
  var env = this.env;
  var r = new TgR(this.home(new TgW().int(0x548a30f5).done()));
  if (r.int() !== 0x957b50fb) throw new Error("account.password expected");
  var flags = r.int();
  if (!(flags & 4)) throw new Error("no password set");
  if (r.int() !== 0x3a912d4a) throw new Error("password algorithm not handled");
  var salt1 = r.bytes().slice(), salt2 = r.bytes().slice(), g = BigInt(r.int()), pB = r.bytes().slice();
  var srpB = r.bytes().slice(), srpId = r.long();
  var p = tgBigFromBE(pB);
  var H = function (b) { return env.sha256(b); };
  var SH = function (data, salt) { return H(tgConcat(salt, data, salt)); };
  var pw = tgUtf8(password);
  var x = tgBigFromBE(SH(env.pbkdf2(SH(SH(pw, salt1), salt2), salt1, 100000), salt2));
  var pad = function (v) { return tgBigToBE(v, 256); };
  var gB = tgBigFromBE(srpB);
  var k = tgBigFromBE(H(tgConcat(pad(p), pad(g))));
  var a = tgBigFromBE(env.random(256));
  var gA = tgModPow(g, a, p);
  var u = tgBigFromBE(H(tgConcat(pad(gA), pad(gB))));
  var kv = (k * tgModPow(g, x, p)) % p;
  var t = ((gB - kv) % p + p) % p;
  var sA = tgModPow(t, a + u * x, p);
  var kA = H(pad(sA));
  var m1 = H(tgConcat(tgXor(H(pad(p)), H(pad(g))), H(salt1), H(salt2), pad(gA), pad(gB), kA));
  this.home(new TgW().int(0xd18b4d16).int(0xd27ff082).long(srpId).bytes(pad(gA)).bytes(m1).done());
};

// The signed-in account on data centre `dc` too (for files kept there).
TgClient.prototype.authorizeOn = function (dc) {
  if (!this.keys[dc]) this.createKey(dc);
  var r = new TgR(this.home(new TgW().int(0xe5bfffcd).int(dc).done()));
  if (r.int() !== 0xb434e2b8) throw new Error("auth.exportedAuthorization expected");
  var id = r.long(), bytes = r.bytes().slice();
  this.call(dc, new TgW().int(0xa57a7dad).long(id).bytes(bytes).done());
};

// A public channel by its name: {id, hash}.
TgClient.prototype.resolve = function (name) {
  var r = new TgR(this.home(new TgW().int(0x725afbbc).int(0).string(name).done()));
  if (r.int() !== 0x7f077ad9) throw new Error("contacts.resolvedPeer expected");
  if (r.int() !== 0xa2a5371e) throw new Error("not a channel");
  var id = r.long();
  r.int();
  var n = r.int();
  for (var i = 0; i < n; i++) {
    var ctor = r.int();
    if (ctor !== 0xd49f34c6) break;
    var flags = r.int();
    r.int();
    var cid = r.long();
    if (cid === id && (flags & (1 << 13))) return { id: id, hash: r.long() };
    break;
  }
  throw new Error("channel without access hash");
};

// The document of post `post` of channel `ch`: {id, hash, ref, size, mime, dc}, or null when
// the post has none. The document is found by its own shape inside the answer (messageMediaDocument
// then document), so the many fields of a message before it need no parsing.
TgClient.prototype.document = function (ch, post) {
  // channels.getMessages#93d7b347 channel:InputChannel id:Vector<int> (the form the fork sends)
  var res = this.home(new TgW().int(0x93d7b347).int(0xf35aec28).long(ch.id).long(ch.hash).int(0x1cb5c415).int(1).int(post).done());
  for (var i = 0; i + 12 < res.length; i += 4) {
    var r = new TgR(res);
    r.p = i;
    if (r.int() !== 0x52d8ccd9) continue;
    var mflags = r.int();
    if (!(mflags & 1) || r.int() !== 0x8fd4c4d8) continue;
    try {
      var dflags = r.int();
      var d = { id: r.long(), hash: r.long(), ref: r.bytes().slice() };
      r.int();
      d.mime = r.string();
      d.size = Number(r.long());
      if (dflags & 1) tgSkipPhotoSizes(r);
      if (dflags & 2) throw new Error("video thumbs");
      d.dc = r.int();
      if (d.dc >= 1 && d.dc <= 5 && d.size > 0) return d;
    } catch (e) {
      // not it after all
    }
  }
  return null;
};

function tgSkipPhotoSizes(r) {
  if (r.int() !== 0x1cb5c415) throw new Error("vector");
  var n = r.int();
  for (var i = 0; i < n; i++) {
    var c = r.int();
    r.string();
    if (c === 0xe17e23c) continue;
    if (c === 0xe0b0bc2e || c === 0xd8214d41) { r.bytes(); continue; }
    r.int(); r.int();
    if (c === 0x75c78e60) r.int();
    else if (c === 0x21e1ad6) r.bytes();
    else if (c === 0xfa3efb95) { r.int(); var k = r.int(); for (var j = 0; j < k; j++) r.int(); }
    else throw new Error("photo size");
  }
}

// A document's bytes, part by part (512 KB each), from the data centre that keeps it.
TgClient.prototype.download = function (d, max) {
  if (d.size > max) throw new Error("too big");
  if (d.dc !== this.opts.home && !this.keys[d.dc]) this.authorizeOn(d.dc);
  var limit = 512 * 1024;
  var parts = [];
  for (var offset = 0; offset < d.size; offset += limit) {
    var m = new TgW().int(0xbe5335be).int(0).int(0xbad07584).long(d.id).long(d.hash).bytes(d.ref).string("").long(offset).int(limit).done();
    var res;
    try {
      res = this.call(d.dc, m);
    } catch (e) {
      if (e.code === 401 && d.dc !== this.opts.home) {
        this.authorizeOn(d.dc);
        res = this.call(d.dc, m);
      } else {
        throw e;
      }
    }
    var r = new TgR(res);
    if (r.int() !== 0x096a18d5) throw new Error("upload.file expected");
    r.int();
    r.int();
    parts.push(r.bytes().slice());
  }
  return tgConcat.apply(null, parts).subarray(0, d.size);
};


// Gereh T71: AES-256 in plain JavaScript, one block at a time, for Google Apps Script (which has
// hashes but no AES). FIPS-197, byte by byte; enough for the small files this carries.

var TG_AES = (function () {
  var S = new Uint8Array(256), SI = new Uint8Array(256);
  var p = 1, q = 1;
  do {
    p = (p ^ (p << 1) ^ (p & 0x80 ? 0x1b : 0)) & 0xff;
    q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 0xff;
    if (q & 0x80) q ^= 0x09;
    var r = function (x, s) { return ((x << s) | (x >> (8 - s))) & 0xff; };
    var x = q ^ r(q, 1) ^ r(q, 2) ^ r(q, 3) ^ r(q, 4) ^ 0x63;
    S[p] = x;
    SI[x] = p;
  } while (p !== 1);
  S[0] = 0x63;
  SI[0x63] = 0;

  function xt(a) { return ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 0xff; }
  function mul(a, b) {
    var out = 0;
    while (b) { if (b & 1) out ^= a; a = xt(a); b >>= 1; }
    return out;
  }

  function expand(key) {
    var w = new Uint8Array(240);
    w.set(key);
    var rcon = 1;
    for (var i = 32; i < 240; i += 4) {
      var t0 = w[i - 4], t1 = w[i - 3], t2 = w[i - 2], t3 = w[i - 1];
      if (i % 32 === 0) {
        var tmp = t0;
        t0 = S[t1] ^ rcon; t1 = S[t2]; t2 = S[t3]; t3 = S[tmp];
        rcon = xt(rcon);
      } else if (i % 32 === 16) {
        t0 = S[t0]; t1 = S[t1]; t2 = S[t2]; t3 = S[t3];
      }
      w[i] = w[i - 32] ^ t0; w[i + 1] = w[i - 31] ^ t1; w[i + 2] = w[i - 30] ^ t2; w[i + 3] = w[i - 29] ^ t3;
    }
    return w;
  }

  function add(s, w, round) {
    for (var i = 0; i < 16; i++) s[i] ^= w[round * 16 + i];
  }

  function encrypt(w, input) {
    var s = new Uint8Array(input);
    add(s, w, 0);
    for (var round = 1; round <= 14; round++) {
      for (var i = 0; i < 16; i++) s[i] = S[s[i]];
      var t = s[1]; s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = t;
      t = s[2]; s[2] = s[10]; s[10] = t; t = s[6]; s[6] = s[14]; s[14] = t;
      t = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t;
      if (round < 14) {
        for (var c = 0; c < 16; c += 4) {
          var a0 = s[c], a1 = s[c + 1], a2 = s[c + 2], a3 = s[c + 3], all = a0 ^ a1 ^ a2 ^ a3;
          s[c] ^= all ^ xt(a0 ^ a1);
          s[c + 1] ^= all ^ xt(a1 ^ a2);
          s[c + 2] ^= all ^ xt(a2 ^ a3);
          s[c + 3] ^= all ^ xt(a3 ^ a0);
        }
      }
      add(s, w, round);
    }
    return s;
  }

  function decrypt(w, input) {
    var s = new Uint8Array(input);
    add(s, w, 14);
    for (var round = 13; round >= 0; round--) {
      var t = s[13]; s[13] = s[9]; s[9] = s[5]; s[5] = s[1]; s[1] = t;
      t = s[2]; s[2] = s[10]; s[10] = t; t = s[6]; s[6] = s[14]; s[14] = t;
      t = s[3]; s[3] = s[7]; s[7] = s[11]; s[11] = s[15]; s[15] = t;
      for (var i = 0; i < 16; i++) s[i] = SI[s[i]];
      add(s, w, round);
      if (round > 0) {
        for (var c = 0; c < 16; c += 4) {
          var a0 = s[c], a1 = s[c + 1], a2 = s[c + 2], a3 = s[c + 3];
          s[c] = mul(a0, 14) ^ mul(a1, 11) ^ mul(a2, 13) ^ mul(a3, 9);
          s[c + 1] = mul(a0, 9) ^ mul(a1, 14) ^ mul(a2, 11) ^ mul(a3, 13);
          s[c + 2] = mul(a0, 13) ^ mul(a1, 9) ^ mul(a2, 14) ^ mul(a3, 11);
          s[c + 3] = mul(a0, 11) ^ mul(a1, 13) ^ mul(a2, 9) ^ mul(a3, 14);
        }
      }
    }
    return s;
  }

  return function (key) {
    var w = expand(key);
    return {
      enc: function (b) { return encrypt(w, b); },
      dec: function (b) { return decrypt(w, b); },
    };
  };
})();


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
