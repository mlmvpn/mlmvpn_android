package kittoku.mvc

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.net.toUri
import kittoku.mvc.extension.nextBytes
import kittoku.mvc.extension.read
import kittoku.mvc.extension.toHexByteArray
import kittoku.mvc.extension.toHexString
import kittoku.mvc.preference.MvcPreference
import kittoku.mvc.preference.accessor.getBooleanPrefValue
import kittoku.mvc.preference.accessor.getIntPrefValue
import kittoku.mvc.preference.accessor.getSetPrefValue
import kittoku.mvc.preference.accessor.getStringPrefValue
import kittoku.mvc.preference.accessor.setStringPrefValue
import kittoku.mvc.service.SoftEtherVpnService
import kittoku.mvc.teminal.CHACHA20_POLY1305_NONCE_SIZE
import kittoku.mvc.teminal.CHACHA20_POLY1305_TAG_SIZE
import kittoku.mvc.teminal.IPTerminal
import kittoku.mvc.teminal.TCPTerminal
import kittoku.mvc.teminal.UDPStatus
import kittoku.mvc.teminal.UDPTerminal
import kittoku.mvc.teminal.UDP_SOFTETHER_HEADER_SIZE
import kittoku.mvc.unit.ETHERNET_HEADER_SIZE
import kittoku.mvc.unit.ETHERNET_MAC_ADDRESS_SIZE
import kittoku.mvc.unit.ETHERNET_MAX_MTU
import kittoku.mvc.unit.IPv4_ADDRESS_SIZE
import kittoku.mvc.unit.IPv4_HEADER_SIZE
import kittoku.mvc.unit.UDP_HEADER_SIZE
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import java.net.Inet4Address
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.net.ssl.SSLSocket


/** Inner MTU that still fits a 1500-byte path once SoftEther/TLS/TCP framing is added. */
internal const val TCP_TUNNEL_SAFE_MTU = 1350

internal enum class Where {
    SOFTETHER,
    DHCP,
    ARP,
    CONTROL,
}

internal enum class Result {
    PROCEEDED,

    // common errors
    ERR_TIMEOUT,
    INVALID_CONFIGURATION,
}

internal data class ControlMessage(
    val from: Where,
    val result: Result,
    val supplement: String? = null
)

internal class UDPAccelerationConfig(random: SecureRandom) {
    internal var status = UDPStatus.CLOSED

    internal lateinit var clientReportedAddress: Inet4Address
    internal var clientNATTAddress: Inet4Address? = null
    internal var clientReportedPort = 0
    internal var clientNATTPort = 0
    internal var clientCookie = 0
    internal lateinit var clientKey: SecretKey

    internal var serverCurrentAddress: Inet4Address? = null
    internal lateinit var serverReportedAddress: Inet4Address
    internal var serverNATTAddress: Inet4Address? = null
    internal var serverCurrentPort = 0
    internal var serverReportedPort = 0
    internal var serverNATTPort = 0
    internal var serverCookie = 0
    internal lateinit var serverKey: SecretKey

    private val nattHostname: String
    internal lateinit var nattAddress: Inet4Address

    internal val validServerPorts: List<Int>
        get() = mutableListOf<Int>().also {
            if (serverCurrentPort != 0) {
                it.add(serverCurrentPort)
            }

            if (serverReportedPort != 0 && serverReportedPort != serverCurrentPort) {
                it.add(serverReportedPort)
            }

            if (serverNATTPort != 0 && serverNATTPort != serverCurrentPort && serverNATTPort != serverReportedPort) {
                it.add(serverNATTPort)
            }
        }

    internal val validServerAddresses: List<Inet4Address>
        get() = mutableListOf<Inet4Address>().also { list ->
            serverCurrentAddress?.also {
                list.add(it)
            }

            serverReportedAddress.also {
                if (it != serverCurrentAddress) {
                    list.add(it)
                }
            }

            serverNATTAddress?.also {
                if (it != serverCurrentAddress && it != serverReportedAddress) {
                    list.add(it)
                }
            }
        }

    init {
        val hex = random.nextBytes(1).toHexString().lowercase()
        nattHostname = "x${hex[0]}.x${hex[1]}.dev.servers.nat-traversal.softether-network.net"
    }

    internal fun initializeNATTAddress() {
        nattAddress = Inet4Address.getByName(nattHostname) as Inet4Address
    }
}

internal class SharedBridge(internal val scope: CoroutineScope, internal val handler: CoroutineExceptionHandler) {
    internal lateinit var service: SoftEtherVpnService // separate from constructor for test
    internal lateinit var socket: SSLSocket

    internal var tcpTerminal: TCPTerminal? = null
    internal var udpTerminal: UDPTerminal? = null
    internal var ipTerminal: IPTerminal? = null

    internal var isTest = false
    internal val controlMailbox = Channel<ControlMessage>(1)
    internal val httpRequestChannel = Channel<Boolean>(1)

    internal var serverPort: Int = 0
    internal var serverHostname: String = ""
    internal var serverHubName: String = ""
    internal var clientUsername: String = ""
    internal var clientPassword: String = ""
    internal val clientMacAddress = ByteArray(ETHERNET_MAC_ADDRESS_SIZE)

