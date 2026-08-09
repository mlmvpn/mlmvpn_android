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
                            "ریکاوری خودکار بعد از قطعی واقعی شبکه",
                            "قبلاً وقتی وای‌فای/دیتا واقعاً قطع می‌شد، تونل هیچ راهی برای فهمیدن این موضوع نداشت، پس رابط کاربری همچنان «متصل» نشان می‌داد — حتی بعد از برگشتن اینترنت — تا وقتی کاربر دستی قطع و وصل می‌کرد. حالا اپ اتصال واقعی شبکه را زیر نظر دارد و اگر در حالت متصل شبکه قطع و بعداً برگردد، خودکار همان اتصال را (قطع، صبر برای پاک‌سازی کامل، وصل مجدد) از نو برقرار می‌کند.",
                            Icons.Default.Wifi
                        ),
                        ChangelogItem(
                            "بخش گیم: جایگزینی سرور اول/دوم با موتور Aether",
                            "دو سرور ثابت قدیمی وایرگارد امارات (که یکی‌شان اصلاً از قبل غیرفعال بود) از تب گیم حذف شدند. حالا بوستر بازی برای اتصال تونل کامل از موتور Aether استفاده می‌کند که خودش سالم‌ترین مسیر را از میان چند پروتکل پیدا می‌کند، نه یک سرور ثابت.",
                            Icons.Default.SportsEsports
                        ),
                        ChangelogItem(
                            "رفع نامتقارن‌بودن آیکون‌های نوار پایین",
                            "وقتی تب‌های اختیاری گیم/وایرگارد باعث می‌شدند دو طرف نوار پایین تعداد آیکون متفاوتی داشته باشند (مثلاً ۳ چپ / ۲ راست)، آیکون‌های طرف کوچک‌تر به سمت دکمه وسط جمع می‌شدند با یک فاصله خالی بعدشان، و اندازه‌شان هم با طرف دیگر فرق داشت. حالا هر دو طرف از یک عرض ثابت و یکسان برای هر آیکون استفاده می‌کنند و گروه آیکون‌های واقعی‌شان (بدون جای خالی) در وسط نیمه خودشان قرار می‌گیرد — چه گیم و وایرگارد هردو فعال باشند، چه یکی، چه هیچ‌کدام.",
                            Icons.Default.Apps
                        ),
                        ChangelogItem(
                            "رفع فضای خالی مرده زیر منوی همبرگری روی گوشی‌های بلند",
                            "منوی کشویی روی گوشی‌های بلند بین آخرین گزینه و پایین صفحه یک فضای خالی می‌گذاشت. حالا فاصله بین منوی اصلی و بخش اضطراری روی گوشی‌های بلند بزرگ می‌شود (بخش اضطراری به پایین صفحه می‌چسبد)، و روی گوشی‌های کوتاه که همه گزینه‌ها جا نمی‌شوند مثل قبل عادی و بدون فاصله اضافه اسکرول می‌شود.",
                            Icons.Default.Menu
                        ),
                        ChangelogItem(
                            "رفع پروکسی محلی بی‌اثر در «حالت پروکسی»",
                            "در «حالت پروکسی» (فقط پروکسی محلی SOCKS/HTTP، بدون تونل کامل VPN)، بعد از وصل‌شدن به یک نود، اپ ممکن بود «متصل» نشان بدهد ولی پروکسی محلی هیچ ترافیکی رد نمی‌کرد و هر سایتی روی آن Timeout می‌داد. علت: این حالت عمداً رابط تونل VPN را نمی‌سازد، ولی تنظیمات Xray طوری ساخته می‌شد که انگار تونلی وجود دارد و یک ورودی «tun» با فایل‌توصیفگر نامعتبر به هسته Xray می‌داد. هسته در لحظه شروع این را قبول می‌کرد (موفقیت گزارش می‌داد)، ولی چند ثانیه بعد همین ورودی نامعتبر می‌توانست کل موتور — از جمله پروکسی SOCKS/HTTP — را بی‌صدا از کار بیندازد، درست بعد از اینکه رابط کاربری از قبل «متصل» را نشان داده بود. حالا این ورودی تونل فقط وقتی ساخته می‌شود که یک رابط VPN واقعی موجود باشد.",
                            Icons.Default.SettingsEthernet
                        ),
                        ChangelogItem(
                            "Aether: نشانگر زمان و توضیح در مرحله «اسکن گیت‌وی»",
                            "مرحله «اسکن گیت‌وی» (MASQUE) می‌تواند بسته به حالت اسکن از چند ده ثانیه تا چند دقیقه طول بکشد، و روی شبکه‌هایی که MASQUE را با DPI مسدود می‌کنند، تا آخرین لحظه‌ی بودجه‌ی زمانی ادامه پیدا می‌کند و بعد شکست می‌خورد — با فقط یک نشان ثابت «در حال کار»، این حالت مثل هنگ‌کردن اپ به‌نظر می‌رسید. حالا کارت وضعیت یک شمارنده زمان زنده روی مرحله اسکن نشان می‌دهد، و بعد از ۲۰ ثانیه یک توضیح اضافه می‌شود که این مرحله ممکن است طول بکشد و بعضی شبکه‌ها MASQUE را کند یا مسدود می‌کنند، به‌همراه یادآوری که با زدن دوباره دکمه اتصال می‌توانید لغوش کنید. این تغییر رفتار یا نرخ موفقیت اسکن را عوض نمی‌کند — فقط یک اسکن طولانی را قابل‌فهم می‌کند به‌جای این‌که گیرکرده به‌نظر برسد.",
                            Icons.Default.Timer
                        ),
                        ChangelogItem(
                            "واقع‌بینانه‌تر شدن نتایج تست «دیلی واقعی» و سرعت اسکنر",
                            "این تست یک اتصال واقعی پروکسی‌شده از طریق Xray برای هر کانفیگ است، نه یک پینگ خام — ولی صدها کانفیگ با تا ۸ اتصال واقعی هم‌زمان روی همان سرورها تست می‌شدند، که برای کلودفلر/DPI شبیه ترافیک مشکوک هم‌زمانِ handshake به‌نظر می‌رسد و throttle می‌شود. عددهای دیلی/سرعت نمایش داده‌شده در واقع ازدحام خودساخته‌ی همان تست را اندازه می‌گرفتند، نه شرایط واقعی شبکه را. یکی از دو محدودیت هم‌زمانی درگیر، حتی یک کامنت داشت («ممکن است کلودفلر/DPI با بیش از ۳ handshake هم‌زمان مسدود کند») که مستقیماً با عدد واقعی‌اش (۸) در تضاد بود. هر دو حالا روی ۳ محدود شدند، مطابق همان مقدار امنی که جای دیگری از اپ برای همین نوع تست استفاده می‌شود.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "کاهش محسوس مصرف باتری در حالت متصل",
                            "چهار علت جدا پیدا شد که همگی تا وقتی VPN وصل بود مدام باتری مصرف می‌کردند: ۱) تونل یک wake lock بدون سقف زمانی برای کل مدت اتصال نگه می‌داشت، یعنی تا وقتی وصل بودید پردازنده هیچ‌وقت نمی‌توانست به خواب عمیق یا حالت Doze برود — حتی تمام شب که گوشی توی جیب بی‌استفاده بود و حتی یک بایت هم رد و بدل نمی‌شد. حالا این قفل فقط تا وقتی ترافیک واقعاً در جریان است نگه داشته می‌شود و بعد از ۵ دقیقه سکوت آزاد می‌شود؛ بسته‌های ورودی همچنان مستقل از این قفل پردازه را بیدار می‌کنند. ۲) یک حلقه تشخیصی باقی‌مانده هر ۵ ثانیه بیدار می‌شد فقط برای نوشتن یک عدد مصرف رم در لاگ که در نسخه نهایی هیچ‌کس نمی‌خواندش — حدود ۱۷٬۰۰۰ بیدارشدن در روز بدون هیچ کار مفیدی؛ کاملاً حذف شد. ۳) شمارنده ترافیک هر ۲ ثانیه روی حافظه می‌نوشت فارغ از این‌که چیزی تغییر کرده باشد یا نه — روی یک تونل بی‌استفاده حدود ۴۳٬۰۰۰ نوشتن بی‌فایده در روز؛ حالا فقط وقتی می‌نویسد که واقعاً بایتی جابه‌جا شده باشد. ۴) ناظر تعویض خودکار سرور هر ۱۰ ثانیه یک مقدار بولین را دوباره می‌خواند با این‌که این قابلیت پیش‌فرض خاموش است؛ حالا دقیقه‌ای یک بار بررسی می‌شود.",
                            Icons.Default.BatteryChargingFull
                        ),
                        ChangelogItem(
                            "رفع آپلودهای تکراری لینک اشتراک روی کلودفلر",
                            "همگام‌سازی خودکار پس‌زمینه با هر تغییر در لیست نودها کانفیگ‌های لینک را دوباره روی کلودفلر آپلود می‌کرد، در حالی که اکثر این تغییرات فقط فراداده‌اند (نتیجه پینگ، عدد تست سرعت، پرچم کشور) و لیست واقعی کانفیگ‌ها را ذره‌ای عوض نمی‌کنند. با این حال هرکدام یک رفت‌وبرگشت کامل به API کلودفلر هزینه داشت، تمام روز، برای هر لینک روی هر حساب — هدررفت باتری و دیتا، و آن‌قدر ترافیک API که می‌تواند در خطاهای کلودفلری که بعضی کاربران بعد از یک روز روشن ماندن اپ می‌دیدند نقش داشته باشد. حالا اگر لیست کانفیگ‌ها واقعاً تغییر نکرده باشد، آپلود کاملاً رد می‌شود.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "رفع قفل‌شدن بی‌جهت پنل ابری پشت «تایید ایمیل الزامی»",
                            "افزودن اکانت کلودفلر می‌توانست برای همیشه پشت صفحه‌ی «تایید ایمیل الزامی» گیر کند، بدون هیچ راه خروجی جز لاگین بیرونی به کلودفلر — حتی وقتی مشکل واقعی، تایید نبودن ایمیل نبود. علت: وقتی ساخت ساب‌دامین workers.dev حساب شکست می‌خورد، اپ صرفاً با بررسی این‌که آیا متن خطای خام کلودفلر شامل کلمه «verify» است یا نه تصمیم می‌گرفت که حتماً ایمیل تایید نشده — در حالی که چند خطای بی‌ربط دیگر کلودفلر (مشکل دامنه‌ی توکن، محدودیت نرخ درخواست و غیره) هم می‌توانند همین کلمه را داشته باشند، و کل پنل ابری را بدون هیچ نشانه‌ای از علت واقعی پشت صفحه‌ی تایید قفل می‌کردند. حالا قبل از نمایش آن صفحه، وضعیت واقعی تایید ایمیل از خودِ endpoint وضعیت حساب کلودفلر پرسیده می‌شود؛ در غیر این صورت خطای واقعی نمایش داده می‌شود.",
                            Icons.Default.VerifiedUser
                        ),
                        ChangelogItem(
                            "رفع خالی‌شدن خودکار کانفیگ‌های لینک اشتراک",
                            "کانفیگ‌های یک لینک اشتراک ممکن بود بعد از مدتی خودشان خالی شوند — خیلی قبل از این‌که کاربر اصلاً دکمه «بروزرسانی» را بزند. علت: هر لینک اشتراک این‌که به کدام گروه نود اشاره می‌کند را به‌صورت یک رشته ترکیبی ذخیره می‌کند (مثلاً «manual:نوع‌موتور:عنوان‌گروه»)، و دکمه «بروزرسانی» خودِ صفحه این رشته را درست تجزیه می‌کند تا نودهای منطبق را پیدا کند — ولی وظیفه‌ی همگام‌سازی خودکار پس‌زمینه (که چند ثانیه بعد از هر تغییر در لیست نودها دوباره کانفیگ‌ها را آپلود می‌کند) نوع موتور خام هر نود را مستقیم با کل همان رشته‌ی ترکیبی مقایسه می‌کرد که هیچ‌وقت برابر نمی‌شد. پس هر بار همگام‌سازی پس‌زمینه اجرا می‌شد، لینک را بی‌صدا با یک لیست کانفیگ خالی جایگزین می‌کرد؛ «بعد از ۲۰-۳۰ بار بروزرسانی» که کاربران می‌دیدند در واقع فقط به این بستگی داشت چقدر طول می‌کشید تا یکی از این همگام‌سازی‌های پس‌زمینه اجرا شود. حالا وظیفه‌ی همگام‌سازی خودکار دقیقاً همان روشی که دکمه «بروزرسانی» استفاده می‌کند را به‌کار می‌برد.",
                            Icons.Default.LinkOff
                        ),
                        ChangelogItem(
                            "بررسی خودکار نسخه جدید و دانلود/نصب داخل اپ",
                            "اپ حالا بلافاصله بعد از اجرا (و همچنین هر بار یک اتصال VPN برقرار می‌شود، برای وقتی گیت‌هاب موقع اجرا فیلتر بوده) نسخه جدید را از گیت‌هاب بررسی می‌کند. اگر نسخه جدیدتری منتشر شده باشد، پنجره‌ای با شماره نسخه، حجم فایل، و لیست کامل و قابل‌اسکرول تغییرات نمایش داده می‌شود، همراه با دکمه «دانلود نسخه جدید» که به یک صفحه دانلود اختصاصی می‌رود (نوار پیشرفت با درصد زنده) و بعد از اتمام دانلود خودکار نصب‌کننده سیستم را باز می‌کند. اگر گیت‌هاب در دسترس نباشد، این بررسی کاملاً بی‌صدا شکست می‌خورد و هیچ خللی در کارکرد اپ ایجاد نمی‌کند.",
                            Icons.Default.SystemUpdate
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
                        ),
                        ChangelogItem(
                            "پنل BPB به نسخه 5.1.1 آپدیت شد",
                            "پنل BPB داخل بخش ابری از نسخهٔ قدیمی 4.2.2 به 5.1.1 ارتقا یافت. چون این نسخه مدل دیپلوی را کاملاً عوض کرده (تنظیمات هر حساب باید داخل خودِ کد ورکر جاسازی شود، نه از طریق متغیرهای کلودفلر مثل قبل)، دیپلوی، ورود به پنل، دریافت ساب‌لینک و ذخیرهٔ تنظیمات همگی با روش جدید بازنویسی شدند. فرم تنظیمات پنل هم با فیلدهای نسخهٔ جدید هماهنگ شد.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "ورکر EDG به آخرین نسخه آپدیت شد",
                            "ورکر EDG داخل بخش ابری (بر پایه پروژه cmliu/edgetunnel) به آخرین نسخهٔ منتشرشده ارتقا یافت؛ شامل چند ماه بهینه‌سازی انتقال داده از منبع اصلی (بسته‌بندی هوشمند بسته‌های آپلود/دانلود، رقابت هم‌زمان چند اتصال TCP، کش سریع‌تر DNS) که باید کانفیگ‌های EDG را سریع‌تر و پایدارتر کند. جایگزینی کاملاً سازگار بود؛ نیازی به تغییر تنظیمات اکانت‌های موجود نیست.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "پنل نهان (Nahan) به نسخه 3.0.0 آپدیت شد",
                            "پنل نهان داخل بخش ابری از نسخهٔ 2.9.4 به 3.0.0 ارتقا یافت: پروکسی زنجیره‌ای VLESS به‌عنوان upstream، فرمت ساب v2rayN JSON، فرم بازطراحی‌شدهٔ افزودن/ویرایش کاربر با toggle مدرن به‌جای چک‌باکس، و رفع چند باگ ظاهری در حالت تاریک/راست‌چین. نام بایندینگ D1 و مسیرهای API بدون تغییر ماندند، پس جایگزینی کاملاً سازگار بود.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "حذف کامل پنل Deno",
                            "پنل Deno (آیتم منو، تب، بخش سؤالات متداول و کد داخلی) کاملاً حذف شد. api.deno.com مدتی است در ایران فیلتر است و مدل صورتحساب Deno (بر اساس مدت‌زمان باز بودن اتصال، نه حجم) اصلاً برای VPN مناسب نبود؛ پنل‌های کلودفلر (BPB/EDG/Nahan) همان نیاز را بدون این مشکلات پوشش می‌دهند.",
                            Icons.Default.Delete
                        ),
                        ChangelogItem(
                            "رفع گیر کردن دائمی دیپلوی MLM/نهان روی خطای دیتابیس D1",
                            "دیپلوی MLM (و نهان) گاهی برای همیشه روی خطای «Failed to create D1 Database» گیر می‌کرد و تنها راه‌حل حذف دستی دیتابیس‌های قدیمی از پنل کلودفلر بود. علت: هر بار دیپلوی — حتی هر بار تلاش دوباره بعد از شکست — یک دیتابیس D1 کاملاً تازه می‌ساخت و قدیمی‌ها را پاک نمی‌کرد؛ چند بار تلاش دوباره کافی بود تا سقف تعداد دیتابیس D1 حساب پر شود. حالا دیپلوی از دیتابیس موجود حساب (یا یک دیتابیس رهاشده از تلاش قبلی) استفاده مجدد می‌کند؛ و اگر سقف ۱۰ دیتابیسی پلن رایگان واقعاً با دیتابیس‌های ابزارهای دیگر پر شده باشد، پیام خطا اسم دقیق همهٔ آن‌ها را نشان می‌دهد تا بدانید کدام را حذف کنید.",
                            Icons.Default.Storage
                        ),
                        ChangelogItem(
                            "شفاف‌سازی دکمه «استعلام» در DNS ضد تحریم شخصی",
                            "دکمه «استعلام» فقط دامنه را دسته‌بندی می‌کند و هیچ‌وقت خودش دامنه را به لیست مسیریابی اضافه نمی‌کرد، ولی پیام «تحریم» طوری خوانده می‌شد انگار همین که قابلیت را روشن کنید کافی است. کاربر استعلام می‌گرفت، «تحریم» می‌دید، قابلیت را روشن می‌کرد، و گیج می‌شد چرا سایت بازهم باز نمی‌شود — چون دکمه‌ی جداگانه‌ی «افزودن» را نزده بود. حالا پیام صریحاً می‌گوید «افزودن» را بزنید، و اگر دامنه‌ای تحریمی باشد ولی هنوز در لیست نباشد، یک هشدار قرمز واضح این را نشان می‌دهد.",
                            Icons.Default.Warning
                        ),
                        ChangelogItem(
                            "رفع باز نشدن سایت‌های تحریمی (خطای ۴۰۳) در «DNS ضد تحریم شخصی»",
                            "سایت‌های تحریمی که خودِ ابزار استعلام همین بخش هم «تحریم» تشخیص می‌داد، بازهم باز نمی‌شدند و ۴۰۳ می‌دادند — دقیقاً انگار قابلیت روشن نبود. علت (با گرفتن لاگ زنده از گوشی واقعی پیدا شد): مسیریابی دامنه‌ها کاملاً به تشخیص SNI از TLS ClientHello وابسته بود که برای سایت‌های دارای Encrypted Client Hello (ECH، این روزها پیش‌فرض کروم) کاملاً از کار می‌افتد چون SNI رمزنگاری‌شده قابل خواندن نیست؛ نتیجه این‌که هر اتصال تحریمی به قانون مستقیم می‌افتاد. یک استخر DNS جعلی (fakedns) اضافه شد، ولی بررسی دوم با لاگ زنده دیگری نشان داد resolve به آی‌پی جعلی خودش درست کار می‌کند اما برگرداندن آن آی‌پی به نام دامنه در لحظه مسیریابی روی این نسخه از هسته Xray قابل‌اعتماد نبود. الان مسیریابی ترافیک تحریمی مستقیماً روی بازه آی‌پی استخر جعلی (198.18.0.0/15) تطبیق داده می‌شود، بدون نیاز به آن مرحله برگردان دامنه.",
                            Icons.Default.Dns
                        ),
                        ChangelogItem(
                            "رفع خراب‌شدن سایت‌های غیرمرتبط با روشن‌کردن «DNS ضد تحریم شخصی»",
                            "روشن‌کردن این بخش گاهی سایت‌هایی که اصلاً قرار نبود دست‌خورده باشند (مثل Gmail) را هم خراب می‌کرد. علت: resolver DNS این بخش به‌صورت ثابت روی UDP ساده 1.1.1.1:53 تنظیم شده بود — پروتکلی که در بسیاری از خطوط ایران فیلتر یا کند می‌شود، بدون هیچ راه جایگزین؛ برخلاف تنظیمات اصلی VPN که به همین دلیل قبلاً به DoH روی HTTPS تغییر کرده بود. حالا از همان روش DoH روی پورت 443 استفاده می‌کند.",
                            Icons.Default.Dns
                        ),
                        ChangelogItem(
                            "دکمه «بروزرسانی وورکر» در DNS ضد تحریم شخصی",
                            "وقتی وورکر خروجی از قبل ساخته شده، حالا می‌توانید بدون حذف و افزودن دوباره حساب، آخرین نسخه ورکر EDG را مستقیم روی همان حساب دیپلوی کنید.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "رفع نمایش نادرست دکمه «ساخت وورکر» بعد از دیپلوی موفق",
                            "بعد از دیپلوی موفق وورکر خروجی در DNS ضد تحریم شخصی، صفحه هنوز دکمه «ساخت وورکر» را نشان می‌داد و کاربر مجبور بود از صفحه خارج و دوباره وارد شود تا وضعیت «آماده ✅» را ببیند. علت: توابع دیپلوی فیلدهای همان آبجکت اکانت کلودفلر را مستقیم تغییر می‌دهند، پس لیست به‌روزشده از نظر ساختاری با چیزی که از قبل در state مشترک بود یکسان بود و Kotlin StateFlow رویداد جدیدی ارسال نمی‌کرد. حالا موفقیت دیپلوی به‌صورت محلی هم ردیابی می‌شود.",
                            Icons.Default.Refresh
                        ),
                        ChangelogItem(
                            "رفع شکست دائمی «ساخت وورکر» در DNS ضد تحریم شخصی",
                            "این مرحله (همان دیپلوی EDG که جای دیگری هم استفاده می‌شود) می‌توانست دقیقاً به همان دلیلی که MLM و نهان داشتند، بعد از چند تلاش برای همیشه شکست بخورد: هر بار یک KV Namespace کاملاً تازه می‌ساخت و قدیمی‌ها را پاک نمی‌کرد. حالا از namespace موجود حساب (یا یکی رهاشده از تلاش قبلی) استفاده مجدد می‌کند؛ خطاها هم حالا متن واقعی پاسخ کلودفلر را نشان می‌دهند.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "رفع گیر کردن/کرش دکمه «تست سلامت پینگ» در تب اسکنر",
                            "این دکمه روی گروه‌های ترکیبی/Mix (و به‌تبع آن بروزرسانی ساب‌اسکریپشن MLM با آی‌پی‌های سالم که به همین مسیر وابسته است) روی بعضی گوشی‌ها ممکن بود برای همیشه گیر کند یا اپ کرش کند. علت: این مسیر خاص، برخلاف تمام مسیرهای مشابه دیگر در اپ، فراخوانی مقداردهی اولیه هسته Xray را نداشت — یک خط کد به‌اشتباه کامنت شده بود. این‌که کار می‌کرد یا نه کاملاً به این بستگی داشت که آیا صفحه دیگری در همان نشست، هسته را قبلاً مقداردهی کرده باشد؛ همین باعث می‌شد برای بعضی کاربران همیشه کار کند و برای بعضی دیگر هرگز. خط گم‌شده بازگردانده شد.",
                            Icons.Default.BugReport
                        ),
                        ChangelogItem(
                            "رفع گیر کردن صفحه «مدیریت کاربران» MLM روی «Error fetching users»",
                            "این صفحه گاهی بلافاصله بعد از یک دیپلوی تازه، برای همیشه روی این خطا گیر می‌کرد. علت: خودِ Cloudflare چند ثانیه اول بعد از فعال‌شدن یک روت تازهٔ *.workers.dev یک ۴۰۴ موقتی (کد داخلی خودِ کلودفلر: 1042) برمی‌گرداند، قبل از اینکه کد ورکر ما اصلاً اجرا شود. اپ حالا چند بار خودکار دوباره امتحان می‌کند؛ اگر خطای واقعی دیگری هم باشد، حالا کد HTTP و متن دقیق پاسخ نمایش داده می‌شود، نه یک پیام کلی و مبهم.",
                            Icons.Default.Refresh
                        ),
                        ChangelogItem(
                            "دکمهٔ جدید «دریافت کانفیگ رایگان» در بخش اتصال",
                            "بالای دکمهٔ + یک ویزارد چندمرحله‌ای اضافه شد: اول تعداد کانفیگ‌های در دسترس را از چند منبع عمومی نشان می‌دهد، بعد شما تعداد دلخواه را انتخاب می‌کنید، سیستم کانفیگ‌ها را با یک تست دو مرحله‌ای (فیلتر سریع شبکه + تست واقعی اتصال با Xray، همان دقتی که «دیلی واقعی» دارد) بررسی می‌کند و فقط کانفیگ‌های واقعاً متصل را برمی‌گرداند. یک دکمهٔ «همین تعداد کافیه» هم هست که هر لحظه می‌توانید جستجو را متوقف و به همان‌ها بسنده کنید. کانفیگ‌ها با نام‌های رندوم mlmvpnXXXX داخل پوشهٔ «کانفیگ‌های رایگان» در تب Manual قرار می‌گیرند و اگر کانفیگی را از قبل داشته باشید، دوباره اضافه نمی‌شود (به شما می‌گوید چند تا تکراری بود).",
                            Icons.Default.Bolt
                        ),
                        ChangelogItem(
                            "پرچم واقعی کشور روی هر کانفیگ، بدون نیاز به اتصال کامل",
                            "حین تست «دیلی واقعی»، اگر کانفیگی متصل شد و پرچمش هنوز مشخص نبود، یک بار (فقط همان یک بار، بعداً از حافظه خوانده می‌شود) از همان تونل واقعی کشور خروجی واقعی سرور استعلام و روی کارت کانفیگ نمایش داده می‌شود؛ دیگر لازم نیست حتماً به کانفیگ وصل شوید تا پرچمش را ببینید.",
                            Icons.Default.Flag
                        ),
                        ChangelogItem(
                            "رفع ناهماهنگی پرچم بالای دکمه اتصال",
                            "پرچم بالای دکمه اتصال از دیتابیس جغرافیایی خودِ Cloudflare (فیلد loc در cdn-cgi/trace) خوانده می‌شد که گاهی با واقعیت فرق داشت (مثلاً یک آی‌پی آمریکایی را کانادا نشان می‌داد). حالا از همان منبع پرچم کانفیگ‌ها استفاده می‌کند، پس همیشه با هم و با سایت‌هایی مثل ip.me هماهنگ است.",
                            Icons.Default.Sync
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
                            "Automatic recovery after a real network drop",
                            "Previously, when Wi-Fi/mobile actually dropped, the tunnel had no way to notice, so the UI kept reporting \"connected\" indefinitely -- even after the network came back -- until the user manually disconnected and reconnected. The app now watches real connectivity and, if the network is lost while connected and later comes back, automatically cycles the same connection (stop, wait for full teardown, reconnect) instead of leaving it stuck.",
                            Icons.Default.Wifi
                        ),
                        ChangelogItem(
                            "Game tab: Server 1/2 replaced with the Aether engine",
                            "The two hardcoded legacy UAE WireGuard servers (one of which was already dead) were removed from the Game tab. Full-tunnel game boosting now uses the Aether engine, which picks the healthiest path across several protocols instead of a single fixed server.",
                            Icons.Default.SportsEsports
                        ),
                        ChangelogItem(
                            "Fixed the bottom nav bar's icons looking lopsided",
                            "Whenever the optional Game/WireGuard tabs made the two sides hold a different number of icons (e.g. 3 left / 2 right), the shorter side's icons visibly clustered toward the centre button with a dead gap past them, and ended up a different size than the other side's. Both sides now use a fixed, identical per-icon width and centre their (unpadded) icon group within their half -- whether Game and WireGuard are both on, one is, or neither is.",
                            Icons.Default.Apps
                        ),
                        ChangelogItem(
                            "Fixed a dead empty gap under the hamburger drawer on tall phones",
                            "The drawer left an empty gap between the last menu item and the bottom of the screen on tall phones. The gap between the main menu and the emergency section now expands on tall screens (pinning the emergency section to the bottom), while still scrolling normally with everything packed together on short screens where it doesn't all fit.",
                            Icons.Default.Menu
                        ),
                        ChangelogItem(
                            "Fixed the local proxy passing no traffic in \"Proxy mode\"",
                            "In \"Proxy mode\" (local SOCKS/HTTP proxy only, no full-device VPN tunnel), connecting to a node could leave the app showing \"connected\" while the local proxy passed no traffic at all -- every site timed out through it. Root cause: proxy mode deliberately skips creating the VPN tunnel interface, but the Xray config was still being built as if a tunnel existed, handing xray-core a \"tun\" inbound pointing at an invalid file descriptor. xray-core accepted this at startup (reporting success), then the invalid inbound could crash the whole engine loop a few seconds later -- taking the SOCKS/HTTP proxy down with it, silently, after the UI had already committed to \"connected\". The tunnel inbound is now only included when a real VPN interface actually exists.",
                            Icons.Default.SettingsEthernet
                        ),
                        ChangelogItem(
                            "Aether: added a timer and explanation on the gateway-scan step",
                            "The gateway-scan step (MASQUE) can legitimately take anywhere from tens of seconds to a few minutes depending on scan mode, and on networks that DPI-block MASQUE it runs the full time budget before failing -- with only a static \"working\" badge, that read as a frozen app. The status card now shows a live elapsed-time counter on the scan step, and after 20 seconds an explanatory note that this step can take a while and that some networks block or slow MASQUE, with a reminder that tapping the connect button again cancels it. This doesn't change scan behavior or success rate -- it just makes a long scan legible instead of looking stuck.",
                            Icons.Default.Timer
                        ),
                        ChangelogItem(
                            "Made scanner \"Real Delay\" and speed test results more accurate",
                            "This test is an actual Xray-proxied connection per config, not a raw ping -- but hundreds of configs were being tested with up to 8 real proxied connections running concurrently against the same servers, which reads as suspicious concurrent-handshake traffic to Cloudflare/DPI and gets throttled. The resulting delay/speed numbers were measuring self-inflicted congestion from the test itself, not real conditions. One of the two concurrency limits involved even carried a comment (\"Cloudflare/DPI might block if >3 concurrent handshakes\") directly contradicting the 8 it was actually set to. Both are now capped at 3, matching the safe value already used elsewhere in the app for the same kind of test.",
                            Icons.Default.Speed
                        ),
                        ChangelogItem(
                            "Substantially reduced battery drain while connected",
                            "Four separate causes, all of which cost power continuously for as long as the VPN was up: 1) The tunnel held an unbounded partial wake lock for the entire session, so the CPU could never enter deep sleep or Doze while connected -- including overnight with the phone idle in a pocket and not a single byte moving. The lock is now held only while traffic is actually flowing and released after 5 minutes of silence; incoming packets still wake the process through the tunnel interface regardless. 2) A leftover diagnostic loop woke every 5 seconds for the whole session purely to write a RAM figure to the log that nothing reads in a release build -- roughly 17,000 wakeups a day doing no work; removed entirely. 3) The traffic meter wrote to storage every 2 seconds whether or not anything had changed -- about 43,000 pointless disk writes a day on an idle tunnel; it now only writes when bytes actually moved. 4) The auto-switch watcher re-read a single boolean every 10 seconds even though the feature is off by default; now checked once a minute.",
                            Icons.Default.BatteryChargingFull
                        ),
                        ChangelogItem(
                            "Fixed redundant subscription-link uploads to Cloudflare",
                            "The background auto-sync re-uploaded a link's configs to Cloudflare on every node-list change, and most of those changes are metadata-only (a ping result, a speed-test figure, a country flag) that leave the actual config list byte-identical. Every one still cost a full Cloudflare API round trip, all day, for every link on every account -- wasted battery and data, and enough API traffic to plausibly contribute to the Cloudflare errors some users saw after leaving the app running for a day. Auto-sync now skips the upload entirely when the config list hasn't actually changed.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "Fixed the cloud panel getting stuck behind \"email verification required\" for unrelated errors",
                            "Adding a Cloudflare account could get permanently stuck behind a \"verify your email\" overlay with no way out except an external Cloudflare login, even when email verification wasn't actually the problem. Root cause: when creating the account's workers.dev subdomain failed, the app decided it must be an unverified email purely by checking whether Cloudflare's raw error text happened to contain the word \"verify\" -- which several unrelated Cloudflare errors (token scope issues, rate limiting, etc.) can also contain, incorrectly locking the whole cloud panel behind the verification screen with no visibility into the real cause. It now asks Cloudflare's own account-status endpoint for the real verified state before showing that screen, and surfaces the actual error otherwise.",
                            Icons.Default.VerifiedUser
                        ),
                        ChangelogItem(
                            "Fixed subscription-link configs going empty on their own",
                            "A subscription link's configs could silently go empty after a while, well before the user ever pressed \"Update\" -- any client would refresh the link and get zero configs back, even though the link had worked fine right after creation. Root cause: sub-links store which node group they serve as a composite string (e.g. \"manual:<engine>:<groupTitle>\"), and the screen's own \"Update\" button correctly parses that string apart to find the matching nodes -- but the background auto-sync job (which re-uploads a link's configs a few seconds after any node list change) was comparing a node's plain engine type directly against that whole composite string, which can never match. So every background sync silently overwrote the link with an empty config list; the \"after 20-30 refreshes\" users saw was really just how long it took one of those background syncs to fire. The auto-sync job now parses the group reference the same way the Update button does.",
                            Icons.Default.LinkOff
                        ),
                        ChangelogItem(
                            "In-app update check, download and install",
                            "The app now checks GitHub for a newer release right after launch (and again whenever any VPN connection reaches \"connected\", in case GitHub itself was blocked at launch). If a newer version exists, a modal shows the new version number, download size, and the full scrollable changelog, with a \"Download new version\" button that opens a dedicated download screen (a fill progress bar with a live percentage) and hands off to the system installer automatically once the download completes. If GitHub is unreachable, the check fails completely silently with no interruption to the rest of the app.",
                            Icons.Default.SystemUpdate
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
                        ),
                        ChangelogItem(
                            "Updated the bundled BPB panel to v5.1.1",
                            "The BPB panel in the Cloud section was upgraded from the old v4.2.2 to v5.1.1. Since that version changed the deployment model entirely (per-account settings are now baked directly into the worker's own code instead of Cloudflare env variables like before), deploy, panel login, subscription fetching, and settings save were all rewritten for the new approach. The settings form was also updated to match the new version's fields.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "Updated the bundled EDG worker to the latest build",
                            "The EDG worker in the Cloud section (based on cmliu/edgetunnel) was updated to the latest upstream release, carrying several months of transport optimizations (smarter up/downlink packet coalescing, concurrent TCP connection racing, faster DNS caching) that should make EDG configs noticeably faster and more stable. The swap is fully compatible -- no changes needed for existing accounts.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "Updated the bundled Nahan panel to v3.0.0",
                            "The Nahan panel in the Cloud section was upgraded from v2.9.4 to v3.0.0: upstream VLESS proxy chaining, a v2rayN JSON subscription format, a redesigned add/edit-user form with modern toggle switches instead of checkboxes, and several RTL/dark-mode dashboard fixes. The D1 binding name and API route contract are unchanged, so the swap is fully compatible.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "Removed the Deno panel entirely",
                            "The Deno panel (drawer entry, tab, FAQ section, and its underlying code) has been removed. api.deno.com has been blocked in Iran for a while now, and Deno's connection-duration-based billing model was never a good fit for VPN use anyway; the Cloudflare panels (BPB/EDG/Nahan) cover the same need without either problem.",
                            Icons.Default.Delete
                        ),
                        ChangelogItem(
                            "Fixed MLM/Nahan deploys getting permanently stuck on a D1 database error",
                            "MLM (and Nahan) deploys could get permanently stuck failing at \"Failed to create D1 Database\", with no fix short of manually deleting old databases in the Cloudflare dashboard. Root cause: every deploy attempt -- including retries of a failed one -- provisioned a brand-new D1 database and never reused or cleaned up earlier ones, so a handful of retries would quietly eat into the account's D1 database quota until none was left to create. Deploys now reuse the account's existing database (or an orphaned one from a previous attempt) instead of always creating a new one. If the account's 10-database Free-plan limit is genuinely full of databases from other tools, the error now lists every existing database's name so you know exactly what to delete.",
                            Icons.Default.Storage
                        ),
                        ChangelogItem(
                            "Clarified the \"check domain\" button in the personal anti-sanction DNS",
                            "The check button only classifies a domain -- it never added it to the routing list on its own, but its \"sanctioned\" message read as if turning the feature on would be enough. Users would check a domain, see it's sanctioned, turn the feature on, and be confused when the site still didn't open because they never pressed the separate \"Add\" button. The message now says to press Add, and if a domain comes back sanctioned but isn't in your list yet, a red warning makes that explicit.",
                            Icons.Default.Warning
                        ),
                        ChangelogItem(
                            "Fixed sanctioned sites (403 errors) still not opening in the personal anti-sanction DNS",
                            "Sanctioned sites -- confirmed \"sanctioned\" by the feature's own domain checker -- still didn't open, returning 403s exactly as if the feature were off. Root cause, found via a live logcat capture from a real device: domain routing relied entirely on sniffing the SNI out of the TLS ClientHello, which silently fails for any site using Encrypted Client Hello (ECH, increasingly Chrome's default) since the SNI is encrypted and invisible to sniffing -- every \"sanctioned\" connection fell through to the catch-all direct rule and hit the origin from the real Iranian IP. A fakedns pool fixed the resolution side, but a second live logcat capture showed the fake-IP-to-domain reverse lookup wasn't reliably matching the domain-based routing rule at routing time on this Xray build. Routing now matches directly on the fakedns pool's IP range (198.18.0.0/15) instead of depending on that reverse lookup at all.",
                            Icons.Default.Dns
                        ),
                        ChangelogItem(
                            "Fixed unrelated sites breaking when the personal anti-sanction DNS was turned on",
                            "Turning this feature on could break sites that were never even supposed to be touched (e.g. Gmail). Root cause: its DNS resolver was hardcoded to plain UDP 1.1.1.1:53 -- a protocol commonly blocked or throttled on Iranian ISPs -- with no fallback, unlike the main VPN config which already resolves via DoH over HTTPS for exactly this reason. It now resolves via the same DoH-over-443 approach.",
                            Icons.Default.Dns
                        ),
                        ChangelogItem(
                            "\"Update worker\" button in the personal anti-sanction DNS screen",
                            "Once an exit worker is already deployed, you can now redeploy the latest bundled EDG worker onto the same account without deleting and re-adding it.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "Fixed the \"build worker\" button not updating after a successful deploy",
                            "After successfully deploying the exit worker in the personal anti-sanction DNS screen, it kept showing the \"build worker\" button instead of switching to \"ready\" -- you had to leave and re-enter the screen to see it had worked. Root cause: the deploy functions update the Cloudflare account object's fields in place, so the updated list pushed into the shared account state was structurally identical to what was already there, and Kotlin's StateFlow skips re-emitting a value it considers unchanged. Now tracks deploy success locally instead.",
                            Icons.Default.Refresh
                        ),
                        ChangelogItem(
                            "Fixed the anti-sanction DNS worker deploy failing permanently on retries",
                            "This step (the same EDG worker deploy used elsewhere) could fail permanently after a few retries for the same reason MLM/Nahan did: it always created a brand-new KV namespace and never reused or cleaned up earlier ones. It now reuses the account's existing namespace (or an orphaned one from a previous attempt); failures now show the real Cloudflare error too.",
                            Icons.Default.CloudSync
                        ),
                        ChangelogItem(
                            "Fixed the Scanner tab's \"Ping Health Test\" button hanging or crashing",
                            "This button on combined/mixed config groups (and the MLM \"update subscription with healthy IPs\" flow that depends on it) could hang indefinitely or crash the app on some devices. Root cause: this specific code path was missing the native Xray core initialization call that every other testing feature in the app performs first -- one line was accidentally commented out. Whether it worked depended entirely on some other screen having already initialized the core earlier in the same app session, which is why it worked reliably for some users/flows and not others. Restored the missing call.",
                            Icons.Default.BugReport
                        ),
                        ChangelogItem(
                            "Fixed the MLM \"User Management\" screen getting stuck on \"Error fetching users\"",
                            "This screen could get permanently stuck on that error right after a fresh deploy. Root cause: Cloudflare's own edge returns a transient 404 (its own error code 1042) for the first few seconds after a brand-new *.workers.dev route is enabled, before the worker code even runs. The app now retries a few times automatically; any other real failure now shows the actual HTTP status/response instead of a generic message.",
                            Icons.Default.Refresh
                        ),
                        ChangelogItem(
                            "New \"Get free configs\" button in the Connection tab",
                            "A multi-step wizard above the + button: it first shows how many configs are available across a few public sources, you pick how many you want, and the system tests them in two stages (a fast reachability filter, then a real Xray-proxied connection test -- the same accuracy as \"Real Delay\") and only keeps genuinely working ones. A \"this many is enough\" button lets you stop the search early and keep what's found so far. Configs land under a \"Free Configs\" folder in the Manual tab with random mlmvpnXXXX names, and anything you already have is skipped instead of duplicated (it tells you how many were already yours).",
                            Icons.Default.Bolt
                        ),
                        ChangelogItem(
                            "Real per-config country flags, no full connection required",
                            "During the \"Real Delay\" test, if a config connects and its flag isn't known yet, the app queries the config's real exit country through that same live tunnel (once -- cached afterward, no repeat lookups) and shows it on the card. No need to fully connect anymore just to see a config's real flag.",
                            Icons.Default.Flag
                        ),
                        ChangelogItem(
                            "Fixed a mismatched flag above the Connect button",
                            "The flag above the Connect button came from Cloudflare's own geoIP database (the `loc` field in cdn-cgi/trace), which occasionally disagreed with reality (e.g. showing Canada for a US exit IP). It now uses the same source as the per-config flags, so both always agree with each other and with sites like ip.me.",
                            Icons.Default.Sync
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
