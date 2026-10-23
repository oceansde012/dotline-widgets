package io.github.oceansde012.dotline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews

class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MediaTracker.ensureStarted(context)
        MediaTracker.render(context, forceFull = true)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        MediaTracker.ensureStarted(context)
        MediaTracker.render(context, forceFull = true, onlyId = appWidgetId)
    }

    override fun onDisabled(context: Context) {
        MediaTracker.render(context)   // no widgets left: stops the progress tick
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (val action = intent.action) {
            ACTION_PREV, ACTION_PLAY_PAUSE, ACTION_NEXT -> MediaTracker.command(context, action)
        }
    }

    companion object {
        const val ACTION_PREV = BuildConfig.APPLICATION_ID + ".PREV"
        const val ACTION_PLAY_PAUSE = BuildConfig.APPLICATION_ID + ".PLAY_PAUSE"
        const val ACTION_NEXT = BuildConfig.APPLICATION_ID + ".NEXT"
    }
}

/** Real widget size in dp from the AppWidgetManager options (portrait: narrow + tall). */
object WidgetSize {
    fun of(ctx: Context, mgr: AppWidgetManager, id: Int, defW: Float, defH: Float): SizeF {
        val o = mgr.getAppWidgetOptions(id) ?: return SizeF(defW, defH)
        val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        @Suppress("DEPRECATION")
        val sizes: List<SizeF>? = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        if (!sizes.isNullOrEmpty()) {
            val s = if (portrait) sizes.maxByOrNull { it.height } else sizes.maxByOrNull { it.width }
            if (s != null && s.width > 0f && s.height > 0f) return s
        }
        val w = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        return SizeF(if (w > 0) w.toFloat() else defW, if (h > 0) h.toFloat() else defH)
    }
}

internal const val DIP = TypedValue.COMPLEX_UNIT_DIP

/** position helpers for RemoteViews (API 31+) */
internal fun RemoteViews.size(id: Int, w: Float, h: Float) {
    setViewLayoutWidth(id, w, DIP)
    setViewLayoutHeight(id, h, DIP)
}

internal fun RemoteViews.margins(
    id: Int, start: Float? = null, top: Float? = null, end: Float? = null, bottom: Float? = null,
) {
    start?.let { setViewLayoutMargin(id, RemoteViews.MARGIN_START, it, DIP) }
    top?.let { setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, it, DIP) }
    end?.let { setViewLayoutMargin(id, RemoteViews.MARGIN_END, it, DIP) }
    bottom?.let { setViewLayoutMargin(id, RemoteViews.MARGIN_BOTTOM, it, DIP) }
}

internal fun immutable(): Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

internal fun RemoteViews.place(id: Int, x: Float, y: Float, w: Float, h: Float) {
    margins(id, start = x, top = y)
    size(id, w, h)
}

/** shows a rendered dot text / bar in an ImageView, sized exactly (dp) */
internal fun RemoteViews.putArt(id: Int, a: DotText.Art) {
    setImageViewBitmap(id, a.bmp)
    size(id, a.wDp, a.hDp)
}
