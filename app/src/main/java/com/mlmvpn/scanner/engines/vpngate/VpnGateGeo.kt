package com.mlmvpn.scanner.engines.vpngate

import java.util.Locale

/**
 * Country → continent grouping for the server browser, plus localised country names.
 *
 * VPN Gate only ever reports an ISO-3166 alpha-2 code, so the continent has to come from a
 * table. Only the mapping is hard-coded — display names come from the platform, which means
 * they follow whatever language the user picked in the app.
 */
object VpnGateGeo {

    enum class Continent(val label: String, val emoji: String) {
        ASIA("آسیا", "🌏"),
        EUROPE("اروپا", "🇪🇺"),
        NORTH_AMERICA("آمریکای شمالی", "🌎"),
        SOUTH_AMERICA("آمریکای جنوبی", "🌎"),
        AFRICA("آفریقا", "🌍"),
        OCEANIA("اقیانوسیه", "🌏"),
        OTHER("سایر", "🌐"),
    }

    private val ASIA = setOf(
        "AE", "AF", "AM", "AZ", "BD", "BH", "BN", "BT", "CN", "CY", "GE", "HK", "ID", "IL",
        "IN", "IQ", "IR", "JO", "JP", "KG", "KH", "KP", "KR", "KW", "KZ", "LA", "LB", "LK",
        "MM", "MN", "MO", "MV", "MY", "NP", "OM", "PH", "PK", "PS", "QA", "SA", "SG", "SY",
        "TH", "TJ", "TL", "TM", "TR", "TW", "UZ", "VN", "YE",
    )

    private val EUROPE = setOf(
        "AD", "AL", "AT", "AX", "BA", "BE", "BG", "BY", "CH", "CZ", "DE", "DK", "EE", "ES",
        "FI", "FO", "FR", "GB", "GG", "GI", "GR", "HR", "HU", "IE", "IM", "IS", "IT", "JE",
        "LI", "LT", "LU", "LV", "MC", "MD", "ME", "MK", "MT", "NL", "NO", "PL", "PT", "RO",
        "RS", "RU", "SE", "SI", "SK", "SM", "UA", "VA", "XK",
    )

    private val NORTH_AMERICA = setOf(
        "AG", "AI", "AW", "BB", "BM", "BS", "BZ", "CA", "CR", "CU", "CW", "DM", "DO", "GD",
        "GL", "GP", "GT", "HN", "HT", "JM", "KN", "KY", "LC", "MQ", "MS", "MX", "NI", "PA",
        "PR", "SV", "SX", "TC", "TT", "US", "VC", "VG", "VI",
    )

    private val SOUTH_AMERICA = setOf(
        "AR", "BO", "BR", "CL", "CO", "EC", "FK", "GF", "GY", "PE", "PY", "SR", "UY", "VE",
    )

    private val AFRICA = setOf(
        "AO", "BF", "BI", "BJ", "BW", "CD", "CF", "CG", "CI", "CM", "CV", "DJ", "DZ", "EG",
        "EH", "ER", "ET", "GA", "GH", "GM", "GN", "GQ", "GW", "KE", "KM", "LR", "LS", "LY",
        "MA", "MG", "ML", "MR", "MU", "MW", "MZ", "NA", "NE", "NG", "RE", "RW", "SC", "SD",
        "SL", "SN", "SO", "SS", "ST", "SZ", "TD", "TG", "TN", "TZ", "UG", "ZA", "ZM", "ZW",
    )

    private val OCEANIA = setOf(
        "AS", "AU", "CK", "FJ", "FM", "GU", "KI", "MH", "MP", "NC", "NF", "NR", "NU", "NZ",
        "PF", "PG", "PW", "SB", "TO", "TV", "VU", "WF", "WS",
    )

    fun continentOf(countryCode: String): Continent {
        val cc = countryCode.uppercase(Locale.US)
        return when (cc) {
            in ASIA -> Continent.ASIA
            in EUROPE -> Continent.EUROPE
            in NORTH_AMERICA -> Continent.NORTH_AMERICA
            in SOUTH_AMERICA -> Continent.SOUTH_AMERICA
            in AFRICA -> Continent.AFRICA
            in OCEANIA -> Continent.OCEANIA
            else -> Continent.OTHER
        }
    }

    /**
     * Country name in the app's current language, falling back to whatever VPN Gate wrote in
     * the CSV when the platform has no name for the code.
     */
    fun countryName(countryCode: String, fallback: String): String {
        val cc = countryCode.uppercase(Locale.US)
        if (cc.length != 2) return fallback.ifBlank { cc }
        val name = Locale("", cc)
            .getDisplayCountry(com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale())
        // getDisplayCountry echoes the code back when it doesn't know it.
        return if (name.isBlank() || name.equals(cc, ignoreCase = true)) fallback.ifBlank { cc } else name
    }
}
