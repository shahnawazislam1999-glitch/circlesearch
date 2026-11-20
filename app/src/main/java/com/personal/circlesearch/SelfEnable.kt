package com.personal.circlesearch

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Keeps the accessibility service switched on by itself. Android only lets an app do this when it holds
 * WRITE_SECURE_SETTINGS, which is granted once from a computer:
 *   adb shell pm grant com.personal.circlesearch android.permission.WRITE_SECURE_SETTINGS
 */
object SelfEnable {

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun component(ctx: Context) =
        ComponentName(ctx, CircleAccessibilityService::class.java)

    fun isListed(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val c = component(ctx)
        return enabled.split(':').any {
            it.equals(c.flattenToString(), true) || it.equals(c.flattenToShortString(), true)
        }
    }

    /** Re-adds the service to the enabled list if the user wants it active. Returns true if it changed anything. */
    fun ensureEnabled(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)
        if (!prefs.autoEnable || !prefs.serviceActive || !hasPermission(app)) return false
        return try {
            if (isListed(app)) return false
            val cur = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val flat = component(app).flattenToString()
            val next = if (cur.isNullOrBlank()) flat else "$cur:$flat"
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
