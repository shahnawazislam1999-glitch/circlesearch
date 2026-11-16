package com.personal.circlesearch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Draws the stack of colour-coded edge handles. Shared by the real on-screen cluster
 * ([GestureStrip]) and the live preview in the settings screen, so both always look the same.
 */
class HandlePainter(private val ctx: Context) {

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = ctx.dp(1f)
    }
    private val rect = RectF()

    /**
     * @param colors   one colour per handle, top to bottom
     * @param handlePx height of every handle (all handles share the same size)
     * @param viewWidth width of the strip/view; handles hug the left or right edge of it
     * @param opacity  0..1 for the whole cluster
     * @param pressed  index of the handle being pressed, or -1
     * @param boost    draw at full strength (used while the cluster is being dragged)
     */
    fun draw(
        canvas: Canvas,
        colors: IntArray,
        handlePx: Float,
        onRight: Boolean,
        viewWidth: Float,
        opacity: Float,
        pressed: Int = -1,
        boost: Boolean = false
    ) {
        val base = if (boost) 1f else opacity
        if (base <= 0f) return
        val pad = padPx(ctx)
        val gap = gapPx(ctx)
        val bar = ctx.dp(BAR_DP)
        val inset = ctx.dp(INSET_DP)
        val halo = ctx.dp(1.5f)
        val radius = bar / 2f

        for (i in colors.indices) {
            val isPressed = i == pressed
            val k = if (isPressed) maxOf(base, 0.95f) else base
            val w = bar + if (isPressed) ctx.dp(2f) else 0f
            val left = if (onRight) viewWidth - inset - w else inset
            val top = pad + i * (handlePx + gap)

            shadow.alpha = (80 * k).toInt()
            rect.set(left - halo, top - halo, left + w + halo, top + handlePx + halo)
            canvas.drawRoundRect(rect, radius + halo, radius + halo, shadow)

            body.color = colors[i]
            body.alpha = (255 * k).toInt()
            rect.set(left, top, left + w, top + handlePx)
            canvas.drawRoundRect(rect, radius, radius, body)

            stroke.alpha = (120 * k).toInt()
            val half = stroke.strokeWidth / 2f
            rect.inset(half, half)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    companion object {
        /** Width of the touch strip (and of its window). */
        const val WIDTH_DP = 28
        private const val GAP_DP = 8f
        private const val PAD_DP = 4f
        private const val BAR_DP = 7f
        private const val INSET_DP = 3f

        fun gapPx(ctx: Context): Float = ctx.dp(GAP_DP)
        fun padPx(ctx: Context): Float = ctx.dp(PAD_DP)

        fun clusterHeightPx(ctx: Context, count: Int, handlePx: Float): Float =
            2f * padPx(ctx) + count * handlePx + (count - 1).coerceAtLeast(0) * gapPx(ctx)
    }
}

/** Small preview card for the settings screen: shows the handles exactly as they will look. */
class HandlePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val painter = HandlePainter(context)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics
        )
    }
    private val rect = RectF()

    private var colors = IntArray(0)
    private var handlePx = 0f
    private var onRight = false
    private var opacity = 1f

    fun bind(prefs: Prefs) {
        val list = IntArray(prefs.shortcuts().size + 1)
        list[0] = prefs.searchHandleColor
        prefs.shortcuts().forEachIndexed { i, s -> list[i + 1] = s.color }
        colors = list
        handlePx = context.dp(prefs.handleHeightDp.toFloat())
        onRight = prefs.stripOnRight
        // Never fully blank in the preview, so colours can still be judged at 0% opacity.
        opacity = maxOf(prefs.stripOpacity, 0.2f)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (HandlePainter.clusterHeightPx(context, colors.size.coerceAtLeast(1), handlePx) +
            context.dp(24f)).toInt().coerceAtLeast(context.dpInt(96))
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (colors.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        bg.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, context.dp(16f), context.dp(16f), bg)

        text.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        text.alpha = 140
        canvas.drawText("Preview", w / 2f, h / 2f + text.textSize / 3f, text)

        val cluster = HandlePainter.clusterHeightPx(context, colors.size, handlePx)
        canvas.save()
        canvas.translate(0f, (h - cluster) / 2f)
        painter.draw(canvas, colors, handlePx, onRight, w, opacity)
        canvas.restore()
    }
}
