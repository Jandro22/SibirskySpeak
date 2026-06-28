package com.sibirskyspeak

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------

internal val BrandLight = lightColorScheme(
    primary = Color(0xFF315DA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFFB23A48),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDADC),
    onSecondaryContainer = Color(0xFF40000B),
    tertiary = Color(0xFF7D5260),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF31101D),
    background = Color(0xFFF6F8F6),
    onBackground = Color(0xFF162022),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF162022),
    surfaceVariant = Color(0xFFE4ECEA),
    onSurfaceVariant = Color(0xFF536265),
    outline = Color(0xFFB8C7C5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

internal val BrandDark = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF164584),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFFFB2B9),
    onSecondary = Color(0xFF680019),
    secondaryContainer = Color(0xFF8F2332),
    onSecondaryContainer = Color(0xFFFFDADC),
    tertiary = Color(0xFFE8B4CB),
    tertiaryContainer = Color(0xFF633B49),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF0E1417),
    onBackground = Color(0xFFE8F0F2),
    surface = Color(0xFF121A1E),
    onSurface = Color(0xFFE8F0F2),
    surfaceVariant = Color(0xFF253238),
    onSurfaceVariant = Color(0xFFB6C8CC),
    outline = Color(0xFF405259),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
internal fun SibirskySpeakTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Keep the deliberate blue/red/berry identity. Device wallpaper-derived
    // dynamic palettes can collapse the learning UI into nearly monochrome tones.
    val colors = if (dark) BrandDark else BrandLight
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}

// ---------------------------------------------------------------------------
// Scaffold + navigation
// ---------------------------------------------------------------------------

