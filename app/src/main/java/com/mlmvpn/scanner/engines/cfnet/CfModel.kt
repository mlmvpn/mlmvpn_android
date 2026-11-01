package com.mlmvpn.scanner.engines.cfnet

/*
 * The data model of the Cloudflare connectivity strategy engine.
 *
 * Everything in this file is pure Kotlin (no android.*, no okhttp, no core) so it compiles and runs
 * under the standalone JVM test runner (`scripts/test-cfnet.ps1`). The Android glue -- the real
 * validators, the network lookup, the OkHttp/Xray wiring -- lives in separate files that depend on
 * these types but are not pulled into the pure test build.
 *
 * A "strategy" is one concrete, testable way to carry a Cloudflare operation on the current network.
 * It is deliberately NOT a free-form bag of flags: it is either a CONTROL route (how the app's own
 * HTTPS reaches api.cloudflare.com / a Worker) or a DATA shape (how an Xray/Aether tunnel is dialled
 * to a Cloudflare-fronted server). The engine picks one per stage, proves it with real data, saves
 * the winner per network, reuses it on a fast path and heals when it stops working.
 */

/** The six internal stages. The user sees five steps; ACCOUNT and IDENTITY share step 1. */
enum class CfStage(val key: String, val userStep: Int, val layer: Layer) {
    ACCOUNT("account", 1, Layer.CONTROL),
    IDENTITY("identity", 1, Layer.CONTROL),
    DEPLOY("deploy", 2, Layer.CONTROL),
    CONFIG("config", 3, Layer.CONTROL),
    SCAN("scan", 4, Layer.DATA),
    CONNECT("connect", 5, Layer.DATA);

    companion object {
        fun of(key: String?): CfStage? = entries.find { it.key == key }
        /** The internal stages behind a user-facing step 1..5, in run order. */
        fun ofStep(step: Int): List<CfStage> = entries.filter { it.userStep == step }
    }
}

/** CONTROL = the app's own calls to Cloudflare (ACCOUNT/IDENTITY/DEPLOY/CONFIG). DATA = a tunnel (SCAN/CONNECT). */
enum class Layer { CONTROL, DATA }

/**
 * Where a network exchange stopped, taken from the wire client's own phase -- never by parsing an
 * exception message, which is unstable across devices and locales.
 */
enum class WirePhase { START, DNS, CONNECT, SECURE_CONNECT, REQUEST, RESPONSE, DONE }

/**
 * A structured failure class. The engine ranks and heals on these, and the report groups by them.
 * A success is the absence of a failure, so this is only ever set on a non-ok result.
 */
enum class FailureClass {
    DNS_FAILURE, DNS_POISONED, IP_UNREACHABLE, TCP_TIMEOUT, TCP_RESET,
    TLS_FAILURE, TLS_CERT, ECH_FAILURE, PROXY_FAILURE, PREREQ_MISSING,
    AUTH_FAILURE, RATE_LIMITED, API_FAILURE, CONFIG_PARSE_FAILURE, SCAN_FAILURE,
    TUNNEL_FAILURE, DATA_TRANSFER_FAILURE, TIMEOUT, CANCELLED, UNKNOWN,
    /**
     * An HTTP server on the path refused the request without an API verdict: a 401/403 that carries
     * no Cloudflare error code (a WAF / "1020 access denied" page for this route's exit address, a
     * captive portal). Unlike [AUTH_FAILURE] it says nothing about the credential -- another route
     * can well succeed -- so it counts against the route.
     */
    HTTP_BLOCKED,
    /**
     * The account is not set up for this operation (no workers.dev subdomain to serve a test worker
     * from, say). Cloudflare answered; no route can change it; the user has to finish the setup.
     */
    ACCOUNT_SETUP;

    /** A failure of the network path (worth trying another route) rather than of the account/data itself. */
    val isNetwork: Boolean
        get() = this in setOf(DNS_FAILURE, DNS_POISONED, IP_UNREACHABLE, TCP_TIMEOUT, TCP_RESET, TLS_FAILURE, TLS_CERT, ECH_FAILURE, PROXY_FAILURE, TUNNEL_FAILURE, TIMEOUT, HTTP_BLOCKED)

    /**
     * Whether this outcome is evidence AGAINST the strategy that produced it. An authoritative answer
     * from Cloudflare (a rejected credential, a rate limit, an API error, an unusable subscription)
     * proves the route carried the request, so it must not lower that route's confidence. A missing
     * prerequisite or a cancellation says nothing about the strategy at all.
     */
    val countsAgainstStrategy: Boolean
        get() = this !in setOf(AUTH_FAILURE, RATE_LIMITED, API_FAILURE, CONFIG_PARSE_FAILURE, PREREQ_MISSING, CANCELLED, ACCOUNT_SETUP)

