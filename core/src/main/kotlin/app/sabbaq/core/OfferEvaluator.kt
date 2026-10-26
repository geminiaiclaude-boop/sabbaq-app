package app.sabbaq.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
enum class PerKmBasis { TRIP, TOTAL }

@Serializable
enum class FilterCombination { ALL, ANY }

/**
 * Driver's acceptance rules. Money and distances are kept as decimal strings
 * so a stored threshold is never rounded through a binary float.
 */
@Serializable
data class Filters(
    val minFare: String? = null,
    val maxFare: String? = null,
    /** Null preserves the legacy rule: a stored value is active. */
    val fareRangeEnabled: Boolean? = null,
    val minFarePerKm: String? = null,
    val farePerKmEnabled: Boolean? = null,
    val perKmBasis: PerKmBasis = PerKmBasis.TRIP,
    val maxPickupKm: String? = null,
    val pickupDistanceEnabled: Boolean? = null,
    val minPickupMinutes: Int? = null,
    val maxPickupMinutes: Int? = null,
    val pickupTimeEnabled: Boolean? = null,
    val minTripKm: String? = null,
    val maxTripKm: String? = null,
    val tripDistanceEnabled: Boolean? = null,
    val minTripMinutes: Int? = null,
    val maxTripMinutes: Int? = null,
    val tripTimeEnabled: Boolean? = null,
    val highFareOverrideEnabled: Boolean = false,
    val highFareOverrideAmount: String = "35",
    val acceptNoDestination: Boolean = false,
    val excludeKeywords: List<String> = emptyList(),
    val scheduleEnabled: Boolean = false,
    val scheduleStartMinute: Int = 0,
    val scheduleEndMinute: Int = 24 * 60,
    val acceptWhenNoFilters: Boolean = false,
    val combination: FilterCombination = FilterCombination.ALL,
    val destinationZones: List<DestinationZone> = emptyList(),
    val smartDestination: DestinationTarget? = null,
    val destinationCorridor: DestinationCorridor? = null,
    val destinationAliases: List<DestinationAlias> = emptyList(),
) {
    val isFareRangeEnabled: Boolean get() = fareRangeEnabled ?: (!minFare.isNullOrBlank() || !maxFare.isNullOrBlank())
    val isFarePerKmEnabled: Boolean get() = farePerKmEnabled ?: !minFarePerKm.isNullOrBlank()
    val isPickupDistanceEnabled: Boolean get() = pickupDistanceEnabled ?: !maxPickupKm.isNullOrBlank()
    val isPickupTimeEnabled: Boolean get() = pickupTimeEnabled ?: (minPickupMinutes != null || maxPickupMinutes != null)
    val isTripDistanceEnabled: Boolean get() = tripDistanceEnabled ?: (!minTripKm.isNullOrBlank() || !maxTripKm.isNullOrBlank())
    val isTripTimeEnabled: Boolean get() = tripTimeEnabled ?: (minTripMinutes != null || maxTripMinutes != null)
    /** Exclusion terms take precedence; retain the driver's stored choice while they are present. */
    val isAcceptNoDestinationEffective: Boolean
        get() = acceptNoDestination && excludeKeywords.none { it.isNotBlank() }

    /** A relaxation toggle is not a selection rule; require at least one actual acceptance criterion. */
    fun anyActive(): Boolean =
        (isFareRangeEnabled && (!minFare.isNullOrBlank() || !maxFare.isNullOrBlank())) ||
            (isFarePerKmEnabled && !minFarePerKm.isNullOrBlank()) ||
            (isPickupDistanceEnabled && !maxPickupKm.isNullOrBlank()) ||
            (isPickupTimeEnabled && ((minPickupMinutes != null && minPickupMinutes > 0) || maxPickupMinutes != null)) ||
            (isTripDistanceEnabled && (!minTripKm.isNullOrBlank() || !maxTripKm.isNullOrBlank())) ||
            (isTripTimeEnabled && ((minTripMinutes != null && minTripMinutes > 0) || maxTripMinutes != null)) ||
            excludeKeywords.any { it.isNotBlank() } || scheduleEnabled ||
            destinationZones.any { it.enabled } || smartDestination != null || destinationCorridor != null
}

internal fun String?.toDecimalOrNull(): BigDecimal? = this?.let { Text.parseNumber(Text.normalize(it)) }

