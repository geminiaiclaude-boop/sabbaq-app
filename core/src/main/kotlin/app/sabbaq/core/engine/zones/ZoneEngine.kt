package app.sabbaq.core.engine.zones

import app.sabbaq.core.engine.DecisionEngine
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.ParsedRide
import java.util.concurrent.ConcurrentHashMap

/** What the zone stage needs from the device: the driver's position, the place cache, the geocoder queue. */
interface ZoneEnvironment {
    /** Last known driver position (lat, lng), or null. */
    fun driverLocation(): DoubleArray?
    fun poiIndex(): PoiIndex?
    /** Queue an address (or a national short address) for geocoding into the place cache. */
    fun requestGeocode(text: String)
    fun nowMs(): Long
}

/** `aI`: where the destination was placed and why it was excluded. */
data class ZoneEvidence(
    val lat: Double,
    val lng: Double,
    val pointKind: String,
    val precisionM: Double,
    val distanceM: Double,
    val source: String,
)

/** The zone stage's verdict: a rejection reason, or null to let the offer through. */
data class ZoneOutcome(val rejectReason: String?, val diagnostic: String?, val evidence: ZoneEvidence? = null)

/**
 * The reference engine's geographic stage of `C0798b9.process` (smali b9 lines 3340–7000): smart
 * destination ("heading home"), include zones with the on-the-way and map-direction rescues,
 * the driver's exclude zones and the shared exclude zones, each unknown handled by its own
 * reject-on-unknown switch. Exclude verdicts are remembered per destination for 10 minutes.
 */
class ZoneEngine(private val env: ZoneEnvironment) {

    private class Verdict(val atMs: Long, val zonesHash: Int, val excluded: Boolean, val reason: String?, val cleared: Boolean, val evidence: ZoneEvidence?)
    private class Resolved(val index: PoiIndex, val text: String, val latKey: Long, val lngKey: Long, val maxKm: Double, val coords: ResolvedCoords)

    private val verdicts = ConcurrentHashMap<String, Verdict>()
    @Volatile private var lastResolved: Resolved? = null

