package com.mlmvpn.scanner.widget.studio

import com.mlmvpn.scanner.store.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * «ویجت استدیو»: the widgets a user designs for the phone's home screen.
 *
 * A [WidgetTemplate] is the design -- which method it connects, its look, its colours, what it shows.
 * A widget placed on the home screen is bound to one template by its id ([StudioData.bindings]), so
 * any number of widgets can share a design, editing a design updates every widget wearing it, and
 * one design can be placed again and again in different sizes.
 */

/** The eight looks of the studio's gallery. The key is what is stored; it must never change. */
enum class StudioStyle(val key: String) {
    GLASS("glass"),
    MINIMAL("minimal"),
    NEON("neon"),
    COLORFUL("colorful"),
    COMPACT("compact"),
    DARK_PRO("dark_pro"),
    IOS("ios"),
    WAVE("wave");

    val title: String
        get() = when (this) {
            GLASS -> tr("شیشه‌ای", "Glass")
            MINIMAL -> tr("مینیمال", "Minimal")
            NEON -> tr("نئونی", "Neon")
            COLORFUL -> tr("رنگارنگ", "Colorful")
            COMPACT -> tr("جمع‌وجور", "Compact")
            DARK_PRO -> tr("تیرهٔ حرفه‌ای", "Dark Pro")
            IOS -> tr("سبک iOS", "iOS Style")
            WAVE -> tr("موج", "Wave")
        }

    companion object {
        fun of(key: String?): StudioStyle = entries.firstOrNull { it.key == key } ?: GLASS
    }
}

/** How much of the home screen a widget takes; each is a widget of its own in the system picker. */
enum class StudioSize(val key: String, val columns: Int, val rows: Int) {
    SMALL("small", 2, 2),
    MEDIUM("medium", 4, 2),
    LARGE("large", 4, 4);

    val title: String
        get() = when (this) {
            SMALL -> tr("کوچک", "Small")
            MEDIUM -> tr("متوسط", "Medium")
            LARGE -> tr("بزرگ", "Large")
        }

    companion object {
        fun of(key: String?): StudioSize = entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

/** The connect button's shape. */
enum class ButtonStyle(val key: String) {
    /** A ring with the power glyph -- the classic VPN switch. */
    RING("ring"),
    /** A rounded bar with a word on it: «اتصال», «متصل». */
    PILL("pill"),
    /** A filled disc with the power glyph. */
    SOLID("solid"),
    /** The ring again, glowing in the accent colour. */
    GLOW("glow");

    val title: String
        get() = when (this) {
            RING -> tr("حلقه", "Ring")
            PILL -> tr("دکمهٔ کشیده", "Pill")
            SOLID -> tr("دایرهٔ پر", "Solid")
            GLOW -> tr("حلقهٔ درخشان", "Glow")
        }

    companion object {
        fun of(key: String?): ButtonStyle = entries.firstOrNull { it.key == key } ?: RING
    }
}

/** What a widget may show besides its method and its button. */
enum class Info(val key: String) {
    BRAND("brand"),
    METHOD("method"),
    STATUS("status"),
    COUNTRY("country"),
    SPEED("speed"),
    USAGE("usage"),
    TIME("time"),
    CHART("chart");

    val title: String
        get() = when (this) {
            BRAND -> tr("نام برنامه", "App name")
            METHOD -> tr("نام روش اتصال", "Method name")
            STATUS -> tr("وضعیت اتصال", "Connection status")
            COUNTRY -> tr("کشور", "Country")
            SPEED -> tr("سرعت لحظه‌ای", "Live speed")
            USAGE -> tr("مصرف این نشست", "This session's usage")
            TIME -> tr("مدت اتصال", "Time connected")
            CHART -> tr("نمودار زنده", "Live chart")
        }

    companion object {
        fun parse(arr: JSONArray?): Set<Info> {
            if (arr == null) return DEFAULT_INFO
            val out = LinkedHashSet<Info>()
            for (i in 0 until arr.length()) entries.firstOrNull { it.key == arr.optString(i) }?.let { out += it }
            return out
        }
    }
}

val DEFAULT_INFO: Set<Info> = setOf(Info.BRAND, Info.METHOD, Info.STATUS, Info.COUNTRY, Info.SPEED)

data class WidgetTemplate(
    val id: String,
    val name: String,
    /** The home tile id of the method this widget connects (see WidgetMethods). */
    val method: String,
    val style: StudioStyle,
    val size: StudioSize,
    /** ARGB. The colour of the button, the lamp and the highlights. */
    val accent: Int,
    /** ARGB. The card's own colour, before [opacity]. */
    val background: Int,
    /** 0..100: how solid the card is. Low is glass over the wallpaper, 100 is opaque. */
    val opacity: Int,
    /** Corner radius, dp. */
    val corner: Int,
    val button: ButtonStyle,
    /** Percent, 85..125: every text on the widget, on top of its own size. */
    val textScale: Int,
    /** ISO-2 exit country to connect to, or null to let the method choose. */
    val country: String?,
    val show: Set<Info>,
    val created: Long,
    val updated: Long,
) {
    fun shows(info: Info) = info in show

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("method", method)
        put("style", style.key)
        put("size", size.key)
        put("accent", accent)
        put("background", background)
        put("opacity", opacity)
        put("corner", corner)
        put("button", button.key)
        put("textScale", textScale)
        country?.let { put("country", it) }
        put("show", JSONArray(show.map { it.key }))
        put("created", created)
        put("updated", updated)
    }

    companion object {
        const val OPACITY_MIN = 0
        const val OPACITY_MAX = 100
        const val CORNER_MIN = 8
        const val CORNER_MAX = 40
        const val TEXT_MIN = 85
        const val TEXT_MAX = 125

        /** Null for a record too damaged to draw; every field is clamped, so one bad value costs nothing. */
        fun fromJson(o: JSONObject): WidgetTemplate? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val method = o.optString("method").takeIf { it.isNotBlank() } ?: return null
            return WidgetTemplate(
                id = id,
                name = o.optString("name").take(40),
                method = method,
                style = StudioStyle.of(o.optString("style")),
                size = StudioSize.of(o.optString("size")),
                accent = o.optInt("accent", 0xFF39E58C.toInt()),
                background = o.optInt("background", 0xFF0E1B2B.toInt()),
                opacity = o.optInt("opacity", 55).coerceIn(OPACITY_MIN, OPACITY_MAX),
                corner = o.optInt("corner", 26).coerceIn(CORNER_MIN, CORNER_MAX),
                button = ButtonStyle.of(o.optString("button")),
                textScale = o.optInt("textScale", 100).coerceIn(TEXT_MIN, TEXT_MAX),
                country = o.optString("country").trim().uppercase().takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } },
                show = Info.parse(o.optJSONArray("show")),
                created = o.optLong("created", 0L),
                updated = o.optLong("updated", 0L),
            )
        }
    }
}

