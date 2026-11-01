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

if (typeof module !== "undefined") {
  module.exports = { TG_AES: TG_AES };
}
