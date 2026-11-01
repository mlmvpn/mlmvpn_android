package com.mlmvpn.core.warp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mlmvpn.core.tunnel.ConnectionLog
import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.update.UpdateNet
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * WARP identities made through the user's own Worker — the Gemini trick, for WireGuard,
 * WARP-in-WARP and MASQUE.
 *
 * Iran filters `api.cloudflareclient.com`, the one host that hands out a WARP identity. The tunnel
 * core tries it directly and then over its own camouflaged route, and both now die at the ISP — so
 * no identity is made and the three transports never start, although the data path to Cloudflare's
 * edge they would then use is still open. "Fix the identity and the connection follows."
 *
 * The fix moves only that one filtered hop:
 *
 *  1. [deploy] puts `assets/warp_id_worker.js` on the user's Cloudflare account, behind a random
 *     key. It forwards the WARP registration API and nothing else.
 *  2. [ensure] runs before the core prepares a WARP transport. When the identity file the core
 *     would load is missing, it registers the device THROUGH THE WORKER — and for MASQUE also
 *     enrolls the P-256 key and makes the self-signed certificate, exactly as the core does — and
 *     writes the file in the core's own format (aether `config.rs`, `PersistedIdentity`, TOML).
 *  3. The core starts, finds "an existing warp identity", and never has to reach the filtered API.
 *
 * An identity the core already has is never touched. Anything that fails here is only logged: the
 * core then tries its own routes exactly as it did before, so this can make things work but
 * cannot make them worse.
 */
object WarpIdRelay {

    private const val TAG = "WarpIdRelay"
    private const val PREFS = "warp_id_relay"
    private const val API_VERSION = "v0a4471"
    private const val UA = "WARP for Android"
    private const val CLIENT_VERSION = "a-6.35-4471"
    private const val CERT_LIFETIME_DAYS = 365L

