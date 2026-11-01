package com.mlmvpn.scanner.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.home.SearchMatcher
import java.util.Locale

/** A text in both languages: a string resource, or the pair a `tr(fa, en)` call carries. */
internal sealed class SText {
    data class Res(@StringRes val id: Int) : SText()
    data class Lit(val fa: String, val en: String) : SText()

    /** In the language the app is showing: the words the row itself draws. */
    fun current(context: Context): String = when (this) {
        is Res -> context.getString(id)
        is Lit -> tr(fa, en)
    }

    /** Persian and English, for finding it whichever language the user types in. */
    fun both(persian: Resources, english: Resources): List<String> = when (this) {
        is Res -> listOf(persian.getString(id), english.getString(id))
        is Lit -> listOf(fa, en)
    }
}

/** A row's glyph colour by name: the [Ios] colours can only be read inside a composition. */
internal enum class Hue {
    BLUE, GREEN, INDIGO, ORANGE, PINK, PURPLE, RED, TEAL, YELLOW, GRAY, CLOUDFLARE;

    val color: Color
        @Composable get() = when (this) {
            BLUE -> Ios.Blue
            GREEN -> Ios.Green
            INDIGO -> Ios.Indigo
            ORANGE -> Ios.Orange
            PINK -> Ios.Pink
            PURPLE -> Ios.Purple
            RED -> Ios.Red
            TEAL -> Ios.Teal
            YELLOW -> Ios.Yellow
            GRAY -> Ios.Gray
            CLOUDFLARE -> Ios.CloudflareOrange
        }
}

/** One row of Settings the home screen's search can find, and where it is. */
internal data class SettingsEntry(
    /** Exactly what the row says. It is also how the row is found again once its page is open. */
    val title: SText,
    /** The page the row is on ([SettingsRoutes]). */
    val route: String,
    /** Where that is, after «تنظیمات»: the section of the list, or the pages on the way. */
    val path: List<SText>,
    val icon: () -> ImageVector,
    val hue: Hue,
    /** The row's grey line, when it has one: found by too, for less than the title. */
    val subtitle: SText? = null,
    /** Other words someone looking for this would type, in both languages. */
    val keywords: List<String> = emptyList(),
    /** Rows to light up instead while this one is not shown (it appears only in some states). */
    val fallback: List<SText> = emptyList(),
) {
    /** Everything this row can be found by; [both] gives a text in Persian and in English. */
    fun fields(both: (SText) -> List<String>): List<SearchMatcher.Field> = buildList {
        both(title).forEach { add(SearchMatcher.Field(it, 1f)) }
        keywords.forEach { add(SearchMatcher.Field(it, 0.85f)) }
        subtitle?.let { s -> both(s).forEach { add(SearchMatcher.Field(it, 0.55f)) } }
        path.forEach { p -> both(p).forEach { add(SearchMatcher.Field(it, 0.5f)) } }
    }
}

/**
 * Every row of Settings the home screen's search can find.
 *
 * Kept by hand, next to the screens it describes, and held to them by SettingsIndexTest: that test
 * reads the settings screens' source and fails for a row whose title is not here (or is listed as
 * deliberately left out there), and for an entry here whose row no longer exists. A new row
 * therefore cannot quietly be missing from the search -- add it here, with the page it is on.
 *
 * A row is listed where its control is. Proxy mode, local network and Smart Iran are switches on
 * pages of their own, so the search opens those pages rather than the list rows that lead to them;
 * a row that opens a picker (language, theme, DNS) is the row itself, on the page it is on.
 */
internal object SettingsIndex {

    private fun res(@StringRes id: Int) = SText.Res(id)

    private val network = res(R.string.settings_group_network)
    private val display = res(R.string.settings_display)
    private val usage = res(R.string.settings_group_usage)
    private val system = res(R.string.settings_group_system)
    private val about = res(R.string.settings_group_about)
    private val smartIran = SText.Lit("مسیریابی هوشمند ایران", "Smart Iran Routing")

