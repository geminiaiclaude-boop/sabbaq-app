package app.sabbaq.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sabbaq.LocaleManager
import app.sabbaq.R

val TajawalFontFamily = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold)
)

// Royal purple carries actions and structure; signal yellow is the accent (selected chips,
// badges, highlights); green is reserved for confirmations.
internal val SabbaqLightColors = lightColorScheme(
    primary = Color(0xFF4E2A84), onPrimary = Color.White,
    primaryContainer = Color(0xFFECE3FA), onPrimaryContainer = Color(0xFF2A1250),
    secondary = Color(0xFF6B4E00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE27A), onSecondaryContainer = Color(0xFF2A1250),
    tertiary = Color(0xFF137A38), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7F5DF), onTertiaryContainer = Color(0xFF0B3D1E),
    background = Color(0xFFF5F2FA), onBackground = Color(0xFF1F1633),
    surface = Color.White, onSurface = Color(0xFF1F1633),
    surfaceVariant = Color(0xFFEDE7F5), onSurfaceVariant = Color(0xFF5A5170),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9F7FC),
    surfaceContainer = Color(0xFFF2EDF8), surfaceContainerHigh = Color(0xFFE9E2F3), surfaceContainerHighest = Color(0xFFE2D9EF),
    outline = Color(0xFF7B7191), outlineVariant = Color(0xFFDDD5EA),
    error = Color(0xFFAE282F), onError = Color.White,
    errorContainer = Color(0xFFFFE8E8), onErrorContainer = Color(0xFF7B1720)
)
internal val SabbaqDarkColors = darkColorScheme(
    primary = Color(0xFFD3BFFF), onPrimary = Color(0xFF2A1250),
    primaryContainer = Color(0xFF3E2270), onPrimaryContainer = Color(0xFFECE3FA),
    secondary = Color(0xFFFFD54A), onSecondary = Color(0xFF2A1F00),
    secondaryContainer = Color(0xFF5A4300), onSecondaryContainer = Color(0xFFFFE27A),
    tertiary = Color(0xFF7FDE9C), onTertiary = Color(0xFF0B3D1E),
    tertiaryContainer = Color(0xFF0D4A2D), onTertiaryContainer = Color(0xFFD7F5DF),
    background = Color(0xFF120B1F), onBackground = Color(0xFFECE6F5),
    surface = Color(0xFF1C1430), onSurface = Color(0xFFECE6F5),
    surfaceVariant = Color(0xFF2C2244), onSurfaceVariant = Color(0xFFC9BEDD),
    surfaceContainerLowest = Color(0xFF120B1F), surfaceContainerLow = Color(0xFF1C1430),
    surfaceContainer = Color(0xFF231A3A), surfaceContainerHigh = Color(0xFF2C2244), surfaceContainerHighest = Color(0xFF362B50),
    outline = Color(0xFF968BAD), outlineVariant = Color(0xFF3F3459),
    error = Color(0xFFFFB3B5), onError = Color(0xFF650F19),
    errorContainer = Color(0xFF731A24), onErrorContainer = Color(0xFFFFDADB)
)
private fun type(family: FontFamily, size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = family, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp
)

/**
 * Arabic: Tajawal, with the taller lines Arabic marks need. English: the phone's own sans-serif
 * (Roboto on most devices, built for Latin text and UI), a slightly smaller scale with Latin line
 * heights, because English labels run longer than the Arabic ones the cards were laid out for.
 */
