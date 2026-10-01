package com.mlmvpn.scanner.engines.cfdoctor

import com.mlmvpn.scanner.utils.SecretRedactor
import org.json.JSONObject

object CfDoctorReport {
    private fun token(s: String) = s.take(64).replace(Regex("[^A-Za-z0-9_.:(),+/-]"), "_")
    fun render(plan: String, rows: List<Evidence>, flags: Set<String>, decisions: List<String>, complete: Boolean,
               environment: Map<String,String> = emptyMap(), hasCredential: Boolean = rows.any { it.id == "ST1" }): String {
        val stages = listOf(StageVerdict.account(rows,hasCredential),StageVerdict.chain(rows,"ST2"),StageVerdict.chain(rows,"ST3"),StageVerdict.chain(rows,"ST4"))
        val winners=rows.filter { it.ok && (it.http in 200..299 || it.id=="ST3") }.map { "${it.id}:${it.route}" }.distinct()
        var raw = rows.take(900).joinToString("\n") { r -> buildString {
            append("{\"id\":").append(JSONObject.quote(token(r.id)))
            append(",\"r\":").append(JSONObject.quote(token(r.route)))
            append(",\"c\":").append(JSONObject.quote(token(r.code)))
            append(",\"ms\":").append(r.elapsedMs.coerceAtLeast(0))
            append(",\"at\":").append(r.atMs.coerceAtLeast(0))
            if(r.http!=0) append(",\"h\":").append(r.http)
            if(r.errors.isNotEmpty()) append(",\"e\":[").append(r.errors.take(6).joinToString(",")).append(']')
            if(r.target.matches(Regex("[a-f0-9]{6}"))) append(",\"t\":\"").append(r.target).append('"')
            if(r.numbers.isNotEmpty()) { append(",\"n\":{"); append(r.numbers.entries.take(12).joinToString(",") { JSONObject.quote(token(it.key)) + ":" + it.value }); append('}') }
            if(r.tags.isNotEmpty()) { append(",\"s\":"); append(JSONObject(r.tags.filter { (k,v) -> Evidence.safeTag(k,v) }).toString()) }
            append('}')
        } }
        var dictionary=""
        if(raw.toByteArray().size>120000) {
            val routes=rows.map { token(it.route) }.distinct()
            val codes=rows.map { token(it.code) }.distinct()
            val keys=rows.flatMap { it.numbers.keys }.distinct().take(24)
            dictionary="COMPACT routes=${org.json.JSONArray(routes)} codes=${org.json.JSONArray(codes)} metrics=${org.json.JSONArray(keys)}"
            fun compact(metrics: Boolean)=rows.take(900).joinToString("\n") { r ->
                "{\"id\":"+JSONObject.quote(token(r.id))+",\"r\":"+routes.indexOf(token(r.route))+",\"c\":"+codes.indexOf(token(r.code))+
                    ",\"ms\":"+r.elapsedMs+",\"at\":"+r.atMs+",\"h\":"+r.http+
                    (if(r.errors.isEmpty()) "" else ",\"e\":${org.json.JSONArray(r.errors.take(6))}")+
                    (if(!metrics || r.numbers.isEmpty()) "" else ",\"n\":${org.json.JSONArray(keys.map { r.numbers[it] ?: JSONObject.NULL })}")+"}"
            }
            raw=compact(true)
            if(raw.toByteArray().size>125000) { raw=compact(false); dictionary+=" metrics_omitted=size_budget" }
        }
        return SecretRedactor.redact(buildString {
            appendLine("HEADER MLMVPN DOCTOR v2 plan=${token(plan)} complete=$complete")
            stages.forEachIndexed { i,s -> appendLine("ST${i+1} ${s.verdict} ${token(s.layer)}") }
            appendLine("ENV")
            environment.filterKeys { it in setOf("app","android","model","net","op","v6","vpn","validated","captive","private_dns","timezone","time","network_label") }
                .forEach { (k,v) -> appendLine("$k=${if(k=="network_label") SecretRedactor.redact(v).take(48).replace(Regex("[^\\p{L}\\p{N} _-]"),"_") else token(v)}") }
            appendLine("TRIAGE flags=${flags.sorted().joinToString(",") { token(it) }}")
            decisions.take(200).forEach { appendLine(token(it)) }
            appendLine("SUMMARY DOCTOR v2 op=${token(environment["op"] ?: "unknown")} net=${token(environment["net"] ?: "unknown")} flags=${flags.sorted().joinToString(",") { token(it) }} " + stages.mapIndexed { i,s -> "st${i+1}=${s.verdict.name.lowercase()}:${token(s.layer)}" }.joinToString(" "))
            appendLine("VERDICT ${if (complete) "finished" else "partial:interrupted_or_budget"}")
            appendLine("WINNERS ${winners.take(50).joinToString(",") { token(it) }}")
            appendLine("TABLES attempts=${rows.size} success=${rows.count { it.ok }} failed=${rows.count { !it.ok }}")
            if(dictionary.isNotEmpty()) appendLine(dictionary)
            appendLine("RAW"); append(raw)
        })
    }
    fun hash(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(3).joinToString("") { "%02x".format(it) }
}
