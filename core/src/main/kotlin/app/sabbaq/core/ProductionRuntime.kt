package app.sabbaq.core

/** Explainability for the dashboard only; never an acceptance gate. [reasons] are FareSniper reason keys. */
data class DecisionReport(
    val score: Int,
    val availableSignals: List<String>,
    val reasons: List<String>,
)

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
