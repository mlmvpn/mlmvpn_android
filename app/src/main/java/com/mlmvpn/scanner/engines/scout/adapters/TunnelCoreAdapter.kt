package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.core.aether.AetherRegister
import com.mlmvpn.core.tunnel.CoreConfig
import com.mlmvpn.core.warp.Nat64Exits
import com.mlmvpn.scanner.data.CfIdentityFiles
import com.mlmvpn.scanner.engines.scout.*
import com.mlmvpn.scanner.store.StoreEngines
import com.mlmvpn.scanner.store.StoreManager
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.tunnel.Transport
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/** The actual JNI core of the three home methods, hosted in :scouttunnel with private files. */
internal class TunnelCoreAdapter(private val transport: Transport) : ScoutAdapter() {
    override val id = transport.id
    override val homeId = transport.id
    override val kind = MethodKind.TUNNEL
    // Not Lane.AETHER: these three use the tile's own identities, never «وارپ»'s, so they no longer
    // wait for it (and for Psiphon's WARP chain) to finish -- most of a scan ran one after another.
    override val lanes = setOf(Lane.TUNNEL_CORE)
    override val estimateSec = 300
    override val speedBudgetMs = 260_000L
    override val discoversVariants = transport != Transport.GOOL
    override fun requiresForeignExit(variantId: String) = variantId.startsWith("exit_")
    private val protocol get() = when (transport) {
        Transport.MASQUE -> "masque"
        Transport.WIREGUARD -> "wireguard"
        Transport.GOOL -> "gool"
        else -> error("unsupported_probe_transport")
    }
    /** [fragment]: MASQUE HTTP/2's ClientHello split forced on/off; null keeps the user's setting. */
    private data class Mode(val carrier: String, val peer: String? = null, val fragment: Boolean? = null)
    private data class Handle(val family: Fam, val mode: Mode, val settings: JSONObject)
    private val roles get() = when (transport) {
        Transport.MASQUE -> setOf(AetherRegister.Role.MASQUE)
        Transport.GOOL -> setOf(AetherRegister.Role.WG, AetherRegister.Role.WG_INNER)
        else -> setOf(AetherRegister.Role.WG)
    }
    private fun identities(ctx: Context) = File(ctx.filesDir, "scout/tunnel/identities").apply { mkdirs() }

    override fun readiness(ctx: Context): Readiness = if (listOf("libtunnelcore.so", "libtunneljni.so").all {
        StoreEngines.available(ctx, "tunnelcore", it)
    }) Readiness.Ready else Readiness.NotInstalled(tr("کتابخانهٔ هستهٔ تونل نصب نیست", "The tunnel core library is not installed"))

    override fun howTested() = tr(
        "همان هستهٔ روش در پردازش جدا، با هویت و پورت مستقل اجرا می‌شود؛ پاسخ معتبر وارپ و عبور داده لازم است. در MASQUE سه راه جدا تست می‌شود: HTTP/3 (با بازگشت به HTTP/2)، HTTP/2 تکه‌تکه و HTTP/2 ساده. آدرس دستی شما هم اگر وارد کرده باشید جدا تست می‌شود.",
        "The method's own core runs in a separate process with private identity files and port; verified WARP traffic and real data are required. MASQUE is tested three ways: HTTP/3 (falling back to HTTP/2), split HTTP/2 and plain HTTP/2. Your manual endpoint, if set, is tested too.")

    /**
     * MASQUE's ways of getting through, each tested on its own: HTTP/3 (with the core's own fall
     * back to HTTP/2), HTTP/2 with the ClientHello split, and plain HTTP/2 -- a line that
     * reassembles split handshakes blackholes the split one and may still pass the plain one, and
     * the other way round. Testing only the user's current setting reported «کار نمی‌کند» for a
     * MASQUE that one switch away worked.
     */
    private data class Carrier(val key: String, val carrier: String, val fragment: Boolean?, val label: String)
    private val carriers: List<Carrier> get() = if (transport == Transport.MASQUE) listOf(
        Carrier("h3", "h3", null, "HTTP/3 → HTTP/2"),
        Carrier("h2", "h2", true, tr("HTTP/2 تکه‌تکه", "HTTP/2 split")),
        Carrier("h2p", "h2", false, tr("HTTP/2 ساده", "HTTP/2 plain")),
    ) else listOf(Carrier("h3", "h3", null, ""))

