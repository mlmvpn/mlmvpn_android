package kittoku.mvc.io.incoming

import kittoku.mvc.debug.assertAlways
import kittoku.mvc.extension.indexOf
import kittoku.mvc.extension.move
import kittoku.mvc.extension.probeInt
import kittoku.mvc.teminal.TCP_KEEP_ALIVE_INTERVAL_DIFF
import kittoku.mvc.teminal.TCP_KEEP_ALIVE_MIN_INTERVAL
import kittoku.mvc.teminal.UDPStatus
import kittoku.mvc.teminal.UDP_KEEP_ALIVE_INTERVAL_DIFF
import kittoku.mvc.teminal.UDP_KEEP_ALIVE_MIN_INTERVAL
import kittoku.mvc.teminal.UDP_NATT_INTERVAL_DIFF
import kittoku.mvc.teminal.UDP_NATT_INTERVAL_INITIAL
import kittoku.mvc.teminal.UDP_NATT_INTERVAL_MIN
import kittoku.mvc.teminal.UDP_NATT_PORT
import kittoku.mvc.unit.ETHERNET_HEADER_SIZE
import kittoku.mvc.unit.HttpMessage
import kittoku.mvc.unit.KeepAlivePacket
import kotlinx.coroutines.yield
import java.net.DatagramPacket
import java.nio.ByteBuffer
import kotlin.math.min


private const val HTTP_SUFFIX = "\r\n\r\n"
private val HTTP_SUFFIX_BYTES = HTTP_SUFFIX.toByteArray(Charsets.UTF_8)


internal suspend fun IncomingManager.trySendTCPKeepAlive() {
    if (System.currentTimeMillis() > nextTCPKeepAlive) {
        val packet = KeepAlivePacket().also {
            it.nattAddress = bridge.udpAccelerationConfig?.clientNATTAddress
            it.nattPort = bridge.udpAccelerationConfig?.clientNATTPort ?: 0
            it.preparePacket(bridge.random)
        }

        val buffer = ByteBuffer.allocate(packet.length)
        buffer.clear()
        packet.write(buffer)
        buffer.flip()
        bridge.tcpTerminal!!.sendStream(buffer)

        nextTCPKeepAlive = System.currentTimeMillis() + TCP_KEEP_ALIVE_MIN_INTERVAL + bridge.random.nextInt(TCP_KEEP_ALIVE_INTERVAL_DIFF)
    }
}

internal suspend fun IncomingManager.trySendUDPKeepAlive() {
    if (System.currentTimeMillis() > nextUDPKeepAlive) {
        bridge.udpTerminal!!.sendData(ByteBuffer.allocate(0))
        kittoku.mvc.debug.Telemetry.udpKeepAlivesSent.incrementAndGet()

        nextUDPKeepAlive = System.currentTimeMillis() + UDP_KEEP_ALIVE_MIN_INTERVAL + bridge.random.nextInt(UDP_KEEP_ALIVE_INTERVAL_DIFF)
    }
}

internal suspend fun IncomingManager.trySendUDPInquireNATT() {
    // The rendezvous host may never have resolved (see initializeNATTAddress). That only costs
    // NAT-T assistance, so skip the inquiry and leave the direct UDP path running.
    val natt = bridge.udpAccelerationConfig!!.nattAddress ?: return

    if (System.currentTimeMillis() > nextUDPInquireNATT) {
        DatagramPacket(
            "B".toByteArray(Charsets.US_ASCII),
            1, natt, UDP_NATT_PORT
        ).also {
            bridge.udpTerminal!!.sendPacket(it)
        }


        val interval = if (bridge.udpAccelerationConfig!!.status == UDPStatus.OPEN) {
            UDP_NATT_INTERVAL_MIN + bridge.random.nextInt(UDP_NATT_INTERVAL_DIFF)
        } else {
            UDP_NATT_INTERVAL_INITIAL
        }

        nextUDPInquireNATT = System.currentTimeMillis() + interval
    }
}

internal suspend fun IncomingManager.processDataPacket(frameSize: Int, buffer: ByteBuffer) {
    bridge.ipTerminal?.also {
        val start = buffer.position() + ETHERNET_HEADER_SIZE
        val packetSize = frameSize - ETHERNET_HEADER_SIZE

        it.writePacket(start, packetSize, buffer.array())
    }

    buffer.move(frameSize)
}

internal suspend fun IncomingManager.processKeepAlivePacket(buffer: ByteBuffer) {
    bridge.tcpTerminal!!.ensureBytes(Int.SIZE_BYTES * 2, buffer)
    val frameSize = buffer.probeInt(Int.SIZE_BYTES)
    bridge.tcpTerminal!!.ensureBytes(frameSize + Int.SIZE_BYTES * 2, buffer)

    val packet = KeepAlivePacket().also {
        it.read(buffer)
    }

    bridge.udpAccelerationConfig?.also {
        packet.extractNATTInfo()

        if (packet.nattPort != 0) {
            it.serverNATTPort = packet.nattPort
        }

        packet.nattAddress?.also { address ->
            it.serverNATTAddress = address
        }
    }
}

internal suspend fun IncomingManager.relayHttpMessage(buffer: ByteBuffer) {
    var header = ""

    // find header
    while (true) {
        yield()
        bridge.tcpTerminal!!.ensureBytes(HTTP_SUFFIX_BYTES.size, buffer)

        val startSuffix = buffer.indexOf(HTTP_SUFFIX_BYTES)
        if (startSuffix != null) {
            header += String(buffer.array(), buffer.position(), startSuffix - buffer.position(), Charsets.UTF_8)
            buffer.position(startSuffix + HTTP_SUFFIX_BYTES.size)
            break
        }

        header += String(buffer.array(), buffer.position(), buffer.remaining() - 3, Charsets.UTF_8)
        buffer.position(buffer.limit() - 3)
        // `-3` for HTTP_SUFFIX_BYTES being fragmented
    }


    val message = HttpMessage()
    header.split("\r\n").forEachIndexed { i, line ->
        if (i == 0){
            message.startLine = line
        } else {
            val delimiterIndex = line.indexOf(": ")
            assertAlways(delimiterIndex > 0)

            message.fields[line.take(delimiterIndex)] = line.substring(delimiterIndex + 2)
        }
    }


    message.fields["Content-Length"]?.also {
        val body = ByteArray(it.toInt())
        var writeStart = 0

        while (true) {
            val writeSize = min(body.size - writeStart, buffer.remaining())
            buffer.get(body, writeStart, writeSize)
            writeStart += writeSize

            // MLMVPN: compare what has been written IN TOTAL, not the size of the chunk that
            // just arrived. With the original `writeSize == body.size` the loop only ever
            // terminated when the whole body turned up in a single read; a split body left it
            // spinning with writeSize=0, the buffer desynchronised, and the next frame header
            // was read out of a bogus offset — surfacing as
            // "IllegalArgumentException: Bad position 3796/3786" inside ByteBuffer.move().
            // VPN Gate's watermark exchange is ~4 KB, so it splits routinely.
            if (writeStart == body.size) {
                break
            } else {
                bridge.tcpTerminal!!.ensureBytes(1, buffer)
            }
        }

        message.body = body
    }

    softEtherMailbox?.send(message)
}