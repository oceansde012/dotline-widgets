package io.github.oceansde012.dotline

import android.widget.RemoteViews
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared tile metrics (DESIGN_SPEC.md section 1). The radius of a RemoteViews background cannot be
 * set per instance from a drawable, so res/drawable holds nd_tile_r10..r26 (and matching ripple
 * zones) and the provider switches the background to the variant closest to the formula.
 */
object Tile {
    private const val R_MAX = 26f
    private const val P_MAX = 16f

    /** tile corner radius in dp: min(26, 0.22 H) */
    fun radius(h: Float): Float = min(R_MAX, 0.22f * h).coerceAtLeast(10f)

    /** inner padding in dp: min(16, 0.14 H) */
    fun pad(h: Float): Float = min(P_MAX, 0.14f * h)

    private fun step(h: Float): Int = ((radius(h) / 2f).roundToInt() * 2).coerceIn(10, 26)

    private fun tileRes(r: Int) = when (r) {
        10 -> R.drawable.nd_tile_r10
        12 -> R.drawable.nd_tile_r12
        14 -> R.drawable.nd_tile_r14
        16 -> R.drawable.nd_tile_r16
        18 -> R.drawable.nd_tile_r18
        20 -> R.drawable.nd_tile_r20
        22 -> R.drawable.nd_tile_r22
        24 -> R.drawable.nd_tile_r24
        else -> R.drawable.nd_tile_r26
    }

    private fun zoneRes(r: Int) = when (r) {
        10 -> R.drawable.nd_zone_r10
        12 -> R.drawable.nd_zone_r12
        14 -> R.drawable.nd_zone_r14
        16 -> R.drawable.nd_zone_r16
        18 -> R.drawable.nd_zone_r18
        20 -> R.drawable.nd_zone_r20
        22 -> R.drawable.nd_zone_r22
        24 -> R.drawable.nd_zone_r24
        else -> R.drawable.nd_zone_r26
    }

    /** background (fill + 1dp stroke + corners) of the tile view and the matching ripple of the full-tile tap zone */
    fun apply(rv: RemoteViews, tileId: Int, zoneId: Int?, h: Float) {
        val r = step(h)
        rv.setInt(tileId, "setBackgroundResource", tileRes(r))
        if (zoneId != null) rv.setInt(zoneId, "setBackgroundResource", zoneRes(r))
    }
}