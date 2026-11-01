package com.mlmvpn.scanner.engines.outline

import android.content.Context
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.route.Capabilities
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import org.json.JSONObject

/**
 * One of the user's Outline servers as a MAE exit: a fixed server abroad, so a stable exit -- what
 * Gemini and Google Flow need -- and MAE proves it per app like every other one (the user found Outline
 * servers that opened them, 2026-10-18). [outbound] is the server's Xray Shadowsocks outbound.
 */
class OutlineRoute(override val id: String, private val outbound: JSONObject, val country: String?) : RouteProvider {
    override val kind = RouteKind.FOREIGN
    override val caps = Capabilities(udp = false)
    override val cost = 0.15
    override val families = listOf(FamilyPolicy.BOTH)

    // The same length for every Outline route, so no tag is a prefix of another.
    override fun tag(family: FamilyPolicy) = "mae-$id-d"

    override fun outbounds(): List<JSONObject> = listOf(JSONObject(outbound.toString()).put("tag", tag(FamilyPolicy.BOTH)))

    companion object {
        const val PREFIX = "olt-"

        fun idFor(serverId: String): String {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(serverId.toByteArray())
            return PREFIX + d.take(3).joinToString("") { "%02x".format(it) }
        }

        fun isOutlineRoute(routeId: String?) = routeId?.startsWith(PREFIX) == true
    }
}

/**
 * Which Outline servers MAE takes: the ones that worked on this network straight -- not only from
 * inside WARP, which MAE does not chain -- the user's own keys and favourites first, then the fastest;
 * and beside them the fastest American ones, for the apps that open only from the US (Google Flow).
 * Servers with an Outline prefix are left out: Xray cannot send one.
 */
object OutlineMae {
    /** Each one is a probe and an outbound more in MAE's set. */
    const val MAX = 3
    const val MAX_US = 2

    fun servers(context: Context): List<OutlineServer> = runCatching {
        OutlineEngine.init(context)
        val st = OutlineEngine.store.state.value
        val stats = st.stats[FluxNet.current(context.applicationContext).key].orEmpty()
        val working = st.servers.filter { s ->
            !s.prefix && stats[s.id]?.let { it.alive && !it.viaCf } == true
        }
        val ranked = working.sortedWith(
            compareBy<OutlineServer>({ !(it.user || it.id in st.favorites) }, { stats[it.id]?.lastMs ?: Long.MAX_VALUE }),
        )
        val picked = ranked.take(MAX)
        picked + ranked.filter { it.country.equals("US", ignoreCase = true) && it !in picked }.take(MAX_US)
    }.getOrDefault(emptyList())
}
