package com.personal.circlesearch

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * PROMPT_SEARCH (Lens app mode): the Google app has no public way to pre-fill its
 * "Ask about this image" box, so after Lens opens we find that box through the accessibility
 * service, type the saved prompt into it and press Enter. That gives picture + text together.
 * If the box is not found in time, the prompt is already on the clipboard and a toast says so.
 */
class PromptFiller(
    private val service: AccessibilityService,
    private val handler: Handler
) {
    private val googlePackages = setOf("com.google.android.googlequicksearchbox", "com.google.ar.lens")
    private var attempts = 0
    private var finished = false

    fun run(prompt: String) {
        attempts = 0
        finished = false
        handler.postDelayed({ tick(prompt) }, FIRST_DELAY_MS)
    }

    private fun tick(prompt: String) {
        if (finished) return
        attempts++
        val ok = try { tryFill(prompt) } catch (_: Throwable) { false }
        if (ok) {
            finished = true
            return
        }
        if (attempts >= MAX_ATTEMPTS) {
            finished = true
            Toast.makeText(
                service,
                "Prompt copied - tap the box and paste it",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        handler.postDelayed({ tick(prompt) }, STEP_MS)
    }

    private fun googleRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in service.windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() in googlePackages) roots.add(r)
            }
        } catch (_: Throwable) {
        }
        if (roots.isEmpty()) {
            val r = service.rootInActiveWindow
            if (r != null && r.packageName?.toString() in googlePackages) roots.add(r)
        }
        return roots
    }

    private fun tryFill(prompt: String): Boolean {
        val roots = googleRoots()
        if (roots.isEmpty()) return false

        // 1) An editable box is already on screen: type into it.
        for (root in roots) {
            val edit = find(root) { it.isEditable }
            if (edit != null) return typeAndSubmit(edit, prompt, roots)
        }
        // 2) Only the "Ask about this image" bar is visible: tap it so the text box opens.
        for (root in roots) {
            val bar = find(root) { matchesAsk(it) }
            if (bar != null) {
                clickNode(bar)
                return false // keep polling; the editable box appears next tick
            }
        }
        return false
    }

    private fun typeAndSubmit(edit: AccessibilityNodeInfo, prompt: String, roots: List<AccessibilityNodeInfo>): Boolean {
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
        }
        val set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!set) return false
        handler.postDelayed({
            var sent = false
            if (Build.VERSION.SDK_INT >= 30) {
                sent = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (!sent) {
                // Fall back to a visible send / search button next to the box.
                for (root in googleRoots()) {
                    val btn = find(root) { n ->
                        val d = (n.contentDescription?.toString() ?: "").lowercase()
                        n.isClickable && (d.contains("send") || d.contains("submit") || d == "search")
                    }
                    if (btn != null) { clickNode(btn); break }
                }
            }
        }, SUBMIT_DELAY_MS)
        return true
    }

    private fun matchesAsk(n: AccessibilityNodeInfo): Boolean {
        val t = (n.text?.toString() ?: "").lowercase()
        val d = (n.contentDescription?.toString() ?: "").lowercase()
        val h = if (Build.VERSION.SDK_INT >= 26) (n.hintText?.toString() ?: "").lowercase() else ""
        return t.contains("ask about this image") || d.contains("ask about this image") ||
            h.contains("ask about this image")
    }

    private fun clickNode(start: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = start
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
            hops++
        }
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
        private const val FIRST_DELAY_MS = 900L
        private const val STEP_MS = 400L
        private const val MAX_ATTEMPTS = 22
        private const val SUBMIT_DELAY_MS = 350L
    }
}
