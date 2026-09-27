package com.claw.autoreplyai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the engine back up after a reboot or an app update.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!Prefs.get(context).enabled) return
        KeepAliveService.start(context)
        LogStore.add(context, "বুটের পর সার্ভিস চালু হলো")
    }
}
