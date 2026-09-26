package dev.aryan.panelkiosk

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var blackout: View
    private lateinit var screen: ScreenController
    private lateinit var api: ApiServer
    private lateinit var owner: OwnerTools
    private var cornerTaps = 0
    private var lastTapMs = 0L
    /** the renderer died: the WebView is destroyed and must not be touched again */
    private var webDead = false

    private val main = Handler(Looper.getMainLooper())
    private val retryLoad = Runnable { if (!isFinishing && !webDead) web.loadUrl(prefs.url) }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        owner = OwnerTools(this)
        owner.applyBasePolicies()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showOverLockScreen()
        applyOrientation()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        blackout = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { screen.wake(); notifyPage("kiosk-wake") }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(blackout, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        // Keep the page clear of notches and punch-holes. Android 15 draws every
        // app edge-to-edge, and the system bars are hidden anyway, so only the
        // cutout needs room.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cut.left, cut.top, cut.right, cut.bottom)
            insets
        }

        screen = ScreenController(this, blackout, prefs)
        api = ApiServer(prefs, screen, onReboot = { owner.reboot() })
        // the camera service calls this on motion — also while the display is off
        MotionService.onMotionListener = motionListener

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100 // the dashboard sizes its own text; OEM font scaling would break the grid
        }
        // The dashboard has its own dark and light themes; never let WebView auto-darken them.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        }
        web.webViewClient = KioskClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
        web.addJavascriptInterface(FullyBridge(screen), "fully")

        // five quick taps in the top-left corner open the settings (64dp, so the
        // target is the same physical size on any screen density)
        val hotCorner = 64 * resources.displayMetrics.density
        web.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.x < hotCorner && ev.y < hotCorner) {
                val now = System.currentTimeMillis()
                cornerTaps = if (now - lastTapMs < 1200) cornerTaps + 1 else 1
                lastTapMs = now
                if (cornerTaps >= 5) { cornerTaps = 0; showSettings() }
            }
            false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* kiosk: back does nothing */ }
        })

        api.start()
        if (prefs.configured) {
            web.loadUrl(prefs.url)
            ensureCamera()
        } else {
            showSettings() // camera and battery prompts wait until Save, so first run is one dialog at a time
        }
    }

    override fun onResume() {
        super.onResume()
        applyLockMode()
    }

    /** Keeps a kiosk alive through the failures a wall panel actually meets. */
    private inner class KioskClient : WebViewClient() {
        // Right after boot Wi-Fi is often not up yet, or the server is restarting:
        // show a holding page and keep retrying rather than strand the error page.
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) waitAndRetry(view, error.description.toString())
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 500) waitAndRetry(view, "HTTP ${response.statusCode}")
        }

        // Low-memory devices kill the WebView renderer; unhandled, the app dies with it.
        @TargetApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The WebView is unusable now: detach and destroy it at once, so no queued
            // retry or motion event can reach it, then rebuild the activity.
            webDead = true
            main.removeCallbacks(retryLoad)
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            // A page that kills the renderer every time must not spin: back off 1 s → 60 s.
            val now = SystemClock.elapsedRealtime()
            renderCrashes = if (now - lastRenderCrashMs < 120_000) renderCrashes + 1 else 1
            lastRenderCrashMs = now
            val delayMs = minOf(60_000L, 1000L shl minOf(renderCrashes - 1, 6))
            main.postDelayed({ recreate() }, delayMs)
            return true
        }
    }

    private fun waitAndRetry(view: WebView, reason: String) {
        val html = """<html><body style="margin:0;height:100vh;display:flex;align-items:center;
            justify-content:center;background:#111;color:#999;font:16px sans-serif;text-align:center">
            <div>Waiting for the dashboard…<br><small>${escape(prefs.url)} · ${escape(reason)}</small></div>
            </body></html>"""
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        main.removeCallbacks(retryLoad)
        main.postDelayed(retryLoad, 10_000)
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Show above the lock screen and light the display when launched. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun applyOrientation() {
        requestedOrientation = when (prefs.orientation) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED // the device's own rotation setting
        }
    }

    /** Lock-task ("app pinning"). Silent — no toast, no swipe-out — as device owner. */
    private fun applyLockMode() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pinned = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        if (prefs.lockApp && !pinned) {
            owner.setStatusBarDisabled(true)
            runCatching { startLockTask() }
        } else if (!prefs.lockApp && pinned) {
            runCatching { stopLockTask() }
            owner.setStatusBarDisabled(false)
        }
    }

    private fun isIgnoringBattery(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    /** Keeps the camera/motion loop alive under aggressive OEM power management. */
    @SuppressLint("BatteryLife") // a kiosk is exactly the case this prompt exists for
    private fun requestBatteryExemption() {
        if (isIgnoringBattery()) return
        openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    }

    /** Settings screens differ between Android versions and brands; fall back to the main one. */
    private fun openSettings(action: String, data: Uri? = null) {
        val ok = runCatching { startActivity(Intent(action).apply { if (data != null) setData(data) }) }.isSuccess
        if (!ok) runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun isHomeApp(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val resolved = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    /**
     * Android 10+ blocks activity starts from the background. The Home app and
     * a device owner are always allowed; "display over other apps" is enough on
     * Android 10–14 but not on 15+, where it also needs a visible overlay.
     */
    private fun bootStatus(): String = when {
        isHomeApp() -> "Starts on boot: yes (PanelKiosk is the Home app)"
        owner.isOwner -> "Starts on boot: yes (device owner)"
        Build.VERSION.SDK_INT < 29 -> "Starts on boot: yes"
        Build.VERSION.SDK_INT < 35 && Settings.canDrawOverlays(this) ->
            "Starts on boot: yes (allowed to display over other apps)"
        else -> "Starts on boot: not guaranteed on Android ${Build.VERSION.RELEASE}. " +
            "Make PanelKiosk the Home app for a reliable kiosk."
    }

    private val motionListener: () -> Unit = {
        screen.wake()               // lift the dim cover (the service has lit the display)
        notifyPage("kiosk-motion")  // the dashboard tells the server, which restarts its idle timer
    }

    /** Start, retune or stop the camera service. It must start while we're in front (Android 11+). */
    private fun ensureCamera() {
        if (!prefs.motionWake) { MotionService.stop(this); return }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            MotionService.status = "no camera on this device"
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            val ask = mutableListOf(Manifest.permission.CAMERA)
            // optional: shows the "watching for motion" notification; the service runs either way
            if (Build.VERSION.SDK_INT >= 33) ask.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, ask.toTypedArray(), 1)
            return
        }
        MotionService.start(this)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != 1) return
        val camera = perms.indexOf(Manifest.permission.CAMERA)
        if (camera >= 0 && results.getOrNull(camera) == PackageManager.PERMISSION_GRANTED) MotionService.start(this)
        else MotionService.status = "camera permission denied"
    }

    /**
     * Android 14+ lets the user revoke "Turn screen on" special access, and then no
     * wake lock can light the display. There's no public API for it, so check the
     * app op by name and assume allowed if this Android doesn't know it.
     */
    private fun canTurnScreenOn(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return runCatching {
            val mode = getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpNoThrow("android:turn_screen_on", applicationInfo.uid, packageName)
            mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
        }.getOrDefault(true)
    }

    private fun notifyPage(event: String) {
        runOnUiThread {
            if (webDead) return@runOnUiThread
            web.evaluateJavascript("window.dispatchEvent(new Event('$event'))", null)
        }
    }

    private fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        return if (s.isEmpty() || s.contains("://")) s else "http://$s"
    }

    private fun showSettings() {
        val ctx = this
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        fun note(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        fun action(label: String, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        val url = EditText(ctx).apply {
            hint = "Dashboard URL, e.g. http://192.168.1.10:8080"
            setText(prefs.url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pass = EditText(ctx).apply {
            hint = "REST API password (:2323, optional)"
            setText(prefs.apiPassword)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val rotations = listOf(
            "auto" to "Rotation: follow the device",
            "landscape" to "Rotation: landscape",
            "portrait" to "Rotation: portrait",
        )
        val rotation = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, rotations.map { it.second })
            setSelection(rotations.indexOfFirst { it.first == prefs.orientation }.coerceAtLeast(0))
        }
        val motionCb = CheckBox(ctx).apply { text = "Camera motion wake"; isChecked = prefs.motionWake }
        val sensitivities = listOf("low", "medium", "high")
        val sens = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, sensitivities)
            setSelection(sensitivities.indexOf(prefs.sensitivity).coerceAtLeast(0))
        }
        val lockCb = CheckBox(ctx).apply {
            text = "Lock app (kiosk pinning" +
                (if (owner.isOwner) ", silent)" else " — Android shows a pinning prompt without device owner)")
            isChecked = prefs.lockApp
        }
        val trueOffCb = CheckBox(ctx).apply {
            text = "True screen off when idle — the camera keeps watching and switches it back on " +
                "(set the lock screen to None or Swipe)"
            isChecked = prefs.trueOff
        }

        listOf(url, pass, rotation, motionCb).forEach { col.addView(it) }
        col.addView(note("Camera: ${MotionService.status}"))
        listOf(sens, lockCb, trueOffCb).forEach { col.addView(it) }
        if (prefs.trueOff && !owner.isAdmin) {
            col.addView(note("No device admin: Android's own screen timeout switches the display off, " +
                "so set it short (15–30 s) in Display settings. Device admin turns it off at once."))
        }
        if (!canTurnScreenOn()) {
            col.addView(note("Android is blocking PanelKiosk from switching the screen on. Allow it in " +
                "Settings → Apps → Special app access → Turn screen on."))
            col.addView(action("Open PanelKiosk's app settings") {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            })
        }

        col.addView(note(bootStatus()))
        if (!isHomeApp()) col.addView(action("Set as Home app (most reliable autostart)") {
            openSettings(Settings.ACTION_HOME_SETTINGS)
        })
        if (Build.VERSION.SDK_INT in 29..34 && !owner.isOwner && !Settings.canDrawOverlays(ctx)) {
            col.addView(action("Allow start on boot (display over other apps)") {
                openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            })
        }
        if (!isIgnoringBattery()) col.addView(action("Stop Android from killing PanelKiosk (battery)") {
            requestBatteryExemption()
        })
        col.addView(note("Some brands (Xiaomi, Huawei, Oppo, Vivo…) also need \"Autostart\" enabled in their own settings."))

        col.addView(note(
            if (owner.isOwner) "Device owner: active — silent pinning, remote reboot, resident camera"
            else "Device owner: not set. For silent pinning and remote reboot run:\n" +
                "adb shell dpm set-device-owner dev.aryan.panelkiosk/.AdminReceiver"
        ))
        if (owner.isOwner) col.addView(action("Release device owner (allows uninstall)") {
            owner.releaseOwnership()
            Toast.makeText(ctx, "Device owner released", Toast.LENGTH_LONG).show()
        })
        col.addView(note("Reopen these settings any time: five quick taps in the top-left corner."))

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("PanelKiosk ${BuildConfig.VERSION_NAME}")
            .setView(ScrollView(ctx).apply { addView(col) }) // small phones: the list must scroll
            .setCancelable(prefs.configured)
            .setPositiveButton("Save") { _, _ ->
                val address = normalizeUrl(url.text.toString())
                if (address.isEmpty()) {
                    Toast.makeText(ctx, "Enter the dashboard's address first", Toast.LENGTH_LONG).show()
                    showSettings()
                    return@setPositiveButton
                }
                prefs.url = address
                prefs.apiPassword = pass.text.toString().trim()
                prefs.orientation = rotations[rotation.selectedItemPosition].first
                prefs.motionWake = motionCb.isChecked
                prefs.sensitivity = sens.selectedItem as String
                prefs.lockApp = lockCb.isChecked
                prefs.trueOff = trueOffCb.isChecked
                prefs.configured = true
                applyOrientation()
                if (prefs.trueOff && !owner.isAdmin) requestAdminIfNeeded()
                ensureCamera() // also hands a new sensitivity to the running service
                applyLockMode()
                if (!prefs.askedBattery) { prefs.askedBattery = true; requestBatteryExemption() }
                main.removeCallbacks(retryLoad)
                if (!webDead) web.loadUrl(prefs.url)
            }
        if (prefs.configured) dialog.setNegativeButton("Cancel", null)
        dialog.show()
    }

    private fun requestAdminIfNeeded() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            runCatching {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets PanelKiosk turn the screen fully off when the room is empty.")
                })
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        api.stop()
        // the camera service keeps running across a recreate; only drop our hook into it
        if (MotionService.onMotionListener === motionListener) MotionService.onMotionListener = null
        if (!webDead) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    private companion object {
        // survive recreate(), so the backoff sees consecutive crashes
        var renderCrashes = 0
        var lastRenderCrashMs = 0L
    }
}