    /** [accepted] is the price/time/destination result so far; [diagnostic] the decision's diagnostic. */
    fun evaluate(ride: ParsedRide, config: EngineConfig, accepted: Boolean, diagnostic: String?): ZoneOutcome {
        val dest = ride.destination
        val hasDest = !ride.addressAmbiguous && !dest.isNullOrBlank()
        val loc = env.driverLocation()
        val dLat = loc?.getOrNull(0) ?: 0.0
        val dLng = loc?.getOrNull(1) ?: 0.0
        val driverKnown = !(dLat == 0.0 && dLng == 0.0)

        val globalJson = if (config.destExcludeZonesEnabled && !config.globalDestExcludeOptOut && driverKnown) {
            ZoneGeo.nearDriver(config.globalDestExcludeZonesJson, dLat, dLng, config.globalZoneProximityKm)
        } else "[]"
        val userExclude = config.destExcludeZonesEnabled && driverKnown && ZoneGeo.hasZones(config.destExcludeZonesJson)
        val globalOn = ZoneGeo.hasZones(globalJson)
        val includeOn = config.destZonesEnabled && ZoneGeo.hasZones(config.destZonesJson)
        val smartOn = config.smartDestActive && dLat != 0.0 && config.smartDestLat != 0.0
        val needCoords = smartOn || includeOn || userExclude || globalOn

        var coords: DoubleArray? = null
        var matchType: String? = null
        var precision = 500.0
        if (hasDest && needCoords) {
            env.poiIndex()?.let { index ->
                val r = resolve(index, dest!!, dLat, dLng, config.destExcludeProximityMaxKm)
                coords = r.point; matchType = r.matchType; precision = r.precisionM
            }
        }
        var pointKind = "text"
        var queued = false
        fun queueOnce(text: String) { if (!queued) { env.requestGeocode(text); queued = true } }
        if (hasDest && needCoords) {
            val plus = PlusCodes.resolve(dLat, dLng, dest)
            if (plus != null) {
                coords = doubleArrayOf(plus.lat, plus.lng); pointKind = "plus_code"; precision = plus.precisionM
            } else {
                val code = PlusCodes.nationalCode(dest)
                if (code != null) {
                    val hit = env.poiIndex()?.nationalCode(code, dLat, dLng)
                    if (hit != null) {
                        coords = hit; pointKind = "natl_code"; precision = 40.0
                    } else {
                        env.requestGeocode(code.uppercase())
                        if (matchType != "exact") { pointKind = "natl_code_pending"; coords = null }
                        queued = true
                    }
                }
            }
        }
        if (hasDest && coords == null && needCoords) queueOnce(dest!!)

        if (smartOn && hasDest) {
            val c = coords
            if (c != null) {
                if (!ZoneGeo.sameDirection(dLat, dLng, c[0], c[1], config.smartDestLat, config.smartDestLng, config.smartDestAngle)) {
                    return ZoneOutcome("wrong_direction", diagnostic)
                }
            } else queueOnce(dest!!)
        }

        var diag = diagnostic
        if (!hasDest) {
            if (includeOn && config.destZonesRejectOnUnknown) return ZoneOutcome("dest_zone_unknown_reject", diagnostic)
            if ((userExclude || globalOn) && config.destExcludeZonesRejectOnUnknown && excludeZoneNearby(config, globalJson, dLat, dLng)) {
                return ZoneOutcome("dest_exclude_zone_unknown_reject", "exclude_zone_unknown")
            }
            if (userExclude || globalOn) diag = "exclude_zone_unknown_fail_open"
            return ZoneOutcome(null, diag)
        }

        if (includeOn) {
            val c = coords
            if (c != null) {
                val inside = ZoneGeo.insideAny(c[0], c[1], config.destZonesJson)
                var onTheWay = false
                if (!inside && config.destZonesOnTheWayEnabled && dLat != 0.0) {
                    val route: List<DoubleArray>? = if (config.destZonesRouteApiBudgetMs > 0) null else listOf(doubleArrayOf(dLat, dLng), c)
                    val crosses = route != null && ZoneGeo.routeCrossesZone(config.destZonesJson, route)
                    val near = ZoneGeo.nearestPoint(c[0], c[1], config.destZonesJson)
                    val nearOk = near != null && !near.inside && near.km <= config.travelOvershootKm &&
                        ZoneGeo.sameDirection(dLat, dLng, c[0], c[1], near.lat, near.lng, config.travelAngleTolDeg)
                    val corridor = ZoneGeo.centroidInCorridor(dLat, dLng, c[0], c[1], config.destZonesJson,
                        config.corridorHalfWidthKm, config.travelOvershootKm)
                    onTheWay = crosses || nearOk || corridor
                }
                if (!inside && !onTheWay && direction(config, dLat, dLng, ride) != DirectionVerdict.INSIDE_DIRECTION) {
                    return ZoneOutcome("outside_dest_zone", diag)
                }
            } else {
                queueOnce(dest!!)
                when (direction(config, dLat, dLng, ride)) {
                    DirectionVerdict.INSIDE_DIRECTION -> Unit
                    DirectionVerdict.OUTSIDE_DIRECTION -> return ZoneOutcome("outside_dest_zone_bearing", diag)
                    DirectionVerdict.INCONCLUSIVE -> if (config.destZonesRejectOnUnknown) return ZoneOutcome("dest_zone_unknown_reject", diag)
                }
            }
        }

        if (!userExclude && !globalOn) return ZoneOutcome(null, diag)
        val key = DecisionEngine.fold(dest)
        val zonesHash = config.destExcludeZonesJson.hashCode() * 31 + globalJson.hashCode()
        val now = env.nowMs()
        val cached = verdicts[key]?.takeIf { it.zonesHash == zonesHash && now - it.atMs < VERDICT_TTL_MS }
        if (cached != null && cached.excluded) return ZoneOutcome(cached.reason, diag, cached.evidence)
        if (cached != null && cached.cleared) return ZoneOutcome(null, diag)

        var reason: String? = null
        var source: String? = null
        var distanceM: Double? = null
        var conclusive = true
        if (userExclude) {
            val r = classify(coords, pointKind, matchType, precision, config.destExcludeZonesJson)
            when (r.first) {
                -1 -> conclusive = false
                1 -> { reason = "dest_excluded_zone"; source = "user"; distanceM = r.second }
            }
            if (r.first == -1) queueOnce(dest!!)
        }
        if (reason == null && globalOn) {
            val r = classify(coords, pointKind, matchType, precision, globalJson)
            when (r.first) {
                -1 -> { conclusive = false; queueOnce(dest!!) }
                1 -> { reason = "dest_global_excluded_zone"; source = "global"; distanceM = r.second }
            }
        }
        if (reason != null) {
            val c = coords
            val evidence = c?.let {
                ZoneEvidence(it[0], it[1], if (pointKind == "text") (matchType ?: "unresolved") else pointKind,
                    precision, distanceM ?: 0.0, source ?: "unknown")
            }
            if (accepted) remember(key, Verdict(now, zonesHash, true, reason, false, evidence))
            return ZoneOutcome(reason, diag, evidence)
        }
        if (conclusive && coords != null) {
            remember(key, Verdict(now, zonesHash, false, null, true, null))
            return ZoneOutcome(null, diag)
        }
        if (coords == null) queueOnce(dest!!)
        if (config.destExcludeZonesRejectOnUnknown && excludeZoneNearby(config, globalJson, dLat, dLng)) {
            return ZoneOutcome("dest_exclude_zone_unknown_reject", "exclude_zone_unknown")
        }
        return ZoneOutcome(null, "exclude_zone_unknown_fail_open")
    }

