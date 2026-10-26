package app.sabbaq.telemetry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import app.sabbaq.BuildConfig
import app.sabbaq.CrashLog
import app.sabbaq.core.OfferTelemetryContract
import app.sabbaq.core.OfferTelemetryEvent
import app.sabbaq.core.TelemetryAggregator
import app.sabbaq.core.TelemetryStage
import app.sabbaq.core.TelemetryUploadPolicy
import app.sabbaq.network.SupabaseClient
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Automatic producer bridge. It has no access to accessibility text or offer payloads.
 * [trace] is safe on the accessibility thread: it only touches the in-memory aggregator and
 * hands persistence to a single background worker that also owns every upload.
 */
object TelemetryRepository {
    private const val PREFS = "sabbaq_telemetry_identity"
    private const val INSTALLATION = "installation_id"
    private const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L
    private const val MAX_BATCHES_PER_FLUSH = 10
    private const val TARGET_VERSION_TTL_MS = 10 * 60_000L
    private const val STARTUP_DELAY_MS = 20_000L
    private const val CRASH_MARKER = "reported_crash"
    private const val SERVICE_PROVIDER = "sabbaq"

    /** Ride-hailing package per bundled profile id, used only to read the installed version. */
    private val providerPackages = mapOf(
        "jeeny" to "me.com.easytaxista",
        "bolt" to "ee.mtakso.driver",
        "petra" to "com.PetraRide_Captain",
    )

    enum class UploadState { IDLE, UPLOADED, WAITING_FOR_SESSION, RETRYING, BATCH_DROPPED }

    data class Status(
        val correlationId: String?,
        val pending: Int,
        val lastUploadEpochMs: Long?,
        val state: UploadState,
        val funnel: List<TelemetryAggregator.FunnelRow>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val aggregator = TelemetryAggregator()
    @Volatile private var queue: TelemetryQueue? = null
    @Volatile private var correlation: String? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var loop: Job? = null
    @Volatile private var pending = 0
    @Volatile private var lastUploadEpochMs: Long? = null
    @Volatile private var uploadState = UploadState.IDLE
    @Volatile var serviceConnected: Boolean = false
    private val targetVersions = HashMap<String, Pair<String?, Long>>()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        queue = TelemetryQueue.create(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installation = prefs.getString(INSTALLATION, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(INSTALLATION, it).apply()
        }
        correlation = OfferTelemetryContract.correlationId(installation)
        startLoop()
    }

    /** Records one observation; never blocks and never throws into the caller. */
    fun trace(
        stage: TelemetryStage,
        reasonCode: String? = null,
        detail: String? = null,
        provider: String = "sabbaq",
        score: Int? = null,
        latencyMs: Long? = null,
    ) {
        val id = correlation ?: return
        val emission = runCatching {
            aggregator.observe(stage, reasonCode?.lowercase(), provider, detail, SystemClock.elapsedRealtime())
        }.getOrNull() ?: return
        val observedAt = System.currentTimeMillis()
        worker.launch { enqueue(listOf(toEvent(id, emission, score, latencyMs, observedAt))) }
    }

    /** Support-safe identifier only; does not expose queued events or telemetry payloads. */
    fun currentCorrelationId(): String? = correlation

    fun status(): Status = Status(correlation, pending, lastUploadEpochMs, uploadState, aggregator.funnel())

    /** Drains counters and uploads now, e.g. when the service stops or the driver asks for it. */
    fun flushNow() {
        worker.launch { runCatching { drainCounters(); flush() } }
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = worker.launch {
            // Lets App.onCreate finish SupabaseClient.init and session restore before the
            // first heartbeat reads license state.
            delay(STARTUP_DELAY_MS)
            var lastHeartbeat = 0L
            while (isActive) {
                runCatching {
                    val now = SystemClock.elapsedRealtime()
                    if (lastHeartbeat == 0L || now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                        lastHeartbeat = now
                        heartbeat()
                    }
                    drainCounters()
                    flush()
                }
                delay(TelemetryUploadPolicy.FLUSH_INTERVAL_MS)
            }
        }
    }

    private fun heartbeat() {
        val id = correlation ?: return
        val app = appContext ?: return
        val health = DeviceHealthProbe.read(app, serviceConnected, installedVersion("jeeny") != null)
        val now = System.currentTimeMillis()
        val event = toEvent(id, TelemetryAggregator.Emission(
            TelemetryAggregator.Key(TelemetryStage.HEARTBEAT, health.primaryIssue(), "jeeny"), 1, health.flags()),
            null, null, now)
        enqueue(listOfNotNull(event, crashEventOnce(id, app, now)))
    }

    /**
     * Reports the stored crash record once: exception type plus the first Sabbaq frame, which is
     * enough to locate the fault. Messages are never read (CrashLog does not keep them).
     */
    private fun crashEventOnce(id: String, app: Context, now: Long): OfferTelemetryEvent? {
        val text = CrashLog.read(app) ?: return null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val marker = text.hashCode().toString()
        if (prefs.getString(CRASH_MARKER, null) == marker) return null
        prefs.edit().putString(CRASH_MARKER, marker).apply()
        val lines = text.lines()
        val type = lines.getOrNull(2)?.substringAfterLast('.')?.lowercase()?.filter { it.isLetterOrDigit() || it == '_' }
        val frame = lines.firstOrNull { it.trim().startsWith("at app.sabbaq.") }?.trim()?.removePrefix("at ")
        val location = frame?.let {
            val method = it.substringBefore('(').split('.').takeLast(2).joinToString(".")
            val line = it.substringAfterLast(':', "").substringBefore(')')
            "at=$method:$line".lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in "._:=" }.take(96)
        }
        return toEvent(id, TelemetryAggregator.Emission(
            TelemetryAggregator.Key(TelemetryStage.APP_CRASH, type?.ifBlank { null } ?: "unknown", SERVICE_PROVIDER), 1, location),
            null, null, now)
    }

