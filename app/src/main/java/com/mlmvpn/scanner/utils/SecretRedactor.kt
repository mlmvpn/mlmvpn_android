package com.mlmvpn.scanner.utils

/**
 * Strips credential-shaped text out of anything that leaves the device or lands in a log.
 *
 * Crash reports go to a GitHub issue (see CrashReporter.upload); an exception message that
 * happened to carry a Cloudflare key or an email would otherwise travel with it. This is a last
 * line of defence, not a licence to put secrets in messages: shapes it does not know pass through.
 *
 * Pure Kotlin so it is unit-tested on the JVM.
 */
object SecretRedactor {
    private const val MASK = "‹redacted›"

    /** Native stdout is read line by line; a PEM body's lines have no field name to redact. */
    class Stream {
        private var inPem = false
        @Synchronized fun redact(line: String): String {
            if (inPem) {
                if (line.contains("-----END ")) inPem = false
                return MASK
            }
            if (line.contains("-----BEGIN ")) {
                inPem = !line.contains("-----END ")
                return MASK
            }
            return SecretRedactor.redact(line)
        }
    }

    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // PEM private material / certificates -- the whole block, including the newlines inside it.
        Regex("(?s)-----BEGIN [A-Z0-9 ]+-----.*?-----END [A-Z0-9 ]+-----") to { _ -> MASK },
        // Authorization: Bearer <token>
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}") to { m -> m.groupValues[1] + MASK },
        // X-Auth-Key / X-Auth-Email header values
        Regex("(?i)(x-auth-(?:key|email)\\s*[:=]\\s*)\\S+") to { m -> m.groupValues[1] + MASK },
        // key=value pairs in URLs, JSON or logs whose name says secret -- now also WireGuard/WARP keys,
        // PEMs, identity tokens and device ids the WARP path carries.
        Regex("(?i)([\"']?(?:token|access[_-]?token|api[_-]?key|apikey|secret|password|passwd|pass|masterkey|auth|" +
            "private[_-]?key|privatekey|wg[_-]?private[_-]?key|key[_-]?pem|cert[_-]?pem|peer[_-]?public[_-]?key|device[_-]?id)[\"']?\\s*[:=]\\s*[\"']?)([^\"'&\\s,}]{4,})") to
            { m -> m.groupValues[1] + MASK },
        // Emails
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to { _ -> MASK },
        // Cloudflare Global API Key: 37 hex chars
        Regex("(?<![A-Za-z0-9])[0-9a-fA-F]{37}(?![A-Za-z0-9])") to { _ -> MASK },
        // Cloudflare API tokens and similar 40-char opaque tokens
        Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{40}(?![A-Za-z0-9_-])") to { _ -> MASK },
        // A WireGuard/X25519 key: 32 bytes as standard base64 (43 chars + '='). Redacted wherever it sits.
        Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])") to { _ -> MASK },
        // A UUID (config ids, device ids): low-risk to mask in a log, high-risk to leak.
        Regex("(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])") to { _ -> MASK },
    )

    fun redact(text: String): String {
        var out = text
        for ((re, repl) in rules) out = re.replace(out, repl)
        return out
    }
}
