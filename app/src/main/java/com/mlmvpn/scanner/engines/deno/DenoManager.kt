package com.mlmvpn.scanner.engines.deno

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistence for Deno accounts and their deployments.
 *
 * Supports multiple Deno organization accounts. Each deployment is tied to the
 * account (token) it was created with, so usage/analytics can be aggregated
 * per account.
 */
class DenoManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("deno_prefs", Context.MODE_PRIVATE)

    // ---- Models ------------------------------------------------------------

    data class DenoAccount(
        val id: String,
        val label: String,
        val token: String,
        val createdAt: Long
    ) {
        /** ddo_qBcz�Pe84 → a short, safe fingerprint for display. */
        val tokenHint: String
            get() = if (token.length > 12) "${token.take(8)}�${token.takeLast(4)}" else token
    }

    data class DenoDeployment(
        val accountId: String,
        val projectId: String,
        val projectName: String,
        val host: String,
        val uuid: String,
        val wsPath: String,
        val vlessLink: String,
        val token: String,
        val createdAt: Long,
        val xhttpPath: String = ""   // xHTTP transport base path
    )

    // ---- Accounts ----------------------------------------------------------

    fun getAccounts(): List<DenoAccount> {
        val str = prefs.getString("accounts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(str)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DenoAccount(
                    id = o.getString("id"),
                    label = o.optString("label", "Account"),
                    token = o.getString("token"),
                    createdAt = o.optLong("createdAt", 0)
                )
            }.sortedBy { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Adds (or updates by token) an account and returns it. */
    fun addAccount(label: String, token: String): DenoAccount {
        val list = getAccounts().toMutableList()
        val existing = list.firstOrNull { it.token == token }
        if (existing != null) return existing
        val acc = DenoAccount(
            id = "acc_" + java.util.UUID.randomUUID().toString().take(8),
            label = label.ifBlank { "Deno ${list.size + 1}" },
            token = token,
            createdAt = System.currentTimeMillis()
        )
        list.add(acc)
        persistAccounts(list)
        return acc
    }

    fun renameAccount(id: String, label: String) {
        val list = getAccounts().map { if (it.id == id) it.copy(label = label) else it }
        persistAccounts(list)
    }

    fun removeAccount(id: String) {
        persistAccounts(getAccounts().filterNot { it.id == id })
        // Also drop this account's deployments.
        persistDeployments(getDeployments().filterNot { it.accountId == id })
    }

    private fun persistAccounts(list: List<DenoAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("label", a.label)
                put("token", a.token)
                put("createdAt", a.createdAt)
            })
        }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    // ---- Deployments -------------------------------------------------------

    fun saveDeployment(
        accountId: String,
        token: String,
        projectId: String,
        projectName: String,
        host: String,
        uuid: String,
        wsPath: String,
        vlessLink: String,
        xhttpPath: String = ""
    ) {
        val list = getDeployments().toMutableList()
        list.removeAll { it.projectId == projectId }
        list.add(
            DenoDeployment(
                accountId = accountId,
                projectId = projectId,
                projectName = projectName,
                host = host,
                uuid = uuid,
                wsPath = wsPath,
                vlessLink = vlessLink,
                token = token,
                createdAt = System.currentTimeMillis(),
                xhttpPath = xhttpPath
            )
        )
        persistDeployments(list)
    }

    fun getDeployments(): List<DenoDeployment> {
        val str = prefs.getString("deployments", "[]") ?: "[]"
        return try {
            val arr = JSONArray(str)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DenoDeployment(
                    accountId = o.optString("accountId", ""),
                    projectId = o.getString("projectId"),
                    projectName = o.getString("projectName"),
                    host = o.getString("host"),
                    uuid = o.getString("uuid"),
                    wsPath = o.getString("wsPath"),
                    vlessLink = o.getString("vlessLink"),
                    token = o.optString("token", ""),
                    createdAt = o.optLong("createdAt", 0),
                    xhttpPath = o.optString("xhttpPath", "")
                )
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getDeploymentsFor(accountId: String): List<DenoDeployment> =
        getDeployments().filter { it.accountId == accountId }

    fun removeDeployment(projectId: String) {
        persistDeployments(getDeployments().filterNot { it.projectId == projectId })
    }

    private fun persistDeployments(list: List<DenoDeployment>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("accountId", d.accountId)
                put("projectId", d.projectId)
                put("projectName", d.projectName)
                put("host", d.host)
                put("uuid", d.uuid)
                put("wsPath", d.wsPath)
                put("vlessLink", d.vlessLink)
                put("token", d.token)
                put("createdAt", d.createdAt)
                put("xhttpPath", d.xhttpPath)
            })
        }
        prefs.edit().putString("deployments", arr.toString()).apply()
    }

    // ---- Usage tracking (in-memory server counters + app-side accumulation) --
    //
    // Each Deno server counts traffic in memory since its last (re)start. We
    // poll it, diff against the last raw reading, and accumulate a persistent,
    // never-decreasing daily total on our side. A changed `boot` id means the
    // server restarted (counter reset to 0), so we baseline from 0.

    data class UsageBaseline(val boot: String, val requests: Long, val up: Long, val down: Long)

    fun getBaseline(projectId: String): UsageBaseline? {
        val str = prefs.getString("baseline_$projectId", null) ?: return null
        return try {
            val o = JSONObject(str)
            UsageBaseline(o.optString("boot"), o.optLong("req"), o.optLong("up"), o.optLong("down"))
        } catch (e: Exception) { null }
    }

    fun setBaseline(projectId: String, b: UsageBaseline) {
        val o = JSONObject().apply {
            put("boot", b.boot); put("req", b.requests); put("up", b.up); put("down", b.down)
        }
        prefs.edit().putString("baseline_$projectId", o.toString()).apply()
    }

    /** Add a usage delta to an account's per-day bucket (dateKey = yyyy-MM-dd). */
    fun addDaily(accountId: String, dateKey: String, req: Long, up: Long, down: Long) {
        if (req == 0L && up == 0L && down == 0L) return
        val root = try { JSONObject(prefs.getString("daily_$accountId", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val day = root.optJSONObject(dateKey) ?: JSONObject()
        day.put("req", day.optLong("req") + req)
        day.put("up", day.optLong("up") + up)
        day.put("down", day.optLong("down") + down)
        root.put(dateKey, day)
        prefs.edit().putString("daily_$accountId", root.toString()).apply()
    }

    /** All daily buckets for an account: dateKey -> [req, up, down]. */
    fun getDaily(accountId: String): Map<String, LongArray> {
        val root = try { JSONObject(prefs.getString("daily_$accountId", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val out = HashMap<String, LongArray>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = root.optJSONObject(k) ?: continue
            out[k] = longArrayOf(o.optLong("req"), o.optLong("up"), o.optLong("down"))
        }
        return out
    }
}
