package dev.sensorlab.app.feature.heart

import android.Manifest
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.KeepScreenOn
import dev.sensorlab.app.ui.components.LiveChart
import dev.sensorlab.app.ui.components.MeterBar
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.PermissionGate
import dev.sensorlab.app.ui.components.Pill
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import java.nio.ByteBuffer
import kotlin.math.max

private const val FS = 30f            // resampling rate (Hz)
private const val WINDOW_SEC = 12f    // analysis window
private const val MEASURE_SEC = 20f   // a full measurement

/**
 * Photoplethysmography: with the flash on and a fingertip over the main camera, blood volume
 * changes modulate the brightness of each frame. Autocorrelation of the detrended signal gives
 * the pulse period.
 */
private class PpgProcessor {
    private val times = ArrayDeque<Double>()
    private val values = ArrayDeque<Double>()
    private var startTime = Double.NaN

    val waveform = SeriesBuffer(1, 240)
    var fingerDetected by mutableStateOf(false)
    var bpm by mutableFloatStateOf(Float.NaN)
    var confidence by mutableFloatStateOf(0f)
    var elapsed by mutableFloatStateOf(0f)
    var brightness by mutableFloatStateOf(0f)

    fun reset() {
        times.clear(); values.clear(); startTime = Double.NaN
        waveform.clear(); bpm = Float.NaN; confidence = 0f; elapsed = 0f
    }

    fun onFrame(timeSec: Double, luma: Float, chromaRed: Float) {
        brightness = luma
        val finger = chromaRed > 150f && luma in 15f..240f
        if (finger != fingerDetected) {
            fingerDetected = finger
            if (!finger) reset()
        }
        if (!finger) return
        if (startTime.isNaN()) startTime = timeSec
        elapsed = (timeSec - startTime).toFloat()
        times.addLast(timeSec)
        values.addLast(luma.toDouble())
        while (times.isNotEmpty() && timeSec - times.first() > WINDOW_SEC) {
            times.removeFirst(); values.removeFirst()
        }
        analyse()
    }

    private fun analyse() {
        if (times.size < 20) return
        val t0 = times.first()
        val span = times.last() - t0
        val n = (span * FS).toInt()
        if (n < 40) return
        // Resample to a uniform grid
        val x = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = t0 + i / FS.toDouble()
            while (j < times.size - 2 && times[j + 1] < t) j++
            val ta = times[j]; val tb = times[j + 1]
            val f = if (tb > ta) ((t - ta) / (tb - ta)).coerceIn(0.0, 1.0) else 0.0
            x[i] = values[j] + f * (values[j + 1] - values[j])
        }
        // Detrend with a 1 s moving average, then smooth with a 5-point average
        val half = (FS / 2).toInt()
        val detrended = DoubleArray(n) { i ->
            var s = 0.0; var c = 0
            for (k in max(0, i - half)..minOf(n - 1, i + half)) {
                s += x[k]; c++
            }
            x[i] - s / c
        }
        val y = DoubleArray(n) { i ->
            var s = 0.0; var c = 0
            for (k in max(0, i - 2)..minOf(n - 1, i + 2)) {
                s += detrended[k]; c++
            }
            -s / c // invert: more blood = darker frame, so systolic peaks point up
        }
        waveform.push(y[n - 1].toFloat())

        if (elapsed < 5f) return
        val mean = y.average()
        var energy = 0.0
        for (v in y) energy += (v - mean) * (v - mean)
        if (energy <= 1e-9) return
        // Autocorrelation over 40–200 bpm
        val minLag = (FS * 60f / 200f).toInt()
        val maxLag = minOf((FS * 60f / 40f).toInt(), n - 10)
        val r = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var s = 0.0
            for (i in 0 until n - lag) s += (y[i] - mean) * (y[i + lag] - mean)
            r[lag] = s / energy * n / (n - lag)
        }
        var bestLag = -1
        var bestR = 0.0
        for (lag in minLag..maxLag) if (r[lag] > bestR) {
            bestR = r[lag]; bestLag = lag
        }
        if (bestLag < 0) return
        // Prefer the first strong peak to avoid locking onto a multiple of the period
        for (lag in minLag + 1..maxLag) {
            if (r[lag] >= 0.85 * bestR && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                bestLag = lag; bestR = r[lag]; break
            }
        }
        // Parabolic refinement
        val a = r[bestLag - 1]; val b = r[bestLag]; val c = r[bestLag + 1]
        val denom = a - 2 * b + c
        val refined = bestLag + if (denom < -1e-9) 0.5 * (a - c) / denom else 0.0
        val estimate = (60.0 * FS / refined).toFloat()
        confidence = bestR.toFloat().coerceIn(0f, 1f)
        if (confidence > 0.35f && estimate in 40f..200f) {
            bpm = if (bpm.isNaN()) estimate else bpm + 0.3f * (estimate - bpm)
        }
    }
}

