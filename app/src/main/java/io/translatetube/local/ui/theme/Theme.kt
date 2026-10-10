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

private fun os(weight: FontWeight, size: Int): androidx.compose.ui.text.TextStyle =
    androidx.compose.ui.text.TextStyle(
        fontFamily = OpenSans,
        fontWeight = weight,
        fontSize = size.sp,
    )

val AppTypography = Typography(
    displaySmall = os(FontWeight.ExtraBold, 34),
    headlineMedium = os(FontWeight.Bold, 26),
    titleLarge = os(FontWeight.Bold, 21),
    titleMedium = os(FontWeight.SemiBold, 17),
    bodyLarge = os(FontWeight.Normal, 16),
    bodyMedium = os(FontWeight.Normal, 14),
    bodySmall = os(FontWeight.Normal, 12),
    labelLarge = os(FontWeight.SemiBold, 14),
    labelMedium = os(FontWeight.SemiBold, 12),
)

private val ColorScheme = lightColorScheme(
    primary = BrandBright,
    onPrimary = BgDark,
    primaryContainer = Brand,
    onPrimaryContainer = OnBg,
    secondary = Brand,
    onSecondary = OnBg,
    background = BgDark,
    onBackground = OnBg,
    surface = SurfaceDark,
    onSurface = OnBg,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = SubText,
    outline = MutedGreen,
    error = DangerRed,
)

/** 深色绿黑主题（与原版一致，固定深色） */
@Composable
fun TranslateTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = AppTypography,
        content = content,
    )
}
