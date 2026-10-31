package app.sabbaq.service.zones

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import app.sabbaq.core.engine.zones.CityIndex
import app.sabbaq.core.engine.zones.CityPlace
import app.sabbaq.core.engine.zones.GeoBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * The driver's city, downloaded ahead of time (`CityIndex`): the zone editor's map stays on it and
 * its search suggests names from it as the driver types, without a request per letter. Its names
 * also go to the address cache, so offers naming a district or landmark are placed at once.
 * Downloaded once a month or when the driver is in another city; only a position rounded to about
 * 5 km (to name the city) and the city's box leave the phone.
 */
object CityIndexStore {
    data class City(val name: String, val box: GeoBox, val places: List<CityPlace>)

    private const val FILE = "city_index.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _city = MutableStateFlow<City?>(null)
    /** The loaded city, null until one was downloaded. */
    val city: StateFlow<City?> = _city
    @Volatile private var loaded = false
    @Volatile private var saved: CityIndex.Saved? = null
    @Volatile private var lastCheck = 0L

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    /** The stored city, read from disk once. */
    private fun load(context: Context): CityIndex.Saved? {
        if (!loaded) {
            loaded = true
            saved = runCatching { file(context).takeIf { it.exists() }?.readText()?.let(CityIndex::decode) }.getOrNull()
            saved?.let { _city.value = City(it.city, it.box, it.places) }
        }
        return saved
    }

    /**
     * Loads the stored city and, at most every 10 minutes, downloads it again in the background
     * when it is a month old or the driver is in another city.
     */
    fun ensure(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (loaded && now - lastCheck < 10 * 60_000L) return
        lastCheck = now
        scope.launch { refresh(app, force = false) }
    }

    /** Returns true when a fresh index is in place. */
    suspend fun refresh(context: Context, force: Boolean): Boolean = withContext(Dispatchers.IO) {
        val stored = load(context)
        val here = ZoneStore.driverLocation() ?: return@withContext stored != null
        if (!force && !CityIndex.stale(stored, here[0], here[1], System.currentTimeMillis())) return@withContext true
        if (!lock.tryLock()) return@withContext false
        try {
            val (name, box) = cityAround(here[0], here[1]) ?: return@withContext false
            val body = ZoneWarmer.overpassBody(CityIndex.overpassQuery(box), readTimeoutMs = 90_000) ?: return@withContext false
            val places = CityIndex.parse(body)
            if (places.isEmpty()) return@withContext false
            val now = System.currentTimeMillis()
            runCatching { file(context).writeText(CityIndex.encode(name, box, now, places)) }
            saved = CityIndex.Saved(name, box, now, places)
            _city.value = City(name, box, places)
            ZoneStore.addWarmed(CityIndex.cacheEntries(places, ZoneStore.cache().keys), "warm_city")
            true
        } finally {
            lock.unlock()
        }
    }

    /** Instant suggestions from the downloaded city. */
    fun suggestLocal(text: String, limit: Int = 8): List<CityPlace> =
        _city.value?.let { CityIndex.suggest(it.places, text, ZoneStore.driverLocation(), limit) }.orEmpty()

    /**
     * Photon (komoot's OpenStreetMap search, built for type-ahead; Nominatim forbids it) inside the
     * city's box: the typed text and the box are sent.
     */
    suspend fun suggestRemote(text: String, limit: Int = 6): List<CityPlace> = withContext(Dispatchers.IO) {
        val q = text.trim()
        if (q.length < 2) return@withContext emptyList()
        val box = _city.value?.box
        val near = ZoneStore.driverLocation()
        val url = buildString {
            append("https://photon.komoot.io/api/?limit=").append(limit).append("&q=").append(URLEncoder.encode(q, "UTF-8"))
            box?.let { append("&bbox=${it.west},${it.south},${it.east},${it.north}") }
            near?.let { append("&lat=${round(it[0])}&lon=${round(it[1])}") }
        }
        val places = runCatching { ZoneWarmer.get(url, 6_000)?.let(CityIndex::parsePhoton) }.getOrNull().orEmpty()
        if (box == null) places else places.filter { p -> box.contains(p.points[0][0], p.points[0][1]) }
    }

