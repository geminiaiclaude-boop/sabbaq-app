package app.sabbaq.core

/**
 * Upload scheduling for telemetry. Transient failures (offline, expired session, 5xx) back off
 * and keep the queue: diagnostics are most needed exactly when the device had trouble. Only a
 * batch the server rejects as invalid several times in a row is dropped, so one malformed event
 * can never block every later upload.
 */
object TelemetryUploadPolicy {
    const val BASE_DELAY_MS = 30_000L
    const val MAX_DELAY_MS = 15 * 60_000L
    const val MAX_INVALID_REJECTIONS = 3
    const val FLUSH_INTERVAL_MS = 60_000L

    enum class Outcome { ACCEPTED, TRANSIENT_FAILURE, NO_SESSION, INVALID_BATCH }

    fun retryDelayMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        val shift = (consecutiveFailures - 1).coerceAtMost(10)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    fun shouldDropBatch(consecutiveInvalidRejections: Int): Boolean =
        consecutiveInvalidRejections >= MAX_INVALID_REJECTIONS
}
