package app.qannas.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.qannas.R

val TajawalFontFamily = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF005B99),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E4FF),
    onPrimaryContainer = Color(0xFF001D33),
    secondary = Color(0xFF006E2F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA8F5B8),
    onSecondaryContainer = Color(0xFF00210D),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF191C1F),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF191C1F),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF42474E),
    outline = Color(0xFF72777F)
)

private val BaseTypography = Typography()
private val QannasTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = TajawalFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = TajawalFontFamily),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = TajawalFontFamily),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = TajawalFontFamily),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = TajawalFontFamily),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = TajawalFontFamily),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = TajawalFontFamily),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = TajawalFontFamily),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = TajawalFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = TajawalFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = TajawalFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = TajawalFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = TajawalFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = TajawalFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = TajawalFontFamily)
)

@Composable
fun QannasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = QannasTypography,
        content = content
    )
}
