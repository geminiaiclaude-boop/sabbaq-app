package app.sabbaq.stats

import app.sabbaq.core.ReasonCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stored history may name reason codes a later release retired. One unknown enum name
 * fails the whole document, so they are dropped from [field] (a list or a keyed map)
 * of every row before decoding instead of discarding the driver's history.
 */
internal object StoredReasonCodes {
    private val known = ReasonCode.entries.mapTo(HashSet()) { it.name }

    fun dropRetired(json: Json, raw: String, field: String): String {
        val rows = json.parseToJsonElement(raw) as? JsonArray ?: return raw
        return JsonArray(rows.map { row -> cleanRow(row, field) }).toString()
    }

    private fun cleanRow(row: JsonElement, field: String): JsonElement {
        if (row !is JsonObject) return row
        val cleaned = when (val value = row[field]) {
            is JsonArray -> JsonArray(value.filter { (it as? JsonPrimitive)?.content in known })
            is JsonObject -> JsonObject(value.filterKeys { it in known })
            else -> return row
        }
        return JsonObject(row + (field to cleaned))
    }
}
