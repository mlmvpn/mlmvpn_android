package com.mlmvpn.core.tunnel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress

/**
 * «مسیریابی هوشمند ایران» (Smart Iran routing): with the switch on, Iranian sites and Iranian apps
 * leave the phone directly, never through the VPN -- on every engine of the app.
 *
 * Three layers, because no single one is enough:
 *
 *  1. Apps. Iranian apps (banks, Snapp, Divar, Bale, Rubika, the operators' apps, the app stores
 *     ...) are left out of the VPN entirely (`addDisallowedApplication`). This is the only layer a
 *     banking app that refuses to run under a VPN cannot see through: a left-out app does not see
 *     the VPN at all. Found from a hand-kept list, the `ir.` package convention, and the user's
 *     own choices (added or removed by hand).
 *  2. Addresses. Iran's IP ranges are taken out of the tunnel at the system level: Android 13+
 *     `excludeRoute` for the whole list; older versions route the complement instead (see
 *     [IranCidr.complementV4]). Works for every engine -- Xray, WireGuard/Amnezia, OpenVPN,
 *     Psiphon, Tor, Geph, SoftEther -- whatever it speaks.
 *  3. Names (the Xray engines). A routing rule sends every `.ir` name, the ~57,000 Iranian
 *     domains of the daily list (also those hosted abroad or behind a CDN) and Iranian addresses
 *     to the `direct` outbound, before anything else can carry them.
 *
 * The lists come from Chocolate4U/Iran-v2ray-rules, rebuilt daily, refreshed here once a day
 * ([SmartIranLists]); copies from the day the app was built ship in the APK.
 */
object SmartIranRoute {

    private const val TAG = "SmartIran"
    private const val PREFS = "smart_iran"
    private const val KEY_ON = "on"
    private const val KEY_ADDED = "apps_added"
    private const val KEY_REMOVED = "apps_removed"

    /** Older Android: only Iranian ranges this large are routed around (see [IranCidr]). */
    /**
     * Only Iran's large ranges become VPN routes; the small ones are left to the Xray layer (geoip
     * ir direct). Every route is an entry Android's own network stack keeps and re-checks: with all
     * ~2,900 of them (2,225 IPv4 + 714 IPv6 on Android 13+) a tester's whole phone ran hot, System
     * UI stopped responding, and switching the route off made it well again (2026-10-11).
     *  - Android 13+: IPv4 /19 and larger, 207 ranges, 84% of Iran's addresses; IPv6 /28 and larger.
     *  - older: the complement of /16 and larger, 253 routes (61%) -- it was /18, 702 routes.
     */
    private const val LEGACY_MAX_PREFIX = 16
    private const val MODERN_MAX_PREFIX_V4 = 19
    private const val MODERN_MAX_PREFIX_V6 = 28

