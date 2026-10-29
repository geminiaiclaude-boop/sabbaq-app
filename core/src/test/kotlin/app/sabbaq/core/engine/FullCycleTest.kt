package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole acceptance cycle per ride app, as EngineHost drives it: the screen is read by the
 * app's reader, OfferFlow decides with the driver's settings, the tap goes to the read node, and
 * the next screen resolves the pending tap (confirmed ride, offer gone, or still shown).
 */
class FullCycleTest {
    private var now = 1_000_000L
    private val flow = OfferFlow({ now })
    private val outcomes = ArrayList<PendingOutcome>()

    private fun frame(app: RideApp, root: FakeNode?, config: EngineConfig = EngineConfig()): FlowAction {
        val parsed = root?.let { RideApps.read(app, it, 2400, config, flow.learnedIds, { 0L }) }
        return flow.onFrame(parsed, config, fromRoot = true, isJeeny = app == RideApp.JEENY, parseMs = 8, frameDetectedMs = now,
            verify = { root?.let { PostAcceptVerifier.verify(it) } ?: PostAcceptOutcome.Inconclusive },
            isBolt = app == RideApp.BOLT) { outcomes += it }
    }

    private fun screen(vararg nodes: FakeNode) = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = nodes.toList())

    private fun jeenyOffer(fare: String, accept: FakeNode): FakeNode {
        var top = 900
        val lines = listOf("مشوار داخل المدينة", "يبعُد 4 دقائق", "يبعُد 1.5كم", "ستربح", fare,
            "Al-Rehab - Abdullah Bin Al-Ashhab,", "Near 3253, Awwad Al Qabidi")
            .map { FakeNode(text = it, rect = row(top).also { top += 100 }) }
        return screen(*(lines + accept).toTypedArray())
    }

    private fun jeenyAccept() = FakeNode(className = "android.widget.FrameLayout", isClickable = true, rect = Bounds(60, 2200, 1020, 2320),
        children = listOf(FakeNode(text = "قبول العرض", rect = Bounds(300, 2230, 780, 2290))))

    @Test
    fun jeeny_offer_is_tapped_then_confirmed_by_the_trip_screen() {
        val accept = jeenyAccept()
        val tap = frame(RideApp.JEENY, jeenyOffer("25.50 ر.س", accept))
        assertTrue(tap is FlowAction.Tap)
        assertSame(accept, (tap as FlowAction.Tap).ride.acceptNode)
        now += 900
        val trip = screen(FakeNode(text = "وصلت إلى نقطة الانطلاق", isClickable = true, rect = row(2100)),
            FakeNode(text = "إلغاء المشوار", isClickable = true, rect = row(2250)))
        frame(RideApp.JEENY, trip)
        assertTrue("outcome: $outcomes", outcomes.single() is PendingOutcome.Confirmed)
    }

    @Test
    fun jeeny_offer_outside_the_price_range_is_rejected_once_and_never_tapped() {
        val config = EngineConfig(minPrice = 30.0)
        val first = frame(RideApp.JEENY, jeenyOffer("25.50 ر.س", jeenyAccept()), config)
        assertTrue(first is FlowAction.Rejected && first.firstTimeForThisOffer)
        assertEquals("failed_price", (first as FlowAction.Rejected).decision.reason)
        now += 50
        val again = frame(RideApp.JEENY, jeenyOffer("25.50 ر.س", jeenyAccept()), config)
        assertTrue(again is FlowAction.Rejected && !again.firstTimeForThisOffer)
    }

    @Test
    fun the_same_offer_is_not_tapped_twice_within_the_suppression_window() {
        val accept = jeenyAccept()
        assertTrue(frame(RideApp.JEENY, jeenyOffer("25.50 ر.س", accept)) is FlowAction.Tap)
        now += 300
        val second = frame(RideApp.JEENY, jeenyOffer("25.50 ر.س", accept))
        assertTrue("second frame of the same offer: $second", second !is FlowAction.Tap)
    }

    @Test
    fun bolt_offer_is_tapped_and_the_active_trip_confirms_it() {
        val accept = FakeNode(text = "قبول", viewId = "ee.mtakso.driver:id/acceptButton", isClickable = true, rect = Bounds(60, 2150, 1020, 2300))
        val offer = screen(
            FakeNode(text = "21.84 ر.س", viewId = "ee.mtakso.driver:id/categoryPriceInfo", rect = row(1300)),
            FakeNode(text = "3 دقيقة", rect = row(1400)),
            FakeNode(text = "حي النرجس، الرياض", viewId = "ee.mtakso.driver:id/newOrderAddressText", rect = row(1500)),
            FakeNode(text = "مطار الملك خالد، الرياض", viewId = "ee.mtakso.driver:id/newOrderAddressText", rect = row(1600)),
            accept,
        )
        val tap = frame(RideApp.BOLT, offer)
        assertTrue(tap is FlowAction.Tap)
        assertEquals("bolt", (tap as FlowAction.Tap).pending.provider)
        now += 1200
        frame(RideApp.BOLT, screen(FakeNode(viewId = "ee.mtakso.driver:id/slideText", text = "اسحب للبدء", rect = row(2000))))
        assertTrue("outcome: $outcomes", outcomes.single() is PendingOutcome.Confirmed)
    }

    @Test
    fun petra_offer_is_tapped_and_the_trip_screen_confirms_it() {
        val accept = FakeNode(text = "قبول", viewId = "com.PetraRide_Captain:id/accept_button", isClickable = true, rect = Bounds(560, 2150, 1020, 2300))
        val offer = screen(
            FakeNode(text = "1.68 د.أ", viewId = "com.PetraRide_Captain:id/estimated_trip_price", rect = row(1300)),
            FakeNode(text = "4 دقائق", viewId = "com.PetraRide_Captain:id/estimated_time_to_pick_up", rect = row(1400)),
            FakeNode(text = "عمان، الصويفية", viewId = "com.PetraRide_Captain:id/drop_off_location_title_label", rect = row(1600)),
            FakeNode(text = "رفض", viewId = "com.PetraRide_Captain:id/reject_button", isClickable = true, rect = Bounds(60, 2150, 500, 2300)),
            accept,
        )
        val tap = frame(RideApp.PETRA, offer)
        assertTrue(tap is FlowAction.Tap)
        assertSame(accept, (tap as FlowAction.Tap).ride.acceptNode)
        now += 1000
        frame(RideApp.PETRA, screen(FakeNode(viewId = "com.PetraRide_Captain:id/trip_flow_button", text = "وصلت", rect = row(2100))))
        assertTrue("outcome: $outcomes", outcomes.single() is PendingOutcome.Confirmed)
    }

    @Test
    fun an_unanswered_tap_is_reported_by_the_watchdog() {
        val tap = frame(RideApp.JEENY, jeenyOffer("25.50 ر.س", jeenyAccept())) as FlowAction.Tap
        now += OfferFlow.WATCHDOG_MS
        val outcome = flow.onWatchdog(tap.pending)
        assertNotNull(outcome)
        assertTrue(outcome is PendingOutcome.Disappeared)
    }
}
