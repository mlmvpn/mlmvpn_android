package com.mlmvpn.scanner.backup

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Every SharedPreferences file the code opens has a backup decision. A new one fails this test until
 * it is written down here -- and, when it holds something a user would want on a new phone, added to
 * an item in MainItems / AccountItems / MethodItems (or a feature's own BackupContributor).
 */
class BackupCoverageTest {

    private val decided = mapOf(
        // ── in an item ──
        "app_locale_prefs" to "MAIN main_language",
        "app_settings" to "MAIN network/sharing/general (update time, last engine, LAN timer left out)",
        "@default" to "MAIN look/network/sharing/general, ACCOUNTS gst, METHOD v2ray/game/emergency/antisanction",
        "vpn_routing_prefs" to "MAIN main_network",
        "smart_iran" to "MAIN main_smart_iran",
        "cfnet_ui" to "MAIN main_general",
        "arena_prefs" to "nothing: the sound switch is the arena's, ip_<net> per network",
        "cloud_accounts_prefs" to "ACCOUNTS acc_cloudflare",
        "nova_panel" to "ACCOUNTS acc_deploys",
        "netra_panel" to "ACCOUNTS acc_deploys",
        "spider_panel" to "ACCOUNTS acc_deploys",
        "gozargah_panel" to "ACCOUNTS acc_deploys",
        "edge_gateway" to "ACCOUNTS acc_deploys",
        "subgen_prefs" to "ACCOUNTS acc_deploys",
        "mae" to "ACCOUNTS acc_deploys",
        "warp_id_relay" to "ACCOUNTS acc_deploys (relay) and acc_warp (identity times)",
        "cfwarp" to "ACCOUNTS acc_warp (id_*), METHOD m_warp",
        "warp_prefs" to "ACCOUNTS acc_warp",
        "wg_trial" to "ACCOUNTS acc_warp",
        "amnezia_carrier" to "ACCOUNTS acc_warp (own); endpoints per network left out",
        "secure_settings" to "ACCOUNTS acc_geph, acc_zero_trust",
        "settings" to "METHOD m_tunnel/m_psiphon/m_tor/m_lantern/m_github/m_vpngate, ACCOUNTS zero trust; state keys left out",
        "github_tunnel" to "ACCOUNTS acc_github, METHOD m_github; sessions and caches left out",
        "fenix" to "ACCOUNTS acc_fenix, METHOD m_fenix; server list and pings left out",
        "windscribe_amz" to "ACCOUNTS acc_windscribe",
        "proton_vpn" to "ACCOUNTS acc_proton",
        "vpn_nodes_prefs" to "ACCOUNTS acc_v2ray (the old copy of nodes.json)",
        "subscription_manager_prefs" to "ACCOUNTS acc_v2ray",
        "group_manager_prefs" to "ACCOUNTS acc_v2ray (cloud groups), acc_scanner_groups",
        "flux_sources" to "ACCOUNTS acc_flux (user_subs); hashes left out",
        "mitm_cert_prefs" to "ACCOUNTS acc_certs",
        "nat64_exit" to "METHOD m_exit (selection, custom); fetched lists left out",
        "geph" to "METHOD m_geph",
        "amnezia_ui" to "METHOD m_amnezia",
        "outline_ui" to "METHOD m_outline",
        "tgproxy_ui" to "METHOD m_tgproxy",
        "game_booster_prefs" to "METHOD m_game; session state left out",
        "gb_crowd" to "METHOD m_game (share); crowd id left out",
        "vpngate_prefs" to "METHOD m_vpngate",
        "urnetwork" to "METHOD m_urnetwork (blocker, location, MAE switch); the usage and country-list caches left out",
        // ── left out ──
        "crash_diag" to "OUT: this phone's crash reporting",
        "mae_exit" to "OUT: which Worker MAE last measured through on this phone; re-learned on the first look",
        "traffic_stats" to "OUT: this phone's usage",
        "vpn_traffic_stats" to "OUT: this phone's usage",
        "vpn_session_traffic" to "OUT: this phone's usage",
        "mlm_pool" to "OUT: this install's id in the shared pool",
        "cf_edge" to "OUT: per network",
        "cfnet_addresses" to "OUT: per network",
        "cfnet_family" to "OUT: per network",
        "cfnet_cleanup" to "OUT: this phone's pending work",
        "cfnet_identity_cleanup" to "OUT: this phone's pending work",
        "warp_ladder" to "OUT: per network",
        "aether_carrier" to "OUT: per network",
        "masque_carrier" to "OUT: per network",
        "proton_routes" to "OUT: per network",
        "amnezia_sources" to "OUT: list cache",
        "outline_sources" to "OUT: list cache",
        "smart_iran_lists" to "OUT: list cache",
        "store_prefs" to "OUT: the store's install bookkeeping",
        "quick_connect" to "OUT: last connected (state)",
        "tgbridge" to "OUT: ports and edges per network",
        "wg_scanner_prefs" to "OUT: scan results",
        "geph_account" to "OUT: account info cache (the account itself is in secure_settings)",
        "studio_combine_handoff" to "OUT: a combine in progress",
        "cloud_coach" to "OUT: one-time guide",
        "combine_coach" to "OUT: one-time guide (a combine in progress)",
        "smart_verify" to "OUT: verification flow state",
        "split_tunneling" to "OUT: the old file vpn_routing_prefs replaced",
    )

    private val literal = Regex("getSharedPreferences\\(\\s*\"([^\"]+)\"")
    private val constant = Regex("getSharedPreferences\\(\\s*([A-Z_][A-Z0-9_]*)\\s*[,)]")

    private fun names(src: File): Set<String> {
        val out = sortedSetOf<String>()
        src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            literal.findAll(text).forEach { out += it.groupValues[1] }
            constant.findAll(text).forEach { m ->
                val name = m.groupValues[1]
                val def = Regex("val\\s+$name\\s*(?::\\s*String)?\\s*=\\s*\"([^\"]+)\"").find(text)
                out += def?.groupValues?.get(1) ?: "<unresolved $name in ${f.name}>"
            }
            if (text.contains("getDefaultSharedPreferences")) out += "@default"
        }
        return out
    }

    @Test
    fun `every prefs file in the code has a backup decision`() {
        val src = File("src/main/java")
        assumeTrue("run from the app module", src.isDirectory)
        val missing = names(src) - decided.keys
        assertTrue("prefs files with no backup decision (add them to an item or to this list): $missing", missing.isEmpty())
    }

    @Test
    fun `item ids are unique and every method has its tile`() {
        val all = MainItems.all + AccountItems.all + MethodItems.all
        val ids = all.map { it.id }
        assertTrue("duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}", ids.size == ids.toSet().size)
        assertTrue(ids.all { Regex("[a-z0-9_]{1,64}").matches(it) })
        assertTrue(all.filter { it.kind == BackupKind.METHOD }.all { !it.tileId.isNullOrBlank() })
        assertTrue(MainItems.all.all { it.kind == BackupKind.MAIN })
        assertTrue(AccountItems.all.all { it.kind == BackupKind.ACCOUNTS })
        assertTrue(MethodItems.all.all { it.kind == BackupKind.METHOD })
    }
}
