package dev.aryan.panelkiosk

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("panelkiosk", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v).apply()

    var apiPassword: String
        get() = sp.getString("apiPassword", "") ?: ""
        set(v) = sp.edit().putString("apiPassword", v).apply()

    var motionWake: Boolean
        get() = sp.getBoolean("motionWake", true)
        set(v) = sp.edit().putBoolean("motionWake", v).apply()

    /** Camera motion sensitivity, 1 (only big movement) to [MotionDetector.MAX_LEVEL] (the slightest change). */
    var sensitivityLevel: Int
        get() = sp.getInt("sensitivityLevel", 0).takeIf { it in 1..MotionDetector.MAX_LEVEL }
            // before 1.6 it was a preset; levels 3, 5 and 8 keep low, medium and high's thresholds
            // (steadier than before, as each cell is now a 16-pixel mean rather than one pixel)
            ?: when (sp.getString("sensitivity", "medium")) { "low" -> 3; "high" -> 8; else -> 5 }
        set(v) = sp.edit().putInt("sensitivityLevel", v.coerceIn(1, MotionDetector.MAX_LEVEL)).apply()

    /** true = lock the device for real (needs device admin); false = backlight-off soft sleep */
    var trueOff: Boolean
        get() = sp.getBoolean("trueOff", false)
        set(v) = sp.edit().putBoolean("trueOff", v).apply()

    /**
     * Minutes without camera motion or a touch before the panel sleeps by itself; 0 = never.
     * Stands down while the page drives the screen through window.fully (most pages don't).
     */
    var idleMinutes: Int
        get() = sp.getInt("idleMinutes", 0)
        set(v) = sp.edit().putInt("idleMinutes", v).apply()

    /** pin the app (lock task). Silent — no toast — when device owner. */
    var lockApp: Boolean
        get() = sp.getBoolean("lockApp", false)
        set(v) = sp.edit().putBoolean("lockApp", v).apply()

    /** auto (follow the device) / landscape / portrait */
    var orientation: String
        get() = sp.getString("orientation", "auto") ?: "auto"
        set(v) = sp.edit().putString("orientation", v).apply()

    /** the battery-optimisation prompt is shown once; Settings offers it again */
    var askedBattery: Boolean
        get() = sp.getBoolean("askedBattery", false)
        set(v) = sp.edit().putBoolean("askedBattery", v).apply()

    var configured: Boolean
        get() = sp.getBoolean("configured", false)
        set(v) = sp.edit().putBoolean("configured", v).apply()
}
