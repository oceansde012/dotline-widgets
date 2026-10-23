package io.github.oceansde012.dotline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews

/**
 * Buds widget 2x1 (DESIGN_SPEC.md 2.6): dot earbuds glyph, `L 80 · R 75` + `CASE 40` (or one hero percent),
 * `NOT CONNECTED` when no device is connected. Accent: a red dot when any bud is at or below 15%.
 */
class BudsWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        WeatherJob.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MediaTracker.ensureStarted(context)
        WeatherJob.schedule(context)
        updateAll(context)
        BudsRepo.refreshAsync(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        updateAll(context)
    }

    override fun onDisabled(context: Context) {
        WeatherJob.cancelIfUnused(context)
    }

    companion object {
        private const val LOW_PERCENT = 15
        private const val REQ_BT = 140
        private const val REQ_APP = 141

        /** the same face the home screen gets, from the last known earbud state (the app's widget gallery) */
        fun buildPreview(ctx: Context, widthDp: Float, heightDp: Float): RemoteViews {
            val a = ctx.applicationContext
            return BudsWidgetProvider().render(a, widthDp, heightDp, BudsRepo.current(a))
        }

        fun updateAll(ctx: Context) {
            val a = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(a)
            val ids = mgr.getAppWidgetIds(ComponentName(a, BudsWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val state = BudsRepo.current(a)
            val p = BudsWidgetProvider()
            for (id in ids) mgr.updateAppWidget(id, p.build(a, mgr, id, state))
        }
    }

    private fun build(ctx: Context, mgr: AppWidgetManager, id: Int, b: Buds): RemoteViews {
        val sz = WidgetSize.of(ctx, mgr, id, 150f, 62f)
        return render(ctx, sz.width, sz.height, b)
    }

    private fun render(ctx: Context, w: Float, h: Float, b: Buds): RemoteViews {
        val pad = Tile.pad(h).coerceAtLeast(10f)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_buds)
        Tile.apply(rv, R.id.tile, R.id.zone, h)

        val density = ctx.resources.displayMetrics.density
        val glyph = (h - 2f * pad).coerceIn(28f, 56f)
        rv.margins(R.id.glyph, start = pad + 2f)
        rv.size(R.id.glyph, glyph, glyph)
        val textStart = pad + 2f + glyph + 10f
        rv.margins(R.id.col, start = textStart)
        val avail = (w - textStart - pad - 4f).coerceAtLeast(40f)
        rv.setViewLayoutWidth(R.id.col, avail, DIP)

        val connected = b.permission && b.connected
        rv.setImageViewBitmap(
            R.id.glyph, WeatherArt.buds(WeatherArt.px(glyph, density), if (connected) Nd.FG else Nd.FAINT)
        )

        val label = fit(avail, 12f)
        val small = fit(avail, 11f)
        rv.setViewVisibility(R.id.hero, View.GONE)
        rv.setViewVisibility(R.id.line2, View.GONE)
        rv.setTextColor(R.id.line1, Nd.FG)

        when {
            !b.permission -> {
                rv.setTextViewText(R.id.line1, ctx.getString(R.string.buds_allow))
                rv.setTextColor(R.id.line1, Nd.DIM)
            }
            !b.connected -> {
                rv.setTextViewText(R.id.line1, ctx.getString(R.string.buds_not_connected))
                rv.setTextColor(R.id.line1, Nd.DIM)
            }
            b.hasSides -> {
                val parts = ArrayList<String>(2)
                if (b.left >= 0) parts.add("L ${b.left}")
                if (b.right >= 0) parts.add("R ${b.right}")
                rv.setTextViewText(R.id.line1, parts.joinToString(" · "))
                if (b.case >= 0) {
                    rv.setTextViewText(R.id.line2, ctx.getString(R.string.buds_case, b.case))
                    rv.setViewVisibility(R.id.line2, View.VISIBLE)
                }
            }
            b.single >= 0 -> {
                rv.setTextViewText(R.id.hero, "${b.single}%")
                rv.setTextViewTextSize(R.id.hero, DIP, (0.42f * h).coerceIn(22f, 36f))
                rv.setViewVisibility(R.id.hero, View.VISIBLE)
                rv.setViewVisibility(R.id.line1, View.GONE)
            }
            else -> {   // connected, battery unknown
                rv.setTextViewText(R.id.line1, b.name.ifBlank { ctx.getString(R.string.buds_connected) })
                rv.setTextViewText(R.id.line2, ctx.getString(R.string.buds_connected))
                rv.setViewVisibility(R.id.line2, if (b.name.isBlank()) View.GONE else View.VISIBLE)
            }
        }
        rv.setTextViewTextSize(R.id.line1, DIP, label)
        rv.setTextViewTextSize(R.id.line2, DIP, small)

        val low = connected && b.lowest in 0..LOW_PERCENT
        val dot = maxOf(6f, 0.12f * h)
        rv.size(R.id.dot, dot, dot)
        rv.margins(R.id.dot, top = pad, end = pad)
        rv.setViewVisibility(R.id.dot, if (low) View.VISIBLE else View.GONE)

        rv.setOnClickPendingIntent(R.id.zone, tapIntent(ctx, b))
        return rv
    }

    /** label size that fits ~16 chars of Space Mono (0.6 em + 0.08 em tracking) into [avail] dp */
    private fun fit(avail: Float, max: Float): Float = minOf(max, avail / (13f * 0.68f)).coerceAtLeast(9f)

    /** Bluetooth settings, or this app (where the permission is asked) when the permission is missing */
    private fun tapIntent(ctx: Context, b: Buds): PendingIntent {
        if (!b.permission) {
            val self = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(WeatherWidgetProvider.EXTRA_SECTION, MainActivity.SECTION_BLUETOOTH)
            return PendingIntent.getActivity(ctx, REQ_APP, self, immutable())
        }
        val bt = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, REQ_BT, bt, immutable())
    }
}
