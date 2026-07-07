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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

// ---------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------

// "Siberian winter" identity: icy taiga teal as primary, birch/sunset gold as
// secondary accent, taiga-forest green as tertiary. Deliberately fixed (not
// device-wallpaper-derived, see SibirskySpeakTheme below).
internal val BrandLight = lightColorScheme(
    primary = Color(0xFF1F6E82),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC2E8F5),
    onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF976D0C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE4B0),
    onSecondaryContainer = Color(0xFF3A2C00),
    tertiary = Color(0xFF4B6C4F),
    tertiaryContainer = Color(0xFFCDEBCE),
    onTertiaryContainer = Color(0xFF0A2010),
    background = Color(0xFFF5F9FA),
    onBackground = Color(0xFF171D1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171D1E),
    surfaceVariant = Color(0xFFDCE4E5),
    onSurfaceVariant = Color(0xFF40484A),
    outline = Color(0xFF707879),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

internal val BrandDark = darkColorScheme(
    primary = Color(0xFF8ED2E8),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5C),
    onPrimaryContainer = Color(0xFFC2E8F5),
    secondary = Color(0xFFF0D47E),
    onSecondary = Color(0xFF3D2E00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFE4B0),
    tertiary = Color(0xFFB1CFB1),
    onTertiary = Color(0xFF1D361F),
    tertiaryContainer = Color(0xFF344D36),
    onTertiaryContainer = Color(0xFFCDEBCE),
    background = Color(0xFF0D1416),
    onBackground = Color(0xFFDEE4E5),
    surface = Color(0xFF12191B),
    onSurface = Color(0xFFDEE4E5),
    surfaceVariant = Color(0xFF40484A),
    onSurfaceVariant = Color(0xFFC0C8CA),
    outline = Color(0xFF8A9294),
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

internal val RussianDisplay = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)

@Composable
internal fun SibirskySpeakTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Keep the deliberate icy-teal/gold/taiga-green identity. Device wallpaper-derived
    // dynamic palettes can collapse the learning UI into nearly monochrome tones.
    val colors = if (dark) BrandDark else BrandLight
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}

// ---------------------------------------------------------------------------
// Scaffold + navigation
// ---------------------------------------------------------------------------