@Serializable
enum class ReasonCode {
    NO_FILTERS, OUTSIDE_SCHEDULE,
    INVALID_FILTER,
    FARE_MISSING, FARE_TOO_LOW, FARE_TOO_HIGH,
    PER_KM_MISSING, PER_KM_TOO_LOW,
    PICKUP_KM_MISSING, PICKUP_TOO_FAR,
    PICKUP_MIN_MISSING, PICKUP_TOO_LONG,
    PICKUP_TOO_SHORT,
    DESTINATION_MISSING,
    TRIP_KM_MISSING, TRIP_TOO_SHORT, TRIP_TOO_LONG,
    TRIP_MIN_MISSING, TRIP_MIN_TOO_SHORT, TRIP_MIN_TOO_LONG,
    EXCLUDED_KEYWORD,
    DESTINATION_GEO_UNKNOWN, DESTINATION_ZONE_EXCLUDED, DESTINATION_ZONE_NOT_MATCHED,
    SMART_DESTINATION_MISMATCH, DESTINATION_CORRIDOR_MISMATCH,
}

data class Reason(val code: ReasonCode, val detail: String? = null)

data class DecisionReport(
    val score: Int,
    val availableSignals: List<String>,
    val reasons: List<ReasonCode>,
)

data class Verdict(
    val accept: Boolean,
    val reasons: List<Reason>,
    val farePerKm: BigDecimal?,
    val report: DecisionReport = DecisionReport(0, emptyList(), emptyList()),
)

/**
 * Applies [Filters] to an [Offer]. A rule the driver enabled is never treated
 * as passed when the screen did not show the value it needs: an unreadable
 * offer is skipped, not accepted.
 */
object OfferEvaluator {

