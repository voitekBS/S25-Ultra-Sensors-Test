package dev.sensorlab.app.feature.motion

import android.hardware.Sensor
import android.hardware.SensorManager
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.RateMeter
import dev.sensorlab.app.core.RingBuffer
import dev.sensorlab.app.core.SensorCatalog
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.sensorManager
import dev.sensorlab.app.core.signed
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.Legend
import dev.sensorlab.app.ui.components.LiveChart
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.SectionLabel
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val G = SensorManager.STANDARD_GRAVITY

private enum class Rate(val label: String, val value: Int) {
    Ui("UI", SensorManager.SENSOR_DELAY_UI),
    Game("Game", SensorManager.SENSOR_DELAY_GAME),
    Fastest("Fastest", SensorManager.SENSOR_DELAY_FASTEST),
}

private val MOTION_TYPES = listOf(
    Sensor.TYPE_ACCELEROMETER,
    Sensor.TYPE_LINEAR_ACCELERATION,
    Sensor.TYPE_GRAVITY,
    Sensor.TYPE_GYROSCOPE,
    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
)

private fun shortName(type: Int) = when (type) {
    Sensor.TYPE_ACCELEROMETER -> "Accel"
    Sensor.TYPE_LINEAR_ACCELERATION -> "Linear"
    Sensor.TYPE_GRAVITY -> "Gravity"
    Sensor.TYPE_GYROSCOPE -> "Gyro"
    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "Accel raw"
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Gyro raw"
    else -> SensorCatalog.name(type)
}

@Composable
fun MotionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val available = remember { MOTION_TYPES.filter { context.sensorManager.getDefaultSensor(it) != null } }
    if (available.isEmpty()) {
        ToolScaffold(title = "Motion lab", onBack = onBack) { MissingSensor("motion") }
        return
    }
    var type by rememberSaveable { mutableIntStateOf(available.first()) }
    var rate by rememberSaveable { mutableStateOf(Rate.Game) }
    val sensor = remember(type) { context.sensorManager.getDefaultSensor(type) }
    val isGyro = type == Sensor.TYPE_GYROSCOPE || type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED
    val unit = if (isGyro) "rad/s" else "m/s²"

    val chart = remember(type) { SeriesBuffer(4, 400) }
    val trail = remember(type) { SeriesBuffer(2, 90) }
    val meter = remember(type) { RateMeter() }
    var x by remember(type) { mutableFloatStateOf(0f) }
    var y by remember(type) { mutableFloatStateOf(0f) }
    var z by remember(type) { mutableFloatStateOf(0f) }
    var peak by remember(type) { mutableFloatStateOf(0f) }
    var bias by remember(type) { mutableStateOf<FloatArray?>(null) }

    SensorEffect(sensor, rate.value) { e ->
        x = e.values[0]; y = e.values[1]; z = e.values[2]
        val m = sqrt(x * x + y * y + z * z)
        if (m > peak) peak = m
        chart.push(x, y, z, m)
        trail.push(x / G, y / G)
        if (e.values.size >= 6) bias = floatArrayOf(e.values[3], e.values[4], e.values[5])
        meter.tick(e.timestamp)
    }
    val magnitude = sqrt(x * x + y * y + z * z)

    ToolScaffold(
        title = "Motion lab",
        subtitle = sensor?.let { "${it.name} · ${meter.hz.fmt(0)} Hz" },
        onBack = onBack,
    ) {
        ChoiceRow(available, type, { type = it }, label = { shortName(it) })
        ChoiceRow(Rate.entries.toList(), rate, { rate = it }, label = { it.label })

        Panel(title = SensorCatalog.name(type)) {
            LiveChart(chart, listOf(ic.x, ic.y, ic.z, ic.magnitude), chartHeight = 220.dp, symmetric = true, minSpan = if (isGyro) 0.5f else 2f)
            Legend(listOf("X" to ic.x, "Y" to ic.y, "Z" to ic.z, "|v|" to ic.magnitude))
            StatGrid(
                listOf(
                    Stat("X", x.signed(3), unit, ic.x),
                    Stat("Y", y.signed(3), unit, ic.y),
                    Stat("Z", z.signed(3), unit, ic.z),
                    Stat("Magnitude", magnitude.fmt(3), unit, ic.magnitude),
                    Stat("Peak", peak.fmt(2), unit),
                    if (isGyro) Stat("Rate", Math.toDegrees(magnitude.toDouble()).fmt(1), "°/s")
                    else Stat("G-force", (magnitude / G).fmt(3), "g"),
                ),
            )
            bias?.let { b ->
                SectionLabel(if (isGyro) "Estimated drift" else "Estimated bias")
                StatGrid(listOf(Stat("X", b[0].signed(4)), Stat("Y", b[1].signed(4)), Stat("Z", b[2].signed(4))))
            }
            OutlinedButton(onClick = { peak = 0f; chart.clear() }, modifier = Modifier.fillMaxWidth()) { Text("Reset peak") }
        }

        if (!isGyro && type != Sensor.TYPE_GRAVITY) {
            Panel(title = "G-meter (screen plane)") {
                GMeter(trail, modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1f).padding(8.dp).align(Alignment.CenterHorizontally))
                Note(
                    if (type == Sensor.TYPE_LINEAR_ACCELERATION) "Linear acceleration has gravity removed — ideal for measuring braking and cornering forces with the phone mounted flat."
                    else "Raw accelerometer includes gravity: a phone lying flat shows ~1 g on Z and ~0 g here.",
                )
            }
        }

        sensor?.let { s ->
            Panel(title = "Hardware") {
                StatGrid(
                    listOf(
                        Stat("Vendor", s.vendor),
                        Stat("Range", s.maximumRange.fmt(1), unit),
                        Stat("Resolution", s.resolution.fmt(5)),
                        Stat("Max rate", if (s.minDelay > 0) (1_000_000f / s.minDelay).fmt(0) else "—", "Hz"),
                        Stat("Power", s.power.fmt(2), "mA"),
                        Stat("FIFO", s.fifoMaxEventCount.toString()),
                    ),
                    columns = 3,
                )
            }
        }
    }
}

