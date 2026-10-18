package dev.sensorlab.app.feature.sound

import android.Manifest
import android.media.AudioManager
import android.media.MicrophoneInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.rememberPrefFloat
import dev.sensorlab.app.core.signed
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.KeepScreenOn
import dev.sensorlab.app.ui.components.KeyValue
import dev.sensorlab.app.ui.components.LiveChart
import dev.sensorlab.app.ui.components.MeterBar
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.PermissionGate
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

private enum class Weighting(val label: String) { A("dB(A)"), Z("dB(Z) flat") }

private val NOTE_NAMES = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

private val REFERENCE = listOf(
    30 to "Whisper, quiet bedroom",
    40 to "Library",
    60 to "Normal conversation",
    70 to "Vacuum cleaner, busy office",
    85 to "Heavy traffic — hearing risk after 8 h",
    100 to "Motorbike, nightclub — risk after 15 min",
    120 to "Thunderclap — threshold of pain",
)

/** Running fast-weighted level, min/max, Leq and peak, all in dBFS. */
private class LevelStats {
    var level by mutableFloatStateOf(Float.NaN)
    var min by mutableFloatStateOf(Float.NaN)
    var max by mutableFloatStateOf(Float.NaN)
    var leq by mutableFloatStateOf(Float.NaN)
    var peak by mutableFloatStateOf(Float.NaN)
    private var meanSquare = Double.NaN
    private var energy = 0.0
    private var seconds = 0.0

    fun add(levelDbfs: Float, peakDbfs: Float, dt: Float) {
        val e = 10.0.pow(levelDbfs / 10.0)
        val a = 1.0 - exp(-dt / 0.125) // IEC "Fast" time weighting
        meanSquare = if (meanSquare.isNaN()) e else meanSquare + a * (e - meanSquare)
        val l = (10.0 * log10(meanSquare)).toFloat()
        level = l
        min = if (min.isNaN()) l else kotlin.math.min(min, l)
        max = if (max.isNaN()) l else kotlin.math.max(max, l)
        peak = if (peak.isNaN()) peakDbfs else kotlin.math.max(peak, peakDbfs)
        energy += e * dt
        seconds += dt
        leq = (10.0 * log10(energy / seconds)).toFloat()
    }

    fun reset() {
        level = Float.NaN; min = Float.NaN; max = Float.NaN; leq = Float.NaN; peak = Float.NaN
        meanSquare = Double.NaN; energy = 0.0; seconds = 0.0
    }
}

@Composable
fun SoundScreen(onBack: () -> Unit) {
    KeepScreenOn()
    ToolScaffold(title = "Sound meter", subtitle = "Microphone level & spectrum", onBack = onBack) {
        PermissionGate(
            listOf(Manifest.permission.RECORD_AUDIO),
            "The sound meter analyses the microphone signal on the phone. Nothing is recorded or saved.",
        ) {
            SoundContent()
        }
    }
}

