package io.github.oceansde012.dotline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The earbuds picture of the Buds widget. Everything is drawn here (no photos, nothing third-party):
 * [illustrationPlate] is our own pair in one of three [Style]s (stem: white silicone tip, clear stem with a dark
 * core; bean: round shell with a short tip; over-ear: band and two cups), each with one round button; [plate] puts
 * any picture (device image, user picture) on the same rounded plate with object-fit contain. Bitmaps are cached
 * by their inputs. [styleFor] picks the style from the device name; unknown devices get the stem pair.
 *
 * Illustration (geometry mirrored by tools/buds_art.html, which renders it in a browser for visual iteration): one
 * bud is drawn in a 120x170 box, the right bud is the mirror image; the over-ear pair is one 232x170 drawing. The
 * button turns red when that side is at or below 15% (the widget's only accent); a dimmed pair is the "nothing
 * connected" picture.
 */
object BudsArt {
    enum class Style { STEM, BEAN, OVER }

    // plain substrings of the lower-case "model name", checked in this order; only drawing, never battery logic
    private val OVER_FIRST = listOf("airpods max")
    private val EARBUD_WORDS = listOf("buds", "earbud", "pods", "wf-", "tws")
    private val OVER_HINTS = listOf(
        "wh-", "xm3", "xm4", "xm5", "xm6", "quietcomfort 35", "qc35", "qc45", "qc 45", "nc 700", "bose 700", "headphone",
        "over-ear", "over ear", "studio pro", "momentum 4", "momentum 3", "px7", "px8", "px5", "arctis", "elite 85h", "evolve",
        "jbl live 6", "jbl tune 7", "jbl tune 5", "soundlink",
    )
    private val BEAN_HINTS = listOf("galaxy buds", "pixel buds", "wf-", "jabra elite", "liberty", "soundcore", "momentum true", "nord buds")

    fun styleFor(modelAndName: String): Style {
        val n = modelAndName.lowercase(java.util.Locale.ROOT)
        if (OVER_FIRST.any { it in n }) return Style.OVER
        if (EARBUD_WORDS.none { it in n } && OVER_HINTS.any { it in n }) return Style.OVER
        if (BEAN_HINTS.any { it in n }) return Style.BEAN
        return Style.STEM
    }

    private const val PLATE = 0xFF101012.toInt()
    private const val MAX_BYTES = 200_000
    private const val MAX_SIDE = 400f
    private const val LOW = 15
    private const val BUD_W = 120f
    private const val BUD_H = 170f
    private const val GAP = 112f          // distance between the two bud boxes
    private const val DIM_ALPHA = 97      // 0.38 of 255

    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }

    private fun size(wDp: Float, hDp: Float, density: Float): Pair<Int, Int> {
        var s = density
        val bytes = wDp * hDp * s * s * 4f
        if (bytes > MAX_BYTES) s *= sqrt(MAX_BYTES / bytes.toDouble()).toFloat()
        val longest = max(wDp, hDp) * s
        if (longest > MAX_SIDE) s *= MAX_SIDE / longest
        return Pair((wDp * s).toInt().coerceAtLeast(8), (hDp * s).toInt().coerceAtLeast(8))
    }

    private fun paint(color: Int = Color.WHITE) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun fillRound(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, p: Paint) =
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)

    /** one bud in its 120x170 box (right bud seen from outside); [mirror] makes the left one */
    private fun drawBud(c: Canvas, mirror: Boolean, low: Boolean, dim: Boolean, k: Float, ox: Float, oy: Float) {
        c.save()
        c.translate(ox, oy)
        c.scale(k, k)
        if (mirror) { c.translate(BUD_W, 0f); c.scale(-1f, 1f) }
        if (dim) c.saveLayerAlpha(0f, 0f, BUD_W, BUD_H, DIM_ALPHA)

        // ear tip: a tilted silicone dome with the dark opening on its rim
        c.save()
        c.translate(40f, 46f)
        c.rotate(-35.5f)
        val dome = paint().apply {
            shader = RadialGradient(-8f, -10f, 52f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFECECEF.toInt(), 0xFFB4B4BB.toInt()), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawOval(RectF(-40f, -33f, 40f, 33f), dome)
        c.drawOval(RectF(-31f, -25f, 23f, 13f), paint().apply {
            shader = LinearGradient(-20f, -24f, 10f, 10f, 0xFF2C2C31.toInt(), 0xFF08080A.toInt(), Shader.TileMode.CLAMP)
        })
        c.drawOval(RectF(-25f, -18f, 17f, 8f), paint(0xFFE4E4E8.toInt()))
        c.drawOval(RectF(-19f, -12.5f, 13f, 4.5f), paint().apply {
            shader = LinearGradient(0f, -18f, 0f, 8f, 0xFF1B1B1F.toInt(), 0xFF050506.toInt(), Shader.TileMode.CLAMP)
        })
        c.save(); c.rotate(-28.6f, -28f, 10f)
        c.drawOval(RectF(-36f, 7.5f, -20f, 12.5f), paint(0xB3FFFFFF.toInt()))
        c.restore()
        c.restore()

        // stem: clear shell around a dark core
        c.save()
        c.rotate(4f, 60f, 100f)
        fillRound(c, 48f, 34f, 88f, 160f, 16f, paint().apply {
            shader = LinearGradient(46f, 0f, 86f, 0f, intArrayOf(0x8CFFFFFF.toInt(), 0x24FFFFFF, 0x66FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawRoundRect(RectF(48f, 34f, 88f, 160f), 16f, 16f, paint(0xF2E6E8EE.toInt()).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f })
        fillRound(c, 53f, 39f, 83f, 155f, 12f, paint(0xFF0B0B0D.toInt()))
        c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(RectF(53f, 39f, 83f, 155f), 12f, 12f, android.graphics.Path.Direction.CW) })
        val dot = paint(0x12FFFFFF)
        var yy = 44
        while (yy < 152) {
            var xx = 56 + ((yy / 4) % 2) * 2
            while (xx < 82) { c.drawCircle(xx.toFloat(), yy.toFloat(), 0.7f, dot); xx += 4 }
            yy += 4
        }
        c.restore()
        // button: the one accent (red when this bud is low)
        c.drawCircle(64f, 58f, 7.5f, paint().apply {
            val (a, b) = if (low) 0xFFFF5A60.toInt() to 0xFFC4141C.toInt() else 0xFFFFFFFF.toInt() to 0xFFB9BBC2.toInt()
            shader = RadialGradient(62.5f, 56.5f, 11f, a, b, Shader.TileMode.CLAMP)
        })
        // speaker grille top right, microphone grille at the foot
        val grille = paint(0xFF2A2A2F.toInt())
        val lines = paint(0x59FFFFFF)
        fillRound(c, 72f, 44f, 81f, 57f, 3f, grille)
        for (i in 0 until 4) c.drawRect(73.5f, 46f + i * 3f, 79.5f, 46.9f + i * 3f, lines)
        fillRound(c, 60f, 144f, 76f, 151f, 2.5f, grille)
        for (i in 0 until 3) c.drawRect(62f, 145.2f + i * 2f, 74f, 145.9f + i * 2f, lines)
        c.restore()

        if (dim) c.restore()
        c.restore()
    }

    /** one round bud in its 120x170 box (right bud seen from outside); [mirror] makes the left one */
    private fun drawBean(c: Canvas, mirror: Boolean, low: Boolean, dim: Boolean, k: Float, ox: Float, oy: Float) {
        c.save()
        c.translate(ox, oy)
        c.scale(k, k)
        if (mirror) { c.translate(BUD_W, 0f); c.scale(-1f, 1f) }
        if (dim) c.saveLayerAlpha(0f, 0f, BUD_W, BUD_H, DIM_ALPHA)

        // ear tip: a short silicone nozzle at the lower inner side of the shell
        c.save()
        c.translate(26f, 118f)
        c.rotate(28.6f)
        c.drawOval(RectF(-20f, -26f, 20f, 26f), paint().apply {
            shader = RadialGradient(-5f, -6f, 26f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFECECEF.toInt(), 0xFFB4B4BB.toInt()), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawOval(RectF(-15f, -13f, 7f, 17f), paint(0xFF0A0A0C.toInt()))
        c.restore()

        // body: round shell, round touch face with one button
        c.save()
        c.translate(70f, 84f)
        c.rotate(17.2f)
        val shell = RectF(-42f, -54f, 42f, 54f)
        c.drawOval(shell, paint().apply {
            shader = RadialGradient(-14f, -22f, 62f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCDCE1.toInt(), 0xFF8E8E97.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawOval(shell, paint(0xF2E6E8EE.toInt()).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f })
        c.drawCircle(1f, -2f, 23f, paint(0xFF0B0B0D.toInt()))
        c.save()
        c.clipPath(Path().apply { addCircle(1f, -2f, 23f, Path.Direction.CW) })
        val dot = paint(0x12FFFFFF)
        var yy = -24
        while (yy < 24) {
            var xx = -24 + (((yy / 4) % 2 + 2) % 2) * 2
            while (xx < 26) { c.drawCircle(xx.toFloat(), yy.toFloat(), 0.7f, dot); xx += 4 }
            yy += 4
        }
        c.restore()
        c.drawCircle(1f, -2f, 7.5f, paint().apply {
            val (a, b) = if (low) 0xFFFF5A60.toInt() to 0xFFC4141C.toInt() else 0xFFFFFFFF.toInt() to 0xFFB9BBC2.toInt()
            shader = RadialGradient(-0.5f, -3.5f, 11f, a, b, Shader.TileMode.CLAMP)
        })
        c.restore()

        if (dim) c.restore()
        c.restore()
    }

    /** one over-ear cup, centred on (0,0) of the translated canvas */
    private fun drawCup(c: Canvas, low: Boolean) {
        val outer = RectF(-31f, -50f, 31f, 50f)
        c.drawRoundRect(outer, 28f, 28f, paint(0xFF17171A.toInt()))
        c.drawRoundRect(outer, 28f, 28f, paint().apply {
            shader = LinearGradient(-30f, 0f, 30f, 0f, intArrayOf(0x9EFFFFFF.toInt(), 0x33FFFFFF, 0x80FFFFFF.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawRoundRect(outer, 28f, 28f, paint(0xF2E6E8EE.toInt()).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f })
        val inner = RectF(-24f, -43f, 24f, 43f)
        c.drawRoundRect(inner, 22f, 22f, paint(0xFF0B0B0D.toInt()))
        c.save()
        c.clipPath(Path().apply { addRoundRect(inner, 22f, 22f, Path.Direction.CW) })
        val dot = paint(0x12FFFFFF)
        var yy = -40
        while (yy < 44) {
            var xx = -22 + (((yy / 4) % 2 + 2) % 2) * 2
            while (xx < 24) { c.drawCircle(xx.toFloat(), yy.toFloat(), 0.7f, dot); xx += 4 }
            yy += 4
        }
        c.restore()
        c.drawCircle(0f, 0f, 7.5f, paint().apply {
            val (a, b) = if (low) 0xFFFF5A60.toInt() to 0xFFC4141C.toInt() else 0xFFFFFFFF.toInt() to 0xFFB9BBC2.toInt()
            shader = RadialGradient(-1.5f, -1.5f, 11f, a, b, Shader.TileMode.CLAMP)
        })
    }

    /** the whole over-ear pair in its 232x170 box: band + two cups */
    private fun drawOver(c: Canvas, lowL: Boolean, lowR: Boolean, dim: Boolean, k: Float, ox: Float, oy: Float) {
        val w = GAP + BUD_W
        c.save()
        c.translate(ox, oy)
        c.scale(k, k)
        if (dim) c.saveLayerAlpha(0f, 0f, w, BUD_H, DIM_ALPHA)
        val band = Path().apply { moveTo(40f, 96f); cubicTo(40f, -6f, w - 40f, -6f, w - 40f, 96f) }
        fun stroke(width: Float, p: Paint) {
            p.style = Paint.Style.STROKE; p.strokeWidth = width; p.strokeCap = Paint.Cap.ROUND
            c.drawPath(band, p)
        }
        stroke(15f, paint(0xF2E6E8EE.toInt()))
        stroke(13f, paint().apply {
            shader = LinearGradient(0f, 8f, 0f, 90f, 0xBFFFFFFF.toInt(), 0x59FFFFFF, Shader.TileMode.CLAMP)
        })
        stroke(8f, paint(0xFF0B0B0D.toInt()))
        c.save(); c.translate(40f, 112f); drawCup(c, lowL); c.restore()
        c.save(); c.translate(w - 40f, 112f); drawCup(c, lowR); c.restore()
        if (dim) c.restore()
        c.restore()
    }

    /** the plate carrying the illustration: [left] / [right] percent or -1; [connected] false = dimmed pair */
    @Synchronized
    fun illustrationPlate(
        wDp: Float, hDp: Float, density: Float, radiusDp: Float, left: Int, right: Int, connected: Boolean,
        style: Style = Style.STEM,
    ): Bitmap {
        val key = "iplate|$style|${wDp.toInt()}x${hDp.toInt()}|$density|$radiusDp|$left|$right|$connected"
        cache[key]?.let { return it }
        val (w, h) = size(wDp, hDp, density)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = radiusDp * w / wDp
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint(PLATE))
        val pairW = GAP + BUD_W
        val k = min(w / pairW, h / BUD_H) * 0.9f
        val ox = (w - pairW * k) / 2f
        val oy = (h - BUD_H * k) / 2f
        val lowL = connected && left in 0..LOW
        val lowR = connected && right in 0..LOW
        when (style) {
            Style.OVER -> drawOver(c, lowL, lowR, !connected, k, ox, oy)
            Style.BEAN -> { drawBean(c, false, lowL, !connected, k, ox, oy); drawBean(c, true, lowR, !connected, k, ox + GAP * k, oy) }
            Style.STEM -> { drawBud(c, false, lowL, !connected, k, ox, oy); drawBud(c, true, lowR, !connected, k, ox + GAP * k, oy) }
        }
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

