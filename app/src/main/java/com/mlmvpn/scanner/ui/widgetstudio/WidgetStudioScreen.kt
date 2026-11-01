package com.mlmvpn.scanner.ui.widgetstudio

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.LocalTabVisible
import com.mlmvpn.scanner.ui.home.AppIconTile
import com.mlmvpn.scanner.ui.home.Appearance
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.IosAlert
import com.mlmvpn.scanner.ui.settings.IosAlertAction
import com.mlmvpn.scanner.ui.settings.IosScreen
import com.mlmvpn.scanner.ui.settings.SettingsActionRow
import com.mlmvpn.scanner.ui.settings.SettingsFooter
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow
import com.mlmvpn.scanner.ui.settings.SettingsSectionHeader
import com.mlmvpn.scanner.ui.settings.SettingsSlider
import com.mlmvpn.scanner.ui.settings.SettingsTextRow
import com.mlmvpn.scanner.ui.settings.SettingsToggle
import com.mlmvpn.scanner.chat.MlmChat
import com.mlmvpn.scanner.widget.GerehWidgets
import com.mlmvpn.scanner.widget.MethodWidgets
import com.mlmvpn.scanner.widget.WidgetConnect
import com.mlmvpn.scanner.widget.WidgetFormat
import com.mlmvpn.scanner.widget.WidgetLive
import com.mlmvpn.scanner.widget.WidgetMethods
import com.mlmvpn.scanner.widget.WidgetPainter
import com.mlmvpn.scanner.widget.WidgetUpdater
import com.mlmvpn.scanner.widget.studio.ButtonStyle
import com.mlmvpn.scanner.widget.studio.Info
import com.mlmvpn.scanner.widget.studio.MethodStyle
import com.mlmvpn.scanner.widget.studio.StudioPin
import com.mlmvpn.scanner.widget.studio.StudioPresets
import com.mlmvpn.scanner.widget.studio.StudioSize
import com.mlmvpn.scanner.widget.studio.StudioStore
import com.mlmvpn.scanner.widget.studio.StudioStyle
import com.mlmvpn.scanner.widget.studio.WidgetTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page { MAIN, EDITOR, METHOD, COUNTRY, ADD_METHOD }

/** Something outside the studio asking it to open a design (the widget's own configuration page). */
object StudioLaunch {
    var editTemplate by mutableStateOf<String?>(null)
}

/**
 * «ویجت استدیو»: where the user designs widgets for the phone's home screen, and where everything
 * about widgets lives -- the in-app connection widget's switch, the look of the plain method
 * widgets, and the designs themselves.
 *
 * A design is edited as a draft and saved explicitly (the way a designer expects, and what the
 * "save the template" in the request asked for); every placed widget wearing it is redrawn on save.
 */
