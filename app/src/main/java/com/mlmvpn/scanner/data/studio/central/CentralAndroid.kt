package com.mlmvpn.scanner.data.studio.central

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.gateway.EdgeGateway
import com.mlmvpn.scanner.engines.gateway.GatewayException
import com.mlmvpn.scanner.engines.gateway.GatewayStatus
import com.mlmvpn.scanner.models.CloudAccount
import com.mlmvpn.scanner.utils.AppLocaleManager

/** The real account behind [CentralCloud]: the registry ([CentralRegistry]) and the central Worker ([EdgeGateway]). */
class CentralAndroidCloud(private val context: Context, private val account: CloudAccount) : CentralCloud {
    override fun registryGet(): RegistryRead = CentralRegistry.read(account)
    override fun registryPut(script: String, url: String, s: org.json.JSONObject) = CentralRegistry.put(account, script, url, s)
    override fun registryRemove(script: String) = CentralRegistry.removeIf(account, script)
    override fun gateway(): GatewayView = EdgeGateway.studioStatusBlocking(context, account).toView()
    override fun apply(ids: List<String>, onStep: (String) -> Unit): GatewayView =
        EdgeGateway.studioApplyBlocking(context, account, ids, onStep).toView()
}

internal fun GatewayStatus.toView() = GatewayView(installed, script, host, studioRoom, studioMark)

/** The engine for one account of the fleet, over its real record and its file on this phone. */
internal fun centralEngine(context: Context, account: CloudAccount): CentralEngine {
    CentralEngine.language = { AppLocaleManager.getResolvedLocale().language }
    val localId = Central.accountIdOf(account.id)
    return CentralEngine(localId, account.accountId, CentralAndroidCloud(context, account), CentralFiles.local(context, localId))
}

/**
 * The ids an upload of the central Worker must carry for «کانفیگ استدیو», when the upload is NOT the
 * Studio's own — a panel connected from the Cloud tab, the code updated, a new Proxy IP. Asked by
 * [EdgeGateway] for every such upload, so no screen can take a person off by uploading for another
 * reason.
 *
 * This phone's copy and the account's record are merged (the same rule as everywhere: the later
 * change wins); either alone is used when it is all there is. A record about another Worker is
 * ignored — its ids would not open this one.
 *
 * When neither can be read but the account's Worker record says Studio people ARE on the Worker
 * ([studioMark] not empty), the upload is refused: going on would take them off without anyone
 * having decided that.
 */
object CentralUploadIds {
    private const val TAG = "CentralUploadIds"

    fun forUpload(context: Context, account: CloudAccount, script: String, studioMark: String, now: Long = System.currentTimeMillis()): List<String> {
        val localId = Central.accountIdOf(account.id)
        val mine = CentralFiles.read(context, localId)
            ?: CentralFiles.all(context).firstOrNull { it.cloudAccountId == account.accountId }
        val mineOk = mine?.takeIf { it.script.isBlank() || it.script == script }
        val read = try {
            CentralRegistry.read(account)
        } catch (e: Exception) {
            Log.w(TAG, "registry unreadable: ${e.message}")
            if (mineOk != null) return mineOk.liveIds(now)
            if (studioMark.isNotEmpty()) throw GatewayException(
                "فهرست کاربران «کانفیگ استدیو» این حساب خوانده نشد؛ بارگذاری ورکر آن‌ها را قطع می‌کرد، پس انجام نشد. کمی بعد دوباره امتحان کنید.",
                "This account's Config Studio list could not be read; uploading the Worker would have cut those people off, so it was not done. Try again shortly.",
            )
            return emptyList()
        }
        val found = (read as? RegistryRead.Found)?.takeIf { it.script.isBlank() || it.script == script }
        return when {
            mineOk != null && found != null -> CentralDoc.merge(mineOk, found.s, found.at).liveIds(now)
            mineOk != null -> mineOk.liveIds(now)
            found != null -> CentralDoc.sharedLiveIds(found.s, now)
            else -> emptyList()
        }
    }
}
