package app.sabbaq.core

import org.junit.Assert.*
import org.junit.Test

class ProductionGuardsTest {
    private val profile = Profiles.bundled().first { it.id == "jeeny" }
    private fun screen(text: String, second: String? = null) = ScreenNode(
        1, children = listOf(ScreenNode(2, text = text, className = "TextView"),
            ScreenNode(3, text = second, clickable = true, className = "Button")),
    )






    @Test fun diagnosticsAreBoundedAndReasonsAreRedacted() {
        val diagnostics = BoundedDiagnostics(2)
        diagnostics.record(DiagnosticStage.SIGNAL_MISS, 1, "address 12 Main Street")
        diagnostics.record(DiagnosticStage.REJECTED, 2, "fare=5.50")
        diagnostics.record(DiagnosticStage.WATCHDOG, 3, "third")
        assertEquals(2, diagnostics.snapshot().size)
        assertEquals(DiagnosticStage.REJECTED, diagnostics.snapshot().first().stage)
        assertFalse(diagnostics.snapshot().any { it.reason?.contains("Main") == true })
        assertFalse(diagnostics.snapshot().any { it.reason?.contains("5.50") == true })
    }

    @Test fun livenessReportsPausedAndStaleWithoutBackgroundLoop() {
        val state = LivenessState(staleAfterMs = 10)
        state.accessibilityEvent(100)
        state.targetEvent(101)
        assertFalse(state.snapshot(105).stale)
        state.pause("license_expired")
        val snapshot = state.snapshot(105)
        assertTrue(snapshot.stale)
        assertEquals("license_expired", snapshot.pausedReason)
        assertEquals(101L, snapshot.lastTargetEventMs)
    }

}
