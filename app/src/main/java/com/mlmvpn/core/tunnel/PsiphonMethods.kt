package com.mlmvpn.core.tunnel

import android.content.Context
import com.mlmvpn.scanner.store.tr

/**
 * «روش اتصال» of «سایفون»: the ways Psiphon is reached, named and explained exactly as the desktop's
 * window lists them (psiphon-manager.js `LADDER`, plus server.js's «پروکسی سرور»).
 *
 *  - **A** domain fronting: ends on a CDN edge, so there is no Psiphon address to block.
 *  - **E** clean IP: rung A dialled at clean edge addresses found and proved on this line
 *    ([PsiphonCleanIps]); only when there are some.
 *  - **D** every protocol, straight to Psiphon's own servers.
 *  - **C** in-proxy: through volunteers' Conduit stations over WebRTC.
 *  - **P** personal Conduit: only through the station of a pairing link ([PsiphonPairing]); only
 *    when asked for by name, never part of the automatic ladder (a friend's station may be off).
 *  - **X** «پروکسی سرور»: Psiphon reaches its servers THROUGH an HTTP/SOCKS5 proxy
 *    ([PsiphonUpstream]), then Psiphon's own ways if no proxy carries.
 *
 * The tile's ladder ([TunnelVpnService]) runs them; the screen lets one be pinned.
 */
object PsiphonMethods {

    /** No pin: the ladder, from the rung that last carried a tunnel on this device. */
    const val AUTO = ""

    /** The automatic ladder's order, before the remembered rung is moved to the front. */
    val LADDER_ORDER = listOf("A", "E", "D", "C", "P")

    /** Every choice the screen offers, in the order it lists them. */
    val ALL = listOf("A", "E", "D", "C", "P", "X")

    /** The method pinned on the screen, or [AUTO]. */
    fun pinned(ctx: Context): String =
        prefs(ctx).getString(PREF, AUTO).orEmpty().takeIf { it in ALL } ?: AUTO

    fun setPinned(ctx: Context, name: String) {
        prefs(ctx).edit().putString(PREF, name.takeIf { it in ALL } ?: AUTO).apply()
    }

    /** Seconds one rung may take before the next one gets its turn. «پروکسی سرور»: per proxy. */
    fun seconds(name: String): Int = when (name) {
        "A" -> 60
        "E" -> 45
        "D" -> 45
        "C" -> 75
        "P" -> 90
        "X" -> 45
        else -> 45
    }

    fun label(name: String): String = when (name) {
        "A" -> tr("دامین‌فرانتینگ (CDN)", "Domain fronting (CDN)")
        "E" -> tr("آی‌پی تمیز", "Clean IP")
        "D" -> tr("همهٔ پروتکل‌ها (مستقیم)", "All protocols (direct)")
        "C" -> tr("رله از طریق کاربران دیگر", "Relay through other users")
        "P" -> tr("کاندوئیت شخصی", "Personal Conduit")
        "X" -> tr("پروکسی سرور", "Proxy server")
        else -> tr("خودکار", "Automatic")
    }

