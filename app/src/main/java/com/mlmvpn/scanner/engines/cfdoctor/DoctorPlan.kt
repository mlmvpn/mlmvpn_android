package com.mlmvpn.scanner.engines.cfdoctor

import org.json.JSONObject
import java.net.InetAddress

object CfBogon {
    fun isBogon(ip: String): Boolean {
        if (!ip.matches(Regex("[0-9a-fA-F:.]+"))) return true
        val b = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return true
        val a = b[0].toInt() and 255
        val c = b[1].toInt() and 255
        if (b.size == 16) return a and 0xe0 != 0x20 || b.copyOfRange(0,8).contentEquals(byteArrayOf(0x20,1,0x41,0x88.toByte(),0,2,6,0))
        return a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && c in 64..127) ||
            (a == 169 && c == 254) || (a == 172 && c in 16..31) || (a == 192 && c in listOf(0,168)) ||
            (a == 198 && c in 18..19) || (a == 198 && c == 51) || (a == 203 && c == 0)
    }
    private val ranges = listOf("173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22",
        "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22")
    fun isCloudflare(ip: String): Boolean {
        if (isBogon(ip)) return false
        if (ip.contains(':')) return ip.lowercase().startsWith("2606:4700:")
        fun number(s: String) = s.split('.').fold(0L) { a, v -> (a shl 8) or v.toLong() }
        return runCatching { ranges.any { val (base,bits) = it.split('/'); val mask = (0xffffffffL shl (32-bits.toInt())) and 0xffffffffL; number(ip) and mask == number(base) and mask } }.getOrDefault(false)
    }
}

data class DoctorPlan(
    val version: String = "1", val ips: List<String> = DEFAULT_IPS,
    val hosts: List<String> = HOSTS, val ports: List<Int> = listOf(443,8443,2053,2083,2087,2096),
    val maxAttempts: Int = 900, val maxMs: Long = 480000, val maxBytes: Long = 3*1024*1024,
    val timeoutMs: Int = 4000, val repeats: Int = 2,
    val core: Set<String> = CORE, val mapping: Map<String, Set<String>> = MAPPING,
    val tiersMs: List<Long> = listOf(30000,240000,240000),
    val fingerprints: List<String> = listOf("chrome","firefox","safari","ios","android","edge","360","qq","random","randomized",""),
    val recipes: Map<String,SplitRecipe> = (1..15).associate { "F$it" to ClientHelloSplit.recipe("F$it") },
    val slowRttMs: Long = 1500,
    val fragmentDelays: List<Int> = listOf(0,10,50,250,1000),
) {
    companion object {
        /** Anycast ranges that serve sites and Workers, two addresses per /16. */
        val DEFAULT_IPS = listOf("104.16.113.28","104.16.179.102","104.17.120.41","104.17.190.119","104.18.127.54","104.18.201.136","104.19.134.67","104.19.212.153","104.20.141.80","104.20.223.170","104.21.148.93","104.21.234.187","104.22.155.106","104.22.245.204","104.23.162.119","104.23.6.221","104.24.169.132","104.24.17.38","104.25.176.145","104.25.28.55","104.26.183.158","104.26.39.72","104.27.190.171","104.27.50.89","172.64.72.232","172.64.82.98","172.65.77.235","172.65.91.105","172.66.82.238","172.66.100.112","172.67.87.41","172.67.109.119","172.68.92.44","172.68.118.126","172.69.97.47","172.69.127.133","172.70.102.50","172.70.136.140","172.71.107.53","172.71.145.147","188.114.96.98","188.114.96.140","188.114.97.101","188.114.97.145","188.114.98.104","188.114.98.150","188.114.99.107","188.114.99.155")
        val HOSTS = listOf("api.cloudflare.com","www.cloudflare.com","workers.dev","cloudflare-ech.com","dash.cloudflare.com","speed.cloudflare.com","cdnjs.cloudflare.com","challenges.cloudflare.com","one.one.one.one","cloudflare-dns.com","api.cloudflareclient.com","engage.cloudflareclient.com","crypto.cloudflare.com")
        val RESOLVERS = setOf("8.8.8.8","8.8.4.4","1.1.1.1","1.0.0.1","94.140.14.14","9.9.9.9")
        val WITNESSES = setOf("www.google.com","connectivitycheck.gstatic.com","www.msftconnecttest.com","www.wikipedia.org","www.aparat.com","www.digikala.com")
        val CORE = setOf("T6","D1","D2","D5","D7","D10","T1","T2","S1","S2","S3","F1","F3","F6","F8","X1","X2","Q1","R1")
        val ALL = ((1..11).map { "D$it" } + (1..6).map { "T$it" } + (1..6).map { "S$it" } + (1..15).map { "F$it" } + (1..4).map { "H$it" } + (1..4).map { "X$it" } + listOf("Q1","Q2","R1","R2","W1","W2") + (1..10).map { "C$it" }).toSet()
        val MAPPING = mapOf(
            "DNS_POISONED" to setOf("D3","D4","D6","D8","D9","D11"),
            "IP_BLOCK_ALL" to setOf("T1","T2","T3","T4","T5","C5"),
            "IP_BLOCK_PARTIAL" to setOf("T1","T2","T3","T4","T5","C5"),
            "SNI_BLOCK" to setOf("S1","S4","S5","S6","F2","F4","F5","F7","F9","F12","F13","F14","F15","H1","H2","H3","H4","C9"),
            "TLS_BLOCK_ALL" to setOf("S1","S4","S5","S6","F2","F4","F5","F7","F9","F12","F13","F14","F15","H1","H2","H3","H4","C9"),
            "SILENT_AFTER_TLS" to setOf("X3","X4","W1","F10","F11","H4","Q2"), "UDP_SUSPECT" to setOf("Q2"))
        fun parse(text: String): DoctorPlan {
            require(text.toByteArray().size <= 65536)
            val j = JSONObject(text)
            fun strings(key: String, fallback: List<String>): List<String> = j.optJSONArray(key)?.let { a -> require(a.length() <= 128); (0 until a.length()).map { a.getString(it) } } ?: fallback
            val ips = strings("ips", DEFAULT_IPS); require(ips.isNotEmpty() && ips.all { CfBogon.isCloudflare(it) })
            val hosts = strings("hosts", HOSTS); require(hosts.all { it in HOSTS })
            val ports = j.optJSONArray("ports")?.let { a -> require(a.length() <= 6); (0 until a.length()).map { a.getInt(it) } } ?: listOf(443,8443,2053,2083,2087,2096)
            require(ports.all { it in setOf(443,8443,2053,2083,2087,2096) })
            val core = strings("core", CORE.toList()).toSet(); require(core.containsAll(CORE) && core.all { it in ALL })
            val mapping = MAPPING.toMutableMap()
            j.optJSONObject("mapping")?.let { m -> m.keys().forEach { flag -> require(flag in MAPPING); val a=m.getJSONArray(flag); require(a.length()<=ALL.size); mapping[flag]=(0 until a.length()).map { a.getString(it).also { id -> require(id in ALL) } }.toSet() } }
            val version = j.optString("version", "1"); require(version.matches(Regex("[0-9A-Za-z._-]{1,32}")))
            val defaults=DoctorPlan()
            val tiers=j.optJSONArray("tiersMs")?.let { a -> require(a.length()==3); listOf(a.getLong(0).coerceIn(5000,30000),a.getLong(1).coerceIn(30000,240000),a.getLong(2).coerceIn(30000,240000)) } ?: defaults.tiersMs
            val fingerprints=strings("fingerprints",defaults.fingerprints); require(fingerprints.all { it in defaults.fingerprints })
            val recipes=defaults.recipes.toMutableMap()
            j.optJSONObject("fragments")?.let { f -> f.keys().forEach { id ->
                require(id in recipes); val o=f.getJSONObject(id)
                recipes[id]=SplitRecipe(o.optInt("at",0).coerceIn(0,4096),o.optInt("chunk",0).coerceIn(0,2048),o.optInt("delayMs",0).coerceIn(0,1000),o.optBoolean("record"),o.optBoolean("sni"))
            } }
            val delays=j.optJSONArray("fragmentDelays")?.let { a -> require(a.length() in 1..5); (0 until a.length()).map { a.getInt(it).coerceIn(0,1000) } } ?: defaults.fragmentDelays
            return DoctorPlan(version, ips, hosts, ports, j.optInt("maxAttempts",900).coerceIn(32,900),
                j.optLong("maxMs",480000).coerceIn(30000,480000), j.optLong("maxBytes",3*1024*1024).coerceIn(65536,3*1024*1024),
                j.optInt("timeoutMs",4000).coerceIn(1000,10000), j.optInt("repeats",2).coerceIn(2,3), core, mapping,tiers,fingerprints,recipes,j.optLong("slowRttMs",1500).coerceIn(500,5000),delays)
        }
    }
}

