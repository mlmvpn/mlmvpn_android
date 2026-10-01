package com.mlmvpn.scanner.engines.cfdoctor

import org.json.JSONObject

/** Export boundary. Never put URLs, response strings, headers, or exception messages in this model. */
data class Evidence(
    val id: String, val route: String,
    val code: String = "unknown", val http: Int = 0,
    val elapsedMs: Long = 0, val atMs: Long = 0,
    val errors: List<Int> = emptyList(),
    val numbers: Map<String, Long> = emptyMap(),
    val target: String = "",
    val tags: Map<String,String> = emptyMap(),
) {
    val ok: Boolean get() = code == "ok"
    companion object {
        fun http(id: String, route: String, status: Int, headers: Map<String, String>, body: String,
                 privateEndpoint: Boolean, elapsedMs: Long = 0): Evidence {
            val errors = runCatching {
                val a = JSONObject(body).optJSONArray("errors")
                (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it)?.optInt("code")?.takeIf { n -> n in 1..99999 } }.take(6)
            }.getOrDefault(emptyList())
            // Only signatures, never matched content. Applies to public responses too: a public
            // endpoint can echo the client's address, cookies or an injected credential.
            val block = body.contains("10.10.34.")
            val tags=buildMap {
                headers.entries.firstOrNull { it.key.equals("cf-ray",true) }?.value?.substringAfterLast('-')
                    ?.takeIf { it.matches(Regex("[A-Z]{3}")) }?.let { put("colo",it) }
                headers.entries.firstOrNull { it.key.equals("content-type",true) }?.value?.substringBefore(';')?.lowercase()
                    ?.takeIf { it in setOf("application/json","text/plain","text/html","application/dns-message","application/octet-stream") }?.let { put("type",it) }
                if(!privateEndpoint) {
                    val trace=body.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
                    for((key,value) in trace) if(safeTag(key,value)) put(key,value)
                }
            }
            return Evidence(id, route, if (block) "http_blockpage" else if (status in 200..299) "ok" else "http_status",
                status, elapsedMs, errors = errors, numbers = buildMap {
                    put("bytes", body.toByteArray().size.toLong())
                    put("private", if (privateEndpoint) 1L else 0L)
                    put("cf", if (headers.entries.any { it.key.equals("server", true) && it.value.equals("cloudflare", true) }) 1L else 0L)
                }, tags=tags)
        }
        fun failure(id: String, route: String, phase: String, error: Throwable, ms: Long = 0): Evidence {
            if(generateSequence(error) { it.cause }.take(8).any { it is java.security.cert.CertificateException || it is javax.net.ssl.SSLPeerUnverifiedException })
                return Evidence(id,route,"cert_unexpected",elapsedMs=ms)
            val kind = when (error) {
                is java.net.UnknownHostException -> "nxdomain"
                is java.net.SocketTimeoutException -> "timeout"
                is java.net.ConnectException -> "refused"
                is java.io.EOFException -> "eof"
                is javax.net.ssl.SSLHandshakeException -> "alert"
                is java.net.SocketException -> "reset"
                else -> "error"
            }
            return Evidence(id, route, "${phase}_$kind", elapsedMs = ms)
        }
        fun safeTag(key: String,value: String): Boolean = when(key) {
            "colo" -> value.matches(Regex("[A-Z]{3}"))
            "loc" -> value.matches(Regex("[A-Z]{2}"))
            "http" -> value in setOf("http/1.1","http/2","http/3","h2","h3")
            "tls" -> value in setOf("TLSv1.2","TLSv1.3","off")
            "sni" -> value in setOf("plaintext","encrypted","off")
            "warp" -> value in setOf("on","off","plus")
            "kex" -> value in setOf("X25519","X25519MLKEM768","X25519Kyber768Draft00","P-256","P-384")
            "type" -> value in setOf("application/json","text/plain","text/html","application/dns-message","application/octet-stream")
            else -> false
        }
    }
}

enum class Verdict { PASS, DEGRADED, FAIL, SKIPPED }
data class StageResult(val verdict: Verdict, val layer: String, val evidence: List<String> = emptyList())

object StageVerdict {
    /**
     * ST1 from the account check's own conclusions (one terminal row per route, see St1RealPath).
     * The app's real route decides; the alternative routes (fixed IP, DoH address) only say
     * whether a network failure on the real route can be routed around.
     */
    fun account(rows: List<Evidence>, hasCredential: Boolean): StageResult {
        if (!hasCredential) return StageResult(Verdict.SKIPPED, "cf.auth.not_tested")
        val r = rows.filter { it.id == "ST1" && it.numbers["terminal"] == 1L }
        if (r.isEmpty()) return StageResult(Verdict.SKIPPED, "not_run")
        // One verdict per account (routes are `real.acc1`, `doh.acc1`, ...), then all of them.
        val perAccount = r.groupBy { it.route.substringAfter('.', "acc") }.values.map { one(it) }
        val bad = perAccount.filter { it.verdict != Verdict.PASS }
        if (bad.isEmpty()) return StageResult(Verdict.PASS, "ok", r.map { it.route })
        val worst = bad.minByOrNull { if (it.verdict == Verdict.FAIL) 0 else 1 }!!
        // Some accounts work and one does not: that account is the problem, not the line.
        if (perAccount.any { it.verdict == Verdict.PASS }) return StageResult(Verdict.DEGRADED, worst.layer, r.map { it.route })
        return worst.copy(evidence = r.map { it.route })
    }

