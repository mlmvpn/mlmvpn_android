package com.mlmvpn.scanner.engines.flux.core.model

/**
 * FLUX's vocabulary. Pure Kotlin, no Android: everything in `flux/core` runs in the JVM unit tests.
 *
 * The user sees three things (country, IP mode, connect). Everything in here is what FLUX decides
 * on their behalf.
 */

/** What the user picked under «نسخه IP». */
enum class IpMode(val code: String) {
    V4("v4"), V6("v6"), BOTH("both");

    /** The families a candidate may use to reach its server or edge under this mode. */
    val families: List<Family> get() = when (this) {
        V4 -> listOf(Family.V4)
        V6 -> listOf(Family.V6)
        BOTH -> listOf(Family.V6, Family.V4)
    }

    companion object {
        fun of(code: String?): IpMode = values().firstOrNull { it.code == code } ?: BOTH
    }
}

/** One address family. */
enum class Family(val code: String) {
    V4("4"), V6("6");

    companion object {
        fun of(code: String?): Family? = values().firstOrNull { it.code == code }

        /** The family of a literal address, or null for a domain name. */
        fun ofLiteral(address: String): Family? = when {
            address.contains(':') -> V6
            address.isNotEmpty() && address.all { it.isDigit() || it == '.' } && address.count { it == '.' } == 3 -> V4
            else -> null
        }
    }
}

enum class Proto { VLESS, TROJAN, HY2 }

enum class Transport(val code: String) {
    TCP("tcp"), WS("ws"), GRPC("grpc"), XHTTP("xhttp"), HTTPUPGRADE("httpupgrade"), QUIC("quic");

    /** Carried by plain HTTP(S) requests: the kind of transport a CDN edge can front. */
    val cdnFrontable: Boolean get() = this == WS || this == GRPC || this == XHTTP || this == HTTPUPGRADE
}

enum class Security { NONE, TLS, REALITY }

/**
 * How the TLS ClientHello is shaped. Never a user setting: FLUX learns, per network, whether any
 * of them beats [OFF], and prefers [OFF] whenever it works.
 *
 * [ECH] and [PATTNG] are the two handshakes the Cloudflare Doctor proves on Iranian lines
 * ([com.mlmvpn.scanner.engines.cfnet.CfShape]): the real name encrypted under cloudflare-ech.com,
 * and PattNG's Go-TLS hello cut into pieces. ECH only means anything on a Cloudflare dial.
 */
enum class FragmentProfile(val code: String) {
    OFF("0"), CONSERVATIVE("c"), BALANCED("b"), AGGRESSIVE("a"), ECH("e"), PATTNG("p");

    companion object {
        fun of(code: String?): FragmentProfile = values().firstOrNull { it.code == code } ?: OFF
    }
}

/**
 * One parsed server. The credential lives in [credential] and nowhere else: [id] is derived from a
 * hash of it, [toString] leaves it out, and [FluxNode.redacted] is what logs print.
 */
