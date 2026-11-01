package com.mlmvpn.core.aether

import android.content.Context
import org.json.JSONObject

/**
 * How a MASQUE-carried Aether tunnel reaches Cloudflare: chosen by the app, remembered per network.
 *
 * MASQUE -- and gool, which since Aether 2.3.0 carries its WireGuard hop inside a MASQUE tunnel and
 * so leaves from a foreign address -- rides either HTTP/3 (QUIC, UDP 443) or HTTP/2 (TCP 443).
 * Which one a line lets through is a property of the line: a carrier that drops QUIC needs H2, and
 * on Irancell-type lines that read the server name from one TCP packet H2 needs its ClientHello
 * split as well (Aether's `--fragment`, at its default sizes: the developer's advice for exactly
 * that case). Nothing on screen asked the user which; the app used to try H3 and fall back to H2
 * once, and forgot the answer.
 *
 * Now [AetherTunEngine] walks [ladder] on the first connect of a network, keeps the carrier that
 * really carried data (its own 204 + download + echoed upload check), and starts with it the next
 * time, without testing the others again. A remembered carrier that stops working is forgotten and
 * the ladder runs again.
 *
 * The last two rungs are PattNG's for Irancell-type filters (Aether 2.4.0 takes them): the
 * semi-python ClientHello ([AetherTls.SEMI_PYTHON]) with the fragment, and the same ClientHello
 * dialled out through a local Xray that cuts it into TLS records ([AetherXrayExit], tlshello).
 */
enum class AetherCarrier(
    val key: String,
    val transport: String,
    val fragment: Boolean,
    val label: String,
    val tls: AetherTls = AetherTls.CHROME,
    val tlsHelloExit: Boolean = false,
) {
    H3("h3", "h3", false, "HTTP/3"),
    H2("h2", "h2", false, "HTTP/2"),
    H2_FRAGMENT("h2frag", "h2", true, "HTTP/2 + fragment"),
    H2_SEMI_PYTHON("h2semipy", "h2", true, "HTTP/2 + semi-python + fragment", AetherTls.SEMI_PYTHON),
    H2_TLSHELLO("h2tlshello", "h2", false, "HTTP/2 + semi-python + tlshello", AetherTls.SEMI_PYTHON, tlsHelloExit = true);

    /** [opts] riding this carrier: the fragment at the user's sizes if they set any, else the engine's defaults. */
    fun applyTo(opts: AetherOptions): AetherOptions =
        opts.copy(transport = transport, fragment = fragment,
            fragmentSize = opts.fragmentSize.takeIf { fragment }, fragmentDelay = opts.fragmentDelay.takeIf { fragment },
            tls = tls, tlsHelloExit = tlsHelloExit)

    companion object {
        /** The transport value that asks for this choice to be made automatically. */
        const val AUTO = "auto"

        fun byKey(key: String?): AetherCarrier? = values().firstOrNull { it.key == key }

        /** The order to try on a network: what worked there last first, then the rest as listed. */
        fun ladder(remembered: AetherCarrier?): List<AetherCarrier> =
            (listOfNotNull(remembered) + values()).distinct()

        /** Whether [protocol] rides MASQUE, so has a carrier to choose. */
        fun applies(protocol: AetherProtocol): Boolean =
            protocol == AetherProtocol.MASQUE || protocol == AetherProtocol.WARP_IN_WARP
    }
}

/** Per network (hashed key, never an SSID) and per protocol: the carrier that last carried data. */
object AetherCarrierMemory {
    private const val PREFS = "aether_carrier"

    private fun slot(network: String, protocol: AetherProtocol) = "$network|${protocol.env}"

    fun get(context: Context, network: String, protocol: AetherProtocol): AetherCarrier? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(slot(network, protocol), null) ?: return null
        AetherCarrier.byKey(JSONObject(raw).optString("carrier"))
    }.getOrNull()

    fun remember(context: Context, network: String, protocol: AetherProtocol, carrier: AetherCarrier) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(slot(network, protocol), JSONObject().put("carrier", carrier.key).put("at", System.currentTimeMillis()).toString())
            .apply()
    }

    fun forget(context: Context, network: String, protocol: AetherProtocol) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(slot(network, protocol)).apply()
    }
}
