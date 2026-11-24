package com.personal.circlesearch.clip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.view.View
import java.util.Random
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The pencil from the floating-pen project (same stabiliser, pressure + speed width, tapers and graphite grain),
 * writing on the sticky-note board. Input is smoothed in SCREEN pixels so it feels identical at any zoom;
 * points are stored in BOARD coordinates so the drawing zooms and moves together with the notes.
 */
class BoardInk(private val view: View) {
    /** board -> screen:  sx = wx * s + tx */
    var tx = 0f
    var ty = 0f
    var s = 1f
    var color = 0xFF1B1B1B.toInt()
    var sizeDp = 3f
    var eraserDp = 14f

    private val dens = view.resources.displayMetrics.density
    private val strokes get() = BoardStore.strokes

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tmp = Path()
    private val rnd = Random()

    private var cur: BStroke? = null
    private var stabX = 0f
    private var stabY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastT = 0L
    private var curW = 1f
    private var radius = 6f
    private var baseS = 1f          // base width in screen px for the stroke being written
    private var gTool = -1
    private var ex = 0f
    private var ey = 0f

    val busy: Boolean get() = gTool >= 0

    private fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

    // ───────────── gesture ─────────────

    /** tool: 0 = write, 1 = erase (whole stroke). Returns true if the board content changed. */
    fun begin(tool: Int, x: Float, y: Float, p: Float, t: Long) {
        abort()
        gTool = tool
        if (tool == 0) beginStroke(x, y, p, t) else {
            ex = x; ey = y
            eraseAt(x, y)
        }
    }

    fun move(x: Float, y: Float, p: Float, t: Long) {
        if (gTool == 0) sample(x, y, p, t) else if (gTool == 1) eraseMove(x, y)
    }

    fun end(x: Float, y: Float) {
        if (gTool == 0) endWrite(x, y)
        gTool = -1
        view.invalidate()
    }

    /** Throws away the stroke in progress without saving it. */
    fun abort() {
        cur = null
        gTool = -1
        view.invalidate()
    }

    // ───────────── writing (pencil profile) ─────────────

    private fun beginStroke(x: Float, y: Float, p: Float, t: Long) {
        baseS = sizeDp * dens * BASE_SCALE
        val pr = if (p > 0f) p else 0.5f
        val iw = max(0.5f * dens, baseS * (P_BASE + pr * P_MUL))
        val w0 = startTaper(iw, 0)
        radius = clamp(baseS * R_MUL, R_MIN * dens, R_MAX * dens)
        stabX = x; stabY = y
        lastRawX = x; lastRawY = y
        lastT = t
        curW = w0
        val st = BStroke(color or 0xFF000000.toInt(), 1f, baseS / s)
        cur = st
        addPoint(st, x, y, w0)
        view.invalidate()
    }

    private fun startTaper(w: Float, idx: Int): Float {
        if (idx >= TAPER_N) return w
        return w * (TAPER_FLOOR + (1f - TAPER_FLOOR) * (idx.toFloat() / TAPER_N))
    }

    private fun addPoint(st: BStroke, sx: Float, sy: Float, wScreen: Float) {
        val ww = wScreen / s
        st.add(BPt((sx - tx) / s, (sy - ty) / s, ww, BoardStore.makeGrain(ww, rnd)))
    }

    private fun sample(x: Float, y: Float, p: Float, t: Long) {
        val st = cur ?: return
        val dx = x - stabX
        val dy = y - stabY
        val dist = hypot(dx, dy)
        if (dist < MIN_MOVE * dens) return
        val pull = min(1f, dist / radius) * STAB
        stabX += dx * pull
        stabY += dy * pull
        val dt = max(1L, t - lastT).toFloat()
        val speed = hypot(x - lastRawX, y - lastRawY) / dens / dt
        val pr = if (p > 0f) p else 0.5f
        val pf = P_BASE + pr * P_MUL
        val sf = clamp(SP_CEIL - speed * 0.5f, SP_FLOOR, SP_CEIL)
        val target = max(0.5f * dens, baseS * pf * sf)
        curW += (target - curW) * W_SMOOTH
        val w = startTaper(curW, st.pts.size)
        lastT = t
        lastRawX = x; lastRawY = y
        addPoint(st, stabX, stabY, w)
        view.invalidate()
    }