class FluxNode(
    val id: String,
    val proto: Proto,
    val server: String,
    val port: Int,
    val credential: String,
    val transport: Transport,
    val security: Security,
    val sni: String = "",
    val host: String = "",
    val path: String = "",
    val alpn: List<String> = emptyList(),
    val fingerprint: String = "",
    val flow: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val serviceName: String = "",
    val xhttpMode: String = "",
    val obfs: String = "",
    val obfsPassword: String = "",
    /** The link's own remark. Untrusted: never used for the country, only for diagnostics. */
    val label: String = "",
    val sourceId: String = "",
    /**
     * The link's own `fm` (Xray finalmask JSON), as its publisher tested it. Used as this node's
     * fragment profile when FLUX decides fragmenting helps on a network -- never by default.
     */
    val finalmask: String? = null,
    /** The link's own `cs` (TLS cipher suites), passed through untouched. */
    val cipherSuites: String = "",
    /**
     * Certificate checks off (Hysteria2 only: VLESS/Trojan links like that are refused). Raced
     * only after every verified candidate has failed.
     */
    val insecure: Boolean = false,
    /**
     * The SHA-256 of the server's own certificate (64 hex digits), from the link's `pinSHA256` or
     * measured on first contact (Hysteria2 with a self-signed certificate). Given to the core as
     * `pinnedPeerCertSha256`: exactly that certificate is accepted, nothing else.
     */
    val certPin: String = "",
) {
    /**
     * A node a CDN edge can carry: an HTTP-shaped transport whose origin is named by Host/SNI, so
     * the address it dials can be any edge of that CDN without the origin noticing.
     */
    val edgeExpandable: Boolean
        get() = proto != Proto.HY2 && transport.cdnFrontable && security != Security.REALITY &&
            (host.isNotEmpty() || sni.isNotEmpty() || Family.ofLiteral(server) == null)

    /** The country the node's own name claims. A hint for ordering, never proof (see EgressVerifier). */
    val countryHint: String? by lazy { com.mlmvpn.scanner.engines.flux.core.country.CountryHint.of(label) }

    /** Carries UDP end to end (QUIC, calls, games). A CDN-fronted transport does not. */
    val carriesUdp: Boolean get() = proto == Proto.HY2 || !transport.cdnFrontable

    fun redacted(): String = "${proto.name.lowercase()}/${transport.code}/${security.name.lowercase()} ${id.take(10)}"

    override fun toString(): String = redacted()
    override fun equals(other: Any?): Boolean = other is FluxNode && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * One way to use a node: which address it dials (its own, or a CDN edge), over which family, with
 * which fragment profile, with or without mux. Each of these is raced and remembered on its own.
 */
data class FluxCandidate(
    val node: FluxNode,
    /** A CDN edge to dial instead of the node's own address, or null for the node's own. */
    val edge: String? = null,
    /** The family used to reach [dialAddress]. */
    val family: Family,
    val fragment: FragmentProfile = FragmentProfile.OFF,
    val mux: Boolean = false,
    /**
     * The address actually dialled: the edge, or the node's server already resolved to [family]
     * (FLUX always dials by IP, so the tunnel never needs a resolver of its own before it is up).
     */
    val dialAddress: String = edge ?: node.server,
) {
    /** Stable across sessions: the node, where it is dialled, and how. */
    val id: String get() = "${node.id}@${edge ?: "o"}/${family.code}${fragment.code}${if (mux) "m" else ""}"

    /** The route whose egress (exit country) this candidate shares: a node via one edge. */
    val egressKey: String get() = egressKey(node.id, edge)

    override fun toString(): String = "${node.redacted()} @${edge ?: "origin"} v${family.code} f=${fragment.code}${if (mux) " mux" else ""}"

    companion object {
        fun egressKey(nodeId: String, edge: String?) = "$nodeId@${edge ?: "o"}"
    }
}

/** Three-valued verdict: a network has not been asked yet is not the same as one that said no. */
enum class Tri(val code: String) {
    UNKNOWN("?"), YES("y"), NO("n");

    companion object {
        fun of(code: String?): Tri = values().firstOrNull { it.code == code } ?: UNKNOWN
        fun of(value: Boolean): Tri = if (value) YES else NO
    }
}

/**
 * What one network lets through, learned on it. The point of this is the Cloudflare question:
 * on many Iranian networks Cloudflare's edges are cut, and racing CDN-fronted nodes there only
 * wastes the user's first seconds. Measured once, it gates the race for every later connect.
 */
data class NetVerdict(
    val cfV4: Tri = Tri.UNKNOWN,
    val cfV6: Tri = Tri.UNKNOWN,
    /** The network has a working IPv6 path at all (a global address and a route). */
    val v6: Tri = Tri.UNKNOWN,
    /** UDP to the outside gets through (Hysteria2 can work). */
    val udp: Tri = Tri.UNKNOWN,
    val at: Long = 0L,
) {
    fun cf(family: Family): Tri = if (family == Family.V4) cfV4 else cfV6

    /** No family reaches Cloudflare: CDN-fronted candidates are left out of the race entirely. */
    val cloudflareCut: Boolean get() = cfV4 == Tri.NO && (cfV6 == Tri.NO || v6 == Tri.NO)

    fun stale(now: Long, ttlMs: Long = TTL_MS): Boolean = at == 0L || now - at > ttlMs

    companion object {
        /** Filtering changes by the hour in Iran; a verdict older than this is measured again. */
        const val TTL_MS = 30 * 60_000L
    }
}

/**
 * The exit as the outside world sees it, measured through the route, never read from the node's
 * label. Kept per node *and edge*: a Worker-based node exits from Cloudflare's own address near
 * whichever edge carried it, so a different edge can mean a different country.
 */
data class FluxEgressIdentity(
    val ipv4: String? = null,
    val ipv6: String? = null,
    val countryCode: String? = null,
    val asn: String? = null,
    val organization: String? = null,
    val verifiedAt: Long = 0L,
    /** 1.0 = two independent sources agreed; 0.6 = one source; 0 = sources disagreed. */
    val confidence: Double = 0.0,
) {
    fun valid(now: Long, ttlMs: Long = TTL_MS): Boolean = countryCode != null && confidence > 0.0 && now - verifiedAt <= ttlMs

    companion object {
        /** GeoIP databases move; a country proof is re-checked after this. */
        const val TTL_MS = 24 * 3600_000L
    }
}

/** Why a probe failed, kept per network: it decides what FLUX tries next there. */
enum class FailReason(val code: String) {
    DNS("dns"), TCP_TIMEOUT("tt"), TCP_REFUSED("tr"), RESET_AFTER_SNI("rs"), TLS_TIMEOUT("lt"),
    TLS_ERROR("le"), PROXY_HANDSHAKE("ph"), HTTP_FAILED("hf"), HTTP_TIMEOUT("ht"), COUNTRY_MISMATCH("cm"),
    OTHER("ot");

    companion object {
        fun of(code: String?): FailReason = values().firstOrNull { it.code == code } ?: OTHER
    }
}

/** The connection state the screen shows: short and true. */
sealed class FluxUiState {
    object Idle : FluxUiState()
    /** «در حال پیدا کردن بهترین مسیر…» */
    object Searching : FluxUiState()
    /** Shown only after a real request through the running tunnel succeeded. */
    data class Connected(val countryCode: String?, val family: Family, val latencyMs: Long?) : FluxUiState()
    data class Failed(val reason: FailureKind, val suggestion: String? = null) : FluxUiState()
}

enum class FailureKind {
    /** No source has given a node yet and nothing is cached. */
    NO_NODES,
    /** Nothing answered on this network. */
    NOTHING_WORKS,
    /** Routes work, but none exits in the chosen country. */
    NO_ROUTE_FOR_COUNTRY,
    /** The chosen family does not exist on this network (no IPv6 address or route). */
    FAMILY_UNAVAILABLE,
    /** The network has the family, but no server answered over it right now. */
    NO_ROUTE_FOR_FAMILY,
    /** The device is offline. */
    OFFLINE,
    /** Android refused the VPN (permission withdrawn, another always-on VPN). */
    VPN_REFUSED,
}