    fun evaluate(offer: Offer, filters: Filters, minuteOfDay: Int): Verdict {
        val reasons = ArrayList<Reason>()
        val limits = filters.validated()
        val perKm = farePerKm(offer, filters.perKmBasis)
        val fareOverrideApplies = filters.highFareOverrideEnabled &&
            limits.highFareOverrideAmount?.let { threshold -> offer.nonNegativeFare()?.let { it >= threshold } } == true
        val noDestination = !offer.destinationAvailable
        val allowNoDestination = filters.isAcceptNoDestinationEffective
        val geoRulesActive = filters.destinationZones.any { it.enabled } ||
            filters.smartDestination != null || filters.destinationCorridor != null
        val destinationPoint = resolveDestinationPoint(offer, filters.destinationAliases)

        limits.invalid.firstOrNull()?.let { reasons += Reason(ReasonCode.INVALID_FILTER, it) }
        validateDestinationRules(filters)?.let { reasons += Reason(ReasonCode.INVALID_FILTER, it) }
        if (filters.scheduleEnabled && limits.scheduleIsValid && !inSchedule(minuteOfDay, filters.scheduleStartMinute, filters.scheduleEndMinute)) {
            reasons += Reason(ReasonCode.OUTSIDE_SCHEDULE)
        }
        if (!filters.anyActive() && !filters.acceptWhenNoFilters) {
            reasons += Reason(ReasonCode.NO_FILTERS)
        }
        if (noDestination && !allowNoDestination) {
            reasons += Reason(ReasonCode.DESTINATION_MISSING)
        }
        if (geoRulesActive && destinationPoint == null) {
            reasons += Reason(ReasonCode.DESTINATION_GEO_UNKNOWN)
        }
        if (destinationPoint != null) {
            Zones.firstHit(filters.destinationZones, destinationPoint)?.let {
                reasons += Reason(ReasonCode.DESTINATION_ZONE_EXCLUDED, it.name)
            }
            if (filters.destinationZones.any { it.enabled && it.mode == ZoneMode.INCLUDE } &&
                !Zones.matchesIncludeZones(filters.destinationZones, destinationPoint)) {
                reasons += Reason(ReasonCode.DESTINATION_ZONE_NOT_MATCHED)
            }
            filters.smartDestination?.let { target ->
                if (Zones.isValidTarget(target) && !Zones.isNearTarget(destinationPoint, target)) {
                    reasons += Reason(ReasonCode.SMART_DESTINATION_MISMATCH)
                }
            }
            filters.destinationCorridor?.let { corridor ->
                if (Zones.isValidCorridor(corridor) && !Zones.isInsideCorridor(destinationPoint, corridor)) {
                    reasons += Reason(ReasonCode.DESTINATION_CORRIDOR_MISMATCH)
                }
            }
        }

        limits.minFare?.let { min ->
            val fare = offer.nonNegativeFare()
            if (fare == null) reasons += Reason(ReasonCode.FARE_MISSING)
            else if (fare < min) reasons += Reason(ReasonCode.FARE_TOO_LOW, fare.toPlainString())
        }
        limits.maxFare?.let { max ->
            val fare = offer.nonNegativeFare()
            if (fare == null) reasons += Reason(ReasonCode.FARE_MISSING)
            else if (fare > max) reasons += Reason(ReasonCode.FARE_TOO_HIGH, fare.toPlainString())
        }
        limits.minFarePerKm?.let { min ->
            if (perKm == null) reasons += Reason(ReasonCode.PER_KM_MISSING)
            else if (!meetsMinimumFarePerKm(offer, filters.perKmBasis, min)) reasons += Reason(ReasonCode.PER_KM_TOO_LOW, perKm.toPlainString())
        }
        if (!fareOverrideApplies) limits.maxPickupKm?.let { max ->
            val v = offer.nonNegativePickupKm()
            if (v == null) reasons += Reason(ReasonCode.PICKUP_KM_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.PICKUP_TOO_FAR, v.toPlainString())
        }
        limits.minPickupMinutes?.takeIf { it > 0 }?.let { min ->
            val v = offer.pickupMinutes?.takeIf { it >= 0 }
            if (v == null) reasons += Reason(ReasonCode.PICKUP_MIN_MISSING)
            else if (v < min) reasons += Reason(ReasonCode.PICKUP_TOO_SHORT, v.toString())
        }
        if (!fareOverrideApplies) limits.maxPickupMinutes?.let { max ->
            val v = offer.pickupMinutes?.takeIf { it >= 0 }
            if (v == null) reasons += Reason(ReasonCode.PICKUP_MIN_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.PICKUP_TOO_LONG, v.toString())
        }
        if (!noDestination || !allowNoDestination) limits.minTripKm?.let { min ->
            val v = offer.nonNegativeTripKm()
            if (v == null) reasons += Reason(ReasonCode.TRIP_KM_MISSING)
            else if (v < min) reasons += Reason(ReasonCode.TRIP_TOO_SHORT, v.toPlainString())
        }
        if (!noDestination || !allowNoDestination) limits.maxTripKm?.let { max ->
            val v = offer.nonNegativeTripKm()
            if (v == null) reasons += Reason(ReasonCode.TRIP_KM_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.TRIP_TOO_LONG, v.toPlainString())
        }
        if (!noDestination || !allowNoDestination) {
            limits.minTripMinutes?.takeIf { it > 0 }?.let { min ->
                val v = offer.tripMinutes?.takeIf { it >= 0 }
                if (v == null) reasons += Reason(ReasonCode.TRIP_MIN_MISSING)
                else if (v < min) reasons += Reason(ReasonCode.TRIP_MIN_TOO_SHORT, v.toString())
            }
            if (!fareOverrideApplies) limits.maxTripMinutes?.let { max ->
                val v = offer.tripMinutes?.takeIf { it >= 0 }
                if (v == null) reasons += Reason(ReasonCode.TRIP_MIN_MISSING)
                else if (v > max) reasons += Reason(ReasonCode.TRIP_MIN_TOO_LONG, v.toString())
            }
        }
        val keywords = filters.excludeKeywords.map(Text::normalize).filter { it.isNotEmpty() }
        keywords.firstOrNull { kw -> offer.texts.any { Text.containsWord(it, kw) } }?.let {
            reasons += Reason(ReasonCode.EXCLUDED_KEYWORD, it)
        }

        val distinct = reasons.distinctBy { it.code }
        val hardCodes = setOf(
            ReasonCode.NO_FILTERS, ReasonCode.OUTSIDE_SCHEDULE, ReasonCode.INVALID_FILTER,
            ReasonCode.DESTINATION_MISSING, ReasonCode.EXCLUDED_KEYWORD,
            ReasonCode.DESTINATION_GEO_UNKNOWN, ReasonCode.DESTINATION_ZONE_EXCLUDED,
        )
        val hardFailures = distinct.filter { it.code in hardCodes }
        val selectionGroups = activeSelectionGroups(filters, limits, minuteOfDay)
        if (filters.combination == FilterCombination.ANY && hardFailures.isEmpty() && selectionGroups.isNotEmpty()) {
            val failedCodes = distinct.mapTo(HashSet()) { it.code }
            val passed = selectionGroups.any { group -> group.none { it in failedCodes } }
            val finalReasons = if (passed) emptyList() else distinct
            return Verdict(accept = passed, reasons = finalReasons, farePerKm = perKm,
                report = decisionReport(offer, filters, distinct, perKm))
        }
        return Verdict(accept = distinct.isEmpty(), reasons = distinct, farePerKm = perKm,
            report = decisionReport(offer, filters, distinct, perKm))
    }

