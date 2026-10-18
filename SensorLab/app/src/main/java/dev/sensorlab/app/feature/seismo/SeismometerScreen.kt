package dev.sensorlab.app.feature.seismo

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.RateMeter
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.rememberSensor
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.KeepScreenOn
import dev.sensorlab.app.ui.components.Legend
import dev.sensorlab.app.ui.components.LiveChart
import dev.sensorlab.app.ui.components.MeterBar
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Pill
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

private const val G = SensorManager.STANDARD_GRAVITY

/** Modified Mercalli intensity from peak ground acceleration (Wald et al., 1999 thresholds). */
private fun mercalli(pgaG: Float): Pair<String, String> = when {
    pgaG < 0.0017f -> "I" to "Not felt"
    pgaG < 0.014f -> "II–III" to "Weak"
    pgaG < 0.039f -> "IV" to "Light"
    pgaG < 0.092f -> "V" to "Moderate"
    pgaG < 0.18f -> "VI" to "Strong"
    pgaG < 0.34f -> "VII" to "Very strong"
    pgaG < 0.65f -> "VIII" to "Severe"
    pgaG < 1.24f -> "IX" to "Violent"
    else -> "X+" to "Extreme"
}

@Composable
fun SeismometerScreen(onBack: () -> Unit) {
    KeepScreenOn()
    val ic = LocalInstrumentColors.current
    val haptics = LocalHapticFeedback.current
    val sensor = rememberSensor(Sensor.TYPE_ACCELEROMETER)

    val trace = remember { SeriesBuffer(2, 4000) }
    val meter = remember { RateMeter() }
    val gravity = remember { FloatArray(3) }
    val state = remember { FloatArray(3) } // sta, lta, lastTimestampSeconds
    var initialised by remember { mutableStateOf(false) }

    var current by remember { mutableFloatStateOf(0f) }
    var pga by remember { mutableFloatStateOf(0f) }
    var recentPeak by remember { mutableFloatStateOf(0f) }
    var recentPeakTime by remember { mutableLongStateOf(0L) }
    var ratio by remember { mutableFloatStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }
    var eventCount by remember { mutableIntStateOf(0) }
    var lastEvent by remember { mutableLongStateOf(0L) }
    var sensitivity by remember { mutableFloatStateOf(0.5f) } // 0..1 → chart range
    var threshold by remember { mutableFloatStateOf(3f) }

    SensorEffect(sensor, SensorManager.SENSOR_DELAY_FASTEST) { e ->
        meter.tick(e.timestamp)
        val t = e.timestamp / 1e9f
        if (!initialised) {
            for (i in 0..2) gravity[i] = e.values[i]
            state[0] = 0f; state[1] = 1e-6f; state[2] = t
            initialised = true
            return@SensorEffect
        }
        val dt = (t - state[2]).coerceIn(0.0005f, 0.1f)
        state[2] = t
        // High-pass: subtract a slow (≈2 s) gravity estimate
        val k = dt / 2f
        for (i in 0..2) gravity[i] += k * (e.values[i] - gravity[i])
        val hx = e.values[0] - gravity[0]
        val hy = e.values[1] - gravity[1]
        val hz = e.values[2] - gravity[2]
        val mag = sqrt(hx * hx + hy * hy + hz * hz)
        current = mag
        trace.push(hz, mag)
        if (mag > pga) pga = mag
        val now = System.currentTimeMillis()
        if (mag > recentPeak || now - recentPeakTime > 1500) {
            recentPeak = mag; recentPeakTime = now
        }
        // STA/LTA on energy: 0.5 s short-term vs 10 s long-term window
        val energy = mag * mag
        state[0] += (dt / 0.5f) * (energy - state[0])
        state[1] += (dt / 10f) * (energy - state[1])
        ratio = state[0] / max(state[1], 1e-7f)
        val isEvent = ratio > threshold && state[0] > 1e-4f
        if (isEvent && !triggered && now - lastEvent > 2000) {
            eventCount++
            lastEvent = now
        }
        triggered = isEvent
    }

    LaunchedEffect(eventCount) {
        if (eventCount > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val range = 0.01f * 10f.pow(sensitivity * 3f) // 0.01 … 10 m/s²
    val (mmi, mmiText) = mercalli(recentPeak / G)

    ToolScaffold(
        title = "Seismometer",
        subtitle = sensor?.let { "${it.name} · ${meter.hz.fmt(0)} Hz" },
        onBack = onBack,
    ) {
        if (sensor == null) {
            MissingSensor("accelerometer")
            return@ToolScaffold
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BigReadout(value = mmi, label = "Intensity (MMI)", fontSize = 44.sp, color = if (recentPeak / G > 0.014f) ic.warn else MaterialTheme.colorScheme.onSurface)
            BigReadout(value = (current * 1000f).fmt(0), unit = "mm/s²", label = "Now", fontSize = 32.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Pill(if (triggered) "EVENT DETECTED" else mmiText, if (triggered) ic.bad else MaterialTheme.colorScheme.primary)
        }

        Panel(title = "Seismograph · ~10 s") {
            LiveChart(
                trace,
                listOf(ic.z, ic.magnitude),
                chartHeight = 240.dp,
                fixedMin = -range,
                fixedMax = range,
                yFormat = { v -> "${(v * 1000f).fmt(0)} mm/s²" },
            )
            Legend(listOf("Z (vertical when flat)" to ic.z, "|a| high-passed" to ic.magnitude))
            Text("Chart range ±${(range * 1000f).fmt(0)} mm/s²", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = sensitivity, onValueChange = { sensitivity = it })
        }

        Panel(title = "Event trigger (STA/LTA)") {
            MeterBar(fraction = ratio / (threshold * 2f), color = if (triggered) ic.bad else MaterialTheme.colorScheme.primary)
            StatGrid(
                listOf(
                    Stat("STA/LTA", ratio.fmt(2)),
                    Stat("Threshold", threshold.fmt(1)),
                    Stat("Events", eventCount.toString(), color = if (eventCount > 0) ic.warn else MaterialTheme.colorScheme.onSurface),
                ),
            )
            Text("Trigger threshold", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 1.5f..10f)
            if (lastEvent > 0) {
                Text(
                    "Last event at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastEvent))}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Panel(title = "Peak ground acceleration") {
            StatGrid(
                listOf(
                    Stat("PGA", (pga * 1000f).fmt(0), "mm/s²"),
                    Stat("PGA", (pga / G).fmt(4), "g"),
                    Stat("Peak MMI", mercalli(pga / G).first),
                ),
            )
            OutlinedButton(
                onClick = { pga = 0f; eventCount = 0; lastEvent = 0L; trace.clear() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset") }
            Note("Place the phone flat on a hard floor, away from footsteps. Phones are far less sensitive than real seismometers, but readily pick up footsteps, doors, machinery, traffic and strong earthquakes.")
        }
    }
}
