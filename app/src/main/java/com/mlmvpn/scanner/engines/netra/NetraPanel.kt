package com.mlmvpn.scanner.engines.netra

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.cloud.CfWorkers
import com.mlmvpn.scanner.engines.cloud.PanelRegistry
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.StoreFiles
import com.mlmvpn.scanner.store.StoreNet
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * «پنل نترا» (github.com/netrair/netra-panel, MIT) on the user's own Cloudflare account.
 *
 * A BPB fork: one Worker, a KV namespace bound as exactly `kv`, subscriptions at
 * `/<securePath>/sub/raw?app=xray`. Unlike BPB v5 it reads `UUID`, `TR_PASS` and `SUB_PATH` from
 * the environment ahead of everything else (read from its own code, 2026-09-27), so the deploy sets
 * those as secrets -- random, never the shipped defaults (`89b3cbba-…`, `netra`, `netra`) that
 * everybody who reads the repo knows -- and no first-visit password step is needed to get
 * configs. A store update replaces the code only; the secrets and KV stay.
 *
 * Its code carries BPB's own markers (`EMBEDED_SETTINGS`, `panelVersion:"5.1.1"`), so the store
 * must recognise it before BPB: see StoreCatalog.
 */
object NetraPanel {

    private const val TAG = "NetraPanel"
    const val REPO = "netrair/netra-panel"
    const val RELEASE_ASSET = "worker.js"
    /** The store's asset name. Not shipped: [code] falls back to the developer's latest release. */
    const val ASSET = "netra_worker.js"
    private const val PREFS = "netra_panel"
    /** This panel's key in the account's shared registry (PanelRegistry). */
    const val CODE = "NTR"

    data class Install(val script: String, val url: String, val uuid: String, val trPass: String, val subPath: String, val kvId: String)

    fun looksLikeNetra(code: String) = code.contains("_project_:\"Netra\"") && code.contains("SOURCE_CONTENT")

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun install(context: Context, accountId: String): Install? = runCatching {
        val o = JSONObject(prefs(context).getString("i_$accountId", null) ?: return null)
        Install(o.getString("script"), o.getString("url"), o.getString("uuid"), o.getString("tr"), o.getString("sub"), o.optString("kv"))
    }.getOrNull()

    private fun save(context: Context, accountId: String, i: Install) {
        prefs(context).edit().putString("i_$accountId", JSONObject().put("script", i.script).put("url", i.url).put("uuid", i.uuid)
            .put("tr", i.trPass).put("sub", i.subPath).put("kv", i.kvId).toString()).apply()
    }

    private fun fromRecord(g: PanelRegistry.Record) = Install(g.script, g.url, g.s.optString("uuid"), g.s.optString("trPass"), g.s.optString("subPath"), g.kv.orEmpty())

    private fun publish(account: CloudAccount, i: Install) = PanelRegistry.publish(account, CODE, i.script, i.url, i.kvId.ifBlank { null }, null,
        JSONObject().put("uuid", i.uuid).put("trPass", i.trPass).put("subPath", i.subPath))

    /**
     * The account's shared Netra (PanelRegistry) → this phone; this phone's → the account when the
     * registry has none yet. Blocking; call on IO.
     */
    fun sync(context: Context, account: CloudAccount) {
        val g = PanelRegistry.live(account, CODE)
        if (g != null) { save(context, account.accountId, fromRecord(g)); return }
        val mine = install(context, account.accountId) ?: return
        if (PanelRegistry.scriptExists(account, mine.script)) publish(account, mine)
    }

    /** The store's copy when it has one, else `worker.js` from the developer's latest release. */
    fun code(context: Context): String =
        runCatching { StoreFiles.readText(context, ASSET) }.getOrNull() ?: run {
            val rel = StoreNet.latestRelease(context, REPO)
            val asset = rel.assets.firstOrNull { it.name == RELEASE_ASSET }
                ?: error(tr("انتشار نترا فایل worker.js ندارد.", "Netra's release has no worker.js."))
            StoreNet.getText(context, asset.url, 8 * 1024 * 1024)
        }

