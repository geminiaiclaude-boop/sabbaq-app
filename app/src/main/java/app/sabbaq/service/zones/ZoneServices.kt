package app.sabbaq.service.zones

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.sabbaq.BuildConfig
import app.sabbaq.core.engine.DecisionEngine
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.zones.PoiIndex
import app.sabbaq.core.engine.zones.ZoneEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The device half of FareSniper's zones: the driver's last position, the address → point cache
 * (`address_poi_cache`), the geocoding queue (`pending_addresses`, 150 entries) and the worker
 * that fills the cache from OpenStreetMap Nominatim, then Android's geocoder.
 */
object ZoneStore : ZoneEnvironment {
    private const val PREFS = "sabbaq_zones"
    private const val KEY_LAT = "driver_lat"
    private const val KEY_LNG = "driver_lng"
    private const val KEY_FIX_AT = "driver_fix_at"
    private const val KEY_PENDING = "pending_addresses"
    private const val KEY_FAILS = "pending_fail_counts"
    private const val CACHE_FILE = "address_poi_cache.json"
    private const val MAX_PENDING = 150
    private const val MAX_CACHE_KEYS = 4000
    private const val MAX_FAILS = 3

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var cacheFile: File? = null
    @Volatile private var cacheRoot: JsonObject = JsonObject(emptyMap())
    @Volatile private var index: PoiIndex? = null
    @Volatile private var appContext: Context? = null
    /** Addresses already queued this session: the decision asks again on every frame of the same offer. */
    private val recentRequests = LinkedHashSet<String>()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cacheFile = File(app.filesDir, CACHE_FILE)
        runCatching {
            val text = cacheFile?.takeIf { it.exists() }?.readText()
            if (!text.isNullOrBlank()) {
                cacheRoot = json.parseToJsonElement(text).jsonObject
                index = PoiIndex(cacheRoot)
            }
        }
    }

    /** The last saved position, however old, as FareSniper uses `driver_lat/lng` (see [fixAgeMs]). */
    override fun driverLocation(): DoubleArray? {
        val p = prefs ?: return null
        if (p.getLong(KEY_FIX_AT, 0L) == 0L) return null
        val lat = java.lang.Double.longBitsToDouble(p.getLong(KEY_LAT, 0L))
        val lng = java.lang.Double.longBitsToDouble(p.getLong(KEY_LNG, 0L))
        return if (lat == 0.0 && lng == 0.0) null else doubleArrayOf(lat, lng)
    }

    /** Age of the saved position, or null when none was ever saved. */
    fun fixAgeMs(): Long? = prefs?.getLong(KEY_FIX_AT, 0L)?.takeIf { it > 0L }?.let { System.currentTimeMillis() - it }

    fun saveDriverLocation(lat: Double, lng: Double) {
        prefs?.edit()
            ?.putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(lat))
            ?.putLong(KEY_LNG, java.lang.Double.doubleToRawLongBits(lng))
            ?.putLong(KEY_FIX_AT, System.currentTimeMillis())
            ?.apply()
    }

    override fun poiIndex(): PoiIndex? = index

    override fun nowMs(): Long = System.currentTimeMillis()

    /** `b9.callback`: queue once, drop the oldest beyond 150, wake the worker. */
    override fun requestGeocode(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val p = prefs ?: return
        synchronized(lock) {
            if (!recentRequests.add(clean)) return
            if (recentRequests.size > MAX_PENDING * 2) recentRequests.remove(recentRequests.first())
            val list = pending(p)
            if (clean in list) return
            if (cacheKey(clean) in cacheRoot) return
            list += clean
            while (list.size > MAX_PENDING) list.removeAt(0)
            p.edit().putString(KEY_PENDING, JsonArray(list.map { JsonPrimitive(it) }).toString()).apply()
        }
        appContext?.let { GeocodeWorker.kick(it) }
    }

    fun pendingCount(): Int = prefs?.let { synchronized(lock) { pending(it).size } } ?: 0

    fun cachedCount(): Int = cacheRoot.size

    internal fun takeNext(): String? = prefs?.let { synchronized(lock) { pending(it).firstOrNull() } }

    /** Removes [text] from the queue; on failure it goes to the back until it failed three times. */
    internal fun finish(text: String, points: List<DoubleArray>) {
        val p = prefs ?: return
        synchronized(lock) {
            val list = pending(p)
            list.remove(text)
            val fails = runCatching { json.parseToJsonElement(p.getString(KEY_FAILS, "{}")!!).jsonObject.toMutableMap() }
                .getOrDefault(mutableMapOf())
            if (points.isEmpty()) {
                recentRequests.remove(text)
                val n = (fails[text]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) + 1
                if (n < MAX_FAILS) { list += text; fails[text] = JsonPrimitive(n) } else fails.remove(text)
            } else {
                fails.remove(text)
                store(text, points)
            }
            p.edit().putString(KEY_PENDING, JsonArray(list.map { JsonPrimitive(it) }).toString())
                .putString(KEY_FAILS, JsonObject(fails).toString()).apply()
        }
    }

    private fun store(text: String, points: List<DoubleArray>) {
        val value = buildJsonObject {
            put("points", buildJsonArray {
                points.take(5).forEach { add(buildJsonObject { put("lat", it[0]); put("lng", it[1]) }) }
            })
        }
        val map = LinkedHashMap(cacheRoot)
        map[cacheKey(text)] = value
        while (map.size > MAX_CACHE_KEYS) map.remove(map.keys.first())
        val root = JsonObject(map)
        runCatching {
            val file = cacheFile ?: return
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        cacheRoot = root
        index = PoiIndex(root)
    }

    /** National short addresses are stored lower-cased; every other address by its folded text. */
    private fun cacheKey(text: String): String =
        if (NATIONAL.matches(text)) text.lowercase() else DecisionEngine.fold(text)

    private fun pending(p: SharedPreferences): MutableList<String> = runCatching {
        json.parseToJsonElement(p.getString(KEY_PENDING, "[]")!!).jsonArray.map { it.jsonPrimitive.content }.toMutableList()
    }.getOrDefault(mutableListOf())

    private val NATIONAL = Regex("[A-Za-z]{4}[0-9]{4}")
}

