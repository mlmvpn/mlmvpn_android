package com.mlmvpn.scanner.engines.game

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * تست رقیب (race) بین DNSها -- و پورت‌های مختلف -- برای resolve کردن دامنه‌ی سرور بازی.
 *
 * کاملاً پشت‌پرده: کاربر هیچ‌وقت نمی‌بیند کدام DNS انتخاب شده. فقط نتیجه‌ی نهایی
 * (�Direct Boost فعال شد») را می‌بیند.
 *
 * مکانیزم: یک query واقعی DNS (type A) برای دامنه‌ی واقعی سرور بازی به همه‌ی ترکیب‌های
 * (IP سرور DNS, پورت) موازی ارسال می‌شود [GameDnsList]. برخلاف نسخه‌ی اول، سریع‌ترین جواب
 * دیگر معیار انتخاب نیست -- در عمل ثابت شد اپراتور می‌تواند پورت ۵۳ را رهگیری کند و یک جواب
 * جعلی/قدیمی سریع‌تر از جواب واقعیِ یک پورت جایگزین برگرداند. به‌جایش همه‌ی IPهای متفاوتی که
 * هر ترکیب برگرداند جمع می‌شود و caller (GameBoosterManager) واقعاً به همه‌شان پینگ می‌زند.
 */
object DnsRaceTester {

    private const val TAG = "DnsRaceTester"
    private const val QUERY_TIMEOUT_MS = 2000L

    // Confirmed in the field: Iranian ISPs transparently intercept/redirect ALL outbound UDP
    // port-53 traffic regardless of destination IP (every "different" DNS server got the exact
    // same kind of hijacked non-answer). A non-standard port isn't covered by that port-53
    // interception rule, so if the provider's own server also happens to listen there, the real
    // query gets through untouched. Only tried against the Iranian providers -- global resolvers
    // (Cloudflare/Google/Quad9) genuinely only listen on 53, so trying alt ports against them is
    // wasted effort.
    private val alternatePorts = listOf(53, 5353, 1053, 8053, 5300, 10053, 2053, 15353)

    data class DnsResult(
        val dnsServer: GameDnsList.DnsServer,
        val port: Int,
        val latencyMs: Long,
        val resolvedIp: String?
    )

