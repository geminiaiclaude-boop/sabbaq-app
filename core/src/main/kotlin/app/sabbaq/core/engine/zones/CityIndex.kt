package app.sabbaq.core.engine.zones

import app.sabbaq.core.engine.DecisionEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * One named place of the driver's city: a district, a landmark or a main road. [names] holds every
 * spelling OpenStreetMap knows (Arabic, English, alternative and old names); [points] its centre,
 * or one point per stretch for a road; [osm] "R123"/"W45"/"N6" when its outline can be fetched.
 */
data class CityPlace(val names: List<String>, val kind: String, val points: List<DoubleArray>, val osm: String? = null) {
    val name: String get() = names.first()
}

/** A bounding box in degrees. */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(lat: Double, lng: Double) = lat in south..north && lng in west..east
    /** Grown by [km] on every side. */
    fun grown(km: Double): GeoBox {
        val dLat = km / 110.54
        val dLng = km / (111.32 * cos(Math.toRadians((south + north) / 2)).coerceAtLeast(0.1))
        return GeoBox(south - dLat, west - dLng, north + dLat, east + dLng)
    }
}

/**
 * The driver's city, downloaded once (districts, landmarks and main roads with every spelling):
 * instant type-ahead suggestions in the zone editor without a request per letter, and extra names
 * for placing offer addresses, closing part of the gap between OpenStreetMap and Google.
 * Pure parts; the app does the network.
 */
object CityIndex {
    const val DISTRICT = "district"
    const val LANDMARK = "landmark"
    const val ROAD = "road"
    /** Kept in the address cache at most, districts and landmarks first. */
    const val MAX_CACHE_NAMES = 1500
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val NAME_TAGS = listOf("name", "name:ar", "name:en", "alt_name", "alt_name:ar", "old_name", "short_name", "official_name")
    private val PREFIXES = listOf("حي ", "شارع ", "طريق ", "ش ", "ط ", "district ", "street ", "road ", "al ", "ال")

    /** Districts, landmarks drivers are sent to, and named main roads inside [box]. */
    fun overpassQuery(box: GeoBox): String {
        val b = "${box.south},${box.west},${box.north},${box.east}"
        return """
            [out:json][timeout:60];
            (
              nwr["place"~"^(suburb|neighbourhood|quarter|city_district|village|hamlet)$"]["name"]($b);
              nwr["amenity"~"^(hospital|clinic|university|college|school|mosque|marketplace|police|bus_station|fuel)$"]["name"]($b);
              nwr["shop"~"^(mall|supermarket|department_store)$"]["name"]($b);
              nwr["aeroway"="aerodrome"]["name"]($b);
              nwr["tourism"~"^(hotel|attraction|museum)$"]["name"]($b);
              nwr["leisure"~"^(park|stadium|sports_centre)$"]["name"]($b);
              nwr["office"="government"]["name"]($b);
              way["highway"~"^(motorway|trunk|primary|secondary)$"]["name"]($b);
            );
            out center tags 8000;
        """.trimIndent()
    }

    fun kindOf(tags: JsonObject): String = when {
        tags["place"] != null -> DISTRICT
        tags["highway"] != null -> ROAD
        else -> LANDMARK
    }

