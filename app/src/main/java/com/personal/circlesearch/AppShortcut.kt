package com.personal.circlesearch

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import java.util.UUID

/** One app shortcut shown as its own colour-coded edge handle. */
data class AppShortcut(
    val id: String,
    val packageName: String,
    val label: String,
    val color: Int,
    /** false = this app opens normally (full screen) instead of in a floating window. */
    val floating: Boolean = true
) {
    companion object {
        const val OPAQUE: Int = 0xFF000000.toInt()

        fun create(packageName: String, label: String, color: Int) =
            AppShortcut(UUID.randomUUID().toString(), packageName, label, color or OPAQUE)
    }
}

/** Opens a shortcut's app in a floating (freeform) window, using the same mechanism as the Lens app. */
object AppLauncher {

    /**
     * Best effort: asks Android for a freeform window at [bounds]. On phones where freeform windows
     * are switched off the system ignores the request and the app simply opens normally.
     * Returns false when the app can't be launched (e.g. it was uninstalled).
     */
    fun launchFloating(context: Context, packageName: String, bounds: Rect, floating: Boolean = true): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // floating off -> no launch bounds, no freeform request: the app opens full screen like normal.
        return LensLauncher.start(context, intent, if (floating) bounds else null)
    }
}
