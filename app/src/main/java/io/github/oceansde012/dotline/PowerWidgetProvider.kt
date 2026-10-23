package io.github.oceansde012.dotline

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Power 3x1 (DESIGN_SPEC.md 2.5): dot-matrix `81` + `%`, a 10-dot bar (one dot = 10%) and a status label.
 * Every render reads the sticky ACTION_BATTERY_CHANGED fresh (never a default), on onUpdate, on resize and
 * from the runtime receivers of [LiveHost]; the 15 min [WeatherJob] is the fallback.
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
    class State(
        val level: Int,
        val charging: Boolean,
        val full: Boolean,
        val tempC: Float,
        val remainingMs: Long,
        val status: Int = BatteryManager.BATTERY_STATUS_UNKNOWN,
    ) {
        val low get() = level <= LOW_PERCENT
        val key get() = listOf(level, charging, full, tempC > HOT_C)
    }

    companion object {
        private const val LOW_PERCENT = 15
        private const val HOT_C = 40f
        private const val DOTS = 10
        private var lastKey: List<Any>? = null

        /** the face for a [widthDp] x [heightDp] tile from the CURRENT battery state (gallery, generated picker preview) */
        fun buildPreview(ctx: Context, widthDp: Float, heightDp: Float): RemoteViews {
            val a = ctx.applicationContext
            return PowerWidgetProvider().render(a, widthDp, heightDp, current(a))
        }

        /** fresh read of the sticky battery broadcast */
        fun current(ctx: Context): State {
            val a = ctx.applicationContext
            val i = a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return if (i != null) state(a, i) else State(0, false, false, 0f, -1L)
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
            for (id in ids) {
                val sz = WidgetSize.of(a, mgr, id, 230f, 62f)
                try {
                    mgr.updateAppWidget(id, p.render(a, sz.width, sz.height, s))
                } catch (e: Exception) {
                    Log.w("NdPower", "render failed", e)
                }
            }
        }

        fun state(ctx: Context, i: Intent): State {
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
            return State(level, charging, full, temp, remaining, status)
        }
    }

    private fun render(ctx: Context, w: Float, h: Float, s: State): RemoteViews {
        val density = ctx.resources.displayMetrics.density
        val pad = Tile.pad(h).coerceAtLeast(12f)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_power)
        Tile.apply(rv, R.id.tile, R.id.zone, h)

        // one content band, shared by hero and right column so they sit on the same top / bottom lines
        val hc = minOf(h - 2f * pad, 38f).coerceAtLeast(20f)
        val top = (h - hc) / 2f
        val low = s.low

        val text = s.level.toString()
        val pitchN = DotText.pitchFor(text, 0.36f * w, hc)
        val num = DotText.art(text, pitchN, density, if (low) Nd.ACCENT else Nd.FG)
        rv.putArt(R.id.num, num)
        val numTop = top + hc - num.hDp   // numerals stand on the bottom line
        rv.margins(R.id.num, start = pad + 2f, top = numTop)
        val unit = (0.2f * h).coerceIn(10f, 13f)
        rv.setTextViewTextSize(R.id.unit, DIP, unit)
        val unitW = unit * 0.7f + 2f
        rv.place(R.id.unit, pad + 2f + num.wDp + 4f, top + hc - 16f, unitW + 4f, 16f)

        val x0 = pad + 2f + num.wDp + 4f + unitW + 14f
        val colW = (w - pad - 2f - x0).coerceAtLeast(50f)
        val pitchB = minOf(colW / DOTS, hc * 0.48f)
        val lit = ceil(s.level / 10.0).toInt().coerceIn(0, DOTS)
        val accentDot = if (!low && s.charging && lit > 0) lit - 1 else -1
        val bar = DotText.bar(DOTS, lit, Nd.FG, accentDot, pitchB, density)
        rv.putArt(R.id.bar, bar)
        rv.margins(R.id.bar, start = x0, top = top)

        // status line under the bar, bottom aligned with the numerals
        val statusH = (hc - pitchB - 4f).coerceAtLeast(12f)
        val size = (statusH * 0.8f).coerceIn(9f, 11.5f)
        rv.setTextViewTextSize(R.id.status, DIP, size)
        val maxChars = (colW / (size * 0.64f)).toInt()
        rv.setTextViewText(R.id.status, statusText(ctx, s, maxChars))
        rv.place(R.id.status, x0, top + hc - statusH, colW, statusH)
        val hot = !s.charging && !s.full && s.tempC > HOT_C && !low
        rv.setTextColor(R.id.status, if (hot) Nd.ACCENT else Nd.DIM)

        rv.setOnClickPendingIntent(R.id.zone, Taps.battery(ctx))
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
}
