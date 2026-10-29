package app.sabbaq.core.engine.zones

import app.sabbaq.core.engine.DecisionEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `unit` (ResolvedCoords): the anchored point, every candidate point, the match kind and its precision. */
data class ResolvedCoords(
    val point: DoubleArray?,
    val allPoints: List<DoubleArray>,
    val matchType: String?,
    val precisionM: Double,
)

/**
 * The reference engine's address → point cache (`I70` + `item`). Keys are folded address texts; each value
 * is `{"lat","lng"}` or `{"points":[{"lat","lng"},…]}`. Lookup tries the exact key, the key with
 * its number-only words dropped, then fuzzy word matches, keeps candidates in the driver's
 * region or within the given distance, and anchors on the one nearest the driver.
 */
class PoiIndex(private val root: JsonObject) {
    private class Slice(val key: String, val tokens: List<String>, val distinct: String?)

    private val slices: List<Slice> = root.keys.mapNotNull { key ->
        val tokens = tokens(key)
        if (tokens.isEmpty()) null else Slice(key, tokens, distinctToken(tokens))
    }

    val size: Int get() = root.size

    /** `item.listener`. */
    fun resolve(address: String, driverLat: Double, driverLng: Double, maxKm: Double): ResolvedCoords {
        val key = DecisionEngine.fold(address)
        class Hit(val kind: String, val key: String, val value: JsonObject)
        val hits = ArrayList<Hit>()
        (root[key] as? JsonObject)?.let { hits += Hit("exact", key, it) }
        val stripped = withoutNumberWords(key)
        var strippedHit = false
        if (hits.isEmpty() && stripped != key) {
            (root[stripped] as? JsonObject)?.let { hits += Hit("exact", stripped, it); strippedHit = true }
        }
        val tokens = tokens(key)
        if (tokens.isNotEmpty()) {
            val distinct = distinctToken(tokens)
            for (s in slices) {
                if (s.key == key || (strippedHit && s.key == stripped)) continue
                if (tokensMatch(tokens, s.tokens) || nearSame(distinct, s.distinct)) {
                    (root[s.key] as? JsonObject)?.let { hits += Hit("fuzzy", s.key, it) }
                }
            }
        }
        if (hits.isEmpty()) return ResolvedCoords(null, emptyList(), null, 500.0)
        val points = ArrayList<DoubleArray>()
        val precision = ArrayList<Double>()
        for (h in hits) {
            val pts = points(h.value)
            val p = precisionM(pts.size, h.kind, h.key)
            for (pt in pts) { points += pt; precision += p }
        }
        if (points.isEmpty()) return ResolvedCoords(null, emptyList(), null, 500.0)
        val driverKnown = !(driverLat == 0.0 && driverLng == 0.0)
        val candidates: List<Int> = if (maxKm <= 0.0 || !driverKnown) {
            points.indices.toList()
        } else {
            val region = region(driverLat, driverLng)
            points.indices.filter { i ->
                (region >= 0 && region(points[i][0], points[i][1]) == region) ||
                    ZoneGeo.haversineKm(driverLat, driverLng, points[i][0], points[i][1]) <= maxKm
            }.ifEmpty { return ResolvedCoords(null, emptyList(), hits.first().kind, 500.0) }
        }
        val chosen = if (driverKnown) {
            candidates.minByOrNull { ZoneGeo.haversineKm(driverLat, driverLng, points[it][0], points[it][1]) } ?: candidates.first()
        } else candidates.first()
        return ResolvedCoords(points[chosen], candidates.map { points[it] }, hits.first().kind, precision[chosen])
    }

