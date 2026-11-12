package com.personal.circlesearch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * A thin, nearly invisible strip on one screen edge. Tap it with two fingers to start a search.
 * It sits on the edge opposite the bubble so the two never overlap.
 */
class GestureStrip(
    private val context: Context,
    private val prefs: Prefs,
    private val onTwoFingerTap: () -> Unit
) {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private var view: StripView? = null
    private var lp: WindowManager.LayoutParams? = null

    fun show() {
        if (view != null) {
            relayout()
            return
        }
        val v = StripView(context)
        val p = WindowManager.LayoutParams(
            context.dpInt(WIDTH_DP), context.dpInt(WIDTH_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        place(v, p)
        try {
            wm.addView(v, p)
            view = v
            lp = p
        } catch (_: Throwable) {
            view = null
            lp = null
        }
    }

    fun hide() {
        view?.let {
            try { wm.removeViewImmediate(it) } catch (_: Throwable) {}
        }
        view = null
        lp = null
    }

    fun relayout() {
        val v = view ?: return
        val p = lp ?: return
        place(v, p)
        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    private fun place(v: StripView, p: WindowManager.LayoutParams) {
        val b = wm.screenBounds()
        val onRight = !prefs.bubbleOnRight
        p.width = context.dpInt(WIDTH_DP)
        p.height = (b.height() * 0.4f).toInt()
        p.x = if (onRight) b.width() - p.width else 0
        p.y = (b.height() - p.height) / 2
        v.onRight = onRight
        v.invalidate()
    }

    @SuppressLint("ViewConstructor")
    private inner class StripView(ctx: Context) : View(ctx) {
        var onRight = true

        private val detector = TwoFingerTapDetector(ctx) { onTwoFingerTap() }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(130, 255, 255, 255) }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val len = context.dp(56f)
            val top = (height - len) / 2f
            val outerW = context.dp(6f)
            val innerW = context.dp(3f)
            val edge = if (onRight) width.toFloat() else 0f
            val dir = if (onRight) -1f else 1f
            val r = context.dp(3f)

            val x2 = edge + dir * (context.dp(2f) + outerW)
            rect.set(minOf(edge + dir * context.dp(2f), x2), top, maxOf(edge + dir * context.dp(2f), x2), top + len)
            canvas.drawRoundRect(rect, r, r, shadow)

            val x1 = edge + dir * (context.dp(3.5f) + innerW)
            rect.set(minOf(edge + dir * context.dp(3.5f), x1), top + context.dp(4f), maxOf(edge + dir * context.dp(3.5f), x1), top + len - context.dp(4f))
            canvas.drawRoundRect(rect, r, r, line)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            detector.onTouchEvent(e)
            return true
        }
    }

    companion object {
        private const val WIDTH_DP = 28
    }
}
