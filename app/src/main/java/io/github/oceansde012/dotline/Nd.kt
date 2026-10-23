package io.github.oceansde012.dotline

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Design tokens, DESIGN_SPEC.md section 1 (mirrored in res/values/colors.xml). */
object Nd {
    val FG = Color.rgb(0xF2, 0xF2, 0xF2)
    val DIM = Color.rgb(0x8E, 0x8E, 0x93)
    val FAINT = Color.rgb(0x4A, 0x4A, 0x4E)
    val BG = Color.rgb(0x10, 0x10, 0x12)
    val STROKE = Color.rgb(0x34, 0x34, 0x3A)
    val OFF = Color.rgb(0x2C, 0x2C, 0x2F)
    val ACCENT = Color.rgb(0xD7, 0x19, 0x21)
    val CHIP = Color.rgb(0x23, 0x23, 0x26)
    val GLYPH = FG
}

/**
 * Dot-matrix glyphs, exact dot centres of widgets_v2/dots.py build_controls() (mc_*.png) and the
 * icons.py note (13x13 grid). Coordinates are shares of the icon box, drawn as circles so they stay
 * crisp at any size instead of downscaling a 520 px PNG.
 */
object Glyphs {
    const val CTRL_DOT = 0.034f   // dots.py DOT for controls, share of the icon box

    val PLAY = floatArrayOf(0.3000f, 0.2200f, 0.3600f, 0.2550f, 0.4200f, 0.2900f, 0.4800f, 0.3250f, 0.5400f, 0.3600f, 0.6000f, 0.3950f, 0.6600f, 0.4300f, 0.7200f, 0.4650f, 0.7800f, 0.5000f, 0.7200f, 0.5350f, 0.6600f, 0.5700f, 0.6000f, 0.6050f, 0.5400f, 0.6400f, 0.4800f, 0.6750f, 0.4200f, 0.7100f, 0.3600f, 0.7450f, 0.3000f, 0.7800f, 0.3000f, 0.7100f, 0.3000f, 0.6400f, 0.3000f, 0.5700f, 0.3000f, 0.5000f, 0.3000f, 0.4300f, 0.3000f, 0.3600f, 0.3000f, 0.2900f)
    val PAUSE = floatArrayOf(0.3600f, 0.2200f, 0.3600f, 0.2957f, 0.3600f, 0.3714f, 0.3600f, 0.4470f, 0.3600f, 0.5227f, 0.3600f, 0.5984f, 0.3600f, 0.6741f, 0.3600f, 0.7497f, 0.6400f, 0.2200f, 0.6400f, 0.2957f, 0.6400f, 0.3714f, 0.6400f, 0.4470f, 0.6400f, 0.5227f, 0.6400f, 0.5984f, 0.6400f, 0.6741f, 0.6400f, 0.7497f)
    val PREV = floatArrayOf(0.2400f, 0.2200f, 0.2400f, 0.2957f, 0.2400f, 0.3714f, 0.2400f, 0.4470f, 0.2400f, 0.5227f, 0.2400f, 0.5984f, 0.2400f, 0.6741f, 0.2400f, 0.7497f, 0.7800f, 0.7800f, 0.7250f, 0.7450f, 0.6700f, 0.7100f, 0.6150f, 0.6750f, 0.5600f, 0.6400f, 0.5050f, 0.6050f, 0.4500f, 0.5700f, 0.3950f, 0.5350f, 0.3400f, 0.5000f, 0.3950f, 0.4650f, 0.4500f, 0.4300f, 0.5050f, 0.3950f, 0.5600f, 0.3600f, 0.6150f, 0.3250f, 0.6700f, 0.2900f, 0.7250f, 0.2550f, 0.7800f, 0.2200f, 0.7800f, 0.2900f, 0.7800f, 0.3600f, 0.7800f, 0.4300f, 0.7800f, 0.5000f, 0.7800f, 0.5700f, 0.7800f, 0.6400f, 0.7800f, 0.7100f)
    val NEXT = floatArrayOf(0.2200f, 0.2200f, 0.2750f, 0.2550f, 0.3300f, 0.2900f, 0.3850f, 0.3250f, 0.4400f, 0.3600f, 0.4950f, 0.3950f, 0.5500f, 0.4300f, 0.6050f, 0.4650f, 0.6600f, 0.5000f, 0.6050f, 0.5350f, 0.5500f, 0.5700f, 0.4950f, 0.6050f, 0.4400f, 0.6400f, 0.3850f, 0.6750f, 0.3300f, 0.7100f, 0.2750f, 0.7450f, 0.2200f, 0.7800f, 0.2200f, 0.7100f, 0.2200f, 0.6400f, 0.2200f, 0.5700f, 0.2200f, 0.5000f, 0.2200f, 0.4300f, 0.2200f, 0.3600f, 0.2200f, 0.2900f, 0.7600f, 0.2200f, 0.7600f, 0.2957f, 0.7600f, 0.3714f, 0.7600f, 0.4470f, 0.7600f, 0.5227f, 0.7600f, 0.5984f, 0.7600f, 0.6741f, 0.7600f, 0.7497f)

    /** music note, cells (x, y) of a 13x13 grid, dot radius 0.39 cell (icons.py render) */
    val NOTE = intArrayOf(2, 9, 2, 10, 2, 11, 3, 9, 3, 10, 3, 11, 3, 12, 4, 9, 4, 10, 4, 11, 4, 12, 5, 1, 5, 2, 5, 3, 5, 4, 5, 5, 5, 6, 5, 7, 5, 8, 5, 9, 5, 10, 5, 11, 6, 1, 6, 2, 7, 1, 7, 2, 8, 1, 8, 2, 8, 8, 8, 9, 8, 10, 9, 1, 9, 2, 9, 8, 9, 9, 9, 10, 9, 11, 10, 1, 10, 2, 10, 8, 10, 9, 10, 10, 10, 11, 11, 1, 11, 2, 11, 3, 11, 4, 11, 5, 11, 6, 11, 7, 11, 8, 11, 9, 11, 10)

    fun draw(c: Canvas, pts: FloatArray, cx: Float, cy: Float, box: Float, paint: Paint) {
        val x0 = cx - box / 2f
        val y0 = cy - box / 2f
        val r = CTRL_DOT * box
        var i = 0
        while (i + 1 < pts.size) {
            c.drawCircle(x0 + pts[i] * box, y0 + pts[i + 1] * box, r, paint)
            i += 2
        }
    }

    fun drawNote(c: Canvas, cx: Float, cy: Float, box: Float, paint: Paint) {
        val cell = box / 13f
        val x0 = cx - box / 2f
        val y0 = cy - box / 2f
        val r = cell * 0.39f
        var i = 0
        while (i + 1 < NOTE.size) {
            c.drawCircle(x0 + (NOTE[i] + 0.5f) * cell, y0 + (NOTE[i + 1] + 0.5f) * cell, r, paint)
            i += 2
        }
    }
}