    val entries: List<SettingsEntry> = listOf(
        // ---- the list, top to bottom ----------------------------------------------------------
        SettingsEntry(
            res(R.string.cf_resources_row), SettingsRoutes.ROOT, emptyList(), { Icons.Default.Dns }, Hue.CLOUDFLARE,
            subtitle = res(R.string.cf_resources_subtitle),
            keywords = listOf("کلادفلر", "Cloudflare", "ورکر", "وورکر", "Worker", "D1", "KV"),
        ),
        SettingsEntry(
            res(R.string.settings_local_port), SettingsRoutes.ROOT, listOf(network), { Icons.Default.SettingsEthernet }, Hue.BLUE,
            keywords = listOf("پورت", "Port", "SOCKS", "HTTP"),
        ),
        SettingsEntry(
            res(R.string.settings_backend_dns), SettingsRoutes.ROOT, listOf(network), { Icons.Default.Dns }, Hue.INDIGO,
            keywords = listOf("DNS", "دی ان اس", "Resolver"),
        ),
        SettingsEntry(
            res(R.string.settings_tls_unfilter), SettingsRoutes.ROOT, listOf(network), { Icons.Default.Https }, Hue.PURPLE,
            subtitle = res(R.string.settings_tls_unfilter_desc),
            keywords = listOf("فیلتر", "رفع فیلتر", "TLS", "Unfilter", "Fragment", "فرگمنت"),
        ),
        SettingsEntry(
            res(R.string.settings_google_fix), SettingsRoutes.ROOT, listOf(network), { Icons.Default.AutoAwesome }, Hue.BLUE,
            subtitle = res(R.string.settings_google_fix_desc),
            keywords = listOf("جمنای", "جیمینای", "Gemini", "گوگل", "Google"),
        ),
        SettingsEntry(
            res(R.string.settings_gemini_exit), SettingsRoutes.ROOT, listOf(network), { Icons.Default.Public }, Hue.INDIGO,
            keywords = listOf("جمنای", "Gemini", "آمریکا", "USA", "خروجی", "Exit"),
            // Listed only while the Gemini switch is on.
            fallback = listOf(res(R.string.settings_google_fix)),
        ),
        SettingsEntry(
            smartIran, SettingsRoutes.SMART_IRAN, listOf(network), { Icons.Default.AltRoute }, Hue.GREEN,
            subtitle = SText.Lit("سایت‌ها و برنامه‌های ایرانی مستقیم، بدون VPN", "Iranian sites and apps direct, without the VPN"),
            keywords = listOf("ایران", "Iran", "مستقیم", "Direct", "بانک", "برنامه‌های ایرانی", "سایت‌های ایرانی", "Split"),
        ),
        SettingsEntry(
            res(R.string.settings_warp_id), SettingsRoutes.ROOT, listOf(network), { Icons.Default.VpnKey }, Hue.ORANGE,
            keywords = listOf("هویت", "Identity", "وارپ", "WARP", "ورکر", "Worker"),
        ),
        SettingsEntry(
            res(R.string.settings_proxy_mode), SettingsRoutes.PROXY_MODE, listOf(network), { Icons.Default.SwapHoriz }, Hue.PURPLE,
            subtitle = res(R.string.settings_proxy_mode_desc),
            keywords = listOf("پروکسی", "Proxy", "SOCKS", "HTTP"),
        ),
        SettingsEntry(
            res(R.string.settings_allow_lan), SettingsRoutes.ALLOW_LAN, listOf(network), { Icons.Default.Wifi }, Hue.TEAL,
            subtitle = res(R.string.settings_allow_lan_desc),
            keywords = listOf("LAN", "هات اسپات", "Hotspot", "اشتراک", "Share", "دستگاه دیگر"),
        ),
        SettingsEntry(
            res(R.string.settings_advanced_vpn), SettingsRoutes.ROOT, listOf(network), { Icons.Default.VpnKey }, Hue.GREEN,
            keywords = listOf("VPN", "تونل", "Tunnel", "Split tunnel"),
        ),
        SettingsEntry(
            res(R.string.settings_display), SettingsRoutes.ROOT, listOf(display), { Icons.Default.Palette }, Hue.PINK,
            keywords = listOf("نمایش", "ظاهر", "Appearance"),
        ),
        SettingsEntry(
            res(R.string.settings_realtime_traffic), SettingsRoutes.ROOT, listOf(display), { Icons.Default.Insights }, Hue.ORANGE,
            keywords = listOf("ترافیک", "Traffic", "سرعت", "Speed"),
        ),
        SettingsEntry(
            res(R.string.settings_language), SettingsRoutes.ROOT, listOf(display), { Icons.Default.Language }, Hue.BLUE,
            keywords = listOf("زبان", "فارسی", "انگلیسی", "English", "Persian", "Farsi"),
        ),
        SettingsEntry(
            res(R.string.settings_usage_tracking), SettingsRoutes.ROOT, listOf(usage), { Icons.Default.Timeline }, Hue.YELLOW,
            subtitle = res(R.string.settings_usage_tracking_desc),
            keywords = listOf("مصرف", "حجم", "Usage", "Data"),
        ),
        SettingsEntry(
            res(R.string.settings_total_usage), SettingsRoutes.ROOT, listOf(usage), { Icons.Default.BarChart }, Hue.GREEN,
            keywords = listOf("مصرف", "حجم", "آمار", "Usage", "Data", "Stats"),
        ),
        SettingsEntry(
            res(R.string.settings_screen_off_timeout), SettingsRoutes.ROOT, listOf(system), { Icons.Default.Timer }, Hue.GRAY,
            keywords = listOf("خاموش", "Screen off", "Timeout"),
        ),
        SettingsEntry(
            SText.Lit("پشتیبان‌گیری و بازگردانی", "Backup & restore"), SettingsRoutes.ROOT, listOf(system),
            { com.mlmvpn.scanner.ui.backup.backupRowIcon }, Hue.BLUE,
            subtitle = SText.Lit(
                "خروجی از تنظیمات، حساب‌ها و کانفیگ‌ها و برگرداندن آن‌ها",
                "Export settings, accounts and configs, and bring them back",
            ),
            keywords = listOf("پشتیبان", "بکاپ", "خروجی", "ورودی", "بازگردانی", "انتقال به گوشی دیگر", "Backup", "Restore", "Export", "Import"),
        ),
        SettingsEntry(
            res(R.string.settings_reset_home), SettingsRoutes.ROOT, listOf(system), { Icons.Default.Apps }, Hue.RED,
            keywords = listOf("چیدمان", "Layout", "Reset"),
        ),
        SettingsEntry(
            res(R.string.settings_version), SettingsRoutes.ROOT, listOf(about), { Icons.Default.Info }, Hue.GRAY,
            keywords = listOf("ورژن", "Version"),
        ),
        SettingsEntry(
            res(R.string.settings_software_update), SettingsRoutes.ROOT, listOf(about), { Icons.Default.SystemUpdate }, Hue.BLUE,
            subtitle = res(R.string.settings_software_update_desc),
            keywords = listOf("آپدیت", "Update", "نسخه جدید", "New version"),
        ),
        SettingsEntry(
            res(R.string.settings_crash_report), SettingsRoutes.ROOT, listOf(about), { Icons.Default.Warning }, Hue.ORANGE,
            keywords = listOf("کرش", "Crash", "خطا", "Error", "باگ", "Bug"),
        ),
        SettingsEntry(
            res(R.string.settings_crash_auto), SettingsRoutes.ROOT, listOf(about), { Icons.Default.Warning }, Hue.ORANGE,
            subtitle = res(R.string.settings_crash_auto_desc),
            keywords = listOf("کرش", "Crash", "خطا", "Error"),
        ),
        SettingsEntry(
            res(R.string.about_title), SettingsRoutes.ROOT, listOf(about), { Icons.Default.Groups }, Hue.BLUE,
            subtitle = res(R.string.about_row_subtitle),
            keywords = listOf("درباره", "About", "سازنده", "تماس", "Contact"),
        ),

        // ---- Display ----------------------------------------------------------------------------
        SettingsEntry(
            res(R.string.settings_wallpaper), SettingsRoutes.DISPLAY, listOf(display), { Icons.Default.Palette }, Hue.PINK,
            keywords = listOf("والپیپر", "Wallpaper", "پس زمینه", "Background"),
        ),
        SettingsEntry(
            res(R.string.settings_theme), SettingsRoutes.DISPLAY, listOf(display), { Icons.Default.Contrast }, Hue.INDIGO,
            keywords = listOf("تم", "Theme", "تاریک", "روشن", "Dark", "Light", "حالت شب", "Night"),
        ),
        SettingsEntry(
            SText.Lit("ویجت اتصال در صفحهٔ اصلی", "Connection widget on the home screen"), SettingsRoutes.DISPLAY, listOf(display),
            { Icons.Default.ViewQuilt }, Hue.BLUE,
            subtitle = SText.Lit("روش متصل، کشور خروج، نمودار زنده، مصرف و دکمهٔ قطع", "The method on, its exit country, a live chart, usage and a disconnect button"),
            keywords = listOf("ویجت", "Widget", "ابزارک", "نمودار", "Chart"),
        ),
        SettingsEntry(
            res(R.string.settings_icon_size), SettingsRoutes.DISPLAY, listOf(display), { Icons.Default.ZoomIn }, Hue.BLUE,
            keywords = listOf("اندازه", "بزرگ", "کوچک", "Size", "آیکون", "Icon"),
        ),
        SettingsEntry(
            res(R.string.settings_text_size_system), SettingsRoutes.DISPLAY, listOf(display), { Icons.Default.TextFields }, Hue.BLUE,
            subtitle = res(R.string.settings_text_size_system_desc),
            keywords = listOf("فونت", "Font", "متن", "Text"),
        ),
        SettingsEntry(
            res(R.string.settings_text_size), SettingsRoutes.DISPLAY, listOf(display), { Icons.Default.TextFields }, Hue.BLUE,
            keywords = listOf("فونت", "Font", "بزرگ", "کوچک"),
            // Shown only while the app is not following the phone's own size.
            fallback = listOf(res(R.string.settings_text_size_system)),
        ),
        SettingsEntry(
            res(R.string.settings_label_size), SettingsRoutes.DISPLAY, listOf(display), { Icons.Default.Title }, Hue.BLUE,
            keywords = listOf("عنوان", "Label", "Caption"),
        ),
        SettingsEntry(
            res(R.string.settings_dock_labels), SettingsRoutes.DISPLAY, listOf(display), { Icons.Default.Label }, Hue.PURPLE,
            subtitle = res(R.string.settings_dock_labels_desc),
            keywords = listOf("داک", "Dock", "عنوان"),
        ),
        SettingsEntry(
            res(R.string.settings_wallpaper_everywhere), SettingsRoutes.DISPLAY, listOf(display), { Icons.Default.Layers }, Hue.TEAL,
            subtitle = res(R.string.settings_wallpaper_everywhere_desc),
            keywords = listOf("والپیپر", "Wallpaper", "پس زمینه", "Background"),
        ),

        // ---- Display > Wallpaper ------------------------------------------------------------------
        SettingsEntry(
            res(R.string.wallpaper_choose_image), SettingsRoutes.WALLPAPER, listOf(display, res(R.string.settings_wallpaper)),
            { Icons.Default.Image }, Hue.INDIGO,
            keywords = listOf("والپیپر", "Wallpaper", "تصویر", "طرح", "Preset"),
        ),
        SettingsEntry(
            res(R.string.wallpaper_from_gallery), SettingsRoutes.WALLPAPER, listOf(display, res(R.string.settings_wallpaper)),
            { Icons.Default.PhotoLibrary }, Hue.GREEN,
            keywords = listOf("گالری", "Gallery", "عکس", "Photo", "والپیپر", "Wallpaper"),
        ),
        SettingsEntry(
            res(R.string.wallpaper_blur), SettingsRoutes.WALLPAPER, listOf(display, res(R.string.settings_wallpaper)),
            { Icons.Default.BlurOn }, Hue.TEAL,
            keywords = listOf("تاری", "محو", "Blur", "والپیپر", "Wallpaper"),
        ),

        // ---- Software update ----------------------------------------------------------------------
        SettingsEntry(
            res(R.string.update_download_and_install), SettingsRoutes.UPDATE, listOf(res(R.string.settings_software_update)),
            { Icons.Default.SystemUpdate }, Hue.BLUE,
            keywords = listOf("آپدیت", "Update", "دانلود", "Download", "نصب", "Install"),
        ),
        SettingsEntry(
            res(R.string.update_auto_download_wifi), SettingsRoutes.UPDATE, listOf(res(R.string.settings_software_update)),
            { Icons.Default.Wifi }, Hue.TEAL,
            subtitle = res(R.string.update_auto_download_wifi_desc),
            keywords = listOf("آپدیت", "Update", "خودکار", "Auto", "وای فای"),
        ),

        // ---- Smart Iran Routing -------------------------------------------------------------------
        SettingsEntry(
            SText.Lit("رنج‌های آی‌پی ایران", "Iran's IP ranges"), SettingsRoutes.SMART_IRAN, listOf(smartIran),
            { Icons.Default.AltRoute }, Hue.GREEN,
            keywords = listOf("IP", "IPv4", "IPv6", "فهرست", "List"),
        ),
        SettingsEntry(
            SText.Lit("دامنه‌های ایرانی", "Iranian domains"), SettingsRoutes.SMART_IRAN, listOf(smartIran),
            { Icons.Default.AltRoute }, Hue.GREEN,
            keywords = listOf("دامنه", "Domain", ".ir", "سایت", "Site", "فهرست", "List"),
        ),

        // ---- VPN settings ---------------------------------------------------------------------------
        SettingsEntry(
            res(R.string.vpn_always_on), SettingsRoutes.VPN, listOf(res(R.string.vpn_settings_title)),
            { Icons.Default.VpnKey }, Hue.GREEN,
            subtitle = res(R.string.vpn_always_on_desc),
            keywords = listOf("همیشه روشن", "Always on", "Kill switch"),
        ),
        SettingsEntry(
            res(R.string.vpn_mtu_label), SettingsRoutes.VPN, listOf(res(R.string.vpn_settings_title)),
            { Icons.Default.Tune }, Hue.BLUE,
            keywords = listOf("ام تی یو", "Packet", "بسته"),
        ),
        SettingsEntry(
            res(R.string.vpn_quick_tile_count), SettingsRoutes.VPN, listOf(res(R.string.vpn_settings_title)),
            { Icons.Default.Widgets }, Hue.ORANGE,
            keywords = listOf("اتصال سریع", "Quick connect", "سرور", "Server"),
        ),
        SettingsEntry(
            res(R.string.vpn_routing_mode), SettingsRoutes.VPN, listOf(res(R.string.vpn_settings_title)),
            { Icons.Default.Public }, Hue.INDIGO,
            keywords = listOf("مسیریابی", "Routing", "Split tunnel", "تونل", "برنامه", "Apps", "Bypass"),
        ),
        SettingsEntry(
            res(R.string.vpn_selected_apps), SettingsRoutes.VPN, listOf(res(R.string.vpn_settings_title)),
            { Icons.Default.Speed }, Hue.PURPLE,
            keywords = listOf("برنامه", "Apps", "Split tunnel"),
            // Listed only in a routing mode that has a list of apps.
            fallback = listOf(res(R.string.vpn_routing_mode)),
        ),

        // ---- About ------------------------------------------------------------------------------------
        SettingsEntry(
            res(R.string.about_changelog), SettingsRoutes.ABOUT, listOf(res(R.string.about_title)),
            { Icons.Default.NewReleases }, Hue.ORANGE,
            keywords = listOf("تغییرات", "تازه‌ها", "Changelog", "What's new"),
        ),
        SettingsEntry(
            SText.Lit("حمایت از ما", "Support us"), SettingsRoutes.ABOUT, listOf(res(R.string.about_title)),
            { Icons.Default.Favorite }, Hue.PINK,
            keywords = listOf("حمایت", "Support", "Donate", "دونیت", "کمک مالی"),
        ),
        SettingsEntry(
            res(R.string.about_telegram), SettingsRoutes.ABOUT, listOf(res(R.string.about_title)),
            { Icons.Default.Send }, Hue.TEAL,
            keywords = listOf("تلگرام", "Telegram", "کانال", "Channel"),
        ),
        SettingsEntry(
            res(R.string.about_github), SettingsRoutes.ABOUT, listOf(res(R.string.about_title)),
            { Icons.Default.Code }, Hue.GRAY,
            keywords = listOf("گیت هاب", "GitHub", "کد", "Source"),
        ),
        SettingsEntry(
            res(R.string.about_youtube), SettingsRoutes.ABOUT, listOf(res(R.string.about_title)),
            { Icons.Default.PlayArrow }, Hue.RED,
            keywords = listOf("یوتیوب", "YouTube", "ویدیو", "Video"),
        ),
    )

    /** Every page an entry may point at. */
    val routes: Set<String> = setOf(
        SettingsRoutes.ROOT, SettingsRoutes.DISPLAY, SettingsRoutes.WALLPAPER, SettingsRoutes.SMART_IRAN,
        SettingsRoutes.PROXY_MODE, SettingsRoutes.ALLOW_LAN, SettingsRoutes.UPDATE, SettingsRoutes.VPN,
        SettingsRoutes.ABOUT,
    )

    /** The first crumb of every path: the Settings icon's own name. */
    val root: SText = SText.Res(R.string.home_settings)

    /** The app's resources pinned to one language, for the texts the search must know in both. */
    fun localized(context: Context, language: String): Resources {
        val conf = Configuration(context.resources.configuration)
        conf.setLocale(Locale(language))
        return context.createConfigurationContext(conf).resources
    }

    /**
     * Arms [entry]'s row to be shown when its page opens (see [SettingsSpotlight]) and returns the
     * route to open Settings on.
     */
    fun prepare(context: Context, entry: SettingsEntry): String {
        SettingsSpotlight.request(entry.route, (listOf(entry.title) + entry.fallback).map { it.current(context) })
        return entry.route
    }
}
