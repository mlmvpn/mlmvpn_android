package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.core.tunnel.SecureStore
import com.mlmvpn.scanner.engines.cfdoctor.NativeProbe
import com.mlmvpn.scanner.engines.github.*
import com.mlmvpn.scanner.engines.scout.*
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Existing live sessions only: no workflow dispatch, runner renewal or VPN state changes. */
internal object GithubAdapter : XrayAdapter() {
    override val id = "github"
    override val homeId = "github"
    override val kind = MethodKind.TUNNEL
    override val estimateSec = 360

    private data class Handle(val xray: XrayHandle, val expiresAt: Long)
    private fun live(session: GtSession) = ScoutSessionPolicy.usable(session.status, session.expiresAt, System.currentTimeMillis())
    private fun sessions(ctx: Context) = GtStore(ctx).sessions().filter(::live).sortedByDescending { it.createdAt }

    override fun readiness(ctx: Context): Readiness =
        if (GtStore(ctx).accounts().isNotEmpty() || sessions(ctx).isNotEmpty()) Readiness.Ready
        else Readiness.NeedsSetup(tr("حساب گیت‌هاب اضافه نشده", "No GitHub account added"))

    override fun howTested() = tr(
        "جلسه‌های زنده با تازه‌ترین آدرس رله و هستهٔ جداگانهٔ Xray سنجیده می‌شوند؛ جلسهٔ تازه از سهمیهٔ گیت‌هاب باید در صفحهٔ گیت‌هاب ساخته شود. نتیجه مربوط به خروجی خود رانر است.",
        "Live sessions use refreshed relay addresses and an isolated Xray core; start a new quota-consuming session on the GitHub page. Results describe the runner's own exit.")

    override fun variants(ctx: Context, line: LineSnapshot): List<Variant> = sessions(ctx).mapIndexed { index, session ->
        Variant("session_${session.id}", tr("جلسهٔ ${index + 1}", "Session ${index + 1}"), quick = index == 0,
            budgetMs = 600_000, payload = session.id)
    }.ifEmpty { listOf(Variant("session", tr("جلسهٔ گیت‌هاب", "GitHub session"), quick = true)) }

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared = withContext(Dispatchers.IO) {
        val store = GtStore(kit.context)
        val saved = (v.payload as? String)?.let(store::session)?.takeIf(::live)
            ?: return@withContext Prepared.Failed(ScoutStatus.ON_DEMAND,
                tr("جلسهٔ زنده ندارید؛ در صفحهٔ گیت‌هاب جلسه بسازید و دوباره تست کنید", "No live session; start one on the GitHub page and test again"))
        kit.step(Step.PREPARING, tr("به‌روزرسانی آدرس رله‌های جلسه…", "Refreshing session relay addresses…"))
        val key = SecureStore.decrypt(saved.sealKeyEnc)
        val fresh = try {
            val token = store.token(saved.accountId)
            if (token.isBlank() || saved.repository.isBlank()) null else runInterruptible {
                GtGithub.sessionFile(token, saved.repository, saved.id)?.let { sealed ->
                    val payload = GtCrypto.open(key, sealed, saved.id)
                    val transport = GtTransport.from(payload)
                    if (ScoutSessionPolicy.acceptHandoff(saved.rev, transport.rev, transport.phase)) transport else null
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        val transport = fresh ?: GtTransport.from(GtCrypto.open(key, JSONObject(saved.sealed), saved.id))
        val broker = store.broker()
        val host = if (broker.deployed && broker.version !in 1 until GtBroker.REQUIRED_VERSION)
            runCatching { java.net.URL(broker.effectiveUrl).host.takeIf(String::isNotBlank) }.getOrNull() else null
        val ips = if (host != null && transport.hosts.isNotEmpty())
            GtCleanIp.candidates(kit.context, store, GtCleanIp.networkKey(kit.context)) else emptyList()
        if (ips.isEmpty() && transport.relays.isEmpty()) return@withContext Prepared.Failed(ScoutStatus.INCONCLUSIVE,
            tr("جلسه مسیر ورودی قابل آزمایش ندارد", "The session has no testable entry route"))
        val candidates = transport.relays.map { GtXray.relayOutbound(it, transport) }.toMutableList()
        if (host != null && ips.isNotEmpty()) {
            val expiry = saved.expiresAt.takeIf { it > 0 } ?: (System.currentTimeMillis() + 6 * 3600_000L)
            val pass = GtCrypto.signPass(store.installSecret(), GtXray.label(transport.hosts.first()), saved.id, expiry)
            candidates += ips.map { GtXray.outbound(it, host, pass, transport) }
        }
        var best: Pair<String, Long>? = null
        var testerFailed = false
        val finished = withTimeoutOrNull(360_000) {
            for ((index, candidate) in candidates.withIndex()) {
                kit.step(Step.PREPARING, tr("سنجش مسیر ورودی", "Testing entry route") + " ${index + 1}/${candidates.size}")
                val config = ScoutSessionPolicy.probeConfig(candidate)
                val result = withTimeoutOrNull(30_000) {
                    NativeProbe(kit.context).withCore(config) { port ->
                        runInterruptible { ScoutNet.https204(port, null, ScoutNet.VERIFY_HOST, 6_000) }
                    }
                }
                if (result == null || result.first != "ok") testerFailed = true
                val hit = result?.takeIf { it.first == "ok" }?.second ?: continue
                if (hit.ms < (best?.second ?: Long.MAX_VALUE)) best = config to hit.ms
            }
            true
        } == true
        if (!live(saved)) return@withContext Prepared.Failed(ScoutStatus.ON_DEMAND,
            tr("جلسه هنگام بررسی منقضی شد؛ جلسهٔ تازه لازم است", "The session expired during testing; a new session is needed"))
        val config = best?.first ?: return@withContext if (!finished || testerFailed)
            Prepared.Failed(ScoutStatus.INCONCLUSIVE, tr("سنجش مسیرهای جلسه کامل نشد", "Session route testing did not finish"))
        else Prepared.Failed(ScoutStatus.FAILED, tr("هیچ‌کدام از مسیرهای ورودی جلسه داده عبور ندادند", "None of the session entry routes carried data"))
        Prepared.Ready(Handle(XrayHandle(config), saved.expiresAt),
            tr("خروجی خود رانر گیت‌هاب", "GitHub runner exit"))
    }

    override suspend fun <T> serve(kit: ScoutKit, ready: Prepared.Ready, block: suspend (Int) -> T): Served<T> {
        val handle = ready.handle as Handle
        if (!ScoutSessionPolicy.usable("READY", handle.expiresAt, System.currentTimeMillis()))
            return Served.Failed(tr("جلسه هنگام بررسی منقضی شد", "The session expired during the check"), inconclusive = true)
        return super.serve(kit, Prepared.Ready(handle.xray, ready.detail), block)
    }
}
