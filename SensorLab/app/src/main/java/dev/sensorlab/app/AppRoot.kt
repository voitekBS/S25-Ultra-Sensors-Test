package dev.sensorlab.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.sensorlab.app.feature.explorer.SensorDetailScreen
import dev.sensorlab.app.home.HomeScreen
import dev.sensorlab.app.home.Tools

/**
 * Minimal back stack navigation: routes are plain strings joined with '|', so the whole stack
 * survives process death via rememberSaveable without a navigation library.
 */
@Composable
fun AppRoot() {
    var stack by rememberSaveable { mutableStateOf("home") }
    val routes = stack.split('|')
    val current = routes.last()

    fun open(route: String) {
        stack = "$stack|$route"
    }

    fun back() {
        if (routes.size > 1) stack = stack.substringBeforeLast('|')
    }

    BackHandler(enabled = routes.size > 1) { back() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val forward = initialState == "home" || targetState.startsWith("sensor:")
                if (forward) {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 6 }) togetherWith
                        (fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { -it / 12 })
                } else {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 12 }) togetherWith
                        (fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { it / 6 })
                }
            },
            label = "navigation",
        ) { route ->
            when {
                route == "home" -> HomeScreen(onOpen = ::open)
                route.startsWith("sensor:") -> SensorDetailScreen(
                    index = route.removePrefix("sensor:").toIntOrNull() ?: 0,
                    onBack = ::back,
                )
                else -> {
                    val tool = Tools.byId(route)
                    if (tool != null) tool.screen(::back, ::open) else HomeScreen(onOpen = ::open)
                }
            }
        }
    }
}
