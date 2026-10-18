package dev.sensorlab.app.feature.compass

import android.Manifest
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.AngleSmoother
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.angleDiff
import dev.sensorlab.app.core.cardinal
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.hasLocationPermission
import dev.sensorlab.app.core.normalizeDeg
import dev.sensorlab.app.core.rememberApproxLocation
import dev.sensorlab.app.core.rememberPrefBool
import dev.sensorlab.app.core.rememberSensor
import dev.sensorlab.app.core.signed
import dev.sensorlab.app.core.toDeg
import dev.sensorlab.app.ui.components.AccuracyPill
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.KeepScreenOn
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Pill
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun CompassScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val haptics = LocalHapticFeedback.current
    KeepScreenOn()

    val rotationSensor = rememberSensor(Sensor.TYPE_ROTATION_VECTOR)
    val accelSensor = rememberSensor(Sensor.TYPE_ACCELEROMETER)
    val magSensor = rememberSensor(Sensor.TYPE_MAGNETIC_FIELD)

    var heading by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var roll by remember { mutableFloatStateOf(0f) }
    var upright by remember { mutableStateOf(false) }
    var magAccuracy by remember { mutableIntStateOf(-2) }
    var field by remember { mutableFloatStateOf(0f) }
    var hasReading by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Float?>(null) }

    val smoother = remember { AngleSmoother(0.15f) }
    val rot = remember { FloatArray(9) }
    val remapped = remember { FloatArray(9) }
    val orientation = remember { FloatArray(3) }
    val gravity = remember { FloatArray(3) }
    val geomagnetic = remember { FloatArray(3) }
    val ready = remember { BooleanArray(2) }

    fun applyRotation(m: FloatArray) {
        // m[8] = cosine of the angle between the screen normal and "up".
        val isUpright = if (upright) abs(m[8]) < 0.6f else abs(m[8]) < 0.45f
        upright = isUpright
        val use = if (isUpright) {
            // Phone held upright: report where the back camera points.
            SensorManager.remapCoordinateSystem(m, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            remapped
        } else m
        SensorManager.getOrientation(use, orientation)
        heading = smoother.add(normalizeDeg(toDeg(orientation[0])))
        SensorManager.getOrientation(m, orientation)
        pitch = toDeg(orientation[1])
        roll = toDeg(orientation[2])
        hasReading = true
    }

    SensorEffect(rotationSensor, SensorManager.SENSOR_DELAY_GAME) { e ->
        val v = if (e.values.size > 4) e.values.copyOf(4) else e.values
        SensorManager.getRotationMatrixFromVector(rot, v)
        applyRotation(rot)
    }
    SensorEffect(magSensor, SensorManager.SENSOR_DELAY_GAME, onAccuracy = { magAccuracy = it }) { e ->
        for (i in 0..2) geomagnetic[i] = if (ready[1]) geomagnetic[i] + 0.2f * (e.values[i] - geomagnetic[i]) else e.values[i]
        ready[1] = true
        field = sqrt(geomagnetic[0] * geomagnetic[0] + geomagnetic[1] * geomagnetic[1] + geomagnetic[2] * geomagnetic[2])
        magAccuracy = e.accuracy
        if (rotationSensor == null && ready[0]) {
            if (SensorManager.getRotationMatrix(rot, null, gravity, geomagnetic)) applyRotation(rot)
        }
    }
    SensorEffect(if (rotationSensor == null) accelSensor else null, SensorManager.SENSOR_DELAY_GAME) { e ->
        for (i in 0..2) gravity[i] = if (ready[0]) gravity[i] + 0.15f * (e.values[i] - gravity[i]) else e.values[i]
        ready[0] = true
    }

    // ---- true north
    var trueNorth by rememberPrefBool("compass_true_north", false)
    var locationAllowed by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationAllowed = context.hasLocationPermission()
        if (!locationAllowed) trueNorth = false
    }
    val location = rememberApproxLocation(trueNorth && locationAllowed)
    val declination = remember(location) {
        location?.let {
            GeomagneticField(
                it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis(),
            ).declination
        }
    }
    val usingTrue = trueNorth && declination != null
    val shown = normalizeDeg(heading + if (usingTrue) declination ?: 0f else 0f)

    // ---- haptic tick at each cardinal point
    val sector = ((shown + 45f) / 90f).toInt() % 4
    var lastSector by remember { mutableIntStateOf(-1) }
    LaunchedEffect(sector) {
        if (lastSector != -1 && lastSector != sector) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastSector = sector
    }

    ToolScaffold(
        title = "Compass",
        subtitle = if (rotationSensor != null) "Fused rotation vector · ${magSensor?.name ?: "magnetometer"}" else "Accelerometer + magnetometer",
        onBack = onBack,
    ) {
        if (magSensor == null) {
            MissingSensor("magnetometer")
            return@ToolScaffold
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            BigReadout(value = if (hasReading) "${shown.fmt(0)}°" else "—", fontSize = 64.sp)
            Spacer(Modifier.width(14.dp))
            androidx.compose.foundation.layout.Column {
                Text(cardinal(shown), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Pill(if (usingTrue) "TRUE" else "MAGNETIC", if (usingTrue) MaterialTheme.colorScheme.primary else ic.warn)
            }
        }

        CompassDial(
            heading = shown,
            target = target,
            bubbleX = if (upright) null else roll,
            bubbleY = if (upright) null else pitch,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp),
        )

        target?.let { t ->
            val delta = angleDiff(t, shown)
            Text(
                "Target ${t.fmt(0)}° · ${if (abs(delta) < 2f) "on course" else "turn ${if (delta > 0) "right" else "left"} ${abs(delta).fmt(0)}°"}",
                style = MaterialTheme.typography.titleMedium,
                color = if (abs(delta) < 2f) ic.good else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (target == null) {
                Button(onClick = { target = shown }, modifier = Modifier.weight(1f)) { Text("Lock bearing") }
            } else {
                OutlinedButton(onClick = { target = null }, modifier = Modifier.weight(1f)) { Text("Clear bearing") }
            }
        }

        Panel(
            title = "True north",
            trailing = {
                Switch(checked = trueNorth, onCheckedChange = { on ->
                    trueNorth = on
                    if (on && !locationAllowed) {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    }
                })
            },
        ) {
            Text(
                when {
                    !trueNorth -> "Showing magnetic north. Turn on to correct for local magnetic declination (uses your approximate location)."
                    declination == null -> "Waiting for a location fix to compute declination…"
                    else -> "Declination here is ${declination.signed(1)}° (World Magnetic Model)."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel(title = "Sensor", trailing = { AccuracyPill(magAccuracy) }) {
            val fieldOk = field in 22f..68f
            StatGrid(
                listOf(
                    Stat("Field", field.fmt(1), "µT", if (fieldOk) ic.good else ic.bad),
                    Stat("Pitch", pitch.fmt(1), "°"),
                    Stat("Roll", roll.fmt(1), "°"),
                    Stat("Mode", if (upright) "Upright" else "Flat"),
                    Stat("Declination", declination?.signed(1) ?: "—", "°"),
                    Stat("Interference", if (fieldOk) "None" else "Likely", color = if (fieldOk) ic.good else ic.bad),
                ),
            )
            if (magAccuracy in 0..1) {
                Note("Low magnetometer accuracy — wave the phone in a figure-of-eight a few times to recalibrate.")
            }
            Note("Earth's field is 25–65 µT. Readings outside that range mean a magnet, metal or electronics nearby (including magnetic cases).")
        }
    }
}

@Composable
private fun CompassDial(
    heading: Float,
    target: Float?,
    bubbleX: Float?,
    bubbleY: Float?,
    modifier: Modifier,
) {
    val ic = LocalInstrumentColors.current
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val cardinalStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ic.tick)
    val northStyle = cardinalStyle.copy(color = ic.bad)
    val numberStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = ic.tickMinor)

    Canvas(modifier) {
        val radius = min(size.width, size.height) / 2f
        val c = center
        drawCircle(ic.dialFace, radius, c)
        drawCircle(cs.outlineVariant, radius, c, style = Stroke(1.5.dp.toPx()))

        rotate(-heading, c) {
            for (d in 0 until 360 step 2) {
                val major = d % 30 == 0
                val mid = d % 10 == 0
                val len = when {
                    major -> radius * 0.10f
                    mid -> radius * 0.065f
                    else -> radius * 0.035f
                }
                val a = Math.toRadians(d.toDouble())
                val sx = sin(a).toFloat()
                val sy = -cos(a).toFloat()
                val r0 = radius * 0.95f
                val r1 = r0 - len
                drawLine(
                    color = if (major || mid) ic.tick else ic.tickMinor,
                    start = Offset(c.x + sx * r0, c.y + sy * r0),
                    end = Offset(c.x + sx * r1, c.y + sy * r1),
                    strokeWidth = if (major) 2.4.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            for (d in 0 until 360 step 30) {
                val label = when (d) {
                    0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> d.toString()
                }
                val style = when (d) {
                    0 -> northStyle
                    90, 180, 270 -> cardinalStyle
                    else -> numberStyle
                }
                val layout = measurer.measure(label, style)
                rotate(d.toFloat(), c) {
                    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - radius * 0.80f))
                }
            }
            if (target != null) {
                rotate(target, c) {
                    val tip = c.y - radius * 0.97f
                    val p = Path().apply {
                        moveTo(c.x, tip + radius * 0.09f)
                        lineTo(c.x - radius * 0.045f, tip)
                        lineTo(c.x + radius * 0.045f, tip)
                        close()
                    }
                    drawPath(p, cs.secondary)
                }
            }
        }

        // Fixed lubber line (direction of travel)
        drawLine(cs.secondary, Offset(c.x, c.y - radius * 1.0f), Offset(c.x, c.y - radius * 0.68f), 3.dp.toPx(), StrokeCap.Round)

        // Centre reticle with a small tilt bubble while the phone is flat
        val inner = radius * 0.32f
        drawCircle(cs.outlineVariant, inner, c, style = Stroke(1.dp.toPx()))
        drawLine(cs.outlineVariant, Offset(c.x - inner, c.y), Offset(c.x + inner, c.y), 1f)
        drawLine(cs.outlineVariant, Offset(c.x, c.y - inner), Offset(c.x, c.y + inner), 1f)
        if (bubbleX != null && bubbleY != null) {
            val maxDeg = 20f
            var bx = bubbleX / maxDeg
            var by = bubbleY / maxDeg
            val len = sqrt(bx * bx + by * by)
            if (len > 1f) {
                bx /= len; by /= len
            }
            val level = abs(bubbleX) < 1f && abs(bubbleY) < 1f
            val pos = Offset(c.x + bx * (inner - radius * 0.06f), c.y + by * (inner - radius * 0.06f))
            drawCircle((if (level) ic.good else cs.primary).copy(alpha = 0.3f), radius * 0.06f, pos)
            drawCircle(if (level) ic.good else cs.primary, radius * 0.06f, pos, style = Stroke(2.dp.toPx()))
        }
    }
}
