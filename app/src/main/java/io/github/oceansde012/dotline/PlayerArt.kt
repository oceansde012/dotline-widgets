package io.github.oceansde012.dotline

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Player geometry in dp (DESIGN_SPEC.md 2.4). P and R come from [Tile]; the cover is a square of
 * H - 2P at the left, the right column holds title, artist, dot bar, times and the controls.
 */
class PlayerGeom(val w: Float, val h: Float) {
    val pad = Tile.pad(h)
    val radius = Tile.radius(h)
    val cover = (h - 2f * pad).coerceAtLeast(40f)
    val colX = pad + cover + 14f
    val colW = (w - colX - pad).coerceAtLeast(60f)
    val titleSize = (0.1f * h).coerceIn(14f, 17f)
    val artistSize = (0.075f * h).coerceIn(12f, 13f)
    val zone = (colW / 3f).coerceIn(40f, 48f)          // touch zone of each control
    val clusterW = zone * 3f
    val ctlBottom = (pad - 4f).coerceAtLeast(0f)
    val showTimes = h >= 140f
    val timeSize = 11f
    val timeH = 14f
    val timeBottom = ctlBottom + zone + 2f
    val barH = colW / PlayerArt.DOTS                  // dot pitch == bar height, dots stay round
    val barBottom = (if (showTimes) timeBottom + timeH else ctlBottom + zone) + 4f
    val coverRadius = (radius - pad).coerceAtLeast(4f)
}

object PlayerArt {
    const val DOTS = 28
    private const val PITCH_PX = 24                     // 672 x 24 px ARGB = 64 KB, scaled to the column
    private const val COVER_MAX_PX = 272                // ARGB_8888: <= 296 KB
    private const val CONTROLS_BUDGET = 300_000

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).roundToInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).roundToInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).roundToInt(),
        )
    }

    /**
     * 28 dots: lit = fg, unlit = dot_off, head = accent. Sub-dot progress: the head fades in while its
     * left neighbour fades from accent to fg.
     */
    fun bar(frac: Float): Bitmap {
        val bmp = Bitmap.createBitmap(DOTS * PITCH_PX, PITCH_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = frac.coerceIn(0f, 1f) * DOTS
        val head = f.toInt().coerceAtMost(DOTS - 1)
        val sub = if (f >= DOTS) 1f else f - head
        val r = PITCH_PX * 0.29f
        for (i in 0 until DOTS) {
            dotPaint.color = when {
                i < head - 1 -> Nd.FG
                i == head - 1 -> mix(Nd.ACCENT, Nd.FG, sub)
                i == head -> mix(Nd.OFF, Nd.ACCENT, 0.4f + 0.6f * sub)
                else -> Nd.OFF
            }
            c.drawCircle((i + 0.5f) * PITCH_PX, PITCH_PX / 2f, r, dotPaint)
        }
        return bmp
    }

    /** px per dp so that ARGB_8888 of areaDp stays inside the budget */
    private fun scaleFor(areaDp: Float, density: Float, budget: Int): Float {
        val bytes = areaDp * density * density * 4f
        return if (bytes <= budget) density else sqrt(budget / (areaDp * 4f))
    }

    /**
     * prev / play-pause / next in three zone-wide slots. Play = fg circle with a black glyph
     * (chip circle with an fg glyph when idle); prev/next are bare fg dot glyphs. Idle: play only.
     */
    fun controls(g: PlayerGeom, density: Float, playing: Boolean, idle: Boolean): Bitmap {
        val s = scaleFor(g.clusterW * g.zone, density, CONTROLS_BUDGET)
        val bmp = Bitmap.createBitmap(
            max(1, (g.clusterW * s).roundToInt()), max(1, (g.zone * s).roundToInt()), Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        c.scale(s, s)
        val cy = g.zone / 2f
        val playCx = g.clusterW - g.zone * (if (idle) 0.5f else 1.5f)
        dotPaint.color = if (idle) Nd.CHIP else Nd.FG
        c.drawCircle(playCx, cy, g.zone * 0.42f, dotPaint)
        dotPaint.color = if (idle) Nd.FG else Color.BLACK
        Glyphs.draw(c, if (playing) Glyphs.PAUSE else Glyphs.PLAY, playCx, cy, g.zone * 0.46f, dotPaint)
        if (!idle) {
            dotPaint.color = Nd.FG
            Glyphs.draw(c, Glyphs.PREV, g.zone / 2f, cy, g.zone * 0.54f, dotPaint)
            Glyphs.draw(c, Glyphs.NEXT, g.clusterW - g.zone / 2f, cy, g.zone * 0.54f, dotPaint)
        }
        return bmp
    }

    fun coverPx(g: PlayerGeom, density: Float): Int =
        min((g.cover * density).roundToInt(), COVER_MAX_PX).coerceAtLeast(32)

    /**
     * square cover with rounded corners baked in (ImageView clipping is not available in RemoteViews):
     * album art centre-cropped, or the chip with the dotted note. ARGB_8888 for the transparent corners.
     */
    fun cover(art: Bitmap?, size: Int, radiusPx: Float): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        var drawn = false
        if (art != null && !art.isRecycled && art.width > 0 && art.height > 0) {
            // a software canvas cannot read HARDWARE bitmaps
            val soft = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
            if (soft != null) {
                val src = downscale(soft, size)
                val side = min(src.width, src.height)
                val sc = size.toFloat() / side
                val m = Matrix()
                m.setScale(sc, sc)
                m.postTranslate(-(src.width - side) / 2f * sc, -(src.height - side) / 2f * sc)
                val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader.setLocalMatrix(m)
                bmpPaint.shader = shader
                c.drawRoundRect(rect, radiusPx, radiusPx, bmpPaint)
                bmpPaint.shader = null
                if (src !== soft) src.recycle()
                if (soft !== art) soft.recycle()
                drawn = true
            }
        }
        if (!drawn) {
            dotPaint.color = Nd.CHIP
            c.drawRoundRect(rect, radiusPx, radiusPx, dotPaint)
            dotPaint.color = Nd.FAINT
            Glyphs.drawNote(c, size / 2f, size / 2f, size * 0.36f, dotPaint)
        }
        return bmp
    }

    /** halve until the short side is < 2x the target, so the final bilinear pass does not alias */
    private fun downscale(src: Bitmap, target: Int): Bitmap {
        var cur = src
        while (min(cur.width, cur.height) >= target * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        return cur
    }
}
