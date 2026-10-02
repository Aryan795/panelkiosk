package dev.aryan.panelkiosk

import android.webkit.JavascriptInterface

/**
 * Injected as `window.fully`, mirroring the subset of the Fully Kiosk JS API
 * the dashboard already uses — the web app needs zero changes to drive us.
 */
class FullyBridge(private val screen: ScreenController) {

    // a page that switches the screen itself runs its own sleep timer; ours stands down

    @JavascriptInterface
    fun turnScreenOn() { screen.pageDrivesSleep = true; screen.wake() }

    @JavascriptInterface
    fun turnScreenOff() { screen.pageDrivesSleep = true; screen.sleep() }

    @JavascriptInterface
    fun turnScreenOff(keepAlive: Boolean) = turnScreenOff()

    @JavascriptInterface
    fun setScreenBrightness(v: Float) = screen.setBrightness(v / 255f)

    @JavascriptInterface
    fun getScreenOn(): Boolean = screen.screenOn
}
