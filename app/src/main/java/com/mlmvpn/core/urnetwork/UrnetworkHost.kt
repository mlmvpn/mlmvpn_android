package com.mlmvpn.core.urnetwork

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.ext.SdkExtensions
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.mlmvpn.scanner.store.tr
import java.util.concurrent.Executors

/**
 * What the phone says to every running URnetwork core -- the tile's, MAE's and Scout's, each a
 * device of its own -- as URnetwork's official Android app tells its SDK device (its
 * MainApplication):
 *
 *  - «network»: the phone's own network changed -- Wi-Fi to mobile data and back, a network lost
 *    and replaced. The SDK redials its transports over the new path at once and the providers
 *    stay; a rebuild would throw the whole window away. The new network's resolvers and mobile
 *    country go first.
 *  - «quality»: the same network with another Wi-Fi level, cell signal, radio type or link
 *    estimate. The SDK remeasures its pacing.
 *  - «degraded»: battery saver, a phone hot enough to be throttled, Data Saver on a metered
 *    network, or a congested or bandwidth-constrained network. The SDK eases its liveness timings
 *    and rests 3x longer between idle pings, so an idle tunnel wakes the radio less often.
 *  - «probe»: the phone woke up (screen on, unlock, Doze over). Every exit is checked again at
 *    once, and a core still without a provider 5 s later has its server connection redialed.
 *
 * Watches only while a core runs, and only the physical networks: the app keeps itself off its own
 * VPN in every mode, so the network it uses is a physical one, as the official app's is.
 */
object UrnetworkHost {

    private const val TAG = "UrnetworkHost"

    /** NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED: API 36, and Android 14 with U extension 16. */
    private const val NOT_BANDWIDTH_CONSTRAINED = 37

    private val lock = Any()
    private val cores = LinkedHashSet<UrnetworkProcess>()
    // Lazy, so the pure helpers below need no Android runtime (the unit tests).
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Commands leave on a thread of their own: a core's stdin is a pipe. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "urnetwork-host").apply { isDaemon = true } }

    // Everything below belongs to the main thread.
    private var app: Context? = null
    private var watching = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val receivers = mutableListOf<BroadcastReceiver>()
    private var thermalListener: Any? = null
    private var cellCallbacks: List<Any> = emptyList()
    private var pendingNetwork: Runnable? = null
    private var pendingWake: Runnable? = null
    private var pendingEval: Runnable? = null
    private var lastWake = 0L
    private val physical = LinkedHashSet<Network>()

    private var thermal = false
    private var dataSaver = false
    private var pressure = false

    // The default network, as the official DefaultNetworkTracker and DefaultNetworkQualityTracker
    // keep it: the first callback is the baseline, every later change of network is a new path,
    // and only a changed estimate on the same network is a quality change.
    private var initialized = false
    private var current: Network? = null
    private var lostSinceAvailable = false
    private var quality: Quality? = null
    private var cellular = false
    private var cellLevel: Int? = null
    private var cellType: Pair<Int, Int>? = null

    private data class Quality(val wifiLevel: Int?, val down: Int, val up: Int)

    /** For a core about to start: is the phone saving power or held back right now? */
    fun degradedNow(context: Context): Boolean {
        val c = context.applicationContext
        val hot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Api29Thermal.hot(c)
        val busy = runCatching {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            cm?.getNetworkCapabilities(cm.activeNetwork)?.let { pressured(it) } ?: false
        }.getOrDefault(false)
        return reasons(c, hot, dataSaverNow(c), busy).isNotEmpty()
    }

    /** A core started: it gets every signal from now on (the first one starts the watching). */
    fun attach(context: Context, core: UrnetworkProcess) {
        val c = context.applicationContext
        synchronized(lock) { cores += core }
        main.post { if (watching) applyDegraded() else start(c) }
    }

    /** A core ended or is ending; the last one stops the watching. */
    fun detach(core: UrnetworkProcess) {
        val none = synchronized(lock) { cores.remove(core) && cores.isEmpty() }
        if (none) main.post { if (synchronized(lock) { cores.isEmpty() }) stop() }
    }

