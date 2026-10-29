package app.sabbaq.stats

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Minimal local-only event record. Intentionally excludes offer text, addresses, fares and IDs. */
@Serializable
data class OfferDecisionLogEntry(
    val timestampEpochMs: Long,
    val profileId: String,
    val outcome: DecisionLogOutcome,
    /** The reference engine decision reason keys (`failed_price`, `dest_excluded`, …). */
    val reasons: List<String> = emptyList(),
)

@Serializable
enum class DecisionLogOutcome { REJECTED, MATCHED, SEMANTIC_CLICK_SENT, GESTURE_CLICK_SENT, ACCEPTED_CONFIRMED, CONFIRMATION_TIMEOUT }

/** Seven-day, bounded, device-private history. It has no network or analytics integration. */
object OfferDecisionLogStore {
    private const val PREFS = "sabbaq_decision_log"
    private const val KEY = "events_v1"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 300
    private const val MAX_CHARS = 65_536
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null
    private var entries = emptyList<OfferDecisionLogEntry>()
    private val _rows = kotlinx.coroutines.flow.MutableStateFlow(emptyList<OfferDecisionLogEntry>())
    /** Newest first; updates on every decision so the log screen stays live. */
    val rows: kotlinx.coroutines.flow.StateFlow<List<OfferDecisionLogEntry>> = _rows
    private val observedFingerprintHashes = LinkedHashSet<Int>()

    fun init(context: Context) = synchronized(lock) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs?.getString(KEY, null)?.takeIf { it.length <= MAX_CHARS }
            entries = runCatching {
                raw?.let { json.decodeFromString<List<OfferDecisionLogEntry>>(it) }
                    .orEmpty()
            }
                .getOrDefault(emptyList())
                .filter { it.timestampEpochMs <= System.currentTimeMillis() &&
                    System.currentTimeMillis() - it.timestampEpochMs <= MAX_AGE_MS }
                .takeLast(MAX_ENTRIES)
            persistLocked()
        }
    }

    /** Dedupe material is transient, process-local only, and never serialized. */
    fun recordDecision(fingerprint: String, profileId: String, rejectedReason: String) = synchronized(lock) {
        val rejectedReasons = listOf(rejectedReason).filter { it.isNotBlank() }
        val hash = fingerprint.hashCode()
        if (!observedFingerprintHashes.add(hash)) return@synchronized
        if (observedFingerprintHashes.size > 2048) observedFingerprintHashes.clear()
        appendLocked(OfferDecisionLogEntry(
            timestampEpochMs = System.currentTimeMillis(),
            profileId = safeProfile(profileId),
            outcome = if (rejectedReasons.isEmpty()) DecisionLogOutcome.MATCHED else DecisionLogOutcome.REJECTED,
            reasons = rejectedReasons.distinct().take(16),
        ))
    }

    fun recordOutcome(profileId: String, outcome: DecisionLogOutcome) = synchronized(lock) {
        if (outcome == DecisionLogOutcome.REJECTED || outcome == DecisionLogOutcome.MATCHED) return@synchronized
        appendLocked(OfferDecisionLogEntry(System.currentTimeMillis(), safeProfile(profileId), outcome))
    }

    fun snapshot(): List<OfferDecisionLogEntry> = synchronized(lock) {
        pruneLocked(System.currentTimeMillis())
        entries.sortedByDescending { it.timestampEpochMs }
    }

    fun clear(context: Context): Boolean = synchronized(lock) {
        val target = prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        val ok = runCatching { target.edit().remove(KEY).commit() }.getOrDefault(false)
        if (ok) { entries = emptyList(); observedFingerprintHashes.clear(); publishLocked() }
        ok
    }

    private fun publishLocked() {
        _rows.value = entries.sortedByDescending { it.timestampEpochMs }
    }

    private fun appendLocked(entry: OfferDecisionLogEntry) {
        if (prefs == null) return
        pruneLocked(entry.timestampEpochMs)
        entries = (entries + entry).takeLast(MAX_ENTRIES)
        persistLocked()
        publishLocked()
    }

    private fun pruneLocked(now: Long) {
        val kept = entries.filter { it.timestampEpochMs <= now && now - it.timestampEpochMs <= MAX_AGE_MS }
        if (kept.size != entries.size) { entries = kept; persistLocked() }
    }

    private fun persistLocked() {
        val serialized = runCatching { json.encodeToString(entries.takeLast(MAX_ENTRIES)) }.getOrNull() ?: return
        if (serialized.length <= MAX_CHARS) runCatching { prefs?.edit()?.putString(KEY, serialized)?.apply() }
    }

    private fun safeProfile(profileId: String) = profileId.filter { it.isLetterOrDigit() || it in "._-" }.take(48)
}
