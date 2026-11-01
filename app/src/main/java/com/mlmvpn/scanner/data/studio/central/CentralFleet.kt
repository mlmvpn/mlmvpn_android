package com.mlmvpn.scanner.data.studio.central

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.studio.UserRef
import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.engines.gateway.EdgeGateway
import com.mlmvpn.scanner.engines.gateway.GatewayException
import com.mlmvpn.scanner.models.CloudAccount

/**
 * The central-Worker side of the Studio's fleet: which accounts carry it, how each appears to the
 * screens, and the few things that are done to many people at once.
 *
 * An account that runs the Studio on its central Worker appears in the fleet as its own installation,
 * id `<account>#gw` ([Central.installationIdOf]), beside the account's old engine installation if it
 * has one — the people already on the engine stay there with everything they had, and new people go
 * to the central Worker. The installation is a copy of the account with every engine field cleared,
 * so code that reaches for an engine with it ([com.mlmvpn.scanner.data.studio.api.StudioHttpApi])
 * finds none and fails fast rather than writing to the wrong place.
 */
object CentralFleet {

    private const val TAG = "CentralFleet"

    /** The fleet's view of one account's central Worker. */
    fun shardAccount(real: CloudAccount): CloudAccount = real.copy(
        id = Central.installationIdOf(real.id),
        mlmWorkerUrl = null, mlmDbId = null, mlmAdminPassword = null, mlmStatus = "idle", mlmVersion = 0,
        studioApiRoute = null, studioKeyId = null, studioApiSecret = null, studioKeyLabel = null,
        studioAdopted = false, studioDoTag = null, studioStatus = "central", studioVersion = 0,
    )

    /** The real account behind an installation id of either kind. */
    fun realAccount(accounts: List<CloudAccount>, installationId: String): CloudAccount? =
        accounts.firstOrNull { it.id == Central.accountIdOf(installationId) }

    /** Every account this phone runs the Studio on through its central Worker, as fleet entries. */
    fun shards(context: Context, accounts: List<CloudAccount>): List<CloudAccount> {
        val ids = CentralFiles.accountIds(context).toSet()
        if (ids.isEmpty()) return emptyList()
        return accounts.filter { it.id in ids }.map(::shardAccount)
    }

    fun isShard(context: Context, account: CloudAccount): Boolean =
        Central.accountIdOf(account.id) in CentralFiles.accountIds(context)

    /**
     * A record kept under a local account id this phone no longer has, for a Cloudflare account it
     * does have under another id -- the account removed in the Cloud tab and added again, or a
     * restore that brought the record but not the account's old id. Moved to the id the account has
     * now (merged, person by person, when that id has a record too). Left alone, the people would be
     * stranded on this phone, and the account could never run the Studio again: [register] refuses
     * a second record for the same Cloudflare account. Nothing to do costs one in-memory list.
     */
    fun adoptOrphans(context: Context, accounts: List<CloudAccount>) {
        val known = accounts.map { it.id }.toHashSet()
        if (CentralFiles.accountIds(context).all { it in known }) return
        val docs = CentralFiles.all(context)
        for (orphan in docs) {
            if (orphan.accountId in known || orphan.cloudAccountId.isBlank()) continue
            val home = accounts.firstOrNull { !Central.isCentral(it.id) && it.accountId == orphan.cloudAccountId } ?: continue
            val existing = CentralFiles.read(context, home.id)
            val moved = existing?.let { CentralDoc.merge(it, orphan.toLocal(), remoteAt = 0L) } ?: orphan.copy(accountId = home.id)
            CentralFiles.write(context, moved)
            CentralFiles.delete(context, orphan.accountId)
            Log.i(TAG, "record of ${orphan.accountId} moved to ${home.id}")
        }
    }

    /**
     * Run the Studio on [real]'s central Worker from this phone. Local only: the Worker is installed (or
     * adopted) by the first upload, and the account's record is read on the next look. Refused for a
     * second local record of the same Cloudflare account — one account, one central Worker, one list.
     */
    fun register(context: Context, real: CloudAccount): Boolean {
        if (Central.isCentral(real.id)) return false
        if (CentralFiles.read(context, real.id) != null) return true
        val twin = CentralFiles.all(context).firstOrNull { it.cloudAccountId == real.accountId && it.accountId != real.id }
        if (twin != null) return false
        CentralFiles.write(context, CentralDoc(accountId = real.id, cloudAccountId = real.accountId))
        return true
    }

    /** Stop running the Studio on this account from this phone. The Worker and the account's record stay. */
    fun unregister(context: Context, installationId: String): Boolean = CentralFiles.delete(context, Central.accountIdOf(installationId))

    /**
     * The accounts that already carry the Studio on an engine get its central Worker beside it, so
     * new people have somewhere to go without the operator setting anything up. Local only.
     */
    fun ensureForEngineAccounts(context: Context, accounts: List<CloudAccount>) {
        for (a in accounts) if (a.isStudioManaged && !isShard(context, a)) runCatching { register(context, a) }
    }

