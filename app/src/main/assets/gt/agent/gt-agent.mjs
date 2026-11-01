// --- GitHub Tunnel v2: the cloud session, on an ubuntu-latest runner ---
//
// Pushed to the user's private repo as agent/gt-agent.mjs (with agent/seal.mjs) and run by
// .github/workflows/mlmvpn-tunnel-v2.yml (gt-workflow-template.js › buildWorkflowYamlV2).
//
// It stands up, for exactly one client:
//   * Xray, VLESS over WebSocket on 127.0.0.1 — nothing on this machine listens publicly;
//   * three Cloudflare quick tunnels to it (a runner has no inbound ports; each quick tunnel
//     also caps at 200 in-flight requests, so three give capacity and failover);
//   * and, when the user set it up, the connector of one STABLE tunnel (a named tunnel the
//     user's Worker reaches through Workers VPC — see THE STABLE TUNNEL below);
// then SEALS the credentials to the dispatching client's one-time key (seal.mjs) and commits
// only ciphertext to the repo. A supervisor restarts whatever dies and republishes.
//
// The client never reaches *.trycloudflare.com directly — that domain is blocked from Iran —
// but through the user's own Worker (cloudflare-worker/gt-broker › THE PASSTHROUGH). A client
// with no Worker (no Cloudflare account, or a line where Cloudflare itself is filtered) comes in
// by the RELAYS instead (relays.mjs): free public relays this runner dials out to, no account.
//
// Every value generated here is masked from the job log before anything can print it.
import { seal } from './seal.mjs';
import { buildXrayConfig, WS_PORT, STATUS_PORT } from './xray-config.mjs';
import { createExitBroker, exitSlots, EXIT_SLOTS } from './exits.mjs';
import { createRelays } from './relays.mjs';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const pexec = promisify(execFile);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const T0 = Date.now();
const log = (m) => console.log(`[${((Date.now() - T0) / 1000).toFixed(0)}s] ${m}`);

const SESSION_ID = process.env.SESSION_ID || '';
const CLIENT_PUB = process.env.CLIENT_PUB || '';
const REPO = process.env.GITHUB_REPOSITORY || '';
const TOKEN = process.env.GITHUB_TOKEN || '';
const KEEP_ALIVE_MINUTES = Math.min(350, Math.max(5, parseInt(process.env.KEEP_ALIVE_MINUTES || '335', 10) || 335));
if (!/^[A-Za-z0-9_-]{6,64}$/.test(SESSION_ID)) throw new Error('bad SESSION_ID');
if (!/^[A-Za-z0-9_-]{43}$/.test(CLIENT_PUB)) throw new Error('bad CLIENT_PUB');
if (!/^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/.test(REPO)) throw new Error('bad GITHUB_REPOSITORY');

// Pinned to the digests GitHub reports for these release assets. Xray follows the client's
// own core so both ends speak the same dialect; an unknown version falls back to the newest.
const XRAY = {
    '26.9.9': { url: 'https://github.com/XTLS/Xray-core/releases/download/v26.9.9/Xray-linux-64.zip', sha256: '1eb9175d0f0a8f8149c9230a7fc5ae66ce332ed20a53155ce61fe62e3f58b7df' },
    '26.7.28': { url: 'https://github.com/XTLS/Xray-core/releases/download/v26.7.28/Xray-linux-64.zip', sha256: '8195d909f1109b8f3d99eefe401a3c451d7bf4af71f24d3815420f77e5dd2a40' },
};
const XRAY_VERSION = XRAY[process.env.XRAY_VERSION] ? process.env.XRAY_VERSION : '26.9.9';
const CLOUDFLARED = { url: 'https://github.com/cloudflare/cloudflared/releases/download/2026.9.1/cloudflared-linux-amd64', sha256: '03f1f25d1cc93b9ad6c60569d44060bc4f17ed97075760ed8cfca4b12dcd68cc' };
const TUNNELS = 3;
// How often each quick tunnel is checked from the OUTSIDE, and how many misses in a row replace
// it. A cloudflared that is still running can serve a tunnel Cloudflare has already dropped —
// the old supervisor only noticed a process that exited, so that tunnel stayed dead for the
// rest of the session while the client kept trying it.
const HEALTH_EVERY_MS = 60000;
const HEALTH_MISSES = 2;
// The client prepares the next session before this one ends (make-before-break); this is
// when the runner starts saying so, in case the client's own clock disagrees.
const ENDING_LEAD_MS = 12 * 60 * 1000;

