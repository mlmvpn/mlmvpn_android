package com.mlmvpn.scanner.engines.game.booster.crowd

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The app's side of the crowd service (worker-src/game-crowd): reading what other players on the
 * same operator learned, and -- for a sample of sessions, when the player has not turned sharing
 * off -- telling it what this one learned.
 *
 * Nothing on the boost's critical path waits for the network: the snapshot is read from disk, a
 * refresh runs in the background at most every [REFRESH_MS], and reports are queued and sent in
 * one batch at most every [FLUSH_MS]. With the service unreachable the booster works exactly as it
 * does alone.
 *
 * What is sent, per sampled session: game, region, plan, whether it worked, WARP measured/won,
 * typical ping, the kind of game on the line, and which anti-sanction DNS opened its sign-in. The
 * operator is read by the service from Cloudflare's own data; no address or device id is sent. The
 * install id is random and exists only for the service's per-install daily cap.
 */
object CrowdClient {

    private const val TAG = "GameCrowd"
    const val ENDPOINT = "https://crowd.redacted.invalid"

    /** Signs reports. Shipped in the app, so it raises the bar and no more; the service caps writes. */
    private const val APP_KEY = "0000000000000000000000000000000000000000000000000000000000000000"

    /** The service's Ed25519 public key: a snapshot that does not verify against it is ignored. */
    val PUBLIC_KEY: ByteArray = CrowdSnapshot.decodeBase64("P/1bzrQly13Bcb2eDa4pGERBbwQGc5UjBt/qeIIy9LI=")!!

    private const val PREFS = "gb_crowd"
    private const val REFRESH_MS = 12 * 60 * 60 * 1000L
    /** A network whose operator is not known yet is asked sooner, but not on every boost. */
    private const val UNKNOWN_ASN_RETRY_MS = 30 * 60 * 1000L
    private const val FLUSH_MS = 20 * 60 * 60 * 1000L
    private const val MAX_QUEUE = 24
    private const val MAX_PER_POST = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val rng = SecureRandom()

