// The subscription Worker (app/src/main/assets/studio_sub/worker.js), against a fake KV.
// Run: node scripts/test-studio-sub.mjs
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import fs from 'node:fs';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
// The Worker is an ES module in a folder Node reads as CommonJS: loaded from its text as a module.
const source = fs.readFileSync(path.join(here, '..', 'app', 'src', 'main', 'assets', 'studio_sub', 'worker.js'), 'utf8');
const worker = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const { handle } = worker;

function kv() {
  const m = new Map();
  let writes = 0;
  return {
    m,
    get writes() { return writes; },
    async get(k, type) { const v = m.get(k); return v === undefined ? null : (type === 'json' ? JSON.parse(v) : v); },
    async put(k, v) { writes++; m.set(k, String(v)); },
  };
}

const TOKEN = 'AbCdEfGhIjKlMnOpQrStUv';
const IDS = ['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222', '33333333-3333-4333-8333-333333333333'];

function record(over = {}) {
  return Object.assign({
    v: 1, on: true, until: 0, name: 'ali', limit: 0, nohwid: 'allow', ids: [IDS[0]],
    t: {
      x: JSON.stringify([{ remarks: 'direct', inbounds: [], outbounds: [{ protocol: 'vless', settings: { vnext: [{ users: [{ id: '{{ID}}' }] }] } }], routing: {} }]),
      c: 'proxies:\n  - {name: "w", type: vless, uuid: {{ID}}}\n',
      l: 'vless://{{ID}}@h.workers.dev:443?type=ws#a\nvless://{{ID}}@1.2.3.4:443?type=ws#b',
    },
  }, over);
}

function req(pathname, { ua = 'v2rayNG/1.9.30', hwid, method = 'GET', model, os } = {}) {
  const headers = new Headers({ 'user-agent': ua });
  if (hwid) headers.set('x-hwid', hwid);
  if (model) headers.set('x-device-model', model);
  if (os) headers.set('x-device-os', os);
  return new Request('https://x-sb.sub.workers.dev' + pathname, { method, headers });
}

async function setup(over) {
  const S = kv();
  await S.put('u:' + TOKEN, JSON.stringify(record(over)));
  return { S, env: { S } };
}

test('anything that is not a token is a small JSON document; an unknown token is 404', async () => {
  const { env } = await setup();
  let r = await handle(req('/'), env);
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { status: 'ok' });
  r = await handle(req('/short'), env);
  assert.deepEqual(await r.json(), { status: 'ok' });
  r = await handle(req('/ZZZZZZZZZZZZZZZZZZZZZZZZ'), env);
  assert.equal(r.status, 404);
  r = await handle(new Request('https://x/' + TOKEN, { method: 'POST' }), env);
  assert.equal(r.status, 405);
});

test('no limit: everyone gets the one id, in the format their app reads', async () => {
  const { env } = await setup();
  let r = await handle(req('/' + TOKEN), env);
  const x = JSON.parse(await r.text());
  assert.equal(x[0].outbounds[0].settings.vnext[0].users[0].id, IDS[0]);
  r = await handle(req('/' + TOKEN, { ua: 'HiddifyNext/2.5.7 (android)' }), env);
  assert.match(r.headers.get('content-type'), /yaml/);
  assert.match(await r.text(), new RegExp('uuid: ' + IDS[0]));
  r = await handle(req('/' + TOKEN + '?f=l'), env);
  const links = Buffer.from(await r.text(), 'base64').toString('utf8');
  assert.equal(links.split('\n').length, 2);
  assert.ok(links.startsWith('vless://' + IDS[0] + '@'));
  assert.ok(!links.includes('{{ID}}'));
});

test('the headers carry the name and the end, as apps read them', async () => {
  const until = Date.UTC(2026, 11, 1);
  const { env } = await setup({ until, name: 'علی' });
  const r = await handle(req('/' + TOKEN), env, until - 1000);
  assert.equal(r.headers.get('subscription-userinfo'), `upload=0; download=0; total=0; expire=${Math.floor(until / 1000)}`);
  const title = r.headers.get('profile-title');
  assert.ok(title.startsWith('base64:'));
  assert.equal(Buffer.from(title.slice(7), 'base64').toString('utf8'), 'علی');
  assert.equal(r.headers.get('cache-control'), 'no-store');
});

test('switched off or past its end: one config that says so, nothing that connects', async () => {
  let { env } = await setup({ on: false });
  let body = await (await handle(req('/' + TOKEN), env)).text();
  assert.match(body, /اشتراک خاموش است/);
  assert.ok(!body.includes(IDS[0]));
  ({ env } = await setup({ until: 1000 }));
  body = await (await handle(req('/' + TOKEN), env, 2000)).text();
  assert.match(body, /اعتبار تمام شده است/);
  assert.ok(!body.includes(IDS[0]));
});

