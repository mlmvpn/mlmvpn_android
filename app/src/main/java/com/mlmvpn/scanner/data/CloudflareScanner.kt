package com.mlmvpn.scanner.data

import android.net.Network
import android.util.Log
import com.mlmvpn.scanner.utils.NetworkWatchdog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

data class ScanResult(
    val ip: String,
    val ping: Int, // average latency in ms, or -1 if timeout
    val lossRate: Float, // 0.0 to 1.0
    /** Spread between the fastest and slowest probe. Zero when only one probe was taken. */
    val jitter: Int = 0,
    /**
     * The port that actually answered.
     *
     * Cloudflare proxies all of its TLS ports to the same origin, so an exit closed on 443 and
     * open on 2053 is still usable -- by a config whose port is 2053. Carrying the working port
     * here is what lets the combine step build a config that can actually connect.
     */
    val port: Int = 443,
    var downloadSpeed: Float = 0f, // real proxied delay in ms, despite the name
    /**
     * The id of the Cloudflare SCAN strategy whose handshake verified this address, or null when it
     * passed as written. A combine writes that handshake into every config it builds on the address
     * (CfNetManager.handshakeParams), so the health test and the connect dial it the same way.
     */
    var strategy: String? = null,
)

class CloudflareScanner {

    companion object {
        private const val TAG = "CloudflareScanner"

        /** A SCAN shape proven this recently is dialled with as it is; older, it is re-checked first. */
        private const val SCAN_SHAPE_FRESH_MS = 6 * 3600_000L

        /** One real test through the core. Generous: one handshake on an Iranian mobile line can take 9-14 s. */
        private const val REAL_DELAY_TIMEOUT_MS = 15_000L

        /**
         * Above this an address is not worth a definitive test.
         *
         * Deliberately a fixed rule rather than a strategy knob: it is not about what the user is
         * optimising for, it is the point past which a Cloudflare edge is too far away to be worth
         * anyone's time. Strategies whose TCP timeout is lower than this never reach it.
         */
        private const val MAX_USEFUL_PING_MS = 800

        // Default Cloudflare IPv4 Ranges
        val DEFAULT_RANGES = listOf(
            "103.21.244.0/22",
            "103.22.200.0/22",
            "103.31.4.0/22",
            "104.16.0.0/13",
            "104.24.0.0/14",
            "108.162.192.0/18",
            "131.0.72.0/22",
            "141.101.64.0/18",
            "162.158.0.0/15",
            "172.67.0.0/16",
            "173.245.48.0/20",
            "188.114.96.0/20",
            "172.64.0.0/15",
            "190.93.240.0/20",
            "197.234.240.0/22",
            "198.41.128.0/17"
        )
    }

