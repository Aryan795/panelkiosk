package dev.aryan.panelkiosk

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * Camera motion wake that keeps working with the display off.
 *
 * The camera used to belong to the activity, and Android stops an activity's
 * camera the moment the screen goes off, so a truly-off panel could never see
 * anyone walk in. A foreground service of type camera keeps it open, a partial
 * wake lock keeps the CPU reading frames, and on motion the display is switched
 * back on. With no lock screen the dashboard is simply there.
 */
class MotionService : LifecycleService() {

    private val main = Handler(Looper.getMainLooper())
    private var detector: MotionDetector? = null
    private var cpu: PowerManager.WakeLock? = null
    private var lastMotionMs = 0L

    // the display switching on or off changes the light the camera sees
    private val displayEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { detector?.rebaseline() }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
        ContextCompat.registerReceiver(
            this, displayEvents,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val prefs = Prefs(this)
        if (!prefs.motionWake) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            goForeground()
        } catch (e: Exception) {
            // Android 11+ won't start a camera service from the background (e.g. a
            // restart after the process was killed); MainActivity starts it again
            // the next time it's in front.
            status = "could not start (${e.javaClass.simpleName})"
            stopSelf()
            return START_NOT_STICKY
        }
        holdCpu()
        val d = detector
        if (d == null) startDetector(prefs) else d.sensitivity = prefs.sensitivity
        return START_STICKY
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Motion wake", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("PanelKiosk")
            .setContentText("Watching for motion to switch the screen on")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(
            this, NOTE_ID, note,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
        )
    }

    /** Keeps frames coming with the display off. Doze honours it only with battery optimisation off. */
    @SuppressLint("WakelockTimeout") // held for exactly as long as the service runs; released in onDestroy
    private fun holdCpu() {
        if (cpu?.isHeld == true) return
        cpu = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "panelkiosk:motion")
            .apply { acquire() }
    }

    private fun startDetector(prefs: Prefs) {
        status = "starting"
        detector = MotionDetector(this) { main.post(::onMotion) }.also {
            it.sensitivity = prefs.sensitivity
            it.start(
                this,
                onStarted = { lens -> status = "watching ($lens camera), also with the screen off" },
                onUnavailable = { why -> status = "unavailable: $why"; stopSelf() },
            )
        }
    }

    private fun onMotion() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMotionMs < 5000) return // also keeps an occupied room's panel awake
        lastMotionMs = now
        Waker.wakeDisplay(this)        // lights the display, even from true screen-off
        onMotionListener?.invoke()     // the activity lifts its dim cover and tells the dashboard
        detector?.rebaseline()         // the panel lighting up is not more motion
    }

    override fun onDestroy() {
        detector?.stop()
        detector = null
        cpu?.let { if (it.isHeld) it.release() }
        cpu = null
        runCatching { unregisterReceiver(displayEvents) }
        if (status == "starting" || status.startsWith("watching")) status = "off"
        if (running === this) running = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "motion"
        private const val NOTE_ID = 1
        @Volatile private var running: MotionService? = null

        /** what the camera is doing, for the settings screen */
        @Volatile var status = "off"

        /** set by MainActivity while it exists; called on the main thread */
        @Volatile var onMotionListener: (() -> Unit)? = null

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, MotionService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MotionService::class.java)) }

        /** Call when the panel's own light changes (sleep or wake), so it isn't read as motion. */
        fun rebaseline() { running?.detector?.rebaseline() }
    }
}
