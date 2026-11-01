package com.mlmvpn.scanner.data.studio

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Puts the Config Studio engine onto a Cloudflare account -- or finds the one that is already there.
 *
 * Three things make this different from [com.mlmvpn.scanner.engines.mlm.MlmDeployer], and each is a
 * bug that deployer has:
 *
 *  1. **It looks before it deploys.** The old path derived its script name from a URL stored on the
 *     phone, so a second device found nothing, minted a random name, and created a *second* worker
 *     beside the first -- serving the old script on the old URL, outside the staleness check, and
 *     still the target of every link already handed out. See [StudioNaming] and [StudioDiscovery].
 *  2. **It refuses to go backwards.** Two devices on different app versions share one installation,
 *     which is the normal case once adoption works. Uploading an older build over a newer one takes
 *     a one-way migration backwards, so the build is read first (plan R16).
 *  3. **The secret is a `secret_text` binding, not `plain_text`.** The old deployer ships
 *     `ADMIN_PASSWORD` in the clear inside the script metadata, readable by anyone with access to
 *     the Cloudflare account.
 *
 * See `android/docs/CONFIG-STUDIO-PLAN.md` §B and §8.4.
 */
class StudioDeployer(private val context: Context) {

    private companion object {
        /**
         * The Durable Object migration tag.
         *
         * A constant, and it moves only when the set of DO classes changes — never with a build
         * number. Cloudflare tracks what it has already created against this string per script, so
         * an unnecessary change means the next deploy claims to be creating a class that exists.
         */
        const val DO_TAG = "v1"
    }

