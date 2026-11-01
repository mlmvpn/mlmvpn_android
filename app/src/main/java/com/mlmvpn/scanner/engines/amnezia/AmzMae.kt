package com.mlmvpn.scanner.engines.amnezia

import android.content.Context
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.route.Capabilities
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import org.json.JSONObject

/**
 * A WireGuard server of the user's Proton or Windscribe account (kept in the Amnezia store) as a MAE
 * exit: a fixed server abroad, so a stable exit -- what Gemini and Google Flow need -- and MAE proves
 * it per app like every other one. Xray dials it itself, with junk packets in front exactly as the
 * Amnezia engine's own tests send them (a bare handshake is dropped on some carriers).
 *
 * One WireGuard session per key and server: the same route in MAE's probe core and in its tunnel
 * would take the session from each other, so it is probed only while MAE is not the tunnel (see
 * MaeEngine.probeProviders), the way WARP is without its second identity.
 */
class WgExitRoute(
    override val id: String,
    private val conf: WgConfig,
    val country: String?,
) : RouteProvider {
    override val kind = RouteKind.FOREIGN
    override val caps = Capabilities(udp = true)
    override val cost = 0.12
    override val families = listOf(FamilyPolicy.BOTH)

    // The same length for every one, so no tag is a prefix of another.
    override fun tag(family: FamilyPolicy) = "mae-$id-d"
    private val noiseTag = "mae-$id-n"

    override fun outbounds(): List<JSONObject> {
        val noise = conf.noiseOutbound(noiseTag, obfuscate = true)
        return listOfNotNull(conf.xrayOutbound(tag(FamilyPolicy.BOTH), noise?.let { noiseTag }), noise)
    }

    companion object {
        const val PROTON = "prt-"
        const val WINDSCRIBE = "wsc-"

        fun idFor(prefix: String, serverId: String): String {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(serverId.toByteArray())
            return prefix + d.take(3).joinToString("") { "%02x".format(it) }
        }

        fun isWgExitRoute(routeId: String?) = routeId != null && (routeId.startsWith(PROTON) || routeId.startsWith(WINDSCRIBE))
        fun isProton(routeId: String?) = routeId?.startsWith(PROTON) == true
        fun isWindscribe(routeId: String?) = routeId?.startsWith(WINDSCRIBE) == true
    }
}

/**
 * Which of the user's Proton and Windscribe servers MAE takes: plain WireGuard (Xray cannot speak an
 * AmneziaWG-only one), never one that failed on this network or worked here only from inside
 * Cloudflare WARP; those that worked here first, then the untested ones, fastest first; and beside
 * them an American one, for the apps that open only from the US (Google Flow). A few of each: every
 * one is a probe and a WireGuard session more.
 */
object AmzMae {
    const val PER_PROVIDER = 2
    const val US_PER_PROVIDER = 1

    fun routes(context: Context): List<WgExitRoute> = runCatching {
        AmzEngine.init(context)
        val st = AmzEngine.store.current
        val stats = st.stats[FluxNet.current(context.applicationContext).key].orEmpty()
        fun pick(prefix: String, ids: Collection<String>): List<WgExitRoute> {
            if (ids.isEmpty()) return emptyList()
            val wanted = ids.toHashSet()
            val usable = st.servers.filter { s ->
                s.id in wanted && s.kind == AmzKind.WG && !s.needsAwgCore &&
                    stats[s.id].let { it == null || !it.tested || (it.alive && !it.viaCloudflare) }
            }
            val ranked = usable.sortedWith(compareBy({ stats[it.id]?.alive != true }, { stats[it.id]?.lastMs?.takeIf { ms -> ms >= 0 } ?: Long.MAX_VALUE }))
            val picked = ranked.take(PER_PROVIDER)
            val us = ranked.filter { it.country.equals("US", ignoreCase = true) && it !in picked }.take(US_PER_PROVIDER)
            return (picked + us).mapNotNull { s ->
                WgConfig.parseConf(s.raw)?.let { WgExitRoute(WgExitRoute.idFor(prefix, s.id), it, s.country) }
            }
        }
        pick(WgExitRoute.PROTON, com.mlmvpn.scanner.engines.proton.ProtonEngine.storedIds(context)) +
            pick(WgExitRoute.WINDSCRIBE, com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.wireGuardIds(context).values)
    }.getOrDefault(emptyList())

    /** For MAE's list: how many Proton / Windscribe WireGuard servers the user has (set up or not). */
    fun counts(context: Context): Pair<Int, Int> = runCatching {
        AmzEngine.init(context)
        val ids = AmzEngine.store.current.servers.mapTo(HashSet()) { it.id }
        val proton = com.mlmvpn.scanner.engines.proton.ProtonEngine.storedIds(context).count { it in ids }
        val windscribe = com.mlmvpn.scanner.engines.windscribe.WindscribeEngine.wireGuardIds(context).values.count { it in ids }
        proton to windscribe
    }.getOrDefault(0 to 0)
}
