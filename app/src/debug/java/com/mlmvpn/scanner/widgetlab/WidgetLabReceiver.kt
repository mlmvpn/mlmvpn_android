package com.mlmvpn.scanner.widgetlab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.widget.MethodView
import com.mlmvpn.scanner.widget.WidgetLive
import com.mlmvpn.scanner.widget.WidgetMethods
import com.mlmvpn.scanner.widget.WidgetPainter
import com.mlmvpn.scanner.widget.studio.MethodStyle
import com.mlmvpn.scanner.widget.studio.StudioPainter
import com.mlmvpn.scanner.widget.studio.StudioPresets
import com.mlmvpn.scanner.widget.studio.StudioSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sin

/**
 * Debug only (shell permission): the widgets drawn to PNG files, for review without placing them.
 *
 *   adb shell am broadcast -n com.mlmvpn.scanner/.widgetlab.WidgetLabReceiver --es mode render
 *   adb shell am broadcast -n com.mlmvpn.scanner/.widgetlab.WidgetLabReceiver --es mode glyph --es name RocketLaunch
 *   adb shell am broadcast -n com.mlmvpn.scanner/.widgetlab.WidgetLabReceiver --es mode state
 *   adb shell am broadcast -n com.mlmvpn.scanner/.widgetlab.WidgetLabReceiver --es mode pin --es method wireguard
 *
 * Files land in files/widgetlab/; read one with `adb exec-out run-as com.mlmvpn.scanner cat files/widgetlab/<name>.png`.
 * Logs under tag WidgetLab.
 */
class WidgetLabReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.getStringExtra("mode")) {
            "render" -> labScope.launch { render(app, intent.getStringExtra("only")) }
            "glyph" -> {
                val v = when (intent.getStringExtra("name")) {
                    "VpnLock" -> Icons.Default.VpnLock
                    "Widgets" -> Icons.Default.Widgets
                    else -> Icons.Default.RocketLaunch
                }
                log("glyph ${v.name}: viewport ${v.viewportWidth}x${v.viewportHeight}")
                paths(v.root).forEach { log("glyph path: $it") }
            }
            "state" -> log("conn=${WidgetLive.conn.value} traffic.rx=${WidgetLive.traffic.value.rx}")
            "pin" -> {
                val m = intent.getStringExtra("method") ?: "wireguard"
                log("pin $m -> ${com.mlmvpn.scanner.widget.MethodWidgets.requestPin(app, m)}")
            }
            else -> log("unknown mode")
        }
    }

    private fun render(app: Context, only: String?) {
        val dir = File(app.filesDir, "widgetlab").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val density = app.resources.displayMetrics.density
        val rtl = AppLocaleManager.isFarsi()
        val now = System.currentTimeMillis()
        val down = LongArray(WidgetLive.HISTORY) { i -> (900_000 + 650_000 * sin(i / 4.0) + 300_000 * sin(i / 1.7)).toLong().coerceAtLeast(0) }
        val up = LongArray(WidgetLive.HISTORY) { i -> (220_000 + 120_000 * sin(i / 3.1 + 1)).toLong().coerceAtLeast(0) }
        val traffic = WidgetLive.Traffic(rx = 1_330_000_000, tx = 210_000_000, rxRate = down.last(), txRate = up.last(), down = down, up = up)
        val states = mapOf(
            "idle" to MethodView(WidgetLive.Phase.IDLE),
            "connecting" to MethodView(WidgetLive.Phase.CONNECTING),
            "on" to MethodView(WidgetLive.Phase.CONNECTED, since = now - 754_000, country = "DE", traffic = traffic),
        )
        var n = 0
        if (only == null || only == "method") {
            val methods = listOf("wireguard", "warp_on_warp", "psiphon", "quick", "emergency_3", "nodes")
            for (id in methods) {
                val a = WidgetMethods.app(id) ?: continue
                for ((sk, view) in states) {
                    for (card in listOf(false, true)) {
                        val style = MethodStyle(card = card)
                        save(dir, "m_${id}_${sk}_${if (card) "card" else "icon"}_1x1", WidgetPainter.paintMethod(app, a, style, view, 76f, 92f, density, rtl)); n++
                        save(dir, "m_${id}_${sk}_${if (card) "card" else "icon"}_2x1", WidgetPainter.paintMethod(app, a, style, view, 170f, 80f, density, rtl)); n++
                    }
                    save(dir, "m_${id}_${sk}_card_2x2", WidgetPainter.paintMethod(app, a, MethodStyle(card = true), view, 150f, 150f, density, rtl)); n++
                }
            }
        }
        if (only == null || only == "studio") {
            for (preset in StudioPresets.ALL) {
                for (size in StudioSize.entries) {
                    val t = preset.toTemplate("lab", "wireguard", "lab", now).copy(size = size,
                        show = preset.show + (if (size == StudioSize.LARGE) setOf(com.mlmvpn.scanner.widget.studio.Info.CHART, com.mlmvpn.scanner.widget.studio.Info.USAGE) else emptySet()))
                    val (w, h) = when (size) {
                        StudioSize.SMALL -> 158f to 158f
                        StudioSize.MEDIUM -> 330f to 150f
                        StudioSize.LARGE -> 330f to 330f
                    }
                    for ((sk, view) in states) {
                        if (sk == "connecting" && size != StudioSize.MEDIUM) continue
                        save(dir, "s_${preset.style.key}_${size.key}_$sk", StudioPainter.paint(app, t, view, w, h, density, rtl)); n++
                    }
                    // The cells a real launcher gives (One UI on a 6.4" phone): far taller than the
                    // design sizes, which is where a layout that only fits its own size shows it.
                    val (tw, th) = when (size) {
                        StudioSize.SMALL -> 185f to 243f
                        StudioSize.MEDIUM -> 371f to 243f
                        StudioSize.LARGE -> 371f to 486f
                    }
                    for (sk in listOf("idle", "on")) {
                        save(dir, "t_${preset.style.key}_${size.key}_$sk", StudioPainter.paint(app, t, states.getValue(sk), tw, th, density, rtl)); n++
                    }
                }
            }
        }
        log("render: $n files in ${dir.absolutePath}")
    }

    private fun save(dir: File, name: String, bmp: Bitmap) {
        runCatching { File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onFailure { log("save $name: ${it.message}") }
        bmp.recycle()
    }

    private fun paths(group: VectorGroup): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until group.size) {
            when (val node = group[i]) {
                is VectorGroup -> out += paths(node)
                is VectorPath -> out += node.pathData.joinToString("") { svg(it) }
            }
        }
        return out
    }

    private fun svg(n: PathNode): String = when (n) {
        is PathNode.MoveTo -> "M${n.x},${n.y}"
        is PathNode.RelativeMoveTo -> "m${n.dx},${n.dy}"
        is PathNode.LineTo -> "L${n.x},${n.y}"
        is PathNode.RelativeLineTo -> "l${n.dx},${n.dy}"
        is PathNode.HorizontalTo -> "H${n.x}"
        is PathNode.RelativeHorizontalTo -> "h${n.dx}"
        is PathNode.VerticalTo -> "V${n.y}"
        is PathNode.RelativeVerticalTo -> "v${n.dy}"
        is PathNode.CurveTo -> "C${n.x1},${n.y1} ${n.x2},${n.y2} ${n.x3},${n.y3}"
        is PathNode.RelativeCurveTo -> "c${n.dx1},${n.dy1} ${n.dx2},${n.dy2} ${n.dx3},${n.dy3}"
        is PathNode.ReflectiveCurveTo -> "S${n.x1},${n.y1} ${n.x2},${n.y2}"
        is PathNode.RelativeReflectiveCurveTo -> "s${n.dx1},${n.dy1} ${n.dx2},${n.dy2}"
        is PathNode.QuadTo -> "Q${n.x1},${n.y1} ${n.x2},${n.y2}"
        is PathNode.RelativeQuadTo -> "q${n.dx1},${n.dy1} ${n.dx2},${n.dy2}"
        is PathNode.ReflectiveQuadTo -> "T${n.x},${n.y}"
        is PathNode.RelativeReflectiveQuadTo -> "t${n.dx},${n.dy}"
        is PathNode.Close -> "Z"
        else -> " ?$n "
    }

    @Suppress("unused") private fun touch(v: ImageVector) = v.name

    private fun log(s: String) { Log.i("WidgetLab", s) }

    private companion object {
        val labScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
