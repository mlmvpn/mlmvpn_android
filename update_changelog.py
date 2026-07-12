import sys

file_path = 'g:/ip scanner/android/app/src/main/java/com/mlmvpn/scanner/ui/AboutScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Replace data class
content = content.replace(
    '''data class ChangelogItem(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)''',
    '''data class ChangelogItem(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

data class ChangelogVersion(
    val versionTitle: String,
    val items: List<ChangelogItem>
)'''
)

start_idx = content.find('val items = remember(isFa) {')
end_idx = content.find('    Dialog(', start_idx)

new_block = '''val versions = remember(isFa) {
        if (isFa) {
            listOf(
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

'''

content = content[:start_idx] + new_block + content[end_idx:]

old_lazy = '''items(items) { item ->
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
                    }'''
                    
new_lazy = '''versions.forEach { version ->
                        item {
                            Text(
                                text = version.versionTitle,
                                color = primaryColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            Divider(color = Color(0xFF333333))
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
                    }'''

content = content.replace(old_lazy, new_lazy)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print('Success')
