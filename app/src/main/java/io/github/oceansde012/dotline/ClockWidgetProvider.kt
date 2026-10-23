package io.github.oceansde012.dotline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.RemoteViews

/**
 * Time widget (3x2). The digits are two native TextClocks with the Doto hero font, so the launcher
 * ticks them itself; this provider only runs when the widget is added or resized.
 */
class ClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, build(context, appWidgetManager, id))
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, build(context, appWidgetManager, appWidgetId))
    }

    companion object {
        /** the same face the home screen gets, for a [widthDp] x [heightDp] tile (the app's widget gallery) */
        fun buildPreview(ctx: Context, widthDp: Float, heightDp: Float): RemoteViews =
            ClockWidgetProvider().render(ctx.applicationContext, widthDp, heightDp)
    }

    private fun build(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        val sz = WidgetSize.of(ctx, mgr, id, 230f, 190f)
        return render(ctx, sz.width, sz.height)
    }

    private fun render(ctx: Context, w: Float, h: Float): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_clock)
        // DESIGN_SPEC.md 2.2: digits min(0.46 H, (W - 2P) / 2.35), gap 0.06 H, red dot at P from the top-right
        val pad = Tile.pad(h)
        Tile.apply(rv, R.id.tile, R.id.zone, h)
        val digits = minOf(0.46f * h, (w - 2f * pad) / 2.35f).coerceAtLeast(12f)
        rv.setTextViewTextSize(R.id.hours, DIP, digits)
        rv.setTextViewTextSize(R.id.minutes, DIP, digits)
        rv.margins(R.id.minutes, start = 0.06f * h)
        val dot = maxOf(6f, 0.04f * h)
        rv.size(R.id.dot, dot, dot)
        rv.margins(R.id.dot, top = pad, end = pad)
        rv.setOnClickPendingIntent(R.id.zone, openClock(ctx, 100))
        return rv
    }
}
