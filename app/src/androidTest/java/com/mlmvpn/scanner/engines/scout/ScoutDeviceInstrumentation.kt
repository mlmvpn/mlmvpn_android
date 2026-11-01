package com.mlmvpn.scanner.engines.scout

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Opt-in device probe runner; no JUnit/AndroidX runner dependency and no production entry point. */
class ScoutDeviceInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val report = JSONObject().put("started", System.currentTimeMillis())
        val results = JSONArray()
        val output = File(targetContext.filesDir, "scout/device-smoke.json")
        fun save() {
            output.parentFile?.mkdirs()
            output.writeText(report.put("results", results).toString())
        }
        try {
            runBlocking {
                ScoutEngine.init(targetContext)
                check(!ScoutEngine.isRunning() && !ScoutVpn.anyVpn(targetContext) && !ScoutVpn.ourVpnUp()) { "Stop Scout/VPN before device probes" }
                val runRoot = File(targetContext.filesDir, "scout/tunnel/runs")
                val before = runRoot.list().orEmpty().toSet()
                report.put("nativeRunDirsBefore", JSONArray(before.toList()))
                check(before.isEmpty() && !nativeChildAlive() && ownedAetherPids().isEmpty()) { "Device probes require a clean baseline" }
                val snapshot = ScoutLine(targetContext) { }.snapshot().second
                report.put("ipv4", snapshot.hasV4).put("ipv6", snapshot.hasV6)
                val methods = arguments.getString("methods", "masque,wireguard,warp_on_warp").split(',').toSet()
                val filter = Regex(arguments.getString("variants", "^(h2_v6|v6)$"))
                val cancelMs = arguments.getString("cancelMs")?.toLongOrNull()?.takeIf { it > 0 }
                val cancelAfterNativeStart = arguments.getString("cancelStage") == "native"
                for (method in methods) {
                    val adapter = checkNotNull(ScoutRegistry.byId(method)) { "Unknown method" }
                    val catalog = if (arguments.getString("discover") == "true" && adapter.discoversVariants)
                        adapter.discover(ScoutKit(targetContext, snapshot) { step, _ -> Log.i("ScoutDevice", "$method catalog ${step.name}") })
                    else adapter.variants(targetContext, snapshot)
                    val variants = catalog.filter { filter.matches(it.id) }
                    check(variants.isNotEmpty()) { "No matching variants for $method" }
                    for (variant in variants) {
                        val row = JSONObject().put("method", method).put("variant", variant.id).put("label", variant.label)
                        val childStarted = CompletableDeferred<Unit>()
                        var phase = Step.PREPARING
                        val kit = ScoutKit(targetContext, snapshot) { step, _ ->
                            phase = step
                            if (nativeChildAlive()) {
                                row.put("nativeStartObserved", true)
                                row.put("nativeRunTokens", JSONArray(runRoot.list().orEmpty().filterNot { it in before }))
                                childStarted.complete(Unit)
                            }
                            Log.i("ScoutDevice", "$method ${step.name}")
                        }
                        results.put(row); save()
                        if (variant.notApplicable != null || variant.needsSetup != null) {
                            row.put("status", "UNAVAILABLE"); save(); continue
                        }
                        val started = android.os.SystemClock.elapsedRealtime()
                        val probe: suspend () -> Unit = {
                            when (val ready = adapter.prepare(kit, variant)) {
                                is Prepared.Failed -> row.put("status", ready.status.name).put("reason", ready.reason)
                                is Prepared.Done -> row.put("status", ready.result.status.name).putOpt("reason", ready.result.reason)
                                    .putOpt("httpsMs", ready.result.numbers.latencyMs)
                                is Prepared.Ready -> when (val served = adapter.serve(kit, ready) { port ->
                                    val connected = android.os.SystemClock.elapsedRealtime()
                                    val hit = runInterruptible(Dispatchers.IO) { ScoutNet.https204(port, null, ScoutNet.VERIFY_HOST, 10_000) }
                                    val filtered = runInterruptible(Dispatchers.IO) { ScoutNet.https204(port, null, ScoutNet.FILTERED_HOST, 10_000) }
                                    val country = runInterruptible(Dispatchers.IO) { ScoutNet.exitCountry(port, 5_000) }
                                    val speed = withTimeoutOrNull(20_000) {
                                        runInterruptible(Dispatchers.IO) { ScoutNet.download(port, null, ScoutNet.SPEED_URL, 1_048_576, 8_000) }
                                    }
                                    row.put("data", hit != null).put("filtered", filtered != null).putOpt("country", country)
                                        .put("startupMs", connected - started).putOpt("httpsMs", hit?.ms)
                                        .putOpt("mbps", speed?.mbps).putOpt("downloadBytes", speed?.bytes)
                                    hit != null
                                }) {
                                    is Served.Ok -> row.put("status", if (served.value) "WORKS" else "FAILED_DATA")
                                    is Served.Failed -> row.put("status", if (served.inconclusive) "INCONCLUSIVE" else "FAILED").put("reason", served.reason)
                                }
                            }
                        }
                        try {
                            if (cancelMs != null && cancelAfterNativeStart) {
                                var cancelled = false
                                val completed = withTimeoutOrNull(variant.budgetMs) {
                                    coroutineScope {
                                        val probing = launch { probe() }
                                        val cancelling = launch {
                                            childStarted.await(); delay(cancelMs)
                                            if (probing.isActive) {
                                                cancelled = true
                                                row.put("cancellationPhase", phase.name)
                                                probing.cancel()
                                            }
                                        }
                                        try { probing.join() } finally { cancelling.cancelAndJoin() }
                                    }
                                    true
                                } == true
                                if (cancelled) row.put("status", "CANCELLED")
                                else if (!completed) row.put("status", "TIMED_OUT")
                            } else {
                                val completed = withTimeoutOrNull(cancelMs ?: variant.budgetMs) { probe(); true } == true
                                if (!completed) row.put("status", if (cancelMs != null) "CANCELLED" else "TIMED_OUT")
                                if (!completed && cancelMs != null) row.put("cancellationPhase", phase.name)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { row.put("status", "TEST_ERROR").put("exception", e.javaClass.simpleName) }
                        delay(1_000)
                        val nativeAlive = nativeChildAlive()
                        val runDir = File(targetContext.filesDir, "scout/tunnel/runs")
                        val registerDirs = File(targetContext.filesDir, "scout/tunnel").listFiles().orEmpty()
                            .count { it.isDirectory && it.name.startsWith("register-") }
                        val helpers = ownedAetherPids()
                        row.put("nativeChildAliveAfter", nativeAlive).put("nativeRunDirsAfter", runDir.listFiles()?.size ?: 0)
                            .put("registerDirsAfter", registerDirs).put("aetherChildrenAfter", JSONArray(helpers))
                            .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                        save()
                        check(!nativeAlive && (runDir.listFiles()?.isNotEmpty() != true) && registerDirs == 0 && helpers.isEmpty()) { "Native cleanup incomplete" }
                    }
                }
            }
            report.put("completed", true).put("finished", System.currentTimeMillis()); save()
            finish(Activity.RESULT_OK, Bundle().apply { putString("report", "scout/device-smoke.json") })
        } catch (e: Exception) {
            report.put("completed", false).put("exception", e.javaClass.simpleName); save()
            Log.e("ScoutDevice", "Device probe failed", e)
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("failure", e.javaClass.simpleName) })
        }
    }

    private fun nativeChildAlive(): Boolean =
        (targetContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager)
            .runningAppProcesses.orEmpty().any { process ->
                process.uid == android.os.Process.myUid() && process.processName == "${targetContext.packageName}:scouttunnel" &&
                    runCatching { android.system.Os.kill(process.pid, 0); true }.getOrDefault(false)
            }

    private fun ownedAetherPids(): List<Int> {
        val executables = setOf(
            File(targetContext.applicationInfo.nativeLibraryDir, "libaether.so").absolutePath,
            com.mlmvpn.scanner.store.StoreEngines.command(targetContext, "aether", "libaether.so").last())
        return File("/proc").listFiles().orEmpty().mapNotNull { directory ->
        val pid = directory.name.toIntOrNull() ?: return@mapNotNull null
        runCatching {
            val uid = Regex("(?m)^Uid:\\s+(\\d+)").find(File(directory, "status").readText())?.groupValues?.get(1)?.toIntOrNull()
            val command = File(directory, "cmdline").readText().split('\u0000').take(2)
            pid.takeIf { uid == android.os.Process.myUid() && command.any { it in executables } }
        }.getOrNull()
        }
    }
}
