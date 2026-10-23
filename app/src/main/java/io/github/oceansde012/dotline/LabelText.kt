package io.github.oceansde012.dotline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.widget.RemoteViews
import kotlin.math.ceil

/**
 * Label text (IBM Plex Mono) drawn to a bitmap with a [Typeface] loaded from the app's own resources.
 *
 * Why not a TextView with android:fontFamily="@font/...": inside a RemoteViews the TextView is inflated and
 * drawn by the launcher process. The font attribute is resolved there, and launchers are free to substitute
 * the user's system font (One UI "font style") for text they host, so the label can come out in the system
 * face. A bitmap is drawn in OUR process with OUR typeface, so it looks the same on every launcher. Same idea
 * as [DotText] for the numerals.
 *
 * Sizes are in dp (not sp): widget boxes are laid out in dp, the system font scale must not break them.
 * The text is exposed to accessibility through the ImageView's contentDescription ([putLabel]).
 */
object LabelText {
    private const val MAX_BYTES = 200_000
    private const val STEP = 0.5f

    @Volatile private var regular: Typeface? = null
    @Volatile private var medium: Typeface? = null

    private fun face(ctx: Context, med: Boolean): Typeface {
        (if (med) medium else regular)?.let { return it }
        val tf = try {
            ctx.applicationContext.resources.getFont(if (med) R.font.label_medium else R.font.label_regular)
        } catch (e: Exception) {
            Typeface.MONOSPACE
        }
        if (med) medium = tf else regular = tf
        return tf
    }

    private val cache = object : LinkedHashMap<String, DotText.Art>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DotText.Art>?) = size > 60
    }

    /**
     * [text] as a bitmap no wider than [maxWidthDp] and no taller than [maxLines] lines. The size starts at
     * [sizeDp] and shrinks down to [minSizeDp] until the text fits without a cut; if it still does not fit it
     * is cut with an ellipsis. The returned size (dp) is the exact size the ImageView must have.
     */
    @Synchronized
    fun art(
        ctx: Context, text: String, sizeDp: Float, color: Int, maxWidthDp: Float,
        medium: Boolean = false, maxLines: Int = 1, minSizeDp: Float = sizeDp, spacingEm: Float = 0.04f,
    ): DotText.Art {
        val density = ctx.applicationContext.resources.displayMetrics.density
        val maxW = maxWidthDp.coerceAtLeast(8f)
        val key = "$text|${(sizeDp * 10).toInt()}|$color|${(maxW * 10).toInt()}|$medium|$maxLines|${(minSizeDp * 10).toInt()}|${(spacingEm * 100).toInt()}|${(density * 100).toInt()}"
        cache[key]?.let { return it }

        val tf = face(ctx, medium)
        var size = sizeDp
        var s = density
        var paint: TextPaint
        var layout: StaticLayout
        while (true) {
            // pixel budget: a label is tiny, but never trust the inputs
            s = minOf(density, kotlin.math.sqrt(MAX_BYTES / (maxW * size * 4f * maxLines * 1.6f)).coerceAtLeast(1f))
            paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                this.typeface = tf
                this.color = color
                textSize = size * s
                letterSpacing = spacingEm
            }
            layout = build(text, paint, (maxW * s).toInt().coerceAtLeast(1), maxLines)
            if (fits(text, paint, layout, maxW * s, maxLines) || size <= minSizeDp + 0.01f) break
            size = (size - STEP).coerceAtLeast(minSizeDp)
        }

        var w = 1f
        for (i in 0 until layout.lineCount) w = maxOf(w, layout.getLineWidth(i))
        val wPx = ceil(w).toInt().coerceAtLeast(1) + 1
        val hPx = layout.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bmp))
        return DotText.Art(bmp, wPx / s, hPx / s).also { cache[key] = it }
    }

    private fun build(text: String, paint: TextPaint, widthPx: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

    /** no ellipsis and no word broken in the middle */
    private fun fits(text: String, paint: TextPaint, layout: StaticLayout, widthPx: Float, maxLines: Int): Boolean {
        for (i in 0 until layout.lineCount) if (layout.getEllipsisCount(i) > 0) return false
        if (maxLines > 1) for (word in text.split(' ')) if (paint.measureText(word) > widthPx) return false
        return true
    }
}

/** shows a rendered label in an ImageView, sized exactly (dp), with the text as its accessibility description */
internal fun RemoteViews.putLabel(id: Int, a: DotText.Art, text: CharSequence) {
    putArt(id, a)
    setContentDescription(id, text)
    setViewVisibility(id, View.VISIBLE)
}

/** the sum of a label's heights is needed before it is placed: render, set, and return the art */
internal fun RemoteViews.label(
    ctx: Context, id: Int, text: String, sizeDp: Float, color: Int, maxWidthDp: Float,
    medium: Boolean = false, maxLines: Int = 1, minSizeDp: Float = sizeDp,
): DotText.Art {
    val a = LabelText.art(ctx, text, sizeDp, color, maxWidthDp, medium, maxLines, minSizeDp)
    putLabel(id, a, text)
    return a
}
