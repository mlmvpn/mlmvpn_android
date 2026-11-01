package com.mlmvpn.scanner.utils

/** Serializes ownership changes with their side effects, including pending starts. */
class ConnectionOwner {
    private var owner: Any? = null

    @Synchronized fun <T> replace(token: Any?, start: () -> T): T {
        owner = token
        return start()
    }

    @Synchronized fun owns(token: Any): Boolean = owner === token

    @Synchronized fun stop(token: Any? = null, stop: () -> Unit): Boolean {
        if (token != null && owner !== token) return false
        owner = null
        stop()
        return true
    }
}
