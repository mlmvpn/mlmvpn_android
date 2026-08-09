package com.mlmvpn.scanner.engines.sanction

import android.content.Context
import androidx.preference.PreferenceManager
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocketFactory

/**
 * "DNS ضد تحریم شخصی" — a domain-based split tunnel that sends ONLY sanctioned domains
 * through the user's own Cloudflare worker (a VLESS-over-workers.dev relay deployed in the
 * Cloud section), so those requests egress from a clean Cloudflare IP and the origin no
 * longer sees an Iranian IP. Everything else stays on the direct connection.
 *
 * Nothing here needs the MITM CA: the client's TLS to the real origin is tunnelled intact
 * through the worker (end-to-end), so it is a real sanction bypass, not a DNS trick.
 */
object AntiSanctionManager {
    private const val PREFS_USER_DOMAINS = "antisanction_user_domains"
    private const val PREFS_REMOVED = "antisanction_removed_defaults"
    private const val PREFS_ACCOUNT_ID = "antisanction_account_id"
    const val NODE_ID = "ANTISANCTION"

    // --- Domain list (bundled defaults + user edits) ------------------------

    private fun assetDomains(context: Context): List<String> {
        return try {
            val raw = context.assets.open("sanction_domains.json").bufferedReader().use { it.readText() }
            val arr = org.json.JSONObject(raw).optJSONArray("domains") ?: JSONArray()
            (0 until arr.length()).map { arr.getString(it).trim().lowercase() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readSet(context: Context, key: String): MutableSet<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = prefs.getString(key, "[]") ?: "[]"
        val out = linkedSetOf<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) out.add(arr.getString(i).trim().lowercase())
        } catch (_: Exception) {}
        return out
    }

    private fun writeSet(context: Context, key: String, set: Set<String>) {
        val arr = JSONArray()
        set.filter { it.isNotBlank() }.forEach { arr.put(it) }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(key, arr.toString()).apply()
    }

    /** Effective sanctioned-domain list: (defaults − removed) ∪ user-added, sorted. */
    fun getDomains(context: Context): List<String> {
        val removed = readSet(context, PREFS_REMOVED)
        val defaults = assetDomains(context).filter { it !in removed }
        val user = readSet(context, PREFS_USER_DOMAINS)
        return (defaults + user).distinct().sorted()
    }

    fun addDomain(context: Context, domainRaw: String) {
        val d = normalize(domainRaw) ?: return
        val user = readSet(context, PREFS_USER_DOMAINS)
        user.add(d)
        writeSet(context, PREFS_USER_DOMAINS, user)
        // If it was a previously-removed default, un-remove it.
        val removed = readSet(context, PREFS_REMOVED)
        if (removed.remove(d)) writeSet(context, PREFS_REMOVED, removed)
    }

    fun removeDomain(context: Context, domain: String) {
        val d = domain.trim().lowercase()
        val user = readSet(context, PREFS_USER_DOMAINS)
        if (user.remove(d)) writeSet(context, PREFS_USER_DOMAINS, user)
        // If it's a bundled default, remember the removal so it stays gone.
        if (assetDomains(context).contains(d)) {
            val removed = readSet(context, PREFS_REMOVED)
            removed.add(d)
            writeSet(context, PREFS_REMOVED, removed)
        }
    }

