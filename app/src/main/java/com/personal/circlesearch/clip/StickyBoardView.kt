package com.personal.circlesearch.clip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The "Canva" board: every clipboard card is a coloured sticky note you can drag, resize, pinch-zoom around,
 * and write over with the pencil. Notes, drawing, zoom/position and the active tool are all remembered.
 */
class StickyBoardView(ctx: Context, private val cb: Callbacks) : View(ctx) {

    interface Callbacks {
        /** Called on every tap: the tapped note, or null for empty canvas. */
        fun onSelect(x: Item?)
        fun onPaste(x: Item)
        /** Press-and-hold on a note: the window shows its action bar. */
        fun onLongPress(x: Item)
    }

    companion object {
        const val TOOL_MOVE = 0
        const val TOOL_PEN = 1
        const val TOOL_ERASE = 2
        /** Hand: one finger slides the canvas, cards never move (tap still selects). */
        const val TOOL_PAN = 3
        private const val BOARD_BG = 0xFFF3F5F9.toInt()
        private const val M_NONE = 0
        private const val M_PAN = 1
        private const val M_NOTE = 2
        private const val M_RESIZE = 3
        private const val M_PINCH = 4
        private const val M_INK = 5
    }

    var notes: List<Item> = emptyList()
    var selected: Item? = null
    val ink = BoardInk(this)

    var tool: Int = Store.prefs.getInt("btool", TOOL_MOVE)
        set(v) {
            field = v
            Store.prefs.edit().putInt("btool", v).apply()
            if (mode == M_INK) { ink.abort(); mode = M_NONE }
            invalidate()
        }

