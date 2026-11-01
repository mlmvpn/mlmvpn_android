package com.mlmvpn.scanner.ui.tunnel

import android.content.Context
import android.content.Intent
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.core.tunnel.TunnelVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Works out which country a live tunnel really comes out in.
 *
 * ## Why not Cloudflare's `loc`
 *
 * The obvious source is `cdn-cgi/trace`, and it is the wrong one twice over. It reports
 * Cloudflare's OWN view of the address, which this app has already been burned by once — see
 * the 1.2.x note in About: an American exit was being shown as Canada, and the per-config flags
 * were moved to `api.ip.sb/geoip` so both would agree with each other and with sites like ip.me.
 * And through Tor it does not report a country at all: every exit reads `loc=T1`, Cloudflare's
 * marker for "this came out of the Tor network", which is true and useless.
 *
 * So the country is resolved from the exit ADDRESS, through the same geo source the rest of the
 * app uses. That works for Tor exactly as it works for everything else.
 *
 * ## Two routes, decided by the transport
 *
 * The address itself has to be measured from inside the tunnel, and how depends on which half
 * of the stack is running:
 *
 *  - **Psiphon and Tor** publish a local SOCKS5 port ([TunnelStatus.activeSocksPort]) which is
 *    the only path from this process into the tunnel — the service excludes the app from its
 *    own tun, or the tunnel's traffic would re-enter the tun it is feeding. One request through
 *    that port returns the address AND its country together.
 *  - **MASQUE, WireGuard and WARP-on-WARP** hand the tun straight to the Rust core, which binds
 *    no listener at all — so there is no way in from here. The core measures its own exit and
 *    reports it, and the only thing left to do is look up that address's country, which can be
 *    asked over any link because the answer does not depend on who is asking.
 *
 * The first version of this got that distinction wrong and made an ordinary request in both
 * cases. It reported the PHONE's address and the phone's country, and on an Iranian line that
 * reading is indistinguishable from a tunnel genuinely exiting in Iran.
 *
 * ## A note on Tor and "the" exit
 *
 * Tor builds a separate circuit per destination, so the exit this measures is the exit for
 * *this* request. Opening a site in a browser can legitimately leave from a different relay in
 * a different country. Neither reading is wrong; there is simply no single exit to report.
 */
object TunnelExitLocator {

    /**
     * The same geo source the connection tab uses for per-config flags.
     *
     * With no path it describes the caller, which through the SOCKS port is the tunnel's exit.
     * Kept first for consistency: the About notes record that the per-config flags were moved
     * here precisely so every flag in the app agrees with every other and with ip.me.
     */
    private const val GEOIP_URL = "https://api.ip.sb/geoip"

    /**
     * Address lookups, tried in order until one answers.
     *
     * Three rather than one because this runs on a censored line and a single endpoint being
     * unreachable would mean no country at all. All three are keyless HTTPS behind a CDN, and
     * all three were checked against the same address: a real WARP exit of `104.28.214.161`
     * comes back `IR` from every one of them.
     *
     * A registration-based source would NOT do. Cymru's whois answers from the RIR record, and
     * every Cloudflare range is registered to ARIN in the US — so the same address reads `US`
     * there while every geolocation database places it in Tehran. Neighbouring addresses in that
     * /24 resolve to PT, CA, GB, CO and AR, because Cloudflare assigns anycast egress per user
     * rather than per region, so the registration country cannot even approximate it.
     */
    private val ADDRESS_LOOKUPS = listOf(
        "https://api.ip.sb/geoip/%s",
        "https://get.geojs.io/v1/ip/country/%s.json",
        "https://ipwho.is/%s?fields=country_code",
    )

    /** Matches `"country":"IR"` and `"country_code":"IR"` alike, across all three shapes. */
    private val COUNTRY_CODE = Regex("\"country(?:_code)?\"\\s*:\\s*\"([A-Za-z]{2})\"")

    private const val ATTEMPTS = 12
    private const val RETRY_MS = 4_000L

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    /**
     * Start locating the session that just came up.
     *
     * Cancels any previous run, so switching transports cannot leave an old lookup reporting
     * the old tunnel's exit against the new one.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            repeat(ATTEMPTS) { attempt ->
                if (!isActive) return@launch
                // "Connected" and "carrying traffic" are different moments: Psiphon reports its
                // tunnel up before tun2socks has finished binding, a Tor circuit takes seconds
                // more to be usable, and the core's own exit probe has not run yet either. So
                // this settles first and then keeps asking.
                delay(if (attempt == 0) 2_000L else RETRY_MS)

                val port = TunnelStatus.activeSocksPort
                val result = if (port > 0) {
                    // Through the tunnel: one request answers both questions.
                    lookup(GEOIP_URL, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                } else {
                    // Native tun. The core measures the exit itself -- it has to, because this
                    // process is excluded from the tun and there is no SOCKS port to borrow --
                    // and asking which country that address is in gives the same answer over any
                    // link, so the lookup goes out directly.
                    val ip = TunnelVpnService.lastMeasuredExitIp().takeIf { it.isNotBlank() }
                        ?: return@repeat
                    lookupAddress(ip)
                }
                if (result == null || result.ip.isBlank()) return@repeat

                com.mlmvpn.scanner.utils.SafeStart.service(app, 
                    Intent(app, TunnelVpnService::class.java).apply {
                        action = TunnelVpnService.ACTION_NOTIFICATION_HEALTH
                        putExtra(TunnelVpnService.EXTRA_NOTIFICATION_IP, result.ip)
                        result.code?.takeIf { it.isNotBlank() }?.let {
                            putExtra(TunnelVpnService.EXTRA_NOTIFICATION_COUNTRY, it)
                        }
                    }
                )
                return@launch
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private data class Located(val ip: String, val code: String?)

    /**
     * Which country an address is in, from whichever of [ADDRESS_LOOKUPS] answers first.
     *
     * Parsed by regex rather than by field name because the three endpoints spell the answer
     * differently and all that is wanted from any of them is two letters.
     */
    private suspend fun lookupAddress(ip: String): Located? = withContext(Dispatchers.IO) {
        for (template in ADDRESS_LOOKUPS) {
            val body = fetch(template.format(ip), Proxy.NO_PROXY) ?: continue
            val code = COUNTRY_CODE.find(body)?.groupValues?.get(1)?.uppercase()
            if (code != null) return@withContext Located(ip, code)
        }
        // The address is still worth reporting without a country: the notification shows it,
        // and a later attempt may resolve what this one could not.
        Located(ip, null)
    }

    /**
     * One geo lookup.
     *
     * A client per call rather than one cached: the proxy differs per transport and per session,
     * and an OkHttpClient holds its proxy for the life of its connection pool.
     */
    private suspend fun lookup(url: String, proxy: Proxy): Located? = withContext(Dispatchers.IO) {
        val body = fetch(url, proxy) ?: return@withContext null
        runCatching {
            val json = JSONObject(body)
            Located(
                ip = json.optString("ip").orEmpty(),
                // `country_code` is the field this source names it. Falling back to `country`
                // would hand back an English name, which is the shape the screen cannot use.
                code = json.optString("country_code").takeIf { it.length == 2 },
            )
        }.getOrNull()
    }

    /** One GET, or null if it did not complete. A client per call; see the note on [lookup]. */
    private fun fetch(url: String, proxy: Proxy): String? {
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .build()
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
