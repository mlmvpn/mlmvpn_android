package com.mlmvpn.scanner.utils

import android.os.Build

/**
 * How many blocking threads a batch may hold at once on this phone.
 *
 * Every parallel test here holds a real thread per server (a socket wait, a JNI call). A modern
 * phone takes dozens; an old or small one (Android 8, a 2 GB TV box) runs out of thread memory
 * well before that and dies with "pthread_create failed" -- crash reports from 1.2.39 on Android
 * 8, 9 and 11. So the width is the phone's, not the code's.
 */
object ThreadBudget {

    private val small: Boolean by lazy {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P || Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024
    }

    /** [wanted] on a capable phone; a quarter of it (at least 6) on a small one. */
    fun parallel(wanted: Int): Int = if (small) maxOf(6, wanted / 4) else wanted
}
