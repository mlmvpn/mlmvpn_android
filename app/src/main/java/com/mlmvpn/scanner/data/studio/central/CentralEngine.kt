package com.mlmvpn.scanner.data.studio.central

import com.mlmvpn.scanner.engines.gateway.Gateway
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the Studio asks of the Cloudflare account and of its central Worker — a seam, so the
 * whole protocol runs as a plain JVM test against a fake account (CentralEngineTest), the way
 * GatewayEngine's does. Every call blocks; callers are on IO.
 */
interface CentralCloud {
    /** The account's shared record (registry key [Central.CODE]). Throws when it could not be read. */
    fun registryGet(): RegistryRead
    fun registryPut(script: String, url: String, s: JSONObject)
    /** Delete the record, but only while it still names [script]. */
    fun registryRemove(script: String)

    /** The account's central Worker as this device sees it after reading the account's own record. */
    fun gateway(): GatewayView

    /**
     * Upload the central Worker carrying exactly these Studio [ids] (installing it first when the
     * account has none). Throws when Cloudflare refuses; nothing on the account changed then.
     */
    fun apply(ids: List<String>, onStep: (String) -> Unit): GatewayView
}

/** What reading the account's record found. */
sealed interface RegistryRead {
    data class Found(val script: String, val url: String, val s: JSONObject, val by: String, val at: Long) : RegistryRead
    /** Read fine; there is no record (yet). */
    data object Missing : RegistryRead
    /** This credential may not read the account's KV at all: nothing to share, nothing to lose. */
    data object NoAccess : RegistryRead
}

/** The central Worker, in the few facts the Studio needs. Never its ids or path. */
data class GatewayView(
    val installed: Boolean,
    val script: String = "",
    val host: String = "",
    /** How many Studio people fit beside BPB and the connected panels. */
    val room: Int = 0,
    /** [Gateway.idsMarker] of the Studio ids the account's Worker record says the last upload carried. */
    val studioMark: String = "",
)

/** This phone's copy of one account's record. */
interface CentralLocal {
    fun get(): CentralDoc?
    fun put(doc: CentralDoc)
}

/** What [CentralEngine.reconcile] found and did. */
data class Reconciled(
    /** The Worker had to be uploaded again (someone's end passed, or another device dropped the ids). */
    val uploaded: Boolean,
    /** The account's record was written (this phone knew of a change it had not heard of). */
    val published: Boolean,
    /** People who should pass but did not fit on the Worker. */
    val leftOut: Int,
    /** The account's record could not be read: nothing was changed. */
    val unreadable: Boolean = false,
)

/**
 * The Studio's protocol on one account — the same four steps the central Worker's own record
 * follows (docs/PANEL-REGISTRY.md, key `GW`), for the Studio's record (key `CS`):
 *
 *  1. **pull** — the account's record is read first and merged in, person by person, so a change
 *     made on another phone is never thrown away;
 *  2. **change** — made on a copy ([CentralRules]); a refusal stops here and nothing is written;
 *  3. **publish** — the copy is written to the account BEFORE the Worker is touched, so a device
 *     that uploads the Worker for another reason carries the new person too;
 *  4. **upload** — the Worker with exactly the ids that may pass now; if Cloudflare refuses, the
 *     account's record goes back to what it held and this phone keeps its old copy — all or nothing.
 *
 * A record this credential may not read ([RegistryRead.NoAccess]) does not stop the Studio: there
 * is then nothing another device could have written either. A record that could not be read for any
 * other reason DOES stop a change — publishing over a record nobody has seen would erase whatever
 * another phone put there.
 */
