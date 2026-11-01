# بستهٔ اندروید هسته (`org.mlmchat.core`)

هستهٔ Rust (`core/`) برای اندروید، با یک رابط Kotlin. نه رابط کاربری دارد و نه چیزی از اپ میزبان (بند ۱۴.۲ برنامه). مجوزش Apache-2.0 است و همان چیزی است که بعداً به دکتر نت هم می‌رسد.

| پوشه | چیست |
|---|---|
| `jni/` | جعبهٔ Rust به نام `mlmchat-android` که `libmlmchat.so` را می‌سازد. همهٔ تابع‌های JNI در `jni/src/lib.rs` است. |
| `core/` | ماژول کتابخانهٔ اندروید (`org.mlmchat.core`)، با رابط Kotlin و نگه‌داری کلید پایگاه داده. |
| `scripts/build-native.sh` | ساختن `libmlmchat.so` در `core/src/main/jniLibs/<abi>/`. خروجی ساخت است و در git نمی‌ماند. |

## ساختن

```bash
ANDROID_NDK_HOME='I:\Android\ndk\android-ndk-r27d' bash android/scripts/build-native.sh
```

پیش‌فرض فقط `arm64-v8a` است، چون کد بومی خود فورک تلگرام هم فعلاً فقط برای همین معماری ساخته شده. پوشهٔ `core/target` با خود هسته مشترک است، پس وابستگی‌هایی که برای `peer-cli` ساخته شده‌اند دوباره ساخته نمی‌شوند. پرچم صفحهٔ ۱۶ کیلوبایتی از `jni/.cargo/config.toml` می‌آید. خروجی ۲۳ اکتبر ۲۰۲۶ (فاز ۳): حدود ۶٫۲ مگابایت برای arm64 (پیش‌تر ۲٫۲)، با بخش‌های LOAD تراز ۰x4000. آن ۴ مگابایت اضافه libp2p، tokio، rustls و aws-lc است. اسکریپت کتابخانهٔ ساخته‌شده را خودش در `jniLibs` کپی می‌کند (`cargo ndk` گاهی نسخهٔ کهنه را می‌گذاشت)، و فایل جانبی بی‌مصرف `libif_watch-*.so` را پاک می‌کند.

اپ میزبان ماژول را با `include ':mlmchat-core'` و `projectDir` همین پوشهٔ `core/` برمی‌دارد.

## رابط (فاز ۲)

```kotlin
// اولین باز کردن: کلیدها همین حالا روی گوشی ساخته می‌شوند، پیش از نام‌گذاری.
val pending = PendingIdentity()
pending.tag            // "4821": چهار رقمی که بعد از نام می‌آید (ت۱۴)
val node = pending.create(context, "ehsan")   // نام می‌گیرد و رمزشده ذخیره می‌شود؛ فقط یک بار

// بارهای بعد
val node = ChatNode.open(context)              // وقتی ChatNode.exists(context)
node.me.displayName                            // "ehsan·4821"

node.startDirect()                             // راه مستقیم فاز ۱ (شبکهٔ محلی با نشانهٔ خصوصی، IPv6 مستقیم)
node.cardLink()                                // کارت رو در رو برای QR، هر بار با کلید یک‌بارمصرف تازه
node.addContact(link)                          // از پیوند یا QR؛ کارت ما هم درون جلسهٔ تازه برایش می‌رود
node.sendText(contact.sk, "salam")             // شناسهٔ پیام؛ پیشرفتش با رویداد State می‌آید
node.history(contact.sk, 100)                  // پیام‌ها، قدیمی اول؛ هر پیام seq ثابتِ همین گوشی را دارد
node.markRead(contact.sk)                      // رسید «خوانده شد» هم می‌رود، اگر روشن باشد
node.securityNumber(contact.sk)                // عدد ۶۰ رقمی (بند ۲ قرارداد)
node.setBlocked(contact.sk, true)              // فقط روی همین گوشی (بند ۱۰ قرارداد)؛ به طرف گفته نمی‌شود
node.deleteMessages(longArrayOf(m.seq))        // فقط روی همین گوشی؛ پیامِ هنوز در صف دیگر فرستاده نمی‌شود
node.clearHistory(contact.sk)                  // همهٔ گفت‌وگو، فقط روی همین گوشی (یا تا یک seq)
node.setName("ehsan2")                         // چهار رقم می‌ماند؛ کارت تازه با KeyUpdate به همهٔ مخاطبان می‌رود
while (true) node.pollEvent(1000)?.let { … }   // NewMessage، State، Queued، NewContact، ContactChanged، Seen، Reachable؛ فقط یک رشته
```

