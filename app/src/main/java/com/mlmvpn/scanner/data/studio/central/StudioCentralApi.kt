package com.mlmvpn.scanner.data.studio.central

import android.content.Context
import com.mlmvpn.scanner.data.studio.api.ActivityPage
import com.mlmvpn.scanner.data.studio.api.AnalyticsSummary
import com.mlmvpn.scanner.data.studio.api.AuditEntry
import com.mlmvpn.scanner.data.studio.api.AuditPage
import com.mlmvpn.scanner.data.studio.api.CountSeries
import com.mlmvpn.scanner.data.studio.api.DeviceReport
import com.mlmvpn.scanner.data.studio.api.RenewMode
import com.mlmvpn.scanner.data.studio.api.SessionEntry
import com.mlmvpn.scanner.data.studio.api.StudioApi
import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.api.StudioSettings
import com.mlmvpn.scanner.data.studio.api.TopUser
import com.mlmvpn.scanner.data.studio.api.TrafficSeries
import com.mlmvpn.scanner.data.studio.api.UsageBreakdown
import com.mlmvpn.scanner.data.studio.api.UserPage
import com.mlmvpn.scanner.data.studio.api.UserSyncPage
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.data.studio.domain.DashboardSnapshot
import com.mlmvpn.scanner.data.studio.domain.Enforcement
import com.mlmvpn.scanner.data.studio.domain.NodeGroup
import com.mlmvpn.scanner.data.studio.domain.NodeHistory
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.StudioConfig
import com.mlmvpn.scanner.data.studio.domain.StudioInstallation
import com.mlmvpn.scanner.data.studio.domain.StudioNode
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.Subscription
import com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy
import com.mlmvpn.scanner.data.studio.domain.UserStatus
import com.mlmvpn.scanner.engines.gateway.EdgeGateway
import com.mlmvpn.scanner.engines.gateway.GatewayException
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [StudioApi] for an account whose people ride its central Worker — the same interface every
 * screen already talks to, answered from this phone instead of from an engine. Reads come from the
 * phone's copy; writes go through [CentralEngine] (pull, change, publish, upload — all or nothing).
 *
 * What the central Worker cannot do is answered honestly rather than faked: no volume, no device
 * counter, no sessions, no subscription page. A screen asks the installation's capabilities
 * ([CAPABILITIES]) before it draws any of those, so most of these refusals are never reached.
 */
