package app.sabbaq.support

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportConversationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `message text is trimmed and bounded`() {
        assertEquals("مرحبا", SupportConversation.normalize("  مرحبا \n"))
        assertNull(SupportConversation.normalize("   "))
        assertNull(SupportConversation.normalize("a".repeat(SupportConversation.MAX_LENGTH + 1)))
        assertEquals(SupportConversation.MAX_LENGTH, SupportConversation.normalize("a".repeat(SupportConversation.MAX_LENGTH))!!.length)
    }

    @Test
    fun `thread parses tolerantly`() {
        val body = """{"status":"closed","driver_unread":0,"messages":[
            {"id":"1","client_id":"c1","sender":"driver","body":"لا يقبل","created_at":"2026-10-27T08:00:00+00:00"},
            {"id":"2","client_id":"c2","sender":"admin","body":"فعّل الإذن","created_at":"2026-10-27T08:05:00.5+00:00"},
            {"id":"3","client_id":"c3","sender":"bot","body":"x","created_at":"2026-10-27T08:06:00Z"},
            {"id":"4","client_id":"c4","sender":"admin","body":"","created_at":"2026-10-27T08:07:00Z"}
        ]}"""
        val thread = SupportConversation.parseThread(json, body)!!
        assertTrue(thread.closed)
        assertEquals(listOf("c1", "c2"), thread.messages.map { it.clientId })
        assertEquals(SupportSender.ADMIN, thread.messages[1].sender)
        assertNull(SupportConversation.parseThread(json, "not json"))
    }

    @Test
    fun `pending messages appear until the server confirms them`() {
        val thread = SupportThread(messages = listOf(SupportMessage("c1", SupportSender.DRIVER, "أولى", 1_000)))
        val pending = listOf(
            PendingSupportMessage("c1", "أولى", 1_000),
            PendingSupportMessage("c2", "ثانية", 2_000),
            PendingSupportMessage("c3", "ثالثة", 3_000, failed = true),
            PendingSupportMessage("c4", "رابعة", 4_000, rateLimited = true),
        )
        val timeline = SupportConversation.timeline(thread, pending)
        assertEquals(listOf("c1", "c2", "c3", "c4"), timeline.map { it.clientId })
        assertEquals(
            listOf(SupportDelivery.SENT, SupportDelivery.SENDING, SupportDelivery.FAILED, SupportDelivery.RATE_LIMITED),
            timeline.map { it.delivery },
        )
        val reconciled = SupportConversation.reconcile(SupportOutbox(owner = "k", pending = pending), thread)
        assertEquals(listOf("c2", "c3", "c4"), reconciled.pending.map { it.clientId })
        assertEquals("k", reconciled.owner)
    }
}
