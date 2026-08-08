package com.mlmvpn.core.aether

/**
 * Stage enum and parser for the Aether engine.
 *
 * Mirrors the STAGE_RULES / NOTE_RULES / NOISE_RE / extractDetail logic from
 * aether-manager.js (the desktop driver). The Rust binary at assets/aether/<abi>/aether
 * emits log lines in a stable format; we map them into a finite set of stages the UI can
 * render as a step-by-step progress indicator (one per protocol).
 *
 * Notes on parity with the desktop:
 *   - The stage list is the same. Desktop and Android both translate the engine's noisy
 *     log stream into a small vocabulary, so the user sees identical steps on both
 *     platforms.
 *   - "provision" is treated as "identity" for UI purposes — same value, different name
 *     in the log. The "reconnecting" stage is shown as "tunnel" because from the user's
 *     point of view the tunnel is mid-flight.
 *   - The "failed"/"crashed" terminal stages collapse to null so the step list doesn't
 *     mark any step as finished when the engine gave up.
 */
enum class AetherStage(val fa: String) {
    IDLE("خاموش"),
    STARTING("در حال راه‌اندازی موتور"),
    IDENTITY("هویت"),
    QUICKCHECK("بررسی سرور قبلی"),
    SCAN("اسکن سرور"),
    SELECTED("انتخاب سرور"),
    HANDSHAKE("دست‌دادن"),
    TUNNEL("برقراری تونل"),
    VALIDATE("اعتبارسنجی عبور داده"),
    CONNECTED("متصل شد"),
    RECONNECTING("قطع شد — اتصال مجدد"),
    STOPPED("متوقف شد"),
    FAILED("خطا"),
    CRASHED("کرش موتور");

    /** Collapse semantics: provision≈identity, reconnecting≈tunnel, terminal stages don't advance. */
    fun forUi(): String? = when (this) {
        STARTING -> "identity"
        RECONNECTING -> "tunnel"
        IDLE, STOPPED, FAILED, CRASHED -> null
        else -> name.lowercase()
    }
}

/**
 * Parsed detail extracted from a single log line. The engine's log lines embed the things
 * we want to display (selected IP:port + RTT, chosen edge, obfuscation profile, listening
 * SOCKS endpoint) so the detail layer is just a handful of regexes.
 */
data class AetherLogDetail(
    val server: String? = null,        // "162.159.193.10:2408"
    val rtt: String? = null,           // "117ms" or whatever the engine writes
    val profile: String? = null,       // obfuscation profile name (firewall, balanced, ...)
    val socks: String? = null,         // "127.0.0.1:20810"
)

object AetherStageParser {