    private fun drainCounters() {
        val id = correlation ?: return
        val target = queue ?: return
        val now = System.currentTimeMillis()
        val rows = aggregator.drain(SystemClock.elapsedRealtime()).map { toEvent(id, it, null, null, now) }
        val dropped = target.takeDroppedCount()
        val all = if (dropped > 0) rows + toEvent(id, TelemetryAggregator.Emission(
            TelemetryAggregator.Key(TelemetryStage.QUEUE_DROPPED, "device_queue", "sabbaq"), dropped, null), null, null, now) else rows
        enqueue(all)
    }

    private suspend fun flush() {
        val target = queue ?: return
        repeat(MAX_BATCHES_PER_FLUSH) {
            val batch = target.peek() ?: return
            val ids = batch.events.mapTo(HashSet()) { it.eventId }
            when (SupabaseClient.uploadOfferTelemetry(batch.events)) {
                TelemetryUploadPolicy.Outcome.ACCEPTED -> {
                    target.acknowledge(ids)
                    lastUploadEpochMs = System.currentTimeMillis()
                    uploadState = UploadState.UPLOADED
                }
                TelemetryUploadPolicy.Outcome.NO_SESSION -> {
                    target.failed(); uploadState = UploadState.WAITING_FOR_SESSION
                    pending = target.size(); return
                }
                TelemetryUploadPolicy.Outcome.TRANSIENT_FAILURE -> {
                    target.failed(); uploadState = UploadState.RETRYING
                    pending = target.size(); return
                }
                TelemetryUploadPolicy.Outcome.INVALID_BATCH -> {
                    val dropped = target.rejected(ids)
                    uploadState = if (dropped) UploadState.BATCH_DROPPED else UploadState.RETRYING
                    aggregator.observe(TelemetryStage.UPLOAD_REJECTED, if (dropped) "batch_dropped" else "batch_invalid",
                        SERVICE_PROVIDER, null, SystemClock.elapsedRealtime())?.let { emission ->
                        correlation?.let { id -> enqueue(listOf(toEvent(id, emission, null, null, System.currentTimeMillis()))) }
                    }
                    pending = target.size(); return
                }
            }
            pending = target.size()
        }
    }

    private fun enqueue(events: List<OfferTelemetryEvent>) {
        val target = queue ?: return
        if (events.isNotEmpty()) target.enqueueAll(events)
        pending = target.size()
    }

    private fun toEvent(
        correlationId: String,
        emission: TelemetryAggregator.Emission,
        score: Int?,
        latencyMs: Long?,
        nowEpochMs: Long,
    ) = OfferTelemetryEvent(
        eventId = OfferTelemetryContract.newId(),
        occurredAtEpochMs = nowEpochMs,
        correlationId = correlationId,
        appId = "sabbaq",
        appVersion = BuildConfig.VERSION_NAME,
        androidApi = Build.VERSION.SDK_INT,
        provider = emission.key.provider,
        stage = emission.key.stage,
        reasonCode = emission.key.reason,
        decisionScore = score,
        latencyMs = latencyMs,
        latencyBucket = latencyMs?.let(::bucket),
        eventCount = emission.count,
        detail = emission.detail,
        targetAppVersion = installedVersion(emission.key.provider),
    )

    /** Called on the worker only; cached so PackageManager is queried at most every few minutes. */
    @Suppress("DEPRECATION")
    private fun installedVersion(provider: String): String? {
        val packageName = providerPackages[provider] ?: return null
        val now = SystemClock.elapsedRealtime()
        synchronized(targetVersions) {
            targetVersions[provider]?.let { (version, at) -> if (now - at < TARGET_VERSION_TTL_MS) return version }
        }
        val version = runCatching {
            val manager = appContext?.packageManager ?: return null
            if (Build.VERSION.SDK_INT >= 33) manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            else manager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
        synchronized(targetVersions) { targetVersions[provider] = version to now }
        return version
    }

    private fun bucket(ms: Long): String = when {
        ms < 100 -> "lt_100ms"
        ms < 500 -> "100_499ms"
        ms < 2000 -> "500_1999ms"
        else -> "gte_2000ms"
    }
}
