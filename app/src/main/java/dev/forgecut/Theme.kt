package dev.forgecut

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ForgeColors = darkColorScheme(
    primary = Color(0xFF8B6CFF),
    onPrimary = Color.White,
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF17171E),
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF262631),
    onSurfaceVariant = Color(0xFFA6A6B8),
    secondaryContainer = Color(0xFF34344A),
    onSecondaryContainer = Color(0xFFEDEDF2),
)

@Composable
fun ForgeTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = ForgeColors, content = content)