    // Stage rules. Most-specific first; first regex match wins. The desktop copy of these
    // rules lives in aether-manager.js — keep them in sync when adding a new rule.
    private val STAGE_RULES = listOf(
        Triple(Regex("""no (warp|masque) identity found|provisioning dedicated""", RegexOption.IGNORE_CASE),
            AetherStage.IDENTITY, "ساخت هویت جدید روی کلادفلر"),
        Triple(Regex("""enrolling masque key""", RegexOption.IGNORE_CASE),
            AetherStage.IDENTITY, "ثبت کلید MASQUE"),
        Triple(Regex("""loaded existing (warp|masque) identity""", RegexOption.IGNORE_CASE),
            AetherStage.IDENTITY, "هویت ذخیره‌شده بارگذاری شد"),
        Triple(Regex("""identity ready: device=(\S+)""", RegexOption.IGNORE_CASE),
            AetherStage.IDENTITY, "هویت آماده است"),
        Triple(Regex("""verifying cached gateway|verifying cached wireguard endpoint""", RegexOption.IGNORE_CASE),
            AetherStage.QUICKCHECK, "بررسی گیت‌وی قبلی"),
        Triple(Regex("""cached (gateway|endpoint) .* still works""", RegexOption.IGNORE_CASE),
            AetherStage.QUICKCHECK, "گیت‌وی قبلی سالم است — بدون اسکن"),
        Triple(Regex("""cached (gateway|endpoint) .* no longer works""", RegexOption.IGNORE_CASE),
            AetherStage.SCAN, "گیت‌وی قبلی از کار افتاده — اسکن مجدد"),
        Triple(Regex("""candidate ok (\S+):(\d+)""", RegexOption.IGNORE_CASE),
            AetherStage.SCAN, "سرور سالم پیدا شد"),
        Triple(Regex("""hunting for a working masque gateway""", RegexOption.IGNORE_CASE),
            AetherStage.SCAN, "جستجوی گیت‌وی MASQUE"),
        Triple(Regex("""hunting for a working wireguard endpoint""", RegexOption.IGNORE_CASE),
            AetherStage.SCAN, "جستجوی اندپوینت WireGuard"),
        Triple(Regex("""selected masque gateway (\S+):(\d+)""", RegexOption.IGNORE_CASE),
            AetherStage.SELECTED, "گیت‌وی انتخاب شد"),
        Triple(Regex("""selected wireguard endpoint (\S+):(\d+)""", RegexOption.IGNORE_CASE),
            AetherStage.SELECTED, "اندپوینت انتخاب شد"),
        Triple(Regex("""using forced peer""", RegexOption.IGNORE_CASE),
            AetherStage.SELECTED, "استفاده از سرور دستی"),
        Triple(Regex("""using cloudflare edge""", RegexOption.IGNORE_CASE),
            AetherStage.SELECTED, "اتصال به لبه‌ی کلادفلر"),
        Triple(Regex("""confirming wireguard handshake""", RegexOption.IGNORE_CASE),
            AetherStage.HANDSHAKE, "دست‌دادن WireGuard"),
        Triple(Regex("""handshake successful""", RegexOption.IGNORE_CASE),
            AetherStage.HANDSHAKE, "دست‌دادن موفق"),
        Triple(Regex("""establishing outer warp tunnel""", RegexOption.IGNORE_CASE),
            AetherStage.HANDSHAKE, "برقراری تونل بیرونی"),
        Triple(Regex("""establishing inner warp tunnel""", RegexOption.IGNORE_CASE),
            AetherStage.HANDSHAKE, "برقراری تونل داخلی (تونل در تونل)"),
        Triple(Regex("""masque transport: http/3""", RegexOption.IGNORE_CASE),
            AetherStage.TUNNEL, "تونل MASQUE روی HTTP/3 (QUIC)"),
        Triple(Regex("""masque transport: http/2""", RegexOption.IGNORE_CASE),
            AetherStage.TUNNEL, "تونل MASQUE روی HTTP/2 (TCP)"),
        Triple(Regex("""validating masque data-plane""", RegexOption.IGNORE_CASE),
            AetherStage.VALIDATE, "اعتبارسنجی عبور واقعی داده"),
        Triple(Regex("""masque tunnel validated""", RegexOption.IGNORE_CASE),
            AetherStage.VALIDATE, "عبور داده تأیید شد"),
        Triple(Regex("""inner endpoint tunneled through outer warp""", RegexOption.IGNORE_CASE),
            AetherStage.HANDSHAKE, "تونل داخلی از دل تونل بیرونی رد شد"),
        Triple(Regex("""socks5 (server )?listening on""", RegexOption.IGNORE_CASE),
            AetherStage.CONNECTED, "متصل شد — پراکسی آماده است"),
        Triple(Regex("""tunnel (closed|ended|exited).*(reconnect)""", RegexOption.IGNORE_CASE),
            AetherStage.RECONNECTING, "قطع شد — اتصال مجدد"),
        Triple(Regex("""no usable masque gateway found""", RegexOption.IGNORE_CASE),
            AetherStage.SCAN, "گیت‌وی سالمی پیدا نشد — اسکن مجدد"),
        Triple(Regex("""found no data-plane endpoint.*trying next profile""", RegexOption.IGNORE_CASE),
            AetherStage.SCAN, "این پروفایل جواب نداد — پروفایل بعدی"),
        Triple(Regex("""prober: no clean endpoint found|no clean endpoint found""", RegexOption.IGNORE_CASE),
            AetherStage.FAILED, "هیچ اندپوینت سالمی پیدا نشد"),
        Triple(Regex("""handshake failed""", RegexOption.IGNORE_CASE),
            AetherStage.FAILED, "دست‌دادن ناموفق"),
    )

