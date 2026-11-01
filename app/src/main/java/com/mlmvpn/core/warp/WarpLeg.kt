package com.mlmvpn.core.warp

import com.mlmvpn.scanner.utils.alive
import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mlmvpn.core.aether.AetherOptions
import com.mlmvpn.core.aether.AetherProtocol
import com.mlmvpn.scanner.store.StoreEngines
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloudflare WARP on a loopback SOCKS port, for something else to ride: the same Aether core «وارپ»
 * runs (its endpoint scan and its obfuscation), with the measurements' own identity
 * ([Nat64Probe.identityToml]) so it never takes «وارپ»'s session, in a data directory of its own.
 *
 * Why it exists: the in-core WARP carrier ([com.mlmvpn.scanner.engines.amnezia.AmzCarrier], plain
 * WireGuard inside Xray with public WARP accounts) found nothing on the user's Wi-Fi on 2026-10-17
 * -- "0 of 16 endpoints carry data" -- so Outline's whole second pass was silently skipped and 1495
 * of 1500 servers were written off. A line that filters plain WireGuard to Cloudflare is exactly the
 * line Aether's scanner and noise were built for.
 *
 * The app's own UID is outside its VPN, so this process's sockets never loop into a tunnel of ours,
 * and a tunnel's core can dial 127.0.0.1:[port] from any process.
 */
class WarpLeg(private val port: Int, private val dirName: String) {

    @Volatile private var process: Process? = null
    @Volatile private var stopping = false

    /** Called when the engine ends without [stop] (a crash, the system killing it). */
    @Volatile var onDied: (() -> Unit)? = null

    val isAlive: Boolean get() = process?.alive() == true
    val socksPort: Int get() = port

    /**
     * Starts the engine and waits until Cloudflare's trace answers `warp=on` through it, at most
     * [budgetMs]. False when it did not get there (the engine is stopped again then).
     */
    @Synchronized
    fun start(ctx: Context, budgetMs: Long = 120_000, cancelled: () -> Boolean = { false }): Boolean {
        stop()
        val app = ctx.applicationContext
        if (!CfWarpEngine.available(app)) { Log.w(TAG, "$dirName: no WARP engine on this device"); return false }
        val t0 = SystemClock.elapsedRealtime()
        val identity = runCatching { Nat64Probe.identityToml(app) }.getOrElse {
            Log.w(TAG, "$dirName: no identity (${it.message})"); return false
        }
        if (cancelled()) return false
        // The directory is kept between runs: the engine writes the endpoint that worked there
        // (aether-lastconn.toml), and the next start dials it straight away instead of scanning.
        val dir = File(app.filesDir, dirName).apply { mkdirs() }
        val idFile = File(dir, "aether.toml")
        if (!idFile.isFile || idFile.readText() != identity) {
            File(dir, "aether-lastconn.toml").delete()
            idFile.writeText(identity)
        }
        val opts = AetherOptions(
            protocol = AetherProtocol.WG,
            // Turbo: the first endpoint that carries. «وارپ»'s own «balanced» sat out its whole 80 s
            // scan deadline on the user's Wi-Fi with a good endpoint found after 3.5 s (2026-10-17),
            // which made this leg give up just before it would have come up. A carrier exists to be
            // up fast, as the desktop's ladder stand-ins are.
            scan = com.mlmvpn.core.aether.AetherScan.TURBO,
            ipFamily = CfWarpEngine.ip(app),
            noize = CfWarpEngine.noize(app),
            keepalive = CfWarpEngine.keepalive(app),
            quickReconnect = true,
            // A NAT64 exit country is the user's choice for their tunnel, not for a carrier.
            wgPeer = null,
        )
        val env = opts.toEnv(System.getenv(), dir.absolutePath, port)
        val pb = ProcessBuilder(StoreEngines.command(app, "aether", "libaether.so")).directory(dir).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)
        stopping = false
        val proc = runCatching { pb.start() }.getOrElse { Log.w(TAG, "$dirName: ${it.message}"); return false }
        process = proc
        val listening = AtomicBoolean(false)
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if ("socks5 server listening" in line) listening.set(true)
                        // The stages only, for the log: why a leg did or did not come up.
                        if (STAGE.containsMatchIn(line)) Log.i(TAG, "$dirName: ${line.substringAfter("] ").take(160)}")
                    }
                }
            }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (process === proc && !stopping) {
                process = null
                Log.w(TAG, "$dirName: engine ended on its own (code $code)")
                onDied?.invoke()
            }
        }, "warp-leg-$port").apply { isDaemon = true }.start()
        // The budget counts from the call: making the identity is part of coming up.
        while (SystemClock.elapsedRealtime() - t0 < budgetMs && proc.alive() && !cancelled()) {
            if (listening.get()) {
                val t = Ipv4Exits.httpsTrace(port, 8_000)
                if (t.ok && t.warp?.startsWith("on") == true) {
                    Log.i(TAG, "$dirName: up in ${(SystemClock.elapsedRealtime() - t0) / 1000}s (colo ${t.colo}, $port)")
                    return true
                }
            }
            Thread.sleep(500)
        }
        Log.w(TAG, "$dirName: did not come up in ${(SystemClock.elapsedRealtime() - t0) / 1000}s" +
            if (cancelled()) " (cancelled)" else if (!proc.alive()) " (engine ended)" else "")
        stop()
        return false
    }

    @Synchronized
    fun stop() {
        val p = process ?: return
        stopping = true
        process = null
        p.destroy()
        if (!runCatching { p.waitUpTo(2, TimeUnit.SECONDS) }.getOrDefault(false)) p.killHard()
    }

    companion object {
        private const val TAG = "WarpLeg"
        private val STAGE = Regex("""candidate ok|selected|validated|listening|exit:|deadline|ERROR|error|failed""")
    }
}
