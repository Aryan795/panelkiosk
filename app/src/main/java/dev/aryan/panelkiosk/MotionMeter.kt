package dev.aryan.panelkiosk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The settings dialog's live bar: how much of the camera picture is changing right now,
 * against the share that wakes the panel (the red line, always at 60% of the width, so a
 * higher sensitivity shows as the same movement filling more of the bar).
 */
class MotionMeter(ctx: Context) : View(ctx) {
    private var changed = 0f
    private var trigger = 0f
    private var fired = false
    private val density = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40888888 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint().apply { color = 0xFFD32F2F.toInt() }

    /** trigger 0 = the camera isn't watching: an empty track */
    fun show(changed: Float, trigger: Float, fired: Boolean) {
        this.changed = changed
        this.trigger = trigger
        this.fired = fired
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        c.drawRoundRect(0f, 0f, w, h, r, r, track)
        if (trigger <= 0f) return
        val filled = (changed / trigger * LINE).coerceIn(0f, 1f) * w
        fill.color = if (fired || changed >= trigger) 0xFF43A047.toInt() else 0xFF78909C.toInt()
        if (filled > 0f) c.drawRoundRect(0f, 0f, filled, h, r, r, fill)
        val x = w * LINE
        c.drawRect(x - density, 0f, x + density, h, line)
    }

    private companion object {
        const val LINE = 0.6f
    }
}
