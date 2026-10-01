# Cloudflare Doctor

> **v2 (2026-10-01).** The low-level probes from v1 are kept (raw TLS with ClientHello/record
> splitting, DNS wire, the VLESS/Trojan stage client, the plan and its validation, the privacy
> boundary). Rewritten around them:
>
> - **`DoctorEngine`** (replaces `AndroidDoctor`): app-scoped. Leaving the screen or the app no
>   longer cancels a run; only Stop does. Milestone progress for the screen.
> - **ST1** verdicts come from the account check's own conclusion (`AccountConclusion`), one
>   terminal row per route. Each request on the wire is kept as `A1` evidence. v1 counted the 403
>   of the check's first, wrong auth-scheme guess as "credentials rejected".
> - **ST3** native tests run in the `:xprobe` process (`NativeProbe`, via `XrayProbeService`): a
>   Go panic no longer takes the app down, a wedged core is stopped, and native tests are no
>   longer skipped while a VPN is up. Skipped variations are written to the trace with a reason.
>   Configs come from panels, the node list and the scanner's own config.
> - **ST4 (new): the IP scanner.** The scanner's three gates (TCP, the config's own first request,
>   a real request through the core) run on a fixed sample of 24 Cloudflare addresses. Layers:
>   `scan.tcp`, `scan.tls`, `scan.ws(n)`, `scan.proxy`, `scan.egress`, `scan.core`,
>   `scan.config.not_cdn`, `ok`. Evidence rows `W3` (per address) and `W4` (core).
> - **`DoctorAdvice`**: the plain findings and "what works" the screen shows, from the same rows.
> - The four stages run beside the fixed core in tier 1 instead of before it. `complete=true` only
>   when no tier or stage hit its deadline or budget.
> - UI rebuilt in the app's iOS style; the icon reshaped to the app's inset squircle.
> - Report header and summary say `v2` and carry `ST4`.


دکتر کلادفلر از پوشهٔ «ابزارها» و صفحهٔ عیب‌یابی حساب باز می‌شود. اجرا فقط با لمس کاربر شروع می‌شود، با خروج از صفحه/پس‌زمینه‌شدن برنامه متوقف می‌شود و گزارش نیمه‌کاره باقی می‌ماند. ارسال خودکار، سرور تازه و مجوز ذخیره‌سازی ندارد.

## User flow

Tools → Cloudflare Doctor → optional provider label / optional temporary credentials → Start.
The existing account troubleshooting screen passes pre-account credentials in memory. Saved
accounts are tested without changing them. Results are reviewed locally, shared through Android's
file chooser, or saved with `ACTION_CREATE_DOCUMENT`. The group button opens `https://t.me/mlmvpn`.

The artwork supplied as a 1,130,683-byte SVG contained a raster WebP. Its original artwork is retained
as a 192×192 WebP (`drawable-nodpi/ic_app_doctor.webp`, 3,874 bytes).

## Architecture and limits

`engines/cfdoctor` separates JVM code from Android adapters. `DoctorRunner` owns an 8-minute wall
clock budget, a maximum of 900 recorded attempts, six blocking probe workers, bounded tier budgets,
and a shared approximately 3 MiB traffic budget. Every raw probe owns its sockets; cancellation and
its deadline close those sockets. TCP leases are keyed by the resolved IP, including host aliases.
TLS ClientHello splitting and record splitting use `SSLEngine`; HTTP/2 uses OkHttp's real framing.
Physical Android networks are selected using `GameNetwork.pick`, with sockets and DNS bound to the
selected network. R1 alone uses the active tunnel's SOCKS port.

Baseline stages have separate time allocations so an unavailable API cannot consume the whole
panel/config diagnosis. Core network tests run before adaptive config sweeps. Budgets can prevent
some core tests from completing on a fully blocked network; the report records this rather than
claiming the missing method passed. A failed domestic/international control set terminates early.

* **ST1:** the actual `CloudManager.probeAccountStatus` / `probeCredential` functions, using a cloned
  client and physical base DNS; public API access is not authentication. Real, fixed-IP and DoH-IP
  routes are compared. A copied account and `readOnly` mode prevent persistence of the diagnostic's
  inferred auth scheme. Requests retain strict certificate validation.
* **ST2:** local panel inventory remains available when the API is blocked. BPB calls the actual
  `fetchCloudConfigs` function, with a bounded response interceptor examining status, base64 and
  `VpnConfig.parseUri` counts. EDG calls its actual local config builder and records its optional KV
  request separately. Other panels are explicitly `host_only`/limited.
* **ST3:** VLESS/Trojan over TLS WebSocket or HTTPUpgrade are tested in Kotlin, followed by native
  Xray confirmation where the core is available and its default route is the selected physical
  network. Up to three existing/fetched configs are selected, prioritizing Cloudflare and retaining
  one non-Cloudflare control when available. Fragment, address, port, SNI, fingerprint, TLS, ECH and
  xhttp variations are ephemeral and never update saved configs.

### Honest limits

* Trojan has no independent positive authentication response. Missing data before upstream output
  is `proxy_or_egress`, not a guessed password rejection. VLESS acknowledgement permits `egress` to
  be distinguished. A successful egress followed by failed inner TLS is `app.http`.
* Q1 validates QUIC Version Negotiation connection IDs. Lack of a reply is `UDP_SUSPECT`, not proof
  that UDP is blocked. Q2 (encrypted QUIC Initial), R2 (TTL), F10 (MSS) and F11 (receive-window
  tuning) are explicitly disabled pending real-device validation. No RFC 9001 implementation or
  vector-test coverage is claimed.
