package dev.sensorlab.app.feature.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.sensorlab.app.core.PeriodicEffect
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.formatDuration
import dev.sensorlab.app.ui.components.BigReadout
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.KeyValue
import dev.sensorlab.app.ui.components.Legend
import dev.sensorlab.app.ui.components.MeterBar
import dev.sensorlab.app.ui.components.MonoSmall
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.PermissionGate
import dev.sensorlab.app.ui.components.Pill
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private data class Sat(
    val constellation: Int,
    val svid: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val used: Boolean,
    val carrierHz: Float,
    val hasEphemeris: Boolean,
)

private enum class GnssTab(val label: String) { Sky("Sky"), Signals("Signals"), Position("Position"), Speed("Speed") }

private enum class SpeedUnit(val label: String, val factor: Float) {
    Kmh("km/h", 3.6f), Mph("mph", 2.236936f), Knots("kn", 1.943844f), Ms("m/s", 1f),
}

private fun constellationName(t: Int) = when (t) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
    else -> "Other"
}

private fun constellationPrefix(t: Int) = when (t) {
    GnssStatus.CONSTELLATION_GPS -> "G"
    GnssStatus.CONSTELLATION_SBAS -> "S"
    GnssStatus.CONSTELLATION_GLONASS -> "R"
    GnssStatus.CONSTELLATION_QZSS -> "J"
    GnssStatus.CONSTELLATION_BEIDOU -> "C"
    GnssStatus.CONSTELLATION_GALILEO -> "E"
    GnssStatus.CONSTELLATION_IRNSS -> "I"
    else -> "?"
}

private fun constellationColor(t: Int) = when (t) {
    GnssStatus.CONSTELLATION_GPS -> Color(0xFF60A5FA)
    GnssStatus.CONSTELLATION_GLONASS -> Color(0xFFFF6B6B)
    GnssStatus.CONSTELLATION_GALILEO -> Color(0xFFFFB547)
    GnssStatus.CONSTELLATION_BEIDOU -> Color(0xFF4ADE80)
    GnssStatus.CONSTELLATION_QZSS -> Color(0xFFC084FC)
    GnssStatus.CONSTELLATION_SBAS -> Color(0xFF9AA5B1)
    GnssStatus.CONSTELLATION_IRNSS -> Color(0xFF5EEAD4)
    else -> Color(0xFF6B7280)
}

/** Frequency band label from the carrier frequency. */
private fun band(hz: Float): String = when {
    hz <= 0f -> ""
    hz > 1.55e9f -> "L1"
    hz in 1.16e9f..1.19e9f -> "L5"
    hz in 1.19e9f..1.30e9f -> "L2/E6"
    else -> "${(hz / 1e6f).roundToInt()} MHz"
}

private fun dms(value: Double, isLatitude: Boolean): String {
    val hemi = if (isLatitude) (if (value >= 0) "N" else "S") else (if (value >= 0) "E" else "W")
    val a = abs(value)
    val d = a.toInt()
    val minutesF = (a - d) * 60.0
    val m = minutesF.toInt()
    val s = (minutesF - m) * 60.0
    return String.format(Locale.US, "%d°%02d′%05.2f″ %s", d, m, s, hemi)
}

