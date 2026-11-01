package com.mlmvpn.scanner.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * «تنظیمات اصلی»: what Settings holds for the whole app. Left out on purpose: when updates were last
 * checked, which engine ran last (the Quick Settings tile's replay), how long a LAN share still has
 * to run, and the old split-tunnel file the current one replaced.
 */
internal object MainItems {

    val all: List<BackupContributor> = listOf(
        SimpleItem(
            "main_language", BackupKind.MAIN, "زبان برنامه", "App language",
            listOf(PrefsSlice("app_locale_prefs", whole = true)),
        ),
        LookItem,
        SimpleItem(
            "main_network", BackupKind.MAIN, "شبکه: DNS، پورت، پراکسی، MTU، برنامه‌های بیرون از VPN", "Network: DNS, port, proxy, MTU, apps outside the VPN",
            listOf(
                PrefsSlice("app_settings", keys = setOf("backend_dns")),
                PrefsSlice(
                    DEFAULT_PREFS,
                    keys = setOf(
                        "local_port", "proxy_mode", "proxy_mode_acknowledged", "tls_unfilter_v2", "google_fix",
                        "gemini_exit", "vpn_routing_mode", "vpn_routing_apps",
                    ),
                ),
                PrefsSlice("vpn_routing_prefs", whole = true),
            ),
        ),
        SimpleItem(
            "main_sharing", BackupKind.MAIN, "اشتراک با دستگاه‌های دیگر", "Sharing with other devices",
            listOf(
                PrefsSlice(DEFAULT_PREFS, keys = setOf("allow_lan", "lan_own_network_only")),
                PrefsSlice("app_settings", keys = setOf("lan_password", "lan_blocked_clients")),
            ),
        ),
        SimpleItem(
            "main_smart_iran", BackupKind.MAIN, "مسیر هوشمند ایران", "Smart Iran routing",
            listOf(PrefsSlice("smart_iran", whole = true)),
        ),
        SimpleItem(
            "main_general", BackupKind.MAIN, "سایر تنظیمات برنامه", "Other app settings",
            listOf(
                PrefsSlice(DEFAULT_PREFS, keys = setOf("screen_off_timeout", "show_realtime_traffic", "enable_usage_tracking", "quick_tile_count")),
                PrefsSlice("app_settings", keys = setOf("update_auto_download_wifi")),
                PrefsSlice("cfnet_ui", whole = true),
            ),
        ),
    )

    /**
     * «ظاهر و صفحهٔ اصلی»: theme, sizes, the wallpaper (a picked image too) and the icon board with
     * its folders. The board keeps ids only; a newer or older app reconciles it with the tiles it has.
     */
    private object LookItem : SimpleItem(
        "main_look", BackupKind.MAIN, "ظاهر، پس‌زمینه و چیدمان صفحهٔ اصلی", "Look, wallpaper and home screen",
        listOf(
            PrefsSlice(
                DEFAULT_PREFS,
                keys = setOf(
                    "home_icon_scale", "app_theme", "wallpaper_everywhere", "home_dock_labels", "app_text_scale",
                    "app_text_scale_auto", "home_label_scale", "wallpaper_id", "wallpaper_blur",
                    "home_icon_order", "home_folders", "home_folders_v1", "home_scout_promoted_v1", "home_lantern_promoted_v1",
                    "home_connection_widget", "home_widgetstudio_promoted_v1",
                ),
            ),
        ),
    ) {
        private const val WALLPAPER = "files/wallpaper.img"
        /** A photo straight off a camera can be 10 MB; past this it is re-encoded at the size the screen can show. */
        private const val KEEP_AS_IS = 4L * 1024 * 1024
        private const val MAX_EDGE = 2560

        override fun export(ctx: Context): JSONObject? {
            val base = super.export(ctx)
            val f = dataFile(ctx, WALLPAPER)
            val image = if (f.isFile && f.length() > 0) runCatching { wallpaperBytes(f) }.getOrNull() else null
            if (base == null && image == null) return null
            return (base ?: JSONObject()).apply { image?.let { put("wallpaper", B64.encode(it)) } }
        }

        override fun summary(data: JSONObject): String? = super.summary(data)

        override fun plan(ctx: Context, data: JSONObject, dataVersion: Int, mode: RestoreMode, plan: RestorePlan) {
            super.plan(ctx, data, dataVersion, mode, plan)
            val b64 = data.optString("wallpaper", "")
            if (b64.isNotEmpty()) plan.putFile(WALLPAPER, B64.decode(b64))
            else if (mode == RestoreMode.REPLACE && dataFile(ctx, WALLPAPER).exists()) plan.deleteFile(WALLPAPER)
        }

        private fun wallpaperBytes(f: java.io.File): ByteArray? {
            if (f.length() <= KEEP_AS_IS) return f.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            return try {
                val edge = maxOf(bmp.width, bmp.height)
                val scaled = if (edge <= MAX_EDGE) bmp else {
                    val k = MAX_EDGE.toFloat() / edge
                    Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                }
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    if (scaled !== bmp) scaled.recycle()
                    out.toByteArray()
                }
            } finally {
                bmp.recycle()
            }
        }
    }
}
