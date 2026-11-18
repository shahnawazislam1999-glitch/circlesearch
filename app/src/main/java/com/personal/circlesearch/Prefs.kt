package com.personal.circlesearch

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

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

    /** PROMPT_SEARCH: text sent together with the picture on every search. */
    var promptText: String
        get() = sp.getString(K_PROMPT_TEXT, "") ?: ""
        set(value) { sp.edit().putString(K_PROMPT_TEXT, value).apply() }

    /** PROMPT_SEARCH: when off, only the picture is searched (the saved text is kept). */
    var promptEnabled: Boolean
        get() = sp.getBoolean(K_PROMPT_ON, true)
        set(value) { sp.edit().putBoolean(K_PROMPT_ON, value).apply() }

    /** PROMPT_SEARCH: the text to send with this search, or null for a picture-only search. */
    fun activePrompt(): String? =
        if (promptEnabled) promptText.trim().takeIf { it.isNotEmpty() } else null

    // ------------------------------------------------------------------ ChatGPT handle

    /** GPT_SEARCH: when on, a ChatGPT edge handle joins the cluster (right under the search handle). */
    var gptEnabled: Boolean
        get() = sp.getBoolean(K_GPT_ON, false)
        set(value) { sp.edit().putBoolean(K_GPT_ON, value).apply() }

    var gptHandleColor: Int
        get() = sp.getInt(K_GPT_COLOR, DEFAULT_GPT_COLOR) or AppShortcut.OPAQUE
        set(value) { sp.edit().putInt(K_GPT_COLOR, value or AppShortcut.OPAQUE).apply() }

    /** GPT_SEARCH: text sent together with the picture to the ChatGPT app. */
    var gptPromptText: String
        get() = sp.getString(K_GPT_PROMPT, "") ?: ""
        set(value) { sp.edit().putString(K_GPT_PROMPT, value).apply() }

    var gptPromptEnabled: Boolean
        get() = sp.getBoolean(K_GPT_PROMPT_ON, true)
        set(value) { sp.edit().putBoolean(K_GPT_PROMPT_ON, value).apply() }

    /** GPT_SEARCH: press ChatGPT's send button for you once the image and prompt are in. */
    var gptAutoSend: Boolean
        get() = sp.getBoolean(K_GPT_SEND, true)
        set(value) { sp.edit().putBoolean(K_GPT_SEND, value).apply() }

    fun activeGptPrompt(): String? =
        if (gptPromptEnabled) gptPromptText.trim().takeIf { it.isNotEmpty() } else null

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
        get() = sp.getFloat(K_STRIP_ALPHA, 0.85f).coerceIn(0f, 1f)
        set(value) { sp.edit().putFloat(K_STRIP_ALPHA, value.coerceIn(0f, 1f)).apply() }

    /** Height of every edge handle in dp (all handles share the same size). */
    var handleHeightDp: Int
        get() = sp.getInt(K_HANDLE_H, DEFAULT_HANDLE_DP).coerceIn(MIN_HANDLE_DP, MAX_HANDLE_DP)
        set(value) { sp.edit().putInt(K_HANDLE_H, value.coerceIn(MIN_HANDLE_DP, MAX_HANDLE_DP)).apply() }

    /** Clipboard (PinClip) window: when on, a clipboard handle joins the cluster, right under the search handle. */
    var clipboardEnabled: Boolean
        get() = sp.getBoolean(K_CLIP_ON, false)
        set(value) { sp.edit().putBoolean(K_CLIP_ON, value).apply() }

    var clipHandleColor: Int
        get() = sp.getInt(K_CLIP_COLOR, DEFAULT_CLIP_COLOR) or AppShortcut.OPAQUE
        set(value) { sp.edit().putInt(K_CLIP_COLOR, value or AppShortcut.OPAQUE).apply() }

    /** Colour of the default (search) handle. */
    var searchHandleColor: Int
        get() = sp.getInt(K_SEARCH_COLOR, DEFAULT_SEARCH_COLOR) or AppShortcut.OPAQUE
        set(value) { sp.edit().putInt(K_SEARCH_COLOR, value or AppShortcut.OPAQUE).apply() }

    /** App shortcuts, in the order they appear on the edge (below the default search handle). */
    fun shortcuts(): List<AppShortcut> {
        val raw = sp.getString(K_SHORTCUTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<AppShortcut>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    AppShortcut(
                        o.getString("id"), o.getString("pkg"), o.getString("label"),
                        o.getInt("color") or AppShortcut.OPAQUE
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveShortcuts(list: List<AppShortcut>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("pkg", s.packageName)
                    .put("label", s.label)
                    .put("color", s.color)
            )
        }
        sp.edit().putString(K_SHORTCUTS, arr.toString()).apply()
    }

    /** Returns false when the list is full or the app is already there. */
    fun addShortcut(s: AppShortcut): Boolean {
        val cur = shortcuts()
        if (cur.size >= MAX_SHORTCUTS || cur.any { it.packageName == s.packageName }) return false
        saveShortcuts(cur + s)
        return true
    }

    fun insertShortcut(index: Int, s: AppShortcut) {
        val cur = shortcuts().toMutableList()
        if (cur.size >= MAX_SHORTCUTS || cur.any { it.packageName == s.packageName }) return
        cur.add(index.coerceIn(0, cur.size), s)
        saveShortcuts(cur)
    }

    fun removeShortcut(id: String) {
        val cur = shortcuts()
        val next = cur.filter { it.id != id }
        if (next.size != cur.size) saveShortcuts(next)
    }

    fun setShortcutColor(id: String, color: Int) {
        saveShortcuts(shortcuts().map { if (it.id == id) it.copy(color = color or AppShortcut.OPAQUE) else it })
    }

    /** Drops shortcuts whose app was uninstalled, so their handle disappears on its own. */
    fun pruneUninstalled(pm: PackageManager): Boolean {
        val cur = shortcuts()
        val keep = cur.filter {
            try { pm.getLaunchIntentForPackage(it.packageName) != null } catch (_: Throwable) { true }
        }
        if (keep.size == cur.size) return false
        saveShortcuts(keep)
        return true
    }

    /**
     * Saved search area (fractions of the screen, 0..1). When set, a tap on the edge handle searches
     * this area directly instead of opening the lasso. Orientation is remembered so a portrait area
     * is never applied to a landscape screen.
     *
     * Every function takes [gpt]: false = the Circle Search (Lens) area, true = the separate area of
     * the ChatGPT handle.
     */
    val hasSavedArea: Boolean
        get() = hasArea(false)

    val areaLandscape: Boolean
        get() = areaLandscape(false)

    private fun ak(gpt: Boolean, key: String) = if (gpt) "gpt_$key" else key

    fun hasArea(gpt: Boolean): Boolean = sp.getBoolean(ak(gpt, K_AREA_SET), false)

    fun areaLandscape(gpt: Boolean): Boolean = sp.getBoolean(ak(gpt, K_AREA_LAND), false)

    fun savedArea(gpt: Boolean = false): RectF? {
        if (!hasArea(gpt)) return null
        val r = RectF(
            sp.getFloat(ak(gpt, K_AREA_L), 0f), sp.getFloat(ak(gpt, K_AREA_T), 0f),
            sp.getFloat(ak(gpt, K_AREA_R), 0f), sp.getFloat(ak(gpt, K_AREA_B), 0f)
        )
        return if (r.right > r.left && r.bottom > r.top) r else null
    }

    fun saveArea(fractions: RectF, landscape: Boolean, gpt: Boolean = false) {
        sp.edit()
            .putFloat(ak(gpt, K_AREA_L), fractions.left.coerceIn(0f, 1f))
            .putFloat(ak(gpt, K_AREA_T), fractions.top.coerceIn(0f, 1f))
            .putFloat(ak(gpt, K_AREA_R), fractions.right.coerceIn(0f, 1f))
            .putFloat(ak(gpt, K_AREA_B), fractions.bottom.coerceIn(0f, 1f))
            .putBoolean(ak(gpt, K_AREA_LAND), landscape)
            .putBoolean(ak(gpt, K_AREA_SET), true)
            .apply()
    }

    fun clearArea(gpt: Boolean = false) {
        sp.edit().putBoolean(ak(gpt, K_AREA_SET), false).apply()
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val LENS_APP = 0
        const val WEB_WINDOW = 1

        const val MAX_SHORTCUTS = 8
        const val MIN_HANDLE_DP = 24
        const val MAX_HANDLE_DP = 80
        const val DEFAULT_HANDLE_DP = 44
        const val DEFAULT_SEARCH_COLOR = 0xFFF1F3F4.toInt()
        const val DEFAULT_CLIP_COLOR = 0xFFFFB224.toInt()
        const val DEFAULT_GPT_COLOR = 0xFF10A37F.toInt()

        private const val FILE = "circle_search_prefs"
        private const val K_AREA_SET = "saved_area_set"
        private const val K_AREA_L = "saved_area_l"
        private const val K_AREA_T = "saved_area_t"
        private const val K_AREA_R = "saved_area_r"
        private const val K_AREA_B = "saved_area_b"
        private const val K_AREA_LAND = "saved_area_landscape"
        private const val K_PROMPT_TEXT = "prompt_text"
        private const val K_PROMPT_ON = "prompt_enabled"
        private const val K_GPT_ON = "gpt_enabled"
        private const val K_GPT_COLOR = "gpt_handle_color"
        private const val K_GPT_PROMPT = "gpt_prompt_text"
        private const val K_GPT_PROMPT_ON = "gpt_prompt_enabled"
        private const val K_GPT_SEND = "gpt_auto_send"
        private const val K_ACTIVE = "service_active"
        private const val K_STRIP_RIGHT = "strip_on_right"
        private const val K_STRIP_Y = "strip_y_fraction"
        private const val K_STRIP_ALPHA = "strip_opacity"
        private const val K_HANDLE_H = "handle_height_dp"
        private const val K_SEARCH_COLOR = "search_handle_color"
        private const val K_CLIP_ON = "clipboard_enabled"
        private const val K_CLIP_COLOR = "clipboard_handle_color"
        private const val K_SHORTCUTS = "app_shortcuts_json"
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
