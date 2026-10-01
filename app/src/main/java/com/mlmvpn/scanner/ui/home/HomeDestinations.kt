package com.mlmvpn.scanner.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mlmvpn.scanner.R

/**
 * One tile on the home screen.
 *
 * `opensAsModal` marks the destinations that are not tabs: the emergency screens, the Iran and
 * domain-fronting profiles and the anti-sanction DNS screen render as full-screen overlays above
 * everything, so they are
 * routed through AppScreen's `activeModal` rather than through its tab stack. Getting this wrong
 * shows a blank screen, which is why it is recorded here next to the id instead of being
 * re-derived at each call site.
 */
data class HomeApp(
    val id: String,
    val labelRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val opensAsModal: Boolean = false,
    /**
     * Finished artwork for this icon, drawn instead of the glyph-on-a-tinted-tile.
     *
     * A drawable here already contains its own background and its own colours, so the tile does
     * NOT paint one behind it -- painting a tint under a complete icon would show as a coloured
     * ring around its corners. [icon] and [tint] stay filled in as the fallback for anywhere the
     * artwork is not appropriate, and so a destination is never left without a glyph.
     */
    val imageRes: Int? = null,
    /**
     * How much of the tile the glyph fills, as a fraction of the tile's side.
     *
     * A constant is nearly always right, which is why 0.52 is the default. It is wrong for the
     * few Material glyphs drawn small inside their own box -- Radar is concentric rings with a
     * wide margin baked in, so at the shared size it reads noticeably lighter than the solid
     * shapes beside it. This is an optical correction for those, not a knob to reach for.
     */
    val glyphScale: Float = 0.52f,
    /**
     * The desktop's own glyph for this destination (an `ic_glyph_*` vector generated from the
     * Windows sprite by scripts/icons-from-windows.js), drawn white on the tinted tile instead of
     * [icon]. [icon] stays as the fallback and for the places that want a Material glyph.
     */
    val glyphRes: Int? = null,
)

object HomeDestinations {

    // Apple's system colours. Using the real values rather than approximations is most of what
    // makes a grid of squircles read as "an iPhone home screen" instead of "coloured squares".
    val Blue   = Color(0xFF007AFF)
    val Green  = Color(0xFF34C759)
    val Indigo = Color(0xFF5856D6)
    val Orange = Color(0xFFFF9500)
    val Pink   = Color(0xFFFF2D55)
    val Purple = Color(0xFFAF52DE)
    val Red    = Color(0xFFFF3B30)
    val Teal   = Color(0xFF5AC8FA)
    val Yellow = Color(0xFFFFCC00)
    val Gray   = Color(0xFF8E8E93)

    /**
     * The dock. Fixed, not reorderable, and visible only on the home screen -- the same contract
     * as an iPhone's dock. Four is the whole point: it is the shortlist, so it does not grow.
     */
    val DOCK: List<HomeApp> = listOf(
        HomeApp("scanner",  R.string.nav_scanner,   Icons.Default.Radar,    Blue, glyphRes = R.drawable.ic_glyph_radar),
        HomeApp("nodes", R.string.home_v2ray, Icons.Default.Bolt, Indigo, imageRes = R.drawable.ic_app_v2ray),
        HomeApp(
            "settings",
            R.string.home_settings,
            Icons.Default.Settings,
            Gray,
            imageRes = R.drawable.ic_app_settings,
        ),
        // Cloud takes the fourth slot. It is where a panel gets deployed and where every config
        // this app makes comes from, so it is reached far more often than any single transport --
        // and it is the one destination a new user has to visit before anything else works.
        // MASQUE moved to the grid beside the other four transports, where it reads as one of
        // five rather than as the odd one out.
        HomeApp("cloud", R.string.nav_cloud, Icons.Default.Cloud, Teal, imageRes = R.drawable.ic_app_cloudflare),
    )

