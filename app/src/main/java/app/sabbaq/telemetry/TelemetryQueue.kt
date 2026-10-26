package app.sabbaq.telemetry

import android.content.Context
import android.content.SharedPreferences
import app.sabbaq.core.OfferTelemetryContract
import app.sabbaq.core.OfferTelemetryEvent
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-private, redacted queue. It is intentionally not encrypted because no approved
 * crypto dependency is present; payloads contain only the contract's bounded metadata. */
class TelemetryQueue private constructor(private val prefs: SharedPreferences) {
    companion object {
        const val CAPACITY = 300
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ATTEMPTS = 5
        private const val KEY = "events_v1"
        private const val RETRY_KEY = "retry_v1"
        private val json = Json { ignoreUnknownKeys = true }
        fun create(context: Context): TelemetryQueue = TelemetryQueue(
            context.applicationContext.getSharedPreferences("sabbaq_telemetry", Context.MODE_PRIVATE)
        )
    }

    data class Batch(val events: List<OfferTelemetryEvent>, val attempt: Int)
    private data class Retry(val attempt: Int, val nextAt: Long)

    @Synchronized fun enqueue(event: OfferTelemetryEvent): Boolean {
        val now = System.currentTimeMillis()
        val current = read().filter { now - it.occurredAtEpochMs in 0..RETENTION_MS }
        if (current.any { it.eventId == event.eventId }) return false
        val next = (current + OfferTelemetryContract.bounded(event)).takeLast(CAPACITY)
        return persist(next)
    }

    @Synchronized fun peek(limit: Int = OfferTelemetryContract.MAX_BATCH_EVENTS, now: Long = System.currentTimeMillis()): Batch? {
        val retry = readRetry() ?: Retry(0, 0)
        if (retry.nextAt > now) return null
        val events = read().filter { now - it.occurredAtEpochMs in 0..RETENTION_MS }.take(limit.coerceIn(1, 50))
        if (events.isEmpty()) return null
        return Batch(events, retry.attempt)
    }

    @Synchronized fun acknowledge(ids: Set<String>) {
        persist(read().filterNot { it.eventId in ids })
        prefs.edit().remove(RETRY_KEY).apply()
    }

    @Synchronized fun failed(now: Long = System.currentTimeMillis()) {
        val old = readRetry() ?: Retry(0, 0)
        val attempt = (old.attempt + 1).coerceAtMost(MAX_ATTEMPTS)
        val delay = (1L shl (attempt - 1).coerceAtMost(6)) * 1000L
        prefs.edit().putString(RETRY_KEY, json.encodeToString(RetryWire(attempt, now + delay))).apply()
    }

    @Synchronized fun discardAfterMaxAttempts(): Boolean {
        val retry = readRetry() ?: return false
        if (retry.attempt < MAX_ATTEMPTS) return false
        persist(emptyList()); prefs.edit().remove(RETRY_KEY).apply(); return true
    }

    @Synchronized fun size(): Int = read().size

    private fun read(): List<OfferTelemetryEvent> = runCatching {
        json.decodeFromString<List<OfferTelemetryEvent>>(prefs.getString(KEY, "[]") ?: "[]")
    }.getOrDefault(emptyList()).takeLast(CAPACITY)

    private fun persist(events: List<OfferTelemetryEvent>): Boolean = runCatching {
        val encoded = json.encodeToString(events.takeLast(CAPACITY))
        require(encoded.toByteArray().size <= 256 * 1024)
        prefs.edit().putString(KEY, encoded).commit()
    }.getOrDefault(false)

    private fun readRetry(): Retry? = runCatching {
        json.decodeFromString<RetryWire>(prefs.getString(RETRY_KEY, "") ?: "").let { Retry(it.attempt, it.nextAt) }
    }.getOrNull()

    @kotlinx.serialization.Serializable
    private data class RetryWire(val attempt: Int, val nextAt: Long)
}
