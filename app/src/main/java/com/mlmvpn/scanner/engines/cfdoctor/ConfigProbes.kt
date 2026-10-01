package com.mlmvpn.scanner.engines.cfdoctor

import android.content.Context
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * ST3: do the user's configs connect, and if not, what makes them connect.
 *
 * Two engines, each for what it is good at:
 *  - the raw stage client ([TunnelStageClient]) for VLESS/Trojan over TLS WebSocket/HTTPUpgrade,
 *    which says WHERE it broke: TCP, TLS, the WebSocket upgrade, the proxy handshake, the egress,
 *    or HTTPS inside the tunnel;
 *  - the app's own Xray core ([NativeProbe], in its own process) for everything else and for the
 *    knobs only the core has: fingerprints, finalmask fragments, TLS versions, ECH, xhttp modes.
 *
 * Rows: one terminal `ST3` row per config (the verdict), `C*` rows for each variation tried.
 * Every variation that is NOT tried is written to the decision trace with why, so a short list
 * is never mistaken for a complete one.
 */
class ConfigProbes(private val context: Context, private val env: NetEnv, private val budget: DataBudget) {

    private val native = NativeProbe(context)
    private val baseline = HashMap<Int, Boolean>()

    /** The first pass: every config's baseline, raw and native. */
    suspend fun baseline(configs: List<VpnConfig>, runner: DoctorRunner) {
        if (configs.isEmpty()) { runner.decision("skip:ST3:no_config"); return }
        val tunnel = TunnelStageClient(env, budget)
        configs.take(MAX_CONFIGS).forEachIndexed { i, c ->
            currentCoroutineContext().ensureActive()
            val tag = "config${i + 1}:${shape(c)}"
            var ok = false
            val raw = rawConfig(c)
            if (raw != null) {
                // A second try only after a failure: one failure on a mobile line is noise, two
                // is a verdict -- and a config that worked needs no second opinion.
                for (attempt in 1..2) {
                    val row = safe { tunnel.test(raw, timeout = RAW_TIMEOUT_MS) } ?: Evidence("ST3", tag, "internal_error")
                    runner.record(row.copy(route = tag))
                    if (row.ok) { ok = true; break }
                }
            } else runner.decision("limited:$tag:raw_client_cannot_speak_${c.network.ifBlank { "tcp" }}")
            val n = nativeRow(c, "baseline", "C1", tag, terminal = raw == null)
            runner.record(n)
            if (n.ok) ok = true
            baseline[i] = ok
        }
        native.release()
    }

    /** The second pass: what changes would make a failing config connect (or a working one faster). */
    suspend fun variations(configs: List<VpnConfig>, runner: DoctorRunner, full: Boolean) {
        val tunnel = TunnelStageClient(env, budget)
        val anchor = runner.state.value.rows.firstOrNull { it.id == "T1" && it.ok }?.route
        configs.take(MAX_CONFIGS).forEachIndexed { i, c ->
            currentCoroutineContext().ensureActive()
            val tag = "config${i + 1}:${shape(c)}"
            val worked = baseline[i] == true
            val cf = isCloudflare(c)
            val all = buildList {
                addAll(listOf("C3.F3", "C3.F12", "C3.F15", "C7", "C8"))
                addAll(runner.plan.fingerprints.map { "C2.$it" })
                if (cf) addAll(listOf("C9.udp", "C9.google", "C9.cloudflare"))
                if (c.network == "xhttp") addAll(listOf("auto", "packet-up", "stream-up", "stream-one").map { "C10.$it" })
            }
            // A working config needs only a couple of comparisons; a failing one gets the sweep.
            val limit = when { full -> all.size; worked -> 2; i == 0 -> 16; else -> 4 }
            val chosen = if (worked && !full) listOf("C3.F12", "C2.firefox") else all.take(limit)
            (all - chosen.toSet()).forEach { runner.decision("skip:$tag:$it:${if (worked) "baseline_ok" else "variant_budget"}") }
            for (v in chosen) {
                currentCoroutineContext().ensureActive()
                if (budget.used.get() >= budget.limit) { runner.decision("skip:$tag:$v:data_budget"); continue }
                runner.record(nativeRow(c, v, v.substringBefore('.'), tag, terminal = false))
            }
            // Raw variations say which lever works at the TLS layer for this config.
            val raw = rawConfig(c)
            if (raw != null && !worked) {
                for (id in listOf("F3", "F12")) {
                    val row = safe { tunnel.test(raw, recipe = runner.plan.recipes[id] ?: SplitRecipe()) } ?: continue
                    runner.record(row.copy(id = "C3", route = "$tag:raw.$id", numbers = row.numbers - "terminal"))
                }
                if (cf && anchor != null) safe { tunnel.test(raw.copy(address = anchor)) }?.let { runner.record(it.copy(id = "C5", route = "$tag:clean_ip", numbers = it.numbers - "terminal")) }
                if (cf) safe { tunnel.test(raw.copy(port = 2053)) }?.let { runner.record(it.copy(id = "C6", route = "$tag:port2053", numbers = it.numbers - "terminal")) }
                val mixed = raw.sni.mapIndexed { n, ch -> if (n % 2 == 0) ch.uppercaseChar() else ch.lowercaseChar() }.joinToString("")
                safe { tunnel.test(raw.copy(sni = mixed)) }?.let { runner.record(it.copy(id = "C4", route = "$tag:mixed_case_sni", numbers = it.numbers - "terminal")) }
            } else if (raw != null) runner.decision("skip:$tag:raw_variants:baseline_ok")
        }
        native.release()
    }

