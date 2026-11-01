package com.mlmvpn.scanner.engines.mae.route

import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import org.json.JSONArray
import org.json.JSONObject

enum class RouteKind {
    /** Plain direct: fastest when nothing is in the way. */
    DIRECT,
    /** Stays in Iran but gets past filtering (fragment, Serverless). Fast, Iranian IP. */
    BYPASS,
    /** Exits abroad. Usable for a service only after that service's own acceptance probe passes. */
    FOREIGN,
}

data class Capabilities(
    val tcp: Boolean = true,
    val udp: Boolean = false,
    val ipv6: Boolean = true,
    /** Costs quota (Worker requests/CPU): heavy traffic avoids it when anything else works. */
    val quotaLimited: Boolean = false,
    /** Cannot reach Cloudflare-fronted destinations (Worker `connect()` refuses Cloudflare IPs). */
    val noCloudflareDestinations: Boolean = false,
    /**
     * QUIC (UDP/443) gets through. A fragment route passes UDP untouched -- the trick is for TLS
     * over TCP -- so a filtered service's QUIC leaves as plain as it came and stalls until the app
     * gives up on it. The Serverless base refuses QUIC outright for exactly that reason, which
     * sends the app to TCP at once; a route with `quic = false` does the same for its services.
     */
    val quic: Boolean = udp,
    /**
     * One exit address for a whole session. False for an exit that moves between servers: Lantern
     * spreads connections over its list (its exit went from Germany to Britain inside one session),
     * and a Worker leaves from whichever colo takes each connection. An app that holds a sign-in to
     * where it was first seen (Gemini) passes a page check through one and is refused at the first
     * prompt (the user, 2026-10-18).
     */
    val stableExit: Boolean = true,
)

/**
 * One way out. A provider only describes itself as Xray outbounds; the compiler wires them in,
 * the prober measures them, and the scorer chooses. Adding a new foreign exit is one new class
 * here -- nothing in the compiler, scorer or UI changes.
 *
 * Tags are chosen so that none is a prefix of another: Xray balancer selectors match by prefix.
 */
interface RouteProvider {
    val id: String
    val kind: RouteKind
    val caps: Capabilities
    /** Relative cost for the scorer (battery/CPU/quota), 0 = free. */
    val cost: Double get() = 0.0
    /** Outbounds this provider adds to the config. Empty when they already exist in the base. */
    fun outbounds(): List<JSONObject>
    /** The outbound tag that carries this route with the given family policy. */
    fun tag(family: FamilyPolicy): String
    /** Families this provider can be asked for. */
    val families: List<FamilyPolicy> get() = listOf(FamilyPolicy.BOTH)
}

/**
 * Plain direct, no tricks, per address family. Families are separate routes because they can
 * behave as different countries: measured on MCI (2026-09-29), api.openai.com and
 * api.anthropic.com served the IPv4 address normally and refused the IPv6 one as Iran.
 * Freedom takes its family from `sockopt.domainStrategy`.
 */
object DirectRoute : RouteProvider {
    override val id = "direct"
    override val kind = RouteKind.DIRECT
    override val caps = Capabilities(udp = true)
    override val families = listOf(FamilyPolicy.V4_ONLY, FamilyPolicy.V6_ONLY, FamilyPolicy.BOTH)
    /** The both-families tag, also used for MAE's own plumbing (e.g. direct DoH). */
    const val TAG = "mae-dd"
    override fun outbounds() = families.map { f ->
        JSONObject().put("tag", tag(f)).put("protocol", "freedom")
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("domainStrategy", when (f) {
                FamilyPolicy.V4_ONLY -> "ForceIPv4"
                FamilyPolicy.V6_ONLY -> "ForceIPv6"
                FamilyPolicy.BOTH -> "UseIP"
            })))
    }
    override fun tag(family: FamilyPolicy) = when (family) {
        FamilyPolicy.V4_ONLY -> "mae-d4"
        FamilyPolicy.V6_ONLY -> "mae-d6"
        FamilyPolicy.BOTH -> TAG
    }
}

/**
 * The Serverless profile's own TLS-fragment outbound. The profile is MAE's base config, so this
 * route is exactly the speed users already get from Serverless -- MAE adds nothing to its path.
 */
