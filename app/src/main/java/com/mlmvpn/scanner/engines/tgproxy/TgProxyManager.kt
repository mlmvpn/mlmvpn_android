package com.mlmvpn.scanner.engines.tgproxy

import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Seals the secret at rest. The app uses the Android Keystore; tests use a stand-in. */
interface TgSealer {
    fun seal(plain: String): String?
    fun open(sealed: String): String?
}

enum class TgOp { CREATE, CHECK, DELETE, DOMAIN }

enum class TgStep { ACCOUNT, BUILD, ACTIVATE, TEST, CLEANUP }

/** What is running now, for the screen: which service (null before it has one), what, and where. */
data class TgProgress(val serviceId: String?, val op: TgOp, val step: TgStep)

/** A one-time message for the screen: a code the UI words, and one number (a time, a count). */
data class TgNotice(val code: String, val arg: Long = 0L)

/** A resource this feature made in a Cloudflare account that the app has no record of. */
data class TgOrphan(val accountId: String, val method: TgMethod, val resource: String)

/**
 * Builds, checks and removes the user's Telegram proxies in their own Cloudflare account.
 *
 * Rules this class keeps, because a mistake here costs the user real resources or their account:
 *
 *  - **One operation at a time.** A second tap, or a second screen, while one runs is refused, not
 *    queued; so there is no way to create two of the same thing or race a delete against a build.
 *  - **Nothing is created before it is recorded.** The service, with its sealed secret, is written to
 *    the store before the first Cloudflare call; an interrupted build is found again as PARTIAL.
 *  - **Resume before create.** A service that is not READY is rebuilt in place (every step is safe to
 *    repeat) instead of a new one being added next to it. A fresh service is made only when all the
 *    existing ones in that account and method are READY, and never past [MAX_PER_METHOD].
 *  - **Only our own resources are changed.** Before a check or a delete, the Cloudflare account must
 *    be the recorded one and the resource's `MLM_TGP_ID` must equal the service id.
 *  - **Honest state.** READY only after the self-test reached Telegram through the app's route. A
 *    lost or rejected account, a missing resource, or a rate limit never reads as healthy.
 *  - **Bounded.** Deployment polling, the self-test and every retry are capped; nothing loops.
 */
