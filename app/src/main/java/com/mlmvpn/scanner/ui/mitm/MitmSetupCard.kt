package com.mlmvpn.scanner.ui.mitm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mlmvpn.scanner.mitm.MitmCertManager
import com.mlmvpn.scanner.mitm.MitmProfile
import com.mlmvpn.scanner.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole setup flow for the MITM domain-fronting profile, as a single card that lives at the
 * top of its own folder in the connection tab. Deliberately not a separate screen: the user
 * never has to leave the folder, and once the two steps are green the config is sitting right
 * below the card ready to be tapped.
 *
 * Three states, one primary button each:
 *   1. nothing yet          -> "شروع راه‌اندازی"  (mints the certificate + registers the config)
 *   2. certificate untrusted -> "نصب گواهی"       (opens Android's own installer dialog)
 *   3. all green            -> hint to pick the config below
 *
 * Trust state is re-read on every ON_RESUME, so returning from the system settings screen
 * flips the step to green without the user having to refresh anything.
 */
@Composable
fun MitmSetupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var certExists by remember { mutableStateOf(false) }
    var certTrusted by remember { mutableStateOf(false) }
    var configReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var guideExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** Non-null once the certificate has been copied where Settings' file picker can reach it. */
    var exportedName by remember { mutableStateOf<String?>(null) }

    /** Bumped on every ON_RESUME to re-trigger the status read. */
    var resumeTick by remember { mutableStateOf(0) }
    /**
     * Accordion state. Starts as null and is decided once the first status read lands: open while
     * there is still setup to do, collapsed when everything is already green so a returning user
     * gets a slim header instead of a wall of finished steps. After that it is the user's to
     * toggle -- nothing reopens or recloses it behind their back.
     */
    var cardOpenState by remember { mutableStateOf<Boolean?>(null) }
    val cardOpenValue = cardOpenState ?: true

    suspend fun refresh() {
        val hasCert = MitmCertManager.exists(context)
        // The keystore lookup touches the filesystem, so keep it off the main thread.
        val trusted = hasCert && withContext(Dispatchers.IO) { MitmCertManager.isTrusted(context) }
        certExists = hasCert
        certTrusted = trusted
        configReady = MitmProfile.isInstalled(context)
        if (cardOpenState == null) cardOpenState = !(hasCert && trusted && configReady)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeTick) { refresh() }

    // While the certificate exists but is not trusted yet, keep watching. Coming back from the
    // Settings screen already triggers ON_RESUME, but on some OEM skins the install completes
    // without the app being backgrounded at all, and the user should not have to restart the app
    // (or even tap anything) for the step to tick over.
    LaunchedEffect(certExists, certTrusted) {
        while (certExists && !certTrusted) {
            kotlinx.coroutines.delay(1500)
            refresh()
        }
    }

    val allDone = certExists && certTrusted && configReady
    val accent = if (allDone) GreenOk else Primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.13f), SurfaceDark.copy(alpha = 0.55f))
                )
            )
            .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        // ---- header (also the accordion toggle) ------------------------------------
        val cardArrow by animateFloatAsState(if (cardOpenValue) 180f else 0f, tween(200), label = "cardArrow")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { cardOpenState = !cardOpenValue },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (allDone) Icons.Default.VerifiedUser else Icons.Default.Shield,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("دامین‌فرانتینگ — بدون سرور", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (allDone && !cardOpenValue) "آماده است — کانفیگ پایین را انتخاب کنید"
                    else "یوتیوب، اینستاگرام، واتس‌اپ، فیسبوک و ردیت بدون هیچ سرور و ورکری",
                    color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp
                )
            }
            if (allDone) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(GreenOk.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("آماده", color = GreenOk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ExpandMore, contentDescription = if (cardOpenValue) "بستن" else "باز کردن",
                tint = TextMuted, modifier = Modifier.size(20.dp).rotate(cardArrow)
            )
        }

        AnimatedVisibility(visible = cardOpenValue, enter = expandVertically(), exit = shrinkVertically()) {
          Column {
        Spacer(Modifier.height(14.dp))

        // ---- steps -----------------------------------------------------------------
        StepRow(
            index = 1,
            title = "ساخت گواهی اختصاصی این گوشی",
            subtitle = "برنامه خودش می‌سازد — چیزی از اینترنت دانلود نمی‌شود",
            done = certExists,
            active = !certExists
        )
        StepRow(
            index = 2,
            title = "نصب گواهی در اندروید",
            subtitle = if (MitmCertManager.canUseDirectInstaller)
                "تنها مرحله‌ای که اندروید اجازه نمی‌دهد برنامه خودش انجام دهد"
            else
                "اندروید ۱۱ به بالا: فایل را ذخیره می‌کنیم و شما از تنظیمات نصبش می‌کنید",
            done = certTrusted,
            active = certExists && !certTrusted
        )
        StepRow(
            index = 3,
            title = "افزودن کانفیگ به همین پوشه",
            subtitle = "بعد از این، کانفیگ پایین همین صفحه نمایش داده می‌شود",
            done = configReady,
            active = certExists && certTrusted && !configReady,
            isLast = true
        )

        Spacer(Modifier.height(14.dp))

        // ---- primary action --------------------------------------------------------
        when {
            !certExists || !configReady -> {
                ActionButton(
                    text = if (busy) "در حال آماده‌سازی..." else "شروع راه‌اندازی",
                    icon = Icons.Default.Rocket,
                    enabled = !busy,
                    accent = Primary
                ) {
                    busy = true
                    error = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { MitmProfile.setUp(context) }
                        busy = false
                        if (!ok) error = "ساخت گواهی یا خواندن کانفیگ ناموفق بود. یک‌بار دیگر تلاش کنید."
                        refresh()
                    }
                }
            }
            !certTrusted && MitmCertManager.canUseDirectInstaller -> {
                // Android 10 and below: the system installer still accepts a certificate handed
                // to it by an app, so this is a single confirmation for the user.
                ActionButton(
                    text = "نصب گواهی",
                    icon = Icons.Default.Shield,
                    enabled = true,
                    accent = YellowWarn
                ) {
                    error = null
                    val intent = MitmCertManager.installIntent(context)
                    val started = intent != null && runCatching { context.startActivity(intent) }.isSuccess
                    if (!started) {
                        runCatching { context.startActivity(MitmCertManager.securitySettingsIntent()) }
                        error = "پنجره نصب باز نشد. از تنظیمات > امنیت > نصب گواهی، فایل را دستی نصب کنید."
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "در پنجره‌ای که باز می‌شود، اگر اسم خواست همین نام پیشنهادی را تأیید کنید. اگر پرسید گواهی برای چیست، «CA Certificate» را انتخاب کنید.",
                    color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
            !certTrusted -> {
                // Android 11+ refuses a certificate passed in by an app ("this certificate from
                // null must be installed in Settings"), so the only route left is the user
                // picking the file themselves. We put the file somewhere the picker can see it
                // and spell out the taps -- there is no public deep link to that screen.
                ActionButton(
                    text = if (exportedName != null) "باز کردن تنظیمات اندروید" else "ذخیره گواهی در پوشه دانلود",
                    icon = if (exportedName != null) Icons.Default.Shield else Icons.Default.Download,
                    enabled = !busy,
                    accent = YellowWarn
                ) {
                    error = null
                    if (exportedName == null) {
                        busy = true
                        scope.launch {
                            val name = withContext(Dispatchers.IO) { MitmCertManager.exportToDownloads(context) }
                            busy = false
                            if (name == null) error = "ذخیره فایل گواهی ناموفق بود."
                            else exportedName = name
                        }
                    } else {
                        val ok = runCatching { context.startActivity(MitmCertManager.securitySettingsIntent()) }.isSuccess
                        if (!ok) runCatching { context.startActivity(MitmCertManager.allSettingsIntent()) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (exportedName == null) {
                    Text(
                        "اندروید ۱۱ به بالا اجازه نمی‌دهد برنامه‌ها گواهی را خودشان نصب کنند. اول فایل را ذخیره می‌کنیم، بعد از تنظیمات نصبش می‌کنید.",
                        color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDark.copy(alpha = 0.7f))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, tint = GreenOk, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "فایل ذخیره شد: پوشه Download → $exportedName",
                                color = GreenOk, fontSize = 11.sp, fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("حالا در تنظیمات اندروید:", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        listOf(
                            "امنیت (Security) → تنظیمات بیشتر امنیت",
                            "رمزگذاری و اطلاعات ورود (Encryption & credentials)",
                            "نصب گواهی (Install a certificate)",
                            "گواهی CA را انتخاب کنید و «Install anyway» را بزنید",
                            "از پوشه Download فایل «$exportedName» را انتخاب کنید"
                        ).forEachIndexed { i, line ->
                            Row(modifier = Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.Top) {
                                Text("${i + 1}.", color = YellowWarn, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(5.dp))
                                Text(line, color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "اگر جای این گزینه‌ها در گوشی شما کمی متفاوت بود، در جستجوی تنظیمات عبارت «certificate» را بزنید. بعد از نصب، به برنامه برگردید تا خودش تشخیص بدهد.",
                            color = TextDim, fontSize = 10.sp, lineHeight = 14.sp
                        )
                    }
                }
            }
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GreenOk.copy(alpha = 0.12f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Check, null, tint = GreenOk, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "همه چیز آماده است. کانفیگ پایین را انتخاب و اتصال را بزنید.",
                        color = GreenOk, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.ErrorOutline, null, tint = RedError, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(it, color = RedError, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }

        // ---- the one limitation the user must know before connecting ---------------
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(YellowWarn.copy(alpha = 0.09f))
                .border(1.dp, YellowWarn.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.Language, null, tint = YellowWarn, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("فقط در مرورگر کار می‌کند", color = YellowWarn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(
                    "سایت‌ها را در کروم (یا هر مرورگر مشابه) باز کنید. اپلیکیشن یوتیوب و اینستاگرام با این روش باز نمی‌شوند — این محدودیت خود اندروید است. برای فایرفاکس باید گزینه استفاده از گواهی‌های شخصی را هم روشن کنید.",
                    color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }

        // ---- per-brand install guide -----------------------------------------------
        // Every OEM skin puts the CA-install entry somewhere different and renames it, so a
        // single generic path would be wrong for most users. The search keyword at the end is the
        // reliable escape hatch on any device.
        Spacer(Modifier.height(10.dp))
        val guideArrow by animateFloatAsState(if (guideExpanded) 180f else 0f, tween(200), label = "guideArrow")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { guideExpanded = !guideExpanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.HelpOutline, null, tint = Primary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "راهنمای نصب گواهی برای برندهای مختلف",
                color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.ExpandMore, null, tint = Primary,
                modifier = Modifier.size(18.dp).rotate(guideArrow)
            )
        }

        AnimatedVisibility(visible = guideExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark.copy(alpha = 0.7f))
                    .padding(12.dp)
            ) {
                Text(
                    "سریع‌ترین راه در همه‌ی گوشی‌ها: در نوار جستجوی خودِ تنظیمات یکی از این‌ها را بنویسید:",
                    color = TextPrimary, fontSize = 11.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("certificate", "گواهی", "credentials").forEach { kw ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Primary.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(kw, color = Primary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "بعد گزینه‌ای شبیه «نصب گواهی» یا «Install a certificate» را بزنید و «گواهی CA / CA certificate» را انتخاب کنید. اگر اخطار داد، «Install anyway» را بزنید. در آخر فایل «${MitmCertManager.EXPORT_FILE_NAME}» را از پوشه Download انتخاب کنید.",
                    color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp
                )

                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderDark))
                Spacer(Modifier.height(10.dp))
                Text("مسیر دستی بر اساس برند:", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                BrandPath(
                    "سامسونگ (One UI)",
                    "تنظیمات ← بیومتریک و امنیت (Biometrics and security) ← تنظیمات امنیتی دیگر (Other security settings) ← نصب از حافظه دستگاه (Install from device storage) ← گواهی CA"
                )
                BrandPath(
                    "شیائومی / ردمی / پوکو (MIUI و HyperOS)",
                    "تنظیمات ← رمزها و امنیت (Passwords & security) ← امنیت سیستم / حریم خصوصی ← رمزگذاری و اطلاعات ورود (Encryption & credentials) ← نصب گواهی از حافظه"
                )
                BrandPath(
                    "پیکسل و اندروید خام (۱۲ و بالاتر)",
                    "تنظیمات ← امنیت و حریم خصوصی (Security & privacy) ← تنظیمات بیشتر امنیت (More security settings) ← رمزگذاری و اطلاعات ورود ← نصب گواهی ← گواهی CA"
                )
                BrandPath(
                    "پیکسل و اندروید خام (۱۱)",
                    "تنظیمات ← امنیت (Security) ← رمزگذاری و اطلاعات ورود (Encryption & credentials) ← نصب گواهی ← گواهی CA"
                )
                BrandPath(
                    "هواوی و آنر (EMUI / MagicOS)",
                    "تنظیمات ← امنیت (Security) ← تنظیمات بیشتر (More settings) ← رمزگذاری و اطلاعات ورود ← نصب گواهی از حافظه"
                )
                BrandPath(
                    "آنر / اوپو / ریلمی / وان‌پلاس (ColorOS و OxygenOS)",
                    "تنظیمات ← رمز و امنیت (Password & security) ← امنیت سیستم (System security) ← رمزگذاری و اطلاعات ورود ← نصب از حافظه"
                )
                BrandPath(
                    "ویوو (Funtouch OS / OriginOS)",
                    "تنظیمات ← تنظیمات بیشتر (More settings) ← امنیت و حریم خصوصی ← رمزگذاری و اطلاعات ورود ← نصب گواهی"
                )
                BrandPath(
                    "موتورولا، نوکیا، ایسوس، سونی",
                    "همان مسیر اندروید خام است — تنظیمات ← امنیت ← رمزگذاری و اطلاعات ورود ← نصب گواهی",
                    isLast = true
                )

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = YellowWarn, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "اگر گوشی شما رمز یا الگوی قفل صفحه ندارد، اندروید قبل از نصب گواهی از شما می‌خواهد یکی تنظیم کنید — این شرط خود اندروید است.",
                        color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Language, null, tint = TextDim, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "فایرفاکس گواهی‌های نصب‌شده توسط کاربر را به‌صورت پیش‌فرض قبول نمی‌کند. اگر با فایرفاکس کار می‌کنید: About Firefox ← پنج بار روی لوگو بزنید ← Settings ← Secret Settings ← گزینه «Use third party CA certificates» را روشن کنید.",
                        color = TextDim, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
            }
        }

        // ---- details ---------------------------------------------------------------
        Spacer(Modifier.height(10.dp))
        val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "arrow")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("جزئیات و مدیریت گواهی", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.ExpandMore, null, tint = TextMuted,
                modifier = Modifier.size(18.dp).rotate(arrow)
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column {
                Spacer(Modifier.height(6.dp))
                InfoLine("نام گواهی", MitmCertManager.commonName(context) ?: "—")
                val fp = remember(certExists) { MitmCertManager.fingerprint(context) }
                if (fp != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("اثر انگشت (SHA-256)", color = TextDim, fontSize = 10.sp)
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fp, color = TextMuted, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { clipboard.setText(AnnotatedString(fp)) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentCopy, "copy", tint = TextMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = TextDim, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "این گواهی مخصوص همین گوشی است و هیچ‌جا ارسال نمی‌شود. گواهی کسی دیگر را هرگز نصب نکنید و گواهی خودتان را هم به کسی ندهید.",
                        color = TextDim, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            error = null
                            exportedName = null // the exported copy is now the wrong certificate
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    MitmCertManager.deleteLocal(context)
                                    MitmProfile.setUp(context)
                                }
                                busy = false
                                if (!ok) error = "ساخت گواهی جدید ناموفق بود."
                                refresh()
                            }
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ساخت گواهی جدید", color = TextMuted, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(MitmCertManager.securitySettingsIntent()) } },
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("تنظیمات امنیت", color = TextMuted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "اگر گواهی جدید بسازید، باید یک‌بار دیگر آن را نصب کنید و گواهی قبلی را از تنظیمات اندروید پاک کنید. مسیر حذف گواهی برای هر برند در «مرکز آموزش» آموزش شماره ۱۷ نوشته شده.",
                    color = TextDim, fontSize = 10.sp, lineHeight = 14.sp
                )
            }
        }
          } // accordion body
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    active: Boolean,
    isLast: Boolean = false
) {
    val color = when {
        done -> GreenOk
        active -> Primary
        else -> TextDim
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (done) GreenOk.copy(alpha = 0.20f) else Color.Transparent)
                    .border(1.dp, color.copy(alpha = if (done) 0f else 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(Icons.Default.Check, null, tint = GreenOk, modifier = Modifier.size(13.dp))
                } else {
                    Text("$index", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(if (done) GreenOk.copy(alpha = 0.35f) else BorderDark)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 6.dp)) {
            Text(
                title,
                color = if (done || active) TextPrimary else TextMuted,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextDim, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = BgDark,
            disabledContainerColor = accent.copy(alpha = 0.35f),
            disabledContentColor = BgDark.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth().height(46.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BrandPath(brand: String, path: String, isLast: Boolean = false) {
    Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 9.dp)) {
        Text(brand, color = TextPrimary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(path, color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, color = TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Start)
    }
}
