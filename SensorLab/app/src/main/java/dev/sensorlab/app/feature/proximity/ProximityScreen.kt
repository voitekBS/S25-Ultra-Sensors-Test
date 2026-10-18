package dev.sensorlab.app.feature.proximity

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.rememberSensor
import dev.sensorlab.app.core.sensorManager
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.KeyValue
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

private data class ProxEvent(val time: Long, val distance: Float, val near: Boolean)

@Composable
fun ProximityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val haptics = LocalHapticFeedback.current
    val sensor = rememberSensor(Sensor.TYPE_PROXIMITY)
    val allProximity = remember {
        context.sensorManager.getSensorList(Sensor.TYPE_ALL).filter {
            it.type == Sensor.TYPE_PROXIMITY || it.stringType.contains("proximity", ignoreCase = true)
        }
    }

    var distance by remember { mutableFloatStateOf(Float.NaN) }
    var near by remember { mutableStateOf(false) }
    var nearCount by remember { mutableIntStateOf(0) }
    val events = remember { mutableStateListOf<ProxEvent>() }
    val maxRange = sensor?.maximumRange ?: 5f

    SensorEffect(sensor, SensorManager.SENSOR_DELAY_UI) { e ->
        val d = e.values[0]
        val isNear = d < maxRange
        if (isNear && !near) {
            nearCount++
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        near = isNear
        distance = d
        events.add(0, ProxEvent(System.currentTimeMillis(), d, isNear))
        if (events.size > 12) events.removeAt(events.lastIndex)
    }

    val fill by animateFloatAsState(if (near) 1f else 0f, tween(250), label = "prox")

    ToolScaffold(title = "Proximity", subtitle = sensor?.let { "${it.name} · ${it.vendor}" }, onBack = onBack) {
        if (sensor == null) {
            MissingSensor("proximity")
            return@ToolScaffold
        }
        val nearColor = MaterialTheme.colorScheme.primary
        val ring = MaterialTheme.colorScheme.outlineVariant
        Canvas(Modifier.fillMaxWidth(0.7f).aspectRatio(1f).padding(8.dp).align(Alignment.CenterHorizontally)) {
            val r = min(size.width, size.height) / 2f
            for (k in 1..4) drawCircle(ring, r * k / 4f, center, style = Stroke(1.dp.toPx()))
            drawCircle(nearColor.copy(alpha = 0.18f + 0.5f * fill), r * (0.25f + 0.75f * fill), center)
            drawCircle(nearColor, r * 0.12f, center)
        }
        BigReadout(
            value = if (near) "NEAR" else "FAR",
            label = "State",
            color = if (near) nearColor else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        StatGrid(
            listOf(
                Stat("Distance", distance.fmt(1), "cm"),
                Stat("Max range", maxRange.fmt(1), "cm"),
                Stat("Near events", nearCount.toString(), color = ic.warn),
            ),
        )
        OutlinedButton(onClick = { nearCount = 0; events.clear() }, modifier = Modifier.fillMaxWidth()) { Text("Reset") }

        Panel(title = "Event log") {
            if (events.isEmpty()) {
                Text("Wave a hand over the top of the screen.", style = MaterialTheme.typography.bodyMedium)
            }
            val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
            events.forEach { ev ->
                KeyValue(fmt.format(Date(ev.time)), "${if (ev.near) "NEAR" else "FAR "}  ${ev.distance.fmt(1)} cm", if (ev.near) nearColor else androidx.compose.ui.graphics.Color.Unspecified)
            }
        }

        Panel(title = "Proximity sensors reported") {
            allProximity.forEach { s ->
                KeyValue(s.name, if (s.isWakeUpSensor) "wake-up" else "non-wake")
            }
            Note("Many phones, including the S25 Ultra, report only two values (near / far) rather than a true distance. The sensor sits under the display beside the front camera.")
        }
    }
}
