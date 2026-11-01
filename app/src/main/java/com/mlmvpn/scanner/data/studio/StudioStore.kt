package com.mlmvpn.scanner.data.studio

import android.content.Context
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.data.PanelBuild
import com.mlmvpn.scanner.data.studio.api.RenewMode
import com.mlmvpn.scanner.data.studio.api.StudioApi
import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.domain.DashboardSnapshot
import com.mlmvpn.scanner.data.studio.domain.Enforcement
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.ActivityEntry
import com.mlmvpn.scanner.data.studio.domain.NodeGroup
import com.mlmvpn.scanner.data.studio.domain.NodeHealth
import com.mlmvpn.scanner.data.studio.domain.NodeHistory
import com.mlmvpn.scanner.data.studio.domain.StudioInstallation
import com.mlmvpn.scanner.data.studio.domain.StudioNode
import com.mlmvpn.scanner.data.studio.domain.StudioUser
import com.mlmvpn.scanner.data.studio.domain.UserStatus
import com.mlmvpn.scanner.data.studio.index.StudioIndex
import com.mlmvpn.scanner.data.studio.index.UserFilter
import com.mlmvpn.scanner.data.studio.index.UserSort
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The state of Config Studio, and the only place a write to it is allowed to originate.
 *
 * A singleton with an immutable state object, following `CloudManager` and `NodeManager` rather than
 * introducing ViewModels — this app has none across ~50 screens, and one feature with a different
 * lifecycle model is a permanent seam. The one thing that differs from `CloudManager` is deliberate:
 * **the state is immutable and replaced**, never mutated in place. `CloudManager.saveAccounts()`
 * publishes the same object references, so in-place field mutation is dropped by `StateFlow`'s
 * `distinctUntilChanged`, and three screens carry a `justDeployed` flag to work around it. Copying
 * costs nothing here and removes the whole class of bug (plan F6).
 */
class StudioStore private constructor(private val context: Context) {

    companion object {
        @Volatile private var INSTANCE: StudioStore? = null

        fun get(context: Context): StudioStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StudioStore(context.applicationContext).also { INSTANCE = it }
            }

        /** How long before an installation's data is called old on screen. */
        const val STALE_AFTER_MS = 10 * 60 * 1000L

        /**
         * How often the app probes every endpoint on its own.
         *
         * Fifteen minutes, and the number is a balance between two real costs rather than a round
         * figure: each pass is one TCP handshake per endpoint on the operator's own mobile
         * connection plus one D1 write per result, and a screen that re-probed on every navigation
         * would spend both on nothing. Not persisted across process death — a fresh launch
         * probing once is the behaviour an operator opening the app to check on things wants.
         */
        const val AUTO_PROBE_EVERY_MS = 15 * 60 * 1000L

        /**
         * How long a node probe waits for a TCP handshake before calling it down.
         *
         * Four seconds because this runs on the network the answer is about. A blocked address on
         * an Iranian mobile carrier does not refuse -- it goes silent, and the socket sits until
         * something gives up. Too short and a slow but working route reads as broken; too long and
         * probing eight nodes is half a minute of a spinner.
         */
        const val PROBE_TIMEOUT_MS = 4000

        /**
         * "Set this cap to no limit", as a value an Int field can carry.
         *
         * A bulk cap change has THREE intentions and only two of them are expressible as `Int?`:
         * leave it alone, set it to a number, and clear it. The first and the third both want to
         * end as `null` on the wire, so one of them needs a sentinel — and -1 is safe because a
         * cap of minus one is not a thing any screen can produce.
         */
        const val CLEAR_CAP = -1

        /** How far back every sync re-reads, so a usage flush committed out of order is not missed. */
        const val SYNC_OVERLAP_MS = 2 * 60 * 1000L

        /** A new node id, minted on the device, for the same reason a plan id is. */
        fun newNodeId(): String =
            "n" + java.util.UUID.randomUUID().toString().replace("-", "").take(15)

