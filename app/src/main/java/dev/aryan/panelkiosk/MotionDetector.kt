package dev.aryan.panelkiosk

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Same algorithm as the dashboard's web detector: downsample every frame's
 * luma plane to a 32x24 grid (each cell the mean of 4x4 samples, as a canvas
 * downscale averages), diff against the previous frame, and require two
 * consecutive frames with enough changed cells.
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
    /** 1 = only big movement … [MAX_LEVEL] = the slightest change; see [LEVELS]. Takes effect on the next frame. */
    @Volatile var level: Int = 5

    // the latest reading, for the settings dialog's live meter
    /** share of the picture that changed between the last two samples */
    @Volatile var lastChanged: Float = 0f
        private set
    /** when two changed samples in a row last counted as motion (elapsedRealtime) */
    @Volatile var lastFiredMs: Long = 0L
        private set
    /** share of the picture that has to change, at the current level */
    val triggerShare: Float get() = LEVELS[level.coerceIn(1, MAX_LEVEL) - 1].second

    /** What a calibration measured: per level, the largest share of the picture that changed on its own. */
    class Calibration {
        val worst = FloatArray(MotionDetector.LEVELS.size)
        @Volatile var samples = 0
    }
    @Volatile private var calibration: Calibration? = null

    /** Start measuring how much a still room changes, at every level at once. */
    fun startCalibration() { calibration = Calibration() }

    /** Stop measuring; null if no calibration was running. */
    fun finishCalibration(): Calibration? = calibration.also { calibration = null }

    private val gridW = 32
    private val gridH = 24


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
                // Average 4x4 samples spread over the cell. A single pixel carries the sensor's
                // full noise, which in a dim room alone crossed the higher levels' thresholds;
                // the mean of 16 has about a quarter of it, while real movement shifts whole
                // areas and still shows.
                var sum = 0
                for (sy in 0 until 4) {
                    val y = gy * cellH + (2 * sy + 1) * cellH / 8
                    for (sx in 0 until 4) {
                        val x = gx * cellW + (2 * sx + 1) * cellW / 8
                        sum += luma.get(y * rowStride + x).toInt() and 0xFF
                    }
                }
                cur[gy * gridW + gx] = sum / 16f
            }
        }
        val previous = prev
        prev = cur
        if (skipFrames > 0) { skipFrames--; consecutive = 0; return }
        if (previous == null) return

        calibration?.let { cal ->
            for (l in LEVELS.indices) {
                val t = LEVELS[l].first
                var n = 0
                for (i in cur.indices) if (abs(cur[i] - previous[i]) > t) n++
                cal.worst[l] = maxOf(cal.worst[l], n.toFloat() / cur.size)
            }
            cal.samples++
        }

        val (cellThresh, pct) = LEVELS[level.coerceIn(1, MAX_LEVEL) - 1]
        var changed = 0
        for (i in cur.indices) if (abs(cur[i] - previous[i]) > cellThresh) changed++
        val share = changed.toFloat() / cur.size
        lastChanged = share
        if (share >= pct) {
            consecutive++
            if (consecutive >= 2) {
                consecutive = 0
                lastFiredMs = SystemClock.elapsedRealtime()
                onMotion()
            }
        } else {
            consecutive = 0
        }
    }

    companion object {
        /**
         * Per level: how far a cell's brightness must move (0-255) to count as changed, and the
         * share of the 32x24 cells that must change. 3, 5 and 8 have the old low, medium and high thresholds.
         * From about 10 up, a dim room's sensor noise alone gets near the line (the meter shows it);
         * 15 fires on two cells.
         */
        val LEVELS = arrayOf(
            38 to 0.20f, 34 to 0.15f, 30 to 0.12f, 26 to 0.09f, 22 to 0.06f,
            19 to 0.045f, 17 to 0.035f, 15 to 0.03f, 12 to 0.02f, 10 to 0.012f,
            9 to 0.009f, 8 to 0.0065f, 7 to 0.005f, 6 to 0.004f, 5 to 0.0026f,
        )
        val MAX_LEVEL = LEVELS.size

        /**
         * The most sensitive level a still room stays well clear of: every level up to it saw at
         * most half its trigger share, since two noisy samples in a row would wake the panel.
         * 0 = even level 1 wasn't clear, so something moved during the calibration.
         */
        fun safestLevel(cal: Calibration): Int {
            var best = 0
            for (l in LEVELS.indices) {
                if (cal.worst[l] <= LEVELS[l].second / 2) best = l + 1 else break
            }
            return best
        }
    }
}
