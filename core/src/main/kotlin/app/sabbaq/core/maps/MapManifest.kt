package app.sabbaq.core.maps

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The offline city maps Sabbaq hosts: one PMTiles archive per city, cut from a Protomaps
 * OpenStreetMap build, listed in `manifest.json` next to them. [bbox] is west, south, east, north.
 */
@Serializable
data class MapPack(
    val id: String,
    val name_ar: String,
    val name_en: String,
    val bbox: List<Double>,
    val file: String,
    val bytes: Long,
    val sha256: String,
) {
    val west get() = bbox[0]
    val south get() = bbox[1]
    val east get() = bbox[2]
    val north get() = bbox[3]
    fun contains(lat: Double, lng: Double) = lat in south..north && lng in west..east
    val centerLat get() = (south + north) / 2
    val centerLng get() = (west + east) / 2
}

@Serializable
data class MapManifest(
    val schema: Int,
    val build: String,
    val generated_at: String,
    val attribution: String,
    val packs: List<MapPack>,
)

/** Which pack a driver needs and when it is downloaded again. Pure; the app does the network. */
object MapPacks {
    const val SCHEMA = 1
    /** A pack older than this is replaced when the server lists a newer build. */
    const val REFRESH_MS = 30L * 24 * 60 * 60 * 1000
    /** The server's list is read again at most this often. */
    const val MANIFEST_TTL_MS = 24L * 60 * 60 * 1000
    /** A driver just outside every city still gets the nearest one within this distance. */
    const val NEAR_KM = 40.0
    private const val MAX_BYTES = 200L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val ID = Regex("^[a-z0-9_-]{2,40}$")
    private val FILE = Regex("^[a-z0-9_.-]{1,80}\\.pmtiles$")
    private val SHA = Regex("^[0-9a-f]{64}$")

    /** The manifest when it is well formed and every pack is sane; null otherwise (never trusted half). */
    fun parse(text: String): MapManifest? {
        val m = runCatching { json.decodeFromString(MapManifest.serializer(), text) }.getOrNull() ?: return null
        if (m.schema != SCHEMA || m.packs.isEmpty()) return null
        val ok = m.packs.all { p ->
            ID.matches(p.id) && FILE.matches(p.file) && SHA.matches(p.sha256) && p.bytes in 1..MAX_BYTES &&
                p.bbox.size == 4 && p.west < p.east && p.south < p.north &&
                p.south >= -90 && p.north <= 90 && p.west >= -180 && p.east <= 180
        }
        return if (ok && m.packs.map { it.id }.toSet().size == m.packs.size) m else null
    }

    /** The pack containing the driver (the smallest when several do), else the nearest within [NEAR_KM]. */
    fun packFor(manifest: MapManifest, lat: Double, lng: Double): MapPack? {
        manifest.packs.filter { it.contains(lat, lng) }.minByOrNull { (it.east - it.west) * (it.north - it.south) }?.let { return it }
        return manifest.packs.map { it to edgeKm(it, lat, lng) }.filter { it.second <= NEAR_KM }.minByOrNull { it.second }?.first
    }

    /**
     * Download [wanted] when nothing is stored for it, or when the stored copy is another build and
     * at least a month old (the monthly refresh); a stored copy of the same build is kept.
     */
    fun needsDownload(wanted: MapPack, storedSha: String?, storedAtMs: Long?, nowMs: Long): Boolean = when {
        storedSha == null || storedAtMs == null -> true
        storedSha == wanted.sha256 -> false
        else -> nowMs - storedAtMs >= REFRESH_MS
    }

    private fun edgeKm(p: MapPack, lat: Double, lng: Double): Double {
        val cLat = lat.coerceIn(p.south, p.north)
        val cLng = lng.coerceIn(p.west, p.east)
        val y = (lat - cLat) * 110.54
        val x = (lng - cLng) * 111.32 * cos(Math.toRadians(lat))
        return sqrt(x * x + y * y)
    }
}