object ServerlessRoute : RouteProvider {
    override val id = "serverless"
    override val kind = RouteKind.BYPASS
    override val caps = Capabilities(udp = true, quic = false)
    const val TAG = "tcp-fragment-tls"
    override fun outbounds() = emptyList<JSONObject>()
    override fun tag(family: FamilyPolicy) = TAG
}

/** A second fragment recipe (the Game Booster's), for networks where Serverless's split is caught. */
object FragmentRoute : RouteProvider {
    override val id = "fragment"
    override val kind = RouteKind.BYPASS
    override val caps = Capabilities(udp = false)
    override val cost = 0.05
    const val TAG = "mae-frag"
    override fun outbounds() = listOf(
        JSONObject().put("tag", TAG).put("protocol", "freedom")
            .put("streamSettings", JSONObject()
                .put("sockopt", JSONObject().put("domainStrategy", "UseIP"))
                .put("finalmask", JSONObject().put("tcp", JSONArray()
                    .put(JSONObject().put("type", "fragment").put("settings", JSONObject()
                        .put("packets", "tlshello").put("lengths", JSONArray().put("6").put("98").put("1"))
                        .put("delays", JSONArray().put("0")).put("maxSplit", "0")))
                    .put(JSONObject().put("type", "fragment").put("settings", JSONObject()
                        .put("packets", "1-1").put("lengths", JSONArray().put("114").put("1"))
                        .put("delays", JSONArray().put("1")).put("maxSplit", "11")))))),
    )
    override fun tag(family: FamilyPolicy) = TAG
}

/**
 * Cloudflare WARP (free, the app's own identity) as an Xray WireGuard outbound.
 *
 * A BYPASS, not a foreign exit -- measured, not assumed (MCI, 2026-09-29): WARP's exit address
 * geolocates to the user's own country (ip-api "IR Cloudflare, Inc.", Cloudflare's trace
 * `loc=IR`), and OpenAI, Anthropic and Gemini refused it as Iran, while YouTube, Instagram and
 * Telegram opened. What it adds over the Worker: no quota, and it carries UDP (QUIC, calls).
 *
 * Plain WireGuard got no reply from any endpoint on MCI -- the handshake is recognised and
 * dropped. A few random UDP packets first (Xray `finalmask` noise, the same device the Serverless
 * profile uses for UDP) and every endpoint answered.
 */
