package dev.sensorlab.app.feature.light

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.rememberSensor
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.LiveChart
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.SectionLabel
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

private val ISOS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
private val APERTURES = listOf(1.4f, 1.8f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)
private val SHUTTERS = listOf(
    1f / 8000, 1f / 4000, 1f / 2000, 1f / 1000, 1f / 500, 1f / 250, 1f / 125, 1f / 60,
    1f / 30, 1f / 15, 1f / 8, 1f / 4, 1f / 2, 1f, 2f, 4f, 8f, 15f, 30f,
)

private data class LightBand(val name: String, val from: Float, val to: Float)

private val BANDS = listOf(
    LightBand("Dark / moonlight", 0f, 1f),
    LightBand("Dim indoor", 1f, 50f),
    LightBand("Living room", 50f, 200f),
    LightBand("Office / classroom", 200f, 1000f),
    LightBand("Overcast daylight", 1000f, 10_000f),
    LightBand("Full daylight", 10_000f, 32_000f),
    LightBand("Direct sunlight", 32_000f, Float.MAX_VALUE),
)

@Composable
fun LightScreen(onBack: () -> Unit) {
    val sensor = rememberSensor(Sensor.TYPE_LIGHT)
    var lux by remember { mutableFloatStateOf(Float.NaN) }
    var extra by remember { mutableStateOf(FloatArray(0)) }
    var minLux by remember { mutableFloatStateOf(Float.NaN) }
    var maxLux by remember { mutableFloatStateOf(Float.NaN) }
    var sum by remember { mutableDoubleStateOf(0.0) }
    var count by remember { mutableIntStateOf(0) }
    val history = remember { SeriesBuffer(1, 600) }

    SensorEffect(sensor, SensorManager.SENSOR_DELAY_UI) { e ->
        val v = e.values[0]
        lux = v
        extra = if (e.values.size > 1) e.values.copyOfRange(1, e.values.size) else FloatArray(0)
        minLux = if (minLux.isNaN()) v else minOf(minLux, v)
        maxLux = if (maxLux.isNaN()) v else maxOf(maxLux, v)
        sum += v; count++
        history.push(log10(v + 1f))
    }

    var iso by rememberSaveable { mutableIntStateOf(100) }
    var aperture by rememberSaveable { mutableFloatStateOf(2.8f) }
    val ev100 = if (lux > 0f) log2(lux / 2.5f) else Float.NaN
    val evIso = ev100 + log2(iso / 100f)
    val shutter = if (evIso.isNaN()) Float.NaN else aperture * aperture / 2f.pow(evIso)

    ToolScaffold(title = "Light meter", subtitle = sensor?.let { "${it.name} · max ${it.maximumRange.fmt(0)} lx" }, onBack = onBack) {
        if (sensor == null) {
            MissingSensor("ambient light")
            return@ToolScaffold
        }
        BigReadout(
            value = if (lux.isNaN()) "—" else if (lux >= 100f) lux.fmt(0) else lux.fmt(1),
            unit = "lx",
            label = "Illuminance",
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        StatGrid(
            listOf(
                Stat("Foot-candles", (lux / 10.764f).fmt(1), "fc"),
                Stat("EV₁₀₀", ev100.fmt(1)),
                Stat("Average", if (count > 0) (sum / count).fmt(0) else "—", "lx"),
                Stat("Min", minLux.fmt(0), "lx"),
                Stat("Max", maxLux.fmt(0), "lx"),
                Stat("Samples", count.toString()),
            ),
        )
        OutlinedButton(
            onClick = {
                minLux = Float.NaN; maxLux = Float.NaN; sum = 0.0; count = 0; history.clear()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset statistics") }

        Panel(title = "History (log scale)") {
            LiveChart(
                history,
                listOf(MaterialTheme.colorScheme.secondary),
                fixedMin = 0f,
                yFormat = { v -> "${(10f.pow(v) - 1f).roundToInt()} lx" },
            )
        }

        Panel(title = "Exposure calculator") {
            SectionLabel("ISO")
            ChoiceRow(ISOS, iso, { iso = it })
            SectionLabel("Aperture")
            ChoiceRow(APERTURES, aperture, { aperture = it }, label = { "f/${if (it % 1f == 0f) it.fmt(0) else it.fmt(1)}" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigReadout(value = formatShutter(shutter), label = "Shutter", fontSize = 36.sp)
                BigReadout(value = evIso.fmt(1), label = "EV @ ISO $iso", fontSize = 36.sp)
            }
            Note("Incident-light reading (calibration constant C = 250). Point the top of the screen towards the light falling on your subject.")
        }

        Panel(title = "Typical light levels") {
            BANDS.forEach { band ->
                val active = !lux.isNaN() && lux >= band.from && lux < band.to
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        band.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (band.to == Float.MAX_VALUE) "> ${band.from.fmt(0)} lx" else "${band.from.fmt(0)}–${band.to.fmt(0)} lx",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (extra.isNotEmpty()) {
            Panel(title = "Additional channels") {
                StatGrid(extra.mapIndexed { i, v -> Stat("value[${i + 1}]", v.fmt(1)) })
                Note("Extra values reported by the vendor light sensor (Samsung exposes raw channel data on some models).")
            }
        }
        Note("The S25 Ultra's light sensor sits under the display near the top. Screen brightness and fingers can affect it; for best results face the screen towards the light.")
    }
}

private fun formatShutter(seconds: Float): String {
    if (seconds.isNaN() || seconds.isInfinite()) return "—"
    if (seconds < 1f / 12000f) return "<1/8000"
    if (seconds > 45f) return ">30s"
    val nearest = SHUTTERS.minBy { abs(log2(it) - log2(seconds)) }
    return if (nearest >= 1f) "${nearest.fmt(0)}s" else "1/${(1f / nearest).roundToInt()}"
}
