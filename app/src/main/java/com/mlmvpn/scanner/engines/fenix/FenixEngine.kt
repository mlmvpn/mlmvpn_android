package com.mlmvpn.scanner.engines.fenix

import kotlinx.coroutines.ensureActive

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.utils.LocalPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * «FENIX»: the free Firefox VPN allowance of the user's own Firefox accounts, as a tunnel.
 *
 * Mozilla gives every Firefox account 50 GB a MONTH of its browser VPN: Fastly MASQUE proxies in
 * 33 countries, reached over TLS with an HTTP/2 CONNECT per connection and a short-lived "proxy
 * pass" as the credential. FENIX runs that on the app's own Xray core ([FenixConfig]) behind the
 * usual TUN, so it carries the whole phone; and it hands the same exit to MAE ([FenixRoute]).
 *
 * Several accounts: each connection takes the next account in turn (the one used longest ago),
 * after proving it -- a fresh pass from Guardian, with allowance left. While connected, the
 * account's allowance is re-read every few minutes, and when it runs low the tunnel moves to the
 * next account on its own.
 *
 * What makes it fast and steady:
 *  - one HTTP/2 session carries every connection (one TLS handshake for the phone), and the server
 *    is dialled by address -- no lookup on the way up;
 *  - a second path to the same server waits in the tunnel, and Xray moves new connections to it
 *    within a minute if the first stops answering;
 *  - the pass is renewed before it runs out, and a renewal that restarts the tunnel waits for the
 *    screen to be off, so the user does not see it;
 *  - "connected" means a real request went through the tunnel, never just a started core.
 */
object FenixEngine {

    private const val TAG = "FENIX"
    const val NODE_ID = "fenix"

    /** Below this an account counts as used up: its last few hundred MB are not worth a connect. */
    private const val MIN_LEFT_BYTES = 200L * 1024 * 1024
    /** How often the connected account's allowance is re-read (screen on). */
    private const val QUOTA_CONNECTED_MS = 3 * 60_000L
    /** How often one account may be asked at all, outside a connect. */
    private const val QUOTA_MIN_GAP_MS = 60_000L
    /** A site's browser check passed this lately is not opened again for the same refusal. */
    private const val CHECK_AGAIN_MS = 2 * 60_000L

    private lateinit var app: Context
    private lateinit var store: FenixStore
    private lateinit var auth: FenixAuth
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val passLock = Mutex()
    @Volatile private var initialized = false

    // ── state the screen reads ─────────────────────────────────────────────────────────────────

    /** One account, as the accounts page shows it. Never carries a token. */
    data class AccountView(
        val id: String,
        val email: String,
        val remainingBytes: Long?,
        val limitBytes: Long?,
        /** Epoch seconds of the monthly refill. */
        val resetAt: Long?,
        /** When Guardian last reported the numbers (ms), or 0. */
        val quotaAt: Long,
        val signedOut: Boolean,
        val active: Boolean,
    ) {
        val exhausted: Boolean get() = remainingBytes != null && remainingBytes < MIN_LEFT_BYTES &&
            (resetAt == null || resetAt > System.currentTimeMillis() / 1000)
    }

    /** The sign-in form's own state (adding an account). */
    sealed class SignIn {
        object Form : SignIn()
        object Busy : SignIn()
        /** Mozilla mailed a code; [verifyCode] next. */
        data class NeedCode(val email: String) : SignIn()
        /** The account was added: the form closes. */
        object Done : SignIn()
    }

    enum class Failure { SIGNED_OUT, CHALLENGE, QUOTA, OFFLINE, SERVER_DOWN, NO_DATA, VPN_REFUSED, WRONG_PASSWORD, WRONG_CODE, UNKNOWN }

    sealed class Conn {
        object Idle : Conn()
        data class Connecting(val step: Step) : Conn()
        data class Connected(val country: String, val exitCountry: String?, val latencyMs: Long?, val email: String = "") : Conn()
        data class Failed(val why: Failure) : Conn()
    }

    enum class Step { PASS, SERVER, TUNNEL, CHECK, SWITCH }

    private val _accounts = MutableStateFlow<List<AccountView>>(emptyList())
    val accounts: StateFlow<List<AccountView>> = _accounts

    private val _signIn = MutableStateFlow<SignIn>(SignIn.Form)
    val signIn: StateFlow<SignIn> = _signIn

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn

    /** An error from the last sign-in step, for the form. */
    private val _signInError = MutableStateFlow<Failure?>(null)
    val signInError: StateFlow<Failure?> = _signInError

    /** Set when Mozilla wants its browser check passed; the screen opens the page. */
    private val _challenge = MutableStateFlow(false)
    val challenge: StateFlow<Boolean> = _challenge

