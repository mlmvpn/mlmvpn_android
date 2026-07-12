package com.mlmvpn.scanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class HelpArticle(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val content: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpCenterScreen(onDismiss: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var expandedArticleId by remember { mutableStateOf<String?>(null) }
    var showFaqModal by remember { mutableStateOf(false) }

    val primaryColor = Color(0xFF4285F4)
    val bgColor = Color(0xFF121212)
    val surfaceColor = Color(0xFF1E1E1E)
    val textColor = Color(0xFFE8EAED)
    val mutedColor = Color(0xFF9AA0A6)

    val isFa = com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale().language == "fa"

    val titleStr = if (isFa) "مرکز آموزش و مستندات" else "Help & Documentation Center"
    val searchStr = if (isFa) "جستجو در آموزش‌ها..." else "Search tutorials..."
    val noResultStr = if (isFa) "موردی یافت نشد!" else "No results found!"

    val articles = remember(isFa) {
        if (isFa) getHelpArticlesFa() else getHelpArticlesEn()
    }

    val filteredArticles = remember(searchQuery, isFa) {
        if (searchQuery.isBlank()) articles
        else articles.filter { 
            it.title.contains(searchQuery, ignoreCase = true) || 
            it.content.contains(searchQuery, ignoreCase = true) 
        }
    }

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
                            text = titleStr,
                            color = primaryColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Search Bar
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(searchStr, color = mutedColor) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = mutedColor) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = mutedColor)
                                }
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(color = textColor),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = Color(0xFF3C4043),
                            cursorColor = primaryColor
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }

                // FAQ Banner Button
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showFaqModal = true },
                    color = primaryColor.copy(alpha = 0.1f)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LiveHelp, contentDescription = null, tint = primaryColor, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isFa) "پرسش و پاسخ‌های متداول (FAQ)" else "Frequently Asked Questions", color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(if (isFa) "جواب سوالات پرتکرار شما اینجاست!" else "Find answers to common questions here!", color = textColor.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                        Icon(
                            Icons.Default.ChevronRight, 
                            contentDescription = null, 
                            tint = primaryColor, 
                            modifier = Modifier.scale(scaleX = if (isFa) -1f else 1f, scaleY = 1f)
                        )
                    }
                }

                // Articles List
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (filteredArticles.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(noResultStr, color = mutedColor)
                            }
                        }
                    }

                    items(filteredArticles, key = { it.id }) { article ->
                        ArticleCard(
                            article = article,
                            isExpanded = expandedArticleId == article.id,
                            onToggle = { 
                                expandedArticleId = if (expandedArticleId == article.id) null else article.id 
                            }
                        )
                    }
                }
        }
    }

    if (showFaqModal) {
        FaqModal(onDismiss = { showFaqModal = false }, isFa = isFa)
    }
}

