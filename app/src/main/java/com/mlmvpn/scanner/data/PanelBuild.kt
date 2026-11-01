package com.mlmvpn.scanner.data

import com.mlmvpn.scanner.models.CloudAccount

/**
 * Which build of each panel this app ships, and which build an account is actually running.
 *
 * A deployed worker is a copy, not a link: once it is on Cloudflare it stays exactly as it was
 * uploaded, however many times the app is updated afterwards. So an account deployed a year ago
 * keeps serving that year-old panel, and every fix shipped since is invisible to it -- which is
 * why "it works for me but not for them" was so often really "their worker is old".
 *
 * Bump the constant for a panel whenever its bundled asset changes. An account whose recorded
 * number is lower is told to redeploy, and the redeploy overwrites the script it already has
 * rather than leaving the old one running beside a new one.
 */
object PanelBuild {

    const val BPB = 2
    const val EDG = 2
    const val NHN = 2
    // 3: XHTTP packet-up upload POSTs complete instead of being held open as a duplex stream.
    // 4: configs are served over WebSocket instead of XHTTP -- one connection carries both
    //    directions, so nothing depends on separate requests reaching the same Worker isolate.
    // 5: the 401 body no longer returns the admin hash (it IS the session cookie, so every
    //    unauthenticated request was handing out a working key), and PUT /api/users/<name>
    //    no longer 503s when a field is omitted.
    // 6: Config Studio. Versioned migrator, the /{STUDIO_ROUTE}/v1/* control plane with bearer
    //    auth, users/configs/subscriptions, the rebuilt subscriber page, and legacy-auth hardening.
    //
    //    THIS MUST EQUAL `STUDIO_API_VERSION` in worker-src/studio/04a-studio-api.js. The engine
    //    reports that number from /v1/health, and StudioDeployer compares it against this one to
    //    decide whether deploying would go backwards. While they disagreed -- asset at 6, constant
    //    at 5 -- every successful deploy left the account looking NEWER than the app, so the
    //    downgrade guard fired on the next attempt and the app could never redeploy again. Found by
    //    deploying to a real account; `checkEngineVersion` now fails the build if they drift.
    // 7: nodes get a health probe measured on the PHONE and reported back, and the dashboard
    //    answers the counts it could not before -- people connected right now, live configs,
    //    node health, connections in the last day, and the month's traffic. `usage_hourly.sessions`
    //    is written for the first time; it was created by the first migration and incremented by
    //    nothing, so every connection figure in the product read zero.
    // 14: the subscriber page hands over a link that is pinned to the list format. It used to print
    //    the plain `/s/{token}` -- which, for the very common case of somebody who reached the page
    //    BY opening that link in a browser, is the URL already in their address bar, and reads as
    //    the status page rather than as a subscription. Nothing was added, so this build carries no
    //    new capability; it is bumped so existing installations report as behind and the operator
    //    is OFFERED the redeploy. A fix that ships inside the APK and is never redeployed is a fix
    //    nobody gets.
    // 15: a quota is enforced while the session is open. Usage reached D1 only in 50 MB commits, so
    //    a small cap (10 MB) never moved during a session and the connection ran on indefinitely;
    //    now every byte is counted against what is left and the session ends when it runs out.
    // 16: locations. `/v1/exits` holds SOCKS5 / HTTP exits per country and a config pinned to a
    //    country leaves through one of them, with no direct fallback. Also the abuse guard: torrent,
    //    mail, private targets and connection floods are refused, which is what gets accounts banned.
    // 17: multi-location. `/v1/pool` -- public per-country servers for a location with no exit of
    //    the operator's own, OFF until the operator turns it on knowingly; pool configs are named
    //    «⚠️ عمومی» and the subscription page says what that means. Users carry a list of ports.
    // 18: one usage meter for every transport (usage reaches the user row within ~90 s and moves
    //    `updated_at`, so the app sees it; quotas stop within a few hundred KB); device limits count
    //    devices connected NOW; WebSocket early data; XHTTP served as stream-one with its own
    //    credential and a country; a location config keeps one verified exit; DNS from the Worker.
    // 19: stale-while-revalidate credentials + the first 16 KB sent unbuffered. Measured WORSE on
    //    the phone (the arena's speed download was cut off in both races, first responses slower).
    // 20: build 19 rolled back -- the data plane is build 18's again; only the number moved, so the
    //    Store offers it to accounts already on 19.
    // 21: A/B -- only the unbuffered first 16 KB from build 19 (credentials as in 18).
    //     Measured: speed back to normal (so build 19's cut-off came from the credential cache), but
    //     latency no better (205/208 ms vs 132-181) -- not kept.
    // 22: build 18's data plane again; the number moves so the Store replaces 21.
    // 23: XHTTP packet-up works on workers.dev: every request of a session is handed to that
    //     session's Durable Object (the existing SessionDO class, named `x:{session}`), so the
    //     download and the upload POSTs finally meet in one isolate. No new class, no new migration.
    // 24: the same code; the number moves so every MLM panel is redeployed WITH the SESSIONS
    //     binding. The MLM panel's own deployer (MlmDeployer) never sent it -- only Config
    //     Studio's did -- so a panel updated to 23 from there still had no object and XHTTP still
    //     carried nothing (device, 2026-10-04).
    const val MLM = 24