    /** The host that asked for the check: its own page is the one opened (accounts or Guardian). */
    private val _challengeHost = MutableStateFlow(FenixAuth.CHALLENGE_HOST)
    val challengeHost: StateFlow<String> = _challengeHost

    /** When each site's check was last passed here: a 406 just after one opens no page again. */
    private val checkedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Mozilla asked for its browser check at [e]'s host: the screen opens that host's page, unless
     * that site's check was passed a moment ago and the call is still refused, which would open the
     * page over and over; then the connection only fails with the check's message.
     */
    private fun askCheck(e: FenixAuth.ChallengeNeeded) {
        val site = FenixHttp.siteOf(e.host)
        if (System.currentTimeMillis() - (checkedAt[site] ?: 0L) < CHECK_AGAIN_MS) {
            Log.w(TAG, "check at ${e.host} passed lately, still refused")
            return
        }
        _challengeHost.value = e.host
        _challenge.value = true
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pings: StateFlow<Map<String, Long>> = _pings
    private val _pinging = MutableStateFlow(false)
    val pinging: StateFlow<Boolean> = _pinging

    private val _servers = MutableStateFlow<List<FenixServers.Server>>(emptyList())
    val servers: StateFlow<List<FenixServers.Server>> = _servers

    private val _country = MutableStateFlow(FenixServers.FASTEST)
    val country: StateFlow<String> = _country

    @Volatile private var wantConnected = false
    @Volatile private var connectJob: Job? = null
    @Volatile private var watchJob: Job? = null
    /** What the running tunnel was built from, for its renewal. */
    @Volatile private var running: Running? = null

    private data class Running(val acctId: String, val server: FenixServers.Server, val paths: List<String>, val http1: Boolean)

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        store = FenixStore(app)
        auth = FenixAuth(store)
        _country.value = store.country
        _servers.value = FenixServers.cached(store)
        _pings.value = store.pings()
        publishAccounts()
        observeSession()
        initialized = true
        scope.launch { refreshServers(force = false) }
    }

    fun isInitialized() = initialized

    /** For MyVpnService: a FENIX config owns its routing and runs on an IPv4-only TUN. */
    fun isFenixConfig(remarks: String?): Boolean = FenixConfig.isFenix(remarks)
    fun hasAccounts(): Boolean = initialized && store.accounts().isNotEmpty()
    fun isOurs(): Boolean = MyVpnService.connectedNodeIdFlow.value == NODE_ID && MyVpnService.connectionPhaseFlow.value != MyVpnService.Phase.IDLE

    private fun observeSession() {
        scope.launch {
            kotlinx.coroutines.flow.combine(MyVpnService.connectionPhaseFlow, MyVpnService.connectedNodeIdFlow) { phase, id ->
                phase != MyVpnService.Phase.IDLE && id == NODE_ID
            }.collect { ours ->
                if (!ours && _conn.value is Conn.Connected) {
                    _conn.value = Conn.Idle
                    wantConnected = false
                    watchJob?.cancel()
                    running = null
                }
            }
        }
    }

    private fun publishAccounts() {
        val active = store.activeId
        _accounts.value = store.accounts().map { a ->
            AccountView(a.id, a.email, a.quota?.remaining, a.quota?.limit, a.quota?.resetAt, a.quota?.at ?: 0L, a.signedOut, a.id == active)
        }
    }

    // ── adding and removing accounts ───────────────────────────────────────────────────────────

    fun signIn(email: String, password: String) {
        _signInError.value = null
        _signIn.value = SignIn.Busy
        scope.launch {
            try {
                val needsCode = auth.signIn(email, password)
                if (needsCode) _signIn.value = SignIn.NeedCode(email.trim()) else afterSignIn()
            } catch (e: Exception) {
                _signIn.value = SignIn.Form
                _signInError.value = signInFailure(e)
            }
        }
    }

    fun verifyCode(code: String) {
        val email = (_signIn.value as? SignIn.NeedCode)?.email ?: return
        _signInError.value = null
        _signIn.value = SignIn.Busy
        scope.launch {
            try {
                auth.verifyCode(code)
                afterSignIn()
            } catch (e: Exception) {
                _signIn.value = SignIn.NeedCode(email)
                _signInError.value = if (e is FenixAuth.ApiError) Failure.WRONG_CODE else signInFailure(e)
            }
        }
    }

