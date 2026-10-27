package app.sabbaq.notifications

import android.content.Context
import android.content.SharedPreferences
import app.sabbaq.network.SupabaseClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The app's notification centre: local events (accepted rides, monitoring stops, subscription
 * reminders) and messages from the administration, in one device-local inbox. Writes are
 * synchronous under one lock so the accessibility service and the UI never lose each other's
 * updates; the network sync is serialized separately and never blocks a local post.
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

    fun init(context: Context) = synchronized(lock) {
        if (prefs != null) return@synchronized
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs?.getString(KEY, null)?.takeIf { it.length <= MAX_CHARS }
        val loaded = raw?.let { runCatching { json.decodeFromString<InboxState>(it) }.getOrNull() } ?: InboxState()
        _state.value = NotificationInbox.normalize(loaded, System.currentTimeMillis())
    }

    /** Records a local event. Same [dedupeKey] within [dedupeWindowMs] is kept once. */
    fun post(
        title: String,
        body: String,
        kind: NotificationKind = NotificationKind.INFO,
        action: NotificationAction? = null,
        dedupeKey: String? = null,
        dedupeWindowMs: Long = NotificationInbox.DEFAULT_DEDUPE_WINDOW_MS,
    ) {
        val now = System.currentTimeMillis()
        val notification = AppNotification(
            id = "local:${dedupeKey ?: "event"}:$now:${(0..9999).random()}",
            title = title, body = body, kind = kind, action = action,
            createdAtEpochMs = now, dedupeKey = dedupeKey,
        )
        update { NotificationInbox.addLocal(it, notification, now, dedupeWindowMs) }
    }

    /** One entry per confirmed acceptance; fare and pickup distance when the offer showed them. */
    fun postAcceptedRide(fare: BigDecimal?, pickupKm: BigDecimal?) {
        val details = listOfNotNull(
            fare?.let { "الأجرة ${amount(it)} ر.س" },
            pickupKm?.let { "الوصول ${amount(it)} كم" },
        )
        post(
            title = "تم قبول طلب",
            body = if (details.isEmpty()) "قبل سبّاق طلبًا يطابق شروطك." else details.joinToString(" · "),
            kind = NotificationKind.SUCCESS,
            dedupeWindowMs = 0,
        )
    }

    private fun amount(value: BigDecimal): String =
        value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

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
     * Pulls the administration's messages, adds the subscription reminder when it is due and
     * sends pending read receipts. Failures keep the local inbox as it is; the next refresh
     * retries, so receipts are never lost.
     */
    suspend fun refresh(): Boolean = syncMutex.withLock {
        val snapshot = SupabaseClient.subscriptionSnapshot()
        NotificationInbox.subscriptionReminder(snapshot?.state, snapshot?.validUntil, System.currentTimeMillis())?.let { reminder ->
            update { NotificationInbox.addLocal(it, reminder, reminder.createdAtEpochMs, dedupeWindowMs = Long.MAX_VALUE) }
        }
        val fetched = SupabaseClient.fetchNotifications().getOrNull() ?: return@withLock false
        val server = NotificationInbox.parseServer(json, fetched)
        update { NotificationInbox.mergeServer(it, server, System.currentTimeMillis()) }
        val pending = _state.value.pendingReadIds
        if (pending.isNotEmpty() && SupabaseClient.markNotificationsRead(pending).isSuccess) {
            update { NotificationInbox.acknowledgeSynced(it, pending) }
        }
        true
    }

    private fun update(transform: (InboxState) -> InboxState) = synchronized(lock) {
        val next = transform(_state.value)
        if (next == _state.value) return@synchronized
        _state.value = next
        val serialized = runCatching { json.encodeToString(next) }.getOrNull() ?: return@synchronized
        if (serialized.length <= MAX_CHARS) runCatching { prefs?.edit()?.putString(KEY, serialized)?.apply() }
    }
}
