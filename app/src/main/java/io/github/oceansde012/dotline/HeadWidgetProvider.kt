package io.github.oceansde012.dotline

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar
import java.util.Date

/**
 * Head widget 5x1: `FRI 23 OCT` (a TextClock, so it rolls over without any update) and the next alarm.
 * The provider re-renders on alarm / time / zone / locale changes (manifest receiver, also registered
 * at runtime from the long-lived media tracker) and when resized.
 */
class HeadWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, build(context, appWidgetManager, id))
        registerRuntime(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, build(context, appWidgetManager, appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in REFRESH_ACTIONS) updateAll(context)
    }

    companion object {
        private val REFRESH_ACTIONS = setOf(
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
        )
        private const val REQ_CALENDAR = 110
        private const val REQ_ALARMS = 111
        private var runtimeRegistered = false

        /** the same face the home screen gets (the app's widget gallery); the width does not matter to this layout */
        fun buildPreview(ctx: Context, widthDp: Float, heightDp: Float): RemoteViews =
            HeadWidgetProvider().render(ctx.applicationContext, heightDp)

        fun updateAll(ctx: Context) {
            val a = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(a)
            val ids = mgr.getAppWidgetIds(ComponentName(a, HeadWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val p = HeadWidgetProvider()
            for (id in ids) mgr.updateAppWidget(id, p.build(a, mgr, id))
        }

        /** belt and braces next to the manifest receiver: the listener process is long-lived */
        fun registerRuntime(ctx: Context) {
            if (runtimeRegistered) return
            val a = ctx.applicationContext
            val filter = IntentFilter().apply { REFRESH_ACTIONS.forEach { addAction(it) } }
            val rcv = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = updateAll(context)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                a.registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                a.registerReceiver(rcv, filter)
            }
            runtimeRegistered = true
        }
    }

    private fun build(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        val sz = WidgetSize.of(ctx, mgr, id, 380f, 56f)
        return render(ctx, sz.height)
    }

    private fun render(ctx: Context, h: Float): RemoteViews {
        val pad = Tile.pad(h).coerceAtLeast(10f)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_head)
        Tile.apply(rv, R.id.tile, null, h)
        val text = (0.24f * h).coerceIn(11f, 13f)
        rv.setTextViewTextSize(R.id.date, DIP, text)
        rv.setTextViewTextSize(R.id.alarm, DIP, text)
        rv.margins(R.id.date, start = pad + 2f)
        rv.margins(R.id.alarm, end = pad + 2f)

        val alarm = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        if (alarm == null) {
            rv.setViewVisibility(R.id.alarm, View.GONE)
        } else {
            rv.setViewVisibility(R.id.alarm, View.VISIBLE)
            rv.setTextViewText(R.id.alarm, ctx.getString(R.string.alarm_fmt, alarmLabel(ctx, alarm.triggerTime)))
        }
        rv.setOnClickPendingIntent(R.id.zone_date, openCalendar(ctx))
        rv.setOnClickPendingIntent(R.id.zone_alarm, openClock(ctx, REQ_ALARMS))
        return rv
    }

    /** `07:30`, or `MON 07:30` when the alarm is not today */
    private fun alarmLabel(ctx: Context, at: Long): String {
        val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
        val time = DateFormat.format(pattern, Date(at)).toString()
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = at }
        val today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return if (today) time else DateFormat.format("EEE", Date(at)).toString() + " " + time
    }

    private fun openCalendar(ctx: Context): PendingIntent {
        val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, REQ_CALENDAR, i, immutable())
    }
}

private const val SAMSUNG_CLOCK = "com.sec.android.app.clockpackage"

/** Clock app on its alarms page; Samsung Clock launcher, then this app, as fallbacks */
internal fun openClock(ctx: Context, req: Int): PendingIntent {
    val pm = ctx.packageManager
    val alarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val target = when {
        alarms.resolveActivity(pm) != null -> alarms
        else -> pm.getLaunchIntentForPackage(SAMSUNG_CLOCK)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return PendingIntent.getActivity(ctx, req, target, immutable())
}