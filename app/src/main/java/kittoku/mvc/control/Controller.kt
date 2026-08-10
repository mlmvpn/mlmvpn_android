package kittoku.mvc.control

import androidx.preference.PreferenceManager
import kittoku.mvc.ControlMessage
import kittoku.mvc.Result
import kittoku.mvc.SharedBridge
import kittoku.mvc.Where
import kittoku.mvc.client.ARPClient
import kittoku.mvc.client.ARP_NEGOTIATION_TIMEOUT
import kittoku.mvc.client.DHCP_NEGOTIATION_TIMEOUT
import kittoku.mvc.client.DhcpClient
import kittoku.mvc.client.SOFTETHER_NEGOTIATION_TIMEOUT
import kittoku.mvc.client.SoftEtherClient
import kittoku.mvc.debug.Telemetry
import kittoku.mvc.debug.assertAlways
import kittoku.mvc.io.incoming.IncomingManager
import kittoku.mvc.io.outgoing.OutgoingManager
import kittoku.mvc.preference.MvcPreference
import kittoku.mvc.preference.accessor.setBooleanPrefValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull


internal class Controller(private val bridge: SharedBridge) {
    private var jobMain: Job? = null

    private var incomingManager: IncomingManager? = null
    private var outgoingManager: OutgoingManager? = null

    private var networkObserver: NetworkObserver? = null
    private var logWriter: LogWriter? = null

    private var softEtherClient: SoftEtherClient? = null
    private var dhcpClient: DhcpClient? = null
    private var arpClient: ARPClient? = null

    private var isClosing = false
    private val mutex = Mutex()

    internal fun run() {
        if (bridge.isLogEnabled && bridge.logDirectory != null) {
            logWriter = LogWriter(bridge)
        }

        Telemetry.reset()
        android.util.Log.i(
            Telemetry.TAG,
            "session start: udpAccel=${bridge.udpAccelerationConfig != null} " +
                "innerMTU=${bridge.internalEthernetMTU} maxFrame=${bridge.maxInternalFrameSize}"
        )

        launchJobMain()
    }

    private var jobTelemetry: Job? = null

    private fun launchJobTelemetry() {
        jobTelemetry = bridge.scope.launch(bridge.handler) {
            while (isActive) {
                delay(2000)
                Telemetry.report()
            }
        }
    }

