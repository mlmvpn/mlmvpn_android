package com.mlmvpn.scanner.engines.scout

import org.json.JSONArray
import org.json.JSONObject

/**
 * MLM Scout's vocabulary: «on my internet, which of the app's ways really work, and which is best?»
 *
 * Everything here is a fact that was measured, or an honest "not measured": a null number is never
 * shown as zero, and [ScoutStatus.INCONCLUSIVE] is a real answer -- better than a wrong one.
 * Nothing identifying is kept: no IP address, no Wi-Fi name, no config content (see docs/SCOUT-PLAN.md).
 */

/** Where one method (or one variant of it) stands. See the plan, section 3-9. */
enum class ScoutStatus {
    WAITING, CHECKING,
    /** Real data went through it, and it held. */
    WORKS,
    /** It carried data, but slowly, unsteadily, or only partly (the filtered site did not open). */
    WEAK,
    FAILED,
    /** Needs an account, a config or a panel first -- never "broken". */
    NEEDS_SETUP,
    /** Its engine is a Store download that is not installed. */
    NOT_INSTALLED,
    /** The service behind it did not answer (the shared pool, the public lists) -- not the line's fault. */
    SERVICE_DOWN,
    /** Not for this line: an IPv6 variant on a line without IPv6. */
    NOT_APPLICABLE,
    /** Costly to test (a GitHub session): only with the user's say-so. */
    ON_DEMAND,
    CANCELLED,
    /** The test itself did not finish (a tester crashed, the app was closed). */
    INCONCLUSIVE,
    /** Listed and counted, but its tester comes in a later stage of Scout. */
    UPCOMING;

    val finished: Boolean get() = this != WAITING && this != CHECKING
    val healthy: Boolean get() = this == WORKS || this == WEAK

    companion object {
        fun of(s: String?): ScoutStatus = entries.firstOrNull { it.name == s } ?: INCONCLUSIVE
    }
}

/** How far the proof goes: real data through the method, or only a server that accepted the session. */
enum class Evidence { DATA, HANDSHAKE }

/**
 * The three kinds are never ranked against each other: a serverless unfilter config is fast because
 * it does not change the address at all, which is not what "best tunnel" means.
 */
enum class MethodKind { TUNNEL, UNFILTER, ANTISANCTION }

enum class Fam(val label: String) {
    V4("IPv4"), V6("IPv6"), AUTO("");

    companion object {
        fun of(s: String?): Fam = entries.firstOrNull { it.name == s } ?: AUTO
    }
}

/** The live step of a method under test. */
enum class Step {
    PREPARING, CONNECTING, MEASURING;

    companion object {
        fun of(s: String?): Step? = entries.firstOrNull { it.name == s }
    }
}

enum class Tri {
    YES, NO, UNKNOWN;

    companion object {
        fun of(s: String?): Tri? = entries.firstOrNull { it.name == s }
    }
}

/** One measured variant's numbers. Null means "not measured", never "zero". */
data class Numbers(
    /** From starting the engine to the first verified data. */
    val connectMs: Long? = null,
    /** Median of small HTTPS requests on one open connection, through the method. Not ICMP. */
    val latencyMs: Long? = null,
    val latencyP95Ms: Long? = null,
    val stabilityOk: Int? = null,
    val stabilityTotal: Int? = null,
    /** A short throughput sample (1 MB, 512 KB on mobile data), never a full speed test. */
    val mbps: Double? = null,
    val speedBytes: Int? = null,
    /** The line's own speed moved more than twofold during the speed round. */
    val speedApprox: Boolean = false,
    /** A filtered site (YouTube) opened through it. */
    val filtered: Tri = Tri.UNKNOWN,
    /** Where it comes out, as a two-letter code, asked through the method itself. */
    val exitCountry: String? = null,
    val speedAttempted: Boolean = false,
) {
    /** p95 − median: how far a request can wander from the usual. */
    val jitterMs: Long? get() = if (latencyMs != null && latencyP95Ms != null) (latencyP95Ms - latencyMs).coerceAtLeast(0) else null

    val stabilityRate: Double? get() =
        if (stabilityOk != null && stabilityTotal != null && stabilityTotal > 0) stabilityOk.toDouble() / stabilityTotal else null

    fun toJson(): JSONObject = JSONObject().apply {
        putOpt("c", connectMs); putOpt("l", latencyMs); putOpt("l95", latencyP95Ms)
        putOpt("so", stabilityOk); putOpt("st", stabilityTotal); putOpt("m", mbps); putOpt("sb", speedBytes)
        if (speedApprox) put("sa", true)
        if (speedAttempted) put("sd", true)
        put("f", filtered.name); putOpt("x", exitCountry)
    }

    companion object {
        fun from(o: JSONObject?): Numbers {
            if (o == null) return Numbers()
            return Numbers(
                connectMs = o.longOrNull("c"), latencyMs = o.longOrNull("l"), latencyP95Ms = o.longOrNull("l95"),
                stabilityOk = o.intOrNull("so"), stabilityTotal = o.intOrNull("st"), mbps = o.doubleOrNull("m"),
                speedBytes = o.intOrNull("sb"), speedApprox = o.optBoolean("sa"),
                filtered = Tri.of(o.optString("f")) ?: Tri.UNKNOWN, exitCountry = o.stringOrNull("x"),
                speedAttempted = o.optBoolean("sd"),
            )
        }
    }
}

