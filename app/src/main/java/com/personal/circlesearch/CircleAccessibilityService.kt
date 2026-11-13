package com.personal.circlesearch

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hosts every overlay (bubble, gesture strip, lasso, floating Lens window) as accessibility
 * overlays, so no "draw over other apps" permission is needed, and takes the screenshot.
 */
class CircleAccessibilityService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var io: ExecutorService? = null

    private var bubble: BubbleOverlay? = null
    private var strip: GestureStrip? = null
    private var selection: SelectionOverlay? = null
    private var lensWindow: FloatingLensWindow? = null
    private var picker: AreaPickerOverlay? = null
<<<<<<< HEAD
=======
    private var anchor: View? = null
>>>>>>> b1f1190 (Initial commit)

    private var busy = false

    private enum class Mode { LASSO, AREA_SEARCH, AREA_PICK }

    private var mode = Mode.LASSO

    private val watchdog = Runnable {
        if (busy && selection == null) fail("Screen capture timed out")
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post { applyPrefs() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cleanup()
        instance = this
        prefs = Prefs.get(this)
        io = Executors.newSingleThreadExecutor()
        bubble = BubbleOverlay(this, prefs) { startSelection() }
        strip = GestureStrip(this, prefs) { onHandleTap() }
        prefs.register(prefListener)
        applyPrefs()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (busy) finishSession()
        main.post {
            bubble?.onConfigurationChanged()
            strip?.relayout()
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        main.removeCallbacksAndMessages(null)
        if (this::prefs.isInitialized) {
            try { prefs.unregister(prefListener) } catch (_: Throwable) {}
        }
        selection?.dismiss()
        selection = null
        picker?.dismiss()
        picker = null
<<<<<<< HEAD
=======
        removeAnchor()
>>>>>>> b1f1190 (Initial commit)
        lensWindow?.dismiss()
        lensWindow = null
        bubble?.hide()
        strip?.hide()
        busy = false
        ensureForeground(false)
        io?.shutdownNow()
        io = null
        if (instance === this) instance = null
    }

    private var inForeground = false

    private fun ensureForeground(on: Boolean) {
        if (on == inForeground) return
        try {
            if (on) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Circle Search running", NotificationManager.IMPORTANCE_MIN).apply {
                        setShowBadge(false)
                    }
                )
                val open = PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle("Circle Search is running")
                    .setContentText("Tap to open settings. Turn it off there to stop.")
                    .setOngoing(true)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setContentIntent(open)
                    .build()
                val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                ServiceCompat.startForeground(this, NOTIF_ID, n, type)
            } else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            inForeground = on
        } catch (_: Throwable) {
            // Never let a notification problem take the service down.
        }
    }

    private fun applyPrefs() {
        ensureForeground(prefs.serviceActive)
        if (busy) return
        if (!prefs.serviceActive) {
            bubble?.hide()
            strip?.hide()
            return
        }
        if (prefs.bubbleEnabled) bubble?.show() else bubble?.hide()
        if (prefs.gestureStrip) strip?.show() else strip?.hide()
        bubble?.setSuppressed(false)
    }

    /** Entry point for the bubble tap, the two-finger gesture and the "Try it now" button. */
    fun startSelection() = begin(Mode.LASSO)

    /**
     * Edge handle tap: searches the saved area straight away when one is saved (and matches the
     * current orientation); otherwise behaves exactly like before and opens the lasso.
     */
    fun onHandleTap() {
        if (busy || !prefs.serviceActive) return
        if (prefs.savedArea() == null) {
            begin(Mode.LASSO)
            return
        }
        if (prefs.areaLandscape != isLandscape()) {
<<<<<<< HEAD
            Toast.makeText(this, "Saved area is for the other orientation - using lasso", Toast.LENGTH_SHORT).show()
=======
>>>>>>> b1f1190 (Initial commit)
            begin(Mode.LASSO)
            return
        }
        begin(Mode.AREA_SEARCH)
    }

    /** Freezes the screen and lets the user drag out the area to save. */
    fun startAreaPick() = begin(Mode.AREA_PICK)

    private fun isLandscape(): Boolean {
        val b = getSystemService(WindowManager::class.java).screenBounds()
        return b.width() > b.height()
    }

    private fun begin(newMode: Mode) {
        if (busy || !prefs.serviceActive) return
        busy = true
        mode = newMode
        lensWindow?.dismiss()
        bubble?.setSuppressed(true)
        strip?.hide()
        main.postDelayed(watchdog, 6000)
        main.postDelayed({ capture(0) }, 140)
    }

    private fun capture(attempt: Int) {
        if (!busy) return
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        handleScreenshot(screenshot)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < 3) {
                            main.postDelayed({ capture(attempt + 1) }, 450)
                        } else {
                            fail("Couldn't capture the screen (this screen may be protected)")
                        }
                    }
                }
            )
        } catch (_: Throwable) {
            fail("Couldn't capture the screen")
        }
    }

    private fun handleScreenshot(result: AccessibilityService.ScreenshotResult) {
        val hardware = result.hardwareBuffer
        val bitmap: Bitmap? = try {
            val hw = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
            if (hw == null) {
                null
            } else {
                val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                hw.recycle()
                soft
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { hardware.close() } catch (_: Throwable) {}
        }

        if (bitmap == null || !busy) {
            if (busy) fail("Couldn't read the screenshot")
            return
        }
        main.removeCallbacks(watchdog)
<<<<<<< HEAD
=======
        // Android only lets an app start another app's activity from the background while it has a
        // visible window. With a saved area nothing of ours is on screen (the handle is hidden for
        // the screenshot), so keep a 1px, invisible window up until the search has been launched.
        addAnchor()
>>>>>>> b1f1190 (Initial commit)
        when (mode) {
            Mode.AREA_SEARCH -> searchSavedArea(bitmap)
            Mode.AREA_PICK -> showPicker(bitmap)
            Mode.LASSO -> {
                val sel = SelectionOverlay(
                    this,
                    bitmap,
                    onSelected = { crop -> onCropSelected(crop) },
                    onCancel = { finishSession() },
                    hasSavedArea = prefs.hasSavedArea,
                    onPickArea = { main.post { switchToPicker() } },
                    onRemoveArea = {
                        main.post {
                            prefs.clearArea()
                            Toast.makeText(this, "Saved area removed", Toast.LENGTH_SHORT).show()
                            finishSession()
                        }
                    }
                )
                selection = sel
                sel.show()
            }
        }
    }

<<<<<<< HEAD
=======
    private fun addAnchor() {
        if (anchor != null) return
        try {
            val v = View(this).apply { setBackgroundColor(Color.argb(2, 0, 0, 0)) }
            val p = WindowManager.LayoutParams(
                dpInt(2), dpInt(2),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            getSystemService(WindowManager::class.java).addView(v, p)
            anchor = v
        } catch (_: Throwable) {
            anchor = null
        }
    }

    private fun removeAnchor() {
        val v = anchor ?: return
        anchor = null
        try { getSystemService(WindowManager::class.java).removeViewImmediate(v) } catch (_: Throwable) {}
    }

>>>>>>> b1f1190 (Initial commit)
    private fun searchSavedArea(bitmap: Bitmap) {
        val a = prefs.savedArea()
        val crop: Bitmap? = if (a == null) null else try {
            val w = bitmap.width
            val h = bitmap.height
            val l = (a.left * w).toInt().coerceIn(0, w - 1)
            val t = (a.top * h).toInt().coerceIn(0, h - 1)
            val r = (a.right * w).toInt().coerceIn(l + 1, w)
            val b = (a.bottom * h).toInt().coerceIn(t + 1, h)
            val c = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
            if (c !== bitmap) bitmap.recycle()
            c
        } catch (_: Throwable) {
            null
        }
        if (crop == null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            fail("Couldn't crop the saved area")
        } else {
            onCropSelected(crop)
        }
    }

    /** From the lasso screen: reuse the same frozen screenshot for the area picker. */
    private fun switchToPicker() {
        val sel = selection ?: return
        if (!busy) return
        val bmp = sel.detach()
        selection = null
        mode = Mode.AREA_PICK
        showPicker(bmp)
    }

    private fun showPicker(bitmap: Bitmap) {
        val existing = if (prefs.areaLandscape == isLandscape()) prefs.savedArea() else null
        val p = AreaPickerOverlay(
            this,
            bitmap,
            initial = existing,
            hasSaved = prefs.hasSavedArea,
            onSave = { fractions ->
                main.post {
                    prefs.saveArea(fractions, isLandscape())
<<<<<<< HEAD
                    Toast.makeText(this, "Search area saved - tap the edge handle to search it", Toast.LENGTH_SHORT).show()
=======
                    Toast.makeText(this, "Area saved", Toast.LENGTH_SHORT).show()
>>>>>>> b1f1190 (Initial commit)
                    finishSession()
                }
            },
            onRemove = {
                main.post {
                    prefs.clearArea()
                    Toast.makeText(this, "Saved area removed", Toast.LENGTH_SHORT).show()
                    finishSession()
                }
            },
            onCancel = { main.post { finishSession() } }
        )
        picker = p
        p.show()
    }

    private fun onCropSelected(crop: Bitmap) {
        val mode = prefs.lensMode
        val appContext = applicationContext
        val executor = io
        if (executor == null) {
            fail("Service is not ready")
            return
        }
        executor.execute {
            val result: Pair<ByteArray, File?>? = try {
                val scaled = ImageUtils.scaleDown(crop, 1280)
                val bytes = ImageUtils.toJpeg(scaled, 90)
                if (scaled !== crop) scaled.recycle()
                crop.recycle()
                val file = if (mode == Prefs.LENS_APP) ImageUtils.writeShared(appContext, bytes) else null
                Pair(bytes, file)
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (result == null) fail("Couldn't prepare the image") else deliver(result.first, result.second, mode)
            }
        }
    }

    private fun deliver(bytes: ByteArray, file: File?, mode: Int) {
        if (!busy) return
        var viaApp = false
        if (mode == Prefs.LENS_APP && file != null) {
            viaApp = LensLauncher.launch(this, file, floatingBounds())
        }
        if (!viaApp) {
            openWebWindow(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        main.postDelayed({ finishSession() }, if (viaApp) 450L else 120L)
    }

    private fun openWebWindow(base64: String) {
        lensWindow?.dismiss()
        val w = FloatingLensWindow(this) { lensWindow = null }
        lensWindow = w
        w.showWithImage(base64)
    }

    private fun floatingBounds(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        val b = wm.screenBounds()
        val ins = wm.systemBarInsets()
        val w = (b.width() * 0.94f).toInt()
        val h = (b.height() * 0.62f).toInt()
        val left = (b.width() - w) / 2
        val bottom = b.height() - ins.bottom - dpInt(12)
        return Rect(left, bottom - h, left + w, bottom)
    }

    private fun fail(message: String) {
        // Searching a saved area is meant to be completely silent: nothing is shown on screen.
        if (mode != Mode.AREA_SEARCH) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finishSession()
    }

    private fun finishSession() {
        main.removeCallbacks(watchdog)
        val sel = selection
        selection = null
        sel?.dismiss()
        val pk = picker
        picker = null
        pk?.dismiss()
<<<<<<< HEAD
=======
        removeAnchor()
>>>>>>> b1f1190 (Initial commit)
        mode = Mode.LASSO
        busy = false
        applyPrefs()
    }

    companion object {
        private const val CHANNEL_ID = "circle_search_running"
        private const val NOTIF_ID = 1001

        @Volatile
        var instance: CircleAccessibilityService? = null
    }
}
