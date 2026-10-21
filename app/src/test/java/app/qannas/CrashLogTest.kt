package app.qannas

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test
    fun `report keeps types, frames and causes but never exception messages`() {
        val secret = "0512345678 token=abc"
        val error = IllegalStateException("outer $secret", IllegalArgumentException("inner $secret"))
        val report = CrashLog.format(error, Instant.parse("2026-10-21T00:00:00Z"), "1.3.2", 6)
        assertTrue(report.startsWith("Qannas 1.3.2 (6)"))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("caused by: java.lang.IllegalArgumentException"))
        assertTrue(report.contains("  at "))
        assertFalse(report.contains(secret))
        assertFalse(report.contains("token"))
    }

    @Test
    fun `self-referencing cause chain terminates`() {
        val looping = object : RuntimeException() {
            override val cause: Throwable get() = this
        }
        val report = CrashLog.format(looping, Instant.EPOCH, "x", 1)
        assertTrue(report.lines().count { it.startsWith("caused by") } == 0)
    }
}