    private fun random(n: Int): String {
        val abc = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    /** Put Netra on [account], or put new code on the one already there (same secrets, same KV). */
    suspend fun deploy(context: Context, account: CloudAccount, onStep: (String) -> Unit): Result<Install> = withContext(Dispatchers.IO) {
        runCatching {
            onStep(tr("بررسی زیردامنه…", "Checking the subdomain…"))
            val sub = CfWorkers.subdomain(account)
            // The account's own Netra first -- the one Windows or this phone already installed --
            // never a second Worker and KV beside it.
            PanelRegistry.live(account, CODE)?.let { g ->
                val shared = fromRecord(g)
                if (install(context, account.accountId) != shared) {
                    save(context, account.accountId, shared)
                    onStep(tr("همان نترای مشترک این حساب به کار می‌رود — ورکر یا KV تازه ساخته نمی‌شود.", "Using this account's shared Netra — no new Worker or KV."))
                }
            }
            val prev = install(context, account.accountId)
            val script = prev?.script ?: (com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-ntr")
            val inst = prev ?: Install(
                script = script, url = "https://$script.$sub.workers.dev",
                uuid = UUID.randomUUID().toString(), trPass = random(16), subPath = random(12), kvId = "",
            )

            onStep(tr("دریافت کد از گیت‌هاب سازنده…", "Fetching the code from the developer's GitHub…"))
            val code = code(context)
            if (!looksLikeNetra(code)) error(tr("کد دریافتی پنل نترا نیست.", "The code received is not Netra."))

            val kv = inst.kvId.ifBlank {
                onStep(tr("ساخت فضای KV…", "Creating the KV namespace…"))
                CfWorkers.kv(account, "$script-kv")
            }
            onStep(tr("بارگذاری ورکر…", "Uploading the Worker…"))
            CfWorkers.upload(
                account, script, code,
                bindings = listOf(
                    CfWorkers.kvBinding("kv", kv),
                    CfWorkers.secret("UUID", inst.uuid),
                    CfWorkers.secret("TR_PASS", inst.trPass),
                    CfWorkers.secret("SUB_PATH", inst.subPath),
                ),
                // A BPB fork: node built-ins exist only under nodejs_compat (as for BPB).
                flags = listOf("nodejs_compat"),
            )
            onStep(tr("فعال کردن آدرس…", "Enabling the address…"))
            CfWorkers.enableWorkersDev(account, script)
            inst.copy(kvId = kv).also { save(context, account.accountId, it); publish(account, it) }
        }.onFailure { Log.w(TAG, "deploy", it) }
    }

    /**
     * The panel's own subscription, as links: `/<SUB_PATH>/sub/raw?app=xray` answers a base64 list,
     * the same shape BPB serves. A fresh workers.dev name can take a few seconds to answer.
     */
    suspend fun configs(context: Context, inst: Install): List<String> = withContext(Dispatchers.IO) {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                val req = Request.Builder().url("${inst.url}/${inst.subPath}/sub/raw?app=xray").get().build()
                com.mlmvpn.scanner.update.UpdateNet.client(context, 15, 30, directFirst = true).newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body?.string().orEmpty().trim()
                    val text = runCatching { String(android.util.Base64.decode(body, android.util.Base64.DEFAULT)) }.getOrDefault(body)
                    val links = text.split("\n").map { it.trim() }.filter { it.startsWith("vless://") || it.startsWith("trojan://") }
                    if (links.isNotEmpty()) return@withContext links.map { com.mlmvpn.scanner.utils.AntiDpi.applySniCamouflage(it) }
                    error(tr("اشتراک نترا خالی بود.", "Netra's subscription was empty."))
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 3) kotlinx.coroutines.delay(3000)
            }
        }
        throw last ?: IllegalStateException("no configs")
    }

    suspend fun remove(context: Context, account: CloudAccount): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val inst = install(context, account.accountId) ?: return@runCatching
            CfWorkers.remove(account, inst.script, kvId = inst.kvId)
            prefs(context).edit().remove("i_${account.accountId}").apply()
            PanelRegistry.removeIf(account, CODE, inst.script)
        }
    }
}
