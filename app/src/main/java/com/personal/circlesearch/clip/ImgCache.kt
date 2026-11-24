package com.personal.circlesearch.clip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/** Decodes card images at full quality (bounded) off the main thread and keeps them for reuse. */
object ImgCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val inflight = HashSet<String>()

    fun key(id: String, maxW: Int, maxH: Int) = "$id@$maxW@$maxH"

    fun peek(key: String): Bitmap? = cache.get(key)

    fun decode(f: File, maxW: Int, maxH: Int): Bitmap? {
        return try {
            val bo = BitmapFactory.Options()
            bo.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, bo)
            if (bo.outWidth <= 0) return null
            var s = 1
            while (bo.outWidth / s > maxW || bo.outHeight / s > maxH) s *= 2
            val o = BitmapFactory.Options()
            o.inSampleSize = s
            BitmapFactory.decodeFile(f.path, o)
        } catch (e: Throwable) {
            null
        }
    }

    /** Calls [done] on the main thread (immediately if cached). */
    fun load(id: String, f: File, maxW: Int, maxH: Int, done: (Bitmap?) -> Unit) {
        val k = key(id, maxW, maxH)
        val hit = cache.get(k)
        if (hit != null) { done(hit); return }
        val first = synchronized(inflight) { inflight.add(k) }
        if (!first) {
            // another request is already decoding it: poll shortly
            main.postDelayed({ load(id, f, maxW, maxH, done) }, 120)
            return
        }
        pool.execute {
            val b = decode(f, maxW, maxH)
            if (b != null) cache.put(k, b)
            synchronized(inflight) { inflight.remove(k) }
            main.post { done(b) }
        }
    }

    fun drop(id: String) {
        for (k in cache.snapshot().keys) if (k.startsWith("$id@")) cache.remove(k)
    }
}
