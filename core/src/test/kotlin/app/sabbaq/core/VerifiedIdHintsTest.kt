package app.sabbaq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedIdHintsTest {
    @Test
    fun rejectsUnsafeAndOverlongIds() {
        val hints = VerifiedIdHints(maxEntries = 2, maxIdLength = 12)
        assertFalse(hints.learn("قبول الطلب"))
        assertFalse(hints.learn("a".repeat(13)))
        assertEquals(emptyList<String>(), hints.snapshot())
    }

    @Test
    fun keepsAStableBoundedSetAndDeduplicates() {
        val hints = VerifiedIdHints(maxEntries = 2)
        assertTrue(hints.learn("me.com.easytaxista:id/accept"))
        assertTrue(hints.learn("me.com.easytaxista:id/accept"))
        assertTrue(hints.learn("me.com.easytaxista:id/accept_alt"))
        assertFalse(hints.learn("me.com.easytaxista:id/third"))
        assertEquals(2, hints.snapshot().size)
    }
}
