package app.sabbaq.service.zones

import app.sabbaq.BuildConfig
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.zones.ZoneCacheWarm
import app.sabbaq.core.engine.zones.ZoneGeo
import app.sabbaq.core.engine.zones.ZoneName
import app.sabbaq.network.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fills the place cache with the names inside a zone before it is switched on (the reference's
 * `ZH.resolver`): OpenStreetMap's named streets and places inside the polygon first; when it
 * names nothing, up to 24 grid points inside the zone reverse geocoded (60 calls at most, 1.1 s
 * apart as OpenStreetMap asks). Only the zone's corners leave the phone, never the driver's position.
 */
object ZoneWarmer {
    /** Outcome of one warming: [ok] false when it could not run; [names] the cached names now inside the zone. */
    data class Result(val ok: Boolean, val added: Int, val names: Int)

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val WAIT_MS = 90_000L

    /**
     * Runs [warm] in the app's own scope, so the cache still fills when the screen that asked is
     * closed; the zone then stays waiting and its next «تكييش وتفعيل» finds the names at once.
     */
    fun warmAsync(polygon: List<DoubleArray>): Deferred<Result> = scope.async { warm(polygon) }

    suspend fun warm(polygon: List<DoubleArray>): Result = withContext(Dispatchers.IO) {
        if (polygon.size < 3) return@withContext Result(false, 0, 0)
        // One warming at a time; a second waits up to 90 s as the reference's tryLock does.
        val locked = withTimeoutOrNull(WAIT_MS) { lock.lock(); true } ?: return@withContext Result(false, 0, 0)
        try {
            val found = runCatching { overpass(polygon) }.getOrNull()
            var added = 0
            val osm = ZoneCacheWarm.newEntries(found.orEmpty(), ZoneStore.cache().keys)
            ZoneStore.addWarmed(osm, "warm_osm")
            added += osm.size
            // `ZH.provider`: Google Places around each grid point, only when the administration
            // switched it on (the server answers "disabled" otherwise and the step stops at once).
            var calls = 1
            val radius = ZoneCacheWarm.placesRadius(polygon)
            for (p in ZoneCacheWarm.grid(polygon)) {
                if (calls >= ZoneCacheWarm.MAX_CALLS) break
                val places = SupabaseClient.zonePlaces(p[0], p[1], radius) ?: break
                calls++
                val inside = places.filter { ZoneCacheWarm.inside(it.points[0][0], it.points[0][1], polygon) }
                val entries = ZoneCacheWarm.newEntries(inside, ZoneStore.cache().keys)
                ZoneStore.addWarmed(entries, "warm_places")
                added += entries.size
                delay(200L)
            }
            if (osm.isEmpty()) {
                for (p in ZoneCacheWarm.grid(polygon)) {
                    if (calls >= ZoneCacheWarm.MAX_CALLS) break
                    calls++
                    val names = runCatching { reverseRoads(p[0], p[1]) }.getOrDefault(emptyList())
                    val entries = ZoneCacheWarm.newEntries(names.map { ZoneName(it, listOf(p)) }, ZoneStore.cache().keys)
                    ZoneStore.addWarmed(entries, "warm_nominatim")
                    added += entries.size
                    delay(1_100L)
                }
            }
            Result(true, added, ZoneCacheWarm.namesInside(ZoneStore.cache(), polygon).size)
        } finally {
            if (locked) lock.unlock()
        }
    }

    /**
     * `ZH.parser`: the named streets and places on the straight road from the driver to each
     * active destination zone («على الطريق»), in squares of the corridor's half width, 60 squares
     * at most, 1.2 s apart. Runs again only when the zones change or the driver leaves the 2 km
     * cell; skipped while another warming runs. Only the squares' corners leave the phone.
     */
    suspend fun warmCorridor(config: EngineConfig, driver: DoubleArray?, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val zonesJson = config.destZonesJson
        val zones = ZoneGeo.polygons(zonesJson)
        if (zones.isEmpty() || driver == null) return@withContext 0
        val signature = ZoneCacheWarm.corridorSignature(zonesJson, driver[0], driver[1])
        if (!force && ZoneStore.corridorSignature() == signature) return@withContext 0
        if (!lock.tryLock()) return@withContext 0
        try {
            val half = config.corridorHalfWidthKm
            val step = ZoneCacheWarm.corridorStepKm(half)
            var added = 0
            var calls = 0
            for (z in zones) {
                val corners = List(z.lats.size) { doubleArrayOf(z.lats[it], z.lngs[it]) }
                val c = ZoneCacheWarm.centroid(corners)
                for (p in ZoneCacheWarm.corridorPoints(driver[0], driver[1], c[0], c[1], zonesJson, step)) {
                    if (calls >= ZoneCacheWarm.MAX_CALLS) break
                    calls++
                    val square = ZoneCacheWarm.box(p[0], p[1], half)
                    val found = runCatching { overpass(square) }.getOrNull().orEmpty()
                    val entries = ZoneCacheWarm.newEntries(found, ZoneStore.cache().keys)
                    ZoneStore.addWarmed(entries, "warm_corridor_osm")
                    added += entries.size
                    delay(1_200L)
                }
                if (calls >= ZoneCacheWarm.MAX_CALLS) break
            }
            ZoneStore.saveCorridorSignature(signature)
            added
        } finally {
            lock.unlock()
        }
    }

    /**
     * The reference service's loop: every 90 s while the engine runs with destination zones and
     * «على الطريق» on, [warmCorridor] (which does nothing when nothing changed).
     */
    fun corridorLoop(config: () -> EngineConfig, running: () -> Boolean): Job = scope.launch {
        while (isActive) {
            delay(CORRIDOR_EVERY_MS)
            val cfg = config()
            if (running() && cfg.destZonesEnabled && cfg.destZonesOnTheWayEnabled) {
                runCatching { warmCorridor(cfg, ZoneStore.driverLocation()) }
            }
        }
    }

    private const val CORRIDOR_EVERY_MS = 90_000L

    /** `fragment.execute`: each Overpass server by POST then GET, the whole list twice with 2 s between. */
    private suspend fun overpass(polygon: List<DoubleArray>): List<ZoneName>? {
        val body = "data=" + URLEncoder.encode(ZoneCacheWarm.overpassQuery(polygon), "UTF-8")
        repeat(2) { round ->
            for (url in ZoneCacheWarm.OVERPASS_URLS) {
                val text = runCatching { post(url, body) }.getOrNull() ?: runCatching { get("$url?$body", 30_000) }.getOrNull()
                if (text != null && text.trimStart().startsWith("{")) return ZoneCacheWarm.parseOverpass(text, polygon)
            }
            if (round == 0) delay(2_000L)
        }
        return null
    }

    /** `fragment.validator` without a Google key: OpenStreetMap's road name at the point. */
    private fun reverseRoads(lat: Double, lng: Double): List<String> {
        val text = get("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=jsonv2&accept-language=ar&zoom=16", 5_000) ?: return emptyList()
        val address = (Json.parseToJsonElement(text) as? JsonObject)?.get("address") as? JsonObject ?: return emptyList()
        return listOfNotNull(address["road"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun post(url: String, body: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, readTimeoutMs: Int): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private val USER_AGENT = "SabbaqDriver/${BuildConfig.VERSION_NAME} (Android)"
}
