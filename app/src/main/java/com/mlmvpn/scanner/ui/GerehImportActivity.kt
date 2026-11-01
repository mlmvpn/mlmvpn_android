package com.mlmvpn.scanner.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import com.mlmvpn.scanner.data.GerehConfigLink
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.engines.amnezia.AmzEngine
import com.mlmvpn.scanner.engines.npv.NpvEngine
import com.mlmvpn.scanner.engines.outline.OutlineEngine
import com.mlmvpn.scanner.openvpn.OpenVpnRepository
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.widget.WidgetOpen
import org.json.JSONArray
import org.json.JSONObject
import org.mlmvpn.chat.bridge.GerehConfigs
import java.io.File
import java.util.UUID

/**
 * «انتقال» from a «گره» chat (n5): the configs a message or a file held go to the app's own sections,
 * here in the main process where those keep their lists (the chat runs in `:chat`), and the user is
 * asked whether to open the section most of them went to. Started over the chat, in its task, so
 * «بمان» goes straight back to it.
 *
 * The list comes as a file in the app's own folder: a text file of hundreds is more than an intent
 * may carry.
 */
class GerehImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return finish()
        val file = intent.getStringExtra(EXTRA_FILE)?.let { File(it) }
        val items = file?.let { runCatching { read(it) }.getOrNull() }.orEmpty()
        file?.delete()
        if (items.isEmpty()) return finish()
        val moved = LinkedHashMap<String, Int>()
        for ((kind, list) in items.groupBy { it.kind }) {
            val n = runCatching { importInto(kind, list) }.getOrElse { e ->
                problems += e.message ?: e.javaClass.simpleName
                0
            }
            if (n > 0) moved[kind] = n
        }
        // why something did not go in, as the section said it (an OpenVPN file it could not read)
        val why = problems.distinct().joinToString("\n").takeIf { it.isNotBlank() }
        if (moved.isEmpty()) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(why ?: tr("چیز تازه‌ای نبود: همه از پیش در MLMVPN هستند.", "Nothing new: they are all in MLMVPN already."))
                .setPositiveButton(tr("باشه", "OK")) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        val main = moved.maxByOrNull { it.value }!!.key
        val section = items.first { it.kind == main }.section
        val total = moved.values.sum()
        val text = tr("$total کانفیگ به «$section» رفت. به صفحهٔ آن برویم؟", "$total configs went to «$section». Open it?")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr("منتقل شد", "Moved"))
            .setMessage(if (why != null) "$text\n\n$why" else text)
            .setPositiveButton(tr("برو", "Open")) { _, _ ->
                WidgetOpen.open(this, main)
                finish()
            }
            .setNegativeButton(tr("بمان", "Stay")) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** How many arrived; the sections that keep their lists in the background report the count sent. */
    private fun importInto(kind: String, list: List<Item>): Int = when (kind) {
        GerehConfigLink.NODES -> NodeManager(this).addNodes(list.map { createNodeFromUri(it.raw) }, GROUP)
        GerehConfigLink.OUTLINE -> { OutlineEngine.importText(this, list.joinToString("\n") { it.raw }); list.size }
        GerehConfigLink.NPV -> list.sumOf {
            // an NPV Tunnel file comes as its path in the chat's files (same app, so readable here);
            // its name is the one the message showed, not the stored file's («2_86254….npvs»)
            val f = File(it.raw)
            val name = it.name.takeIf { n -> n.lowercase().let { l -> l.endsWith(".npvs") || l.endsWith(".npvt") } } ?: f.name
            if (it.raw.startsWith("/") && f.isFile) NpvEngine.importFile(this, name, f.readBytes()) else NpvEngine.importText(this, it.raw)
        }
        GerehConfigLink.AMNEZIA -> { list.forEach { AmzEngine.importText(this, it.raw) }; list.size }
        GerehConfigLink.OPENVPN -> OpenVpnRepository.get(this).importFiles(list.associate { ovpnName(it.name) to it.raw }).let { (n, errors) ->
            problems += errors
            n
        }
        else -> 0
    }

    /** What a section could not take, with its own reason. */
    private val problems = mutableListOf<String>()

    private fun ovpnName(name: String) = if (name.endsWith(".ovpn", true)) name else "$name.ovpn"

    private class Item(val kind: String, val section: String, val name: String, val raw: String)

    private fun read(file: File): List<Item> {
        val a = JSONArray(file.readText())
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Item(o.getString("kind"), o.getString("section"), o.getString("name"), o.getString("raw"))
        }
    }

    companion object {
        private const val EXTRA_FILE = "gereh_configs"
        /** The node list's group for what came from a chat. */
        private const val GROUP = "گره"

        /** From the chat's process: write the list where the main process can read it, and start over the chat. */
        fun start(activity: Activity, configs: List<GerehConfigs.Config>) {
            val dir = File(activity.filesDir, "gereh-import").apply { mkdirs() }
            val file = File(dir, UUID.randomUUID().toString() + ".json")
            val a = JSONArray()
            configs.forEach { c ->
                a.put(JSONObject().put("kind", c.kind).put("section", c.section).put("name", c.name).put("raw", c.raw))
            }
            file.writeText(a.toString())
            activity.startActivity(Intent(activity, GerehImportActivity::class.java).putExtra(EXTRA_FILE, file.absolutePath))
        }
    }
}
