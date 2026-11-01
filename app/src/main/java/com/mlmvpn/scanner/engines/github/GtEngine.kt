package com.mlmvpn.scanner.engines.github

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** What the GitHub Tunnel screen shows. One immutable snapshot, replaced on every change. */
data class GtUiState(
    val accounts: List<GtAccount> = emptyList(),
    val signIn: GtSignIn? = null,
    val broker: GtBrokerState = GtBrokerState(),
    val brokerRequired: Int = GtBroker.REQUIRED_VERSION,
    val brokerBusy: Boolean = false,
    val brokerError: String = "",
    /** The live session, or the newest one (so a failure can say how it ended). */
    val session: GtSession? = null,
    /** A session being built: its step, its log, and how it failed. */
    val run: GtRun? = null,
    val link: GtLink = GtLink(),
    val autoRenew: Boolean = true,
    val renewing: Boolean = false,
    val exit: GtExitUi = GtExitUi(),
    val log: List<String> = emptyList(),
)

/** One exit slot on the runner, as runner/exits.mjs › view() reports it. */
data class GtExitInfo(val id: String, val state: String, val country: String, val provider: String,
                      val ip: String = "", val city: String = "", val mbps: Double = 0.0, val rttMs: Int = 0,
                      val udp: Boolean = false, val error: String = "")

/**
 * «کشور خروجی»: what the user asked for, what the runner has, and where the connection is now.
 * `job`: idle | preparing (the runner is bringing exits up) | switching (the VPN moves onto them)
 * | done | partial (some did not come up — theirs leave by the runner's own address) | failed.
 */
data class GtExitUi(
    val prefs: GtExitPrefs = GtExitPrefs(),
    val exits: List<GtExitInfo> = emptyList(),
    /** Countries the runner's VPN Gate list carries, with how many servers each. */
    val vpngate: Map<String, Int> = emptyMap(),
    val psiphon: List<String> = emptyList(),
    val job: String = "idle",
    val jobCountry: String = "",
    val jobError: String = "",
    /** The user the connection leaves by: `direct` or an exit slot. */
    val use: String = "direct",
    /** Every exit slot the connection leaves by — the default one and the site rules'. */
    val inUse: Set<String> = emptySet(),
    /** What websites see, measured through the tunnel itself. */
    val seenCountry: String = "",
    val seenCity: String = "",
    val seenIp: String = "",
    /**
     * Where UDP (calls, games) leaves from, measured with STUN through the tunnel ([GtUdpProbe]):
     * "" not measured yet, `testing`, `ok` (udpIp/udpCountry), or `none` — nothing came back.
     */
    val udpState: String = "",
    val udpIp: String = "",
    val udpCountry: String = "",
    val udpCity: String = "",
)

data class GtSignIn(val userCode: String = "", val verificationUri: String = "", val waiting: Boolean = true,
                    val error: String = "", val adding: Boolean = false)

/** SETTING_UP → STARTING → INSTALLING → CONNECTING_NETWORK → READY, or FAILED with a reason. */
data class GtRun(val step: String = "SETTING_UP", val error: String = "", val code: String = "")

/**
 * The phone's own side: idle, finding a way in, starting the VPN, connected, or failed. `ips` are the
 * clean Cloudflare addresses (with a Worker), `relays` the names of the relays that answered.
 */
data class GtLink(val phase: String = "idle", val ips: List<String> = emptyList(), val delayMs: Long = 0,
                  val error: String = "", val code: String = "", val sessionId: String = "",
                  val relays: List<String> = emptyList())

/**
 * GitHub Tunnel on Android — the same system as the Windows app, end to end on the phone:
 *
 *   1. a GitHub account (device-code sign-in) — the cloud server runs on its Actions allowance;
 *   2. OPTIONAL: a relay Worker on the user's own Cloudflare account ([GtBroker]);
 *   3. a session: the v2 workflow and its agent pushed into the account's private repo, a runner
 *      dispatched with a one-time public key, and its credentials back SEALED to that key;
 *   4. the connection: clean Cloudflare addresses to the Worker AND the runner's own free relays
 *      ([GtRelay] — no Cloudflare at all), each measured through the real outbound, then the whole
 *      Xray config ([GtXray]) handed to [MyVpnService], which adds the tun. Without a Worker, or on
 *      a line where Cloudflare is filtered, the relays alone carry it: a GitHub account is enough.
 *
 * The runner, the workflow and the Worker are the Windows app's own files (assets/gt, synced by
 * scripts/sync-gt-assets.js), so both apps can share a GitHub account without fighting over them.
 */
object GtEngine {
    private const val TAG = "GtEngine"
    private const val POLL_MS = 5_000L
    private const val MAX_WAIT_MS = 8 * 60_000L
    private const val MAX_ACCOUNT_ATTEMPTS = 4
    /** Ahead of the end, when the next session is made ready (a boot takes ~70 s, tunnels ~30 s). */
    private const val RENEW_LEAD_MS = 8 * 60_000L
    /** With no Worker, how long a ready session may take to publish its first relay. */
    private const val RELAY_WAIT_MS = 90_000L
    const val NODE_PREFIX = "gt_"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(GtUiState())
    val state: StateFlow<GtUiState> = _state

    private lateinit var app: Context
    private lateinit var store: GtStore
    private var ticker: Job? = null
    private var signInJob: Job? = null
    private var runJob: Job? = null
    private var linkJob: Job? = null
    private var lastReconcile = 0L
    /** The exit plan the running connection carries, and the session it was made for. */
    private var plan = GtXray.ExitPlan()
    private var planSession = ""
    /** The clean addresses of the running connection — a plan change reuses them, no new scan. */
    private var linkIps: List<String> = emptyList()
    /** The relays of the running connection, the same way. */
    private var linkRelays: List<GtRelay> = emptyList()
    private val exitRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var exitAgain = false
    private var lastStatusAt = 0L

