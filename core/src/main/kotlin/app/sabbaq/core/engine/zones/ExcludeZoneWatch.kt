package app.sabbaq.core.engine.zones

import app.sabbaq.core.engine.DecisionEngine
import app.sabbaq.core.engine.EngineConfig

/**
 * The reference engine's post-accept exclude check (`Mb`): an accepted ride whose destination
 * could not be placed is accepted at once ("exclude_zone_unknown_fail_open"), and the destination
 * keeps being located afterwards. When it turns out to lie inside an active exclude zone the driver
 * is warned, so they can cancel the ride in the ride app.
 *
 * Two paths reach the warning, both here:
 * - right after the accept, attempts at 0 s, then 20 s, 40 s, 120 s and 120 s later
 *   ([RETRY_DELAYS_MS]); the place cache is read on every attempt, the network only on the first two;
 * - whenever the background geocoder places an address that was accepted in the last two hours.
 * Each address warns at most once per 30 minutes. Pure state; the app supplies time and lookups.
 */
class ExcludeZoneWatch(private val nowMs: () -> Long) {
    private val accepted = LinkedHashMap<String, Long>()
    private val warned = LinkedHashMap<String, Long>()

    /** `Mb.connect`: exclude zones are on and at least one usable zone applies (own, or the global list unless opted out). */
    fun applies(config: EngineConfig): Boolean =
        config.destExcludeZonesEnabled &&
            (ZoneGeo.hasZones(config.destExcludeZonesJson) || (!config.globalDestExcludeOptOut && ZoneGeo.hasZones(config.globalDestExcludeZonesJson)))

    /** `Mb.handler`: the point is inside one of those zones. */
    fun inside(config: EngineConfig, lat: Double, lng: Double): Boolean {
        if (!config.destExcludeZonesEnabled) return false
        if (ZoneGeo.insideAny(lat, lng, config.destExcludeZonesJson)) return true
        return !config.globalDestExcludeOptOut && ZoneGeo.insideAny(lat, lng, config.globalDestExcludeZonesJson)
    }

    /** Remembers an accepted destination for the geocoder path (two hours, 64 entries). */
    @Synchronized
    fun recordAccepted(destination: String) {
        val key = DecisionEngine.fold(destination)
        if (key.isEmpty()) return
        accepted.remove(key)
        accepted[key] = nowMs()
        while (accepted.size > MAX_ENTRIES) accepted.remove(accepted.keys.first())
    }

    /** `Mb.execute`: [destination] was accepted within the last two hours. */
    @Synchronized
    fun acceptedRecently(destination: String): Boolean {
        val at = accepted[DecisionEngine.fold(destination)] ?: return false
        return nowMs() - at <= ACCEPT_WINDOW_MS
    }

    /** `Mb.observer`: true the first time an address warns in 30 minutes, and records it. */
    @Synchronized
    fun claimWarning(destination: String): Boolean {
        val key = DecisionEngine.fold(destination)
        if (key.isEmpty()) return false
        val now = nowMs()
        warned[key]?.let { if (now - it < WARN_DEDUPE_MS) return false }
        if (warned.size >= MAX_ENTRIES) warned.minByOrNull { it.value }?.let { warned.remove(it.key) }
        warned[key] = now
        return true
    }

    /** What the attempt after an accept should do next. */
    sealed class Step {
        /** Warn the driver now. */
        object Warn : Step()
        /** Placed outside every exclude zone, or nothing applies: stop. */
        object Done : Step()
        /** Not placed yet: try again after [delayMs]. */
        data class Retry(val delayMs: Long) : Step()
    }

    /**
     * One attempt (`Mb.process`): [point] is where the cache or the network placed [destination]
     * on attempt [attempt] (0-based), or null when it is still unknown.
     */
    fun attempt(config: EngineConfig, destination: String, attempt: Int, point: DoubleArray?): Step = when {
        !applies(config) -> Step.Done
        point == null -> if (attempt < RETRY_DELAYS_MS.size) Step.Retry(RETRY_DELAYS_MS[attempt]) else Step.Done
        inside(config, point[0], point[1]) && claimWarning(destination) -> Step.Warn
        else -> Step.Done
    }

    /** The geocoder path (`Mb.listener`): a freshly placed address that was accepted recently. */
    fun onPlaced(config: EngineConfig, address: String, lat: Double, lng: Double): Boolean =
        address.isNotBlank() && applies(config) && inside(config, lat, lng) && acceptedRecently(address) && claimWarning(address)

    companion object {
        /** `Mb.connect` delays between attempts. */
        val RETRY_DELAYS_MS = longArrayOf(20_000L, 40_000L, 120_000L, 120_000L)
        /** Attempts that may also ask the network (the reference: `i < 2`). */
        const val NETWORK_ATTEMPTS = 2
        const val ACCEPT_WINDOW_MS = 2 * 60 * 60_000L
        const val WARN_DEDUPE_MS = 30 * 60_000L
        private const val MAX_ENTRIES = 64
    }
}
