package kittoku.mvc.io.outgoing

import java.nio.ByteBuffer


internal fun OutgoingManager.addOutGoingPacket(buffer: ByteBuffer) {
    mainBuffer.putInt(buffer.remaining())
    mainBuffer.put(buffer)
}

internal suspend fun OutgoingManager.sendOutgoingPacket(frameNum: Int) {
    mainBuffer.putInt(0, frameNum)
    mainBuffer.flip()
    bridge.tcpTerminal!!.sendStream(mainBuffer)
}