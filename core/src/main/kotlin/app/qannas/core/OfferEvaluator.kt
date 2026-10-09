package app.qannas.core

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
        listOf(minFare, maxFare, minFarePerKm, maxPickupKm, minTripKm, maxTripKm).any { it.toDecimalOrNull() != null } ||
            maxPickupMinutes != null || maxTripMinutes != null || excludeKeywords.any { it.isNotBlank() }
}

internal fun String?.toDecimalOrNull(): BigDecimal? = this?.let { Text.parseNumber(Text.normalize(it)) }

enum class ReasonCode {
    NO_FILTERS, OUTSIDE_SCHEDULE,
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
        val perKm = farePerKm(offer, filters.perKmBasis)

        if (filters.scheduleEnabled && !inSchedule(minuteOfDay, filters.scheduleStartMinute, filters.scheduleEndMinute)) {
            reasons += Reason(ReasonCode.OUTSIDE_SCHEDULE)
        }
        if (!filters.anyActive() && !filters.acceptWhenNoFilters) {
            reasons += Reason(ReasonCode.NO_FILTERS)
        }

        filters.minFare.toDecimalOrNull()?.let { min ->
            val fare = offer.fare
            if (fare == null) reasons += Reason(ReasonCode.FARE_MISSING)
            else if (fare < min) reasons += Reason(ReasonCode.FARE_TOO_LOW, fare.toPlainString())
        }
        filters.maxFare.toDecimalOrNull()?.let { max ->
            val fare = offer.fare
            if (fare == null) reasons += Reason(ReasonCode.FARE_MISSING)
            else if (fare > max) reasons += Reason(ReasonCode.FARE_TOO_HIGH, fare.toPlainString())
        }
        filters.minFarePerKm.toDecimalOrNull()?.let { min ->
            if (perKm == null) reasons += Reason(ReasonCode.PER_KM_MISSING)
            else if (perKm < min) reasons += Reason(ReasonCode.PER_KM_TOO_LOW, perKm.toPlainString())
        }
        filters.maxPickupKm.toDecimalOrNull()?.let { max ->
            val v = offer.pickupKm
            if (v == null) reasons += Reason(ReasonCode.PICKUP_KM_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.PICKUP_TOO_FAR, v.toPlainString())
        }
        filters.maxPickupMinutes?.let { max ->
            val v = offer.pickupMinutes
            if (v == null) reasons += Reason(ReasonCode.PICKUP_MIN_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.PICKUP_TOO_LONG, v.toString())
        }
        filters.minTripKm.toDecimalOrNull()?.let { min ->
            val v = offer.tripKm
            if (v == null) reasons += Reason(ReasonCode.TRIP_KM_MISSING)
            else if (v < min) reasons += Reason(ReasonCode.TRIP_TOO_SHORT, v.toPlainString())
        }
        filters.maxTripKm.toDecimalOrNull()?.let { max ->
            val v = offer.tripKm
            if (v == null) reasons += Reason(ReasonCode.TRIP_KM_MISSING)
            else if (v > max) reasons += Reason(ReasonCode.TRIP_TOO_LONG, v.toPlainString())
        }
        filters.maxTripMinutes?.let { max ->
            val v = offer.tripMinutes
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
        val fare = offer.fare ?: return null
        val km = when (basis) {
            PerKmBasis.TRIP -> offer.tripKm
            PerKmBasis.TOTAL -> {
                val trip = offer.tripKm ?: return null
                trip + (offer.pickupKm ?: return null)
            }
        } ?: return null
        if (km.signum() <= 0) return null
        return fare.divide(km, 3, RoundingMode.HALF_UP)
    }

    /** Windows may wrap midnight, e.g. 22:00 → 04:00. */
    fun inSchedule(minuteOfDay: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> minuteOfDay in start until end
        else -> minuteOfDay >= start || minuteOfDay < end
    }
}
