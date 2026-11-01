package com.mlmvpn.scanner.engines.scout

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

object ScoutTiming {
    /** The diagnostic trains and even the small heartbeat must be quiet during throughput. */
    suspend fun exclusiveSpeed(diagnostics: Job, heartbeat: Job, sample: suspend () -> Unit) {
        diagnostics.join()
        heartbeat.cancelAndJoin()
        sample()
    }
}
