package com.mlmvpn.scanner.backup

import android.content.Context
import com.mlmvpn.scanner.store.tr
import org.json.JSONObject

/**
 * «تنظیمات روش‌ها»: one item per tile, holding that tile's own choices.
 *
 * What a tile only learned or measured stays out: the method that won on a network, its endpoints,
 * delays and speeds, the lists it fetched. Those are about the network the phone was on, are wrong on
 * the next one, and are found again on the first connect.
 *
 * MASQUE, WireGuard and WARP-in-WARP read one set of settings (TransportAdvancedScreen), so they are
 * one item; the exit country is shared by the WARP family and is one item too.
 */
internal object MethodItems {

    val all: List<BackupContributor> = listOf(
        SimpleItem(
            "m_tunnel", BackupKind.METHOD, "ماسک، وایرگارد و وارپ در وارپ", "MASQUE, WireGuard and WARP-in-WARP",
            listOf(
                PrefsSlice(
                    "settings",
                    keys = setOf(
                        "default_protocol", "default_scan_mode", "default_scan", "endpoint_discovery",
                        "default_masque_transport", "masque_transport_auto_v1", "manual_endpoint",
                        "obfuscation_profile", "retry_obfuscation_profiles", "tls_curve_preset", "wireguard_data_check",
                        "log_level", "perf_profile", "h2_fragmentation", "dns_servers", "route_block", "route_direct",
                        "kill_switch", "auto_reconnect",
                    ),
                    prefixes = listOf("obfuscation_"),
                ),
            ),
            tileId = "masque",
        ),
        ExitCountry,
        SimpleItem(
            "m_warp", BackupKind.METHOD, "وارپ", "WARP",
            listOf(PrefsSlice("cfwarp", keys = setOf("ip", "keepalive", "noize", "scan"))),
            tileId = "warp",
        ),
        SimpleItem(
            "m_psiphon", BackupKind.METHOD, "سایفون", "Psiphon",
            listOf(
                PrefsSlice(
                    "settings",
                    keys = setOf("psiphon_method", "psiphon_egress_region", "psiphon_clean_fast", "chain_outer_mode"),
                    prefixes = listOf("psiphon_region_"),
                ),
            ),
            files = listOf("files/psiphon/clean-ips.json", "files/psiphon/upstream.json"),
            tileId = "psiphon",
        ),
        SimpleItem(
            "m_tor", BackupKind.METHOD, "تور", "Tor",
            listOf(
                PrefsSlice(
                    "settings",
                    keys = setOf("tor_mode", "tor_exit_region", "tor_chain_armed", "tor_bridge_choice", "tor_bridge_pinned", "chain_outer_mode_tor"),
                    prefixes = listOf("tor_region_"),
                ),
            ),
            files = listOf("files/tor_bridges/state.json"),
            tileId = "tor",
        ),
        SimpleItem(
            "m_lantern", BackupKind.METHOD, "لنترن", "Lantern",
            listOf(PrefsSlice("settings", keys = setOf("lantern_smart", "lantern_for_mae"))),
            tileId = "lantern",
        ),
        SimpleItem(
            "m_urnetwork", BackupKind.METHOD, "URnetwork", "URnetwork",
            listOf(PrefsSlice("urnetwork", keys = setOf("blocker", "location", "location_name", "location_cc", "for_mae"))),
            tileId = "urnetwork",
        ),
        SimpleItem(
            "m_geph", BackupKind.METHOD, "گف", "Geph",
            listOf(PrefsSlice("geph", whole = true, except = setOf("last_fastest"))),
            tileId = "geph",
        ),
        StateKeysItem(
            "m_mae", "MAE: سرویس‌ها، سایت‌ها و برنامه‌های شما", "MAE: your services, sites and apps", "mae",
            path = "files/mae/state.json",
            keys = listOf("onboarded", "selected", "customSites", "apps", "siteHosts", "xpk"),
            empty = { JSONObject().put("schema", com.mlmvpn.scanner.engines.mae.store.MaeStateCodec.SCHEMA) },
            validate = { com.mlmvpn.scanner.engines.mae.store.MaeStateCodec.decode(it) },
        ),
        StateKeysItem(
            "m_flux", "FLUX", "FLUX", "flux",
            path = "files/flux/state.json",
            keys = listOf("prefs"),
            empty = { JSONObject().put("schema", com.mlmvpn.scanner.engines.flux.core.memory.FluxState.SCHEMA) },
            validate = { com.mlmvpn.scanner.engines.flux.core.memory.FluxStateCodec.decode(it) },
        ),
        SimpleItem(
            "m_fenix", BackupKind.METHOD, "FENIX", "FENIX",
            listOf(PrefsSlice("fenix", keys = setOf("country", "for_mae", "http1"))),
            tileId = "fenix",
        ),
        StateKeysItem(
            "m_amnezia", "آمنزیا", "Amnezia", "amnezia",
            path = "files/amnezia/state.json",
            keys = listOf("prefs"),
            empty = { JSONObject().put("v", 2) },
            validate = { com.mlmvpn.scanner.engines.amnezia.AmzCodec.decode(it) },
            slices = listOf(PrefsSlice("amnezia_ui", whole = true)),
        ),
        StateKeysItem(
            "m_outline", "اوتلاین", "Outline", "outline",
            path = "files/outline/state.json",
            keys = listOf("prefs", "fav"),
            empty = { JSONObject().put("v", 1) },
            validate = { com.mlmvpn.scanner.engines.outline.OutlineCodec.decode(it) },
            slices = listOf(PrefsSlice("outline_ui", whole = true)),
        ),
        SimpleItem(
            "m_tgproxy", BackupKind.METHOD, "پروکسی تلگرام", "Telegram proxy",
            listOf(PrefsSlice("tgproxy_ui", whole = true)),
            tileId = "tgproxy",
        ),
        SimpleItem(
            "m_github", BackupKind.METHOD, "گیت‌هاب تانل", "GitHub Tunnel",
            listOf(
                PrefsSlice("github_tunnel", keys = setOf("auto_renew", "exit_prefs")),
                PrefsSlice("settings", prefixes = listOf("gt_exit_")),
            ),
            tileId = "github",
        ),
        SimpleItem(
            "m_quick", BackupKind.METHOD, "اتصال سریع: سرورهای حذف‌شده", "Quick Connect: removed servers",
            files = listOf("files/quick-blocklist.json"),
            tileId = "quick",
        ),
        SimpleItem(
            "m_game", BackupKind.METHOD, "بوستر بازی", "Game booster",
            listOf(
                PrefsSlice(
                    "game_booster_prefs",
                    keys = setOf("dedicated_dns_enabled", "dedicated_dns_mode", "dedicated_dns_manual_region", "aether_protocol", "aether_scan", "gb_last_game"),
                ),
                PrefsSlice("gb_crowd", keys = setOf("share")),
                PrefsSlice(DEFAULT_PREFS, keys = setOf("game_route_profile", "game_split_routes", "game_dns_ip")),
            ),
            files = listOf("files/game_booster/profiles.json"),
            tileId = "game",
        ),
        SimpleItem(
            "m_vpngate", BackupKind.METHOD, "VPN Gate", "VPN Gate",
            listOf(
                PrefsSlice("vpngate_prefs", keys = setOf("udp_acceleration", "kept_hosts", "hidden_hosts")),
                PrefsSlice("settings", keys = setOf("vpngate_recent", "vpngate_favourite")),
            ),
            tileId = "vpngate",
        ),
        SimpleItem(
            "m_v2ray", BackupKind.METHOD, "V2Ray", "V2Ray",
            listOf(
                PrefsSlice(
                    DEFAULT_PREFS,
                    keys = setOf(
                        "auto_switch_enabled", "auto_switch_interval", "auto_switch_platform", "auto_switch_test_count",
                        "nodes_group_by_panel", "nodes_manual_group", "nodes_tab_engine", "sort_state",
                    ),
                ),
            ),
            tileId = "nodes",
        ),
        SimpleItem(
            "m_emergency2", BackupKind.METHOD, "اضطراری ۲", "Emergency 2",
            listOf(PrefsSlice(DEFAULT_PREFS, keys = setOf("gst_sni_list", "gst_clean_ip_list"))),
            tileId = "emergency_2",
        ),
        SimpleItem(
            "m_emergency3", BackupKind.METHOD, "اضطراری ۳", "Emergency 3",
            listOf(
                PrefsSlice(
                    DEFAULT_PREFS,
                    keys = setOf("sni_selected_config_id", "sni_method_ech", "rsta_connect_ip", "rsta_connect_port", "rsta_fake_sni", "rsta_profile"),
                ),
            ),
            tileId = "emergency_3",
        ),
        SimpleItem(
            "m_antisanction", BackupKind.METHOD, "ضدتحریم", "Anti-sanction",
            listOf(PrefsSlice(DEFAULT_PREFS, prefixes = listOf("antisanction_"))),
            tileId = "antisanction",
        ),
    )

