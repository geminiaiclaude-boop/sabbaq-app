package app.sabbaq.notifications

import app.sabbaq.network.LicenseState
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationInboxTest {
    private val now = 1_800_000_000_000L
    private val json = Json { ignoreUnknownKeys = true }

    private fun local(id: String, at: Long = now, key: String? = null) =
        AppNotification(id = id, title = "عنوان", body = "نص", createdAtEpochMs = at, dedupeKey = key)

    private fun server(id: String, read: Boolean = false, at: Long = now) =
        AppNotification(id = id, title = "من الإدارة", body = "نص", createdAtEpochMs = at, read = read, fromServer = true)

    @Test
    fun `same dedupe key inside the window is kept once`() {
        val first = NotificationInbox.addLocal(InboxState(), local("local:a:1", key = "stop"), now)
        val second = NotificationInbox.addLocal(first, local("local:a:2", at = now + 1000, key = "stop"), now + 1000)
        val later = NotificationInbox.addLocal(second, local("local:a:3", at = now + 11 * 60_000, key = "stop"), now + 11 * 60_000)
        assertEquals(1, second.items.size)
        assertEquals(2, later.items.size)
    }

    @Test
    fun `server list is authoritative and keeps read state from this device`() {
        val start = NotificationInbox.mergeServer(InboxState(), listOf(server("s1"), server("s2")), now)
        val read = NotificationInbox.markRead(start, listOf("s1"))
        assertEquals(setOf("s1"), read.pendingReadIds)
        // The admin deleted s2; s1 comes back unread from a server that has not seen the receipt yet.
        val merged = NotificationInbox.mergeServer(read, listOf(server("s1", read = false)), now)
        assertEquals(listOf("s1"), merged.items.map { it.id })
        assertTrue(merged.items.single().read)
        assertEquals(0, NotificationInbox.unread(merged))
    }

    @Test
    fun `receipts are dropped only after the server acknowledges them`() {
        val state = NotificationInbox.markRead(NotificationInbox.mergeServer(InboxState(), listOf(server("s1")), now), listOf("s1"))
        assertEquals(setOf("s1"), state.pendingReadIds)
        assertTrue(NotificationInbox.acknowledgeSynced(state, setOf("s1")).pendingReadIds.isEmpty())
    }

    @Test
    fun `dismissed items do not return on the next sync`() {
        val merged = NotificationInbox.mergeServer(InboxState(), listOf(server("s1")), now)
        val dismissed = NotificationInbox.dismiss(merged, "s1")
        val again = NotificationInbox.mergeServer(dismissed, listOf(server("s1")), now)
        assertTrue(again.items.isEmpty())
        val reminder = NotificationRules.endingSoon(LicenseState.TRIAL, Instant.ofEpochMilli(now + 3_600_000), now)!!
        val withReminder = NotificationInbox.addLocal(again, reminder, now)
        val noReminder = NotificationInbox.dismiss(withReminder, reminder.id)
        assertTrue(NotificationInbox.addLocal(noReminder, reminder, now + 60_000, Long.MAX_VALUE).items.isEmpty())
    }

    @Test
    fun `local events survive a server merge and old items expire`() {
        val withLocal = NotificationInbox.addLocal(InboxState(), local("local:ride:1"), now)
        val merged = NotificationInbox.mergeServer(withLocal, listOf(server("s1")), now)
        assertEquals(setOf("local:ride:1", "s1"), merged.items.map { it.id }.toSet())
        val aged = NotificationInbox.normalize(merged, now + NotificationInbox.RETENTION_MS + 1)
        assertTrue(aged.items.isEmpty())
    }

    @Test
    fun `inbox is bounded and newest first`() {
        var state = InboxState()
        repeat(NotificationInbox.MAX_ITEMS + 20) { i -> state = NotificationInbox.addLocal(state, local("local:n:$i", at = now + i), now + i) }
        assertEquals(NotificationInbox.MAX_ITEMS, state.items.size)
        assertEquals("local:n:${NotificationInbox.MAX_ITEMS + 19}", state.items.first().id)
    }

    @Test
    fun `server rows parse tolerantly`() {
        val body = """[
            {"id":"a1","title":"تحديث متاح","body":"نسخة جديدة","kind":"update","action":"update_app","created_at":"2026-10-27T08:00:00.123456+00:00","read":false},
            {"id":"a2","title":"","body":"بلا عنوان","created_at":"2026-10-27T08:00:00Z"},
            {"id":"a3","title":"إجراء غير معروف","body":"نص","kind":"shout","action":"open_url","created_at":"2026-10-27T08:00:00Z","read":true},
            "not an object"
        ]"""
        val parsed = NotificationInbox.parseServer(json, body)
        assertEquals(listOf("a1", "a3"), parsed.map { it.id })
        assertEquals(NotificationKind.UPDATE, parsed[0].kind)
        assertEquals(NotificationAction.UPDATE_APP, parsed[0].action)
        assertEquals(NotificationKind.INFO, parsed[1].kind)
        assertNull(parsed[1].action)
        assertTrue(parsed[1].read)
        assertTrue(NotificationInbox.parseServer(json, "{oops").isEmpty())
    }
}