class TgProxyManager(
    private val store: TgStore,
    private val api: TgCfApi,
    private val prober: TgProber,
    private val accounts: () -> List<CloudAccount>,
    private val code: () -> String,
    private val sealer: TgSealer,
    private val scope: CoroutineScope,
    private val createSubdomain: suspend (CloudAccount) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val random: SecureRandom = SecureRandom(),
) {
    private val workers = TgWorkersOps(api)
    private val pages = TgPagesOps(api)
    private val domains = TgDomainOps(api)

    val services: StateFlow<List<TgService>> = store.state

    private val _progress = MutableStateFlow<TgProgress?>(null)
    val progress: StateFlow<TgProgress?> = _progress

    private val _notice = MutableStateFlow<TgNotice?>(null)
    val notice: StateFlow<TgNotice?> = _notice

    private val _orphans = MutableStateFlow<List<TgOrphan>>(emptyList())
    val orphans: StateFlow<List<TgOrphan>> = _orphans

    private val _zones = MutableStateFlow<List<TgZone>>(emptyList())

    /** The sites of the account of the proxy whose domain page is open (see [loadZones]). */
    val zones: StateFlow<List<TgZone>> = _zones

    private val running = AtomicBoolean(false)
    @Volatile private var job: Job? = null

    val idle: Boolean get() = !running.get()

    fun consumeNotice() {
        _notice.value = null
    }

    /** When requests for this service's account may resume, or 0. */
    fun blockedUntil(service: TgService): Long =
        accounts().firstOrNull { it.id == service.accountId }?.let { api.blockedUntil(it) } ?: 0L

    /** Anything left BUILDING by a killed app is really PARTIAL: something may exist in Cloudflare. */
    fun recover() {
        store.update { list ->
            list.map { if (it.state == TgState.BUILDING) it.copy(state = TgState.PARTIAL, reason = "interrupted", updatedAt = now()) else it }
        }
    }

    /** Stops the running operation; the service it was working on is marked PARTIAL. */
    fun cancel() {
        job?.cancel()
    }

    /** The proxy's secret, for the Telegram link and the copy buttons. Null if the Keystore cannot open it. */
    fun secretOf(id: String): String? = store.get(id)?.let { sealer.open(it.sealedSecret) }

    // ------------------------------------------------------------------------ create

    /** Continues exactly this unfinished service (every step is safe to repeat). */
    fun resume(id: String): Boolean = launchOp(TgOp.CREATE, id) {
        val service = store.get(id) ?: return@launchOp
        if (service.state !in RESUMABLE) return@launchOp
        val account = accountFor(service) ?: return@launchOp
        build(account, id)
    }

    /**
     * Builds a proxy of [method] in the account [accountId] (the app's account id). Returns false
     * when another operation is running. Resumes an unfinished service rather than adding one.
     */
    fun create(method: TgMethod, accountId: String): Boolean = launchOp(TgOp.CREATE, null) {
        val account = accounts().firstOrNull { it.id == accountId } ?: return@launchOp notice("no_account")
        if (account.accountId.isBlank()) return@launchOp notice("no_account")

        val mine = store.current.filter { it.accountId == accountId && it.method == method }
        val unfinished = mine.firstOrNull { it.state in RESUMABLE }
        if (unfinished == null && mine.size >= MAX_PER_METHOD) return@launchOp notice("limit", MAX_PER_METHOD.toLong())

        val service = unfinished ?: newService(account, method) ?: return@launchOp notice("keystore")
        build(account, service.id)
    }

    private fun newService(account: CloudAccount, method: TgMethod): TgService? {
        val secret = TgLink.newSecret(random)
        val sealed = sealer.seal(secret) ?: return null
        var id: String
        do {
            id = ByteArray(4).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        } while (store.get(id) != null)
        val at = now()
        val service = TgService(
            id = id, method = method, accountId = account.id, cfAccountId = account.accountId,
            resource = TgService.resourceName(id), host = "", domain = null, sealedSecret = sealed,
            state = TgState.BUILDING, reason = null, blockedUntil = 0L, createdAt = at, updatedAt = at, probe = null,
        )
        // Recorded before Cloudflare hears of it.
        store.update { it + service }
        return service
    }

    private suspend fun build(account: CloudAccount, id: String) {
        setProgress(id, TgOp.CREATE, TgStep.ACCOUNT)
        update(id) { it.copy(state = TgState.BUILDING, reason = null, blockedUntil = 0L, updatedAt = now()) }
        var created = false
        try {
            val service = store.get(id) ?: return
            if (account.accountId != service.cfAccountId) return fail(id, TgState.LOST, "other_account")
            val secret = sealer.open(service.sealedSecret) ?: return fail(id, TgState.FAILED, "keystore")

            val script = code()

            // Listing proves the credential can reach this product at all, before anything is made.
            val names = if (service.method == TgMethod.WORKERS) workers.names(account) else pages.names(account)
            if (names.count { it != service.resource } >= MAX_PER_METHOD + SPARE) return fail(id, TgState.ACTION, "account_full")

            setProgress(id, TgOp.CREATE, TgStep.BUILD)
            val host = when (service.method) {
                TgMethod.WORKERS -> {
                    val sub = workers.subdomain(account)
                        ?: (if (createSubdomain(account)) workers.subdomain(account) else null)
                        ?: return fail(id, TgState.ACTION, "no_subdomain")
                    created = true
                    workers.upload(account, service.resource, script, secret, service.id)
                    setProgress(id, TgOp.CREATE, TgStep.ACTIVATE)
                    workers.enable(account, service.resource)
                    "${service.resource}.$sub.workers.dev"
                }
                TgMethod.PAGES -> {
                    created = true
                    pages.create(account, service.resource, secret, service.id)
                    val deployment = pages.deploy(account, service.resource, script)
                    setProgress(id, TgOp.CREATE, TgStep.ACTIVATE)
                    if (!waitDeployed(account, service.resource, deployment)) return fail(id, TgState.PARTIAL, "deploy_failed")
                    pages.hostOf(pages.project(account, service.resource)).ifBlank { "${service.resource}.pages.dev" }
                }
            }
            update(id) { it.copy(host = host, rev = revOf(script), updatedAt = now()) }
            selfTest(id, host, secret, attempts = TEST_ATTEMPTS, op = TgOp.CREATE)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { fail(id, if (created) TgState.PARTIAL else TgState.FAILED, "cancelled") }
            throw e
        } catch (e: TgApiException) {
            failApi(id, e, created)
        } catch (e: Exception) {
            fail(id, if (created) TgState.PARTIAL else TgState.FAILED, "internal")
        }
    }

    /** Pages builds a deployment asynchronously. At most [DEPLOY_POLLS] looks, [DEPLOY_PAUSE_MS] apart. */
    private suspend fun waitDeployed(account: CloudAccount, project: String, deployment: String): Boolean {
        repeat(DEPLOY_POLLS) { i ->
            val (stage, status) = pages.stage(account, project, deployment)
            if (status == "failure") return false
            if (stage == "deploy" && status == "success") return true
            if (i < DEPLOY_POLLS - 1) pause(DEPLOY_PAUSE_MS)
        }
        return false
    }

    /**
     * Asks Telegram a question through the proxy. The first test goes through the app's route to
     * Cloudflare; only when it passes is the service READY. The second goes through the phone's
     * ordinary network (what Telegram itself will use) and is reported separately. A fresh service
     * is given [attempts] tries within [TEST_WINDOW_MS], because a new address takes a moment.
     */
    private suspend fun selfTest(id: String, host: String, secret: String, attempts: Int, op: TgOp) {
        setProgress(id, op, TgStep.TEST)
        var viaApp: Boolean? = null
        var step: String? = null
        val ok = withTimeoutOrNull(TEST_WINDOW_MS) {
            for (i in 0 until attempts) {
                val r = prober.probe(host, secret, viaApp = true)
                if (r.ok) {
                    viaApp = true
                    step = null
                    return@withTimeoutOrNull true
                }
                viaApp = false
                step = r.step
                if (i < attempts - 1) pause(TEST_PAUSE_MS * (i + 1))
            }
            false
        } == true
        if (!ok) {
            update(id) { it.copy(state = TgState.PARTIAL, reason = "not_verified", probe = TgProbeResult(viaApp, null, step, now()), updatedAt = now()) }
            return
        }
        // Ready as soon as Telegram answered through the app's route; the second test only adds a fact.
        update(id) {
            it.copy(state = TgState.READY, reason = null, blockedUntil = 0L, probe = TgProbeResult(true, null, null, now()), updatedAt = now())
        }
        val direct = withTimeoutOrNull(TEST_WINDOW_MS) { runCatching { prober.probe(host, secret, viaApp = false) }.getOrNull() }
        update(id) {
            it.copy(probe = TgProbeResult(true, direct?.ok, if (direct?.ok == false) direct.step else null, now()), updatedAt = now())
        }
    }

    // ------------------------------------------------------------------------ check

    /** Re-checks one service: is it still there, still ours, and does Telegram answer through it. */
    fun check(id: String): Boolean = launchOp(TgOp.CHECK, id) {
        val service = store.get(id) ?: return@launchOp
        val account = accountFor(service) ?: return@launchOp
        // A check that cannot run says so; it does not turn a healthy proxy into a problem.
        val wait = api.blockedUntil(account)
        if (wait > 0) return@launchOp notice("rate_limited", wait)
        setProgress(id, TgOp.CHECK, TgStep.ACCOUNT)
        try {
            val owner = ownerOf(account, service)
            if (owner != service.id) return@launchOp fail(id, TgState.LOST, "not_ours")
            val secret = sealer.open(service.sealedSecret) ?: return@launchOp fail(id, TgState.FAILED, "keystore")
            // A relay uploaded by an older version of the app gets the bundled one first. The
            // address and the secret stay, so the link already in Telegram keeps working.
            val script = code()
            if (service.rev != revOf(script)) {
                setProgress(id, TgOp.CHECK, TgStep.BUILD)
                if (updateRelay(account, service, script, secret)) update(id) { it.copy(rev = revOf(script), updatedAt = now()) }
            }
            var host = service.host
            if (service.method == TgMethod.PAGES) {
                host = pages.hostOf(pages.project(account, service.resource)).ifBlank { host }
                if (host != service.host) update(id) { it.copy(host = host) }
            }
            if (host.isBlank()) return@launchOp fail(id, TgState.PARTIAL, "not_verified")
            selfTest(id, host, secret, attempts = 2, op = TgOp.CHECK)
            // The custom domain is tested on its own: the proxy stays Ready on its Cloudflare
            // address while the domain is still coming up, and the link follows whichever works.
            val domain = store.get(id)?.takeIf { it.state == TgState.READY }?.domain
            if (domain != null) verifyDomain(id, domain.host, secret, announce = !domain.ready)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgApiException) {
            when (e.kind) {
                TgApiException.Kind.NOT_FOUND -> fail(id, TgState.LOST, "gone")
                // A check that could not reach Cloudflare, or was told to wait, says so; it does not
                // turn what was last known to work into a problem.
                TgApiException.Kind.RATE_LIMIT, TgApiException.Kind.NETWORK, TgApiException.Kind.SERVER -> noticeFor(e)
                else -> failApi(id, e, created = true)
            }
        }
    }

    /**
     * Checks the first READY service whose relay is older than the bundled one, which brings it up
     * to date (see [check]). Meant for opening the screen; false when there is none or one is running.
     */
    fun refreshOutdated(): Boolean {
        val rev = runCatching { revOf(code()) }.getOrNull() ?: return false
        val stale = store.current.firstOrNull { s ->
            s.state == TgState.READY && s.rev != rev &&
                accounts().any { it.id == s.accountId && api.blockedUntil(it) == 0L }
        } ?: return false
        return check(stale.id)
    }

    /** Uploads [script] in place of the deployed one. False when a Pages deployment did not finish. */
    private suspend fun updateRelay(account: CloudAccount, service: TgService, script: String, secret: String): Boolean =
        when (service.method) {
            TgMethod.WORKERS -> {
                workers.upload(account, service.resource, script, secret, service.id)
                true
            }
            TgMethod.PAGES -> waitDeployed(account, service.resource, pages.deploy(account, service.resource, script))
        }

    private fun revOf(script: String): String =
        MessageDigest.getInstance("SHA-256").digest(script.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private suspend fun ownerOf(account: CloudAccount, service: TgService): String? =
        if (service.method == TgMethod.WORKERS) workers.owner(account, service.resource) else pages.owner(account, service.resource)

    // ------------------------------------------------------------------------ delete

    /**
     * Deletes the service's resource from Cloudflare and then the record. A resource whose marker is
     * not this service's is left alone. A resource already gone is fine.
     */
    fun delete(id: String): Boolean = launchOp(TgOp.DELETE, id) {
        val service = store.get(id) ?: return@launchOp
        val account = accountFor(service, markLost = false)
        if (account == null) return@launchOp notice("cannot_delete")
        setProgress(id, TgOp.DELETE, TgStep.CLEANUP)
        try {
            val owner = try {
                ownerOf(account, service)
            } catch (e: TgApiException) {
                if (e.kind == TgApiException.Kind.NOT_FOUND) service.id else throw e
            }
            if (owner != service.id) return@launchOp fail(id, TgState.LOST, "not_ours")
            // The domain goes first, so no record of ours is left pointing at nothing.
            service.domain?.let { removeDomain(account, service, it) }
            if (service.method == TgMethod.WORKERS) workers.delete(account, service.resource) else pages.delete(account, service.resource)
            store.update { list -> list.filter { it.id != id } }
            notice("deleted")
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgApiException) {
            if (e.kind == TgApiException.Kind.RATE_LIMIT) notice("rate_limited", e.until) else notice("delete_failed", e.http.toLong())
        }
    }

    /** Drops the record only. For a service whose account is no longer in the app. */
    fun forget(id: String) {
        if (running.get()) return
        store.update { list -> list.filter { it.id != id } }
    }

    // ------------------------------------------------------------------------ custom domain

    /** Loads the sites of this proxy's account into [zones]. */
    fun loadZones(serviceId: String): Boolean = launchOp(TgOp.DOMAIN, serviceId) {
        val service = store.get(serviceId) ?: return@launchOp
        val account = accountFor(service, markLost = false) ?: return@launchOp notice("no_account")
        try {
            _zones.value = domains.zones(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgApiException) {
            _zones.value = emptyList()
            domainNotice(e)
        }
    }

    /**
     * Connects `label.<zone>` to the proxy. Refuses a hostname that already has a DNS record that is
     * not this service's, a site whose nameservers are not switched yet, and a second domain.
     * The result is tested through the new address before it is called ready.
     */
    fun attachDomain(serviceId: String, zone: TgZone, label: String): Boolean = launchOp(TgOp.DOMAIN, serviceId) {
        val service = store.get(serviceId) ?: return@launchOp
        val account = accountFor(service) ?: return@launchOp
        if (service.state != TgState.READY) return@launchOp notice("not_ready")
        val host = TgDomainOps.hostFor(label, zone)?.takeIf { TgLink.isHost(it) } ?: return@launchOp notice("bad_label")
        if (!zone.active) return@launchOp notice("zone_pending")
        val secret = sealer.open(service.sealedSecret) ?: return@launchOp notice("keystore")
        val mine = service.domain?.takeIf { it.host == host }
        if (service.domain != null && mine == null) return@launchOp notice("domain_exists")
        setProgress(serviceId, TgOp.DOMAIN, TgStep.ACCOUNT)
        try {
            if (ownerOf(account, service) != service.id) return@launchOp fail(serviceId, TgState.LOST, "not_ours")
            val existing = domains.records(account, zone.id, host)
            val ours = existing.firstOrNull { it.comment == TgDomainOps.COMMENT + service.id }
            if (existing.isNotEmpty() && ours == null && mine == null) return@launchOp notice("domain_taken")

            setProgress(serviceId, TgOp.DOMAIN, TgStep.BUILD)
            var domainId = mine?.domainId
            var recordId = mine?.recordId ?: ours?.id
            if (service.method == TgMethod.WORKERS) {
                domainId = domains.attachWorker(account, host, service.resource, zone.id) ?: domainId
            } else {
                domains.attachPages(account, service.resource, host)
                if (recordId == null) {
                    recordId = domains.createCname(account, zone.id, host, service.host.ifBlank { "${service.resource}.pages.dev" }, service.id)
                }
            }
            // Recorded as soon as it exists, not yet ready: it can be checked again or removed.
            update(serviceId) { it.copy(domain = TgDomain(host, zone.id, domainId, recordId, ready = false), updatedAt = now()) }

            setProgress(serviceId, TgOp.DOMAIN, TgStep.ACTIVATE)
            if (service.method == TgMethod.PAGES) waitPagesDomain(account, service.resource, host)
            verifyDomain(serviceId, host, secret, announce = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgApiException) {
            domainNotice(e)
        }
    }

    /** Disconnects the custom domain; the Cloudflare address keeps working. */
    fun detachDomain(serviceId: String): Boolean = launchOp(TgOp.DOMAIN, serviceId) {
        val service = store.get(serviceId) ?: return@launchOp
        val domain = service.domain ?: return@launchOp
        val account = accountFor(service, markLost = false) ?: return@launchOp notice("cannot_delete")
        setProgress(serviceId, TgOp.DOMAIN, TgStep.CLEANUP)
        try {
            if (ownerOf(account, service) != service.id) return@launchOp fail(serviceId, TgState.LOST, "not_ours")
            removeDomain(account, service, domain)
            update(serviceId) { it.copy(domain = null, updatedAt = now()) }
            notice("domain_removed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgApiException) {
            domainNotice(e)
        }
    }

    private suspend fun removeDomain(account: CloudAccount, service: TgService, domain: TgDomain) {
        if (service.method == TgMethod.WORKERS) {
            domain.domainId?.let { domains.detachWorker(account, it) }
        } else {
            domains.detachPages(account, service.resource, domain.host)
            // Only the record this service made; anything else on the name is the user's.
            domain.recordId?.let { domains.deleteOurRecord(account, domain.zoneId, it, service.id) }
        }
    }

    /** Pages issues the certificate asynchronously: at most [DEPLOY_POLLS] looks, [DEPLOY_PAUSE_MS] apart. */
    private suspend fun waitPagesDomain(account: CloudAccount, project: String, host: String) {
        repeat(DEPLOY_POLLS) { i ->
            val status = try {
                domains.pagesStatus(account, project, host)
            } catch (e: TgApiException) {
                if (e.kind == TgApiException.Kind.RATE_LIMIT) throw e
                ""
            }
            if (status == "active") return
            if (i < DEPLOY_POLLS - 1) pause(DEPLOY_PAUSE_MS)
        }
    }

    /**
     * The domain is ready only when Telegram answers through it. Until then the link keeps using the
     * Cloudflare address ([TgService.shownHost]); a ready domain that stops answering falls back the
     * same way.
     */
    private suspend fun verifyDomain(id: String, host: String, secret: String, announce: Boolean) {
        setProgress(id, TgOp.DOMAIN, TgStep.TEST)
        val ok = withTimeoutOrNull(TEST_WINDOW_MS) {
            for (i in 0 until TEST_ATTEMPTS) {
                if (prober.probe(host, secret, viaApp = true).ok) return@withTimeoutOrNull true
                if (i < TEST_ATTEMPTS - 1) pause(TEST_PAUSE_MS * (i + 1))
            }
            false
        } == true
        if (!ok) {
            val was = store.get(id)?.domain?.ready == true
            update(id) { s -> s.copy(domain = s.domain?.copy(ready = false), updatedAt = now()) }
            if (announce || was) notice("domain_pending")
            return
        }
        update(id) { s -> s.copy(domain = s.domain?.copy(ready = true), probe = TgProbeResult(true, null, null, now()), updatedAt = now()) }
        val direct = withTimeoutOrNull(TEST_WINDOW_MS) { runCatching { prober.probe(host, secret, viaApp = false) }.getOrNull() }
        update(id) { s -> s.copy(probe = TgProbeResult(true, direct?.ok, if (direct?.ok == false) direct.step else null, now()), updatedAt = now()) }
        if (announce) notice("domain_ok")
    }

    private fun domainNotice(e: TgApiException) {
        when (e.kind) {
            TgApiException.Kind.PERMISSION -> notice("domain_permission")
            TgApiException.Kind.CONFLICT -> notice("domain_taken")
            else -> noticeFor(e)
        }
    }

    // ------------------------------------------------------------------------ account scan

    /**
     * Lists what this feature made in the account that the app has no record of (a reinstall, a
     * lost state file), so it can be seen and deleted instead of lingering unseen.
     */
    fun scan(accountId: String): Boolean = launchOp(TgOp.CHECK, null) {
        val account = accounts().firstOrNull { it.id == accountId } ?: return@launchOp notice("no_account")
        try {
            val known = store.current.map { it.resource }.toSet()
            val found = workers.names(account).filter { it !in known }.map { TgOrphan(accountId, TgMethod.WORKERS, it) } +
                pages.names(account).filter { it !in known }.map { TgOrphan(accountId, TgMethod.PAGES, it) }
            _orphans.value = _orphans.value.filter { it.accountId != accountId } + found
            notice("scanned", found.size.toLong())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgApiException) {
            noticeFor(e)
        }
    }

    /** Deletes one orphan, only when it carries this feature's marker. */
    fun deleteOrphan(orphan: TgOrphan): Boolean = launchOp(TgOp.DELETE, null) {
        val account = accounts().firstOrNull { it.id == orphan.accountId } ?: return@launchOp notice("no_account")
        try {
            val owner = if (orphan.method == TgMethod.WORKERS) workers.owner(account, orphan.resource) else pages.owner(account, orphan.resource)
            if (owner.isNullOrBlank()) return@launchOp notice("not_ours")
            if (orphan.method == TgMethod.WORKERS) workers.delete(account, orphan.resource) else pages.delete(account, orphan.resource)
            _orphans.value = _orphans.value.filter { it != orphan }
            notice("deleted")
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgApiException) {
            if (e.kind == TgApiException.Kind.NOT_FOUND) _orphans.value = _orphans.value.filter { it != orphan } else noticeFor(e)
        }
    }

    // ------------------------------------------------------------------------ plumbing

    /** The account a service belongs to, or null (and, if [markLost], LOST) when it cannot be used. */
    private fun accountFor(service: TgService, markLost: Boolean = true): CloudAccount? {
        val account = accounts().firstOrNull { it.id == service.accountId }
        if (account == null) {
            if (markLost) fail(service.id, TgState.LOST, "no_account")
            return null
        }
        // Never act on a resource through an account that is not the one it was made in.
        if (account.accountId != service.cfAccountId) {
            if (markLost) fail(service.id, TgState.LOST, "other_account")
            return null
        }
        return account
    }

    private fun launchOp(op: TgOp, serviceId: String?, block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        _progress.value = TgProgress(serviceId, op, TgStep.ACCOUNT)
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("unexpected")
            } finally {
                _progress.value = null
                running.set(false)
            }
        }
        return true
    }

    private fun setProgress(id: String?, op: TgOp, step: TgStep) {
        _progress.value = TgProgress(id, op, step)
    }

    private fun update(id: String, change: (TgService) -> TgService) = store.put(id, change)

    private fun fail(id: String, state: TgState, reason: String, until: Long = 0L) {
        update(id) { it.copy(state = state, reason = reason, blockedUntil = until, updatedAt = now()) }
    }

    private fun failApi(id: String, e: TgApiException, created: Boolean) {
        val resumable = if (created) TgState.PARTIAL else TgState.FAILED
        when (e.kind) {
            TgApiException.Kind.RATE_LIMIT -> fail(id, TgState.ACTION, "rate_limited", until = e.until)
            TgApiException.Kind.PERMISSION -> fail(id, TgState.ACTION, "no_permission" + (e.cf?.let { ":$it" } ?: ""))
            TgApiException.Kind.AUTH -> fail(id, TgState.LOST, "auth")
            TgApiException.Kind.NETWORK -> fail(id, resumable, "network")
            TgApiException.Kind.SERVER -> fail(id, resumable, "server:${e.http}")
            else -> fail(id, resumable, "rejected:${e.http}" + (e.cf?.let { ":$it" } ?: ""))
        }
    }

    private fun noticeFor(e: TgApiException) {
        when (e.kind) {
            TgApiException.Kind.RATE_LIMIT -> notice("rate_limited", e.until)
            TgApiException.Kind.PERMISSION -> notice("no_permission")
            TgApiException.Kind.AUTH -> notice("auth")
            TgApiException.Kind.NETWORK -> notice("network")
            else -> notice("failed", e.http.toLong())
        }
    }

    private fun notice(code: String, arg: Long = 0L) {
        _notice.value = TgNotice(code, arg)
    }

    companion object {
        /** Services per account and method. A cap, not a target: the app is not a way to mass-produce proxies. */
        const val MAX_PER_METHOD = 3

        /** Leftovers of this feature in an account tolerated beyond the cap before refusing to add more. */
        private const val SPARE = 2

        /** States a build can continue from; LOST ones (gone, not ours, wrong account) are never rebuilt in place. */
        private val RESUMABLE = setOf(TgState.BUILDING, TgState.PARTIAL, TgState.FAILED, TgState.ACTION)

        const val DEPLOY_POLLS = 10
        const val DEPLOY_PAUSE_MS = 3_000L
        const val TEST_ATTEMPTS = 6
        const val TEST_PAUSE_MS = 4_000L
        const val TEST_WINDOW_MS = 90_000L
    }
}
