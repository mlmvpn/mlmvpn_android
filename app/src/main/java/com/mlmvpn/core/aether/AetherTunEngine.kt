package com.mlmvpn.core.aether

import android.content.Context
import com.mlmvpn.core.warp.IVpnEngine
import com.mlmvpn.scanner.tun.Tun2proxyClient
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-device Aether tunnel: VpnService TUN → native `tun2proxy` → the Aether process's
 * local SOCKS5 → Cloudflare.
 *
 * This is what makes the Android side behave like the Windows app. On its own the Aether
 * binary only publishes a SOCKS5 listener on 127.0.0.1, which nothing routes to
 * automatically — the user would have to point a separate proxy client (v2rayNG and
 * friends) at it. Bridging the TUN fd into that listener is what captures every app's
 * traffic without any second app involved.
 *
 * Wired exactly like [com.mlmvpn.scanner.engines.gst.GstTunEngine], which does the same
 * job for the GST core. The differences are that the upstream here is an out-of-process
 * binary rather than an in-process Rust runtime, and that we have to wait for it to
 * actually finish scanning before tun2proxy has anything to dial.
 *
 * @param openTun called only once the engine is actually carrying traffic; must establish the
 *   VpnService interface and return the RAW detached fd (or <= 0 on failure). Deliberately a
 *   callback rather than an fd handed in up front: a TUN that exists before the tunnel does
 *   blackholes every app on the device for the whole scan — up to two minutes of "no
 *   internet" that looks exactly like a failed connect. tun2proxy takes ownership of the fd
 *   (`--close-fd-on-drop true`), so the caller must NOT close it and must drop its
 *   ParcelFileDescriptor reference.
 * @param mtu the MTU used on the VpnService Builder.
 */
class AetherTunEngine(private val openTun: () -> Int, private val mtu: Int) : IVpnEngine {

    private var engine: AetherEngine? = null
    private val tun2proxyRunning = AtomicBoolean(false)

    /** Needed in [stop] to signal the `:tun` process. Captured in [start]. */
    private var appContext: Context? = null

    /**
     * The raw TUN descriptor handed to tun2proxy. Since tun2proxy no longer closes it, this
     * engine is its sole owner and closes it once — after the worker has actually exited.
     */
    private var ownedTunFd: Int = -1

    /**
     * Idempotency guard. MyVpnService calls stop() from both the ACTION_STOP path and
     * onDestroy(), and Android can revoke the VPN out-of-band; those run on different
     * threads. Running the native tun2proxy teardown twice races two threads through freed
     * native state and crashes some seconds later, far from the cause — the same guard
     * exists in GstTunEngine for exactly this reason. First caller wins.
     */
    private val stopped = AtomicBoolean(false)

    /**
     * When the TUN was handed to the `:tun` process, or 0 if it never was.
     * Read by [stop] to refuse tearing the tunnel down while native init is still in flight
     * — see [MIN_UPTIME_MS].
     */
    @Volatile
    private var tun2proxyStartedAt = 0L

    /**
     * @param config JSON produced by [buildConfig] — the AetherOptions, serialized, because
     *   IVpnEngine only passes a String through.
     * @param localPort ignored: the Aether binary's SOCKS port is fixed at
     *   [AetherEngine.AETHER_SOCKS_PORT] and the app's other probes already assume it.
     */
    override suspend fun start(context: Context, config: String, localPort: Int): Boolean {
        val opts = try {
            parseConfig(config)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "bad config: ${t.message}")
            return false
        }

        // Clear any abort left over from a previous attempt before we start waiting on it.
        abortRequested = false

        val eng = AetherEngine.get(context)
        engine = eng

        if (!eng.start(opts) { msg -> android.util.Log.e(TAG, "engine start failed: $msg") }) {
            return false
        }

