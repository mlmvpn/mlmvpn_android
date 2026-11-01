package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.probe.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProbeWorkTest {
    @Test fun `slow region does not discard completed regions`() = runBlocking {
        val result = collectWithin(listOf("fast", "slow"), 150) { region ->
            if (region == "slow") delay(5_000)
            region
        }
        assertEquals(listOf("fast"), result)
    }

    @Test fun `parent cancellation is not converted into partial success`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val task = launch {
            collectWithin(listOf(1), 5_000) { started.complete(Unit); delay(10_000); it }
            returned = true
        }
        started.await()
        task.cancelAndJoin()
        assertFalse(returned)
    }

    @Test fun `lookup timeout does not wait for an uninterruptible resolver`() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val pool = BoundedProbeWork(1)
        try {
            val task = async(Dispatchers.Default) {
                withTimeoutOrNull(250) {
                    pool.run {
                        started.countDown()
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
                        7
                    }
                }
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val value = withTimeoutOrNull(1500) { task.await(); "finished" }
            assertEquals("finished", value)
            assertNull(task.await())
            // A wedged lookup cannot create an unbounded queue or another worker.
            assertNull(pool.run { 8 })
        } finally { release.countDown(); pool.close() }
    }

    @Test fun `one deadline supplies the remaining budget to every stage`() {
        var now = 100L
        val budget = ProbeBudget(1000) { now }
        now = 750
        assertEquals(350L, budget.cap(900))
        assertFalse(budget.allows(351))
        now = 1200
        assertEquals(0L, budget.left())
    }
}
