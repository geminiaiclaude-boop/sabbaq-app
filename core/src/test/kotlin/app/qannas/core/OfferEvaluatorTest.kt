package app.qannas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun scheduleIsAnActiveRuleByItself() {
        val filters = Filters(scheduleEnabled = true, scheduleStartMinute = 8 * 60, scheduleEndMinute = 9 * 60)
        assertTrue(OfferEvaluator.evaluate(offer(), filters, 8 * 60 + 30).accept)
        assertEquals(listOf(ReasonCode.OUTSIDE_SCHEDULE), codes(OfferEvaluator.evaluate(offer(), filters, 10 * 60)))
    }

    @Test
    fun roundedDisplayRateCannotPassAThresholdItIsActuallyBelow() {
        val verdict = OfferEvaluator.evaluate(offer(fare = "0.4996", tripKm = "1"), Filters(minFarePerKm = "0.500"), 600)
        assertEquals(BigDecimal("0.500"), verdict.farePerKm)
        assertEquals(listOf(ReasonCode.PER_KM_TOO_LOW), codes(verdict))
    }

    @Test
    fun malformedOrContradictoryLimitsFailClosed() {
        val malformed = OfferEvaluator.evaluate(offer(), Filters(minFare = "not-a-number", acceptWhenNoFilters = true), 600)
        assertFalse(malformed.accept)
        assertEquals(listOf(ReasonCode.INVALID_FILTER), codes(malformed))

        val contradictory = OfferEvaluator.evaluate(offer(), Filters(minFare = "10", maxFare = "5"), 600)
        assertFalse(contradictory.accept)
        assertTrue(ReasonCode.INVALID_FILTER in codes(contradictory))
    }

    @Test
    fun invalidScheduleBoundsAndMinuteOfDayFailClosed() {
        val invalid = OfferEvaluator.evaluate(
            offer(),
            Filters(scheduleEnabled = true, scheduleStartMinute = 1_440, scheduleEndMinute = 60),
            30,
        )
        assertEquals(listOf(ReasonCode.INVALID_FILTER), codes(invalid))
        assertFalse(OfferEvaluator.inSchedule(-1, 0, 60))
        assertFalse(OfferEvaluator.inSchedule(0, -1, 60))
        assertFalse(OfferEvaluator.inSchedule(0, 0, 1_441))
    }

    @Test
    fun negativeOfferMeasurementsAreMissingRatherThanPassingMaximums() {
        val unsafe = offer(fare = "-1", pickupKm = "-1", tripKm = "-1", pickupMin = -1, tripMin = -1)
        val verdict = OfferEvaluator.evaluate(
            unsafe,
            Filters(maxFare = "10", maxPickupKm = "10", maxPickupMinutes = 10, maxTripKm = "10", maxTripMinutes = 10),
            600,
        )
        assertEquals(
            setOf(ReasonCode.FARE_MISSING, ReasonCode.PICKUP_KM_MISSING, ReasonCode.PICKUP_MIN_MISSING, ReasonCode.TRIP_KM_MISSING, ReasonCode.TRIP_MIN_MISSING),
            codes(verdict).toSet(),
        )
        assertNull(OfferEvaluator.farePerKm(unsafe, PerKmBasis.TRIP))
    }
}
