package app.sabbaq.core

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 point. Coordinates are supplied by an adapter or explicit local user mapping. */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double) {
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

@Serializable
enum class ZoneMode { INCLUDE, EXCLUDE }

@Serializable
data class DestinationZone(
    val id: String,
    val name: String,
    val center: GeoPoint,
    val radiusMeters: Int,
    val mode: ZoneMode = ZoneMode.EXCLUDE,
    val enabled: Boolean = true,
)

@Serializable
data class DestinationTarget(val center: GeoPoint, val radiusMeters: Int)

@Serializable
data class DestinationCorridor(val points: List<GeoPoint>, val halfWidthMeters: Int)

@Serializable
data class DestinationAlias(val phrase: String, val point: GeoPoint)

/** Backward-compatible legacy circle model. */
@Serializable
data class ExcludeZone(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Int,
    val enabled: Boolean = true,
)

object Zones {
    private const val EARTH_RADIUS_M = 6_371_000.0
    const val MAX_ZONES = 32
    const val MAX_CORRIDOR_POINTS = 32
    const val MAX_ZONE_RADIUS_METERS = 100_000
    const val MAX_CORRIDOR_WIDTH_METERS = 100_000

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!isValidCoordinate(lat1, lon1) || !isValidCoordinate(lat2, lon2)) return Double.POSITIVE_INFINITY
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun firstHit(zones: List<ExcludeZone>, lat: Double, lon: Double): ExcludeZone? = zones.firstOrNull {
        it.enabled && it.radiusMeters >= 0 && isValidCoordinate(it.lat, it.lon) && distanceMeters(it.lat, it.lon, lat, lon) <= it.radiusMeters
    }

    fun firstHit(zones: List<DestinationZone>, point: GeoPoint): DestinationZone? {
        if (!point.isValid) return null
        return zones.firstOrNull { zone ->
            zone.enabled && zone.mode == ZoneMode.EXCLUDE && isValidZone(zone) &&
                distanceMeters(zone.center.latitude, zone.center.longitude, point.latitude, point.longitude) <= zone.radiusMeters
        }
    }

    fun matchesIncludeZones(zones: List<DestinationZone>, point: GeoPoint): Boolean {
        if (!point.isValid) return false
        return zones.asSequence().filter { it.enabled && it.mode == ZoneMode.INCLUDE }
            .any { zone -> isValidZone(zone) && distanceMeters(zone.center.latitude, zone.center.longitude, point.latitude, point.longitude) <= zone.radiusMeters }
    }

    fun isValidZone(zone: DestinationZone): Boolean = zone.id.isNotBlank() && zone.id.length <= 80 &&
        zone.name.isNotBlank() && zone.name.length <= 100 && zone.center.isValid && zone.radiusMeters in 1..MAX_ZONE_RADIUS_METERS

    fun isValidTarget(target: DestinationTarget): Boolean = target.center.isValid && target.radiusMeters in 1..MAX_ZONE_RADIUS_METERS

    fun isValidCorridor(corridor: DestinationCorridor): Boolean = corridor.points.size in 2..MAX_CORRIDOR_POINTS &&
        corridor.points.all(GeoPoint::isValid) && corridor.halfWidthMeters in 1..MAX_CORRIDOR_WIDTH_METERS &&
        corridor.points.zipWithNext().all { (a, b) ->
            val length = distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            length.isFinite() && length in 1.0..(MAX_ZONE_RADIUS_METERS * 2.0)
        }

    fun isNearTarget(point: GeoPoint, target: DestinationTarget): Boolean = point.isValid && isValidTarget(target) &&
        distanceMeters(point.latitude, point.longitude, target.center.latitude, target.center.longitude) <= target.radiusMeters

    /** Point-to-segment distance on a local tangent plane, with maximum segment length bounded above. */
    fun isInsideCorridor(point: GeoPoint, corridor: DestinationCorridor): Boolean {
        if (!point.isValid || !isValidCorridor(corridor)) return false
        return corridor.points.zipWithNext().any { (a, b) -> pointToSegmentMeters(point, a, b) <= corridor.halfWidthMeters }
    }

    private fun pointToSegmentMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val referenceLat = Math.toRadians((point.latitude + start.latitude + end.latitude) / 3.0)
        fun x(p: GeoPoint): Double {
            val raw = Math.toRadians(p.longitude - start.longitude)
            val wrapped = ((raw + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI
            return wrapped * EARTH_RADIUS_M * cos(referenceLat)
        }
        fun y(p: GeoPoint) = Math.toRadians(p.latitude) * EARTH_RADIUS_M
        val px = x(point); val py = y(point)
        val ax = x(start); val ay = y(start)
        val bx = x(end); val by = y(end)
        val dx = bx - ax; val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
}
