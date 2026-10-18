package dev.sensorlab.app.core

import android.hardware.Sensor

enum class SensorGroup(val title: String) {
    Motion("Motion"),
    Position("Position"),
    Environment("Environment"),
    Body("Body & activity"),
    Other("Other"),
    Vendor("Samsung / vendor"),
}

/** Human-readable metadata for Android sensor types (raw ints so it compiles regardless of API level). */
object SensorCatalog {

    const val VENDOR_BASE = 0x10000

    fun name(type: Int): String = when (type) {
        1 -> "Accelerometer"
        2 -> "Magnetic field"
        3 -> "Orientation (legacy)"
        4 -> "Gyroscope"
        5 -> "Light"
        6 -> "Pressure"
        7 -> "Temperature (legacy)"
        8 -> "Proximity"
        9 -> "Gravity"
        10 -> "Linear acceleration"
        11 -> "Rotation vector"
        12 -> "Relative humidity"
        13 -> "Ambient temperature"
        14 -> "Magnetic field (uncalibrated)"
        15 -> "Game rotation vector"
        16 -> "Gyroscope (uncalibrated)"
        17 -> "Significant motion"
        18 -> "Step detector"
        19 -> "Step counter"
        20 -> "Geomagnetic rotation vector"
        21 -> "Heart rate"
        22 -> "Tilt detector"
        23 -> "Wake gesture"
        24 -> "Glance gesture"
        25 -> "Pick-up gesture"
        26 -> "Wrist tilt gesture"
        27 -> "Device orientation"
        28 -> "Pose 6DOF"
        29 -> "Stationary detect"
        30 -> "Motion detect"
        31 -> "Heart beat"
        32 -> "Dynamic sensor meta"
        33 -> "Additional info"
        34 -> "Low-latency off-body detect"
        35 -> "Accelerometer (uncalibrated)"
        36 -> "Hinge angle"
        37 -> "Head tracker"
        38 -> "Accelerometer (limited axes)"
        39 -> "Gyroscope (limited axes)"
        40 -> "Accelerometer (limited axes, uncal.)"
        41 -> "Gyroscope (limited axes, uncal.)"
        42 -> "Heading"
        else -> if (type >= VENDOR_BASE) "Vendor sensor" else "Type $type"
    }

    fun group(type: Int): SensorGroup = when (type) {
        1, 4, 9, 10, 11, 16, 17, 35, 38, 39, 40, 41, 29, 30 -> SensorGroup.Motion
        2, 3, 8, 14, 15, 20, 22, 25, 27, 28, 36, 37, 42 -> SensorGroup.Position
        5, 6, 7, 12, 13 -> SensorGroup.Environment
        18, 19, 21, 31, 34, 23, 24, 26 -> SensorGroup.Body
        else -> if (type >= VENDOR_BASE) SensorGroup.Vendor else SensorGroup.Other
    }

    /** Labels for event.values, with units. */
    fun axes(type: Int, count: Int): List<String> {
        val base: List<String> = when (type) {
            1, 9, 10 -> listOf("X m/s²", "Y m/s²", "Z m/s²")
            35 -> listOf("X m/s²", "Y m/s²", "Z m/s²", "bias X", "bias Y", "bias Z")
            4 -> listOf("X rad/s", "Y rad/s", "Z rad/s")
            16 -> listOf("X rad/s", "Y rad/s", "Z rad/s", "drift X", "drift Y", "drift Z")
            2 -> listOf("X µT", "Y µT", "Z µT")
            14 -> listOf("X µT", "Y µT", "Z µT", "bias X", "bias Y", "bias Z")
            11 -> listOf("x·sin(θ/2)", "y·sin(θ/2)", "z·sin(θ/2)", "cos(θ/2)", "heading acc. rad")
            15, 20 -> listOf("x·sin(θ/2)", "y·sin(θ/2)", "z·sin(θ/2)", "cos(θ/2)")
            3 -> listOf("azimuth °", "pitch °", "roll °")
            5 -> listOf("lux")
            6 -> listOf("hPa")
            7, 13 -> listOf("°C")
            8 -> listOf("cm")
            12 -> listOf("% RH")
            18 -> listOf("step")
            19 -> listOf("steps since boot")
            21 -> listOf("bpm")
            36 -> listOf("angle °")
            42 -> listOf("heading °", "accuracy °")
            38, 40 -> listOf("X m/s²", "Y m/s²", "Z m/s²", "X supp.", "Y supp.", "Z supp.")
            39, 41 -> listOf("X rad/s", "Y rad/s", "Z rad/s", "X supp.", "Y supp.", "Z supp.")
            else -> emptyList()
        }
        return List(count) { i -> base.getOrElse(i) { "v$i" } }
    }

    fun reportingMode(sensor: Sensor): String = when (sensor.reportingMode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "Continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "On change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "One shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "Special trigger"
        else -> "Unknown"
    }

    fun accuracyLabel(accuracy: Int): String = when (accuracy) {
        3 -> "High"
        2 -> "Medium"
        1 -> "Low"
        0 -> "Unreliable"
        -1 -> "No contact"
        else -> "—"
    }
}
