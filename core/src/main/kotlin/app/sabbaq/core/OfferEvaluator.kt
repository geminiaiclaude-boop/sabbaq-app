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
    val minFarePerKm: String? = null,
    val perKmBasis: PerKmBasis = PerKmBasis.TRIP,
    val maxPickupKm: String? = null,
    val maxPickupMinutes: Int? = null,
    val minTripKm: String? = null,
    val maxTripKm: String? = null,
    val maxTripMinutes: Int? = null,
    val excludeKeywords: List<String> = emptyList(),
    val scheduleEnabled: Boolean = false,
    val scheduleStartMinute: Int = 0,
    val scheduleEndMinute: Int = 24 * 60,
    val acceptWhenNoFilters: Boolean = false,
) {
    fun anyActive(): Boolean =
        listOf(minFare, maxFare, minFarePerKm, maxPickupKm, minTripKm, maxTripKm).any { !it.isNullOrBlank() } ||
            maxPickupMinutes != null || maxTripMinutes != null || excludeKeywords.any { it.isNotBlank() } || scheduleEnabled
}

internal fun String?.toDecimalOrNull(): BigDecimal? = this?.let { Text.parseNumber(Text.normalize(it)) }

enum class ReasonCode {
    NO_FILTERS, OUTSIDE_SCHEDULE,
    INVALID_FILTER,
    FARE_MISSING, FARE_TOO_LOW, FARE_TOO_HIGH,
    PER_KM_MISSING, PER_KM_TOO_LOW,
    PICKUP_KM_MISSING, PICKUP_TOO_FAR,
    PICKUP_MIN_MISSING, PICKUP_TOO_LONG,
    TRIP_KM_MISSING, TRIP_TOO_SHORT, TRIP_TOO_LONG,
    TRIP_MIN_MISSING, TRIP_MIN_TOO_LONG,
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

        limits.invalid.firstOrNull()?.let { reasons += Reason(ReasonCode.INVALID_FILTER, it) }
        if (filters.scheduleEnabled && limits.scheduleIsValid && !inSchedule(minuteOfDay, filters.scheduleStartMinute, filters.scheduleEndMinute)) {
            reasons += Reason(ReasonCode.OUTSIDE_SCHEDULE)
        }
        if (!filters.anyActive() && !filters.acceptWhenNoFilters) {
            reasons += Reason(ReasonCode.NO_FILTERS)
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
        limits.maxPickupKm?.let { max ->
            val v = offer.nonNegativePickupKm()
            if (v == null) reasons += Reason(ReasonCode.PICKUP_KM_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.PICKUP_TOO_FAR, v.toPlainString())
        }
        limits.maxPickupMinutes?.let { max ->
            val v = offer.pickupMinutes?.takeIf { it >= 0 }
            if (v == null) reasons += Reason(ReasonCode.PICKUP_MIN_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.PICKUP_TOO_LONG, v.toString())
        }
        limits.minTripKm?.let { min ->
            val v = offer.nonNegativeTripKm()
            if (v == null) reasons += Reason(ReasonCode.TRIP_KM_MISSING)
            else if (v < min) reasons += Reason(ReasonCode.TRIP_TOO_SHORT, v.toPlainString())
        }
        limits.maxTripKm?.let { max ->
            val v = offer.nonNegativeTripKm()
            if (v == null) reasons += Reason(ReasonCode.TRIP_KM_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.TRIP_TOO_LONG, v.toPlainString())
        }
        limits.maxTripMinutes?.let { max ->
            val v = offer.tripMinutes?.takeIf { it >= 0 }
            if (v == null) reasons += Reason(ReasonCode.TRIP_MIN_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.TRIP_MIN_TOO_LONG, v.toString())
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
        fun decimal(name: String, value: String?): BigDecimal? {
            if (value.isNullOrBlank()) return null
            val parsed = value.toDecimalOrNull()
            if (parsed == null || parsed.signum() < 0) {
                invalid += name
                return null
            }
            return parsed
        }
        fun minutes(name: String, value: Int?): Int? {
            if (value != null && value < 0) invalid += name
            return value?.takeIf { it >= 0 }
        }

        val minFare = decimal("minFare", minFare)
        val maxFare = decimal("maxFare", maxFare)
        val minFarePerKm = decimal("minFarePerKm", minFarePerKm)
        val maxPickupKm = decimal("maxPickupKm", maxPickupKm)
        val minTripKm = decimal("minTripKm", minTripKm)
        val maxTripKm = decimal("maxTripKm", maxTripKm)
        if (minFare != null && maxFare != null && minFare > maxFare) invalid += "fareRange"
        if (minTripKm != null && maxTripKm != null && minTripKm > maxTripKm) invalid += "tripKmRange"
        val scheduleIsValid = !scheduleEnabled || scheduleBoundsAreValid(scheduleStartMinute, scheduleEndMinute)
        if (!scheduleIsValid) invalid += "schedule"

        return ValidatedFilters(
            minFare = minFare,
            maxFare = maxFare,
            minFarePerKm = minFarePerKm,
            maxPickupKm = maxPickupKm,
            maxPickupMinutes = minutes("maxPickupMinutes", maxPickupMinutes),
            minTripKm = minTripKm,
            maxTripKm = maxTripKm,
            maxTripMinutes = minutes("maxTripMinutes", maxTripMinutes),
            scheduleIsValid = scheduleIsValid,
            invalid = invalid,
        )
    }

    private data class ValidatedFilters(
        val minFare: BigDecimal?,
        val maxFare: BigDecimal?,
        val minFarePerKm: BigDecimal?,
        val maxPickupKm: BigDecimal?,
        val maxPickupMinutes: Int?,
        val minTripKm: BigDecimal?,
        val maxTripKm: BigDecimal?,
        val maxTripMinutes: Int?,
        val scheduleIsValid: Boolean,
        val invalid: List<String>,
    )

    private fun scheduleBoundsAreValid(start: Int, end: Int): Boolean =
        start in 0 until MINUTES_PER_DAY && end in 0..MINUTES_PER_DAY

    private const val MINUTES_PER_DAY = 24 * 60
}
