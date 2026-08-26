package dev.aryan.panelkiosk

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var blackout: View
    private lateinit var screen: ScreenController
    private lateinit var api: ApiServer
    private lateinit var owner: OwnerTools
    private var motion: MotionDetector? = null
    private var cornerTaps = 0
    private var lastTapMs = 0L
    private var lastMotionWakeMs = 0L

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        owner = OwnerTools(this)
        owner.applyBasePolicies()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        web = WebView(this)
        blackout = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { screen.wake(); notifyPage("kiosk-wake") }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(blackout, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        screen = ScreenController(this, blackout, prefs)
        api = ApiServer(prefs, screen, onReboot = { owner.reboot() })

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
        web.addJavascriptInterface(FullyBridge(screen), "fully")

        // five quick taps in the top-left corner opens settings
        web.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN &&
                ev.x < 120 && ev.y < 120) {
                val now = System.currentTimeMillis()
                cornerTaps = if (now - lastTapMs < 1200) cornerTaps + 1 else 1
                lastTapMs = now
                if (cornerTaps >= 5) { cornerTaps = 0; showSettings() }
            }
            false
        }

        if (!prefs.configured) showSettings() else web.loadUrl(prefs.url)
        api.start()
        ensureCamera()
        requestBatteryExemption()
    }

    override fun onResume() {
        super.onResume()
        applyLockMode()
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

    /** Keeps the camera/motion loop alive under aggressive OEM power management. */
    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun ensureCamera() {
        if (!prefs.motionWake) { motion?.stop(); motion = null; return }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        startMotion()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMotion()
    }

    private fun startMotion() {
        if (motion != null) return
        motion = MotionDetector(this) {
            val now = System.currentTimeMillis()
            if (now - lastMotionWakeMs > 5000) {
                lastMotionWakeMs = now
                val wasOff = !screen.screenOn
                screen.wake()
                if (wasOff) motion?.rebaseline()
                notifyPage("kiosk-motion")
            }
        }.also {
            it.sensitivity = prefs.sensitivity
            it.start(this)
        }
    }

    private fun notifyPage(event: String) {
        runOnUiThread {
            web.evaluateJavascript("window.dispatchEvent(new Event('$event'))", null)
        }
    }

    private fun showSettings() {
        val ctx = this
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val status = TextView(ctx).apply {
            text = if (owner.isOwner)
                "Device owner: active — silent pinning, remote reboot, resident camera"
            else
                "Device owner: not set. For silent pinning + remote reboot run:\n" +
                "adb shell dpm set-device-owner dev.aryan.panelkiosk/.AdminReceiver"
            textSize = 12f
        }
        val url = EditText(ctx).apply {
            hint = "Dashboard URL (http://server:8080)"
            setText(prefs.url); inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val pass = EditText(ctx).apply {
            hint = "REST API password (:2323, optional)"
            setText(prefs.apiPassword)
        }
        val motionCb = CheckBox(ctx).apply { text = "Camera motion wake"; isChecked = prefs.motionWake }
        val sens = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("low", "medium", "high"))
            setSelection(listOf("low", "medium", "high").indexOf(prefs.sensitivity))
        }
        val lockCb = CheckBox(ctx).apply {
            text = "Lock app (kiosk pinning" +
                (if (owner.isOwner) ", silent)" else " — shows Android's pin toast without device owner)")
            isChecked = prefs.lockApp
        }
        val trueOffCb = CheckBox(ctx).apply {
            text = "True screen off when idle (disables camera wake while off)"
            isChecked = prefs.trueOff
        }
        listOf(status, url, pass, motionCb, sens, lockCb, trueOffCb).forEach { col.addView(it) }
        if (owner.isOwner) {
            col.addView(Button(ctx).apply {
                text = "Release device owner (allows uninstall)"
                setOnClickListener {
                    owner.releaseOwnership()
                    android.widget.Toast.makeText(ctx, "Device owner released", android.widget.Toast.LENGTH_LONG).show()
                }
            })
        }

        AlertDialog.Builder(ctx)
            .setTitle("PanelKiosk settings")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                prefs.url = url.text.toString().trim()
                prefs.apiPassword = pass.text.toString().trim()
                prefs.motionWake = motionCb.isChecked
                prefs.sensitivity = sens.selectedItem as String
                prefs.lockApp = lockCb.isChecked
                prefs.trueOff = trueOffCb.isChecked
                prefs.configured = true
                if (prefs.trueOff && !owner.isAdmin) requestAdminIfNeeded()
                if (!prefs.motionWake) { motion?.stop(); motion = null } else ensureCamera()
                motion?.sensitivity = prefs.sensitivity
                applyLockMode()
                web.loadUrl(prefs.url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestAdminIfNeeded() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lets PanelKiosk turn the screen fully off when the room is empty.")
            })
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
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Deprecated("kiosk: back disabled")
    override fun onBackPressed() { /* kiosk mode: swallow */ }

    override fun onDestroy() {
        api.stop()
        motion?.stop()
        super.onDestroy()
    }
}
