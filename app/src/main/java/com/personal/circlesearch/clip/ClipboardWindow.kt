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
import android.graphics.Rect
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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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
    private var searchRow: LinearLayout? = null
    private var compact = false
    private var confirmBulk = false
    private var searchBox: EditText? = null
    private var titleTv: TextView? = null
    private var subTv: TextView? = null
    private var panBtn: ImageView? = null
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
    private var actionFor: String? = null            // list card whose long-press action bar is open
    private var noteBarFor: Item? = null             // board note whose long-press action bar is open
    private var sep = 0
    private var board: StickyBoardView? = null
    private var wrap: FrameLayout? = null
    private var menuCard: View? = null
    private var menuScroll: ScrollView? = null
    private var noteBar: HorizontalScrollView? = null
    private var drawPill: LinearLayout? = null
    private var moreBtn: ImageView? = null
    private var colorPick = false
    private var confirmNoteDel = false
    private fun readingOn() = Store.prefs.getBoolean("reading", false)
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
        filter = if (lf == "all" || lf == "pinned" || lf == "fav" || lf == "used" || Store.groups.any { it.id == lf }) lf else "all"
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
        clearPanelRefs()
        confirmBulk = false
        multi = false; selected.clear(); pendingDelete = null
        onClosed()
    }

    private fun clearPanelRefs() {
        panel = null; panelLp = null; body = null; multiBar = null; searchBox = null; searchRow = null
        titleTv = null; subTv = null; panBtn = null
        editorOpen = false; actionFor = null; noteBarFor = null
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

    /** Re-fit the full-screen panel after rotation, without rebuilding it. */
    private fun refitFull() {
        val p = panel ?: return
        val lp = panelLp ?: return
        if (!isFull()) return
        val (sw, sh) = screenWH()
        lp.x = 0; lp.y = 0; lp.width = sw
        fullH = sh
        shortH = maxOf(minOf(fullH, (sh * 0.42f).toInt()), dp(200))
        lp.height = if ((lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) shortH else fullH
        try { wm.updateViewLayout(p, lp) } catch (e: Exception) {}
    }

    /** Full screen: the panel covers the status bar, so only the camera cut-out / nav bar are kept clear. */
    private fun applyFullInsets(ins: WindowInsets) {
        val r = panel ?: return
        val pd = dp(if (compact) 6 else 10)
        if (!isFull()) { r.setPadding(pd, pd, pd, pd); return }
        val cut = ins.getInsets(WindowInsets.Type.displayCutout())
        val nav = ins.getInsets(WindowInsets.Type.navigationBars())
        r.setPadding(pd + cut.left, pd + cut.top, pd + cut.right, pd + maxOf(cut.bottom, nav.bottom))
    }

    private fun saveScroll() {
        (body?.getChildAt(0) as? ScrollView)?.let { scrollMap[filter] = it.scrollY }
    }

    private fun rebuildPanel() {
        hideKb()
        saveScroll()
        dropBoard()
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        clearPanelRefs()
        buildPanel()
    }

    private fun curGroup(): String? = if (filter == "all" || filter == "pinned" || filter == "used") null else filter

    private fun buildPanel() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val full = isFull()
        val (rw, rh) = screenWH()
        val w = if (full) rw else Store.prefs.getInt("pw", minOf(dp(340), sw - dp(16))).coerceIn(dp(150), sw)
        fullH = if (full) rh else Store.prefs.getInt("ph", (sh * 0.62f).toInt()).coerceIn(dp(140), sh)
        shortH = maxOf(minOf(fullH, ((if (full) rh else sh) * 0.42f).toInt()), dp(200))
        compact = w < dp(300)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(C.BG, if (full) 0 else 18)
        val pd = dp(if (compact) 6 else 10)
        root.setPadding(pd, pd, pd, pd)
        root.elevation = dp(8).toFloat()
        root.setOnApplyWindowInsetsListener { _, ins -> applyFullInsets(ins); ins }

        // header: [back] [pin] [view name + count ......] [hand] [+] [⋮ menu]
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
            // the title shows which list you are looking at; tap it to open the menu, drag it to move the window
            val tb = LinearLayout(this)
            tb.orientation = LinearLayout.VERTICAL
            tb.setPadding(dp(2), 0, dp(6), 0)
            val title = label("", 15f)
            title.setTypeface(title.typeface, Typeface.BOLD)
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            val subT = label("", 10f, C.SUB)
            subT.maxLines = 1
            tb.addView(title)
            tb.addView(subT)
            head.addView(tb, LP(0, WRAP, 1f))
            titleTv = title
            subTv = subT
            if (!full) attachDrag(tb, { panel }, { panelLp }, { toggleMenu() }) { savePanelGeometry() }
            else tb.setOnClickListener { toggleMenu() }
        } else {
            val sp = View(this)
            head.addView(sp, LP(0, 1, 1f))
            if (!full) attachDrag(sp, { panel }, { panelLp }, null) { savePanelGeometry() }
        }
        if (!full) attachDrag(pinIc, { panel }, { panelLp }, null) { savePanelGeometry() }

        // quick switch (sticky note view only): move cards <-> pan only
        val panB = ic(R.drawable.clip_ic_hand, C.TEXT)
        panB.setOnClickListener { toggleNav() }
        head.addView(panB)
        panBtn = panB

        val addB = ic(R.drawable.clip_ic_add, C.ACCENT)
        addB.setOnClickListener {
            hideMenu()
            showEditor("New text", "", curGroup(), true, false, null) { t, g ->
                Store.addText(t, g)
            }
        }
        head.addView(addB)
        val moreB = ic(R.drawable.clip_ic_more, if (sticky()) C.ACCENT else C.SUB)
        moreB.setOnClickListener { toggleMenu() }
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

        // everything under the header lives in [wrap] so the ⋮ menu and the action bars can float over it
        val wrapV = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        wrapV.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        wrap = wrapV

        // search box: hidden until Search is picked in the ⋮ menu
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

        // floating action bar for the sticky note you pressed and held (appears at the bottom)
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

        // small pill shown only while the pen / eraser is active: Undo + Done
        val dpill = LinearLayout(this)
        dpill.orientation = LinearLayout.HORIZONTAL
        dpill.gravity = Gravity.CENTER_VERTICAL
        dpill.background = rounded(0xEE151922.toInt(), 16)
        dpill.setPadding(dp(5), dp(4), dp(5), dp(4))
        dpill.visibility = View.GONE
        val undoB = actionBtn(R.drawable.clip_ic_undo, "Undo")
        undoB.setOnClickListener { board?.undo() }
        val doneB = actionBtn(R.drawable.clip_ic_check, "Done", Color.WHITE, C.ACCENT)
        doneB.setOnClickListener { setTool(Store.prefs.getInt("bnav", StickyBoardView.TOOL_MOVE)) }
        dpill.addView(undoB, LP(WRAP, WRAP).apply { rightMargin = dp(6) })
        dpill.addView(doneB, LP(WRAP, WRAP))
        drawPill = dpill
        wrapV.addView(dpill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
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
            // really full screen: start at the very top and cover the status bar and the camera cut-out area
            lp.x = 0
            lp.y = 0
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            lp.x = Store.prefs.getInt("px", (sw - w) / 2).coerceIn(0, maxOf(0, sw - w))
            lp.y = Store.prefs.getInt("py", dp(60)).coerceIn(0, maxOf(0, sh - dp(120)))
        }
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        panel = root
        panelLp = lp
        wm.addView(root, lp)
        applyPanelAlpha()

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

    /** Switches to another list (All, Favourites, Pinned, Top used or a group). */
    private fun setFilter(key: String) {
        if (filter == key) return
        saveScroll()
        filter = key
        actionFor = null
        Store.prefs.edit().putString("lfilter", key).apply()
        if (!editorOpen) body?.removeAllViews()   // so the new tab restores ITS own saved position
        renderList()
    }

    private fun groupMenu(g: Group) {
        showEditor("Rename group", g.name, null, false, true, "Delete group" to {
            Store.deleteGroup(g.id)
            if (filter == g.id) filter = "all"
            Unit
        }) { t, _ -> Store.renameGroup(g, t) }
    }

    private fun filterName(): String = when (filter) {
        "all" -> "All cards"
        "fav" -> "Favourites"
        "pinned" -> "Pinned"
        "used" -> "Top used"
        else -> Store.groups.find { it.id == filter }?.name ?: "All cards"
    }

    /** Header title = the list you are in, with a card count underneath. */
    private fun updateHeader(count: Int) {
        titleTv?.text = filterName()
        subTv?.text = if (query.trim().isNotEmpty()) "$count found" else if (count == 1) "1 card" else "$count cards"
        updatePanBtn()
    }

    private fun filtered(): List<Item> {
        val q = query.trim().lowercase()
        var l = Store.items.filter {
            when (filter) {
                "all" -> true
                "pinned" -> it.pinned
                "fav" -> it.fav
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
        updateHeader(list.size)
        if (editorOpen) return
        if (sticky()) { renderBoard(list); return }
        // remember where the user was so a paste/refresh doesn't jump back to the top
        val oldScroll = if (resetScroll) 0 else ((b.getChildAt(0) as? ScrollView)?.scrollY ?: scrollMap[filter] ?: 0)
        scrollMap[filter] = oldScroll
        b.removeAllViews()
        if (list.isEmpty()) {
            val e = label(
                if (filter == "fav") "No favourites yet.\nPress and hold a card, then tap Favourite."
                else "Nothing here yet.\nTap + to add text, or turn on Auto detect.", 13f, C.SUB
            )
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

    private fun badge(res: Int, tint: Int): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(tint)
        v.layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(4) }
        return v
    }

    /**
     * A clean card: just the text (or picture). Tap = paste. Press and hold = the action bar opens right below
     * the card (Pin / Favourite / Edit / Copy / Expand / Select / Delete).
     */
    private fun card(x: Item): View {
        val sel = selected.contains(x.id)
        val pend = pendingDelete == x.id
        val open = x.id in expanded || readingOn()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        val tone = if (x.color != 0) x.color else C.CARD
        row.background = if (pend) cardBg(0xFFFF9AA2.toInt(), null) else cardBg(tone, if (sel) 0xFF3B5BFF.toInt() else null)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        if (x.image != null) {
            // full picture, never cropped; decoded at full quality (long-press -> View to zoom to actual size)
            val iv = ImageView(this)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.maxHeight = dp(560)
            val f = Store.imageFile(x)
            ImgCache.load(x.id, f, 1400, 3500) { bmp -> if (bmp != null) iv.setImageBitmap(bmp) }
            mid.addView(iv, LP(MATCH, WRAP))
            if (x.text.isNotBlank()) {
                val cl = label(x.text, 12f, INK_DARK)
                cl.maxLines = if (open) Int.MAX_VALUE else 2
                cl.ellipsize = TextUtils.TruncateAt.END
                cl.setPadding(0, dp(4), 0, 0)
                mid.addView(cl)
            }
        } else {
            val t = label(x.text, if (compact) 12f else 13f, INK_DARK)
            if (!open) {
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
        if (x.pinned || x.fav || meta.isNotEmpty()) {
            val mr = LinearLayout(this)
            mr.orientation = LinearLayout.HORIZONTAL
            mr.gravity = Gravity.CENTER_VERTICAL
            if (x.pinned) mr.addView(badge(R.drawable.clip_ic_pin, 0xFFD9480F.toInt()))
            if (x.fav) mr.addView(badge(R.drawable.clip_ic_star, 0xFFE08A00.toInt()))
            if (meta.isNotEmpty()) mr.addView(label(meta, 10f, INK_SUB))
            mid.addView(mr, LP(MATCH, WRAP).apply { topMargin = dp(3) })
        }
        row.addView(mid, LP(MATCH, WRAP))

        row.setOnClickListener {
            if (multi) {
                if (!selected.add(x.id)) selected.remove(x.id)
                updateMultiBar()
                renderList()
            } else if (actionFor != null) {
                actionFor = null          // first tap just closes the action bar
                renderList()
            } else paste(x)
        }
        row.setOnLongClickListener {
            if (multi) {
                if (!selected.add(x.id)) selected.remove(x.id)
                updateMultiBar()
            } else {
                row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                actionFor = if (actionFor == x.id) null else x.id
                pendingDelete = null
            }
            renderList()
            true
        }
        if (actionFor != x.id || multi) return row

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(row, LP(MATCH, WRAP))
        box.addView(actionStrip(x), LP(MATCH, WRAP).apply { topMargin = dp(4) })
        box.post { box.requestRectangleOnScreen(Rect(0, 0, box.width, box.height), false) }   // keep the bar in view
        return box
    }

    /** The row of actions that opens under a card after a long press. */
    private fun actionStrip(x: Item): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.background = rounded(0xFF10131A.toInt(), 14)
        hs.setPadding(dp(6), dp(5), dp(6), dp(5))
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        hs.addView(r, FrameLayout.LayoutParams(WRAP, WRAP))
        fun add(v: View) { r.addView(v, LP(WRAP, WRAP).apply { rightMargin = dp(6) }) }

        val pin = actionBtn(R.drawable.clip_ic_pin, if (x.pinned) "Unpin" else "Pin", if (x.pinned) C.AMBER else C.TEXT)
        pin.setOnClickListener { Store.togglePin(x) }
        add(pin)

        val fav = actionBtn(
            if (x.fav) R.drawable.clip_ic_star else R.drawable.clip_ic_star_border,
            if (x.fav) "Unfavourite" else "Favourite", if (x.fav) C.AMBER else C.TEXT
        )
        fav.setOnClickListener { Store.toggleFav(x) }
        add(fav)

        val ed = actionBtn(R.drawable.clip_ic_edit, "Edit")
        ed.setOnClickListener { actionFor = null; editItem(x) }
        add(ed)

        val cp = actionBtn(R.drawable.clip_ic_copy, "Copy")
        cp.setOnClickListener { copyOnly(x); actionFor = null; renderList() }
        add(cp)

        if (x.image != null) {
            val vw = actionBtn(R.drawable.clip_ic_fullscreen, "View")
            vw.setOnClickListener { actionFor = null; showImageViewer(x) }
            add(vw)
        } else if (!readingOn()) {
            val isOpen = x.id in expanded
            val ex = actionBtn(
                if (isOpen) R.drawable.clip_ic_collapse else R.drawable.clip_ic_expand,
                if (isOpen) "Collapse" else "Expand"
            )
            ex.setOnClickListener {
                if (!expanded.add(x.id)) expanded.remove(x.id)
                renderList()
            }
            add(ex)
        }

        val sl = actionBtn(R.drawable.clip_ic_multi, "Select")
        sl.setOnClickListener {
            actionFor = null
            multi = true
            selected.clear()
            selected.add(x.id)
            updateMultiBar()
            renderList()
        }
        add(sl)

        val pend = pendingDelete == x.id
        val del = actionBtn(R.drawable.clip_ic_delete, if (pend) "Tap again" else "Delete", C.RED)
        del.setOnClickListener {
            if (pendingDelete == x.id) {
                pendingDelete = null
                actionFor = null
                selected.remove(x.id)
                Store.delete(x)
            } else {
                pendingDelete = x.id
                renderList()
                h.postDelayed({ if (pendingDelete == x.id) { pendingDelete = null; renderList() } }, 2500)
            }
        }
        add(del)
        return hs
    }

    private fun editItem(x: Item) {
        actionFor = null
        noteBarFor = null
        if (x.image == null) {
            showEditor("Edit text", x.text, x.groupId, true, false, null, false, null, x) { t, g -> Store.update(x, t, g) }
        } else {
            showEditor("Edit image card", x.text, x.groupId, true, false, null, true, x, x) { t, g -> Store.update(x, t.trim(), g) }
        }
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
        noteBar?.visibility = View.GONE
        drawPill?.visibility = View.GONE
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

    private fun toggleMenu() { if (menuCard != null) hideMenu() else showMenu() }

    private fun hideMenu() {
        menuCard?.let { (it.parent as? FrameLayout)?.removeView(it) }
        menuCard = null
        menuScroll = null
    }

    /** Rebuilds the open menu (e.g. after picking a pen colour) and keeps its scroll position. */
    private fun refreshMenu() {
        if (menuCard == null) return
        val y = menuScroll?.scrollY ?: 0
        showMenu()
        val ns = menuScroll
        ns?.post { ns.scrollTo(0, y) }
    }

    /**
     * Everything that used to sit above the cards lives here now: the list switcher (All / Favourites / Pinned /
     * Top used / groups), Paste all, the view options and - in sticky note view - the board tools.
     */
    private fun showMenu() {
        val w = wrap ?: return
        hideMenu()
        val layer = FrameLayout(this)
        layer.setOnClickListener { hideMenu() }          // tapping outside the card closes it

        val cardW = minOf(dp(320), w.width - dp(8)).coerceAtLeast(dp(180))
        val sc = MaxHeightScroll(this, (w.height - dp(8)).coerceAtLeast(dp(160)))
        sc.background = rounded(C.CARD2, 14)
        sc.elevation = dp(10).toFloat()
        sc.isClickable = true                            // swallow taps so they never reach the layer behind
        sc.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), dp(2), dp(8), dp(8))
        sc.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))

        fun row(res: Int, text: String, on: Boolean?, act: () -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(2), dp(2), dp(6), dp(2))
            r.addView(iconView(res, if (on == true) C.ACCENT else C.TEXT, 18, 5))
            val tv = label(text, 13f, if (on == true) C.ACCENT else C.TEXT)
            tv.setPadding(dp(4), 0, dp(10), 0)
            r.addView(tv, LP(0, WRAP, 1f))
            if (on != null) {
                r.addView(pill(if (on) "ON" else "OFF", if (on) C.ACCENT else C.CARD, if (on) Color.WHITE else C.SUB, true))
            }
            r.setOnClickListener { hideMenu(); act() }
            col.addView(r, LP(MATCH, WRAP))
        }

        // ── show: which list
        col.addView(sectionLabel("Show"))
        val chips = FlowLayout(this, dp(6), dp(6))
        fun chip(text: String, key: String, g: Group?) {
            val on = filter == key
            val t = tiny(text, if (on) C.ACCENT else C.CARD, if (on) Color.WHITE else C.TEXT)
            t.setOnClickListener { hideMenu(); setFilter(key) }
            if (g != null) t.setOnLongClickListener { hideMenu(); groupMenu(g); true }
            chips.addView(t)
        }
        chip("All", "all", null)
        chip("★ Favourites (" + Store.items.count { it.fav } + ")", "fav", null)
        chip("Pinned", "pinned", null)
        chip("Top used", "used", null)
        Store.groups.forEach { chip(it.name, it.id, it) }
        val addG = tiny("+ Group", C.CARD, C.ACCENT)
        addG.setOnClickListener {
            hideMenu()
            showEditor("New group name", "", null, false, true, null) { t, _ -> Store.addGroup(t) }
        }
        chips.addView(addG)
        col.addView(chips, LP(MATCH, WRAP))

        // ── paste all (list view)
        if (!sticky()) {
            val n = filtered().count { it.image == null }
            if (n > 0) {
                col.addView(sectionLabel("Paste"))
                val pr = LinearLayout(this)
                pr.orientation = LinearLayout.HORIZONTAL
                pr.gravity = Gravity.CENTER_VERTICAL
                val go = tiny("Paste all ($n)", C.ACCENT, Color.WHITE)
                go.setOnClickListener { hideMenu(); pasteTexts(filtered()) }
                val sp = tiny("Join: " + seps[sep].first, C.CARD)
                sp.setOnClickListener { sep = (sep + 1) % seps.size; sp.text = "Join: " + seps[sep].first }
                pr.addView(go, LP(WRAP, WRAP).apply { rightMargin = dp(6) })
                pr.addView(sp, LP(WRAP, WRAP))
                col.addView(pr, LP(MATCH, WRAP))
            }
        }

        // ── view options
        col.addView(sectionLabel("View"))
        row(R.drawable.clip_ic_sticky, "Sticky note view", sticky()) { toggleSticky() }
        row(R.drawable.clip_ic_read, "Reading mode", readingOn()) { setReading(!readingOn()) }
        row(R.drawable.clip_ic_search, "Search", query.isNotEmpty()) { toggleSearch() }
        if (!sticky()) {
            row(R.drawable.clip_ic_multi, "Select multiple", multi) {
                multi = !multi
                selected.clear()
                actionFor = null
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

        // ── board tools (sticky note view only)
        if (sticky()) {
            col.addView(sectionLabel("Board tools"))
            if (readingOn()) {
                val note = label("Drawing and moving cards are paused while Reading mode is on.", 12f, C.SUB)
                note.setPadding(dp(4), dp(2), dp(4), dp(4))
                col.addView(note, LP(MATCH, WRAP))
            } else {
                val tool = Store.prefs.getInt("btool", StickyBoardView.TOOL_MOVE)
                val curColor = Store.prefs.getInt("bcolor", INK_COLORS[0])
                val curSize = Store.prefs.getFloat("bsize", 3f)

                val tools = FlowLayout(this, dp(6), dp(6))
                fun toolBtn(res: Int, text: String, t: Int) {
                    val on = tool == t
                    val b = actionBtn(res, text, if (on) Color.WHITE else C.TEXT, if (on) C.ACCENT else C.CARD)
                    b.setOnClickListener { hideMenu(); setTool(t) }
                    tools.addView(b)
                }
                toolBtn(R.drawable.clip_ic_hand, "Move cards", StickyBoardView.TOOL_MOVE)
                toolBtn(R.drawable.clip_ic_pan, "Pan only", StickyBoardView.TOOL_PAN)
                toolBtn(R.drawable.clip_ic_edit, "Pen", StickyBoardView.TOOL_PEN)
                toolBtn(R.drawable.clip_ic_eraser, "Eraser", StickyBoardView.TOOL_ERASE)
                val undo = actionBtn(R.drawable.clip_ic_undo, "Undo", C.TEXT, C.CARD)
                undo.setOnClickListener { board?.undo() }
                tools.addView(undo)
                col.addView(tools, LP(MATCH, WRAP))

                col.addView(sectionLabel("Pen colour"))
                val colors = FlowLayout(this, dp(8), dp(8))
                colors.setPadding(dp(4), dp(2), dp(4), dp(2))
                INK_COLORS.forEach { c ->
                    val d = View(this)
                    d.background = ovalDot(c, if (c == curColor) Color.WHITE else 0x55FFFFFF)
                    d.setOnClickListener {
                        Store.prefs.edit().putInt("bcolor", c).apply()
                        board?.applyInk()
                        val t0 = Store.prefs.getInt("btool", StickyBoardView.TOOL_MOVE)
                        if (t0 == StickyBoardView.TOOL_MOVE || t0 == StickyBoardView.TOOL_PAN) setTool(StickyBoardView.TOOL_PEN)
                        refreshMenu()
                    }
                    colors.addView(d, ViewGroup.LayoutParams(dp(26), dp(26)))
                }
                col.addView(colors, LP(MATCH, WRAP))

                col.addView(sectionLabel("Pen size"))
                val sizes = FlowLayout(this, dp(6), dp(6))
                sizes.setPadding(dp(2), dp(2), dp(2), dp(2))
                INK_SIZES.forEach { sz ->
                    val box = FrameLayout(this)
                    box.background = if (sz == curSize) rounded(C.CARD, 12) else null
                    val dot = View(this)
                    dot.background = ovalDot(C.TEXT, null)
                    val dd = (2 + sz * 1.5f).toInt().coerceAtMost(22)
                    box.addView(dot, FrameLayout.LayoutParams(dp(dd), dp(dd), Gravity.CENTER))
                    box.setOnClickListener {
                        Store.prefs.edit().putFloat("bsize", sz).apply()
                        board?.applyInk()
                        refreshMenu()
                    }
                    sizes.addView(box, ViewGroup.LayoutParams(dp(32), dp(32)))
                }
                col.addView(sizes, LP(MATCH, WRAP))
            }
            val fit = actionBtn(R.drawable.clip_ic_fullscreen, "Fit all notes", C.TEXT, C.CARD)
            fit.setOnClickListener { hideMenu(); board?.fitAll() }
            col.addView(fit, LP(WRAP, WRAP).apply { topMargin = dp(10) })
        }

        layer.addView(sc, FrameLayout.LayoutParams(cardW, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(2)
            rightMargin = dp(2)
        })
        w.addView(layer, FrameLayout.LayoutParams(MATCH, MATCH))
        menuCard = layer
        menuScroll = sc
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

    /** Reading mode: list cards open to full text; sticky notes grow so all of their text shows. */
    private fun setReading(on: Boolean) {
        Store.prefs.edit().putBoolean("reading", on).apply()
        board?.reading = on
        noteBarFor = null
        actionFor = null
        updateNoteBar()
        renderList()
    }

    private fun applyStickyUi() {
        val on = sticky()
        moreBtn?.setColorFilter(if (on) C.ACCENT else C.SUB)
        if (on) {
            multi = false
            selected.clear()
            updateMultiBar()
        } else {
            board?.selected = null
        }
        noteBarFor = null
        actionFor = null
        noteBar?.visibility = View.GONE
        updatePanBtn()
        updateDrawPill()
        renderList()
    }

    private fun dropBoard() {
        hideMenu()
        board?.release()
        board = null
        wrap = null; noteBar = null; drawPill = null; moreBtn = null
        colorPick = false
    }

    private fun renderBoard(list: List<Item>) {
        val b = body ?: return
        Store.ensureLayout()
        var bd = board
        if (bd == null) {
            bd = StickyBoardView(this, object : StickyBoardView.Callbacks {
                override fun onSelect(x: Item?) { colorPick = false; confirmNoteDel = false; noteBarFor = null; updateNoteBar() }
                override fun onPaste(x: Item) { paste(x) }
                override fun onLongPress(x: Item) { colorPick = false; confirmNoteDel = false; noteBarFor = x; updateNoteBar() }
            })
            board = bd
        }
        bd.reading = readingOn()
        bd.notes = list
        if (bd.parent !== b) {
            b.removeAllViews()
            b.addView(bd, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        bd.refresh()
        updateNoteBar()
        updateDrawPill()
    }

    /** Picks the board tool (move cards / pan only / pen / eraser) and keeps every button in step. */
    private fun setTool(t: Int) {
        Store.prefs.edit().putInt("btool", t).apply()
        if (t == StickyBoardView.TOOL_MOVE || t == StickyBoardView.TOOL_PAN) Store.prefs.edit().putInt("bnav", t).apply()
        board?.tool = t
        noteBarFor = null
        updateNoteBar()
        updatePanBtn()
        updateDrawPill()
    }

    /** Header hand button: switches between "move cards" and "pan only" (one finger slides the canvas, cards stay put). */
    private fun toggleNav() {
        if (!sticky() || readingOn()) return
        val t = Store.prefs.getInt("btool", StickyBoardView.TOOL_MOVE)
        setTool(
            when (t) {
                StickyBoardView.TOOL_MOVE -> StickyBoardView.TOOL_PAN
                StickyBoardView.TOOL_PAN -> StickyBoardView.TOOL_MOVE
                else -> Store.prefs.getInt("bnav", StickyBoardView.TOOL_MOVE)      // leaving pen / eraser
            }
        )
    }

    private fun updatePanBtn() {
        val b = panBtn ?: return
        val on = sticky()
        b.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        val t = Store.prefs.getInt("btool", StickyBoardView.TOOL_MOVE)
        val pan = t == StickyBoardView.TOOL_PAN
        val drawing = t == StickyBoardView.TOOL_PEN || t == StickyBoardView.TOOL_ERASE
        b.setImageResource(if (pan) R.drawable.clip_ic_pan else R.drawable.clip_ic_hand)
        b.setColorFilter(if (pan) Color.WHITE else if (drawing) C.SUB else C.TEXT)
        b.background = rounded(if (pan) C.ACCENT else C.CARD2, 14)
        b.alpha = if (readingOn()) 0.4f else 1f
    }

    private fun updateDrawPill() {
        val p = drawPill ?: return
        val t = Store.prefs.getInt("btool", StickyBoardView.TOOL_MOVE)
        val drawing = t == StickyBoardView.TOOL_PEN || t == StickyBoardView.TOOL_ERASE
        p.visibility = if (sticky() && !readingOn() && !editorOpen && drawing) View.VISIBLE else View.GONE
    }

    /** Action bar of the sticky note you pressed and held; it floats at the bottom of the board. */
    private fun updateNoteBar() {
        val nb = noteBar ?: return
        val sel = noteBarFor
        if (!sticky() || editorOpen || sel == null || Store.items.none { it === sel } || board?.selected !== sel) {
            nb.visibility = View.GONE
            return
        }
        val row = nb.getChildAt(0) as? LinearLayout ?: return
        row.removeAllViews()
        nb.visibility = View.VISIBLE
        fun add(v: View) { row.addView(v, LP(WRAP, WRAP).apply { rightMargin = dp(6) }) }
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
        val go = actionBtn(R.drawable.clip_ic_clip, "Paste", Color.WHITE, C.ACCENT)
        go.setOnClickListener { paste(sel) }
        add(go)
        if (sel.image != null) {
            val v = actionBtn(R.drawable.clip_ic_fullscreen, "View")
            v.setOnClickListener { showImageViewer(sel) }
            add(v)
        }
        val cp = actionBtn(R.drawable.clip_ic_copy, "Copy")
        cp.setOnClickListener { copyOnly(sel) }
        add(cp)
        val ed = actionBtn(R.drawable.clip_ic_edit, "Edit")
        ed.setOnClickListener { editItem(sel) }
        add(ed)
        val cl = actionBtn(R.drawable.clip_ic_sticky, "Colour", C.ACCENT)
        cl.setOnClickListener { colorPick = true; updateNoteBar() }
        add(cl)
        val pn = actionBtn(R.drawable.clip_ic_pin, if (sel.pinned) "Unpin" else "Pin", if (sel.pinned) C.AMBER else C.TEXT)
        pn.setOnClickListener { Store.togglePin(sel) }
        add(pn)
        val fv = actionBtn(
            if (sel.fav) R.drawable.clip_ic_star else R.drawable.clip_ic_star_border,
            if (sel.fav) "Unfavourite" else "Favourite", if (sel.fav) C.AMBER else C.TEXT
        )
        fv.setOnClickListener { Store.toggleFav(sel) }
        add(fv)
        val del = actionBtn(R.drawable.clip_ic_delete, if (confirmNoteDel) "Tap again" else "Delete", C.RED)
        del.setOnClickListener {
            if (confirmNoteDel) {
                confirmNoteDel = false
                noteBarFor = null
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
        noteBar?.visibility = View.GONE
        drawPill?.visibility = View.GONE
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
        noteBar?.visibility = View.GONE
        drawPill?.visibility = View.GONE
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
            d.setOnClickListener { closeEditor(); danger.second(); renderList() }
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
