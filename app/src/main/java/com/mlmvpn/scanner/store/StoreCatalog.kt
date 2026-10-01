package com.mlmvpn.scanner.store

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Waves
import androidx.compose.ui.graphics.Color
import com.mlmvpn.scanner.R

/**
 * Everything the store knows how to update, and where each one's new versions come from.
 *
 * Presentation lives in ui/store; this file is facts only. Worker recognition follows the Windows
 * store exactly: a deployed worker is identified by what its CODE says, never by its name (names
 * are random), and a worker nothing here recognises is the user's own and is never touched.
 */
object StoreCatalog {

    private val Blue = Color(0xFF0A84FF)
    private val Indigo = Color(0xFF5E5CE6)
    private val Green = Color(0xFF32D74B)
    private val Orange = Color(0xFFFF9F0A)
    private val Purple = Color(0xFFBF5AF2)
    private val Pink = Color(0xFFFF375F)
    private val Teal = Color(0xFF40C8E0)
    private val Gray = Color(0xFF8E8E93)
    private val Red = Color(0xFFFF453A)
    private val CfOrange = Color(0xFFF6821F)

    private fun has(t: String, vararg needles: String) = needles.all { t.contains(it) }

    // ── the app ─────────────────────────────────────────────────────────────────────────────────

    val APP = StoreItem(
        id = "mlmvpn", kind = StoreKind.APP,
        titleFa = "MLM VPN", titleEn = "MLM VPN",
        subtitleFa = "خود برنامه", subtitleEn = "The app itself",
        developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
        source = StoreSource.App,
        icon = StoreIcon(Icons.Default.Shield, Blue),
        aboutFa = "نسخهٔ تازهٔ برنامه از صفحهٔ انتشارهای گیت‌هاب خودش، مخصوص پردازندهٔ همین گوشی.",
        aboutEn = "New versions of the app from its own GitHub releases, built for this phone's processor.",
    )

    // ── engines ─────────────────────────────────────────────────────────────────────────────────

    private fun aetherAsset(abi: String): String? = when (abi) {
        "arm64-v8a" -> "aether-android-arm64.tar.gz"
        "armeabi-v7a" -> "aether-android-armv7.tar.gz"
        "x86_64" -> "aether-android-x86_64.tar.gz"
        else -> null
    }

