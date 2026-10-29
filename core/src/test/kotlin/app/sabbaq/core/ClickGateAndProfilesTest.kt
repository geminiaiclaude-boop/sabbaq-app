package app.sabbaq.core

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickGateAndProfilesTest {







    @Test
    fun newerRemoteProfileReplacesBundled() {
        val local = Profiles.bundled()
        // Relative to the bundled version so the test keeps meaning "strictly newer" after profile bumps.
        val remote = listOf(local.first().copy(version = local.first().version + 1, acceptLabels = listOf("go")), local.first().copy(id = "genie", version = 1))
        val merged = Profiles.merge(local, remote).associateBy { it.id }
        assertEquals(listOf("go"), merged.getValue(local.first().id).acceptLabels)
        assertTrue("genie" in merged)
        assertEquals(local.size + 1, merged.size)
    }

    @Test
    fun malformedRemoteProfileCannotReplaceValidLocalProfile() {
        val local = Profiles.bundled()
        val original = local.first()
        val malformed = original.copy(version = original.version + 1, currencyTokens = emptyList())
        val duplicateNewer = original.copy(id = "new-profile", version = 2)
        val duplicateOlder = duplicateNewer.copy(version = 1, acceptLabels = listOf("bad"))
        val merged = Profiles.merge(local, listOf(malformed, duplicateOlder, duplicateNewer)).associateBy { it.id }
        assertEquals(original, merged.getValue(original.id))
        assertEquals(listOf("accept", "accept offer", "accept the offer", "accept ride", "aceptar", "قبول", "قبول الطلب", "قبول العرض", "قبول الرحلة"), merged.getValue("new-profile").acceptLabels)
        assertFalse(Profiles.isUsable(malformed))
    }

    @Test
    fun boundsFingerprintAndTreeWalkAreRobustAtNumericAndStructuralEdges() {
        assertEquals(0, Bounds(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE).centerX)
        assertEquals(0, Bounds(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE).centerY)

        val base = Offer("bolt", BigDecimal("4"), "jd", null, BigDecimal("9"), null, null, listOf("a|b"), emptyList())
        val distinct = base.copy(addresses = listOf("a", "b"))
        assertFalse(base.fingerprint == distinct.fingerprint)

        var tree = ScreenNode(id = 0)
        repeat(5_000) { index -> tree = ScreenNode(id = index + 1, children = listOf(tree)) }
        var visited = 0
        tree.walk { visited++ }
        assertEquals(5_001, visited)
    }
}
