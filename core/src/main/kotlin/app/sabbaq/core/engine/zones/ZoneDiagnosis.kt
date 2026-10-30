package app.sabbaq.core.engine.zones

import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.ParsedRide

/**
 * «تشخيص المناطق الجغرافية»: for a destination the driver types, where it lies on the map and what
 * the zone stage actually does with it right now. The engine verdict runs the real [ZoneEngine]
 * on the place cache only (what an offer sees the moment it appears); the map verdict uses the
 * cached point, else the live lookup's point.
 */
object ZoneDiagnosis {
    enum class Where {
        /** No destination zone or exclude zone is on. */
        NO_ZONES,
        /** The destination could not be placed, neither from the cache nor live. */
        UNPLACED,
        INSIDE_EXCLUDE,
        INSIDE_INCLUDE,
        OUTSIDE_INCLUDE,
        /** Placed, outside every exclude zone, and no destination zone is on. */
        CLEAR,
    }

    data class Report(
        val where: Where,
        /** The zone containing the point (exclude first), when there is one. */
        val zoneName: String?,
        /** The point was in the cache already (else it came from the live lookup). */
        val fromCache: Boolean,
        val point: DoubleArray?,
        /** The zone stage's own rejection reason for this destination now, or null when it lets it through. */
        val engineReject: String?,
        /** The zone stage let it through without placing it ("…_fail_open" or the include default). */
        val engineUnplaced: Boolean,
    ) {
        /** It should pass, yet the engine rejects it now: its address is not in the cache when the offer appears. */
        val rejectedForNoPoint: Boolean get() = engineReject != null && !fromCache && (where == Where.INSIDE_INCLUDE || where == Where.CLEAR)
    }

    /**
     * [cache] is the place cache, [livePoint] the live lookup's answer (null when none or not
     * asked), [driver] the driver's last position.
     */
    fun diagnose(config: EngineConfig, destination: String, cache: PoiIndex?, livePoint: DoubleArray?, driver: DoubleArray?): Report {
        val includeOn = config.destZonesEnabled && ZoneGeo.hasZones(config.destZonesJson)
        val globalJson = if (config.destExcludeZonesEnabled && !config.globalDestExcludeOptOut) config.globalDestExcludeZonesJson else "[]"
        val excludeOn = config.destExcludeZonesEnabled && (ZoneGeo.hasZones(config.destExcludeZonesJson) || ZoneGeo.hasZones(globalJson))
        val dLat = driver?.getOrNull(0) ?: 0.0
        val dLng = driver?.getOrNull(1) ?: 0.0
        val cached = cache?.resolve(destination, dLat, dLng, config.destExcludeProximityMaxKm)?.point
        val point = cached ?: livePoint

        val env = object : ZoneEnvironment {
            override fun driverLocation(): DoubleArray? = driver
            override fun poiIndex(): PoiIndex? = cache
            override fun requestGeocode(text: String) = Unit
            override fun nowMs(): Long = 0L
        }
        val outcome = ZoneEngine(env).evaluate(ParsedRide(destination = destination), config, accepted = false, diagnostic = null)
        val unplaced = outcome.rejectReason == null && (outcome.diagnostic == "exclude_zone_unknown_fail_open" || (includeOn && cached == null))

        if (!includeOn && !excludeOn) return Report(Where.NO_ZONES, null, cached != null, point, outcome.rejectReason, false)
        if (point == null) return Report(Where.UNPLACED, null, false, null, outcome.rejectReason, unplaced)
        val excludeZone = (ZoneGeo.polygons(config.destExcludeZonesJson).takeIf { config.destExcludeZonesEnabled }.orEmpty() + ZoneGeo.polygons(globalJson))
            .firstOrNull { ZoneGeo.contains(point[0], point[1], it.lats, it.lngs) }
        val includeZone = if (includeOn) ZoneGeo.polygons(config.destZonesJson).firstOrNull { ZoneGeo.contains(point[0], point[1], it.lats, it.lngs) } else null
        val where = when {
            excludeZone != null -> Where.INSIDE_EXCLUDE
            includeZone != null -> Where.INSIDE_INCLUDE
            includeOn -> Where.OUTSIDE_INCLUDE
            else -> Where.CLEAR
        }
        return Report(where, (excludeZone ?: includeZone)?.name, cached != null, point, outcome.rejectReason, unplaced)
    }
}
