package com.mlmvpn.core.aether

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import com.mlmvpn.scanner.utils.alive
import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo

/**
 * WARP identities registered by the bundled Aether binary: with the API's name hidden (ECH), and on
 * a line where that fails, by the ways of [Rung].
 *
 * Iran's filter drops `api.cloudflareclient.com` by its name. On an Irancell line (2026-10-07) the
 * direct call and Aether's own fragmented one both timed out, so «ماسک», «وایرگارد» and «وارپ در
 * وارپ» could not even make an identity there and stopped with "making a WARP identity is filtered".
 * Aether (2.3.0 on) looks up the ECH key of cloudflare-ech.com and registers with the server name
 * encrypted: on the same line a WireGuard pair took 4 s, and the tunnel then carried data at once.
 *
 * Its identity files are the tunnel core's own format (both cores are aether; `config.rs`,
 * `PersistedIdentity`), so they are made here and the core loads them as if it had registered
 * itself. Nothing is changed for a line where the API answers directly.
 */
object AetherRegister {
    private const val TAG = "AetherRegister"
    class CleanupIncompleteException : IllegalStateException("identity_process_cleanup_incomplete")

    /** One identity a caller needs, named by the file Aether writes it to. */
    enum class Role(val file: String, val masque: Boolean) {
        WG("tunnel.toml", false),
        WG_INNER("tunnel-secondary.toml", false),
        MASQUE("tunnel-masque.toml", true),
        MASQUE_INNER("tunnel-masque-secondary.toml", true),
    }

    /**
     * One way to reach the WARP API, tried in this order -- what made an identity on this network
     * last time first ([RegisterMemory]).
     *
     *  - ECH: Aether asks for the ECH key of cloudflare-ech.com -- Cloudflare's resolver over UDP
     *    (its default), then over HTTPS for a line that tampers with plain DNS, then Google's over TCP.
     *  - [PLAIN], no ECH: Aether 2.4.0 calls the API by its new name (api.devices.cloudflare.com),
     *    then the old one, then through two Cloudflare edges of its own; on the home line of
     *    2026-10-26, where no resolver gave the ECH key, that made a WARP-in-WARP pair in 8 s.
     *  - PattNG's two for a filter that reads the API's name: the semi-python ClientHello with the
     *    ClientHello cut into TCP segments (`--fragment`, which 2.4.0 applies to these calls too), and
     *    the same ClientHello dialled out through a local Xray that cuts it into TLS records
     *    ([AetherXrayExit], tlshello).
     */
    private enum class Rung(
        val key: String,
        val dns: String?,
        val label: String,
        val tls: AetherTls = AetherTls.CHROME,
        val fragment: Boolean = false,
        val tlsHello: Boolean = false,
    ) {
        ECH_UDP("ech-udp", "udp://1.1.1.1", "over ECH"),
        ECH_DOH("ech-doh", "https://1.1.1.1/dns-query", "over ECH"),
        ECH_TCP("ech-tcp", "tcp://8.8.8.8", "over ECH"),
        PLAIN("plain", null, "by the WARP API's new name or a Cloudflare edge"),
        SEMI_PYTHON("semipy", null, "with the semi-python ClientHello, fragmented", AetherTls.SEMI_PYTHON, fragment = true),
        TLSHELLO("tlshello", null, "with the semi-python ClientHello through Xray's tlshello", AetherTls.SEMI_PYTHON, tlsHello = true),
    }

    private const val ATTEMPT_MS = 35_000L

    /** All rungs together: a line that answers none of them is not kept waiting much past the old three. */
    private const val TOTAL_MS = 180_000L

    /**
     * Register every identity in [roles] and return each one's file text, or null when no attempt
     * worked. Blocking: a few seconds when it works. Gives up at once when the thread is interrupted
     * (a cancelled connect).
     */
    fun register(context: Context, roles: Set<Role>, log: (String) -> Unit = {}): Map<Role, String>? =
        register(context, roles, null, log)

    fun register(context: Context, roles: Set<Role>, workDirectory: File?, log: (String) -> Unit = {}): Map<Role, String>? {
        if (roles.isEmpty()) return emptyMap()
        // One run per family. Since Aether 2.4.0 `all` makes all five identities (gool's own pair
        // among them) and `gool` makes gool's MASQUE + WireGuard pair; the two WireGuard hops of
        // WARP-in-WARP are `gool-classic`. A file a run finds already there is kept, so the second
        // run in the same directory only adds its own.
        val runs = buildList {
            if (Role.WG_INNER in roles) add("gool-classic") else if (Role.WG in roles) add("wg")
            if (Role.MASQUE_INNER in roles) add("mim") else if (Role.MASQUE in roles) add("masque")
        }
        val which = runs.joinToString("+")
        val dir = workDirectory ?: File(context.filesDir, "aether-reg")
        if (workDirectory != null) require(dir.canonicalPath.startsWith(File(context.filesDir, "scout").canonicalPath + File.separator))
        var mayDelete = true
        val network = runCatching { com.mlmvpn.scanner.engines.cfnet.CfNetEnv.profile(context).key }.getOrDefault("unknown")
        val remembered = RegisterMemory.get(context, network)
        val started = SystemClock.elapsedRealtime()
        try {
            for (rung in (listOfNotNull(Rung.values().firstOrNull { it.key == remembered }) + Rung.values()).distinct()) {
                if (Thread.currentThread().isInterrupted) return null
                if (SystemClock.elapsedRealtime() - started > TOTAL_MS) break
                dir.deleteRecursively(); dir.mkdirs()
                val t0 = SystemClock.elapsedRealtime()
                if (!attempt(context, dir, runs, rung)) {
                    if (rung.key == remembered) RegisterMemory.forget(context, network)
                    continue
                }
                val out = roles.associateWith { role -> File(dir, role.file).takeIf { it.isFile }?.readText()?.takeIf { valid(it, role.masque) } }
                if (out.values.any { it == null }) {
                    Log.w(TAG, "register $which: no usable file for ${out.filterValues { it == null }.keys}")
                    continue
                }
                RegisterMemory.remember(context, network, rung.key)
                val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
                log("Identity: made ${rung.label} (${roles.joinToString { it.name.lowercase() }}) in ${String.format(java.util.Locale.US, "%.1f", secs)} s")
                return out.mapValues { it.value!! }
            }
            return null
        } catch (e: CleanupIncompleteException) {
            mayDelete = false
            throw e
        } finally {
            if (mayDelete) dir.deleteRecursively()
        }
    }

