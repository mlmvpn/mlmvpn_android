package com.mlmvpn.scanner.widget

import com.mlmvpn.scanner.ui.home.HomeApp
import com.mlmvpn.scanner.ui.home.HomeDestinations

/**
 * The tiles a home-screen widget can stand for: every way the app has of connecting the phone, and
 * nothing that only manages, measures or explains (Settings, the scanner, Scout, Cloud, the help).
 *
 * The icon, the name and the colour are deliberately NOT copied here. They are read from the tile
 * itself ([HomeDestinations]), so a widget always wears exactly what the user sees on the in-app
 * board, and a tile that gets new artwork gets it on every widget in the same release.
 *
 * The order is the board's factory order, so every list of methods (the studio's picker, the page
 * that adds a method's widget) reads the way the board does.
 */
object WidgetMethods {

    val IDS: List<String> = listOf(
        "masque", "wireguard", "warp_on_warp", "warp", "psiphon", "tor", "geph", "lantern", "urnetwork",
        "quick", "mae", "flux", "fenix", "amnezia", "proton", "windscribe", "outline", "npv", "github",
        "vpngate", "openvpn", "nodes", "iran", "fronting", "antisanction", "emergency_2", "emergency_3",
    )

    /**
     * The tile behind [id], or null for an id this build does not have -- a release leaves out the
     * destinations still being built ([HomeDestinations.IN_PROGRESS]), and their widgets with them.
     */
    fun app(id: String?): HomeApp? = id?.let { key ->
        HomeDestinations.gridById(key) ?: HomeDestinations.DOCK.firstOrNull { it.id == key }
    }

    /** Every method this build has, in the board's order. */
    fun all(): List<HomeApp> = IDS.mapNotNull { app(it) }

    fun isMethod(id: String?): Boolean = id != null && id in IDS && app(id) != null
}