        // Wait for the SOCKS listener to actually accept a connection BEFORE the TUN exists.
        // Aether has to discover and validate a gateway first: a cold «متعادل» scan sweeps
        // ~2000 candidates on a 120s budget. Establishing the interface up front means every
        // app on the device is routed into a tunnel with nothing on the far end for that
        // whole window — total loss of connectivity that reads as "it never connects".
        if (!awaitSocks(eng, opts.scan)) {
            android.util.Log.e(TAG, "Aether never reached a connected state — aborting tunnel")
            // Only speak up if the engine hasn't already explained itself. When the Rust
            // side reported its own failure, overwriting it with a generic timeout message
            // would replace a specific diagnosis with a vague one.
            if (eng.state.value.stage != AetherStage.FAILED &&
                eng.state.value.stage != AetherStage.CRASHED
            ) {
                eng.reportFailure(
                    "سرور سالمی پیدا نشد",
                    "حالت اسکن «${opts.scan.displayFa.substringBefore(" —")}» — دوباره تلاش کنید یا حالت دیگری انتخاب کنید",
                )
            }
            eng.stop()
            return false
        }

        // Engine is carrying traffic; now it is safe to capture the device's.
        val rawTunFd = try { openTun() } catch (t: Throwable) {
            android.util.Log.e(TAG, "openTun threw: ${t.message}")
            eng.reportFailure("ساخت رابط VPN با خطا مواجه شد", t.message ?: "")
            eng.stop()
            return false
        }
        if (rawTunFd <= 0) {
            android.util.Log.e(TAG, "TUN establish failed (fd=$rawTunFd) — VPN permission?")
            eng.reportFailure("رابط VPN ساخته نشد", "اجازهٔ VPN را بررسی کنید")
            eng.stop()
            return false
        }

        // `--dns virtual` makes tun2proxy answer DNS inside the tunnel rather than leaking
        // queries to the device resolver; the same setting the GST path uses.
        // NOTE: no --tun-fd here. tun2proxy runs in the :tun process (see Tun2proxyHostService)
        // and fd numbers are process-local, so the host appends its own duplicate's number.
        val cliArgs = buildString {
            append("tun2proxy")
            append(" --proxy socks5://127.0.0.1:${AetherEngine.AETHER_SOCKS_PORT}")
            append(" --dns virtual")
            // info logs a line per connection, which on a busy device is a steady drip of
            // string formatting and logcat writes on the data path. Keep it for debugging
            // runs, but don't make every user pay for it.
            append(if (opts.verbose) " --verbosity info" else " --verbosity warn")
            // The fd stays ours to close.
            //
            // With `true`, tun2proxy closes the TUN fd from inside its own teardown while the
            // service is also unwinding around it — two owners for one descriptor. That is
            // exactly the shape of the one crash on this app that produced a readable
            // backtrace: `fdsan: attempted to close file descriptor N, expected to be
            // unowned, actually owned by unique_fd`, which aborts the process. A descriptor
            // closed twice can also be handed out again in between, so the damage need not
            // surface at the close itself — it can land seconds later, anywhere.
            append(" --close-fd-on-drop false")
        }
        ownedTunFd = rawTunFd
        appContext = context.applicationContext
        tun2proxyStartedAt = System.currentTimeMillis()
        // Runs in the :tun process from here on. Our descriptor stays open and ours; the host
        // gets a binder-duplicated one. See Tun2proxyHostService for why this is split out.
        if (!Tun2proxyClient.start(context, rawTunFd, cliArgs, mtu)) {
            android.util.Log.e(TAG, "Failed to start tun2proxy host process")
            eng.stop()
            return false
        }
        tun2proxyRunning.set(true)

