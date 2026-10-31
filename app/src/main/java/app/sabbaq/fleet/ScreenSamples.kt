package app.sabbaq.fleet

import android.content.Context
import android.os.SystemClock
import app.sabbaq.BuildConfig
import app.sabbaq.core.engine.ScreenSampler
import app.sabbaq.core.engine.UiNode
import app.sabbaq.network.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executors

/**
 * Sends the redacted structure (ScreenSampler) of offer screens Sabbaq could not use for a reason
 * other than the driver's conditions: a half-read offer, a tap that was not sent, a tap Android
 * did not confirm. At most 20 a day, one per layout a day, one attempt per reason every
 * 10 minutes; plain map/menu screens, trip screens and condition rejections are never sent.
 */
object ScreenSamples {
    private const val PREFS = "sabbaq_fleet"
    private const val KEY_DAY = "samples_day"
    private const val KEY_COUNT = "samples_count"
    private const val KEY_SENT = "samples_sent"
    private const val DAILY_CAP = 20
    private const val REASON_GAP_MS = 10 * 60_000L

    /** Reader misses that mean "an offer was on screen but could not be read". */
    private val NOT_OFFERS = setOf("not_offer_screen", "online_screen", "trip_screen")

    @Volatile private var appContext: Context? = null
    private val lastAttempt = HashMap<String, Long>()
    /** The one thread that walks a live accessibility tree off the main thread (also ShadowTester's). */
    internal val walker = Executors.newSingleThreadExecutor { r -> Thread(r, "sabbaq-sampler").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) { appContext = context.applicationContext }

    fun eligibleMiss(reason: String): Boolean = reason !in NOT_OFFERS

    /** Captures [screen] off the main thread when the reason is due; never throws. */
    fun capture(provider: String, packageName: String?, reason: String, screen: UiNode?) {
        val app = appContext ?: return
        screen ?: return
        val key = reason.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(32).ifEmpty { return }
        val now = SystemClock.elapsedRealtime()
        synchronized(lastAttempt) {
            val last = lastAttempt[key]
            if (last != null && now - last < REASON_GAP_MS) return
            lastAttempt[key] = now
        }
        if (!budgetLeft(app)) return
        runCatching {
            walker.execute {
                runCatching {
                    val sample = ScreenSampler.capture(screen, ScreenSampler.vocabulary(FleetRepository.lexicon().words))
                    if (!markSent(app, sample.signature)) return@runCatching
                    val body = encode(provider, targetVersion(app, packageName), key, sample)
                    scope.launch { SupabaseClient.ingestScreenSample(body) }
                }
            }
        }
    }

    private fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

    private fun budgetLeft(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DAY, null) != today() || prefs.getInt(KEY_COUNT, 0) < DAILY_CAP
    }

    /** Counts the sample against today's budget; false when this layout was already sent today. */
    @Synchronized
    private fun markSent(context: Context, signature: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sameDay = prefs.getString(KEY_DAY, null) == today()
        val count = if (sameDay) prefs.getInt(KEY_COUNT, 0) else 0
        val sent = if (sameDay) prefs.getStringSet(KEY_SENT, emptySet()).orEmpty() else emptySet()
        if (count >= DAILY_CAP || signature in sent) return false
        prefs.edit().putString(KEY_DAY, today()).putInt(KEY_COUNT, count + 1).putStringSet(KEY_SENT, sent + signature).apply()
        return true
    }

    private fun targetVersion(context: Context, packageName: String?): String? = packageName?.let { pkg ->
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull()?.filter { it.isLetterOrDigit() && it.code < 128 || it in "._-" }?.take(32)?.ifEmpty { null }
    }

    internal fun encode(provider: String, targetVersion: String?, reason: String, sample: ScreenSampler.Sample): JsonObject = buildJsonObject {
        put("provider", provider.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(32).ifEmpty { "unknown" })
        targetVersion?.let { put("target_app_version", it) }
        put("app_version", BuildConfig.VERSION_NAME.filter { it.isLetterOrDigit() && it.code < 128 || it in "._-" }.take(32))
        put("reason", reason)
        put("signature", sample.signature)
        putJsonArray("nodes") {
            for (n in sample.nodes) addJsonObject {
                put("c", n.className)
                put("v", n.viewId)
                putJsonArray("b") { n.bounds.forEach { add(it) } }
                put("k", n.clickable)
                put("e", n.enabled)
                n.text?.let { put("t", it) }
                put("d", n.depth)
            }
        }
    }
}