@Composable
fun WidgetStudioScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val visible = LocalTabVisible.current
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { StudioStore.get(context) }
        loaded = true
    }
    val data by StudioStore.data.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var draft by remember { mutableStateOf<WidgetTemplate?>(null) }
    var draftIsNew by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var manualHelp by remember { mutableStateOf(false) }
    // The editor's tab and preview state live here, not in the editor: picking a method or a
    // country leaves the editor for a page of its own, and coming back must find the same tab.
    var editorTab by rememberSaveable { mutableStateOf(Tab.LOOK) }
    var preview by remember { mutableStateOf(PreviewState.CONNECTED) }

    fun openEditor(t: WidgetTemplate, isNew: Boolean) {
        draft = t
        draftIsNew = isNew
        dirty = isNew
        editorTab = Tab.LOOK
        preview = PreviewState.CONNECTED
        page = Page.EDITOR
    }

    fun newFrom(style: StudioStyle) {
        val method = WidgetLive.conn.value.method?.takeIf { WidgetMethods.isMethod(it) }
            ?: "wireguard".takeIf { WidgetMethods.isMethod(it) } ?: WidgetMethods.IDS.first { WidgetMethods.isMethod(it) }
        // No name yet: the field shows the automatic one, which follows the look and the method
        // until the user types a name of their own; saving writes it in.
        openEditor(StudioPresets.of(style).toTemplate(StudioStore.newId(), method, "", System.currentTimeMillis()), isNew = true)
    }

    /** [next], renamed along with its look or method while it still wears its automatic name. */
    fun follow(prev: WidgetTemplate, next: WidgetTemplate): WidgetTemplate =
        if (next.name == prev.name && prev.name == autoName(prev) && (next.style != prev.style || next.method != prev.method))
            next.copy(name = autoName(next)) else next

    // The configuration page of a placed widget can ask for one of its designs here.
    LaunchedEffect(StudioLaunch.editTemplate, loaded) {
        val id = StudioLaunch.editTemplate ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        StudioLaunch.editTemplate = null
        data.template(id)?.let { openEditor(it, isNew = false) }
    }

    fun save(): WidgetTemplate? {
        val t = draft ?: return null
        val named = if (t.name.isBlank()) t.copy(name = autoName(t)) else t
        StudioStore.save(context, named)
        draft = StudioStore.get(context).template(named.id) ?: named
        draftIsNew = false
        dirty = false
        WidgetUpdater.refreshAllAsync(context)
        return draft
    }

    fun leaveEditor() {
        if (dirty) askDiscard = true else { page = Page.MAIN; draft = null }
    }

    BackHandler(enabled = visible && page != Page.MAIN) {
        when (page) {
            Page.EDITOR -> leaveEditor()
            Page.METHOD, Page.COUNTRY -> page = Page.EDITOR
            else -> page = Page.MAIN
        }
    }

    when (page) {
        Page.MAIN -> StudioMain(
            onBack = onBack,
            templates = data.templates,
            methodStyle = data.methodStyle,
            placed = { id -> data.bindings.values.count { it == id } },
            onNew = { newFrom(StudioStyle.GLASS) },
            onPreset = { newFrom(it) },
            onEdit = { openEditor(it, isNew = false) },
            onPin = { t ->
                if (!StudioPin.request(context, t)) manualHelp = true
            },
            onMethodStyle = { StudioStore.setMethodStyle(context, it); WidgetUpdater.refreshAllAsync(context) },
            onAddMethod = { page = Page.ADD_METHOD },
        )
        Page.EDITOR -> draft.let { d ->
            if (d == null) { LaunchedEffect(Unit) { page = Page.MAIN }; return@let }
            StudioEditor(
                draft = d,
                isNew = draftIsNew,
                placed = data.bindings.values.count { it == d.id },
                tab = editorTab,
                onTab = { editorTab = it },
                preview = preview,
                onPreview = { preview = it },
                onChange = { draft = follow(d, it); dirty = true },
                onBack = { leaveEditor() },
                onSave = {
                    save()
                    Toast.makeText(context, tr("طرح ذخیره شد", "Design saved"), Toast.LENGTH_SHORT).show()
                },
                onPin = {
                    val saved = save()
                    if (saved != null && !StudioPin.request(context, saved)) manualHelp = true
                },
                onDuplicate = {
                    save()
                    StudioStore.duplicate(context, d.id, tr("(کپی)", "(copy)"))?.let { openEditor(it, isNew = false) }
                },
                onDelete = {
                    StudioStore.delete(context, d.id)
                    WidgetUpdater.refreshAllAsync(context)
                    draft = null
                    dirty = false
                    page = Page.MAIN
                },
                onMethod = { page = Page.METHOD },
                onCountry = { page = Page.COUNTRY },
            )
        }
        Page.METHOD -> draft.let { d ->
            if (d == null) { LaunchedEffect(Unit) { page = Page.MAIN }; return@let }
            MethodPicker(
                selected = d.method,
                onPick = { m ->
                    // A country chosen for another method means nothing to this one.
                    draft = follow(d, d.copy(method = m, country = if (m == d.method) d.country else null))
                    dirty = true
                    page = Page.EDITOR
                },
                onBack = { page = Page.EDITOR },
            )
        }
        Page.COUNTRY -> draft.let { d ->
            if (d == null) { LaunchedEffect(Unit) { page = Page.MAIN }; return@let }
            CountryPicker(
                method = d.method,
                selected = d.country,
                onPick = { cc ->
                    draft = d.copy(country = cc)
                    dirty = true
                    page = Page.EDITOR
                },
                onBack = { page = Page.EDITOR },
            )
        }
        Page.ADD_METHOD -> AddMethodWidget(
            onBack = { page = Page.MAIN },
            onAdd = { m ->
                val placed = if (m == MlmChat.TILE_ID) GerehWidgets.requestPin(context) else MethodWidgets.requestPin(context, m)
                if (!placed) manualHelp = true
            },
        )
    }

    if (askDiscard) {
        IosAlert(
            title = tr("تغییرات ذخیره نشده‌اند", "Changes are not saved"),
            message = tr("طرح را پیش از بیرون رفتن ذخیره کنیم؟", "Save the design before leaving?"),
            actions = listOf(
                IosAlertAction(tr("دور ریختن", "Discard"), onClick = {
                    askDiscard = false; dirty = false; draft = null; page = Page.MAIN
                }, destructive = true),
                IosAlertAction(tr("ذخیره", "Save"), onClick = {
                    askDiscard = false; save(); draft = null; page = Page.MAIN
                }),
            ),
            onDismiss = { askDiscard = false },
        )
    }
    if (manualHelp) {
        IosAlert(
            title = tr("افزودن دستی ویجت", "Adding the widget by hand"),
            message = tr(
                "صفحهٔ اصلی گوشی خودکار ویجت را قبول نکرد. جای خالی صفحهٔ اصلی را چند ثانیه نگه دارید، «ویجت‌ها» را بزنید، MLMVPN را پیدا کنید و ویجت دلخواه را روی صفحه بکشید.",
                "The phone's home screen did not accept the widget automatically. Long-press an empty spot on the home screen, tap «Widgets», find MLMVPN and drag the widget you want onto the screen.",
            ),
            actions = listOf(IosAlertAction(tr("باشه", "OK"), onClick = { manualHelp = false })),
            onDismiss = { manualHelp = false },
        )
    }
}

