package app.sabbaq.network

import app.sabbaq.core.AppRelease
import app.sabbaq.core.SharedLexicon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Reads `app_update_info` and `shared_lexicon` exactly as the server returns them. */
object FleetParser {
    fun release(o: JsonObject): AppRelease? {
        val latest = o.long("latest_code") ?: return null
        return AppRelease(
            latestCode = latest,
            latestName = o.str("latest_name").orEmpty().take(32),
            apkUrl = o.str("apk_url").orEmpty().take(300),
            apkSha256 = o.str("apk_sha256").orEmpty().lowercase(),
            apkSize = o.long("apk_size") ?: 0,
            notesAr = o.str("notes_ar").orEmpty().take(1000),
            notesEn = o.str("notes_en").orEmpty().take(1000),
            minCode = o.long("min_code") ?: 0,
        )
    }

    fun lexicon(o: JsonObject): SharedLexicon? {
        val version = (o["version"] as? JsonPrimitive)?.intOrNull ?: return null
        fun words(key: String) = (o[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter(SharedLexicon::valid)
            .take(30)
        val ids = (o["accept_ids"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter(SharedLexicon::validId)
            .take(10)
        return SharedLexicon(version, words("accept"), words("time"), words("currency"), words("noise"), ids)
    }

    /** The shadow words to test (the "shadow" object of `shared_lexicon`); empty when none. */
    fun shadow(o: JsonObject): SharedLexicon = (o["shadow"] as? JsonObject)?.let(::lexicon) ?: SharedLexicon()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
}