    override fun variants(ctx: Context, line: LineSnapshot): List<Variant> {
        val first = firstFamily(ctx, line)
        val variants = carriers.flatMap { c -> listOf(Fam.V4, Fam.V6).map { family ->
            val present = if (family == Fam.V4) line.hasV4 else line.hasV6
            val suffix = if (family == Fam.V4) "v4" else "v6"
            Variant((if (transport == Transport.MASQUE) "${c.key}_" else "") + suffix,
                listOf(tr("مستقیم", "Direct"), c.label, family.label).filter(String::isNotBlank).joinToString(" · "),
                family, quick = c.key == "h3" && family == first,
                budgetMs = 600_000, payload = Mode(c.carrier, fragment = c.fragment), notApplicable = if (present) null else tr("این خانواده روی خط موجود نیست", "This IP family is absent on the line"),
                // HTTP/2 matters where HTTP/3 does not get through; where it does, the family is answered.
                unneededIf = if (transport == Transport.MASQUE && c.key != "h3") "h3_$suffix" else null)
        } } + manualVariants(ctx, line)
        if (transport == Transport.GOOL) return variants
        val proto = if (transport == Transport.MASQUE) Nat64Exits.Proto.MASQUE else Nat64Exits.Proto.WG
        // A country gateway is one more exit, not one more way through: HTTP/3 and split HTTP/2 only.
        val exits = Nat64Exits.onePerCountry(ctx).flatMap { gateway ->
            val peer = Nat64Exits.endpoint(gateway, proto) ?: return@flatMap emptyList()
            carriers.filter { it.key != "h2p" }.map { c -> Variant("exit_${gateway.key}_${c.key}",
                listOf(gateway.country, gateway.city, gateway.provider, c.label)
                    .filter(String::isNotBlank).joinToString(" · "), Fam.V6, budgetMs = 600_000,
                payload = Mode(c.carrier, peer, c.fragment), notApplicable = if (line.hasV6) null else tr("خروجی این کشور به IPv6 نیاز دارد", "This country gateway requires IPv6"),
                unneededIf = if (c.key != "h3") "exit_${gateway.key}_h3" else null) }
        }
        // The full pass runs in this order: every HTTP/3 way first (direct, then each country), then
        // the HTTP/2 ones. On a line that drops MASQUE's own gateways each direct way waits out its
        // hunt, and the countries that do work (four seconds by hand) came only after all of them.
        val isH3 = { v: Variant -> (v.payload as? Mode)?.carrier == "h3" }
        return (variants + exits).sortedBy { if (isH3(it)) 0 else 1 }
    }

    /**
     * The endpoint the user typed by hand (Settings → manual endpoint), which a tap on the tile
     * dials: tested as its own variant, so an address that works only by hand is not missed.
     */
    private fun manualVariants(ctx: Context, line: LineSnapshot): List<Variant> {
        val peer = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("manual_endpoint", null)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val v6 = peer.startsWith("[") || peer.count { it == ':' } > 1
        val family = if (v6) Fam.V6 else Fam.V4
        val present = if (v6) line.hasV6 else line.hasV4
        return carriers.filter { it.key != "h2p" }.map { c ->
            Variant("manual_${c.key}", listOf(tr("آدرس دستی شما", "Your manual endpoint"), c.label, family.label)
                .filter(String::isNotBlank).joinToString(" · "), family, budgetMs = 600_000,
                payload = Mode(c.carrier, peer, c.fragment),
                notApplicable = if (present) null else tr("این خانواده روی خط موجود نیست", "This IP family is absent on the line"))
        }
    }