private fun methodTitle(id: String): String = WidgetMethods.app(id)?.let { WidgetPainter.labelOf(it) } ?: id

/** The name a design gets when the user gives it none: its look and its method. */
private fun autoName(t: WidgetTemplate): String = t.style.title + " · " + methodTitle(t.method)

// ---------------------------------------------------------------------------------------------------
// The studio's first page
// ---------------------------------------------------------------------------------------------------

@Composable
private fun StudioMain(
    onBack: () -> Unit,
    templates: List<WidgetTemplate>,
    methodStyle: MethodStyle,
    placed: (String) -> Int,
    onNew: () -> Unit,
    onPreset: (StudioStyle) -> Unit,
    onEdit: (WidgetTemplate) -> Unit,
    onPin: (WidgetTemplate) -> Unit,
    onMethodStyle: (MethodStyle) -> Unit,
    onAddMethod: () -> Unit,
) {
    val context = LocalContext.current
    IosScreen(title = tr("ویجت استدیو", "Widget Studio"), onBack = onBack, backLabel = tr("خانه", "Home")) {
        Spacer(Modifier.height(10.dp))
        // The hero: the user's newest design, live, or the gallery's first look until there is one.
        val hero = templates.firstOrNull()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PreviewStage {
                val sample = hero ?: StudioPresets.of(StudioStyle.GLASS).toTemplate("hero", "wireguard".takeIf { WidgetMethods.isMethod(it) } ?: WidgetMethods.IDS.first(), "", 0)
                TemplatePreview(sample, if (hero != null) PreviewState.LIVE else PreviewState.CONNECTED)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                tr("ویجت دلخواه خودتان را برای صفحهٔ اصلی گوشی بسازید", "Make a widget of your own for the phone's home screen"),
                color = Ios.Label, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr("رنگ، شیشه‌ای بودن، دکمهٔ اتصال، کشور و هر اطلاعاتی که می‌خواهید؛ هر چند طرح که بخواهید.",
                    "Colours, glass, the connect button, the country and whatever it shows; as many designs as you like."),
                color = Ios.SecondaryLabel, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(tr("ساخت ویجت جدید", "New widget"), onClick = onNew)
        }

        SettingsSectionHeader(tr("ویجت داخل برنامه", "In-app widget"))
        SettingsGroup {
            SettingsToggle(
                title = tr("ویجت اتصال در صفحهٔ اصلی برنامه", "Connection widget on the app's home"),
                subtitle = tr("روش متصل، کشور خروج، نمودار زنده، مصرف و دکمهٔ قطع", "The method on, its exit country, a live chart, usage and a disconnect button"),
                checked = Appearance.homeWidget,
                onCheckedChange = { Appearance.setHomeWidget(context, it) },
                icon = Icons.Default.ViewQuilt,
                tint = Ios.Blue,
            )
        }
        SettingsFooter(tr("همین کلید در تنظیمات › صفحه نمایش هم هست.", "The same switch is in Settings › Display."))

        if (templates.isNotEmpty()) {
            SettingsSectionHeader(tr("طرح‌های من", "My designs"))
            SettingsGroup {
                templates.forEachIndexed { i, t ->
                    if (i > 0) Separator()
                    TemplateRow(t, placed(t.id), onClick = { onEdit(t) }, onPin = { onPin(t) })
                }
            }
        }

        SettingsSectionHeader(tr("طرح‌های آماده", "Ready-made looks"))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StudioPresets.ALL.forEach { p ->
                val sample = p.toTemplate("p_" + p.style.key, "wireguard".takeIf { WidgetMethods.isMethod(it) } ?: WidgetMethods.IDS.first(), "", 0)
                PresetCard(sample, p.style.title, onClick = { onPreset(p.style) })
            }
        }

        SettingsSectionHeader(tr("ویجت روش‌ها", "Method widgets"))
        MethodStylePreview(methodStyle)
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingsRow(
                title = tr("افزودن ویجت یک روش به صفحهٔ گوشی", "Add a method's widget to the home screen"),
                icon = Icons.Default.AddToHomeScreen,
                tint = Ios.Green,
                onClick = onAddMethod,
            )
            Separator()
            SettingsToggle(
                title = tr("قاب شیشه‌ای", "Glass card"),
                subtitle = tr("خاموش: فقط آیکون، مثل آیکون برنامه‌ها", "Off: the icon alone, like an app icon"),
                checked = methodStyle.card,
                onCheckedChange = { onMethodStyle(methodStyle.copy(card = it)) },
                icon = Icons.Default.CropSquare,
                tint = Ios.Teal,
            )
            Separator()
            SettingsToggle(
                title = tr("نام زیر آیکون", "Name under the icon"),
                checked = methodStyle.label,
                onCheckedChange = { onMethodStyle(methodStyle.copy(label = it)) },
                icon = Icons.Default.Label,
                tint = Ios.Purple,
            )
            Separator()
            SettingsToggle(
                title = tr("چراغ وضعیت", "Status lamp"),
                subtitle = tr("سبز: متصل · نارنجی: در حال اتصال", "Green: connected · orange: connecting"),
                checked = methodStyle.status,
                onCheckedChange = { onMethodStyle(methodStyle.copy(status = it)) },
                icon = Icons.Default.Lightbulb,
                tint = Ios.Yellow,
            )
            Separator()
            SettingsToggle(
                title = tr("لمس ویجت، وصل یا قطع می‌کند", "A tap connects or disconnects"),
                subtitle = tr("خاموش: لمس، صفحهٔ همان روش را در برنامه باز می‌کند", "Off: a tap opens that method's page in the app"),
                checked = methodStyle.tapConnects,
                onCheckedChange = { onMethodStyle(methodStyle.copy(tapConnects = it)) },
                icon = Icons.Default.TouchApp,
                tint = Ios.Orange,
            )
        }
        if (methodStyle.card) {
            Spacer(Modifier.height(12.dp))
            SettingsSlider(
                title = tr("پررنگی قاب", "Card solidity"),
                value = methodStyle.opacity,
                valueLabel = WidgetFormat.digits("${methodStyle.opacity}%"),
                range = 0f..100f,
                onValueChange = { onMethodStyle(methodStyle.copy(opacity = it)) },
            )
        }
        SettingsFooter(tr(
            "هر کاشی اتصال برنامه یک ویجت هم‌شکل خودش دارد. برای افزودن دستی: جای خالی صفحهٔ اصلی گوشی را نگه دارید › ویجت‌ها › MLMVPN.",
            "Every connection tile of the app has a widget that looks like it. To add one by hand: long-press an empty spot on the home screen › Widgets › MLMVPN.",
        ))
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Ios.Blue else Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun com.mlmvpn.scanner.ui.settings.SettingsGroupScope.TemplateRow(t: WidgetTemplate, placed: Int, onClick: () -> Unit, onPin: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(110.dp), contentAlignment = Alignment.Center) {
            TemplatePreview(t, PreviewState.LIVE, width = if (t.size == StudioSize.SMALL) 64.dp else 110.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(t.name.ifBlank { t.style.title }, color = Ios.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val country = t.country?.let { WidgetFormat.flag(it) + " " + WidgetFormat.countryName(it) } ?: tr("کشور خودکار", "Auto country")
            Text(methodTitle(t.method) + " · " + country, color = Ios.SecondaryLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                WidgetFormat.joined(t.size.title,
                    if (placed > 0) WidgetFormat.digits(tr("$placed ویجت روی صفحه", "$placed on the home screen")) else tr("هنوز روی صفحه نیست", "Not on the home screen yet")),
                color = Ios.SecondaryLabel, fontSize = 11.5.sp, maxLines = 1,
            )
        }
        Icon(
            Icons.Default.AddToHomeScreen,
            contentDescription = tr("افزودن به صفحهٔ گوشی", "Add to the home screen"),
            tint = Ios.Blue,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(onClick = onPin).padding(9.dp),
        )
    }
}

