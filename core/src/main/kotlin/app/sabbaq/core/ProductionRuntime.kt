package app.sabbaq.core

/** Explicit strategy for each bundled provider; no unsupported provider is implied. */
class BoltRideAppAdapter(override val profile: RideProfile) : RideAppAdapter {
    override val packageNames: Set<String> = profile.packages.toSet()
    override val signalConfig = AdapterSignalConfig(minimumIndependentFeatures = 3)
    override val acceptLabelFamilies = profile.acceptLabels
    override val confirmLabelFamilies = profile.confirmLabels
}

class PetraRideAppAdapter(override val profile: RideProfile) : RideAppAdapter {
    override val packageNames: Set<String> = profile.packages.toSet()
    override val signalConfig = AdapterSignalConfig(minimumIndependentFeatures = 3)
    override val acceptLabelFamilies = profile.acceptLabels
    override val confirmLabelFamilies = profile.confirmLabels
}

/** At most one bounded retry for one offer/window after an explicit dispatch failure. */
enum class RetryState { IDLE, RETRY_ELIGIBLE, RETRY_CONSUMED, CANCELLED }
enum class DispatchFailure { NOT_DISPATCHED, SEMANTIC_SUCCESS, GESTURE_SUCCESS }
class BoundedDispatchRetry(
    private val cooldownMs: Long = 750L,
) {
    init { require(cooldownMs > 0) }
    private var state = RetryState.IDLE
    private var offer: String? = null
    private var window: String? = null
    private var eligibleAt = Long.MAX_VALUE
    fun begin(offerFingerprint: String, windowToken: String) {
        if (offer != offerFingerprint || window != windowToken) {
            offer = offerFingerprint; window = windowToken; state = RetryState.IDLE; eligibleAt = Long.MAX_VALUE
        }
    }
    fun onDispatchResult(result: DispatchFailure, nowMs: Long) {
        state = when (result) {
            DispatchFailure.NOT_DISPATCHED -> if (state == RetryState.IDLE) RetryState.RETRY_ELIGIBLE else state
            DispatchFailure.SEMANTIC_SUCCESS, DispatchFailure.GESTURE_SUCCESS -> RetryState.CANCELLED
        }
        if (result == DispatchFailure.NOT_DISPATCHED && state == RetryState.RETRY_ELIGIBLE) eligibleAt = nowMs + cooldownMs
    }
    fun canRetry(offerFingerprint: String, windowToken: String, nowMs: Long): Boolean =
        state == RetryState.RETRY_ELIGIBLE && offer == offerFingerprint && window == windowToken && nowMs >= eligibleAt
    fun consumeRetry(offerFingerprint: String, windowToken: String, nowMs: Long): Boolean {
        if (!canRetry(offerFingerprint, windowToken, nowMs)) return false
        state = RetryState.RETRY_CONSUMED; return true
    }
    fun onConfirmationOrWindowChange() { state = RetryState.CANCELLED; offer = null; window = null; eligibleAt = Long.MAX_VALUE }
    fun snapshot(): RetryState = state
}

data class RuntimeStatusSnapshot(
    val active: Boolean,
    val pausedReason: String?,
    val stale: Boolean,
    val lastEventMs: Long?,
    val lastOfferMs: Long?,
    val lastDecisionMs: Long?,
    val lastClickMs: Long?,
    val latestReport: DecisionReport?,
    val diagnostics: List<DiagnosticEntry>,
)

/** Single bounded in-memory status model. It stores metadata and reason codes only. */
class RuntimeStatusStore(private val diagnosticCapacity: Int = 12) {
    init { require(diagnosticCapacity in 1..64) }
    private val diagnostics = ArrayDeque<DiagnosticEntry>(diagnosticCapacity)
    private var active = false
    private var pausedReason: String? = null
    private var stale = false
    private var lastEvent: Long? = null
    private var lastOffer: Long? = null
    private var lastDecision: Long? = null
    private var lastClick: Long? = null
    private var report: DecisionReport? = null
    @Synchronized fun lifecycle(isActive: Boolean, reason: String? = null) { active = isActive; pausedReason = reason?.take(48); stale = reason == "stale" }
    @Synchronized fun event(now: Long) { lastEvent = now; stale = false }
    @Synchronized fun offer(now: Long) { lastOffer = now }
    @Synchronized fun decision(now: Long, value: DecisionReport) { lastDecision = now; report = value.copy(score = value.score.coerceIn(0, 100), availableSignals = value.availableSignals.take(16), reasons = value.reasons.distinct().take(16)) }
    @Synchronized fun click(now: Long) { lastClick = now }
    @Synchronized fun diagnostic(entry: DiagnosticEntry) { if (diagnostics.size == diagnosticCapacity) diagnostics.removeFirst(); diagnostics.addLast(entry.copy(reason = entry.reason?.take(48))) }
    @Synchronized fun snapshot(): RuntimeStatusSnapshot = RuntimeStatusSnapshot(active, pausedReason, stale, lastEvent, lastOffer, lastDecision, lastClick, report, diagnostics.toList())
}
