package io.github.oceansde012.dotline

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Weather widget 2x2 (DESIGN_SPEC.md 2.3): dot icon top-left, dot-matrix temperature filling the middle,
 * condition label + `H 12° · L 6°` at the bottom. Without a city or data: icon + message, never a number. Data comes from [WeatherRepo]; this class only renders
 * what is cached, so a resize never hits the network.
 */
class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        WeatherJob.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MediaTracker.ensureStarted(context)
        WeatherJob.schedule(context)
        updateAll(context)
        WeatherRepo.refreshAsync(context, minAgeMs = 0)   // widget added / system update: fetch right away
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
        const val EXTRA_SECTION = "section"
        const val SECTION_WEATHER = "weather"
        private const val REQ_OPEN = 120
        private const val RAIN_SOON_MS = 60 * 60 * 1000L

        /** the same face the home screen gets, for a [widthDp] x [heightDp] tile (gallery, generated picker preview) */
        fun buildPreview(ctx: Context, widthDp: Float, heightDp: Float): RemoteViews =
            WeatherWidgetProvider().render(ctx.applicationContext, widthDp, heightDp)

        fun updateAll(ctx: Context) {
            val a = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(a)
            val ids = mgr.getAppWidgetIds(ComponentName(a, WeatherWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val p = WeatherWidgetProvider()
            for (id in ids) {
                val sz = WidgetSize.of(a, mgr, id, 150f, 150f)
                try {
                    mgr.updateAppWidget(id, p.render(a, sz.width, sz.height))
                } catch (e: Exception) {
                    Log.w("NdWeatherW", "render failed", e)
                }
            }
        }
    }

    private fun render(ctx: Context, w: Float, h: Float): RemoteViews {
        val pad = Tile.pad(h)
        val density = ctx.resources.displayMetrics.density
        val rv = RemoteViews(ctx.packageName, R.layout.widget_weather)
        Tile.apply(rv, R.id.tile, R.id.zone, h)
        rv.setOnClickPendingIntent(R.id.zone, Taps.app(ctx, REQ_OPEN, SECTION_WEATHER))

        val loc = Locale.getDefault()
        val wx = if (WeatherRepo.hasLocation(ctx)) WeatherRepo.cached(ctx) else null
        val now = System.currentTimeMillis()
        val stale = wx != null && now - wx.fetchedAt > WeatherRepo.STALE_MS
        val rainSoon = wx != null && !stale && wx.rainAt >= 0 && now <= wx.rainAt + RAIN_SOON_MS

        // texts first: the bottom block height depends on their rendered size
        val labelText: String
        val labelColor: Int
        val subText: String
        when {
            !WeatherRepo.hasLocation(ctx) -> {
                labelText = ctx.getString(R.string.wx_set_city).uppercase(loc)
                labelColor = Nd.FG
                subText = ctx.getString(R.string.wx_set_city_hint)
            }
            wx == null -> {
                labelText = ctx.getString(R.string.wx_no_data)
                labelColor = Nd.FAINT
                subText = WeatherRepo.locationName(ctx).uppercase(loc)
            }
            stale -> {
                val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
                labelText = ctx.getString(R.string.wx_updated, DateFormat.format(pattern, Date(wx.fetchedAt)).toString()).uppercase(loc)
                labelColor = Nd.FAINT
                subText = ctx.getString(R.string.wx_hi_lo, deg(ctx, wx.hi), deg(ctx, wx.lo))
            }
            else -> {
                labelText = conditionLabel(ctx, wx.code).uppercase(loc)
                labelColor = Nd.FG
                subText = if (rainSoon) {
                    if (now >= wx.rainAt) ctx.getString(R.string.wx_rain_now) else ctx.getString(R.string.wx_rain_at, wx.rainHm)
                } else {
                    ctx.getString(R.string.wx_hi_lo, deg(ctx, wx.hi), deg(ctx, wx.lo))
                }
            }
        }

        val label = (0.085f * h).coerceIn(11f, 13f)
        val showSub = h >= 100f && subText.isNotBlank()
        val maxW = (w - 2f * pad).coerceAtLeast(60f)
        val l1 = rv.label(ctx, R.id.label, labelText, label, labelColor, maxW, medium = true, minSizeDp = 9f)
        val subTwoLines = wx == null && !WeatherRepo.hasLocation(ctx)   // "Tap to choose" may need a second line
        val l2 = if (showSub) {
            rv.label(ctx, R.id.sub, subText, label - 1f, Nd.DIM, maxW, maxLines = if (subTwoLines) 2 else 1, minSizeDp = 9f)
        } else {
            rv.setViewVisibility(R.id.sub, View.GONE)
            null
        }
        val gap = 6f
        val blockH = l1.hDp + (if (l2 != null) 4f + l2.hDp else 0f)
        val blockTop = h - pad - blockH
        rv.margins(R.id.label, start = pad, top = blockTop)
        if (l2 != null) rv.margins(R.id.sub, start = pad, top = blockTop + l1.hDp + 4f)

        val dot = maxOf(6f, 0.055f * h)
        rv.size(R.id.dot, dot, dot)
        rv.margins(R.id.dot, top = pad, end = pad)
        rv.setViewVisibility(R.id.dot, if (rainSoon) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.city, View.GONE)

        val iconDp = (0.24f * h).coerceIn(26f, 46f)
        val tempBox = (blockTop - gap) - (pad + iconDp + gap)

        fun icon(kind: WeatherArt.Kind, color: Int, dp: Float) {
            rv.setImageViewBitmap(R.id.icon, WeatherArt.weather(kind, WeatherArt.px(dp, density), color))
            rv.size(R.id.icon, dp, dp)
        }

        if (wx == null) {
            // nothing to show but a message: no number at all, the icon takes the middle of the tile
            rv.setViewVisibility(R.id.temp, View.GONE)
            val big = minOf(w - 2f * pad, blockTop - gap - pad, 64f).coerceAtLeast(iconDp)
            icon(WeatherArt.Kind.CLOUD, Nd.FAINT, big)
            rv.margins(R.id.icon, start = pad, top = pad + ((blockTop - gap - pad) - big) / 2f)
            return rv
        }

        rv.setViewVisibility(R.id.temp, View.VISIBLE)
        rv.margins(R.id.icon, start = pad, top = pad)
        icon(WeatherArt.kind(wx.code, wx.isDay), if (stale) Nd.FAINT else Nd.FG, iconDp)
        val tempText = deg(ctx, wx.temp)
        val pitch = DotText.pitchFor(tempText, w - 2f * pad, tempBox.coerceAtLeast(14f))
        val art = DotText.art(tempText, pitch, density, if (stale) Nd.DIM else Nd.FG)
        rv.putArt(R.id.temp, art)
        rv.setContentDescription(R.id.temp, tempText)
        rv.margins(R.id.temp, start = pad, top = pad + iconDp + gap + (tempBox - art.hDp) / 2f)

        if (w >= 200f) {   // city: sits between the icon and the accent dot
            val name = WeatherRepo.locationName(ctx).uppercase(loc)
            if (name.isNotBlank()) {
                val cx = pad + iconDp + 10f
                val c = rv.label(ctx, R.id.city, name, label - 1f, Nd.DIM, (w - cx - pad - dot - 8f).coerceAtLeast(30f), minSizeDp = 9f)
                rv.margins(R.id.city, start = w - pad - dot - 8f - c.wDp, top = pad + (iconDp - c.hDp) / 2f)
            }
        }
        return rv
    }

    /** the forecast is stored in Celsius; the unit chosen in the app is applied here, so a switch is instant */
    private fun deg(ctx: Context, c: Double): String = "${Prefs.temp(ctx, c).roundToInt()}°"

    private fun conditionLabel(ctx: Context, code: Int): String = ctx.getString(
        when (code) {
            0 -> R.string.wx_clear
            1 -> R.string.wx_mostly_clear
            2 -> R.string.wx_partly
            3 -> R.string.wx_cloudy
            45, 48 -> R.string.wx_fog
            51, 53, 55, 56, 57 -> R.string.wx_drizzle
            61, 63, 65 -> R.string.wx_rain
            66, 67 -> R.string.wx_freezing_rain
            71, 73, 75, 77 -> R.string.wx_snow
            80, 81, 82 -> R.string.wx_showers
            85, 86 -> R.string.wx_snow_showers
            95, 96, 99 -> R.string.wx_storm
            else -> R.string.wx_cloudy
        }
    )
}
