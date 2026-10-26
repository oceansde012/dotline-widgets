package io.github.oceansde012.dotline

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log

/**
 * Tap targets of every widget. Each target is a list of candidate intents: the first one that resolves
 * (package visibility is declared in the manifest `queries`) wins, the last resort is this app, and the
 * choice is logged, so a tap never silently does nothing. All PendingIntents are FLAG_IMMUTABLE.
 */
object Taps {
    private const val TAG = "NdTaps"

    const val SAMSUNG_CLOCK = "com.sec.android.app.clockpackage"
    const val GOOGLE_CLOCK = "com.google.android.deskclock"
    const val AOSP_CLOCK = "com.android.deskclock"   // Xiaomi (MIUI / HyperOS) and AOSP-based ROMs
    const val SAMSUNG_CALENDAR = "com.samsung.android.calendar"
    const val GOOGLE_CALENDAR = "com.google.android.calendar"
    const val SAMSUNG_CARE = "com.samsung.android.lool"

    const val REQ_CALENDAR = 110
    const val REQ_ALARMS = 111
    const val REQ_CLOCK = 100
    const val REQ_BATTERY = 130
    const val REQ_BT = 140
    const val REQ_APP = 141
    const val REQ_NOTIF = 150
    const val REQ_BUDS_APP = 142

    private fun new(i: Intent) = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun launch(ctx: Context, pkg: String): Intent? = try {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { new(it) }
    } catch (e: Exception) {
        null
    }

    fun ownApp(ctx: Context): Intent =
        new(Intent(ctx, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun firstResolvable(ctx: Context, candidates: Array<out Intent?>): Intent? {
        val pm = ctx.packageManager
        for (c in candidates) {
            if (c == null) continue
            val ok = try {
                c.resolveActivity(pm) != null
            } catch (e: Exception) {
                false
            }
            if (ok) return c
        }
        return null
    }

    /** first resolvable candidate, else this app */
    fun pending(ctx: Context, req: Int, what: String, vararg candidates: Intent?): PendingIntent {
        firstResolvable(ctx, candidates)?.let { return PendingIntent.getActivity(ctx, req, new(it), immutable()) }
        Log.w(TAG, "$what: no target resolved, opening this app")
        return PendingIntent.getActivity(ctx, req, ownApp(ctx), immutable())
    }

    /** what a tap on [candidates] opens, for the diagnostics report */
    private fun describe(ctx: Context, vararg candidates: Intent?): String {
        val i = firstResolvable(ctx, candidates) ?: return "none, opens this app"
        val comp = try {
            i.resolveActivity(ctx.packageManager)?.flattenToShortString()
        } catch (e: Exception) {
            null
        }
        return comp ?: i.action ?: "?"
    }

    fun diagnose(ctx: Context): List<String> = listOf(
        "alarm tap: ${describe(ctx, launch(ctx, SAMSUNG_CLOCK), launch(ctx, GOOGLE_CLOCK), launch(ctx, AOSP_CLOCK), Intent(AlarmClock.ACTION_SHOW_ALARMS))}",
        "clock tap: ${describe(ctx, launch(ctx, SAMSUNG_CLOCK), launch(ctx, GOOGLE_CLOCK), launch(ctx, AOSP_CLOCK), Intent(AlarmClock.ACTION_SHOW_ALARMS))}",
        "calendar tap: ${describe(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), launch(ctx, SAMSUNG_CALENDAR), launch(ctx, GOOGLE_CALENDAR))}",
    )

    fun calendar(ctx: Context): PendingIntent = pending(
        ctx, REQ_CALENDAR, "calendar",
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
        launch(ctx, SAMSUNG_CALENDAR),
        launch(ctx, GOOGLE_CALENDAR),
        Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time")),
    )

    fun alarms(ctx: Context): PendingIntent = pending(
        ctx, REQ_ALARMS, "alarms",
        launch(ctx, SAMSUNG_CLOCK), launch(ctx, GOOGLE_CLOCK), launch(ctx, AOSP_CLOCK), Intent(AlarmClock.ACTION_SHOW_ALARMS),
    )

    /** Time widget: the Clock app itself */
    fun clock(ctx: Context): PendingIntent = pending(
        ctx, REQ_CLOCK, "clock",
        launch(ctx, SAMSUNG_CLOCK), launch(ctx, GOOGLE_CLOCK), launch(ctx, AOSP_CLOCK), Intent(AlarmClock.ACTION_SHOW_ALARMS),
    )

    fun battery(ctx: Context): PendingIntent = pending(
        ctx, REQ_BATTERY, "battery",
        Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Intent("android.settings.BATTERY_SAVER"),
        launch(ctx, SAMSUNG_CARE),
    )

    fun bluetooth(ctx: Context): PendingIntent = pending(
        ctx, REQ_BT, "bluetooth", Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
    )

    /** this app, scrolled to a section (weather city, Bluetooth permission, notification access) */
    fun app(ctx: Context, req: Int, section: String?): PendingIntent {
        val i = ownApp(ctx)
        if (section != null) i.putExtra(WeatherWidgetProvider.EXTRA_SECTION, section)
        return PendingIntent.getActivity(ctx, req, i, immutable())
    }

    /** the notification-access page of this app's listener, else the app (which explains the steps) */
    fun notificationAccess(ctx: Context): PendingIntent {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, MediaTracker.listenerComponent(ctx).flattenToString())
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val ok = try {
            detail.resolveActivity(ctx.packageManager) != null || list.resolveActivity(ctx.packageManager) != null
        } catch (e: Exception) {
            false
        }
        if (!ok) return app(ctx, REQ_NOTIF, MainActivity.SECTION_NOTIFICATION)
        return pending(ctx, REQ_NOTIF, "notification access", detail, list)
    }
}

/** the minute clock + date rollover for widgets that draw their own digits */
object Ticker {
    private const val ACTION = BuildConfig.APPLICATION_ID + ".TICK"
    private const val REQ = 160

    fun tickIntent(ctx: Context) = Intent(ctx, ClockWidgetProvider::class.java).setAction(ACTION)

    const val TICK_ACTION = ACTION

    /**
     * Fallback for a dead host process: an alarm at the next minute boundary (clock widget present) or
     * the next midnight (only the date widget present). Re-armed by every render and every tick.
     */
    fun arm(ctx: Context) {
        val a = ctx.applicationContext
        val am = a.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(a, REQ, tickIntent(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val clock = WeatherJob.hasWidgets(a, ClockWidgetProvider::class.java)
        val head = WeatherJob.hasWidgets(a, HeadWidgetProvider::class.java)
        if (!clock && !head) {
            am.cancel(pi)
            return
        }
        val now = System.currentTimeMillis()
        val at = if (clock) (now / 60_000L + 1) * 60_000L + 300L else {
            val c = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 2); set(java.util.Calendar.MILLISECOND, 0)
            }
            c.timeInMillis
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        } catch (e: Exception) {
            Log.w("NdTicker", "arm failed", e)
        }
    }

    /** one tick: re-render the clock and the date, re-arm */
    fun tick(ctx: Context) {
        val a = ctx.applicationContext
        ClockWidgetProvider.updateAll(a)
        HeadWidgetProvider.updateAll(a, onlyIfDateChanged = true)
        arm(a)
    }
}
