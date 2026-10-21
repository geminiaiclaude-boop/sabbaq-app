package app.qannas.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedPlatformsTest {
    @Test
    fun brands_are_the_names_drivers_know() {
        assertEquals("Bolt", SupportedPlatforms.brandOf("Bolt Driver"))
        assertEquals("PetraRide", SupportedPlatforms.brandOf("PetraRide Captain"))
        assertEquals("Jeeny", SupportedPlatforms.brandOf("Jeeny Driver"))
    }

    /** What the app advertises must be exactly what the accessibility service is allowed to read. */
    @Test
    fun advertised_platforms_match_the_accessibility_config_and_package_queries() {
        val advertised = SupportedPlatforms.all.flatMap { it.packages }.toSet()
        assertTrue("no supported platforms found", advertised.isNotEmpty())

        val config = File("src/main/res/xml/accessibility_service_config.xml").readText()
        val allowed = Regex("android:packageNames=\"([^\"]+)\"").find(config)!!.groupValues[1].split(',').map { it.trim() }.toSet()
        assertEquals(allowed, advertised)

        val manifest = File("src/main/AndroidManifest.xml").readText()
        advertised.forEach { pkg ->
            assertTrue("$pkg must be declared in <queries> for installed detection", "<package android:name=\"$pkg\" />" in manifest)
        }
    }
}
