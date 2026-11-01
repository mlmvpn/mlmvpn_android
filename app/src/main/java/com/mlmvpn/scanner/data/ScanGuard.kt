package com.mlmvpn.scanner.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one place that knows a scan owns the network right now.
 *
 * A scan is not a background chore. It holds up to 256 sockets open at once and then runs real
 * proxied handshakes through the Xray core, so for as long as it runs it IS the device's internet.
 * Two things collide with that and both used to fail silently:
 *
 *  - **A delay test on the connection screen.** Same core, same link, same edge. Started during a
 *    scan it reads "Timeout" on servers that are perfectly healthy, and the user concludes their
 *    configs are dead when what is actually happening is that their own scan is in the way.
 *  - **Connecting a VPN.** The tunnel takes the device's default route, so every socket the scan
 *    has open dies at once and every address still unprobed gets marked dead in a few milliseconds.
 *    The scan does not crash; it finishes early with a fake, mostly-empty result.
 *
 * Neither is a thing to forbid -- the user may well want the tunnel more than the scan. What they
 * cannot do is choose without being told, which is what this object exists for: the action is held,
 * the screen asks, and the answer is either "wait" or "stop the scan and do it".
 *
 * Every entry point routes through [run], so a new one cannot forget: the call is the same shape
 * whether a scan is running or not, and with no scan it is a direct call with one boolean's cost.
 */
object ScanGuard {

    /** What the user was trying to do, which is what the question has to be about. */
    enum class Reason {
        /** Bring up a tunnel -- ours or a transport. Takes the default route from the scan. */
        CONNECT_VPN,

        /** Measure one or many configs. Same core and same link as the scan's own verification. */
        DELAY_TEST,
    }

    /** One held action, with the reason it was held. */
    class Request(val reason: Reason, internal val proceed: () -> Unit)

    private val _pending = MutableStateFlow<Request?>(null)

    /** Non-null while a screen owes the user this question. Rendered by `ScanGuardHost`. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * True while a scan holds the network: the IP scan, or an MLM Scout run -- which is measuring the
     * user's own line, so a tunnel brought up under it would turn every number into one about the
     * tunnel.
     */
    fun isScanBusy(): Boolean = ScannerManager.isScanning.value || isScoutBusy()

    /** The one holding the network is MLM Scout (the dialog says so in its own words). */
    fun isScoutBusy(): Boolean = com.mlmvpn.scanner.engines.scout.ScoutEngine.isRunning()

    /**
     * Do [action] now, or hold it and raise the question.
     *
     * Returns true when it ran. Callers that set their own "connecting…" spinner should use the
     * return value to avoid leaving it spinning on a held action.
     */
    fun run(reason: Reason, action: () -> Unit): Boolean {
        if (!isScanBusy()) {
            action()
            return true
        }
        // A second conflict replaces the first rather than queueing: the held action is always
        // something the user just pressed, and answering a stale question would run the wrong one.
        _pending.value = Request(reason, action)
        return false
    }

    /** "I'll wait." The held action is dropped; the scan carries on. */
    fun dismiss() {
        _pending.value = null
    }

    /**
     * "Stop the scan and do it."
     *
     * The short pause is not cosmetic. [ScannerManager.stopScan] cancels a job whose probe sockets
     * are still unwinding on IO threads, and establishing a tun inside that window is the same
     * class of race `TunnelExclusion` exists to avoid.
     */
    fun stopScanAndProceed() {
        val request = _pending.value ?: return
        _pending.value = null
        val scout = isScoutBusy()
        if (ScannerManager.isScanning.value) ScannerManager.stopScan()
        // Scout keeps what it measured so far; its test cores are stopped on the way out.
        if (scout) com.mlmvpn.scanner.engines.scout.ScoutEngine.stop(com.mlmvpn.scanner.engines.scout.EndReason.STOPPED)
        scope.launch {
            // Cancellation begins cleanup; it does not mean the shared core is free yet.
            if (scout) com.mlmvpn.scanner.engines.scout.ScoutEngine.stopAndWait()
            else delay(250)
            // The wait above can outlast the screen that asked (Scout can take seconds to stop):
            // its VPN-permission launcher is unregistered by then, and launching it threw
            // IllegalStateException and closed the app (1.2.41). The user has left; nothing to do.
            try {
                request.proceed()
            } catch (e: IllegalStateException) {
                android.util.Log.w("ScanGuard", "the screen that asked is gone: ${e.message}")
            }
        }
    }
}
