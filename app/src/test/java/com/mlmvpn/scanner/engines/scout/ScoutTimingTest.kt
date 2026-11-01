package com.mlmvpn.scanner.engines.scout

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScoutTimingTest {
    @Test fun `speed waits for diagnostics and heartbeat cleanup`() = runBlocking {
        val events = mutableListOf<String>()
        val diagnostics = launch { delay(30); events += "diagnostics done" }
        val heartbeat = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { events += "heartbeat stopped" }
        }
        ScoutTiming.exclusiveSpeed(diagnostics, heartbeat) {
            assertTrue(diagnostics.isCompleted)
            assertTrue(heartbeat.isCompleted)
            events += "speed"
        }
        assertEquals(listOf("diagnostics done", "heartbeat stopped", "speed"), events)
    }
}
