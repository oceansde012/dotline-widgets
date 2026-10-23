package io.github.oceansde012.dotline

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Head widget 5x1: the day number as dot-matrix, weekday + month in the label font, the next alarm
 * (bell + time) on the right. The date rolls over via [Ticker] (midnight alarm / minute tick) and the
 * provider re-renders on alarm / time / zone / locale changes.
 */
class HeadWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
        registerRuntime(context)
        Ticker.arm(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        updateAll(context)
    }

    override fun onEnabled(context: Context) = Ticker.arm(context)

    override fun onDisabled(context: Context) = Ticker.arm(context)

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
        private var runtimeRegistered = false
        private var lastDay = -1

        /** the same face the home screen gets (gallery, generated picker preview) */
        fun buildPreview(ctx: Context, widthDp: Float, heightDp: Float): RemoteViews =
            HeadWidgetProvider().render(ctx.applicationContext, widthDp, heightDp)

        fun updateAll(ctx: Context, onlyIfDateChanged: Boolean = false) {
            val a = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(a)
            val ids = mgr.getAppWidgetIds(ComponentName(a, HeadWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            if (onlyIfDateChanged && day == lastDay) return
            lastDay = day
            val p = HeadWidgetProvider()
            for (id in ids) {
                val sz = WidgetSize.of(a, mgr, id, 380f, 58f)
                try {
                    mgr.updateAppWidget(id, p.render(a, sz.width, sz.height))
                } catch (e: Exception) {
                    Log.w("NdHead", "render failed", e)
                }
            }
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

    private fun render(ctx: Context, w: Float, h: Float): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_head)
        val density = ctx.resources.displayMetrics.density
        val pad = Tile.pad(h).coerceAtLeast(12f)
        Tile.apply(rv, R.id.tile, null, h)

        // left: day number, ~62% of the tile height, vertically centred
        val now = Date()
        val dayText = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString()
        val numH = minOf(0.62f * h, h - 2f * pad + 4f).coerceAtLeast(18f)
        val pitch = DotText.pitchFor(dayText, 0.4f * w, numH)
        val num = DotText.art(dayText, pitch, density)
        rv.putArt(R.id.day, num)
        val top = (h - num.hDp) / 2f
        rv.margins(R.id.day, start = pad + 2f, top = top)

        // weekday over month, beside the number, same height
        val loc = Locale.getDefault()
        val weekday = SimpleDateFormat("EEE", loc).format(now).uppercase(loc)
        val month = SimpleDateFormat("LLL", loc).format(now).uppercase(loc)
        val colX = pad + 2f + num.wDp + 12f
        rv.setTextViewText(R.id.weekday, weekday)
        rv.setTextViewText(R.id.month, month)
        val size = (0.21f * h).coerceIn(11f, 14f)
        rv.setTextViewTextSize(R.id.weekday, DIP, size)
        rv.setTextViewTextSize(R.id.month, DIP, size - 1f)
        rv.place(R.id.date_col, colX, top, (w * 0.3f).coerceAtLeast(60f), num.hDp)

        // right: bell + next alarm, dim
        val alarm = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        if (alarm == null) {
            rv.setViewVisibility(R.id.alarm_row, View.GONE)
        } else {
            rv.setViewVisibility(R.id.alarm_row, View.VISIBLE)
            rv.setTextViewText(R.id.alarm, alarmLabel(ctx, alarm.triggerTime))
            rv.setTextViewTextSize(R.id.alarm, DIP, size)
            val bell = DotText.art(DotText.BELL.toString(), (size * 1.15f) / DotText.ROWS, density, Nd.DIM, matrix = false)
            rv.putArt(R.id.bell, bell)
            rv.margins(R.id.alarm_row, end = pad + 2f)
        }
        rv.setOnClickPendingIntent(R.id.zone_date, Taps.calendar(ctx))
        rv.setOnClickPendingIntent(R.id.zone_alarm, Taps.alarms(ctx))
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
        return if (today) time else SimpleDateFormat("EEE", Locale.getDefault()).format(Date(at)).uppercase() + " " + time
    }
}
