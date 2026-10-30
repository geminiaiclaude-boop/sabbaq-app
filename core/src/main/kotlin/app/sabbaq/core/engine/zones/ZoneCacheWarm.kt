package app.sabbaq.core.engine.zones

import app.sabbaq.core.engine.DecisionEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.sqrt

/** A street or place name found inside a zone, with its points inside the zone. */
data class ZoneName(val name: String, val points: List<DoubleArray>)

/**
 * The reference engine's zone cache warming (`ZH.resolver`, `fragment.factory`): before a zone is
 * switched on, the named streets, amenities, shops, tourism and offices inside it are fetched from
 * OpenStreetMap (Overpass) and stored in the address → point cache, so an offer naming any of them
 * is placed at once. When OpenStreetMap names nothing, up to 24 grid points inside the zone are
 * reverse geocoded instead (60 calls at most). Pure parts; the app does the network.
 */
object ZoneCacheWarm {
    /** `fragment.process`: Overpass servers, tried in order, each by POST then GET, twice over. */
    val OVERPASS_URLS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    const val MAX_CALLS = 60
    const val GRID_MAX = 24

    private val json = Json { ignoreUnknownKeys = true }
    private val GENERIC = setOf("unnamed road", "unnamed", "no name", "بدون اسم", "بدون اسم.", "road", "street", "طريق", "شارع")
    private val PLUS_CODE = Regex("^[0-9a-z]{2,}\\+[0-9a-z]{2,}$")
    private val NAME_TAGS = listOf("name", "name:en", "name:ar", "int_name")

    /** `ZH.observer`: too short, a generic word, a plus code, or only digits: not a usable name. */
    fun junk(name: String): Boolean {
        val s = name.trim().lowercase()
        if (s.length < 3 || s in GENERIC) return true
        if (PLUS_CODE.matches(s.replace(" ", ""))) return true
        val solid = s.count { !it.isWhitespace() && !Character.isSpaceChar(it) }
        return solid > 0 && s.count { it.isDigit() } == solid
    }

    /** `fragment.factory`: the Overpass query for every named way and place inside [polygon]. */
    fun overpassQuery(polygon: List<DoubleArray>): String {
        val poly = polygon.joinToString(" ") { "${it[0]} ${it[1]}" }
        return """
            [out:json][timeout:25];
            (
              way["highway"]["name"](poly:"$poly");
              node["amenity"]["name"](poly:"$poly");
              way["amenity"]["name"](poly:"$poly");
              node["shop"]["name"](poly:"$poly");
              way["shop"]["name"](poly:"$poly");
              node["tourism"]["name"](poly:"$poly");
              way["tourism"]["name"](poly:"$poly");
              node["office"]["name"](poly:"$poly");
            );
            out geom 1500;
        """.trimIndent()
    }