/** Three method widgets drawn with the current look: what the settings below change. */
@Composable
private fun MethodStylePreview(style: MethodStyle) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var pics by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    LaunchedEffect(style) {
        val rtl = com.mlmvpn.scanner.utils.AppLocaleManager.isFarsi()
        pics = withContext(Dispatchers.Default) {
            val ids = listOf("wireguard", "psiphon", "warp").mapNotNull { WidgetMethods.app(it) }
            val views = listOf(
                com.mlmvpn.scanner.widget.MethodView(WidgetLive.Phase.CONNECTED, since = System.currentTimeMillis() - 720_000),
                com.mlmvpn.scanner.widget.MethodView(WidgetLive.Phase.IDLE),
                com.mlmvpn.scanner.widget.MethodView(WidgetLive.Phase.CONNECTING),
            )
            ids.mapIndexed { i, a -> WidgetPainter.paintMethod(context, a, style, views[i % views.size], 76f, 92f, density, rtl) }
        }
    }
    PreviewStage(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            pics.forEach { b ->
                androidx.compose.foundation.Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(width = 76.dp, height = 92.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// The editor
// ---------------------------------------------------------------------------------------------------

private enum class Tab { LOOK, COLOR, INFO, SIZE }

@Composable
private fun StudioEditor(
    draft: WidgetTemplate,
    isNew: Boolean,
    placed: Int,
    tab: Tab,
    onTab: (Tab) -> Unit,
    preview: PreviewState,
    onPreview: (PreviewState) -> Unit,
    onChange: (WidgetTemplate) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onPin: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMethod: () -> Unit,
    onCountry: () -> Unit,
) {
    var askDelete by remember { mutableStateOf(false) }
    IosScreen(
        title = if (isNew) tr("طرح تازه", "New design") else draft.name.ifBlank { tr("طرح", "Design") },
        onBack = onBack,
        backLabel = tr("استدیو", "Studio"),
        trailing = {
            Text(
                tr("ذخیره", "Save"),
                color = Ios.Label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onSave).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        },
    ) {
        Spacer(Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PreviewStage {
                TemplatePreview(draft, preview, width = if (draft.size == StudioSize.SMALL) 170.dp else null)
            }
            Spacer(Modifier.height(10.dp))
            PreviewStateChips(preview, onChange = onPreview)
        }

        Spacer(Modifier.height(16.dp))
        Segmented(
            options = listOf(Tab.LOOK to tr("قالب", "Look"), Tab.COLOR to tr("رنگ", "Colour"), Tab.INFO to tr("اطلاعات", "Info"), Tab.SIZE to tr("اندازه", "Size")),
            selected = tab,
            onSelect = onTab,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        when (tab) {
            Tab.LOOK -> LookTab(draft, onChange)
            Tab.COLOR -> ColorTab(draft, onChange)
            Tab.INFO -> InfoTab(draft, onChange, onMethod, onCountry)
            Tab.SIZE -> SizeTab(draft, onChange)
        }

        SettingsSectionHeader(tr("نام", "Name"))
        SettingsGroup {
            SettingsTextRow(
                title = tr("نام طرح", "Design name"),
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it.take(40))) },
                placeholder = autoName(draft),
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsGroup {
            SettingsActionRow(label = tr("افزودن به صفحهٔ اصلی گوشی", "Add to the phone's home screen"), icon = Icons.Default.AddToHomeScreen, tint = Ios.Blue, onClick = onPin)
            if (!isNew) {
                Separator()
                SettingsActionRow(label = tr("ساخت یک کپی", "Duplicate"), icon = Icons.Default.ContentCopy, onClick = onDuplicate)
                Separator()
                SettingsActionRow(label = tr("حذف طرح", "Delete design"), icon = Icons.Default.Delete, tint = Ios.Destructive, onClick = { askDelete = true })
            }
        }
        if (placed > 0) {
            SettingsFooter(WidgetFormat.digits(tr("این طرح روی $placed ویجت صفحهٔ گوشی است؛ با ذخیره، همه به‌روز می‌شوند.", "$placed widgets on the home screen wear this design; saving updates them all.")))
        }
        Spacer(Modifier.height(30.dp))
    }
    if (askDelete) {
        IosAlert(
            title = tr("حذف این طرح؟", "Delete this design?"),
            message = if (placed > 0) WidgetFormat.digits(tr("$placed ویجت روی صفحهٔ گوشی این طرح را دارند؛ آن‌ها تا انتخاب طرح دیگر «طرح حذف شده» نشان می‌دهند.",
                "$placed widgets on the home screen wear it; they will say the design was deleted until given another."))
                else tr("این کار برگشت ندارد.", "This cannot be undone."),
            actions = listOf(
                IosAlertAction(tr("انصراف", "Cancel"), onClick = { askDelete = false }),
                IosAlertAction(tr("حذف", "Delete"), onClick = { askDelete = false; onDelete() }, destructive = true),
            ),
            onDismiss = { askDelete = false },
        )
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else Ios.SecondaryLabel, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LookTab(draft: WidgetTemplate, onChange: (WidgetTemplate) -> Unit) {
    SettingsSectionHeader(tr("قالب ظاهری", "Look"))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StudioPresets.ALL.forEach { p ->
            val sample = p.toTemplate("look_" + p.style.key, draft.method, "", 0).copy(size = StudioSize.SMALL, country = draft.country)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PresetCard(sample, p.style.title, cardWidth = 118.dp, onClick = {
                    // A look comes with its own palette and button; the method, country, size and what
                    // the widget shows stay the user's.
                    onChange(draft.copy(style = p.style, accent = p.accent, background = p.background, opacity = p.opacity,
                        corner = p.corner, button = p.button))
                })
                if (p.style == draft.style) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(18.dp))
                } else Spacer(Modifier.height(18.dp))
            }
        }
    }
    SettingsSectionHeader(tr("دکمهٔ اتصال", "Connect button"))
    Segmented(
        options = ButtonStyle.entries.map { it to it.title },
        selected = draft.button,
        onSelect = { onChange(draft.copy(button = it)) },
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun ColorTab(draft: WidgetTemplate, onChange: (WidgetTemplate) -> Unit) {
    SettingsSectionHeader(tr("رنگ اصلی", "Accent colour"))
    SwatchRow(StudioPresets.ACCENTS, draft.accent) { onChange(draft.copy(accent = it)) }
    Spacer(Modifier.height(8.dp))
    HueSlider(draft.accent) { onChange(draft.copy(accent = it)) }

    SettingsSectionHeader(tr("رنگ زمینه", "Card colour"))
    SwatchRow(StudioPresets.BACKGROUNDS, draft.background) { onChange(draft.copy(background = it)) }

    SettingsSectionHeader(tr("شیشه‌ای بودن", "Glass"))
    SettingsSlider(
        title = tr("پررنگی زمینه", "Card solidity"),
        value = draft.opacity,
        valueLabel = WidgetFormat.digits("${draft.opacity}%"),
        range = WidgetTemplate.OPACITY_MIN.toFloat()..WidgetTemplate.OPACITY_MAX.toFloat(),
        onValueChange = { onChange(draft.copy(opacity = it)) },
    )
    SettingsFooter(tr("کمتر یعنی شیشه‌ای‌تر: کاغذدیواری گوشی بیشتر از پشت ویجت دیده می‌شود.", "Lower is more glass: more of the phone's wallpaper shows through."))
    Spacer(Modifier.height(12.dp))
    SettingsSlider(
        title = tr("گردی گوشه‌ها", "Corner radius"),
        value = draft.corner,
        valueLabel = WidgetFormat.digits("${draft.corner}"),
        range = WidgetTemplate.CORNER_MIN.toFloat()..WidgetTemplate.CORNER_MAX.toFloat(),
        onValueChange = { onChange(draft.copy(corner = it)) },
    )
    Spacer(Modifier.height(12.dp))
    SettingsSlider(
        title = tr("اندازهٔ متن", "Text size"),
        value = draft.textScale,
        valueLabel = WidgetFormat.digits("${draft.textScale}%"),
        range = WidgetTemplate.TEXT_MIN.toFloat()..WidgetTemplate.TEXT_MAX.toFloat(),
        onValueChange = { onChange(draft.copy(textScale = it)) },
    )
}

@Composable
private fun SwatchRow(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { c -> Swatch(c, selected == c, onClick = { onPick(c) }) }
    }
}

/** Any accent at all: a hue, at the saturation and brightness the presets use. */
@Composable
private fun HueSlider(color: Int, onPick: (Int) -> Unit) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.75f, 0.95f))) }
                )),
        )
        Slider(
            value = hsv[0],
            onValueChange = { onPick(android.graphics.Color.HSVToColor(floatArrayOf(it, 0.75f, 0.95f))) },
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(thumbColor = Color(color), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
        )
    }
}

