package dev.aryan.panelkiosk

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("panelkiosk", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "http://192.168.1.1:8080") ?: ""
        set(v) = sp.edit().putString("url", v).apply()

    var apiPassword: String
        get() = sp.getString("apiPassword", "") ?: ""
        set(v) = sp.edit().putString("apiPassword", v).apply()

    var motionWake: Boolean
        get() = sp.getBoolean("motionWake", true)
        set(v) = sp.edit().putBoolean("motionWake", v).apply()

    /** low / medium / high */
    var sensitivity: String
        get() = sp.getString("sensitivity", "medium") ?: "medium"
        set(v) = sp.edit().putString("sensitivity", v).apply()

    /** true = lock the device for real (needs device admin); false = backlight-off soft sleep */
    var trueOff: Boolean
        get() = sp.getBoolean("trueOff", false)
        set(v) = sp.edit().putBoolean("trueOff", v).apply()

    /** pin the app (lock task). Silent — no toast — when device owner. */
    var lockApp: Boolean
        get() = sp.getBoolean("lockApp", false)
        set(v) = sp.edit().putBoolean("lockApp", v).apply()

    var configured: Boolean
        get() = sp.getBoolean("configured", false)
        set(v) = sp.edit().putBoolean("configured", v).apply()
}
