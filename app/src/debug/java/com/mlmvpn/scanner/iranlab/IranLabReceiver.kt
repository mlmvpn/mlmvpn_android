package com.mlmvpn.scanner.iranlab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mlmvpn.scanner.data.IranProfileTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only, shell permission: runs the Iran configs' real test and logs each verdict.
 *   am broadcast -n com.mlmvpn.scanner/.iranlab.IranLabReceiver [--es only <id>] [--ez fronting true]
 * `fronting` adds the domain-fronting config (certificate made if missing) as the first row.
 */
class IranLabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val only = intent.getStringExtra("only")
        scope.launch {
            val fronting = if (intent.getBooleanExtra("fronting", false) &&
                com.mlmvpn.scanner.mitm.MitmCertManager.ensure(app)) com.mlmvpn.scanner.mitm.MitmProfile.buildConfig(app) else null
            val all = (fronting?.let { mapOf("fronting" to it) }.orEmpty() + IranProfileTester.builtInConfigs(app))
                .filterKeys { only == null || it.contains(only) }
            Log.i(TAG, "testing ${all.size}")
            val out = IranProfileTester.testAll(app, all) { id, v -> Log.i(TAG, "$id -> ${v.level} ${v.ms}ms ${v.why}") }
            Log.i(TAG, "DONE ok=${out.values.count { it.level == IranProfileTester.Level.OK }} partial=${out.values.count { it.level == IranProfileTester.Level.PARTIAL }} fail=${out.values.count { it.level == IranProfileTester.Level.FAIL }}")
        }
    }

    companion object {
        private const val TAG = "IranLab"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
