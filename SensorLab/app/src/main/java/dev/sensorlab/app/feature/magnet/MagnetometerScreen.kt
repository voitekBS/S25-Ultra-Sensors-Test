package dev.sensorlab.app.feature.magnet

import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.rememberSensor
import dev.sensorlab.app.core.signed
import dev.sensorlab.app.ui.components.AccuracyPill
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.Legend
import dev.sensorlab.app.ui.components.LiveChart
import dev.sensorlab.app.ui.components.MeterBar
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

@Composable
fun MagnetometerScreen(onBack: () -> Unit) {
    val ic = LocalInstrumentColors.current
    val haptics = LocalHapticFeedback.current
    val sensor = rememberSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val rawSensor = rememberSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)

    val v = remember { FloatArray(3) }
    var fx by remember { mutableFloatStateOf(0f) }
    var fy by remember { mutableFloatStateOf(0f) }
    var fz by remember { mutableFloatStateOf(0f) }
    var accuracy by remember { mutableIntStateOf(-2) }
    var baseline by remember { mutableStateOf<FloatArray?>(null) }
    var hardIron by remember { mutableStateOf<FloatArray?>(null) }
    var peakDelta by remember { mutableFloatStateOf(0f) }
    val chart = remember { SeriesBuffer(2, 300) }

    SensorEffect(sensor, SensorManager.SENSOR_DELAY_GAME, onAccuracy = { accuracy = it }) { e ->
        val first = v[0] == 0f && v[1] == 0f && v[2] == 0f
        for (i in 0..2) v[i] = if (first) e.values[i] else v[i] + 0.35f * (e.values[i] - v[i])
        fx = v[0]; fy = v[1]; fz = v[2]
        accuracy = e.accuracy
        val total = sqrt(fx * fx + fy * fy + fz * fz)
        val b = baseline
        val delta = if (b != null) {
            val dx = fx - b[0]; val dy = fy - b[1]; val dz = fz - b[2]
            sqrt(dx * dx + dy * dy + dz * dz)
        } else 0f
        if (delta > peakDelta) peakDelta = delta
        chart.push(total, delta)
    }
    SensorEffect(rawSensor, SensorManager.SENSOR_DELAY_UI) { e ->
        if (e.values.size >= 6) hardIron = floatArrayOf(e.values[3], e.values[4], e.values[5])
    }

    val total = sqrt(fx * fx + fy * fy + fz * fz)
    val delta = baseline?.let { b ->
        val dx = fx - b[0]; val dy = fy - b[1]; val dz = fz - b[2]
        sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ---- detector audio / haptics
    var detector by remember { mutableStateOf(false) }
    var threshold by remember { mutableFloatStateOf(8f) }
    val currentDelta by rememberUpdatedState(delta ?: 0f)
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(detector, threshold) {
        while (detector && isActive) {
            val d = currentDelta
            if (d > threshold) {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                // stronger signal → faster beeps (60…600 ms)
                val strength = ((d - threshold) / (threshold * 4f)).coerceIn(0f, 1f)
                delay((600 - 540 * strength).toLong())
            } else {
                delay(100)
            }
        }
    }

    ToolScaffold(title = "Magnetometer", subtitle = sensor?.let { "${it.name} · ${it.vendor}" }, onBack = onBack) {
        if (sensor == null) {
            MissingSensor("magnetometer")
            return@ToolScaffold
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            BigReadout(value = total.fmt(1), unit = "µT", label = "Field", fontSize = 44.sp)
            BigReadout(
                value = delta?.fmt(1) ?: "—",
                unit = "µT",
                label = "Δ baseline",
                fontSize = 44.sp,
                color = if ((delta ?: 0f) > threshold) ic.warn else MaterialTheme.colorScheme.primary,
            )
        }
        MeterBar(
            fraction = (delta ?: total) / (if (delta != null) threshold * 6f else 120f),
            color = if ((delta ?: 0f) > threshold) ic.warn else MaterialTheme.colorScheme.primary,
            thickness = 14.dp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { baseline = floatArrayOf(fx, fy, fz); peakDelta = 0f }, modifier = Modifier.weight(1f)) {
                Text(if (baseline == null) "Set baseline" else "Re-zero")
            }
            OutlinedButton(onClick = { baseline = null; peakDelta = 0f }, modifier = Modifier.weight(1f)) { Text("Clear") }
        }

        Panel(title = "Metal detector", trailing = { Switch(checked = detector, onCheckedChange = { detector = it }) }) {
            Text(
                "Beeps and buzzes when the field changes by more than ${threshold.fmt(0)} µT from the baseline. Set the baseline away from metal first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 2f..60f)
            StatGrid(listOf(Stat("Threshold", threshold.fmt(0), "µT"), Stat("Peak Δ", peakDelta.fmt(1), "µT")), columns = 2)
            Note("Finds ferrous metal (steel studs, nails, pipes) and magnets within a few centimetres of the top of the phone. Non-ferrous metals like copper or aluminium barely register.")
        }

        Panel(title = "Field · last ~15 s") {
            LiveChart(chart, listOf(MaterialTheme.colorScheme.primary, ic.warn), fixedMin = 0f, minSpan = 20f)
            Legend(listOf("|B|" to MaterialTheme.colorScheme.primary, "Δ" to ic.warn))
        }

        Panel(title = "Components", trailing = { AccuracyPill(accuracy) }) {
            StatGrid(
                listOf(
                    Stat("X", fx.signed(1), "µT", ic.x),
                    Stat("Y", fy.signed(1), "µT", ic.y),
                    Stat("Z", fz.signed(1), "µT", ic.z),
                ),
            )
            hardIron?.let { h ->
                Text("Hard-iron bias (uncalibrated sensor)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatGrid(listOf(Stat("X", h[0].signed(1), "µT"), Stat("Y", h[1].signed(1), "µT"), Stat("Z", h[2].signed(1), "µT")))
            }
            Note("Earth's field is roughly 25–65 µT depending on where you are (around 49 µT in the UK).")
        }
    }
}
