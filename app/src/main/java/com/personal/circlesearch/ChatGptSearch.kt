package com.personal.circlesearch

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import java.io.File

/**
 * GPT_SEARCH: shares the cropped screenshot (plus the saved prompt as text) to the real ChatGPT app,
 * the same way the Lens handle does it for Google Lens.
 */
object ChatGptLauncher {

    const val PACKAGE = "com.openai.chatgpt"

    fun isInstalled(context: Context): Boolean =
        try { context.packageManager.getLaunchIntentForPackage(PACKAGE) != null } catch (_: Throwable) { false }

    fun launch(context: Context, imageFile: File, bounds: Rect, prompt: String?): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
        } catch (_: Exception) {
            return false
        }
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_SEND).setType("image/jpeg").setPackage(PACKAGE)
        val candidates = try { pm.queryIntentActivities(probe, 0) } catch (_: Exception) { emptyList() }
        if (candidates.isEmpty()) return false
        val chosen = candidates.first().activityInfo.name

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setClassName(PACKAGE, chosen)
            putExtra(Intent.EXTRA_STREAM, uri)
            if (prompt != null) putExtra(Intent.EXTRA_TEXT, prompt)
            clipData = ClipData.newRawUri("image", uri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        try {
            context.grantUriPermission(PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        return LensLauncher.start(context, intent, bounds)
    }
}

/**
 * After ChatGPT opens with the image, makes sure the prompt is in its message box and (optionally)
 * presses send. Uses the accessibility service; if the box or button isn't found in time the prompt
 * is already on the clipboard and a toast says so.
 */
class GptFiller(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val pkg: String = ChatGptLauncher.PACKAGE
) {
    private var attempts = 0
    private var finished = false
    private var typed = false

    fun run(prompt: String?, autoSend: Boolean) {
        attempts = 0
        finished = false
        typed = false
        handler.postDelayed({ tick(prompt, autoSend) }, FIRST_DELAY_MS)
    }

    /** No-screenshot / any-app mode: paste the copied text into the app and send it (see PasteSender). */
    fun runPaste() {
        PasteSender(service, handler, pkg).start()
    }

    private fun tick(prompt: String?, autoSend: Boolean) {
        if (finished) return
        attempts++
        val done = try { step(prompt, autoSend) } catch (_: Throwable) { false }
        if (done) {
            finished = true
            return
        }
        if (attempts >= MAX_ATTEMPTS) {
            finished = true
            return
        }
        handler.postDelayed({ tick(prompt, autoSend) }, STEP_MS)
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in service.windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg) out.add(r)
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) {
            val r = service.rootInActiveWindow
            if (r != null && r.packageName?.toString() == pkg) out.add(r)
        }
        return out
    }

    /** Returns true when everything that was asked for is done. */
    private fun step(prompt: String?, autoSend: Boolean): Boolean {
        val rs = roots()
        if (rs.isEmpty()) return false

        if (prompt != null && !typed) {
            for (root in rs) {
                val edit = find(root) { it.isEditable } ?: continue
                val current = edit.text?.toString().orEmpty()
                if (current.trim() == prompt.trim() || current.contains(prompt.trim())) {
                    typed = true // the share intent already filled it
                } else {
                    edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
                    }
                    typed = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                break
            }
            if (!typed) return false
        }

        if (!autoSend) return true

        // Wait for an enabled send button (it stays disabled while the image uploads).
        for (root in rs) {
            val btn = find(root) { n ->
                val d = (n.contentDescription?.toString() ?: "").lowercase()
                n.isEnabled && d.contains("send") && !d.contains("voice")
            } ?: continue
            if (clickNode(btn)) return true
        }
        return false
    }

    private fun clickNode(start: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = start
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            hops++
        }
        return false
    }

    private fun find(node: AccessibilityNodeInfo?, depth: Int = 0, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val hit = find(node.getChild(i), depth + 1, pred)
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        private const val FIRST_DELAY_MS = 1200L
        private const val STEP_MS = 500L
        private const val MAX_ATTEMPTS = 30
    }
}
