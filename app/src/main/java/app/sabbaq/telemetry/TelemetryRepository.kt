package app.sabbaq.telemetry

import android.content.Context
import android.os.Build
import app.sabbaq.BuildConfig
import app.sabbaq.core.OfferTelemetryContract
import app.sabbaq.core.OfferTelemetryEvent
import app.sabbaq.core.TelemetryStage
import app.sabbaq.network.SupabaseClient
import java.util.UUID

/** Automatic producer bridge. It has no access to accessibility text or offer payloads. */
object TelemetryRepository {
    private const val PREFS = "sabbaq_telemetry_identity"
    private const val INSTALLATION = "installation_id"
    @Volatile private var queue: TelemetryQueue? = null
    @Volatile private var correlation: String? = null

    fun init(context: Context) {
        val app = context.applicationContext
        queue = TelemetryQueue.create(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installation = prefs.getString(INSTALLATION, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(INSTALLATION, it).apply()
        }
        correlation = OfferTelemetryContract.correlationId(installation)
    }

    fun record(
        stage: TelemetryStage,
        reasonCode: String? = null,
        score: Int? = null,
        latencyMs: Long? = null,
        profileVersion: String? = null,
        provider: String = "unknown",
    ) {
        val id = correlation ?: return
        queue?.enqueue(OfferTelemetryEvent(
            eventId = OfferTelemetryContract.newId(),
            occurredAtEpochMs = System.currentTimeMillis(),
            correlationId = id,
            appId = "sabbaq",
            appVersion = BuildConfig.VERSION_NAME,
            androidApi = Build.VERSION.SDK_INT,
            provider = provider,
            stage = stage,
            reasonCode = reasonCode,
            decisionScore = score,
            latencyMs = latencyMs,
            latencyBucket = latencyMs?.let(::bucket),
            profileVersion = profileVersion,
        ))
    }

    /** Support-safe identifier only; does not expose queued events or telemetry payloads. */
    fun currentCorrelationId(): String? = correlation

    fun pending(): TelemetryQueue? = queue

    suspend fun flushOnce() {
        val target = queue ?: return
        val batch = target.peek() ?: return
        val result = SupabaseClient.uploadOfferTelemetry(batch.events)
        if (result.isSuccess) target.acknowledge(batch.events.map { it.eventId }.toSet())
        else {
            target.failed()
            target.discardAfterMaxAttempts()
        }
    }
    private fun bucket(ms: Long): String = when {
        ms < 100 -> "lt_100ms"
        ms < 500 -> "100_499ms"
        ms < 2000 -> "500_1999ms"
        else -> "gte_2000ms"
    }
}
