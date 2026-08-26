package dev.aryan.panelkiosk

import android.app.Activity
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.view.WindowManager

/**
 * Two sleep strategies:
 *  - soft (default): window brightness to minimum + an opaque black layer.
 *    The activity (webview, camera, REST server) keeps running, so camera
 *    motion can wake the panel. On most LCD tablets brightness 0 turns the
 *    backlight effectively off.
 *  - true off: DevicePolicyManager.lockNow() — real screen off. The camera
 *    dies with it, so waking then relies on the dashboard server (HA motion
 *    sensors -> REST :2323) or a tap on the power button. Requires the user
 *    to enable device admin, and a lockscreen set to None/Swipe.
 */
class ScreenController(private val activity: Activity, private val blackout: View, private val prefs: Prefs) {

    @Volatile var screenOn: Boolean = true
        private set

    private val dpm get() = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin get() = ComponentName(activity, AdminReceiver::class.java)

    fun sleep() {
        activity.runOnUiThread {
            screenOn = false
            if (prefs.trueOff && !prefs.motionWake && dpm.isAdminActive(admin)) {
                dpm.lockNow()
            } else {
                setBrightness(0.003f)
                blackout.visibility = View.VISIBLE
            }
        }
    }

    fun wake() {
        activity.runOnUiThread {
            screenOn = true
            blackout.visibility = View.GONE
            setBrightness(-1f) // back to system brightness

            // pulse the screen awake even if the system turned it off
            if (Build.VERSION.SDK_INT >= 27) {
                activity.setTurnScreenOn(true)
                activity.setShowWhenLocked(true)
                (activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                    .requestDismissKeyguard(activity, null)
            }
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "panelkiosk:wake"
            )
            wl.acquire(3000)
        }
    }

    fun setBrightness(value: Float) {
        val lp: WindowManager.LayoutParams = activity.window.attributes
        lp.screenBrightness = value
        activity.window.attributes = lp
    }
}