@Composable
private fun InfoTab(draft: WidgetTemplate, onChange: (WidgetTemplate) -> Unit, onMethod: () -> Unit, onCountry: () -> Unit) {
    SettingsSectionHeader(tr("روش و کشور", "Method and country"))
    SettingsGroup {
        SettingsRow(
            title = tr("روش اتصال", "Connection method"),
            value = methodTitle(draft.method),
            icon = Icons.Default.Widgets,
            tint = Ios.Indigo,
            onClick = onMethod,
        )
        if (WidgetConnect.hasCountries(draft.method)) {
            Separator()
            SettingsRow(
                title = tr("کشور", "Country"),
                value = draft.country?.let { WidgetFormat.flag(it) + " " + WidgetFormat.countryName(it) } ?: tr("خودکار", "Automatic"),
                icon = Icons.Default.Public,
                tint = Ios.Teal,
                onClick = onCountry,
            )
        }
    }
    if (!WidgetConnect.hasCountries(draft.method)) {
        SettingsFooter(tr("این روش کشور خروج را خودش انتخاب می‌کند.", "This method picks its exit country itself."))
    }
    SettingsSectionHeader(tr("اطلاعات روی ویجت", "What it shows"))
    SettingsGroup {
        Info.entries.forEachIndexed { i, info ->
            if (i > 0) Separator()
            SettingsToggle(
                title = info.title,
                checked = draft.shows(info),
                onCheckedChange = { on -> onChange(draft.copy(show = if (on) draft.show + info else draft.show - info)) },
            )
        }
    }
    SettingsFooter(tr("هر چه جا شود نشان داده می‌شود؛ ویجت کوچک فقط مهم‌ترین‌ها را دارد.", "Whatever fits is shown; a small widget keeps only the most important."))
}