    /**
     * The account the user signed in to (or made) on Mozilla's own page in the app's WebView: that
     * page keeps its Firefox Accounts session in the WebView's storage, and the session is all the
     * OAuth grant needs -- no password ever passes through the app.
     */
    fun signInWithSession(email: String, sessionToken: String) {
        if (_signIn.value is SignIn.Busy) return
        _signInError.value = null
        _signIn.value = SignIn.Busy
        scope.launch {
            try {
                auth.signInWithSession(email, sessionToken)
                afterSignIn()
            } catch (e: Exception) {
                Log.w(TAG, "web sign-in: ${e.javaClass.simpleName}")
                _signIn.value = SignIn.Form
                _signInError.value = signInFailure(e)
            }
        }
    }

    /** Back to an empty form (the form was closed, or "add another"). */
    fun resetSignIn() { _signIn.value = SignIn.Form; _signInError.value = null }

    private fun signInFailure(e: Exception): Failure = when (e) {
        is FenixAuth.ChallengeNeeded -> { askCheck(e); Failure.CHALLENGE }
        // 102: unknown account, 103: wrong password.
        is FenixAuth.ApiError -> if (e.errno == 102 || e.errno == 103) Failure.WRONG_PASSWORD else Failure.UNKNOWN
        is java.io.IOException -> Failure.OFFLINE
        else -> Failure.UNKNOWN
    }

    /** Switches the free allowance on (a fresh account has it off) and reads its allowance. */
    private suspend fun afterSignIn() {
        val id = auth.lastSignedIn ?: return
        runCatching { FenixGuardian.activate(auth.accessToken(id)) }
            .onFailure { Log.w(TAG, "activate: ${it.javaClass.simpleName}") }
        runCatching { readQuota(id) }
        if (store.activeId == null) store.activeId = id
        publishAccounts()
        _signIn.value = SignIn.Done
    }

    fun removeAccount(context: Context, id: String) {
        init(context)
        if (running?.acctId == id && isOurs()) disconnectAsync(context)
        store.remove(id)
        publishAccounts()
    }

    /**
     * The user passed Mozilla's browser check at [host] in the WebView: its cookies (by the host
     * they came from) carry the API calls, in that WebView's name ([userAgent]) if they must.
     */
    fun challengePassed(host: String, cookies: Map<String, String?>, userAgent: String?) {
        for ((h, header) in cookies) FenixHttp.cookies.importFromWebView(h, header)
        FenixHttp.checkPassed(host, userAgent)
        checkedAt[FenixHttp.siteOf(host)] = System.currentTimeMillis()
        _challenge.value = false
        if (_signInError.value == Failure.CHALLENGE) _signInError.value = null
    }

    fun dismissChallenge() { _challenge.value = false }

    // ── passes and allowances ──────────────────────────────────────────────────────────────────

    /** A fresh pass for account [id] from Guardian, with a second try on a revoked access token. */
    private suspend fun fetchPass(id: String): FenixStore.Pass = try {
        FenixGuardian.pass(auth.accessToken(id))
    } catch (e: FenixGuardian.Rejected) {
        FenixGuardian.pass(auth.accessToken(id, force = true))
    }

    private fun quotaOf(p: FenixStore.Pass) =
        FenixStore.Quota(p.quotaLimit, p.quotaRemaining, p.quotaReset, System.currentTimeMillis())

    /**
     * The tunnel's pass for account [id], good for at least [minLeftS] more seconds: the stored one,
     * or a new one (which also refreshes the allowance). One at a time.
     */
    private suspend fun tunnelPass(id: String, force: Boolean = false, minLeftS: Long = 300): FenixStore.Pass = passLock.withLock {
        val cur = store.account(id)?.pass
        if (!force && cur != null && cur.expiresAt - FenixAuth.now() > minLeftS) return@withLock cur
        val pass = fetchPass(id)
        store.update(id) { a -> a.copy(pass = pass, quota = if (pass.quotaRemaining != null || pass.quotaLimit != null) quotaOf(pass) else a.quota) }
        publishAccounts()
        Log.i(TAG, "pass renewed: ${(pass.expiresAt - pass.issuedAt) / 60} min, quota left ${pass.quotaRemaining}")
        pass
    }

    /**
     * Reads account [id]'s allowance now. Guardian reports it only with a new pass (the X-Quota-*
     * headers), so a pass is fetched for it and only the numbers are kept: the tunnel keeps the pass
     * it was built with. A 429 means the month's allowance is gone.
     */
    private suspend fun readQuota(id: String): FenixStore.Quota? {
        return try {
            val p = fetchPass(id)
            val q = quotaOf(p)
            store.update(id) { it.copy(quota = q, signedOut = false) }
            q
        } catch (e: FenixGuardian.QuotaExhausted) {
            val q = FenixStore.Quota(store.account(id)?.quota?.limit, 0L, store.account(id)?.quota?.resetAt, System.currentTimeMillis())
            store.update(id) { it.copy(quota = q) }
            q
        } finally {
            publishAccounts()
        }
    }

