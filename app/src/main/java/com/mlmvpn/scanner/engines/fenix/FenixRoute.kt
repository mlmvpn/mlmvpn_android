package com.mlmvpn.scanner.engines.fenix

import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.route.Capabilities
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import org.json.JSONObject

/**
 * FENIX as one of MAE's foreign exits: a real exit abroad (Mozilla's servers are in the country
 * they are listed in, and Cloudflare's trace says so through them), quick to open connections and
 * independent of the user's Cloudflare account. MAE proves it per app like every other exit -- an
 * app that refuses it simply never gets it.
 *
 * TCP only (the proxy has no UDP), so QUIC is refused for the apps on it, which then use TCP.
 * Metered against the account's monthly allowance, which the scorer weighs like the Worker's quota.
 */
class FenixRoute internal constructor(
    private val server: FenixServers.Server,
    private val address: String,
    private val token: String,
    /** [ID] for the country the user chose; [US_ID] for the second, American exit (Gemini, Google Flow). */
    override val id: String = ID,
    private val tag: String = TAG,
) : RouteProvider {
    override val kind = RouteKind.FOREIGN
    override val caps = Capabilities(udp = false, quotaLimited = true)
    override val cost = 0.2
    override val families = listOf(FamilyPolicy.BOTH)

    override fun tag(family: FamilyPolicy) = tag

    override fun outbounds(): List<JSONObject> = listOf(FenixConfig.outbound(tag, server, address, token))

    /** For diagnostics only. */
    val label: String get() = "FENIX ${server.country} ${server.host}"

    companion object {
        const val ID = "fenix"
        /** No other MAE tag starts with this, and it starts with no other tag. */
        const val TAG = "mae-fnx"

        /**
         * FENIX's American server beside the chosen one, when the choice is another country: what an
         * app that only opens from the US (Google Flow) is proven on. Its tag shares no prefix with [TAG].
         */
        const val US_ID = "fenix-us"
        const val US_TAG = "mae-fnu"

        fun isFenixRoute(routeId: String?) = routeId == ID || routeId == US_ID
    }
}
