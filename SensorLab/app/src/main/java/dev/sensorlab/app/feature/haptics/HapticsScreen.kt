package dev.sensorlab.app.feature.haptics

import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.ui.components.KeyValue
import dev.sensorlab.app.ui.components.MissingSensor
import dev.sensorlab.app.ui.components.Note
import dev.sensorlab.app.ui.components.Panel
import dev.sensorlab.app.ui.components.SectionLabel
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors

private data class Primitive(val name: String, val id: Int)

private val PRIMITIVES = listOf(
    Primitive("Click", VibrationEffect.Composition.PRIMITIVE_CLICK),
    Primitive("Tick", VibrationEffect.Composition.PRIMITIVE_TICK),
    Primitive("Low tick", VibrationEffect.Composition.PRIMITIVE_LOW_TICK),
    Primitive("Thud", VibrationEffect.Composition.PRIMITIVE_THUD),
    Primitive("Spin", VibrationEffect.Composition.PRIMITIVE_SPIN),
    Primitive("Quick rise", VibrationEffect.Composition.PRIMITIVE_QUICK_RISE),
    Primitive("Slow rise", VibrationEffect.Composition.PRIMITIVE_SLOW_RISE),
    Primitive("Quick fall", VibrationEffect.Composition.PRIMITIVE_QUICK_FALL),
)

private val EFFECTS = listOf(
    "Click" to VibrationEffect.EFFECT_CLICK,
    "Double click" to VibrationEffect.EFFECT_DOUBLE_CLICK,
    "Tick" to VibrationEffect.EFFECT_TICK,
    "Heavy click" to VibrationEffect.EFFECT_HEAVY_CLICK,
)

private data class Pattern(val name: String, val timings: LongArray, val amplitudes: IntArray)

private val PATTERNS = listOf(
    Pattern("Heartbeat", longArrayOf(0, 70, 110, 90, 700, 70, 110, 90), intArrayOf(0, 255, 0, 150, 0, 255, 0, 150)),
    Pattern(
        "Ramp up",
        LongArray(26) { if (it == 0) 0L else 40L },
        IntArray(26) { if (it == 0) 0 else (it * 10).coerceAtMost(255) },
    ),
    Pattern(
        "SOS",
        longArrayOf(0, 100, 100, 100, 100, 100, 300, 300, 100, 300, 100, 300, 300, 100, 100, 100, 100, 100),
        IntArray(18) { if (it % 2 == 1) 255 else 0 },
    ),
    Pattern("Notification", longArrayOf(0, 40, 60, 40, 60, 120), intArrayOf(0, 180, 0, 180, 0, 255)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HapticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val vm = remember { context.getSystemService(VibratorManager::class.java) }
    val vibrator: Vibrator? = remember { vm?.defaultVibrator }

    ToolScaffold(title = "Haptics lab", subtitle = "Linear resonant actuator", onBack = onBack) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            MissingSensor("vibration motor")
            return@ToolScaffold
        }
        val primitiveSupport = remember { vibrator.arePrimitivesSupported(*PRIMITIVES.map { it.id }.toIntArray()) }
        val effectSupport = remember { vibrator.areEffectsSupported(*EFFECTS.map { it.second }.toIntArray()) }
        var amplitude by remember { mutableFloatStateOf(180f) }
        var duration by remember { mutableFloatStateOf(120f) }
        var primitiveScale by remember { mutableFloatStateOf(1f) }
        val sequence = remember { mutableStateListOf<Primitive>() }
        var holding by remember { mutableStateOf(false) }

        Panel(title = "Actuator") {
            KeyValue("Vibrators", (vm?.vibratorIds?.size ?: 1).toString())
            KeyValue("Amplitude control", if (vibrator.hasAmplitudeControl()) "Yes (1–255)" else "No")
            val f0 = vibrator.resonantFrequency
            KeyValue("Resonant frequency", if (f0.isNaN()) "Not reported" else "${f0.fmt(0)} Hz")
            val q = vibrator.qFactor
            KeyValue("Q factor", if (q.isNaN()) "Not reported" else q.fmt(1))
            KeyValue("Primitives supported", "${primitiveSupport.count { it }} / ${PRIMITIVES.size}")
        }

        Panel(title = "One-shot") {
            SectionLabel("Amplitude ${amplitude.toInt()}")
            Slider(value = amplitude, onValueChange = { amplitude = it }, valueRange = 1f..255f)
            SectionLabel("Duration ${duration.toInt()} ms")
            Slider(value = duration, onValueChange = { duration = it }, valueRange = 10f..1000f)
            Button(
                onClick = { vibrator.vibrate(VibrationEffect.createOneShot(duration.toLong(), amplitude.toInt())) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pulse") }
            val cs = MaterialTheme.colorScheme
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (holding) cs.primary else cs.surfaceContainerHigh)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            holding = true
                            vibrator.vibrate(VibrationEffect.createOneShot(10_000L, amplitude.toInt()))
                            tryAwaitRelease()
                            vibrator.cancel()
                            holding = false
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (holding) "Vibrating…" else "Press and hold",
                    color = if (holding) cs.onPrimary else cs.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Panel(title = "Waveforms") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PATTERNS.forEach { p ->
                    FilledTonalButton(onClick = {
                        vibrator.vibrate(VibrationEffect.createWaveform(p.timings, p.amplitudes, -1))
                    }) { Text(p.name) }
                }
            }
        }

        Panel(title = "Predefined effects") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EFFECTS.forEachIndexed { i, (name, id) ->
                    val support = effectSupport.getOrElse(i) { Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN }
                    FilledTonalButton(onClick = { vibrator.vibrate(VibrationEffect.createPredefined(id)) }) {
                        Text(name + if (support == Vibrator.VIBRATION_EFFECT_SUPPORT_NO) " (emulated)" else "")
                    }
                }
            }
        }

        Panel(title = "Composition primitives") {
            Text("Tap primitives to build a sequence, then play it.", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PRIMITIVES.forEachIndexed { i, p ->
                    val supported = primitiveSupport.getOrElse(i) { false }
                    AssistChip(
                        onClick = {
                            sequence.add(p)
                            vibrator.vibrate(VibrationEffect.startComposition().addPrimitive(p.id, primitiveScale).compose())
                        },
                        enabled = supported,
                        label = { Text(p.name) },
                    )
                }
            }
            SectionLabel("Scale ${primitiveScale.fmt(2)}")
            Slider(value = primitiveScale, onValueChange = { primitiveScale = it }, valueRange = 0.05f..1f)
            Text(
                if (sequence.isEmpty()) "Sequence: empty" else "Sequence: " + sequence.joinToString(" → ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = if (sequence.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else ic.magnitude,
            )
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (sequence.isNotEmpty()) {
                            val c = VibrationEffect.startComposition()
                            sequence.forEach { c.addPrimitive(it.id, primitiveScale, 60) }
                            vibrator.vibrate(c.compose())
                        }
                    },
                    enabled = sequence.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Play sequence") }
                OutlinedButton(onClick = { sequence.clear() }, modifier = Modifier.weight(1f)) { Text("Clear") }
            }
        }
        Note("If nothing happens, check Settings › Sounds and vibration › Vibration intensity. Touch-feedback effects follow the system intensity setting.")
    }
}