    /**
     * Everything else, in its factory order. This replaces the hamburger drawer outright: every
     * entry that used to be hidden behind it is here, plus the tabs that lost their slot in the
     * old bottom bar.
     *
     * The order is by expected use, not by category -- the four transports lead because they are
     * what the app is for and because four fills a row exactly, then Quick Connect as the
     * shortest path to being online without configuring anything, and the emergency entries last
     * because they are for the day nothing else works. A user who disagrees can drag them;
     * see HomeLayoutStore, which also appends ids a saved layout has never seen rather than
     * resetting it.
     *
     * Since the board gained folders this list is the REGISTRY -- every destination the app has,
     * whether it sits on the board or inside a folder. Where each one goes on a fresh install is
     * [BOARD_DEFAULT] and [DEFAULT_FOLDERS].
     */
    val GRID_DEFAULT: List<HomeApp> = listOf(
        HomeApp("cfdoctor", R.string.cf_doctor_title, Icons.Default.Cloud, Blue, imageRes = R.drawable.ic_app_doctor),
        // The other four transports of the tunnel stack, first and in one unbroken row of four.
        // Together with MASQUE in the dock they are five separate products, not five modes of
        // one: MASQUE and WireGuard reach Cloudflare's edge with entirely different handshakes,
        // WARP-on-WARP stacks one inside the other, and Psiphon and Tor are different networks
        // with their own servers and their own countries. An icon each is what says so -- and
        // the row break is what says they belong together, which is why they lead rather than
        // starting mid-row where the fourth would wrap away from the other three.
        HomeApp("masque",            R.string.home_masque,        Icons.Default.Bolt,          Blue, imageRes = R.drawable.ic_app_masque),
        HomeApp("wireguard",         R.string.home_wireguard,     Icons.Default.VpnKey,        Green, imageRes = R.drawable.ic_app_wireguard),
        HomeApp("warp_on_warp",      R.string.home_warp_on_warp,  Icons.Default.Layers,        Orange, glyphRes = R.drawable.ic_glyph_layers),
        // «وارپ»: plain WARP on an engine of its own, with the desktop's artwork.
        HomeApp("warp",              R.string.home_warp,          Icons.Default.Cloud,         Teal,   imageRes = R.drawable.ic_app_warp),
        // Psiphon's own mark, seated on the app's squircle in the red the mark is already
        // painted in -- so the disc's edge disappears into the tile instead of reading as a
        // circle in a square hole. Hub/Indigo stay as the fallback glyph; see HomeApp.imageRes.
        HomeApp("psiphon", R.string.home_psiphon, Icons.Default.Hub, Indigo, imageRes = R.drawable.ic_app_psiphon),
        HomeApp("tor",               R.string.home_tor,           Icons.Default.Shield,        Purple, imageRes = R.drawable.ic_app_tor),
        // «گف»: Geph's own network and engine, with the Windows desktop's artwork.
        HomeApp("geph",              R.string.home_geph,          Icons.Default.Public,        Blue,   imageRes = R.drawable.ic_app_geph),
        HomeApp("quick",             R.string.home_quick,         Icons.Default.RocketLaunch,  Blue),
        HomeApp("mae",               R.string.mae_short,          Icons.Default.AutoAwesome,   Blue, imageRes = R.drawable.ic_app_mae),
        // «FLUX»: country, IP version, connect -- the rest is the engine's job. Beside MAE, which
        // can use FLUX's routes as exits of its own.
        HomeApp("flux",              R.string.flux_title_short,   Icons.Default.Bolt,          Indigo, imageRes = R.drawable.ic_app_flux),
        // «آمنزیا»: free WireGuard (hidden behind AmneziaWG junk packets) and Hysteria2 servers,
        // plus the user's own .conf files, by country and by real delay.
        HomeApp("amnezia",           R.string.amz_title,          Icons.Default.VpnKey,        Orange, imageRes = R.drawable.ic_app_amnezia),
        // A cloud server on the user's own GitHub allowance, reached through a Worker on their own
        // Cloudflare -- the Windows app's «گیت‌هاب تانل», whole. Beside Quick Connect: both are
        // "get me online", and this one is the fast one when the free networks are slow.
        HomeApp("github", R.string.home_github, Icons.Default.Cloud, Gray, imageRes = R.drawable.ic_app_github),
        // Was the first row of V2Ray's Add-Node page. It is the only thing in the app that finds
        // working servers for someone who has none, which made three levels down inside the
        // feature they cannot yet use exactly the wrong place for it.
        //
        // A downward cloud rather than the Bolt the old row carried: Bolt is already the V2Ray
        // and MASQUE glyph, and a third one would have made the newest icon the least legible on
        // the grid. Teal is free in practice -- the only other destination holding it is Cloud,
        // which draws Cloudflare's own artwork through `imageRes` and never renders this tint or
        // a cloud glyph at all.
        HomeApp("freeconfig",        R.string.home_freeconfig,    Icons.Default.CloudDownload, Teal),
        HomeApp("game",              R.string.home_game,          Icons.Default.SportsEsports, Green, glyphRes = R.drawable.ic_glyph_pad),
        HomeApp("vpngate",           R.string.home_gateway,       Icons.Default.Public,        Indigo, imageRes = R.drawable.ic_app_gateway),
        HomeApp("openvpn", R.string.home_openvpn, Icons.Default.VpnKey, Orange, imageRes = R.drawable.ic_app_openvpn),
        HomeApp("fixed_ip",          R.string.home_fixed_ip,      Icons.Default.LocationOn,    Pink, glyphRes = R.drawable.ic_glyph_pin),
        HomeApp("sublink",           R.string.home_sublink,       Icons.Default.Link,          Purple),
        // Beside sublink rather than beside cloud, and not as a row inside «ابری» at all. Cloud is
        // the plumbing screen -- credentials, workers, what is deployed where. This is a product
        // that happens to run on that plumbing, and listing it under the engine it extends is
        // exactly the association the naming rule exists to avoid.
        HomeApp("configstudio",      R.string.studio_home_tile,   Icons.Default.Tune,          Indigo),
        // Sharing the tunnel with a second device was a switch in Settings called "Allow LAN"
        // and nothing else -- no address to type, no way to see whether anyone had connected,
        // and no hint that the engine currently running might publish no listener at all. It is
        // a destination, so it gets an icon.
        //
        // Teal is Cloud's nominal tint, which Cloud never renders: it draws Cloudflare's own
        // artwork through `imageRes`. So the colour is free, and it is the one the Settings row
        // for this setting has always used.
        HomeApp("lan",               R.string.home_lan,           Icons.Default.Lan,           Teal),
        // «ام‌ال‌ام استور»: the app, every engine, every worker on the user's accounts and the data
        // files, updated from one place — the Windows app's store, with the same artwork.
        HomeApp("store", R.string.home_store, Icons.Default.CloudDownload, Blue, imageRes = R.drawable.ic_app_store),
        HomeApp("antisanction", R.string.home_antisanction, Icons.Default.Shield, Green, opensAsModal = true, glyphRes = R.drawable.ic_glyph_shield_check),
        // The two built-in profiles, off the shelf they were on.
        //
        // Both lived as folders inside the connection list, which is where configs ARRIVE -- a
        // panel deployed, a subscription imported, a scan combined. These two ship with the app
        // and have nothing to do with an account or a panel, and the Iran defaults in particular
        // are what someone reaches for on the day nothing else works: "open the config list and
        // find the right folder among the panels" is not a path anyone walks in that state.
        //
        // Domain fronting keeps its own tint rather than borrowing the emergency red: it is not
        // a last resort, it is a different technique -- no server anywhere in the path.
        // The country's own outline, on white. Flag/Green stay as the fallback glyph the tile
        // draws if the artwork is ever missing; see HomeApp.imageRes.
        HomeApp(
            "iran",
            R.string.home_iran,
            Icons.Default.Flag,
            Green,
            opensAsModal = true,
            imageRes = R.drawable.ic_app_iran,
        ),
        HomeApp("fronting", R.string.home_fronting, Icons.Default.SwapHoriz, Yellow, opensAsModal = true, glyphRes = R.drawable.ic_glyph_swap),
        HomeApp("usage",             R.string.home_usage,         Icons.Default.BarChart,      Yellow, glyphRes = R.drawable.ic_glyph_bars),
        HomeApp("tutorial",          R.string.home_tutorial,      Icons.Default.School,        Orange, glyphRes = R.drawable.ic_glyph_book),
        // About is not here any more. It is Settings > About > About Us -- the last row of the
        // group that already carries the version, the updater and the crash reports, which is
        // what About is about. A tile on the grid claimed it was a destination on the level of
        // a transport, and it is the one screen nobody opens twice.
        //
        // The Vercel tunnel («تونل ورسل») that led this group is gone from the app entirely: its
        // shared proxy answered 402 and could not be revived from Iran.
        HomeApp("emergency_2", R.string.home_emergency_2, Icons.Default.FlashOn, Red, opensAsModal = true, imageRes = R.drawable.ic_app_gscript),
        HomeApp("emergency_3",       R.string.home_emergency_3,   Icons.Default.VpnLock,       Red,    opensAsModal = true),
    )

