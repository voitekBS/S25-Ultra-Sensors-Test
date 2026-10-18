package dev.sensorlab.app.home

import android.hardware.Sensor
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.SensorCatalog
import dev.sensorlab.app.core.sensorManager
import dev.sensorlab.app.ui.theme.Readouts

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val sensors = remember { context.sensorManager.getSensorList(Sensor.TYPE_ALL) }
    val vendorCount = remember(sensors) { sensors.count { it.type >= SensorCatalog.VENDOR_BASE } }
    val wakeUpCount = remember(sensors) { sensors.count { it.isWakeUpSensor } }
    val availability = remember { Tools.all.associate { it.id to it.isAvailable(context) } }
    val grouped = remember { Tools.all.groupBy { it.category } }
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Header(
                model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
                android = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                sensorCount = sensors.size,
                vendorCount = vendorCount,
                wakeUpCount = wakeUpCount,
                onExplore = { onOpen("explorer") },
            )
        }
        ToolCategory.entries.forEach { category ->
            val tools = grouped[category].orEmpty().filter { it.id != "explorer" }
            if (tools.isEmpty()) return@forEach
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    category.title.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
            items(tools, key = { it.id }) { tool ->
                ToolCard(tool, availability[tool.id] != false) { onOpen(tool.id) }
            }
        }
    }
}

@Composable
private fun Header(
    model: String,
    android: String,
    sensorCount: Int,
    vendorCount: Int,
    wakeUpCount: Int,
    onExplore: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("Sensor Lab", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "$model · $android",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Card(
            onClick = onExplore,
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SENSORS ON THIS PHONE",
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                        color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$sensorCount", style = Readouts.hero.copy(fontSize = 48.sp), color = cs.onPrimaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "$vendorCount vendor · $wakeUpCount wake-up",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onPrimaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                    Text(
                        "Open the explorer for raw values, specs and CSV logging",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open sensor explorer",
                    tint = cs.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ToolCard(tool: Tool, available: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        modifier = Modifier.fillMaxWidth().alpha(if (available) 1f else 0.45f),
    ) {
        Column(Modifier.padding(16.dp).height(118.dp)) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tool.icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(tool.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                if (available) tool.subtitle else "Not available on this phone",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
