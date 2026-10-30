package app.sabbaq.service.zones

import app.sabbaq.BuildConfig
import app.sabbaq.core.engine.zones.ZoneCacheWarm
import app.sabbaq.core.engine.zones.ZoneName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
            if (found.isNullOrEmpty()) {
                var calls = 0
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