/**
 * A starting point in the gallery. Picking one makes a template from it; the preset itself is never
 * edited, so the gallery always shows the eight looks as they ship.
 */
data class StudioPreset(
    val style: StudioStyle,
    val accent: Int,
    val background: Int,
    val opacity: Int,
    val corner: Int,
    val button: ButtonStyle,
    val size: StudioSize,
    val show: Set<Info>,
) {
    fun toTemplate(id: String, method: String, name: String, now: Long) = WidgetTemplate(
        id = id,
        name = name,
        method = method,
        style = style,
        size = size,
        accent = accent,
        background = background,
        opacity = opacity,
        corner = corner,
        button = button,
        textScale = 100,
        country = null,
        show = show,
        created = now,
        updated = now,
    )
}

object StudioPresets {
    val ALL: List<StudioPreset> = listOf(
        StudioPreset(StudioStyle.GLASS, 0xFF39E58C.toInt(), 0xFF0F2236.toInt(), 46, 28, ButtonStyle.RING, StudioSize.MEDIUM,
            setOf(Info.BRAND, Info.METHOD, Info.STATUS, Info.COUNTRY, Info.SPEED)),
        StudioPreset(StudioStyle.MINIMAL, 0xFFE9EBEE.toInt(), 0xFF131518.toInt(), 94, 22, ButtonStyle.PILL, StudioSize.MEDIUM,
            setOf(Info.METHOD, Info.COUNTRY, Info.SPEED)),
        StudioPreset(StudioStyle.NEON, 0xFFB46CFF.toInt(), 0xFF0B0718.toInt(), 90, 26, ButtonStyle.GLOW, StudioSize.SMALL,
            setOf(Info.METHOD, Info.STATUS, Info.COUNTRY)),
        StudioPreset(StudioStyle.COLORFUL, 0xFFFF4F8B.toInt(), 0xFF7A4DFF.toInt(), 100, 28, ButtonStyle.PILL, StudioSize.SMALL,
            setOf(Info.BRAND, Info.METHOD, Info.COUNTRY, Info.SPEED)),
        StudioPreset(StudioStyle.COMPACT, 0xFF34C759.toInt(), 0xFF101418.toInt(), 90, 24, ButtonStyle.PILL, StudioSize.SMALL,
            setOf(Info.METHOD, Info.COUNTRY)),
        StudioPreset(StudioStyle.DARK_PRO, 0xFF22C55E.toInt(), 0xFF06080B.toInt(), 96, 24, ButtonStyle.RING, StudioSize.LARGE,
            setOf(Info.BRAND, Info.METHOD, Info.STATUS, Info.COUNTRY, Info.SPEED, Info.USAGE, Info.TIME, Info.CHART)),
        StudioPreset(StudioStyle.IOS, 0xFF0A84FF.toInt(), 0xFFF2F2F7.toInt(), 80, 30, ButtonStyle.PILL, StudioSize.MEDIUM,
            setOf(Info.METHOD, Info.STATUS, Info.COUNTRY, Info.SPEED)),
        StudioPreset(StudioStyle.WAVE, 0xFF8B5CF6.toInt(), 0xFF171447.toInt(), 94, 28, ButtonStyle.PILL, StudioSize.MEDIUM,
            setOf(Info.BRAND, Info.METHOD, Info.COUNTRY, Info.SPEED)),
    )

