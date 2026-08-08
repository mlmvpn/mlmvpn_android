# MLM VPN — Android

<p align="center">
  <b>A multi-engine VPN, network-scanning, and connectivity-boosting Android app.</b><br/>
  Developed and maintained by <a href="https://github.com/mlmvpn">mlmvpn</a>.
</p>

<p align="center">
  <a href="#english">English</a> · <a href="#فارسی">فارسی</a>
</p>

---

## English

### Overview

MLM VPN is an Android application that combines several independent
connectivity engines behind one UI: a VPN node scanner/manager, multiple
tunnel protocols, a DNS/latency "game booster" for reducing in-game ping,
and a set of specialized circumvention engines for restrictive network
environments. It is built with Kotlin and Jetpack Compose, targeting
`minSdk 24` / `compileSdk 34`.

> ⚠️ This project involves network circumvention and VPN technology. Use it
> in compliance with the laws of your jurisdiction. The maintainers do not
> encourage or condone illegal use.

### Features

- **Node scanner & manager** — import, scan, and manage VLESS/VMess/Trojan/
  Shadowsocks-style subscription nodes (`NodeManager`, `SubscriptionManager`).
- **Multiple tunnel engines**:
  - Xray-core (VLESS/VMess/Trojan) via `VlessXrayInjector`
  - AmneziaWG (obfuscated WireGuard) via `AmneziaWgInjector`
  - `kittoku` MVC-based WireGuard support
  - GST relay engine (`engines/gst`)
  - EDG engine (`engines/edg`)
  - Aether engine (`core/aether`) — see [`AETHER.md`](AETHER.md)
  - VPN Gate public relay browsing (`engines/vpngate`)
- **Game Booster** — races Cloudflare/UAE Dedicated DNS and tunnel options
  by real ping to minimize in-game latency without a full VPN hop when
  possible.
- **Sanction-domain routing** (`engines/sanction`) — smart routing for
  sanctioned/geo-restricted services.
- **Cloud deployment tooling** (`engines/mlm`, `assets/gst`) — Google Apps
  Script / Cloudflare Worker deployment helpers for self-hosted relay/DNS
  infrastructure.
- **Emergency fallback screens** — guided setup when primary connectivity
  paths are blocked.

### Tech stack

- Kotlin, Jetpack Compose, Coroutines/Flow
- Native libraries (JNI) for Xray-core, AmneziaWG, Aether, tun2proxy
- Cloudflare Workers / Google Apps Script for optional self-hosted backend
  components (see `app/src/main/assets/gst/`, `*_worker.js`)

### Project structure

```
app/src/main/java/com/mlmvpn/
├── core/                  # Low-level engine wrappers (aether, warp)
└── scanner/
    ├── data/              # Node/subscription/cloud data managers
    ├── engines/           # Pluggable connectivity engines (one dir each)
    ├── ui/                # Compose screens
    └── utils/             # Config generators, testers, watchdogs
app/src/main/assets/       # Bundled worker scripts, geo data, sanction lists
app/src/main/jniLibs/      # Prebuilt native libraries (arm64-v8a, armeabi-v7a)
```

### Building

Requirements: Android Studio (recent), JDK matching the Android Gradle
Plugin used in `build.gradle`, Android SDK with `compileSdk 34` installed.

