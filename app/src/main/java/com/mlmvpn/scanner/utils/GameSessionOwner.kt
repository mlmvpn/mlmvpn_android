package com.mlmvpn.scanner.utils

/** Reject queued starts after their boost has ended; normal VPN starts carry no token. */
object GameSessionOwner {
    const val EXTRA = "GAME_SESSION_ID"
    const val EXPECTED = "EXPECTED_GAME_SESSION_ID"
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun register(id: String) { active.add(id) }
    fun release(id: String) { active.remove(id) }
    fun accepts(id: String?): Boolean = id == null || id in active
}
