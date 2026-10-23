package io.github.oceansde012.dotline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * ND_PlayerA as RemoteViews (DESIGN_SPEC.md 2.4): rounded cover bitmap, native text, a Chronometer for
 * the elapsed time, a dot-bar bitmap + remaining time pushed per tick, dot-matrix control bitmap.
 */
object PlayerRenderer {
    private const val REQ_PREV = 1
    private const val REQ_PLAY = 2
    private const val REQ_NEXT = 3
    private const val REQ_OPEN = 4
    private const val REQ_APP = 5
    private const val MINUS = "−"

    // bitmaps are rebuilt only when their inputs change (cover: art + px size; controls: state + size)
    private var coverKey: Any? = null
    private var coverBmp: Bitmap? = null
    private val controlsCache = HashMap<List<Any>, Bitmap>()

    fun full(ctx: Context, mgr: AppWidgetManager, id: Int, t: Track?): RemoteViews {
        val sz = WidgetSize.of(ctx, mgr, id, 380f, 170f)
        return full(ctx, sz.width, sz.height, t)
    }

    /** the face for a [w] x [h] dp tile; [t] = null is the idle face */
    fun full(ctx: Context, w: Float, h: Float, t: Track?): RemoteViews {
        val g = PlayerGeom(w, h)
        val density = ctx.resources.displayMetrics.density
        val idle = t == null
        val rv = RemoteViews(ctx.packageName, R.layout.widget_player)

        Tile.apply(rv, R.id.tile, R.id.zone_open, g.h)

        // cover / idle placeholder
        rv.margins(R.id.cover, start = g.pad, top = g.pad)
        rv.size(R.id.cover, g.cover, g.cover)
        rv.setImageViewBitmap(R.id.cover, cover(t, g, density))

        // title (system sans medium) + artist (sans, dim)
        rv.margins(R.id.text_block, start = g.colX, top = g.pad)
        rv.setViewLayoutWidth(R.id.text_block, g.colW, DIP)
        rv.setTextViewTextSize(R.id.title, DIP, g.titleSize)
        rv.setTextViewTextSize(R.id.artist, DIP, g.artistSize)
        if (t == null) {
            val app = MediaTracker.lastAppLabel(ctx)
            rv.setTextViewText(R.id.title, ctx.getString(R.string.not_playing))
            rv.setTextColor(R.id.title, Nd.DIM)
            rv.setTextViewText(R.id.artist, app ?: "")
            rv.setViewVisibility(R.id.artist, if (app == null) View.GONE else View.VISIBLE)
        } else {
            rv.setTextViewText(R.id.title, t.title)
            rv.setTextColor(R.id.title, Nd.FG)
            rv.setTextViewText(R.id.artist, t.artist)
            rv.setViewVisibility(R.id.artist, if (t.artist.isEmpty()) View.GONE else View.VISIBLE)
        }

        // dot bar + elapsed / remaining labels
        val showBar = t != null && t.hasPosition && t.durationMs > 0
        rv.margins(R.id.bar, start = g.colX, bottom = g.barBottom)
        rv.size(R.id.bar, g.colW, g.barH)
        rv.setViewVisibility(R.id.bar, if (showBar) View.VISIBLE else View.GONE)
        rv.margins(R.id.time_row, start = g.colX, bottom = g.timeBottom)
        rv.size(R.id.time_row, g.colW, g.timeH)
        rv.setTextViewTextSize(R.id.elapsed, DIP, g.timeSize)
        rv.setTextViewTextSize(R.id.remaining, DIP, g.timeSize)
        val showTimes = g.showTimes && t != null && t.hasPosition
        rv.setViewVisibility(R.id.time_row, if (showTimes) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.remaining, if (t != null && t.durationMs > 0) View.VISIBLE else View.GONE)
        val paused = t != null && !t.playing
        rv.setTextColor(R.id.elapsed, if (paused) Nd.DIM else Nd.FG)
        if (t != null) applyProgress(ctx, rv, t)

        // controls picture + 48 dp tap zones on top of it
        rv.margins(R.id.controls, end = g.pad, bottom = g.ctlBottom)
        rv.size(R.id.controls, g.clusterW, g.zone)
        rv.setImageViewBitmap(R.id.controls, controls(g, density, t?.playing == true, idle))
        for (z in intArrayOf(R.id.zone_prev, R.id.zone_play, R.id.zone_next)) rv.size(z, g.zone, g.zone)
        rv.margins(R.id.zone_next, end = g.pad, bottom = g.ctlBottom)
        rv.margins(R.id.zone_play, end = if (idle) g.pad else g.pad + g.zone, bottom = g.ctlBottom)
        rv.margins(R.id.zone_prev, end = g.pad + 2f * g.zone, bottom = g.ctlBottom)
        val sides = if (idle) View.GONE else View.VISIBLE
        rv.setViewVisibility(R.id.zone_prev, sides)
        rv.setViewVisibility(R.id.zone_next, sides)

        rv.setOnClickPendingIntent(R.id.zone_prev, command(ctx, PlayerWidgetProvider.ACTION_PREV, REQ_PREV))
        rv.setOnClickPendingIntent(R.id.zone_play, command(ctx, PlayerWidgetProvider.ACTION_PLAY_PAUSE, REQ_PLAY))
        rv.setOnClickPendingIntent(R.id.zone_next, command(ctx, PlayerWidgetProvider.ACTION_NEXT, REQ_NEXT))
        rv.setOnClickPendingIntent(R.id.zone_open, openPlayer(ctx, t))
        return rv
    }