    private val dohDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            // *.workers.dev is poisoned by some operators' resolvers: DNS over HTTPS to a resolver's
            // address first, the system resolver second (same as the pool client).
            val viaDoh = runCatching {
                kotlinx.coroutines.runBlocking { com.mlmvpn.scanner.utils.DomainPreResolver.resolve(hostname) }
            }.getOrDefault(emptyList()).mapNotNull { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
            return viaDoh.ifEmpty { okhttp3.Dns.SYSTEM.lookup(hostname) }
        }
    }

    private val http by lazy {
        OkHttpClient.Builder().protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns(dohDns))
            .build()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "game_booster").apply { mkdirs() }
    private fun snapFile(ctx: Context) = File(dir(ctx), "crowd_snapshot.json")
    private fun queueFile(ctx: Context) = File(dir(ctx), "crowd_queue.json")

    // ── the player's choice ──────────────────────────────────────────────────────────────────

    /** «کمک به بهتر شدن بوستر»: on unless the player turned it off. Reading is not affected. */
    fun sharing(ctx: Context): Boolean = prefs(ctx).getBoolean("share", true)

    fun setSharing(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("share", on).apply()
        if (!on) queueFile(ctx).delete()
    }

    private fun installId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("id", null)?.takeIf { it.length == 32 }?.let { return it }
        val id = ByteArray(16).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p.edit().putString("id", id).apply()
        return id
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────

    /** The operator last seen on [networkKey], if the service has said. */
    fun asnFor(ctx: Context, networkKey: String): Int? =
        prefs(ctx).getInt("asn_$networkKey", 0).takeIf { it > 0 }

    /**
     * What the crowd says for the network the phone is on, from the snapshot on disk (verified
     * again on every read -- a file on disk is not trusted more than one on the wire).
     */
    fun advice(ctx: Context, networkKey: String): CrowdAdvice {
        val o = try { JSONObject(snapFile(ctx).readText()) } catch (e: Exception) { return CrowdAdvice.NONE }
        val all = o.optJSONObject("all")?.let { CrowdSnapshot.open(it.optString("b"), it.optString("s"), PUBLIC_KEY) }
        val asn = asnFor(ctx, networkKey)
        val mine = asn?.let { a ->
            o.optJSONObject("mines")?.optJSONObject(a.toString())?.let { CrowdSnapshot.open(it.optString("b"), it.optString("s"), PUBLIC_KEY) }
        }
        return CrowdAdvice(all, mine, asn)
    }

    /** Refresh the snapshot in the background when it is old, or when this network's operator is unknown. */
    fun refreshSoon(ctx: Context, networkKey: String) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val last = p.getLong("fetched_at", 0L)
        val now = System.currentTimeMillis()
        val stale = now - last > REFRESH_MS
        val unknown = networkKey.isNotEmpty() && asnFor(app, networkKey) == null && now - last > UNKNOWN_ASN_RETRY_MS
        if (!stale && !unknown) return
        scope.launch { refresh(app, networkKey) }
    }

    private suspend fun refresh(ctx: Context, networkKey: String) = lock.withLock {
        try {
            val body = http.newCall(Request.Builder().url("$ENDPOINT/s").get().build()).execute().use { res ->
                if (!res.isSuccessful) return@withLock
                res.body.string()
            }
            val j = JSONObject(body)
            val asn = j.optInt("asn", 0)
            val p = prefs(ctx)
            p.edit().putLong("fetched_at", System.currentTimeMillis()).apply()
            if (asn > 0 && networkKey.isNotEmpty()) p.edit().putInt("asn_$networkKey", asn).apply()
            // Keep only what verifies; a slice for another operator the phone was on stays.
            val cur = try { JSONObject(snapFile(ctx).readText()) } catch (e: Exception) { JSONObject() }
            j.optJSONObject("all")?.let { a ->
                if (CrowdSnapshot.verify(a.optString("b"), a.optString("s"), PUBLIC_KEY)) cur.put("all", a)
            }
            j.optJSONObject("mine")?.let { m ->
                if (asn > 0 && CrowdSnapshot.verify(m.optString("b"), m.optString("s"), PUBLIC_KEY)) {
                    val mines = cur.optJSONObject("mines") ?: JSONObject()
                    mines.put(asn.toString(), m)
                    // A handful of operators at most: home Wi-Fi, one or two SIMs.
                    while (mines.length() > 6) mines.remove(mines.keys().next())
                    cur.put("mines", mines)
                }
            }
            writeAtomic(snapFile(ctx), cur.toString())
            Log.i(TAG, "snapshot refreshed asn=$asn")
        } catch (e: Exception) {
            Log.d(TAG, "snapshot refresh failed: ${e.message}")
        }
    }

    // ── writing ──────────────────────────────────────────────────────────────────────────────

    /** Whether a session starting now is one of the sample the crowd service asked for. */
    fun sampleThisSession(ctx: Context, advice: CrowdAdvice): Boolean =
        sharing(ctx) && rng.nextDouble() < advice.reportRate()

    fun queue(ctx: Context, row: JSONObject) {
        if (!sharing(ctx)) return
        val app = ctx.applicationContext
        scope.launch {
            lock.withLock {
                val q = readQueue(app)
                q.put(row)
                while (q.length() > MAX_QUEUE) q.remove(0)
                writeAtomic(queueFile(app), q.toString())
            }
            flushIfDue(app)
        }
    }

    /** Send the queue, at most once per [FLUSH_MS]; rows the service refused as invalid are dropped. */
    suspend fun flushIfDue(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!sharing(ctx)) return@withContext
        val p = prefs(ctx)
        if (!force && System.currentTimeMillis() - p.getLong("flushed_at", 0L) < FLUSH_MS) return@withContext
        lock.withLock {
            val q = readQueue(ctx)
            if (q.length() == 0) return@withLock
            val batch = JSONArray()
            for (i in 0 until minOf(MAX_PER_POST, q.length())) batch.put(q.get(i))
            val body = JSONObject().put("v", 1).put("rows", batch).toString()
            val id = installId(ctx)
            val ts = System.currentTimeMillis().toString()
            val sig = hmacHex(APP_KEY, "$id.$ts.$body")
            try {
                val code = http.newCall(
                    Request.Builder().url("$ENDPOINT/r")
                        .header("x-install", id).header("x-ts", ts).header("x-sig", sig)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute().use { res ->
                    res.body.string().let { txt ->
                        runCatching { JSONObject(txt).optDouble("rate") }.getOrNull()?.takeIf { !it.isNaN() }
                            ?.let { p.edit().putFloat("rate_hint", it.toFloat()).apply() }
                    }
                    res.code
                }
                // Sent, or refused for good (bad rows, over the day's cap): either way these rows are done.
                if (code in 200..499) {
                    val rest = JSONArray()
                    for (i in batch.length() until q.length()) rest.put(q.get(i))
                    writeAtomic(queueFile(ctx), rest.toString())
                    p.edit().putLong("flushed_at", System.currentTimeMillis()).apply()
                }
                Log.i(TAG, "report sent: ${batch.length()} rows, HTTP $code")
            } catch (e: Exception) {
                Log.d(TAG, "report failed, kept for later: ${e.message}")
            }
        }
    }

    private fun readQueue(ctx: Context): JSONArray =
        try { JSONArray(queueFile(ctx).readText()) } catch (e: Exception) { JSONArray() }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun hmacHex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
