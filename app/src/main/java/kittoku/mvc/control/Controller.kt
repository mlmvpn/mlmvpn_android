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
import kittoku.mvc.debug.assertAlways
import kittoku.mvc.io.incoming.IncomingManager
import kittoku.mvc.io.outgoing.OutgoingManager
import kittoku.mvc.preference.MvcPreference
import kittoku.mvc.preference.accessor.setBooleanPrefValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

        launchJobMain()
    }

    private fun launchJobMain() {
        jobMain = bridge.scope.launch(bridge.handler) {
            logWriter?.report("Connecting has been attempted")


            bridge.attachTCPTerminal()

            bridge.udpAccelerationConfig?.also {
                it.initializeNATTAddress()
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