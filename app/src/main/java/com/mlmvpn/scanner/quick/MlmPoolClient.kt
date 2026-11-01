package com.mlmvpn.scanner.quick

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The MLMVPN shared pool, from the client side.
 *
 * Every install that runs a sweep learns something the next one would otherwise have to rediscover:
 * that a given config answered, on a given network, a minute ago. This sends those answers to the
 * pool worker and reads back what everyone else found -- so the list a user connects from is one
 * that other people on their own operator have just proven, rather than a public feed of which
 * roughly one percent works at any moment.
 *
 * Nothing here is stored on disk except the enrolment pair. The configs themselves never touch
 * the filesystem -- see [QuickSavedStore.addAll], which refuses them -- because they are the
 * app's own list and must not be exportable, and because `android:allowBackup="true"` would
 * otherwise copy them into the user's Google Drive.
 */
object MlmPoolClient {

    private const val TAG = "MlmPool"
    private const val PREFS = "mlm_pool"
    private const val KEY_ID = "install_id"
    private const val KEY_SECRET = "install_secret"
    private const val KEY_ENDPOINT = "endpoint"

    /**
     * Where the pool lives.
     *
     * A stored default rather than a constant so a second worker can be swapped in without an app
     * update -- D1's free allowance is per account, and at roughly sixteen thousand daily users
     * this one runs out.
     */
    private const val DEFAULT_ENDPOINT = "https://pool.redacted.invalid"

    /** Marks nodes that came from the pool, so the rest of the app can treat them as locked. */
    const val SOURCE = "mlmvpn"

