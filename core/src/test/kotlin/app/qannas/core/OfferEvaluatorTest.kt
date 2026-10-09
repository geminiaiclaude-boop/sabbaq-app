package app.qannas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class OfferEvaluatorTest {
    private fun offer(
        fare: String? = "5",
        pickupKm: String? = "1",
        tripKm: String? = "10",
        pickupMin: Int? = 3,
        tripMin: Int? = 20,
        texts: List<String> = listOf("mecca street"),
    ) = Offer("bolt", fare?.let(::BigDecimal), "jd", pickupKm?.let(::BigDecimal), tripKm?.let(::BigDecimal), pickupMin, tripMin, emptyList(), texts)

    private fun codes(v: Verdict) = v.reasons.map { it.code }

    @Test
    fun noFiltersMeansNoAutoAcceptUnlessExplicitlyAllowed() {
        assertEquals(listOf(ReasonCode.NO_FILTERS), codes(OfferEvaluator.evaluate(offer(), Filters(), 600)))
        assertTrue(OfferEvaluator.evaluate(offer(), Filters(acceptWhenNoFilters = true), 600).accept)
    }

    @Test
    fun everyThresholdIsApplied() {
        val f = Filters(minFare = "6", minFarePerKm = "1", maxPickupKm = "0.5", maxPickupMinutes = 2, minTripKm = "11", maxTripMinutes = 10)
        val v = OfferEvaluator.evaluate(offer(), f, 600)
        assertFalse(v.accept)
        assertEquals(
            setOf(ReasonCode.FARE_TOO_LOW, ReasonCode.PER_KM_TOO_LOW, ReasonCode.PICKUP_TOO_FAR, ReasonCode.PICKUP_TOO_LONG, ReasonCode.TRIP_TOO_SHORT, ReasonCode.TRIP_MIN_TOO_LONG),
            codes(v).toSet(),
        )
    }

    @Test
    fun unreadableValueNeverPassesAnActiveRule() {
        val v = OfferEvaluator.evaluate(offer(fare = null, tripKm = null), Filters(minFare = "1", minFarePerKm = "0.1"), 600)
        assertEquals(setOf(ReasonCode.FARE_MISSING, ReasonCode.PER_KM_MISSING), codes(v).toSet())
    }

    @Test
    fun goodOfferIsAccepted() {
        val v = OfferEvaluator.evaluate(offer(), Filters(minFare = "4", minFarePerKm = "0.45", maxPickupKm = "2"), 600)
        assertTrue(v.accept)
        assertEquals(BigDecimal("0.500"), v.farePerKm)
    }

    @Test
    fun totalBasisIncludesThePickupLeg() {
        assertEquals(BigDecimal("0.455"), OfferEvaluator.farePerKm(offer(), PerKmBasis.TOTAL))
    }

    @Test
    fun excludedKeywordBlocks() {
        val v = OfferEvaluator.evaluate(offer(texts = listOf("airport road")), Filters(excludeKeywords = listOf("Airport")), 600)
        assertEquals(listOf(ReasonCode.EXCLUDED_KEYWORD), codes(v))
    }

    @Test
    fun scheduleWrapsMidnight() {
        assertTrue(OfferEvaluator.inSchedule(23 * 60, 22 * 60, 4 * 60))
        assertTrue(OfferEvaluator.inSchedule(60, 22 * 60, 4 * 60))
        assertFalse(OfferEvaluator.inSchedule(12 * 60, 22 * 60, 4 * 60))
        val f = Filters(minFare = "1", scheduleEnabled = true, scheduleStartMinute = 22 * 60, scheduleEndMinute = 4 * 60)
        assertEquals(listOf(ReasonCode.OUTSIDE_SCHEDULE), codes(OfferEvaluator.evaluate(offer(), f, 12 * 60)))
    }
}
