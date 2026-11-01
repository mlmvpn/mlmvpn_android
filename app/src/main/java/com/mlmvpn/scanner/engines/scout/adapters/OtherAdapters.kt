package com.mlmvpn.scanner.engines.scout.adapters

import android.content.Context
import com.mlmvpn.core.tunnel.TorManager
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.engines.amnezia.AmzKind
import com.mlmvpn.scanner.engines.scout.Evidence
import com.mlmvpn.scanner.engines.scout.Fam
import com.mlmvpn.scanner.engines.scout.Lane
import com.mlmvpn.scanner.engines.scout.LineSnapshot
import com.mlmvpn.scanner.engines.scout.MethodKind
import com.mlmvpn.scanner.engines.scout.Readiness
import com.mlmvpn.scanner.engines.scout.ScoutAdapter
import com.mlmvpn.scanner.engines.scout.Variant
import com.mlmvpn.scanner.engines.scout.Prepared
import com.mlmvpn.scanner.engines.scout.ScoutKit
import com.mlmvpn.scanner.engines.scout.ScoutStatus
import com.mlmvpn.scanner.engines.scout.VariantResult
import com.mlmvpn.scanner.engines.scout.Numbers
import com.mlmvpn.scanner.engines.scout.Step
import com.mlmvpn.scanner.engines.scout.ScoutHandshake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.tunnel.Transport

/**
 * The handshake-only methods (VPN Gate, OpenVPN) and FENIX. The engines whose testers reuse their
 * own screens' tests live in EngineAdapters.kt.
 */

/** «گیت‌وی MLM» (VPN Gate / SoftEther): proven only up to the session handshake without the phone's VPN. */
internal object VpnGateAdapter : ScoutAdapter() {
    override val id = "vpngate"
    override val homeId = "vpngate"
    override val kind = MethodKind.TUNNEL
    override val evidence = Evidence.HANDSHAKE
    override val lanes = emptySet<Lane>()

    override fun howTested() = tr("دست‌دهی واقعی SoftEther تا «سرور جلسه را پذیرفت»؛ بدون گرفتن VPN گوشی داده رد نمی‌شود، پس عدد سرعت ندارد.", "A real SoftEther handshake up to «session accepted»; no data without the phone's VPN, so no speed.")
    override fun readiness(ctx: Context): Readiness = Readiness.Ready
    override fun variants(ctx: Context, line: LineSnapshot) = listOf(Variant("gate", "VPN Gate", quick = true, budgetMs = 60_000))

    override suspend fun handshake(kit: ScoutKit, v: Variant): VariantResult {
        val repo = com.mlmvpn.scanner.engines.vpngate.VpnGateRepository(kit.context)
        repo.refresh()
        val servers = repo.serversFlow.value.sortedWith(compareByDescending<com.mlmvpn.scanner.engines.vpngate.VpnGateServer> { it.isOfficialRelay }.thenByDescending { it.score }).take(4)
        if (servers.isEmpty()) return VariantResult(v.id, v.label, status = ScoutStatus.SERVICE_DOWN,
            reason = tr("فهرست سرورها در دسترس نیست", "Server list unavailable"), at = System.currentTimeMillis())
        for (server in servers) {
            // SoftEther uses 443, not the OpenVPN port advertised in the .ovpn profile.
            val result = com.mlmvpn.scanner.engines.vpngate.SoftEtherProbe.probe(server.ip, 443)
            if (result is com.mlmvpn.scanner.engines.vpngate.SoftEtherProbe.Result.Ok) return VariantResult(
                v.id, v.label, status = ScoutStatus.WORKS,
                reason = tr("تأیید تا پذیرش دست‌دهی؛ عبور داده سنجیده نشده", "Handshake accepted; data transfer not measured"),
                numbers = Numbers(connectMs = result.ms.toLong()), at = System.currentTimeMillis())
        }
        return VariantResult(v.id, v.label, status = ScoutStatus.FAILED,
            reason = tr("سرورهای نمونه دست‌دهی را کامل نکردند", "Sampled servers did not complete the handshake"), failedAt = Step.CONNECTING, at = System.currentTimeMillis())
    }
}

internal object OpenVpnAdapter : ScoutAdapter() {
    override val id = "openvpn"
    override val homeId = "openvpn"
    override val kind = MethodKind.TUNNEL
    override val evidence = Evidence.HANDSHAKE
    override val lanes = emptySet<Lane>()

