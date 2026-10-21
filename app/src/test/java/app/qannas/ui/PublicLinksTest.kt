package app.qannas.ui

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicLinksTest {
    @Test
    fun `legal links are public https pages on the declared authorized domain`() {
        listOf(PublicLinks.PRIVACY_POLICY, PublicLinks.ACCOUNT_DELETION).forEach { link ->
            val uri = URI(link)
            assertEquals("https", uri.scheme)
            assertEquals("yahooyahoo99.github.io", uri.host)
            assertTrue(uri.path.endsWith(".html"))
        }
    }
}