    private val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()
    private val cancelWatch = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "identity-request-cancel").apply { isDaemon = true }
    }

    @Volatile private var app: Context? = null

    /** From Application.onCreate, so the core's prepare step can reach this without a context. */
    fun bind(context: Context) { app = context.applicationContext }
    /** Sealed off the caller's thread: a stop pressed on the screen waited on the Keystore (ANR #662). */
    fun onCoreStop() {
        val ctx = app ?: return
        Thread({
            runCatching {
                // Not under a session that has started meanwhile: its core is loading these files.
                com.mlmvpn.scanner.data.CfIdentityFiles.sealDirectory(ctx, ctx.filesDir, maySeal = {
                    !com.mlmvpn.core.tunnel.TunnelEngine.preparing && !runCatching { com.mlmvpn.core.tunnel.TunnelEngine.isRunning() }.getOrDefault(false)
                })
            }
        }, "seal-core-identities").start()
    }

    fun ensure(configJson: String) {
        val ctx = app ?: return
        try {
            ensure(ctx, configJson)
        } catch (e: IllegalStateException) {
            if (isBlocked(e.message)) throw e
            if (e.message?.startsWith("secure_") == true) throw IllegalStateException(BLOCKED + fa("ذخیره‌سازی امن دستگاه در دسترس نیست.", "Secure device storage is unavailable."))
            Log.w(TAG, "ensure failed: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) throw e
            Log.w(TAG, "ensure failed: ${e.javaClass.simpleName}")
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun relayKey(context: Context): String? {
        val p = prefs(context)
        p.getString("key_sealed", null)?.let { return com.mlmvpn.scanner.data.SecureStore.open(it) }
        val legacy = p.getString("key", null) ?: return null
        val sealed = com.mlmvpn.scanner.data.SecureStore.seal(legacy) ?: return null
        if (!p.edit().putString("key_sealed", sealed).remove("key").commit()) return null
        return legacy
    }

    /** The relay's base address (`https://x.y.workers.dev/<key>`), or null when not set up. */
    fun base(context: Context): String? {
        val p = prefs(context)
        val url = p.getString("url", null)?.trimEnd('/') ?: return null
        val key = relayKey(context) ?: return null
        return "$url/$key"
    }

    /**
     * Set up at all. Only looks at what is stored -- no Keystore call: the screens ask this while they
     * draw, and opening the sealed key on the main thread froze the WireGuard tab (ANR #870).
     */
    fun isReady(context: Context): Boolean = prefs(context).let { p ->
        p.getString("url", null) != null && (p.contains("key_sealed") || p.contains("key"))
    }

    /**
     * The relay's build. Bump it whenever assets/warp_id_worker.js changes: a relay on an older
     * build is offered the update, and one on this build is left alone -- tapping the row used to
     * upload the worker again every time.
     */
    const val VERSION = 1

    /** The build deployed, 0 when none. A relay deployed before builds were kept is build 1. */
    fun deployedVersion(context: Context): Int =
        if (!isReady(context)) 0 else prefs(context).getInt("version", 1)

    fun upToDate(context: Context): Boolean = deployedVersion(context) >= VERSION

    fun accountId(context: Context): String? = prefs(context).getString("account", null)

    // ── putting the Worker on the account ─────────────────────────────────────────────────────

    /**
     * Deploy (or update) the relay on [account]. Returns a sentence for the user.
     *
     * A second deploy keeps the script name and the key, so it replaces the code in place.
     */
    fun deploy(context: Context, account: CloudAccount, onStep: (String) -> Unit = {}): Pair<Boolean, String> {
        // Deploying this worker is itself a call to api.cloudflare.com, so it takes the same route the
        // rest of the app uses for Cloudflare: a proven edge (WorkerRoute.dns) with the ClientHello
        // split past the name filter (CfFront), for the networks where the API host is cut by name.
        val client = OkHttpClient.Builder()
            .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns())
            .socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory())
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        val headers = CloudAuth.headers(account)
        val api = "https://api.cloudflare.com/client/v4/accounts/${account.accountId}"
        return try {
            onStep(fa("بررسی زیردامنه…", "Checking the subdomain…"))
            val sub = client.newCall(Request.Builder().url("$api/workers/subdomain").headers(headers).get().build()).execute().use { r ->
                runCatching { JSONObject(r.body?.string() ?: "").optJSONObject("result")?.optString("subdomain") }.getOrNull().orEmpty()
            }
            if (sub.isBlank()) return false to fa("این حساب هنوز زیردامنهٔ workers.dev ندارد.", "This account has no workers.dev subdomain yet.")

            val p = prefs(context)
            val sameAccount = p.getString("account", null) == account.accountId
            val name = p.getString("script", null)?.takeIf { sameAccount }
                ?: (com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-wid")
            val key = relayKey(context)?.takeIf { sameAccount }
                ?: ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val sealedKey = com.mlmvpn.scanner.data.SecureStore.seal(key)
                ?: throw IllegalStateException("secure_storage_unavailable")

            onStep(fa("بارگذاری ورکر…", "Uploading the worker…"))
            val script = com.mlmvpn.scanner.store.StoreFiles.readText(context, "warp_id_worker.js")
            val meta = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2024-09-23")
                put("bindings", JSONArray().put(JSONObject().put("type", "secret_text").put("name", "KEY").put("text", key)))
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", meta.toString().toRequestBody("application/json".toMediaType()))
                .addFormDataPart("worker.js", "worker.js", script.toRequestBody("application/javascript+module".toMediaType()))
                .build()
            client.newCall(Request.Builder().url("$api/workers/scripts/$name").headers(headers).put(body).build()).execute().use { r ->
                if (!r.isSuccessful) return false to fa("بارگذاری ورکر رد شد (HTTP ${r.code}).", "Uploading the worker failed (HTTP ${r.code}).")
            }
            onStep(fa("فعال کردن آدرس…", "Enabling the address…"))
            client.newCall(
                Request.Builder().url("$api/workers/scripts/$name/subdomain").headers(headers)
                    .post("{\"enabled\":true}".toRequestBody("application/json".toMediaType())).build()
            ).execute().use { r ->
                if (!r.isSuccessful) return false to fa("فعال کردن آدرس ورکر نشد (HTTP ${r.code}).", "Could not enable the worker address (HTTP ${r.code}).")
            }
            p.edit()
                .putString("url", "https://$name.$sub.workers.dev")
                .putString("key_sealed", sealedKey).remove("key")
                .putString("script", name)
                .putString("account", account.accountId)
                .putInt("version", VERSION)
                .apply()
            true to fa(
                "آماده است. از این به بعد هویت وایرگارد، وارپ در وارپ و ماسک از راه ورکر خودتان ساخته می‌شود.",
                "Ready. From now on the WireGuard, WARP-in-WARP and MASQUE identity is made through your own worker.",
            )
        } catch (e: Exception) {
            Log.w(TAG, "deploy failed: ${e.javaClass.simpleName}")
            false to fa("راه‌اندازی ورکر انجام نشد.", "Worker setup failed.")
        }
    }

    // ── making the identity before the core needs it ──────────────────────────────────────────

    /**
     * Make sure every identity file the core is about to load for this config exists.
     *
     * Called with the exact JSON handed to `TunnelEngine.prepare`, so the paths are the core's own:
     * `config_path` for WireGuard, `<base>-masque` for MASQUE, and a `-secondary` beside either for
     * the inner leg of the nested modes (aether `lib.rs`, derive_sibling_path).
     */
    fun ensure(context: Context, configJson: String) {
        check(com.mlmvpn.scanner.data.SecureStore.available) { "secure_identity_unavailable" }
        val relay = base(context)
        val base = relay ?: DIRECT_BASE.takeIf {
            com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context)
                .saved(com.mlmvpn.scanner.engines.cfnet.CfStage.IDENTITY) != null
        }
        val cfg = runCatching { JSONObject(configJson) }.getOrNull() ?: return
        // A Zero Trust organization registers with its own login; that path is not this one.
        if (cfg.optString("team").isNotBlank()) return
        val configPath = cfg.optString("config_path").ifBlank { return }
        val protocol = cfg.optString("protocol").lowercase(Locale.US)

        val targets = mutableListOf<Pair<String, Boolean>>()   // path, masque?
        when {
            protocol == "gool" || protocol.contains("warp") -> {
                val primary = cfg.optString("wireguard_config_path").ifBlank { configPath }
                targets += primary to false
                targets += sibling(primary, "secondary") to false
            }
            protocol.contains("wire") || protocol == "wg" -> {
                targets += cfg.optString("wireguard_config_path").ifBlank { configPath } to false
            }
            protocol.contains("masque") || protocol == "mim" -> {
                val primary = cfg.optString("masque_config_path").ifBlank { sibling(configPath, "masque") }
                targets += primary to true
                if (protocol != "masque") targets += sibling(primary, "secondary") to true
            }
            else -> return
        }

        var relayError: String? = null
        var missing = false
        for ((path, masque) in targets) {
            val file = File(path)
            com.mlmvpn.scanner.data.CfIdentityFiles.restore(context, file)
            if (file.exists() && file.length() > 0) continue
            if (base == null) { missing = true; continue }
            try {
                ConnectionLog.record("Identity: making ${if (masque) "a MASQUE" else "a WARP"} identity through the selected route")
                val toml = provision(context, base, masque)
                file.parentFile?.mkdirs()
                com.mlmvpn.scanner.data.CfIdentityFiles.save(context, file, toml, materialize = true)
                markSource(context, file.name, if (relay != null) VIA_WORKER else VIA_DIRECT)
                ConnectionLog.record("Identity: saved to ${file.name} through the selected route")
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("identity_cancelled")
                relayError = e.javaClass.simpleName
                missing = true
                ConnectionLog.record("Identity: the worker route failed ($relayError)")
                Log.w(TAG, "provision through the relay failed: ${e.javaClass.simpleName}")
            }
        }
        if (!missing) return

        // Something still has no identity, so the core would go to the WARP API itself - and on a
        // filtered line it spends two minutes there, deaf to cancel, before failing without saying
        // why. Ask the same question in a few seconds instead, and stop with a clear answer.
        if (directReachable(context)) {
            ConnectionLog.record("Identity: the WARP API answers directly; the core will register")
            return
        }
        // Filtered by its name: Aether registers with the name encrypted (ECH), in the core's format.
        if (provisionOverEch(context, targets)) return
        ConnectionLog.record("Identity: the WARP API is filtered on this network")
        throw IllegalStateException(
            BLOCKED + if (base == null) {
                fa("ساخت هویت وارپ روی این اینترنت فیلتر است.",
                    "Making a WARP identity is filtered on this network.")
            } else {
                fa("هویت وارپ نه مستقیم ساخته شد و نه از راه ورکر",
                    "The WARP identity could not be made directly or through the worker") +
                    (relayError?.let { " ($it)" } ?: "")
            }
        )
    }

    /**
     * Make the identities [targets] still lacks with Aether over ECH ([com.mlmvpn.core.aether.AetherRegister]).
     * [targets] is [ensure]'s list: a primary and, for the nested modes, its `-secondary`.
     */
    private fun provisionOverEch(context: Context, targets: List<Pair<String, Boolean>>): Boolean {
        val wanted = LinkedHashMap<File, com.mlmvpn.core.aether.AetherRegister.Role>()
        for ((path, masque) in targets) {
            val file = File(path)
            if (file.exists() && file.length() > 0) continue
            val inner = path.substringAfterLast('/').contains("-secondary")
            wanted[file] = when {
                masque && inner -> com.mlmvpn.core.aether.AetherRegister.Role.MASQUE_INNER
                masque -> com.mlmvpn.core.aether.AetherRegister.Role.MASQUE
                inner -> com.mlmvpn.core.aether.AetherRegister.Role.WG_INNER
                else -> com.mlmvpn.core.aether.AetherRegister.Role.WG
            }
        }
        if (wanted.isEmpty()) return true
        ConnectionLog.record("Identity: the WARP API is filtered by name here; registering over ECH")
        val made = com.mlmvpn.core.aether.AetherRegister.register(context, wanted.values.toSet()) { ConnectionLog.record(it) }
        if (made == null) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("identity_cancelled")
            ConnectionLog.record("Identity: ECH did not get through either")
            return false
        }
        for ((file, role) in wanted) {
            file.parentFile?.mkdirs()
            com.mlmvpn.scanner.data.CfIdentityFiles.save(context, file, made.getValue(role), materialize = true)
            markSource(context, file.name, VIA_ECH)
        }
        return true
    }

    /**
     * Does `api.cloudflareclient.com` answer at all from here? Any HTTP status counts - only a
     * connection or TLS failure means filtered. A few seconds, where the core takes two minutes.
     */
    fun directReachable(context: Context): Boolean = try {
        val req = Request.Builder().url("https://api.cloudflareclient.com/$API_VERSION/reg/probe")
            .header("User-Agent", UA).get().build()
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS).build()
            .newCall(req).execute().use { true }
    } catch (e: Exception) {
        Log.i(TAG, "direct WARP API probe failed: ${e.message}")
        false
    }

    // -- how each identity was made, for the transport screens --------------------------------

    const val BLOCKED = "IDENTITY_BLOCKED|"
    const val VIA_WORKER = "worker"
    const val VIA_DIRECT = "direct"
    const val VIA_FRONT = "front"
    /** Registered by Aether with the API's name encrypted (ECH). */
    const val VIA_ECH = "ech"

    /** A failure message this class raised; [readable] is the part for the user. */
    fun isBlocked(message: String?): Boolean = message?.startsWith(BLOCKED) == true
    fun readable(message: String?): String? = message?.removePrefix(BLOCKED)

    private fun markSource(context: Context, fileName: String, via: String) {
        prefs(context).edit().putString("src_$fileName", via).putLong("at_$fileName", System.currentTimeMillis()).apply()
    }

    /** After the core prepared: a file that appeared without us was made by the core, directly. */
    fun afterPrepare(configJson: String) {
        val ctx = app ?: return
        runCatching {
            val dir = File(JSONObject(configJson).optString("config_path")).parentFile ?: return
            for (name in listOf("tunnel.toml", "tunnel-secondary.toml", "tunnel-masque.toml", "tunnel-masque-secondary.toml")) {
                val f = File(dir, name)
                if (f.exists() && prefs(ctx).getString("src_$name", null) == null) markSource(ctx, name, VIA_DIRECT)
            }
            com.mlmvpn.scanner.data.CfIdentityFiles.sealDirectory(ctx, dir, removePlain = false)
        }
    }

    data class IdentityState(val made: Boolean, val via: String?, val at: Long)

    /** The identity a transport uses, by its protocol value: `masque`, `wireguard` or `gool`. */
    fun identityState(context: Context, protocol: String): IdentityState {
        val name = if (protocol.contains("masque")) "tunnel-masque.toml" else "tunnel.toml"
        val f = File(context.filesDir, name)
        // Between connects the identity is kept only as its sealed copy (`<name>.sealed`; the plain
        // file is removed when the core stops). Looking at the plain file alone showed "not made yet"
        // after every disconnect, though the same identity is restored and reused on the next connect.
        val sealed = File(context.filesDir, "$name.sealed")
        val kept = (f.exists() && f.length() > 0) || (sealed.exists() && sealed.length() > 0)
        val p = prefs(context)
        return IdentityState(
            made = kept,
            via = if (kept) p.getString("src_$name", null) else null,
            at = p.getLong("at_$name", 0L).takeIf { it > 0 } ?: if (kept) maxOf(f.lastModified(), sealed.lastModified()) else 0L,
        )
    }

    private fun sibling(base: String, suffix: String): String {
        val dirEnd = maxOf(base.lastIndexOf('/'), base.lastIndexOf('\\')) + 1
        val dot = base.substring(dirEnd).lastIndexOf('.')
        return if (dot >= 0) {
            val at = dirEnd + dot
            base.substring(0, at) + "-" + suffix + base.substring(at)
        } else "$base-$suffix"
    }

    /** Register one device through the relay and return the identity file's text. */
    private fun provision(context: Context, base: String, masque: Boolean): String =
        provisionFull(context, base, masque, enableWarp = false).toml

    /** A registration and what came of it. */
    data class Provisioned(val toml: String, val deviceId: String, val warpEnabled: Boolean)

    /** Where «وارپ»'s own identity was made, for its screen. */
    data class Standalone(val toml: String, val deviceId: String, val via: String)

    /**
     * «وارپ»'s own identity -- separate from the three core transports' -- registered directly
     * when Cloudflare's API answers from here and through the user's Worker when it is filtered.
     *
     * And switched ON. A fresh registration comes back with `warp_enabled: false`; the edges then
     * complete the WireGuard handshake and let nothing through. That cost six sweeps and sixty
     * endpoints on the desktop before the answer was read closely enough to see it. Cloudflare's
     * own client PATCHes it on right after registering, and so does this.
     */
    fun registerStandalone(context: Context): Standalone {
        val direct = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context).saved(com.mlmvpn.scanner.engines.cfnet.CfStage.IDENTITY) != null || directReachable(context)
        // Filtered by its name (Iran, 2026-10-07): Aether registers with the name encrypted (ECH) in
        // seconds, where the worker needs setting up and the camouflaged route costs ~50 s of timeouts.
        if (!direct) {
            com.mlmvpn.core.aether.AetherRegister.register(context, setOf(com.mlmvpn.core.aether.AetherRegister.Role.WG)) { ConnectionLog.record(it) }
                ?.get(com.mlmvpn.core.aether.AetherRegister.Role.WG)
                ?.let { toml ->
                    val id = Regex("""(?m)^device_id = "([^"]+)"""").find(toml)?.groupValues?.get(1).orEmpty()
                    if (id.isNotBlank()) return Standalone(toml, id, VIA_ECH)
                }
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("identity_cancelled")
        }
        val routes = buildList {
            if (direct) add(DIRECT_BASE to VIA_DIRECT)
            // The user's own Worker before the camouflaged route, when there is one. Measured on
            // the phone 2026-09-27 (Iran, Wi-Fi): api.cloudflareclient.com as the SNI gets no
            // reply on any Cloudflare address, whole or cut into 1-29 byte segments -- the
            // filter reassembles -- while www.cloudflare.com on the same addresses answers in
            // under a second; and Cloudflare refuses a mismatched SNI/Host with 403. The front
            // then costs four read timeouts (~50 s) before the route that works.
            base(context)?.let { add(it to VIA_WORKER) }
            // The camouflaged route the core gives MASQUE and WireGuard (WarpApiFront): still
            // tried, since a network that reads only single packets lets it through.
            add(FRONT_BASE to VIA_FRONT)
        }
        var last: Exception? = null
        // The first route on which Cloudflare itself answered. That answer says more than the
        // timeout of whatever route came after it: on the phone the Worker got 429 and the
        // screen showed the front's «Read timed out» instead.
        var answered: String? = null
        for ((b, via) in routes) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("identity_cancelled")
            try {
                val manager = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context)
                val selected = manager.saved(com.mlmvpn.scanner.engines.cfnet.CfStage.IDENTITY)
                val p = provisionFull(context, b, masque = false, enableWarp = true)
                if (!p.warpEnabled) throw IllegalStateException(
                    fa("کلادفلر وارپ را برای این حساب فعال نکرد.", "Cloudflare did not enable WARP for this account."))
                manager.report(com.mlmvpn.scanner.engines.cfnet.CfStage.IDENTITY, selected, manager.profile(), com.mlmvpn.scanner.engines.cfnet.ValidationResult.pass(0, "identity=authenticated_warp_enabled"))
                return Standalone(p.toml, p.deviceId, selected?.route?.carrier?.key ?: via)
            } catch (e: Exception) {
                last = e
                if (answered == null && e.message?.startsWith("HTTP ") == true) answered = e.message
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("identity_cancelled")
                Log.w(TAG, "standalone registration via $via failed: ${e.javaClass.simpleName}")
            }
        }
        // The 429 is short-lived (the phone, 2026-09-27: refused at 10:58 and 10:59, made at
        // 11:01), and the front's four timeouts just spent ~50 s. One more ask is worth it.
        val worker = base(context)
        if (worker != null && answered?.startsWith("HTTP 429") == true) {
            try {
                val p = provisionFull(context, worker, masque = false, enableWarp = true)
                if (p.warpEnabled) return Standalone(p.toml, p.deviceId, VIA_WORKER)
            } catch (e: Exception) {
                Log.w(TAG, "standalone registration via worker, second ask, failed", e)
            }
        }
        val reason = when {
            // Measured 2026-09-27: the Worker is healthy and Cloudflare's WARP API answers its
            // registration with 429 / error 1015 -- a rate limit on requests that come from
            // Workers, not on this user.
            answered?.startsWith("HTTP 429") == true -> fa(
                "کلادفلر ساخت حساب وارپ از راه ورکر را محدود کرده است (خطای ۱۰۱۵)؛ ساخت مستقیم هم روی این اینترنت فیلتر است",
                "Cloudflare rate-limits WARP registration through Workers (error 1015), and the direct route is filtered here")
            else -> fa("هویت وارپ ساخته نشد", "The WARP identity could not be made") +
                ((answered ?: last?.message)?.let { " ($it)" } ?: "")
        }
        throw IllegalStateException(BLOCKED + reason)
    }

    /**
     * A WARP identity registered THROUGH [proxy]: a local SOCKS port whose traffic leaves from inside
     * Cloudflare (a WARP session that already carries data, as the Amnezia carrier borrows one).
     * From there the API is never filtered, so this works on lines where every route
     * [registerStandalone] knows is closed. Switched on, as that one does.
     */
    fun registerThrough(context: Context, proxy: java.net.Proxy): Standalone {
        val p = provisionFull(context, DIRECT_BASE, masque = false, enableWarp = true, proxy = proxy)
        if (!p.warpEnabled) throw IllegalStateException(
            fa("کلادفلر وارپ را برای این حساب فعال نکرد.", "Cloudflare did not enable WARP for this account."))
        return Standalone(p.toml, p.deviceId, VIA_PROXY)
    }

    const val VIA_PROXY = "proxy"

    private const val DIRECT_BASE = "https://api.cloudflareclient.com"
    /** Not a URL: [call] sends these over [WarpApiFront] instead of OkHttp. */
    private const val FRONT_BASE = "front:"

    private fun provisionFull(context: Context, base: String, masque: Boolean, enableWarp: Boolean, proxy: java.net.Proxy? = null): Provisioned {
        // Shadows the member for the body below: through [proxy] when one is given.
        fun call(context: Context, base: String, method: String, path: String, body: JSONObject, bearer: String?): JSONObject =
            if (proxy != null) callThrough(proxy, base, method, path, body, bearer)
            else this@WarpIdRelay.call(context, base, method, path, body, bearer)
        val wg = WarpCrypto.generateKeyPair()
        val reg = JSONObject().apply {
            put("key", wg.publicKeyBase64)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date()))
            put("model", "PC")
            put("serial_number", ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) })
            put("os_version", "")
            put("key_type", "curve25519")
            put("tunnel_type", "wireguard")
            put("locale", "en_US")
        }
        val account = call(context, base, "POST", "/$API_VERSION/reg", reg, null)
        val deviceId = account.optString("id").ifBlank { throw IllegalStateException("no device id in the answer") }
        val token = account.optString("token").ifBlank { throw IllegalStateException("no token in the answer") }
        var warpEnabled = account.optBoolean("warp_enabled", false)
        if (enableWarp && !warpEnabled) {
            val patched = call(context, base, "PATCH", "/$API_VERSION/reg/$deviceId", JSONObject().put("warp_enabled", true), token)
            warpEnabled = patched.optBoolean("warp_enabled", false)
        }
        val config = account.optJSONObject("config") ?: throw IllegalStateException("no config in the answer")
        val addresses = config.optJSONObject("interface")?.optJSONObject("addresses")
        val peer = config.optJSONArray("peers")?.optJSONObject(0)?.optString("public_key").orEmpty()
        if (peer.isBlank()) throw IllegalStateException("no peer in the answer")
        val clientId = config.optString("client_id").takeIf { id ->
            runCatching { Base64.decode(id, Base64.DEFAULT).size == 3 }.getOrDefault(false)
        }.orEmpty()

        var certPem = ""
        var keyPem = ""
        var issuedAt = 0L
        if (masque) {
            val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val kp = kpg.generateKeyPair()
            val enroll = JSONObject().apply {
                put("key", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))   // SPKI DER
                put("key_type", "secp256r1")
                put("tunnel_type", "masque")
            }
            call(context, base, "PATCH", "/$API_VERSION/reg/$deviceId", enroll, token)
            val now = System.currentTimeMillis()
            keyPem = pem("PRIVATE KEY", kp.private.encoded)                              // PKCS#8
            certPem = pem("CERTIFICATE", selfSigned(kp, now))
            issuedAt = now / 1000
        }

        // aether config.rs PersistedIdentity, as toml::to_string_pretty writes it.
        val toml = buildString {
            line("device_id", deviceId)
            line("access_token", token)
            line("cert_pem", certPem)
            line("key_pem", keyPem)
            append("cert_issued_at = ").append(issuedAt).append('\n')
            line("ipv4", addresses?.optString("v4").orEmpty())
            line("ipv6", addresses?.optString("v6").orEmpty())
            line("wg_private_key", wg.privateKeyBase64)
            line("wg_peer_public_key", peer)
            line("client_id", clientId)
            line("organization", "")
            line("gateway_proxy", "")
            line("assigned_endpoint", "")
        }
        return Provisioned(toml, deviceId, warpEnabled)
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append(" = ").append(tomlString(value)).append('\n')
    }

    /** A TOML basic string: quotes, backslashes and newlines escaped (the PEMs span lines). */
    private fun tomlString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    /** [call] over [proxy] alone: no learned strategy, no front -- the proxy is the way in. */
    private fun callThrough(proxy: java.net.Proxy, base: String, method: String, path: String, body: JSONObject, bearer: String?): JSONObject {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("identity_cancelled")
        val client = OkHttpClient.Builder().proxy(proxy)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
        val builder = Request.Builder().url(base + path)
            .header("User-Agent", UA)
            .header("CF-Client-Version", CLIENT_VERSION)
            .header("Accept", "application/json")
            .method(method, body.toString().toRequestBody(JSON_TYPE))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val call = client.newCall(builder.build())
        val owner = Thread.currentThread()
        val cancel = cancelWatch.scheduleAtFixedRate({ if (owner.isInterrupted) call.cancel() }, 100, 100, TimeUnit.MILLISECONDS)
        try {
            call.execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                return JSONObject(text)
            }
        } finally {
            cancel.cancel(false)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    private fun call(context: Context, base: String, method: String, path: String, body: JSONObject, bearer: String?): JSONObject {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("identity_cancelled")
        val manager = com.mlmvpn.scanner.engines.cfnet.CfNetManager.get(context)
        val learned = manager.identityExchange(method, path, buildMap {
            put("Content-Type", "application/json; charset=UTF-8"); put("User-Agent", UA)
            put("CF-Client-Version", CLIENT_VERSION); put("Accept", "application/json")
            if (bearer != null) put("Authorization", "Bearer $bearer")
        }, body.toString().toByteArray(Charsets.UTF_8))
        if (learned != null) {
            if (learned.http !in 200..299) throw IllegalStateException("HTTP ${learned.http}")
            return JSONObject(learned.body)
        }
        if (base == FRONT_BASE) {
            val headers = buildList {
                add("Content-Type" to "application/json; charset=UTF-8")
                add("User-Agent" to UA)
                add("CF-Client-Version" to CLIENT_VERSION)
                add("Accept" to "application/json")
                if (bearer != null) add("Authorization" to "Bearer $bearer")
            }
            val a = WarpApiFront.exchange(method, path, headers, body.toString().toByteArray(Charsets.UTF_8))
            if (a.status !in 200..299) {
                throw IllegalStateException("HTTP ${a.status}")
            }
            return JSONObject(a.body)
        }
        val builder = Request.Builder().url(base + path)
            .header("User-Agent", UA)
            .header("CF-Client-Version", CLIENT_VERSION)
            .header("Accept", "application/json")
            .method(method, body.toString().toRequestBody(JSON_TYPE))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val call = UpdateNet.client(context, 12, 25).newCall(builder.build())
        val owner = Thread.currentThread()
        val cancel = cancelWatch.scheduleAtFixedRate({ if (owner.isInterrupted) call.cancel() }, 100, 100, TimeUnit.MILLISECONDS)
        try { call.execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw IllegalStateException("HTTP ${r.code}")
            }
            return JSONObject(text)
        } } finally { cancel.cancel(false) }
    }

    // ── the MASQUE certificate: the same self-signed one the core makes ──────────────────────
    //
    // aether account.rs generate_masque_keypair: X.509 v3, serial 0, empty subject and issuer,
    // valid from now for 365 days, the P-256 key, signed with ECDSA-SHA256 by that same key.

    private fun selfSigned(kp: java.security.KeyPair, nowMs: Long): ByteArray {
        val ecdsaSha256 = der(0x30, der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)))
        val emptyName = der(0x30, ByteArray(0))
        val validity = der(0x30, time(nowMs) + time(nowMs + CERT_LIFETIME_DAYS * 86_400_000L))
        val tbs = der(
            0x30,
            der(0xA0, der(0x02, byteArrayOf(2))) +      // version v3
                der(0x02, byteArrayOf(0)) +              // serial 0
                ecdsaSha256 + emptyName + validity + emptyName +
                kp.public.encoded,                       // SubjectPublicKeyInfo, already DER
        )
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private); update(tbs); sign()
        }
        return der(0x30, tbs + ecdsaSha256 + der(0x03, byteArrayOf(0) + sig))
    }

    private fun time(ms: Long): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
        // UTCTime up to 2049, GeneralizedTime after, as RFC 5280 requires.
        return if (year < 2050) {
            der(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(Date(ms)).toByteArray())
        } else {
            der(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(Date(ms)).toByteArray())
        }
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun pem(type: String, der: ByteArray): String =
        "-----BEGIN $type-----\n" +
            Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n") +
            "\n-----END $type-----\n"

    private fun fa(fa: String, en: String) = com.mlmvpn.scanner.store.tr(fa, en)
}