    /**
     * The folders a fresh board starts with, by key; members are destination ids, in order.
     *
     * «ابزارها» is the Windows desktop's folder of the same name: the destinations a user reaches
     * for now and then -- a fixed exit address, a subscription link, sharing with another device,
     * the usage meter, the tutorials -- rather than the ones that connect the phone. Like there, it
     * is only the folder a board starts with: it can be renamed, emptied and taken apart.
     */
    val DEFAULT_FOLDERS: Map<String, List<String>> = mapOf(
        "tools" to listOf("cfdoctor", "fixed_ip", "sublink", "lan", "usage", "tutorial"),
    )

    /**
     * A fresh board's top level: destination ids, and `@key` for a folder from [DEFAULT_FOLDERS].
     *
     * This decides a fresh install only; an existing board keeps its own order (see
     * [HomeLayoutStore]). Any destination missing here still appears -- the reconciliation
     * appends it -- so forgetting to list a new one costs its position, never its icon.
     */
    val BOARD_DEFAULT: List<String> = listOf(
        "masque", "wireguard", "warp_on_warp", "warp",
        "psiphon", "tor", "geph", "quick", "mae", "flux",
        "amnezia", "github", "freeconfig",
        "game", "vpngate", "openvpn", "configstudio",
        "store", "antisanction", "iran", "fronting",
        "@tools", "emergency_2", "emergency_3",
    )

