package dev.sensorlab.app.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------- formatting

fun Float.fmt(decimals: Int): String =
    if (isNaN() || isInfinite()) "—" else String.format(Locale.US, "%.${decimals}f", this)

fun Double.fmt(decimals: Int): String =
    if (isNaN() || isInfinite()) "—" else String.format(Locale.US, "%.${decimals}f", this)

fun Float.signed(decimals: Int): String =
    if (isNaN() || isInfinite()) "—" else String.format(Locale.US, "%+.${decimals}f", this)

fun Int.grouped(): String = String.format(Locale.US, "%,d", this)
fun Long.grouped(): String = String.format(Locale.US, "%,d", this)

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024.0; i++
    }
    return "${v.fmt(1)} ${units[i]}"
}

// ---------------------------------------------------------------- angles

fun toDeg(rad: Float): Float = Math.toDegrees(rad.toDouble()).toFloat()
fun toDeg(rad: Double): Float = Math.toDegrees(rad).toFloat()

fun normalizeDeg(a: Float): Float {
    var x = a % 360f
    if (x < 0f) x += 360f
    return x
}

/** Signed smallest difference a - b in degrees, in (-180, 180]. */
fun angleDiff(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

private val CARDINALS = listOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
)

fun cardinal(deg: Float): String = CARDINALS[((normalizeDeg(deg) + 11.25f) / 22.5f).toInt() % 16]

/** Low-pass filter for headings that wraps correctly around 0/360°. */
class AngleSmoother(private val alpha: Float) {
    private var s = 0.0
    private var c = 0.0
    private var initialised = false

    fun add(deg: Float): Float {
        val r = Math.toRadians(deg.toDouble())
        if (!initialised) {
            s = sin(r); c = cos(r); initialised = true
        } else {
            s += alpha * (sin(r) - s)
            c += alpha * (cos(r) - c)
        }
        return normalizeDeg(toDeg(atan2(s, c)))
    }
}

/** Exponential low-pass over a fixed-size vector. */
class LowPass(size: Int, private val alpha: Float) {
    val value = FloatArray(size)
    var initialised = false
        private set

    fun add(v: FloatArray): FloatArray {
        if (!initialised) {
            for (i in value.indices) value[i] = v.getOrElse(i) { 0f }
            initialised = true
        } else {
            for (i in value.indices) value[i] += alpha * (v.getOrElse(i) { 0f } - value[i])
        }
        return value
    }

    fun reset() {
        initialised = false
    }
}

fun approxEqual(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) < eps

// ---------------------------------------------------------------- context helpers

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

val Context.prefs: SharedPreferences
    get() = applicationContext.getSharedPreferences("sensorlab", Context.MODE_PRIVATE)

// ---------------------------------------------------------------- persisted state

private class PrefState<T>(
    private val sp: SharedPreferences,
    private val key: String,
    default: T,
    read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) : MutableState<T> {
    private val state = mutableStateOf(sp.read(key, default))

    override var value: T
        get() = state.value
        set(v) {
            state.value = v
            val editor = sp.edit()
            write(editor, key, v)
            editor.apply()
        }

    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

@Composable
fun rememberPrefFloat(key: String, default: Float): MutableState<Float> {
    val context = LocalContext.current
    return remember(key) {
        PrefState(context.prefs, key, default, { k, d -> getFloat(k, d) }, { k, v -> putFloat(k, v) })
    }
}

@Composable
fun rememberPrefBool(key: String, default: Boolean): MutableState<Boolean> {
    val context = LocalContext.current
    return remember(key) {
        PrefState(context.prefs, key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })
    }
}

@Composable
fun rememberPrefLong(key: String, default: Long): MutableState<Long> {
    val context = LocalContext.current
    return remember(key) {
        PrefState(context.prefs, key, default, { k, d -> getLong(k, d) }, { k, v -> putLong(k, v) })
    }
}

// ---------------------------------------------------------------- periodic work

/** Runs [block] every [intervalMs] while the screen is resumed. */
@Composable
fun PeriodicEffect(intervalMs: Long, vararg keys: Any?, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    LaunchedEffect(owner, intervalMs, *keys) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                current()
                delay(intervalMs)
            }
        }
    }
}