    /** Strip scheme/path and keep just the host. Returns null if empty/invalid. */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/')
        s = s.substringBefore(':')
        return s.takeIf { it.contains('.') && !it.contains(' ') }
    }

    /** Xray routing entries: "domain:x" matches x and all its subdomains. */
    fun routingDomains(context: Context): List<String> = getDomains(context).map { "domain:$it" }

    // --- Cloudflare worker (exit) selection --------------------------------

    /** Cloud accounts that already have a deployed EDG (VLESS) worker. */
    fun accountsWithWorker(context: Context) =
        CloudManager(context).accountsFlow.value.filter { !it.edgWorkerUrl.isNullOrEmpty() && !it.edgUuid.isNullOrEmpty() }

    fun selectedAccountId(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREFS_ACCOUNT_ID, null)

    fun setSelectedAccountId(context: Context, id: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREFS_ACCOUNT_ID, id).apply()
    }

    /**
     * The VLESS URI to use as the proxy exit. Prefers a clean-IP Cloudflare variant (EDG-CF1)
     * over the workers.dev hostname, because workers.dev DNS is blocked on many Iran networks.
     * Returns null when no worker-backed account is configured.
     */
    suspend fun getExitVless(context: Context): String? = withContext(Dispatchers.IO) {
        val cloud = CloudManager(context)
        val accounts = accountsWithWorker(context)
        if (accounts.isEmpty()) return@withContext null
        val chosen = selectedAccountId(context)?.let { id -> accounts.firstOrNull { it.accountId == id } }
            ?: accounts.first()
        val (ok, configs) = cloud.fetchEdgConfigs(chosen)
        if (!ok || configs.isEmpty()) return@withContext null
        // configs = [EDG-Auto(workers.dev), EDG-CF1(clean ip), EDG-CF2(clean ip)]
        configs.getOrNull(1) ?: configs.first()
    }

    /**
     * Build the full Xray split-tunnel JSON: sanctioned domains → the Cloudflare worker,
     * everything else → direct. Returns null if no exit worker is configured.
     */
    suspend fun buildConfig(context: Context, localPort: Int, backendDns: String = "1.1.1.1"): String? {
        val vless = getExitVless(context) ?: return null
        val config = VpnConfig.parseUri(vless) ?: return null
        val domains = routingDomains(context)
        if (domains.isEmpty()) return null
        // Temporary diagnostic: a live device trace showed some sanctioned domains (e.g.
        // microsoft.com) correctly routing to proxy while another (flaticon.com) did not, with
        // no visible structural difference in how they were dispatched -- logging the exact list
        // actually being used settles whether the domain is even present as expected, instead of
        // guessing further from routing-decision logs alone. Remove once root-caused.
        android.util.Log.d("AntiSanction", "buildConfig: sanctionedDomains (${domains.size}) = $domains")
        return XrayJsonGenerator.generateAntiSanctionConfig(
            config = config,
            localPort = localPort,
            sanctionedDomains = domains,
            backendDns = backendDns
        )
    }

    // --- Sanction vs filter detection (port of sanction-manager.js) ---------

    enum class State { OPEN, FILTERED, SANCTIONED, UNKNOWN }
    data class Report(val domain: String, val state: State, val note: String)

    suspend fun classifyDomain(domainRaw: String): Report = withContext(Dispatchers.IO) {
        val domain = domainRaw.trim().lowercase()
        val tcp = tcpConnect(domain, 443, 6000)
        val https = httpsHead(domain, domain, 6000)

        // Direct open: TCP ok + HTTP < 400
        if (tcp.first && https.first != null && https.first!! < 400) {
            return@withContext Report(domain, State.OPEN, "مستقیم باز است — نه تحریم نه فیلتر")
        }
        // Network-layer block (reset/timeout) → filtering, not sanction
        if (!tcp.first && (tcp.second == "reset" || tcp.second == "timeout")) {
            return@withContext Report(domain, State.FILTERED, "فیلتر شبکه‌ای (کار VPN، نه این بخش)")
        }
        // Geo-block: TLS ok but HTTP 403/451
        if (https.first == 403 || https.first == 451) {
            return@withContext Report(domain, State.SANCTIONED, "تحریم — با روشن کردن این بخش از کلادفلر باز می‌شود ✅")
        }
        // TCP ok but TLS reset → SNI filtering
        if (tcp.first && https.first == null && https.second == "reset") {
            return@withContext Report(domain, State.FILTERED, "فیلتر مبتنی بر SNI (کار VPN)")
        }
        Report(domain, State.UNKNOWN, "نامشخص — می‌توانید دستی به لیست اضافه کنید")
    }

    private fun tcpConnect(host: String, port: Int, timeoutMs: Int): Pair<Boolean, String> {
        return try {
            java.net.Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true to "ok"
            }
        } catch (e: java.net.SocketTimeoutException) {
            false to "timeout"
        } catch (e: java.net.ConnectException) {
            false to "reset"
        } catch (e: Exception) {
            false to (e.message ?: "error")
        }
    }

    /** Returns (httpStatus?, reason). Uses SNI = servername; connects to connectHost. */
    private fun httpsHead(connectHost: String, servername: String, timeoutMs: Int): Pair<Int?, String> {
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val plain = java.net.Socket()
            plain.connect(InetSocketAddress(connectHost, 443), timeoutMs)
            plain.soTimeout = timeoutMs
            val ssl = factory.createSocket(plain, servername, 443, true) as javax.net.ssl.SSLSocket
            // Present the real SNI.
            try {
                val p = ssl.sslParameters
                p.serverNames = listOf(javax.net.ssl.SNIHostName(servername))
                ssl.sslParameters = p
            } catch (_: Exception) {}
            ssl.startHandshake()
            val req = "HEAD / HTTP/1.1\r\nHost: $servername\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"
            ssl.outputStream.write(req.toByteArray())
            ssl.outputStream.flush()
            val line = ssl.inputStream.bufferedReader().readLine() ?: ""
            ssl.close()
            val m = Regex("^HTTP/[\\d.]+ (\\d{3})").find(line)
            if (m != null) m.groupValues[1].toInt() to "ok" else null to "closed"
        } catch (e: javax.net.ssl.SSLException) {
            null to "reset"
        } catch (e: java.net.SocketTimeoutException) {
            null to "timeout"
        } catch (e: Exception) {
            null to (e.message ?: "error")
        }
    }
}
