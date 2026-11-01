package com.mlmvpn.scanner.engines.tgproxy

import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The manager against a Cloudflare that is a real HTTP server on localhost: real requests, real
 * multipart, real status codes. What is checked is what matters to the user -- no duplicate or
 * foreign resources, honest states, rate limits obeyed, and no credential or secret anywhere it
 * should not be.
 */
class TgManagerTest {

    private val token = "tok_SUPER_SECRET_ABCDEFGH_0123456789"
    private val email = "owner@example.com"
    private val account = CloudAccount(id = "a1", token = token, email = email, name = "Owner", accountId = "ACC1", status = "active", addedAt = "")

    private lateinit var cf: FakeCloudflare
    private lateinit var cloud: FakeAccount
    private lateinit var store: TgStore
    private lateinit var scope: CoroutineScope
    private lateinit var dir: java.io.File

    private var clock = 1_000_000_000L
    private val pauses = ArrayList<Long>()
    private val probes = ArrayList<Triple<String, String, Boolean>>()
    private var probeOk = true
    private val failHosts = HashSet<String>()
    private var availableAccounts: List<CloudAccount> = listOf(account)
    private var subdomainCreator: suspend (CloudAccount) -> Boolean = { false }
    private var keystoreWorks = true

    private val sealer = object : TgSealer {
        override fun seal(plain: String): String? = if (keystoreWorks) "s:" + plain.reversed() else null
        override fun open(sealed: String): String? = if (sealed.startsWith("s:")) sealed.removePrefix("s:").reversed() else null
    }

    @Before fun setUp() {
        cf = FakeCloudflare()
        cloud = FakeAccount()
        cf.handler = cloud::handle
        dir = Files.createTempDirectory("tgp").toFile()
        store = TgStore(dir)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        cf.close()
        dir.deleteRecursively()
    }

    private fun manager(): TgProxyManager = TgProxyManager(
        store = store,
        api = TgCfApi(OkHttpClient(), base = cf.base, now = { clock }, pause = { pauses += it }),
        prober = { host, secret, viaApp ->
            synchronized(probes) { probes += Triple(host, secret, viaApp) }
            val ok = probeOk && host !in failHosts
            TgProbeOutcome(ok, if (ok) null else "telegram", 3)
        },
        accounts = { availableAccounts },
        code = { "export default { fetch() { return new Response('x') } }" },
        sealer = sealer,
        scope = scope,
        createSubdomain = { subdomainCreator(it) },
        now = { clock },
        pause = { pauses += it },
    )

    private fun await(m: TgProxyManager) {
        val end = System.currentTimeMillis() + 15_000
        while (!m.idle) {
            if (System.currentTimeMillis() > end) throw AssertionError("operation did not finish")
            Thread.sleep(5)
        }
    }

    private fun only(): TgService = store.current.single()

    private fun secretOf(s: TgService) = sealer.open(s.sealedSecret)!!

