package io.translatetube.local.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.translatetube.local.R

val OpenSans = FontFamily(
    Font(R.font.opensans_regular, FontWeight.Normal),
    Font(R.font.opensans_semibold, FontWeight.SemiBold),
    Font(R.font.opensans_bold, FontWeight.Bold),
    Font(R.font.opensans_extrabold, FontWeight.ExtraBold),
)

private fun os(weight: FontWeight, size: Int): TextStyle =
    TextStyle(fontFamily = OpenSans, fontWeight = weight, fontSize = size.sp)

// 全局明确每一级文字的字号与字重，避免页面各自使用不同的默认样式。
val AppTypography = Typography(
    displaySmall = os(FontWeight.ExtraBold, 32),
    headlineMedium = os(FontWeight.Bold, 25),
    headlineSmall = os(FontWeight.Bold, 22),
    titleLarge = os(FontWeight.Bold, 20),
    titleMedium = os(FontWeight.SemiBold, 16),
    titleSmall = os(FontWeight.SemiBold, 14),
    bodyLarge = os(FontWeight.Normal, 15),
    bodyMedium = os(FontWeight.Normal, 14),
    bodySmall = os(FontWeight.Normal, 12),
    labelLarge = os(FontWeight.SemiBold, 13),
    labelMedium = os(FontWeight.SemiBold, 12),
    labelSmall = os(FontWeight.SemiBold, 11),
)

private val ColorScheme = lightColorScheme(
    primary = BrandBright,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = SurfaceHigh,
    onPrimaryContainer = OnBg,
    secondary = Brand,
    onSecondary = Color(0xFFFFFFFF),
    background = BgDark,
    onBackground = OnBg,
    surface = SurfaceDark,
    onSurface = OnBg,
    surfaceVariant = SelectedBlue,
    onSurfaceVariant = SubText,
    outline = OutlineSoft,
    error = DangerRed,
)

/** 应用统一蓝白主题 */
@Composable
fun TranslateTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = AppTypography,
        content = content,
    )
}
