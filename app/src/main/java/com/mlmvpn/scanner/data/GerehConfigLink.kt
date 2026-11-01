package com.mlmvpn.scanner.data

import android.app.Activity
import android.content.Context
import android.util.Base64
import com.mlmvpn.scanner.ui.GerehImportActivity
import com.mlmvpn.scanner.utils.VpnConfig
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import kotlinx.coroutines.runBlocking
import org.mlmvpn.chat.bridge.GerehConfigs

/**
 * «گره»'s configs in chats (Ehsan, 28 Oct 2026, n5): the chat finds them in a message or a file,
 * measures them and hands them over through [org.mlmvpn.chat.bridge.GerehConfigs], which this fills
 * in the chat's own `:chat` process (MlmApp).
 *
 * - Found: what the app's own sections take. Share links (vless, vmess, trojan, ss, socks,
 *   hysteria2, tuic) go to «کانفیگ‌ها»; Outline keys (ssconf, ss marked outline) to Outline; a
 *   WireGuard or AmneziaWG `.conf` to Amnezia; an `.ovpn` to OpenVPN; an NPV Tunnel file to NPV. A
 *   text file may be a base64 subscription: it is read as one.
 * - Measured: the links Xray reads, in `:xmeasure` ([CombineEngine.measureDelay]) -- never in `:chat`
 *   itself, where a core's panic would close the chat (XrayHosts). The rest are measured in their
 *   own section after the move.
 * - Moved: [GerehImportActivity], in the main process, where the sections keep their lists.
 */
object GerehConfigLink {
    const val NODES = "nodes"
    const val OUTLINE = "outline"
    const val OPENVPN = "openvpn"
    const val NPV = "npv"
    const val AMNEZIA = "amnezia"

    private val LINK = Regex("""(?i)(?<![A-Za-z0-9+.-])((?:vless|vmess|trojan|ss|ssconf|socks|hysteria2|hy2|tuic)://[^\s"'<>`]+)""")
    private const val DELAY_MS = 8_000L

    fun bind(context: Context) {
        val app = context.applicationContext
        // the measurement process is reached from here too
        CombineEngine.bind(app)
        GerehConfigs.set(object : GerehConfigs.Hook {
            override fun appName() = "MLMVPN"
            override fun find(text: String, fileName: String?, filePath: String?): List<GerehConfigs.Config> = GerehConfigLink.find(app, text, fileName, filePath)
            override fun delay(c: GerehConfigs.Config): Long = measure(c)
            override fun transfer(activity: Activity, configs: List<GerehConfigs.Config>) = GerehImportActivity.start(activity, configs)
        })
    }

    private fun section(context: Context, kind: String): String = when (kind) {
        OUTLINE -> "Outline"
        OPENVPN -> "OpenVPN"
        NPV -> "NPV"
        AMNEZIA -> "آمنزیا"
        else -> com.mlmvpn.scanner.store.tr("کانفیگ‌ها", "Configs")
    }

    fun find(context: Context, text: String, fileName: String?, filePath: String?): List<GerehConfigs.Config> {
        val lower = fileName?.lowercase().orEmpty()
        fun one(kind: String, raw: String = text) = listOf(GerehConfigs.Config(kind, section(context, kind), fileName ?: kind, raw, false))
        if (lower.endsWith(".ovpn") || looksOpenVpn(text)) return one(OPENVPN)
        if (looksWireGuard(text)) return one(AMNEZIA)
        // an NPV Tunnel file is kept as it is: its path, read as bytes when it is moved
        if ((lower.endsWith(".npvt") || lower.endsWith(".npvs")) && filePath != null) return one(NPV, filePath)
        val body = if (LINK.containsMatchIn(text)) text else fromBase64(text) ?: return emptyList()
        return LINK.findAll(body)
            .map { it.groupValues[1].trimEnd(')', ',', '.', ';', ']', '}') }
            .distinct()
            .map { link(context, it) }
            .toList()
    }

    private fun link(context: Context, uri: String): GerehConfigs.Config {
        val scheme = uri.substringBefore("://").lowercase()
        val kind = if (scheme == "ssconf" || scheme == "ss" && uri.contains("outline=1")) OUTLINE else NODES
        val parsed = VpnConfig.parseUri(uri)
        val name = uri.substringAfter('#', "")
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .ifBlank { parsed?.let { "${it.address}:${it.port}" } ?: uri.take(32) }
        return GerehConfigs.Config(kind, section(context, kind), name, uri, scheme != "ssconf" && parsed?.address?.isNotBlank() == true)
    }

    /** The real delay through one link, in `:xmeasure`; 0 when nothing came back. */
    private fun measure(c: GerehConfigs.Config): Long {
        val parsed = VpnConfig.parseUri(c.raw) ?: return 0
        val json = runCatching { XrayJsonGenerator.generateSpeedtestConfig(parsed) }.getOrNull() ?: return 0
        return runBlocking { CombineEngine.measureDelay(json, timeoutMs = DELAY_MS) }
    }

    private fun looksOpenVpn(t: String) = Regex("""(?m)^\s*remote\s+\S+""").containsMatchIn(t) &&
        (t.contains("<ca>") || Regex("""(?m)^\s*client\s*$""").containsMatchIn(t))

    private fun looksWireGuard(t: String) = t.contains("[Interface]") && t.contains("PrivateKey") && t.contains("[Peer]")

    /** A subscription file: one base64 block of links. */
    private fun fromBase64(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.length < 16) return null
        val bytes = runCatching { Base64.decode(compact, Base64.DEFAULT) }.getOrNull()
            ?: runCatching { Base64.decode(compact, Base64.URL_SAFE) }.getOrNull() ?: return null
        val decoded = String(bytes, Charsets.UTF_8)
        return decoded.takeIf { LINK.containsMatchIn(it) }
    }
}
