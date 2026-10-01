package com.mlmvpn.scanner.openvpn

import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Locale

class ProfileImportException(message: String, val dependency: String? = null) : IllegalArgumentException(message)

object ProfileImporter {
    const val MAX_BYTES = 1_048_576
    private val files = setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "extra-certs")
    private val allowed = setOf("client", "dev", "dev-type", "proto", "remote", "port", "rport", "nobind",
        "remote-cert-tls", "verify-x509-name", "peer-fingerprint", "persist-key", "persist-tun", "reneg-sec",
        "dhcp-option", "redirect-gateway", "redirect-private", "route", "route-ipv6", "route-metric", "route-nopull",
        "route-delay", "route-gateway", "verb", "mute", "auth-user-pass", "auth-nocache", "data-ciphers", "cipher",
        "data-ciphers-fallback", "auth", "tls-version-min", "tls-version-max", "tls-cipher", "tls-ciphersuites",
        "tls-client", "key-direction", "resolv-retry", "connect-retry", "connect-retry-max", "connect-timeout",
        "server-poll-timeout", "remote-random", "remote-random-hostname", "explicit-exit-notify", "tun-mtu",
        "mssfix", "sndbuf", "rcvbuf", "ping", "ping-restart", "keepalive", "pull", "pull-filter",
        "topology", "ifconfig", "ifconfig-ipv6", "auth-retry", "comp-lzo", "compress", "allow-compression") + files

    fun parse(name: String, text: String, companions: Map<String, String>): Profile {
        if (text.toByteArray().size > MAX_BYTES || '\u0000' in text) throw ProfileImportException("Profile too large or invalid")
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        val out = mutableListOf<String>()
        val directives = mutableListOf<List<String>>()
        var block: String? = null
        var hasCa = false
        var control = false
        var inlineAuth = false
        var dropped = 0
        var skipping: String? = null
        for (line in lines) {
            if (skipping != null) { if (line == "</$skipping>") skipping = null; continue }
            if (block != null) {
                out += line
                if (line == "</$block>") block = null
                continue
            }
            if (line.isBlank() || line.startsWith('#') || line.startsWith(';')) continue
            if (line.startsWith('<')) {
                val tag = line.removePrefix("<").removeSuffix(">")
                if (line != "<$tag>" || tag.startsWith("/")) throw ProfileImportException("Unsupported inline section")
                if (tag == "auth-user-pass") { inlineAuth = true; block = tag; out += line; continue }
                // A block this app has no use for (<connection>, <http-proxy-user-pass>, ...):
                // skipped whole, rather than refusing a profile that works without it.
                if (tag !in files) { skipping = tag; continue }
                if (tag == "ca") hasCa = true
                if (tag.startsWith("tls-")) control = true
                block = tag
                out += line
                continue
            }
            val words = tokenize(line)
            if (words.isEmpty()) continue
            val key = words[0].removePrefix("--").lowercase()
            // Free profiles from Telegram carry tuning and client options this core does not
            // take (inactive, setenv, fast-io, mute-replay-warnings, push-peer-info, ...). One of
            // them used to refuse the whole file. They are dropped instead -- and so are scripts,
            // plugins and management options, which are never run whatever the file says.
            if (key !in allowed) { dropped++; continue }
            // A credentials FILE cannot come along; the credentials then come from an account.
            val parts = if (key == "auth-user-pass") listOf(key) else listOf(key) + words.drop(1)
            if (key == "dev" && parts.getOrNull(1)?.startsWith("tun") != true) throw ProfileImportException("Only TUN profiles are supported")
            if (key in files) {
                if (parts.size !in 2..3) throw ProfileImportException("Invalid certificate reference")
                val ref = parts[1]
                if (ref.contains('/') || ref.contains('\\') || ref.contains(':') || ref == "..") throw ProfileImportException("Select companion files by filename")
                val contents = companions[ref] ?: throw ProfileImportException("Missing companion: $ref", ref)
                if (contents.toByteArray().size > MAX_BYTES || '\u0000' in contents || contents.contains("</$key>")) throw ProfileImportException("Invalid companion file")
                if (key == "ca") {
                    try { require(CertificateFactory.getInstance("X.509").generateCertificates(contents.byteInputStream()).isNotEmpty()) }
                    catch (_: Exception) { throw ProfileImportException("Invalid CA certificate") }
                    hasCa = true
                }
                if (key.startsWith("tls-")) control = true
                val canonical = contents.trim().lineSequence().joinToString("\n") { it.trim() }
                out += "<$key>\n$canonical\n</$key>"
                if (parts.size == 3) {
                    if (key != "tls-auth" || parts[2] !in listOf("0", "1")) throw ProfileImportException("Invalid key direction")
                    out += "key-direction ${parts[2]}"
                }
            } else {
                directives += parts
                // Core diagnostics must not persist profile contents or credentials.
                if (key != "verb" && key != "mute" && key != "auth-nocache") out += parts.joinToString(" ") { quote(it) }
            }
        }
        if (block != null || skipping != null) throw ProfileImportException("Unclosed inline section")
        if (inlineAuth) {
            val auth = Profile.inlineCredentials(out.joinToString("\n"))
            if (auth == null || auth.first.isBlank()) throw ProfileImportException("Invalid inline credentials")
        }
        if (!hasCa && directives.none { it[0] == "peer-fingerprint" }) throw ProfileImportException("A CA or peer fingerprint is required")
        if (directives.none { it[0] == "remote-cert-tls" && it.getOrNull(1) == "server" } && directives.none { it[0] == "peer-fingerprint" }) {
            out += "remote-cert-tls server"
        }
        val proto = directives.lastOrNull { it[0] == "proto" }?.getOrNull(1) ?: "udp"
        val port = directives.lastOrNull { it[0] in setOf("port", "rport") }?.getOrNull(1) ?: "1194"
        val remotes = directives.filter { it[0] == "remote" }.map {
            val host = it.getOrNull(1) ?: throw ProfileImportException("Missing remote host")
            val p = (it.getOrNull(2) ?: port).toIntOrNull() ?: throw ProfileImportException("Invalid port")
            val transport = it.getOrNull(3) ?: proto
            if (p !in 1..65535 || host.any(Char::isWhitespace) || transport !in setOf("udp", "udp4", "udp6", "tcp-client", "tcp4-client", "tcp6-client", "tcp")) throw ProfileImportException("Invalid remote")
            Remote(host, p, transport)
        }
        if (remotes.isEmpty()) throw ProfileImportException("No remote server")
        val config = out.joinToString("\n") + "\nverb 0\n"
        if (config.toByteArray().size > MAX_BYTES) throw ProfileImportException("Expanded profile is too large")
        val id = MessageDigest.getInstance("SHA-256").digest(config.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
        return Profile(id, name.removeSuffix(".txt").removeSuffix(".ovpn").take(120), config, remotes, control)
    }

    private fun quote(s: String) = if (s.any { it.isWhitespace() || it in "'\"\\" } || s.startsWith('#') || s.startsWith(';'))
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else s

    private fun tokenize(line: String): List<String> {
        val result = mutableListOf<String>(); val word = StringBuilder()
        var quote: Char? = null; var escaped = false
        for (c in line) {
            if (escaped) { word.append(c); escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (quote != null) { if (c == quote) quote = null else word.append(c); continue }
            if (c == '\'' || c == '"') { quote = c; continue }
            if ((c == '#' || c == ';') && word.isEmpty()) break
            if (c.isWhitespace()) { if (word.isNotEmpty()) { result += word.toString(); word.clear() } }
            else word.append(c)
        }
        if (quote != null || escaped) throw ProfileImportException("Invalid quoting")
        if (word.isNotEmpty()) result += word.toString()
        return result
    }
}
