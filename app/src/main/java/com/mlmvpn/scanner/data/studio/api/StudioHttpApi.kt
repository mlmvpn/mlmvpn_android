package com.mlmvpn.scanner.data.studio.api

import android.content.Context
import com.google.gson.Gson
import com.mlmvpn.scanner.data.studio.api.dto.AnalyticsSummaryDto
import com.mlmvpn.scanner.data.studio.api.dto.UsageBreakdownDto
import com.mlmvpn.scanner.data.studio.api.dto.UsageSliceDto
import com.mlmvpn.scanner.data.studio.api.dto.CountSeriesDto
import com.mlmvpn.scanner.data.studio.api.dto.SessionListDto
import com.mlmvpn.scanner.data.studio.api.dto.ConfigListDto
import com.mlmvpn.scanner.data.studio.api.dto.ConfigDto
import com.mlmvpn.scanner.data.studio.api.dto.DashboardDto
import com.mlmvpn.scanner.data.studio.api.dto.DeviceListDto
import com.mlmvpn.scanner.data.studio.api.dto.ErrorBodyDto
import com.mlmvpn.scanner.data.studio.api.dto.HealthDto
import com.mlmvpn.scanner.data.studio.api.dto.ActivityPageDto
import com.mlmvpn.scanner.data.studio.api.dto.NodeDto
import com.mlmvpn.scanner.data.studio.api.dto.NodeGroupDto
import com.mlmvpn.scanner.data.studio.api.dto.NodeGroupListDto
import com.mlmvpn.scanner.data.studio.api.dto.NodeHistoryDto
import com.mlmvpn.scanner.data.studio.api.dto.NodeListDto
import com.mlmvpn.scanner.data.studio.api.dto.PlanApplyDto
import com.mlmvpn.scanner.data.studio.api.dto.PlanDto
import com.mlmvpn.scanner.data.studio.api.dto.PlanListDto
import com.mlmvpn.scanner.data.studio.api.dto.PlanUsersDto
import com.mlmvpn.scanner.data.studio.api.dto.SettingsDto
import com.mlmvpn.scanner.data.studio.api.dto.AuditPageDto
import com.mlmvpn.scanner.data.studio.api.dto.SubscriptionDto
import com.mlmvpn.scanner.data.studio.api.dto.TemplateDto
import com.mlmvpn.scanner.data.studio.api.dto.TemplateListDto
import com.mlmvpn.scanner.data.studio.api.dto.TopUsersDto
import com.mlmvpn.scanner.data.studio.api.dto.TrafficSeriesDto
import com.mlmvpn.scanner.data.studio.api.dto.UserDto
import com.mlmvpn.scanner.data.studio.api.dto.UserPageDto
import com.mlmvpn.scanner.data.studio.api.dto.UserSyncDto
import com.mlmvpn.scanner.data.studio.domain.DashboardSnapshot
import com.mlmvpn.scanner.data.studio.domain.ExpiryMode
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.StudioConfig
import com.mlmvpn.scanner.data.studio.domain.StudioInstallation
import com.mlmvpn.scanner.data.studio.domain.NodeGroup
import com.mlmvpn.scanner.data.studio.domain.NodeHistory
import com.mlmvpn.scanner.data.studio.domain.StudioNode
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.Subscription
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * [StudioApi] over HTTP, against one installation.
 *
 * One instance per installation, because a fleet is many of these and each carries its own base URL,
 * route segment and key. The OkHttp client is shared across them: building one per installation
 * would mean a connection pool and a thread pool per Cloudflare account, which at the fleet sizes
 * this is designed for is a lot of idle machinery.
 *
 * Requests go straight to the operator's own Worker. The third-party proxy that could once rewrite
 * them (the "Vercel tunnel", bearer token included) was removed from the app entirely.
 */