const WORK = '/tmp/gt';
fs.mkdirSync(WORK, { recursive: true });
const rnd = (n) => crypto.randomBytes(n).toString('base64url');
const S = { uuid: crypto.randomUUID(), wsPath: '/' + rnd(12) };
for (const v of Object.values(S)) console.log(`::add-mask::${v}`);
// One VLESS user per EXIT SLOT (exits.mjs): the client picks a country by picking a user.
const EXIT_UUIDS = Array.from({ length: EXIT_SLOTS }, () => crypto.randomUUID());
for (const v of EXIT_UUIDS) console.log(`::add-mask::${v}`);

let rev = 0;
const DEADLINE = T0 + KEEP_ALIVE_MINUTES * 60 * 1000;
const result = { v: 2, rev, phase: 'starting', hosts: [], wsPath: S.wsPath,
    uuids: { direct: S.uuid, ...Object.fromEntries(EXIT_UUIDS.map((u, i) => [`x${i + 1}`, u])) },
    xrayVersion: XRAY_VERSION, runner: {}, startedAt: T0, endsAt: DEADLINE, ending: false, errors: [], exits: [], catalog: null,
    // The ways in that are not Cloudflare: [{ host, kind }] (relays.mjs). A field of its own, so a
    // client that knows only quick tunnels never meets a name it would refuse.
    relays: [] };
const fail = (where, e) => { const msg = `${where}: ${e && e.message ? e.message : e}`; result.errors.push(msg.slice(0, 200)); log(`ERROR ${msg}`); };

// ── publishing ──────────────────────────────────────────────────────────────────
async function gh(method, url, body) {
    return fetch(url, {
        method,
        headers: {
            Authorization: `Bearer ${TOKEN}`, Accept: 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'mlmvpn-gt-agent',
            ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
    });
}
// One publish at a time: the first way in, the ways that come up after it and the supervisor can
// all want to publish in the same second, and two PUTs racing for one file's sha lose one rev.
let publishing = Promise.resolve(true);
function publish() {
    publishing = publishing.then(publishNow, publishNow);
    return publishing;
}
async function publishNow() {
    result.rev = ++rev;
    const url = `https://api.github.com/repos/${REPO}/contents/sessions/${SESSION_ID}.json`;
    const content = Buffer.from(JSON.stringify(seal(CLIENT_PUB, SESSION_ID, result))).toString('base64');
    for (let i = 0; i < 5; i++) {
        let sha;
        try { const r = await gh('GET', url); if (r.ok) sha = (await r.json()).sha; } catch (e) {}
        try {
            const r = await gh('PUT', url, { message: 'MLMVPN: session', content, ...(sha ? { sha } : {}) });
            if (r.ok) { log(`published rev ${rev} (${result.phase}, ${result.hosts.length} hosts)`); return true; }
            log(`publish HTTP ${r.status}`);
        } catch (e) { log(`publish: ${e.message}`); }
        await sleep(1500 * (i + 1));
    }
    return false;
}

// ── binaries ────────────────────────────────────────────────────────────────────
async function fetchPinned({ url, sha256 }) {
    const res = await fetch(url, { redirect: 'follow' });
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${path.basename(url)}`);
    const buf = Buffer.from(await res.arrayBuffer());
    const got = crypto.createHash('sha256').update(buf).digest('hex');
    if (got !== sha256) throw new Error(`sha256 mismatch for ${path.basename(url)}`);
    const file = path.join(WORK, path.basename(new URL(url).pathname));
    fs.writeFileSync(file, buf);
    return file;
}
function daemon(name, exe, args) {
    const out = fs.openSync(path.join(WORK, `${name}.log`), 'a');
    const p = spawn(exe, args, { stdio: ['ignore', out, out] });
    p.on('exit', (code) => log(`${name} exited (${code})`));
    return p;
}

// ── the stable tunnel (github-tunnel/gt-slots.js) ───────────────────────────────
// A named Cloudflare tunnel the user's Worker reaches through Workers VPC — no domain, no public
// hostname. This session runs the connector of ONE slot; the token is a repository secret for
// that slot, handed to the connector in its environment (never on a command line, where `ps`
// shows it). Both slots' secrets leave this process's environment at once: nothing started later
// — cloudflared's quick tunnels, Xray — inherits them.
const SLOT = /^[abc]$/.test(process.env.SLOT || '') ? process.env.SLOT : '';
const SLOT_TOKEN = SLOT ? String(process.env[`GT_SLOT_${SLOT.toUpperCase()}`] || '') : '';
delete process.env.GT_SLOT_A;
delete process.env.GT_SLOT_B;
delete process.env.GT_SLOT_C;
const SLOT_METRICS = 20299;
let slotProc = null;
let slotMisses = 0;
function startSlot() {
    const out = fs.openSync(path.join(WORK, 'slot.log'), 'a');
    // QUIC (the default): Workers VPC reaches a connector only over QUIC.
    slotProc = spawn(CF, ['tunnel', '--no-autoupdate', '--metrics', `127.0.0.1:${SLOT_METRICS}`, 'run'],
        { stdio: ['ignore', out, out], env: { ...process.env, TUNNEL_TOKEN: SLOT_TOKEN } });
    slotProc.on('exit', (code) => log(`slot connector exited (${code})`));
}
/** Registered with Cloudflare and able to take requests (cloudflared's own readiness answer). */
async function slotReady() {
    try {
        const r = await fetch(`http://127.0.0.1:${SLOT_METRICS}/ready`, { signal: AbortSignal.timeout(3000) });
        return r.status === 200;
    } catch (e) { return false; }
}
async function bringSlot(timeoutMs = 40000) {
    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
        if (await slotReady()) return true;
        if (slotProc && slotProc.exitCode !== null) return false;
        await sleep(1000);
    }
    return false;
}

