package com.mlmvpn.scanner.utils

import com.mlmvpn.scanner.engines.flux.core.net.Cloudflare
import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies explicit TLS settings carried by a config. Automatic per-network strategies are
 * selected and applied separately by CfNetManager after real validation.
 */
object CfUnfilter {

    const val FINGERPRINT = com.mlmvpn.scanner.engines.cfnet.CfShape.FINGERPRINT_UNSAFE

    /** PattNG's `semi-python-cipherSuites`. Defined once in [com.mlmvpn.scanner.engines.cfnet.CfShape]. */
    const val CIPHER_SUITES = com.mlmvpn.scanner.engines.cfnet.CfShape.CIPHER_SUITES

    /** Transports that speak HTTP/1.1 only after the handshake: an `h2` offered to Cloudflare would be picked and then fail. */
    private val HTTP1_ONLY = setOf("ws", "httpupgrade")

    /** Transports a Worker or a proxied site is reached over, which is what a Cloudflare config is in practice. */
    private val CDN_TRANSPORTS = setOf("ws", "websocket", "httpupgrade", "xhttp", "splithttp")

    /** PattNG's finalMask preset `tlshello-0-len`. Defined once in [com.mlmvpn.scanner.engines.cfnet.CfShape]. */
    fun finalMask(): JSONObject = com.mlmvpn.scanner.engines.cfnet.CfShape.finalMaskTlsHello()

    /**
     * Whether [config] is reached through Cloudflare: a workers.dev or pages.dev name, a literal
     * address in Cloudflare's own ranges (IPv4 or IPv6, which is what a scanned clean address is),
     * or a Worker-style transport over TLS to a name of the user's own. Only TLS: REALITY is another
     * handshake with another fingerprint, and a config with no security has none to shape.
     */
    fun isCloudflareFronted(config: VpnConfig): Boolean {
        if (!config.tls.equals("tls", ignoreCase = true)) return false
        val names = listOf(config.sni, config.wsHost, config.xhttpHost, config.address).map { it.trim().lowercase() }
        if (names.any { it.endsWith(".workers.dev") || it.endsWith(".pages.dev") }) return true
        if (Cloudflare.isCloudflare(config.address.trim().removeSurrounding("[", "]"))) return true
        return false
    }

    /**
     * A config that dials the app's own local front (the SNI engine on 127.0.0.1:40443) rather
     * than Cloudflare directly. It still names a worker, so it reads as Cloudflare-fronted, but the
     * edge address, the port and the handshake trick are the FRONT's job: a learned Cloudflare
     * strategy that swaps the address or port for an edge IP skips the front altogether, and one
     * that reshapes the ClientHello stacks a second trick on top of the front's. Left as written.
     */
    fun isLocalFront(config: VpnConfig): Boolean =
        config.address.trim().removeSurrounding("[", "]").lowercase().let { it == "127.0.0.1" || it == "localhost" || it == "::1" }

    /**
     * The ClientHello a config sends INTO the SNI front: small, so the front's split lands inside
     * the server name.
     *
     * The front reads the whole ClientHello and re-sends it cut by its method (in half, in chunks,
     * a fake record first). That worked while the hello was ~500 bytes. Xray's `chrome` fingerprint
     * now carries the post-quantum key share and the hello is 1,750-1,810 bytes (device log,
     * 2026-10-05): «half» made two ~900-byte pieces with the name whole inside the first, «chunk»
     * sent it in ONE piece, and every method carried 0 bytes. Safari's profile has no
     * post-quantum share -- a classic ~500-byte hello -- so the same methods split the name again.
     * ECH and the link's own handshake overrides are dropped for the same reason: the front, not
     * the core, is what gets this config past the filter.
     */
    fun shapeForLocalFront(stream: JSONObject) {
        val tls = stream.optJSONObject("tlsSettings") ?: return
        tls.put("fingerprint", LOCAL_FRONT_FINGERPRINT)
        tls.remove("echConfigList")
        tls.remove("cipherSuites")
        stream.remove("finalmask")
    }

    const val LOCAL_FRONT_FINGERPRINT = "safari"

    fun isCloudflareOutbound(outbound: JSONObject): Boolean {
        val stream = outbound.optJSONObject("streamSettings") ?: return false
        if (stream.optString("security") != "tls") return false
        val settings = outbound.optJSONObject("settings")
        val servers = settings?.optJSONArray("vnext") ?: settings?.optJSONArray("servers")
        val address = servers?.optJSONObject(0)?.optString("address").orEmpty()
        return isCloudflareFronted(VpnConfig(address = address,
            sni = stream.optJSONObject("tlsSettings")?.optString("serverName").orEmpty(),
            wsHost = stream.optJSONObject("wsSettings")?.optJSONObject("headers")?.optString("Host").orEmpty(),
            xhttpHost = stream.optJSONObject("xhttpSettings")?.optString("host").orEmpty()))
    }

    /**
     * [text] as a finalMask object, or null when it is blank or neither a JSON object nor a preset name
     * (`tlshello-0-len`, as the developer's client and the Windows app write it) — a typing mistake is no failure.
     */
    fun parseFinalMask(text: String?): JSONObject? = com.mlmvpn.scanner.engines.cfnet.CfShape.resolveFinalMask(text)

    /**
     * Shapes [stream], the `streamSettings` the shared builder made for [config].
     *
     * [global] is the app's «رفع فیلتر» switch. With it on and [config] Cloudflare-fronted, the
     * automatic strategy is handled by CfNetManager; explicit `cs` and `fm` are applied either way.
     */
    fun shape(stream: JSONObject, config: VpnConfig, global: Boolean) {
        if (stream.optString("security") != "tls") return
        val tls = stream.optJSONObject("tlsSettings") ?: return

        // ECH is an alternative to the fragment handshake: a link (or the ECH strategy) that carries
        // its own `ech` gets chrome + the ECH config list + no finalmask -- the screenshot's values --
        // regardless of the «رفع فیلتر» switch. The link's own `fm`, if any, is still honoured.
        // Cloudflare's short form becomes the key the app fetched, when it has one (CfEchKey).
        if (config.ech.isNotBlank()) {
            com.mlmvpn.scanner.engines.cfnet.CfShape.applyEch(tls, com.mlmvpn.scanner.engines.cfnet.CfShape.upgradeEch(config.ech.trim()))
            parseFinalMask(config.finalMask)?.let { stream.put("finalmask", it) }
            return
        }

        val suites = com.mlmvpn.scanner.engines.cfnet.CfShape.resolveCipherSuites(config.cipherSuites)
        if (suites.isNotEmpty()) {
            // cipherSuites only count when the core dials with Go's TLS, which is `unsafe`.
            tls.put("fingerprint", FINGERPRINT)
            tls.put("cipherSuites", suites)
        }

        if (tls.optString("fingerprint").equals(FINGERPRINT, ignoreCase = true)) {
            val network = stream.optString("network")
            // The unsafe fingerprint offers the ALPN exactly as written (the browser profiles add h2 on
            // their own), so what a transport needs has to be written.
            if (network in HTTP1_ONLY) tls.put("alpn", JSONArray().put("http/1.1"))
            else if (network == "xhttp" && (tls.optJSONArray("alpn")?.length() ?: 0) == 0) tls.put("alpn", JSONArray().put("h2").put("http/1.1"))
        }

        val own = parseFinalMask(config.finalMask)
        when {
            own != null -> stream.put("finalmask", own)
        }
    }
}
