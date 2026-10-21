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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sabbaq.R

val TajawalFontFamily = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold)
)

internal val SabbaqLightColors = lightColorScheme(
    primary = Color(0xFF006B58), onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F3E5), onPrimaryContainer = Color(0xFF00382E),
    secondary = Color(0xFF244B60), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E9F3), onSecondaryContainer = Color(0xFF143448),
    tertiary = Color(0xFF795900), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6A7), onTertiaryContainer = Color(0xFF493400),
    background = Color(0xFFF3F7F8), onBackground = Color(0xFF142C37),
    surface = Color.White, onSurface = Color(0xFF142C37),
    surfaceVariant = Color(0xFFE6EFF0), onSurfaceVariant = Color(0xFF4C626B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F9FA),
    surfaceContainer = Color(0xFFEDF3F4), surfaceContainerHigh = Color(0xFFE4ECEE),
    outline = Color(0xFF70838B), outlineVariant = Color(0xFFD5E1E4),
    error = Color(0xFFAE282F), onError = Color.White,
    errorContainer = Color(0xFFFFE8E8), onErrorContainer = Color(0xFF7B1720)
)
internal val SabbaqDarkColors = darkColorScheme(
    primary = Color(0xFF82DFC3), onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005443), onPrimaryContainer = Color(0xFFC6F3E5),
    secondary = Color(0xFFA7CEE2), onSecondary = Color(0xFF103747),
    secondaryContainer = Color(0xFF244B60), onSecondaryContainer = Color(0xFFD5E9F3),
    tertiary = Color(0xFFF1CC7C), onTertiary = Color(0xFF493400),
    tertiaryContainer = Color(0xFF634A00), onTertiaryContainer = Color(0xFFFFE6A7),
    background = Color(0xFF081A22), onBackground = Color(0xFFE1EDF0),
    surface = Color(0xFF112A35), onSurface = Color(0xFFE1EDF0),
    surfaceVariant = Color(0xFF223E49), onSurfaceVariant = Color(0xFFB7CBD1),
    surfaceContainerLowest = Color(0xFF081A22), surfaceContainerLow = Color(0xFF112A35),
    surfaceContainer = Color(0xFF17333E), surfaceContainerHigh = Color(0xFF223E49),
    outline = Color(0xFF8CA6B0), outlineVariant = Color(0xFF35515D),
    error = Color(0xFFFFB3B5), onError = Color(0xFF650F19),
    errorContainer = Color(0xFF731A24), onErrorContainer = Color(0xFFFFDADB)
)
private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = TajawalFontFamily, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp
)
private val SabbaqTypography = Typography(
    displayLarge = type(48, 60, FontWeight.ExtraBold), displayMedium = type(40, 52, FontWeight.ExtraBold),
    displaySmall = type(36, 46, FontWeight.Bold), headlineLarge = type(32, 42, FontWeight.Bold),
    headlineMedium = type(28, 38, FontWeight.Bold), headlineSmall = type(24, 34, FontWeight.Bold),
    titleLarge = type(22, 30, FontWeight.Bold), titleMedium = type(18, 26, FontWeight.Bold),
    titleSmall = type(15, 22, FontWeight.Bold), bodyLarge = type(16, 26), bodyMedium = type(14, 23),
    bodySmall = type(12, 20), labelLarge = type(15, 22, FontWeight.Bold),
    labelMedium = type(12, 18, FontWeight.Medium), labelSmall = type(11, 16, FontWeight.Medium)
)

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
)

internal val SabbaqLightBrand = SabbaqBrandColors(
    heroStart = Color(0xFF0F3442), heroEnd = Color(0xFF00594A),
    onHero = Color.White, onHeroMuted = Color(0xFFCFE6E0), heroAccent = Color(0xFF82DFC3),
    success = Color(0xFF006B3F), successContainer = Color(0xFFD3F5E2), onSuccessContainer = Color(0xFF00391F),
)
internal val SabbaqDarkBrand = SabbaqBrandColors(
    heroStart = Color(0xFF0C2A35), heroEnd = Color(0xFF004638),
    onHero = Color(0xFFE9F4F2), onHeroMuted = Color(0xFFB4CFC9), heroAccent = Color(0xFF82DFC3),
    success = Color(0xFF8CDDB1), successContainer = Color(0xFF0D4A2D), onSuccessContainer = Color(0xFFD3F5E2),
)

val LocalSabbaqBrand = staticCompositionLocalOf { SabbaqLightBrand }

/** Brand colors stay stable; theme follows the system unless the user explicitly chooses. */
@Composable
fun SabbaqTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSabbaqBrand provides if (darkTheme) SabbaqDarkBrand else SabbaqLightBrand,
        LocalReducedMotion provides rememberSystemReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) SabbaqDarkColors else SabbaqLightColors,
            typography = SabbaqTypography,
            shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)),
            content = content
        )
    }
}
