package com.personal.circlesearch.clip

import android.graphics.Picture
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Random

/** Pencil texture, baked once per point so a stroke always redraws identically. */
class BGrain(
    @JvmField val jx1: Float, @JvmField val jy1: Float,
    @JvmField val jx2: Float, @JvmField val jy2: Float,
    @JvmField val jx3: Float, @JvmField val jy3: Float,
    @JvmField val ga1: Float, @JvmField val ga2: Float, @JvmField val ga3: Float,
    @JvmField val sx: FloatArray, @JvmField val sy: FloatArray,
    @JvmField val sr: FloatArray, @JvmField val sa: FloatArray,
)

/** A pencil point in BOARD coordinates (dp at zoom 1). */
class BPt(@JvmField val x: Float, @JvmField val y: Float, @JvmField var w: Float, @JvmField val g: BGrain?)

class BStroke(@JvmField val color: Int, @JvmField val opacity: Float, @JvmField val baseW: Float) {
    @JvmField val pts = ArrayList<BPt>(64)
    @JvmField var l = Float.MAX_VALUE
    @JvmField var t = Float.MAX_VALUE
    @JvmField var r = -Float.MAX_VALUE
    @JvmField var b = -Float.MAX_VALUE
    @JvmField var pic: Picture? = null
    @JvmField var picL = 0f
    @JvmField var picT = 0f

    fun add(p: BPt) {
        pts.add(p)
        if (p.x < l) l = p.x
        if (p.x > r) r = p.x
        if (p.y < t) t = p.y
        if (p.y > b) b = p.y
    }
}

/** The pencil drawing that lies on top of the sticky notes. One shared list, saved to board.json. */
object BoardStore {
    val strokes = ArrayList<BStroke>()
    private var loaded = false
    private var pending = false
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val saveRun = Runnable { writeNow() }

    fun file() = File(Store.app.filesDir, "board.json")

    fun load() {
        if (loaded) return
        loaded = true
        try {
            val f = file()
            if (f.exists()) parseInto(f.readText(), strokes)
        } catch (e: Exception) {
        }
    }

    /** After a restore replaced board.json. */
    fun reload() {
        strokes.clear()
        loaded = false
        load()
    }

    fun saveAsync() {
        pending = true
        main.removeCallbacks(saveRun)
        main.postDelayed(saveRun, 500)
    }

    /** Writes any unsaved drawing right now (window closing / rebuilding). */
    fun flush() {
        if (!pending) return
        main.removeCallbacks(saveRun)
        writeNow()
    }

    private fun writeNow() {
        pending = false
        val js = toJson()
        Thread {
            synchronized(lock) {
                try {
                    val f = file()
                    val tmp = File(f.parentFile, "board.json.tmp")
                    tmp.writeText(js)
                    if (!tmp.renameTo(f)) { f.writeText(js); tmp.delete() }
                } catch (e: Exception) {
                }
            }
        }.start()
    }

    private fun f2(v: Float): String = (Math.round(v * 100f) / 100f).toString()

    fun toJson(): String {
        val sb = StringBuilder("[")
        var first = true
        for (s in strokes) {
            if (s.pts.isEmpty()) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"c\":").append(s.color).append(",\"o\":").append(f2(s.opacity))
                .append(",\"w\":").append(f2(s.baseW)).append(",\"p\":[")
            for (i in s.pts.indices) {
                val p = s.pts[i]
                if (i > 0) sb.append(',')
                sb.append(f2(p.x)).append(',').append(f2(p.y)).append(',').append(f2(p.w))
            }
            sb.append("]}")
        }
        return sb.append(']').toString()
    }

    fun parseInto(text: String, out: MutableList<BStroke>) {
        val rnd = Random(7)
        val a = JSONArray(text)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val st = BStroke(o.optInt("c", 0xFF1B1B1B.toInt()), o.optDouble("o", 1.0).toFloat(), o.optDouble("w", 2.0).toFloat())
            val p = o.optJSONArray("p") ?: continue
            var k = 0
            while (k + 2 < p.length()) {
                val w = p.getDouble(k + 2).toFloat()
                st.add(BPt(p.getDouble(k).toFloat(), p.getDouble(k + 1).toFloat(), w, makeGrain(w, rnd)))
                k += 3
            }
            if (st.pts.isNotEmpty()) out.add(st)
        }
    }

    /** Joins two board.json texts (used by "merge" restore). */
    fun mergeText(a: String?, b: String): String {
        return try {
            val arr = if (a.isNullOrBlank()) JSONArray() else JSONArray(a)
            val add = JSONArray(b)
            for (i in 0 until add.length()) arr.put(add.get(i))
            arr.toString()
        } catch (e: Exception) {
            b
        }
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

    fun makeGrain(w: Float, rnd: Random): BGrain {
        val two = 6.2831855f
        val mag = clamp(w * 0.24f + 0.22f, 0.24f, 1.15f)
        val a1 = rnd.nextFloat() * two
        val a2 = rnd.nextFloat() * two
        val a3 = rnd.nextFloat() * two
        val spread = clamp(w * 0.85f + 0.6f, 0.7f, 3.6f)
        val cnt = 3 + rnd.nextInt(4)
        val sx = FloatArray(cnt)
        val sy = FloatArray(cnt)
        val sr = FloatArray(cnt)
        val sa = FloatArray(cnt)
        for (i in 0 until cnt) {
            val ang = rnd.nextFloat() * two
            val dist = rnd.nextFloat() * spread
            sx[i] = kotlin.math.cos(ang) * dist
            sy[i] = kotlin.math.sin(ang) * dist
            sr[i] = 0.14f + rnd.nextFloat() * 0.32f
            sa[i] = 0.1f + rnd.nextFloat() * 0.32f
        }
        return BGrain(
            kotlin.math.cos(a1) * mag, kotlin.math.sin(a1) * mag,
            kotlin.math.cos(a2) * mag * 0.72f, kotlin.math.sin(a2) * mag * 0.72f,
            kotlin.math.cos(a3) * mag * 0.46f, kotlin.math.sin(a3) * mag * 0.46f,
            0.5f + rnd.nextFloat() * 0.5f, 0.35f + rnd.nextFloat() * 0.45f, 0.25f + rnd.nextFloat() * 0.4f,
            sx, sy, sr, sa,
        )
    }
}