private fun sabbaqTypography(arabic: Boolean): Typography = if (arabic) TajawalFontFamily.let { f ->
    Typography(
        displayLarge = type(f, 48, 60, FontWeight.ExtraBold), displayMedium = type(f, 40, 52, FontWeight.ExtraBold),
        displaySmall = type(f, 36, 46, FontWeight.Bold), headlineLarge = type(f, 32, 42, FontWeight.Bold),
        headlineMedium = type(f, 28, 38, FontWeight.Bold), headlineSmall = type(f, 24, 34, FontWeight.Bold),
        titleLarge = type(f, 22, 30, FontWeight.Bold), titleMedium = type(f, 18, 26, FontWeight.Bold),
        titleSmall = type(f, 15, 22, FontWeight.Bold), bodyLarge = type(f, 16, 26), bodyMedium = type(f, 14, 23),
        bodySmall = type(f, 12, 20), labelLarge = type(f, 15, 22, FontWeight.Bold),
        labelMedium = type(f, 12, 18, FontWeight.Medium), labelSmall = type(f, 11, 16, FontWeight.Medium)
    )
} else FontFamily.SansSerif.let { f ->
    Typography(
        displayLarge = type(f, 44, 52, FontWeight.Bold), displayMedium = type(f, 36, 44, FontWeight.Bold),
        displaySmall = type(f, 32, 40, FontWeight.Bold), headlineLarge = type(f, 28, 36, FontWeight.Bold),
        headlineMedium = type(f, 24, 32, FontWeight.Bold), headlineSmall = type(f, 22, 28, FontWeight.Bold),
        titleLarge = type(f, 20, 26, FontWeight.Bold), titleMedium = type(f, 16, 22, FontWeight.Bold),
        titleSmall = type(f, 14, 20, FontWeight.Bold), bodyLarge = type(f, 15, 22), bodyMedium = type(f, 14, 20),
        bodySmall = type(f, 12, 16), labelLarge = type(f, 14, 20, FontWeight.Medium),
        labelMedium = type(f, 12, 16, FontWeight.Medium), labelSmall = type(f, 11, 16, FontWeight.Medium)
    )
}

/**
 * Brand roles Material3 has no slot for: the deep "command" surface used by hero areas,
 * and a success role distinct from primary so confirmations never look like actions.
 */
@Immutable
data class SabbaqBrandColors(
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val heroAccent: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** Signal yellow fill for badges, accent bars and highlight strips, with its ink. */
    val signature: Color,
    val onSignature: Color,
)

internal val SabbaqLightBrand = SabbaqBrandColors(
    heroStart = Color(0xFF341862), heroEnd = Color(0xFF56298F),
    onHero = Color.White, onHeroMuted = Color(0xFFE4D9F5), heroAccent = Color(0xFFFFD21F),
    success = Color(0xFF137A38), successContainer = Color(0xFFD7F5DF), onSuccessContainer = Color(0xFF0B3D1E),
    signature = Color(0xFFFFD21F), onSignature = Color(0xFF2A1250),
)
internal val SabbaqDarkBrand = SabbaqBrandColors(
    heroStart = Color(0xFF22103F), heroEnd = Color(0xFF3E1F70),
    onHero = Color(0xFFF3EEFB), onHeroMuted = Color(0xFFD3C6EA), heroAccent = Color(0xFFFFD21F),
    success = Color(0xFF7FDE9C), successContainer = Color(0xFF0D4A2D), onSuccessContainer = Color(0xFFD7F5DF),
    signature = Color(0xFFFFD21F), onSignature = Color(0xFF2A1250),
)

val LocalSabbaqBrand = staticCompositionLocalOf { SabbaqLightBrand }

/**
 * Brand colors stay stable; theme follows the system unless the user explicitly chooses. Layout
 * direction and type follow the app language from the same source as every label
 * ([LocaleManager.isArabic]): right-to-left with Tajawal in Arabic, left-to-right with the
 * system sans-serif in English.
 */
@Composable
fun SabbaqTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val arabic = LocaleManager.isArabic()
    CompositionLocalProvider(
        LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalSabbaqBrand provides if (darkTheme) SabbaqDarkBrand else SabbaqLightBrand,
        LocalReducedMotion provides rememberSystemReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) SabbaqDarkColors else SabbaqLightColors,
            typography = sabbaqTypography(arabic),
            shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)),
            content = content
        )
    }
}
