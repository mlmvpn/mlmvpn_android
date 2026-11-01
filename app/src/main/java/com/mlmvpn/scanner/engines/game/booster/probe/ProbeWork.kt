package com.mlmvpn.scanner.engines.game.booster.probe

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Monotonic budget shared by preparation, measurement and route activation. */
class ProbeBudget(totalMs: Long, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val end = now() + totalMs
    fun left(): Long = (end - now()).coerceAtLeast(0)
    fun cap(ms: Long): Long = minOf(ms, left())
    fun allows(ms: Long): Boolean = left() >= ms
}

/** Collect completed probes without making a silent endpoint erase useful results. */
suspend fun <T, R : Any> collectWithin(items: List<T>, timeoutMs: Long, probe: suspend (T) -> R): List<R> =
    coroutineScope {
        val completed = ConcurrentHashMap<Int, R>()
        withTimeoutOrNull(timeoutMs) {
            items.mapIndexed { index, item -> launch { completed[index] = probe(item) } }.joinAll()
        }
        items.indices.mapNotNull { completed[it] }
    }

/** Bounded escape hatch for legacy DNS APIs that cannot be interrupted. */
class BoundedProbeWork(maxWorkers: Int) : AutoCloseable {
    private val executor = ThreadPoolExecutor(0, maxWorkers, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "gb-dns").apply { isDaemon = true } })

    suspend fun <T> run(block: () -> T): T? = suspendCancellableCoroutine { continuation ->
        val task = FutureTask {
            val result = try { block() } catch (_: Exception) { null }
            continuation.resume(result)
        }
        continuation.invokeOnCancellation { task.cancel(true) }
        try { executor.execute(task) }
        catch (_: RejectedExecutionException) { continuation.resume(null) }
    }

    override fun close() { executor.shutdownNow() }
}
