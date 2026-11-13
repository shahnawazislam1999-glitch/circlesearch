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
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)

        statusCard = findViewById(R.id.statusCard)
        statusTitle = findViewById(R.id.statusTitle)
        statusBody = findViewById(R.id.statusBody)
        btnEnable = findViewById(R.id.btnEnable)
        btnAppInfo = findViewById(R.id.btnAppInfo)

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
        val sliderStrip: Slider = findViewById(R.id.sliderStripOpacity)
        val textStrip: TextView = findViewById(R.id.textStripOpacity)
        val stripPct = (prefs.stripOpacity * 100f).roundToInt().coerceIn(0, 100)
        sliderStrip.value = stripPct.toFloat()
        textStrip.text = "$stripPct%"
        sliderStrip.setLabelFormatter { "${it.toInt()}%" }
        sliderStrip.addOnChangeListener { _, value, fromUser ->
            textStrip.text = "${value.toInt()}%"
            if (fromUser) prefs.stripOpacity = value / 100f
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
}
