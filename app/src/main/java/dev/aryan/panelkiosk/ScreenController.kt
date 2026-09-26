package dev.aryan.panelkiosk

import android.app.Activity
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * Two sleep strategies (camera motion wake works with both — the camera lives
 * in MotionService, which keeps running with the display off):
 *  - soft (default): window brightness to minimum + an opaque black layer.
 *    The activity (webview, camera, REST server) keeps running, so camera
 *    motion can wake the panel. On most LCD tablets brightness 0 turns the
 *    backlight effectively off.
 *  - true off: the display really switches off — at once via device admin's
 *    lockNow(), or, without device admin, when Android's own screen timeout
 *    runs out. The camera keeps watching from MotionService and switches it
 *    back on, as do the dashboard server (HA motion sensors -> REST :2323) and
 *    the power button. Set the lock screen to None or Swipe; a PIN can't be
 *    bypassed, but the dashboard still shows over the lock screen.
 */
class ScreenController(private val activity: Activity, private val blackout: View, private val prefs: Prefs) {

    @Volatile var screenOn: Boolean = true
        private set

    private val dpm get() = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin get() = ComponentName(activity, AdminReceiver::class.java)

    fun sleep() {
        activity.runOnUiThread {
            screenOn = false
            MotionService.rebaseline() // the panel's own light changes; that isn't motion
            if (prefs.trueOff) {
                if (dpm.isAdminActive(admin)) {
                    dpm.lockNow() // display off now; with no lock screen there's nothing to unlock later
                    return@runOnUiThread
                }
                // no device admin: let Android's own screen timeout switch the display off
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            setBrightness(0.003f)
            blackout.visibility = View.VISIBLE
        }
    }

    fun wake() {
        activity.runOnUiThread {
            screenOn = true
            blackout.visibility = View.GONE
            setBrightness(-1f) // back to system brightness
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            MotionService.rebaseline()

            // pulse the screen awake even if the system turned it off. The Activity
            // methods exist from Android 8.1, keyguard dismissal from 8.0; older
            // versions only understand the window flags.
            if (Build.VERSION.SDK_INT >= 27) {
                activity.setTurnScreenOn(true)
                activity.setShowWhenLocked(true)
            } else {
                @Suppress("DEPRECATION")
                activity.window.addFlags(
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                )
            }
            // No lock screen, or Swipe: dismiss it. A PIN/pattern can't be bypassed by any
            // app, and asking would pop the PIN pad on every wake — so leave it; the
            // dashboard still shows over the lock screen (showWhenLocked).
            val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguard.isKeyguardSecure) {
                if (Build.VERSION.SDK_INT >= 26) {
                    keyguard.requestDismissKeyguard(activity, null)
                } else {
                    @Suppress("DEPRECATION")
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                }
            }
            Waker.wakeDisplay(activity)
        }
    }

    fun setBrightness(value: Float) {
        val lp: WindowManager.LayoutParams = activity.window.attributes
        lp.screenBrightness = value
        activity.window.attributes = lp
    }
}