    /**
     * One account across its routes. The app's real route decides; the alternative routes
     * (fixed IP, DoH address) only say whether a network failure there can be routed around.
     */
    private fun one(r: List<Evidence>): StageResult {
        val real = r.filter { it.route.startsWith("real") }
        val other = r - real.toSet()
        if (real.any { it.ok } || (real.isEmpty() && r.any { it.ok })) return StageResult(Verdict.PASS, "ok")
        val failure = (real.ifEmpty { r }).filterNot { it.ok }.minByOrNull { priority(it.code) }
            ?: return StageResult(Verdict.SKIPPED, "not_run")
        if (failure.code.startsWith("net.") && other.any { it.ok }) return StageResult(Verdict.DEGRADED, failure.code)
        return StageResult(Verdict.FAIL, failure.code)
    }

    /** Which failure names the problem when several routes failed differently: Cloudflare's own answer first. */
    private fun priority(code: String) = when {
        code == "cf.ratelimit" -> 0
        code == "cf.auth" -> 1
        code == "cf.account" -> 2
        code == "cf.server" -> 3
        else -> 4
    }

    fun chain(rows: List<Evidence>, stage: String): StageResult {
        val r = rows.filter { it.id == stage }
        if (r.isEmpty()) return StageResult(Verdict.SKIPPED, when (stage) { "ST2" -> "panel.none"; "ST4" -> "scan.config.none"; else -> "config.none" })
        // A stage cut by its deadline says so; it is never read as "nothing to check".
        val done = r.filter { it.numbers["terminal"] == 1L && it.code != "unfinished" }
        if (done.isEmpty()) return StageResult(Verdict.SKIPPED, if (r.any { it.code == "unfinished" }) "unfinished" else "incomplete")
        // One verdict per subject (a config, a panel): it works if any of its attempts did. A
        // config that failed once and connected on the retry is a working config.
        val subjects = done.groupBy { it.route.substringBefore(':') }.values
        val failed = subjects.filter { g -> g.none { it.ok } }
        if (failed.isEmpty()) {
            return if (done.any { it.code == "parse.partial" }) StageResult(Verdict.DEGRADED, "parse.partial") else StageResult(Verdict.PASS, "ok")
        }
        val layer = failed.first().last().code
        return StageResult(if (failed.size < subjects.size || layer == "parse.partial") Verdict.DEGRADED else Verdict.FAIL, layer)
    }
}

object PanelChain {
    fun inspect(status: Int, body: String, parse: (String) -> Boolean): StageResult {
        if (status !in 200..299) return StageResult(Verdict.FAIL, "sub.status($status)")
        if (body.isBlank()) return StageResult(Verdict.FAIL, "sub.empty")
        val decoded = runCatching { String(WireBase64.decode(body), Charsets.UTF_8) }
            .getOrElse { return StageResult(Verdict.FAIL, "sub.format") }
        val lines = decoded.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return StageResult(Verdict.FAIL, "sub.empty")
        val count = lines.count { runCatching { parse(it) }.getOrDefault(false) }
        return when (count) {
            0 -> StageResult(Verdict.FAIL, "parse.zero")
            lines.size -> StageResult(Verdict.PASS, "ok")
            else -> StageResult(Verdict.DEGRADED, "parse.partial")
        }.copy(evidence = listOf("lines=${lines.size}", "parsed=$count"))
    }
}

/**
 * What the app's own account check concluded, as one ST1 layer. Pure: the cases below are what
 * the Doctor tells a user about their Cloudflare account, so each is tested.
 */
object AccountConclusion {
    /** The numeric parts of CloudVerifyProbe; error strings are mined for codes here and dropped. */
    data class Check(val tokenHttp: Int, val subHttp: Int, val errors: String, val hasSubdomain: Boolean,
                     val emailVerified: Boolean?, val transport: String, val scheme: String)

    /**
     *  - it never reached Cloudflare: the network, at the phase its requests died in;
     *  - 429 / 1015: rate limited;
     *  - 401/403 to the credential itself: cf.auth (only when the credential was not ALSO accepted
     *    -- the check tries the wrong scheme first when it has to guess, and that 403 is not a verdict);
     *  - accepted, but no workers.dev subdomain or an unverified email: cf.account;
     *  - otherwise ok.
     */
    fun of(route: String, c: Check?, wire: List<Evidence>, ms: Long): Evidence {
        val base = mapOf("terminal" to 1L)
        fun network(): String = "net.${wire.lastOrNull { !it.ok && it.http == 0 }?.code?.substringBefore('_') ?: "http"}"
        if (c == null) return Evidence("ST1", route, network(), elapsedMs = ms, numbers = base)
        val codes = Regex("""\b(\d{4,5})\b""").findAll(c.errors).map { it.value.toInt() }.toList().take(6)
        val tok = c.tokenHttp
        val sub = c.subHttp
        val layer = when {
            tok == 429 || sub == 429 || 1015 in codes -> "cf.ratelimit"
            tok <= 0 && sub <= 0 -> network()
            tok in listOf(401, 403) && sub !in 200..299 -> "cf.auth"
            // 400 with Cloudflare's own code (6003 "invalid request headers": a malformed token or
            // a key sent as the wrong kind) is the credential, not the network.
            (tok == 400 || sub == 400) && codes.isNotEmpty() && sub !in 200..299 -> "cf.auth"
            // A credential that is valid but may not touch Workers is still unusable for the app.
            sub in listOf(401, 403) -> "cf.auth"
            sub in 200..299 && !c.hasSubdomain -> "cf.account"
            c.emailVerified == false -> "cf.account"
            sub in 200..299 || tok in 200..299 -> "ok"
            sub in 500..599 || tok in 500..599 -> "cf.server"
            sub < 0 || tok < 0 -> network()
            else -> "net.http"
        }
        return Evidence("ST1", route, layer, http = maxOf(tok, sub), elapsedMs = ms, errors = codes,
            numbers = base + mapOf("scheme_bearer" to if (c.scheme.startsWith("bearer")) 1L else 0L))
    }
}