    override suspend fun discover(kit: ScoutKit): List<Variant> {
        runInterruptible(Dispatchers.IO) { Nat64Exits.refreshIfStale(kit.context) }
        return variants(kit.context, kit.line)
    }

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared = withContext(Dispatchers.IO) {
        val cache = identities(kit.context)
        for (role in roles) {
            val destination = File(cache, role.file)
            val original = File(kit.context.filesDir, role.file)
            if (!CfIdentityFiles.exists(destination) && CfIdentityFiles.exists(original)) {
                CfIdentityFiles.copyForProbe(kit.context, original, destination)
                CfIdentityFiles.sealDirectory(kit.context, cache)
            }
        }
        val missing = roles.filter { !CfIdentityFiles.exists(File(cache, it.file)) }.toSet()
        if (missing.isNotEmpty()) {
            kit.step(Step.PREPARING, tr("آماده‌سازی خودکار هویت تست…", "Preparing a test identity automatically…"))
            val registered = try {
                if (!StoreEngines.available(kit.context, "aether", "libaether.so"))
                    StoreManager.ensureEngine(kit.context, "aether", "libaether.so")
                runInterruptible {
                    AetherRegister.register(kit.context, missing, File(kit.context.filesDir, "scout/tunnel/register-${UUID.randomUUID()}"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AetherRegister.CleanupIncompleteException) {
                ScoutEngine.stop(EndReason.INTERRUPTED)
                throw e
            } catch (_: Exception) {
                null // Optional ECH provisioning must not prevent the native registration path.
            }
            registered?.forEach { (role, text) -> CfIdentityFiles.save(kit.context, File(cache, role.file), text) }
            // The native core can still register directly if ECH did not answer; only its private files are used.
        }
        val settings = JSONObject(CoreConfig.json(kit.context, protocol, 1080, migratePreferences = false))
        Prepared.Ready(Handle(v.fam, v.payload as? Mode ?: Mode("h3"), settings))
    }

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> = withContext(Dispatchers.IO) {
        val handle = ready.handle as Handle
        val token = UUID.randomUUID().toString()
        val directory = File(kit.context.filesDir, "scout/tunnel/runs/$token").apply { mkdirs() }
        val cache = identities(kit.context)
        var hostMayUseFiles = false
        try {
            for (role in roles) if (CfIdentityFiles.exists(File(cache, role.file)))
                CfIdentityFiles.copyForProbe(kit.context, File(cache, role.file), File(directory, role.file))
            warmUp(kit.context, directory, handle)
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val config = ScoutTunnelPolicy.config(handle.settings, directory, protocol, handle.family, handle.mode.carrier, port, handle.mode.peer, handle.mode.fragment)
            // How long to wait for verified traffic: a fixed peer (a country, the manual address) answers
            // within seconds or not at all; a hunt from a warm start finds its endpoint quickly too.
            val warm = handle.mode.peer == null && lastConnNames.any { File(directory, it).isFile }
            val verifyMs = when {
                // Measured on the A50: a country that works carried data in 6-13 s; the ones that do not
                // never answer, and at 60 s each they were twelve minutes of a scan.
                handle.mode.peer != null -> 35_000L
                warm -> 120_000L
                // HTTP/2 hunts the same few gateways HTTP/3 just did.
                handle.mode.carrier == "h2" -> 75_000L
                else -> 150_000L
            }
            ScoutTunnelHost.run(kit, token, config, verifyMs = verifyMs, beforeStart = { hostMayUseFiles = true }, afterStop = {
                try {
                    for (role in roles) File(directory, role.file).takeIf { it.isFile }?.readText()?.let { text ->
                        if (ScoutTunnelPolicy.identityUsable(text, role.masque)) CfIdentityFiles.save(kit.context, File(cache, role.file), text)
                    }
                    keepWarm(kit.context, directory, handle)
                } finally { clear(directory); hostMayUseFiles = false }
            }, block = block)
        } finally {
            // Before START, a failed copy/cancel is ours to clean. Afterwards the host must prove death first.
            if (!hostMayUseFiles) clear(directory)
        }
    }

    // ---- starting where the tile starts --------------------------------------------------------
    //
    // A tap on the tile reconnects in a couple of seconds because the core first dials the endpoint
    // that last worked on this network (`tunnel-lastconn.toml` beside the identity) and the gateways
    // it has cached. Every Scout run had a fresh, empty directory: no last endpoint, no cache, so each
    // variant began a whole endpoint hunt -- minutes per variant, and «این دور جواب نداد» when the
    // hunt ran out of time (user report 2026-10-11, WireGuard: "by hand it takes 2 seconds").
    // Now a run starts from what Scout itself last found for this method and family, else from the
    // tile's own files, and keeps what it found for the next run. An endpoint of the other family is
    // never handed over: a variant named IPv4 must have reached the line over IPv4.

    private val lastConnNames = listOf("tunnel-lastconn.toml", "tunnel-gool-lastconn.toml", "tunnel-masque-lastconn.toml")

    /**
     * The family the quick pass tries: the one that last worked for this method (Scout's own, then
     * the tile's), else IPv6 where the line has it -- on the user's home Wi-Fi WireGuard and
     * WARP-in-WARP pass only over IPv6, and the quick pass on IPv4 spent 3.5 minutes per method
     * learning that again every scan.
     */
    private fun firstFamily(ctx: Context, line: LineSnapshot): Fam {
        val present = { f: Fam -> if (f == Fam.V4) line.hasV4 else line.hasV6 }
        for (f in listOf(Fam.V6, Fam.V4)) {
            val dir = File(ctx.filesDir, "scout/tunnel/warm/$protocol-${f.name.lowercase()}")
            if (present(f) && lastConnNames.any { File(dir, it).isFile }) return f
        }
        val tile = lastConnNames.firstNotNullOfOrNull { n -> File(ctx.filesDir, n).takeIf { it.isFile }?.let { runCatching { peerFamily(it.readText()) }.getOrNull() } }
        if (tile != null && present(tile)) return tile
        return if (line.hasV6) Fam.V6 else Fam.V4
    }
    private val endpointCache = "endpoints.json"
    private fun warmDir(ctx: Context, family: Fam) = File(ctx.filesDir, "scout/tunnel/warm/$protocol-${family.name.lowercase()}").apply { mkdirs() }

    private fun warmUp(ctx: Context, directory: File, handle: Handle) {
        // A forced peer (a country gateway, the manual address) is the variant itself: nothing to add.
        if (handle.mode.peer != null) return
        val warm = warmDir(ctx, handle.family)
        runCatching {
            for (name in lastConnNames) {
                val from = File(warm, name).takeIf { it.isFile } ?: File(ctx.filesDir, name).takeIf { it.isFile } ?: continue
                val text = from.readText()
                if (peerFamily(text) == handle.family) File(directory, name).writeText(text)
            }
            (File(warm, endpointCache).takeIf { it.isFile } ?: File(ctx.filesDir, "masque-gateway-cache.json").takeIf { it.isFile })
                ?.copyTo(File(directory, endpointCache), overwrite = true)
        }
    }

    private fun keepWarm(ctx: Context, directory: File, handle: Handle) {
        if (handle.mode.peer != null) return
        val warm = warmDir(ctx, handle.family)
        runCatching {
            for (name in lastConnNames) {
                val f = File(directory, name).takeIf { it.isFile } ?: continue
                if (peerFamily(f.readText()) == handle.family) f.copyTo(File(warm, name), overwrite = true)
            }
            File(directory, endpointCache).takeIf { it.isFile && it.length() > 2 }?.copyTo(File(warm, endpointCache), overwrite = true)
        }
    }

    /** The family of `peer = "…"` in a last-connection file. */
    private fun peerFamily(text: String): Fam? {
        val peer = Regex("(?m)^peer\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return null
        return if (peer.startsWith("[") || peer.count { it == ':' } > 1) Fam.V6 else Fam.V4
    }

    private fun clear(directory: File) {
        if (!directory.deleteRecursively() && directory.exists()) {
            ScoutEngine.stop(EndReason.INTERRUPTED)
            error("probe_file_cleanup_incomplete")
        }
    }
}
