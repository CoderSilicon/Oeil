package com.example.oeil.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DarkGreenPrimary,
    onPrimary = TextOnAccent,
    primaryContainer = Color(0xFF1C3320),
    onPrimaryContainer = DarkGreenBright,
    secondary = DarkTealAccent,
    onSecondary = TextOnAccent,
    secondaryContainer = DarkSurfaceElevated,
    onSecondaryContainer = DarkTextPrimary,
    tertiary = DarkPurpleGlow,
    onTertiary = DarkTextPrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkOutline,
    outlineVariant = Color(0xFF1E2A22),
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurfaceGlass,
    surfaceContainer = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceElevated,
    surfaceContainerHighest = Color(0xFF1A2A20)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF426B13),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F2A5),
    onPrimaryContainer = Color(0xFF172500),
    secondary = Color(0xFF006B5C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF2DF),
    onSecondaryContainer = Color(0xFF002019),
    tertiary = Color(0xFF63558B),
    onTertiary = Color.White,
    background = Color(0xFFF7FAF5),
    onBackground = Color(0xFF191D16),
    surface = Color(0xFFF7FAF5),
    onSurface = Color(0xFF191D16),
    surfaceVariant = Color(0xFFE8EDE3),
    onSurfaceVariant = Color(0xFF454B40),
    outline = Color(0xFF74796D),
    outlineVariant = Color(0xFFC5C9BD),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5EC),
    surfaceContainer = Color(0xFFEBF0E7),
    surfaceContainerHigh = Color(0xFFE5EAE1),
    surfaceContainerHighest = Color(0xFFDDE3D9)
)

@Composable
fun OeilTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
