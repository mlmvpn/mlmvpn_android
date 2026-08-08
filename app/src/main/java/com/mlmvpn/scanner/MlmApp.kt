package com.mlmvpn.scanner

import android.app.Application

/**
 * Exists purely so the crash reporter is installed before any other app code runs — including
 * in the VPN service process, which is where several of the native crashes have originated.
 */
class MlmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
