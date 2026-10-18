package dev.sensorlab.app.feature.explorer

import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sensorlab.app.core.RateMeter
import dev.sensorlab.app.core.SensorCatalog
import dev.sensorlab.app.core.SensorEffect
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.TriggerSensorEffect
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.grouped
import dev.sensorlab.app.core.sensorManager
import dev.sensorlab.app.ui.components.AccuracyPill
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.KeyValue
import dev.sensorlab.app.ui.components.Legend
import dev.sensorlab.app.ui.components.LiveChart
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Pill
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DetailRate(val label: String, val value: Int) {
    Normal("Normal", SensorManager.SENSOR_DELAY_NORMAL),
    Ui("UI", SensorManager.SENSOR_DELAY_UI),
    Game("Game", SensorManager.SENSOR_DELAY_GAME),
    Fastest("Fastest", SensorManager.SENSOR_DELAY_FASTEST),
}

private const val MAX_ROWS = 1_000_000

/** CSV recorder kept outside composition so high-rate sensors don't allocate state per row. */
private class CsvRecorder {
    private val sb = StringBuilder()
    var rows = 0
        private set
    var startNs = 0L
        private set

    fun begin(axes: List<String>) {
        sb.setLength(0)
        rows = 0
        startNs = 0L
        sb.append("timestamp_ns,elapsed_s,accuracy")
        axes.forEach { sb.append(',').append(it.replace(',', ' ')) }
        sb.append('\n')
    }

    fun add(timestampNs: Long, accuracy: Int, values: FloatArray) {
        if (rows >= MAX_ROWS) return
        if (startNs == 0L) startNs = timestampNs
        sb.append(timestampNs).append(',')
            .append(String.format(Locale.US, "%.6f", (timestampNs - startNs) / 1e9)).append(',')
            .append(accuracy)
        for (v in values) sb.append(',').append(v)
        sb.append('\n')
        rows++
    }

    fun text(): String = sb.toString()
}

