package com.mlmvpn.scanner.data.studio.central

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.engines.cloud.CfTunnel
import com.mlmvpn.scanner.engines.cloud.CfWorkers
import com.mlmvpn.scanner.engines.cloud.WorkerRoute
import com.mlmvpn.scanner.engines.gateway.EdgeGateway
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.utils.AntiDpi
import com.mlmvpn.scanner.widget.WidgetFormat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The account's subscription Worker (assets/studio_sub) on Cloudflare: installed once per account,
 * beside the central Worker and never in place of it, with a KV namespace of its own. Blocking
 * calls; callers are on IO.
 */
object CentralSubCloud {
    private const val TAG = "CentralSubCloud"
    const val NS_TITLE = "mlmvpn-studio-sub"
    /** The script's name ends with this: a neutral word list first, like every Worker the app makes. */
    const val SCRIPT_SUFFIX = "-sb"
    private const val ASSET = "studio_sub/worker.js"
    private const val BINDING = "S"

    private val http = OkHttpClient.Builder().dns(WorkerRoute.dns()).protocols(WorkerRoute.HTTP1)
        .socketFactory(WorkerRoute.cfSocketFactory()).proxySelector(CfTunnel.selector())
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val TEXT = "text/plain".toMediaType()

    fun code(context: Context): String = context.assets.open(ASSET).bufferedReader().use { it.readText() }

    /** A digest of the code, LF line ends whatever the checkout did: the same file reads the same. */
    fun codeHash(code: String): String =
        MessageDigest.getInstance("SHA-256").digest(code.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)

    /** A new sub Worker on [account]: its KV (found again after an interrupted install), the code, its address. */
    fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit): SubWorker {
        onStep(tr("ساخت جای داده‌های ساب روی حساب…", "Making the subscriptions' storage on the account…"))
        val kv = CfWorkers.kv(account, NS_TITLE)
        val sub = CfWorkers.ensureSubdomain(account)
        val script = AntiDpi.generateSafeWorkerName() + SCRIPT_SUFFIX
        val code = code(context)
        onStep(tr("بارگذاری ورکر ساب…", "Uploading the subscription Worker…"))
        CfWorkers.upload(account, script, code, listOf(CfWorkers.kvBinding(BINDING, kv)))
        onStep(tr("فعال کردن نشانی ورکر ساب…", "Turning on the subscription Worker's address…"))
        CfWorkers.enableWorkersDev(account, script)
        return SubWorker(script, "$script.$sub.workers.dev", kv, codeHash(code))
    }

    /** This app's code over an older one, on the same script, with the same KV: every link stays. */
    fun upgrade(context: Context, account: CloudAccount, sub: SubWorker): SubWorker {
        val code = code(context)
        CfWorkers.upload(account, sub.script, code, listOf(CfWorkers.kvBinding(BINDING, sub.kv)))
        return sub.copy(code = codeHash(code))
    }

    private fun api(account: CloudAccount, sub: SubWorker, key: String) =
        "https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${sub.kv}/values/" +
            URLEncoder.encode(key, "UTF-8")

    fun put(account: CloudAccount, sub: SubWorker, key: String, value: String) {
        val headers = CloudAuth.headers(account).newBuilder().set("Content-Type", "text/plain").build()
        http.newCall(Request.Builder().url(api(account, sub, key)).headers(headers).put(value.toRequestBody(TEXT)).build()).execute().use { r ->
            if (!r.isSuccessful) error(tr("نوشتن ساب روی حساب نشد", "Could not write the subscription to the account") + " (HTTP ${r.code}): " + CfWorkers.cfMessage(r.body?.string()))
        }
    }

    /** The value, or null when there is none. Throws when it could not be read. */
    fun get(account: CloudAccount, sub: SubWorker, key: String): String? =
        http.newCall(Request.Builder().url(api(account, sub, key)).headers(CloudAuth.headers(account)).get().build()).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> error("KV HTTP ${r.code}")
                else -> r.body?.string()
            }
        }

    fun delete(account: CloudAccount, sub: SubWorker, key: String) {
        http.newCall(Request.Builder().url(api(account, sub, key)).headers(CloudAuth.headers(account)).delete().build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 404) Log.w(TAG, "delete $key: HTTP ${r.code}")
        }
    }
}

/**
 * People's subscriptions on the account's sub Worker: written from their record here, read back for
 * the devices that came. Every write is owed until it is done ([CentralDoc.subPushed]); [pushOwed]
 * pays what is owed, from the screens after each change and from the background check.
 */
object CentralSubs {
    private const val TAG = "CentralSubs"

    /** One device that took a place, as the sub Worker wrote it down. */
    data class Device(val key: String, val place: Int, val os: String, val model: String, val app: String, val at: Long, val seen: Long) {
        /** An app that sends no device id: every such app shares this one entry. */
        val anonymous: Boolean get() = key == "~"
    }

