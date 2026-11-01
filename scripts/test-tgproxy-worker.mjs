#!/usr/bin/env node
/**
 * Runs the Telegram WEB proxy worker (app/src/main/assets/tgproxy_worker.js) for real, in Node.
 *
 *   node scripts/test-tgproxy-worker.mjs          the whole suite, offline
 *   node scripts/test-tgproxy-worker.mjs --live   also dials Telegram's real data centres
 *
 * The worker is imported as it ships, with two changes made to a temporary copy: the
 * `cloudflare:sockets` import becomes a swappable `connect`, and the private helpers are exported.
 * Everything else -- the capability, the tokens, the frames, the obfuscated2 re-keying, the flow
 * control, the bridge page -- runs unchanged.
 *
 * The other side of each connection is written independently, with Node's own crypto: a client
 * that speaks obfuscated2 exactly as Telegram does, and a data centre that opens the worker's
 * header the way Telegram's servers do. The bridge page is run in a small fake browser and driven
 * the way the Android app drives it.
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { Readable, Writable } from 'node:stream';

const LIVE = process.argv.includes('--live');
const ROOT = path.resolve(import.meta.dirname, '..');
const WORKER = path.join(ROOT, 'app/src/main/assets/tgproxy_worker.js');

const IMPORT = "import { connect } from 'cloudflare:sockets';";
const source = fs.readFileSync(WORKER, 'utf8');
assert.ok(source.includes(IMPORT), 'the worker still imports connect from cloudflare:sockets');
assert.ok(!/console\./.test(source), 'the worker never logs');
const patched =
  source.replace(IMPORT, 'const connect = (...a) => globalThis.__connect(...a);') +
  '\nexport { hexBytes, b64url, unb64url, getConfig, mintToken, checkToken, parseFrames, checkClientFrame, frame, serve, handle,' +
  ' ctrCipher, ctrApply, addCounter, sha256, BRIDGE_SCRIPT, DATA_CENTRES };\n';
const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'tgp-')), 'worker.mjs');
fs.writeFileSync(tmp, patched);
const W = await import(pathToFileURL(tmp).href);
const worker = W.default;

let passed = 0;
async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log('ok   ' + name);
  } catch (e) {
    console.log('FAIL ' + name + '\n     ' + (e && e.stack ? e.stack.split('\n').slice(0, 4).join('\n     ') : e));
    process.exitCode = 1;
  }
}

const tick = (ms = 5) => new Promise((r) => setTimeout(r, ms));
const text = (s) => new TextEncoder().encode(s);
const SECRET_HEX = '000102030405060708090a0b0c0d0e0f';
const SECRET = Buffer.from(SECRET_HEX, 'hex');
const HOST = 'proxy.example.com';
const sha = (b) => crypto.createHash('sha256').update(b).digest();

// ------------------------------------------------------------------------------ the other ends

/** A fake server-side WebSocket: records what the worker sends, lets a test deliver messages. */
function fakeSocket() {
  const listeners = {};
  const ws = {
    sent: [],
    closedWith: undefined,
    accept: () => {},
    addEventListener: (type, fn) => (listeners[type] = listeners[type] || []).push(fn),
    send: (data) => ws.sent.push(Uint8Array.from(data)),
    close: (code) => {
      if (ws.closedWith === undefined) ws.closedWith = code;
    },
    deliver: (data) => (listeners.message || []).forEach((fn) => fn({ data })),
    hangUp: () => (listeners.close || []).forEach((fn) => fn({})),
  };
  return ws;
}

/** Frames as the client sends them. */
const cframe = (type, id, payload = new Uint8Array()) => W.frame(type, id, payload);
const T = { OPEN: 1, DATA: 2, CLOSE: 3, WINDOW: 4, PONG: 6 };
const buf = (u8) => u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength);
const win = (n) => {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n);
  return b;
};

/** Frames the worker sent, grouped by type and stream. */
function received(ws) {
  const out = [];
  for (const m of ws.sent) for (const f of W.parseFrames(m)) out.push({ type: f.type, id: f.id, payload: Buffer.from(f.payload) });
  return out;
}

const FORBIDDEN = new Set([0x44414548, 0x54534f50, 0x20544547, 0x4954504f, 0xdddddddd, 0xeeeeeeee, 0x02010316]);

/** A Telegram client's obfuscated2 stream toward the proxy, built the way tgnet builds it. */
function telegramClient(secret16, dc, tag) {
  let init;
  do {
    init = crypto.randomBytes(64);
  } while (init[0] === 0xef || FORBIDDEN.has(init.readUInt32LE(0)) || init.readUInt32LE(4) === 0);
  init.writeUInt32BE(tag, 56);
  init.writeInt16LE(dc, 60);
  const upKey = sha(Buffer.concat([init.subarray(8, 40), secret16]));
  const rev = Buffer.from(init.subarray(8, 56)).reverse();
  const downKey = sha(Buffer.concat([rev.subarray(0, 32), secret16]));
  const enc = crypto.createCipheriv('aes-256-ctr', upKey, init.subarray(40, 56));
  const sealed = enc.update(init);
  const dec = crypto.createDecipheriv('aes-256-ctr', downKey, rev.subarray(32, 48));
  return {
    wire: Buffer.concat([init.subarray(0, 56), sealed.subarray(56, 64)]),
    seal: (data) => enc.update(data),
    open: (data) => dec.update(data),
  };
}

/** A fake data centre: opens the worker's header like Telegram's servers, answers with its own keys. */
function fakeDataCentre() {
  const dc = { received: Buffer.alloc(0), plain: Buffer.alloc(0), tag: null, id: null, closed: false, dials: [] };
  let push;
  let end;
  let recv;
  let send;
  let head = Buffer.alloc(0);
  const tcp = {
    opened: Promise.resolve(),
    closed: false,
    writable: new WritableStream({
      write(chunk) {
        const c = Buffer.from(chunk);
        dc.received = Buffer.concat([dc.received, c]);
        if (!recv) {
          head = Buffer.concat([head, c]);
          if (head.length < 64) return;
          const init = head.subarray(0, 64);
          recv = crypto.createDecipheriv('aes-256-ctr', init.subarray(8, 40), init.subarray(40, 56));
          const opened = recv.update(init);
          dc.tag = opened.readUInt32BE(56);
          dc.id = opened.readInt16LE(60);
          const rev = Buffer.from(init.subarray(8, 56)).reverse();
          send = crypto.createCipheriv('aes-256-ctr', rev.subarray(0, 32), rev.subarray(32, 48));
          dc.plain = Buffer.concat([dc.plain, recv.update(head.subarray(64))]);
        } else {
          dc.plain = Buffer.concat([dc.plain, recv.update(c)]);
        }
      },
    }),
    readable: new ReadableStream({
      start(c) {
        push = (d) => c.enqueue(d);
        end = () => c.close();
      },
    }),
    close: () => {
      tcp.closed = true;
      dc.closed = true;
    },
  };
  dc.tcp = tcp;
  dc.reply = (data) => push(Uint8Array.from(send.update(data)));
  dc.hangUp = () => end();
  return dc;
}

