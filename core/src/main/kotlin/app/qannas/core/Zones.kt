package app.qannas.core

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A circular area the driver does not want to serve. Geometry is not money,
 * so plain doubles are used for coordinates and metres.
 */
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

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun firstHit(zones: List<ExcludeZone>, lat: Double, lon: Double): ExcludeZone? =
        zones.firstOrNull { it.enabled && distanceMeters(it.lat, it.lon, lat, lon) <= it.radiusMeters }
}