    /** `item.callback`: the point stored under a national short address, nearest the driver. */
    fun nationalCode(code: String, driverLat: Double, driverLng: Double): DoubleArray? {
        val value = root[code] as? JsonObject ?: return null
        val pts = points(value)
        if (pts.isEmpty()) return null
        if (driverLat == 0.0 && driverLng == 0.0) return pts.first()
        return pts.minByOrNull { ZoneGeo.haversineKm(driverLat, driverLng, it[0], it[1]) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val SPLIT = Regex("[\\s,.\\-]+")
        private val GENERIC = setOf("street", "road", "ave", "avenue", "blvd", "circle", "square", "building", "tower", "near",
            "شارع", "طريق", "دوار", "حي", "ميدان", "مبنى", "برج", "عماره")
        private val CITIES = setOf("amman", "عمان", "riyadh", "الرياض", "jeddah", "جده")

        /** The reference engine's 14 city circles (`P20.process`: lat, lng, radius km): Saudi cities, then Jordan. */
        private val REGIONS = arrayOf(
            doubleArrayOf(24.71, 46.68, 60.0), doubleArrayOf(21.54, 39.19, 55.0), doubleArrayOf(21.42, 39.83, 30.0),
            doubleArrayOf(24.47, 39.61, 40.0), doubleArrayOf(26.42, 50.09, 60.0), doubleArrayOf(21.27, 40.42, 35.0),
            doubleArrayOf(26.33, 43.97, 40.0), doubleArrayOf(28.38, 36.57, 35.0), doubleArrayOf(18.22, 42.51, 40.0),
            doubleArrayOf(16.89, 42.57, 30.0), doubleArrayOf(27.51, 41.69, 30.0), doubleArrayOf(31.95, 35.93, 45.0),
            doubleArrayOf(32.55, 35.85, 30.0), doubleArrayOf(29.53, 35.01, 25.0),
        )

        fun parse(cacheJson: String?): PoiIndex? = cacheJson?.takeIf { it.isNotBlank() }
            ?.let { runCatching { PoiIndex(json.parseToJsonElement(it).jsonObject) }.getOrNull() }

        /** `P20.provider`: index of the nearest city circle containing the point, or -1. */
        fun region(lat: Double, lng: Double): Int {
            var best = -1
            var bestKm = Double.MAX_VALUE
            for (i in REGIONS.indices) {
                val km = ZoneGeo.haversineKm(lat, lng, REGIONS[i][0], REGIONS[i][1])
                if (km <= REGIONS[i][2] && km < bestKm) { best = i; bestKm = km }
            }
            return best
        }

        /** `item.observer`: words of 3+ characters that are not only digits. */
        fun tokens(text: String): List<String> = text.split(SPLIT).filter { w -> w.length >= 3 && w.any { !it.isDigit() } }

        /** `item.provider`: the single word that is neither generic nor a city, else null. */
        fun distinctToken(tokens: List<String>): String? {
            var found: String? = null
            for (t in tokens) {
                if (t in GENERIC || t in CITIES) continue
                if (found != null) return null
                found = t
            }
            return found
        }

        /** `item.controller`: drop the number-only words when the key has digits. */
        fun withoutNumberWords(key: String): String {
            if (key.none { it.isDigit() }) return key
            val words = key.split(' ')
            val kept = words.filter { w -> w.isEmpty() || w.any { !it.isDigit() } }
            if (kept.size == words.size || kept.isEmpty()) return key
            return kept.joinToString(" ").trim()
        }

        /**
         * `item.dispatch`: every word of the shorter list (≥ 2 words) is in the other, or a typo
         * of one (same first letter, 1 edit up to 6 letters, 2 above 4 letters… as the reference engine), at
         * least one shared word is a specific 4+ letter word, and at most half the words are typos.
         */
        fun tokensMatch(a: List<String>, b: List<String>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            val (small, large) = if (a.size <= b.size) a to b else b to a
            if (small.size < 2) return false
            var misses = 0
            var strong = false
            for (s in small) {
                if (s in large) {
                    if (s.length >= 4 && s !in GENERIC) strong = true
                    continue
                }
                misses++
                val typo = large.any { t ->
                    when {
                        s == t -> true
                        s.isEmpty() || t.isEmpty() || s[0] != t[0] -> false
                        else -> {
                            val m = maxOf(s.length, t.length)
                            val k = if (m <= 4) 0 else if (m <= 6) 1 else 2
                            k > 0 && withinEdits(k, s, t)
                        }
                    }
                }
                if (!typo) return false
            }
            if (!strong) return false
            return misses <= maxOf(1, small.size / 2)
        }

        /** `item.manager`: equal, or 5+ letters with the same first letter and one edit apart. */
        fun nearSame(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            if (a == b) return true
            return a.length >= 5 && b.length >= 5 && a[0] == b[0] && withinEdits(1, a, b)
        }

        /** `item.builder`: optimal string alignment distance (adjacent swaps count once) ≤ [k]. */
        fun withinEdits(k: Int, a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > k) return false
            val n = a.length
            val m = b.length
            var prev2 = IntArray(m + 1)
            var prev = IntArray(m + 1) { it }
            var cur = IntArray(m + 1)
            for (i in 1..n) {
                cur[0] = i
                var rowMin = i
                for (j in 1..m) {
                    var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > k) return false
                val t = prev2; prev2 = prev; prev = cur; cur = t
            }
            return prev[m] <= k
        }

        /** `item.process`: the `points` list, else the single lat/lng. */
        fun points(value: JsonObject): List<DoubleArray> {
            val arr = value["points"] as? JsonArray
            if (arr != null && arr.isNotEmpty()) {
                val out = arr.mapNotNull { p ->
                    val o = p as? JsonObject ?: return@mapNotNull null
                    val lat = o["lat"]?.jsonPrimitive?.doubleOrNull
                    val lng = o["lng"]?.jsonPrimitive?.doubleOrNull
                    if (lat == null || lng == null || lat.isNaN() || lng.isNaN()) null else doubleArrayOf(lat, lng)
                }
                if (out.isNotEmpty()) return out
            }
            val lat = value["lat"]?.jsonPrimitive?.doubleOrNull
            val lng = value["lng"]?.jsonPrimitive?.doubleOrNull
            return if (lat == null || lng == null || lat.isNaN() || lng.isNaN()) emptyList() else listOf(doubleArrayOf(lat, lng))
        }

        /** `item.execute`: street-like or multi-point matches are broad. */
        fun precisionM(pointCount: Int, kind: String, key: String): Double {
            val broad = key.startsWith("طريق ") || key.startsWith("شارع ") || key.startsWith("حي ") ||
                key.endsWith(" street") || key.endsWith(" road") || key.endsWith(" rd") || key.endsWith(" st") || pointCount > 1
            return when {
                kind == "exact" && !broad -> 120.0
                kind == "exact" -> 1500.0
                broad -> 2500.0
                else -> 500.0
            }
        }
    }
}
