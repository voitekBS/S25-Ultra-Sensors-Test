package dev.sensorlab.app.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

val Context.sensorManager: SensorManager
    get() = getSystemService(Context.SENSOR_SERVICE) as SensorManager

/** The default sensor of [type], or null when the phone doesn't have one. */
@Composable
fun rememberSensor(type: Int): Sensor? {
    val context = LocalContext.current
    return remember(type) { context.sensorManager.getDefaultSensor(type) }
}

/**
 * Registers a listener for [sensor] while the screen is resumed and unregisters it on pause or
 * when it leaves composition. Events arrive on the main thread.
 */
@Composable
fun SensorEffect(
    sensor: Sensor?,
    rate: Int = SensorManager.SENSOR_DELAY_GAME,
    onAccuracy: (Int) -> Unit = {},
    onEvent: (SensorEvent) -> Unit,
) {
    val context = LocalContext.current
    val eventCallback = rememberUpdatedState(onEvent)
    val accuracyCallback = rememberUpdatedState(onAccuracy)
    if (sensor == null) return
    LifecycleResumeEffect(sensor, rate) {
        val sm = context.sensorManager
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                eventCallback.value(event)
            }

            override fun onAccuracyChanged(s: Sensor, accuracy: Int) {
                accuracyCallback.value(accuracy)
            }
        }
        sm.registerListener(listener, sensor, rate)
        onPauseOrDispose { sm.unregisterListener(listener) }
    }
}

/** One-shot sensors (significant motion, some vendor gestures) need a trigger listener that is re-armed. */
@Composable
fun TriggerSensorEffect(sensor: Sensor?, onTrigger: (TriggerEvent) -> Unit) {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onTrigger)
    if (sensor == null) return
    LifecycleResumeEffect(sensor) {
        val sm = context.sensorManager
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent) {
                callback.value(event)
                sm.requestTriggerSensor(this, sensor)
            }
        }
        sm.requestTriggerSensor(listener, sensor)
        onPauseOrDispose { sm.cancelTriggerSensor(listener, sensor) }
    }
}

class SensorSample(val values: FloatArray, val accuracy: Int, val timestampNs: Long)

/** Latest copy of the sensor's values (null until the first event). */
@Composable
fun rememberSensorSample(type: Int, rate: Int = SensorManager.SENSOR_DELAY_UI): SensorSample? {
    val sensor = rememberSensor(type)
    var sample by remember(type) { mutableStateOf<SensorSample?>(null) }
    SensorEffect(sensor, rate) { e -> sample = SensorSample(e.values.copyOf(), e.accuracy, e.timestamp) }
    return sample
}

/** Measures the actual delivery rate of events in Hz, updated once per second. */
class RateMeter {
    private var count = 0
    private var windowStart = 0L
    var hz by mutableFloatStateOf(0f)
        private set

    fun tick(nowNs: Long = System.nanoTime()) {
        if (windowStart == 0L) windowStart = nowNs
        count++
        val dt = nowNs - windowStart
        if (dt >= 1_000_000_000L) {
            hz = count * 1e9f / dt
            count = 0
            windowStart = nowNs
        }
    }

    fun reset() {
        count = 0; windowStart = 0L; hz = 0f
    }
}

// ---------------------------------------------------------------- ring buffers for charts

class RingBuffer(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = 0
    var size = 0
        private set

    fun add(v: Float) {
        data[head] = v
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** 0 = oldest sample, size-1 = newest. */
    operator fun get(i: Int): Float = data[(head - size + i + 2 * capacity) % capacity]

    fun last(): Float = if (size == 0) Float.NaN else get(size - 1)

    fun clear() {
        head = 0; size = 0
    }

    fun toArray(): FloatArray = FloatArray(size) { get(it) }
}

/** Several aligned ring buffers plus a version counter that redraws charts reading it. */
class SeriesBuffer(val seriesCount: Int, val capacity: Int) {
    val series = Array(seriesCount) { RingBuffer(capacity) }
    var version by mutableLongStateOf(0L)
        private set

    val size: Int get() = series[0].size

    fun push(vararg values: Float) {
        for (i in 0 until seriesCount) series[i].add(if (i < values.size) values[i] else Float.NaN)
        version++
    }

    fun pushArray(values: FloatArray) {
        for (i in 0 until seriesCount) series[i].add(if (i < values.size) values[i] else Float.NaN)
        version++
    }

    fun clear() {
        series.forEach { it.clear() }
        version++
    }
}
