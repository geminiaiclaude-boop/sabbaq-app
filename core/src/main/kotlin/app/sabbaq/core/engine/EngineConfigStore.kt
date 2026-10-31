package app.sabbaq.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How the driver's settings are written and read. Every field is written, so a later change of
 * a default never changes a driver's saved settings. Settings written by versions up to
 * 2.0.3-20261030 left out fields equal to their default; those fields are read with the
 * defaults of that time, so a driver who had the price or time check on keeps it on.
 */
object EngineConfigStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Defaults before 2.0.3-20261030 (owner, 2026-10-30: no condition is on at first install). */
    private val LEGACY_DEFAULTS = mapOf(
        "priceEnabled" to true,
        "timeEnabled" to true,
        "destIncludeRejectOnUnknown" to true,
        "destZonesRejectOnUnknown" to true,
    )

    fun encode(config: EngineConfig): String = json.encodeToString(EngineConfig.serializer(), config)

    /** The saved settings, or null when the text is not a settings object. */
    fun decode(stored: String): EngineConfig? = runCatching {
        val saved = json.parseToJsonElement(stored) as JsonObject
        val filled = JsonObject(LEGACY_DEFAULTS.filterKeys { it !in saved }.mapValues { JsonPrimitive(it.value) } + saved)
        json.decodeFromJsonElement(EngineConfig.serializer(), filled)
    }.getOrNull()
}
