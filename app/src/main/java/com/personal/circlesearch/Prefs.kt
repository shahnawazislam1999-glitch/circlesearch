package com.personal.circlesearch

import android.content.Context
import android.content.SharedPreferences

/** Thin typed wrapper over SharedPreferences. Shared by the settings screen and the service. */
class Prefs private constructor(private val sp: SharedPreferences) {

    /** Master switch: when on, the service stays alive in the foreground; when off it goes idle. */
    var serviceActive: Boolean
        get() = sp.getBoolean(K_ACTIVE, true)
        set(value) { sp.edit().putBoolean(K_ACTIVE, value).apply() }

    var bubbleEnabled: Boolean
        get() = sp.getBoolean(K_BUBBLE, true)
        set(value) { sp.edit().putBoolean(K_BUBBLE, value).apply() }

    /** Bubble opacity, 0.10 .. 1.0 */
    var opacity: Float
        get() = sp.getFloat(K_OPACITY, 0.55f).coerceIn(0.10f, 1f)
        set(value) { sp.edit().putFloat(K_OPACITY, value.coerceIn(0.10f, 1f)).apply() }

    /** Bubble diameter in dp, 32 .. 72 */
    var bubbleSizeDp: Int
        get() = sp.getInt(K_SIZE, 46).coerceIn(32, 72)
        set(value) { sp.edit().putInt(K_SIZE, value.coerceIn(32, 72)).apply() }

    var gestureStrip: Boolean
        get() = sp.getBoolean(K_STRIP, true)
        set(value) { sp.edit().putBoolean(K_STRIP, value).apply() }

    var lensMode: Int
        get() = sp.getInt(K_MODE, LENS_APP)
        set(value) { sp.edit().putInt(K_MODE, value).apply() }

    var bubbleOnRight: Boolean
        get() = sp.getBoolean(K_RIGHT, true)
        set(value) { sp.edit().putBoolean(K_RIGHT, value).apply() }

    /** Vertical position as a fraction of the screen height. */
    var bubbleYFraction: Float
        get() = sp.getFloat(K_YFRAC, 0.35f).coerceIn(0f, 1f)
        set(value) { sp.edit().putFloat(K_YFRAC, value.coerceIn(0f, 1f)).apply() }

    /** Edge handle: which side it sits on and its vertical position (fraction of screen height). */
    var stripOnRight: Boolean
        get() = sp.getBoolean(K_STRIP_RIGHT, false)
        set(value) { sp.edit().putBoolean(K_STRIP_RIGHT, value).apply() }

    var stripYFraction: Float
        get() = sp.getFloat(K_STRIP_Y, 0.6f).coerceIn(0f, 1f)
        set(value) { sp.edit().putFloat(K_STRIP_Y, value.coerceIn(0f, 1f)).apply() }

    /** Edge handle opacity, 0.0 (invisible, still tappable) .. 1.0 */
    var stripOpacity: Float
        get() = sp.getFloat(K_STRIP_ALPHA, 0.6f).coerceIn(0f, 1f)
        set(value) { sp.edit().putFloat(K_STRIP_ALPHA, value.coerceIn(0f, 1f)).apply() }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val LENS_APP = 0
        const val WEB_WINDOW = 1

        private const val FILE = "circle_search_prefs"
        private const val K_ACTIVE = "service_active"
        private const val K_STRIP_RIGHT = "strip_on_right"
        private const val K_STRIP_Y = "strip_y_fraction"
        private const val K_STRIP_ALPHA = "strip_opacity"
        private const val K_BUBBLE = "bubble_enabled"
        private const val K_OPACITY = "bubble_opacity"
        private const val K_SIZE = "bubble_size_dp"
        private const val K_STRIP = "gesture_strip"
        private const val K_MODE = "lens_mode"
        private const val K_RIGHT = "bubble_on_right"
        private const val K_YFRAC = "bubble_y_fraction"

        fun get(context: Context): Prefs =
            Prefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