    /** Explainability only; this report is not an acceptance gate. Missing values earn no points. */
    private fun decisionReport(offer: Offer, filters: Filters, reasons: List<Reason>, perKm: BigDecimal?): DecisionReport {
        val signals = buildList {
            if (offer.fare != null) add("fare")
            if (offer.pickupMinutes != null || offer.tripMinutes != null) add("time")
            if (offer.pickupKm != null || offer.tripKm != null) add("distance")
            if (offer.destinationAvailable) add("destination")
            if (perKm != null) add("fare_per_km")
            if (filters.destinationZones.any { it.enabled } || filters.smartDestination != null || filters.destinationCorridor != null) {
                if (offer.destinationAvailable) add("destination_filter")
            }
        }
        val raw = (if (offer.fare != null) 25 else 0) +
            (if (offer.pickupMinutes != null || offer.tripMinutes != null) 15 else 0) +
            (if (offer.pickupKm != null || offer.tripKm != null) 15 else 0) +
            (if (offer.destinationAvailable) 20 else 0) +
            (if (perKm != null) 10 else 0) +
            (if ("destination_filter" in signals) 15 else 0)
        val penalty = reasons.count { it.code in setOf(
            ReasonCode.FARE_MISSING, ReasonCode.PER_KM_MISSING, ReasonCode.PICKUP_KM_MISSING,
            ReasonCode.PICKUP_MIN_MISSING, ReasonCode.TRIP_KM_MISSING, ReasonCode.TRIP_MIN_MISSING,
            ReasonCode.DESTINATION_MISSING, ReasonCode.DESTINATION_GEO_UNKNOWN,
        ) } * 5
        return DecisionReport((raw - penalty).coerceIn(0, 100), signals, reasons.map { it.code })
    }

    private fun resolveDestinationPoint(offer: Offer, aliases: List<DestinationAlias>): GeoPoint? {
        offer.destinationPoint?.takeIf { it.isValid }?.let { return it }
        val destinationText = offer.addresses.lastOrNull()?.let(Text::normalize)?.takeIf(String::isNotEmpty) ?: return null
        val matchingAliases = aliases.asSequence()
            .filter { it.phrase.isNotBlank() && it.point.isValid && Text.normalize(it.phrase).isNotBlank() &&
                Text.containsWord(destinationText, Text.normalize(it.phrase)) }
            .toList()
        val longest = matchingAliases.maxOfOrNull { Text.normalize(it.phrase).length } ?: return null
        val matches = matchingAliases.asSequence()
            .filter { Text.normalize(it.phrase).length == longest }
            .map { it.point }
            .distinct()
            .take(2)
            .toList()
        return matches.singleOrNull()
    }

    private fun validateDestinationRules(filters: Filters): String? {
        val enabledZones = filters.destinationZones.filter { it.enabled }
        if (filters.destinationZones.size > Zones.MAX_ZONES || enabledZones.any { !Zones.isValidZone(it) }) return "destinationZones"
        if (enabledZones.map { it.id }.distinct().size != enabledZones.size) return "duplicateDestinationZoneIds"
        if (filters.destinationAliases.size > Zones.MAX_ZONES * 4 || filters.destinationAliases.any {
                it.phrase.isBlank() || it.phrase.length > 120 || !it.point.isValid
            }) return "destinationAliases"
        if (filters.destinationAliases.groupBy { Text.normalize(it.phrase) }.values.any { same ->
                same.map { it.point }.distinct().size > 1
            }) return "ambiguousDestinationAliases"
        if (filters.smartDestination?.let { !Zones.isValidTarget(it) } == true) return "smartDestination"
        if (filters.destinationCorridor?.let { !Zones.isValidCorridor(it) } == true) return "destinationCorridor"
        return null
    }

