package com.mlmvpn.scanner.engines.cfnet

/**
 * Turns the structured facts of a failed exchange -- the phase it reached, the HTTP status, the
 * Cloudflare error codes, and whether it timed out or was cancelled -- into a [FailureClass].
 *
 * Pure and deterministic: it never looks at an exception message (those differ by device, OEM TLS
 * stack and locale, so a text match is a latent bug). The caller is responsible for turning its
 * own world into these inputs:
 *  - a CONTROL exchange takes the furthest [WirePhase] from the Doctor's WayClient result;
 *  - a DATA (tunnel) check reports TUNNEL/DATA_TRANSFER itself.
 */
object FailureClassifier {

    /**
     * @param reached the furthest phase the exchange got to before it failed.
     * @param http the HTTP status if a response line was read, else 0.
     * @param cfErrors Cloudflare's own error codes from a JSON `errors` array (may be empty).
     * @param timedOut the exchange hit its deadline.
     * @param cancelled the run was cancelled (Stop, or a better strategy already won).
     * @param dnsPoisoned the resolver returned a bogon/private address for a public name.
     */
    fun classify(
        reached: WirePhase,
        http: Int = 0,
        cfErrors: List<Int> = emptyList(),
        timedOut: Boolean = false,
        cancelled: Boolean = false,
        dnsPoisoned: Boolean = false,
    ): FailureClass {
        if (cancelled) return FailureClass.CANCELLED

        // Cloudflare answered: the route works; the verdict is about the account/data, not the path.
        if (http in 200..599 || cfErrors.isNotEmpty()) {
            authOrApi(http, cfErrors)?.let { return it }
        }

        return when (reached) {
            WirePhase.START, WirePhase.DNS ->
                if (dnsPoisoned) FailureClass.DNS_POISONED else FailureClass.DNS_FAILURE
            WirePhase.CONNECT ->
                if (timedOut) FailureClass.TCP_TIMEOUT else FailureClass.TCP_RESET
            WirePhase.SECURE_CONNECT -> FailureClass.TLS_FAILURE
            // The request went out over a finished handshake and no answer came back: the
            // "silent after TLS" filtering, or a reset. Never an API verdict -- nothing answered.
            WirePhase.REQUEST, WirePhase.RESPONSE ->
                if (timedOut) FailureClass.TIMEOUT else FailureClass.TCP_RESET
            WirePhase.DONE ->
                // Reached DONE but still a failure means the body/validation was wrong.
                authOrApi(http, cfErrors) ?: FailureClass.API_FAILURE
        }
    }

    /**
     * Cloudflare's own verdict from the status + error codes, or null when it did not answer with one.
     *
     * A 401/403 is a CREDENTIAL failure only when it carries Cloudflare's own error codes. A bare
     * 403 is what a WAF rule, a "1020 access denied" page for this route's exit address or a captive
     * portal returns, and blaming the user's token for that sent people to re-create a token that was
     * fine -- it is [FailureClass.HTTP_BLOCKED], a property of the route.
     */
    fun authOrApi(http: Int, cfErrors: List<Int>): FailureClass? = when {
        http == 429 || 1015 in cfErrors || 971 in cfErrors -> FailureClass.RATE_LIMITED
        1000 in cfErrors -> FailureClass.AUTH_FAILURE          // invalid token
        9103 in cfErrors || 9106 in cfErrors || 9107 in cfErrors -> FailureClass.AUTH_FAILURE  // key/email wrong
        6003 in cfErrors || 6111 in cfErrors -> FailureClass.AUTH_FAILURE  // malformed auth header
        9109 in cfErrors -> FailureClass.AUTH_FAILURE          // expired / ip restricted
        10000 in cfErrors -> FailureClass.AUTH_FAILURE         // no permission
        (http == 401 || http == 403) && cfErrors.isNotEmpty() -> FailureClass.AUTH_FAILURE
        http == 401 || http == 403 -> FailureClass.HTTP_BLOCKED
        http in 500..599 -> FailureClass.API_FAILURE
        http in 400..499 -> FailureClass.API_FAILURE
        else -> null
    }

    /**
     * A CONTROL exchange that got no HTTP answer, from the wire client's own phase name (WayClient's
     * dns / tcp / tls / send / wait / body, the edge pickers' `*.none`, the tunnel route's `proxy.*`).
     * [timedOut] is whether it ran to its deadline -- the wire client closes the socket then, so the
     * phase alone cannot tell a stall from a reset.
     */
    fun ofWirePhase(phase: String, timedOut: Boolean = false): FailureClass = when {
        phase.startsWith("proxy") -> FailureClass.PROXY_FAILURE
        phase == "dns.bogon" -> FailureClass.DNS_POISONED
        phase.startsWith("dns") -> FailureClass.DNS_FAILURE
        // No address for this source at all (no proven edge, no live address in the range, no socket).
        phase.endsWith(".none") || phase == "socket" -> FailureClass.IP_UNREACHABLE
        phase == "tcp" -> if (timedOut) FailureClass.TCP_TIMEOUT else FailureClass.TCP_RESET
        phase == "tls.cert" -> FailureClass.TLS_CERT
        phase.startsWith("tls") -> FailureClass.TLS_FAILURE
        phase == "send" || phase == "wait" || phase == "body" -> if (timedOut) FailureClass.TIMEOUT else FailureClass.TCP_RESET
        else -> FailureClass.UNKNOWN
    }

    /** The [WirePhase] a wire phase name stands for, for the report. */
    fun wirePhaseOf(phase: String): WirePhase = when {
        phase.startsWith("dns") -> WirePhase.DNS
        phase == "tcp" || phase == "socket" || phase.endsWith(".none") -> WirePhase.CONNECT
        phase.startsWith("tls") -> WirePhase.SECURE_CONNECT
        phase == "send" || phase == "wait" -> WirePhase.REQUEST
        phase == "body" -> WirePhase.RESPONSE
        phase == "ok" -> WirePhase.DONE
        else -> WirePhase.START
    }

    /** Cloudflare error codes from a response body, if it is a JSON object with an `errors` array. */
    fun cfErrorsOf(body: String?): List<Int> {
        if (body.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = org.json.JSONObject(body).optJSONArray("errors") ?: return emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("code")?.takeIf { c -> c > 0 } }.take(6)
        }.getOrDefault(emptyList())
    }
}
