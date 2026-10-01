package com.mlmvpn.scanner.engines.cfdoctor

/**
 * What the Doctor tells the PERSON, as opposed to what it writes in the report for analysis.
 *
 * The report keeps every attempt and every code; this turns them into a few plain findings in
 * order of importance and a list of what actually worked on this line. Pure, so every rule here
 * is tested: a wrong sentence on this screen sends a user after the wrong fix.
 */
object DoctorAdvice {

    enum class Overall { NO_INTERNET, NO_INTERNATIONAL, ALL_GOOD, PROBLEMS, PARTIAL }

    enum class Finding {
        DNS_POISONED, IP_BLOCK_ALL, IP_BLOCK_PARTIAL, SNI_BLOCK, TLS_BLOCK_ALL, SILENT_AFTER_TLS, UDP_SUSPECT,
        ACCOUNT_NETWORK, ACCOUNT_ROUTE_ONLY, ACCOUNT_AUTH, ACCOUNT_RATELIMIT, ACCOUNT_SETUP, ACCOUNT_SERVER, ACCOUNT_SOME_FAILED,
        PANEL_HOST, PANEL_SUB, PANEL_PARSE,
        CONFIG_TCP, CONFIG_TLS, CONFIG_WS, CONFIG_QUOTA, CONFIG_PROXY, CONFIG_EGRESS, CONFIG_APP, CONFIG_CORE, CONFIG_SOME_FAILED,
        SCAN_NOT_CDN, SCAN_TCP, SCAN_TLS, SCAN_WS, SCAN_PROXY, SCAN_EGRESS, SCAN_CORE,
    }

    enum class Works { DOH, DOT, FAKE_SNI_DNS, ALT_PORT, NO_SNI, FAKE_SNI, MIXED_CASE_SNI, FRAGMENT, TLS12, TLS13, HTTP2,
        CONFIG_FRAGMENT, CONFIG_FINGERPRINT, CONFIG_TLS_SHAPE, CONFIG_ECH, CONFIG_XHTTP, CONFIG_CLEAN_IP, CONFIG_PORT, CONFIG_MIXED_SNI, SCAN_CAN_WORK }

    data class Stage(val verdict: Verdict, val layer: String)

    /**
     * [findings] are problems with what the user actually uses (the account, the panels, the
     * configs, the scanner) or a total block. [notes] are observations about the line -- some
     * Cloudflare ranges cut, a name filtered, UDP silent -- that matter for the analysis and for
     * other lines, but are not a problem while the user's own things work. v1 listed them as
     * problems, so a line where everything worked was reported as broken.
     */
    data class Advice(
        val overall: Overall,
        val stages: List<Stage>,
        val findings: List<Finding>,
        val works: List<Works>,
        val notes: List<Finding> = emptyList(),
    )

    fun of(rows: List<Evidence>, flags: Set<String>, complete: Boolean, hasCredential: Boolean): Advice {
        val st1 = StageVerdict.account(rows, hasCredential)
        val st2 = StageVerdict.chain(rows, "ST2")
        val st3 = StageVerdict.chain(rows, "ST3")
        val st4 = StageVerdict.chain(rows, "ST4")
        val stages = listOf(st1, st2, st3, st4).map { Stage(it.verdict, it.layer) }

        val findings = LinkedHashSet<Finding>()
        val notes = LinkedHashSet<Finding>()
        // A total block is a problem in itself; everything partial is a note.
        if ("IP_BLOCK_ALL" in flags) findings += Finding.IP_BLOCK_ALL else if ("IP_BLOCK_PARTIAL" in flags) notes += Finding.IP_BLOCK_PARTIAL
        if ("TLS_BLOCK_ALL" in flags) findings += Finding.TLS_BLOCK_ALL else if ("SNI_BLOCK" in flags) notes += Finding.SNI_BLOCK
        if ("DNS_POISONED" in flags) notes += Finding.DNS_POISONED
        if ("SILENT_AFTER_TLS" in flags) notes += Finding.SILENT_AFTER_TLS
        if ("UDP_SUSPECT" in flags) notes += Finding.UDP_SUSPECT

        listOf(st1, st2, st3, st4).forEachIndexed { i, r -> stageFinding(i, r.verdict, r.layer)?.let { findings += it } }

        val works = LinkedHashSet<Works>()
        val ok = rows.filter { it.ok }
        fun any(vararg ids: String) = ok.any { it.id in ids }
        if (any("D2", "D3", "D4", "D5", "D6", "D9")) works += Works.DOH
        if (any("D8")) works += Works.DOT
        if (any("D10")) works += Works.FAKE_SNI_DNS
        if (ok.any { it.id == "T2" && !it.route.endsWith(":443") }) works += Works.ALT_PORT
        if (any("S2")) works += Works.NO_SNI
        if (any("S5")) works += Works.FAKE_SNI
        if (any("S3", "S4")) works += Works.MIXED_CASE_SNI
        // A fragment "works" only where the plain handshake did not -- otherwise it proves nothing.
        if (("SNI_BLOCK" in flags || "TLS_BLOCK_ALL" in flags) && ok.any { it.id.startsWith("F") }) works += Works.FRAGMENT
        if (any("H1")) works += Works.TLS12
        if (any("H2")) works += Works.TLS13
        if (any("H3", "H4")) works += Works.HTTP2
        if (st3.verdict == Verdict.FAIL) {
            if (any("C3")) works += Works.CONFIG_FRAGMENT
            if (any("C2")) works += Works.CONFIG_FINGERPRINT
            if (any("C7", "C8")) works += Works.CONFIG_TLS_SHAPE
            if (any("C9")) works += Works.CONFIG_ECH
            if (any("C10")) works += Works.CONFIG_XHTTP
            if (any("C5")) works += Works.CONFIG_CLEAN_IP
            if (any("C6")) works += Works.CONFIG_PORT
            if (any("C4")) works += Works.CONFIG_MIXED_SNI
        }
        if (st4.layer == "ok") works += Works.SCAN_CAN_WORK

        val overall = when {
            "NO_CONNECTIVITY" in flags -> Overall.NO_INTERNET
            "NO_INTERNATIONAL" in flags -> Overall.NO_INTERNATIONAL
            findings.isEmpty() && complete -> Overall.ALL_GOOD
            findings.isEmpty() -> Overall.PARTIAL
            else -> Overall.PROBLEMS
        }
        return Advice(overall, stages, findings.toList(), works.toList(), notes.toList())
    }

