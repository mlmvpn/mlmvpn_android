package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.scanner.engines.npv.NpvLibrary
import com.mlmvpn.scanner.engines.scout.LineSnapshot
import com.mlmvpn.scanner.engines.scout.MethodKind
import com.mlmvpn.scanner.engines.scout.Prepared
import com.mlmvpn.scanner.engines.scout.Readiness
import com.mlmvpn.scanner.engines.scout.ScoutKit
import com.mlmvpn.scanner.engines.scout.ScoutStatus
import com.mlmvpn.scanner.engines.scout.Step
import com.mlmvpn.scanner.engines.scout.Variant
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.emergency.faDigits

/**
 * «NPV» in Scout: the standard links in NPV's library, run for real on the Xray test core the way
 * «کانفیگ‌های من» are; the fastest is measured by the shared yardstick. NPV Tunnel's own files are
 * not run in this app (see [NpvLibrary]), so they are never tested here either.
 */
internal object NpvAdapter : XrayAdapter() {
    override val id = "npv"
    override val homeId = "npv"
    override val kind = MethodKind.TUNNEL
    override val estimateSec = 20
    override val estimateMb = 0.5

    override fun howTested() = tr(
        "کانفیگ‌های استاندارد بخش NPV (vless، vmess، trojan، ss و …) با هستهٔ Xray برنامه واقعاً اجرا می‌شوند و سریع‌ترین با خط‌کش مشترک سنجیده می‌شود. فایل‌های خود NPV Tunnel (npvt و npvs) در این برنامه اجرا و آزموده نمی‌شوند.",
        "The NPV section's standard configs (vless, vmess, trojan, ss …) are run for real on the app's Xray core, and the fastest is measured. NPV Tunnel's own files (npvt, npvs) are neither run nor tested in this app.",
    )

    private fun noneYet() = tr("هنوز کانفیگ قابل اجرایی در بخش NPV نیست", "No runnable config in the NPV section yet")

    override fun readiness(ctx: Context): Readiness =
        if (NpvLibrary.runnable(ctx).isEmpty()) Readiness.NeedsSetup(noneYet()) else Readiness.Ready

    override fun variants(ctx: Context, line: LineSnapshot) =
        listOf(Variant("npv", tr("کانفیگ‌های NPV", "NPV configs"), quick = true, budgetMs = 150_000))

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        // The main page first, then «انبار»; each fastest first by its last measurement here, the
        // never-measured newest next, the ones that did not answer last.
        val all = NpvLibrary.runnable(kit.context)
        val links = (NpvLibrary.byDelay(all.filter { it.shelf == NpvLibrary.Shelf.MAIN }) +
            NpvLibrary.byDelay(all.filter { it.shelf == NpvLibrary.Shelf.STORE })).take(40)
        if (links.isEmpty()) return Prepared.Failed(ScoutStatus.NEEDS_SETUP, noneYet())
        kit.step(Step.CONNECTING, tr("آزمودن ${faDigits(links.size.toString())} کانفیگ…", "Trying ${links.size} configs…"))
        val won = fastest(kit.context, links.mapNotNull { it.uri }, parallel = 4, timeoutMs = 10_000)
            ?: return Prepared.Failed(
                ScoutStatus.FAILED,
                tr("هیچ‌کدام از ${faDigits(links.size.toString())} کانفیگ آزموده‌شده جواب نداد", "None of the ${links.size} configs tried answered"),
                Step.CONNECTING,
            )
        val handle = uriHandle(won.first)
            ?: return Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("کانفیگ خوانده نشد", "Could not read the config"))
        return Prepared.Ready(handle, detail = links.firstOrNull { it.uri == won.first }?.name)
    }
}
