package app.sabbaq.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class SabbaqColorContrastTest {
    @Test fun `light text roles meet normal text contrast`() = verify(false)
    @Test fun `dark text roles meet normal text contrast`() = verify(true)

    private fun verify(dark: Boolean) {
        val c = if (dark) SabbaqDarkColors else SabbaqLightColors
        val pairs = mapOf(
            "primary" to (c.primary to c.onPrimary),
            "primaryContainer" to (c.primaryContainer to c.onPrimaryContainer),
            "secondaryContainer" to (c.secondaryContainer to c.onSecondaryContainer),
            "surface" to (c.surface to c.onSurface),
            "muted text on surface" to (c.surface to c.onSurfaceVariant),
            "background" to (c.background to c.onBackground),
            "errorContainer" to (c.errorContainer to c.onErrorContainer)
        )
        val brand = if (dark) SabbaqDarkBrand else SabbaqLightBrand
        val brandPairs = mapOf(
            "hero start" to (brand.heroStart to brand.onHero),
            "hero end" to (brand.heroEnd to brand.onHero),
            "muted hero text" to (brand.heroEnd to brand.onHeroMuted),
            "successContainer" to (brand.successContainer to brand.onSuccessContainer),
            "success on surface" to (c.surface to brand.success),
            "signature ink" to (brand.signature to brand.onSignature),
            "accent on hero start" to (brand.heroStart to brand.heroAccent),
            "accent on hero end" to (brand.heroEnd to brand.heroAccent),
        )
        (pairs + brandPairs).forEach { (name, pair) ->
            val contrast = ratio(pair.first, pair.second)
            assertTrue("$name contrast=$contrast dark=$dark", contrast >= 4.5)
        }
    }
    private fun ratio(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