    private val askedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Refreshes every account's numbers (the accounts page's pull, and its opening). */
    fun refreshAll(force: Boolean = false) {
        if (!initialized || _refreshing.value) return
        _refreshing.value = true
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                for (a in store.accounts()) {
                    if (a.signedOut) continue
                    if (!force && now - (askedAt[a.id] ?: 0L) < QUOTA_MIN_GAP_MS) continue
                    askedAt[a.id] = now
                    runCatching { readQuota(a.id) }.onFailure { e ->
                        Log.w(TAG, "quota ${a.id}: ${e.javaClass.simpleName}")
                        if (e is FenixAuth.ChallengeNeeded) askCheck(e)
                    }
                }
            } finally {
                publishAccounts()
                _refreshing.value = false
            }
        }
    }

    /**
     * The account for the next connection, proven usable: the one used longest ago first (so every
     * connection takes the next account in turn), skipping signed-out and used-up ones. "Proven"
     * is a fresh pass from Guardian with allowance left -- what the tunnel will actually carry.
     * Returns the account and its pass, or the failure that stopped every one.
     */
    private suspend fun pickAccount(exclude: Set<String> = emptySet()): Pair<String, FenixStore.Pass>? {
        val order = store.accounts()
            .filter { !it.signedOut && it.id !in exclude }
            .sortedBy { it.lastUsedAt }
        var last: Failure? = null
        for (a in order) {
            val q = a.quota
            val reset = q?.resetAt
            // Known to be used up until its refill: not even asked.
            if (q?.remaining != null && q.remaining < MIN_LEFT_BYTES && (reset == null || reset > FenixAuth.now()) &&
                System.currentTimeMillis() - q.at < 6 * 3600_000L) { last = Failure.QUOTA; continue }
            try {
                val pass = tunnelPass(a.id, force = true)
                if (pass.quotaRemaining != null && pass.quotaRemaining < MIN_LEFT_BYTES) {
                    Log.i(TAG, "account ${a.id}: allowance used up, next"); last = Failure.QUOTA; continue
                }
                return a.id to pass
            } catch (e: FenixGuardian.QuotaExhausted) {
                store.update(a.id) { it.copy(quota = FenixStore.Quota(it.quota?.limit, 0L, it.quota?.resetAt, System.currentTimeMillis())) }
                last = Failure.QUOTA
            } catch (e: FenixAuth.SignedOut) {
                last = Failure.SIGNED_OUT
            } catch (e: FenixAuth.ChallengeNeeded) {
                askCheck(e); lastPickFailure = Failure.CHALLENGE; publishAccounts(); return null
            } catch (e: java.io.IOException) {
                // The network, not the account: no point asking the others.
                lastPickFailure = Failure.OFFLINE; publishAccounts(); return null
            } catch (e: Exception) {
                Log.w(TAG, "account ${a.id}: ${e.javaClass.simpleName}"); last = Failure.UNKNOWN
            }
        }
        publishAccounts()
        lastPickFailure = last ?: Failure.SIGNED_OUT
        return null
    }

    @Volatile private var lastPickFailure: Failure = Failure.UNKNOWN

    // ── servers and countries ──────────────────────────────────────────────────────────────────

    suspend fun refreshServers(force: Boolean) {
        val list = FenixServers.list(store, force)
        if (list.isNotEmpty()) _servers.value = list
    }

    fun setCountry(context: Context, code: String) {
        init(context)
        if (code == store.country) return
        store.country = code
        _country.value = code
        // A new country while connected: the tunnel moves there now.
        if (isOurs() && wantConnected) connectAsync(context, owner = gameSessionId)
    }

    /** Measures every country from this line (a real TLS handshake with each server). */
    fun pingAll() {
        if (_pinging.value) return
        _pinging.value = true
        scope.launch {
            try {
                if (_servers.value.isEmpty()) refreshServers(force = true)
                val acc = HashMap(_pings.value)
                // The servers answer from several threads at once: one lock around the map, or a
                // copy taken while another answer is written throws (crash #471).
                val result = FenixServers.pingAll(_servers.value.filter { it.country != FenixServers.RECOMMENDED }) { cc, ms ->
                    synchronized(acc) {
                        acc[cc] = ms
                        _pings.value = HashMap(acc)
                    }
                }
                store.savePings(result)
                _pings.value = result
            } finally {
                _pinging.value = false
            }
        }
    }

    /** The server the current choice means, fastest first for [FenixServers.FASTEST]. */
    private suspend fun candidates(): List<FenixServers.Server> {
        if (_servers.value.isEmpty()) refreshServers(force = true)
        val all = _servers.value.ifEmpty { return emptyList() }
        val real = all.filter { it.country != FenixServers.RECOMMENDED }
        return when (val c = store.country) {
            FenixServers.RECOMMENDED -> all.filter { it.country == FenixServers.RECOMMENDED } + fastestFirst(real).take(2)
            FenixServers.FASTEST -> fastestFirst(real)
            else -> real.filter { it.country == c }
        }
    }

    private suspend fun fastestFirst(list: List<FenixServers.Server>): List<FenixServers.Server> {
        // Measured within the last half hour on this network, or measured now (a second or two).
        if (System.currentTimeMillis() - store.pingsAt > 30 * 60_000L || _pings.value.isEmpty()) {
            val m = FenixServers.pingAll(list)
            store.savePings(m); _pings.value = m
        }
        val p = _pings.value
        return list.filter { (p[it.country] ?: -1) >= 0 }.sortedBy { p[it.country] }
    }

    // ── connect / disconnect ───────────────────────────────────────────────────────────────────

    private val connectionOwner = com.mlmvpn.scanner.utils.ConnectionOwner()
    @Volatile private var gameSessionId: String? = null

    fun connectAsync(context: Context, owner: String? = null): Job = connectionOwner.replace(owner) {
        init(context)
        val previous = connectJob
        val previousWatch = watchJob
        previous?.cancel()
        previousWatch?.cancel()
        scope.launch {
            previous?.join()
            previousWatch?.join()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            wantConnected = true
            gameSessionId = owner
            try { connect() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "connect crashed", e)
                if (wantConnected) _conn.value = Conn.Failed(Failure.UNKNOWN)
            }
        }.also { connectJob = it }
    }

    fun disconnectAsync(context: Context, owner: String? = null) = connectionOwner.stop(owner) {
        init(context)
        wantConnected = false
        connectJob?.cancel(); watchJob?.cancel()
        running = null
        _conn.value = Conn.Idle
        if (MyVpnService.connectedNodeIdFlow.value == NODE_ID) {
            com.mlmvpn.scanner.utils.SafeStart.service(context.applicationContext, Intent(context.applicationContext, MyVpnService::class.java).apply {
                action = "STOP"
                putExtra(com.mlmvpn.scanner.utils.GameSessionOwner.EXPECTED, owner)
            })
        }
    }

    private suspend fun connect() {
        _conn.value = Conn.Connecting(Step.PASS)
        if (store.accounts().isEmpty()) { fail(Failure.SIGNED_OUT); return }

        _conn.value = Conn.Connecting(Step.SERVER)
        val servers = candidates()
        if (servers.isEmpty()) { fail(if (_servers.value.isEmpty()) Failure.OFFLINE else Failure.SERVER_DOWN); return }

        // The first server (of at most three) that answers a TLS handshake from here, and the
        // paths to it: its address that answered, and its other one as the standby.
        var target: Pair<FenixServers.Server, List<String>>? = null
        for (server in servers.take(3)) {
            if (!wantConnected) return
            val addrs = FenixServers.resolve(server.host)
            val alive = addrs.firstOrNull { FenixServers.handshakeMs(server, 4_000, it) != null } ?: continue
            val standby = addrs.firstOrNull { it != alive && (it is Inet6Address) != (alive is Inet6Address) }
                ?: addrs.firstOrNull { it != alive }
            target = server to listOfNotNull(alive, standby).map { it.hostAddress!!.substringBefore('%') }
            break
        }
        val (server, paths) = target ?: run { fail(Failure.SERVER_DOWN); return }

        // Up to two accounts: a tunnel that carries nothing with one is tried once with the next,
        // in case it was the account and not the network.
        val tried = HashSet<String>()
        repeat(2) {
            if (!wantConnected) return
            _conn.value = Conn.Connecting(Step.PASS)
            val (acctId, pass) = pickAccount(tried) ?: run {
                if (tried.isEmpty()) { fail(lastPickFailure); return } else { stopService(); fail(Failure.NO_DATA); return }
            }
            tried += acctId
            _conn.value = Conn.Connecting(Step.TUNNEL)
            withContext(Dispatchers.IO) { com.mlmvpn.scanner.ui.tunnel.TunnelExclusion.releaseForXray(app) }
            val ms = startTunnel(acctId, server, paths, pass)
            if (ms == VPN_REFUSED) { fail(Failure.VPN_REFUSED); return }
            if (ms != null) {
                _conn.value = Conn.Connected(server.country, store.exitSeen()[server.country], ms, store.account(acctId)?.email.orEmpty())
                afterConnected(server)
                return
            }
        }
        stopService()
        fail(Failure.NO_DATA)
    }

    private const val VPN_REFUSED = -2L

    /**
     * For the game booster: a tunnel config for the best reachable server and an account with a
     * pass, to be measured in the probe process. Nothing of the engine's own state changes; null
     * when signed out or no server answers.
     */
    suspend fun probeConfig(context: Context): String? {
        init(context)
        if (store.accounts().isEmpty()) return null
        for (server in candidates().take(2)) {
            val addrs = FenixServers.resolve(server.host)
            val alive = addrs.firstOrNull { FenixServers.handshakeMs(server, 4_000, it) != null } ?: continue
            val (_, pass) = pickAccount() ?: return null
            return compile(server, listOf(alive.hostAddress!!.substringBefore('%')), pass.token, store.http1)
        }
        return null
    }

    /**
     * Brings the tunnel up on [server] with account [acctId]'s [pass], HTTP/2 first (or what worked
     * last time), then the other. Returns the canary's time, null when nothing went through, or
     * [VPN_REFUSED].
     */
    private suspend fun startTunnel(acctId: String, server: FenixServers.Server, paths: List<String>, pass: FenixStore.Pass): Long? {
        for (http1 in listOf(store.http1, !store.http1)) {
            if (!wantConnected) return null
            if (!launch(compile(server, paths, pass.token, http1))) {
                if (MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.FAILED) return VPN_REFUSED
                continue
            }
            if (_conn.value is Conn.Connecting) _conn.value = Conn.Connecting(Step.CHECK)
            val ms = canary(3)
            if (ms != null) {
                if (http1 != store.http1) store.http1 = http1
                running = Running(acctId, server, paths, http1)
                store.activeId = acctId
                store.update(acctId) { it.copy(lastUsedAt = System.currentTimeMillis()) }
                publishAccounts()
                Log.i(TAG, "CONNECTED ${server.country} ${server.host} account $acctId via ${paths.first()} h${if (http1) 1 else 2} in ${ms} ms")
                return ms
            }
            Log.w(TAG, "${server.host}: no request went through over HTTP/${if (http1) "1.1" else "2"} (account $acctId)")
        }
        return null
    }

    private fun fail(f: Failure) {
        Log.w(TAG, "failed: $f")
        if (wantConnected) _conn.value = Conn.Failed(f)
        wantConnected = false
    }

    private fun compile(server: FenixServers.Server, paths: List<String>, token: String, http1: Boolean): String {
        val sink = com.mlmvpn.scanner.utils.XrayJsonGenerator.quicRefusalOutbound()
        val cfg = FenixConfig.tunnel(server, paths, token, LocalPort.get(app), sink, http1, mobile = onCellular())
        return if (sink == null) cfg else JSONObject(cfg).also { com.mlmvpn.scanner.utils.XrayJsonGenerator.addQuicRefusalPolicyTo(it) }.toString()
    }

    /**
     * A config for MyVpnService's own reconnect after a network drop, and for the Quick Settings
     * tile: the running server and account with a pass that is still good. Null sends the caller
     * to [connectAsync] (or to what it had).
     */
    fun freshConfig(context: Context): String? {
        init(context)
        val r = running ?: return null
        val pass = runCatching { runBlocking(Dispatchers.IO) { tunnelPass(r.acctId) } }.getOrNull() ?: return null
        return compile(r.server, r.paths, pass.token, r.http1)
    }

    private suspend fun launch(cfg: String): Boolean {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val wasUp = MyVpnService.connectionPhaseFlow.value == MyVpnService.Phase.CONNECTED
        com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply {
            putExtra("NODE_URI", cfg)
            putExtra("MTU_PROFILE", com.mlmvpn.scanner.utils.NetworkSettings.Method.FENIX.id)
            putExtra("NODE_ID", NODE_ID)
            putExtra(com.mlmvpn.scanner.utils.GameSessionOwner.EXTRA, gameSessionId)
            putExtra("PROXY_MODE", false)
            putExtra("LOCAL_PORT", LocalPort.getString(app))
        })
        if (wasUp) withTimeoutOrNull(5_000) { MyVpnService.connectionPhaseFlow.first { it != MyVpnService.Phase.CONNECTED } }
        var sawConnecting = false
        val phase = withTimeoutOrNull(15_000) {
            MyVpnService.connectionPhaseFlow.first { p ->
                if (p == MyVpnService.Phase.CONNECTING) sawConnecting = true
                (p == MyVpnService.Phase.CONNECTED && MyVpnService.connectedNodeIdFlow.value == NODE_ID) ||
                    (sawConnecting && (p == MyVpnService.Phase.IDLE || p == MyVpnService.Phase.FAILED))
            }
        }
        return phase == MyVpnService.Phase.CONNECTED
    }

    private fun stopService() {
        if (MyVpnService.connectedNodeIdFlow.value == NODE_ID) {
            com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply { action = "STOP" })
        }
    }

    /** A real request through the tunnel's own HTTP inbound: the time it took, or null. */
    private suspend fun canary(attempts: Int): Long? = withContext(Dispatchers.IO) {
        val port = LocalPort.get(app) + 10000
        repeat(attempts) { i ->
            val ms = runCatching {
                val start = System.nanoTime()
                Socket(Proxy.NO_PROXY).use { s ->
                    s.soTimeout = 8_000
                    s.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                    s.getOutputStream().write("GET http://www.gstatic.com/generate_204 HTTP/1.0\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                    if (line.contains(" 204") || line.contains(" 200")) (System.nanoTime() - start) / 1_000_000 else null
                }
            }.getOrNull()
            if (ms != null) return@withContext ms
            if (i < attempts - 1) delay(1_200)
        }
        null
    }

    /** Where Cloudflare sees the tunnel come out (`loc=` of its trace), through the tunnel. */
    private suspend fun exitCountry(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val port = LocalPort.get(app) + 10000
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(okhttp3.Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute().use { r ->
                Regex("(?m)^loc=([A-Z]{2})").find(r.body?.string().orEmpty())?.groupValues?.get(1)
            }
        }.getOrNull()
    }

    // ── after connecting ───────────────────────────────────────────────────────────────────────

    private fun afterConnected(server: FenixServers.Server) {
        watchJob?.cancel()
        watchJob = scope.launch {
            exitCountry()?.let { loc ->
                store.saveExitSeen(server.country, loc)
                (_conn.value as? Conn.Connected)?.let { _conn.value = it.copy(exitCountry = loc) }
            }
            watch()
        }
    }

    /**
     * Two jobs while connected.
     *
     * The allowance: every [QUOTA_CONNECTED_MS] with the screen on, the account's numbers are read
     * again; when it is used up (or Guardian refuses its pass with 429) the tunnel moves to the
     * next account that has allowance left.
     *
     * The pass: a new pass needs a new config, and a new config restarts the core -- about a second
     * without connections. So the renewal is made early, from three quarters of the pass's life,
     * but only while the screen is off; with the screen on all the way it is made two minutes
     * before expiry regardless, because after expiry no new connection would open at all.
     */
    private suspend fun watch() {
        var lastQuotaAt = System.currentTimeMillis()
        while (scope.isActive && wantConnected && isOurs()) {
            delay(30_000)
            val r = running ?: return

            if (screenOn() && System.currentTimeMillis() - lastQuotaAt >= QUOTA_CONNECTED_MS) {
                lastQuotaAt = System.currentTimeMillis()
                askedAt[r.acctId] = lastQuotaAt
                val q = runCatching { readQuota(r.acctId) }.getOrNull()
                if (q?.remaining != null && q.remaining < MIN_LEFT_BYTES) {
                    if (!switchAccount(r, "allowance used up")) return
                    continue
                }
            }

            val pass = store.account(r.acctId)?.pass ?: continue
            val now = FenixAuth.now()
            val life = (pass.expiresAt - pass.issuedAt).coerceAtLeast(600)
            if (now < pass.issuedAt + life * 3 / 4) continue
            if (now < pass.expiresAt - 120 && screenOn()) continue
            val renewed = runCatching { tunnelPass(r.acctId, force = true) }
            val fresh = renewed.getOrNull()
            if (fresh == null) {
                val e = renewed.exceptionOrNull()
                Log.w(TAG, "renewal failed: ${e?.javaClass?.simpleName}")
                if (e is FenixGuardian.QuotaExhausted || e is FenixAuth.SignedOut) {
                    if (!switchAccount(r, "pass refused")) return
                }
                continue
            }
            if (fresh.quotaRemaining != null && fresh.quotaRemaining < MIN_LEFT_BYTES) {
                if (!switchAccount(r, "allowance used up")) return
                continue
            }
            Log.i(TAG, "renewing the tunnel's pass (${if (screenOn()) "deadline" else "screen off"})")
            if (launch(compile(r.server, r.paths, fresh.token, r.http1))) {
                canary(2)?.let { ms -> (_conn.value as? Conn.Connected)?.let { _conn.value = it.copy(latencyMs = ms) } }
            }
        }
    }

    /**
     * Moves the running tunnel to the next account with allowance left. False when there is none:
     * the tunnel is stopped and the screen says the month's allowance is used up everywhere.
     */
    private suspend fun switchAccount(r: Running, why: String): Boolean {
        Log.i(TAG, "account ${r.acctId}: $why -- switching account")
        val (next, pass) = pickAccount(setOf(r.acctId)) ?: run {
            _conn.value = Conn.Failed(Failure.QUOTA); stopService(); wantConnected = false
            return false
        }
        val keep = _conn.value as? Conn.Connected
        _conn.value = Conn.Connecting(Step.SWITCH)
        val ms = startTunnel(next, r.server, r.paths, pass)
        if (ms == null || ms == VPN_REFUSED) { stopService(); fail(Failure.NO_DATA); return false }
        _conn.value = (keep ?: Conn.Connected(r.server.country, null, ms)).copy(latencyMs = ms, email = store.account(next)?.email.orEmpty())
        return true
    }

    private fun screenOn(): Boolean = runCatching { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)

    private fun onCellular(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }.getOrDefault(false)

    // ── for MAE ────────────────────────────────────────────────────────────────────────────────

    /**
     * FENIX as one of MAE's foreign exits: the chosen country's server (the fastest measured one
     * when the choice is automatic) with an account that has allowance left -- the tunnel's active
     * account first -- dialled by address. Null when there is no account, MAE use is off, or before
     * a pass and an address are known. A pass close to its end is renewed in the background, and
     * MAE re-applies when it arrives.
     */
    fun maeRoute(context: Context): FenixRoute? = maeRoutes(context).firstOrNull { it.id == FenixRoute.ID }

    /**
     * FENIX's exits for MAE: the chosen country's server ([maeRoute]) and, when that is not the US,
     * the American one beside it -- for the apps that open only from there (Google Flow), which MAE
     * then proves like any other exit. One account and one pass for both.
     */
    fun maeRoutes(context: Context): List<FenixRoute> {
        init(context)
        if (!store.forMae) return emptyList()
        val usable = store.accounts().filter { a ->
            val q = a.quota
            !a.signedOut && !(q?.remaining != null && q.remaining < MIN_LEFT_BYTES && (q.resetAt ?: Long.MAX_VALUE) > FenixAuth.now())
        }
        val acct = usable.firstOrNull { it.id == store.activeId } ?: usable.firstOrNull() ?: return emptyList()
        val pass = acct.pass
        if (pass == null || pass.expiresAt - FenixAuth.now() < 600) {
            renewForMae(acct.id)
            if (pass == null || pass.expiresAt <= FenixAuth.now() + 60) return emptyList()
        }
        val p = _pings.value
        val real = _servers.value.filter { it.country != FenixServers.RECOMMENDED }
        val chosen = when (val c = store.country) {
            FenixServers.FASTEST, FenixServers.RECOMMENDED -> real.filter { (p[it.country] ?: -1) >= 0 }.minByOrNull { p[it.country]!! }
            else -> real.firstOrNull { it.country == c }
        }
        val out = mutableListOf<FenixRoute>()
        chosen?.let { s -> maeAddress(s)?.let { out += FenixRoute(s, it, pass.token) } }
        if (chosen?.country != US) {
            real.firstOrNull { it.country == US }?.let { s ->
                maeAddress(s)?.let { out += FenixRoute(s, it, pass.token, FenixRoute.US_ID, FenixRoute.US_TAG) }
            }
        }
        return out
    }

    private const val US = "US"

    private val maeAddresses = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** A literal address for [server], resolved once in the background (MAE asks often, and fast). */
    private fun maeAddress(server: FenixServers.Server): String? {
        maeAddresses[server.host]?.let { return it }
        scope.launch {
            FenixServers.resolve(server.host).firstOrNull()?.hostAddress?.substringBefore('%')?.let { maeAddresses[server.host] = it }
        }
        return null
    }

    @Volatile private var maeRenewing = false

    private fun renewForMae(id: String) {
        if (maeRenewing) return
        maeRenewing = true
        scope.launch {
            try {
                if (runCatching { tunnelPass(id, minLeftS = 600) }.isSuccess) {
                    runCatching { com.mlmvpn.scanner.engines.mae.MaeEngine.applyIfConnected() }
                }
            } finally { maeRenewing = false }
        }
    }

    var forMae: Boolean
        get() = initialized && store.forMae
        set(v) { if (initialized) store.forMae = v }

    /** Seen exits by chosen country, for the picker. */
    fun exitSeen(): Map<String, String> = if (initialized) store.exitSeen() else emptyMap()
}
