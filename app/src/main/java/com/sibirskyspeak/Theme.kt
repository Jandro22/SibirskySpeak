package com.sibirskyspeak

import androidx.compose.foundation.background
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
    primary = Color(0xFF2D7DB9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1C4F73),
    onPrimaryContainer = Color(0xFFD7EEFF),
    secondary = Color(0xFF5DB878),
    onSecondary = Color(0xFF082611),
    secondaryContainer = Color(0xFF1D4B2B),
    onSecondaryContainer = Color(0xFFC8F3D0),
    tertiary = Color(0xFFD6AE4B),
    onTertiary = Color(0xFF2A1B00),
    tertiaryContainer = Color(0xFF594300),
    onTertiaryContainer = Color(0xFFFFE5A4),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF141B24),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF26323D),
    onSurfaceVariant = Color(0xFFB4C0CA),
    outline = Color(0xFF71808C),
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
    // SibirskySpeak is intentionally dark-first: the app is a focused evening
    // practice tool, and its primary user keeps the device in dark mode. Keeping
    // this explicit also means screenshots, QA, and the shipped experience agree.
    val colors = BrandDark
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}

// ---------------------------------------------------------------------------
// Scaffold + navigation
// ---------------------------------------------------------------------------

