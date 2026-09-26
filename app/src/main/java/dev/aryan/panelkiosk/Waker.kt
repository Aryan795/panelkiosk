package dev.aryan.panelkiosk

import android.content.Context
import android.os.PowerManager

object Waker {
    /**
     * Light the display from anywhere — the activity, the camera service, the
     * REST API — even after a true screen-off. Android 14+ also needs the
     * "Turn screen on" special access, which PanelKiosk declares but the user
     * can revoke; Settings shows it.
     */
    @Suppress("DEPRECATION") // still the only public way for a non-system app
    fun wakeDisplay(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "panelkiosk:wake")
            .acquire(3000)
    }
}