function withDial(dcs, fn) {
  const previous = globalThis.__connect;
  let n = 0;
  globalThis.__connect = (to) => {
    const d = dcs[Math.min(n++, dcs.length - 1)];
    d.dials.push(to);
    return d.tcp;
  };
  return Promise.resolve(fn()).finally(() => (globalThis.__connect = previous));
}

async function config(secretHex = SECRET_HEX) {
  return W.getConfig({ PROXY_SECRET: secretHex });
}

// ------------------------------------------------------------------------------ the spec's vectors

await test('bridge capability matches the published vectors', async () => {
  for (const [secret, expected] of [
    ['000102030405060708090a0b0c0d0e0f', 'MHLEY5PmW1GWqJkSrlmJpvJUiLhBH_QKy6yKg8a0JPk'],
    ['dd000102030405060708090a0b0c0d0e0f', 'IpJrt3e7sKtzPyoXy6w-Zj6GGEvsvclN66JzQEfPYLA'],
  ]) {
    const mac = crypto.createHmac('sha256', Buffer.from(secret, 'hex')).update('tdesktop-web-proxy-bridge-v1\nproxy.example.com').digest();
    assert.equal(mac.toString('base64url'), expected, 'the test itself agrees with the vector');
    const env = { PROXY_SECRET: secret, MLM_TGP_ID: 'x' };
    const res = await worker.fetch(new Request('https://proxy.example.com/?bridge=' + expected), env);
    assert.equal(res.status, 200, 'the worker accepts the vector: ' + secret);
  }
});

await test('the secret is parsed strictly: plain, dd, never ee or wrong length', async () => {
  assert.ok(await config('AB'.repeat(16)), 'upper case hex is fine');
  assert.ok(await config('dd' + 'ab'.repeat(16)));
  assert.equal(await W.getConfig({ PROXY_SECRET: 'ee' + 'ab'.repeat(16) + '6578616d706c652e636f6d' }), null, 'fake-TLS is refused');
  assert.equal(await W.getConfig({ PROXY_SECRET: 'ab'.repeat(15) }), null);
  assert.equal(await W.getConfig({ PROXY_SECRET: 'zz'.repeat(16) }), null);
  assert.equal(await W.getConfig({}), null);
  assert.equal((await worker.fetch(new Request('https://x.example/'), {})).status, 404, 'unconfigured: plain 404');
});

// ------------------------------------------------------------------------------ tokens

await test('tokens: round trip, kind, expiry, tampering', async () => {
  const cfg = await config();
  const t = await W.mintToken(cfg, 1, 1000);
  assert.match(t, /^[A-Za-z0-9_-]{43}$/);
  assert.ok(await W.checkToken(cfg, t, 1, 120, 1000));
  assert.ok(await W.checkToken(cfg, t, 1, 120, 1119));
  assert.ok(!(await W.checkToken(cfg, t, 1, 120, 1121)), 'expired');
  assert.ok(!(await W.checkToken(cfg, t, 1, 120, 900)), 'from the future');
  assert.ok(!(await W.checkToken(cfg, t, 2, 120, 1000)), 'wrong kind');
  const flipped = t.slice(0, 20) + (t[20] === 'A' ? 'B' : 'A') + t.slice(21);
  assert.ok(!(await W.checkToken(cfg, flipped, 1, 120, 1000)), 'tampered');
  assert.ok(!(await W.checkToken(cfg, t.slice(1), 1, 120, 1000)), 'short');
  assert.ok(!(await W.checkToken(cfg, 'not a token', 1, 120, 1000)));
  const other = await config('ff'.repeat(16));
  assert.ok(!(await W.checkToken(other, t, 1, 120, 1000)), 'another secret does not accept it');
});

// ------------------------------------------------------------------------------ frames

await test('frames: parse, limits, client shapes', () => {
  const batch = Buffer.concat([cframe(T.OPEN, 1), cframe(T.DATA, 1, text('hi')), cframe(T.CLOSE, 1)]);
  const fs = W.parseFrames(new Uint8Array(batch));
  assert.deepEqual(fs.map((f) => [f.type, f.id, f.payload.length]), [[1, 1, 0], [2, 1, 2], [3, 1, 0]]);
  assert.throws(() => W.parseFrames(new Uint8Array(0)), /empty/);
  assert.throws(() => W.parseFrames(new Uint8Array(7)));
  const lie = Buffer.from(cframe(T.DATA, 1, text('abc')));
  lie.writeUInt32BE(99, 4);
  assert.throws(() => W.parseFrames(new Uint8Array(lie)), /frame/);
  const big = Buffer.alloc(8);
  big[0] = 2;
  big[3] = 1;
  big.writeUInt32BE(1024 * 1024 + 1, 4);
  assert.throws(() => W.parseFrames(new Uint8Array(big)), /frame/);
  assert.throws(() => W.parseFrames(new Uint8Array(Buffer.concat(Array.from({ length: 4097 }, () => cframe(T.CLOSE, 1))))), /batch/);
  for (const bad of [cframe(T.DATA, 1), cframe(T.OPEN, 1, text('x')), cframe(T.WINDOW, 1, win(0)), cframe(T.WINDOW, 1, text('abc')), cframe(0x10, 0), cframe(T.OPEN, 0), cframe(7, 1)]) {
    assert.throws(() => W.checkClientFrame(W.parseFrames(new Uint8Array(bad))[0]), undefined, 'rejects ' + Buffer.from(bad).toString('hex'));
  }
  W.checkClientFrame(W.parseFrames(new Uint8Array(cframe(T.PONG, 0, text('echo'))))[0]);
  W.checkClientFrame(W.parseFrames(new Uint8Array(cframe(T.WINDOW, 3, win(5))))[0]);
});