@Composable
private fun ColumnScope.SoundContent() {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val engine = remember { SoundEngine(context.applicationContext) }
    DisposableEffect(engine) { onDispose { engine.release() } }
    LifecycleResumeEffect(engine) {
        engine.start()
        onPauseOrDispose { engine.stop() }
    }

    val frame by engine.frames.collectAsStateWithLifecycle()
    val error by engine.error.collectAsStateWithLifecycle()
    var offset by rememberPrefFloat("sound_offset_${if (engine.unprocessed) "raw" else "vr"}", engine.defaultOffset)
    var weighting by rememberSaveable { mutableStateOf(Weighting.A) }
    val stats = remember { LevelStats() }
    val history = remember { SeriesBuffer(1, 700) }
    val currentWeighting by rememberUpdatedState(weighting)
    val currentOffset by rememberUpdatedState(offset)

    LaunchedEffect(engine) {
        engine.frames.filterNotNull().collect { f ->
            val l = if (currentWeighting == Weighting.A) f.aWeightedDbfs else f.rmsDbfs
            stats.add(l, f.peakDbfs, f.durationSec)
            history.push(stats.level + currentOffset)
        }
    }
    LaunchedEffect(weighting) {
        stats.reset(); history.clear()
    }

    error?.let { msg ->
        Panel(title = "Microphone") { Text(msg, color = ic.bad) }
    }

    val spl = stats.level + offset
    val levelColor = when {
        spl.isNaN() -> MaterialTheme.colorScheme.onSurface
        spl < 55f -> ic.good
        spl < 70f -> MaterialTheme.colorScheme.primary
        spl < 85f -> ic.warn
        else -> ic.bad
    }

    ChoiceRow(Weighting.entries.toList(), weighting, { weighting = it }, label = { it.label })
    BigReadout(
        value = if (spl.isNaN()) "—" else spl.fmt(1),
        unit = if (weighting == Weighting.A) "dB(A)" else "dB",
        label = "Sound level · fast",
        color = levelColor,
        fontSize = 72.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    MeterBar(fraction = (spl - 20f) / 100f, color = levelColor, thickness = 14.dp)
    StatGrid(
        listOf(
            Stat("Min", (stats.min + offset).fmt(1), "dB"),
            Stat("Leq", (stats.leq + offset).fmt(1), "dB"),
            Stat("Max", (stats.max + offset).fmt(1), "dB"),
            Stat("Peak", (stats.peak + offset).fmt(1), "dB"),
            Stat("dBFS", stats.level.fmt(1)),
            Stat("Source", if (engine.unprocessed) "Unprocessed" else "Voice rec."),
        ),
    )
    OutlinedButton(onClick = { stats.reset(); history.clear() }, modifier = Modifier.fillMaxWidth()) { Text("Reset") }

    Panel(title = "Level · last minute") {
        LiveChart(history, listOf(levelColor), fixedMin = 20f, fixedMax = 120f, referenceLines = listOf(85f), yFormat = { "${it.fmt(0)} dB" })
    }

    Panel(title = "Spectrum") {
        val f = frame
        SpectrumView(f?.spectrum, Modifier.fillMaxWidth().height(200.dp))
        val dominant = f?.dominantHz ?: 0f
        if (dominant > 0f) {
            val midi = 69f + 12f * log2(dominant / 440f)
            val nearest = midi.roundToInt()
            val cents = (midi - nearest) * 100f
            val name = NOTE_NAMES[((nearest % 12) + 12) % 12] + (nearest / 12 - 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigReadout(value = dominant.fmt(if (dominant < 1000f) 1 else 0), unit = "Hz", label = "Dominant", fontSize = 30.sp)
                BigReadout(value = name, label = "Note", fontSize = 30.sp)
                BigReadout(
                    value = cents.signed(0),
                    unit = "¢",
                    label = "Tuning",
                    fontSize = 30.sp,
                    color = if (kotlin.math.abs(cents) < 5f) ic.good else ic.warn,
                )
            }
        } else {
            Text("No clear tone detected.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Panel(title = "Calibration") {
        Text("Offset ${offset.fmt(1)} dB (dBFS → dB SPL)", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { offset -= 1f }, modifier = Modifier.weight(1f)) { Text("−1") }
            FilledTonalButton(onClick = { offset -= 0.1f }, modifier = Modifier.weight(1f)) { Text("−0.1") }
            FilledTonalButton(onClick = { offset += 0.1f }, modifier = Modifier.weight(1f)) { Text("+0.1") }
            FilledTonalButton(onClick = { offset += 1f }, modifier = Modifier.weight(1f)) { Text("+1") }
        }
        OutlinedButton(onClick = { offset = engine.defaultOffset }, modifier = Modifier.fillMaxWidth()) {
            Text("Reset to default (${engine.defaultOffset.fmt(1)} dB)")
        }
        Note("The default follows Android's microphone sensitivity spec, which is usually within a few dB. For accurate readings, compare against a calibrated meter or a 94 dB calibrator and adjust the offset.")
    }

    Panel(title = "Reference levels") {
        REFERENCE.forEach { (db, what) -> KeyValue(what, "$db dB") }
    }

    val mics = remember {
        runCatching { context.getSystemService(AudioManager::class.java)?.microphones.orEmpty() }.getOrDefault(emptyList())
    }
    if (mics.isNotEmpty()) {
        Panel(title = "Microphones (${mics.size})") {
            mics.forEach { m ->
                val location = when (m.location) {
                    MicrophoneInfo.LOCATION_MAINBODY -> "main body"
                    MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "movable"
                    MicrophoneInfo.LOCATION_PERIPHERAL -> "peripheral"
                    else -> "unknown"
                }
                val pos = m.position
                val sens = if (m.sensitivity != MicrophoneInfo.SENSITIVITY_UNKNOWN) " · ${m.sensitivity.fmt(1)} dBFS" else ""
                KeyValue(
                    m.description.ifBlank { "Mic ${m.id}" },
                    "$location$sens\n(${pos.x.fmt(3)}, ${pos.y.fmt(3)}) m",
                )
            }
        }
    }
}

@Composable
private fun SpectrumView(spectrum: FloatArray?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val ic = LocalInstrumentColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = ic.tickMinor, fontWeight = FontWeight.Medium)
    val fill = Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.55f), cs.primary.copy(alpha = 0.05f)))
    Canvas(modifier) {
        val w = size.width
        val h = size.height - 14.dp.toPx()
        val minDb = -110f
        val maxDb = -10f
        fun xOf(hz: Float) = (ln(hz / SPECTRUM_MIN_HZ) / ln(SPECTRUM_MAX_HZ / SPECTRUM_MIN_HZ)) * w
        for (db in listOf(-100f, -80f, -60f, -40f, -20f)) {
            val y = h * (1f - (db - minDb) / (maxDb - minDb))
            drawLine(cs.outlineVariant, Offset(0f, y), Offset(w, y), 1f)
        }
        for ((hz, label) in listOf(50f to "50", 100f to "100", 200f to "200", 500f to "500", 1000f to "1k", 2000f to "2k", 5000f to "5k", 10000f to "10k")) {
            val x = xOf(hz)
            drawLine(cs.outlineVariant, Offset(x, 0f), Offset(x, h), 1f)
            val t = measurer.measure(label, labelStyle)
            drawText(t, topLeft = Offset(x - t.size.width / 2f, h + 2f))
        }
        if (spectrum == null) return@Canvas
        val path = Path()
        val n = spectrum.size
        path.moveTo(0f, h)
        for (b in 0 until n) {
            val x = b / (n - 1f) * w
            val v = ((spectrum[b] - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            path.lineTo(x, h * (1f - v))
        }
        path.lineTo(w, h)
        path.close()
        drawPath(path, fill)
        val line = Path()
        for (b in 0 until n) {
            val x = b / (n - 1f) * w
            val v = ((spectrum[b] - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            if (b == 0) line.moveTo(x, h * (1f - v)) else line.lineTo(x, h * (1f - v))
        }
        drawPath(line, cs.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}