    /** Every register run of [runs] by [rung]; through a local Xray for [Rung.TLSHELLO], stopped after. */
    private fun attempt(context: Context, dir: File, runs: List<String>, rung: Rung): Boolean {
        if (!rung.tlsHello) return runs.all { run(context, dir, it, rung, null) }
        val port = AetherXrayExit.REGISTER.start(context) ?: return false
        try {
            return runs.all { run(context, dir, it, rung, AetherXrayExit.upstream(port)) }
        } finally {
            AetherXrayExit.REGISTER.stop(context, port)
        }
    }

    /** Per network (hashed key, never an SSID): the rung that last made an identity there. */
    private object RegisterMemory {
        private const val PREFS = "aether_register"
        fun get(context: Context, network: String): String? =
            runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(network, null) }.getOrNull()
        fun remember(context: Context, network: String, rung: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(network, rung).apply()
        }
        fun forget(context: Context, network: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(network).apply()
        }
    }

    /** A file the core will accept: the fields it cannot start without, and a certificate for MASQUE. */
    private fun valid(text: String, masque: Boolean): Boolean =
        Regex("""(?m)^device_id = "\S+"""").containsMatchIn(text) &&
            Regex("""(?m)^wg_private_key = "\S+"""").containsMatchIn(text) &&
            // A PEM spans lines, so Aether writes it as a multi-line string: cert_pem = """⏎-----BEGIN.
            (!masque || Regex("""(?m)^cert_pem = "{1,3}\s*-----BEGIN""").containsMatchIn(text))

    private fun run(context: Context, dir: File, which: String, rung: Rung, upstream: String?): Boolean {
        val dns = rung.dns
        val config = File(dir, "tunnel.toml").path
        val cmd = com.mlmvpn.scanner.store.StoreEngines.command(context, "aether", AetherBinaryLocator.LIB_NAME) + listOf(
            "--register", which,
            "--config", config, "--wg-config", config,
            "--masque-config", File(dir, "tunnel-masque.toml").path,
        ) + (if (dns != null) listOf("--ech", "auto", "--ech-dns", dns) else emptyList()) + listOf(
            "--log-level", "info",
        )
        val proc = try {
            ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).apply {
                val env = environment()
                // Nothing of a session's settings, nor a proxy the identity must not go through.
                env.keys.filter { it.startsWith("AETHER_") || it.lowercase().endsWith("_proxy") }.forEach { env.remove(it) }
                env["RUST_LOG"] = "info"
                // The rung's own: its ClientHello, the fragment, and the Xray it dials out through.
                env.putAll(rung.tls.env)
                if (rung.fragment) env["AETHER_MASQUE_H2_FRAGMENT"] = "1"
                if (upstream != null) env["AETHER_UPSTREAM"] = upstream
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "could not start aether: ${e.javaClass.simpleName}")
            return false
        }
        // Drained so the binary never blocks on a full pipe; only the last line is kept, for the
        // reason of a failure (the rest names the device).
        var last = ""
        val reader = Thread({
            runCatching { proc.inputStream.bufferedReader().forEachLine { last = it } }
        }, "aether-register-out").apply { isDaemon = true; start() }
        val deadline = SystemClock.elapsedRealtime() + ATTEMPT_MS
        try {
            while (!proc.waitUpTo(250, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() > deadline) {
                    proc.killHard()
                    Log.w(TAG, "register $which (${rung.key}): gave up")
                    return false
                }
            }
            reader.join(500)
            val code = proc.exitValue()
            if (code != 0) Log.w(TAG, "register $which (${rung.key}) exited $code: ${last.takeLast(160)}")
            return code == 0
        } catch (e: InterruptedException) {
            proc.killHard()
            Thread.currentThread().interrupt()
            return false
        } finally {
            // Wait for the owned executable before its work directory is removed, even on cancellation.
            val interrupted = Thread.interrupted()
            try {
                if (proc.alive()) proc.killHard()
                val ended = proc.waitUpTo(2, TimeUnit.SECONDS) || !proc.alive()
                try {
                    runCatching { proc.inputStream.close() }
                    reader.join(500)
                } finally {
                    if (!ended) throw CleanupIncompleteException()
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }
}