    val ENGINES: List<StoreItem> = listOf(
        StoreItem(
            id = "openvpn", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ OpenVPN", titleEn = "OpenVPN core",
            subtitleFa = "اتصال داخلی OpenVPN و حساب‌های شما", subtitleEn = "Internal OpenVPN connections",
            developer = "OpenVPN Inc. · MLMVPN Android integration", repo = "OpenVPN/openvpn3",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.VpnKey, Orange, imageRes = R.drawable.ic_app_openvpn),
            engine = EngineSpec(libs = listOf("libmlmopenvpn.so"), mode = EngineSpec.Mode.JNI, shipped = "1.0.1"),
            aboutFa = "هسته از سورس رسمی OpenVPN ساخته می‌شود. نسخهٔ سازگار با رابط اندروید MLMVPN، پس از ساخت و آزمون برای پردازندهٔ گوشی، از کانال امضاشده نصب می‌شود. برای استفاده از نسخهٔ جدید برنامه را کامل ببندید و دوباره باز کنید. حساب‌ها و پروفایل‌ها حفظ می‌شوند.",
            aboutEn = "Built from the official OpenVPN source. Android builds compatible with MLMVPN's native interface are tested per ABI and installed through the signed channel. Fully close and reopen the app to use an update. Accounts and profiles are preserved.",
        ),
        StoreItem(
            id = "aether", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ ایتر (Aether)", titleEn = "Aether core",
            subtitleFa = "ایتر، اسکن وارپ و تقویت بازی", subtitleEn = "Aether, WARP scan and Game Boost",
            developer = "CluvexStudio", repo = "CluvexStudio/Aether",
            // The developer publishes an Android build per ABI with every release, so this one is
            // installed straight from THEIR release — digest from GitHub, run once to prove it
            // starts, and only then used.
            source = StoreSource.GithubRelease(
                repo = "CluvexStudio/Aether",
                assetFor = ::aetherAsset,
                format = "tar.gz",
                extract = mapOf("aether" to "libaether.so"),
            ),
            icon = StoreIcon(Icons.Default.Waves, Teal, tint2 = Blue),
            engine = EngineSpec(
                libs = listOf("libaether.so"), mode = EngineSpec.Mode.EXEC,
                probeArgs = listOf("--version"), probeRe = Regex("""aether\s+v?([0-9][0-9.]*)"""),
                // Read out of the shipped binary's own version string. Change it together with
                // the binary: it is the "version in use" until the store installs one.
                shipped = "1.4.0",
            ),
            aboutFa = "ساخت رسمی خودِ سازنده برای اندروید، بدون هیچ تغییری. پیش از استفاده یک بار اجرا می‌شود تا مطمئن شویم روی این گوشی بالا می‌آید.",
            aboutEn = "The developer's own Android build, unmodified. It is run once before use to prove it starts on this phone.",
        ),
        StoreItem(
            id = "geph", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ گف", titleEn = "Geph core",
            subtitleFa = "گف", subtitleEn = "Geph",
            developer = "Gephyra OÜ", repo = "geph-official/geph5",
            // Geph publishes no bare Android binary; its Android app carries the engine as
            // lib/<abi>/libgeph.so, and that is what this installs -- taken out of the build its
            // signed manifest names, digest-checked, unmodified. See scripts/install-geph-binary.js.
            source = StoreSource.SignedManifest(
                mirrors = listOf(
                    "https://f001.backblazeb2.com/file/geph4-dl/geph-releases",
                    "https://sos-ch-dk-2.exo.io/utopia/geph-releases-new",
                ),
                minisignKey = "RWSzEWRCN0AaNpPj+yw0zbOI87jI8PNpnCoITCroKQxRAANAzUawpph7",
                track = "android-stable",
                extract = { abi ->
                    if (abi == "arm64-v8a" || abi == "armeabi-v7a") mapOf("lib/$abi/libgeph.so" to "libgeph.so") else null
                },
            ),
            icon = StoreIcon(Icons.Default.Public, Blue, imageRes = R.drawable.ic_app_geph),
            engine = EngineSpec(
                libs = listOf("libgeph.so"), mode = EngineSpec.Mode.EXEC,
                // The client has no --version; --help proves it starts, and the version is the
                // signed manifest's. Change `shipped` together with the binary.
                probeArgs = listOf("--help"), probeRe = null,
                shipped = "5.9.0",
            ),
            aboutFa = "ساخت رسمی خودِ گف برای اندروید، بدون هیچ تغییری: از بستهٔ برنامهٔ رسمی گف برداشته می‌شود، پس از اینکه امضای فهرست نسخه‌های گف و هش بسته بررسی شد. پیش از استفاده یک بار اجرا می‌شود.",
            aboutEn = "Geph's own Android build, unmodified: taken out of the official Geph app package after the signature on Geph's release list and the package digest are checked. It is run once before use.",
        ),
        StoreItem(
            id = "tor", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ تور", titleEn = "Tor core",
            subtitleFa = "تور", subtitleEn = "Tor",
            developer = "The Tor Project", repo = null,
            // torproject.org publishes no Android executable (Tor Browser embeds it as a library),
            // so this is our build, offered only through the signed channel.
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Security, Purple, imageRes = R.drawable.ic_app_tor),
            engine = EngineSpec(
                libs = listOf("libtor.so"), mode = EngineSpec.Mode.EXEC,
                probeArgs = listOf("--version"), probeRe = Regex("""Tor version ([0-9][0-9.]*[0-9])"""),
                shipped = "0.4.9.11",
            ),
        ),
        StoreItem(
            id = "lyrebird", kind = StoreKind.ENGINE,
            titleFa = "پل‌های تور (lyrebird)", titleEn = "Tor bridges (lyrebird)",
            subtitleFa = "obfs4، وب‌تانل، اسنوفلیک و میک", subtitleEn = "obfs4, WebTunnel, Snowflake and meek",
            developer = "The Tor Project", repo = null,
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Link, Indigo, tint2 = Purple),
            engine = EngineSpec(
                libs = listOf("libobfs4proxy.so"), mode = EngineSpec.Mode.EXEC,
                probeArgs = listOf("-version"), probeRe = Regex("""(?:lyrebird|obfs4proxy)[- ]v?([0-9][0-9.]*)"""),
            ),
        ),
        StoreItem(
            id = "tunnelcore", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ تونل", titleEn = "Tunnel core",
            subtitleFa = "ماسک، وایرگارد و وارپ در وارپ", subtitleEn = "MASQUE, WireGuard and WARP-on-WARP",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Bolt, Blue, tint2 = Indigo),
            engine = EngineSpec(libs = listOf("libtunnelcore.so", "libtunneljni.so"), mode = EngineSpec.Mode.JNI),
        ),
        StoreItem(
            id = "gst", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ تونل گوگل اسکریپت", titleEn = "Google Script tunnel core",
            subtitleFa = "گوگل اسکریپت", subtitleEn = "Google Script",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Bolt, Red, imageRes = R.drawable.ic_app_gscript),
            engine = EngineSpec(libs = listOf("libmhrv_rs.so"), mode = EngineSpec.Mode.JNI),
        ),
        // ── what Android does not let an app swap at runtime ──
        StoreItem(
            id = "xray", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ V2Ray (Xray)", titleEn = "V2Ray core (Xray)",
            subtitleFa = "V2Ray، کانفیگ رایگان، کانفیگ ایران و پنل‌ها", subtitleEn = "V2Ray, free configs, Iran configs and panels",
            developer = "XTLS", repo = "XTLS/Xray-core",
            source = StoreSource.WithApp,
            icon = StoreIcon(Icons.Default.Bolt, Indigo, imageRes = R.drawable.ic_app_v2ray),
            aboutFa = "این هسته به شکل یک کتابخانهٔ جاوا داخل خود برنامه ساخته شده و اندروید اجازهٔ تعویضش را در حین کار نمی‌دهد؛ با هر نسخهٔ تازهٔ برنامه بروز می‌شود. داده‌های مسیریابی‌اش (geoip و geosite) اما جداگانه همین‌جا بروز می‌شوند.",
            aboutEn = "Built into the app as a Java library, which Android does not allow to be swapped while running; it updates with each app version. Its routing data (geoip, geosite) updates separately, right here.",
        ),
        StoreItem(
            id = "psiphon", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ سایفون", titleEn = "Psiphon core",
            subtitleFa = "سایفون", subtitleEn = "Psiphon",
            developer = "Psiphon Inc.", repo = "Psiphon-Labs/psiphon-tunnel-core",
            source = StoreSource.WithApp,
            icon = StoreIcon(Icons.Default.Hub, Red, imageRes = R.drawable.ic_app_psiphon),
            aboutFa = "مثل Xray، کتابخانه‌ای داخل خود برنامه است و با برنامه بروز می‌شود. فهرست سرورهای همراهش جداگانه همین‌جا بروز می‌شود.",
            aboutEn = "Like Xray, a library inside the app that updates with it. Its bundled server list updates separately, right here.",
        ),
        StoreItem(
            id = "amneziawg", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ AmneziaWG", titleEn = "AmneziaWG core",
            subtitleFa = "وارپ رایگان و وایرگارد", subtitleEn = "Free WARP and WireGuard",
            developer = "Amnezia", repo = "amnezia-vpn/amneziawg-android",
            source = StoreSource.WithApp,
            icon = StoreIcon(Icons.Default.VpnKey, Green),
        ),
        StoreItem(
            id = "rstaspoof", kind = StoreKind.ENGINE,
            titleFa = "هستهٔ SNI جعلی", titleEn = "SNI spoof core",
            subtitleFa = "کانفیگ‌های SNI", subtitleEn = "SNI configs",
            developer = "MLM VPN", repo = null,
            source = StoreSource.WithApp,
            icon = StoreIcon(Icons.Default.SwapHoriz, Orange),
            aboutFa = "یک فایل اجرایی ایستا است که فقط از پوشهٔ خود برنامه اجرا می‌شود؛ با برنامه بروز می‌شود.",
            aboutEn = "A static executable that can only run from the app's own folder; it updates with the app.",
        ),
    )

    // ── workers ─────────────────────────────────────────────────────────────────────────────────

    private val ID_NUM = Regex("""WORKER_VERSION\s*=\s*(\d+)""")
    private val BPB_VERSION = Regex("""panelVersion:"([0-9][^"]*)"""")
    private val GOZARGAH_VERSION = Regex("""VERSION\s*=\s*["']([0-9]+\.[0-9]+\.[0-9]+)["']""")
    private val EDG_VERSION = Regex("""const Version = '(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})'""")

    val WORKERS: List<StoreItem> = listOf(
        // First: classify() takes the first match, and Netra's code also carries BPB's markers.
        StoreItem(
            id = "netra", kind = StoreKind.WORKER,
            titleFa = "پنل نترا", titleEn = "Netra panel",
            subtitleFa = "پنل ساده بر پایهٔ BPB", subtitleEn = "A simple panel built on BPB",
            developer = "netrair", repo = com.mlmvpn.scanner.engines.netra.NetraPanel.REPO,
            source = StoreSource.GithubRelease(
                repo = com.mlmvpn.scanner.engines.netra.NetraPanel.REPO,
                assetFor = { com.mlmvpn.scanner.engines.netra.NetraPanel.RELEASE_ASSET },
            ),
            icon = StoreIcon(Icons.Default.Shield, Purple, tint2 = CfOrange),
            worker = WorkerSpec(
                asset = com.mlmvpn.scanner.engines.netra.NetraPanel.ASSET,
                detect = { com.mlmvpn.scanner.engines.netra.NetraPanel.looksLikeNetra(it) },
                // Its code carries BPB's panelVersion, not its own: the bytes decide.
                versionOf = { null },
                mustContain = listOf("_project_:\"Netra\"", "SOURCE_CONTENT"),
            ),
            aboutFa = "مستقیم از انتشار سازنده. فقط کد عوض می‌شود؛ UUID، رمز و مسیر امن (متغیرهای ورکر) و KV همان می‌مانند.",
            aboutEn = "Straight from the developer's release. Only the code changes; the UUID, password and secure path (Worker variables) and KV stay.",
        ),
        StoreItem(
            id = "gozargah", kind = StoreKind.WORKER,
            titleFa = "پنل گذرگاه", titleEn = "Gozargah panel",
            subtitleFa = "چندکاربره با دیتابیس D1", subtitleEn = "Multi-user, on a D1 database",
            developer = "panelgozargah", repo = com.mlmvpn.scanner.engines.gozargah.GozargahPanel.REPO,
            source = StoreSource.GithubRelease(
                repo = com.mlmvpn.scanner.engines.gozargah.GozargahPanel.REPO,
                assetFor = { com.mlmvpn.scanner.engines.gozargah.GozargahPanel.RELEASE_ASSET },
                versionInContent = GOZARGAH_VERSION,
            ),
            icon = StoreIcon(Icons.Default.Storage, Teal, tint2 = Blue),
            worker = WorkerSpec(
                asset = com.mlmvpn.scanner.engines.gozargah.GozargahPanel.ASSET,
                detect = { com.mlmvpn.scanner.engines.gozargah.GozargahPanel.looksLikeGozargah(it) && GOZARGAH_VERSION.containsMatchIn(it) },
                versionOf = { GOZARGAH_VERSION.find(it)?.groupValues?.get(1) },
                mustContain = listOf("GZ_DB"),
            ),
            // Its tables are created and migrated forward by the Worker itself (ensureSchema).
            aboutFa = "مستقیم از انتشار سازنده. فقط کد عوض می‌شود؛ دیتابیس D1 با کاربران و تنظیمات همان می‌ماند و خود پنل جدول‌هایش را به‌روز می‌کند.",
            aboutEn = "Straight from the developer's release. Only the code changes; the D1 database with its users and settings stays, and the panel migrates its own tables.",
        ),
        StoreItem(
            id = "bpb", kind = StoreKind.WORKER,
            titleFa = "پنل BPB", titleEn = "BPB panel",
            subtitleFa = "پنل ابری V2Ray", subtitleEn = "Cloud V2Ray panel",
            developer = "bia-pain-bache", repo = "bia-pain-bache/BPB-Worker-Panel",
            source = StoreSource.GithubRelease(
                repo = "bia-pain-bache/BPB-Worker-Panel",
                assetFor = { "worker.js" },
                versionInContent = BPB_VERSION,
            ),
            icon = StoreIcon(Icons.Default.Cloud, CfOrange, imageRes = R.drawable.ic_app_cloudflare),
            worker = WorkerSpec(
                asset = "worker.js",
                // Netra is a BPB fork carrying these same markers; it is its own item, listed
                // before this one, and never BPB -- BPB's code pushed over it would erase it.
                detect = { has(it, "EMBEDED_SETTINGS", "panelVersion:\"") && !com.mlmvpn.scanner.engines.netra.NetraPanel.looksLikeNetra(it) },
                versionOf = { BPB_VERSION.find(it)?.groupValues?.get(1) },
                // v5 reads its install values from a statement compiled into the script. The new
                // code must still read them, or every deployed panel would stop on the next start.
                mustContain = listOf("EMBEDED_SETTINGS"),
                bpbPrefix = true,
            ),
            aboutFa = "از انتشار رسمی سازنده. تنظیمات هر پنل — UUID، رمز، مسیر و حساب — از پنل فعلی به کد تازه منتقل می‌شود و کاربران و KV دست نمی‌خورند.",
            aboutEn = "From the developer's own release. Each panel's settings — UUID, password, path, account — carry over to the new code; users and KV are untouched.",
        ),
        StoreItem(
            id = "edg", kind = StoreKind.WORKER,
            titleFa = "پنل EDG (edgetunnel)", titleEn = "EDG panel (edgetunnel)",
            subtitleFa = "پروکسی Edge", subtitleEn = "Edge proxy",
            developer = "cmliu", repo = "cmliu/edgetunnel",
            // No releases: the project ships `_worker.js` on its main branch. Read once and pinned
            // to that commit, so what was checked is what is installed.
            source = StoreSource.GithubFile(
                repo = "cmliu/edgetunnel", branch = "main", path = "_worker.js",
                versionInContent = EDG_VERSION,
            ),
            icon = StoreIcon(Icons.Default.Public, CfOrange, tint2 = Orange),
            worker = WorkerSpec(
                asset = "edg_worker.js",
                detect = { EDG_VERSION.containsMatchIn(it) && it.contains("config_JSON") },
                versionOf = { EDG_VERSION.find(it)?.groupValues?.get(1) },
                mustContain = listOf("config_JSON"),
                stripBom = true,
            ),
            aboutFa = "همان متغیرها (UUID، PROXYIP، ADMIN) و همان KV می‌مانند؛ فقط کد عوض می‌شود.",
            aboutEn = "The same variables (UUID, PROXYIP, ADMIN) and KV stay; only the code changes.",
        ),
        StoreItem(
            id = "spider", kind = StoreKind.WORKER,
            titleFa = "پنل اسپایدر", titleEn = "Spider panel",
            subtitleFa = "خروجی‌های مسابقه‌ای و محدودیت IP", subtitleEn = "Racing exits and IP limits",
            developer = "amirh00sain", repo = com.mlmvpn.scanner.engines.spider.SpiderPanel.REPO,
            // No releases and no version in the code: the file on main, pinned to its commit.
            // The version shown is that commit's date and hash.
            source = StoreSource.GithubFile(
                repo = com.mlmvpn.scanner.engines.spider.SpiderPanel.REPO,
                branch = com.mlmvpn.scanner.engines.spider.SpiderPanel.BRANCH,
                path = com.mlmvpn.scanner.engines.spider.SpiderPanel.PATH,
            ),
            icon = StoreIcon(Icons.Default.Hub, CfOrange, tint2 = Purple),
            worker = WorkerSpec(
                asset = com.mlmvpn.scanner.engines.spider.SpiderPanel.ASSET,
                detect = { com.mlmvpn.scanner.engines.spider.SpiderPanel.looksLikeSpider(it) },
                versionOf = { null },
                // The new code must still take the token and domains the deploy writes into it,
                // and still speak the admin API the app drives.
                mustContain = listOf("__PANEL_TOKEN__", "__PANEL_DOMAIN__", "__WORKER_DOMAIN__", "SPIDER_KV", "/panel/config", "/api/users"),
                inject = { target, deployed ->
                    val values = com.mlmvpn.scanner.engines.spider.SpiderPanel.injectedValues(deployed)
                        ?: throw StoreError(tr("کلید و آدرس این پنل از کد فعلی خوانده نشد — برای امنیت دست نخورد.",
                            "This panel's key and address could not be read from its code — it was left alone."))
                    com.mlmvpn.scanner.engines.spider.SpiderPanel.inject(target, values)
                },
                normalize = com.mlmvpn.scanner.engines.spider.SpiderPanel::normalize,
            ),
            aboutFa = "مستقیم از گیت‌هاب سازنده، همان فایل بدون تغییر. کلید مدیریت و آدرس هر پنل از کد فعلی به کد تازه منتقل می‌شود و کاربران، مصرفشان و فهرست خروجی‌ها در KV دست نمی‌خورند.",
            aboutEn = "Straight from the developer's GitHub, the file unchanged. Each panel's admin key and address carry over to the new code; users, their usage and the exit list in KV are untouched.",
        ),
        StoreItem(
            id = "nova", kind = StoreKind.WORKER,
            titleFa = "پنل نوا", titleEn = "Nova panel",
            subtitleFa = "پنل کامل با آی‌پی تمیز و ربات تلگرام", subtitleEn = "A full panel with clean IPs and a Telegram bot",
            developer = "IRNova", repo = com.mlmvpn.scanner.engines.nova.NovaPanel.REPO,
            // PolyForm Noncommercial and shipped obfuscated: never bundled, always the developer's
            // own file on main, pinned to its commit (the install also checks its published SHA-256).
            source = StoreSource.GithubFile(
                repo = com.mlmvpn.scanner.engines.nova.NovaPanel.REPO,
                branch = com.mlmvpn.scanner.engines.nova.NovaPanel.BRANCH,
                path = com.mlmvpn.scanner.engines.nova.NovaPanel.PATH,
            ),
            icon = StoreIcon(Icons.Default.AutoAwesome, Color(0xFF5E5CE6), tint2 = CfOrange),
            worker = WorkerSpec(
                asset = com.mlmvpn.scanner.engines.nova.NovaPanel.ASSET,
                detect = { com.mlmvpn.scanner.engines.nova.NovaPanel.looksLikeNova(it) },
                versionOf = { null },
                // The new code must still speak the first-run and subscription API the app drives.
                mustContain = listOf("/install/set", "admin/sub-content", "IRNova"),
            ),
            aboutFa = "مستقیم از گیت‌هاب سازنده (مجوز غیرتجاری PolyForm). فقط کد عوض می‌شود؛ رمز، کاربران و تنظیمات در D1 و KV همان می‌مانند.",
            aboutEn = "Straight from the developer's GitHub (PolyForm Noncommercial). Only the code changes; the password, users and settings in D1 and KV stay.",
        ),
        StoreItem(
            id = "nahan", kind = StoreKind.WORKER,
            titleFa = "پنل نهان", titleEn = "Nahan panel",
            subtitleFa = "پنل کاربران نهان", subtitleEn = "Nahan user panel",
            developer = "Project Nahan", repo = null,
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Storage, Indigo, tint2 = Purple),
            worker = WorkerSpec(
                asset = "nahan_worker.js",
                detect = { has(it, "Project Nahan", "CURRENT_VERSION") },
                versionOf = { Regex("""CURRENT_VERSION = "([0-9][0-9.]*)"""").find(it)?.groupValues?.get(1) },
                mustContain = listOf("Project Nahan"),
            ),
        ),
        StoreItem(
            id = "studio", kind = StoreKind.WORKER,
            titleFa = "پنل MLM (کانفیگ استدیو)", titleEn = "MLM panel (Config Studio)",
            subtitleFa = "کاربران، کانفیگ‌ها و ساب", subtitleEn = "Users, configs and subscriptions",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Layers, Indigo, tint2 = Blue),
            worker = WorkerSpec(
                asset = "mlm_worker.js",
                detect = { has(it, "STUDIO_API_VERSION", "STUDIO_ROUTE") },
                versionOf = { Regex("""STUDIO_API_VERSION\s*=\s*(\d+)""").find(it)?.groupValues?.get(1) },
                mustContain = listOf("STUDIO_API_VERSION", "STUDIO_ROUTE"),
                // Its database migrates forward and never back, and it runs Durable Objects:
                // updated through its own deployer, which checks the build first.
                update = WorkerSpec.Update.STUDIO,
            ),
            aboutFa = "با استقرارکنندهٔ خود کانفیگ استدیو بروز می‌شود: دیتابیس را جلو می‌برد و هرگز نسخهٔ جدیدتر را با قدیمی‌تر عوض نمی‌کند.",
            aboutEn = "Updated through Config Studio's own deployer: it migrates the database forward and never replaces a newer build with an older one.",
        ),
        StoreItem(
            id = "dns", kind = StoreKind.WORKER,
            titleFa = "DNS اختصاصی", titleEn = "Dedicated DNS",
            subtitleFa = "تقویت بازی", subtitleEn = "Game Boost",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Dns, Green, imageRes = R.drawable.ic_app_dns),
            worker = WorkerSpec(
                asset = "dns_worker.js",
                detect = { it.contains("Dedicated DNS relay worker") },
                versionOf = { null },
            ),
        ),
        StoreItem(
            id = "gst-relay", kind = StoreKind.WORKER,
            titleFa = "رلهٔ کلودفلر گوگل اسکریپت", titleEn = "Google Script Cloudflare relay",
            subtitleFa = "شتاب‌دهندهٔ گوگل اسکریپت", subtitleEn = "Google Script accelerator",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Bolt, Red, tint2 = Orange),
            worker = WorkerSpec(
                asset = "gst_relay_worker.js",
                detect = { has(it, "GST Relay Worker", "relayOne") },
                versionOf = { ID_NUM.find(it)?.groupValues?.get(1) },
            ),
        ),
        StoreItem(
            id = "vpngate-relay", kind = StoreKind.WORKER,
            titleFa = "رلهٔ فهرست VPN Gate", titleEn = "VPN Gate list relay",
            subtitleFa = "گیت‌وی", subtitleEn = "Gateway",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Public, Indigo),
            worker = WorkerSpec(
                asset = "vpngate_relay_worker.js",
                detect = { it.contains("The VPN Gate server list, relayed through the user") },
                versionOf = { null },
            ),
        ),
        StoreItem(
            id = "gemini-exit", kind = StoreKind.WORKER,
            titleFa = "خروجی جمنای", titleEn = "Gemini exit",
            subtitleFa = "جمنای و برنامه‌های گوگل", subtitleEn = "Gemini and Google apps",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.SmartToy, Blue, tint2 = Purple),
            worker = WorkerSpec(
                asset = "gemini_exit_worker.js",
                detect = { it.contains("The Gemini exit") },
                versionOf = { null },
            ),
        ),
        StoreItem(
            id = "warp-id", kind = StoreKind.WORKER,
            titleFa = "رلهٔ هویت وارپ", titleEn = "WARP identity relay",
            subtitleFa = "وایرگارد، وارپ در وارپ و ماسک", subtitleEn = "WireGuard, WARP-in-WARP and MASQUE",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.VpnKey, Orange, tint2 = CfOrange),
            worker = WorkerSpec(
                asset = "warp_id_worker.js",
                detect = { it.contains("The WARP identity relay") },
                versionOf = { null },
            ),
        ),
        StoreItem(
            id = "sub", kind = StoreKind.WORKER,
            titleFa = "سازندهٔ لینک ساب", titleEn = "Sub link generator",
            subtitleFa = "لینک ساب", subtitleEn = "Sub links",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Link, Purple),
            worker = WorkerSpec(
                asset = "sub_worker.js",
                detect = { has(it, "/_health", "sub_\${slug}") },
                versionOf = { Regex("""version:\s*"([0-9][0-9.]*)"""").find(it)?.groupValues?.get(1) },
            ),
        ),
        StoreItem(
            id = "gt-broker", kind = StoreKind.WORKER,
            titleFa = "سرویس کلید گیت‌هاب تانل", titleEn = "GitHub Tunnel key service",
            subtitleFa = "گیت‌هاب تانل", subtitleEn = "GitHub Tunnel",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Cloud, Gray, imageRes = R.drawable.ic_app_github),
            worker = WorkerSpec(
                asset = "gt/broker_worker.js",
                detect = { has(it, "TS_OAUTH_CLIENT_ID", "GitHub Tunnel") },
                versionOf = { ID_NUM.find(it)?.groupValues?.get(1) },
            ),
        ),
    )

    // ── data ────────────────────────────────────────────────────────────────────────────────────

    val DATA: List<StoreItem> = listOf(
        StoreItem(
            id = "cf-doctor-plan", kind = StoreKind.DATA,
            titleFa = "برنامهٔ تست دکتر کلادفلر", titleEn = "Cloudflare Doctor test plan",
            subtitleFa = "تست‌های تشخیص اتصال", subtitleEn = "Connection diagnostic tests",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Public, Blue, imageRes = R.drawable.ic_app_doctor),
            data = DataSpec("cf_doctor_plan.json", DataSpec.Target.OVERLAY, json = true),
        ),
        StoreItem(
            id = "geosite", kind = StoreKind.DATA,
            titleFa = "دادهٔ مسیریابی دامنه‌ها (geosite)", titleEn = "Domain routing data (geosite)",
            subtitleFa = "Xray — قوانین category-ir و تبلیغات", subtitleEn = "Xray — category-ir and ad rules",
            developer = "Loyalsoldier", repo = "Loyalsoldier/v2ray-rules-dat",
            source = StoreSource.GithubRelease(repo = "Loyalsoldier/v2ray-rules-dat", assetFor = { "geosite.dat" }, tagToVersion = { it }),
            icon = StoreIcon(Icons.Default.Map, Teal, tint2 = Green),
            data = DataSpec("geosite.dat", DataSpec.Target.FILES_DIR, minBytes = 100_000),
        ),
        StoreItem(
            id = "geoip", kind = StoreKind.DATA,
            titleFa = "دادهٔ مسیریابی آی‌پی‌ها (geoip)", titleEn = "IP routing data (geoip)",
            subtitleFa = "Xray — قوانین geoip:ir و شبکه‌های خصوصی", subtitleEn = "Xray — geoip:ir and private ranges",
            developer = "Loyalsoldier", repo = "Loyalsoldier/v2ray-rules-dat",
            source = StoreSource.GithubRelease(repo = "Loyalsoldier/v2ray-rules-dat", assetFor = { "geoip.dat" }, tagToVersion = { it }),
            icon = StoreIcon(Icons.Default.Public, Teal, tint2 = Blue),
            data = DataSpec("geoip.dat", DataSpec.Target.FILES_DIR, minBytes = 100_000),
        ),
        StoreItem(
            id = "iran-configs", kind = StoreKind.DATA,
            titleFa = "کانفیگ‌های ایران", titleEn = "Iran configs",
            subtitleFa = "کانفیگ ایران", subtitleEn = "Iran configs",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Flag, Green, imageRes = R.drawable.ic_app_iran),
            data = DataSpec("iran_profiles.json", DataSpec.Target.OVERLAY, json = true),
        ),
        StoreItem(
            id = "mitm-config", kind = StoreKind.DATA,
            titleFa = "کانفیگ دامین فرانتینگ", titleEn = "Domain fronting config",
            subtitleFa = "دامین فرانتینگ", subtitleEn = "Domain fronting",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.SwapHoriz, Orange, tint2 = Color(0xFFFFD60A)),
            data = DataSpec("mitm_domainfronting_v23.json", DataSpec.Target.OVERLAY, json = true),
        ),
        StoreItem(
            id = "psiphon-servers", kind = StoreKind.DATA,
            titleFa = "فهرست سرورهای سایفون", titleEn = "Psiphon server list",
            subtitleFa = "سایفون — سرورهای شروع اتصال", subtitleEn = "Psiphon — bootstrap servers",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Hub, Red, tint2 = Pink),
            data = DataSpec("server_entries.txt", DataSpec.Target.OVERLAY, minBytes = 1000),
        ),
        StoreItem(
            id = "sanction-domains", kind = StoreKind.DATA,
            titleFa = "فهرست دامنه‌های تحریمی", titleEn = "Sanctioned domains list",
            subtitleFa = "ضد تحریم", subtitleEn = "Anti-sanction",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.Shield, Green),
            data = DataSpec("sanction_domains.json", DataSpec.Target.OVERLAY, json = true),
        ),
        StoreItem(
            id = "game-catalog", kind = StoreKind.DATA,
            titleFa = "فهرست بازی‌ها", titleEn = "Game catalogue",
            subtitleFa = "تقویت بازی", subtitleEn = "Game Boost",
            developer = "MLM VPN", repo = "mlmvpn/mlmvpn_android",
            source = StoreSource.Channel,
            icon = StoreIcon(Icons.Default.SportsEsports, Green, tint2 = Teal),
            data = DataSpec("game_catalog.json", DataSpec.Target.OVERLAY, json = true),
        ),
    )

    val ALL: List<StoreItem> = listOf(APP) + ENGINES + WORKERS + DATA
    private val BY_ID = ALL.associateBy { it.id }

    fun byId(id: String): StoreItem? = BY_ID[id]

    private val BY_ASSET: Map<String, String> =
        (WORKERS.mapNotNull { w -> w.worker?.let { it.asset to w.id } } +
            DATA.mapNotNull { d -> d.data?.takeIf { it.target == DataSpec.Target.OVERLAY }?.let { it.file to d.id } }).toMap()

    fun itemIdForAsset(asset: String): String? = BY_ASSET[asset]

    /** Which worker a deployed script is, or null when it is the user's own. */
    fun classify(code: String): StoreItem? = WORKERS.firstOrNull { w ->
        runCatching { w.worker!!.detect(code) }.getOrDefault(false)
    }
}