    /** Hard safety gates stay conjunctive; ANY combines only explicit selection groups. */
    private fun activeSelectionGroups(
        filters: Filters,
        limits: ValidatedFilters,
        minuteOfDay: Int,
    ): List<Set<ReasonCode>> {
        val groups = ArrayList<Set<ReasonCode>>()
        if (limits.minFare != null || limits.maxFare != null) groups.add(setOf(
            ReasonCode.FARE_MISSING, ReasonCode.FARE_TOO_LOW, ReasonCode.FARE_TOO_HIGH,
        ))
        if (limits.minFarePerKm != null) groups.add(setOf(ReasonCode.PER_KM_MISSING, ReasonCode.PER_KM_TOO_LOW))
        if (limits.maxPickupKm != null) groups.add(setOf(ReasonCode.PICKUP_KM_MISSING, ReasonCode.PICKUP_TOO_FAR))
        if (limits.minPickupMinutes != null || limits.maxPickupMinutes != null) groups.add(setOf(
            ReasonCode.PICKUP_MIN_MISSING, ReasonCode.PICKUP_TOO_LONG, ReasonCode.PICKUP_TOO_SHORT,
        ))
        if (limits.minTripKm != null || limits.maxTripKm != null) groups.add(setOf(
            ReasonCode.TRIP_KM_MISSING, ReasonCode.TRIP_TOO_SHORT, ReasonCode.TRIP_TOO_LONG,
        ))
        if (limits.minTripMinutes != null || limits.maxTripMinutes != null) groups.add(setOf(
            ReasonCode.TRIP_MIN_MISSING, ReasonCode.TRIP_MIN_TOO_SHORT, ReasonCode.TRIP_MIN_TOO_LONG,
        ))
        if (filters.destinationZones.any { it.enabled && it.mode == ZoneMode.INCLUDE }) groups.add(setOf(ReasonCode.DESTINATION_ZONE_NOT_MATCHED))
        if (filters.smartDestination != null) groups.add(setOf(ReasonCode.SMART_DESTINATION_MISMATCH))
        if (filters.destinationCorridor != null) groups.add(setOf(ReasonCode.DESTINATION_CORRIDOR_MISMATCH))
        // Schedule and exclusions are gates, never OR-success criteria when a
        // positive selection rule exists. Preserve the established behavior
        // for configurations that intentionally contain only these gates.
        if (groups.isEmpty() && (
                filters.scheduleEnabled && inSchedule(minuteOfDay, filters.scheduleStartMinute, filters.scheduleEndMinute) ||
                    filters.excludeKeywords.any { it.isNotBlank() } ||
                    filters.destinationZones.any { it.enabled && it.mode == ZoneMode.EXCLUDE }
            )) groups.add(emptySet())
        return groups
    }

    fun farePerKm(offer: Offer, basis: PerKmBasis): BigDecimal? {
        val fare = offer.nonNegativeFare() ?: return null
        val km = offer.kilometresFor(basis) ?: return null
        if (km.signum() <= 0) return null
        return fare.divide(km, 3, RoundingMode.HALF_UP)
    }

    /** Windows may wrap midnight, e.g. 22:00 → 04:00. */
    fun inSchedule(minuteOfDay: Int, start: Int, end: Int): Boolean {
        if (minuteOfDay !in 0 until MINUTES_PER_DAY || !scheduleBoundsAreValid(start, end)) return false
        return when {
        start == end -> true
        start < end -> minuteOfDay in start until end
        else -> minuteOfDay >= start || minuteOfDay < end
        }
    }

    private fun meetsMinimumFarePerKm(offer: Offer, basis: PerKmBasis, minimum: BigDecimal): Boolean {
        val fare = offer.nonNegativeFare() ?: return false
        val km = offer.kilometresFor(basis)?.takeIf { it.signum() > 0 } ?: return false
        return fare >= minimum.multiply(km)
    }

    private fun Offer.nonNegativeFare(): BigDecimal? = fare?.takeIf { it.signum() >= 0 }

    private fun Offer.nonNegativePickupKm(): BigDecimal? = pickupKm?.takeIf { it.signum() >= 0 }

    private fun Offer.nonNegativeTripKm(): BigDecimal? = tripKm?.takeIf { it.signum() >= 0 }

    private fun Offer.kilometresFor(basis: PerKmBasis): BigDecimal? {
        return when (basis) {
            PerKmBasis.TRIP -> nonNegativeTripKm()
            PerKmBasis.TOTAL -> {
                val trip = nonNegativeTripKm() ?: return null
                trip + (nonNegativePickupKm() ?: return null)
            }
        }
    }

