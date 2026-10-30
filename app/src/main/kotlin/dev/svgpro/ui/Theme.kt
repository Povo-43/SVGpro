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
 * アプリ共通パレット（Illustrator風・ブランドカラー #B53202）。
 * すべて #RRGGBB / #AARRGGBB で定義する。
 *
 * ― ブランド ―
 *  Primary        #B53202 （指定のテーマカラー）
 *  Bright tint    #E8590C （暗地キャンバス上の選択色など視認性が必要な箇所の派生トーン）
 *  Deep shade     #7A2101 （コンテナ）
 *
 * ― Dark（編集画面の既定） ―
 *  キャンバス周り   #1B1B1B
 *  背景 chrome      #1A1A1A
 *  パネル           #232323
 *  盛り上げ         #2C2C2C
 *  ボーダー         #3A3A3A / #2E2E2E
 *  テキスト         #E8E8E8 / #9E9E9E
 *
 * ― Light ―
 *  背景 #F2F0ED / パネル #FAF9F8 / ボーダー #D5D2CF
 *  テキスト #232323 / #6E6E6E
 */
object Palette {
    // ―― ブランド（#B53202 とその派生トーン） ――
    val Brand = Color(0xFFB53202)
    val BrandBright = Color(0xFFE8590C)
    val BrandDeep = Color(0xFF7A2101)
    val BrandTint = Color(0xFFFFD9C7)

    // ―― Dark ――
    val DarkCanvasBg = Color(0xFF1B1B1B)
    val DarkBg = Color(0xFF1A1A1A)
    val DarkSurface = Color(0xFF232323)
    val DarkElevated = Color(0xFF2C2C2C)
    val DarkOutline = Color(0xFF3A3A3A)
    val DarkText = Color(0xFFE8E8E8)
    val DarkTextDim = Color(0xFF9E9E9E)
    val DarkAmber = Color(0xFFE8A33D)
    val DarkGreen = Color(0xFF56A36C)
    val DarkError = Color(0xFFE0644F)

    // ―― Light ――
    val LightCanvasBg = Color(0xFFE2E2E2)
    val LightBg = Color(0xFFF2F0ED)
    val LightSurface = Color(0xFFFAF9F8)
    val LightElevated = Color(0xFFECEAE7)
    val LightOutline = Color(0xFFD5D2CF)
    val LightText = Color(0xFF232323)
    val LightTextDim = Color(0xFF6E6E6E)
    val LightAmber = Color(0xFFB26A10)
    val LightGreen = Color(0xFF2E7D4F)
    val LightError = Color(0xFFC4402E)
}

private val DarkColors = darkColorScheme(
    primary = Palette.Brand,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Palette.BrandDeep,
    onPrimaryContainer = Palette.BrandTint,
    secondary = Palette.DarkAmber,
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF4A3A1E),
    onSecondaryContainer = Color(0xFFF5D9A8),
    tertiary = Palette.DarkGreen,
    error = Palette.DarkError,
    background = Palette.DarkBg,
    onBackground = Palette.DarkText,
    surface = Palette.DarkSurface,
    onSurface = Palette.DarkText,
    surfaceVariant = Palette.DarkElevated,
    onSurfaceVariant = Palette.DarkTextDim,
    surfaceContainerLowest = Color(0xFF161616),
    surfaceContainerLow = Palette.DarkBg,
    surfaceContainer = Palette.DarkSurface,
    surfaceContainerHigh = Palette.DarkElevated,
    surfaceContainerHighest = Color(0xFF383838),
    outline = Palette.DarkOutline,
    outlineVariant = Color(0xFF2E2E2E),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF1A1A1A),
)

private val LightColors = lightColorScheme(
    primary = Palette.Brand,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Palette.BrandTint,
    onPrimaryContainer = Palette.BrandDeep,
    secondary = Palette.LightAmber,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Palette.LightGreen,
    error = Palette.LightError,
    background = Palette.LightBg,
    onBackground = Palette.LightText,
    surface = Palette.LightSurface,
    onSurface = Palette.LightText,
    surfaceVariant = Palette.LightElevated,
    onSurfaceVariant = Palette.LightTextDim,
    outline = Palette.LightOutline,
    outlineVariant = Color(0xFFE4E1DE),
)

/** Illustrator風のタイトなタイポグラフィ */
private val AppTypography = Typography(
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

/** Illustrator風のシャープな角（工具はほぼスクエア） */
private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(7.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
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
