package com.mlmvpn.scanner.engines.tgproxy

import android.content.Context
import com.mlmvpn.scanner.data.CfEdge
import com.mlmvpn.scanner.data.CfFamily
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.engines.cfdoctor.CfBogon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress

/**
 * Which Cloudflare address carries the bridge best on THIS network, for the user's own relay.
 *
 * Candidates, best evidence first: last time's winners on this network, the clean addresses the
 * app's own IP scanner found (its archive), the edges the app has proven here, and what DNS says for
 * the relay's name. Every candidate is tried for real -- the relay's bridge page through that
 * address, twice, the faster kept -- and the quickest few must then pass the full self-test
 * (session, WebSocket, a question to a Telegram data centre) before they are used.
 */
object TgCleanIp {

    data class Pick(val ip: String, val ms: Long)

    /** What a pick found; [fromScanner] = how many of the candidates came from the scanner's archive. */
    data class Result(val picks: List<Pick>, val tried: Int, val fromScanner: Int)

    private const val PREFS = "tgbridge"
    private const val MAX_CANDIDATES = 16
    private const val VERIFY = 4
    private const val KEEP = 4
    private const val PAGE_MS = 6_000L

    suspend fun pick(context: Context, base: OkHttpClient, host: String, secret: String, onTried: (Int) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val network = runCatching { CfFamily.networkKey(app) }.getOrDefault("unknown")
            val remembered = remembered(app, network, host)
            val archive = scannerArchive(app)
            val proven = runCatching { CfEdge.good(app, 4) }.getOrDefault(emptyList())
            val dns = runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') } }.getOrDefault(emptyList())
            val v6 = if (runCatching { CfFamily.hasIpv6RouteCached() }.getOrDefault(false)) CfFamily.sampleV6(2) else emptyList()
            val list = LinkedHashSet<String>()
            (remembered + archive.take(10) + proven + dns + v6).map(::clean).filter(::usable).forEach { list += it }
            if (list.size < 4) runCatching { CfEdge.find(app, host, 4) }.getOrDefault(emptyList()).forEach { list += it }
            val candidates = list.take(MAX_CANDIDATES)
            onTried(candidates.size)

            val reached = measure(base, host, secret, candidates)
            val verified = coroutineScope {
                reached.take(VERIFY).map { p ->
                    async {
                        val client = pinned(base, host, p.ip)
                        val ok = runCatching { TgProbe(client, client).probe(host, secret, viaApp = true).ok }.getOrDefault(false)
                        if (ok) p else null
                    }
                }.awaitAll().filterNotNull()
            }
            if (verified.isNotEmpty()) remember(app, network, host, verified.map { it.ip })
            verified.filter { it.ip in archive }.forEach { runCatching { CfEdge.noteGood(app, it.ip, it.ms) } }
            Result(verified.take(KEEP), candidates.size, candidates.count { it in archive })
        }

    /** The bridge page through each address, two shots, the better kept; working ones fastest first. */
    private suspend fun measure(base: OkHttpClient, host: String, secret: String, ips: List<String>): List<Pick> {
        val gate = Semaphore(6)
        val url = TgLink.bridgeUrl(host, secret)
        return coroutineScope {
            ips.map { ip ->
                async {
                    gate.withPermit {
                        val client = pinned(base, host, ip)
                        val a = shot(client, url) ?: return@withPermit null
                        val b = shot(client, url)
                        Pick(ip, if (b != null) minOf(a, b) else a)
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.ms }
        }
    }

    private suspend fun shot(client: OkHttpClient, url: String): Long? = withTimeoutOrNull(PAGE_MS) {
        val t0 = System.currentTimeMillis()
        runCatching {
            client.newCall(Request.Builder().url(url).build()).await().use { res ->
                if (res.code == 200 && res.body.string().contains("BOOT=")) System.currentTimeMillis() - t0 else null
            }
        }.getOrNull()
    }

    /** [base] with [host] pinned to [ip]; a fresh connection pool so no other edge's connection is reused. */
    fun pinned(base: OkHttpClient, host: String, ip: String): OkHttpClient {
        val address = InetAddress.getByName(ip)
        return base.newBuilder()
            .connectionPool(okhttp3.ConnectionPool())
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = if (hostname.equals(host, true)) listOf(address) else Dns.SYSTEM.lookup(hostname)
            })
            .build()
    }

    /** The clean addresses the app's IP scanner kept, newest first. */
    private fun scannerArchive(context: Context): List<String> = runCatching {
        val gm = GroupManager(context)
        gm.loadIpArchives()
        // The flow's snapshot: the list behind it is guarded by the manager's lock and a scan may be writing.
        gm.ipArchivesFlow.value.flatMap { it.ips }.map(::clean).filter(::usable).distinct().takeLast(40).reversed()
    }.getOrDefault(emptyList())

    private fun clean(a: String): String = a.trim().removePrefix("[").let {
        if (it.contains(']')) it.substringBefore(']') else if (it.count { c -> c == ':' } == 1) it.substringBefore(':') else it
    }

    private fun usable(a: String): Boolean {
        if (a.isEmpty() || CfBogon.isBogon(a)) return false
        return if (a.contains(':')) a.count { it == ':' } >= 2 else a.split('.').let { p -> p.size == 4 && p.all { (it.toIntOrNull() ?: 999) in 0..255 } }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun remembered(context: Context, network: String, host: String): List<String> =
        prefs(context).getString("edges|$network|$host", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    private fun remember(context: Context, network: String, host: String, ips: List<String>) {
        prefs(context).edit().putString("edges|$network|$host", ips.take(KEEP).joinToString(",")).apply()
    }
}
