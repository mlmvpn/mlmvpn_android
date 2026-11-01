package com.mlmvpn.scanner.engines.cfnet

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a [ShapeSpec] into edits on an Xray `streamSettings` object's TLS block. This is the one
 * place the DATA-layer handshakes are defined, so the engine and the «رفع فیلتر» switch
 * ([com.mlmvpn.scanner.utils.CfUnfilter], which delegates here) cannot drift apart. Pure (org.json
 * only), and it takes primitives rather than a `VpnConfig`, so it runs under the pure test runner.
 *
 * What each shape sets on `tlsSettings`:
 *  - CHROME: fingerprint chrome, nothing else (the uTLS Chrome ClientHello, no fragment).
 *  - FRAG_FP: fingerprint `unsafe` + PattNG's semi-python cipher list + the tlshello finalmask, so
 *    the core dials with Go's TLS (where the list counts) and cuts the first flight. ALPN is written
 *    explicitly because `unsafe` offers it as-is.
 *  - FRAG_CHROME: fingerprint chrome + the SAME tlshello finalmask -- fragmented, but a uTLS Chrome
 *    ClientHello rather than Go's. A genuinely different handshake from FRAG_FP.
 *  - ECH: fingerprint chrome + echConfigList, no finalmask (the screenshot). The list is Cloudflare's
 *    key when the app fetched one ([echValue]), else `cloudflare-ech.com+udp://1.1.1.1`, with which
 *    the core asks 1.1.1.1 over UDP for the key itself.
 *  - AS_IS: nothing -- the config's own handshake, proven as written.
 *  - FIREFOX / SAFARI / RANDOMIZED / IOS / EDGE / ANDROID: that uTLS fingerprint, nothing else.
 *  - XHTTP_*: for an XHTTP config only -- the HTTP version (one-entry ALPN) and the XHTTP mode.
 *  - MASQUE_H2 / WARP / CHAIN_*: not an Xray TLS shape -- [apply] returns false and the engine starts
 *    a different core instead.
 */
object CfShape {

    const val FINGERPRINT_UNSAFE = "unsafe"

    /** PattNG's `semi-python-cipherSuites`, as the field holds it (Go's names joined by ':'). */
    const val CIPHER_SUITES =
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:" +
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:" +
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"

    /** The ECH outer name + resolver this app uses, matching the EDG worker's export and the screenshot. */
    const val ECH_DEFAULT = "cloudflare-ech.com+udp://1.1.1.1"

    /**
     * Cloudflare's current ECH key (base64) when the app fetched one on this line, else null. Set by
     * the Android side ([CfEchKey]) so this file stays pure. With the key itself in the config the core
     * never asks 1.1.1.1 over UDP at dial time -- which some lines drop, and then ECH read as "does not
     * work" while ECH itself was fine.
     */
    @Volatile var echKeyProvider: () -> String? = { null }

    /** What the ECH shape writes into `echConfigList`: the fetched key, else [ECH_DEFAULT]. */
    fun echValue(): String = runCatching { echKeyProvider() }.getOrNull()?.takeIf { it.isNotBlank() } ?: ECH_DEFAULT

    /**
     * A link's own `ech` as the core should get it: Cloudflare's short form («cloudflare-ech.com+…»)
     * becomes the fetched key when there is one; anything else (a key, another name) stays as written.
     * The link itself keeps the short form: the key rotates, and a stored link must not go stale.
     */
    fun upgradeEch(value: String): String =
        if (value.trim().startsWith("cloudflare-ech.com+", ignoreCase = true)) echValue() else value

    private val HTTP1_ONLY = setOf("ws", "httpupgrade")

    /**
     * The names a link may carry instead of the full `cs` / `fm` value. The developer's client writes
     * them and so does the Windows app (its cf-methods and the central Worker's method links), so a
     * link copied from there must work here: without this a `cs=semi-python` reached the core as a
     * cipher list nobody knows, and `fm=tlshello-0-len` was dropped as "not JSON".
     */
    const val CS_PRESET = "semi-python"
    const val FM_PRESET = "tlshello-0-len"
    const val FM_PRESET_V1 = "tlshello-5-94-1"

    /** PattNG's finalMask preset `tlshello-0-len`: an empty first record, 104 bytes, then the rest cut again. */
    fun finalMaskTlsHello(): JSONObject = JSONObject().put(
        "tcp",
        JSONArray()
            .put(fragment("tlshello", listOf("0", "104", "1"), listOf("0"), "0"))
            .put(fragment("1-1", listOf("114", "1"), listOf("1"), "11")),
    )

    /** The older F&F mask, `tlshello-5-94-1`: the first record cut at 5, then 94, then the rest. */
    fun finalMaskTlsHelloV1(): JSONObject = JSONObject().put(
        "tcp",
        JSONArray()
            .put(fragment("tlshello", listOf("5", "94", "1"), listOf("0"), "0"))
            .put(fragment("1-1", listOf("109", "1"), listOf("1"), "355")),
    )

