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

if (typeof module !== "undefined") {
  module.exports = { TgClient: TgClient, tgHex: tgHex, tgUnhex: tgUnhex, tgIge: tgIge, TgW: TgW, TgR: TgR };
}
