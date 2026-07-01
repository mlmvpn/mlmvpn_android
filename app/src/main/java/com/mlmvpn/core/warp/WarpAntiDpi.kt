package com.mlmvpn.core.warp

import kotlin.random.Random

object WarpAntiDpi {
    /**
     * تولید 3 بایت تصادفی برای تزریق به هدر WireGuard
     * این کار امضای پکت را تغییر داده و از سد DPI عبور میکند.
     */
    fun generateReservedBytes(): List<Int> {
        return listOf(
            Random.nextInt(0, 256),
            Random.nextInt(0, 256),
            Random.nextInt(0, 256)
        )
    }
}