    /**
     * The exit country of «وارپ», «ماسک», «وایرگارد» and «وارپ در وارپ»: NAT64's choice and the user's
     * own gateways, and the IPv4 exit's switch and country. The proxies it measured stay out -- free
     * proxies die within hours.
     */
    private object ExitCountry : BackupContributor {
        override val id = "m_exit"
        override val kind = BackupKind.METHOD
        override val tileId = "warp_on_warp"
        override fun title() = tr("کشور خروج (وارپ، ماسک، وایرگارد)", "Exit country (WARP, MASQUE, WireGuard)")

        private const val FILE = "files/exit_proxy.json"
        private val slices = listOf(PrefsSlice("nat64_exit", keys = setOf("selection", "custom")))
        private val fields = listOf("enabled", "ack", "country")

        override fun export(ctx: Context): JSONObject? {
            val prefs = exportSlices(ctx, slices)
            val ipv4 = dataFile(ctx, FILE).takeIf { it.isFile }?.let { f ->
                val s = JSONObject(f.readText())
                JSONObject().apply { for (k in fields) if (s.has(k)) put(k, s.get(k)) }.takeIf { it.length() > 0 }
            }
            if (prefs == null && ipv4 == null) return null
            return JSONObject().apply {
                prefs?.let { put("prefs", it) }
                ipv4?.let { put("ipv4", it) }
            }
        }

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            planSlices(ctx, slices, data.optJSONObject("prefs"), mode, plan)
            val ipv4 = data.optJSONObject("ipv4")
            if (ipv4 == null && mode == RestoreMode.MERGE) return
            val s = currentText(ctx, plan, FILE)?.let(::JSONObject) ?: JSONObject()
            for (k in fields) {
                if (ipv4?.has(k) == true) s.put(k, ipv4.get(k)) else if (mode == RestoreMode.REPLACE) s.remove(k)
            }
            plan.putFile(FILE, s.toString().toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Top-level keys of an engine's state file -- the user's choices among what the engine learned --
     * written into the file as it stands, and checked with the engine's own decoder before staging.
     */
    private class StateKeysItem(
        override val id: String,
        private val fa: String,
        private val en: String,
        override val tileId: String,
        private val path: String,
        private val keys: List<String>,
        private val empty: () -> JSONObject,
        private val validate: (String) -> Unit,
        private val slices: List<PrefsSlice> = emptyList(),
    ) : BackupContributor {
        override val kind = BackupKind.METHOD
        override fun title() = tr(fa, en)

        override fun export(ctx: Context): JSONObject? {
            val state = dataFile(ctx, path).takeIf { it.isFile }?.let { f ->
                val s = JSONObject(f.readText())
                JSONObject().apply { for (k in keys) if (s.has(k)) put(k, s.get(k)) }.takeIf { it.length() > 0 }
            }
            val prefs = exportSlices(ctx, slices)
            if (state == null && prefs == null) return null
            return JSONObject().apply {
                state?.let { put("state", it) }
                prefs?.let { put("prefs", it) }
            }
        }

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            if (slices.isNotEmpty()) planSlices(ctx, slices, data.optJSONObject("prefs"), mode, plan)
            val state = data.optJSONObject("state")
            if (state == null && mode == RestoreMode.MERGE) return
            val s = currentText(ctx, plan, path)?.let(::JSONObject) ?: empty()
            for (k in keys) {
                if (state?.has(k) == true) s.put(k, state.get(k)) else if (mode == RestoreMode.REPLACE) s.remove(k)
            }
            val text = s.toString()
            validate(text)
            plan.putFile(path, text.toByteArray(Charsets.UTF_8))
        }
    }
}