    private val client = OkHttpClient.Builder()
        .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns())
        .protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        /**
         * The engine is installed and this device holds a key for it. [deviceLimits] is false when
         * Cloudflare would not give this account the Durable Object, so the screen can say so.
         */
        data class Ready(
            val workerUrl: String,
            val adopted: Boolean,
            val build: Int,
            val deviceLimits: Boolean = true,
        ) : Result()

        /**
         * Deploying would replace a newer engine with an older one (R16).
         *
         * Never resolved silently: only the operator can say whether they mean to roll back, and
         * build 7 onwards is one-way.
         */
        data class WouldDowngrade(val installed: Int, val shipping: Int) : Result()

        data class Failed(val message: String) : Result()
    }

    /**
     * Install, adopt, or upgrade -- whichever this account needs.
     *
     * @param force skip the downgrade guard. Only ever set from an explicit operator confirmation.
     */
    suspend fun install(
        account: CloudAccount,
        force: Boolean = false,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Result = try {
        installSteps(account, force, onProgress)
    } catch (e: java.io.IOException) {
        // A dropped or filtered connection to api.cloudflare.com mid-install (reset, timeout,
        // no DNS) used to escape as an exception and crash the app. It is a failed install.
        Log.w("StudioDeployer", "install: ${e.javaClass.simpleName}")
        Result.Failed("ارتباط با Cloudflare قطع شد؛ اینترنت یا فیلترشکن را بررسی کنید و دوباره بزنید")
    }

    private suspend fun installSteps(
        account: CloudAccount,
        force: Boolean,
        onProgress: (Int, String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val cloud = CloudManager(context)
        val headers = cloud.authHeadersFor(account)

        // ---- 1. is there already an installation on this account? -------------------------
        onProgress(10, "بررسی حساب")
        val found = when (val scan = StudioDiscovery(context).scan(account)) {
            is StudioDiscovery.Result.Failed ->
                // Deploying now is exactly how the duplicate-worker bug happens: we cannot see what
                // is on the account, so we cannot know we are about to add a second engine to it.
                return@withContext Result.Failed("حساب بررسی نشد: ${scan.message}")
            is StudioDiscovery.Result.None -> null
            is StudioDiscovery.Result.Adopted -> scan.found
        }

        if (found != null && found.otherCandidates.isNotEmpty()) {
            Log.w("StudioDeployer", "account carries extra candidates: ${found.otherCandidates}")
        }

        // ---- 2. the downgrade guard (R16) --------------------------------------------------
        // The build of the script about to be uploaded, read from the script itself: «ام‌ال‌ام
        // استور» can deliver a newer engine than this APK shipped, and comparing against the APK's
        // constant would then call every later deploy of that newer build a downgrade.
        val shipping = runCatching { com.mlmvpn.scanner.store.StoreFiles.readText(context, "mlm_worker.js") }.getOrNull()
            ?.let { Regex("""STUDIO_API_VERSION\s*=\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: com.mlmvpn.scanner.data.PanelBuild.MLM
        Log.i(
            "StudioDeployer",
            "scan: found=" + (found?.scriptName ?: "none") + " kind=" + (found?.kind ?: "-") +
                " build=" + (found?.build ?: -1) + " route=" + (found?.apiRoute ?: "-") +
                " db=" + (found?.databaseId ?: "-") + " shipping=" + shipping,
        )
        if (!force && found != null && found.build > shipping) {
            return@withContext Result.WouldDowngrade(found.build, shipping)
        }

        // ---- 3. subdomain + database --------------------------------------------------------
        onProgress(25, "آماده‌سازی حساب")
        val subdomain = cloud.getWorkersDetailed(account).second.subdomain
            .ifEmpty { return@withContext Result.Failed("این حساب هنوز زیردامنهٔ workers.dev ندارد") }

        val databaseId = found?.databaseId
            ?: account.mlmDbId?.takeIf { it.isNotEmpty() }
            ?: findOrCreateDatabase(account, headers)
            ?: return@withContext Result.Failed("دیتابیس ساخته نشد")

        // ---- 4. the script -------------------------------------------------------------------
        onProgress(45, "نصب موتور")
        // An adopted installation keeps the name it already answers on. Renaming would change the
        // URL, and every subscription link already in someone's hands points at the old one.
        val scriptName = found?.scriptName ?: StudioNaming.scriptName(account.accountId)

        // Reuse the route an existing installation already advertises: it is baked into nothing
        // else, but changing it for no reason invalidates any link the app has cached.
        val apiRoute = found?.apiRoute
            ?: account.studioApiRoute?.takeIf { it.isNotEmpty() }
            ?: StudioNaming.newApiRoute()

        // A FRESH bootstrap secret on every deploy. That is what re-arms bootstrap for a device
        // that cannot read the old one back -- Cloudflare never returns a secret_text binding --
        // and it is why the worker burns secrets individually rather than burning "bootstrap".
        val bootstrapSecret = randomHex(32)

        val script = runCatching {
            com.mlmvpn.scanner.store.StoreFiles.open(context, "mlm_worker.js").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@withContext Result.Failed("فایل موتور خوانده نشد")

        /** How the Durable Object's migration is sent on one upload attempt. */
        val noMigration = "none"
        val createClass = "create"
        val replayTag = "replay"

        fun metadataFor(withDurableObject: Boolean, migration: String = createClass): JSONObject = JSONObject().apply {
            put("main_module", "worker.js")
            // Fixed past date, as every deployer in this app uses. Cloudflare rejects a
            // compatibility_date in the future, and for timezones ahead of UTC the device's local
            // date rolls over first -- so a computed date fails every upload between midnight and
            // 03:30 local time.
            put("compatibility_date", "2024-03-03")
            put("bindings", JSONArray().apply {
                put(JSONObject().apply { put("type", "d1"); put("name", "DB"); put("id", databaseId) })
                put(JSONObject().apply {
                    put("type", "plain_text"); put("name", "STUDIO_ROUTE"); put("text", apiRoute)
                })
                put(JSONObject().apply {
                    // secret_text, so it is write-only. The legacy ADMIN_PASSWORD below is
                    // plain_text and is readable by anyone with the Cloudflare account -- which is
                    // exactly why nothing new is put there.
                    put("type", "secret_text")
                    put("name", "STUDIO_BOOTSTRAP_HASH")
                    put("text", sha256(bootstrapSecret))
                })
                // Still shipped, and deliberately so. Removing it now would make `getAdminHash`
                // fall through to a database most installs never wrote, and the legacy panel would
                // open to anyone with the URL. Build 6 seeds the database from this binding on
                // first use; only build 7 stops sending it (plan R3, and the ordering matters).
                put(JSONObject().apply {
                    put("type", "plain_text")
                    put("name", "ADMIN_PASSWORD")
                    // Never the old default: a panel that answers to "admin" is found and used by
                    // bots within minutes of a deploy (utils/AdminPassword).
                    put("text", com.mlmvpn.scanner.utils.AdminPassword.ensure(account))
                })
                // DEBUG is NOT sent. The old deployer hardcoded "1", which writes a debug_logs row
                // per request, forever, unpruned -- spending the scarcest budget in the system
                // (100k D1 row writes a day) on logs nobody reads.

                // The Durable Object that makes device, connection and IP limits real. Without it
                // the worker reports `do_mode: soft` and the app draws those caps as recorded
                // rather than enforced — which is what every installation deployed before this
                // build does, and what an account where Durable Objects are unavailable keeps
                // doing.
                if (withDurableObject) {
                    put(JSONObject().apply {
                        put("type", "durable_object_namespace")
                        put("name", "SESSIONS")
                        put("class_name", "SessionDO")
                    })
                }
            })

            // The tagged migration that CREATES the class, and the single most breakable part of
            // this deploy (plan R1).
            //
            // Re-sending `new_sqlite_classes` for a class Cloudflare has already created is an
            // error. The tag used to be remembered ON THIS PHONE and replayed from there -- so a
            // second phone, or a reinstall, sent the class again, the upload was refused, and the
            // fallback below quietly deployed without the object: device limits turned off by an
            // engine update, with the screen saying "recorded, not enforced". Build 18 reads what
            // Cloudflare itself has applied (StudioDiscovery › migrationTag) and, like wrangler,
            // sends a migration only when there is one to apply.
            if (withDurableObject && migration != noMigration) {
                put("migrations", JSONObject().apply {
                    if (migration == replayTag) {
                        put("old_tag", DO_TAG)
                        put("new_tag", DO_TAG)
                    } else {
                        put("new_tag", DO_TAG)
                        put("new_sqlite_classes", JSONArray().put("SessionDO"))
                    }
                })
            }
        }

        fun bodyFor(meta: JSONObject) = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "metadata", "metadata.json",
                meta.toString().toRequestBody("application/json".toMediaTypeOrNull()),
            )
            .addFormDataPart(
                "worker.js", "worker.js",
                script.toRequestBody("application/javascript+module".toMediaTypeOrNull()),
            )
            .build()

        val uploadUrl =
            "https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$scriptName"

        fun upload(meta: JSONObject): Pair<Boolean, String> =
            client.newCall(Request.Builder().url(uploadUrl).headers(headers).put(bodyFor(meta)).build())
                .execute().use { res -> res.isSuccessful to res.body.string().take(400) }

        // Try WITH the Durable Object, and fall back to an install without one.
        //
        // This is the shape the whole feature depends on being right, and it is deliberately the
        // conservative one: nobody has been able to prototype the DO metadata against a real
        // account, Durable Objects can be unavailable on an account for reasons the app cannot see,
        // and a migration tag can end up out of step with what Cloudflare believes it created. Any
        // of those must produce a **working installation with soft caps**, never a failed deploy —
        // the operator's users are on this worker, and refusing to redeploy because an enforcement
        // feature could not be enabled would take their tunnel down to add a limit.
        // Which migration to send, from what Cloudflare says it has applied to this script: the
        // class exists (tag present) → the binding alone; the script has never had one (field
        // present, blank) or there is no script yet → create it; the listing did not say → try
        // creating, then the binding alone. The old replay form is kept as a last try, and only
        // after all of them does the install go ahead without the object.
        val knownTag = found?.migrationTag
        val attempts = when {
            found == null -> listOf(createClass, noMigration)
            knownTag == null -> listOf(createClass, noMigration, replayTag)
            knownTag.isNotEmpty() -> listOf(noMigration, replayTag)
            else -> listOf(createClass, noMigration)
        }
        var doEnabled = false
        var ok = false
        var detail = ""
        for (migration in attempts) {
            val r = upload(metadataFor(true, migration))
            if (r.first) {
                ok = true
                doEnabled = true
                break
            }
            detail = r.second
            Log.w("StudioDeployer", "deploy with SESSIONS ($migration) failed: $detail")
        }
        if (!ok) {
            onProgress(50, "نصب موتور (بدون محدودیت دستگاه)")
            val retry = upload(metadataFor(false))
            ok = retry.first
            detail = retry.second
        }
        if (!ok) return@withContext Result.Failed("آپلود موتور ناموفق بود: $detail")

        onProgress(70, "فعال‌سازی آدرس")
        client.newCall(
            Request.Builder().url("$uploadUrl/subdomain").headers(headers)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull())).build()
        ).execute().use { res ->
            if (!res.isSuccessful) return@withContext Result.Failed("آدرس فعال نشد")
        }

        val workerUrl = "https://$scriptName.$subdomain.workers.dev"

        // ---- 5. exchange the bootstrap secret for a key -------------------------------------
        // The script upload does NOT replace a secret that already exists.
        //
        // Cloudflare preserves secrets across script uploads, so a `secret_text` entry in the
        // multipart metadata sets the value only the first time; on every later deploy the old
        // secret survives and the new one is silently dropped. That is not a theory -- it is what
        // two deploys to a real account did: the worker answered `bootstrap_invalid` (403, "there
        // is a hash and it is not yours") rather than `bootstrap_unavailable` (409, "there is no
        // hash"), which is only possible if the previous secret was still in place.
        //
        // Left unfixed it breaks exactly the case re-bootstrap exists for: a second device can
        // never obtain a key, because the secret it just uploaded is not the one the engine holds.
        onProgress(78, "تنظیم کلید")
        if (!putSecret(account, headers, scriptName, "STUDIO_BOOTSTRAP_HASH", sha256(bootstrapSecret))) {
            return@withContext Result.Failed("کلید بوت‌استرپ روی موتور تنظیم نشد")
        }

        onProgress(85, "اتصال به موتور")
        // Retried, because a single attempt races the binding's propagation and always loses.
        //
        // A fresh secret is generated on every deploy, so the exchange only works once the *new*
        // value has reached the isolate that serves the call. A warm isolate keeps the bindings it
        // started with, so the first attempt after an upload can legitimately see the previous
        // secret -- which is what a real deploy did twice, answering `bootstrap_invalid` about ten
        // seconds after the secret was set. Backing off across a few attempts costs nothing on the
        // happy path and removes the race entirely.
        var key: Pair<String, String>? = null
        for (attempt in 1..5) {
            key = bootstrap(workerUrl, apiRoute, bootstrapSecret)
            if (key != null) break
            if (attempt < 5) {
                onProgress(85, "اتصال به موتور… (${attempt + 1}/۵)")
                kotlinx.coroutines.delay(6000)
            }
        }
        if (key == null) return@withContext Result.Failed("کلید دسترسی گرفته نشد")

        // Retire the key this device was holding.
        //
        // Every deploy — install, adopt, repair, engine update — mints a fresh bootstrap secret and
        // exchanges it for a NEW key, because Cloudflare will not read a `secret_text` binding back.
        // The account row then keeps only the newest key id, so the app forgets the old one while
        // the engine goes on honouring it: an operator who pressed «تعمیر» ten times left ten live
        // admin keys behind, none of them listed against a device they still have. Done after the
        // new key is proven to work, and a failure here is not fatal — the deploy succeeded, and
        // `GET /v1/auth/keys` still lists the leftover for a deliberate revoke.
        account.studioKeyId
            ?.takeIf { it.isNotEmpty() && it != key.first }
            ?.let { revokeKey(workerUrl, apiRoute, "cs_${key.first}_${key.second}", it) }

        account.mlmWorkerUrl = workerUrl
        account.mlmDbId = databaseId
        account.mlmStatus = "deployed"
        account.mlmVersion = shipping
        // Recorded only on a deploy that actually carried the object, so the next upgrade replays
        // the tag as a no-op instead of trying to create a class that already exists — and so an
        // account that fell back to soft still offers to create it next time rather than believing
        // it already has one.
        account.studioDoTag = if (doEnabled) DO_TAG else null
        account.studioApiRoute = apiRoute
        account.studioKeyId = key.first
        account.studioApiSecret = key.second
        account.studioKeyLabel = android.os.Build.MODEL ?: "device"
        account.studioAdopted = found != null
        account.studioStatus = if (found != null) "adopted" else "deployed"
        account.studioVersion = shipping
        cloud.saveAccounts()

        onProgress(100, "آماده")
        Result.Ready(workerUrl, found != null, shipping, deviceLimits = doEnabled)
    }

    /**
     * Set one secret through the dedicated secrets endpoint, which does overwrite.
     *
     * `PUT /workers/scripts/{name}/secrets` is the only way to change a secret's value on a script
     * that already has one; the multipart script upload will not.
     */
    private fun putSecret(
        account: CloudAccount,
        headers: okhttp3.Headers,
        scriptName: String,
        name: String,
        value: String,
    ): Boolean = try {
        val body = JSONObject().apply {
            put("name", name)
            put("text", value)
            put("type", "secret_text")
        }
        val req = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$scriptName/secrets")
            .headers(headers)
            .put(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) Log.w("StudioDeployer", "secret $name failed ${res.code}: ${text.take(200)}")
            res.isSuccessful
        }
    } catch (e: Exception) {
        Log.w("StudioDeployer", "secret $name error: ${e.message}")
        false
    }

    /** @return keyId to secret, or null. */
    private fun bootstrap(workerUrl: String, apiRoute: String, secret: String): Pair<String, String>? = try {
        val payload = JSONObject().apply {
            put("secret", secret)
            put("label", android.os.Build.MODEL ?: "device")
        }
        val req = Request.Builder()
            .url("$workerUrl/$apiRoute/v1/auth/bootstrap")
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) {
                Log.w("StudioDeployer", "bootstrap failed ${res.code}: ${text.take(200)}")
                null
            } else {
                // "cs_<keyId>_<secret>" -- split rather than trusted, so a malformed answer is a
                // null here instead of a token that fails every later call for no visible reason.
                val full = JSONObject(text).optString("key")
                val parts = full.split("_")
                if (parts.size == 3 && parts[0] == "cs") parts[1] to parts[2] else null
            }
        }
    } catch (e: Exception) {
        Log.w("StudioDeployer", "bootstrap error: ${e.message}")
        null
    }

    /**
     * Revoke one API key, using another one.
     *
     * Every revocation writes an audit row on the engine, which is the record that says a key stopped
     * being usable and when — the same trail that makes an unexplained *new* key the one signal an
     * operator gets that their Cloudflare token leaked.
     */
    private fun revokeKey(workerUrl: String, apiRoute: String, bearer: String, keyId: String) {
        try {
            val req = Request.Builder()
                .url("$workerUrl/$apiRoute/v1/auth/keys/$keyId")
                .header("Authorization", "Bearer $bearer")
                .delete()
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) Log.w("StudioDeployer", "revoke $keyId: ${res.code}")
            }
        } catch (e: Exception) {
            Log.w("StudioDeployer", "revoke $keyId error: ${e.message}")
        }
    }

    /**
     * An existing Studio database on this account, or a new one.
     *
     * Searched before creating because a failed or interrupted deploy leaves a database behind that
     * was never recorded on the account, and the free plan caps D1 at ten per account -- so a few
     * retries used to be enough to make every future deploy fail with no databases left to create.
     */
    private fun findOrCreateDatabase(account: CloudAccount, headers: okhttp3.Headers): String? = try {
        val base = "https://api.cloudflare.com/client/v4/accounts/${account.accountId}/d1/database"
        var id: String? = null
        client.newCall(Request.Builder().url("$base?per_page=100").headers(headers).get().build())
            .execute().use { res ->
                val json = JSONObject(res.body.string())
                val arr = json.optJSONArray("result")
                if (arr != null) for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (StudioNaming.isStudioDatabaseName(item.optString("name"))) {
                        id = item.optString("uuid").takeIf { it.isNotEmpty() }
                        if (id != null) break
                    }
                }
            }
        id ?: run {
            val name = StudioNaming.databaseName(account.accountId)
            client.newCall(
                Request.Builder().url(base).headers(headers)
                    .post(JSONObject().put("name", name).toString()
                        .toRequestBody("application/json".toMediaTypeOrNull())).build()
            ).execute().use { res ->
                JSONObject(res.body.string())
                    .optJSONObject("result")?.optString("uuid")?.takeIf { it.isNotEmpty() }
            }
        }
    } catch (e: Exception) {
        Log.w("StudioDeployer", "d1 provisioning failed: ${e.message}")
        null
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
