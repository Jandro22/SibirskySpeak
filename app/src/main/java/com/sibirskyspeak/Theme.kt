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

// AnkiDroid-inspired identity: the same Material blue/green pairing AnkiDroid
// itself is built on, echoing this app's own rating-button colors (see
// Rating.accent() in CommonComponents.kt — GOOD is green, EASY is blue) so the
// study-screen and the rest of the app read as one system instead of two.
// Deliberately fixed (not device-wallpaper-derived, see SibirskySpeakTheme below).
internal val BrandLight = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBBDEFB),
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF2E7D32),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8E6C9),
    onSecondaryContainer = Color(0xFF1B5E20),
    tertiary = Color(0xFF976D0C),
    tertiaryContainer = Color(0xFFFFE4B0),
    onTertiaryContainer = Color(0xFF3A2C00),
    background = Color(0xFFFAFBFC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E5E8),
    onSurfaceVariant = Color(0xFF44484C),
    outline = Color(0xFF74777A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

internal val BrandDark = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF1565C0),
    onPrimaryContainer = Color(0xFFBBDEFB),
    secondary = Color(0xFFA5D6A7),
    onSecondary = Color(0xFF0F3512),
    secondaryContainer = Color(0xFF2E7D32),
    onSecondaryContainer = Color(0xFFC8E6C9),
    tertiary = Color(0xFFF0D47E),
    onTertiary = Color(0xFF3D2E00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFE4B0),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE3E5E7),
    surface = Color(0xFF17181B),
    onSurface = Color(0xFFE3E5E7),
    surfaceVariant = Color(0xFF43474A),
    onSurfaceVariant = Color(0xFFC3C7CA),
    outline = Color(0xFF8D9195),
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

// Fully-rounded "pill" shape for thin progress bars, tag chips, and small
// circular tap targets. Percent-based (not a fixed dp radius like
// RoundedCornerShape(99.dp)) so it stays fully round regardless of the
// element's height instead of only "close enough" for small elements.
internal val PillShape = RoundedCornerShape(50)

// Material3's ColorScheme has no built-in "success" slot. This is the app's
// one semantic success/mastered/won green, used for GOOD ratings, known
// words, goal-reached states, and match wins — reference this rather than
// re-declaring the literal so every "this went well" signal stays the same
// shade in both light and dark theme.
internal val SuccessGreen = Color(0xFF2E9E5B)

internal val RussianDisplay = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)

@Composable
internal fun SibirskySpeakTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Keep the deliberate blue/green/gold identity. Device wallpaper-derived
    // dynamic palettes can collapse the learning UI into nearly monochrome tones.
    val colors = if (dark) BrandDark else BrandLight
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}

// ---------------------------------------------------------------------------
// Scaffold + navigation
// ---------------------------------------------------------------------------