class StudioCentralApi(
    private val context: Context,
    /** The fleet's view of the account: `id` is the installation id (`<account>#gw`). */
    private val account: CloudAccount,
) : StudioApi {

    companion object {
        /** What a central-Worker installation can do (asked by the screens through `StudioInstallation.can`). */
        val CAPABILITIES = setOf(
            "central.v1",
            // A renewal may take days back (floored at now), like an engine on build 8+.
            "renew.adjust",
            // The dashboard draws its feed only when EVERY installation can answer; this one answers empty.
            "activity.v1",
        )

        /** The code of a Worker-side refusal whose words are in [StudioError.detail], for the screens to show as they are. */
        const val GATEWAY = "gateway"
        const val NOT_SUPPORTED = "not_supported"

        private fun unsupported(): StudioResult.Err = StudioResult.Err(StudioError(NOT_SUPPORTED))
    }

    private val installationId = account.id
    private val engine by lazy { centralEngine(context, account) }
    private fun now() = System.currentTimeMillis()

    /** Run [block] on IO; every way it can fail becomes a [StudioError] a screen can render. */
    private suspend fun <T> run(block: () -> T): StudioResult<T> = withContext(Dispatchers.IO) {
        try {
            StudioResult.Ok(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errOf(e)
        }
    }

    private fun errOf(e: Throwable): StudioResult.Err = when (e) {
        is CentralRules.RuleError -> StudioResult.Err(StudioError(e.code, detail = e.detail))
        is GatewayException -> StudioResult.Err(StudioError(GATEWAY, detail = EdgeGateway.localized(e)))
        else -> {
            val offline = generateSequence(e) { it.cause }.any { it is java.io.IOException }
            StudioResult.Err(StudioError(if (offline) StudioError.NETWORK else GATEWAY, detail = EdgeGateway.explainForStudio(e)))
        }
    }

    private fun view(u: CentralUser) = u.toStudioUser(installationId, now())

    private fun doc() = engine.current()

    // ------------------------------------------------------------------ the installation

    override suspend fun health(): StudioResult<StudioInstallation> = run {
        val d = doc()
        val gw = EdgeGateway.status(context, account)
        StudioInstallation(
            installationId = installationId,
            cloudAccountId = account.accountId,
            accountLabel = account.name.ifEmpty { account.email },
            workerUrl = gw.url.ifBlank { d.host.takeIf { it.isNotBlank() }?.let { "https://$it" }.orEmpty() },
            apiRoute = "",
            enforcement = Enforcement.SOFT,
            capabilities = CAPABILITIES,
            d1Ok = true,
            users = d.people.size,
            reachable = true,
            centralRoom = gw.studioRoom,
            centralLive = d.liveCount(now()),
        )
    }

    override suspend fun dashboard(): StudioResult<DashboardSnapshot> = run {
        val d = doc()
        val now = now()
        val soon = now + 3 * Central.DAY_MS
        val people = d.people
        DashboardSnapshot(
            total = people.size,
            active = people.count { it.isLive(now) },
            expiringSoon = people.count { it.isLive(now) && it.expiresAt in (now + 1)..soon },
            expired = people.count { it.isExpired(now) },
            overQuota = 0,
            enforcement = Enforcement.SOFT,
            generatedAt = now,
        )
    }

    // ------------------------------------------------------------------ people

    override suspend fun listUsers(cursor: String?, limit: Int, query: String?, status: String?): StudioResult<UserPage> = run {
        val now = now()
        val q = query?.trim()?.lowercase().orEmpty()
        val all = doc().people.sortedWith(compareByDescending<CentralUser> { it.createdAt }.thenByDescending { it.id })
            .map { it.toStudioUser(installationId, now) }
            .filter { q.isEmpty() || it.username.lowercase().contains(q) }
            .filter { status == null || it.status == UserStatus.from(status) }
        val start = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = all.drop(start).take(limit.coerceIn(1, 500))
        UserPage(page, if (start + page.size < all.size) (start + page.size).toString() else null, all.size)
    }

    /**
     * Everyone, every time — tombstones included, so the index drops them — after the account's record
     * has been taken in. One page: the whole record is a few hundred people at most, and a status that
     * depends on the clock (an ended term) is recomputed on every read rather than frozen in the index.
     */
    override suspend fun syncUsers(since: Long, sinceUid: String, limit: Int): StudioResult<UserSyncPage> = run {
        val d = engine.pull()
        val now = now()
        val items = d.users.map { it.toStudioUser(installationId, now) }
        UserSyncPage(items, nextSince = d.users.maxOfOrNull { it.updatedAt } ?: since, nextUid = "", complete = true)
    }

    override suspend fun getUser(id: String): StudioResult<StudioUser> {
        val u = doc().user(id)?.takeIf { !it.isDeleted } ?: return StudioResult.Err(StudioError(StudioError.NOT_FOUND, 404))
        return StudioResult.Ok(view(u))
    }

    override suspend fun createUser(username: String, policy: SubscriptionPolicy, note: String?, tags: List<String>, planId: String?): StudioResult<StudioUser> =
        run {
            val now = now()
            // «شروع از اولین اتصال» has nothing to count from here: the term starts now.
            val expiresAt = policy.expiresAt?.takeIf { it > 0 }
                ?: policy.activationDays?.takeIf { it > 0 }?.let { now + it * Central.DAY_MS }
                ?: 0L
            val user = engine.mutate { d, room ->
                CentralRules.create(d, username, expiresAt, now, room, Central::newUserId, { UUID.randomUUID().toString() },
                    planId = planId, note = note, tags = tags)
            }
            engine.audit("user.create", user.id, user.name)
            view(user)
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
    ): StudioResult<StudioUser> = run {
        val now = now()
        val enabled = when (status?.lowercase()) {
            "active" -> true
            "disabled" -> false
            else -> null
        }
        val expiresAt = when {
            "expires_at" in clearPolicyFields -> 0L
            else -> policy?.expiresAt?.takeIf { it > 0 }
        }
        val user = engine.mutate { d, room ->
            CentralRules.patch(d, id, now, room, name = username, note = note, tags = tags, planId = planId, expiresAt = expiresAt, enabled = enabled)
        }
        engine.audit("user.update", user.id, user.name)
        view(user)
    }

    override suspend fun deleteUser(id: String): StudioResult<Unit> = run {
        val now = now()
        val name = doc().user(id)?.name
        engine.mutate { d, _ -> CentralRules.delete(d, id, now) to Unit }
        engine.audit("user.delete", id, name)
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): StudioResult<StudioUser> = run {
        val now = now()
        val user = engine.mutate { d, room -> CentralRules.setEnabled(d, id, enabled, now, room) }
        engine.audit(if (enabled) "user.enable" else "user.disable", user.id, user.name)
        view(user)
    }

    override suspend fun renewUser(id: String, planId: String?, addDays: Int?, addBytes: Long?, mode: RenewMode): StudioResult<StudioUser> =
        run {
            // Volume is not something the central Worker can count; a renewal that is ONLY volume
            // is refused rather than reported as done.
            if ((addDays == null || addDays == 0) && planId == null && addBytes != null && mode == RenewMode.EXTEND) {
                throw CentralRules.RuleError(NOT_SUPPORTED, "volume")
            }
            val now = now()
            val user = engine.mutate { d, room ->
                val planDays = planId?.let { pid -> d.plans.firstOrNull { it.id == pid && !it.archived }?.durationDays }
                if (planId != null && planDays == null && (addDays == null || addDays == 0)) throw CentralRules.RuleError(CentralRules.NOT_FOUND, "plan")
                CentralRules.renew(d, id, addDays, mode, now, room, planDays = planDays, planId = planId)
            }
            engine.audit("user.renew", user.id, user.name)
            view(user)
        }

    /** There is no subscription document on the central Worker: a person is handed their links instead. */
    override suspend fun getSubscription(userId: String): StudioResult<Subscription> = unsupported()

    override suspend fun rotateSubscription(userId: String): StudioResult<Subscription> = unsupported()

    /**
     * A person's links, one [StudioConfig] each — what «ترکیب» and the arena read a person's configs
     * as. Built on this phone (the panels' set, with their id and their name).
     */
    override suspend fun listConfigs(userId: String): StudioResult<List<StudioConfig>> {
        val u = doc().user(userId)?.takeIf { !it.isDeleted } ?: return StudioResult.Err(StudioError(StudioError.NOT_FOUND, 404))
        val links = EdgeGateway.studioLinks(context, account, u.gw, u.name).getOrElse { return errOf(it) }.configs
        val on = u.isLive(now())
        return StudioResult.Ok(links.mapIndexed { i, link ->
            StudioConfig(
                id = "${u.id}:$i",
                userId = u.id,
                label = runCatching { java.net.URLDecoder.decode(link.substringAfter('#', ""), "UTF-8") }.getOrDefault(""),
                enabled = on,
                protocol = "v",
                transportType = "ws",
                credential = u.gw,
                uriTemplate = link,
                createdAt = u.createdAt,
            )
        })
    }

    override suspend fun createConfig(
        userId: String, uriTemplate: String, label: String?, protocol: String?, transportType: String?, credential: String?,
        routeKey: String?, authHash: String?, nodeIds: List<String>, exitCc: String?,
    ): StudioResult<StudioConfig> = unsupported()

    override suspend fun rotateConfig(id: String, credential: String, uriTemplate: String, authHash: String?): StudioResult<Unit> = unsupported()
    override suspend fun retargetConfig(id: String, nodeIds: List<String>): StudioResult<Unit> = unsupported()
    override suspend fun deleteConfig(id: String): StudioResult<Unit> = unsupported()

    // ------------------------------------------------------------------ «قالب» (no shapes to choose: VLESS over WebSocket)

    override suspend fun listTemplates(includeArchived: Boolean): StudioResult<List<ConfigTemplate>> = StudioResult.Ok(emptyList())
    override suspend fun putTemplate(template: ConfigTemplate): StudioResult<ConfigTemplate> = unsupported()
    override suspend fun deleteTemplate(id: String): StudioResult<Unit> = unsupported()

    // ------------------------------------------------------------------ «بسته»

    override suspend fun listPlans(includeArchived: Boolean): StudioResult<List<Plan>> = run {
        engine.pull().plans.filter { includeArchived || !it.archived }
    }

    override suspend fun putPlan(plan: Plan): StudioResult<Plan> = run {
        val now = now()
        engine.share { d -> CentralRules.putPlan(d, plan, now).let { it to it.plans.first { p -> p.id == plan.id } } }
    }

    override suspend fun archivePlan(id: String): StudioResult<Unit> = run {
        val now = now()
        engine.share { d -> CentralRules.archivePlan(d, id, now) to Unit }
    }

    override suspend fun countPlanUsers(id: String): StudioResult<Int> = StudioResult.Ok(CentralRules.countPlan(doc(), id))

    /** A «بسته»'s caps are all an apply moves, and the central Worker has none: nobody is written. */
    override suspend fun applyPlan(id: String, fields: List<String>): StudioResult<Int> = StudioResult.Ok(0)

    // ------------------------------------------------------------------ what the central Worker cannot know

    override suspend fun listDevices(userId: String): StudioResult<DeviceReport> =
        StudioResult.Ok(DeviceReport(available = false, reason = "central"))

    override suspend fun deviceAction(userId: String, deviceHash: String, action: String): StudioResult<Unit> = unsupported()

    override suspend fun traffic(range: String, daily: Boolean): StudioResult<TrafficSeries> =
        StudioResult.Ok(TrafficSeries(daily, if (range == "30d") 30 else 7, emptyList(), 0))

    override suspend fun topUsers(range: String, limit: Int): StudioResult<List<TopUser>> = StudioResult.Ok(emptyList())
    override suspend fun analyticsSummary(range: String): StudioResult<AnalyticsSummary> = unsupported()
    override suspend fun analyticsBreakdown(range: String, limit: Int): StudioResult<UsageBreakdown> = unsupported()
    override suspend fun analyticsSessions(range: String, daily: Boolean, userId: String?): StudioResult<CountSeries> = unsupported()
    override suspend fun analyticsNewUsers(range: String): StudioResult<CountSeries> = unsupported()
    override suspend fun userSessions(userId: String, limit: Int): StudioResult<List<SessionEntry>> = StudioResult.Ok(emptyList())

    /** What THIS phone did on this account, newest first. */
    override suspend fun audit(cursor: Long?, action: String?, limit: Int): StudioResult<AuditPage> {
        val all = doc().audit.filter { (cursor == null || it.ts < cursor) && (action == null || it.action == action) }
        val page = all.take(limit.coerceIn(1, 200))
        val items = page.map { AuditEntry(it.ts, it.ts, "phone", it.action, "user", it.target, it.summary) }
        return StudioResult.Ok(AuditPage(items, if (all.size > page.size) page.lastOrNull()?.ts else null))
    }

    override suspend fun activity(cursor: Long?, severity: String?, limit: Int, userId: String?): StudioResult<ActivityPage> =
        StudioResult.Ok(ActivityPage())

    // ------------------------------------------------------------------ endpoints: the central Worker's own settings decide them

    override suspend fun listNodes(): StudioResult<List<StudioNode>> = StudioResult.Ok(emptyList())
    override suspend fun putNode(node: StudioNode): StudioResult<StudioNode> = unsupported()
    override suspend fun deleteNode(id: String): StudioResult<Unit> = unsupported()
    override suspend fun reportNodeHealth(id: String, ok: Boolean, latencyMs: Int?, error: String?): StudioResult<StudioNode> = unsupported()
    override suspend fun listNodeGroups(): StudioResult<List<NodeGroup>> = StudioResult.Ok(emptyList())
    override suspend fun putNodeGroup(group: NodeGroup): StudioResult<NodeGroup> = unsupported()
    override suspend fun deleteNodeGroup(id: String): StudioResult<Unit> = unsupported()
    override suspend fun nodeHistory(id: String): StudioResult<NodeHistory> = unsupported()

    // ------------------------------------------------------------------ the central Worker's own actions

    /** «لینک تازه»: a new Worker id for this person alone. Every device on the old one is cut off. */
    suspend fun rotateLink(userId: String): StudioResult<StudioUser> = run {
        val now = now()
        val user = engine.mutate { d, _ -> CentralRules.rotate(d, userId, now, { UUID.randomUUID().toString() }) }
        engine.audit("user.rotate", user.id, user.name)
        view(user)
    }

    /** This person's links, built on this phone. [refresh] looks the Worker's addresses up again. */
    suspend fun links(userId: String, refresh: Boolean = false): StudioResult<List<String>> {
        val u = doc().user(userId)?.takeIf { !it.isDeleted } ?: return StudioResult.Err(StudioError(StudioError.NOT_FOUND, 404))
        return EdgeGateway.studioLinks(context, account, u.gw, u.name, refresh).fold(
            onSuccess = { StudioResult.Ok(it.configs) },
            onFailure = { errOf(it) },
        )
    }

    /** Bring the Worker in step now (an ended term, ids another device dropped). */
    suspend fun applyNow(): StudioResult<Reconciled> = run { engine.reconcile() }

    /**
     * A person moved here from an engine installation: the same name, the same end, their note and
     * tags — and a new link, because the Worker is another one. [movedFrom] is "installation|user".
     * The name is checked on this account only: on the engine it belongs to the very person moving.
     */
    suspend fun createMoved(name: String, expiresAt: Long, note: String?, tags: List<String>, movedFrom: String): StudioResult<StudioUser> =
        run {
            val now = now()
            if (doc().people.any { it.movedFrom == movedFrom }) throw CentralRules.RuleError("already_moved")
            val user = engine.mutate { d, room ->
                // An end already passed is refused by the rule itself: a move never gives time away.
                CentralRules.create(d, name, expiresAt.coerceAtLeast(0L), now, room, Central::newUserId, { UUID.randomUUID().toString() },
                    note = note, tags = tags, movedFrom = movedFrom)
            }
            engine.audit("user.move", user.id, user.name)
            view(user)
        }

    // ------------------------------------------------------------------ the operator's own words

    override suspend fun getSettings(): StudioResult<StudioSettings> = StudioResult.Ok(StudioSettings(contact = doc().contact))

    override suspend fun patchSettings(contact: String?, brand: String?): StudioResult<StudioSettings> = run {
        if (contact == null) return@run StudioSettings(contact = doc().contact)
        val text = contact.trim().take(Central.MAX_CONTACT).ifEmpty { null }
        engine.share { d -> d.copy(contact = text) to Unit }
        StudioSettings(contact = text)
    }
}