class StudioHttpApi(
    context: Context,
    private val account: CloudAccount,
) : StudioApi {

    private val gson = Gson()
    private val base: String? = run {
        val url = account.mlmWorkerUrl?.trimEnd('/')
        val route = account.studioApiRoute
        if (url.isNullOrEmpty() || route.isNullOrEmpty()) null else "$url/$route/v1"
    }
    private val bearer: String? = run {
        val id = account.studioKeyId
        val secret = account.studioApiSecret
        if (id.isNullOrEmpty() || secret.isNullOrEmpty()) null else "cs_${id}_$secret"
    }

    private val client = shared(context)

    companion object {
        @Volatile private var CLIENT: OkHttpClient? = null

        private fun shared(context: Context): OkHttpClient =
            CLIENT ?: synchronized(this) {
                CLIENT ?: OkHttpClient.Builder()
                    .dns(com.mlmvpn.scanner.engines.cloud.WorkerRoute.dns())
                    .protocols(com.mlmvpn.scanner.engines.cloud.WorkerRoute.HTTP1).socketFactory(com.mlmvpn.scanner.engines.cloud.WorkerRoute.cfSocketFactory()).proxySelector(com.mlmvpn.scanner.engines.cloud.CfTunnel.selector())
                    .connectTimeout(15, TimeUnit.SECONDS)
                    // 40 s, not 20: making a location config now tests that country's servers on
                    // the engine before answering (build 18), and a slow batch of public servers
                    // takes longer than the old limit allowed.
                    .readTimeout(40, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .build().also { CLIENT = it }
            }
    }

    // ------------------------------------------------------------------ plumbing

    private suspend inline fun <reified T> request(
        method: String,
        path: String,
        body: Any? = null,
        requireAuth: Boolean = true,
    ): StudioResult<T> = withContext(Dispatchers.IO) {
        val root = base ?: return@withContext StudioResult.Err(
            StudioError(StudioError.NOT_CONFIGURED, detail = "no worker url or api route on this account")
        )
        if (requireAuth && bearer == null) {
            return@withContext StudioResult.Err(
                StudioError(StudioError.NOT_CONFIGURED, detail = "no api key for this account")
            )
        }

        val builder = Request.Builder().url(root + path)
        bearer?.let { builder.header("Authorization", "Bearer $it") }

        val payload = body?.let {
            (if (it is String) it else gson.toJson(it))
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        }
        val empty = { "{}".toRequestBody("application/json".toMediaTypeOrNull()) }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "PATCH" -> builder.patch(payload ?: empty())
            // PUT has to be named. Without this branch it fell to `else` and was sent as a POST,
            // which the plans route answers with 405 -- a method mismatch that looks like a broken
            // endpoint rather than a broken client.
            "PUT" -> builder.put(payload ?: empty())
            else -> builder.post(payload ?: empty())
        }

        // A diary line per call -- path, status, size, time, or the exact failure -- so a network
        // fault shows WHICH call it hits (the 2026-09-28 hunt: some calls answered in a second while
        // others hung to the read timeout, and nothing said which).
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(builder.build()).execute().use { res ->
                val text = res.body.string()
                android.util.Log.i("StudioNet", "$method ${path.substringBefore('?')} -> ${res.code} ${res.protocol} ${text.length} B ${System.currentTimeMillis() - t0} ms")
                if (!res.isSuccessful) {
                    // The engine's own code wins when it sent one. Falling back to the HTTP status
                    // alone would collapse `username_taken` and `bootstrap_used` into "409".
                    val code = runCatching { gson.fromJson(text, ErrorBodyDto::class.java) }
                        .getOrNull()?.error?.code
                    return@use StudioResult.Err(
                        StudioError(code ?: "http_${res.code}", res.code, text.take(400))
                    )
                }
                if (T::class == Unit::class) {
                    @Suppress("UNCHECKED_CAST")
                    return@use StudioResult.Ok(Unit as T)
                }
                val parsed = runCatching { gson.fromJson(text, T::class.java) }.getOrNull()
                    ?: return@use StudioResult.Err(
                        StudioError(StudioError.MALFORMED, res.code, text.take(400))
                    )
                StudioResult.Ok(parsed)
            }
        } catch (e: Exception) {
            android.util.Log.w("StudioNet", "$method ${path.substringBefore('?')} FAILED after ${System.currentTimeMillis() - t0} ms: ${e.javaClass.simpleName} ${e.message}")
            // Not reaching the engine is a different thing from the engine refusing, and a screen
            // has to say something different about each: one is retried, the other is acted on.
            StudioResult.Err(StudioError(StudioError.NETWORK, detail = e.message))
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ endpoints

    override suspend fun health(): StudioResult<StudioInstallation> =
        request<HealthDto>("GET", "/health", requireAuth = false).map {
            StudioMappers.installation(
                dto = it,
                installationId = account.id,
                cloudAccountId = account.accountId,
                accountLabel = account.name.ifEmpty { account.email },
                workerUrl = account.mlmWorkerUrl.orEmpty(),
                apiRoute = account.studioApiRoute.orEmpty(),
                adopted = account.studioAdopted,
            )
        }

    override suspend fun dashboard(): StudioResult<DashboardSnapshot> =
        request<DashboardDto>("GET", "/dashboard").map { StudioMappers.dashboard(it) }

    override suspend fun listUsers(
        cursor: String?,
        limit: Int,
        query: String?,
        status: String?,
    ): StudioResult<UserPage> {
        val q = buildString {
            append("?limit=").append(limit)
            cursor?.let { append("&cursor=").append(enc(it)) }
            query?.takeIf { it.isNotBlank() }?.let { append("&q=").append(enc(it)) }
            status?.takeIf { it.isNotBlank() }?.let { append("&status=").append(enc(it)) }
        }
        return request<UserPageDto>("GET", "/users$q").map {
            UserPage(
                items = StudioMappers.users(it.items, account.id),
                nextCursor = it.nextCursor,
                totalHint = it.totalHint,
            )
        }
    }

    override suspend fun syncUsers(since: Long, sinceUid: String, limit: Int): StudioResult<UserSyncPage> =
        request<UserSyncDto>(
            "GET",
            "/users?since=$since&since_uid=${enc(sinceUid)}&limit=$limit",
        ).map {
            UserSyncPage(
                items = StudioMappers.users(it.items, account.id),
                nextSince = it.nextSince ?: since,
                // An engine on schema 9 or below ignores `since_uid` and sends no `next_uid`. Then
                // the watermark stays a bare timestamp and the sync behaves as it always did — a
                // fleet on mixed builds is an ordinary state (R13), so the older half must not
                // start failing because the newer half gained a field.
                nextUid = it.nextUid ?: "",
                complete = it.complete ?: true,
            )
        }

    override suspend fun getUser(id: String): StudioResult<StudioUser> =
        request<UserDto>("GET", "/users/${enc(id)}").mapUser()

    override suspend fun createUser(
        username: String,
        policy: SubscriptionPolicy,
        note: String?,
        tags: List<String>,
        planId: String?,
    ): StudioResult<StudioUser> {
        val body = JSONObject().apply {
            put("username", username)
            note?.let { put("note", it) }
            if (tags.isNotEmpty()) put("tags", org.json.JSONArray(tags))
            planId?.let { put("plan_id", it) }
            put("policy", JSONObject(StudioMappers.policyBody(policy)))
        }
        return request<UserDto>("POST", "/users", body.toString()).mapUser()
    }

    override suspend fun patchUser(
        id: String,
        username: String?,
        note: String?,
        tags: List<String>?,
        planId: String?,
        status: String?,
        policy: SubscriptionPolicy?,
        clearPolicyFields: Set<String>,
    ): StudioResult<StudioUser> {
        val body = JSONObject().apply {
            // Only what was passed. A PATCH that sent every field would silently overwrite whatever
            // another device changed since this screen loaded.
            username?.let { put("username", it) }
            note?.let { put("note", it) }
            tags?.let { put("tags", org.json.JSONArray(it)) }
            planId?.let { put("plan_id", it) }
            status?.let { put("status", it) }
            policy?.let { p ->
                val o = JSONObject(StudioMappers.policyBody(p))
                for (f in clearPolicyFields) o.put(f, JSONObject.NULL)
                put("policy", o)
            }
        }
        return request<UserDto>("PATCH", "/users/${enc(id)}", body.toString()).mapUser()
    }

    override suspend fun deleteUser(id: String): StudioResult<Unit> =
        request("DELETE", "/users/${enc(id)}")

    override suspend fun setEnabled(id: String, enabled: Boolean): StudioResult<StudioUser> =
        request<UserDto>("POST", "/users/${enc(id)}:" + if (enabled) "enable" else "disable").mapUser()

    override suspend fun renewUser(
        id: String,
        planId: String?,
        addDays: Int?,
        addBytes: Long?,
        mode: RenewMode,
    ): StudioResult<StudioUser> {
        val body = JSONObject().apply {
            planId?.let { put("plan_id", it) }
            addDays?.let { put("add_days", it) }
            addBytes?.let { put("add_bytes", it) }
            put("mode", mode.wire)
        }
        return request<UserDto>("POST", "/users/${enc(id)}:renew", body.toString()).mapUser()
    }

    override suspend fun getSubscription(userId: String): StudioResult<Subscription> =
        request<SubscriptionDto>("GET", "/users/${enc(userId)}/subscription").mapSubscription()

    override suspend fun rotateSubscription(userId: String): StudioResult<Subscription> =
        request<SubscriptionDto>("POST", "/users/${enc(userId)}:rotate-subscription").mapSubscription()

    override suspend fun listConfigs(userId: String): StudioResult<List<StudioConfig>> =
        request<ConfigListDto>("GET", "/users/${enc(userId)}/configs").map { list ->
            list.items.orEmpty().mapNotNull { StudioMappers.config(it) }
        }

    override suspend fun createConfig(
        userId: String,
        uriTemplate: String,
        label: String?,
        protocol: String?,
        transportType: String?,
        credential: String?,
        routeKey: String?,
        authHash: String?,
        nodeIds: List<String>,
        exitCc: String?,
    ): StudioResult<StudioConfig> {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("uri_template", uriTemplate)
            label?.let { put("label", it) }
            protocol?.let { put("protocol", it) }
            transportType?.let { put("transport_type", it) }
            credential?.let { put("credential", it) }
            routeKey?.let { put("route_key", it) }
            authHash?.let { put("auth_hash", it) }
            // Omitted when empty rather than sent as [], so an engine on build 8 sees exactly the
            // body it saw yesterday and this cannot change behaviour anywhere it is not supported.
            if (nodeIds.isNotEmpty()) put("nodes", org.json.JSONArray(nodeIds))
            exitCc?.let { put("exit_cc", it) }
        }
        return request<ConfigDto>("POST", "/configs", body.toString()).map {
            // Named, because the positional form put the template in `credential` and the route key
            // in `uriTemplate` -- a config that looked created and rendered as nothing.
            StudioMappers.config(it) ?: StudioConfig(
                id = "", userId = userId, label = label, enabled = true, protocol = protocol,
                transportType = transportType, credential = credential, uriTemplate = uriTemplate,
                routeKey = routeKey, exitCc = exitCc,
            )
        }
    }

    override suspend fun retargetConfig(id: String, nodeIds: List<String>): StudioResult<Unit> {
        val body = JSONObject().apply {
            put("nodes", org.json.JSONArray().also { arr -> nodeIds.forEach { arr.put(it) } })
        }
        return request("POST", "/configs/${enc(id)}:nodes", body.toString())
    }

    override suspend fun deleteConfig(id: String): StudioResult<Unit> =
        request("DELETE", "/configs/${enc(id)}")

    override suspend fun rotateConfig(
        id: String,
        credential: String,
        uriTemplate: String,
        authHash: String?,
    ): StudioResult<Unit> {
        val body = JSONObject().apply {
            put("credential", credential)
            // The template travels with the credential because it carries it: a rotation that
            // changed one and not the other would leave the subscription serving a link built from
            // the old secret.
            put("uri_template", uriTemplate)
            put("auth_hash", authHash ?: JSONObject.NULL)
        }
        return request("POST", "/configs/${enc(id)}:rotate", body.toString())
    }

    // ------------------------------------------------------------------ «بسته»

    override suspend fun listTemplates(includeArchived: Boolean): StudioResult<List<ConfigTemplate>> =
        request<TemplateListDto>(
            "GET", "/templates" + if (includeArchived) "?archived=1" else ""
        ).map { dto -> dto.items.orEmpty().mapNotNull { StudioMappers.template(it) } }

    override suspend fun putTemplate(template: ConfigTemplate): StudioResult<ConfigTemplate> =
        request<TemplateDto>(
            "PUT", "/templates/${enc(template.id)}",
            body = mapOf(
                "name" to template.name,
                "protocol" to template.protocol,
                "transport_type" to template.transportType,
                // Free-form on the engine: it stores these as JSON and never reads inside them,
                // which is what lets a shape grow a field without a migration.
                "security" to mapOf(
                    "fingerprint" to template.fingerprint,
                    "alpn" to template.alpn,
                ),
                "advanced" to mapOf("own_path" to template.ownPath),
                "nodes" to template.nodeIds,
                "is_default" to template.isDefault,
            ),
        ).map { StudioMappers.template(it) ?: template }

    override suspend fun deleteTemplate(id: String): StudioResult<Unit> =
        request("DELETE", "/templates/${enc(id)}")

    override suspend fun listPlans(includeArchived: Boolean): StudioResult<List<Plan>> =
        request<PlanListDto>("GET", "/plans" + if (includeArchived) "?archived=1" else "").map { list ->
            list.items.orEmpty().mapNotNull { StudioMappers.plan(it) }
        }

    /**
     * `PUT`, with the id in the path rather than the body.
     *
     * That is what makes this safe to fan out across the fleet and safe to retry: the same call to
     * the same installation twice leaves one plan, and the same call to twenty installations leaves
     * the same plan on all twenty under one id.
     */
    override suspend fun putPlan(plan: Plan): StudioResult<Plan> =
        request<PlanDto>(
            "PUT", "/plans/${enc(plan.id)}",
            body = mapOf(
                "name" to plan.name,
                "quota_bytes" to plan.quotaBytes,
                "duration_days" to plan.durationDays,
                "expiry_mode" to if (plan.expiryMode == ExpiryMode.ON_FIRST_CONNECT) "on_first_connect" else "absolute",
                "daily_quota_bytes" to plan.dailyQuotaBytes,
                "reset_policy" to plan.reset.name.lowercase(),
                "device_limit" to plan.deviceLimit,
                "conn_limit" to plan.connLimit,
                "ip_limit" to plan.ipLimit,
                "template_id" to plan.templateId,
                "is_default" to plan.isDefault,
            ),
        ).mapPlan()

    override suspend fun archivePlan(id: String): StudioResult<Unit> =
        request("DELETE", "/plans/${enc(id)}")

    override suspend fun countPlanUsers(id: String): StudioResult<Int> =
        request<PlanUsersDto>("GET", "/plans/${enc(id)}/users").map { it.users ?: 0 }

    override suspend fun applyPlan(id: String, fields: List<String>): StudioResult<Int> =
        request<PlanApplyDto>("POST", "/plans/${enc(id)}:apply", body = mapOf("fields" to fields))
            .map { it.users ?: 0 }

    // ------------------------------------------------------------------ devices

    override suspend fun listDevices(userId: String): StudioResult<DeviceReport> =
        request<DeviceListDto>("GET", "/users/${enc(userId)}/devices").map { dto ->
            DeviceReport(
                // Absent means an engine too old to have this endpoint at all, which is the same
                // answer as an installation with no Durable Object: it cannot count devices.
                available = dto.available == true,
                live = dto.live ?: 0,
                devices = dto.devices.orEmpty().mapNotNull { d ->
                    val hash = d.deviceHash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    StudioDevice(
                        hash = hash,
                        firstSeen = d.firstSeen ?: 0,
                        lastSeen = d.lastSeen ?: 0,
                        clientHint = d.clientHint?.takeIf { it.isNotBlank() },
                        blocked = (d.blocked ?: 0) != 0,
                        online = d.online == true,
                    )
                },
                reason = dto.reason,
            )
        }

    override suspend fun deviceAction(userId: String, deviceHash: String, action: String): StudioResult<Unit> =
        request("POST", "/users/${enc(userId)}/devices/${enc(deviceHash)}:$action")

    // ------------------------------------------------------------------ analytics

    override suspend fun traffic(range: String, daily: Boolean): StudioResult<TrafficSeries> =
        request<TrafficSeriesDto>(
            "GET",
            "/analytics/traffic?range=${enc(range)}&granularity=" + if (daily) "day" else "hour",
        ).map { dto ->
            TrafficSeries(
                daily = dto.granularity == "day",
                rangeDays = dto.rangeDays ?: 0,
                points = dto.points.orEmpty().mapNotNull { p ->
                    val ts = p.ts ?: return@mapNotNull null
                    TrafficPoint(ts, p.upBytes ?: 0, p.downBytes ?: 0)
                },
                totalBytes = dto.totalBytes ?: 0,
            )
        }

    override suspend fun topUsers(range: String, limit: Int): StudioResult<List<TopUser>> =
        request<TopUsersDto>("GET", "/analytics/top-users?range=${enc(range)}&limit=$limit").map { dto ->
            dto.items.orEmpty().mapNotNull { u ->
                val id = u.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TopUser(id, u.username?.takeIf { it.isNotBlank() }, u.bytes ?: 0, u.sessions ?: 0)
            }
        }

override suspend fun analyticsSummary(range: String): StudioResult<AnalyticsSummary> =
        request<AnalyticsSummaryDto>("GET", "/analytics/summary?range=${enc(range)}").map { dto ->
            AnalyticsSummary(
                rangeDays = dto.rangeDays ?: 0,
                newUsers = dto.newUsers ?: 0,
                sessions = dto.sessions ?: 0,
                activeUsers = dto.activeUsers ?: 0,
                refusals = dto.refusals ?: 0,
                refusalRate = dto.refusalRate ?: 0f,
                bytes = dto.bytes ?: 0,
            )
        }

    override suspend fun analyticsBreakdown(range: String, limit: Int): StudioResult<UsageBreakdown> =
        request<UsageBreakdownDto>(
            "GET", "/analytics/breakdown?range=${enc(range)}&limit=$limit"
        ).map { dto ->
            fun slices(rows: List<UsageSliceDto>?): List<UsageSlice> = rows.orEmpty().mapNotNull { r ->
                // `id` on the config list, `key` on the protocol and transport lists. One shape
                // rather than three, because the screen renders all three the same way.
                val key = (r.id ?: r.key)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                UsageSlice(
                    key = key,
                    label = r.label?.takeIf { it.isNotBlank() },
                    username = r.username?.takeIf { it.isNotBlank() },
                    sessions = r.sessions ?: 0,
                    bytes = r.bytes ?: 0,
                )
            }
            UsageBreakdown(
                rangeDays = dto.rangeDays ?: 0,
                configs = slices(dto.configs),
                protocols = slices(dto.protocols),
                transports = slices(dto.transports),
                // Defaults to false rather than true: an engine that does not send the field is one
                // that cannot measure endpoints either, and a missing "no" must not read as a yes.
                nodesMeasurable = dto.nodesMeasurable ?: false,
            )
        }

    override suspend fun analyticsSessions(
        range: String,
        daily: Boolean,
        userId: String?,
    ): StudioResult<CountSeries> {
        val q = StringBuilder("/analytics/sessions?range=${enc(range)}")
        q.append("&granularity=").append(if (daily) "day" else "hour")
        userId?.takeIf { it.isNotBlank() }?.let { q.append("&user_id=").append(enc(it)) }
        return request<CountSeriesDto>("GET", q.toString()).map { dto ->
            CountSeries(
                rangeDays = dto.rangeDays ?: 0,
                points = dto.points.orEmpty().mapNotNull { p ->
                    val ts = p.ts ?: return@mapNotNull null
                    CountPoint(ts, p.sessions ?: 0, p.bytes ?: 0)
                },
            )
        }
    }

    override suspend fun analyticsNewUsers(range: String): StudioResult<CountSeries> =
        request<CountSeriesDto>("GET", "/analytics/users?range=${enc(range)}").map { dto ->
            CountSeries(
                rangeDays = dto.rangeDays ?: 0,
                points = dto.points.orEmpty().mapNotNull { p ->
                    val ts = p.ts ?: return@mapNotNull null
                    CountPoint(ts, p.users ?: 0)
                },
            )
        }

    override suspend fun userSessions(userId: String, limit: Int): StudioResult<List<SessionEntry>> =
        request<SessionListDto>(
            "GET", "/users/${enc(userId)}/sessions?limit=$limit"
        ).map { dto ->
            dto.items.orEmpty().mapNotNull { r ->
                val id = r.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SessionEntry(
                    id = id,
                    configId = r.configId?.takeIf { it.isNotBlank() },
                    configLabel = r.configLabel?.takeIf { it.isNotBlank() },
                    protocol = r.protocol?.takeIf { it.isNotBlank() },
                    transport = r.transport?.takeIf { it.isNotBlank() },
                    startedAt = r.startedAt ?: 0,
                    endedAt = r.endedAt ?: 0,
                    bytes = r.bytes ?: 0,
                    closeReason = r.closeReason?.takeIf { it.isNotBlank() },
                )
            }
        }

    override suspend fun audit(cursor: Long?, action: String?, limit: Int): StudioResult<AuditPage> {
        val q = buildString {
            append("?limit=").append(limit)
            cursor?.let { append("&cursor=").append(it) }
            action?.takeIf { it.isNotBlank() }?.let { append("&action=").append(enc(it)) }
        }
        return request<AuditPageDto>("GET", "/audit$q").map { dto ->
            AuditPage(
                items = dto.items.orEmpty().mapNotNull { e ->
                    val id = e.id ?: return@mapNotNull null
                    AuditEntry(
                        id = id,
                        ts = e.ts ?: 0,
                        actor = e.actor.orEmpty(),
                        action = e.action.orEmpty(),
                        targetType = e.targetType?.takeIf { it.isNotBlank() },
                        targetId = e.targetId?.takeIf { it.isNotBlank() },
                        summary = e.summary?.takeIf { it.isNotBlank() },
                    )
                },
                nextCursor = dto.nextCursor,
            )
        }
    }

    // ------------------------------------------------------------------ installation settings

    override suspend fun activity(
        cursor: Long?,
        severity: String?,
        limit: Int,
        userId: String?,
    ): StudioResult<ActivityPage> {
        val q = buildString {
            append("/activity?limit=").append(limit)
            cursor?.let { append("&cursor=").append(it) }
            severity?.let { append("&severity=").append(enc(it)) }
            userId?.takeIf { it.isNotBlank() }?.let { append("&user_id=").append(enc(it)) }
        }
        return request<ActivityPageDto>("GET", q).map { dto ->
            ActivityPage(
                items = dto.items.orEmpty().mapNotNull { StudioMappers.activity(it, account.id) },
                nextCursor = dto.nextCursor,
            )
        }
    }

    // ------------------------------------------------------------------ nodes

    override suspend fun listNodes(): StudioResult<List<StudioNode>> =
        request<NodeListDto>("GET", "/nodes").map { dto ->
            // A node with no id cannot be addressed by any later call, so it is dropped rather than
            // carried as a row whose every button would fail.
            dto.items.orEmpty()
                .filter { !it.id.isNullOrBlank() }
                .map { StudioMappers.node(it, account.id) }
        }

    override suspend fun putNode(node: StudioNode): StudioResult<StudioNode> {
        val body = JSONObject().apply {
            put("name", node.name)
            put("host", node.host)
            put("ports", org.json.JSONArray().apply { node.ports.forEach { put(it) } })
            put("priority", node.priority)
            put("enabled", node.enabled)
            // Sent as JSON null rather than omitted, because the engine replaces the row wholesale:
            // an omitted field would keep the old value, which makes clearing a server name
            // impossible from a screen that shows it as empty.
            put("country", node.country ?: JSONObject.NULL)
            put("city", node.city ?: JSONObject.NULL)
            // The group is sent the same way, so moving an endpoint OUT of a group is expressible.
            // Omitting it would make ungrouping impossible from a screen that shows no group.
            put("group_id", node.groupId ?: JSONObject.NULL)
            put("sni", node.sni ?: JSONObject.NULL)
            put("host_header", node.hostHeader ?: JSONObject.NULL)
        }
        return request<NodeDto>("PUT", "/nodes/" + enc(node.id), body.toString())
            .map { StudioMappers.node(it, account.id) }
    }

    override suspend fun deleteNode(id: String): StudioResult<Unit> =
        request("DELETE", "/nodes/" + enc(id))

    override suspend fun reportNodeHealth(
        id: String,
        ok: Boolean,
        latencyMs: Int?,
        error: String?,
    ): StudioResult<StudioNode> {
        val body = JSONObject().apply {
            put("ok", ok)
            latencyMs?.let { put("latency_ms", it) }
            error?.takeIf { it.isNotBlank() }?.let { put("error", it) }
        }
        return request<NodeDto>("POST", "/nodes/" + enc(id) + ":health", body.toString())
            .map { StudioMappers.node(it, account.id) }
    }

    override suspend fun listNodeGroups(): StudioResult<List<NodeGroup>> =
        request<NodeGroupListDto>("GET", "/node-groups").map { dto ->
            dto.items.orEmpty().mapNotNull { StudioMappers.nodeGroup(it, account.id) }
        }

    override suspend fun putNodeGroup(group: NodeGroup): StudioResult<NodeGroup> =
        request<NodeGroupDto>(
            "PUT", "/node-groups/${enc(group.id)}",
            body = mapOf(
                "name" to group.name,
                "strategy" to group.strategy.wire,
                "priority" to group.priority,
                "enabled" to group.enabled,
            ),
        ).map { StudioMappers.nodeGroup(it, account.id) ?: group }

    override suspend fun deleteNodeGroup(id: String): StudioResult<Unit> =
        request("DELETE", "/node-groups/${enc(id)}")

    override suspend fun nodeHistory(id: String): StudioResult<NodeHistory> =
        request<NodeHistoryDto>("GET", "/nodes/${enc(id)}/health")
            .map { StudioMappers.nodeHistory(it, id) }

    // ------------------------------------------------------------------ exits («لوکیشن»)
    //
    // Not on [StudioApi]: they need `exits.v1`, and the screens that use them already hold an
    // installation and talk to it directly.

    private fun exit(dto: com.mlmvpn.scanner.data.studio.api.dto.ExitDto): com.mlmvpn.scanner.data.studio.domain.StudioExit? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        return com.mlmvpn.scanner.data.studio.domain.StudioExit(
            id = id,
            cc = dto.cc?.takeIf { it.length == 2 } ?: "ZZ",
            label = dto.label.orEmpty(),
            url = dto.url.orEmpty(),
            display = dto.display.orEmpty(),
            enabled = dto.enabled ?: true,
            health = dto.health ?: "unknown",
            exitIp = dto.exitIp,
            exitCc = dto.exitCc?.takeIf { it.length == 2 },
            latencyMs = dto.latencyMs,
            checkedAt = dto.checkedAt,
        )
    }

    suspend fun listExits(): StudioResult<List<com.mlmvpn.scanner.data.studio.domain.StudioExit>> =
        request<com.mlmvpn.scanner.data.studio.api.dto.ExitListDto>("GET", "/exits").map { list ->
            list.items.orEmpty().mapNotNull { exit(it) }
        }

    /** Each line as the operator pasted it, with the country they filed it under (or none). */
    suspend fun addExits(items: List<Pair<String, String?>>): StudioResult<com.mlmvpn.scanner.data.studio.domain.ExitAddResult> {
        val arr = org.json.JSONArray()
        items.forEach { (url, cc) -> arr.put(JSONObject().apply { put("url", url); cc?.let { put("cc", it) } }) }
        return request<com.mlmvpn.scanner.data.studio.api.dto.ExitAddDto>(
            "POST", "/exits", JSONObject().put("items", arr).toString(),
        ).map { dto ->
            com.mlmvpn.scanner.data.studio.domain.ExitAddResult(
                added = dto.added.orEmpty(),
                rejected = dto.rejected.orEmpty().map { it.url.orEmpty() to it.reason.orEmpty() },
            )
        }
    }

    /** A list read from a link by the engine (it can reach GitHub when the phone cannot). */
    suspend fun importExits(url: String, cc: String?): StudioResult<com.mlmvpn.scanner.data.studio.domain.ExitAddResult> =
        request<com.mlmvpn.scanner.data.studio.api.dto.ExitAddDto>(
            "POST", "/exits:import",
            JSONObject().apply { put("url", url); cc?.let { put("cc", it) } }.toString(),
        ).map { dto ->
            com.mlmvpn.scanner.data.studio.domain.ExitAddResult(
                added = dto.added.orEmpty(),
                rejected = dto.rejected.orEmpty().map { it.url.orEmpty() to it.reason.orEmpty() },
            )
        }

    suspend fun patchExit(id: String, enabled: Boolean? = null, cc: String? = null): StudioResult<Unit> {
        val body = JSONObject().apply {
            enabled?.let { put("enabled", it) }
            cc?.let { put("cc", it) }
        }
        return request("PATCH", "/exits/${enc(id)}", body.toString())
    }

    suspend fun deleteExit(id: String): StudioResult<Unit> = request("DELETE", "/exits/${enc(id)}")

    /** Dialled through, by the engine: whether it answers, where it leaves from, and how fast. */
    suspend fun testExit(id: String): StudioResult<Pair<com.mlmvpn.scanner.data.studio.domain.StudioExit?, String?>> =
        request<com.mlmvpn.scanner.data.studio.api.dto.ExitDto>("POST", "/exits/${enc(id)}:test")
            .map { exit(it) to it.error }

    // ── build 17: multi-location ─────────────────────────────────────────────────────────────

    /** The ports a person's subscription is served on ("443,8443,…"). Empty restores the default. */
    suspend fun setPorts(userId: String, ports: List<Int>): StudioResult<Unit> =
        request<UserDto>(
            "PATCH", "/users/${enc(userId)}",
            JSONObject().put("ports", org.json.JSONArray(ports)).toString(),
        ).map { }

    /** The public-pool settings (04k): on/off, the list it reads, the countries it offers, the warning. */
    suspend fun getPool(): StudioResult<PoolInfo> =
        request<PoolDto>("GET", "/pool").map {
            PoolInfo(
                enabled = it.enabled == true,
                source = it.source.orEmpty(),
                countries = it.countries.orEmpty(),
                warning = it.warning.orEmpty(),
                verified = it.verified.orEmpty(),
            )
        }

    /**
     * Test a batch of [cc]'s public servers on the engine and keep the ones that really leave from
     * there (build 18). The engine tests at most a dozen per call, so a screen repeats it until the
     * country has enough. An engine older than 18 answers 404, which reads as "cannot verify".
     */
    suspend fun verifyPool(cc: String): StudioResult<PoolVerify> =
        request<PoolVerifyDto>("POST", "/pool/verify?cc=${enc(cc)}", "{}").map { dto ->
            PoolVerify(
                cc = cc,
                tested = dto.tested ?: 0,
                found = dto.found ?: 0,
                candidates = dto.candidates ?: 0,
                verified = dto.verified.orEmpty().map {
                    PoolServer(it.exitIp, it.exitCc, it.latencyMs, it.checkedAt)
                },
            )
        }

    /**
     * Turn the pool on or off. On is only ever sent with [risksAcknowledged] = true, from the
     * confirmation that lists the risks; the engine refuses it otherwise.
     */
    suspend fun setPool(enabled: Boolean, risksAcknowledged: Boolean = false): StudioResult<Unit> =
        request<PoolDto>(
            "PATCH", "/pool",
            JSONObject().put("enabled", enabled).put("risks_acknowledged", risksAcknowledged).toString(),
        ).map { }

    /** Find a working public server for [cc] and say where it really leaves from. */
    suspend fun testPool(cc: String): StudioResult<PoolTest> =
        request<PoolTestDto>("GET", "/pool/test?cc=${enc(cc)}").map {
            PoolTest(
                cc = cc, ok = it.ok == true, candidates = it.candidates ?: 0,
                exitIp = it.exitIp, exitCc = it.exitCc, latencyMs = it.latencyMs,
            )
        }

    data class PoolInfo(
        val enabled: Boolean,
        val source: String,
        val countries: List<String>,
        val warning: String,
        /** Per country, how many public servers were tested and really leave from there. */
        val verified: Map<String, Int> = emptyMap(),
    )

    /** One verified public server, as the engine measured it. Never the proxy's own address. */
    data class PoolServer(val exitIp: String?, val exitCc: String?, val latencyMs: Long?, val checkedAt: Long?)

    data class PoolVerify(
        val cc: String,
        val tested: Int,
        val found: Int,
        val candidates: Int,
        val verified: List<PoolServer>,
    )

    data class PoolVerifyDto(
        @com.google.gson.annotations.SerializedName("tested") val tested: Int? = null,
        @com.google.gson.annotations.SerializedName("found") val found: Int? = null,
        @com.google.gson.annotations.SerializedName("candidates") val candidates: Int? = null,
        @com.google.gson.annotations.SerializedName("verified") val verified: List<PoolServerDto>? = null,
    )

    data class PoolServerDto(
        @com.google.gson.annotations.SerializedName("exit_ip") val exitIp: String? = null,
        @com.google.gson.annotations.SerializedName("exit_cc") val exitCc: String? = null,
        @com.google.gson.annotations.SerializedName("latency_ms") val latencyMs: Long? = null,
        @com.google.gson.annotations.SerializedName("checked_at") val checkedAt: Long? = null,
    )
    data class PoolTest(val cc: String, val ok: Boolean, val candidates: Int, val exitIp: String?, val exitCc: String?, val latencyMs: Long?)

    data class PoolDto(
        @com.google.gson.annotations.SerializedName("enabled") val enabled: Boolean? = null,
        @com.google.gson.annotations.SerializedName("source") val source: String? = null,
        @com.google.gson.annotations.SerializedName("countries") val countries: List<String>? = null,
        @com.google.gson.annotations.SerializedName("warning") val warning: String? = null,
        @com.google.gson.annotations.SerializedName("verified") val verified: Map<String, Int>? = null,
    )

    data class PoolTestDto(
        @com.google.gson.annotations.SerializedName("ok") val ok: Boolean? = null,
        @com.google.gson.annotations.SerializedName("candidates") val candidates: Int? = null,
        @com.google.gson.annotations.SerializedName("exit_ip") val exitIp: String? = null,
        @com.google.gson.annotations.SerializedName("exit_cc") val exitCc: String? = null,
        @com.google.gson.annotations.SerializedName("latency_ms") val latencyMs: Long? = null,
    )

    /** Move one config to another country, or to none. The credential does not change. */
    suspend fun moveConfigExit(configId: String, cc: String?): StudioResult<Unit> =
        request("POST", "/configs/${enc(configId)}:exit",
            JSONObject().put("exit_cc", cc ?: JSONObject.NULL).toString())

    override suspend fun getSettings(): StudioResult<StudioSettings> =
        request<SettingsDto>("GET", "/settings").map {
            StudioSettings(
                contact = it.contact?.takeIf { s -> s.isNotBlank() },
                brand = it.brand?.takeIf { s -> s.isNotBlank() },
            )
        }

    override suspend fun patchSettings(contact: String?, brand: String?): StudioResult<StudioSettings> {
        // Only what was passed, so clearing one field cannot wipe the other. An empty string is a
        // deliberate clear and is sent; null means "leave it alone" and is not.
        val body = JSONObject().apply {
            contact?.let { put("contact", it) }
            brand?.let { put("brand", it) }
        }
        return request<SettingsDto>("PATCH", "/settings", body.toString()).map {
            StudioSettings(
                contact = it.contact?.takeIf { s -> s.isNotBlank() },
                brand = it.brand?.takeIf { s -> s.isNotBlank() },
            )
        }
    }

    private fun StudioResult<PlanDto>.mapPlan(): StudioResult<Plan> = when (this) {
        is StudioResult.Err -> this
        is StudioResult.Ok -> StudioMappers.plan(value)
            ?.let { StudioResult.Ok(it) }
            ?: StudioResult.Err(StudioError(StudioError.MALFORMED, detail = "plan without an id"))
    }

    // A user or subscription with no id cannot be addressed by any later call, so it is refused
    // here rather than carried as an object every subsequent operation would fail on.
    private fun StudioResult<UserDto>.mapUser(): StudioResult<StudioUser> = when (this) {
        is StudioResult.Err -> this
        is StudioResult.Ok -> StudioMappers.user(value, account.id)
            ?.let { StudioResult.Ok(it) }
            ?: StudioResult.Err(StudioError(StudioError.MALFORMED, detail = "user without an id"))
    }

    private fun StudioResult<SubscriptionDto>.mapSubscription(): StudioResult<Subscription> = when (this) {
        is StudioResult.Err -> this
        is StudioResult.Ok -> StudioMappers.subscription(value)
            ?.let { StudioResult.Ok(it) }
            ?: StudioResult.Err(StudioError(StudioError.MALFORMED, detail = "subscription without an id"))
    }
}
