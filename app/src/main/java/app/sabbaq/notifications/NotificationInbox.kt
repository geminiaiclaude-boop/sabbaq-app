package app.sabbaq.notifications

import app.sabbaq.network.LicenseState
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class NotificationKind { INFO, WARNING, SUCCESS, ACCOUNT, UPDATE }

/** In-app destinations a notification can lead to; the wire names match the server's check list. */
@Serializable
enum class NotificationAction(val wire: String) {
    OPEN_ACCOUNT("open_account"),
    OPEN_SETUP("open_setup"),
    OPEN_FILTERS("open_filters"),
    UPDATE_APP("update_app");

    companion object {
        fun fromWire(value: String?): NotificationAction? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class AppNotification(
    val id: String,
    val title: String,
    val body: String,
    val kind: NotificationKind = NotificationKind.INFO,
    val action: NotificationAction? = null,
    val createdAtEpochMs: Long,
    val read: Boolean = false,
    val fromServer: Boolean = false,
    /** Local events with the same key inside the dedupe window are one notification. */
    val dedupeKey: String? = null,
)

/**
 * Everything the inbox persists. [pendingReadIds] are server notifications read on this device
 * whose read receipt has not reached the server yet; [dismissedIds] keeps items the driver
 * removed (server messages and fixed-id reminders) from coming back on the next sync.
 */
@Serializable
data class InboxState(
    val items: List<AppNotification> = emptyList(),
    val pendingReadIds: Set<String> = emptySet(),
    val dismissedIds: Set<String> = emptySet(),
)

/** Pure inbox rules; [NotificationCenter] owns persistence, threading and the network. */
object NotificationInbox {
    const val MAX_ITEMS = 100
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    const val DEFAULT_DEDUPE_WINDOW_MS = 10L * 60 * 1000
    private const val ENDING_SOON_MS = 2L * 24 * 60 * 60 * 1000
    private const val MAX_DISMISSED_LOCAL = 50
    private const val LOCAL_PREFIX = "local:"

    fun unread(state: InboxState): Int = state.items.count { !it.read }

    fun addLocal(state: InboxState, notification: AppNotification, now: Long, dedupeWindowMs: Long = DEFAULT_DEDUPE_WINDOW_MS): InboxState {
        if (notification.id in state.dismissedIds || state.items.any { it.id == notification.id }) return state
        val key = notification.dedupeKey
        if (key != null && state.items.any { it.dedupeKey == key && now - it.createdAtEpochMs in 0 until dedupeWindowMs }) return state
        return normalize(state.copy(items = listOf(notification.copy(fromServer = false)) + state.items), now)
    }

    /**
     * The server list is authoritative for which server notifications exist (an item the admin
     * deleted disappears); read state is the union of the server's, this device's and receipts
     * still waiting to be sent.
     */
    fun mergeServer(state: InboxState, server: List<AppNotification>, now: Long): InboxState {
        val readHere = state.items.filter { it.fromServer && it.read }.mapTo(HashSet()) { it.id }
        val incoming = server
            .filter { it.id !in state.dismissedIds }
            .map { it.copy(fromServer = true, read = it.read || it.id in readHere || it.id in state.pendingReadIds) }
        val serverIds = server.mapTo(HashSet()) { it.id }
        return normalize(state.copy(
            items = state.items.filterNot { it.fromServer } + incoming,
            pendingReadIds = state.pendingReadIds.filterTo(HashSet()) { it in serverIds },
            dismissedIds = keepDismissed(state.dismissedIds, serverIds),
        ), now)
    }

    fun markRead(state: InboxState, ids: Collection<String>): InboxState {
        val target = ids.toHashSet()
        val newlyRead = state.items.filter { it.id in target && !it.read }
        if (newlyRead.isEmpty()) return state
        return state.copy(
            items = state.items.map { if (it.id in target) it.copy(read = true) else it },
            pendingReadIds = state.pendingReadIds + newlyRead.filter { it.fromServer }.map { it.id },
        )
    }

    fun markAllRead(state: InboxState): InboxState = markRead(state, state.items.map { it.id })

    fun dismiss(state: InboxState, id: String): InboxState {
        val item = state.items.firstOrNull { it.id == id } ?: return state
        val read = markRead(state, listOf(id))
        return read.copy(
            items = read.items.filterNot { it.id == id },
            dismissedIds = read.dismissedIds + id,
        )
    }

    fun clearRead(state: InboxState): InboxState {
        val gone = state.items.filter { it.read }
        return state.copy(
            items = state.items.filterNot { it.read },
            dismissedIds = state.dismissedIds + gone.map { it.id },
        )
    }

    /** Server ids stay only while the server still lists them; local ids keep the newest few. */
    private fun keepDismissed(ids: Set<String>, serverIds: Set<String>): Set<String> {
        val (local, server) = ids.partition { it.startsWith(LOCAL_PREFIX) }
        return server.filterTo(LinkedHashSet()) { it in serverIds } + local.takeLast(MAX_DISMISSED_LOCAL)
    }

    fun acknowledgeSynced(state: InboxState, ids: Collection<String>): InboxState =
        state.copy(pendingReadIds = state.pendingReadIds - ids.toSet())

    fun normalize(state: InboxState, now: Long): InboxState = state.copy(
        items = state.items
            .filter { now - it.createdAtEpochMs < RETENTION_MS }
            .distinctBy { it.id }
            .sortedByDescending { it.createdAtEpochMs }
            .take(MAX_ITEMS),
    )

    /** Rows of `my_notifications`; a malformed row is skipped instead of failing the whole sync. */
    fun parseServer(json: Json, body: String): List<AppNotification> {
        val rows = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            runCatching {
                val id = row["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val title = row["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@runCatching null
                val body = row["body"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@runCatching null
                val created = row["created_at"]?.jsonPrimitive?.contentOrNull?.let(::parseInstant) ?: return@runCatching null
                AppNotification(
                    id = id,
                    title = title.take(80),
                    body = body.take(500),
                    kind = kindFromWire(row["kind"]?.jsonPrimitive?.contentOrNull),
                    action = NotificationAction.fromWire(row["action"]?.jsonPrimitive?.contentOrNull),
                    createdAtEpochMs = created.toEpochMilli(),
                    read = row["read"]?.jsonPrimitive?.booleanOrNull == true,
                    fromServer = true,
                )
            }.getOrNull()
        }
    }

    /** A reminder while the trial or subscription has two days or less left; one per end date. */
    fun subscriptionReminder(state: LicenseState?, validUntil: Instant?, now: Long): AppNotification? {
        if (state == null || validUntil == null || !state.isValidState) return null
        val left = validUntil.toEpochMilli() - now
        if (left <= 0 || left > ENDING_SOON_MS) return null
        val trial = state == LicenseState.TRIAL
        val day = validUntil.atOffset(ZoneOffset.UTC).toLocalDate()
        return AppNotification(
            id = "local:sub-ending:$day",
            title = if (trial) "تنتهي تجربتك قريبًا" else "ينتهي اشتراكك قريبًا",
            body = if (trial) "اشترك قبل انتهاء التجربة حتى لا تتوقف متابعة الطلبات."
                else "جدّد اشتراكك قبل انتهائه حتى لا تتوقف متابعة الطلبات.",
            kind = NotificationKind.ACCOUNT,
            action = NotificationAction.OPEN_ACCOUNT,
            createdAtEpochMs = now,
            dedupeKey = "sub-ending:$day",
        )
    }

    private fun kindFromWire(value: String?): NotificationKind = when (value) {
        "warning" -> NotificationKind.WARNING
        "success" -> NotificationKind.SUCCESS
        "account" -> NotificationKind.ACCOUNT
        "update" -> NotificationKind.UPDATE
        else -> NotificationKind.INFO
    }

    private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
}
