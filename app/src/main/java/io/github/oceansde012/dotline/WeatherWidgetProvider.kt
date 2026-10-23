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
import kotlin.math.roundToInt

/**
 * Weather widget 2x2 (DESIGN_SPEC.md 2.3): dot icon top-left, dot-matrix temperature filling the middle,
 * condition label + `H 12° · L 6°` at the bottom. Data comes from [WeatherRepo]; this class only renders
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

        // vertical plan: icon | temperature fills the rest | text block (label + sub) on the bottom line
        val label = (0.085f * h).coerceIn(11f, 13f)
        val showSub = h >= 100f
        val blockH = label * 1.25f + (if (showSub) 4f + (label - 1f) * 1.25f else 0f)
        val iconDp = (0.24f * h).coerceIn(26f, 46f)
        val gap = 6f
        val blockTop = h - pad - blockH
        val tempBox = (blockTop - gap) - (pad + iconDp + gap)

        rv.margins(R.id.icon, start = pad, top = pad)
        rv.size(R.id.icon, iconDp, iconDp)
        rv.setTextViewTextSize(R.id.label, DIP, label)
        rv.setTextViewTextSize(R.id.sub, DIP, label - 1f)
        rv.place(R.id.text_block, pad, blockTop, (w - 2f * pad).coerceAtLeast(60f), blockH)
        rv.setViewVisibility(R.id.sub, if (showSub) View.VISIBLE else View.GONE)
        val dot = maxOf(6f, 0.055f * h)
        rv.size(R.id.dot, dot, dot)
        rv.margins(R.id.dot, top = pad, end = pad)
        rv.setViewVisibility(R.id.dot, View.GONE)
        rv.setViewVisibility(R.id.city, View.GONE)
        rv.setViewVisibility(R.id.temp, View.VISIBLE)

        fun temp(text: String, color: Int) {
            val pitch = DotText.pitchFor(text, w - 2f * pad, tempBox.coerceAtLeast(14f))
            val art = DotText.art(text, pitch, density, color)
            rv.putArt(R.id.temp, art)
            val y = pad + iconDp + gap + (tempBox - art.hDp) / 2f
            rv.margins(R.id.temp, start = pad, top = y)
        }
        fun icon(kind: WeatherArt.Kind, color: Int) =
            rv.setImageViewBitmap(R.id.icon, WeatherArt.weather(kind, WeatherArt.px(iconDp, density), color))

        if (!WeatherRepo.hasLocation(ctx)) {
            icon(WeatherArt.Kind.CLOUD, Nd.FAINT)
            temp("--°", Nd.FAINT)
            rv.setTextViewText(R.id.label, ctx.getString(R.string.wx_set_city))
            rv.setTextColor(R.id.label, Nd.FG)
            rv.setTextViewText(R.id.sub, ctx.getString(R.string.wx_set_city_hint))
            return rv
        }

        val wx = WeatherRepo.cached(ctx)
        if (wx == null) {
            icon(WeatherArt.Kind.CLOUD, Nd.FAINT)
            temp("--°", Nd.FAINT)
            rv.setTextViewText(R.id.label, ctx.getString(R.string.wx_no_data))
            rv.setTextColor(R.id.label, Nd.FAINT)
            rv.setTextViewText(R.id.sub, WeatherRepo.locationName(ctx).uppercase())
            return rv
        }

        val now = System.currentTimeMillis()
        val stale = now - wx.fetchedAt > WeatherRepo.STALE_MS
        icon(WeatherArt.kind(wx.code, wx.isDay), if (stale) Nd.FAINT else Nd.FG)
        temp(deg(ctx, wx.temp), if (stale) Nd.DIM else Nd.FG)

        if (stale) {
            val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
            val at = DateFormat.format(pattern, Date(wx.fetchedAt)).toString()
            rv.setTextViewText(R.id.label, ctx.getString(R.string.wx_updated, at).uppercase())
            rv.setTextColor(R.id.label, Nd.FAINT)
        } else {
            rv.setTextViewText(R.id.label, conditionLabel(ctx, wx.code).uppercase())
            rv.setTextColor(R.id.label, Nd.FG)
        }

        // accent: rain within the next 2 h replaces H / L (not shown from stale data)
        val rainSoon = !stale && wx.rainAt >= 0 && now <= wx.rainAt + RAIN_SOON_MS
        if (rainSoon) {
            val txt = if (now >= wx.rainAt) ctx.getString(R.string.wx_rain_now) else ctx.getString(R.string.wx_rain_at, wx.rainHm)
            rv.setTextViewText(R.id.sub, txt)
            rv.setViewVisibility(R.id.dot, View.VISIBLE)
        } else {
            rv.setTextViewText(R.id.sub, ctx.getString(R.string.wx_hi_lo, deg(ctx, wx.hi), deg(ctx, wx.lo)))
        }

        if (w >= 200f) {   // city: sits between the icon and the accent dot
            rv.margins(R.id.city, start = pad + iconDp + 10f, top = pad + (iconDp - label) / 2f, end = pad + dot + 8f)
            rv.setTextViewTextSize(R.id.city, DIP, label - 1f)
            rv.setTextViewText(R.id.city, WeatherRepo.locationName(ctx).uppercase())
            rv.setViewVisibility(R.id.city, View.VISIBLE)
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
