package com.mlmvpn.scanner.engines.game.booster.model

import org.json.JSONArray
import org.json.JSONObject

/** An inclusive port range, "7085-7995" or a single "8700". */
data class PortRange(val from: Int, val to: Int) {
    init { require(from in 1..65535 && to in from..65535) }

    operator fun contains(port: Int): Boolean = port in from..to

    override fun toString(): String = if (from == to) "$from" else "$from-$to"

    companion object {
        /** "7085-7995, 8700" → ranges; anything unparsable is skipped rather than fatal. */
        fun parseList(text: String): List<PortRange> =
            // "7085 - 7995" is one range, not three tokens: close the gaps around dashes first.
            text.replace(Regex("""\s*[-–]\s*"""), "-").split(Regex("""[,;\s]+""")).mapNotNull { part ->
                val t = part.trim()
                if (t.isEmpty()) return@mapNotNull null
                val bits = t.split('-').map { it.trim() }
                try {
                    when (bits.size) {
                        1 -> bits[0].toInt().let { PortRange(it, it) }
                        2 -> PortRange(bits[0].toInt(), bits[1].toInt())
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
            }

        fun format(ranges: List<PortRange>): String = ranges.joinToString(", ")
    }
}

/** One region a game offers, and which anchor region stands in for its servers. */
data class GameRegion(
    val key: String,
    val anchorRegion: String,
    val labelFa: String,
    val labelEn: String,
)

/** A match server the booster saw the game talk to during a learning session. */
data class LearnedServer(
    val ip: String,
    val port: Int,
    val udp: Boolean,
    val regionKey: String? = null,
    val hits: Int = 1,
    val firstSeen: Long,
    val lastSeen: Long,
    val icmpMinMs: Int? = null,
    val pinned: Boolean = false,
)

/** The routes the booster can put a game on. Stored by name, so never rename one. */
enum class RouteKind {
    DIRECT, DIRECT_DNS, WARP_MASQUE_H3, WARP_MASQUE_H2, WARP_WG, ACCESS_HELPER,
    /** A free AmneziaWG / WireGuard server abroad, from the Amnezia section (its own engine). */
    AMNEZIA,
    /** Firefox's VPN on the user's own Firefox account (FENIX). */
    FENIX,
}

/** What the user chose for this game in the advanced screen. AUTO everywhere by default. */
enum class RouteChoice {
    AUTO, DIRECT, WARP,
    /** Pinned to WARP over WireGuard (with the exit country chosen on the WireGuard screen). */
    WIREGUARD,
    AMNEZIA,
    FENIX;

    /** The routes that leave through a tunnel of the app's own rather than the line itself. */
    val isTunnelPin: Boolean get() = this == WIREGUARD || this == AMNEZIA || this == FENIX
}

enum class ProbeDepth { QUICK, THOROUGH }

data class RoutePrefs(
    val route: RouteChoice = RouteChoice.AUTO,
    val dnsSteering: Boolean = false,
    val focus: Boolean = false,
    val overlay: Boolean = false,
    val depth: ProbeDepth = ProbeDepth.QUICK,
    /**
     * May the booster send the game's sanctioned sign-in / store / update names to an Iranian
     * anti-sanction DNS it has proven on this line? Never the match itself. On by default.
     */
    val sanctionDns: Boolean = true,
    /**
     * The player says this game is filtered or sanctioned, or does not load at all, here. The
     * direct line is then never chosen for it, however it measures: the fastest tunnel that
     * reaches the game's own region -- Amnezia, WireGuard, WARP or FENIX -- is.
     */
    val blocked: Boolean = false,
)

/**
 * Everything the booster knows about one game.
 *
 * Built-in profiles are never written to disk -- they are rebuilt at every load and only the
 * user's overrides, custom games and learned servers are stored -- so an app update can correct a
 * built-in without fighting a stale copy.
 */
data class GameProfile(
    /** "codm", "pubg", … for built-ins; "custom:<package>" for a game the user added. */
    val id: String,
    val name: String,
    val packages: List<String>,
    val emoji: String,
    val builtIn: Boolean,
    val regions: List<GameRegion>,
    val defaultRegion: String,
    /** A starting guess for learning; never used for routing. */
    val udpPorts: List<PortRange> = emptyList(),
    /** Login / API / CDN hosts, used to check the game can be reached at all. */
    val loginHosts: List<String> = emptyList(),
    val learned: List<LearnedServer> = emptyList(),
    val prefs: RoutePrefs = RoutePrefs(),
    /** The game's hosts per part of its traffic, from the catalog. Empty for most custom games. */
    val classes: Map<TrafficClass, List<String>> = emptyMap(),
    /**
     * Domain suffixes that may be steered to an anti-sanction DNS as a whole once one of their
     * hosts has been proven fixed by it -- the game signs in through more names than any list
     * knows. Only suffixes whose every name is access traffic belong here, never a match server's.
     */
    val steer: List<String> = emptyList(),
    val noteFa: String? = null,
    val noteEn: String? = null,
) {
    val isCustom: Boolean get() = id.startsWith(CUSTOM_PREFIX)

    fun region(key: String?): GameRegion =
        regions.firstOrNull { it.key == key } ?: regions.firstOrNull { it.key == defaultRegion } ?: regions.first()

    /**
     * What the doctor checks: the catalog's access hosts per part, or -- for a game with no
     * catalog entry -- its login hosts as sign-in.
     */
    fun accessHosts(): Map<TrafficClass, List<String>> {
        val fromCatalog = classes.filterKeys { it != TrafficClass.GAMEPLAY }.filterValues { it.isNotEmpty() }
        if (fromCatalog.isNotEmpty()) return fromCatalog
        return if (loginHosts.isEmpty()) emptyMap() else mapOf(TrafficClass.LOGIN to loginHosts)
    }

    companion object {
        const val CUSTOM_PREFIX = "custom:"
        const val MAX_LEARNED = 32
    }
}

/**
 * JSON for profiles, versioned so a future field never breaks an older store. Unknown fields are
 * ignored; missing ones take their defaults.
 */
object GameProfileCodec {
    const val VERSION = 1
    const val EXPORT_FORMAT = "mlmvpn-game-profile"

    fun toJson(p: GameProfile, includeLearned: Boolean = true): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("id", p.id)
        put("name", p.name)
        put("packages", JSONArray(p.packages))
        put("emoji", p.emoji)
        put("builtIn", p.builtIn)
        put("regions", JSONArray().apply {
            p.regions.forEach { r ->
                put(JSONObject().put("key", r.key).put("anchor", r.anchorRegion)
                    .put("fa", r.labelFa).put("en", r.labelEn))
            }
        })
        put("defaultRegion", p.defaultRegion)
        put("udpPorts", PortRange.format(p.udpPorts))
        put("loginHosts", JSONArray(p.loginHosts))
        if (includeLearned) put("learned", learnedToJson(p.learned))
        put("prefs", prefsToJson(p.prefs))
        if (p.classes.isNotEmpty()) put("classes", classesToJson(p.classes))
        if (p.steer.isNotEmpty()) put("steer", JSONArray(p.steer))
        p.noteFa?.let { put("noteFa", it) }
        p.noteEn?.let { put("noteEn", it) }
    }

    fun fromJson(o: JSONObject): GameProfile? = try {
        val regions = o.optJSONArray("regions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { r ->
                    GameRegion(
                        key = r.getString("key"),
                        anchorRegion = r.optString("anchor", RegionCatalog.DEFAULT_REGION),
                        labelFa = r.optString("fa", r.getString("key")),
                        labelEn = r.optString("en", r.getString("key")),
                    )
                }
            }
        }.orEmpty()
        GameProfile(
            id = o.getString("id"),
            name = o.getString("name"),
            packages = o.optJSONArray("packages").strings(),
            emoji = o.optString("emoji", "🎮"),
            builtIn = o.optBoolean("builtIn", false),
            regions = regions.ifEmpty { listOf(defaultRegion()) },
            defaultRegion = o.optString("defaultRegion", regions.firstOrNull()?.key ?: "ME"),
            udpPorts = PortRange.parseList(o.optString("udpPorts", "")),
            loginHosts = o.optJSONArray("loginHosts").strings(),
            learned = learnedFromJson(o.optJSONArray("learned")),
            prefs = prefsFromJson(o.optJSONObject("prefs")),
            classes = classesFromJson(o.optJSONObject("classes")),
            steer = o.optJSONArray("steer").strings(),
            noteFa = o.optString("noteFa").takeIf { it.isNotBlank() },
            noteEn = o.optString("noteEn").takeIf { it.isNotBlank() },
        )
    } catch (e: Exception) {
        null
    }

    /** `{"LOGIN":["a.example"],…}`; unknown part names are skipped, not fatal. */
    fun classesToJson(classes: Map<TrafficClass, List<String>>): JSONObject = JSONObject().apply {
        classes.forEach { (c, hosts) -> put(c.name, JSONArray(hosts)) }
    }

    fun classesFromJson(o: JSONObject?): Map<TrafficClass, List<String>> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<TrafficClass, List<String>>()
        o.keys().forEach { k ->
            val c = TrafficClass.byName(k) ?: return@forEach
            val hosts = o.optJSONArray(k).strings().map { it.trim().lowercase() }.distinct()
            if (hosts.isNotEmpty()) out[c] = hosts
        }
        return out
    }

    /** A shareable file: the profile wrapped with a format marker. Learned IPs only on request. */
    fun export(p: GameProfile, includeLearned: Boolean): String =
        JSONObject().put("format", EXPORT_FORMAT).put("v", VERSION)
            .put("profile", toJson(p, includeLearned)).toString(2)

    fun import(text: String): GameProfile? = try {
        val o = JSONObject(text)
        if (o.optString("format") != EXPORT_FORMAT) null
        else o.optJSONObject("profile")?.let { fromJson(it) }
    } catch (e: Exception) {
        null
    }

    fun learnedToJson(list: List<LearnedServer>): JSONArray = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().put("ip", s.ip).put("port", s.port).put("udp", s.udp)
                .put("region", s.regionKey ?: JSONObject.NULL).put("hits", s.hits)
                .put("first", s.firstSeen).put("last", s.lastSeen)
                .put("icmp", s.icmpMinMs ?: JSONObject.NULL).put("pinned", s.pinned))
        }
    }

    fun learnedFromJson(arr: JSONArray?): List<LearnedServer> = arr?.let {
        (0 until it.length()).mapNotNull { i ->
            it.optJSONObject(i)?.let { s ->
                try {
                    LearnedServer(
                        ip = s.getString("ip"),
                        port = s.getInt("port"),
                        udp = s.optBoolean("udp", true),
                        regionKey = s.optString("region").takeIf { r -> r.isNotEmpty() && r != "null" },
                        hits = s.optInt("hits", 1),
                        firstSeen = s.optLong("first", 0L),
                        lastSeen = s.optLong("last", 0L),
                        icmpMinMs = if (s.isNull("icmp")) null else s.optInt("icmp"),
                        pinned = s.optBoolean("pinned", false),
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }.orEmpty()

    fun prefsToJson(p: RoutePrefs): JSONObject = JSONObject()
        .put("route", p.route.name).put("dns", p.dnsSteering).put("focus", p.focus)
        .put("overlay", p.overlay).put("depth", p.depth.name).put("sdns", p.sanctionDns).put("blocked", p.blocked)

    fun prefsFromJson(o: JSONObject?): RoutePrefs {
        if (o == null) return RoutePrefs()
        return RoutePrefs(
            route = enumOr(o.optString("route"), RouteChoice.AUTO),
            dnsSteering = o.optBoolean("dns", false),
            focus = o.optBoolean("focus", false),
            overlay = o.optBoolean("overlay", false),
            depth = enumOr(o.optString("depth"), ProbeDepth.QUICK),
            sanctionDns = o.optBoolean("sdns", true),
            blocked = o.optBoolean("blocked", false),
        )
    }

    private fun defaultRegion() =
        GameRegion("ME", RegionCatalog.DEFAULT_REGION, "خاورمیانه", "Middle East")

    private fun JSONArray?.strings(): List<String> =
        this?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }
            .orEmpty()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
