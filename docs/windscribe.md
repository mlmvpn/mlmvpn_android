# Windscribe integration

Implemented in MLMVPN on 2026-10-10. This module uses Windscribe's actual account APIs and server inventory. It does not include accounts, static VPN servers, embedded API signing secrets, or simulated measurements.

## Research and architecture

Before implementation the existing Compose navigation, home destination registry, Proton integration, Amnezia/WireGuard configuration, TunnelBear/OpenVPN repository, VPN ownership, localization, and secure storage were inspected. The comparison projects supplied by the user were also inspected: Defyx's connectivity testing and PattN/PattNG's method/config support. Their provider-specific transports cannot automatically be applied to a Windscribe endpoint; the endpoint must support the selected protocol.

The implementation reuses:

| Existing component | Windscribe use |
|---|---|
| `HomeDestinations`, `AppScreen`, `ActiveEngines` | Native home tile, hosted screen, verified connection indicator |
| `IosScreen` and settings components | Existing light/dark Compose appearance |
| `OpenVpnVault` | Atomic AES-256-GCM files backed by Android Keystore, outside backups |
| `MyVpnService`, `WgConfig`, `AwgHostClient` | Actual WireGuard tunnel, protected upstream, interface/routing/DNS, handshake statistics |
| `OpenVpnService`, `ProfileImporter`, `ProfileRuntime` | Existing native OpenVPN core and encrypted profiles |
| `OpenVpnSplitRelay` | Optional TCP segmentation using the server's advertised endpoint/port |
| `TunnelExclusion`, `GameSessionOwner` | Existing VPN handoff and cancellation of queued starts |
| `FluxNet` | Underlying network identity and DNS information |
| `LastEngineReplay` | Replay through the Windscribe coordinator; only a marker goes in ordinary preferences |

```text
Windscribe
├── WsApi: official WSNet authentication, session, account and configuration API
├── WsRepository: accounts, inventory, encrypted config secrets and per-network history
├── WsContract / WsPlanner: provider contracts, available methods and measured ranking
├── WindscribeEngine: sequential connection/disconnection and VPN-bound measurement
└── WindscribeScreen: native login, accounts, email, protocol, server and result UI
```

Network and vault work run on `Dispatchers.IO`. Public StateFlows contain display metadata, not session hashes, passwords or private keys. Account changes cancel and join existing connection work before opening a replacement. Pending service starts carry an ownership token; cancelling it invalidates queued starts, and delayed stops are fenced to their owner. Default OpenVPN callers without a token retain their existing behavior.

## Official sources and binary provenance