// ── quick tunnels ───────────────────────────────────────────────────────────────
const CF = path.join(WORK, 'cloudflared');
const tunnels = [];
function startTunnel(n) {
    const t = { n, metrics: 20240 + n, logFile: path.join(WORK, `cf${n}.log`), host: null, ok: false, restarts: 0 };
    t.proc = daemon(`cf${n}`, CF, ['tunnel', '--no-autoupdate', '--url', `http://127.0.0.1:${WS_PORT}`,
        '--metrics', `127.0.0.1:${t.metrics}`, '--logfile', t.logFile, '--loglevel', 'info']);
    return t;
}
async function resolveHost(t, timeoutMs = 90000) {
    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
        try {
            const r = await fetch(`http://127.0.0.1:${t.metrics}/quicktunnel`);
            if (r.ok) { const j = await r.json(); if (j && j.hostname) return j.hostname; }
        } catch (e) {}
        try {
            const m = fs.readFileSync(t.logFile, 'utf8').match(/https:\/\/([a-z0-9-]+\.trycloudflare\.com)/);
            if (m) return m[1];
        } catch (e) {}
        await sleep(1000);
    }
    return null;
}
// Through Cloudflare's edge and back: Xray answering a plain GET with 400 proves the path;
// a 5xx/530 is Cloudflare saying the tunnel is not there yet.
async function selfTest(t) {
    const until = Date.now() + 45000;
    while (Date.now() < until) {
        try {
            const r = await fetch(`https://${t.host}${S.wsPath}`, { signal: AbortSignal.timeout(8000) });
            t.status = r.status;
            if (r.status < 500) return true;
        } catch (e) { t.status = String(e.message || e).slice(0, 40); }
        await sleep(2000);
    }
    return false;
}
async function bring(t) {
    // In flight: the session may already be open on another path, and the supervisor must not
    // judge (or replace) a tunnel that is still coming up.
    t.bringing = true;
    try {
        t.host = await resolveHost(t);
        if (!t.host) { t.ok = false; return t; }
        console.log(`::add-mask::${t.host}`);
        t.ok = await selfTest(t);
        return t;
    } finally {
        t.bringing = false;
    }
}
const liveHosts = () => tunnels.filter((t) => t.ok && t.host).map((t) => ({ host: t.host }));
/** True as soon as any of `promises` resolves true; false once all have settled without one. */
function firstTrue(promises) {
    return new Promise((resolve) => {
        let left = promises.length;
        if (!left) { resolve(false); return; }
        for (const p of promises) {
            p.then((v) => { if (v) resolve(true); if (--left === 0) resolve(false); }, () => { if (--left === 0) resolve(false); });
        }
    });
}

