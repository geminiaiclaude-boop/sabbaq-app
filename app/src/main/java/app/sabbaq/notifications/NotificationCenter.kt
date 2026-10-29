package app.sabbaq.notifications

import android.content.Context
import android.content.SharedPreferences
import app.sabbaq.network.SupabaseClient
import app.sabbaq.stats.OfferStatsStore
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The app's notification centre: local events and messages from the administration in one
 * device-local inbox that belongs to one account. Whenever a different account is signed in
 * (however the previous session ended) the inbox is emptied before anything is shown or added.
 * Writes are synchronous under one lock so the accessibility service and the UI never lose each
 * other's updates; the network sync is serialized separately and never blocks a local post.
 */
object NotificationCenter {
    private const val PREFS = "sabbaq_notifications"
    private const val KEY = "inbox_v1"
    private const val MAX_CHARS = 200_000
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val syncMutex = Mutex()
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(InboxState())
    val state: StateFlow<InboxState> = _state.asStateFlow()
    private val _lastFailure = MutableStateFlow<SupabaseClient.FeatureFailure?>(null)
    /** Why the last server sync failed, or null after a successful one. */
    val lastFailure: StateFlow<SupabaseClient.FeatureFailure?> = _lastFailure.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (prefs != null) return@synchronized
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs?.getString(KEY, null)?.takeIf { it.length <= MAX_CHARS }
        val loaded = raw?.let { runCatching { json.decodeFromString<InboxState>(it) }.getOrNull() } ?: InboxState()
        _state.value = NotificationInbox.normalize(loaded, System.currentTimeMillis())
    }

    /** True only when [state] belongs to the account signed in now; the UI shows nothing otherwise. */
    fun belongsToCurrentAccount(inbox: InboxState): Boolean {
        val key = SupabaseClient.accountKey() ?: return false
        return inbox.owner == key
    }

    /** Records a local event for the signed-in account; dropped when no account is signed in. */
    fun post(notification: AppNotification, dedupeWindowMs: Long = NotificationInbox.DEFAULT_DEDUPE_WINDOW_MS) {
        val key = SupabaseClient.accountKey() ?: return
        update { NotificationInbox.addLocal(owned(it, key), notification, notification.createdAtEpochMs, dedupeWindowMs) }
    }

    fun postAcceptedRide(fare: BigDecimal?, pickupKm: BigDecimal?) =
        post(NotificationTemplates.acceptedRide(fare, pickupKm, System.currentTimeMillis()), dedupeWindowMs = 0)

    fun markRead(ids: Collection<String>) = update { NotificationInbox.markRead(it, ids) }

    fun markAllRead() = update { NotificationInbox.markAllRead(it) }

    fun dismiss(id: String) = update { NotificationInbox.dismiss(it, id) }

    fun clearRead() = update { NotificationInbox.clearRead(it) }

    /** Wiped with the account or on sign-out so the next driver on this phone starts clean. */
    fun clear() = synchronized(lock) {
        _state.value = InboxState()
        runCatching { prefs?.edit()?.remove(KEY)?.apply() }
    }

    /**
     * Applies the automatic account rules (welcome, trial, subscription changes, update needed),
     * the daily summary, then pulls the administration's messages and sends pending read
     * receipts. Failures keep the local inbox as it is; the next refresh retries, so receipts are
     * never lost.
     */
    suspend fun refresh(): Boolean = syncMutex.withLock {
        val key = SupabaseClient.accountKey() ?: return@withLock false
        val now = System.currentTimeMillis()
        val snapshot = SupabaseClient.subscriptionSnapshot()
        val facts = AccountFacts(
            state = snapshot?.state,
            validUntil = snapshot?.validUntil,
            trialUntil = snapshot?.trialUntil,
            proUntil = snapshot?.proUntil,
            updateRequired = SupabaseClient.appUpdateRequired || app.sabbaq.fleet.FleetRepository.updateRequired(),
            updateAvailableCode = app.sabbaq.fleet.FleetRepository.release.value
                .takeIf { it.state(app.sabbaq.BuildConfig.VERSION_CODE.toLong()) == app.sabbaq.core.UpdateState.AVAILABLE }?.latestCode ?: 0L,
        )
        val today = LocalDate.now()
        val yesterday = runCatching { OfferStatsStore.totalsFor(today.minusDays(1)) }.getOrNull()
        update { current ->
            var inbox = owned(current, key)
            val (changes, mark) = NotificationRules.accountChanges(inbox.account, facts, now)
            val (summary, summaryMark) = NotificationRules.dailySummary(mark, today, yesterday?.first ?: 0, yesterday?.second ?: 0, now)
            (changes + listOfNotNull(summary)).forEach { inbox = NotificationInbox.addLocal(inbox, it, now, Long.MAX_VALUE) }
            inbox.copy(account = summaryMark)
        }
        val result = SupabaseClient.fetchNotifications()
        val fetched = result.getOrNull() ?: run {
            _lastFailure.value = SupabaseClient.featureFailureOf(result.exceptionOrNull())
            return@withLock false
        }
        _lastFailure.value = null
        if (SupabaseClient.accountKey() != key) return@withLock false
        val server = NotificationInbox.parseServer(json, fetched)
        update { NotificationInbox.mergeServer(owned(it, key), server, System.currentTimeMillis()) }
        val pending = _state.value.pendingReadIds
        if (pending.isNotEmpty() && SupabaseClient.markNotificationsRead(pending).isSuccess) {
            update { NotificationInbox.acknowledgeSynced(it, pending) }
        }
        true
    }

    /** The inbox of [key]: unchanged when it already belongs to it, a fresh one otherwise. */
    private fun owned(inbox: InboxState, key: String): InboxState =
        if (inbox.owner == key) inbox else InboxState(owner = key)

    private fun update(transform: (InboxState) -> InboxState) = synchronized(lock) {
        val next = transform(_state.value)
        if (next == _state.value) return@synchronized
        _state.value = next
        val serialized = runCatching { json.encodeToString(next) }.getOrNull() ?: return@synchronized
        if (serialized.length <= MAX_CHARS) runCatching { prefs?.edit()?.putString(KEY, serialized)?.apply() }
    }
}
