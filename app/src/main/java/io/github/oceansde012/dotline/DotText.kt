package io.github.oceansde012.dotline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Dot-matrix text drawn by us, never by a font: a 7-row dot glyph set (digits, A-Z, common punctuation,
 * a bell) rendered with round dots on a grid. Lit dots use the given colour, the unlit matrix stays faintly
 * visible behind (#2C2C2F at 60%). Dot diameter = 0.62 of the pitch.
 *
 * The glyph table below is also parsed by tools/preview_dots.py (one glyph per line: char, space, 7 rows
 * separated by `/`), so keep that format.
 */
object DotText {
    const val ROWS = 7
    const val DOT = 0.62f           // dot diameter / pitch
    private const val GAP = 1       // empty columns between glyphs
    private const val SPACE_W = 3
    private const val MAX_BYTES = 340_000   // one bitmap; a whole RemoteViews stays far below 700 KB

    private val SRC = """
0 .###./#...#/#...#/#...#/#...#/#...#/.###.
1 ..#../.##../..#../..#../..#../..#../.###.
2 .###./#...#/....#/...#./..#../.#.../#####
3 .###./#...#/....#/..##./....#/#...#/.###.
4 ...#./..##./.#.#./#..#./#####/...#./...#.
5 #####/#..../####./....#/....#/#...#/.###.
6 ..##./.#.../#..../####./#...#/#...#/.###.
7 #####/....#/...#./..#../.#.../.#.../.#...
8 .###./#...#/#...#/.###./#...#/#...#/.###.
9 .###./#...#/#...#/.####/....#/...#./.##..
A .###./#...#/#...#/#####/#...#/#...#/#...#
B ####./#...#/#...#/####./#...#/#...#/####.
C .###./#...#/#..../#..../#..../#...#/.###.
D ###../#..#./#...#/#...#/#...#/#..#./###..
E #####/#..../#..../####./#..../#..../#####
F #####/#..../#..../####./#..../#..../#....
G .###./#...#/#..../#.###/#...#/#...#/.####
H #...#/#...#/#...#/#####/#...#/#...#/#...#
I .###./..#../..#../..#../..#../..#../.###.
J ..###/...#./...#./...#./...#./#..#./.##..
K #...#/#..#./#.#../##.../#.#../#..#./#...#
L #..../#..../#..../#..../#..../#..../#####
M #...#/##.##/#.#.#/#.#.#/#...#/#...#/#...#
N #...#/##..#/#.#.#/#..##/#...#/#...#/#...#
O .###./#...#/#...#/#...#/#...#/#...#/.###.
P ####./#...#/#...#/####./#..../#..../#....
Q .###./#...#/#...#/#...#/#.#.#/#..#./.##.#
R ####./#...#/#...#/####./#.#../#..#./#...#
S .####/#..../#..../.###./....#/....#/####.
T #####/..#../..#../..#../..#../..#../..#..
U #...#/#...#/#...#/#...#/#...#/#...#/.###.
V #...#/#...#/#...#/#...#/#...#/.#.#./..#..
W #...#/#...#/#...#/#.#.#/#.#.#/##.##/#...#
X #...#/#...#/.#.#./..#../.#.#./#...#/#...#
Y #...#/#...#/.#.#./..#../..#../..#../..#..
Z #####/....#/...#./..#../.#.../#..../#####
: ./././#/././#/.
. ./././././././#
, ./././././.#/#.
- .../.../.../###/.../.../...
+ ...../..#../..#../#####/..#../..#../.....
% ##..#/##.#./...#./..#../.#.../.#.##/#..##
° ###/#.#/###/.../.../.../...
/ ....#/....#/...#./..#../.#.../#..../#....
! #/#/#/#/#/./#
? .###./#...#/....#/...#./..#../...../..#..
( ..#/.#./#../#../#../.#./..#
) #../.#./..#/..#/..#/.#./#..
' #/#/././././.
_ ...../...../...../...../...../...../#####
= ...../...../#####/...../#####/...../.....
· ./././#/././.
~ ...#.../..###../.#####./.#####./.#####./#######/..###..
"""

    private val glyphs: Map<Char, Array<String>> = HashMap<Char, Array<String>>().also { m ->
        for (line in SRC.lines()) {
            if (line.length < 4) continue
            m[line[0]] = line.substring(2).split('/').toTypedArray()
        }
    }

    const val BELL = '~'

    private fun glyph(c: Char): Array<String>? = glyphs[c.uppercaseChar()] ?: glyphs[c]

    private fun width(c: Char): Int = if (c == ' ') SPACE_W else glyph(c)?.get(0)?.length ?: 0

    /** number of dot columns of [text] (glyph widths + 1 empty column between glyphs) */
    fun cols(text: String): Int {
        var n = 0
        var first = true
        for (c in text) {
            val w = width(c)
            if (w == 0) continue
            if (!first) n += GAP
            n += w
            first = false
        }
        return n.coerceAtLeast(1)
    }

    /** the largest pitch (dp) at which [text] fits a [boxW] x [boxH] dp box */
    fun pitchFor(text: String, boxW: Float, boxH: Float): Float =
        minOf(boxW / cols(text), boxH / ROWS).coerceAtLeast(0.5f)

    /** a rendered text: bitmap + the exact size in dp the ImageView must have */
    class Art(val bmp: Bitmap, val wDp: Float, val hDp: Float)

    private val cache = object : LinkedHashMap<String, Art>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Art>?) = size > 40
    }

    fun offColor(): Int = Color.argb(153, Color.red(Nd.OFF), Color.green(Nd.OFF), Color.blue(Nd.OFF))

    /** px per dp so that [wDp] x [hDp] stays within the bitmap budget (and never above the display density) */
    fun scaleFor(wDp: Float, hDp: Float, density: Float): Float {
        val area = (wDp * hDp * 4f).coerceAtLeast(1f)
        return minOf(density, sqrt(MAX_BYTES / area))
    }

    @Synchronized
    fun art(text: String, pitchDp: Float, density: Float, lit: Int = Nd.FG, matrix: Boolean = true): Art {
        val key = "$text|${(pitchDp * 100).toInt()}|${(density * 100).toInt()}|$lit|$matrix"
        cache[key]?.let { return it }
        val cols = cols(text)
        val wDp = cols * pitchDp
        val hDp = ROWS * pitchDp
        val s = scaleFor(wDp, hDp, density)
        val pitch = pitchDp * s
        val bmp = Bitmap.createBitmap(ceil(cols * pitch).toInt().coerceAtLeast(1), ceil(ROWS * pitch).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = pitch * DOT / 2f
        if (matrix) {
            p.color = offColor()
            for (col in 0 until cols) for (row in 0 until ROWS) {
                c.drawCircle((col + 0.5f) * pitch, (row + 0.5f) * pitch, r, p)
            }
        }
        p.color = lit
        var x = 0
        var first = true
        for (ch in text) {
            val w = width(ch)
            if (w == 0) continue
            if (!first) x += GAP
            first = false
            val g = glyph(ch)
            if (g != null) {
                for (row in 0 until minOf(ROWS, g.size)) for (col in 0 until w) {
                    if (g[row][col] == '#') {
                        if (matrix) {   // clear the faint dot below so the lit one is a clean colour
                            val cx = (x + col + 0.5f) * pitch
                            val cy = (row + 0.5f) * pitch
                            p.xfermode = CLEAR
                            c.drawCircle(cx, cy, r + 0.5f, p)
                            p.xfermode = null
                        }
                        c.drawCircle((x + col + 0.5f) * pitch, (row + 0.5f) * pitch, r, p)
                    }
                }
            }
            x += w
        }
        return Art(bmp, wDp, hDp).also { cache[key] = it }
    }

    private val CLEAR = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)

    /**
     * [n] dots in one row: the first [lit] in [litColor], [accentIndex] in accent, the rest unlit.
     * Pitch in dp; dot = 0.62 pitch like everywhere else.
     */
    @Synchronized
    fun bar(n: Int, lit: Int, litColor: Int, accentIndex: Int, pitchDp: Float, density: Float): Art {
        val key = "bar|$n|$lit|$litColor|$accentIndex|${(pitchDp * 100).toInt()}|${(density * 100).toInt()}"
        cache[key]?.let { return it }
        val wDp = n * pitchDp
        val s = scaleFor(wDp, pitchDp, density)
        val pitch = pitchDp * s
        val bmp = Bitmap.createBitmap(ceil(n * pitch).toInt().coerceAtLeast(1), ceil(pitch).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = pitch * DOT / 2f
        for (i in 0 until n) {
            p.color = when {
                i == accentIndex -> Nd.ACCENT
                i < lit -> litColor
                else -> Nd.OFF
            }
            c.drawCircle((i + 0.5f) * pitch, pitch / 2f, r, p)
        }
        return Art(bmp, wDp, pitchDp).also { cache[key] = it }
    }
}