// One look from the outside, the way the client's traffic arrives: through Cloudflare's edge.
async function healthy(t) {
    if (!t.host) return false;
    try {
        const r = await fetch(`https://${t.host}${S.wsPath}`, { signal: AbortSignal.timeout(10000) });
        return r.status < 500;
    } catch (e) { return false; }
}

// ── the status channel ──────────────────────────────────────────────────────────
// The same sealed payload the repo gets, served on loopback. Xray sends the client's requests
// for STATUS_HOST here (xray-config.mjs), so any live quick tunnel carries it: a replaced tunnel
// or the session's end reaches the client in seconds, without GitHub and without DNS. Sealed per
// response: only the client that dispatched this session can read it, and a tampered copy fails
// its tag. Loopback only — nothing outside this machine can open it.
// Also the EXIT BROKER's two commands (exits.mjs): `/x?cc=JP[&provider=]` starts or reuses an exit,
// `/x/stop?id=x1` frees one. Only the tunnel's own client can reach this server at all.
let exitBroker = null;
function startStatusServer() {
    const srv = http.createServer(async (req, res) => {
        const send = (code, extra = {}) => {
            try {
                const body = JSON.stringify(seal(CLIENT_PUB, SESSION_ID, { ...result, ...(exitBroker ? exitBroker.publicState() : {}), now: Date.now(), ...extra }));
                res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'Content-Length': Buffer.byteLength(body) });
                res.end(body);
            } catch (e) { res.writeHead(500); res.end(); }
        };
        const url = new URL(req.url || '/', 'http://status');
        if (req.method !== 'GET') { res.writeHead(404); res.end(); return; }
        if (url.pathname === '/s') return send(200);
        if (url.pathname === '/x' && exitBroker) {
            try { return send(200, { asked: exitBroker.request(url.searchParams.get('cc'), url.searchParams.get('provider') || '') }); }
            catch (e) { return send(e.status || 500, { askError: String(e.message || e).slice(0, 160) }); }
        }
        if (url.pathname === '/x/stop' && exitBroker) {
            try { return send(200, { stopped: await exitBroker.stop(url.searchParams.get('id'), { leak: url.searchParams.get('leak') === '1' }) }); }
            catch (e) { return send(e.status || 500, { askError: String(e.message || e).slice(0, 160) }); }
        }
        res.writeHead(404); res.end();
    });
    srv.on('error', (e) => fail('status server', e));
    srv.listen(STATUS_PORT, '127.0.0.1');
    return srv;
}

async function curlJson(url) {
    try { const { stdout } = await pexec('curl', ['-s', '--max-time', '10', url], { timeout: 15000 }); return JSON.parse(stdout); } catch (e) { return null; }
}