    /** Everything a user, a log or a report could ever see of the state. */
    private fun visible(m: TgProxyManager): String =
        TgCodec.encode(store.current) + dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() } +
            m.notice.value + store.current.joinToString { "${it.reason} ${it.state}" }

    private fun assertNoLeak(m: TgProxyManager) {
        val text = visible(m)
        assertFalse("token leaked", text.contains(token))
        assertFalse("email leaked", text.contains(email))
        store.current.forEach { assertFalse("plain secret stored", text.contains(secretOf(it))) }
        store.current.forEach { s -> cf.requests.forEach { assertFalse("secret in a URL", it.path.contains(secretOf(s))) } }
    }

    private fun readyService(id: String, method: TgMethod = TgMethod.WORKERS, resource: String = TgService.resourceName(id)) = TgService(
        id = id, method = method, accountId = "a1", cfAccountId = "ACC1", resource = resource, host = "$resource.mysub.workers.dev", domain = null,
        sealedSecret = sealer.seal(TgLink.newSecret())!!, state = TgState.READY, reason = null, blockedUntil = 0, createdAt = clock, updatedAt = clock, probe = null,
    )

    // ------------------------------------------------------------------------------------ create

    @Test fun workersBuildEndsReadyAfterTelegramAnswersThroughIt() {
        val m = manager()
        assertTrue(m.create(TgMethod.WORKERS, "a1"))
        await(m)
        val s = only()
        assertEquals(TgState.READY, s.state)
        assertNull(s.reason)
        assertEquals("ACC1", s.cfAccountId)
        assertEquals("${s.resource}.mysub.workers.dev", s.host)
        assertTrue(s.resource.startsWith("tgp-"))

        val script = cloud.scripts.getValue(s.resource)
        assertEquals("the ownership marker is the service id", s.id, script.owner)
        assertEquals("the relay got the same secret the link will carry", secretOf(s), script.secret)
        assertTrue(script.enabled)
        assertTrue(TgLink.isSecret(secretOf(s)))

        assertEquals("tested through the app, then through the phone's network", listOf(true, false), probes.map { it.third })
        assertTrue(probes.all { it.first == s.host && it.second == secretOf(s) })
        assertEquals(true, s.probe!!.viaApp)
        assertEquals(true, s.probe!!.direct)

        assertTrue("every call carried the credential", cf.requests.all { it.headers["authorization"] == "Bearer $token" })
        assertNull("progress is cleared", m.progress.value)
        assertNoLeak(m)
    }

    @Test fun pagesBuildCreatesTheProjectDeploysAndWaitsForTheDeploy() {
        val m = manager()
        assertTrue(m.create(TgMethod.PAGES, "a1"))
        await(m)
        val s = only()
        assertEquals(TgState.READY, s.state)
        assertEquals("${s.resource}.pages.dev", s.host)
        val project = cloud.projects.getValue(s.resource)
        assertEquals(s.id, project.owner)
        assertEquals(secretOf(s), project.secret)
        assertEquals(1, project.deployments)
        assertNoLeak(m)
    }

    @Test fun aFailedPagesDeployIsPartialNotReady() {
        cloud.deployStatus = "failure"
        val m = manager()
        m.create(TgMethod.PAGES, "a1")
        await(m)
        assertEquals(TgState.PARTIAL, only().state)
        assertEquals("deploy_failed", only().reason)
        assertTrue("no self-test of something that did not deploy", probes.isEmpty())
    }

    @Test fun aSecondTapWhileBuildingIsRefusedAndCreatesNothingExtra() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        cf.gate = {
            if (cf.requests.last().method == "PUT") {
                inside.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val m = manager()
        assertTrue(m.create(TgMethod.WORKERS, "a1"))
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        assertFalse("second tap", m.create(TgMethod.WORKERS, "a1"))
        assertFalse("another kind meanwhile", m.create(TgMethod.PAGES, "a1"))
        assertFalse("a delete meanwhile", m.delete(store.current.single().id))
        release.countDown()
        await(m)
        assertEquals(1, store.current.size)
        assertEquals(1, cloud.scripts.size)
        assertTrue(cloud.projects.isEmpty())
    }

    @Test fun cancelMarksItPartialAndTheNextTapResumesTheSameService() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        cf.gate = {
            if (cf.requests.last().method == "PUT") {
                inside.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        m.cancel()
        await(m)
        val id = only().id
        assertEquals(TgState.PARTIAL, only().state)
        assertEquals("cancelled", only().reason)
        release.countDown()
        cf.gate = null

        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals("resumed, not duplicated", 1, store.current.size)
        assertEquals(id, only().id)
        assertEquals(TgState.READY, only().state)
        assertEquals(1, cloud.scripts.size)
    }

    @Test fun anInterruptedBuildFoundAfterARestartBecomesPartial() {
        store.update { it + readyService("aa11bb22").copy(state = TgState.BUILDING) }
        val m = manager()
        m.recover()
        assertEquals(TgState.PARTIAL, only().state)
        assertEquals("interrupted", only().reason)
    }

    @Test fun neverMoreThanThreePerMethodAndPerAccount() {
        store.update { listOf(readyService("00000001"), readyService("00000002"), readyService("00000003")) }
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals(3, store.current.size)
        assertEquals(TgNotice("limit", 3), m.notice.value)
        assertTrue("not a single request was made", cf.requests.isEmpty())
        // the other product is a separate allowance
        m.create(TgMethod.PAGES, "a1")
        await(m)
        assertEquals(4, store.current.size)
    }

    @Test fun anUnfinishedServiceIsRebuiltInPlaceNotAddedBeside() {
        store.update { listOf(readyService("00000001").copy(state = TgState.FAILED, reason = "network", host = "")) }
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals(1, store.current.size)
        assertEquals("00000001", only().id)
        assertEquals(TgState.READY, only().state)
    }

    @Test fun anAccountWithNoWorkersSubdomainNeedsTheUserUnlessTheAppMayMakeIt() {
        cloud.subdomain = null
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals(TgState.ACTION, only().state)
        assertEquals("no_subdomain", only().reason)
        assertTrue("nothing was uploaded", cloud.scripts.isEmpty())

        subdomainCreator = { cloud.subdomain = "fresh"; true }
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals(TgState.READY, only().state)
        assertTrue(only().host.endsWith(".fresh.workers.dev"))
    }

    @Test fun theSecretIsNeverCreatedWhenItCannotBeStoredSafely() {
        keystoreWorks = false
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertTrue(store.current.isEmpty())
        assertEquals("keystore", m.notice.value!!.code)
        assertTrue(cf.requests.isEmpty())
    }

    @Test fun anUnverifiedBuildIsPartialAfterABoundedNumberOfTries() {
        probeOk = false
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals(TgState.PARTIAL, only().state)
        assertEquals("not_verified", only().reason)
        assertEquals(false, only().probe!!.viaApp)
        assertEquals("telegram", only().probe!!.step)
        assertEquals("six tries, no endless loop", TgProxyManager.TEST_ATTEMPTS, probes.size)
        assertTrue("never claims the phone-network test when the first failed", probes.all { it.third })
        assertEquals(listOf(4_000L, 8_000L, 12_000L, 16_000L, 20_000L), pauses.filter { it >= 4_000 })
    }

    @Test fun theCloudflareAccountMustBeTheRecordedOne() {
        store.update { listOf(readyService("00000001")) }
        availableAccounts = listOf(account.copy(accountId = "ACC2"))
        val m = manager()
        m.check("00000001")
        await(m)
        assertEquals(TgState.LOST, only().state)
        assertEquals("other_account", only().reason)
        assertTrue(cf.requests.isEmpty())
        m.delete("00000001")
        await(m)
        assertEquals("not deleted through the wrong account", 1, store.current.size)
        assertTrue(cf.requests.isEmpty())
    }

    // ------------------------------------------------------------------------------------ errors

    private fun cfError(status: Int, code: Int, headers: Map<String, String> = emptyMap()) =
        FakeCloudflare.Res(status, """{"success":false,"errors":[{"code":$code,"message":"Authentication error for $token and $email"}]}""", headers)

    @Test fun rateLimitIsObeyedUntilTheTimeCloudflareSaid() {
        var limited = true
        cf.handler = { r -> if (limited) cfError(429, 971, mapOf("Retry-After" to "120")) else cloud.handle(r) }
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        val until = clock + 120_000
        assertEquals(TgState.ACTION, only().state)
        assertEquals("rate_limited", only().reason)
        assertEquals(until, only().blockedUntil)
        assertEquals(until, m.blockedUntil(only()))
        assertEquals(1, cf.requests.size)

        // Tapping again before the time sends nothing at all.
        repeat(3) { m.create(TgMethod.WORKERS, "a1"); await(m) }
        m.check(only().id); await(m)
        assertEquals("no request while blocked", 1, cf.requests.size)
        assertEquals("rate_limited", only().reason)

        // After the time, with Cloudflare willing again, the same service is finished.
        clock = until + 1
        limited = false
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals(1, store.current.size)
        assertEquals(TgState.READY, only().state)
        assertNoLeak(m)
    }

    @Test fun aRateLimitWithoutRetryAfterBlocksForFiveMinutes() {
        cf.handler = { cfError(429, 971) }
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals(clock + 5 * 60_000L, only().blockedUntil)
    }

    @Test fun aMissingPermissionAsksTheUserAndCreatesNothing() {
        cf.handler = { cfError(403, 10000) }
        val m = manager()
        m.create(TgMethod.PAGES, "a1")
        await(m)
        assertEquals(TgState.ACTION, only().state)
        assertEquals("no_permission:10000", only().reason)
        assertEquals("one listing call, then it stopped", 1, cf.requests.size)
        assertNoLeak(m)
    }

    @Test fun aRejectedCredentialMakesTheServiceLostNotHealthy() {
        cf.handler = { cfError(401, 1000) }
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals(TgState.LOST, only().state)
        assertEquals("auth", only().reason)
        assertNoLeak(m)
    }

    @Test fun serverErrorsAreRetriedAFewTimesThenReportedAsPartial() {
        var failing = 0
        cf.handler = { r ->
            if (r.method == "PUT") {
                failing++
                FakeCloudflare.Res(503, "<html>down</html>")
            } else cloud.handle(r)
        }
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        assertEquals("the upload was tried once and retried twice", 3, failing)
        assertEquals(TgState.PARTIAL, only().state)
        assertEquals("server:503", only().reason)
        assertEquals(listOf(1_000L, 3_000L), pauses)
    }

    // ------------------------------------------------------------------------------------ check

    private fun built(method: TgMethod = TgMethod.WORKERS): TgProxyManager {
        val m = manager()
        m.create(method, "a1")
        await(m)
        assertEquals(TgState.READY, only().state)
        probes.clear()
        cf.requests.clear()
        return m
    }

    @Test fun checkConfirmsAHealthyServiceAndTestsItAgain() {
        val m = built()
        m.check(only().id)
        await(m)
        assertEquals(TgState.READY, only().state)
        assertEquals(listOf(true, false), probes.map { it.third })
        assertTrue(cf.requests.all { it.method == "GET" })
    }

    @Test fun checkNoticesAResourceDeletedFromTheDashboard() {
        val m = built()
        cloud.scripts.clear()
        m.check(only().id)
        await(m)
        assertEquals(TgState.LOST, only().state)
        assertEquals("gone", only().reason)
        assertTrue("no claim of health: nothing was tested", probes.isEmpty())
    }

    @Test fun checkRefusesAResourceThatIsNotOurs() {
        val m = built()
        val s = only()
        cloud.scripts[s.resource] = FakeAccount.Script(owner = "someone-else", secret = null, enabled = true)
        m.check(s.id)
        await(m)
        assertEquals(TgState.LOST, only().state)
        assertEquals("not_ours", only().reason)
        assertTrue(probes.isEmpty())
    }

    @Test fun checkOnAServiceWhoseSelfTestFailsIsNotReady() {
        val m = built()
        probeOk = false
        m.check(only().id)
        await(m)
        assertEquals(TgState.PARTIAL, only().state)
        assertEquals("not_verified", only().reason)
    }

    @Test fun aCheckThatCannotReachCloudflareKeepsWhatWasLastKnown() {
        val m = built()
        cf.handler = { FakeCloudflare.Res(503, "<html>down</html>") }
        m.check(only().id)
        await(m)
        assertEquals(TgState.READY, only().state)
        assertEquals("failed", m.notice.value!!.code)
        assertTrue("the failed calls were retried a bounded number of times", cf.requests.size in 1..3)
    }

    @Test fun checkTakesAnUpdatedPagesHost() {
        val m = built(TgMethod.PAGES)
        val s = only()
        cloud.projects[s.resource] = FakeAccount.Project(s.id, "x", "renamed.pages.dev")
        m.check(s.id)
        await(m)
        assertEquals("renamed.pages.dev", only().host)
    }

    @Test fun checkPutsTheBundledRelayOnAServiceBuiltByAnOlderVersion() {
        val m = built()
        val before = only()
        assertTrue("a build records which relay it uploaded", before.rev.isNotEmpty())
        store.put(before.id) { it.copy(rev = "older") }
        m.check(before.id)
        await(m)
        val after = only()
        assertEquals(before.rev, after.rev)
        assertEquals(TgState.READY, after.state)
        assertEquals("the address stays, so Telegram's link keeps working", before.host, after.host)
        assertEquals(1, cf.requests.count { it.method == "PUT" && it.path.endsWith("/workers/scripts/${before.resource}") })
        val script = cloud.scripts.getValue(before.resource)
        assertEquals("the same secret goes up again", secretOf(before), script.secret)
        assertEquals(before.id, script.owner)
        assertTrue("still on workers.dev", script.enabled)
        assertEquals(listOf(true, false), probes.map { it.third })
    }

    @Test fun checkRedeploysAnOlderPagesRelay() {
        val m = built(TgMethod.PAGES)
        val s = only()
        val deployments = cloud.projects.getValue(s.resource).deployments
        store.put(s.id) { it.copy(rev = "older") }
        m.check(s.id)
        await(m)
        assertEquals(deployments + 1, cloud.projects.getValue(s.resource).deployments)
        assertEquals(s.rev, only().rev)
        assertEquals(TgState.READY, only().state)
    }

    @Test fun openingTheScreenUpdatesOnlyAnOutdatedReadyService() {
        val m = built()
        assertFalse("nothing to do while the relay is current", m.refreshOutdated())
        assertTrue(cf.requests.isEmpty())
        val s = only()
        store.put(s.id) { it.copy(rev = "older", state = TgState.PARTIAL) }
        assertFalse("an unfinished service is the user's to continue", m.refreshOutdated())
        store.put(s.id) { it.copy(state = TgState.READY) }
        assertTrue(m.refreshOutdated())
        await(m)
        assertEquals(s.rev, only().rev)
        assertFalse(m.refreshOutdated())
    }

    // ------------------------------------------------------------------------------------ delete

    @Test fun deleteRemovesTheResourceThenTheRecord() {
        val m = built()
        val s = only()
        assertTrue(m.delete(s.id))
        await(m)
        assertEquals(listOf(s.resource), cloud.deletes)
        assertTrue(cloud.scripts.isEmpty())
        assertTrue(store.current.isEmpty())
    }

    @Test fun deleteLeavesAResourceItDoesNotOwn() {
        val m = built()
        val s = only()
        cloud.scripts[s.resource] = FakeAccount.Script(owner = "someone-else", secret = null)
        m.delete(s.id)
        await(m)
        assertTrue("nothing deleted", cloud.deletes.isEmpty())
        assertEquals(1, cloud.scripts.size)
        assertEquals("not_ours", only().reason)
    }

    @Test fun deleteOfAnAlreadyGoneResourceStillClearsTheRecord() {
        val m = built(TgMethod.PAGES)
        val s = only()
        cloud.projects.clear()
        m.delete(s.id)
        await(m)
        assertTrue(store.current.isEmpty())
    }

    @Test fun deleteWithoutTheAccountExplainsAndForgetDropsTheRecordOnly() {
        val m = built()
        val s = only()
        availableAccounts = emptyList()
        m.delete(s.id)
        await(m)
        assertEquals("cannot_delete", m.notice.value!!.code)
        assertEquals(1, store.current.size)
        m.forget(s.id)
        assertTrue(store.current.isEmpty())
        assertEquals("the resource itself was not touched", 1, cloud.scripts.size)
    }

    @Test fun aRateLimitDuringDeleteKeepsTheRecordAndSaysWhen() {
        val m = built()
        cf.handler = { cfError(429, 971, mapOf("Retry-After" to "60")) }
        m.delete(only().id)
        await(m)
        assertEquals(1, store.current.size)
        assertEquals("a healthy proxy is not turned into a problem by a delete that could not run", TgState.READY, only().state)
        assertEquals(TgNotice("rate_limited", clock + 60_000), m.notice.value)
    }

    @Test fun aCheckThatCannotRunBecauseOfTheLimitSaysSoAndChangesNothing() {
        val m = built()
        cf.handler = { cfError(429, 971, mapOf("Retry-After" to "90")) }
        m.check(only().id)
        await(m)
        assertEquals(TgState.READY, only().state)
        assertEquals(TgNotice("rate_limited", clock + 90_000), m.notice.value)
        val before = cf.requests.size
        m.consumeNotice()
        m.check(only().id)
        await(m)
        assertEquals("blocked: nothing is sent", before, cf.requests.size)
        assertEquals(TgState.READY, only().state)
        assertEquals("rate_limited", m.notice.value!!.code)
    }

    // ------------------------------------------------------------------------------------ orphans

    @Test fun scanFindsThingsTheAppHasNoRecordOfAndDeletesOnlyMarkedOnes() {
        val m = built()
        cloud.scripts["tgp-deadbeef"] = FakeAccount.Script(owner = "deadbeef", secret = null)
        cloud.scripts["tgp-unmarked"] = FakeAccount.Script(owner = null, secret = null)
        cloud.scripts["somebody-elses-worker"] = FakeAccount.Script(owner = null, secret = null)
        cloud.projects["tgp-pagesorph"] = FakeAccount.Project("pagesorph", null, "tgp-pagesorph.pages.dev")
        m.scan("a1")
        await(m)
        val names = m.orphans.value.map { it.resource }.toSet()
        assertEquals(setOf("tgp-deadbeef", "tgp-unmarked", "tgp-pagesorph"), names)

        m.deleteOrphan(m.orphans.value.first { it.resource == "tgp-unmarked" })
        await(m)
        assertTrue("no marker, so not ours: kept", cloud.scripts.containsKey("tgp-unmarked"))
        m.deleteOrphan(m.orphans.value.first { it.resource == "tgp-deadbeef" })
        await(m)
        assertFalse(cloud.scripts.containsKey("tgp-deadbeef"))
        assertTrue("the user's own worker was never listed or touched", cloud.scripts.containsKey("somebody-elses-worker"))
        assertNotNull(store.get(only().id))
    }

    @Test fun noticesAreConsumable() {
        val m = manager()
        m.create(TgMethod.WORKERS, "nobody")
        await(m)
        assertEquals("no_account", m.notice.value!!.code)
        m.consumeNotice()
        assertNull(m.notice.value)
    }

    // ------------------------------------------------------------------------------------ domains

    private fun zone(name: String = "example.com", status: String = "active") = FakeAccount.Zone("z-$name", name, status)

    /** A ready proxy with a zone on its account, its zones loaded. */
    private fun withZone(method: TgMethod = TgMethod.WORKERS, vararg zones: FakeAccount.Zone): TgProxyManager {
        cloud.zones += if (zones.isEmpty()) listOf(zone()) else zones.toList()
        val m = built(method)
        m.loadZones(only().id)
        await(m)
        return m
    }

    private fun attach(m: TgProxyManager, label: String = "tg", zone: TgZone = m.zones.value.first()) {
        assertTrue(m.attachDomain(only().id, zone, label))
        await(m)
    }

    private fun domainCalls() = cf.requests.filter { "/domains" in it.path || "/dns_records" in it.path }

    @Test fun sitesAreListedActiveFirstWithTheirNameservers() {
        val m = withZone(TgMethod.WORKERS, zone("b.com", "pending"), zone("a.com"))
        assertEquals(listOf("a.com", "b.com"), m.zones.value.map { it.name })
        assertTrue(m.zones.value[0].active)
        assertFalse(m.zones.value[1].active)
        assertEquals(listOf("ada.ns.cloudflare.com", "bob.ns.cloudflare.com"), m.zones.value[1].nameServers)
    }

    @Test fun aTokenWithoutZoneRightsIsToldWhatToUseInstead() {
        val m = built()
        cf.handler = { r -> if (r.path.endsWith("/zones")) cfError(403, 10000) else cloud.handle(r) }
        m.loadZones(only().id)
        await(m)
        assertEquals("domain_permission", m.notice.value!!.code)
        assertTrue(m.zones.value.isEmpty())
        assertNoLeak(m)
    }

    @Test fun aWorkersDomainIsAttachedTestedAndThenUsedForTheLink() {
        val m = withZone()
        val s = only()
        attach(m)
        val d = only().domain!!
        assertEquals("tg.example.com", d.host)
        assertTrue("answered through the new address", d.ready)
        assertEquals("z-example.com", d.zoneId)
        assertEquals("the link follows the domain", "tg.example.com", only().shownHost)
        assertEquals("and the Cloudflare address is untouched", s.host, only().host)

        val put = cf.requests.last { it.method == "PUT" && it.path.endsWith("/workers/domains") }
        val body = org.json.JSONObject(String(put.body))
        assertEquals("tg.example.com", body.getString("hostname"))
        assertEquals(s.resource, body.getString("service"))
        assertEquals("z-example.com", body.getString("zone_id"))
        assertEquals("production", body.getString("environment"))
        assertTrue("Cloudflare makes the DNS record itself", cloud.records.isEmpty())
        assertEquals("domain_ok", m.notice.value!!.code)
        assertTrue(probes.any { it.first == "tg.example.com" && it.third })
        assertEquals(true, only().probe!!.direct)
        assertNoLeak(m)
    }

    @Test fun aPagesDomainGetsOurProxiedCnameAndWaitsForTheCertificate() {
        cloud.pageDomainStatus = "active"
        val m = withZone(TgMethod.PAGES)
        val s = only()
        attach(m)
        val d = only().domain!!
        assertTrue(d.ready)
        assertNotNull(d.recordId)
        val record = cloud.records.single()
        assertEquals("CNAME", record.type)
        assertEquals("tg.example.com", record.name)
        assertEquals("${s.resource}.pages.dev", record.content)
        assertTrue(record.proxied)
        assertEquals("the record is marked as this service's", "mlmvpn-tgp:${s.id}", record.comment)
        assertEquals("active", cloud.pageDomains.getValue(s.resource)["tg.example.com"])
    }

    @Test fun aNameThatAlreadyHasARecordIsLeftAlone() {
        val m = withZone()
        cloud.records += FakeAccount.Record("r-user", "tg.example.com", "A", "1.2.3.4", "")
        cf.requests.clear()
        attach(m)
        assertEquals("domain_taken", m.notice.value!!.code)
        assertNull(only().domain)
        assertTrue("nothing was attached", cf.requests.none { it.method != "GET" })
        assertEquals("the user's record is untouched", 1, cloud.records.size)
    }

    @Test fun aSiteWhoseNameserversAreNotSwitchedYetIsRefused() {
        val m = withZone(TgMethod.WORKERS, zone("new.com", "pending"))
        cf.requests.clear()
        attach(m)
        assertEquals("zone_pending", m.notice.value!!.code)
        assertTrue(cf.requests.isEmpty())
    }

    @Test fun badSubdomainNamesNeverReachCloudflare() {
        val m = withZone()
        cf.requests.clear()
        for (label in listOf("", " ", "a b", "-x", "x-", "a_b", "a.b", "tg/evil", "a".repeat(64))) {
            attach(m, label)
            assertEquals("bad_label for '$label'", "bad_label", m.notice.value!!.code)
            m.consumeNotice()
        }
        assertTrue(cf.requests.isEmpty())
        assertNull(only().domain)
    }

    @Test fun aDomainThatDoesNotAnswerYetIsKeptButNotUsedUntilItDoes() {
        val m = withZone()
        val s = only()
        failHosts += "tg.example.com"
        probes.clear()
        attach(m)
        assertEquals("domain_pending", m.notice.value!!.code)
        assertFalse(only().domain!!.ready)
        assertEquals("the link stays on the Cloudflare address", s.host, only().shownHost)
        assertEquals(TgState.READY, only().state)
        assertEquals(TgProxyManager.TEST_ATTEMPTS, probes.count { it.first == "tg.example.com" })

        failHosts.clear()
        m.consumeNotice()
        m.check(s.id)
        await(m)
        assertTrue(only().domain!!.ready)
        assertEquals("tg.example.com", only().shownHost)
        assertEquals("domain_ok", m.notice.value!!.code)
    }

    @Test fun aReadyDomainThatStopsAnsweringFallsBackToTheCloudflareAddress() {
        val m = withZone()
        val s = only()
        attach(m)
        assertEquals("tg.example.com", only().shownHost)
        failHosts += "tg.example.com"
        m.check(s.id)
        await(m)
        assertEquals(TgState.READY, only().state)
        assertFalse(only().domain!!.ready)
        assertEquals(s.host, only().shownHost)
    }

    @Test fun onlyOneDomainPerProxy() {
        val m = withZone()
        attach(m, "tg")
        m.consumeNotice()
        attach(m, "other")
        assertEquals("domain_exists", m.notice.value!!.code)
        assertEquals("tg.example.com", only().domain!!.host)
        assertEquals(1, cloud.workerDomains.size)
    }

    @Test fun removingAWorkersDomainDetachesItAndKeepsTheProxy() {
        val m = withZone()
        attach(m)
        val id = only().domain!!.domainId!!
        assertTrue(m.detachDomain(only().id))
        await(m)
        assertTrue(cloud.workerDomains.isEmpty())
        assertTrue(cloud.deletes.contains("workersdomain:$id"))
        assertNull(only().domain)
        assertEquals(TgState.READY, only().state)
        assertEquals("domain_removed", m.notice.value!!.code)
    }

    @Test fun removingAPagesDomainDeletesOnlyTheRecordWeMade() {
        val m = withZone(TgMethod.PAGES)
        attach(m)
        val s = only()
        val ours = s.domain!!.recordId!!
        m.detachDomain(s.id)
        await(m)
        assertTrue(cloud.deletes.contains("pagesdomain:tg.example.com"))
        assertTrue(cloud.deletes.contains("record:$ours"))
        assertNull(only().domain)

        // A record that someone re-pointed to their own purposes (our comment is gone) is kept.
        attach(m)
        val again = only().domain!!.recordId!!
        cloud.records.removeAll { it.id == again }
        cloud.records += FakeAccount.Record(again, "tg.example.com", "CNAME", "elsewhere.example.net", "mine now")
        cloud.deletes.clear()
        m.detachDomain(s.id)
        await(m)
        assertFalse(cloud.deletes.contains("record:$again"))
        assertEquals(1, cloud.records.size)
        assertNull(only().domain)
    }

    @Test fun deletingAProxyRemovesItsDomainBeforeTheProxy() {
        val m = withZone(TgMethod.PAGES)
        attach(m)
        val s = only()
        m.delete(s.id)
        await(m)
        assertTrue(store.current.isEmpty())
        val order = cloud.deletes
        assertTrue(order.indexOf("pagesdomain:tg.example.com") in 0 until order.indexOf(s.resource))
        assertTrue(order.indexOf("record:${cloud.deletes.first { it.startsWith("record:") }.removePrefix("record:")}") in 0 until order.indexOf(s.resource))
        assertTrue(cloud.records.isEmpty())
    }

    @Test fun aRateLimitDuringAnAttachLeavesNothingHalfRecorded() {
        val m = withZone()
        cf.handler = { r -> if (r.path.endsWith("/workers/domains")) cfError(429, 971, mapOf("Retry-After" to "30")) else cloud.handle(r) }
        attach(m)
        assertEquals("rate_limited", m.notice.value!!.code)
        assertNull(only().domain)
    }

    @Test fun threadsAreNotLeftRunningAfterAnOperation() {
        val counter = AtomicInteger()
        val m = manager()
        m.create(TgMethod.WORKERS, "a1")
        await(m)
        counter.incrementAndGet()
        assertTrue(m.idle)
        assertNull(m.progress.value)
    }
}
