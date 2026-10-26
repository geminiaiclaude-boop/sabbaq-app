package app.sabbaq.core

import org.junit.Assert.*
import org.junit.Test

class ProductionRuntimeTest {
    @Test fun retryIsSingleDelayedAndCancelledBySuccessOrWindowChange() {
        val retry = BoundedDispatchRetry(100)
        retry.begin("offer", "window")
        retry.onDispatchResult(DispatchFailure.NOT_DISPATCHED, 10)
        assertFalse(retry.canRetry("offer", "window", 109))
        assertTrue(retry.consumeRetry("offer", "window", 110))
        assertFalse(retry.consumeRetry("offer", "window", 111))
        retry.begin("other", "window")
        retry.onDispatchResult(DispatchFailure.SEMANTIC_SUCCESS, 200)
        assertFalse(retry.canRetry("other", "window", 300))
        retry.onConfirmationOrWindowChange()
        assertEquals(RetryState.CANCELLED, retry.snapshot())
    }

    @Test fun runtimeStatusBoundsAndRedactsMetadata() {
        val status = RuntimeStatusStore(2)
        status.lifecycle(true)
        status.event(1)
        status.decision(2, DecisionReport(140, listOf("fare", "route"), listOf(ReasonCode.FARE_TOO_LOW)))
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
