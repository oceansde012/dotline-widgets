package io.github.oceansde012.dotline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews

/**
 * Buds widget 2x1 (DESIGN_SPEC.md 2.6): the earbuds picture on a plate (the actual model when it can be found,
 * else our own illustration), the model name, `L80 R75` as dot-matrix and `CASE 40`. Single-level devices show one
 * dot-matrix percentage. Faces: no permission -> `Allow Bluetooth`; nothing connected -> `No earbuds`
 * (last known picture dimmed). Accent: a value at or below 15% turns red.
 */
class BudsWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        WeatherJob.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MediaTracker.ensureStarted(context)
        WeatherJob.schedule(context)
        updateAll(context)           // the real state replaces the layout at once ...
        BudsRepo.refreshAsync(context)   // ... and is refreshed from Bluetooth right after
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
        private const val REQ_APP = 141

        /** the same face the home screen gets, from the last known earbud state (gallery, generated picker preview) */
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
            for (id in ids) {
                val sz = WidgetSize.of(a, mgr, id, 160f, 62f)
                try {
                    mgr.updateAppWidget(id, p.render(a, sz.width, sz.height, state))
                } catch (e: Exception) {
                    Log.w("NdBudsW", "render failed", e)
                }
            }
        }
    }

    private fun render(base: Context, w: Float, h: Float, b: Buds): RemoteViews {
        val ctx = Lang.wrap(base)
        val density = ctx.resources.displayMetrics.density
        val pad = Tile.pad(h).coerceAtLeast(10f)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_buds)
        Tile.apply(rv, R.id.tile, R.id.zone, h)
        val connected = b.permission && b.connected
        val inner = (h - 2f * pad).coerceAtLeast(24f)

        // picture plate on the left
        val picW = minOf(0.42f * w, inner * 1.7f)
        val radius = (Tile.radius(h) - pad).coerceIn(6f, 14f)
        val pic = BudsRepo.picture(ctx, b)
        val plate = if (pic.bmp != null) {
            BudsArt.plate(pic.bmp, picW, inner, density, radius, dim = !connected, tag = pic.tag)
        } else {
            BudsArt.illustrationPlate(
                picW, inner, density, radius,
                if (connected && b.hasSides) b.left else -1, if (connected && b.hasSides) b.right else -1, connected,
                BudsArt.styleFor("${b.model} ${b.name}"),
            )
        }
        rv.setImageViewBitmap(R.id.pic, plate)
        rv.place(R.id.pic, pad, pad, picW, inner)

        // right column
        val x = pad + picW + 10f
        val colW = (w - x - pad).coerceAtLeast(40f)
        val small = (0.15f * h).coerceIn(9f, 10.5f)
        val lineH = small * 1.3f
        rv.setViewVisibility(R.id.read_l, View.GONE)
        rv.setViewVisibility(R.id.read_r, View.GONE)
        rv.setViewVisibility(R.id.sub, View.GONE)
        rv.setViewVisibility(R.id.badge, View.GONE)

        // short, wrapped, shrunk to the narrow column: nothing is cut to "Allow…"
        fun twoLines(title: String, titleColor: Int, hint: String, hintColor: Int, titleSize: Float) {
            val avail = h - 12f
            var t = rv.label(ctx, R.id.name, title.uppercase(), titleSize, titleColor, colW, medium = true, maxLines = 2, minSizeDp = 8f)
            var hn = rv.label(ctx, R.id.sub, hint.uppercase(), small, hintColor, colW, maxLines = 2, minSizeDp = 8f)
            if (t.hDp + 3f + hn.hDp > avail) hn = rv.label(ctx, R.id.sub, hint.uppercase(), small, hintColor, colW, minSizeDp = 8f)
            if (t.hDp + 3f + hn.hDp > avail) t = rv.label(ctx, R.id.name, title.uppercase(), titleSize, titleColor, colW, medium = true, minSizeDp = 8f)
            val y = (h - (t.hDp + 3f + hn.hDp)) / 2f
            rv.margins(R.id.name, start = x, top = y)
            rv.margins(R.id.sub, start = x, top = y + t.hDp + 3f)
        }

        when {
            !b.permission -> twoLines(ctx.getString(R.string.buds_allow), Nd.FG, ctx.getString(R.string.buds_allow_hint), Nd.DIM, small + 2f)
            !b.connected -> twoLines(ctx.getString(R.string.buds_none), Nd.FG, ctx.getString(R.string.buds_none_hint), Nd.DIM, small + 2f)
            else -> {
                var nameX = x
                val badgeBmp = badge(ctx, b)
                if (badgeBmp != null) {
                    rv.setImageViewBitmap(R.id.badge, badgeBmp)
                    rv.place(R.id.badge, x, pad + (lineH - 11f) / 2f, 11f, 11f)
                    rv.setViewVisibility(R.id.badge, View.VISIBLE)
                    nameX += 15f
                }
                // the name may take two lines (wrapped at a space, shrunk down to 8dp, then "...")
                val nm = rv.label(ctx, R.id.name, b.title, small, Nd.FG, (colW - (nameX - x)).coerceAtLeast(20f), medium = true, maxLines = 2, minSizeDp = 8f)
                val nameH = maxOf(nm.hDp, lineH)
                rv.margins(R.id.name, start = nameX, top = pad + (nameH - nm.hDp) / 2f)
                val twoLines = nm.hDp > lineH * 1.5f
                val readTop = pad + nameH + 3f
                // two name lines leave little height: the case line gives way to bigger dot digits
                val caseFits = !twoLines || (h - pad - lineH - 3f - readTop) >= 14f
                val showCase = b.hasSides && b.case >= 0 && caseFits
                val readBottom = if (showCase) h - pad - lineH - 3f else h - pad
                val availH = (readBottom - readTop).coerceAtLeast(8f)
                when {
                    b.hasSides -> {
                        val l = if (b.left >= 0) "L${b.left}" else "L-"
                        val r = if (b.right >= 0) "R${b.right}" else "R-"
                        val gap = 6f
                        val pitch = minOf(
                            (colW - gap) / (DotText.cols(l) + DotText.cols(r)), availH / DotText.ROWS,
                        ).coerceAtLeast(1f)
                        val la = DotText.art(l, pitch, density, if (b.left in 0..LOW_PERCENT) Nd.ACCENT else Nd.FG)
                        val ra = DotText.art(r, pitch, density, if (b.right in 0..LOW_PERCENT) Nd.ACCENT else Nd.FG)
                        val y = readTop + (availH - la.hDp) / 2f
                        rv.putArt(R.id.read_l, la); rv.margins(R.id.read_l, start = x, top = y)
                        rv.putArt(R.id.read_r, ra); rv.margins(R.id.read_r, start = x + la.wDp + gap, top = y)
                        rv.setViewVisibility(R.id.read_l, View.VISIBLE)
                        rv.setViewVisibility(R.id.read_r, View.VISIBLE)
                        if (showCase) {
                            val cs = rv.label(ctx, R.id.sub, ctx.getString(R.string.buds_case, b.case), small, Nd.DIM, colW, minSizeDp = 8f)
                            rv.margins(R.id.sub, start = x, top = h - pad - cs.hDp)
                            rv.setViewVisibility(R.id.sub, View.VISIBLE)
                        }
                    }
                    b.single >= 0 -> {
                        val t = "${b.single}%"
                        val pitch = DotText.pitchFor(t, colW, availH)
                        val art = DotText.art(t, pitch, density, if (b.single <= LOW_PERCENT) Nd.ACCENT else Nd.FG)
                        rv.putArt(R.id.read_l, art)
                        rv.margins(R.id.read_l, start = x, top = readTop + (availH - art.hDp) / 2f)
                        rv.setViewVisibility(R.id.read_l, View.VISIBLE)
                    }
                    else -> {   // connected, battery unknown
                        val cn = rv.label(ctx, R.id.sub, ctx.getString(R.string.buds_connected).uppercase(), small, Nd.DIM, colW, minSizeDp = 8f)
                        rv.margins(R.id.sub, start = x, top = readTop + (availH - cn.hDp) / 2f)
                        rv.setViewVisibility(R.id.sub, View.VISIBLE)
                    }
                }
            }
        }
        rv.setOnClickPendingIntent(R.id.zone, tapIntent(ctx, b))
        rv.setContentDescription(R.id.zone, ctx.getString(R.string.cd_open_bluetooth))
        return rv
    }

    private fun badge(ctx: Context, b: Buds): android.graphics.Bitmap? {
        val pkg = b.companion ?: return null
        return try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val px = 48
            val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px)
            d.draw(android.graphics.Canvas(bmp))
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /** the companion app (Nothing X) when installed, else Bluetooth settings; this app when the permission is missing */
    private fun tapIntent(ctx: Context, b: Buds): PendingIntent {
        if (!b.permission) return Taps.app(ctx, REQ_APP, MainActivity.SECTION_BLUETOOTH)
        val companion = b.companion?.let { Taps.launch(ctx, it) }
        return Taps.pending(
            ctx, Taps.REQ_BT, "buds", companion, android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
        )
    }
}
