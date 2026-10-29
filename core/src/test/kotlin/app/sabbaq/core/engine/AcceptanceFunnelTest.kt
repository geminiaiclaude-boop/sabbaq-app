package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Test

/** The diagnosis names the first step that explains why nothing was accepted. */
class AcceptanceFunnelTest {
    private var now = 10_000_000L
    private val funnel = AcceptanceFunnel { now }.apply { serviceConnected(true) }
    private fun verdict() = AcceptanceFunnel.diagnose(funnel.snapshot(), now)

    @Test
    fun service_off_comes_first() {
        funnel.serviceConnected(false)
        assertEquals("service_off", verdict().code)
    }

    @Test
    fun a_recent_gate_block_names_its_reason() {
        funnel.event(); funnel.gateBlocked("subscription_unknown")
        assertEquals(FunnelVerdict("gate_subscription_unknown", FunnelStep.GATE, "subscription_unknown"), verdict())
        now += AcceptanceFunnel.GATE_RECENT_MS + 1
        funnel.event()
        assertEquals("waiting_for_offers", verdict().code)
    }

    @Test
    fun no_events_from_the_ride_app() {
        assertEquals("no_ride_app_events", verdict().code)
        funnel.event()
        now += AcceptanceFunnel.QUIET_MS + 1
        assertEquals("no_ride_app_events", verdict().code)
    }

    @Test
    fun half_read_offers_name_the_missing_piece_and_plain_screens_do_not_count() {
        funnel.event(); repeat(30) { funnel.readMiss("not_offer_screen") }
        assertEquals("waiting_for_offers", verdict().code)
        repeat(3) { funnel.readMiss("no_minutes") }; funnel.readMiss("no_accept")
        assertEquals(FunnelVerdict("offer_not_read", FunnelStep.READ, "no_minutes"), verdict())
    }

    @Test
    fun all_rejected_names_the_main_condition() {
        funnel.event(); repeat(4) { funnel.offer() }
        repeat(3) { funnel.rejected("failed_price") }; funnel.rejected("dest_excluded")
        assertEquals(FunnelVerdict("all_rejected", FunnelStep.DECISION, "failed_price"), verdict())
    }

    @Test
    fun taps_not_sent_and_taps_not_confirmed() {
        funnel.event(); funnel.offer(); funnel.notDispatched("empty_bounds")
        assertEquals(FunnelVerdict("tap_not_sent", FunnelStep.TAP, "empty_bounds"), verdict())
        funnel.tapped(); funnel.unconfirmed("disappeared")
        assertEquals("tap_not_confirmed", verdict().code)
        funnel.confirmed()
        assertEquals("working", verdict().code)
    }

    @Test
    fun the_window_forgets_after_an_hour() {
        funnel.event(); funnel.offer(); funnel.rejected("failed_price")
        now += 61 * 60_000L
        funnel.event()
        assertEquals("waiting_for_offers", verdict().code)
        assertEquals(1, funnel.snapshot().events)
    }

    @Test
    fun readers_say_why_a_screen_gave_no_offer() {
        val half = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "25.50 ر.س", rect = row(1200)), FakeNode(text = "يبعُد 4 دقائق", rect = row(1300))))
        var miss: String? = null
        assertEquals(null, RideApps.read(RideApp.JEENY, half, 2400, EngineConfig(), null, { 0L }, onMiss = { r, _ -> miss = r }))
        assertEquals("no_accept", miss)
        RideApps.read(RideApp.JEENY, FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(FakeNode(text = "القائمة", rect = row(100)))),
            2400, EngineConfig(), null, { 0L }, onMiss = { r, _ -> miss = r })
        assertEquals("not_offer_screen", miss)
    }
}