    /** The Xray file names (in the core's asset dir, filesDir) of the daily Iran-only geo data. */
    const val GEOSITE_FILE = "iran_geosite.dat"
    const val GEOIP_FILE = "iran_geoip.dat"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
        if (on) SmartIranLists.ensureSeeded(ctx)
        cache = null
    }

    // ------------------------------------------------------------------ layer 1: apps

    /**
     * Iranian apps whose package names do not carry the `ir.` mark. Kept short and sure: a wrong
     * entry here would take an app out of the VPN that needs it. The user adds the rest by hand.
     */
    val KNOWN_APPS: Set<String> = setOf(
        // App stores
        "com.farsitel.bazaar", "ir.mservices.market",
        // Ride-hailing, delivery, shopping, travel
        "cab.snapp.passenger", "cab.snapp.driver", "com.zoodfood.android", "com.digikala", "com.digikala.seller",
        "taxi.tapsi.pack.passenger", "com.snapptrip.android", "com.alibaba.ir", "com.basalam.app",
        // Messengers
        "mobi.mmdt.ottplus", "mobi.mmdt.ott",
        // Payments and wallets
        "com.sibche.aspardproject.app", "com.mydigipay.app.android", "com.adpdigital.mbs.ayande",
        "com.isc.bsinew", "com.tosan.dara.mellat", "com.parsmobapp",
        // Operators
        "com.myirancell", "com.rightel.myrightel",
        // Maps and media
        "org.rajman.neshan.traffic.tehran", "com.aparat", "com.sabaidea.filimo", "com.sabaidea.aparat",
    )

    /** An `ir.` package or one with `.ir` as a segment: Iran's own convention for its apps. */
    fun looksIranian(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p in KNOWN_APPS || p.startsWith("ir.") || p.endsWith(".ir") || p.contains(".ir.")
    }

    fun addedApps(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_ADDED, emptySet()).orEmpty()
    fun removedApps(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_REMOVED, emptySet()).orEmpty()

    /** The user ticks or unticks one app on the Smart Iran page. */
    fun setApp(ctx: Context, pkg: String, outside: Boolean) {
        val added = addedApps(ctx).toMutableSet()
        val removed = removedApps(ctx).toMutableSet()
        if (outside) { removed -= pkg; if (!looksIranian(pkg)) added += pkg }
        else { added -= pkg; if (looksIranian(pkg)) removed += pkg }
        prefs(ctx).edit().putStringSet(KEY_ADDED, added).putStringSet(KEY_REMOVED, removed).apply()
    }

    /** Installed apps from an Iranian store: offered on the page, never left out without a tick. */
    fun fromIranianStore(ctx: Context, pkg: String): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
            else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(pkg)
        installer == "com.farsitel.bazaar" || installer == "ir.mservices.market"
    }.getOrDefault(false)

    /** Installed apps the route leaves outside the VPN right now. */
    fun outsideApps(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val removed = removedApps(ctx)
        val added = addedApps(ctx)
        val installed = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA).map { it.packageName }
        }.getOrDefault(emptyList())
        return installed.filter { it != ctx.packageName && it !in removed && (it in added || looksIranian(it)) }
    }

    /** Every launchable installed app, for the page's picker: (package, label, system). */
    fun launchableApps(ctx: Context): List<Triple<String, String, Boolean>> {
        val pm = ctx.packageManager
        return runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { Triple(it.packageName, pm.getApplicationLabel(it).toString(), (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
        }.getOrDefault(emptyList())
    }

    /**
     * The app layer on a VPN builder, in harmony with the user's split tunnel ([mode]): with all
     * apps or an exclusion list, Iranian apps are excluded too; with an "only these apps" list,
     * they are simply never among the allowed ones (Android forbids mixing the two calls, so the
     * caller passes the allowed list through [allowedFilter] instead).
     */
    fun excludeApps(ctx: Context, builder: VpnService.Builder, mode: SplitTunnelSettings.Mode, skip: Collection<String> = emptyList()): Int {
        if (!enabled(ctx) || mode == SplitTunnelSettings.Mode.INCLUDE) return 0
        var n = 0
        val already = skip.toHashSet()
        outsideApps(ctx).forEach { pkg -> if (pkg !in already) runCatching { builder.addDisallowedApplication(pkg); n++ } }
        if (n > 0) Log.i(TAG, "$n Iranian app(s) left outside the VPN")
        return n
    }

    /** For "only these apps" mode: the user's list without the Iranian apps the route leaves outside. */
    fun allowedFilter(ctx: Context, allowed: Collection<String>): Collection<String> {
        if (!enabled(ctx)) return allowed
        val outside = outsideApps(ctx).toHashSet()
        return allowed.filter { it !in outside }
    }

    // ------------------------------------------------------------------ layer 2: addresses

    private class Lists(val v4: List<IranCidr.V4>, val v6: List<IranCidr.V6>, val stamp: Long)
    @Volatile private var cache: Lists? = null

    private fun lists(ctx: Context): Lists {
        val f = SmartIranLists.cidrFile(ctx)
        val stamp = if (f.exists()) f.lastModified() else 0L
        cache?.takeIf { it.stamp == stamp }?.let { return it }
        val text = runCatching { if (f.exists()) f.readText() else ctx.assets.open(SmartIranLists.SEED_CIDR).bufferedReader().use { it.readText() } }.getOrDefault("")
        val (v4, v6) = IranCidr.parse(text)
        return Lists(v4, v6, stamp).also { cache = it }
    }

    /**
     * Instead of `addRoute("0.0.0.0", 0)`: the whole IPv4 space, minus Iran's ranges when the
     * route is on. [keep] are addresses that must stay inside whatever happens (the tunnel's
     * resolvers), even if they are Iranian.
     */
    fun routeAllV4(ctx: Context, builder: VpnService.Builder, keep: Collection<String> = emptyList()) {
        if (!enabled(ctx)) { builder.addRoute("0.0.0.0", 0); return }
        val l = runCatching { lists(ctx) }.getOrNull()
        if (l == null || l.v4.isEmpty()) { builder.addRoute("0.0.0.0", 0); return }
        val keepV = keep.mapNotNull { IranCidr.ipv4Value(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            var n = 0
            for (r in l.v4) {
                if (r.prefix > MODERN_MAX_PREFIX_V4 || keepV.any { it in r.start..r.end }) continue
                runCatching { builder.excludeRoute(IpPrefix(InetAddress.getByName(IranCidr.text(r.start)), r.prefix)); n++ }
            }
            Log.i(TAG, "IPv4: $n Iranian ranges excluded from the VPN")
        } else {
            val routes = IranCidr.complementV4(l.v4, LEGACY_MAX_PREFIX, keepV)
            var n = 0
            for (r in routes) runCatching { builder.addRoute(IranCidr.text(r.start), r.prefix); n++ }
            if (n == 0) builder.addRoute("0.0.0.0", 0)
            Log.i(TAG, "IPv4: routed around Iran's large ranges with $n routes (Android ${Build.VERSION.SDK_INT})")
        }
    }

    /** Instead of `addRoute("::", 0)`: on Android 13+, minus Iran's IPv6 ranges. */
    fun routeAllV6(ctx: Context, builder: VpnService.Builder) {
        builder.addRoute("::", 0)
        if (!enabled(ctx) || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val l = runCatching { lists(ctx) }.getOrNull() ?: return
        var n = 0
        for (r in l.v6) if (r.prefix <= MODERN_MAX_PREFIX_V6) runCatching { builder.excludeRoute(IpPrefix(InetAddress.getByName(r.address), r.prefix)); n++ }
        Log.i(TAG, "IPv6: $n Iranian ranges excluded from the VPN")
    }

    /**
     * For a tunnel whose routes the server pushes (OpenVPN, SoftEther): on Android 13+ Iran's
     * ranges are excluded on top of whatever was routed. Older versions keep the pushed routes;
     * the app layer still applies there.
     */
    fun excludeIranRoutes(ctx: Context, builder: VpnService.Builder) {
        if (!enabled(ctx) || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val l = runCatching { lists(ctx) }.getOrNull() ?: return
        var n = 0
        for (r in l.v4) if (r.prefix <= MODERN_MAX_PREFIX_V4) runCatching { builder.excludeRoute(IpPrefix(InetAddress.getByName(IranCidr.text(r.start)), r.prefix)); n++ }
        for (r in l.v6) if (r.prefix <= MODERN_MAX_PREFIX_V6) runCatching { builder.excludeRoute(IpPrefix(InetAddress.getByName(r.address), r.prefix)); n++ }
        Log.i(TAG, "$n Iranian ranges excluded from a pushed-route tunnel")
    }

    /** Every app layer at once, for engines with their own split-tunnel code: (allowed list to use, apps to exclude). */
    fun appsFor(ctx: Context, mode: SplitTunnelSettings.Mode, apps: Set<String>): Pair<Set<String>, Set<String>> {
        if (!enabled(ctx)) return apps to emptySet()
        val outside = outsideApps(ctx).toSet()
        return when (mode) {
            SplitTunnelSettings.Mode.INCLUDE -> (apps - outside).let { inc -> if (inc.isEmpty() && apps.isNotEmpty()) emptySet<String>() to outside else inc to emptySet() }
            else -> apps to (outside - apps)
        }
    }

    /**
     * For a WireGuard config: `AllowedIPs = 0.0.0.0/0` becomes the complement of Iran's large
     * ranges (the GoBackend turns AllowedIPs into routes; it has no exclusions of its own). A
     * config that does not route everything is left as it is.
     */
    fun wireGuardAllowedIps(ctx: Context, config: String): String {
        if (!enabled(ctx)) return config
        val l = runCatching { lists(ctx) }.getOrNull() ?: return config
        val re = Regex("""(?im)^([ \t]*AllowedIPs[ \t]*=[ \t]*)(.*)$""")
        return re.replace(config) { m ->
            val parts = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if ("0.0.0.0/0" !in parts) return@replace m.value
            val routes = IranCidr.complementV4(l.v4, LEGACY_MAX_PREFIX).map { it.toString() }
            m.groupValues[1] + (parts.filter { it != "0.0.0.0/0" } + routes).joinToString(", ")
        }
    }

    // ------------------------------------------------------------------ layer 3: names (Xray)

    /**
     * Puts the Iran rules into an Xray config: after any rule that names a source (blocked LAN
     * devices stay blocked) and before everything else, so nothing later can carry Iranian
     * traffic through the proxy. Needs an outbound tagged `direct`; one is added when missing.
     */
    fun injectXray(ctx: Context, json: JSONObject) {
        if (!enabled(ctx)) return
        runCatching {
            SmartIranLists.ensureSeeded(ctx)
            val outbounds = json.optJSONArray("outbounds") ?: return
            val directTag = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
                .firstOrNull { it.optString("protocol") == "freedom" && it.optString("tag").isNotBlank() }?.optString("tag")
                ?: "direct".also { outbounds.put(JSONObject().put("protocol", "freedom").put("tag", "direct")) }
            val routing = json.optJSONObject("routing") ?: JSONObject().also { json.put("routing", it) }
            val rules = routing.optJSONArray("rules") ?: JSONArray()
            val files = ctx.filesDir
            val domains = JSONArray().put("domain:ir")
            if (File(files, GEOSITE_FILE).length() > 1000) domains.put("ext:$GEOSITE_FILE:ir")
            else if (File(files, "geosite.dat").exists()) domains.put("geosite:category-ir")
            val ips = JSONArray()
            if (File(files, GEOIP_FILE).length() > 1000) ips.put("ext:$GEOIP_FILE:ir")
            else if (File(files, "geoip.dat").exists()) ips.put("geoip:ir")
            val mine = listOf(
                JSONObject().put("type", "field").put("domain", domains).put("outboundTag", directTag),
            ) + if (ips.length() > 0) listOf(JSONObject().put("type", "field").put("ip", ips).put("outboundTag", directTag)) else emptyList()
            var at = 0
            while (at < rules.length() && rules.optJSONObject(at)?.has("source") == true) at++
            val out = JSONArray()
            for (i in 0 until at) out.put(rules.get(i))
            mine.forEach { out.put(it) }
            for (i in at until rules.length()) out.put(rules.get(i))
            routing.put("rules", out)
            // The smart part: a name in no list is looked up, and where its address is Iranian the
            // connection goes straight -- so any site hosted on an Iranian server is found by where
            // it lives, not by a list of names (user request 2026-10-11: «واقعا هوشمند باشه»).
            // Only when the config left it at AsIs: a config that chose its own strategy keeps it.
            if (ips.length() > 0 && routing.optString("domainStrategy").let { it.isBlank() || it == "AsIs" }) {
                routing.put("domainStrategy", "IPIfNonMatch")
            }
            // Sniffing must name the connection for a domain rule to match on the TUN path.
            json.optJSONArray("inbounds")?.let { ins ->
                for (i in 0 until ins.length()) {
                    val inb = ins.optJSONObject(i) ?: continue
                    if (inb.optString("protocol") !in setOf("tun", "socks", "mixed", "http")) continue
                    if (!inb.has("sniffing")) inb.put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls")))
                }
            }
        }.onFailure { Log.w(TAG, "Iran rules not added: ${it.message}") }
    }

    fun injectXray(ctx: Context, config: String): String {
        if (!enabled(ctx) || !config.trimStart().startsWith("{")) return config
        return runCatching { JSONObject(config).also { injectXray(ctx, it) }.toString() }.getOrDefault(config)
    }

    /** Domain patterns for the transport stack's own direct list (its core matches `*.suffix`). */
    fun transportDirect(ctx: Context): List<String> =
        if (!enabled(ctx)) emptyList() else listOf("*.ir")
}
