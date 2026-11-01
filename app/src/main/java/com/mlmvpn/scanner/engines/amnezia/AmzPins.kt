package com.mlmvpn.scanner.engines.amnezia

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.flux.FluxNet
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Self-signed Hysteria2 servers ([AmzServer.selfSigned]), pinned to the certificate each one shows
 * right now. Asked again before every test and every connect: a server that made itself a new
 * certificate on a restart is followed, and the core still accepts only the one just seen.
 */
object AmzPins {

    private const val TAG = "AmzPins"

    /**
     * [s] with `pinSHA256` set to the certificate it shows now ([s] itself when that is the pin it
     * already has); null when it showed none in time -- nothing answers QUIC there, or the line
     * drops its UDP.
     */
    suspend fun pin(app: Context, here: FluxNet.Here, s: AmzServer, timeoutMs: Int): AmzServer? = withContext(Dispatchers.IO) {
        val node = (FluxLinkParser.parse(s.raw, s.sourceId) as? FluxLinkParser.Result.Ok)?.node ?: return@withContext null
        val ip = Family.ofLiteral(node.server)?.let { node.server }
            ?: FluxNet.resolve(app, here.network, node.server).let { it[Family.V4] ?: it[Family.V6] }
            ?: return@withContext null
        val obfs = if (node.obfs == "salamander") node.obfsPassword else ""
        // A literal: no lookup happens here.
        val pin = QuicCertProbe.fetch(InetAddress.getByName(ip), node.port, node.sni, obfs, timeoutMs) { sock ->
            here.network?.let { n -> runCatching { n.bindSocket(sock) } }
        }
        when {
            pin == null -> null
            pin == node.certPin -> s
            else -> {
                if (node.certPin.isNotEmpty()) Log.i(TAG, "$s shows a new certificate")
                s.copy(raw = AmzParser.withPin(s.raw, pin))
            }
        }
    }
}
