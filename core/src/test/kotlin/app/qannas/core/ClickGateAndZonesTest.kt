package app.qannas.core

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
    fun zoneHitUsesGreatCircleDistance() {
        val airport = ExcludeZone("1", "airport", 31.7226, 35.9932, 3_000)
        assertEquals("airport", Zones.firstHit(listOf(airport), 31.7300, 35.9900)?.name)
        assertNull(Zones.firstHit(listOf(airport), 31.9539, 35.9106))
        assertNull(Zones.firstHit(listOf(airport.copy(enabled = false)), 31.7300, 35.9900))
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
}