    /** Overpass `out center` → places, one per spelling set; a road's stretches become its points. */
    fun parse(body: String): List<CityPlace> {
        val elements = runCatching { json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray }.getOrNull() ?: return emptyList()
        val byKey = LinkedHashMap<String, CityPlace>()
        for (e in elements) {
            val o = e as? JsonObject ?: continue
            val tags = o["tags"] as? JsonObject ?: continue
            val center = o["center"] as? JsonObject
            val lat = (o["lat"] ?: center?.get("lat"))?.jsonPrimitive?.doubleOrNull ?: continue
            val lng = (o["lon"] ?: center?.get("lon"))?.jsonPrimitive?.doubleOrNull ?: continue
            val names = NAME_TAGS.mapNotNull { tags[it]?.jsonPrimitive?.contentOrNull?.trim() }
                .flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() && !ZoneCacheWarm.junk(it) }.distinct()
            if (names.isEmpty()) continue
            val kind = kindOf(tags)
            val key = kind + ":" + DecisionEngine.fold(names.first())
            val type = o["type"]?.jsonPrimitive?.contentOrNull
            val id = o["id"]?.jsonPrimitive?.longOrNull
            val osm = if (type != null && id != null) type.first().uppercaseChar() + id.toString() else null
            val old = byKey[key]
            byKey[key] = if (old == null) CityPlace(names, kind, listOf(doubleArrayOf(lat, lng)), osm.takeIf { kind == DISTRICT })
            else old.copy(names = (old.names + names).distinct(), points = (old.points + doubleArrayOf(lat, lng)).take(40))
        }
        return byKey.values.toList()
    }

    /** The name without a leading «حي»/«شارع»/«ال»…, folded, for matching. */
    fun core(text: String): String {
        var s = DecisionEngine.fold(text).trim()
        repeat(2) { for (p in PREFIXES) if (s.startsWith(p) && s.length > p.length + 2) { s = s.removePrefix(p).trim(); break } }
        return s
    }

    /** Typos forgiven: one up to six letters, two above. */
    private fun typos(q: String) = if (q.length >= 7) 2 else 1

    /**
     * Type-ahead: the places whose spelling matches what was typed — exact, then prefix, then a
     * word prefix, then contained, then one or two typos — nearest the driver first within a rank.
     */
    fun suggest(places: List<CityPlace>, typed: String, near: DoubleArray?, limit: Int = 8): List<CityPlace> {
        val q = core(typed)
        if (q.length < 2) return emptyList()
        val scored = ArrayList<Pair<CityPlace, Double>>()
        for (p in places) {
            var best = 0
            for (n in p.names) {
                val full = DecisionEngine.fold(n)
                val c = core(n)
                val s = when {
                    c == q || full == q -> 100
                    c.startsWith(q) || full.startsWith(q) -> 80
                    c.split(' ').any { it.startsWith(q) } -> 60
                    q.length >= 3 && (c.contains(q) || full.contains(q)) -> 45
                    q.length >= 4 && (c.split(' ').map(::core) + c).any { w -> w.length >= 4 && PoiIndex.withinEdits(typos(q), w.take(q.length), q) } -> 30
                    else -> 0
                }
                if (s > best) best = s
            }
            if (best == 0) continue
            val kindBonus = when (p.kind) { DISTRICT -> 6; LANDMARK -> 4; else -> 0 }
            val km = near?.let { d -> p.points.minOf { distanceKm(d[0], d[1], it[0], it[1]) } } ?: 0.0
            scored += p to (best + kindBonus - minOf(km, 50.0) / 10.0)
        }
        return scored.sortedByDescending { it.second }.map { it.first }.take(limit)
    }

    /** The cache entries the city adds for placing offers: districts and landmarks first, every spelling. */
    fun cacheEntries(places: List<CityPlace>, cached: Set<String>): Map<String, ZoneName> {
        val ordered = places.sortedBy { when (it.kind) { DISTRICT -> 0; LANDMARK -> 1; else -> 2 } }
        val found = ordered.flatMap { p -> p.names.map { ZoneName(it, p.points) } }
        return ZoneCacheWarm.newEntries(found, cached).entries.take(MAX_CACHE_NAMES).associate { it.key to it.value }
    }

    /** Nominatim `reverse` (zoom 10) → the city's name and box, grown so its edges stay reachable. */
    fun cityFromReverse(body: String): Pair<String, GeoBox>? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val bb = (o["boundingbox"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() } ?: return null
        if (bb.size != 4) return null
        val address = o["address"] as? JsonObject
        val name = listOf("city", "town", "state_district", "county", "state").firstNotNullOfOrNull { k ->
            address?.get(k)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: o["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val box = GeoBox(bb[0], bb[2], bb[1], bb[3])
        // A city's box below ~20 km is a district answer; never pan the map into too small an area.
        val minHalfKm = 15.0
        val hKm = (box.north - box.south) * 110.54 / 2
        return name to box.grown(maxOf(5.0, minHalfKm - hKm))
    }

    /**
     * A boundary from Nominatim's `polygon_geojson` (Polygon or the largest ring of a
     * MultiPolygon) as `[lat, lng]` corners, cut to at most [max] corners for a zone.
     */
    fun outline(geojson: JsonObject?, max: Int = 60): List<DoubleArray> {
        val type = geojson?.get("type")?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val coords = geojson["coordinates"] as? JsonArray ?: return emptyList()
        val rings: List<JsonArray> = when (type) {
            "Polygon" -> listOfNotNull(coords.firstOrNull() as? JsonArray)
            "MultiPolygon" -> coords.mapNotNull { (it as? JsonArray)?.firstOrNull() as? JsonArray }
            else -> emptyList()
        }
        val ring = rings.maxByOrNull { it.size } ?: return emptyList()
        val pts = ring.mapNotNull { p ->
            val a = p as? JsonArray ?: return@mapNotNull null
            val lng = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull
            val lat = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            if (lat == null || lng == null) null else doubleArrayOf(lat, lng)
        }.let { if (it.size > 1 && it.first().contentEquals(it.last())) it.dropLast(1) else it }
        return simplify(pts, max)
    }

    /**
     * The district boundaries that contain a point, with their geometry. Search engines often know a
     * district only as a point (Al Olaya in Riyadh is a node), while its boundary is a relation.
     */
    fun containingDistrictQuery(lat: Double, lng: Double): String =
        "[out:json][timeout:25];is_in($lat,$lng)->.a;rel(pivot.a)[boundary=administrative][admin_level~\"^(8|9|10|11)$\"];out geom;"

    /** The smallest (deepest admin level) boundary in a [containingDistrictQuery] answer: its name and outline. */
    fun containingDistrict(body: String, max: Int = 60): Pair<String, List<DoubleArray>>? {
        val root = runCatching { Json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
        val elements = (root["elements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return elements
            .sortedByDescending { (it["tags"] as? JsonObject)?.get("admin_level")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }
            .firstNotNullOfOrNull { rel ->
                val tags = rel["tags"] as? JsonObject
                val name = listOf("name:ar", "name", "name:en").firstNotNullOfOrNull { k ->
                    tags?.get(k)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                } ?: return@firstNotNullOfOrNull null
                val ways = (rel["members"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "way" && it["role"]?.jsonPrimitive?.contentOrNull != "inner" }
                    .map { m ->
                        (m["geometry"] as? JsonArray).orEmpty().mapNotNull { g ->
                            val o = g as? JsonObject ?: return@mapNotNull null
                            val lat = o["lat"]?.jsonPrimitive?.doubleOrNull
                            val lon = o["lon"]?.jsonPrimitive?.doubleOrNull
                            if (lat == null || lon == null) null else doubleArrayOf(lat, lon)
                        }
                    }
                    .filter { it.size >= 2 }
                val ring = assembleRing(ways)
                if (ring.size >= 3) name to simplify(ring, max) else null
            }
    }

    /** A name search near a point (Nominatim, with polygons): the address of the request. */
    fun polygonSearchUrl(name: String, lat: Double, lng: Double): String {
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        return "https://nominatim.openstreetmap.org/search?q=$q&format=json&polygon_geojson=1&polygon_threshold=0.0003&limit=8" +
            "&bounded=1&viewbox=${lng - 0.25},${lat + 0.25},${lng + 0.25},${lat - 0.25}"
    }

    /** The first area in a [polygonSearchUrl] answer whose outline contains the point; empty when none does. */
    fun polygonAround(body: String, lat: Double, lng: Double, max: Int = 60): List<DoubleArray> {
        val results = runCatching { Json.parseToJsonElement(body) as JsonArray }.getOrNull() ?: return emptyList()
        return results.asSequence()
            .mapNotNull { (it as? JsonObject)?.get("geojson") as? JsonObject }
            .map { outline(it, max) }
            .firstOrNull { it.size >= 3 && ZoneCacheWarm.inside(lat, lng, it) }
            .orEmpty()
    }

    /** Joins a relation's outer ways end to end (each in either direction); the largest ring, without the closing duplicate. */
    internal fun assembleRing(ways: List<List<DoubleArray>>): List<DoubleArray> {
        fun same(a: DoubleArray, b: DoubleArray) = kotlin.math.abs(a[0] - b[0]) < 1e-7 && kotlin.math.abs(a[1] - b[1]) < 1e-7
        val left = ways.toMutableList()
        val rings = mutableListOf<List<DoubleArray>>()
        while (left.isNotEmpty()) {
            val ring = left.removeAt(0).toMutableList()
            while (!(ring.size > 2 && same(ring.first(), ring.last()))) {
                val i = left.indexOfFirst { same(it.first(), ring.last()) || same(it.last(), ring.last()) }
                if (i < 0) break
                val next = left.removeAt(i)
                ring += (if (same(next.first(), ring.last())) next else next.reversed()).drop(1)
            }
            rings += ring
        }
        val best = rings.maxByOrNull { it.size } ?: return emptyList()
        return if (best.size > 1 && same(best.first(), best.last())) best.dropLast(1) else best
    }

    /** Keeps the [max] corners that carry the shape (smallest triangle areas dropped first). */
    fun simplify(points: List<DoubleArray>, max: Int): List<DoubleArray> {
        if (points.size <= max || max < 3) return points
        val pts = points.toMutableList()
        while (pts.size > max) {
            var drop = 0
            var least = Double.MAX_VALUE
            for (i in pts.indices) {
                val a = pts[(i - 1 + pts.size) % pts.size]; val b = pts[i]; val c = pts[(i + 1) % pts.size]
                val area = kotlin.math.abs((b[1] - a[1]) * (c[0] - a[0]) - (c[1] - a[1]) * (b[0] - a[0]))
                if (area < least) { least = area; drop = i }
            }
            pts.removeAt(drop)
        }
        return pts
    }

    fun distanceKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val y = (bLat - aLat) * 110.54
        val x = (bLng - aLng) * 111.32 * cos(Math.toRadians(aLat))
        return sqrt(x * x + y * y)
    }

    /** Photon (OpenStreetMap search made for type-ahead) GeoJSON → places. */
    fun parsePhoton(body: String): List<CityPlace> {
        val features = runCatching { json.parseToJsonElement(body).jsonObject["features"]?.jsonArray }.getOrNull() ?: return emptyList()
        return features.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val props = o["properties"] as? JsonObject ?: return@mapNotNull null
            val c = (o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return@mapNotNull null
            val lng = c.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lat = c.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val name = props["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val area = listOf("district", "locality", "city").mapNotNull { props[it]?.jsonPrimitive?.contentOrNull }.firstOrNull { it != name }
            val label = if (area != null) "$name، $area" else name
            val key = props["osm_key"]?.jsonPrimitive?.contentOrNull
            val kind = when (key) { "place", "boundary" -> DISTRICT; "highway" -> ROAD; else -> LANDMARK }
            val osmType = props["osm_type"]?.jsonPrimitive?.contentOrNull
            val osmId = props["osm_id"]?.jsonPrimitive?.longOrNull
            CityPlace(listOf(label, name).distinct(), kind, listOf(doubleArrayOf(lat, lng)),
                if (kind == DISTRICT && osmType != null && osmId != null) osmType + osmId else null)
        }
    }

    // ---- storage -------------------------------------------------------------------------------

    fun encode(city: String, box: GeoBox, savedAt: Long, places: List<CityPlace>): String = buildJsonObject {
        put("city", city); put("savedAt", savedAt)
        put("box", buildJsonArray { add(JsonPrimitive(box.south)); add(JsonPrimitive(box.west)); add(JsonPrimitive(box.north)); add(JsonPrimitive(box.east)) })
        put("places", buildJsonArray {
            places.forEach { p ->
                add(buildJsonObject {
                    put("n", buildJsonArray { p.names.forEach { add(JsonPrimitive(it)) } })
                    put("k", p.kind)
                    put("p", buildJsonArray { p.points.forEach { add(buildJsonArray { add(JsonPrimitive(it[0])); add(JsonPrimitive(it[1])) }) } })
                    p.osm?.let { put("o", it) }
                })
            }
        })
    }.toString()

    data class Saved(val city: String, val box: GeoBox, val savedAt: Long, val places: List<CityPlace>)

    fun decode(text: String): Saved? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val b = o["box"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull!! }
        Saved(o["city"]!!.jsonPrimitive.content, GeoBox(b[0], b[1], b[2], b[3]), o["savedAt"]!!.jsonPrimitive.longOrNull!!,
            o["places"]!!.jsonArray.mapNotNull { e ->
                val p = e as? JsonObject ?: return@mapNotNull null
                val names = (p["n"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val pts = ZoneCacheWarm.corners(p["p"] as? JsonArray ?: JsonArray(emptyList()))
                if (names.isEmpty() || pts.isEmpty()) null
                else CityPlace(names, p["k"]?.jsonPrimitive?.contentOrNull ?: LANDMARK, pts, p["o"]?.jsonPrimitive?.contentOrNull)
            })
    }.getOrNull()

    /** Downloaded again after 30 days, or when the driver is outside the stored city. */
    fun stale(saved: Saved?, lat: Double, lng: Double, now: Long): Boolean =
        saved == null || now - saved.savedAt > MAX_AGE_MS || !saved.box.contains(lat, lng)
}
