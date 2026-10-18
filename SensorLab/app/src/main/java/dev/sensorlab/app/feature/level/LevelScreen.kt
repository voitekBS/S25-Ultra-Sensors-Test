package dev.sensorlab.app.feature.level

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.angleDiff
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.normalizeDeg
import dev.sensorlab.app.core.rememberPrefFloat
import dev.sensorlab.app.core.rememberSensor
import dev.sensorlab.app.core.signed
import dev.sensorlab.app.core.toDeg
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.KeepScreenOn
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.tan

private enum class LevelMode(val label: String) { Auto("Auto"), Surface("Surface"), Edge("Edge") }

@Composable
fun LevelScreen(onBack: () -> Unit) {
    KeepScreenOn()
    val ic = LocalInstrumentColors.current
    val haptics = LocalHapticFeedback.current

    val gravitySensor = rememberSensor(Sensor.TYPE_GRAVITY)
    val accelSensor = rememberSensor(Sensor.TYPE_ACCELEROMETER)
    val sensor = gravitySensor ?: accelSensor
    val alpha = if (gravitySensor != null) 0.3f else 0.08f

    var gx by remember { mutableFloatStateOf(0f) }
    var gy by remember { mutableFloatStateOf(0f) }
    var gz by remember { mutableFloatStateOf(9.81f) }
    var started by remember { mutableStateOf(false) }
    var flatAuto by remember { mutableStateOf(true) }
    var hold by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(LevelMode.Auto) }

    var offX by rememberPrefFloat("level_offset_x", 0f)
    var offY by rememberPrefFloat("level_offset_y", 0f)
    var offEdge by rememberPrefFloat("level_offset_edge", 0f)

    SensorEffect(sensor, SensorManager.SENSOR_DELAY_GAME) { e ->
        if (hold) return@SensorEffect
        if (!started) {
            gx = e.values[0]; gy = e.values[1]; gz = e.values[2]; started = true
        } else {
            gx += alpha * (e.values[0] - gx)
            gy += alpha * (e.values[1] - gy)
            gz += alpha * (e.values[2] - gz)
        }
        val n = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.001f)
        val az = abs(gz / n)
        flatAuto = if (flatAuto) az > 0.55f else az > 0.8f
    }

    val n = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.001f)
    val ax = (gx / n).coerceIn(-1f, 1f)
    val ay = (gy / n).coerceIn(-1f, 1f)
    val az = (gz / n).coerceIn(-1f, 1f)

    // Surface (phone lying on its back): tilt of each axis
    val rawX = toDeg(asin(ax))
    val rawY = toDeg(asin(ay))
    val tiltX = rawX - offX
    val tiltY = rawY - offY
    val totalTilt = toDeg(acos(abs(az)))

    // Edge (phone standing on an edge): rotation about the screen normal
    val rawAngle = toDeg(atan2(ax, ay))
    val reference = round(rawAngle / 90f) * 90f
    val rawDev = angleDiff(rawAngle, reference)
    val edgeDev = rawDev - offEdge

    val effective = when (mode) {
        LevelMode.Auto -> if (flatAuto) LevelMode.Surface else LevelMode.Edge
        else -> mode
    }
    val surfaceError = sqrt(tiltX * tiltX + tiltY * tiltY)
    val isLevel = if (effective == LevelMode.Surface) surfaceError < 0.15f else abs(edgeDev) < 0.15f
    LaunchedEffect(isLevel) {
        if (isLevel && started) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    ToolScaffold(
        title = "Spirit level",
        subtitle = sensor?.let { "${it.name} · ${if (gravitySensor != null) "fused gravity" else "filtered accelerometer"}" },
        onBack = onBack,
    ) {
        if (sensor == null) {
            MissingSensor("accelerometer")
            return@ToolScaffold
        }

        ChoiceRow(LevelMode.entries.toList(), mode, { mode = it }, label = { it.label })

        val levelColor = when {
            isLevel -> ic.good
            (if (effective == LevelMode.Surface) surfaceError else abs(edgeDev)) < 1f -> ic.warn
            else -> MaterialTheme.colorScheme.onSurface
        }

        if (effective == LevelMode.Surface) {
            BigReadout(
                value = surfaceError.fmt(1) + "°",
                label = "Surface tilt",
                color = levelColor,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            BubbleLevel(
                x = tiltX,
                y = tiltY,
                isLevel = isLevel,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp),
            )
            StatGrid(
                listOf(
                    Stat("X (left/right)", tiltX.signed(1), "°"),
                    Stat("Y (top/bottom)", tiltY.signed(1), "°"),
                    Stat("Slope", (tan(Math.toRadians(surfaceError.toDouble())) * 100).toFloat().fmt(1), "%"),
                ),
            )
        } else {
            BigReadout(
                value = edgeDev.signed(1) + "°",
                label = "Edge level · ref ${normalizeDeg(reference).fmt(0)}°",
                color = levelColor,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            TubeLevel(
                deviation = edgeDev,
                rotation = reference,
                isLevel = isLevel,
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
            StatGrid(
                listOf(
                    Stat("Inclinometer", normalizeDeg(-rawAngle).fmt(1), "°"),
                    Stat("Slope", (tan(Math.toRadians(edgeDev.toDouble())) * 100).toFloat().fmt(2), "%"),
                    Stat("Rise", (tan(Math.toRadians(edgeDev.toDouble())) * 1000).toFloat().fmt(1), "mm/m"),
                ),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (effective == LevelMode.Surface) {
                        offX = rawX; offY = rawY
                    } else {
                        offEdge = rawDev
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Calibrate") }
            OutlinedButton(
                onClick = { offX = 0f; offY = 0f; offEdge = 0f },
                modifier = Modifier.weight(1f),
            ) { Text("Reset") }
            FilledTonalButton(onClick = { hold = !hold }, modifier = Modifier.weight(1f)) {
                Text(if (hold) "Release" else "Hold")
            }
        }

        Panel(title = "Calibration") {
            StatGrid(
                listOf(
                    Stat("Offset X", offX.signed(2), "°"),
                    Stat("Offset Y", offY.signed(2), "°"),
                    Stat("Offset edge", offEdge.signed(2), "°"),
                ),
            )
            Note("Place the phone on a surface you know is level (or against a true vertical) and tap Calibrate. For best accuracy remove bumpy cases — the S25 Ultra's camera bump lifts one corner when lying flat.")
        }
    }
}

/** Logarithmic radius so tiny tilts are visible while 10° still fits on the dial. */
private fun radiusFor(deg: Float, max: Float): Float = (ln(1f + deg) / ln(11f)).coerceIn(0f, 1f) * max

@Composable
private fun BubbleLevel(x: Float, y: Float, isLevel: Boolean, modifier: Modifier) {
    val ic = LocalInstrumentColors.current
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = ic.tickMinor)
    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) / 2f
        val usable = r * 0.86f
        drawCircle(ic.dialFace, r, c)
        drawCircle(cs.outlineVariant, r, c, style = Stroke(1.5.dp.toPx()))
        for (ring in listOf(0.5f, 1f, 2f, 5f, 10f)) {
            val rr = radiusFor(ring, usable)
            drawCircle(if (ring == 1f) cs.outline else cs.outlineVariant, rr, c, style = Stroke(1.dp.toPx()))
            val t = measurer.measure("${if (ring < 1f) ring.fmt(1) else ring.fmt(0)}°", labelStyle)
            drawText(t, topLeft = Offset(c.x + rr * 0.7071f + 2f, c.y - rr * 0.7071f - t.size.height))
        }
        drawLine(cs.outlineVariant, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(cs.outlineVariant, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)

        val total = sqrt(x * x + y * y)
        val dist = radiusFor(total, usable)
        val dirX = if (total > 1e-4f) x / total else 0f
        val dirY = if (total > 1e-4f) -y / total else 0f
        val pos = Offset(c.x + dirX * dist, c.y + dirY * dist)
        val bubbleR = r * 0.13f
        val col = if (isLevel) ic.good else cs.primary
        drawCircle(col.copy(alpha = 0.22f), bubbleR, pos)
        drawCircle(col, bubbleR, pos, style = Stroke(2.5.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.5f), bubbleR * 0.25f, Offset(pos.x - bubbleR * 0.35f, pos.y - bubbleR * 0.35f))
    }
}

@Composable
private fun TubeLevel(deviation: Float, rotation: Float, isLevel: Boolean, modifier: Modifier) {
    val ic = LocalInstrumentColors.current
    val cs = MaterialTheme.colorScheme
    Canvas(modifier) {
        val c = center
        rotate(rotation, c) {
            val tubeW = min(size.width, size.height * 2.2f) * 0.92f
            val tubeH = tubeW * 0.17f
            val left = c.x - tubeW / 2f
            val top = c.y - tubeH / 2f
            drawRoundRect(ic.dialFace, Offset(left, top), Size(tubeW, tubeH), CornerRadius(tubeH / 2f))
            drawRoundRect(cs.outline, Offset(left, top), Size(tubeW, tubeH), CornerRadius(tubeH / 2f), style = Stroke(1.5.dp.toPx()))
            // graduation marks, 1° apart up to ±10°
            for (d in -10..10) {
                val xx = c.x + (d / 10f) * (tubeW / 2f - tubeH * 0.6f)
                val major = d % 5 == 0
                val hh = if (major) tubeH * 0.32f else tubeH * 0.18f
                drawLine(if (d == 0) cs.secondary else ic.tickMinor, Offset(xx, top), Offset(xx, top + hh), if (major) 2f else 1f)
                drawLine(if (d == 0) cs.secondary else ic.tickMinor, Offset(xx, top + tubeH), Offset(xx, top + tubeH - hh), if (major) 2f else 1f)
            }
            val travel = tubeW / 2f - tubeH * 0.6f
            val bx = c.x + (deviation / 10f).coerceIn(-1f, 1f) * travel
            val bw = tubeH * 1.3f
            val col = if (isLevel) ic.good else cs.primary
            drawRoundRect(col.copy(alpha = 0.25f), Offset(bx - bw / 2f, c.y - tubeH * 0.36f), Size(bw, tubeH * 0.72f), CornerRadius(tubeH * 0.36f))
            drawRoundRect(col, Offset(bx - bw / 2f, c.y - tubeH * 0.36f), Size(bw, tubeH * 0.72f), CornerRadius(tubeH * 0.36f), style = Stroke(2.5.dp.toPx()))
        }
    }
}
