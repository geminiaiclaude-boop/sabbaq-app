package app.sabbaq.core

import org.junit.Assert.*
import org.junit.Test

class OfferTelemetryTest {
    @Test fun `reason redaction removes identifiers and keeps bounded code`() {
        val reason = OfferTelemetryContract.sanitizeReason("fare=5.50 address 12 Main Street phone +966512345678")
        assertTrue(reason!!.length <= OfferTelemetryContract.MAX_REASON_LENGTH)
        assertFalse(reason.contains("5.50"))
        assertFalse(reason.contains("966512345678"))
        assertFalse(reason.contains("Main"))
    }

    @Test fun `correlation is deterministic but does not expose identity`() {
        val a = OfferTelemetryContract.correlationId("installation-a", "user-a")
        assertEquals(a, OfferTelemetryContract.correlationId("installation-a", "user-a"))
        assertEquals(32, a.length)
        assertFalse(a.contains("installation"))
        assertFalse(a.contains("user"))
    }

    @Test fun `bounded event clamps score and reason`() {
        val event = OfferTelemetryContract.bounded(OfferTelemetryEvent(
            eventId = "id", occurredAtEpochMs = 1, correlationId = "c", appId = "app",
            appVersion = "v", androidApi = 1, provider = "p", stage = TelemetryStage.REJECTED,
            reasonCode = "BAD REASON 123456789"
        ))
        assertEquals(26, event.androidApi)
        assertTrue(event.reasonCode!!.length <= 48)
    }
}