@Composable
fun ArticleCard(article: HelpArticle, isExpanded: Boolean, onToggle: () -> Unit) {
    val primaryColor = Color(0xFF4285F4)
    val surfaceColor = Color(0xFF1E1E1E)
    val textColor = Color(0xFFE8EAED)
    val mutedColor = Color(0xFF9AA0A6)

    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = article.icon,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = article.title,
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = mutedColor
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Divider(color = Color(0xFF333333), modifier = Modifier.padding(bottom = 16.dp))
                    
                    // Simple Markdown-like parser for bold and line breaks
                    val lines = article.content.split("\n")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (line in lines) {
                            if (line.trim().isEmpty()) continue
                            
                            // Highlight lines starting with warning or note
                            val isWarning = line.contains("⚠️")
                            
                            val parsedLine = line.replace("**", "") // Simplified, in a real app use an AnnotatedString builder
                            
                            Text(
                                text = parsedLine,
                                color = if (isWarning) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.85f),
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

fun getHelpArticlesFa(): List<HelpArticle> {
    return listOf(
        HelpArticle(
            id = "base",
            title = "۱. آموزش پایه (صفر تا صد استفاده)",
            icon = Icons.Default.School,
            content = """
                **آموزش قدم به قدم استفاده از برنامه:**
                
                ۱. **کلادفلر:** وارد سایت cloudflare.com شوید و در صورت نداشتن اکانت ثبت‌نام کنید یا وارد شوید.
                ۲. **دریافت API:** به بخش پروفایل (My Profile) > API Tokens بروید. در قسمت Global API Key روی دکمه View کلیک کرده و کد را دریافت کنید.
                ۳. **جای‌گذاری:** در تب «ابری» برنامه، ایمیل کلادفلر و Global API Key را وارد کنید و روی دکمه بررسی/ورود کلیک کنید.
                ۴. **دیپلوی پنل:** پس از ورود، در صورت نیاز یک ساب‌دامین بسازید. سپس روی گزینه «دیپلوی» کلیک کنید تا پنل شخصی شما روی کلادفلر ساخته شود.
                ۵. **دریافت نود:** پس از پایان دیپلوی، روی دکمه «دریافت نود» کلیک کنید تا کانفیگ‌های اولیه ذخیره شوند.
                ۶. **اسکن آی‌پی:** به تب «اسکنر» بروید. در این بخش با وارد کردن آی‌پی یا ساب‌نت، روی شروع اسکن کلیک کنید تا آی‌پی‌های تمیز پیدا شوند.
                ۷. **ترکیب:** پس از پیدا شدن آی‌پی‌های سالم، در پایین صفحه روی دکمه «ترکیب با نودهای ابری» کلیک کنید.
                ۸. **انتقال به نود:** به تب «نودها» بروید. در اینجا کانفیگ‌های جدیدی که از ترکیب آی‌پی‌های تمیز و پنل شما ساخته شده‌اند را مشاهده می‌کنید.
                ۹. **پینگ و سرعت:** در تب نودها، کیفیت تمام نودها را بررسی کنید.
                ۱۰. **اتصال:** بهترین نود را انتخاب کرده، به تب اصلی (اتصال) برگردید و دکمه اتصال را لمس کنید.
            """.trimIndent()
        ),
        HelpArticle(
            id = "fixed_ip",
            title = "۲. آموزش سیستم آی‌پی ثابت و تغییر لوکیشن (اختصاصی Nahan)",
            icon = Icons.Default.LocationOn,
            content = """
                **چگونه لوکیشن کانفیگ خود را برای همیشه روی یک کشور (مثلاً آمریکا) قفل کنیم؟**
                
                در آپدیت جدید برنامه، کدهای پروژه اصلی Nahan در اپلیکیشن ما به صورت کاملاً اختصاصی تغییر یافته‌اند. این تغییر به ما اجازه می‌دهد که علاوه بر تغییر آدرس اتصال (Ingress)، تونل خروجی وورکر (Egress ProxyIP) را هم کنترل کنیم.
                
                **حالت اول: برای یک کاربر خاص (مثلاً user1) - روش پیشنهادی**
                ۱. در تب ابری، بخش تنظیمات NHN، به لیست کاربران بروید.
                ۲. روی آیکون مداد ✏️ (ویرایش) کنار نام کاربر کلیک کنید.
                ۳. آی‌پی کشور مورد نظر (مثلاً آمریکا) را در فیلد جدیدِ **«آی‌پی ثابت اختصاصی کاربر (Proxy IP)�** قرار دهید و ذخیره کنید.
                *نتیجه:* با این کار، هم آدرس اولیه کانفیگ و هم تونل خروجی این کاربر روی آی‌پی آمریکا قفل می‌شود.
                
                **حالت دوم: برای کل پنل (همه کاربران به صورت پیش‌فرض)**
                اگر می‌خواهید همه کاربران پنل آی‌پی ثابت آمریکا را داشته باشند:
                ۱. در تنظیمات شبکه NHN، فیلد **«ریلی اختصاصی (Custom Relay)�** را با آی‌پی آمریکا پر کنید. (این فیلد لوکیشن خروجی وورکر را به اجبار به آمریکا می‌فرستد).
                ۲. فیلد **«آی‌پی ثابت کل پنل / فرگمنت»** را هم با همان آی‌پی پر کنید. (این فیلد آدرسِ خامِ کانفیگی که دریافت می‌کنید را آمریکا می‌کند).
                
                **⚠️ قدم بسیار مهم (استقرار مجدد):**
                از آنجا که کدهای هسته وورکر تغییر کرده، باید یک بار از منوی ابری (سه نقطه کنار اکانت) روی **"استقرار مجدد" (Redeploy)** کلیک کنید تا کدهای جدیدِ وورکر روی کلادفلر شما نصب شوند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "nahan_users",
            title = "۳. مدیریت کاربران نهان (ساخت کانفیگ برای دوستان)",
            icon = Icons.Default.GroupAdd,
            content = """
                **چگونه به صورت نامحدود برای دوستان و خانواده کانفیگ بسازیم؟**
                
                شما می‌توانید از طریق پنل ابری خود، کاربران مجزا با ترافیک‌های محدود یا نامحدود بسازید:
                
                ۱. در تب «ابری»، زیر اکانت NHN دیپلوی شده خود، روی دکمه مدیریت کاربران (آیکون چند کاربر) کلیک کنید.
                ۲. لیستی از کاربران فعلی (از جمله اکانت پیش‌فرض خودتان) را می‌بینید. روی دکمه شناور **افزودن (+)** در پایین صفحه کلیک کنید.
                ۳. **نام کاربری:** یک نام دلخواه (مثلا Ali) وارد کنید.
                ۴. **محدودیت ترافیک:** می‌توانید حجم مشخصی (مثلا 50 گیگابایت) و تعداد روز انقضا تعیین کنید. اگر 0 بگذارید، نامحدود خواهد بود.
                ۵. پس از ذخیره، کاربر ساخته می‌شود.
                ۶. برای ارسال کانفیگ به دوستتان، روی آیکون **اشتراک‌گذاری** (Share) کنار نام او کلیک کنید. لینک اتصال VLESS کپی می‌شود و می‌توانید آن را در تلگرام یا واتس‌اپ بفرستید.
            """.trimIndent()
        ),
        HelpArticle(
            id = "flag_logic",
            title = "۴. جریان نمایش پرچم نودها چیست؟",
            icon = Icons.Default.Flag,
            content = """
                **چرا با وجود اینکه آی‌پی کلادفلر را می‌دهیم، پرچم کشور واقعی سرور نمایش داده می‌شود؟**
                
                یکی از قابلیت‌های هوشمند این اپلیکیشن، سیستم تشخیص مسیر واقعی دیتای شماست.
                وقتی شما کانفیگ‌های خود را با آی‌پی‌های تمیز اسکنر (Scanner) یا فرگمنت ترکیب می‌کنید، آدرس ظاهری نود (کانفیگ) تغییر می‌کند (مثلاً به یک آی‌پی آلمان). 
                
                اما سرور واقعی وورکر کلادفلر شما ممکن است در فرانسه یا آمریکا باشد.
                
                **نحوه عملکرد فنی:**
                اپلیکیشن ما در پس‌زمینه، به جای نگاه کردن به نام ظاهری، به صورت مستقیم با سرور واقعی که ترافیک از آن عبور می‌کند تماس می‌گیرد. 
                حتی برای آی‌پی‌های نسخه ۶ (IPv6) که رزولوشن آن‌ها سخت‌تر است، برنامه از طریق ارتباط ایمن DNS over HTTPS (DoH) کلادفلر، مسیر را رمزگشایی کرده و **پرچم دقیق و واقعی آن کشوری که در نهایت سایت‌ها شما را با آن می‌شناسند** به شما نمایش می‌دهد. این پرچم نشان‌دهنده مسیر فیزیکی خروجی دیتاست، نه فقط یک آدرس ظاهری.
            """.trimIndent()
        ),
        HelpArticle(
            id = "cloudflare_limits",
            title = "۵. محدودیت ۱۰۰ هزار درخواست روزانه کلادفلر",
            icon = Icons.Default.Warning,
            content = """
                **سقف مصرف کلادفلر چیست و چه زمانی با آن مواجه می‌شوید؟**
                
                سرویس رایگان Cloudflare Workers که پنل شما روی آن نصب می‌شود، محدودیت **۱۰۰,۰۰۰ درخواست در روز** (به ازای هر اکانت) دارد.
                
                **جزئیات بسیار مهم:**
                - **درخواست چیست؟** در دنیای وب، دانلود یک فایل ۱ گیگابایتی یک درخواست نیست! هر بار که صفحه‌ای باز می‌شود، صدها درخواست (برای عکس‌ها، کدها و...) ارسال می‌شود. تماشای ویدیو در یوتیوب یا دانلود فایل با دانلود منجر، می‌تواند ده‌ها هزار درخواست در دقیقه تولید کند.
                - **اگر به سقف برسیم چه می‌شود؟** پنل شما و تمام نودهای متصل به آن فوراً قطع خواهند شد و خطای 1045 یا عدم اتصال دریافت می‌کنید.
                - **چه زمانی ریست می‌شود؟** این محدودیت هر روز ساعت ۳:۳۰ بامداد (به وقت ایران) که معادل 00:00 به وقت جهانی (UTC) است، صفر می‌شود و کانفیگ‌ها دوباره وصل می‌شوند.
                
                **راه‌حل حرفه‌ای:**
                اگر مصرف شما و دوستانتان سنگین است، تنها راه رایگان این است که چند اکانت جیمیل و کلادفلر مختلف بسازید، API جدید بگیرید، و در تب ابریِ اپلیکیشن، پنل‌های مجزا (اکانت ابری جدید) دیپلوی کنید تا بار مصرفی بین آن‌ها تقسیم شود.
            """.trimIndent()
        ),
        HelpArticle(
            id = "sort_panel",
            title = "۶. قابلیت قدرتمند «مرتب‌سازی بر اساس پنل»",
            icon = Icons.Default.Sort,
            content = """
                **چگونه صدها کانفیگ ابری را بدون سردرگمی مدیریت کنیم؟**
                
                اگر شما از چندین اکانت ابری یا پنل‌های مختلف (مثل EDG, BPB, NHN و حتی نودهای کاستوم) به صورت همزمان استفاده می‌کنید، لیست نودهای شما در تب «نودها» به سرعت شلوغ و گیج‌کننده می‌شود.
                
                **توضیح فنی سوئیچ مرتب‌سازی:**
                - در بالای لیست نودها، کنار آیکون سطل زباله، یک سوئیچ به نام «مرتب‌سازی بر اساس پنل» وجود دارد.
                - **وقتی خاموش است:** تمام نودها به صورت یکپارچه زیر هم ردیف می‌شوند.
                - **وقتی روشن است:** اپلیکیشن وارد کدهای کانفیگ‌ها شده و آن‌ها را بر اساس «موتور تولیدکننده» (Engine Type) دسته‌بندی می‌کند. 
                - در این حالت، برای هر پنل یک **تب اختصاصی (Segmented Tab)** در بالای لیست ظاهر می‌شود. با کلیک روی هر تب، فقط نودهای مربوط به همان پنل نمایش داده می‌شوند.
                
                **مزیت اصلی:** این قابلیت نه تنها باعث زیبایی و دسترسی سریع‌تر می‌شود، بلکه وقتی روی دکمه «پینگ همه» کلیک می‌کنید، فقط کانفیگ‌های همان پنلی که در حال مشاهده آن هستید پینگ می‌شوند و بار اضافی به سایر پنل‌ها وارد نمی‌شود.
            """.trimIndent()
        ),
        HelpArticle(
            id = "scanner_pro",
            title = "۷. راهنمای جامع اسکنر هوشمند دومرحله‌ای",
            icon = Icons.Default.Radar,
            content = """
                **چرا اسکنر این اپلیکیشن با سایر اسکنرهای ساده متفاوت است؟**
                
                اسکنر تعبیه شده در این اپلیکیشن از یک معماری دومرحله‌ای و مبتنی بر هسته Xray استفاده می‌کند تا دقیق‌ترین نتیجه را بدهد:
                
                **فاز اول: شبکه خام (TCP/TLS Ping)**
                شما در بخش اسکنر یک یا چند رنج آی‌پی (CIDR مثل 104.20.0.0/24) را وارد می‌کنید. اپلیکیشن ابتدا هزاران آی‌پی از این رنج تولید کرده و با سرعت بسیار بالا، آن‌ها را در سطح شبکه خام پینگ می‌کند. این کار فقط آی‌پی‌هایی که روشن و زنده هستند را جدا می‌کند.
                
                **فاز دوم: تست مسدودیت زیر بار اینترنت ملی (Xray Testing)**
                آی‌پی‌های زنده‌ای که از فاز اول عبور کردند، مستقیماً وارد هسته Xray اندروید می‌شوند. اپلیکیشن در پس‌زمینه یک تونل واقعی می‌سازد تا ببیند آیا این آی‌پی‌ها توسط فیلترینگ مسدود شده‌اند یا خیر. اگر پورت کلادفلرِ یک آی‌پی روی شبکه اینترنت شما بسته باشد، در این مرحله شناسایی و حذف می‌شود.
                
                **مرحله پایانی (ترکیب):**
                آی‌پی‌هایی که از هر دو فاز زنده بیرون می‌آیند، آی‌پی‌های طلایی هستند! با کلیک روی گزینه **«ترکیب با نودهای ابری»** در پایین صفحه اسکنر، این آی‌پی‌ها روی کانفیگ‌های پنل شما سوار می‌شوند و ده‌ها کانفیگ بدون فیلتر و پرسرعت در تب «نودها» برای شما آماده اتصال می‌شوند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "ip_archive",
            title = "۸. سیستم آرشیو آی‌پی‌ها و تست مجدد (Retest)",
            icon = Icons.Default.Storage,
            content = """
                **چگونه بدون صرف زمان برای اسکن‌های طولانی، همیشه آی‌پی تمیز داشته باشیم؟**
                
                اپلیکیشن به یک سیستم حرفه‌ای به نام **آرشیو اسکنر** مجهز است.
                
                **ذخیره خودکار:** 
                هر بار که شما با موفقیت اسکن می‌کنید و در نهایت دکمه «ترکیب» را می‌زنید، اپلیکیشن به صورت خودکار آن دسته از آی‌پی‌های سالمی که پیدا کرده بود را در بخش «آرشیو اسکنر» (در تب اسکنر) ذخیره می‌کند.
                
                **قابلیت جادویی تست مجدد (Retest):**
                پس از چند روز، ممکن است آی‌پی‌های تمیزی که پیدا کرده بودید توسط سیستم فیلترینگ مسدود شوند و کانفیگ‌هایتان کار نکنند. به جای اینکه دوباره رنج‌های طولانی را از صفر اسکن کنید:
                ۱. به بخش اسکنر بروید و یکی از گروه‌های قدیمیِ آرشیو را باز کنید.
                ۲. روی آیکون رفرش/تست مجدد کلیک کنید.
                ۳. اپلیکیشن فقط همان چند آی‌پی قدیمی را دوباره پینگ می‌کند و مرده‌ها را بلافاصله دور می‌ریزد.
                ۴. با زدن دکمه ترکیب، شما در عرض چند ثانیه با استفاده از آی‌پی‌هایی که هنوز سالم مانده‌اند، کانفیگ‌های جدید می‌سازید!
            """.trimIndent()
        ),
        HelpArticle(
            id = "nahan_settings_guide",
            title = "۹. راهنمای کامل تنظیمات پیشرفته پنل نهان",
            icon = Icons.Default.Settings,
            content = """
                **راهنمای کامل فیلدهای تنظیمات پنل نهان**
                
                برای دسترسی به تنظیمات پیشرفته، در تب "ابری" روی آیکون چرخ‌دنده (تنظیمات) در زیر اکانت نهان کلیک کنید. در این صفحه فیلدهای مختلفی وجود دارد که هرکدام کاربرد خاصی دارند:
                
                **۱. پروتکل خروجی (Output Protocol):**
                این فیلد به شما اجازه می‌دهد نوع کانفیگ ساخته شده را تعیین کنید. می‌توانید آن را روی `VLESS` یا `Trojan` قرار دهید. اگر آن را روی `Both` بگذارید، وورکر شما همزمان از هر دو پروتکل پشتیبانی خواهد کرد و کانفیگ‌های متنوع‌تری به شما می‌دهد.
                
                **۲. پسورد (UUID/Password):**
                این کد طولانی، قلب تپنده و شناسه امنیتی کانفیگ شماست. این پسورد هم برای ورود به پنل تحت وب نهان و هم به عنوان رمز اتصال در کانفیگ‌های VLESS و Trojan استفاده می‌شود. نیازی به تغییر آن نیست مگر آنکه بخواهید رمز اختصاصی خود را داشته باشید.
                
                **۳. ریلی اختصاصی (Custom Relay / ProxyIP):**
                این فیلد یکی از مهم‌ترین بخش‌ها برای ثابت کردن آی‌پی خروجی (Egress) است. اگر آی‌پی یک سرور تمیز از کشور دلخواه (مثلا آمریکا) را در اینجا قرار دهید، ترافیک شما بعد از کلادفلر به آن سرور منتقل می‌شود. نتیجه این است که سایت‌ها لوکیشن شما را "آمریکا" تشخیص می‌دهند. اگر خالی بماند، کلادفلر خودش به صورت خودکار لوکیشن را تعیین می‌کند.
                
                **۴. آی‌پی ثابت کل پنل / فرگمنت:**
                این آی‌پی همان آدرسی است که در ظاهر کانفیگ‌های شما (بخش Address در V2ray) قرار می‌گیرد (Ingress). این آی‌پی به کلادفلر وصل می‌شود. اگر آن را تغییر دهید، تمامی کانفیگ‌هایی که از این پنل دریافت می‌کنید، از همین آی‌پی برای دور زدن فیلترینگ استفاده خواهند کرد. (همچنین می‌توانید به جای آی‌پی، یک دامنه تمیز یا آدرس فرگمنت قرار دهید).
                
                *نکته مهم:* بعد از تغییر هرکدام از این تنظیمات و کلیک روی دکمه "ذخیره تنظیمات"، حتماً یک بار از منوی سه نقطه پنل ابری خود، روی **"استقرار مجدد"** کلیک کنید تا تنظیمات جدید به طور کامل روی سرورهای کلادفلر اعمال شوند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "edg_fixed_ip",
            title = "۱۰. راهنمای آی‌پی ثابت در پنل ادج (EDG)",
            icon = Icons.Default.SettingsEthernet,
            content = """
                **چگونه لوکیشن کانفیگ‌های پنل EDG را تغییر دهیم؟**
                
                برای اینکه ترافیک خروجی پنل ادج از آی‌پی آلمانِ کلادفلر خارج نشود و به لوکیشن دلخواه شما (مثل فنلاند، آمریکا، ترکیه و...) منتقل شود، می‌توانید از قابلیت "آی‌پی ثابت" استفاده کنید.
                
                **مراحل تنظیم آی‌پی ثابت:**
                ۱. در تب "ابری"، روی آیکون چرخ‌دنده‌ی زیر پنل EDG کلیک کنید.
                ۲. در فیلد "آی‌پی ثابت (Proxy IP)"، آی‌پی تمیز و سالم خود را وارد کنید (مثال: `1.2.3.4`).
                ۳. روی دکمه ذخیره کلیک کنید تا تنظیمات در دیتابیس کلادفلر ثبت شود.
                ۴. حالا به تب "اسکن" بروید و مانند گذشته دکمه "ترکیب با نودهای ابری" را بزنید. 
                ۵. کانفیگ‌های جدیدی که ساخته می‌شوند، همگی ترافیک شما را از آی‌پی جدید عبور خواهند داد و سایت‌ها لوکیشن جدید را تشخیص می‌دهند.
                
                *نکته:* نیازی به استقرار مجدد پنل پس از تغییر آی‌پی ثابت نیست و تغییرات به صورت آنی در کانفیگ‌های جدید اعمال می‌شوند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "bpb_fixed_ip",
            title = "۱۱. راهنمای آی‌پی ثابت در پنل بی‌پی‌بی (BPB)",
            icon = Icons.Default.SettingsEthernet,
            content = """
                **چگونه آی‌پی ثابت کانفیگ‌های پنل BPB را تنظیم کنیم؟**
                
                در پنل BPB برای قفل کردن لوکیشن روی یک کشور خاص و جلوگیری از شناسایی توسط سیستم‌های تحریم، باید آی‌پی ثابت را تنظیم کنید:
                
                **مراحل تنظیم آی‌پی ثابت:**
                ۱. ابتدا باید یک آی‌پی تمیز از نوع IPv4 داشته باشید. می‌توانید این آی‌پی را از کانال‌ها و سورس‌های مختلف دریافت کنید یا به راحتی از سیستم اسکنر خودِ اپلیکیشن (تب اسکن) یک آی‌پی سالم پیدا کنید.
                ۲. در تب "ابری"، روی آیکون چرخ‌دنده‌ی تنظیمات زیر پنل BPB کلیک کنید.
                ۳. آی‌پی سالمی که پیدا کردید را در فیلد "آی‌پی ثابت (Proxy IP)" قرار دهید.
                ۴. (اختیاری) می‌توانید در فیلد "آی‌پی کل پنل / فرگمنت" نیز همان آی‌پی یا آدرس تمیز دیگری تنظیم کنید تا اینگرس تغییر کند.
                ۵. تنظیمات را ذخیره کنید.
                ۶. به تب اسکن رفته و "ترکیب با نودهای ابری" را بزنید. کانفیگ‌های تولید شده، ترافیک شما را مستقیماً از آی‌پی ثابتی که تنظیم کردید عبور می‌دهند.
            """.trimIndent()
        ),
        HelpArticle(
            id = "routing_mode",
            title = "۱۲. راهنمای حالت مسیریابی (Split Tunneling)",
            icon = Icons.Default.AltRoute,
            content = """
                **چگونه فقط بعضی برنامه‌ها را از VPN عبور دهیم؟**
                
                اگر می‌خواهید فیلترشکن فقط روی برنامه‌های خاصی (مثل تلگرام یا اینستاگرام) کار کند و بقیه برنامه‌ها (مثل همراه بانک‌ها) از اینترنت عادی استفاده کنند، باید از قابلیت "حالت مسیریابی" استفاده کنید.
                
                **آموزش تنظیم حالت مسیریابی:**
                ۱. در تب اصلی نرم‌افزار (اتصال)، روی آیکون چرخ‌دنده (تنظیمات) کلیک کنید.
                ۲. وارد بخش "حالت مسیریابی" شوید.
                ۳. در اینجا سه حالت وجود دارد:
                   - **همه (ALL):** تمام ترافیک گوشی از VPN عبور می‌کند.
                   - **مجاز (ALLOW):** فقط برنامه‌هایی که تیک می‌زنید از VPN عبور می‌کنند (بقیه برنامه‌ها با اینترنت عادی کار می‌کنند). این حالت بهترین انتخاب برای جلوگیری از مسدود شدن همراه بانک‌هاست.
                   - **غیرمجاز (BYPASS):** تمام گوشی از VPN عبور می‌کند، به‌جز برنامه‌هایی که تیک می‌زنید (آن برنامه‌ها از VPN رد نمی‌شوند).
                ۴. بعد از انتخاب حالت مورد نظر (مثلاً مجاز)، صبر کنید تا لیست برنامه‌های گوشی لود شود و سپس تیک برنامه‌های مد نظرتان (مثل تلگرام، اینستاگرام، کروم و یوتیوب) را روشن کنید.
                ۵. به محض بازگشت، تنظیمات ذخیره می‌شود و در اتصال بعدی، مسیریابی جدید اعمال می‌گردد.
            """.trimIndent()
        ),
        HelpArticle(
            id = "mythological_names",
            title = "۱۳. داستان اسامی اساطیری کانفیگ‌ها چیست؟",
            icon = Icons.Default.AutoAwesome,
            content = """
                **چرا اسامی اساطیری روی گروه‌های من نمایش داده می‌شود؟**
                
                برای نظم‌دهی بهتر و زیبایی بصری، هر زمان که شما کانفیگ‌های جدیدی از پنل‌هایی مانند EDG یا BPB دریافت و اسکن می‌کنید، اپلیکیشن به صورت هوشمند و کاملاً تصادفی یکی از اسامی زیبای اساطیر، مشاهیر و مکان‌های باستانی ایران را به عنوان "نام گروه" برای آن کانفیگ‌ها انتخاب می‌کند. (البته برای پنل نهان، در صورتی که کانفیگ مختص کاربری خاص باشد، نام همان کاربر نمایش داده می‌شود).
                
                **آشنایی با اسامی اساطیری استفاده شده در اپلیکیشن:**
                
                - **رستم:** بزرگترین پهلوان شاهنامه، نماد قدرت و دفاع از ایران.
                - **سهراب:** پسر رستم، جوانی دلاور و جنگجو که به شکلی تراژیک کشته شد.
                - **کاوه:** آهنگر دلاوری که در برابر ظلم ضحاک قیام کرد و درفش کاویانی را برافراشت.
                - **آرش:** کماندار اسطوره‌ای که جانش را در کمان گذاشت تا مرز ایران را تعیین کند.
                - **سیاوش:** شاهزاده‌ای پاکدامان و بی‌گناه که نماد مظلومیت و وفاداری است.
                - **فریدون:** پادشاهی عادل که ضحاک را شکست داد و پادشاهی را به سه پسرش تقسیم کرد.
                - **اسفندیار:** شاهزاده‌ای رویین‌تن و پهلوان که تنها نقطه ضعفش چشمانش بود.
                - **جمشید:** از بزرگترین پادشاهان باستانی که جام جهان‌بین داشت و نوروز را پایه‌گذاری کرد.
                - **کیخسرو:** پادشاهی آرمانی و مقدس در شاهنامه که انتقام خون سیاوش را گرفت.
                - **گرشاسپ:** پهلوانی اژدهاکش و نیاکان رستم، مظهر شجاعت و دلاوری باستانی.
                - **بهرام:** ایزد پیروزی و جنگاوری در ایران باستان و همچنین نام پادشاهی قدرتمند (بهرام گور).
                - **زال:** پدر رستم، پهلوانی خردمند که با موهای سپید به دنیا آمد و سیمرغ او را بزرگ کرد.
                - **تهمینه:** همسر رستم و مادر سهراب، زنی زیبا و خردمند از سرزمین سمنگان.
                - **گردآفرید:** شیرزن و پهلوان‌بانوی ایرانی که دلاورانه با سهراب جنگید.
                - **رودابه:** مادر رستم و همسر زال، بانویی زیبارو که داستان عشق او از زیباترین داستان‌های شاهنامه است.
                - **گردیه:** زنی دلاور و خردمند، خواهر بهرام چوبین که در سوارکاری و نبرد همتا نداشت.
                - **آناهیتا:** ایزدبانوی آب‌ها، پاکی و باروری در ایران باستان که معابد بزرگی داشت.
                - **پاسارگاد:** پایتخت کوروش بزرگ و نماد شکوه معماری و امپراتوری هخامنشی.
                - **یوتاب:** خواهر آریوبرزن، سردار زن دلاوری که در کنار برادرش در برابر اسکندر جنگید.
                - **زربانو:** پهلوان‌بانوی ایرانی و دختر رستم که دوشادوش مردان در میدان نبرد می‌جنگید.
                - **آرتمیس:** نخستین دریاسالار زن جهان که فرماندهی نیروی دریایی خشایارشا را بر عهده داشت.
                - **آتوسا:** شهبانوی قدرتمند هخامنشی، دختر کوروش بزرگ و همسر داریوش.
                - **آرتادخت:** اقتصاددان و وزیر خزانه‌داری در دوره اشکانی که نماد تدبیر و خرد مالی بود.
                - **آذرآناهید:** از ملکه‌های قدرتمند و بانفوذ امپراتوری ساسانی.
                - **کاساندان:** همسر محبوب کوروش بزرگ که شریک و مشاور او در اداره امپراتوری بود.
            """.trimIndent()
        ),
        HelpArticle(
            id = "sni_engine",
            title = "۱۴. آموزش راه‌اندازی موتور ضد فیلتر SNI (اضطراری سوم)",
            icon = Icons.Default.RocketLaunch,
            content = """
                **چگونه با استفاده از موتور ضد فیلتر SNI یک اینترنت بدون فیلتر و پرسرعت داشته باشیم؟**
                
                اگر به دنبال دور زدن فیلترینگ با بالاترین سرعت و پایداری ممکن هستید، سیستم SNI یکی از بهترین روش‌هاست. برای استفاده از این قابلیت، مراحل ساده‌ی زیر را به ترتیب انجام دهید:
                
                ۱. **ورود به بخش اضطراری:** از منوی اصلی اپلیکیشن، گزینه «اضطراری سوم (SNI)� را انتخاب کنید.
                ۲. **اسکن و اتصال اولیه:** در بالای صفحه روی آیکون وای‌فای (اسکن/پینگ) کلیک کنید تا بهترین مسیرها بررسی شوند. سپس دکمه بزرگ «اتصال» را لمس کنید تا موتور ضد فیلتر در پس‌زمینه فعال شود.
                ۳. **بازگشت به تب اتصال:** از این صفحه خارج شده و به تب اصلی نرم‌افزار (تب اتصال) برگردید.
                ۴. **افزودن کانفیگ‌های SNI:** در بالای صفحه، روی دکمه «افزودن (+ )� کلیک کرده و گزینه «افزودن کانفیگ SNI� را انتخاب کنید.
                ۵. **تعیین تعداد:** در پنجره باز شده، تعداد کانفیگ‌هایی که می‌خواهید بسازید را انتخاب کنید (توصیه می‌کنیم روی حداکثر مقدار تنظیم کنید) و دکمه افزودن را بزنید.
                ۶. **تست و اتصال نهایی:** حالا به تب «نودها» بروید و **حتماً فقط از دکمه «تست دیلی (Delay)� استفاده کنید**. در این بخش فرقی نمی‌کند چه عدد دیلی به شما بدهد، این تست صرفاً برای تشخیص متصل بودن کانفیگ‌هاست. اما پیشنهاد می‌شود موردی که عدد کمتری دارد را انتخاب کنید (بی‌تاثیر نیست). پس از سبز شدن دیلی، به آن متصل شوید.
                
                ✅ **تبریک می‌گوییم! اینترنت شما فضایی شد 😍**
            """.trimIndent()
        ),
        HelpArticle(
            id = "sublink_generator",
            title = "۱۵. آموزش ساخت لینک ساب با کلادفلر",
            icon = Icons.Default.Link,
            content = """
                **چگونه برای دوستانمان لینک ساب (Subscription Link) اختصاصی بسازیم؟**
                
                با استفاده از قابلیت جدید «ساخت لینک ساب»، شما می‌توانید کانفیگ‌های خود را در قالب یک لینک دائمی به دوستان خود بدهید تا آن‌ها با وارد کردن آن در کلاینت‌های V2ray (مثل V2rayNG) همیشه به آخرین کانفیگ‌های شما دسترسی داشته باشند.
                
                **مراحل ساخت لینک ساب:**
                ۱. ابتدا از منوی کشویی سمت راست (همبرگری)، گزینه **«ساخت لینک ساب»** را انتخاب کنید.
                ۲. اگر حساب کلادفلر متصل نباشد، باید آن را در تب ابری متصل کنید.
                ۳. در اولین ورود، روی دکمه **«راه‌اندازی سیستم (Deploy)�** کلیک کنید. سیستم به صورت خودکار یک وورکر فوق‌سریع برای شما در کلادفلر می‌سازد و فضای ذخیره‌سازی ابری (KV) را تنظیم می‌کند.
                ۴. پس از آماده‌سازی، روی دکمه (+) کلیک کنید تا یک لینک جدید بسازید.
                ۵. در صفحه جدید، نام لینک، یک عبارت دلخواه کوتاه (Slug) برای آدرس، و گروهی از کانفیگ‌هایتان (مثلاً BPB یا VLESS) که می‌خواهید در این لینک قرار گیرند را انتخاب کنید.
                ۶. می‌توانید برای لینک **تاریخ انقضا (به روز)** تعیین کنید. اگر این فیلد را پر کنید، پس از گذشت آن زمان، لینک از کار می‌افتد و کاربر به جای کانفیگ، یک پیام «منقضی شده است» دریافت می‌کند.
                ۷. لینک را کپی کرده و به دوستانتان بدهید.
                
                **قابلیت آپدیت خودکار و آمارگیری:**
                - هر زمان که کانفیگ‌های آن گروه در اپلیکیشن شما تغییر کنند (مثلاً اسکن جدید بزنید)، لینک ساب به صورت **خودکار در پس‌زمینه** آپدیت می‌شود و دوستان شما با زدن دکمه آپدیت در V2rayNG، کانفیگ‌های جدید را دریافت می‌کنند.
                - با کلیک روی دکمه **«آمار (📊)�** در کنار هر لینک، می‌توانید ببینید تا الان چند بار آن لینک توسط دیگران آپدیت و دانلود شده است!
            """.trimIndent()
        ),
        HelpArticle(
            id = "dedicated_dns",
            title = "۱۶. DNS اختصاصی برای کاهش پینگ بازی",
            icon = Icons.Default.Dns,
            content = """
                **DNS اختصاصی چیست؟**
                یک سرور DNS کاملاً شخصی که روی حساب Cloudflare **خودتان** (نه حساب مشترک) مستقر می‌شود و فقط مال شماست. کاملاً رایگان و بدون نیاز به کارت بانکی جهانی.

                **چرا به حساب Cloudflare نیاز دارد؟**
                این DNS باید روی زیرساخت شخصی خودتان اجرا شود تا هیچ‌کس دیگری از آن استفاده نکند و ترافیک/محدودیت آن فقط برای شما باشد � دقیقاً مثل پنل‌های EDG یا نهان که قبلاً مستقر کرده‌اید. برای همین ابتدا باید یک حساب Cloudflare را در **تب کلاد** وصل کنید.

                **تفاوتش با DNS معمولی چیست؟**
                یک DNS معمولی (مثل 1.1.1.1) فقط آدرس سرور بازی را برمی‌گرداند. اما این DNS اختصاصی با تکنیکی به نام **ECS (EDNS Client Subnet)** به سرور بالادست می‌گوید «انگار از منطقه X پرسیده می‌شود» تا نزدیک‌ترین و کم‌تاخیرترین سرور بازیِ آن منطقه را تحویل بگیرد. این یعنی بسته‌های بازی مسیر کوتاه‌تری طی می‌کنند و پینگ پایین می‌آید.

                **حالت خودکار (Auto):**
                برنامه چند منطقه را همزمان امتحان می‌کند، به سرورهای برگشتی واقعاً پینگ می‌زند، بهترین منطقه را انتخاب می‌کند و برای **هر بازی به‌صورت جداگانه** به خاطر می‌سپارد.

                **حالت دستی (Manual):**
                خودتان یک منطقه ثابت (مثلاً امارات یا ترکیه) را همیشه انتخاب می‌کنید.

                **چرا امارات پیش‌فرض است؟**
                معمولاً نزدیک‌ترین و کم‌تاخیرترین منطقه به کاربران ایرانی برای اکثر سرورهای بازی است؛ اگر مسیر بهتری پیدا نشود، امارات انتخاب می‌شود.

                **نکته مهم:**
                این قابلیت روی **همه‌ی حالت‌های بوست** (مستقیم، تونل، وارپ و هیبرید) و **همه‌ی بازی‌های موجود در لیست** به‌صورت خودکار اعمال می‌شود و نیازی به تنظیم جداگانه برای هر بازی ندارید. کافی است یک‌بار روی دکمه «فعال‌سازی DNS اختصاصی» در تب گیمینگ بزنید.
            """.trimIndent()
        )
    )
}

fun getHelpArticlesEn(): List<HelpArticle> {
    return listOf(
        HelpArticle(
            id = "base",
            title = "1. Basic Tutorial (End-to-End Usage)",
            icon = Icons.Default.School,
            content = """
                **Step-by-step app usage tutorial:**
                
                1. **Cloudflare:** Go to cloudflare.com and sign up or log in.
                2. **Get API:** Go to My Profile > API Tokens. In the Global API Key section, click View and get the code.
                3. **Insert:** In the "Cloud" tab of the app, enter your Cloudflare email and Global API Key, then click Check/Login.
                4. **Deploy Panel:** After login, create a subdomain if needed. Then click "Deploy" to create your personal panel on Cloudflare.
                5. **Get Node:** After deployment finishes, click "Get Node" to save initial configs.
                6. **Scan IP:** Go to the "Scanner" tab. Enter an IP or subnet and click Start Scan to find clean IPs.
                7. **Combine:** After finding healthy IPs, click "Combine with Cloud Nodes" at the bottom.
                8. **Move to Nodes:** Go to the "Nodes" tab. Here you will see new configs created from the combination of clean IPs and your panel.
                9. **Ping and Speed:** In the Nodes tab, check the quality of all nodes.
                10. **Connect:** Select the best node, return to the main tab (Connect), and tap the connect button.
            """.trimIndent()
        ),
        HelpArticle(
            id = "fixed_ip",
            title = "2. Fixed IP System & Location Change (Nahan Exclusive)",
            icon = Icons.Default.LocationOn,
            content = """
                **How to lock your config's location to a specific country (e.g., US) forever?**
                
                In the new app update, the core Nahan project codes have been customized exclusively. This allows us to control the Egress ProxyIP as well as the Ingress address.
                
                **Case 1: For a specific user (e.g., user1) - Recommended**
                1. In the Cloud tab, Nahan Settings section, go to the users list.
                2. Click the Edit (✏️) icon next to the user's name.
                3. Put the target country's IP (e.g., US) in the new **"User Proxy IP"** field and save.
                *Result:* Both the initial config address and this user's egress tunnel will be locked to the US IP.
                
                **Case 2: For the entire panel (All users by default)**
                If you want all panel users to have a US fixed IP:
                1. In Nahan Network Settings, fill the **"Custom Relay"** field with a US IP. (This forces the worker's egress location to the US).
                2. Fill the **"Clean IPs/Proxy IPs"** field with the same IP. (This sets the raw config address you receive to the US).
                
                **⚠️ Very Important Step (Redeploy):**
                Since the core worker codes have changed, you must click **"Redeploy"** from the cloud panel menu (three dots) once to install the new worker codes on your Cloudflare.
            """.trimIndent()
        ),
        HelpArticle(
            id = "nahan_users",
            title = "3. Nahan User Management (Creating Configs for Friends)",
            icon = Icons.Default.GroupAdd,
            content = """
                **How to create unlimited configs for friends and family?**
                
                You can create separate users with limited or unlimited traffic through your cloud panel:
                
                1. In the "Cloud" tab, under your deployed NHN account, click the User Management button (multi-user icon).
                2. You will see a list of current users. Click the floating **Add (+)** button at the bottom.
                3. **Username:** Enter a desired name (e.g., Ali).
                4. **Traffic Limit:** You can set a specific volume (e.g., 50 GB) and expiry days. If set to 0, it will be unlimited.
                5. After saving, the user is created.
                6. To send the config to a friend, click the **Share** icon next to their name. The VLESS connection link will be copied, and you can send it via Telegram or WhatsApp.
            """.trimIndent()
        ),
        HelpArticle(
            id = "flag_logic",
            title = "4. How do Node Flags Work?",
            icon = Icons.Default.Flag,
            content = """
                **Why is the real server's country flag shown even when we provide a Cloudflare IP?**
                
                One of the smart features of this app is its real data path detection system.
                When you combine your configs with scanner or fragment clean IPs, the node's apparent address changes (e.g., to a German IP).
                
                However, your actual Cloudflare worker server might be in France or the US.
                
                **Technical Operation:**
                In the background, instead of looking at the apparent name, our app directly contacts the real server through which the traffic passes.
                Even for IPv6 addresses that are harder to resolve, the app decrypts the path via Cloudflare's secure DNS over HTTPS (DoH) connection and displays **the exact, real flag of the country that websites ultimately recognize you as**. This flag represents the physical egress path of the data, not just an apparent address.
            """.trimIndent()
        ),
        HelpArticle(
            id = "cloudflare_limits",
            title = "5. Cloudflare's 100K Daily Request Limit",
            icon = Icons.Default.Warning,
            content = """
                **What is Cloudflare's usage cap and when will you face it?**
                
                The free Cloudflare Workers service, where your panel is installed, has a limit of **100,000 requests per day** (per account).
                
                **Crucial Details:**
                - **What is a request?** In the web world, downloading a 1GB file is not one request! Every time a page is opened, hundreds of requests (for images, codes, etc.) are sent. Watching YouTube or downloading with a download manager can generate tens of thousands of requests per minute.
                - **What happens if we reach the cap?** Your panel and all connected nodes will instantly disconnect, and you will receive a 1045 error or no connection.
                - **When does it reset?** This limit resets to zero every day at 00:00 UTC, and configs will connect again.
                
                **Pro Solution:**
                If you and your friends consume a lot, the only free way is to create several different Gmail and Cloudflare accounts, get new APIs, and deploy separate panels (new cloud accounts) in the app's Cloud tab to distribute the load among them.
            """.trimIndent()
        ),
        HelpArticle(
            id = "sort_panel",
            title = "6. Powerful 'Sort by Panel' Feature",
            icon = Icons.Default.Sort,
            content = """
                **How to manage hundreds of cloud configs without confusion?**
                
                If you use multiple cloud accounts or different panels (like EDG, BPB, NHN, and even custom nodes) simultaneously, your node list in the "Nodes" tab gets cluttered very quickly.
                
                **Technical Explanation of the Sort Switch:**
                - At the top of the node list, next to the trash icon, there is a switch named "Sort by Panel".
                - **When off:** All nodes are listed sequentially.
                - **When on:** The app categorizes them based on the "generator engine" (Engine Type).
                - In this mode, a **Segmented Tab** appears at the top for each panel. Clicking each tab shows only the nodes for that panel.
                
                **Main Advantage:** This feature not only enhances aesthetics and faster access, but when you click "Ping All", only the configs of the currently viewed panel are pinged, preventing extra load on other panels.
            """.trimIndent()
        ),
        HelpArticle(
            id = "scanner_pro",
            title = "7. Comprehensive Guide to Two-Stage Smart Scanner",
            icon = Icons.Default.Radar,
            content = """
                **Why is this app's scanner different from other simple scanners?**
                
                The built-in scanner in this app uses a two-stage, Xray-core-based architecture to provide the most accurate results:
                
                **Phase 1: Raw Network (TCP/TLS Ping)**
                You enter one or more IP ranges (CIDR like 104.20.0.0/24). The app generates thousands of IPs from this range and pings them at the raw network level very quickly. This only isolates the alive and reachable IPs.
                
                **Phase 2: Blockage Test Under National Internet Load (Xray Testing)**
                Alive IPs from phase 1 are directly fed into the Android Xray core. The app builds a real tunnel in the background to see if these IPs are blocked by filtering. If a Cloudflare port for an IP is blocked on your network, it's identified and removed here.
                
                **Final Stage (Combination):**
                IPs surviving both phases are golden IPs! By clicking **"Combine with Cloud Nodes"** at the bottom, these IPs are mounted onto your panel's configs, readying dozens of unblocked, high-speed configs in the "Nodes" tab for connection.
            """.trimIndent()
        ),
        HelpArticle(
            id = "ip_archive",
            title = "8. IP Archive System & Retest",
            icon = Icons.Default.Storage,
            content = """
                **How to always have clean IPs without spending time on long scans?**
                
                The app is equipped with a professional system called **Scanner Archive**.
                
                **Auto Save:**
                Every time you successfully scan and finally hit "Combine", the app automatically saves those healthy IPs in the "Scanner Archive" section (in the Scanner tab).
                
                **Magical Retest Feature:**
                After a few days, clean IPs you found might get blocked and configs stop working. Instead of scanning long ranges from scratch:
                1. Go to the Scanner section and open an old archive group.
                2. Click the Refresh/Retest icon.
                3. The app only repings those few old IPs and instantly discards dead ones.
                4. By hitting combine, you create new configs in seconds using the still-healthy IPs!
            """.trimIndent()
        ),
        HelpArticle(
            id = "nahan_settings_guide",
            title = "9. Complete Guide to Nahan Panel Advanced Settings",
            icon = Icons.Default.Settings,
            content = """
                **Complete guide to Nahan panel settings fields**
                
                To access advanced settings, click the gear (settings) icon under the Nahan account in the "Cloud" tab. There are various fields here, each with a specific purpose:
                
                **1. Output Protocol:**
                Determines the type of config generated. You can set it to `VLESS` or `Trojan`. If set to `Both`, your worker will support both protocols simultaneously, giving you more varied configs.
                
                **2. Password (UUID/Password):**
                This long code is the beating heart and security identifier of your config. It's used both for web panel login and as the connection password in VLESS and Trojan configs. No need to change it unless you want your own custom password.
                
                **3. Custom Relay / ProxyIP:**
                One of the most important fields to fix the egress IP. If you put a clean server IP from a desired country (e.g., US) here, your traffic transfers there after Cloudflare. Result: websites detect your location as "US". If left empty, Cloudflare determines the location automatically.
                
                **4. Clean IPs/Proxy IPs:**
                This is the address that appears in the Address section of your configs (Ingress). This connects to Cloudflare. If changed, all configs you get from this panel will use this IP to bypass filtering. (You can also use a clean domain or fragment address).
                
                *Important Note:* After changing any setting and saving, make sure to click **"Redeploy"** from your cloud panel's three-dot menu once so the new settings fully apply on Cloudflare servers.
            """.trimIndent()
        ),
        HelpArticle(
            id = "edg_fixed_ip",
            title = "10. Fixed IP Guide for EDG Panel",
            icon = Icons.Default.SettingsEthernet,
            content = """
                **How to change the location of EDG panel configs?**
                
                To prevent your EDG panel traffic from exiting via a German Cloudflare IP and route it to your desired location (like Finland, US, Turkey), use the "Proxy IP" feature.
                
                **Setup Steps:**
                1. In the "Cloud" tab, click the gear icon under the EDG panel.
                2. In the "Proxy IP" field, enter your clean, healthy IP (e.g., `1.2.3.4`).
                3. Click Save to register settings in the Cloudflare database.
                4. Go to the "Scan" tab and hit "Combine with Cloud Nodes" as usual.
                5. The newly generated configs will all pass your traffic through the new IP, and websites will detect the new location.
                
                *Note:* No redeployment is needed after changing the fixed IP; changes apply instantly to new configs.
            """.trimIndent()
        ),
        HelpArticle(
            id = "bpb_fixed_ip",
            title = "11. Fixed IP Guide for BPB Panel",
            icon = Icons.Default.SettingsEthernet,
            content = """
                **How to set a fixed IP for BPB panel configs?**
                
                In the BPB panel, to lock the location to a specific country and avoid detection by sanction systems, you must set a fixed IP:
                
                **Setup Steps:**
                1. First, get a clean IPv4 address. You can get this from channels or easily find a healthy IP using the app's scanner system.
                2. In the "Cloud" tab, click the settings gear icon under the BPB panel.
                3. Put the healthy IP you found in the "Proxy IP" field.
                4. (Optional) You can also set the same IP or another clean address in the "Clean IPs" field to change the ingress.
                5. Save settings.
                6. Go to the scan tab and hit "Combine with Cloud Nodes". The generated configs will route your traffic directly through your configured fixed IP.
            """.trimIndent()
        ),
        HelpArticle(
            id = "routing_mode",
            title = "12. Split Tunneling Guide",
            icon = Icons.Default.AltRoute,
            content = """
                **How to route only specific apps through the VPN?**
                
                If you want the VPN to work only on certain apps (like Telegram or Instagram) while others (like banking apps) use normal internet, you should use the "Routing Mode" feature.
                
                **Setup Tutorial:**
                1. In the main app tab (Connect), click the gear icon (Settings).
                2. Enter the "Routing Mode" section.
                3. There are three modes here:
                   - **ALL:** All phone traffic passes through the VPN.
                   - **ALLOW:** Only checked apps pass through the VPN (others use normal internet). This is the best choice to prevent banking apps from blocking you.
                   - **BYPASS:** The entire phone passes through the VPN, EXCEPT the checked apps (they bypass the VPN).
                4. After choosing the desired mode (e.g., ALLOW), wait for the app list to load, then check your target apps (like Telegram, Instagram, Chrome, YouTube).
                5. Upon returning, settings are saved and the new routing is applied on the next connection.
            """.trimIndent()
        ),
        HelpArticle(
            id = "mythological_names",
            title = "13. The Story of Mythological Config Names",
            icon = Icons.Default.AutoAwesome,
            content = """
                **Why are mythological names displayed on my groups?**
                
                For better organization and visual appeal, whenever you receive and scan new configs from panels like EDG or BPB, the app intelligently and randomly selects one of the beautiful names of Iranian mythology, famous figures, or ancient places as the "Group Name". (For the Nahan panel, if the config is for a specific user, that user's name is displayed instead).
                
                **Introduction to mythological names used:**
                
                - **Rostam:** The greatest hero of the Shahnameh, symbol of strength and defense of Iran.
                - **Sohrab:** Rostam's son, a brave warrior youth tragically killed.
                - **Kaveh:** The brave blacksmith who rebelled against the tyrant Zahhak.
                - **Arash:** The legendary archer who put his life into his bow to set Iran's border.
                - **Siavash:** An innocent prince symbolizing purity and loyalty.
                - **Fereydoun:** A just king who defeated Zahhak.
                - **Esfandiar:** An invulnerable prince and hero whose only weakness was his eyes.
                - **Jamshid:** One of the greatest ancient kings who founded Nowruz.
                - **Kay Khosrow:** An ideal, sacred king in the Shahnameh.
                - **Garshasp:** A dragon-slaying hero and ancestor of Rostam.
                - **Bahram:** The god of victory and war, also a powerful king.
                - **Zal:** Rostam's father, a wise hero raised by the Simurgh.
                - **Tahmineh:** Rostam's wife and Sohrab's mother, a wise and beautiful woman.
                - **Gordafarid:** An Iranian heroine who bravely fought Sohrab.
                - **Rudabeh:** Rostam's mother and Zal's wife, a beautiful lady with a famous love story.
                - **Gordieh:** A brave, wise woman, sister of Bahram Chobin, peerless in battle.
                - **Anahita:** The goddess of water, purity, and fertility.
                - **Pasargadae:** The capital of Cyrus the Great, symbol of Achaemenid glory.
                - **Youtab:** Sister of Ariobarzanes, a brave female commander fighting Alexander.
                - **Zarbanu:** An Iranian heroine and Rostam's daughter who fought alongside men.
                - **Artemis:** The world's first female admiral who commanded Xerxes' navy.
                - **Atossa:** A powerful Achaemenid queen, daughter of Cyrus the Great.
                - **Artadokht:** An economist and treasury minister in the Parthian era.
                - **Azaranahid:** One of the powerful and influential queens of the Sassanid Empire.
                - **Cassandane:** The beloved wife of Cyrus the Great and his advisor.
            """.trimIndent()
        ),
        HelpArticle(
            id = "sni_engine",
            title = "14. How to Setup SNI Anti-Filter Engine (Emergency Level 3)",
            icon = Icons.Default.RocketLaunch,
            content = """
                **How to get high-speed, uncensored internet using the SNI Anti-Filter Engine?**
                
                If you are looking to bypass censorship with the highest possible speed and stability, the SNI system is one of the best methods. Follow these simple steps:
                
                1. **Enter Emergency Mode:** From the app's main drawer, select "Emergency Level 3 (SNI)".
                2. **Scan and Initial Connect:** Tap the WiFi (Scan/Ping) icon at the top to find the best routes. Then tap the large "Connect" button to start the anti-filter engine in the background.
                3. **Return to Connect Tab:** Leave this screen and go back to the app's main tab (Connect tab).
                4. **Add SNI Configs:** At the top of the screen, tap the "Add (+)" button and select "Add SNI Config".
                5. **Set Quantity:** In the dialog, select the number of configs you want to generate (we recommend setting it to maximum) and tap Add.
                6. **Test and Final Connection:** Go to the "Nodes" tab and **make sure to ONLY use the "Delay" test**. It doesn't matter what delay number you get; this test is purely to identify which configs are successfully connected. However, choosing a lower number can be slightly better. Once you get a green delay number, connect to it.
                
                ✅ **Congratulations! Your internet is now space-speed! 😍**
            """.trimIndent()
        ),
        HelpArticle(
            id = "sublink_generator",
            title = "15. How to Create Sub Links with Cloudflare",
            icon = Icons.Default.Link,
            content = """
                **How to create a dedicated Subscription Link for friends?**
                
                Using the new "Create Sub Link" feature, you can provide your configs as a permanent link to your friends. They can add it to their V2ray clients (like V2rayNG) and always have access to your latest configs.
                
                **Steps to create a sub link:**
                1. Open the right drawer (hamburger menu) and select **"Create Sub Link"**.
                2. If your Cloudflare account is not connected, you must connect it in the Cloud tab.
                3. On your first visit, tap **"Deploy System"**. The app will automatically build a high-speed worker and setup KV storage for you on Cloudflare.
                4. After deployment, tap the (+) button to create a new link.
                5. Enter a name for the link, a short custom slug for the address, and select a config group (e.g. BPB or VLESS).
                6. You can set an **Expiry date (in days)**. After this time, the link will stop working and users will receive an "Expired" message.
                7. Copy the link and share it with your friends.
                
                **Auto-Sync and Stats:**
                - Whenever the configs in that group change (e.g. you run a new scan), the sub link will be updated **automatically in the background**. Your friends just need to tap Update in V2rayNG to get the new configs.
                - By tapping the **"Stats (📊)"** button next to each link, you can see how many times that link has been updated and downloaded by others!
            """.trimIndent()
        ),
        HelpArticle(
            id = "dedicated_dns",
            title = "16. Dedicated DNS for Lower Game Ping",
            icon = Icons.Default.Dns,
            content = """
                **What is Dedicated DNS?**
                A fully private DNS resolver deployed on **your own** Cloudflare account (not a shared one), dedicated to you alone. Completely free, no international bank card required.

                **Why does it need a Cloudflare account?**
                It must run on your own infrastructure so no one else shares it and its traffic/quota is yours only � exactly like the EDG or Nahan panels you may have deployed before. So first connect a Cloudflare account in the **Cloud tab**.

                **How is it different from a normal DNS?**
                A normal DNS (like 1.1.1.1) just returns the game server address. This dedicated DNS uses a technique called **ECS (EDNS Client Subnet)** to tell the upstream resolver "answer as if I'm in region X," so it returns the closest, lowest-latency game server for that region. That means game packets travel a shorter path and ping drops.

                **Auto mode:**
                The app races several regions in parallel, actually pings the returned servers, picks the fastest region, and remembers it **separately for each game**.

                **Manual mode:**
                You pick one fixed region yourself (e.g. UAE or Turkey) and it's always used.

                **Why is UAE the default?**
                It's usually the closest, lowest-latency region to Iran-based players for most game servers; if no better route is found, UAE is chosen.

                **Important:**
                This applies automatically across **all boost modes** (Direct, Tunnel, WARP, Hybrid) and **every game in the list**, with no per-game setup. Just tap "Deploy Dedicated DNS" once in the Gaming tab.
            """.trimIndent()
        )
    )
}

data class FaqItem(val q: String, val a: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaqModal(onDismiss: () -> Unit, isFa: Boolean) {
    val primaryColor = Color(0xFF4285F4)
    val bgColor = Color(0xFF1E1E1E)
    val surfaceColor = Color(0xFF2D2E31)
    val textColor = Color(0xFFE8EAED)
    val mutedColor = Color(0xFF9AA0A6)

    val faqs = remember(isFa) { if (isFa) getFaqsFa() else getFaqsEn() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.85f)
                    .padding(top = com.mlmvpn.scanner.ui.LocalSystemTopPadding.current, bottom = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { },
                color = bgColor
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(primaryColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.LiveHelp, contentDescription = null, tint = primaryColor)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = if (isFa) "پرسش و پاسخ (FAQ)" else "FAQ",
                            color = textColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = mutedColor)
                        }
                    }

                    // Content
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(faqs) { faq ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceColor)
                                    .padding(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.HelpOutline, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(faq.q, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.CheckCircleOutline, contentDescription = null, tint = Color(0xFF81C995), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(faq.a, color = mutedColor, fontSize = 14.sp, lineHeight = 22.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun getFaqsFa(): List<FaqItem> = listOf(
    FaqItem("چرا نسخه جدید روی گوشی من نصب نمیشود و ارور \"Package conflicts\" میدهد؟", "این مشکل معمولاً به دلیل تداخل با نسخه قبلی است. ابتدا نسخه قدیمی را کاملاً پاک کنید و سپس نسخه جدید را نصب نمایید."),
    FaqItem("آیا برنامه روی اندرویدهای قدیمی یا نسخه‌های خیلی جدید (مثل اندروید ۱۴ و ۱۵) کار میکند؟", "برنامه برای اکثر نسخه‌های اندروید بهینه شده است، اما در صورت بروز مشکل در نسخه‌های خاص، حتماً از آخرین آپدیت استفاده کنید یا کش برنامه را پاک کنید."),
    FaqItem("آیا قابلیت نصب رو تلویزیون هوشمند اندروید وجود دارد؟", "بله از نسخه 1.0.5 برای تلویزیون های هوشمند اندروید بهینه سازی شده است."),
    FaqItem("چرا بعد از اتمام اسکن آیپی، هیچ اتفاقی نمیافتد و لیست آیپی‌ها یا کانفیگ‌های ترکیبی نمایش داده نمیشود؟", "این مورد میتواند به دلیل کیفیت پایین اینترنت در لحظه اسکن یا محدودیت‌های اپراتور باشد. پیشنهاد میشود با اپراتور دیگری تست کنید یا بخش اسکنر را مجدداً اجرا نمایید، اما مشکل اصلی اینجاست که قطعا شما از کانفیگ نامناسب استفاده کرده اید، باید حتما از کانفیگ هایی که از پنل های nhn-edge-bpb گرفته اید استفاده کنید. و حتما کانفیگ های vless با پورت 443 استفاده کنید."),
    FaqItem("چرا با وجود پینگ سبز، هیچ دانلودی ندارم و فقط آپلود انجام میشود؟", "پینگ سبز همیشه به معنای عبور دیتا نیست. ممکن است آیپی اسکن شده نیمه‌باز باشد یا پورت مورد نظر توسط اپراتور مسدود شده باشد. آیپی‌های دیگر را امتحان کنید."),
    FaqItem("چرا در بخش NHN وقتی کاربر با حجم و زمان مشخص میسازم، کانفیگ خروجی کار نمیکند؟", "در آپدیت جدید 1.0.5 این مشکل حل شده."),
    FaqItem("ارور \"Invalid API Token\" یا کد ۹۱۰۳ برای چیست؟", "این ارور مربوط به اشتباه بودن یا منقضی شدن توکن کلودفلر شماست. حتماً توکن را مطابق آموزش ویدیوها مجدداً ساخته و در برنامه وارد کنید."),
    FaqItem("آیا امکان استفاده از قابلیت \"Per-app setting\" (انتخاب برنامه‌های خاص برای عبور از VPN) وجود دارد؟", "این قابلیت در برنامه وجود دارد به تنظیمات برنامه، تنظیمات vpn مراجعه بفرمایید."),
    FaqItem("چرا در تلگرام یا اینستاگرام با وجود وصل بودن، لود شدن ویدیوها یا ربات‌ها کند است؟", "این موضوع معمولاً به دلیل تنظیمات DNS یا MTU است. در نسخه‌های جدید تلاش شده این مورد بهبود یابد، اما تعویض پروتکل (مثلاً از BPB به Edge یا برعکس) میتواند کمک کند."),

    FaqItem("دکمه‌ی اتصال سریع در پنل بالای گوشی چطور کار می‌کند؟", "می‌توانید دکمه‌ی �mlmvpn� را از پنل تنظیمات سریع گوشی اضافه کنید (از بالای صفحه به پایین بکشید ← ویرایش/افزودن دکمه‌ها). با یک لمس، VPN روشن یا خاموش می‌شود؛ هنگام روشن‌شدن، تأخیر (delay) چند سرور اخیر شما گرفته شده و به سریع‌ترین وصل می‌شوید. تعداد سرورهایی که تست می‌شوند (پیش‌فرض ۲۰) را می‌توانید در تنظیمات ← تنظیمات VPN تغییر دهید. نکته: بار اول باید یک‌بار از داخل برنامه VPN را وصل کنید تا مجوز اتصال داده شود."),

    // --- پنل Deno ---
    FaqItem("پنل Deno چیست و چه فرقی با کلودفلر دارد؟", "Deno Deploy یک سرویس میزبانی رایگان است که برخلاف بعضی سرویس‌ها نیازی به کارت اعتباری ندارد. با پنل Deno کاملاً خودکار یک سرور اختصاصی برای خودتان می‌سازید. سرور هم VLESS و هم Trojan را روی دو بستر WebSocket و xHTTP پشتیبانی می‌کند. توجه مهم: Deno برای مصرف سنگین و دائمی مناسب نیست (به بخش «چرا سرور Deno سریع معلق می‌شود» مراجعه کنید)؛ بهترین کاربرد آن به‌عنوان گزینه‌ی سبک یا پشتیبان است و برای مصرف اصلی از پنل‌های Cloudflare (BPB/Edge/NHN) استفاده کنید."),
    FaqItem("چطور توکن Deno بسازم؟ چرا توکن من نامعتبر است؟", "توکن باید حتماً از نوع Organization Access Token باشد و با ddo_ شروع شود. آن را از console.deno.com بخش Settings ← Organization tokens بسازید. توکن‌های شخصی که با ddp_ شروع می‌شوند کار نمی‌کنند. داخل پنل هم دکمه‌ای برای باز کردن مستقیم سایت گذاشته شده است. هشدار: این توکن به کل سازمان دسترسی دارد، آن را جایی منتشر نکنید."),
    FaqItem("در پنل Deno خطای «مسدود / ۴۰۳» می‌گیرم. چرا؟", "سرور api.deno.com در ایران مسدود است. برنامه هم خودش را از تونل مستثنی می‌کند، پس قبل از ساختن سرور یا دیدن آمار باید ابتدا یک کانفیگ سالم دیگر (BPB/Edge/NHN یا یک سرور Deno قبلی که کار می‌کند) را وصل کنید تا درخواست از تونل عبور کند، سپس دوباره امتحان کنید."),
    FaqItem("سرور Deno اول کار کند است یا وصل نمی‌شود.", "سرورهای رایگان Deno وقتی بی‌استفاده می‌مانند به خواب می‌روند (Cold Start) و سرورهای تازه‌ساخت هم چند دقیقه طول می‌کشد تا گواهی امنیتی‌شان آماده شود. به تست پینگ/تأخیر اکتفا نکنید؛ مستقیم Connect بزنید و چند ثانیه (برای سرور نو، چند دقیقه) صبر کنید."),
    FaqItem("چرا سرور Deno خیلی سریع معلق می‌شود (Memory Time / Usage Exceeded)؟", "مهم‌ترین محدودیت Deno این است: هزینه بر اساس «مدت‌زمان باز ماندن اتصال» حساب می‌شود، نه حجم دیتا. چون VPN اتصال را ساعت‌ها باز نگه می‌دارد (حتی وقتی بیکار است)، این سقف خیلی زود پر می‌شود؛ حتی با چند صد مگابایت. توجه: کانفیگ xHTTP هم این مشکل را حل نمی‌کند (طبق تست، حتی کمی بدتر است). راهکارها: ۱) برنامه به‌طور خودکار اتصال بیکار را می‌بندد تا مصرف کم شود، پس وقتی نیاز ندارید VPN را خاموش کنید. ۲) چند اکانت Deno بسازید و بین آن‌ها جابجا شوید؛ این محدودیت برای هر اکانت جداست و هر ماه ریست می‌شود. ۳) مصرف سنگین را روی پنل‌های Cloudflare انجام دهید."),
    FaqItem("تفاوت کانفیگ WS و xHTTP در پنل Deno چیست؟ کدام کم‌مصرف‌تر است؟", "هر دو از نظر محدودیت Memory Time تقریباً یکسان‌اند و هیچ‌کدام معجزه نمی‌کند (تست‌های ما نشان داد xHTTP کم‌مصرف‌تر نیست). تفاوت اصلی در سازگاری است: بعضی هسته‌ها/نسخه‌ها با WS بهتر کار می‌کنند و بعضی با xHTTP. هرکدام وصل شد و پایدارتر بود را استفاده کنید. دکمه‌ی «دریافت کانفیگ xHTTP� دو کانفیگ (یک VLESS و یک Trojan) روی پورت 443 می‌سازد."),
    FaqItem("چرا آمار مصرف اکانت Deno با تأخیر است یا کمی با داشبورد فرق دارد؟", "آمار از سیستم Analytics رسمی خود Deno خوانده می‌شود که بین همه‌ی سرورها درست جمع می‌زند، اما چند دقیقه تأخیر دارد و ممکن است کمی کمتر از عدد داشبورد باشد (آمار تقریبی است، نه مبنای دقیق صورتحساب). اگر صفر دیدید: مطمئن شوید یک کانفیگ سالم وصل است (چون api.deno.com در ایران بسته است) و دکمه Refresh را بزنید. آمار همه‌ی سرورهای آن اکانت را نشان می‌دهد."),
    FaqItem("آیا می‌توانم چند اکانت Deno اضافه کنم؟", "بله و شدیداً توصیه می‌شود. چون محدودیت مصرف برای هر اکانت جداست و ماهانه ریست می‌شود، با چند اکانت چند برابر ظرفیت می‌گیرید. در پنل چند اکانت با نام دلخواه اضافه کنید و بین آن‌ها جابجا شوید؛ آمار و سرورهای هر اکانت جداگانه است."),
    FaqItem("کانفیگ Deno چه محدودیت‌های فنی دارد؟", "۱) فقط پورت 443 (TLS) پشتیبانی می‌شود. ۲) فقط ترافیک TCP (برای مرور وب و اکثر برنامه‌ها کافی است)؛ UDP/QUIC هنوز نه. ۳) تکنیک «آی‌پی تمیز» روی Deno جواب نمی‌دهد چون Deno روی شبکه‌ی اختصاصی کوچک خودش است (نه یک CDN بزرگ)؛ فقط از آدرس دامنه‌ی مستقیم استفاده کنید.")
)

fun getFaqsEn(): List<FaqItem> = listOf(
    FaqItem("Why can't I install the new version, getting a \"Package conflicts\" error?", "This usually happens due to a conflict with the previous version. Please completely uninstall the old version first, then install the new one."),
    FaqItem("Does the app work on older Android versions or very new ones (like Android 14 and 15)?", "The app is optimized for most Android versions. If you encounter issues on specific versions, ensure you are using the latest update or try clearing the app cache."),
    FaqItem("Can it be installed on Android Smart TVs?", "Yes, since version 1.0.5, the app is fully optimized for Android Smart TVs."),
    FaqItem("Why does nothing happen after IP scanning finishes, and no IPs or mixed configs are shown?", "This could be due to poor internet quality during the scan or operator restrictions. We suggest testing with another operator or running the scanner again. However, the main issue is usually using inappropriate configs; you must strictly use configs generated from NHN, Edge, or BPB panels, specifically VLESS configs with port 443."),
    FaqItem("Why do I only have upload but no download despite having a green ping?", "A green ping doesn't always mean data is passing through. The scanned IP might be half-open or the port might be blocked by your operator. Try other IPs."),
    FaqItem("Why doesn't the output config work when I create a user with specific volume/time in the NHN panel?", "This issue has been resolved in the new 1.0.5 update."),
    FaqItem("What does the \"Invalid API Token\" or code 9103 error mean?", "This error means your Cloudflare token is either incorrect or expired. Please recreate the token following the video tutorials and enter it again in the app."),
    FaqItem("Is there a \"Per-app proxy\" feature to select specific apps to bypass the VPN?", "Yes, this feature is available. Please go to App Settings > VPN Settings."),
    FaqItem("Why is loading videos or bots slow in Telegram or Instagram even though I'm connected?", "This is usually due to DNS or MTU settings. We've tried to improve this in newer versions, but changing the protocol (e.g., from BPB to Edge or vice versa) can help."),

    FaqItem("How does the Quick Settings VPN tile work?", "You can add the 'mlmvpn' tile from your phone's Quick Settings panel (swipe down from the top → edit/add tiles). One tap toggles the VPN; when turning on, it delay-tests your most recent servers and connects to the fastest. You can change how many servers are tested (default 20) in Settings → VPN Settings. Note: the first time, connect once from inside the app to grant VPN permission."),

    // --- Deno Panel ---
    FaqItem("What is the Deno panel and how is it different from Cloudflare?", "Deno Deploy is a free hosting service that, unlike some providers, needs no credit card. The panel auto-creates your own dedicated server, which supports both VLESS and Trojan over WebSocket and xHTTP. Important: Deno is NOT suited for heavy, always-on use (see 'Why does my Deno server get suspended so fast'); it's best as a light/backup option � use the Cloudflare panels (BPB/Edge/NHN) for your main traffic."),
    FaqItem("How do I create a Deno token? Why is my token invalid?", "The token must be an Organization Access Token and start with ddo_. Create it at console.deno.com under Settings → Organization tokens. Personal tokens (ddp_) will NOT work. The panel has a button that opens the site directly. Warning: this token grants access to your whole organization � never share it."),
    FaqItem("I get a 'Forbidden / 403' error in the Deno panel. Why?", "api.deno.com is blocked in Iran, and the app excludes itself from the tunnel, so before creating a server or viewing stats you must first connect another working config (BPB/Edge/NHN or an existing working Deno server) so the request goes through the tunnel, then try again."),
    FaqItem("My Deno server is slow at first or won't connect.", "Free Deno servers sleep when idle (cold start), and a brand-new server takes a few minutes for its TLS certificate to be issued. Don't rely on the ping/latency test � connect directly and wait a few seconds (a few minutes for a new server)."),
    FaqItem("Why does my Deno server get suspended so fast (Memory Time / Usage Exceeded)?", "Deno's key limit: billing is based on how LONG a connection stays open, not on data volume. Since a VPN holds the connection open for hours (even while idle), this limit fills up very fast � even with a few hundred MB. Note: the xHTTP config does NOT fix this (our tests showed it's slightly worse). Solutions: 1) The app auto-closes idle connections to save usage, so turn the VPN off when not needed. 2) Create several Deno accounts and switch between them � the limit is per-account and resets monthly. 3) Do heavy traffic on the Cloudflare panels."),
    FaqItem("WS vs xHTTP Deno config � which uses less?", "Both are about equal for the Memory Time limit and neither is a magic fix (our tests showed xHTTP is not lighter). The real difference is compatibility: some cores/versions work better with WS, others with xHTTP. Use whichever connects and is more stable. The 'Get xHTTP config' button creates two configs (one VLESS, one Trojan) on port 443."),
    FaqItem("Why is my Deno usage delayed or slightly different from the dashboard?", "Usage is read from Deno's official Analytics API, which aggregates correctly across all servers but is delayed by a few minutes and may be a bit lower than the dashboard (it's approximate, not exact billing data). If you see zero, make sure a working config is connected (api.deno.com is blocked in Iran) and tap Refresh. It covers all servers on that account."),
    FaqItem("Can I add multiple Deno accounts?", "Yes, and it's strongly recommended. Because the usage limit is per-account and resets monthly, several accounts give you several times the capacity. Add accounts with custom names in the panel and switch between them; usage and servers are separate per account."),
    FaqItem("What are the technical limits of a Deno config?", "1) Only port 443 (TLS). 2) TCP traffic only (fine for web and most apps); UDP/QUIC not yet. 3) The 'clean IP' technique does NOT work on Deno because Deno runs on its own small dedicated network (not a big CDN) � just use the direct domain address.")
)
