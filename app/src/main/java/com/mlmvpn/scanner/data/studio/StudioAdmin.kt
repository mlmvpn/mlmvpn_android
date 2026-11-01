package com.mlmvpn.scanner.data.studio

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.PanelBuild
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The Cloudflare-account operations Config Studio needs and the rest of the app does not.
 *
 * Kept out of `CloudManager` on purpose. That class is 2,800 lines and is shared by seven panels;
 * everything here is about ONE installation's own address and lifecycle, and the alternative was
 * adding a ninth engine's worth of special cases to a file every other feature also depends on.
 *
 * Nothing in here is a read the app can do without: an operator who never opens these screens pays
 * for none of it.
 */
class StudioAdmin(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns())
        .protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun headers(account: CloudAccount) = CloudAuth.headers(account)

    private fun api(path: String) = "https://api.cloudflare.com/client/v4$path"

    // ---------------------------------------------------------------- what the token may do

    /**
     * What this credential is actually allowed to do, asked of Cloudflare rather than assumed.
     *
     * The setup wizard already fails when a permission is missing — but it fails at the step that
     * needed it, with whatever error Cloudflare returned, which for a missing scope is a 403 whose
     * body reads like a bug. Asking up front turns "deploy failed" into "this token cannot write
     * Workers", which is a sentence the operator can act on without guessing.
     *
     * Each capability is proved by the cheapest READ that requires it, never by parsing the token's
     * declared policy: a Global API Key has no policy document at all, and a token's policy can
     * name a permission group whose contents change. What matters is whether the call works.
     */
    suspend fun checkPermissions(account: CloudAccount): List<StudioPermission> =
        withContext(Dispatchers.IO) {
            val id = account.accountId ?: return@withContext emptyList()
            listOf(
                probe(StudioPermission.WORKERS, account, "/accounts/$id/workers/scripts"),
                probe(StudioPermission.D1, account, "/accounts/$id/d1/database"),
                probe(StudioPermission.SUBDOMAIN, account, "/accounts/$id/workers/subdomain"),
                probe(StudioPermission.ZONES, account, "/zones?per_page=1"),
            )
        }

    private fun probe(kind: String, account: CloudAccount, path: String): StudioPermission = try {
        val req = Request.Builder().url(api(path)).headers(headers(account)).get().build()
        client.newCall(req).execute().use { res ->
            StudioPermission(
                kind = kind,
                // 200 is a yes. 403 is a definite no. Anything else — a 404 on an account with no
                // subdomain, a 5xx, a timeout — is UNKNOWN rather than a failure, because telling
                // an operator their token lacks a permission it has is worse than saying nothing.
                granted = if (res.isSuccessful) true else if (res.code == 403) false else null,
                httpCode = res.code,
            )
        }
    } catch (e: Exception) {
        StudioPermission(kind, granted = null, httpCode = 0, error = e.message?.take(120))
    }

    // ---------------------------------------------------------------- where it answers

    /** The custom hostnames pointing at this account's workers, with the script each serves. */
    suspend fun listDomains(account: CloudAccount): List<StudioDomain> = withContext(Dispatchers.IO) {
        val id = account.accountId ?: return@withContext emptyList()
        try {
            val req = Request.Builder().url(api("/accounts/$id/workers/domains"))
                .headers(headers(account)).get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use emptyList()
                val arr = JSONObject(res.body.string()).optJSONArray("result") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val host = o.optString("hostname").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    StudioDomain(
                        id = o.optString("id"),
                        hostname = host,
                        service = o.optString("service").takeIf { it.isNotBlank() },
                        zoneName = o.optString("zone_name").takeIf { it.isNotBlank() },
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listDomains: ${e.message}")
            emptyList()
        }
    }

    /**
     * Point a hostname on one of this account's zones at the Config Studio worker.
     *
     * The zone is looked up rather than asked for, because an operator types `vpn.example.com` and
     * should not also have to know which of their zones owns it — and getting that pairing wrong is
     * a 400 from Cloudflare that says nothing useful. The longest matching zone wins, so
     * `a.b.example.com` attaches to `b.example.com` when both zones exist.
     *
     * **The worker keeps its workers.dev address as well.** A custom domain is added, not moved to:
     * every subscription link already handed out points at the old address, and those must keep
     * working or this action would silently cut off everyone the operator has.
     */
    suspend fun attachDomain(account: CloudAccount, hostname: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val id = account.accountId ?: return@withContext false to "no account id"
            val script = PanelBuild.scriptName(account, "MLM")
                ?: return@withContext false to "not deployed"
            val host = hostname.trim().lowercase().removePrefix("https://").removePrefix("http://")
                .trimEnd('/')
            if (host.isEmpty() || !host.contains('.')) return@withContext false to "bad hostname"

            val zone = findZone(account, host)
                ?: return@withContext false to "no zone on this account owns $host"

            val body = JSONObject().apply {
                put("environment", "production")
                put("hostname", host)
                put("service", script)
                put("zone_id", zone)
            }
            try {
                val req = Request.Builder().url(api("/accounts/$id/workers/domains"))
                    .headers(headers(account))
                    .put(body.toString().toRequestBody(JSON))
                    .build()
                client.newCall(req).execute().use { res ->
                    val text = res.body.string()
                    if (res.isSuccessful) true to host
                    else false to cloudflareMessage(text, res.code)
                }
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

    suspend fun detachDomain(account: CloudAccount, domainId: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val id = account.accountId ?: return@withContext false to "no account id"
            try {
                val req = Request.Builder().url(api("/accounts/$id/workers/domains/$domainId"))
                    .headers(headers(account)).delete().build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) true to "" else false to cloudflareMessage(res.body.string(), res.code)
                }
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

    /** The zone on this account that owns [host], longest suffix first. Null when none does. */
    private fun findZone(account: CloudAccount, host: String): String? = try {
        val req = Request.Builder().url(api("/zones?per_page=50"))
            .headers(headers(account)).get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) null
            else {
                val arr = JSONObject(res.body.string()).optJSONArray("result") ?: JSONArray()
                var bestId: String? = null
                var bestLen = -1
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").lowercase()
                    if (name.isEmpty()) continue
                    // Suffix match on a label boundary, so `notexample.com` does not match a zone
                    // called `example.com`.
                    if ((host == name || host.endsWith(".$name")) && name.length > bestLen) {
                        bestLen = name.length
                        bestId = o.optString("id").takeIf { it.isNotBlank() }
                    }
                }
                bestId
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "findZone: ${e.message}")
        null
    }

    // ---------------------------------------------------------------- does it answer from here

    /**
     * Fetch the installation's own address from THIS phone and time it.
     *
     * The wizard's scan already proves the worker exists — it asks Cloudflare, which will happily
     * confirm a script that the operator's own ISP has since blocked. This asks the only question
     * the scan cannot: does the address answer *here*, on the network the operator and their
     * subscribers are actually on.
     *
     * Both halves are checked and reported separately, because they fail for different reasons and
     * need different actions. The camouflage page answering while `/v1/health` does not means the
     * worker is up and the API route is wrong; neither answering means the address is unreachable.
     */
    suspend fun probeWorker(account: CloudAccount): StudioReachability = withContext(Dispatchers.IO) {
        val url = account.mlmWorkerUrl?.trimEnd('/')
            ?: return@withContext StudioReachability(error = "not deployed")
        val route = account.studioApiRoute

        val started = System.currentTimeMillis()
        val page = fetchCode(url)
        val pageMs = (System.currentTimeMillis() - started).toInt()
        val apiCode = route?.let { fetchCode("$url/$it/v1/health") }

        StudioReachability(
            pageCode = page,
            pageLatencyMs = if (page != null) pageMs else null,
            apiCode = apiCode,
            // Named rather than inferred by the screen, so the two layers cannot drift on what
            // "reachable" means.
            error = when {
                page == null -> "unreachable"
                route == null -> "no api route"
                apiCode == null -> "api unreachable"
                else -> null
            },
        )
    }

    /** The status code, or null when nothing came back at all. */
    private fun fetchCode(url: String): Int? = try {
        val req = Request.Builder().url(url).get()
            // No cache anywhere in the path: a cached 200 from an address that stopped answering
            // an hour ago is the one result this function must never return.
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(req).execute().use { it.code }
    } catch (e: Exception) {
        null
    }

    /**
     * Whether a config of this shape could survive this network, asked before one is made.
     *
     * **What this can and cannot prove is the whole design.** A config's path does not exist until
     * the config does — the engine serves the camouflage page to every path no enabled config
     * claims — so nothing can dial the tunnel that is about to be created. Pretending otherwise
     * would be the worst kind of test: one that passes because it never checked.
     *
     * What it does check is the half that actually fails in the field. An Iranian carrier that has
     * decided to kill WebSocket does it by dropping the upgrade, not by blocking the address, so an
     * ordinary GET succeeds and a config made on top of it never connects. This sends the real
     * upgrade headers and reports what came back:
     *
     *  * **101** — a config already claims this path and the upgrade went through end to end.
     *  * **any other answer from the worker** — the address is reachable and the upgrade was not
     *    stripped in transit, which is the thing being asked.
     *  * **nothing** — the address did not answer at all, and a config made now would not work.
     */
    suspend fun probeTransport(
        account: CloudAccount,
        host: String,
        port: Int,
        serverName: String?,
        websocket: Boolean,
    ): StudioTransportProbe = withContext(Dispatchers.IO) {
        val sni = serverName?.takeIf { it.isNotBlank() }
            ?: account.mlmWorkerUrl?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')
            ?: return@withContext StudioTransportProbe(error = "no server name")

        // Dialled by ADDRESS and presenting the worker's NAME. That pairing is the entire point of
        // a clean IP, and a probe that used the name for both would be testing a route the
        // subscriber will never take.
        val url = "https://$host:$port/"
        val started = System.currentTimeMillis()
        try {
            val builder = Request.Builder().url(url)
                .header("Host", sni)
                .header("User-Agent", BROWSER_UA)
            if (websocket) {
                builder.header("Connection", "Upgrade")
                    .header("Upgrade", "websocket")
                    .header("Sec-WebSocket-Version", "13")
                    // Sixteen random bytes, base64. A fixed value would be a signature of its own.
                    .header("Sec-WebSocket-Key", randomWebSocketKey())
            }
            client.newCall(builder.get().build()).execute().use { res ->
                StudioTransportProbe(
                    code = res.code,
                    latencyMs = (System.currentTimeMillis() - started).toInt(),
                    // A 101 means an enabled config already claims the root and the whole upgrade
                    // survived; anything else from the worker still proves the hop is open.
                    upgraded = res.code == 101,
                )
            }
        } catch (e: Exception) {
            StudioTransportProbe(error = (e.message ?: e.javaClass.simpleName).take(160))
        }
    }

    private fun randomWebSocketKey(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- taking it away

    /**
     * Delete the worker, and optionally its database, then forget the installation.
     *
     * The order matters and is not the obvious one: the **script goes first**. A database deleted
     * while a worker still binds it leaves a worker that answers every request with a D1 error —
     * which looks to a subscriber exactly like a broken tunnel and to an operator exactly like a
     * bug. Removing the script first means the address simply stops answering, which is what
     * "uninstalled" should look like.
     *
     * [alsoDeleteDatabase] is off by default and stays a separate decision: the D1 holds every
     * user, every subscription token and the whole traffic history, and it is the one thing here
     * that a redeploy cannot rebuild.
     */
    suspend fun uninstall(
        account: CloudAccount,
        alsoDeleteDatabase: Boolean,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cloud = CloudManager(context)
        val script = PanelBuild.scriptName(account, "MLM")
        if (script != null) {
            val (ok, message) = cloud.deleteWorker(account, script)
            // 10007 is Cloudflare's "no such script", which is success for our purposes: the thing
            // we were asked to remove is not there.
            if (!ok && !message.contains("10007") && !message.contains("not found", true)) {
                return@withContext false to message
            }
        }

        if (alsoDeleteDatabase) {
            account.mlmDbId?.let { db ->
                val (ok, message) = cloud.deleteD1Database(account, db)
                if (!ok) return@withContext false to message
                account.mlmDbId = null
            }
        }

        account.mlmStatus = "idle"
        account.mlmWorkerUrl = null
        account.mlmVersion = 0
        account.studioApiRoute = null
        account.studioKeyId = null
        account.studioApiSecret = null
        account.studioKeyLabel = null
        account.studioStatus = "idle"
        account.studioVersion = 0
        account.studioAdopted = false
        account.studioDoTag = null
        cloud.accounts.firstOrNull { it.id == account.id }?.let { live ->
            if (live !== account) {
                live.mlmStatus = account.mlmStatus
                live.mlmWorkerUrl = null
                live.mlmVersion = 0
                live.mlmDbId = account.mlmDbId
                live.studioApiRoute = null
                live.studioKeyId = null
                live.studioApiSecret = null
                live.studioKeyLabel = null
                live.studioStatus = "idle"
                live.studioVersion = 0
                live.studioAdopted = false
                live.studioDoTag = null
            }
        }
        cloud.saveAccounts()
        true to ""
    }

    private fun cloudflareMessage(body: String, code: Int): String = try {
        val errors = JSONObject(body).optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            (0 until errors.length()).joinToString("، ") { i ->
                errors.optJSONObject(i)?.optString("message").orEmpty()
            }.ifBlank { "HTTP $code" }
        } else "HTTP $code"
    } catch (e: Exception) {
        "HTTP $code"
    }

    private companion object {
        const val TAG = "StudioAdmin"
        // The same string the app's own connect path presents. A probe that announced itself would
        // be measuring how the network treats a probe.
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}

/**
 * One capability, and whether this credential has it.
 *
 * [granted] is deliberately `Boolean?`. Cloudflare answers 403 for "you may not", but a 404, a
 * timeout or a 5xx says nothing about the permission — and printing "missing" for a network blip
 * would send an operator to reissue a token that was fine.
 */
data class StudioPermission(
    val kind: String,
    val granted: Boolean?,
    val httpCode: Int = 0,
    val error: String? = null,
) {
    companion object {
        const val WORKERS = "workers"
        const val D1 = "d1"
        const val SUBDOMAIN = "subdomain"
        const val ZONES = "zones"
    }
}

/**
 * Whether an installation answers from the device asking.
 *
 * Two codes rather than one boolean: the camouflage page and the control plane are served by the
 * same worker on the same address, so a page that answers while the API does not is a route
 * problem, and neither answering is a reachability problem. One "is it up" flag would collapse the
 * two into an unactionable no.
 */
data class StudioReachability(
    val pageCode: Int? = null,
    val pageLatencyMs: Int? = null,
    val apiCode: Int? = null,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && apiCode == 200
}

/**
 * What a transport probe found.
 *
 * [upgraded] and `code != null` are different answers and both are useful. An upgrade that
 * completed proves the whole path; a plain answer proves the network did not strip it, which is the
 * failure this exists to catch.
 */
data class StudioTransportProbe(
    val code: Int? = null,
    val latencyMs: Int? = null,
    val upgraded: Boolean = false,
    val error: String? = null,
) {
    /** The address answered. Whether the tunnel will accept a credential is a later question. */
    val reachable: Boolean get() = error == null && code != null
}

/** A hostname pointing at one of this account's workers. */
data class StudioDomain(
    val id: String,
    val hostname: String,
    val service: String? = null,
    val zoneName: String? = null,
)
