package com.gflix.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.gflix.app.R

object GColors {
    val Black = Color(0xFF000000)
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141414)
    val Card = Color(0xFF232323)
    val Elevated = Color(0xFF2B2B2B)
    val Red = Color(0xFFE50914)
    val RedDark = Color(0xFFB20710)
    val White = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextMuted = Color(0xFF808080)
    val Divider = Color(0xFF2F2F2F)
    val ButtonGray = Color(0xB36D6D6E)
    val Chip = Color(0xFF5E5E5E)
    val Match = Color(0xFF46D369)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_black, FontWeight.Black),
)

val Bebas = FontFamily(Font(R.font.bebas_neue, FontWeight.Normal))

private fun TextStyle.inter() = copy(fontFamily = Inter)

private val base = Typography()
private val GTypography = Typography(
    displayLarge = base.displayLarge.inter(),
    displayMedium = base.displayMedium.inter(),
    displaySmall = base.displaySmall.inter(),
    headlineLarge = base.headlineLarge.inter(),
    headlineMedium = base.headlineMedium.inter(),
    headlineSmall = base.headlineSmall.inter(),
    titleLarge = base.titleLarge.inter(),
    titleMedium = base.titleMedium.inter(),
    titleSmall = base.titleSmall.inter(),
    bodyLarge = base.bodyLarge.inter(),
    bodyMedium = base.bodyMedium.inter(),
    bodySmall = base.bodySmall.inter(),
    labelLarge = base.labelLarge.inter(),
    labelMedium = base.labelMedium.inter(),
    labelSmall = base.labelSmall.inter(),
)

private val GColorScheme = darkColorScheme(
    primary = GColors.Red,
    onPrimary = GColors.White,
    secondary = GColors.White,
    onSecondary = GColors.Black,
    background = GColors.Background,
    onBackground = GColors.White,
    surface = GColors.Surface,
    onSurface = GColors.White,
    surfaceVariant = GColors.Card,
    onSurfaceVariant = GColors.TextSecondary,
    surfaceContainer = GColors.Surface,
    surfaceContainerHigh = GColors.Card,
    surfaceContainerHighest = GColors.Elevated,
    outline = GColors.TextMuted,
)

@Composable
fun GFlixTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GColorScheme, typography = GTypography, content = content)
}
