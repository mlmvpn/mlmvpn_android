package com.mlmvpn.scanner.engines.windscribe

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.mlmvpn.core.warp.AwgHostClient
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.engines.amnezia.AmzConnect
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.amnezia.AmzParser
import com.mlmvpn.scanner.engines.amnezia.AmzServer
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.openvpn.*
import com.mlmvpn.scanner.ui.tunnel.TunnelExclusion
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.SafeStart
import com.mlmvpn.scanner.utils.GameSessionOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

enum class WsPhase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }
data class WsConnection(val phase: WsPhase = WsPhase.DISCONNECTED, val method: WsMethod? = null,
    val attempt: Int = 0, val total: Int = 0, val latencyMs: Long? = null, val error: WsError? = null,
    /** A measurement is running (the attempts are tests, not a connect). */
    val testing: Boolean = false)
/** [wrong]: the answer before this challenge was not accepted (a fresh one replaces it). */
data class WsLoginChallenge(val ascii: String, val wrong: Boolean = false)

/** Owns account operations and a sequential search; existing services own the device tunnel. */
object WindscribeEngine {
    private const val TAG = "Windscribe"
    const val NODE_ID = "windscribe"
    const val PROFILE_PREFIX = "windscribe_"
    const val SIGN_UP_URL = "https://windscribe.com/signup"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private var initialized = false
    private lateinit var app: Context
    private lateinit var repository: WsRepository
    private var native: WsApi? = null
    private var connectionJob: Job? = null
    private var connectedAccount: String? = null
    private var tunnelOwner: String? = null
    private var tunnelProtocol: WsProtocol? = null
    /** The Cloudflare relay (or WARP door) under a running OpenVPN-family tunnel, closed with it. */
    @Volatile private var relay: java.io.Closeable? = null
    /** The user wants the tunnel kept: set once a connect proved it, cleared only by the user (disconnect, account change). */
    @Volatile private var keepAlive = false
    @Volatile private var reconnectFailures = 0
    private var pendingLogin: PendingLogin? = null
    private class PendingLogin(val username: String, val password: String, val twoFa: String, val token: String) {
        override fun toString() = "Windscribe login [REDACTED]"
    }
    private val ready = MutableStateFlow(false)
    private val mutableData = MutableStateFlow(WsData())
    val data = mutableData.asStateFlow()
    private val mutableConnection = MutableStateFlow(WsConnection())
    val connection = mutableConnection.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<WsError?>(null)
    val error = mutableError.asStateFlow()
    private val mutableResults = MutableStateFlow<List<WsMeasurement>>(emptyList())
    val results = mutableResults.asStateFlow()
    private val mutableChallenge = MutableStateFlow<WsLoginChallenge?>(null)
    val challenge = mutableChallenge.asStateFlow()
    private val mutableEmailSent = MutableStateFlow(false)
    val emailSent = mutableEmailSent.asStateFlow()
    private val mutableLoginGeneration = MutableStateFlow(0L)
    val loginGeneration = mutableLoginGeneration.asStateFlow()
    private val mutableProtocols = MutableStateFlow(setOf(WsProtocol.WIREGUARD))
    val supportedProtocols = mutableProtocols.asStateFlow()

