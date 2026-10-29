package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Field shapes that the old engine rejected or never tapped (engine notes/
 * FIELD_TELEMETRY_2026-10-28.md), read the way the reference engine reads them.
 */
class JeenyScreenReaderTest {
    private val height = 2400
    private fun reader(config: EngineConfig = EngineConfig()) = JeenyScreenReader(config) { 0L }

    /** Jeeny's offer card: «يبعد» time, a riyal-sign amount, and a clickable frame around «قبول العرض». */
    private fun jeenyCard(
        acceptClickableFrame: Boolean = true,
        price: String = "⃁ 25.50",
        extra: List<FakeNode> = emptyList(),
    ): Pair<FakeNode, FakeNode> {
        val label = FakeNode(text = "قبول العرض", viewId = "me.com.easytaxista:id/tvAccept", rect = Bounds(100, 2200, 980, 2290))
        val frame = FakeNode(className = "android.widget.FrameLayout", isClickable = acceptClickableFrame,
            viewId = "me.com.easytaxista:id/btnAccept", rect = Bounds(80, 2180, 1000, 2310), children = listOf(label))
        val root = FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "طلب جديد", rect = row(1200)),
            FakeNode(text = "يبعد 4 دقائق", viewId = "me.com.easytaxista:id/tvTime", rect = row(1400)),
            FakeNode(text = price, viewId = "me.com.easytaxista:id/tvPrice", rect = row(1500)),
        ) + extra + frame)
        return root to frame
    }

    @Test
    fun reads_a_jeeny_offer_and_returns_the_clickable_frame_as_the_live_tap_target() {
        val (root, frame) = jeenyCard()
        val ride = reader().read(root, height)
        assertNotNull(ride)
        ride!!
        assertEquals(4, ride.minutes)
        assertEquals(25.5, ride.price!!, 0.0)
        assertSame(frame, ride.acceptNode)
        assertEquals(540, ride.clickX)
    }

    /** Field session 2026-10-29: a trip screen with a fare and «عرض الاتجاهات» is a trip, not an offer. */
    @Test
    fun a_trip_screen_with_a_fare_and_directions_is_an_active_ride() {
        val directions = FakeNode(text = "عرض الاتجاهات", isClickable = true, rect = Bounds(80, 2180, 1000, 2310))
        val root = FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "⃁ 25.50", rect = row(1500)),
            FakeNode(text = "تأكيد الاستلام", isClickable = true, rect = row(1700)),
            directions,
        ))
        val ride = reader().read(root, height)!!
        assertTrue(ride.isActiveRide)
        assertNull(ride.acceptNode)
    }

    @Test
    fun an_explicit_accept_still_wins_on_a_screen_with_a_trip_phrase() {
        val (card, frame) = jeenyCard()
        val root = FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400),
            children = card.kids + FakeNode(className = "android.widget.LinearLayout", rect = row(2350),
                children = listOf(FakeNode(text = "نقطة الانطلاق", rect = row(2350)))))
        val ride = reader().read(root, height)!!
        assertFalse(ride.isActiveRide)
        assertSame(frame, ride.acceptNode)
        assertEquals(JeenyLexicon.ACCEPT.indexOf("قبول"), ride.acceptWord)
    }

    @Test
    fun accepts_a_button_that_no_ancestor_marks_clickable_by_tapping_the_text_itself() {
        val (root, _) = jeenyCard(acceptClickableFrame = false)
        val ride = reader().read(root, height)!!
        assertEquals("قبول العرض", ride.acceptNode!!.text)
    }

    @Test
    fun a_missing_destination_never_stops_the_read() {
        val (root, _) = jeenyCard()
        val ride = reader().read(root, height)!!
        assertNull(ride.destination)
        assertTrue(ride.addressAmbiguous)
    }

    @Test
    fun currency_in_a_separate_node_still_gives_the_price() {
        val (root, _) = jeenyCard(price = "18.75", extra = listOf(FakeNode(text = "ريال", rect = row(1600))))
        val ride = reader().read(root, height)!!
        assertEquals(18.75, ride.price!!, 0.0)
    }

    @Test
    fun the_home_screen_online_banner_means_no_offer() {
        val (root, _) = jeenyCard(extra = listOf(FakeNode(text = "أنت متصل", rect = row(100))))
        val reordered = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "أنت متصل", rect = row(100)), root,
        ))
        assertNull(reader().read(reordered, height))
    }

    @Test
    fun an_amount_in_the_top_thirty_percent_is_not_the_price() {
        val (root, _) = jeenyCard(price = "nothing")
        val top = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(FakeNode(text = "⃁ 99", rect = row(100)), root))
        val ride = reader(EngineConfig(priceEnabled = false)).read(top, height)!!
        assertNull(ride.price)
    }

    @Test
    fun no_time_to_customer_means_no_offer() {
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "⃁ 25", rect = row(1500)),
            FakeNode(text = "قبول العرض", isClickable = true, rect = row(2200)),
        ))
        assertNull(reader().read(root, height))
    }

    @Test
    fun an_active_ride_is_reported_when_no_accept_control_is_shown() {
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "إنهاء المشوار", rect = row(2200)),
        ))
        assertTrue(reader().read(root, height)!!.isActiveRide)
    }

    @Test
    fun a_disabled_accept_control_is_never_a_target() {
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "يبعد 3 دقائق", rect = row(1400)),
            FakeNode(text = "⃁ 20", rect = row(1500)),
            FakeNode(text = "قبول العرض", isClickable = true, isEnabled = false, rect = row(2200)),
        ))
        assertNull(reader().read(root, height))
    }

    @Test
    fun accept_drawn_as_an_icon_is_found_beside_the_reject_button() {
        val reject = FakeNode(text = "رفض", isClickable = true, rect = Bounds(60, 2200, 500, 2300))
        val icon = FakeNode(className = "android.widget.ImageView", isClickable = true, rect = Bounds(560, 2200, 1000, 2300))
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "يبعد 5 دقائق", rect = row(1400)),
            FakeNode(text = "⃁ 30", rect = row(1500)),
            FakeNode(text = "حي الملقا شمال الرياض", rect = row(1600)),
            reject, icon,
        ))
        val ride = reader(EngineConfig(destCheckEnabled = true)).read(root, height)!!
        assertSame(icon, ride.acceptNode)
    }

    @Test
    fun two_addresses_give_pickup_and_destination() {
        val (root, _) = jeenyCard(extra = listOf(
            FakeNode(text = "حي النرجس", rect = row(1650)),
            FakeNode(text = "مطار الملك خالد الدولي", rect = row(1750)),
        ))
        val ride = reader(EngineConfig(destCheckEnabled = true)).read(root, height)!!
        assertEquals("مطار الملك خالد الدولي", ride.destination)
        // The reference engine's noise list has no «طلب», so the card header is the first address it collects.
        assertEquals(listOf("طلب جديد", "حي النرجس", "مطار الملك خالد الدولي"), ride.addresses)
        assertEquals("طلب جديد", ride.pickup)
        assertFalse(ride.addressAmbiguous)
    }

    @Test
    fun an_aborted_read_before_any_offer_signal_is_marked_aborted() {
        val (root, _) = jeenyCard()
        val ride = reader().read(root, height, abort = AtomicBoolean(true))!!
        assertTrue(ride.aborted)
    }

    @Test
    fun learned_ids_read_the_offer_without_walking_the_tree() {
        val (root, frame) = jeenyCard()
        val ids = LearnedOfferIds("me.com.easytaxista:id/btnAccept", "me.com.easytaxista:id/tvPrice", "me.com.easytaxista:id/tvTime")
        // The learned accept id is the frame, whose own text is empty: the reference engine needs accept text on it.
        assertNull(reader().readByLearnedIds(root, ids))
        val byLabel = ids.copy(acceptId = "me.com.easytaxista:id/tvAccept")
        val ride = reader().readByLearnedIds(root, byLabel)!!
        assertEquals(4, ride.minutes)
        assertEquals(25.5, ride.price!!, 0.0)
        assertTrue(ride.fromFastIds)
        assertEquals("قبول العرض", ride.acceptNode!!.text)
        assertTrue(frame.isClickable)
    }

    @Test
    fun arabic_indic_digits_are_read() {
        val (root, _) = jeenyCard(price = "⃁ ٢٥.٥٠")
        val ride = reader().read(root, height)!!
        assertEquals(25.5, ride.price!!, 0.0)
    }
}
