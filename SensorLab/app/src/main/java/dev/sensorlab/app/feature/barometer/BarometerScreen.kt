package dev.sensorlab.app.feature.barometer

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.rememberPrefFloat
import dev.sensorlab.app.core.rememberSensor
import dev.sensorlab.app.core.signed
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.LiveChart
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlin.math.abs
import kotlin.math.pow

private const val STANDARD_QNH = SensorManager.PRESSURE_STANDARD_ATMOSPHERE // 1013.25 hPa

@Composable
fun BarometerScreen(onBack: () -> Unit) {
    val ic = LocalInstrumentColors.current
    val sensor = rememberSensor(Sensor.TYPE_PRESSURE)

    var pressure by remember { mutableFloatStateOf(Float.NaN) }
    var lastSample by remember { mutableLongStateOf(0L) }
    val history = remember { SeriesBuffer(1, 1800) } // 30 min at 1 Hz
    val altHistory = remember { SeriesBuffer(1, 1800) }
    var minP by remember { mutableFloatStateOf(Float.NaN) }
    var maxP by remember { mutableFloatStateOf(Float.NaN) }
    var startP by remember { mutableFloatStateOf(Float.NaN) }

    var qnh by rememberPrefFloat("baro_qnh", STANDARD_QNH)
    var zeroAltitude by remember { mutableStateOf<Float?>(null) }
    var elevationInput by remember { mutableStateOf("") }

    SensorEffect(sensor, SensorManager.SENSOR_DELAY_UI) { e ->
        val p = e.values[0]
        pressure = if (pressure.isNaN()) p else pressure + 0.12f * (p - pressure)
        if (startP.isNaN()) startP = p
        val now = System.currentTimeMillis()
        if (now - lastSample >= 1000L) {
            lastSample = now
            history.push(pressure)
            altHistory.push(SensorManager.getAltitude(qnh, pressure))
            minP = if (minP.isNaN()) pressure else minOf(minP, pressure)
            maxP = if (maxP.isNaN()) pressure else maxOf(maxP, pressure)
        }
    }

    val altitude = if (pressure.isNaN()) Float.NaN else SensorManager.getAltitude(qnh, pressure)
    val relative = zeroAltitude?.let { altitude - it }
    val trendPerHour = remember(history.version) { slopePerHour(history) }

    ToolScaffold(title = "Barometer", subtitle = sensor?.let { "${it.name} · ${it.vendor}" }, onBack = onBack) {
        if (sensor == null) {
            MissingSensor("barometer")
            return@ToolScaffold
        }

        BigReadout(
            value = pressure.fmt(2),
            unit = "hPa",
            label = "Station pressure",
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        StatGrid(
            listOf(
                Stat("inHg", (pressure * 0.0295300f).fmt(2)),
                Stat("mmHg", (pressure * 0.750062f).fmt(1)),
                Stat(
                    "Trend",
                    trendPerHour?.signed(2) ?: "…",
                    "hPa/h",
                    when {
                        trendPerHour == null -> MaterialTheme.colorScheme.onSurface
                        trendPerHour > 0.5f -> ic.good
                        trendPerHour < -0.5f -> ic.bad
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                ),
            ),
        )

        Panel(title = "Pressure · last 30 min") {
            LiveChart(history, listOf(MaterialTheme.colorScheme.primary), minSpan = 0.3f)
            StatGrid(
                listOf(
                    Stat("Min", minP.fmt(2), "hPa"),
                    Stat("Max", maxP.fmt(2), "hPa"),
                    Stat("Since open", (pressure - startP).signed(2), "hPa"),
                ),
            )
        }

        Panel(title = "Altimeter") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigReadout(value = altitude.fmt(0), unit = "m", label = "Altitude", fontSize = 40.sp)
                BigReadout(
                    value = relative?.signed(1) ?: "—",
                    unit = "m",
                    label = "Relative",
                    fontSize = 40.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (relative != null) {
                Text(
                    "≈ ${(relative / 3.0f).fmt(1)} floors (3 m per floor)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { zeroAltitude = altitude }, modifier = Modifier.weight(1f)) { Text("Set zero here") }
                OutlinedButton(onClick = { zeroAltitude = null }, modifier = Modifier.weight(1f)) { Text("Clear") }
            }
            LiveChart(altHistory, listOf(MaterialTheme.colorScheme.secondary), minSpan = 2f)
        }

        Panel(title = "Reference pressure (QNH)") {
            Text(
                "Sea-level pressure ${qnh.fmt(2)} hPa",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { qnh -= 1f }, modifier = Modifier.weight(1f)) { Text("−1") }
                FilledTonalButton(onClick = { qnh -= 0.1f }, modifier = Modifier.weight(1f)) { Text("−0.1") }
                FilledTonalButton(onClick = { qnh += 0.1f }, modifier = Modifier.weight(1f)) { Text("+0.1") }
                FilledTonalButton(onClick = { qnh += 1f }, modifier = Modifier.weight(1f)) { Text("+1") }
            }
            OutlinedTextField(
                value = elevationInput,
                onValueChange = { elevationInput = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' } },
                label = { Text("Known elevation here (m)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val h = elevationInput.toFloatOrNull()
                        if (h != null && !pressure.isNaN()) qnh = seaLevelPressure(pressure, h)
                    },
                    enabled = elevationInput.toFloatOrNull() != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Calibrate") }
                OutlinedButton(onClick = { qnh = STANDARD_QNH }, modifier = Modifier.weight(1f)) { Text("Standard 1013.25") }
            }
            Note("Absolute altitude needs today's sea-level pressure (QNH, from a local weather report or airport METAR) or a known elevation. Relative altitude is accurate to about ±1 m regardless.")
        }
    }
}

/** Inverse of the international barometric formula used by SensorManager.getAltitude. */
private fun seaLevelPressure(pressure: Float, elevationM: Float): Float =
    (pressure / (1.0 - elevationM / 44330.0).pow(5.255)).toFloat()

/** Least-squares slope of the recorded history, in hPa per hour. Needs at least 2 minutes of data. */
private fun slopePerHour(buffer: SeriesBuffer): Float? {
    val rb = buffer.series[0]
    val n = rb.size
    if (n < 120) return null
    var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
    for (i in 0 until n) {
        val x = i.toDouble()
        val y = rb[i].toDouble()
        sx += x; sy += y; sxx += x * x; sxy += x * y
    }
    val denom = n * sxx - sx * sx
    if (abs(denom) < 1e-9) return null
    val slopePerSecond = (n * sxy - sx * sy) / denom
    return (slopePerSecond * 3600.0).toFloat()
}