    /**
     * Accounts with a central Worker that another phone already runs the Studio on: found by their
     * record (key [Central.CODE]) and taken into this phone's fleet — D8, the same people on every
     * device, never a second list. Only accounts whose central Worker this phone knows are asked.
     */
    fun discover(context: Context, accounts: List<CloudAccount>): Int {
        var found = 0
        for (a in accounts) {
            if (isShard(context, a)) continue
            if (!EdgeGateway.status(context, a).installed) continue
            val read = runCatching { CentralRegistry.read(a) }.getOrNull() as? RegistryRead.Found ?: continue
            if (CentralDoc.parseUsers(read.s).isEmpty() && CentralDoc.parsePlans(read.s).isEmpty()) continue
            if (register(context, a)) found++
        }
        return found
    }

    /** Bring every shard's Worker in step. Blocking (IO). A failure on one account never stops the rest. */
    fun reconcile(context: Context, shard: CloudAccount): Reconciled? = try {
        centralEngine(context, shard).reconcile()
    } catch (e: Exception) {
        Log.w(TAG, "reconcile ${shard.id}: ${e.message}")
        null
    }

    /** One person's outcome inside a batch: null = done. */
    class BatchOutcome(val done: Int, val failures: List<Pair<UserRef, String>>, val failedAll: StudioError? = null)

    /**
     * Do [op] to every one of [refs] on ONE account in ONE upload. A person [op] refuses keeps their
     * old record and is named with the refusal's code; the rest go up together. When the upload
     * itself fails nobody changed, and all of them are named with the reason.
     */
    fun batch(
        context: Context,
        shard: CloudAccount,
        refs: List<UserRef>,
        op: (doc: CentralDoc, ref: UserRef, room: Int, now: Long) -> CentralDoc,
    ): BatchOutcome {
        val failures = mutableListOf<Pair<UserRef, String>>()
        return try {
            val engine = centralEngine(context, shard)
            val done = engine.mutate { start, room ->
                var doc = start
                val now = System.currentTimeMillis()
                var ok = 0
                failures.clear()
                for (ref in refs) {
                    try {
                        doc = op(doc, ref, room, now)
                        ok++
                    } catch (e: CentralRules.RuleError) {
                        failures += ref to e.code
                    }
                }
                doc to ok
            }
            engine.audit("bulk", null, "$done")
            BatchOutcome(done, failures.toList())
        } catch (e: CentralRules.RuleError) {
            BatchOutcome(0, refs.map { it to e.code }, StudioError(e.code, detail = e.detail))
        } catch (e: GatewayException) {
            BatchOutcome(0, refs.map { it to StudioCentralApi.GATEWAY }, StudioError(StudioCentralApi.GATEWAY, detail = EdgeGateway.localized(e)))
        } catch (e: Exception) {
            val offline = generateSequence<Throwable>(e) { it.cause }.any { it is java.io.IOException }
            val code = if (offline) StudioError.NETWORK else StudioCentralApi.GATEWAY
            BatchOutcome(0, refs.map { it to code }, StudioError(code, detail = EdgeGateway.explainForStudio(e)))
        }
    }

    /** What [createMany] made, and the names it could not, with why. */
    class CreateOutcome(val created: List<CentralUser>, val failures: List<Pair<String, String>>, val failedAll: StudioError? = null)

    /**
     * Many people on ONE account in ONE upload, in the order given. A name the rules refuse (taken,
     * no room left) is named with its code and the rest are still made; when the upload itself
     * fails, nobody was made.
     */
    fun createMany(context: Context, shard: CloudAccount, names: List<String>, expiresAt: Long, planId: String?): CreateOutcome {
        val failures = mutableListOf<Pair<String, String>>()
        return try {
            val engine = centralEngine(context, shard)
            val created = engine.mutate { start, room ->
                var doc = start
                val now = System.currentTimeMillis()
                val made = mutableListOf<CentralUser>()
                failures.clear()
                for (name in names) {
                    try {
                        val (next, user) = CentralRules.create(doc, name, expiresAt, now, room, Central::newUserId,
                            { java.util.UUID.randomUUID().toString() }, planId = planId)
                        doc = next
                        made += user
                    } catch (e: CentralRules.RuleError) {
                        failures += name to e.code
                    }
                }
                doc to made.toList()
            }
            engine.audit("bulk.create", null, "${created.size}")
            CreateOutcome(created, failures.toList())
        } catch (e: CentralRules.RuleError) {
            CreateOutcome(emptyList(), names.map { it to e.code }, StudioError(e.code, detail = e.detail))
        } catch (e: GatewayException) {
            CreateOutcome(emptyList(), names.map { it to StudioCentralApi.GATEWAY }, StudioError(StudioCentralApi.GATEWAY, detail = EdgeGateway.localized(e)))
        } catch (e: Exception) {
            val offline = generateSequence<Throwable>(e) { it.cause }.any { it is java.io.IOException }
            val code = if (offline) StudioError.NETWORK else StudioCentralApi.GATEWAY
            CreateOutcome(emptyList(), names.map { it to code }, StudioError(code, detail = EdgeGateway.explainForStudio(e)))
        }
    }

    /** The doc of the shard an installation id names, or null. No request. */
    fun doc(context: Context, installationId: String): CentralDoc? = CentralFiles.read(context, Central.accountIdOf(installationId))
}
