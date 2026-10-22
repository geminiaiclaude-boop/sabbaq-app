package app.sabbaq.stats

import android.content.Context
import android.content.SharedPreferences
import app.sabbaq.core.OfferStatsDay
import app.sabbaq.core.OfferStatsDeduplicator
import app.sabbaq.core.OfferStatsPeriods
import app.sabbaq.core.OfferStatsTotals
import app.sabbaq.core.ReasonCode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Period totals exposed to the driver's dashboard. */
data class DriverStatsSnapshot(
    val today: OfferStatsTotals,
    val week: OfferStatsTotals,
    val month: OfferStatsTotals,
    val date: String,
)

/**
 * Device-only aggregate storage. The serialized schema contains dates and counters only;
 * offer text, addresses, identifiers and fingerprints are never persisted or uploaded.
 */
object OfferStatsStore {
    private const val PREFERENCES_NAME = "sabbaq_stats"
    private const val KEY_DAILY_AGGREGATES = "daily_aggregates"
    private const val MAX_RETAINED_DAYS = 37
    private const val MAX_SERIALIZED_CHARS = 65_536
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE
    private var preferences: SharedPreferences? = null
    private var daily = emptyMap<LocalDate, OfferStatsDay>()
    private val deduplicator = OfferStatsDeduplicator()
    private val _snapshots = MutableStateFlow(emptySnapshot(LocalDate.now()))
    val snapshots: StateFlow<DriverStatsSnapshot> = _snapshots.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (preferences == null) {
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            loadLocked()
        }
        publishLocked(LocalDate.now())
    }

    /** Refresh at screen resume so date/period boundaries follow the device's local date. */
    fun refresh() = synchronized(lock) {
        if (preferences == null) return@synchronized
        rollAndPublishLocked(LocalDate.now())
    }

    /** Remove the device-local history after account deletion or an explicit privacy request. */
    fun clear(context: Context): Boolean = synchronized(lock) {
        val prefs = preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .also { preferences = it }
        val cleared = runCatching { prefs.edit().remove(KEY_DAILY_AGGREGATES).commit() }.getOrDefault(false)
        if (cleared) {
            daily = emptyMap()
            deduplicator.reset()
            publishLocked(LocalDate.now())
        }
        cleared
    }

    /** Count one distinct offer fingerprint per process/day; only its hash is held in memory. */
    fun recordVisible(fingerprint: String) = synchronized(lock) {
        val today = LocalDate.now()
        rollLocked(today)
        if (deduplicator.firstVisible(fingerprint, today.format(isoDate))) {
            updateDayLocked(today) { it.recordVisible() }
        } else {
            publishLocked(today)
        }
    }

    fun recordRejected(fingerprint: String, reasons: Collection<ReasonCode>) = synchronized(lock) {
        val today = LocalDate.now()
        rollLocked(today)
        if (deduplicator.firstRejection(fingerprint, today.format(isoDate))) {
            updateDayLocked(today) { it.recordRejected(reasons) }
        }
    }

    fun recordAcceptedConfirmed() = synchronized(lock) {
        val today = LocalDate.now()
        rollLocked(today)
        updateDayLocked(today) { it.recordAccepted() }
    }

    private fun updateDayLocked(today: LocalDate, update: (OfferStatsDay) -> OfferStatsDay) {
        requireInitialized()
        val current = daily[today] ?: OfferStatsDay(today.format(isoDate))
        daily = daily + (today to update(current))
        persistAndPublishLocked(today)
    }

    private fun rollAndPublishLocked(today: LocalDate) {
        rollLocked(today)
        publishLocked(today)
    }

    private fun rollLocked(today: LocalDate) {
        requireInitialized()
        val keepFrom = OfferStatsPeriods.retentionStart(today)
        val retained = daily.filterKeys { date -> date >= keepFrom && date <= today }
        if (retained.size != daily.size) {
            daily = retained
            persistAndPublishLocked(today)
        }
    }

    private fun loadLocked() {
        val raw = preferences?.getString(KEY_DAILY_AGGREGATES, null)
            ?.takeIf { it.length <= MAX_SERIALIZED_CHARS }
        val decoded = runCatching { raw?.let { json.decodeFromString<List<OfferStatsDay>>(it) }.orEmpty() }
            .getOrDefault(emptyList())
        daily = decoded.mapNotNull { row ->
            runCatching {
                LocalDate.parse(row.date, isoDate) to row.copy(
                    visibleOffers = row.visibleOffers.coerceAtLeast(0),
                    rejectedOffers = row.rejectedOffers.coerceAtLeast(0),
                    acceptedOffers = row.acceptedOffers.coerceAtLeast(0),
                    rejectionsByReason = row.rejectionsByReason.filterValues { it > 0 },
                )
            }.getOrNull()
        }.toMap()
        deduplicator.reset()
        rollLocked(LocalDate.now())
    }

    private fun persistAndPublishLocked(today: LocalDate) {
        val rows = daily.values.sortedBy { it.date }
            .takeLast(MAX_RETAINED_DAYS)
        daily = rows.mapNotNull { row ->
            runCatching { LocalDate.parse(row.date, isoDate) to row }.getOrNull()
        }.toMap()
        runCatching { preferences?.edit()?.putString(KEY_DAILY_AGGREGATES, json.encodeToString(rows))?.apply() }
        publishLocked(today)
    }

    private fun publishLocked(today: LocalDate) {
        val isoToday = today.format(isoDate)
        val weekStart = OfferStatsPeriods.weekStart(today)
        val monthStart = OfferStatsPeriods.monthStart(today)
        _snapshots.value = DriverStatsSnapshot(
            today = OfferStatsTotals.from(listOfNotNull(daily[today])),
            week = OfferStatsTotals.from(daily.filterKeys { it >= weekStart && it <= today }.values),
            month = OfferStatsTotals.from(daily.filterKeys { it >= monthStart && it <= today }.values),
            date = isoToday,
        )
    }

    private fun requireInitialized() {
        check(preferences != null) { "OfferStatsStore.init must be called before use" }
    }

    private fun emptySnapshot(today: LocalDate) = DriverStatsSnapshot(
        OfferStatsTotals(), OfferStatsTotals(), OfferStatsTotals(), today.format(isoDate),
    )
}