    private fun Filters.validated(): ValidatedFilters {
        val invalid = ArrayList<String>()
        val fareRangeEnabled = isFareRangeEnabled
        val farePerKmEnabled = isFarePerKmEnabled
        val pickupDistanceEnabled = isPickupDistanceEnabled
        val pickupTimeEnabled = isPickupTimeEnabled
        val tripDistanceEnabled = isTripDistanceEnabled
        val tripTimeEnabled = isTripTimeEnabled
        val highFareOverrideEnabled = this.highFareOverrideEnabled
        fun decimal(name: String, value: String?, enabled: Boolean = true): BigDecimal? {
            if (!enabled) return null
            if (value.isNullOrBlank()) return null
            val parsed = value.toDecimalOrNull()
            if (parsed == null || parsed.signum() < 0) {
                invalid += name
                return null
            }
            return parsed
        }
        fun minutes(name: String, value: Int?, enabled: Boolean = true): Int? {
            if (!enabled) return null
            if (value != null && value < 0) invalid += name
            return value?.takeIf { it >= 0 }
        }

        val minFare = decimal("minFare", minFare, fareRangeEnabled)
        val maxFare = decimal("maxFare", maxFare, fareRangeEnabled)
        val minFarePerKm = decimal("minFarePerKm", minFarePerKm, farePerKmEnabled)
        val maxPickupKm = decimal("maxPickupKm", maxPickupKm, pickupDistanceEnabled)
        val minTripKm = decimal("minTripKm", minTripKm, tripDistanceEnabled)
        val maxTripKm = decimal("maxTripKm", maxTripKm, tripDistanceEnabled)
        val highFareOverrideAmount = decimal(
            "highFareOverrideAmount", highFareOverrideAmount, highFareOverrideEnabled,
        )
        val minPickupMinutes = minutes("minPickupMinutes", this.minPickupMinutes, pickupTimeEnabled)
        val maxPickupMinutes = minutes("maxPickupMinutes", this.maxPickupMinutes, pickupTimeEnabled)
        val minTripMinutes = minutes("minTripMinutes", this.minTripMinutes, tripTimeEnabled)
        val maxTripMinutes = minutes("maxTripMinutes", this.maxTripMinutes, tripTimeEnabled)
        if (minFare != null && maxFare != null && minFare > maxFare) invalid += "fareRange"
        if (minTripKm != null && maxTripKm != null && minTripKm > maxTripKm) invalid += "tripKmRange"
        if (minPickupMinutes != null && maxPickupMinutes != null && minPickupMinutes > maxPickupMinutes) invalid += "pickupTimeRange"
        if (minTripMinutes != null && maxTripMinutes != null && minTripMinutes > maxTripMinutes) invalid += "tripTimeRange"
        val scheduleIsValid = !scheduleEnabled || scheduleBoundsAreValid(scheduleStartMinute, scheduleEndMinute)
        if (!scheduleIsValid) invalid += "schedule"

        return ValidatedFilters(
            minFare = minFare,
            maxFare = maxFare,
            minFarePerKm = minFarePerKm,
            maxPickupKm = maxPickupKm,
            minPickupMinutes = minPickupMinutes,
            maxPickupMinutes = maxPickupMinutes,
            minTripKm = minTripKm,
            maxTripKm = maxTripKm,
            minTripMinutes = minTripMinutes,
            maxTripMinutes = maxTripMinutes,
            highFareOverrideAmount = highFareOverrideAmount,
            scheduleIsValid = scheduleIsValid,
            invalid = invalid,
        )
    }

    private data class ValidatedFilters(
        val minFare: BigDecimal?,
        val maxFare: BigDecimal?,
        val minFarePerKm: BigDecimal?,
        val maxPickupKm: BigDecimal?,
        val minPickupMinutes: Int?,
        val maxPickupMinutes: Int?,
        val minTripKm: BigDecimal?,
        val maxTripKm: BigDecimal?,
        val minTripMinutes: Int?,
        val maxTripMinutes: Int?,
        val highFareOverrideAmount: BigDecimal?,
        val scheduleIsValid: Boolean,
        val invalid: List<String>,
    )

    private fun scheduleBoundsAreValid(start: Int, end: Int): Boolean =
        start in 0 until MINUTES_PER_DAY && end in 0..MINUTES_PER_DAY

    private const val MINUTES_PER_DAY = 24 * 60
}
