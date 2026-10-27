package app.sabbaq.service

import android.content.Context
import android.os.SystemClock
import app.sabbaq.core.Profiles
import app.sabbaq.core.RideAppAdapters
import app.sabbaq.core.RideProfile
import app.sabbaq.core.TelemetryStage
import app.sabbaq.network.SupabaseClient
import app.sabbaq.telemetry.TelemetryRepository

/**
 * Ride-app profiles in effect: the bundled set, replaced per id by a strictly newer profile the
 * administration published. This is how a changed Jeeny/Bolt/Petra layout is fixed for every
 * driver without a new APK. Remote profiles may only target packages the build already knows,
 * because the accessibility service only receives events from those packages.
 */
object ProfileRepository {
    private const val PREFS = "sabbaq_remote_profiles"
    private const val KEY = "bundle_v1"
    private const val MAX_CHARS = 600_000
    private const val REFRESH_INTERVAL_MS = 6 * 60 * 60_000L

    @Volatile private var appContext: Context? = null
    @Volatile private var effective: List<RideProfile> = emptyList()
    @Volatile private var lastRefreshAt = 0L

    /** Increments whenever [current] changes; the accessibility service rebuilds its parsers on it. */
    @Volatile var revision: Int = 0
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val cached = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        apply(cached?.let(::decodeRemote).orEmpty())
    }

    fun current(): List<RideProfile> = effective.ifEmpty { bundled }

    fun versionOf(profileId: String): Int? = current().firstOrNull { it.id == profileId }?.version

    /** Fetches published profiles when the cached copy is older than six hours (or [force]). */
    suspend fun refreshIfStale(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastRefreshAt != 0L && now - lastRefreshAt < REFRESH_INTERVAL_MS) return
        val text = SupabaseClient.fetchRideProfiles().getOrNull() ?: return
        lastRefreshAt = now
        if (text.length > MAX_CHARS) return
        val remote = decodeRemote(text)
        val before = current().associate { it.id to it.version }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, text)?.apply()
        apply(remote)
        val after = current().associate { it.id to it.version }
        if (after != before) {
            TelemetryRepository.trace(TelemetryStage.SERVICE_CONNECTED, "profiles_updated",
                detail = after.entries.joinToString(",") { "${it.key}=${it.value}" }.lowercase().take(96))
        }
    }

    private val bundled: List<RideProfile> by lazy { Profiles.bundled() }

    private fun decodeRemote(text: String): List<RideProfile> {
        val allowedPackages = bundled.flatMap { it.packages }.toSet()
        return runCatching { Profiles.decode(text) }.getOrDefault(emptyList())
            // An unknown id has no adapter; RideAppAdapters would throw and disable every platform.
            .filter { profile -> profile.id in RideAppAdapters.supportedProfileIds() && profile.packages.all { it in allowedPackages } }
    }

    @Synchronized
    private fun apply(remote: List<RideProfile>) {
        val merged = runCatching { Profiles.merge(bundled, remote) }.getOrDefault(bundled)
        if (merged != effective) {
            effective = merged
            revision++
        }
    }
}
