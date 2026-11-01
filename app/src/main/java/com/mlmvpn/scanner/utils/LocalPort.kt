package com.mlmvpn.scanner.utils

import android.content.Context
import androidx.preference.PreferenceManager
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * The app's Local Port setting, and the rules that make a value valid.
 *
 * The setting is not one port. Everything that starts a tunnel uses `port` for the mixed
 * SOCKS/HTTP inbound and `port + 10000` for the status/country probe — a convention shared with
 * the GST engine, which listens there regardless of which engine is connected. So a value is
 * only usable if BOTH land somewhere legal and neither collides with a range the app has
 * already spoken for.
 *
 * Before this existed the settings field accepted any text at all. Most readers do
 * `getString("local_port","10808")?.toIntOrNull() ?: 10808`, which quietly rescues an empty or
 * non-numeric value — but a *valid* number in the wrong place is not rescued by anything:
 *
 *   - 21000 puts the probe on 31000, inside the throwaway range the delay tester walks, so
 *     bulk tests and the probe fight over the same port
 *   - anything at or above 55536 puts the probe past 65535, so it can never bind
 *   - a port inside 31000-34999 collides with the delay tester directly
 *   - 20810 is Aether's
 *
 * None of these announce themselves. They present as "every server tests as dead" or "the
 * status bar never finds the country", which is a long way from "the port you typed".
 */
object LocalPort {

    const val DEFAULT = 10808
    const val KEY = "local_port"

    /** The probe offset. Not configurable; several engines assume it. */
    const val PROBE_OFFSET = 10000

    private const val MIN = 1024
    // Above this the probe (port + 10000) would exceed 65535.
    private const val MAX = 65535 - PROBE_OFFSET

    private const val AETHER_PORT = 20810

    /**
     * Fixed ports other engines bind while they run, independent of this value. A user port whose
     * derived band (or probe) lands on one of these would fight that engine for the port, and the
     * symptom (an engine that binds and then behaves as if dead) is a long way from "the port you
     * typed". Tor's front/daemon/DNS, Geph's SOCKS/HTTP, «وارپ», and udpgw.
     */
    private val RESERVED = setOf(1821, 1822, 1823, 20850, 20851, 20870, 7300)

    /**
     * How far above `port` the app's derived listeners reach.
     *
     * `+1` the Psiphon-over-WARP chain, `+2` Psiphon's HTTP proxy, `+3`
     * [com.mlmvpn.scanner.lan.LanSetupServer], `+4` [com.mlmvpn.scanner.lan.LanRelay].
     */
    private const val LAN_BAND = 4

    /** Null when [raw] is usable; otherwise a Persian sentence naming the actual problem. */
    fun validate(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return S(R.string.the_local_port_cannot_be_empty)
        val port = text.toIntOrNull() ?: return S(R.string.the_port_must_be_digits_only)
        if (port < MIN) return S(R.string.the_port_must_be_min_or_higher, MIN)
        if (port > MAX) return S(R.string.the_port_must_be_max_or_lower, MAX, PROBE_OFFSET)
        if (port == AETHER_PORT) return S(R.string.this_port_is_reserved_for_the_aether)
        val probe = port + PROBE_OFFSET
        // The whole derived band and the probe, against the other engines' fixed ports.
        if (((port..port + LAN_BAND) + probe).any { it in RESERVED }) {
            return S(R.string.this_port_conflicts_with_an_engine)
        }
        val testRange = XrayJsonGenerator.TEST_PORT_MIN..XrayJsonGenerator.TEST_PORT_MAX
        // The whole band, not just `port`. Local Network sharing derives two more listeners from
        // this value -- `port + 3` for the setup page and `port + 4` for the relay that fronts
        // the proxy -- so a value four below the tester's range puts one of them inside it, and
        // the symptom is a share that binds and then fights the delay tester for a port.
        if ((port..port + LAN_BAND).any { it in testRange }) {
            return S(R.string.this_port_is_in_the_range_which, XrayJsonGenerator.TEST_PORT_MIN, XrayJsonGenerator.TEST_PORT_MAX)
        }
        if (probe in testRange) {
            return S(R.string.the_app_also_uses_port_probe_and, probe)
        }
        return null
    }

    /**
     * The configured port, or [DEFAULT] when what is stored is unusable.
     *
     * Every caller went through `?: DEFAULT` on its own before, which caught bad text but not a
     * bad number. Going through here means one definition of "usable" instead of nine.
     */
    fun get(context: Context): Int {
        val raw = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, DEFAULT.toString())
        return if (validate(raw) == null) raw!!.trim().toInt() else DEFAULT
    }

    /** The same value as a string, for the intent extras the services read. */
    fun getString(context: Context): String = get(context).toString()

    /** The probe/secondary port that goes with [get]. */
    fun probe(context: Context): Int = get(context) + PROBE_OFFSET
}
