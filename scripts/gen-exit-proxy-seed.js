#!/usr/bin/env node
// Builds app/src/main/assets/exit_proxies/ -- the offline proxy list of «کشور خروجی با پراکسی
// رایگان» (core/warp/Ipv4Exits.kt) -- from the desktop's full copy of freeproxy.world
// (G:\ip scanner\data\free-proxies\freeproxy-world.json, refreshed by the desktop's
// scripts/update-freeproxy-seed.js). Every row the desktop ships, nothing dropped: the page states
// the same count on both platforms (2026-10-13: 47,947 proxies in 145 countries).
//
// One file per country plus an index, so the phone reads only the country it needs: parsing the
// whole list at once would hold ~50k objects on a low-end phone for a scan that looks at one country.
//
//   exit_proxies/index.json   {source, fetchedAt, total, countries: {CC: {s5, h, s4}}}
//   exit_proxies/c/CC.json    [[ip, port, kind, speedMs|null, city], ...]   kind: 's' SOCKS5, 'h' HTTP CONNECT
//                                                                          (http and https rows), '4' SOCKS4
//
// Rows keep the site's order within a type, fastest listed first; SOCKS5 first, then HTTP, then
// SOCKS4 -- the order a scan tries them in after the live lists.
//
//   node scripts/gen-exit-proxy-seed.js [path/to/freeproxy-world.json]

'use strict';

const fs = require('fs');
const path = require('path');

const src = process.argv[2] || path.join(__dirname, '..', '..', 'data', 'free-proxies', 'freeproxy-world.json');
const dir = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'exit_proxies');

const full = JSON.parse(fs.readFileSync(src, 'utf8'));
fs.rmSync(dir, { recursive: true, force: true });
fs.mkdirSync(path.join(dir, 'c'), { recursive: true });

const index = {};
let total = 0;
for (const [cc, c] of Object.entries(full.countries || {})) {
    if (!/^[A-Z]{2}$/.test(cc)) continue;
    const rows = [];
    const seen = new Set();
    const counts = { s5: 0, h: 0, s4: 0 };
    for (const [type, kind, slot] of [['socks5', 's', 's5'], ['https', 'h', 'h'], ['http', 'h', 'h'], ['socks4', '4', 's4']]) {
        const list = (c[type] || []).slice().sort((a, b) => (a[2] == null ? 99999 : a[2]) - (b[2] == null ? 99999 : b[2]));
        for (const [ip, port, speedMs, city] of list) {
            const k = `${ip}:${port}`;
            if (seen.has(k) || !/^\d{1,3}(\.\d{1,3}){3}$/.test(ip) || !(port > 0 && port < 65536)) continue;
            seen.add(k);
            rows.push([ip, port, kind, speedMs == null ? null : speedMs, String(city || '').slice(0, 24)]);
            counts[slot]++;
        }
    }
    if (!rows.length) continue;
    fs.writeFileSync(path.join(dir, 'c', `${cc}.json`), JSON.stringify(rows));
    index[cc] = counts;
    total += rows.length;
}
fs.writeFileSync(path.join(dir, 'index.json'), JSON.stringify({ source: 'freeproxy.world', fetchedAt: full.fetchedAt || null, total, countries: index }));
const bytes = fs.readdirSync(path.join(dir, 'c')).reduce((n, f) => n + fs.statSync(path.join(dir, 'c', f)).size, 0);
console.log(`seed: ${total} proxies in ${Object.keys(index).length} countries (desktop total ${full.total}) -> ${dir} (${(bytes / 1024).toFixed(0)} KB)`);