object TriageClassifier {
    fun classify(rows: List<Evidence>): Set<String> = buildSet {
        val domestic = rows.filter { it.id == "T6" && it.route == "domestic" }
        val foreign = rows.filter { it.id == "T6" && it.route == "foreign" }
        val reached = rows.any { it.ok && it.id in setOf("T1","X1","X2") }
        val controlsComplete=domestic.size>=2 && foreign.size>=3
        if (controlsComplete && domestic.none { it.ok } && foreign.none { it.ok } && !reached) add("NO_CONNECTIVITY")
        if (controlsComplete && domestic.any { it.ok } && foreign.none { it.ok } && !reached) add("NO_INTERNATIONAL")
        if (rows.any { it.code == "dns_bogon" }) add("DNS_POISONED")
        val tcp = rows.filter { it.id == "T1" }
        if (tcp.isNotEmpty() && tcp.none { it.ok }) add("IP_BLOCK_ALL")
        else if (tcp.any { !it.ok }) add("IP_BLOCK_PARTIAL")
        val tls = rows.filter { it.id == "S1" }
        if (tls.isNotEmpty() && tls.none { it.numbers["tls"] == 1L } && tcp.any { it.ok }) add("TLS_BLOCK_ALL")
        if (tls.any { it.numbers["tls"] == 1L } && tls.any { it.code.startsWith("tls_") }) add("SNI_BLOCK")
        if (rows.any { it.numbers["tls"] == 1L && it.code in setOf("http_timeout","http_eof","stall") }) add("SILENT_AFTER_TLS")
        if (rows.any { it.id == "Q1" && !it.ok }) add("UDP_SUSPECT")
        if (isEmpty() && rows.any { it.id == "X1" && it.ok }) add("OK")
    }
}
object TierPlanner {
    fun methods(flags: Set<String>, full: Boolean, plan: DoctorPlan): Set<String> =
        if (full) DoctorPlan.ALL else plan.core + flags.flatMap { plan.mapping[it].orEmpty() }
}
