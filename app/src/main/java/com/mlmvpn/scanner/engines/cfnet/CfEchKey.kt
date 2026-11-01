package com.mlmvpn.scanner.engines.cfnet

import android.content.Context
import android.net.Network
import android.util.Base64
import com.mlmvpn.scanner.engines.cfdoctor.AndroidNetEnv
import com.mlmvpn.scanner.engines.cfdoctor.DnsWire
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cloudflare's current ECH key (a base64 ECHConfigList), fetched by the app on the real line -- what
 * the Windows app's ech-key.js does.
 *
 * The ECH handshake's short form, `cloudflare-ech.com+udp://1.1.1.1`, makes the core ask 1.1.1.1 over
 * UDP/53 at dial time. A line that drops that (the user's own Wi-Fi did) turned a working method into
 * «ECH جواب نداد» while ECH itself was fine. So the key is asked here: plain DNS to four resolvers at
 * once, then the same question over HTTPS, on the physical network, and the core is handed the key
 * itself ([CfShape.echKeyProvider]). Kept an hour: Cloudflare rotates it.
 *
 * The one implementation for every engine that dials ECH (the cfnet strategies, FLUX, Proton's carrier).
 */
object CfEchKey {
    const val DOMAIN = "cloudflare-ech.com"
    private const val FRESH_MS = 3_600_000L
    private const val STAGE_MS = 3_000
    private val UDP = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "9.9.9.9")
    private val DOH = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "https://1.0.0.1/dns-query")

    @Volatile private var memo: Pair<Long, String>? = null

    init {
        // The ECH shapes read the key from here. Wired on first use, which is always before the first key.
        CfShape.echKeyProvider = { cached() }
    }

    /** The key fetched within the hour, or null. No I/O, so safe anywhere -- the config builders included. */
    fun cached(now: Long = System.currentTimeMillis()): String? =
        memo?.takeIf { now - it.first in 0..FRESH_MS }?.second

    /** The key for this line: the cached one when fresh, else asked now on the physical network. Blocking. */
    fun fetch(context: Context): String? =
        cached() ?: fetchOn(runCatching { AndroidNetEnv(context).picked?.network }.getOrNull())

    /** Asked now on [network] (null: the process default) -- UDP first, then DoH; remembered when found. Blocking. */
    fun fetchOn(network: Network?): String? {
        val key = race(UDP.map { server -> { udp(network, server) } }, STAGE_MS + 500L)
            ?: race(DOH.map { url -> { doh(network, url) } }, STAGE_MS + 1_500L)
            ?: return null
        memo = System.currentTimeMillis() to key
        return key
    }

    private fun udp(network: Network?, server: String): String? = DatagramSocket().use { s ->
        network?.bindSocket(s)
        s.soTimeout = STAGE_MS
        val id = java.security.SecureRandom().nextInt(65_536)
        val q = DnsWire.query(DOMAIN, 65, id)
        s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
        val buf = ByteArray(4_096)
        val p = DatagramPacket(buf, buf.size)
        s.receive(p)
        DnsWire.echConfig(buf.copyOf(p.length), id)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun doh(network: Network?, url: String): String? {
        val q = DnsWire.query(DOMAIN, 65, 0)
        val b64 = Base64.encodeToString(q, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val u = java.net.URL("$url?dns=$b64")
        val c = (network?.openConnection(u) ?: u.openConnection()) as java.net.HttpURLConnection
        c.connectTimeout = STAGE_MS; c.readTimeout = STAGE_MS
        c.setRequestProperty("Accept", "application/dns-message")
        return try {
            if (c.responseCode != 200) null
            else DnsWire.echConfig(c.inputStream.use { it.readBytes() }, 0)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        } finally { c.disconnect() }
    }

    /** The first non-null answer among [tasks], all asked at once, within [capMs]; the rest are dropped. */
    private fun race(tasks: List<() -> String?>, capMs: Long): String? {
        val pool = Executors.newFixedThreadPool(tasks.size) { r -> Thread(r, "cf-ech-key").apply { isDaemon = true } }
        try {
            val done = ExecutorCompletionService<String?>(pool)
            tasks.forEach { task -> done.submit(Callable { runCatching(task).getOrNull() }) }
            val deadline = System.currentTimeMillis() + capMs
            repeat(tasks.size) {
                val left = deadline - System.currentTimeMillis()
                val next = (if (left > 0) done.poll(left, TimeUnit.MILLISECONDS) else null) ?: return null
                runCatching { next.get() }.getOrNull()?.let { return it }
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }
}
