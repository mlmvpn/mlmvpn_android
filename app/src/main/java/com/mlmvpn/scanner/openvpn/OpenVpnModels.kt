package com.mlmvpn.scanner.openvpn

enum class AuthState { UNKNOWN, ACCEPTED, FAILED, VERIFICATION_REQUIRED }
enum class AccountState { READY, USAGE_UNAVAILABLE, QUOTA_LOW, DEPLETED, AUTH_FAILED, VERIFICATION_REQUIRED, UNAVAILABLE }

data class ProviderUsage(val total: Long, val used: Long, val checkedAt: Long, val resetAt: Long? = null) {
    init { require(total >= 0 && used in 0..total && checkedAt > 0) }
    val remaining: Long get() = total - used
    fun resetPassed(now: Long) = resetAt?.let { now >= it && it > checkedAt } == true
    fun fresh(now: Long) = now - checkedAt in 0..900_000L && !resetPassed(now)
}

data class Account(
    val id: String,
    val username: String,
    val auth: AuthState = AuthState.UNKNOWN,
    val usage: ProviderUsage? = null,
    val lastChecked: Long? = null,
    val lastConnected: Long? = null,
    val unavailableUntil: Long = 0,
    val lastError: String? = null,
    val credentialRef: String = id,
) {
    fun usable(now: Long) = auth != AuthState.FAILED && auth != AuthState.VERIFICATION_REQUIRED &&
        (usage?.remaining != 0L || usage.resetPassed(now)) && unavailableUntil <= now
    fun state(now: Long): AccountState = when {
        auth == AuthState.FAILED -> AccountState.AUTH_FAILED
        auth == AuthState.VERIFICATION_REQUIRED -> AccountState.VERIFICATION_REQUIRED
        usage?.remaining == 0L && !usage.resetPassed(now) -> AccountState.DEPLETED
        unavailableUntil > now -> AccountState.UNAVAILABLE
        usage == null || !usage.fresh(now) -> AccountState.USAGE_UNAVAILABLE
        usage.total > 0 && usage.remaining.toDouble() / usage.total < .1 -> AccountState.QUOTA_LOW
        else -> AccountState.READY
    }
}

object AccountPolicy {
    fun confirmedUnusable(a: Account, now: Long) = a.auth == AuthState.FAILED ||
        a.auth == AuthState.VERIFICATION_REQUIRED || (a.usage?.let { it.fresh(now) && it.remaining == 0L } == true)

    fun best(accounts: List<Account>, now: Long, excluded: Set<String> = emptySet()): Account? =
        accounts.filter { it.id !in excluded && it.usable(now) }.maxWithOrNull(
            compareBy<Account> { if (it.usage?.fresh(now) == true) 1 else 0 }
                .thenBy { if (it.auth == AuthState.ACCEPTED && now - (it.lastConnected ?: 0) in 0..86_400_000L) 1 else 0 }
                .thenBy { if (it.usage?.fresh(now) == true) it.usage.remaining else -1L }
                .thenBy { it.lastConnected ?: 0 }
        )
}

data class Remote(val host: String, val port: Int, val protocol: String)
data class ProbeResult(
    val millis: Long?, val checkedAt: Long, val method: String, val error: String? = null,
    /**
     * The server addresses that answered, fastest first. One TunnelBear name is a pool of about
     * twenty servers and DNS hands out a different one on every lookup, so a delay is only true
     * of the address it was measured on -- the connect goes to these, not to a fresh lookup.
     */
    val healthy: List<String> = emptyList(),
)
class Profile(
    val id: String, val name: String, val config: String, val remotes: List<Remote>,
    val authenticatedControl: Boolean = false, val favorite: Boolean = false, val probe: ProbeResult? = null,
) {
    fun withFavorite(value: Boolean) = Profile(id, name, config, remotes, authenticatedControl, value, probe)

    /** The username and password the file itself carries (an inline auth-user-pass block), if any. */
    val ownCredentials: Pair<String, String>? get() = inlineCredentials(config)

    /**
     * Connects without one of the app's accounts: the file needs no credentials at all
     * (certificate only), or carries its own. TunnelBear profiles always need the account.
     */
    val selfContained: Boolean
        get() = ownCredentials != null || config.lineSequence().none { it.trim().lowercase().startsWith("auth-user-pass") }

    companion object {
        fun inlineCredentials(config: String): Pair<String, String>? {
            val lines = config.lineSequence().map { it.trim() }.toList()
            val start = lines.indexOf("<auth-user-pass>")
            if (start < 0) return null
            val end = lines.indexOf("</auth-user-pass>")
            if (end < start) return null
            val body = lines.subList(start + 1, end).filter { it.isNotEmpty() }
            return body.getOrNull(0)?.let { it to body.getOrElse(1) { "" } }
        }
    }
    fun withProbe(value: ProbeResult) = Profile(id, name, config, remotes, authenticatedControl, favorite, value)
    override fun toString() = "OpenVPN profile $id"
}

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTING, ERROR }
data class OpenVpnConnection(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val profileId: String? = null, val accountId: String? = null,
    val connectedAt: Long? = null, val received: Long = 0, val sent: Long = 0,
    val error: String? = null,
    /** A step before the core runs, e.g. "FRONT" while the front engine comes up. */
    val detail: String? = null,
    /** Measured through the tunnel once it carries data. */
    val exitIp: String? = null,
    val exitCountry: String? = null,
) { val active get() = phase !in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR) }
