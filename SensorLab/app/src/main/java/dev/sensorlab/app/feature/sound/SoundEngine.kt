package dev.sensorlab.app.feature.sound

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One analysed block of audio. Levels are in dB relative to full scale (dBFS). */
class AudioFrame(
    val rmsDbfs: Float,
    val aWeightedDbfs: Float,
    val peakDbfs: Float,
    /** Display spectrum: [SPECTRUM_BANDS] log-spaced bands from 20 Hz to 20 kHz, in dBFS. */
    val spectrum: FloatArray,
    val dominantHz: Float,
    val durationSec: Float,
)

const val SPECTRUM_BANDS = 96
const val SPECTRUM_MIN_HZ = 20f
const val SPECTRUM_MAX_HZ = 20_000f

/**
 * Reads the microphone on a background coroutine and produces level + spectrum frames.
 * Uses the UNPROCESSED source when the device supports it (no AGC / noise suppression).
 */
class SoundEngine(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    val unprocessed: Boolean =
        audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    /** Default dBFS → dB SPL offset from the Android CDD microphone sensitivity targets. */
    val defaultOffset: Float = if (unprocessed) 130f else 112.4f

    val sampleRate = 48_000
    private val n = 4096
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _frames = MutableStateFlow<AudioFrame?>(null)
    val frames: StateFlow<AudioFrame?> = _frames
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val fft = Fft(n)
    private val window = FloatArray(n) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).toFloat() }
    private val aWeight = FloatArray(n / 2) { k -> aWeightingPower(k * sampleRate.toFloat() / n) }
    private val bandEdges = IntArray(SPECTRUM_BANDS + 1) { b ->
        val f = SPECTRUM_MIN_HZ * (SPECTRUM_MAX_HZ / SPECTRUM_MIN_HZ).pow(b / SPECTRUM_BANDS.toFloat())
        (f * n / sampleRate).toInt().coerceIn(1, n / 2 - 1)
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runLoop() {
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, n * 4))
        } catch (t: Throwable) {
            _error.value = "Microphone unavailable: ${t.message}"
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            _error.value = "Microphone could not be opened (is another app recording?)"
            record.release()
            return
        }
        _error.value = null
        val pcm = ShortArray(n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val power = FloatArray(n / 2)
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                var read = 0
                while (read < n) {
                    val r = record.read(pcm, read, n - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < n) continue

                // ---- time domain level
                var sumSq = 0.0
                var peak = 0f
                for (i in 0 until n) {
                    val x = pcm[i] / 32768f
                    sumSq += x * x
                    val a = abs(x)
                    if (a > peak) peak = a
                    re[i] = x * window[i]
                    im[i] = 0f
                }
                val rms = sqrt(sumSq / n).toFloat()

                // ---- spectrum
                fft.transform(re, im)
                var total = 0.0
                var weighted = 0.0
                var bestK = 0
                var bestP = 0f
                val kMin = (30f * n / sampleRate).toInt()
                val kMax = (8000f * n / sampleRate).toInt()
                for (k in 1 until n / 2) {
                    val p = re[k] * re[k] + im[k] * im[k]
                    power[k] = p
                    total += p
                    weighted += p * aWeight[k]
                    if (k in kMin..kMax && p > bestP) {
                        bestP = p; bestK = k
                    }
                }
                val ratio = if (total > 0) weighted / total else 1.0
                val rmsDb = 20f * log10(max(rms, 1e-9f))
                val aDb = rmsDb + 10f * log10(max(ratio, 1e-12).toFloat())
                val peakDb = 20f * log10(max(peak, 1e-9f))

                // Hann-windowed full-scale sine gives |X| = n/4 → normalise so a full-scale tone reads 0 dBFS
                val ref = (n / 4f) * (n / 4f)
                val spectrum = FloatArray(SPECTRUM_BANDS) { b ->
                    var m = 0f
                    val from = bandEdges[b]
                    val to = max(bandEdges[b + 1], from + 1)
                    for (k in from until to) if (power[k] > m) m = power[k]
                    10f * log10(max(m / ref, 1e-14f))
                }

                // Dominant frequency with parabolic interpolation (only when clearly above noise)
                var dominant = 0f
                if (bestK in 2 until n / 2 - 1 && 10f * log10(max(bestP / ref, 1e-14f)) > -75f) {
                    val a = 10f * log10(power[bestK - 1] + 1e-20f)
                    val b = 10f * log10(power[bestK] + 1e-20f)
                    val c = 10f * log10(power[bestK + 1] + 1e-20f)
                    val denom = a - 2f * b + c
                    val delta = if (abs(denom) > 1e-6f) 0.5f * (a - c) / denom else 0f
                    dominant = (bestK + delta) * sampleRate / n
                }

                _frames.value = AudioFrame(rmsDb, aDb, peakDb, spectrum, dominant, n / sampleRate.toFloat())
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    companion object {
        /** IEC 61672 A-weighting as a linear power gain. */
        fun aWeightingPower(f: Float): Float {
            if (f <= 0f) return 0f
            val f2 = f.toDouble() * f
            val num = 12194.0 * 12194.0 * f2 * f2
            val den = (f2 + 20.6 * 20.6) * sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194.0)
            val ra = num / den
            return (ra * ra * 10.0.pow(0.2)).toFloat() // +2.0 dB normalisation at 1 kHz
        }
    }
}

/** In-place iterative radix-2 complex FFT. */
class Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosTable = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }

    init {
        require(1 shl levels == n) { "FFT size must be a power of two" }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        // bit-reversal permutation
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tre = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tim = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
