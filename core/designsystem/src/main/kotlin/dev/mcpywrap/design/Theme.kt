package dev.mcpywrap.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF35694B), onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F1C8), onPrimaryContainer = Color(0xFF103921),
    secondary = Color(0xFF526454), secondaryContainer = Color(0xFFD5E8D5),
    tertiary = Color(0xFF38656A), tertiaryContainer = Color(0xFFBCEBF0),
    background = Color(0xFFF7FAF3), surface = Color(0xFFF7FAF3),
    surfaceContainer = Color(0xFFEBEFE7), surfaceContainerLow = Color(0xFFF1F5ED),
    surfaceContainerHigh = Color(0xFFE5E9E1), surfaceContainerHighest = Color(0xFFDFE4DC),
    onSurface = Color(0xFF181D18), onSurfaceVariant = Color(0xFF424940),
    outline = Color(0xFF72796F), outlineVariant = Color(0xFFC2C9BE)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CD5AE), onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF1C5134), onPrimaryContainer = Color(0xFFB7F1C8),
    secondaryContainer = Color(0xFF3A4C3C), tertiaryContainer = Color(0xFF1D4D52),
    background = Color(0xFF101510), surface = Color(0xFF101510),
    surfaceContainer = Color(0xFF1C211B), surfaceContainerLow = Color(0xFF181D17),
    surfaceContainerHigh = Color(0xFF272C25), surfaceContainerHighest = Color(0xFF323730)
)

@Composable
fun McpyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