    /** the per-second partial update: the dot bar, the chronometer base and the remaining time */
    fun progress(ctx: Context, t: Track): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_player)
        applyProgress(ctx, rv, t)
        return rv
    }

    private fun applyProgress(ctx: Context, rv: RemoteViews, t: Track) {
        if (!t.hasPosition) return
        val pos = t.positionNow()
        // the Chronometer keeps counting by itself when a partial update is throttled by the launcher
        rv.setChronometer(R.id.elapsed, SystemClock.elapsedRealtime() - pos, "%s", t.playing)
        if (t.durationMs > 0) {
            rv.setImageViewBitmap(R.id.bar, PlayerArt.bar(pos.toFloat() / t.durationMs))
            rv.setTextViewText(
                R.id.remaining,
                if (Prefs.timeTotal(ctx)) mmss(t.durationMs) else MINUS + mmss((t.durationMs - pos).coerceAtLeast(0)),
            )
        }
    }

    fun mmss(ms: Long): String {
        val s = (if (ms < 0) 0 else ms) / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(s / 60, sec)
    }

    private fun cover(t: Track?, g: PlayerGeom, density: Float): Bitmap {
        val px = PlayerArt.coverPx(g, density)
        val key = listOf(t?.artKey ?: -1L, t?.art != null, px, g.coverRadius)
        coverBmp?.let { if (key == coverKey && !it.isRecycled) return it }
        val b = PlayerArt.cover(t?.art, px, g.coverRadius * px / g.cover)
        coverKey = key
        coverBmp = b   // not recycled: RemoteViews may still be parceling the previous one
        return b
    }

    private fun controls(g: PlayerGeom, density: Float, playing: Boolean, idle: Boolean): Bitmap {
        val key = listOf(g.zone, playing, idle, density)
        controlsCache[key]?.let { return it }
        if (controlsCache.size > 8) controlsCache.clear()
        return PlayerArt.controls(g, density, playing, idle).also { controlsCache[key] = it }
    }

    private fun command(ctx: Context, action: String, req: Int): PendingIntent {
        val i = Intent(ctx, PlayerWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, req, i, immutable())
    }

    /** the player's own session activity, else its launcher activity, else the last player, else this app */
    private fun openPlayer(ctx: Context, t: Track?): PendingIntent {
        t?.sessionActivity?.let { return it }
        val pkg = t?.pkg ?: MediaTracker.lastPackage(ctx)
        val launch = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(ctx, REQ_OPEN, launch, immutable())
        }
        val self = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, REQ_APP, self, immutable())
    }
}