    /** A district's boundary as zone corners (`osm` "R123"), from Nominatim. */
    suspend fun outline(osm: String, lat: Double, lng: Double): List<DoubleArray> = withContext(Dispatchers.IO) {
        if (!Regex("^[NWR][0-9]{1,12}$").matches(osm)) return@withContext emptyList()
        // A district searched by name is often only a point (a node has no boundary): the boundary
        // is then the district relation that contains that point.
        val mapped = if (osm.startsWith("N")) emptyList() else runCatching {
            ZoneWarmer.get("https://nominatim.openstreetmap.org/lookup?osm_ids=$osm&format=json&polygon_geojson=1&polygon_threshold=0.0003", 10_000)
        }.getOrNull()?.let { body ->
            val first = runCatching { (json.parseToJsonElement(body) as JsonArray).firstOrNull() as? JsonObject }.getOrNull()
            CityIndex.outline(first?.get("geojson") as? JsonObject)
        }.orEmpty()
        mapped.takeIf { it.size >= 3 } ?: containingDistrict(lat, lng)?.second.orEmpty()
    }

    /** The district boundary around a point, from the map's administrative areas; null when none is mapped. */
    suspend fun containingDistrict(lat: Double, lng: Double): Pair<String, List<DoubleArray>>? = withContext(Dispatchers.IO) {
        val body = runCatching { ZoneWarmer.overpassBody(CityIndex.containingDistrictQuery(lat, lng)) }.getOrNull() ?: return@withContext null
        CityIndex.containingDistrict(body)
    }

    /** «حيّي الحالي»: the district at the point, its name and boundary (empty when it has none mapped). */
    suspend fun districtAt(lat: Double, lng: Double): Pair<String, List<DoubleArray>>? = withContext(Dispatchers.IO) {
        val body = runCatching {
            ZoneWarmer.get("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=jsonv2&zoom=14&polygon_geojson=1&polygon_threshold=0.0003&accept-language=ar,en", 10_000)
        }.getOrNull() ?: return@withContext null
        val o = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return@withContext null
        val address = o["address"] as? JsonObject
        val name = listOf("suburb", "neighbourhood", "quarter", "city_district").firstNotNullOfOrNull { k ->
            address?.get(k)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: o["name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val mapped = CityIndex.outline(o["geojson"] as? JsonObject)
        if (mapped.size >= 3) return@withContext name to mapped
        // The district is often mapped only as a point: take the administrative district around the driver.
        containingDistrict(lat, lng)?.let { (_, ring) -> name to ring } ?: (name to mapped)
    }

    private fun cityAround(lat: Double, lng: Double): Pair<String, GeoBox>? {
        val body = runCatching {
            ZoneWarmer.get("https://nominatim.openstreetmap.org/reverse?lat=${round(lat)}&lon=${round(lng)}&format=jsonv2&zoom=10&accept-language=ar,en", 10_000)
        }.getOrNull() ?: return null
        return CityIndex.cityFromReverse(body)
    }

    /** About 5 km: enough to name the city, never the driver's street. */
    private fun round(v: Double) = Math.round(v * 20.0) / 20.0

    /**
     * «موقعي الحالي»: a fresh fix (up to 10 s), saved for the zones; the newest known one when
     * none comes. Null without the location permission or any fix.
     */
    @SuppressLint("MissingPermission")
    suspend fun freshLocation(context: Context): DoubleArray? {
        if (!DriverLocationTracker.hasPermission(context)) return null
        val m = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        var fix: Location? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && providers.isNotEmpty()) {
            fix = withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine { cont ->
                    val signal = android.os.CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        m.getCurrentLocation(providers.first(), signal, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }
        if (fix == null) {
            fix = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        }
        return fix?.let {
            ZoneStore.saveDriverLocation(it.latitude, it.longitude)
            doubleArrayOf(it.latitude, it.longitude)
        } ?: ZoneStore.driverLocation()
    }
}
