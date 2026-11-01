#!/usr/bin/env node
/**
 * Deploys the crash collector (worker-src/crash) to a Cloudflare account through the REST API --
 * no wrangler, the same calls scripts/deploy-game-crowd.mjs makes.
 *
 *   node scripts/deploy-crash-worker.mjs <credentials.txt> <secrets.json>
 *
 * <credentials.txt>: line 1 the account e-mail, line 2 its Global API Key. Read, never printed.
 *   Use an account the Quick Connect pool does NOT run on: the point of a separate collector is
 *   that the pool's traffic cannot spend its limits (Worker requests are counted per account).
 *
 * <secrets.json>: created on the first run, then filled in by hand and reused on every later run,
 *   so a redeploy keeps the same worker and tokens. Keep it outside the repository:
 *     ghToken     a fine-grained GitHub token: "Issues: read and write" on ghRepo, nothing else
 *     ghRepo      the PRIVATE repository issues go to (default mlmvpn/crashes)
 *     adminToken  generated; opens /health
 *     workerName  generated; the collector's address is https://<workerName>.<subdomain>.workers.dev
 *
 * Before uploading it checks that the token can reach the repository and that the repository is
 * private (a stack trace names internal classes and the screens a user walked through). After,
 * it asks /health whether the Worker sees its secrets, and if the address differs from
 * CrashClient.ENDPOINT it rewrites that line, so the next build sends there.
 *
 * Idempotent: the KV namespace is found by title before one is created, and the upload replaces
 * the previous version in place.
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const ROOT = path.resolve(import.meta.dirname, '..');
const WORKER = path.join(ROOT, 'worker-src/crash/worker.js');
const CLIENT = path.join(ROOT, 'app/src/main/java/com/mlmvpn/scanner/crash/CrashClient.kt');
const KV_TITLE = 'mlm-crash-state';
const COMPAT_DATE = '2026-09-01';

const [credPath, secretsPath] = process.argv.slice(2);
if (!credPath || !secretsPath) {
  console.error('usage: node scripts/deploy-crash-worker.mjs <credentials.txt> <secrets.json>');
  process.exit(2);
}

function loadOrCreateSecrets() {
  if (fs.existsSync(secretsPath)) return JSON.parse(fs.readFileSync(secretsPath, 'utf8'));
  const s = {
    ghToken: '',
    ghRepo: 'mlmvpn/crashes',
    adminToken: crypto.randomBytes(24).toString('hex'),
    workerName: 'mlm-crash',
    note: 'Crash collector secrets. Fill in ghToken (fine-grained: Issues read+write on ghRepo only). Keep out of git.',
  };
  fs.writeFileSync(secretsPath, JSON.stringify(s, null, 2));
  return s;
}

const secrets = loadOrCreateSecrets();
if (!secrets.ghToken) {
  console.error(`${secretsPath} was created. Put a GitHub token in "ghToken" and run this again.`);
  console.error('Token: github.com -> Settings -> Developer settings -> Fine-grained tokens -> repository');
  console.error(`${secrets.ghRepo}, permission "Issues: Read and write". Note its expiry date: when it`);
  console.error('expires, /health says so and reports wait on the phones until it is replaced.');
  process.exit(2);
}

// ── GitHub, first: a collector that cannot file is worse than none, because the app trusts it ──
{
  const res = await fetch(`https://api.github.com/repos/${secrets.ghRepo}`, {
    headers: {
      Authorization: 'Bearer ' + secrets.ghToken,
      Accept: 'application/vnd.github+json',
      'User-Agent': 'mlmvpn-crash-deploy',
    },
  });
  const repo = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`the token cannot see ${secrets.ghRepo}: HTTP ${res.status} ${repo.message || ''}`);
  if (!repo.private) throw new Error(`${secrets.ghRepo} is public. Crash reports must go to a private repository.`);
  console.log('github ok:', secrets.ghRepo, '(private)');
}

// ── Cloudflare ──────────────────────────────────────────────────────────────────────────────
const [email, key] = fs.readFileSync(credPath, 'utf8').split(/\r?\n/).map((s) => s.trim());
if (!email || !key) throw new Error('credentials file needs the e-mail on line 1 and the key on line 2');
const API = 'https://api.cloudflare.com/client/v4';
const auth = { 'X-Auth-Email': email, 'X-Auth-Key': key };

async function cf(method, p, body, headers = {}) {
  const res = await fetch(API + p, {
    method,
    headers: { ...auth, ...(body && !(body instanceof FormData) ? { 'content-type': 'application/json' } : {}), ...headers },
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
  });
  const j = await res.json().catch(() => ({}));
  if (!res.ok || j.success === false) {
    throw new Error(`${method} ${p} -> HTTP ${res.status} ${JSON.stringify(j.errors || j).slice(0, 400)}`);
  }
  return j.result;
}

const accounts = await cf('GET', '/accounts?per_page=5');
if (!accounts.length) throw new Error('no account visible to this key');
const accountId = accounts[0].id;
const subdomain = (await cf('GET', `/accounts/${accountId}/workers/subdomain`)).subdomain;

const namespaces = await cf('GET', `/accounts/${accountId}/storage/kv/namespaces?per_page=100`);
let kv = namespaces.find((n) => n.title === KV_TITLE);
if (!kv) {
  kv = await cf('POST', `/accounts/${accountId}/storage/kv/namespaces`, { title: KV_TITLE });
  console.log('created KV namespace', kv.id);
} else {
  console.log('KV namespace exists', kv.id);
}

const metadata = {
  main_module: 'worker.js',
  compatibility_date: COMPAT_DATE,
  bindings: [
    { type: 'kv_namespace', name: 'STATE', namespace_id: kv.id },
    { type: 'secret_text', name: 'GH_TOKEN', text: secrets.ghToken },
    { type: 'secret_text', name: 'GH_REPO', text: secrets.ghRepo },
    { type: 'secret_text', name: 'ADMIN_TOKEN', text: secrets.adminToken },
  ],
  observability: { enabled: true, head_sampling_rate: 1 },
};
const form = new FormData();
form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
form.append('worker.js', new Blob([fs.readFileSync(WORKER, 'utf8')], { type: 'application/javascript+module' }), 'worker.js');
await cf('PUT', `/accounts/${accountId}/workers/scripts/${secrets.workerName}`, form);
await cf('POST', `/accounts/${accountId}/workers/scripts/${secrets.workerName}/subdomain`, { enabled: true, previews_enabled: false });

const endpoint = `https://${secrets.workerName}.${subdomain}.workers.dev`;
console.log('deployed', secrets.workerName);
console.log('endpoint', endpoint);

// A new workers.dev name can take a little while to answer everywhere.
let health = null;
for (let i = 0; i < 10 && !health; i++) {
  const res = await fetch(`${endpoint}/health?k=${secrets.adminToken}`).catch(() => null);
  if (res && res.ok) health = await res.json().catch(() => null);
  else await new Promise((r) => setTimeout(r, 3000));
}
if (!health) console.log('health: no answer yet; try', `${endpoint}/health?k=<adminToken from ${secretsPath}>`);
else console.log('health:', JSON.stringify(health.github));

const client = fs.readFileSync(CLIENT, 'utf8');
const line = /const val ENDPOINT = "([^"]*)"/;
const current = (client.match(line) || [])[1];
if (current !== endpoint) {
  fs.writeFileSync(CLIENT, client.replace(line, `const val ENDPOINT = "${endpoint}"`));
  console.log(`CrashClient.ENDPOINT was ${current}; now ${endpoint}. Commit it and build.`);
} else {
  console.log('CrashClient.ENDPOINT already points here.');
}
