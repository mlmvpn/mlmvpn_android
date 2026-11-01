package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.core.lantern.LanternEngine
import com.mlmvpn.core.lantern.LanternProcess
import com.mlmvpn.core.warp.WarpVia
import com.mlmvpn.scanner.engines.scout.*
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * «لنترن» in Scout: Lantern 10's own core in a child process of its own -- its own ports and its own
 * data folder, so a Lantern the tile is running is never touched -- given the tile's kept list when
 * it has one (the desktop's engine-sweep runs the same profile). A first list comes the way the tile
 * gets it: directly, and through WARP after twenty seconds of nothing. The shared yardstick then
 * measures the port like any other method's.
 */
internal object LanternAdapter : ScoutAdapter() {
    override val id = "lantern"
    override val homeId = "lantern"
    override val kind = MethodKind.TUNNEL
    override val lanes = setOf(Lane.LANTERN)
    override val estimateSec = 40
    override val speedBudgetMs = 90_000L

    override fun readiness(ctx: Context): Readiness =
        if (LanternEngine.available(ctx)) Readiness.Ready
        else Readiness.NotInstalled(tr("هستهٔ لنترن فقط برای گوشی‌های ۶۴ بیتی (arm64) ساخته شده است.", "Lantern's core is built for 64-bit (arm64) phones only."))

    override fun howTested() = tr(
        "هستهٔ رسمی لنترن ۱۰ در پوشه و درگاه جدای خودش بالا می‌آید، با فهرست سرور ذخیره‌شدهٔ کاشی اگر باشد، و به لنترنِ روشنِ شما دست نمی‌زند. بار اول اگر سرویس لنترن مستقیم جواب نداد، فهرست از راه وارپ گرفته می‌شود. مسیر داده با دست‌دادن کامل TLS با دو سایت فیلترشده از داخل آن ثابت می‌شود؛ کشور خروج را خود لنترن انتخاب می‌کند.",
        "Lantern 10's own core comes up in a folder and on a port of its own, with the tile's kept server list if there is one, and never touches a Lantern you have running. The first time, if Lantern's service does not answer directly, the list is fetched through WARP. The data path is proven with full TLS handshakes to two filtered sites through it; Lantern picks the exit country itself.")

    override fun variants(ctx: Context, line: LineSnapshot): List<Variant> = listOf(
        Variant("lantern", tr("خودکار · کشور را خود لنترن انتخاب می‌کند", "Automatic · Lantern picks the country"), budgetMs = 300_000),
    )

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared =
        if (LanternEngine.available(kit.context)) Prepared.Ready(v.id)
        else Prepared.Failed(ScoutStatus.NOT_APPLICABLE, tr("هستهٔ لنترن روی این گوشی اجرا نمی‌شود (۳۲ بیتی).", "Lantern's core does not run on this phone (32-bit)."))

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> = withContext(Dispatchers.IO) {
        val ctx = kit.context
        val dir = File(ctx.filesDir, "scout/lantern/v10")
        LanternEngine.seedProfile(LanternEngine.dataDir(ctx), dir)
        val kept = LanternEngine.cachedServerCount(dir)
        val port = freePort()
        val proc = LanternProcess(port, freePort(), dir)
        try {
            kit.step(Step.CONNECTING,
                if (kept > 0) tr("لنترن · ${LanternEngine.fa(kept)} سرور ذخیره‌شده", "Lantern · $kept kept servers")
                else tr("لنترن · گرفتن فهرست سرورها…", "Lantern · getting the server list…"))
            proc.start(ctx, smart = false, via = null, ident = LanternEngine.readIdentity(ctx), dns = LanternEngine.systemDns(ctx))
            var up = proc.awaitListening(20_000) { !isActive }
            if (!up && kept == 0 && proc.isAlive && isActive) {
                kit.step(Step.CONNECTING, tr("لنترن · گرفتن فهرست از راه وارپ…", "Lantern · getting the list through WARP…"))
                up = WarpVia.use(ctx) { warpPort, _ ->
                    if (warpPort > 0) proc.command("via 127.0.0.1:$warpPort")
                    val ok = runInterruptible { proc.awaitListening(220_000) { false } }
                    if (warpPort > 0) proc.command("via direct")
                    ok
                }
            }
            if (!up) return@withContext Served.Failed(tr("لنترن فهرست سرورهایش را نگرفت یا آماده نشد", "Lantern did not get its server list or did not come up"),
                inconclusive = !proc.isAlive)
            kit.step(Step.CONNECTING, tr("لنترن · آزمایش مسیر داده…", "Lantern · testing the data path…"))
            if (!LanternEngine.carriesWarm(port, 45_000) { !isActive || !proc.isAlive }) {
                return@withContext Served.Failed(tr("هیچ سرور لنترن از این اینترنت ترافیکی رد نکرد", "No Lantern server carried traffic from this internet"))
            }
            // A list Scout fetched is the tile's first list too, when the tile has none of its own.
            if (LanternEngine.cachedServerCount(ctx) == 0 && !LanternEngine.isAlive) LanternEngine.seedProfile(dir, LanternEngine.dataDir(ctx))
            Served.Ok(block(port))
        } finally {
            withContext(NonCancellable) { proc.stop() }
        }
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