test('a limit of two: each device its own place and id, the third is refused, a known one keeps its place', async () => {
  const { S, env } = await setup({ limit: 2, ids: IDS.slice(0, 2) });
  const idOf = async (hwid) => {
    const t = await (await handle(req('/' + TOKEN, { hwid, ua: 'Happ/1.0', model: 'SM-A505F', os: 'Android' }), env)).text();
    return IDS.find((i) => t.includes(i)) || t;
  };
  assert.equal(await idOf('phone-A'), IDS[0]);
  assert.equal(await idOf('phone-B'), IDS[1]);
  const third = await idOf('phone-C');
  assert.match(third, /سقف دستگاه پر است/);
  assert.equal(await idOf('phone-A'), IDS[0]);
  assert.equal(await idOf('phone-B'), IDS[1]);
  const devs = JSON.parse(S.m.get('d:' + TOKEN)).dev;
  assert.equal(devs.length, 2);
  assert.equal(devs[0].model, 'SM-A505F');
  assert.ok(!JSON.stringify(devs).includes('phone-A'), 'the device id is kept only as a hash');
});

test('a HEAD request reads the headers and registers nobody', async () => {
  const { S, env } = await setup({ limit: 1, ids: IDS.slice(0, 1) });
  const r = await handle(req('/' + TOKEN, { hwid: 'x', method: 'HEAD' }), env);
  assert.equal(r.status, 200);
  assert.equal(S.m.get('d:' + TOKEN), undefined);
});

test('apps that send no device id share one place, or are refused, as the operator chose', async () => {
  let { env } = await setup({ limit: 2, ids: IDS.slice(0, 2), nohwid: 'allow' });
  const a = await (await handle(req('/' + TOKEN, { ua: 'v2rayNG/1.9' }), env)).text();
  const b = await (await handle(req('/' + TOKEN, { ua: 'v2rayNG/1.8' }), env)).text();
  assert.ok(a.includes(IDS[0]) && b.includes(IDS[0]), 'both share the first place');
  const c = await (await handle(req('/' + TOKEN, { hwid: 'h1' }), env)).text();
  assert.ok(c.includes(IDS[1]), 'a device with an id takes the second');
  ({ env } = await setup({ limit: 2, ids: IDS.slice(0, 2), nohwid: 'block' }));
  const d = await (await handle(req('/' + TOKEN, { ua: 'v2rayNG/1.9' }), env)).text();
  assert.match(d, /Happ/);
  assert.ok(!d.includes(IDS[0]));
});

test('a lower limit takes the places past it away: their devices are refused, not given someone else\'s id', async () => {
  const { S, env } = await setup({ limit: 2, ids: IDS.slice(0, 2) });
  await handle(req('/' + TOKEN, { hwid: 'A' }), env);
  await handle(req('/' + TOKEN, { hwid: 'B' }), env);
  await S.put('u:' + TOKEN, JSON.stringify(record({ limit: 1, ids: IDS.slice(0, 1) })));
  const b = await (await handle(req('/' + TOKEN, { hwid: 'B' }), env)).text();
  assert.match(b, /سقف دستگاه پر است/);
  const a = await (await handle(req('/' + TOKEN, { hwid: 'A' }), env)).text();
  assert.ok(a.includes(IDS[0]));
});

test('a known device is written back at most once a day', async () => {
  const { S, env } = await setup({ limit: 1, ids: IDS.slice(0, 1) });
  const t0 = 1_800_000_000_000;
  await handle(req('/' + TOKEN, { hwid: 'A' }), env, t0);
  const w = S.writes;
  await handle(req('/' + TOKEN, { hwid: 'A' }), env, t0 + 3600_000);
  assert.equal(S.writes, w);
  await handle(req('/' + TOKEN, { hwid: 'A' }), env, t0 + 2 * 86_400_000);
  assert.equal(S.writes, w + 1);
});

test('a device the operator took off is refused, and the place it left is free for another', async () => {
  const { S, env } = await setup({ limit: 1, ids: IDS.slice(0, 1) });
  await handle(req('/' + TOKEN, { hwid: 'A' }), env);
  const key = JSON.parse(S.m.get('d:' + TOKEN)).dev[0].k;
  assert.match(key, /^h:[0-9a-f]{24}$/);
  // The phone: a fresh id for the place, the device banned, its entry gone.
  await S.put('u:' + TOKEN, JSON.stringify(record({ limit: 1, ids: [IDS[1]], ban: [key] })));
  await S.put('d:' + TOKEN, JSON.stringify({ v: 1, dev: [] }));
  const a = await (await handle(req('/' + TOKEN, { hwid: 'A' }), env)).text();
  assert.match(a, /برداشته شده/);
  assert.ok(!a.includes(IDS[1]));
  const b = await (await handle(req('/' + TOKEN, { hwid: 'B' }), env)).text();
  assert.ok(b.includes(IDS[1]), 'the next device takes the freed place');
});