// ------------------------------------------------------------------------------ AES-CTR at any offset

await test('AES-CTR at arbitrary offsets equals a continuous stream', async () => {
  const key = crypto.randomBytes(32);
  const iv = crypto.randomBytes(16);
  const data = crypto.randomBytes(5000);
  const ref = crypto.createCipheriv('aes-256-ctr', key, iv).update(data);
  const cipher = await W.ctrCipher(new Uint8Array(key), new Uint8Array(iv));
  let at = 0;
  const out = [];
  for (const n of [1, 15, 16, 17, 31, 64, 1000, 3, 2000, 1853]) {
    out.push(Buffer.from(await W.ctrApply(cipher, at, new Uint8Array(data.subarray(at, at + n)))));
    at += n;
  }
  assert.equal(at, 5000);
  assert.ok(Buffer.concat(out).equals(ref));
});

await test('the counter carries across bytes like a 128-bit big-endian number', () => {
  const iv = new Uint8Array(16).fill(255);
  assert.deepEqual([...W.addCounter(iv, 1)], new Array(16).fill(0), 'wraps the whole block');
  const low = new Uint8Array(16);
  low[15] = 255;
  assert.equal(W.addCounter(low, 1)[14], 1);
  assert.equal(W.addCounter(low, 1)[15], 0);
  assert.equal(W.addCounter(new Uint8Array(16), 2 ** 40)[10], 1);
});

// ------------------------------------------------------------------------------ one stream, end to end

async function openStream({ tag = 0xeeeeeeee, dc = 2, secretHex = SECRET_HEX, splits = [64] } = {}) {
  const cfg = await config(secretHex);
  const ws = fakeSocket();
  const dcs = [fakeDataCentre()];
  const client = telegramClient(Buffer.from(secretHex.replace(/^dd/, ''), 'hex'), dc, tag);
  W.serve(ws, cfg, { maxDials: 45 }, (to) => globalThis.__connect(to));
  return { ws, dcs, client, cfg };
}

await test('a stream: header re-keyed, uploads re-encrypted, downloads pass through untouched', async () => {
  const dcs = [fakeDataCentre()];
  await withDial(dcs, async () => {
    const { ws, client } = await openStream({ dc: 2, tag: 0xeeeeeeee });
    ws.deliver(buf(cframe(T.OPEN, 7)));
    // the 64-byte header arrives in two pieces, the first message follows in the same DATA
    const first = client.seal(text('hello data centre'));
    const wire = Buffer.concat([client.wire, first]);
    ws.deliver(buf(Buffer.concat([cframe(T.DATA, 7, wire.subarray(0, 20)), cframe(T.DATA, 7, wire.subarray(20, 90))])));
    await tick(40);
    assert.deepEqual(dcs[0].dials, [{ hostname: W.DATA_CENTRES[2][0], port: 443 }]);
    assert.equal(dcs[0].id, 2);
    assert.equal(dcs[0].tag, 0xeeeeeeee);
    assert.equal(dcs[0].plain.toString(), 'hello data centre', 'the data centre reads the client\'s plaintext');
    assert.ok(!dcs[0].received.subarray(64).equals(first), 'and what it was sent is not the client\'s ciphertext');

    // more uploads in odd sizes
    const parts = ['a', 'bc', 'x'.repeat(15), 'y'.repeat(33), 'z'.repeat(4000)];
    for (const p of parts) ws.deliver(buf(cframe(T.DATA, 7, client.seal(text(p)))));
    await tick(60);
    assert.equal(dcs[0].plain.toString(), 'hello data centre' + parts.join(''));

    // the credit for everything uploaded comes back as WINDOW
    const granted = received(ws).filter((f) => f.type === T.WINDOW && f.id === 7).reduce((n, f) => n + f.payload.readUInt32BE(0), 0);
    assert.equal(granted, wire.length + parts.join('').length);

    // downloads: the data centre's bytes reach the client as they are and decrypt with the client's key
    const reply = crypto.randomBytes(150000);
    dcs[0].reply(reply);
    await tick(60);
    const data = received(ws).filter((f) => f.type === T.DATA && f.id === 7);
    assert.ok(data.every((f) => f.payload.length <= 65536), 'DATA chunks stay within 64 KiB');
    assert.ok(client.open(Buffer.concat(data.map((f) => f.payload))).equals(reply), 'the client decrypts the data centre\'s reply');
  });
});

await test('padded (dd) secret: the data centre gets the dd tag, a plain tag is refused', async () => {
  const dcs = [fakeDataCentre()];
  await withDial(dcs, async () => {
    const dd = 'dd' + SECRET_HEX;
    const good = await openStream({ secretHex: dd, tag: 0xdddddddd });
    good.ws.deliver(buf(cframe(T.OPEN, 1)));
    good.ws.deliver(buf(cframe(T.DATA, 1, Buffer.concat([good.client.wire, good.client.seal(text('x'))]))));
    await tick(40);
    assert.equal(dcs[0].tag, 0xdddddddd);

    const bad = await openStream({ secretHex: dd, tag: 0xeeeeeeee });
    bad.ws.deliver(buf(cframe(T.OPEN, 1)));
    bad.ws.deliver(buf(cframe(T.DATA, 1, bad.client.wire)));
    await tick(40);
    assert.ok(received(bad.ws).some((f) => f.type === T.CLOSE && f.id === 1), 'that stream is closed');
    assert.equal(dcs[0].dials.length, 1, 'and no data centre is dialled for it');
  });
});

await test('every transport tag and the media data-centre ids are carried', async () => {
  for (const [tag, dc] of [[0xefefefef, 1], [0xeeeeeeee, 3], [0xdddddddd, 4], [0xeeeeeeee, -5], [0xefefefef, -2]]) {
    const dcs = [fakeDataCentre()];
    await withDial(dcs, async () => {
      const { ws, client } = await openStream({ tag, dc });
      ws.deliver(buf(cframe(T.OPEN, 1)));
      ws.deliver(buf(cframe(T.DATA, 1, Buffer.concat([client.wire, client.seal(text('ping'))]))));
      await tick(40);
      assert.equal(dcs[0].tag, tag);
      assert.equal(dcs[0].id, dc, 'the id is passed on as the client wrote it');
      assert.equal(dcs[0].dials[0].hostname, W.DATA_CENTRES[Math.abs(dc)][0]);
      assert.equal(dcs[0].plain.toString(), 'ping');
    });
  }
});

