package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reference engine's per-frame timing rules (reference engine notes). */
class OfferFlowTest {
    private var now = 10_000L
    private val flow = OfferFlow({ now })
    private val outcomes = ArrayList<PendingOutcome>()

    private fun offer(price: Double = 20.0, x: Int = 540) = ParsedRide(
        price = price, minutes = 4, acceptNode = FakeNode(text = "قبول", rect = Bounds(0, 0, 10, 10)),
        addressAmbiguous = true, clickX = x, clickY = 2200,
        acceptViewId = "a", priceViewId = "p", timeViewId = "t",
    )

    private fun frame(ride: ParsedRide?, verify: PostAcceptOutcome = PostAcceptOutcome.Inconclusive, cfg: EngineConfig = EngineConfig()) =
        flow.onFrame(ride, cfg, fromRoot = true, isJeeny = true, parseMs = 10, frameDetectedMs = now, verify = { verify }) { outcomes += it }

    @Test
    fun a_matching_offer_is_tapped_once_then_suppressed() {
        assertTrue(frame(offer()) is FlowAction.Tap)
        now += 50
        assertEquals(FlowAction.Idle("post_click_suppressed"), frame(offer()))
    }

    @Test
    fun the_trip_screen_after_the_tap_confirms_it() {
        frame(offer())
        now += 800
        frame(null, PostAcceptOutcome.Confirmed(null))
        assertTrue(outcomes.single() is PendingOutcome.Confirmed)
        assertNull(flow.pending)
    }

    @Test
    fun a_different_offer_while_waiting_is_reported_and_decided() {
        frame(offer(price = 20.0))
        now += 500
        val next = frame(offer(price = 33.0), PostAcceptOutcome.StillOffer)
        assertTrue(outcomes.single() is PendingOutcome.NewOfferAppeared)
        assertTrue(next is FlowAction.Tap)
    }

    @Test
    fun a_vanished_offer_is_resolved_by_the_watchdog() {
        val tap = frame(offer()) as FlowAction.Tap
        now += OfferFlow.WATCHDOG_MS
        assertTrue(flow.onWatchdog(tap.pending) is PendingOutcome.Disappeared)
    }

    @Test
    fun a_rejected_offer_is_reported_once() {
        val cfg = EngineConfig(minPrice = 50.0)
        val first = frame(offer(), cfg = cfg) as FlowAction.Rejected
        now += 300
        val again = frame(offer(), cfg = cfg) as FlowAction.Rejected
        assertTrue(first.firstTimeForThisOffer)
        assertTrue(!again.firstTimeForThisOffer)
    }

    @Test
    fun view_ids_are_learned_and_forgotten_after_three_misses() {
        frame(offer())
        assertEquals(LearnedOfferIds("a", "p", "t"), flow.learnedIds)
        repeat(3) { now += 7000; frame(null) }
        assertNull(flow.learnedIds)
    }

    @Test
    fun throttle_adapts_between_25_and_60_ms() {
        repeat(5) { flow.onFrame(null, EngineConfig(), true, true, 100, now, { PostAcceptOutcome.Inconclusive }) {} }
        assertEquals(60L, flow.eventThrottleMs)
    }

    @Test
    fun smart_delay_stays_inside_the_driver_range() {
        val cfg = EngineConfig(smartDelayEnabled = true, smartDelayMinMs = 200, smartDelayMaxMs = 600)
        val tap = frame(offer(), cfg = cfg) as FlowAction.Tap
        assertTrue(tap.pending.delayMs in 200..600)
    }
}