    private fun start(c: Context) {
        if (watching) return
        watching = true
        app = c
        thermal = false
        dataSaver = dataSaverNow(c)
        pressure = false
        initialized = false
        current = null
        lostSinceAvailable = false
        quality = null
        cellular = false
        cellLevel = null
        cellType = null
        watchPower(c)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = Api29Thermal.register(c) { hot ->
                if (watching && hot != thermal) {
                    thermal = hot
                    applyDegraded()
                }
            }
        }
        watchDataSaver(c)
        watchNetwork(c)
        watchWake(c)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cellCallbacks = Api31Cell.register(c, ::onCellLevel, ::onCellType)
        }
        applyDegraded()
    }

    private fun stop() {
        if (!watching) return
        watching = false
        val c = app ?: return
        pendingNetwork?.let(main::removeCallbacks)
        pendingNetwork = null
        pendingWake?.let(main::removeCallbacks)
        pendingWake = null
        pendingEval?.let(main::removeCallbacks)
        pendingEval = null
        physical.clear()
        receivers.forEach { r -> runCatching { c.unregisterReceiver(r) } }
        receivers.clear()
        netCallback?.let { cb -> runCatching { c.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalListener?.let { Api29Thermal.unregister(c, it) }
        thermalListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Api31Cell.unregister(c, cellCallbacks)
        cellCallbacks = emptyList()
    }

    // ── battery saver, heat, Data Saver, a congested network ─────────────────────────────────

    private fun watchPower(c: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (watching) applyDegraded()
            }
        }
        // A protected system broadcast: exported, as the official app registers it, so every OEM's
        // framework sender reaches it while nothing else can send it.
        runCatching {
            ContextCompat.registerReceiver(c, r, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            receivers += r
        }.onFailure { Log.w(TAG, "battery saver not watched: ${it.message}") }
    }

    private fun watchDataSaver(c: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (watching) updateDataSaver()
            }
        }
        // Delivered only to receivers registered at run time.
        runCatching {
            ContextCompat.registerReceiver(c, r, IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            receivers += r
        }.onFailure { Log.w(TAG, "Data Saver not watched: ${it.message}") }
    }

    private fun updateDataSaver() {
        val c = app ?: return
        val next = dataSaverNow(c)
        if (next == dataSaver) return
        dataSaver = next
        applyDegraded()
    }

    private fun dataSaverNow(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { dataSaverDegrades(cm.isActiveNetworkMetered, cm.restrictBackgroundStatus) }.getOrDefault(false)
    }

    /** Data Saver holds this app back only while its network is metered (the official policy). */
    internal fun dataSaverDegrades(metered: Boolean, status: Int): Boolean =
        metered && status != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED

    /** The network says it is congested, or (Android 14+) that it is short of bandwidth. */
    @SuppressLint("WrongConstant")
    internal fun pressured(caps: NetworkCapabilities): Boolean {
        val congested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
        val constrained = bandwidthCapabilityKnown() && !caps.hasCapability(NOT_BANDWIDTH_CONSTRAINED)
        return congested || constrained
    }

    private fun bandwidthCapabilityKnown(): Boolean =
        Build.VERSION.SDK_INT >= 36 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                runCatching { SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) }.getOrDefault(0) >= 16)

    /** What holds the phone back now, in the user's words; empty when nothing does. */
    private fun reasons(c: Context, hot: Boolean, saver: Boolean, busy: Boolean): List<String> {
        val powerSave = runCatching { c.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
        return buildList {
            if (powerSave) add(tr("صرفه‌جویی باتری", "battery saver"))
            if (hot) add(tr("گوشی داغ است", "the phone is hot"))
            if (saver) add(tr("صرفه‌جویی داده", "Data Saver"))
            if (busy) add(tr("شبکه شلوغ است", "the network is congested"))
        }
    }

    /** Every core in line with the phone's state now; the tile's screen says why. */
    private fun applyDegraded() {
        val c = app ?: return
        val why = reasons(c, thermal, dataSaver, pressure)
        val on = why.isNotEmpty()
        val list = synchronized(lock) { cores.toList() }
        writer.execute {
            list.forEach { core ->
                if (core.degraded != on) {
                    core.degraded = on
                    core.command("degraded ${if (on) 1 else 0}")
                }
            }
        }
        if (list.any { it.role == "tile" }) UrnetworkEngine.savingChanged(why)
    }

    // ── waking up ────────────────────────────────────────────────────────────────────────────

    /**
     * The official app's wake audit: Android has no sleep/wake callback for a VPN, so the screen
     * coming on, the user unlocking and Doze ending stand for it. Every exit is checked again at
     * once; after a 5 s grace a core that still has no provider gets its server connection redialed.
     */
    private fun watchWake(c: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!watching) return
                if (intent?.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                    val pm = c.getSystemService(PowerManager::class.java) ?: return
                    if (!pm.isInteractive || pm.isDeviceIdleMode) return
                }
                wakeAudit()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        // Protected system broadcasts, delivered only to receivers registered at run time.
        runCatching {
            ContextCompat.registerReceiver(c, r, filter, ContextCompat.RECEIVER_EXPORTED)
            receivers += r
        }.onFailure { Log.w(TAG, "wake not watched: ${it.message}") }
    }

    private fun wakeAudit() {
        // The screen and the unlock arrive together: one audit for both.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWake < 5_000) return
        lastWake = now
        send("probe")
        pendingWake?.let(main::removeCallbacks)
        val r = Runnable {
            pendingWake = null
            val idle = synchronized(lock) { cores.filter { it.added == 0 } }
            if (idle.isNotEmpty()) writer.execute { idle.forEach { it.command("network") } }
        }
        pendingWake = r
        main.postDelayed(r, 5_000)
    }

    // ── the default network ──────────────────────────────────────────────────────────────────

    /**
     * Every physical network (never a VPN), and from them the one this app uses now. Not the
     * default-network callback: bringing the tile's VPN up hands default requests to the VPN for a
     * moment (seen on the owner's phone: NetReassign 630 -> 667), which that callback reported as a
     * new path and which would redial every transport right after connecting.
     */
    private fun watchNetwork(c: Context) {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (netCallback !== this) return@post
                    physical += network
                    evaluate()
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                main.post {
                    if (netCallback !== this) return@post
                    if (network == current) onCaps(network, caps)
                    evaluate()
                }
            }

            override fun onLost(network: Network) {
                main.post {
                    if (netCallback !== this) return@post
                    physical -= network
                    if (initialized && network == current) onCurrentLost()
                    evaluate()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        netCallback = cb
        physical.clear()
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onFailure {
                netCallback = null
                Log.w(TAG, "networks not watched: ${it.message}")
            }
    }

    /** The network in use, read once the callbacks of a change have settled (they come before it moves). */
    private fun evaluate() {
        pendingEval?.let(main::removeCallbacks)
        val r = Runnable {
            pendingEval = null
            if (watching) physicalDefault()?.let { onDefault(it) }
        }
        pendingEval = r
        main.postDelayed(r, 300)
    }

    private fun physicalDefault(): Network? {
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            if (active != null && caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                active
            } else {
                // The app keeps itself off its own VPN, so this is only a moment's answer: the
                // validated physical network, Wi-Fi first.
                physical.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
                    .filter { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                    .maxByOrNull { if (it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 1 }
                    ?.first
            }
        }.getOrNull()
    }

    private fun onDefault(network: Network) {
        if (initialized && current == network && !lostSinceAvailable) return
        val changed = initialized && (lostSinceAvailable || current != network)
        initialized = true
        if (current != network) {
            // Another network: its first estimate is a baseline, and its pressure is read anew.
            quality = null
            cellular = false
            if (pressure) {
                pressure = false
                applyDegraded()
            }
        }
        current = network
        lostSinceAvailable = false
        Log.i(TAG, "network in use: $network" + if (changed) " (a new path)" else "")
        app?.getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(network)?.let { onCaps(network, it) }
        if (changed) networkChanged()
        updateDataSaver()
    }

    private fun onCaps(network: Network, caps: NetworkCapabilities) {
        if (network != current) return
        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val busy = pressured(caps)
        if (busy != pressure) {
            pressure = busy
            applyDegraded()
        }
        val next = qualityOf(caps)
        val before = quality
        quality = next
        if (before != null && before != next) send("quality")
        // Meteredness can change with the capabilities and no Data Saver broadcast.
        updateDataSaver()
    }

    private fun onCurrentLost() {
        current = null
        lostSinceAvailable = true
        quality = null
        cellular = false
        if (pressure) {
            pressure = false
            applyDegraded()
        }
        updateDataSaver()
    }

    /** A new path: the new resolvers and country first, then the redial -- once per burst of callbacks. */
    private fun networkChanged() {
        pendingNetwork?.let(main::removeCallbacks)
        val r = Runnable {
            pendingNetwork = null
            val c = app ?: return@Runnable
            val dns = com.mlmvpn.core.lantern.LanternEngine.systemDns(c).joinToString(",")
            send("dns $dns", "country ${UrnetworkEngine.networkCountry(c)}", "network")
            if (synchronized(lock) { cores.any { it.role == "tile" } }) {
                UrnetworkEngine.record(tr(
                    "شبکهٔ گوشی عوض شد — اتصال‌ها روی شبکهٔ تازه دوباره برقرار شد و ارائه‌دهنده‌ها ماندند",
                    "The phone's network changed -- the connections were redialed over it and the providers stayed",
                ))
            }
        }
        pendingNetwork = r
        main.postDelayed(r, 1_000)
    }

    private fun qualityOf(caps: NetworkCapabilities): Quality {
        val strength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) caps.signalStrength else Int.MIN_VALUE
        return Quality(signalLevel(strength), bandwidthBucket(caps.linkDownstreamBandwidthKbps), bandwidthBucket(caps.linkUpstreamBandwidthKbps))
    }

    /** Five steady levels: raw dBm jitter must not remeasure on every callback. */
    internal fun signalLevel(dbm: Int): Int? = when {
        dbm == Int.MIN_VALUE -> null
        dbm < -90 -> 0
        dbm < -80 -> 1
        dbm < -70 -> 2
        dbm < -60 -> 3
        else -> 4
    }

    /** Power-of-two bands: a real change of capacity counts, the estimator's noise does not. */
    internal fun bandwidthBucket(kbps: Int): Int = if (kbps <= 0) 0 else Integer.highestOneBit(kbps)

    private fun onCellLevel(level: Int) {
        val before = cellLevel
        cellLevel = level
        if (watching && cellular && before != null && before != level) send("quality")
    }

    private fun onCellType(type: Int, override: Int) {
        val next = type to override
        val before = cellType
        cellType = next
        if (watching && cellular && before != null && before != next) send("quality")
    }

    /** The mobile network's country while on mobile data, "" on anything else (the official NetworkCountryReporter). */
    internal fun countryFor(cellular: Boolean, iso: String?): String {
        if (!cellular) return ""
        val cc = iso?.trim()?.lowercase() ?: return ""
        return if (cc.length == 2 && cc.all { it in 'a'..'z' }) cc else ""
    }

    private fun send(vararg lines: String) {
        val list = synchronized(lock) { cores.toList() }
        if (list.isEmpty()) return
        writer.execute { list.forEach { core -> lines.forEach { core.command(it) } } }
    }
}

