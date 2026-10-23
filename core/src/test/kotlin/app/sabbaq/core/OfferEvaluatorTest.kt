package app.sabbaq.core

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
        addresses: List<String> = listOf("Pickup address", "Destination address"),
    ) = Offer("bolt", fare?.let(::BigDecimal), "jd", pickupKm?.let(::BigDecimal), tripKm?.let(::BigDecimal), pickupMin, tripMin, addresses, texts)

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
    fun minimumPickupAndTripDurationsRejectShorterOffers() {
        val verdict = OfferEvaluator.evaluate(
            offer(pickupMin = 3, tripMin = 9),
            Filters(
                minPickupMinutes = 5, maxPickupMinutes = 20, pickupTimeEnabled = true,
                minTripMinutes = 10, maxTripMinutes = 60, tripTimeEnabled = true,
            ),
            600,
        )
        assertEquals(setOf(ReasonCode.PICKUP_TOO_SHORT, ReasonCode.TRIP_MIN_TOO_SHORT), codes(verdict).toSet())
    }

    @Test
    fun reversedTimeRangesFailClosedWhenFiltersAreReadDirectly() {
        val pickup = OfferEvaluator.evaluate(
            offer(), Filters(minPickupMinutes = 15, maxPickupMinutes = 10, pickupTimeEnabled = true), 600,
        )
        assertTrue(ReasonCode.INVALID_FILTER in codes(pickup))

        val trip = OfferEvaluator.evaluate(
            offer(), Filters(minTripMinutes = 80, maxTripMinutes = 30, tripTimeEnabled = true), 600,
        )
        assertTrue(ReasonCode.INVALID_FILTER in codes(trip))
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

    @Test
    fun highFareOverrideBypassesOnlyPickupDistanceAndTimeLimitsAtTheThreshold() {
        val filters = Filters(
            maxPickupKm = "2", pickupDistanceEnabled = true,
            maxPickupMinutes = 10, pickupTimeEnabled = true,
            maxTripMinutes = 30, tripTimeEnabled = true,
            highFareOverrideEnabled = true, highFareOverrideAmount = "35",
        )
        val acceptedAtThreshold = OfferEvaluator.evaluate(
            offer(fare = "35", pickupKm = "8", pickupMin = 45, tripMin = 90), filters, 600,
        )
        assertTrue(acceptedAtThreshold.accept)

        val belowThreshold = OfferEvaluator.evaluate(
            offer(fare = "34.99", pickupKm = "8", pickupMin = 45, tripMin = 90), filters, 600,
        )
        assertEquals(
            setOf(ReasonCode.PICKUP_TOO_FAR, ReasonCode.PICKUP_TOO_LONG, ReasonCode.TRIP_MIN_TOO_LONG),
            codes(belowThreshold).toSet(),
        )

        val disabled = OfferEvaluator.evaluate(
            offer(fare = "40", pickupKm = "8", pickupMin = 45, tripMin = 90),
            filters.copy(highFareOverrideEnabled = false),
            600,
        )
        assertEquals(
            setOf(ReasonCode.PICKUP_TOO_FAR, ReasonCode.PICKUP_TOO_LONG, ReasonCode.TRIP_MIN_TOO_LONG),
            codes(disabled).toSet(),
        )
    }

    @Test
    fun highFareOverrideDoesNotBypassFarePerKmOrExcludedKeywords() {
        val filters = Filters(
            minFarePerKm = "2", farePerKmEnabled = true,
            maxPickupKm = "1", pickupDistanceEnabled = true,
            highFareOverrideEnabled = true, highFareOverrideAmount = "35",
            excludeKeywords = listOf("airport"),
        )
        val verdict = OfferEvaluator.evaluate(
            offer(fare = "35", pickupKm = "9", tripKm = "20", texts = listOf("airport road")),
            filters,
            600,
        )
        assertEquals(setOf(ReasonCode.PER_KM_TOO_LOW, ReasonCode.EXCLUDED_KEYWORD), codes(verdict).toSet())
    }

    @Test
    fun highFareOverrideWithUnknownFareDoesNotBypassPickupLimits() {
        val verdict = OfferEvaluator.evaluate(
            offer(fare = null, pickupKm = "8"),
            Filters(maxPickupKm = "2", pickupDistanceEnabled = true, highFareOverrideEnabled = true),
            600,
        )
        assertEquals(listOf(ReasonCode.PICKUP_TOO_FAR), codes(verdict))
    }

    @Test
    fun highFareOverrideDoesNotBypassMinimumTimeRequirements() {
        val verdict = OfferEvaluator.evaluate(
            offer(fare = "35", pickupMin = 3, tripMin = 5),
            Filters(
                minPickupMinutes = 5, maxPickupMinutes = 10, pickupTimeEnabled = true,
                minTripMinutes = 10, maxTripMinutes = 20, tripTimeEnabled = true,
                maxPickupKm = "1", pickupDistanceEnabled = true,
                highFareOverrideEnabled = true, highFareOverrideAmount = "35",
            ),
            600,
        )
        assertEquals(setOf(ReasonCode.PICKUP_TOO_SHORT, ReasonCode.TRIP_MIN_TOO_SHORT), codes(verdict).toSet())
    }

    @Test
    fun highFareOverrideWithDefaultZeroMinimumDoesNotRequireTheBypassedTimeFields() {
        val verdict = OfferEvaluator.evaluate(
            offer(fare = "35", pickupMin = null, tripMin = null, pickupKm = "8"),
            Filters(
                minPickupMinutes = 0, maxPickupMinutes = 10, pickupTimeEnabled = true,
                minTripMinutes = 0, maxTripMinutes = 20, tripTimeEnabled = true,
                maxPickupKm = "2", pickupDistanceEnabled = true,
                highFareOverrideEnabled = true, highFareOverrideAmount = "35",
            ),
            600,
        )
        assertTrue(verdict.accept)
    }

    @Test
    fun invalidEnabledOverrideThresholdFailsClosedButDisabledInvalidValueIsIgnored() {
        val enabled = OfferEvaluator.evaluate(
            offer(), Filters(maxPickupKm = "1", pickupDistanceEnabled = true,
                highFareOverrideEnabled = true, highFareOverrideAmount = "not-a-number"), 600,
        )
        assertTrue(ReasonCode.INVALID_FILTER in codes(enabled))

        val disabled = OfferEvaluator.evaluate(
            offer(), Filters(minFare = "not-a-number", fareRangeEnabled = false,
                maxPickupKm = "2", pickupDistanceEnabled = true,
                highFareOverrideEnabled = false, highFareOverrideAmount = "not-a-number"), 600,
        )
        assertFalse(ReasonCode.INVALID_FILTER in codes(disabled))
        assertTrue(disabled.accept)
    }

    @Test
    fun disabledLimitsKeepTheirValuesWithoutBeingAppliedOrCountingAsActive() {
        val filters = Filters(
            minFare = "99", fareRangeEnabled = false,
            maxPickupKm = "0.1", pickupDistanceEnabled = false,
        )
        val verdict = OfferEvaluator.evaluate(offer(fare = "5", pickupKm = "8"), filters, 600)
        assertEquals(listOf(ReasonCode.NO_FILTERS), codes(verdict))
        assertEquals("99", filters.minFare)
        assertEquals("0.1", filters.maxPickupKm)
    }

    @Test
    fun noDestinationIsRejectedUnlessExplicitlyAllowedAndThenTripOnlyRulesAreSkipped() {
        val noDestinationOffer = offer(tripKm = null, tripMin = null, addresses = emptyList())
        val filters = Filters(
            minFare = "4", fareRangeEnabled = true,
            maxPickupKm = "2", pickupDistanceEnabled = true,
            maxTripKm = "10", tripDistanceEnabled = true,
            maxTripMinutes = 20, tripTimeEnabled = true,
        )
        assertTrue(ReasonCode.DESTINATION_MISSING in codes(OfferEvaluator.evaluate(noDestinationOffer, filters, 600)))

        val allowed = OfferEvaluator.evaluate(noDestinationOffer, filters.copy(acceptNoDestination = true), 600)
        assertTrue(allowed.accept)
    }

    @Test
    fun noDestinationPermissionAloneDoesNotDisableTheNoFiltersSafetyGate() {
        val verdict = OfferEvaluator.evaluate(
            offer(addresses = emptyList()), Filters(acceptNoDestination = true), 600,
        )
        assertEquals(listOf(ReasonCode.NO_FILTERS), codes(verdict))
    }

    @Test
    fun excludedKeywordsTemporarilyDisableNoDestinationAcceptanceWithoutBypassingKeywordRule() {
        val offer = offer(addresses = emptyList(), texts = listOf("airport pickup"))
        val filters = Filters(
            minFare = "4", fareRangeEnabled = true,
            acceptNoDestination = true,
            excludeKeywords = listOf("airport"),
        )
        val otherText = OfferEvaluator.evaluate(
            offer(addresses = emptyList(), texts = listOf("regular offer")), filters, 600,
        )
        assertFalse(otherText.accept)
        assertTrue(ReasonCode.DESTINATION_MISSING in codes(otherText))
        assertFalse(ReasonCode.EXCLUDED_KEYWORD in codes(otherText))

        val verdict = OfferEvaluator.evaluate(offer, filters, 600)
        assertFalse(verdict.accept)
        assertTrue(ReasonCode.DESTINATION_MISSING in codes(verdict))
        assertTrue(ReasonCode.EXCLUDED_KEYWORD in codes(verdict))
        assertTrue(filters.copy(excludeKeywords = emptyList()).isAcceptNoDestinationEffective)
    }

    @Test
    fun andModeRequiresEveryDeclaredSelectionGroupToPass() {
        val filters = Filters(
            minFare = "6", fareRangeEnabled = true,
            minTripKm = "8", tripDistanceEnabled = true,
            combination = FilterCombination.ALL,
        )
        val verdict = OfferEvaluator.evaluate(offer(fare = "5", tripKm = "10"), filters, 600)
        assertFalse(verdict.accept)
        assertEquals(listOf(ReasonCode.FARE_TOO_LOW), codes(verdict))
    }

    @Test
    fun orModeAcceptsEligibleOfferWhenAnySelectionGroupPasses() {
        val filters = Filters(
            minFare = "6", fareRangeEnabled = true,
            minTripKm = "8", tripDistanceEnabled = true,
            combination = FilterCombination.ANY,
        )
        val verdict = OfferEvaluator.evaluate(offer(fare = "5", tripKm = "10"), filters, 600)
        assertTrue("a valid trip-distance match must not be lost because another OR group failed", verdict.accept)
        assertTrue(verdict.reasons.isEmpty())
    }

    @Test
    fun orModeNeverBypassesScheduleOrExplicitExclusions() {
        val filters = Filters(
            minFare = "100", fareRangeEnabled = true,
            minTripKm = "1", tripDistanceEnabled = true,
            scheduleEnabled = true, scheduleStartMinute = 8 * 60, scheduleEndMinute = 9 * 60,
            excludeKeywords = listOf("airport"), combination = FilterCombination.ANY,
        )
        assertTrue(ReasonCode.OUTSIDE_SCHEDULE in codes(OfferEvaluator.evaluate(offer(), filters, 10 * 60)))
        val blocked = OfferEvaluator.evaluate(offer(texts = listOf("airport")), filters, 8 * 60 + 30)
        assertFalse(blocked.accept)
        assertTrue(ReasonCode.EXCLUDED_KEYWORD in codes(blocked))
    }

    @Test
    fun scheduleAndNonMatchingExclusionsAreGatesNotSuccessfulOrCriteria() {
        val filters = Filters(
            minFare = "6", fareRangeEnabled = true,
            minTripKm = "20", tripDistanceEnabled = true,
            scheduleEnabled = true, scheduleStartMinute = 8 * 60, scheduleEndMinute = 9 * 60,
            excludeKeywords = listOf("airport"), combination = FilterCombination.ANY,
        )
        val verdict = OfferEvaluator.evaluate(offer(fare = "5", tripKm = "10"), filters, 8 * 60 + 30)
        assertFalse("inside-hours and not-excluded are gates, not reasons to accept", verdict.accept)
        assertTrue(ReasonCode.FARE_TOO_LOW in codes(verdict))
        assertTrue(ReasonCode.TRIP_TOO_SHORT in codes(verdict))
    }

    @Test
    fun destinationIncludeAndExcludeZonesUseExplicitAddressAliases() {
        val center = GeoPoint(31.95, 35.91)
        val aliases = listOf(DestinationAlias("Destination address", center))
        val included = DestinationZone("home", "منطقة المنزل", center, 2_000, ZoneMode.INCLUDE)
        val filters = Filters(
            minFare = "4", fareRangeEnabled = true,
            destinationZones = listOf(included), destinationAliases = aliases,
        )
        assertTrue(OfferEvaluator.evaluate(offer(), filters, 600).accept)

        val excluded = included.copy(mode = ZoneMode.EXCLUDE)
        val blocked = OfferEvaluator.evaluate(offer(), filters.copy(destinationZones = listOf(excluded)), 600)
        assertFalse(blocked.accept)
        assertEquals(listOf(ReasonCode.DESTINATION_ZONE_EXCLUDED), codes(blocked))
    }

    @Test
    fun geographyFailsClosedAndReportsUnknownOrAmbiguousResolution() {
        val zone = DestinationZone("id", "allow", GeoPoint(31.95, 35.91), 2_000, ZoneMode.INCLUDE)
        val base = Filters(minFare = "4", fareRangeEnabled = true, destinationZones = listOf(zone))
        assertEquals(listOf(ReasonCode.DESTINATION_GEO_UNKNOWN), codes(OfferEvaluator.evaluate(offer(), base, 600)))

        val aliases = listOf(
            DestinationAlias("Destination address", GeoPoint(31.95, 35.91)),
            DestinationAlias("Destination address", GeoPoint(32.0, 36.0)),
        )
        val ambiguous = OfferEvaluator.evaluate(offer(), base.copy(destinationAliases = aliases), 600)
        assertFalse(ambiguous.accept)
        assertTrue(ReasonCode.INVALID_FILTER in codes(ambiguous))
        assertTrue(ReasonCode.DESTINATION_GEO_UNKNOWN in codes(ambiguous))
    }

    @Test
    fun mostSpecificDestinationAliasWinsOverItsBroaderParentPhrase() {
        val center = GeoPoint(31.95, 35.91)
        val zone = DestinationZone("home", "home", center, 500, ZoneMode.INCLUDE)
        val filters = Filters(
            minFare = "4", fareRangeEnabled = true,
            destinationZones = listOf(zone),
            destinationAliases = listOf(
                DestinationAlias("Destination", GeoPoint(20.0, 20.0)),
                DestinationAlias("Destination address", center),
            ),
        )
        assertTrue("specific address mapping must resolve rather than cause a false rejection", OfferEvaluator.evaluate(offer(), filters, 600).accept)
    }

    @Test
    fun preferredDestinationAndCorridorAcceptValidNearbyDestinations() {
        val point = GeoPoint(31.0001, 35.05)
        val direct = offer().copy(destinationPoint = point)
        val target = DestinationTarget(GeoPoint(31.0, 35.05), 500)
        val smart = Filters(minFare = "4", fareRangeEnabled = true, smartDestination = target)
        assertTrue(OfferEvaluator.evaluate(direct, smart, 600).accept)

        val corridor = DestinationCorridor(
            points = listOf(GeoPoint(31.0, 35.0), GeoPoint(31.0, 35.1)),
            halfWidthMeters = 500,
        )
        val routed = Filters(minFare = "4", fareRangeEnabled = true, destinationCorridor = corridor)
        assertTrue(OfferEvaluator.evaluate(direct, routed, 600).accept)
        assertTrue("eligible offer must not be rejected by corridor geometry", OfferEvaluator.evaluate(direct, routed.copy(combination = FilterCombination.ANY), 600).accept)
    }

    @Test
    fun malformedEnabledGeographyNeverSilentlyPasses() {
        val invalid = DestinationZone("", "", GeoPoint(200.0, 0.0), 0, ZoneMode.INCLUDE)
        val verdict = OfferEvaluator.evaluate(
            offer().copy(destinationPoint = GeoPoint(31.95, 35.91)),
            Filters(minFare = "4", destinationZones = listOf(invalid)), 600,
        )
        assertFalse(verdict.accept)
        assertTrue(ReasonCode.INVALID_FILTER in codes(verdict))
    }
}
