package com.mlmvpn.scanner.engines.cfdoctor

import java.util.Collections

/**
 * The Doctor v3 sections of the report: one line per way tried, per subject, per stage.
 *
 * Shape, for whoever reads it next (human or script):
 *   == STAGE <name> <subject> key=value...
 *   WAY <stage> <subject> <way> <outcome> phase=.. ip=.. http=.. cf=.. ms=.. [k=v...]
 *   VERDICT <stage> <subject> <code> [ways=...] [why=...]
 *   RANGE / IP / PERM / XFER lines for the scan, permissions and data transfer.
 *
 * Never in here: tokens, keys, account ids, worker or panel host names, UUIDs, the user's own IP.
 * Cloudflare edge addresses are public and are written in full; a user's own server address only
 * as its /16 ([EdgeBook.mask16]).
 */
class DeepReport {
    private val lines = Collections.synchronizedList(mutableListOf<String>())

    fun section(stage: String, subject: String, vararg kv: Pair<String, Any?>) =
        add("== $stage ${tok(subject)}" + kvs(kv))

    fun way(stage: String, subject: String, r: WayResult, outcome: String, vararg kv: Pair<String, Any?>) {
        val core: List<Pair<String, Any?>> = listOf(
            "phase" to r.phase,
            // Cloudflare edges and bogus resolver answers in full; anyone else's server only as its /16.
            "ip" to r.ip.takeIf { it.isNotEmpty() }?.let { if (CfBogon.isCloudflare(it) || CfBogon.isBogon(it)) it else EdgeBook.mask16(it) },
            "http" to r.http.takeIf { it != 0 }, "cf" to r.cfErrors.takeIf { it.isNotEmpty() }?.joinToString("/"),
            "ms" to r.ms, "tcp_ms" to r.tcpMs.takeIf { it > 0 }, "tls_ms" to r.tlsMs.takeIf { it > 0 },
            "up" to r.sent.takeIf { it > 0 }, "down" to r.received.takeIf { it > 0 },
        )
        add("WAY $stage ${tok(subject)} ${tok(r.way)} ${tok(outcome)}" + kvs((core + kv.toList()).toTypedArray()))
    }

    fun line(tag: String, stage: String, subject: String, vararg kv: Pair<String, Any?>) =
        add("$tag $stage ${tok(subject)}" + kvs(kv))

    fun verdict(stage: String, subject: String, code: String, vararg kv: Pair<String, Any?>) =
        add("VERDICT $stage ${tok(subject)} ${tok(code)}" + kvs(kv))

    fun raw(text: String) = add(text)

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun verdicts(): List<String> = synchronized(lines) { lines.filter { it.startsWith("VERDICT ") } }

    private fun add(s: String) { lines += s.replace('\n', ' ').take(2000) }

    private fun kvs(kv: Array<out Pair<String, Any?>>): String =
        kv.filter { it.second != null && it.second.toString().isNotEmpty() }
            .joinToString("") { " ${it.first}=${tok(it.second.toString())}" }

    companion object {
        fun tok(s: String) = s.take(160).replace(Regex("[^A-Za-z0-9_.:,()+/@<>=#|*-]"), "_")
    }
}
