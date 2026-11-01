package com.mlmvpn.scanner.engines.game.booster.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mlmvpn.core.aether.AetherEngine
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.game.booster.brain.BrainStore
import com.mlmvpn.scanner.engines.game.booster.brain.CrowdRows
import com.mlmvpn.scanner.engines.game.booster.brain.Feedback
import com.mlmvpn.scanner.engines.game.booster.brain.SessionOutcome
import com.mlmvpn.scanner.engines.game.booster.brain.SessionRecord
import com.mlmvpn.scanner.engines.game.booster.crowd.CrowdClient
import com.mlmvpn.scanner.engines.game.booster.decide.ProbeMethod
import com.mlmvpn.scanner.engines.game.booster.memory.RouteMemoryStore
import com.mlmvpn.scanner.engines.game.booster.model.GameProfileStore
import com.mlmvpn.scanner.engines.game.booster.model.ProbeDepth
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.probe.EchoProtocol
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeStats
import com.mlmvpn.scanner.engines.game.booster.probe.SocksUdpSession
import com.mlmvpn.scanner.engines.game.booster.probe.UdpVia
import com.mlmvpn.scanner.engines.game.booster.probe.tcpTrain
import com.mlmvpn.scanner.engines.game.booster.probe.tlsTrain
import com.mlmvpn.scanner.engines.game.booster.probe.TrainResult
import com.mlmvpn.scanner.engines.game.booster.probe.SliceAccumulator
import com.mlmvpn.scanner.engines.game.booster.probe.udpTrainFlows
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

/**
 * Owns one game boost: measures and applies it (via [BoostOrchestrator]), then watches the
 * chosen route and keeps the notification's live ping honest until the user stops.
 *
 * A foreground service because a boost outlives the screen that started it -- the whole point is
 * that the user leaves for the game -- and on a DIRECT route there is no VPN service keeping this
 * process alive.
 */
class GameBoostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var orchestrator: BoostOrchestrator? = null
    private lateinit var tuner: DeviceTuner

    // The running session as the learning side will record it when it ends.
    @Volatile private var record: SessionRecord? = null
    @Volatile private var spikeWatch: SpikeWatch? = null
    private val overlay by lazy { PingOverlay(this) }
    @Volatile private var goodMs = 0L
    @Volatile private var badMs = 0L
    @Volatile private var routeLost = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tuner = DeviceTuner(this)
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID,
            notification(S(R.string.gb_notif_measuring), null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBoost(userAsked = true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val gameId = intent.getStringExtra(EXTRA_GAME) ?: return START_NOT_STICKY
                val region = intent.getStringExtra(EXTRA_REGION)
                val thorough = intent.getBooleanExtra(EXTRA_THOROUGH, false)
                val forceWarp = intent.getBooleanExtra(EXTRA_FORCE_WARP, false)
                val symptom = com.mlmvpn.scanner.engines.game.booster.model.Symptom.byName(intent.getStringExtra(EXTRA_SYMPTOM))
                // A problem the player named gets the thorough measurement.
                startBoost(gameId, region, if (thorough || symptom != null) ProbeDepth.THOROUGH else ProbeDepth.QUICK, forceWarp, symptom)
            }
        }
        return START_NOT_STICKY
    }

    private fun startBoost(
        gameId: String, regionKey: String?, depth: ProbeDepth, forceWarp: Boolean,
        symptom: com.mlmvpn.scanner.engines.game.booster.model.Symptom? = null,
    ) {
        // A boost replacing a running one ends that session first, so it is learned from too.
        val previous = job
        previous?.cancel()
        orchestrator?.abort()
        val store = GameProfileStore(this)
        val profile = store.get(gameId) ?: run { stopSelf(); return }
        val memory = RouteMemoryStore(this)
        val orch = BoostOrchestrator(this, tuner, memory)
        orchestrator = orch
        job = scope.launch {
            try {
                previous?.join()
                finishSession(userStopped = true)
                overlay.hide()
                val session = orch.run(BoostOrchestrator.Plan(
                    profile = profile,
                    regionKey = regionKey ?: profile.defaultRegion,
                    gamePackage = store.installedPackage(profile),
                    depth = depth,
                    forceWarp = forceWarp,
                    symptom = symptom,
                ))
                currentCoroutineContext().ensureActive()
                if (session == null) {
                    failBoost("internal")
                    return@launch
                }
                if (session.measureOnly) {
                    // Nothing was applied, so there is nothing to watch or keep alive: the card
                    // stays on screen and the service leaves.
                    orchestrator = null
                    tuner.release()
                    ServiceCompat.stopForeground(this@GameBoostService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                record = session.record
                goodMs = 0L
                badMs = 0L
                routeLost = false
                if (profile.prefs.overlay) overlay.show()
                monitor(session, profile.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                Log.e(TAG, "boost failed", e)
                failBoost(e.javaClass.simpleName)
            } finally {
                orch.abort()
            }
        }
    }

    /**
     * Undo everything and leave, but keep the failure on screen: the user tapped Boost and must
     * see that it did not work rather than find the screen quietly back at rest.
     */
    private fun failBoost(error: String) {
        overlay.hide()
        orchestrator?.abort()
        orchestrator = null
        tuner.release()
        GameBoostController.update { it.copy(phase = BoostPhase.FAILED, error = error, live = null) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Keep measuring the route the game is on: a 5-packet burst every two seconds (UDP echo where
     * the region's beacon answers, TCP handshakes where it does not), judged over a rolling 30 s.
     * Through the WARP route the bursts go through Aether's proxy, so they follow the game's path.
     *
     * Nothing is switched automatically: a new route mid-match drops the game's UDP session. The
     * reading and the notification change colour; the user decides.
     */
    private suspend fun monitor(session: BoostOrchestrator.Session, gameName: String) {
        val viaWarp = session.route == RouteKind.WARP_MASQUE_H3 || session.route == RouteKind.WARP_MASQUE_H2 ||
            session.route == RouteKind.WARP_WG
        val window = ArrayDeque<Pair<Long, TrainResult>>()
        val series = ArrayDeque<Int?>()
        // One association per flow of the burst, kept for the whole session (see udpTrainFlows).
        var udpSessions: List<SocksUdpSession> = emptyList()
        val baseline = session.baseline
        val startedAt = System.currentTimeMillis()
        var badSince = 0L
        var routeMissing = 0
        val watch = SpikeWatch()
        spikeWatch = watch
        val phone = PhoneSampler(this)
        var lastCause: SpikeWatch.Cause? = null
        var lastCauseAt = 0L
        var lastTickAt = android.os.SystemClock.elapsedRealtime()
        try {
            while (currentCoroutineContext().isActive) {
                if (System.currentTimeMillis() - startedAt > MAX_SESSION_MS) break
                // A route through our VPN that someone switched off, or replaced with another
                // connection, is over: a live reading of a path the game is no longer on would be
                // a lie. A few seconds of grace ride out the service's own reconnects.
                if (session.route != RouteKind.DIRECT) {
                    val up = GameRoute.isUp(session.route, MyVpnService.isRunning, MyVpnService.connectedNodeId) &&
                        MyVpnService.activeGameSessionId == session.owner
                    routeMissing = if (up) 0 else routeMissing + 1
                    if (routeMissing >= ROUTE_GONE_CHECKS) {
                        Log.i(TAG, "the game route is gone (VPN stopped or replaced) -- ending the boost")
                        routeLost = true
                        break
                    }
                }
                // The phone may have moved between Wi-Fi and mobile data since the boost; probe the
                // network the game is on now, not the one it was on then.
                val path = GameRoute.probePath(session.route, nativeAmnezia = MyVpnService.runtimeSocksPort == 0)
                val proxyPort = if (viaWarp) AetherEngine.AETHER_SOCKS_PORT else MyVpnService.runtimeSocksPort
                val socks = if (path == GameRoute.ProbePath.SOCKS && proxyPort > 0)
                    InetSocketAddress("127.0.0.1", proxyPort) else null
                val network = when (path) {
                    GameRoute.ProbePath.PHYSICAL -> GameNetwork.pick(this)?.network
                    GameRoute.ProbePath.SOCKS -> null
                    GameRoute.ProbePath.VPN -> {
                        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)
                            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
                    }
                }
                val burst = when {
                    routeMissing > 0 -> null
                    path == GameRoute.ProbePath.PHYSICAL && network == null -> null
                    path == GameRoute.ProbePath.VPN && network == null -> null
                    path == GameRoute.ProbePath.SOCKS && socks == null -> null
                    session.method == ProbeMethod.TLS_HANDSHAKE && session.tcpTarget != null ->
                        tlsTrain(session.tcpTarget, count = 2, gapMs = 100, timeoutMs = 2000, socks = socks, network = network)
                    session.method == ProbeMethod.ECHO && session.echoTarget != null -> {
                        // Five probes, one per flow: a beacon answers a couple of packets a second
                        // per flow, and a burst of five on one flow read as loss that was not there.
                        val flows = if (socks != null) {
                            if (udpSessions.size < BURST_FLOWS || udpSessions.any { !it.alive }) {
                                udpSessions.forEach { it.close() }
                                udpSessions = SocksUdpSession.openMany(socks, BURST_FLOWS)
                            }
                            udpSessions.map { UdpVia.Socks(it) }
                        } else List(BURST_FLOWS) { UdpVia.Direct(network) }
                        if (flows.isEmpty()) null
                        else udpTrainFlows(session.echoTarget, EchoProtocol, flows,
                            ppsPerFlow = 20.0 / flows.size, durationMs = 250, timeoutMs = 1000)
                    }
                    session.tcpTarget != null -> tcpTrain(session.tcpTarget, count = 2, gapMs = 100, timeoutMs = 2000,
                        socks = socks, network = network)
                    else -> null
                }
                val now = System.currentTimeMillis()
                // What the phone was doing during this burst, for blaming a spike on the right thing.
                val ctxNow = phone.sample()
                watch.add(SpikeWatch.Tick(
                    maxRttMs = burst?.samples?.maxOfOrNull { it.rttMs },
                    lost = burst?.let { (it.sent - it.stats.n).coerceAtLeast(0) } ?: 0,
                    sent = burst?.sent ?: 0,
                    bgRxKBps = ctxNow.bgRxKBps, bgTxKBps = ctxNow.bgTxKBps,
                    wifiRssi = ctxNow.wifiRssi, wifiLinkMbps = ctxNow.wifiLinkMbps, thermal = ctxNow.thermal,
                ))?.let { cause ->
                    lastCause = cause
                    lastCauseAt = now
                    Log.i(TAG, "spike: $cause (bg ${ctxNow.bgRxKBps}/${ctxNow.bgTxKBps} KB/s, rssi ${ctxNow.wifiRssi}, " +
                        "link ${ctxNow.wifiLinkMbps}, thermal ${ctxNow.thermal})")
                }
                if (burst != null) {
                    window.addLast(now to burst)
                    series.addLast(burst.stats.p50)
                }
                while (window.isNotEmpty() && now - window.first().first > WINDOW_MS) window.removeFirst()
                while (series.size > SERIES_POINTS) series.removeFirst()

                val accumulated = SliceAccumulator()
                window.forEach { (_, train) -> accumulated.add(train) }
                val stats = accumulated.stats()
                val status = judge(stats, baseline, burst?.ok == true)
                badSince = if (status == LiveStatus.GOOD) 0L else if (badSince == 0L) now else badSince
                // A single bad burst is noise; the colour only changes once it has lasted.
                val shown = if (status != LiveStatus.GOOD && now - badSince < SUSTAIN_MS) LiveStatus.GOOD else status
                val tickAt = android.os.SystemClock.elapsedRealtime()
                val elapsed = tickAt - lastTickAt
                lastTickAt = tickAt
                if (shown == LiveStatus.GOOD) goodMs += elapsed else badMs += elapsed
                val live = LiveReading(stats.p50, stats.jitter, stats.loss, stats.lossIsMeaningful,
                    shown, series.toList(), now,
                    lastCause = lastCause?.takeIf { now - lastCauseAt < CAUSE_SHOWN_MS }, spikes = watch.spikes)
                GameBoostController.update { it.copy(live = live) }
                overlay.update(live)
                updateNotification(liveText(gameName, stats), stats)
                delay(BURST_EVERY_MS)
            }
        } finally {
            udpSessions.forEach { it.close() }
        }
        if (currentCoroutineContext().isActive) stopBoost(userAsked = false)
    }

    private fun judge(s: PathStats, base: PathStats?, answeredLast: Boolean): LiveStatus {
        if (!s.answered || (!answeredLast && s.n == 0)) return LiveStatus.DOWN
        val p50 = s.p50 ?: return LiveStatus.DOWN
        val baseP50 = base?.p50
        if (baseP50 != null && p50 > baseP50 + maxOf(25, baseP50 * 30 / 100)) return LiveStatus.DEGRADED
        val baseJ = base?.jitter ?: 0.0
        if ((s.jitter ?: 0.0) > maxOf(15.0, baseJ * 2)) return LiveStatus.UNSTABLE
        if (s.lossIsMeaningful && s.loss >= 3.0) return LiveStatus.LOSSY
        return LiveStatus.GOOD
    }

    private fun liveText(game: String, s: PathStats): String =
        if (!s.answered) S(R.string.gb_notif_no_answer, game)
        else S(R.string.gb_notif_live, game, s.p50 ?: 0, ProbeStats.display(s.jitter), s.loss.toInt())

    /**
     * Close the learning record of the session that just ended: how it went (from what could be
     * observed), into this phone's memory, into the crowd sample if it was picked, and -- now and
     * then -- the one question to the player.
     */
    private fun finishSession(userStopped: Boolean) {
        val r = record ?: return
        record = null
        try {
            val facts = SessionOutcome.Facts(
                durationMs = System.currentTimeMillis() - r.startedAt,
                userStopped = userStopped && !routeLost,
                goodMs = goodMs, badMs = badMs, routeLost = routeLost,
            )
            val v = SessionOutcome.implicit(facts)
            val store = BrainStore(this)
            store.brain.record(r.networkKey, r.gameId, r.plan, v.outcome, v.weight)
            store.save()
            if (r.sampled && r.shareable) CrowdClient.queue(this, CrowdRows.session(r, v.outcome))
            Feedback.offer(this, r, facts.durationMs)
            spikeWatch?.let { w -> LastSession.save(this, r.gameId, facts.durationMs, w.spikes, w.mainCause()) }
            spikeWatch = null
            Log.i(TAG, "session ${r.gameId}/${r.plan} ${facts.durationMs / 1000}s good=${goodMs / 1000}s " +
                "bad=${badMs / 1000}s lost=$routeLost -> ${v.outcome}×${v.weight} sampled=${r.sampled}")
        } catch (e: Exception) {
            Log.w(TAG, "could not record the session: ${e.message}")
        }
    }

    /**
     * The orchestrator's teardown stops the tunnel it brought up (Aether, which seals its identity
     * files on the way out): on the main thread that froze the app for nine seconds when a boost
     * was stopped (1.2.42 #2256).
     */
    private fun abortOffMain(o: BoostOrchestrator?) {
        if (o == null) return
        Thread({ runCatching { o.abort() } }, "boost-abort").start()
    }

    private fun stopBoost(userAsked: Boolean) {
        finishSession(userStopped = userAsked)
        overlay.hide()
        job?.cancel()
        job = null
        abortOffMain(orchestrator)
        orchestrator = null
        tuner.release()
        GameBoostController.reset()
        Log.i(TAG, "boost stopped (${if (userAsked) "user" else "session ended"})")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finishSession(userStopped = false)
        overlay.hide()
        job?.cancel()
        abortOffMain(orchestrator)
        tuner.release()
        // Destroyed without Stop (the system reclaimed it): a game route left behind would keep
        // the game in a session nobody watches any more. Our own game route only, never the user's.
        val s = GameBoostController.state.value
        if (s.phase != BoostPhase.FAILED && s.outcome?.measureOnly != true) GameBoostController.reset()
        scope.cancel()
        super.onDestroy()
    }

    // ── notification ───────────────────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, S(R.string.gb_notif_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private var lastNotifAt = 0L

    private fun updateNotification(text: String, stats: PathStats?) {
        val now = System.currentTimeMillis()
        if (now - lastNotifAt < 1800) return
        lastNotifAt = now
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(text, stats))
        } catch (_: Exception) {
        }
    }

    private fun notification(text: String, @Suppress("UNUSED_PARAMETER") stats: PathStats?): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_OPEN_BOOSTER, true)
        }?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(this, 1,
            Intent(this, GameBoostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.gb_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, S(R.string.gb_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "GameBoost"
        private const val CHANNEL_ID = "game_boost"
        private const val NOTIFICATION_ID = 7720
        const val ACTION_START = "com.mlmvpn.scanner.gameboost.START"
        const val ACTION_STOP = "com.mlmvpn.scanner.gameboost.STOP"
        const val EXTRA_GAME = "game"
        const val EXTRA_REGION = "region"
        const val EXTRA_THOROUGH = "thorough"
        const val EXTRA_FORCE_WARP = "force_warp"
        const val EXTRA_SYMPTOM = "symptom"
        const val EXTRA_OPEN_BOOSTER = "open_game_booster"

        private const val BURST_EVERY_MS = 2000L
        /** How long the live card names the cause of the last spike. */
        private const val CAUSE_SHOWN_MS = 60_000L
        private const val BURST_FLOWS = 5
        private const val WINDOW_MS = 30_000L
        private const val SUSTAIN_MS = 20_000L
        private const val SERIES_POINTS = 60
        private const val MAX_SESSION_MS = 4 * 60 * 60 * 1000L
        /** Consecutive 2 s checks without our game VPN before the boost counts as ended. */
        private const val ROUTE_GONE_CHECKS = 3

        fun start(
            context: Context, gameId: String, regionKey: String?, thorough: Boolean = false, forceWarp: Boolean = false,
            symptom: com.mlmvpn.scanner.engines.game.booster.model.Symptom? = null,
        ) {
            val app = context.applicationContext
            val intent = Intent(app, GameBoostService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_GAME, gameId)
                .putExtra(EXTRA_REGION, regionKey)
                .putExtra(EXTRA_THOROUGH, thorough)
                .putExtra(EXTRA_FORCE_WARP, forceWarp)
                .putExtra(EXTRA_SYMPTOM, symptom?.name)
            androidx.core.content.ContextCompat.startForegroundService(app, intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            // Only if running -- a STOP intent to a stopped service would start it. A failed boost
            // has already cleaned up and left, and so has a measure-only one; only the card is
            // still on screen.
            val s = GameBoostController.state.value
            when {
                s.phase == BoostPhase.IDLE -> return
                s.phase == BoostPhase.FAILED -> { GameBoostController.reset(); return }
                s.phase == BoostPhase.ACTIVE && s.outcome?.measureOnly == true -> { GameBoostController.reset(); return }
                else -> Unit
            }
            try {
                com.mlmvpn.scanner.utils.SafeStart.service(app, Intent(app, GameBoostService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                GameBoostController.reset()
            }
        }
    }
}
