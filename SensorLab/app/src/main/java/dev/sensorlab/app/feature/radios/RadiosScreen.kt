package dev.sensorlab.app.feature.radios

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.sensorlab.app.core.PeriodicEffect
import dev.sensorlab.app.core.findActivity
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.KeyValue
import dev.sensorlab.app.ui.components.MeterBar
import dev.sensorlab.app.ui.components.MonoSmall
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.PermissionGate
import dev.sensorlab.app.ui.components.Pill
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class RadioTab(val label: String) { Wifi("Wi-Fi"), Bluetooth("Bluetooth"), Cellular("Cellular"), Nfc("NFC"), Uwb("UWB") }

@Composable
fun RadiosScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(RadioTab.Wifi) }
    ToolScaffold(title = "Radios", subtitle = "Wireless receivers as sensors", onBack = onBack) {
        ChoiceRow(RadioTab.entries.toList(), tab, { tab = it }, label = { it.label })
        when (tab) {
            RadioTab.Wifi -> WifiPanel()
            RadioTab.Bluetooth -> BluetoothPanel()
            RadioTab.Cellular -> CellularPanel()
            RadioTab.Nfc -> NfcPanel()
            RadioTab.Uwb -> UwbPanel()
        }
    }
}

/** Maps an RSSI in dBm to 0..1 for a signal bar (-100 dBm → 0, -40 dBm → 1). */
private fun rssiFraction(dbm: Int) = ((dbm + 100) / 60f).coerceIn(0f, 1f)

@Composable
private fun rssiColor(dbm: Int): Color {
    val ic = LocalInstrumentColors.current
    return when {
        dbm >= -60 -> ic.good
        dbm >= -75 -> ic.warn
        else -> ic.bad
    }
}

@Composable
private fun SignalRow(title: String, subtitle: String, dbm: Int) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
            Text("$dbm dBm", style = MonoSmall)
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        MeterBar(fraction = rssiFraction(dbm), color = rssiColor(dbm), thickness = 6.dp)
    }
}

// ------------------------------------------------------------------ Wi-Fi

private fun wifiBand(freq: Int) = when (freq) {
    in 2400..2500 -> "2.4 GHz"
    in 4900..5900 -> "5 GHz"
    in 5925..7125 -> "6 GHz"
    else -> "$freq MHz"
}

private fun wifiChannel(freq: Int) = when (freq) {
    2484 -> 14
    in 2400..2500 -> (freq - 2407) / 5
    in 4900..5900 -> (freq - 5000) / 5
    in 5925..7125 -> (freq - 5950) / 5
    else -> 0
}

private fun wifiStandard(std: Int) = when (std) {
    1 -> "Legacy"
    4 -> "Wi-Fi 4"
    5 -> "Wi-Fi 5"
    6 -> "Wi-Fi 6/6E"
    7 -> "WiGig"
    8 -> "Wi-Fi 7"
    else -> "—"
}

private fun channelWidth(w: Int) = when (w) {
    0 -> "20 MHz"
    1 -> "40 MHz"
    2 -> "80 MHz"
    3 -> "160 MHz"
    4 -> "80+80 MHz"
    5 -> "320 MHz"
    else -> "20 MHz"
}

private fun security(caps: String) = when {
    caps.contains("SAE") || caps.contains("WPA3") -> "WPA3"
    caps.contains("RSN") || caps.contains("WPA2") -> "WPA2"
    caps.contains("WPA") -> "WPA"
    caps.contains("WEP") -> "WEP"
    caps.contains("OWE") -> "Enhanced open"
    else -> "Open"
}

private class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val width: Int,
    val standard: Int,
    val caps: String,
)