await test('a wrong secret or garbage never reaches a data centre', async () => {
  const dcs = [fakeDataCentre()];
  await withDial(dcs, async () => {
    const cfg = await config();
    const ws = fakeSocket();
    W.serve(ws, cfg, { maxDials: 45 }, (to) => globalThis.__connect(to));
    const stranger = telegramClient(crypto.randomBytes(16), 2, 0xeeeeeeee);
    ws.deliver(buf(cframe(T.OPEN, 1)));
    ws.deliver(buf(cframe(T.DATA, 1, Buffer.concat([stranger.wire, stranger.seal(text('hi'))]))));
    ws.deliver(buf(cframe(T.OPEN, 2)));
    ws.deliver(buf(cframe(T.DATA, 2, crypto.randomBytes(80))));
    await tick(40);
    assert.equal(dcs[0].dials.length, 0);
    const closed = received(ws).filter((f) => f.type === T.CLOSE).map((f) => f.id).sort();
    assert.deepEqual(closed, [1, 2]);
    assert.equal(ws.closedWith, undefined, 'only the streams end, not the session');
  });
});

await test('flow control: nothing past the client\'s credit, resumes on WINDOW', async () => {
  const dcs = [fakeDataCentre()];
  await withDial(dcs, async () => {
    const { ws, client } = await openStream();
    ws.deliver(buf(cframe(T.OPEN, 1)));
    ws.deliver(buf(cframe(T.DATA, 1, Buffer.concat([client.wire, client.seal(text('go'))]))));
    await tick(40);
    const total = (id) => received(ws).filter((f) => f.type === T.DATA && f.id === id).reduce((n, f) => n + f.payload.length, 0);
    const MiB = 1024 * 1024;
    for (let i = 0; i < 5; i++) dcs[0].reply(crypto.randomBytes(MiB));
    await tick(300);
    assert.equal(total(1), 4 * MiB, 'stops at the 4 MiB the client granted');
    ws.deliver(buf(cframe(T.WINDOW, 1, win(MiB))));
    await tick(200);
    assert.equal(total(1), 5 * MiB, 'the rest follows the new credit');
    assert.equal(total(1) % 1, 0);
  });
});

await test('CLOSE from the client ends the data-centre side; the data centre hanging up ends the stream', async () => {
  const a = fakeDataCentre();
  const b = fakeDataCentre();
  await withDial([a, b], async () => {
    const { ws, client } = await openStream();
    ws.deliver(buf(cframe(T.OPEN, 1)));
    ws.deliver(buf(cframe(T.DATA, 1, Buffer.concat([client.wire, client.seal(text('a'))]))));
    const second = telegramClient(SECRET, 2, 0xeeeeeeee);
    ws.deliver(buf(cframe(T.OPEN, 2)));
    ws.deliver(buf(cframe(T.DATA, 2, Buffer.concat([second.wire, second.seal(text('b'))]))));
    await tick(40);
    ws.deliver(buf(cframe(T.CLOSE, 1)));
    await tick(30);
    assert.ok(a.closed, 'the first connection is closed');
    assert.ok(!received(ws).some((f) => f.type === T.CLOSE && f.id === 1), 'no echo of a close the client asked for');
    b.hangUp();
    await tick(30);
    assert.ok(received(ws).some((f) => f.type === T.CLOSE && f.id === 2), 'the client is told the data centre closed');
    ws.deliver(buf(cframe(T.DATA, 1, text('late'))));
    await tick(10);
    assert.equal(ws.closedWith, undefined, 'a late frame for a closed stream is ignored, not fatal');
  });
});

await test('protocol violations end the session with a close code', async () => {
  const cfg = await config();
  const cases = {
    'text message': () => 'hello',
    'empty message': () => new ArrayBuffer(0),
    'truncated frame': () => buf(cframe(T.OPEN, 1).subarray(0, 5)),
    'DATA with no payload': () => buf(cframe(T.DATA, 1)),
    'stream 0 that is not PONG': () => buf(cframe(T.OPEN, 0)),
    'frame for a stream never opened': () => buf(cframe(T.DATA, 9, text('x'))),
    'unknown type': () => buf(cframe(0x30, 1)),
  };
  for (const [name, make] of Object.entries(cases)) {
    const ws = fakeSocket();
    W.serve(ws, cfg, { maxDials: 45 }, () => assert.fail('no dial'));
    ws.deliver(make());
    assert.equal(ws.closedWith, 4001, name);
  }
  const reuse = fakeSocket();
  W.serve(reuse, cfg, { maxDials: 45 }, () => fakeDataCentre().tcp);
  reuse.deliver(buf(cframe(T.OPEN, 3)));
  reuse.deliver(buf(cframe(T.CLOSE, 3)));
  reuse.deliver(buf(cframe(T.OPEN, 3)));
  assert.equal(reuse.closedWith, 4001, 'a stream id is never reused');
  const pong = fakeSocket();
  W.serve(pong, cfg, { maxDials: 45 }, () => assert.fail('no dial'));
  pong.deliver(buf(cframe(T.PONG, 0, text('echo'))));
  assert.equal(pong.closedWith, undefined, 'PONG is fine');
});

await test('past its dial budget a socket refuses the stream, not the session; extra streams too', async () => {
  const cfg = await config();
  const ws = fakeSocket();
  W.serve(ws, cfg, { maxDials: 4 }, () => fakeDataCentre().tcp);
  for (let id = 1; id <= 4; id++) ws.deliver(buf(cframe(T.OPEN, id)));
  assert.equal(received(ws).filter((f) => f.type === T.CLOSE).length, 0);
  ws.deliver(buf(cframe(T.OPEN, 5)));
  assert.equal(ws.closedWith, undefined, 'the session and its four streams stay up');
  assert.deepEqual(received(ws).filter((f) => f.type === T.CLOSE).map((f) => f.id), [5], 'only the fifth stream is refused');
  ws.deliver(buf(cframe(T.DATA, 5, text('late'))));
  assert.equal(ws.closedWith, undefined, 'a late frame for the refused stream is ignored');

  const many = fakeSocket();
  W.serve(many, cfg, { maxDials: 1000 }, () => fakeDataCentre().tcp);
  for (let id = 1; id <= 60; id++) many.deliver(buf(cframe(T.OPEN, id)));
  const refused = received(many).filter((f) => f.type === T.CLOSE).length;
  assert.equal(refused, 60 - 48, 'no more than 48 streams at once');
  assert.equal(many.closedWith, undefined);
});

