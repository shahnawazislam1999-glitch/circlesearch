package com.personal.circlesearch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * The edge handle cluster.
 *
 * The first handle is always the default one (Circle Search). Below it there is one extra,
 * colour-coded handle per app shortcut the user added. All handles are the same size, live in a
 * single overlay window, and move together: press, hold and drag any of them to move the whole
 * cluster along the left or right edge (it snaps to the nearest edge and remembers its position).
 *
 * Tap the default handle -> Circle Search. Tap the clipboard handle (when switched on) -> the PinClip clipboard
 * window. Tap an app handle -> that app opens in a floating window.
 */
class GestureStrip(
    private val context: Context,
    private val prefs: Prefs,
    private val onTap: () -> Unit,
    private val onShortcut: (AppShortcut) -> Unit,
    private val onClipboard: () -> Unit,
    private val onGpt: () -> Unit = {},
    private val onShot: () -> Unit = {}
) {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private var view: StripView? = null
    private var lp: WindowManager.LayoutParams? = null

    /** One handle: the default search handle has no shortcut. */
    private class Item(
        val color: Int,
        val shortcut: AppShortcut?,
        val isClipboard: Boolean = false,
        val isGpt: Boolean = false,
        val isShot: Boolean = false
    )

    fun show() {
        if (view != null) {
            relayout()
            return
        }
        val v = StripView(context)
        val p = WindowManager.LayoutParams(
            context.dpInt(HandlePainter.WIDTH_DP), context.dpInt(HEIGHT_PLACEHOLDER_DP),
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

    /** Re-reads the settings (handles, colours, height, opacity, side) and updates the window. */
    fun relayout() {
        val v = view ?: return
        val p = lp ?: return
        if (v.dragging) return
        place(v, p)
        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    private fun buildItems(): List<Item> {
        val list = ArrayList<Item>()
        list.add(Item(prefs.searchHandleColor, null))
        if (prefs.gptEnabled) list.add(Item(prefs.gptHandleColor, null, isGpt = true))
        if (prefs.shotEnabled) list.add(Item(prefs.shotHandleColor, null, isShot = true))
        if (prefs.clipboardEnabled) list.add(Item(prefs.clipHandleColor, null, true))
        for (s in prefs.shortcuts()) list.add(Item(s.color, s))
        return list
    }

    /** Wanted handle height, shrunk only if the whole cluster would not fit on the screen. */
    private fun handlePx(count: Int, screenHeight: Int): Float {
        val want = context.dp(prefs.handleHeightDp.toFloat())
        val room = screenHeight * 0.85f - 2f * HandlePainter.padPx(context) -
            (count - 1) * HandlePainter.gapPx(context)
        return minOf(want, room / count).coerceAtLeast(context.dp(16f))
    }

    private fun clampY(y: Int, height: Int): Int {
        val b = wm.screenBounds()
        return y.coerceIn(0, (b.height() - height).coerceAtLeast(0))
    }

    private fun place(v: StripView, p: WindowManager.LayoutParams) {
        val b = wm.screenBounds()
        val items = buildItems()
        val hPx = handlePx(items.size, b.height())
        val onRight = prefs.stripOnRight
        p.width = context.dpInt(HandlePainter.WIDTH_DP)
        p.height = HandlePainter.clusterHeightPx(context, items.size, hPx).toInt()
        p.x = if (onRight) b.width() - p.width else 0
        p.y = clampY((prefs.stripYFraction * b.height()).toInt(), p.height)
        v.bind(items, hPx, onRight)
    }

    @SuppressLint("ViewConstructor")
    private inner class StripView(ctx: Context) : View(ctx) {
        var dragging = false

        private var items: List<Item> = emptyList()
        private var colors = IntArray(0)
        private var handlePx = 0f
        private var onRight = false
        private var pressed = -1
        private var alphaBoost = false

        private val painter = HandlePainter(ctx)
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
                pressed = -1
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        fun bind(newItems: List<Item>, newHandlePx: Float, newOnRight: Boolean) {
            items = newItems
            colors = IntArray(newItems.size) { newItems[it].color }
            handlePx = newHandlePx
            onRight = newOnRight
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Keep the system back-gesture from stealing touches on the handles.
            setSystemGestureExclusionRects(listOf(Rect(0, 0, w, h)))
        }

        override fun onDraw(canvas: Canvas) {
            painter.draw(
                canvas, colors, handlePx, onRight, width.toFloat(),
                prefs.stripOpacity, pressed, alphaBoost
            )
        }

        /** Which handle is under local y. */
        private fun hit(y: Float): Int {
            if (items.isEmpty()) return -1
            val gap = HandlePainter.gapPx(context)
            val slot = handlePx + gap
            val idx = ((y - HandlePainter.padPx(context) + gap / 2f) / slot).toInt()
            return idx.coerceIn(0, items.size - 1)
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
                    pressed = hit(e.y)
                    invalidate()
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
                        pressed = -1
                        invalidate()
                    }
                    if (dragging) {
                        // The whole cluster is one window, so every handle moves together.
                        val b = wm.screenBounds()
                        p.x = (startWinX + dx).toInt().coerceIn(0, (b.width() - p.width).coerceAtLeast(0))
                        p.y = clampY((startWinY + dy).toInt(), p.height)
                        try { wm.updateViewLayout(this, p) } catch (_: Throwable) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    val tapped = pressed
                    pressed = -1
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
                    } else {
                        invalidate()
                        if (!moved && tapped in items.indices) {
                            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            val item = items[tapped]
                            val shortcut = item.shortcut
                            when {
                                shortcut != null -> onShortcut(shortcut)
                                item.isGpt -> onGpt()
                                item.isShot -> onShot()
                                item.isClipboard -> onClipboard()
                                else -> onTap()
                            }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    dragging = false
                    alphaBoost = false
                    pressed = -1
                    invalidate()
                }
            }
            return true
        }
    }

    companion object {
        /** Only used for the very first window size; place() sets the real one straight away. */
        private const val HEIGHT_PLACEHOLDER_DP = 72
    }
}
