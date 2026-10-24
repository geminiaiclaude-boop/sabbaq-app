package app.sabbaq.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchSequenceTest {
    @Test fun logoIntroDoesNotStartWhileCinematicOpeningIsVisible() {
        assertFalse(shouldRenderLogoIntro(showIntro = true, showVideoOpening = true))
    }

    @Test fun logoIntroStartsAfterCinematicOpeningFinishes() {
        assertTrue(shouldRenderLogoIntro(showIntro = true, showVideoOpening = false))
    }

    @Test fun logoIntroRemainsHiddenWhenNotRequested() {
        assertFalse(shouldRenderLogoIntro(showIntro = false, showVideoOpening = false))
    }
}
