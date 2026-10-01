package com.mlmvpn.scanner.openvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import com.mlmvpn.scanner.MainActivity
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.ui.tunnel.TunnelExclusion
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OpenVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var running: Job? = null
    @Volatile private var session: Session? = null
    @Volatile private var shuttingDown = false
    private var latestStartId = 0
    private var quotaSwitchFrom: Session? = null
    private val attempted = mutableSetOf<String>()
    private val repo by lazy { OpenVpnRepository.get(this) }
    private data class Command(val profile: String? = null, val account: String? = null,
        val automatic: Boolean = false, val startId: Int = 0, val finished: Session? = null)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "OpenVPN", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            for (command in commands) {
                val finished = command.finished
                if (finished != null) {
                    if (session !== finished) continue
                    running?.join()
                    running = null; session = null
                    val data = repo.data.value
                    val failed = data.accounts.firstOrNull { it.id == finished.accountId }
                    val now = System.currentTimeMillis()
                    val next = if (!finished.wasStopped && latestStartId == finished.requestId && data.autoSwitch &&
                        failed != null && AccountPolicy.confirmedUnusable(failed, now) && attempted.size < 3)
                        AccountPolicy.best(data.accounts, now, attempted) else null
                    if (next != null) {
                        // Bound the chain to three distinct accounts; never revisit a failed one.
                        delay(1500)
                        if (latestStartId != finished.requestId || shuttingDown) continue
                        val fresh = repo.data.value
                        val replacement = if (fresh.autoSwitch) AccountPolicy.best(fresh.accounts, System.currentTimeMillis(), attempted) else null
                        if (replacement != null) startSession(Command(finished.profileId, replacement.id, true, finished.requestId))
                        else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(finished.requestId) }
                    } else if (latestStartId == finished.requestId) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(finished.requestId)
                    }
                    continue
                }
                session?.stop()
                val stopped = withTimeoutOrNull(STOP_GRACE_MS) { running?.join(); true } == true
                if (!stopped) {
                    // The core did not let go (a dead server it keeps dialling, a blocked
                    // handshake). Waiting on it left the page on "connecting" / "disconnecting"
                    // for good -- users had to force-stop the app. The session is abandoned
                    // instead: its tunnel and relay are closed from here, the state is freed at
                    // once, and the stuck thread cleans up after itself whenever it returns.
                    session?.abandon()
                    OpenVpnRuntime.mutable.value = OpenVpnConnection()
                }
                running = null; session = null
                if (command.profile == null || command.account == null) {
                    OpenVpnRuntime.mutable.value = OpenVpnConnection()
                    if (latestStartId == command.startId) stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(command.startId)
                    continue
                }
                startSession(command)
            }
        }
        scope.launch {
            repo.data.collect { data ->
                val state = OpenVpnRuntime.connection.value
                val account = data.accounts.firstOrNull { it.id == state.accountId } ?: return@collect
                val now = System.currentTimeMillis()
                // Authentication failures are processed by the serialized completion command.
                // This collector handles only a future provider adapter's confirmed quota change.
                if (data.autoSwitch && state.phase == ConnectionPhase.CONNECTED && session !== quotaSwitchFrom &&
                    account.usage?.let { it.fresh(now) && it.remaining == 0L } == true && attempted.size < 3) {
                    val next = AccountPolicy.best(data.accounts, now, attempted + account.id) ?: return@collect
                    quotaSwitchFrom = session
                    commands.send(Command(state.profileId, next.id, automatic = true, startId = latestStartId))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == CONNECT) {
            startForeground(NOTIFICATION, notification("CONNECTING"))
            val p = intent.getStringExtra("profile"); val a = intent.getStringExtra("account")
            if (p != null && a != null) {
                if (!OpenVpnRuntime.connection.value.active) OpenVpnRuntime.mutable.value = OpenVpnConnection(ConnectionPhase.CONNECTING, p, a)
                commands.trySend(Command(p, a, startId = startId))
            } else { commands.trySend(Command(startId = startId)) }
        } else {
            session?.stop()
            commands.trySend(Command(startId = startId))
        }
        // Do not resurrect credentials or replace another VPN after process death.
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        session?.stop()
        commands.trySend(Command(startId = latestStartId))
        super.onRevoke()
    }

    private suspend fun startSession(command: Command) {
        if (!command.automatic) attempted.clear()
        val account = command.account ?: return
        val profile = command.profile ?: return
        if (account in attempted) return
        if (account != OpenVpnRuntime.SELF) try { withContext(Dispatchers.IO) { repo.selectAccount(account) } }
        catch (_: Exception) {
            OpenVpnRuntime.mutable.value = OpenVpnConnection(ConnectionPhase.ERROR, error = "ACCOUNT_UNAVAILABLE")
            if (latestStartId == command.startId) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(command.startId) }
            return
        }
        attempted += account
        val newSession = Session(profile, account, command.startId)
        session = newSession
        OpenVpnRuntime.mutable.value = OpenVpnConnection(ConnectionPhase.CONNECTING, profile, account)
        running = scope.launch(Dispatchers.IO) { newSession.run() }
        // A server that never answers used to keep the core dialling for ever, with the page on
        // "connecting". Not connected (data verified) by the deadline: it ends with a reason.
        scope.launch {
            delay(CONNECT_DEADLINE_MS)
            if (session === newSession && OpenVpnRuntime.connection.value.phase != ConnectionPhase.CONNECTED &&
                OpenVpnRuntime.connection.value.connectedAt == null) {
                newSession.timeOut()
                delay(STOP_GRACE_MS)
                // Still not returned: given up on, like a stop that hangs.
                if (session === newSession) {
                    newSession.abandon()
                    running = null; session = null
                    OpenVpnRuntime.mutable.value = OpenVpnConnection(ConnectionPhase.ERROR, profile, account, error = "CONNECT_DEADLINE")
                    if (latestStartId == newSession.requestId) stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(newSession.requestId)
                }
            }
        }
    }
    override fun onDestroy() {
        shuttingDown = true
        session?.stop()
        commands.close()
        // The blocking native call owns its reference until it returns and releases the core.
        scope.cancel()
        super.onDestroy()
    }

    @Keep
    private inner class Session(val profileId: String, val accountId: String, val requestId: Int) {
        private val stopped = AtomicBoolean(false)
        val wasStopped get() = stopped.get()
        private val handle = AtomicLong(0)
        private var builder: Builder? = null
        private var retainedTun: ParcelFileDescriptor? = null
        private var terminal: String? = null
        /** The connect ran out of time: ends like a failure, with a reason on the page. */
        fun timeOut() {
            timedOut = true
            if (terminal == null) terminal = "CONNECT_DEADLINE"
            stop()
        }
        @Volatile private var timedOut = false

        /** Given up on: everything this session holds is closed from outside its own thread. */
        fun abandon() {
            stopped.set(true)
            handle.get().takeIf { it != 0L }?.let { runCatching { OpenVpnNative.stop(it) } }
            relay?.let { r -> relay = null; runCatching { r.close() } }
            synchronized(this) { runCatching { retainedTun?.close() }; retainedTun = null }
        }

        fun stop() {
            stopped.set(true)
            if (session === this && OpenVpnRuntime.connection.value.active) {
                OpenVpnRuntime.mutable.value = OpenVpnRuntime.connection.value.copy(phase = ConnectionPhase.DISCONNECTING)
            }
            handle.get().takeIf { it != 0L }?.let { OpenVpnNative.stop(it) }
        }
        suspend fun run() {
            try {
                val d = repo.data.value
                val profile = d.profiles.firstOrNull { it.id == profileId } ?: error("profile")
                val self = accountId == OpenVpnRuntime.SELF
                if (self && !profile.selfContained) { terminal = "ACCOUNT_UNAVAILABLE"; return }
                val account = if (self) null else d.accounts.firstOrNull { it.id == accountId && it.usable(System.currentTimeMillis()) } ?: error("account")
                if (!OpenVpnNative.available(this@OpenVpnService)) { terminal = "CORE_UNAVAILABLE"; return }
                val validation = OpenVpnNative.evaluate(ProfileRuntime.effective(profile, tcp = true))
                if (validation.isNotEmpty()) { terminal = validation; return }
                // Validate everything before releasing the user's working tunnel.
                val own = profile.ownCredentials
                val username = if (self) own?.first.orEmpty() else account!!.username
                val password = if (self) own?.second.orEmpty() else repo.password(accountId)
                if (stopped.get()) return
                TunnelExclusion.releaseForOpenVpn(this@OpenVpnService)
                if (stopped.get()) return
                // From Iran the OpenVPN handshake is cut at TLS on UDP and TCP alike (measured
                // 2026-09-27: the server answers the reset and nothing after; the same server
                // from abroad completes TLS). A TunnelBear session therefore goes over TCP 7011
                // through OpenVpnSplitRelay: a direct connection whose opening bytes are cut
                // into misaligned segments. No second tunnel -- the user ruled that out, and
                // it would cost the speed this engine exists for.
                val config = if (ProfileRuntime.isTunnelBear(profile)) {
                    // The addresses the delay test saw answer, not a fresh lookup: the name is a
                    // pool of ~20 servers and DNS returns a different one every time.
                    val hosts = (profile.probe?.healthy.orEmpty() + OpenVpnLatency.candidates(profile))
                        .distinct().ifEmpty { listOf(profile.remotes.first().host) }
                    val r = OpenVpnSplitRelay(this@OpenVpnService, hosts, ProfileRuntime.TUNNELBEAR_TCP_PORT)
                    relay = r
                    r.start()
                    ProfileRuntime.effective(profile, tcp = true, relayPort = r.localPort)
                } else ProfileRuntime.effective(profile, tcp = false)
                val id = OpenVpnNative.create()
                check(id != 0L)
                handle.set(id)
                if (stopped.get()) OpenVpnNative.stop(id)
                val error = OpenVpnNative.run(id, config, username, password, this)
                if (terminal == null && error.isNotEmpty()) terminal = error
            } catch (_: CancellationException) { stop() }
            catch (_: Exception) { terminal = "CONNECTION_FAILED" }
            catch (_: LinkageError) { terminal = "CORE_UNAVAILABLE" }
            finally {
                relay?.let { r -> relay = null; runCatching { r.close() } }
                synchronized(this) { runCatching { retainedTun?.close() }; retainedTun = null }
                handle.getAndSet(0).takeIf { it != 0L }?.let { OpenVpnNative.release(it) }
                if (session === this) {
                    val state = OpenVpnRuntime.connection.value
                    OpenVpnRuntime.mutable.value = state.copy(phase = if (terminal != null && (!stopped.get() || timedOut)) ConnectionPhase.ERROR else ConnectionPhase.DISCONNECTED, error = terminal)
                    if (!shuttingDown) notifyStatus(terminal ?: "DISCONNECTED")
                }
                commands.trySend(Command(finished = this))
            }
        }
        @Volatile private var relay: OpenVpnSplitRelay? = null

        @Keep fun configureTun(operation: String, value: Int): Boolean = runCatching {
            when (operation) {
                "new" -> { builder = Builder().setSession("MLMVPN OpenVPN").setBlocking(false); true }
                "mtu" -> { builder!!.setMtu(value.coerceIn(576, 9000)); true }
                "protect" -> protect(value)
                else -> false
            }
        }.getOrDefault(false)
        @Keep fun addAddress(address: String, prefix: Int) = runCatching { builder!!.addAddress(address, prefix); true }.getOrDefault(false)
        @Keep fun addRoute(address: String, prefix: Int) = runCatching { builder!!.addRoute(address, prefix); true }.getOrDefault(false)
        @Keep fun addDns(address: String, ignored: Int) = runCatching { builder!!.addDnsServer(address); true }.getOrDefault(false)
        @Keep fun addSearchDomain(domain: String, ignored: Int) = runCatching { builder!!.addSearchDomain(domain); true }.getOrDefault(false)
        @Keep fun establishTun(): Int {
            if (stopped.get()) return -1
            // An IPv6 DNS server also enables that family. Capture all IPv6 explicitly
            // even on IPv4-only profiles so it cannot fall through to the physical link.
            builder?.addRoute("::", 0)
            val tun = builder?.establish() ?: return -1
            retainedTun?.close()
            retainedTun = tun
            return ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd()
        }
        @Keep fun onCoreEvent(name: String, error: Boolean) {
            if (session !== this || stopped.get()) return
            val phase = when (name) {
                // Not yet "connected": the core's CONNECTED means the handshake finished, and
                // the button used to turn green there even when nothing then passed -- the
                // "fake connected" users saw. The phase moves only after verifyThroughTunnel().
                "CONNECTED" -> null
                "RECONNECTING", "PAUSE", "RESOLVE", "WAIT", "CONNECTING" -> if (OpenVpnRuntime.connection.value.connectedAt != null) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING
                "DISCONNECTED" -> ConnectionPhase.DISCONNECTING
                else -> null
            }
            if (name == "AUTH_FAILED") {
                terminal = "AUTH_FAILED"
                repo.updateAccount(accountId) { it.copy(auth = AuthState.FAILED, lastError = "AUTH_FAILED") }
            } else if (error) terminal = when (name) {
                "CERT_VERIFY_FAIL", "TLS_VERSION_MIN", "CONNECTION_TIMEOUT", "NETWORK_UNREACHABLE" -> name
                else -> "CONNECTION_FAILED"
            }
            if (phase != null) {
                val old = OpenVpnRuntime.connection.value
                OpenVpnRuntime.mutable.value = old.copy(phase = phase,
                    connectedAt = if (name == "CONNECTED") old.connectedAt ?: System.currentTimeMillis() else old.connectedAt)
            }
            if (name == "CONNECTED") {
                terminal = null
                repo.updateAccount(accountId) { it.copy(auth = AuthState.ACCEPTED, lastConnected = System.currentTimeMillis(), lastError = null) }
                repo.refreshAccount(accountId)
                OpenVpnRuntime.mutable.value = OpenVpnRuntime.connection.value.copy(detail = "VERIFYING")
                val generation = verifyGeneration.incrementAndGet()
                Thread({ verifyThroughTunnel(generation) }, "ovpn-verify").start()
            }
            notifyStatus(terminal ?: if (name == "CONNECTED") "VERIFYING" else name)
        }

        private val verifyGeneration = java.util.concurrent.atomic.AtomicInteger()

        /**
         * A real request across the tunnel -- the definition of "connected". This app is inside
         * its own tunnel, so an ordinary socket goes through it; the answer also names the exit.
         * Three tries over about twenty seconds; if none gets through, the session is ended with
         * NO_DATA rather than left looking connected.
         */
        private fun verifyThroughTunnel(generation: Int) {
            repeat(3) { attempt ->
                if (stopped.get() || session !== this || verifyGeneration.get() != generation) return
                val exit = runCatching { OpenVpnExit.probe(6_000) }.getOrNull()
                if (exit != null) {
                    if (stopped.get() || session !== this || verifyGeneration.get() != generation) return
                    val old = OpenVpnRuntime.connection.value
                    OpenVpnRuntime.mutable.value = old.copy(
                        phase = ConnectionPhase.CONNECTED, detail = null,
                        connectedAt = old.connectedAt ?: System.currentTimeMillis(),
                        exitIp = exit.first, exitCountry = exit.second,
                    )
                    notifyStatus("CONNECTED")
                    return
                }
                if (attempt < 2) Thread.sleep(2_000)
            }
            if (stopped.get() || session !== this || verifyGeneration.get() != generation) return
            terminal = "NO_DATA"
            handle.get().takeIf { it != 0L }?.let { OpenVpnNative.stop(it) }
        }

        @Keep fun onCoreTraffic(bytesOut: Long, bytesIn: Long) {
            // The core reports its TUN's counters: "in" is what it wrote to the TUN, i.e. what
            // arrived from the server. Read the other way round, a 55 MB download showed as 55 MB
            // sent.
            if (session === this && !stopped.get()) OpenVpnRuntime.mutable.value = OpenVpnRuntime.connection.value.copy(received = bytesIn.coerceAtLeast(0), sent = bytesOut.coerceAtLeast(0))
        }
    }

    private fun notification(status: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 610, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 611, Intent(this, OpenVpnService::class.java).setAction(DISCONNECT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MLMVPN · OpenVPN").setContentText(status).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(OpenVpnRuntime.connection.value.active)
            .addAction(0, "Disconnect", stop).build()
    }
    private fun notifyStatus(status: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(status)) }
    companion object {
        const val CONNECT = "com.mlmvpn.openvpn.CONNECT"
        const val DISCONNECT = "com.mlmvpn.openvpn.DISCONNECT"
        private const val CHANNEL = "mlmvpn_openvpn"
        private const val NOTIFICATION = 610
        /** How long a stop may take before the session is abandoned. */
        private const val STOP_GRACE_MS = 5_000L
        /** Handshake plus the data check (up to ~22 s) on a slow network, and no longer. */
        private const val CONNECT_DEADLINE_MS = 60_000L
    }
}
