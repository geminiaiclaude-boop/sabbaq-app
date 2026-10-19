package app.qannas.core

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickGateAndZonesTest {
    @Test
    fun sameOfferIsClickedOnce() {
        val gate = ClickGate(perOfferSuppressMs = 60_000, minIntervalMs = 1_000)
        assertTrue(gate.tryAcquire("a", 0))
        assertFalse(gate.tryAcquire("a", 5_000))
        assertTrue(gate.tryAcquire("a", 61_000))
    }

    @Test
    fun differentOffersRespectTheMinimumInterval() {
        val gate = ClickGate(perOfferSuppressMs = 60_000, minIntervalMs = 1_000)
        assertTrue(gate.tryAcquire("a", 0))
        assertFalse(gate.tryAcquire("b", 500))
        assertTrue(gate.tryAcquire("b", 1_000))
    }

    @Test
    fun suppressionExpiresAtItsExactBoundaryButClockRollbackAndBlankIdsDoNotBypassIt() {
        val gate = ClickGate(perOfferSuppressMs = 60_000, minIntervalMs = 1_000)
        assertFalse(gate.tryAcquire("", 0))
        assertTrue(gate.tryAcquire("a", 1_000))
        assertFalse(gate.tryAcquire("b", 999))
        assertTrue(gate.tryAcquire("a", 61_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSuppressionConfigurationIsRejected() {
        ClickGate(perOfferSuppressMs = 0, minIntervalMs = 0)
    }

    @Test
    fun fullGateRejectsNewOfferInsteadOfEvictingAnActiveFingerprint() {
        val gate = ClickGate(perOfferSuppressMs = 100, minIntervalMs = 0, capacity = 1)
        assertTrue(gate.tryAcquire("a", 0))
        assertFalse(gate.tryAcquire("b", 1))
        assertFalse(gate.tryAcquire("a", 2))
        assertTrue(gate.tryAcquire("b", 100))
    }

    @Test
    fun zoneHitUsesGreatCircleDistance() {
        val airport = ExcludeZone("1", "airport", 31.7226, 35.9932, 3_000)
        assertEquals("airport", Zones.firstHit(listOf(airport), 31.7300, 35.9900)?.name)
        assertNull(Zones.firstHit(listOf(airport), 31.9539, 35.9106))
        assertNull(Zones.firstHit(listOf(airport.copy(enabled = false)), 31.7300, 35.9900))
    }

    @Test
    fun invalidCoordinatesAndNegativeRadiiCannotCreateZoneHits() {
        val airport = ExcludeZone("1", "airport", 31.7226, 35.9932, 3_000)
        assertTrue(Zones.distanceMeters(Double.NaN, 0.0, 0.0, 0.0).isInfinite())
        assertNull(Zones.firstHit(listOf(airport), Double.POSITIVE_INFINITY, 35.9932))
        assertNull(Zones.firstHit(listOf(airport.copy(radiusMeters = -1)), 31.7226, 35.9932))
        assertNull(Zones.firstHit(listOf(airport.copy(lat = 91.0)), 31.7226, 35.9932))
    }

    @Test
    fun newerRemoteProfileReplacesBundled() {
        val local = Profiles.bundled()
        val remote = listOf(local.first().copy(version = 2, acceptLabels = listOf("go")), local.first().copy(id = "genie", version = 1))
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
