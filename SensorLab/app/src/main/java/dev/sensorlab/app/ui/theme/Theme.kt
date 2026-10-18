package dev.sensorlab.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colours used by the instrument drawings (dials, charts, axes) on top of the Material scheme. */
@Immutable
data class InstrumentColors(
    val x: Color,
    val y: Color,
    val z: Color,
    val magnitude: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val dialFace: Color,
    val tick: Color,
    val tickMinor: Color,
    val series: List<Color>,
)

private val DarkInstrument = InstrumentColors(
    x = Color(0xFFFF6B6B),
    y = Color(0xFF4ADE80),
    z = Color(0xFF60A5FA),
    magnitude = Color(0xFFFFB547),
    good = Color(0xFF4ADE80),
    warn = Color(0xFFFFB547),
    bad = Color(0xFFFF6B6B),
    dialFace = Color(0xFF12171C),
    tick = Color(0xFFE3E7EB),
    tickMinor = Color(0xFF5B6773),
    series = listOf(
        Color(0xFFFF6B6B), Color(0xFF4ADE80), Color(0xFF60A5FA),
        Color(0xFFFFB547), Color(0xFFC084FC), Color(0xFF5EEAD4),
    ),
)

private val LightInstrument = InstrumentColors(
    x = Color(0xFFDC2626),
    y = Color(0xFF16A34A),
    z = Color(0xFF2563EB),
    magnitude = Color(0xFFD97706),
    good = Color(0xFF16A34A),
    warn = Color(0xFFD97706),
    bad = Color(0xFFDC2626),
    dialFace = Color(0xFFFFFFFF),
    tick = Color(0xFF111827),
    tickMinor = Color(0xFF9CA3AF),
    series = listOf(
        Color(0xFFDC2626), Color(0xFF16A34A), Color(0xFF2563EB),
        Color(0xFFD97706), Color(0xFF9333EA), Color(0xFF0D9488),
    ),
)

val LocalInstrumentColors = staticCompositionLocalOf { DarkInstrument }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0F3D38),
    onPrimaryContainer = Color(0xFFB2F5EA),
    secondary = Color(0xFFFFB547),
    onSecondary = Color(0xFF2B1700),
    secondaryContainer = Color(0xFF4A3410),
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = Color(0xFF8AB4FF),
    onTertiary = Color(0xFF00214D),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE3E7EB),
    surface = Color(0xFF0B0E11),
    onSurface = Color(0xFFE3E7EB),
    surfaceVariant = Color(0xFF1A2026),
    onSurfaceVariant = Color(0xFF9AA5B1),
    surfaceContainerLowest = Color(0xFF080A0D),
    surfaceContainerLow = Color(0xFF101418),
    surfaceContainer = Color(0xFF13181D),
    surfaceContainerHigh = Color(0xFF1A2026),
    surfaceContainerHighest = Color(0xFF222A31),
    outline = Color(0xFF3A444F),
    outlineVariant = Color(0xFF242C34),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCF5EE),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFFB45309),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE8C7),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF1D4ED8),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF6F8FA),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE5E9EE),
    onSurfaceVariant = Color(0xFF5B6673),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F3F6),
    surfaceContainerHighest = Color(0xFFE8ECF0),
    outline = Color(0xFFC3CAD3),
    outlineVariant = Color(0xFFE2E7EC),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
)

/** Monospaced styles for numeric read-outs, so digits don't jitter as values change. */
object Readouts {
    val hero = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 56.sp,
        letterSpacing = (-1).sp,
    )
    val large = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp,
    )
    val medium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
    )
    val small = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

@Composable
fun SensorLabTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInstrumentColors provides if (darkTheme) DarkInstrument else LightInstrument) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = Typography(),
            content = content,
        )
    }
}
