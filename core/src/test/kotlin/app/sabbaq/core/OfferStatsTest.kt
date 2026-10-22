package app.sabbaq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

class OfferStatsTest {
    @Test
    fun recordsCountsAndDistinctReasonsWithoutOfferDetails() {
        val day = OfferStatsDay("2026-10-22")
            .recordVisible()
            .recordRejected(listOf(ReasonCode.FARE_TOO_LOW, ReasonCode.FARE_TOO_LOW, ReasonCode.PICKUP_TOO_FAR))
            .recordAccepted()

        assertEquals(1L, day.visibleOffers)
        assertEquals(1L, day.rejectedOffers)
        assertEquals(1L, day.acceptedOffers)
        assertEquals(1L, day.rejectionsByReason[ReasonCode.FARE_TOO_LOW])
        assertEquals(1L, day.rejectionsByReason[ReasonCode.PICKUP_TOO_FAR])
    }

    @Test
    fun aggregatesDailyBucketsForAnyRequestedPeriod() {
        val days = listOf(
            OfferStatsDay("2026-10-20", visibleOffers = 3, rejectedOffers = 2, acceptedOffers = 1,
                rejectionsByReason = mapOf(ReasonCode.FARE_TOO_LOW to 2)),
            OfferStatsDay("2026-10-21", visibleOffers = 5, rejectedOffers = 4, acceptedOffers = 1,
                rejectionsByReason = mapOf(ReasonCode.PICKUP_TOO_FAR to 3)),
            OfferStatsDay("2026-11-01", visibleOffers = 100, rejectedOffers = 90, acceptedOffers = 10),
        )

        val weekly = OfferStatsTotals.from(days.filter { it.date in "2026-10-19".."2026-10-25" })
        val monthly = OfferStatsTotals.from(days.filter { it.date in "2026-10-01".."2026-10-31" })

        assertEquals(8L, weekly.visibleOffers)
        assertEquals(6L, weekly.rejectedOffers)
        assertEquals(2L, weekly.acceptedOffers)
        assertEquals(8L, monthly.visibleOffers)
        assertEquals(6L, monthly.rejectedOffers)
        assertEquals(2L, monthly.acceptedOffers)
        assertNotSame(weekly, monthly)
    }

    @Test
    fun aggregateIgnoresNegativeCorruptValuesAndSaturatesOverflow() {
        val totals = OfferStatsTotals.from(listOf(
            OfferStatsDay("2026-10-22", visibleOffers = Long.MAX_VALUE, acceptedOffers = -20),
            OfferStatsDay("2026-10-23", visibleOffers = 5),
        ))
        assertEquals(Long.MAX_VALUE, totals.visibleOffers)
        assertEquals(0L, totals.acceptedOffers)
    }

    @Test
    fun topReasonsAreLimitedAndOrderedByCountThenStableCode() {
        val totals = OfferStatsTotals(rejectionsByReason = mapOf(
            ReasonCode.PICKUP_TOO_FAR to 8,
            ReasonCode.FARE_TOO_LOW to 8,
            ReasonCode.EXCLUDED_KEYWORD to 3,
            ReasonCode.OUTSIDE_SCHEDULE to 1,
        ))

        assertEquals(
            listOf(
                ReasonCode.FARE_TOO_LOW to 8L,
                ReasonCode.PICKUP_TOO_FAR to 8L,
                ReasonCode.EXCLUDED_KEYWORD to 3L,
            ),
            totals.topRejectionReasons(),
        )
        assertTrue(totals.topRejectionReasons(0).isEmpty())
    }

    @Test
    fun persistedDaySchemaContainsOnlyDateAndAggregateCounters() {
        val encoded = Json.encodeToString(
            OfferStatsDay("2026-10-22", 4, 2, 1, mapOf(ReasonCode.FARE_TOO_LOW to 2)),
        )

        assertTrue(encoded.contains("2026-10-22"))
        assertTrue(encoded.contains("FARE_TOO_LOW"))
        assertFalse(encoded.contains("address"))
        assertFalse(encoded.contains("fingerprint"))
        assertFalse(encoded.contains("passenger"))
    }

    @Test
    fun periodWindowsUseMondayWeeksAndRetainCrossMonthWeekData() {
        val firstOfMonth = LocalDate.parse("2026-11-01") // Sunday

        assertEquals(LocalDate.parse("2026-10-26"), OfferStatsPeriods.weekStart(firstOfMonth))
        assertEquals(LocalDate.parse("2026-11-01"), OfferStatsPeriods.monthStart(firstOfMonth))
        assertEquals(LocalDate.parse("2026-10-26"), OfferStatsPeriods.retentionStart(firstOfMonth))
        assertTrue(OfferStatsPeriods.contains("2026-10-26", OfferStatsPeriods.weekStart(firstOfMonth), firstOfMonth))
        assertFalse(OfferStatsPeriods.contains("2026-10-25", OfferStatsPeriods.weekStart(firstOfMonth), firstOfMonth))
    }

    @Test
    fun deduplicatesPerCategoryAndDateWithBoundedMemory() {
        val deduplicator = OfferStatsDeduplicator(maxTrackedFingerprints = 2, evictionBatchSize = 1)

        assertTrue(deduplicator.firstVisible("offer-a", "2026-10-22"))
        assertFalse(deduplicator.firstVisible("offer-a", "2026-10-22"))
        assertTrue(deduplicator.firstRejection("offer-a", "2026-10-22"))
        assertFalse(deduplicator.firstRejection("offer-a", "2026-10-22"))
        assertTrue(deduplicator.firstVisible("offer-b", "2026-10-22"))
        assertTrue(deduplicator.firstVisible("offer-c", "2026-10-22"))
        assertTrue(deduplicator.firstVisible("offer-a", "2026-10-23"))

        deduplicator.reset()
        assertTrue(deduplicator.firstVisible("offer-a", "2026-10-23"))
    }
}
