package app.sabbaq.support

import android.content.Context
import android.content.SharedPreferences
import app.sabbaq.BuildConfig
import app.sabbaq.network.SupabaseClient
import app.sabbaq.telemetry.TelemetryRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SupportUiState(
    val messages: List<SupportMessage> = emptyList(),
    val closed: Boolean = false,
    val unread: Int = 0,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val failure: SupabaseClient.FeatureFailure? = null,
)

/**
 * The driver's conversation with the administration. Outgoing messages go to a persisted outbox
 * first, so nothing typed is lost to a dead network or a killed process; each carries a client id
 * the server deduplicates on, so retries are always safe. Like the notification inbox, the outbox
 * belongs to one account and is dropped when another account signs in.
 */
object SupportRepository {
    private const val PREFS = "sabbaq_support"
    private const val KEY = "outbox_v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val syncMutex = Mutex()
    private var prefs: SharedPreferences? = null
    private var outbox = SupportOutbox()
    private var thread = SupportThread()
    private val _state = MutableStateFlow(SupportUiState())
    val state: StateFlow<SupportUiState> = _state.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (prefs != null) return@synchronized
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        outbox = prefs?.getString(KEY, null)?.let { runCatching { json.decodeFromString<SupportOutbox>(it) }.getOrNull() } ?: SupportOutbox()
    }

    /** Loads the conversation (which marks replies read) and sends anything still waiting. */
    suspend fun refresh(): Boolean = syncMutex.withLock {
        val key = SupabaseClient.accountKey() ?: return@withLock false
        synchronized(lock) {
            bindOwner(key)
            _state.value = _state.value.copy(loading = true, loadFailed = false)
        }
        val response = SupabaseClient.fetchSupportThread()
        val parsed = response.getOrNull()?.let { SupportConversation.parseThread(json, it) }
        val failure = if (parsed != null) null
            else if (response.isFailure) SupabaseClient.featureFailureOf(response.exceptionOrNull())
            else SupabaseClient.FeatureFailure.UNAVAILABLE
        synchronized(lock) {
            if (parsed != null && SupabaseClient.accountKey() == key) {
                thread = parsed
                outbox = SupportConversation.reconcile(outbox, parsed)
                persist()
            }
            publish(loading = false, loadFailed = parsed == null, unread = if (parsed != null) 0 else _state.value.unread, failure = failure)
        }
        flushLocked(key)
        parsed != null
    }

    /** Badge count only; leaves the replies unread until the conversation is opened. */
    suspend fun refreshUnread() {
        val key = SupabaseClient.accountKey() ?: return
        val unread = SupabaseClient.fetchSupportUnread().getOrNull() ?: return
        synchronized(lock) {
            if (outbox.owner != key) bindOwner(key)
            _state.value = _state.value.copy(unread = unread)
        }
    }

    /** Queues [raw] and tries to send it now; false when the text is empty, too long or the queue is full. */
    suspend fun send(raw: String): Boolean {
        val text = SupportConversation.normalize(raw) ?: return false
        val key = SupabaseClient.accountKey() ?: return false
        synchronized(lock) {
            bindOwner(key)
            if (outbox.pending.size >= SupportConversation.MAX_PENDING) return false
            outbox = outbox.copy(pending = outbox.pending + PendingSupportMessage(UUID.randomUUID().toString(), text, System.currentTimeMillis()))
            persist()
            publish()
        }
        syncMutex.withLock { flushLocked(key) }
        // Picks up the automatic acknowledgement the server adds to a new conversation.
        refresh()
        return true
    }

    suspend fun retry(clientId: String) {
        val key = SupabaseClient.accountKey() ?: return
        synchronized(lock) {
            outbox = outbox.copy(pending = outbox.pending.map {
                if (it.clientId == clientId) it.copy(failed = false, rateLimited = false) else it
            })
            persist()
            publish()
        }
        syncMutex.withLock { flushLocked(key) }
    }

    fun discard(clientId: String) = synchronized(lock) {
        outbox = outbox.copy(pending = outbox.pending.filterNot { it.clientId == clientId })
        persist()
        publish()
    }

    /** Wiped with the account or on sign-out. */
    fun clear() = synchronized(lock) {
        outbox = SupportOutbox()
        thread = SupportThread()
        _state.value = SupportUiState()
        runCatching { prefs?.edit()?.remove(KEY)?.apply() }
    }

    /** Sends waiting messages in order; must run under [syncMutex]. Stops at the first fault. */
    private suspend fun flushLocked(key: String) {
        while (true) {
            val next = synchronized(lock) {
                if (outbox.owner != key) null else outbox.pending.firstOrNull { !it.failed && !it.rateLimited }
            } ?: break
            val result = SupabaseClient.sendSupportMessage(
                clientId = next.clientId,
                body = next.body,
                appVersion = BuildConfig.VERSION_NAME.takeIf { it.matches(Regex("^[A-Za-z0-9._-]{1,32}$")) },
                correlationId = TelemetryRepository.currentCorrelationId(),
            )
            synchronized(lock) {
                if (outbox.pending.any { it.clientId == next.clientId }) {
                    outbox = when (result) {
                        SupabaseClient.SupportSendResult.SENT -> {
                            thread = thread.copy(closed = false, messages = thread.messages +
                                SupportMessage(next.clientId, SupportSender.DRIVER, next.body, next.createdAtEpochMs))
                            outbox.copy(pending = outbox.pending.filterNot { it.clientId == next.clientId })
                        }
                        SupabaseClient.SupportSendResult.RATE_LIMITED -> outbox.markPending(next.clientId) { it.copy(rateLimited = true) }
                        SupabaseClient.SupportSendResult.REJECTED,
                        SupabaseClient.SupportSendResult.RETRY -> outbox.markPending(next.clientId) { it.copy(failed = true) }
                    }
                    persist()
                    publish()
                }
            }
            if (result != SupabaseClient.SupportSendResult.SENT) break
        }
    }

    private fun SupportOutbox.markPending(clientId: String, change: (PendingSupportMessage) -> PendingSupportMessage) =
        copy(pending = pending.map { if (it.clientId == clientId) change(it) else it })

    private fun bindOwner(key: String) {
        if (outbox.owner == key) return
        outbox = SupportOutbox(owner = key)
        thread = SupportThread()
        _state.value = SupportUiState()
        persist()
    }

    private fun publish(
        loading: Boolean = _state.value.loading,
        loadFailed: Boolean = _state.value.loadFailed,
        unread: Int = _state.value.unread,
        failure: SupabaseClient.FeatureFailure? = _state.value.failure,
    ) {
        _state.value = SupportUiState(
            messages = SupportConversation.timeline(thread, outbox.pending),
            closed = thread.closed,
            unread = unread,
            loaded = _state.value.loaded || (!loading && !loadFailed),
            loading = loading,
            loadFailed = loadFailed,
            failure = if (loadFailed) failure else null,
        )
    }

    private fun persist() {
        val serialized = runCatching { json.encodeToString(outbox) }.getOrNull() ?: return
        runCatching { prefs?.edit()?.putString(KEY, serialized)?.apply() }
    }
}
