package app.sabbaq.core

import kotlinx.serialization.Serializable

/** Persistable per-day aggregates only; deliberately contains no offer or passenger data. */
@Serializable
data class OfferStatsDay(
    val date: String,
    val visibleOffers: Long = 0,
    val rejectedOffers: Long = 0,
    val acceptedOffers: Long = 0,
    val rejectionsByReason: Map<String, Long> = emptyMap(),
) {
    fun recordVisible(): OfferStatsDay = copy(visibleOffers = visibleOffers.increment())

    fun recordRejected(reasons: Collection<String>): OfferStatsDay {
        if (reasons.isEmpty()) return this
        val reasonCounts = rejectionsByReason.toMutableMap()
        reasons.distinct().forEach { reason ->
            reasonCounts[reason] = (reasonCounts[reason] ?: 0).increment()
        }
        return copy(
            rejectedOffers = rejectedOffers.increment(),
            rejectionsByReason = reasonCounts,
        )
    }

    fun recordAccepted(): OfferStatsDay = copy(acceptedOffers = acceptedOffers.increment())

    private fun Long.increment(): Long = if (this == Long.MAX_VALUE) this else this + 1
}

@Serializable
data class OfferStatsTotals(
    val visibleOffers: Long = 0,
    val rejectedOffers: Long = 0,
    val acceptedOffers: Long = 0,
    val rejectionsByReason: Map<String, Long> = emptyMap(),
) {
    fun topRejectionReasons(limit: Int = 3): List<Pair<String, Long>> {
        if (limit <= 0) return emptyList()
        return rejectionsByReason.entries
            .asSequence()
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }
            .toList()
    }

    companion object {
        fun from(days: Iterable<OfferStatsDay>): OfferStatsTotals {
            var visible = 0L
            var rejected = 0L
            var accepted = 0L
            val reasons = mutableMapOf<String, Long>()
            for (day in days) {
                visible = visible.saturatingAdd(day.visibleOffers.coerceAtLeast(0))
                rejected = rejected.saturatingAdd(day.rejectedOffers.coerceAtLeast(0))
                accepted = accepted.saturatingAdd(day.acceptedOffers.coerceAtLeast(0))
                day.rejectionsByReason.forEach { (reason, count) ->
                    reasons[reason] = (reasons[reason] ?: 0L).saturatingAdd(count.coerceAtLeast(0))
                }
            }
            return OfferStatsTotals(visible, rejected, accepted, reasons)
        }

        private fun Long.saturatingAdd(other: Long): Long =
            if (Long.MAX_VALUE - this < other) Long.MAX_VALUE else this + other
    }
}
