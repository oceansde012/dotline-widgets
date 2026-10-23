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
 * ND_PlayerA as RemoteViews (DESIGN_SPEC.md 2.4). Three faces:
 *  - playing / paused: cover, source app name, title, artist, dot progress bar + times, controls;
 *  - idle: compact centred row (note cover, "Not playing" + hint, round play button);
 *  - no notification access: the same compact row saying `Allow access`, tap opens the access settings.
 * The elapsed time is a Chronometer, the dot bar + remaining time are pushed per tick.
 */
object PlayerRenderer {
    private const val REQ_PREV = 1
    private const val REQ_PLAY = 2
    private const val REQ_NEXT = 3
    private const val REQ_OPEN = 4
    private const val MINUS = "−"

    // bitmaps are rebuilt only when their inputs change (cover: art + px size; controls: state + size)
    private var coverKey: Any? = null
    private var coverBmp: Bitmap? = null
    private val controlsCache = HashMap<List<Any>, Bitmap>()

    fun full(ctx: Context, mgr: AppWidgetManager, id: Int, t: Track?): RemoteViews {
        val sz = WidgetSize.of(ctx, mgr, id, 380f, 165f)
        return full(ctx, sz.width, sz.height, t)
    }

    /** the face for a [w] x [h] dp tile; [t] = null is the idle / no-access face */
    fun full(ctx: Context, w: Float, h: Float, t: Track?): RemoteViews {
        val access = MediaTracker.hasAccess(ctx)
        val idle = t == null
        val g = PlayerGeom(w, h, idle)
        val density = ctx.resources.displayMetrics.density
        val rv = RemoteViews(ctx.packageName, R.layout.widget_player)
        Tile.apply(rv, R.id.tile, R.id.zone_open, g.h)

        // cover / idle placeholder
        rv.place(R.id.cover, g.pad, g.coverY, g.cover, g.cover)
        rv.setImageViewBitmap(R.id.cover, cover(t, g, density))

        // text block
        rv.setTextViewTextSize(R.id.title, DIP, g.titleSize)
        rv.setTextViewTextSize(R.id.artist, DIP, if (idle) g.artistSize - 1f else g.artistSize)
        rv.setTextViewTextSize(R.id.source, DIP, g.sourceSize)
        if (t == null) {
            val blockH = g.titleSize * 1.25f + 3f + (g.artistSize - 1f) * 1.25f
            rv.place(R.id.text_block, g.colX, (g.h - blockH) / 2f, g.idleTextW, blockH)
            rv.setViewVisibility(R.id.source, View.GONE)
            rv.setTextViewText(R.id.title, ctx.getString(if (access) R.string.not_playing else R.string.player_allow))
            rv.setTextColor(R.id.title, if (access) Nd.DIM else Nd.FG)
            rv.setTextViewText(R.id.artist, ctx.getString(if (access) R.string.player_idle_hint else R.string.player_allow_hint))
            rv.setTextColor(R.id.artist, if (access) Nd.FAINT else Nd.DIM)
            rv.setViewVisibility(R.id.artist, View.VISIBLE)
        } else {
            rv.place(R.id.text_block, g.colX, g.pad, g.colW, g.blockH)
            rv.setViewVisibility(R.id.source, View.VISIBLE)
            rv.setTextViewText(R.id.source, t.appName.uppercase())
            rv.setTextViewText(R.id.title, t.title)
            rv.setTextColor(R.id.title, Nd.FG)
            rv.setTextViewText(R.id.artist, t.artist)
            rv.setViewVisibility(R.id.artist, if (t.artist.isEmpty()) View.GONE else View.VISIBLE)
        }

        // progress group, centred between the title block and the controls
        val showBar = t != null && t.showProgress
        rv.place(R.id.bar, g.colX, g.groupY, g.colW, g.barH)
        rv.setViewVisibility(R.id.bar, if (showBar) View.VISIBLE else View.GONE)
        rv.place(R.id.time_row, g.colX, g.groupY + g.barH + 4f, g.colW, g.timeH)
        rv.setTextViewTextSize(R.id.elapsed, DIP, g.timeSize)
        rv.setTextViewTextSize(R.id.remaining, DIP, g.timeSize)
        rv.setViewVisibility(R.id.time_row, if (g.showTimes && showBar) View.VISIBLE else View.GONE)
        rv.setTextColor(R.id.elapsed, if (t != null && !t.playing) Nd.DIM else Nd.FG)
        if (t != null) applyProgress(ctx, rv, t)

        // controls picture + 48 dp tap zones on top of it (idle: only the round play button; no access: none)
        val showControls = t != null || access
        rv.place(R.id.controls, g.ctlX, g.ctlY, g.clusterW, g.zone)
        rv.setViewVisibility(R.id.controls, if (showControls) View.VISIBLE else View.GONE)
        if (showControls) rv.setImageViewBitmap(R.id.controls, controls(g, density, t?.playing == true, idle))
        rv.place(R.id.zone_play, if (idle) g.ctlX else g.ctlX + g.zone, g.ctlY, g.zone, g.zone)
        rv.place(R.id.zone_prev, g.ctlX, g.ctlY, g.zone, g.zone)
        rv.place(R.id.zone_next, g.ctlX + 2f * g.zone, g.ctlY, g.zone, g.zone)
        rv.setViewVisibility(R.id.zone_play, if (showControls) View.VISIBLE else View.GONE)
        val sides = if (idle) View.GONE else View.VISIBLE
        rv.setViewVisibility(R.id.zone_prev, sides)
        rv.setViewVisibility(R.id.zone_next, sides)

        rv.setOnClickPendingIntent(R.id.zone_prev, command(ctx, PlayerWidgetProvider.ACTION_PREV, REQ_PREV))
        rv.setOnClickPendingIntent(R.id.zone_play, command(ctx, PlayerWidgetProvider.ACTION_PLAY_PAUSE, REQ_PLAY))
        rv.setOnClickPendingIntent(R.id.zone_next, command(ctx, PlayerWidgetProvider.ACTION_NEXT, REQ_NEXT))
        rv.setOnClickPendingIntent(R.id.zone_open, if (!access && t == null) Taps.notificationAccess(ctx) else openPlayer(ctx, t))
        return rv
    }