    /**
     * Whether no other strategy can change the stage's verdict: the credential was rejected, the
     * account is not set up for it, or the content itself is unusable. Discovery stops here instead
     * of walking every route to the same answer, and the stage reports this verdict rather than
     * "nothing worked on this network".
     */
    val endsStage: Boolean
        get() = this == AUTH_FAILURE || this == CONFIG_PARSE_FAILURE || this == ACCOUNT_SETUP
}

/** A prerequisite a strategy needs before it can even be tried on a stage. */
enum class Prereq { ACCOUNT, WORKER_DEPLOYED, IPV6_ROUTE, OWN_CONFIG, WARP_SUPPORTED, TOR, PSIPHON, ACTIVE_PROXY, XHTTP_CONFIG }

// ---- CONTROL: how the app's own HTTPS reaches Cloudflare -----------------------------------------

/**
 * How the destination address is found for a CONTROL exchange. [range] names a fixed Cloudflare
 * range group (the Doctor's EdgeGroups) for the per-range sources: reachability in Iran is per
 * address range and per region, so "a live address in THIS range" is its own strategy.
 */
enum class AddressSource(val key: String, val range: String = "") {
    CURRENT_TUNNEL("proxy"),     // HTTPS through the app's existing local SOCKS tunnel
    SYSTEM("sys"),              // the phone's resolver -- what every app does
    WORKER_ROUTE("wroute"),     // WorkerRoute's health-checked, healthy-first answer
    CF_EDGE("cfedge"),          // an edge proven on this network by CfEdge
    SCANNER_CLEAN("clean"),     // a clean IP the scanner validated here
    DOH_GOOGLE("doh8"),         // DoH JSON to 8.8.8.8
    DOH_ADGUARD("doh94"),       // DoH JSON to 94.140.14.14 (a second, non-Google resolver path)
    UDP_GOOGLE("udp8"),         // plain UDP DNS to 8.8.8.8
    IPV6_EDGE("v6edge"),        // a Cloudflare IPv6 edge
    RANGE_172_64("r172_64", "172_64"),   // a live address in 172.64.0.0/15
    RANGE_104_16("r104_16", "104_16"),   // a live address in 104.16.0.0/14
    RANGE_188_114("r188_114", "188_114"), // a live address in 188.114.96.0/22
}

/** How the first TLS flight is shaped on a CONTROL exchange. */
enum class WireShape(val key: String) {
    NONE("plain"),              // ordinary TLS, ClientHello in one write
    SPLIT("split"),             // 16-byte TCP segments, 10ms apart (the Doctor's F6)
    F3("f3"),                   // 5 + single-byte (tcp-fragment-tls)
    F4("f4"),                   // one cut in the middle of the server name
    F8("f8"),                   // TLS record fragmentation: the ClientHello in two TLS records
    F12("f12"),                 // 75-byte chunks, 15ms
    F14("f14"),                 // 1-byte chunks, 1ms
    TLS12("tls12"),             // TLS 1.2 only
    TLS13("tls13"),             // TLS 1.3 only
    SNI_MIXED("snimix"),        // the server name in mIxEd case (name filters that match case-sensitively)
}

/** For IDENTITY: which carrier reaches the WARP API. */
enum class Carrier(val key: String) {
    DIRECT("direct"),           // straight to api.cloudflareclient.com
    WARP_FRONT("front"),        // WarpApiFront: random 141.101.113.x edge + split ClientHello
    WORKER_RELAY("relay"),      // through the user's deployed warp_id_relay Worker
}

// ---- DATA: how an Xray/Aether tunnel is dialled --------------------------------------------------

/** Which family the tunnel's address uses. */
enum class Family(val key: String) { V4("v4"), V6("v6"), DUAL("dual") }

/** Where the tunnel's dialled address comes from. */
enum class AddressPolicy(val key: String) {
    RESOLVED("resolved"),       // the config's own address / its SNI resolved
    CLEAN_IP("clean"),          // a scanner-validated clean IP, original name pinned as SNI/host
    CF_EDGE("cfedge"),          // a CfEdge proven edge, name pinned
    IPV6_TWIN("v6twin"),        // the Cloudflare IPv6 twin (CfEdgeHeal)
}

