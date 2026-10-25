package io.github.oceansde012.dotline

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
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
        stopPulse()
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
        private const val PULSE_STEP_MS = 200L
        private const val PULSE_PERIOD_MS = 2600L
        private const val PULSE_MIN = 0.2f
        private var lastKey: List<Any>? = null

        // while charging (screen on only) the one red dot, the last lit one, breathes: a separate overlay dot whose
        // alpha is the only thing a tick sends (a few bytes, no bitmap). Off / solid red when the screen is off,
        // Battery Saver is on or the user removed animations.
        private val main = Handler(Looper.getMainLooper())
        private var pulseApp: Context? = null
        private var pulseOn = false
        private val pulseTick = Runnable { pulseStep() }

        private fun litDots(level: Int) = ceil(level / 10.0).toInt().coerceIn(0, DOTS)

        /** the red dot is the last lit one while charging, unless the level is low (then the hero is red; never both) */
        private fun redDot(s: State) = s.charging && !s.low && litDots(s.level) > 0

        /** the red dot may move: screen on, no Battery Saver, system animations not turned off */
        private fun animated(a: Context): Boolean {
            val pm = a.getSystemService(PowerManager::class.java)
            if (pm != null && (!pm.isInteractive || pm.isPowerSaveMode)) return false
            return android.provider.Settings.Global.getFloat(a.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }

        /** 0..255, a slow cosine between [PULSE_MIN] and full; time based, so a full render and the ticks agree */
        private fun pulseAlpha(): Int {
            val t = (System.currentTimeMillis() % PULSE_PERIOD_MS).toDouble() / PULSE_PERIOD_MS
            val k = 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * t)
            return ((PULSE_MIN + (1f - PULSE_MIN) * k) * 255).roundToInt().coerceIn(0, 255)
        }

        private fun stopPulse() {
            main.removeCallbacks(pulseTick)
            pulseOn = false
            pulseApp = null
        }

        /** (re)starts the tick chain when the red dot is shown and may move; a no-op chain is cheap to call again */
        private fun schedulePulse(a: Context, s: State) {
            main.removeCallbacks(pulseTick)
            pulseApp = a
            pulseOn = redDot(s) && animated(a)
            if (pulseOn) main.postDelayed(pulseTick, PULSE_STEP_MS)
        }

        /** the screen came on: pick the pulse up again */
        fun resumePulse(ctx: Context) {
            val a = ctx.applicationContext
            if (AppWidgetManager.getInstance(a).getAppWidgetIds(ComponentName(a, PowerWidgetProvider::class.java)).isEmpty()) return
            schedulePulse(a, current(a))
        }

        private fun pulseStep() {
            val a = pulseApp ?: return
            val mgr = AppWidgetManager.getInstance(a)
            val ids = mgr.getAppWidgetIds(ComponentName(a, PowerWidgetProvider::class.java))
            if (ids.isEmpty()) {
                pulseOn = false
                return
            }
            val live = redDot(current(a)) && animated(a)   // the live battery, not a snapshot
            // when it stops (screen off, unplugged, saver) the dot is left solid, never at a dim phase
            val rv = RemoteViews(a.packageName, R.layout.widget_power)
            rv.setInt(R.id.pulse, "setImageAlpha", if (live) pulseAlpha() else 255)
            try {
                mgr.partiallyUpdateAppWidget(ids, rv)
            } catch (e: Exception) {
                Log.w("NdPower", "pulse step failed", e)
            }
            if (live) main.postDelayed(pulseTick, PULSE_STEP_MS) else pulseOn = false
        }

        /** the face for a [widthDp] x [heightDp] tile from the CURRENT battery state (gallery, generated picker preview) */
        fun buildPreview(ctx: Context, widthDp: Float, heightDp: Float): RemoteViews {
            val a = ctx.applicationContext
            return PowerWidgetProvider().render(a, widthDp, heightDp, current(a), live = false)
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
            if (!force && s.key == lastKey) {
                if (!pulseOn) schedulePulse(a, s)   // a missed SCREEN_ON must not leave the dot dead until the next 1%
                return
            }
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
            schedulePulse(a, s)
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

    /** [live]: false for the gallery / picker preview, which must not freeze at a dim phase of the pulse */
    private fun render(ctx: Context, w: Float, h: Float, s: State, live: Boolean = true): RemoteViews {
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
        rv.setContentDescription(R.id.num, "$text%")
        val numTop = top + hc - num.hDp   // numerals stand on the bottom line
        rv.margins(R.id.num, start = pad + 2f, top = numTop)
        val unitSize = (0.2f * h).coerceIn(10f, 13f)
        val unit = rv.label(ctx, R.id.unit, "%", unitSize, Nd.DIM, 30f)
        val unitW = unit.wDp
        rv.margins(R.id.unit, start = pad + 2f + num.wDp + 4f, top = top + hc - unit.hDp)

        val x0 = pad + 2f + num.wDp + 4f + unitW + 14f
        val colW = (w - pad - 2f - x0).coerceAtLeast(50f)
        val pitchB = minOf(colW / DOTS, hc * 0.48f)
        val lit = litDots(s.level)
        // charging: the last lit dot is the red one, drawn as an overlay (it breathes) over an unlit dot
        val accentDot = if (!low && s.charging && lit > 0) lit - 1 else -1
        val bar = DotText.bar(DOTS, if (accentDot >= 0) lit - 1 else lit, Nd.FG, -1, pitchB, density)
        rv.putArt(R.id.bar, bar)
        rv.margins(R.id.bar, start = x0, top = top)
        if (accentDot >= 0) {
            rv.putArt(R.id.pulse, DotText.bar(1, 0, Nd.FG, 0, pitchB, density))
            rv.margins(R.id.pulse, start = x0 + accentDot * pitchB, top = top)
            rv.setInt(R.id.pulse, "setImageAlpha", if (live && animated(ctx)) pulseAlpha() else 255)
            rv.setViewVisibility(R.id.pulse, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.pulse, View.GONE)
        }

        // status line under the bar, bottom aligned with the numerals
        val statusH = (hc - pitchB - 4f).coerceAtLeast(12f)
        val size = (statusH * 0.8f).coerceIn(9f, 11.5f)
        val maxChars = (colW / (size * 0.64f)).toInt()
        val hot = !s.charging && !s.full && s.tempC > HOT_C && !low
        val st = rv.label(ctx, R.id.status, statusText(ctx, s, maxChars), size, if (hot) Nd.ACCENT else Nd.DIM, colW, minSizeDp = 8.5f)
        rv.margins(R.id.status, start = x0, top = top + hc - st.hDp)

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
