package dev.aryan.panelkiosk

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Minimal HTTP server on :2323 speaking the Fully Kiosk Remote Admin dialect
 * (`/?cmd=screenOn&password=...`) — the dashboard server's existing
 * `screen.fullyHost` config, and Home Assistant's Fully integration, both
 * work against it unmodified.
 */
class ApiServer(
    private val prefs: Prefs,
    private val screen: ScreenController,
    private val onReboot: () -> Boolean,
) {
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        thread(isDaemon = true, name = "panelkiosk-api") {
            try {
                val server = ServerSocket(2323)
                socket = server
                while (!server.isClosed) {
                    val client = server.accept()
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (_: Exception) { /* port busy or shutdown */ }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 5000
            val line = BufferedReader(InputStreamReader(c.getInputStream())).readLine() ?: return
            // "GET /?cmd=screenOn&password=x HTTP/1.1"
            val path = line.split(" ").getOrNull(1) ?: return
            val query = path.substringAfter('?', "")
            val params = query.split('&').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
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
                    "deviceInfo" -> """{"appVersionName":"PanelKiosk 1.1","screenOn":${screen.screenOn}}"""
                    "rebootDevice" ->
                        if (onReboot()) ok("rebootDevice")
                        else """{"status":"error","statustext":"needs device owner"}"""
                    else -> """{"status":"error","statustext":"unknown cmd"}"""
                }
            }
            val response = "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
            c.getOutputStream().write(response.toByteArray())
        }
    }

    private fun ok(cmd: String) = """{"status":"OK","statustext":"$cmd"}"""
}
