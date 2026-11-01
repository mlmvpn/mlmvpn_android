package com.mlmvpn.scanner.engines.cloud

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.engines.gozargah.GozargahPanel
import com.mlmvpn.scanner.engines.netra.NetraPanel
import com.mlmvpn.scanner.engines.nova.NovaPanel
import com.mlmvpn.scanner.engines.spider.SpiderPanel
import com.mlmvpn.scanner.models.CloudAccount
import org.json.JSONObject
import java.util.Collections

/**
 * «هماهنگ با ویندوز»: the account's shared registry ([PanelRegistry]) → this phone, for every panel;
 * this phone's installs → the account when the registry does not know them yet. The Windows app does
 * the same from its side (panel-registry.js › sync), so both end up on ONE install per panel.
 *
 * Spider, Netra, Gozargah and Nova keep their records in their own preferences; BPB, Edge, Nahan and
 * the legacy MLM panel keep theirs on the [CloudAccount]. Config Studio accounts are left alone: that
 * engine is the phone's own (the Windows app does not install it).
 */
object PanelSync {

    private const val TAG = "PanelSync"
    private val done = Collections.synchronizedSet(mutableSetOf<String>())

    /** Once per app session per account — the Cloud tab calls this when it shows the account. */
    fun syncOnce(context: Context, account: CloudAccount): Boolean {
        if (!done.add(account.accountId)) return false
        syncAll(context, account)
        return true
    }

    /** Blocking; call on IO. Never throws: a panel that cannot be synced is left as it was. */
    fun syncAll(context: Context, account: CloudAccount) {
        runCatching { SpiderPanel.sync(context, account) }.onFailure { Log.w(TAG, "SPD", it) }
        runCatching { NetraPanel.sync(context, account) }.onFailure { Log.w(TAG, "NTR", it) }
        runCatching { GozargahPanel.sync(context, account) }.onFailure { Log.w(TAG, "GZG", it) }
        runCatching { NovaPanel.sync(context, account) }.onFailure { Log.w(TAG, "NVA", it) }
        // The account's ONE central Worker (key GW): the other device's Worker and the panels it connected, taken in.
        runCatching { com.mlmvpn.scanner.engines.gateway.EdgeGateway.syncBlocking(context, account) }.onFailure { Log.w(TAG, "GW", it) }
        var changed = false
        runCatching { changed = syncAccountFields(account) }.onFailure { Log.w(TAG, "fields", it) }
        if (changed) runCatching { CloudManager(context).saveAccounts() }
    }

    /** BPB, Edge, Nahan and legacy MLM — the fields on the account. True when any field moved. */
    private fun syncAccountFields(account: CloudAccount): Boolean {
        var changed = false
        // BPB
        val bpb = PanelRegistry.live(account, "BPB")
        if (bpb != null && bpb.s.optString("uuid").isNotBlank()) {
            if (account.workerUrl != bpb.url || account.uuid != bpb.s.optString("uuid")) {
                account.workerUrl = bpb.url
                account.uuid = bpb.s.optString("uuid")
                account.trPass = bpb.s.optString("trPass").ifBlank { account.trPass }
                account.subPath = bpb.s.optString("subPath").ifBlank { account.subPath }
                bpb.kv?.let { account.kvNamespaceId = it }
                account.status = "deployed"
                changed = true
            }
        } else if (!account.workerUrl.isNullOrBlank() && !account.uuid.isNullOrBlank()) {
            PanelRegistry.scriptOf(account.workerUrl)?.takeIf { PanelRegistry.scriptExists(account, it) }?.let { script ->
                PanelRegistry.publish(account, "BPB", script, account.workerUrl!!, account.kvNamespaceId, null,
                    JSONObject().put("uuid", account.uuid).put("trPass", account.trPass ?: "").put("subPath", account.subPath ?: ""))
            }
        }
        // Edge
        val edg = PanelRegistry.live(account, "EDG")
        if (edg != null && edg.s.optString("uuid").isNotBlank()) {
            if (account.edgWorkerUrl != edg.url || account.edgUuid != edg.s.optString("uuid")) {
                account.edgWorkerUrl = edg.url
                account.edgUuid = edg.s.optString("uuid")
                edg.kv?.let { account.edgKvNamespaceId = it }
                account.edgStatus = "deployed"
                changed = true
            }
        } else if (!account.edgWorkerUrl.isNullOrBlank() && !account.edgUuid.isNullOrBlank()) {
            PanelRegistry.scriptOf(account.edgWorkerUrl)?.takeIf { PanelRegistry.scriptExists(account, it) }?.let { script ->
                PanelRegistry.publish(account, "EDG", script, account.edgWorkerUrl!!, account.edgKvNamespaceId, null, JSONObject().put("uuid", account.edgUuid))
            }
        }
        // Nahan
        val nhn = PanelRegistry.live(account, "NHN")
        if (nhn != null) {
            val key = nhn.s.optString("masterKey").ifBlank { null }
            val route = nhn.s.optString("apiRoute").ifBlank { null }
            if (account.nahanWorkerUrl != nhn.url || (key != null && account.nahanMasterKey != key) || (route != null && account.nahanApiRoute != route)) {
                account.nahanWorkerUrl = nhn.url
                nhn.d1?.let { account.nahanDbId = it }
                key?.let { account.nahanMasterKey = it }
                route?.let { account.nahanApiRoute = it }
                account.nahanStatus = "deployed"
                changed = true
            }
        } else if (!account.nahanWorkerUrl.isNullOrBlank()) {
            PanelRegistry.scriptOf(account.nahanWorkerUrl)?.takeIf { PanelRegistry.scriptExists(account, it) }?.let { script ->
                PanelRegistry.publish(account, "NHN", script, account.nahanWorkerUrl!!, null, account.nahanDbId,
                    JSONObject().put("masterKey", account.nahanMasterKey ?: "admin").put("apiRoute", account.nahanApiRoute))
            }
        }
        // Legacy MLM (never on a Config Studio account: that engine is the phone's alone)
        if (!account.isStudioManaged) {
            val mlm = PanelRegistry.live(account, "MLM")
            if (mlm != null) {
                val pw = mlm.s.optString("password").ifBlank { null }
                if (account.mlmWorkerUrl != mlm.url || (pw != null && account.mlmAdminPassword != pw)) {
                    account.mlmWorkerUrl = mlm.url
                    mlm.d1?.let { account.mlmDbId = it }
                    pw?.let { account.mlmAdminPassword = it }
                    account.mlmStatus = "deployed"
                    changed = true
                }
            } else if (!account.mlmWorkerUrl.isNullOrBlank() && !account.mlmAdminPassword.isNullOrBlank()) {
                PanelRegistry.scriptOf(account.mlmWorkerUrl)?.takeIf { PanelRegistry.scriptExists(account, it) }?.let { script ->
                    PanelRegistry.publish(account, "MLM", script, account.mlmWorkerUrl!!, null, account.mlmDbId, JSONObject().put("password", account.mlmAdminPassword))
                }
            }
        }
        return changed
    }
}
