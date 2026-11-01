#!/usr/bin/env node
/**
 * End-to-end test of the crash pipeline's SERVER half, without a phone.
 *
 *   node scripts/test-crash-report.js
 *
 * Enrols a throwaway install against the pool Worker, signs a `/crash` POST exactly the way
 * `MlmPoolClient` does, and prints what came back. If GH_TOKEN and GH_REPO are set on the Worker,
 * an issue appears in the private `mlmvpn/crashes` repository within a second or two.
 *
 * Why this exists rather than "just crash the app": the app half and the server half fail for
 * completely different reasons -- a dismissed dialog, an unenrolled install and a poisoned DNS
 * answer on one side; a missing secret, a read-limited database and a bad token on the other. A
 * test that only exercises both at once cannot tell you which half is broken, which is exactly
 * the question worth answering. See docs/CRASH-REPORTS.md for the app-half test.
 *
 * It writes a real row and a real issue. The summary is prefixed so the result is obviously a
 * test and can be closed on sight; the signature is randomised per run so each run files its own
 * issue instead of commenting on the last one.
 */

const crypto = require('crypto');

const ENDPOINT = process.env.POOL_ENDPOINT
  || 'https://pool.redacted.invalid';

const sign = (secret, message) =>
  crypto.createHmac('sha256', secret).update(message).digest('hex');

async function main() {
  console.log('endpoint:', ENDPOINT);

  // 1. Enrol. Open to anyone by design -- the value of a token is capped by the rate limit, not
  //    by who may ask for one.
  const enrolled = await fetch(ENDPOINT + '/enroll', { method: 'POST' });
  const who = await enrolled.json();
  if (!enrolled.ok || !who.id) {
    console.error('enrol failed:', enrolled.status, JSON.stringify(who));
    process.exit(1);
  }
  console.log('enrolled as:', who.id);

  // 2. A report that looks like the real thing. `signatureOf` on the app side takes the exception
  //    line plus the first `com.mlmvpn` frame, so the body is shaped to produce a sane title.
  const nonce = crypto.randomBytes(3).toString('hex');
  const summary =
    `TEST java.lang.IllegalStateException: pipeline check ${nonce}  |  at com.mlmvpn.scanner.TestOnly.check(TestOnly.kt:1)`;
  const body = [
    'MLM VPN crash report (DELIBERATE TEST -- safe to close)',
    'app     : test-harness',
    `device  : scripts/test-crash-report.js run ${new Date().toISOString()}`,
    '',
    `error: java.lang.IllegalStateException: pipeline check ${nonce}`,
    '    at com.mlmvpn.scanner.TestOnly.check(TestOnly.kt:1)',
    '    at com.mlmvpn.scanner.MainActivity.onCreate(MainActivity.kt:1)',
    '',
    'breadcrumbs:',
    '  home -> settings -> about',
  ].join('\n');

  const payload = JSON.stringify({
    summary,
    body,
    app: 'test (0)',
    device: 'crash-pipeline-test',
    android: '0',
  });

  // 3. Sign it the way the app does: HMAC-SHA256 over "<id>.<ts>.<body>", hex, five-minute window.
  const ts = String(Date.now());
  const res = await fetch(ENDPOINT + '/crash', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Install': who.id,
      'X-Ts': ts,
      'X-Sig': sign(who.secret, `${who.id}.${ts}.${payload}`),
    },
    body: payload,
  });
  const out = await res.text();
  console.log('POST /crash ->', res.status, out);

  if (!res.ok) {
    // The two failures worth telling apart, because they need opposite responses.
    if (out.includes('row read limit')) {
      console.error('\nD1 is read-limited for the day. Reports cannot be accepted at all until');
      console.error('midnight UTC -- see docs/CRASH-REPORTS.md, "the failure that keeps happening".');
    }
    process.exit(1);
  }

  let parsed = {};
  try { parsed = JSON.parse(out); } catch (e) { /* printed above either way */ }
  console.log('\nsignature:', parsed.sig);
  console.log('stored in D1:', parsed.stored);
  console.log('\nNow check https://github.com/mlmvpn/crashes/issues for an issue titled');
  console.log(`"TEST java.lang.IllegalStateException: pipeline check ${nonce} ..."`);
  console.log('If nothing appears, GH_TOKEN / GH_REPO are not set on the Worker:');
  console.log('  npx wrangler secret list --name mlm-pool');
}

main().catch((e) => { console.error(e); process.exit(1); });