await test('a socket pings with a fresh session token, and stops when it closes', async () => {
  const cfg = await config();
  const ws = fakeSocket();
  W.serve(ws, cfg, { maxDials: 45, pingMs: 15 }, () => assert.fail('no dial'));
  await tick(50);
  const pings = received(ws).filter((f) => f.type === 5 && f.id === 0);
  assert.ok(pings.length >= 2, 'pinged more than once');
  for (const p of pings) {
    const token = p.payload.toString();
    assert.match(token, /^[A-Za-z0-9_-]{43}$/);
    assert.ok(await W.checkToken(cfg, token, 2, 600), 'the ping token is a valid session token');
  }
  ws.deliver(buf(cframe(T.PONG, 0, pings[0].payload)));
  assert.equal(ws.closedWith, undefined, 'the page answering is fine');
  ws.hangUp();
  const after = ws.sent.length;
  await tick(50);
  assert.equal(ws.sent.length, after, 'no pings after the socket closed');
});

await test('hanging up the socket closes every data-centre connection', async () => {
  const a = fakeDataCentre();
  await withDial([a], async () => {
    const { ws, client } = await openStream();
    ws.deliver(buf(cframe(T.OPEN, 1)));
    ws.deliver(buf(cframe(T.DATA, 1, Buffer.concat([client.wire, client.seal(text('a'))]))));
    await tick(40);
    ws.hangUp();
    await tick(20);
    assert.ok(a.closed);
  });
});

// ------------------------------------------------------------------------------ http

const env = { PROXY_SECRET: SECRET_HEX, MLM_TGP_ID: 'x' };
const CAP = crypto.createHmac('sha256', SECRET).update('tdesktop-web-proxy-bridge-v1\n' + HOST).digest('base64url');
const get = (url, init) => worker.fetch(new Request(url, init), env);

await test('the bridge page: right headers, nonce, bootstrap, no secret', async () => {
  const res = await get(`https://${HOST}/?bridge=${CAP}`);
  assert.equal(res.status, 200);
  const html = await res.text();
  const csp = res.headers.get('Content-Security-Policy');
  const nonce = /script nonce="([^"]+)"/.exec(html)[1];
  assert.ok(csp.includes(`script-src 'nonce-${nonce}'`));
  assert.ok(csp.includes(`connect-src 'self' wss://${HOST}`));
  assert.ok(csp.includes("default-src 'none'") && csp.includes('sandbox allow-same-origin allow-scripts'));
  assert.equal(res.headers.get('Cache-Control'), 'no-store');
  assert.equal(res.headers.get('Referrer-Policy'), 'no-referrer');
  assert.equal(res.headers.get('X-Content-Type-Options'), 'nosniff');
  assert.ok(res.headers.get('Permissions-Policy').includes('camera=()'));
  assert.ok(!res.headers.has('X-Frame-Options'));
  assert.match(html, /const BASE="https:\/\/proxy\.example\.com\/",BOOT="[A-Za-z0-9_-]{43}",DIALS=45;/);
  assert.ok(!html.includes(SECRET_HEX) && !html.includes(CAP), 'neither the secret nor the capability is echoed');
  assert.ok(!html.includes('__BASE__') && !html.includes('__BOOT__') && !html.includes('__DIALS__'));
  const custom = await worker.fetch(new Request(`https://${HOST}/?bridge=${CAP}`), { ...env, MAX_DIALS: '200' });
  assert.match(await custom.text(), /,DIALS=200;/, 'the page knows the socket\'s dial budget');
});

await test('everything else is a plain 404 and shows nothing', async () => {
  const wrong = CAP.slice(0, 42) + (CAP[42] === 'A' ? 'B' : 'A');
  const attempts = [
    [`https://${HOST}/`], [`https://${HOST}/?bridge=${wrong}`], [`https://${HOST}/?bridge=short`], [`https://${HOST}/?bridge=${CAP}&x=1`],
    [`https://${HOST}/index.html`], [`https://${HOST}/api/v1/ws`], [`https://${HOST}/api/v1/session`],
    [`https://other.example.com/?bridge=${CAP}`, undefined], [`https://${HOST}/?bridge=${CAP}`, { method: 'POST' }],
  ];
  for (const [url, init] of attempts) {
    const res = await get(url, init);
    assert.equal(res.status, 404, url);
    const body = await res.text();
    assert.ok(!body.includes(SECRET_HEX) && !body.includes(CAP) && !body.toLowerCase().includes('secret'));
  }
});

async function bootstrapOf(host = HOST) {
  const cap = crypto.createHmac('sha256', SECRET).update('tdesktop-web-proxy-bridge-v1\n' + host).digest('base64url');
  const html = await (await get(`https://${host}/?bridge=${cap}`)).text();
  return /BOOT="([^"]+)"/.exec(html)[1];
}
const HELLO = Buffer.from([0x10, 0, 0, 0, 0, 0, 0, 1, 1]);
const session = (token, body = HELLO) => get(`https://${HOST}/api/v1/session`, { method: 'POST', body, headers: token ? { Authorization: 'Bearer ' + token, 'Content-Type': 'application/octet-stream' } : {} });

