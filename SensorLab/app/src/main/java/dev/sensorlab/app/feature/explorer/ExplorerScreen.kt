package dev.sensorlab.app.feature.explorer

import android.hardware.Sensor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sensorlab.app.core.SensorCatalog
import dev.sensorlab.app.core.SensorGroup
import dev.sensorlab.app.core.fmt
import dev.sensorlab.app.core.sensorManager
import dev.sensorlab.app.ui.components.ChoiceRow
import dev.sensorlab.app.ui.components.MonoSmall
import dev.sensorlab.app.ui.components.Pill
import dev.sensorlab.app.ui.components.ToolScaffold
import dev.sensorlab.app.ui.theme.LocalInstrumentColors

/** Same list (and order) the detail screen indexes into. */
fun allSensors(sm: android.hardware.SensorManager): List<Sensor> =
    sm.getSensorList(Sensor.TYPE_ALL) + sm.getDynamicSensorList(Sensor.TYPE_ALL)

private enum class Filter(val label: String) { All("All"), Motion("Motion"), Position("Position"), Environment("Env."), Vendor("Vendor"), Wake("Wake-up") }

@Composable
fun ExplorerScreen(onBack: () -> Unit, onOpenSensor: (Int) -> Unit) {
    val context = LocalContext.current
    val ic = LocalInstrumentColors.current
    val sensors = remember { allSensors(context.sensorManager) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }

    val shown = sensors.withIndex().filter { (_, s) ->
        val g = SensorCatalog.group(s.type)
        val matchesFilter = when (filter) {
            Filter.All -> true
            Filter.Motion -> g == SensorGroup.Motion
            Filter.Position -> g == SensorGroup.Position
            Filter.Environment -> g == SensorGroup.Environment
            Filter.Vendor -> g == SensorGroup.Vendor
            Filter.Wake -> s.isWakeUpSensor
        }
        val q = query.trim()
        matchesFilter && (q.isEmpty() || s.name.contains(q, true) || s.vendor.contains(q, true) || s.stringType.contains(q, true) ||
            SensorCatalog.name(s.type).contains(q, true))
    }

    ToolScaffold(
        title = "Sensor explorer",
        subtitle = "${sensors.size} sensors · ${sensors.count { it.type >= SensorCatalog.VENDOR_BASE }} vendor-specific",
        onBack = onBack,
        scrollable = false,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search name, vendor or type") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceRow(Filter.entries.toList(), filter, { filter = it }, label = { it.label })
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(shown, key = { it.index }) { (index, s) ->
                Card(
                    onClick = { onOpenSensor(index) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (s.isWakeUpSensor) {
                                Spacer(Modifier.width(6.dp))
                                Pill("wake", ic.warn)
                            }
                            if (s.type >= SensorCatalog.VENDOR_BASE) {
                                Spacer(Modifier.width(6.dp))
                                Pill("vendor", MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        Text(
                            "${SensorCatalog.name(s.type)} · ${s.vendor}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${s.stringType}  ·  ${s.power.fmt(2)} mA  ·  ${SensorCatalog.reportingMode(s)}",
                            style = MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