    /**
     * DNS that does not go through the operator's resolver.
     *
     * Measured on the test phone: `ping` for any `*.workers.dev` name returns "unknown host",
     * while the same name resolves fine from a desktop on a different line -- the operator
     * poisons the whole zone. The system resolver therefore cannot be trusted to reach our own
     * worker, and OkHttp's default DNS is the system resolver.
     *
     * [DomainPreResolver] already solves exactly this for config domains: DoH addressed to a
     * resolver's IP, so nothing has to be resolved in order to resolve. Reused here rather than
     * written again, with the system resolver kept as the fallback for networks where it works.
     */
    private val dohDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val viaDoh = runCatching {
                kotlinx.coroutines.runBlocking {
                    com.mlmvpn.scanner.utils.DomainPreResolver.resolve(hostname)
                }
            }.getOrDefault(emptyList())
            if (viaDoh.isNotEmpty()) {
                val addrs = viaDoh.mapNotNull {
                    runCatching { java.net.InetAddress.getByName(it) }.getOrNull()
                }
                if (addrs.isNotEmpty()) return addrs
            }
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }

    private val client = OkHttpClient.Builder().protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory())
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns(dohDns))
        // Kept alive on purpose: from Iran the TCP and TLS handshakes to Cloudflare's edge cost
        // about half a second between them, measured, and that is most of a request's total time.
        // Reusing the connection across a session is the single biggest thing the client can do.
        .retryOnConnectionFailure(true)
        .build()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun endpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT

    fun setEndpoint(context: Context, url: String) {
        prefs(context).edit().putString(KEY_ENDPOINT, url.trimEnd('/')).apply()
    }

    // ---- enrolment -------------------------------------------------------------------------

    private data class Identity(val id: String, val secret: String)

    /**
     * The install's identity with the pool.
     *
     * Deliberately a random value of our own rather than ANDROID_ID or anything else that
     * identifies the device: the server needs to tell reporters apart -- five *distinct* users
     * condemning a config is the rule -- and nothing more than that.
     */
    private suspend fun identity(context: Context): Identity? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val id = p.getString(KEY_ID, null)
        val secret = p.getString(KEY_SECRET, null)
        if (!id.isNullOrBlank() && !secret.isNullOrBlank()) return@withContext Identity(id, secret)

        try {
            val req = Request.Builder()
                .url(endpoint(context) + "/enroll")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: return@withContext null
                if (!res.isSuccessful) {
                    Log.w(TAG, "enroll failed: HTTP ${res.code}")
                    return@withContext null
                }
                val json = JSONObject(body)
                val newId = json.optString("id")
                val newSecret = json.optString("secret")
                if (newId.isBlank() || newSecret.isBlank()) return@withContext null
                p.edit().putString(KEY_ID, newId).putString(KEY_SECRET, newSecret).apply()
                Identity(newId, newSecret)
            }
        } catch (e: Exception) {
            Log.w(TAG, "enroll failed: ${e.message}")
            null
        }
    }

    private fun sign(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun signed(
        context: Context,
        who: Identity,
        path: String,
        body: String?,
    ): Request.Builder {
        val ts = System.currentTimeMillis().toString()
        val sig = sign(who.secret, "${who.id}.$ts.${body ?: ""}")
        return Request.Builder()
            .url(endpoint(context) + path)
            .header("X-Install", who.id)
            .header("X-Ts", ts)
            .header("X-Sig", sig)
    }

    // ---- reading ---------------------------------------------------------------------------

    /**
     * A batch of configs other people have just proven, on this network.
     *
     * The server picks them: top-scoring for the caller's ISP, shuffled so five thousand people do
     * not all hammer the same twenty servers, spread across providers so one edge being blocked
     * cannot take the whole list, and with a couple of slots kept for unproven candidates so the
     * pool keeps growing. The client's job is only to race them.
     */
    suspend fun fetch(context: Context): List<QuickNode> = withContext(Dispatchers.IO) {
        val who = identity(context) ?: return@withContext emptyList()
        try {
            client.newCall(signed(context, who, "/list", null).get().build()).execute().use { res ->
                val body = res.body?.string() ?: return@withContext emptyList()
                if (!res.isSuccessful) {
                    Log.w(TAG, "list failed: HTTP ${res.code} $body")
                    return@withContext emptyList()
                }
                val servers = JSONObject(body).optJSONArray("servers") ?: return@withContext emptyList()
                val out = ArrayList<QuickNode>(servers.length())
                for (i in 0 until servers.length()) {
                    val o = servers.optJSONObject(i) ?: continue
                    val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: continue
                    QuickConnectRepository.parseNode(uri, SOURCE)?.let { node ->
                        o.optString("country").takeIf { it.isNotBlank() }?.let { node.country = it }
                        handedOut.add(uri)
                        out.add(node)
                    }
                }
                Log.d(TAG, "pool returned ${out.size} servers")
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "list failed: ${e.message}")
            emptyList()
        }
    }

    // ---- writing ---------------------------------------------------------------------------

    /** One measurement worth telling the pool about. */
    data class Result(val uri: String, val ok: Boolean, val ms: Int, val country: String? = null)

    /**
     * Sends a handful of results back.
     *
     * Deltas only, and capped: D1's free tier allows a hundred thousand row writes a day and the
     * limit is per account, so reporting every test a sweep runs would exhaust it at a few
     * thousand users. What is worth a write is a success (rare and valuable) and a failure that
     * contradicts a config the pool is currently recommending.
     *
     * Fire and forget -- the caller never waits on this, and a failure is not worth surfacing.
     */
    suspend fun report(context: Context, results: List<Result>) = withContext(Dispatchers.IO) {
        if (results.isEmpty()) return@withContext
        val who = identity(context) ?: return@withContext
        try {
            val payload = JSONObject().put("results", JSONArray().apply {
                results.take(12).forEach { r ->
                    put(JSONObject().apply {
                        put("uri", r.uri)
                        put("ok", r.ok)
                        put("ms", r.ms)
                        r.country?.let { put("country", it) }
                    })
                }
            }).toString()

            val req = signed(context, who, "/report", payload)
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) Log.w(TAG, "report failed: HTTP ${res.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "report failed: ${e.message}")
        }
    }

    /**
     * Send one crash report to the pool worker.
     *
     * Reuses this client rather than growing a second one, and that is the whole reason it lives
     * here: the pool endpoint is already enrolled, already signed, and already resolves through
     * [dohDns] on operators that poison `*.workers.dev`. A crash reporter that could not reach
     * its own server on an Iranian line would be a crash reporter in name only.
     *
     * [summary] is the crash's identity -- exception class plus the top frames -- and the server
     * groups by a hash of it, so four hundred reports of one bug read as one row with a count of
     * four hundred instead of four hundred stacks to compare by eye.
     *
     * Returns true only when the server accepted it, because the caller shows the user a result
     * and "sent" has to mean sent.
     */
    suspend fun reportCrash(
        context: Context,
        summary: String,
        body: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val who = identity(context) ?: return@withContext false
        try {
            val payload = JSONObject().apply {
                put("summary", summary.take(300))
                put("body", body.take(20_000))
                put("app", appVersion(context))
                put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                put("android", android.os.Build.VERSION.RELEASE)
            }.toString()
            val req = signed(context, who, "/crash", payload)
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) Log.w(TAG, "crash report failed: HTTP ${res.code}")
                res.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "crash report failed: ${e.message}")
            false
        }
    }

    /**
     * Send a diagnostic that is not a crash: something the app can see is wrong but cannot fix.
     *
     * The same signed `/crash` endpoint, deliberately, rather than a new one. The worker groups
     * reports by a hash of the summary, so a summary that starts `CFVERIFY ...` forms its own
     * groups in the existing `/crashes` dashboard, ordered by how many DISTINCT installs hit each
     * one -- which is exactly the question worth asking about a bug several users have reported and
     * nobody can reproduce. A new endpoint would have meant redeploying the worker before a single
     * report could arrive, and the answer is wanted from the users who are stuck *now*.
     *
     * [summary] must name the failure class and nothing per-user, or one bug arrives as fifty
     * groups. [body] must already be redacted; nothing here inspects it.
     */
    suspend fun reportDiagnostic(
        context: Context,
        summary: String,
        body: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val who = identity(context) ?: return@withContext false
        try {
            val payload = JSONObject().apply {
                put("summary", summary.take(300))
                put("body", body.take(20_000))
                put("app", appVersion(context))
                put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                put("android", android.os.Build.VERSION.RELEASE)
            }.toString()
            val req = signed(context, who, "/crash", payload)
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) Log.w(TAG, "diagnostic failed: HTTP ${res.code}")
                res.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "diagnostic failed: ${e.message}")
            false
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("?")

    // ---- the outbox --------------------------------------------------------------------------

    /**
     * URIs the pool handed this install during this session.
     *
     * A failure is only worth a row write when it contradicts something the pool is currently
     * recommending. A failure on one of the eleven thousand public configs is the expected case
     * and teaches nobody anything. Keeping the set here, rather than threading a flag down
     * through the scanner, means every measurement path gets that rule for free.
     */
    private val handedOut = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Matches the worker's own MAX_REPORT_ROWS; a larger batch is truncated there anyway. */
    private const val BATCH = 12

    /** This client's share of the server's 60 calls a day, leaving plenty for /list. */
    private const val MAX_SENDS_PER_DAY = 24

    /** How long the same verdict about the same config is worth repeating. */
    private const val OK_COOLDOWN_MS = 6L * 60 * 60 * 1000
    private const val BAD_COOLDOWN_MS = 60L * 60 * 1000

    /** Let a sweep settle before sending, so one run costs one request rather than fifty. */
    private const val QUIET_MS = 4_000L

    private const val KEY_SENT = "sent"
    private const val KEY_DAY = "send_day"
    private const val KEY_SENDS = "send_count"
    private const val SENT_CAP = 600
    private const val PENDING_CAP = 200

    private val pending = LinkedHashMap<String, Result>()
    private var sentCache: LinkedHashMap<String, Long>? = null

    private val outbox = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED
    )
    private var pump: kotlinx.coroutines.Job? = null

    /**
     * Tell the pool what one measurement showed.
     *
     * Called from [QuickScanner.measure], which every real test in the app goes through -- the
     * browse screen's country sweeps, the saved-list re-test, and the connect button's race.
     * That is deliberate, and it is the whole growth engine.
     *
     * The pool can only ever contain what somebody has actually proven. Until this existed the
     * only thing reporting was the connect button, which races servers the pool itself supplied:
     * a closed loop in which nothing new could get in, so the list stayed at whatever seeded it
     * no matter how many people used the app. The sweeps are where discovery really happens --
     * a user browsing Germany tests hundreds of catalogue configs against their own operator --
     * and that is exactly the knowledge everyone else needs.
     *
     * Cheap, non-blocking, and safe to call from a hot loop. Everything that decides whether a
     * result is worth a database write lives here, once:
     *
     *  - a success always, because a config that works is the scarce thing;
     *  - a failure only for a config the pool recommended, since that is the only failure that
     *    contradicts the list;
     *  - and neither if this install already said the same thing recently. Re-scanning the same
     *    catalogue rewrites the same rows for no new information, and D1's hundred thousand
     *    daily row writes are shared by every user of the app.
     */
    fun offer(context: Context, uri: String, ok: Boolean, ms: Int, country: String? = null) {
        if (uri.isBlank() || uri.length > 2000) return
        if (!ok && uri !in handedOut) return
        val app = context.applicationContext
        if (recentlySent(app, uri, ok)) return
        synchronized(pending) {
            if (pending.size >= PENDING_CAP && !pending.containsKey(uri)) return
            pending[uri] = Result(uri, ok, if (ok) ms else 0, country)
        }
        ensurePump(app)
        wake.trySend(Unit)
    }

    /**
     * One long-lived sender rather than a job per result.
     *
     * A debounce built out of cancelled jobs would eventually cancel a flush mid-request and
     * lose it; a conflated channel plus a quiet period cannot, because nothing is ever
     * interrupted -- offers that arrive while a batch is in flight simply wake the loop again.
     */
    private fun ensurePump(app: Context) {
        synchronized(this) {
            if (pump?.isActive == true) return
            pump = outbox.launch {
                for (ignored in wake) {
                    kotlinx.coroutines.delay(QUIET_MS)
                    drain(app)
                }
            }
        }
    }

    private suspend fun drain(app: Context) {
        repeat(3) {
            val batch = synchronized(pending) {
                if (pending.isEmpty()) return
                val take = pending.values.take(BATCH).toList()
                take.forEach { pending.remove(it.uri) }
                take
            }
            if (batch.isEmpty()) return
            if (!claimSend(app)) {
                // Out of budget for today. Dropped rather than held over: by tomorrow these
                // measurements describe a network that has moved on.
                synchronized(pending) { pending.clear() }
                Log.d(TAG, "daily report budget spent; dropping queue")
                return
            }
            Log.d(TAG, "reporting " + batch.size + " results, " + batch.count { it.ok } + " ok")
            report(app, batch)
            markSent(app, batch)
        }
    }

    /**
     * Six bytes of the digest: short enough to keep hundreds of them in preferences, and a
     * collision costs nothing worse than one config going unreported for an hour.
     */
    private fun keyOf(uri: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }

    @Synchronized
    private fun sentMap(app: Context): LinkedHashMap<String, Long> {
        sentCache?.let { return it }
        val map = LinkedHashMap<String, Long>()
        runCatching {
            val raw = prefs(app).getString(KEY_SENT, null)
            if (!raw.isNullOrBlank()) {
                val o = JSONObject(raw)
                for (k in o.keys()) map[k] = o.optLong(k, 0L)
            }
        }
        sentCache = map
        return map
    }

    /**
     * Has this install already told the pool this, recently enough that saying it again is just
     * noise? A verdict that CHANGED is always news -- a success after failures is a pardon, and
     * the pool needs it at once -- so only a repeat of the same answer is suppressed.
     */
    @Synchronized
    private fun recentlySent(app: Context, uri: String, ok: Boolean): Boolean {
        val v = sentMap(app)[keyOf(uri)] ?: return false
        if ((v > 0) != ok) return false
        val age = System.currentTimeMillis() - kotlin.math.abs(v)
        return age < if (ok) OK_COOLDOWN_MS else BAD_COOLDOWN_MS
    }

    @Synchronized
    private fun markSent(app: Context, batch: List<Result>) {
        val map = sentMap(app)
        val now = System.currentTimeMillis()
        for (r in batch) {
            val k = keyOf(r.uri)
            map.remove(k)                       // re-insert, so eviction is least-recently-used
            map[k] = if (r.ok) now else -now
        }
        while (map.size > SENT_CAP) map.remove(map.keys.first())
        runCatching {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            prefs(app).edit().putString(KEY_SENT, o.toString()).apply()
        }
    }


    /**
     * Report a config that is still carrying traffic a minute after it was connected.
     *
     * A config that answers a delay test and then dies thirty seconds later is broken as far as
     * the user is concerned, but the test already recorded it as a success. This is the second
     * opinion, and it is the strongest thing the pool ever hears -- so it must not depend on the
     * user standing still.
     *
     * It used to be a `LaunchedEffect` on the connect screen, which meant leaving that screen
     * within the minute cancelled it. Measured: connect, then tap Home, and the report never
     * arrives -- which is what most people do. Running it on the client's own scope and asking
     * the service directly whether the tunnel is still up makes navigation irrelevant.
     */
    fun confirmIfStillUp(context: Context, nodeId: String, uri: String, country: String?) {
        val app = context.applicationContext
        outbox.launch {
            kotlinx.coroutines.delay(60_000)
            val stillOurs =
                com.mlmvpn.scanner.MyVpnService.connectedNodeIdFlow.value == nodeId &&
                    com.mlmvpn.scanner.MyVpnService.connectionPhaseFlow.value ==
                    com.mlmvpn.scanner.MyVpnService.Phase.CONNECTED
            if (!stillOurs) return@launch
            Log.d(TAG, "confirming a tunnel that has held for a minute")
            report(app, listOf(Result(uri, true, 1, country)))
        }
    }

    /** The client's own daily cap, claimed before every request. */
    @Synchronized
    private fun claimSend(app: Context): Boolean {
        val p = prefs(app)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        val used = if (p.getString(KEY_DAY, "") == today) p.getInt(KEY_SENDS, 0) else 0
        if (used >= MAX_SENDS_PER_DAY) return false
        p.edit().putString(KEY_DAY, today).putInt(KEY_SENDS, used + 1).apply()
        return true
    }
}

