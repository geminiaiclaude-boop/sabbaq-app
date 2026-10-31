package app.sabbaq.core

import java.util.LinkedHashMap

/** Bounded, process-memory-only de-duplication; no offer identifiers are persisted. */
class OfferStatsDeduplicator(
    private val maxTrackedFingerprints: Int = 2_048,
    private val evictionBatchSize: Int = 512,
) {
    init {
        require(maxTrackedFingerprints > 0)
        require(evictionBatchSize in 1..maxTrackedFingerprints)
    }

    private val seen = LinkedHashMap<Int, Int>()
    private val seenAt = HashMap<Int, Long>()
    private var currentDate: String? = null

    fun firstVisible(fingerprint: String, date: String, nowMs: Long = System.currentTimeMillis()): Boolean = mark(fingerprint, date, VISIBLE, nowMs)

    fun firstRejection(fingerprint: String, date: String, nowMs: Long = System.currentTimeMillis()): Boolean = mark(fingerprint, date, REJECTED, nowMs)

    fun reset() {
        currentDate = null
        seen.clear()
        seenAt.clear()
    }

    private fun mark(fingerprint: String, date: String, flag: Int, nowMs: Long): Boolean {
        if (currentDate != date) {
            currentDate = date
            seen.clear()
            seenAt.clear()
        }
        val hash = fingerprint.hashCode()
        val last = seenAt[hash]
        if (last != null && (nowMs < last || nowMs - last > SAME_OFFER_WINDOW_MS)) seen.remove(hash)
        seenAt[hash] = nowMs
        val previousFlags = seen[hash] ?: 0
        if ((previousFlags and flag) != 0) return false
        seen[hash] = previousFlags or flag
        if (seen.size > maxTrackedFingerprints) {
            val iterator = seen.entries.iterator()
            repeat(evictionBatchSize) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        return true
    }

    private companion object {
        const val VISIBLE = 1
        const val REJECTED = 2
        /** An offer card stays on screen for seconds; the same fingerprint after this is a new offer. */
        const val SAME_OFFER_WINDOW_MS = 60_000L
    }
}