        /**
         * A new plan id, minted on the **device**.
         *
         * The app chooses it, not a worker, because one «بسته» has to be the same row on every
         * account in the fleet. A server-generated id would give the same plan twenty different
         * ids across twenty accounts, and nothing could then say whether they were the same thing.
         *
         * Random rather than derived from the name: a plan gets renamed, and an id that moved when
         * it did would orphan every user pointing at the old one.
         */
        fun newPlanId(): String =
            java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        /**
         * A new «قالب» id, minted on the device for exactly the reasons above.
         *
         * The same generator as [newPlanId] rather than a shared one under a vaguer name: the two
         * are the same today and there is no rule that they stay so, and a template id that
         * silently changed shape because a plan needed a different one is the kind of coupling
         * nobody looks for.
         */
        fun newTemplateId(): String = newPlanId()
    }

    private val index = StudioIndex.get(context)
    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    /** One sync at a time. Two overlapping pulls of the same shard race on its watermark. */
    private val syncLock = Mutex()

    /** When [autoProbeNodes] last ran. Process-lifetime, not persisted — see the constant. */
    private var lastAutoProbeAt = 0L

    /**
     * Internal rather than private so [StudioCombine] can reach one installation directly.
     *
     * It has to: a combine is a sequence of calls against ONE account — write the endpoint, list
     * that person's configs, point each at it — and routing them through the store's fleet-wide
     * helpers would fan a per-person action out across accounts that have nothing to do with it.
     */
    internal fun apiFor(account: CloudAccount): StudioApi =
        // An account's central Worker is answered from this phone (data/studio/central); an engine, over HTTP.
        if (com.mlmvpn.scanner.data.studio.central.Central.isCentral(account.id)) {
            com.mlmvpn.scanner.data.studio.central.StudioCentralApi(context, account)
        } else {
            StudioHttpApi(context, account)
        }

    /**
     * Accounts that actually carry an installation. The fleet is derived, never a second store: the
     * engines from the account records, and — after them — every account this phone runs the Studio
     * on through its central Worker, as an installation of its own (`<account>#gw`).
     */
    fun installedAccounts(): List<CloudAccount> {
        val all = CloudManager(context).accounts.toList()
        return all.filter { it.isStudioManaged } + com.mlmvpn.scanner.data.studio.central.CentralFleet.shards(context, all)
    }

    /** Whether an installation is an account's central Worker rather than an engine. */
    fun isCentral(installationId: String?): Boolean = com.mlmvpn.scanner.data.studio.central.Central.isCentral(installationId)

    // ------------------------------------------------------------------ health & sync

    /** Refresh what each installation says it is and can do. One request per installation. */
    suspend fun refreshInstallations(): List<StudioInstallation> = withContext(Dispatchers.IO) {
        val previous = _state.value.installations.associateBy { it.installationId }
        val out = mutableListOf<StudioInstallation>()
        // The central Worker side of the fleet (data/studio/central): an account on an engine gets its
        // central Worker beside it for new people, and an account another phone already runs the
        // Studio on is taken in (D8). Then each shard's Worker is brought in step — an ended term
        // taken off, ids another device dropped put back — before its health is read.
        val cloudAccounts = CloudManager(context).accounts.toList()
        runCatching { com.mlmvpn.scanner.data.studio.central.CentralFleet.adoptOrphans(context, cloudAccounts) }
        runCatching { com.mlmvpn.scanner.data.studio.central.CentralFleet.ensureForEngineAccounts(context, cloudAccounts) }
        runCatching { com.mlmvpn.scanner.data.studio.central.CentralFleet.discover(context, cloudAccounts) }
        // The check that ends a term while the app is closed, now that the set of accounts is known.
        com.mlmvpn.scanner.data.studio.central.StudioCentralJob.ensure(context)
        for (account in installedAccounts()) {
            // Carried across a refresh so a shard does not lose its user count — and therefore its
            // place in the capacity ordering — every time health is polled without the dashboard.
            val knownUsers = previous[account.id]?.users ?: -1
            val central = isCentral(account.id)
            val centralProblem = if (!central) null else {
                val r = com.mlmvpn.scanner.data.studio.central.CentralFleet.reconcile(context, account)
                when {
                    r == null -> "central_apply_failed"
                    r.unreadable -> com.mlmvpn.scanner.data.studio.central.CentralEngine.UNREADABLE
                    r.leftOut > 0 -> "central_full"
                    else -> null
                }
            }
            when (val res = apiFor(account).health()) {
                is StudioResult.Ok -> out.add(
                    res.value.copy(
                        lastSyncAt = index.lastSyncAt(account.id),
                        syncError = centralProblem ?: index.syncErrorOf(account.id),
                        // A central shard counts its people on this phone, exactly; an engine's
                        // count comes from its dashboard and is carried until that is read again.
                        users = if (central) res.value.users else knownUsers,
                        reachable = true,
                    )
                )
                is StudioResult.Err -> out.add(
                    StudioInstallation(
                        installationId = account.id,
                        cloudAccountId = account.accountId,
                        accountLabel = account.name.ifEmpty { account.email },
                        workerUrl = account.mlmWorkerUrl.orEmpty(),
                        apiRoute = account.studioApiRoute.orEmpty(),
                        lastSyncAt = index.lastSyncAt(account.id),
                        // Unreachable is a state to render, not an installation to drop from the
                        // list. An operator whose account vanished from their own fleet screen has
                        // a worse problem than one whose account is marked unreachable.
                        syncError = res.error.code,
                        users = knownUsers,
                        reachable = false,
                    )
                )
            }
        }
        _state.update { it.copy(installations = out) }
        out
    }

    /**
     * Where a new user should go.
     *
     * **Auto-placement is the default, not an option** (plan §A.4): nobody picks by hand out of
     * fifty accounts, so the automatic path is the primary one and the manual picker exists for the
     * operator who opens it. The rule, in order:
     *
     *  1. Skip anything unreachable, mid-migration, or on an engine too old for what the app is
     *     about to write — a user placed on a stale shard gets terms that shard cannot express.
     *  2. Among what is left, take the **emptiest**, because the binding ceiling is per account and
     *     spread is the only thing that raises it.
     *  3. A shard whose user count has never been read does not win by default. It sorts after every
     *     shard that has reported one, because "unknown" and "empty" are the same number and only
     *     one of them is a reason to send everyone there.
     *
     * Falls back to the first installed account when nothing qualifies, rather than refusing to
     * create anyone: a fleet where every shard is stale still has to be able to hand out a link, and
     * the screen says which account it used.
     */
    fun placementFor(installations: List<StudioInstallation> = _state.value.installations): CloudAccount? {
        // New people go to an account's central Worker (the operator's choice, 2026-10-18): an
        // engine keeps the people it already has, and nobody new is placed on one. Among the
        // central Workers, the one with the most room left wins — room is a hard number there.
        val accounts = installedAccounts().filter { isCentral(it.id) }
        if (accounts.isEmpty()) return null
        if (accounts.size == 1) return accounts.first()
        val healthy = installations.filter { it.reachable }.map { it.installationId }.toSet()
        return accounts
            .sortedWith(compareBy({ it.id !in healthy }, { -centralRoomLeft(it) }))
            .first()
    }

    /**
     * How many more people fit on this central Worker right now: its room (the Worker's cap less its
     * own id and the panels) less the people who may pass. From this phone's copies, no request.
     */
    fun centralRoomLeft(account: CloudAccount): Int {
        val doc = com.mlmvpn.scanner.data.studio.central.CentralFleet.doc(context, account.id) ?: return 0
        val room = com.mlmvpn.scanner.engines.gateway.EdgeGateway.status(context, account).studioRoom
        return (room - doc.liveCount(System.currentTimeMillis())).coerceAtLeast(0)
    }

    /**
     * Pull everything that changed on one installation, and apply it.
     *
     * Loops until the page comes back short, so a first sync of a large account completes rather
     * than leaving an index that silently holds the first two hundred users.
     */
    suspend fun sync(account: CloudAccount, maxPages: Int = 50): StudioResult<Int> = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val api = apiFor(account)
            var watermark = index.watermarkOf(account.id)
            var watermarkUid = index.watermarkUidOf(account.id)
            // Re-read the last two minutes every time (build 18). Usage now moves `updated_at` on
            // the engine every ~90 s, written by many isolates whose clocks and commits do not line
            // up with this device's watermark -- a flush that committed just after a sync read, with
            // a timestamp just before its watermark, would otherwise be skipped until that person's
            // row changed again. Re-applying a row is idempotent; missing one is what froze the list.
            if (watermark > SYNC_OVERLAP_MS) {
                watermark -= SYNC_OVERLAP_MS
                watermarkUid = ""
            }
            var applied = 0

            repeat(maxPages) {
                when (val res = api.syncUsers(watermark, watermarkUid)) {
                    is StudioResult.Err -> {
                        index.markSyncFailed(account.id, res.error.code)
                        refreshSyncState()
                        return@withContext res
                    }
                    is StudioResult.Ok -> {
                        val page = res.value
                        index.applySync(account.id, page.items, page.nextSince, page.nextUid)
                        applied += page.items.size

                        // A full page that moved neither half of the watermark means the engine is
                        // handing back the same rows: an installation on schema 9 or older, where
                        // `updated_at` alone cannot say where inside a group of tied rows the last
                        // page stopped. Asking again would loop until `maxPages` and re-apply the
                        // same page fifty times, so stop and say why — the fix is to update that
                        // installation's engine, which Settings already offers.
                        val stalled = !page.complete &&
                            page.nextSince == watermark && page.nextUid == watermarkUid
                        watermark = page.nextSince
                        watermarkUid = page.nextUid

                        if (page.complete) {
                            refreshSyncState()
                            return@withContext StudioResult.Ok(applied)
                        }
                        if (stalled) {
                            refreshSyncState()
                            return@withContext StudioResult.Err(
                                StudioError(
                                    "sync_incomplete",
                                    detail = "the engine paged without advancing; update it",
                                )
                            )
                        }
                    }
                }
            }
            // Ran out of pages rather than reaching the end. Reported rather than treated as done,
            // because a caller that believes the index is current will not come back for the rest.
            refreshSyncState()
            StudioResult.Err(StudioError("sync_incomplete", detail = "stopped after $maxPages pages"))
        }
    }

    /**
     * A copy of one person the caller just read from the engine, written into the local index.
     *
     * The user page re-reads its person on open and on pull-to-refresh; without this the list behind
     * it went on showing the older figure, so the two screens disagreed about the same person.
     */
    fun remember(installationId: String, user: StudioUser) {
        index.upsert(installationId, user)
    }

    suspend fun syncAll(): Int {
        var total = 0
        for (account in installedAccounts()) {
            (sync(account) as? StudioResult.Ok)?.let { total += it.value }
        }
        return total
    }

    private fun refreshSyncState() {
        val ids = installedAccounts().map { it.id }
        _state.update {
            it.copy(
                indexedUsers = index.count(),
                indexedActive = index.count(UserStatus.ACTIVE),
                oldestSyncAgeMs = index.oldestSyncAge(ids),
            )
        }
    }

    /**
     * Stop managing one installation from this device.
     *
     * **Nothing on Cloudflare is touched.** The worker keeps running, the database keeps its rows,
     * and every subscription link handed out from that shard keeps working — which is the only
     * behaviour that can be offered, because those links are in other people's hands and there is
     * no way to tell them. What this removes is the app's own key and its cached copy of that
     * shard's users, so the account stops appearing in the fleet.
     *
     * The Cloudflare account itself stays in «ابری». One account store, and Config Studio does not
     * get to delete a credential the rest of the app is using (D2).
     */
    suspend fun disconnect(installationId: String): Unit = withContext(Dispatchers.IO) {
        // A central Worker shard: this phone stops running the Studio on it. The Worker keeps its ids
        // and the account keeps its list, so every link handed out keeps working, and the next phone
        // (or this one, added again) finds the same people.
        if (isCentral(installationId)) {
            com.mlmvpn.scanner.data.studio.central.CentralFleet.unregister(context, installationId)
            index.forgetInstallation(installationId)
            refreshInstallations()
            refreshSyncState()
            return@withContext
        }
        val cloud = CloudManager(context)
        cloud.accounts.firstOrNull { it.id == installationId }?.let { account ->
            account.studioApiRoute = null
            account.studioKeyId = null
            account.studioApiSecret = null
            account.studioKeyLabel = null
            account.studioStatus = "idle"
            account.studioVersion = 0
            account.studioAdopted = false
            cloud.saveAccounts()
        }
        // The index rows go too, and they must: leaving them behind would show users from an
        // account this device can no longer read, act on, or refresh — a list that is wrong and
        // has no way to become right.
        index.forgetInstallation(installationId)
        refreshInstallations()
        refreshSyncState()
    }

    // ------------------------------------------------------------------ reads (the index)

    fun page(
        limit: Int,
        offset: Int = 0,
        query: String? = null,
        status: UserStatus? = null,
        /** Null is the whole fleet. One indexed query either way — the cost does not move with N. */
        installationId: String? = null,
        filter: UserFilter = UserFilter.ALL,
        sort: UserSort = UserSort.NEWEST,
        tag: String? = null,
    ): List<StudioUser> = index.page(limit, offset, query, status, installationId, filter, sort, tag)

    /** How many rows the same narrowing matches. Paged from [page]; counted here. */
    fun countMatching(
        query: String? = null,
        status: UserStatus? = null,
        installationId: String? = null,
        filter: UserFilter = UserFilter.ALL,
        tag: String? = null,
    ): Int = index.countMatching(query, status, installationId, filter, tag)

    /** Every tag in use across the fleet, most-used first. Drives the filter row, nothing else. */
    fun tagsInUse(): List<String> = index.allTags()

    fun cached(installationId: String, userId: String): StudioUser? = index.find(installationId, userId)

    /**
     * Whether this name is taken anywhere in the fleet.
     *
     * Answered from the index, which is the only version of this check that survives an uncapped
     * fleet -- asking every installation would be one request per account per keystroke. It is an
     * advisory answer: the target installation's `UNIQUE(username)` is the actual authority and
     * returns `username_taken` if the index was stale.
     */
    fun usernameTaken(username: String): Boolean = index.usernameExists(username)

    /**
     * The same question for a whole batch, in one query.
     *
     * Bulk creation asks this on every keystroke over as many as five hundred names; five hundred
     * point lookups on the main thread, sixty times a minute, is a list that stutters while it is
     * being typed into.
     */
    fun usernamesTaken(names: List<String>): Set<String> = index.takenAmong(names)

    // ------------------------------------------------------------------ writes (fresh, always)

    /**
     * Re-read a user from its own installation, then act on what came back.
     *
     * **This is the rule that makes a stale index safe**, and it is why it lives in one function
     * rather than being remembered at each call site. The index is minutes old by design; acting on
     * minutes-old figures is exactly the failure the plan warned about when it said not to build a
     * mirror at all. So: browse from the index, and re-read before every mutation. One request, tens
     * of milliseconds, and the write is never based on data the operator saw a while ago.
     *
     * Reviewer's grep: no `StudioUser` taken from [page] or [cached] should reach a mutating call.
     * They go through here, by id.
     */
    suspend fun <T> withFreshUser(
        account: CloudAccount,
        userId: String,
        block: suspend (StudioApi, StudioUser) -> StudioResult<T>,
    ): StudioResult<T> = withContext(Dispatchers.IO) {
        val api = apiFor(account)
        when (val fresh = api.getUser(userId)) {
            is StudioResult.Err -> fresh
            is StudioResult.Ok -> {
                // Keep the index honest about what we just learned, whatever the write then does.
                index.upsert(account.id, fresh.value)
                block(api, fresh.value)
            }
        }
    }

    suspend fun createUser(
        account: CloudAccount,
        username: String,
        policy: com.mlmvpn.scanner.data.studio.domain.SubscriptionPolicy,
        planId: String? = null,
    ): StudioResult<StudioUser> = withContext(Dispatchers.IO) {
        val res = apiFor(account).createUser(username, policy, planId = planId)
        if (res is StudioResult.Ok) {
            index.upsert(account.id, res.value)
            refreshSyncState()
        }
        res
    }

    suspend fun renew(
        account: CloudAccount,
        userId: String,
        addDays: Int? = null,
        addBytes: Long? = null,
        mode: RenewMode = RenewMode.EXTEND,
        planId: String? = null,
    ): StudioResult<StudioUser> = withFreshUser(account, userId) { api, _ ->
        api.renewUser(userId, planId, addDays, addBytes, mode).also {
            if (it is StudioResult.Ok) index.upsert(account.id, it.value)
        }
    }

    /**
     * What the operator knows about this person, as opposed to what they are allowed.
     *
     * A separate call from a general patch so the index update below is unconditional: tags drive
     * the list's filter row, and a tag written to the engine but not to the index would be a chip
     * that appears at the next sync and not before — which reads as the save having failed.
     *
     * `note` is sent as an empty string rather than omitted when it is cleared. Omitting it would
     * leave the old note in place, so the one edit that cannot be made would be undoing one.
     */
    suspend fun setLabels(
        account: CloudAccount,
        userId: String,
        note: String,
        tags: List<String>,
    ): StudioResult<StudioUser> = withFreshUser(account, userId) { api, _ ->
        api.patchUser(userId, note = note, tags = tags).also {
            if (it is StudioResult.Ok) index.upsert(account.id, it.value)
        }
    }

    suspend fun setEnabled(account: CloudAccount, userId: String, enabled: Boolean): StudioResult<StudioUser> =
        withFreshUser(account, userId) { api, _ ->
            api.setEnabled(userId, enabled).also {
                if (it is StudioResult.Ok) {
                    index.upsert(account.id, it.value)
                    refreshSyncState()
                }
            }
        }

    /**
     * Whether this person's device, connection and IP caps are applied or merely recorded.
     *
     * A per-user switch rather than a per-installation one, because it answers a question about a
     * person: some are worth watching before they are worth cutting off. It re-reads first like
     * every other write here, and sends **only** the policy field it is changing — a PATCH carrying
     * the whole policy would overwrite whatever another device had changed since this screen loaded.
     */
    suspend fun setEnforcement(
        account: CloudAccount,
        userId: String,
        strict: Boolean,
    ): StudioResult<StudioUser> = withFreshUser(account, userId) { api, fresh ->
        api.patchUser(
            userId,
            policy = fresh.policy.copy(
                enforcement = if (strict) Enforcement.STRICT else Enforcement.SOFT,
            ),
        ).also { if (it is StudioResult.Ok) index.upsert(account.id, it.value) }
    }

    suspend fun deleteUser(account: CloudAccount, userId: String): StudioResult<Unit> =
        withFreshUser(account, userId) { api, _ ->
            api.deleteUser(userId).also {
                if (it is StudioResult.Ok) {
                    index.remove(account.id, userId)
                    refreshSyncState()
                }
            }
        }

    // ------------------------------------------------------------------ the central Worker (data/studio/central)

    private fun centralApi(account: CloudAccount): com.mlmvpn.scanner.data.studio.central.StudioCentralApi? =
        apiFor(account) as? com.mlmvpn.scanner.data.studio.central.StudioCentralApi

    private fun notCentral(): StudioResult.Err =
        StudioResult.Err(StudioError(com.mlmvpn.scanner.data.studio.central.StudioCentralApi.NOT_SUPPORTED))

    /** «لینک تازه» for one person on a central Worker: a new id; every device on the old one is cut off. */
    suspend fun centralRotate(account: CloudAccount, userId: String): StudioResult<StudioUser> = withContext(Dispatchers.IO) {
        val api = centralApi(account) ?: return@withContext notCentral()
        api.rotateLink(userId).also { if (it is StudioResult.Ok) index.upsert(account.id, it.value) }
    }

    /** One person's links on a central Worker, built on this phone. */
    suspend fun centralLinks(account: CloudAccount, userId: String, refresh: Boolean = false): StudioResult<List<String>> =
        withContext(Dispatchers.IO) { centralApi(account)?.links(userId, refresh) ?: notCentral() }

    /** Bring one central Worker in step now, and re-read its people. */
    suspend fun centralApplyNow(account: CloudAccount): StudioResult<com.mlmvpn.scanner.data.studio.central.Reconciled> =
        withContext(Dispatchers.IO) {
            val api = centralApi(account) ?: return@withContext notCentral()
            api.applyNow().also { sync(account); refreshSyncState() }
        }

    /**
     * Move a person from an engine installation onto a central Worker ([to]): the same name, end,
     * note and tags, and a new link. The engine keeps them as they were — their old link works until
     * its own end, or until the operator switches it off there — so nothing anyone holds stops by
     * surprise. Volume and device limits do not move: the central Worker has neither.
     */
    suspend fun moveToCentral(from: CloudAccount, user: StudioUser, to: CloudAccount): StudioResult<StudioUser> =
        withContext(Dispatchers.IO) {
            val api = centralApi(to) ?: return@withContext notCentral()
            api.createMoved(user.username, user.policy.expiresAt ?: 0L, user.note, user.tags, "${from.id}|${user.id}").also {
                if (it is StudioResult.Ok) {
                    index.upsert(to.id, it.value)
                    refreshSyncState()
                }
            }
        }

    /** Many people on one central Worker in one upload («ساخت انبوه»). [days] null or 0 = no end. */
    suspend fun centralCreateMany(
        account: CloudAccount,
        names: List<String>,
        days: Int?,
        planId: String?,
    ): com.mlmvpn.scanner.data.studio.central.CentralFleet.CreateOutcome = withContext(Dispatchers.IO) {
        val expiresAt = days?.takeIf { it > 0 }
            ?.let { System.currentTimeMillis() + it * com.mlmvpn.scanner.data.studio.central.Central.DAY_MS } ?: 0L
        com.mlmvpn.scanner.data.studio.central.CentralFleet.createMany(context, account, names, expiresAt, planId).also {
            sync(account)
            refreshSyncState()
        }
    }

    /** Where a person on [account]'s engine moves to: its own central Worker when it has room, else the roomiest. */
    fun centralTargetFor(account: CloudAccount): CloudAccount? {
        val shards = installedAccounts().filter { isCentral(it.id) }
        val own = shards.firstOrNull { com.mlmvpn.scanner.data.studio.central.Central.accountIdOf(it.id) == account.id }
        if (own != null && centralRoomLeft(own) > 0) return own
        return shards.filter { centralRoomLeft(it) > 0 }.maxByOrNull { centralRoomLeft(it) } ?: own
    }

    // ------------------------------------------------------------------ bulk (see StudioBulk.kt)

    /**
     * Run [op] over a selection, one person at a time, and report per person.
     *
     * The three properties `StudioBulk.kt` argues for live here: it never stops at the first
     * failure, it is sequential rather than concurrent, and it names the people it could not reach.
     *
     * [onProgress] is called with how many are finished, on the caller's dispatcher’s behalf — a
     * bulk run over a hundred people on a mobile connection is a minute of screen with nothing on
     * it otherwise, and the operator has no way to tell that from a hang.
     *
     * A ref whose installation is no longer connected is a **failure, not a skip**: the account was
     * disconnected while the selection was open, and quietly dropping those people would report a
     * run as complete that touched fewer than were chosen.
     */
    private suspend fun bulkApply(
        targets: List<UserRef>,
        onProgress: (done: Int, total: Int) -> Unit,
        /**
         * The same change for a person on an account's central Worker, as a rule on its record. All
         * of a selection's people on one account then go up in ONE upload — a hundred uploads for a
         * hundred people would be minutes and a hundred writes to the account's record.
         */
        central: ((com.mlmvpn.scanner.data.studio.central.CentralDoc, UserRef, Int, Long) -> com.mlmvpn.scanner.data.studio.central.CentralDoc)? = null,
        op: suspend (StudioApi, CloudAccount, UserRef) -> StudioResult<*>,
    ): BulkResult = withContext(Dispatchers.IO) {
        val accounts = installedAccounts().associateBy { it.id }
        // One API object per account rather than per person: it is cheap, but building a hundred of
        // them for a hundred people on one shard is a hundred round trips through CloudManager.
        val apis = HashMap<String, StudioApi>()
        var ok = 0
        var done = 0
        val failures = mutableListOf<BulkFailure>()

        val batched = if (central == null) emptyList() else targets.filter { isCentral(it.installationId) }
        for ((installationId, refs) in batched.groupBy { it.installationId }) {
            val account = accounts[installationId]
            if (account == null) {
                refs.forEach { failures.add(BulkFailure(it.username, StudioError.NETWORK)) }
            } else {
                val outcome = com.mlmvpn.scanner.data.studio.central.CentralFleet.batch(context, account, refs, central!!)
                ok += outcome.done
                outcome.failures.forEach { (ref, code) -> failures.add(BulkFailure(ref.username, code)) }
                // The index learns the new state from this phone's copy, tombstones and all.
                sync(account)
            }
            done += refs.size
            onProgress(done, targets.size)
        }

        val batchedKeys = batched.map { it.key }.toHashSet()
        targets.filter { it.key !in batchedKeys }.forEach { ref ->
            val account = accounts[ref.installationId]
            if (account == null) {
                failures.add(BulkFailure(ref.username, StudioError.NETWORK))
            } else {
                val api = apis.getOrPut(ref.installationId) { apiFor(account) }
                when (val res = op(api, account, ref)) {
                    is StudioResult.Ok -> ok++
                    is StudioResult.Err -> failures.add(BulkFailure(ref.username, res.error.code))
                }
            }
            done++
            onProgress(done, targets.size)
        }

        refreshSyncState()
        BulkResult(ok, failures)
    }

    /** Switch a selection on or off. Addressed by id, so nothing here reads a client-side figure. */
    suspend fun bulkSetEnabled(
        targets: List<UserRef>,
        enabled: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkResult = bulkApply(
        targets, onProgress,
        central = { d, ref, room, now -> com.mlmvpn.scanner.data.studio.central.CentralRules.setEnabled(d, ref.userId, enabled, now, room).first },
    ) { api, account, ref ->
        api.setEnabled(ref.userId, enabled).also {
            if (it is StudioResult.Ok) index.upsert(account.id, it.value)
        }
    }

    /**
     * Give or take back days and volume across a selection.
     *
     * The arithmetic happens on the engine, from its own row — which is why this needs no re-read
     * and why a stale index cannot make it wrong. Needs `renew.adjust` on each installation when
     * anything here is negative or the mode is not `EXTEND`; the screen is what checks that, because
     * only it can say which accounts the selection actually spans.
     */
    suspend fun bulkRenew(
        targets: List<UserRef>,
        addDays: Int? = null,
        addBytes: Long? = null,
        mode: RenewMode = RenewMode.EXTEND,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkResult = bulkApply(
        targets, onProgress,
        central = { d, ref, room, now ->
            // Volume is not counted on the central Worker: a renewal that is only volume is refused.
            if ((addDays == null || addDays == 0) && addBytes != null && mode == RenewMode.EXTEND) {
                throw com.mlmvpn.scanner.data.studio.central.CentralRules.RuleError(com.mlmvpn.scanner.data.studio.central.StudioCentralApi.NOT_SUPPORTED)
            }
            com.mlmvpn.scanner.data.studio.central.CentralRules.renew(d, ref.userId, addDays, mode, now, room).first
        },
    ) { api, account, ref ->
        api.renewUser(ref.userId, null, addDays, addBytes, mode).also {
            if (it is StudioResult.Ok) index.upsert(account.id, it.value)
        }
    }

    /**
     * Change the three caps across a selection.
     *
     * Each is null-or-set independently and **a null means "leave it alone", not "no limit"** — a
     * screen that offered three boxes and wrote every one of them would clear two caps every time
     * the operator meant to change one. Clearing a cap is [CLEAR_CAP], a value the field can hold.
     *
     * This one DOES re-read, and it is the exception that proves the rule above: the policy is
     * PATCHed as an object, so the fields not being changed have to be the ones the engine currently
     * holds. Sending them from the index would push minutes-old caps back over anything another
     * device had changed.
     */
    suspend fun bulkSetLimits(
        targets: List<UserRef>,
        deviceLimit: Int? = null,
        connLimit: Int? = null,
        ipLimit: Int? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkResult = bulkApply(
        targets, onProgress,
        // No device, connection or IP counter on the central Worker: every such person is named.
        central = { _, _, _, _ -> throw com.mlmvpn.scanner.data.studio.central.CentralRules.RuleError(com.mlmvpn.scanner.data.studio.central.StudioCentralApi.NOT_SUPPORTED) },
    ) { api, account, ref ->
        when (val fresh = api.getUser(ref.userId)) {
            is StudioResult.Err -> fresh
            is StudioResult.Ok -> {
                val newDevices = resolveCap(deviceLimit, fresh.value.policy.deviceLimit)
                api.patchUser(
                    ref.userId,
                    policy = fresh.value.policy.copy(
                        deviceLimit = newDevices,
                        connLimit = resolveCap(connLimit, fresh.value.policy.connLimit),
                        ipLimit = resolveCap(ipLimit, fresh.value.policy.ipLimit),
                        // A device limit typed here is one that applies (build 18); monitor-only
                        // stays whatever the person already had when the limit is not being changed.
                        enforcement = if (deviceLimit != null && newDevices != null) Enforcement.STRICT
                        else fresh.value.policy.enforcement,
                    ),
                    // "0 = remove the cap" really removes it. The policy body leaves nulls out so a
                    // PATCH stays partial, which is why a cleared cap used to stay exactly as it was.
                    clearPolicyFields = buildSet {
                        if (deviceLimit == CLEAR_CAP) add("device_limit")
                        if (connLimit == CLEAR_CAP) add("conn_limit")
                        if (ipLimit == CLEAR_CAP) add("ip_limit")
                    },
                ).also { if (it is StudioResult.Ok) index.upsert(account.id, it.value) }
            }
        }
    }

    /**
     * One cap, after the three-way choice above.
     *
     * Null in, null out means "not being changed"; [CLEAR_CAP] means the operator asked for no
     * limit at all. Two different intentions that both want to end as `null` on the wire, which is
     * the whole reason the sentinel exists.
     */
    private fun resolveCap(requested: Int?, current: Int?): Int? = when {
        requested == null -> current
        requested == CLEAR_CAP -> null
        else -> requested
    }

    /** Delete a selection. Tombstones, like the single-user path — the name is released, the row is not. */
    suspend fun bulkDelete(
        targets: List<UserRef>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkResult = bulkApply(
        targets, onProgress,
        central = { d, ref, _, now -> com.mlmvpn.scanner.data.studio.central.CentralRules.delete(d, ref.userId, now) },
    ) { api, account, ref ->
        api.deleteUser(ref.userId).also {
            if (it is StudioResult.Ok) index.remove(account.id, ref.userId)
        }
    }

    /**
     * Collect the subscription link of everyone in a selection.
     *
     * One request per person and no way around it: a link is a token minted by the engine, not
     * anything the app can derive from a username. Someone with no subscription yet comes back with
     * [BulkLink.error] set rather than being dropped — an export of nineteen links and one missing
     * row is a file that looks complete and is not.
     */
    suspend fun bulkLinks(
        targets: List<UserRef>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<BulkLink> = withContext(Dispatchers.IO) {
        val accounts = installedAccounts().associateBy { it.id }
        val apis = HashMap<String, StudioApi>()
        val out = mutableListOf<BulkLink>()
        targets.forEachIndexed { i, ref ->
            val account = accounts[ref.installationId]
            if (account == null) {
                out.add(BulkLink(ref.username, null, StudioError.NETWORK))
            } else if (isCentral(ref.installationId)) {
                // A central-Worker person has no subscription link: their own configs, on one line.
                val gw = com.mlmvpn.scanner.data.studio.central.CentralFleet.doc(context, ref.installationId)
                    ?.user(ref.userId)?.takeIf { !it.isDeleted }
                if (gw == null) out.add(BulkLink(ref.username, null, StudioError.NOT_FOUND))
                else com.mlmvpn.scanner.engines.gateway.EdgeGateway.studioLinks(context, account, gw.gw, gw.name).fold(
                    onSuccess = { out.add(BulkLink(ref.username, it.configs.joinToString(" "))) },
                    onFailure = { out.add(BulkLink(ref.username, null, StudioError.NETWORK)) },
                )
            } else {
                val api = apis.getOrPut(ref.installationId) { apiFor(account) }
                when (val sub = api.getSubscription(ref.userId)) {
                    is StudioResult.Ok -> out.add(BulkLink(ref.username, sub.value.url))
                    is StudioResult.Err -> out.add(BulkLink(ref.username, null, sub.error.code))
                }
            }
            onProgress(i + 1, targets.size)
        }
        out
    }

    // ------------------------------------------------------------------ «قالب»

    /**
     * Read the templates off every installation and merge them into one list.
     *
     * The same merge [refreshPlans] does, for the same reason: one template is written to every
     * account under the same id, so two accounts reporting `t-ws` are reporting the same shape.
     * Newest wins for display and [ConfigTemplate.presentOn] records which accounts actually carry
     * it — a template missing from one account is a config built there that quietly gets a
     * different shape, and it is invisible unless something goes looking (R14).
     *
     * An installation too old to know the endpoint is **skipped rather than counted as a failure**:
     * an engine on build 8 answering 404 for `/v1/templates` is out of date, not unreachable, and
     * listing it under "could not be read" sends the operator to check a network that is fine. The
     * settings screen already offers the update.
     */
    suspend fun refreshTemplates(): List<ConfigTemplate> = withContext(Dispatchers.IO) {
        val merged = LinkedHashMap<String, ConfigTemplate>()
        val failures = mutableListOf<String>()
        val answered = mutableSetOf<String>()
        val known = _state.value.installations.associateBy { it.installationId }

        for (account in installedAccounts()) {
            val label = account.name.ifEmpty { account.email }
            if (known[account.id]?.can("templates.v1") == false) continue
            when (val res = apiFor(account).listTemplates()) {
                is StudioResult.Err ->
                    if (res.error.code != "not_found") failures.add(label)
                is StudioResult.Ok -> {
                    answered.add(account.id)
                    for (t in res.value) {
                        val existing = merged[t.id]
                        val winner = when {
                            existing == null -> t
                            t.updatedAt > existing.updatedAt -> t
                            else -> existing
                        }
                        merged[t.id] = winner.copy(presentOn = existing?.presentOn.orEmpty() + account.id)
                    }
                }
            }
        }

        val out = merged.values.sortedWith(
            compareByDescending<ConfigTemplate> { it.isDefault }.thenBy { it.name }
        )
        _state.update {
            it.copy(
                templates = out,
                templatesAnsweredBy = answered,
                templatesUnreachable = failures,
            )
        }
        out
    }

    /**
     * Write one template to **every** installation.
     *
     * Same shape as [savePlan] and same argument: a template the operator can pick when creating a
     * config has to exist wherever that person landed. `PUT` on a caller-chosen id, so a partial
     * failure is retried by running it again rather than by working out what got through.
     */
    suspend fun saveTemplate(template: ConfigTemplate): PlanWriteResult = withContext(Dispatchers.IO) {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, StudioError>>()
        val known = _state.value.installations.associateBy { it.installationId }
        for (account in installedAccounts()) {
            val label = account.name.ifEmpty { account.email }
            // An engine that has no templates endpoint would fail every write and make the result
            // read as broken. It is reported as its own kind of problem by the settings screen.
            if (known[account.id]?.can("templates.v1") == false) continue
            when (val res = apiFor(account).putTemplate(template)) {
                is StudioResult.Ok -> ok.add(account.id)
                is StudioResult.Err -> failed.add(label to res.error)
            }
        }
        refreshTemplates()
        PlanWriteResult(ok, failed)
    }

    /** Delete one template everywhere. Already gone from an account is the asked-for outcome. */
    suspend fun deleteTemplate(id: String): PlanWriteResult = withContext(Dispatchers.IO) {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, StudioError>>()
        val known = _state.value.installations.associateBy { it.installationId }
        for (account in installedAccounts()) {
            val label = account.name.ifEmpty { account.email }
            if (known[account.id]?.can("templates.v1") == false) continue
            when (val res = apiFor(account).deleteTemplate(id)) {
                is StudioResult.Ok -> ok.add(account.id)
                is StudioResult.Err ->
                    if (res.error.code == "not_found") ok.add(account.id)
                    else failed.add(label to res.error)
            }
        }
        refreshTemplates()
        PlanWriteResult(ok, failed)
    }

    /**
     * Give everyone in a selection a config built from [template].
     *
     * **Additive, never a replacement.** Re-shaping somebody’s existing config means re-issuing
     * its credential, which cuts off the link they are using right now — that is `rotate`, it is
     * per person on purpose, and doing it to two hundred people from one button is not a thing this
     * screen should be able to do by accident. Adding a config leaves every existing link working
     * and simply puts another server in their subscription.
     *
     * [buildFor] renders the URI template on the app side, because the worker builds no URIs and
     * refuses a config without one — the property that keeps the link handed out and the outbound
     * dialled from being built by two different pieces of code.
     */
    suspend fun bulkAddConfig(
        targets: List<UserRef>,
        template: ConfigTemplate,
        buildFor: (StudioUser) -> ConfigPayload,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkResult = bulkApply(targets, onProgress) { api, _, ref ->
        when (val fresh = api.getUser(ref.userId)) {
            is StudioResult.Err -> fresh
            is StudioResult.Ok -> {
                val payload = buildFor(fresh.value)
                api.createConfig(
                    userId = ref.userId,
                    uriTemplate = payload.uriTemplate,
                    label = template.name.take(60),
                    protocol = payload.protocol,
                    transportType = payload.transportType,
                    credential = payload.credential,
                    routeKey = payload.routeKey,
                    authHash = payload.authHash,
                    nodeIds = template.nodeIds,
                )
            }
        }
    }

    // ------------------------------------------------------------------ «بسته»

    /**
     * Read the plans off every installation and merge them into one list.
     *
     * Merged by id, because that is what the id is for: the same plan is written to every account
     * under the same id, so two accounts reporting `p30` are reporting the same «بسته». When their
     * contents disagree — one account missed a write, or was unreachable when the edit went out —
     * the **newest** wins for display, and [Plan.presentOn] records exactly which accounts actually
     * carry it.
     *
     * That set is the whole point. A plan quietly missing from one account means a user created
     * there gets terms nobody chose, and it is invisible unless something goes looking. Shown as
     * drift, it is one line on a screen and one tap to fix (plan R14).
     */
    suspend fun refreshPlans(): List<Plan> = withContext(Dispatchers.IO) {
        val merged = LinkedHashMap<String, Plan>()
        val labels = LinkedHashMap<String, String>()
        val failures = mutableListOf<String>()
        // Which accounts actually answered. Drift can only be measured against these: an account
        // that did not reply has plans nobody has seen, and calling that "missing" sends the
        // operator to fix something that may not be broken.
        val answered = mutableSetOf<String>()

        for (account in installedAccounts()) {
            labels[account.id] = account.name.ifEmpty { account.email }
            when (val res = apiFor(account).listPlans()) {
                is StudioResult.Err -> failures.add(labels[account.id]!!)
                is StudioResult.Ok -> {
                    answered.add(account.id)
                    for (plan in res.value) {
                        val existing = merged[plan.id]
                        val winner = when {
                            existing == null -> plan
                            plan.updatedAt > existing.updatedAt -> plan
                            else -> existing
                        }
                        merged[plan.id] = winner.copy(
                            presentOn = existing?.presentOn.orEmpty() + account.id
                        )
                    }
                }
            }
        }

        val out = merged.values.sortedWith(compareByDescending<Plan> { it.isDefault }.thenBy { it.name })
        _state.update {
            it.copy(
                plans = out,
                fleetLabels = labels,
                plansAnsweredBy = answered,
                plansUnreachable = failures,
            )
        }
        out
    }

    /**
     * Write one plan to **every** installation.
     *
     * Not to the "current" one, because there is no such thing: a plan the operator can pick when
     * creating a user has to exist wherever that user might land (§A.4). Every write is a `PUT` on
     * the same id, so this is safe to run again after a partial failure — which is what the returned
     * failure list is for. It reports the accounts that did not take the write rather than throwing
     * the whole operation away, because nineteen of twenty succeeding is a different situation from
     * none of them succeeding, and only one of the two needs the operator to do anything.
     */
    suspend fun savePlan(plan: Plan): PlanWriteResult = withContext(Dispatchers.IO) {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, StudioError>>()
        for (account in installedAccounts()) {
            val label = account.name.ifEmpty { account.email }
            when (val res = apiFor(account).putPlan(plan)) {
                is StudioResult.Ok -> ok.add(account.id)
                is StudioResult.Err -> failed.add(label to res.error)
            }
        }
        refreshPlans()
        PlanWriteResult(ok, failed)
    }

    suspend fun archivePlan(id: String): PlanWriteResult = withContext(Dispatchers.IO) {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, StudioError>>()
        for (account in installedAccounts()) {
            val label = account.name.ifEmpty { account.email }
            when (val res = apiFor(account).archivePlan(id)) {
                is StudioResult.Ok -> ok.add(account.id)
                // Already gone from this account is the outcome that was asked for, not a failure.
                is StudioResult.Err ->
                    if (res.error.code == "not_found") ok.add(account.id)
                    else failed.add(label to res.error)
            }
        }
        refreshPlans()
        PlanWriteResult(ok, failed)
    }

    /**
     * How many people across the fleet are on this plan.
     *
     * Asked of every installation and summed, rather than answered from the local index. The index
     * would be cheaper and is wrong here for a specific reason: this number is shown immediately
     * before a bulk write and is used to decide whether to make it, and the index is minutes old by
     * design. Same rule as [withFreshUser] — browse from the index, act on what the engine says.
     */
    suspend fun countPlanUsers(planId: String): Int = withContext(Dispatchers.IO) {
        var total = 0
        for (account in installedAccounts()) {
            (apiFor(account).countPlanUsers(planId) as? StudioResult.Ok)?.let { total += it.value }
        }
        total
    }

    /**
     * Push a plan's terms onto the people already on it, across the fleet.
     *
     * @return how many users were written, summed. The number matters: this is a bulk write against
     *   a 100k-row-per-day D1 budget, and the operator is entitled to see what it cost.
     */
    suspend fun applyPlan(id: String, fields: List<String> = emptyList()): PlanApplyResult =
        withContext(Dispatchers.IO) {
            var users = 0
            val failed = mutableListOf<Pair<String, StudioError>>()
            for (account in installedAccounts()) {
                when (val res = apiFor(account).applyPlan(id, fields)) {
                    is StudioResult.Ok -> users += res.value
                    is StudioResult.Err -> failed.add((account.name.ifEmpty { account.email }) to res.error)
                }
            }
            // The index now disagrees with every installation it wrote to, so pull it back in step
            // rather than leaving the list showing the caps these users had a moment ago.
            syncAll()
            PlanApplyResult(users, failed)
        }

    // ------------------------------------------------------------------ dashboard

    /**
     * The fleet's dashboard: one request per installation, summed here.
     *
     * Summed rather than merged: the tiles are counts and byte totals, so adding them is the correct
     * aggregate. `enforcement` is the exception -- it reports STRICT only when **every** installation
     * enforces, because a fleet where one shard cannot apply caps does not apply caps.
     */
    // ------------------------------------------------------------------ activity

    /**
     * The most recent things the fleet's engines observed, newest first.
     *
     * A small page from each installation, merged — not a paged walk. The dashboard shows a handful
     * of lines, and asking each account for fifty rows to display five is one request per account
     * per open on a network where that is the expensive part.
     *
     * An installation too old to answer is skipped in silence rather than reported: this is a
     * diagnostic strip, and «این حساب سابقه ندارد» on a dashboard is noise about the app rather
     * than information about the fleet. The engine-update prompt already says what to do.
     */
    suspend fun refreshActivity(
        limit: Int = 8,
        /**
         * Narrow the feed to failures only.
         *
         * The default keeps warnings, which is right for the dashboard: a refusal for a spent quota
         * is what an operator most often needs to see and it is a warning, not an error. The
         * narrower reading exists because on an account with many expired subscribers the warnings
         * are a steady background that buries the one row that is actually broken.
         */
        errorsOnly: Boolean = false,
    ): List<ActivityEntry> = withContext(Dispatchers.IO) {
        val merged = mutableListOf<ActivityEntry>()
        for (account in installedAccounts()) {
            val inst = _state.value.installations.firstOrNull { it.installationId == account.id }
            if (inst != null && !inst.can("activity.v1")) continue
            val res = apiFor(account).activity(
                severity = if (errorsOnly) "error" else "warn", limit = limit,
            )
            if (res is StudioResult.Ok) merged.addAll(res.value.items)
        }
        // By timestamp, because ids are per installation and comparing them across accounts would
        // interleave two unrelated sequences into an order that means nothing.
        val out = merged.sortedByDescending { it.ts }.take(limit)
        _state.update { it.copy(activity = out) }
        out
    }

    // ------------------------------------------------------------------ nodes

    /**
     * Every installation's endpoints, merged, each carrying the account it lives on.
     *
     * Merged rather than summed, and NOT deduplicated by host: the same clean IP written to three
     * accounts is three rows here on purpose. Each is served by a different worker, each can fail on
     * its own, and collapsing them would leave the operator unable to tell which account is the one
     * whose node stopped answering.
     */
    suspend fun refreshNodes(): List<StudioNode> = withContext(Dispatchers.IO) {
        val out = mutableListOf<StudioNode>()
        val labels = LinkedHashMap<String, String>()
        val failures = mutableListOf<String>()

        for (account in installedAccounts()) {
            val label = account.name.ifEmpty { account.email }
            labels[account.id] = label
            when (val res = apiFor(account).listNodes()) {
                is StudioResult.Err -> failures.add(label)
                is StudioResult.Ok -> out.addAll(res.value)
            }
        }

        val sorted = out.sortedWith(compareBy<StudioNode> { it.priority }.thenBy { it.name })
        _state.update { it.copy(nodes = sorted, nodesUnreachable = failures, fleetLabels = labels) }
        sorted
    }

    /** The group an endpoint belongs to, or null. Resolved per installation, never by id alone. */
    fun groupOf(node: StudioNode): NodeGroup? = node.groupId?.let { id ->
        _state.value.nodeGroups.firstOrNull {
            it.id == id && it.installationId == node.installationId
        }
    }

    /**
     * Read every installation's endpoint groups.
     *
     * Not merged by id across accounts, unlike plans and templates, and the difference is the point:
     * a group orders the endpoints of the installation it lives on, and two accounts have different
     * endpoints. Two rows with the same id are two groups here, and a drift line about them would be
     * a warning about a difference that is meant to exist.
     */
    suspend fun refreshNodeGroups(): List<NodeGroup> = withContext(Dispatchers.IO) {
        val out = mutableListOf<NodeGroup>()
        val stale = mutableListOf<String>()
        val known = _state.value.installations.associateBy { it.installationId }

        for (account in installedAccounts()) {
            val label = account.name.ifEmpty { account.email }
            // A central Worker has no endpoint groups to read — its own settings decide its addresses.
            if (isCentral(account.id)) continue
            // Too old to have the endpoint at all. Reported as an update rather than as a failure,
            // because "could not be read" sends the operator to check a network that is fine.
            if (known[account.id]?.can("node.groups") == false) { stale.add(label); continue }
            when (val res = apiFor(account).listNodeGroups()) {
                is StudioResult.Err -> if (res.error.code == "not_found") stale.add(label)
                is StudioResult.Ok -> out.addAll(res.value)
            }
        }

        val sorted = out.sortedWith(compareBy<NodeGroup> { it.priority }.thenBy { it.name })
        _state.update { it.copy(nodeGroups = sorted, nodeGroupsStale = stale) }
        sorted
    }

    /**
     * Write one group to the installation it belongs to, or — for a new one — to every installation.
     *
     * The same split [saveNode] makes and for the same reason: an edit addresses the account that
     * holds the row, while a NEW group has no account yet and "everywhere" is what an operator means
     * by adding one. Both are `PUT`s on a client-chosen id, so a partial failure is retried by
     * running it again.
     */
    suspend fun saveNodeGroup(group: NodeGroup, toWholeFleet: Boolean): PlanWriteResult =
        withContext(Dispatchers.IO) {
            val ok = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, StudioError>>()
            val known = _state.value.installations.associateBy { it.installationId }
            val targets = (if (toWholeFleet) installedAccounts()
            else installedAccounts().filter { it.id == group.installationId }).filter { !isCentral(it.id) }

            for (account in targets) {
                if (known[account.id]?.can("node.groups") == false) continue
                val label = account.name.ifEmpty { account.email }
                when (val res = apiFor(account).putNodeGroup(group)) {
                    is StudioResult.Ok -> ok.add(account.id)
                    is StudioResult.Err -> failed.add(label to res.error)
                }
            }
            refreshNodeGroups()
            refreshNodes()
            PlanWriteResult(ok, failed)
        }

    /** Deletes from the one installation that holds it. Its endpoints stay and are ungrouped. */
    suspend fun deleteNodeGroup(group: NodeGroup): StudioResult<Unit> = withContext(Dispatchers.IO) {
        val account = installedAccounts().firstOrNull { it.id == group.installationId }
            ?: return@withContext StudioResult.Err(
                StudioError(StudioError.NOT_CONFIGURED, detail = "no such installation")
            )
        val res = apiFor(account).deleteNodeGroup(group.id)
        if (res is StudioResult.Ok) { refreshNodeGroups(); refreshNodes() }
        res
    }

    /** The last fifty probes on one endpoint. Null when its installation is too old to keep them. */
    suspend fun nodeHistory(node: StudioNode): NodeHistory? = withContext(Dispatchers.IO) {
        val account = installedAccounts().firstOrNull { it.id == node.installationId }
            ?: return@withContext null
        (apiFor(account).nodeHistory(node.id) as? StudioResult.Ok)?.value
    }

    /**
     * Probe everything, but not more often than [AUTO_PROBE_EVERY_MS].
     *
     * **This is what "automatic health checks" can honestly mean here, and the limit is worth
     * stating.** A Cloudflare Worker has no scheduler in this deployment path — nothing in the
     * engine wakes up on its own — and the probe has to run from the operator's own network
     * anyway, because the question is whether an address answers *from Iran*. So the only machine
     * that can do this is the phone, and the only time it can do it is while the app is open.
     *
     * Called on entry to Config Studio and on entry to the endpoints screen. The interval is what
     * keeps that from being a burst of TCP handshakes every time somebody navigates.
     */
    suspend fun autoProbeNodes(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastAutoProbeAt < AUTO_PROBE_EVERY_MS) return@withContext 0
        lastAutoProbeAt = now
        probeAllNodes()
    }

    /**
     * Write one node to the installation it belongs to, or — for a new one — to every installation.
     *
     * The two cases genuinely differ. Editing an existing node addresses the account that holds it,
     * because that is the worker serving it. A NEW node has no account yet, and the useful default
     * is the one an operator means by "add this clean IP": everywhere, so a subscriber landing on
     * any shard gets it. Both are `PUT`s on a client-chosen id, so a retry after a partial failure
     * is safe.
     */
    suspend fun saveNode(node: StudioNode, toWholeFleet: Boolean): PlanWriteResult =
        withContext(Dispatchers.IO) {
            val ok = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, StudioError>>()
            // A central Worker's addresses are its own settings (the Cloud tab), not endpoints written here.
            val targets = (if (toWholeFleet) installedAccounts()
            else installedAccounts().filter { it.id == node.installationId }).filter { !isCentral(it.id) }

            for (account in targets) {
                val label = account.name.ifEmpty { account.email }
                when (val res = apiFor(account).putNode(node)) {
                    is StudioResult.Ok -> ok.add(account.id)
                    is StudioResult.Err -> failed.add(label to res.error)
                }
            }
            refreshNodes()
            PlanWriteResult(ok, failed)
        }

    /** Deletes from the one installation that holds it. A node id is not unique across the fleet. */
    suspend fun deleteNode(node: StudioNode): StudioResult<Unit> = withContext(Dispatchers.IO) {
        val account = installedAccounts().firstOrNull { it.id == node.installationId }
            ?: return@withContext StudioResult.Err(
                StudioError(StudioError.NOT_CONFIGURED, detail = "no such installation")
            )
        val res = apiFor(account).deleteNode(node.id)
        if (res is StudioResult.Ok) refreshNodes()
        res
    }

    /**
     * Open a TCP connection to a node and time it, then tell that node's installation.
     *
     * **A connect, not a ping.** ICMP needs a raw socket Android will not hand an ordinary app, and
     * it answers a different question anyway: what matters here is whether the operator's own
     * network will let a TCP session reach that address on that port, which is exactly what a
     * subscriber's client is about to try. A host that answers ICMP and refuses 443 is the ordinary
     * shape of a blocked address, and a ping would call it healthy.
     *
     * The first port is probed, not all of them. An operator with five nodes on three ports each
     * would otherwise wait through fifteen timeouts to learn one thing.
     */
    suspend fun probeNode(node: StudioNode): StudioResult<StudioNode> = withContext(Dispatchers.IO) {
        val res = probeOne(node)
        if (res is StudioResult.Ok) refreshNodes()
        res
    }

    /**
     * Probe every node the fleet has, and report how many answered.
     *
     * **One refresh at the end, not one per node.** Calling [probeNode] in a loop would re-read
     * every installation's node list after each probe — with eight nodes across three accounts that
     * is twenty-four list requests to learn eight facts, and the operator waits through all of them.
     *
     * Sequential rather than parallel, and deliberately so: these probes run on the operator's own
     * connection, and eight simultaneous TCP handshakes over a congested mobile link measure the
     * congestion rather than the nodes.
     */
    suspend fun probeAllNodes(): Int = withContext(Dispatchers.IO) {
        var up = 0
        for (node in _state.value.nodes) {
            val res = probeOne(node)
            if (res is StudioResult.Ok && res.value.health == NodeHealth.UP) up++
        }
        refreshNodes()
        up
    }

    /** The measurement and the report, without the list re-read. */
    private suspend fun probeOne(node: StudioNode): StudioResult<StudioNode> {
        val account = installedAccounts().firstOrNull { it.id == node.installationId }
            ?: return StudioResult.Err(
                StudioError(StudioError.NOT_CONFIGURED, detail = "no such installation")
            )

        // The first port only. An operator with five nodes on three ports each would otherwise
        // wait through fifteen timeouts to learn five things.
        val port = node.ports.firstOrNull() ?: 443
        var latency: Int? = null
        var failure: String? = null
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(node.host, port), PROBE_TIMEOUT_MS)
            }
            latency = (android.os.SystemClock.elapsedRealtime() - started).toInt()
        } catch (e: Exception) {
            // The exception's own words, trimmed. A translated string here would hide which of
            // "refused", "unreachable" and "timed out" it was, and those need different actions.
            failure = (e.message ?: e.javaClass.simpleName).take(160)
        }

        return apiFor(account).reportNodeHealth(
            id = node.id,
            ok = failure == null,
            latencyMs = latency,
            error = failure,
        )
    }

    suspend fun refreshDashboard(): DashboardSnapshot = withContext(Dispatchers.IO) {
        var acc = DashboardSnapshot(enforcement = Enforcement.STRICT)
        var any = false
        val failures = mutableListOf<String>()
        // Kept per shard as well as summed, because the capacity meter and auto-placement both need
        // to know which account the users are on, not just how many there are in total.
        val perShard = mutableMapOf<String, Int>()

        for (account in installedAccounts()) {
            when (val res = apiFor(account).dashboard()) {
                is StudioResult.Err -> failures.add(account.name.ifEmpty { account.email })
                is StudioResult.Ok -> {
                    val d = res.value
                    any = true
                    perShard[account.id] = d.total
                    acc = acc.copy(
                        total = acc.total + d.total,
                        active = acc.active + d.active,
                        expiringSoon = acc.expiringSoon + d.expiringSoon,
                        expired = acc.expired + d.expired,
                        overQuota = acc.overQuota + d.overQuota,
                        trafficUpBytes24h = acc.trafficUpBytes24h + d.trafficUpBytes24h,
                        trafficDownBytes24h = acc.trafficDownBytes24h + d.trafficDownBytes24h,
                        trafficUpBytes30d = acc.trafficUpBytes30d + d.trafficUpBytes30d,
                        trafficDownBytes30d = acc.trafficDownBytes30d + d.trafficDownBytes30d,
                        // Summed only over the installations that can answer, and left null while
                        // none of them can. `(a ?: 0) + (b ?: 0)` would turn a fleet of build-6
                        // engines into a confident zero, which is the one answer that is certainly
                        // wrong -- the number is unknown, not none.
                        online = plus(acc.online, d.online),
                        sessions24h = plus(acc.sessions24h, d.sessions24h),
                        configs = plus(acc.configs, d.configs),
                        nodesTotal = plus(acc.nodesTotal, d.nodesTotal),
                        nodesEnabled = plus(acc.nodesEnabled, d.nodesEnabled),
                        nodesDown = plus(acc.nodesDown, d.nodesDown),
                        enforcement = if (d.enforcement == Enforcement.SOFT) Enforcement.SOFT else acc.enforcement,
                        generatedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
        if (!any) acc = acc.copy(enforcement = Enforcement.SOFT)

        _state.update { s ->
            s.copy(
                dashboard = acc,
                // Named, not counted. "3 accounts did not answer" is a different sentence from
                // numbers that are quietly missing three accounts' worth of users.
                unreachable = failures,
                // A shard that did not answer keeps the count it had. Resetting it to "unknown"
                // would make auto-placement treat a temporarily unreachable account as the emptiest
                // one on the next pass, which is the opposite of what its silence means.
                installations = s.installations.map { inst ->
                    perShard[inst.installationId]?.let { inst.copy(users = it) } ?: inst
                },
            )
        }
        acc
    }
}

/**
 * Immutable, and replaced wholesale on every change.
 *
 * The `justDeployed` workarounds elsewhere in this app exist because `CloudManager` mutates account
 * objects in place and re-publishes the same references, which `distinctUntilChanged` then swallows.
 * Nothing here is mutated after construction, so a screen that collects this flow sees every change
 * without needing a flag to tell it to look again.
 */
data class StudioState(
    val installations: List<StudioInstallation> = emptyList(),
    val dashboard: DashboardSnapshot? = null,
    val indexedUsers: Int = 0,
    val indexedActive: Int = 0,
    /** How old the *worst* installation's data is. Null when nothing has synced yet. */
    val oldestSyncAgeMs: Long? = null,
    /** Installations that did not answer. Rows from them are still listed, and marked. */
    val unreachable: List<String> = emptyList(),
    val busy: Set<String> = emptySet(),
    /** Merged across the fleet. Each carries the accounts that actually hold it. */
    val plans: List<Plan> = emptyList(),
    /** Installation id to the name to print for it, so drift can be named rather than counted. */
    val fleetLabels: Map<String, String> = emptyMap(),
    /**
     * The installations whose plan list was actually read.
     *
     * Drift is measured against this, never against the whole fleet. An account that did not answer
     * has plans nobody has seen — reporting those as "missing from X" would name an account that
     * may hold every plan perfectly well, and send the operator to fix nothing.
     */
    val plansAnsweredBy: Set<String> = emptySet(),
    /** Accounts whose plans could not be read. Their plans are absent, not proven missing. */
    val plansUnreachable: List<String> = emptyList(),
    /** Merged across the fleet, exactly as [plans] are. Each carries the accounts that hold it. */
    val templates: List<ConfigTemplate> = emptyList(),
    /** Installations whose template list was read. Drift is measured against this, never the fleet. */
    val templatesAnsweredBy: Set<String> = emptySet(),
    /** Accounts whose templates could not be read. An engine too old to have them is not in here. */
    val templatesUnreachable: List<String> = emptyList(),
    /** Every installation's endpoints, merged. The same address on two accounts is two rows. */
    val nodes: List<StudioNode> = emptyList(),
    /** Accounts whose node list could not be read. */
    val nodesUnreachable: List<String> = emptyList(),
    /**
     * Every installation's endpoint groups, merged, exactly as [nodes] are.
     *
     * NOT merged by id across accounts the way plans and templates are. A group is written per
     * installation because the endpoints it orders are per installation — the same group id on two
     * accounts orders two different sets of addresses, so calling them one row would produce a
     * drift line about a difference that is supposed to exist.
     */
    val nodeGroups: List<NodeGroup> = emptyList(),
    /** Installations too old to have groups at all. Named apart from unreachable: this is an update. */
    val nodeGroupsStale: List<String> = emptyList(),
    /** The fleet's most recent warnings and errors, merged by time. Empty until first read. */
    val activity: List<ActivityEntry> = emptyList(),
) {
    val isStale: Boolean get() = (oldestSyncAgeMs ?: 0) > StudioStore.STALE_AFTER_MS
    val hasFleet: Boolean get() = installations.size > 1
}

/**
 * Everything about ONE config that only the app can produce.
 *
 * It exists because [StudioStore.bulkAddConfig] must not know how a URI is built — that lives in
 * `config/ConfigBuilder`, which is in the same module but is UI-facing, and a store that imported it
 * would put URI construction on both sides of the boundary the whole feature is organised around.
 * The caller renders; the store loops.
 */
data class ConfigPayload(
    val uriTemplate: String,
    val credential: String?,
    val protocol: String?,
    val transportType: String?,
    val routeKey: String?,
    val authHash: String?,
)

/**
 * The outcome of a write that had to reach every installation.
 *
 * Partial success is the normal case in a fleet with an account that is temporarily unreachable, so
 * it is a first-class result rather than an exception: nineteen of twenty is a situation the
 * operator can act on by retrying one account, and collapsing it into "failed" hides that.
 */
/**
 * Adds two counts that may each be "this engine cannot answer".
 *
 * Null only when NEITHER side has a number. One installation on build 7 and three on build 6 gives
 * a figure that is true for the part of the fleet that can measure it, which is more useful than
 * nothing and honest in a way that a silent zero is not.
 */
private fun plus(a: Int?, b: Int?): Int? = when {
    a == null && b == null -> null
    else -> (a ?: 0) + (b ?: 0)
}

data class PlanWriteResult(
    val wroteTo: List<String>,
    val failed: List<Pair<String, StudioError>>,
) {
    val ok: Boolean get() = failed.isEmpty()
    val partial: Boolean get() = failed.isNotEmpty() && wroteTo.isNotEmpty()
}

data class PlanApplyResult(
    val users: Int,
    val failed: List<Pair<String, StudioError>>,
) {
    val ok: Boolean get() = failed.isEmpty()
}
