package app.qannas.core

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
    private val clicked = LinkedHashMap<String, Long>()
    private var lastClickAt: Long? = null

    @Synchronized
    fun tryAcquire(fingerprint: String, nowMs: Long): Boolean {
        clicked.entries.removeAll { nowMs - it.value > perOfferSuppressMs }
        if (clicked.containsKey(fingerprint)) return false
        val last = lastClickAt
        if (last != null && nowMs - last < minIntervalMs) return false
        clicked[fingerprint] = nowMs
        while (clicked.size > capacity) clicked.remove(clicked.keys.first())
        lastClickAt = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        clicked.clear()
        lastClickAt = null
    }
}
