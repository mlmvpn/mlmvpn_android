package com.mlmvpn.scanner.widget.studio

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.home.AppIconTile
import com.mlmvpn.scanner.ui.widgetstudio.PresetCard
import com.mlmvpn.scanner.ui.widgetstudio.PreviewStage
import com.mlmvpn.scanner.ui.widgetstudio.PreviewState
import com.mlmvpn.scanner.ui.widgetstudio.StudioLaunch
import com.mlmvpn.scanner.ui.widgetstudio.TemplatePreview
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.widget.WidgetLive
import com.mlmvpn.scanner.widget.WidgetMethods
import com.mlmvpn.scanner.widget.WidgetOpen
import com.mlmvpn.scanner.widget.WidgetPainter
import com.mlmvpn.scanner.widget.WidgetUpdater

/**
 * Opened by the launcher when a studio widget is placed from the widget picker (or reconfigured on
 * Android 12+): the user says which design it wears. A widget placed from inside the studio already
 * has its design waiting (StudioStore's pending pin), and is bound here without showing anything.
 *
 * Exported, as the launcher must reach it; so it binds only a widget id that belongs to this app.
 */
class StudioConfigureActivity : ComponentActivity() {

    companion object {
        /** Opened from our own widget (a deleted design): choosing again, not a first placement. */
        const val EXTRA_RECONFIGURE = "studio_reconfigure"
    }

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun attachBaseContext(newBase: Context) {
        AppLocaleManager.init(newBase)
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out of a first placement removes the widget again -- the platform's contract.
        setResult(RESULT_CANCELED, resultData())
        val mgr = AppWidgetManager.getInstance(this)
        val info = runCatching { mgr.getAppWidgetInfo(widgetId) }.getOrNull()
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || info == null || info.provider.packageName != packageName) {
            finish()
            return
        }
        val size = StudioWidgetProvider.sizeOf(info.provider) ?: StudioSize.MEDIUM
        val reconfigure = intent?.getBooleanExtra(EXTRA_RECONFIGURE, false) == true
        if (!reconfigure) {
            // Placed from the studio: its design is waiting, or the provider already bound it.
            if (StudioStore.isBound(this, widgetId)) { done(); return }
            StudioStore.takePending()?.let { StudioStore.bind(this, widgetId, it); done(); return }
        }
        val rtl = AppLocaleManager.isFarsi()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    ConfigureScreen(
                        size = size,
                        onPick = { id -> StudioStore.bind(this, widgetId, id); done() },
                        onPreset = { t -> StudioStore.save(this, t); StudioStore.bind(this, widgetId, t.id); done() },
                        onStudio = { t ->
                            // A full design wanted: bind a starting one now, then open the studio on it.
                            StudioStore.save(this, t)
                            StudioStore.bind(this, widgetId, t.id)
                            StudioLaunch.editTemplate = t.id
                            WidgetOpen.open(this, "widgetstudio")
                            done()
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun resultData() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun done() {
        setResult(RESULT_OK, resultData())
        WidgetUpdater.attach(applicationContext)
        WidgetUpdater.refreshAllAsync(applicationContext)
        finish()
    }
}

@Composable
private fun ConfigureScreen(
    size: StudioSize,
    onPick: (String) -> Unit,
    onPreset: (WidgetTemplate) -> Unit,
    onStudio: (WidgetTemplate) -> Unit,
    onCancel: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val data = remember { StudioStore.get(context) }
    var method by remember {
        mutableStateOf(
            WidgetLive.conn.value.method?.takeIf { WidgetMethods.isMethod(it) }
                ?: "wireguard".takeIf { WidgetMethods.isMethod(it) } ?: WidgetMethods.all().first().id
        )
    }
    fun fresh(style: StudioStyle): WidgetTemplate {
        val name = style.title + " · " + (WidgetMethods.app(method)?.let { WidgetPainter.labelOf(it) } ?: method)
        return StudioPresets.of(style).toTemplate(StudioStore.newId(), method, name, System.currentTimeMillis()).copy(size = size)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101114))
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        Text(tr("ویجت استدیو", "Widget Studio"), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Text(tr("یک طرح برای این ویجت انتخاب کنید", "Choose a design for this widget"), color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

        val mine = data.templates
        if (mine.isNotEmpty()) {
            Heading(tr("طرح‌های من", "My designs"))
            mine.forEach { t ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .clickable { onPick(t.id) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.width(120.dp)) { TemplatePreview(t.copy(size = size), PreviewState.CONNECTED, width = if (size == StudioSize.SMALL) 70.dp else 120.dp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name.ifBlank { t.style.title }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(WidgetMethods.app(t.method)?.let { WidgetPainter.labelOf(it) } ?: t.method, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }

        Heading(tr("یک طرح آماده برای این روش", "A ready-made look for this method"))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WidgetMethods.all().forEach { app ->
                val on = app.id == method
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { method = app.id }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // No lamp for the chosen one: green on a tile means connected everywhere in the
                    // app; the highlight behind it is what says chosen.
                    AppIconTile(app = app, size = 40.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(WidgetPainter.labelOf(app), color = Color.White, fontSize = 10.5.sp, maxLines = 1, textAlign = TextAlign.Center,
                        modifier = Modifier.width(58.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Stable samples for the pictures (a fresh id on every pass would redraw them for nothing);
        // the design that is kept gets its own id when it is picked.
        val samples = remember(method, size) {
            StudioPresets.ALL.map { p -> p.toTemplate("sample_" + p.style.key, method, "", 0).copy(size = size) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            samples.forEach { sample ->
                PresetCard(sample, sample.style.title, onClick = { onPreset(fresh(sample.style)) })
            }
        }

        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0A84FF))
                .clickable { onStudio(fresh(StudioStyle.GLASS)) }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(tr("طراحی کامل در ویجت استدیو", "Design it fully in Widget Studio"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            tr("انصراف", "Cancel"), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onCancel).padding(12.dp),
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp))
}
