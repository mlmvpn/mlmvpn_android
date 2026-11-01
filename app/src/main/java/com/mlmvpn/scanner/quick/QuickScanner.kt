package com.mlmvpn.scanner.quick

import android.content.Context
import com.mlmvpn.scanner.engines.freeconfig.FreeConfigEngine
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Finding servers that work.
 *
 * The funnel is still two tests -- a TCP connect, then a real proxied request -- because on a
 * filtered line most hosts accept the connection and then carry nothing, so only the second
 * proves anything. What changed is that they no longer run one after the other.
 *
 * The first version swept every candidate for reachability and only then began real-testing
 * the survivors. With a pool of ~9,000 that meant minutes of nothing happening before the
 * first real result could exist, which is the wrong shape for a button whose whole promise is
 * "press this and you are online". Now a pool of TCP probes feeds proven-reachable hosts
 * through a channel to a smaller pool of real testers, so the two stages overlap: the first
 * genuine result arrives within seconds of pressing, while the reachability sweep is still
 * running behind it.
 *
 * The channel is bounded, which is load-bearing rather than incidental: it is what stops the
 * cheap stage from racing thousands of entries ahead of the expensive one and building a
 * queue the user is waiting behind.
 */
object QuickScanner {

    private const val DELAY_TEST_URL = "https://clients3.google.com/generate_204"

    // Both timeouts match what the app's own delay test in the nodes tab uses (3s connect,
    // 10s request). They were tighter here -- 1.5s and 6s -- which on a mobile line out of Iran
    // is not a stricter test, it is a different one: a server needing 1.6s to complete a TCP
    // handshake was thrown out before the real test ever saw it, and a working server needing
    // 7s was recorded as dead. Two funnels disagreeing about the same servers is worse than
    // either being slow.
    private const val TCP_TIMEOUT_MS = 3_000
    private const val REAL_TIMEOUT_MS = 10_000L

    /**
     * How long a TLS handshake gets before the candidate is dropped.
     *
     * Short on purpose: this stage exists to be cheap. A server that cannot finish a handshake
     * in three seconds is not one the real test would have liked either.
     */
    private const val TLS_TIMEOUT_MS = 3_000

    /** Concurrency for each half of the pipeline. TCP is cheap, a real test is a whole core. */
    private const val TCP_WORKERS = 96

    // Each of these is a full Xray instance. Twelve is what the free-config importer settled on
    // for the same work on the same phones; sixteen with a 10s timeout starves the slow ones.
    private const val REAL_WORKERS = 12

    /**
     * A first result at or under this delay is good enough to connect to immediately.
     *
     * Above it, the sweep keeps looking for a little longer -- the first server to answer is
     * not necessarily one worth using, and the difference between 300ms and 1500ms is the
     * difference between a page loading and a page crawling.
     */
    const val GOOD_ENOUGH_MS = 900

    enum class Stage { IDLE, TCP, DELAY, DONE }

    /**
     * Where both halves of the pipeline have got to.
     *
     * Every field is always present and always means the same thing, which the first version
     * got wrong: it reused one `tested` counter for whichever stage happened to emit last, so
     * a single bar fed from it jumped backwards and forwards between the two stages several
     * times a second. The two stages run concurrently, so they need two independent readings
     * and the UI draws a bar for each.
     */
    data class Progress(
        val stage: Stage,
        /** Stage 1: hosts whose port has been probed, out of [total]. */
        val probed: Int = 0,
        /** Stage 1: how many of those answered -- and so how many stage 2 has to get through. */
        val reachable: Int = 0,
        /** Stage 2: reachable hosts that have been through a real request, out of [reachable]. */
        val realTested: Int = 0,
        /** Servers proven to work. */
        val found: Int = 0,
        val total: Int = 0,
        val target: Int = 0,
        val stopped: Boolean = false,
        val empty: Boolean = false,
    )

    @Volatile
    private var stopRequested = false