    fun attach(context: Context) {
        if (::app.isInitialized) { refresh(); return }
        app = context.applicationContext
        store = GtStore(app)
        refresh()
        ticker = scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) { Log.w(TAG, "tick: ${e.message}") }
                delay(15_000)
            }
        }
    }

    private fun refresh(change: (GtUiState) -> GtUiState = { it }) {
        val s = store.sessions()
        val session = store.activeSession() ?: s.maxByOrNull { it.createdAt }
        _state.value = change(_state.value).copy(
            accounts = store.accounts(), broker = store.broker(), session = session, autoRenew = store.autoRenew,
            brokerRequired = GtBroker.REQUIRED_VERSION,
        ).let {
            // Before any runner has answered this launch, the last one's offer (see exitCatalog).
            val e = it.exit
            val (vg, ps) = if (e.vpngate.isEmpty() && e.psiphon.isEmpty()) store.exitCatalog() else Pair(e.vpngate, e.psiphon)
            it.copy(exit = e.copy(prefs = store.exitPrefs, vpngate = vg, psiphon = ps))
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _state.value = _state.value.copy(log = (_state.value.log + line).takeLast(40))
    }

    // ── GitHub accounts ─────────────────────────────────────────────────────────
    fun signIn(adding: Boolean) {
        signInJob?.cancel()
        _state.value = _state.value.copy(signIn = GtSignIn(adding = adding))
        signInJob = scope.launch {
            try {
                val dc = GtGithub.startDeviceFlow()
                _state.value = _state.value.copy(signIn = GtSignIn(dc.userCode, dc.verificationUri, true, "", adding))
                val (token, granted) = GtGithub.awaitDeviceToken(dc) { _state.value.signIn?.waiting == true }
                val who = GtGithub.identify(token)
                val (_, created) = store.upsertAccount(who.login, token, who.name, who.avatarUrl, who.scopes.ifBlank { granted })
                refresh { it.copy(signIn = null) }
                toast(if (created) S(R.string.gt_signin_added, who.login) else S(R.string.gt_signin_same, who.login))
            } catch (e: GtGithub.GhError) {
                if (e.code == "CANCELLED") return@launch
                val msg = when (e.code) {
                    "EXPIRED" -> S(R.string.gt_signin_expired)
                    "DENIED" -> S(R.string.gt_signin_denied)
                    else -> e.message.orEmpty()
                }
                _state.value = _state.value.copy(signIn = _state.value.signIn?.copy(waiting = false, error = msg))
            } catch (e: Exception) {
                _state.value = _state.value.copy(signIn = _state.value.signIn?.copy(waiting = false, error = e.message.orEmpty()))
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _state.value = _state.value.copy(signIn = null)
    }

    fun removeAccount(id: String) { store.removeAccount(id); refresh() }

    fun setAccountDisabled(id: String, disabled: Boolean) {
        store.updateAccount(id) { it.copy(disabled = disabled) }
        refresh()
    }

    fun retryAccount(id: String) {
        store.updateAccount(id) { it.copy(health = "OK", healthReason = "", cooldownUntil = 0) }
        refresh()
    }

    // ── the relay Worker ────────────────────────────────────────────────────────
    fun deployBroker(account: CloudAccount) {
        if (_state.value.brokerBusy) return
        _state.value = _state.value.copy(brokerBusy = true, brokerError = "")
        scope.launch {
            try {
                log(S(R.string.gt_log_deploying))
                GtBroker.deploy(app, store, account) { }
                log(S(R.string.gt_log_deployed))
                refresh { it.copy(brokerBusy = false) }
            } catch (e: Exception) {
                refresh { it.copy(brokerBusy = false, brokerError = e.message.orEmpty()) }
            }
        }
    }

    fun setCustomUrl(url: String) {
        store.saveBroker(store.broker().copy(customUrl = url.trim().trimEnd('/')))
        refresh()
    }

    // ── sessions ────────────────────────────────────────────────────────────────
    private val runLive get() = runJob?.isActive == true

    /** Build a new cloud session (and, when `connectAfter`, connect to it once it is ready). */
    fun createSession(connectAfter: Boolean = true) {
        if (runLive) return
        runJob = scope.launch {
            try {
                val s = runSession(standby = false)
                if (connectAfter) connectNow(s)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "session failed", e)
                runFailed(e)
            }
        }
    }

    /** One pass over the pool: each account at most once, the reason each one failed logged. */
    private suspend fun runSession(standby: Boolean): GtSession {
        // No Worker is no reason to stop: the runner's own relays carry the session without one.
        if (workerHostOrNull() == null) log(S(R.string.gt_log_no_worker))
        val tried = mutableSetOf<String>()
        var last: Exception? = null
        repeat(MAX_ACCOUNT_ATTEMPTS) { attempt ->
            val account = pickAccount(tried) ?: run {
                if (tried.isEmpty()) {
                    throw fail(if (store.accounts().isEmpty()) "NO_ACCOUNTS" else "NONE_AVAILABLE",
                        if (store.accounts().isEmpty()) S(R.string.gt_err_no_accounts) else S(R.string.gt_err_all_spent))
                }
                throw last ?: fail("NONE_AVAILABLE", S(R.string.gt_err_all_spent))
            }
            tried += account.id
            if (attempt > 0) log(S(R.string.gt_log_next_account, "@${account.login}"))
            try {
                val s = runOnce(account, standby)
                store.updateAccount(account.id) { it.copy(health = "OK", healthReason = "", lastUsedAt = System.currentTimeMillis()) }
                return s
            } catch (e: RunFailure) {
                last = e
                if (e.health.isNotBlank()) {
                    store.updateAccount(account.id) {
                        it.copy(health = e.health, healthReason = e.message.orEmpty().take(200),
                            cooldownUntil = if (e.health == "RATE_LIMITED" || e.health == "DISPATCH_FAILED") System.currentTimeMillis() + 15 * 60_000L else 0)
                    }
                }
                if (!e.retryable) throw e
            }
        }
        throw last ?: fail("NONE_AVAILABLE", S(R.string.gt_err_all_spent))
    }

    private fun pickAccount(exclude: Set<String>): GtAccount? {
        val now = System.currentTimeMillis()
        val busy = store.sessions().filter { it.live || it.status == "STANDBY" }.map { it.accountId }.toSet()
        return store.accounts()
            .filter { it.id !in exclude && !it.disabled && it.health != "AUTH_REQUIRED" && it.health != "EXHAUSTED" && it.cooldownUntil <= now }
            // An account already carrying a live session goes last, never excluded.
            .sortedWith(compareBy<GtAccount> { it.id in busy }.thenBy { it.lastUsedAt })
            .firstOrNull()
    }

    private class RunFailure(message: String, val code: String, val health: String = "", val retryable: Boolean = true) : Exception(message)

    private fun fail(code: String, message: String) = RunFailure(message, code, retryable = false)

    private fun classify(e: Exception): RunFailure {
        if (e is RunFailure) return e
        val msg = e.message.orEmpty()
        val status = (e as? GtGithub.GhError)?.status ?: 0
        return when {
            status == 401 || msg.contains("bad credentials", true) -> RunFailure(S(R.string.gt_err_auth), "AUTH", "AUTH_REQUIRED")
            Regex("spending limit|billing|payment|quota|minutes", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
                RunFailure(S(R.string.gt_err_quota), "QUOTA", "EXHAUSTED")
            Regex("rate limit", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> RunFailure(msg, "RATE", "RATE_LIMITED")
            else -> RunFailure(S(R.string.gt_err_generic, msg.take(160)), "ERROR", "REPO_ERROR")
        }
    }

    private fun assetText(path: String) = app.assets.open("gt/$path").bufferedReader().use { it.readText() }

    private suspend fun runOnce(account: GtAccount, standby: Boolean): GtSession {
        val token = store.token(account.id)
        if (token.isBlank()) throw RunFailure(S(R.string.gt_err_auth), "AUTH", "AUTH_REQUIRED")
        setRun("SETTING_UP")
        log(S(R.string.gt_log_checking_github, "@${account.login}"))
        val manifest = JSONObject(assetText("manifest.json"))
        val workflow = manifest.getJSONObject("workflow")
        val repo = try {
            val r = GtGithub.ensureRepo(token)
            store.updateAccount(account.id) { it.copy(repository = r.fullName, defaultBranch = r.defaultBranch) }
            var changed = GtGithub.ensureFile(token, r.fullName, workflow.getString("repoPath"), assetText(workflow.getString("file")), "tunnel workflow")
            val agent = manifest.getJSONArray("agent")
            for (i in 0 until agent.length()) {
                val f = agent.getJSONObject(i)
                changed = GtGithub.ensureFile(token, r.fullName, f.getString("repoPath"), assetText(f.getString("file")), "tunnel agent") || changed
            }
            log(S(if (changed) R.string.gt_log_infra_updated else R.string.gt_log_infra_ready))
            r
        } catch (e: Exception) { throw classify(e) }

        val kp = GtCrypto.newKeyPair()
        val id = "GM-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + "-" + GtCrypto.randomHex(4)
        var session = store.addSession(GtSession(id = id, accountId = account.id, accountLogin = account.login,
            repository = repo.fullName, status = "SETTING_UP", sealKeyEnc = com.mlmvpn.core.tunnel.SecureStore.encrypt(kp.privateKey)))
        refresh()

        setRun("STARTING")
        log(S(R.string.gt_log_starting_server))
        // The exits the user picked start while the runner boots, so the connection lands on them.
        val inputs = JSONObject().put("session_id", id).put("client_pub", kp.publicKey).put("slot", "")
            .put("exits", store.exitPrefs.dispatchInput())
        var runId = 0L
        var lastErr: Exception? = null
        // A workflow GitHub has only just been handed can answer 404/422 for a few seconds.
        for (i in 0 until 6) {
            try { runId = GtGithub.dispatch(token, repo.fullName, repo.defaultBranch, workflow.getString("name"), inputs); break }
            catch (e: GtGithub.GhError) { lastErr = e; if (e.status != 404 && e.status != 422) break; delay(5000) }
            catch (e: Exception) { lastErr = e; break }
        }
        if (runId == 0L) {
            store.updateSession(id) { it.copy(status = "FAILED", lastError = lastErr?.message.orEmpty().take(200)) }
            throw classify(lastErr ?: Exception("dispatch failed"))
        }
        session = store.updateSession(id) { it.copy(runId = runId, status = "STARTING") } ?: session

        // From here a runner is spending the account's allowance: every failure cancels it.
        try {
            return awaitSession(token, repo.fullName, session, kp.privateKey, standby, manifest.optInt("usableSessionMinutes", 325))
        } catch (e: Exception) {
            GtGithub.cancelRun(token, repo.fullName, runId)
            GtGithub.deleteSessionFile(token, repo.fullName, id)
            throw e
        }
    }

    private suspend fun awaitSession(token: String, repo: String, session: GtSession, priv: String, standby: Boolean, usableMinutes: Int): GtSession {
        setRun("INSTALLING")
        log(S(R.string.gt_log_preparing_tunnel))
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        var sealed: JSONObject? = null
        var payload: JSONObject? = null
        var sawInProgress = false
        var runStartedAt = 0L
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val run = try { GtGithub.getRun(token, repo, session.runId) } catch (_: Exception) { continue }
            run.optString("run_started_at").takeIf { it.isNotBlank() }?.let { runStartedAt = parseIso(it) }
            when (run.optString("status")) {
                "in_progress" -> {
                    if (!sawInProgress) { sawInProgress = true; setRun("CONNECTING_NETWORK"); log(S(R.string.gt_log_server_on)) }
                    val s = try { GtGithub.sessionFile(token, repo, session.id) } catch (_: Exception) { null } ?: continue
                    val p = GtCrypto.open(priv, s, session.id)
                    if (p.optString("phase") == "ready" || p.optString("phase") == "failed") { sealed = s; payload = p; break }
                }
                "completed" -> {
                    val conclusion = run.optString("conclusion")
                    store.updateSession(session.id) { it.copy(status = "FAILED", lastError = "workflow $conclusion") }
                    if (!sawInProgress && (conclusion == "failure" || conclusion == "startup_failure")) {
                        throw RunFailure(S(R.string.gt_err_run_died), "RUN_DIED_EARLY", "EXHAUSTED")
                    }
                    throw RunFailure(S(R.string.gt_err_run_ended, conclusion.ifBlank { "?" }), "RUN_ENDED")
                }
            }
        }
        if (payload == null || payload.optString("phase") != "ready") {
            val why = payload?.optJSONArray("errors")?.optString(0)?.takeIf { it.isNotBlank() }?.let { " (${it.take(120)})" }.orEmpty()
            store.updateSession(session.id) { it.copy(status = "FAILED", lastError = if (payload != null) "runner: ${payload.optString("phase")}" else "timed out") }
            throw if (payload != null) RunFailure(S(R.string.gt_err_no_tunnel, why), "QT_UNAVAILABLE", retryable = false)
            else RunFailure(S(R.string.gt_err_timeout), "SESSION_TIMEOUT", retryable = false)
        }
        var t = try { GtTransport.from(payload) } catch (e: Exception) {
            throw RunFailure(S(R.string.gt_err_generic, e.message.orEmpty()), "BAD_TRANSPORT", retryable = false)
        }
        // With no Worker, only a relay can carry the session — and the runner opens it on its FIRST
        // way in, which may be a quick tunnel. The relays follow within seconds, republished.
        if (workerHostOrNull() == null && t.relays.isEmpty()) {
            log(S(R.string.gt_log_waiting_relays))
            val until = System.currentTimeMillis() + RELAY_WAIT_MS
            while (System.currentTimeMillis() < until && t.relays.isEmpty()) {
                delay(POLL_MS)
                val s = try { GtGithub.sessionFile(token, repo, session.id) } catch (_: Exception) { null } ?: continue
                val nt = try { GtTransport.from(GtCrypto.open(priv, s, session.id)) } catch (_: Exception) { continue }
                if (nt.rev >= t.rev) { t = nt; sealed = s }
            }
            if (t.relays.isEmpty()) {
                store.updateSession(session.id) { it.copy(status = "FAILED", lastError = "no relay") }
                throw RunFailure(S(R.string.gt_err_no_relay), "NO_RELAY", retryable = false)
            }
        }
        val anchor = if (runStartedAt > 0) runStartedAt else GtGithub.serverNow()
        val updated = store.updateSession(session.id) {
            it.copy(status = if (standby) "STANDBY" else "READY", expiresAt = anchor + usableMinutes * 60_000L, runStartedAt = anchor,
                sealed = sealed.toString(), rev = t.rev, runnerCountry = t.runnerCountry, runnerCity = t.runnerCity,
                hosts = t.hosts.size, lastError = "")
        } ?: session
        setRun("READY")
        log(S(R.string.gt_log_session_ready, if (t.runnerCountry.isNotBlank()) " — ${t.runnerCountry}" else ""))
        refresh { it.copy(run = null) }
        return updated
    }

    private fun setRun(step: String, error: String = "", code: String = "") {
        refresh { it.copy(run = GtRun(step, error, code)) }
    }

    private fun parseIso(s: String): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    } catch (_: Exception) { 0L }

    /** The failure of a session build, for the screen. */
    fun runFailed(e: Exception) {
        val f = e as? RunFailure
        refresh { it.copy(run = GtRun("FAILED", e.message.orEmpty(), f?.code.orEmpty())) }
    }

    /** End a session on the account that owns it: cancel the runner, clear its file, mark it. */
    fun endSession(sessionId: String? = null) {
        scope.launch {
            val s = (sessionId?.let { store.session(it) } ?: store.activeSession()) ?: return@launch
            if (connectedTo(s.id)) disconnect()
            endQuietly(s)
            refresh()
        }
    }

    private fun endQuietly(s: GtSession) {
        val token = store.token(s.accountId)
        if (token.isNotBlank() && s.repository.isNotBlank()) {
            if (s.runId > 0) GtGithub.cancelRun(token, s.repository, s.runId)
            GtGithub.deleteSessionFile(token, s.repository, s.id)
        }
        store.updateSession(s.id) { it.copy(status = "EXPIRED", endedAt = System.currentTimeMillis()) }
    }

    fun setAutoRenew(on: Boolean) { store.autoRenew = on; refresh() }

    // ── the connection ──────────────────────────────────────────────────────────
    fun connectedTo(sessionId: String): Boolean =
        MyVpnService.connectedNodeIdFlow.value == NODE_PREFIX + sessionId && MyVpnService.isRunningFlow.value

    fun isConnected(): Boolean = MyVpnService.connectedNodeIdFlow.value?.startsWith(NODE_PREFIX) == true && MyVpnService.isRunningFlow.value

    /** The session's transport, opened with its key. */
    private fun transportOf(s: GtSession): GtTransport =
        GtTransport.from(GtCrypto.open(com.mlmvpn.core.tunnel.SecureStore.decrypt(s.sealKeyEnc), JSONObject(s.sealed), s.id))

    /** The user's Worker, when there is one this app can ride; null means the relays alone. */
    private fun workerHostOrNull(): String? {
        val b = store.broker()
        if (!b.deployed || b.version in 1 until GtBroker.REQUIRED_VERSION) return null
        return try { java.net.URL(b.effectiveUrl).host.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }

    /**
     * The runner's newest hand-off from GitHub, kept when it is at least as new as the stored one:
     * a relay that restarted is under a NEW name, and only the runner knows which.
     */
    private fun refreshSealed(s: GtSession): GtSession {
        val token = store.token(s.accountId)
        if (token.isBlank() || s.repository.isBlank()) return s
        return try {
            val sealed = GtGithub.sessionFile(token, s.repository, s.id) ?: return s
            val p = GtCrypto.open(com.mlmvpn.core.tunnel.SecureStore.decrypt(s.sealKeyEnc), sealed, s.id)
            if (p.optString("phase") != "ready") return s
            val t = GtTransport.from(p)
            if (t.rev < s.rev) return s
            store.updateSession(s.id) { it.copy(sealed = sealed.toString(), rev = t.rev) } ?: s
        } catch (e: Exception) { Log.w(TAG, "hand-off: ${e.message}"); s }
    }

    /** The same from the status channel: its answer is the runner's live state, sealed the same way. */
    private fun noteHandoff(s: GtSession, body: String, p: JSONObject) {
        if (p.optString("phase") != "ready") return
        val fresh = try { GtTransport.from(p) } catch (_: Exception) { return }
        if (fresh.rev < s.rev) return
        val old = try { transportOf(s) } catch (_: Exception) { null }
        if (old != null && old.hosts == fresh.hosts && old.relays == fresh.relays) return
        store.updateSession(s.id) { it.copy(sealed = body, rev = fresh.rev) }
    }

    /** On relays alone, none of the ones the connection rides is still published. */
    private fun relaysMoved(s: GtSession): Boolean {
        if (linkRelays.isEmpty() || linkIps.isNotEmpty()) return false
        val now = try { transportOf(s).relays.map { it.host }.toSet() } catch (_: Exception) { return false }
        return now.isNotEmpty() && linkRelays.none { it.host in now }
    }

    /** Connect to the live session (the caller has VPN consent). */
    fun connect() {
        val s = store.activeSession()
        if (s == null) {
            _state.value = _state.value.copy(link = GtLink("failed", error = S(R.string.gt_err_no_session), code = "NO_SESSION"))
            return
        }
        linkJob?.cancel()
        linkJob = scope.launch { connectNow(s) }
    }

    private suspend fun connectNow(session0: GtSession) {
        _state.value = _state.value.copy(link = GtLink("picking", sessionId = session0.id))
        try {
            // The newest hand-off first: a relay's name can move while the session runs.
            val session = refreshSealed(session0)
            val t = transportOf(session)
            val host = workerHostOrNull()
            val secret = store.installSecret()
            val exp = session.expiresAt.takeIf { it > 0 } ?: (System.currentTimeMillis() + 6 * 3600_000L)
            // Both ways in at once, each through the real outbound: the Worker on clean Cloudflare
            // addresses (when there is one) and the runner's relays. Whatever answers is used.
            val (picked, relayRes) = coroutineScope {
                val cf = async {
                    if (host == null || t.hosts.isEmpty()) emptyList()
                    else {
                        val pass = GtCrypto.signPass(secret, GtXray.label(t.hosts.first()), session.id, exp)
                        GtCleanIp.pick(app, store, { ip -> GtXray.outbound(ip, host, pass, t) }) { n -> log(S(R.string.gt_log_clean_ips, n)) }
                    }
                }
                val rl = async {
                    if (t.relays.isEmpty()) emptyList()
                    else {
                        log(S(R.string.gt_log_relays, t.relays.size))
                        val byHost = t.relays.associateBy { it.host }
                        GtCleanIp.measure(app, t.relays.map { it.host }, { h -> GtXray.relayOutbound(byHost.getValue(h), t) })
                    }
                }
                cf.await() to rl.await()
            }
            val ips = picked.map { it.ip }
            val relays = relayRes.mapNotNull { r -> t.relays.firstOrNull { it.host == r.ip } }
            if (ips.isEmpty() && relays.isEmpty()) {
                throw if (host != null && t.hosts.isNotEmpty() && t.relays.isEmpty()) {
                    val n = GtCleanIp.candidates(app, store, GtCleanIp.networkKey(app)).size
                    fail("NO_CLEAN_IP", S(R.string.gt_err_no_clean_ip, n))
                } else fail("NO_WAY", S(R.string.gt_err_no_way))
            }
            if (ips.isNotEmpty()) log(S(R.string.gt_log_clean_ok, ips.joinToString("، "), picked.first().delayMs.toInt()))
            if (relays.isNotEmpty()) log(S(R.string.gt_log_relay_ok, relays.joinToString("، ") { it.label }, relayRes.first().delayMs.toInt()))
            val best = listOfNotNull(picked.firstOrNull()?.delayMs, relayRes.firstOrNull()?.delayMs).minOrNull() ?: 0L
            val labels = relays.map { it.label }
            _state.value = _state.value.copy(link = GtLink("connecting", ips, best, sessionId = session.id, relays = labels))
            // Marked here, before the service is asked: startService is asynchronous, and the flow
            // may still read CONNECTED from whatever ran before — including this very session.
            MyVpnService.connectionPhaseFlow.value = MyVpnService.Phase.CONNECTING
            // A new session starts on the runner's own address; its exits are asked for once it is up.
            if (planSession != session.id) { plan = GtXray.ExitPlan(); planSession = session.id }
            linkIps = ips
            linkRelays = relays
            startVpn(session, GtXray.config(session, t, host.takeIf { ips.isNotEmpty() }, secret, ips,
                com.mlmvpn.scanner.utils.LocalPort.get(app), plan, relays))
            // What the service reports is the only thing that counts as up; IDLE means it stopped.
            val phase = withTimeoutOrNull(45_000) {
                MyVpnService.connectionPhaseFlow.first { it != MyVpnService.Phase.CONNECTING }
            }
            if (phase == MyVpnService.Phase.CONNECTED) {
                log(S(R.string.gt_log_connected))
                _state.value = _state.value.copy(link = GtLink("connected", ips, best, sessionId = session.id, relays = labels))
                store.updateSession(session.id) { if (it.status == "READY") it.copy(status = "ACTIVE") else it }
                refresh { it.copy(exit = it.exit.copy(use = plan.use, inUse = inUseOf(plan), seenCountry = "", seenCity = "", seenIp = "",
                    udpState = "", udpIp = "", udpCountry = "", udpCity = "")) }
                scheduleExits()
            } else {
                throw fail("VPN_FAILED", S(R.string.gt_log_vpn_failed))
            }
        } catch (e: Exception) {
            val f = e as? RunFailure
            _state.value = _state.value.copy(link = GtLink("failed", error = e.message.orEmpty(), code = f?.code.orEmpty(), sessionId = session0.id))
        }
    }

    private fun startVpn(session: GtSession, config: String) {
        try { Log.i(TAG, "config users: " + GtXray.describeUsers(config, transportOf(session))) } catch (_: Exception) {}
        com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply {
            putExtra("NODE_URI", config)
            putExtra("NODE_ID", NODE_PREFIX + session.id)
            putExtra("PROXY_MODE", com.mlmvpn.scanner.utils.NetworkSettings.proxyMode(app))
            putExtra("LOCAL_PORT", com.mlmvpn.scanner.utils.LocalPort.getString(app))
            putExtra("MTU_PROFILE", com.mlmvpn.scanner.utils.NetworkSettings.Method.GITHUB.id)
        })
    }

    fun disconnect() {
        linkJob?.cancel()
        if (isConnected()) com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, MyVpnService::class.java).apply { action = "STOP" })
        _state.value = _state.value.copy(link = GtLink(),
            exit = _state.value.exit.copy(job = "idle", jobError = "", seenCountry = "", seenCity = "", seenIp = "",
                udpState = "", udpIp = "", udpCountry = "", udpCity = ""))
    }

    // ── the exit country (P4, runner/exits.mjs) ─────────────────────────────────
    // The user picks a country (and, optionally, sites that leave by another); the runner is asked
    // to bring those exits up, and the connection moves onto them as a different VLESS user of the
    // SAME tunnel. Until an exit is ready the traffic leaves by the runner's own address, and the
    // screen says so. Ported from github-tunnel/routes.js › ensureExits.
    private const val EXIT_WAIT_MS = 100_000L
    private const val STATUS_HOST = "status.gt.internal"

    private data class Need(val country: String, val provider: String)

    private fun neededExits(p: GtExitPrefs): List<Need> {
        val out = mutableListOf<Need>()
        fun add(c: String, pr: String) { if (c.isNotBlank() && out.none { it.country == c && it.provider == pr }) out.add(Need(c, pr)) }
        add(p.country, p.provider)
        p.rules.forEach { add(it.country, it.provider) }
        return out
    }

    private fun findExit(exits: List<GtExitInfo>, n: Need): GtExitInfo? =
        exits.filter { it.country == n.country && (n.provider.isBlank() || it.provider == n.provider) && it.state != "idle" }
            .minByOrNull { if (it.state == "ready") 0 else 1 }

    /** The plan from what the runner has READY: anything not ready leaves by `direct`. */
    private fun planFor(p: GtExitPrefs, exits: List<GtExitInfo>): GtXray.ExitPlan {
        fun ready(n: Need) = findExit(exits, n)?.takeIf { it.state == "ready" }?.id
        val use = if (p.country.isNotBlank()) ready(Need(p.country, p.provider)) ?: "direct" else "direct"
        val rules = p.rules.mapNotNull { r ->
            ready(Need(r.country, r.provider))?.takeIf { it != use }?.let { GtXray.ExitPlan.Rule(it, r.domains, uidsOf(r.apps)) }
        }
        return GtXray.ExitPlan(use, rules)
    }

    /**
     * The UIDs of the rule's apps — what the core's owner lookup answers with. Resolved on every
     * build rather than stored: a reinstalled app can come back under another UID. An app no longer
     * installed just drops out; its package name stays in the rule for when it returns.
     */
    private fun uidsOf(packages: List<String>): List<Int> = packages.mapNotNull { p ->
        try {
            @Suppress("DEPRECATION")
            app.packageManager.getApplicationInfo(p, 0).uid
        } catch (_: Exception) { null }
    }.distinct()

    private fun inUseOf(p: GtXray.ExitPlan): Set<String> = (setOf(p.use) + p.rules.map { it.id }) - "direct"

    fun setExitPrefs(p: GtExitPrefs) {
        store.exitPrefs = p
        refresh()
        scheduleExits()
    }

    private fun countryName(cc: String): String = com.mlmvpn.scanner.ui.tunnel.CountryLabel.name(cc,
        com.mlmvpn.scanner.utils.AppLocaleManager.wrapContext(app).resources.configuration.locales[0])

    /** Plain HTTP through the running connection's own SOCKS inbound — the host is resolved by the runner. */
    private fun viaTunnel(timeoutMs: Long): okhttp3.OkHttpClient = GtGithub.http.newBuilder()
        .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress("127.0.0.1", com.mlmvpn.scanner.utils.LocalPort.get(app))))
        .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    /**
     * One question to the runner over its status channel (`status.gt.internal`, which the runner's
     * Xray hands to its agent). The answer is sealed to this session's key; a forged one throws.
     */
    private fun askRunner(path: String, timeoutMs: Long = 15_000): JSONObject? {
        val s = store.activeSession() ?: return null
        if (!connectedTo(s.id)) return null
        return try {
            viaTunnel(timeoutMs).newCall(okhttp3.Request.Builder().url("http://$STATUS_HOST$path").build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (body.isBlank()) return null
                val payload = GtCrypto.open(com.mlmvpn.core.tunnel.SecureStore.decrypt(s.sealKeyEnc), JSONObject(body), s.id)
                noteExits(payload)
                noteHandoff(s, body, payload)
                payload
            }
        } catch (e: Exception) { Log.w(TAG, "runner $path: ${e.message}"); null }
    }

    private fun JSONObject.str(k: String) = optString(k).takeIf { it != "null" }.orEmpty()

    private fun exitsOf(p: JSONObject): List<GtExitInfo>? {
        val arr = p.optJSONArray("exits") ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            GtExitInfo(o.str("id"), o.str("state"), o.str("country"), o.str("provider"), o.str("ip"), o.str("city"),
                o.optDouble("mbps", 0.0).takeIf { !it.isNaN() } ?: 0.0, o.optInt("rttMs"), o.optBoolean("udp"), o.str("error"))
        }
    }

    /** What the runner reports — its exits and the countries it can offer — onto the screen. */
    private fun noteExits(p: JSONObject) {
        val exits = exitsOf(p) ?: return
        val cat = p.optJSONObject("catalog")
        val vg = cat?.optJSONObject("vpngate")?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }
        val ps = cat?.optJSONArray("psiphon")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        lastStatusAt = System.currentTimeMillis()
        setJob { cur ->
            cur.copy(exits = exits, vpngate = vg?.takeIf { it.isNotEmpty() } ?: cur.vpngate,
                psiphon = ps?.takeIf { it.isNotEmpty() } ?: cur.psiphon)
        }
        if (!vg.isNullOrEmpty() || !ps.isNullOrEmpty()) {
            val e = _state.value.exit
            store.saveExitCatalog(e.vpngate, e.psiphon)
        }
    }

    private fun setJob(change: (GtExitUi) -> GtExitUi) { _state.value = _state.value.copy(exit = change(_state.value.exit)) }

    private fun scheduleExits() {
        scope.launch { try { ensureExits() } catch (e: Exception) { Log.w(TAG, "exits: ${e.message}") } }
    }

    /** The running connection onto what the prefs ask for — brought up first, then switched. */
    private suspend fun ensureExits() {
        if (!exitRunning.compareAndSet(false, true)) { exitAgain = true; return }
        try {
            val session = store.activeSession() ?: return
            if (!connectedTo(session.id) || _state.value.link.phase != "connected") return
            val prefs = store.exitPrefs
            val need = neededExits(prefs)
            if (need.isEmpty()) {
                // Back to the runner's own address, if the connection was anywhere else.
                if (plan.use != "direct" || plan.rules.isNotEmpty()) {
                    setJob { it.copy(job = "switching", jobCountry = "", jobError = "") }
                    if (!applyPlan(session, GtXray.ExitPlan())) throw Exception(S(R.string.gt_err_exit_switch))
                    log(S(R.string.gt_log_exit_direct))
                }
                freeUnusedExits(askRunner("/s")?.let { exitsOf(it) } ?: _state.value.exit.exits, prefs, plan)
                setJob { it.copy(job = "idle", jobCountry = "", jobError = "") }
                locate()
                return
            }
            setJob { it.copy(job = "preparing", jobCountry = prefs.country.ifBlank { need[0].country }, jobError = "") }
            log(S(R.string.gt_log_exit_preparing, need.joinToString("، ") { countryName(it.country) }))
            var exits: List<GtExitInfo> = emptyList()
            for (n in need) {
                val q = "/x?cc=${n.country}" + (if (n.provider.isNotBlank()) "&provider=${n.provider}" else "")
                askRunner(q)?.let { p -> exitsOf(p)?.let { exits = it } }
            }
            val until = System.currentTimeMillis() + EXIT_WAIT_MS
            while (System.currentTimeMillis() < until) {
                if (need.all { n -> findExit(exits, n)?.let { it.state != "starting" } == true }) break
                delay(3000)
                if (!connectedTo(session.id)) return
                askRunner("/s")?.let { p -> exitsOf(p)?.let { exits = it } }
            }
            val failed = need.filter { n -> findExit(exits, n)?.state != "ready" }.map { n ->
                "${countryName(n.country)}: ${findExit(exits, n)?.error?.ifBlank { null } ?: S(R.string.gt_exit_not_ready)}"
            }
            val next = planFor(prefs, exits)
            Log.i(TAG, "runner exits: " + exits.joinToString(" ") { "${it.id}=${it.country}/${it.provider}/${it.state}" } +
                " → plan: default=${next.use} rules=" + next.rules.joinToString(",") { "${it.id}(${it.domains.size}s/${it.uids.size}a)" })
            // Still the same connection? A disconnect or a new session meanwhile makes this moot.
            if (!connectedTo(session.id) || store.activeSession()?.id != session.id) { setJob { it.copy(job = "idle") }; return }
            if (next.normalize(transportOf(session)) != plan) {
                setJob { it.copy(job = "switching") }
                if (!applyPlan(session, next)) throw Exception(S(R.string.gt_err_exit_switch))
            }
            freeUnusedExits(exits, prefs, plan)
            val seen = locate()
            if (healLeak(session)) { exitAgain = true; return }
            if (prefs.country.isNotBlank() && plan.use != "direct" && seen.isNotBlank() && seen != prefs.country) {
                log(S(R.string.gt_log_exit_mismatch, countryName(prefs.country), countryName(seen)))
            }
            val err = failed.joinToString(" — ")
            setJob { it.copy(job = if (failed.isEmpty()) "done" else "partial", jobError = err) }
            if (failed.isNotEmpty()) log(S(R.string.gt_log_exit_partial, err))
            else log(when {
                prefs.country.isBlank() -> S(R.string.gt_log_exit_rules)
                prefs.rules.isEmpty() -> S(R.string.gt_log_exit_now, countryName(prefs.country))
                else -> S(R.string.gt_log_exit_now, countryName(prefs.country)) + " " + S(R.string.gt_log_exit_rules)
            })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            setJob { it.copy(job = "failed", jobError = e.message.orEmpty().take(240)) }
            log(S(R.string.gt_log_exit_failed, e.message.orEmpty().take(240)))
        } finally {
            exitRunning.set(false)
            if (exitAgain) { exitAgain = false; scheduleExits() }
        }
    }

    /**
     * The same tunnel, the same clean addresses, a different user (and site rules): the VPN
     * restarts once on the new config, a second or two, and must come back up to count.
     */
    private suspend fun applyPlan(session: GtSession, next: GtXray.ExitPlan): Boolean {
        if (linkIps.isEmpty() && linkRelays.isEmpty()) return false
        val t = transportOf(session)
        MyVpnService.connectionPhaseFlow.value = MyVpnService.Phase.CONNECTING
        startVpn(session, GtXray.config(session, t, workerHostOrNull().takeIf { linkIps.isNotEmpty() }, store.installSecret(), linkIps,
            com.mlmvpn.scanner.utils.LocalPort.get(app), next, linkRelays))
        val phase = withTimeoutOrNull(45_000) { MyVpnService.connectionPhaseFlow.first { it != MyVpnService.Phase.CONNECTING } }
        if (phase != MyVpnService.Phase.CONNECTED) return false
        plan = next.normalize(t)
        planSession = session.id
        setJob { it.copy(use = plan.use, inUse = inUseOf(plan)) }
        return true
    }

    /** Per session and exit: when a leak of it was last healed — at most once in five minutes. */
    private val healedAt = mutableMapOf<String, Long>()
    private var lastLocateAt = 0L

    /**
     * The default exit, measured leaving from the runner's OWN address, is no exit at all — seen
     * 2026-09-24 on a VPN Gate JP exit that stayed «ready» on the runner while every byte left from
     * its Azure address. The traffic goes back onto the runner's address openly (not dressed up as
     * the country), the runner is asked to build that exit again, and the caller ensures the plan
     * once more. Runners from this version on catch it themselves (exits.mjs › leakOf); this covers
     * the one the phone is on, now.
     */
    private suspend fun healLeak(session: GtSession): Boolean {
        val runnerIp = try { transportOf(session).runnerIp } catch (_: Exception) { "" }
        val seenIp = _state.value.exit.seenIp
        if (plan.use == "direct" || runnerIp.isBlank() || seenIp != runnerIp) return false
        val key = session.id + "/" + plan.use
        val now = System.currentTimeMillis()
        if (now - (healedAt[key] ?: 0L) < 5 * 60_000L) return false
        healedAt[key] = now
        val country = _state.value.exit.exits.firstOrNull { it.id == plan.use }?.country.orEmpty()
        log(S(R.string.gt_log_exit_leak, countryName(country.ifBlank { store.exitPrefs.country })))
        // leak=1: the runner stops using that server for this session (older runners ignore it).
        askRunner("/x/stop?id=${plan.use}&leak=1")
        applyPlan(session, plan.copy(use = "direct"))
        return true
    }

    /** The runner has six exit slots, each an OpenVPN or Psiphon process: stop the ones nothing needs. */
    private fun freeUnusedExits(exits: List<GtExitInfo>, prefs: GtExitPrefs, p: GtXray.ExitPlan) {
        val need = neededExits(prefs)
        val inUse = setOf(p.use) + p.rules.map { it.id }
        for (x in exits) {
            if (x.state == "idle" || x.id in inUse) continue
            if (need.any { n -> x.country == n.country && (n.provider.isBlank() || x.provider == n.provider) }) continue
            askRunner("/x/stop?id=${x.id}")
        }
    }

    /**
     * Where websites see this connection — asked through the tunnel itself — and then where its UDP
     * leaves from ([GtUdpProbe]). Returns the TCP country code.
     */
    private fun locate(): String {
        lastLocateAt = System.currentTimeMillis()
        val cc = try {
            val req = okhttp3.Request.Builder().url("http://ip-api.com/json/?fields=status,countryCode,city,query").build()
            viaTunnel(12_000).newCall(req).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty())
                if (o.optString("status") != "success") return@use ""
                val c = o.optString("countryCode")
                setJob { it.copy(seenCountry = c, seenCity = o.optString("city"), seenIp = o.optString("query")) }
                c
            }
        } catch (e: Exception) { Log.w(TAG, "locate: ${e.message}"); "" }
        locateUdp()
        return cc
    }

    private fun locateUdp() {
        setJob { it.copy(udpState = "testing") }
        val ip = GtUdpProbe.publicAddress(com.mlmvpn.scanner.utils.LocalPort.get(app))
        if (ip == null) {
            setJob { it.copy(udpState = "none", udpIp = "", udpCountry = "", udpCity = "") }
            return
        }
        val tcp = _state.value.exit
        // The same address as the TCP check: same place, no second lookup.
        if (ip == tcp.seenIp && tcp.seenCountry.isNotBlank()) {
            setJob { it.copy(udpState = "ok", udpIp = ip, udpCountry = tcp.seenCountry, udpCity = tcp.seenCity) }
            return
        }
        val geo = try {
            val req = okhttp3.Request.Builder().url("http://ip-api.com/json/$ip?fields=status,countryCode,city").build()
            viaTunnel(12_000).newCall(req).execute().use { r -> JSONObject(r.body?.string().orEmpty()) }
        } catch (e: Exception) { null }
        setJob {
            it.copy(udpState = "ok", udpIp = ip,
                udpCountry = geo?.takeIf { g -> g.optString("status") == "success" }?.optString("countryCode").orEmpty(),
                udpCity = geo?.optString("city").orEmpty())
        }
    }

    /** «به‌روزرسانی»: what the runner has now, and where the connection leaves. */
    fun refreshExits() {
        scope.launch { askRunner("/s"); locate() }
    }

    // ── time ────────────────────────────────────────────────────────────────────
    /** Session clocks, the runner's real state once a minute, and the seamless renewal. */
    private suspend fun tick() {
        val now = GtGithub.serverNow()
        for (s in store.sessions().filter { it.live && it.expiresAt > 0 }) {
            val left = s.expiresAt - now
            val next = when {
                left <= 0 -> "EXPIRED"
                left <= 10 * 60_000L -> "EXPIRING_SOON"
                s.status == "READY" && connectedTo(s.id) -> "ACTIVE"
                else -> s.status
            }
            if (next != s.status) store.updateSession(s.id) { it.copy(status = next, endedAt = if (next == "EXPIRED") System.currentTimeMillis() else it.endedAt) }
            if (next == "EXPIRED") {
                log(S(R.string.gt_log_session_expired))
                if (connectedTo(s.id)) disconnect()
            }
        }
        // A run can end early (cancelled, evicted): the countdown alone would keep promising time.
        if (System.currentTimeMillis() - lastReconcile > 60_000) {
            lastReconcile = System.currentTimeMillis()
            store.activeSession()?.let { s ->
                val token = store.token(s.accountId)
                if (token.isNotBlank() && s.runId > 0) {
                    val run = try { GtGithub.getRun(token, s.repository, s.runId) } catch (_: Exception) { null }
                    if (run?.optString("status") == "completed") {
                        store.updateSession(s.id) { it.copy(status = "EXPIRED", endedAt = System.currentTimeMillis(), lastError = "cloud session ended (${run.optString("conclusion")})") }
                        log(S(R.string.gt_log_session_expired))
                        if (connectedTo(s.id)) disconnect()
                    }
                }
            }
        }
        // The runner's exits once a minute while connected: an exit that died moves the connection
        // back onto whatever still works instead of leaving it on a dead user.
        if (isConnected() && _state.value.link.phase == "connected" && System.currentTimeMillis() - lastStatusAt > 60_000) {
            lastStatusAt = System.currentTimeMillis()
            val p = askRunner("/s")
            p?.let {
                val exits = exitsOf(it).orEmpty()
                val lost = (listOf(plan.use) + plan.rules.map { r -> r.id }).any { id -> id != "direct" && exits.none { x -> x.id == id && x.state == "ready" } }
                if (lost && !exitRunning.get()) scheduleExits()
            }
            // On relays alone, a relay that restarted comes back under a NEW name. The status
            // channel says which — or GitHub, when no relay carries the status channel any more —
            // and the connection moves onto the new names.
            store.activeSession()?.let { s ->
                if (linkIps.isEmpty() && linkRelays.isNotEmpty()) {
                    val cur = if (p == null) refreshSealed(s) else store.session(s.id) ?: s
                    if (relaysMoved(cur) && linkJob?.isActive != true) {
                        log(S(R.string.gt_log_relay_moved))
                        linkJob = scope.launch { connectNow(cur) }
                    }
                }
            }
        }
        // Where the default exit really leaves from, every few minutes: an exit can go wrong long
        // after it came up «ready» (healLeak), and the screen's «what sites see» stays current.
        if (isConnected() && _state.value.link.phase == "connected" && plan.use != "direct" && !exitRunning.get() &&
            System.currentTimeMillis() - lastLocateAt > 3 * 60_000L) {
            lastLocateAt = System.currentTimeMillis()
            locate()
            store.activeSession()?.let { s -> if (healLeak(s)) scheduleExits() }
        }
        maybeRenew()
        refresh()
    }

    /**
     * MAKE-BEFORE-BREAK: shortly before the session ends, the next one is built while this one
     * still carries traffic; then the tunnel moves to it (the VPN restarts once, a second or two)
     * and the old runner is stopped.
     */
    private suspend fun maybeRenew() {
        if (!store.autoRenew || runLive || _state.value.renewing) return
        val cur = store.activeSession() ?: return
        if (!connectedTo(cur.id) || cur.expiresAt - GtGithub.serverNow() > RENEW_LEAD_MS) return
        if (store.sessions().any { it.status == "STANDBY" }) return
        _state.value = _state.value.copy(renewing = true)
        log(S(R.string.gt_log_renewing))
        runJob = scope.launch {
            try {
                val next = runSession(standby = true)
                store.updateSession(cur.id) { it.copy(status = "ENDING") }
                store.updateSession(next.id) { it.copy(status = "READY") }
                connectNow(store.session(next.id) ?: next)
                if (_state.value.link.phase == "connected") {
                    endQuietly(cur)
                    log(S(R.string.gt_log_renewed))
                } else {
                    // The new one would not carry traffic: back on the old one while it lasts.
                    store.updateSession(next.id) { it.copy(status = "FAILED", lastError = "cutover failed") }
                    endQuietly(next)
                    store.updateSession(cur.id) { it.copy(status = "EXPIRING_SOON") }
                    connectNow(store.session(cur.id) ?: cur)
                }
            } catch (e: Exception) {
                log(S(R.string.gt_log_renew_failed, e.message.orEmpty().take(160)))
            } finally {
                _state.value = _state.value.copy(renewing = false)
                refresh()
            }
        }
    }

    /** «ریست کامل»: forget everything here. The repository and the Worker stay on the user's accounts. */
    fun resetAll() {
        signInJob?.cancel(); runJob?.cancel(); linkJob?.cancel()
        if (isConnected()) disconnect()
        store.resetAll()
        plan = GtXray.ExitPlan(); planSession = ""; linkIps = emptyList(); linkRelays = emptyList()
        _state.value = GtUiState()
        refresh()
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            try { android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }
}
