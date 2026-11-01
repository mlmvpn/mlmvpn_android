// --- GitHub Tunnel v2: the ways in that are NOT Cloudflare ---
//
// Pushed beside gt-agent.mjs (agent/relays.mjs). A runner has no inbound ports, so something
// outside has to carry the client in. The quick tunnels are Cloudflare's, and from Iran they are
// reached only through the user's own Worker — which a user without a Cloudflare account, or on a
// line where Cloudflare itself is filtered, does not have. These are free public relays the runner
// dials OUT to — an SSH reverse forward (localhost.run, srv.us, serveo) or localtunnel's own
// client — with no account anywhere: each hands back one HTTPS name whose WebSocket requests land
// on Xray's WS port, exactly as a quick tunnel's do. The client connects to that name directly.
//
// Measured 2026-10-06 from an Iranian mobile line (MCI, DNS and TLS): *.lhr.life, *.srv.us,
// *.serveo.net and *.loca.lt answered; *.trycloudflare.com and pinggy did not. Which of them
// carries a given line is for the client to measure — it tries every name published here.
//
// Each name is proven before it is published: a real WebSocket upgrade through the relay to Xray
// (101), the same request the client's Xray will make — a plain GET answering is not enough, a
// relay can serve pages and still refuse the upgrade.
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import https from 'node:https';
import path from 'node:path';

// How a relay's name looks — anything else in its output is ignored, and the client checks the
// same suffixes again before a name reaches its config (GtTransport › RELAY_HOST).
const HOST_OF = {
    lhr: /https:\/\/([a-z0-9-]{1,63}\.lhr\.(?:life|pro))\b/,
    srv: /https:\/\/([a-z0-9-]{1,63}\.srv\.us)\b/,
    serveo: /https:\/\/([a-z0-9-]{1,63}\.(?:serveo\.net|serveousercontent\.com))\b/,
    lt: /https:\/\/([a-z0-9-]{1,63}\.loca\.lt)\b/,
};
// localtunnel shows browsers a reminder page instead of the site; this header (any value) skips
// it. The client sends it too (GtXray › relayOutbound): Xray's WebSocket has a browser's User-Agent.
export const RELAY_HEADERS = { lt: { 'bypass-tunnel-reminder': '1' } };

const HEALTH_MISSES = 2;
// A relay that will not come up is left alone for a while instead of being redialled every 20 s.
const MAX_FAILS = 3;
const REST_MS = 10 * 60 * 1000;

const ANSI = /\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07/g;

/** One WebSocket upgrade through `host` to Xray: 101 is the only answer that counts. */
export function wsProbe(host, wsPath, headers = {}, timeoutMs = 10000) {
    return new Promise((resolve) => {
        let done = false;
        const finish = (v) => { if (!done) { done = true; resolve(v); } };
        const req = https.request({
            host, port: 443, path: wsPath, method: 'GET', servername: host, timeout: timeoutMs,
            headers: {
                Host: host, Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13',
                'Sec-WebSocket-Key': crypto.randomBytes(16).toString('base64'), ...headers,
            },
        });
        req.on('upgrade', (res, socket) => { socket.destroy(); finish(101); });
        req.on('response', (res) => { res.resume(); finish(res.statusCode); });
        req.on('timeout', () => req.destroy(new Error('timeout')));
        req.on('error', (e) => finish(String(e.code || e.message || e).slice(0, 40)));
        req.end();
    });
}

/**
 * The relays of one session. `onChange` is called whenever the set of live names changes after
 * [start] — the agent republishes, so the client hears about a name that moved.
 */
