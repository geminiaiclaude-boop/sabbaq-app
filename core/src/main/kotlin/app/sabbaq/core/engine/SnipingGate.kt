package app.sabbaq.core.engine

/**
 * FareSniper's `isSnipingAllowedNow` (QANNAS_ENGINE_FACTS.md §4, A11y 900–961): the driver's
 * own switches and the subscription end date saved on the device, checked against the wall
 * clock — never a network call or a live session. Setting the clock back more than two hours
 * below the newest time ever seen stops it; a smaller jump only re-anchors that time.
 */
object SnipingGate {
    const val CLOCK_TAMPER_THRESHOLD_MS = 2L * 60 * 60 * 1000
    private const val REANCHOR_MS = 5_000L

    data class Result(val allowed: Boolean, val reason: String?, val newestSeenMs: Long)

    /** [subscriptionEndsAtMs] 0 = not known yet (allowed); a past date = expired. */
    fun check(
        monitoringWanted: Boolean,
        disclosureAccepted: Boolean,
        subscriptionEndsAtMs: Long,
        newestSeenMs: Long,
        nowMs: Long,
    ): Result {
        if (!disclosureAccepted) return Result(false, "disclosure_required", newestSeenMs)
        if (!monitoringWanted) return Result(false, "service_paused", newestSeenMs)
        val behind = newestSeenMs - nowMs
        if (behind > CLOCK_TAMPER_THRESHOLD_MS) return Result(false, "clock_tampered", newestSeenMs)
        val newest = when {
            behind > REANCHOR_MS -> nowMs
            nowMs > newestSeenMs -> nowMs
            else -> newestSeenMs
        }
        if (subscriptionEndsAtMs > 0 && nowMs > subscriptionEndsAtMs) return Result(false, "subscription_expired", newest)
        return Result(true, null, newest)
    }
}
