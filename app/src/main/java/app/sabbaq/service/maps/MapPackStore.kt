package app.sabbaq.service.maps

import android.content.Context
import android.content.SharedPreferences
import app.sabbaq.BuildConfig
import app.sabbaq.core.maps.MapManifest
import app.sabbaq.core.maps.MapPack
import app.sabbaq.core.maps.MapPacks
import app.sabbaq.service.zones.ZoneStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The driver's offline city map: Sabbaq's own Storage lists every Saudi city's PMTiles archive in
 * `maps/sa/manifest.json`; the archive of the city the driver is in is downloaded once (Wi-Fi or
 * mobile data), checked against the manifest's size and SHA-256, and replaced by a newer build
 * at most once a month. The map then works fully offline. Nothing about the driver is sent: the
 * city is chosen on the phone.
 */
object MapPackStore {
    sealed interface Status {
        data object Idle : Status
        data class Downloading(val pack: MapPack, val done: Long) : Status
        data class Ready(val pack: MapPack, val file: File) : Status
        data class Failed(val reason: String) : Status
    }

    private const val PREFS = "sabbaq_maps"
    private const val KEY_ID = "pack_id"
    private const val KEY_FILE = "pack_file"
    private const val KEY_SHA = "pack_sha"
    private const val KEY_AT = "pack_at"
    private const val KEY_MANIFEST_AT = "manifest_at"
    private const val KEEP = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status
    @Volatile private var lastCheck = 0L
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    }

    private fun base() = BuildConfig.SUPABASE_URL.trimEnd('/') + "/storage/v1/object/public/maps/sa/"
    private fun dir(context: Context) = File(context.applicationContext.filesDir, "maps").apply { mkdirs() }
    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored archive of the driver's city, when one is in place. */
    fun stored(context: Context): Pair<MapPack, File>? {
        val p = prefs(context)
        val file = File(dir(context), p.getString(KEY_FILE, null) ?: return null)
        if (!file.isFile) return null
        val manifest = cachedManifest(context) ?: return null
        val pack = manifest.packs.firstOrNull { it.id == p.getString(KEY_ID, null) && it.file == file.name }
            ?: manifest.packs.firstOrNull { it.id == p.getString(KEY_ID, null) }?.copy(file = file.name)
            ?: return null
        return pack to file
    }

    /** Checks at most every 10 minutes, in the background, whether the driver's city must be downloaded. */
    fun ensure(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 10 * 60_000L) return
        lastCheck = now
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }

    suspend fun refresh(context: Context): Status = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext _status.value
        try {
            val manifest = manifest(context) ?: return@withContext stored(context)?.let { Status.Ready(it.first, it.second) }
                .also { if (it != null) _status.value = it } ?: Status.Failed("manifest").also { _status.value = it }
            val here = ZoneStore.driverLocation() ?: return@withContext idleOrReady(context)
            val wanted = MapPacks.packFor(manifest, here[0], here[1]) ?: return@withContext idleOrReady(context)
            val p = prefs(context)
            val sameCity = p.getString(KEY_ID, null) == wanted.id
            val have = File(dir(context), p.getString(KEY_FILE, "") ?: "")
            if (sameCity && have.isFile && !MapPacks.needsDownload(wanted, p.getString(KEY_SHA, null), p.getLong(KEY_AT, 0L).takeIf { it > 0 }, System.currentTimeMillis())) {
                return@withContext Status.Ready(wanted, have).also { _status.value = it }
            }
            val cached = File(dir(context), wanted.file)
            val file = if (cached.isFile && cached.length() == wanted.bytes && sha256(cached) == wanted.sha256) cached
                else download(context, wanted) ?: return@withContext (_status.value as? Status.Failed ?: Status.Failed("download")).also { _status.value = it }
            p.edit().putString(KEY_ID, wanted.id).putString(KEY_FILE, file.name).putString(KEY_SHA, wanted.sha256)
                .putLong(KEY_AT, System.currentTimeMillis()).apply()
            prune(context, file)
            Status.Ready(wanted, file).also { _status.value = it }
        } finally {
            lock.unlock()
        }
    }

    private fun idleOrReady(context: Context): Status =
        (stored(context)?.let { Status.Ready(it.first, it.second) } ?: Status.Idle).also { _status.value = it }

    /** The server's list, read again once a day; the stored copy when the server cannot be reached. */
    private fun manifest(context: Context): MapManifest? {
        val p = prefs(context)
        val file = File(dir(context), "manifest.json")
        if (file.isFile && System.currentTimeMillis() - p.getLong(KEY_MANIFEST_AT, 0L) < MapPacks.MANIFEST_TTL_MS) {
            MapPacks.parse(file.readText())?.let { return it }
        }
        val fresh = runCatching {
            http.newCall(Request.Builder().url(base() + "manifest.json").build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        }.getOrNull()?.takeIf { it.length < 512 * 1024 }
        val parsed = fresh?.let(MapPacks::parse)
        if (parsed != null) {
            File(dir(context), "manifest.json.tmp").apply { writeText(fresh); renameTo(file) }
            p.edit().putLong(KEY_MANIFEST_AT, System.currentTimeMillis()).apply()
            return parsed
        }
        return cachedManifest(context)
    }

    private fun cachedManifest(context: Context): MapManifest? =
        File(dir(context), "manifest.json").takeIf { it.isFile }?.let { runCatching { MapPacks.parse(it.readText()) }.getOrNull() }

    /** Resumes a partial download, then accepts the file only with the manifest's size and SHA-256. */
    private fun download(context: Context, pack: MapPack): File? {
        val d = dir(context)
        if (d.usableSpace < pack.bytes * 2) { _status.value = Status.Failed("space"); return null }
        val part = File(d, pack.file + ".part")
        if (part.length() > pack.bytes) part.delete()
        repeat(3) {
            val from = part.length()
            _status.value = Status.Downloading(pack, from)
            val ok = runCatching {
                val req = Request.Builder().url(base() + pack.file).apply { if (from > 0) header("Range", "bytes=$from-") }.build()
                http.newCall(req).execute().use { r ->
                    val append = from > 0 && r.code == 206
                    if (!r.isSuccessful) return@use false
                    val body = r.body ?: return@use false
                    FileOutputStream(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = if (append) from else 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastReport > 512 * 1024) { lastReport = done; _status.value = Status.Downloading(pack, done) }
                            }
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (ok && part.length() == pack.bytes) {
                if (sha256(part) != pack.sha256) { part.delete(); _status.value = Status.Failed("checksum"); return null }
                val file = File(d, pack.file)
                file.delete()
                return if (part.renameTo(file)) file else null
            }
            if (part.length() >= pack.bytes) part.delete()
        }
        _status.value = Status.Failed("download")
        return null
    }

    /** Keeps the current archive and the one before it (a driver going back and forth), nothing else. */
    private fun prune(context: Context, current: File) {
        val archives = dir(context).listFiles { f -> f.name.endsWith(".pmtiles") }.orEmpty().sortedByDescending { it.lastModified() }
        archives.filter { it != current }.drop(KEEP - 1).forEach { it.delete() }
        dir(context).listFiles { f -> f.name.endsWith(".part") && !f.name.startsWith(current.name) }.orEmpty()
            .filter { System.currentTimeMillis() - it.lastModified() > 7L * 24 * 60 * 60 * 1000 }.forEach { it.delete() }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