    @Volatile
    var running: Boolean = false
        private set

    fun stop() { stopRequested = true }

    // ── primitives ──────────────────────────────────────────────────────────────────────

    internal suspend fun tcpOpen(host: String, port: Int, timeoutMs: Int): Int = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                (System.currentTimeMillis() - started).toInt()
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** One server, one real proxied request. Returns the delay in ms, or -1. */
    suspend fun measure(context: Context, uri: String, skipTcp: Boolean = false): Int {
        val delay = measureRaw(context, uri, skipTcp)
        // The single place the shared pool learns anything. Every real test in the app comes
        // through here -- the browse screen's sweeps, the saved-list re-test, the connect
        // button's race -- so none of them has to know the pool exists, and none of them can
        // forget to tell it. What actually gets sent is decided in [MlmPoolClient.offer].
        MlmPoolClient.offer(context, uri, delay > 0, delay)
        return delay
    }

    private suspend fun measureRaw(
        context: Context,
        uri: String,
        skipTcp: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        FreeConfigEngine.ensureXrayEnv(context)
        try {
            val config = VpnConfig.parseUri(uri) ?: return@withContext -1
            // A dead host is far more common than a live one that fails the request, and the
            // cheap check costs 1.5s against the real test's 6s. Skipped when the caller has
            // already done it -- the pipeline below has, by definition.
            if (!skipTcp && tcpOpen(config.address, config.port, TCP_TIMEOUT_MS) < 0) return@withContext -1
            val json = XrayJsonGenerator.generateSpeedtestConfig(config)
            // In `:xmeasure`, with a timeout that really fires (CombineEngine.measureDelay): a pool
            // config that panics Xray used to close the app from inside Quick Connect's scan.
            val ms = com.mlmvpn.scanner.data.CombineEngine.measureDelay(json, DELAY_TEST_URL, REAL_TIMEOUT_MS)
            if (ms > 0) ms.toInt() else -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Does anything actually answer TLS for this config's server name?
     *
     * Measured against a 300-server sample of the live catalogue: 36% of the list opens a TCP
     * port, but a sixth of those never complete a handshake for the name the config asks for.
     * Almost all of that class is Cloudflare-fronted: the address is a shared Cloudflare IP, so
     * the TCP probe always succeeds no matter what, and the config only turns out to be dead when
     * the edge refuses the SNI because that domain is no longer served. Catching it here costs
     * one round trip; catching it in the real test costs a whole Xray instance and up to ten
     * seconds, and those seconds are the scan's entire budget.
     *
     * Deliberately does NOT verify the certificate. The question is "is there a live endpoint for
     * this name", not "is it trusted" -- a self-signed or mismatched certificate is completely
     * normal for these servers and says nothing about whether the proxy works.
     *
     * Returns true when TLS is not expected at all, so plain configs pass straight through.
     */
    internal fun tlsAnswers(config: com.mlmvpn.scanner.utils.VpnConfig): Boolean {
        val security = config.tls.lowercase()
        // REALITY does not present an ordinary certificate to an ordinary client, so a plain
        // handshake proves nothing either way; let those through untouched.
        if (security != "tls") return true
        val serverName = config.sni.ifEmpty { config.wsHost }.ifEmpty { config.address }
        if (serverName.isEmpty()) return true

        return try {
            java.net.Socket().use { raw ->
                raw.connect(java.net.InetSocketAddress(config.address, config.port), TLS_TIMEOUT_MS)
                raw.soTimeout = TLS_TIMEOUT_MS
                val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(TrustEverything), java.security.SecureRandom())
                val ssl = ctx.socketFactory.createSocket(raw, serverName, config.port, false)
                        as javax.net.ssl.SSLSocket
                // The name has to travel as SNI, which is the whole point of this check.
                runCatching {
                    val params = ssl.sslParameters
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(serverName))
                    ssl.sslParameters = params
                }
                ssl.use { it.startHandshake() }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Certificates are irrelevant here -- see [tlsAnswers]. */
    private object TrustEverything : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }

    /**
     * Re-testing the user's saved list has a stop flag of its own.
     *
     * It used to share [stopRequested] with the pool sweeps, which are a completely separate
     * activity that can be running at the same time. Pressing stop on the browse screen -- or
     * a sweep simply finishing, since every sweep clears the flag on its way out -- reached
     * into a saved-list run and ended it at the next batch boundary. Every row it had not got
     * to yet kept the "testing" marker for good, which is what "some of them are never tested"
     * was.
     */
    @Volatile
    private var stopMeasureAll = false

    fun stopMeasuring() { stopMeasureAll = true }

    /**
     * Re-test many servers at once, reporting each as it finishes.
     *
     * Returns the ids that were never reached, so the caller can clear their markers rather
     * than leave them looking like a test that is still running.
     */
    suspend fun measureAll(
        context: Context,
        uris: List<Pair<String, String>>,          // id to uri
        onResult: (id: String, delay: Int) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<String> = coroutineScope {
        FreeConfigEngine.ensureXrayEnv(context)
        stopMeasureAll = false
        val reached = java.util.Collections.synchronizedSet(HashSet<String>())
        val done = AtomicInteger(0)
        val gate = Semaphore(REAL_WORKERS)
        for (batch in uris.chunked(REAL_WORKERS)) {
            if (stopMeasureAll) break
            batch.map { (id, uri) ->
                async(Dispatchers.IO) {
                    gate.acquire()
                    // Every id reports a result, including when the measurement throws. The
                    // caller marks a row as "testing" before this runs, so an id that never
                    // reports back is left showing that marker for good -- which is what "some
                    // configs are never tested" looked like from the outside.
                    var ms = -1
                    try {
                        ms = measure(context, uri)
                    } finally {
                        gate.release()
                        reached.add(id)
                        onResult(id, ms)
                        onProgress(done.incrementAndGet(), uris.size)
                    }
                }
            }.awaitAll()
        }
        uris.map { it.first }.filter { it !in reached }
    }

    // ── the pipeline ────────────────────────────────────────────────────────────────────

    /**
     * Sweep [candidates], reporting each proven server as it is proven.
     *
     * [accept] decides what happens to a result: return true to keep it. Returning false puts
     * it aside without counting toward [want] -- which is how the balanced sweep enforces a
     * per-country share without throwing away servers it may need to top up with later.
     *
     * [shouldFinish] is checked after every accepted result; returning true ends the run early.
     * This is what lets the connect button stop at the first server that is actually good
     * rather than at the first server that merely answers.
     */
    private suspend fun pipeline(
        context: Context,
        candidates: List<QuickNode>,
        want: Int,
        onProgress: (Progress) -> Unit,
        onFound: (QuickNode) -> Unit,
        accept: (QuickNode) -> Boolean = { true },
        shouldFinish: (List<QuickNode>) -> Boolean = { it.size >= want },
    ): List<QuickNode> = coroutineScope {
        FreeConfigEngine.ensureXrayEnv(context)

        val results = ArrayList<QuickNode>()
        val overflow = ArrayList<QuickNode>()
        val probed = AtomicInteger(0)
        val reachable = AtomicInteger(0)
        val realTested = AtomicInteger(0)
        val total = candidates.size

        // Bounded on purpose: without a cap the TCP half races thousands of hosts ahead of the
        // testers and the user waits behind a queue that serves them no sooner.
        val survivors = Channel<QuickNode>(capacity = 64)

        // A local AtomicBoolean rather than a @Volatile local (which Kotlin does not allow):
        // producer, consumers and the reporter all read it from different threads.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun over() = finished.get() || stopRequested

        val producer = launch(Dispatchers.IO) {
            val gate = Semaphore(TCP_WORKERS)
            val jobs = ArrayList<kotlinx.coroutines.Job>()
            for (node in candidates) {
                if (over()) break
                gate.acquire()
                jobs += launch(Dispatchers.IO) {
                    try {
                        if (over()) return@launch
                        val ms = tcpOpen(node.host, node.port, TCP_TIMEOUT_MS)
                        probed.incrementAndGet()
                        if (ms >= 0) {
                            node.tcp = ms
                            reachable.incrementAndGet()
                            if (!over()) survivors.send(node)
                        }
                    } catch (e: Exception) {
                        // A closed channel means the consumers are done; nothing to report.
                    } finally {
                        gate.release()
                    }
                }
            }
            jobs.forEach { it.join() }
            survivors.close()
        }

        val consumers = List(REAL_WORKERS) {
            launch(Dispatchers.IO) {
                for (node in survivors) {
                    if (over()) break
                    // The cheap gate first: a candidate whose TLS endpoint is gone costs one
                    // round trip to reject here and ten seconds to reject below.
                    val parsed = com.mlmvpn.scanner.utils.VpnConfig.parseUri(node.uri)
                    if (parsed != null && !tlsAnswers(parsed)) {
                        realTested.incrementAndGet()
                        continue
                    }
                    val ms = measure(context, node.uri, skipTcp = true)
                    realTested.incrementAndGet()
                    if (ms > 0) {
                        node.delay = ms
                        val stopNow = synchronized(results) {
                            if (accept(node)) {
                                results.add(node)
                                onFound(node)
                            } else {
                                overflow.add(node)
                            }
                            shouldFinish(results)
                        }
                        if (stopNow) {
                            finished.set(true)
                            break
                        }
                    }
                    onProgress(
                        Progress(
                            stage = Stage.DELAY,
                            probed = probed.get(), reachable = reachable.get(),
                            realTested = realTested.get(),
                            found = synchronized(results) { results.size },
                            total = total, target = want,
                        )
                    )
                }
            }
        }

        // A separate reporter, because the two halves progress independently and the strip has
        // to show the cheap sweep moving even while no real test has finished yet.
        val reporter = launch {
            while (!over() && producer.isActive) {
                onProgress(
                    Progress(
                        stage = Stage.TCP,
                        probed = probed.get(), reachable = reachable.get(),
                        realTested = realTested.get(),
                        found = synchronized(results) { results.size },
                        total = total, target = want,
                    )
                )
                kotlinx.coroutines.delay(250)
            }
        }

        consumers.forEach { it.join() }
        finished.set(true)
        survivors.cancel()
        producer.cancel()
        reporter.cancel()

        // Top up from what the per-country cap set aside, so a run that asked for 50 comes
        // back with 50 whenever 50 exist at all.
        synchronized(results) {
            if (results.size < want && overflow.isNotEmpty()) {
                results.addAll(overflow.sortedBy { it.delay }.take(want - results.size))
            }
            results.sortBy { it.delay }
        }
        results
    }

    /**
     * The connect button's search: come back with something usable as fast as possible.
     *
     * Returns at the first server whose real delay is at or under [GOOD_ENOUGH_MS]. If the
     * servers that answer are all slow, it keeps looking until [deadlineMs] and then returns
     * the best of them -- a slow connection still beats no connection, but not at the cost of
     * taking the very first thing that replies when something better is seconds away.
     */
    suspend fun quickest(
        context: Context,
        candidates: List<QuickNode>,
        deadlineMs: Long = 20_000,
        onProgress: (Progress) -> Unit = {},
    ): QuickNode? {
        if (candidates.isEmpty()) return null
        if (running) throw IllegalStateException(S(R.string.a_search_is_already_running))
        running = true
        stopRequested = false
        val found = ArrayList<QuickNode>()
        try {
            withTimeoutOrNull(deadlineMs) {
                pipeline(
                    context = context,
                    candidates = candidates.shuffled(),
                    want = 1,
                    onProgress = onProgress,
                    onFound = { synchronized(found) { found.add(it) } },
                    // Any working server is worth remembering; only a good one ends the search.
                    shouldFinish = { it.any { node -> node.delay in 1..GOOD_ENOUGH_MS } },
                ).let { synchronized(found) { for (n in it) if (found.none { f -> f.id == n.id }) found.add(n) } }
            }
        } finally {
            running = false
            stopRequested = false
        }
        return synchronized(found) { found.filter { it.delay > 0 }.minByOrNull { it.delay } }
    }

    /**
     * Find [want] working servers among [candidates]. Used by the browse screen's sweeps.
     */
    suspend fun scan(
        context: Context,
        candidates: List<QuickNode>,
        want: Int,
        onProgress: (Progress) -> Unit,
        onFound: (QuickNode) -> Unit,
    ): List<QuickNode> {
        if (running) throw IllegalStateException(S(R.string.a_search_is_already_running))
        if (candidates.isEmpty()) throw IllegalStateException(S(R.string.there_is_no_server_for_this_country))
        running = true
        stopRequested = false
        try {
            val target = want.coerceIn(1, candidates.size)
            onProgress(Progress(Stage.TCP, total = candidates.size, target = target))
            val results = pipeline(
                context = context,
                candidates = candidates.shuffled(),
                want = target,
                onProgress = onProgress,
                onFound = onFound,
            )
            onProgress(
                Progress(
                    Stage.DONE, found = results.size, target = target,
                    stopped = stopRequested, empty = results.isEmpty(),
                )
            )
            return results
        } finally {
            running = false
            stopRequested = false
        }
    }

    /**
     * Find [want] working servers spread across [countries].
     *
     * "50 servers from 5 countries" has to mean 10 from each, or it is just "50 servers" with
     * a filter on top -- one popular country would supply all of them. Candidates are
     * interleaved round-robin so every country is being probed from the start, and a country
     * that has already contributed its share puts further results aside rather than losing
     * them, so a country that cannot fill its quota is topped up from the others at the end
     * instead of leaving the run short.
     */
    suspend fun scanBalanced(
        context: Context,
        byCountry: Map<String, List<QuickNode>>,
        want: Int,
        onProgress: (Progress) -> Unit,
        onFound: (QuickNode) -> Unit,
    ): List<QuickNode> {
        if (running) throw IllegalStateException(S(R.string.a_search_is_already_running))
        val pools = byCountry.filterValues { it.isNotEmpty() }
        if (pools.isEmpty()) throw IllegalStateException(S(R.string.there_are_no_servers_for_these_countries))

        running = true
        stopRequested = false
        try {
            val perCountry = kotlin.math.ceil(want.toDouble() / pools.size).toInt().coerceAtLeast(1)

            // Round-robin interleave, so the sweep is working on every country at once rather
            // than finishing one before it starts the next.
            val shuffledPools = pools.mapValues { it.value.shuffled() }
            val interleaved = ArrayList<QuickNode>()
            var index = 0
            while (true) {
                var appended = false
                for ((_, list) in shuffledPools) {
                    if (index < list.size) {
                        interleaved.add(list[index])
                        appended = true
                    }
                }
                if (!appended) break
                index++
            }

            val perCountryCount = HashMap<String, Int>()
            val target = want.coerceIn(1, interleaved.size)
            onProgress(Progress(Stage.TCP, total = interleaved.size, target = target))

            val results = pipeline(
                context = context,
                candidates = interleaved,
                want = target,
                onProgress = onProgress,
                onFound = onFound,
                accept = { node ->
                    val code = node.country ?: "??"
                    val used = perCountryCount.getOrDefault(code, 0)
                    if (used < perCountry) {
                        perCountryCount[code] = used + 1
                        true
                    } else {
                        false
                    }
                },
            )
            onProgress(
                Progress(
                    Stage.DONE, found = results.size, target = target,
                    stopped = stopRequested, empty = results.isEmpty(),
                )
            )
            return results
        } finally {
            running = false
            stopRequested = false
        }
    }
}
