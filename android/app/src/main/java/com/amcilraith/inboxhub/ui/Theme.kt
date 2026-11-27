package com.amcilraith.inboxhub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// White & charcoal, matching the desktop app.
private val Light = lightColorScheme(
    primary = Color(0xFF2A2A2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECECEF),
    onPrimaryContainer = Color(0xFF1B1B1E),
    secondary = Color(0xFF5C5C64),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECECEF),
    onSecondaryContainer = Color(0xFF1B1B1E),
    background = Color(0xFFF6F6F7),
    onBackground = Color(0xFF1B1B1E),
    surface = Color.White,
    onSurface = Color(0xFF1B1B1E),
    surfaceVariant = Color(0xFFF1F1F3),
    onSurfaceVariant = Color(0xFF5C5C64),
    surfaceContainer = Color(0xFFF6F6F7),
    surfaceContainerHigh = Color(0xFFF1F1F3),
    outline = Color(0xFFD9D9DE),
    outlineVariant = Color(0xFFE6E6E9),
    error = Color(0xFFD64545),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFECECEE),
    onPrimary = Color(0xFF161618),
    primaryContainer = Color(0xFF2C2C31),
    onPrimaryContainer = Color(0xFFF1F1F2),
    secondary = Color(0xFFA3A3AB),
    onSecondary = Color(0xFF161618),
    secondaryContainer = Color(0xFF2C2C31),
    onSecondaryContainer = Color(0xFFF1F1F2),
    background = Color(0xFF141416),
    onBackground = Color(0xFFF1F1F2),
    surface = Color(0xFF1C1C1F),
    onSurface = Color(0xFFF1F1F2),
    surfaceVariant = Color(0xFF242428),
    onSurfaceVariant = Color(0xFFA3A3AB),
    surfaceContainer = Color(0xFF1C1C1F),
    surfaceContainerHigh = Color(0xFF242428),
    outline = Color(0xFF3A3A40),
    outlineVariant = Color(0xFF2E2E33),
    error = Color(0xFFF0706F),
)

object Tones {
    val high = Color(0xFFD64545)
    val med = Color(0xFFB7791F)
    val good = Color(0xFF2F855A)
    /** Links and the unread marker: the desktop app's indigo accent. */
    val accent = Color(0xFF5B5BD6)
    val accentDark = Color(0xFF9B9BF0)
}

private val base = Typography()

/** Slightly tighter, heavier headings than Material's defaults, like the desktop app. */
private val type = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, fontSize = 28.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 23.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
)

@Composable
fun InboxHubTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = type, content = content)
}

@Composable
fun accentColor(): Color = if (MaterialTheme.colorScheme.background.luminanceApprox() < 0.5f) Tones.accentDark else Tones.accent
