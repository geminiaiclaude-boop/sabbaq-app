package app.sabbaq.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
enum class PerKmBasis { TRIP, TOTAL }

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
            excludeKeywords.any { it.isNotBlank() } || scheduleEnabled
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
}

data class Reason(val code: ReasonCode, val detail: String? = null)

data class Verdict(val accept: Boolean, val reasons: List<Reason>, val farePerKm: BigDecimal?)

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

        limits.invalid.firstOrNull()?.let { reasons += Reason(ReasonCode.INVALID_FILTER, it) }
        if (filters.scheduleEnabled && limits.scheduleIsValid && !inSchedule(minuteOfDay, filters.scheduleStartMinute, filters.scheduleEndMinute)) {
            reasons += Reason(ReasonCode.OUTSIDE_SCHEDULE)
        }
        if (!filters.anyActive() && !filters.acceptWhenNoFilters) {
            reasons += Reason(ReasonCode.NO_FILTERS)
        }
        if (noDestination && !allowNoDestination) {
            reasons += Reason(ReasonCode.DESTINATION_MISSING)
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
        return Verdict(accept = distinct.isEmpty(), reasons = distinct, farePerKm = perKm)
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