    /** Forget remembered exclude verdicts (after the driver edits a zone list). */
    fun clear() {
        verdicts.clear()
        lastResolved = null
    }

    private fun direction(config: EngineConfig, dLat: Double, dLng: Double, ride: ParsedRide): DirectionVerdict =
        if (config.destZonesBearingEnabled) {
            ZoneGeo.directionFromMarkers(dLat, dLng, ride.markers, config.destZonesJson, config.destZonesBearingMarginDeg)
        } else DirectionVerdict.INCONCLUSIVE

    /** An exclude zone (own or shared) lies within 15 km of the driver. */
    private fun excludeZoneNearby(config: EngineConfig, globalJson: String, dLat: Double, dLng: Double): Boolean =
        ZoneGeo.hasZones(ZoneGeo.nearDriver(config.destExcludeZonesJson, dLat, dLng, UNKNOWN_EXCLUDE_RADIUS_KM)) ||
            ZoneGeo.hasZones(ZoneGeo.nearDriver(globalJson, dLat, dLng, UNKNOWN_EXCLUDE_RADIUS_KM))

    /**
     * `b9.execute`: 1 inside an exclude zone, 0 clearly outside (or no zones), -1 undecided — no
     * point, a fuzzy text match, a point closer to the edge than its precision, or a broad point inside.
     */
    private fun classify(coords: DoubleArray?, pointKind: String, matchType: String?, precisionM: Double, zonesJson: String): Pair<Int, Double?> {
        coords ?: return -1 to null
        val edge = ZoneGeo.edgeDistance(coords[0], coords[1], zonesJson) ?: return 0 to null
        val edgeM = edge.km * 1000.0
        val undecided = (pointKind == "text" && matchType != "exact") || edgeM < precisionM || (edge.inside && precisionM > 200.0)
        return if (undecided) -1 to edgeM else (if (edge.inside) 1 else 0) to edgeM
    }

    /** The last address lookup is reused while the cache, text, driver (0.01°) and radius are unchanged (`Y8`). */
    private fun resolve(index: PoiIndex, text: String, dLat: Double, dLng: Double, maxKm: Double): ResolvedCoords {
        val latKey = (dLat * 100).toLong()
        val lngKey = (dLng * 100).toLong()
        lastResolved?.let { r ->
            if (r.index === index && r.text == text && r.latKey == latKey && r.lngKey == lngKey && r.maxKm == maxKm) return r.coords
        }
        val coords = runCatching { index.resolve(text, dLat, dLng, maxKm) }.getOrElse { ResolvedCoords(null, emptyList(), null, 500.0) }
        lastResolved = Resolved(index, text, latKey, lngKey, maxKm, coords)
        return coords
    }

    private fun remember(key: String, verdict: Verdict) {
        if (verdicts.size >= MAX_VERDICTS) verdicts.clear()
        verdicts[key] = verdict
    }

    companion object {
        const val VERDICT_TTL_MS = 600_000L
        const val UNKNOWN_EXCLUDE_RADIUS_KM = 15.0
        private const val MAX_VERDICTS = 500
    }
}
