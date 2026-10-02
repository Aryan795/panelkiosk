package dev.aryan.panelkiosk

import android.app.Activity
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
 *    back on, as do the REST API (e.g. a Home Assistant automation on a motion
 *    sensor) and the power button. Set the lock screen to None or Swipe; a PIN can't be
 *    bypassed, but the dashboard still shows over the lock screen.
 *
 * Who decides to sleep: a page that drives the screen through window.fully (a
 * Fully-aware dashboard, or a page like KlipperScreen's kiosk.html), the REST API,
 * or — for a page that doesn't, which is most of them — the built-in idle timer:
 * `idleMinutes` without camera motion or a touch.
 */
class ScreenController(private val activity: Activity, private val blackout: View, private val prefs: Prefs) {

    /** What the kiosk last asked for: true after wake(), false after sleep(). */
    @Volatile var screenOn: Boolean = true
        private set

    /** Times onResume put the display back to sleep after Android relit it; every wake() resets it. */
    var relightResleeps = 0

    @Volatile private var blackoutShown = false
    @Volatile private var sleptAtMs = 0L
    /** after release() (the activity is being destroyed) nothing may touch the window or lock the device */
    @Volatile private var released = false

    /**
     * What the panel really shows: the display is on and not covered by the soft-sleep layer.
     * Unlike [screenOn] it sees the power button and a display Android relit on its own, so a
     * client that skips a command it thinks is already done (Moonraker's power device) acts on
     * the truth.
     */
    val displayLit: Boolean
        get() = (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive && !blackoutShown

    fun msSinceSleep(): Long = SystemClock.elapsedRealtime() - sleptAtMs

    /** The settings dialog is open: no idle sleep while someone is configuring the panel. */
    @Volatile var settingsOpen: Boolean = false
        set(v) { field = v; if (v) main.removeCallbacks(idleSleep) else userActivity() }

    /** Another screen (Android's own settings, say) is in front of the kiosk: no idle sleep either. */
    @Volatile var inBackground: Boolean = false
        set(v) { field = v; if (v) main.removeCallbacks(idleSleep) else userActivity() }

    /** The page has called window.fully's screen methods, so it owns sleep; cleared on each page load. */
    @Volatile var pageDrivesSleep: Boolean = false
        set(v) {
            val was = field
            field = v
            // only a hand-back restarts the countdown: the retry page reloads every 10 s
            if (v) main.removeCallbacks(idleSleep) else if (was) userActivity()
        }

    private val main = Handler(Looper.getMainLooper())
    private val idleSleep = Runnable { if (screenOn && !pageDrivesSleep) sleep() }

    private val dpm get() = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin get() = ComponentName(activity, AdminReceiver::class.java)

    /** Someone is there (a touch, camera motion, a wake): restart the idle countdown. Any thread. */
    fun userActivity() {
        main.removeCallbacks(idleSleep)
        if (released || settingsOpen || inBackground) return
        val mins = prefs.idleMinutes
        if (mins > 0 && screenOn && !pageDrivesSleep) main.postDelayed(idleSleep, mins * 60_000L)
    }

    fun sleep() {
        activity.runOnUiThread {
            if (released) return@runOnUiThread
            main.removeCallbacks(idleSleep)
            screenOn = false
            sleptAtMs = SystemClock.elapsedRealtime()
            MotionService.rebaseline() // the panel's own light changes; that isn't motion
            if (prefs.trueOff) {
                if (dpm.isAdminActive(admin)) {
                    // Disarm turn-screen-on first: left armed, Android relights the display about
                    // 1.5 s after lockNow() (reproduced on a Vivo V15). wake() re-arms it.
                    setTurnScreenOn(false)
                    dpm.lockNow() // display off now; with no lock screen there's nothing to unlock later
                    return@runOnUiThread
                }
                // no device admin: let Android's own screen timeout switch the display off
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            setBrightness(0.003f)
            blackout.visibility = View.VISIBLE
            blackoutShown = true
        }
    }

    fun wake() {
        activity.runOnUiThread {
            if (released) return@runOnUiThread
            screenOn = true
            relightResleeps = 0
            blackout.visibility = View.GONE
            blackoutShown = false
            setBrightness(-1f) // back to system brightness
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            MotionService.rebaseline()

            // pulse the screen awake even if the system turned it off. The Activity
            // methods exist from Android 8.1, keyguard dismissal from 8.0; older
            // versions only understand the window flags.
            setTurnScreenOn(true)
            if (Build.VERSION.SDK_INT >= 27) {
                activity.setShowWhenLocked(true)
            } else {
                @Suppress("DEPRECATION")
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
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
            userActivity()
        }
    }

    /** The activity is going away: a REST or motion wake still in flight must not re-arm anything. */
    fun release() {
        released = true
        main.removeCallbacks(idleSleep)
    }

    /** Both switches, so no Android version keeps an old "turn the screen on" around. */
    private fun setTurnScreenOn(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) activity.setTurnScreenOn(on)
        @Suppress("DEPRECATION")
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    fun setBrightness(value: Float) {
        val lp: WindowManager.LayoutParams = activity.window.attributes
        lp.screenBrightness = value
        activity.window.attributes = lp
    }
}
