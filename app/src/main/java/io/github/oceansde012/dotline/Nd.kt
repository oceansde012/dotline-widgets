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
 * Dot-matrix glyphs drawn as circles on a cell grid, so they stay crisp at any size. The player controls are
 * solid shapes on a 9x9 grid (like the dot numerals), the note keeps the icons.py 13x13 grid.
 */
object Glyphs {
    private const val GRID = 9
    private const val CELL_DOT = 0.40f   // dot radius, share of a cell

    /** a solid dot shape: cells (x, y) on a 9-row grid, [w] columns wide, nudged [shift] cells right to look centred */
    class Glyph(val cells: IntArray, val w: Int, val shift: Float = 0f)

    /** columns of dots by height (odd, centred on row 4); 0 = empty column */
    private fun columns(vararg h: Int, shift: Float = 0f): Glyph {
        val out = ArrayList<Int>()
        h.forEachIndexed { x, n -> if (n > 0) for (y in (GRID - n) / 2 until (GRID + n) / 2) { out.add(x); out.add(y) } }
        return Glyph(out.toIntArray(), h.size, shift)
    }

    val PLAY = columns(9, 7, 7, 5, 5, 3, 3, 1, shift = 0.5f)
    val PAUSE = columns(9, 9, 0, 0, 9, 9)
    val NEXT = columns(7, 5, 5, 3, 3, 1, 0, 7)
    val PREV = columns(7, 0, 1, 3, 3, 5, 5, 7)

    /** [g] centred on ([cx], [cy]); [box] is the height of the 9-row grid */
    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, box: Float, paint: Paint) {
        val cell = box / GRID
        val x0 = cx - (g.w / 2f - g.shift) * cell
        val y0 = cy - box / 2f
        val r = cell * CELL_DOT
        var i = 0
        while (i + 1 < g.cells.size) {
            c.drawCircle(x0 + (g.cells[i] + 0.5f) * cell, y0 + (g.cells[i + 1] + 0.5f) * cell, r, paint)
            i += 2
        }
    }

    /** music note, cells (x, y) of a 13x13 grid, dot radius 0.39 cell (icons.py render) */
    val NOTE = intArrayOf(2, 9, 2, 10, 2, 11, 3, 9, 3, 10, 3, 11, 3, 12, 4, 9, 4, 10, 4, 11, 4, 12, 5, 1, 5, 2, 5, 3, 5, 4, 5, 5, 5, 6, 5, 7, 5, 8, 5, 9, 5, 10, 5, 11, 6, 1, 6, 2, 7, 1, 7, 2, 8, 1, 8, 2, 8, 8, 8, 9, 8, 10, 9, 1, 9, 2, 9, 8, 9, 9, 9, 10, 9, 11, 10, 1, 10, 2, 10, 8, 10, 9, 10, 10, 10, 11, 11, 1, 11, 2, 11, 3, 11, 4, 11, 5, 11, 6, 11, 7, 11, 8, 11, 9, 11, 10)

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