await test('a session: HELLO in, WELCOME and a session token out', async () => {
  const boot = await bootstrapOf();
  const res = await session(boot);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('X-Carrier-Mode'), 'websocket');
  assert.equal(res.headers.get('X-Down-Cursor'), '0');
  assert.match(res.headers.get('X-Session-Token'), /^[A-Za-z0-9_-]{43}$/);
  assert.deepEqual([...Buffer.from(await res.arrayBuffer())], [0x11, 0, 0, 0, 0, 0, 0, 0]);
  assert.equal((await session(undefined)).status, 404, 'no token');
  assert.equal((await session('A'.repeat(43))).status, 404, 'a made-up token');
  assert.equal((await session(boot, Buffer.from([0x10, 0, 0, 0, 0, 0, 0, 1, 2]))).status, 404, 'a wrong HELLO');
  assert.equal((await session(boot, Buffer.alloc(200))).status, 404, 'an oversized body');
  const sessionToken = res.headers.get('X-Session-Token');
  const renewed = await session(sessionToken);
  assert.equal(renewed.status, 200, 'a session token opens the page\'s next session');
  assert.notEqual(renewed.headers.get('X-Session-Token'), sessionToken);
  const cfg = await config();
  const stale = await W.mintToken(cfg, 2, Math.floor(Date.now() / 1000) - 3700);
  assert.equal((await session(stale)).status, 404, 'an hour-old session token does not');
  const oldBoot = await W.mintToken(cfg, 1, Math.floor(Date.now() / 1000) - 130);
  assert.equal((await session(oldBoot)).status, 404, 'nor does a bootstrap past its two minutes');
  const del = await get(`https://${HOST}/api/v1/session`, { method: 'DELETE', headers: { Authorization: 'Bearer ' + sessionToken } });
  assert.equal(del.status, 204);
});

await test('the WebSocket upgrade needs a session token in the subprotocol', async () => {
  const pairs = [];
  globalThis.WebSocketPair = class {
    constructor() {
      this[0] = fakeSocket();
      this[1] = fakeSocket();
      pairs.push(this);
    }
  };
  const RealResponse = globalThis.Response;
  globalThis.Response = class extends RealResponse {
    constructor(body, init = {}) {
      if (init.status === 101) {
        super(null, { ...init, status: 200 });
        this.upgraded = init.webSocket;
        this.fake101 = true;
      } else super(body, init);
    }
    get status() {
      return this.fake101 ? 101 : super.status;
    }
  };
  try {
    const boot = await bootstrapOf();
    const token = (await session(boot)).headers.get('X-Session-Token');
    const up = (proto) => get(`https://${HOST}/api/v1/ws`, { headers: { Upgrade: 'websocket', Connection: 'Upgrade', ...(proto ? { 'Sec-WebSocket-Protocol': proto } : {}) } });
    const ok = await up('tproxy-v1.' + token);
    assert.equal(ok.status, 101);
    assert.equal(ok.headers.get('Sec-WebSocket-Protocol'), 'tproxy-v1.' + token);
    assert.equal((await up('tproxy-v1.' + boot)).status, 404, 'a bootstrap token cannot open the socket');
    assert.equal((await up('tproxy-v1.' + 'A'.repeat(43))).status, 404);
    assert.equal((await up('tproxy-lane-v1.' + token + '.3')).status, 404, 'lane mode is not offered');
    assert.equal((await up(undefined)).status, 404);
    assert.equal((await get(`https://${HOST}/api/v1/ws`)).status, 404, 'a plain GET');
  } finally {
    globalThis.Response = RealResponse;
    pairs.forEach((p) => p[1].hangUp()); // stops the sockets' ping timers
  }
});

// ------------------------------------------------------------------------------ the bridge page, run

/**
 * Runs the bridge page's script in a small fake browser wired to the real worker, and plays the
 * Telegram Android app on the other side of `TelegramWebProxy`.
 */
function browser(dcs, { maxDials = 45 } = {}) {
  const messages = []; // what the page handed to the app
  const app = { onmessage: null, postMessage: (m) => messages.push(m) };
  const fakeWs = [];
  const RealResponse = globalThis.Response;
  const page = { requests: [] };

  class FakeWebSocket {
    constructor(url, protocol) {
      this.url = url;
      this.protocol = protocol;
      this.readyState = 0;
      this.bufferedAmount = 0;
      this.binaryType = 'blob';
      this.sent = [];
      fakeWs.push(this);
      this.pair = [fakeSocket(), fakeSocket()];
      const client = this.pair[0];
      const server = this.pair[1];
      // the server end gets the worker's real session logic, with the data centre faked
      const env2 = env;
      W.getConfig(env2).then((cfg) => {
        W.serve(server, cfg, { maxDials }, (to) => globalThis.__connect(to));
        // what the server sends reaches the page; what the page sends reaches the server
        server.send = (data) => queueMicrotask(() => this.readyState === 1 && this.onmessage && this.onmessage({ data: buf(Uint8Array.from(data)) }));
        // the server closing the socket is seen by the page, as in a browser
        const closeServer = server.close;
        server.close = (code) => {
          closeServer(code);
          if (this.readyState === 3) return;
          this.readyState = 3;
          queueMicrotask(() => this.onclose && this.onclose({ code }));
        };
        this.readyState = 1;
        queueMicrotask(() => this.onopen && this.onopen());
      });
    }
    send(body) {
      this.sent.push(body);
      this.pair[1].deliver(buf(Uint8Array.from(body)));
    }
    close() {
      if (this.readyState === 3) return;
      this.readyState = 3;
      this.pair[1].hangUp();
    }
  }

  const sandbox = {
    location: { hash: '#android=' + 'N'.repeat(43), pathname: '/' },
    history: { replaceState: (_s, _t, p) => (sandbox.location.hash = '', (sandbox.location.pathname = p)) },
    TelegramWebProxy: app,
    WebSocket: FakeWebSocket,
    fetch: async (url, init) => {
      page.requests.push({ url, init });
      return worker.fetch(new Request(url, init), env);
    },
    parent: {},
    addEventListener: () => {},
    setTimeout,
    clearTimeout,
    // the page's watchdog must not keep the test process alive
    setInterval: (fn, ms) => setInterval(fn, ms).unref(),
    clearInterval,
    queueMicrotask,
    JSON,
    ArrayBuffer,
    Uint8Array,
    DataView,
    Object,
    Error,
    URL,
    Promise,
    console: { log() {} },
  };
  sandbox.globalThis = sandbox;
  return { app, messages, sandbox, fakeWs, page, RealResponse };
}

