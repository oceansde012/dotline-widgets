package io.github.oceansde012.dotline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The earbuds picture of the Buds widget. Everything is drawn here (no photos): [illustration] is our own
 * stem-style true-wireless pair, [plate] puts any picture (device image, user picture) on a rounded plate
 * with object-fit contain. Bitmaps are cached by their inputs.
 *
 * Illustration geometry (design units, one bud is 100 x 160): ear-tip head (ellipse) + short straight stem,
 * flat greys, a 3x3 dot grille on the head, a light tip on the stem. The right bud is the mirrored left bud,
 * the two overlap a little. When a level is known the bud outline is lit from the bottom as a thin meter.
 */
object BudsArt {
    private const val BODY = 0xFF3A3A3E.toInt()
    private const val EDGE = 0xFF5A5A60.toInt()
    private const val GRILLE = 0xFF76767C.toInt()
    private const val PLATE = 0xFF1A1A1D.toInt()
    private const val DIM_BODY = 0xFF2A2A2E.toInt()
    private const val MAX_BYTES = 200_000

    enum class Style { STEM, ROUND }

    /** model name to illustration style: Ear (a) / CMF Buds have a rounder, shorter stem */
    fun styleFor(model: String): Style {
        val m = model.lowercase()
        return if ("(a)" in m || "cmf" in m) Style.ROUND else Style.STEM
    }

    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }

    private fun size(wDp: Float, hDp: Float, density: Float): Pair<Int, Int> {
        var s = density
        val bytes = wDp * hDp * s * s * 4f
        if (bytes > MAX_BYTES) s *= Math.sqrt(MAX_BYTES / bytes.toDouble()).toFloat()
        return Pair((wDp * s).toInt().coerceAtLeast(8), (hDp * s).toInt().coerceAtLeast(8))
    }

    private fun budPath(style: Style): Path {
        val head = RectF(14f, 6f, 86f, 84f)
        val stem = if (style == Style.STEM) RectF(33f, 58f, 67f, 150f) else RectF(35f, 72f, 67f, 128f)
        val headRect = if (style == Style.ROUND) RectF(10f, 6f, 90f, 94f) else head
        val p = Path().apply { addOval(headRect, Path.Direction.CW) }
        val s = Path().apply { addRoundRect(stem, 17f, 17f, Path.Direction.CW) }
        p.op(s, Path.Op.UNION)
        return p
    }

    /**
     * [left] / [right] are percent or -1; [low] marks the red accent dot, otherwise a small white status dot
     * is shown when [connected]. A dimmed pair is the "nothing connected" picture.
     */
    @Synchronized
    fun illustration(
        wDp: Float, hDp: Float, density: Float, style: Style, left: Int, right: Int, connected: Boolean, low: Boolean,
    ): Bitmap {
        val key = "ill|${wDp.toInt()}x${hDp.toInt()}|$density|$style|$left|$right|$connected|$low"
        cache[key]?.let { return it }
        val (w, h) = size(wDp, hDp, density)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pairW = 172f
        val pairH = 160f
        val k = minOf(w / pairW, h / pairH) * 0.94f
        val ox = (w - pairW * k) / 2f
        val oy = (h - pairH * k) / 2f
        val path = budPath(style)
        drawBud(c, path, k, ox + 0f, oy, mirror = false, level = left, connected = connected, variant = style)
        drawBud(c, path, k, ox + 72f * k, oy, mirror = true, level = right, connected = connected, variant = style)
        // status dot: the only accent (red when a bud is low), white while connected
        if (connected) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (low) Nd.ACCENT else Nd.FG }
            c.drawCircle(w - 4f * density.coerceAtMost(3f), 4f * density.coerceAtMost(3f), 2.4f * density.coerceAtMost(3f), p)
        }
        cache[key] = bmp
        return bmp
    }

    private fun drawBud(c: Canvas, path: Path, k: Float, ox: Float, oy: Float, mirror: Boolean, level: Int, connected: Boolean, variant: Style) {
        c.save()
        val m = Matrix()
        m.postRotate(if (mirror) 5f else -5f, 50f, 48f)
        if (mirror) m.postScale(-1f, 1f, 50f, 0f)
        m.postScale(k, k)
        m.postTranslate(ox, oy)
        c.concat(m)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = if (connected) BODY else DIM_BODY }
        c.drawPath(path, fill)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = if (connected) EDGE else DIM_BODY
        }
        c.drawPath(path, edge)
        // level meter: the outline lit from the bottom, proportion = battery
        if (connected && level in 0..100) {
            c.save()
            val bottom = if (variant == Style.STEM) 150f else 128f
            val top = 4f
            c.clipRect(0f, bottom - (bottom - top) * level / 100f, 100f, 170f)
            val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.4f; color = Nd.FG }
            c.drawPath(path, lit)
            c.restore()
        }
        // 3x3 dot grille on the head
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (connected) GRILLE else 0xFF3A3A3E.toInt() }
        for (i in -1..1) for (j in -1..1) c.drawCircle(50f + i * 9f, 36f + j * 9f, 2.5f, dot)
        // light detail at the stem tip
        val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (connected) Nd.FG else 0xFF4A4A4E.toInt() }
        val bottom = if (variant == Style.STEM) 150f else 128f
        c.drawRoundRect(RectF(45f, bottom - 22f, 57f, bottom - 9f), 4f, 4f, tip)
        c.restore()
    }

    /** any picture on a rounded plate (object-fit contain); [dim] fades it for "nothing connected" */
    @Synchronized
    fun plate(src: Bitmap, wDp: Float, hDp: Float, density: Float, radiusDp: Float, dim: Boolean, tag: String): Bitmap {
        val key = "plate|$tag|${src.width}x${src.height}|${wDp.toInt()}x${hDp.toInt()}|$density|$dim"
        cache[key]?.let { return it }
        val (w, h) = size(wDp, hDp, density)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = radiusDp * w / wDp
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, bg)
        val inset = 0.06f * minOf(w, h)
        val box = RectF(inset, inset, w - inset, h - inset)
        val sc = minOf(box.width() / src.width, box.height() / src.height)
        val dw = src.width * sc
        val dh = src.height * sc
        val dst = RectF(box.centerX() - dw / 2f, box.centerY() - dh / 2f, box.centerX() + dw / 2f, box.centerY() + dh / 2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (dim) p.alpha = 90
        c.drawBitmap(src, null, dst, p)
        cache[key] = bmp
        return bmp
    }

    /** the plate for the illustration fallback: same plate, picture drawn straight on it */
    @Synchronized
    fun illustrationPlate(
        wDp: Float, hDp: Float, density: Float, radiusDp: Float, style: Style, left: Int, right: Int, connected: Boolean, low: Boolean,
    ): Bitmap {
        val key = "iplate|${wDp.toInt()}x${hDp.toInt()}|$density|$style|$left|$right|$connected|$low"
        cache[key]?.let { return it }
        val inner = illustration(wDp, hDp, density, style, left, right, connected, low)
        val (w, h) = size(wDp, hDp, density)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = radiusDp * w / wDp
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE })
        c.drawBitmap(Bitmap.createScaledBitmap(inner, w, h, true), 0f, 0f, null)
        cache[key] = bmp
        return bmp
    }
}