    /**
     * Parse CIDR and pick one random IP per /24 subnet block.
     *
     * Example: 103.21.244.0/22 contains 4 /24 blocks, so it returns 4 random IPs.
     *
     * A prefix longer than /24 has no /24 blocks to walk, so it yields a single address -- and it
     * has to be built by ADDING a random host part to the network address rather than OR-ing one
     * in, because the network address of, say, a /28 already has a non-zero low byte and `or`
     * would land outside the range. Anything outside 0..32 is not a prefix at all and is dropped:
     * left as it was, `/33` produced a zero mask and the loop then walked all 16.7 million /24
     * blocks of the IPv4 space.
     */
    fun generateIPs(cidrList: List<String>): List<String> {
        val resultList = mutableListOf<String>()
        for (cidr in cidrList) {
            val parts = cidr.split("/")
            if (parts.size != 2) continue
            val ipStr = parts[0]
            val prefixLen = parts[1].trim().toIntOrNull() ?: continue
            if (prefixLen !in 0..32) continue

            val ipLong = ipToLong(ipStr) ?: continue
            val mask = if (prefixLen == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLen)) and 0xFFFFFFFFL
            val networkIp = ipLong and mask
            val broadcastIp = networkIp or (mask.inv() and 0xFFFFFFFFL)

            if (prefixLen > 24) {
                // Smaller than a /24: one address, picked inside the block's own host range.
                val span = (broadcastIp - networkIp).toInt()
                val offset = if (span <= 0) 0 else (0..span).random()
                resultList.add(longToIp(networkIp + offset))
                continue
            }

            // Iterate over each /24 block within this subnet
            var currentBlock = networkIp
            while (currentBlock <= broadcastIp) {
                // Generate a random IP in the current /24 block (1 to 254)
                val randomSuffix = (1..254).random().toLong()
                val selectedIp = currentBlock or randomSuffix
                resultList.add(longToIp(selectedIp))

                // Move to next /24 block
                currentBlock += 256
            }
        }
        return resultList
    }

    enum class ScanPhase {
        IDLE, PINGING, SPEED_TESTING, DONE
    }

    /**
     * Everything the scan knows about itself, as one snapshot.
     *
     * Both halves run at once, so neither number alone describes the run: the probes can be
     * finished while verification still has a queue to work through, and verification can hit
     * its target while thousands of addresses remain unprobed. Reporting both is the only
     * honest answer, and it is also the only way the screen can show that work is happening.
     */
    data class ScanStats(
        /** Addresses whose port has been probed. */
        val probed: Int = 0,
        val total: Int = 0,
        /** Of those, the ones that answered -- candidates for the real test. */
        val alive: Int = 0,
        /** Candidates that have been through a real proxied request. */
        val tested: Int = 0,
        /** The ceiling on [tested]; the scan stops there even if the target was not met. */
        val testBudget: Int = 0,
        /** Of those, the ones that passed every pass. */
        val healthy: Int = 0,
        /** How many healthy ones end the scan. */
        val target: Int = 0,
        /**
         * True when this run is re-checking a fixed list rather than discovering new addresses.
         *
         * The screen has to read the same numbers differently for the two. A discovery scan is
         * finished when it has found enough, so its second bar is healthy-against-target. A health
         * test is finished when it has been through the LIST, and most of that list is expected to
         * fail -- so the same bar would sit near empty at the end of a run that worked perfectly.
         */
        val healthTest: Boolean = false,
    )

    /**
     * Scan as a pipeline, not as two phases.
     *
     * The old shape was: TCP-probe every one of ~5,000 IPs, `awaitAll()`, sort, take the best 50,
     * and only THEN start the real proxied tests. The verification stage -- the slow, useful part
     * -- sat idle for the whole probe, and the probe kept running long after enough candidates had
     * been found, because nothing downstream could tell it to stop.
     *
     * Now a probe pool and a verify pool run at the same time, joined by a channel. A probe that
     * opens becomes a candidate immediately; the verifiers pull candidates and run the real test.
     * The moment [successTarget] verified exits exist, both pools are cancelled -- so a lucky scan
     * finishes in seconds instead of grinding through the remaining four thousand addresses.
     *
     * Between the two sits an ADMISSION QUEUE, and that is where the strategies differ. With no
     * window ([ScanStrategy.FAST_SCAN]) a candidate goes straight across and the scan is simply as
     * fast as the network allows. With a window, candidates gather for a moment and are released
     * best-first, so the verifiers -- which are the scarce resource -- always spend their time on
     * the most promising address known so far rather than on whichever one happened to answer.
     *
     * @param testBudget the ceiling on definitive (proxied) tests. The second half of the promise
     *   the screen makes: [successTarget] decides when the scan can stop early, this decides when
     *   it has to stop anyway. Without it a scan on a network where nothing passes verification ran
     *   the entire address space with no upper bound on its own duration.
     * @param ports the ports the configs being combined actually use. Cloudflare proxies all of
     *   its TLS ports to the same origin, so an exit that is closed on 443 and open on 2053 is
     *   still a usable exit -- for a config whose port is 2053. Each is probed and the one that
     *   answers is recorded on the result.
     * @param probeNetwork the interface the TCP sweep must use, or null for the default route.
     *   Non-null only when a foreign VPN holds the default route and the user chose to scan
     *   anyway: the sweep is then pinned to the real line so the numbers describe it. The
     *   verification stage cannot be pinned -- it runs inside the Xray core -- and the screen says
     *   as much rather than implying otherwise.
     */
    suspend fun startScan(
        context: android.content.Context,
        ips: List<String>,
        baseConfig: String,
        testBudget: Int = 50,
        successTarget: Int = 10,
        maxConcurrency: Int = 200,
        strategy: ScanStrategy = ScanStrategy.FAST_CONFIG,
        ports: List<Int> = listOf(443),
        watchdog: NetworkWatchdog,
        probeNetwork: Network? = null,
        /** Re-checking a fixed list rather than discovering; changes how the screen reads. */
        healthTest: Boolean = false,
        onPhaseChange: (ScanPhase) -> Unit,
        onStats: (ScanStats) -> Unit,
        onIpFound: (ScanResult) -> Unit, // Real-time emit of found IP
        onPaused: (String?) -> Unit = {} // non-null while the scan is frozen waiting for connectivity
    ): List<ScanResult> = coroutineScope {
        // The Cloudflare engine's SCAN stage: the shape proven on this network for measuring this
        // kind of config is what every real test below dials with (generateSpeedtestConfig applies
        // it). A winner proven in the last six hours is used as it is; otherwise a bounded search
        // runs first, shown as a notice rather than a scan that silently does nothing. The address
        // list is the user's own and is never replaced or filtered here.
        val cfnet = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context)
        val base = com.mlmvpn.scanner.utils.VpnConfig.parseUri(baseConfig)
        val cloudflareBase = base != null && com.mlmvpn.scanner.utils.CfUnfilter.isCloudflareFronted(base)
        // The family of the addresses being tested. Every real test dials a literal address, and the
        // engine hands a literal address only a strategy of its own family (an IPv6 winner says
        // nothing about an IPv4 clean IP), so the shape this scan is judged by is of that family too.
        val scanFamily = ips.firstNotNullOfOrNull { com.mlmvpn.scanner.engines.cfnet.CfShape.literalFamily(it) }
            ?: com.mlmvpn.scanner.engines.cfnet.Family.V4
        val scanShape = if (cfnet.enabled() && cloudflareBase && !healthTest) {
            val fresh = cfnet.saved(com.mlmvpn.scanner.engines.cfnet.CfStage.SCAN, base, scanFamily)?.takeIf {
                cfnet.store.provenWithin(cfnet.profile(), com.mlmvpn.scanner.engines.cfnet.CfStage.SCAN, it.id, SCAN_SHAPE_FRESH_MS)
            }
            fresh ?: try {
                withContext(Dispatchers.Main) { onPaused(S(R.string.cfnet_scan_preparing)) }
                cfnet.ensure(com.mlmvpn.scanner.engines.cfnet.CfStage.SCAN, base, freshMs = SCAN_SHAPE_FRESH_MS,
                    budget = com.mlmvpn.scanner.engines.cfnet.StrategyEngine.StageBudget(parallel = 1, deadlineMs = 75_000))
                    ?.takeIf { it.data?.family == scanFamily }
                    ?: cfnet.saved(com.mlmvpn.scanner.engines.cfnet.CfStage.SCAN, base, scanFamily)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { onPaused(null) }
            }
        } else null
        Log.i(TAG, "scan shape: ${scanShape?.id ?: "none"} family=${scanFamily.key}")
        val tuning = strategy.tuning
        // The proven port is probed IN ADDITION to the user's own, never instead of them.
        val savedPort = if (scanShape != null) cfnet.scanPort(base, scanFamily) else 0
        val probePorts = (ports + listOfNotNull(savedPort.takeIf { it > 0 })).distinct().filter { it in 1..65535 }.ifEmpty { listOf(443) }
        // A shaped handshake gets through filters a plain one does not, so the plain-TLS pre-filter
        // would throw away exactly the addresses the shape is for: TCP alone then.
        val shapedPrefilter = cloudflareBase && (scanShape != null && cfnet.scanNeedsShapedPrefilter(base, scanFamily))
        // Both are user-typed. Zero as a target made every verifier exit on its first check, which
        // left the admission queue undrained and the probe pool running the whole address space for
        // a result that was empty by construction; a budget below the target could never reach it.
        val target = successTarget.coerceAtLeast(1)
        val budget = testBudget.coerceAtLeast(target)
        val totalIps = ips.size
        val probedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val verifiedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val aliveCount = java.util.concurrent.atomic.AtomicInteger(0)
        val testedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val finalValidIps = java.util.concurrent.CopyOnWriteArrayList<ScanResult>()
        // One address can reach verification twice -- a custom list may repeat one, and the probe
        // pool has no memory. Verifying it twice would spend the budget on an answer already known.
        val claimed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        // One place builds the snapshot, so no caller can report half of it.
        fun emit() = onStats(
            ScanStats(
                probed = probedCount.get(),
                total = totalIps,
                alive = aliveCount.get(),
                tested = testedCount.get(),
                testBudget = budget,
                healthy = verifiedCount.get(),
                target = target,
                healthTest = healthTest,
            )
        )
        emit()

        Log.d(TAG, "Pipeline scan: ${ips.size} IPs, strategy=$strategy, ports=$probePorts, " +
            "target=$target, budget=$budget, pinned=${probeNetwork != null}")
        onPhaseChange(ScanPhase.PINGING)

        // Unbounded so a probe never blocks waiting for a verifier; back-pressure is handled by
        // cancelling the whole pipeline once the target is met, not by stalling the probes.
        val candidates = kotlinx.coroutines.channels.Channel<ScanResult>(
            capacity = kotlinx.coroutines.channels.Channel.UNLIMITED
        )
        val admitted = kotlinx.coroutines.channels.Channel<ScanResult>(
            capacity = kotlinx.coroutines.channels.Channel.UNLIMITED
        )

        // A CHILD of this scope, not a root job. As a root, a probe that threw anything other than
        // a cancellation had nowhere to report it: the exception went to the process-wide handler
        // and took the app down. As a child it cancels the pipeline and surfaces from
        // `coroutineScope` below, where the caller already handles it -- and cancelling the caller
        // now stops the pipeline by itself instead of relying on the finally to notice.
        val pipeline = Job(coroutineContext[Job])

        /** True once the scan has no reason to continue: target met, or budget spent. */
        fun finished() = verifiedCount.get() >= target || testedCount.get() >= budget

        // ---- probes -----------------------------------------------------------------------
        val probeSemaphore = kotlinx.coroutines.sync.Semaphore(
            maxOf(1, minOf(tuning.probeConcurrency, maxConcurrency))
        )
        // The config's own first request, after TCP and before the costly real test (ScanScout):
        // the filtering lets many addresses complete TLS and then never answers, and each of those
        // used to cost a whole Xray test from the budget.
        val aliveTarget = ScanScout.target(baseConfig)
        val aliveSemaphore = kotlinx.coroutines.sync.Semaphore(32)
        val probeJob = launch(Dispatchers.IO + pipeline) {
            val jobs = ips.map { ip ->
                async {
                    probeSemaphore.acquire()
                    try {
                        if (!isActive) return@async
                        // If the device's own connection just dropped, a bare TCP connect fails
                        // almost instantly (no route) instead of waiting out the timeout -- which
                        // would make the scan look like it sped up while really marking every
                        // remaining IP dead without testing it.
                        ensureOnline(watchdog, onPaused)
                        val result = tcpProbe(ip, probePorts, tuning, probeNetwork)
                            ?.takeIf { r -> probeNetwork != null || shapedPrefilter || aliveSemaphore.withPermit { ScanScout.alive(r.ip, r.port, aliveTarget) } }
                        probedCount.incrementAndGet()
                        // Feeds the watchdog's second detector: a long run of these with no
                        // success is what makes it stop trusting the OS and move a byte itself.
                        watchdog.noteProbe(result != null)
                        if (result != null) {
                            aliveCount.incrementAndGet()
                            candidates.trySend(result)
                        }
                        emit()
                    } finally {
                        probeSemaphore.release()
                    }
                }
            }
            jobs.joinAll()
            candidates.close()
        }

        // ---- admission --------------------------------------------------------------------
        //
        // With no window this is a wire. With one, it is the whole difference between "the first
        // exits that answered" and "the best exits found so far".
        val admissionJob = launch(Dispatchers.Default + pipeline) {
            if (tuning.admissionWindowMs <= 0L) {
                for (c in candidates) admitted.trySend(c)
            } else {
                val buffer = mutableListOf<ScanResult>()
                var lastRelease = System.currentTimeMillis()

                fun flush() {
                    if (buffer.isEmpty()) return
                    // Best first: no loss beats some loss, then lower latency, then steadier.
                    buffer.sortWith(
                        compareBy<ScanResult> { it.lossRate }
                            .thenBy { it.ping }
                            .thenBy { it.jitter }
                    )
                    buffer.forEach { admitted.trySend(it) }
                    buffer.clear()
                    lastRelease = System.currentTimeMillis()
                }

                // A tick channel rather than a timeout wrapped around the receive.
                //
                // `withTimeoutOrNull { candidates.receiveCatching() }` looks equivalent and is
                // not: a timeout that fires after `receive` has already taken an element discards
                // it, because this channel carries no undelivered-element handler. So an address
                // that answered was occasionally dropped before any verifier saw it. A `select`
                // takes exactly one clause, so the element is either delivered or not taken.
                val ticks = kotlinx.coroutines.channels.Channel<Unit>(
                    kotlinx.coroutines.channels.Channel.CONFLATED
                )
                val ticker = launch {
                    while (isActive) {
                        delay(200)
                        ticks.trySend(Unit)
                    }
                }

                var closed = false
                while (isActive && !closed) {
                    kotlinx.coroutines.selects.select<Unit> {
                        candidates.onReceiveCatching { outcome ->
                            val value = outcome.getOrNull()
                            if (value != null) buffer.add(value) else closed = true
                        }
                        ticks.onReceive { }
                    }
                    val windowElapsed =
                        System.currentTimeMillis() - lastRelease >= tuning.admissionWindowMs
                    if (buffer.size >= tuning.admissionBatch || (buffer.isNotEmpty() && windowElapsed)) {
                        flush()
                    }
                }
                ticker.cancel()
                flush()
            }
            admitted.close()
        }

        // ---- verification -------------------------------------------------------------------
        withContext(Dispatchers.Main) { onPhaseChange(ScanPhase.SPEED_TESTING) }

        val verifiers = (1..maxOf(1, tuning.verifyConcurrency)).map {
            launch(Dispatchers.IO + pipeline) {
                for (candidate in admitted) {
                    if (!isActive) break
                    if (finished()) break
                    if (claimed.putIfAbsent(candidate.ip, true) != null) continue
                    ensureOnline(watchdog, onPaused)

                    // Every pass has to succeed, and the WORST of them is what gets recorded --
                    // one good reading says nothing about an exit that alternates.
                    testedCount.incrementAndGet()
                    emit()
                    var worst = 0f
                    var ok = true
                    var shape: String? = null
                    repeat(tuning.verifyPasses) {
                        if (!ok) return@repeat
                        val (delay, used) = realDelayTestWith(candidate.ip, baseConfig, context, candidate.port)
                        if (delay <= 0f) ok = false else { worst = maxOf(worst, delay); shape = used }
                    }

                    if (ok && worst > 0f) {
                        com.mlmvpn.scanner.data.CfEdge.noteGood(context, candidate.ip, worst.toLong())
                        candidate.downloadSpeed = worst
                        candidate.strategy = shape
                        finalValidIps.add(candidate)
                        withContext(Dispatchers.Main) { onIpFound(candidate) }
                        verifiedCount.incrementAndGet()
                    }
                    emit()
                    if (finished()) {
                        Log.d(TAG, "Stopping: healthy=${verifiedCount.get()}/$target, " +
                            "tested=${testedCount.get()}/$budget")
                        // Stops the probes too. Nothing downstream could do that before, which
                        // is why a scan kept probing thousands of addresses it no longer needed.
                        pipeline.cancel()
                        break
                    }
                }
            }
        }

        // The pipeline ends when the target is hit (cancel above) or when the probes run out and
        // the queues drain. Cancellation is the normal exit, so it must not propagate as an error.
        try {
            probeJob.join()
            admissionJob.join()
            verifiers.forEach { it.join() }
        } catch (e: CancellationException) {
            // Expected: the target was reached.
        } finally {
            pipeline.cancel()
            candidates.close()
            admitted.close()
        }

        // NonCancellable, so a scan the user stopped still reports its final numbers and lands on
        // DONE. Without it this threw on the way out and the screen kept the last mid-scan frame.
        withContext(Dispatchers.Main + NonCancellable) {
            emit()
            onPaused(null)
            onPhaseChange(ScanPhase.DONE)
        }

        // Monitor: what this scan proved about the SCAN strategy it dialled with. Real tests that
        // found addresses reinforce it; a full budget of real tests that found nothing lowers it, so
        // the next scan searches for another shape instead of repeating this one.
        if (scanShape != null) {
            // Only what this shape verified: a mixed list's other family was dialled another way.
            val proven = finalValidIps.filter { it.strategy == scanShape.id }
            val healthy = proven.size
            val tested = testedCount.get()
            val result = when {
                healthy > 0 -> com.mlmvpn.scanner.engines.cfnet.ValidationResult.pass(proven.minOf { it.downloadSpeed }.toLong(), "found=$healthy")
                tested >= 8 -> com.mlmvpn.scanner.engines.cfnet.ValidationResult.fail(com.mlmvpn.scanner.engines.cfnet.FailureClass.SCAN_FAILURE, detail = "tested=$tested found=0")
                else -> null
            }
            if (result != null) cfnet.report(com.mlmvpn.scanner.engines.cfnet.CfStage.SCAN, scanShape, cfnet.profile(), result)
        }

        return@coroutineScope finalValidIps.sortedBy { it.downloadSpeed }
    }

    /**
     * If the device is currently offline, mark the scan paused (for the UI banner) and suspend
     * here until connectivity is back, instead of racing through the rest of the IP list marking
     * every one "dead" in a few milliseconds each.
     *
     * The clear is in a `finally` for a reason: the pipeline is routinely cancelled while probes
     * sit in here, and without it the banner survived the scan that raised it -- and the next one,
     * since nothing else ever cleared the message.
     */
    private suspend fun ensureOnline(watchdog: NetworkWatchdog, onPaused: (String?) -> Unit) {
        if (watchdog.isOnline.value) return
        val message = when (watchdog.reason.value) {
            NetworkWatchdog.Reason.NO_DATA_WIFI -> S(R.string.scan_paused_no_data_wifi)
            NetworkWatchdog.Reason.NO_DATA_MOBILE -> S(R.string.scan_paused_no_data_mobile)
            else -> S(R.string.scan_paused_offline)
        }
        try {
            withContext(Dispatchers.Main) { onPaused(message) }
            Log.w(TAG, "Paused: ${watchdog.reason.value ?: "no connectivity"}")
            watchdog.waitUntilOnline()
            Log.d(TAG, "Resumed: connectivity is back")
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { onPaused(null) }
            }
        }
    }

    /**
     * Probe one address, across the ports the configs actually use.
     *
     * The ports are tried in order and the FIRST one that opens wins, which is the cheap way to be
     * port-aware without multiplying the scan by the number of ports: the common case is a single
     * port, where this costs exactly what the old single-port probe cost, and the uncommon case
     * pays only for the addresses that fail on the primary port.
     *
     * How many probes are taken, and what disqualifies an address, both come from the strategy --
     * a scan optimising for finish time takes one probe and accepts anything that answers, and one
     * optimising for stability takes five and rejects anything that lost one or wobbled.
     *
     * With [network] given, every socket is pinned to that interface rather than the default
     * route. That is what lets a scan measure the real line while another app's VPN holds the
     * default route, instead of measuring their tunnel.
     *
     * Note: unlike the WARP UDP probe, a fast failure here ("connection refused") is a normal,
     * expected outcome when scanning random Cloudflare addresses and does NOT mean the device is
     * offline -- that detection is the OS-level NetworkWatchdog's job, up in startScan.
     */
    private fun tcpProbe(
        ip: String,
        ports: List<Int>,
        tuning: ScanTuning,
        network: Network?,
    ): ScanResult? {
        for (port in ports) {
            val delays = mutableListOf<Long>()
            var successCount = 0

            for (i in 0 until tuning.pingTimes) {
                val start = System.currentTimeMillis()
                var socket: Socket? = null
                try {
                    socket = network?.socketFactory?.createSocket() ?: Socket()
                    socket.connect(InetSocketAddress(ip, port), tuning.tcpTimeoutMs)
                    delays.add(System.currentTimeMillis() - start)
                    successCount++
                } catch (e: SocketTimeoutException) {
                    // A normal miss.
                } catch (e: Exception) {
                    // Refused / unreachable, also normal on a random address.
                } finally {
                    try { socket?.close() } catch (e: Exception) {}
                }
                // One miss already disqualifies a stability scan, so stop paying for the rest.
                if (tuning.maxLossRate == 0f && successCount != i + 1) break
            }

            if (successCount == 0) continue

            val lossRate = (tuning.pingTimes - successCount).toFloat() / tuning.pingTimes
            if (lossRate > tuning.maxLossRate) continue

            val avgPing = (delays.sum() / successCount).toInt()
            if (avgPing > MAX_USEFUL_PING_MS) continue

            val jitter = if (delays.size > 1) (delays.max() - delays.min()).toInt() else 0
            if (jitter > tuning.maxJitterMs) continue

            return ScanResult(
                ip = ip,
                ping = avgPing,
                lossRate = lossRate,
                jitter = jitter,
                port = port,
            )
        }
        return null
    }

    /**
     * Performs a real delay test using Xray native measureOutboundDelay.
     * Returns real delay in milliseconds, or 0f if failed.
     */
    internal suspend fun realDelayTest(
        ip: String,
        baseConfigUri: String,
        context: android.content.Context,
        port: Int = 443,
    ): Float = realDelayTestWith(ip, baseConfigUri, context, port).first

    /**
     * [realDelayTest], and the id of the Cloudflare SCAN strategy whose handshake it dialled with
     * (null: the config as written) -- what the combine writes into the configs it builds on [ip].
     */
    internal suspend fun realDelayTestWith(
        ip: String,
        baseConfigUri: String,
        context: android.content.Context,
        port: Int = 443,
    ): Pair<Float, String?> {
        var realDelay = 0f
        var strategy: String? = null

        try {
            val config = com.mlmvpn.scanner.utils.VpnConfig.parseUri(baseConfigUri) ?: return 0f to null
            // Pin the ORIGINAL host into sni/host BEFORE the address is replaced.
            //
            // This is the pairing the whole feature rests on: the packets go to the clean IP, the
            // TLS handshake still presents the name the edge expects. A base config that relies on
            // the implicit default -- no `sni=`, no `host=`, the server name simply being the
            // address -- lost that name the moment the address became an IP, and the core then
            // offered the IP as the server name. Cloudflare rejects that, so EVERY address came
            // back dead and the failure looked like a blocked range rather than a lost hostname.
            // It also matches what CombineEngine.rewrite writes, so the scan measures the config
            // the combine is actually going to build.
            val originalHost = config.address
            if (config.sni.isBlank()) config.sni = config.wsHost.ifBlank { originalHost }
            if (config.wsHost.isBlank()) config.wsHost = originalHost
            if (config.xhttpHost.isBlank()) config.xhttpHost = originalHost

            config.address = ip // Replace the address with our target IP
            // And the port the probe actually got through on: verifying an exit on 443 when
            // the probe only opened on 2053 would measure a door that is not there.
            config.port = port
            // Trimmed test config (see generateSpeedtestConfig): measureOutboundDelay never
            // serves traffic, so the DNS/routing/second-inbound setup the full config carries
            // was paid once per scanned IP for nothing.
            // SCAN: dialled with the shape proven for scanning on this network, if any -- the one
            // generateSpeedtestConfig stamps on it, named here so the combine can write it down.
            if (com.mlmvpn.scanner.utils.XrayJsonGenerator.tlsUnfilter) strategy = com.mlmvpn.scanner.engines.cfnet.CfNetManager.current()
                ?.appliedStrategy(com.mlmvpn.scanner.engines.cfnet.CfStage.SCAN, config)?.id
            val jsonConfig = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(
                config, strategyStage = com.mlmvpn.scanner.engines.cfnet.CfStage.SCAN)

            // In-process and in parallel (the verifiers run several at once), on a thread the
            // timeout can ABANDON: a wedged core no longer holds the scan, and the probe process's
            // one-core-at-a-time lane stays free for the Doctor.
            CombineEngine.ensureCoreEnv(context)
            val delayMs = CombineEngine.measureDelay(jsonConfig, timeoutMs = REAL_DELAY_TIMEOUT_MS)
            if (delayMs > 0) realDelay = delayMs.toFloat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception. measureOutboundDelay is a JNI call, so a device the core
            // was not built for raises UnsatisfiedLinkError -- an Error, which `catch (Exception)`
            // walks straight past and which then killed the whole pipeline job.
            Log.e(TAG, "Real delay test failed for $ip", e)
        }
        return realDelay to strategy
    }

    /** Dotted quad to a 32-bit value, or null if it is not one. */
    private fun ipToLong(ipAddress: String): Long? {
        val ipAddressInArray = ipAddress.trim().split(".")
        if (ipAddressInArray.size != 4) return null
        var result: Long = 0
        for (i in 0..3) {
            // Range-checked: "999.1.1.1" used to parse into a value that overflowed into the
            // neighbouring octet and produced addresses that were never in the range asked for.
            val ipPart = ipAddressInArray[i].toIntOrNull() ?: return null
            if (ipPart !in 0..255) return null
            result = (result shl 8) or ipPart.toLong()
        }
        return result
    }

    private fun longToIp(ip: Long): String {
        return ((ip shr 24 and 0xFF).toString() + "."
                + (ip shr 16 and 0xFF) + "."
                + (ip shr 8 and 0xFF) + "."
                + (ip and 0xFF))
    }
}