    /** A link's `cs`: the preset name expanded to the cipher list, anything else exactly as written. */
    fun resolveCipherSuites(value: String?): String {
        val v = value?.trim().orEmpty()
        return if (v.equals(CS_PRESET, ignoreCase = true)) CIPHER_SUITES else v
    }

    /** A link's `fm`: a preset name or a JSON object as a finalmask; null for anything else (a typing mistake is no failure). */
    fun resolveFinalMask(value: String?): JSONObject? {
        val v = value?.trim().orEmpty()
        return when {
            v.equals(FM_PRESET, ignoreCase = true) -> finalMaskTlsHello()
            v.equals(FM_PRESET_V1, ignoreCase = true) -> finalMaskTlsHelloV1()
            v.startsWith("{") -> runCatching { JSONObject(v) }.getOrNull()
            else -> null
        }
    }

    private fun fragment(packets: String, lengths: List<String>, delays: List<String>, maxSplit: String): JSONObject =
        JSONObject().put("type", "fragment").put(
            "settings",
            JSONObject().put("packets", packets).put("lengths", JSONArray(lengths)).put("delays", JSONArray(delays)).put("maxSplit", maxSplit),
        )

    /**
     * Apply [spec]'s TLS shape to [stream] (which must already have a `tlsSettings` -- i.e. a TLS, not
     * REALITY, outbound). [network] is the transport ("ws"/"xhttp"/...), used for the ALPN rule.
     * Returns true when a shape was applied; false for the non-TLS engine shapes (MASQUE/WARP/chains).
     */
    fun apply(stream: JSONObject, spec: ShapeSpec, network: String): Boolean {
        val tls = stream.optJSONObject("tlsSettings") ?: return false
        return when (spec.shape) {
            // The config's own handshake: proven as it is, so nothing is changed.
            TunnelShape.AS_IS -> true
            TunnelShape.CHROME -> { applyFingerprint(tls, "chrome"); clearFragAndEch(stream, tls); true }
            TunnelShape.FRAG_FP -> { applyFragFp(stream, tls, network); true }
            TunnelShape.FRAG_CHROME -> { applyFingerprint(tls, "chrome"); tls.remove("echConfigList"); stream.put("finalmask", finalMaskTlsHello()); true }
            TunnelShape.ECH -> { applyEch(tls, echValue()); stream.remove("finalmask"); true }
            TunnelShape.FIREFOX, TunnelShape.SAFARI, TunnelShape.RANDOMIZED,
            TunnelShape.IOS, TunnelShape.EDGE, TunnelShape.ANDROID -> {
                applyFingerprint(tls, spec.shape.key); clearFragAndEch(stream, tls); true
            }
            TunnelShape.XHTTP_H2 -> applyXhttp(stream, tls, network, "h2", "packet-up", fragment = false)
            TunnelShape.XHTTP_H2_STREAM -> applyXhttp(stream, tls, network, "h2", "stream-up", fragment = false)
            TunnelShape.XHTTP_H11 -> applyXhttp(stream, tls, network, "http/1.1", "packet-up", fragment = false)
            TunnelShape.XHTTP_H2_FRAG -> applyXhttp(stream, tls, network, "h2", "packet-up", fragment = true)
            TunnelShape.MASQUE_H2, TunnelShape.WARP, TunnelShape.CHAIN_TOR, TunnelShape.CHAIN_PSIPHON -> false
        }
    }

    /**
     * XHTTP tuning. Xray picks the HTTP version an XHTTP outbound dials from a single-entry ALPN
     * (`http/1.1` → HTTP/1.1, otherwise HTTP/2), so the ALPN is written as exactly one value, and
     * the mode beside it: `packet-up` (POST per chunk, works through any CDN, the builder's default
     * for Cloudflare) or `stream-up` (one long upload, only where the CDN streams request bodies).
     * uTLS chrome keeps the ClientHello a browser's. False -- nothing applied -- for a config whose
     * transport is not XHTTP: the transport is the server's, it cannot be switched from here.
     */
    private fun applyXhttp(stream: JSONObject, tls: JSONObject, network: String, alpn: String, mode: String, fragment: Boolean): Boolean {
        if (network !in ShapeSpec.XHTTP_NETWORKS) return false
        applyFingerprint(tls, "chrome")
        tls.remove("echConfigList")
        tls.put("alpn", JSONArray().put(alpn))
        val key = if (stream.has("splithttpSettings") && !stream.has("xhttpSettings")) "splithttpSettings" else "xhttpSettings"
        val x = stream.optJSONObject(key) ?: JSONObject().also { stream.put(key, it) }
        x.put("mode", mode)
        if (fragment) stream.put("finalmask", finalMaskTlsHello()) else stream.remove("finalmask")
        return true
    }

