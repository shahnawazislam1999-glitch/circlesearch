package com.personal.circlesearch.clip

import android.util.SparseIntArray
import android.view.InputDevice
import android.view.MotionEvent

/** Tells a smart pen / S Pen from a finger (ported from the floating-pen project's PenDetect). */
object PenKind {
    private const val SRC_STYLUS = 0x00004000
    private const val SRC_BT_STYLUS = 0x00008000
    private const val PEN_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
    private val nameCache = SparseIntArray()
    private val penName = Regex("(^|[^a-z])(pen|stylus)([^a-z]|$)")

    fun isPen(e: MotionEvent, i: Int): Boolean {
        if ((e.buttonState and PEN_BUTTONS) != 0) return true
        return when (e.getToolType(i)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
            MotionEvent.TOOL_TYPE_MOUSE -> false
            MotionEvent.TOOL_TYPE_UNKNOWN -> (e.source and (SRC_STYLUS or SRC_BT_STYLUS)) != 0 || looksLikePen(e.deviceId)
            else -> looksLikePen(e.deviceId)
        }
    }

    private fun looksLikePen(id: Int): Boolean {
        val c = nameCache.get(id, 0)
        if (c != 0) return c == 1
        val dev = InputDevice.getDevice(id) ?: return false
        val pen = penName.containsMatchIn(dev.name.lowercase())
        nameCache.put(id, if (pen) 1 else 2)
        return pen
    }
}