@Composable
private fun SizeTab(draft: WidgetTemplate, onChange: (WidgetTemplate) -> Unit) {
    SettingsSectionHeader(tr("اندازهٔ ویجت", "Widget size"))
    Segmented(
        options = StudioSize.entries.map { it to it.title },
        selected = draft.size,
        onSelect = { onChange(draft.copy(size = it)) },
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    SettingsFooter(WidgetFormat.digits(tr(
        "کوچک: ۲ در ۲ خانه · متوسط: ۴ در ۲ · بزرگ: ۴ در ۴. بعد از گذاشتن روی صفحه هم می‌شود اندازه را با کشیدن لبه‌ها عوض کرد.",
        "Small: 2 by 2 cells · Medium: 4 by 2 · Large: 4 by 4. Once placed, it can still be resized by dragging its edges.",
    )))
}

// ---------------------------------------------------------------------------------------------------
// Pickers
// ---------------------------------------------------------------------------------------------------

@Composable
private fun MethodPicker(selected: String, onPick: (String) -> Unit, onBack: () -> Unit) {
    IosScreen(title = tr("روش اتصال", "Connection method"), onBack = onBack, backLabel = tr("طرح", "Design")) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            WidgetMethods.all().forEachIndexed { i, app ->
                if (i > 0) Separator()
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(app.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconTile(app = app, size = 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(WidgetPainter.labelOf(app), color = Ios.Label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (app.id == selected) Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun CountryPicker(method: String, selected: String?, onPick: (String?) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var list by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(method) { list = WidgetConnect.countries(context, method).orEmpty() }
    IosScreen(title = tr("کشور", "Country"), onBack = onBack, backLabel = tr("طرح", "Design")) {
        Spacer(Modifier.height(14.dp))
        WidgetConnect.countryNote(method)?.let {
            Text(it, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(10.dp))
        }
        val countries = list
        if (countries == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Ios.Label, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
        } else {
            SettingsGroup {
                CountryRow(tr("خودکار — انتخاب خود روش", "Automatic — the method's own choice"), "🌐", selected == null) { onPick(null) }
                countries.forEach { cc ->
                    Separator()
                    CountryRow(WidgetFormat.countryName(cc), WidgetFormat.flag(cc), selected == cc) { onPick(cc) }
                }
            }
            if (countries.isEmpty()) {
                SettingsFooter(tr("هنوز فهرست کشوری برای این روش ساخته نشده؛ یک بار صفحهٔ خود روش را باز کنید.", "No country list for this method yet; open its own page once."))
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun CountryRow(name: String, flag: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Text(name, color = Ios.Label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AddMethodWidget(onBack: () -> Unit, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    IosScreen(title = tr("ویجت روش‌ها", "Method widgets"), onBack = onBack, backLabel = tr("استدیو", "Studio")) {
        Spacer(Modifier.height(10.dp))
        Text(
            tr("یک روش را بزنید تا ویجتش به صفحهٔ اصلی گوشی اضافه شود. با لمس ویجت، همان روش وصل یا قطع می‌شود.",
                "Tap a method to add its widget to the phone's home screen. Tapping the widget then connects or disconnects that method."),
            color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(10.dp))
        // Gereh is not a way of connecting, so it is not in the list below: its widget shows the
        // unread messages and whether Gereh reaches anyone, and a tap opens it.
        GerehWidgets.tile()?.let { gereh ->
            SettingsSectionHeader(tr("پیام‌رسان", "Messenger"))
            SettingsGroup {
                val count = remember { GerehWidgets.placedCount(context) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onAdd(gereh.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconTile(app = gereh, size = 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(WidgetPainter.labelOf(gereh), color = Ios.Label, fontSize = 16.sp)
                        Text(
                            if (count > 0) WidgetFormat.digits(tr("$count ویجت روی صفحه", "$count on the home screen"))
                            else tr("پیام‌های نخوانده؛ با لمس، گره باز می‌شود", "Unread messages; a tap opens Gereh"),
                            color = Ios.SecondaryLabel, fontSize = 12.sp,
                        )
                    }
                    Icon(Icons.Default.AddToHomeScreen, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            SettingsSectionHeader(tr("روش‌های اتصال", "Connection methods"))
        }
        SettingsGroup {
            WidgetMethods.all().forEachIndexed { i, app ->
                if (i > 0) Separator()
                val count = remember(app.id) { MethodWidgets.placedCount(context, app.id) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onAdd(app.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconTile(app = app, size = 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(WidgetPainter.labelOf(app), color = Ios.Label, fontSize = 16.sp)
                        if (count > 0) Text(WidgetFormat.digits(tr("$count ویجت روی صفحه", "$count on the home screen")), color = Ios.SecondaryLabel, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.AddToHomeScreen, contentDescription = null, tint = Ios.Blue, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}