@Composable
fun GnssScreen(onBack: () -> Unit) {
    ToolScaffold(title = "GNSS & speed", subtitle = "Satellites, signals and position", onBack = onBack) {
        PermissionGate(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            "Reading satellites and position needs precise location access. Nothing leaves your phone.",
        ) {
            GnssContent()
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ColumnScope.GnssContent() {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val lm = remember { context.getSystemService(LocationManager::class.java) }
    if (lm == null) {
        Text("Location service unavailable.")
        return
    }

    var sats by remember { mutableStateOf<List<Sat>>(emptyList()) }
    var location by remember { mutableStateOf<Location?>(null) }
    var ttff by remember { mutableStateOf<Int?>(null) }
    var gpsEnabled by remember { mutableStateOf(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) }
    var tab by rememberSaveable { mutableStateOf(GnssTab.Sky) }
    var unit by rememberSaveable { mutableStateOf(SpeedUnit.Kmh) }

    var maxSpeed by remember { mutableFloatStateOf(0f) }
    var tripMeters by remember { mutableFloatStateOf(0f) }
    var movingMillis by remember { mutableLongStateOf(0L) }
    var lastFix by remember { mutableStateOf<Location?>(null) }
    var nowNanos by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }

    fun onFix(l: Location) {
        location = l
        val prev = lastFix
        if (l.hasSpeed() && l.speed > maxSpeed && l.accuracy < 30f) maxSpeed = l.speed
        if (prev != null && l.accuracy < 25f && l.hasSpeed() && l.speed > 0.6f) {
            tripMeters += prev.distanceTo(l)
            movingMillis += (l.time - prev.time).coerceIn(0L, 5_000L)
        }
        lastFix = l
    }

    LifecycleResumeEffect(lm) {
        val executor = context.mainExecutor
        val statusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                sats = (0 until status.satelliteCount).map { i ->
                    Sat(
                        constellation = status.getConstellationType(i),
                        svid = status.getSvid(i),
                        cn0 = status.getCn0DbHz(i),
                        elevation = status.getElevationDegrees(i),
                        azimuth = status.getAzimuthDegrees(i),
                        used = status.usedInFix(i),
                        carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else 0f,
                        hasEphemeris = status.hasEphemerisData(i),
                    )
                }
            }

            override fun onFirstFix(ttffMillis: Int) {
                ttff = ttffMillis
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) = onFix(l)
            override fun onProviderEnabled(provider: String) {
                gpsEnabled = true
            }

            override fun onProviderDisabled(provider: String) {
                gpsEnabled = false
            }
        }
        runCatching {
            lm.registerGnssStatusCallback(executor, statusCallback)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, executor, listener)
        }
        onPauseOrDispose {
            lm.unregisterGnssStatusCallback(statusCallback)
            lm.removeUpdates(listener)
        }
    }
    PeriodicEffect(1000L) { nowNanos = SystemClock.elapsedRealtimeNanos() }

    if (!gpsEnabled) {
        Panel(title = "Location is off") {
            Text("Turn on Location to receive satellites.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open location settings") }
        }
    }

    val used = sats.count { it.used }
    val dualBand = sats.any { band(it.carrierHz) == "L5" }
    val fixAge = location?.let { (nowNanos - it.elapsedRealtimeNanos) / 1e9f }
    val hasFix = fixAge != null && fixAge < 5f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(if (hasFix) (if (location?.hasAltitude() == true) "3D fix" else "2D fix") else "No fix", if (hasFix) ic.good else ic.warn)
        Pill("$used / ${sats.size} sats", MaterialTheme.colorScheme.primary)
        if (dualBand) Pill("Dual-band L1+L5", MaterialTheme.colorScheme.secondary)
    }

    ChoiceRow(GnssTab.entries.toList(), tab, { tab = it }, label = { it.label })

    when (tab) {
        GnssTab.Sky -> {
            Panel(title = "Sky plot · north up") {
                SkyPlot(sats, Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp))
                Note("Filled = used in the position fix. Dot size = signal strength. Rings at 0°, 30° and 60° elevation.")
            }
            Panel(title = "Constellations") {
                val groups = sats.groupBy { it.constellation }.toSortedMap()
                if (groups.isEmpty()) Text("Waiting for satellites… go outside with a clear view of the sky.", style = MaterialTheme.typography.bodyMedium)
                groups.forEach { (c, list) ->
                    val bands = list.map { band(it.carrierHz) }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("+")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Legend(listOf(constellationName(c) to constellationColor(c)), Modifier.weight(1f))
                        Text(
                            "${list.count { it.used }}/${list.size}  $bands",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }

        GnssTab.Signals -> {
            Panel(title = "Carrier-to-noise density (dB-Hz)") {
                val sorted = sats.sortedByDescending { it.cn0 }
                if (sorted.isEmpty()) Text("No satellites in view yet.", style = MaterialTheme.typography.bodyMedium)
                sorted.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${constellationPrefix(s.constellation)}${s.svid.toString().padStart(2, '0')} ${band(s.carrierHz)}",
                            style = MonoSmall,
                            modifier = Modifier.width(78.dp),
                        )
                        MeterBar(
                            fraction = s.cn0 / 50f,
                            color = constellationColor(s.constellation).copy(alpha = if (s.used) 1f else 0.4f),
                            modifier = Modifier.weight(1f),
                            thickness = 8.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(s.cn0.fmt(0), style = MonoSmall, modifier = Modifier.width(26.dp))
                    }
                }
                Note("Above ~30 dB-Hz is a good signal. L5/E5a signals are more resistant to multipath in cities.")
            }
        }

        GnssTab.Position -> {
            Panel(title = "Position") {
                val l = location
                if (l == null) {
                    Text("Waiting for first fix…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    KeyValue("Latitude", "${l.latitude.fmt(6)}°")
                    KeyValue("", dms(l.latitude, true))
                    KeyValue("Longitude", "${l.longitude.fmt(6)}°")
                    KeyValue("", dms(l.longitude, false))
                    KeyValue("Altitude (WGS84)", if (l.hasAltitude()) "${l.altitude.fmt(1)} m" else "—")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && l.hasMslAltitude()) {
                        KeyValue("Altitude (sea level)", "${l.mslAltitudeMeters.fmt(1)} m")
                    }
                    KeyValue("Horizontal accuracy", "±${l.accuracy.fmt(1)} m")
                    if (l.hasVerticalAccuracy()) KeyValue("Vertical accuracy", "±${l.verticalAccuracyMeters.fmt(1)} m")
                    KeyValue("Bearing", if (l.hasBearing()) "${l.bearing.fmt(0)}°" else "—")
                    KeyValue("Fix age", "${fixAge?.fmt(1) ?: "—"} s")
                    KeyValue("Time to first fix", ttff?.let { "${(it / 1000f).fmt(1)} s" } ?: "—")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val text = "${l.latitude.fmt(6)}, ${l.longitude.fmt(6)}"
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Coordinates", text))
                        }, modifier = Modifier.weight(1f)) { Text("Copy") }
                        OutlinedButton(onClick = {
                            val uri = Uri.parse("geo:${l.latitude},${l.longitude}?q=${l.latitude},${l.longitude}")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, modifier = Modifier.weight(1f)) { Text("Open in Maps") }
                    }
                }
            }
            Panel(title = "GNSS chipset") {
                KeyValue("Hardware", lm.gnssHardwareModelName ?: "—")
                KeyValue("Year of hardware", lm.gnssYearOfHardware.toString())
                val caps = lm.gnssCapabilities
                KeyValue("Raw measurements", if (caps.hasMeasurements()) "Yes" else "No")
                KeyValue("Navigation messages", if (caps.hasNavigationMessages()) "Yes" else "No")
                KeyValue("Antenna info", if (caps.hasAntennaInfo()) "Yes" else "No")
                KeyValue("Measurement corrections", if (caps.hasMeasurementCorrections()) "Yes" else "No")
                KeyValue("Satellite blocklist", if (caps.hasSatelliteBlocklist()) "Yes" else "No")
            }
        }

        GnssTab.Speed -> {
            val l = location
            val speed = if (hasFix && l != null && l.hasSpeed()) l.speed else 0f
            ChoiceRow(SpeedUnit.entries.toList(), unit, { unit = it }, label = { it.label })
            BigReadout(
                value = (speed * unit.factor).fmt(if (unit == SpeedUnit.Ms) 1 else 0),
                unit = unit.label,
                label = "Speed",
                fontSize = 96.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            val avg = if (movingMillis > 0) tripMeters / (movingMillis / 1000f) else 0f
            StatGrid(
                listOf(
                    Stat("Max", (maxSpeed * unit.factor).fmt(0), unit.label),
                    Stat("Avg moving", (avg * unit.factor).fmt(0), unit.label),
                    Stat("Trip", if (unit == SpeedUnit.Mph) (tripMeters / 1609.344f).fmt(2) else (tripMeters / 1000f).fmt(2), if (unit == SpeedUnit.Mph) "mi" else "km"),
                    Stat("Moving time", formatDuration(movingMillis / 1000)),
                    Stat("Heading", if (l?.hasBearing() == true) "${l.bearing.fmt(0)}°" else "—"),
                    Stat(
                        "Speed acc.",
                        if (l != null && l.hasSpeedAccuracy()) "±${(l.speedAccuracyMetersPerSecond * unit.factor).fmt(1)}" else "—",
                        unit.label,
                    ),
                ),
            )
            OutlinedButton(
                onClick = { maxSpeed = 0f; tripMeters = 0f; movingMillis = 0L },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset trip") }
            Note("GNSS speed is measured from Doppler shift, so it is typically accurate to a few tenths of a km/h — but keep your eyes on the road.")
        }
    }
}

