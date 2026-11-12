package com.personal.circlesearch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot

/** The small dim floating bubble. Tap = start search, drag = move, snaps to the screen edge. */
class BubbleOverlay(
    private val context: Context,
    private val prefs: Prefs,
    private val onActivate: () -> Unit
) {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private var view: BubbleView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var suppressed = false
    private var snapAnim: ValueAnimator? = null

    val isShown: Boolean get() = view != null

    fun show() {
        if (view != null) {
            refresh()
            return
        }
        val size = sizePx()
        val v = BubbleView(context)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        positionFromPrefs(params, size)
        v.alpha = prefs.opacity
        v.visibility = if (suppressed) View.INVISIBLE else View.VISIBLE
        try {
            wm.addView(v, params)
            view = v
            lp = params
        } catch (_: Throwable) {
            view = null
            lp = null
        }
    }

    fun hide() {
        snapAnim?.cancel()
        snapAnim = null
        view?.let {
            try { wm.removeViewImmediate(it) } catch (_: Throwable) {}
        }
        view = null
        lp = null
    }

    fun setSuppressed(value: Boolean) {
        suppressed = value
        view?.visibility = if (value) View.INVISIBLE else View.VISIBLE
    }

    fun refresh() {
        val v = view ?: return
        val p = lp ?: return
        if (snapAnim?.isRunning == true) return
        val size = sizePx()
        p.width = size
        p.height = size
        positionFromPrefs(p, size)
        v.alpha = prefs.opacity
        update(v, p)
    }

    fun onConfigurationChanged() = refresh()

    private fun sizePx(): Int = context.dpInt(prefs.bubbleSizeDp)

    private fun margin(): Int = context.dpInt(4)

    private fun clampY(y: Int, size: Int): Int {
        val b = wm.screenBounds()
        val ins = wm.systemBarInsets()
        val minY = ins.top
        val maxY = (b.height() - size - ins.bottom).coerceAtLeast(minY)
        return y.coerceIn(minY, maxY)
    }

    private fun positionFromPrefs(p: WindowManager.LayoutParams, size: Int) {
        val b = wm.screenBounds()
        p.x = if (prefs.bubbleOnRight) b.width() - size - margin() else margin()
        p.y = clampY((prefs.bubbleYFraction * b.height()).toInt(), size)
    }

    private fun update(v: View, p: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    private fun snapToEdge() {
        val v = view ?: return
        val p = lp ?: return
        val b = wm.screenBounds()
        val size = p.width
        val right = p.x + size / 2 > b.width() / 2
        val targetX = if (right) b.width() - size - margin() else margin()
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                p.x = it.animatedValue as Int
                update(v, p)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    prefs.bubbleOnRight = right
                    prefs.bubbleYFraction = p.y.toFloat() / b.height().coerceAtLeast(1)
                }
            })
            start()
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class BubbleView(ctx: Context) : View(ctx) {

        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var multi = false

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(235, 32, 33, 36)
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ctx.dp(2f)
        }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
        }
        private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(255, 224, 130)
        }
        private val sparkPath = Path()
        private val arcRect = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            border.shader = SweepGradient(
                w / 2f, h / 2f,
                intArrayOf(
                    0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(),
                    0xFF34A853.toInt(), 0xFF4285F4.toInt()
                ),
                null
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val cx = w / 2f
            val cy = height / 2f
            val radius = w / 2f - border.strokeWidth
            canvas.drawCircle(cx, cy, radius, bg)
            canvas.drawCircle(cx, cy, radius, border)

            // Lasso ring + handle + sparkle, centered optically.
            val gcx = cx - w * 0.04f
            val gcy = cy - w * 0.04f
            val r = w * 0.19f
            glyph.strokeWidth = w * 0.065f
            arcRect.set(gcx - r, gcy - r, gcx + r, gcy + r)
            canvas.drawArc(arcRect, 250f, 320f, false, glyph)
            val hx = gcx + r * 0.72f
            val hy = gcy + r * 0.72f
            canvas.drawLine(hx, hy, hx + w * 0.12f, hy + w * 0.12f, glyph)

            val s = r * 0.62f
            sparkPath.reset()
            sparkPath.moveTo(gcx, gcy - s)
            sparkPath.quadTo(gcx, gcy, gcx + s, gcy)
            sparkPath.quadTo(gcx, gcy, gcx, gcy + s)
            sparkPath.quadTo(gcx, gcy, gcx - s, gcy)
            sparkPath.quadTo(gcx, gcy, gcx, gcy - s)
            sparkPath.close()
            canvas.drawPath(sparkPath, spark)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = lp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    multi = false
                    animate().alpha(1f).scaleX(1.08f).scaleY(1.08f).setDuration(110).start()
                }
                MotionEvent.ACTION_POINTER_DOWN -> multi = true
                MotionEvent.ACTION_MOVE -> {
                    if (multi) return true
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        val b = wm.screenBounds()
                        p.x = (startX + dx).toInt().coerceIn(0, (b.width() - p.width).coerceAtLeast(0))
                        p.y = clampY((startY + dy).toInt(), p.height)
                        update(this, p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    animate().alpha(prefs.opacity).scaleX(1f).scaleY(1f).setDuration(160).start()
                    if (dragging) {
                        snapToEdge()
                    } else if (!multi) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onActivate()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    animate().alpha(prefs.opacity).scaleX(1f).scaleY(1f).setDuration(160).start()
                    if (dragging) snapToEdge()
                }
            }
            return true
        }
    }

    companion object {
        private const val FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
