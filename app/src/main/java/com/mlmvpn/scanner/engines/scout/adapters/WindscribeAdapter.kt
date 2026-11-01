package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.scout.Lane
import com.mlmvpn.scanner.engines.scout.LineSnapshot
import com.mlmvpn.scanner.engines.scout.MethodKind
import com.mlmvpn.scanner.engines.scout.Prepared
import com.mlmvpn.scanner.engines.scout.Readiness
import com.mlmvpn.scanner.engines.scout.ScoutAdapter
import com.mlmvpn.scanner.engines.scout.ScoutKit
import com.mlmvpn.scanner.engines.scout.ScoutStatus
import com.mlmvpn.scanner.engines.scout.Served
import com.mlmvpn.scanner.engines.scout.Step
import com.mlmvpn.scanner.engines.scout.Variant
import com.mlmvpn.scanner.engines.windscribe.WindscribeEngine
import com.mlmvpn.scanner.engines.windscribe.WsWgProbe
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Windscribe as Scout tests it: the account's own WireGuard to the nearest node, as a real session in
 * a probe core -- straight, and from inside Cloudflare WARP (how it connects where the line
 * blackholes Windscribe, measured on the user's home line 2026-10-10). Scout's common yardstick then
 * measures data, delay, stability and speed through it like every other method.
 *
 * Scout's shared test core replaces a config's inbounds, which would drop the loopback door the WARP
 * route needs; so this adapter runs its own probe ([WsWgProbe]) and hands Scout its port.
 */
internal object WindscribeAdapter : ScoutAdapter() {
    override val id = "windscribe"
    override val homeId = "windscribe"
    override val kind = MethodKind.TUNNEL
    override val lanes = setOf(Lane.XRAY)

    override fun howTested() = tr(
        "وایرگارد خودِ حساب وینداسکرایب شما به نزدیک‌ترین سرور، مستقیم و از راه وارپ کلادفلر؛ داده، سرعت و پایداری واقعاً سنجیده می‌شود.",
        "Your Windscribe account's own WireGuard to the nearest server, direct and through Cloudflare WARP; data, speed and stability are really measured.",
    )

    override fun readiness(ctx: Context): Readiness =
        if (WindscribeEngine.signedIn(ctx)) Readiness.Ready
        else Readiness.NeedsSetup(tr("به حساب وینداسکرایب وارد نشده‌اید", "Not signed in to Windscribe"))

    override fun variants(ctx: Context, line: LineSnapshot) = listOf(
        Variant("warp", tr("وایرگارد از راه کلادفلر", "WireGuard through Cloudflare"), quick = true, budgetMs = 75_000),
        Variant("direct", tr("وایرگارد مستقیم", "WireGuard direct"), budgetMs = 45_000),
    )

    private class Handle(val conf: String, val warp: Boolean)

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        kit.step(Step.PREPARING)
        val (conf, city) = WindscribeEngine.probeConf(kit.context) ?: return Prepared.Failed(ScoutStatus.SERVICE_DOWN,
            tr("پیکربندی وایرگارد از وینداسکرایب دریافت نشد", "Windscribe gave no WireGuard configuration"))
        if (v.id == "warp" && AmzEngine.warpCarrier(kit.context) == null) return Prepared.Failed(ScoutStatus.FAILED,
            tr("وارپ کلادفلر روی این خط داده‌ای نبرد", "Cloudflare WARP carried no data on this line"), Step.CONNECTING)
        return Prepared.Ready(Handle(conf, v.id == "warp"), detail = city)
    }

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> {
        val h = ready.handle as? Handle ?: return Served.Failed(tr("داده‌ای برای اجرا نبود", "Nothing to run"), inconclusive = true)
        // Through WARP: the node's WireGuard rides the shared WARP session's UDP, as Windscribe's own test does.
        val udp = if (h.warp) {
            val wg = com.mlmvpn.scanner.engines.amnezia.WgConfig.parseConf(h.conf)
                ?: return Served.Failed(tr("پیکربندی نامعتبر است", "The config is invalid"), inconclusive = true)
            WindscribeEngine.udpThroughWarp(wg.endpointHost, wg.endpointPort)
                ?: return Served.Failed(tr("وارپ کلادفلر روی این خط داده‌ای نبرد", "Cloudflare WARP carried no data on this line"))
        } else null
        val probe = withContext(Dispatchers.IO) { WsWgProbe.start(kit.context, h.conf, udp?.second) }
        if (probe == null) { udp?.first?.close(); return Served.Failed(tr("هستهٔ تست بالا نیامد", "The test core did not start"), inconclusive = true) }
        return try { Served.Ok(block(probe.httpPort)) } finally { withContext(Dispatchers.IO) { probe.close(); udp?.first?.close() } }
    }
}
