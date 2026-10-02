package dev.aryan.panelkiosk

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Minimal HTTP server on :2323 speaking the Fully Kiosk Remote Admin dialect
 * (`/?cmd=screenOn&password=...`), enough for a Home Assistant `rest_command`, a
 * Moonraker power device and other plain HTTP clients. It is not a full Fully
 * implementation: Home Assistant's Fully Kiosk integration needs deviceID,
 * deviceName, Mac and the settings commands, which it doesn't offer.
 */
class ApiServer(
    private val context: Context,
    private val prefs: Prefs,
    private val screen: ScreenController,
    private val onReboot: () -> Boolean,
) {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false
    // Bounded, so a burst of connections (a LAN scan) can't exhaust threads on a small tablet;
    // with DEADLINE_MS per request, a slow client can't hold a worker for long either.
    @Volatile private var workers: ExecutorService? = null

    fun start() {
        if (running) return
        running = true
        val pool = Executors.newFixedThreadPool(8)
        workers = pool
        thread(isDaemon = true, name = "panelkiosk-api") {
            try {
                val server = ServerSocket(2323)
                socket = server
                // stop() may have run before the bind; then nothing else would close this socket
                // and the old server would keep :2323 from the next activity
                if (!running) { server.close(); return@thread }
                while (running && !server.isClosed) {
                    val client = server.accept()
                    try {
                        pool.execute { handle(client) }
                    } catch (_: RejectedExecutionException) {
                        client.close()
                    }
                }
            } catch (_: Exception) {
                /* port busy or shutdown */
            } finally {
                // whatever ended the loop, leave a state start() can recover from
                running = false
                pool.shutdownNow()
            }
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
        workers?.shutdownNow()
        workers = null
    }

    /** One request. Never throws: an uncaught exception in any thread would crash the kiosk app. */
    private fun handle(client: Socket) {
        // a whole-request deadline: soTimeout alone restarts with every byte a slow client sends
        val deadline = deadlines.schedule({ runCatching { client.close() } }, DEADLINE_MS, TimeUnit.MILLISECONDS)
        try {
            client.use { c -> respond(c) }
        } catch (_: Exception) {
            // timed-out, reset or malformed requests just drop the connection
        } finally {
            deadline.cancel(false)
        }
    }

    private fun respond(c: Socket) {
        c.soTimeout = DEADLINE_MS.toInt()
        val line = BufferedReader(InputStreamReader(c.getInputStream())).readLine() ?: return
        // "GET /?cmd=screenOn&password=x HTTP/1.1"
        val path = line.split(" ").getOrNull(1) ?: return
        val query = path.substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            // a malformed %-escape drops that parameter instead of failing the request
            if (kv.size == 2) runCatching { kv[0] to URLDecoder.decode(kv[1], "UTF-8") }.getOrNull() else null
        }.toMap()

        val expected = prefs.apiPassword
        val body: String
        val status: String
        if (expected.isNotEmpty() && params["password"] != expected) {
            status = "403 Forbidden"
            body = """{"status":"error","statustext":"wrong password"}"""
        } else {
            status = "200 OK"
            body = when (params["cmd"]) {
                "screenOn" -> { screen.wake(); ok("screenOn") }
                "screenOff" -> { screen.sleep(); ok("screenOff") }
                "deviceInfo" -> deviceInfo().toString()
                "rebootDevice" ->
                    if (onReboot()) ok("rebootDevice")
                    else """{"status":"error","statustext":"needs device owner and Android 7+"}"""
                else -> """{"status":"error","statustext":"unknown cmd"}"""
            }
        }
        val response = "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
        c.getOutputStream().write(response.toByteArray())
    }

    private fun ok(cmd: String) = """{"status":"OK","statustext":"$cmd"}"""

    private fun deviceInfo() = JSONObject().apply {
        put("appVersionName", "PanelKiosk ${BuildConfig.VERSION_NAME}")
        put("screenOn", screen.displayLit) // the real display, not just what was last asked for
        putBattery(this)
    }

    /**
     * Battery state, named as in Fully's API. The temperature is the battery's, the one that
     * matters for a phone charging inside an enclosure (other sensors need device owner's
     * HardwarePropertiesManager, or root). A device without a battery reports only isPlugged.
     */
    private fun putBattery(info: JSONObject) {
        // ACTION_BATTERY_CHANGED is sticky: a null receiver just returns the latest state
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        info.put("isPlugged", b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        // mains-powered boxes report placeholder level and temperature with present=false
        if (!b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) info.put("batteryLevel", (level * 100 / scale).coerceIn(0, 100))
        val tenths = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths != Int.MIN_VALUE) info.put("batteryTemperature", tenths / 10.0)
    }

    private companion object {
        const val DEADLINE_MS = 3000L

        // One timer thread for the whole process, shared by every ApiServer instance, so an
        // activity recreate never leaks one; a request that finishes in time removes its timer.
        val deadlines = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "panelkiosk-api-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
