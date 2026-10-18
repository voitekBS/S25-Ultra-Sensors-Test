package dev.sensorlab.app.feature.device

import android.app.ActivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.InputDevice
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.sensorlab.app.core.findActivity
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.formatBytes
import dev.sensorlab.app.ui.components.KeyValue
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.Pill
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors
import java.io.File
import kotlin.math.sqrt

private val FEATURES = listOf(
    "Accelerometer" to "android.hardware.sensor.accelerometer",
    "Gyroscope" to "android.hardware.sensor.gyroscope",
    "Compass" to "android.hardware.sensor.compass",
    "Barometer" to "android.hardware.sensor.barometer",
    "Light sensor" to "android.hardware.sensor.light",
    "Proximity" to "android.hardware.sensor.proximity",
    "Step counter" to "android.hardware.sensor.stepcounter",
    "Step detector" to "android.hardware.sensor.stepdetector",
    "Hi-fi sensors" to "android.hardware.sensor.hifi_sensors",
    "Humidity" to "android.hardware.sensor.relative_humidity",
    "Thermometer" to "android.hardware.sensor.ambient_temperature",
    "Heart rate" to "android.hardware.sensor.heartrate",
    "GPS" to "android.hardware.location.gps",
    "NFC" to "android.hardware.nfc",
    "NFC card emulation" to "android.hardware.nfc.hce",
    "Ultra-wideband" to "android.hardware.uwb",
    "Bluetooth LE" to "android.hardware.bluetooth_le",
    "Wi-Fi Aware" to "android.hardware.wifi.aware",
    "Wi-Fi RTT (indoor ranging)" to "android.hardware.wifi.rtt",
    "Wi-Fi Direct" to "android.hardware.wifi.direct",
    "Camera flash" to "android.hardware.camera.flash",
    "Fingerprint" to "android.hardware.fingerprint",
    "Face unlock (biometric)" to "android.hardware.biometrics.face",
    "5-finger multitouch" to "android.hardware.touchscreen.multitouch.jazzhand",
    "Pro audio" to "android.hardware.audio.pro",
    "Low-latency audio" to "android.hardware.audio.low_latency",
    "StrongBox keystore" to "android.hardware.strongbox_keystore",
    "Telephony" to "android.hardware.telephony",
    "eSIM" to "android.hardware.telephony.euicc",
    "Vulkan" to "android.hardware.vulkan.level",
)

private fun readCpuMaxFreqs(cores: Int): List<Long> = (0 until cores).mapNotNull { i ->
    runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
}