```bash
git clone https://github.com/mlmvpn/mlmvpn_android.git
cd mlmvpn_android
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

> Some native binaries (`.so` files under `app/src/main/jniLibs/`) and data
> files (`geoip.dat`, `geosite.dat`) are large and tracked with **Git LFS**.
> Install [Git LFS](https://git-lfs.com/) and run `git lfs pull` after
> cloning if those files show up as small pointer files.

### Contributing

Contributions are welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md) for
project structure, coding conventions, and how to add a new engine.

### License & Attribution

This project is licensed under **GNU GPLv3**, plus an additional term
(permitted under GPLv3 §7) requiring attribution — see [`LICENSE`](LICENSE).
This is because the project vendors GPL-3.0 source from `kittoku/mvc` and
`shadowsocks-android`, which requires the whole combined work to be GPL. In
short:

> Any use, fork, or derivative of this project — source or compiled — must
> clearly credit the original developer, **mlmvpn**
> (https://github.com/mlmvpn), wherever the software is presented to end
> users (About screen, README, store listing, etc.), in addition to the
> standard GPLv3 obligations (source availability, same license on
> derivatives, etc.).

---

## فارسی

### معرفی

**MLM VPN** یک اپلیکیشن اندرویدی است که چند موتور اتصال مستقل را زیر یک
رابط کاربری واحد گرد هم می‌آورد: اسکنر و مدیریت‌کننده نودهای VPN، چند
پروتکل تونل، سیستم «بوستر بازی» برای کاهش پینگ داخل بازی از طریق DNS و
تست تأخیر، و مجموعه‌ای از موتورهای تخصصی دورزدن محدودیت برای شبکه‌های
محدودشده. این پروژه با Kotlin و Jetpack Compose نوشته شده و `minSdk 24` و
`compileSdk 34` را هدف قرار می‌دهد.

> ⚠️ این پروژه با فناوری VPN و دورزدن محدودیت‌های شبکه سروکار دارد. لطفاً
> مطابق قوانین کشور خود از آن استفاده کنید. توسعه‌دهندگان این پروژه استفاده
> غیرقانونی را تشویق یا تأیید نمی‌کنند.

### امکانات

- **اسکنر و مدیریت نود** — وارد کردن، اسکن و مدیریت نودهای اشتراکی به سبک
  VLESS/VMess/Trojan/Shadowsocks (`NodeManager`، `SubscriptionManager`).
- **موتورهای تونل متعدد**:
  - Xray-core (VLESS/VMess/Trojan) از طریق `VlessXrayInjector`
  - AmneziaWG (وایرگارد مبهم‌سازی‌شده) از طریق `AmneziaWgInjector`
  - پشتیبانی وایرگارد مبتنی بر `kittoku`
  - موتور رله GST (`engines/gst`)
  - موتور EDG (`engines/edg`)
  - موتور Aether (`core/aether`) — به [`AETHER.md`](AETHER.md) مراجعه کنید
  - مرور رله‌های عمومی VPN Gate (`engines/vpngate`)
- **بوستر بازی** — گزینه‌های DNS اختصاصی کلودفلر/امارات و تونل را با پینگ
  واقعی مسابقه می‌دهد تا در صورت امکان بدون یک تونل کامل، تأخیر داخل بازی
  را کمینه کند.
- **مسیریابی دامنه‌های تحریم‌شده** (`engines/sanction`) — مسیریابی هوشمند
  برای سرویس‌های تحریم‌شده/محدودشده جغرافیایی.
- **ابزارهای استقرار ابری** (`engines/mlm`, `assets/gst`) — کمک‌کننده‌های
  استقرار Google Apps Script / Cloudflare Worker برای زیرساخت رله/DNS
  خوداستقرار.
- **صفحات پشتیبان اضطراری** — راه‌اندازی هدایت‌شده هنگامی که مسیرهای اصلی
  اتصال مسدود شده باشند.

### ساختار پروژه

```
app/src/main/java/com/mlmvpn/
├── core/                  # لایه‌های پایه موتورها (aether، warp)
└── scanner/
    ├── data/              # مدیریت داده نود/اشتراک/کلاود
    ├── engines/           # موتورهای اتصال قابل‌افزودن (هر کدام یک پوشه)
    ├── ui/                # صفحات Compose
    └── utils/             # تولیدکننده‌های کانفیگ، تسترها، واچ‌داگ‌ها
app/src/main/assets/       # اسکریپت‌های ورکر، داده جغرافیایی، لیست تحریم
app/src/main/jniLibs/      # کتابخانه‌های native از پیش‌کامپایل‌شده
```

### راه‌اندازی و ساخت پروژه

پیش‌نیازها: Android Studio (نسخه اخیر)، JDK متناظر با Android Gradle
Plugin استفاده‌شده در `build.gradle`، Android SDK با `compileSdk 34` نصب‌شده.

```bash
git clone https://github.com/mlmvpn/mlmvpn_android.git
cd mlmvpn_android
./gradlew assembleDebug
```

فایل APK دیباگ در مسیر `app/build/outputs/apk/debug/` تولید می‌شود.

> برخی فایل‌های باینری native (فایل‌های `.so` در `app/src/main/jniLibs/`) و
> فایل‌های داده (`geoip.dat`، `geosite.dat`) حجیم هستند و با **Git LFS**
> ردیابی می‌شوند. پس از clone کردن، [Git LFS](https://git-lfs.com/) را نصب
> کرده و دستور `git lfs pull` را اجرا کنید.

### مشارکت در پروژه

از مشارکت شما استقبال می‌شود — فایل [`CONTRIBUTING.md`](CONTRIBUTING.md)
را برای آشنایی با ساختار پروژه، استانداردهای کدنویسی، و نحوه افزودن موتور
جدید مطالعه کنید.

### مجوز و ذکر منبع

این پروژه تحت یک **نسخه اصلاح‌شده از مجوز MIT با الزام ذکر منبع** منتشر
شده است — به فایل [`LICENSE`](LICENSE) مراجعه کنید. به‌طور خلاصه:

> هرگونه استفاده، فورک یا اثر مشتق‌شده از این پروژه — چه به‌صورت سورس و چه
> کامپایل‌شده — باید هرجا که نرم‌افزار به کاربر نهایی نمایش داده می‌شود
> (صفحه درباره ما، README، صفحه فروشگاه و غیره) به‌طور واضح توسعه‌دهنده
> اصلی، **mlmvpn** (https://github.com/mlmvpn)، را ذکر کند.