@SuppressLint("MissingPermission")
@Composable
private fun ColumnScope.WifiPanel() {
    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    PermissionGate(perms, "Android only reveals nearby Wi-Fi networks to apps with location and nearby-devices access.") {
        val context = LocalContext.current
        val wm = remember { context.applicationContext.getSystemService(WifiManager::class.java) }
        var networks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
        var lastScan by remember { mutableLongStateOf(0L) }
        var throttled by remember { mutableStateOf(false) }

        fun readResults() {
            val list = runCatching { wm?.scanResults.orEmpty() }.getOrDefault(emptyList())
            networks = list.map { r ->
                @Suppress("DEPRECATION")
                WifiNetwork(
                    ssid = (r.SSID ?: "").ifBlank { "(hidden)" },
                    bssid = r.BSSID ?: "",
                    level = r.level,
                    frequency = r.frequency,
                    width = r.channelWidth,
                    standard = r.wifiStandard,
                    caps = r.capabilities ?: "",
                )
            }.sortedByDescending { it.level }
            lastScan = System.currentTimeMillis()
        }

        DisposableEffect(wm) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = readResults()
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED,
            )
            readResults()
            onDispose { context.unregisterReceiver(receiver) }
        }
        PeriodicEffect(30_000L) {
            @Suppress("DEPRECATION")
            throttled = wm?.startScan() == false
        }

        @Suppress("DEPRECATION")
        val info = remember(lastScan) { runCatching { wm?.connectionInfo }.getOrNull() }
        Panel(title = "Connected") {
            if (info == null || info.networkId == -1) {
                Text("Not connected to Wi-Fi", style = MaterialTheme.typography.bodyMedium)
            } else {
                @Suppress("DEPRECATION")
                SignalRow(info.ssid.trim('"'), "${wifiBand(info.frequency)} · ch ${wifiChannel(info.frequency)} · ${wifiStandard(info.wifiStandard)}", info.rssi)
                KeyValue("Link speed", "${info.txLinkSpeedMbps} ↑ / ${info.rxLinkSpeedMbps} ↓ Mbps")
                KeyValue("Frequency", "${info.frequency} MHz")
            }
        }
        Panel(
            title = "Nearby networks (${networks.size})",
            trailing = {
                OutlinedButton(onClick = {
                    @Suppress("DEPRECATION")
                    throttled = wm?.startScan() == false
                }) { Text("Scan") }
            },
        ) {
            val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
            Text(
                if (lastScan == 0L) "Scanning…" else "Last results ${fmt.format(Date(lastScan))}" + if (throttled) " · scan throttled by Android" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            networks.take(40).forEach { n ->
                SignalRow(
                    n.ssid,
                    "${wifiBand(n.frequency)} ch ${wifiChannel(n.frequency)} · ${channelWidth(n.width)} · ${wifiStandard(n.standard)} · ${security(n.caps)}",
                    n.level,
                )
            }
            Note("Android limits apps to four Wi-Fi scans every two minutes. Developer options › Wi-Fi scan throttling can lift this.")
        }
    }
}

// ------------------------------------------------------------------ Bluetooth LE

private class BleDevice(val address: String, val name: String?, val rssi: Int, val txPower: Int?, val connectable: Boolean, val vendor: String?, val seen: Long)

private fun vendorName(companyId: Int): String? = when (companyId) {
    0x004C -> "Apple"
    0x0075 -> "Samsung"
    0x0006 -> "Microsoft"
    0x00E0 -> "Google"
    0x0087 -> "Garmin"
    0x0157 -> "Huami"
    0x02E5 -> "Espressif"
    else -> null
}

