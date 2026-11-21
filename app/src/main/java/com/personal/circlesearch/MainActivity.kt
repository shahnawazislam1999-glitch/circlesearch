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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.personal.circlesearch.clip.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private lateinit var textOverlay: TextView
    private lateinit var btnOverlay: MaterialButton

    private lateinit var textAreaStatus: TextView
    private lateinit var btnRemoveArea: MaterialButton

    private lateinit var handlePreview: HandlePreviewView
    private lateinit var shortcutList: LinearLayout
    private lateinit var btnAddApp: MaterialButton
    private lateinit var textShortcutCount: TextView

    // Clipboard card
    private lateinit var switchClip: MaterialSwitch
    private lateinit var switchClipAuto: MaterialSwitch
    private lateinit var layoutClip: View
    private lateinit var textClipHint: TextView
    private lateinit var textClipAuto: TextView
    private lateinit var textClipStats: TextView
    private var clipSuppress = false

    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) writeClipBackup(uri)
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askClipRestore(uri)
    }

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
        val switchKeepAlive: MaterialSwitch = findViewById(R.id.switchKeepAlive)
        switchKeepAlive.isChecked = prefs.runningNotification
        switchKeepAlive.setOnCheckedChangeListener { _, checked -> prefs.runningNotification = checked }
        val switchAuto: MaterialSwitch = findViewById(R.id.switchAutoEnable)
        switchAuto.isChecked = prefs.autoEnable
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.autoEnable = checked
            updateBackgroundStatus()
            if (checked) SelfEnable.ensureEnabled(this)
        }
        findViewById<MaterialButton>(R.id.btnAutostart).setOnClickListener { openAutostartSettings() }

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
        setupClipboard(switchGesture)
        setupGpt(switchGesture)

        val switchAutoPaste: MaterialSwitch = findViewById(R.id.switchAutoPaste)
        switchAutoPaste.isChecked = prefs.autoPaste
        switchAutoPaste.setOnCheckedChangeListener { _, checked ->
            prefs.autoPaste = checked
            if (!gptSuppress) refreshGpt()
        }
        autoPasteSwitch = switchAutoPaste
        // Auto paste prompt box + switch (off = only the copied text is pasted; the prompt stays saved).
        val switchApPrompt: MaterialSwitch = findViewById(R.id.switchAutoPastePrompt)
        val editApPrompt: EditText = findViewById(R.id.editAutoPastePrompt)
        val textApHint: TextView = findViewById(R.id.textAutoPastePromptHint)
        fun refreshApHint() {
            val on = prefs.autoPastePromptEnabled
            textApHint.text = if (on) "ON: your prompt, a blank line, then the copied text are pasted and sent."
            else "OFF: only the copied text is pasted. Your prompt stays saved."
            editApPrompt.alpha = if (on) 1f else 0.5f
        }
        editApPrompt.setText(prefs.autoPastePrompt)
        switchApPrompt.isChecked = prefs.autoPastePromptEnabled
        refreshApHint()
        editApPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { prefs.autoPastePrompt = s?.toString() ?: "" }
        })
        switchApPrompt.setOnCheckedChangeListener { _, checked ->
            prefs.autoPastePromptEnabled = checked
            refreshApHint()
        }
        findViewById<MaterialButton>(R.id.btnPasteDebug).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("paste debug", PasteSender.readLog(this)))
            Toast.makeText(this, "Debug log copied - paste it to share", Toast.LENGTH_SHORT).show()
        }

        val switchShot: MaterialSwitch = findViewById(R.id.switchShot)
        val switchShotWhole: MaterialSwitch = findViewById(R.id.switchShotWhole)
        val layoutShot: View = findViewById(R.id.layoutShot)
        val textShotHint: TextView = findViewById(R.id.textShotHint)
        fun refreshShot() {
            layoutShot.visibility = if (prefs.shotEnabled) View.VISIBLE else View.GONE
            textShotHint.text = if (prefs.shotEnabled) {
                if (prefs.shotWholeScreen) "ON: tap the screenshot handle to save the whole screen to Pictures/CircleSearch."
                else "ON: tap the screenshot handle to save your saved area (whole screen if none is saved) to Pictures/CircleSearch."
            } else "OFF: switch on to add a screenshot handle to the edge handles."
        }
        switchShot.isChecked = prefs.shotEnabled
        switchShotWhole.isChecked = prefs.shotWholeScreen
        refreshShot()
        switchShot.setOnCheckedChangeListener { _, checked ->
            prefs.shotEnabled = checked
            if (checked && !prefs.gestureStrip) {
                prefs.gestureStrip = true
                switchGesture.isChecked = true
            }
            refreshShot()
            renderShortcuts()
        }
        switchShotWhole.setOnCheckedChangeListener { _, checked ->
            prefs.shotWholeScreen = checked
            refreshShot()
        }

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
        SelfEnable.ensureEnabled(this)
        prefs.pruneUninstalled(packageManager)
        renderShortcuts()
        updateStatus()
        updateBackgroundStatus()
        updateAreaStatus()
        refreshClipboard()
        refreshGpt()
    }

    // ---------------------------------------------------------------- ChatGPT handle (GPT_SEARCH)

    private lateinit var switchGpt: MaterialSwitch
    private lateinit var layoutGpt: View
    private lateinit var textGptHint: TextView
    private lateinit var switchGptCapture: MaterialSwitch
    private lateinit var switchGptPaste: MaterialSwitch
    private var autoPasteSwitch: MaterialSwitch? = null
    private lateinit var textGptCapture: TextView
    private lateinit var layoutGptCapture: View
    private lateinit var editGptPrompt: EditText
    private lateinit var switchGptPrompt: MaterialSwitch
    private lateinit var switchGptSend: MaterialSwitch
    private lateinit var switchGptFloat: MaterialSwitch
    private var gptSuppress = false

    private fun setupGpt(switchGesture: MaterialSwitch) {
        switchGpt = findViewById(R.id.switchGpt)
        layoutGpt = findViewById(R.id.layoutGpt)
        textGptHint = findViewById(R.id.textGptHint)
        switchGptCapture = findViewById(R.id.switchGptCapture)
        switchGptFloat = findViewById(R.id.switchGptFloat)
        switchGptFloat.isChecked = prefs.gptFloating
        switchGptFloat.setOnCheckedChangeListener { _, checked ->
            prefs.gptFloating = checked
            renderShortcuts()
        }
        switchGptPaste = findViewById(R.id.switchGptPaste)
        textGptCapture = findViewById(R.id.textGptCapture)
        layoutGptCapture = findViewById(R.id.layoutGptCapture)
        editGptPrompt = findViewById(R.id.editGptPrompt)
        switchGptPrompt = findViewById(R.id.switchGptPrompt)
        switchGptSend = findViewById(R.id.switchGptSend)

        editGptPrompt.setText(prefs.gptPromptText)
        editGptPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { prefs.gptPromptText = s?.toString() ?: "" }
        })
        switchGptPrompt.setOnCheckedChangeListener { _, checked ->
            if (gptSuppress) return@setOnCheckedChangeListener
            prefs.gptPromptEnabled = checked
            editGptPrompt.alpha = if (checked) 1f else 0.5f
        }
        switchGptSend.setOnCheckedChangeListener { _, checked ->
            if (gptSuppress) return@setOnCheckedChangeListener
            prefs.gptAutoSend = checked
        }
        switchGpt.setOnCheckedChangeListener { _, checked ->
            if (gptSuppress) return@setOnCheckedChangeListener
            prefs.gptEnabled = checked
            if (checked && !prefs.gestureStrip) {
                prefs.gestureStrip = true
                switchGesture.isChecked = true
            }
            renderShortcuts()
            refreshGpt()
        }
        switchGptCapture.setOnCheckedChangeListener { _, checked ->
            if (gptSuppress) return@setOnCheckedChangeListener
            prefs.gptCaptureEnabled = checked
            refreshGpt()
        }
        switchGptPaste.setOnCheckedChangeListener { _, checked ->
            if (gptSuppress) return@setOnCheckedChangeListener
            prefs.gptPasteClipboard = checked
        }
        refreshGpt()
    }

    private fun refreshGpt() {
        if (!this::switchGpt.isInitialized) return
        gptSuppress = true
        val on = prefs.gptEnabled
        switchGpt.isChecked = on
        switchGptPrompt.isChecked = prefs.gptPromptEnabled
        switchGptSend.isChecked = prefs.gptAutoSend
        val cap = prefs.gptCaptureEnabled
        switchGptCapture.isChecked = cap
        switchGptPaste.isChecked = prefs.gptPasteClipboard
        autoPasteSwitch?.let { if (it.isChecked != prefs.autoPaste) it.isChecked = prefs.autoPaste }
        gptSuppress = false
        layoutGptCapture.visibility = if (cap) View.VISIBLE else View.GONE
        switchGptPaste.visibility = if (cap) View.GONE else View.VISIBLE
        (switchGptPaste.parent as? View)?.visibility = if (cap) View.GONE else View.VISIBLE
        textGptCapture.text = if (cap) "ON: the handle captures the screen (or your saved area) and shares it to ChatGPT."
        else "OFF: no screenshot. The handle only opens ChatGPT (floating or full screen, as you chose)."
        editGptPrompt.alpha = if (prefs.gptPromptEnabled) 1f else 0.5f
        layoutGpt.visibility = if (on) View.VISIBLE else View.GONE
        textGptHint.text = if (on) {
            val inst = if (ChatGptLauncher.isInstalled(this)) "" else " The ChatGPT app is not installed on this phone."
            "ON: a ChatGPT handle sits under the Circle Search handle. Tap it and the screenshot (or your saved area) opens in the ChatGPT app together with your prompt.$inst"
        } else {
            "OFF: switch on to add a ChatGPT handle to the edge handles."
        }
    }

    // ---------------------------------------------------------------- clipboard (PinClip window)

    private fun mediaPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun setupClipboard(switchGesture: MaterialSwitch) {
        Store.init(this)
        switchClip = findViewById(R.id.switchClip)
        switchClipAuto = findViewById(R.id.switchClipAuto)
        layoutClip = findViewById(R.id.layoutClip)
        textClipHint = findViewById(R.id.textClipHint)
        textClipAuto = findViewById(R.id.textClipAuto)
        textClipStats = findViewById(R.id.textClipStats)
        val slider: Slider = findViewById(R.id.sliderClipOpacity)
        val textOpacity: TextView = findViewById(R.id.textClipOpacity)

        val pct = (Store.prefs.getInt("palpha", 100).coerceIn(15, 100) / 5) * 5
        slider.value = pct.toFloat()
        textOpacity.text = "$pct%"
        slider.setLabelFormatter { "${it.toInt()}%" }
        slider.addOnChangeListener { _, value, fromUser ->
            textOpacity.text = "${value.toInt()}%"
            if (fromUser) Store.prefs.edit().putInt("palpha", value.toInt()).apply()
        }

        switchClip.setOnCheckedChangeListener { _, checked ->
            if (clipSuppress) return@setOnCheckedChangeListener
            prefs.clipboardEnabled = checked
            if (checked && !prefs.gestureStrip) {
                prefs.gestureStrip = true
                switchGesture.isChecked = true
            }
            renderShortcuts()
            refreshClipboard()
        }
        switchClipAuto.setOnCheckedChangeListener { _, checked ->
            if (clipSuppress) return@setOnCheckedChangeListener
            if (checked && checkSelfPermission(mediaPermission()) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(mediaPermission()), 12)
            }
            Store.prefs.edit().putBoolean("auto", checked).apply()
            refreshClipboard()
        }
        findViewById<MaterialButton>(R.id.btnClipBackup).setOnClickListener {
            backupLauncher.launch("CircleSearch-clipboard-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".zip")
        }
        findViewById<MaterialButton>(R.id.btnClipRestore).setOnClickListener {
            restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        refreshClipboard()
    }

    private fun refreshClipboard() {
        if (!this::switchClip.isInitialized) return
        clipSuppress = true
        val on = prefs.clipboardEnabled
        switchClip.isChecked = on
        switchClipAuto.isChecked = Store.prefs.getBoolean("auto", false)
        clipSuppress = false
        layoutClip.visibility = if (on) View.VISIBLE else View.GONE
        textClipHint.text = if (on)
            "ON: a clipboard handle sits right under the Circle Search handle (turn on Edge handles above). Tap it to open your clipboard window, tap a card to paste it into the field you are typing in. Change the handle colour in the list under Edge handles."
        else
            "OFF: switch on to add a clipboard handle to the edge handles. Tapping it opens your PinClip clipboard window."
        val photos = checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED
        textClipAuto.text = "Saves everything you copy as a card" +
            if (photos) ", plus new screenshots." else ". Screenshots need photo access: switch this off and on and tap Allow."
        textClipStats.text = "${Store.items.size} cards - ${Store.groups.size} groups - ${Store.items.count { it.pinned }} pinned"
    }

    private fun writeClipBackup(uri: Uri) {
        val js = Store.backupJson()
        val imgs = Store.backupImages()
        Thread {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { Store.writeZip(it, js, imgs) }
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Backup saved (${imgs.size} images)" else "Backup failed", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun askClipRestore(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore clipboard backup")
            .setMessage("Merge adds missing cards and keeps what you have. Replace wipes everything first. Works with PinClip backups too.")
            .setPositiveButton("Merge") { _, _ -> runClipImport(uri, false) }
            .setNegativeButton("Replace all") { _, _ -> runClipImport(uri, true) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun runClipImport(uri: Uri, replace: Boolean) {
        Thread {
            val ins = try { contentResolver.openInputStream(uri) } catch (_: Throwable) { null }
            if (ins == null) {
                runOnUiThread { Toast.makeText(this, "Could not open that file", Toast.LENGTH_LONG).show() }
            } else {
                Store.importZip(ins, replace) { n ->
                    Toast.makeText(this, if (n >= 0) "Restored $n cards" else "Not a valid backup", Toast.LENGTH_LONG).show()
                    refreshClipboard()
                }
            }
        }.start()
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
        refreshClipboard()
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Xiaomi/MIUI/HyperOS (and a few others) kill apps on "clear recents" unless Autostart is allowed. */
    private fun openAutostartSettings() {
        val candidates = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent().setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                .putExtra("package_name", packageName).putExtra("package_label", getString(R.string.app_name)),
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        for (i in candidates) {
            try { startActivity(i); return } catch (_: Throwable) {}
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {}
    }

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
        val textAuto: TextView = findViewById(R.id.textAutoEnable)
        textAuto.text = if (SelfEnable.hasPermission(this)) "Auto-enable: ready. If accessibility ever turns off, the app switches it back on by itself."
        else "Auto-enable needs a one-time permission. On a computer with USB debugging, run:\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        textAuto.setTextIsSelectable(true)
        val ok = batteryUnrestricted()
        textBattery.text = if (ok) "Battery: unrestricted - Android won't stop the app."
        else "Battery: restricted - Android may kill the app. Tap below to fix."
        btnBattery.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE
        val overlayOk = Settings.canDrawOverlays(this)
        textOverlay.text = if (overlayOk) "Display over other apps: allowed - Lens always opens from the edge handle."
        else "Display over other apps: off. Allow it if Lens doesn't open when you tap the edge handle with a saved area."
        btnOverlay.visibility = if (overlayOk) android.view.View.GONE else android.view.View.VISIBLE
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
        if (!this::handlePreview.isInitialized) return
        shortcutList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        fun row(icon: Drawable?, label: String, sub: String, color: Int, onColor: (Int) -> Unit, onDelete: (() -> Unit)?, iconRes: Int = R.drawable.ic_search, floating: Boolean? = null, onFloating: ((Boolean) -> Unit)? = null) {
            val v = inflater.inflate(R.layout.item_shortcut, shortcutList, false)
            val iconView: ImageView = v.findViewById(R.id.rowIcon)
            if (icon != null) iconView.setImageDrawable(icon)
            else {
                iconView.setImageResource(iconRes)
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
            val floatSwitch: MaterialSwitch = v.findViewById(R.id.rowFloat)
            if (floating != null && onFloating != null) {
                floatSwitch.visibility = View.VISIBLE
                floatSwitch.isChecked = floating
                floatSwitch.setOnCheckedChangeListener { _, checked ->
                    onFloating(checked)
                    renderShortcuts()
                }
            }
            shortcutList.addView(v)
        }

        row(
            null, "Circle Search", if (prefs.lensFloating) "Default handle - Lens in a floating window" else "Default handle - Lens opens full screen",
            prefs.searchHandleColor, { prefs.searchHandleColor = it }, null,
            floating = prefs.lensFloating, onFloating = { prefs.lensFloating = it }
        )
        if (prefs.gptEnabled) {
            val gptIcon = try { packageManager.getApplicationIcon(ChatGptLauncher.PACKAGE) } catch (_: Throwable) { null }
            row(
                gptIcon, "ChatGPT", "Screenshot + prompt to the ChatGPT app",
                prefs.gptHandleColor, { prefs.gptHandleColor = it }, null,
                floating = prefs.gptFloating, onFloating = { prefs.gptFloating = it }
            )
        }
        if (prefs.shotEnabled) {
            row(
                null, "Screenshot", if (prefs.shotWholeScreen) "Saves the whole screen" else "Saves the saved area",
                prefs.shotHandleColor, { prefs.shotHandleColor = it }, null
            )
        }
        if (prefs.clipboardEnabled) {
            row(
                null, "Clipboard", "PinClip window - tap to open",
                prefs.clipHandleColor, { prefs.clipHandleColor = it }, null, R.drawable.clip_ic_clip
            )
        }
        val list = prefs.shortcuts()
        for (s in list) {
            val icon = try { packageManager.getApplicationIcon(s.packageName) } catch (_: Throwable) { null }
            row(
                icon, s.label, if (s.floating) "Opens in a floating window" else "Opens full screen", s.color,
                { prefs.setShortcutColor(s.id, it) },
                { removeShortcut(s) },
                floating = s.floating, onFloating = { prefs.setShortcutFloating(s.id, it) }
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
