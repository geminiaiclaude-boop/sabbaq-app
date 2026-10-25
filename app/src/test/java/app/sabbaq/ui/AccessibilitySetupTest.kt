package app.sabbaq.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySetupTest {
    @Test
    fun google_play_is_the_only_source_that_skips_restricted_settings_guidance() {
        assertTrue(AccessibilitySetup.isGooglePlayInstaller("com.android.vending"))
        assertFalse(AccessibilitySetup.isGooglePlayInstaller(null))
        assertFalse(AccessibilitySetup.isGooglePlayInstaller("com.android.packageinstaller"))
        assertFalse(AccessibilitySetup.isGooglePlayInstaller("com.transsion.phonemaster"))
    }
}