@Composable
private fun GMeter(trail: SeriesBuffer, modifier: Modifier) {
    val ic = LocalInstrumentColors.current
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = ic.tickMinor)
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE")
        val v = trail.version
        val r = min(size.width, size.height) / 2f
        val c = center
        val fullScale = 1.5f // g at the outer ring
        drawCircle(ic.dialFace, r, c)
        for (g in listOf(0.5f, 1.0f, 1.5f)) {
            val rr = r * g / fullScale
            drawCircle(cs.outlineVariant, rr, c, style = Stroke(1.dp.toPx()))
            val t = measurer.measure("${g.fmt(1)} g", label)
            drawText(t, topLeft = Offset(c.x + 4f, c.y - rr))
        }
        drawLine(cs.outlineVariant, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(cs.outlineVariant, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        val xs: RingBuffer = trail.series[0]
        val ys: RingBuffer = trail.series[1]
        val n = xs.size
        for (i in 0 until n) {
            // Screen X = device X, screen Y = -device Y (device Y points to the top of the phone)
            val px = c.x + (xs[i] / fullScale).coerceIn(-1f, 1f) * r
            val py = c.y - (ys[i] / fullScale).coerceIn(-1f, 1f) * r
            val a = (i + 1f) / n
            drawCircle(cs.primary.copy(alpha = 0.08f + 0.5f * a), max(2f, 5f * a), Offset(px, py))
        }
        if (n > 0) {
            val px = c.x + (xs.last() / fullScale).coerceIn(-1f, 1f) * r
            val py = c.y - (ys.last() / fullScale).coerceIn(-1f, 1f) * r
            drawCircle(cs.secondary, 9.dp.toPx(), Offset(px, py))
        }
    }
}