@Composable
private fun SkyPlot(sats: List<Sat>, modifier: Modifier) {
    val ic = LocalInstrumentColors.current
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val cardinalStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ic.tick)
    val svStyle = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = ic.tickMinor)
    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) / 2f * 0.88f
        drawCircle(ic.dialFace, r / 0.88f, c)
        for (elev in listOf(0f, 30f, 60f)) {
            drawCircle(cs.outlineVariant, r * (90f - elev) / 90f, c, style = Stroke(1.dp.toPx()))
        }
        drawLine(cs.outlineVariant, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(cs.outlineVariant, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        for ((label, angle) in listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)) {
            val a = Math.toRadians(angle.toDouble())
            val layout = measurer.measure(label, if (label == "N") cardinalStyle.copy(color = ic.bad) else cardinalStyle)
            val px = c.x + (r + 12.dp.toPx()) * sin(a).toFloat()
            val py = c.y - (r + 12.dp.toPx()) * cos(a).toFloat()
            drawText(layout, topLeft = Offset(px - layout.size.width / 2f, py - layout.size.height / 2f))
        }
        for (s in sats) {
            if (s.elevation < 0f || (s.elevation == 0f && s.azimuth == 0f)) continue
            val rr = r * (90f - s.elevation) / 90f
            val a = Math.toRadians(s.azimuth.toDouble())
            val p = Offset(c.x + rr * sin(a).toFloat(), c.y - rr * cos(a).toFloat())
            val dot = (3f + (s.cn0 / 50f).coerceIn(0f, 1f) * 6f).dp.toPx()
            val col = constellationColor(s.constellation)
            if (s.used) drawCircle(col, dot, p) else drawCircle(col, dot, p, style = Stroke(1.5.dp.toPx()))
            val layout = measurer.measure("${constellationPrefix(s.constellation)}${s.svid}", svStyle)
            drawText(layout, topLeft = Offset(p.x + dot + 1f, p.y - layout.size.height / 2f))
        }
    }
}
