package com.personal.circlesearch

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import java.io.File

/**
 * Robust "paste the copied text into app X and send it".
 *
 * Works on any app (ChatGPT, Grok, Claude, ...) by trying several strategies in order and VERIFYING each one:
 *  1. find the message box (editable / EditText / SET_TEXT-capable, bottom-most visible one; if the app
 *     shows only a "Ask anything..." placeholder, tap it first)
 *  2. put the text in: PASTE action -> SET_TEXT -> selection + PASTE -> click + PASTE (checks the box
 *     really contains the text before moving on)
 *  3. send it: labelled send/submit button -> arrow-like button next to the box -> keyboard Enter action
 *     (checks that the box emptied afterwards)
 * Everything is logged to files/paste_debug.txt so a failing app can be diagnosed.
 */
class PasteSender(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val pkg: String,
    private val prompt: String? = null
) {
    private enum class Phase { PASTE, SEND }

    private var cancelled = false
    private var tick = 0
    private var phase = Phase.PASTE
    private var pasteTries = 0
    private var sendTries = 0
    private var revealTries = 0
    private var lastRevealTick = -10
    private var clip: String? = null
    private var textBefore = ""
    private val log = StringBuilder()

    fun start() {
        current?.cancel()
        current = this
        clip = buildText(readClip(), prompt)
        note("start pkg=$pkg clipLen=${clip?.length ?: -1}")
        handler.postDelayed({ step() }, FIRST_DELAY_MS)
    }

    fun cancel() {
        cancelled = true
    }

    private fun step() {
        if (cancelled) return
        tick++
        var done = false
        try {
            done = doStep()
        } catch (t: Throwable) {
            note("error: $t")
        }
        if (done || tick >= MAX_TICKS) {
            finish(done)
        } else {
            handler.postDelayed({ step() }, STEP_MS)
        }
    }

    private fun finish(ok: Boolean) {
        note(if (ok) "DONE ok (tick $tick)" else "GAVE UP at tick $tick phase=$phase pasteTries=$pasteTries sendTries=$sendTries")
        if (!ok) {
            try { dumpTree() } catch (_: Throwable) {}
        }
        try { File(service.filesDir, LOG_FILE).writeText(log.toString()) } catch (_: Throwable) {}
        if (current === this) current = null
    }

    // ---------------------------------------------------------------- state machine

    private fun doStep(): Boolean {
        val roots = roots()
        if (roots.isEmpty()) {
            if (tick % 5 == 1) note("tick $tick: no window of $pkg yet")
            return false
        }
        val edit = findEdit(roots)

        if (phase == Phase.PASTE) {
            if (edit == null) {
                tryReveal(roots)
                return false
            }
            val cur = textOf(edit)
            if (pasteTries > 0 && looksPasted(cur)) {
                note("tick $tick: text is in the box (after $pasteTries paste tries)")
                phase = Phase.SEND
                return false // give the app one tick to enable its send button
            }
            if (pasteTries == 0) textBefore = cur
            if (pasteTries >= MAX_PASTE_TRIES) {
                note("paste never showed up in the box")
                return true // stop; nothing more we can do
            }
            doPaste(edit, pasteTries)
            pasteTries++
            return false
        }

        // SEND phase
        if (sendTries > 0 && (edit == null || textOf(edit).isBlank())) {
            note("tick $tick: box is empty again -> message sent")
            return true
        }
        if (sendTries == 0 && edit != null && !looksPasted(textOf(edit))) {
            note("tick $tick: text vanished before sending, pasting again")
            phase = Phase.PASTE
            return false
        }
        if (sendTries >= MAX_SEND_TRIES) {
            note("send never emptied the box; stopping")
            return true
        }
        sendTries++
        val btn = findSendButton(roots, edit)
        val useEnter = btn == null || sendTries % 3 == 0
        if (btn != null && !(useEnter && sendTries > 1 && edit != null)) {
            val ok = clickNode(btn)
            note("tick $tick: click send button '${label(btn)}' ok=$ok")
        }
        if (useEnter && edit != null && (btn == null || sendTries % 3 == 0) && sendTries >= 2) {
            val ok = edit.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
            note("tick $tick: IME_ENTER ok=$ok")
        }
        return false
    }

    private fun doPaste(edit: AccessibilityNodeInfo, n: Int) {
        val ok: Boolean
        when (n % 4) {
            0 -> {
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ok = edit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                note("tick $tick: focus+click+PASTE ok=$ok")
            }
            1 -> {
                val t = clip
                if (t != null) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
                    }
                    ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    note("tick $tick: SET_TEXT ok=$ok")
                } else {
                    ok = edit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    note("tick $tick: PASTE (no clip snapshot) ok=$ok")
                }
            }
            2 -> {
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 0)
                }
                edit.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
                ok = edit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                note("tick $tick: selection+PASTE ok=$ok")
            }
            else -> {
                clickNode(edit)
                ok = edit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                note("tick $tick: click+PASTE ok=$ok")
            }
        }
    }

    // ---------------------------------------------------------------- finding things

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

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (node == null || depth > 60 || out.size > 4000) return
        out.add(node)
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    private fun allNodes(roots: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (r in roots) collect(r, out)
        return out
    }

    private fun isTextField(n: AccessibilityNodeInfo): Boolean {
        if (n.isPassword) return false
        if (n.isEditable) return true
        if (n.className?.toString()?.contains("EditText", true) == true) return true
        return n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }

    private fun findEdit(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val fields = allNodes(roots).filter { it.isVisibleToUser && isTextField(it) }
        if (fields.isEmpty()) return null
        val focused = fields.firstOrNull { it.isFocused }
        if (focused != null) return focused
        val r = Rect()
        // The composer sits at the bottom of the screen; the lowest visible field wins.
        return fields.maxByOrNull { n -> n.getBoundsInScreen(r); r.bottom }
    }

    /** Some apps show only a tappable "Ask anything..." placeholder until you touch it. */
    private fun tryReveal(roots: List<AccessibilityNodeInfo>) {
        if (revealTries >= 5 || tick - lastRevealTick < 3) return
        val keys = listOf("ask", "message", "type", "chat", "prompt", "talk", "what's on", "how can")
        val bad = listOf("new", "history", "search", "voice", "mic", "attach", "menu", "settings", "share")
        for (n in allNodes(roots)) {
            if (!n.isVisibleToUser) continue
            val l = label(n).lowercase()
            if (l.isBlank() || bad.any { l.contains(it) } || keys.none { l.contains(it) }) continue
            if (clickNode(n)) {
                revealTries++
                lastRevealTick = tick
                note("tick $tick: tapped placeholder '${l.take(40)}' to open the message box")
                return
            }
        }
    }

    private fun findSendButton(roots: List<AccessibilityNodeInfo>, edit: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val er = Rect()
        edit?.getBoundsInScreen(er)
        val nr = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestRight = Int.MIN_VALUE
        for (n in allNodes(roots)) {
            if (!n.isVisibleToUser || !n.isEnabled || n === edit || isTextField(n)) continue
            val clickable = n.isClickable || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (!clickable) continue
            val l = label(n).lowercase()
            if (NEGATIVE.any { l.contains(it) }) continue
            var score = 0
            if (l.contains("send")) score += 10
            if (l.contains("submit")) score += 9
            if (l.contains("arrow") || l.contains("up")) score += 3
            n.getBoundsInScreen(nr)
            if (edit != null) {
                val nearRow = nr.centerY() >= er.top - 220 && nr.centerY() <= er.bottom + 220
                if (nearRow && nr.centerX() >= er.centerX()) score += 3
            }
            val need = if (sendTries >= 3) 3 else 6
            if (score < need) continue
            if (score > bestScore || (score == bestScore && nr.right > bestRight)) {
                best = n
                bestScore = score
                bestRight = nr.right
            }
        }
        return best
    }

    private fun clickNode(start: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = start
        var hops = 0
        while (n != null && hops < 6) {
            if ((n.isClickable || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) &&
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true
            n = n.parent
            hops++
        }
        return false
    }

    // ---------------------------------------------------------------- helpers

    private fun textOf(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else n.text?.toString() ?: ""

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun looksPasted(cur: String): Boolean {
        if (cur.isBlank()) return false
        val c = clip
        if (c != null) {
            val head = norm(c).take(15)
            if (head.isNotEmpty()) return norm(cur).contains(head)
        }
        return cur != textBefore
    }

    private fun label(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.contentDescription?.toString(), n.text?.toString(), n.hintText?.toString(), n.viewIdResourceName)
            .joinToString(" ")

    /**
     * prompt + blank line + copied text. The result is also put on the clipboard so the PASTE action
     * pastes the whole thing. If the clipboard already holds our own previous result (nothing new was
     * copied) the prompt is not added a second time.
     */
    private fun buildText(copied: String?, prompt: String?): String? {
        if (prompt == null) return copied
        val combined = when {
            copied == null -> prompt
            copied == lastCombined -> copied
            else -> prompt + "\n\n" + copied
        }
        try {
            val cm = service.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Circle Search paste", combined))
            lastCombined = combined
        } catch (_: Throwable) {
        }
        return combined
    }

    private fun readClip(): String? = try {
        val cm = service.getSystemService(ClipboardManager::class.java)
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(service)?.toString()
            ?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    private fun note(s: String) {
        log.append(s).append('\n')
    }

    private fun dumpTree() {
        val roots = roots()
        note("---- screen dump of $pkg (${roots.size} window(s)) ----")
        val r = Rect()
        var count = 0
        for (n in allNodes(roots)) {
            if (count++ > 150) break
            n.getBoundsInScreen(r)
            note(
                "${n.className?.toString()?.substringAfterLast('.')} id=${n.viewIdResourceName} " +
                    "desc=${n.contentDescription?.toString()?.take(30)} text=${n.text?.toString()?.take(30)} " +
                    "edit=${n.isEditable} click=${n.isClickable} en=${n.isEnabled} vis=${n.isVisibleToUser} " +
                    "[${r.left},${r.top},${r.right},${r.bottom}]"
            )
        }
    }

    companion object {
        private const val FIRST_DELAY_MS = 1100L
        private const val STEP_MS = 400L
        private const val MAX_TICKS = 55
        private const val MAX_PASTE_TRIES = 12
        private const val MAX_SEND_TRIES = 9
        const val LOG_FILE = "paste_debug.txt"

        private val NEGATIVE = listOf(
            "voice", "mic", "speech", "dictat", "attach", "plus", "camera", "photo", "upload",
            "file", "tool", "more", "menu", "model", "stop", "cancel", "close", "back", "search",
            "image", "think", "deep", "add", "record", "listen", "settings"
        )

        @Volatile
        private var current: PasteSender? = null

        @Volatile
        private var lastCombined: String? = null

        fun readLog(ctx: android.content.Context): String =
            try { File(ctx.filesDir, LOG_FILE).readText() } catch (_: Throwable) { "No log yet - run an auto paste first." }
    }
}