        android.util.Log.i(TAG, "Aether full-tunnel engine started")
        return true
    }

    /**
     * Poll until the engine reports CONNECTED *and* the SOCKS port answers, or we give up.
     *
     * Both conditions matter: the stage parser can call it connected off a log line a moment
     * before the listener is bound, and a bound listener with no gateway behind it is not
     * usable either.
     */
    private suspend fun awaitSocks(eng: AetherEngine, scan: AetherScan): Boolean {
        val started = System.currentTimeMillis()
        val budget = connectTimeoutMs(scan)
        val deadline = started + budget
        android.util.Log.i(TAG, "waiting for gateway: scan=${scan.env} budget=${budget}ms")
        while (System.currentTimeMillis() < deadline) {
            // Bail out the moment the user asks to disconnect. This runs inside
            // MyVpnService's xrayMutex and the ACTION_STOP path needs that same mutex, so
            // without a check here a stop pressed during a slow scan would sit queued
            // behind us with a dead-looking button. [requestAbort] is called before the
            // STOP handler reaches for the mutex.
            //
            // This used to read MyVpnService.connectionPhaseFlow == IDLE instead, which was
            // wrong: that flow is global with many writers, and MyVpnService.onDestroy()
            // sets IDLE unconditionally. A previous attempt's service finishing its
            // teardown would therefore abort a healthy scan that had only been running a
            // few seconds. A cancellation signal has to belong to the operation it cancels.
            if (abortRequested) {
                android.util.Log.i(TAG, "give up after ${elapsed(started)}ms: aborted by user")
                return false
            }
            val st = eng.state.value
            if (st.stage == AetherStage.FAILED || st.stage == AetherStage.CRASHED) {
                android.util.Log.i(TAG, "give up after ${elapsed(started)}ms: engine reported ${st.stage}")
                return false
            }
            // The process died without ever reaching a terminal stage.
            if (st.stage == AetherStage.STOPPED && eng.process == null) {
                android.util.Log.i(TAG, "give up after ${elapsed(started)}ms: engine process gone")
                return false
            }
            if (st.connected && eng.isSocksAlive()) {
                android.util.Log.i(TAG, "gateway ready after ${elapsed(started)}ms")
                return true
            }
            delay(POLL_INTERVAL_MS)
        }
        android.util.Log.i(TAG, "give up after ${elapsed(started)}ms: budget exhausted")
        return false
    }

    private fun elapsed(since: Long) = System.currentTimeMillis() - since

    /**
     * Teardown order:
     *   1. Stop the Aether process FIRST. Closing its SOCKS listener is what unblocks
     *      tun2proxy's native read; the other order frees state out from under a blocking
     *      call and crashes in native code.
     *   2. Wait for the tun2proxy worker to exit on its own — see the comment inline.
     *   3. Only if it didn't, signal it cooperatively, bounded on a side thread so a hung
     *      JNI call can't stall MyVpnService's shared mutex.
     *   4. Final join with a timeout.
     */
    override fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            android.util.Log.d(TAG, "stop() already run — skipping duplicate teardown")
            return
        }
        android.util.Log.i(TAG, "Stopping Aether full-tunnel engine")

        // Never tear down within [MIN_UPTIME_MS] of handing the fd to tun2proxy. The first
        // step below kills the SOCKS process out from under it and the steps after that
        // drive its native shutdown; both are unsafe while runTun2proxy is still setting up
        // the tun reader and the proxy session state. A teardown that lands ~300ms after
        // start took the whole process down with no Java exception and no tombstone.
        //
        // This is a safety net for the ordering bug fixed in MyVpnService (a stale
        // stopSelf() destroying the service under a fresh connect); it also covers the
        // out-of-band cases we do not control — a system VPN revoke, or the user tapping
        // disconnect the instant the tunnel comes up.
        val age = System.currentTimeMillis() - tun2proxyStartedAt
        if (tun2proxyStartedAt > 0L && age < MIN_UPTIME_MS) {
            val wait = MIN_UPTIME_MS - age
            android.util.Log.i(TAG, "stop() arrived ${age}ms after start — settling ${wait}ms first")
            try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        }

        try { engine?.stop() } catch (t: Throwable) {
            android.util.Log.e(TAG, "engine.stop threw: ${t.message}")
        }

        // Hand the tun2proxy teardown to the :tun process and do not wait for it.
        //
        // This used to be an elaborate settle-then-signal dance, because tun2proxy ran here and
        // a double teardown took the app down seconds later. It still needs sequencing -- and it
        // still gets it, inside Tun2proxyHostService -- but it is no longer OUR process that pays
        // if it goes wrong. If the core exits(255) on its way out, :tun dies alone and the app
        // does not notice.
        appContext?.let { Tun2proxyClient.stop(it) }
        tun2proxyRunning.set(false)
        // Closed here and nowhere else, and only once the worker is done with it. Adopting it
        // into a ParcelFileDescriptor hands it to the same fd-ownership tracking the platform
        // uses, so a stray second close anywhere else is reported rather than silently
        // recycling a live descriptor.
        val fd = ownedTunFd
        ownedTunFd = -1
        if (fd > 0) {
            try {
                android.os.ParcelFileDescriptor.adoptFd(fd).close()
                android.util.Log.i(TAG, "TUN fd $fd closed")
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "closing TUN fd $fd threw: ${t.message}")
            }
        }

        appContext = null
        engine = null
        android.util.Log.i(TAG, "Aether full-tunnel engine stopped")
    }

    private fun parseConfig(config: String): AetherOptions {
        val o = JSONObject(config)
        return AetherOptions(
            protocol = AetherProtocol.valueOf(o.optString("protocol", "MASQUE")),
            scan = AetherScan.valueOf(o.optString("scan", "TURBO")),
            ipFamily = AetherIp.valueOf(o.optString("ip", "V4")),
            transport = o.optString("transport", "h3"),
            ech = o.optString("ech", ""),
            fragment = o.optBoolean("fragment", false),
            fragmentSize = if (o.has("fragmentSize")) o.optInt("fragmentSize") else null,
            fragmentDelay = if (o.has("fragmentDelay")) o.optInt("fragmentDelay") else null,
            // Empty default, NOT "firewall": that name only exists in MASQUE's profile table.
            // Forcing it on a WireGuard run made aethernoize fall through to balanced and
            // silently discard whatever the user actually picked. Empty lets AetherOptions
            // resolve the right default for the protocol.
            noize = o.optString("noize", ""),
            keepalive = o.optInt("keepalive", 5),
            noProfileRetry = o.optBoolean("noProfileRetry", false),
            quickReconnect = o.optBoolean("quickReconnect", true),
            verbose = o.optBoolean("verbose", false),
            noDataCheck = o.optBoolean("noDataCheck", false),
        )
    }

    companion object {
        private const val TAG = "AetherTunEngine"

        /**
         * Cross-thread "stop what you're doing" flag for the connect wait.
         *
         * Lives in the companion because the thing that needs to set it — MyVpnService's
         * ACTION_STOP handler — cannot reach the instance without first taking the mutex
         * the instance is holding. Volatile Boolean is enough: one writer per transition
         * and readers only ever need eventual visibility on a 500ms poll.
         */
        @Volatile
        private var abortRequested: Boolean = false

        /** Ask an in-flight [start] to give up. Call BEFORE contending for xrayMutex. */
        fun requestAbort() {
            abortRequested = true
        }

        /**
         * How long to wait for a usable SOCKS listener, per scan mode.
         *
         * These track the `overall_deadline` values in the engine's own strategy table
         * (aether/src/prober.rs): turbo 45s, balanced 120s, thorough 300s, stealth 180s.
         * Plus [TIMEOUT_HEADROOM_MS] for identity provisioning, the handshake and the
         * data-plane validation that follow the scan proper.
         *
         * Getting this wrong in the short direction is the dangerous one: a flat 180s cap
         * silently aborted «کامل» runs at 180s even though the engine still had two minutes
         * of budget left, so a mode the user picked on purpose could never succeed.
         */
        private fun connectTimeoutMs(scan: AetherScan): Long = when (scan) {
            AetherScan.TURBO -> 45_000L
            AetherScan.BALANCED -> 120_000L
            AetherScan.THOROUGH -> 300_000L
            // Ironclad's own deadline is also 180s, but every candidate costs a real tunnel
            // and real traffic (prober.rs:200 — 15s per probe, 3 successes required, no early
            // exit), so it reaches that deadline far more often than stealth does. Listed
            // explicitly rather than left to `else` so it is obvious this was considered.
            AetherScan.IRONCLAD -> 180_000L
            else -> 180_000L
        } + TIMEOUT_HEADROOM_MS

        private const val TIMEOUT_HEADROOM_MS = 45_000L
        private const val POLL_INTERVAL_MS = 500L

        /**
         * Minimum time tun2proxy must have been running before [stop] is allowed to touch
         * it. Native setup (tun reader + proxy session state) is not re-entrant with
         * shutdown; the crash that motivated this had a teardown land 308ms after start.
         * 1.5s clears setup with headroom and is only ever paid on the rare
         * connect-then-immediately-disconnect path.
         */
        private const val MIN_UPTIME_MS = 1_500L

        /** Marker MyVpnService dispatches on, mirroring the "gst"/"hybrid" config types. */
        const val CONFIG_TYPE = "aether"

        /**
         * Serialize options into the String that IVpnEngine.start carries. Tagged with
         * `type` so MyVpnService can route it the same way it routes GST configs.
         */
        /**
         * The Aether profile the Game tab uses, for BOTH the AUTO race and the explicit
         * "Aether" button.
         *
         * It lives here rather than in GameBoosterManager because it was previously written
         * out twice — once in the manager for the race and once inline in GameTab for the
         * button — and the two had already drifted: the button path shipped plain MASQUE/TURBO
         * and never picked up any game tuning at all. One definition, both call sites.
         *
         * Tuned for latency rather than throughput:
         *  - **h3 (QUIC), never h2.** MASQUE over HTTP/2 is TCP, so the game's UDP would ride
         *    inside a reliable ordered stream: one lost packet head-of-line-blocks every
         *    packet behind it, and the tunnel's retransmits fight the game's own. HTTP/3 keeps
         *    datagrams as datagrams the whole way.
         *  - **TURBO scanning, despite this being the latency-sensitive path.** BALANCED and
         *    IRONCLAD were both tried here first, on the reasoning that a booster should rank
         *    endpoints by RTT rather than take the first hit. Device logs on a filtered line
         *    say otherwise: IRONCLAD fails 100% of candidates ("tunnel exited before
         *    data-plane validation" — it builds a real tunnel per candidate and the network
         *    kills each one), and BALANCED found its single working candidate 4s in, then
         *    spent the remaining 118s of its deadline hunting for six that do not exist. On a
         *    network where roughly one endpoint in three hundred survives, ranking is a
         *    fiction — there is nothing to rank. TURBO reaches the same endpoint in seconds.
         *  - **`light` obfuscation.** Anything heavier prepends junk to every datagram, which
         *    on a 60-tick game loop is latency and bandwidth spent on the hot path. `light`
         *    still disguises the handshake, which is what DPI fingerprints.
         *  - **5s keepalive.** Carrier NATs drop idle UDP mappings in as little as 30s, and a
         *    dropped mapping mid-match costs a full reconnect.
         */
        fun buildGameConfig(
            protocol: AetherProtocol = AetherProtocol.MASQUE,
            scan: AetherScan = AetherScan.TURBO,
        ): String = buildConfig(
            AetherOptions(
                protocol = protocol,
                scan = scan,
                transport = "h3",
                noize = "light",
                keepalive = 5,
                ipFamily = AetherIp.V4,
            )
        )

        fun buildConfig(opts: AetherOptions): String = JSONObject().apply {
            put("type", CONFIG_TYPE)
            put("protocol", opts.protocol.name)
            put("scan", opts.scan.name)
            put("ip", opts.ipFamily.name)
            put("transport", opts.transport)
            put("ech", opts.ech)
            put("fragment", opts.fragment)
            opts.fragmentSize?.let { put("fragmentSize", it) }
            opts.fragmentDelay?.let { put("fragmentDelay", it) }
            put("noize", opts.noize)
            put("keepalive", opts.keepalive)
            put("noProfileRetry", opts.noProfileRetry)
            put("quickReconnect", opts.quickReconnect)
            put("verbose", opts.verbose)
            put("noDataCheck", opts.noDataCheck)
        }.toString()
    }
}
