package dev.sensorlab.app.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.sensorlab.app.core.hasPermission

/**
 * Shows [content] only once every permission in [permissions] is granted. Asks once automatically,
 * then offers a button (and a shortcut to App settings if the user declined).
 */
@Composable
fun PermissionGate(
    permissions: List<String>,
    rationale: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val perms = remember(permissions) { permissions.toTypedArray() }
    fun allGranted() = perms.all { context.hasPermission(it) }

    var granted by remember(permissions) { mutableStateOf(allGranted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var declined by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = allGranted()
        declined = !granted
    }

    LifecycleResumeEffect(permissions) {
        granted = allGranted()
        onPauseOrDispose { }
    }

    LaunchedEffect(permissions) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(perms)
        }
    }

    if (granted) {
        content()
    } else {
        Panel(title = "Permission needed") {
            Text(rationale, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launcher.launch(perms) }) { Text("Grant access") }
                if (declined) {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("App settings") }
                }
            }
        }
    }
}
