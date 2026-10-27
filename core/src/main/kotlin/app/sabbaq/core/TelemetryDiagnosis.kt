package app.sabbaq.core

/**
 * Turns the on-device funnel into the single most likely reason offers are not being handled,
 * ordered from the earliest pipeline stop to the latest. Pure so the ranking is unit tested.
 */
object TelemetryDiagnosis {
    enum class Verdict {
        NO_EVENTS, DISCLOSURE_REQUIRED, SERVICE_PAUSED, LICENSE_INVALID, NO_PROFILES,
        WINDOW_NOT_READABLE, TREE_REJECTED, OFFER_NOT_RECOGNISED, OFFER_NOT_PARSED,
        OFFERS_REJECTED_BY_RULES, ACCEPT_NOT_DISPATCHED, ACCEPT_NOT_CONFIRMED, WORKING,
        WAITING_FOR_OFFER,
    }

    fun diagnose(rows: List<TelemetryAggregator.FunnelRow>): Verdict {
        if (rows.isEmpty()) return Verdict.NO_EVENTS
        fun total(stage: TelemetryStage, reason: String? = null) = rows
            .filter { it.key.stage == stage && (reason == null || it.key.reason == reason) }
            .sumOf { it.total }
        val gate = rows.filter { it.key.stage == TelemetryStage.GATE_BLOCKED }.maxByOrNull { it.lastSeenMs }
        val newestProgress = rows.filter { it.key.stage in progressStages }.maxOfOrNull { it.lastSeenMs } ?: Long.MIN_VALUE
        if (gate != null && gate.lastSeenMs >= newestProgress) {
            when (gate.key.reason) {
                "disclosure_required" -> return Verdict.DISCLOSURE_REQUIRED
                "service_paused" -> return Verdict.SERVICE_PAUSED
                "license_invalid" -> return Verdict.LICENSE_INVALID
                "no_profiles", "no_parser", "no_adapter" -> return Verdict.NO_PROFILES
            }
        }
        val detected = total(TelemetryStage.OFFER_DETECTED)
        if (detected == 0L) {
            val packageSeen = total(TelemetryStage.PACKAGE_SEEN)
            val rootMissing = total(TelemetryStage.ROOT_UNAVAILABLE)
            val treeRejected = total(TelemetryStage.TREE_REJECTED)
            val notParsed = total(TelemetryStage.OFFER_NOT_PARSED)
            val signalMiss = total(TelemetryStage.SIGNAL_MISS)
            return when {
                packageSeen == 0L -> Verdict.NO_EVENTS
                rootMissing > 0 && rootMissing >= treeRejected && rootMissing >= signalMiss -> Verdict.WINDOW_NOT_READABLE
                treeRejected > 0 && treeRejected >= signalMiss -> Verdict.TREE_REJECTED
                notParsed > 0 -> Verdict.OFFER_NOT_PARSED
                signalMiss > 0 -> Verdict.OFFER_NOT_RECOGNISED
                else -> Verdict.WAITING_FOR_OFFER
            }
        }
        val accepted = total(TelemetryStage.DECISION_ACCEPTED)
        if (accepted == 0L) return Verdict.OFFERS_REJECTED_BY_RULES
        val dispatched = total(TelemetryStage.CLICK_RESULT, "semantic") + total(TelemetryStage.CLICK_RESULT, "gesture")
        if (dispatched == 0L) return Verdict.ACCEPT_NOT_DISPATCHED
        val confirmed = total(TelemetryStage.CONFIRMATION_CONFIRMED)
        if (confirmed == 0L && total(TelemetryStage.CONFIRMATION_TIMEOUT) > 0) return Verdict.ACCEPT_NOT_CONFIRMED
        return Verdict.WORKING
    }

    private val progressStages = setOf(
        TelemetryStage.PACKAGE_SEEN, TelemetryStage.SIGNAL_FOUND, TelemetryStage.SIGNAL_MISS,
        TelemetryStage.OFFER_DETECTED, TelemetryStage.CLICK_RESULT,
    )
}
