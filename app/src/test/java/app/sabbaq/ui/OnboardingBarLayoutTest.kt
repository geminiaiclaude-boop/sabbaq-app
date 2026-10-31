package app.sabbaq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first screen's bar never gives «سبّاق» less than its own width (clipped on a 360 dp phone in 2.0.3-20261031.1219). */
class OnboardingBarLayoutTest {
    @Test fun everything_fits_in_one_row_at_the_original_height() {
        val layout = onboardingBarLayout(width = 620, brand = 200, language = 180, skip = 200, gap = 16)
        assertTrue(layout.oneRow)
        assertTrue(layout.brandMaxWidth >= 200)
    }

    @Test fun a_narrow_phone_moves_the_language_chips_down_instead_of_clipping_the_name() {
        val layout = onboardingBarLayout(width = 600, brand = 214, language = 180, skip = 198, gap = 16)
        assertFalse(layout.oneRow)
        assertEquals(600 - 198 - 16, layout.brandMaxWidth)
        assertTrue(layout.brandMaxWidth >= 214)
    }
}
