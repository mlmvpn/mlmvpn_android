package com.mlmvpn.scanner.data

import android.content.Context
import com.mlmvpn.scanner.models.VpnNode
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Combining a config with clean IPs, as a library rather than as a button's onClick.
 *
 * This logic used to live inline in three places on the scanner screen -- the action sheet, the
 * archive re-mix, and the group card's health test -- which is why the guided flow could not do any
 * of it: an assistant cannot press a button that only exists inside a dialog the user opened.
 *
 * Nothing here touches the UI. The screen calls it, and so does [com.mlmvpn.scanner.ui.CombineCoach].
 */
object CombineEngine {

    /**
     * One config × one clean IP.
     *
     * The address is swapped for the IP and the ORIGINAL host is preserved as `sni=` and `host=`
     * so the TLS handshake still presents the name the server expects -- that pairing is the whole
     * trick, and dropping either half produces a config that connects to nothing.
     *
     * Two link shapes exist and only one has a `user@host:port` to edit. `vmess://` is a base64
     * JSON object and `ss://` in its legacy spelling is a base64 `method:password@host:port` blob;
     * neither contains a literal `@`, so the string surgery below found nothing to rewrite and
     * returned the link UNCHANGED. That failed silently and expensively: a combine produced N
     * copies of the original config, each renamed `[1.2.3.4]` as though it pointed there, and the
     * health test then reported every one of them dead. They get their own readers instead.
     */
    fun rewrite(uri: String, ip: String, newName: String, port: Int? = null): String {
        val trimmed = uri.trim()
        if (trimmed.startsWith("vmess://", ignoreCase = true)) {
            return rewriteVmess(trimmed, ip, newName, port) ?: uri
        }
        if (trimmed.startsWith("ss://", ignoreCase = true) && !hasPlainAuthority(trimmed)) {
            return rewriteLegacyShadowsocks(trimmed, ip, newName, port) ?: uri
        }

        var newUri = uri
        try {
            val schemeIndex = newUri.indexOf("://")
            if (schemeIndex == -1) return uri
            val atIndex = newUri.indexOf("@", schemeIndex)
            if (atIndex == -1) return uri

            val endOfHostPortIndex = listOf(
                newUri.indexOf("/", atIndex),
                newUri.indexOf("?", atIndex),
                newUri.indexOf("#", atIndex),
                newUri.length,
            ).filter { it != -1 }.minOrNull() ?: newUri.length

            val hostPort = newUri.substring(atIndex + 1, endOfHostPortIndex)
            val portIndex = hostPort.lastIndexOf(":")
            val originalHost = if (portIndex != -1) hostPort.substring(0, portIndex) else hostPort
            // The port the scan actually got through on wins over the config's own. Cloudflare
            // proxies all its TLS ports to the same origin, so an exit that only opened on 2053
            // still reaches this worker -- but only if the config asks for 2053.
            val effectivePort = port?.toString()
                ?: if (portIndex != -1) hostPort.substring(portIndex + 1) else "443"
            val newHostPort = if (ip.contains(":")) "[$ip]:$effectivePort" else "$ip:$effectivePort"

            newUri = newUri.substring(0, atIndex + 1) + newHostPort + newUri.substring(endOfHostPortIndex)

            fun insertParam(param: String) {
                val qIndex = newUri.indexOf("?")
                val hIndex = newUri.indexOf("#")
                val insertPos = if (qIndex != -1) qIndex + 1 else if (hIndex != -1) hIndex else newUri.length
                val prefix = if (qIndex == -1) "?" else ""
                val suffix = if (qIndex != -1) "&" else ""
                newUri = newUri.substring(0, insertPos) + prefix + param + suffix + newUri.substring(insertPos)
            }

            /**
             * Is this parameter already in the QUERY?
             *
             * A plain `contains("host=")` over the whole link also matches the `#remark`, which is
             * user-chosen text, and matches `xhost=`/`myhost=` in the query. Either way the
             * parameter is then not written, and the config connects to a clean IP while telling
             * the edge nothing about which name it wants -- which is the one thing this rewrite
             * exists to prevent.
             */
            fun hasQueryParam(name: String): Boolean {
                val q = newUri.indexOf('?')
                if (q == -1) return false
                val end = newUri.indexOf('#', q).let { if (it == -1) newUri.length else it }
                return newUri.substring(q + 1, end)
                    .split('&')
                    .any { it.substringBefore('=').trim().equals(name, ignoreCase = true) }
            }
            if (!hasQueryParam("sni")) insertParam("sni=$originalHost")
            if (!hasQueryParam("host")) insertParam("host=$originalHost")

            val hashIndex = newUri.indexOf("#")
            val encodedName = android.net.Uri.encode(newName)
            newUri = if (hashIndex != -1) {
                newUri.substring(0, hashIndex) + "#" + encodedName
            } else {
                newUri + "#" + encodedName
            }
        } catch (e: Exception) {
            return uri
        }
        return newUri
    }

