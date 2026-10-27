package app.sabbaq.support

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class SupportSender { DRIVER, ADMIN }

/** SENDING and FAILED exist only on this device, until the server confirms the message. */
enum class SupportDelivery { SENT, SENDING, FAILED, RATE_LIMITED }

data class SupportMessage(
    val clientId: String,
    val sender: SupportSender,
    val body: String,
    val createdAtEpochMs: Long,
    val delivery: SupportDelivery = SupportDelivery.SENT,
)

/** A driver message not yet confirmed by the server; persisted so it survives a restart. */
@Serializable
data class PendingSupportMessage(
    val clientId: String,
    val body: String,
    val createdAtEpochMs: Long,
    val failed: Boolean = false,
    val rateLimited: Boolean = false,
)

@Serializable
data class SupportOutbox(
    /** [app.sabbaq.network.SupabaseClient.accountKey] of the account that wrote these. */
    val owner: String? = null,
    val pending: List<PendingSupportMessage> = emptyList(),
)

data class SupportThread(
    val closed: Boolean = false,
    val messages: List<SupportMessage> = emptyList(),
)

object SupportConversation {
    const val MAX_LENGTH = 1000
    const val MAX_PENDING = 20

    /** The text that would be sent, or null when it is empty or too long. */
    fun normalize(raw: String): String? = raw.trim().takeIf { it.length in 1..MAX_LENGTH }

    /** Rows of `support_my_thread`; malformed rows are skipped instead of failing the screen. */
    fun parseThread(json: Json, body: String): SupportThread? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val rows = root["messages"] as? JsonArray ?: JsonArray(emptyList())
        val messages = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            runCatching {
                val clientId = row["client_id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val text = row["body"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val sender = when (row["sender"]?.jsonPrimitive?.contentOrNull) {
                    "driver" -> SupportSender.DRIVER
                    "admin" -> SupportSender.ADMIN
                    else -> return@runCatching null
                }
                val created = row["created_at"]?.jsonPrimitive?.contentOrNull?.let(::parseInstant) ?: return@runCatching null
                SupportMessage(clientId, sender, text, created.toEpochMilli())
            }.getOrNull()
        }
        return SupportThread(closed = root["status"]?.jsonPrimitive?.contentOrNull == "closed", messages = messages)
    }

    /** Server messages plus local ones the server has not confirmed yet, oldest first. */
    fun timeline(thread: SupportThread, pending: List<PendingSupportMessage>): List<SupportMessage> {
        val confirmed = thread.messages.mapTo(HashSet()) { it.clientId }
        val local = pending.filter { it.clientId !in confirmed }.map {
            SupportMessage(it.clientId, SupportSender.DRIVER, it.body, it.createdAtEpochMs, when {
                it.rateLimited -> SupportDelivery.RATE_LIMITED
                it.failed -> SupportDelivery.FAILED
                else -> SupportDelivery.SENDING
            })
        }
        return (thread.messages + local).sortedBy { it.createdAtEpochMs }
    }

    /** Pending messages the server has now confirmed are dropped from the outbox. */
    fun reconcile(outbox: SupportOutbox, thread: SupportThread): SupportOutbox {
        val confirmed = thread.messages.mapTo(HashSet()) { it.clientId }
        return outbox.copy(pending = outbox.pending.filter { it.clientId !in confirmed })
    }

    private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
}