The unmodified `app/libs/wsnet.aar` comes from the [official Android repository](https://github.com/Windscribe/Android-App/tree/main/base/src/main/libs). Research snapshots:

- Android-App main: `2c585dd5979ba93655853d56d849ed8ad2c964cb`
- wsnet master: `c63bf287d79559bb8a6a8d5e32f843250c748309`
- AAR SHA-256: `5b0145d526aa10f6b7ec094639bfc8dc2f187d76d1b70915cf4da4802b822f11`

See [Windscribe/wsnet](https://github.com/Windscribe/wsnet) for networking source and build instructions. Source contracts were checked against `ApiCallManager`, the official location/server response models, `WgConfigRepository`, `WireguardUtil`, `VPNProfileCreator`, and `VpnProfile` in the [Android source](https://github.com/Windscribe/Android-App). Binary license/attribution is in `third_party/windscribe`.

## Authentication and registration

Native sign-in follows the official two-stage flow:

1. `authTokenLogin(username, asciiCaptcha=true)` obtains the secure login token and any ASCII CAPTCHA challenge.
2. The user enters the challenge when required. `login` submits username, password, optional two-factor code, token and CAPTCHA response.
3. The returned `session_auth_hash` is stored in the encrypted account vault. The login password is kept only in memory while the request/challenge is pending and is not persisted. The Compose password field clears when submitted.
4. `session` refreshes the account snapshot. A provider expiration error `701` invalidates the local session and asks for sign-in again. There is no invented OAuth refresh token or expiry interval.

The current official registration implementation uses a web/integrity flow. Create account opens [Windscribe signup](https://windscribe.com/signup) in the user's browser; users return to MLMVPN to sign in. Passwords are not collected in an embedded WebView. Registration is therefore browser based, while normal account and VPN operations are native.

API failures are mapped to local error categories. Raw API error text, callback JSON and credentials are never shown or logged by this module. Native WSNet logging is a no-op; API response logging is explicitly disabled. TLS validation is enabled (`setIgnoreSslErrors(false)`). No credentials are sent to analytics or crash reporting.

## Accounts, quota and email

Multiple accounts may be added and manually selected. There is no account-count limit in the UI and no automatic account rotation. An exhausted or unavailable active account produces an error; the coordinator never switches to another account to obtain additional free data. Signing out requests `deleteSession` and removes the local account even if the remote request is unavailable; in that case the network error remains visible.

The account API supplies username, email, email verification, paid/free status, account status, data allowance, consumption, and any reset field actually returned. Remaining bytes are the display calculation `max(traffic_max - traffic_used, 0)`, based on those provider values. Unknown quota stays unknown. A cached snapshot includes its retrieval time. The screen refreshes an active valid account when opened, offers explicit refresh, and always refreshes before a new connection.

Email uses `addEmail` and `confirmEmail`; refresh checks actual `email_status`. Sending an email does not locally mark the account as verified. The UI shows `last_reset` only if provided; it does not infer the next reset date or a countdown from a previous reset.

The reused vault has a 16 MiB authenticated file-size safety bound; this is not a UI account-count restriction. Corrupt or inaccessible encrypted data fail closed without replacing the existing store with an empty/plaintext file.

## Real locations, inventory and ports

`getLocations` supplies the v2 location/datacenter catalog. `getServers` supplies node inventory. They are joined by `dc_id`, retaining provider IDs, hostname, country, city, datacenter, load/weight, endpoint addresses, WireGuard public key and OpenVPN certificate identity. Disabled datacenters and `fd=1` nodes are excluded. Free/paid and `alc` entitlement filtering use the active account's API data.

`portMap` supplies the supported protocol names and ports. Only an advertised port and a compatible node can enter the plan. The protocol picker also requires a packaged native engine. Selecting a city chooses its least-loaded advertised node. There is no fallback to a static catalog if retrieval fails; the previous dated cache remains available for display.

## Protocols and VPN engines

Measured on the user's home Wi-Fi in Iran (2026-10-10): every Windscribe address is blackholed -- no TCP
connect on any advertised port of `ip`, `ip2` or `ip3`, no ping -- while the API answers through WSNet's
own failover. So every protocol can be carried from inside Cloudflare, end to end with Windscribe:

| Protocol | How it connects | Carrier on a blocked line |
|---|---|---|
| WireGuard | Imported as one config per datacenter into the Amnezia engine (`AmzEngine.importConfs`), connected with `only = Windscribe's ids`, like Proton | The Amnezia engine tries the node straight, then inside Cloudflare WARP (`AmzCarrier`) |
| OpenVPN TCP | Existing `OpenVpnService`, remote rewritten to a loopback relay (`WsCfRelay`) | Shared WARP session (`WsWarpCarrier`), else the user's Cloudflare configs (`ProtonCfCarrier`) |
| Stealth | Official proxy (`libwsproxy.so`, `:wsproxy`) dialling `https://127.0.0.1:<relay>`; it sets InsecureSkipVerify, OpenVPN inside still checks the server certificate | Same as OpenVPN TCP |
| WStunnel | Official proxy, `wss://127.0.0.1:<relay>/tcp/127.0.0.1/1194`; `portMap` is asked with `forceProtocols = ["wstunnel"]` (the official call), or WStunnel never appears | Same as OpenVPN TCP |
| OpenVPN UDP | `OpenVpnService`, remote rewritten to a loopback UDP port (`WsUdpRelay`) | Shared WARP session's SOCKS5 UDP ASSOCIATE |

Address use follows the official `VPNParameters`: WireGuard and Stealth `ip3`, WStunnel `ip`, OpenVPN `ip2`.

**One WARP session.** Cloudflare keeps one session per WARP key. A second WARP core of this app with the
same key takes the session from the first (measured: every test after one OpenVPN UDP core timed out). So
`WsWarpCarrier` is one shared, ref-counted core (SOCKS in with TCP and UDP, WARP out), kept a minute after
its last user and checked with one request before reuse; UDP users go through `WsUdpRelay` instead of a
dokodemo door of their own; and the shared core is closed before the Amnezia engine's own WARP tunnel takes
the key (WireGuard connects).

**No loop.** The OpenVPN tunnel captures everything, the carrier's core included. `OpenVpnBypass` holds the
WARP edge address; `OpenVpnService.addRoute` and the IPv6 catch-all are split around it (Android 11 has no
`excludeRoute`), so the carrier's packets take the real network.

**Order.** WireGuard once (the Amnezia engine falls back across nodes itself), then the TCP family and
OpenVPN UDP through Cloudflare, then straight only to addresses the line reaches (a 3 s TCP connect per
address decides), plain and with split opening bytes / padded TLS. Servers near Iran first
(`WsPlanner.ORDER`: Turkey and the Gulf, Europe, then the rest; measured Dallas 2.1 s vs Paris 0.2 s).

## Method discovery, ECH and saved routes

There are two distinct searches:

1. **Account/configuration API reachability:** official WSNet performs its failover, including ECH/CDN routes documented in [Windscribe anti-censorship](https://github.com/Windscribe/Android-App/blob/main/docs/features/ANTI_CENSORSHIP.md). MLMVPN retries transport failures with automatic/alternate/regular inventory modes and API TLS padding. Authentication errors are not retried as network failures. Successful settings are encrypted per underlying network. Search again resets native failover and the remembered VPN method. ECH here applies to supported HTTPS API routes, not to ordinary WireGuard or OpenVPN packets.
2. **VPN connectivity:** try only advertised protocol/port/node combinations, ordinary WireGuard and WireGuard junk-packet padding, ordinary OpenVPN UDP, and ordinary/segmented OpenVPN TCP. WireGuard padding preserves standard message headers and server compatibility. TCP segmentation is a transport technique whose effectiveness depends on the network; it does not claim guaranteed circumvention.

The ordinary connect action first tries the saved method if it is still available. Otherwise discovery explores up to four datacenters and 24 combinations. The method order reaches alternate protocols before spending the entire budget on WireGuard servers. An explicitly selected server/protocol limits that search. A method is saved only after actual tunnel traffic succeeds, separately for each account and underlying network. Measurements are also stored in that scope. Saved methods are tried, not assumed to work forever.

## Measurement and best connection

**«تست همهٔ سرورها با همهٔ پروتکل‌ها»** (`connect(all = true)`, `WsPlanner.matrix`): every datacenter's
least-loaded node with each protocol once, the way a connect reaches it on a filtered line. Each is really
connected and measured: five HTTPS requests to `generate_204` about 0.25 s apart (ping = median, stability
= share answered) and a 1 MB download capped at 8 s (speed from the bytes that arrived, at least 128 KB).
The table ranks steadiness first, then speed, then ping (`WsPlanner.SCORE`); a row's tap connects exactly
that combination (`connect(exact = ...)`), which is then remembered for the network.

WireGuard is measured without the device VPN: `WsWgProbe` runs the node's WireGuard (same keys, same Xray
WireGuard as the connect) in an in-process core whose UDP goes through `WsUdpRelay` into the shared WARP
session, and the requests use its HTTP/SOCKS port. The OpenVPN family has to bring the VPN up each time.
Measured: about 70 s per server (WireGuard ~8 s, each OpenVPN-family protocol 11-19 s: ~1 s setup, ~2 s
handshake through WARP, ~1.4 s the service's own check, ~7 s measuring), so all 25 free servers take about
half an hour; one server alone (`allOf`) about a minute. Only tests write the table; a connect never wipes it.

## Security and persistence

- AES-GCM/Android Keystore for account session hashes, generated WireGuard keys, OpenVPN credentials/configs, caches and saved methods.
- Atomic authenticated writes; fail closed on corruption. Storage is under `noBackupFilesDir`.
- No saved login password, ordinary-preference token, raw config in `LastEngine`, raw API log, API signing secret, disabled certificate validation or analytics payload containing credentials.
- Provider OpenVPN profiles remain in the existing encrypted OpenVPN repository. Removing accounts purges unused provider profiles; an active profile is not removed underneath a running service.
- Strong references retain WSNet JNI peers and cancellable callback handles. Cancellation reaches the native callback handle and does not silently start another retry.
- The bundled library is the official binary with its attribution/license retained. TLS defaults remain those of the official implementation.

## Verification

The deterministic JVM suite covers API error redaction, unknown/known quota, v2 inventory joining, free/paid/disabled/revoked filtering, real advertised ports, reuse of remembered methods, official WG address derivation, certificate-preserving OpenVPN templates, generic loopback relay configuration, bounded protocol fairness and reliability ranking. Existing OpenVPN profile/policy tests are included in the Gradle verification command.

```powershell
./scripts/test-windscribe.ps1
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest `
  --tests 'com.mlmvpn.scanner.engines.windscribe.*' --tests 'com.mlmvpn.scanner.openvpn.*' `
  '-PscoutDeviceRunner=com.mlmvpn.scanner.engines.windscribe.WindscribeDeviceInstrumentation' `
  --offline --no-daemon --console=plain
```

`WindscribeDeviceInstrumentation` uses a unique isolated app storage directory, never real accounts. It checks Android Keystore encryption/roundtrip, account selection/removal/reload, expired sessions, per-account/network isolation, WSNet native initialization, disabled response logs and corrupt-vault rejection. Optional `-e apiProbe true` sends an explicitly invalid session to the real API: a genuine provider expiration response is a pass; network failure is reported unavailable, not a fake pass.

Final build/device results are recorded below after verification. A valid user account is required to verify successful real login, CAPTCHA/2FA, email delivery, live entitled inventory, every VPN protocol, throughput, Wi-Fi/mobile transitions and service recovery against Windscribe. No real account credentials were supplied for those checks, and local fixtures must not be mistaken for them.

## Known limitations / future improvements

- Browser registration; native CAPTCHA sign-in and account operations.
- IKEv2 is not offered (no IPsec engine in the app).
- A connection carried by a public WARP account shares that account's session with other users of the same
  list; the Amnezia engine registers an own WARP identity when it can (it timed out on the test line).
- Stability: every 30 s a real request goes through the tunnel; three misses (~40 s), a core that ended or
  hangs reconnecting for 30 s are repaired by a fresh connect, with a growing pause (10 s to 2 min), until the
  user disconnects; never while another engine or VPN app holds the VPN (`takenOver`). Not yet watched over
  many hours on a phone.
- Split tunnel and LAN sharing: OpenVPN applies the app-wide list (`SplitTunnelSettings`) and serves the Local
  Port (`OpenVpnSocks`, share password and blocked devices respected); direct WireGuard writes the list into
  the AmneziaWG config. LAN sharing over OpenVPN is TCP only (no SOCKS UDP).

