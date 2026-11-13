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
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * A small, faint edge handle. Tap it once (one finger) to start Circle Search
 * (or to search the saved area, if one is saved).
 * Press, hold and drag it to move it anywhere along the left or right edge; it snaps to the
 * nearest edge when released and remembers its position.
 */
class GestureStrip(
    private val context: Context,
    private val prefs: Prefs,
    private val onTap: () -> Unit
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
            context.dpInt(WIDTH_DP), context.dpInt(HEIGHT_DP),
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
        if (v.dragging) return
        place(v, p)
        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    private fun clampY(y: Int, height: Int): Int {
        val b = wm.screenBounds()
        return y.coerceIn(0, (b.height() - height).coerceAtLeast(0))
    }

    private fun place(v: StripView, p: WindowManager.LayoutParams) {
        val b = wm.screenBounds()
        val onRight = prefs.stripOnRight
        p.width = context.dpInt(WIDTH_DP)
        p.height = context.dpInt(HEIGHT_DP)
        p.x = if (onRight) b.width() - p.width else 0
        p.y = clampY((prefs.stripYFraction * b.height()).toInt(), p.height)
        v.onRight = onRight
        v.invalidate()
    }

    @SuppressLint("ViewConstructor")
    private inner class StripView(ctx: Context) : View(ctx) {
        var onRight = false
        var dragging = false

        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val holdMs = ViewConfiguration.getLongPressTimeout().toLong()
        private var downX = 0f
        private var downY = 0f
        private var startWinX = 0
        private var startWinY = 0
        private var moved = false
        private val longPress = Runnable {
            if (!moved) {
                dragging = true
                alphaBoost = true
                invalidate()
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
        private var alphaBoost = false

        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val len = context.dp(56f)
            val top = (height - len) / 2f
            val outerW = context.dp(8f)
            val innerW = context.dp(4f)
            val edge = if (onRight) width.toFloat() else 0f
            val dir = if (onRight) -1f else 1f
            val r = context.dp(4f)
            val k = if (alphaBoost) 1f else prefs.stripOpacity
            shadow.alpha = (70 * k).toInt()
            line.alpha = (255 * k).toInt()
            if (k <= 0f) return

            val x2 = edge + dir * (context.dp(2f) + outerW)
            rect.set(minOf(edge + dir * context.dp(2f), x2), top, maxOf(edge + dir * context.dp(2f), x2), top + len)
            canvas.drawRoundRect(rect, r, r, shadow)

            val x1 = edge + dir * (context.dp(4f) + innerW)
            rect.set(minOf(edge + dir * context.dp(4f), x1), top + context.dp(4f), maxOf(edge + dir * context.dp(4f), x1), top + len - context.dp(4f))
            canvas.drawRoundRect(rect, r, r, line)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = lp ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startWinX = p.x
                    startWinY = p.y
                    moved = false
                    dragging = false
                    postDelayed(longPress, holdMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        // Moved before the hold finished: treat as a drag straight away.
                        moved = true
                        removeCallbacks(longPress)
                        dragging = true
                        alphaBoost = true
                        invalidate()
                    }
                    if (dragging) {
                        val b = wm.screenBounds()
                        p.x = (startWinX + dx).toInt().coerceIn(0, (b.width() - p.width).coerceAtLeast(0))
                        p.y = clampY((startWinY + dy).toInt(), p.height)
                        try { wm.updateViewLayout(this, p) } catch (_: Throwable) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (dragging) {
                        val b = wm.screenBounds()
                        val right = p.x + p.width / 2 > b.width() / 2
                        dragging = false
                        alphaBoost = false
                        onRight = right
                        p.x = if (right) b.width() - p.width else 0
                        try { wm.updateViewLayout(this, p) } catch (_: Throwable) {}
                        invalidate()
                        prefs.stripYFraction = p.y.toFloat() / b.height().coerceAtLeast(1)
                        prefs.stripOnRight = right
                    } else if (!moved) {
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    dragging = false
                    alphaBoost = false
                    invalidate()
                }
            }
            return true
        }
    }

    companion object {
        private const val WIDTH_DP = 28
        private const val HEIGHT_DP = 72
    }
}