async function runPage({ maxDials = 45 } = {}) {
  const dcs = [fakeDataCentre()];
  const b = browser(dcs, { maxDials });
  const boot = await bootstrapOf();
  const script = W.BRIDGE_SCRIPT.replace('__BASE__', () => JSON.stringify(`https://${HOST}/`))
    .replace('__BOOT__', () => JSON.stringify(boot))
    .replace('__DIALS__', () => String(maxDials));
  vm.createContext(b.sandbox);
  vm.runInContext(script, b.sandbox);
  return { ...b, dcs, boot };
}

const jsonOf = (m) => (typeof m === 'string' ? JSON.parse(m) : null);

await test('the bridge page speaks the Android boundary and carries a stream to a data centre', async () => {
  await withDial([fakeDataCentre()], async () => {
    const { app, messages, dcs } = await runPage();
    const dc = fakeDataCentre();
    globalThis.__connect = (to) => (dc.dials.push(to), dc.tcp);

    // 1. the page announces itself with the nonce it was loaded with
    const init = messages.map(jsonOf).find((m) => m && m.t === 'tproxy-android-init');
    assert.deepEqual(init, { t: 'tproxy-android-init', v: 1, nonce: 'N'.repeat(43) });
    assert.ok(messages.map(jsonOf).some((m) => m && m.t === 'status' && m.state === 'connecting'));

    // 2. the app sends HELLO as a single frame; the page answers with WELCOME and "connected"
    app.onmessage({ data: buf(HELLO) });
    await tick(80);
    const frames = messages.filter((m) => m instanceof ArrayBuffer);
    assert.deepEqual([...Buffer.from(frames[0])], [0x11, 0, 0, 0, 0, 0, 0, 0], 'WELCOME arrives as one frame');
    assert.ok(messages.map(jsonOf).some((m) => m && m.t === 'status' && m.state === 'connected'));

    // 3. a stream: OPEN, the obfuscated2 header and a first message as separate frames
    const client = telegramClient(SECRET, 4, 0xdddddddd);
    // dd is only valid for a dd secret; use the plain secret here with the intermediate tag instead
    const plainClient = telegramClient(SECRET, 4, 0xeeeeeeee);
    app.onmessage({ data: buf(cframe(T.OPEN, 1)) });
    app.onmessage({ data: buf(cframe(T.DATA, 1, plainClient.wire)) });
    app.onmessage({ data: buf(cframe(T.DATA, 1, plainClient.seal(text('through the page')))) });
    await tick(80);
    assert.equal(dc.dials[0].hostname, W.DATA_CENTRES[4][0]);
    assert.equal(dc.plain.toString(), 'through the page');
    void client;

    // 4. the reply comes back to the app frame by frame and decrypts with the client's key
    const reply = crypto.randomBytes(200000);
    dc.reply(reply);
    await tick(120);
    const data = messages.filter((m) => m instanceof ArrayBuffer).map((m) => W.parseFrames(new Uint8Array(m))).flat().filter((f) => f.type === T.DATA && f.id === 1);
    assert.ok(data.length >= 4, 'split into several frames');
    assert.ok(messages.filter((m) => m instanceof ArrayBuffer).every((m) => W.parseFrames(new Uint8Array(m)).length === 1), 'the app never gets a batch');
    assert.ok(plainClient.open(Buffer.concat(data.map((f) => Buffer.from(f.payload)))).equals(reply));
    const traffic = messages.map(jsonOf).filter((m) => m && m.t === 'traffic');
    assert.ok(traffic.some((m) => m.up > 0) && traffic.some((m) => m.down > 0));
  });
});

await test('the bridge page reports failure and tells the server when the session cannot start', async () => {
  const { app, messages, page } = await runPage();
  // a HELLO the server rejects: the page must fail closed, tell the app, and not hang
  app.onmessage({ data: buf(Buffer.from([0x10, 0, 0, 0, 0, 0, 0, 1, 9])) });
  await tick(60);
  const states = messages.map(jsonOf).filter(Boolean);
  assert.ok(states.some((m) => m.t === 'status' && m.state === 'failed'));
  assert.ok(states.some((m) => m.t === 'close'));
  assert.ok(page.requests.every((r) => r.init.credentials === 'omit' && r.init.mode === 'same-origin' && r.init.cache === 'no-store'));
});

/** A page that has said hello and is connected; every dial reaches a fresh fake data centre. */
async function connectedPage(opts) {
  const p = await runPage(opts);
  const dials = [];
  globalThis.__connect = (to) => {
    const d = fakeDataCentre();
    d.dials.push(to);
    dials.push(d);
    return d.tcp;
  };
  p.app.onmessage({ data: buf(HELLO) });
  await tick(60);
  assert.ok(p.messages.map(jsonOf).some((m) => m && m.t === 'status' && m.state === 'connected'));
  const appFrames = () => p.messages.filter((m) => m instanceof ArrayBuffer).map((m) => W.parseFrames(new Uint8Array(m))[0]);
  const failed = () => p.messages.map(jsonOf).some((m) => m && (m.t === 'close' || (m.t === 'status' && m.state === 'failed')));
  const opensOn = (ws) => ws.sent.flatMap((b) => W.parseFrames(Uint8Array.from(b))).filter((f) => f.type === T.OPEN).map((f) => f.id);
  /** Opens a stream the way tgnet does and sends one message on it. */
  const stream = (id, message) => {
    const c = telegramClient(SECRET, 2, 0xeeeeeeee);
    p.app.onmessage({ data: buf(cframe(T.OPEN, id)) });
    p.app.onmessage({ data: buf(cframe(T.DATA, id, Buffer.concat([c.wire, c.seal(text(message))]))) });
    return c;
  };
  return { ...p, dials, appFrames, failed, opensOn, stream };
}

