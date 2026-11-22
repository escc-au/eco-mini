package org.escc.ecomini.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Roles used by the screens:
//   primary            accents (buttons, links, progress)
//   secondaryContainer the user's message bubbles
//   outlineVariant     borders
//   onSurfaceVariant   secondary text
private val Light = lightColorScheme(
    primary = Color(0xFF0466C8),
    onPrimary = Color.White,
    secondary = Color(0xFF0466C8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF4FD),
    onSecondaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFEAF4FD),
    onSurfaceVariant = Color(0xFF5B7FA6),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF5F9FD),
    surfaceContainerHighest = Color(0xFFEAF4FD),
    outline = Color(0xFFC9DCEE),
    outlineVariant = Color(0xFFC9DCEE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5AA4E8),
    onPrimary = Color.Black,
    secondary = Color(0xFF5AA4E8),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF0B2A4A),
    onSecondaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF0B2A4A),
    onSurfaceVariant = Color(0xFF8FB0D3),
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color(0xFF07182B),
    surfaceContainerHighest = Color(0xFF0B2A4A),
    outline = Color(0xFF1F3B5C),
    outlineVariant = Color(0xFF1F3B5C),
)

@Composable
fun EcoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
