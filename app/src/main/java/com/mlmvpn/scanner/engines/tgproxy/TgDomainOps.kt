package com.mlmvpn.scanner.engines.tgproxy

import com.mlmvpn.scanner.models.CloudAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A site (zone) in the user's Cloudflare account. [active] false = nameservers not switched yet. */
data class TgZone(val id: String, val name: String, val status: String, val nameServers: List<String>) {
    val active: Boolean get() = status == "active"
}

/** A DNS record on a zone, as far as ownership checks need it. */
data class TgRecord(val id: String, val type: String, val content: String, val comment: String)

/**
 * A custom domain for a proxy: the user's own domain on Cloudflare, pointed at the Worker or Pages
 * project. Nothing is overwritten: a hostname that already has a DNS record is refused, and the
 * records this creates carry a comment naming the service, so only those are ever deleted.
 *
 * Workers: Cloudflare creates the DNS record and the certificate itself (`workers/domains`).
 * Pages: the project gets the domain, and a proxied CNAME to `<project>.pages.dev` is added by hand.
 */
class TgDomainOps(private val api: TgCfApi) {

    private fun json(text: String) = text.toRequestBody("application/json".toMediaType())

    /** The sites of this account, active ones first. */
    suspend fun zones(a: CloudAccount): List<TgZone> {
        val arr = api.send(a, "GET", "/zones?account.id=${a.accountId}&per_page=50").json.optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val z = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = z.optString("name").lowercase().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ns = z.optJSONArray("name_servers")?.let { n -> (0 until n.length()).map { n.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            TgZone(z.optString("id"), name, z.optString("status"), ns)
        }.sortedWith(compareByDescending<TgZone> { it.active }.thenBy { it.name })
    }

    /** The records of [zoneId] named exactly [host]. */
    suspend fun records(a: CloudAccount, zoneId: String, host: String): List<TgRecord> {
        val arr = api.send(a, "GET", "/zones/$zoneId/dns_records?name=$host&per_page=20").json.optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            TgRecord(r.optString("id"), r.optString("type"), r.optString("content"), r.optString("comment"))
        }
    }

    /** Attaches [host] to the Worker [script]; returns Cloudflare's id for the attachment. */
    suspend fun attachWorker(a: CloudAccount, host: String, script: String, zoneId: String): String? {
        val body = JSONObject().put("environment", "production").put("hostname", host).put("service", script).put("zone_id", zoneId)
        val r = api.send(a, "PUT", "/accounts/${a.accountId}/workers/domains", json(body.toString()), retries = 1)
        return r.json.optJSONObject("result")?.optString("id")?.takeIf { it.isNotBlank() }
    }

    suspend fun detachWorker(a: CloudAccount, domainId: String) {
        try {
            api.send(a, "DELETE", "/accounts/${a.accountId}/workers/domains/$domainId")
        } catch (e: TgApiException) {
            if (e.kind != TgApiException.Kind.NOT_FOUND) throw e
        }
    }

    /** Adds [host] to the Pages project. A domain already added (a resumed attach) is fine. */
    suspend fun attachPages(a: CloudAccount, project: String, host: String) {
        try {
            api.send(a, "POST", "/accounts/${a.accountId}/pages/projects/$project/domains", json(JSONObject().put("name", host).toString()))
        } catch (e: TgApiException) {
            if (e.kind != TgApiException.Kind.CONFLICT) throw e
        }
    }

    /** The Pages domain's status: `initializing`, `pending`, `active`, ... */
    suspend fun pagesStatus(a: CloudAccount, project: String, host: String): String =
        api.send(a, "GET", "/accounts/${a.accountId}/pages/projects/$project/domains/$host").json.optJSONObject("result")?.optString("status").orEmpty()

    suspend fun detachPages(a: CloudAccount, project: String, host: String) {
        try {
            api.send(a, "DELETE", "/accounts/${a.accountId}/pages/projects/$project/domains/$host")
        } catch (e: TgApiException) {
            if (e.kind != TgApiException.Kind.NOT_FOUND) throw e
        }
    }

    /** A proxied CNAME to the Pages project, commented with the service so it can be told from the user's own. */
    suspend fun createCname(a: CloudAccount, zoneId: String, host: String, target: String, serviceId: String): String? {
        val body = JSONObject().put("type", "CNAME").put("name", host).put("content", target).put("proxied", true).put("ttl", 1).put("comment", COMMENT + serviceId)
        return api.send(a, "POST", "/zones/$zoneId/dns_records", json(body.toString())).json.optJSONObject("result")?.optString("id")?.takeIf { it.isNotBlank() }
    }

    /** Deletes a record only if it carries our comment for this service. */
    suspend fun deleteOurRecord(a: CloudAccount, zoneId: String, recordId: String, serviceId: String) {
        val record = try {
            api.send(a, "GET", "/zones/$zoneId/dns_records/$recordId").json.optJSONObject("result")
        } catch (e: TgApiException) {
            if (e.kind == TgApiException.Kind.NOT_FOUND) return
            throw e
        } ?: return
        if (record.optString("comment") != COMMENT + serviceId) return
        try {
            api.send(a, "DELETE", "/zones/$zoneId/dns_records/$recordId")
        } catch (e: TgApiException) {
            if (e.kind != TgApiException.Kind.NOT_FOUND) throw e
        }
    }

    companion object {
        const val COMMENT = "mlmvpn-tgp:"

        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        fun isLabel(text: String): Boolean = LABEL.matches(text)

        /** `label.zone`, or null when the label is not a valid single DNS label. */
        fun hostFor(label: String, zone: TgZone): String? = label.trim().lowercase().takeIf { isLabel(it) }?.let { "$it.${zone.name}" }
    }
}
