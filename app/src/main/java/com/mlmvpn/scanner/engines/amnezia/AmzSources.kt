package com.mlmvpn.scanner.engines.amnezia

/**
 * Where the free servers come from: public GitHub lists that publish Hysteria2, `wireguard://`
 * links and WireGuard `.conf` files, refreshed by their owners every hour or day.
 *
 * Each is fetched from raw.githubusercontent.com first and from jsDelivr's mirror of the same
 * file when GitHub is filtered. The lists copy each other heavily; servers are merged by identity,
 * so a server in five lists is one row.
 *
 * [prior] orders the first test before anything has been learned: lists that publish only
 * servers they have checked themselves go first.
 */
data class AmzSource(
    val id: String,
    val repo: String,
    val branch: String,
    val path: String,
    val format: Format,
    val prior: Double,
    val maxBytes: Int = 2 * 1024 * 1024,
    /** Hours between refreshes. */
    val everyHours: Int = 6,
) {
    enum class Format { LINKS, ZIP, CONF_DIR }

    val rawUrl: String get() = "https://raw.githubusercontent.com/$repo/$branch/$path"
    val mirrorUrl: String get() = "https://cdn.jsdelivr.net/gh/$repo@$branch/$path"
    /** For [Format.CONF_DIR]: GitHub's listing of the directory, and jsDelivr's flat listing of the repo. */
    val listingUrl: String get() = "https://api.github.com/repos/$repo/contents/$path?ref=$branch"
    val mirrorListingUrl: String get() = "https://data.jsdelivr.com/v1/packages/gh/$repo@$branch?structure=flat"
}

object AmzSources {

    val BUILT_IN: List<AmzSource> = listOf(
        // morpheusadam/v2ray-config: only servers its own harvester found alive, best first.
        AmzSource("mv-hy2", "morpheusadam/v2ray-config", "main", "subs/bundles/hysteria2.txt", AmzSource.Format.LINKS, 0.8),
        AmzSource("mv-wg", "morpheusadam/v2ray-config", "main", "subs/bundles/wireguard.txt", AmzSource.Format.LINKS, 0.75),
        // rtwo2/FastNodes: checked with Xray every hour, with city-level names.
        AmzSource("fn-hy2-1", "rtwo2/FastNodes", "main", "sub/protocols/hysteria2.txt", AmzSource.Format.LINKS, 0.7),
        AmzSource("fn-hy2-2", "rtwo2/FastNodes", "main", "sub/protocols/hysteria2_part2.txt", AmzSource.Format.LINKS, 0.6),
        AmzSource("fn-hy2-3", "rtwo2/FastNodes", "main", "sub/protocols/hysteria2_part3.txt", AmzSource.Format.LINKS, 0.55),
        AmzSource("fn-hy2-4", "rtwo2/FastNodes", "main", "sub/protocols/hysteria2_part4.txt", AmzSource.Format.LINKS, 0.5),
        AmzSource("fn-wgconf", "rtwo2/FastNodes", "main", "sub/wireguard", AmzSource.Format.CONF_DIR, 0.6, everyHours = 24),
        // Argh73/VpnConfigCollector and the Argh94 lists: Telegram channels, collected hourly.
        AmzSource("vcc-hy2", "Argh73/VpnConfigCollector", "main", "Splitted-By-Protocol/Hysteria2.txt", AmzSource.Format.LINKS, 0.45),
        AmzSource("vcc-wg", "Argh73/VpnConfigCollector", "main", "Splitted-By-Protocol/WireGuard.txt", AmzSource.Format.LINKS, 0.45),
        AmzSource("a94-hy2", "Argh94/Proxy-List", "main", "Hysteria2.txt", AmzSource.Format.LINKS, 0.45),
        AmzSource("a94-wg", "Argh94/Proxy-List", "main", "WireGuard.txt", AmzSource.Format.LINKS, 0.45),
        AmzSource("vac-hy2", "Argh94/V2RayAutoConfig", "main", "configs/Hysteria2.txt", AmzSource.Format.LINKS, 0.45),
        AmzSource("vac-wg", "Argh94/V2RayAutoConfig", "main", "configs/WireGuard.txt", AmzSource.Format.LINKS, 0.45),
        // 10ium/VpnClashFaCollector: tested with xray-knife before publishing.
        AmzSource("vcf-hy2", "10ium/VpnClashFaCollector", "main", "sub/all/hysteria2.txt", AmzSource.Format.LINKS, 0.6),
        AmzSource("vcf-wg", "10ium/VpnClashFaCollector", "main", "sub/all/wireguard.txt", AmzSource.Format.LINKS, 0.6),
        // 10Dream/sub-mod: an aggregate of several of the above.
        AmzSource("sm-hy2", "10Dream/sub-mod", "main", "sub/split/protocols/hy2.txt", AmzSource.Format.LINKS, 0.4),
        AmzSource("sm-wg", "10Dream/sub-mod", "main", "sub/split/protocols/wireguard.txt", AmzSource.Format.LINKS, 0.35),
        // Delta-Kronecker: fresh Cloudflare WARP accounts, and the same as real AmneziaWG configs.
        AmzSource("dk-warp", "Delta-Kronecker/WARP-Config", "main", "ALL.txt", AmzSource.Format.LINKS, 0.4),
        AmzSource("dk-awg", "Delta-Kronecker/Cloudflare-Warp", "main", "AmneziaWG.zip", AmzSource.Format.ZIP, 0.5, everyHours = 24),
    )

    fun byId(id: String): AmzSource? = BUILT_IN.firstOrNull { it.id == id }

    /** Servers shipped with the app: working on the day it was built. */
    const val SEED = "seed"

    fun prior(id: String): Double = when (id) {
        AmzServer.USER_SOURCE -> 1.0
        SEED -> 0.95
        else -> byId(id)?.prior ?: 0.3
    }

    fun due(src: AmzSource, lastOkAt: Long, now: Long): Boolean = now - lastOkAt >= src.everyHours * 3_600_000L
}