/** How the tunnel's TLS is shaped. The engine applies this instead of the old always-on CfUnfilter. */
enum class TunnelShape(val key: String) {
    AS_IS("asis"),              // the config's own handshake, untouched -- the baseline every other shape is measured against
    CHROME("chrome"),           // uTLS chrome, no fragment
    FRAG_FP("fragfp"),          // unsafe fp + semi-python ciphers + finalmask tlshello-0-len (PattNG's method)
    FRAG_CHROME("fragchrome"),  // uTLS chrome + the same tlshello finalmask (uTLS kept on)
    ECH("ech"),                 // fp chrome, echConfigList cloudflare-ech.com+udp://1.1.1.1, no finalmask (the screenshot)
    // Fingerprint-only: each a different uTLS ClientHello the core ships (checked in libgojni.so).
    FIREFOX("firefox"), SAFARI("safari"), RANDOMIZED("randomized"),
    IOS("ios"), EDGE("edge"), ANDROID("android"),
    // XHTTP transport tuning -- only for a config whose server already speaks XHTTP (the transport
    // itself is the server's choice, not ours). ALPN decides the HTTP version Xray dials:
    XHTTP_H2("xhttp_h2"),               // HTTP/2 (ALPN h2), packet-up: the CDN-safe XHTTP
    XHTTP_H2_STREAM("xhttp_h2_stream"), // HTTP/2, stream-up: one long upload -- only where the CDN streams request bodies
    XHTTP_H11("xhttp_h11"),             // HTTP/1.1 (ALPN http/1.1), packet-up: where HTTP/2 is throttled
    XHTTP_H2_FRAG("xhttp_h2_frag"),     // HTTP/2 packet-up with the tlshello fragmentation
    MASQUE_H2("masque_h2"),     // Aether MASQUE over HTTP/2 (not an Xray TLS shape)
    WARP("warp"),               // WARP WireGuard directly
    CHAIN_TOR("chain_tor"),     // WARP outer, Tor inner
    CHAIN_PSIPHON("chain_psiphon"), // WARP outer, Psiphon inner
}

/** The concrete inputs a CONTROL strategy applies. */
data class RouteSpec(
    val address: AddressSource,
    val shape: WireShape = WireShape.NONE,
    val carrier: Carrier = Carrier.DIRECT,
)

/** The concrete inputs a DATA strategy applies. */
data class ShapeSpec(
    val shape: TunnelShape,
    val family: Family = Family.V4,
    val addressPolicy: AddressPolicy = AddressPolicy.RESOLVED,
    /** Alternate port to try instead of the config's own, or 0 to keep it. */
    val port: Int = 0,
) {
    /** Only meaningful for a config whose transport is XHTTP. */
    val needsXhttp: Boolean get() = shape in XHTTP_SHAPES

    companion object {
        val XHTTP_SHAPES = setOf(TunnelShape.XHTTP_H2, TunnelShape.XHTTP_H2_STREAM, TunnelShape.XHTTP_H11, TunnelShape.XHTTP_H2_FRAG)
        /** The transport names that are XHTTP ("splithttp" is its older name). */
        val XHTTP_NETWORKS = setOf("xhttp", "splithttp")
    }
}

/** One member of the catalog: metadata + the concrete spec. The validator is chosen by stage/layer in the engine. */
data class Strategy(
    val id: String,
    val name: String,
    val stages: Set<CfStage>,
    val layer: Layer,
    /** A static hint 0..1 (how likely to work before any learning); the store's confidence refines it. */
    val prior: Double,
    /** Relative cost 1..10 (time/battery/complexity); cheaper is tried first when confidence ties. */
    val cost: Int,
    val timeoutMs: Long,
    val retries: Int,
    val prereqs: Set<Prereq> = emptySet(),
    val route: RouteSpec? = null,
    val data: ShapeSpec? = null,
) {
    init {
        require((layer == Layer.CONTROL) == (route != null)) { "CONTROL strategy must carry a RouteSpec and nothing else" }
        require((layer == Layer.DATA) == (data != null)) { "DATA strategy must carry a ShapeSpec and nothing else" }
    }
}

/** The outcome of running one strategy's validator for one stage. Never carries a secret. */
data class ValidationResult(
    val ok: Boolean,
    val failure: FailureClass? = null,
    val latencyMs: Long = 0,
    /** A short, privacy-safe note for the report ("http=200 success=true", "down=3MB up=1MB"). */
    val detail: String = "",
    val phase: WirePhase? = null,
) {
    init { require(ok == (failure == null)) { "a result is ok iff it has no failure class" } }

    companion object {
        fun pass(latencyMs: Long, detail: String = "", phase: WirePhase? = WirePhase.DONE) =
            ValidationResult(true, null, latencyMs, detail, phase)
        fun fail(failure: FailureClass, latencyMs: Long = 0, detail: String = "", phase: WirePhase? = null) =
            ValidationResult(false, failure, latencyMs, detail, phase)
    }
}
