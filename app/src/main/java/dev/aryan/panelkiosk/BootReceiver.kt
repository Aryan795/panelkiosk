package dev.aryan.panelkiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in STARTERS) return
        // Android 10+ silently blocks background activity starts unless PanelKiosk
        // is the Home app, the device owner, or (Android 10-14) allowed to display
        // over other apps; Settings shows which applies. Elsewhere this is a no-op.
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private companion object {
        val STARTERS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,           // come back up after an update
            "android.intent.action.QUICKBOOT_POWERON",   // fast boot on some brands
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
