package app.sabbaq.admin

import app.sabbaq.core.engine.zones.ZoneGeo
import app.sabbaq.network.SupabaseClient
import app.sabbaq.network.SupabaseClient.FeatureException
import app.sabbaq.network.SupabaseClient.FeatureFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The administration side of 0017: shared exclude zones per country, the zone limits. */
object AdminZones {
    private val json = Json { ignoreUnknownKeys = true }
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    /** The countries the server accepts (`admin_set_global_zones`). */
    val COUNTRIES = listOf("SA", "JO")

    data class Country(val enabled: Boolean = false, val zonesJson: String = "[]")
    data class Policy(val version: Int, val defaultMax: Int, val proximityKm: Double, val countries: Map<String, Country>)
    data class DriverLimit(val own: Int?, val default: Int, val country: String?)

    suspend fun policy(): Result<Policy> = SupabaseClient.adminRpc("admin_zone_policy").mapCatching { parse(it) }

    suspend fun saveCountry(country: String, enabled: Boolean, zonesJson: String): Result<Policy> {
        if (country !in COUNTRIES || !(zonesJson == "[]" || ZoneGeo.hasDrawn(zonesJson))) return Result.failure(FeatureException(FeatureFailure.REJECTED))
        val zones = runCatching { json.parseToJsonElement(zonesJson) as JsonArray }.getOrNull() ?: return Result.failure(FeatureException(FeatureFailure.REJECTED))
        return SupabaseClient.adminRpc("admin_set_global_zones", buildJsonObject {
            put("p_country", country); put("p_enabled", enabled); put("p_zones", zones)
        }).mapCatching { parse(it) }
    }

    suspend fun saveLimits(defaultMax: Int, proximityKm: Double): Result<Policy> {
        if (defaultMax !in 0..100 || proximityKm !in 0.0..1000.0) return Result.failure(FeatureException(FeatureFailure.REJECTED))
        return SupabaseClient.adminRpc("admin_set_zone_limits", buildJsonObject {
            put("p_default_max", defaultMax); put("p_proximity_km", proximityKm)
        }).mapCatching { parse(it) }
    }

    suspend fun driverLimit(driverId: String): Result<DriverLimit> {
        if (!UUID.matches(driverId)) return Result.failure(FeatureException(FeatureFailure.REJECTED))
        return SupabaseClient.adminRpc("admin_driver_zone_limit", buildJsonObject { put("p_driver", driverId) }).mapCatching { parseLimit(it) }
    }

    /** [limit] null returns the driver to the default. */
    suspend fun setDriverLimit(driverId: String, limit: Int?): Result<DriverLimit> {
        if (!UUID.matches(driverId) || (limit != null && limit !in 0..100)) return Result.failure(FeatureException(FeatureFailure.REJECTED))
        return SupabaseClient.adminRpc("admin_set_driver_zone_limit", buildJsonObject {
            put("p_driver", driverId); put("p_limit", if (limit == null) JsonNull else JsonPrimitive(limit))
        }).mapCatching { parseLimit(it) }
    }

    private fun parse(body: String): Policy {
        val o = json.parseToJsonElement(body) as? JsonObject ?: throw FeatureException(FeatureFailure.UNAVAILABLE)
        val countries = (o["countries"] as? JsonObject).orEmpty().mapValues { (_, v) ->
            val c = v as? JsonObject
            Country(c?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: false, (c?.get("zones") as? JsonArray)?.toString() ?: "[]")
        }
        return Policy(
            version = o["version"]?.jsonPrimitive?.intOrNull ?: 0,
            defaultMax = o["default_max_zones"]?.jsonPrimitive?.intOrNull ?: 0,
            proximityKm = o["proximity_km"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            countries = countries,
        )
    }

    private fun parseLimit(body: String): DriverLimit {
        val o = json.parseToJsonElement(body) as? JsonObject ?: throw FeatureException(FeatureFailure.UNAVAILABLE)
        return DriverLimit((o["own"] as? JsonPrimitive)?.intOrNull, o["default"]?.jsonPrimitive?.intOrNull ?: 0,
            (o["country"] as? JsonPrimitive)?.contentOrNull)
    }
}
