<h1 align="center">
  <br>
  <img src="https://raw.githubusercontent.com/cmliu/edgetunnel/main/img.png" alt="MLMVPN Logo" width="120">
  <br>
  MLMVPN - Next-Gen Serverless VPN & Scanner
  <br>
</h1>

<h4 align="center">اپلیکیشن جامع استقرار خودکار و اسکنر فوق‌سریع برای عبور از سیستم‌های فیلترینگ پیچیده (DPI)</h4>

<p align="center">
  <a href="#features">ویژگی‌ها</a> •
  <a href="#architecture">معماری</a> •
  <a href="#download">دانلود</a> •
  <a href="#acknowledgments">تقدیر و تشکر</a>
</p>

---

## <a id="about"></a>📌 معرفی پروژه (About The Project)

پروژه **MLMVPN** یک کلاینت پیشرفته اندرویدی است که با ترکیب موتور قدرتمند **Xray-core** و تکنولوژی **Serverless** (مبتنی بر Cloudflare Workers & Pages) توسعه یافته است. 
این اپلیکیشن به کاربران اجازه می‌دهد بدون نیاز به دانش برنامه‌نویسی یا خرید سرور مجازی، پنل‌های پروکسی اختصاصی خود را مستقیماً از داخل اپلیکیشن روی اکانت Cloudflare مستقر (Deploy) کنند و با استفاده از یک اسکنر بومی و بسیار سریع، بهترین آی‌پی‌های تمیز (Clean IPs) را برای اتصال پیدا کنند.

## <a id="features"></a>✨ ویژگی‌های کلیدی (Key Features)

* 🚀 **استقرار چند موتوره (Multi-Engine Auto-Deployer):**
  * پشتیبانی از استقرار خودکار پنل‌های قدرتمند **BPB**، **Edgetunnel (EDG)** و **Nahan** (نهان).
  * ساخت Worker، آپلود کدهای جاوااسکریپت و دریافت کانفیگ‌های Base64 به صورت کاملاً اتوماتیک از طریق Cloudflare REST API.
* ⚡ **اسکنر بومی فوق‌سریع (Native UDP WARP Scanner):**
  * استفاده از `DatagramSocket` بومی (Native Java/Kotlin) به جای درگیر کردن هسته Xray برای تست هندشیک وایرگارد.
  * قابلیت اسکن موازی (Concurrent) هزاران آی‌پی در چند ثانیه بدون درگیری رم و پردازنده.
* 🛡 **بای‌پَس پیشرفته DPI مخابرات:**
  * پیاده‌سازی مکانیزم `WarpAntiDpi` برای تزریق **Reserved Bytes** تصادفی در پکت‌های Initiation وایرگارد.
  * جلوگیری از Silent Drop شدن پکت‌های UDP توسط فایروال‌های هوشمند.
* 🌐 **پشتیبانی کامل از IPv6:** قابلیت تولید و اسکن رنج‌های تمیز IPv6 برای عبور از محدودیت‌های شدید روی بستر IPv4.
* 🔄 **سیستم آپدیت درون‌برنامه‌ای (In-App Updater):** دریافت بی‌صدای نسخه‌های جدید از طریق ترکیب *GitHub Releases* و فایل کانفیگ JSON روی *Cloudflare Pages*.
* 🎨 **رابط کاربری مدرن (Jetpack Compose):** طراحی مینیمال، تاریک و یکپارچه با استانداردهای Material 3.

## <a id="architecture"></a>🛠 معماری شبکه (Tech Stack & Architecture)

این پروژه از به‌روزترین تکنولوژی‌های توسعه اندروید و شبکه بهره می‌برد:
* **Android UI:** `Kotlin`, `Jetpack Compose`, `MVVM`
* **Network & API:** `OkHttp`, `Retrofit` (برای ارتباط با Cloudflare API)
* **VPN Core:** `Xray-core` (v26.6.1) با قابلیت سفارشی‌سازی `outbounds`
* **Local Storage:** `Room Database`, `SharedPreferences`
* **Cryptography:** تولید و مدیریت UUIDv4 استاندارد به صورت محلی و تزریق `MAC1` معتبر برای کلید عمومی کلودفلر در اسکنر.

## <a id="scanner"></a>⚙️ مکانیزم کاری اسکنر (How the Scanner Works)

برای دور زدن باگ `gVisor` در هسته اندروید و جلوگیری از افت سرعت، اسکنر MLMVPN از هسته Xray استفاده نمی‌کند. در عوض:
1. یک پکت 148 بایتی استانداردِ `Initiation` وایرگارد (با MAC1 معتبر) تولید می‌شود.
2. بایت‌های 1 تا 3 این پکت (Reserved Bytes) برای دور زدن DPI به اعداد تصادفی تغییر می‌کنند.
3. پکت‌ها به صورت موازی (Batch Testing) به سمت رنج‌های IPv4 و IPv6 شلیک می‌شوند.
4. در صورت دریافت پکت `Cookie Reply` یا `Response`، آی‌پی به عنوان گره (Node) سالم شناسایی شده و پینگ واقعی آن محاسبه می‌شود.

## <a id="download"></a>📲 دانلود و نصب (Download & Installation)

شما می‌توانید همیشه آخرین نسخه پایدار و امن این اپلیکیشن را از بخش **Releases** در مخزن رسمی ما دانلود کنید:

🔗 **[دانلود آخرین نسخه اندروید MLMVPN](https://github.com/mlmvpn/mlmvpn_android/releases)**

*(کاربران فعلی می‌توانند از طریق سیستم آپدیت درون‌برنامه‌ای، نسخه‌های جدید را به صورت خودکار دریافت کنند).*

## <a id="contributing"></a>🤝 مشارکت در توسعه (Contributing)

مشارکت شما باعث پیشرفت و قدرتمندتر شدن این ابزار ضدسانسور می‌شود! 
1. پروژه را Fork کنید.
2. یک Branch جدید بسازید (`git checkout -b feature/AmazingFeature`).
3. تغییرات خود را Commit کنید (`git commit -m 'افزودن قابلیت جدید'`).
4. تغییرات را Push کنید (`git push origin feature/AmazingFeature`).
5. یک Pull Request ثبت کنید.

## <a id="acknowledgments"></a>🙏 تقدیر و تشکر (Acknowledgments)

هسته اصلی ارتباطی و پنل‌های استقرار یافته در این اپلیکیشن، بر پایه تلاش‌های بی‌دریغ توسعه‌دهندگان جامعه متن‌باز بنا شده است. از پروژه‌های شاهکار زیر که الهام‌بخش و تامین‌کننده زیرساخت Serverless ما بوده‌اند، صمیمانه تشکر می‌کنیم:

* 🥇 **[bia-pain-bache/BPB-Worker-Panel](https://github.com/bia-pain-bache/BPB-Worker-Panel)** - برای توسعه پنل قدرتمند و بی‌نظیر BPB.
* 🥇 **[cmliu/edgetunnel](https://github.com/cmliu/edgetunnel)** - برای طراحی زیرساخت منعطف و چندپروتکله ادج‌تانل.
* 🥇 **[itsyebekhe/nahan](https://github.com/itsyebekhe/nahan)** - برای توسعه پنل سبک، سریع و استاندارد نهان.

## ⚠️ سلب مسئولیت (Disclaimer)

این پروژه منحصراً برای اهداف آموزشی، پژوهشی و دور زدن محدودیت‌های ناعادلانه دسترسی به اطلاعات توسعه یافته است. مسئولیت استفاده از این ابزار بر عهده کاربر نهایی می‌باشد.
