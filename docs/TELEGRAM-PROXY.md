# Telegram Proxy: a Telegram WEB proxy on the user's own Cloudflare account

> **خلاصه (فارسی).** کاشی «پروکسی تلگرام» روی حساب Cloudflare خود کاربر یک «WEB proxy» رسمی تلگرام
> می‌سازد (Workers یا Pages)، با یک پرسش واقعی از دیتاسنتر تلگرام آزمایشش می‌کند و با یک دکمه به
> تلگرام اضافه‌اش می‌کند. پروکسی MTProto معمولی (`tg://proxy`) و کانال حامی روی Cloudflare ممکن
> نیستند؛ علتشان پایین‌تر آمده است.

## What it is

Telegram added a proxy type of its own, the **WEB proxy** (Telegram Desktop 7.1, 2026-08-21; Telegram
for Android 12.10.x; iOS not yet). Instead of a raw TCP connection to an MTProxy, the app opens the
proxy's *bridge page* in a private WebView and talks to it over HTTPS and one WebSocket. That is the
one Telegram proxy type a Cloudflare Worker can carry, because a Worker accepts HTTP and WebSocket and
nothing else.

The link is `tg://webproxy?server=<host>&secret=<hex>`: no port (always 443), no user name. The
secret is the ordinary MTProxy secret, 32 hex characters, or `dd` + 32 hex (random padding). The
feature always makes the `dd` form. Fake-TLS (`ee…`) secrets are not offered: they need a TLS
handshake the Cloudflare edge would terminate itself.

The protocol is Telegram's published *WEB proxy protocol v1* (bridge capability, shared frames, the
`websocket` carrier). It was implemented from that description; **no code was copied** from any
repository. (The two reference repositories of the task were read for architecture only: `TWP-CF`
carries no licence at all, which means all rights reserved, and `How-to-Build-a-Camouflaged-…` is a
VPS and DNS guide that installs no proxy and uses Cloudflare for DNS only.)

## Feasibility, measured and cited

| Combination | Verdict | Why |
|---|---|---|
| Workers + WEB proxy | **Works** | The relay is one ES module on `connect()` from `cloudflare:sockets`. |
| Pages + WEB proxy | **Works** | The same file, uploaded as `_worker.js` (Advanced Mode). Pages cannot define a Durable Object, so the relay is stateless and needs none. |
| Workers or Pages + plain MTProto (`tg://proxy`) | **Not possible** | A Worker/Pages Function has no inbound TCP. Cloudflare proxies only HTTP(S) on fixed ports and terminates TLS itself, so obfuscated2 and fake-TLS never reach the script. |
| Sponsor channel (`@mlmvpn`) | **Not possible here** | The promoted channel is attached through `@MTProxybot`, which gives a 32-hex tag the *MTProxy server* must be started with. It only works through Telegram's middle proxies, which need a public, stable IP. A Worker dials the data centres directly from rotating addresses, and the WEB protocol has no field for it. A VPS running Telegram's reference server plus stock MTProxy could. Recorded as future work. |

The only real way to offer plain MTProto on top of Cloudflare is a **bridge on the phone**: MLMVPN
listens on `127.0.0.1`, speaks MTProto to Telegram locally, and carries the bytes to the same Worker
over a WebSocket. It works with any Telegram build, only on that phone, and only while MLMVPN runs.
Built as «Bridge with a clean IP»; see *The bridge on the phone* below.

## How the relay works

`app/src/main/assets/tgproxy_worker.js` (one file, ~700 lines; `scripts/test-tgproxy-worker.mjs` runs it
for real in Node).

* `GET /?bridge=<cap>`: the bridge page. `cap = base64url(HMAC-SHA256(secret, "tdesktop-web-proxy-bridge-v1\n" + host))`,
  checked against the request's own host, so it is right for a workers.dev name and a custom domain
  alike. Without it every path is a plain 404; the secret is never shown anywhere.
* `POST /api/v1/session`: one HELLO frame in, one WELCOME out, a session token in a header.
* `GET /api/v1/ws`: the multiplexed WebSocket (subprotocol `tproxy-v1.<token>`). Each logical stream is
  one Telegram TCP connection: OPEN, DATA, CLOSE, WINDOW frames, 4 MiB of credit each way, 64 KiB chunks.
* Tokens are **stateless** (a time stamp and a MAC): the POST that issues one and the WebSocket that
  uses it can land on different instances. The price: a bootstrap token can be replayed inside its two
  minutes. It is only ever handed to a caller who already knows the capability.
