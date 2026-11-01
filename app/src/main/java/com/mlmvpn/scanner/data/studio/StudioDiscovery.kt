package com.mlmvpn.scanner.data.studio

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Finds a Config Studio installation that already exists on a Cloudflare account.
 *
 * This is what makes "install it on my other phone" mean *adopt what is already there* rather than
 * *deploy a second copy*. Nothing about an installation is recovered from the device: the account
 * credential is enough, because the script name is derived from the account ([StudioNaming]) and
 * everything else is read back off Cloudflare's own API.
 *
 * **Identification is positive, never by name.** The same Cloudflare account routinely carries BPB,
 * EDG, Nahan, the DNS resolver, the VPN Gate relay and the shared pool worker. A name that merely
 * looks right is a candidate; adopting one of those by mistake and then deploying Studio over it
 * would destroy another engine's deployment, so a candidate is only adopted once its bindings or a
 * live probe say what it actually is.
 *
 * See `android/docs/CONFIG-STUDIO-PLAN.md` §B.2.2 and R15.
 */
class StudioDiscovery(private val context: Context) {

    /** The binding a build-6+ installation carries its API path segment in. */
    private val ROUTE_BINDING = "STUDIO_ROUTE"

    enum class Kind {
        /** A Config Studio engine, build 6 or later: it answers `/{apiRoute}/v1/health`. */
        STUDIO,

        /**
         * The pre-Studio panel. Adoptable, but it speaks the old `/api/…` surface until upgraded.
         *
         * (That ellipsis is not a typo. Kotlin nests block comments, unlike Java, so writing the
         * route with a literal star opens a nested comment that the line's own close tag then
         * closes -- leaving the KDoc open to the end of the file.)
         */
        LEGACY,
    }

    data class Found(
        val scriptName: String,
        val workerUrl: String,
        val databaseId: String?,
        val apiRoute: String?,
        val kind: Kind,
        /** Engine build, from `/v1/health`. 0 when it could not be established. */
        val build: Int = 0,
        /**
         * Other scripts on this account that also looked like installations.
         *
         * Non-empty means the duplicate-worker bug already happened here: one of these is serving
         * subscription links that the app is not managing. Surfaced rather than hidden, because the
         * fix -- which one to keep -- is not a decision this class can make.
         */
        val otherCandidates: List<String> = emptyList(),
        /**
         * The Durable Object migration Cloudflare has applied to this script (see
         * [CloudManager.WorkerInfo.migrationTag]): blank for none, null when the listing did not say.
         */
        val migrationTag: String? = null,
        /** Whether the script is bound to the Durable Object that makes device limits real. */
        val hasDurableObject: Boolean = false,
    )

    sealed class Result {
        /** An installation is already on this account. Adopt it; do not deploy. */
        data class Adopted(val found: Found) : Result()

        /** The account is reachable and genuinely has no installation. Deploying is correct. */
        object None : Result()

        /**
         * The account could not be examined. **Deploying after this is not safe** -- it is exactly
         * the state in which a second worker gets created on an account that already has one.
         */
        data class Failed(val message: String) : Result()
    }

