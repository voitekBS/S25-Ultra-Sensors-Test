package dev.sensorlab.app.feature.pedometer

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.sensorlab.app.core.PeriodicEffect
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.formatDuration
import dev.sensorlab.app.core.grouped
import dev.sensorlab.app.core.rememberPrefFloat
import dev.sensorlab.app.core.rememberPrefLong
import dev.sensorlab.app.core.rememberSensor
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.PermissionGate
import dev.sensorlab.app.ui.components.SectionLabel
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlin.math.min

private val GOALS = listOf(2_000, 5_000, 8_000, 10_000, 15_000)

@Composable
fun PedometerScreen(onBack: () -> Unit) {
    val counter = rememberSensor(Sensor.TYPE_STEP_COUNTER)
    val detector = rememberSensor(Sensor.TYPE_STEP_DETECTOR)
    ToolScaffold(title = "Pedometer", subtitle = (counter ?: detector)?.name, onBack = onBack) {
        if (counter == null && detector == null) {
            MissingSensor("step counter")
            return@ToolScaffold
        }
        PermissionGate(
            listOf(Manifest.permission.ACTIVITY_RECOGNITION),
            "Step counting needs the Physical activity permission.",
        ) {
            PedometerContent(counter, detector)
        }
    }
}

@Composable
private fun ColumnScope.PedometerContent(counter: Sensor?, detector: Sensor?) {
    val ic = LocalInstrumentColors.current
    var baseline by rememberPrefLong("ped_baseline", -1L)
    var sessionStart by rememberPrefLong("ped_start", System.currentTimeMillis())
    var stride by rememberPrefFloat("ped_stride_cm", 75f)
    var goal by rememberPrefLong("ped_goal", 10_000L)

    var total by remember { mutableLongStateOf(-1L) }
    var detectorSteps by remember { mutableLongStateOf(0L) }
    val stepTimes = remember { mutableStateListOf<Long>() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var cadence by remember { mutableFloatStateOf(0f) }

    SensorEffect(counter, SensorManager.SENSOR_DELAY_UI) { e ->
        val v = e.values[0].toLong()
        if (baseline < 0L || v < baseline) {
            baseline = v // first run, or the phone rebooted (counter restarts from zero)
            sessionStart = System.currentTimeMillis()
        }
        total = v
    }
    SensorEffect(detector, SensorManager.SENSOR_DELAY_FASTEST) { _ ->
        detectorSteps++
        stepTimes.add(System.currentTimeMillis())
        if (stepTimes.size > 40) stepTimes.removeAt(0)
    }
    PeriodicEffect(1000L) {
        now = System.currentTimeMillis()
        val recent = stepTimes.filter { now - it < 10_000L }
        cadence = if (recent.size >= 3) (recent.size - 1) * 60_000f / (recent.last() - recent.first()).coerceAtLeast(1L) else 0f
    }

    val steps = if (counter != null && total >= 0 && baseline >= 0) total - baseline else detectorSteps
    val distanceKm = steps * stride / 100_000f
    val elapsed = ((now - sessionStart) / 1000L).coerceAtLeast(0L)
    val progress = (steps.toFloat() / goal).coerceIn(0f, 1f)

    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = if (progress >= 1f) ic.good else MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth(0.78f).aspectRatio(1f).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val stroke = 18.dp.toPx()
            val d = min(size.width, size.height) - stroke
            val topLeft = androidx.compose.ui.geometry.Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arcSize = androidx.compose.ui.geometry.Size(d, d)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(accent, 135f, 270f * progress, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigReadout(value = steps.grouped(), label = "Steps")
            Text("of ${goal.grouped()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    StatGrid(
        listOf(
            Stat("Distance", distanceKm.fmt(2), "km"),
            Stat("Cadence", cadence.fmt(0), "spm"),
            Stat("Session", formatDuration(elapsed)),
            Stat("Since boot", if (total >= 0) total.grouped() else "—"),
            Stat("Detector", detectorSteps.grouped()),
            Stat("Avg pace", if (distanceKm > 0.05f) formatDuration((elapsed / distanceKm).toLong()) else "—", "/km"),
        ),
    )
    OutlinedButton(
        onClick = {
            baseline = if (total >= 0) total else -1L
            sessionStart = System.currentTimeMillis()
            detectorSteps = 0L
            stepTimes.clear()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Start new session") }

    Panel(title = "Settings") {
        SectionLabel("Daily goal")
        ChoiceRow(GOALS, goal.toInt(), { goal = it.toLong() }, label = { "${it / 1000}k" })
        SectionLabel("Stride length · ${stride.fmt(0)} cm")
        Slider(value = stride, onValueChange = { stride = it }, valueRange = 40f..120f)
        Note("Stride is about 0.415 × your height for walking. The counter runs in Samsung's low-power sensor hub, so steps may arrive in small batches.")
    }
}