/**
 * Geocodes the queue one address at a time, at most one request a second (the Nominatim
 * usage policy), biased to Saudi Arabia, Jordan and the driver's region. Only the offer's
 * address and a coarse box (position rounded to 0.5°, ±1°) leave the device, never the fix itself.
 */
object GeocodeWorker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    fun kick(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            while (isActive) {
                val text = ZoneStore.takeNext() ?: break
                val near = ZoneStore.driverLocation()
                val points = runCatching { nominatim(text, near) }.getOrDefault(emptyList())
                    .ifEmpty { runCatching { androidGeocoder(app, text, near) }.getOrDefault(emptyList()) }
                ZoneStore.finish(text, points)
                delay(1_100L)
            }
        }
    }

    private fun nominatim(text: String, near: DoubleArray?): List<DoubleArray> {
        val q = URLEncoder.encode(text, "UTF-8")
        val box = near?.let {
            val lat = Math.round(it[0] * 2.0) / 2.0
            val lng = Math.round(it[1] * 2.0) / 2.0
            "&viewbox=${lng - 1.0},${lat + 1.0},${lng + 1.0},${lat - 1.0}"
        }.orEmpty()
        val url = URL("https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=5&countrycodes=sa,jo&accept-language=ar,en$box")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "SabbaqDriver/${BuildConfig.VERSION_NAME} (Android)")
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Json.parseToJsonElement(body).jsonArray.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val lng = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (lat == null || lng == null) null else doubleArrayOf(lat, lng)
            }
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun androidGeocoder(context: Context, text: String, near: DoubleArray?): List<DoubleArray> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context)
        val found = if (near != null) {
            val lat = Math.round(near[0] * 2.0) / 2.0
            val lng = Math.round(near[1] * 2.0) / 2.0
            geocoder.getFromLocationName(text, 5, lat - 1.0, lng - 1.0, lat + 1.0, lng + 1.0)
        } else geocoder.getFromLocationName(text, 5)
        return found.orEmpty().map { doubleArrayOf(it.latitude, it.longitude) }
    }
}

/**
 * Keeps the driver's position fresh while a geographic rule is on (FareSniper's `driver_lat/lng`):
 * network and GPS updates every 15 s or 50 m. Needs the location permission; without it the
 * zone stage sees no position and treats the driver as unknown.
 */
class DriverLocationTracker(private val context: Context) : LocationListener {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    @Volatile private var running = false

    fun update(config: EngineConfig) {
        if (config.zoneStageOn && hasPermission(context)) start() else stop()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (running) return
        val m = manager ?: return
        if (!hasPermission(context)) return
        running = true
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            runCatching {
                if (m.isProviderEnabled(provider)) m.requestLocationUpdates(provider, 15_000L, 50f, this, Looper.getMainLooper())
            }
            runCatching { m.getLastKnownLocation(provider)?.let(::onLocationChanged) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.removeUpdates(this) }
    }

    override fun onLocationChanged(location: Location) {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs > 30 * 60_000L) return
        ZoneStore.saveDriverLocation(location.latitude, location.longitude)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