    /** True for the SIP002 spelling of `ss://`, whose host really is in the URI. */
    private fun hasPlainAuthority(uri: String): Boolean {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd == -1) return false
        val body = uri.substring(schemeEnd + 3).substringBefore('#')
        return body.contains('@')
    }

    /**
     * `vmess://<base64 JSON>` -- rewritten as JSON, since there is no authority to edit.
     *
     * `add` becomes the clean IP and `port` the one that answered; `host` and `sni` take the
     * ORIGINAL address when they are empty, which is the same pairing the URI path writes into
     * `host=`/`sni=`. `ps` (the remark inside the blob) is updated too, because most clients show
     * that rather than the fragment.
     */
    private fun rewriteVmess(uri: String, ip: String, newName: String, port: Int?): String? = try {
        val body = uri.substringAfter("vmess://").substringBefore('#')
        val decoded = decodeB64(body)
        val json = if (decoded == null) null else org.json.JSONObject(decoded)
        if (json == null) {
            null
        } else {
            val originalHost = json.optString("add")
            if (originalHost.isBlank()) {
                null
            } else {
                json.put("add", ip)
                port?.let { json.put("port", it.toString()) }
                if (json.optString("host").isBlank()) json.put("host", originalHost)
                if (json.optString("sni").isBlank()) json.put("sni", originalHost)
                json.put("ps", newName)
                val encoded = android.util.Base64.encodeToString(
                    json.toString().toByteArray(),
                    android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                )
                "vmess://$encoded#" + android.net.Uri.encode(newName)
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Legacy `ss://<base64 of method:password@host:port>`.
     *
     * Re-encoded rather than upgraded to SIP002: some cores still only read this spelling, and a
     * combine is not the place to change a link's format under the user.
     */
    private fun rewriteLegacyShadowsocks(
        uri: String,
        ip: String,
        newName: String,
        port: Int?,
    ): String? = try {
        val body = uri.substringAfter("ss://").substringBefore('#').substringBefore('?')
        val decoded = decodeB64(body)
        val split = decoded?.lastIndexOf('@') ?: -1
        if (decoded == null || split == -1) {
            null
        } else {
            val credentials = decoded.substring(0, split)
            val endpoint = decoded.substring(split + 1)
            val portSep = endpoint.lastIndexOf(':')
            val originalPort = if (portSep != -1) endpoint.substring(portSep + 1) else "443"
            val effectivePort = port?.toString() ?: originalPort
            val encoded = android.util.Base64.encodeToString(
                "$credentials@$ip:$effectivePort".toByteArray(),
                android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            "ss://$encoded#" + android.net.Uri.encode(newName)
        }
    } catch (e: Exception) {
        null
    }

    /** Tolerant base64: these links appear both padded and unpadded, URL-safe and not. */
    private fun decodeB64(raw: String): String? {
        val cleaned = raw.trim().replace("-", "+").replace("_", "/").replace(Regex("\\s"), "")
        if (cleaned.isEmpty()) return null
        val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
        return try {
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }

    /** Every source config against every IP, named so the pairing is readable in the list. */
    fun combine(
        sourceNodes: List<VpnNode>,
        ips: List<String>,
        groupTitle: String,
        /** The port each IP answered on, when the scan discovered one. */
        portFor: (String) -> Int? = { null },
        /**
         * The link parameters that carry the handshake the scan proved for an address
         * (`CfNetManager.handshakeParams`), written into each config built on it -- see [withParams].
         * Without them a combined config was health-tested and connected with whatever the strategy
         * store held for CONNECT, not with the handshake that found its address: on a line where
         * Cloudflare IPv4 passes only with ECH or a fragmented hello, every combination read dead.
         */
        handshakeFor: (VpnNode, String) -> Map<String, String?> = { _, _ -> emptyMap() },
    ): List<VpnNode> =
        sourceNodes.flatMap { node ->
            ips.map { ip ->
                val newName = "${node.name} [$ip]"
                node.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    name = newName,
                    uri = withParams(rewrite(node.uri, ip, newName, portFor(ip)), handshakeFor(node, ip)),
                    groupTitle = groupTitle,
                )
            }
        }

    /**
     * [uri] with [params] set in its query (a null value removes the parameter), everything else --
     * the order of the other parameters, their spelling, the remark -- exactly as it was. Values are
     * percent-encoded: a raw `+` in `ech=cloudflare-ech.com+udp://1.1.1.1` would read back as a space.
     * `vmess://` is edited as its JSON. Any other link comes back unchanged.
     */
    fun withParams(uri: String, params: Map<String, String?>): String {
        if (params.isEmpty()) return uri
        val trimmed = uri.trim()
        if (trimmed.startsWith("vmess://", ignoreCase = true)) return withVmessParams(trimmed, params) ?: uri
        if (!trimmed.startsWith("vless://", ignoreCase = true) && !trimmed.startsWith("trojan://", ignoreCase = true)) return uri
        val hash = trimmed.indexOf('#').let { if (it == -1) trimmed.length else it }
        val head = trimmed.substring(0, hash)
        val remark = trimmed.substring(hash)
        val q = head.indexOf('?')
        val base = if (q == -1) head else head.substring(0, q)
        val names = params.keys.map { it.lowercase() }.toSet()
        val kept = (if (q == -1) emptyList() else head.substring(q + 1).split('&'))
            .filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in names }
        val added = params.mapNotNull { (k, v) -> v?.let { "$k=" + android.net.Uri.encode(it) } }
        val query = (kept + added).joinToString("&")
        return base + (if (query.isEmpty()) "" else "?$query") + remark
    }

    private fun withVmessParams(uri: String, params: Map<String, String?>): String? = try {
        val body = uri.substringAfter("vmess://").substringBefore('#')
        val json = org.json.JSONObject(decodeB64(body) ?: throw IllegalArgumentException())
        params.forEach { (k, v) -> if (v == null) json.remove(k) else json.put(k, v) }
        val remark = uri.substringAfter('#', "")
        "vmess://" + android.util.Base64.encodeToString(json.toString().toByteArray(),
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) + (if (remark.isEmpty()) "" else "#$remark")
    } catch (e: Exception) {
        null
    }

    /**
     * Which ports a group of configs uses.
     *
     * A scan that only ever probes 443 is useless to a group whose workers answer on 2053, and
     * probing all six Cloudflare TLS ports for every address would multiply the work by six on
     * the common single-port group. So: probe exactly what the configs ask for, most-used first.
     */
    fun portsOf(nodes: List<VpnNode>): List<Int> {
        val counted = nodes
            .mapNotNull { VpnConfig.parseUri(it.uri)?.port }
            .filter { it in 1..65535 }
            .groupingBy { it }
            .eachCount()
        if (counted.isEmpty()) return listOf(443)
        return counted.entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * Measures every combined config through a real proxied request and keeps the ones that answer.
     *
     * The delay is KEPT on the node rather than thrown away. The old health test only asked
     * "alive?" and discarded the number, so the connection screen had to measure all of them a
     * second time before it could sort -- which is the step the guided flow was about to tell the
     * user to do for no reason.
     */
    suspend fun measureHealthy(
        context: Context,
        nodes: List<VpnNode>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<VpnNode> = withContext(Dispatchers.IO) {
        ensureCoreEnv(context)

        // Three at a time: these are real handshakes against the same edge, and more than that
        // reads as concurrent-connection abuse and comes back as inflated numbers.
        val gate = Semaphore(3)
        val done = AtomicInteger(0)
        val total = nodes.size

        val cfnet = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context)
        nodes.map { node ->
            async {
                gate.acquire()
                try {
                    val delayMs = measureUri(context, node.uri)
                    // How each row was dialled, ids and numbers only: «written» = the handshake the
                    // combine wrote into it, else the strategy the engine stamped, else none.
                    val how = runCatching { VpnConfig.parseUri(node.uri)?.let { cfnet.describe(it, com.mlmvpn.scanner.engines.cfnet.CfStage.CONNECT) } }.getOrNull()
                    android.util.Log.i("CfNet", "health: via=$how ${if (delayMs > 0) "ok ms=$delayMs" else "no answer"}")
                    if (delayMs > 0) node.copy().also { it.delay = "${delayMs}ms" } else null
                } finally {
                    gate.release()
                    onProgress(done.incrementAndGet(), total)
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** How long one measurement may take before it counts as a failure. */
    private const val DELAY_TEST_TIMEOUT_MS = 12_000L

    /**
     * One config, one real proxied request, in milliseconds -- or 0 when it did not answer.
     *
     * The single implementation of "does this config work, and how fast", so the number the config
     * picker shows and the number the health test keeps are produced the same way. It also makes
     * the core initialisation and the timeout one decision instead of six copies of one.
     */
    suspend fun measureUri(
        context: Context,
        uri: String,
        timeoutMs: Long = DELAY_TEST_TIMEOUT_MS,
    ): Long = withContext(Dispatchers.IO) {
        try {
            val parsed = VpnConfig.parseUri(uri)
            if (parsed == null || parsed.address.isEmpty()) return@withContext 0L
            // Health is a verdict about THIS endpoint. Replacing it with an IPv6 twin or another
            // cached edge mislabels every row and can discard IPv4 routes that need a TLS shape.
            val config = parsed
            com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context)
            ensureCoreEnv(context)
            val json = com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(config)
            measureDelay(json, timeoutMs = timeoutMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: this is a JNI call, so a device without the core raises
            // UnsatisfiedLinkError, which is an Error and walked straight past `catch (Exception)`.
            0L
        }
    }

    private const val DELAY_TEST_URL = "https://clients3.google.com/generate_204"

    /**
     * Measure one outbound, with a timeout THAT ACTUALLY FIRES.
     *
     * This used to be `withTimeoutOrNull { Libv2ray.measureOutboundDelay(...) }`, and that bound
     * does not exist. `withTimeoutOrNull` cancels at a suspension point, and a JNI call into the Go
     * core is not one: the coroutine sits inside it until the core chooses to come back, timeout or
     * no timeout. The core's own internal timeout does not always save it either.
     *
     * Measured on the device, and it is not a near miss: three test instances logged
     * `core: Xray 26.6.27 started` and then **nothing for over three minutes** on a 12-second
     * timeout. Nothing downstream could recover, because nothing downstream ever ran -- the pass
     * never finished, so the list never filled, the spinner never stopped, and the front was never
     * released. That is the whole of «همش روی لودینگ در حال اندازه‌گیری گیر میکنه», and it is why a
     * measurement over a network where the handshake stalls looked frozen rather than slow.
     *
     * So the blocking call goes on a thread of its own that we are willing to ABANDON, and the wait
     * is on a Deferred -- which is a real suspension point, so the timeout works. The abandoned
     * thread costs one stuck core instance until its socket errors out; restarting whatever it was
     * measuring through (the SNI front, say) breaks its connection and collects it.
     */
    private val delayThreads = kotlinx.coroutines.sync.Semaphore(com.mlmvpn.scanner.utils.ThreadBudget.parallel(64))

    suspend fun measureDelay(
        json: String,
        url: String = DELAY_TEST_URL,
        timeoutMs: Long = DELAY_TEST_TIMEOUT_MS,
    ): Long {
        val done = kotlinx.coroutines.CompletableDeferred<Long>()
        // A thread of its own is held until the core returns, even after we walk away, so their
        // number is capped: "test all" on hundreds of configs started one per config and an old
        // phone ran out of threads (pthread_create failed, Android 8, 1.2.39). The permit goes back
        // when the thread ends, not when the wait times out.
        if (kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { delayThreads.acquire() } == null) return 0L
        // A bare daemon thread, not a dispatcher: the point is to be able to walk away, and a
        // coroutine on Dispatchers.IO would hold one of that pool's threads for as long as the core
        // holds this one. Daemon, so a stuck measurement can never keep the process alive.
        try {
            Thread({
                val v = try {
                    // In `:xmeasure` (XrayMeasureService) only: a public config that panics Xray ends
                    // that process, not the app. It used to fall back to this process when that one
                    // could not be reached -- and then the panic closed the app after all.
                    isolated(json, url, timeoutMs)
                } catch (t: Throwable) {
                    // Throwable: this is JNI, so a device without the core raises UnsatisfiedLinkError,
                    // which is an Error and walks straight past `catch (Exception)`.
                    0L
                } finally {
                    delayThreads.release()
                }
                done.complete(v)
            }, "xray-delay").apply { isDaemon = true }.start()
        } catch (_: OutOfMemoryError) {
            delayThreads.release()
            return 0L
        }
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { done.await() } ?: 0L
    }

    @Volatile private var appContext: Context? = null
    private val measureHost by lazy { com.mlmvpn.core.warp.AwgHostClient(com.mlmvpn.core.warp.XrayMeasureService::class.java, "XrayMeasureClient") }

    /**
     * One delay test in the `:xmeasure` process: the delay, or 0 when nothing came back -- a
     * timeout, that process dying under it (a panicking config), or no process to reach.
     */
    private fun isolated(json: String, url: String, timeoutMs: Long): Long {
        val ctx = appContext ?: return 0L
        val reply = measureHost.call(ctx, com.mlmvpn.core.warp.XrayMeasureService.MSG_MEASURE, android.os.Bundle().apply {
            putString(com.mlmvpn.core.warp.XrayMeasureService.KEY_CONFIG, json)
            putString(com.mlmvpn.core.warp.XrayMeasureService.KEY_URL, url)
        }, timeoutMs + 2_000)
        return reply?.getLong(com.mlmvpn.core.warp.XrayMeasureService.KEY_DELAY) ?: 0L
    }

    /** From Application.onCreate, so every caller's tests go to `:xmeasure`, not only those that set up the core here. */
    fun bind(context: Context) { if (appContext == null) appContext = context.applicationContext }

    /** Idempotent; the core ignores a second call, and the first is what the JNI layer needs. */
    fun ensureCoreEnv(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        // No core runs in the main process any more (XrayHosts, `:xmeasure`), so it does not load
        // the core at all: a Go runtime in the app process was one more thing that could abort it,
        // and its library in every tombstone named it as the culprit of crashes it had no part in.
        if (com.mlmvpn.core.warp.XrayHosts.inMainProcess(context)) return
        try {
            val keyBytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(keyBytes)
            val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or
                android.util.Base64.NO_WRAP
            val xudpBaseKey = android.util.Base64.encodeToString(keyBytes, flags)
            libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, xudpBaseKey)
        } catch (e: Throwable) {
            // Already initialised, which is the normal case after the first test of a session.
        }
    }

    /**
     * Why a scan or a combine produced nothing, in one sentence the user can act on.
     *
     * Guessing is worse than silence here, so each branch is a condition the app can actually
     * observe. The last one is the honest "we do not know", and it still names the next move.
     */
    fun diagnose(
        context: Context,
        baseConfig: String,
        testedCount: Int,
        foundCount: Int,
        combinedCount: Int,
        healthyCount: Int,
    ): String {
        val parsed = VpnConfig.parseUri(baseConfig)
        return when {
            baseConfig.isBlank() ->
                S(R.string.the_base_config_is_empty_pick_a)

            parsed == null ->
                S(R.string.the_base_config_could_not_be_parsed)

            parsed.address == "127.0.0.1" ->
                S(R.string.this_config_already_points_at_the_local) +
                    S(R.string.makes_no_sense_pick_a_direct_config)

            com.mlmvpn.scanner.MyVpnService.isRunning ->
                S(R.string.a_tunnel_is_up_and_scanning_from) +
                    S(R.string.scan_again)

            testedCount == 0 ->
                S(R.string.no_ip_was_tested_check_the_device)

            foundCount == 0 ->
                S(R.string.none_of_the_ips_tested_answered_that, testedCount) +
                    S(R.string.has_just_blocked_the_cloudflare_range_try) +
                    S(R.string.raise_the_definitive_test_count_so_a)

            combinedCount == 0 ->
                S(R.string.working_ips_were_found_but_no_combination) +
                    S(R.string.had_no_configs_take_some_from_the)

            healthyCount == 0 ->
                S(R.string.combinations_were_built_but_none_answered_the, combinedCount) +
                    S(R.string.the_base_config_itself_probably_does_not) +
                    S(R.string.and_try_again)

            else -> S(R.string.nothing_was_built_try_once_more_with)
        }
    }
}
