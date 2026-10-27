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

    @Test fun `reasons the server would refuse are redacted on the device`() {
        // One refused reason makes the server reject the whole 50-event batch.
        assertEquals("redacted", OfferTelemetryContract.sanitizeReason("HttpException"))
        assertEquals("redacted", OfferTelemetryContract.sanitizeReason("tokenrefreshfailed"))
        assertEquals("illegalstateexception", OfferTelemetryContract.sanitizeReason("IllegalStateException"))
    }

    @Test fun `health flags fit the detail contract and name the first blocking issue`() {
        val healthy = DeviceHealth(true, true, true, true, true, true, true, true, false, true, false, true)
        assertEquals("healthy", healthy.primaryIssue())
        assertEquals(healthy.flags(), OfferTelemetryContract.sanitizeDetail(healthy.flags()))
        assertTrue(healthy.flags().length <= OfferTelemetryContract.MAX_DETAIL_LENGTH)
        assertEquals("a11y_disabled", healthy.copy(accessibilityEnabled = false, serviceConnected = false).primaryIssue())
        assertEquals("battery_optimized", healthy.copy(batteryUnrestricted = false).primaryIssue())
        assertEquals("target_not_installed", healthy.copy(targetInstalled = false, licenseValid = false).primaryIssue())
    }

    @Test fun `detail keeps machine flags and redacts anything else`() {
        assertEquals("a1,f0,d1,t1,p1,o0,c0,n42,cap0", OfferTelemetryContract.sanitizeDetail("a1,f0,d1,t1,p1,o0,c0,n42,cap0"))
        assertEquals("redacted", OfferTelemetryContract.sanitizeDetail("SAR 5.50 شارع الجامعة"))
        assertEquals("redacted", OfferTelemetryContract.sanitizeDetail("phone=0512345678"))
        assertNull(OfferTelemetryContract.sanitizeDetail("  "))
    }

    @Test fun `bounded event keeps aggregate count within limits`() {
        val event = OfferTelemetryContract.bounded(OfferTelemetryEvent(
            eventId = "id", occurredAtEpochMs = 1, correlationId = "c", appId = "app",
            appVersion = "v", androidApi = 30, provider = "jeeny", stage = TelemetryStage.SIGNAL_MISS,
            eventCount = 0, targetAppVersion = "5.2.1 (beta)",
        ))
        assertEquals(1, event.eventCount)
        assertEquals("5.2.1beta", event.targetAppVersion)
        assertEquals(OfferTelemetryContract.SCHEMA_VERSION, event.schemaVersion)
    }

    @Test fun `upload backoff grows and is capped`() {
        assertEquals(0L, TelemetryUploadPolicy.retryDelayMs(0))
        assertEquals(30_000L, TelemetryUploadPolicy.retryDelayMs(1))
        assertEquals(60_000L, TelemetryUploadPolicy.retryDelayMs(2))
        assertEquals(TelemetryUploadPolicy.MAX_DELAY_MS, TelemetryUploadPolicy.retryDelayMs(50))
        assertFalse(TelemetryUploadPolicy.shouldDropBatch(2))
        assertTrue(TelemetryUploadPolicy.shouldDropBatch(3))
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