/** What one variant (one mode, one IP family) of a method did. */
data class VariantResult(
    val id: String,
    val label: String,
    val fam: Fam = Fam.AUTO,
    val status: ScoutStatus,
    /** Why, in plain words, when it is not simply "works". */
    val reason: String? = null,
    /** Where it stopped, for a failure. */
    val failedAt: Step? = null,
    val numbers: Numbers = Numbers(),
    /** What it ran on inside, when that is worth saying ("obfs4", a server's country, a config's name). */
    val detail: String? = null,
    val at: Long = 0,
    /** Measured with the whole yardstick, not only swept for a yes/no. */
    val full: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("lb", label); put("fa", fam.name); put("s", status.name)
        putOpt("r", reason); putOpt("fs", failedAt?.name); put("n", numbers.toJson()); putOpt("d", detail)
        put("at", at); if (!full) put("sw", true)
    }

    companion object {
        fun from(o: JSONObject) = VariantResult(
            id = o.optString("id"), label = o.optString("lb"), fam = Fam.of(o.optString("fa")),
            status = ScoutStatus.of(o.optString("s")), reason = o.stringOrNull("r"), failedAt = Step.of(o.optString("fs")),
            numbers = Numbers.from(o.optJSONObject("n")), detail = o.stringOrNull("d"), at = o.optLong("at"),
            full = !o.optBoolean("sw"),
        )
    }
}

/** One method's standing in a run. Its status is derived from its variants by [ScoutRules.aggregate]. */
data class MethodResult(
    val id: String,
    val status: ScoutStatus = ScoutStatus.WAITING,
    val reason: String? = null,
    /** The live step while [status] is CHECKING, and what it is doing in words. */
    val step: Step? = null,
    val stepNote: String? = null,
    val variants: List<VariantResult> = emptyList(),
    /** Variants planned for this run that have no result yet. */
    val pending: Int = 0,
    val updatedAt: Long = 0,
) {
    /** The variant that speaks for the method: the best healthy one, else the furthest-going failure. */
    val best: VariantResult? get() = ScoutRules.representative(variants)

    /** The best outcome among this method's variants of one IP family; null when none was planned. */
    fun famStatus(f: Fam): ScoutStatus? =
        variants.filter { it.fam == f }.map { it.status }.minByOrNull { ScoutRules.rank(it) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("s", status.name); putOpt("r", reason)
        put("v", JSONArray(variants.map { it.toJson() })); if (pending > 0) put("p", pending); put("u", updatedAt)
    }

    companion object {
        fun from(o: JSONObject) = MethodResult(
            id = o.optString("id"), status = ScoutStatus.of(o.optString("s")), reason = o.stringOrNull("r"),
            variants = o.optJSONArray("v")?.let { a -> (0 until a.length()).map { VariantResult.from(a.getJSONObject(it)) } }.orEmpty(),
            pending = o.optInt("p"), updatedAt = o.optLong("u"),
        )
    }
}

enum class FamState {
    NONE, BROKEN, WORKS;

    companion object {
        fun of(s: String?): FamState? = entries.firstOrNull { it.name == s }
    }
}

/**
 * «اینترنت شما»: what the line itself can do. A null field is "not measured yet"; a measured
 * unknown is [Tri.UNKNOWN] -- the two are kept apart so the card can fill in honestly.
 *
 * This is also the read-only "line card" other engines (MAE, FLUX, the tile's ladder) may use later.
 */
