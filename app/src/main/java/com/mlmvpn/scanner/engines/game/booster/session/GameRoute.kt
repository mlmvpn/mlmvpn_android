package com.mlmvpn.scanner.engines.game.booster.session

import com.mlmvpn.scanner.engines.game.booster.model.RouteKind

/** The service identity and data path for an applied game route. */
object GameRoute {
    enum class ProbePath { PHYSICAL, SOCKS, VPN }

    fun nodeId(route: RouteKind): String? = when (route) {
        RouteKind.DIRECT -> null
        RouteKind.DIRECT_DNS -> "game_dns"
        RouteKind.WARP_WG, RouteKind.WARP_MASQUE_H2, RouteKind.WARP_MASQUE_H3 -> "game_warp"
        RouteKind.AMNEZIA -> "amnezia"
        RouteKind.FENIX -> "fenix"
        RouteKind.ACCESS_HELPER -> "game_access"
    }

    fun isUp(route: RouteKind, running: Boolean, nodeId: String?): Boolean =
        route == RouteKind.DIRECT || running && nodeId == nodeId(route)

    fun probePath(route: RouteKind, nativeAmnezia: Boolean = false): ProbePath = when (route) {
        RouteKind.WARP_WG, RouteKind.WARP_MASQUE_H2, RouteKind.WARP_MASQUE_H3 -> ProbePath.SOCKS
        RouteKind.AMNEZIA -> if (nativeAmnezia) ProbePath.VPN else ProbePath.SOCKS
        RouteKind.FENIX -> ProbePath.SOCKS
        else -> ProbePath.PHYSICAL
    }
}
