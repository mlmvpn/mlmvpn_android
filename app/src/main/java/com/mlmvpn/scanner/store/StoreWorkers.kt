package com.mlmvpn.scanner.store

import android.content.Context
import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.models.CloudAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updating the panels on the user's OWN Cloudflare accounts — the Android half of the Windows
 * store's `store/workers.js`, with the same two rules:
 *
 *   RECOGNISE, DON'T GUESS. Script names are random, so a worker is identified by a fingerprint in
 *   its CODE ([StoreCatalog.classify]). What is not positively recognised is the user's own, and is
 *   never read twice, offered an update, or touched.
 *
 *   UPDATE THE CODE, NOTHING ELSE. Cloudflare's content endpoint (`PUT …/content`) replaces the
 *   module and keeps every binding, secret, KV namespace, D1 database, Durable Object, route and
 *   compatibility flag exactly as they were. BPB's per-account settings are compiled into a prefix
 *   of its own script; that prefix is carried to the new code verbatim.
 *
 * The code that was live goes to the phone before anything is uploaded, so «برگشت» is a button.
 */
object StoreWorkers {

    private const val API = "https://api.cloudflare.com/client/v4"

    // api.cloudflare.com is reachable in much of Iran directly, but not everywhere: on the networks
    // that read the TLS server name it is cut like any other. So the deploy goes through the same
    // route the rest of the app uses for Cloudflare -- a proven edge address (WorkerRoute.dns) with
    // the ClientHello split so the name is not in one packet (CfFront) -- which is a no-op where the
    // API was reachable anyway.
    private val client = OkHttpClient.Builder()
        .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns())
        .socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Script(val mainModule: String, val body: String, val isModule: Boolean)

    fun accounts(context: Context): List<CloudAccount> =
        CloudManager(context).apply { loadAccounts() }.accounts.toList()

    private fun cfError(body: String?, code: Int): String = runCatching {
        JSONObject(body ?: "").optJSONArray("errors")?.optJSONObject(0)?.optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"

    fun listScripts(account: CloudAccount): List<String> {
        val req = Request.Builder().url("$API/accounts/${account.accountId}/workers/scripts")
            .headers(CloudAuth.headers(account)).get().build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful) throw StoreError(cfError(body, r.code))
            val arr = JSONObject(body ?: "{}").optJSONArray("result") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
        }
    }

    /**
     * The main module of a deployed script. `/content/v2` answers multipart; the module is the
     * part whose name ends in .js/.mjs. Bytes are split as Latin-1 so no UTF-8 sequence is cut.
     */
    fun fetchScript(account: CloudAccount, name: String): Script? {
        val req = Request.Builder().url("$API/accounts/${account.accountId}/workers/scripts/$name/content/v2")
            .headers(CloudAuth.headers(account)).get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val bytes = r.body?.bytes() ?: return null
            val ct = r.header("Content-Type").orEmpty()
            val boundary = Regex("""boundary=("?)([^";]+)\1""").find(ct)?.groupValues?.get(2)
                ?: return Script("worker.js", String(bytes, Charsets.UTF_8), true)
            val raw = String(bytes, Charsets.ISO_8859_1)
            var first: Script? = null
            for (seg in raw.split("--$boundary")) {
                val i = seg.indexOf("\r\n\r\n")
                if (i < 0) continue
                val head = seg.substring(0, i)
                val nm = Regex("""name="([^"]+)"""").find(head)?.groupValues?.get(1) ?: continue
                var body = seg.substring(i + 4)
                if (body.endsWith("\r\n")) body = body.dropLast(2)
                val text = String(body.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                val partType = Regex("""Content-Type:\s*([^\r\n]+)""", RegexOption.IGNORE_CASE)
                    .find(head)?.groupValues?.get(1)?.trim().orEmpty()
                val isModule = when {
                    partType.contains("module") -> true
                    partType.startsWith("application/javascript") -> false
                    else -> text.contains("export default")
                }
                val part = Script(nm, text, isModule)
                if (first == null) first = part
                if (nm.endsWith(".js") || nm.endsWith(".mjs")) return part
            }
            return first
        }
    }

    /**
     * Replace the code of an existing worker without touching anything else.
     *
     * A service-worker-format script (no `export default`) is uploaded as a `body_part`, a module
     * as `main_module` — sending the wrong one is rejected by Cloudflare, or worse, accepted and
     * then never called.
     */
    fun putContent(account: CloudAccount, name: String, script: Script, code: String) {
        val meta = JSONObject().apply {
            if (script.isModule) put("main_module", script.mainModule) else put("body_part", script.mainModule)
        }
        val type = if (script.isModule) "application/javascript+module" else "application/javascript"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", "metadata.json", meta.toString().toRequestBody("application/json".toMediaType()))
            .addFormDataPart(script.mainModule, script.mainModule, code.toRequestBody(type.toMediaType()))
            .build()
        val req = Request.Builder().url("$API/accounts/${account.accountId}/workers/scripts/$name/content")
            .headers(CloudAuth.headers(account)).put(body).build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string()
            val ok = r.isSuccessful && runCatching { JSONObject(text ?: "{}").optBoolean("success", true) }.getOrDefault(true)
            if (!ok) throw StoreError(tr("آپلود کد ورکر رد شد: ", "Cloudflare refused the new code: ") + cfError(text, r.code))
        }
    }

    // ── BPB's settings prefix ─────────────────────────────────────────────────────────────────

    private const val BPB_MARKER = "Object.assign(globalThis,"

    /**
     * Everything the deploy put in front of BPB's source, including the closing `);`.
     *
     * One statement — `Object.assign(globalThis,{"EMBEDED_SETTINGS":{…}});` — whose end is found by
     * balancing the braces of its JSON argument (strings accounted for), because the panel's own
     * self-redeploy writes it with different spacing than this app does. Null when the shape is
     * not what v5 produces — and the caller then refuses to touch that panel.
     */
    fun bpbPrefix(text: String): String? {
        val at = text.indexOf(BPB_MARKER)
        if (at < 0) return null
        val open = text.indexOf('{', at + BPB_MARKER.length)
        if (open < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        var end = -1
        for (i in open until text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end < 0) return null
        val close = Regex("""^\s*\)\s*;""").find(text.substring(end + 1, minOf(text.length, end + 12))) ?: return null
        val settings = runCatching { JSONObject(text.substring(open, end + 1)) }.getOrNull() ?: return null
        if (settings.optJSONObject("EMBEDED_SETTINGS") == null) return null
        return text.substring(0, end + 1 + close.value.length)
    }

    fun stripBpbPrefix(text: String): String {
        val p = bpbPrefix(text) ?: return text
        return text.substring(p.length).removePrefix("\r\n").removePrefix("\n")
    }

    /** The exact code to upload for one deployed worker, built from the store's current copy. */
    fun codeFor(item: StoreItem, target: String, deployed: String): String {
        val spec = item.worker!!
        var code = if (spec.stripBom) target.removePrefix("﻿") else target
        if (spec.bpbPrefix) {
            val prefix = bpbPrefix(deployed)
                ?: throw StoreError(tr("تنظیمات جاسازی‌شدهٔ این پنل خوانده نشد — برای امنیت دست نخورد.",
                    "This panel's embedded settings could not be read — it was left alone."))
            code = prefix + stripBpbPrefix(code)
        }
        spec.inject?.let { code = it(code, deployed) }
        return code
    }

    // ── backups ─────────────────────────────────────────────────────────────────────────────────

    private fun backupDir(context: Context, account: CloudAccount, script: String) =
        File(context.filesDir, "store/worker-backups/${account.accountId.safe()}/${script.safe()}")

    private fun String.safe() = replace(Regex("""[^\w.-]"""), "_")

    fun saveBackup(context: Context, account: CloudAccount, script: String, s: Script, version: String?) {
        val dir = backupDir(context, account, script).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        File(dir, "$stamp.js").writeText(s.body)
        File(dir, "$stamp.json").writeText(JSONObject().apply {
            put("module", s.mainModule); put("isModule", s.isModule); put("version", version ?: ""); put("at", stamp)
        }.toString())
        // Three is enough to undo a bad run. For BPB each one carries the account token, so no more.
        dir.listFiles { f -> f.name.endsWith(".js") }?.sortedByDescending { it.name }?.drop(3)?.forEach {
            it.delete(); File(dir, it.name.removeSuffix(".js") + ".json").delete()
        }
    }

    fun hasBackup(context: Context, account: CloudAccount, script: String): Boolean =
        backupDir(context, account, script).listFiles { f -> f.name.endsWith(".js") }?.isNotEmpty() == true

    /** Put the most recent backup of this worker's code back. */
    fun rollback(context: Context, account: CloudAccount, script: String): String? {
        val dir = backupDir(context, account, script)
        val latest = dir.listFiles { f -> f.name.endsWith(".js") }?.maxByOrNull { it.name }
            ?: throw StoreError(tr("پشتیبانی از کد این ورکر روی این گوشی نیست.", "No backup of this worker's code is on this phone."))
        val meta = runCatching { JSONObject(File(dir, latest.name.removeSuffix(".js") + ".json").readText()) }.getOrElse { JSONObject() }
        val s = Script(meta.optString("module", "worker.js"), latest.readText(), meta.optBoolean("isModule", true))
        putContent(account, script, s, s.body)
        latest.delete()
        File(dir, latest.name.removeSuffix(".js") + ".json").delete()
        return meta.optString("version").ifBlank { null }
    }
}
