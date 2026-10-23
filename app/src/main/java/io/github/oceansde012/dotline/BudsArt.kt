package io.github.oceansde012.dotline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The earbuds picture of the Buds widget. Everything is drawn here (no photos, nothing third-party):
 * [illustrationPlate] is our own stem-style true-wireless pair, [plate] puts any picture (device image, user
 * picture) on the same rounded plate with object-fit contain. Bitmaps are cached by their inputs.
 *
 * Illustration (geometry mirrored by tools/buds_art.py, which renders PNGs for visual iteration): one bud is the
 * smooth union of a head ellipse and a tapered stem, flat greys with a 3x3 dot grille. The battery of each bud is
 * a white level fill from the bottom; a bud at or below 15% is filled red (the widget's only accent). A dimmed
 * pair without fill is the "nothing connected" picture.
 */
object BudsArt {
    private const val BODY = 0xFF3A3A3E.toInt()
    private const val EDGE = 0xFF5A5A60.toInt()
    private const val GRILLE = 0xFF77777D.toInt()
    private const val FILL = 0xFFECECEE.toInt()
    private const val PLATE = 0xFF101012.toInt()
    private const val DIM_BODY = 0xFF26262A.toInt()
    private const val DIM_EDGE = 0xFF323236.toInt()
    private const val DIM_GRILLE = 0xFF323236.toInt()
    private const val MAX_BYTES = 200_000
    private const val LOW = 15
    private const val TILT = 5f
    private const val INSET = 2f

    enum class Style { STEM, ROUND }

    private class Shape(
        val hx: Float, val hcy: Float, val rx: Float, val ry: Float, val s0: Float, val s1: Float,
        val sy: Float, val sb: Float, val fil: Float, val pairW: Float, val pairH: Float, val dx: Float,
    )

    private val STEM = Shape(50f, 30f, 28f, 29f, 15f, 12f, 24f, 114f, 10f, 150f, 120f, 62f)
    private val ROUND = Shape(50f, 33f, 33f, 33f, 17f, 14f, 36f, 98f, 8f, 160f, 110f, 72f)
    private fun shape(s: Style) = if (s == Style.ROUND) ROUND else STEM

    /** model name to illustration style: Ear (a) / CMF Buds have a rounder, shorter stem */
    fun styleFor(model: String): Style {
        val m = model.lowercase()
        return if ("(a)" in m || "cmf" in m) Style.ROUND else Style.STEM
    }

    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }

    private fun size(wDp: Float, hDp: Float, density: Float): Pair<Int, Int> {
        var s = density
        val bytes = wDp * hDp * s * s * 4f
        if (bytes > MAX_BYTES) s *= sqrt(MAX_BYTES / bytes.toDouble()).toFloat()
        return Pair((wDp * s).toInt().coerceAtLeast(8), (hDp * s).toInt().coerceAtLeast(8))
    }

    // --- outline: head ellipse smoothly united with a tapered, round-ended stem -------------------------

    private fun halfWidth(s: Shape, y: Float): Float {
        var ell = 0f
        val dy = (y - s.hcy) / s.ry
        if (abs(dy) < 1f) ell = s.rx * sqrt(1f - dy * dy)
        var st = 0f
        if (y >= s.sy) {
            val t = min(1f, (y - s.sy) / (s.sb - s.sy))
            st = s.s0 + (s.s1 - s.s0) * t
            val yc = s.sb - s.s1
            if (y > yc) {
                val d = y - yc
                st = if (d < s.s1) sqrt(max(0f, s.s1 * s.s1 - d * d)) else 0f
            }
        }
        if (ell <= 0f || st <= 0f) return max(ell, st)
        val h = (0.5f + 0.5f * (ell - st) / s.fil).coerceIn(0f, 1f)
        return st * (1 - h) + ell * h + s.fil * h * (1 - h)
    }

    private val outlines = HashMap<String, Path>()

    @Synchronized
    private fun outline(style: Style, inset: Float): Path = outlines.getOrPut("$style|$inset") {
        val s = shape(style)
        val left = ArrayList<FloatArray>()
        val right = ArrayList<FloatArray>()
        var y = 0f
        while (y <= s.sb + 0.5f) {
            val w = halfWidth(s, y) - inset
            if (w > 0.05f) {
                left.add(floatArrayOf(s.hx - w, y)); right.add(floatArrayOf(s.hx + w, y))
            }
            y += 0.5f
        }
        Path().apply {
            left.forEachIndexed { i, p -> if (i == 0) moveTo(p[0], p[1]) else lineTo(p[0], p[1]) }
            for (i in right.indices.reversed()) lineTo(right[i][0], right[i][1])
            close()
        }
    }

    private fun budMatrix(s: Shape, mirror: Boolean, k: Float, ox: Float, oy: Float) = Matrix().apply {
        postRotate(if (mirror) TILT else -TILT, s.hx, s.hcy)
        if (mirror) postScale(-1f, 1f, s.hx, 0f)
        postScale(k, k)
        postTranslate(ox, oy)
    }

    private fun drawBud(c: Canvas, style: Style, mirror: Boolean, level: Int, connected: Boolean, k: Float, ox: Float, oy: Float, h: Int) {
        val s = shape(style)
        val m = budMatrix(s, mirror, k, ox, oy)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.save()
        c.concat(m)
        p.color = if (connected) EDGE else DIM_EDGE
        c.drawPath(outline(style, 0f), p)
        p.color = if (connected) BODY else DIM_BODY
        c.drawPath(outline(style, INSET), p)
        c.restore()
        if (connected && level in 0..100) {
            // level line is horizontal on screen: clip in screen space, then draw the inner shape lit
            val top = s.hcy - s.ry + INSET
            val bottom = s.sb - INSET
            val yFill = (bottom - (bottom - top) * level / 100f) * k + oy
            c.save()
            c.clipRect(0f, yFill, c.width.toFloat(), h.toFloat())
            c.concat(m)
            p.color = if (level <= LOW) Nd.ACCENT else FILL
            c.drawPath(outline(style, INSET), p)
            c.restore()
        }
        c.save()
        c.concat(m)
        p.color = if (connected) GRILLE else DIM_GRILLE
        for (i in -1..1) for (j in -1..1) c.drawCircle(s.hx + i * 8.5f, s.hcy + j * 8.5f, 2f, p)
        c.restore()
    }

    /** the plate carrying the illustration: [left] / [right] percent or -1; [connected] false = dimmed pair */
    @Synchronized
    fun illustrationPlate(
        wDp: Float, hDp: Float, density: Float, radiusDp: Float, style: Style, left: Int, right: Int, connected: Boolean,
    ): Bitmap {
        val key = "iplate|${wDp.toInt()}x${hDp.toInt()}|$density|$radiusDp|$style|$left|$right|$connected"
        cache[key]?.let { return it }
        val (w, h) = size(wDp, hDp, density)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = radiusDp * w / wDp
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE })
        val s = shape(style)
        val k = min(w / s.pairW, h / s.pairH) * 0.9f
        val ox = (w - s.pairW * k) / 2f
        val oy = (h - s.pairH * k) / 2f
        drawBud(c, style, false, if (connected) left else -1, connected, k, ox, oy, h)
        drawBud(c, style, true, if (connected) right else -1, connected, k, ox + s.dx * k, oy, h)
        cache[key] = bmp
        return bmp
    }

    /** any picture on the same plate (object-fit contain); [dim] fades it for "nothing connected" */
    @Synchronized
    fun plate(src: Bitmap, wDp: Float, hDp: Float, density: Float, radiusDp: Float, dim: Boolean, tag: String): Bitmap {
        val key = "plate|$tag|${src.width}x${src.height}|${wDp.toInt()}x${hDp.toInt()}|$density|$radiusDp|$dim"
        cache[key]?.let { return it }
        val (w, h) = size(wDp, hDp, density)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = radiusDp * w / wDp
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE })
        val inset = 0.06f * min(w, h)
        val box = RectF(inset, inset, w - inset, h - inset)
        val sc = min(box.width() / src.width, box.height() / src.height)
        val dw = src.width * sc
        val dh = src.height * sc
        val dst = RectF(box.centerX() - dw / 2f, box.centerY() - dh / 2f, box.centerX() + dw / 2f, box.centerY() + dh / 2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (dim) p.alpha = 90
        c.drawBitmap(src, null, dst, p)
        cache[key] = bmp
        return bmp
    }

}

