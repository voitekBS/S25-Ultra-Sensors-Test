package dev.sensorlab.app.home

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.os.VibratorManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.sensorlab.app.core.sensorManager
import dev.sensorlab.app.feature.attitude.AttitudeScreen
import dev.sensorlab.app.feature.barometer.BarometerScreen
import dev.sensorlab.app.feature.battery.BatteryScreen
import dev.sensorlab.app.feature.compass.CompassScreen
import dev.sensorlab.app.feature.device.DeviceScreen
import dev.sensorlab.app.feature.explorer.ExplorerScreen
import dev.sensorlab.app.feature.gnss.GnssScreen
import dev.sensorlab.app.feature.haptics.HapticsScreen
import dev.sensorlab.app.feature.heart.HeartRateScreen
import dev.sensorlab.app.feature.level.LevelScreen
import dev.sensorlab.app.feature.light.LightScreen
import dev.sensorlab.app.feature.magnet.MagnetometerScreen
import dev.sensorlab.app.feature.motion.MotionScreen
import dev.sensorlab.app.feature.pedometer.PedometerScreen
import dev.sensorlab.app.feature.proximity.ProximityScreen
import dev.sensorlab.app.feature.radios.RadiosScreen
import dev.sensorlab.app.feature.seismo.SeismometerScreen
import dev.sensorlab.app.feature.sound.SoundScreen
import dev.sensorlab.app.feature.touch.TouchScreen

enum class ToolCategory(val title: String) {
    Orientation("Orientation & navigation"),
    Motion("Motion & vibration"),
    Environment("Environment"),
    Human("Camera, audio & touch"),
    System("Connectivity & system"),
}

class Tool(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val category: ToolCategory,
    val isAvailable: (Context) -> Boolean = { true },
    val screen: @Composable (onBack: () -> Unit, open: (String) -> Unit) -> Unit,
)

private fun Context.has(vararg types: Int): Boolean = types.any { sensorManager.getDefaultSensor(it) != null }
private fun Context.feature(name: String): Boolean = packageManager.hasSystemFeature(name)

object Tools {
    val all: List<Tool> = listOf(
        Tool(
            "compass", "Compass", "True & magnetic heading, field strength",
            Icons.Filled.Explore, ToolCategory.Orientation,
            isAvailable = { it.has(Sensor.TYPE_MAGNETIC_FIELD) },
        ) { back, _ -> CompassScreen(back) },
        Tool(
            "level", "Spirit level", "Bubble, edge level & inclinometer",
            Icons.Filled.Straighten, ToolCategory.Orientation,
            isAvailable = { it.has(Sensor.TYPE_ACCELEROMETER) },
        ) { back, _ -> LevelScreen(back) },
        Tool(
            "attitude", "3D attitude", "Rotation vectors, quaternion, live model",
            Icons.Filled.ScreenRotation, ToolCategory.Orientation,
            isAvailable = { it.has(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR) },
        ) { back, _ -> AttitudeScreen(back) },
        Tool(
            "gnss", "GNSS & speed", "Satellites, sky plot, dual-band, speedometer",
            Icons.Filled.GpsFixed, ToolCategory.Orientation,
            isAvailable = { it.feature(PackageManager.FEATURE_LOCATION_GPS) },
        ) { back, _ -> GnssScreen(back) },

        Tool(
            "motion", "Motion lab", "Accelerometer, gyroscope, gravity, G-meter",
            Icons.Filled.ShowChart, ToolCategory.Motion,
            isAvailable = { it.has(Sensor.TYPE_ACCELEROMETER) },
        ) { back, _ -> MotionScreen(back) },
        Tool(
            "seismo", "Seismometer", "Vibration, PGA, intensity, event trigger",
            Icons.Filled.Waves, ToolCategory.Motion,
            isAvailable = { it.has(Sensor.TYPE_ACCELEROMETER) },
        ) { back, _ -> SeismometerScreen(back) },
        Tool(
            "pedometer", "Pedometer", "Step counter, cadence, distance",
            Icons.Filled.DirectionsWalk, ToolCategory.Motion,
            isAvailable = { it.has(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR) },
        ) { back, _ -> PedometerScreen(back) },
        Tool(
            "haptics", "Haptics lab", "Vibration motor, primitives, waveforms",
            Icons.Filled.Vibration, ToolCategory.Motion,
            isAvailable = { ctx ->
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.hasVibrator() == true
            },
        ) { back, _ -> HapticsScreen(back) },

        Tool(
            "barometer", "Barometer", "Pressure, altimeter, floor counter",
            Icons.Filled.Cloud, ToolCategory.Environment,
            isAvailable = { it.has(Sensor.TYPE_PRESSURE) },
        ) { back, _ -> BarometerScreen(back) },
        Tool(
            "light", "Light meter", "Lux, EV and exposure calculator",
            Icons.Filled.WbSunny, ToolCategory.Environment,
            isAvailable = { it.has(Sensor.TYPE_LIGHT) },
        ) { back, _ -> LightScreen(back) },
        Tool(
            "proximity", "Proximity", "Near / far detection and events",
            Icons.Filled.Adjust, ToolCategory.Environment,
            isAvailable = { it.has(Sensor.TYPE_PROXIMITY) },
        ) { back, _ -> ProximityScreen(back) },
        Tool(
            "magnet", "Magnetometer", "Field strength and metal detector",
            Icons.Filled.TrackChanges, ToolCategory.Environment,
            isAvailable = { it.has(Sensor.TYPE_MAGNETIC_FIELD) },
        ) { back, _ -> MagnetometerScreen(back) },

        Tool(
            "sound", "Sound meter", "dB(A), spectrum analyser, tuner",
            Icons.Filled.Mic, ToolCategory.Human,
            isAvailable = { it.feature(PackageManager.FEATURE_MICROPHONE) },
        ) { back, _ -> SoundScreen(back) },
        Tool(
            "heart", "Pulse", "Heart rate via camera and flash",
            Icons.Filled.Favorite, ToolCategory.Human,
            isAvailable = { it.feature(PackageManager.FEATURE_CAMERA_FLASH) },
        ) { back, _ -> HeartRateScreen(back) },
        Tool(
            "touch", "Touch & S Pen", "Multi-touch, pressure, tilt, hover",
            Icons.Filled.Gesture, ToolCategory.Human,
        ) { back, _ -> TouchScreen(back) },

        Tool(
            "radios", "Radios", "Wi-Fi, Bluetooth LE, cellular, NFC, UWB",
            Icons.Filled.Wifi, ToolCategory.System,
        ) { back, _ -> RadiosScreen(back) },
        Tool(
            "battery", "Battery & thermal", "Current, voltage, temperature, headroom",
            Icons.Filled.BatteryFull, ToolCategory.System,
        ) { back, _ -> BatteryScreen(back) },
        Tool(
            "device", "Device & biometrics", "Display, SoC, features, fingerprint test",
            Icons.Filled.PhoneAndroid, ToolCategory.System,
        ) { back, _ -> DeviceScreen(back) },
        Tool(
            "explorer", "Sensor explorer", "Every sensor, raw values, CSV logging",
            Icons.Filled.Memory, ToolCategory.System,
        ) { back, open -> ExplorerScreen(onBack = back, onOpenSensor = { open("sensor:$it") }) },
    )

    fun byId(id: String): Tool? = all.firstOrNull { it.id == id }
}
