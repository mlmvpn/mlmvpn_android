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
    // Physical back closes the changelog first (About > Changelog), before leaving the screen.
    androidx.activity.compose.BackHandler(enabled = showChangelog) { showChangelog = false }

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
                    "نسخه 1.2.1",
                    listOf(
                        ChangelogItem(
                            "بازبینی سورس و انتشار عمومی روی گیت‌هاب",
                            "کد کامل اپ اندروید تحت لایسنس GPLv3 روی گیت‌هاب منتشر شد تا هرکس بتواند بررسی، مشارکت یا نسخه شخصی خودش را بسازد.",
                            Icons.Default.Code
                        ),
                        ChangelogItem(
                            "بخش گیم: جایگزینی سرور اول/دوم با موتور Aether",
                            "دو سرور ثابت قدیمی وایرگارد امارات (که یکی‌شان اصلاً از قبل غیرفعال بود) از تب گیم حذف شدند. حالا بوستر بازی برای اتصال تونل کامل از موتور Aether استفاده می‌کند که خودش سالم‌ترین مسیر را از میان چند پروتکل پیدا می‌کند، نه یک سرور ثابت.",
                            Icons.Default.SportsEsports
                        ),
                        ChangelogItem(
                            "رفع پیام گمراه‌کننده هنگام افزودن اکانت کلودفلر",
                            "وقتی از Global API Key بدون وارد کردن ایمیل استفاده می‌شد، برنامه پیام مبهم «Invalid API Token» نشان می‌داد. حالا پیام واضح می‌گوید که برای این نوع کلید، ایمیل هم لازم است؛ راهنمای متنی هم زیر فیلد اضافه شد.",
                            Icons.Default.BugReport
                        ),
                        ChangelogItem(
                            "رفع خطای «آپلود ورکر MLM ناموفق بود»",
                            "تاریخ سازگاری (compatibility date) ورکر MLM به‌اشتباه از ساعت گوشی محاسبه می‌شد؛ برای کاربران در تایم‌زون‌های جلوتر از UTC (مثل ایران)، بین نیمه‌شب تا ۳:۳۰ بامداد این تاریخ یک روز جلوتر از سرور کلادفلر می‌افتاد و آپلود رد می‌شد. این مقدار حالا مثل بقیهٔ ورکرهای برنامه، ثابت و امن است.",
                            Icons.Default.CloudUpload
                        ),
                        ChangelogItem(
                            "رفع بریدگی منوی کشویی همبرگری در صفحه‌های کوتاه",
                            "روی برخی گوشی‌ها گزینه‌های اضطراری در پایین منوی کناری قابل مشاهده یا لمس نبودند. محتوای منو حالا در صفحه‌های کوتاه هم به‌طور کامل قابل اسکرول است.",
                            Icons.Default.Menu
                        )
                    )
                ),
                ChangelogVersion(
                    "نسخه 1.2.0",
                    listOf(
                        ChangelogItem(
                            "رفع باگ کرش گوگل اسکریپت",
                            "تا پیش از این، چند ثانیه بعد از قطع اتصال گوگل اسکریپت، برنامه خودبه‌خود بسته می‌شد و دوباره از ابتدا بالا می‌آمد. ریشهٔ ماجرا هستهٔ داخلی تونل بود که هنگام خاموش شدن، کل برنامه را هم با خودش پایین می‌کشید. حالا این هسته جدا از برنامه اجرا می‌شود؛ اگر موقع خاموش شدن به مشکلی بخورد، فقط خودش تمام می‌شود و برنامه، اتصال و تنظیمات شما دست‌نخورده سر جایشان می‌مانند. قطع اتصال از این پس آنی است و دیگر خبری از بسته و باز شدن برنامه نیست.",
                            Icons.Default.BugReport
                        ),
                        ChangelogItem(
                            "GATE MLMVPN — موتور اتصال تازه (جدید)",
                            "یک روش اتصال کاملاً تازه با دکمهٔ بزرگ وسط منوی پایین. روی TCP پورت ۴۴۳ کار می‌کند، برای همین روی خطوطی که UDP محدود است هم می‌گیرد. هزاران سرور از سراسر دنیا با پرچم کشور، و هر سروری که تا امروز دیده شده در یک آرشیو نگه داشته می‌شود، نه فقط سرورهای زندهٔ همین لحظه.",
                            Icons.Default.Public
                        ),
                        ChangelogItem(
                            "تست واقعیِ اتصال، جدا از تست پینگ",
                            "پینگ فقط می‌گوید بسته چقدر طول می‌کشد برسد، نه اینکه اتصال برقرار می‌شود یا نه. تست واقعی، دست‌دادن کامل با سرور را انجام می‌دهد؛ نتیجهٔ سبز یعنی این سرور روی خط اینترنت شما واقعاً وصل می‌شود. لیست هم خودکار از سریع‌ترین سرورِ تأییدشده مرتب می‌شود.",
                            Icons.Default.VerifiedUser
                        ),
                        ChangelogItem(
                            "مرور بر اساس قاره و کشور",
                            "می‌توانید چند کشور را با هم انتخاب کنید، همه را یکجا تست بگیرید، سالم‌ها را به لیست اصلی اضافه کنید و بی‌پاسخ‌ها را پاک کنید. یک راهنمای کامل هم کنار دکمهٔ بستن اضافه شد که تک‌تک امکانات را توضیح می‌دهد.",
                            Icons.Default.TravelExplore
                        ),
                        ChangelogItem(
                            "شتاب‌دهی UDP، قابل انتخاب",
                            "روی شبکه‌هایی که UDP باز است سرعت را بالا می‌برد. چون روی هر خطی جواب نمی‌دهد، به‌صورت یک کلید در اختیار خودتان است.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "حریم خصوصی: آی‌پی و پورت سرورها دیگر نمایش داده نمی‌شود",
                            "آدرس و پورت سرورها از روی کارت‌ها برداشته شد تا با یک اسکرین‌شات لو نروند. همچنان می‌توانید بر اساس همین موارد مرتب‌سازی کنید.",
                            Icons.Default.Security
                        ),
                        ChangelogItem(
                            "بازطراحی کامل تب وایرگارد",
                            "دکمهٔ اتصال بزرگ وسط صفحه، تنظیمات مهم درست زیر آن، بقیهٔ تنظیمات در یک صفحهٔ جدا، و وضعیت اتصال در پایین صفحه. صفحه‌ای که قبلاً شلوغ و گیج‌کننده بود حالا با یک نگاه خوانده می‌شود.",
                            Icons.Default.Security
                        ),
                        ChangelogItem(
                            "وایرگارد: رفع قطع‌ووصل شدن مداوم",
                            "تونل سالم بعد از حدود ۱۲ ثانیه «مرده» فرض می‌شد و از اول وصل می‌شد؛ چون تونلِ بی‌کار از تونلِ خراب قابل تشخیص نبود و اولین درخواست هم فرصت تمام‌شدن پیدا نمی‌کرد. این مهلت اصلاح شد و اتصال پایدار ماند.",
                            Icons.Default.CheckCircle
                        ),
                        ChangelogItem(
                            "وایرگارد: تلاش خودکار برای اتصال",
                            "اگر ترنسپورت اول جواب نداد، خودکار روی حالت دیگر امتحان می‌شود، و اگر هویت وارپ رد شده باشد خودش هویت تازه می‌گیرد — بدون اینکه لازم باشد کاری کنید.",
                            Icons.Default.Autorenew
                        ),
                        ChangelogItem(
                            "انیمیشن شروع برنامه",
                            "لوگوی MLMVPN حالا به یک موشک تبدیل می‌شود و بالا می‌رود. صفحهٔ اصلی هم پشت همین انیمیشن آماده می‌شود تا زمانی از دست نرود.",
                            Icons.Default.Star
                        )
                    )
                ),
                ChangelogVersion(
                    "نسخه 1.1.0",
                    listOf(
                        ChangelogItem(
                            "DNS ضد تحریم شخصی (جدید)",
                            "بخش تازه‌ای در منو اضافه شد که فقط سایت‌های تحریم‌شده (مثل ChatGPT، Gemini، GitHub، Steam) را از طریق وورکر کلادفلرِ خودتان و با آی‌پی تمیز باز می‌کند؛ بقیهٔ سایت‌ها مستقیم می‌مانند. می‌توانید هر دامنه‌ای را استعلام و به لیست اضافه کنید.",
                            Icons.Default.Shield
                        ),
                        ChangelogItem(
                            "بازطراحی کامل اضطراری دوم (Google Apps Script)",
                            "به‌جای استقرار خودکار، یک ویزارد گام‌به‌گام نشسته: انتخاب رمز، تأیید رمز، دریافت کدِ آماده با راهنمای استقرار و لینک مستقیم، ورود Deployment ID و تست رله. پشتیبانی از چند حساب گوگل برای توزیع بار و دور زدن محدودیت.",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "رفع مشکل کپیِ کد اسکریپت",
                            "کدِ کپی‌شده اکنون کاملاً تمیز و بدون کاراکتر اضافه است (حذف BOM و کامنت‌های اضافه) و گزینهٔ «ذخیره به‌صورت فایل» برای مواقعی که کلیپ‌بورد گوشی ناقص کپی می‌کند اضافه شد.",
                            Icons.Default.ContentCopy
                        ),
                        ChangelogItem(
                            "نصب گواهی امنیتی بدون نیاز به اتصال",
                            "دیگر لازم نیست اول یک‌بار متصل شوید؛ گواهی CA حالا مستقیم از کارت بالای صفحهٔ اضطراری دوم قابل ساخت و نصب است.",
                            Icons.Default.Security
                        ),
                        ChangelogItem(
                            "رفع بسته‌شدن ناگهانی اپ هنگام قطع اتصال اضطراری دوم",
                            "پس از قطع اتصال، به‌جای بسته‌شدن ناگهانی به لانچر، برنامه به‌صورت کنترل‌شده و تمیز تازه‌سازی می‌شود.",
                            Icons.Default.CheckCircle
                        ),
                        ChangelogItem(
                            "اصلاح رفتار دکمهٔ برگشت گوشی",
                            "دکمهٔ برگشت حالا استاندارد است: از هر صفحه به تب خانه (ابری) برمی‌گردد، در صفحات دومرحله‌ای یک مرحله عقب می‌رود و روی تب خانه با تأییدِ خروج بسته می‌شود.",
                            Icons.Default.ArrowBack
                        )
                    )
                ),
                ChangelogVersion(
                    "نسخه 1.0.9 — نسخهٔ ویژهٔ شرایط اضطراری",
                    listOf(
                        ChangelogItem(
                            "نسخهٔ مخصوص شرایط سخت و نت ملی",
                            "این نسخه به‌طور ویژه برای استفادهٔ بهینه در شرایط سخت شبکه و اینترنت ملی آماده شده تا حتی هنگام قطعی و فیلترینگ گسترده هم بتوانید سرور بسازید، مدیریت کنید و متصل شوید.",
                            Icons.Default.Bolt
                        ),
                        ChangelogItem(
                            "اضطراری دوم (Google Apps Script) کاملاً بهینه شد",
                            "زیرساخت اضطراری دوم مبتنی بر Google Apps Script به‌طور کامل بهینه‌سازی شد برای اتصال پایدارتر و سریع‌تر در شرایط سخت شبکه.",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "اضطراری اول (Vercel) برای هر ۴ پنل",
                            "مسیر نجات اضطراری اول اکنون برای هر ۴ پنل (MLM، نهان، BPB و Edge) کار می‌کند: دیپلوی، تنظیمات، مدیریت کاربران و دریافت کانفیگ حتی وقتی دسترسی مستقیم به کلادفلر بلاک باشد، از طریق Vercel انجام می‌شود.",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "رفع لگ کانفیگ‌های ابری (xHTTP)",
                            "مشکل کندی و لگ کانفیگ‌های xHTTP برطرف شد؛ اتصال روان‌تر و پایدارتر شد.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "بستن نشت DNS در کانفیگ‌های ابری",
                            "نشت DNS در کانفیگ‌های xHTTP بسته شد؛ اکنون کوئری‌های دامنه رمزنگاری‌شده و از داخل تونل انجام می‌شوند تا حریم خصوصی حفظ و از مسموم‌سازی DNS جلوگیری شود.",
                            Icons.Default.Language
                        ),
                        ChangelogItem(
                            "کانفیگ‌های پیش‌فرض ایران با روش‌های متنوع عبور",
                            "شش کانفیگ پیش‌فرض ایران هرکدام با استراتژی متفاوت عبور از فیلترینگ تنظیم شدند تا روی اپراتورهای مختلف (همراه اول، ایرانسل و…) گزینهٔ کارآمد داشته باشید؛ این کانفیگ‌ها محافظت‌شده و غیرقابل‌حذف هستند.",
                            Icons.Default.FlashOn
                        ),
                        ChangelogItem(
                            "بهبود پایداری و رفع باگ",
                            "بهبودهای متعدد در پایداری و رفع اشکالات گزارش‌شده برای تجربه‌ای روان‌تر.",
                            Icons.Default.CheckCircle
                        )
                    )
                ),
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
                    "Version 1.2.1",
                    listOf(
                        ChangelogItem(
                            "Source review and public release on GitHub",
                            "The full Android app source is now published under GPLv3 on GitHub, so anyone can review it, contribute, or build their own version.",
                            Icons.Default.Code
                        ),
                        ChangelogItem(
                            "Game tab: Server 1/2 replaced with the Aether engine",
                            "The two hardcoded legacy UAE WireGuard servers (one of which was already dead) were removed from the Game tab. Full-tunnel game boosting now uses the Aether engine, which picks the healthiest path across several protocols instead of a single fixed server.",
                            Icons.Default.SportsEsports
                        ),
                        ChangelogItem(
                            "Fixed misleading error when adding a Cloudflare account",
                            "Using a Global API Key without an email used to show a vague \"Invalid API Token\" error. It now clearly explains that this key type requires an email, with a hint added under the field too.",
                            Icons.Default.BugReport
                        ),
                        ChangelogItem(
                            "Fixed \"Failed to upload MLM worker\" error",
                            "The MLM worker's compatibility date was computed from the phone's local clock; for timezones ahead of UTC (like Iran), between midnight and 3:30 AM local time this date landed a day ahead of Cloudflare's server date and the upload was rejected. It is now a fixed, safe date like every other worker in the app.",
                            Icons.Default.CloudUpload
                        ),
                        ChangelogItem(
                            "Fixed the hamburger drawer menu getting cut off on short screens",
                            "On some phones the emergency options at the bottom of the side menu were unreachable. The drawer content now scrolls fully on short screens.",
                            Icons.Default.Menu
                        )
                    )
                ),
                ChangelogVersion(
                    "Version 1.2.0",
                    listOf(
                        ChangelogItem(
                            "Fixed the Google Script crash",
                            "Until now, a few seconds after you disconnected Google Script, the app would close on its own and start again from scratch. The cause was the tunnel's internal core, which took the whole app down with it as it shut off. That core now runs separately from the app, so if it runs into trouble while shutting down, only it ends — your app, your connection and your settings stay exactly where they were. Disconnecting is instant from now on, with no more closing and reopening.",
                            Icons.Default.BugReport
                        ),
                        ChangelogItem(
                            "GATE MLMVPN — a new connection engine (new)",
                            "A completely new way to connect, behind the big button in the middle of the bottom bar. It runs over TCP on port 443, so it works even on lines where UDP is restricted. Thousands of servers worldwide with country flags, and every server seen so far is kept in an archive — not just the ones live at this moment.",
                            Icons.Default.Public
                        ),
                        ChangelogItem(
                            "A real connection test, separate from ping",
                            "Ping only tells you how long a packet takes to arrive, not whether a connection will succeed. The real test performs a full handshake with the server, so a green result means that server actually connects on your line. The list also sorts itself by the fastest verified server.",
                            Icons.Default.VerifiedUser
                        ),
                        ChangelogItem(
                            "Browse by continent and country",
                            "Pick several countries at once, test them all in one go, add the healthy ones to your main list and prune the dead ones. A full guide next to the close button explains every feature.",
                            Icons.Default.TravelExplore
                        ),
                        ChangelogItem(
                            "Optional UDP acceleration",
                            "Speeds things up on networks where UDP is open. Since it doesn't help everywhere, it's left as a switch under your control.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "Privacy: server IPs and ports are no longer shown",
                            "Addresses and ports were removed from the server cards so a screenshot can't give them away. You can still sort by those values.",
                            Icons.Default.Security
                        ),
                        ChangelogItem(
                            "WireGuard tab fully redesigned",
                            "A big connect button in the middle, the settings that matter right beneath it, everything else on its own screen, and the connection status at the bottom. What used to be a crowded, confusing screen now reads at a glance.",
                            Icons.Default.Security
                        ),
                        ChangelogItem(
                            "WireGuard: fixed the constant reconnect loop",
                            "A healthy tunnel was being declared dead after about 12 seconds and restarted from scratch — an idle tunnel was indistinguishable from a broken one, and the first request never had time to finish. That window was corrected and the connection now holds.",
                            Icons.Default.CheckCircle
                        ),
                        ChangelogItem(
                            "WireGuard: automatic connection recovery",
                            "If the first transport doesn't answer, the other one is tried automatically, and if the WARP identity has been rejected a fresh one is enrolled — with nothing for you to do.",
                            Icons.Default.Autorenew
                        ),
                        ChangelogItem(
                            "Startup animation",
                            "The MLMVPN logo now turns into a rocket and lifts off. The main screen loads behind the animation, so none of that time is wasted.",
                            Icons.Default.Star
                        )
                    )
                ),
                ChangelogVersion(
                    "Version 1.1.0",
                    listOf(
                        ChangelogItem(
                            "Personal anti-sanction DNS (new)",
                            "A new menu section that opens only sanctioned sites (ChatGPT, Gemini, GitHub, Steam…) through your own Cloudflare worker with a clean IP, while everything else stays direct. You can test any domain and add it to the list.",
                            Icons.Default.Shield
                        ),
                        ChangelogItem(
                            "Emergency #2 (Google Apps Script) redesigned",
                            "The auto-deploy flow is replaced by a step-by-step wizard: choose a password, confirm it, get the ready-to-paste code with a deployment guide and a direct link, enter the Deployment ID, and test the relay. Multiple Google accounts are supported for load-balancing and bypassing quotas.",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "Fixed the script copy issue",
                            "The copied code is now completely clean with no stray characters (BOM and comments stripped), plus a 'Save as file' option for when the phone clipboard truncates it.",
                            Icons.Default.ContentCopy
                        ),
                        ChangelogItem(
                            "Install the CA certificate without connecting first",
                            "You no longer need to connect once beforehand — the CA can be generated and installed straight from the card at the top of the Emergency #2 screen.",
                            Icons.Default.Security
                        ),
                        ChangelogItem(
                            "Fixed the app closing on Emergency #2 disconnect",
                            "After disconnecting, instead of abruptly closing to the launcher, the app now restarts cleanly in a controlled way.",
                            Icons.Default.CheckCircle
                        ),
                        ChangelogItem(
                            "Improved phone back-button behavior",
                            "Back now behaves like a standard app: returns to the home (Cloud) tab from any screen, goes up one level in two-level screens, and asks to confirm exit on the home tab.",
                            Icons.Default.ArrowBack
                        )
                    )
                ),
                ChangelogVersion(
                    "Version 1.0.9 — Emergency Edition",
                    listOf(
                        ChangelogItem(
                            "Built for harsh conditions & national internet",
                            "This release is specially tuned for optimal use under harsh network conditions and the national internet, so you can still create servers, manage them, and connect even during heavy outages and filtering.",
                            Icons.Default.Bolt
                        ),
                        ChangelogItem(
                            "Emergency #2 (Google Apps Script) fully optimized",
                            "The Google Apps Script based Emergency #2 infrastructure has been fully optimized for a more stable and faster connection under harsh network conditions.",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "Emergency #1 (Vercel) now covers all 4 panels",
                            "The Emergency #1 rescue route now works for all 4 panels (MLM, Nahan, BPB, Edge): deploy, settings, user management and fetching configs go through Vercel even when direct access to Cloudflare is blocked.",
                            Icons.Default.Cloud
                        ),
                        ChangelogItem(
                            "Fixed cloud config (xHTTP) lag",
                            "Resolved the slowness and lag on xHTTP configs for a smoother, more stable connection.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "Closed DNS leak on cloud configs",
                            "The DNS leak on xHTTP configs is now closed; domain lookups are encrypted and tunneled to protect privacy and prevent DNS poisoning.",
                            Icons.Default.Language
                        ),
                        ChangelogItem(
                            "Iran default configs with diverse bypass methods",
                            "The six Iran default configs each use a different anti-filtering strategy so you always have a working option across different carriers (Hamrah-e-Aval, Irancell, etc.). These configs are protected and cannot be deleted.",
                            Icons.Default.FlashOn
                        ),
                        ChangelogItem(
                            "Stability improvements & bug fixes",
                            "Multiple stability improvements and fixes for reported issues.",
                            Icons.Default.CheckCircle
                        )
                    )
                ),
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
