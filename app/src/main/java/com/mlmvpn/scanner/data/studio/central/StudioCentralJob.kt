package com.mlmvpn.scanner.data.studio.central

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.CloudManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps every account's central Worker in step while the app is closed: a person whose term ended
 * is taken off, and ids another device dropped (the Windows app, an older phone) are put back.
 *
 * The central Worker cannot tell the time — it only knows the list it was given — so this phone is
 * what ends a term. The system runs this about every half hour while there is a network, and later
 * when the phone is asleep (Android defers background work then); the Studio's own screens do the
 * same on every open. When nothing is out of step it uploads nothing: [CentralEngine.reconcile]
 * compares the digest the account's Worker record carries before it touches anything.
 */
class StudioCentralJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val ok = runCatching { runOnce(applicationContext) }
                .onFailure { Log.w(TAG, "reconcile failed: ${it.message}") }
                .getOrDefault(false)
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StudioCentralJob"
        private const val JOB_ID = 0x5C3A71
        private const val EVERY_MS = 30 * 60 * 1000L

        /** One pass over every account this phone runs the Studio on. True when none failed. */
        suspend fun runOnce(context: Context): Boolean {
            val docs = CentralFiles.all(context)
            if (docs.isEmpty()) return true
            val cloud = CloudManager(context)
            cloud.awaitLoaded()
            var ok = true
            for (doc in docs) {
                val real = cloud.accounts.firstOrNull { it.id == doc.accountId } ?: continue
                // Nobody here and nothing applied: nothing that could be out of step.
                if (doc.people.isEmpty() && doc.appliedIds.isEmpty()) continue
                val r = CentralFleet.reconcile(context, CentralFleet.shardAccount(real))
                if (r == null || r.unreadable) ok = false
            }
            return ok
        }

        /**
         * Scheduled while this phone runs the Studio on any account, cancelled when it runs it on none.
         * Safe to call at every launch: a job already pending is left as it is.
         */
        fun ensure(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                if (CentralFiles.accountIds(context).isEmpty()) {
                    scheduler.cancel(JOB_ID)
                    return
                }
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, StudioCentralJob::class.java))
                    .setPeriodic(EVERY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(job)
            } catch (t: Throwable) {
                // Not registered in this build's manifest, or the system refused: the screens still
                // bring the Worker in step every time the Studio opens.
                Log.w(TAG, "could not schedule: ${t.message}")
            }
        }
    }
}