    override fun howTested() = tr("بررسی پاسخ کنترل و TLS در OpenVPN؛ دریافت گواهی هنوز تأیید نمی‌شود.", "OpenVPN control/TLS response probe; certificate receipt is not proven.")
    private fun profiles(ctx: Context) = com.mlmvpn.scanner.openvpn.OpenVpnRepository.get(ctx).data.value.profiles
    override fun readiness(ctx: Context): Readiness = if (profiles(ctx).isEmpty())
        Readiness.NeedsSetup(tr("یک پروفایل OpenVPN اضافه کنید", "Add an OpenVPN profile")) else Readiness.Ready
    override fun variants(ctx: Context, line: LineSnapshot) = profiles(ctx)
        .sortedWith(compareByDescending<com.mlmvpn.scanner.openvpn.Profile> { it.favorite }.thenBy { it.probe?.millis ?: Long.MAX_VALUE })
        .take(6).mapIndexed { index, profile ->
            Variant(profile.id, tr("پروفایل ${index + 1}", "Profile ${index + 1}"), quick = index == 0, budgetMs = 35_000, payload = profile)
        }

    override suspend fun handshake(kit: ScoutKit, v: Variant): VariantResult {
        val profile = v.payload as com.mlmvpn.scanner.openvpn.Profile
        if (profile.remotes.isEmpty()) return VariantResult(v.id, v.label, status = ScoutStatus.INCONCLUSIVE,
            reason = tr("پروفایل مقصد ندارد", "Profile has no remote"), at = System.currentTimeMillis())
        val result = com.mlmvpn.scanner.openvpn.OpenVpnLatency.measure(profile)
        val verdict = ScoutHandshake.openVpn(result.millis, result.method, result.error)
        val why = when (verdict.status) {
            ScoutStatus.WORKS -> tr("گواهی سرور در دست‌دهی دریافت شد؛ عبور داده سنجیده نشده", "Server certificate flight received; data transfer not measured")
            ScoutStatus.INCONCLUSIVE -> tr("این آزمون دریافت گواهی را ثابت نمی‌کند؛ نتیجه برای تأیید اتصال کافی نیست", "This probe does not prove certificate receipt; connection is unconfirmed")
            else -> if (result.error == "TLS_BLOCKED") tr("دست‌دهی TLS کامل نشد", "TLS handshake did not finish") else tr("سرور پاسخی به دست‌دهی نداد", "Server did not answer the handshake")
        }
        return VariantResult(v.id, v.label, status = verdict.status, reason = why,
            failedAt = if (verdict.status == ScoutStatus.FAILED) Step.CONNECTING else null,
            numbers = Numbers(connectMs = verdict.millis), at = System.currentTimeMillis())
    }
}

internal object FenixAdapter : XrayAdapter() {
    override val id = "fenix"
    override val homeId = "fenix"
    override val kind = MethodKind.TUNNEL
    override val lanes = setOf(Lane.XRAY)

    override fun howTested() = tr("گذرگاه تازهٔ FENIX گرفته و در هستهٔ تست سنجیده می‌شود.", "A fresh FENIX pass is taken and measured in the test core.")
    override fun readiness(ctx: Context): Readiness {
        val has = runCatching {
            com.mlmvpn.scanner.engines.fenix.FenixEngine.init(ctx)
            com.mlmvpn.scanner.engines.fenix.FenixEngine.hasAccounts()
        }.getOrDefault(false)
        return if (has) Readiness.Ready else Readiness.NeedsSetup(tr("حساب فایرفاکس وارد نشده", "No Firefox account"))
    }
    override fun variants(ctx: Context, line: LineSnapshot) = listOf(Variant("fenix", "FENIX", quick = true, budgetMs = 90_000))

    override suspend fun prepare(kit: ScoutKit, v: Variant): Prepared {
        val config = withContext(Dispatchers.IO) { com.mlmvpn.scanner.engines.fenix.FenixEngine.probeConfig(kit.context) }
        return if (config == null) Prepared.Failed(ScoutStatus.INCONCLUSIVE,
            tr("گذرگاه قابل آزمایش دریافت نشد؛ وضعیت حساب و سرورها را در FENIX بررسی کنید", "No test route obtained; check the account and servers in FENIX"))
        else Prepared.Ready(XrayHandle(config, keepRouting = true))
    }
}

