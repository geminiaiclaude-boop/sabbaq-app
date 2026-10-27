package app.sabbaq.core

import org.junit.Assert.*
import org.junit.Test

class TelemetryAggregatorTest {
    @Test fun `first observation is emitted at once and repeats are only counted`() {
        val aggregator = TelemetryAggregator(discreteIntervalMs = 1_000)
        val first = aggregator.observe(TelemetryStage.SIGNAL_MISS, "cls_none", "jeeny", "a0", 0)
        assertEquals(1, first!!.count)
        repeat(500) { assertNull(aggregator.observe(TelemetryStage.SIGNAL_MISS, "cls_none", "jeeny", "a0", 10L + it)) }
        assertEquals(501L, aggregator.funnel().single().total)
    }

    @Test fun `after the window the next observation carries the suppressed count`() {
        val aggregator = TelemetryAggregator(discreteIntervalMs = 1_000)
        aggregator.observe(TelemetryStage.EVENT_RECEIVED, null, "jeeny", null, 0)
        repeat(9) { aggregator.observe(TelemetryStage.EVENT_RECEIVED, null, "jeeny", null, 100) }
        val emission = aggregator.observe(TelemetryStage.EVENT_RECEIVED, null, "jeeny", null, 1_500)
        assertEquals(10, emission!!.count)
    }

    @Test fun `drain returns every suppressed counter exactly once with the latest detail`() {
        val aggregator = TelemetryAggregator(discreteIntervalMs = 60_000)
        aggregator.observe(TelemetryStage.ROOT_UNAVAILABLE, "active_other", "jeeny", null, 0)
        aggregator.observe(TelemetryStage.ROOT_UNAVAILABLE, "active_other", "jeeny", "n1", 1)
        aggregator.observe(TelemetryStage.ROOT_UNAVAILABLE, "active_other", "jeeny", "n2", 2)
        val drained = aggregator.drain(3)
        assertEquals(1, drained.size)
        assertEquals(2, drained.single().count)
        assertEquals("n2", drained.single().detail)
        assertTrue(aggregator.drain(4).isEmpty())
    }

    @Test fun `distinct reasons are distinct keys so a rare cause is never hidden`() {
        val aggregator = TelemetryAggregator()
        assertNotNull(aggregator.observe(TelemetryStage.GATE_BLOCKED, "service_paused", "jeeny", null, 0))
        assertNotNull(aggregator.observe(TelemetryStage.GATE_BLOCKED, "license_invalid", "jeeny", null, 1))
        assertEquals(2, aggregator.funnel().size)
    }

    @Test fun `key budget overflow is reported instead of growing without bound`() {
        val aggregator = TelemetryAggregator(maxKeys = 8)
        repeat(12) { aggregator.observe(TelemetryStage.TREE_REJECTED, "r$it", "jeeny", null, it.toLong()) }
        assertEquals(8, aggregator.funnel().size)
        val overflow = aggregator.drain(100).single { it.key.stage == TelemetryStage.QUEUE_DROPPED }
        assertEquals(4, overflow.count)
    }

    @Test fun `clock going backwards re-emits instead of suppressing forever`() {
        val aggregator = TelemetryAggregator(discreteIntervalMs = 1_000)
        aggregator.observe(TelemetryStage.STALE, "unchanged_frame", "jeeny", null, 5_000)
        assertNotNull(aggregator.observe(TelemetryStage.STALE, "unchanged_frame", "jeeny", null, 10))
    }
}
