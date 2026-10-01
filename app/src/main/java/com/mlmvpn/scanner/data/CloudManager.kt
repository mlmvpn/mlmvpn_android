package com.mlmvpn.scanner.data

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.models.CloudSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

class CloudManager private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var instance: CloudManager? = null

        /** Marks that the one-off cleanup in [clearVerdictsFromTheBrokenProbe] has run. */
        private const val KEY_VERIFY_RESET = "verify_verdicts_reset_v1"

        /** The account list: sealed by [SecureStore], or the pre-1.2.37 plaintext it replaces. */
        private const val KEY_SEALED = "accounts_sealed"
        private const val KEY_PLAIN = "accounts_list"

        /** The Gemini exit's Durable Object class, its creating migration tag, and where it lives. */
        private const val GEMINI_EXIT_CLASS = "GeminiExit"
        private const val GEMINI_EXIT_DO_TAG = "v1"

        /**
         * The Gemini exit's build. Bump it whenever assets/gemini_exit_worker.js changes: an
         * account on an older build is offered the update, and one on this build is left alone --
         * tapping the row used to redeploy every time.
         */
        const val GEMINI_EXIT_VERSION = 2
        private const val GEMINI_EXIT_HINT = "enam"

        operator fun invoke(context: Context): CloudManager {
            return instance ?: synchronized(this) {
                instance ?: CloudManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val client = OkHttpClient.Builder().dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns()).protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences("cloud_accounts_prefs", Context.MODE_PRIVATE)

    /** Same settings as the app path; callers isolate calls and never mutate this client. */
    internal fun diagnosticClientBuilder(): OkHttpClient.Builder = client.newBuilder()

    // Temporary lists for memory, later persist to SharedPreferences or Room
    val accounts = mutableListOf<CloudAccount>()
    var settings = CloudSettings()

    private val _accountsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<CloudAccount>>(emptyList())
    val accountsFlow: kotlinx.coroutines.flow.StateFlow<List<CloudAccount>> = _accountsFlow

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            loadAccounts()
            _accountsFlow.value = accounts.toList()
        }
    }

    /**
     * The saved account list as JSON. Newer installs keep it sealed by [SecureStore] under
     * [KEY_SEALED]; the older plaintext [KEY_PLAIN] is still read so an update loses nothing, and
     * the next [saveAccounts] seals it and deletes the plaintext.
     */
    private fun readAccountsJson(): String {
        prefs.getString(KEY_SEALED, null)?.let { sealed ->
            SecureStore.open(sealed)?.let { return it }
            Log.w("CloudManager", "sealed account list unreadable; falling back")
        }
        return prefs.getString(KEY_PLAIN, "[]") ?: "[]"
    }

    private fun writeAccountsJson(json: String) {
        val sealed = SecureStore.seal(json)
        if (sealed != null) {
            prefs.edit().putString(KEY_SEALED, sealed).remove(KEY_PLAIN).apply()
        } else {
            // Keystore unusable on this device: keep the old behaviour rather than drop accounts.
            prefs.edit().putString(KEY_PLAIN, json).remove(KEY_SEALED).apply()
        }
    }

    fun loadAccounts() {
        val jsonStr = readAccountsJson()
        try {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<CloudAccount>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CloudAccount(
                        id = obj.getString("id"),
                        token = obj.getString("token"),
                        email = obj.getString("email"),
                        name = obj.getString("name"),
                        accountId = obj.getString("accountId"),
                        status = obj.getString("status"),
                        addedAt = obj.getString("addedAt"),
                        workerUrl = obj.optString("workerUrl", ""),
                        uuid = obj.optString("uuid", ""),
                        trPass = obj.optString("trPass", ""),
                        subPath = obj.optString("subPath", ""),
                        kvNamespaceId = obj.optString("kvNamespaceId", "").ifBlank { null },
                        // Blank on accounts saved before the probe existed; CloudAuth then falls
                        // back to the shape check for those.
                        authScheme = obj.optString("authScheme", "").ifBlank { null },
                        edgWorkerUrl = obj.optString("edgWorkerUrl", ""),
                        edgUuid = obj.optString("edgUuid", ""),
                        edgAdminPass = obj.optString("edgAdminPass", ""),
                        edgKvNamespaceId = obj.optString("edgKvNamespaceId", ""),
                        edgStatus = obj.optString("edgStatus", "idle"),
                        nahanWorkerUrl = obj.optString("nahanWorkerUrl", ""),
                        nahanDbId = obj.optString("nahanDbId", ""),
                        nahanApiRoute = obj.optString("nahanApiRoute", "sync"),
                        nahanMasterKey = obj.optString("nahanMasterKey", ""),
                        nahanStatus = obj.optString("nahanStatus", "idle"),
                        mlmWorkerUrl = obj.optString("mlmWorkerUrl", ""),
                        mlmDbId = obj.optString("mlmDbId", ""),
                        mlmAdminPassword = obj.optString("mlmAdminPassword", ""),
                        mlmStatus = obj.optString("mlmStatus", "idle"),
                        bpbVersion = obj.optInt("bpbVersion", 0),
                        edgVersion = obj.optInt("edgVersion", 0),
                        nahanVersion = obj.optInt("nahanVersion", 0),
                        mlmVersion = obj.optInt("mlmVersion", 0),
                        studioApiRoute = obj.optString("studioApiRoute", ""),
                        studioKeyId = obj.optString("studioKeyId", ""),
                        studioApiSecret = obj.optString("studioApiSecret", ""),
                        studioKeyLabel = obj.optString("studioKeyLabel", ""),
                        studioAdopted = obj.optBoolean("studioAdopted", false),
                        studioDoTag = obj.optString("studioDoTag", ""),
                        studioStatus = obj.optString("studioStatus", "idle"),
                        studioVersion = obj.optInt("studioVersion", 0),
                        relayWorkerUrl = obj.optString("relayWorkerUrl", ""),
                        relayStatus = obj.optString("relayStatus", "idle"),
                        relayVersion = obj.optInt("relayVersion", 0),
                        geminiExitUrl = obj.optString("geminiExitUrl", "").ifBlank { null },
                        geminiExitId = obj.optString("geminiExitId", "").ifBlank { null },
                        geminiExitPath = obj.optString("geminiExitPath", "").ifBlank { null },
                        geminiExitStatus = obj.optString("geminiExitStatus", "idle"),
                        geminiExitVersion = obj.optInt("geminiExitVersion", 0),
                        geminiExitDoTag = obj.optString("geminiExitDoTag", "").ifBlank { null },
                        poolWorkerUrl = obj.optString("poolWorkerUrl", ""),
                        poolDbId = obj.optString("poolDbId", ""),
                        poolKvId = obj.optString("poolKvId", ""),
                        poolStatus = obj.optString("poolStatus", "idle"),
                        poolVersion = obj.optInt("poolVersion", 0),
                        dnsVersion = obj.optInt("dnsVersion", 0),
                        dnsWorkerUrl = obj.optString("dnsWorkerUrl", ""),
                        dnsKvNamespaceId = obj.optString("dnsKvNamespaceId", ""),
                        dnsStatus = obj.optString("dnsStatus", "idle"),
                        gstRelayWorkerUrl = obj.optString("gstRelayWorkerUrl", ""),
                        gstRelayStatus = obj.optString("gstRelayStatus", "idle"),
                        isEmailVerified = obj.optBoolean("isEmailVerified", true),
                        hasSubdomain = obj.optBoolean("hasSubdomain", 
                            obj.optString("workerUrl", "").isNotEmpty() || 
                            obj.optString("edgWorkerUrl", "").isNotEmpty() || 
                            obj.optString("nahanWorkerUrl", "").isNotEmpty() ||
                            obj.optString("mlmWorkerUrl", "").isNotEmpty()
                        )
                    )
                )
            }
            accounts.clear()
            accounts.addAll(list)
            clearVerdictsFromTheBrokenProbe()
            // One-time move of a pre-1.2.37 plaintext list into the sealed slot.
            if (prefs.contains(KEY_PLAIN) && SecureStore.available) saveAccounts()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Throw away every `isEmailVerified = false` written by the probe that could not answer.
     *
     * Once, on the first load after updating. The old status probe read `email_verified` from
     * `GET /client/v4/user` -- a field that endpoint does not return -- so it answered "not
     * verified" for every account that did not already have a workers.dev subdomain, and wrote that
     * to disk. Fixing the probe alone would not free anyone already carrying the flag: the panel
     * reads the saved value, and the button offered to re-check it ran the same dead query.
     *
     * Clearing it is not a claim that these accounts are verified. It is removing an answer that
     * was never evidence. What replaces it is Cloudflare's own: the subdomain create either
     * succeeds, or is refused with the email named, and only that refusal sets the flag again --
     * see [createSubdomain]. An account that really is unverified therefore lands back here within
     * one button press, with Cloudflare's reason attached instead of ours.
     *
     * Accounts marked verified are untouched, which is every account the cloud panel currently
     * works for.
     */
    private fun clearVerdictsFromTheBrokenProbe() {
        if (prefs.getBoolean(KEY_VERIFY_RESET, false)) return
        val stuck = accounts.filter { !it.isEmailVerified }
        if (stuck.isNotEmpty()) {
            stuck.forEach { it.isEmailVerified = true }
            saveAccounts()
            Log.i("CloudManager", "cleared unverified-email flag on ${stuck.size} account(s)")
        }
        prefs.edit().putBoolean(KEY_VERIFY_RESET, true).apply()
    }

    fun saveAccounts() {
        val arr = org.json.JSONArray()
        for (account in accounts) {
            val obj = JSONObject().apply {
                put("id", account.id)
                put("token", account.token)
                put("email", account.email)
                put("name", account.name)
                put("accountId", account.accountId)
                put("status", account.status)
                put("addedAt", account.addedAt)
                put("workerUrl", account.workerUrl ?: "")
                put("uuid", account.uuid ?: "")
                put("trPass", account.trPass ?: "")
                put("subPath", account.subPath ?: "")
                put("kvNamespaceId", account.kvNamespaceId ?: "")
                put("authScheme", account.authScheme ?: "")
                put("edgWorkerUrl", account.edgWorkerUrl ?: "")
                put("edgUuid", account.edgUuid ?: "")
                put("edgAdminPass", account.edgAdminPass ?: "")
                put("edgKvNamespaceId", account.edgKvNamespaceId ?: "")
                put("edgStatus", account.edgStatus)
                put("nahanWorkerUrl", account.nahanWorkerUrl ?: "")
                put("nahanDbId", account.nahanDbId ?: "")
                put("nahanApiRoute", account.nahanApiRoute)
                put("nahanMasterKey", account.nahanMasterKey ?: "")
                put("nahanStatus", account.nahanStatus)
                put("mlmWorkerUrl", account.mlmWorkerUrl ?: "")
                put("mlmDbId", account.mlmDbId ?: "")
                put("mlmAdminPassword", account.mlmAdminPassword ?: "")
                put("mlmStatus", account.mlmStatus)
                put("bpbVersion", account.bpbVersion)
                put("edgVersion", account.edgVersion)
                put("nahanVersion", account.nahanVersion)
                put("mlmVersion", account.mlmVersion)
                put("studioApiRoute", account.studioApiRoute ?: "")
                put("studioKeyId", account.studioKeyId ?: "")
                put("studioApiSecret", account.studioApiSecret ?: "")
                put("studioKeyLabel", account.studioKeyLabel ?: "")
                put("studioAdopted", account.studioAdopted)
                put("studioDoTag", account.studioDoTag ?: "")
                put("studioStatus", account.studioStatus)
                put("studioVersion", account.studioVersion)
                put("relayWorkerUrl", account.relayWorkerUrl ?: "")
                put("relayStatus", account.relayStatus)
                put("relayVersion", account.relayVersion)
                put("geminiExitUrl", account.geminiExitUrl ?: "")
                put("geminiExitId", account.geminiExitId ?: "")
                put("geminiExitPath", account.geminiExitPath ?: "")
                put("geminiExitStatus", account.geminiExitStatus)
                put("geminiExitVersion", account.geminiExitVersion)
                put("geminiExitDoTag", account.geminiExitDoTag ?: "")
                put("poolWorkerUrl", account.poolWorkerUrl ?: "")
                put("poolDbId", account.poolDbId ?: "")
                put("poolKvId", account.poolKvId ?: "")
                put("poolStatus", account.poolStatus)
                put("poolVersion", account.poolVersion)
                put("dnsVersion", account.dnsVersion)
                put("dnsWorkerUrl", account.dnsWorkerUrl ?: "")
                put("dnsKvNamespaceId", account.dnsKvNamespaceId ?: "")
                put("dnsStatus", account.dnsStatus)
                put("gstRelayWorkerUrl", account.gstRelayWorkerUrl ?: "")
                put("gstRelayStatus", account.gstRelayStatus)
                put("isEmailVerified", account.isEmailVerified)
                put("hasSubdomain", account.hasSubdomain)
            }
            arr.put(obj)
        }
        writeAccountsJson(arr.toString())
        _accountsFlow.value = accounts.toList()
    }

    suspend fun addAccount(rawToken: String, rawEmail: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = rawToken.replace(Regex("[^a-zA-Z0-9_-]"), "").trim()
        val email = rawEmail.trim()
        
        if (token.isEmpty()) return@withContext Pair(false, "Token is empty")

        // Which scheme this credential works with is PROVEN here, not guessed.
        //
        // `/user/tokens/verify` answers 200 only for an API token; `/user` with the X-Auth pair
        // answers 200 only for a Global API Key. The shape check picks which to try first -- it
        // is right for the common cases and saves a request -- but if that attempt fails, the
        // other is tried before giving up. Whichever answers is recorded on the account, so no
        // later call has to guess and none of them can guess differently.
        val shapeSaysGlobal = CloudAuth.isGlobalKey(token, email)
        var provenScheme: String? = null
        var authTransportFailed = false

        fun probe(scheme: String): Boolean = try {
            val url = if (scheme == "bearer") "https://api.cloudflare.com/client/v4/user/tokens/verify"
                      else "https://api.cloudflare.com/client/v4/user"
            val headers = if (scheme == "bearer") {
                Headers.Builder()
                    .add("Authorization", "Bearer ${token.trim()}")
                    .add("Content-Type", "application/json").build()
            } else {
                Headers.Builder()
                    .add("X-Auth-Email", email.trim())
                    .add("X-Auth-Key", token.trim())
                    .add("Content-Type", "application/json").build()
            }
            client.newCall(Request.Builder().url(url).headers(headers).get().build())
                .execute().use { it.isSuccessful }
        } catch (e: Exception) {
            authTransportFailed = true
            false
        }

        val order = if (shapeSaysGlobal) listOf("global", "bearer") else listOf("bearer", "global")
        for (scheme in order) {
            // A Global API Key is meaningless without the email, so do not spend a request on it.
            if (scheme == "global" && email.isBlank()) continue
            if (probe(scheme)) { provenScheme = scheme; break }
        }

        if (provenScheme == null) {
            return@withContext Pair(
                false,
                if (authTransportFailed)
                    com.mlmvpn.scanner.store.tr(
                        "ارتباط با کلادفلر کامل نشد؛ ممکن است اینترنت مسدود یا کند باشد. از بخش عیب‌یابی، دکتر کلادفلر را اجرا کنید.",
                        "Cloudflare could not be reached reliably. This network may be blocked or slow. Open Cloudflare Doctor from Troubleshooting.")
                else if (email.isBlank())
                    S(R.string.the_credentials_were_rejected_if_you_entered)
                else
                    S(R.string.the_credentials_were_rejected_check_the_token)
            )
        }

        val usesGlobalKey = provenScheme == "global"
        val requestBuilder = Request.Builder().headers(
            if (usesGlobalKey) {
                Headers.Builder()
                    .add("X-Auth-Email", email.trim())
                    .add("X-Auth-Key", token.trim())
                    .add("Content-Type", "application/json").build()
            } else {
                Headers.Builder()
                    .add("Authorization", "Bearer ${token.trim()}")
                    .add("Content-Type", "application/json").build()
            }
        )

        try {
            // Already verified above; this keeps the original flow's shape for the account fetch.
            val verifyUrl = if (!usesGlobalKey) "https://api.cloudflare.com/client/v4/user/tokens/verify"
                            else "https://api.cloudflare.com/client/v4/user"
            
            val req1 = requestBuilder.url(verifyUrl).get().build()
            client.newCall(req1).execute().use { response ->
                if (!response.isSuccessful) {
                    // A Global API Key (unlike a scoped Token) is only valid paired with its
                    // account email -- sent alone it always fails verification with a generic
                    // error that gives no hint why. Since we tried it as a Bearer token (the
                    // scoped-token path) and that failed, tell the user to add the email instead
                    // of surfacing Cloudflare's opaque body.
                    if (!usesGlobalKey && email.isEmpty()) {
                        return@withContext Pair(false, S(R.string.this_looks_like_a_global_api_key))
                    }
                    val body = response.body?.string()
                    return@withContext Pair(false, "Invalid API Token: $body")
                }
            }

            // Get Accounts
            val req2 = requestBuilder.url("https://api.cloudflare.com/client/v4/accounts").get().build()
            client.newCall(req2).execute().use { response ->
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to get account details")
                
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                if (json.getBoolean("success")) {
                    val results = json.getJSONArray("result")
                    if (results.length() > 0) {
                        val firstAcc = results.getJSONObject(0)
                        val accName = firstAcc.getString("name")
                        val accId = firstAcc.getString("id")

                        if (accounts.any { it.accountId == accId }) {
                            return@withContext Pair(false, "Account already added")
                        }

                        val newAcc = CloudAccount(
                            id = UUID.randomUUID().toString(),
                            token = token,
                            email = email,
                            name = accName,
                            accountId = accId,
                            status = "active",
                            addedAt = System.currentTimeMillis().toString(),
                            // Proven above, not guessed. Every later call reads this.
                            authScheme = provenScheme,
                        )
                        val statusResult = checkAccountStatus(newAcc)
                        newAcc.hasSubdomain = statusResult.first
                        newAcc.isEmailVerified = statusResult.second
                        
                        accounts.add(newAcc)
                        saveAccounts()
                        return@withContext Pair(true, "Account added: $accName")
                    }
                }
            }
            Pair(false, "No Cloudflare accounts found under this token")
        } catch (e: Exception) {
            Pair(false, "Network error: ${e.message}")
        }
    }

    suspend fun getUsage(account: CloudAccount): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // Cloudflare free tier resets daily usage at midnight UTC. We query from the start of the current day in UTC.
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val endStr = sdf.format(cal.time)
            
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val startStr = sdf.format(cal.time)

            val query = """
                {"query":"query GetWorkersAnalytics(${'$'}accountTag: String!, ${'$'}datetimeStart: String!, ${'$'}datetimeEnd: String!) {\n                    viewer {\n                        accounts(filter: {accountTag: ${'$'}accountTag}) {\n                            workersInvocationsAdaptive(limit: 10000, filter: {datetime_geq: ${'$'}datetimeStart, datetime_leq: ${'$'}datetimeEnd}) {\n                                sum { requests }\n                            }\n                        }\n                    }\n                }","variables":{"accountTag":"${account.accountId}","datetimeStart":"$startStr","datetimeEnd":"$endStr"}}
            """.trimIndent()
            
            val requestBuilder = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/graphql")
                .post(query.toRequestBody("application/json".toMediaTypeOrNull()))

            if (com.mlmvpn.scanner.data.CloudAuth.useBearer(account)) {
                requestBuilder.header("Authorization", "Bearer ${account.token}")
            } else {
                requestBuilder.header("X-Auth-Email", account.email)
                requestBuilder.header("X-Auth-Key", account.token)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = org.json.JSONObject(body)
                    try {
                        if (json.has("errors") && !json.isNull("errors")) {
                            return@withContext Pair(false, "GraphQL Error: " + json.getJSONArray("errors").getJSONObject(0).getString("message"))
                        }
                        
                        val accountsArray = json.optJSONObject("data")
                            ?.optJSONObject("viewer")
                            ?.optJSONArray("accounts")
                            
                        if (accountsArray == null || accountsArray.length() == 0) return@withContext Pair(true, "0")
                        
                        val adaptiveArray = accountsArray.getJSONObject(0)
                            .optJSONArray("workersInvocationsAdaptive")
                            
                        if (adaptiveArray == null || adaptiveArray.length() == 0) return@withContext Pair(true, "0")
                            
                        val sumObj = adaptiveArray.getJSONObject(0).optJSONObject("sum")
                        val requests = sumObj?.optLong("requests", 0L) ?: 0L
                        return@withContext Pair(true, requests.toString())
                    } catch (e: Exception) {
                        val bodyPreview = if (body.length > 200) body.substring(0, 200) else body
                        return@withContext Pair(false, "Parse Error: ${e.message}\nBody: $bodyPreview")
                    }
                } else {
                    return@withContext Pair(false, "HTTP ${response.code}")
                }
            }
            Pair(false, "Unknown Error")
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * One worker with the numbers that say whether it is doing anything.
     *
     * A bare list of script names -- which is all this screen used to fetch -- cannot answer the
     * question the workers screen exists to answer: which of these is live, which is failing, and
     * which is a leftover safe to delete. The desktop app has shown requests/errors/CPU per worker
     * since it had the screen at all; these are the same four fields it renders.
     */
    data class WorkerInfo(
        val name: String,
        /** Invocations over the last 24 hours. */
        val requests: Long = 0L,
        /** Of those, the ones that threw. */
        val errors: Long = 0L,
        /** p99 CPU time, in milliseconds, as Cloudflare's GraphQL reports it. */
        val cpu: Double = 0.0,
        /** ISO-8601 from `modified_on`, or blank. The last time the script was deployed. */
        val modifiedOn: String = "",
        /** `standard` or `bundled`, from `usage_model`. Blank when Cloudflare omits it. */
        val usageModel: String = "",
        /**
         * The Durable Object migration tag Cloudflare last applied to this script: blank for a
         * script that never had one, null when the listing did not carry the field at all. The
         * Config Studio deployer needs to know which, because re-sending a migration that already
         * ran is refused -- and that refusal is what used to remove device limits on redeploy.
         */
        val migrationTag: String? = null,
    )

    /**
     * The three numbers GraphQL returns per script, before they are merged onto the REST entry.
     *
     * Separate from [WorkerInfo] because the two halves come from different APIs: the REST list
     * knows a script's name and when it was deployed, GraphQL knows what it has been doing, and
     * neither knows the other's fields.
     */
    private data class WorkerStats(
        val requests: Long = 0L,
        val errors: Long = 0L,
        val cpu: Double = 0.0,
    )

    /**
     * A whole account's workers, plus the `*.workers.dev` subdomain they are addressed under.
     *
     * The subdomain is account-level rather than per-worker, which is why it is here and not on
     * [WorkerInfo]: a worker's public address is `<name>.<subdomain>.workers.dev`, and without it
     * the screen can only show a bare script name that does not resolve to anything.
     */
    data class WorkersSnapshot(
        val workers: List<WorkerInfo> = emptyList(),
        val subdomain: String = "",
        /**
         * Whether the analytics half succeeded. The script list and the numbers come from two
         * different APIs (REST and GraphQL) with different permissions -- an API token scoped to
         * Workers Scripts but not Account Analytics lists every worker and returns nothing at all
         * for the stats. That is not a failure of the screen, so the list still renders; this flag
         * is what lets it say "no stats" instead of silently showing zeros that look like an idle
         * worker.
         */
        val statsAvailable: Boolean = false,
    )

    /**
     * The workers screen's fetch, in the same three calls the desktop's `/api/cloudflare/list-workers`
     * makes: the subdomain, the script list, then one GraphQL query for 24h analytics across every
     * script at once.
     *
     * The analytics query is deliberately NOT per-worker. `workersInvocationsAdaptive` grouped by
     * `dimensions { scriptName }` returns every script in one round trip; asking per script would
     * be N requests against an endpoint that rate-limits, on a screen that opens with all of them
     * visible.
     *
     * Only the script list is fatal. A missing subdomain (an account that never created one) and
     * missing analytics (a token without the Analytics scope, or an account with no traffic yet)
     * both leave the list perfectly usable, so they degrade rather than fail.
     */
    suspend fun getWorkersDetailed(account: CloudAccount): Pair<Boolean, WorkersSnapshot> =
        withContext(Dispatchers.IO) {
            val authHeaders = Headers.Builder().apply {
                if (com.mlmvpn.scanner.data.CloudAuth.useBearer(account)) {
                    add("Authorization", "Bearer ${account.token}")
                } else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            // ---- 1. the subdomain ------------------------------------------------------------
            //
            // `name` before `subdomain`: Cloudflare has answered this endpoint with both keys over
            // the years, and the desktop reads them in that order. Failing here is not fatal.
            var subdomain = ""
            try {
                val subReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                    .headers(authHeaders)
                    .get()
                    .build()
                client.newCall(subReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val result = JSONObject(response.body?.string() ?: "").optJSONObject("result")
                        subdomain = result?.optString("name", "").orEmpty()
                            .ifEmpty { result?.optString("subdomain", "").orEmpty() }
                    }
                }
            } catch (e: Exception) {
                Log.w("CloudManager", "subdomain fetch failed: ${e.message}")
            }

            // ---- 2. the scripts --------------------------------------------------------------
            val scripts = mutableListOf<WorkerInfo>()
            try {
                val listReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts")
                    .headers(authHeaders)
                    .get()
                    .build()
                client.newCall(listReq).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext Pair(false, WorkersSnapshot(subdomain = subdomain))
                    }
                    val json = JSONObject(body)
                    if (!json.optBoolean("success", false)) {
                        return@withContext Pair(false, WorkersSnapshot(subdomain = subdomain))
                    }
                    val arr = json.optJSONArray("result")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val id = item.optString("id").takeIf { it.isNotEmpty() } ?: continue
                            scripts.add(
                                WorkerInfo(
                                    name = id,
                                    modifiedOn = item.optString("modified_on"),
                                    usageModel = item.optString("usage_model"),
                                    migrationTag = if (item.has("migration_tag")) {
                                        item.optString("migration_tag").takeIf { it != "null" }.orEmpty()
                                    } else null,
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                return@withContext Pair(false, WorkersSnapshot(subdomain = subdomain))
            }

            // ---- 3. 24h analytics, all scripts in one query ----------------------------------
            val stats = mutableMapOf<String, WorkerStats>()
            var statsAvailable = false
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val now = System.currentTimeMillis()
                val endStr = sdf.format(java.util.Date(now))
                val startStr = sdf.format(java.util.Date(now - 24L * 60L * 60L * 1000L))

                // Built through JSONObject rather than string-interpolated, so a subdomain or
                // account id carrying a quote cannot break out of the query body.
                val gql = """
                    query GetWorkersAnalytics(${'$'}accountTag: String!, ${'$'}datetimeStart: String!, ${'$'}datetimeEnd: String!) {
                      viewer {
                        accounts(filter: {accountTag: ${'$'}accountTag}) {
                          workersInvocationsAdaptive(limit: 10000, filter: {datetime_geq: ${'$'}datetimeStart, datetime_leq: ${'$'}datetimeEnd}) {
                            sum { requests, errors }
                            quantiles { cpuTimeP99 }
                            dimensions { scriptName }
                          }
                        }
                      }
                    }
                """.trimIndent()
                val payload = JSONObject().apply {
                    put("query", gql)
                    put(
                        "variables",
                        JSONObject().apply {
                            put("accountTag", account.accountId)
                            put("datetimeStart", startStr)
                            put("datetimeEnd", endStr)
                        },
                    )
                }

                val gqlReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/graphql")
                    .headers(authHeaders)
                    .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                client.newCall(gqlReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "")
                        val hasErrors = json.has("errors") && !json.isNull("errors")
                        val rows = json.optJSONObject("data")
                            ?.optJSONObject("viewer")
                            ?.optJSONArray("accounts")
                            ?.optJSONObject(0)
                            ?.optJSONArray("workersInvocationsAdaptive")
                        if (!hasErrors && rows != null) {
                            statsAvailable = true
                            for (i in 0 until rows.length()) {
                                val row = rows.optJSONObject(i) ?: continue
                                val script = row.optJSONObject("dimensions")?.optString("scriptName")
                                    ?.takeIf { it.isNotEmpty() } ?: continue
                                val sum = row.optJSONObject("sum")
                                stats[script] = WorkerStats(
                                    requests = sum?.optLong("requests", 0L) ?: 0L,
                                    errors = sum?.optLong("errors", 0L) ?: 0L,
                                    cpu = row.optJSONObject("quantiles")?.optDouble("cpuTimeP99", 0.0)
                                        ?.takeIf { !it.isNaN() } ?: 0.0,
                                )
                            }
                        } else if (hasErrors) {
                            Log.w("CloudManager", "workers analytics: ${json.optJSONArray("errors")}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("CloudManager", "workers analytics failed: ${e.message}")
            }

            Pair(
                true,
                WorkersSnapshot(
                    workers = scripts.map { script ->
                        val n = stats[script.name] ?: return@map script
                        script.copy(requests = n.requests, errors = n.errors, cpu = n.cpu)
                    },
                    subdomain = subdomain,
                    statsAvailable = statsAvailable,
                ),
            )
        }

    /**
     * The auth headers every Cloudflare v4 call in this class builds by hand.
     *
     * Written out once here because the resource listings below are three near-identical GETs,
     * and a fourth copy of the bearer-versus-global-key branch is a fourth place for it to drift.
     *
     * Public rather than private for the same reason: Config Studio's discovery scan
     * ([com.mlmvpn.scanner.data.studio.StudioDiscovery]) reads worker settings off the same API and
     * would otherwise be that fourth copy.
     */
    fun authHeadersFor(account: CloudAccount): Headers = Headers.Builder().apply {
        if (com.mlmvpn.scanner.data.CloudAuth.useBearer(account)) {
            add("Authorization", "Bearer ${account.token}")
        } else {
            add("X-Auth-Email", account.email)
            add("X-Auth-Key", account.token)
        }
    }.build()

    /** One D1 database, as the account's resource list shows it. */
    data class D1Info(
        val name: String,
        val uuid: String,
        /** Bytes on disk. Cloudflare omits this on a database that has never been written to. */
        val sizeBytes: Long = 0L,
        val tables: Int = 0,
        /** ISO-8601 from `created_at`, or blank. */
        val createdAt: String = "",
        /** Where Cloudflare placed the primary, from `running_in_region`. Often blank. */
        val region: String = "",
        /** `alpha` on a legacy database, `production` otherwise. */
        val version: String = "",
    )

    /** One KV namespace. `title` is the name the dashboard shows; `id` is what a binding uses. */
    data class KvInfo(
        val id: String,
        val title: String,
        /**
         * How many keys it holds, or null while that has not been asked for.
         *
         * Counting costs one extra request PER NAMESPACE, so it is not part of the listing --
         * [getKvKeyCount] fills it in afterwards, per row, once the list is already on screen.
         */
        val keyCount: Int? = null,
        /** True when the count hit the page limit, so the real number is "at least" that. */
        val keyCountTruncated: Boolean = false,
    )

    /**
     * Every D1 database on the account.
     *
     * The deployers already list this endpoint to find a `mlm_db_*` or `nahan_db_*` to reuse, but
     * they throw away everything except the one id they were looking for. This returns the whole
     * list, which is what makes the quota answerable: Cloudflare's free plan caps D1 databases
     * per account, and "you are at the cap" is only actionable next to the names using it up.
     */
    suspend fun getD1Databases(account: CloudAccount): Pair<Boolean, List<D1Info>> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/d1/database?per_page=100")
                    .headers(authHeadersFor(account))
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) return@withContext Pair(false, emptyList())
                    val json = JSONObject(body)
                    if (!json.optBoolean("success", false)) return@withContext Pair(false, emptyList())
                    val arr = json.optJSONArray("result") ?: return@withContext Pair(true, emptyList())
                    val out = mutableListOf<D1Info>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        out.add(
                            D1Info(
                                name = item.optString("name"),
                                uuid = item.optString("uuid"),
                                sizeBytes = item.optLong("file_size", 0L),
                                tables = item.optInt("num_tables", 0),
                                createdAt = item.optString("created_at"),
                                region = item.optString("running_in_region"),
                                version = item.optString("version"),
                            )
                        )
                    }
                    Pair(true, out.toList())
                }
            } catch (e: Exception) {
                Log.w("CloudManager", "d1 list failed: ${e.message}")
                Pair(false, emptyList())
            }
        }

    /**
     * Every KV namespace on the account, paged.
     *
     * Paged rather than a single `per_page=100` because this is exactly the account that hits the
     * cap: the BPB panel, EDG, and the dedicated DNS resolver each bind one, and the deploy path
     * already has a comment about a deploy failing for a namespace it already owned. A truncated
     * list would under-report the very thing the screen is there to explain.
     */
    suspend fun getKvNamespaces(account: CloudAccount): Pair<Boolean, List<KvInfo>> =
        withContext(Dispatchers.IO) {
            val headers = authHeadersFor(account)
            val out = mutableListOf<KvInfo>()
            try {
                var page = 1
                while (page <= 20) {
                    val request = Request.Builder()
                        .url(
                            "https://api.cloudflare.com/client/v4/accounts/${account.accountId}" +
                                "/storage/kv/namespaces?per_page=100&page=$page"
                        )
                        .headers(headers)
                        .get()
                        .build()
                    val more = client.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        if (!response.isSuccessful) return@withContext Pair(false, emptyList())
                        val json = JSONObject(body)
                        if (!json.optBoolean("success", false)) {
                            return@withContext Pair(false, emptyList())
                        }
                        val arr = json.optJSONArray("result")
                        if (arr == null || arr.length() == 0) return@use false
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            out.add(KvInfo(id = item.optString("id"), title = item.optString("title")))
                        }
                        // A short page is the last page.
                        arr.length() >= 100
                    }
                    if (!more) break
                    page++
                }
                Pair(true, out.toList())
            } catch (e: Exception) {
                Log.w("CloudManager", "kv list failed: ${e.message}")
                Pair(false, emptyList())
            }
        }

    /**
     * How many keys a namespace holds.
     *
     * Cloudflare has no count endpoint, only a paged key listing, so this walks it. Capped at
     * [maxPages] pages of 1000 -- a namespace with more than ten thousand keys is not one the user
     * is about to eyeball, and the row says "10000+" rather than spending twenty round trips to
     * find the exact figure. The second element of the pair is that truncation flag.
     */
    suspend fun getKvKeyCount(
        account: CloudAccount,
        namespaceId: String,
        maxPages: Int = 10,
    ): Pair<Int, Boolean>? = withContext(Dispatchers.IO) {
        try {
            val headers = authHeadersFor(account)
            var total = 0
            var cursor: String? = null
            var pages = 0
            while (pages < maxPages) {
                val url = StringBuilder(
                    "https://api.cloudflare.com/client/v4/accounts/${account.accountId}" +
                        "/storage/kv/namespaces/$namespaceId/keys?limit=1000"
                )
                cursor?.let { url.append("&cursor=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
                val request = Request.Builder().url(url.toString()).headers(headers).get().build()
                val done = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val json = JSONObject(response.body?.string() ?: "")
                    if (!json.optBoolean("success", false)) return@withContext null
                    total += json.optJSONArray("result")?.length() ?: 0
                    cursor = json.optJSONObject("result_info")
                        ?.optString("cursor")
                        ?.takeIf { it.isNotEmpty() }
                    cursor == null
                }
                pages++
                if (done) return@withContext Pair(total, false)
            }
            Pair(total, true)
        } catch (e: Exception) {
            Log.w("CloudManager", "kv key count failed: ${e.message}")
            null
        }
    }

    /**
     * Drop a D1 database.
     *
     * Irreversible and unbindable-from: a worker bound to this database keeps its binding and
     * starts failing at runtime rather than at deploy time. The confirmation for that lives in the
     * UI; this only reports what Cloudflare said.
     */
    suspend fun deleteD1Database(account: CloudAccount, uuid: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/d1/database/$uuid")
                    .headers(authHeadersFor(account))
                    .delete()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful && JSONObject(body).optBoolean("success", false)) {
                        Pair(true, "Success")
                    } else {
                        Pair(false, cfError(body, response.code))
                    }
                }
            } catch (e: Exception) {
                Pair(false, e.message ?: "Unknown error")
            }
        }

    /** Drop a KV namespace and everything in it. See [deleteD1Database] for the binding caveat. */
    suspend fun deleteKvNamespace(account: CloudAccount, namespaceId: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(
                        "https://api.cloudflare.com/client/v4/accounts/${account.accountId}" +
                            "/storage/kv/namespaces/$namespaceId"
                    )
                    .headers(authHeadersFor(account))
                    .delete()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful && JSONObject(body).optBoolean("success", false)) {
                        Pair(true, "Success")
                    } else {
                        Pair(false, cfError(body, response.code))
                    }
                }
            } catch (e: Exception) {
                Pair(false, e.message ?: "Unknown error")
            }
        }

    suspend fun deleteWorker(account: CloudAccount, workerName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            val request = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to delete: $body")
                Pair(true, "Success")
            }
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }


    /**
     * Cloudflare's error envelope, reduced to something a user can act on.
     *
     * Every v4 endpoint answers failures as `{"success":false,"errors":[{"code":N,"message":"..."}]}`.
     * Pasting that whole body into a toast is why a KV failure read as a wall of JSON. The codes
     * that matter here are called out by name, because each one has a different fix and none of
     * them is obvious from Cloudflare's own wording.
     */
    private fun cfError(body: String, httpCode: Int): String {
        val err = try {
            org.json.JSONObject(body).optJSONArray("errors")?.optJSONObject(0)
        } catch (e: Exception) { null }
        val code = err?.optInt("code") ?: 0
        val message = err?.optString("message").orEmpty()
        // Logged in full as well as summarised. Every one of these failures used to reach the
        // user as a toast and nothing else, so a report of "it says error 10037" could not be
        // traced any further -- the response body that would have explained it was gone.
        Log.e("CloudflareApi", "cloudflare HTTP $httpCode code=$code: ${body.take(500)}")
        return when {
            // The API token was made from a template that does not include Workers KV. This is by
            // far the most common cause, and the raw message ("Authentication error") does not
            // hint at it at all.
            code == 10000 || httpCode == 403 ->
                S(R.string.the_token_has_no_workers_kv_permission) +
                    S(R.string.workers_kv_storage_edit_to_it_code, code)
            code == 10014 -> S(R.string.this_kv_namespace_already_exists_but_could, code)
            // Nothing in the app can clear this; the user has to delete a namespace in the
            // dashboard. Saying so beats a generic failure.
            code == 10026 || message.contains("limit", ignoreCase = true) ->
                S(R.string.this_cloudflare_account_has_reached_its_kv) +
                    S(R.string.delete_an_unused_namespace_in_the_cloudflare, code)
            // 10037 is Cloudflare refusing the resource rather than the request: the
            // credentials authenticated, but they are not allowed to touch this. It shows up
            // identically for Workers KV and for D1, which is why EDG and the MLM panel fail with
            // the same number on completely different resources. A Global API Key never hits it;
            // a scoped token built from a template that omits these permissions always does.
            code == 10037 ->
                S(R.string.cloudflare_refused_access_to_this_resource_10037) +
                    S(R.string.your_token_needs_these_permissions_workers_scripts) +
                    S(R.string.workers_kv_storage_edit_and_d1_edit) +
                    S(R.string.the_simplest_route_is_to_use_a) +
                    (if (message.isNotBlank()) S(R.string.cloudflare_s_message_message, message) else "")
            httpCode == 429 -> S(R.string.cloudflare_is_rate_limiting_requests_try_again)
            message.isNotBlank() -> "$message ($code)"
            else -> "HTTP $httpCode"
        }
    }


    /**
     * Whether a KV namespace is still there.
     *
     * Cloudflare has no "get namespace" endpoint, but listing its keys with a limit of one is
     * cheap and answers 404 for a namespace that has been deleted -- which is the case that
     * matters, because a stale recorded id would otherwise be bound to a worker that then fails
     * at runtime with no KV behind it.
     */
    private fun kvNamespaceExists(
        account: CloudAccount,
        authHeaders: okhttp3.Headers,
        namespaceId: String,
    ): Boolean = try {
        val req = Request.Builder()
            .url(
                "https://api.cloudflare.com/client/v4/accounts/${account.accountId}" +
                    "/storage/kv/namespaces/$namespaceId/keys?limit=10"
            )
            .headers(authHeaders)
            .get().build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        // A network failure is not evidence the namespace is gone. Saying "yes" here keeps the
        // deploy on the namespace it already had rather than creating a duplicate on a bad line.
        true
    }

    suspend fun deployWorker(account: CloudAccount, onProgress: (Int, String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            // 1. Check Subdomain
            onProgress(5, "Checking subdomain...")
            var subdomain = ""
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()
            
            client.newCall(subReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    if (json.optBoolean("success")) {
                        subdomain = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                }
            }

            if (subdomain.isEmpty()) {
                onProgress(10, "Creating new subdomain...")
                var created = false
                var attempts = 0
                while (!created && attempts < 3) {
                    val randomSub = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
                    val createReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                        .headers(authHeaders)
                        .put("{\"subdomain\":\"$randomSub\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    client.newCall(createReq).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            subdomain = randomSub
                            created = true
                        } else {
                            try {
                                val json = org.json.JSONObject(body)
                                val errors = json.optJSONArray("errors")
                                if (errors != null && errors.length() > 0) {
                                    val errorCode = errors.getJSONObject(0).optInt("code")
                                    if (errorCode == 10007) {
                                        return@withContext Pair(false, "ERR_ACCOUNT_HAS_SUBDOMAIN")
                                    }
                                }
                            } catch (e: Exception) { }
                        }
                    }
                    attempts++
                }
                if (!created) {
                    return@withContext Pair(false, "Failed to create a workers subdomain. Please create one manually in Cloudflare dashboard.")
                }
            }

            // 2. The account's own BPB first (PanelRegistry): the one Windows or this phone already
            // installed, with ITS UUID, password and path -- never a second Worker beside it. A
            // redeploy of the same Worker keeps its credentials, so the configs handed out keep working.
            com.mlmvpn.scanner.engines.cloud.PanelRegistry.live(account, "BPB")?.let { g ->
                if (!g.s.optString("uuid").isNullOrBlank()) {
                    account.workerUrl = g.url
                    account.uuid = g.s.optString("uuid")
                    account.trPass = g.s.optString("trPass").ifBlank { account.trPass }
                    account.subPath = g.s.optString("subPath").ifBlank { account.subPath }
                    g.kv?.let { account.kvNamespaceId = it }
                }
            }
            val keep = !account.workerUrl.isNullOrBlank()
            val workerUuid = account.uuid?.takeIf { keep && it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
            val trPass = account.trPass?.takeIf { keep && it.isNotBlank() } ?: java.util.UUID.randomUUID().toString().replace("-", "")
            val subPath = account.subPath?.takeIf { keep && it.isNotBlank() } ?: java.util.UUID.randomUUID().toString().substring(0, 8)

            // 3. KV namespace: find it, and only create it if it is genuinely not there.
            //
            // This used to create first and fall back to listing when Cloudflare answered 10014
            // ("a namespace with this title already exists"). Two things went wrong with that.
            // The title is the fixed string "mlmvpn", so EVERY redeploy after the first took the
            // fallback path -- and that path listed namespaces without `per_page`, which
            // Cloudflare defaults to 20. An account with more than twenty namespaces simply did
            // not have "mlmvpn" on the first page, so the id came back empty and the deploy died
            // reporting the CREATE error -- "already exists" -- as if that were the failure.
            //
            // Listing first is also what the EDG path here already does, and it is the reason
            // that one does not have this bug.
            onProgress(20, "Creating KV Namespace...")
            // The one this account was bound to last time, if it is still there. Checked by id
            // rather than by title, so renaming it in the Cloudflare dashboard does not orphan it.
            var namespaceId = account.kvNamespaceId?.takeIf { it.isNotBlank() }
                ?.let { id -> if (kvNamespaceExists(account, authHeaders, id)) id else "" }
                .orEmpty()
            var kvError = ""

            if (namespaceId.isEmpty()) run {
                var page = 1
                while (page <= 20 && namespaceId.isEmpty()) {
                    val listReq = Request.Builder()
                        .url(
                            "https://api.cloudflare.com/client/v4/accounts/${account.accountId}" +
                                "/storage/kv/namespaces?per_page=100&page=$page"
                        )
                        .headers(authHeaders)
                        .get().build()
                    val more = try {
                        client.newCall(listReq).execute().use { listRes ->
                            val body = listRes.body?.string() ?: ""
                            val listJson = try { JSONObject(body) } catch (e: Exception) { null }
                            if (listJson?.optBoolean("success") != true) {
                                kvError = cfError(body, listRes.code)
                                return@use false
                            }
                            val results = listJson.optJSONArray("result")
                            if (results == null || results.length() == 0) return@use false
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                if (item.optString("title") == "mlmvpn") {
                                    namespaceId = item.optString("id")
                                    break
                                }
                            }
                            // A short page is the last page.
                            results.length() >= 100
                        }
                    } catch (e: Exception) {
                        kvError = e.message ?: "network error"
                        false
                    }
                    if (!more) break
                    page++
                }
            }

            if (namespaceId.isEmpty()) {
                val kvReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces")
                    .headers(authHeaders)
                    .post("{\"title\":\"mlmvpn\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                try {
                    client.newCall(kvReq).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        val json = try { JSONObject(body) } catch (e: Exception) { null }
                        if (response.isSuccessful && json?.optBoolean("success") == true) {
                            namespaceId = json.getJSONObject("result").optString("id")
                        } else {
                            kvError = cfError(body, response.code)
                        }
                    }
                } catch (e: Exception) {
                    kvError = e.message ?: "network error"
                }
            }

            if (namespaceId.isEmpty()) {
                val detail = kvError.ifBlank { S(R.string.an_unclear_response_from_cloudflare) }
                return@withContext Pair(false, S(R.string.could_not_create_kv, detail))
            }
            // Remembered for the next deploy, which then rebinds this exact namespace and never
            // asks Cloudflare for another one.
            account.kvNamespaceId = namespaceId
            saveAccounts()

            // 4. Upload Worker
            onProgress(40, "Uploading Worker...")
            var workerScript = ""
            try {
                com.mlmvpn.scanner.store.StoreFiles.open(context, "worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
            } catch (e: Exception) {
                return@withContext Pair(false, "Failed to read worker.js from assets: ${e.message}")
            }

            // Worker name/domain must be known before building EMBEDED_SETTINGS below -- v5's own
            // config generator (src/cores/xray/configs.ts getXrCustomConfigs) uses EMBEDED_SETTINGS's
            // "mainDomain" as THE domain for every config's Host/SNI. Leaving it blank doesn't just
            // omit a field: every generated config gets an empty host/sni and Xray falls back to
            // connecting straight to whatever's in cleanIPs (BPB's own stock default,
            // "www.speedtest.net") with no TLS SNI at all, which is a dead, non-functional config.
            val workerName = PanelBuild.scriptName(account, "BPB")
                ?: com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName()
            val mainDomain = "$workerName.$subdomain.workers.dev"

            // BPB Worker Panel v5.x reads its per-account config from a runtime global named
            // EMBEDED_SETTINGS (see src/settings/settings.ts init()) instead of Cloudflare secret
            // bindings -- it actively THROWS if it sees env.UUID/env.TR_PASS bindings without that
            // global also being present. Upstream's own "BPB Wizard" tool builds this by prepending
            // an `Object.assign(globalThis, {EMBEDED_SETTINGS: {...}})` call before the bundled
            // script; we do the same here at upload time so each deploy gets its own UUID/pass/path
            // baked into the JS text itself.
            val embededSettings = JSONObject().apply {
                put("accID", account.accountId)
                put("accEmail", account.email.lowercase())
                put("apiToken", account.token)
                put("vlUUID", workerUuid)
                put("trPass", trPass)
                put("securePath", subPath)
                put("proxyIpMode", "proxyip")
                put("proxyIPs", org.json.JSONArray().apply { put("bpb.yousef.isegaro.com") })
                put("prefixes", org.json.JSONArray())
                put("fallback", "")
                put("dohUrl", "")
                put("mainDomain", mainDomain)
            }
            workerScript = "Object.assign(globalThis,{\"EMBEDED_SETTINGS\":$embededSettings});$workerScript"

            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                // BPB v5 imports `node:crypto` (src/protocols/trojan.ts, src/api/warp.ts), which only
                // exists under the nodejs_compat flag; Cloudflare rejects the upload otherwise with
                // error 10021 "No such module node:crypto". Upstream's own self-redeploy route
                // (src/api/workers.ts) always pairs this flag with a recent compatibility_date.
                put("compatibility_date", "2024-09-23")
                put("compatibility_flags", org.json.JSONArray().apply { put("nodejs_compat") })
                // No UUID/TR_PASS/SUB_PATH bindings anymore -- v5's settings.ts init() throws if it
                // sees them (see the EMBEDED_SETTINGS injection above). Only the KV binding remains.
                val bindings = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "kv_namespace")
                        put("name", "kv")
                        put("namespace_id", namespaceId)
                    })
                }
                put("bindings", bindings)
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()

            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to upload worker: $body")
            }

            // 5. Proxy settings are intentionally NOT pre-seeded here. BPB v5's KvSettings schema is
            // dozens of fields and changes across releases; the worker's own getDataset() already
            // writes correct in-code defaults to KV on its first request when no `proxySettings` key
            // exists yet, so we just let it self-initialize instead of hand-maintaining a schema copy
            // here that would silently drift out of sync on every upstream update.

            // 6. Enable Subdomain
            onProgress(80, "Enabling subdomain routing...")
            val enableReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName/subdomain")
                .headers(authHeaders)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            client.newCall(enableReq).execute().use { response ->
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to enable subdomain: ${response.body?.string()}")
            }

            // 7. Setup BPB panel password via KV directly
            onProgress(95, "Setting up panel...")
            val finalUrl = "https://$workerName.$subdomain.workers.dev"
            val setPassReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/$namespaceId/values/pwd")
                .headers(authHeaders)
                .put("Admin123!".toRequestBody("text/plain".toMediaTypeOrNull()))
                .build()
            client.newCall(setPassReq).execute().use {}
            
            // Save to account model
            account.status = "deployed"
            account.bpbVersion = PanelBuild.BPB
            account.workerUrl = finalUrl
            account.uuid = workerUuid
            account.trPass = trPass
            account.subPath = subPath
            // And to the account, for the Windows app: this is THE BPB of this account now.
            com.mlmvpn.scanner.engines.cloud.PanelRegistry.publish(account, "BPB", workerName, finalUrl, namespaceId, null,
                JSONObject().put("uuid", workerUuid).put("trPass", trPass).put("subPath", subPath))
            
            // Save to shared preferences
            saveAccounts()
            
            onProgress(100, "Done!")

            Pair(true, "Deployment Successful! URL: $finalUrl")

        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun fetchCloudConfigs(account: CloudAccount, probeClient: OkHttpClient = client): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        val client = probeClient
        try {
            if (account.workerUrl.isNullOrEmpty() || account.subPath.isNullOrEmpty() || account.trPass.isNullOrEmpty()) {
                return@withContext Pair(false, emptyList())
            }

            // Fetch subscription data using the stored workerUrl, subPath, and trPass.
            // BPB v5: subPath now doubles as the route-obfuscation "securePath" prefix -- the URL
            // shape moved from /sub/raw/{subPath}?app=xray to /{subPath}/sub/raw?app=xray.
            val subUrl = "${account.workerUrl}/${account.subPath}/sub/raw?app=xray"
            val fetchReq = Request.Builder()
                .url(subUrl)
                .get().build()

            val configs = mutableListOf<String>()
            client.newCall(fetchReq).execute().use { res ->
                if (!res.isSuccessful) return@withContext Pair(false, emptyList())
                val base64Data = res.body?.string() ?: ""
                try {
                    val decoded = String(android.util.Base64.decode(base64Data.trim(), android.util.Base64.DEFAULT))
                    configs.addAll(decoded.split("\n").map { it.trim().replace("💦", "mlmvpn") }.filter { it.isNotEmpty() }.map { com.mlmvpn.scanner.utils.AntiDpi.applySniCamouflage(it) })
                } catch (e: Exception) {
                    return@withContext Pair(false, emptyList())
                }
            }

            Pair(true, configs)
        } catch (e: javax.net.ssl.SSLException) {
            Pair(false, emptyList())
        } catch (e: Exception) {
            Pair(false, emptyList())
        }
    }

    /**
     * BPB v5's /login/authenticate expects a JSON body `{"username","password"}` (not plain text
     * like v4.2.2) and rejects any username that doesn't match the accEmail baked into
     * EMBEDED_SETTINGS at deploy time (see the addAccount-adjacent worker upload code) -- so the
     * username here MUST be account.email.lowercase(), not a guessed literal.
     */
    private fun loginBody(username: String, password: String): okhttp3.RequestBody =
        JSONObject().apply { put("username", username); put("password", password) }
            .toString().toRequestBody("application/json".toMediaTypeOrNull())

    private fun ensureLogin(account: CloudAccount): Pair<Boolean, String> {
        // securePath (== the stored subPath) prefixes every v5 route, including login.
        val loginUrl = "${account.workerUrl}/${account.subPath}/login/authenticate"
        val username = account.email.lowercase()
        val passwordsToTry = listOf("Admin123!", "admin")

        try {
            for (pass in passwordsToTry) {
                val loginReq = Request.Builder()
                    .url(loginUrl)
                    .post(loginBody(username, pass))
                    .build()

                val loginRes = client.newCall(loginReq).execute()
                if (loginRes.isSuccessful) {
                    val cookies = loginRes.headers("Set-Cookie")
                    if (cookies.isNotEmpty()) {
                        val sessionCookie = cookies.joinToString("; ") { it.split(";")[0] }
                        loginRes.close()
                        Log.d("CloudManager", "Login successful with password length: ${pass.length}")
                        return Pair(true, sessionCookie)
                    }
                }
                loginRes.close()
            }
        } catch (e: javax.net.ssl.SSLException) {
            return Pair(false, S(R.string.this_subdomain_s_ssl_certificate_is_not))
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("SSL") || msg.contains("Handshake") || msg.contains("ssl=")) {
                return Pair(false, S(R.string.this_subdomain_s_ssl_certificate_is_not))
            }
            return Pair(false, "Error during login: ${e.message}")
        }

        // If all failed, forcefully reset password via Cloudflare API
        forceResetPasswordViaCloudflareAPI(account)

        // Try login one last time with Admin123! Retry for up to 30 seconds to allow KV edge propagation
        for (i in 1..10) {
            Log.d("CloudManager", "ensureLogin retry $i: Attempting login with password: Admin123!")
            val finalLoginReq = Request.Builder()
                .url(loginUrl)
                .post(loginBody(username, "Admin123!"))
                .build()
            val finalLoginRes = client.newCall(finalLoginReq).execute()
            if (finalLoginRes.isSuccessful) {
                val cookies = finalLoginRes.headers("Set-Cookie")
                if (cookies.isNotEmpty()) {
                    val sessionCookie = cookies.joinToString("; ") { it.split(";")[0] }
                    finalLoginRes.close()
                    Log.d("CloudManager", "Login successful after force KV reset.")
                    return Pair(true, sessionCookie)
                }
            }
            val code = finalLoginRes.code
            val errBody = finalLoginRes.body?.string()
            finalLoginRes.close()
            
            if (code == 401) {
                Log.d("CloudManager", "ensureLogin retry $i: 401 Wrong password, waiting for KV propagation...")
                Thread.sleep(3000)
                continue
            } else {
                return Pair(false, "Code: $code, Body: $errBody")
            }
        }
        return Pair(false, "Code: 401, Body: Failed to login after KV reset due to propagation timeout.")
    }

    private fun forceResetPasswordViaCloudflareAPI(account: CloudAccount) {
        try {
            val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            var namespaceId = ""
            val listReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces")
                .headers(authHeaders)
                .get().build()
            client.newCall(listReq).execute().use { listRes ->
                val listJson = JSONObject(listRes.body?.string() ?: "")
                if (listJson.optBoolean("success")) {
                    val results = listJson.getJSONArray("result")
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val title = item.getString("title")
                        if (title == "mlmvpn" || title.contains("mlmvpn")) {
                            namespaceId = item.getString("id")
                            break
                        }
                    }
                }
            }

            if (namespaceId.isNotEmpty()) {
                val setPassReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/$namespaceId/values/pwd")
                    .headers(authHeaders)
                    .put("Admin123!".toRequestBody("text/plain".toMediaTypeOrNull()))
                    .build()
                client.newCall(setPassReq).execute().use {
                    Log.d("CloudManager", "forceReset KV pwd result: ${it.code} - ${it.body?.string()}")
                }
                Thread.sleep(2000) // Wait for Cloudflare KV edge propagation
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun fetchWorkerSettings(account: CloudAccount): JSONObject? = withContext(Dispatchers.IO) {
        try {
            if (account.workerUrl.isNullOrEmpty()) return@withContext null

            // Step 1: Login using helper
            val loginResult = ensureLogin(account)
            if (!loginResult.first) {
                Log.e("CloudManager", "fetchWorkerSettings ensureLogin failed: ${loginResult.second}")
                return@withContext null
            }
            val sessionCookie = loginResult.second

            // Step 2: Get settings
            val settingsUrl = "${account.workerUrl}/${account.subPath}/panel/settings"
            val settingsReqBuilder = Request.Builder().url(settingsUrl).get()
            if (sessionCookie.isNotEmpty()) {
                settingsReqBuilder.header("Cookie", sessionCookie)
            }

            client.newCall(settingsReqBuilder.build()).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.optBoolean("success")) {
                        val bodyObj = json.optJSONObject("body")
                        return@withContext bodyObj?.optJSONObject("proxySettings")
                    }
                }
            }
            return@withContext null
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun updateWorkerSettings(account: CloudAccount, settingsJson: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (account.workerUrl.isNullOrEmpty()) {
                return@withContext Pair(false, "Worker URL is missing")
            }

            // Step 1: Login using helper
            val loginResult = ensureLogin(account)
            if (!loginResult.first) {
                return@withContext Pair(false, "Login failed: ${loginResult.second}")
            }
            val sessionCookie = loginResult.second

            // Step 2: Put settings to /panel/update-settings
            val settingsUrl = "${account.workerUrl}/${account.subPath}/panel/update-settings"
            val settingsReqBuilder = Request.Builder()
                .url(settingsUrl)
                .put(settingsJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))

            if (sessionCookie.isNotEmpty()) {
                settingsReqBuilder.header("Cookie", sessionCookie)
            }

            client.newCall(settingsReqBuilder.build()).execute().use { res ->
                if (!res.isSuccessful) {
                    val bodyStr = res.body?.string()
                    return@withContext Pair(false, "Failed to update settings: ${res.code} - $bodyStr")
                }
            }

            Pair(true, S(R.string.settings_updated_successfully))
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun deployEdgWorker(account: CloudAccount, onProgress: (Int, String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            // 1. Check Subdomain
            onProgress(10, "Checking subdomain...")
            var subdomain = ""
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()
            
            client.newCall(subReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    if (json.optBoolean("success")) {
                        subdomain = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                }
            }

            if (subdomain.isEmpty()) {
                onProgress(20, "Creating new subdomain...")
                var created = false
                var attempts = 0
                while (!created && attempts < 3) {
                    val randomSub = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
                    val createReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                        .headers(authHeaders)
                        .put("{\"subdomain\":\"$randomSub\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    client.newCall(createReq).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            subdomain = randomSub
                            created = true
                        } else {
                            try {
                                val json = org.json.JSONObject(body)
                                val errors = json.optJSONArray("errors")
                                if (errors != null && errors.length() > 0) {
                                    val errorCode = errors.getJSONObject(0).optInt("code")
                                    if (errorCode == 10007) {
                                        return@withContext Pair(false, "ERR_ACCOUNT_HAS_SUBDOMAIN")
                                    }
                                }
                            } catch (e: Exception) { }
                        }
                    }
                    attempts++
                }
                if (!created) {
                    return@withContext Pair(false, "Failed to create a workers subdomain. Please create one manually in Cloudflare dashboard.")
                }
            }

            // 2. Setup Variables
            // The account's own Edge first (PanelRegistry), with its UUID -- and a redeploy of the same
            // Worker keeps its UUID, so its configs keep working.
            com.mlmvpn.scanner.engines.cloud.PanelRegistry.live(account, "EDG")?.let { g ->
                if (!g.s.optString("uuid").isNullOrBlank()) {
                    account.edgWorkerUrl = g.url
                    account.edgUuid = g.s.optString("uuid")
                    g.kv?.let { account.edgKvNamespaceId = it }
                }
            }
            val edgUuid = account.edgUuid?.takeIf { !account.edgWorkerUrl.isNullOrBlank() && it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
            val edgAdminPass = account.edgAdminPass.takeIf { !it.isNullOrEmpty() } ?: java.util.UUID.randomUUID().toString().substring(0, 8)
            val proxyIp = "proxyip.cmliussss.net"

            // 3. Reuse the existing KV namespace if this account was already deployed before,
            // instead of always provisioning a new one on every deploy/retry (same quota-leak
            // pattern found and fixed for MLM/Nahan's D1 databases: unbounded retries would
            // otherwise silently create a new orphaned namespace every time).
            onProgress(40, "Setting up KV Namespace...")
            var kvId = account.edgKvNamespaceId?.takeIf { it.isNotEmpty() } ?: ""
            var kvErrorInfo = ""

            // The one this account was bound to last time, if it is still there. Same reason
            // as the BPB path: a Cloudflare account caps how many KV namespaces it may hold, and
            // asking for a new one on every deploy is what walks an account into that cap.
            if (kvId.isEmpty()) {
                kvId = account.edgKvNamespaceId?.takeIf { it.isNotBlank() }
                    ?.let { id -> if (kvNamespaceExists(account, authHeaders, id)) id else null }
                    .orEmpty()
            }

            if (kvId.isEmpty()) {
                val listReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces?per_page=100")
                    .headers(authHeaders)
                    .get().build()
                try {
                    client.newCall(listReq).execute().use { listRes ->
                        val listJson = org.json.JSONObject(listRes.body?.string() ?: "")
                        if (listJson.optBoolean("success")) {
                            val results = listJson.getJSONArray("result")
                            for (i in 0 until results.length()) {
                                val entry = results.getJSONObject(i)
                                if (entry.optString("title").startsWith("edg_")) {
                                    kvId = entry.getString("id")
                                    break
                                }
                            }
                        }
                    }
                } catch (e: Exception) { }
            }

            if (kvId.isEmpty()) {
                val kvTitle = "edg_${java.util.UUID.randomUUID().toString().substring(0, 8).replace("-", "")}"
                val createKvReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces")
                    .headers(authHeaders)
                    .post("{\"title\":\"$kvTitle\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                try {
                    val kvRes = client.newCall(createKvReq).execute()
                    val kvBody = kvRes.body?.string() ?: ""
                    val kvJson = org.json.JSONObject(kvBody)
                    if (kvRes.isSuccessful && kvJson.optBoolean("success", false)) {
                        kvId = kvJson.getJSONObject("result").getString("id")
                    } else {
                        kvErrorInfo = cfError(kvBody, kvRes.code)
                    }
                } catch (e: Exception) {
                    kvErrorInfo = e.message ?: "unknown error"
                }
            }

            if (kvId.isEmpty()) {
                return@withContext Pair(false, S(R.string.could_not_create_kv_for_edg_kverrorinfo, kvErrorInfo))
            }

            // Save KV namespace ID to account for EDG Settings
            account.edgKvNamespaceId = kvId
            saveAccounts()

            // 4. Upload Worker
            onProgress(50, "Uploading EDG Worker...")
            var workerScript = ""
            try {
                com.mlmvpn.scanner.store.StoreFiles.open(context, "edg_worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
            } catch (e: Exception) {
                return@withContext Pair(false, "Failed to read edg_worker.js from assets: ${e.message}")
            }

            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2025-11-04")
                val bindings = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "UUID")
                        put("text", edgUuid)
                    })
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "PROXYIP")
                        put("text", proxyIp)
                    })
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "ADMIN")
                        put("text", edgAdminPass)
                    })
                    put(JSONObject().apply {
                        put("type", "kv_namespace")
                        put("name", "KV")
                        put("namespace_id", kvId)
                    })
                }
                put("bindings", bindings)
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val workerName = PanelBuild.scriptName(account, "EDG")
                ?: (com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-edg")
            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()

            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to upload EDG worker: $body")
            }

            // 4. Enable Subdomain
            onProgress(80, "Enabling subdomain routing...")
            val enableReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName/subdomain")
                .headers(authHeaders)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            client.newCall(enableReq).execute().use { response ->
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to enable subdomain: ${response.body?.string()}")
            }

            // 5. Finalize
            onProgress(100, "Done!")
            val finalUrl = "https://$workerName.$subdomain.workers.dev"
            
            account.edgStatus = "deployed"
            account.edgVersion = PanelBuild.EDG
            account.edgWorkerUrl = finalUrl
            account.edgUuid = edgUuid
            account.edgAdminPass = edgAdminPass
            com.mlmvpn.scanner.engines.cloud.PanelRegistry.publish(account, "EDG", workerName, finalUrl, kvId, null, JSONObject().put("uuid", edgUuid))
            
            saveAccounts()

            Pair(true, "EDG Deployment Successful! URL: $finalUrl")

        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * Deploys the per-user Dedicated DNS relay worker (dns_worker.js) onto the user's own
     * Cloudflare account. Mirrors [deployEdgWorker] exactly: ensure a workers.dev subdomain,
     * create a KV namespace (for the resolver's short-TTL cache), upload the worker with
     * DEFAULT_REGION + KV bindings, enable subdomain routing, and persist dnsWorkerUrl/dnsStatus.
     *
     * The resulting worker exposes /resolve (JSON) and /dns-query (binary DoH passthrough) and
     * steers upstream (Google) answers per region via EDNS Client Subnet -- see dns_worker.js.
     */
    suspend fun deployDnsWorker(account: CloudAccount, onProgress: (Int, String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val TAG = "DnsWorkerDeploy"
        try {
            Log.d(TAG, "Starting deploy for account=${account.name} (${account.accountId})")
            val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            // 1. Check Subdomain
            onProgress(10, "Checking subdomain...")
            var subdomain = ""
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()

            client.newCall(subReq).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d(TAG, "GET subdomain -> HTTP ${response.code}: ${body.take(300)}")
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.optBoolean("success")) {
                        subdomain = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                    Unit
                } else {
                    Log.w(TAG, "GET subdomain failed: HTTP ${response.code}")
                    Unit
                }
            }

            if (subdomain.isEmpty()) {
                onProgress(20, "Creating new subdomain...")
                var created = false
                var attempts = 0
                while (!created && attempts < 3) {
                    val randomSub = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
                    val createReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                        .headers(authHeaders)
                        .put("{\"subdomain\":\"$randomSub\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    client.newCall(createReq).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        Log.d(TAG, "PUT create-subdomain (attempt ${attempts + 1}) -> HTTP ${response.code}: ${body.take(300)}")
                        if (response.isSuccessful) {
                            subdomain = randomSub
                            created = true
                        } else {
                            try {
                                val json = org.json.JSONObject(body)
                                val errors = json.optJSONArray("errors")
                                if (errors != null && errors.length() > 0) {
                                    val errorCode = errors.getJSONObject(0).optInt("code")
                                    if (errorCode == 10007) {
                                        // The account ALREADY has a workers.dev subdomain; the initial
                                        // GET just hadn't propagated yet (common right after connecting
                                        // an account). Don't fail with a "duplicate" -- re-fetch the
                                        // existing subdomain (with a few retries) and carry on. This is
                                        // exactly the case that used to force an app restart.
                                        Log.w(TAG, "Account already has a subdomain (code 10007) -- re-fetching it")
                                        var fetched = ""
                                        for (r in 0 until 5) {
                                            kotlinx.coroutines.delay(800)
                                            val gReq = Request.Builder()
                                                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                                                .headers(authHeaders)
                                                .get().build()
                                            client.newCall(gReq).execute().use { gr ->
                                                val gb = gr.body?.string() ?: ""
                                                if (gr.isSuccessful) {
                                                    val gj = JSONObject(gb)
                                                    if (gj.optBoolean("success")) {
                                                        fetched = gj.optJSONObject("result")?.optString("subdomain", "") ?: ""
                                                    }
                                                }
                                            }
                                            if (fetched.isNotEmpty()) break
                                        }
                                        if (fetched.isNotEmpty()) {
                                            subdomain = fetched
                                            created = true
                                            Log.d(TAG, "Recovered existing subdomain: $subdomain")
                                        } else {
                                            return@withContext Pair(false, "ERR_ACCOUNT_HAS_SUBDOMAIN")
                                        }
                                    }
                                }
                            } catch (e: Exception) { }
                        }
                    }
                    attempts++
                }
                if (!created) {
                    Log.e(TAG, "Failed to create a workers subdomain after $attempts attempts")
                    return@withContext Pair(false, "Failed to create a workers subdomain. Please create one manually in Cloudflare dashboard.")
                }
            }
            Log.d(TAG, "Using subdomain: $subdomain")

            // 2. Create KV Namespace (used by the resolver for short-TTL caching)
            onProgress(40, "Creating KV Namespace...")
            val kvTitle = "dns_${java.util.UUID.randomUUID().toString().substring(0, 8).replace("-", "")}"
            val createKvReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces")
                .headers(authHeaders)
                .post("{\"title\":\"$kvTitle\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            var kvId = ""
            var kvErrorBody = ""
            try {
                val kvRes = client.newCall(createKvReq).execute()
                val kvBody = kvRes.body?.string() ?: ""
                kvErrorBody = kvBody
                Log.d(TAG, "POST kv/namespaces -> HTTP ${kvRes.code}: ${kvBody.take(300)}")
                if (kvRes.isSuccessful) {
                    val kvJson = org.json.JSONObject(kvBody)
                    if (kvJson.optBoolean("success", false)) {
                        kvId = kvJson.getJSONObject("result").getString("id")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "KV namespace creation threw an exception", e)
            }

            if (kvId.isEmpty()) {
                Log.e(TAG, "KV namespace id empty. Response was: ${kvErrorBody.take(500)}")
                return@withContext Pair(false, S(R.string.could_not_create_kv_for_dns, cfError(kvErrorBody, 0)))
            }
            Log.d(TAG, "KV namespace created: $kvId")

            account.dnsKvNamespaceId = kvId
            saveAccounts()

            // 3. Upload Worker
            onProgress(60, "Uploading DNS Worker...")
            var workerScript = ""
            try {
                com.mlmvpn.scanner.store.StoreFiles.open(context, "dns_worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
                Log.d(TAG, "Loaded dns_worker.js from assets (${workerScript.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read dns_worker.js from assets", e)
                return@withContext Pair(false, "Failed to read dns_worker.js from assets: ${e.message}")
            }

            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2024-03-03")
                val bindings = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "DEFAULT_REGION")
                        put("text", "AE")
                    })
                    put(JSONObject().apply {
                        put("type", "kv_namespace")
                        put("name", "KV")
                        put("namespace_id", kvId)
                    })
                }
                put("bindings", bindings)
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val workerName = PanelBuild.scriptName(account, "DNS")
                ?: (com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-dns")
            Log.d(TAG, "Uploading worker as: $workerName")
            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()

            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                Log.d(TAG, "PUT workers/scripts/$workerName -> HTTP ${response.code}: ${body?.take(500)}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "Worker upload failed: HTTP ${response.code}")
                    return@withContext Pair(false, "Failed to upload DNS worker (HTTP ${response.code}): ${body?.take(300)}")
                }
            }

            // 4. Enable Subdomain
            onProgress(80, "Enabling subdomain routing...")
            val enableReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName/subdomain")
                .headers(authHeaders)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(enableReq).execute().use { response ->
                val body = response.body?.string()
                Log.d(TAG, "POST enable subdomain -> HTTP ${response.code}: ${body?.take(300)}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "Enable subdomain failed: HTTP ${response.code}")
                    return@withContext Pair(false, "Failed to enable subdomain (HTTP ${response.code}): ${body?.take(300)}")
                }
            }

            // 5. Finalize
            onProgress(100, "Done!")
            val finalUrl = "https://$workerName.$subdomain.workers.dev"

            account.dnsStatus = "deployed"
            account.dnsVersion = PanelBuild.DNS
            account.dnsWorkerUrl = finalUrl

            saveAccounts()

            Log.d(TAG, "Deploy succeeded: $finalUrl")
            Pair(true, "Dedicated DNS Worker deployed! URL: $finalUrl")

        } catch (e: Exception) {
            Log.e(TAG, "Deploy threw an unhandled exception", e)
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun fetchEdgConfigs(account: CloudAccount, probeClient: OkHttpClient = client): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        val client = probeClient
        try {
            if (account.edgWorkerUrl.isNullOrEmpty() || account.edgUuid.isNullOrEmpty()) {
                return@withContext Pair(false, emptyList())
            }

            val workerHost = account.edgWorkerUrl!!.replace("https://", "").replace("http://", "").trimEnd('/')
            val uuid = account.edgUuid!!

            // Fetch proxy IP from KV if available
            var proxyIpStr = ""
            if (!account.edgKvNamespaceId.isNullOrEmpty() && account.accountId.isNotEmpty()) {
                try {
                    val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
                    val authHeaders = okhttp3.Headers.Builder().apply {
                        if (isCfat) add("Authorization", "Bearer ${account.token}")
                        else {
                            add("X-Auth-Email", account.email)
                            add("X-Auth-Key", account.token)
                        }
                        add("Content-Type", "application/json")
                    }.build()
                    val req = okhttp3.Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/storage/kv/namespaces/${account.edgKvNamespaceId}/values/config.json")
                        .headers(authHeaders)
                        .get()
                        .build()
                    val res = client.newCall(req).execute()
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        val json = org.json.JSONObject(body)
                        val fanDai = json.optJSONObject("反代")
                        if (fanDai != null) {
                            val ip = fanDai.optString("PROXYIP", "")
                            if (ip.isNotBlank() && ip != "auto") {
                                proxyIpStr = "proxyip=$ip&"
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore, use default path
                }
            }

            // Encode the path query part. edg_worker parses searchParams.get('proxyip').
            val queryParam = if (proxyIpStr.isNotEmpty()) "?${proxyIpStr}ed=2560" else "?ed=2560"
            val encodedPath = java.net.URLEncoder.encode("/$queryParam", "UTF-8")

            val vlessMain = "vless://$uuid@$workerHost:443?encryption=none&security=tls&sni=$workerHost&type=ws&host=$workerHost&path=$encodedPath#EDG-Auto"
            val vlessCF1 = "vless://$uuid@104.21.5.155:443?encryption=none&security=tls&sni=$workerHost&type=ws&host=$workerHost&path=$encodedPath#EDG-CF1"
            val vlessCF2 = "vless://$uuid@172.67.13.12:443?encryption=none&security=tls&sni=$workerHost&type=ws&host=$workerHost&path=$encodedPath#EDG-CF2"
            
            val configs = listOf(vlessMain, vlessCF1, vlessCF2).map { 
                com.mlmvpn.scanner.utils.AntiDpi.applySniCamouflage(it) 
            }

            Pair(true, configs)
        } catch (e: Exception) {
            Pair(false, emptyList())
        }
    }

    /**
     * Deploys the GST relay accelerator worker (gst_relay_worker.js) onto the user's
     * Cloudflare account. It speaks the same relay protocol as the Google Apps Script
     * (assets/gst/Code.gs), so the mhrv-rs core can use it as an optional `relay_url`
     * to speed up / stabilize the Google tunnel. The shared secret [authKey] is injected
     * as an AUTH_KEY secret binding and MUST match the GST relay auth key in the app.
     *
     * Mirrors [deployEdgWorker]: ensure a workers.dev subdomain, upload the worker,
     * enable subdomain routing, and persist gstRelayWorkerUrl/gstRelayStatus.
     */
    suspend fun deployGstRelayWorker(
        account: CloudAccount,
        authKey: String,
        onProgress: (Int, String) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            // 1. Check / create subdomain
            onProgress(10, "Checking subdomain...")
            var subdomain = ""
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()
            client.newCall(subReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    if (json.optBoolean("success")) {
                        subdomain = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                }
            }
            if (subdomain.isEmpty()) {
                onProgress(20, "Creating new subdomain...")
                var created = false
                var attempts = 0
                while (!created && attempts < 3) {
                    val randomSub = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
                    val createReq = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                        .headers(authHeaders)
                        .put("{\"subdomain\":\"$randomSub\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    client.newCall(createReq).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            subdomain = randomSub
                            created = true
                        } else if (body.contains("10007")) {
                            return@withContext Pair(false, "ERR_ACCOUNT_HAS_SUBDOMAIN")
                        }
                    }
                    attempts++
                }
                if (!created) {
                    return@withContext Pair(false, "Failed to create a workers subdomain.")
                }
            }

            // 2. Upload worker with AUTH_KEY secret binding
            onProgress(50, "Uploading GST relay worker...")
            var workerScript = ""
            try {
                com.mlmvpn.scanner.store.StoreFiles.open(context, "gst_relay_worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
            } catch (e: Exception) {
                return@withContext Pair(false, "Failed to read gst_relay_worker.js: ${e.message}")
            }

            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2024-09-23")
                // Force the worker's fetch() subrequests to resolve to PUBLIC IPs, so
                // fetching script.googleapis.com isn't mistaken for a same-zone request
                // (Cloudflare error 1042). Required for the deploy-proxy use.
                put("compatibility_flags", org.json.JSONArray().apply { put("global_fetch_strictly_public") })
                put("bindings", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        // plain_text (not secret_text) � secret_text bindings are not
                        // populated by the multipart script-upload path, leaving env.AUTH_KEY
                        // empty so the worker rejects every request with its decoy page.
                        put("type", "plain_text")
                        put("name", "AUTH_KEY")
                        put("text", authKey)
                    })
                })
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val workerName = com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-gst"
            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()
            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to upload GST worker: $body")
            }

            // 3. Enable subdomain routing
            onProgress(80, "Enabling subdomain routing...")
            val enableReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName/subdomain")
                .headers(authHeaders)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(enableReq).execute().use { response ->
                if (!response.isSuccessful) return@withContext Pair(false, "Failed to enable subdomain: ${response.body?.string()}")
            }

            // 4. Finalize
            onProgress(100, "Done!")
            val finalUrl = "https://$workerName.$subdomain.workers.dev"
            account.gstRelayStatus = "deployed"
            account.gstRelayWorkerUrl = finalUrl
            saveAccounts()

            Pair(true, finalUrl)
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }


    /**
     * Which scheme this credential actually works with, established by asking Cloudflare.
     *
     * `/user/tokens/verify` answers 200 only for an API token; `/user` with the X-Auth pair
     * answers 200 only for a Global API Key. Returns null when neither works -- a revoked or
     * mistyped credential -- and the caller then leaves the account as it was rather than
     * recording a guess.
     */
    private fun proveAuthScheme(account: CloudAccount, probeClient: OkHttpClient = client): String? {
        val client = probeClient
        fun ok(scheme: String): Boolean = try {
            val url = if (scheme == "bearer") "https://api.cloudflare.com/client/v4/user/tokens/verify"
                      else "https://api.cloudflare.com/client/v4/user"
            val headers = if (scheme == "bearer") {
                Headers.Builder()
                    .add("Authorization", "Bearer ${account.token.trim()}")
                    .add("Content-Type", "application/json").build()
            } else {
                Headers.Builder()
                    .add("X-Auth-Email", account.email.trim())
                    .add("X-Auth-Key", account.token.trim())
                    .add("Content-Type", "application/json").build()
            }
            client.newCall(Request.Builder().url(url).headers(headers).get().build())
                .execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }

        val order = if (CloudAuth.isGlobalKey(account.token, account.email))
            listOf("global", "bearer") else listOf("bearer", "global")
        for (scheme in order) {
            if (scheme == "global" && account.email.isBlank()) continue
            if (ok(scheme)) return scheme
        }
        return null
    }

    /**
     * The two booleans the cloud panel gates on, from [probeAccountStatus].
     *
     * `unknown` counts as verified on purpose. The old probe could not tell "Cloudflare says this
     * email is unverified" apart from "Cloudflare would not answer the question", and reported both
     * as `false` -- which locked the whole panel behind an email-verification overlay that a user
     * with a perfectly verified account had no way out of. Locking a working account out of the
     * feature is a far worse failure than letting an unverified one reach a subdomain-create call
     * that Cloudflare will refuse anyway, with its own message.
     */
    suspend fun checkAccountStatus(account: CloudAccount): Pair<Boolean, Boolean> {
        val probe = probeAccountStatus(account)
        return Pair(probe.hasSubdomain, probe.emailVerified != false)
    }

    /**
     * Ask Cloudflare what state this account is in, and keep what it said.
     *
     * **There is no email-verification field in the Cloudflare API.** `GET /client/v4/user` returns
     * id, email, name, country, phone, 2FA and zone flags -- and no `email_verified`. The old code
     * read `result.email_verified` with a default of `false`, so that branch returned "not
     * verified" for *every* account it ever reached, verified or not. On top of that `/user` needs
     * the `User Details Read` permission, which a Workers-scoped API token does not carry, so for
     * token accounts the call is a 403 before the missing field even matters.
     *
     * Together those made the outcome unconditional: the only way an account could come back
     * verified was the shortcut below -- it already has a workers.dev subdomain, which Cloudflare
     * only issues to a verified account. Every account that did not yet have one was told its email
     * was unverified, and the "check status" button re-ran the same dead query.
     *
     * So the question is no longer asked of an endpoint that cannot answer it. A subdomain proves
     * verification; nothing else here disproves it. The one thing that can is Cloudflare rejecting
     * a subdomain *create* for that stated reason -- see [createSubdomainOnly] -- and that answer
     * comes from Cloudflare's own error, not from us inferring it.
     */
    suspend fun probeAccountStatus(account: CloudAccount, probeClient: OkHttpClient = client, readOnly: Boolean = false): CloudVerifyProbe = withContext(Dispatchers.IO) {
        val client = probeClient
        val started = System.currentTimeMillis()
        var subHttp = -1
        var subErrors = ""
        var hasSubdomain = false
        var tokenHttp = 0
        var tokenErrors = ""
        var transport = ""

        // An account saved before the scheme was recorded proves it here, once, and keeps the
        // answer. Without this the fallback inference decides -- and inference is what put every
        // token account on Global-Key headers in the first place.
        if (account.authScheme.isNullOrBlank()) {
            account.authScheme = proveAuthScheme(account, client)
            if (account.authScheme != null && !readOnly) saveAccounts()
        }
        val scheme = account.authScheme
            ?: if (CloudAuth.useBearer(account)) "bearer?" else "global?"

        // Was hardcoded to the Global API Key headers, with no Bearer branch anywhere in this
        // function. For a token account that made the subdomain probe fail every time, which
        // surfaced to the user as "this account has no workers subdomain".
        val authHeaders = CloudAuth.headers(account)

        try {
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()

            client.newCall(subReq).execute().use { response ->
                subHttp = response.code
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = runCatching { org.json.JSONObject(body) }.getOrNull()
                    if (json?.optBoolean("success") == true) {
                        val name = json.optJSONObject("result")?.optString("subdomain", "").orEmpty()
                        hasSubdomain = name.isNotEmpty()
                        // A 200 that carries no subdomain is the "you never made one" answer, not
                        // an error -- record it so the report can tell the two apart.
                        if (!hasSubdomain) subErrors = "200 but result.subdomain empty"
                    } else {
                        subErrors = CloudVerifyProbe.errorsOf(body)
                    }
                } else {
                    subErrors = CloudVerifyProbe.errorsOf(body)
                }
            }
        } catch (e: Exception) {
            transport = "subdomain: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
        }

        // Is the credential itself still live? A revoked or expired token fails the subdomain probe
        // in a way that looks identical to a permission it was never granted, and the two need
        // completely different advice. Token accounts only: `/user/tokens/verify` is the token
        // endpoint, and a Global API Key has no equivalent that does not also need /user.
        if (CloudAuth.useBearer(account)) {
            try {
                val req = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/user/tokens/verify")
                    .headers(authHeaders)
                    .get().build()
                client.newCall(req).execute().use { response ->
                    tokenHttp = response.code
                    if (!response.isSuccessful) {
                        tokenErrors = CloudVerifyProbe.errorsOf(response.body?.string())
                    }
                }
            } catch (e: Exception) {
                tokenHttp = -1
                if (transport.isBlank()) {
                    transport = "tokens/verify: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                }
            }
        }

        CloudVerifyProbe(
            scheme = scheme,
            subdomainHttp = subHttp,
            subdomainErrors = subErrors,
            hasSubdomain = hasSubdomain,
            tokenVerifyHttp = tokenHttp,
            tokenVerifyErrors = tokenErrors,
            // Cloudflare will not issue a workers.dev subdomain to an unverified account, so having
            // one is proof. Not having one proves nothing at all.
            emailVerified = if (hasSubdomain) true else null,
            basis = if (hasSubdomain) "subdomain" else "unknown",
            transport = transport,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }


    /**
     * Ask Cloudflare whether a credential works, before there is an account to attach it to.
     *
     * The troubleshooting page can be reached from the add screen, and everything the account probe
     * asks about -- the workers.dev subdomain, the email lock, the auth scheme on file -- does not
     * exist yet at that point. What does exist is the pair of questions [addAccount] itself asks
     * and then throws away: does this credential authenticate, and does it list an account? A user
     * who cannot get past that screen has no other way to find out which of the two failed, and
     * "the credentials were rejected" fits a revoked token, a Global API Key typed without its
     * email, a token with no account permission, and a phone that never reached Cloudflare.
     *
     * Reported through [CloudVerifyProbe] so one screen and one report format cover both modes.
     * `tokenVerify` carries the authentication answer; `subdomain`, whose slot is free here,
     * carries the account listing.
     */
    suspend fun probeCredential(rawToken: String, rawEmail: String, probeClient: OkHttpClient = client): CloudVerifyProbe =
        withContext(Dispatchers.IO) {
            val client = probeClient
            val started = System.currentTimeMillis()
            val token = rawToken.replace(Regex("[^a-zA-Z0-9_-]"), "").trim()
            val email = rawEmail.trim()

            var authHttp = -1
            var authErrors = ""
            var listHttp = 0
            var listErrors = ""
            var transport = ""
            var proven: String? = null

            fun headersFor(s: String): Headers =
                if (s == "bearer") Headers.Builder()
                    .add("Authorization", "Bearer $token")
                    .add("Content-Type", "application/json").build()
                else Headers.Builder()
                    .add("X-Auth-Email", email)
                    .add("X-Auth-Key", token)
                    .add("Content-Type", "application/json").build()

            // `/user/tokens/verify` answers 200 only for an API token; `/user` with the X-Auth pair
            // answers 200 only for a Global API Key. Shape picks the order and saves a request; the
            // other is still tried, because the shape check is a heuristic and this screen exists
            // for the cases where heuristics were wrong.
            val order = if (CloudAuth.isGlobalKey(token, email)) listOf("global", "bearer")
                        else listOf("bearer", "global")
            for (s in order) {
                if (s == "global" && email.isBlank()) continue
                try {
                    val url = if (s == "bearer") "https://api.cloudflare.com/client/v4/user/tokens/verify"
                              else "https://api.cloudflare.com/client/v4/user"
                    client.newCall(Request.Builder().url(url).headers(headersFor(s)).get().build())
                        .execute().use { res ->
                            authHttp = res.code
                            if (res.isSuccessful) proven = s
                            else authErrors = CloudVerifyProbe.errorsOf(res.body?.string())
                        }
                } catch (e: Exception) {
                    authHttp = -1
                    transport = "auth: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                }
                if (proven != null) break
            }

            // Authentication is not the same permission as seeing an account, and addAccount needs
            // both. A token that verifies and then lists nothing is the single most confusing
            // outcome on the add screen, because the error it produces talks about credentials.
            proven?.let { s ->
                try {
                    val req = Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts")
                        .headers(headersFor(s)).get().build()
                    client.newCall(req).execute().use { res ->
                        listHttp = res.code
                        val body = res.body?.string() ?: ""
                        if (res.isSuccessful) {
                            val n = runCatching {
                                org.json.JSONObject(body).optJSONArray("result")?.length() ?: 0
                            }.getOrDefault(0)
                            if (n == 0) listErrors = "200 but result[] is empty"
                        } else {
                            listErrors = CloudVerifyProbe.errorsOf(body)
                        }
                    }
                } catch (e: Exception) {
                    listHttp = -1
                    if (transport.isBlank()) {
                        transport = "accounts: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                    }
                }
            }

            CloudVerifyProbe(
                scheme = proven ?: if (CloudAuth.isGlobalKey(token, email)) "global?" else "bearer?",
                subdomainHttp = listHttp,
                subdomainErrors = listErrors,
                hasSubdomain = false,
                tokenVerifyHttp = authHttp,
                tokenVerifyErrors = authErrors,
                emailVerified = null,
                // Named so a report from this screen is never mistaken for one about a saved
                // account: the fields mean different things in the two modes.
                basis = if (email.isBlank()) "credential/no-email" else "credential",
                transport = transport,
                elapsedMs = System.currentTimeMillis() - started,
            )
        }

    /**
     * Cloudflare's verdict on a failed subdomain create, as one word.
     *
     * `EMAIL_UNVERIFIED` is the only thing in this app that is allowed to declare an email
     * unverified, and it comes from Cloudflare saying so about the one operation that requires it
     * -- not from an endpoint that has no such field. Everything else is `OTHER`, which the UI must
     * show as itself rather than as a verification problem.
     */
    enum class SubdomainFailure { NONE, EMAIL_UNVERIFIED, OTHER }

    data class SubdomainResult(
        val ok: Boolean,
        /** The subdomain on success, Cloudflare's error text on failure. */
        val message: String,
        val failure: SubdomainFailure = SubdomainFailure.NONE,
        /** HTTP status of the create call, -1 when it never completed. */
        val http: Int = 0,
        /** Cloudflare's `errors[]`, for the diagnostic report. */
        val errors: String = "",
    )

    suspend fun createSubdomainOnly(account: CloudAccount): Pair<Boolean, String> {
        val r = createSubdomain(account)
        return Pair(r.ok, r.message)
    }

    /**
     * Create this account's workers.dev subdomain, and say precisely why if Cloudflare refuses.
     *
     * The headers were hardcoded to the Global API Key pair, with no Bearer branch -- the same bug
     * that was already fixed in the status probe and missed here. For an API-token account that
     * sends the token as `X-Auth-Key`, which Cloudflare does not reject cleanly: it authenticates
     * nothing and refuses the resource with error 10037, so "create subdomain" failed for every
     * token account no matter what its permissions were.
     */
    suspend fun createSubdomain(account: CloudAccount): SubdomainResult = withContext(Dispatchers.IO) {
        try {
            val authHeaders = CloudAuth.headers(account)

            // First check if a subdomain already exists
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()

            var existingSub = ""
            client.newCall(subReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = org.json.JSONObject(response.body?.string() ?: "")
                    if (json.optBoolean("success")) {
                        existingSub = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                }
            }

            if (existingSub.isNotEmpty()) {
                return@withContext SubdomainResult(true, existingSub, http = 200)
            }

            val randomSub = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
            val createReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .put("{\"subdomain\":\"$randomSub\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(createReq).execute().use { response ->
                val body = response.body?.string() ?: ""
                val errors = CloudVerifyProbe.errorsOf(body)
                if (response.isSuccessful) {
                    return@withContext SubdomainResult(true, randomSub, http = response.code)
                }
                if (body.contains("10007")) {
                    return@withContext SubdomainResult(true, "already_exists", http = response.code)
                }
                // Narrow on purpose. The old rule was "the error text contains the word verify",
                // matched anywhere -- and Cloudflare uses that word in permission and rate-limit
                // messages that have nothing to do with email, so unrelated failures were locking
                // the panel behind an email overlay. It has to say verify AND name the email.
                val emailProblem = Regex("verif", RegexOption.IGNORE_CASE).containsMatchIn(errors) &&
                    Regex("e-?mail", RegexOption.IGNORE_CASE).containsMatchIn(errors)
                return@withContext SubdomainResult(
                    ok = false,
                    message = "Failed to create subdomain: $body",
                    failure = if (emailProblem) SubdomainFailure.EMAIL_UNVERIFIED else SubdomainFailure.OTHER,
                    http = response.code,
                    errors = errors,
                )
            }
        } catch (e: Exception) {
            SubdomainResult(
                ok = false,
                message = "Error: ${e.message}",
                failure = SubdomainFailure.OTHER,
                http = -1,
                errors = "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    /**
     * Deletes one panel's worker from Cloudflare and forgets it locally.
     *
     * A script Cloudflare no longer has is already in the state the caller asked for, so a 404 is
     * reported as success -- otherwise a half-deleted account can never be cleaned up from here.
     */
    suspend fun removePanel(account: CloudAccount, engine: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val name = PanelBuild.scriptName(account, engine)
                ?: return@withContext Pair(false, S(R.string.this_panel_is_not_deployed_on_this))

            val (ok, message) = deleteWorker(account, name)
            if (!ok && !message.contains("10007") && !message.contains("not found", ignoreCase = true)) {
                return@withContext Pair(false, message)
            }

            when (engine.uppercase()) {
                "BPB" -> {
                    account.status = "active"
                    account.workerUrl = null
                    account.uuid = null
                    account.trPass = null
                    account.subPath = null
                    account.bpbVersion = 0
                }
                "EDG" -> {
                    account.edgStatus = "idle"
                    account.edgWorkerUrl = null
                    account.edgUuid = null
                    account.edgAdminPass = null
                    account.edgVersion = 0
                }
                "NHN", "NAHAN" -> {
                    account.nahanStatus = "idle"
                    account.nahanWorkerUrl = null
                    account.nahanMasterKey = null
                    account.nahanVersion = 0
                }
                "MLM" -> {
                    account.mlmStatus = "idle"
                    account.mlmWorkerUrl = null
                    account.mlmAdminPassword = null
                    account.mlmVersion = 0
                }
                "DNS" -> {
                    account.dnsStatus = "idle"
                    account.dnsWorkerUrl = null
                    account.dnsVersion = 0
                }
                "RELAY" -> {
                    account.relayStatus = "idle"
                    account.relayWorkerUrl = null
                    account.relayVersion = 0
                }
                "POOL" -> {
                    account.poolStatus = "idle"
                    account.poolWorkerUrl = null
                    account.poolVersion = 0
                    // The D1 and KV ids are deliberately kept: deleting the script must not throw
                    // away everybody's accumulated scores, and a redeploy rebinds the same stores.
                }
            }
            saveAccounts()
            Pair(true, S(R.string.deleted))
        }

    /**
     * Puts the VPN Gate relay on this account.
     *
     * The simplest deploy in here: one module script, no KV, no D1, no bindings. It exists
     * because VPN Gate's domain is blocked at the ISP and every shared relay the app tried either
     * ran out of quota (HTTP 402) or could not reach the origin itself (520/522). A Worker on the
     * user's own account is the one relay that is nobody else's to exhaust.
     */
    suspend fun deployVpnGateRelay(
        account: CloudAccount,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val TAG = "RelayDeploy"
        try {
            val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            onProgress(10, S(R.string.checking_the_subdomain))
            var subdomain = ""
            val subReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                .headers(authHeaders)
                .get().build()
            client.newCall(subReq).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d(TAG, "GET subdomain -> HTTP ${response.code}: ${body.take(200)}")
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.optBoolean("success")) {
                        subdomain = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                }
            }
            if (subdomain.isBlank()) {
                return@withContext Pair(false, "ERR_ACCOUNT_HAS_SUBDOMAIN")
            }

            onProgress(40, S(R.string.preparing_the_relay))
            val workerScript = com.mlmvpn.scanner.store.StoreFiles.open(context, "vpngate_relay_worker.js")
                .bufferedReader().use { it.readText() }

            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2024-01-01")
                put("bindings", org.json.JSONArray())
            }
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "metadata", "metadata.json",
                    metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()),
                )
                .addFormDataPart(
                    "worker.js", "worker.js",
                    workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()),
                )
                .build()

            // Same name on a redeploy, so an update replaces the script instead of adding a
            // second one beside it.
            val workerName = PanelBuild.scriptName(account, "RELAY")
                ?: (com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-rly")

            onProgress(65, S(R.string.uploading_the_relay))
            val uploadReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(multipartBody)
                .build()
            client.newCall(uploadReq).execute().use { response ->
                val body = response.body?.string()
                Log.d(TAG, "PUT scripts/$workerName -> HTTP ${response.code}: ${body?.take(300)}")
                if (!response.isSuccessful) {
                    return@withContext Pair(false, S(R.string.uploading_the_relay_failed_http, response.code))
                }
            }

            onProgress(85, S(R.string.enabling_the_address))
            val enableReq = Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName/subdomain")
                .headers(authHeaders)
                .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(enableReq).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    Log.e(TAG, "enable subdomain -> HTTP ${response.code}: ${body?.take(300)}")
                    return@withContext Pair(false, S(R.string.enabling_the_address_failed_http, response.code))
                }
            }

            onProgress(100, S(R.string.done_2))
            val finalUrl = "https://$workerName.$subdomain.workers.dev"
            account.relayStatus = "deployed"
            account.relayVersion = PanelBuild.RELAY
            account.relayWorkerUrl = finalUrl
            saveAccounts()
            Log.d(TAG, "relay deployed: $finalUrl")
            Pair(true, S(R.string.the_relay_is_live_on_your_account))
        } catch (e: Exception) {
            Log.e(TAG, "relay deploy failed", e)
            Pair(false, S(R.string.error, e.message))
        }
    }

    /**
     * Puts the Gemini exit on this account: a Worker whose Durable Object runs in North America.
     *
     * Gemini refuses by where Google thinks a request comes from, and a Worker leaves from the
     * data centre nearest the phone -- from Iran a European one whose exit Google places in
     * Russia. A Durable Object created with `locationHint: "enam"` lives in Eastern North America
     * and dials Google from there. See assets/gemini_exit_worker.js.
     *
     * The user id and path are kept across redeploys, so an update never strands a config
     * generated before it. The Object's class has to be created by a tagged migration exactly
     * once; which migration to send is remembered per account, and a stale memory (a reinstall,
     * another device) is recovered by trying the other form.
     */
    suspend fun deployGeminiExit(
        account: CloudAccount,
        /** A proxy to reach Cloudflare's API through, where the network blocks it (MAE passes one). */
        via: java.net.Proxy? = null,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val TAG = "GeminiExitDeploy"
        val client = if (via == null) client else client.newBuilder().proxy(via).build()
        try {
            val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()

            onProgress(10, S(R.string.checking_the_subdomain))
            var subdomain = ""
            client.newCall(
                Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/subdomain")
                    .headers(authHeaders).get().build()
            ).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.optBoolean("success")) {
                        subdomain = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                }
            }
            if (subdomain.isBlank()) return@withContext Pair(false, "ERR_ACCOUNT_HAS_SUBDOMAIN")

            onProgress(35, S(R.string.gemini_exit_uploading))
            val script = com.mlmvpn.scanner.store.StoreFiles.open(context, "gemini_exit_worker.js").bufferedReader().use { it.readText() }
            val exitId = account.geminiExitId ?: java.util.UUID.randomUUID().toString()
            val exitPath = account.geminiExitPath ?: randomExitPath()
            // Same name on a redeploy, so an update replaces the script instead of adding another.
            val workerName = account.geminiExitUrl
                ?.let { runCatching { android.net.Uri.parse(it).host?.substringBefore('.') }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?: com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName()

            fun metadata(migration: JSONObject?) = JSONObject().apply {
                put("main_module", "worker.js")
                // Fixed past date, as every deployer here uses: a date "in the future" by the
                // device's clock is rejected for everyone ahead of UTC around midnight.
                put("compatibility_date", "2024-03-03")
                put("bindings", org.json.JSONArray().apply {
                    put(JSONObject().put("type", "durable_object_namespace").put("name", "EXIT")
                        .put("class_name", GEMINI_EXIT_CLASS))
                    put(JSONObject().put("type", "secret_text").put("name", "EXIT_ID").put("text", exitId))
                    put(JSONObject().put("type", "plain_text").put("name", "EXIT_PATH").put("text", exitPath))
                    put(JSONObject().put("type", "plain_text").put("name", "EXIT_HINT").put("text", GEMINI_EXIT_HINT))
                })
                migration?.let { put("migrations", it) }
            }
            val create = JSONObject().put("new_tag", GEMINI_EXIT_DO_TAG)
                .put("new_sqlite_classes", org.json.JSONArray().put(GEMINI_EXIT_CLASS))
            val keep = JSONObject().put("old_tag", GEMINI_EXIT_DO_TAG).put("new_tag", GEMINI_EXIT_DO_TAG)
            // The remembered form first, then the other: a class already created rejects "create",
            // and one never created rejects "keep".
            val attempts = if (account.geminiExitDoTag.isNullOrBlank()) listOf(create, keep) else listOf(keep, create)

            fun upload(meta: JSONObject): Pair<Boolean, String> {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "metadata", "metadata.json",
                        meta.toString().toRequestBody("application/json".toMediaTypeOrNull()),
                    )
                    .addFormDataPart(
                        "worker.js", "worker.js",
                        script.toRequestBody("application/javascript+module".toMediaTypeOrNull()),
                    )
                    .build()
                return client.newCall(
                    Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                        .headers(authHeaders).put(body).build()
                ).execute().use { res -> res.isSuccessful to (res.body?.string() ?: "").take(400) }
            }

            var uploaded = false
            var lastError = ""
            for (migration in attempts) {
                val (ok, detail) = upload(metadata(migration))
                Log.d(TAG, "PUT scripts/$workerName (${if (migration === create) "create" else "keep"}) -> ok=$ok $detail")
                if (ok) {
                    uploaded = true
                    break
                }
                lastError = detail
            }
            if (!uploaded) {
                return@withContext Pair(false, S(R.string.gemini_exit_failed, cloudflareMessage(lastError)))
            }
            account.geminiExitDoTag = GEMINI_EXIT_DO_TAG

            onProgress(80, S(R.string.enabling_the_address))
            client.newCall(
                Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName/subdomain")
                    .headers(authHeaders)
                    .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "enable subdomain -> HTTP ${response.code}: ${response.body?.string()?.take(300)}")
                    return@withContext Pair(false, S(R.string.enabling_the_address_failed_http, response.code))
                }
            }

            val host = "$workerName.$subdomain.workers.dev"
            account.geminiExitUrl = "https://$host"
            account.geminiExitId = exitId
            account.geminiExitPath = exitPath
            account.geminiExitStatus = "deployed"
            account.geminiExitVersion = GEMINI_EXIT_VERSION
            saveAccounts()
            com.mlmvpn.scanner.utils.NetworkSettings.setGeminiExit(
                context,
                com.mlmvpn.scanner.utils.XrayJsonGenerator.GeminiExitRoute(host, exitPath, exitId),
            )
            onProgress(100, S(R.string.done_2))
            Log.i(TAG, "Gemini exit live: https://$host/$exitPath")
            Pair(true, S(R.string.gemini_exit_done))
        } catch (e: Exception) {
            Log.e(TAG, "Gemini exit deploy failed", e)
            Pair(false, S(R.string.gemini_exit_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * The Gemini exit build deployed on [a], 0 when none. An exit deployed before builds were kept
     * on the account is build 2 when MAE already brought it up to date (its own marker), else 1.
     */
    fun geminiExitBuild(a: CloudAccount): Int = when {
        a.geminiExitStatus != "deployed" -> 0
        a.geminiExitVersion > 0 -> a.geminiExitVersion
        context.getSharedPreferences("mae", Context.MODE_PRIVATE).getInt("usx_script_ver", 0) >= 2 -> 2
        else -> 1
    }

    /** A path nobody guesses: the exit answers nothing anywhere else. */
    private fun randomExitPath(): String {
        val chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = java.security.SecureRandom()
        return (1..24).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    /** Cloudflare's own error message out of an API response body, or the body itself. */
    private fun cloudflareMessage(body: String): String = runCatching {
        JSONObject(body).optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: body.take(160)

    /**
     * The accounts, loaded now if the constructor's background load has not finished yet -- for
     * a caller that must not mistake "not loaded" for "no account" (MAE's account gate).
     */
    fun loadedAccounts(): List<CloudAccount> {
        if (accountsFlow.value.isEmpty()) {
            runCatching {
                loadAccounts()
                _accountsFlow.value = accounts.toList()
            }
        }
        return accountsFlow.value
    }

    /** The relay URLs across every connected account, for the repository to try in turn. */
    fun relayUrls(): List<String> {
        // Accounts load on an IO coroutine at construction. The server-list refresh asks for the
        // relays immediately after building this, so the flow was still empty and the user's own
        // Worker was skipped entirely -- the fetch fell straight through to the shared relays,
        // which are exactly the ones that no longer work. Load them here if that race lost.
        if (accountsFlow.value.isEmpty()) {
            runCatching {
                loadAccounts()
                _accountsFlow.value = accounts.toList()
            }
        }
        return accountsFlow.value.mapNotNull {
            it.relayWorkerUrl?.takeIf { u -> u.isNotBlank() }
        }
    }

    /**
     * Deploys the MLMVPN shared config pool.
     *
     * Unlike the panels, this one is deployed ONCE on one account and read by every install -- it
     * is infrastructure, not a per-user resource. It needs both a D1 database (the ledger of which
     * config worked for whom, on which network) and a KV namespace (the precomputed served list).
     *
     * The schema is not created here. The worker runs `CREATE TABLE IF NOT EXISTS` on its first
     * request, the same way mlm_worker.js and nahan_worker.js do, so a redeploy never has to
     * reason about migrations from the phone.
     */
    suspend fun deployMlmPoolWorker(
        account: CloudAccount,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val TAG = "PoolDeploy"
        try {
            val isCfat = com.mlmvpn.scanner.data.CloudAuth.useBearer(account)
            val authHeaders = Headers.Builder().apply {
                if (isCfat) add("Authorization", "Bearer ${account.token}")
                else {
                    add("X-Auth-Email", account.email)
                    add("X-Auth-Key", account.token)
                }
            }.build()
            val base = "https://api.cloudflare.com/client/v4/accounts/${account.accountId}"

            onProgress(10, S(R.string.checking_the_subdomain))
            var subdomain = ""
            client.newCall(
                Request.Builder().url("$base/workers/subdomain").headers(authHeaders).get().build()
            ).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.optBoolean("success")) {
                        subdomain = json.optJSONObject("result")?.optString("subdomain", "") ?: ""
                    }
                }
            }
            if (subdomain.isBlank()) return@withContext Pair(false, "ERR_ACCOUNT_HAS_SUBDOMAIN")

            // ---- D1 -----------------------------------------------------------------------
            onProgress(25, S(R.string.preparing_the_database))
            var dbId = account.poolDbId?.takeIf { it.isNotBlank() } ?: ""
            if (dbId.isEmpty()) {
                // An earlier attempt may have created one and failed before saving the id, so
                // look before making a second.
                client.newCall(
                    Request.Builder().url("$base/d1/database?per_page=100").headers(authHeaders).get().build()
                ).execute().use { response ->
                    runCatching {
                        val json = JSONObject(response.body?.string() ?: "")
                        if (json.optBoolean("success")) {
                            val results = json.getJSONArray("result")
                            for (i in 0 until results.length()) {
                                val entry = results.getJSONObject(i)
                                if (entry.optString("name").startsWith("mlmvpn_pool_")) {
                                    dbId = entry.getString("uuid"); break
                                }
                            }
                        }
                    }
                }
            }
            if (dbId.isEmpty()) {
                val name = "mlmvpn_pool_" + java.util.UUID.randomUUID().toString().take(6)
                client.newCall(
                    Request.Builder().url("$base/d1/database").headers(authHeaders)
                        .post("{\"name\":\"$name\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                ).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (response.isSuccessful && json?.optBoolean("success") == true) {
                        dbId = json.getJSONObject("result").getString("uuid")
                    } else {
                        val msg = json?.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                        return@withContext Pair(false, S(R.string.creating_the_database_failed, msg ?: body.take(160)))
                    }
                }
            }

            // ---- KV -----------------------------------------------------------------------
            onProgress(45, S(R.string.preparing_the_list_storage))
            var kvId = account.poolKvId?.takeIf { it.isNotBlank() } ?: ""
            if (kvId.isEmpty()) {
                client.newCall(
                    Request.Builder().url("$base/storage/kv/namespaces").headers(authHeaders)
                        .post("{\"title\":\"mlmvpn_pool_cache\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                ).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (response.isSuccessful && json?.optBoolean("success") == true) {
                        kvId = json.getJSONObject("result").getString("id")
                    } else {
                        // A namespace with that title already exists on a retry; find it.
                        client.newCall(
                            Request.Builder().url("$base/storage/kv/namespaces?per_page=100")
                                .headers(authHeaders).get().build()
                        ).execute().use { listRes ->
                            runCatching {
                                val listJson = JSONObject(listRes.body?.string() ?: "")
                                val results = listJson.getJSONArray("result")
                                for (i in 0 until results.length()) {
                                    val entry = results.getJSONObject(i)
                                    if (entry.optString("title") == "mlmvpn_pool_cache") {
                                        kvId = entry.getString("id"); break
                                    }
                                }
                            }
                        }
                        if (kvId.isEmpty()) {
                            return@withContext Pair(false, S(R.string.creating_the_list_storage_failed, body.take(160)))
                        }
                    }
                }
            }

            // ---- upload -------------------------------------------------------------------
            onProgress(65, S(R.string.uploading_the_worker))
            val script = context.assets.open("mlmvpn_pool_worker.js").bufferedReader().use { it.readText() }
            val metadata = JSONObject().apply {
                put("main_module", "worker.js")
                put("compatibility_date", "2024-03-03")
                put("bindings", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "d1"); put("name", "DB"); put("id", dbId)
                    })
                    put(JSONObject().apply {
                        put("type", "kv_namespace"); put("name", "POOL"); put("namespace_id", kvId)
                    })
                })
                // Secrets set in the Cloudflare dashboard survive a redeploy from here.
                //
                // A script upload REPLACES the binding list, so without this any secret the
                // maintainer added by hand -- GH_TOKEN and GH_REPO, which is how a crash becomes
                // a GitHub issue -- would be silently deleted by the next update from this
                // screen, and crash reporting would quietly fall back to the database with
                // nothing on screen to say why. The app never sends the token itself: an APK is
                // readable by anyone who downloads it.
                put("keep_bindings", org.json.JSONArray().apply { put("secret_text") })
            }
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json",
                    metadata.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js",
                    script.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            // Same name on a redeploy, so an update replaces the script rather than leaving a
            // second one running beside it with the old logic.
            val workerName = PanelBuild.scriptName(account, "POOL")
                ?: (com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() + "-pool")

            client.newCall(
                Request.Builder().url("$base/workers/scripts/$workerName")
                    .headers(authHeaders).put(multipart).build()
            ).execute().use { response ->
                val body = response.body?.string()
                Log.d(TAG, "PUT scripts/$workerName -> HTTP ${response.code}: ${body?.take(300)}")
                if (!response.isSuccessful) {
                    return@withContext Pair(false, S(R.string.uploading_the_worker_failed_http, response.code))
                }
            }

            onProgress(85, S(R.string.enabling_the_address))
            client.newCall(
                Request.Builder().url("$base/workers/scripts/$workerName/subdomain")
                    .headers(authHeaders)
                    .post("{\"enabled\":true}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Pair(false, S(R.string.enabling_the_address_failed_http, response.code))
                }
            }

            onProgress(100, S(R.string.done_2))
            val finalUrl = "https://$workerName.$subdomain.workers.dev"
            account.poolStatus = "deployed"
            account.poolVersion = PanelBuild.POOL
            account.poolWorkerUrl = finalUrl
            account.poolDbId = dbId
            account.poolKvId = kvId
            saveAccounts()
            Log.d(TAG, "pool deployed: $finalUrl")
            Pair(true, finalUrl)
        } catch (e: Exception) {
            Log.e(TAG, "pool deploy failed", e)
            Pair(false, S(R.string.error, e.message))
        }
    }
}
