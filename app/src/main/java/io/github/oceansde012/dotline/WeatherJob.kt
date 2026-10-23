package io.github.oceansde012.dotline

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * The 15 minute heartbeat (DESIGN_SPEC.md 2.3 / section 5 fallback): fetches the forecast and, since the
 * process may have been dead since the last event, also re-renders the power and buds widgets from the
 * current system state. Runs only while at least one of the three widgets exists.
 */
class WeatherJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val a = applicationContext
        LiveHost.ensure(a)
        PowerWidgetProvider.refresh(a)
        BudsRepo.refreshAsync(a)
        if (!hasWidgets(a, WeatherWidgetProvider::class.java)) {
            jobFinished(params, false)
            return false
        }
        WeatherRepo.refreshAsync(a, minAgeMs = 10 * 60 * 1000L) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true   // retry next period

    companion object {
        private const val JOB_ID = 4101
        private const val PERIOD_MS = 15 * 60 * 1000L

        fun hasWidgets(ctx: Context, provider: Class<*>): Boolean =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, provider)).isNotEmpty()

        /** idempotent: keeps an already scheduled job (and its phase) */
        fun schedule(ctx: Context) {
            val a = ctx.applicationContext
            val js = a.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(a, WeatherJob::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            try {
                js.schedule(info)
            } catch (e: RuntimeException) {
                Log.w("WeatherJob", "schedule failed", e)
            }
        }

        /** call from onDisabled of each provider: stops the job once none of the three widgets is left */
        fun cancelIfUnused(ctx: Context) {
            val a = ctx.applicationContext
            val any = listOf(WeatherWidgetProvider::class.java, PowerWidgetProvider::class.java, BudsWidgetProvider::class.java)
                .any { hasWidgets(a, it) }
            if (!any) a.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
