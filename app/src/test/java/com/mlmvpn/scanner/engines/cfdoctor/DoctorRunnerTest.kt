package com.mlmvpn.scanner.engines.cfdoctor

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DoctorRunnerTest {
    @Test fun cancellationKeepsCompletedEvidenceAndStopsScheduling() = runBlocking {
        val runner=DoctorRunner(DoctorPlan(maxMs=30000), object: DoctorProbe {
            override suspend fun probe(spec: ProbeSpec,timeout: Int): Evidence { delay(10); return Evidence(spec.id,spec.route,"ok",200) }
            override suspend fun dns(id: String,resolver: String,mode: String,type: Int,sni: String,timeout: Int): Evidence { delay(10000); return Evidence(id,resolver,"ok") }
            override suspend fun quic(ip: String,timeout: Int)=Evidence("Q1",ip,"ok")
        })
        val job=launch { runner.run(false) }
        delay(150); job.cancelAndJoin()
        assertFalse(runner.state.value.running)
        assertFalse(runner.state.value.complete)
        assertTrue(runner.state.value.rows.isNotEmpty())
        assertTrue(runner.state.value.decisions.any { it.contains("cancelled") })
    }
    @Test fun noConnectivityTerminatesWithEvidenceInsteadOfCloudflareBlame() = runBlocking {
        val runner=DoctorRunner(DoctorPlan(),object: DoctorProbe {
            override suspend fun probe(spec: ProbeSpec,timeout: Int)=Evidence(spec.id,spec.route,"tcp_timeout")
            override suspend fun dns(id: String,resolver: String,mode: String,type: Int,sni: String,timeout: Int)=Evidence(id,resolver,"dns_timeout")
            override suspend fun quic(ip: String,timeout: Int)=Evidence("Q1",ip,"udp_timeout")
        })
        runner.run(false)
        assertTrue(runner.state.value.flags.contains("NO_CONNECTIVITY"))
        assertFalse(runner.state.value.rows.any { it.id=="F1" })
        assertTrue(runner.state.value.decisions.any { it.contains("early_stop") })
    }
}
