package app.sabbaq.core.engine.zones

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One polygon of a zones JSON: `[{"name","active","points":[[lat,lng],…]}]` (the reference engine format). */
class ZonePolygon(val name: String, val lats: DoubleArray, val lngs: DoubleArray)

/** Distance from a point to the nearest zone edge, and whether the point lies inside (`Zh.encoder`). */
data class EdgeDistance(val km: Double, val inside: Boolean)

/** Nearest point on any zone (`Zh.provider`). */
data class NearestZonePoint(val km: Double, val lat: Double, val lng: Double, val inside: Boolean)

/** The reference engine's travel direction relative to the zones (`To`). */
enum class DirectionVerdict { INSIDE_DIRECTION, OUTSIDE_DIRECTION, INCONCLUSIVE }

/**
 * The reference engine's zone geometry (`Zh`, `Xr0`, `Zi.connect`, `item.handler`), ported from smali.
 * Distances use the same flat projection: 110 540 m per degree of latitude and
 * 111 320 × cos(lat) m per degree of longitude.
 */
object ZoneGeo {
    private const val M_PER_DEG_LAT = 110540.0
    private const val M_PER_DEG_LNG = 111320.0
    private val json = Json { ignoreUnknownKeys = true }
    private val parsed = ConcurrentHashMap<String, List<ZonePolygon>>()
    private val nearby = ConcurrentHashMap<String, String>()

    /** Active polygons with at least 3 valid points (`Zh.controller`, cached by JSON text, 8 entries). */
    fun polygons(zonesJson: String): List<ZonePolygon> {
        parsed[zonesJson]?.let { return it }
        val out = ArrayList<ZonePolygon>()
        runCatching {
            for (item in json.parseToJsonElement(zonesJson).jsonArray) {
                val obj = item as? JsonObject ?: continue
                if (obj.bool("active") == false) continue
                val points = obj["points"] as? JsonArray ?: continue
                if (points.size < 3) continue
                val lats = DoubleArray(points.size)
                val lngs = DoubleArray(points.size)
                var ok = true
                for (i in points.indices) {
                    val p = points[i] as? JsonArray
                    val lat = p?.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                    val lng = p?.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                    if (p == null || p.size < 2 || lat == null || lng == null) { ok = false; break }
                    lats[i] = lat; lngs[i] = lng
                }
                if (ok) out += ZonePolygon(obj.str("name").orEmpty(), lats, lngs)
            }
        }
        if (parsed.size >= 8) parsed.clear()
        parsed[zonesJson] = out
        return out
    }

    /** `Zh.callback`: the JSON holds at least one usable zone. */
    fun hasZones(zonesJson: String): Boolean = polygons(zonesJson).isNotEmpty()

    /** At least one zone is drawn (3+ points), switched on or not: an on list may wait for its zones' warming. */
    fun hasDrawn(zonesJson: String): Boolean = runCatching {
        json.parseToJsonElement(zonesJson).jsonArray.any { ((it as? JsonObject)?.get("points") as? JsonArray)?.size?.let { n -> n >= 3 } == true }
    }.getOrDefault(false)