/** API 29 thermal classes kept out of [UrnetworkHost]'s own linkage, as the official app keeps them. */
@RequiresApi(Build.VERSION_CODES.Q)
private object Api29Thermal {
    // SEVERE is where the official apps call the phone throttled enough that pings answer slowly.
    fun hot(c: Context): Boolean =
        runCatching { (c.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE }.getOrDefault(false)

    /** Registration reports the current status once, the baseline. */
    fun register(c: Context, onHot: (Boolean) -> Unit): Any? {
        val pm = c.getSystemService(PowerManager::class.java) ?: return null
        val listener = PowerManager.OnThermalStatusChangedListener { status -> onHot(status >= PowerManager.THERMAL_STATUS_SEVERE) }
        return runCatching {
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(c), listener)
            listener
        }.getOrNull()
    }

    fun unregister(c: Context, listener: Any) {
        runCatching { c.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(listener as PowerManager.OnThermalStatusChangedListener) }
    }
}

/** Cell bars and the shown radio type (API 31), each on its own callback: one may need a permission the other does not. */
@RequiresApi(Build.VERSION_CODES.S)
private object Api31Cell {
    private class Level(val onLevel: (Int) -> Unit) : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onLevel(signalStrength.level)
    }

    private class Type(val onType: (Int, Int) -> Unit) : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) = onType(info.networkType, info.overrideNetworkType)
    }

    fun register(c: Context, onLevel: (Int) -> Unit, onType: (Int, Int) -> Unit): List<Any> {
        val tm = c.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val executor = ContextCompat.getMainExecutor(c)
        return listOf<TelephonyCallback>(Level(onLevel), Type(onType)).mapNotNull { cb ->
            runCatching { tm.registerTelephonyCallback(executor, cb); cb }
                .onFailure { Log.i("UrnetworkHost", "cell callback not available: ${it.message}") }
                .getOrNull()
        }
    }

    fun unregister(c: Context, callbacks: List<Any>) {
        val tm = c.getSystemService(TelephonyManager::class.java) ?: return
        callbacks.forEach { cb -> runCatching { tm.unregisterTelephonyCallback(cb as TelephonyCallback) } }
    }
}