class CentralEngine(
    private val accountId: String,
    private val cloudAccountId: String,
    private val cloud: CentralCloud,
    private val local: CentralLocal,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** One lock per account for the whole process: two screens never interleave a change. */
    private val locks: MutableMap<String, Any> = LOCKS,
) {

    private fun lock(): Any = locks.getOrPut(cloudAccountId.ifBlank { accountId }) { Any() }

    private fun blank() = CentralDoc(accountId = accountId, cloudAccountId = cloudAccountId)

    /** This phone's copy, or an empty one. No request. */
    fun current(): CentralDoc = local.get() ?: blank()

    private class Pulled(val doc: CentralDoc, val read: RegistryRead?, val failure: Exception?)

    /** Step 1: the account's record merged into this phone's copy, which is saved when it moved. */
    private fun pullLocked(gw: GatewayView?): Pulled {
        val mine = local.get() ?: blank()
        val read = try {
            cloud.registryGet()
        } catch (e: Exception) {
            return Pulled(mine, null, e)
        }
        val found = read as? RegistryRead.Found ?: return Pulled(mine, read, null)
        // Merged even when it names another Worker (the account's was replaced): the people are the
        // same people, and only their links changed — which the screens say, from [CentralDoc.script].
        val merged = CentralDoc.merge(mine, found.s, found.at).copy(sharedAt = maxOf(mine.sharedAt, found.at))
        if (merged != mine) local.put(merged)
        return Pulled(merged, read, null)
    }

    /** The account's record taken in, without changing anything on Cloudflare. Never throws. */
    fun pull(): CentralDoc = synchronized(lock()) { pullLocked(null).doc }

    /** Change what this phone keeps for itself alone (what it wrote where), under the account's lock. No request. */
    fun updateLocal(change: (CentralDoc) -> CentralDoc) = synchronized(lock()) {
        local.get()?.let { val next = change(it); if (next != it) local.put(next) }
        Unit
    }

    /** One line in this phone's own history of the account (never shared, never a request). */
    fun audit(action: String, target: String?, summary: String?) = synchronized(lock()) {
        local.get()?.let { local.put(it.withAudit(CentralAudit(now(), action, target, summary))) }
        Unit
    }

    /**
     * Change the people of this account. [change] gets the merged copy and the room the Worker has
     * for Studio ids, and returns the new copy and whatever the caller wants back; a
     * [CentralRules.RuleError] from it stops everything before a byte is written.
     */
    fun <T> mutate(onStep: (String) -> Unit = {}, change: (doc: CentralDoc, room: Int) -> Pair<CentralDoc, T>): T =
        synchronized(lock()) {
            val step = { m: String -> try { onStep(m) } catch (_: Exception) { } }
            val gw = cloud.gateway()
            val pulled = pullLocked(gw)
            pulled.failure?.let { throw CentralRules.RuleError(UNREADABLE, it.message) }
            val doc = pulled.doc
            val stamp = now()
            // A Worker not installed yet has room for everyone but its own id; the upload installs it.
            val (changed, result) = change(doc, gw.room)
            publishAndApply(doc, changed, pulled.read, gw, stamp, step)
            result
        }

    /**
     * A change that does not touch who may pass — a «بسته», the contact line: pulled, changed and
     * written to the account's record, with no upload. A Worker not installed yet has no script to
     * file the record under, so the change waits on this phone (`unshared`) for the first upload.
     */
    fun <T> share(onStep: (String) -> Unit = {}, change: (doc: CentralDoc) -> Pair<CentralDoc, T>): T =
        synchronized(lock()) {
            val step = { m: String -> try { onStep(m) } catch (_: Exception) { } }
            val gw = cloud.gateway()
            val pulled = pullLocked(gw)
            pulled.failure?.let { throw CentralRules.RuleError(UNREADABLE, it.message) }
            val stamp = now()
            val (next, result) = change(pulled.doc)
            val canShare = pulled.read != null && pulled.read !is RegistryRead.NoAccess
            var published = false
            if (canShare && gw.installed) {
                try {
                    cloud.registryPut(gw.script, "https://${gw.host}", next.toShared(stamp))
                    published = true
                } catch (e: Exception) {
                    step(tr("در حساب ثبت نشد (${e.message}) — در هماهنگی بعدی ثبت می‌شود.",
                        "Not written to the account (${e.message}) — the next sync writes it."))
                }
            }
            local.put(next.copy(sharedAt = if (published) stamp else pulled.doc.sharedAt, unshared = canShare && !published))
            result
        }

    /** Steps 3 and 4, and the commit — or, when Cloudflare refuses, the account's record put back. */
    private fun publishAndApply(before: CentralDoc, next: CentralDoc, read: RegistryRead?, gw: GatewayView, stamp: Long, step: (String) -> Unit): GatewayView {
        val ids = next.liveIds(stamp).take(gw.room)
        var published = false
        val canShare = read != null && read !is RegistryRead.NoAccess
        // The Worker's script is only known once it is installed; a first upload publishes after it.
        if (canShare && gw.installed) {
            try {
                cloud.registryPut(gw.script, "https://${gw.host}", next.toShared(stamp))
                published = true
            } catch (e: Exception) {
                step(tr("فهرست در حساب ثبت نشد (${e.message}) — ورکر به‌روز می‌شود، ولی دستگاه دیگر هنوز خبر ندارد.",
                    "The list was not written to the account (${e.message}) — the Worker is updated, but the other device does not know yet."))
            }
        }
        // The Worker already carries exactly these ids (the account's Worker record says so): a change
        // that moved nobody on or off it -- a name, a note, a renewal inside the term -- needs no
        // upload. An upload is a new deployment of the Worker every panel on it rides, and ten
        // seconds of the operator waiting, for nothing.
        val inStep = gw.installed && Gateway.idsMarker(ids) == gw.studioMark
        val after = if (inStep) gw else try {
            cloud.apply(ids, step)
        } catch (e: Exception) {
            if (published) restore(read, gw.script, step)
            throw e
        }
        // A first install: the script exists now, so the account can be told.
        if (canShare && !published && after.installed) {
            try {
                cloud.registryPut(after.script, "https://${after.host}", next.toShared(stamp))
                published = true
            } catch (e: Exception) {
                step(tr("فهرست در حساب ثبت نشد (${e.message}).", "The list was not written to the account (${e.message})."))
            }
        }
        local.put(
            next.copy(
                cloudAccountId = cloudAccountId.ifBlank { next.cloudAccountId },
                script = after.script.ifBlank { gw.script }, host = after.host.ifBlank { gw.host },
                appliedIds = ids, appliedAt = stamp,
                sharedAt = if (published) stamp else before.sharedAt,
                unshared = canShare && !published,
            )
        )
        return after
    }

    /** Put the account's record back to what it held before a change the Worker then refused. */
    private fun restore(read: RegistryRead?, script: String, step: (String) -> Unit) {
        try {
            when (read) {
                is RegistryRead.Found -> cloud.registryPut(read.script, read.url, read.s)
                RegistryRead.Missing -> cloud.registryRemove(script)
                else -> Unit
            }
        } catch (e: Exception) {
            step(tr("فهرست حساب به حالت قبل برنگشت (${e.message}) — در هماهنگی بعدی درست می‌شود.",
                "The account's list did not go back (${e.message}) — the next sync fixes it."))
        }
    }

    /**
     * Bring the Worker in step without anyone changing anything: after a person's end has passed,
     * after another device uploaded the Worker without the Studio's ids (the Windows app, an older
     * phone), or after this phone's last write to the account failed. The account's Worker record
     * carries a digest of the Studio ids its last upload had ([GatewayView.studioMark]); when that
     * is not the digest of who may pass now, the Worker is uploaded again. Nothing is uploaded when it
     * matches — so two phones running this never take turns uploading.
     */
    fun reconcile(onStep: (String) -> Unit = {}): Reconciled = synchronized(lock()) {
        val step = { m: String -> try { onStep(m) } catch (_: Exception) { } }
        val gw = cloud.gateway()
        val pulled = pullLocked(gw)
        if (pulled.failure != null) return@synchronized Reconciled(uploaded = false, published = false, leftOut = 0, unreadable = true)
        val doc = pulled.doc
        val stamp = now()
        val wanted = doc.liveIds(stamp)
        val fits = wanted.take(gw.room)
        val leftOut = wanted.size - fits.size
        // Nobody here and nobody on the Worker: nothing to do, and no Worker is installed for nothing.
        if (!gw.installed) return@synchronized Reconciled(uploaded = false, published = false, leftOut = 0)
        val canShare = pulled.read != null && pulled.read !is RegistryRead.NoAccess
        val remoteKnows = (pulled.read as? RegistryRead.Found)?.let { knowsAll(it.s, doc) } ?: (doc.users.isEmpty() && doc.plans.isEmpty())
        var published = false
        if (canShare && (doc.unshared || !remoteKnows)) {
            try {
                cloud.registryPut(gw.script, "https://${gw.host}", doc.toShared(stamp))
                published = true
            } catch (e: Exception) {
                step(tr("فهرست در حساب ثبت نشد (${e.message}).", "The list was not written to the account (${e.message})."))
            }
        }
        val inStep = Gateway.idsMarker(fits) == gw.studioMark
        if (inStep) {
            if (published || doc.appliedIds != fits) local.put(doc.copy(appliedIds = fits, sharedAt = if (published) stamp else doc.sharedAt, unshared = canShare && !published && doc.unshared))
            return@synchronized Reconciled(uploaded = false, published = published, leftOut = leftOut)
        }
        val after = cloud.apply(fits, step)
        local.put(
            doc.copy(
                script = after.script.ifBlank { gw.script }, host = after.host.ifBlank { gw.host },
                appliedIds = fits, appliedAt = stamp,
                sharedAt = if (published) stamp else doc.sharedAt,
                unshared = if (published) false else doc.unshared,
            )
        )
        Reconciled(uploaded = true, published = published, leftOut = leftOut)
    }

    /** Whether the account's record already holds every person and plan of [doc], as new or newer. */
    private fun knowsAll(s: JSONObject, doc: CentralDoc): Boolean {
        val remoteUsers = CentralDoc.parseUsers(s).associateBy { it.id }
        val remotePlans = CentralDoc.parsePlans(s).associateBy { it.id }
        val usersOk = doc.users.all { u -> remoteUsers[u.id]?.let { CentralUser.newer(it, u) == it } == true }
        val plansOk = doc.plans.all { p -> remotePlans[p.id]?.let { PlanJson.newer(it, p) == it } == true }
        val contactOk = (s.optNullableString("contact") ?: "") == (doc.contact ?: "")
        val subOk = doc.sub == null || SubWorker.fromJson(s.optJSONObject("sub")) == doc.sub
        return usersOk && plansOk && contactOk && subOk
    }

    companion object {
        /** The code of a refusal because the account's record could not be read (try again). */
        const val UNREADABLE = "registry_unreadable"

        private val LOCKS = ConcurrentHashMap<String, Any>()

        /** Multi-language text without Android: the screens pass the user's language in through [language]. */
        @Volatile var language: () -> String = { java.util.Locale.getDefault().language }
    }

    private fun tr(fa: String, en: String): String = when (language()) {
        "fa" -> fa
        "zh" -> com.mlmvpn.scanner.utils.ZhTr.translate(en) ?: en
        else -> en
    }
}
