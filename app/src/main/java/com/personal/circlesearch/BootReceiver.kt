package com.personal.circlesearch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot or an app update, switch the accessibility service back on (needs the one-time adb grant). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SelfEnable.ensureEnabled(context)
    }
}
