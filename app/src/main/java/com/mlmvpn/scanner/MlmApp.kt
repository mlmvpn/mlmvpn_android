package com.mlmvpn.scanner

import android.app.Application

/**
 * Exists purely so the crash reporter is installed before any other app code runs — including
 * in the VPN service process, which is where several of the native crashes have originated.
 */
class MlmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Before any engine can be loaded or started in this process: the store's copies are
        // found through this, and a JNI copy that crashed its last load is set aside here.
        com.mlmvpn.scanner.store.StoreEngines.init(this)
        com.mlmvpn.core.warp.WarpIdRelay.bind(this)
        // Read once, here, so the generator's copy is right before any screen or service can ask
        // it to build a config. Every later change goes through NetworkSettings.setTlsUnfilter.
        com.mlmvpn.scanner.utils.NetworkSettings.tlsUnfilter(this)
        // Same arrangement for the Gemini / Google apps switch.
        com.mlmvpn.scanner.utils.NetworkSettings.googleFix(this)
        com.mlmvpn.scanner.utils.NetworkSettings.geminiExit(this)
        // Before any screen or engine asks for a string. AppLocaleManager has to be read first
        // or Loc would pin the device's language instead of the one the user chose.
        com.mlmvpn.scanner.utils.AppLocaleManager.init(this)
        com.mlmvpn.scanner.utils.Loc.bind(this)
        // Read once, here, so every screen can ask whether this device should run in
        // performance mode (lighter animation and rendering) without re-detecting.
        com.mlmvpn.scanner.utils.Perf.init(this)
        // Listen for the transport stack's status broadcasts from process start, not from the
        // first time a transport screen opens. The tunnel can be started by the Quick Settings
        // tile and restored by the boot receiver, and a screen opened afterwards would
        // otherwise show "آماده" over a tunnel that is carrying the device's traffic.
        com.mlmvpn.scanner.ui.tunnel.TunnelController.attach(this)
        // Mirror that stack's connection log to a file. The in-memory ring holds a hundred
        // lines and dies with the process, which is exactly the wrong property for the case it
        // exists to serve: a tunnel that takes the app down with it leaves nothing to read. The
        // file is capped and truncates itself, so it cannot grow without bound.
        com.mlmvpn.core.tunnel.ConnectionLog.bind(java.io.File(filesDir, "tunnel.log"))
        // The Vercel tunnel is gone; its one stored switch ("vercel_enabled") lived alone in this
        // file. Deleting it keeps an old install from carrying a setting nothing reads any more.
        runCatching { deleteSharedPreferences("emergency_prefs") }
    }
}
