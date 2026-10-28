package app.sabbaq.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The subscription never blocks a tap through the network or a lost session (owner, 2026-10-28). */
class SnipingGateTest {
    private val now = 1_800_000_000_000L

    @Test
    fun an_unknown_end_date_allows_taps_after_a_restart() {
        // The old in-memory lease made every restart "license_invalid" until the server answered.
        assertTrue(SnipingGate.check(true, true, 0L, 0L, now).allowed)
    }

    @Test
    fun a_saved_future_end_date_allows_taps_offline() {
        assertTrue(SnipingGate.check(true, true, now + 86_400_000L, now - 1000, now).allowed)
    }

    @Test
    fun only_a_passed_end_date_stops_taps() {
        assertEquals("subscription_expired", SnipingGate.check(true, true, now - 1, 0L, now).reason)
    }

    @Test
    fun the_drivers_switch_and_consent_decide_first() {
        assertEquals("service_paused", SnipingGate.check(false, true, 0L, 0L, now).reason)
        assertEquals("disclosure_required", SnipingGate.check(true, false, 0L, 0L, now).reason)
    }

    @Test
    fun turning_the_clock_back_past_two_hours_is_refused() {
        val back = SnipingGate.check(true, true, now + 1000, now + 3 * 3_600_000L, now)
        assertFalse(back.allowed)
        assertEquals("clock_tampered", back.reason)
        assertTrue(SnipingGate.check(true, true, 0L, now + 60_000L, now).allowed)
    }
}