    /**
     * `fragment.factory` + `fragment.connect`: each name (its `name`, `name:en`, `name:ar` and
     * `int_name`) with the element's points that lie inside [polygon]; elements outside are dropped.
     */
    fun parseOverpass(body: String, polygon: List<DoubleArray>): List<ZoneName> {
        val elements = runCatching { json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray }.getOrNull() ?: return emptyList()
        val byName = LinkedHashMap<String, MutableList<DoubleArray>>()
        for (e in elements) {
            val o = e as? JsonObject ?: continue
            val tags = o["tags"] as? JsonObject ?: continue
            val lat = o["lat"]?.jsonPrimitive?.doubleOrNull
            val lon = o["lon"]?.jsonPrimitive?.doubleOrNull
            val pts = if (lat != null && lon != null) {
                if (inside(lat, lon, polygon)) listOf(doubleArrayOf(lat, lon)) else emptyList()
            } else {
                (o["geometry"] as? JsonArray).orEmpty().mapNotNull { g ->
                    val p = g as? JsonObject ?: return@mapNotNull null
                    val a = p["lat"]?.jsonPrimitive?.doubleOrNull
                    val b = p["lon"]?.jsonPrimitive?.doubleOrNull
                    if (a == null || b == null || !inside(a, b, polygon)) null else doubleArrayOf(a, b)
                }
            }
            if (pts.isEmpty()) continue
            for (tag in NAME_TAGS) {
                val name = tags[tag]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isEmpty()) continue
                val list = byName.getOrPut(name) { ArrayList() }
                for (p in pts) if (list.none { it[0] == p[0] && it[1] == p[1] }) list += p
            }
        }
        return byName.map { (name, pts) -> ZoneName(name, pts) }
    }

    /**
     * `ZH.dispatch` / `ZH.execute`: the names worth adding — not junk, not already in the cache
     * ([cached] keys) and once each — keyed by the cache's folded key.
     */
    fun newEntries(found: List<ZoneName>, cached: Set<String>): Map<String, ZoneName> {
        val out = LinkedHashMap<String, ZoneName>()
        for (n in found) {
            if (n.points.isEmpty() || junk(n.name)) continue
            val key = DecisionEngine.fold(n.name)
            if (key.isEmpty() || key in cached || key in out) continue
            out[key] = n
        }
        return out
    }

    /**
     * `ZH.adapter`: up to 24 points on a grid inside the zone (step √(area/24)° clamped to
     * 0.0004–0.06°, starting half a step in); the centre alone when no grid point falls inside.
     */
    fun grid(polygon: List<DoubleArray>): List<DoubleArray> {
        if (polygon.size < 3) return emptyList()
        val minLat = polygon.minOf { it[0] }; val maxLat = polygon.maxOf { it[0] }
        val minLng = polygon.minOf { it[1] }; val maxLng = polygon.maxOf { it[1] }
        val step = gridStep(polygon)
        val out = ArrayList<DoubleArray>()
        var lat = minLat + step / 2
        while (lat < maxLat && out.size < GRID_MAX) {
            var lng = minLng + step / 2
            while (lng < maxLng && out.size < GRID_MAX) {
                if (inside(lat, lng, polygon)) out += doubleArrayOf(lat, lng)
                lng += step
            }
            lat += step
        }
        if (out.isEmpty()) {
            val c = doubleArrayOf((minLat + maxLat) / 2, (minLng + maxLng) / 2)
            if (inside(c[0], c[1], polygon)) out += c
        }
        return out
    }

    /** `ZH.adapter`'s step in degrees: √(bounding area / 24), clamped to 0.0004–0.06°. */
    fun gridStep(polygon: List<DoubleArray>): Double {
        val h = (polygon.maxOf { it[0] } - polygon.minOf { it[0] }).coerceAtLeast(1e-6)
        val w = (polygon.maxOf { it[1] } - polygon.minOf { it[1] }).coerceAtLeast(1e-6)
        return sqrt(h * w / GRID_MAX).coerceIn(4.0e-4, 0.06)
    }

    /** `ZH.handler`: the Places search radius around each grid point, ¾ of a step, 250–1500 m. */
    fun placesRadius(polygon: List<DoubleArray>): Int = (gridStep(polygon) * 111_000.0 * 0.75).toInt().coerceIn(250, 1500)

    /**
     * The cached names with at least one point inside [polygon] (the reference's «كاش هذه
     * المنطقة»): the stored display name when there is one, else the cache key.
     */
    fun namesInside(cache: JsonObject, polygon: List<DoubleArray>): List<ZoneName> {
        if (polygon.size < 3) return emptyList()
        val out = ArrayList<ZoneName>()
        for ((key, value) in cache) {
            val o = value as? JsonObject ?: continue
            val pts = PoiIndex.points(o).filter { inside(it[0], it[1], polygon) }
            if (pts.isEmpty()) continue
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: key
            if (!junk(name)) out += ZoneName(name, pts)
        }
        return out.sortedBy { it.name }
    }

    /**
     * «اقتراح كلمات لهذه المنطقة»: names inside the zone that the keyword list ([keywords],
     * comma or newline separated) does not already hold, compared folded.
     */
    fun suggestions(inside: List<ZoneName>, keywords: String): List<String> {
        val have = keywords.split(',', '،', '\n').map { DecisionEngine.fold(it.trim()) }.filter { it.isNotEmpty() }.toSet()
        val seen = HashSet<String>()
        return inside.map { it.name }.filter { n ->
            val k = DecisionEngine.fold(n)
            k.isNotEmpty() && k !in have && seen.add(k)
        }
    }

    /**
     * `ZH.parser`'s cell: the destination zones and the driver's 2 km square. The road is warmed
     * again only when either changes (`corridor_warm_signature`).
     */
    fun corridorSignature(zonesJson: String, lat: Double, lng: Double): String {
        val y = Math.round(lat * 110.54 / 2.0)
        val x = Math.round(lng * 111.32 * kotlin.math.cos(Math.toRadians(lat)) / 2.0)
        return "${zonesJson.hashCode()}_${y}_$x"
    }

    /** `ZH.parser`: points [corridorKm] × 2 apart (1 km at least). */
    fun corridorStepKm(corridorKm: Double): Double = (corridorKm * 2.0).coerceAtLeast(1.0)

    /** `ZH.controller`: the mean of the zone's corners. */
    fun centroid(polygon: List<DoubleArray>): DoubleArray =
        doubleArrayOf(polygon.sumOf { it[0] } / polygon.size, polygon.sumOf { it[1] } / polygon.size)

    /**
     * `ZH.listener`: up to 20 points every [stepKm] on the straight line from the driver to the
     * zone's centre, short of the centre, leaving out points inside any of [zonesJson]'s zones
     * (those were warmed with the zone). None when the zone is nearer than one step.
     */
    fun corridorPoints(dLat: Double, dLng: Double, cLat: Double, cLng: Double, zonesJson: String, stepKm: Double): List<DoubleArray> {
        if (stepKm <= 0.0) return emptyList()
        val dy = cLat - dLat
        val dx = cLng - dLng
        val km = sqrt((110.54 * dy) * (110.54 * dy) + (kotlin.math.cos(Math.toRadians(dLat)) * 111.32 * dx).let { it * it })
        if (km < stepKm) return emptyList()
        val out = ArrayList<DoubleArray>()
        for (i in 1..20) {
            val t = i * stepKm / km
            if (t >= 1.0) break
            val lat = dLat + dy * t
            val lng = dLng + dx * t
            if (!ZoneGeo.insideAny(lat, lng, zonesJson)) out += doubleArrayOf(lat, lng)
        }
        return out
    }

    /** `ZH.connect`: the square of half side [km] around a point, as polygon corners. */
    fun box(lat: Double, lng: Double, km: Double): List<DoubleArray> {
        val dLat = km / 110.54
        val dLng = km / (kotlin.math.cos(Math.toRadians(lat)) * 111.32)
        return listOf(
            doubleArrayOf(lat - dLat, lng - dLng), doubleArrayOf(lat - dLat, lng + dLng),
            doubleArrayOf(lat + dLat, lng + dLng), doubleArrayOf(lat + dLat, lng - dLng),
        )
    }

    /** `Zh.observer`: point-in-polygon over `[lat, lng]` corners. */
    fun inside(lat: Double, lng: Double, polygon: List<DoubleArray>): Boolean =
        ZoneGeo.contains(lat, lng, DoubleArray(polygon.size) { polygon[it][0] }, DoubleArray(polygon.size) { polygon[it][1] })

    /** The zone's corners from its JSON points (`[[lat, lng], …]`). */
    fun corners(points: JsonArray): List<DoubleArray> = points.mapNotNull { p ->
        val a = p as? JsonArray ?: return@mapNotNull null
        val lat = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull
        val lng = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull
        if (lat == null || lng == null) null else doubleArrayOf(lat, lng)
    }
}

/**
 * The administration's zone limit («بلغت الحد الأقصى للمناطق المفعّلة»): how many of the
 * driver's own zones (destination and exclude together) may be switched on at once. 0 = no limit.
 */
object ZoneLimit {
    /** Switching one more zone on is allowed with [activeNow] already on. */
    fun canActivate(activeNow: Int, max: Int): Boolean = max <= 0 || activeNow < max

    fun activeCount(vararg zonesJson: String): Int = zonesJson.sumOf { j ->
        runCatching {
            Json.parseToJsonElement(j).jsonArray.count { z ->
                val o = z as? JsonObject
                o != null && (o["active"]?.jsonPrimitive?.contentOrNull != "false") && (o["points"] as? JsonArray)?.size?.let { it >= 3 } == true
            }
        }.getOrDefault(0)
    }
}
