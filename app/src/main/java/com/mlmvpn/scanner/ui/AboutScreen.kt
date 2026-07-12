package com.mlmvpn.scanner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mlmvpn.scanner.R

data class ChangelogItem(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

data class ChangelogVersion(
    val versionTitle: String,
    val items: List<ChangelogItem>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val primaryColor = Color(0xFF4285F4)
    val bgColor = Color(0xFF121212)
    val surfaceColor = Color(0xFF1E1E1E)
    val textColor = Color(0xFFE8EAED)
    val mutedColor = Color(0xFF9AA0A6)

    var showChangelog by remember { mutableStateOf(false) }

    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    val appIcon = remember {
        try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = android.graphics.Bitmap.createBitmap(
                drawable.intrinsicWidth.takeIf { it > 0 } ?: 200,
                drawable.intrinsicHeight.takeIf { it > 0 } ?: 200,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    val isFa = com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale().language == "fa"
    val changelogTitle = if (isFa) "لیست تغییرات جدید" else "What's New (Changelog)"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            Surface(
                color = surfaceColor,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.about_title),
                        color = primaryColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = "Logo",
                        modifier = Modifier.size(96.dp).clip(CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Logo",
                        modifier = Modifier.size(96.dp),
                        tint = primaryColor
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("MLMVPN", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                Text(stringResource(R.string.about_version, versionName), color = mutedColor, fontSize = 16.sp)

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.about_desc),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Justify,
                    lineHeight = 24.sp
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Changelog Button
                Button(
                    onClick = { showChangelog = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.NewReleases, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(changelogTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(32.dp))
                Divider(color = Color(0xFF333333))
                Spacer(modifier = Modifier.height(24.dp))

                Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    AboutLinkRowCustom(stringResource(R.string.about_telegram), "t.me/mlmvpn", Icons.Default.Send, primaryColor) {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/mlmvpn")))
                    }
                    AboutLinkRowCustom(stringResource(R.string.about_github), "github.com/mlmvpn", Icons.Default.Code, primaryColor) {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/mlmvpn")))
                    }
                    AboutLinkRowCustom(stringResource(R.string.about_youtube), "youtube.com/@marketmlm", Icons.Default.PlayArrow, primaryColor) {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/@marketmlm")))
                    }
                }

                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }

    if (showChangelog) {
        ChangelogModal(isFa = isFa, onDismiss = { showChangelog = false })
    }
}

@Composable
fun AboutLinkRowCustom(title: String, url: String, icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(text = url, color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
fun ChangelogModal(isFa: Boolean, onDismiss: () -> Unit) {
    val primaryColor = Color(0xFF4285F4)
    val surfaceColor = Color(0xFF2D2D2D)
    
    val title = if (isFa) "لیست تغییرات جدید" else "Changelog"
    
    val versions = remember(isFa) {
        if (isFa) {
            listOf(
                ChangelogVersion(
                    "نسخه 1.0.8",
                    listOf(
                        ChangelogItem(
                            "وایرگارد اختصاصی امارات برای کاهش پینگ",
                            "افزودن سرور اختصاصی وایرگارد در امارات مخصوص کاهش پینگ بازی‌ها، با مسیر بهینه و پایدار. قابل استفاده برای همه‌ی بازی‌های فهرست تب گیم.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "بازطراحی و یکپارچه‌سازی تب گیم",
                            "تب گیم به‌طور کامل بازطراحی شد؛ حالت خودکار (AUTO) اکنون همه‌ی روش‌های کاهش پینگ را با سنجش پینگ واقعی مقایسه می‌کند و بهترین گزینه را پیشنهاد می‌دهد. رابط کاربری ساده‌تر و یکدست‌تر شد.",
                            Icons.Default.FlashOn
                        ),
                        ChangelogItem(
                            "DNS اختصاصی کلادفلر با انتخاب کشور",
                            "بهینه‌سازی کامل DNS اختصاصی کلادفلر با هدایت هوشمند بر اساس کشور، برای رسیدن به نزدیک‌ترین و کم‌تأخیرترین سرور بازی روی اتصال شما.",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "DNS اختصاصی امارات (رایگان و نامحدود)",
                            "افزودن سرویس DNS اختصاصی روی سرور امارات با ارتباط رمزنگاری‌شده و امن، به‌عنوان گزینه‌ای سبک و پایدار برای کاهش پینگ بدون نیاز به تونل کامل.",
                            Icons.Default.Language
                        ),
                        ChangelogItem(
                            "بهبود پایداری و رفع باگ",
                            "رفع چند مشکل گزارش‌شده و افزایش پایداری کلی برنامه برای تجربه‌ای روان‌تر و مطمئن‌تر.",
                            Icons.Default.CheckCircle
                        )
                    )
                ),
                ChangelogVersion(
                    "نسخه 1.0.8 beta",
                    listOf(
                        ChangelogItem(
                            "پنل جدید Deno (میزبانی رایگان بدون کارت)",
                            "اضافه‌شدن پنل Deno برای ساخت کاملاً خودکار سرور اختصاصی، بدون نیاز به کارت اعتباری. پشتیبانی از چند اکانت با جابجایی آسان و نمایش آمار مصرف (روزانه/هفتگی/ماهانه به‌همراه آپلود و دانلود) هر اکانت.",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "کانفیگ‌های VLESS و Trojan روی WS و xHTTP",
                            "سرور Deno هم VLESS و هم Trojan را روی دو بستر WebSocket و xHTTP پشتیبانی می‌کند. دکمه‌ی «دریافت کانفیگ xHTTP� با یک کلیک دو کانفیگ (VLESS و Trojan) می‌سازد. کانفیگ‌ها به تب اختصاصی DENO و بخش ترکیب اسکنر هم اضافه می‌شوند.",
                            Icons.Default.Bolt
                        ),
                        ChangelogItem(
                            "بهینه‌سازی مصرف و پایداری Deno",
                            "بستن خودکار اتصال‌های بیکار برای کاهش مصرف و دووم بیشتر سرورهای رایگان، به‌همراه مسیردهی هوشمند درخواست‌ها از داخل تونل و راهنمای کامل در بخش پرسش و پاسخ.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "دکمه‌ی اتصال سریع در پنل بالای گوشی",
                            "افزودن دکمه‌ی mlmvpn به پنل تنظیمات سریع (Quick Settings) گوشی؛ با یک لمس، VPN روشن/خاموش می‌شود و از میان سرورهای اخیر شما تأخیر (delay) گرفته و به سریع‌ترین متصل می‌شود. تعداد سرورها در تنظیمات VPN قابل تغییر است.",
                            Icons.Default.Bolt
                        ),
                        ChangelogItem(
                            "کاهش چشمگیر حجم برنامه",
                            "حذف فایل‌های بلااستفاده و فعال‌سازی فشرده‌سازی کد برای کم‌کردن قابل‌توجه حجم برنامه، ضمن حفظ حالت یونیورسال و نصب روی همه‌ی گوشی‌ها (از قدیمی تا جدیدترین).",
                            Icons.Default.Compress
                        ),
                        ChangelogItem(
                            "سیستم گیم بوستر اختصاصی",
                            "اضافه‌شدن هوشمندترین سیستم گیم بوستر با قابلیت تشخیص و انتخاب بهترین مسیر (مستقیم یا تونل) جهت کاهش حداکثری پینگ و تجربه بازی بدون لگ.",
                            Icons.Default.FlashOn
                        ),
                        ChangelogItem(
                            "رفع مشکلات ظاهری",
                            "بهینه‌سازی و برطرف‌سازی باگ‌های بصری رابط کاربری برای تجربه‌ای یکپارچه‌تر و چشم‌نوازتر.",
                            Icons.Default.Palette
                        ),
                        ChangelogItem(
                            "بهبود پایداری و رفع باگ",
                            "بررسی و رفع تمامی مشکلات گزارش شده توسط کاربران عزیز به منظور ارتقای کیفیت و سرعت برنامه.",
                            Icons.Default.BugReport
                        )
                    )
                ),
                ChangelogVersion(
                    "نسخه 1.0.7",
                    listOf(
                        ChangelogItem(
                            "متد ارتباطی بدون سرور (Serverless)",
                            "اضافه‌شدن متد جدید برای استفاده در شرایط سخت و اختلالات اینترنت. این متد دارای ۵ سرور پیش‌فرض آماده به کار است. (نکته: این متد مختص دسترسی به سایت‌های بدون تحریم است و به‌دلیل استفاده از آی‌پی ایران، برای تلگرام کاربرد ندارد).",
                            Icons.Default.Language
                        ),
                        ChangelogItem(
                            "پنل اختصاصی MLMVPN",
                            "اضافه‌شدن پنل قدرتمند MLMVPN با قابلیت ارائه کانفیگ‌های پایدار XHTTP.",
                            Icons.Default.Settings
                        ),
                        ChangelogItem(
                            "بهینه‌سازی هوشمند اسکنر",
                            "بازنویسی و ارتقای کامل دقت اسکنر، تست‌های تأخیر (Delay)، پینگ و سرعت.",
                            Icons.Default.Search
                        ),
                        ChangelogItem(
                            "ارتقای پایداری اتصالات",
                            "افزایش چشمگیر پایداری اتصالات و حل مشکل نمایش پینگ کاذب بدون اینترنت.",
                            Icons.Default.CheckCircle
                        ),
                        ChangelogItem(
                            "بهبودهای عمومی",
                            "رفع مشکلات گزارش‌شده توسط کاربران جهت تجربه کاربری روان‌تر.",
                            Icons.Default.BugReport
                        )
                    )
                ),
                ChangelogVersion(
                    "نسخه 1.0.6",
                    listOf(
                        ChangelogItem(
                            "سیستم ساخت ساب‌لینک",
                            "اضافه شدن سیستم قدرتمند و اختصاصی جهت ساخت ساب‌لینک.",
                            Icons.Default.Link
                        ),
                        ChangelogItem(
                            "رفع مشکلات گزارش شده",
                            "برطرف‌سازی باگ‌ها و ارتقای عملکرد برنامه.",
                            Icons.Default.BugReport
                        )
                    )
                ),
                ChangelogVersion(
                    "نسخه 1.0.51",
                    listOf(
                        ChangelogItem(
                            "سازگاری کامل با اندروید ۱۵ و ۱۶",
                            "حل ریشه‌ای مشکل هم‌پوشانی و قرار گرفتن گزینه‌های منو در زیر نوار ناوبری در نسخه‌های جدید اندروید.",
                            Icons.Default.Android
                        )
                    )
                ),
                ChangelogVersion(
                    "نسخه 1.0.5",
                    listOf(
                        ChangelogItem(
                            "موتور قدرتمند ضد فیلتر SNI",
                            "اضافه شدن بیش از ۴۰۰ کانفیگ SNI و امکان مدیریت و افزودن کانفیگ‌های جدید از طریق بخش اتصال.",
                            Icons.Default.RocketLaunch
                        ),
                        ChangelogItem(
                            "سیستم‌های اضطراری نوین",
                            "پیاده‌سازی سیستم اضطراری اول (Vercel Tunnel) و سیستم اضطراری دوم (Google Script Tunnel - GST) برای اتصال در سخت‌ترین شرایط فیلترینگ.",
                            Icons.Default.Security
                        ),
                        ChangelogItem(
                            "ارتقای هسته پنل‌های ابری",
                            "به‌روزرسانی و بهینه‌سازی کامل هسته مرکزی هر سه پنل ابری (NHN، BPB و EDG).",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "نوار پیشرفت هوشمند (Progress Bar)",
                            "افزوده شدن نوار درصد برای تست‌های پینگ، دیلی و سرعت جهت اطلاع دقیق و لحظه‌ای از روند بررسی کانفیگ‌ها.",
                            Icons.Default.Insights
                        ),
                        ChangelogItem(
                            "تنظیمات پیشرفته MTU",
                            "امکان تنظیم دستی مقدار MTU در بخش اتصال برای بهبود چشمگیر سرعت آپلود و پایداری شبکه.",
                            Icons.Default.SettingsEthernet
                        ),
                        ChangelogItem(
                            "مدیریت یکپارچه وورکرها",
                            "مشاهده تمامی وورکرهای فعال روی حساب کلادفلر با امکان مدیریت جامع و حذف تکی یا گروهی.",
                            Icons.Default.CleaningServices
                        ),
                        ChangelogItem(
                            "تست پلتفرم‌های خاص",
                            "اضافه شدن قابلیت تست اختصاصی کانفیگ‌ها برای اتصال به پلتفرم‌های محبوب نظیر یوتیوب، اینستاگرام و ۸ اپلیکیشن کاربردی دیگر.",
                            Icons.Default.GpsFixed
                        ),
                        ChangelogItem(
                            "سوئیچ خودکار در پس‌زمینه",
                            "قابلیت جابجایی خودکار سرور در صورت قطعی، با تنظیمات شخصی‌سازی شده حتی برای یک اپلیکیشن خاص و بدون دخالت کاربر.",
                            Icons.Default.Autorenew
                        ),
                        ChangelogItem(
                            "پشتیبانی چندزبانه (انگلیسی و فارسی)",
                            "اضافه شدن زبان انگلیسی با قابلیت تشخیص خودکار زبان سیستم‌عامل و امکان تغییر دستی.",
                            Icons.Default.Language
                        ),
                        ChangelogItem(
                            "بازطراحی و ارتقای UI/UX",
                            "تغییر آیکون اصلی برنامه و بهینه‌سازی حداکثری رابط و تجربه کاربری برای کارکرد روان‌تر و جذاب‌تر.",
                            Icons.Default.AutoAwesome
                        ),
                        ChangelogItem(
                            "مدیریت تب Warp",
                            "اضافه شدن امکان غیرفعال کردن و مخفی‌سازی کامل تب Warp در صورت عدم نیاز کاربر.",
                            Icons.Default.VisibilityOff
                        ),
                        ChangelogItem(
                            "رفع مشکلات و بهبود پایداری",
                            "برطرف‌سازی باگ‌ها و خطاهای گزارش‌شده در نسخه‌های پیشین جهت افزایش پایداری برنامه.",
                            Icons.Default.BugReport
                        )
                    )
                )
            )
        } else {
            listOf(
                ChangelogVersion(
                    "Version 1.0.8",
                    listOf(
                        ChangelogItem(
                            "Dedicated UAE WireGuard for lower ping",
                            "Added a dedicated WireGuard server in the UAE, purpose-built to reduce game ping over an optimized, stable route. Works for every game in the Game tab list.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "Redesigned, unified Game tab",
                            "The Game tab has been fully redesigned. AUTO now compares every ping-reduction method by real measured ping and recommends the best one. The interface is simpler and more consistent.",
                            Icons.Default.FlashOn
                        ),
                        ChangelogItem(
                            "Cloudflare Dedicated DNS with country steering",
                            "Cloudflare Dedicated DNS is fully optimized with smart country-based steering, so you reach the nearest, lowest-latency game server on your connection.",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "UAE Dedicated DNS (free & unlimited)",
                            "Added a dedicated DNS service on our UAE server over a secure, encrypted connection — a lightweight, stable option to lower ping without a full tunnel.",
                            Icons.Default.Language
                        ),
                        ChangelogItem(
                            "Stability improvements & bug fixes",
                            "Fixed several reported issues and improved overall stability for a smoother, more reliable experience.",
                            Icons.Default.CheckCircle
                        )
                    )
                ),
                ChangelogVersion(
                    "Version 1.0.8 beta",
                    listOf(
                        ChangelogItem(
                            "New Deno Panel (free hosting, no card)",
                            "A new Deno panel to fully auto-create your own dedicated server with no credit card required. Supports multiple accounts with easy switching and per-account usage stats (daily/weekly/monthly plus upload and download).",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "VLESS & Trojan over WS and xHTTP",
                            "The Deno server supports both VLESS and Trojan over WebSocket and xHTTP. The 'Get xHTTP config' button creates two configs (VLESS + Trojan) in one tap. Configs are also added to a dedicated DENO tab and the scanner's combine feature.",
                            Icons.Default.Bolt
                        ),
                        ChangelogItem(
                            "Quick Settings tile (fast connect)",
                            "Add the mlmvpn tile to your phone's Quick Settings panel to toggle the VPN in one tap � it delay-tests your most recent servers and connects to the fastest. The number of servers to test is adjustable in VPN Settings.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "Smaller app size",
                            "Removed unused bundled files and enabled code shrinking to significantly reduce the app size, while keeping it universal and installable on all phones (old to newest).",
                            Icons.Default.Compress
                        ),
                        ChangelogItem(
                            "Exclusive Game Booster System",
                            "Introduced the smartest Game Booster system capable of detecting and selecting the optimal route (Direct or Tunnel) to massively reduce ping for a lag-free gaming experience.",
                            Icons.Default.FlashOn
                        ),
                        ChangelogItem(
                            "UI Enhancements",
                            "Optimized and resolved visual glitches in the user interface for a more seamless and visually appealing experience.",
                            Icons.Default.Palette
                        ),
                        ChangelogItem(
                            "Bug Fixes & Stability Improvements",
                            "Investigated and resolved all user-reported bugs to enhance overall app quality and speed.",
                            Icons.Default.BugReport
                        )
                    )
                ),
                ChangelogVersion(
                    "Version 1.0.7",
                    listOf(
                        ChangelogItem(
                            "Serverless Connection Method",
                            "Introduced a new Serverless mode designed for highly restricted networks, featuring 5 pre-configured servers. (Note: Uses Iran IP; does not bypass sanctions for platforms like Telegram).",
                            Icons.Default.Language
                        ),
                        ChangelogItem(
                            "MLMVPN Exclusive Panel",
                            "Added the robust MLMVPN panel, offering high-stability XHTTP configs.",
                            Icons.Default.Settings
                        ),
                        ChangelogItem(
                            "Scanner & Test Optimizations",
                            "Completely overhauled the core scanner for extreme accuracy, alongside optimized delay, ping, and speed testing mechanisms.",
                            Icons.Default.Search
                        ),
                        ChangelogItem(
                            "Enhanced Connection Stability",
                            "Significantly improved connection speed and reliability, and resolved fake/false-positive connection states.",
                            Icons.Default.CheckCircle
                        ),
                        ChangelogItem(
                            "General Improvements",
                            "Addressed user-reported bugs for a smoother overall experience.",
                            Icons.Default.BugReport
                        )
                    )
                ),
                ChangelogVersion(
                    "Version 1.0.6",
                    listOf(
                        ChangelogItem(
                            "Sub-link Creation System",
                            "Added a powerful and dedicated system for creating sub-links.",
                            Icons.Default.Link
                        ),
                        ChangelogItem(
                            "Bug Fixes",
                            "Resolved reported issues to improve app stability and performance.",
                            Icons.Default.BugReport
                        )
                    )
                ),
                ChangelogVersion(
                    "Version 1.0.51",
                    listOf(
                        ChangelogItem(
                            "Full Android 15 & 16 Compatibility",
                            "Resolved the edge-to-edge UI overlap issue where menu options were hidden under the navigation bar on newer Android versions.",
                            Icons.Default.Android
                        )
                    )
                ),
                ChangelogVersion(
                    "Version 1.0.5",
                    listOf(
                        ChangelogItem(
                            "Powerful SNI Anti-Filter Engine",
                            "Added over 400 SNI configs with the ability to manage and add new configs via the Connection tab.",
                            Icons.Default.RocketLaunch
                        ),
                        ChangelogItem(
                            "Advanced Emergency Systems",
                            "Implemented Emergency System 1 (Vercel Tunnel) and Emergency System 2 (Google Script Tunnel - GST) for extreme censorship situations.",
                            Icons.Default.Security
                        ),
                        ChangelogItem(
                            "Cloud Panels Core Upgrade",
                            "Complete update and optimization of the core for all three cloud panels (NHN, BPB, and EDG).",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "Smart Progress Bar",
                            "Added percentage progress bars for Ping, Delay, and Speed tests to track the exact progress of config evaluations.",
                            Icons.Default.Insights
                        ),
                        ChangelogItem(
                            "Advanced MTU Settings",
                            "Ability to manually set the MTU value in the connection tab for significant upload speed and stability improvements.",
                            Icons.Default.SettingsEthernet
                        ),
                        ChangelogItem(
                            "Unified Workers Management",
                            "View and manage all active Cloudflare workers with the ability to delete them individually or in bulk.",
                            Icons.Default.CleaningServices
                        ),
                        ChangelogItem(
                            "Platform-Specific Testing",
                            "Exclusive capability to test configs against popular platforms like YouTube, Instagram, and 8 other apps.",
                            Icons.Default.GpsFixed
                        ),
                        ChangelogItem(
                            "Background Auto-Switch",
                            "Automatic server switching upon disconnection, featuring highly customizable settings even for specific apps.",
                            Icons.Default.Autorenew
                        ),
                        ChangelogItem(
                            "Multilingual Support",
                            "Added English language support with automatic OS detection and manual override.",
                            Icons.Default.Language
                        ),
                        ChangelogItem(
                            "UI/UX Redesign",
                            "New app icon and maximum optimization of the user interface and experience for a smoother and more attractive flow.",
                            Icons.Default.AutoAwesome
                        ),
                        ChangelogItem(
                            "Warp Tab Management",
                            "Added the ability to disable and completely hide the Warp tab if not needed.",
                            Icons.Default.VisibilityOff
                        ),
                        ChangelogItem(
                            "Bug Fixes & Stability",
                            "Resolved reported bugs and errors from previous versions to increase overall app stability.",
                            Icons.Default.BugReport
                        )
                    )
                )
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f)
                .padding(top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current, bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current)
                .clip(RoundedCornerShape(16.dp)),
            color = Color(0xFF1E1E1E),
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(
                    color = surfaceColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.NewReleases, contentDescription = null, tint = primaryColor, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                        }
                    }
                }
                
                Divider(color = Color(0xFF333333))
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    versions.forEach { version ->
                        item {
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                Text(
                                    text = version.versionTitle,
                                    color = primaryColor,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Divider(color = Color(0xFF333333))
                            }
                        }
                        items(version.items) { item ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(primaryColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = primaryColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.description,
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 14.sp,
                                        lineHeight = 22.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
