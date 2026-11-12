package com.personal.circlesearch

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sends the cropped image to the Google Lens app and asks Android to open it as a floating
 * (freeform) window. The floating part only works on devices/ROMs where freeform windows are
 * available; otherwise the app opens normally. Returns false when Lens can't be launched so the
 * caller can fall back to the built-in floating window.
 */
object LensLauncher {

    private val PACKAGES = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.ar.lens"
    )

    @Suppress("DEPRECATION")
    fun launch(context: Context, imageFile: File, bounds: Rect): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
        } catch (_: Exception) {
            return false
        }
        val pm = context.packageManager

        for (pkg in PACKAGES) {
            val probe = Intent(Intent.ACTION_SEND).setType("image/jpeg").setPackage(pkg)
            val candidates = try { pm.queryIntentActivities(probe, 0) } catch (_: Exception) { emptyList() }
            if (candidates.isEmpty()) continue

            val chosen = candidates.firstOrNull { it.activityInfo.name.contains("lens", ignoreCase = true) }
                ?: if (candidates.size == 1) candidates.first() else null
            if (chosen == null) continue

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                setClassName(pkg, chosen.activityInfo.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("image", uri)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            if (start(context, intent, bounds)) return true
        }
        return false
    }

    private fun start(context: Context, intent: Intent, bounds: Rect): Boolean {
        val base: Bundle = ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle() ?: Bundle()
        // Best effort: ask for a freeform window. Ignored by the system when unsupported.
        val freeform = Bundle(base).apply { putInt("android.activity.windowingMode", 5) }

        for (opts in listOf(freeform, base)) {
            try {
                context.startActivity(intent, opts)
                return true
            } catch (_: ActivityNotFoundException) {
                return false
            } catch (_: SecurityException) {
                // try next option set
            } catch (_: Exception) {
                // try next option set
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
