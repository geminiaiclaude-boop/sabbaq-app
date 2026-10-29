package app.sabbaq.service

import app.sabbaq.core.TelemetryStage
import app.sabbaq.core.engine.AcceptanceFunnel
import app.sabbaq.core.engine.FunnelSnapshot
import app.sabbaq.core.engine.FunnelVerdict
import app.sabbaq.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The acceptance funnel of this device: the service feeds every step an offer passes or
 * stops at, the dashboard shows the verdict, and each change of verdict goes to telemetry as
 * HEARTBEAT "diag:<code>" so the administration sees where a driver's offers stop.
 */
object DiagnosisRepository {
    data class State(val snapshot: FunnelSnapshot, val verdict: FunnelVerdict)

    val funnel = AcceptanceFunnel { System.currentTimeMillis() }
    private val _state = MutableStateFlow(compute())
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var lastRefreshMs = 0L
    @Volatile private var lastReported: FunnelVerdict? = null

    /** Recomputes at most every 2 s unless [force]; reports a changed verdict once. */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs < REFRESH_MS) return
        lastRefreshMs = now
        val next = compute()
        _state.value = next
        if (next.verdict != lastReported) {
            lastReported = next.verdict
            runCatching {
                TelemetryRepository.trace(TelemetryStage.HEARTBEAT, reasonCode = ("diag:" + next.verdict.code).take(48),
                    detail = next.verdict.reason?.let { "r=" + it.take(60) })
            }
        }
    }

    private fun compute(): State {
        val snapshot = funnel.snapshot()
        return State(snapshot, AcceptanceFunnel.diagnose(snapshot, System.currentTimeMillis()))
    }

    private const val REFRESH_MS = 2_000L
}
