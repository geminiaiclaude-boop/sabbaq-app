package app.sabbaq.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reference engine turbo (reference engine notes): instant blind tap only without filters. */
class TurboTest {
    private var now = 50_000L
    private val flow = OfferFlow({ now })
    private val turbo = EngineConfig(turboModeEnabled = true, clickX = 540, clickY = 2200, priceEnabled = false, timeEnabled = false)

    @Test
    fun instant_turbo_needs_saved_point_and_no_active_filter() {
        assertTrue(turbo.instantTurbo)
        assertFalse("a price check with a bound blocks it", turbo.copy(priceEnabled = true, minPrice = 10.0).instantTurbo)
        assertTrue("a price check without a bound checks nothing (rule 5)", turbo.copy(priceEnabled = true).instantTurbo)
        assertFalse(turbo.copy(clickX = null).instantTurbo)
        assertFalse(turbo.copy(snipingNoDest = false).instantTurbo)
    }

    @Test
    fun the_signal_taps_the_saved_point_once_per_100_ms_and_only_when_nothing_is_pending() {
        val first = flow.onTurboSignal(turbo, now)
        assertNotNull(first)
        assertEquals(540, first!!.tapX)
        assertNull("a tap is pending", flow.onTurboSignal(turbo, now))
        now += OfferFlow.WATCHDOG_MS
        flow.onWatchdog(first)
        now += 50
        assertNotNull(flow.onTurboSignal(turbo, now))
    }

    @Test
    fun blind_coordinates_let_a_decided_offer_be_tapped_without_an_accept_node() {
        val ride = ParsedRide(price = 30.0, minutes = 3, addressAmbiguous = true, clickX = 0, clickY = 0)
        val withFilters = turbo.copy(priceEnabled = true, minPrice = 10.0)
        val action = flow.onFrame(ride, withFilters, true, false, 5, now, { PostAcceptOutcome.Inconclusive }) {}
        assertTrue(action is FlowAction.Tap)
        assertEquals(2200, (action as FlowAction.Tap).pending.tapY)
    }
}
