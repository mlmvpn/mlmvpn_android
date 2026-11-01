package com.mlmvpn.scanner.data.studio.central

import android.content.Context
import com.mlmvpn.scanner.backup.BackupContributor
import com.mlmvpn.scanner.backup.BackupKind
import com.mlmvpn.scanner.backup.RestoreMode
import com.mlmvpn.scanner.backup.RestorePlan
import com.mlmvpn.scanner.backup.countLabel
import com.mlmvpn.scanner.backup.objects
import com.mlmvpn.scanner.store.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * «کانفیگ استدیو» on the central Worker, in the backup file (accounts): this phone's copy of each
 * account's people, «بسته»‌ها and contact line (`files/studio_central/`). The phone is where those
 * people live -- the central Worker only knows ids -- so a phone that is lost without a backup would
 * leave them with nobody to renew or end them but the account's own record.
 *
 * What this phone did ([CentralDoc.audit]) and which ids it last uploaded ([CentralDoc.appliedIds])
 * are facts about this phone, not data, and are not exported.
 *
 * A restore never touches the network. The account's record is merged in on the next look and the
 * Worker brought in step by the next check ([CentralEngine.reconcile]); a merge keeps the newer copy
 * of each person, so an old backup cannot bring back someone since deleted or turned off.
 */
internal object CentralBackup : BackupContributor {

    override val id: String = "acc_studio_central"
    override val kind: BackupKind = BackupKind.ACCOUNTS

    override fun title(): String = tr("کاربران کانفیگ استدیو روی ورکر مرکزی", "Config Studio people on the central Worker")

    private fun pathOf(accountId: String) = "files/${CentralFiles.DIR}/${CentralFiles.fileName(accountId)}"

    override fun export(ctx: Context): JSONObject? {
        val docs = CentralFiles.all(ctx).filter { it.users.isNotEmpty() || it.plans.isNotEmpty() || it.contact != null }
        if (docs.isEmpty()) return null
        val out = JSONArray()
        for (d in docs) out.put(d.copy(appliedIds = emptyList(), appliedAt = 0L, audit = emptyList(), unshared = false).toLocal())
        return JSONObject().put("docs", out)
    }

    override fun summary(data: JSONObject): String? {
        val docs = data.optJSONArray("docs")?.objects().orEmpty().mapNotNull { CentralDoc.fromLocal(it) }
        return countLabel(docs.sumOf { it.people.size }, "کاربر", "people")
    }

    override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
        val incoming = data.optJSONArray("docs")?.objects().orEmpty().map {
            CentralDoc.fromLocal(it) ?: throw IllegalArgumentException("unreadable Config Studio record")
        }
        val phone = CentralFiles.all(ctx)
        val kept = HashSet<String>()
        for (doc in incoming) {
            val path = pathOf(doc.accountId)
            // What an earlier item of this restore planned for the file, else the phone's copy -- of
            // this account, or of the same Cloudflare account under another local id.
            val planned = plan.planned(path)?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { CentralDoc.fromLocal(JSONObject(String(it, Charsets.UTF_8))) }.getOrNull() }
            val base = planned ?: phone.firstOrNull { it.accountId == doc.accountId }
                ?: phone.firstOrNull { doc.cloudAccountId.isNotBlank() && it.cloudAccountId == doc.cloudAccountId }
            val result = when {
                base == null -> doc
                // Person by person, the newer copy: an old backup does not undo a deletion or a renewal.
                mode == RestoreMode.MERGE -> CentralDoc.merge(base, doc.toLocal(), remoteAt = 0L).copy(
                    accountId = doc.accountId,
                    cloudAccountId = base.cloudAccountId.ifBlank { doc.cloudAccountId },
                    script = base.script.ifBlank { doc.script },
                    host = base.host.ifBlank { doc.host },
                )
                // The file's people and plans; what this phone knows about its own uploads stays.
                else -> doc.copy(appliedIds = base.appliedIds, appliedAt = base.appliedAt, audit = base.audit)
            }
            plan.putFile(path, result.toLocal().toString().toByteArray(Charsets.UTF_8))
            kept += CentralFiles.fileName(doc.accountId)
            // The same account's copy under another local id is folded into this one, not left beside it.
            if (base != null && base.accountId != doc.accountId && CentralFiles.fileName(base.accountId) !in kept) {
                plan.deleteFile(pathOf(base.accountId))
            }
        }
        if (mode == RestoreMode.REPLACE) {
            for (d in phone) if (CentralFiles.fileName(d.accountId) !in kept) plan.deleteFile(pathOf(d.accountId))
        }
    }
}
