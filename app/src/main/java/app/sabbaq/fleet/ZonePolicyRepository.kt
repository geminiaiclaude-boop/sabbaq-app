package app.sabbaq.fleet

import android.content.Context
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.network.SupabaseClient
import app.sabbaq.notifications.NotificationCenter
import app.sabbaq.notifications.NotificationTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the administration sets for the driver's zones (migration 0017): the shared exclude zones of
 * the driver's country, applied as a default exclude list the driver may switch off for themselves,
 * how far such a zone may be and still apply, and how many own zones may be on at once. Cached on
 * the device so it keeps applying offline.
 */
object ZonePolicyRepository {
    data class Policy(
        val version: Int = 0,
        val country: String? = null,
        val zonesJson: String = "[]",
        /** 0 = no limit. */
        val maxActiveZones: Int = 0,
        val proximityKm: Double = 0.0,
    )

    private const val PREFS = "sabbaq_fleet"
    private const val KEY_POLICY = "zone_policy_v1"
    private const val KEY_ANNOUNCED = "zone_policy_announced"

    @Volatile private var appContext: Context? = null
    private val state = MutableStateFlow(Policy())
    val policy: StateFlow<Policy> = state.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POLICY, null)
            ?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
            ?.let { state.value = parse(it) }
    }

    /** The driver's settings with the shared zones applied (the engine opts out by itself when asked). */
    fun applyTo(config: EngineConfig): EngineConfig {
        val p = state.value
        if (p.zonesJson == config.globalDestExcludeZonesJson && p.proximityKm == config.globalZoneProximityKm) return config
        return config.copy(globalDestExcludeZonesJson = p.zonesJson, globalZoneProximityKm = p.proximityKm)
    }

    suspend fun refresh() {
        val o = SupabaseClient.zonePolicy().getOrNull() ?: return
        val p = parse(o)
        state.value = p
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit().putString(KEY_POLICY, o.toString()).apply()
        // «تمت إضافة مناطق استثناء جديدة»: once per published version that holds zones.
        if (p.version > prefs.getInt(KEY_ANNOUNCED, 0) && p.zonesJson != "[]") {
            NotificationCenter.post(NotificationTemplates.newSharedZones(p.version, System.currentTimeMillis()))
        }
        if (p.version > prefs.getInt(KEY_ANNOUNCED, 0)) prefs.edit().putInt(KEY_ANNOUNCED, p.version).apply()
    }

    private fun parse(o: JsonObject): Policy = Policy(
        version = o["version"]?.jsonPrimitive?.intOrNull ?: 0,
        country = o["country"]?.jsonPrimitive?.contentOrNull,
        zonesJson = (o["zones"] as? JsonArray)?.toString() ?: "[]",
        maxActiveZones = o["max_active_zones"]?.jsonPrimitive?.intOrNull ?: 0,
        proximityKm = o["proximity_km"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    )
}
