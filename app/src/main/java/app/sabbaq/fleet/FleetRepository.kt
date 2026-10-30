package app.sabbaq.fleet

import android.content.Context
import android.os.SystemClock
import app.sabbaq.BuildConfig
import app.sabbaq.core.AppRelease
import app.sabbaq.core.SharedLexicon
import app.sabbaq.core.UpdateState
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.network.FleetParser
import app.sabbaq.network.SupabaseClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the administration publishes to every app: the newest release (with the minimum version)
 * and the shared lexicon learned from the fleet. Both are cached on the device, so the minimum
 * keeps applying offline and a restart reads with the learned words at once.
 */
object FleetRepository {
    private const val PREFS = "sabbaq_fleet"
    private const val KEY_RELEASE = "release_v1"
    private const val KEY_LEXICON = "lexicon_v1"
    private const val REFRESH_INTERVAL_MS = 6 * 60 * 60_000L

    @Volatile private var appContext: Context? = null
    @Volatile private var lastRefreshAt = 0L
    private val releaseState = MutableStateFlow(AppRelease())
    @Volatile private var lexicon = SharedLexicon()
    @Volatile private var shadow = SharedLexicon()
    private class Merged(val own: EngineConfig, val lexicon: Int, val zones: ZonePolicyRepository.Policy, val result: EngineConfig)
    @Volatile private var merged: Merged? = null

    val release: StateFlow<AppRelease> = releaseState.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_RELEASE, null)?.let { decode(it)?.let(FleetParser::release) }?.let { releaseState.value = it }
        prefs.getString(KEY_LEXICON, null)?.let(::decode)?.let { o ->
            FleetParser.lexicon(o)?.let { lexicon = it }
            shadow = FleetParser.shadow(o)
        }
    }

    fun updateState(): UpdateState = releaseState.value.state(BuildConfig.VERSION_CODE.toLong())

    /** Below the published minimum: the gate refuses taps until the driver updates. */
    fun updateRequired(): Boolean = updateState() == UpdateState.REQUIRED

    fun lexicon(): SharedLexicon = lexicon

    /** Words the server asks every phone to test read-only (ShadowCheck). */
    fun shadow(): SharedLexicon = shadow

    /**
     * The driver's settings with the shared words and the administration's shared exclude zones
     * merged in; recomputed only when one of them changes.
     */
    fun effectiveConfig(own: EngineConfig): EngineConfig {
        val lex = lexicon
        val zones = ZonePolicyRepository.policy.value
        merged?.let { m -> if (m.own === own && m.lexicon == lex.version && m.zones === zones) return m.result }
        val result = ZonePolicyRepository.applyTo(lex.applyTo(own))
        merged = Merged(own, lex.version, zones, result)
        return result
    }

    /** Fetches the release, the lexicon and the zone policy when the cached copy is older than six hours (or [force]). */
    suspend fun refreshIfStale(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastRefreshAt != 0L && now - lastRefreshAt < REFRESH_INTERVAL_MS) return
        if (!SupabaseClient.hasSavedSession()) return
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var any = false
        SupabaseClient.appUpdateInfo().getOrNull()?.let { r ->
            val before = updateState()
            releaseState.value = r
            prefs?.edit()?.putString(KEY_RELEASE, encode(r))?.apply()
            if (updateState() != UpdateState.NONE && updateState() != before) UpdateTelemetry.offered(r)
            any = true
        }
        SupabaseClient.sharedLexicon().getOrNull()?.let { o ->
            val l = FleetParser.lexicon(o) ?: return@let
            if (l.version != lexicon.version) { lexicon = l; merged = null }
            shadow = FleetParser.shadow(o)
            prefs?.edit()?.putString(KEY_LEXICON, o.toString())?.apply()
            any = true
        }
        runCatching { ZonePolicyRepository.refresh() }
        if (any) lastRefreshAt = now
        appContext?.let { runCatching { UpdateNotifier.maybeNotify(it, releaseState.value) } }
    }

    private fun decode(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()

    private fun encode(r: AppRelease): String = buildJsonObject {
        put("latest_code", r.latestCode); put("latest_name", r.latestName); put("apk_url", r.apkUrl)
        put("apk_sha256", r.apkSha256); put("apk_size", r.apkSize); put("notes_ar", r.notesAr)
        put("notes_en", r.notesEn); put("min_code", r.minCode)
    }.toString()
}