    fun desc(name: String): String = when (name) {
        "A" -> tr(
            "اتصال روی لبهٔ یک CDN بزرگ (اکامای) تمام می‌شود و آدرسی از سایفون دیده نمی‌شود که بشود مسدودش کرد. روی بیشتر اینترنت‌های ایران اولین گزینه است.",
            "The connection ends on the edge of a big CDN (Akamai), so no Psiphon address is visible to block. On most Iranian internets this is the first choice.",
        )
        "E" -> tr(
            "همان دامین‌فرانتینگ، ولی به آی‌پی‌های تمیزی که در بخش «آی‌پی تمیز» روی اینترنت خودتان پیدا و آزموده شده‌اند — معمولاً سریع‌تر.",
            "The same domain fronting, but to the clean IPs found and tested on your own internet in «Clean IP» -- usually faster.",
        )
        "D" -> tr(
            "مستقیم به سرورهای خود سایفون، با همهٔ پروتکل‌هایش. هرجا آدرس سرورها باز باشد سریع‌ترین است؛ روی اینترنتی که آن‌ها را بسته، وصل نمی‌شود.",
            "Straight to Psiphon's own servers, with all its protocols. The fastest wherever their addresses are open; it does not connect on an internet that blocks them.",
        )
        "C" -> tr(
            "داوطلبانی بیرون از ایران برنامهٔ «Conduit» سایفون را روی گوشی یا کامپیوترشان روشن گذاشته‌اند؛ اتصال شما از دستگاه یکی از آن‌ها (با WebRTC، مثل یک تماس تصویری) به سرور سایفون می‌رسد. آدرس این دستگاه‌ها خانگی است و در فهرست مسدودی نیست، پس وقتی همه‌چیز بسته است هم کار می‌کند. داوطلب فقط ترافیک رمزشده را می‌بیند، نه سایت‌ها را. کندتر است و پیدا شدن داوطلب تا یک دقیقه طول می‌کشد.",
            "Volunteers outside Iran keep Psiphon's «Conduit» app running on their phone or computer; your connection reaches a Psiphon server through one of their devices (over WebRTC, like a video call). Their addresses are residential and on no block list, so it works even when everything else is blocked. The volunteer sees only encrypted traffic, not the sites. Slower, and finding a volunteer can take up to a minute.",
        )
        "P" -> tr(
            "فقط از ایستگاه Conduit دوست یا آشنایتان که لینک جفت‌سازی‌اش را در بخش «کاندوئیت» گذاشته‌اید. اگر آن ایستگاه خاموش باشد وصل نمی‌شود.",
            "Only through the Conduit station of a friend whose pairing link you added in «Conduit». If that station is off, it does not connect.",
        )
        "X" -> tr(
            "مثل «پروکسی» در اپ رسمی سایفون: سایفون از راه یک پروکسی HTTP یا SOCKS5 به سرورهایش وصل می‌شود — پروکسی خودتان، یا یکی از فهرست آماده که از اینترنت خودتان سنجیده و با سایفون واقعاً وصل شده است. کشور خروج را عوض نمی‌کند؛ راه ورود است وقتی راه‌های خود سایفون بسته یا کند است.",
            "Like «Proxy» in the official Psiphon app: Psiphon reaches its servers through an HTTP or SOCKS5 proxy -- your own, or one from the ready list that was measured on your own internet and really connected with Psiphon. It does not change the exit country; it is a way in when Psiphon's own ways are blocked or slow.",
        )
        else -> tr("روش‌ها به‌ترتیب، تا یکی جواب دهد", "The methods in turn, until one answers")
    }

    /**
     * Which rung carried the last tunnel, per mode -- plain and inside WARP are different ladders, so a
     * plain win on C must never become the first rung inside WARP, where C cannot work. By NAME: an
     * index meant a different rung every time the ladder changed shape, and the clean-IP rung comes
     * and goes with the list.
     */
    fun remembered(ctx: Context, chained: Boolean): String? {
        val p = prefs(ctx)
        p.getString(lastKey(chained), null)?.takeIf { it in LADDER_ORDER }?.let { return it }
        // The index this replaced, read once for the ladder shape that wrote it.
        val old = if (chained) "psiphon_winning_strategy_chained" else "psiphon_winning_strategy"
        val shape = p.getString(old + "_shape", null)?.split(',') ?: return null
        val name = shape.getOrNull(p.getInt(old, -1))?.takeIf { it in LADDER_ORDER } ?: return null
        p.edit().putString(lastKey(chained), name).apply()
        return name
    }

    fun remember(ctx: Context, chained: Boolean, name: String) {
        if (name in LADDER_ORDER) prefs(ctx).edit().putString(lastKey(chained), name).apply()
    }

    private fun lastKey(chained: Boolean) = if (chained) "psiphon_last_rung_chained" else "psiphon_last_rung"

    private const val PREF = "psiphon_method"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
