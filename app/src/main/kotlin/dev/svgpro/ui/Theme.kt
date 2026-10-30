package dev.svgpro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * アプリ共通パレット（Illustrator風ダークUI）。
 * すべて #RRGGBB / #AARRGGBB で定義する。
 *
 * ― Dark（既定） ―
 *  キャンバス周り   #1B1B1B
 *  背景 chrome      #1E1E1E
 *  パネル           #252525
 *  盛り上げ         #2F2F2F
 *  ボーダー         #383838
 *  テキスト         #E8E8E8 / #A6A6A6
 *  アクセント Blue  #1473E6
 *  セカンダリ Amber #E8A33D
 *  スムーズ緑       #56A36C
 *  エラー           #E0644F
 *
 * ― Light ―
 *  背景 #F0F0F0 / パネル #FAFAFA / ボーダー #C9C9C9
 *  テキスト #232323 / #6E6E6E / アクセント #0D66D0
 */
object Palette {
    // ―― Dark ――
    val DarkCanvasBg = Color(0xFF1B1B1B)
    val DarkBg = Color(0xFF1E1E1E)
    val DarkSurface = Color(0xFF252525)
    val DarkElevated = Color(0xFF2F2F2F)
    val DarkOutline = Color(0xFF383838)
    val DarkText = Color(0xFFE8E8E8)
    val DarkTextDim = Color(0xFFA6A6A6)
    val DarkAccent = Color(0xFF1473E6)
    val DarkAccentDim = Color(0xFF2E5C99)
    val DarkAmber = Color(0xFFE8A33D)
    val DarkGreen = Color(0xFF56A36C)
    val DarkError = Color(0xFFE0644F)

    // ―― Light ――
    val LightCanvasBg = Color(0xFFE2E2E2)
    val LightBg = Color(0xFFF0F0F0)
    val LightSurface = Color(0xFFFAFAFA)
    val LightElevated = Color(0xFFE8E8E8)
    val LightOutline = Color(0xFFC9C9C9)
    val LightText = Color(0xFF232323)
    val LightTextDim = Color(0xFF6E6E6E)
    val LightAccent = Color(0xFF0D66D0)
    val LightAmber = Color(0xFFB26A10)
    val LightGreen = Color(0xFF2E7D4F)
    val LightError = Color(0xFFC4402E)
}

private val DarkColors = darkColorScheme(
    primary = Palette.DarkAccent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Palette.DarkAccentDim,
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Palette.DarkAmber,
    onSecondary = Color(0xFF1E1E1E),
    secondaryContainer = Color(0xFF4A3A1E),
    onSecondaryContainer = Color(0xFFF5D9A8),
    error = Palette.DarkError,
    background = Palette.DarkBg,
    onBackground = Palette.DarkText,
    surface = Palette.DarkSurface,
    onSurface = Palette.DarkText,
    surfaceVariant = Palette.DarkElevated,
    onSurfaceVariant = Palette.DarkTextDim,
    surfaceContainerLowest = Color(0xFF171717),
    surfaceContainerLow = Palette.DarkBg,
    surfaceContainer = Palette.DarkSurface,
    surfaceContainerHigh = Palette.DarkElevated,
    surfaceContainerHighest = Color(0xFF383838),
    outline = Palette.DarkOutline,
    outlineVariant = Color(0xFF2E2E2E),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF1E1E1E),
)

private val LightColors = lightColorScheme(
    primary = Palette.LightAccent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE2FF),
    onPrimaryContainer = Color(0xFF0A3B78),
    secondary = Palette.LightAmber,
    onSecondary = Color(0xFFFFFFFF),
    error = Palette.LightError,
    background = Palette.LightBg,
    onBackground = Palette.LightText,
    surface = Palette.LightSurface,
    onSurface = Palette.LightText,
    surfaceVariant = Palette.LightElevated,
    onSurfaceVariant = Palette.LightTextDim,
    outline = Palette.LightOutline,
    outlineVariant = Color(0xFFDDDDDD),
)

/** Illustrator風のタイトなタイポグラフィ */
private val AppTypography = Typography(
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 10.5.sp, letterSpacing = 0.3.sp),
)

private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun SVGproTheme(content: @Composable () -> Unit) {
    // ダークテーマを基本とし、ライトもサポート
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