@Composable
fun SensorDetailScreen(index: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val sensor: Sensor? = remember(index) { allSensors(context.sensorManager).getOrNull(index) }
    if (sensor == null) {
        ToolScaffold(title = "Sensor", onBack = onBack) { Text("Sensor not found.") }
        return
    }
    val oneShot = sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT

    var rate by rememberSaveable { mutableStateOf(DetailRate.Game) }
    var values by remember { mutableStateOf(FloatArray(0)) }
    var accuracy by remember { mutableIntStateOf(-2) }
    var lastTimestamp by remember { mutableLongStateOf(0L) }
    var eventCount by remember { mutableLongStateOf(0L) }
    val meter = remember { RateMeter() }
    val chart = remember { SeriesBuffer(6, 300) }
    val triggers = remember { mutableStateListOf<Long>() }

    val recorder = remember { CsvRecorder() }
    var recording by remember { mutableStateOf(false) }
    var recordedRows by remember { mutableIntStateOf(0) }
    var hasRecording by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (oneShot) {
        TriggerSensorEffect(sensor) { e ->
            values = e.values.copyOf()
            lastTimestamp = e.timestamp
            eventCount++
            triggers.add(0, System.currentTimeMillis())
            if (recording) recorder.add(e.timestamp, 3, e.values)
        }
    } else {
        SensorEffect(sensor, rate.value, onAccuracy = { accuracy = it }) { e ->
            values = e.values.copyOf()
            accuracy = e.accuracy
            lastTimestamp = e.timestamp
            eventCount++
            meter.tick(e.timestamp)
            chart.pushArray(e.values)
            if (recording) {
                recorder.add(e.timestamp, e.accuracy, e.values)
                if (recorder.rows % 25 == 0) recordedRows = recorder.rows
            }
        }
    }

    val axes = SensorCatalog.axes(sensor.type, values.size)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val data = recorder.text()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray()) } != null
                    }.getOrDefault(false)
                }
                exportMessage = if (ok) "Saved ${recorder.rows.grouped()} rows" else "Export failed"
            }
        }
    }

    ToolScaffold(title = sensor.name, subtitle = "${SensorCatalog.name(sensor.type)} · ${sensor.vendor}", onBack = onBack) {
        if (!oneShot) {
            ChoiceRow(DetailRate.entries.toList(), rate, { rate = it; meter.reset() }, label = { it.label })
        }

        Panel(
            title = "Live values",
            trailing = { if (!oneShot) AccuracyPill(accuracy) else Pill("One-shot trigger", ic.warn) },
        ) {
            if (values.isEmpty()) {
                Text(
                    when {
                        oneShot -> "Armed — waiting for the trigger condition."
                        sensor.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE -> "Waiting for the value to change…"
                        else -> "Waiting for data…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            values.forEachIndexed { i, v -> KeyValue(axes[i], v.fmt(5)) }
            KeyValue("Events", eventCount.grouped())
            if (!oneShot) KeyValue("Measured rate", "${meter.hz.fmt(1)} Hz")
            if (lastTimestamp > 0) {
                val ageMs = (SystemClock.elapsedRealtimeNanos() - lastTimestamp) / 1_000_000
                KeyValue("Last event", "${ageMs.coerceAtLeast(0)} ms ago")
            }
        }

        if (!oneShot && values.isNotEmpty()) {
            Panel(title = "Chart") {
                val n = minOf(values.size, 6)
                LiveChart(chart, ic.series, visible = List(6) { it < n })
                Legend(axes.take(n).mapIndexed { i, a -> a.substringBefore(' ') to ic.series[i] })
            }
        }

        if (oneShot && triggers.isNotEmpty()) {
            Panel(title = "Triggers") {
                val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                triggers.take(10).forEach { KeyValue("Triggered", fmt.format(Date(it))) }
            }
        }

        Panel(title = "Record to CSV") {
            Text(
                when {
                    recording -> "Recording… ${recordedRows.grouped()} rows"
                    hasRecording -> "${recorder.rows.grouped()} rows ready to export"
                    else -> "Records every event with its nanosecond timestamp."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!recording) {
                    Button(onClick = {
                        recorder.begin(SensorCatalog.axes(sensor.type, maxOf(values.size, 1)))
                        recordedRows = 0
                        recording = true
                        exportMessage = null
                    }, modifier = Modifier.weight(1f)) { Text("Start") }
                } else {
                    Button(onClick = {
                        recording = false
                        recordedRows = recorder.rows
                        hasRecording = true
                    }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
                OutlinedButton(
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        val safe = sensor.name.replace(Regex("[^A-Za-z0-9]+"), "_").take(40)
                        exporter.launch("sensorlab_${safe}_$stamp.csv")
                    },
                    enabled = hasRecording && !recording,
                    modifier = Modifier.weight(1f),
                ) { Text("Export") }
            }
            exportMessage?.let { Text(it, color = ic.good, style = MaterialTheme.typography.bodyMedium) }
        }

        Panel(title = "Specifications") {
            KeyValue("Type", "${sensor.type} · ${sensor.stringType}")
            KeyValue("Group", SensorCatalog.group(sensor.type).title)
            KeyValue("Vendor", sensor.vendor)
            KeyValue("Version", sensor.version.toString())
            KeyValue("Sensor ID", sensor.id.toString())
            KeyValue("Reporting mode", SensorCatalog.reportingMode(sensor))
            KeyValue("Wake-up", if (sensor.isWakeUpSensor) "Yes" else "No")
            KeyValue("Dynamic", if (sensor.isDynamicSensor) "Yes" else "No")
            KeyValue("Maximum range", sensor.maximumRange.fmt(4))
            KeyValue("Resolution", sensor.resolution.fmt(6))
            KeyValue("Power", "${sensor.power.fmt(3)} mA")
            KeyValue(
                "Min delay",
                if (sensor.minDelay > 0) "${sensor.minDelay} µs (${(1_000_000f / sensor.minDelay).fmt(0)} Hz max)" else sensor.minDelay.toString(),
            )
            KeyValue("Max delay", "${sensor.maxDelay} µs")
            KeyValue("FIFO reserved / max", "${sensor.fifoReservedEventCount} / ${sensor.fifoMaxEventCount}")
            KeyValue(
                "Direct channel",
                when (sensor.highestDirectReportRateLevel) {
                    SensorDirectChannel.RATE_NORMAL -> "~50 Hz"
                    SensorDirectChannel.RATE_FAST -> "~200 Hz"
                    SensorDirectChannel.RATE_VERY_FAST -> "~800 Hz"
                    else -> "Not supported"
                },
            )
            KeyValue("Additional info", if (sensor.isAdditionalInfoSupported) "Supported" else "No")
            if (sensor.type >= SensorCatalog.VENDOR_BASE) {
                Note("Vendor sensors are private to the manufacturer. Their values are shown raw; their meaning isn't documented publicly.")
            }
        }
    }
}
