package app.sabbaq.core

import org.junit.Assert.*
import org.junit.Test

class ProductionRuntimeTest {

    @Test fun runtimeStatusBoundsAndRedactsMetadata() {
        val status = RuntimeStatusStore(2)
        status.lifecycle(true)
        status.event(1)
        status.decision(2, DecisionReport(140, listOf("fare", "route"), listOf("failed_price")))
        status.diagnostic(DiagnosticEntry(DiagnosticStage.REJECTED, 3, "address 12 Main Street"))
        status.diagnostic(DiagnosticEntry(DiagnosticStage.CLICK_PATH, 4, "semantic"))
        status.diagnostic(DiagnosticEntry(DiagnosticStage.STALE, 5, "third"))
        val snapshot = status.snapshot()
        assertTrue(snapshot.active)
        assertEquals(100, snapshot.latestReport!!.score)
        assertEquals(2, snapshot.diagnostics.size)
        assertFalse(snapshot.diagnostics.any { it.reason?.contains("Main") == true })
    }
}