private fun ByteBuffer.sampledMean(step: Int): Float {
    val limit = limit()
    if (limit == 0) return 0f
    var sum = 0L
    var count = 0
    var i = 0
    while (i < limit) {
        sum += get(i).toInt() and 0xFF
        count++
        i += step
    }
    return sum.toFloat() / count
}

@Composable
fun HeartRateScreen(onBack: () -> Unit) {
    KeepScreenOn()
    ToolScaffold(title = "Pulse", subtitle = "Camera + flash photoplethysmography", onBack = onBack) {
        PermissionGate(
            listOf(Manifest.permission.CAMERA),
            "Pulse measurement uses the rear camera and flash to see tiny colour changes in your fingertip. No images are stored.",
        ) {
            HeartContent()
        }
    }
}

@Composable
private fun ColumnScope.HeartContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ic = LocalInstrumentColors.current
    val ppg = remember { PpgProcessor() }
    var running by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(running) {
        if (!running) return@DisposableEffect onDispose { }
        var disposed = false
        var camera: Camera? = null
        var provider: ProcessCameraProvider? = null
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(320, 240),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(mainExecutor) { image: ImageProxy ->
                    val y = image.planes[0].buffer.sampledMean(16)
                    val v = if (image.planes.size > 2) image.planes[2].buffer.sampledMean(8) else 128f
                    ppg.onFrame(image.imageInfo.timestamp / 1e9, y, v)
                    image.close()
                }
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                cam.cameraControl.enableTorch(true)
                camera = cam
                cameraError = null
            } catch (t: Throwable) {
                cameraError = t.message ?: "Camera unavailable"
                running = false
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            runCatching { camera?.cameraControl?.enableTorch(false) }
            runCatching { provider?.unbindAll() }
            ppg.reset()
        }
    }

    val progress = (ppg.elapsed / MEASURE_SEC).coerceIn(0f, 1f)
    val done = progress >= 1f && !ppg.bpm.isNaN()

    BigReadout(
        value = if (ppg.bpm.isNaN()) "—" else ppg.bpm.fmt(0),
        unit = "bpm",
        label = if (done) "Result" else "Heart rate",
        color = if (done) ic.good else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Pill(
        when {
            !running -> "Ready"
            !ppg.fingerDetected -> "Cover the camera and flash with your fingertip"
            done -> "Measurement complete — keep still for a live reading"
            else -> "Measuring… keep still"
        },
        if (running && !ppg.fingerDetected) ic.warn else MaterialTheme.colorScheme.primary,
        Modifier.align(Alignment.CenterHorizontally),
    )
    MeterBar(fraction = progress, color = if (done) ic.good else MaterialTheme.colorScheme.primary)

    if (!running) {
        Button(onClick = { ppg.reset(); running = true }, modifier = Modifier.fillMaxWidth()) { Text("Start (turns on the flash)") }
    } else {
        OutlinedButton(onClick = { running = false }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
    }
    cameraError?.let { Text("Camera error: $it", color = ic.bad) }

    Panel(title = "Pulse waveform") {
        LiveChart(ppg.waveform, listOf(ic.bad), chartHeight = 160.dp, yFormat = { "" })
        StatGrid(
            listOf(
                Stat("Signal quality", "${(ppg.confidence * 100).fmt(0)}", "%", if (ppg.confidence > 0.6f) ic.good else ic.warn),
                Stat("Brightness", ppg.brightness.fmt(0)),
                Stat("Elapsed", ppg.elapsed.fmt(0), "s"),
            ),
        )
    }
    Note("Rest your fingertip lightly over both the main camera and the flash — pressing hard stops the blood flow. The flash gets warm; stop after a minute. This is a demonstration of the camera as a sensor, not a medical device.")
}