    fun of(style: StudioStyle): StudioPreset = ALL.first { it.style == style }

    /** The accent swatches the editor offers, the mockups' and iOS's own. */
    val ACCENTS: List<Int> = listOf(
        0xFF39E58C, 0xFF34C759, 0xFF0A84FF, 0xFF64D2FF, 0xFF8B5CF6, 0xFFB46CFF,
        0xFFFF4F8B, 0xFFFF9F0A, 0xFFFFD60A, 0xFFFF453A, 0xFFE9EBEE,
    ).map { it.toInt() }

    /** The card colours the editor offers. */
    val BACKGROUNDS: List<Int> = listOf(
        0xFF0F2236, 0xFF131518, 0xFF06080B, 0xFF0B0718, 0xFF171447, 0xFF1C1C1E,
        0xFF102A22, 0xFF2A1020, 0xFF7A4DFF, 0xFFF2F2F7,
    ).map { it.toInt() }
}

/**
 * How the plain method widgets (one per connection tile) look. One setting for all of them: they are
 * shortcuts that must look like the tiles they stand for, so the choices are about framing, not
 * design. See WidgetPainter.paintMethod.
 */
data class MethodStyle(
    /** False: the tile alone, like an app icon. True: the tile on a glass card with its state. */
    val card: Boolean = false,
    val label: Boolean = true,
    /** The status lamp and, on a wide widget, the state in words. */
    val status: Boolean = true,
    /** 0..100, the card's solidity when [card] is on. */
    val opacity: Int = 45,
    /** True: a tap connects or disconnects. False: a tap opens the method's page in the app. */
    val tapConnects: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("card", card)
        put("label", label)
        put("status", status)
        put("opacity", opacity)
        put("tapConnects", tapConnects)
    }

    companion object {
        fun fromJson(o: JSONObject?): MethodStyle {
            if (o == null) return MethodStyle()
            return MethodStyle(
                card = o.optBoolean("card", false),
                label = o.optBoolean("label", true),
                status = o.optBoolean("status", true),
                opacity = o.optInt("opacity", 45).coerceIn(0, 100),
                tapConnects = o.optBoolean("tapConnects", true),
            )
        }
    }
}

/** Everything the studio keeps, in one file. */
data class StudioData(
    val templates: List<WidgetTemplate> = emptyList(),
    /** Placed widget id -> template id. */
    val bindings: Map<Int, String> = emptyMap(),
    val methodStyle: MethodStyle = MethodStyle(),
) {
    fun template(id: String?): WidgetTemplate? = id?.let { k -> templates.firstOrNull { it.id == k } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("templates", JSONArray(templates.map { it.toJson() }))
        put("bindings", JSONObject().apply { bindings.forEach { (k, v) -> put(k.toString(), v) } })
        put("methodStyle", methodStyle.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): StudioData {
            val arr = o.optJSONArray("templates") ?: JSONArray()
            val templates = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(WidgetTemplate::fromJson) }
                .distinctBy { it.id }
            val b = o.optJSONObject("bindings")
            val bindings = LinkedHashMap<Int, String>()
            b?.keys()?.forEach { k -> k.toIntOrNull()?.let { id -> b.optString(k).takeIf { it.isNotBlank() }?.let { bindings[id] = it } } }
            return StudioData(templates, bindings, MethodStyle.fromJson(o.optJSONObject("methodStyle")))
        }
    }
}
