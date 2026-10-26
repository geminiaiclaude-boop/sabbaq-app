package app.sabbaq.core

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** Versioned, privacy-safe offer tracing contract. Never add raw offer/UI/location fields here. */
@Serializable
data class OfferTelemetryEvent(
    val schemaVersion: Int = 1,
    val eventId: String,
    val occurredAtEpochMs: Long,
    val correlationId: String,
    val appId: String,
    val appVersion: String,
    val androidApi: Int,
    val provider: String,
    val stage: TelemetryStage,
    val reasonCode: String? = null,
    val decisionScore: Int? = null,
    val latencyMs: Long? = null,
    val latencyBucket: String? = null,
    val configVersion: String? = null,
    val profileVersion: String? = null,
    val installCohort: String? = null,
)

@Serializable
enum class TelemetryStage {
    EVENT_RECEIVED, SIGNAL_FOUND, SIGNAL_MISS, FRAME_INCOMPLETE, PARSED,
    REJECTED, ACCEPT_NODE, ACCEPT_DISPATCHED, CLICK_RESULT,
    CONFIRMATION_CONFIRMED, CONFIRMATION_TIMEOUT, STALE, PAUSED,
    QUEUE_DROPPED, UPLOAD_REJECTED
}

object OfferTelemetryContract {
    const val MAX_REASON_LENGTH = 48
    const val MAX_CORRELATION_LENGTH = 64
    const val MAX_BATCH_EVENTS = 50
    const val MAX_EVENT_BYTES = 4096
    val forbiddenPayloadNames = setOf(
        "offer", "offerText", "text", "fare", "address", "pickup", "destination",
        "location", "latitude", "longitude", "screenshot", "accessibilityTree",
        "phone", "email", "token", "accessToken", "refreshToken"
    )

    fun newId(): String = UUID.randomUUID().toString()

    fun correlationId(installationId: String, authenticatedUserId: String? = null): String {
        val material = installationId.trim() + ":" + authenticatedUserId.orEmpty().trim()
        return sha256(material).take(32)
    }

    fun sanitizeReason(raw: String?): String? {
        if (raw == null) return null
        val candidate = raw.trim().lowercase()
        // Free-form diagnostics can contain UI text, addresses or fares. Only an existing
        // machine reason-code shape is accepted; everything else becomes a fixed marker.
        val normalized = if (candidate.matches(Regex("[a-z][a-z0-9_.:-]{0,47}"))) candidate else "redacted"
        return normalized.take(MAX_REASON_LENGTH).ifBlank { null }
    }

    fun bounded(event: OfferTelemetryEvent): OfferTelemetryEvent = event.copy(
        schemaVersion = 1,
        eventId = event.eventId.take(64),
        occurredAtEpochMs = event.occurredAtEpochMs.coerceIn(0, Instant.now().toEpochMilli() + 86_400_000),
        correlationId = event.correlationId.take(MAX_CORRELATION_LENGTH),
        appId = event.appId.filter { it.isLetterOrDigit() || it in "._-" }.take(64),
        appVersion = event.appVersion.filter { it.isLetterOrDigit() || it in ".-_" }.take(32),
        androidApi = event.androidApi.coerceIn(26, 99),
        provider = event.provider.filter { it.isLetterOrDigit() || it in "._-" }.take(32),
        reasonCode = sanitizeReason(event.reasonCode),
        decisionScore = event.decisionScore?.coerceIn(0, 100),
        latencyMs = event.latencyMs?.coerceIn(0, 86_400_000),
        latencyBucket = event.latencyBucket?.filter { it.isLetterOrDigit() || it in "_-" }?.take(24),
        configVersion = event.configVersion?.filter { it.isLetterOrDigit() || it in "._-" }?.take(48),
        profileVersion = event.profileVersion?.filter { it.isLetterOrDigit() || it in "._-" }?.take(48),
        installCohort = event.installCohort?.filter { it.isLetterOrDigit() || it in "._-" }?.take(32),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