@SuppressLint("MissingPermission")
@Composable
private fun ColumnScope.BluetoothPanel() {
    PermissionGate(listOf(Manifest.permission.BLUETOOTH_SCAN), "Scanning for Bluetooth Low Energy devices needs the Nearby devices permission.") {
        val context = LocalContext.current
        val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
        if (adapter == null) {
            Panel { Text("This phone has no Bluetooth adapter.") }
            return@PermissionGate
        }
        var enabled by remember { mutableStateOf(adapter.isEnabled) }
        PeriodicEffect(2000L) { enabled = adapter.isEnabled }
        if (!enabled) {
            Panel(title = "Bluetooth is off") {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open Bluetooth settings") }
            }
            return@PermissionGate
        }
        val devices = remember { mutableStateMapOf<String, BleDevice>() }
        LifecycleResumeEffect(adapter) {
            val scanner = adapter.bluetoothLeScanner
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val record = result.scanRecord
                    val mfg = record?.manufacturerSpecificData
                    val vendor = if (mfg != null && mfg.size() > 0) vendorName(mfg.keyAt(0)) else null
                    devices[result.device.address] = BleDevice(
                        address = result.device.address,
                        name = record?.deviceName,
                        rssi = result.rssi,
                        txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT },
                        connectable = result.isConnectable,
                        vendor = vendor,
                        seen = System.currentTimeMillis(),
                    )
                }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            runCatching { scanner?.startScan(null, settings, callback) }
            onPauseOrDispose { runCatching { scanner?.stopScan(callback) } }
        }
        PeriodicEffect(5000L) {
            val cutoff = System.currentTimeMillis() - 30_000L
            devices.keys.filter { (devices[it]?.seen ?: 0L) < cutoff }.forEach { devices.remove(it) }
        }
        Panel(title = "Nearby BLE devices (${devices.size})") {
            if (devices.isEmpty()) Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
            devices.values.sortedByDescending { it.rssi }.take(50).forEach { d ->
                SignalRow(
                    d.name ?: d.vendor?.let { "$it device" } ?: "Unnamed device",
                    listOfNotNull(
                        d.address,
                        d.vendor,
                        d.txPower?.let { "tx $it dBm" },
                        if (d.connectable) "connectable" else "beacon",
                    ).joinToString(" · "),
                    d.rssi,
                )
            }
            Note("Addresses of most phones and earbuds are randomised for privacy, so the same device can appear more than once.")
        }
    }
}

// ------------------------------------------------------------------ Cellular

private class CellReading(val tech: String, val dbm: Int, val level: Int, val details: List<Pair<String, String>>)

private fun v(x: Int, unit: String) = if (x == Int.MAX_VALUE || x == Int.MIN_VALUE) "—" else "$x $unit"

private fun describe(s: CellSignalStrength): CellReading = when (s) {
    is CellSignalStrengthNr -> CellReading(
        "5G NR", s.dbm, s.level,
        listOf("SS-RSRP" to v(s.ssRsrp, "dBm"), "SS-RSRQ" to v(s.ssRsrq, "dB"), "SS-SINR" to v(s.ssSinr, "dB")),
    )
    is CellSignalStrengthLte -> CellReading(
        "LTE", s.dbm, s.level,
        listOf("RSRP" to v(s.rsrp, "dBm"), "RSRQ" to v(s.rsrq, "dB"), "RSSNR" to v(s.rssnr, "dB"), "Timing advance" to v(s.timingAdvance, "")),
    )
    is CellSignalStrengthWcdma -> CellReading("3G WCDMA", s.dbm, s.level, listOf("Ec/No" to v(s.ecNo, "dB")))
    is CellSignalStrengthGsm -> CellReading("2G GSM", s.dbm, s.level, listOf("BER" to v(s.bitErrorRate, "")))
    else -> CellReading(s.javaClass.simpleName.removePrefix("CellSignalStrength"), s.dbm, s.level, emptyList())
}

@Composable
private fun ColumnScope.CellularPanel() {
    val context = LocalContext.current
    val tm = remember { context.getSystemService(TelephonyManager::class.java) }
    var readings by remember { mutableStateOf<List<CellReading>>(emptyList()) }
    var operator by remember { mutableStateOf("") }
    PeriodicEffect(2000L) {
        readings = runCatching { tm?.signalStrength?.cellSignalStrengths.orEmpty().map { describe(it) } }.getOrDefault(emptyList())
        operator = runCatching { tm?.networkOperatorName.orEmpty() }.getOrDefault("")
    }
    Panel(title = "Mobile network") {
        KeyValue("Operator", operator.ifBlank { "—" })
        KeyValue("Active modems", runCatching { tm?.activeModemCount?.toString() }.getOrNull() ?: "—")
        if (readings.isEmpty()) Text("No signal information (no SIM, or airplane mode).", style = MaterialTheme.typography.bodyMedium)
    }
    readings.forEach { r ->
        Panel(title = r.tech, trailing = { Pill("Level ${r.level}/4", rssiColor(r.dbm)) }) {
            SignalRow(r.tech, "Signal strength", r.dbm)
            r.details.forEach { (k, value) -> KeyValue(k, value) }
        }
    }
    Note("5G non-standalone connections show both an LTE anchor and an NR carrier. The S25 Ultra's modem is part of the Snapdragon 8 Elite.")
}

// ------------------------------------------------------------------ NFC