    // Per-packet chatter that drops to ~zero diagnostic value at debug level. Counting
    // these instead of printing keeps the log readable on a noisy connection.
    // `tls verification: pin-based` is emitted once per TLS connection attempt. During a
    // scan that is once per probed candidate — thousands of identical lines in under a
    // minute, all saying the same thing. Left unfiltered it dominated the log stream badly
    // enough to slow the reader, and a slow reader fills the child's 64KB stdout pipe and
    // blocks the scanner itself on write.
    private val NOISE_RE = Regex("""\] (junk(\[\d+\]|_after\[\d+\])? sent|signature i\d sent|sending \d+ junk|obfuscation pre-handshake complete|verify recv \d+ bytes|recv \d+ bytes (from|type=)|tls verification: )""")

    fun isNoise(line: String): Boolean = NOISE_RE.containsMatchIn(line)

    /**
     * Per-candidate rejection lines from `aether::prober` (TRACE level), e.g.
     *   `probe 162.159.192.1:443 -> timed out`
     *   `h2 probe 162.159.192.1:443 -> connection refused`
     *
     * One of these fires for every failed candidate — thousands per scan — so they are
     * never logged individually. [probeFailureReason] extracts just the error text so the
     * engine can count reasons and report a histogram instead. That histogram is the
     * difference between "the network drops our UDP" and "we never even got that far".
     */
    private val PROBE_FAIL_RE =
        Regex("""\b(?:h2 )?probe \S+ -> (.+)$""", RegexOption.IGNORE_CASE)

    fun probeFailureReason(line: String): String? =
        PROBE_FAIL_RE.find(line)?.groupValues?.get(1)?.trim()?.take(80)

    /**
     * @return null if no rule matched (no stage change); otherwise the matched stage + the
     * human-readable label, so the caller can paint the step list without needing to
     * re-resolve aliases.
     */
    fun classify(line: String): Pair<AetherStage, String>? {
        for ((re, stage, fa) in STAGE_RULES) {
            if (re.containsMatchIn(line)) return stage to fa
        }
        return null
    }

    /**
     * Pull fields we want pinned into state: selected IP, RTT, profile, listening socks.
     * The same regex set is in aether-manager.js; keep both copies in lock-step.
     */
    fun extractDetail(line: String): AetherLogDetail {
        val selected = Regex("""selected (?:MASQUE gateway|WireGuard endpoint) (\S+?):(\d+)(?:.*rtt ([^)]+))?""", RegexOption.IGNORE_CASE).find(line)
        if (selected != null) {
            val host = selected.groupValues[1]
            val port = selected.groupValues[2]
            val rtt = selected.groupValues[3].ifEmpty { null }
            return AetherLogDetail(server = "$host:$port", rtt = rtt)
        }
        val edge = Regex("""using cloudflare edge (\S+)""", RegexOption.IGNORE_CASE).find(line)
        if (edge != null) return AetherLogDetail(server = edge.groupValues[1])
        val prof = Regex("""obfuscation profile: (\S+)|aethernoize (?:primary )?profile: (\S+)""", RegexOption.IGNORE_CASE).find(line)
        if (prof != null) return AetherLogDetail(profile = (prof.groupValues[1].ifEmpty { prof.groupValues[2] }))
        val socks = Regex("""socks5 (?:server )?listening on (\S+)""", RegexOption.IGNORE_CASE).find(line)
        if (socks != null) return AetherLogDetail(socks = socks.groupValues[1])
        return AetherLogDetail()
    }

    /**
     * Distinguish a Rust panic (real crash, needs the backtrace file) from a clean error
     * exit. The CLI prints "panicked at" and "RUST_BACKTRACE" markers that we can match.
     */
    fun isPanicLine(line: String): Boolean =
        Regex("""panicked at|stack backtrace|RUST_BACKTRACE""", RegexOption.IGNORE_CASE).containsMatchIn(line)

    fun isApiFailLine(line: String): Boolean =
        Regex("""Api\(.*cloudflareclient\.com""", RegexOption.IGNORE_CASE).containsMatchIn(line)
}
