package org.tdgs.ph.ui.theme

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

/** One accent per metric, used consistently on cards, charts and icons. */
@Immutable
data class Metrics(
    val steps: Color, val heart: Color, val sleep: Color, val restful: Color, val stress: Color,
    val readiness: Color, val oxygen: Color, val weight: Color, val battery: Color, val energy: Color,
    val intensity: Color, val muted: Color, val track: Color, val card: Color, val cardAlt: Color,
)

private val DarkMetrics = Metrics(
    steps = Color(0xFF34D3A6), heart = Color(0xFFFF6B6B), sleep = Color(0xFF8C7CF0), restful = Color(0xFF5B4BC4),
    stress = Color(0xFFF5B544), readiness = Color(0xFF4CC9F0), oxygen = Color(0xFF5AB0FF), weight = Color(0xFFC9A7FF),
    battery = Color(0xFF9BE15D), energy = Color(0xFFFF9F43), intensity = Color(0xFFFF7AB6),
    muted = Color(0xFF8A93A3), track = Color(0xFF262C38), card = Color(0xFF171B22), cardAlt = Color(0xFF1F2430),
)

private val LightMetrics = DarkMetrics.copy(
    steps = Color(0xFF0FA97F), heart = Color(0xFFE04848), sleep = Color(0xFF6B5AE0), restful = Color(0xFF4636A8),
    stress = Color(0xFFD08A10), readiness = Color(0xFF1597C9), oxygen = Color(0xFF2F86E0), weight = Color(0xFF8E62D9),
    battery = Color(0xFF5DA924), energy = Color(0xFFE07A10), intensity = Color(0xFFD94C8E),
    muted = Color(0xFF6B7280), track = Color(0xFFE6E9EF), card = Color(0xFFFFFFFF), cardAlt = Color(0xFFF2F4F8),
)

val LocalMetrics = staticCompositionLocalOf { DarkMetrics }

private val Dark = darkColorScheme(
    primary = Color(0xFF34D3A6), onPrimary = Color(0xFF00281D), secondary = Color(0xFF4CC9F0),
    background = Color(0xFF0E1116), onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF0E1116), onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF171B22), onSurfaceVariant = Color(0xFF8A93A3),
    surfaceContainer = Color(0xFF13171D), outline = Color(0xFF2B3240), error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0FA97F), onPrimary = Color.White, secondary = Color(0xFF1597C9),
    background = Color(0xFFF4F6FA), onBackground = Color(0xFF12161D),
    surface = Color(0xFFF4F6FA), onSurface = Color(0xFF12161D),
    surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainer = Color(0xFFFFFFFF), outline = Color(0xFFD9DEE7), error = Color(0xFFE04848),
)

private val Type = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
)

@Composable
fun PhTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    CompositionLocalProvider(LocalMetrics provides if (dark) DarkMetrics else LightMetrics) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
    }
}