    private fun applyFingerprint(tls: JSONObject, fp: String) {
        tls.put("fingerprint", fp)
        tls.remove("cipherSuites")   // uTLS fingerprints ignore the list; leaving it is misleading
    }

    private fun clearFragAndEch(stream: JSONObject, tls: JSONObject) {
        stream.remove("finalmask"); tls.remove("echConfigList")
    }

    /** The PattNG fragment + fingerprint handshake. Shared with [com.mlmvpn.scanner.utils.CfUnfilter]. */
    fun applyFragFp(stream: JSONObject, tls: JSONObject, network: String) {
        tls.put("fingerprint", FINGERPRINT_UNSAFE)
        tls.put("cipherSuites", CIPHER_SUITES)
        tls.remove("echConfigList")
        // `unsafe` offers the ALPN exactly as written (uTLS profiles add h2 themselves), so it has to be set.
        if (network in HTTP1_ONLY) tls.put("alpn", JSONArray().put("http/1.1"))
        else if (network == "xhttp" && (tls.optJSONArray("alpn")?.length() ?: 0) == 0) tls.put("alpn", JSONArray().put("h2").put("http/1.1"))
        if (!stream.has("finalmask")) stream.put("finalmask", finalMaskTlsHello())
    }

    /** The ECH handshake: chrome fingerprint, the ECH config list, and no finalmask. Shared with CfUnfilter. */
    fun applyEch(tls: JSONObject, echConfigList: String) {
        tls.put("fingerprint", "chrome")
        tls.remove("cipherSuites")
        tls.put("echConfigList", echConfigList)
    }

    /**
     * The link parameters that make a config carry [spec]'s handshake by itself: `ech`, PattNG's `cs`
     * and `fm`, `fp`, and for XHTTP `alpn` and `mode`. A null value removes the parameter.
     *
     * Written by a combine into each config it builds on an address the scan proved with [spec]
     * (`CombineEngine.withParams`), so the health test, the connect and any other client dial that
     * address with the handshake that found it, whatever the strategy store holds later. Empty for
     * AS_IS (the link's own handshake is what passed), for an XHTTP tuning on a transport that is not
     * XHTTP, and for the shapes that are not an Xray TLS handshake.
     */
    fun linkParams(spec: ShapeSpec, network: String): Map<String, String?> {
        val mask = finalMaskTlsHello().toString()
        val plain = mapOf<String, String?>("ech" to null, "cs" to null, "fm" to null)
        val xhttp = network in ShapeSpec.XHTTP_NETWORKS
        return when (spec.shape) {
            TunnelShape.AS_IS, TunnelShape.MASQUE_H2, TunnelShape.WARP,
            TunnelShape.CHAIN_TOR, TunnelShape.CHAIN_PSIPHON -> emptyMap()
            TunnelShape.CHROME, TunnelShape.FIREFOX, TunnelShape.SAFARI, TunnelShape.RANDOMIZED,
            TunnelShape.IOS, TunnelShape.EDGE, TunnelShape.ANDROID -> plain + ("fp" to spec.shape.key)
            // `cs` is what turns the `unsafe` fingerprint on (CfUnfilter.shape), and with it the ALPN
            // a WebSocket transport needs; the link's own `fp` stays for clients that ignore `cs`.
            TunnelShape.FRAG_FP -> mapOf("ech" to null, "cs" to CIPHER_SUITES, "fm" to mask)
            TunnelShape.FRAG_CHROME -> mapOf("ech" to null, "cs" to null, "fm" to mask, "fp" to "chrome")
            TunnelShape.ECH -> mapOf("ech" to ECH_DEFAULT, "cs" to null, "fm" to null, "fp" to "chrome")
            TunnelShape.XHTTP_H2 -> if (!xhttp) emptyMap() else plain + mapOf("fp" to "chrome", "alpn" to "h2", "mode" to "packet-up")
            TunnelShape.XHTTP_H2_STREAM -> if (!xhttp) emptyMap() else plain + mapOf("fp" to "chrome", "alpn" to "h2", "mode" to "stream-up")
            TunnelShape.XHTTP_H11 -> if (!xhttp) emptyMap() else plain + mapOf("fp" to "chrome", "alpn" to "http/1.1", "mode" to "packet-up")
            TunnelShape.XHTTP_H2_FRAG -> if (!xhttp) emptyMap() else
                mapOf("ech" to null, "cs" to null, "fm" to mask, "fp" to "chrome", "alpn" to "h2", "mode" to "packet-up")
        }
    }

    /**
     * The family of [address] when it is a literal IP -- what a scanned clean IP, and every config a
     * combine builds on one, dials -- or null for a name.
     */
    fun literalFamily(address: String): Family? {
        val a = address.trim().removeSurrounding("[", "]")
        if (a.isEmpty()) return null
        if (a.contains(':')) {
            return if (a.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) Family.V6 else null
        }
        val parts = a.split('.')
        return if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.toIntOrNull()?.let { it in 0..255 } == true }) Family.V4 else null
    }
}
