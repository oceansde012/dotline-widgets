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
 * Head widget 5x1: the day number as dot-matrix, weekday + month labels, the next alarm (label + dot time)
 * or "no alarm" on the right. The date rolls over via [Ticker] (midnight alarm / minute tick) and the
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
        rv.setContentDescription(R.id.day, dayText)
        val top = (h - num.hDp) / 2f
        rv.margins(R.id.day, start = pad + 2f, top = top)

        // weekday over month, beside the number, same band
        val loc = Locale.getDefault()
        val weekday = SimpleDateFormat("EEE", loc).format(now).uppercase(loc)
        val month = SimpleDateFormat("LLL", loc).format(now).uppercase(loc)
        val colX = pad + 2f + num.wDp + 12f
        val colW = (w * 0.26f).coerceAtLeast(60f)
        val size = (0.21f * h).coerceIn(11f, 14f)
        val wd = rv.label(ctx, R.id.weekday, weekday, size, Nd.FG, colW, medium = true)
        val mo = rv.label(ctx, R.id.month, month, size - 1f, Nd.DIM, colW)
        val gap = 3f
        val colTop = top + (num.hDp - (wd.hDp + gap + mo.hDp)) / 2f
        rv.margins(R.id.weekday, start = colX, top = colTop)
        rv.margins(R.id.month, start = colX, top = colTop + wd.hDp + gap)

        // right: next alarm (label over dot-matrix time), or an honest "no alarm"
        val rightEdge = w - pad - 2f
        val maxRight = (rightEdge - (colX + colW + 12f)).coerceAtLeast(60f)
        val alarm = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        if (alarm == null) {
            rv.setViewVisibility(R.id.alarm_time, View.GONE)
            val none = rv.label(ctx, R.id.alarm_label, ctx.getString(R.string.head_no_alarm).uppercase(loc), size - 1f, Nd.DIM, maxRight, minSizeDp = 9f)
            rv.margins(R.id.alarm_label, start = rightEdge - none.wDp, top = top + (num.hDp - none.hDp) / 2f)
        } else {
            val (dayPart, timePart) = alarmParts(ctx, alarm.triggerTime)
            val caption = (ctx.getString(R.string.head_alarm) + (dayPart?.let { " · $it" } ?: "")).uppercase(loc)
            val cap = rv.label(ctx, R.id.alarm_label, caption, size - 1f, Nd.DIM, maxRight, minSizeDp = 9f)
            val timeH = (num.hDp - cap.hDp - 4f).coerceAtLeast(12f)
            val tp = DotText.pitchFor(timePart, maxRight, timeH)
            val t = DotText.art(timePart, tp, density)
            rv.putArt(R.id.alarm_time, t)
            rv.setContentDescription(R.id.alarm_time, timePart)
            rv.setViewVisibility(R.id.alarm_time, View.VISIBLE)
            val blockH = cap.hDp + 4f + t.hDp
            val y0 = top + (num.hDp - blockH) / 2f
            rv.margins(R.id.alarm_label, start = rightEdge - cap.wDp, top = y0)
            rv.margins(R.id.alarm_time, start = rightEdge - t.wDp, top = y0 + cap.hDp + 4f)
        }
        rv.setOnClickPendingIntent(R.id.zone_date, Taps.calendar(ctx))
        rv.setOnClickPendingIntent(R.id.zone_alarm, Taps.alarms(ctx))
        return rv
    }

    /** (weekday or null when the alarm is today, `07:30`) */
    private fun alarmParts(ctx: Context, at: Long): Pair<String?, String> {
        val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
        val time = DateFormat.format(pattern, Date(at)).toString()
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = at }
        val today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val day = if (today) null else SimpleDateFormat("EEE", Locale.getDefault()).format(Date(at)).uppercase()
        return day to time
    }
}
