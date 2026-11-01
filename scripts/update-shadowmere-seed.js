#!/usr/bin/env node
/**
 * Refresh the Shadowmere keys shipped in the APK (Outline's offline copy: shadowmere.xyz is filtered
 * in Iran, so a phone reaches it only through one of the app's tunnels).
 *
 *   node scripts/update-shadowmere-seed.js
 *
 * Run it on a machine that reaches the site (through a VPN in Iran), before a release. It reads
 * every page of the API's working keys and writes app/src/main/assets/outline/shadowmere_seed.json
 * in the API's own page shape, which the app parses with OutlineParser.parseShadowmerePage. A list
 * with fewer than 20 keys is refused rather than shipped.
 *
 * The User-Agent matters: Cloudflare's bot check in front of the site turns away curl's and
 * Java's default ones (403 challenge).
 */
'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');

const API = 'https://shadowmere.xyz/api/proxies/?is_active=true&page=';
const OUT = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'outline', 'shadowmere_seed.json');
const MIN_KEYS = 20;
const KEEP = ['url', 'location', 'location_country_code', 'last_checked', 'times_checked', 'times_check_succeeded'];

function fetchJson(url) {
    return new Promise((resolve, reject) => {
        const req = https.get(url, { timeout: 30000, headers: { 'User-Agent': 'MLMVPN-Outline' } }, (res) => {
            if (res.statusCode !== 200) { res.resume(); return reject(new Error(`HTTP ${res.statusCode} for ${url}`)); }
            const chunks = [];
            res.on('data', (c) => chunks.push(c));
            res.on('end', () => {
                try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (e) { reject(e); }
            });
        });
        req.on('timeout', () => req.destroy(new Error('timeout')));
        req.on('error', reject);
    });
}

(async () => {
    const first = await fetchJson(API + 1);
    const results = [...first.results];
    for (let p = 2; p <= first.total_pages; p++) results.push(...(await fetchJson(API + p)).results);
    const seen = new Set();
    const keys = results
        .filter((r) => r.is_active && !seen.has(r.url) && seen.add(r.url))
        .map((r) => Object.fromEntries(KEEP.map((k) => [k, r[k]])));
    if (keys.length < MIN_KEYS) throw new Error(`only ${keys.length} keys in the list; not shipping it`);
    const snapshot = keys.map((k) => k.last_checked).sort().pop();
    fs.mkdirSync(path.dirname(OUT), { recursive: true });
    const body = { snapshot, count: keys.length, total_pages: 1, results: keys };
    fs.writeFileSync(OUT, JSON.stringify(body, null, 0).replace(/},{/g, '},\n{') + '\n');
    console.log(`${keys.length} keys written, last checked ${snapshot}`);
})().catch((e) => { console.error(e.message); process.exit(1); });