    private suspend fun nativeRow(c: VpnConfig, variant: String, id: String, tag: String, terminal: Boolean): Evidence {
        val json = runCatching {
            val base = XrayJsonGenerator.generateSpeedtestConfig(c.copy())
            if (variant == "baseline") base else ConfigVariants.apply(base, variant)
        }.getOrElse { return Evidence(if (terminal) "ST3" else id, "$tag:$variant", "config_invalid", numbers = terminalOf(terminal)) }
        val started = env.now()
        val r = safe { native.measure(json) } ?: NativeProbe.Result(null, "internal_error")
        return Evidence(
            if (terminal) "ST3" else id, "$tag:$variant", if (r.ms != null) "ok" else "native.${r.code}",
            elapsedMs = env.now() - started,
            numbers = mapOf("delay" to (r.ms ?: 0L), "limited" to 1L) + terminalOf(terminal),
        )
    }

    private fun terminalOf(terminal: Boolean) = if (terminal) mapOf("terminal" to 1L) else emptyMap()

    /** A probe's own failure is evidence about the probe, never a reason to stop the Doctor. */
    private suspend fun <T> safe(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    companion object {
        const val MAX_CONFIGS = 3
        const val RAW_TIMEOUT_MS = 15_000

        /** VLESS/Trojan over TLS WebSocket/HTTPUpgrade: what the raw client can take apart stage by stage. */
        fun rawConfig(c: VpnConfig): TunnelConfig? {
            if (c.protocol !in setOf("vless", "trojan") || c.network !in setOf("ws", "httpupgrade") || c.tls != "tls") return null
            if (c.uuid.isBlank() || c.address.isBlank()) return null
            val sni = c.sni.ifBlank { c.wsHost.ifBlank { c.address } }
            return TunnelConfig(c.protocol, c.address, c.port, c.uuid, c.network, c.wsHost.ifBlank { sni }, c.wsPath, sni)
        }

        fun isCloudflare(c: VpnConfig): Boolean = CfBogon.isCloudflare(c.address) ||
            listOf(c.sni, c.wsHost, c.address).any { it.endsWith(".workers.dev", true) || it.endsWith(".pages.dev", true) }

        /** What a config is, without anything that identifies it: protocol, transport, port, CDN or not. */
        fun shape(c: VpnConfig): String = "${c.protocol}-${c.network.ifBlank { "tcp" }}-${c.tls.ifBlank { "none" }}-${c.port}${if (isCloudflare(c)) "-cf" else ""}"
            .replace(Regex("[^A-Za-z0-9-]"), "")

        /** At most [MAX_CONFIGS]: Cloudflare-fronted first, and one that is not as a witness. */
        fun pick(all: List<VpnConfig>): List<VpnConfig> {
            val usable = all.filter { it.address.isNotBlank() }.distinctBy { listOf(it.address, it.port, it.uuid, it.network, it.wsPath) }
            val cf = usable.filter(::isCloudflare)
            val witness = usable.firstOrNull { !isCloudflare(it) }
            return (cf.take(if (witness != null) MAX_CONFIGS - 1 else MAX_CONFIGS) + listOfNotNull(witness)).ifEmpty { usable.take(MAX_CONFIGS) }
        }
    }
}