    /** Ray-casting point-in-polygon (`Zh.factory`). */
    fun contains(lat: Double, lng: Double, lats: DoubleArray, lngs: DoubleArray): Boolean {
        var inside = false
        var j = lats.size - 1
        for (i in lats.indices) {
            val yi = lats[i]; val xi = lngs[i]; val yj = lats[j]; val xj = lngs[j]
            if ((yi > lat) != (yj > lat) && lng < (lat - yi) * (xj - xi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** `Zh.listener`: the point is inside any zone. */
    fun insideAny(lat: Double, lng: Double, zonesJson: String): Boolean =
        polygons(zonesJson).any { contains(lat, lng, it.lats, it.lngs) }

    /** `Zh.encoder`: distance to the nearest edge of any zone, null when there are no zones. */
    fun edgeDistance(lat: Double, lng: Double, zonesJson: String): EdgeDistance? {
        val zones = polygons(zonesJson)
        if (zones.isEmpty()) return null
        val inside = zones.any { contains(lat, lng, it.lats, it.lngs) }
        val kx = cos(Math.toRadians(lat)) * M_PER_DEG_LNG
        var best = Double.MAX_VALUE
        for (z in zones) best = min(best, nearestOnPolygon(lat, lng, z, kx).first)
        return EdgeDistance(best / 1000.0, inside)
    }

    /** `Zh.provider`: the nearest point on any zone (the point itself when inside one). */
    fun nearestPoint(lat: Double, lng: Double, zonesJson: String): NearestZonePoint? {
        val zones = polygons(zonesJson)
        if (zones.isEmpty()) return null
        if (zones.any { contains(lat, lng, it.lats, it.lngs) }) return NearestZonePoint(0.0, lat, lng, true)
        val kx = cos(Math.toRadians(lat)) * M_PER_DEG_LNG
        var best = Double.MAX_VALUE
        var bestLat = lat
        var bestLng = lng
        for (z in zones) {
            val (d, dx, dy) = nearestOnPolygon(lat, lng, z, kx)
            if (d < best) { best = d; bestLng = dx / kx + lng; bestLat = dy / M_PER_DEG_LAT + lat }
        }
        return NearestZonePoint(best / 1000.0, bestLat, bestLng, false)
    }

    /** Smallest distance (m) from (lat,lng) to the polygon outline, with the offset (x,y m) of that point. */
    private fun nearestOnPolygon(lat: Double, lng: Double, z: ZonePolygon, kx: Double): Triple<Double, Double, Double> {
        var best = Double.MAX_VALUE
        var bx = 0.0
        var by = 0.0
        var j = z.lats.size - 1
        for (i in z.lats.indices) {
            val ax = (z.lngs[j] - lng) * kx
            val ay = (z.lats[j] - lat) * M_PER_DEG_LAT
            val ex = (z.lngs[i] - lng) * kx - ax
            val ey = (z.lats[i] - lat) * M_PER_DEG_LAT - ay
            val len2 = ex * ex + ey * ey
            val t = if (len2 <= 0.0) 0.0 else ((-ax * ex - ay * ey) / len2).coerceIn(0.0, 1.0)
            val px = ax + ex * t
            val py = ay + ey * t
            val d = sqrt(px * px + py * py)
            if (d < best) { best = d; bx = px; by = py }
            j = i
        }
        return Triple(best, bx, by)
    }

    /**
     * `Zh.dispatch`: keep inactive or malformed entries and the active zones within [maxKm] of the
     * driver; [maxKm] ≤ 0 keeps everything. Cached by JSON hash and the driver rounded to 0.02°.
     */
    fun nearDriver(zonesJson: String, lat: Double, lng: Double, maxKm: Double): String {
        if (zonesJson.isBlank() || zonesJson == "[]" || maxKm <= 0.0) return zonesJson
        val key = "${zonesJson.hashCode()}|${Math.round(lat * 50.0) / 50.0}|${Math.round(lng * 50.0) / 50.0}|$maxKm"
        nearby[key]?.let { return it }
        val kept = runCatching {
            val all = json.parseToJsonElement(zonesJson).jsonArray
            JsonArray(all.filter { item ->
                val obj = item as? JsonObject ?: return@filter false
                if (obj.bool("active") == false) return@filter true
                val points = obj["points"] as? JsonArray
                if (points == null || points.size < 3) return@filter true
                distanceToOutlineKm(points, lat, lng) <= maxKm
            }).toString()
        }.getOrElse { return zonesJson }
        if (nearby.size >= 32) nearby.clear()
        nearby[key] = kept
        return kept
    }

    /** `Zh.adapter`: 0 inside, else the outline distance in km; malformed points count as infinitely far. */
    private fun distanceToOutlineKm(points: JsonArray, lat: Double, lng: Double): Double {
        val lats = DoubleArray(points.size)
        val lngs = DoubleArray(points.size)
        for (i in points.indices) {
            val p = points[i] as? JsonArray ?: return Double.MAX_VALUE
            lats[i] = p.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return Double.MAX_VALUE
            lngs[i] = p.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return Double.MAX_VALUE
        }
        if (contains(lat, lng, lats, lngs)) return 0.0
        val kx = cos(Math.toRadians(lat)) * M_PER_DEG_LNG
        return nearestOnPolygon(lat, lng, ZonePolygon("", lats, lngs), kx).first / 1000.0
    }

    /**
     * `Zh.builder`: the route (lat,lng points) enters a zone — a single point inside one, or a
     * segment that crosses or touches a zone edge.
     */
    fun routeCrossesZone(zonesJson: String, route: List<DoubleArray>): Boolean {
        if (route.isEmpty()) return false
        if (route.size == 1) return insideAny(route[0][0], route[0][1], zonesJson)
        val zones = polygons(zonesJson)
        for (s in 0 until route.size - 1) {
            val aLat = route[s][0]; val aLng = route[s][1]
            val bLat = route[s + 1][0]; val bLng = route[s + 1][1]
            val kx = M_PER_DEG_LNG * cos(Math.toRadians(aLat))
            val bx = (bLng - aLng) * kx
            val by = (bLat - aLat) * M_PER_DEG_LAT
            for (z in zones) {
                if (contains(aLat, aLng, z.lats, z.lngs) || contains(bLat, bLng, z.lats, z.lngs)) return true
                var j = z.lats.size - 1
                for (i in z.lats.indices) {
                    val px = (z.lngs[j] - aLng) * kx; val py = (z.lats[j] - aLat) * M_PER_DEG_LAT
                    val qx = (z.lngs[i] - aLng) * kx; val qy = (z.lats[i] - aLat) * M_PER_DEG_LAT
                    val d1 = cross(px, py, qx, qy, 0.0, 0.0)
                    val d2 = cross(px, py, qx, qy, bx, by)
                    val d3 = cross(0.0, 0.0, bx, by, px, py)
                    val d4 = cross(0.0, 0.0, bx, by, qx, qy)
                    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
                    if (d1 == 0.0 && onSegment(px, py, qx, qy, 0.0, 0.0)) return true
                    if (d2 == 0.0 && onSegment(px, py, qx, qy, bx, by)) return true
                    if (d3 == 0.0 && onSegment(0.0, 0.0, bx, by, px, py)) return true
                    if (d4 == 0.0 && onSegment(0.0, 0.0, bx, by, qx, qy)) return true
                    j = i
                }
            }
        }
        return false
    }

    private fun cross(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
        (cy - ay) * (bx - ax) - (cx - ax) * (by - ay)

    private fun onSegment(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Boolean =
        cx <= maxOf(ax, bx) && minOf(ax, bx) <= cx && cy <= maxOf(ay, by) && minOf(ay, by) <= cy

    /**
     * `Zh.handler`: some zone centroid lies within [corridorKm] of the driver→destination line,
     * no further than [overshootKm] past the destination.
     */
    fun centroidInCorridor(
        dLat: Double, dLng: Double, tLat: Double, tLng: Double, zonesJson: String, corridorKm: Double, overshootKm: Double,
    ): Boolean {
        val zones = polygons(zonesJson)
        if (zones.isEmpty()) return false
        val kx = cos(Math.toRadians(dLat)) * M_PER_DEG_LNG
        val tx = (tLng - dLng) * kx
        val ty = (tLat - dLat) * M_PER_DEG_LAT
        val corridorM = corridorKm * 1000.0
        val overshootM = overshootKm * 1000.0
        for (z in zones) {
            val cx = (z.lngs.average() - dLng) * kx
            val cy = (z.lats.average() - dLat) * M_PER_DEG_LAT
            val len2 = cy * cy + cx * cx
            if (len2 <= 1.0) continue
            val t = (ty * cy + tx * cx) / len2
            val tMax = overshootM / sqrt(len2) + 1.0
            if (t < 0.0 || t > tMax) continue
            val ox = tx - cx * t
            val oy = ty - cy * t
            if (sqrt(oy * oy + ox * ox) <= corridorM) return true
        }
        return false
    }

    /** Initial great-circle bearing in degrees 0–360 (`Xr0.connect`). */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val deg = Math.toDegrees(atan2(cos(p2) * sin(dl), sin(p2) * cos(p1) - cos(dl) * (cos(p2) * sin(p1))))
        return (deg + 360.0) % 360.0
    }

    /** `Xr0.resolver`: from (lat,lng), points A and B lie within [toleranceDeg] of the same bearing. */
    fun sameDirection(lat: Double, lng: Double, aLat: Double, aLng: Double, bLat: Double, bLng: Double, toleranceDeg: Double): Boolean {
        var diff = abs(bearing(lat, lng, aLat, aLng) - bearing(lat, lng, bLat, bLng))
        if (diff > 180.0) diff = 360.0 - diff
        return diff <= toleranceDeg
    }

    /** Great-circle distance in km (`item.handler`). */
    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1) / 2.0
        val dLng = Math.toRadians(lng2 - lng1) / 2.0
        val a = sin(dLng) * sin(dLng) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(lat1)) + sin(dLat) * sin(dLat)
        return atan2(sqrt(a), sqrt(1.0 - a)) * 12742.0
    }

    /**
     * `Zi.connect`: the travel direction read from the offer map's markers. Markers within 15 % of
     * the widest spread are grouped; the biggest group (≥ 2) is the route start, the marker
     * farthest from it (≥ half the spread) the end, and the screen bearing start→end (north up)
     * is compared with the arc each zone covers as seen from the driver.
     */
    fun directionFromMarkers(driverLat: Double, driverLng: Double, markers: List<IntArray>, zonesJson: String, marginDeg: Double): DirectionVerdict {
        if (driverLat == 0.0 && driverLng == 0.0) return DirectionVerdict.INCONCLUSIVE
        val n = markers.size
        if (n < 3) return DirectionVerdict.INCONCLUSIVE
        var spread = 0.0
        for (i in 0 until n) for (j in i + 1 until n) spread = maxOf(spread, dist(markers[i], markers[j]))
        if (spread < 1.0) return DirectionVerdict.INCONCLUSIVE
        val parent = IntArray(n) { it }
        fun root(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (parent[c] != c) { val nx = parent[c]; parent[c] = r; c = nx }; return r }
        for (i in 0 until n) for (j in i + 1 until n) if (dist(markers[i], markers[j]) <= 0.15 * spread) parent[root(i)] = root(j)
        val groups = (0 until n).groupBy { root(it) }.values
        val group = groups.maxByOrNull { it.size } ?: return DirectionVerdict.INCONCLUSIVE
        if (group.size < 2) return DirectionVerdict.INCONCLUSIVE
        val sx = group.sumOf { markers[it][0].toDouble() } / group.size
        val sy = group.sumOf { markers[it][1].toDouble() } / group.size
        var far = -1.0
        var end: DoubleArray? = null
        for (i in 0 until n) {
            if (i in group) continue
            val dx = markers[i][0] - sx
            val dy = markers[i][1] - sy
            val d2 = dx * dx + dy * dy
            if (d2 > far) { far = d2; end = doubleArrayOf(markers[i][0].toDouble(), markers[i][1].toDouble()) }
        }
        if (end == null || sqrt(far) < spread * 0.5) return DirectionVerdict.INCONCLUSIVE
        val travel = (Math.toDegrees(atan2(end[0] - sx, -(end[1] - sy))) + 360.0) % 360.0
        val zones = polygons(zonesJson)
        if (zones.isEmpty()) return DirectionVerdict.INCONCLUSIVE
        var anyOutside = false
        for (z in zones) {
            val b = DoubleArray(z.lats.size) { bearing(driverLat, driverLng, z.lats[it], z.lngs[it]) }.sorted()
            var gapStart = b.last()
            var gap = ((b.first() - gapStart) + 360.0) % 360.0
            for (i in 0 until b.size - 1) {
                val g = b[i + 1] - b[i]
                if (g > gap) { gap = g; gapStart = b[i] }
            }
            if (360.0 - gap > 170.0) continue
            val offset = (((travel - gapStart) % 360.0) + 360.0) % 360.0
            val inside = offset >= gap || min(offset, gap - offset) <= marginDeg
            if (inside) return DirectionVerdict.INSIDE_DIRECTION
            anyOutside = true
        }
        return if (anyOutside) DirectionVerdict.OUTSIDE_DIRECTION else DirectionVerdict.INCONCLUSIVE
    }

    private fun dist(a: IntArray, b: IntArray): Double {
        val dx = (a[0] - b[0]).toDouble()
        val dy = (a[1] - b[1]).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