* Native `CombineEngine.measureDelay` cannot forcibly terminate a wedged native core. After one
  timeout no more native measurements are started. Native traffic is not observable by the raw
  byte counter; the 3 MiB limit is approximate, not a billing guarantee. Native tests are skipped
  when Android's default network differs from the physical network being measured.
* Raw F12/F13 use representative chunk lengths/delays; Xray C3 uses the precise `finalmask`
  recipes. Raw F14/F15 do not claim parity with all packet/maxSplit behavior of patched cores.
* Three direct ECH resolver variants are implemented. An extra ECH-resolver outbound through fake
  SNI/fragment is not installed. Native-core ECH support must be checked on the target phone.
* Private responses never have body, hex, raw headers, paths or exception messages in the report.
  This implementation is stricter for public responses too: it keeps numeric metadata and a
  whitelist of trace tags, not raw block-page text, hex, certificate SANs or certificate issuer
  strings. Certificate failures are failures; diagnostics never disable validation for credentials.
* Other panels' subscription-specific endpoints are not instrumented. Worker HTTP status proves
  host reachability only; it does not establish that a subscription or proxy works.
* DNS, TLS and HTTP times are available for raw probes; inherited/native helpers have less detailed
  timing. `limited`, method skips and budgets must be considered during analysis.

## Plan updates

`assets/cf_doctor_plan.json` is the fallback. The Store's existing signed Ed25519 channel supplies the
`cf-doctor-plan` DATA item, overlaid via `StoreFiles.open`. No separate update server is used.
Invalid overlay data falls back to the bundled plan.

Accepted fields: `version`, Cloudflare `ips`, allowlisted public `hosts`, HTTPS `ports`, `core`,
`mapping` from existing triage flags to existing method IDs, `maxAttempts`, `maxMs`, `maxBytes`,
`timeoutMs`, `repeats`, `tiersMs`, `slowRttMs`, `fingerprints`, `fragmentDelays`, and `fragments`.
Each fragment recipe accepts `at`, `chunk`, `delayMs`, `record`, and `sni`. Limits are clamped locally;
untrusted destinations are rejected even for signed plans. The mandatory core cannot be removed.
New probe *implementations* still need an APK update. Unimplemented experimental methods are
recorded as skips even if present in a plan.

## Report schema v1

File: `mlmvpn-doctor-YYYYMMDD-HHMMSS-OPERATOR.txt`. `cacheDir/doctor/latest.txt` is atomically updated
after each observation. It can be recovered after process death. Export copies have their own
timestamp; Config Studio's directory is unaffected.

Sections: `HEADER`, independent `ST1`/`ST2`/`ST3` verdicts, `ENV`, `TRIAGE` and decisions, one
`SUMMARY DOCTOR ...` line, `VERDICT`, `WINNERS`, `TABLES`, `RAW`. `complete=false` means interrupted
or budget-limited; it does not mean every unfinished probe failed. Always read the decision trace.

`RAW` has one JSON object per recorded attempt:

| Key | Meaning |
| --- | --- |
| `id` | Method/stage, e.g. `D2`, `ST1`, `ST3` |
| `r` | Nonsecret route label; public test IPs may be present |
| `c` | Result code; no exception message |
| `ms`, `at` | Attempt duration and offset from run start in milliseconds |
| `h` | HTTP status, if observed |
| `e` | Numeric Cloudflare error codes |
| `t` | Six-character SHA-256 prefix for a target; no private hostname/address |
| `n` | Numeric measurements: phase times, bytes, TLS reached, terminal/limited, etc. |
| `s` | Allowlisted trace/content-type tags; excludes the user's IP |

Large reports use a `COMPACT` dictionary line before `RAW`. `r` and `c` become zero-based indexes
into `routes` and `codes`, and `n` becomes an array indexed by `metrics` (`null` means absent).
Every attempt retains its status/timing/error codes. If necessary, verbose metrics are omitted
with `metrics_omitted=size_budget`; this is explicitly marked. Compact rows omit trace tags and
target hashes. The report never silently truncates a JSON line.

ENV includes network medium, a coarse operator code, Android/app/model, IPv6 availability, VPN,
validated/captive/private-DNS state, timezone/time and an optional sanitized provider label.
It does not read SSID, IMSI, subscriber number, account email/token, or the user's public IP.
Only private host hashes and aggregate config attributes may leave the diagnostic boundary.
`SecretRedactor.redact` runs on the finished document as an additional safeguard.

## Verification

Run `scripts/test-cf-doctor.ps1` for the pure Kotlin/JUnit suite using cached Kotlin dependencies on
this Windows development machine. Normal project tasks remain `:app:testDebugUnitTest` and
`:app:assembleDebug`.

Tests exercise malformed DNS, compression loops, destination allowlists, plan limits, classification,
base64/subscription errors, report privacy/size, cancellation, QUIC connection-ID validation, TLS
record splitting, WebSocket masking/ping, a real local TLS server with fragmented ClientHello,
echoed secrets, post-TLS silence, and a fake VLESS/WebSocket server with dead first egress, fallback
egress and failing inner TLS. `test-server.p12` is a deliberately public test-only certificate/key
with password `doctor-test-only`; it is not included in the APK or used by production code.

Device checks still needed: Android 7 and 14 UI/save/share flows, native Xray variants and ECH,
real restricted networks, VPN on/off, and signed Store overlay update/rollback. Simulation verifies
classification and wire behavior, not the behavior of any particular ISP's DPI.

Protocol references: [QUIC RFC 9000](https://www.rfc-editor.org/rfc/rfc9000.html),
[Trojan protocol](https://trojan-gfw.github.io/trojan/protocol).