    // OkHttp still has to resolve the DoH server's own hostname (e.g. "dns.google") before it can
    // open the HTTPS connection -- and that resolution normally goes through the same poisoned
    // system DNS we're trying to route around in the first place (confirmed in the field: on a
    // filtered network "dns.google" resolved to a private carrier IP like 10.x.x.x instead of
    // Google's real server, so the DoH request never even reached Google). Pinning known-good IPs
    // for these specific hostnames skips DNS entirely for the bootstrap step -- OkHttp still sends
    // the correct Host header and TLS SNI for the real hostname, so certificate validation still
    // passes; only the "where do I connect the socket" step is hardcoded.
    private val pinnedDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
            "dns.google" -> listOf(
                InetAddress.getByAddress(hostname, byteArrayOf(8, 8, 8, 8)),
                InetAddress.getByAddress(hostname, byteArrayOf(8, 8, 4, 4))
            )
            "cloudflare-dns.com" -> listOf(
                InetAddress.getByAddress(hostname, byteArrayOf(1, 1, 1, 1)),
                InetAddress.getByAddress(hostname, byteArrayOf(1, 0, 0, 1))
            )
            else -> okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }

    private val dohClient by lazy {
        OkHttpClient.Builder()
            .dns(pinnedDns)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * همه‌ی ترکیب‌های (DNS, پورت) رو موازی امتحان می‌کند و هر چیزی که واقعاً جواب داد برمی‌گردونه.
     *
     * مهم: [testHostname] باید دامنه‌ی واقعی‌ای باشه که قراره بعداً resolve بشه (مثلاً دامنه‌ی
     * سرور بازی)، نه یه دامنه‌ی عمومیِ همیشه-قابل-resolve مثل cloudflare.com.
     */
    private suspend fun raceAllCombos(testHostname: String): List<DnsResult> = withContext(Dispatchers.IO) {
        val results = ConcurrentHashMap<String, DnsResult>()

        // هر سرور رو روی همه‌ی پورت‌های جایگزین (برای DNSهای ایرانی) یا فقط ۵۳ (برای بین‌المللی)
        // امتحان کن.
        val combos = GameDnsList.servers.flatMap { dns ->
            val ports = if (dns.category == GameDnsList.DnsCategory.PUBLIC_INTL) listOf(53) else alternatePorts
            ports.map { port -> dns to port }
        }

        coroutineScope {
            combos.map { (dns, port) ->
                async(Dispatchers.IO) {
                    val result = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                        queryDns(dns, port, testHostname)
                    }
                    if (result != null) {
                        results["${dns.ip}:$port"] = result
                        Log.d(TAG, "DNS ${dns.name} (${dns.ip}:$port): ${result.latencyMs}ms -> ${result.resolvedIp}")
                    }
                }
            }.awaitAll()
        }

        if (results.isEmpty()) Log.w(TAG, "هیچ ترکیب DNS:پورتی پاسخ نداد")
        results.values.toList()
    }

    /**
     * لیست سرورهای DNS (به همراه IP resolve شده) که واقعاً به [testHostname] جواب دادن، از سریع
     * به کند مرتب شده (تکراری‌ها بر اساس IP سرور حذف شدن). در عمل ثابت شد سریع‌ترین جواب می‌تونه
     * جعلی/قدیمی باشه (رهگیری‌شده روی پورت ۵۳)، پس این لیست کامل رو برمی‌گردونه تا caller هم
     * بتونه IPهای resolve شده رو واقعاً پینگ کنه، هم (اگه هیچ‌کدوم وصل نشد) از خودِ سرورهای DNS
     * به‌عنوان DNS یک VPN محلی استفاده کنه و بذاره سیستم‌عامل/بازی با stack واقعی شبکه وصل بشه.
     * latencyMs همون تاخیر پاسخ DNS است (نه پینگ بازی)، فقط به‌عنوان یک تخمین/نمایش استفاده بشه.
     */
    suspend fun findWorkingDnsServers(testHostname: String): List<DnsResult> {
        val results = raceAllCombos(testHostname).sortedBy { it.latencyMs }.distinctBy { it.dnsServer.ip }
        Log.d(TAG, "findWorkingDnsServers($testHostname): ${results.size} DNS جواب داد: ${results.map { it.dnsServer.ip }}")
        return results
    }

    /**
     * DNS-over-HTTPS: وقتی همه‌ی DNSهای UDP:53 با «پاسخ گرفتیم ولی IP نداشت» شکست می‌خورن،
     * معمولاً یعنی اپراتور کل ترافیک UDP:53 رو -- صرف‌نظر از این‌که واقعاً به کدوم IP فرستاده
     * شده -- شفاف رهگیری و با ریزالو خودش (سانسورشده) جایگزین می‌کنه؛ امتحان کردن IPهای «متفاوت»
     * روی UDP کمکی نمی‌کنه چون همه از یک نقطه رد می‌شن. DoH یک HTTPS معمولی به یک IP مشخصه که
     * رهگیری شفافش برای اپراتور خیلی سخت‌تره، و پاسخش JSON ساده است (نه پارس باینری دستی).
     */
    suspend fun resolveViaDoh(hostname: String): List<String> = withContext(Dispatchers.IO) {
        val attempts = listOf(
            Triple("https://dns.google/resolve?name=$hostname&type=A", null, "Google DoH"),
            Triple("https://cloudflare-dns.com/dns-query?name=$hostname&type=A", "application/dns-json", "Cloudflare DoH")
        )
        for ((url, accept, label) in attempts) {
            try {
                val requestBuilder = Request.Builder().url(url)
                if (accept != null) requestBuilder.addHeader("Accept", accept)
                val response = dohClient.newCall(requestBuilder.build()).execute()
                val body = response.body?.string()
                response.close()
                if (body.isNullOrEmpty()) continue

                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: continue
                val ips = mutableListOf<String>()
                for (i in 0 until answers.length()) {
                    val ans = answers.getJSONObject(i)
                    if (ans.optInt("type") == 1) { // A record
                        ips.add(ans.getString("data"))
                    }
                }
                if (ips.isNotEmpty()) {
                    Log.d(TAG, "$label: $hostname -> $ips")
                    return@withContext ips
                }
            } catch (e: Exception) {
                Log.d(TAG, "$label query for $hostname failed: ${e.message}")
            }
        }
        emptyList()
    }

    /**
     * یک query واقعی DNS (UDP, type A) به سرور و پورت مشخص می‌زند و latency + IP حل‌شده را برمی‌گرداند.
     */
    private suspend fun queryDns(dns: GameDnsList.DnsServer, port: Int, hostname: String): DnsResult? =
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = QUERY_TIMEOUT_MS.toInt()

                val query = buildDnsQuery(hostname)
                val packet = DatagramPacket(
                    query, query.size,
                    InetSocketAddress(dns.ip, port)
                )

                val startTime = System.currentTimeMillis()
                socket.send(packet)

                val responseBuffer = ByteArray(512)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)
                val latency = System.currentTimeMillis() - startTime

                val resolvedIp = parseDnsResponse(responsePacket.data, responsePacket.length)
                if (resolvedIp != null) {
                    DnsResult(dns, port, latency, resolvedIp)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }

    /**
     * یک query DNS ساده برای type A می‌سازد.
     * Transaction ID ثابت + یک flag + question section.
     */
    private fun buildDnsQuery(hostname: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()

        // Transaction ID
        baos.write(0xAB)
        baos.write(0xCD)

        // Flags: standard query, recursion desired
        baos.write(0x01)
        baos.write(0x00)

        // Questions: 1
        baos.write(0x00); baos.write(0x01)
        // Answer RRs: 0
        baos.write(0x00); baos.write(0x00)
        // Authority RRs: 0
        baos.write(0x00); baos.write(0x00)
        // Additional RRs: 0
        baos.write(0x00); baos.write(0x00)

        // Question: hostname as labels
        for (label in hostname.split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            baos.write(bytes.size)
            baos.write(bytes)
        }
        baos.write(0) // end of labels

        // Type A (1)
        baos.write(0x00); baos.write(0x01)
        // Class IN (1)
        baos.write(0x00); baos.write(0x01)

        return baos.toByteArray()
    }

    /**
     * اولین A record را از پاسخ DNS استخراج می‌کند.
     */
    private fun parseDnsResponse(data: ByteArray, length: Int): String? {
        try {
            if (length < 12) return null

            // Transaction ID (bytes 0-1) و Flags (bytes 2-3) را رد کن
            val qdcount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val ancount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (ancount == 0) return null

            var pos = 12

            // Question section را رد کن
            repeat(qdcount) {
                // labels
                while (pos < length) {
                    val len = data[pos].toInt() and 0xFF
                    pos++
                    if (len == 0) break
                    pos += len
                }
                pos += 4 // type (2) + class (2)
            }

            // Answer section � اولین A record را پیدا کن
            repeat(ancount) {
                if (pos >= length) return null
                // name (می‌تواند pointer باشد)
                if ((data[pos].toInt() and 0xC0) == 0xC0) {
                    pos += 2
                } else {
                    while (pos < length) {
                        val len = data[pos].toInt() and 0xFF
                        pos++
                        if (len == 0) break
                        pos += len
                    }
                }
                if (pos + 10 > length) return null
                val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos += 2 // type
                pos += 2 // class
                pos += 4 // TTL
                val rdlength = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos += 2

                if (type == 1 && rdlength == 4) { // A record
                    return "${data[pos].toInt() and 0xFF}.${data[pos + 1].toInt() and 0xFF}.${data[pos + 2].toInt() and 0xFF}.${data[pos + 3].toInt() and 0xFF}"
                }
                pos += rdlength
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
}
