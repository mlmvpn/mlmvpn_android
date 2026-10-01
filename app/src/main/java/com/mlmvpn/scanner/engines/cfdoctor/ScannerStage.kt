package com.mlmvpn.scanner.engines.cfdoctor

import android.content.Context
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * ST4: why a clean-IP scan finds nothing.
 *
 * The scanner keeps an address only when it passes three gates in a row: a TCP connect, the
 * config's own first request (TLS with the config's name, then the WebSocket upgrade), and a real
 * request through the core with that address in place of the config's. A scan that finds nothing
 * failed one of these everywhere -- and which one is the whole answer:
 *  - nothing answers TCP: Cloudflare's addresses are blocked on this line (or the scan ran through
 *    another VPN);
 *  - TCP yes, TLS with the config's name no: the name (usually the workers.dev host) is filtered,
 *    and no clean IP can fix that -- fragmenting or another name can;
 *  - TLS yes, the WebSocket upgrade no: the worker answers with an error (quota 1101/1015, wrong path);
 *  - upgrade yes, the proxy no: the config itself is dead (UUID, password, deleted worker);
 *  - all yes on some addresses: the scan can work; its settings (budget, ports) are the problem.
 *
 * This runs the same gates on a fixed sample, so the answer does not depend on luck.
 */
class ScannerStage(private val context: Context, private val env: NetEnv, private val budget: DataBudget) {

    suspend fun run(config: VpnConfig?, sample: List<String>, runner: DoctorRunner) {
        if (config == null) {
            runner.record(Evidence("ST4", "scanner", "scan.config.none", numbers = mapOf("terminal" to 1L)))
            return
        }
        val shape = ConfigProbes.shape(config)
        if (!ConfigProbes.isCloudflare(config) && !hostedOnCdn(config)) {
            // A clean Cloudflare address cannot stand in for a server that is not behind Cloudflare.
            runner.record(Evidence("ST4", shape, "scan.config.not_cdn", numbers = mapOf("terminal" to 1L)))
            return
        }
        val raw = ConfigProbes.rawConfig(config)
        val ips = sample.filter(CfBogon::isCloudflare).take(SAMPLE)
        val gate = Semaphore(6)
        val tunnel = TunnelStageClient(env, budget)
        // Gates 1 and 2 on every sampled address.
        val results = coroutineScope {
            ips.map { ip ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val row = if (raw != null) safe { tunnel.test(raw.copy(address = ip), timeout = 12_000) }
                        else safe { NetProbes(env, budget).run(ProbeSpec("ST4", config.sni.ifBlank { config.wsHost.ifBlank { config.address } }, ip = ip, port = config.port, kind = "tls", route = "tls", privateEndpoint = true), 6_000) }
                        ip to (row ?: Evidence("ST4", "probe", "internal_error"))
                    }
                }
            }.awaitAll()
        }
        results.forEach { (_, r) -> runner.record(r.copy(id = "W3", route = "scan:$shape", numbers = r.numbers - "terminal")) }

        val tcp = results.count { (_, r) -> r.code !in setOf("tcp", "dns") && !r.code.startsWith("tcp_") }
        val tls = results.count { (_, r) -> r.numbers["tls"] == 1L || r.ok }
        val ws = results.count { (_, r) -> r.code in setOf("proxy.handshake", "proxy_or_egress", "egress", "app.http", "ok") || r.numbers["proxy_ack"] == 1L }
        val proxy = results.count { (_, r) -> r.numbers["proxy_ack"] == 1L || r.ok }
        val full = results.filter { (_, r) -> r.ok }.map { it.first }

        // Gate 3, the core, on a few addresses that came through gate 2 (or the first few, when
        // the raw client cannot speak this config).
        val forCore = (if (raw != null) results.filter { (_, r) -> r.numbers["tls"] == 1L }.map { it.first } else results.map { it.first }).take(CORE_CHECKS)
        var coreOk = 0
        val native = NativeProbe(context)
        for (ip in forCore) {
            val c = config.copy()
            if (c.sni.isBlank()) c.sni = c.wsHost.ifBlank { c.address }
            if (c.wsHost.isBlank()) c.wsHost = c.address
            c.address = ip
            val r = safe { native.measure(XrayJsonGenerator.generateSpeedtestConfig(c), 10_000) }
            runner.record(Evidence("W4", "scan_core:$shape", if (r?.ms != null) "ok" else "native.${r?.code ?: "internal_error"}",
                numbers = mapOf("delay" to (r?.ms ?: 0L))))
            if (r?.ms != null) coreOk++
        }
        native.release()

        val layer = when {
            ips.isEmpty() -> "scan.sample.none"
            tcp == 0 -> "scan.tcp"
            raw == null && coreOk == 0 && forCore.isNotEmpty() -> "scan.core"
            raw != null && tls == 0 -> "scan.tls"
            raw != null && ws == 0 -> results.firstNotNullOfOrNull { (_, r) -> r.code.takeIf { it.startsWith("ws(") } }?.let { "scan.$it" } ?: "scan.ws"
            raw != null && proxy == 0 -> "scan.proxy"
            raw != null && full.isEmpty() -> "scan.egress"
            coreOk == 0 && forCore.isNotEmpty() -> "scan.core"
            else -> "ok"
        }
        runner.record(Evidence("ST4", shape, layer, numbers = mapOf(
            "terminal" to 1L, "sample" to ips.size.toLong(), "tcp" to tcp.toLong(), "tls" to tls.toLong(),
            "ws" to ws.toLong(), "proxy" to proxy.toLong(), "full" to full.size.toLong(), "core_ok" to coreOk.toLong(),
            "limited" to if (raw == null) 1L else 0L,
        )))
    }

    /** A config whose server is a name (not a Cloudflare address) may still be fronted by Cloudflare. */
    private fun hostedOnCdn(c: VpnConfig): Boolean =
        runCatching { env.resolve(c.address).any { CfBogon.isCloudflare(it.hostAddress.orEmpty()) } }.getOrDefault(false)

    private suspend fun <T> safe(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    companion object {
        const val SAMPLE = 24
        const val CORE_CHECKS = 4
    }
}
