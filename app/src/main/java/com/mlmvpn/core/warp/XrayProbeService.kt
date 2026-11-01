package com.mlmvpn.core.warp

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.runBlocking

/**
 * Runs the «آمنزیا» delay-test cores in their own process (`:xprobe`).
 *
 * WHY
 * ---
 * A test core holds dozens of public servers at once, and some of them drive Xray into a Go panic
 * ("close of closed channel", measured on a phone 2026-10-01). A Go panic cannot be caught: it
 * aborts the whole process. Here it aborts only this one -- the app, its VPN and the test carry
 * on, and the batch that was running is simply tried again in smaller pieces.
 *
 * The core listens on loopback SOCKS ports; the main process measures through them directly
 * (loopback is loopback from any process), so only start and stop cross the process boundary.
 */
open class XrayProbeService : Service() {

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val KEY_CONFIG = "config"
        const val KEY_OK = "ok"
    }

    private var core: VlessXrayInjector? = null
    private val worker = HandlerThread("xray-probe").apply { start() }
    private val messenger = Messenger(Handler(worker.looper) { msg ->
        val reply = Bundle()
        when (msg.what) {
            MSG_START -> {
                stopCore()
                val cfg = msg.data?.getString(KEY_CONFIG)
                val c = VlessXrayInjector(0)
                val ok = cfg != null && runCatching { runBlocking { c.start(applicationContext, cfg, 0) } }.getOrDefault(false)
                if (ok) core = c else runCatching { c.stop() }
                reply.putBoolean(KEY_OK, ok)
            }
            MSG_STOP -> reply.putBoolean(KEY_OK, true)
        }
        runCatching { msg.replyTo?.send(Message.obtain(null, AwgHostService.MSG_REPLY).apply { data = reply }) }
        // A stop ends the process instead of the core. Stopping a core whose WireGuard dials through a
        // noise outbound panics Xray ("close of closed channel": the noise handler closes first, the
        // WireGuard bind sees its connection end and takes the device down while the core closes it
        // too) -- on the user's A50, 2026-10-11, at the end of every Amnezia test batch, which then
        // read as "the core died" and was tested again in halves. Nothing is lost: the process exists
        // for this one core, and the next start brings a fresh one.
        if (msg.what == MSG_STOP && core != null) android.os.Process.killProcess(android.os.Process.myPid())
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        // On the worker, not here: Xray's stopLoop can block for seconds while its connections
        // drain, and on the main thread that was an ANR of this process (1.2.39). quitSafely
        // still runs the stop, after whatever was already queued.
        Handler(worker.looper).post { stopCore() }
        worker.quitSafely()
        super.onDestroy()
    }

    private fun stopCore() {
        core?.let { runCatching { it.stop() } }
        core = null
    }
}

/**
 * The shared Cloudflare WARP carrier ([com.mlmvpn.scanner.engines.windscribe.WsWarpCarrier]) in a
 * process of its own (`:xcarrier`). Its WireGuard outbound dials through a noise outbound, and that
 * pairing panics Xray now and then ("close of closed channel" after "Unable to update bind ... no
 * outbound handler for dialerProxy", the user's phone 2026-10-11): in the main process it closed the
 * app whenever Windscribe or Outline went through WARP. Here it ends this process only.
 */
class XrayCarrierService : XrayProbeService()

/**
 * Proton's Cloudflare carrier (ProtonCfCarrier) in a process of its own (`:pcarrier`). It starts a
 * core per candidate config and stops the ones that carried nothing; a config that dials through
 * another outbound panics Xray at that stop, and in the main process that closed the app on opening
 * Proton (a tester's phone, 2026-10-11: SIGABRT in libgojni.so, two «proton-cf-warm» threads in Go).
 * Its own process, not Windscribe's: the two carriers can be up at the same time.
 */
class XrayProtonCarrierService : XrayProbeService()

/**
 * The Xray an Aether session dials out through when its ClientHello has to be cut into TLS records
 * ([com.mlmvpn.core.aether.AetherXrayExit], `:aexit`): one freedom outbound with the tlshello
 * finalmask behind a loopback SOCKS port, for as long as that session runs.
 */
class XrayAetherExitService : XrayProbeService()

/** The same for a WARP registration ([com.mlmvpn.core.aether.AetherRegister], `:aregexit`), which can run beside a session. */
class XrayAetherRegExitService : XrayProbeService()
