package com.mlmvpn.scanner.engines.tgproxy

/** Where the proxy runs in the user's Cloudflare account. */
enum class TgMethod(val id: String) {
    WORKERS("workers"),
    PAGES("pages");

    companion object {
        fun of(id: String?): TgMethod? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the service honestly is right now.
 *
 *  - BUILDING: a build is running (or was, when the app was killed -- see [TgProxyManager.recover]).
 *  - READY: built, and the self-test reached a Telegram data centre through it.
 *  - PARTIAL: something was created but the build did not finish; it can be resumed or deleted.
 *  - FAILED: the last attempt or check failed; [TgService.reason] says where.
 *  - ACTION: the user has to do something first (rate limit wait, missing permission, no subdomain).
 *  - LOST: the account is gone or no longer works, or the resource was deleted from the dashboard.
 *    Nothing is claimed about the service in this state.
 */
enum class TgState { BUILDING, READY, PARTIAL, FAILED, ACTION, LOST }

/**
 * The two self-tests: [viaApp] goes through the app's own route to Cloudflare, [direct] through the
 * phone's ordinary network, which is how Telegram itself will reach the proxy. Null = not run.
 * [step] is where the last failing run stopped (page, session, socket, stream, telegram).
 */
data class TgProbeResult(val viaApp: Boolean?, val direct: Boolean?, val step: String?, val at: Long)

/** A custom domain attached to the service; it counts only once [ready]. */
data class TgDomain(val host: String, val zoneId: String, val domainId: String?, val recordId: String?, val ready: Boolean)

/**
 * One proxy in one Cloudflare account. The secret is kept sealed (Android Keystore) and the
 * Cloudflare credentials are never stored here -- they stay in the account list.
 */
data class TgService(
    val id: String,
    val method: TgMethod,
    /** [com.mlmvpn.scanner.models.CloudAccount.id], the app's own id for the account. */
    val accountId: String,
    /** Cloudflare's id of the account the resource lives in; checked before any change. */
    val cfAccountId: String,
    /** The Worker script or Pages project name, `tgp-<id>`. */
    val resource: String,
    /** `<name>.<sub>.workers.dev` or `<name>.pages.dev`; empty until known. */
    val host: String,
    val domain: TgDomain?,
    val sealedSecret: String,
    val state: TgState,
    /** A short machine code for the UI to word: rate_limited, no_subdomain, no_permission, ... */
    val reason: String?,
    /** For rate_limited: when requests may resume (epoch ms). */
    val blockedUntil: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val probe: TgProbeResult?,
    /** Fingerprint of the relay script last uploaded; a different bundled one is pushed on the next check. */
    val rev: String = "",
) {
    /** The address a link should use: the custom domain once it works, else the Cloudflare one. */
    val shownHost: String get() = domain?.takeIf { it.ready }?.host ?: host

    companion object {
        fun resourceName(id: String) = TgLink.PREFIX + id
    }
}