    /**
     * Reading mode: every note grows (and pushes the notes below it down) so its whole text is visible.
     * The stored positions are never touched, so switching it off brings the board back exactly as it was.
     * Cards cannot be moved and the pen is paused while it is on.
     */
    var reading: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            readDirty = true
            longHandled()
            if (mode == M_INK) { ink.abort(); mode = M_NONE }
            invalidate()
        }

    private val dens = resources.displayMetrics.density
    private var zoom = Store.prefs.getFloat("bz", 1f).coerceIn(0.12f, 6f)
    private var tx = if (Store.prefs.contains("bx")) Store.prefs.getFloat("bx", 0f) else 12f * dens
    private var ty = if (Store.prefs.contains("by")) Store.prefs.getFloat("by", 0f) else 12f * dens
    private val sc: Float get() = zoom * dens

    // paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCBD2DF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202124.toInt(); textSize = 13f }
    private val fold = Path()
    private val rect = RectF()
    private val layouts = HashMap<String, StaticLayout>()

    // touch state
    private var mode = M_NONE
    private var activeId = -1
    private var inkByPen = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var dragNote: Item? = null
    private var noteX0 = 0f
    private var noteY0 = 0f
    private var noteW0 = 0f
    private var noteH0 = 0f
    private var pressNote: Item? = null
    private val longRun = Runnable { fireLong() }
    private val readGeo = HashMap<String, FloatArray>()   // note id -> [top, height] while reading
    private var readDirty = true
    private val starPath = Path()
    private var lastTapId: String? = null
    private var lastTapAt = 0L
    private var pDist = 1f
    private var pZoom = 1f
    private var pWx = 0f
    private var pWy = 0f

    init {
        BoardStore.load()
        applyInk()
    }

    // ───────────── public api ─────────────

    fun applyInk() {
        ink.color = Store.prefs.getInt("bcolor", 0xFF1B1B1B.toInt())
        ink.sizeDp = Store.prefs.getFloat("bsize", 3f)
    }

    fun refresh() {
        val sel = selected
        if (sel != null && notes.none { it === sel }) { selected = null; cb.onSelect(null) }
        layouts.clear()
        readDirty = true
        invalidate()
    }

    fun undo() {
        if (ink.undo()) BoardStore.saveAsync()
    }

    fun saveView() {
        Store.prefs.edit().putFloat("bx", tx).putFloat("by", ty).putFloat("bz", zoom).apply()
        Store.saveLayout()
        BoardStore.flush()
    }

    fun release() {
        ink.abort()
        saveView()
    }

    /** Shows everything: all visible notes and the drawing. */
    fun fitAll() {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (n in notes) {
            if (n.sw <= 0f) continue
            l = min(l, n.sx); t = min(t, top(n)); r = max(r, n.sx + n.sw); b = max(b, top(n) + hgt(n))
        }
        if (!reading) for (s in BoardStore.strokes) { l = min(l, s.l); t = min(t, s.t); r = max(r, s.r); b = max(b, s.b) }
        if (l > r || width <= 0) {
            zoom = 1f; tx = 12f * dens; ty = 12f * dens
        } else {
            val bw = max(r - l, 40f)
            val bh = max(b - t, 40f)
            zoom = min((width - 24f * dens) / (bw * dens), (height - 24f * dens) / (bh * dens)).coerceIn(0.12f, 1.5f)
            tx = (width - bw * sc) / 2f - l * sc
            ty = (height - bh * sc) / 2f - t * sc
        }
        saveView()
        invalidate()
    }

    // ───────────── drawing ─────────────

    private fun noteColor(n: Item): Int =
        if (n.color != 0) n.color else PALETTE[abs(n.id.hashCode()) % PALETTE.size]

    override fun onDraw(c: Canvas) {
        val s = sc
        ink.tx = tx; ink.ty = ty; ink.s = s
        c.drawColor(BOARD_BG)
        val vx0 = -tx / s
        val vy0 = -ty / s
        val vx1 = (width - tx) / s
        val vy1 = (height - ty) / s

        // dotted canvas grid
        val step = 28f
        if (step * s >= 9f) {
            val cols = ((vx1 - vx0) / step).toInt() + 2
            val rows = ((vy1 - vy0) / step).toInt() + 2
            if (cols * rows < 6000) {
                dotPaint.strokeWidth = max(1.5f, 1.6f * dens)
                val arr = FloatArray(cols * rows * 2)
                var k = 0
                var gx = floor(vx0 / step) * step
                for (i in 0 until cols) {
                    var gy = floor(vy0 / step) * step
                    for (j in 0 until rows) {
                        arr[k++] = gx * s + tx
                        arr[k++] = gy * s + ty
                        gy += step
                    }
                    gx += step
                }
                c.drawPoints(arr, 0, k, dotPaint)
            }
        }

        c.save()
        c.translate(tx, ty)
        c.scale(s, s)
        val vis = RectF(vx0, vy0, vx1, vy1)
        val sel = selected
        for (n in notes) if (n !== sel) drawNote(c, n, vis, false)
        if (sel != null && notes.any { it === sel }) drawNote(c, sel, vis, true)
        if (!reading) ink.draw(c, vis)
        c.restore()
        if (!reading) ink.drawRing(c)
    }

    private fun drawNote(c: Canvas, n: Item, vis: RectF, isSel: Boolean) {
        if (n.sw <= 0f) return
        val x = n.sx; val y = top(n); val w = n.sw; val h = hgt(n)
        if (x + w < vis.left || x > vis.right || y + h < vis.top || y > vis.bottom) return
        val base = noteColor(n)
        // shadow
        fill.color = 0x2A000000
        rect.set(x + 1.5f, y + 3f, x + w + 1.5f, y + h + 3f)
        c.drawRoundRect(rect, 7f, 7f, fill)
        // paper
        fill.color = base
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, 7f, 7f, fill)
        // folded corner
        val fs = min(16f, min(w, h) / 3f)
        fill.color = darker(base)
        fold.reset()
        fold.moveTo(x + w, y + h - fs)
        fold.lineTo(x + w - fs, y + h)
        fold.lineTo(x + w, y + h)
        fold.close()
        c.drawPath(fold, fill)

        c.save()
        c.clipRect(x, y, x + w, y + h)
        val hasCaption = n.text.isNotBlank()
        if (n.image != null) {
            val capH = if (!hasCaption) 0f
            else if (reading) max(30f, fullLayout(n, w - 16f).height + 4f) else 30f
            val availW = w - 12f
            val availH = h - 12f - capH
            val key = ImgCache.key(n.id, 1000, 1600)
            val bmp = ImgCache.peek(key)
            if (bmp == null) {
                ImgCache.load(n.id, Store.imageFile(n), 1000, 1600) { invalidate() }
                fill.color = 0x22000000
                rect.set(x + 6f, y + 6f, x + 6f + availW, y + 6f + availH)
                c.drawRoundRect(rect, 4f, 4f, fill)
            } else {
                val k = min(availW / bmp.width, availH / bmp.height)
                val dw = bmp.width * k
                val dh = bmp.height * k
                rect.set(x + 6f + (availW - dw) / 2f, y + 6f, x + 6f + (availW - dw) / 2f + dw, y + 6f + dh)
                c.drawBitmap(bmp, null, rect, bmpPaint)
            }
            if (hasCaption) {
                val lay = if (reading) fullLayout(n, w - 16f) else layoutFor(n, n.text, w - 16f, 28f)
                c.save(); c.translate(x + 8f, y + h - capH); lay.draw(c); c.restore()
            }
        } else if (hasCaption) {
            val lay = if (reading) fullLayout(n, w - 16f) else layoutFor(n, n.text, w - 16f, h - 16f)
            c.save(); c.translate(x + 8f, y + 8f); lay.draw(c); c.restore()
        }
        c.restore()

        if (n.pinned) {
            fill.color = 0xFFE8590C.toInt()
            c.drawCircle(x + w - 9f, y + 9f, 4f, fill)
            fill.color = 0x66FFFFFF
            c.drawCircle(x + w - 10.2f, y + 7.8f, 1.4f, fill)
        }
        if (n.fav) {
            val sx = if (n.pinned) x + w - 25f else x + w - 12f
            fill.color = 0xB3FFFFFF.toInt()
            c.drawCircle(sx, y + 11f, 8.5f, fill)
            star(sx, y + 11.5f, 6.5f)
            fill.color = 0xFFF5A300.toInt()
            c.drawPath(starPath, fill)
        }
        if (isSel) {
            line.strokeWidth = 2.5f / zoom
            line.color = 0xFF3B5BFF.toInt()
            rect.set(x, y, x + w, y + h)
            c.drawRoundRect(rect, 7f, 7f, line)
            if (tool == TOOL_MOVE && !reading) {      // resize handle only while cards can be edited
                fill.color = 0xFFFFFFFF.toInt()
                c.drawCircle(x + w, y + h, 10f / zoom, fill)
                fill.color = 0xFF3B5BFF.toInt()
                c.drawCircle(x + w, y + h, 7f / zoom, fill)
            }
        }
    }

    private fun darker(c: Int): Int {
        val r = ((c shr 16) and 0xFF) * 8 / 10
        val g = ((c shr 8) and 0xFF) * 8 / 10
        val b = (c and 0xFF) * 8 / 10
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun star(cx: Float, cy: Float, r: Float) {
        starPath.reset()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.45f
            val a = -Math.PI / 2 + i * Math.PI / 5
            val px = cx + (rad * Math.cos(a)).toFloat()
            val py = cy + (rad * Math.sin(a)).toFloat()
            if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
    }

    // ───────────── reading-mode geometry ─────────────

    private fun top(n: Item): Float = if (reading) readRect(n)[0] else n.sy
    private fun hgt(n: Item): Float = if (reading) readRect(n)[1] else n.sh

    private fun readRect(n: Item): FloatArray {
        if (readDirty) computeReadGeo()
        return readGeo[n.id] ?: floatArrayOf(n.sy, n.sh)
    }

    /** Text laid out in full (no line limit), used by reading mode. */
    private fun fullLayout(n: Item, w: Float): StaticLayout {
        val key = n.id + "|full|" + n.text.hashCode() + "|" + w.toInt()
        layouts[key]?.let { return it }
        if (layouts.size > 400) layouts.clear()
        val lay = StaticLayout.Builder.obtain(n.text, 0, n.text.length, textPaint, max(20, w.toInt())).build()
        layouts[key] = lay
        return lay
    }

    /** Height a note needs to show all of its text (never smaller than its normal height). */
    private fun neededH(n: Item): Float {
        if (n.text.isBlank()) return n.sh
        return if (n.image != null) {
            n.sh - 30f + max(30f, fullLayout(n, n.sw - 16f).height + 4f)
        } else {
            max(n.sh, fullLayout(n, n.sw - 16f).height + 16f)
        }
    }

    /**
     * Grows every note to fit its text and pushes the notes underneath it down by the same amount,
     * column by column, so nothing overlaps. Positions are only computed for display; nothing is saved.
     */
    private fun computeReadGeo() {
        readDirty = false
        readGeo.clear()
        val order = notes.filter { it.sw > 0f }.sortedWith(compareBy<Item>({ it.sy }, { it.sx }))
        val shift = FloatArray(order.size)
        val grow = FloatArray(order.size)
        for (i in order.indices) {
            val n = order[i]
            var s = 0f
            for (j in 0 until i) {
                val m = order[j]
                if (m.sy + m.sh > n.sy + 6f) continue                       // m was not above n
                val overlap = min(m.sx + m.sw, n.sx + n.sw) - max(m.sx, n.sx)
                if (overlap > 4f) s = max(s, shift[j] + grow[j])
            }
            shift[i] = s
            grow[i] = max(0f, neededH(n) - n.sh)
            readGeo[n.id] = floatArrayOf(n.sy + s, n.sh + grow[i])
        }
    }

    private fun layoutFor(n: Item, text: String, w: Float, h: Float): StaticLayout {
        val key = n.id + "|" + text.hashCode() + "|" + w.toInt() + "|" + h.toInt()
        layouts[key]?.let { return it }
        if (layouts.size > 400) layouts.clear()
        val lines = max(1, (h / (textPaint.textSize * 1.22f)).toInt())
        val lay = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, max(20, w.toInt()))
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
        layouts[key] = lay
        return lay
    }

    // ───────────── touch ─────────────

    private fun wx(x: Float) = (x - tx) / sc
    private fun wy(y: Float) = (y - ty) / sc

    private fun noteAt(px: Float, py: Float): Item? {
        val x = wx(px); val y = wy(py)
        val sel = selected
        if (sel != null && notes.any { it === sel } && inside(sel, x, y)) return sel
        for (i in notes.indices.reversed()) if (inside(notes[i], x, y)) return notes[i]
        return null
    }

    private fun inside(n: Item, x: Float, y: Float) =
        n.sw > 0f && x >= n.sx && x <= n.sx + n.sw && y >= top(n) && y <= top(n) + hgt(n)

    private fun onHandle(n: Item, px: Float, py: Float): Boolean {
        val cx = (n.sx + n.sw) * sc + tx
        val cy = (top(n) + hgt(n)) * sc + ty
        return hypot(px - cx, py - cy) < 24f * dens
    }

    private fun startPinch(e: MotionEvent) {
        mode = M_PINCH
        val x0 = e.getX(0); val y0 = e.getY(0); val x1 = e.getX(1); val y1 = e.getY(1)
        pDist = max(10f, hypot(x1 - x0, y1 - y0))
        pZoom = zoom
        val mx = (x0 + x1) / 2f; val my = (y0 + y1) / 2f
        pWx = wx(mx); pWy = wy(my)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(longRun)
                pressNote = null
                activeId = e.getPointerId(0)
                val x = e.getX(0); val y = e.getY(0)
                downX = x; downY = y; lastX = x; lastY = y; moved = false
                dragNote = null
                val pen = PenKind.isPen(e, 0)
                val eraserEnd = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                // drawing is paused in reading mode; the pan tool and reading mode never move cards
                val drawing = !reading && (tool == TOOL_PEN || tool == TOOL_ERASE || eraserEnd)
                val canEdit = tool == TOOL_MOVE && !reading
                if (drawing) {
                    applyInk()
                    ink.tx = tx; ink.ty = ty; ink.s = sc
                    inkByPen = pen
                    mode = M_INK
                    val erase = tool == TOOL_ERASE || eraserEnd
                    ink.begin(if (erase) 1 else 0, x, y, e.getPressure(0).coerceIn(0f, 1f), e.eventTime)
                } else {
                    val sel = selected
                    if (canEdit && sel != null && notes.any { it === sel } && onHandle(sel, x, y)) {
                        mode = M_RESIZE; dragNote = sel
                        noteW0 = sel.sw; noteH0 = sel.sh
                    } else {
                        val n = noteAt(x, y)
                        pressNote = n
                        if (n != null && canEdit) {
                            mode = M_NOTE; dragNote = n
                            noteX0 = n.sx; noteY0 = n.sy
                        } else mode = M_PAN          // one finger slides the canvas, even when it starts on a card
                        if (n != null) postDelayed(longRun, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longRun)
                if (mode == M_INK && inkByPen) return true      // palm while the pen is writing: ignore
                if (mode == M_INK) ink.abort()
                if (mode == M_NOTE || mode == M_RESIZE) finishNoteDrag()
                if (e.pointerCount >= 2) startPinch(e)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                M_INK -> {
                    val i = e.findPointerIndex(activeId)
                    if (i >= 0) {
                        for (h in 0 until e.historySize) {
                            ink.move(e.getHistoricalX(i, h), e.getHistoricalY(i, h),
                                e.getHistoricalPressure(i, h).coerceIn(0f, 1f), e.getHistoricalEventTime(h))
                        }
                        ink.move(e.getX(i), e.getY(i), e.getPressure(i).coerceIn(0f, 1f), e.eventTime)
                    }
                }
                M_PINCH -> if (e.pointerCount >= 2) {
                    val x0 = e.getX(0); val y0 = e.getY(0); val x1 = e.getX(1); val y1 = e.getY(1)
                    val d = max(10f, hypot(x1 - x0, y1 - y0))
                    zoom = (pZoom * d / pDist).coerceIn(0.12f, 6f)
                    val s = sc
                    tx = (x0 + x1) / 2f - pWx * s
                    ty = (y0 + y1) / 2f - pWy * s
                    invalidate()
                }
                M_PAN, M_NOTE, M_RESIZE -> {
                    val i = e.findPointerIndex(activeId)
                    if (i >= 0) {
                        val x = e.getX(i); val y = e.getY(i)
                        if (!moved && hypot(x - downX, y - downY) > 8f * dens) { moved = true; removeCallbacks(longRun) }
                        if (moved) {
                            when (mode) {
                                M_PAN -> { tx += x - lastX; ty += y - lastY }
                                M_NOTE -> dragNote?.let {
                                    it.sx = noteX0 + (x - downX) / sc
                                    it.sy = noteY0 + (y - downY) / sc
                                }
                                M_RESIZE -> dragNote?.let {
                                    it.sw = max(70f, noteW0 + (x - downX) / sc)
                                    it.sh = max(60f, noteH0 + (y - downY) / sc)
                                }
                            }
                            invalidate()
                        }
                        lastX = x; lastY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == M_PINCH && e.pointerCount <= 2) { mode = M_NONE; saveView() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when (mode) {
                    M_INK -> {
                        val i = e.findPointerIndex(activeId).let { if (it >= 0) it else 0 }
                        if (up) ink.end(e.getX(i), e.getY(i)) else ink.abort()
                        BoardStore.saveAsync()
                    }
                    M_NOTE, M_RESIZE -> {
                        val n = dragNote
                        if (!moved && up && mode == M_NOTE && n != null) tapNote(n)
                        else finishNoteDrag()
                    }
                    M_PAN -> {
                        if (!moved && up) {
                            val n = pressNote
                            if (n != null) tapNote(n) else select(null)
                        } else saveView()
                    }
                    M_PINCH -> saveView()
                }
                mode = M_NONE
                dragNote = null
                pressNote = null
            }
        }
        return true
    }

    private fun longHandled() { removeCallbacks(longRun) }

    /** Press-and-hold on a card (finger did not move): select it and ask the window to show its action bar. */
    private fun fireLong() {
        val n = pressNote ?: return
        if (moved || (mode != M_NOTE && mode != M_PAN)) return
        mode = M_NONE
        dragNote = null
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        select(n)
        cb.onLongPress(n)
    }

    private fun finishNoteDrag() {
        val n = dragNote ?: return
        if (moved) {
            Store.bringToFront(n)
            Store.saveLayout()
        }
        dragNote = null
        mode = M_NONE
        invalidate()
    }

    private fun tapNote(n: Item) {
        val now = SystemClock.uptimeMillis()
        val dbl = lastTapId == n.id && now - lastTapAt < 380
        lastTapId = n.id
        lastTapAt = now
        select(n)
        if (dbl) cb.onPaste(n)
    }

    private fun select(n: Item?) {
        val changed = selected !== n
        selected = n
        if (changed || n != null) cb.onSelect(n)
        invalidate()
    }
}