    @Synchronized fun init(context: Context) {
        if (initialized) return
        initialized = true; app = context.applicationContext
        AmzEngine.init(app)
        scope.launch {
            repository = WsRepository(app)
            mutableProtocols.value = WsProtocol.values().filter { p -> p == WsProtocol.WIREGUARD ||
                (OpenVpnNative.available(app) && (p !in setOf(WsProtocol.STEALTH, WsProtocol.WSTUNNEL) || WsProxyClient.available(app))) }.toSet()
            mutableData.value = repository.data.value
            ready.value = true
            repository.data.collect {
                mutableData.value = it
                // A plain marker for callers that must answer before the vault is read (Scout's readiness).
                app.getSharedPreferences("windscribe_amz", Context.MODE_PRIVATE).edit().putBoolean("signed_in", it.active?.sessionValid == true).apply()
            }
        }
        scope.launch { observeTunnel() }
    }
    private suspend fun api(reset: Boolean = false): Pair<WsApi, String> {
        ready.first { it }
        if (data.value.storageError) throw WsException(WsError.STORAGE)
        val client = native ?: WsApi(app, repository).also { native = it }
        val network = FluxNet.current(app).key
        client.mode(network, reset)
        return client to network
    }
    private fun operation(block: suspend () -> Unit) {
        scope.launch {
            operations.withLock {
                mutableBusy.value = true; mutableError.value = null
                try { ready.first { it }; block() }
                catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); mutableError.value = WsError.NETWORK }
                catch (e: CancellationException) { throw e }
                catch (e: WsException) { Log.i(TAG, "operation failed ${e.error}"); mutableError.value = e.error }
                catch (_: LinkageError) { mutableError.value = WsError.CORE }
                catch (e: Exception) { Log.w(TAG, "operation crashed", e); mutableError.value = WsError.NETWORK }
                finally { mutableBusy.value = false }
            }
        }
    }
    fun login(username: String, password: String, twoFa: String) = operation {
        com.mlmvpn.scanner.CrashReporter.note("windscribe login")
        if (username.isBlank() || password.isEmpty()) throw WsException(WsError.AUTH)
        cancelLogin()
        val (client, network) = api()
        challenge(PendingLogin(username.trim(), password, twoFa.trim(), ""), client, network, wrong = false)
    }
    /** A login token for [base]'s user; with a challenge, it is shown, else the login goes straight on. */
    private suspend fun challenge(base: PendingLogin, client: WsApi, network: String, wrong: Boolean) {
        val auth = client.json(network) { a, cb -> a.authTokenLogin(base.username, true, cb) }
        val token = auth.optString("token").takeIf { it.isNotBlank() } ?: throw WsException(WsError.CONFIG)
        val pending = PendingLogin(base.username, base.password, base.twoFa, token)
        val captcha = auth.optJSONObject("captcha")
        if (captcha != null) {
            val ascii = captcha.optString("ascii_art").takeIf { it.isNotBlank() }?.let(::captchaArt) ?: throw WsException(WsError.CAPTCHA)
            pendingLogin = pending; mutableChallenge.value = WsLoginChallenge(ascii, wrong)
        } else finishLogin(pending, "", client, network)
    }
    /**
     * A wrong answer does not close the dialog: a fresh challenge (and token) comes in its place with
     * a line saying the last one was not accepted, as Windscribe's own app does. It used to end the
     * login with «تأیید ناموفق بود», and the user had to start over from the password.
     */
    fun answerCaptcha(answer: String) = operation {
        val pending = pendingLogin ?: throw WsException(WsError.CAPTCHA)
        val (client, network) = api()
        try {
            finishLogin(pending, answer.trim(), client, network)
            cancelLogin()
        } catch (e: WsException) {
            if (e.error != WsError.CAPTCHA) { cancelLogin(); throw e }
            challenge(pending, client, network, wrong = true)
        }
    }
    /** Another challenge for the same login, when this one cannot be read. */
    fun refreshCaptcha() = operation {
        val pending = pendingLogin ?: throw WsException(WsError.CAPTCHA)
        val (client, network) = api()
        challenge(pending, client, network, wrong = false)
    }
    fun cancelLogin() { pendingLogin = null; mutableChallenge.value = null }
    /**
     * The API sends the challenge base64-encoded: shown as it came, it was one long line of letters
     * no one could read or type (user report 2026-10-11). Decoded it is the drawing itself, rows of
     * `*` forming the characters. Kept as it came when it is not base64 of printable text.
     */
    private fun captchaArt(raw: String): String {
        val decoded = runCatching { String(android.util.Base64.decode(raw.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            ?: return raw
        val printable = decoded.isNotBlank() && decoded.all { it == '\n' || it == '\r' || it == '\t' || it in ' '..'~' }
        return if (printable && decoded.contains('\n')) decoded else raw
    }
    private suspend fun finishLogin(pending: PendingLogin, answer: String, client: WsApi, network: String) {
        val account = client.json(network) { a, cb -> a.login(pending.username, pending.password, pending.twoFa, pending.token, answer, floatArrayOf(), floatArrayOf(), cb) }
        stopAndWait()
        repository.login(account)
        mutableLoginGeneration.value += 1
        mutableEmailSent.value = false
        refreshAccount(account.optString("user_id"), client, network, true)
    }
    fun refresh() = operation {
        val account = data.value.active ?: throw WsException(WsError.AUTH)
        val (client, network) = api()
        withTimeout(20_000) { refreshAccount(account.id, client, network, true) }
        mutableResults.value = repository.measurements(account.id, network)
    }
    private suspend fun refreshAccount(id: String, client: WsApi, network: String, servers: Boolean) {
        try {
            repository.update(id, client.session(id, network))
            if (servers) {
                val locations = client.locations(id, network)
                val inventory = client.servers(id, network)
                val ports = client.ports(id, network)
                repository.cache(id, locations, inventory, ports)
            }
        } catch (e: WsException) {
            if (e.error == WsError.SESSION_EXPIRED) repository.invalidate(id)
            throw e
        }
    }
    fun selectAccount(id: String) = operation {
        keepAlive = false
        stopAndWait(); repository.selectAccount(id); removeWireGuard()
        mutableResults.value = emptyList(); mutableEmailSent.value = false
        val (client, network) = api()
        refreshAccount(id, client, network, true)
        mutableResults.value = repository.measurements(id, network)
    }
    /** User chooses every account change. No quota-driven account rotation. */
    fun logout(id: String) = operation {
        keepAlive = false
        if (connectedAccount == id || data.value.activeId == id) stopAndWait()
        var remoteError: WsError? = null
        try {
            val (client, network) = api()
            client.json(network) { a, cb -> a.deleteSession(repository.token(id), cb) }
        } catch (e: WsException) { if (e.error != WsError.SESSION_EXPIRED) remoteError = e.error }
        repository.remove(id)
        purgeProfiles()
        removeWireGuard()
        mutableResults.value = emptyList()
        remoteError?.let { mutableError.value = it }
    }
    fun removeAccount(id: String) = logout(id)
    fun sendVerification() = operation {
        val account = data.value.active ?: throw WsException(WsError.AUTH)
        if (account.email.isNullOrBlank()) throw WsException(WsError.CONFIG)
        val (client, network) = api()
        client.json(network) { a, cb -> a.confirmEmail(repository.token(account.id), cb) }
        mutableEmailSent.value = true
    }
    fun addEmail(email: String) = operation {
        val account = data.value.active ?: throw WsException(WsError.AUTH)
        val (client, network) = api()
        client.json(network) { a, cb -> a.addEmail(repository.token(account.id), email.trim(), cb) }
        refreshAccount(account.id, client, network, false)
    }
    // The repository's own values: the public copy may not have caught up with a choice made a moment ago.
    fun selectServer(serverId: String?) = operation { repository.selection(serverId, repository.data.value.protocol) }
    fun selectProtocol(protocol: WsProtocol?) = operation { repository.selection(repository.data.value.selectedServer, protocol) }

    /**
     * [benchmark]: measure instead of stopping at the first working method, then connect the best.
     * [all]: the benchmark over every server with every protocol (the screen's «تست همه»).
     */
    fun connect(context: Context, benchmark: Boolean = false, searchAgain: Boolean = false, all: Boolean = false, exact: WsMethod? = null,
                /** With [all]: only this datacenter's node (the screen's «تست سرور انتخاب‌شده»). */
                allOf: String? = null) {
        com.mlmvpn.scanner.CrashReporter.note("windscribe connect bench=$benchmark all=$all exact=${exact?.protocol}")
        init(context)
        // Cancel/join previous work before opening a new owner. A late finally cannot stop its replacement.
        scope.launch {
            operations.withLock {
                stopAndWait()
                connectionJob = scope.launch {
                    mutableBusy.value = true; mutableError.value = null
                    mutableConnection.value = WsConnection(WsPhase.CONNECTING)
                    var retain = false
                    try {
                      val budget = if (all) WsSearchPolicy.TEST_ALL_BUDGET_MS else if (benchmark) WsSearchPolicy.BENCHMARK_BUDGET_MS else if (searchAgain) WsSearchPolicy.SEARCH_BUDGET_MS else WsSearchPolicy.CONNECT_BUDGET_MS
                      val deadline = android.os.SystemClock.elapsedRealtime() + budget
                      withTimeout(budget) {
                        ready.first { it }
                        val (client, network) = api(searchAgain)
                        val account = data.value.active ?: throw WsException(WsError.AUTH)
                        if (!account.sessionValid) throw WsException(WsError.SESSION_EXPIRED)
                        val cached = repository.data.value
                        val cacheValid = cached.active?.sessionValid == true && cached.servers.isNotEmpty() && cached.ports.isNotEmpty()
                        try {
                            withTimeout(8_000) { refreshAccount(account.id, client, network, searchAgain || !cacheValid) }
                        } catch (_: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            if (!cacheValid) throw WsException(WsError.NETWORK)
                        } catch (e: WsException) {
                            if (!WsSearchPolicy.mayUseCache(e.error, cacheValid)) throw e
                        }
                        val refreshed = repository.data.value.active ?: throw WsException(WsError.AUTH)
                        if (refreshed.status != 1) throw WsException(WsError.ACCOUNT_UNAVAILABLE)
                        if (!refreshed.premium && refreshed.remaining == 0L) throw WsException(WsError.QUOTA)
                        if (searchAgain) repository.remember(account.id, network, null)
                        val snapshot = repository.data.value
                        val preferred = if (searchAgain || benchmark) null else repository.preferred(account.id, network)
                        val candidates = snapshot.servers.groupBy { it.dcId }.values.map { nodes -> nodes.minBy { it.load ?: 100 } }
                            .sortedWith(WsPlanner.ORDER)
                        val servers = snapshot.selectedServer?.let { id -> snapshot.servers.filter { it.id == id } }
                            ?: ((preferred?.let { p -> snapshot.servers.firstOrNull { it.id == p.serverId } }?.let(::listOf) ?: emptyList()) + candidates).distinctBy { it.dcId }.take(4)
                        val allowedPorts = snapshot.ports.filterKeys { it in supportedProtocols.value }
                        val cf = true
                        val reach = if (exact != null) emptyMap() else reachability(if (all) candidates else servers, allowedPorts)
                        // A row of the test table: that server with that protocol, and it is remembered when it works.
                        var plan = if (exact != null) listOf(exact).filter { m -> snapshot.servers.any { it.id == m.serverId } }
                            else if (all) WsPlanner.matrix(allOf?.let { dc -> candidates.filter { it.dcId == dc } } ?: candidates, allowedPorts)
                            else WsPlanner.plan(servers, allowedPorts, snapshot.protocol, preferred, cf) { s, p -> reach[WsContract.endpoint(s, p)] == true }
                                .take(if (benchmark) 12 else 24)
                        if (plan.isEmpty()) throw WsException(WsError.PROTOCOL)
                        val available = prepareConfigs(account.id, client, network, plan, searchAgain)
                        plan = plan.filter { it.protocol in available }
                        if (plan.isEmpty()) throw WsException(WsError.PROTOCOL)
                        Log.i(TAG, "plan ${plan.size} configs=$available: " + plan.take(8).joinToString { "${it.protocol}:${it.port}${if (it.padding) "+pad" else ""}${if (it.cf) "+cf" else ""}" } +
                            " reachable=${reach.count { it.value }}/${reach.size} cf=$cf")
                        // One server's test replaces that server's rows only; the rest of the table stays.
                        // Only a test writes the table; a connect's attempts never wipe it.
                        if (benchmark) mutableResults.value = if (all && allOf != null) {
                            val dcOf = snapshot.servers.associate { it.id to it.dcId }
                            results.value.filter { r -> dcOf[r.method.serverId] != allOf }
                        } else emptyList()
                        connectedAccount = account.id
                        for ((index, method) in plan.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                            if (remaining < (if (benchmark) 35_000 else 8_000)) break
                            mutableConnection.value = WsConnection(WsPhase.CONNECTING, method, index + 1, plan.size, testing = benchmark)
                            val result = try { run {
                                val before = System.currentTimeMillis()
                                // «تست همه»: WireGuard without the device VPN (a real session in a probe core).
                                if (all && method.protocol == WsProtocol.WIREGUARD) return@run probeWireGuard(account.id, method, reach)
                                withTimeout(minOf(if (method.protocol == WsProtocol.WIREGUARD) (if (benchmark) 45_000L else 120_000L)
                                    else if (method.cf) (if (all) 25_000L else 40_000L) else 28_000L,
                                    remaining - (if (benchmark) 25_000 else 1000))) { startMethod(account.id, method, exact = benchmark) }
                                val connectMs = System.currentTimeMillis() - before
                                // The Amnezia engine proved its tunnel with a real request; its Xray tunnel leaves this app outside.
                                // A connect trusts the Amnezia engine's own proof for WireGuard; a test measures it like the rest.
                                if (method.protocol == WsProtocol.WIREGUARD && !benchmark) wireGuardResult(method, connectMs)
                                else measure(method, connectMs, benchmark)
                            } } catch (_: TimeoutCancellationException) {
                                currentCoroutineContext().ensureActive()
                                Log.i(TAG, "method ${method.protocol} timed out (openvpn=${OpenVpnRuntime.connection.value.error} vpn=${MyVpnService.connectionPhaseFlow.value})")
                                WsMeasurement(method, 0, null, null, 0, 3, System.currentTimeMillis())
                            } catch (e: WsException) {
                                Log.i(TAG, "method ${method.protocol} failed ${e.error} (openvpn=${OpenVpnRuntime.connection.value.error})")
                                if (e.error in setOf(WsError.SESSION_EXPIRED, WsError.AUTH, WsError.STORAGE)) throw e
                                WsMeasurement(method, 0, null, null, 0, 3, System.currentTimeMillis())
                            }
                            Log.i(TAG, "method ${method.protocol}:${method.port} pad=${method.padding} cf=${method.cf} server=${method.serverId} -> ok=${result.success} ${result.latencyMs}ms")
                            if (benchmark) {
                                mutableResults.value += result
                                currentCoroutineContext().ensureActive()
                                repository.saveMeasurements(account.id, network, results.value)
                            }
                            if (result.success && !benchmark) {
                                repository.remember(account.id, network, method)
                                mutableConnection.value = WsConnection(WsPhase.CONNECTED, shown(method), latencyMs = result.latencyMs)
                                retain = true; break
                            }
                            stopOwned()
                        }
                        // A combination that failed once gets one more real try at the end, so a moment's
                        // loss on the line is not written into the table as "does not work".
                        if (all) for (failed in results.value.filter { !it.success }.map { it.method }) {
                            currentCoroutineContext().ensureActive()
                            if (deadline - android.os.SystemClock.elapsedRealtime() < 35_000) break
                            mutableConnection.value = WsConnection(WsPhase.CONNECTING, failed, plan.size, plan.size, testing = true)
                            val again = try {
                                if (failed.protocol == WsProtocol.WIREGUARD) probeWireGuard(account.id, failed, reach)
                                else {
                                    val before = System.currentTimeMillis()
                                    withTimeout(30_000) { startMethod(account.id, failed, exact = true) }
                                    measure(failed, System.currentTimeMillis() - before, true)
                                }
                            } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
                            catch (e: WsException) { if (e.error in setOf(WsError.SESSION_EXPIRED, WsError.AUTH, WsError.STORAGE)) throw e; null }
                            Log.i(TAG, "retry ${failed.protocol} server=${failed.serverId} -> ok=${again?.success == true}")
                            if (again?.success == true) {
                                mutableResults.value = results.value.map { if (it.method == failed) again else it }
                                repository.saveMeasurements(account.id, network, results.value)
                            }
                            stopOwned()
                        }
                        if (benchmark) {
                            // The best measured, and if that one does not come up now, the next best (up to five):
                            // a test must never leave the phone disconnected because its winner had a bad moment.
                            val ranked = results.value.filter { it.success }.sortedWith(WsPlanner.SCORE).reversed().take(5)
                            if (ranked.isEmpty()) throw WsException(WsError.TIMEOUT)
                            for (best in ranked) {
                                currentCoroutineContext().ensureActive()
                                mutableConnection.value = WsConnection(WsPhase.CONNECTING, best.method)
                                val check = try {
                                    withTimeout(60_000) { startMethod(account.id, best.method, exact = true) }
                                    if (best.method.protocol == WsProtocol.WIREGUARD) wireGuardResult(best.method, 0) else measure(best.method, 0, false)
                                } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
                                catch (e: WsException) { if (e.error in setOf(WsError.SESSION_EXPIRED, WsError.AUTH, WsError.STORAGE)) throw e; null }
                                Log.i(TAG, "after the test: ${best.method.protocol} server=${best.method.serverId} -> ${check?.success == true}")
                                if (check?.success == true) {
                                    repository.remember(account.id, network, best.method)
                                    mutableConnection.value = WsConnection(WsPhase.CONNECTED, shown(best.method), latencyMs = check.latencyMs)
                                    retain = true; break
                                }
                                stopOwned()
                            }
                            if (!retain) throw WsException(WsError.TESTED_NOT_CONNECTED)
                        }
                        if (!retain) throw WsException(WsError.TIMEOUT)
                      }
                      // Refresh provider status through the working route; quota remains server authoritative.
                      if (retain) {
                          try { withTimeout(8_000) { val (client, network) = api(); refreshAccount(connectedAccount!!, client, network, false) } }
                          catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
                          catch (e: WsException) { if (e.error != WsError.NETWORK) { retain = false; throw e } }
                          val latest = data.value.active
                          if (latest?.status != 1 || (!latest.premium && latest.remaining == 0L)) {
                              retain = false; throw WsException(if (latest?.status != 1) WsError.ACCOUNT_UNAVAILABLE else WsError.QUOTA)
                          }
                          com.mlmvpn.scanner.data.LastEngine.recordXray(app, NODE_ID, NODE_ID)
                          // From now on a drop is the watchdog's to repair, until the user disconnects.
                          keepAlive = true; reconnectFailures = 0
                      }
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive(); Log.i(TAG, "connect timed out"); mutableConnection.value = WsConnection(WsPhase.ERROR, error = WsError.TIMEOUT)
                    } catch (e: CancellationException) { throw e }
                    catch (e: WsException) { Log.i(TAG, "connect failed ${e.error}"); mutableConnection.value = WsConnection(WsPhase.ERROR, error = e.error) }
                    catch (_: LinkageError) { mutableConnection.value = WsConnection(WsPhase.ERROR, error = WsError.CORE) }
                    catch (e: Exception) { Log.w(TAG, "connect crashed", e); mutableConnection.value = WsConnection(WsPhase.ERROR, error = WsError.NETWORK) }
                    finally {
                        if (!retain) withContext(NonCancellable) { stopOwned() }
                        mutableBusy.value = false
                    }
                }
            }
        }
    }
    fun disconnect() { com.mlmvpn.scanner.CrashReporter.note("windscribe disconnect"); keepAlive = false; scope.launch { operations.withLock { stopAndWait(); mutableConnection.value = WsConnection() } } }
    private suspend fun stopAndWait() {
        connectionJob?.cancelAndJoin(); connectionJob = null
        if (ownsTunnel()) mutableConnection.value = connection.value.copy(phase = WsPhase.DISCONNECTING)
        stopOwned(); connectedAccount = null
        mutableConnection.value = WsConnection()
    }
    private fun ownsTunnel(): Boolean = wireGuardUp() ||
        OpenVpnRuntime.connection.value.profileId?.startsWith(PROFILE_PREFIX) == true
    /** Windscribe's WireGuard runs as the Amnezia engine's tunnel, on one of the nodes imported for it. */
    private fun wireGuardUp(): Boolean = MyVpnService.connectedNodeIdFlow.value == AmzEngine.NODE_ID &&
        (AmzEngine.connect.value as? AmzConnect.Connected)?.serverId in wireGuardIds().values
    private suspend fun stopOwned() {
        val owner = tunnelOwner
        if (owner != null) GameSessionOwner.release(owner)
        if ((owner != null && tunnelProtocol != WsProtocol.WIREGUARD) || OpenVpnRuntime.connection.value.profileId?.startsWith(PROFILE_PREFIX) == true)
            withTimeoutOrNull(10_000) { OpenVpnRuntime.release(app, owner) }
        if ((owner != null && tunnelProtocol == WsProtocol.WIREGUARD) || wireGuardUp()) {
            AmzEngine.disconnectAsync(app)
            withTimeoutOrNull(10_000) { while (MyVpnService.connectedNodeIdFlow.value == AmzEngine.NODE_ID) delay(100) }
        }
        relay?.let { r -> relay = null; withContext(Dispatchers.IO) { r.close() } }
        tunnelOwner = null; tunnelProtocol = null
        delay(200)
    }
    private fun purgeProfiles() {
        val repo = OpenVpnRepository.get(app)
        repo.data.value.profiles.filter { it.id.startsWith(PROFILE_PREFIX) && it.id != OpenVpnRuntime.connection.value.profileId }
            .forEach { repo.removeProfile(it.id) }
    }
    private suspend fun prepareConfigs(id: String, client: WsApi, network: String, plan: List<WsMethod>, force: Boolean): Set<WsProtocol> {
        val available = mutableSetOf<WsProtocol>()
        suspend fun attempt(protocols: Set<WsProtocol>, cached: Boolean, block: suspend () -> Unit) {
            if (cached && !force) { available += protocols; return }
            try { withTimeout(18_000) { block() }; available += protocols }
            catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); if (cached) available += protocols }
            catch (e: WsException) {
                if (e.error in setOf(WsError.AUTH, WsError.SESSION_EXPIRED, WsError.STORAGE, WsError.QUOTA)) throw e
                if (WsSearchPolicy.mayUseCache(e.error, cached)) available += protocols
            }
        }
        val wgCached = repository.secret(id, "wg_private") != null && repository.secret(id, "wg_init")?.let {
            runCatching { val j = JSONObject(it); j.getString("PresharedKey").isNotBlank() && j.getJSONArray("HashedCIDR").length() > 0 }.getOrDefault(false)
        } == true
        if (plan.any { it.protocol == WsProtocol.WIREGUARD }) attempt(setOf(WsProtocol.WIREGUARD), wgCached) {
            val privateKey = repository.secret(id, "wg_private") ?: org.amnezia.awg.crypto.KeyPair().privateKey.toBase64().also { repository.putSecret(id, "wg_private", it) }
            val publicKey = org.amnezia.awg.crypto.KeyPair(org.amnezia.awg.crypto.Key.fromBase64(privateKey)).publicKey.toBase64()
            val init = client.json(network) { a, cb -> a.wgConfigsInit(repository.token(id), publicKey, false, cb) }
            repository.putSecret(id, "wg_init", init.getJSONObject("config").toString())
        }
        val ovpnCached = repository.secret(id, "ovpn_credentials") != null && repository.secret(id, "ovpn_template")?.contains("<ca>") == true
        if (plan.any { it.protocol != WsProtocol.WIREGUARD }) attempt(WsProtocol.values().filter { it != WsProtocol.WIREGUARD }.toSet(), ovpnCached) {
            val credentials = client.json(network) { a, cb -> a.serverCredentials(repository.token(id), true, cb) }
            repository.putSecret(id, "ovpn_credentials", credentials.toString())
            val template = client.request(network) { a, cb -> a.serverConfigs(repository.token(id), cb) }
            // ServerConfigs is a raw base64 resource, not the JSON data envelope used by other calls.
            repository.putSecret(id, "ovpn_template", String(WsContract.decode64(template), Charsets.UTF_8))
        }
        return available
    }
    /** [exact]: this node and nothing else (a test); a connect lets WireGuard fall back across the nodes. */
    private suspend fun startMethod(id: String, method: WsMethod, exact: Boolean = false) {
        val server = repository.data.value.servers.firstOrNull { it.id == method.serverId } ?: throw WsException(WsError.CONFIG)
        // Each stage is a breadcrumb: a freeze or a native crash names the last one it reached.
        com.mlmvpn.scanner.CrashReporter.note("windscribe start ${method.protocol} cf=${method.cf}")
        if (method.protocol == WsProtocol.WIREGUARD) {
            val ids = importWireGuard(id, method.port)
            val target = ids[method.serverId] ?: ids.values.firstOrNull() ?: throw WsException(WsError.CONFIG)
            currentCoroutineContext().ensureActive()
            val owner = "windscribe:${UUID.randomUUID()}".also { tunnelOwner = it; tunnelProtocol = method.protocol }
            Log.i(TAG, "WireGuard through the Amnezia engine: ${ids.size} nodes, first ${server.city}")
            // Its tunnel holds WARP with the same key the shared carrier uses: one session per key.
            WsWarpCarrier.closeShared()
            com.mlmvpn.scanner.CrashReporter.note("windscribe wireguard through amnezia (${ids.size} nodes)")
            // It tries the node straight, then inside Cloudflare WARP, and falls back across Windscribe's nodes only.
            AmzEngine.connectAsync(app, serverId = target, only = if (exact) setOf(target) else ids.values.toSet()).join()
            val state = AmzEngine.connect.value
            if (state !is AmzConnect.Connected || state.serverId !in ids.values || (exact && state.serverId != target)) {
                Log.i(TAG, "WireGuard did not connect: $state")
                throw WsException(WsError.TIMEOUT)
            }
            Log.i(TAG, "WireGuard connected via=${state.via} in ${state.delayMs} ms")
            check(owner == tunnelOwner)
        } else {
            val credentials = JSONObject(repository.secret(id, "ovpn_credentials") ?: throw WsException(WsError.CONFIG))
            val username = String(WsContract.decode64(credentials.getString("username")), Charsets.UTF_8)
            val password = String(WsContract.decode64(credentials.getString("password")), Charsets.UTF_8)
            val template = repository.secret(id, "ovpn_template") ?: throw WsException(WsError.CONFIG)
            val viaCf = if (method.cf && method.protocol == WsProtocol.OPENVPN_UDP) {
                // UDP: a Worker carries TCP only; the shared WARP carrier takes the datagrams.
                com.mlmvpn.scanner.CrashReporter.note("windscribe udp relay through warp")
                val (r, port) = withContext(Dispatchers.IO) { udpThroughWarp(server.ip2, method.port) } ?: throw WsException(WsError.TIMEOUT)
                Log.i(TAG, "OPENVPN_UDP to ${server.city} through warp")
                relay = r; port
            } else if (method.cf) {
                com.mlmvpn.scanner.CrashReporter.note("windscribe ${method.protocol} relay through cloudflare")
                val r = withContext(Dispatchers.IO) { WsCfRelay.open(app, WsContract.endpoint(server, method.protocol), method.port) }
                    ?: throw WsException(WsError.TIMEOUT)
                Log.i(TAG, "${method.protocol} to ${server.city} through ${r.via}")
                relay = r; r.localPort
            } else null
            val proxy = if (method.protocol in setOf(WsProtocol.STEALTH, WsProtocol.WSTUNNEL)) WsContract.proxy(server, method, viaCf) else null
            // OpenVPN TCP inside Cloudflare dials the relay itself; Stealth/WStunnel's proxy dials it.
            val raw = WsContract.openVpn(template, server, method, username, password, if (proxy == null) viaCf else null)
            val parsed = ProfileImporter.parse("Windscribe ${server.city}", raw, emptyMap())
            val profile = Profile(PROFILE_PREFIX + parsed.id + (if (proxy?.padding == true) "_pad" else "") + (if (method.cf) "_cf" else ""), parsed.name, parsed.config, parsed.remotes,
                parsed.authenticatedControl, splitTcp = method.protocol == WsProtocol.OPENVPN_TCP && method.padding && !method.cf, wsProxy = proxy)
            OpenVpnRepository.get(app).putProviderProfile(profile)
            currentCoroutineContext().ensureActive()
            val owner = "windscribe:${UUID.randomUUID()}".also { tunnelOwner = it; tunnelProtocol = method.protocol; GameSessionOwner.register(it) }
            com.mlmvpn.scanner.CrashReporter.note("windscribe openvpn start ${method.protocol} relay=${viaCf != null} proxy=${proxy != null}")
            OpenVpnRuntime.connect(app, profile.id, OpenVpnRuntime.SELF, owner)
            withTimeout(35_000) {
                OpenVpnRuntime.connection.first { state ->
                    if (state.profileId == profile.id && state.phase == ConnectionPhase.ERROR) {
                        Log.i(TAG, "OpenVPN ${method.protocol} ended: ${state.error}")
                        throw WsException(WsError.TIMEOUT)
                    }
                    state.profileId == profile.id && state.phase == ConnectionPhase.CONNECTED && state.detail != "VERIFYING"
                }
            }
        }
    }
    // ── WireGuard through the Amnezia engine ─────────────────────────────────────────────────────

    private fun amzPrefs() = app.getSharedPreferences("windscribe_amz", Context.MODE_PRIVATE)

    /** Windscribe node id -> the Amnezia-store id of its imported config (the active account's). */
    private fun wireGuardIds(): Map<String, String> = if (initialized) wireGuardIds(app) else emptyMap()

    /** Also for callers before [init] (the home screen's lamps). */
    fun wireGuardIds(context: Context): Map<String, String> {
        return context.getSharedPreferences("windscribe_amz", Context.MODE_PRIVATE).getStringSet("ids", emptySet()).orEmpty().mapNotNull { e ->
            e.split('|').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
    }

    /**
     * One WireGuard config per datacenter (its least-loaded node), made with the account's key and
     * kept in the Amnezia store, the way Proton's are. Configs of nodes gone or of another key are
     * removed. The returned map is what a connect may use, and nothing else.
     */
    private fun importWireGuard(id: String, port: Int): Map<String, String> {
        val key = repository.secret(id, "wg_private") ?: throw WsException(WsError.CONFIG)
        val publicKey = org.amnezia.awg.crypto.KeyPair(org.amnezia.awg.crypto.Key.fromBase64(key)).publicKey.toBase64()
        val init = JSONObject(repository.secret(id, "wg_init") ?: throw WsException(WsError.CONFIG))
        val address = WsContract.address(publicKey, init.getJSONArray("HashedCIDR").getString(0))
        val nodes = repository.data.value.servers.groupBy { it.dcId }.values.map { n -> n.minBy { it.load ?: 100 } }
            .filter { WsPlanner.available(it, WsProtocol.WIREGUARD) }
        val confs = nodes.map { s -> s to WsContract.wireGuard(key, address, init, s, port) }
        val now = System.currentTimeMillis()
        // Ids follow the endpoint and keys: the same configs again are not imported (and re-tested) again.
        val expected = confs.map { (s, text) -> s.id to AmzParser.parseText(text, AmzServer.USER_SOURCE, now).servers.firstOrNull()?.id }
        val present = AmzEngine.store.current.servers.mapTo(HashSet()) { it.id }
        val map = if (expected.all { it.second != null && it.second in present }) expected.associate { it.first to it.second!! } else {
            val ids = AmzEngine.importConfs(app, confs.map { (s, text) -> Triple(text, "Windscribe ${s.city} ${s.nick}".trim(), s.country) })
            // The import starts a delay test of them all; the connect that follows needs the probe process alone.
            AmzEngine.stopTest()
            confs.map { it.first.id }.zip(ids).toMap()
        }
        val stale = wireGuardIds().values.toSet() - map.values.toSet()
        if (stale.isNotEmpty()) AmzEngine.delete(stale)
        amzPrefs().edit().putStringSet("ids", map.map { "${it.key}|${it.value}" }.toSet()).apply()
        return map
    }

    private fun wireGuardConf(id: String, server: WsServer, port: Int): String {
        val key = repository.secret(id, "wg_private") ?: throw WsException(WsError.CONFIG)
        val publicKey = org.amnezia.awg.crypto.KeyPair(org.amnezia.awg.crypto.Key.fromBase64(key)).publicKey.toBase64()
        val init = JSONObject(repository.secret(id, "wg_init") ?: throw WsException(WsError.CONFIG))
        return WsContract.wireGuard(key, WsContract.address(publicKey, init.getJSONArray("HashedCIDR").getString(0)), init, server, port)
    }

    /**
     * One node's WireGuard measured without the device VPN ([WsWgProbe]): straight to the node where
     * the line reaches its address, inside Cloudflare WARP where it does not (or straight carried
     * nothing). The connect time is until the first request came back through it.
     */
    private suspend fun probeWireGuard(id: String, method: WsMethod, reach: Map<String, Boolean>): WsMeasurement {
        val server = repository.data.value.servers.firstOrNull { it.id == method.serverId } ?: throw WsException(WsError.CONFIG)
        val conf = wireGuardConf(id, server, method.port)
        val routes = buildList {
            if (reach[server.ip3] == true) add(false)
            add(true)
        }
        for (viaWarp in routes) {
            currentCoroutineContext().ensureActive()
            val started = System.currentTimeMillis()
            val udp = if (viaWarp) udpThroughWarp(server.ip3, method.port) ?: continue else null
            val probe = withContext(Dispatchers.IO) { WsWgProbe.start(app, conf, udp?.second) }
            if (probe == null) { udp?.first?.close(); continue }
            try {
                val proxy = probe.proxy()
                val open: (URL) -> HttpURLConnection = { url -> url.openConnection(proxy) as HttpURLConnection }
                var up = false
                val deadline = started + (if (viaWarp) 12_000 else 6_000)
                while (!up && System.currentTimeMillis() < deadline) {
                    currentCoroutineContext().ensureActive()
                    up = withContext(Dispatchers.IO) { runCatching {
                        val c = open(URL("https://www.gstatic.com/generate_204"))
                        c.connectTimeout = 4_000; c.readTimeout = 4_000; c.useCaches = false
                        try { c.responseCode == 204 } finally { c.disconnect() }
                    }.getOrDefault(false) }
                    if (!up) delay(300)
                }
                if (!up) continue
                val shown = method.copy(cf = viaWarp)
                return measure(shown, System.currentTimeMillis() - started, true, open) { true }
            } finally { withContext(Dispatchers.IO) { probe.close(); udp?.first?.close() } }
        }
        return WsMeasurement(method, 0, null, null, 0, 5, System.currentTimeMillis())
    }

    /**
     * A loopback UDP port into [host]:[port] through the shared WARP carrier, and what closes it.
     * Also for MLM Scout.
     */
    suspend fun udpThroughWarp(host: String, port: Int): Pair<java.io.Closeable, Int>? {
        val lease = WsWarpCarrier.lease(app) ?: return null
        val udp = try { withContext(Dispatchers.IO) { WsUdpRelay(lease.port, host, port) } }
            catch (e: Exception) { Log.i(TAG, "no UDP through WARP (${e.javaClass.simpleName})"); lease.close(); return null }
        return java.io.Closeable { udp.close(); lease.close() } to udp.localPort
    }

    /** For MLM Scout, instantly and locally: an account is signed in. */
    fun signedIn(context: Context): Boolean =
        context.getSharedPreferences("windscribe_amz", Context.MODE_PRIVATE).getBoolean("signed_in", false)

    /**
     * For MLM Scout: the nearest node's WireGuard config (with this account's key) and its city. The
     * account's WireGuard registration is fetched once from Windscribe when it is not stored yet.
     */
    suspend fun probeConf(context: Context): Pair<String, String>? = runCatching {
        init(context); ready.first { it }
        val account = data.value.active?.takeIf { it.sessionValid } ?: return@runCatching null
        operations.withLock {
            val snapshot = repository.data.value
            val server = snapshot.servers.groupBy { it.dcId }.values.map { n -> n.minBy { it.load ?: 100 } }
                .filter { WsPlanner.available(it, WsProtocol.WIREGUARD) }.sortedWith(WsPlanner.ORDER).firstOrNull() ?: return@withLock null
            val port = WsPlanner.mainPort(snapshot.ports[WsProtocol.WIREGUARD].orEmpty()) ?: return@withLock null
            if (repository.secret(account.id, "wg_init") == null || repository.secret(account.id, "wg_private") == null) {
                val (client, network) = api()
                prepareConfigs(account.id, client, network, listOf(WsMethod(server.id, WsProtocol.WIREGUARD, port)), false)
            }
            wireGuardConf(account.id, server, port) to server.city
        }
    }.getOrNull()

    private fun removeWireGuard() {
        val ids = wireGuardIds().values
        if (ids.isNotEmpty()) AmzEngine.delete(ids)
        amzPrefs().edit().remove("ids").apply()
    }

    /** For the screen: WireGuard that the Amnezia engine carried inside Cloudflare WARP is shown as such. */
    private fun shown(method: WsMethod): WsMethod =
        if (method.protocol == WsProtocol.WIREGUARD && (AmzEngine.connect.value as? AmzConnect.Connected)?.via == "cf") method.copy(cf = true) else method

    private fun wireGuardResult(method: WsMethod, connectMs: Long): WsMeasurement {
        val state = AmzEngine.connect.value as? AmzConnect.Connected
        val ms = state?.delayMs?.takeIf { wireGuardUp() }
        return WsMeasurement(method, connectMs, ms, null, if (ms != null) 1 else 0, 1, System.currentTimeMillis())
    }

    /**
     * Which server addresses the bare line reaches at all: one TCP connect each (any advertised TCP
     * port), side by side, 3 s. A blackholed address (the user's home line, 2026-10-10) is never dialled
     * straight; Cloudflare carries it instead.
     */
    private suspend fun reachability(servers: List<WsServer>, ports: Map<WsProtocol, List<Int>>): Map<String, Boolean> = coroutineScope {
        val network = FluxNet.current(app).network ?: return@coroutineScope emptyMap()
        val tcpPort = WsPlanner.mainPort(ports[WsProtocol.OPENVPN_TCP].orEmpty()) ?: 443
        val stealthPort = WsPlanner.mainPort(ports[WsProtocol.STEALTH].orEmpty()) ?: 443
        val targets = servers.flatMap { s -> listOf(s.ip2 to tcpPort, s.ip3 to stealthPort, s.ip to stealthPort) }
            .filter { it.first.isNotBlank() }.distinctBy { it.first }
        targets.map { (host, port) ->
            async(Dispatchers.IO) {
                host to runCatching {
                    network.socketFactory.createSocket().use { it.connect(java.net.InetSocketAddress(host, port), 3_000) }
                    true
                }.getOrDefault(false)
            }
        }.awaitAll().toMap()
    }

    private suspend fun awaitVpn(): Network = withTimeout(5_000) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var vpn: Network? = null
        while (vpn == null) {
            currentCoroutineContext().ensureActive()
            vpn = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            if (vpn == null) delay(100)
        }
        vpn
    }
    /**
     * How a measurement reaches the Internet through the tunnel under test: the VPN network this app is
     * inside (OpenVPN, direct WireGuard), or the HTTP inbound of the Xray tunnel that carries WireGuard
     * inside Cloudflare (that tunnel leaves this app outside). Never the direct Internet.
     */
    private suspend fun opener(method: WsMethod): (URL) -> HttpURLConnection {
        if (method.protocol == WsProtocol.WIREGUARD && (AmzEngine.connect.value as? AmzConnect.Connected)?.via == "cf") {
            val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", LocalPort.get(app) + LocalPort.PROBE_OFFSET))
            return { url -> url.openConnection(proxy) as HttpURLConnection }
        }
        val vpn = awaitVpn()
        return { url -> vpn.openConnection(url) as HttpURLConnection }
    }

    /**
     * TLS-validated HTTPS samples through the tunnel. A connect takes 3 back to back; a test ([speed])
     * takes 5, about a second apart, so the share that answered says how steady the route is, and
     * then a timed download says how fast.
     */
    private suspend fun measure(method: WsMethod, connectMs: Long, speed: Boolean,
                                route: ((URL) -> HttpURLConnection)? = null, guard: () -> Boolean = { ownsTunnel() }): WsMeasurement {
        val open = route ?: opener(method)
        val total = if (speed) 5 else 3
        val samples = mutableListOf<Long>()
        repeat(total) { i ->
            currentCoroutineContext().ensureActive()
            if (!guard()) throw WsException(WsError.TIMEOUT)
            if (speed && i > 0) delay(250)
            val started = System.nanoTime()
            val value = withContext(Dispatchers.IO) { runCatching {
                val c = open(URL("https://www.gstatic.com/generate_204"))
                c.connectTimeout = 5_000; c.readTimeout = 5_000; c.useCaches = false; c.instanceFollowRedirects = false
                try { if (c.responseCode == 204) (System.nanoTime() - started) / 1_000_000 else null }
                finally { c.disconnect() }
            }.getOrNull() }
            if (value != null) samples += value
        }
        val bps = if (speed && samples.isNotEmpty()) download(open, guard) else null
        currentCoroutineContext().ensureActive()
        return WsMeasurement(method, connectMs, samples.sorted().let { it.getOrNull(it.size / 2) }, bps, samples.size, total, System.currentTimeMillis())
    }

    /** Up to 1 MB in at most 8 s; the rate of what actually arrived (at least 128 KB of it), else unknown. */
    private suspend fun download(open: (URL) -> HttpURLConnection, guard: () -> Boolean): Long? = withContext(Dispatchers.IO) {
        val size = 1_048_576L
        runCatching {
            val c = open(URL("https://speed.cloudflare.com/__down?bytes=$size"))
            c.connectTimeout = 6_000; c.readTimeout = 6_000; c.useCaches = false; c.instanceFollowRedirects = false
            try {
                if (c.responseCode != 200) return@runCatching null
                c.inputStream.use { input ->
                    val buffer = ByteArray(32_768); var received = 0L
                    val started = System.nanoTime(); val deadline = started + 8_000_000_000
                    while (received < size && System.nanoTime() < deadline) {
                        currentCoroutineContext().ensureActive()
                        if (!guard()) return@runCatching null
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - received).toInt())
                        if (n < 0) break
                        received += n
                    }
                    val elapsed = System.nanoTime() - started
                    if (received < 131_072 || elapsed < 1_000_000) null else received * 8 * 1_000_000_000 / elapsed
                }
            } finally { c.disconnect() }
        }.getOrNull()
    }
    /**
     * Keeps a long session up. Every 2 s it follows the core; every 30 s it proves the tunnel with a
     * real request (three misses in a row, about 40 s, count as a drop); a core stuck reconnecting for
     * 30 s, an ended core or a changed network that no longer carries data are drops too. A drop is
     * repaired by a fresh connect -- the remembered method first, so usually seconds -- and while the
     * user has not disconnected, again and again with a growing pause (10 s up to 2 min).
     */
    private suspend fun observeTunnel() {
        ready.first { it }
        var lastNetwork = FluxNet.current(app).key
        var lastCheck = 0L
        var misses = 0
        var stuckSince = 0L
        var retryAt = 0L
        while (currentCoroutineContext().isActive) {
            delay(2_000)
            val now = android.os.SystemClock.elapsedRealtime()
            if (connectionJob?.isActive == true) { stuckSince = 0; misses = 0; lastCheck = now; continue }
            val phase = connection.value.phase
            // A drop the last repair could not mend: try again after the pause.
            if (keepAlive && phase == WsPhase.ERROR) {
                if (retryAt == 0L) retryAt = now + backoff()
                if (now >= retryAt && FluxNet.online(app)) { retryAt = 0; repair("retry ${reconnectFailures + 1}") }
                continue
            }
            retryAt = 0
            if (phase !in setOf(WsPhase.CONNECTED, WsPhase.CONNECTING)) continue
            val method = connection.value.method ?: continue
            val wireGuard = method.protocol == WsProtocol.WIREGUARD
            val up: Boolean
            val reconnecting: Boolean
            if (wireGuard) {
                val mine = wireGuardIds().values
                val st = AmzEngine.connect.value
                up = st is AmzConnect.Connected && st.serverId in mine
                reconnecting = st is AmzConnect.Connecting && st.serverId in mine
            } else {
                val ovpn = OpenVpnRuntime.connection.value
                val ours = ovpn.profileId?.startsWith(PROFILE_PREFIX) == true
                up = ours && ovpn.phase == ConnectionPhase.CONNECTED && ovpn.detail != "VERIFYING"
                reconnecting = ours && ovpn.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING) ||
                    (ours && ovpn.detail == "VERIFYING")
            }
            if (reconnecting) {
                if (stuckSince == 0L) stuckSince = now
                mutableConnection.value = connection.value.copy(phase = WsPhase.CONNECTING)
                if (now - stuckSince > 30_000) { stuckSince = 0; repair("stuck reconnecting") }
                continue
            }
            stuckSince = 0
            if (!up) {
                if (keepAlive) repair("core ended")
                else { mutableConnection.value = WsConnection(WsPhase.ERROR, method, error = WsError.TIMEOUT); stopOwned(); connectedAccount = null }
                continue
            }
            if (phase != WsPhase.CONNECTED) {
                com.mlmvpn.scanner.CrashReporter.note("windscribe connected ${method.protocol}")
                mutableConnection.value = connection.value.copy(phase = WsPhase.CONNECTED)
            }
            val network = FluxNet.current(app).key
            val due = network != lastNetwork || now - lastCheck >= (if (misses > 0) 10_000 else 30_000)
            if (!due) continue
            lastCheck = now
            if (network != lastNetwork) { lastNetwork = network; misses = 2 } // one miss on a new network is enough
            if (alive(wireGuard)) { misses = 0; reconnectFailures = 0; continue }
            misses++
            Log.i(TAG, "health check missed ($misses)")
            if (misses >= 3) { misses = 0; if (keepAlive) repair("no data through the tunnel") }
        }
    }

    private fun backoff(): Long = (10_000L shl reconnectFailures.coerceAtMost(4)).coerceAtMost(120_000L)

    /** Another engine of this app, or another VPN app, has the device's VPN now: never fought for. */
    private fun takenOver(): Boolean {
        val ids = wireGuardIds().values
        val ovpn = OpenVpnRuntime.connection.value
        val amz = AmzEngine.connect.value
        return (ovpn.active && ovpn.profileId?.startsWith(PROFILE_PREFIX) != true) ||
            (amz is AmzConnect.Connected && amz.serverId !in ids) || (amz is AmzConnect.Connecting && amz.serverId !in ids) ||
            (MyVpnService.isRunning && MyVpnService.connectedNodeIdFlow.value.let { it != null && it != AmzEngine.NODE_ID }) ||
            com.mlmvpn.core.tunnel.TunnelStatus.isActive() ||
            android.net.VpnService.prepare(app) != null
    }

    private fun repair(why: String) {
        if (takenOver()) {
            Log.i(TAG, "not repairing ($why): another tunnel has the VPN")
            keepAlive = false; connectedAccount = null
            mutableConnection.value = WsConnection()
            return
        }
        reconnectFailures++
        Log.i(TAG, "repairing the tunnel: $why")
        connect(app)
    }

    /**
     * One real request through the tunnel. WireGuard inside Cloudflare is an Xray tunnel that leaves
     * this app outside, so it goes through the tunnel's own HTTP inbound; everything else through
     * the VPN network the app is inside.
     */
    private suspend fun alive(wireGuard: Boolean): Boolean = withContext(Dispatchers.IO) {
        val viaProxy = wireGuard && (AmzEngine.connect.value as? AmzConnect.Connected)?.via == "cf"
        runCatching {
            val url = URL("https://www.gstatic.com/generate_204")
            val c = (if (viaProxy) url.openConnection(java.net.Proxy(java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress("127.0.0.1", LocalPort.get(app) + LocalPort.PROBE_OFFSET)))
                else awaitVpn().openConnection(url)) as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 8_000; c.useCaches = false
            try { c.responseCode == 204 } finally { c.disconnect() }
        }.getOrDefault(false)
    }
}
