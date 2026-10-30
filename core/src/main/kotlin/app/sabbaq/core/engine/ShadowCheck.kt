package app.sabbaq.core.engine

import app.sabbaq.core.SharedLexicon

/**
 * The phone's half of automatic engine learning: when a screen fails to read, re-read it with each
 * shadow word the server proposed, read-only and never tapping, and report the first word that
 * would have made a complete offer (accept control, fare and arrival time). Only the list that
 * matches the miss is tried: time words for "no_minutes", fare words for "no_price", accept words
 * for "no_accept"; at most 10.
 */
object ShadowCheck {
    data class Hit(val version: Int, val category: Char, val index: Int) {
        /** Telemetry reason: distinct per word, so the aggregator never merges two words' counts. */
        val reason: String get() = "shadow_hit:$version:$category$index"
        val detail: String get() = "lv=$version,c=$category,i=$index"
    }

    fun find(base: EngineConfig, shadow: SharedLexicon, missReason: String, read: (EngineConfig) -> ParsedRide?): Hit? {
        if (shadow.version <= 0) return null
        val (category, words) = when (missReason) {
            "no_minutes" -> 't' to shadow.time
            "no_price" -> 'c' to shadow.currency
            "no_accept" -> 'a' to shadow.accept
            else -> return null
        }
        words.take(10).forEachIndexed { i, word ->
            if (!SharedLexicon.valid(word)) return@forEachIndexed
            val one = SharedLexicon(
                version = 1,
                accept = if (category == 'a') listOf(word) else emptyList(),
                time = if (category == 't') listOf(word) else emptyList(),
                currency = if (category == 'c') listOf(word) else emptyList(),
            )
            val ride = runCatching { read(one.applyTo(base)) }.getOrNull()
            if (ride != null && ride.acceptNode != null && ride.price != null && ride.minutes != null) return Hit(shadow.version, category, i)
        }
        return null
    }
}
