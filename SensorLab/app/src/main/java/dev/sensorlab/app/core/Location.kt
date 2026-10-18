package dev.sensorlab.app.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

fun Context.hasLocationPermission(): Boolean =
    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * A recent location for things that only need to know roughly where the phone is
 * (magnetic declination). Uses the newest last-known fix, then asks for one fresh fix.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberApproxLocation(enabled: Boolean): Location? {
    val context = LocalContext.current
    var location by remember { mutableStateOf<Location?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled || !context.hasLocationPermission()) return@LaunchedEffect
        val lm = context.getSystemService(LocationManager::class.java) ?: return@LaunchedEffect
        val providers = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        location = providers
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (location == null) {
            val provider = providers.firstOrNull { p ->
                p != LocationManager.PASSIVE_PROVIDER && runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
            } ?: return@LaunchedEffect
            runCatching {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { fix ->
                    if (fix != null) location = fix
                }
            }
        }
    }
    return location
}
