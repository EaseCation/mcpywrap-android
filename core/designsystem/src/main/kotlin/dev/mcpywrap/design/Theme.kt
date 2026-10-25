package dev.mcpywrap.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF35694B), onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F1C8), onPrimaryContainer = Color(0xFF103921),
    secondary = Color(0xFF526454), secondaryContainer = Color(0xFFD5E8D5),
    tertiary = Color(0xFF38656A), tertiaryContainer = Color(0xFFBCEBF0),
    background = Color(0xFFF3F4F6), surface = Color(0xFFF3F4F6),
    surfaceContainer = Color(0xFFECEEF1), surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE6E8EC), surfaceContainerHighest = Color(0xFFDEE2E6),
    onSurface = Color(0xFF1B1D21), onSurfaceVariant = Color(0xFF555A61),
    outline = Color(0xFF7C828B), outlineVariant = Color(0xFFD0D4DA)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CD5AE), onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF1C5134), onPrimaryContainer = Color(0xFFB7F1C8),
    secondaryContainer = Color(0xFF3A4C3C), tertiaryContainer = Color(0xFF1D4D52),
    background = Color(0xFF111315), surface = Color(0xFF111315),
    surfaceContainer = Color(0xFF202327), surfaceContainerLow = Color(0xFF1C1F22),
    surfaceContainerHigh = Color(0xFF282C30), surfaceContainerHighest = Color(0xFF33383D)
)

/** Shared product geometry. Keep screens on the same 4dp grid and shape language. */
object McpySpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val page = 20.dp
    val section = 24.dp
}

object McpyLayout {
    val compactBreakpoint = 600.dp
    val contentWidth = 840.dp
    val wideGutter = 32.dp
}

object McpyShapes {
    val card = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val compactCard = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(50)
}

@Composable
fun McpyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = McpyShapes.compactCard,
            medium = McpyShapes.card,
            large = McpyShapes.card,
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
