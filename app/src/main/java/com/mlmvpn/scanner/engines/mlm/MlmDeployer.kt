package com.mlmvpn.scanner.engines.mlm

import android.content.Context
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.utils.AntiDpi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MlmDeployer(private val context: Context) {

    private val client = OkHttpClient.Builder().dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns()).protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun deployMlm(account: CloudAccount, onProgress: (Int, String) -> Unit): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // One engine, one installer. On an account Config Studio manages, the upload below would
        // replace the Worker's settings with this short list -- dropping Config Studio's route (and
        // with it every subscription link it handed out), the Durable Object that enforces device
        // limits, and the key the app talks to it with. So the Cloud tab's install and update go
        // through Config Studio's own installer there, which keeps all of them.
        if (account.isStudioManaged) {
            return@withContext when (val r = com.mlmvpn.scanner.data.studio.StudioDeployer(context).install(account, onProgress = onProgress)) {
                is com.mlmvpn.scanner.data.studio.StudioDeployer.Result.Ready -> Pair(true, r.workerUrl)
                is com.mlmvpn.scanner.data.studio.StudioDeployer.Result.WouldDowngrade ->
                    Pair(false, context.getString(com.mlmvpn.scanner.R.string.studio_downgrade_body))
                is com.mlmvpn.scanner.data.studio.StudioDeployer.Result.Failed -> Pair(false, r.message)
            }
        }
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
                    val randomSub = AntiDpi.generateSafeSubdomain()
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
                                val json = JSONObject(body)
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
                    return@withContext Pair(false, "Failed to create a workers subdomain.")
                }
            }

            // 1b. The account's own legacy MLM panel first (PanelRegistry) -- its Worker, D1 and
            // password -- never a second one beside it.
            com.mlmvpn.scanner.engines.cloud.PanelRegistry.live(account, "MLM")?.let { g ->
                account.mlmWorkerUrl = g.url
                g.d1?.let { account.mlmDbId = it }
                g.s.optString("password").takeIf { it.isNotBlank() }?.let { account.mlmAdminPassword = it }
            }

            // 2. Reuse the existing D1 Database if this account was already deployed before
            // (a redeploy/update used to always create a brand-new D1 database and never
            // cleaned up the old one on failure, so repeated retries silently ate into the
            // account's D1 quota until every future deploy failed with no D1 left to create --
            // matching reports of "have to delete the previous version to make it work again").
            onProgress(40, "Setting up D1 Database...")
            var databaseId = account.mlmDbId?.takeIf { it.isNotEmpty() } ?: ""

            var createErrorInfo = ""
            var existingDbNames: List<String> = emptyList()

            if (databaseId.isEmpty()) {
                // Look for a previously-created mlm_db_* database on this account before
                // provisioning a new one, in case an earlier failed/interrupted deploy already
                // created one that never got saved to account.mlmDbId. Also remember every
                // existing database's name so that if we're out of quota, we can tell the user
                // exactly what's using it instead of a generic "delete something in the dashboard".
                val listReq = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/d1/database?per_page=100")
                    .headers(authHeaders)
                    .get().build()
                client.newCall(listReq).execute().use { listRes ->
                    val listBody = listRes.body?.string() ?: ""
                    try {
                        val listJson = JSONObject(listBody)
                        if (listJson.optBoolean("success")) {
                            val results = listJson.getJSONArray("result")
                            val names = mutableListOf<String>()
                            for (i in 0 until results.length()) {
                                val entry = results.getJSONObject(i)
                                names.add(entry.optString("name"))
                                if (databaseId.isEmpty() && entry.optString("name").startsWith("mlm_db_")) {
                                    databaseId = entry.getString("uuid")
                                }
                            }
                            existingDbNames = names
                        }
                    } catch (e: Exception) { }
                }
            }

            if (databaseId.isEmpty()) {
                val dbName = "mlm_db_" + UUID.randomUUID().toString().substring(0, 6)
                val d1Req = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/d1/database")
                    .headers(authHeaders)
                    .post("{\"name\":\"$dbName\"}".toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                client.newCall(d1Req).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        if (response.isSuccessful && json.optBoolean("success")) {
                            databaseId = json.getJSONObject("result").getString("uuid")
                        } else {
                            val errors = json.optJSONArray("errors")
                            createErrorInfo = if (errors != null && errors.length() > 0) {
                                errors.getJSONObject(0).optString("message", body.take(200))
                            } else body.take(200)
                        }
                    } catch (e: Exception) {
                        createErrorInfo = body.take(200)
                    }
                }
            }

            if (databaseId.isEmpty()) {
                val quotaHit = createErrorInfo.contains("System limit reached", ignoreCase = true) ||
                    createErrorInfo.contains("databases per account", ignoreCase = true)
                val msg = if (quotaHit) {
                    "Your Cloudflare account has reached its D1 database limit (max 10 on the Free plan), and none of the existing ones belong to MLM VPN, so a new one can't be created.\n\n" +
                        "Existing databases on this account:\n" + existingDbNames.joinToString("\n") { "- $it" } + "\n\n" +
                        "Delete one you don't need at dash.cloudflare.com -> Workers & Pages -> D1, then try deploying again."
                } else {
                    "Failed to create D1 Database: $createErrorInfo"
                }
                return@withContext Pair(false, msg)
            }

            // 3. Upload Worker
            onProgress(60, "Uploading MLM Worker...")
            var workerScript = ""
            try {
                com.mlmvpn.scanner.store.StoreFiles.open(context, "mlm_worker.js").bufferedReader().use {
                    workerScript = it.readText()
                }
            } catch (e: Exception) {
                return@withContext Pair(false, "Failed to read mlm_worker.js from assets: ${e.message}")
            }

            // Build 23's XHTTP needs the SESSIONS Durable Object: on workers.dev a packet-up
            // session's POSTs and its GET land in different isolates, and only the session's own
            // object can bring them together. This deployer never sent that binding (only Config
            // Studio's did), so on a panel installed from here the worker fell back to the
            // in-isolate path and «دریافت کانفیگ xhttp» found nothing that carried data
            // (device, 2026-10-04: 10 ways tried, all DATA_TRANSFER_FAILURE). Same binding, same
            // migration tag and the same fallbacks as StudioDeployer: create the class, else
            // the binding alone (class already there), else replay the tag, else no object --
            // a panel that deploys without XHTTP still beats a panel that does not deploy.
            fun metadataFor(withDo: Boolean, migration: String) = JSONObject().apply {
                put("main_module", "worker.js")
                // Fixed past date, matching every other deployer in this app (Nahan/SubGen/EDG/DNS/GST).
                // This used to be computed from the device's local date/timezone -- Cloudflare rejects a
                // compatibility_date in the future (UTC), and for timezones ahead of UTC (e.g. Iran,
                // UTC+3:30) the local calendar date rolls over before UTC's does, so between ~00:00 and
                // 03:30 local time the computed date was tomorrow in UTC and every upload failed.
                put("compatibility_date", "2024-03-03")
                val bindings = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "d1")
                        put("name", "DB")
                        put("id", databaseId)
                    })
                    val adminPass = com.mlmvpn.scanner.utils.AdminPassword.ensure(account)
                    put(JSONObject().apply {
                        put("type", "plain_text")
                        put("name", "ADMIN_PASSWORD")
                        put("text", adminPass)
                    })
                    // DEBUG is not sent. "1" made the engine write a database row for every
                    // request, forever and never pruned -- the free plan's 100k daily writes spent
                    // on logs nobody reads, and nothing left for recording usage. Config Studio's
                    // installer stopped sending it for the same reason.
                    if (withDo) put(JSONObject().apply {
                        put("type", "durable_object_namespace")
                        put("name", "SESSIONS")
                        put("class_name", "SessionDO")
                    })
                }
                put("bindings", bindings)
                if (withDo && migration != "none") put("migrations", JSONObject().apply {
                    val tag = "v1"   // StudioDeployer.DO_TAG: the same class, so the same tag
                    if (migration == "replay") { put("old_tag", tag); put("new_tag", tag) }
                    else { put("new_tag", tag); put("new_sqlite_classes", org.json.JSONArray().put("SessionDO")) }
                })
            }

            fun bodyFor(meta: JSONObject) = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", meta.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addFormDataPart("worker.js", "worker.js", workerScript.toRequestBody("application/javascript+module".toMediaTypeOrNull()))
                .build()

            val workerName = com.mlmvpn.scanner.data.PanelBuild.scriptName(account, "MLM")
                ?: (AntiDpi.generateSafeWorkerName() + "-mlm")
            fun upload(meta: JSONObject): Pair<Boolean, String> = client.newCall(Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$workerName")
                .headers(authHeaders)
                .put(bodyFor(meta))
                .build()).execute().use { response -> response.isSuccessful to (response.body?.string().orEmpty().take(400)) }

            var uploaded = false
            var detail = ""
            for (migration in listOf("create", "none", "replay")) {
                val r = upload(metadataFor(true, migration))
                if (r.first) { uploaded = true; break }
                detail = r.second
                android.util.Log.w("MlmDeployer", "deploy with SESSIONS ($migration) failed: ${detail.take(200)}")
            }
            if (!uploaded) {
                val r = upload(metadataFor(false, "none"))
                uploaded = r.first
                detail = r.second
                if (uploaded) android.util.Log.w("MlmDeployer", "deployed without SESSIONS: XHTTP will not work on workers.dev")
            }
            if (!uploaded) return@withContext Pair(false, "Failed to upload MLM worker: $detail")

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

            account.mlmStatus = "deployed"
            account.mlmVersion = com.mlmvpn.scanner.data.PanelBuild.MLM
            account.mlmWorkerUrl = finalUrl
            account.mlmDbId = databaseId
            com.mlmvpn.scanner.engines.cloud.PanelRegistry.publish(account, "MLM", workerName, finalUrl, null, databaseId,
                JSONObject().put("password", account.mlmAdminPassword ?: ""))

            Pair(true, "Deployment Successful! URL: $finalUrl")

        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }
}