    /**
     * The highest schema version the bundled Config Studio worker knows how to migrate to.
     *
     * Separate from [MLM] because the two move independently, and only one of them was visible.
     * [MLM] is the API surface; this is the database. A release can add a table and an index without
     * changing a single endpoint — which is exactly what «بسته» did — and an installation left on the
     * older schema then answers 404 for a feature the app believes is there, with nothing anywhere
     * saying an update is available.
     *
     * So the Settings screen offers an update when **either** number is behind, and this constant is
     * held to the worker's own `MIGRATIONS` list by `checkEngineVersion` rather than by memory.
     */
    // 18: `users.locations`, the countries of a person's configs (multi-location, build 17).
    // 19: `configs.exit_pin*`, `pool_verified`, device limits made strict (build 18).
    const val MLM_SCHEMA = 19

    /** The dedicated DNS resolver deployed from the game screen. */
    const val DNS = 1

    /** The VPN Gate list relay deployed from the gateway screen. */
    const val RELAY = 1

    /** The MLMVPN shared config pool. */
    // 2: crash intake that survives the pool's own limits -- its own daily allowance, a D1-down
    //    fallback, no error page when the schema check fails, GitHub health in /crashes, and a
    //    closed issue reopened by a newer build. Now the fallback behind the crash collector.
    const val POOL = 2

    /** Engine keys as the rest of the app spells them. */
    fun current(engine: String): Int = when (engine.uppercase()) {
        "BPB" -> BPB
        "EDG" -> EDG
        "NHN", "NAHAN" -> NHN
        "MLM" -> MLM
        "DNS" -> DNS
        "RELAY" -> RELAY
        "POOL" -> POOL
        else -> 0
    }

    fun deployed(account: CloudAccount, engine: String): Int = when (engine.uppercase()) {
        "BPB" -> account.bpbVersion
        "EDG" -> account.edgVersion
        "NHN", "NAHAN" -> account.nahanVersion
        "MLM" -> account.mlmVersion
        "DNS" -> account.dnsVersion
        "RELAY" -> account.relayVersion
        "POOL" -> account.poolVersion
        else -> 0
    }

    fun setDeployed(account: CloudAccount, engine: String, build: Int) {
        when (engine.uppercase()) {
            "BPB" -> account.bpbVersion = build
            "EDG" -> account.edgVersion = build
            "NHN", "NAHAN" -> account.nahanVersion = build
            "MLM" -> account.mlmVersion = build
            "DNS" -> account.dnsVersion = build
            "RELAY" -> account.relayVersion = build
            "POOL" -> account.poolVersion = build
        }
    }

    fun workerUrl(account: CloudAccount, engine: String): String? = when (engine.uppercase()) {
        "BPB" -> account.workerUrl
        "EDG" -> account.edgWorkerUrl
        "NHN", "NAHAN" -> account.nahanWorkerUrl
        "MLM" -> account.mlmWorkerUrl
        "DNS" -> account.dnsWorkerUrl
        "RELAY" -> account.relayWorkerUrl
        "POOL" -> account.poolWorkerUrl
        else -> null
    }

    /**
     * The Cloudflare script name behind a deployed panel.
     *
     * Derived from the URL rather than stored separately, so accounts saved before any of this
     * existed still resolve. `https://abc123-edg.sub.workers.dev` is script `abc123-edg`.
     */
    fun scriptName(account: CloudAccount, engine: String): String? {
        val url = workerUrl(account, engine)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            android.net.Uri.parse(url).host?.substringBefore('.')?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Whether this panel is deployed but out of date.
     *
     * A recorded 0 means "deployed before the app tracked builds", which is by definition older
     * than anything it ships now -- so it counts as stale rather than as unknown.
     */
    fun isStale(account: CloudAccount, engine: String): Boolean {
        val url = workerUrl(account, engine)?.takeIf { it.isNotBlank() } ?: return false
        return deployed(account, engine) < current(engine)
    }
}
