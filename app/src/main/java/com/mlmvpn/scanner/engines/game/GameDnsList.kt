package com.mlmvpn.scanner.engines.game

/**
 * لیست سرورهای DNS که برای انتخاب خودکار سریع‌ترین، رقیب (race) می‌شوند.
 *
 * این لیست کاملاً پشت‌پرده است؛ کاربر هرگز آن را نمی‌بیند. وقتی کاربر حالت Direct را
 * انتخاب می‌کند، [DnsRaceTester] همزمان به همه‌ی این سرورها query می‌زند و سریع‌ترین
 * آن که موفق به resolve شدن شود را انتخاب می‌کند.
 *
 * ترکیبی از DNSهای گیمینگ ایرانی (که مسیرهای بهینه‌شده برای سرورهای بازی دارند)،
 * DNSهای bypass ایرانی (برای دور زدن رزامولوشن ISP) و DNSهای بین‌المللی عمومی.
 */
object GameDnsList {

    data class DnsServer(
        val ip: String,
        val name: String,        // فقط برای logcat � کاربر نمی‌بیند
        val category: DnsCategory
    )

    enum class DnsCategory { GAMING_IR, BYPASS_IR, PUBLIC_INTL }

    val servers: List<DnsServer> = listOf(
        // ── DNSهای گیمینگ ایرانی (مسیر بهینه برای سرورهای بازی) ──
        // نکته: RadarGame روی رنج 10.202.x.x یعنی IP خصوصی داخل شبکه‌ی یک اپراتور خاص است --
        // فقط وقتی دیتای موبایل همون اپراتور فعاله جواب می‌ده، نه روی وای‌فای یا اپراتور دیگه.
        DnsServer("10.202.10.10", "RadarGame-1", DnsCategory.GAMING_IR),
        DnsServer("10.202.10.11", "RadarGame-2", DnsCategory.GAMING_IR),
        DnsServer("78.157.42.100", "Electro-1", DnsCategory.GAMING_IR),
        DnsServer("78.157.42.101", "Electro-2", DnsCategory.GAMING_IR),
        // جفت تایید‌شده‌ی کاربر (تست شده و وصل شده با یه نرم‌افزار دیگه)
        DnsServer("78.157.32.202", "Electro-Verified", DnsCategory.GAMING_IR),

        // ── DNSهای bypass ایرانی (دور زدن رزامولوشن/hijack ISP) ──
        DnsServer("178.22.122.111", "Shecan-1", DnsCategory.BYPASS_IR),
        DnsServer("185.51.200.2", "Shecan-2", DnsCategory.BYPASS_IR),
        // جفت‌های تایید‌شده‌ی کاربر (تست شده و وصل شده با یه نرم‌افزار دیگه)
        DnsServer("178.22.122.100", "Shecan-Classic-1", DnsCategory.BYPASS_IR),
        DnsServer("178.22.122.101", "Shecan-Alt-1", DnsCategory.BYPASS_IR),
        DnsServer("185.51.200.1", "Shecan-Alt-2", DnsCategory.BYPASS_IR),
        DnsServer("10.202.10.202", "Begzar-1", DnsCategory.BYPASS_IR),
        DnsServer("10.202.10.102", "Begzar-2", DnsCategory.BYPASS_IR),

        // ── DNSهای بین‌المللی عمومی (fallback با پایداری بالا) ──
        DnsServer("1.1.1.1", "Cloudflare", DnsCategory.PUBLIC_INTL),
        DnsServer("8.8.8.8", "Google", DnsCategory.PUBLIC_INTL),
        DnsServer("9.9.9.9", "Quad9", DnsCategory.PUBLIC_INTL)
    )

    /** فقط IPها (برای query) */
    val ips: List<String> = servers.map { it.ip }
}
