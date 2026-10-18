package dev.sensorlab.app.feature.touch

import android.view.InputDevice
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.Stat
import dev.sensorlab.app.ui.components.StatGrid
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors

private enum class TouchMode(val label: String) { Multi("Multi-touch"), Draw("S Pen ink") }

@Composable
fun TouchScreen(onBack: () -> Unit) {
    val ic = LocalInstrumentColors.current
    val cs = MaterialTheme.colorScheme
    var mode by rememberSaveable { mutableStateOf(TouchMode.Multi) }
    var stats by remember { mutableStateOf(ProbeStats()) }
    var probe by remember { mutableStateOf<TouchProbeView?>(null) }
    val stylusPresent = remember {
        InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id)
            d != null && (d.sources and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        }
    }

    ToolScaffold(
        title = "Touch & S Pen",
        subtitle = if (stylusPresent) "Digitizer with stylus support detected" else "Touchscreen",
        onBack = onBack,
        scrollable = false,
        actions = {
            IconButton(onClick = { probe?.clear() }) { Icon(Icons.Filled.Delete, contentDescription = "Clear drawing") }
        },
    ) {
        ChoiceRow(TouchMode.entries.toList(), mode, { mode = it }, label = { it.label })
        StatGrid(
            listOf(
                Stat("Tool", stats.toolType + if (stats.buttonPressed) " ●" else ""),
                Stat("Touches", "${stats.pointers} / ${stats.maxPointers}"),
                Stat("Rate", stats.reportRateHz.fmt(0), "Hz"),
                Stat("Pressure", stats.pressure.fmt(3), color = ic.magnitude),
                Stat("Tilt", stats.tiltDeg.fmt(0), "°"),
                Stat("Orient.", stats.orientationDeg.fmt(0), "°"),
                Stat("Hover", if (stats.hovering) stats.hoverDistance.fmt(2) else "—"),
                Stat("Contact", "${stats.touchMajorMm.fmt(1)}×${stats.touchMinorMm.fmt(1)}", "mm"),
                Stat("Side button", if (stats.buttonPressed) "Pressed" else "—"),
            ),
        )
        val ink = cs.primary.toArgb()
        val finger = ic.warn.toArgb()
        val grid = cs.outlineVariant.toArgb()
        val text = cs.onSurface.toArgb()
        AndroidView(
            factory = { ctx ->
                TouchProbeView(ctx).also { v ->
                    v.onStats = { s -> stats = s }
                    probe = v
                }
            },
            update = { v ->
                v.mode = if (mode == TouchMode.Draw) TouchProbeView.Mode.Draw else TouchProbeView.Mode.MultiTouch
                v.inkColor = ink
                v.fingerColor = finger
                v.gridColor = grid
                v.textColor = text
                v.invalidate()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .systemGestureExclusion(),
        )
    }
}
