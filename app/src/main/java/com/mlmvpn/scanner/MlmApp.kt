package com.mlmvpn.scanner

import android.app.Application

/**
 * Exists purely so the crash reporter is installed before any other app code runs — including
 * in the VPN service process, which is where several of the native crashes have originated.
 *
 * MLM CHAT (PLAN 13, T23): an APK has one Application class, and the Telegram fork's is
 * ApplicationLoader, so this extends it. Telegram starts only in the `:chat` process (see
 * [isTelegramProcess]); there none of the VPN app's own start-up runs. In every other process
 * ApplicationLoader does nothing and the app starts exactly as before.
 */
class MlmApp : org.telegram.messenger.ApplicationLoader() {
    private val currentProcess: String? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
    }

    override fun isTelegramProcess(): Boolean = currentProcess == "$packageName:chat"

    override fun onGetApplicationId(): String = BuildConfig.APPLICATION_ID

    override fun onCreate() {
        if (isTelegramProcess()) {
            CrashReporter.install(this)
            // the home-screen widget Gereh's bridge tells when its unread count or network changes
            org.mlmvpn.chat.bridge.GerehHost.setWidget(com.mlmvpn.scanner.widget.GerehWidget::class.java)
            // the newest release the updates have seen: «old version» beside whoever runs an older one (T75)
            val app = this
            org.mlmvpn.chat.bridge.GerehHost.setRelease(object : org.mlmvpn.chat.bridge.GerehHost.Release {
                override fun latest(): String? = com.mlmvpn.scanner.update.UpdateChecker.latestKnown(app)
                override fun openUpdate(context: android.content.Context): Boolean = false
            })
            // the heart beside Gereh's menu: MLMVPN's support page
            org.mlmvpn.chat.bridge.GerehHost.setSupport("https://donito.me/ehsan2noven")
            super.onCreate() // Telegram's own start-up (ApplicationLoader)
            // a stopped storage queue left every chat loading for good (S26, 27 Oct 2026)
            CrashReporter.watchChatQueues(this)
            // «گره»'s combined relay makes its Worker on the cloud section's Cloudflare account from
            // here; what that needs of the app's start-up is done when it is first asked for
            com.mlmvpn.scanner.data.GerehCloudLink.bind(this)
            // configs in chats (n5): found and measured from here, moved into the sections by the main process
            com.mlmvpn.scanner.data.GerehConfigLink.bind(this)
            // the app's tiles and settings in chats (q11, q12): offered, named and opened from here
            com.mlmvpn.scanner.data.GerehAppLinks.bind(this)
            return
        }
        super.onCreate()
        // Started before the first unlock after a reboot (a Xiaomi, mlmvpn/crashes #3220): every
        // setting lives in credential-encrypted storage, which does not exist until then, and
        // nothing of the app is declared to run that early.
        if (android.os.Build.VERSION.SDK_INT >= 24 &&
            getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == false
        ) return
        CrashReporter.install(this)
        // A restore the user started is written here, before anything below reads a setting or
        // loads a store -- see backup/BackupStage. One file check when there is none.
        com.mlmvpn.scanner.backup.BackupStage.onAppStart(this)
        // Before any engine can be loaded or started in this process: the store's copies are
        // found through this, and a JNI copy that crashed its last load is set aside here.
        com.mlmvpn.scanner.store.StoreEngines.init(this)
        com.mlmvpn.core.warp.WarpIdRelay.bind(this)
        com.mlmvpn.scanner.data.CombineEngine.bind(this)
        val mainProcess = currentProcess == packageName
        // Off the main thread: the Keystore can take seconds to answer (it waits for its own
        // service after a reboot), and the app stood frozen at launch (ANR #542, #543).
        if (mainProcess) Thread({ runCatching { com.mlmvpn.scanner.data.CfIdentityFiles.sealKnown(this) } }, "seal-identities").start()
        // «گره» listens for messages while the app is open or not (its own foreground service in the
        // :chat process); started here too, so opening MLMVPN is enough after an update or a kill.
        if (mainProcess) org.mlmvpn.chat.bridge.GerehService.startIfReady(this)
        // Which Cloudflare edges answer on this network, for every caller (see CfEdge).
        com.mlmvpn.scanner.data.CfEdge.init(this)
        // Read once, here, so the generator's copy is right before any screen or service can ask
        // it to build a config. Every later change goes through NetworkSettings.setTlsUnfilter.
        com.mlmvpn.scanner.utils.NetworkSettings.tlsUnfilter(this)
        // Same arrangement for the Gemini / Google apps switch.
        com.mlmvpn.scanner.utils.NetworkSettings.googleFix(this)
        com.mlmvpn.scanner.utils.NetworkSettings.geminiExit(this)
        // Smart Iran route: the shipped lists in place, and today's from the daily build, once a day.
        if (mainProcess) Thread({
            runCatching {
                com.mlmvpn.core.tunnel.SmartIranLists.ensureSeeded(this)
                if (com.mlmvpn.core.tunnel.SmartIranRoute.enabled(this)) com.mlmvpn.core.tunnel.SmartIranLists.refresh(this)
            }
        }, "smart-iran-lists").start()
        // Before any screen or engine asks for a string. AppLocaleManager has to be read first
        // or Loc would pin the device's language instead of the one the user chose.
        com.mlmvpn.scanner.utils.AppLocaleManager.init(this)
        com.mlmvpn.scanner.utils.Loc.bind(this)
        com.mlmvpn.scanner.engines.cfnet.CfNetManager.bind(this)
        // The app's tunnels as the FALLBACK for deploying a Worker and taking configs when Cloudflare is
        // blocked (engines/cloud/CfTunnel.kt): direct stays first.
        com.mlmvpn.scanner.engines.cloud.CfTunnel.bind(this)
        // Proton's servers reach their WireGuard through Stealth (TLS on 443), from wherever they
        // are connected or tested -- the Proton screen, the Amnezia list, the tile.
        if (mainProcess) com.mlmvpn.scanner.engines.amnezia.AmzEngine.relay = com.mlmvpn.scanner.engines.proton.ProtonStealth.relay(this)
        // Listen for the transport stack's status broadcasts from process start, not from the
        // first time a transport screen opens. The tunnel can be started by the Quick Settings
        // tile and restored by the boot receiver, and a screen opened afterwards would
        // otherwise show "آماده" over a tunnel that is carrying the device's traffic.
        // The main process only: the tunnel and every screen live there. Registered in each helper
        // process too, the tunnel's status and traffic broadcasts (every second or two while it is
        // up) were delivered to every one of them -- and a helper the system had frozen in the
        // background had them pile up until it was killed: "can't deliver broadcast" and
        // "excessive binder traffic during cached" (1.2.41).
        if (mainProcess) com.mlmvpn.scanner.ui.tunnel.TunnelController.attach(this)
        // The home-screen widgets follow the connection from process start too, so a tunnel brought
        // up by the tile, the boot receiver or a widget is drawn on every widget at once. With no
        // widget placed this is a handful of state flows nobody writes to. Main process only, for
        // the same reason as the line above; after the language is bound (above), as they draw text.
        if (mainProcess) com.mlmvpn.scanner.widget.WidgetUpdater.attach(this)
        // «کانفیگ استدیو» on the central Worker: the check that takes an ended term off while the app
        // is closed, scheduled while this phone runs the Studio on any account. One directory listing.
        if (mainProcess) Thread({ runCatching { com.mlmvpn.scanner.data.studio.central.StudioCentralJob.ensure(this) } }, "studio-central-job").start()
        // Mirror that stack's connection log to a file. The in-memory ring holds a hundred
        // lines and dies with the process, which is exactly the wrong property for the case it
        // exists to serve: a tunnel that takes the app down with it leaves nothing to read. The
        // file is capped and truncates itself, so it cannot grow without bound.
        com.mlmvpn.core.tunnel.ConnectionLog.bind(java.io.File(filesDir, "tunnel.log"))
        // Helper engines that only serve a screen go when the app leaves the screen: a child
        // process left working in a backgrounded app gets the whole app killed for its CPU use.
        if (mainProcess) registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: android.app.Activity) {
                started++
                com.mlmvpn.scanner.utils.BackgroundGuard.onForeground()
            }
            override fun onActivityStopped(a: android.app.Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) {
                    Thread({ runCatching { com.mlmvpn.core.geph.GephEngine.onAppBackground() } }, "app-background").start()
                    // and, two minutes on with no VPN up, whatever else is still working (BackgroundGuard)
                    com.mlmvpn.scanner.utils.BackgroundGuard.onBackground(this@MlmApp)
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // The Vercel tunnel is gone; its one stored switch ("vercel_enabled") lived alone in this
        // file. Deleting it keeps an old install from carrying a setting nothing reads any more.
        runCatching { deleteSharedPreferences("emergency_prefs") }
    }
}
