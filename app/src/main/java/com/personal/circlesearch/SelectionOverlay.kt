package com.personal.circlesearch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Full-screen overlay that shows the frozen screenshot. The user draws a lasso (or taps) and
 * the selected region is cropped out and passed to [onSelected].
 */
class SelectionOverlay(
    private val context: Context,
    private val screenshot: Bitmap,
    private val onSelected: (Bitmap) -> Unit,
    private val onCancel: () -> Unit
) {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private var view: SelectionView? = null

    fun show() {
        val b = wm.screenBounds()
        val v = SelectionView(context)
        val p = WindowManager.LayoutParams(
            b.width(), b.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        v.alpha = 0f
        try {
            wm.addView(v, p)
            view = v
            v.animate().alpha(1f).setDuration(140).start()
            v.requestFocus()
        } catch (_: Throwable) {
            view = null
            onCancel()
        }
    }

    fun dismiss() {
        view?.let {
            try { wm.removeViewImmediate(it) } catch (_: Throwable) {}
        }
        view = null
        if (!screenshot.isRecycled) screenshot.recycle()
    }

    @SuppressLint("ViewConstructor")
    private inner class SelectionView(ctx: Context) : View(ctx) {

        private val density = resources.displayMetrics.density
        private val dst = Rect()
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint().apply { color = Color.argb(135, 0, 0, 0) }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10 * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(70, 255, 255, 255)
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4 * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 20, 24) }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14 * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val path = Path()
        private val pillRect = RectF()
        private val closeRect = RectF()

        private val statusBar: Int = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else (24 * density).toInt()
        }

        private var lastX = 0f
        private var lastY = 0f
        private var minX = 0f
        private var minY = 0f
        private var maxX = 0f
        private var maxY = 0f
        private var drawing = false
        private var closing = false
        private var searching = false
        private var done = false

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        private fun dp(v: Float) = v * density

        fun setSearching() {
            searching = true
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            dst.set(0, 0, w, h)
            strokePaint.shader = SweepGradient(
                w / 2f, h / 2f,
                intArrayOf(
                    0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(),
                    0xFF34A853.toInt(), 0xFF4285F4.toInt()
                ),
                null
            )
            val top = statusBar + dp(8f)
            closeRect.setEmpty() // close button removed; Back still cancels
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !done) {
                    done = true
                    onCancel()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            if (screenshot.isRecycled) return
            canvas.drawBitmap(screenshot, null, dst, bmpPaint)
            canvas.drawRect(dst, dimPaint)

            if (!path.isEmpty) {
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(screenshot, null, dst, bmpPaint)
                canvas.restore()
                canvas.drawPath(path, glowPaint)
                canvas.drawPath(path, strokePaint)
            }

            if (searching) {
                drawPill(canvas, "Searching with Lens…", width / 2f, height - dp(120f))
            }
        }

        private fun drawPill(c: Canvas, text: String, cx: Float, cy: Float) {
            val tw = textPaint.measureText(text)
            val padH = dp(18f)
            val h = dp(38f)
            pillRect.set(cx - tw / 2 - padH, cy - h / 2, cx + tw / 2 + padH, cy + h / 2)
            c.drawRoundRect(pillRect, h / 2, h / 2, pillPaint)
            val fm = textPaint.fontMetrics
            c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, textPaint)
        }

        private fun addPoint(x: Float, y: Float) {
            val mx = (x + lastX) / 2f
            val my = (y + lastY) / 2f
            path.quadTo(lastX, lastY, mx, my)
            lastX = x
            lastY = y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (searching || done) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (closeRect.contains(e.x, e.y)) {
                        closing = true
                        return true
                    }
                    closing = false
                    path.reset()
                    path.moveTo(e.x, e.y)
                    lastX = e.x
                    lastY = e.y
                    minX = e.x
                    maxX = e.x
                    minY = e.y
                    maxY = e.y
                    drawing = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (drawing) {
                        for (i in 0 until e.historySize) {
                            addPoint(e.getHistoricalX(i), e.getHistoricalY(i))
                        }
                        addPoint(e.x, e.y)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (closing) {
                        closing = false
                        if (closeRect.contains(e.x, e.y)) {
                            done = true
                            onCancel()
                        }
                        return true
                    }
                    if (drawing) {
                        addPoint(e.x, e.y)
                        drawing = false
                        finishSelection(e.x, e.y)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    drawing = false
                    closing = false
                    path.reset()
                    invalidate()
                }
            }
            return true
        }

        private fun finishSelection(upX: Float, upY: Float) {
            val vw = width.toFloat()
            val vh = height.toFloat()
            if (vw <= 0f || vh <= 0f) return
            val spanW = maxX - minX
            val spanH = maxY - minY
            val rect = RectF()
            if (spanW < dp(28f) && spanH < dp(28f)) {
                val half = dp(100f)
                rect.set(upX - half, upY - half, upX + half, upY + half)
            } else {
                val pad = dp(10f)
                rect.set(minX - pad, minY - pad, maxX + pad, maxY + pad)
                val minSide = dp(48f)
                if (rect.width() < minSide) rect.inset(-(minSide - rect.width()) / 2f, 0f)
                if (rect.height() < minSide) rect.inset(0f, -(minSide - rect.height()) / 2f)
            }
            // Clamp into the view, shifting when possible.
            val w = minOf(rect.width(), vw)
            val h = minOf(rect.height(), vh)
            val left = rect.left.coerceIn(0f, vw - w)
            val top = rect.top.coerceIn(0f, vh - h)
            rect.set(left, top, left + w, top + h)

            val sx = screenshot.width / vw
            val sy = screenshot.height / vh
            val l = (rect.left * sx).toInt().coerceIn(0, screenshot.width - 1)
            val t = (rect.top * sy).toInt().coerceIn(0, screenshot.height - 1)
            val r = (rect.right * sx).toInt().coerceIn(l + 1, screenshot.width)
            val b = (rect.bottom * sy).toInt().coerceIn(t + 1, screenshot.height)

            val crop: Bitmap? = try {
                val c = Bitmap.createBitmap(screenshot, l, t, r - l, b - t)
                if (c === screenshot) screenshot.copy(Bitmap.Config.ARGB_8888, false) else c
            } catch (_: Throwable) {
                null
            }
            done = true
            if (crop == null) {
                onCancel()
            } else {
                setSearching()
                onSelected(crop)
            }
        }
    }
}
