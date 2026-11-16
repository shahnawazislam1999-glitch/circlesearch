package com.personal.circlesearch

import android.content.ComponentName
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var statusCard: MaterialCardView
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var btnEnable: MaterialButton
    private lateinit var btnAppInfo: MaterialButton

    private lateinit var textBattery: TextView
    private lateinit var btnBattery: MaterialButton
    private lateinit var btnNotif: MaterialButton
    private lateinit var textOverlay: TextView
    private lateinit var btnOverlay: MaterialButton

    private lateinit var textAreaStatus: TextView
    private lateinit var btnRemoveArea: MaterialButton

    private lateinit var handlePreview: HandlePreviewView
    private lateinit var shortcutList: LinearLayout
    private lateinit var btnAddApp: MaterialButton
    private lateinit var textShortcutCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)

        statusCard = findViewById(R.id.statusCard)
        statusTitle = findViewById(R.id.statusTitle)
        statusBody = findViewById(R.id.statusBody)
        btnEnable = findViewById(R.id.btnEnable)
        btnAppInfo = findViewById(R.id.btnAppInfo)

        // PROMPT_SEARCH: prompt box + on/off switch (off = picture-only search, text is kept).
        val switchPrompt: MaterialSwitch = findViewById(R.id.switchPrompt)
        val editPrompt: EditText = findViewById(R.id.editPrompt)
        val textPromptHint: TextView = findViewById(R.id.textPromptHint)
        fun refreshPromptHint() {
            val on = prefs.promptEnabled
            textPromptHint.text = if (on) "ON: this text is searched together with the picture every time."
            else "OFF: only the picture is searched. Your text stays saved."
            editPrompt.alpha = if (on) 1f else 0.5f
        }
        editPrompt.setText(prefs.promptText)
        switchPrompt.isChecked = prefs.promptEnabled
        refreshPromptHint()
        editPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { prefs.promptText = s?.toString() ?: "" }
        })
        switchPrompt.setOnCheckedChangeListener { _, checked ->
            prefs.promptEnabled = checked
            refreshPromptHint()
        }

        val switchActive: MaterialSwitch = findViewById(R.id.switchActive)
        textBattery = findViewById(R.id.textBattery)
        btnBattery = findViewById(R.id.btnBattery)
        btnNotif = findViewById(R.id.btnNotif)
        switchActive.isChecked = prefs.serviceActive
        switchActive.setOnCheckedChangeListener { _, checked -> prefs.serviceActive = checked }
        textOverlay = findViewById(R.id.textOverlay)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnOverlay.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
        btnBattery.setOnClickListener { requestBatteryExemption() }
        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            }
        }
        // Ask once on first launch so the foreground notification can show.
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }

        val switchBubble: MaterialSwitch = findViewById(R.id.switchBubble)
        val sliderOpacity: Slider = findViewById(R.id.sliderOpacity)
        val textOpacity: TextView = findViewById(R.id.textOpacity)
        val sliderSize: Slider = findViewById(R.id.sliderSize)
        val textSize: TextView = findViewById(R.id.textSize)
        val switchGesture: MaterialSwitch = findViewById(R.id.switchGesture)
        val rgLens: RadioGroup = findViewById(R.id.rgLens)
        val btnTry: MaterialButton = findViewById(R.id.btnTry)

        // Initial values (set before listeners so nothing is written back).
        switchBubble.isChecked = prefs.bubbleEnabled
        val opacityPct = (prefs.opacity * 100f).roundToInt().coerceIn(10, 100)
        sliderOpacity.value = opacityPct.toFloat()
        textOpacity.text = "$opacityPct%"
        val sizeDp = prefs.bubbleSizeDp.coerceIn(32, 72)
        sliderSize.value = sizeDp.toFloat()
        textSize.text = "$sizeDp dp"
        handlePreview = findViewById(R.id.handlePreview)
        shortcutList = findViewById(R.id.shortcutList)
        btnAddApp = findViewById(R.id.btnAddApp)
        textShortcutCount = findViewById(R.id.textShortcutCount)
        btnAddApp.setOnClickListener { showAppPicker() }

        val sliderHeight: Slider = findViewById(R.id.sliderHandleHeight)
        val textHeight: TextView = findViewById(R.id.textHandleHeight)
        // Slider step is 2 dp starting at 24, so snap the stored value onto that grid.
        val heightDp = Prefs.MIN_HANDLE_DP + (prefs.handleHeightDp - Prefs.MIN_HANDLE_DP) / 2 * 2
        sliderHeight.value = heightDp.toFloat()
        textHeight.text = "$heightDp dp"
        sliderHeight.setLabelFormatter { "${it.toInt()} dp" }
        sliderHeight.addOnChangeListener { _, value, fromUser ->
            textHeight.text = "${value.toInt()} dp"
            if (fromUser) {
                prefs.handleHeightDp = value.toInt()
                handlePreview.bind(prefs)
            }
        }

        val sliderStrip: Slider = findViewById(R.id.sliderStripOpacity)
        val textStrip: TextView = findViewById(R.id.textStripOpacity)
        val stripPct = (prefs.stripOpacity * 100f).roundToInt().coerceIn(0, 100)
        sliderStrip.value = stripPct.toFloat()
        textStrip.text = "$stripPct%"
        sliderStrip.setLabelFormatter { "${it.toInt()}%" }
        sliderStrip.addOnChangeListener { _, value, fromUser ->
            textStrip.text = "${value.toInt()}%"
            if (fromUser) {
                prefs.stripOpacity = value / 100f
                handlePreview.bind(prefs)
            }
        }
        switchGesture.isChecked = prefs.gestureStrip
        rgLens.check(if (prefs.lensMode == Prefs.WEB_WINDOW) R.id.rbWebWindow else R.id.rbLensApp)

        switchBubble.setOnCheckedChangeListener { _, checked -> prefs.bubbleEnabled = checked }

        sliderOpacity.setLabelFormatter { "${it.toInt()}%" }
        sliderOpacity.addOnChangeListener { _, value, fromUser ->
            textOpacity.text = "${value.toInt()}%"
            if (fromUser) prefs.opacity = value / 100f
        }

        sliderSize.setLabelFormatter { "${it.toInt()} dp" }
        sliderSize.addOnChangeListener { _, value, fromUser ->
            textSize.text = "${value.toInt()} dp"
            if (fromUser) prefs.bubbleSizeDp = value.toInt()
        }

        switchGesture.setOnCheckedChangeListener { _, checked -> prefs.gestureStrip = checked }

        rgLens.setOnCheckedChangeListener { _, id ->
            prefs.lensMode = if (id == R.id.rbWebWindow) Prefs.WEB_WINDOW else Prefs.LENS_APP
        }

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnAppInfo.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        textAreaStatus = findViewById(R.id.textAreaStatus)
        btnRemoveArea = findViewById(R.id.btnRemoveArea)
        val btnSetArea: MaterialButton = findViewById(R.id.btnSetArea)
        btnSetArea.setOnClickListener {
            if (CircleAccessibilityService.instance == null) {
                Toast.makeText(this, "Turn on the Circle Search accessibility service first", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Go to the screen you want. The area picker opens in 5 seconds.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
                Handler(Looper.getMainLooper()).postDelayed({
                    CircleAccessibilityService.instance?.startAreaPick()
                }, 5000)
            }
        }
        btnRemoveArea.setOnClickListener {
            prefs.clearArea()
            updateAreaStatus()
            Toast.makeText(this, "Saved area removed", Toast.LENGTH_SHORT).show()
        }

        btnTry.setOnClickListener {
            if (CircleAccessibilityService.instance == null) {
                Toast.makeText(this, "Turn on the Circle Search accessibility service first", Toast.LENGTH_LONG).show()
            } else {
                moveTaskToBack(true)
                Handler(Looper.getMainLooper()).postDelayed({
                    CircleAccessibilityService.instance?.startSelection()
                }, 450)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.pruneUninstalled(packageManager)
        renderShortcuts()
        updateStatus()
        updateBackgroundStatus()
        updateAreaStatus()
    }

    private fun updateAreaStatus() {
        val a = prefs.savedArea()
        if (a == null) {
            textAreaStatus.text = "No area saved - the handle opens the lasso."
            btnRemoveArea.visibility = android.view.View.GONE
        } else {
            val w = ((a.right - a.left) * 100f).roundToInt()
            val h = ((a.bottom - a.top) * 100f).roundToInt()
            val orient = if (prefs.areaLandscape) "landscape" else "portrait"
            textAreaStatus.text = "Area saved ($w% x $h% of the $orient screen) - the handle searches it."
            btnRemoveArea.visibility = android.view.View.VISIBLE
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateBackgroundStatus()
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun updateBackgroundStatus() {
        val ok = batteryUnrestricted()
        textBattery.text = if (ok) "Battery: unrestricted - Android won't stop the app."
        else "Battery: restricted - Android may kill the app. Tap below to fix."
        btnBattery.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE
        val overlayOk = Settings.canDrawOverlays(this)
        textOverlay.text = if (overlayOk) "Display over other apps: allowed - Lens always opens from the edge handle."
        else "Display over other apps: off. Allow it if Lens doesn't open when you tap the edge handle with a saved area."
        btnOverlay.visibility = if (overlayOk) android.view.View.GONE else android.view.View.VISIBLE
        btnNotif.visibility = if (notificationsGranted()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun isServiceEnabled(): Boolean {
        if (CircleAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val component = ComponentName(this, CircleAccessibilityService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun updateStatus() {
        val on = isServiceEnabled()
        val bgAttr = if (on) com.google.android.material.R.attr.colorPrimaryContainer
        else com.google.android.material.R.attr.colorErrorContainer
        val fgAttr = if (on) com.google.android.material.R.attr.colorOnPrimaryContainer
        else com.google.android.material.R.attr.colorOnErrorContainer
        statusCard.setCardBackgroundColor(MaterialColors.getColor(statusCard, bgAttr))
        val fg = MaterialColors.getColor(statusCard, fgAttr)
        statusTitle.setTextColor(fg)
        statusBody.setTextColor(fg)

        if (on) {
            statusTitle.text = "Ready"
            statusBody.text = "The accessibility service is running. Tap the bubble to circle and search."
            btnEnable.text = "Accessibility settings"
            btnAppInfo.visibility = android.view.View.GONE
        } else {
            statusTitle.text = "One-time setup needed"
            statusBody.text = "Turn on \"Circle Search\" in Accessibility settings. It is how the app takes a " +
                "screenshot and shows the bubble. If the switch is greyed out, open App info, tap the " +
                "three-dot menu and choose \"Allow restricted settings\", then try again."
            btnEnable.text = "Open accessibility settings"
            btnAppInfo.visibility = android.view.View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- app shortcuts (edge handles)

    private data class AppEntry(val label: String, val pkg: String, val icon: Drawable)

    private class AppAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
        var items: List<AppEntry> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: inflater.inflate(R.layout.item_app, parent, false)
            val e = items[position]
            v.findViewById<ImageView>(R.id.appIcon).setImageDrawable(e.icon)
            v.findViewById<TextView>(R.id.appLabel).text = e.label
            return v
        }
    }

    /** Rebuilds the shortcut rows (default search handle first) and the live preview. */
    private fun renderShortcuts() {
        shortcutList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        fun row(icon: Drawable?, label: String, sub: String, color: Int, onColor: (Int) -> Unit, onDelete: (() -> Unit)?) {
            val v = inflater.inflate(R.layout.item_shortcut, shortcutList, false)
            val iconView: ImageView = v.findViewById(R.id.rowIcon)
            if (icon != null) iconView.setImageDrawable(icon)
            else {
                iconView.setImageResource(R.drawable.ic_search)
                iconView.setPadding(dpInt(8), dpInt(8), dpInt(8), dpInt(8))
                iconView.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(iconView, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
            v.findViewById<TextView>(R.id.rowLabel).text = label
            v.findViewById<TextView>(R.id.rowSub).text = sub
            ColorPickerDialog.style(v.findViewById(R.id.rowColor), color, false)
            v.findViewById<View>(R.id.rowColorHit).setOnClickListener {
                ColorPickerDialog.show(this, "Colour for $label", color, "Save") { c ->
                    onColor(c)
                    renderShortcuts()
                }
            }
            val del: ImageButton = v.findViewById(R.id.rowDelete)
            if (onDelete == null) del.visibility = View.INVISIBLE else del.setOnClickListener { onDelete() }
            shortcutList.addView(v)
        }

        row(
            null, "Circle Search", "Default handle - lasso or saved area",
            prefs.searchHandleColor, { prefs.searchHandleColor = it }, null
        )
        val list = prefs.shortcuts()
        for (s in list) {
            val icon = try { packageManager.getApplicationIcon(s.packageName) } catch (_: Throwable) { null }
            row(
                icon, s.label, "Opens in a floating window", s.color,
                { prefs.setShortcutColor(s.id, it) },
                { removeShortcut(s) }
            )
        }
        textShortcutCount.text = "${list.size} / ${Prefs.MAX_SHORTCUTS} apps"
        val full = list.size >= Prefs.MAX_SHORTCUTS
        btnAddApp.isEnabled = !full
        btnAddApp.text = if (full) "Maximum reached" else "Add app shortcut"
        handlePreview.bind(prefs)
    }

    /** Deleting a shortcut deletes its edge handle too; UNDO brings both back. */
    private fun removeShortcut(s: AppShortcut) {
        val index = prefs.shortcuts().indexOfFirst { it.id == s.id }
        prefs.removeShortcut(s.id)
        renderShortcuts()
        Snackbar.make(findViewById(android.R.id.content), "${s.label} removed", Snackbar.LENGTH_LONG)
            .setAction("UNDO") {
                prefs.insertShortcut(index, s)
                renderShortcuts()
            }
            .show()
    }

    private fun showAppPicker() {
        if (prefs.shortcuts().size >= Prefs.MAX_SHORTCUTS) {
            Toast.makeText(this, "You can add up to ${Prefs.MAX_SHORTCUTS} app shortcuts", Toast.LENGTH_SHORT).show()
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val search: EditText = view.findViewById(R.id.pickerSearch)
        val listView: ListView = view.findViewById(R.id.pickerList)
        val progress: View = view.findViewById(R.id.pickerProgress)
        val empty: TextView = view.findViewById(R.id.pickerEmpty)
        val adapter = AppAdapter(layoutInflater)
        listView.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Add app shortcut")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()

        var all: List<AppEntry> = emptyList()
        fun applyFilter() {
            val q = search.text?.toString().orEmpty().trim().lowercase()
            adapter.items = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
            empty.visibility = if (adapter.count == 0 && progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })
        listView.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapter.items.getOrNull(pos) ?: return@setOnItemClickListener
            dialog.dismiss()
            chooseColorAndAdd(entry)
        }
        dialog.show()

        // Loading icons can take a moment, so do it off the main thread.
        val taken = prefs.shortcuts().map { it.packageName }.toSet()
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val loaded: List<AppEntry> = try {
                pm.queryIntentActivities(launcher, 0)
                    .mapNotNull { ri ->
                        val pkg = ri.activityInfo.packageName
                        if (pkg == packageName || pkg in taken) null
                        else AppEntry(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm))
                    }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
            } catch (_: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                all = loaded
                progress.visibility = View.GONE
                applyFilter()
            }
        }.start()
    }

    /** Step 2: choose the handle colour (pre-filled from the app icon), then add the shortcut. */
    private fun chooseColorAndAdd(app: AppEntry) {
        val fallback = ColorPickerDialog.PRESETS[10]
        val suggested = try {
            IconColor.dominant(packageManager.getApplicationIcon(app.pkg), fallback)
        } catch (_: Throwable) {
            fallback
        }
        ColorPickerDialog.show(this, "Handle colour for ${app.label}", suggested, "Add") { color ->
            if (prefs.addShortcut(AppShortcut.create(app.pkg, app.label, color))) {
                renderShortcuts()
            } else {
                Toast.makeText(this, "Couldn't add ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
