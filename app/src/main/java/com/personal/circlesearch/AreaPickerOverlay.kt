package com.personal.circlesearch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Full-screen overlay over the frozen screenshot where the user drags out a rectangle (move it by
 * dragging inside, resize with the corner handles) and saves it as the search area.
 * Areas are reported as fractions of the screen so they survive different screenshot sizes.
 */
class AreaPickerOverlay(
    private val context: Context,
    private val screenshot: Bitmap,
    private val initial: RectF?,
    private val hasSaved: Boolean,
    private val onSave: (RectF) -> Unit,
    private val onRemove: () -> Unit,
    private val onCancel: () -> Unit
) {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private var view: PickerView? = null

    fun show() {
        val b = wm.screenBounds()
        val v = PickerView(context)
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
    private inner class PickerView(ctx: Context) : View(ctx) {

        private val density = resources.displayMetrics.density
        private val dst = Rect()
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = Color.WHITE
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = 0xFF4285F4.toInt()
        }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 20, 20, 24) }
        private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4285F4.toInt() }
        private val disabledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 80, 80, 90) }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14 * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        private val statusBar: Int = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else (24 * density).toInt()
        }

        private var area: RectF? = null
        private val backup = RectF()
        private var hadBackup = false

        private val cancelRect = RectF()
        private val removeRect = RectF()
        private val saveRect = RectF()
        private var pressedButton = 0 // 1 cancel, 2 remove, 3 save
        private var finished = false

        private val tmp = RectF()
        private val hintRect = RectF()

        // Gesture state
        private val MODE_NONE = 0
        private val MODE_DRAW = 1
        private val MODE_MOVE = 2
        private val MODE_RESIZE = 3
        private var mode = MODE_NONE
        private var anchorX = 0f
        private var anchorY = 0f
        private var lastX = 0f
        private var lastY = 0f

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        private fun dp(v: Float) = v * density

        private fun canSave(): Boolean {
            val a = area ?: return false
            return a.width() >= dp(40f) && a.height() >= dp(40f)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            dst.set(0, 0, w, h)
            val i = initial
            if (area == null && i != null && w > 0 && h > 0) {
                area = RectF(i.left * w, i.top * h, i.right * w, i.bottom * h)
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !finished) {
                    finished = true
                    onCancel()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        private fun layoutButtons() {
            val labels = ArrayList<String>()
            labels.add("Cancel")
            if (hasSaved) labels.add("Remove")
            labels.add("Save area")
            val padH = dp(20f)
            val gap = dp(10f)
            val h = dp(42f)
            val widths = labels.map { textPaint.measureText(it) + padH * 2 }
            val total = widths.sum() + gap * (labels.size - 1)
            var x = (width - total) / 2f
            val cy = height - dp(96f)
            for (i in labels.indices) {
                val r = when (labels[i]) {
                    "Cancel" -> cancelRect
                    "Remove" -> removeRect
                    else -> saveRect
                }
                r.set(x, cy - h / 2, x + widths[i], cy + h / 2)
                x += widths[i] + gap
            }
            if (!hasSaved) removeRect.setEmpty()
        }

        private fun drawButton(c: Canvas, r: RectF, text: String, paint: Paint) {
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            val fm = textPaint.fontMetrics
            c.drawText(text, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
        }

        override fun onDraw(canvas: Canvas) {
            if (screenshot.isRecycled) return
            canvas.drawBitmap(screenshot, null, dst, bmpPaint)
            canvas.drawRect(dst, dimPaint)

            val a = area
            if (a != null) {
                canvas.save()
                canvas.clipRect(a)
                canvas.drawBitmap(screenshot, null, dst, bmpPaint)
                canvas.restore()
                canvas.drawRect(a, borderPaint)
                val hr = dp(8f)
                for (i in 0..3) {
                    val (cx, cy) = corner(a, i)
                    canvas.drawCircle(cx, cy, hr, handleFill)
                    canvas.drawCircle(cx, cy, hr, handleRing)
                }
            }

            val hint = if (a == null) "Drag to select the area to search" else "Drag inside to move, corners to resize"
            val tw = textPaint.measureText(hint)
            val cy = statusBar + dp(40f)
            hintRect.set(width / 2f - tw / 2 - dp(18f), cy - dp(19f), width / 2f + tw / 2 + dp(18f), cy + dp(19f))
            canvas.drawRoundRect(hintRect, dp(19f), dp(19f), pillPaint)
            val fm = textPaint.fontMetrics
            canvas.drawText(hint, width / 2f, cy - (fm.ascent + fm.descent) / 2, textPaint)

            layoutButtons()
            drawButton(canvas, cancelRect, "Cancel", pillPaint)
            if (hasSaved) drawButton(canvas, removeRect, "Remove", pillPaint)
            drawButton(canvas, saveRect, "Save area", if (canSave()) primaryPaint else disabledPaint)
        }

        private fun corner(a: RectF, i: Int): Pair<Float, Float> = when (i) {
            0 -> Pair(a.left, a.top)
            1 -> Pair(a.right, a.top)
            2 -> Pair(a.right, a.bottom)
            else -> Pair(a.left, a.bottom)
        }

        private fun hitCorner(a: RectF, x: Float, y: Float): Int {
            val reach = dp(34f)
            var best = -1
            var bestD = Float.MAX_VALUE
            for (i in 0..3) {
                val (cx, cy) = corner(a, i)
                val d = abs(cx - x) + abs(cy - y)
                if (abs(cx - x) <= reach && abs(cy - y) <= reach && d < bestD) {
                    best = i
                    bestD = d
                }
            }
            return best
        }

        private fun setFromPoints(x1: Float, y1: Float, x2: Float, y2: Float) {
            val l = minOf(x1, x2).coerceIn(0f, width.toFloat())
            val r = maxOf(x1, x2).coerceIn(0f, width.toFloat())
            val t = minOf(y1, y2).coerceIn(0f, height.toFloat())
            val b = maxOf(y1, y2).coerceIn(0f, height.toFloat())
            val a = area ?: RectF().also { area = it }
            a.set(l, t, r, b)
        }

        private fun buttonAt(x: Float, y: Float): Int = when {
            cancelRect.contains(x, y) -> 1
            hasSaved && removeRect.contains(x, y) -> 2
            saveRect.contains(x, y) -> 3
            else -> 0
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (finished) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedButton = buttonAt(e.x, e.y)
                    if (pressedButton != 0) return true
                    val a = area
                    hadBackup = a != null
                    if (a != null) backup.set(a)
                    lastX = e.x
                    lastY = e.y
                    if (a != null) {
                        val c = hitCorner(a, e.x, e.y)
                        if (c >= 0) {
                            // Resize: the opposite corner stays fixed.
                            val (ox, oy) = corner(a, (c + 2) % 4)
                            anchorX = ox
                            anchorY = oy
                            mode = MODE_RESIZE
                            return true
                        }
                        if (a.contains(e.x, e.y)) {
                            mode = MODE_MOVE
                            return true
                        }
                    }
                    mode = MODE_DRAW
                    anchorX = e.x
                    anchorY = e.y
                    setFromPoints(e.x, e.y, e.x, e.y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressedButton != 0) return true
                    when (mode) {
                        MODE_DRAW, MODE_RESIZE -> {
                            setFromPoints(anchorX, anchorY, e.x, e.y)
                            invalidate()
                        }
                        MODE_MOVE -> {
                            val a = area ?: return true
                            var dx = e.x - lastX
                            var dy = e.y - lastY
                            dx = dx.coerceIn(-a.left, width - a.right)
                            dy = dy.coerceIn(-a.top, height - a.bottom)
                            a.offset(dx, dy)
                            lastX = e.x
                            lastY = e.y
                            invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (pressedButton != 0) {
                        val hit = buttonAt(e.x, e.y)
                        val pressed = pressedButton
                        pressedButton = 0
                        if (hit == pressed) fire(pressed)
                        return true
                    }
                    val a = area
                    if (a != null && (mode == MODE_DRAW || mode == MODE_RESIZE) &&
                        (a.width() < dp(40f) || a.height() < dp(40f))
                    ) {
                        // Too small (a stray tap): put the previous area back.
                        if (hadBackup) a.set(backup) else area = null
                    }
                    mode = MODE_NONE
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedButton = 0
                    if (hadBackup) area?.set(backup) else area = null
                    mode = MODE_NONE
                    invalidate()
                }
            }
            return true
        }

        private fun fire(button: Int) {
            when (button) {
                1 -> {
                    finished = true
                    onCancel()
                }
                2 -> {
                    finished = true
                    onRemove()
                }
                3 -> {
                    val a = area
                    if (a != null && canSave() && width > 0 && height > 0) {
                        finished = true
                        tmp.set(a.left / width, a.top / height, a.right / width, a.bottom / height)
                        onSave(RectF(tmp))
                    }
                }
            }
        }
    }
}
