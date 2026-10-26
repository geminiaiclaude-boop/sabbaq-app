package app.sabbaq.telemetry

import android.content.Context
import android.content.SharedPreferences
import app.sabbaq.core.OfferTelemetryContract
import app.sabbaq.core.OfferTelemetryEvent
import app.sabbaq.core.TelemetryUploadPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-private, redacted queue. It is intentionally not encrypted because no approved
 * crypto dependency is present; payloads contain only the contract's bounded metadata.
 * Every method does disk I/O and must be called from [TelemetryRepository]'s worker, never
 * from the accessibility callback thread. */
class TelemetryQueue private constructor(private val prefs: SharedPreferences) {
    companion object {
        const val CAPACITY = 500
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val KEY = "events_v1"
        private const val RETRY_KEY = "retry_v2"
        private const val DROPPED_KEY = "dropped_v1"
        private val json = Json { ignoreUnknownKeys = true }
        fun create(context: Context): TelemetryQueue = TelemetryQueue(
            context.applicationContext.getSharedPreferences("sabbaq_telemetry", Context.MODE_PRIVATE)
        )
    }

    data class Batch(val events: List<OfferTelemetryEvent>, val attempt: Int)

    @kotlinx.serialization.Serializable
    private data class RetryWire(val failures: Int = 0, val invalid: Int = 0, val nextAt: Long = 0)

    @Synchronized fun enqueueAll(events: List<OfferTelemetryEvent>): Boolean {
        if (events.isEmpty()) return true
        val now = System.currentTimeMillis()
        val current = read().filter { now - it.occurredAtEpochMs in 0..RETENTION_MS }
        val known = current.mapTo(HashSet()) { it.eventId }
        val merged = current + events.filter { known.add(it.eventId) }.map(OfferTelemetryContract::bounded)
        val overflow = merged.size - CAPACITY
        if (overflow > 0) prefs.edit().putInt(DROPPED_KEY, prefs.getInt(DROPPED_KEY, 0) + overflow).apply()
        return persist(merged.takeLast(CAPACITY))
    }

    @Synchronized fun peek(limit: Int = OfferTelemetryContract.MAX_BATCH_EVENTS, now: Long = System.currentTimeMillis()): Batch? {
        val retry = readRetry()
        if (retry.nextAt > now) return null
        val events = read().filter { now - it.occurredAtEpochMs in 0..RETENTION_MS }.take(limit.coerceIn(1, 50))
        if (events.isEmpty()) return null
        return Batch(events, retry.failures)
    }

    @Synchronized fun acknowledge(ids: Set<String>) {
        persist(read().filterNot { it.eventId in ids })
        prefs.edit().remove(RETRY_KEY).apply()
    }

    /** Transient failure: keep every event and wait with exponential backoff. */
    @Synchronized fun failed(now: Long = System.currentTimeMillis()) {
        val old = readRetry()
        val failures = old.failures + 1
        writeRetry(RetryWire(failures, old.invalid, now + TelemetryUploadPolicy.retryDelayMs(failures)))
    }

    /** The server rejected the batch as invalid; returns true when it was dropped as poison. */
    @Synchronized fun rejected(ids: Set<String>, now: Long = System.currentTimeMillis()): Boolean {
        val old = readRetry()
        val invalid = old.invalid + 1
        if (TelemetryUploadPolicy.shouldDropBatch(invalid)) {
            persist(read().filterNot { it.eventId in ids })
            prefs.edit().putInt(DROPPED_KEY, prefs.getInt(DROPPED_KEY, 0) + ids.size).remove(RETRY_KEY).apply()
            return true
        }
        val failures = old.failures + 1
        writeRetry(RetryWire(failures, invalid, now + TelemetryUploadPolicy.retryDelayMs(failures)))
        return false
    }

    /** Events lost to capacity or poison batches since the last call; reported as QUEUE_DROPPED. */
    @Synchronized fun takeDroppedCount(): Int {
        val dropped = prefs.getInt(DROPPED_KEY, 0)
        if (dropped > 0) prefs.edit().remove(DROPPED_KEY).apply()
        return dropped
    }

    @Synchronized fun size(): Int = read().size

    @Synchronized fun nextRetryAt(): Long = readRetry().nextAt

    private fun read(): List<OfferTelemetryEvent> = runCatching {
        json.decodeFromString<List<OfferTelemetryEvent>>(prefs.getString(KEY, "[]") ?: "[]")
    }.getOrDefault(emptyList()).takeLast(CAPACITY)

    private fun persist(events: List<OfferTelemetryEvent>): Boolean = runCatching {
        val encoded = json.encodeToString(events.takeLast(CAPACITY))
        require(encoded.toByteArray().size <= 512 * 1024)
        prefs.edit().putString(KEY, encoded).commit()
    }.getOrDefault(false)

    private fun readRetry(): RetryWire = runCatching {
        json.decodeFromString<RetryWire>(prefs.getString(RETRY_KEY, null) ?: return RetryWire())
    }.getOrDefault(RetryWire())

    private fun writeRetry(value: RetryWire) {
        prefs.edit().putString(RETRY_KEY, json.encodeToString(value)).apply()
    }
}
