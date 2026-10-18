package dev.sensorlab.app.feature.attitude

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.normalizeDeg
import dev.sensorlab.app.core.sensorManager
import dev.sensorlab.app.core.signed
import dev.sensorlab.app.core.toDeg
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.MonoSmall
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val ROTATION_TYPES = listOf(
    Sensor.TYPE_ROTATION_VECTOR to "Rotation",
    Sensor.TYPE_GAME_ROTATION_VECTOR to "Game",
    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR to "Geomagnetic",
)

// Galaxy S25 Ultra: 162.8 × 77.6 × 8.2 mm, normalised so the long side is ~1.6 units.
private const val HX = 0.388f
private const val HY = 0.814f
private const val HZ = 0.041f

@Composable
fun AttitudeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val available = remember { ROTATION_TYPES.filter { context.sensorManager.getDefaultSensor(it.first) != null } }
    if (available.isEmpty()) {
        ToolScaffold(title = "3D attitude", onBack = onBack) { MissingSensor("rotation vector") }
        return
    }
    var type by rememberSaveable { mutableIntStateOf(available.first().first) }
    val sensor = remember(type) { context.sensorManager.getDefaultSensor(type) }

    val matrix = remember { FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f } }
    val quaternion = remember { FloatArray(4) }
    val orientation = remember { FloatArray(3) }
    var tick by remember { mutableLongStateOf(0L) }
    var headingAccuracy by remember { mutableFloatStateOf(Float.NaN) }
    var viewYaw by remember { mutableFloatStateOf(-25f) }
    var viewPitch by remember { mutableFloatStateOf(28f) }
    var accuracy by remember { mutableStateOf(-2) }

    SensorEffect(sensor, SensorManager.SENSOR_DELAY_GAME, onAccuracy = { accuracy = it }) { e ->
        val v = if (e.values.size > 4) e.values.copyOf(4) else e.values
        SensorManager.getRotationMatrixFromVector(matrix, v)
        SensorManager.getQuaternionFromVector(quaternion, v)
        SensorManager.getOrientation(matrix, orientation)
        headingAccuracy = if (type == Sensor.TYPE_ROTATION_VECTOR && e.values.size > 4 && e.values[4] >= 0f) toDeg(e.values[4]) else Float.NaN
        tick++
    }

    ToolScaffold(title = "3D attitude", subtitle = sensor?.name, onBack = onBack) {
        ChoiceRow(available, available.first { it.first == type }, { type = it.first }, label = { it.second })

        Panel(title = "Live model · drag to orbit") {
            PhoneModel(
                matrix = matrix,
                tick = tick,
                viewYaw = viewYaw,
                viewPitch = viewPitch,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            viewYaw += drag.x * 0.4f
                            viewPitch = (viewPitch + drag.y * 0.3f).coerceIn(-10f, 85f)
                        }
                    },
            )
        }

        @Suppress("UNUSED_VARIABLE")
        val t = tick
        val azimuth = normalizeDeg(toDeg(orientation[0]))
        StatGrid(
            listOf(
                Stat("Azimuth", azimuth.fmt(1), "°", ic.magnitude),
                Stat("Pitch", toDeg(orientation[1]).signed(1), "°", ic.x),
                Stat("Roll", toDeg(orientation[2]).signed(1), "°", ic.y),
            ),
        )

        Panel(title = "Quaternion (w, x, y, z)") {
            StatGrid(
                listOf(
                    Stat("w", quaternion[0].signed(4)),
                    Stat("x", quaternion[1].signed(4)),
                    Stat("y", quaternion[2].signed(4)),
                    Stat("z", quaternion[3].signed(4)),
                ),
                columns = 4,
            )
            if (!headingAccuracy.isNaN()) {
                Text("Estimated heading accuracy ±${headingAccuracy.fmt(1)}°", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Panel(title = "Rotation matrix (device → world)") {
            for (r in 0..2) {
                Text(
                    (0..2).joinToString("   ") { c -> matrix[r * 3 + c].signed(4).padStart(8) },
                    style = MonoSmall.copy(fontSize = 14.sp),
                )
            }
            Note(
                when (type) {
                    Sensor.TYPE_ROTATION_VECTOR -> "Fuses accelerometer, gyroscope and magnetometer. Azimuth is relative to magnetic north."
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> "Accelerometer + gyroscope only: smooth and immune to magnetic interference, but the azimuth drifts and starts from an arbitrary direction."
                    else -> "Accelerometer + magnetometer only: low power, no gyroscope, so it reacts more slowly and is noisier."
                },
            )
        }
    }
}

private class Projector(val yawDeg: Float, val pitchDeg: Float, val cx: Float, val cy: Float, val scale: Float) {
    private val cyaw = cos(Math.toRadians(yawDeg.toDouble())).toFloat()
    private val syaw = sin(Math.toRadians(yawDeg.toDouble())).toFloat()
    private val cp = cos(Math.toRadians(pitchDeg.toDouble())).toFloat()
    private val sp = sin(Math.toRadians(pitchDeg.toDouble())).toFloat()

    /** World (east, north, up) → screen position + depth (bigger = farther). */
    fun project(e: Float, n: Float, u: Float): Triple<Float, Float, Float> {
        val e2 = e * cyaw - n * syaw
        val n2 = e * syaw + n * cyaw
        val sx = e2
        val sy = n2 * sp + u * cp
        val depth = n2 * cp - u * sp
        val f = 4f / (4f + depth)
        return Triple(cx + sx * scale * f, cy - sy * scale * f, depth)
    }
}

@Composable
private fun PhoneModel(matrix: FloatArray, tick: Long, viewYaw: Float, viewPitch: Float, modifier: Modifier) {
    val ic = LocalInstrumentColors.current
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    val screenColor = Color(0xFF0E1A24)
    val bodyColor = Color(0xFF59636E)
    val backColor = Color(0xFF3A424B)

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE")
        val t = tick
        val proj = Projector(viewYaw, viewPitch, center.x, center.y, min(size.width, size.height) * 0.36f)

        fun toWorld(x: Float, y: Float, z: Float): Triple<Float, Float, Float> = Triple(
            matrix[0] * x + matrix[1] * y + matrix[2] * z,
            matrix[3] * x + matrix[4] * y + matrix[5] * z,
            matrix[6] * x + matrix[7] * y + matrix[8] * z,
        )

        // Ground grid (world horizontal plane, slightly below the phone)
        val gridColor = cs.outlineVariant
        for (k in -4..4) {
            val a = proj.project(k * 0.3f, -1.2f, -0.9f)
            val b = proj.project(k * 0.3f, 1.2f, -0.9f)
            drawLine(gridColor, Offset(a.first, a.second), Offset(b.first, b.second), 1f)
            val c = proj.project(-1.2f, k * 0.3f, -0.9f)
            val d = proj.project(1.2f, k * 0.3f, -0.9f)
            drawLine(gridColor, Offset(c.first, c.second), Offset(d.first, d.second), 1f)
        }

        // World axes
        val origin = proj.project(0f, 0f, -0.9f)
        fun axis(e: Float, n: Float, u: Float, color: Color, label: String) {
            val p = proj.project(e, n, u - 0.9f)
            drawLine(color, Offset(origin.first, origin.second), Offset(p.first, p.second), 2.dp.toPx(), StrokeCap.Round)
            val layout = measurer.measure(label, axisStyle.copy(color = color))
            drawText(layout, topLeft = Offset(p.first - layout.size.width / 2f, p.second - layout.size.height - 2f))
        }
        axis(1.25f, 0f, 0f, ic.x, "E")
        axis(0f, 1.25f, 0f, ic.y, "N")
        axis(0f, 0f, 1.9f, ic.z, "UP")

        // Phone box: 8 corners in device coordinates
        val corners = listOf(
            floatArrayOf(-HX, -HY, -HZ), floatArrayOf(HX, -HY, -HZ), floatArrayOf(HX, HY, -HZ), floatArrayOf(-HX, HY, -HZ),
            floatArrayOf(-HX, -HY, HZ), floatArrayOf(HX, -HY, HZ), floatArrayOf(HX, HY, HZ), floatArrayOf(-HX, HY, HZ),
        ).map { c -> toWorld(c[0], c[1], c[2]).let { w -> proj.project(w.first, w.second, w.third) } }

        data class Face(val idx: IntArray, val color: Color, val isScreen: Boolean)
        val faces = listOf(
            Face(intArrayOf(4, 5, 6, 7), screenColor, true),   // front (screen, +Z)
            Face(intArrayOf(0, 3, 2, 1), backColor, false),    // back
            Face(intArrayOf(0, 1, 5, 4), bodyColor, false),    // bottom edge
            Face(intArrayOf(3, 7, 6, 2), bodyColor, false),    // top edge
            Face(intArrayOf(0, 4, 7, 3), bodyColor, false),    // left
            Face(intArrayOf(1, 2, 6, 5), bodyColor, false),    // right
        ).sortedByDescending { f -> f.idx.sumOf { corners[it].third.toDouble() } }

        for (f in faces) {
            val path = Path().apply {
                moveTo(corners[f.idx[0]].first, corners[f.idx[0]].second)
                for (i in 1..3) lineTo(corners[f.idx[i]].first, corners[f.idx[i]].second)
                close()
            }
            drawPath(path, f.color)
            drawPath(path, cs.outline, style = Stroke(1.dp.toPx()))
            if (f.isScreen) {
                // "Top" marker on the screen so orientation is unambiguous
                val top = toWorld(0f, HY * 0.8f, HZ + 0.001f).let { w -> proj.project(w.first, w.second, w.third) }
                val bottom = toWorld(0f, HY * 0.35f, HZ + 0.001f).let { w -> proj.project(w.first, w.second, w.third) }
                drawLine(cs.primary, Offset(bottom.first, bottom.second), Offset(top.first, top.second), 3.dp.toPx(), StrokeCap.Round)
                val cam = toWorld(0f, HY * 0.9f, HZ + 0.001f).let { w -> proj.project(w.first, w.second, w.third) }
                drawCircle(cs.primary, 3.dp.toPx(), Offset(cam.first, cam.second))
            }
        }
        // Rear camera cluster (top-left on the back)
        drawCameraBump(corners, toWorld = ::toWorld, proj = proj, color = Color(0xFF1C2126))
    }
}

private fun DrawScope.drawCameraBump(
    corners: List<Triple<Float, Float, Float>>,
    toWorld: (Float, Float, Float) -> Triple<Float, Float, Float>,
    proj: Projector,
    color: Color,
) {
    // Only visible when the back faces the viewer: compare depth of back vs front centre.
    val front = corners.subList(4, 8).sumOf { it.third.toDouble() }
    val back = corners.subList(0, 4).sumOf { it.third.toDouble() }
    if (back > front) return
    // Seen from behind, "top-left" is the device's +X side.
    val lenses = listOf(0.22f to 0.66f, 0.22f to 0.46f, 0.22f to 0.26f, 0.04f to 0.58f)
    for ((lx, ly) in lenses) {
        val w = toWorld(lx, ly, -HZ - 0.01f)
        val p = proj.project(w.first, w.second, w.third)
        drawCircle(color, 7.dp.toPx(), Offset(p.first, p.second))
        drawCircle(Color(0xFF9AA5B1), 7.dp.toPx(), Offset(p.first, p.second), style = Stroke(1.dp.toPx()))
    }
}
