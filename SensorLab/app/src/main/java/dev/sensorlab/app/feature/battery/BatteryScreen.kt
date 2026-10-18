package dev.sensorlab.app.feature.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.sensorlab.app.core.PeriodicEffect
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.grouped
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.KeyValue
import dev.sensorlab.app.ui.components.LiveChart
import dev.sensorlab.app.ui.components.MeterBar
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Pill
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import kotlin.math.abs

private fun healthName(h: Int) = when (h) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
    else -> "Unknown"
}

private fun pluggedName(p: Int) = when (p) {
    BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
    BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
    0 -> "Unplugged"
    else -> "Other"
}

private fun statusName(s: Int) = when (s) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
    BatteryManager.BATTERY_STATUS_FULL -> "Full"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
    else -> "Unknown"
}

private fun thermalName(s: Int) = when (s) {
    PowerManager.THERMAL_STATUS_NONE -> "Normal"
    PowerManager.THERMAL_STATUS_LIGHT -> "Light"
    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
    else -> "Unknown"
}

/**
 * Android specifies microamps, but some Samsung firmware reports milliamps.
 * Real phone currents are tens to thousands of mA, so values below 20 000 are treated as mA.
 */
private fun toMilliAmps(raw: Int): Float =
    if (raw == Int.MIN_VALUE) Float.NaN else if (abs(raw) < 20_000) raw.toFloat() else raw / 1000f

@Composable
fun BatteryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val bm = remember { context.getSystemService(BatteryManager::class.java) }
    val pm = remember { context.getSystemService(PowerManager::class.java) }

    var batteryIntent by remember { mutableStateOf<Intent?>(null) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                batteryIntent = i
            }
        }
        batteryIntent = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    var currentNow by remember { mutableFloatStateOf(Float.NaN) }
    var currentAvg by remember { mutableFloatStateOf(Float.NaN) }
    var chargeCounter by remember { mutableLongStateOf(-1L) }
    var thermalStatus by remember { mutableIntStateOf(pm?.currentThermalStatus ?: 0) }
    var headroom by remember { mutableFloatStateOf(Float.NaN) }
    val currentChart = remember { SeriesBuffer(1, 300) }
    val tempChart = remember { SeriesBuffer(1, 300) }

    val intent = batteryIntent
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val percent = if (level >= 0 && scale > 0) level * 100f / scale else Float.NaN
    val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
    val tempC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE).let {
        if (it == Int.MIN_VALUE) Float.NaN else it / 10f
    }
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
    val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "—"
    val cycles = intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1

    PeriodicEffect(1000L) {
        if (bm != null) {
            currentNow = toMilliAmps(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            currentAvg = toMilliAmps(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
            chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
        }
        if (pm != null) {
            thermalStatus = pm.currentThermalStatus
            headroom = pm.getThermalHeadroom(10)
        }
        currentChart.push(currentNow)
        tempChart.push(tempC)
    }

    val powerW = if (voltageMv > 0 && !currentNow.isNaN()) voltageMv / 1000f * currentNow / 1000f else Float.NaN
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING

    ToolScaffold(title = "Battery & thermal", subtitle = "Fuel gauge, charger and thermal sensors", onBack = onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            BigReadout(value = percent.fmt(0), unit = "%", label = "Charge", fontSize = 56.sp)
            BigReadout(
                value = if (powerW.isNaN()) "—" else abs(powerW).fmt(2),
                unit = "W",
                label = if (charging) "Charging" else "Drawing",
                fontSize = 40.sp,
                color = if (charging) ic.good else MaterialTheme.colorScheme.primary,
            )
        }
        MeterBar(
            fraction = percent / 100f,
            color = when {
                percent < 15f -> ic.bad
                percent < 30f -> ic.warn
                else -> ic.good
            },
            thickness = 14.dp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Pill("${statusName(status)} · ${pluggedName(plugged)}", MaterialTheme.colorScheme.primary)
        }

        StatGrid(
            listOf(
                Stat("Current", currentNow.fmt(0), "mA", if (!currentNow.isNaN() && currentNow > 0) ic.good else MaterialTheme.colorScheme.onSurface),
                Stat("Average", currentAvg.fmt(0), "mA"),
                Stat("Voltage", if (voltageMv > 0) (voltageMv / 1000f).fmt(3) else "—", "V"),
                Stat("Temperature", tempC.fmt(1), "°C", if (tempC > 40f) ic.bad else if (tempC > 35f) ic.warn else MaterialTheme.colorScheme.onSurface),
                Stat("Remaining", if (chargeCounter > 0) (chargeCounter / 1000).toString() else "—", "mAh"),
                Stat("Cycles", if (cycles >= 0) cycles.toString() else "—"),
            ),
        )

        Panel(title = "Current (mA) · + charging / − discharging") {
            LiveChart(currentChart, listOf(MaterialTheme.colorScheme.primary), referenceLines = listOf(0f), yFormat = { it.fmt(0) })
            if (!charging && !currentNow.isNaN() && currentNow < 0 && chargeCounter > 0) {
                val hours = (chargeCounter / 1000f) / abs(currentNow)
                Text(
                    "At this rate: about ${hours.fmt(1)} h remaining",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Panel(title = "Thermal") {
            val tColor = when {
                thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> ic.bad
                thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT -> ic.warn
                else -> ic.good
            }
            StatGrid(
                listOf(
                    Stat("Status", thermalName(thermalStatus), color = tColor),
                    Stat("Headroom", if (headroom.isNaN()) "—" else (headroom * 100f).fmt(0), "%"),
                    Stat("Battery", tempC.fmt(1), "°C"),
                ),
            )
            LiveChart(tempChart, listOf(ic.warn), minSpan = 2f, yFormat = { "${it.fmt(1)}°" })
            Note("Thermal headroom is Android's forecast of how close the phone is to throttling (100% = at the 'severe' limit), 10 seconds ahead.")
        }

        Panel(title = "Battery") {
            KeyValue("Health", healthName(health))
            KeyValue("Technology", technology)
            KeyValue("Charge counter", if (chargeCounter > 0) "${chargeCounter.grouped()} µAh" else "—")
            KeyValue("Present", if (intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) != false) "Yes" else "No")
            Note("The S25 Ultra has a 5,000 mAh cell. Some Samsung firmware reports current in mA rather than the standard µA; Sensor Lab detects and normalises this.")
        }
    }
}