    /**
     * The finding that names stage [index]'s failure (0 = ST1 .. 3 = ST4), or null when it passed,
     * was skipped, or failed in a way that has no plain sentence. The stage rows and the findings
     * list both use this, so they can never disagree.
     */
    fun stageFinding(index: Int, verdict: Verdict, layer: String): Finding? {
        if (verdict == Verdict.PASS || verdict == Verdict.SKIPPED) return null
        return when (index) {
            0 -> when {
                verdict == Verdict.DEGRADED && layer.startsWith("cf.") -> Finding.ACCOUNT_SOME_FAILED
                layer == "cf.ratelimit" -> Finding.ACCOUNT_RATELIMIT
                layer == "cf.auth" -> Finding.ACCOUNT_AUTH
                layer == "cf.account" -> Finding.ACCOUNT_SETUP
                layer == "cf.server" -> Finding.ACCOUNT_SERVER
                verdict == Verdict.DEGRADED && layer.startsWith("net.") -> Finding.ACCOUNT_ROUTE_ONLY
                layer.startsWith("net.") -> Finding.ACCOUNT_NETWORK
                else -> null
            }
            1 -> when {
                layer.startsWith("host.") -> Finding.PANEL_HOST
                layer.startsWith("sub.") -> Finding.PANEL_SUB
                layer.startsWith("parse.") -> Finding.PANEL_PARSE
                else -> null
            }
            2 -> when {
                // At least one config connected: the others are old or dead, not this line.
                verdict == Verdict.DEGRADED && layer != "ok" -> Finding.CONFIG_SOME_FAILED
                layer == "tcp" || layer == "dns" || layer.startsWith("tcp_") || layer.startsWith("dns_") -> Finding.CONFIG_TCP
                layer == "tls" || layer.startsWith("tls_") || layer == "cert_unexpected" -> Finding.CONFIG_TLS
                layer.startsWith("ws(") -> if (quota(layer)) Finding.CONFIG_QUOTA else Finding.CONFIG_WS
                layer == "ws" -> Finding.CONFIG_WS
                layer == "proxy.handshake" -> Finding.CONFIG_PROXY
                layer == "egress" || layer == "proxy_or_egress" -> Finding.CONFIG_EGRESS
                layer == "app.http" -> Finding.CONFIG_APP
                layer.startsWith("native.") -> Finding.CONFIG_CORE
                else -> null
            }
            3 -> when {
                layer == "scan.config.not_cdn" -> Finding.SCAN_NOT_CDN
                layer == "scan.tcp" -> Finding.SCAN_TCP
                layer == "scan.tls" -> Finding.SCAN_TLS
                layer.startsWith("scan.ws") -> Finding.SCAN_WS
                layer == "scan.proxy" -> Finding.SCAN_PROXY
                layer == "scan.egress" -> Finding.SCAN_EGRESS
                layer == "scan.core" -> Finding.SCAN_CORE
                else -> null
            }
            else -> null
        }
    }

    /** A worker that throws (1101, sent as HTTP 500) or is rate limited (429/1015) is out of quota or broken, not misconfigured. */
    private fun quota(layer: String) = Regex("""ws\((\d+)\)""").find(layer)?.groupValues?.get(1)?.toIntOrNull() in setOf(429, 500, 503, 530, 1015, 1101, 1102)
}
