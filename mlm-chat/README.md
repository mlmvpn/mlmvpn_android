# گره (MLM CHAT)

پیام‌رسان گوشی‌به‌گوشی با رمزنگاری سرتاسری، مشترک بین MLMVPN و دکتر نت.

| پوشه | چیست |
|---|---|
| `core/` | هستهٔ گره به زبان Rust (Apache-2.0): هویت، رمزنگاری Olm و Megolm، پیام، گروه و کانال، و راه‌های رساندن پیام (بلوتوث، شبکهٔ محلی، اتصال مستقیم، رلهٔ گوگل، رله‌های Nostr) |
| `android/` | پل JNI و بستهٔ Kotlin `org.mlmchat.core` |
| `docs/SPEC.md` | قرارداد پروتکل: قالب داده روی شبکه، رمزنگاری و رله‌ها |
| `vectors/` | نمونه‌های آزمون مرجع پروتکل |
| `relay/` | کد رله‌ها: صندوق گوگل (`Mailbox.gs`)، ورکر کلادفلر (`MailboxWorker.js`) و رلهٔ Nostr (`nostr/setup-strfry.sh`) |
| `tools/` | ابزارهای آزمون و سنجش شبکه |

رابط کاربری گره (فورک تلگرام اندروید، GPL) در پوشهٔ `chat/` همین مخزن است.

در این نسخهٔ عمومی، آدرس و کلید رله‌های خودمان با جای‌نگهدار عوض شده است.

---

**Gereh (MLM CHAT)** is a phone-to-phone, end-to-end encrypted messenger shared by MLMVPN and Dr. Net. `core/` is the Rust core (Apache-2.0): identity, Olm and Megolm encryption, messages, groups and channels, and the transports (Bluetooth, local network, direct, Google relay, Nostr relays). `android/` holds the JNI bridge and the Kotlin package, `docs/SPEC.md` the protocol, `vectors/` the reference test vectors, `relay/` the relays' code and `tools/` the test tools. The user interface, a GPL fork of Telegram for Android, is in `chat/`. In this public copy, our own relays' addresses and keys are replaced with placeholders.
