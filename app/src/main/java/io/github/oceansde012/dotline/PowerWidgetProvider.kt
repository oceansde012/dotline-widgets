package io.github.oceansde012.dotline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.widget.RemoteViews
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * ND_Power 3x1 (DESIGN_SPEC.md 2.5): `77 %`, a 10-dot bar (one dot = 10%) and a status label. Driven by
 * [LiveHost] (battery / power broadcasts) with the 15 min [WeatherJob] as a fallback.
 */
class PowerWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        WeatherJob.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MediaTracker.ensureStarted(context)   // registers the runtime receivers (LiveHost)
        WeatherJob.schedule(context)
        refresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        refresh(context)
    }

    override fun onDisabled(context: Context) {
        WeatherJob.cancelIfUnused(context)
    }

    /** snapshot of the battery state; what the face depends on */
    private class State(
        val level: Int,
        val charging: Boolean,
        val full: Boolean,
        val tempC: Float,
        val remainingMs: Long,
    ) {
        val low get() = level <= LOW_PERCENT
        val key get() = listOf(level, charging, full, tempC > HOT_C)
    }

    companion object {
        private const val LOW_PERCENT = 15
        private const val HOT_C = 40f
        private const val REQ_OPEN = 130
        private const val DOTS = 10
        private var lastKey: List<Any>? = null

        /** the same face the home screen gets, from the current battery state (the app's widget gallery) */
        fun buildPreview(ctx: Context, widthDp: Float, heightDp: Float): RemoteViews {
            val a = ctx.applicationContext
            val i = a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 77)
            return PowerWidgetProvider().render(a, widthDp, heightDp, state(a, i))
        }

        /** re-render from the current sticky battery state, whatever it was before */
        fun refresh(ctx: Context) {
            val a = ctx.applicationContext
            val i = a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            onBattery(a, i, force = true)
        }

        /** ACTION_BATTERY_CHANGED fires on every voltage / temperature tick: render on 1% or status change only */
        fun onBattery(ctx: Context, intent: Intent, force: Boolean) {
            val a = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(a)
            val ids = mgr.getAppWidgetIds(ComponentName(a, PowerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val s = state(a, intent)
            if (!force && s.key == lastKey) return
            lastKey = s.key
            val p = PowerWidgetProvider()
            for (id in ids) mgr.updateAppWidget(id, p.build(a, mgr, id, s))
        }

        private fun state(ctx: Context, i: Intent): State {
            val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val level = if (raw < 0) 0 else (raw * 100f / scale).roundToInt().coerceIn(0, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val full = status == BatteryManager.BATTERY_STATUS_FULL || (plugged && level >= 100)
            val charging = !full && status == BatteryManager.BATTERY_STATUS_CHARGING
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            val remaining = if (charging) {
                try {
                    ctx.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1L
                } catch (e: Exception) {
                    -1L
                }
            } else {
                -1L
            }
            return State(level, charging, full, temp, remaining)
        }
    }

    private fun build(ctx: Context, mgr: AppWidgetManager, id: Int, s: State): RemoteViews {
        val sz = WidgetSize.of(ctx, mgr, id, 230f, 62f)
        return render(ctx, sz.width, sz.height, s)
    }

    private fun render(ctx: Context, w: Float, h: Float, s: State): RemoteViews {
        val pad = Tile.pad(h).coerceAtLeast(10f)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_power)
        Tile.apply(rv, R.id.tile, R.id.zone, h)

        // hero: share of the height, three digits ("100") must still leave a usable bar
        val hero = (0.5f * h).coerceIn(24f, 46f)
        val unit = (0.2f * h).coerceIn(10f, 13f)
        rv.setTextViewTextSize(R.id.pct, DIP, hero)
        rv.setTextViewTextSize(R.id.unit, DIP, unit)
        rv.setTextViewText(R.id.pct, s.level.toString())
        rv.margins(R.id.hero_row, start = pad + 4f)
        val heroReserve = 1.7f * hero + unit + 4f
        val colW = (w - 2f * pad - 4f - heroReserve - 10f).coerceAtLeast(60f)
        rv.margins(R.id.col, end = pad + 2f)
        rv.setViewLayoutWidth(R.id.col, colW, DIP)
        rv.size(R.id.bar, colW, colW / DOTS)

        // accent: low battery colours the hero; otherwise charging colours the last lit dot. Never both.
        val low = s.low
        rv.setTextColor(R.id.pct, if (low) Nd.ACCENT else Nd.FG)
        val lit = ceil(s.level / 10.0).toInt().coerceIn(0, DOTS)
        val accentDot = if (!low && s.charging && lit > 0) lit - 1 else -1
        rv.setImageViewBitmap(R.id.bar, WeatherArt.bar(DOTS, lit, Nd.FG, accentDot))

        // status label
        val size = (0.17f * h).coerceIn(9f, 11f)
        rv.setTextViewTextSize(R.id.status, DIP, size)
        val maxChars = (colW / (size * 0.68f)).toInt()
        val status = statusText(ctx, s, maxChars)
        rv.setTextViewText(R.id.status, status)
        val hot = !s.charging && !s.full && s.tempC > HOT_C && !low
        rv.setTextColor(R.id.status, if (hot) Nd.ACCENT else Nd.DIM)

        rv.setOnClickPendingIntent(R.id.zone, openBattery(ctx))
        return rv
    }

    /** `CHARGING · FULL IN 1H 05M`, shortened to what fits the column */
    private fun statusText(ctx: Context, s: State, maxChars: Int): String {
        if (s.full) return ctx.getString(R.string.power_charged)
        if (s.charging) {
            val min = if (s.remainingMs > 0) ((s.remainingMs + 59_999L) / 60_000L).toInt() else -1
            if (min < 0) return ctx.getString(R.string.power_charging)
            val t = if (min >= 60) ctx.getString(R.string.power_h_m, min / 60, min % 60) else ctx.getString(R.string.power_m, min)
            val long = ctx.getString(R.string.power_charging_full_in, t)
            if (long.length <= maxChars) return long
            val mid = ctx.getString(R.string.power_charging_time, t)
            return if (mid.length <= maxChars) mid else ctx.getString(R.string.power_charging)
        }
        val base = ctx.getString(R.string.power_on_battery)
        if (s.tempC > HOT_C && !s.low) {
            val withTemp = base + " · " + s.tempC.roundToInt() + "°C"
            if (withTemp.length <= maxChars) return withTemp
        }
        return base
    }

    /** battery usage page, this app if the settings screen is not available */
    private fun openBattery(ctx: Context): PendingIntent {
        val usage = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = if (usage.resolveActivity(ctx.packageManager) != null) usage
        else Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, REQ_OPEN, target, immutable())
    }
}