await test('the page moves to a fresh socket before the dial budget runs out; Telegram never notices', async () => {
  const previous = globalThis.__connect;
  try {
    const p = await connectedPage({ maxDials: 6 }); // the page switches at 4 opens per socket
    const clients = [];
    for (let id = 1; id <= 12; id++) {
      clients.push(p.stream(id, 'message ' + id));
      await tick(30);
    }
    await tick(60);
    assert.deepEqual(p.dials.map((d) => d.plain.toString()).sort(), Array.from({ length: 12 }, (_, i) => 'message ' + (i + 1)).sort(), 'every stream reached its data centre');
    assert.ok(!p.failed(), 'the carrier never failed');
    assert.equal(p.appFrames().filter((f) => f.type === T.CLOSE).length, 0, 'no stream was refused or dropped');
    assert.ok(p.fakeWs.length >= 3, 'several sockets were used (' + p.fakeWs.length + ')');
    assert.ok(p.fakeWs.every((ws) => p.opensOn(ws).length <= 4), 'no socket went past its switch point');

    // a stream on the first socket still works after the page moved on
    const first = p.opensOn(p.fakeWs[0]);
    assert.ok(first.includes(1));
    assert.equal(p.fakeWs[0].readyState, 1, 'the first socket lives while its streams do');
    const dc1 = p.dials.find((d) => d.plain.toString() === 'message 1');
    const reply = crypto.randomBytes(5000);
    dc1.reply(reply);
    await tick(60);
    const back = p.appFrames().filter((f) => f.type === T.DATA && f.id === 1);
    assert.ok(clients[0].open(Buffer.concat(back.map((f) => Buffer.from(f.payload)))).equals(reply));

    // once its last stream closes, the old socket goes; the newest stays
    for (const id of first) p.app.onmessage({ data: buf(cframe(T.CLOSE, id)) });
    await tick(30);
    assert.equal(p.fakeWs[0].readyState, 3, 'the drained socket is closed');
    assert.equal(p.fakeWs.at(-1).readyState, 1);
    assert.ok(!p.failed());
  } finally {
    globalThis.__connect = previous;
  }
});

await test('a socket that dies closes only its own streams, and the page carries on', async () => {
  const previous = globalThis.__connect;
  try {
    const p = await connectedPage();
    p.stream(1, 'before');
    p.stream(2, 'before too');
    await tick(60);
    p.fakeWs[0].pair[1].close(1011); // the relay's end goes away
    await tick(80);
    const closes = p.appFrames().filter((f) => f.type === T.CLOSE).map((f) => f.id).sort();
    assert.deepEqual(closes, [1, 2], 'the app hears that exactly those streams closed');
    assert.ok(!p.failed(), 'the carrier itself did not fail');
    assert.ok(p.fakeWs.length >= 2 && p.fakeWs.at(-1).readyState === 1, 'a fresh socket is ready');
    p.stream(3, 'after');
    await tick(80);
    assert.ok(p.dials.some((d) => d.plain.toString() === 'after'), 'a new stream gets through');
    assert.deepEqual(p.opensOn(p.fakeWs.at(-1)), [3]);
  } finally {
    globalThis.__connect = previous;
  }
});

await test('pings are answered by the page, never reach the app, and renew its token', async () => {
  const previous = globalThis.__connect;
  try {
    const p = await connectedPage();
    const cfg = await config();
    const token = await W.mintToken(cfg, 2);
    p.fakeWs[0].pair[1].send(W.frame(5, 0, text(token)));
    await tick(30);
    const pongs = p.fakeWs[0].sent.flatMap((b) => W.parseFrames(Uint8Array.from(b))).filter((f) => f.type === T.PONG);
    assert.equal(pongs.length, 1);
    assert.equal(pongs[0].id, 0);
    assert.equal(Buffer.from(pongs[0].payload).toString(), token, 'the PONG echoes the token');
    assert.ok(!p.appFrames().some((f) => f.type === 5), 'the app never sees a PING');

    // the next lane is opened with the token from the ping
    p.fakeWs[0].pair[1].close(1011);
    await tick(80);
    const posts = p.page.requests.filter((r) => r.init.method === 'POST');
    assert.equal(posts.length, 2);
    assert.equal(posts[1].init.headers.Authorization, 'Bearer ' + token);
    assert.ok(!p.failed());
  } finally {
    globalThis.__connect = previous;
  }
});

// ------------------------------------------------------------------------------ live (optional)

if (LIVE) {
  /** connect() backed by a real TCP socket. */
  const realConnect = ({ hostname, port }) => {
    const socket = net.connect({ host: hostname, port });
    const opened = new Promise((resolve, reject) => {
      socket.once('connect', resolve);
      socket.once('error', reject);
    });
    return {
      opened,
      readable: Readable.toWeb(socket),
      writable: Writable.toWeb(socket),
      close: () => socket.destroy(),
    };
  };

  for (const dc of [1, 2, 3, 4, 5]) {
    await test(`LIVE: data centre ${dc} (${W.DATA_CENTRES[dc][0]}) answers req_pq_multi through the worker`, async () => {
      globalThis.__connect = realConnect;
      const { ws, client } = await openStream({ dc, tag: 0xeeeeeeee });
      ws.deliver(buf(cframe(T.OPEN, 1)));
      // an unencrypted MTProto message: req_pq_multi#be7e8ef1 nonce:int128, in the intermediate transport
      const nonce = crypto.randomBytes(16);
      const body = Buffer.concat([Buffer.from('f18e7ebe', 'hex'), nonce]);
      const message = Buffer.alloc(20 + body.length);
      message.writeBigUInt64LE(BigInt(Math.floor(Date.now() / 1000)) << 32n, 8);
      message.writeUInt32LE(body.length, 16);
      body.copy(message, 20);
      const packet = Buffer.alloc(4 + message.length);
      packet.writeUInt32LE(message.length, 0);
      message.copy(packet, 4);
      ws.deliver(buf(cframe(T.DATA, 1, Buffer.concat([client.wire, client.seal(packet)]))));

      let plain = Buffer.alloc(0);
      let consumed = 0;
      for (let i = 0; i < 80 && plain.length < 4 + 24 + 16; i++) {
        await tick(100);
        const all = Buffer.concat(received(ws).filter((f) => f.type === T.DATA && f.id === 1).map((f) => f.payload));
        if (all.length > consumed) {
          plain = Buffer.concat([plain, client.open(all.subarray(consumed))]);
          consumed = all.length;
        }
      }
      assert.ok(plain.length >= 44, 'no answer from the data centre (got ' + plain.length + ' bytes)');
      assert.equal(plain.readUInt32LE(4 + 20), 0x05162463, 'the answer is res_pq');
      assert.ok(plain.subarray(4 + 24, 4 + 40).equals(nonce), 'it echoes our nonce');
      ws.deliver(buf(cframe(T.CLOSE, 1)));
    });
  }
}

console.log(`\n${passed} passed${process.exitCode ? ', some FAILED' : ''}`);
