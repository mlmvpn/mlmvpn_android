# Changelog

All notable changes to the MLM VPN Android app are documented here. Dates are in `YYYY-MM-DD`. This file follows the spirit of [Keep a Changelog](https://keepachangelog.com/); version numbers match `versionName` in [`app/build.gradle`](app/build.gradle).

فارسی این فایل در ادامه (پایین همین صفحه) آمده است.

## [1.2.1] — 2026-08-09

### Added
- Public GPLv3 source release on GitHub, with a full bilingual README, CONTRIBUTING guide, and Git LFS for native binaries/geo data.

### Changed
- **Game tab**: removed the two hardcoded legacy UAE WireGuard servers ("Server 1" — a dead 1-hour-trial WireGuard endpoint, "Server 2" — already-retired dead code). Full-tunnel game boosting now goes through the Aether engine, which self-selects a healthy endpoint across multiple protocols instead of a single fixed server.

### Fixed
- **Cloud panel**: adding a Cloudflare account with a Global API Key and no email showed a vague "Invalid API Token" error. It now explains clearly that Global API Keys require an email, with a hint shown under the field.
- **Cloud panel**: MLM worker deploys failed intermittently with "Failed to upload MLM worker". Root cause: the worker's `compatibility_date` was computed from the device's local clock instead of a fixed date; for timezones ahead of UTC (e.g. Iran, UTC+3:30), roughly between midnight and 03:30 local time the computed date landed a day ahead of Cloudflare's UTC clock and the upload was rejected. Now pinned to a fixed, safe date, matching every other worker deployer in the app.
- **Navigation**: the hamburger drawer menu could get cut off on short screens, making the emergency-tier options at the bottom unreachable. Drawer content now scrolls fully.

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
**افزوده‌شده:** انتشار عمومی سورس با مجوز GPLv3 روی گیت‌هاب، به‌همراه README دوزبانه کامل، راهنمای مشارکت، و Git LFS برای باینری‌ها/دیتای جغرافیایی.

**تغییرات:** در تب گیم، دو سرور ثابت قدیمی وایرگارد امارات حذف و بوستر بازی برای اتصال کامل از موتور Aether استفاده می‌کند.

**رفع باگ:**
- پیام مبهم هنگام افزودن اکانت کلودفلر با Global API Key بدون ایمیل، حالا واضح توضیح می‌دهد.
- خطای «آپلود ورکر MLM ناموفق بود» به‌خاطر محاسبه اشتباه تاریخ سازگاری از ساعت گوشی رفع شد.
- بریدگی/عدم اسکرول منوی کشویی همبرگری در صفحه‌های کوتاه رفع شد.

برای جزئیات کامل نسخه‌های ۱٫۲٫۰ تا ۱٫۰٫۵، به بخش انگلیسی همین فایل یا صفحه «لیست تغییرات» داخل اپ مراجعه کنید (این‌ها به فارسی، به‌طور کامل، همان‌جا موجودند).