    private fun endWrite(x: Float, y: Float) {
        val st = cur ?: return
        val n = st.pts.size
        if (n == 1) {
            val p0 = st.pts[0]
            val px = p0.x * s + tx
            val py = p0.y * s + ty
            if (hypot(x - px, y - py) < 3f * dens && p0.w < st.baseW) p0.w = st.baseW   // a tap = full-size dot
        }
        if (n >= 1) {
            val last = st.pts[n - 1]
            val d = hypot(x - (last.x * s + tx), y - (last.y * s + ty))
            if (d > 0.3f * dens && d < 40f * dens) addPoint(st, x, y, last.w * s)
        }
        val m = min(END_N, st.pts.size)
        if (m >= 2) {
            for (k in 0 until m) {
                val frac = k.toFloat() / (m - 1)
                val mult = END_FLOOR + (1f - END_FLOOR) * frac
                val pt = st.pts[st.pts.size - 1 - k]
                pt.w *= mult
            }
        }
        strokes.add(st)
        cur = null
    }

    // ───────────── eraser (whole stroke) ─────────────

    private fun eraseMove(x: Float, y: Float) {
        val r = eraserDp * dens
        val dist = hypot(x - ex, y - ey)
        val steps = max(1, ceil(dist / (r * 0.5f)).toInt())
        for (k in 1..steps) {
            val f = k.toFloat() / steps
            eraseAt(ex + (x - ex) * f, ey + (y - ey) * f)
        }
        ex = x; ey = y
        view.invalidate()
    }

    private fun eraseAt(sx: Float, sy: Float) {
        val px = (sx - tx) / s
        val py = (sy - ty) / s
        val r = eraserDp * dens / s
        var i = strokes.size - 1
        var removed = false
        while (i >= 0) {
            if (hit(strokes[i], px, py, r)) { strokes.removeAt(i); removed = true }
            i--
        }
        if (removed) view.invalidate()
    }

