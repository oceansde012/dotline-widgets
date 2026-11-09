package io.github.oceansde012.dotline

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

class OutageJob : JobService() {
    private val running = mutableMapOf<Int, JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        if (!OutageWidgetProvider.hasWidgets(app) || OutageRepo.queue(app) == null) return false
        running[params.jobId] = params
        OutageWidgetProvider.updateAll(app)
        OutageRepo.refresh(app) {
            // The refresh can complete synchronously from cache. Finish after onStartJob returns.
            mainExecutor.execute {
                if (running[params.jobId] === params) {
                    running.remove(params.jobId)
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)
        return false
    }

    companion object {
        private const val PERIODIC = 4103
        private const val NOW = 4104

        fun schedule(ctx: Context) {
            if (!OutageWidgetProvider.hasWidgets(ctx) || OutageRepo.queue(ctx) == null) return
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            val service = ComponentName(ctx, OutageJob::class.java)
            try {
                if (scheduler.getPendingJob(PERIODIC) == null) {
                    scheduler.schedule(JobInfo.Builder(PERIODIC, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(15 * 60 * 1000L)
                        .setPersisted(true)
                        .build())
                }
                if (scheduler.getPendingJob(NOW) == null) {
                    scheduler.schedule(JobInfo.Builder(NOW, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .build())
                }
            } catch (e: RuntimeException) {
                Log.w("OutageJob", "Could not schedule outage refresh", e)
            }
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java)?.let {
                it.cancel(PERIODIC)
                it.cancel(NOW)
            }
        }
    }
}
