package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Jeeny offer card as it appears on a driver's phone in Saudi Arabia (October 2026): pickup
 * time and distance, net earnings under «ستربح» with the riyal sign (as a character or an icon),
 * two addresses, and «قبول العرض» inside a clickable frame. The old engine read it and never
 * tapped; FareSniper's reader must produce a tappable offer the default settings accept.
 */
class JeenyFieldCardTest {
    private var top = 900
    private fun line(text: String? = null, desc: String? = null, clickable: Boolean = false, children: List<FakeNode> = emptyList()) =
        FakeNode(text = text, description = desc, isClickable = clickable, rect = row(top).also { top += 100 }, children = children)

    private fun card(vararg fare: FakeNode, mapMarkers: Boolean = false): Pair<FakeNode, FakeNode> {
        val accept = FakeNode(className = "android.widget.FrameLayout", isClickable = true, rect = Bounds(60, 2200, 1020, 2320),
            children = listOf(FakeNode(text = "قبول العرض", rect = Bounds(300, 2230, 780, 2290))))
        val nodes = buildList {
            if (mapMarkers) { add(FakeNode(text = "4 دقائق", rect = row(300))); add(FakeNode(text = "2 دقائق", rect = row(400))) }
            add(line(desc = "إغلاق", clickable = true))
            add(line("مشوار داخل المدينة"))
            add(line("يبعُد 4 دقائق"))
            add(line("يبعُد 1.5كم"))
            add(line("ستربح"))
            addAll(fare)
            add(line("صافي الأرباح بعد الرسوم وضريبة القيمة المضافة"))
            add(line("Al-Rehab - Abdullah Bin Al-Ashhab,"))
            add(line("Near 3253, Awwad Al Qabidi"))
            add(accept)
        }
        return FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400), children = nodes) to accept
    }

    private fun assertTapped(root: FakeNode, frame: FakeNode) {
        val ride = JeenyScreenReader(EngineConfig()) { 0L }.read(root, 2400)!!
        assertEquals(5.84, ride.price!!, 1e-9)
        assertSame("the clickable frame around «قبول العرض» is tapped", frame, ride.acceptNode)
        assertTrue(DecisionEngine.decide(ride, EngineConfig()).accept)
        assertTrue("minutes come from the first time line", ride.minutes == 4 || ride.minutes == 2)
    }

    @Test
    fun riyal_sign_as_a_character() {
        val (root, frame) = card(line("5.84 ⃁"))
        assertTapped(root, frame)
        assertEquals(4, JeenyScreenReader(EngineConfig()) { 0L }.read(root, 2400)!!.minutes)
    }

    @Test
    fun riyal_sign_drawn_as_an_icon_next_to_the_amount() {
        val (root, frame) = card(line("5.84"), line(desc = null))
        assertTapped(root, frame)
    }

    @Test
    fun map_time_labels_above_the_card() {
        // FareSniper takes the first time it meets in reading order, as it does on the road.
        val (root, frame) = card(line("5.84 ⃁"), mapMarkers = true)
        assertTapped(root, frame)
    }

    @Test
    fun a_driver_price_limit_above_the_fare_rejects_with_the_faresniper_reason() {
        val (root, _) = card(line("5.84 ⃁"))
        val ride = JeenyScreenReader(EngineConfig(minPrice = 10.0)) { 0L }.read(root, 2400)!!
        assertEquals("failed_price", DecisionEngine.decide(ride, EngineConfig(minPrice = 10.0)).reason)
    }
}
