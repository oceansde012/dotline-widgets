package io.github.oceansde012.dotline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** the app was updated: the new code re-renders every widget and restarts the background jobs */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val a = context.applicationContext
        Updater.onReplaced(a)
        MediaTracker.ensureStarted(a)
        MediaTracker.render(a, forceFull = true)
        WeatherWidgetProvider.updateAll(a)
        HeadWidgetProvider.updateAll(a)
        BudsWidgetProvider.updateAll(a)
        PowerWidgetProvider.refresh(a)
        // the clock widget has no update helper: ask its provider through the standard broadcast
        val mgr = android.appwidget.AppWidgetManager.getInstance(a)
        val ids = mgr.getAppWidgetIds(android.content.ComponentName(a, ClockWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            a.sendBroadcast(
                Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(android.content.ComponentName(a, ClockWidgetProvider::class.java))
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
        if (listOf(WeatherWidgetProvider::class.java, PowerWidgetProvider::class.java, BudsWidgetProvider::class.java)
                .any { WeatherJob.hasWidgets(a, it) }
        ) WeatherJob.schedule(a)
        UpdateJob.schedule(a)
    }
}