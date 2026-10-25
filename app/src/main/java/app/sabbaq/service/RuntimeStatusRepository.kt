package app.sabbaq.service

import app.sabbaq.core.DecisionReport
import app.sabbaq.core.DiagnosticEntry
import app.sabbaq.core.RuntimeStatusSnapshot
import app.sabbaq.core.RuntimeStatusStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local bridge between the accessibility service and the dashboard. */
object RuntimeStatusRepository {
    private val store = RuntimeStatusStore()
    private val _snapshot = MutableStateFlow(store.snapshot())
    val snapshot: StateFlow<RuntimeStatusSnapshot> = _snapshot.asStateFlow()
    fun lifecycle(active: Boolean, reason: String? = null) { store.lifecycle(active, reason); publish() }
    fun event(now: Long) { store.event(now); publish() }
    fun offer(now: Long) { store.offer(now); publish() }
    fun decision(now: Long, report: DecisionReport) { store.decision(now, report); publish() }
    fun click(now: Long) { store.click(now); publish() }
    fun diagnostic(entry: DiagnosticEntry) { store.diagnostic(entry); publish() }
    fun reset() { store.lifecycle(false, "service_destroyed"); publish() }
    private fun publish() { _snapshot.value = store.snapshot() }
}
