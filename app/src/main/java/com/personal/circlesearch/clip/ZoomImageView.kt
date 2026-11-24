package com.personal.circlesearch.clip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File

/**
 * Full-quality image viewer: pinch to zoom, drag to move, double-tap toggles fit <-> 100% (actual pixels).
 * "scale" is screen pixels per ORIGINAL image pixel, so 1.0 really is actual size.
 */
class ZoomImageView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var ow = 0
    private var oh = 0
    private var rx = 1f          // original px per decoded px
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var ready = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val main = Handler(Looper.getMainLooper())

    private fun fitScale(): Float {
        if (ow <= 0 || width <= 0) return 1f
        return minOf(width.toFloat() / ow, height.toFloat() / oh)
    }

    fun load(f: File) {
        Thread {
            val bo = BitmapFactory.Options()
            bo.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, bo)
            val b = ImgCache.decode(f, 3200, 5200)
            main.post {
                if (b != null && bo.outWidth > 0) {
                    bmp = b
                    ow = bo.outWidth
                    oh = bo.outHeight
                    rx = ow.toFloat() / b.width
                    ready = false
                    layoutInit()
                    invalidate()
                }
            }
        }.start()
    }

    private fun layoutInit() {
        if (ready || width <= 0 || ow <= 0) return
        ready = true
        fit()
    }

    fun fit() {
        scale = fitScale()
        tx = (width - ow * scale) / 2f
        ty = (height - oh * scale) / 2f
        invalidate()
    }

    fun actual() {
        zoomTo(1f, width / 2f, height / 2f)
    }

    private fun zoomTo(ns0: Float, fx: Float, fy: Float) {
        val ns = ns0.coerceIn(fitScale() * 0.95f, maxOf(10f, fitScale() * 4f))
        tx = fx - (fx - tx) * (ns / scale)
        ty = fy - (fy - ty) * (ns / scale)
        scale = ns
        clampPos()
        invalidate()
    }

    private fun clampPos() {
        val iw = ow * scale
        val ih = oh * scale
        tx = if (iw <= width) (width - iw) / 2f else tx.coerceIn(width - iw, 0f)
        ty = if (ih <= height) (height - ih) / 2f else ty.coerceIn(height - ih, 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow0: Int, oh0: Int) {
        super.onSizeChanged(w, h, ow0, oh0)
        if (!ready) layoutInit() else { clampPos() }
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        c.save()
        c.translate(tx, ty)
        c.scale(scale * rx, scale * rx)
        c.drawBitmap(b, 0f, 0f, paint)
        c.restore()
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomTo(scale * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaler.isInProgress) return false
            tx -= dx
            ty -= dy
            clampPos()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > fitScale() * 1.2f) fit() else zoomTo(1f.coerceAtLeast(fitScale() * 2f), e.x, e.y)
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }
}