class WarpRoute(
    private val privateKey: String,
    private val peerPublicKey: String,
    private val v4: String,
    private val v6: String,
    private val reserved: List<Int>,
    private val endpoint: String,
) : RouteProvider {
    override val id = ID
    override val kind = RouteKind.BYPASS
    override val caps = Capabilities(udp = true)
    override val cost = 0.1

    /**
     * ONE WireGuard session per core. Each WireGuard outbound is its own session, and Cloudflare
     * keeps one per key: three per-family outbounds with one key (plus the probe core's copies)
     * took the session from each other -- the server answers whichever handshook last -- and the
     * route died under the apps using it. Families are not what WARP is chosen for (its exit is
     * Iranian anyway); every family maps to the one outbound.
     */
    override val families = listOf(FamilyPolicy.BOTH)

    override fun tag(family: FamilyPolicy) = TAG

    override fun outbounds() = listOf(
        JSONObject().put("tag", TAG).put("protocol", "wireguard")
            .put("settings", JSONObject()
                .put("secretKey", privateKey)
                .put("address", JSONArray().apply { if (v4.isNotBlank()) put("$v4/32"); if (v6.isNotBlank()) put("$v6/128") })
                .put("mtu", 1280)
                .put("reserved", JSONArray(reserved))
                // WireGuard's own family choice for the destination (not a proxied targetStrategy).
                .put("domainStrategy", "ForceIP")
                .put("peers", JSONArray().put(JSONObject()
                    .put("publicKey", peerPublicKey)
                    .put("endpoint", endpoint)
                    // Keeps the carrier's UDP mapping open between an app's own packets. Each one
                    // switches the phone's radio on, so this is a battery cost paid all session:
                    // 25 s, WireGuard's own recommendation for a peer behind NAT and what the app's
                    // standalone WARP engine has always used on the same networks (CfWarpEngine),
                    // rather than 15 s -- with a radio that stays up ~10 s after each packet, 15 s
                    // kept it on about two thirds of the time.
                    .put("keepAlive", KEEPALIVE_S)
                    .put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0")))))
            .put("streamSettings", JSONObject().put("finalmask", JSONObject().put("udp", JSONArray().put(
                JSONObject().put("type", "noise").put("settings", JSONObject().put("noise", JSONArray()
                    .put(JSONObject().put("rand", "10-20").put("delay", "10"))
                    .put(JSONObject().put("rand", "10-20").put("delay", "10")))))))),
    )

    companion object {
        const val ID = "warp"
        const val TAG = "mae-wgd"
        const val KEEPALIVE_S = 25
        /** Tried in order, one per network until one carries traffic (MCI: all of these did). */
        val ENDPOINTS = listOf("162.159.192.1:2408", "162.159.192.1:500", "188.114.97.1:4500", "[2606:4700:d0::a29f:c001]:2408", "162.159.195.1:1701")
    }
}

/**
 * One of the user's own saved configs (VLESS / Trojan / …) as a foreign-exit CANDIDATE.
 *
 * The second foreign exit. The user already has configs in the app; MAE takes a few of the best,
 * and the same proofs as for the Worker decide, per app and per IP family, whether each is
 * really abroad and accepted. Nothing is assumed from the config's name or country code.
 *
 * [proxy] is the config's own `proxy` outbound as the app's generator builds it; it is copied
 * per family with the outbound-level `targetStrategy`.
 */
class UserConfigRoute(
    /**
     * `cfg-` and six hex digits of the config's own id ([idFor]): the SAME config keeps the same
     * id whatever the order of the list. It used to be its position (`cfg1`..`cfg3`) in a list
     * re-sorted by delay -- every ping test re-dealt the positions, and a proof earned by one
     * server was then credited to another.
     */
    override val id: String,
    private val proxy: JSONObject,
    /** For diagnostics only: the config's own name. */
    val label: String,
) : RouteProvider {
    override val kind = RouteKind.FOREIGN
    override val caps = Capabilities(udp = false)
    override val cost = 0.15
    override val families = listOf(FamilyPolicy.V4_ONLY, FamilyPolicy.V6_ONLY, FamilyPolicy.BOTH)

    // Same length for every config, so no tag is a prefix of another.
    override fun tag(family: FamilyPolicy) = "mae-$id-" + when (family) {
        FamilyPolicy.V4_ONLY -> "4"
        FamilyPolicy.V6_ONLY -> "6"
        FamilyPolicy.BOTH -> "d"
    }

    override fun outbounds() = families.map { f ->
        JSONObject(proxy.toString()).apply {
            put("tag", tag(f))
            when (f) {
                FamilyPolicy.V4_ONLY -> put("targetStrategy", "ForceIPv4")
                FamilyPolicy.V6_ONLY -> put("targetStrategy", "ForceIPv6")
                FamilyPolicy.BOTH -> remove("targetStrategy")
            }
        }
    }

    companion object {
        const val PREFIX = "cfg-"
        const val MAX = 3

        /** Configs from the user's own Cloudflare panels (see MaeCloudConfigs), not the saved list. */
        const val CLOUD_PREFIX = "cfc-"

        /** The route id of the saved config whose own id is [nodeId] (or of a panel link, with [CLOUD_PREFIX]). */
        fun idFor(nodeId: String, prefix: String = PREFIX): String {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(nodeId.toByteArray())
            return prefix + d.take(3).joinToString("") { "%02x".format(it) }
        }

        /** A route that is one of the user's configs, saved or from a panel. */
        fun isConfigRoute(id: String) = id.startsWith(PREFIX) || id.startsWith(CLOUD_PREFIX)

        /** Route ids from before [idFor] (positions, not identities): what was learned under them is dropped. */
        val LEGACY_IDS = setOf("cfg1", "cfg2", "cfg3")
    }
}

/**
 * VLESS over WebSocket to the user's own Worker, which dials the destination with `connect()`.
 *
 * A CANDIDATE, not a general foreign exit: TCP only, quota-limited, and unable to reach anything
 * behind Cloudflare (chatgpt.com, for one). Whether its exit is foreign, in which family, and
 * whether a given service accepts it are all proven per service before it is used.
 *
 * Proxied outbounds take their family from the outbound-level `targetStrategy` (Freedom uses
 * `sockopt.domainStrategy` instead): the core resolves the destination itself and hands the
 * Worker an address of that family. With no strategy the name goes through and the Worker
 * resolves it.
 */
/**
 * The US exit: a Durable Object in North America on the user's own Cloudflare account (the
 * app's «خروجی آمریکا برای جمنای»), reached through one of the user's own Cloudflare configs
 * -- same clean address, TLS and socket options, so whatever gets that config past the filter
 * gets this past it too. Google then sees a US address instead of the colo nearest the phone.
 * [outbound] is built by [com.mlmvpn.scanner.utils.XrayJsonGenerator.geminiExitOutboundVia].
 */
class UsExitRoute(private val outbound: JSONObject) : RouteProvider {
    override val id = ID
    override val kind = RouteKind.FOREIGN
    override val caps = Capabilities(udp = false, quotaLimited = true, noCloudflareDestinations = true)
    override val cost = 0.35
    override fun tag(family: FamilyPolicy) = TAG
    override fun outbounds() = listOf(JSONObject(outbound.toString()).put("tag", TAG))

    companion object {
        const val ID = "usexit"
        const val TAG = "mae-usx"
    }
}

class WorkerRoute(
    private val host: String,
    private val uuid: String,
    private val path: String = "/",
    /** Outbound to dial the Worker's edge through; null = directly (workers.dev is open on most networks). */
    private val dialVia: String? = null,
    /**
     * The central Worker has a Proxy IP (its `FALLBACK`): where `connect()` refuses a Cloudflare
     * address it goes through that, so Cloudflare-hosted destinations open too. MAE's own Worker had none.
     */
    reachesCloudflare: Boolean = false,
) : RouteProvider {
    override val id = ID
    override val kind = RouteKind.FOREIGN
    override val caps = Capabilities(udp = false, quotaLimited = true, noCloudflareDestinations = !reachesCloudflare, stableExit = false)
    override val cost = 0.3
    override val families = listOf(FamilyPolicy.V4_ONLY, FamilyPolicy.V6_ONLY, FamilyPolicy.BOTH)

    override fun outbounds() = families.map { f -> outbound(tag(f), strategyFor(f)) }

    override fun tag(family: FamilyPolicy) = when (family) {
        FamilyPolicy.V4_ONLY -> "mae-wk4"
        FamilyPolicy.V6_ONLY -> "mae-wk6"
        FamilyPolicy.BOTH -> "mae-wkd"
    }

    private fun strategyFor(f: FamilyPolicy) = when (f) {
        FamilyPolicy.V4_ONLY -> "ForceIPv4"
        FamilyPolicy.V6_ONLY -> "ForceIPv6"
        FamilyPolicy.BOTH -> null
    }

    private fun outbound(tag: String, strategy: String?) = JSONObject().apply {
        put("tag", tag)
        put("protocol", "vless")
        put("settings", JSONObject().apply {
            put("vnext", JSONArray().put(JSONObject()
                .put("address", host).put("port", 443)
                .put("users", JSONArray().put(JSONObject().put("id", uuid).put("encryption", "none")))))
        })
        // Outbound-level field. Xray ignores unknown JSON fields silently, so a misplaced strategy
        // would not fail to load -- it would just not apply. The egress proof catches that: a
        // V4_ONLY route whose echo shows an IPv6 address is recorded as not proven.
        if (strategy != null) put("targetStrategy", strategy)
        put("streamSettings", JSONObject()
            .put("network", "ws")
            .put("security", "tls")
            .put("tlsSettings", JSONObject().put("serverName", host).put("fingerprint", "chrome")
                .put("alpn", JSONArray().put("http/1.1")))
            .put("wsSettings", JSONObject().put("path", path).put("host", host))
            .apply { if (dialVia != null) put("sockopt", JSONObject().put("dialerProxy", dialVia)) })
    }

    companion object { const val ID = "worker" }
}