    /**
     * What a destination is for, which is what a folder made from it is called -- the way iOS
     * names a new folder after its apps' category instead of "New Folder". The category travels in
     * the folder's key ([newFolderKey]), so the name follows the app's language until the user
     * types one of their own, exactly as the factory «ابزارها» does.
     */
    enum class Category(val keyPart: String, val titleRes: Int) {
        CONNECT("connect", R.string.home_folder_connect),
        CONFIGS("configs", R.string.home_folder_configs),
        TOOLS("tools", R.string.home_folder_tools),
        GAMES("games", R.string.home_folder_games),
        EMERGENCY("emergency", R.string.home_folder_emergency),
    }

    fun categoryOf(id: String): Category? = when (id) {
        "masque", "wireguard", "warp_on_warp", "warp", "psiphon", "tor", "geph", "quick", "mae", "flux",
        "amnezia", "github", "vpngate", "openvpn", "nodes" -> Category.CONNECT
        "freeconfig", "iran", "fronting", "sublink", "configstudio", "cloud" -> Category.CONFIGS
        "cfdoctor", "fixed_ip", "lan", "usage", "tutorial", "store", "scanner", "settings" -> Category.TOOLS
        "game" -> Category.GAMES
        "emergency_2", "emergency_3", "antisanction" -> Category.EMERGENCY
        else -> null
    }

    /**
     * The key for a folder made by dropping [draggedId] onto [targetId]: their shared category, or
     * the target's -- the app that was there first is the one the folder grows out of.
     */
    fun newFolderKey(targetId: String, draggedId: String, now: Long = System.currentTimeMillis()): String {
        val target = categoryOf(targetId)
        val dragged = categoryOf(draggedId)
        val category = if (target != null && target == dragged) target else target ?: dragged
        val stamp = now.toString(36)
        return if (category != null) "c_${category.keyPart}_$stamp" else "f$stamp"
    }

    /** The name a folder shows while the user has not given it one of their own. */
    fun folderTitleRes(key: String): Int = when {
        key == "tools" -> R.string.home_folder_tools
        key.startsWith("c_") ->
            Category.entries.firstOrNull { key.startsWith("c_${it.keyPart}_") }?.titleRes ?: R.string.home_folder_new
        else -> R.string.home_folder_new
    }

    fun gridById(id: String): HomeApp? = GRID_DEFAULT.firstOrNull { it.id == id }
}