## رابط فاز ۳: راه بین دو شبکه

```kotlin
node.startP2p()                                // بعد از startDirect: داوطلبان از کارت مخاطبان، رزرو رله، رکورد حضور در DHT خصوصی
node.netStatus()                               // NetStatus: volunteersKnown، volunteersConnected، reservations، publishedMs، online
node.contactStatus(contact.sk)                 // ContactStatus: lastReachedMs، via (lan|direct|p2p|relay)، online، queued، reason
node.setNetwork("cell:43235")                  // برچسب شبکه برای حافظهٔ مسیر (کد اپراتور روی دیتا)؛ هرگز SSID روشن
node.networkChanged()                          // شبکه عوض شد: اتصال تازه، رکورد حضور تازه، صف همین حالا
```

- گوشی هرگز خودکار داوطلب نمی‌شود (`startP2p(volunteer = false)`). داوطلب شدن انتخاب کاربر است (بند ۱۱ برنامه).
- خط بالای صفحه، راستین: بی‌شبکه «در انتظار شبکه». وقتی `volunteersKnown > 0` ولی `!online`، «در حال اتصال». بقیه «متصل». فورک تلگرام MLMVPN همین را هر ۳ ثانیه از `netStatus` می‌خواند.
- خط زیر نام مخاطب، فقط از `contactStatus` و رویدادهای `Reachable` و `Queued`: «در دسترس»، یا «در دسترس، از راه گرهٔ داوطلب» اگر `via` برابر `relay` باشد، یا «الان در دسترس نیست…» تا وقتی پیام در صف است، یا «آخرین بار در دسترس: …».
- اپ میزبان `networkChanged` را با هر عوض شدن شبکهٔ پیش‌فرض صدا می‌زند. فورک با `registerDefaultNetworkCallback` این کار را می‌کند. برچسب شبکه روی دیتا `cell:` به‌علاوهٔ `getSimOperator()` است و روی وای‌فای خالی، که یعنی هسته خودش از نشانی‌ها می‌سازد.

- `Me`: `sk` (کلید هویت، hex)، `name`، `displayName` (ت۱۴) و `code` (نیمهٔ ۳۰ رقمیِ شمارهٔ امنیتی، بند ۱۴.۱ قرارداد).
- `Message.seq`: شمارهٔ خودِ این گوشی برای پیام، به ترتیب ذخیره، که هرگز عوض نمی‌شود. رابط‌هایی که شناسهٔ عددی می‌خواهند (فورک تلگرام) همین را به کار می‌برند.
- وضعیت پیام فقط جلو می‌رود: در صف، رسید به گوشی طرف (`SENT`)، باز شد (`DELIVERED`)، خوانده شد. هیچ وضعیتی پیش از وقتش نشان داده نمی‌شود (اصل ۴).
- هر خطای هسته `CoreException` است. پیامش هیچ کلید، نام یا مسیری ندارد.
- پایگاه داده در `noBackupFilesDir/mlmchat/core.db` است. کلید ۳۲ بایتی‌اش (SQLCipher، بند ۱۴.۹) تصادفی ساخته می‌شود و فقط پیچیده‌شده با AES-256-GCM روی دیسک می‌ماند (`db.key`)، زیر کلیدی که در Android Keystore است و هرگز بیرون نمی‌آید. همه‌اش رمزنگاری خود سکوست، بدون رمزنگاری دست‌ساز (ت۱۸).
- اپ میزبان برای نشانه‌های شبکهٔ محلی قفل multicast وای‌فای را نگه می‌دارد (`CHANGE_WIFI_MULTICAST_STATE`).
