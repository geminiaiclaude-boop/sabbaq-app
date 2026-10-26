package app.sabbaq.core

/**
 * Rate-limits telemetry at the source. The first observation of a (stage, reason, provider)
 * key in each window is emitted at once so a support timeline shows when a problem started;
 * repeats are only counted and leave through [drain] as one aggregated row per key. Accessibility
 * delivers hundreds of events a minute, so per-event rows would evict the rare rows that matter.
 */
class TelemetryAggregator(
    private val discreteIntervalMs: Long = 30_000L,
    private val maxKeys: Int = 96,
) {
    init { require(discreteIntervalMs > 0); require(maxKeys in 8..1024) }

    data class Key(val stage: TelemetryStage, val reason: String?, val provider: String)
    data class Emission(val key: Key, val count: Int, val detail: String?)
    data class FunnelRow(val key: Key, val total: Long, val lastSeenMs: Long, val lastDetail: String?)

    private class Slot(var lastEmitMs: Long, var suppressed: Int, var detail: String?, var total: Long, var lastSeenMs: Long)

    private val slots = LinkedHashMap<Key, Slot>()
    private var droppedKeys = 0

    /** Returns an emission to persist now, or null when the observation was folded into a counter. */
    @Synchronized
    fun observe(stage: TelemetryStage, reason: String?, provider: String, detail: String?, nowMs: Long): Emission? {
        val key = Key(stage, reason, provider)
        val slot = slots[key]
        if (slot == null) {
            if (slots.size >= maxKeys) { droppedKeys++; return null }
            slots[key] = Slot(nowMs, 0, detail, 1, nowMs)
            return Emission(key, 1, detail)
        }
        slot.total++
        slot.lastSeenMs = nowMs
        if (detail != null) slot.detail = detail
        val elapsed = nowMs - slot.lastEmitMs
        if (elapsed < 0 || elapsed >= discreteIntervalMs) {
            val count = slot.suppressed + 1
            slot.suppressed = 0
            slot.lastEmitMs = nowMs
            return Emission(key, count, slot.detail)
        }
        slot.suppressed++
        return null
    }

    /** Pending suppressed counters as aggregated rows; each is returned exactly once. */
    @Synchronized
    fun drain(nowMs: Long): List<Emission> {
        val rows = slots.entries.filter { it.value.suppressed > 0 }.map { (key, slot) ->
            Emission(key, slot.suppressed, slot.detail).also {
                slot.suppressed = 0
                slot.lastEmitMs = nowMs
            }
        }
        val overflow = droppedKeys
        droppedKeys = 0
        return if (overflow > 0) rows + Emission(Key(TelemetryStage.QUEUE_DROPPED, "key_budget", "sabbaq"), overflow, null) else rows
    }

    /** Totals since process start for the on-device diagnostics card, most recent first. */
    @Synchronized
    fun funnel(): List<FunnelRow> = slots.entries
        .map { (key, slot) -> FunnelRow(key, slot.total, slot.lastSeenMs, slot.detail) }
        .sortedByDescending { it.lastSeenMs }

    @Synchronized
    fun reset() { slots.clear(); droppedKeys = 0 }
}
