package dev.svgpro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF42A5F5),
    onPrimary = Color(0xFF0B1116),
    secondary = Color(0xFFFFB74D),
    background = Color(0xFF101418),
    surface = Color(0xFF161C22),
    surfaceVariant = Color(0xFF1E262E),
    onBackground = Color(0xFFE6EAF0),
    onSurface = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFF9AA7B2),
    outline = Color(0xFF3A4750),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFFEF6C00),
    background = Color(0xFFF2F4F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFE4E8ED),
    onBackground = Color(0xFF16202A),
    onSurface = Color(0xFF16202A),
    onSurfaceVariant = Color(0xFF5A6874),
    outline = Color(0xFFB9C2CB),
)

@Composable
fun SVGproTheme(content: @Composable () -> Unit) {
    // ダークテーマを基本とし、ライトもサポート
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
