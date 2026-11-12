package com.personal.circlesearch

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Detects a quick two-finger tap. Feed every MotionEvent of a view into [onTouchEvent].
 */
class TwoFingerTapDetector(context: Context, private val onTwoFingerTap: () -> Unit) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val timeoutMs = 400L

    private var tracking = false
    private var fired = false
    private var startTime = 0L
    private val startX = FloatArray(2)
    private val startY = FloatArray(2)

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = false
                fired = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    tracking = true
                    fired = false
                    startTime = ev.eventTime
                    for (i in 0..1) {
                        startX[i] = ev.getX(i)
                        startY[i] = ev.getY(i)
                    }
                } else {
                    tracking = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && ev.pointerCount == 2) {
                    for (i in 0..1) {
                        if (hypot(ev.getX(i) - startX[i], ev.getY(i) - startY[i]) > slop * 2) {
                            tracking = false
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (tracking && ev.pointerCount == 2) {
                    tracking = false
                    if (!fired && ev.eventTime - startTime <= timeoutMs) {
                        fired = true
                        onTwoFingerTap()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
    }
}
