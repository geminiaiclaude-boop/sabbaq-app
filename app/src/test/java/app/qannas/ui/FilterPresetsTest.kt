package app.qannas.ui

import app.qannas.core.Filters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterPresetsTest {
    private val base = FilterDraft.from(Filters()).copy(
        minFare = "20",
        minFarePerKm = "1.5",
        excludeKeywords = "مطار",
        scheduleEnabled = true,
        scheduleStart = "08:00",
        scheduleEnd = "22:00",
        maxTripMinutes = "90",
    )

    @Test
    fun `preset rewrites only distance and time fields`() {
        val applied = FilterPreset.NearAndQuick.applyTo(base)
        assertEquals("3", applied.maxPickupKm)
        assertEquals("7", applied.maxPickupMinutes)
        assertEquals("15", applied.maxTripKm)
        assertEquals("", applied.maxTripMinutes)
        assertEquals("20", applied.minFare)
        assertEquals("1.5", applied.minFarePerKm)
        assertEquals("مطار", applied.excludeKeywords)
        assertTrue(applied.scheduleEnabled)
        assertEquals("08:00", applied.scheduleStart)
    }

    @Test
    fun `every preset produces a valid draft`() {
        FilterPreset.entries.forEach { preset ->
            val validation = preset.applyTo(base).validate()
            assertTrue("${preset.name}: ${validation.errors}", validation.isValid)
        }
    }

    @Test
    fun `matching reflects the applied preset only`() {
        val applied = FilterPreset.LongTrips.applyTo(base)
        assertTrue(FilterPreset.LongTrips.matches(applied))
        assertFalse(FilterPreset.NearAndQuick.matches(applied))
        assertFalse(FilterPreset.LongTrips.matches(base))
    }
}
