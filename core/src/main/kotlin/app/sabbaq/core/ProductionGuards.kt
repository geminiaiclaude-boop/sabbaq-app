package app.sabbaq.core
import java.security.MessageDigest

enum class DiagnosticStage {
    PACKAGE_SEEN, EVENT_RECEIVED, SIGNAL_FOUND, SIGNAL_MISS, FRAME_INCOMPLETE,
    FARE_MISSING, REJECTED, ACCEPT_NODE_FOUND, CLICK_PATH, CONFIRMATION_CONFIRMED,
    CONFIRMATION_TIMEOUT, STALE, WATCHDOG, GATE_BLOCKED, ROOT_UNAVAILABLE, TREE_REJECTED,
    OFFER_NOT_PARSED, OFFER_DETECTED,
}

data class DiagnosticEntry(val stage: DiagnosticStage, val atElapsedMs: Long, val reason: String? = null)

/** In-memory ring buffer: metadata only, never offer text, addresses, screenshots or payloads. */
class BoundedDiagnostics(private val capacity: Int = 64) {
    init { require(capacity in 1..256) }
    private val entries = ArrayDeque<DiagnosticEntry>(capacity)
    @Synchronized fun record(stage: DiagnosticStage, nowMs: Long, reason: String? = null) {
        if (entries.size == capacity) entries.removeFirst()
        val safeReason = reason?.takeIf { it.length <= 48 && it.matches(Regex("[A-Za-z0-9_.:-]+")) }
        entries.addLast(DiagnosticEntry(stage, nowMs, safeReason ?: reason?.let { "redacted" }))
    }
    @Synchronized fun snapshot(): List<DiagnosticEntry> = entries.toList()
}

data class LivenessSnapshot(
    val lastAccessibilityEventMs: Long?, val lastTargetEventMs: Long?, val lastOfferDetectionMs: Long?,
    val lastDecisionMs: Long?, val lastClickMs: Long?, val stale: Boolean, val pausedReason: String?,
)

/** Explicit service liveness state; callers advance it from their existing lifecycle/events. */
class LivenessState(private val staleAfterMs: Long = 15_000L) {
    init { require(staleAfterMs > 0) }
    private var accessibility: Long? = null
    private var target: Long? = null
    private var offer: Long? = null
    private var decision: Long? = null
    private var click: Long? = null
    private var paused: String? = null
    @Synchronized fun accessibilityEvent(now: Long) { accessibility = now }
    @Synchronized fun targetEvent(now: Long) { target = now }
    @Synchronized fun offerDetected(now: Long) { offer = now }
    @Synchronized fun decision(now: Long) { decision = now }
    @Synchronized fun click(now: Long) { click = now }
    @Synchronized fun pause(reason: String?) { paused = reason?.take(48) }
    @Synchronized fun snapshot(now: Long): LivenessSnapshot {
        val last = listOfNotNull(accessibility, target, offer, decision, click).maxOrNull()
        return LivenessSnapshot(accessibility, target, offer, decision, click,
            paused != null || (last != null && now - last > staleAfterMs), paused)
    }
}
