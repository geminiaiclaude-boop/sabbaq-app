package app.sabbaq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first screen's bar is always one row (owner, 2026-10-31) and never gives «سبّاق» less than
 * its own width (clipped on a 360 dp phone in 2.0.3-20261031.1219).
 */
class OnboardingBarLayoutTest {
    @Test fun everything_fits_at_full_width() {
        val bar = onboardingBarLayout(width = 616, brand = 200, language = 90, skip = 200, gap = 16)
        assertEquals(200, bar.skipMaxWidth)
        assertTrue(bar.brandMaxWidth >= 200)
    }

    @Test fun a_narrow_phone_with_large_text_shrinks_the_skip_button_never_the_name() {
        val bar = onboardingBarLayout(width = 560, brand = 260, language = 90, skip = 240, gap = 16)
        assertEquals(560 - 260 - 90 - 32, bar.skipMaxWidth)
        assertEquals(260, bar.brandMaxWidth)
    }
}
