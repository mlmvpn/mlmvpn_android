package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.core.urnetwork.UrnetworkEngine
import com.mlmvpn.core.urnetwork.UrnetworkProcess
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
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * «URnetwork» in Scout: URnetwork's own core in a child process and a folder of its own -- a device
 * of its own in the user's account, so a URnetwork the tile is running is never touched. Scout waits
 * for providers to join its window, says how many while it does, proves the data path with TLS
 * handshakes to two filtered sites, then hands its local SOCKS port to the shared yardstick.
 *
 * No account is «راه‌اندازی نشده», never «خراب»; a used-up balance (the free plan's is daily) says
 * so instead of pretending the line failed.
 */
internal object UrnetworkAdapter : ScoutAdapter() {
    override val id = "urnetwork"
    override val homeId = "urnetwork"
    override val kind = MethodKind.TUNNEL
    override val lanes = setOf(Lane.URNETWORK)
    override val estimateSec = 45
    override val speedBudgetMs = 90_000L

    override fun howTested() = tr(
        "هستهٔ URnetwork در پردازه و پوشهٔ جدای خودش، به‌عنوان دستگاهی جدا در همین حساب، بالا می‌آید و به URnetwork روشنِ شما دست نمی‌زند. پس از وصل شدن ارائه‌دهنده‌ها، مسیر داده با دست‌دادن کامل TLS با دو سایت فیلترشده ثابت می‌شود و بعد با خط‌کش مشترک سنجیده می‌شود. خروج از چند ارائه‌دهنده در کشورهای مختلف است و مصرف آن از حجم حساب (سهمیهٔ روزانهٔ طرح رایگان) کم می‌شود.",
        "URnetwork's core comes up in a process and a folder of its own, as a device of its own in the same account, and never touches a URnetwork you have running. Once providers join, the data path is proven with full TLS handshakes to two filtered sites, then measured by the shared yardstick. Traffic leaves through several providers in different countries and counts against the account's data (the free plan's daily allowance).",
    )

    private fun noAccount() = tr("حساب URnetwork هنوز ساخته نشده", "No URnetwork account yet")

    override fun readiness(ctx: Context): Readiness = when {
        !UrnetworkEngine.available(ctx) -> Readiness.NotInstalled(UrnetworkEngine.unavailableReason(ctx) ?: "")
        !UrnetworkEngine.hasAccount(ctx) -> Readiness.NeedsSetup(noAccount())
        else -> Readiness.Ready
    }

    override fun variants(ctx: Context, line: LineSnapshot): List<Variant> = listOf(
        Variant("urnetwork", tr("بهترین مکان در دسترس", "Best available location"), budgetMs = 200_000),
    )

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        UrnetworkEngine.unavailableReason(kit.context)?.let { return Prepared.Failed(ScoutStatus.NEEDS_SETUP, it) }
        // A balance the last reading found used up, before the data that reading was waiting for came
        // (the free plan's next daily grant, Pro's next month): the account, not the line -- nothing
        // to measure until then.
        UrnetworkEngine.cachedUsage(kit.context)?.let { u ->
            val next = u.refreshAt(u.fetchedAt)
            if (u.total > 0 && u.remaining <= 0L && next != null && next > System.currentTimeMillis()) {
                return Prepared.Failed(ScoutStatus.NEEDS_SETUP, quotaText())
            }
        }
        return Prepared.Ready(v.id)
    }

    private fun quotaText() = tr("حجم حساب URnetwork تمام شده؛ تا شارژ بعدی (طرح رایگان: هر روز ساعت ۰۰:۰۰ به وقت جهانی) داده‌ای رد نمی‌شود",
        "The URnetwork account's data is used up; nothing passes until the next grant (free plan: every day at 00:00 UTC)")

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> = withContext(Dispatchers.IO) {
        val ctx = kit.context
        UrnetworkEngine.syncAccount(ctx)
        val port = UrnetworkEngine.freePort()
        var proc = UrnetworkProcess(role = "scout", dir = UrnetworkEngine.roleDir(ctx, "scout"), socksPort = port, memoryMb = 48)
        try {
            kit.step(Step.CONNECTING, tr("URnetwork · راه‌اندازی هسته…", "URnetwork · starting the core…"))
            proc.start(ctx)
            var up = proc.awaitReady(100_000) { !isActive }
            // A refused sign-in, recovered once (a new device, or the account signed in again with
            // the recovery phrase kept on the phone), then one more start.
            if (!up && isActive && proc.loggedOut && UrnetworkEngine.recover(ctx, proc)) {
                proc.stop()
                proc = UrnetworkProcess(role = "scout", dir = UrnetworkEngine.roleDir(ctx, "scout"), socksPort = port, memoryMb = 48)
                proc.start(ctx)
                up = proc.awaitReady(100_000) { !isActive }
            }
            if (!up) {
                return@withContext Served.Failed(proc.lastError?.let { UrnetworkEngine.readable(it) } ?: tr("هستهٔ URnetwork آماده نشد", "URnetwork's core did not come up"),
                    inconclusive = !proc.isAlive && proc.exitCode != UrnetworkEngine.EXIT_REFUSED && proc.exitCode != UrnetworkEngine.EXIT_LOGGED_OUT)
            }
            kit.step(Step.CONNECTING, tr("URnetwork · یافتن ارائه‌دهنده‌ها…", "URnetwork · finding providers…"))
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline && isActive) {
                if (proc.added > 0 || !proc.isAlive || proc.insufficient || proc.refused != null || proc.loggedOut) break
                Thread.sleep(500)
            }
            // The account's balance, not this line: not a verdict on the method here.
            if (proc.insufficient) return@withContext Served.Failed(quotaText(), inconclusive = true)
            if (proc.added == 0) {
                return@withContext Served.Failed(
                    proc.refused?.let { UrnetworkEngine.readable(it) } ?: proc.lastError?.let { UrnetworkEngine.readable(it) }
                        ?: tr("هیچ ارائه‌دهندهٔ URnetwork از این اینترنت وصل نشد", "No URnetwork provider connected from this internet"),
                    inconclusive = !proc.isAlive && proc.refused == null && !proc.loggedOut,
                )
            }
            val n = proc.added
            kit.step(Step.CONNECTING, tr("URnetwork · ${UrnetworkEngine.fa(n)} ارائه‌دهنده وصل شد · آزمایش مسیر داده…", "URnetwork · $n providers connected · testing the data path…"))
            if (!com.mlmvpn.core.lantern.LanternEngine.carriesWarm(port, 60_000) { !isActive || !proc.isAlive }) {
                return@withContext Served.Failed(proc.lastError?.let { UrnetworkEngine.readable(it) }
                    ?: tr("ارائه‌دهنده‌های URnetwork وصل شدند ولی داده‌ای از آن‌ها رد نشد", "URnetwork's providers connected but no data went through them"))
            }
            Served.Ok(block(port))
        } finally {
            withContext(NonCancellable) { proc.stop() }
        }
    }
}
