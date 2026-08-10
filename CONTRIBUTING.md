# Contributing / راهنمای مشارکت

## English

Thanks for your interest in improving MLM VPN. Please read this before
opening a PR.

### License / Attribution requirement

This project is licensed under **GNU GPLv3** (the codebase vendors GPL-3.0
source from `kittoku/mvc` and `shadowsocks-android`) plus an additional term
that **requires attribution to the original developer, mlmvpn
(https://github.com/mlmvpn)**, in any fork, redistribution, or derivative
build — see [`LICENSE`](LICENSE). Do not remove or replace this attribution
in your contributions or forks, and any code you contribute must be
GPLv3-compatible.

### Getting set up

1. Fork and clone the repo, then `git lfs pull` (native `.so` files and geo
   data are stored with Git LFS — see the README).
2. Open the project in Android Studio, let Gradle sync.
3. `./gradlew assembleDebug` should produce a working debug APK.

### Project structure

- `app/src/main/java/com/mlmvpn/scanner/engines/<name>/` — each connectivity
  engine (Xray, AmneziaWG, GST, EDG, Aether, VPN Gate, Sanction routing, Game
  booster, ...) lives in its own package. An engine typically owns:
  - a `*Manager` or `*Engine` class with the connect/scan/test logic
  - its own data models
  - (optionally) its own Compose screen under `ui/`
- `app/src/main/java/com/mlmvpn/scanner/ui/` — Compose screens, one file per
  tab/screen (e.g. `GameTab.kt`, `NodesTab.kt`, `WireguardTab.kt`).
- `app/src/main/java/com/mlmvpn/scanner/data/` — persistence and shared state
  (`NodeManager`, `SubscriptionManager`, `CloudManager`).
- `app/src/main/java/com/mlmvpn/scanner/utils/` — config generators
  (`XrayJsonGenerator`, `AmneziaWgConfigGenerator`), network testers, and the
  connectivity watchdog.
- `app/src/main/java/com/mlmvpn/core/` — lower-level native engine wrappers
  that don't belong to a single UI tab (e.g. `aether/`).
- `app/src/main/java/com/mlmvpn/scanner/mitm/` — on-device CA generation and
  the server-less domain-fronting profile; see the README's "Server-less
  domain fronting" feature bullet.
- `app/src/main/assets/` — bundled JS/Apps Script for optional self-hosted
  backend workers (DNS, relay), plus geo/sanction data files.
- `app/src/main/jniLibs/` — prebuilt native libraries per ABI. If you change
  a native engine, you must rebuild and commit the `.so` for all supported
  ABIs (`arm64-v8a`, `armeabi-v7a`).
- `scripts/` — standalone maintainer/dev tooling that isn't part of the app
  build itself (e.g. `install-aether-binary.ps1`, see [`AETHER.md`](AETHER.md)).

### Adding a new connectivity engine

1. Create `app/src/main/java/com/mlmvpn/scanner/engines/<yourengine>/`.
2. Implement a manager/engine class following the pattern of an existing
   simple engine (e.g. `engines/edg/`) — expose connect/disconnect/test as
   suspend functions or a `StateFlow`-based status, consistent with how
   `GameBoosterManager` or `AetherEngine` are structured.
3. Wire it into `MyVpnService.kt` only if it needs to run as the active VPN
   tunnel; otherwise it can be a self-contained probe/booster like the Game
   tab's DNS modes.
4. Add a Compose screen under `ui/` and a tab entry in `AppScreen.kt` if it
   needs its own UI.
5. Keep secrets/keys out of the code — nothing should be hardcoded; anything
   sensitive should come from user input or a deployed worker the user owns.

### Code style

- Kotlin, standard Android/Compose conventions (match surrounding code).
- Persian-facing UI strings stay in Persian; code comments in English.
- Keep comments to the non-obvious "why," not a restatement of the code.
- No unrelated refactors in a bugfix PR — keep changes scoped.

### Submitting changes

- Open an issue first for anything non-trivial (new engine, architecture
  change) to avoid duplicated work.
- Small, focused PRs are preferred over large multi-topic ones.
- Make sure `./gradlew assembleDebug` succeeds before submitting.

---

## فارسی

از علاقه شما به بهبود MLM VPN سپاسگزاریم. لطفاً قبل از ارسال PR این سند را
مطالعه کنید.

### الزام ذکر منبع

این پروژه تحت یک نسخه اصلاح‌شده از مجوز MIT منتشر شده که **الزام ذکر منبع
توسعه‌دهنده اصلی، mlmvpn (https://github.com/mlmvpn)**، در هر فورک، توزیع
مجدد یا نسخه مشتق‌شده را دارد — به [`LICENSE`](LICENSE) مراجعه کنید. لطفاً
این ذکر منبع را در مشارکت‌ها یا فورک‌های خود حذف یا جایگزین نکنید.

### راه‌اندازی محیط توسعه

۱. ریپازیتوری را fork و clone کنید، سپس `git lfs pull` را اجرا کنید (فایل‌های
   native و داده جغرافیایی با Git LFS ذخیره شده‌اند — به README مراجعه کنید).
۲. پروژه را در Android Studio باز کنید و اجازه دهید Gradle sync شود.
۳. دستور `./gradlew assembleDebug` باید یک APK دیباگ سالم تولید کند.

### ساختار پروژه

- `app/src/main/java/com/mlmvpn/scanner/engines/<name>/` — هر موتور اتصال
  (Xray، AmneziaWG، GST، EDG، Aether، VPN Gate، مسیریابی تحریم، بوستر بازی و
  ...) در پکیج مخصوص خودش قرار دارد.
- `app/src/main/java/com/mlmvpn/scanner/ui/` — صفحات Compose، هر فایل یک
  تب/صفحه.
- `app/src/main/java/com/mlmvpn/scanner/data/` — لایه ماندگاری و وضعیت
  مشترک.
- `app/src/main/java/com/mlmvpn/scanner/utils/` — تولیدکننده‌های کانفیگ،
  تسترهای شبکه، و واچ‌داگ اتصال.
- `app/src/main/java/com/mlmvpn/core/` — لایه‌های پایه موتورهای native که
  مختص یک تب خاص نیستند (مثل `aether/`).
- `app/src/main/java/com/mlmvpn/scanner/mitm/` — ساخت گواهی روی گوشی و
  پروفایل دامین‌فرانتینگ بدون سرور؛ به بخش «دامین‌فرانتینگ بدون سرور» در
  README مراجعه کنید.
- `app/src/main/assets/` — اسکریپت‌های JS/Apps Script برای بک‌اند خوداستقرار
  اختیاری، به‌همراه فایل‌های داده جغرافیایی/تحریم.
- `app/src/main/jniLibs/` — کتابخانه‌های native از پیش کامپایل‌شده برای هر
  ABI. در صورت تغییر یک موتور native، باید فایل `.so` را برای تمام
  ABIهای پشتیبانی‌شده (`arm64-v8a`، `armeabi-v7a`) بازسازی و commit کنید.
- `scripts/` — ابزارهای توسعه/نگهداری مستقل که بخشی از بیلد خود اپ نیستند
  (مثل `install-aether-binary.ps1`؛ به [`AETHER.md`](AETHER.md) مراجعه کنید).

### افزودن موتور اتصال جدید

۱. پوشه `app/src/main/java/com/mlmvpn/scanner/engines/<yourengine>/` را
   بسازید.
۲. یک کلاس manager/engine مطابق الگوی یک موتور ساده موجود (مثلاً
   `engines/edg/`) پیاده‌سازی کنید.
۳. فقط در صورتی که موتور نیاز به اجرا به‌عنوان تونل VPN فعال دارد آن را در
   `MyVpnService.kt` متصل کنید؛ در غیر این صورت می‌تواند مانند حالت‌های DNS
   تب Game، یک پروب/بوستر مستقل باشد.
۴. در صورت نیاز به رابط کاربری مستقل، یک صفحه Compose در `ui/` و یک ورودی
   تب در `AppScreen.kt` اضافه کنید.
۵. هیچ کلید یا اطلاعات حساسی نباید در کد هاردکد شود؛ هر مقدار حساس باید از
   ورودی کاربر یا ورکری که خود کاربر مستقر کرده تأمین شود.

### استایل کد

- Kotlin، مطابق استانداردهای رایج Android/Compose.
- رشته‌های رابط کاربری فارسی، فارسی باقی بمانند؛ کامنت‌های کد به انگلیسی.
- کامنت‌ها فقط برای دلیل غیربدیهی («چرا»)، نه توضیح تکراری کد.
- در یک PR رفع باگ، ریفکتور نامرتبط اضافه نکنید — تغییرات را محدود نگه دارید.

### ارسال تغییرات

- برای هر تغییر غیرجزئی (موتور جدید، تغییر معماری) ابتدا یک issue باز کنید.
- PRهای کوچک و متمرکز بر PRهای بزرگ و چندموضوعی ترجیح دارند.
- قبل از ارسال، از موفقیت `./gradlew assembleDebug` مطمئن شوید.
