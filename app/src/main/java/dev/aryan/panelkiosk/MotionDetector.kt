package dev.aryan.panelkiosk

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Same algorithm as the dashboard's web detector: downsample every frame's
 * luma plane to a 32x24 grid, diff against the previous frame, and require
 * two consecutive frames with enough changed cells.
 */
class MotionDetector(
    private val context: Context,
    private val onMotion: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var prev: FloatArray? = null
    private var consecutive = 0
    @Volatile private var stopped = false
    @Volatile private var skipFrames = 4
    @Volatile private var lastSampleMs = 0L
    @Volatile var sensitivity: String = "medium"

    private val gridW = 32
    private val gridH = 24

    private fun thresholds(): Pair<Int, Float> = when (sensitivity) {
        "low" -> 30 to 0.12f
        "high" -> 15 to 0.03f
        else -> 22 to 0.06f
    }

    /**
     * Front camera if there is one, else back, else whatever else exists (a USB
     * webcam on a TV box). Any failure — no camera, a camera held by another app,
     * a broken HAL — reports [onUnavailable] instead of crashing the kiosk.
     */
    fun start(owner: LifecycleOwner, onStarted: (lens: String) -> Unit, onUnavailable: (why: String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            try {
                val p = future.get()
                val (selector, lens) = when {
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA to "front"
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA to "back"
                    else -> p.availableCameraInfos.firstOrNull()?.cameraSelector?.let { it to "external" }
                        ?: run { onUnavailable("no camera found"); return@addListener }
                }
                val a = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                a.setAnalyzer(executor) { image ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastSampleMs >= 330) {   // ~3 fps is plenty
                            lastSampleMs = now
                            analyze(image.planes[0].buffer, image.width, image.height, image.planes[0].rowStride)
                        }
                    } finally {
                        image.close()
                    }
                }
                p.unbindAll()
                p.bindToLifecycle(owner, selector, a)
                provider = p
                analysis = a
                onStarted(lens)
            } catch (e: Exception) {
                onUnavailable(e.message ?: e.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopped = true
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        provider = null
        analysis = null
        prev = null
        executor.shutdown()
    }

    /** Call on screen sleep/wake so the lighting shift is not read as motion. */
    fun rebaseline() {
        skipFrames = 8
        consecutive = 0
    }

    private fun analyze(luma: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int) {
        val cur = FloatArray(gridW * gridH)
        val cellW = w / gridW
        val cellH = h / gridH
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                // one sample from the middle of each cell is enough at this scale
                val x = gx * cellW + cellW / 2
                val y = gy * cellH + cellH / 2
                cur[gy * gridW + gx] = (luma.get(y * rowStride + x).toInt() and 0xFF).toFloat()
            }
        }
        val previous = prev
        prev = cur
        if (skipFrames > 0) { skipFrames--; consecutive = 0; return }
        if (previous == null) return

        val (cellThresh, pct) = thresholds()
        var changed = 0
        for (i in cur.indices) if (abs(cur[i] - previous[i]) > cellThresh) changed++
        if (changed.toFloat() / cur.size >= pct) {
            consecutive++
            if (consecutive >= 2) { consecutive = 0; onMotion() }
        } else {
            consecutive = 0
        }
    }
}
