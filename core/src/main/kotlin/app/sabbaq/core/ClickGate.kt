package app.sabbaq.core

/**
 * Guarantees one click per offer. The same offer is redrawn many times a
 * second while its countdown runs; without this gate each redraw would be a
 * fresh tap on whatever sits under the accept button once it disappears.
 */
class ClickGate(
    private val perOfferSuppressMs: Long,
    private val minIntervalMs: Long,
    private val capacity: Int = 64,
) {
    init {
        require(perOfferSuppressMs > 0) { "perOfferSuppressMs must be positive" }
        require(minIntervalMs >= 0) { "minIntervalMs must not be negative" }
        require(capacity > 0) { "capacity must be positive" }
    }

    private val clicked = LinkedHashMap<String, Long>()
    private var lastClickAt: Long? = null

    @Synchronized
    fun tryAcquire(fingerprint: String, nowMs: Long): Boolean {
        if (fingerprint.isBlank() || nowMs < 0) return false
        val last = lastClickAt
        // System.currentTimeMillis can move backwards. Refuse rather than making
        // the global rate limit and per-offer suppression unexpectedly permissive.
        if (last != null && nowMs < last) return false
        clicked.entries.removeAll { hasElapsed(nowMs, it.value, perOfferSuppressMs) }
        if (clicked.containsKey(fingerprint)) return false
        if (last != null && !hasElapsed(nowMs, last, minIntervalMs)) return false
        // Never evict a still-suppressed fingerprint: doing so would permit a
        // duplicate tap during a burst. Reject the new offer until an entry ages
        // out instead, which preserves both the memory bound and click safety.
        if (clicked.size >= capacity) return false
        clicked[fingerprint] = nowMs
        lastClickAt = nowMs
        return true
    }

    /** Release only the reservation created by this failed dispatch attempt. */
    @Synchronized
    fun release(fingerprint: String, acquiredAtMs: Long): Boolean {
        if (fingerprint.isBlank() || acquiredAtMs < 0) return false
        if (clicked[fingerprint] != acquiredAtMs) return false
        clicked.remove(fingerprint)
        return true
    }

    @Synchronized
    fun reset() {
        clicked.clear()
        lastClickAt = null
    }

    private fun hasElapsed(nowMs: Long, thenMs: Long, intervalMs: Long): Boolean =
        nowMs >= thenMs && nowMs - thenMs >= intervalMs
}