* Behind the socket the relay is the MTProxy: it opens the client's obfuscated2 header with the
  secret (data-centre id, transport tag), dials that data centre on port 443 with `connect()` and
  speaks obfuscated2 to it with a fresh header. That header is built so the data centre's keystream
  *equals the one the client expects*: **downloads pass through untouched**, only uploads are
  re-encrypted. (Measured with Telegram's five real data centres: all answer `res_pq` through it.)

### Staying connected

Telegram treats its WEB proxy as one carrier: if the bridge page reports a failure or its socket
closes, Telegram for Android closes every connection, waits a second, destroys the WebView and loads
the page again. That is the «connecting to proxy» line. The first relay recycled its one socket after
45 data-centre connections, and Telegram opens one for every reconnect and every media download, so
it showed every few minutes. The page now keeps the carrier whole itself:

* **Lanes.** The page carries the streams over one or more sessions. New streams go to the current
  lane; 12 connections before its budget the page opens the next lane, switches as soon as it is up,
  and closes the old one when its last stream ends. Telegram sees none of it.
* **A lane that dies closes only its own streams** (the page sends Telegram a CLOSE for each, and
  Telegram reconnects them in a moment). Only four lane losses in 30 seconds, or no way to open a
  new lane, fail the carrier.
* **Pings.** Each socket sends a PING every 20 seconds; its payload is a fresh session token. The page
  answers it (Telegram never sees it), uses the token to open later lanes (`POST /api/v1/session`
  accepts a session token up to an hour old as well as the bootstrap), and drops a lane that has
  pinged and then been silent for 65 seconds.
* **Old relays update themselves.** The service remembers a fingerprint of the script it uploaded.
  Opening the tile, or «Check again», uploads the bundled relay to a READY proxy whose fingerprint
  differs, in place: same address, same secret, so the link already in Telegram keeps working.

### Bindings

| Name | Type | Meaning |
|---|---|---|
| `PROXY_SECRET` | secret_text | 32 hex, or `dd` + 32 hex |
| `MLM_TGP_ID` | plain_text | the service id: the ownership marker checked before any change or delete |
| `MAX_DIALS` | plain_text, optional | data-centre connections per socket (default 45); past it a stream is refused, and the page has moved to a fresh socket before that |

### Limits worth knowing

* **CPU.** The free plan gives 10 ms of CPU per request. Cloudflare's documentation does not say how
  that is counted on a long-lived WebSocket. Downloads (the heavy direction) involve no cryptography;
  uploads cost two AES-CTR passes. Measure with a large download after deploying; see *On the phone*.
* **Subrequests.** The free plan allows 50 per request; the documentation does not say whether
  `connect()` counts. A socket refuses streams past `MAX_DIALS`; the page never gets there (see
  *Staying connected*). Raise `MAX_DIALS` once measured.
* **Reachability.** Telegram connects straight to `*.workers.dev` / `*.pages.dev`. If the user's network
  blocks those names, only a custom domain helps. The in-app self-test reports this separately
  ("through the app" vs "through the phone's own network").
* **Terms.** Cloudflare's Self-Serve Subscription Agreement (§2.2.1(j)) forbids using the services as a
  VPN or proxy service, and §8 allows immediate suspension. The screen says so once before the first
  build; builds are capped at three per method per account. This is an honest risk, not hidden.
* **Rate limit.** 1,200 API requests per five minutes per user; past it Cloudflare answers 429 and
  blocks all calls for five minutes. The app obeys `Retry-After` (five minutes when absent) and sends
  nothing else for that account until then.

## The bridge on the phone

«Bridge with a clean IP» on a ready proxy. Telegram is given `tg://proxy?server=127.0.0.1&port=<p>&secret=<dd…>`
-- the proxy's own secret -- and MLMVPN listens there. Each TCP connection Telegram opens becomes one
stream of the same WEB proxy protocol, so the relay needs nothing new: Telegram's obfuscated2 bytes
are carried untouched and the relay opens them as before.

* **The address.** `TgCleanIp` collects candidates, best evidence first: last time's winners on this
  network, the clean addresses the app's IP scanner kept (its archive), the edges `CfEdge` has proven
  here, and what DNS says for the relay's name (plus two IPv6 edges when there is an IPv6 route). Each
  one fetches the relay's bridge page through that address with the relay's name as SNI, twice, the
  faster kept; the quickest four must pass the full self-test (`TgProbe`: session, WebSocket, `res_pq`)
  before they are used. Winners are remembered per network and relay. Requests use the app's
  split-ClientHello socket factory toward Cloudflare and HTTP/1.1.
* **The bridge.** `TgBridge` (pure JVM, `TgBridgeTest`) mirrors the bridge page: lanes with the
  page's budget (`DIALS=`), a switch 12 dials early to a lane made beforehand, a dying lane closing only
  its own connections, PINGs answered with their token kept, a pinged-then-silent lane dropped after
  65 s. `TgWsLanes` opens the lanes through the chosen addresses, moving to the next when one fails;
  a session token is renewed with the last one, else a bootstrap comes from a fresh bridge page.
* **Staying on.** `TgBridgeService` is a foreground service (specialUse) whose notification shows the
  address and the number of connections and has an off button. The port is fixed per proxy, so the
  proxy Telegram saved keeps working; when every address keeps failing (a new network), the engine
  looks again without changing the port.

## In the app

```
engines/tgproxy/
  TgLink.kt          secret, host, capability, links (pure; checked against Telegram's published vectors)
  TgWire.kt          frames and the client end of obfuscated2 (pure; checked against Node's crypto)
  TgModels.kt        TgService, states, probe result, custom domain
  TgStore.kt         filesDir/tgproxy/state.json (sealed secrets only, written before the resource exists)
  TgCfApi.kt         the one door to Cloudflare: auth, bounded retry, 429 gate, errors without bodies
  TgWorkersOps.kt    upload, switch on workers.dev, read the ownership marker, delete, list
  TgPagesOps.kt      create project (env vars), direct upload of _worker.js, poll the deploy, delete, list
  TgDomainOps.kt     sites, DNS records, Workers/Pages custom domains
  TgProbe.kt         the self-test: page → session → socket → stream → Telegram's res_pq
  TgProxyManager.kt  build / check / delete / scan / domain, one operation at a time
  TgProxyEngine.kt   the singleton wired to the app's accounts, Keystore and route
  TgBridge.kt        the on-phone MTProxy: Telegram's connections as streams over lanes
  TgWsLanes.kt       lanes to the relay through chosen Cloudflare addresses
  TgCleanIp.kt       which Cloudflare address carries the bridge on this network
  TgBridgeEngine.kt  the bridge's state for the screen; TgBridgeService keeps it running
ui/tgproxy/          TgProxyScreen.kt, TgDomainPage.kt
```

Rules the manager keeps (each has a test):

1. **One operation at a time.** A second tap or screen while one runs is refused, never queued.
2. **Recorded before created.** The service and its sealed secret are written to the store before the
   first Cloudflare call; a build interrupted by the app dying is found again as *unfinished*.
3. **Resume before create.** An unfinished service is rebuilt in place; a new one is made only when all
   existing ones are ready, and never past three per account and method.
4. **Only our own resources.** The Cloudflare account must be the recorded one and the resource's
   `MLM_TGP_ID` must equal the service id before a check, a domain change or a delete. A resource that
   fails the test is left alone and reported.
5. **Honest state.** *Ready* only after the self-test reached Telegram through the app's route. A
   rejected credential, a removed account, a resource deleted from the dashboard, or a rate limit never
   reads as healthy.
6. **Bounded.** Pages deploy polling (10 × 3 s), the self-test (6 tries within 90 s) and every retry
   are capped. Every step can be cancelled.
7. **No secrets in the open.** The Cloudflare credential stays in the account list and goes only to
   `api.cloudflare.com`; errors carry a kind and two numbers, never a body, a header or a key. The proxy
   secret is Keystore-sealed at rest, sent to Cloudflare once as a `secret_text`, and copied to the
   clipboard marked sensitive.

### Custom domain

A five-step guide that ticks itself: the domain is a site on the account; its nameservers point to
Cloudflare; a subdomain name; the app connects it; it answers and Telegram gets the new address.
A hostname that already has a DNS record that is not ours is refused. Workers: `PUT workers/domains`
(Cloudflare makes the record and certificate). Pages: the project domain plus a proxied CNAME that
carries the comment `mlmvpn-tgp:<id>`, the only kind of record the app ever deletes. The Cloudflare
address keeps working; the link follows the domain once it answers and falls back if it stops.

## Tests

* `node scripts/test-tgproxy-worker.mjs`: the relay in Node (capability vectors, tokens, frames, flow
  control, re-keying against an independent obfuscated2 implementation, the bridge page run in a fake
  browser against the real worker). `--live` also asks Telegram's five data centres `req_pq_multi`.
* `gradlew :app:testDebugUnitTest --tests "com.mlmvpn.scanner.engines.tgproxy.*"`: links, wire,
  and the manager against a Cloudflare that is a real HTTP server on localhost (create, cancel,
  duplicate taps, 429 and `Retry-After`, 5xx retry, permissions, ownership, orphans, domains, and that
  no key, email or secret ever appears in the state, the files, a URL or a message).
* `ScoutCoverageTest` knows the tile is a tool (`ScoutRegistry.EXCLUDED`).

## What was verified, and what was not (2026-10-11)

* Verified: the relay in Node (21 offline tests; 5 live: every Telegram data centre answers `res_pq`
  through it); 59 JUnit tests of the feature and the whole suite (818 tests, 92 suites) green; the
  APK installs on the Galaxy A50; the tile appears; the key field is masked; Telegram 12.10.6 accepts a
  `tg://webproxy?server=…&secret=dd…` link and shows its own "proxy server" sheet (dismissed, nothing
  added).
* **Not yet verified: everything that needs a real Cloudflare account**, because the A50 has none:
  the Workers and Pages builds against Cloudflare itself, the custom-domain steps, Telegram actually
  connecting through a deployed relay, and the CPU / subrequest behaviour of a Worker under a large
  download. The Cloudflare calls are tested against a local stand-in built from the documented API;
  expect to adjust small details (field names, error codes) on the first real run.

## Not built (and why)

* Plain MTProto (`tg://proxy`) and the sponsor channel: not possible on Cloudflare (above).
* The `https` and lane carriers: the relay offers `websocket` only and says so in `X-Carrier-Mode`.
* iOS: Telegram for iOS does not support WEB proxies yet.