    private fun launchJobMain() {
        jobMain = bridge.scope.launch(bridge.handler) {
            logWriter?.report("Connecting has been attempted")


            bridge.attachTCPTerminal()

            bridge.udpAccelerationConfig?.also { config ->
                // Resolve the NAT-T host CONCURRENTLY, not before continuing. Measured at 6.6s
                // of a 14s connect on a censored resolver, for a lookup whose result is not
                // needed until the first NAT-T inquiry — and not needed at all when the direct
                // endpoint works, which is the common case. trySendUDPInquireNATT() skips
                // itself until the address lands.
                bridge.scope.launch(bridge.handler) {
                    withContext(Dispatchers.IO) {
                        val started = System.currentTimeMillis()
                        val ok = config.initializeNATTAddress()
                        android.util.Log.d(
                            Telemetry.TAG,
                            "NAT-T resolve ${if (ok) "ok" else "failed"} in " +
                                "${System.currentTimeMillis() - started}ms"
                        )
                    }
                }
                bridge.attachUDPTerminal()
            }

            bridge.attachIPTerminal()


            IncomingManager(bridge).also {
                it.launchJobTCP()
                incomingManager = it
            }


            android.util.Log.d("SoftEther", "stage: SoftEther negotiation")


            // SoftEther negotiation
            SoftEtherClient(bridge).also {
                softEtherClient = it
                incomingManager!!.registerMailbox(it)
                it.launchJobNegotiation()

                if (!expectProceeded(Where.SOFTETHER, SOFTETHER_NEGOTIATION_TIMEOUT)) {
                    return@launch
                }

                incomingManager!!.unregisterMailbox(it)
            }


            android.util.Log.d("SoftEther", "stage: DHCP negotiation")


            // DHCP negotiation
            DhcpClient(bridge).also {
                dhcpClient = it
                incomingManager!!.registerMailbox(it)
                it.launchJobNegotiation()

                if (!expectProceeded(Where.DHCP, DHCP_NEGOTIATION_TIMEOUT)) {
                    return@launch
                }

                incomingManager!!.unregisterMailbox(dhcpClient)
            }


            android.util.Log.d("SoftEther", "stage: ARP negotiation")


            // ARP negotiation
            ARPClient(bridge).also {
                arpClient = it
                incomingManager!!.registerMailbox(it)
                it.launchJobNegotiation()

                if (!expectProceeded(Where.ARP, ARP_NEGOTIATION_TIMEOUT)) {
                    return@launch
                }

                it.launchJobControl()
            }


            // if this is test, we need to get out because VpnService.Builder is not given
            if (bridge.isTest) {
                return@launch
            }


            // start observing network
            networkObserver = NetworkObserver(bridge)


            android.util.Log.d("SoftEther", "stage: establishing tun")


            // Establish VPN connection
            bridge.tcpTerminal!!.setTimeoutForData()
            bridge.ipTerminal!!.initializeBuilder()

            OutgoingManager(bridge).also {
                outgoingManager = it
                it.launchJobRetrieve()
                it.launchJobMain()
            }

            bridge.udpAccelerationConfig?.also {
                incomingManager!!.launchJobUDP()
            }

            // One line every two seconds is enough to watch a session live without drowning
            // the log. Separate from logWriter, which writes to a file the user has to go and
            // fetch; this is for `adb logcat -s MlmVpnPerf:D` while the phone is in hand.
            launchJobTelemetry()

            logWriter?.report("VPN connection has been established")
            // MLMVPN: the one point where the tunnel is known to be carrying traffic.
            kittoku.mvc.service.SoftEtherVpnService.isConnected = true


            expectProceeded(Where.CONTROL, null) // wait until disconnection
        }
    }

    private suspend fun expectProceeded(where: Where, timeout: Long?): Boolean {
        val received = if (timeout != null) {
            withTimeoutOrNull(timeout) {
                bridge.controlMailbox.receive()
            } ?: ControlMessage(where, Result.ERR_TIMEOUT)
        } else {
            bridge.controlMailbox.receive()
        }

        if (received.result == Result.PROCEEDED) {
            assertAlways(received.from == where)

            return true
        }

        kill(null)

        val header = "${received.from.name}: ${received.result.name}"
        var log = header
        if (received.supplement != null) {
            log += "\n${received.supplement}"
        }

        logWriter?.report(log)
        bridge.service.notifyError(header)


        return false
    }

    internal fun kill(throwable: Throwable?) {
        bridge.scope.launch {
            mutex.withLock {
                if (!isClosing) {
                    if (throwable != null) {
                        logWriter?.reportThrowable(throwable)
                        bridge.service.notifyError("MVC: ERR_UNEXPECTED")
                    }

                    isClosing = true

                    cancelClients()
                    closeTerminals()
                    networkObserver?.close()
                    jobTelemetry?.cancel()
                    jobMain?.cancel()

                    PreferenceManager.getDefaultSharedPreferences(bridge.service).also {
                        setBooleanPrefValue(false, MvcPreference.HOME_CONNECTOR, it)
                    }

                    if (throwable == null) {
                        // after confirming everything was OK
                        logWriter?.report("The connection has been closed")
                    }

                    logWriter?.close()

                    bridge.service.stopForeground(true)
                    bridge.service.stopSelf()
                }
            }
        }
    }

    private fun cancelClients() {
        softEtherClient?.cancel()
        dhcpClient?.cancel()
        arpClient?.cancel()
        incomingManager?.cancel()
        outgoingManager?.cancel()
    }

    private fun closeTerminals() {
        bridge.tcpTerminal?.close()
        bridge.udpTerminal?.close()
        bridge.ipTerminal?.close()
    }
}