    /** the per-second partial update: the dot bar, the chronometer base and the remaining time */
    fun progress(ctx: Context, t: Track): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_player)
        applyProgress(ctx, rv, t)
        return rv
    }

    private fun applyProgress(ctx: Context, rv: RemoteViews, t: Track) {
        if (!t.showProgress) return
        val pos = t.positionNow()
        // the Chronometer keeps counting by itself when a partial update is throttled by the launcher
        rv.setChronometer(R.id.elapsed, SystemClock.elapsedRealtime() - pos, "%s", t.playing)
        rv.setImageViewBitmap(R.id.bar, PlayerArt.bar(pos.toFloat() / t.durationMs))
        rv.setTextViewText(
            R.id.remaining,
            if (Prefs.timeTotal(ctx)) mmss(t.durationMs) else MINUS + mmss((t.durationMs - pos).coerceAtLeast(0)),
        )
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
        val key = listOf(t?.artKey ?: -1L, t?.art != null, px, g.coverRadius, g.idle)
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

    /** the playing app: its session activity (when it is an activity), else its launcher activity, else this app */
    private fun openPlayer(ctx: Context, t: Track?): PendingIntent {
        t?.sessionActivity?.let { if (it.isActivity) return it }
        val pkg = t?.pkg ?: MediaTracker.lastPackage(ctx)
        val launch = pkg?.let { Taps.launch(ctx, it) }
        if (launch != null) return PendingIntent.getActivity(ctx, REQ_OPEN, launch, immutable())
        return PendingIntent.getActivity(ctx, Taps.REQ_APP, Taps.ownApp(ctx), immutable())
    }
}