    /** The sub Worker for this account -- installed, or brought to this app's code -- and known to every phone. */
    fun ensureWorker(context: Context, shard: CloudAccount, onStep: (String) -> Unit = {}): SubWorker {
        val engine = centralEngine(context, shard)
        val doc = engine.pull()
        val hash = CentralSubCloud.codeHash(CentralSubCloud.code(context))
        val have = doc.sub
        val sw = when {
            have == null -> CentralSubCloud.install(context, shard, onStep)
            have.code != hash -> CentralSubCloud.upgrade(context, shard, have)
            else -> return have
        }
        // Into the account's record, so the second phone uses this Worker and never makes another.
        engine.share { d -> d.copy(sub = d.sub?.takeIf { it.script == sw.script } ?: sw).let { it.copy(sub = it.sub?.copy(code = sw.code)) } to Unit }
        return sw
    }

    /** What the sub Worker serves for [u]: their ids, their state, and their configs in the three shapes. */
    fun recordOf(u: CentralUser, t: CentralSubRender.Templates): JSONObject = JSONObject()
        .put("v", 1).put("on", u.enabled && !u.isDeleted).put("until", u.expiresAt).put("name", u.name)
        .put("limit", u.deviceLimit).put("nohwid", u.noHwid).put("ids", JSONArray(u.ids())).put("ban", JSONArray(u.banned))
        .put("t", JSONObject().put("x", t.x).put("c", t.c).put("l", t.l))

    private fun digest(links: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest(links.joinToString("\n") { it.substringBefore('#') }.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    /**
     * Writes every subscription of [shard] this phone owes: a person changed since their last write,
     * a token that moved (the old address taken down, with the devices it had), a person deleted (the
     * same), and everyone when the Worker's ways in changed (its clean IPs, ports or address).
     * Blocking. Returns how many could not be written; they stay owed.
     */
    fun pushOwed(context: Context, shard: CloudAccount): Int {
        val engine = centralEngine(context, shard)
        val doc = engine.current()
        val sub = doc.sub ?: return 0
        val relevant = doc.users.filter { it.token != null || doc.subPushed[it.id]?.token != null }
        if (relevant.isEmpty()) return 0
        val ways = try {
            EdgeGateway.studioLinksBlocking(context, shard, CentralSubRender.DUMMY_ID, "x")
        } catch (e: Exception) {
            Log.w(TAG, "links: ${e.message}")
            return relevant.size
        }
        val waysChanged = digest(ways) != doc.subLinks
        val owed = relevant.filter { u ->
            val p = doc.subPushed[u.id]
            p == null || p.at != u.updatedAt || p.token != u.token || (waysChanged && !u.isDeleted && u.token != null)
        }
        var failed = 0
        val done = HashMap<String, SubPushed>()
        for (u in owed) {
            try {
                val before = doc.subPushed[u.id]?.token
                if (before != null && before != u.token) {
                    CentralSubCloud.delete(shard, sub, "u:$before")
                    CentralSubCloud.delete(shard, sub, "d:$before")
                }
                val token = u.token
                if (token != null && u.isDeleted) {
                    CentralSubCloud.delete(shard, sub, "u:$token")
                    CentralSubCloud.delete(shard, sub, "d:$token")
                } else if (token != null) {
                    val links = EdgeGateway.studioLinksBlocking(context, shard, CentralSubRender.DUMMY_ID, u.name)
                    val t = CentralSubRender.render(links, u.locations, { WidgetFormat.countryName(it) }, tr("IP ثابت", "fixed IP"))
                    CentralSubCloud.put(shard, sub, "u:$token", recordOf(u, t).toString())
                }
                done[u.id] = SubPushed(u.updatedAt, token)
            } catch (e: Exception) {
                Log.w(TAG, "push ${u.id}: ${e.message}")
                failed++
            }
        }
        val linksNow = digest(ways)
        engine.updateLocal { d -> d.copy(subPushed = d.subPushed + done, subLinks = if (failed == 0) linksNow else d.subLinks) }
        return failed
    }

    /** The devices that took [u]'s places, as the sub Worker wrote them down. Throws when it could not be read. */
    fun devices(context: Context, shard: CloudAccount, u: CentralUser): List<Device> {
        val sub = centralEngine(context, shard).current().sub ?: return emptyList()
        val token = u.token ?: return emptyList()
        val text = CentralSubCloud.get(shard, sub, "d:$token") ?: return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("dev") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("k")
            if (!Central.DEVICE_KEY_RE.matches(key)) return@mapNotNull null
            Device(key, o.optInt("s", -1), o.optString("os").take(40), o.optString("model").take(60), o.optString("app").take(80), o.optLong("at"), o.optLong("seen"))
        }.filter { it.place in 0 until u.deviceLimit }.sortedBy { it.place }
    }

    /** The sub Worker's list of [u]'s devices without [keys] (gone with their place), or empty for [all]. */
    fun forgetDevices(context: Context, shard: CloudAccount, u: CentralUser, keys: Set<String>, all: Boolean = false) {
        val sub = centralEngine(context, shard).current().sub ?: return
        val token = u.token ?: return
        if (all) {
            CentralSubCloud.delete(shard, sub, "d:$token")
            return
        }
        val text = CentralSubCloud.get(shard, sub, "d:$token") ?: return
        val o = JSONObject(text)
        val arr = o.optJSONArray("dev") ?: return
        val kept = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.takeIf { it.optString("k") !in keys }?.let { kept.put(it) }
        CentralSubCloud.put(shard, sub, "d:$token", JSONObject().put("v", 1).put("dev", kept).toString())
    }
}
