package dev.aryan.panelkiosk

import android.webkit.JavascriptInterface

/**
 * Injected as `window.fully`, mirroring the subset of the Fully Kiosk JS API
 * the dashboard already uses — the web app needs zero changes to drive us.
 */
class FullyBridge(private val screen: ScreenController) {

    @JavascriptInterface
    fun turnScreenOn() = screen.wake()

    @JavascriptInterface
    fun turnScreenOff() = screen.sleep()

    @JavascriptInterface
    fun turnScreenOff(keepAlive: Boolean) = screen.sleep()

    @JavascriptInterface
    fun setScreenBrightness(v: Float) = screen.setBrightness(v / 255f)

    @JavascriptInterface
    fun getScreenOn(): Boolean = screen.screenOn
}
