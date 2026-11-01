package com.mlmvpn.scanner.widget.studio

import android.content.Context
import com.mlmvpn.scanner.backup.BackupContributor
import com.mlmvpn.scanner.backup.BackupKind
import com.mlmvpn.scanner.backup.RestoreMode
import com.mlmvpn.scanner.backup.RestorePlan
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.widget.WidgetFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * «ویجت استدیو» in the backup file (main settings): the user's designs and the method widgets'
 * look. Which design each placed widget wears is not exported -- a widget's id belongs to this
 * phone's launcher, and means nothing on another one.
 */
internal object StudioBackup : BackupContributor {

    private const val FILE = "files/widgets/studio.json"

    override val id: String = "main_widget_studio"
    override val kind: BackupKind = BackupKind.MAIN

    override fun title(): String = tr("طرح‌های ویجت استدیو", "Widget Studio designs")

    override fun export(ctx: Context): JSONObject? {
        val d = StudioStore.get(ctx)
        if (d.templates.isEmpty() && d.methodStyle == MethodStyle()) return null
        return JSONObject()
            .put("templates", JSONArray(d.templates.map { it.toJson() }))
            .put("methodStyle", d.methodStyle.toJson())
    }

    override fun summary(data: JSONObject): String? {
        val n = data.optJSONArray("templates")?.length() ?: 0
        return WidgetFormat.digits(tr("$n طرح", "$n designs"))
    }

    override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
        val incoming = StudioData.fromJson(
            JSONObject()
                .put("templates", data.optJSONArray("templates") ?: JSONArray())
                .put("methodStyle", data.optJSONObject("methodStyle") ?: JSONObject())
        )
        // What the phone has now -- or what an earlier item of this restore already planned for the
        // same file -- is the base the file's designs are laid over.
        val base = plan.planned(FILE)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { StudioData.fromJson(JSONObject(String(it))) }.getOrNull() }
            ?: StudioStore.get(ctx)
        val templates = when (mode) {
            // The file's copy of a design replaces the phone's; the phone's other designs stay.
            RestoreMode.MERGE -> incoming.templates + base.templates.filter { t -> incoming.templates.none { it.id == t.id } }
            RestoreMode.REPLACE -> incoming.templates
        }
        val ids = templates.map { it.id }.toSet()
        // This phone's placed widgets keep their designs where those designs are still there.
        val result = StudioData(templates, base.bindings.filterValues { it in ids }, incoming.methodStyle)
        plan.putFile(FILE, result.toJson().toString().toByteArray())
    }
}
