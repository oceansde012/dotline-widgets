package io.github.oceansde012.dotline

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Tiny view factory for the app screen (no AndroidX, no layout XML per card). Three type roles as in
 * DESIGN_SPEC.md section 1: hero (header: Doto), space_mono_regular caps (labels, buttons), system sans (body).
 */
internal class Ui(private val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density

    val heroFont: Typeface = ctx.resources.getFont(R.font.hero)
    val mono: Typeface = ctx.resources.getFont(R.font.space_mono_regular)

    fun dp(v: Int): Int = (v * density).roundToInt()

    fun text(s: CharSequence, sp: Float, color: Int, face: Typeface? = null, spacing: Float = 0f): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (face != null) typeface = face
            letterSpacing = spacing
        }

    /** Space Mono caps label, 0.08 em tracking */
    fun label(s: CharSequence, color: Int = Nd.DIM, sp: Float = 11f): TextView = text(s, sp, color, mono, 0.08f)

    /** system sans body text */
    fun body(s: CharSequence, color: Int = Nd.DIM, sp: Float = 14f): TextView =
        text(s, sp, color).apply { setLineSpacing(0f, 1.2f) }

    /** 48 dp button: primary = fg fill + black text (one per card), otherwise the #232326 chip */
    fun button(s: CharSequence, primary: Boolean): Button = Button(ctx).apply {
        text = s
        isAllCaps = false
        typeface = mono
        letterSpacing = 0.08f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(if (primary) Color.BLACK else Nd.FG)
        setBackgroundResource(if (primary) R.drawable.nd_btn_primary else R.drawable.nd_button)
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        gravity = Gravity.CENTER
        setPadding(dp(20), 0, dp(20), 0)
    }

    /** text-only secondary action: left aligned, 48 dp touch height, underlined */
    fun link(s: CharSequence, color: Int = Nd.FG): Button = Button(ctx).apply {
        text = android.text.SpannableString(s).also {
            it.setSpan(android.text.style.UnderlineSpan(), 0, it.length, 0)
        }
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(color)
        setBackgroundResource(R.drawable.nd_row)
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
    }

    fun hLp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** LinearLayout params with margins in dp */
    fun lp(
        w: Int = MATCH, h: Int = WRAP, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, weight: Float = 0f,
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight).apply {
        setMargins(dp(start), dp(top), dp(end), dp(bottom))
    }

    fun vertical(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** two-or-more option switch: selected = fg pill with black text, others dim on the chip track */
    fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.nd_seg_track)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val items = ArrayList<TextView>()
        fun paint(sel: Int) {
            items.forEachIndexed { i, v ->
                v.setBackgroundResource(if (i == sel) R.drawable.nd_seg_on else R.drawable.nd_row)
                v.setTextColor(if (i == sel) Color.BLACK else Nd.DIM)
                v.isSelected = i == sel
            }
        }
        options.forEachIndexed { i, s ->
            val v = label(s, Nd.DIM, 12f).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!it.isSelected) {
                        paint(i)
                        onPick(i)
                    }
                }
            }
            items.add(v)
            track.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        paint(selected)
        return track
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** clears every click handler under [v]: a gallery preview shows the widget, it must not fire the widget's taps */
internal fun neutralize(v: View) {
    v.setOnClickListener(null)
    v.isClickable = false
    v.isLongClickable = false
    v.isFocusable = false
    if (v is ViewGroup) for (i in 0 until v.childCount) neutralize(v.getChildAt(i))
}
