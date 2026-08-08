package kittoku.mvc.io.outgoing

import androidx.preference.PreferenceManager
import kittoku.mvc.SharedBridge
import kittoku.mvc.extension.move
import kittoku.mvc.preference.MvcPreference
import kittoku.mvc.preference.accessor.setStringPrefValue
import kittoku.mvc.teminal.TCP_SOFTETHER_HEADER_SIZE
import kittoku.mvc.teminal.UDPStatus
import kittoku.mvc.unit.ETHERNET_HEADER_SIZE
import kittoku.mvc.unit.ETHERNET_MAX_MTU
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.max


/** How far the tun reader may run ahead of the sender, in packets. */
private const val BUFFER_POOL_SIZE = 32

internal class OutgoingManager(internal val bridge: SharedBridge) {
    private val bufferSize = max(bridge.socket.session.applicationBufferSize, TCP_SOFTETHER_HEADER_SIZE + ETHERNET_MAX_MTU)
    internal val mainBuffer = ByteBuffer.allocate(bufferSize)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(bridge.service)

    private var jobMain: Job? = null
    private var jobRetrieve: Job? = null

    // MLMVPN: a bounded buffer pool instead of a rendezvous hand-off.
    //
    // The original used Channel(0) with two alternating ByteBuffers, so the reader could never
    // run ahead of the sender. jobMain's batching loop (`tryReceive` until the frame is full)
    // therefore almost never found a second packet waiting and sent nearly every packet in its
    // own TLS record and its own write syscall.
    //
    // Buffers are checked out of `freeBuffers`, filled, queued, and returned once their
    // contents have been copied into mainBuffer — so a buffer can never be refilled while the
    // sender is still reading it, which is what made the two-buffer rendezvous necessary.
    private val retrieveChannel = Channel<ByteBuffer>(BUFFER_POOL_SIZE)
    private val freeBuffers = Channel<ByteBuffer>(BUFFER_POOL_SIZE)

    internal fun launchJobMain() {
        jobMain = bridge.scope.launch(bridge.handler) {
            val minCapacity = TCP_SOFTETHER_HEADER_SIZE + ETHERNET_MAX_MTU

            var lastUDPStatus = UDPStatus.CLOSED

            while (isActive) {
                val firstPacket = retrieveChannel.receive()

                // send through UDP hole if possible
                if (bridge.udpTerminal != null) {
                    val currentUDPStatus = bridge.udpAccelerationConfig!!.status

                    if (currentUDPStatus != lastUDPStatus) {
                        setStringPrefValue(currentUDPStatus.name, MvcPreference.UDP_STATUS, prefs)
                        lastUDPStatus = currentUDPStatus
                    }

                    if (currentUDPStatus == UDPStatus.OPEN) {
                        bridge.udpTerminal!!.sendData(firstPacket)
                        continue
                    }
                }

                // finally TCP connection is needed
                mainBuffer.clear()
                mainBuffer.move(Int.SIZE_BYTES)
                addOutGoingPacket(firstPacket)
                freeBuffers.send(firstPacket)
                var frameNum = 1

                while (mainBuffer.remaining() >= minCapacity) {
                    val polled = retrieveChannel.tryReceive().getOrNull() ?: break
                    addOutGoingPacket(polled)
                    freeBuffers.send(polled)
                    frameNum += 1
                }

                sendOutgoingPacket(frameNum)
            }
        }
    }

    internal fun launchJobRetrieve() {
        jobRetrieve = bridge.scope.launch(bridge.handler) {
            val bufferSize = bridge.internalEthernetMTU + ETHERNET_HEADER_SIZE
            repeat(BUFFER_POOL_SIZE) { freeBuffers.send(ByteBuffer.allocate(bufferSize)) }

            while (isActive) {
                val buffer = freeBuffers.receive()
                bridge.ipTerminal!!.retrievePacket(buffer)
                retrieveChannel.send(buffer)
            }
        }
    }

    internal fun cancel() {
        jobMain?.cancel()
        jobRetrieve?.cancel()
    }
}