    internal var sslVersion = "DEFAULT"
    internal var doSelectCipherSuites = false
    internal val selectedCipherSuites = mutableListOf<String>()

    internal var udpAccelerationConfig: UDPAccelerationConfig? = null

    internal var internalEthernetMTU = 0
    internal var maxInternalFrameSize = 0

    internal val assignedIpAddress = ByteArray(IPv4_ADDRESS_SIZE)
    internal val subnetMask = ByteArray(IPv4_ADDRESS_SIZE)
    internal val defaultGatewayIpAddress = ByteArray(IPv4_ADDRESS_SIZE)
    internal val defaultGatewayMacAddress = ByteArray(ETHERNET_MAC_ADDRESS_SIZE)
    internal val dhcpServerIpAddress  = ByteArray(IPv4_ADDRESS_SIZE)

    internal var dnsServerIpAddress: ByteArray? = null
    internal var leaseTime: Long? = null

    internal var isLogEnabled = false
    internal var logDirectory: Uri? = null

    internal val random = SecureRandom()

    internal fun prepareParameters(prefs: SharedPreferences) {
        serverPort = getIntPrefValue(MvcPreference.SSL_PORT, prefs)
        serverHostname = getStringPrefValue(MvcPreference.HOME_HOSTNAME, prefs)
        serverHubName = getStringPrefValue(MvcPreference.HOME_HUB, prefs)
        clientUsername = getStringPrefValue(MvcPreference.HOME_USERNAME, prefs)
        clientPassword = getStringPrefValue(MvcPreference.HOME_PASSWORD, prefs)
        clientMacAddress.read(loadMac(prefs))

        sslVersion = getStringPrefValue(MvcPreference.SSL_VERSION, prefs)
        doSelectCipherSuites = getBooleanPrefValue(MvcPreference.SSL_DO_SELECT_SUITES, prefs)
        selectedCipherSuites.clear()
        if (doSelectCipherSuites) {
            getSetPrefValue(MvcPreference.SSL_SUITES, prefs).forEach { selectedCipherSuites.add(it) }
        }

        if (getBooleanPrefValue(MvcPreference.UDP_ENABLE_ACCELERATION, prefs)) {
            udpAccelerationConfig = UDPAccelerationConfig(random)

            internalEthernetMTU = getIntPrefValue(MvcPreference.ETHERNET_MTU, prefs)
            internalEthernetMTU -= IPv4_HEADER_SIZE
            internalEthernetMTU -= UDP_HEADER_SIZE
            internalEthernetMTU -= UDP_SOFTETHER_HEADER_SIZE
            internalEthernetMTU -= CHACHA20_POLY1305_NONCE_SIZE
            internalEthernetMTU -= CHACHA20_POLY1305_TAG_SIZE
            internalEthernetMTU -= ETHERNET_HEADER_SIZE
        } else {
            // MLMVPN: was ETHERNET_MAX_MTU (1500), on the reasoning that "TCP connection will
            // optimize". It does not. The tunnel is Ethernet inside SoftEther inside TLS
            // inside TCP, so a 1500-byte inner packet cannot fit the 1500-byte outer path and
            // every one of them is fragmented — classic TCP-in-TCP, and measured at ~67 kbps.
            //
            //   1500 outer
            //   -  20 IPv4
            //   -  20 TCP
            //   -  57 TLS record (5 header + 16 IV + 20 HMAC-SHA1 + up to 16 CBC padding)
            //   -  12 SoftEther frame header (8 + 4)
            //   -  14 Ethernet header
            //   = 1377, rounded down for safety
            internalEthernetMTU = TCP_TUNNEL_SAFE_MTU
        }

        maxInternalFrameSize = internalEthernetMTU + ETHERNET_HEADER_SIZE

        isLogEnabled = getBooleanPrefValue(MvcPreference.LOG_DO_SAVE_LOG, prefs)
        logDirectory = loadUri(MvcPreference.LOG_DIRECTORY, prefs)
    }

    private fun loadUri(key: MvcPreference, prefs: SharedPreferences): Uri? {
        val uriString = getStringPrefValue(key, prefs)

        return if (uriString.isEmpty()) {
            null
        } else {
            uriString.toUri()
        }
    }

    private fun loadMac(prefs: SharedPreferences): ByteArray {
        var addressString = getStringPrefValue(MvcPreference.ETHERNET_MAC_ADDRESS, prefs)

        if (addressString.isEmpty()) {
            addressString = "5E" + random.nextBytes(5).toHexString()
            setStringPrefValue(addressString, MvcPreference.ETHERNET_MAC_ADDRESS, prefs)
        }

        return addressString.toHexByteArray()
    }

    internal fun attachTCPTerminal() {
        tcpTerminal = TCPTerminal(this)
    }

    internal fun attachUDPTerminal() {
        udpTerminal = UDPTerminal(this)
    }

    internal fun attachIPTerminal() {
        ipTerminal = IPTerminal(this)
    }
}