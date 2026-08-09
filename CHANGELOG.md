# Changelog

All notable changes to the MLM VPN Android app are documented here. Dates are in `YYYY-MM-DD`. This file follows the spirit of [Keep a Changelog](https://keepachangelog.com/); version numbers match `versionName` in [`app/build.gradle`](app/build.gradle).

فارسی این فایل در ادامه (پایین همین صفحه) آمده است.

## [1.2.1] — 2026-08-09

### Added
- Public GPLv3 source release on GitHub, with a full bilingual README, CONTRIBUTING guide, and Git LFS for native binaries/geo data.
- **Connection tab**: new "Get free configs" button above the `+` add-node button. A multi-step wizard aggregates configs from a few public GitHub-hosted subscription sources, shows how many are available, lets the user pick a target count, then tests them in two stages — a fast TCP reachability pre-check, then a real Xray-proxied connection test (the same primitive the app's own "Real Delay" test uses) — keeping only genuinely working configs. A "this many is enough" button stops the search early and keeps whatever's been found. Results are renamed to random `mlmvpnXXXX` IDs (baked into the URI's own remark so it survives sharing) and land in a "Free Configs" folder under the Manual tab; configs the user already has (matched by real server identity, not name) are skipped and reported instead of duplicated.
- **Connection tab**: real per-config country flags. During the "Real Delay" test, a config that connects and has no cached flag yet gets its exit country queried once through that same live tunnel (via `api.ip.sb/geoip`) and cached on the node — no full VPN connection needed just to see a config's real flag, and it's a one-time lookup afterward served from storage.

### Changed
- **Game tab**: removed the two hardcoded legacy UAE WireGuard servers ("Server 1" — a dead 1-hour-trial WireGuard endpoint, "Server 2" — already-retired dead code). Full-tunnel game boosting now goes through the Aether engine, which self-selects a healthy endpoint across multiple protocols instead of a single fixed server.
- **Cloud panel**: bundled BPB Worker Panel upgraded from v4.2.2 to **v5.1.1**. This upstream release changed the deployment model entirely: per-account config (UUID, Trojan password, secure path, account email/token) is now baked directly into the worker's own JS source at upload time (`EMBEDED_SETTINGS`), replacing the old Cloudflare secret-binding approach, which v5 actively rejects. Deploy, panel login (now JSON `{username,password}` instead of a plain-text password, matched against the account email), subscription fetching, and settings save were all rewritten for the new route scheme (routes are now prefixed with a `securePath` obfuscation segment). The in-app settings form was also updated to match v5's schema (a single `protocols` field instead of separate VLESS/Trojan toggles; Proxy IP/NAT64 selection moved to deploy-time only).
- **Cloud panel**: bundled EDG worker (cmliu/edgetunnel) updated to the latest upstream build, carrying several months of upstream transport optimizations (GrainTCP-based up/downlink packet coalescing, TCP concurrent-dial connection racing, DoH resolution caching, China Mobile single-path fallback) that should make EDG configs noticeably faster and more stable to connect. Deploy-time env vars (`UUID`, `PROXYIP`, `ADMIN`, `KV`) are unchanged, so this is a drop-in worker swap; the deploy's `compatibility_date` was bumped to match the new build's requirements.
- **Cloud panel**: bundled Nahan (نهان) worker upgraded from v2.9.4 to **v3.0.0** — upstream VLESS proxy chaining, a v2rayN JSON subscription format, a redesigned add/edit-user form with a modern protocol toggle, and various dashboard RTL/dark-mode fixes. The D1 binding name (`IOT_DB`) and `/api/auth` + `/api/sync` route contract are unchanged, so this is a drop-in worker swap.

### Removed
- **Deno Panel**: removed entirely (drawer entry, tab, FAQ section, and the underlying `engines/deno` deploy/stats code). `api.deno.com` has been blocked in Iran for a while now, and Deno's connection-duration-based billing model made free servers unsuitable for VPN use anyway (constant "Memory Time / Usage Exceeded" suspensions) -- the Cloudflare panels (BPB/EDG/Nahan) cover the same need without either problem.

### Fixed
- **Cloud panel**: adding a Cloudflare account with a Global API Key and no email showed a vague "Invalid API Token" error. It now explains clearly that Global API Keys require an email, with a hint shown under the field.
- **Cloud panel**: MLM worker deploys failed intermittently with "Failed to upload MLM worker". Root cause: the worker's `compatibility_date` was computed from the device's local clock instead of a fixed date; for timezones ahead of UTC (e.g. Iran, UTC+3:30), roughly between midnight and 03:30 local time the computed date landed a day ahead of Cloudflare's UTC clock and the upload was rejected. Now pinned to a fixed, safe date, matching every other worker deployer in the app.
- **Cloud panel**: MLM (and Nahan) deploys could get permanently stuck failing at "Failed to create D1 Database" after a few retries, with no way to recover short of manually deleting old databases in the Cloudflare dashboard. Root cause: every deploy attempt -- including retries of a failed one -- provisioned a brand-new D1 database and never reused or cleaned up earlier ones, so a handful of retries would quietly eat into the account's D1 database quota until none was left to create. Deploys now reuse the account's existing database (or an orphaned one from a previous attempt) instead of always creating a new one. If the account's 10-database Free-plan limit is genuinely full with databases from other tools/projects, the error now lists every existing database's name so you know exactly what to delete, instead of a generic "Failed to create D1 Database."
- **Personal anti-sanction DNS**: turning this feature on could break sites that were never even supposed to be touched (e.g. Gmail), because its DNS resolver was hardcoded to plain UDP `1.1.1.1:53` -- a protocol commonly blocked or throttled on Iranian ISPs -- with no fallback, unlike the main VPN config which already resolves via DoH over HTTPS for exactly this reason. It now resolves via the same DoH-over-443 approach, so DNS keeps working (and non-sanctioned domains keep resolving normally) even when plain DNS is blocked.
- **Personal anti-sanction DNS**: after successfully deploying the exit worker, the screen kept showing the "build worker" button instead of switching to "ready" -- the user had to leave and re-enter the screen to see it had actually worked. Root cause: the deploy functions update the Cloudflare account object's fields in place, so the list pushed into the shared account state afterward was structurally identical to what was already there (same mutated object references), and Kotlin's `StateFlow` skips re-emitting a value it considers unchanged -- so this screen never recomposed. Now tracks deploy success locally instead of waiting on that round-trip.
- **Personal anti-sanction DNS**: the "build worker" step (same EDG worker deploy used elsewhere) could fail permanently on repeated retries for the same reason MLM/Nahan did -- it always created a brand-new KV namespace and never reused or cleaned up earlier ones. It now reuses the account's existing KV namespace (or an orphaned one from a previous attempt), and deploy failures now show the real Cloudflare error instead of a generic message.
- **Scanner tab**: the "Ping Health Test" button on a combined/mixed config group (and the MLM "Update subscription with healthy IPs" flow that depends on it) could hang indefinitely or crash the app on some devices. Root cause: this specific code path was missing the native Xray core initialization call that every other testing feature in the app performs first (it was commented out) -- whether it happened to work depended entirely on some other screen having already initialized the core earlier in the same app session, which is why it worked reliably for some users/flows and not others. Restored the missing call.
- **Cloud panel**: the MLM "User Management" screen could get permanently stuck on "Error fetching users" right after a fresh MLM deploy. Root cause: Cloudflare's own edge returns a transient `404` (its own error code `1042`) for the first few seconds after a brand-new `*.workers.dev` route is enabled, before the worker code even runs -- the app now retries a few times automatically before treating it as a real error, and any real failure now shows the actual HTTP status/response instead of a generic message.
- **Cloud panel**: BPB worker uploads failed with Cloudflare error 10021 ("No such module node:crypto") — v5.1.1 requires the `nodejs_compat` compatibility flag, which wasn't being sent.
- **Cloud panel**: BPB-generated configs connected to nothing (empty SNI/Host, falling back to BPB's own placeholder domain `www.speedtest.net`) because the worker's `mainDomain` was left blank; it's now set to the deployed worker's actual `*.workers.dev` hostname.
- **Navigation**: the hamburger drawer menu could get cut off on short screens, making the emergency-tier options at the bottom unreachable. Drawer content now scrolls fully.
- **Connection tab**: the country flag shown above the Connect button came from Cloudflare's own geoIP database (the `loc` field in `cdn-cgi/trace`), which occasionally disagreed with reality (e.g. showed Canada for a US exit IP). It now uses the same `api.ip.sb/geoip` source as the per-config flags, so both agree with each other and with third-party checkers like ip.me.

---

## [1.2.0] — 2026-07

### Added
- **GATE MLMVPN** — a new connection engine reachable over TCP :443 (works on lines that throttle UDP), with a large connect button in the bottom nav, thousands of servers with country flags, and a persistent archive of every server ever seen (not just currently-live ones).
- Real connection testing, separate from ping: a full handshake against the server, not just RTT — green means it actually connects on your line.
- Browse servers by continent/country, multi-select, batch test, add healthy ones to your main list, and clear the unresponsive ones.
- Optional UDP acceleration toggle for networks where UDP is open.
- New app-launch animation.

### Changed
- Full redesign of the WireGuard tab: large central connect button, key settings directly beneath it, secondary settings on their own screen, connection status at the bottom.

### Fixed
- App used to crash and relaunch a few seconds after a Google Apps Script disconnect — the tunnel's native core was pulling the whole app down with it on shutdown. It now runs isolated from the app process.
- WireGuard: a healthy tunnel used to be misdetected as dead after ~12s idle and reconnected from scratch; the idle-vs-dead detection window was fixed.
- WireGuard: automatic fallback to another transport if the first doesn't respond, and automatic re-keying if the WARP identity is rejected.

### Security
- Server IP:port is no longer shown on server cards (still sortable by those fields) to avoid leaking it in screenshots.

---

## [1.1.0] — 2026-06

### Added
- **Personal anti-sanction DNS** — routes only sanctioned domains (ChatGPT, Gemini, GitHub, Steam, ...) through your own Cloudflare Worker with a clean IP; everything else stays direct. Any domain can be looked up and added.
- Full redesign of the second-tier emergency flow (Google Apps Script): step-by-step wizard (choose password → confirm → get ready-to-deploy code with a guide and direct link → enter Deployment ID → test the relay). Multiple Google accounts supported for load spreading.
- CA certificate for the second-tier emergency flow can now be generated and installed directly from the top card, no prior connection required.

### Fixed
- Copied Apps Script code is now clean (no BOM, no stray comments); added a "save as file" option for devices whose clipboard truncates large copies.
- App used to hard-close to the launcher on second-tier emergency disconnect; it now refreshes itself in a controlled way.
- Back-button behavior standardized: returns to the Cloud (home) tab from anywhere, steps back one level in two-step screens, and asks for confirmation to exit from the home tab.

---

## [1.0.9] — Emergency-conditions release

Optimized specifically for degraded/censored network conditions (server creation, management, and connection kept working under heavy filtering).

- Second-tier emergency (Google Apps Script) fully optimized for stability under bad networks.
- First-tier emergency (Vercel) now covers all 4 panels (MLM, Nahan, BPB, Edge): deploy, settings, user management, and config retrieval all work over Vercel even when direct Cloudflare access is blocked.
- Fixed xHTTP cloud-config lag and closed a DNS leak in xHTTP configs (queries now resolve encrypted, inside the tunnel).
- Six protected, non-removable default Iran configs, each with a different bypass strategy for different carriers.

## [1.0.8]

- Dedicated UAE WireGuard server for lower game ping, usable across the whole Game tab server list.
- Full Game tab redesign; AUTO mode now compares every ping-reduction method by real measured ping.
- Cloudflare Dedicated DNS with country-based smart steering.
- Free, unlimited dedicated UAE DNS service as a lightweight full-tunnel-free ping-reduction option.

## [1.0.8-beta]

- New Deno panel: fully automatic dedicated-server provisioning with no credit card, multi-account support, and per-account usage stats.
- Deno VLESS/Trojan configs over both WebSocket and xHTTP.
- Idle-connection auto-close and smarter in-tunnel request routing for free Deno server longevity.
- Quick-Settings tile: toggle the VPN and auto-connect to the fastest of your recent servers with one tap.
- Significant app size reduction (dead code removed, code shrinking enabled) while staying universal across device ABIs.
- Dedicated Game Booster system: automatically picks direct vs. tunnel routing for minimal ping.

## [1.0.7]

- New serverless connection method for degraded-network conditions, with 5 ready-made default servers (bypasses censorship, not usable for Telegram since it uses an Iranian IP).
- New dedicated MLMVPN panel with stable XHTTP configs.
- Rewritten scanner: more accurate delay/ping/speed tests.
- Fixed false ping readings shown with no actual internet connection.

## [1.0.6]

- New subscription-link (sub-link) generation system.

## [1.0.51]

- Full Android 15/16 compatibility fix: menu items no longer overlap the navigation bar.

## [1.0.5]

- SNI anti-filter engine with 400+ bundled configs.
- First-tier (Vercel Tunnel) and second-tier (Google Script Tunnel) emergency systems introduced.
- Core rewrite of all three cloud panels (NHN, BPB, EDG).
- Progress bars for ping/delay/speed tests.
- Manual MTU tuning.
- Unified worker management across a Cloudflare account (view/delete individually or in bulk).
- Per-platform config testing (YouTube, Instagram, + 8 more).
- Automatic background server failover, configurable per-app.
- English + Persian localization with automatic OS-language detection.
- New app icon and broad UI/UX overhaul.
- Optional WARP tab hide/disable.

---

<a id="فارسی"></a>
## فارسی

نسخه‌بندی این فایل مطابق `versionName` در [`app/build.gradle`](app/build.gradle) است. برای جزئیات کامل‌تر و به‌روزتر هر نسخه، داخل خود اپ به «درباره ما → لیست تغییرات» مراجعه کنید.

### [1.2.1] — 2026-08-09
**افزوده‌شده:**
- انتشار عمومی سورس با مجوز GPLv3 روی گیت‌هاب، به‌همراه README دوزبانه کامل، راهنمای مشارکت، و Git LFS برای باینری‌ها/دیتای جغرافیایی.
- **تب اتصال:** دکمه جدید «دریافت کانفیگ رایگان» بالای دکمه +. یک ویزارد چندمرحله‌ای از چند منبع عمومی گیت‌هابی کانفیگ جمع می‌کند، تعداد در دسترس را نشان می‌دهد، تعداد دلخواه کاربر را می‌گیرد، و با تست دو مرحله‌ای (فیلتر سریع شبکه + تست واقعی اتصال Xray، همان دقت «دیلی واقعی») فقط کانفیگ‌های واقعاً متصل را نگه می‌دارد. دکمه «همین تعداد کافیه» امکان توقف زودهنگام را می‌دهد. نتایج با نام رندوم `mlmvpnXXXX` (داخل خود URI هم baked می‌شود تا هنگام اشتراک‌گذاری بماند) در پوشه «کانفیگ‌های رایگان» تب Manual قرار می‌گیرند؛ کانفیگ‌های تکراری (بر اساس هویت واقعی سرور) دوباره اضافه نمی‌شوند.
- **تب اتصال:** پرچم واقعی کشور برای هر کانفیگ. حین تست «دیلی واقعی»، کانفیگی که متصل شد و پرچمش نامشخص بود، یک‌بار (و فقط یک‌بار، بعد از آن از حافظه) از همان تونل واقعی کشور خروجی‌اش استعلام می‌شود — دیگر لازم نیست حتماً وصل شوید تا پرچم واقعی را ببینید.

**تغییرات:**
- در تب گیم، دو سرور ثابت قدیمی وایرگارد امارات حذف و بوستر بازی برای اتصال کامل از موتور Aether استفاده می‌کند.
- پنل BPB در بخش ابری از نسخه ۴٫۲٫۲ به **۵٫۱٫۱** ارتقا یافت. این نسخه مدل دیپلوی را کاملاً عوض کرده: تنظیمات هر حساب (UUID، پسورد Trojan، مسیر امن، ایمیل/توکن حساب) حالا مستقیم داخل کد ورکر جاسازی می‌شود، نه از طریق متغیرهای کلودفلر مثل قبل. دیپلوی، ورود به پنل (حالا با JSON به‌جای متن ساده)، دریافت ساب‌لینک و ذخیره تنظیمات همگی با روش جدید بازنویسی شدند؛ فرم تنظیمات هم با فیلدهای نسخه جدید هماهنگ شد.
- ورکر EDG (بر پایه پروژه cmliu/edgetunnel) به آخرین نسخه آپدیت شد؛ این نسخه شامل چند ماه بهینه‌سازی انتقال داده از منبع اصلی است (بسته‌بندی هوشمند بسته‌های آپلود/دانلود، رقابت هم‌زمان چند اتصال TCP، کش DoH، حالت تک‌مسیره برای همراه اول) که باید باعث اتصال سریع‌تر و پایدارتر کانفیگ‌های EDG شود. متغیرهای دیپلوی (`UUID`، `PROXYIP`، `ADMIN`، `KV`) بدون تغییر ماندند، پس این یک جایگزینی مستقیم فایل ورکر است.
- پنل نهان (Nahan) از نسخه ۲٫۹٫۴ به **۳٫۰٫۰** ارتقا یافت — پروکسی زنجیره‌ای VLESS به‌عنوان upstream، فرمت ساب v2rayN JSON، فرم بازطراحی‌شده افزودن/ویرایش کاربر، و رفع چند باگ ظاهری RTL/حالت تاریک. نام بایندینگ D1 (`IOT_DB`) و مسیرهای `/api/auth` و `/api/sync` بدون تغییر ماندند، پس این هم جایگزینی مستقیم فایل ورکر است.

**حذف‌شده:**
- **پنل Deno** کاملاً حذف شد (آیتم منو، تب، بخش سؤالات متداول، و کل کد داخلی `engines/deno`). چون `api.deno.com` مدتی است در ایران فیلتر است و مدل صورتحساب Deno (بر اساس مدت‌زمان باز بودن اتصال، نه حجم) اصلاً برای VPN مناسب نبود (تعلیق مکرر با خطای Memory Time)؛ پنل‌های کلودفلر (BPB/EDG/Nahan) همان نیاز را بدون این دو مشکل پوشش می‌دهند.

**رفع باگ:**
- پیام مبهم هنگام افزودن اکانت کلودفلر با Global API Key بدون ایمیل، حالا واضح توضیح می‌دهد.
- خطای «آپلود ورکر MLM ناموفق بود» به‌خاطر محاسبه اشتباه تاریخ سازگاری از ساعت گوشی رفع شد.
- دیپلوی MLM (و نهان) گاهی برای همیشه روی خطای «Failed to create D1 Database» گیر می‌کرد و تنها راه‌حل حذف دستی دیتابیس‌های قدیمی از پنل کلودفلر بود. علت: هر بار دیپلوی (حتی هر بار retry بعد از شکست) یک دیتابیس D1 کاملاً تازه می‌ساخت و قدیمی‌ها را پاک نمی‌کرد؛ چند بار تلاش دوباره کافی بود تا سقف تعداد دیتابیس D1 حساب پر شود و دیگر هیچ دیپلویی جواب ندهد. حالا دیپلوی از دیتابیس موجود حساب (یا یک دیتابیس رهاشده از تلاش قبلی) استفاده مجدد می‌کند، نه ساخت دیتابیس تازه. اگر سقف ۱۰ دیتابیسی پلن رایگان واقعاً با دیتابیس‌های ابزارهای دیگر پر شده باشد، پیام خطا حالا اسم دقیق همه دیتابیس‌های موجود را نشان می‌دهد تا بدانید دقیقاً کدام را باید حذف کنید.
- روشن‌کردن «DNS ضد تحریم شخصی» می‌توانست سایت‌هایی که اصلاً قرار نبود دست‌خورده باشند (مثل Gmail) را هم خراب کند، چون resolver DNS این بخش به‌صورت ثابت روی UDP ساده `1.1.1.1:53` تنظیم شده بود — پروتکلی که در بسیاری از خطوط ایران فیلتر یا کند می‌شود — بدون هیچ راه جایگزین، برخلاف تنظیمات اصلی VPN که قبلاً به همین دلیل از DoH روی HTTPS استفاده می‌کند. حالا از همان روش DoH روی پورت ۴۴۳ استفاده می‌کند، پس DNS حتی وقتی DNS ساده فیلتر است هم کار می‌کند (و دامنه‌های غیرتحریمی هم عادی باز می‌شوند).
- بعد از دیپلوی موفق وورکر خروجی، صفحه هنوز دکمه «ساخت وورکر» را نشان می‌داد و کاربر باید از صفحه خارج و دوباره وارد می‌شد تا وضعیت «آماده ✅» را ببیند. علت: توابع دیپلوی فیلدهای همان آبجکت اکانت کلودفلر را مستقیم تغییر می‌دهند، پس لیستی که بعداً به state مشترک اکانت‌ها فرستاده می‌شود از نظر ساختاری با چیزی که از قبل آنجا بود یکسان است (همان رفرنس‌های تغییریافته) و Kotlin StateFlow این را «بدون تغییر» تشخیص داده و رویداد جدید ارسال نمی‌کند — پس این صفحه هیچ‌وقت دوباره رندر نمی‌شد. حالا موفقیت دیپلوی به‌صورت محلی هم ردیابی می‌شود.
- مرحله «ساخت وورکر» در همین بخش (همان دیپلوی EDG که جای دیگری هم استفاده می‌شود) می‌توانست دقیقاً به همان دلیلی که MLM/نهان داشتند، بعد از چند تلاش برای همیشه شکست بخورد — هر بار یک KV Namespace کاملاً تازه می‌ساخت و قدیمی‌ها را پاک نمی‌کرد. حالا از namespace موجود حساب (یا یکی رهاشده از تلاش قبلی) استفاده مجدد می‌کند، و خطای دیپلوی هم حالا متن واقعی پاسخ کلودفلر را نشان می‌دهد، نه یک پیام کلی.
- دکمه «تست سلامت پینگ» روی گروه‌های ترکیبی/Mix در تب اسکنر (و به‌تبع آن، بروزرسانی ساب‌اسکریپشن MLM با آی‌پی‌های سالم) روی بعضی گوشی‌ها ممکن بود برای همیشه گیر کند یا اپ کرش کند. علت: این مسیر خاص از کد، برخلاف تمام مسیرهای مشابه دیگر در اپ، فراخوانی مقداردهی اولیه هسته Xray را نداشت (یک خط کد به‌اشتباه کامنت شده بود)؛ این‌که کار می‌کرد یا نه کاملاً به این بستگی داشت که آیا صفحه‌ی دیگری از اپ قبلاً همان session هسته را مقداردهی کرده باشد یا نه — به همین دلیل برای بعضی کاربران/مسیرها همیشه کار می‌کرد و برای بعضی دیگر هرگز. خط گم‌شده بازگردانده شد.
- صفحه «مدیریت کاربران» در MLM گاهی بلافاصله بعد از دیپلوی تازه، برای همیشه روی خطای «Error fetching users» گیر می‌کرد. علت: خودِ Cloudflare چند ثانیه اول بعد از فعال‌شدن یک روت تازه‌ی `*.workers.dev` یک خطای ۴۰۴ موقتی (کد داخلی خودِ کلودفلر: ۱۰۴۲) برمی‌گرداند، قبل از اینکه کد ورکر ما اصلاً اجرا شود. اپ حالا چند بار خودکار دوباره امتحان می‌کند؛ اگر خطای واقعی دیگری باشد هم حالا کد HTTP و متن دقیق پاسخ نمایش داده می‌شود، نه یک پیام کلی.
- خطای آپلود ورکر BPB («No such module node:crypto») رفع شد — نسخه ۵٫۱٫۱ نیاز به فلگ سازگاری `nodejs_compat` دارد.
- کانفیگ‌های دریافتی از BPB قطع بودند (SNI/Host خالی، بازگشت به دامنه پیش‌فرض BPB) چون `mainDomain` تنظیم نشده بود؛ حالا به آدرس واقعی ورکر تنظیم می‌شود.
- بریدگی/عدم اسکرول منوی کشویی همبرگری در صفحه‌های کوتاه رفع شد.
- پرچم بالای دکمه اتصال گاهی با واقعیت اختلاف داشت (چون از دیتابیس geoIP خودِ Cloudflare خوانده می‌شد)؛ حالا از همان منبع پرچم کانفیگ‌ها استفاده می‌کند و همیشه با هم و با ابزارهایی مثل ip.me هماهنگ است.

برای جزئیات کامل نسخه‌های ۱٫۲٫۰ تا ۱٫۰٫۵، به بخش انگلیسی همین فایل یا صفحه «لیست تغییرات» داخل اپ مراجعه کنید (این‌ها به فارسی، به‌طور کامل، همان‌جا موجودند).
