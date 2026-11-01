package com.mlmvpn.scanner.data

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.utils.VpnConfig

/**
 * Self-healing for Cloudflare-fronted configs: before a connect (or a delay test) a config whose
 * edge is Cloudflare IPv4 is moved to a Cloudflare IPv6 address when IPv4 carries no traffic on this
 * network -- the address changes, the Worker's name stays as SNI and host, so it is the same config
 * reaching the same Worker through the family the filter still lets through.
 *
 * Decided by measurement, never assumed ([CfFamily]): with a fresh verdict it is instant; without
 * one, the config is tried as it is (5 s), and only when that fails is the IPv6 twin tried, and the
 * outcome recorded for everything else on this network. A config that works as it is, or that is
 * not Cloudflare-fronted, is never touched.
 */
object CfEdgeHeal {

    private const val TAG = "CfEdgeHeal"
    private val CF_V4 = CloudflareScanner.DEFAULT_RANGES + listOf("172.64.0.0/13")

    fun v4ToLong(ip: String): Long? {
        val p = ip.split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) { val n = x.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n.toLong() }
        return v
    }

    fun isCfV4(ip: String): Boolean {
        val v = v4ToLong(ip) ?: return false
        return CF_V4.any { r ->
            val (base, bits) = r.split('/')
            val b = v4ToLong(base) ?: return@any false
            val mask = if (bits.toInt() == 0) 0L else (0xFFFFFFFFL shl (32 - bits.toInt())) and 0xFFFFFFFFL
            (v and mask) == (b and mask)
        }
    }

    private fun cfName(n: String) = n.lowercase().let { it.endsWith(".workers.dev") || it.endsWith(".pages.dev") }

    /** Cloudflare-fronted over TLS, on an address that is not already IPv6. */
    fun isCfFronted(c: VpnConfig): Boolean {
        if (!c.tls.equals("tls", true) || CfFamily.isV6(c.address)) return false
        val names = listOf(c.sni, c.wsHost, c.xhttpHost, c.address)
        return names.any { cfName(it) } || isCfV4(c.address)
    }

    /** A Cloudflare IPv6 edge address that carried traffic here: the scanner's newest, else a fresh pick. */
    fun goodV6(context: Context): String {
        val gm = GroupManager(context)
        runCatching { gm.loadIpArchives() }
        return gm.ipArchives.find { it.id == GroupManager.UNIFIED_ARCHIVE }?.ips.orEmpty()
            .map { it.trim().removePrefix("[").removeSuffix("]") }
            .lastOrNull { CfFamily.isV6(it) } ?: CfFamily.sampleV6(1).first()
    }

    /** [c] on [ip], the original name pinned into SNI and host first (as the scanner's real test does). */
    fun onto(c: VpnConfig, ip: String): VpnConfig = c.copy().apply {
        val original = address
        if (sni.isBlank()) sni = wsHost.ifBlank { original }
        if (wsHost.isBlank()) wsHost = original
        if (xhttpHost.isBlank()) xhttpHost = original
        address = ip
    }

    private suspend fun works(context: Context, c: VpnConfig, timeoutMs: Long): Boolean = runCatching {
        CombineEngine.ensureCoreEnv(context)
        CombineEngine.measureDelay(com.mlmvpn.scanner.utils.XrayJsonGenerator.generateSpeedtestConfig(c), timeoutMs = timeoutMs) > 0
    }.getOrDefault(false)

    /**
     * The config to use: [c] itself, or its IPv6 twin when IPv4 is measured dead here. [probe] false
     * uses only a fresh verdict (for bulk paths that must not add seconds per config).
     */
    suspend fun heal(context: Context, c: VpnConfig, probe: Boolean = true): VpnConfig {
        if (!isCfFronted(c)) return c
        // A clean-IP config that carries the handshake its scan proved (a combine wrote it in): its
        // address is the config, and measuring another edge or the IPv6 twin would mislabel the row.
        if (com.mlmvpn.scanner.engines.cfnet.CfNetManager.current()?.dialledAsWritten(c) == true) return c
        // The address itself first (CfEdge). Reachability differs by address range and by region:
        // the Doctor reports showed one edge dead and its neighbour alive on the same line, so
        // "IPv4 works here" says nothing about THIS config's address. A config pointed at a dead
        // edge, or at a name the resolver answers with a dead or bogus edge, goes to one that is
        // proven on this network -- same Worker, its name kept as SNI and host.
        edgeFor(context, c, probe)?.let { return it }
        val v = CfFamily.current(context)
        if (v?.v4 == true) return c
        // A name that is not a literal IPv4 is left to the phone's resolver unless IPv4 is dead,
        // and then moved like any other.
        if (v?.v4 == false && v.v6 == true) return onto(c, goodV6(context)).also { Log.i(TAG, "IPv4 dead here: ${c.address} -> ${it.address}") }
        if (!probe) return c
        // Only a literal IPv4 address says anything about IPv4: a name is resolved by the phone,
        // which prefers IPv6 when it has it (measured: a workers.dev name worked while every
        // literal IPv4 of the same account carried nothing), so its success proves nothing.
        val literalV4 = v4ToLong(c.address) != null
        if (works(context, c, 5_000)) { if (literalV4) CfFamily.record(context, true, v?.v6); return c }
        if (!CfFamily.hasIpv6Route()) { if (literalV4) CfFamily.record(context, false, false); return c }
        val twin = onto(c, goodV6(context))
        return if (works(context, twin, 7_000)) {
            CfFamily.record(context, if (literalV4) false else v?.v4, true)
            Log.i(TAG, "healed: ${c.address} carried nothing, ${twin.address} works")
            twin
        } else {
            if (literalV4) CfFamily.record(context, false, v?.v6)
            c
        }
    }

    /**
     * [c] moved onto a working IPv4 edge when its own address is known (or, with [probe], checked)
     * not to answer here; null when it is fine as it is, or nothing better is known.
     */
    private suspend fun edgeFor(context: Context, c: VpnConfig, probe: Boolean): VpnConfig? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val name = c.sni.ifBlank { c.wsHost.ifBlank { c.xhttpHost.ifBlank { c.address } } }
                .takeIf { v4ToLong(it) == null && !CfFamily.isV6(it) } ?: CfEdge.DEFAULT_HOST
            val addr = c.address
            val healthy: Boolean? = when {
                v4ToLong(addr) != null -> when {
                    CfEdge.isGood(context, addr) -> true
                    CfEdge.isBad(context, addr) -> false
                    probe -> CfEdge.check(context, addr, name)
                    else -> null
                }
                // A name: what the phone's resolver makes of it. Bogus or dead answers are the
                // DNS half of the regional failures.
                probe -> {
                    val ips = runCatching { java.net.InetAddress.getAllByName(addr).mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
                        .filter { v4ToLong(it) != null }
                    when {
                        ips.isEmpty() -> null // IPv6-only or unresolved: the family logic below decides
                        ips.all { com.mlmvpn.scanner.engines.cfdoctor.CfBogon.isBogon(it) } -> false
                        else -> ips.take(2).any { CfEdge.check(context, it, name) }
                    }
                }
                else -> null
            }
            if (healthy != false) return@withContext null
            val edge = (if (probe) CfEdge.find(context, name, 2) else CfEdge.good(context, 2)).firstOrNull { it != addr }
                ?: return@withContext null
            Log.i(TAG, "edge: $addr does not answer here -> $edge")
            onto(c, edge)
        }

    /** The same, for a link: returns a rewritten link only when it changed. */
    suspend fun healUri(context: Context, uri: String, probe: Boolean = true): String {
        val c = VpnConfig.parseUri(uri) ?: return uri
        val h = heal(context, c, probe)
        return if (h === c) uri else CombineEngine.rewrite(uri, h.address, android.net.Uri.decode(uri.substringAfter('#', "")).ifBlank { h.address }, null)
    }
}