data class LineFacts(
    /** "wifi" | "cell" | "ethernet" | "other" */
    val netType: String? = null,
    /** Mobile operator (MCI / MTN / RIGHTEL / OTHER); never an SSID. */
    val operator: String? = null,
    val metered: Boolean? = null,
    val validated: Boolean? = null,
    val captive: Boolean? = null,
    val privateDns: Boolean? = null,
    val intl: Tri? = null,
    val domestic: Tri? = null,
    val v4: FamState? = null,
    val v6: FamState? = null,
    /** Median TCP handshake on each family to the same servers, taken in turns. */
    val v4Ms: Long? = null,
    val v6Ms: Long? = null,
    val tcpOk: Int? = null,
    val tcpTotal: Int? = null,
    val tcpMs: Long? = null,
    val udp: Tri? = null,
    val udpWhy: String? = null,
    val quic: Tri? = null,
    val dnsMs: Long? = null,
    val dnsTampered: Tri? = null,
    val doh: Tri? = null,
    val tlsMs: Long? = null,
    val sniFilter: Tri? = null,
    val http: Tri? = null,
    val httpTampered: Tri? = null,
    val https: Tri? = null,
    /** Median TCP handshake to Cloudflare -- called that on screen, never "ping". */
    val rttMs: Long? = null,
    /** ICMP echo, only when it really answered. */
    val icmpMs: Long? = null,
    val jitterMs: Double? = null,
    val lossPct: Double? = null,
    val udpSamples: Int? = null,
    val mbpsStart: Double? = null,
    val mbpsEnd: Double? = null,
    val mtu: Int? = null,
    val mtuTried: Boolean = false,
    val clockSkewS: Long? = null,
    /** The filtering type proven on this network by data (cfnet), never a guess. */
    val family: String? = null,
) {
    val mbps: Double? get() = mbpsStart ?: mbpsEnd

    fun toJson(): JSONObject = JSONObject().apply {
        putOpt("nt", netType); putOpt("op", operator); putOpt("me", metered); putOpt("va", validated); putOpt("ca", captive)
        putOpt("pd", privateDns); putOpt("in", intl?.name); putOpt("do", domestic?.name); putOpt("v4", v4?.name); putOpt("v6", v6?.name)
        putOpt("v4m", v4Ms); putOpt("v6m", v6Ms); putOpt("to", tcpOk); putOpt("tt", tcpTotal); putOpt("tm", tcpMs)
        putOpt("u", udp?.name); putOpt("uw", udpWhy); putOpt("q", quic?.name); putOpt("dm", dnsMs); putOpt("dt", dnsTampered?.name)
        putOpt("dh", doh?.name); putOpt("tl", tlsMs); putOpt("sf", sniFilter?.name); putOpt("h", http?.name)
        putOpt("ht", httpTampered?.name); putOpt("hs", https?.name); putOpt("r", rttMs); putOpt("ic", icmpMs)
        putOpt("j", jitterMs); putOpt("lo", lossPct); putOpt("us", udpSamples); putOpt("ms", mbpsStart); putOpt("mE", mbpsEnd)
        putOpt("mt", mtu); if (mtuTried) put("mtt", true); putOpt("cs", clockSkewS); putOpt("fm", family)
    }

    companion object {
        fun from(o: JSONObject?): LineFacts {
            if (o == null) return LineFacts()
            return LineFacts(
                netType = o.stringOrNull("nt"), operator = o.stringOrNull("op"), metered = o.boolOrNull("me"),
                validated = o.boolOrNull("va"), captive = o.boolOrNull("ca"), privateDns = o.boolOrNull("pd"),
                intl = Tri.of(o.stringOrNull("in")), domestic = Tri.of(o.stringOrNull("do")),
                v4 = FamState.of(o.stringOrNull("v4")), v6 = FamState.of(o.stringOrNull("v6")),
                v4Ms = o.longOrNull("v4m"), v6Ms = o.longOrNull("v6m"), tcpOk = o.intOrNull("to"), tcpTotal = o.intOrNull("tt"),
                tcpMs = o.longOrNull("tm"), udp = Tri.of(o.stringOrNull("u")), udpWhy = o.stringOrNull("uw"),
                quic = Tri.of(o.stringOrNull("q")), dnsMs = o.longOrNull("dm"), dnsTampered = Tri.of(o.stringOrNull("dt")),
                doh = Tri.of(o.stringOrNull("dh")), tlsMs = o.longOrNull("tl"), sniFilter = Tri.of(o.stringOrNull("sf")),
                http = Tri.of(o.stringOrNull("h")), httpTampered = Tri.of(o.stringOrNull("ht")), https = Tri.of(o.stringOrNull("hs")),
                rttMs = o.longOrNull("r"), icmpMs = o.longOrNull("ic"), jitterMs = o.doubleOrNull("j"), lossPct = o.doubleOrNull("lo"),
                udpSamples = o.intOrNull("us"), mbpsStart = o.doubleOrNull("ms"), mbpsEnd = o.doubleOrNull("mE"),
                mtu = o.intOrNull("mt"), mtuTried = o.optBoolean("mtt"), clockSkewS = o.longOrNull("cs"), family = o.stringOrNull("fm"),
            )
        }
    }
}

