package io.github.oceansde012.dotline

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.widget.RemoteViews
import java.util.Calendar

/**
 * Time widget (3x2). The digits are our own dot-matrix bitmaps (no TextClock, no font), re-rendered every
 * minute: by ACTION_TIME_TICK in the long-lived host ([LiveHost]), and by an AlarmManager alarm at the next
 * minute boundary when the process is dead ([Ticker]). Both re-arm in every render.
 */
class ClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MediaTracker.ensureStarted(context)
        updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        updateAll(context)
    }

    override fun onEnabled(context: Context) {
        Ticker.arm(context)
    }

    override fun onDisabled(context: Context) {
        Ticker.arm(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Ticker.TICK_ACTION, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Ticker.tick(context)
        }
    }

    companion object {
        fun updateAll(ctx: Context) {
            val a = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(a)
            val ids = mgr.getAppWidgetIds(ComponentName(a, ClockWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val p = ClockWidgetProvider()
            for (id in ids) {
                val sz = WidgetSize.of(a, mgr, id, 230f, 190f)
                try {
                    mgr.updateAppWidget(id, p.render(a, sz.width, sz.height))
                } catch (e: Exception) {
                    Log.w("NdClock", "render failed", e)
                }
            }
            Ticker.arm(a)
        }

        /** the same face the home screen gets, for a [widthDp] x [heightDp] tile (gallery, generated picker preview) */
        fun buildPreview(ctx: Context, widthDp: Float, heightDp: Float): RemoteViews =
            ClockWidgetProvider().render(ctx.applicationContext, widthDp, heightDp)
    }

    private fun render(ctx: Context, w: Float, h: Float): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_clock)
        val density = ctx.resources.displayMetrics.density
        val pad = Tile.pad(h)
        Tile.apply(rv, R.id.tile, R.id.zone, h)

        val now = Calendar.getInstance()
        val hh = if (DateFormat.is24HourFormat(ctx)) now.get(Calendar.HOUR_OF_DAY) else (now.get(Calendar.HOUR) .let { if (it == 0) 12 else it })
        val hours = "%02d".format(hh)
        val mins = "%02d".format(now.get(Calendar.MINUTE))

        // two stacked rows (HH over mm) or one row, whichever gives the larger dots
        val bw = (w - 2f * pad).coerceAtLeast(40f)
        val bh = (h - 2f * pad).coerceAtLeast(30f)
        val colsOne = DotText.cols(hours)
        val stacked = minOf(bw / colsOne, bh / (2 * DotText.ROWS + 2))
        val row = minOf(bw / (2 * colsOne + 3), bh / DotText.ROWS)
        val hArt: DotText.Art
        val mArt: DotText.Art
        if (stacked >= row) {
            hArt = DotText.art(hours, stacked, density)
            mArt = DotText.art(mins, stacked, density)
            val totalH = hArt.hDp * 2 + 2 * stacked
            val x = (w - hArt.wDp) / 2f
            val y = (h - totalH) / 2f
            rv.putArt(R.id.hours, hArt); rv.margins(R.id.hours, start = x, top = y)
            rv.putArt(R.id.minutes, mArt); rv.margins(R.id.minutes, start = x, top = y + hArt.hDp + 2 * stacked)
        } else {
            hArt = DotText.art(hours, row, density)
            mArt = DotText.art(mins, row, density)
            val totalW = hArt.wDp + mArt.wDp + 3 * row
            val x = (w - totalW) / 2f
            val y = (h - hArt.hDp) / 2f
            rv.putArt(R.id.hours, hArt); rv.margins(R.id.hours, start = x, top = y)
            rv.putArt(R.id.minutes, mArt); rv.margins(R.id.minutes, start = x + hArt.wDp + 3 * row, top = y)
        }
        // the one red element: a single matrix-sized dot in the top-right corner
        val dot = maxOf(7f, DotText.DOT * minOf(stacked, row) * 1.1f)
        rv.size(R.id.dot, dot, dot)
        rv.margins(R.id.dot, top = pad, end = pad)
        rv.setOnClickPendingIntent(R.id.zone, Taps.clock(ctx))
        rv.setContentDescription(R.id.zone, Lang.wrap(ctx).getString(R.string.cd_open_clock))
        return rv
    }
}
