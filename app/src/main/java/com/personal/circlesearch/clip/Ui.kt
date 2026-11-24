package com.personal.circlesearch.clip

import com.personal.circlesearch.R

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    val BG = Color.parseColor("#151922")
    val CARD = Color.parseColor("#1F2430")
    val CARD2 = Color.parseColor("#2A3040")
    val ACCENT = Color.parseColor("#6C8CFF")
    val TEXT = Color.parseColor("#ECEFF6")
    val SUB = Color.parseColor("#8E97AB")
    val AMBER = Color.parseColor("#FFB224")
    val RED = Color.parseColor("#FF5C6C")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, rDp: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(rDp).toFloat()
    return g
}

fun Context.iconView(res: Int, tint: Int, sizeDp: Int = 20, padDp: Int = 6): ImageView {
    val v = ImageView(this)
    v.setImageResource(res)
    v.setColorFilter(tint)
    val p = dp(padDp)
    v.setPadding(p, p, p, p)
    val s = dp(sizeDp + padDp * 2)
    v.layoutParams = LinearLayout.LayoutParams(s, s)
    return v
}

fun Context.label(t: String, sp: Float = 14f, color: Int = C.TEXT): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = sp
    v.setTextColor(color)
    return v
}

fun Context.pill(t: String, bg: Int, fg: Int = C.TEXT, small: Boolean = false): TextView {
    val v = label(t, if (small) 11f else 13f, fg)
    if (small) v.setPadding(dp(9), dp(4), dp(9), dp(4)) else v.setPadding(dp(12), dp(7), dp(12), dp(7))
    v.background = rounded(bg, if (small) 12 else 16)
    v.gravity = Gravity.CENTER
    v.maxLines = 1
    return v
}

fun LP(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

/** Colours the edge handle can wear, so it never gets mixed up with another app's handle. */
val HANDLE_COLORS = intArrayOf(
    0xFFFFB224.toInt(), // amber (default, matches the pin)
    0xFF2ED47A.toInt(), // green
    0xFFFF4D8D.toInt(), // pink
    0xFF22D3EE.toInt(), // cyan
    0xFFA855F7.toInt(), // purple
    0xFFFF5C6C.toInt()  // red
)

/** Sticky-note colours: every card gets one so neighbours are easy to tell apart. */
val PALETTE = intArrayOf(
    0xFFFFEE8C.toInt(), // yellow
    0xFFFFB3CF.toInt(), // pink
    0xFF9ED8FF.toInt(), // sky blue
    0xFFB7F0A6.toInt(), // green
    0xFFFFC98F.toInt(), // orange
    0xFFD9C2FF.toInt(), // lavender
    0xFF9AF0DA.toInt(), // mint
    0xFFFFA9A0.toInt(), // coral
    0xFFE4F28A.toInt(), // lime
    0xFFA9BCFF.toInt(), // periwinkle
    0xFFF7B8F0.toInt(), // orchid
    0xFFFFD9A8.toInt()  // peach
)

/** Pencil colours of the board toolbar. */
val INK_COLORS = intArrayOf(
    0xFF1B1B1B.toInt(), 0xFF1F5BFF.toInt(), 0xFFE02B2B.toInt(), 0xFF1E9E4A.toInt(),
    0xFFF28A00.toInt(), 0xFF8E3CF0.toInt(), 0xFF7A4A21.toInt()
)
val INK_SIZES = floatArrayOf(1.6f, 3f, 5f, 8f)

fun Context.ovalDot(color: Int, ringColor: Int?): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(color)
    if (ringColor != null) g.setStroke(dp(2), ringColor)
    return g
}

/** Small icon + text button used by the long-press action bars and the ⋮ menu. */
fun Context.actionBtn(res: Int, text: String, fg: Int = C.TEXT, bg: Int = C.CARD2): LinearLayout {
    val r = LinearLayout(this)
    r.orientation = LinearLayout.HORIZONTAL
    r.gravity = Gravity.CENTER_VERTICAL
    r.setPadding(dp(9), dp(6), dp(11), dp(6))
    r.background = rounded(bg, 14)
    val ic = ImageView(this)
    ic.setImageResource(res)
    ic.setColorFilter(fg)
    r.addView(ic, LinearLayout.LayoutParams(dp(15), dp(15)))
    val t = label(text, 12f, fg)
    t.maxLines = 1
    t.setPadding(dp(5), 0, 0, 0)
    r.addView(t)
    return r
}

/** Small grey caption that starts a section of the ⋮ menu. */
fun Context.sectionLabel(t: String): TextView {
    val v = label(t.uppercase(), 10f, C.SUB)
    v.letterSpacing = 0.08f
    v.setPadding(dp(4), dp(10), dp(4), dp(4))
    return v
}

/** Lays children out left to right and wraps to a new line when the row is full. */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams
            val ws = if (lp != null && lp.width >= 0) MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
            else MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
            val hs = if (lp != null && lp.height >= 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
            else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            c.measure(ws, hs)
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += cw + hGap
            rowH = max(rowH, ch)
        }
        val h = y + rowH + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(wSpec), resolveSize(h, hSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + cw, paddingTop + y + ch)
            x += cw + hGap
            rowH = max(rowH, ch)
        }
    }
}

/** A ScrollView that never grows taller than [maxH] pixels (used for the ⋮ menu). */
class MaxHeightScroll(ctx: Context, var maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}
