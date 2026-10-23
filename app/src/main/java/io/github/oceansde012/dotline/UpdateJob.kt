package io.github.oceansde012.dotline

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** every 15 min: looks for a new release and downloads it, so INSTALL is instant when the user gets to it */
class UpdateJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        if (!BuildConfig.UPDATES_ENABLED) return false
        val sync = java.util.concurrent.atomic.AtomicBoolean(true)
        val doneSync = java.util.concurrent.atomic.AtomicBoolean(false)
        Updater.checkAsync(applicationContext) {
            // checkAsync may call back before it returns (disabled / already running): then there is no work to finish
            if (sync.get()) doneSync.set(true) else jobFinished(params, false)
        }
        sync.set(false)
        return !doneSync.get()
    }
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4102
        private const val PERIOD_MS = 15 * 60 * 1000L

        /** idempotent; persisted over reboots */
        fun schedule(ctx: Context) {
            if (!BuildConfig.UPDATES_ENABLED) return
            val a = ctx.applicationContext
            val js = a.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(a, UpdateJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}