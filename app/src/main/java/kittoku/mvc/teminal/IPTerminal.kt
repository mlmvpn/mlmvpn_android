package kittoku.mvc.teminal

import android.os.ParcelFileDescriptor
import kittoku.mvc.SharedBridge
import kittoku.mvc.extension.move
import kittoku.mvc.extension.toInetAddress
import kittoku.mvc.unit.ETHERNET_HEADER_SIZE
import kittoku.mvc.unit.ETHER_TYPE_IPv4
import kittoku.mvc.unit.IPv4_VERSION_AND_HEADER_LENGTH
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer


internal class IPTerminal(private val bridge: SharedBridge) {
    private var fd: ParcelFileDescriptor? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val mutex = Mutex()

    internal fun initializeBuilder() {
        val builder = bridge.service.Builder()

        val prefixLength = bridge.subnetMask.let {
            val buffer = ByteBuffer.wrap(it)
            buffer.int.countOneBits()
        }

        builder.addAddress(bridge.assignedIpAddress.toInetAddress(), prefixLength)
        builder.addRoute("0.0.0.0", 0)

        bridge.dnsServerIpAddress?.also {
            builder.addDnsServer(it.toInetAddress())
        }

        builder.setBlocking(true)
        builder.setMtu(bridge.internalEthernetMTU)
        applySplitTunnel(builder)

        fd = builder.establish()
        inputStream = FileInputStream(fd!!.fileDescriptor)
        outputStream = FileOutputStream(fd!!.fileDescriptor)
    }
    
    /**
     * The app-wide split tunnel (Settings > Advanced VPN), as every other engine applies it. This
     * app stays inside, as on the AmneziaWG path: its own sockets to the server are protected, and
     * the connection check asks through the tunnel.
     */
    private fun applySplitTunnel(builder: android.net.VpnService.Builder) {
        val service = bridge.service
        val split = com.mlmvpn.core.tunnel.SplitTunnelSettings(service)
        // Smart Iran route: Iranian apps never tunnelled (out of an allow list, or excluded).
        val (apps, iranian) = com.mlmvpn.core.tunnel.SmartIranRoute.appsFor(service, split.mode(), split.packages() - service.packageName)
        com.mlmvpn.core.tunnel.SmartIranRoute.excludeIranRoutes(service, builder)
        if (apps.isEmpty() || split.mode() != com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.INCLUDE) iranian.forEach { runCatching { builder.addDisallowedApplication(it) } }
        when (split.mode()) {
            com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.INCLUDE -> {
                var added = 0
                apps.forEach { runCatching { builder.addAllowedApplication(it); added++ } }
                if (added > 0) runCatching { builder.addAllowedApplication(service.packageName) }
            }
            com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.EXCLUDE ->
                apps.forEach { runCatching { builder.addDisallowedApplication(it) } }
            com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.ALL -> Unit
        }
    }

    internal suspend fun retrievePacket(buffer: ByteBuffer) {
        while (true) {
            yield()

            buffer.clear()
            buffer.put(bridge.defaultGatewayMacAddress)
            buffer.put(bridge.clientMacAddress)
            buffer.putShort(ETHER_TYPE_IPv4)
            val readLength = inputStream!!.read(buffer.array(), buffer.position(), bridge.internalEthernetMTU)
            buffer.move(readLength)
            buffer.flip()

            if (buffer.get(ETHERNET_HEADER_SIZE) == IPv4_VERSION_AND_HEADER_LENGTH) break // send only IPv4 packet
        }
    }

    internal suspend fun writePacket(start: Int, size: Int, src: ByteArray) { // packets are discarded until VPN has been established
        mutex.withLock {
            outputStream?.write(src, start, size)
        }
    }

    internal fun close() {
        fd?.close()
    }
}