    private fun hit(st: BStroke, px: Float, py: Float, r: Float): Boolean {
        val m = r + st.baseW * 1.5f
        if (px < st.l - m || px > st.r + m || py < st.t - m || py > st.b + m) return false
        val pts = st.pts
        if (pts.size == 1) {
            val p = pts[0]
            val rr = r + p.w / 2f
            val dx = px - p.x; val dy = py - p.y
            return dx * dx + dy * dy <= rr * rr
        }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val rr = r + max(a.w, b.w) / 2f
            if (distSegSq(px, py, a.x, a.y, b.x, b.y) <= rr * rr) return true
        }
        return false
    }

    private fun distSegSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        var t = if (l2 < 1e-6f) 0f else ((px - ax) * dx + (py - ay) * dy) / l2
        t = clamp(t, 0f, 1f)
        val cx = ax + t * dx - px; val cy = ay + t * dy - py
        return cx * cx + cy * cy
    }

    fun undo(): Boolean {
        abort()
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.size - 1)
        view.invalidate()
        return true
    }

    // ───────────── rendering (board coordinates) ─────────────

    /** Call inside the board transform. [vis] = visible board rectangle. */
    fun draw(c: Canvas, vis: RectF) {
        for (st in strokes) {
            val pad = st.baseW * 2f + 6f
            if (st.r + pad < vis.left || st.l - pad > vis.right || st.b + pad < vis.top || st.t - pad > vis.bottom) continue
            drawCached(c, st)
        }
        cur?.let { if (it.pts.isNotEmpty()) drawStroke(c, it) }
    }

    /** Eraser circle, in SCREEN coordinates. */
    fun drawRing(c: Canvas) {
        if (gTool != 1) return
        val r = eraserDp * dens
        ring.strokeWidth = 3f * dens
        ring.color = 0x66000000
        c.drawCircle(ex, ey, r, ring)
        ring.strokeWidth = 1.5f * dens
        ring.color = 0xFFFFFFFF.toInt()
        c.drawCircle(ex, ey, r, ring)
    }

    private fun drawCached(c: Canvas, st: BStroke) {
        var p = st.pic
        if (p == null) {
            val pad = st.baseW * 2f + 6f
            st.picL = st.l - pad
            st.picT = st.t - pad
            val pw = ceil(st.r - st.l + 2 * pad).toInt().coerceAtLeast(1)
            val ph = ceil(st.b - st.t + 2 * pad).toInt().coerceAtLeast(1)
            p = Picture()
            val rc = p.beginRecording(pw, ph)
            rc.translate(-st.picL, -st.picT)
            drawStroke(rc, st)
            p.endRecording()
            st.pic = p
        }
        c.save()
        c.translate(st.picL, st.picT)
        c.drawPicture(p)
        c.restore()
    }

    private fun col(color: Int, a: Float): Int {
        val ai = (clamp(a, 0f, 1f) * 255f + 0.5f).toInt()
        return (color and 0x00FFFFFF) or (ai shl 24)
    }

    private fun drawStroke(c: Canvas, st: BStroke) {
        val n = st.pts.size
        if (n == 0) return
        if (n == 1) { drawDot(c, st, st.pts[0]); return }
        for (i in 1 until n) seg(c, st, i)
        tail(c, st, st.pts[n - 2], st.pts[n - 1])
    }

    private fun drawDot(c: Canvas, st: BStroke, p: BPt) {
        val r = max(0.4f, p.w / 2f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = col(st.color, 0.62f)
        c.drawCircle(p.x, p.y, r * 0.78f, fillPaint)
        val g = p.g
        if (g != null) {
            fillPaint.color = col(st.color, 0.28f)
            c.drawCircle(p.x + g.jx1, p.y + g.jy1, r * 0.55f, fillPaint)
            for (k in g.sx.indices) {
                fillPaint.color = col(st.color, g.sa[k])
                c.drawCircle(p.x + g.sx[k], p.y + g.sy[k], g.sr[k], fillPaint)
            }
        }
    }

    private fun seg(c: Canvas, st: BStroke, i: Int) {
        val p1 = st.pts[i - 1]
        val p2 = st.pts[i]
        val pmx: Float
        val pmy: Float
        if (i >= 2) {
            val p0 = st.pts[i - 2]
            pmx = (p0.x + p1.x) / 2f; pmy = (p0.y + p1.y) / 2f
        } else { pmx = p1.x; pmy = p1.y }
        val nmx = (p1.x + p2.x) / 2f
        val nmy = (p1.y + p2.y) / 2f
        tmp.reset()
        tmp.moveTo(pmx, pmy)
        tmp.quadTo(p1.x, p1.y, nmx, nmy)
        paint.style = Paint.Style.STROKE
        pencilSeg(c, st, p2, max(0.5f, (p1.w + p2.w) / 2f))
    }

    private fun pass(c: Canvas, st: BStroke, jx: Float, jy: Float, a: Float, w: Float) {
        paint.color = col(st.color, a)
        paint.strokeWidth = w
        c.save()
        c.translate(jx, jy)
        c.drawPath(tmp, paint)
        c.restore()
    }

    /** Graphite: firm core + three thin jittered passes + loose speckles. */
    private fun pencilSeg(c: Canvas, st: BStroke, p2: BPt, w: Float) {
        pass(c, st, 0f, 0f, 0.6f, max(0.45f, w * 0.62f))
        val g = p2.g ?: return
        pass(c, st, g.jx1, g.jy1, 0.3f * g.ga1, max(0.35f, w * 0.48f))
        pass(c, st, -g.jx2, -g.jy2, 0.22f * g.ga2, max(0.3f, w * 0.36f))
        pass(c, st, g.jx3, -g.jy3, 0.16f * g.ga3, max(0.25f, w * 0.26f))
        fillPaint.style = Paint.Style.FILL
        for (k in g.sx.indices) {
            fillPaint.color = col(st.color, g.sa[k])
            c.drawCircle(p2.x + g.sx[k], p2.y + g.sy[k], g.sr[k], fillPaint)
        }
    }

    private fun tail(c: Canvas, st: BStroke, p1: BPt, p2: BPt) {
        tmp.reset()
        tmp.moveTo((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
        tmp.lineTo(p2.x, p2.y)
        paint.style = Paint.Style.STROKE
        paint.color = col(st.color, 0.6f)
        paint.strokeWidth = max(0.5f, p2.w) * 0.62f
        c.drawPath(tmp, paint)
    }

    companion object {
        private const val MIN_MOVE = 0.12f
        // pencil profile (identical to the floating pen's P_PENCIL)
        private const val STAB = 0.64f
        private const val R_MUL = 0.9f
        private const val R_MIN = 2.5f
        private const val R_MAX = 6.5f
        private const val TAPER_N = 3
        private const val TAPER_FLOOR = 0.42f
        private const val P_BASE = 0.58f
        private const val P_MUL = 0.48f
        private const val SP_FLOOR = 0.78f
        private const val SP_CEIL = 1.0f
        private const val W_SMOOTH = 0.44f
        private const val END_N = 4
        private const val END_FLOOR = 0.28f
        private const val BASE_SCALE = 0.7f
    }
}