private class TagInfo(val time: Long, val id: String, val techs: String, val ndefType: String?, val records: List<String>, val maxSize: Int?)

private fun ByteArray.hex(sep: String = ":") = joinToString(sep) { "%02X".format(it) }

private fun recordText(r: NdefRecord): String {
    val uri = r.toUri()
    if (uri != null) return "URI  $uri"
    if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) && r.payload.isNotEmpty()) {
        val status = r.payload[0].toInt()
        val langLen = status and 0x3F
        val charset = if ((status and 0x80) != 0) Charsets.UTF_16 else Charsets.UTF_8
        val text = String(r.payload, 1 + langLen, r.payload.size - 1 - langLen, charset)
        return "Text \"$text\""
    }
    if (r.tnf == NdefRecord.TNF_MIME_MEDIA) return "MIME ${String(r.type)} (${r.payload.size} bytes)"
    return "TNF ${r.tnf} type ${r.type.hex("")} (${r.payload.size} bytes)"
}

private fun parseTag(tag: Tag): TagInfo {
    val ndef = Ndef.get(tag)
    val message = ndef?.cachedNdefMessage
    return TagInfo(
        time = System.currentTimeMillis(),
        id = tag.id.hex(),
        techs = tag.techList.joinToString(", ") { it.substringAfterLast('.') },
        ndefType = ndef?.type,
        records = message?.records?.map { recordText(it) }.orEmpty(),
        maxSize = ndef?.maxSize,
    )
}

@Composable
private fun ColumnScope.NfcPanel() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    if (adapter == null) {
        Panel { Text("This phone has no NFC.") }
        return
    }
    var enabled by remember { mutableStateOf(adapter.isEnabled) }
    PeriodicEffect(2000L) { enabled = adapter.isEnabled }
    if (!enabled) {
        Panel(title = "NFC is off") {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open NFC settings") }
        }
        return
    }
    val tags = remember { mutableStateListOf<TagInfo>() }
    val activity = remember { context.findActivity() }
    LifecycleResumeEffect(adapter, activity) {
        val main = Handler(Looper.getMainLooper())
        if (activity != null) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE
            adapter.enableReaderMode(activity, { tag ->
                val info = runCatching { parseTag(tag) }.getOrNull()
                main.post {
                    if (info != null) {
                        tags.add(0, info)
                        if (tags.size > 10) tags.removeAt(tags.lastIndex)
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }, flags, null)
        }
        onPauseOrDispose { if (activity != null) runCatching { adapter.disableReaderMode(activity) } }
    }
    Panel(title = "Tag reader") {
        Text(
            if (tags.isEmpty()) "Hold an NFC tag, card or phone against the middle of the back of the phone." else "${tags.size} tag(s) read",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    tags.forEach { t ->
        Panel(title = "Tag · ${fmt.format(Date(t.time))}") {
            KeyValue("UID", t.id)
            KeyValue("Technologies", t.techs)
            t.ndefType?.let { KeyValue("NDEF type", it.substringAfterLast('.')) }
            t.maxSize?.let { KeyValue("NDEF capacity", "$it bytes") }
            t.records.forEach { r ->
                Row {
                    Spacer(Modifier.width(4.dp))
                    Text(r, style = MonoSmall)
                }
            }
        }
    }
    Note("Payment cards only reveal a random ID and supported technologies — no card data is read.")
}

// ------------------------------------------------------------------ UWB

@Composable
private fun ColumnScope.UwbPanel() {
    val context = LocalContext.current
    val hasUwb = remember { context.packageManager.hasSystemFeature("android.hardware.uwb") }
    Panel(title = "Ultra-wideband") {
        KeyValue("UWB radio", if (hasUwb) "Present" else "Not present", if (hasUwb) LocalInstrumentColors.current.good else Color.Unspecified)
        Text(
            if (hasUwb) {
                "The S25 Ultra's UWB radio measures distance and angle to another UWB device with centimetre accuracy " +
                    "(it powers SmartTag2 precision finding and digital car keys). Ranging needs a second UWB device and the " +
                    "androidx.core.uwb ranging API, so there is nothing to measure on its own."
            } else {
                "This phone doesn't report an ultra-wideband radio."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