    private val client = OkHttpClient.Builder()
        .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns())
        .protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun scan(account: CloudAccount): Result = withContext(Dispatchers.IO) {
        val cloud = CloudManager(context)

        val (ok, snapshot) = cloud.getWorkersDetailed(account)
        if (!ok) return@withContext Result.Failed("could not list the workers on this account")

        val subdomain = snapshot.subdomain
        if (subdomain.isEmpty()) {
            // No workers.dev subdomain means nothing has ever been deployed here, so there is
            // nothing to adopt -- but it also means we cannot build a URL to probe with, so say
            // "none" rather than pretending we checked.
            return@withContext if (snapshot.workers.isEmpty()) Result.None
            else Result.Failed("this account has no workers.dev subdomain yet")
        }

        val expected = StudioNaming.scriptName(account.accountId)
        val names = snapshot.workers.map { it.name }

        // The deterministic name first, then anything carrying the legacy suffix. Order matters:
        // an account that has both should adopt the current one and merely report the old.
        val candidates = buildList {
            if (names.contains(expected)) add(expected)
            addAll(names.filter { it != expected && StudioNaming.isLegacyScriptName(it) })
        }
        if (candidates.isEmpty()) return@withContext Result.None

        val confirmed = mutableListOf<Found>()
        // Candidates whose bindings could not be read AND which no probe identified. We do not know
        // what they are, and "do not know" must not collapse into "nothing is here" — see below.
        val unexamined = mutableListOf<String>()

        for (name in candidates) {
            val url = "https://$name.$subdomain.workers.dev"
            val bindings = readBindings(account, name)
            val dbId = bindings?.d1Id
            val route = bindings?.route
            val tag = snapshot.workers.firstOrNull { it.name == name }?.migrationTag
            val hasDo = bindings?.hasDurableObject == true

            // A D1 binding is the minimum: every installation has one, and a worker without one is
            // some other engine no matter what it is called.
            if (bindings != null && dbId == null) continue

            val health = route?.let { probeHealth(url, it) }
            if (health != null) {
                confirmed.add(Found(name, url, dbId, route, Kind.STUDIO, health, migrationTag = tag, hasDurableObject = hasDo))
                continue
            }
            if (probeLegacy(url)) {
                confirmed.add(Found(name, url, dbId, null, Kind.LEGACY, migrationTag = tag, hasDurableObject = hasDo))
                continue
            }
            // Neither signal fired, and we could not even read the bindings — so this is a script
            // whose identity is unknown rather than one we checked and ruled out.
            if (bindings == null) unexamined.add(name)
        }

        val best = confirmed.firstOrNull()
            // Reporting "nothing here" is the one answer that does damage: the wizard would offer
            // to install, and installing beside a worker we merely failed to inspect is precisely
            // the duplicate-engine bug this class exists to prevent. Not being able to see the
            // account is a state to say out loud, and step 3 already knows what to do with it —
            // offer a retry and no install button at all (plan §B.3, R15).
            ?: return@withContext if (unexamined.isEmpty()) Result.None
            else Result.Failed("could not examine ${unexamined.joinToString(", ")}")

        Result.Adopted(best.copy(otherCandidates = confirmed.drop(1).map { it.scriptName }))
    }

    /**
     * Delete one worker script from the account.
     *
     * Offered only for the extra workers older versions left behind (see [Found.otherCandidates]),
     * and never done in bulk. They share one database, so removing one destroys no data — but every
     * subscription link handed out from the device that created it points at **that** address, and
     * those stop working the moment it goes. There is no undo and no redirect, so this is always a
     * per-worker decision the operator makes with the address in front of them.
     *
     * The D1 database is deliberately not touched: it is the one every remaining worker reads.
     */
    suspend fun removeWorker(account: CloudAccount, scriptName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$scriptName")
                    .headers(CloudManager(context).authHeadersFor(account))
                    .delete()
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        Log.w("StudioDiscovery", "delete $scriptName failed ${res.code}: ${res.body.string().take(200)}")
                    }
                    res.isSuccessful
                }
            } catch (e: Exception) {
                Log.w("StudioDiscovery", "delete $scriptName error: ${e.message}")
                false
            }
        }

    private data class Bindings(val d1Id: String?, val route: String?, val hasDurableObject: Boolean = false)

    /**
     * Reads a script's bindings back off Cloudflare.
     *
     * `plain_text` bindings come back with their value, which is how the per-install API path is
     * recovered on a device that has never seen this installation. `secret_text` bindings come back
     * named but empty, which is why the bootstrap secret cannot be recovered this way and is
     * re-armed by a redeploy instead (plan §B.2.3).
     */
    private fun readBindings(account: CloudAccount, scriptName: String): Bindings? = try {
        val req = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$scriptName/settings")
            .headers(CloudManager(context).authHeadersFor(account))
            .get().build()
        client.newCall(req).execute().use { res ->
            val body = res.body.string()
            if (!res.isSuccessful) null else {
                val arr = JSONObject(body).optJSONObject("result")?.optJSONArray("bindings")
                var d1: String? = null
                var route: String? = null
                var hasDo = false
                if (arr != null) for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    when (b.optString("type")) {
                        "d1" -> d1 = b.optString("id").takeIf { it.isNotEmpty() }
                        "plain_text" -> if (b.optString("name") == ROUTE_BINDING) {
                            route = b.optString("text").takeIf { it.isNotEmpty() }
                        }
                        "durable_object_namespace" -> if (b.optString("name") == "SESSIONS") hasDo = true
                    }
                }
                Bindings(d1, route, hasDo)
            }
        }
    } catch (e: Exception) {
        Log.w("StudioDiscovery", "settings read failed for $scriptName: ${e.message}")
        null
    }

    /** @return the engine build, or null if this is not a Studio engine answering here. */
    private fun probeHealth(workerUrl: String, route: String): Int? = try {
        val req = Request.Builder().url("$workerUrl/$route/v1/health").get().build()
        client.newCall(req).execute().use { res ->
            val body = res.body.string()
            if (!res.isSuccessful) null
            else JSONObject(body).takeIf { it.has("schema_version") }?.optInt("version", 0)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Whether the pre-Studio panel is answering on this URL.
     *
     * The legacy worker serves a fake nginx page to everything it does not recognise, so a 200 with
     * that page is its signature -- and it is a *positive* signal rather than a name match, which is
     * what R15 asks for. Anything else on the account (BPB, EDG, the DNS resolver) answers
     * differently.
     */
    private fun probeLegacy(workerUrl: String): Boolean = try {
        val req = Request.Builder().url(workerUrl).get().build()
        client.newCall(req).execute().use { res ->
            val body = res.body.string()
            res.isSuccessful && body.contains("nginx", ignoreCase = true)
        }
    } catch (e: Exception) {
        false
    }
}
