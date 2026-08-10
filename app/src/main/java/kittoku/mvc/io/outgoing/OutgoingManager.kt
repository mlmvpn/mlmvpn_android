package kittoku.mvc.io.outgoing

import androidx.preference.PreferenceManager
import kittoku.mvc.SharedBridge
import kittoku.mvc.debug.Telemetry
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
import java.util.concurrent.atomic.AtomicInteger
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

    // Channel has no size query, and the pool running dry is the single most useful thing to
    // see in a log when the tunnel stalls — so the count is tracked alongside it. Every path
    // that returns a buffer goes through recycle(), which is also what makes a missing return
    // visible instead of silent.
    private val freeCount = AtomicInteger(0)

    private suspend fun recycle(buffer: ByteBuffer) {
        freeBuffers.send(buffer)
        Telemetry.poolFree = freeCount.incrementAndGet()
    }

    private suspend fun takeFree(): ByteBuffer {
        val b = freeBuffers.receive()
        Telemetry.poolFree = freeCount.decrementAndGet()
        return b
    }

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
                        val size = firstPacket.remaining()
                        bridge.udpTerminal!!.sendData(firstPacket)
                        // THE buffer MUST go back to the pool here.
                        //
                        // Every other path returns it; this one used to `continue` straight
                        // past. jobRetrieve takes a buffer out of `freeBuffers` before each
                        // read from the tun, so with a 32-buffer pool the 33rd outgoing packet
                        // found the pool empty and blocked there forever. The result was a VPN
                        // that negotiated UDP, reported OPEN, kept answering keep-alives (they
                        // allocate their own buffer and never touch the pool) and moved no user
                        // traffic whatsoever — "UDP is on, nothing works; turn it off and it
                        // works". Turning UDP off avoided it because the TCP branch below
                        // always returned its buffers.
                        //
                        // sendData() has already copied the bytes into its own encrypt buffer
                        // by the time it returns, so recycling here cannot corrupt the frame
                        // in flight.
                        recycle(firstPacket)
                        Telemetry.udpTxBytes.addAndGet(size.toLong())
                        Telemetry.udpTxPackets.incrementAndGet()
                        continue
                    }
                }

                // finally TCP connection is needed
                mainBuffer.clear()
                mainBuffer.move(Int.SIZE_BYTES)
                var payload = firstPacket.remaining().toLong()
                addOutGoingPacket(firstPacket)
                recycle(firstPacket)
                var frameNum = 1

                while (mainBuffer.remaining() >= minCapacity) {
                    val polled = retrieveChannel.tryReceive().getOrNull() ?: break
                    payload += polled.remaining().toLong()
                    addOutGoingPacket(polled)
                    recycle(polled)
                    frameNum += 1
                }

                sendOutgoingPacket(frameNum)
                Telemetry.tcpTxBytes.addAndGet(payload)
                Telemetry.tcpTxFrames.addAndGet(frameNum.toLong())
            }
        }
    }

    internal fun launchJobRetrieve() {
        jobRetrieve = bridge.scope.launch(bridge.handler) {
            val bufferSize = bridge.internalEthernetMTU + ETHERNET_HEADER_SIZE
            repeat(BUFFER_POOL_SIZE) { recycle(ByteBuffer.allocate(bufferSize)) }

            while (isActive) {
                val buffer = takeFree()
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