// ── main ────────────────────────────────────────────────────────────────────────
(async () => {
    log(`session ${SESSION_ID}: xray ${XRAY_VERSION}, ${KEEP_ALIVE_MINUTES} min`);

    const [xz, cfb] = await Promise.all([fetchPinned(XRAY[XRAY_VERSION]), fetchPinned(CLOUDFLARED)]);
    await pexec('unzip', ['-o', '-q', xz, '-d', path.join(WORK, 'xray')]);
    fs.renameSync(cfb, CF);
    fs.chmodSync(CF, 0o755);
    const XRAY_BIN = path.join(WORK, 'xray', 'xray');
    log('binaries verified');

    // A congestion controller built for long fat pipes, and buffers to match.
    for (const s of ['net.core.default_qdisc=fq', 'net.ipv4.tcp_congestion_control=bbr',
        'net.core.rmem_max=67108864', 'net.core.wmem_max=67108864',
        'net.ipv4.tcp_rmem=4096 87380 67108864', 'net.ipv4.tcp_wmem=4096 65536 67108864', 'net.ipv4.tcp_mtu_probing=1']) {
        try { await pexec('sudo', ['sysctl', '-w', s]); } catch (e) { fail('sysctl', e); }
    }

    startStatusServer();
    fs.writeFileSync(path.join(WORK, 'xray.json'), JSON.stringify(buildXrayConfig({ uuid: S.uuid, wsPath: S.wsPath, exits: exitSlots(EXIT_UUIDS) })));
    exitBroker = createExitBroker({ work: WORK, log, sleep, pexec, xrayBin: XRAY_BIN, fetchPinned, onChange: () => { Object.assign(result, exitBroker.publicState()); },
        // An exit found leaving from this address is not an exit at all (exits.mjs › leakOf).
        ownIp: () => (result.runner && result.runner.ip) || '' });
    exitBroker.init(EXIT_UUIDS);
    let xray = daemon('xray', XRAY_BIN, ['run', '-c', path.join(WORK, 'xray.json')]);
    await sleep(800);

    for (let n = 1; n <= TUNNELS; n++) tunnels.push(startTunnel(n));
    if (SLOT && SLOT_TOKEN) startSlot();
    // Beside the quick tunnels, the ways in without Cloudflare. Once the session is open, a relay
    // that comes up late, dies or moves to another name is republished at once.
    const relays = createRelays({ work: WORK, log, pexec, sleep, wsPort: WS_PORT, wsPath: S.wsPath,
        onChange: () => { result.relays = relays.live(); publish(); } });
    const relayP = await relays.begin();
    // The client only ever learns whether its slot answers — never the token, never an id.
    if (SLOT) result.slot = { name: SLOT, ready: false };
    const geoP = (async () => {
        const geo = await curlJson('https://api.ip.sb/geoip') || await curlJson('https://ipinfo.io/json');
        if (geo) result.runner = { ip: geo.ip, country: geo.country_code || geo.country, city: geo.city, org: geo.organization || geo.asn_organization || geo.org };
        if (result.runner.ip) console.log(`::add-mask::${result.runner.ip}`);
    })().catch(() => {});

    // THE FIRST WAY IN OPENS THE SESSION. Measured 2026-09-23: all three quick tunnels failed on
    // one runner while its stable tunnel was up in seconds, and waiting for all of them held the
    // session back 58 s. Now whichever path answers first makes the session ready — with a short
    // grace for the others, so the client rarely has to reload for a path that was a second late —
    // and anything that comes up after is published as it does (the client's status channel
    // picks it up).
    const bringing = tunnels.map((t) => bring(t).then(() => t.ok, () => false));
    const slotP = slotProc ? bringSlot().then((ok) => { result.slot.ready = ok; return ok; }, () => false) : Promise.resolve(false);
    const all = Promise.all([...bringing, slotP, ...relayP]);
    await firstTrue([...bringing, slotP, ...relayP]);
    await Promise.race([all, sleep(8000)]);
    await Promise.race([geoP, sleep(5000)]);
    result.hosts = liveHosts();
    result.relays = relays.live();
    const describe = () => `tunnels: ${tunnels.map((t) => (t.ok ? 'ok' : t.host === null && !t.status ? '…' : `FAIL(${t.status || 'no host'})`)).join(' ')}; stable ${SLOT || '-'}: ${result.slot && result.slot.ready ? 'ready' : SLOT && !SLOT_TOKEN ? 'no token' : SLOT ? 'not yet' : 'off'}; relays: ${relays.describe()}`;
    log(describe());

    // Any path is a way in: the quick tunnels, the stable one, or a relay alone.
    const usable = () => result.hosts.length > 0 || !!(result.slot && result.slot.ready) || result.relays.length > 0;
    result.phase = usable() ? 'ready' : 'failed';
    await publish();
    if (!usable()) { log('no tunnel came up'); process.exit(1); }
    relays.started();
    // The countries an exit can be asked for — fetched once the session is open, never before it —
    // then the ones the user chose (the `exits` input), started now so they are ready by the time
    // the client asks. Only well-formed codes: `JP` or `JP:psiphon`, six at most.
    const wanted = String(process.env.EXITS || '').split(',').map((s) => s.trim()).filter((s) => /^[A-Z]{2}(:(vpngate|psiphon))?$/.test(s)).slice(0, 6);
    exitBroker.refreshCatalog().catch(() => {}).then(() => {
        for (const w of wanted) {
            const [cc, provider] = w.split(':');
            try { exitBroker.request(cc, provider || ''); log(`exit ${cc}${provider ? `/${provider}` : ''}: started at boot`); } catch (e) { log(`exit ${cc}: ${e.message}`); }
        }
    });
    all.then(async () => {
        const before = JSON.stringify([result.hosts, result.slot]);
        result.hosts = liveHosts();
        if (JSON.stringify([result.hosts, result.slot]) !== before) { log(`late: ${describe()}`); await publish(); }
    });

    // Stay up; bring back whatever dies — a process that exits, or a tunnel Cloudflare stopped
    // serving while its process kept running — and tell the client where it went.
    let lastHealth = Date.now();
    while (Date.now() < DEADLINE) {
        await sleep(20000);
        let changed = false;
        if (xray.exitCode !== null) { log('xray died; restarting'); xray = daemon('xray', XRAY_BIN, ['run', '-c', path.join(WORK, 'xray.json')]); }
        const healthTurn = Date.now() - lastHealth >= HEALTH_EVERY_MS;
        // The relays publish their own changes (onChange above).
        await relays.supervise(healthTurn);
        if (healthTurn) {
            lastHealth = Date.now();
            // Exits that stopped carrying data are brought up again; the catalog is refreshed now
            // and then (VPN Gate's list changes through the day).
            if (exitBroker) {
                exitBroker.supervise().catch(() => {});
                if (Date.now() - (exitBroker.publicState().catalog.at || 0) > 30 * 60 * 1000) exitBroker.refreshCatalog().catch(() => {});
            }
            await Promise.all(tunnels.map(async (t) => {
                if (t.bringing || t.proc.exitCode !== null) return;
                if (await healthy(t)) { t.misses = 0; return; }
                t.misses = (t.misses || 0) + 1;
                if (t.misses >= HEALTH_MISSES) {
                    log(`tunnel ${t.n} stopped answering through Cloudflare; replacing it`);
                    t.ok = false;
                    try { t.proc.kill('SIGKILL'); } catch (e) {}
                    await sleep(500);
                }
            }));
            // The stable tunnel: cloudflared's own readiness, the same two-miss rule.
            if (slotProc && slotProc.exitCode === null) {
                if (await slotReady()) {
                    slotMisses = 0;
                    if (!result.slot.ready) { result.slot.ready = true; changed = true; log('stable tunnel ready'); }
                } else if (++slotMisses >= HEALTH_MISSES) {
                    log('stable tunnel stopped answering; restarting its connector');
                    try { slotProc.kill('SIGKILL'); } catch (e) {}
                    await sleep(500);
                }
            }
        }
        if (slotProc && slotProc.exitCode !== null && Date.now() < DEADLINE) {
            log('stable tunnel connector died; restarting');
            startSlot();
            slotMisses = 0;
            const ok = await bringSlot();
            if (result.slot.ready !== ok) { result.slot.ready = ok; changed = true; }
        }
        for (const t of tunnels) {
            if (t.bringing || t.proc.exitCode === null) continue;
            const restarts = t.restarts + 1;
            log(`tunnel ${t.n} died; restarting`);
            Object.assign(t, startTunnel(t.n), { restarts, misses: 0 });
            await bring(t);
            changed = true;
        }
        // Said once, well before the end: the client uses it to have the next session ready.
        if (!result.ending && DEADLINE - Date.now() <= ENDING_LEAD_MS) { result.ending = true; changed = true; log('session ending soon'); }
        if (changed) { result.hosts = liveHosts(); await publish(); }
    }
    log('session over');
    relays.stopAll();
    if (exitBroker) await exitBroker.stopAll();
    process.exit(0);
})().catch(async (e) => {
    fail('fatal', e);
    result.phase = 'failed';
    await publish();
    process.exit(1);
});
