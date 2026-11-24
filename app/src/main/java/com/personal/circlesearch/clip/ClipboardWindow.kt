package com.personal.circlesearch.clip

import com.personal.circlesearch.R

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import kotlin.math.abs

/**
 * The PinClip clipboard window, hosted by Circle Search's accessibility service.
 * Same window, same cards, same editor - only the way it is opened changed: the clipboard handle
 * in the edge-handle cluster toggles it ([toggle]). It is an accessibility overlay, so no extra
 * "draw over other apps" permission is needed.
 */
class ClipboardWindow(private val host: AccessibilityService, private val onClosed: () -> Unit = {}) : ContextWrapper(host) {

    /** Set by the host: false while the clipboard feature is switched off (panel closed, auto-save paused). */
    var active = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) close()
            applyAuto()
        }

    private lateinit var wm: WindowManager
    private lateinit var cm: ClipboardManager
    private val h = Handler(Looper.getMainLooper())

    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var body: FrameLayout? = null
    private var multiBar: LinearLayout? = null
    private var chipRow: LinearLayout? = null
    private var searchRow: LinearLayout? = null
    private var compact = false
    private var actionRow: LinearLayout? = null
    private var confirmBulk = false
    private var searchBox: EditText? = null
    private var fullH = 0
    private var shortH = 0

    private var autoOn = false
    private var obs: ContentObserver? = null
    private val seen = HashSet<Long>()

    private var filter = "all"
    private var query = ""
    private var multi = false
    private var editorOpen = false
    private val scrollMap = HashMap<String, Int>()   // list scroll remembered per tab / group
    private val expanded = HashSet<String>()         // cards opened to full text
    private val selected = LinkedHashSet<String>()
    private var pendingDelete: String? = null
    private var sep = 0
    private var board: StickyBoardView? = null
    private var wrap: FrameLayout? = null
    private var menuCard: View? = null
    private var noteBar: HorizontalScrollView? = null
    private var boardTools: HorizontalScrollView? = null
    private var boardToolsRow: LinearLayout? = null
    private var moreBtn: ImageView? = null
    private var colorPick = false
    private var confirmNoteDel = false
    private fun sticky() = Store.prefs.getBoolean("sticky", false)
    private val seps = listOf("New line" to "\n", "Space" to " ", "Comma" to ", ")

    private val prefL = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k == "palpha") applyPanelAlpha() else if (k == "auto") applyAuto()
    }
    private val clipL = ClipboardManager.OnPrimaryClipChangedListener { onClip("listener") }
    private val clipRun = Runnable { readClip() }
    private val shotRun = Runnable { Thread { checkShot() }.start() }
    init {
        Store.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        Store.prefs.registerOnSharedPreferenceChangeListener(prefL)
        Store.onChange = { if (panel != null) renderList() }
        val lf = Store.prefs.getString("lfilter", "all") ?: "all"
        filter = if (lf == "all" || lf == "pinned" || lf == "used" || Store.groups.any { it.id == lf }) lf else "all"
    }

    /** Called when the service stops or is turned off. */
    fun destroy() {
        close()
        if (autoOn) {
            cm.removePrimaryClipChangedListener(clipL)
            obs?.let { try { contentResolver.unregisterContentObserver(it) } catch (e: Exception) {} }
            obs = null
            autoOn = false
        }
        Store.prefs.unregisterOnSharedPreferenceChangeListener(prefL)
        Store.onChange = null
        h.removeCallbacksAndMessages(null)
    }

    val isOpen: Boolean get() = panel != null

    /** The clipboard handle: opens the window, or closes it when it is already open. */
    fun toggle() { if (panel != null) closePanel() else openPanel() }

    fun close() { if (panel != null) closePanel() }

    /** Hides the window while Circle Search takes its screenshot, then brings it back. */
    fun setSuppressed(on: Boolean) {
        panel?.visibility = if (on) View.INVISIBLE else View.VISIBLE
    }

    /** Re-fits the window after a rotation. */
    fun onConfigChanged() {
        h.postDelayed({
            refitFull()
            keepOnScreen()
        }, 150)
    }

    private fun keepOnScreen() {
        val p = panel ?: return
        val lp = panelLp ?: return
        if (isFull()) return
        val (sw, sh) = screenWH()
        lp.width = lp.width.coerceAtMost(sw)
        lp.x = lp.x.coerceIn(0, maxOf(0, sw - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, sh - dp(120)))
        try { wm.updateViewLayout(p, lp) } catch (e: Exception) {}
    }

    // ───────────────────────── bubble ─────────────────────────

    private fun overlayLp(ww: Int, hh: Int) = WindowManager.LayoutParams(
        ww, hh, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private fun attachDrag(
        handle: View, target: () -> View?, lpOf: () -> WindowManager.LayoutParams?,
        onTap: (() -> Unit)?, onEnd: (() -> Unit)?
    ) {
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            val lp = lpOf()
            val t = target()
            if (lp == null || t == null) return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = sx + dx
                        lp.y = sy + dy
                        val d = resources.displayMetrics
                        lp.x = lp.x.coerceIn(-(t.width - dp(70)).coerceAtLeast(0), d.widthPixels - dp(70))
                        lp.y = lp.y.coerceIn(0, d.heightPixels - dp(50))
                        try { wm.updateViewLayout(t, lp) } catch (ex: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) onEnd?.invoke() else onTap?.invoke()
                }
                MotionEvent.ACTION_CANCEL -> {
                }
            }
            true
        }
    }

    // ───────────────────────── panel ─────────────────────────

    private fun openPanel() {
        if (panel != null) return
        buildPanel()
        // catch up: if something was copied while no focus was available, save it now
        if (autoOn) h.postDelayed({ if (autoOn && panel != null) readClip() }, 350)
    }

    private fun closePanel() {
        hideKb()
        saveScroll()
        dropBoard()
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panel = null; panelLp = null; body = null; multiBar = null; chipRow = null; actionRow = null; confirmBulk = false; searchBox = null; searchRow = null
        multi = false; editorOpen = false; selected.clear(); pendingDelete = null
        onClosed()
    }

    private fun setFocusable(on: Boolean) {
        val p = panel ?: return
        val lp = panelLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.height = if (on) shortH else fullH
        try { wm.updateViewLayout(p, lp) } catch (e: Exception) {}
        if (on) p.alpha = 1f else applyPanelAlpha()
    }

    private fun applyPanelAlpha() {
        val p = panel ?: return
        if (editorOpen) { p.alpha = 1f; return }
        p.alpha = (Store.prefs.getInt("palpha", 100).coerceIn(0, 100) / 100f).coerceAtLeast(0.15f)
    }

    private fun savePanelGeometry() {
        if (isFull()) return   // keep the normal-size geometry for when full screen is switched off
        val lp = panelLp ?: return
        Store.prefs.edit().putInt("px", lp.x).putInt("py", lp.y).putInt("pw", lp.width).putInt("ph", fullH).apply()
    }

    private fun hideKb() {
        val p = panel ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(p.windowToken, 0)
        searchBox?.clearFocus()
    }

    private fun showKb(et: EditText) {
        h.postDelayed({
            et.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(et, 0)
        }, 220)
    }

    private fun ic(res: Int, tint: Int): ImageView = if (compact) iconView(res, tint, 16, 4) else iconView(res, tint, 18, 5)
    private fun tiny(t: String, bg: Int, fg: Int = C.TEXT): TextView = pill(t, bg, fg, true)
    private fun gp(): Int = dp(if (compact) 4 else 7)

    private fun setActionVis(v: Int) {
        val ar = actionRow ?: return
        ar.visibility = v
        (ar.parent as? View)?.visibility = v
    }

    private fun isFull() = Store.prefs.getBoolean("pfull", false)

    /** Real screen size in the current orientation (follows rotation). */
    private fun screenWH(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun statusBarH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /** Re-fit the full-screen panel after rotation, without rebuilding it. */
    private fun refitFull() {
        val p = panel ?: return
        val lp = panelLp ?: return
        if (!isFull()) return
        val (sw, sh) = screenWH()
        val sb = statusBarH()
        lp.x = 0; lp.y = sb; lp.width = sw
        fullH = sh - sb
        shortH = maxOf(minOf(fullH, (sh * 0.42f).toInt()), dp(200))
        lp.height = if ((lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) shortH else fullH
        try { wm.updateViewLayout(p, lp) } catch (e: Exception) {}
    }

    private fun saveScroll() {
        (body?.getChildAt(0) as? ScrollView)?.let { scrollMap[filter] = it.scrollY }
    }

    private fun rebuildPanel() {
        hideKb()
        saveScroll()
        dropBoard()
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panel = null; panelLp = null; body = null; multiBar = null; chipRow = null; actionRow = null
        searchBox = null; searchRow = null; editorOpen = false
        buildPanel()
    }

    private fun curGroup(): String? = if (filter == "all" || filter == "pinned" || filter == "used") null else filter

    private fun buildPanel() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val full = isFull()
        val (rw, rh) = screenWH()
        val sb = if (full) statusBarH() else 0
        val w = if (full) rw else Store.prefs.getInt("pw", minOf(dp(340), sw - dp(16))).coerceIn(dp(150), sw)
        fullH = if (full) rh - sb else Store.prefs.getInt("ph", (sh * 0.62f).toInt()).coerceIn(dp(140), sh)
        shortH = maxOf(minOf(fullH, ((if (full) rh else sh) * 0.42f).toInt()), dp(200))
        compact = w < dp(300)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(C.BG, 18)
        val pd = dp(if (compact) 6 else 10)
        root.setPadding(pd, pd, pd, pd)
        root.elevation = dp(8).toFloat()

        // header: [back] [pin grip] [title ......] [+] [⋮ menu]
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val minB = ic(R.drawable.clip_ic_minimize, C.TEXT)
        minB.background = rounded(C.CARD2, 14)
        minB.setOnClickListener { closePanel() }
        head.addView(minB)
        val pinIc = ic(R.drawable.clip_ic_pin, C.AMBER)
        head.addView(pinIc)
        if (!compact) {
            val title = label("PinClip", 15f)
            title.setTypeface(title.typeface, Typeface.BOLD)
            title.maxLines = 1
            head.addView(title, LP(0, WRAP, 1f))
            if (!full) attachDrag(title, { panel }, { panelLp }, null) { savePanelGeometry() }
        } else {
            val sp = View(this)
            head.addView(sp, LP(0, 1, 1f))
            if (!full) attachDrag(sp, { panel }, { panelLp }, null) { savePanelGeometry() }
        }
        if (!full) attachDrag(pinIc, { panel }, { panelLp }, null) { savePanelGeometry() }

        val addB = ic(R.drawable.clip_ic_add, C.ACCENT)
        addB.setOnClickListener {
            hideMenu()
            showEditor("New text", "", curGroup(), true, false, null) { t, g ->
                Store.addText(t, g)
            }
        }
        head.addView(addB)
        val moreB = ic(R.drawable.clip_ic_more, if (sticky()) C.ACCENT else C.SUB)
        moreB.setOnClickListener { if (menuCard != null) hideMenu() else showMenu() }
        head.addView(moreB)
        moreBtn = moreB

        // drag handle across the whole top edge (grab anywhere here to move the window)
        val handle = FrameLayout(this)
        val pillV = View(this)
        pillV.background = rounded(C.SUB, 3)
        pillV.alpha = 0.6f
        handle.addView(pillV, FrameLayout.LayoutParams(dp(44), dp(4), Gravity.CENTER))
        if (!full) {
            root.addView(handle, LP(MATCH, dp(22)))
            attachDrag(handle, { panel }, { panelLp }, null) { savePanelGeometry() }
        }
        root.addView(head, LP(MATCH, WRAP))

        // everything under the header lives in [wrap] so the ⋮ menu and the note bar can float over it
        val wrapV = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        wrapV.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        wrap = wrapV

        // search box: hidden until the search button is tapped
        val sRow = LinearLayout(this)
        sRow.orientation = LinearLayout.HORIZONTAL
        sRow.gravity = Gravity.CENTER_VERTICAL
        sRow.background = rounded(C.CARD, 10)
        sRow.setPadding(dp(8), 0, dp(2), 0)
        val et = EditText(this)
        et.hint = "Search"
        et.setHintTextColor(C.SUB)
        et.setTextColor(C.TEXT)
        et.textSize = 13f
        et.background = null
        et.setPadding(dp(2), 0, dp(2), 0)
        et.isSingleLine = true
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        et.setText(query)
        et.doAfterTextChanged { query = it?.toString() ?: ""; renderList(true) }
        et.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                setFocusable(true)
                showKb(et)
            }
            false
        }
        et.setOnFocusChangeListener { _, f -> if (!f && !editorOpen) setFocusable(false) }
        et.setOnEditorActionListener { _, _, _ -> hideKb(); setFocusable(false); true }
        sRow.addView(et, LP(0, WRAP, 1f))
        val clearB = ic(R.drawable.clip_ic_close, C.SUB)
        clearB.setOnClickListener { et.setText("") }
        sRow.addView(clearB)
        searchBox = et
        searchRow = sRow
        sRow.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        col.addView(sRow, LP(MATCH, dp(32)).apply { topMargin = gp() })

        // chips
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        hs.addView(row)
        chipRow = row
        col.addView(hs, LP(MATCH, WRAP).apply { topMargin = gp() })

        // sticky-board tools (hand / pencil / eraser / undo / colours / sizes), only in sticky note view
        val bt = HorizontalScrollView(this)
        bt.isHorizontalScrollBarEnabled = false
        bt.visibility = View.GONE
        val btRow = LinearLayout(this)
        btRow.orientation = LinearLayout.HORIZONTAL
        btRow.gravity = Gravity.CENTER_VERTICAL
        bt.addView(btRow, FrameLayout.LayoutParams(WRAP, WRAP))
        boardTools = bt
        boardToolsRow = btRow
        col.addView(bt, LP(MATCH, WRAP).apply { topMargin = gp() })

        // paste-all row (scrolls sideways in a tiny window)
        val ar = LinearLayout(this)
        ar.orientation = LinearLayout.HORIZONTAL
        ar.gravity = Gravity.CENTER_VERTICAL
        val ahs = HorizontalScrollView(this)
        ahs.isHorizontalScrollBarEnabled = false
        ahs.visibility = View.GONE
        ahs.addView(ar, FrameLayout.LayoutParams(WRAP, WRAP))
        actionRow = ar
        col.addView(ahs, LP(MATCH, WRAP).apply { topMargin = gp() })

        // body
        val b = FrameLayout(this)
        body = b
        col.addView(b, LP(MATCH, 0, 1f).apply { topMargin = gp() })

        // multi bar
        val mb = LinearLayout(this)
        mb.orientation = LinearLayout.VERTICAL
        mb.visibility = View.GONE
        multiBar = mb
        col.addView(mb, LP(MATCH, WRAP).apply { topMargin = gp() })

        // floating action bar for the selected sticky note
        val nb = HorizontalScrollView(this)
        nb.isHorizontalScrollBarEnabled = false
        nb.visibility = View.GONE
        nb.background = rounded(0xEE151922.toInt(), 16)
        nb.setPadding(dp(6), dp(5), dp(6), dp(5))
        val nbRow = LinearLayout(this)
        nbRow.orientation = LinearLayout.HORIZONTAL
        nbRow.gravity = Gravity.CENTER_VERTICAL
        nb.addView(nbRow, FrameLayout.LayoutParams(WRAP, WRAP))
        noteBar = nb
        wrapV.addView(nb, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })
        root.addView(wrapV, LP(MATCH, 0, 1f))

        // resize grip (bottom-right): drag to make the window bigger or smaller
        val gripRow = LinearLayout(this)
        gripRow.gravity = Gravity.END
        val grip = iconView(R.drawable.clip_ic_resize, C.SUB, 18, 5)
        if (!full) {
            gripRow.addView(grip)
            root.addView(gripRow, LP(MATCH, WRAP))
            attachResize(grip)
        }

        val lp = overlayLp(w, fullH)
        lp.gravity = Gravity.TOP or Gravity.START
        if (full) {
            lp.x = 0
            lp.y = sb
        } else {
            lp.x = Store.prefs.getInt("px", (sw - w) / 2).coerceIn(0, maxOf(0, sw - w))
            lp.y = Store.prefs.getInt("py", dp(60)).coerceIn(0, maxOf(0, sh - dp(120)))
        }
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        panel = root
        panelLp = lp
        wm.addView(root, lp)
        applyPanelAlpha()

        renderChips()
        updateMultiBar()
        applyStickyUi()
    }

    private fun attachResize(grip: View) {
        var sw0 = 0
        var sh0 = 0
        var tx = 0f
        var ty = 0f
        grip.setOnTouchListener { _, e ->
            val lp = panelLp
            val p = panel
            if (lp == null || p == null) return@setOnTouchListener false
            val dm = resources.displayMetrics
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sw0 = lp.width; sh0 = lp.height; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (sw0 + (e.rawX - tx)).toInt().coerceIn(dp(150), maxOf(dp(150), dm.widthPixels - lp.x))
                    lp.height = (sh0 + (e.rawY - ty)).toInt().coerceIn(dp(140), maxOf(dp(140), dm.heightPixels - lp.y))
                    fullH = lp.height
                    shortH = maxOf(minOf(fullH, (dm.heightPixels * 0.42f).toInt()), dp(200))
                    try { wm.updateViewLayout(p, lp) } catch (ex: Exception) {}
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePanelGeometry()
                    if ((lp.width < dp(300)) != compact) rebuildPanel()
                }
            }
            true
        }
    }

    private fun renderChips() {
        val row = chipRow ?: return
        row.removeAllViews()
        fun chip(text: String, key: String, g: Group?) {
            val sel = filter == key
            val t = tiny(text, if (sel) C.ACCENT else C.CARD, if (sel) Color.WHITE else C.TEXT)
            t.setOnClickListener {
                if (filter != key) {
                    saveScroll()
                    filter = key
                    Store.prefs.edit().putString("lfilter", key).apply()
                    if (!editorOpen) body?.removeAllViews()   // so the new tab restores ITS own saved position
                    renderChips()
                    renderList()
                }
            }
            if (g != null) t.setOnLongClickListener { groupMenu(g); true }
            row.addView(t, LP(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        chip("All", "all", null)
        chip("Pinned", "pinned", null)
        chip("Top used", "used", null)
        Store.groups.forEach { chip(it.name, it.id, it) }
        val add = tiny("+ Group", C.CARD2, C.ACCENT)
        add.setOnClickListener {
            showEditor("New group name", "", null, false, true, null) { t, _ -> Store.addGroup(t); renderChips() }
        }
        row.addView(add)
    }

    private fun groupMenu(g: Group) {
        showEditor("Rename group", g.name, null, false, true, "Delete group" to {
            Store.deleteGroup(g.id)
            if (filter == g.id) filter = "all"
            Unit
        }) { t, _ -> Store.renameGroup(g, t); renderChips() }
    }

    private fun filtered(): List<Item> {
        val q = query.trim().lowercase()
        var l = Store.items.filter {
            when (filter) {
                "all" -> true
                "pinned" -> it.pinned
                "used" -> it.uses > 0
                else -> it.groupId == filter
            }
        }
        if (q.isNotEmpty()) {
            l = l.filter { x ->
                x.text.lowercase().contains(q) ||
                    (Store.groups.find { g -> g.id == x.groupId }?.name?.lowercase()?.contains(q) == true)
            }
        }
        return if (filter == "used")
            l.sortedWith(compareByDescending<Item> { it.uses }.thenByDescending { it.created })
        else
            l.sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.created })
    }

    private fun renderList(resetScroll: Boolean = false) {
        val b = body ?: return
        val list = filtered()
        updateActionRow(list)
        if (editorOpen) return
        if (sticky()) { renderBoard(list); return }
        // remember where the user was so a paste/refresh doesn't jump back to the top
        val oldScroll = if (resetScroll) 0 else ((b.getChildAt(0) as? ScrollView)?.scrollY ?: scrollMap[filter] ?: 0)
        scrollMap[filter] = oldScroll
        b.removeAllViews()
        if (list.isEmpty()) {
            val e = label("Nothing here yet.\nTap + to add text, or turn on Auto detect.", 13f, C.SUB)
            e.gravity = Gravity.CENTER
            b.addView(e, FrameLayout.LayoutParams(MATCH, MATCH))
            return
        }
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        for (it0 in list) col.addView(card(it0), LP(MATCH, WRAP).apply { bottomMargin = dp(6) })
        sv.addView(col)
        b.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        if (oldScroll > 0) {
            // wait until the list is laid out, otherwise the scroll gets clamped to 0
            sv.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (sv.viewTreeObserver.isAlive) sv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    sv.scrollTo(0, oldScroll)
                }
            })
        }
    }

    private val INK_DARK = 0xFF1E2128.toInt()
    private val INK_SUB = 0xFF4A4F5C.toInt()
    private val ICON_DARK = 0xFF3B3F4A.toInt()

    private fun cardBg(fill: Int, stroke: Int?): GradientDrawable {
        val g = rounded(fill, 12)
        if (stroke != null) g.setStroke(dp(3), stroke)
        return g
    }

    private fun card(x: Item): View {
        val sel = selected.contains(x.id)
        val pend = pendingDelete == x.id
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(3), dp(4), dp(3), dp(4))
        val tone = if (x.color != 0) x.color else C.CARD
        row.background = if (pend) cardBg(0xFFFF9AA2.toInt(), null) else cardBg(tone, if (sel) 0xFF3B5BFF.toInt() else null)

        val pin = ic(R.drawable.clip_ic_pin, if (x.pinned) 0xFFD9480F.toInt() else ICON_DARK)
        pin.setOnClickListener { Store.togglePin(x) }
        row.addView(pin)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.setPadding(dp(4), 0, dp(4), 0)
        if (x.image != null) {
            // full picture, never cropped; decoded at full quality (tap the expand icon to zoom to actual size)
            val iv = ImageView(this)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.maxHeight = dp(560)
            val f = Store.imageFile(x)
            ImgCache.load(x.id, f, 1400, 3500) { bmp ->
                if (bmp != null && iv.isAttachedToWindow) iv.setImageBitmap(bmp)
                else if (bmp != null) iv.setImageBitmap(bmp)
            }
            mid.addView(iv, LP(MATCH, WRAP))
            if (x.text.isNotBlank()) {
                val cl = label(x.text, 12f, INK_DARK)
                cl.maxLines = if (x.id in expanded) Int.MAX_VALUE else 2
                cl.ellipsize = TextUtils.TruncateAt.END
                cl.setPadding(0, dp(4), 0, 0)
                mid.addView(cl)
            }
        } else {
            val t = label(x.text, if (compact) 12f else 13f, INK_DARK)
            if (!Store.prefs.getBoolean("reading", false) && x.id !in expanded) {
                t.maxLines = 4
                t.ellipsize = TextUtils.TruncateAt.END
            }
            mid.addView(t)
        }
        val meta = listOfNotNull(
            Store.groups.find { g -> g.id == x.groupId }?.name,
            if (x.uses > 0) "${x.uses}×" else null,
            if (x.image != null) "image" else null
        ).joinToString(" · ")
        if (meta.isNotEmpty()) mid.addView(label(meta, 10f, INK_SUB))
        row.addView(mid, LP(0, WRAP, 1f))

        val right = LinearLayout(this)
        right.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val ia = if (compact) 14 else 16
        val ip = if (compact) 4 else 5
        val isOpen = x.id in expanded
        val ex = if (x.image != null) iconView(R.drawable.clip_ic_fullscreen, ICON_DARK, ia, ip)
        else iconView(if (isOpen) R.drawable.clip_ic_collapse else R.drawable.clip_ic_expand, if (isOpen) 0xFF2447D8.toInt() else ICON_DARK, ia, ip)
        ex.setOnClickListener {
            if (x.image != null) showImageViewer(x)
            else {
                if (!expanded.add(x.id)) expanded.remove(x.id)
                renderList()
            }
        }
        right.addView(ex)
        val ed = iconView(R.drawable.clip_ic_edit, ICON_DARK, ia, ip)
        ed.setOnClickListener { editItem(x) }
        right.addView(ed)
        val cp = iconView(R.drawable.clip_ic_copy, ICON_DARK, ia, ip)
        cp.setOnClickListener { copyOnly(x) }
        right.addView(cp)
        val del = iconView(if (pend) R.drawable.clip_ic_check else R.drawable.clip_ic_delete, if (pend) 0xFFB0001E.toInt() else ICON_DARK, ia, ip)
        del.setOnClickListener {
            if (pendingDelete == x.id) {
                pendingDelete = null
                selected.remove(x.id)
                Store.delete(x)
            } else {
                pendingDelete = x.id
                renderList()
                h.postDelayed({ if (pendingDelete == x.id) { pendingDelete = null; renderList() } }, 2500)
            }
        }
        right.addView(del)
        if (compact) {
            right.gravity = Gravity.END
            mid.addView(right, LP(MATCH, WRAP))
        } else {
            row.addView(right)
        }

        row.setOnClickListener {
            if (multi) {
                if (!selected.add(x.id)) selected.remove(x.id)
                updateMultiBar()
                renderList()
            } else paste(x)
        }
        row.setOnLongClickListener {
            multi = true
            selected.add(x.id)
            updateMultiBar()
            renderList()
            true
        }
        return row
    }

    private fun editItem(x: Item) {
        if (x.image == null) {
            showEditor("Edit text", x.text, x.groupId, true, false, null, false, null, x) { t, g -> Store.update(x, t, g) }
        } else {
            showEditor("Edit image card", x.text, x.groupId, true, false, null, true, x, x) { t, g -> Store.update(x, t.trim(), g) }
        }
    }

    private fun updateActionRow(list: List<Item>) {
        val ar = actionRow ?: return
        ar.removeAllViews()
        val n = list.count { it.image == null }
        if (editorOpen || multi || n == 0 || sticky()) { setActionVis(View.GONE); return }
        setActionVis(View.VISIBLE)
        val go = tiny("Paste all ($n)", C.ACCENT, Color.WHITE)
        go.setOnClickListener { pasteTexts(list) }
        ar.addView(go, LP(WRAP, WRAP).apply { rightMargin = dp(4) })
        val sp = tiny("Join: " + seps[sep].first, C.CARD2)
        sp.setOnClickListener { sep = (sep + 1) % seps.size; updateActionRow(filtered()) }
        ar.addView(sp)
    }

    private fun updateMultiBar() {
        val bar = multiBar ?: return
        bar.removeAllViews()
        if (sticky()) multi = false
        bar.visibility = if (multi) View.VISIBLE else View.GONE
        if (!multi) return
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        hs.addView(r, FrameLayout.LayoutParams(WRAP, WRAP))
        fun add(v: View) { r.addView(v, LP(WRAP, WRAP).apply { rightMargin = dp(4) }) }

        add(label("${selected.size}✓", 12f, C.SUB))

        val all = tiny("All", C.CARD2)
        all.setOnClickListener {
            val ids = filtered().map { it.id }
            if (selected.containsAll(ids)) selected.clear() else selected.addAll(ids)
            updateMultiBar()
            renderList()
        }
        add(all)

        val go = tiny("Paste", C.ACCENT, Color.WHITE)
        go.setOnClickListener {
            if (selected.isEmpty()) toast("Select cards first")
            else pasteTexts(filtered().filter { selected.contains(it.id) })
        }
        add(go)

        val mv = tiny("Move", C.CARD2, C.ACCENT)
        mv.setOnClickListener {
            if (selected.isEmpty()) {
                toast("Select cards first")
            } else {
                showPicker("Move ${selected.size} cards to…") { g ->
                    Store.moveMany(selected.toList(), g)
                    multi = false
                    selected.clear()
                    updateMultiBar()
                    renderList()
                }
            }
        }
        add(mv)

        val del = tiny(if (confirmBulk) "Tap again" else "Delete", C.CARD2, C.RED)
        del.setOnClickListener {
            if (selected.isEmpty()) {
                toast("Select cards first")
            } else if (confirmBulk) {
                Store.deleteMany(selected.toList())
                selected.clear()
                confirmBulk = false
                multi = false
                updateMultiBar()
                renderList()
            } else {
                confirmBulk = true
                updateMultiBar()
                h.postDelayed({ if (confirmBulk) { confirmBulk = false; updateMultiBar() } }, 3000)
            }
        }
        add(del)

        val sp = tiny(seps[sep].first, C.CARD2)
        sp.setOnClickListener { sep = (sep + 1) % seps.size; updateMultiBar() }
        add(sp)

        val done = tiny("Done", C.CARD2)
        done.setOnClickListener { multi = false; selected.clear(); confirmBulk = false; updateMultiBar(); renderList() }
        add(done)

        bar.addView(hs, LP(MATCH, WRAP))
    }

    private fun showPicker(title: String, onPick: (String?) -> Unit) {
        val b = body ?: return
        editorOpen = true
        setActionVis(View.GONE)
        noteBar?.visibility = View.GONE
        b.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(title, 13f, C.SUB))
        val sv = ScrollView(this)
        val opts = LinearLayout(this)
        opts.orientation = LinearLayout.VERTICAL
        fun opt(text: String, gid: String?) {
            val p = tiny(text, C.CARD, C.TEXT)
            p.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            p.setOnClickListener { editorOpen = false; renderList(); onPick(gid) }
            opts.addView(p, LP(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        opt("No group", null)
        Store.groups.forEach { opt(it.name, it.id) }
        if (Store.groups.isEmpty()) opts.addView(label("Create groups with “+ Group” first.", 12f, C.SUB))
        sv.addView(opts)
        col.addView(sv, LP(MATCH, 0, 1f))
        val cancel = tiny("Cancel", C.CARD2)
        cancel.setOnClickListener { editorOpen = false; renderList() }
        col.addView(cancel, LP(WRAP, WRAP).apply { topMargin = dp(8) })
        b.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ───────────────────────── ⋮ menu ─────────────────────────

    private fun hideMenu() {
        menuCard?.let { (it.parent as? FrameLayout)?.removeView(it) }
        menuCard = null
    }

    private fun showMenu() {
        val w = wrap ?: return
        hideMenu()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(C.CARD2, 14)
        card.elevation = dp(10).toFloat()
        card.setPadding(dp(4), dp(4), dp(4), dp(4))
        fun row(res: Int, text: String, on: Boolean?, act: () -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(6), dp(2), dp(10), dp(2))
            r.addView(iconView(res, if (on == true) C.ACCENT else C.TEXT, 18, 5))
            val tv = label(text, 13f, if (on == true) C.ACCENT else C.TEXT)
            tv.setPadding(dp(4), 0, dp(10), 0)
            r.addView(tv, LP(0, WRAP, 1f))
            if (on != null) {
                val st = pill(if (on) "ON" else "OFF", if (on) C.ACCENT else C.CARD, if (on) Color.WHITE else C.SUB, true)
                r.addView(st)
            }
            r.setOnClickListener { hideMenu(); act() }
            card.addView(r, LP(MATCH, WRAP))
        }
        row(R.drawable.clip_ic_sticky, "Sticky note view", sticky()) { toggleSticky() }
        row(R.drawable.clip_ic_search, "Search", query.isNotEmpty()) { toggleSearch() }
        if (!sticky()) {
            row(R.drawable.clip_ic_read, "Reading mode", Store.prefs.getBoolean("reading", false)) {
                Store.prefs.edit().putBoolean("reading", !Store.prefs.getBoolean("reading", false)).apply()
                renderList()
            }
            row(R.drawable.clip_ic_multi, "Select multiple", multi) {
                multi = !multi
                selected.clear()
                updateMultiBar()
                renderList()
            }
        }
        row(R.drawable.clip_ic_image, "Add image", null) {
            try {
                val i = Intent(this, ImagePickActivity::class.java)
                i.putExtra("group", curGroup())
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e: Exception) {
                toast("Open the PinClip app and tap Add images")
            }
        }
        row(if (isFull()) R.drawable.clip_ic_fullscreen_exit else R.drawable.clip_ic_fullscreen, "Full screen", isFull()) {
            saveScroll()
            Store.prefs.edit().putBoolean("pfull", !isFull()).apply()
            rebuildPanel()
        }
        w.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = dp(2) })
        menuCard = card
    }

    private fun toggleSearch() {
        val sr = searchRow ?: return
        val box = searchBox ?: return
        if (sr.visibility != View.VISIBLE) {
            sr.visibility = View.VISIBLE
            setFocusable(true)
            showKb(box)
        } else {
            box.setText("")
            sr.visibility = View.GONE
            hideKb()
            setFocusable(false)
        }
    }

    // ───────────────────────── sticky note view ─────────────────────────

    private fun toggleSticky() {
        Store.prefs.edit().putBoolean("sticky", !sticky()).apply()
        confirmBulk = false
        colorPick = false
        body?.removeAllViews()
        applyStickyUi()
    }

    private fun applyStickyUi() {
        val on = sticky()
        moreBtn?.setColorFilter(if (on) C.ACCENT else C.SUB)
        boardTools?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            multi = false
            selected.clear()
            updateMultiBar()
            renderBoardTools()
        } else {
            board?.selected = null
        }
        noteBar?.visibility = View.GONE
        renderList()
    }

    private fun dropBoard() {
        hideMenu()
        board?.release()
        board = null
        wrap = null; noteBar = null; boardTools = null; boardToolsRow = null; moreBtn = null
        colorPick = false
    }

    private fun renderBoard(list: List<Item>) {
        val b = body ?: return
        Store.ensureLayout()
        var bd = board
        if (bd == null) {
            bd = StickyBoardView(this, object : StickyBoardView.Callbacks {
                override fun onSelect(x: Item?) { colorPick = false; confirmNoteDel = false; updateNoteBar() }
                override fun onPaste(x: Item) { paste(x) }
            })
            board = bd
        }
        bd.notes = list
        if (bd.parent !== b) {
            b.removeAllViews()
            b.addView(bd, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        bd.refresh()
        updateNoteBar()
    }

    private fun renderBoardTools() {
        val row = boardToolsRow ?: return
        row.removeAllViews()
        val tool = Store.prefs.getInt("btool", StickyBoardView.TOOL_MOVE)
        val curColor = Store.prefs.getInt("bcolor", INK_COLORS[0])
        val curSize = Store.prefs.getFloat("bsize", 3f)
        fun toolBtn(res: Int, t: Int) {
            val v = ic(res, if (tool == t) Color.WHITE else C.SUB)
            v.background = rounded(if (tool == t) C.ACCENT else C.CARD2, 14)
            v.setOnClickListener {
                board?.tool = t
                Store.prefs.edit().putInt("btool", t).apply()
                renderBoardTools()
            }
            row.addView(v, LP(v.layoutParams.width, v.layoutParams.height).apply { rightMargin = dp(4) })
        }
        toolBtn(R.drawable.clip_ic_hand, StickyBoardView.TOOL_MOVE)
        toolBtn(R.drawable.clip_ic_edit, StickyBoardView.TOOL_PEN)
        toolBtn(R.drawable.clip_ic_eraser, StickyBoardView.TOOL_ERASE)
        val undo = ic(R.drawable.clip_ic_undo, C.TEXT)
        undo.background = rounded(C.CARD2, 14)
        undo.setOnClickListener { board?.undo() }
        row.addView(undo, LP(undo.layoutParams.width, undo.layoutParams.height).apply { rightMargin = dp(8) })

        INK_COLORS.forEach { c ->
            val d = View(this)
            d.background = ovalDot(c, if (c == curColor) Color.WHITE else 0x55FFFFFF)
            d.setOnClickListener {
                Store.prefs.edit().putInt("bcolor", c).apply()
                board?.applyInk()
                if (Store.prefs.getInt("btool", 0) == StickyBoardView.TOOL_MOVE) board?.tool = StickyBoardView.TOOL_PEN
                renderBoardTools()
            }
            row.addView(d, LP(dp(24), dp(24)).apply { rightMargin = dp(6); leftMargin = dp(1) })
        }
        val gap = View(this)
        row.addView(gap, LP(dp(6), 1))
        INK_SIZES.forEach { sz ->
            val box = FrameLayout(this)
            box.background = if (sz == curSize) rounded(C.CARD2, 12) else null
            val dot = View(this)
            dot.background = ovalDot(C.TEXT, null)
            val dd = (2 + sz * 1.5f).toInt().coerceAtMost(22)
            box.addView(dot, FrameLayout.LayoutParams(dp(dd), dp(dd), Gravity.CENTER))
            box.setOnClickListener {
                Store.prefs.edit().putFloat("bsize", sz).apply()
                board?.applyInk()
                renderBoardTools()
            }
            row.addView(box, LP(dp(28), dp(28)).apply { rightMargin = dp(2) })
        }
        val fit = ic(R.drawable.clip_ic_fullscreen, C.SUB)
        fit.setOnClickListener { board?.fitAll() }
        row.addView(fit, LP(fit.layoutParams.width, fit.layoutParams.height).apply { leftMargin = dp(6) })
    }

    private fun updateNoteBar() {
        val nb = noteBar ?: return
        val sel = board?.selected
        if (!sticky() || editorOpen || sel == null || Store.items.none { it === sel }) { nb.visibility = View.GONE; return }
        val row = nb.getChildAt(0) as? LinearLayout ?: return
        row.removeAllViews()
        nb.visibility = View.VISIBLE
        fun add(v: View) { row.addView(v, LP(WRAP, WRAP).apply { rightMargin = dp(5) }) }
        if (colorPick) {
            PALETTE.forEach { pc ->
                val d = View(this)
                d.background = ovalDot(pc, if (pc == sel.color) Color.WHITE else null)
                d.setOnClickListener { colorPick = false; Store.setColor(sel, pc) }
                row.addView(d, LP(dp(26), dp(26)).apply { rightMargin = dp(6) })
            }
            val back = tiny("Back", C.CARD2)
            back.setOnClickListener { colorPick = false; updateNoteBar() }
            add(back)
            return
        }
        val go = tiny("Paste", C.ACCENT, Color.WHITE)
        go.setOnClickListener { paste(sel) }
        add(go)
        if (sel.image != null) {
            val v = tiny("View", C.CARD2)
            v.setOnClickListener { showImageViewer(sel) }
            add(v)
        }
        val cp = tiny("Copy", C.CARD2)
        cp.setOnClickListener { copyOnly(sel) }
        add(cp)
        val ed = tiny("Edit", C.CARD2)
        ed.setOnClickListener { editItem(sel) }
        add(ed)
        val cl = tiny("Color", C.CARD2, C.ACCENT)
        cl.setOnClickListener { colorPick = true; updateNoteBar() }
        add(cl)
        val pn = tiny(if (sel.pinned) "Unpin" else "Pin", C.CARD2)
        pn.setOnClickListener { Store.togglePin(sel) }
        add(pn)
        val del = tiny(if (confirmNoteDel) "Tap again" else "Delete", C.CARD2, C.RED)
        del.setOnClickListener {
            if (confirmNoteDel) {
                confirmNoteDel = false
                board?.selected = null
                Store.delete(sel)
            } else {
                confirmNoteDel = true
                updateNoteBar()
                h.postDelayed({ if (confirmNoteDel) { confirmNoteDel = false; updateNoteBar() } }, 3000)
            }
        }
        add(del)
    }

    // ───────────────────────── full-quality image viewer ─────────────────────────

    private fun showImageViewer(x: Item) {
        val b = body ?: return
        hideMenu()
        editorOpen = true
        setActionVis(View.GONE)
        noteBar?.visibility = View.GONE
        b.removeAllViews()
        val fl = FrameLayout(this)
        fl.background = rounded(0xFF0B0D12.toInt(), 12)
        val zv = ZoomImageView(this)
        fl.addView(zv, FrameLayout.LayoutParams(MATCH, MATCH))
        zv.load(Store.imageFile(x))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val fit = tiny("Fit", C.CARD2)
        fit.setOnClickListener { zv.fit() }
        val one = tiny("100%", C.CARD2)
        one.setOnClickListener { zv.actual() }
        val cl = tiny("Close", C.ACCENT, Color.WHITE)
        cl.setOnClickListener { editorOpen = false; renderList(); updateNoteBar() }
        bar.addView(fit, LP(WRAP, WRAP).apply { rightMargin = dp(5) })
        bar.addView(one, LP(WRAP, WRAP).apply { rightMargin = dp(5) })
        bar.addView(cl)
        fl.addView(bar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = dp(6); rightMargin = dp(6) })
        b.addView(fl, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ───────────────────────── editor ─────────────────────────

    private fun showEditor(
        title: String, initial: String, group: String?, withGroup: Boolean, single: Boolean,
        danger: Pair<String, () -> Unit>?, allowBlank: Boolean = false, preview: Item? = null,
        colorItem: Item? = null,
        onSave: (String, String?) -> Unit
    ) {
        val b = body ?: return
        editorOpen = true
        setActionVis(View.GONE)
        noteBar?.visibility = View.GONE
        setFocusable(true)
        b.removeAllViews()
        var gid = group
        var edColor = colorItem?.color ?: 0
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(title, 13f, C.SUB))
        if (preview != null) {
            val pv = ImageView(this)
            pv.adjustViewBounds = true
            pv.scaleType = ImageView.ScaleType.CENTER_CROP
            pv.maxHeight = dp(80)
            val tf = Store.thumb(preview)
            if (tf.exists()) pv.setImageBitmap(BitmapFactory.decodeFile(tf.path))
            col.addView(pv, LP(MATCH, WRAP).apply { topMargin = dp(6) })
        }

        val et = EditText(this)
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.gravity = Gravity.CENTER_VERTICAL
        val cancel = tiny("Cancel", C.CARD2)
        cancel.setOnClickListener { closeEditor() }
        val save = tiny("Save", C.ACCENT, Color.WHITE)
        save.setOnClickListener {
            val v = et.text.toString()
            if (allowBlank || v.isNotBlank()) {
                closeEditor()
                onSave(v, gid)
                if (colorItem != null && edColor != 0 && edColor != colorItem.color) Store.setColor(colorItem, edColor)
            }
        }
        btns.addView(cancel, LP(WRAP, WRAP).apply { rightMargin = dp(8) })
        btns.addView(save, LP(WRAP, WRAP).apply { rightMargin = dp(8) })
        if (danger != null) {
            val d = tiny(danger.first, C.CARD2, C.RED)
            d.setOnClickListener { closeEditor(); danger.second() ; renderChips() }
            btns.addView(d)
        }
        col.addView(btns, LP(MATCH, WRAP).apply { topMargin = dp(6) })

        if (withGroup) {
            fun gl() = "Group: " + (Store.groups.find { g -> g.id == gid }?.name ?: "None")
            val gb = tiny(gl(), C.CARD2, C.ACCENT)
            gb.setOnClickListener {
                val ids = listOf<String?>(null) + Store.groups.map { g -> g.id }
                gid = ids[(ids.indexOf(gid) + 1) % ids.size]
                gb.text = gl()
            }
            col.addView(gb, LP(WRAP, WRAP).apply { topMargin = dp(6) })
        }
        if (colorItem != null) {
            val cs = HorizontalScrollView(this)
            cs.isHorizontalScrollBarEnabled = false
            val cr = LinearLayout(this)
            cr.orientation = LinearLayout.HORIZONTAL
            cr.gravity = Gravity.CENTER_VERTICAL
            cs.addView(cr, FrameLayout.LayoutParams(WRAP, WRAP))
            fun paintDots() {
                cr.removeAllViews()
                PALETTE.forEach { pc ->
                    val d = View(this)
                    d.background = ovalDot(pc, if (pc == edColor) Color.WHITE else null)
                    d.setOnClickListener { edColor = pc; paintDots() }
                    cr.addView(d, LP(dp(26), dp(26)).apply { rightMargin = dp(7) })
                }
            }
            paintDots()
            col.addView(cs, LP(MATCH, WRAP).apply { topMargin = dp(8) })
        }

        et.setText(initial)
        et.setTextColor(C.TEXT)
        et.setHintTextColor(C.SUB)
        et.hint = if (single) "Name" else if (preview != null) "Caption (optional)" else "Type or paste text… ({date} and {time} work too)"
        et.textSize = 15f
        et.background = rounded(C.CARD, 12)
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        if (single) et.isSingleLine = true else {
            et.minLines = 3
            et.gravity = Gravity.TOP or Gravity.START
        }
        et.setSelection(et.text.length)
        col.addView(et, LP(MATCH, if (single) WRAP else 0, if (single) 0f else 1f).apply { topMargin = dp(8) })
        b.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        showKb(et)
    }

    private fun closeEditor() {
        hideKb()
        editorOpen = false
        setFocusable(false)
        renderList()
    }

    // ───────────────────────── paste / copy ─────────────────────────

    @Suppress("UNUSED_PARAMETER")
    private fun toast(m: String) = Unit // notifications disabled

    private fun paste(x: Item) {
        Store.touch(x)
        hideKb()
        setFocusable(false)
        h.postDelayed({
            if (x.image != null) pasteImage(x) else pasteText(Store.expand(x.text))
        }, 150)
    }

    private fun pasteText(t: String) {
        Store.ignoreClipUntil = SystemClock.elapsedRealtime() + 3000
        Store.lastClip = t
        cm.setPrimaryClip(ClipData.newPlainText("PinClip", t))
        val ok = doPaste(t)
        toast(if (ok) "Pasted" else "Copied – tap inside a text field first")
    }

    private fun imageClip(x: Item): ClipData {
        val uri = FileProvider.getUriForFile(this, "$packageName.clipfiles", Store.imageFile(x))
        return ClipData.newUri(contentResolver, "PinClip image", uri)
    }

    private fun pasteImage(x: Item) {
        Store.ignoreClipUntil = SystemClock.elapsedRealtime() + 3000
        cm.setPrimaryClip(imageClip(x))
        val ok = doPaste(null)
        toast(if (ok) "Pasted" else "Image copied – paste it in the app")
    }

    private fun copyOnly(x: Item) {
        Store.ignoreClipUntil = SystemClock.elapsedRealtime() + 3000
        if (x.image != null) cm.setPrimaryClip(imageClip(x))
        else {
            Store.lastClip = Store.expand(x.text)
            cm.setPrimaryClip(ClipData.newPlainText("PinClip", Store.lastClip))
        }
        toast("Copied")
    }

    /** Pastes many text cards as one block, oldest first. */
    private fun pasteTexts(src: List<Item>) {
        val picked = src.filter { it.image == null }.sortedBy { it.created }
        if (picked.isEmpty()) { toast("No text cards to paste"); return }
        Store.touchMany(picked)
        val joined = picked.joinToString(seps[sep].second) { Store.expand(it.text) }
        hideKb()
        setFocusable(false)
        h.postDelayed({ pasteText(joined) }, 150)
    }

    // ───────────────────────── auto detect ─────────────────────────

    private fun applyAuto() {
        val on = active && Store.prefs.getBoolean("auto", false)
        if (on && !autoOn) {
            cm.addPrimaryClipChangedListener(clipL)
            val o = object : ContentObserver(h) {
                override fun onChange(selfChange: Boolean) {
                    h.removeCallbacks(shotRun)
                    h.postDelayed(shotRun, 900)
                }
            }
            obs = o
            try {
                contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
            } catch (e: Exception) {
            }
            autoOn = true
        } else if (!on && autoOn) {
            cm.removePrimaryClipChangedListener(clipL)
            obs?.let { contentResolver.unregisterContentObserver(it) }
            obs = null
            autoOn = false
        }
    }

    private fun launchReader() {
        val i = Intent(this, ClipReadActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        try {
            // the accessibility service is allowed to start screens from the background on most phones
            host.startActivity(i)
        } catch (e: Exception) {
            try { startActivity(i) } catch (e2: Exception) { Store.log("could not open reader screen") }
        }
    }

    /**
     * Android lets only the window that has focus read the clipboard. We add a 1px focusable
     * overlay for a moment, read the clip, and fall back to an invisible activity if that fails.
     */
    private fun readClip() {
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1,
            if (Settings.canDrawOverlays(this)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            try { wm.removeView(v) } catch (e: Exception) {}
            if (!ok) launchReader()
        }
        v.viewTreeObserver.addOnWindowFocusChangeListener { has ->
            if (has && !finished) {
                val ok = try { ClipReader.read(this) } catch (e: Exception) { false }
                Store.log(if (ok) "overlay read ok" else "overlay read blocked")
                finish(ok)
            }
        }
        try { wm.addView(v, lp) } catch (e: Exception) { launchReader(); return }
        h.postDelayed({
            if (!finished) Store.log("overlay never got focus")
            finish(false)
        }, 900)
    }

    fun onClip(src: String) {
        if (!autoOn) return
        if (SystemClock.elapsedRealtime() < Store.ignoreClipUntil) return
        Store.log("copy signal: $src")
        h.removeCallbacks(clipRun)
        h.postDelayed(clipRun, 350)
    }

    private fun hasMediaPerm(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun checkShot() {
        try {
            if (!hasMediaPerm()) return
            val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val since = System.currentTimeMillis() / 1000 - 20
            contentResolver.query(
                base, proj, MediaStore.Images.Media.DATE_ADDED + ">=?", arrayOf(since.toString()),
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    val path = c.getString(2) ?: ""
                    val bucket = c.getString(3) ?: ""
                    if (!(name.contains("screenshot", true) || path.contains("screenshot", true) ||
                            bucket.contains("screenshot", true))) continue
                    val fresh = synchronized(seen) { seen.add(id) }
                    if (fresh) Store.addImage(ContentUris.withAppendedId(base, id))
                }
            }
        } catch (e: Exception) {
        }
    }

    // ───────────────────────── accessibility: paste + copy hint ─────────────────────────

    private val copyWords = setOf("copy", "copied", "cut", "copiar", "cortar", "copia", "copier", "kopieren")
    private val copyMarks = listOf("复制", "複製", "コピー", "복사", "कॉपी", "نسخ", "копир")

    /** Called by the host for every accessibility event: notices the "Copy" button / copy preview. */
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !autoOn) return
        val pkg = event.packageName?.toString() ?: ""
        if (pkg == packageName) return
        val sb = StringBuilder()
        event.text?.forEach { sb.append(it).append(' ') }
        event.contentDescription?.let { sb.append(it) }
        val t = sb.toString().lowercase()
        val cls = event.className?.toString()?.lowercase() ?: ""
        var hit = false
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            hit = t.split(Regex("[^\\p{L}]+")).any { copyWords.contains(it) } || copyMarks.any { t.contains(it) }
        }
        if (!hit && pkg == "com.android.systemui" && (cls.contains("clip") || t.contains("clip") || t.contains("copied"))) hit = true
        if (hit) onClip("copy button")
    }

    /** Presses "paste" in the field that currently has input focus. */
    private fun doPaste(text: String?): Boolean {
        val root = host.rootInActiveWindow ?: return false
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        if (text == null || !n.isEditable) return false
        val hint = Build.VERSION.SDK_INT >= 26 && n.isShowingHintText
        val cur = if (hint) "" else (n.text?.toString() ?: "")
        var s = n.textSelectionStart
        var e = n.textSelectionEnd
        if (s < 0) s = cur.length
        if (e < 0) e = cur.length
        val a = minOf(s, e).coerceIn(0, cur.length)
        val b = maxOf(s, e).coerceIn(0, cur.length)
        val merged = cur.substring(0, a) + text + cur.substring(b)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val pos = a + text.length
            val sel = Bundle()
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }
}