export function createRelays({ work, log, pexec, sleep, wsPort, wsPath, onChange = () => {} }) {
    const key = path.join(work, 'relay_key');
    const known = path.join(work, 'relay_known_hosts');
    const ssh = (...rest) => ['ssh', ['-i', key, '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=accept-new',
        '-o', `UserKnownHostsFile=${known}`, '-o', 'ServerAliveInterval=20', '-o', 'ServerAliveCountMax=3',
        '-o', 'ExitOnForwardFailure=yes', '-o', 'PasswordAuthentication=no', '-o', 'ConnectTimeout=20', ...rest]];
    const target = `127.0.0.1:${wsPort}`;
    const KINDS = {
        lhr: () => ssh('-R', `80:${target}`, 'nokey@localhost.run'),
        srv: () => ssh('-R', `1:${target}`, 'srv.us'),
        serveo: () => ssh('-R', `80:${target}`, 'serveo.net'),
        lt: () => ['npx', ['-y', 'localtunnel@2', '--port', String(wsPort), '--local-host', '127.0.0.1']],
    };
    const relays = Object.keys(KINDS).map((kind) => ({ kind, proc: null, host: null, ok: false, misses: 0, fails: 0, restUntil: 0, bringing: false }));
    let started = false;

    const live = () => relays.filter((r) => r.ok && r.host).map((r) => ({ host: r.host, kind: r.kind }));
    let lastLive = '[]';
    function changed() {
        const now = JSON.stringify(live());
        if (now === lastLive) return;
        lastLive = now;
        if (started) { try { onChange(); } catch (e) {} }
    }

    function launch(r) {
        const [exe, args] = KINDS[r.kind]();
        r.text = '';
        r.host = null;
        r.ok = false;
        r.misses = 0;
        const out = fs.openSync(path.join(work, `relay-${r.kind}.log`), 'a');
        // stdin stays an open pipe: an SSH session whose stdin hits EOF is closed by some relays.
        const p = spawn(exe, args, { stdio: ['pipe', 'pipe', 'pipe'] });
        const take = (b) => {
            const s = b.toString('utf8');
            try { fs.writeSync(out, s); } catch (e) {}
            if (r.text.length < 65536) r.text += s.replace(ANSI, '');
        };
        p.stdout.on('data', take);
        p.stderr.on('data', take);
        p.on('error', (e) => log(`relay ${r.kind}: ${e.message}`));
        p.on('exit', (code) => { log(`relay ${r.kind} exited (${code})`); try { fs.closeSync(out); } catch (e) {} });
        r.proc = p;
    }

    async function bring(r, timeoutMs = 40000) {
        r.bringing = true;
        try {
            launch(r);
            const until = Date.now() + timeoutMs;
            while (Date.now() < until && !r.host) {
                const m = r.text.match(HOST_OF[r.kind]);
                if (m) r.host = m[1].toLowerCase();
                else if (r.proc.exitCode !== null) break;
                else await sleep(500);
            }
            if (!r.host) { r.fails++; return false; }
            console.log(`::add-mask::${r.host}`);
            // The relay may need a moment between printing its name and routing it.
            const probeUntil = Date.now() + 25000;
            let status = 0;
            while (Date.now() < probeUntil && r.proc.exitCode === null) {
                status = await wsProbe(r.host, wsPath, RELAY_HEADERS[r.kind] || {});
                if (status === 101) break;
                await sleep(2000);
            }
            r.ok = status === 101;
            r.status = status;
            if (r.ok) r.fails = 0; else r.fails++;
            return r.ok;
        } catch (e) {
            r.fails++;
            return false;
        } finally {
            r.bringing = false;
            if (r.fails >= MAX_FAILS) { r.restUntil = Date.now() + REST_MS; r.fails = 0; }
            changed();
        }
    }

    function kill(r) {
        try { r.proc && r.proc.kill('SIGKILL'); } catch (e) {}
    }

    return {
        /** Generate the session's throwaway SSH key, then dial every relay: one promise each. */
        async begin() {
            try { await pexec('ssh-keygen', ['-t', 'ed25519', '-N', '', '-q', '-f', key]); } catch (e) { log(`relay key: ${e.message}`); }
            return relays.map((r) => bring(r).catch(() => false));
        },
        /** From here on, a change in the live names is the agent's to publish. */
        started() { started = true; lastLive = JSON.stringify(live()); },
        live,
        describe: () => relays.map((r) => `${r.kind}:${r.ok ? 'ok' : r.bringing ? '…' : `FAIL(${r.status || (r.host ? 'no ws' : 'no name')})`}`).join(' '),
        /**
         * The supervisor's turn: `health` — look at each live relay from the outside (two misses in
         * a row replace it); always — redial the ones whose process ended, unless resting.
         */
        async supervise(health) {
            if (health) {
                await Promise.all(relays.map(async (r) => {
                    if (r.bringing || !r.ok || !r.proc || r.proc.exitCode !== null) return;
                    if ((await wsProbe(r.host, wsPath, RELAY_HEADERS[r.kind] || {})) === 101) { r.misses = 0; return; }
                    if (++r.misses >= HEALTH_MISSES) {
                        log(`relay ${r.kind} stopped answering; replacing it`);
                        r.ok = false;
                        kill(r);
                        changed();
                        await sleep(500);
                    }
                }));
            }
            // Not awaited: a relay coming up takes up to a minute, and the supervisor has Xray and
            // the quick tunnels to look after meanwhile.
            for (const r of relays) {
                if (r.bringing || Date.now() < r.restUntil) continue;
                if (r.proc && r.proc.exitCode === null && r.ok) continue;
                kill(r);
                bring(r).catch(() => {});
            }
        },
        stopAll() { relays.forEach(kill); },
    };
}
