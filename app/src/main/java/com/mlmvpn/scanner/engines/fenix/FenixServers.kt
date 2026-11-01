package com.mlmvpn.scanner.engines.fenix

import com.mlmvpn.scanner.engines.fenix.FenixHttp.mozilla
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket

/**
 * Firefox VPN's servers: one Fastly MASQUE proxy per country (`muc139.m1.fastly-masque.net:2499`),
 * from Mozilla's public Remote Settings list. 33 countries on 2026-10-03, every one reachable from
 * an Iranian line over TLS 1.3 with HTTP/2.
 */
object FenixServers {

    private const val LIST_URL =
        "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"

    /** Selections that are not a country. */
    const val FASTEST = "fastest"
    const val RECOMMENDED = "REC"

    data class Server(val country: String, val name: String, val host: String, val port: Int)

    /** The list, from the cache when it is under [MAX_AGE_MS] old; refetched otherwise. */
    internal suspend fun list(store: FenixStore, force: Boolean = false): List<Server> {
        val cached = store.serversJson()?.let { parse(it) }.orEmpty()
        if (!force && cached.isNotEmpty() && System.currentTimeMillis() - store.serversAt < MAX_AGE_MS) return cached
        val fresh = runCatching { fetch() }.getOrNull()
        if (fresh != null && parse(fresh).isNotEmpty()) { store.saveServers(fresh); return parse(fresh) }
        return cached
    }

    internal fun cached(store: FenixStore): List<Server> = store.serversJson()?.let { parse(it) }.orEmpty()

    private suspend fun fetch(): String = withContext(Dispatchers.IO) {
        FenixHttp.execute(Request.Builder().url(LIST_URL).mozilla().build()).use { r ->
            if (r.code == 406) throw FenixAuth.ChallengeNeeded(r.request.url.host)
            if (!r.isSuccessful) throw IllegalStateException("server list: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    /** One server per country; Mozilla's "recommended" entry kept under [RECOMMENDED]. */
    internal fun parse(json: String): List<Server> = runCatching {
        val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
        val out = LinkedHashMap<String, Server>()
        for (i in 0 until data.length()) {
            val rec = data.getJSONObject(i)
            val c = rec.optJSONObject("country") ?: rec
            val code = c.optString("code").uppercase()
            val name = c.optString("name")
            // "CatchAll Anycast" is a second US entry that lands wherever Fastly likes: not a country.
            if (code.isBlank() || name.contains("anycast", ignoreCase = true)) continue
            val cities = c.optJSONArray("cities") ?: continue
            loop@ for (ci in 0 until cities.length()) {
                val servers = cities.getJSONObject(ci).optJSONArray("servers") ?: continue
                for (si in 0 until servers.length()) {
                    val s = servers.getJSONObject(si)
                    if (s.optBoolean("quarantined", false)) continue
                    val host = s.optString("hostname").takeIf { it.isNotBlank() } ?: continue
                    val port = s.optInt("port", 0).takeIf { it in 1..65535 } ?: continue
                    if (code !in out) out[code] = Server(code, name, host, port)
                    break@loop
                }
            }
        }
        out.values.toList()
    }.getOrDefault(emptyList())

    // ── reaching a server ───────────────────────────────────────────────────────────────────────

    /** The server's addresses, IPv4 first; empty when the name does not resolve here. */
    suspend fun resolve(host: String): List<InetAddress> = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            .sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    /**
     * Milliseconds for a full TLS handshake with [s] -- the cost every new connection would pay, and
     * proof the server answers from this line. Null when it does not within [timeoutMs].
     */
    suspend fun handshakeMs(s: Server, timeoutMs: Int = 4_000, address: InetAddress? = null): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val ip = address ?: InetAddress.getAllByName(s.host).firstOrNull { it is Inet4Address } ?: InetAddress.getByName(s.host)
            val start = System.nanoTime()
            Socket().use { raw ->
                raw.connect(InetSocketAddress(ip, s.port), timeoutMs)
                raw.soTimeout = timeoutMs
                val tls = FenixRoots.socketFactory.createSocket(raw, s.host, s.port, false) as SSLSocket
                tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(s.host)) }
                tls.use { it.startHandshake() }
            }
            (System.nanoTime() - start) / 1_000_000
        }.getOrNull()
    }

    /** [handshakeMs] for every server at once (a few at a time): country -> ms, -1 for no answer. */
    suspend fun pingAll(servers: List<Server>, onEach: (String, Long) -> Unit = { _, _ -> }): Map<String, Long> = coroutineScope {
        val gate = Semaphore(8)
        servers.map { s ->
            async {
                gate.withPermit {
                    val ms = handshakeMs(s) ?: -1L
                    onEach(s.country, ms)
                    s.country to ms
                }
            }
        }.awaitAll().toMap()
    }

    fun isV6(a: InetAddress) = a is Inet6Address

    private const val MAX_AGE_MS = 12 * 3600_000L
}