@Composable
fun DeviceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current

    ToolScaffold(title = "Device & biometrics", subtitle = "${Build.MANUFACTURER} ${Build.MODEL}", onBack = onBack) {
        Panel(title = "Device") {
            KeyValue("Model", "${Build.MODEL} (${Build.DEVICE})")
            KeyValue("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            KeyValue("Security patch", Build.VERSION.SECURITY_PATCH)
            KeyValue("Build", Build.DISPLAY)
            KeyValue("Hardware", "${Build.HARDWARE} / ${Build.BOARD}")
        }

        val cores = remember { Runtime.getRuntime().availableProcessors() }
        val freqs = remember { readCpuMaxFreqs(cores) }
        val memory = remember {
            val am = context.getSystemService(ActivityManager::class.java)
            ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        }
        val storage = remember { runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() }
        Panel(title = "SoC & memory") {
            KeyValue("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            KeyValue("CPU cores", cores.toString())
            if (freqs.isNotEmpty()) {
                val clusters = freqs.groupingBy { it }.eachCount().toSortedMap(compareByDescending { it })
                KeyValue("Clusters", clusters.entries.joinToString("  ") { (f, n) -> "${n}× ${(f / 1_000_000f).fmt(2)} GHz" })
            }
            KeyValue("RAM", formatBytes(memory.totalMem))
            KeyValue("RAM available", formatBytes(memory.availMem))
            storage?.let {
                KeyValue("Storage", "${formatBytes(it.availableBytes)} free of ${formatBytes(it.totalBytes)}")
            }
        }

        DisplayPanel()

        Panel(title = "Biometric sensors") {
            BiometricTest()
        }

        val inputs = remember {
            InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { !it.isVirtual }
        }
        Panel(title = "Input devices (${inputs.size})") {
            inputs.forEach { d ->
                val kinds = buildList {
                    if (d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) add("touch")
                    if (d.supportsSource(InputDevice.SOURCE_STYLUS)) add("stylus")
                    if (d.supportsSource(InputDevice.SOURCE_KEYBOARD)) add("keys")
                    if (d.supportsSource(InputDevice.SOURCE_MOUSE)) add("mouse")
                    if (d.supportsSource(InputDevice.SOURCE_ROTARY_ENCODER)) add("rotary")
                }
                KeyValue(d.name, kinds.joinToString(", ").ifEmpty { "other" })
            }
        }

        val pm = context.packageManager
        Panel(title = "Hardware features") {
            FEATURES.forEach { (label, feature) ->
                val has = remember(feature) { pm.hasSystemFeature(feature) }
                KeyValue(label, if (has) "✓ Yes" else "— No", if (has) ic.good else Color.Unspecified)
            }
        }
    }
}

@Composable
private fun DisplayPanel() {
    val context = LocalContext.current
    val display = remember { runCatching { context.display }.getOrNull() }
    val bounds = remember { context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds }
    val metrics = context.resources.displayMetrics
    Panel(title = "Display") {
        if (bounds != null) {
            val w = bounds.width()
            val h = bounds.height()
            val inches = sqrt((w / metrics.xdpi) * (w / metrics.xdpi) + (h / metrics.ydpi) * (h / metrics.ydpi))
            KeyValue("Resolution (current)", "$w × $h px")
            KeyValue("Diagonal", "${inches.fmt(2)} in")
        }
        KeyValue("Density", "${metrics.densityDpi} dpi (×${metrics.density.fmt(2)})")
        if (display != null) {
            KeyValue("Refresh rate (now)", "${display.refreshRate.fmt(0)} Hz")
            val modes = display.supportedModes
                .map { "${it.physicalWidth}×${it.physicalHeight}" to it.refreshRate }
                .groupBy({ it.first }, { it.second })
            modes.forEach { (res, rates) ->
                KeyValue("Mode $res", rates.distinct().sorted().joinToString(" / ") { "${it.fmt(0)} Hz" })
            }
            KeyValue("HDR", if (display.isHdr) "Yes" else "No")
            KeyValue("Wide colour gamut", if (display.isWideColorGamut) "Yes" else "No")
        }
        Note("One UI runs at FHD+ by default; switch to QHD+ in Settings › Display › Screen resolution to use the panel's full 1440 × 3120.")
    }
}

@Composable
private fun BiometricTest() {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val manager = remember { BiometricManager.from(context) }
    val strong = remember { manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) }
    val weak = remember { manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun statusText(code: Int) = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> "Ready"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Nothing enrolled"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No hardware"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Unavailable"
        else -> "Not supported"
    }

    KeyValue("Strong (fingerprint)", statusText(strong), if (strong == BiometricManager.BIOMETRIC_SUCCESS) ic.good else Color.Unspecified)
    KeyValue("Weak (face)", statusText(weak), if (weak == BiometricManager.BIOMETRIC_SUCCESS) ic.good else Color.Unspecified)
    Button(
        onClick = {
            val activity = context.findActivity() as? FragmentActivity ?: return@Button
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                        result = "Recognised ✓" to true
                    }

                    override fun onAuthenticationFailed() {
                        result = "Not recognised — try again" to false
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        result = errString.toString() to false
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Fingerprint sensor test")
                    .setSubtitle("Touch the ultrasonic sensor under the display")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build(),
            )
        },
        enabled = strong == BiometricManager.BIOMETRIC_SUCCESS,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Test fingerprint sensor") }
    result?.let { (msg, ok) ->
        Pill(msg, if (ok) ic.good else ic.warn)
    }
    Text(
        "The S25 Ultra uses Qualcomm's 3D Sonic ultrasonic fingerprint sensor. Apps only ever get a yes/no answer — never the fingerprint itself.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