enum class Pass {
    QUICK, SPEED, DEEP, DONE;

    companion object {
        fun of(s: String?): Pass = entries.firstOrNull { it.name == s } ?: DONE
    }
}

/** Why a run ended. Anything but [DONE] means the results are partial, and the summary says so. */
enum class EndReason {
    DONE, STOPPED, NETWORK_CHANGED, LINE_DOWN, VPN_STARTED, OTHER_SCAN,
    /** The app was closed or killed mid-run; found on the next launch. */
    INTERRUPTED;

    companion object {
        fun of(s: String?): EndReason? = entries.firstOrNull { it.name == s }
    }
}

/** The app's own connection that was paused for the run, so one button can bring it back. */
data class PausedVpn(val homeId: String?, val label: String) {
    fun toJson(): JSONObject = JSONObject().put("label", label).apply { putOpt("home", homeId) }

    companion object {
        fun from(o: JSONObject?): PausedVpn? = o?.let { PausedVpn(it.stringOrNull("home"), it.optString("label")) }
    }
}

/** One Scout run on one network. Never mixed with another network's. */
data class ScoutRun(
    val id: String,
    /** The anonymous network key (CfNetEnv's hash) the run belongs to. */
    val netKey: String,
    /** "Wi-Fi" or the operator -- what the user recognises, never an SSID. */
    val netLabel: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val end: EndReason? = null,
    val pass: Pass = Pass.QUICK,
    val line: LineFacts = LineFacts(),
    val methods: Map<String, MethodResult> = emptyMap(),
    /** What the app actually sent and received while the run lasted. */
    val dataBytes: Long? = null,
    val paused: PausedVpn? = null,
) {
    val partial: Boolean get() = end != null && end != EndReason.DONE

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("net", netKey); put("nl", netLabel); put("st", startedAt); putOpt("fi", finishedAt)
        putOpt("end", end?.name); put("pass", pass.name); put("line", line.toJson())
        put("m", JSONArray(methods.values.map { it.toJson() })); putOpt("data", dataBytes); putOpt("pz", paused?.toJson())
    }

    companion object {
        fun from(o: JSONObject): ScoutRun = ScoutRun(
            id = o.optString("id"), netKey = o.optString("net"), netLabel = o.optString("nl"), startedAt = o.optLong("st"),
            finishedAt = o.longOrNull("fi"), end = EndReason.of(o.stringOrNull("end")), pass = Pass.of(o.optString("pass")),
            line = LineFacts.from(o.optJSONObject("line")),
            methods = o.optJSONArray("m")?.let { a ->
                (0 until a.length()).map { MethodResult.from(a.getJSONObject(it)) }.associateByTo(LinkedHashMap()) { it.id }
            } ?: LinkedHashMap(),
            dataBytes = o.longOrNull("data"), paused = PausedVpn.from(o.optJSONObject("pz")),
        )
    }
}

// ---- small JSON helpers: absent and null are both "not measured" ----------------------------------

internal fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
internal fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
internal fun JSONObject.doubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
internal fun JSONObject.boolOrNull(k: String): Boolean? = if (has(k) && !isNull(k)) optBoolean(k) else null
internal fun JSONObject.stringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null

/**
 * One independently tested mode of a method: Tor with obfs4, FLUX over IPv6, one cloud panel.
 *
 * [quick] variants run in the quick pass; the rest in the full one. [notApplicable] is set when the
 * line rules it out before any test (IPv6 on a line without it): «مناسب این خط نیست», not «خراب».
 */
data class Variant(
    val id: String,
    val label: String,
    val fam: Fam = Fam.AUTO,
    val quick: Boolean = false,
    val budgetMs: Long = 60_000,
    val payload: Any? = null,
    val notApplicable: String? = null,
    /** Known before any test: its account or panel is missing. */
    val needsSetup: String? = null,
    /**
     * Another variant of the same method that answers this one's question when it works: then this
     * one is not run. (MASQUE over HTTP/2 to a country, once HTTP/3 to it carried data.)
     */
    val unneededIf: String? = null,
)
