// Copies the central Worker's modules from the Windows app's repo into this app's assets, with LF
// line ends (Git for Windows checks them out as CRLF, and both apps hash exactly what they upload).
//
//     node android/scripts/sync-gateway-assets.js        (from the monorepo root)
//
// GatewayParityTest fails when the two copies drift, and when the golden links no longer match what
// the Windows manager builds — run scripts/gen-gateway-golden.js after changing either side.
'use strict';
const fs = require('fs');
const path = require('path');

const src = path.join(__dirname, '..', '..', 'cloudflare-worker', 'edge-gateway');
const dst = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'edge_gateway');
fs.mkdirSync(dst, { recursive: true });
const names = fs.readdirSync(src).filter((n) => n.endsWith('.js'));
for (const f of fs.readdirSync(dst).filter((n) => n.endsWith('.js') && !names.includes(n))) fs.unlinkSync(path.join(dst, f));
for (const f of names) {
    fs.writeFileSync(path.join(dst, f), fs.readFileSync(path.join(src, f), 'utf8').replace(/\r\n/g, '\n'));
    console.log('copied', f);
}
